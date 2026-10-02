package com.carcan.dashcam

import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LoopManager — gerencia a rotação de arquivos de vídeo e a proteção de clipes.
 *
 * Responsabilidades:
 *  1. Verificar espaço disponível no armazenamento interno antes de cada novo segmento.
 *  2. Excluir automaticamente o arquivo .mp4 comum mais antigo quando o espaço livre
 *     ficar abaixo de [thresholdBytes] (padrão: 3 GB).
 *  3. Mover arquivos protegidos (impacto detectado) para [protectedDir], onde jamais
 *     serão excluídos pela rotina de rotação.
 *
 * Estimativa de consumo de armazenamento:
 *  - Bitrate: 2 Mbps → ~90 MB por segmento de 3 min
 *  - 32 GB interno, limiar 3 GB → ~29 GB usáveis → ~322 segmentos antes de rotacionar
 *
 * @param baseDir      Diretório onde os segmentos comuns são gravados.
 * @param protectedDir Diretório para clipes protegidos (imune à exclusão automática).
 */
class LoopManager(
    private val baseDir: File,
    private val protectedDir: File
) {

    companion object {
        private const val TAG = "LoopManager"

        /** Espaço mínimo livre em bytes antes de acionar a exclusão (3 GB). */
        const val DEFAULT_THRESHOLD_BYTES: Long = 3L * 1024L * 1024L * 1024L
    }

    // ------------------------------------------------------------------
    // Inicialização de diretórios
    // ------------------------------------------------------------------

    init {
        baseDir.mkdirs()
        protectedDir.mkdirs()
    }

    // ------------------------------------------------------------------
    // API pública
    // ------------------------------------------------------------------

    /**
     * Verifica o espaço disponível e exclui os segmentos comuns mais antigos
     * até que o espaço livre seja >= [thresholdBytes].
     *
     * Executado em [Dispatchers.IO] — não bloqueia a thread principal.
     * Chamado antes de cada novo segmento de gravação.
     */
    suspend fun checkAndFreeSpace(thresholdBytes: Long = DEFAULT_THRESHOLD_BYTES) {
        withContext(Dispatchers.IO) {
            var freed = false

            while (true) {
                val available = getAvailableBytes()

                if (available >= thresholdBytes) {
                    if (freed) {
                        Log.d(TAG, "Espaço suficiente após rotação: ${available.toMb()} MB livres.")
                    }
                    break
                }

                Log.w(TAG, "Espaço insuficiente: ${available.toMb()} MB < ${thresholdBytes.toMb()} MB. Rotacionando...")

                val oldestFile = findOldestCommonFile()

                if (oldestFile == null) {
                    Log.e(TAG, "Sem arquivos comuns para excluir. Espaço não pode ser liberado.")
                    break
                }

                val deleted = oldestFile.delete()
                if (deleted) {
                    Log.i(TAG, "Arquivo excluído: ${oldestFile.name}")
                    freed = true
                } else {
                    Log.e(TAG, "Falha ao excluir: ${oldestFile.name}")
                    break
                }
            }
        }
    }

    /**
     * Move [file] para [protectedDir], tornando-o imune à exclusão automática.
     *
     * Usa [File.renameTo] que é atômico dentro do mesmo volume de armazenamento.
     * Retorna o [File] de destino, ou null em caso de falha.
     */
    suspend fun protectFile(file: File): File? = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            Log.w(TAG, "Arquivo a proteger não encontrado: ${file.name}")
            return@withContext null
        }

        protectedDir.mkdirs()
        val destination = File(protectedDir, file.name)

        return@withContext if (file.renameTo(destination)) {
            Log.i(TAG, "Arquivo protegido: ${file.name} → ${protectedDir.name}/${destination.name}")
            destination
        } else {
            // Fallback: copiar e deletar (volumes diferentes ou rename falhou)
            try {
                file.copyTo(destination, overwrite = true)
                file.delete()
                Log.i(TAG, "Arquivo protegido via cópia: ${file.name}")
                destination
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao proteger arquivo ${file.name}: ${e.message}")
                null
            }
        }
    }

    /**
     * Retorna a lista de segmentos no diretório protegido.
     * Útil para exibir contagem na UI.
     */
    fun getProtectedFiles(): List<File> =
        protectedDir.listFiles { f -> f.extension == "mp4" }?.toList() ?: emptyList()

    /**
     * Retorna a lista de segmentos comuns (não protegidos).
     */
    fun getCommonFiles(): List<File> =
        baseDir.listFiles { f -> f.extension == "mp4" && !f.isInProtectedDir() }?.toList() ?: emptyList()

    // ------------------------------------------------------------------
    // Helpers privados
    // ------------------------------------------------------------------

    /** Bytes livres no sistema de arquivos de [baseDir]. */
    private fun getAvailableBytes(): Long {
        val stat = StatFs(baseDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    /**
     * Retorna o arquivo .mp4 mais antigo em [baseDir], excluindo qualquer
     * arquivo que pertença a [protectedDir].
     */
    private fun findOldestCommonFile(): File? {
        return baseDir
            .listFiles { f -> f.extension == "mp4" && !f.isInProtectedDir() }
            ?.minByOrNull { it.lastModified() }
    }

    /** Verifica se este arquivo está dentro do diretório protegido. */
    private fun File.isInProtectedDir(): Boolean {
        return this.canonicalPath.startsWith(protectedDir.canonicalPath)
    }

    /** Converte bytes para MB para logging legível. */
    private fun Long.toMb(): Long = this / (1024L * 1024L)
}

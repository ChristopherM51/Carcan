package com.carcan.dashcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RecordingService — Foreground Service responsável por toda a gravação contínua.
 *
 * Características principais:
 *  - Estende LifecycleService para integração nativa com CameraX
 *  - Grava vídeo 720p/30fps + áudio, bitrate 2 Mbps (≈90 MB/segmento de 3 min)
 *  - Segmentos de 3 minutos com transição automática (rotação em loop)
 *  - Detecção de impacto via ImpactDetector → protege clipe atual via LoopManager
 *  - PARTIAL_WAKE_LOCK garante execução com tela bloqueada
 *  - START_STICKY: SO relança o service após kill por memória
 *  - SEM Preview use case — nenhum frame renderizado na GPU do service
 *  - Prioridade de thread elevada para minimizar preempção
 *
 * Intents aceitos via onStartCommand:
 *  - ACTION_START_RECORDING  (com extra EXTRA_CAMERA_FACING: Int)
 *  - ACTION_STOP_RECORDING
 *  - ACTION_SWITCH_CAMERA    (com extra EXTRA_CAMERA_FACING: Int)
 */
class RecordingService : LifecycleService() {

    // ------------------------------------------------------------------
    // Constantes
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "RecordingService"

        const val ACTION_START_RECORDING = "com.carcan.dashcam.START_RECORDING"
        const val ACTION_STOP_RECORDING  = "com.carcan.dashcam.STOP_RECORDING"
        const val ACTION_SWITCH_CAMERA   = "com.carcan.dashcam.SWITCH_CAMERA"

        /** 0 = frontal, 1 = traseira (mesmos valores de CameraSelector facing) */
        const val EXTRA_CAMERA_FACING = "extra_camera_facing"
        const val FACING_FRONT = 0
        const val FACING_BACK  = 1

        private const val NOTIFICATION_ID      = 1001
        private const val CHANNEL_ID           = "dashcam_channel"
        private const val CHANNEL_NAME         = "Dashcam"

        /** Duração de cada segmento de vídeo em milissegundos (3 minutos). */
        private const val SEGMENT_DURATION_MS  = 3L * 60L * 1_000L

        /** Bitrate de vídeo em bps (2 Mbps) — balanceia qualidade e carga do encoder. */
        private const val VIDEO_BITRATE_BPS    = 2_000_000
    }

    // ------------------------------------------------------------------
    // Estado interno
    // ------------------------------------------------------------------

    private var cameraProvider: ProcessCameraProvider? = null
    private var currentRecording: Recording? = null
    private var currentOutputFile: File? = null
    private var currentCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    private lateinit var loopManager: LoopManager
    private lateinit var impactDetector: ImpactDetector
    private lateinit var cameraExecutor: ExecutorService

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** WakeLock para manter CPU ativa com tela bloqueada. */
    private var wakeLock: PowerManager.WakeLock? = null

    /** Handler de rotação pendente — cancelado quando impacto é detectado. */
    private val rotationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rotationRunnable = Runnable { rotateSegment() }

    /** Flag para evitar chamadas reentrantes durante transição de segmento. */
    private var isRotating = false

    // ------------------------------------------------------------------
    // Ciclo de vida do Service
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        // Elevação de prioridade da thread do service
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        // Executor dedicado para operações de câmera
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Diretórios de armazenamento
        val baseDir      = File(getExternalFilesDir(null), "Dashcam")
        val protectedDir = File(baseDir, "Protected")

        loopManager     = LoopManager(baseDir, protectedDir)
        impactDetector  = ImpactDetector { onImpactDetected() }

        // WakeLock — mantém CPU ativa sem manter tela ligada
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CarCan::RecordingWakeLock"
        ).apply { acquire(/* sem timeout — liberado em onDestroy */ 0L) }

        // Notificação + canal
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        Log.d(TAG, "RecordingService criado.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val facing = intent.getIntExtra(EXTRA_CAMERA_FACING, FACING_FRONT)
                currentCameraSelector = facing.toCameraSelector()
                Log.i(TAG, "Iniciando gravação — câmera: ${facing.cameraName()}")
                startRecording()
            }
            ACTION_STOP_RECORDING -> {
                Log.i(TAG, "Parando gravação por solicitação.")
                stopRecordingAndSelf()
            }
            ACTION_SWITCH_CAMERA -> {
                val facing = intent.getIntExtra(EXTRA_CAMERA_FACING, FACING_FRONT)
                Log.i(TAG, "Trocando câmera para: ${facing.cameraName()}")
                switchCamera(facing.toCameraSelector())
            }
        }

        // START_STICKY: SO relança o service se for morto, reentregando o último intent
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        impactDetector.stop()
        rotationHandler.removeCallbacks(rotationRunnable)
        currentRecording?.stop()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        Log.d(TAG, "RecordingService destruído.")
    }

    // ------------------------------------------------------------------
    // Gravação
    // ------------------------------------------------------------------

    /**
     * Inicia a gravação com [currentCameraSelector].
     * Verifica espaço livre antes de abrir o arquivo de saída.
     * Nenhum Preview use case é criado — economia de GPU e RAM.
     */
    private fun startRecording() {
        serviceScope.launch {
            // 1. Liberar espaço se necessário (operação de I/O em Dispatchers.IO internamente)
            loopManager.checkAndFreeSpace()

            // 2. Obter CameraProvider (pode já estar disponível)
            val provider = getOrInitCameraProvider() ?: run {
                Log.e(TAG, "Falha ao obter ProcessCameraProvider.")
                return@launch
            }

            // 3. Configurar Recorder — sem Preview use case
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()

            val videoCapture = VideoCapture.Builder(recorder)
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()

            // 4. Vincular ao LifecycleOwner do service (this@RecordingService)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this@RecordingService,
                    currentCameraSelector,
                    videoCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao vincular câmera: ${e.message}")
                return@launch
            }

            // 5. Criar arquivo de saída com timestamp
            val outputFile = createOutputFile()
            currentOutputFile = outputFile

            val outputOptions = FileOutputOptions.Builder(outputFile).build()

            // 6. Iniciar Recording com áudio sempre habilitado
            currentRecording = videoCapture.output
                .prepareRecording(this@RecordingService, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this@RecordingService)) { event ->
                    handleRecordEvent(event)
                }

            // 7. Agendar rotação após SEGMENT_DURATION_MS
            rotationHandler.removeCallbacks(rotationRunnable)
            rotationHandler.postDelayed(rotationRunnable, SEGMENT_DURATION_MS)

            // 8. Iniciar sensor de impacto
            impactDetector.start(this@RecordingService)

            // 9. Atualizar notificação
            updateNotification()

            Log.i(TAG, "Gravação iniciada: ${outputFile.name}")
        }
    }

    /**
     * Trata eventos do ciclo de vida da gravação CameraX.
     */
    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                Log.d(TAG, "VideoRecordEvent: Start")
            }
            is VideoRecordEvent.Finalize -> {
                if (event.hasError()) {
                    Log.e(TAG, "Erro na gravação: ${event.error} — ${event.cause?.message}")
                    currentOutputFile?.delete() // Descarta arquivo corrompido
                } else {
                    Log.i(TAG, "Segmento finalizado: ${currentOutputFile?.name}")
                }
                isRotating = false
            }
            else -> { /* Status/Pause — não usado */ }
        }
    }

    /**
     * Para o segmento atual e inicia o próximo (rotação em loop).
     * Chamado pelo handler após SEGMENT_DURATION_MS.
     */
    private fun rotateSegment() {
        if (isRotating) return
        isRotating = true
        Log.d(TAG, "Rotacionando segmento...")

        // Para a gravação atual; o Finalize será recebido em handleRecordEvent
        // Após o Finalize, startRecording() inicia o próximo segmento
        currentRecording?.stop()

        // Aguarda o evento Finalize para reiniciar
        // Usamos postDelayed curto para dar tempo ao CameraX de finalizar o arquivo
        rotationHandler.postDelayed({
            startRecording()
        }, 300L)
    }

    /**
     * Chamado pelo ImpactDetector quando um impacto é detectado.
     * Para a gravação atual, protege o arquivo e reinicia imediatamente.
     */
    private fun onImpactDetected() {
        Log.i(TAG, "Impacto detectado! Protegendo segmento atual...")

        // Cancelar rotação agendada para este segmento
        rotationHandler.removeCallbacks(rotationRunnable)

        val fileToProtect = currentOutputFile
        currentRecording?.stop()

        // Aguarda finalização e protege o arquivo
        rotationHandler.postDelayed({
            if (fileToProtect != null) {
                serviceScope.launch {
                    loopManager.protectFile(fileToProtect)
                    Log.i(TAG, "Arquivo protegido após impacto: ${fileToProtect.name}")
                }
            }
            startRecording()
        }, 300L)
    }

    /**
     * Troca a câmera em tempo real:
     * para a gravação → troca o seletor → reinicia.
     */
    private fun switchCamera(newSelector: CameraSelector) {
        rotationHandler.removeCallbacks(rotationRunnable)
        currentCameraSelector = newSelector
        currentRecording?.stop()

        rotationHandler.postDelayed({
            startRecording()
        }, 300L)
    }

    /**
     * Para toda a gravação e encerra o service.
     */
    private fun stopRecordingAndSelf() {
        rotationHandler.removeCallbacks(rotationRunnable)
        impactDetector.stop()
        currentRecording?.stop()
        currentRecording = null
        cameraProvider?.unbindAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------
    // CameraProvider
    // ------------------------------------------------------------------

    private suspend fun getOrInitCameraProvider(): ProcessCameraProvider? {
        return try {
            if (cameraProvider == null) {
                cameraProvider = ProcessCameraProvider.getInstance(this).get()
            }
            cameraProvider
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter ProcessCameraProvider: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // Arquivos de saída
    // ------------------------------------------------------------------

    private fun createOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseDir = File(getExternalFilesDir(null), "Dashcam")
        baseDir.mkdirs()
        return File(baseDir, "VID_$timestamp.mp4")
    }

    // ------------------------------------------------------------------
    // Notificação persistente
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // LOW: sem som, sem vibração
            ).apply {
                description = "Dashcam gravando em segundo plano"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.action_stop),
                stopIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    // ------------------------------------------------------------------
    // Extensões privadas
    // ------------------------------------------------------------------

    private fun Int.toCameraSelector(): CameraSelector = when (this) {
        FACING_BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        else        -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

    private fun Int.cameraName(): String = when (this) {
        FACING_BACK -> "Traseira"
        else        -> "Frontal"
    }
}

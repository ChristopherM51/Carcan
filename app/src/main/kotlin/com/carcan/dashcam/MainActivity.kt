package com.carcan.dashcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.carcan.dashcam.databinding.ActivityMainBinding

/**
 * MainActivity — painel de controle do Dashcam.
 *
 * Responsabilidades:
 *  - Solicitar permissões em runtime (Câmera, Áudio, Notificações)
 *  - Iniciar / parar RecordingService via Intent
 *  - Trocar câmera durante a gravação (envia Intent ao service)
 *  - Gerenciar Preview opcional: bind/unbind ao ciclo de vida da Activity
 *
 * Importante:
 *  - A Activity e o Service usam ProcessCameraProvider independentes
 *  - onStop() sempre libera o Preview para não bloquear a câmera do service
 *  - O Service usa APENAS VideoCapture — sem Preview — para economizar GPU/RAM
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // ------------------------------------------------------------------
    // ViewBinding
    // ------------------------------------------------------------------

    private lateinit var binding: ActivityMainBinding

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    private var isRecording = false
    private var previewEnabled = false
    private var currentFacing = RecordingService.FACING_FRONT

    private var cameraProvider: ProcessCameraProvider? = null

    // ------------------------------------------------------------------
    // Permissões
    // ------------------------------------------------------------------

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Log.d(TAG, "Todas as permissões concedidas.")
        } else {
            Toast.makeText(
                this,
                getString(R.string.permissions_denied),
                Toast.LENGTH_LONG
            ).show()
            binding.btnRecord.isEnabled = false
        }
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()
        setupListeners()
    }

    override fun onStart() {
        super.onStart()
        // Restaura preview se toggle estava ativo
        if (previewEnabled && allPermissionsGranted()) {
            bindPreview()
        }
    }

    override fun onStop() {
        super.onStop()
        // Libera a câmera obrigatoriamente ao ir para background
        // O RecordingService continua com seu próprio bind independente
        releasePreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePreview()
    }

    // ------------------------------------------------------------------
    // Setup de listeners
    // ------------------------------------------------------------------

    private fun setupListeners() {
        // Botão Iniciar / Parar
        binding.btnRecord.setOnClickListener {
            if (!allPermissionsGranted()) {
                checkAndRequestPermissions()
                return@setOnClickListener
            }
            if (isRecording) {
                stopRecordingService()
            } else {
                startRecordingService()
            }
        }

        // Botão Trocar Câmera
        binding.btnSwitchCamera.setOnClickListener {
            currentFacing = if (currentFacing == RecordingService.FACING_FRONT) {
                RecordingService.FACING_BACK
            } else {
                RecordingService.FACING_FRONT
            }

            // Envia intent ao service para trocar câmera
            val switchIntent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_SWITCH_CAMERA
                putExtra(RecordingService.EXTRA_CAMERA_FACING, currentFacing)
            }
            startService(switchIntent)

            // Atualiza preview local se ativo
            if (previewEnabled) {
                bindPreview()
            }

            updateCameraButtonLabel()
        }

        // Toggle Preview
        binding.switchPreview.setOnCheckedChangeListener { _, isChecked ->
            previewEnabled = isChecked
            if (isChecked) {
                if (allPermissionsGranted()) {
                    binding.previewView.visibility = View.VISIBLE
                    bindPreview()
                } else {
                    binding.switchPreview.isChecked = false
                    checkAndRequestPermissions()
                }
            } else {
                binding.previewView.visibility = View.GONE
                releasePreview()
            }
        }
    }

    // ------------------------------------------------------------------
    // Controle do RecordingService
    // ------------------------------------------------------------------

    private fun startRecordingService() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
            putExtra(RecordingService.EXTRA_CAMERA_FACING, currentFacing)
        }
        ContextCompat.startForegroundService(this, intent)

        isRecording = true
        updateRecordingUI()
        Log.i(TAG, "RecordingService iniciado.")
    }

    private fun stopRecordingService() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }
        startService(intent)

        isRecording = false
        updateRecordingUI()
        Log.i(TAG, "RecordingService parado.")
    }

    // ------------------------------------------------------------------
    // Preview (gerenciado apenas pela Activity)
    // ------------------------------------------------------------------

    private fun bindPreview() {
        val cameraSelector = if (currentFacing == RecordingService.FACING_BACK) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview)
                Log.d(TAG, "Preview vinculado.")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao vincular preview: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun releasePreview() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        Log.d(TAG, "Preview liberado.")
    }

    // ------------------------------------------------------------------
    // Atualização de UI
    // ------------------------------------------------------------------

    private fun updateRecordingUI() {
        if (isRecording) {
            binding.btnRecord.text = getString(R.string.btn_stop)
            binding.btnRecord.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.btn_stop)
            binding.btnSwitchCamera.isEnabled = true
            binding.tvStatus.text = getString(R.string.status_recording)
        } else {
            binding.btnRecord.text = getString(R.string.btn_start)
            binding.btnRecord.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.btn_record)
            binding.btnSwitchCamera.isEnabled = false
            binding.tvStatus.text = getString(R.string.status_stopped)
        }
    }

    private fun updateCameraButtonLabel() {
        val label = if (currentFacing == RecordingService.FACING_FRONT) {
            getString(R.string.camera_front)
        } else {
            getString(R.string.camera_back)
        }
        binding.btnSwitchCamera.text = label
    }

    // ------------------------------------------------------------------
    // Permissões
    // ------------------------------------------------------------------

    private fun allPermissionsGranted(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

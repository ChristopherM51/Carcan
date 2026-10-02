package com.carcan.dashcam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

/**
 * ImpactDetector — monitora o acelerômetro nativo do dispositivo e detecta
 * colisões ou frenagens bruscas acima de um limiar configurável.
 *
 * Limitações do Redmi 8A / Snapdragon 439 consideradas:
 *  - Usa TYPE_ACCELEROMETER (raw) em vez de TYPE_LINEAR_ACCELERATION para
 *    evitar dependência de sensor fusion que pode ser imprecisa no hardware.
 *  - SENSOR_DELAY_GAME (~20 ms) oferece resposta rápida sem sobrecarregar a CPU.
 *  - Debounce de 3 s evita múltiplas proteções de arquivo para o mesmo evento.
 */
class ImpactDetector(
    private val onImpactListener: OnImpactListener
) : SensorEventListener {

    // ------------------------------------------------------------------
    // Interface de callback
    // ------------------------------------------------------------------

    fun interface OnImpactListener {
        fun onImpactDetected()
    }

    // ------------------------------------------------------------------
    // Constantes
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "ImpactDetector"

        /**
         * Limiar de magnitude do vetor aceleração total (m/s²).
         * O vetor inclui a gravidade (~9,8 m/s²); um valor de 15 m/s² representa
         * uma aceleração total considerável, equivalente a ~0,5 G acima da gravidade.
         * Ajuste conforme necessidade de sensibilidade.
         */
        const val THRESHOLD_MS2 = 15f

        /** Intervalo mínimo entre dois eventos de impacto detectados (ms). */
        const val DEBOUNCE_MS = 3_000L
    }

    // ------------------------------------------------------------------
    // Estado interno
    // ------------------------------------------------------------------

    private var sensorManager: SensorManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Timestamp do último impacto detectado (SystemClock.elapsedRealtime). */
    private var lastImpactTime = 0L

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    /**
     * Registra o listener do acelerômetro.
     * Deve ser chamado quando o RecordingService iniciar a gravação.
     */
    fun start(context: Context) {
        if (sensorManager != null) return // já iniciado

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer == null) {
            Log.w(TAG, "Acelerômetro não disponível neste dispositivo.")
            return
        }

        sm.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager = sm
        Log.d(TAG, "ImpactDetector iniciado. Limiar: $THRESHOLD_MS2 m/s²")
    }

    /**
     * Desregistra o listener e libera recursos.
     * Deve ser chamado em RecordingService.onDestroy().
     */
    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        mainHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "ImpactDetector parado.")
    }

    // ------------------------------------------------------------------
    // SensorEventListener
    // ------------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Magnitude total do vetor aceleração (inclui gravidade)
        val magnitude = sqrt(x * x + y * y + z * z)

        if (magnitude > THRESHOLD_MS2) {
            val now = SystemClock.elapsedRealtime()

            // Debounce: ignora eventos em janela de 3 s após o último impacto
            if (now - lastImpactTime < DEBOUNCE_MS) return

            lastImpactTime = now
            Log.i(TAG, "Impacto detectado! Magnitude: ${"%.2f".format(magnitude)} m/s²")

            // Notifica no main thread para garantir thread-safety com CameraX
            mainHandler.post { onImpactListener.onImpactDetected() }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Não é necessário reagir a mudanças de precisão do acelerômetro
    }
}

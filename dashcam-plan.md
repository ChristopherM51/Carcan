# Plano: Aplicativo Dashcam Android (Redmi 8A)

## Visão Geral

Criar do zero um aplicativo Android de dashcam em Kotlin, otimizado para o Xiaomi Redmi 8A
(Snapdragon 439, 2 GB RAM, 32 GB armazenamento interno). O app usa CameraX para gravação
contínua em loop de segmentos de 3 minutos em 720p/30fps com áudio sempre habilitado,
detecção de impacto via acelerômetro, proteção automática de clipes e execução contínua via
Foreground Service — incluindo com tela bloqueada.

**Restrição de hardware confirmada:** O Snapdragon 439 possui um único ISP (Spectra 150),
impossibilitando gravação simultânea de câmera frontal e traseira. A arquitetura usa **uma
câmera por vez** — frontal por padrão, com alternância via botão (para + troca + reinicia).

**Decisões de design confirmadas:**
- Câmera **frontal** como padrão; traseira disponível via botão, nunca simultâneas
- **Preview opcional** — toggle na UI para ligar/desligar; desligado por padrão economiza RAM/GPU
- **Áudio sempre gravado** — `withAudioEnabled()` em todos os segmentos
- **Sem preview quando minimizado** — Activity libera o bind ao ir para background
- Armazenamento: `getExternalFilesDir("Dashcam")` — sem `WRITE_EXTERNAL_STORAGE` no Android 10+
- Sem BootReceiver — inicialização manual pelo usuário
- `minSdk 26`, `targetSdk 34`
- **Prioridade máxima de processo** — WakeLock, foreground service type camera+microphone,
  thread priority elevada no service

---

## Arquitetura Geral

```
MainActivity (painel de controle)
  ├── Toggle Preview → bind/unbind Preview use case via ProcessCameraProvider
  ├── Botão Iniciar/Parar → startForegroundService / stopService
  └── Botão Trocar Câmera → Intent ACTION_SWITCH_CAMERA ao RecordingService

RecordingService (LifecycleService — Foreground)
  ├── ProcessCameraProvider → VideoCapture<Recorder> com áudio (sem Preview)
  ├── LoopManager → segmentos 3 min, verificação de espaço, exclusão do mais antigo
  ├── ImpactDetector → acelerômetro > 15 m/s², protege clipe atual
  ├── WakeLock (PARTIAL_WAKE_LOCK) → CPU ativa com tela bloqueada
  └── Notificação persistente → canal dashcam_channel, ação "Parar"
```

**Fluxo de segmentos:**
```
[Iniciar] → startRecording() → grava 3 min → rotateSegment()
                                    ↓
                          checkAndFreeSpace() → apaga mais antigo se < 3 GB livres
                                    ↓
                              startRecording() → ...loop...

[Impacto] → para segmento atual → protectFile() → startRecording()
```

---

## Otimizações de Recursos (2 GB RAM / Snapdragon 439)

| Medida | Impacto |
|---|---|
| Sem Preview use case no service | -80 a 120 MB RAM / -GPU load |
| Preview desligado por padrão na Activity | -40 MB quando em foreground |
| Bitrate de vídeo: 2 Mbps (720p) | ~90 MB por segmento de 3 min |
| `SENSOR_DELAY_GAME` no acelerômetro | Equilíbrio entre resposta e CPU |
| Coroutines `Dispatchers.IO` para I/O de disco | Não bloqueia thread principal |
| `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)` no service | Menos preempção |
| Limiar LoopManager: 3 GB livres | Conservador para 32 GB total |
| Segmentos de 3 min a 2 Mbps ≈ 90 MB cada | Máx ~29 segmentos comuns antes da rotação |

---

## Sub-Tarefa 1 — Estrutura do Projeto e Configuração Gradle

**Status:** [ ] pendente

### Intent
Criar o esqueleto completo do projeto Android Gradle (estrutura de diretórios e arquivos de
configuração) que é a base de toda compilação subsequente. Sem isso nenhum código pode ser
compilado ou instalado.

### Expected Outcomes
- Árvore de diretórios completa criada no workspace
- `settings.gradle.kts` e `build.gradle.kts` (raiz e módulo app) corretos
- `gradle/wrapper/gradle-wrapper.properties` apontando para Gradle 8.x compatível com AGP 8.x
- Projeto compila com `./gradlew assembleDebug` sem erros

### Todo List
1. Criar a árvore de diretórios:
   - `app/src/main/kotlin/com/carcan/dashcam/`
   - `app/src/main/res/layout/`
   - `app/src/main/res/values/`
   - `app/src/main/res/drawable/`
   - `app/src/main/res/mipmap-anydpi-v26/`
   - `app/src/main/res/xml/`
   - `gradle/wrapper/`
2. Escrever `settings.gradle.kts` com `rootProject.name = "CarCan"` e inclusão do módulo `app`
3. Escrever `build.gradle.kts` (raiz) com plugins `com.android.application` e `org.jetbrains.kotlin.android` aplicados como `apply false`
4. Escrever `app/build.gradle.kts` com:
   - `compileSdk 34`, `minSdk 26`, `targetSdk 34`
   - `applicationId "com.carcan.dashcam"`
   - `kotlinOptions.jvmTarget = "17"`
   - Dependências: CameraX (`camera-camera2:1.3.4`, `camera-lifecycle:1.3.4`, `camera-video:1.3.4`), `lifecycle-service:2.8.x`, AndroidX Core KTX, Coroutines Android
5. Escrever `gradle/wrapper/gradle-wrapper.properties` com `distributionUrl` para Gradle 8.7
6. Criar `local.properties` com instrução de preenchimento do `sdk.dir`
7. Criar `.gitignore` padrão Android

### Relevant Context
- Dependências CameraX: `androidx.camera:camera-camera2`, `camera-lifecycle`, `camera-video` — versão 1.3.4
- `lifecycle-service` necessário para `LifecycleService` no `RecordingService`
- Kotlin Coroutines: `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.x`
- AGP 8.x requer Java 17 toolchain

---

## Sub-Tarefa 2 — AndroidManifest.xml

**Status:** [ ] pendente

### Intent
Declarar todas as permissões, componentes e metadados necessários para o app funcionar
corretamente no Redmi 8A, incluindo execução com tela bloqueada e foreground service de câmera.

### Expected Outcomes
- Todas as permissões declaradas corretamente
- `RecordingService` declarado com `foregroundServiceType="camera|microphone"`
- `MainActivity` configurada para landscape e tela sempre ligada
- Nenhuma permissão faltando que cause `SecurityException` em runtime

### Todo List
1. Declarar permissões no `<manifest>`:
   - `android.permission.CAMERA`
   - `android.permission.RECORD_AUDIO`
   - `android.permission.FOREGROUND_SERVICE`
   - `android.permission.FOREGROUND_SERVICE_CAMERA`
   - `android.permission.FOREGROUND_SERVICE_MICROPHONE`
   - `android.permission.WAKE_LOCK`
   - `android.permission.POST_NOTIFICATIONS` (Android 13+)
2. Declarar `<uses-feature android:name="android.hardware.camera" android:required="false"/>`
3. Declarar `RecordingService` com:
   - `android:foregroundServiceType="camera|microphone"`
   - `android:stopWithTask="false"` — service sobrevive ao swipe do app
4. Configurar `MainActivity`:
   - `android:screenOrientation="landscape"`
   - `android:configChanges="orientation|screenSize"` — evita recreação desnecessária
5. Configurar `<application>` com `android:largeHeap="false"` — não solicitar heap extra, manter footprint mínimo

### Relevant Context
- `FOREGROUND_SERVICE_CAMERA` e `FOREGROUND_SERVICE_MICROPHONE` são permissões `normal` (não runtime) — basta declarar no manifest
- `foregroundServiceType="camera|microphone"` é obrigatório no Android 14+ para usar câmera/microfone em foreground service
- `stopWithTask="false"` garante que o service continue gravando mesmo após swipe na recents list

---

## Sub-Tarefa 3 — ImpactDetector.kt

**Status:** [ ] pendente

### Intent
Implementar o monitor de acelerômetro isolado e reutilizável que detecta impactos e notifica
o service via callback. É completamente independente de câmera e pode ser iniciado/parado
separadamente.

### Expected Outcomes
- Classe `ImpactDetector` com `SensorManager` registrando `TYPE_ACCELEROMETER`
- Limiar de 15 m/s² calculado sobre a magnitude do vetor aceleração total
- Debounce de 3 segundos entre eventos para evitar múltiplas notificações do mesmo impacto
- Callback `onImpactDetected()` invocado na main thread
- `start()` / `stop()` gerenciam o ciclo de vida do sensor

### Todo List
1. Criar `app/src/main/kotlin/com/carcan/dashcam/ImpactDetector.kt`
2. Definir interface funcional `OnImpactListener` com `fun onImpactDetected()`
3. Implementar `SensorEventListener.onSensorChanged`:
   - Calcular magnitude: `sqrt(x² + y² + z²)`
   - Limiar: magnitude > `THRESHOLD_MS2` (constante = 15f)
   - Debounce: checar `SystemClock.elapsedRealtime() - lastImpactTime > DEBOUNCE_MS` (3000L)
   - Postar callback na main thread via `Handler(Looper.getMainLooper())`
4. Implementar `start(context: Context)`: obter `SensorManager`, registrar listener com `SENSOR_DELAY_GAME`
5. Implementar `stop()`: desregistrar listener, limpar referências
6. Usar `SENSOR_DELAY_GAME` (20ms de intervalo) — responsivo sem sobrecarregar o Snapdragon 439

### Relevant Context
- Magnitude bruta de 15 m/s² já é um limiar alto o suficiente para distinguir frenagem brusca/colisão de solavancos normais
- `SENSOR_DELAY_GAME` = ~20ms de latência — suficiente para colisões sem drenar CPU
- Não usar `TYPE_LINEAR_ACCELERATION` pois requer sensor fusion (fusão de giroscópio) que pode não estar disponível ou ser impreciso no Redmi 8A

---

## Sub-Tarefa 4 — LoopManager.kt

**Status:** [ ] pendente

### Intent
Implementar a lógica de rotação e proteção de arquivos: excluir automaticamente o vídeo comum
mais antigo quando o espaço livre cair abaixo de 3 GB, e mover arquivos protegidos para pasta
segura imune à exclusão automática.

### Expected Outcomes
- `LoopManager` recebe `baseDir` e `protectedDir` no construtor
- `checkAndFreeSpace()` libera espaço apagando arquivos comuns mais antigos até atingir o limiar
- `protectFile(file)` move o arquivo para `protectedDir` de forma segura
- Arquivos em `protectedDir` nunca são tocados pelo processo de rotação
- Toda operação de I/O executada em `Dispatchers.IO`

### Todo List
1. Criar `app/src/main/kotlin/com/carcan/dashcam/LoopManager.kt`
2. Construtor: `class LoopManager(private val baseDir: File, private val protectedDir: File)`
3. Implementar `suspend fun checkAndFreeSpace(thresholdBytes: Long = 3L * 1024 * 1024 * 1024)`:
   - `withContext(Dispatchers.IO)` para todo o bloco
   - Usar `StatFs(baseDir.absolutePath)` para obter `availableBytes`
   - Enquanto `availableBytes < thresholdBytes`:
     - Listar `baseDir.listFiles()` filtrando apenas `.mp4`, excluindo qualquer arquivo dentro de `protectedDir`
     - Ordenar por `lastModified()` crescente (mais antigo primeiro)
     - Se lista vazia: break (nada mais a apagar)
     - Deletar o mais antigo e reatualizar `StatFs`
4. Implementar `suspend fun protectFile(file: File)`:
   - `withContext(Dispatchers.IO)`
   - Criar `protectedDir` se não existir: `protectedDir.mkdirs()`
   - Mover com `file.renameTo(File(protectedDir, file.name))` — atômico no mesmo filesystem
   - Retornar o `File` de destino
5. Criar constante `THRESHOLD_BYTES` como valor padrão (3 GB)

### Relevant Context
- `getExternalFilesDir("Dashcam")` retorna algo como `/sdcard/Android/data/com.carcan.dashcam/files/Dashcam`
- `StatFs` deve ser reinicializado a cada verificação para leitura atualizada do espaço
- `renameTo` funciona atomicamente se origem e destino estão no mesmo volume — que é o caso aqui
- A 2 Mbps, cada segmento de 3 min ocupa ~90 MB; 3 GB livres = ~33 segmentos de buffer

---

## Sub-Tarefa 5 — RecordingService.kt

**Status:** [ ] pendente

### Intent
Implementar o coração do app: Foreground Service que gerencia todo o ciclo de vida da gravação
com CameraX, coordena os segmentos em loop, responde a impactos e mantém execução contínua
com tela bloqueada. É o componente mais crítico do sistema.

### Expected Outcomes
- `RecordingService` estende `LifecycleService`, rodando como foreground service tipo camera+microphone
- Grava vídeo em 720p/30fps com áudio sempre habilitado, bitrate ~2 Mbps
- Segmentos de 3 minutos com transição automática (gap mínimo entre segmentos)
- Detecção de impacto: para segmento atual, protege arquivo, recomeça imediatamente
- `checkAndFreeSpace()` executado antes de cada novo segmento
- `PARTIAL_WAKE_LOCK` mantém CPU ativa com tela bloqueada
- Troca de câmera via Intent: para gravação, troca `CameraSelector`, reinicia
- Sem `Preview` use case — nenhum frame renderizado na GPU do service
- Thread do service com prioridade elevada (`THREAD_PRIORITY_URGENT_AUDIO`)

### Todo List
1. Criar `app/src/main/kotlin/com/carcan/dashcam/RecordingService.kt` estendendo `LifecycleService`
2. Em `onCreate()`:
   - Criar canal de notificação `dashcam_channel` (se Android 8+)
   - Chamar `startForeground(NOTIFICATION_ID, buildNotification())`
   - Adquirir `PARTIAL_WAKE_LOCK` via `PowerManager`
   - Instanciar `LoopManager` com `baseDir` e `protectedDir`
   - Instanciar `ImpactDetector` com callback `::onImpactDetected`
   - Definir `Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)`
3. Em `onStartCommand()`:
   - Tratar `ACTION_START_RECORDING`: iniciar `ImpactDetector.start()`, chamar `startRecording(cameraSelector)`
   - Tratar `ACTION_STOP_RECORDING`: chamar `stopRecording()`, depois `stopSelf()`
   - Tratar `ACTION_SWITCH_CAMERA`: chamar `switchCamera(newSelector)`
   - Retornar `START_STICKY` — SO reinicia o service se for morto
4. Implementar `private fun startRecording(cameraSelector: CameraSelector)`:
   - `ProcessCameraProvider.getInstance(this).get()` (síncrono OK pois já em background thread)
   - Configurar `Recorder` com `QualitySelector.from(Quality.HD)` e `setTargetVideoEncodingBitRate(2_000_000)`
   - Configurar `VideoCapture.withOutput(recorder)`
   - `cameraProvider.bindToLifecycle(this, cameraSelector, videoCapture)` — **sem Preview**
   - Criar `FileOutputOptions` com nome `VID_yyyyMMdd_HHmmss.mp4` em `baseDir`
   - Iniciar `Recording` com `.withAudioEnabled()` e listener de eventos
   - Agendar rotação: `Handler(mainLooper).postDelayed(::rotateSegment, SEGMENT_DURATION_MS)`
5. Implementar `private fun rotateSegment()`:
   - Parar `currentRecording.stop()`
   - No callback `VideoRecordEvent.Finalize`: lançar coroutine `Dispatchers.IO` para `checkAndFreeSpace()`
   - Após space check: chamar `startRecording(currentCameraSelector)`
6. Implementar `private fun onImpactDetected()`:
   - Cancelar handler de rotação pendente
   - Parar `currentRecording.stop()`
   - No `Finalize`: chamar `loopManager.protectFile(currentFile)` e depois `startRecording()`
7. Implementar `private fun switchCamera(newSelector: CameraSelector)`:
   - Parar gravação atual → no `Finalize`: `cameraProvider.unbindAll()`, setar `currentCameraSelector = newSelector`, chamar `startRecording()`
8. Implementar `private fun stopRecording()`:
   - Cancelar handler de rotação
   - `currentRecording?.stop()`
   - `ImpactDetector.stop()`
   - Liberar `cameraProvider.unbindAll()`
9. Em `onDestroy()`: liberar WakeLock, cancelar coroutines scope
10. `buildNotification()`: ícone `ic_notification`, texto "Dashcam ativa", ação PendingIntent "Parar" → `ACTION_STOP_RECORDING`

### Relevant Context
- `LifecycleService` de `androidx.lifecycle:lifecycle-service` provê `LifecycleOwner` necessário para `bindToLifecycle()`
- `START_STICKY` garante que o SO relance o service após kill por memória — crítico para app de dashcam
- `PARTIAL_WAKE_LOCK` mantém CPU ativa sem manter tela ligada — correto para uso com tela bloqueada
- O gap entre `stop()` e o início do próximo `startRecording()` é minimizado processando tudo dentro do callback `Finalize`
- `setTargetVideoEncodingBitRate(2_000_000)` = 2 Mbps — reduz carga do encoder MediaCodec no Snapdragon 439

---

## Sub-Tarefa 6 — MainActivity.kt e Layout

**Status:** [ ] pendente

### Intent
Implementar o painel de controle mínimo: botões iniciar/parar, trocar câmera, toggle de
preview e indicadores de status. O preview é opcional e gerenciado inteiramente pela Activity
— o service nunca renderiza frames.

### Expected Outcomes
- `MainActivity` com layout landscape: `PreviewView` fullscreen (visível apenas com toggle ativo) + overlay de controles
- Botão Iniciar/Parar que inicia/para o `RecordingService`
- Botão Trocar Câmera (habilitado somente durante gravação)
- Toggle Preview que liga/desliga o bind do `Preview` use case na Activity
- Permissões solicitadas em runtime: `CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`
- `onStop()` sempre desliga o preview (unbind) independente do toggle
- `onStart()` re-liga o preview se toggle estiver ativo

### Todo List
1. Criar `app/src/main/kotlin/com/carcan/dashcam/MainActivity.kt`
2. Criar `app/src/main/res/layout/activity_main.xml`:
   - `ConstraintLayout` raiz com `PreviewView` fullscreen + visibilidade `GONE` por padrão
   - Overlay semi-transparente no bottom com: botão Iniciar/Parar, botão Trocar Câmera, Switch "Preview", TextView de status
3. Implementar `requestPermissions()` via `ActivityResultLauncher` para `CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`
4. Implementar `bindPreview(cameraSelector: CameraSelector)`:
   - Usa `ProcessCameraProvider` com apenas o use case `Preview`
   - Vincula ao `previewView.surfaceProvider`
   - Chamado apenas se toggle de preview está ON
5. Em `onStart()`: se preview toggle ON e permissões concedidas → `bindPreview()`
6. Em `onStop()`: `cameraProvider?.unbindAll()` — garante que Activity nunca consome câmera em background
7. Botão Iniciar: `startForegroundService(Intent(ACTION_START_RECORDING))`, atualizar UI
8. Botão Parar: `startService(Intent(ACTION_STOP_RECORDING))`, atualizar UI
9. Botão Trocar Câmera: alternar `currentCameraFacing`, enviar `Intent(ACTION_SWITCH_CAMERA)` com extra `EXTRA_CAMERA_FACING`; se preview ativo → rebind com nova câmera
10. Toggle Preview: se ON → `bindPreview()` e `previewView.visibility = VISIBLE`; se OFF → `cameraProvider?.unbindAll()` e `previewView.visibility = GONE`
11. Observar status do service via `BroadcastReceiver` local (ou `ServiceConnection`) para atualizar o TextView de status

### Relevant Context
- A Activity e o Service usam `ProcessCameraProvider` **independentes** via seus próprios `LifecycleOwner`
- Quando a Activity vai para background (`onStop`), ela libera o `Preview` use case — o service continua com seu `VideoCapture` sem interferência
- O toggle de preview desligado por padrão preserva ~40 MB de RAM e reduz calor em uso prolongado

---

## Sub-Tarefa 7 — Recursos, Ícones e README de Build

**Status:** [ ] pendente

### Intent
Adicionar todos os recursos de compilação obrigatórios (strings, ícones, cores) e escrever
o README completo com instruções de build, instalação e debug via terminal do VS Code para
o Redmi 8A em modo desenvolvedor.

### Expected Outcomes
- Ícone de notificação monocromo `ic_notification.xml` (vector drawable branco)
- Ícone launcher adaptativo (XML) + fallback PNG simples
- `strings.xml`, `colors.xml` completos
- `README.md` com todos os comandos necessários para compilar, instalar e debugar

### Todo List
1. Criar `app/src/main/res/drawable/ic_notification.xml` — vector drawable câmera monocromo branco
2. Criar `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` com adaptive icon
3. Criar `app/src/main/res/drawable/ic_launcher_foreground.xml` — ícone foreground do adaptive icon
4. Criar `app/src/main/res/drawable/ic_launcher_background.xml` — fundo do adaptive icon
5. Criar `app/src/main/res/values/strings.xml` com todas as strings usadas no app
6. Criar `app/src/main/res/values/colors.xml` com paleta mínima (fundo escuro, botão vermelho)
7. Criar `app/src/main/res/values/themes.xml` com tema `Theme.AppCompat` ou `Theme.Material3`
8. Escrever `README.md` com seções:
   - Pré-requisitos: Java 17 JDK, Android SDK (API 34 + Build Tools 34), ADB
   - Configurar `local.properties` com `sdk.dir=C:\\Users\\...\\AppData\\Local\\Android\\Sdk`
   - Dar permissão de execução ao gradlew: `chmod +x gradlew` (Linux/Mac) ou usar `gradlew.bat` (Windows)
   - Compilar: `./gradlew assembleDebug`
   - Instalar: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   - Habilitar USB Debugging no Redmi 8A: Configurações → Sobre → MIUI → toque 7x → Opções do desenvolvedor → Depuração USB
   - Verificar dispositivo conectado: `adb devices`
   - Instalar e abrir direto: `adb install -r <apk> && adb shell am start -n com.carcan.dashcam/.MainActivity`
   - Monitorar logs: `adb logcat -s "RecordingService" "ImpactDetector" "LoopManager" "CameraX"`
   - Verificar uso de memória: `adb shell dumpsys meminfo com.carcan.dashcam`

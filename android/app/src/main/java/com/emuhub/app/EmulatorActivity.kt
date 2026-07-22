package com.emuhub.app

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.input.InputManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.emuhub.app.data.repo.SettingsRepository
import com.emuhub.app.util.EmuHubLogger
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Activity que executa um jogo usando um core libretro nativamente.
 */
class EmulatorActivity : ComponentActivity() {

    companion object {
        private const val TAG = "EmulatorActivity"
        const val EXTRA_CORE_PATH = "core_path"
        const val EXTRA_ROM_PATH = "rom_path"

        init {
            System.loadLibrary("emuhub_emulator")
        }

        fun createIntent(context: Context, corePath: String, romPath: String): Intent {
            return Intent(context, EmulatorActivity::class.java).apply {
                putExtra(EXTRA_CORE_PATH, corePath)
                putExtra(EXTRA_ROM_PATH, romPath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private var glSurfaceView: GLSurfaceView? = null
    private var touchView: TouchOverlayView? = null
    private var corePath: String = ""
    private var romPath: String = ""
    private var audioTrack: AudioTrack? = null
    private var running = false
    private var gameLoaded = false
    private var showSavePanel = false
    private val saveSlots = 5  // 5 save slots (1-5) + auto-save (slot 0)

    // ─── N64 mode: layout diferente dos botões ───
    private var isN64 = false
    // Estado do D-Pad gerado pelo analógico (fallback pra quando RETRO_DEVICE_ANALOG não é consultado)
    private var analogDpadUp = false
    private var analogDpadDown = false
    private var analogDpadLeft = false
    private var analogDpadRight = false

    // ─── Gamepad físico ───
    private var inputManager: InputManager? = null
    private var autoHideTouchOverlay = true
    // Estado do D-pad vindo dos eixos (HAT / analógico esquerdo): -1, 0, 1
    private var axisDpadX = 0
    private var axisDpadY = 0

    // SELECT+START segurados juntos por EXIT_HOLD_MS = sair do jogo
    private var selectPressed = false
    private var startPressed = false
    private val EXIT_HOLD_MS = 1000L
    private val exitHandler = Handler(Looper.getMainLooper())
    private val exitRunnable = Runnable {
        EmuHubLogger.i(TAG, "SELECT+START segurados: saindo do jogo")
        finish()
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateTouchOverlayVisibility()
        override fun onInputDeviceRemoved(deviceId: Int) = updateTouchOverlayVisibility()
        override fun onInputDeviceChanged(deviceId: Int) = updateTouchOverlayVisibility()
    }

    // Native methods
    private external fun nativeLoadCore(corePath: String): Boolean
    private external fun nativeLoadGame(romPath: String): Boolean
    private external fun nativeRunFrame()
    private external fun nativeGetFBWidth(): Int
    private external fun nativeGetFBHeight(): Int
    private external fun nativeGetFrameBuffer(buf: IntArray): Boolean
    private external fun nativeSetButton(id: Int, pressed: Boolean)
    private external fun nativeSetAnalog(index: Int, axis: Int, value: Int)
    private external fun nativeReset()
    private external fun nativeUnload()
    private external fun nativeGetFPS(): Double
    private external fun nativeGetSampleRate(): Int
    private external fun nativeReadAudio(buf: ShortArray): Int
    private external fun nativeSetSystemDir(dir: String)
    private external fun nativeSetCrashLogPath(path: String)
    private external fun nativeGLContextCreated()
    private external fun nativeIsHwRender(): Boolean
    private external fun nativeSetScreenSize(w: Int, h: Int)
    private external fun nativeSetLayoutMode(mode: Int)
    private external fun nativeGetLayoutMode(): Int
    private external fun nativeGetHwTextureId(): Int
    private external fun nativeGetSerializeSize(): Long
    private external fun nativeSerialize(buf: ByteArray): Boolean
    private external fun nativeUnserialize(buf: ByteArray): Boolean
    private external fun nativeSetSaveDir(dir: String)
    private external fun nativeGetSram(): ByteArray?
    private external fun nativeSetSram(buf: ByteArray): Boolean

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        corePath = intent?.getStringExtra(EXTRA_CORE_PATH) ?: ""
        romPath = intent?.getStringExtra(EXTRA_ROM_PATH) ?: ""

        if (corePath.isEmpty() || romPath.isEmpty()) {
            EmuHubLogger.e(TAG, "Missing core_path or rom_path")
            finish()
            return
        }

        // Fullscreen imersivo + manter tela ligada
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Inicializa JNI log bridge (logs C++ vão pro arquivo)
        EmuHubLogger.i(TAG, "EmuActivity onCreate: iniciando...")

        // Signal handler nativo: SIGSEGV/SIGABRT gravam nesse arquivo antes
        // do processo morrer (o UncaughtExceptionHandler Java não captura)
        try {
            val logDir = EmuHubLogger.getLogPath()?.let { File(it).parentFile }
                ?: File(filesDir, "emuhub-logs").apply { mkdirs() }
            val crashLog = File(logDir, "emuhub-native-crash.log")
            nativeSetCrashLogPath(crashLog.absolutePath)
            EmuHubLogger.i(TAG, "Crash handler nativo: ${crashLog.absolutePath}")
        } catch (e: Throwable) {
            EmuHubLogger.e(TAG, "Falha ao instalar crash handler nativo", e)
        }

        EmuHubLogger.i(TAG, "Antes filesDir...")
        val sysDir: java.io.File
        try {
            sysDir = File(filesDir, "system").apply { mkdirs() }
            EmuHubLogger.i(TAG, "mkdirs OK: ${sysDir.absolutePath}")
        } catch (e: Throwable) {
            EmuHubLogger.e(TAG, "Falha mkdirs", e)
            finish()
            return
        }

        EmuHubLogger.i(TAG, "Antes nativeSetSystemDir...")
        try {
            nativeSetSystemDir(sysDir.absolutePath)
            EmuHubLogger.i(TAG, "nativeSetSystemDir OK")
        } catch (e: Throwable) {
            EmuHubLogger.e(TAG, "Falha nativeSetSystemDir", e)
            finish()
            return
        }

        // Setup save directory
        try {
            val saveDir = File(filesDir, "saves").apply { mkdirs() }
            nativeSetSaveDir(saveDir.absolutePath)
            EmuHubLogger.i(TAG, "Save dir: ${saveDir.absolutePath}")
        } catch (e: Throwable) {
            EmuHubLogger.e(TAG, "Falha nativeSetSaveDir", e)
        }

        // Load core
        EmuHubLogger.i(TAG, "Loading core: $corePath")
        val coreOk = try {
            nativeLoadCore(corePath)
        } catch (e: Throwable) {
            EmuHubLogger.e(TAG, "EXCEPTION loading core", e)
            false
        }
        if (!coreOk) {
            EmuHubLogger.e(TAG, "Failed to load core: $corePath")
            finish()
            return
        }

        // Load game
        EmuHubLogger.i(TAG, "Loading game ROM: $romPath")
        val gameOk = try {
            nativeLoadGame(romPath)
        } catch (e: Throwable) {
            EmuHubLogger.e(TAG, "EXCEPTION loading game", e)
            false
        }
        if (!gameOk) {
            EmuHubLogger.e(TAG, "Failed to load ROM: $romPath")
            nativeUnload()
            finish()
            return
        }
        EmuHubLogger.i(TAG, "Core + game loaded OK, setting up GL...")
        gameLoaded = true

        // Try to load auto-save
        try { autoLoad() } catch (e: Throwable) {
            EmuHubLogger.w(TAG, "Auto-load failed (may be first play): ${e.message}")
        }

        // Dual-screen cores (3DS/NDS): side-by-side layout
        val coreLower = corePath.lowercase()
        if (coreLower.contains("citra") || coreLower.contains("melonds") || coreLower.contains("desmume")) {
            nativeSetLayoutMode(1)
            EmuHubLogger.i(TAG, "Dual-screen core detected, layout=side-by-side")
        } else {
            nativeSetLayoutMode(0)
        }

        // N64: botões A/B trocados no libretro, precisa de analógico virtual
        isN64 = coreLower.contains("mupen64")

        // Setup GL surface — retro_run() roda dentro de onDrawFrame() (GL
        // thread), pois cores com HW render (Panda3DS/Citra GL) chamam gl*()
        // e precisam do contexto EGL current na thread do retro_run()
        glSurfaceView = GLSurfaceView(this)
        // GLES3: cores HW (Citra) pedem OPENGLES3 no SET_HW_RENDER; nossos
        // shaders ES2 continuam válidos num contexto ES3
        glSurfaceView!!.setEGLContextClientVersion(3)
        // Core HW renderiza direto na surface da janela: precisa de
        // depth+stencil nela (default da GLSurfaceView tem stencil 0)
        glSurfaceView!!.setEGLConfigChooser(8, 8, 8, 8, 16, 8)
        glSurfaceView!!.preserveEGLContextOnPause = true
        glSurfaceView!!.setRenderer(EmuRenderer())
        glSurfaceView!!.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Touch overlay por cima do GL surface
        touchView = TouchOverlayView(this)

        val root = android.widget.FrameLayout(this)
        root.addView(glSurfaceView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(touchView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Gamepad: a root view precisa de foco para receber KeyEvents
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()

        // Preferência: esconder touch overlay quando há gamepad conectado
        autoHideTouchOverlay = try {
            runBlocking { SettingsRepository(applicationContext).autoHideTouchOverlay.first() }
        } catch (e: Throwable) {
            EmuHubLogger.w(TAG, "Falha ao ler autoHideTouchOverlay: ${e.message}")
            true
        }

        // Monitora conexão/desconexão de gamepads (Bluetooth/USB)
        inputManager = getSystemService(Context.INPUT_SERVICE) as? InputManager
        inputManager?.registerInputDeviceListener(
            inputDeviceListener, Handler(Looper.getMainLooper()))
        updateTouchOverlayVisibility()

        // Audio na taxa reportada pelo core (GBA = 32768 Hz)
        startAudio(nativeGetSampleRate())

        // Thread só de áudio: drena o buffer nativo pro AudioTrack.
        // retro_run() agora acontece na GL thread (EmuRenderer.onDrawFrame)
        running = true
        Thread {
            val audioBuf = ShortArray(8192)
            EmuHubLogger.i(TAG, "Audio thread started")
            while (running) {
                val n = nativeReadAudio(audioBuf)
                if (n > 0) {
                    try {
                        audioTrack?.write(audioBuf, 0, n)
                    } catch (_: Exception) { }
                } else {
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) { }
                }
            }
        }.apply {
            name = "emu-audio"
            start()
        }
    }

    override fun onDestroy() {
        exitHandler.removeCallbacks(exitRunnable)
        inputManager?.unregisterInputDeviceListener(inputDeviceListener)

        // Auto-save antes de descarregar o jogo
        if (gameLoaded) {
            try { autoSave() } catch (e: Throwable) {
                EmuHubLogger.w(TAG, "Auto-save failed: ${e.message}")
            }
        }

        running = false
        // Cores HW (Citra) fazem chamadas GL no teardown — o unload precisa
        // rodar na GL thread com o contexto current. Fallback na UI thread se
        // a GL thread não responder (nativeUnload é idempotente).
        val latch = java.util.concurrent.CountDownLatch(1)
        val queued = try {
            glSurfaceView?.queueEvent {
                nativeUnload()
                latch.countDown()
            } != null
        } catch (_: Exception) {
            false
        }
        if (!queued || !latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
            EmuHubLogger.w(TAG, "Unload na GL thread nao completou; fallback na UI thread")
            nativeUnload()
        }
        glSurfaceView?.onPause()
        audioTrack?.stop()
        audioTrack?.release()
        super.onDestroy()
    }

    private fun startAudio(sampleRate: Int) {
        val rate = if (sampleRate in 8000..192000) sampleRate else 44100
        val bufferSize = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
                .setBufferSizeInBytes((bufferSize * 2).coerceAtLeast(8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
            EmuHubLogger.i(TAG, "AudioTrack started at ${rate}Hz")
        } catch (e: Exception) {
            EmuHubLogger.e(TAG, "Failed to init audio", e)
            audioTrack = null
        }
    }

    // ─── Save State Manager ───

    private fun getSavesDir(): File {
        val romName = java.io.File(romPath).nameWithoutExtension
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return java.io.File(filesDir, "saves/$romName").apply { mkdirs() }
    }

    private fun saveState(slot: Int) {
        val dir = getSavesDir()
        val file = java.io.File(dir, "slot$slot.state")

        val size = nativeGetSerializeSize()
        if (size <= 0) {
            EmuHubLogger.w(TAG, "Core does not support save states (size=$size)")
            return
        }

        val buf = ByteArray(size.toInt())
        if (nativeSerialize(buf)) {
            try {
                file.writeBytes(buf)
                EmuHubLogger.i(TAG, "Saved slot $slot: ${file.absolutePath} (${buf.size} bytes)")
            } catch (e: Exception) {
                EmuHubLogger.e(TAG, "Failed to write save file slot $slot", e)
            }
        } else {
            EmuHubLogger.e(TAG, "serialize() returned false for slot $slot")
        }
    }

    private fun loadState(slot: Int): Boolean {
        val dir = getSavesDir()
        val file = java.io.File(dir, "slot$slot.state")

        if (!file.exists()) {
            EmuHubLogger.w(TAG, "No save state for slot $slot")
            return false
        }

        try {
            val buf = file.readBytes()
            if (nativeUnserialize(buf)) {
                EmuHubLogger.i(TAG, "Loaded slot $slot: ${file.absolutePath} (${buf.size} bytes)")
                return true
            } else {
                EmuHubLogger.e(TAG, "unserialize() returned false for slot $slot")
                return false
            }
        } catch (e: Exception) {
            EmuHubLogger.e(TAG, "Failed to read save file slot $slot", e)
            return false
        }
    }

    private fun autoSave() {
        // Save state (slot 0 = auto-save)
        val size = nativeGetSerializeSize()
        if (size > 0) {
            val dir = getSavesDir()
            val file = java.io.File(dir, "slot0.state")
            val buf = ByteArray(size.toInt())
            if (nativeSerialize(buf)) {
                try {
                    file.writeBytes(buf)
                    EmuHubLogger.i(TAG, "Save state written: ${file.absolutePath}")
                } catch (_: Exception) { }
            }
        }

        // SRAM backup (save interno do jogo)
        try {
            val sram = nativeGetSram()
            if (sram != null && sram.isNotEmpty()) {
                val dir = getSavesDir()
                val file = java.io.File(dir, "sram.bin")
                file.writeBytes(sram)
                EmuHubLogger.i(TAG, "SRAM backup: ${file.absolutePath} (${sram.size} bytes)")
            }
        } catch (_: Exception) { }
    }

    private fun autoLoad() {
        // SRAM restore (save interno do jogo — ex.: Pokemon "Continue")
        try {
            val dir = getSavesDir()
            val sramFile = java.io.File(dir, "sram.bin")
            if (sramFile.exists()) {
                val sram = sramFile.readBytes()
                if (nativeSetSram(sram)) {
                    EmuHubLogger.i(TAG, "SRAM restored: ${sramFile.absolutePath} (${sram.size} bytes)")
                }
            }
        } catch (_: Exception) { }
        // NOTA: save states (slot0.state) NÃO são auto-carregados — só via
        // menu manual, para não sobrescrever a tela inicial do jogo
    }

    // ─── Gamepad físico (Xbox/PS/genérico) ───

    /** true se o device é um gamepad/joystick físico (ignora devices virtuais). */
    private fun isGamepad(device: InputDevice?): Boolean {
        if (device == null || device.isVirtual) return false
        val sources = device.sources
        return (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
            (sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
    }

    private fun hasGamepadConnected(): Boolean =
        InputDevice.getDeviceIds().any { isGamepad(InputDevice.getDevice(it)) }

    /**
     * Esconde o touch overlay quando há gamepad conectado (se a preferência
     * permitir) e o traz de volta quando o último gamepad desconecta.
     */
    private fun updateTouchOverlayVisibility() {
        runOnUiThread {
            val hide = autoHideTouchOverlay && hasGamepadConnected()
            val view = touchView ?: return@runOnUiThread
            val newVisibility = if (hide) View.GONE else View.VISIBLE
            if (view.visibility != newVisibility) {
                if (hide) {
                    // Solta botões que ficaram pressionados no touch
                    view.releaseAllButtons()
                }
                view.visibility = newVisibility
                EmuHubLogger.i(TAG, "Gamepad ${if (hide) "conectado: touch overlay oculto" else "ausente: touch overlay visível"}")
            }
        }
    }

    /** Mapeia KeyEvent do Android → RETRO_DEVICE_ID_JOYPAD_* (libretro.h). */
    private fun libretroButtonFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> 4
        KeyEvent.KEYCODE_DPAD_DOWN -> 5
        KeyEvent.KEYCODE_DPAD_LEFT -> 6
        KeyEvent.KEYCODE_DPAD_RIGHT -> 7
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> 8
        KeyEvent.KEYCODE_BUTTON_B -> 0
        KeyEvent.KEYCODE_BUTTON_X -> 9
        KeyEvent.KEYCODE_BUTTON_Y -> 1
        KeyEvent.KEYCODE_BUTTON_START -> 3
        KeyEvent.KEYCODE_BUTTON_SELECT -> 2
        KeyEvent.KEYCODE_BUTTON_L1 -> 10
        KeyEvent.KEYCODE_BUTTON_R1 -> 11
        KeyEvent.KEYCODE_BUTTON_L2 -> 12
        KeyEvent.KEYCODE_BUTTON_R2 -> 13
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 14
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 15
        else -> -1
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromGamepad =
            (event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
            (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) ||
            (event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD)

        // BACK de gamepad = SELECT; BACK de touch/gesto continua saindo do jogo
        val buttonId = if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (fromGamepad) 2 else -1
        } else {
            libretroButtonFor(event.keyCode)
        }

        if (buttonId >= 0) {
            when (event.action) {
                KeyEvent.ACTION_DOWN ->
                    if (event.repeatCount == 0) nativeSetButton(buttonId, true)
                KeyEvent.ACTION_UP -> nativeSetButton(buttonId, false)
            }
            if (buttonId == 2 || buttonId == 3) {
                val pressed = when (event.action) {
                    KeyEvent.ACTION_DOWN -> true
                    KeyEvent.ACTION_UP -> false
                    else -> null
                }
                if (pressed != null) {
                    if (buttonId == 2) selectPressed = pressed else startPressed = pressed
                    if (selectPressed && startPressed) {
                        exitHandler.removeCallbacks(exitRunnable)
                        exitHandler.postDelayed(exitRunnable, EXIT_HOLD_MS)
                    } else {
                        exitHandler.removeCallbacks(exitRunnable)
                    }
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            event.actionMasked == MotionEvent.ACTION_MOVE
        ) {
            // HAT = D-pad reportado como eixo (padrão em controles Xbox/PS).
            // Sem HAT ativo, o analógico esquerdo também vira D-pad digital.
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val stickX = event.getAxisValue(MotionEvent.AXIS_X)
            val stickY = event.getAxisValue(MotionEvent.AXIS_Y)
            val x = if (abs(hatX) > 0.5f) hatX else stickX
            val y = if (abs(hatY) > 0.5f) hatY else stickY
            updateDpadFromAxes(x, y)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun updateDpadFromAxes(x: Float, y: Float) {
        val newX = if (x < -0.5f) -1 else if (x > 0.5f) 1 else 0
        val newY = if (y < -0.5f) -1 else if (y > 0.5f) 1 else 0
        if (newX != axisDpadX) {
            nativeSetButton(6, newX == -1) // LEFT
            nativeSetButton(7, newX == 1)  // RIGHT
            axisDpadX = newX
        }
        if (newY != axisDpadY) {
            nativeSetButton(4, newY == -1) // UP
            nativeSetButton(5, newY == 1)  // DOWN
            axisDpadY = newY
        }
    }

    // ─── OpenGL Renderer (GLES 2.0 com shaders) ───
    inner class EmuRenderer : GLSurfaceView.Renderer {
        private var textureId = 0
        private var program = 0
        private var aPosition = 0
        private var aTexCoord = 0
        private val fbBuffer = IntArray(1024 * 1024) // Max 1024x1024 RGBA
        private val vertBuf = java.nio.ByteBuffer.allocateDirect(32 * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        private var surfaceW = 1
        private var surfaceH = 1

        // Pacing: onDrawFrame dispara a cada vsync (ex.: 120Hz), mas o core
        // deve rodar no fps dele (ex.: 60); frames adiantados só redesenham
        private var frameNs = 0L
        private var nextFrameNs = 0L

        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        private fun loadShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)

            // Avisa o bridge: contexto novo → recriar FBO + context_reset do
            // core no próximo nativeRunFrame (que roda nesta thread)
            nativeGLContextCreated()

            val fps = nativeGetFPS().takeIf { it > 0.0 } ?: 60.0
            frameNs = (1_000_000_000.0 / fps).toLong()
            nextFrameNs = System.nanoTime()
            EmuHubLogger.i(TAG, "GL surface created, core fps=$fps")

            // Compile shaders
            val vs = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vs)
                GLES20.glAttachShader(it, fs)
                GLES20.glLinkProgram(it)
            }
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")

            // Create texture
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            surfaceW = width
            surfaceH = height
            nativeSetScreenSize(width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val hwRender = nativeIsHwRender()

            // Roda o core aqui: esta é a thread com o contexto GL current
            if (running) {
                val now = System.nanoTime()
                if (hwRender) {
                    // HW pacing adaptativo: se o core demorar mais que
                    // frameNs, roda sem sleep (não força 60fps artificial)
                    val waitNs = nextFrameNs - now
                    if (waitNs > 0) {
                        try {
                            Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
                        } catch (_: InterruptedException) { }
                    }
                    val t0 = System.nanoTime()
                    nativeRunFrame()
                    val elapsed = System.nanoTime() - t0
                    nextFrameNs += frameNs
                    if (elapsed > frameNs) {
                        // Core lento demais: não força pacing, só
                        // re-sincroniza pra evitar acúmulo de atraso
                        nextFrameNs = System.nanoTime()
                    } else if (nextFrameNs < System.nanoTime() - frameNs) {
                        nextFrameNs = System.nanoTime() + frameNs
                    }
                } else if (now >= nextFrameNs) {
                    nativeRunFrame()
                    nextFrameNs += frameNs
                    // Atrasado demais (pausa, hitch): re-sincroniza
                    if (nextFrameNs < now - frameNs) nextFrameNs = now + frameNs
                }
            }

            // HW render (Citra): a apresentação acontece no C++ dentro de
            // nativeRunFrame (present_hw_frame), com save/restore de estado.
            // NENHUMA chamada GL aqui — o state tracker do core assume que
            // nada muda o contexto entre retro_run()s (v42: era a causa do
            // SIGSEGV em AccelerateDrawBatchInternal e da tela preta).
            if (hwRender) return

            // ─── Caminho software ───
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val w = nativeGetFBWidth()
            val h = nativeGetFBHeight()
            if (w <= 0 || h <= 0) return

            val needed = w * h
            if (needed > fbBuffer.size) return
            if (!nativeGetFrameBuffer(fbBuffer)) return
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            val pixelBuf = java.nio.IntBuffer.wrap(fbBuffer, 0, needed)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                pixelBuf
            )
            val texToDraw: Int = textureId
            val uMax = 1f
            val vMax = 1f
            val flipV = !hwRender  // HW: FBO já em orientação GL (Y=0 embaixo); SW: pixel data Y=0 em cima

            // Calculate aspect-ratio-preserved quad
            val gameAspect = w.toFloat() / h.toFloat()
            val screenAspect = surfaceW.toFloat() / surfaceH.toFloat()

            val drawW: Float
            val drawH: Float
            if (gameAspect > screenAspect) {
                // Jogo mais largo que a tela: limita pela largura
                drawW = 1f
                drawH = screenAspect / gameAspect
            } else {
                // Tela mais larga que o jogo: limita pela altura
                drawW = gameAspect / screenAspect
                drawH = 1f
            }

            val vBottom = if (flipV) vMax else 0f
            val vTop = if (flipV) 0f else vMax

            // ─── Dual-screen (NDS) side-by-side via software ───
            val isDualScreen = nativeGetLayoutMode() == 1 && h > w * 1.3f
            if (isDualScreen) {
                // Cada tela: w × h/2 (ex.: 256×192 no NDS)
                val sw = surfaceW.toFloat()
                val sh = surfaceH.toFloat()
                val halfSw = sw / 2f
                val halfGameAspect = w.toFloat() / (h / 2f).toFloat()
                val halfScreenAspect = halfSw / sh
                val dW: Float
                val dH: Float
                if (halfGameAspect > halfScreenAspect) {
                    dW = 1f
                    dH = halfScreenAspect / halfGameAspect
                } else {
                    dW = halfGameAspect / halfScreenAspect
                    dH = 1f
                }
                // Normaliza pro espaço NDC (-1 a 1, que corresponde a sw × sh)
                val normW = dW * (halfSw / (sw / 2f))  // w ocupa metade da tela
                val normH = dH
                // Desenha dois quads: left = top screen, right = bottom screen
                // flipV=true (SW): top=rows0-V0.5, bottom=rows_half-V1.0
                // flipV=false (HW): top=V0.5-1.0, bottom=V0.0-0.5
                val leftVLow = if (flipV) 0f else 0.5f
                val leftVHigh = 0.5f
                val rightVLow = 0.5f
                val rightVHigh = if (flipV) 1f else 0.5f
                val dualVerts = floatArrayOf(
                    // Left quad (top screen)
                    -1f, -normH, 0f, leftVHigh,
                     0f, -normH, 1f, leftVHigh,
                    -1f,  normH, 0f, leftVLow,
                     0f,  normH, 1f, leftVLow,
                    // Right quad (bottom screen)
                     0f, -normH, 0f, rightVHigh,
                     1f, -normH, 1f, rightVHigh,
                     0f,  normH, 0f, rightVLow,
                     1f,  normH, 1f, rightVLow,
                )
                vertBuf.position(0)
                vertBuf.put(dualVerts).position(0)
            } else {
                val vertices = floatArrayOf(
                    // x, y, u, v
                    -drawW, -drawH, 0f, vBottom,
                     drawW, -drawH, uMax, vBottom,
                    -drawW,  drawH, 0f, vTop,
                     drawW,  drawH, uMax, vTop,
                )
                vertBuf.position(0)
                vertBuf.put(vertices).position(0)
            }
            val numVerts = if (isDualScreen) 8 else 4

            // Use shader program
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texToDraw)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)

            // Position attribute (2 floats, stride 16 bytes = 4 floats)
            vertBuf.position(0)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertBuf)
            GLES20.glEnableVertexAttribArray(aPosition)

            // TexCoord attribute (next 2 floats)
            vertBuf.position(2)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertBuf)
            GLES20.glEnableVertexAttribArray(aTexCoord)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, numVerts)

            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)
        }
    }

    // ─── Touch Overlay ───
    inner class TouchOverlayView(context: Context) : View(context) {
        private val buttons = mutableListOf<TouchButton>()
        private val paint = Paint().apply {
            isAntiAlias = true
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }

        // ─── Analog stick state ───
        private var analogTouchId = -1
        private var analogBaseX = 0f
        private var analogBaseY = 0f
        private var analogCX = 0f
        private var analogCY = 0f
        private var analogRadius = 0f
        private var analogKnobX = 0f
        private var analogKnobY = 0f
        private val ANALOG_DEADZONE = 0.15f

        init {
            post {
                val sw = width.toFloat()
                val sh = height.toFloat()
                val u = Math.min(sw, sh)  // base unit
                val bs = u * 0.15f

                if (isN64) {
                    // ─── N64 LAYOUT — só o que o Majora's Mask usa ───
                    //
                    // NADA de D-Pad, L, R — só o essencial pra jogar.
                    // O D-Pad no N64 é quase irrelevante pros Zeldas.
                    //
                    // Botões (mapping Mupen64Plus-Next padrão):
                    //   A = ID 0  (JOYPAD_B  → N64 A: ação)
                    //   B = ID 8  (JOYPAD_A  → N64 B: item/cancel)
                    //   C↑= ID 1  (JOYPAD_Y  → N64 C-Up)
                    //   C←= ID 9  (JOYPAD_X  → N64 C-Left)
                    //   C→= ID 11 (JOYPAD_R  → N64 C-Right)
                    //   C↓= ID 10 (JOYPAD_L  → N64 C-Down)
                    //   Z  = ID 2  (SELECT   → N64 Z: lock-on!)
                    //   STA= ID 3  (START    → N64 Start: pausa)

                    val unit = Math.min(sw, sh)  // base unit

                    // ── Top bar: Start (left) + Z (right) ──
                    val barH = unit * 0.04f
                    val barY = unit * 0.10f  // abaixo da status bar (~50px)
                    buttons.add(TouchButton(sw * 0.30f, barY, unit * 0.12f, barH, "STA", 3))
                    buttons.add(TouchButton(sw * 0.70f, barY, unit * 0.10f, barH, "Z", 2))

                    // ── Left side: Analog stick (BOTTOM left) ──
                    analogRadius = unit * 0.14f
                    analogCX = unit * 0.24f
                    analogCY = sh - unit * 0.22f
                    analogKnobX = analogCX
                    analogKnobY = analogCY

                    // ── Right side: A + B lado a lado + C diamond acima ──
                    val rxC = sw * 0.72f  // center X do par A+B
                    val ryB = sh - unit * 0.18f  // Y base dos botões

                    // A — GRANDE (ação)
                    val aSz = unit * 0.14f
                    buttons.add(TouchButton(rxC, ryB, aSz, aSz, "A", 0))

                    // B — GRANDE também, lado a lado com A (à esquerda)
                    val bSz = unit * 0.12f
                    buttons.add(TouchButton(rxC - unit * 0.18f, ryB, bSz, bSz, "B", 8))

                    // C-buttons — diamante BEM ACIMA de A+B,
                    // sem sobreposição com o A
                    val cSz = unit * 0.07f
                    val cOff = unit * 0.10f  // ↑ de 0.09
                    val cCy = ryB - aSz * 1.5f  // ↑ de 1.0 → bem acima do A
                    buttons.add(TouchButton(rxC, cCy - cOff, cSz, cSz, "C↑", 1))
                    buttons.add(TouchButton(rxC + cOff, cCy, cSz, cSz, "C→", 11))
                    buttons.add(TouchButton(rxC, cCy + cOff, cSz, cSz, "C↓", 10))
                    buttons.add(TouchButton(rxC - cOff, cCy, cSz, cSz, "C←", 9))

                } else {
                    // ─── Standard layout (SNES/Genesis etc) ───
                    val dpadCenterX = bs * 2.2f
                    val dpadCenterY = sh - bs * 2.3f
                    val dpadOffset = bs * 1.3f

                    buttons.add(TouchButton(dpadCenterX, dpadCenterY - dpadOffset, bs, bs, "↑", 4))
                    buttons.add(TouchButton(dpadCenterX, dpadCenterY + dpadOffset, bs, bs, "↓", 5))
                    buttons.add(TouchButton(dpadCenterX - dpadOffset, dpadCenterY, bs, bs, "←", 6))
                    buttons.add(TouchButton(dpadCenterX + dpadOffset, dpadCenterY, bs, bs, "→", 7))

                    val actionX = sw - bs * 3.2f
                    val actionY = sh - bs * 2.3f
                    val actionOffset = bs * 1.3f

                    buttons.add(TouchButton(actionX + actionOffset, actionY + actionOffset, bs, bs, "B", 0))
                    buttons.add(TouchButton(actionX + actionOffset * 2, actionY, bs, bs, "A", 8))
                    buttons.add(TouchButton(actionX, actionY, bs, bs, "Y", 1))
                    buttons.add(TouchButton(actionX + actionOffset, actionY - actionOffset, bs, bs, "X", 9))

                    val centerX = sw / 2
                    buttons.add(TouchButton(centerX - bs, bs, bs * 0.7f, bs * 0.5f, "SEL", 2))
                    buttons.add(TouchButton(centerX + bs, bs, bs * 0.7f, bs * 0.5f, "STA", 3))

                    buttons.add(TouchButton(bs, bs * 0.3f, bs, bs * 0.5f, "L", 10))
                    buttons.add(TouchButton(sw - bs, bs * 0.3f, bs, bs * 0.5f, "R", 11))
                }

                invalidate()
            }
        }

        inner class TouchButton(
            val cx: Float, val cy: Float, val w: Float, val h: Float,
            val label: String, val buttonId: Int,
        ) {
            val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            fun contains(x: Float, y: Float) = rect.contains(x, y)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            handleTouch(event)
            return true
        }

        fun handleTouch(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_CANCEL -> {
                    for (btn in buttons) {
                        nativeSetButton(btn.buttonId, false)
                        nativeButtonState[btn.buttonId] = false
                    }
                    if (isN64) {
                        analogTouchId = -1
                        analogKnobX = analogCX
                        analogKnobY = analogCY
                        nativeSetAnalog(0, 0, 0) // left X = 0
                        nativeSetAnalog(0, 1, 0) // left Y = 0
                        // Libera D-Pad do fallback analógico
                        for ((id, was) in listOf(
                            4 to analogDpadUp, 5 to analogDpadDown,
                            6 to analogDpadLeft, 7 to analogDpadRight)) {
                            if (was) { nativeSetButton(id, false); nativeButtonState[id] = false }
                        }
                        analogDpadUp = false; analogDpadDown = false
                        analogDpadLeft = false; analogDpadRight = false
                    }
                }
                else -> {
                    val upIndex = when (event.actionMasked) {
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.actionIndex
                        else -> -1
                    }

                    // ─── N64: handle analog stick ───
                    if (isN64) {
                        if (upIndex >= 0 && upIndex == analogTouchId) {
                            // Finger que estava no analógico saiu
                            analogTouchId = -1
                            analogKnobX = analogCX
                            analogKnobY = analogCY
                            nativeSetAnalog(0, 0, 0)
                            nativeSetAnalog(0, 1, 0)
                            // Libera D-Pad do fallback analógico
                            for ((id, was) in listOf(
                                4 to analogDpadUp, 5 to analogDpadDown,
                                6 to analogDpadLeft, 7 to analogDpadRight)) {
                                if (was) { nativeSetButton(id, false); nativeButtonState[id] = false }
                            }
                            analogDpadUp = false; analogDpadDown = false
                            analogDpadLeft = false; analogDpadRight = false
                        }
                        for (i in 0 until event.pointerCount) {
                            if (i == upIndex) continue
                            val x = event.getX(i)
                            val y = event.getY(i)
                            val dx = x - analogCX
                            val dy = y - analogCY
                            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            if (analogTouchId < 0 && dist < analogRadius) {
                                // Novo toque no analógico
                                analogTouchId = i
                                analogBaseX = x
                                analogBaseY = y
                            }
                            if (i == analogTouchId) {
                                val maxDist = analogRadius * 0.7f
                                var normDx = dx
                                var normDy = dy
                                val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                                if (d > maxDist) {
                                    normDx = dx / d * maxDist
                                    normDy = dy / d * maxDist
                                }
                                analogKnobX = analogCX + normDx
                                analogKnobY = analogCY + normDy
                                val rawX = normDx / maxDist
                                val rawY = -normDy / maxDist  // invert Y (N64 up = -Y)
                                val ax = clampAnalog(rawX)
                                val ay = clampAnalog(rawY)
                                nativeSetAnalog(0, 0, ax)
                                nativeSetAnalog(0, 1, ay)

                                // ─── Fallback: analógico → D-Pad digital ───
                                // Manda D-Pad junto pra garantir que o jogo
                                // responda, mesmo se o core não ler ANALOG.
                                val digUp = ay < -16383    // empurrou pra CIMA (Y negativo)
                                val digDown = ay > 16383   // empurrou pra BAIXO
                                val digLeft = ax < -16383  // empurrou pra ESQUERDA
                                val digRight = ax > 16383  // empurrou pra DIREITA
                                if (digUp != analogDpadUp) {
                                    nativeSetButton(4, digUp)    // UP
                                    analogDpadUp = digUp
                                }
                                if (digDown != analogDpadDown) {
                                    nativeSetButton(5, digDown)  // DOWN
                                    analogDpadDown = digDown
                                }
                                if (digLeft != analogDpadLeft) {
                                    nativeSetButton(6, digLeft)  // LEFT
                                    analogDpadLeft = digLeft
                                }
                                if (digRight != analogDpadRight) {
                                    nativeSetButton(7, digRight)  // RIGHT
                                    analogDpadRight = digRight
                                }
                            }
                        }
                    }

                    // ─── Handle buttons ───
                    for (btn in buttons) {
                        var nowPressed = false
                        for (i in 0 until event.pointerCount) {
                            if (i == upIndex) continue
                            if (isN64 && i == analogTouchId) continue // skip analog finger
                            if (btn.contains(event.getX(i), event.getY(i))) {
                                nowPressed = true
                                break
                            }
                        }
                        val wasPressed = nativeButtonState[btn.buttonId] ?: false
                        if (wasPressed != nowPressed) {
                            nativeSetButton(btn.buttonId, nowPressed)
                            nativeButtonState[btn.buttonId] = nowPressed
                            if (nowPressed) {
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        }
                    }
                }
            }
            invalidate()
        }

        private fun clampAnalog(v: Float): Int {
            if (Math.abs(v) < ANALOG_DEADZONE) return 0
            val clamped = v.coerceIn(-1f, 1f)
            return (clamped * 32767).toInt().coerceIn(-32767, 32767)
        }

        // Track button state for ACTION_MOVE
        private val nativeButtonState = mutableMapOf<Int, Boolean>()

        /** Solta todos os botões pressionados (usado ao esconder o overlay). */
        fun releaseAllButtons() {
            for (btn in buttons) {
                if (nativeButtonState[btn.buttonId] == true) {
                    nativeSetButton(btn.buttonId, false)
                    nativeButtonState[btn.buttonId] = false
                }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // ─── N64: draw analog stick ───
            if (isN64 && analogRadius > 0f) {
                // Filled base — gray translucent so it's VISIBLE even over black
                paint.style = Paint.Style.FILL
                paint.color = 0x60404040.toInt()  // dark gray translucent
                canvas.drawCircle(analogCX, analogCY, analogRadius, paint)

                // Outer ring (bright border, thick)
                paint.style = Paint.Style.STROKE
                paint.color = 0xE0FFFFFF.toInt()
                paint.strokeWidth = 8f
                canvas.drawCircle(analogCX, analogCY, analogRadius, paint)

                // Inner deadzone dot
                paint.style = Paint.Style.FILL
                paint.color = 0x60FFFFFF.toInt()
                canvas.drawCircle(analogCX, analogCY, analogRadius * 0.15f, paint)

                // Knob (visible circle that moves)
                val knobRadius = analogRadius * 0.35f
                val isTouching = analogTouchId >= 0
                paint.style = Paint.Style.FILL
                paint.color = if (isTouching) 0x80FFFFFF.toInt() else 0x40000000.toInt()
                canvas.drawCircle(analogKnobX, analogKnobY, knobRadius, paint)
                paint.style = Paint.Style.STROKE
                paint.color = 0x80FFFFFF.toInt()
                paint.strokeWidth = 2f
                canvas.drawCircle(analogKnobX, analogKnobY, knobRadius, paint)

                // Label
                paint.style = Paint.Style.FILL
                paint.color = 0x80FFFFFF.toInt()
                paint.textSize = 24f
                canvas.drawText("⏚", analogCX, analogCY + paint.textSize / 3, paint)
                paint.textSize = 36f
            }

            // ─── Draw buttons ───
            for (btn in buttons) {
                val pressed = nativeButtonState[btn.buttonId] ?: false
                // Fill: dark gray when not pressed, white when pressed
                paint.style = Paint.Style.FILL
                paint.color = if (pressed) 0x80FFFFFF.toInt() else 0x80404040.toInt()
                canvas.drawRoundRect(btn.rect, 8f, 8f, paint)

                // Border: always visible white outline
                paint.style = Paint.Style.STROKE
                paint.color = 0xA0FFFFFF.toInt()
                paint.strokeWidth = 3f
                canvas.drawRoundRect(btn.rect, 8f, 8f, paint)

                // Label
                paint.style = Paint.Style.FILL
                paint.color = if (pressed) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                canvas.drawText(btn.label, btn.cx, btn.cy + paint.textSize / 3, paint)
            }
        }
    }
}

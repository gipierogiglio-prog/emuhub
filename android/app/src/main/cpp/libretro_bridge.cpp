/**
 * emuhub_emulator — NDK bridge for libretro cores.
 *
 * Loads a libretro core .so, initializes it with a ROM, renders video
 * to a shared framebuffer, and handles audio/input via JNI callbacks.
 *
 * Architecture:
 *   Java (EmulatorActivity) ← JNI → C++ (this file) ← dlopen → libretro core .so
 */
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <cstring>
#include <cstdarg>
#include <cstdio>
#include <csignal>
#include <cinttypes>
#include <vector>
#include <mutex>
#include <atomic>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/time.h>
#include <sys/ucontext.h>
#include <ctime>

#define LOG_TAG "EmuHubEmu"
// Logcat + arquivo de log nativo (o logcat morre com o processo e não sobe
// pro R2; o arquivo sim). Definida na seção do crash handler.
static void emuhub_log(int prio, const char *fmt, ...)
    __attribute__((format(printf, 2, 3)));
#define LOGI(...) emuhub_log(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGE(...) emuhub_log(ANDROID_LOG_ERROR, __VA_ARGS__)

// ─── Libretro types (from libretro.h) ───
// We forward-declare only what we need from the API

#define RETRO_DEVICE_JOYPAD 1
// IDs oficiais do libretro.h — Java envia esses mesmos valores
#define RETRO_DEVICE_ID_JOYPAD_B       0
#define RETRO_DEVICE_ID_JOYPAD_Y       1
#define RETRO_DEVICE_ID_JOYPAD_SELECT  2
#define RETRO_DEVICE_ID_JOYPAD_START   3
#define RETRO_DEVICE_ID_JOYPAD_UP      4
#define RETRO_DEVICE_ID_JOYPAD_DOWN    5
#define RETRO_DEVICE_ID_JOYPAD_LEFT    6
#define RETRO_DEVICE_ID_JOYPAD_RIGHT   7
#define RETRO_DEVICE_ID_JOYPAD_A       8
#define RETRO_DEVICE_ID_JOYPAD_X       9
#define RETRO_DEVICE_ID_JOYPAD_L       10
#define RETRO_DEVICE_ID_JOYPAD_R       11
#define RETRO_DEVICE_ID_JOYPAD_L2      12
#define RETRO_DEVICE_ID_JOYPAD_R2      13
#define RETRO_DEVICE_ID_JOYPAD_L3      14
#define RETRO_DEVICE_ID_JOYPAD_R3      15

#define RETRO_ENVIRONMENT_GET_CAN_DUPE 3
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY 9
#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT 10
#define RETRO_ENVIRONMENT_GET_VARIABLE 15
#define RETRO_ENVIRONMENT_SET_VARIABLES 16
#define RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE 17
#define RETRO_ENVIRONMENT_GET_LANGUAGE 39
#define RETRO_ENVIRONMENT_GET_LOG_INTERFACE 27
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY 31
#define RETRO_PIXEL_FORMAT_0RGB1555 0
#define RETRO_PIXEL_FORMAT_XRGB8888 1
#define RETRO_PIXEL_FORMAT_RGB565 2

// ─── HW render (SET_HW_RENDER, cmd 14) — layout EXATO do libretro.h ───
#define RETRO_ENVIRONMENT_SET_HW_RENDER 14
#define RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER 56
#define RETRO_HW_FRAME_BUFFER_VALID ((void *)-1)

enum retro_hw_context_type {
    RETRO_HW_CONTEXT_NONE = 0,
    RETRO_HW_CONTEXT_OPENGL = 1,
    RETRO_HW_CONTEXT_OPENGLES2 = 2,
    RETRO_HW_CONTEXT_OPENGL_CORE = 3,
    RETRO_HW_CONTEXT_OPENGLES3 = 4,
    RETRO_HW_CONTEXT_OPENGLES_VERSION = 5,
};

typedef void (*retro_hw_context_reset_t)(void);
typedef uintptr_t (*retro_hw_get_current_framebuffer_t)(void);
typedef void (*retro_proc_address_t)(void);
typedef retro_proc_address_t (*retro_hw_get_proc_address_t)(const char *sym);

#define RETRO_MEMORY_SAVE_RAM 0
#define RETRO_MEMORY_RTC 1
#define RETRO_MEMORY_SYSTEM_RAM 2
#define RETRO_MEMORY_VIDEO_RAM 3
// quase no fim — não reordenar
struct retro_hw_render_callback {
    enum retro_hw_context_type context_type;
    retro_hw_context_reset_t context_reset;        // DO CORE: nós chamamos
    retro_hw_get_current_framebuffer_t get_current_framebuffer;  // nosso
    retro_hw_get_proc_address_t get_proc_address;  // nosso
    bool depth;
    bool stencil;
    bool bottom_left_origin;
    unsigned version_major;
    unsigned version_minor;
    bool cache_context;
    retro_hw_context_reset_t context_destroy;      // DO CORE: nós chamamos
    bool debug_context;
};

struct retro_game_geometry {
    unsigned base_width;
    unsigned base_height;
    unsigned max_width;
    unsigned max_height;
    float aspect_ratio;
};

struct retro_system_timing {
    double fps;
    double sample_rate;
};

struct retro_system_av_info {
    struct retro_game_geometry geometry;
    struct retro_system_timing timing;
};

struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
};

struct retro_system_info {
    const char *library_name;
    const char *library_version;
    const char *valid_extensions;
    bool need_fullpath;
    bool block_extract;
};

struct retro_variable {
    const char *key;
    const char *value;
};

typedef bool (*retro_environment_t)(unsigned cmd, void *data);
typedef void (*retro_video_refresh_t)(const void *data, unsigned width, unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef int (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);

// Function pointers loaded from core .so
static void *core_handle = nullptr;

// Shutdown thread-safe: nativeUnload() pode rodar (UI thread) enquanto a
// GL/emu thread está dentro de retro_run(). O mutex serializa run x unload;
// o atomic evita entrar em retro_run() depois que o teardown começou.
static std::atomic<bool> core_loaded{false};
// true só depois de retro_load_game() retornar ok — retro_unload_game() em
// core com jogo não carregado crasha (ex.: Citra Core::System::Shutdown)
static std::atomic<bool> game_loaded{false};
static std::mutex run_mutex;

static void (*retro_init)(void);
static void (*retro_deinit)(void);
static unsigned (*retro_api_version)(void);
static void (*retro_get_system_info)(struct retro_system_info *);
static void (*retro_get_system_av_info)(struct retro_system_av_info *);
static void (*retro_set_environment)(retro_environment_t);
static void (*retro_set_video_refresh)(retro_video_refresh_t);
static void (*retro_set_audio_sample)(retro_audio_sample_t);
static void (*retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
static void (*retro_set_input_poll)(retro_input_poll_t);
static void (*retro_set_input_state)(retro_input_state_t);
static void (*retro_reset)(void);
static bool (*retro_load_game)(const struct retro_game_info *);
static void (*retro_unload_game)(void);
static void (*retro_run)(void);
static size_t (*retro_serialize_size)(void);
static bool (*retro_serialize)(void *, size_t);
static bool (*retro_unserialize)(const void *, size_t);
static void *(*retro_get_memory_data)(unsigned id);
static size_t (*retro_get_memory_size)(unsigned id);

// ─── Crash handler nativo ───
// SIGSEGV/SIGABRT etc. matam o processo sem passar pelo
// UncaughtExceptionHandler do Java; aqui gravamos num arquivo (path vindo
// do EmuHubLogger via nativeSetCrashLogPath) antes de re-emitir o sinal
// para o sistema gerar o tombstone normal.
static char crash_log_path[512] = {0};

// Cores logam muito (às vezes por frame); acima disso só logcat
static const off_t NATIVE_LOG_MAX_BYTES = 4 * 1024 * 1024;
static std::mutex native_log_mutex;

// Append de uma linha no arquivo de log nativo, formato do EmuHubLogger:
// "yyyy-MM-dd HH:mm:ss.SSS L/EmuHubEmu: msg". Abre+escreve+fecha pra não
// segurar fd; mutex evita linhas entrelaçadas entre threads do core.
static void emuhub_log_file(int prio, const char *msg) {
    if (crash_log_path[0] == '\0') return;
    std::lock_guard<std::mutex> lock(native_log_mutex);
    int fd = open(crash_log_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return;
    struct stat st;
    if (fstat(fd, &st) == 0 && st.st_size > NATIVE_LOG_MAX_BYTES) {
        close(fd);
        return;
    }
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    struct tm tm_buf;
    localtime_r(&tv.tv_sec, &tm_buf);
    char levelc = prio >= ANDROID_LOG_ERROR ? 'E'
                : prio == ANDROID_LOG_WARN ? 'W' : 'I';
    char line[1200];
    int n = snprintf(line, sizeof(line),
        "%04d-%02d-%02d %02d:%02d:%02d.%03d %c/" LOG_TAG ": %s\n",
        tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
        tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec, (int)(tv.tv_usec / 1000),
        levelc, msg);
    if (n > 0) write(fd, line, n < (int)sizeof(line) ? n : (int)sizeof(line) - 1);
    close(fd);
}

static void emuhub_log(int prio, const char *fmt, ...) {
    char msg[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(msg, sizeof(msg), fmt, args);
    va_end(args);
    size_t len = strlen(msg);
    while (len > 0 && (msg[len - 1] == '\n' || msg[len - 1] == '\r')) msg[--len] = '\0';
    __android_log_write(prio, LOG_TAG, msg);
    emuhub_log_file(prio, msg);
}

static void crash_write(int fd, const char *s) {
    if (fd >= 0) write(fd, s, strlen(s));
    __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, s);
}

// Simboliza um PC via dladdr e escreve no formato do ndk-stack (offset
// relativo à base da lib)
static void crash_dump_frame(int fd, int idx, uintptr_t pc) {
    char line[256];
    Dl_info dli;
    if (dladdr((void *)pc, &dli) && dli.dli_fname) {
        uintptr_t rel = pc - (uintptr_t)dli.dli_fbase;
        snprintf(line, sizeof(line), "  #%02d pc 0x%08" PRIxPTR " %s (%s)\n",
                 idx, rel, dli.dli_fname, dli.dli_sname ? dli.dli_sname : "??");
    } else {
        snprintf(line, sizeof(line), "  #%02d pc 0x%" PRIxPTR " <unknown>\n", idx, pc);
    }
    crash_write(fd, line);
}

static void crash_handler(int sig, siginfo_t *info, void *ucontext_v) {
    int fd = -1;
    if (crash_log_path[0]) {
        fd = open(crash_log_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    }
    char line[256];
    const char *name = sig == SIGSEGV ? "SIGSEGV"
                     : sig == SIGABRT ? "SIGABRT"
                     : sig == SIGBUS  ? "SIGBUS"
                     : sig == SIGFPE  ? "SIGFPE"
                     : sig == SIGILL  ? "SIGILL" : "?";
    snprintf(line, sizeof(line),
             "\n*** NATIVE CRASH *** signal=%d (%s) fault_addr=%p pid=%d tid=%d\n",
             sig, name, info ? info->si_addr : nullptr, getpid(), gettid());
    crash_write(fd, line);

    // Registradores da stack que CRASHOU vêm do ucontext — _Unwind_Backtrace
    // chamado aqui de dentro só veria a altstack do próprio handler
#if defined(__aarch64__)
    ucontext_t *uc = (ucontext_t *)ucontext_v;
    uintptr_t pc = (uintptr_t)uc->uc_mcontext.pc;
    uintptr_t lr = (uintptr_t)uc->uc_mcontext.regs[30];
    uintptr_t fp = (uintptr_t)uc->uc_mcontext.regs[29];
    uintptr_t sp = (uintptr_t)uc->uc_mcontext.sp;
    snprintf(line, sizeof(line),
             "  regs: pc=0x%" PRIxPTR " lr=0x%" PRIxPTR " fp=0x%" PRIxPTR
             " sp=0x%" PRIxPTR "\n", pc, lr, fp, sp);
    crash_write(fd, line);

    int idx = 0;
    uintptr_t prev = 0;
    if (pc) { crash_dump_frame(fd, idx++, pc); prev = pc; }
    if (lr && lr != prev) { crash_dump_frame(fd, idx++, lr); prev = lr; }

    // Caminha a cadeia de frame pointers (AAPCS64: [fp]=fp anterior,
    // [fp+8]=lr). Só dereferencia fp dentro da stack crashada (sp..sp+8MB)
    // pra não tomar SIGSEGV recursivo aqui dentro
    uintptr_t cur_fp = fp;
    for (int i = 0; i < 24 && idx < 32; i++) {
        if (cur_fp < sp || cur_fp > sp + (8u << 20) || (cur_fp & 0x7) != 0) break;
        uintptr_t next_fp = ((uintptr_t *)cur_fp)[0];
        uintptr_t ret_lr  = ((uintptr_t *)cur_fp)[1];
        if (!ret_lr) break;
        if (ret_lr != prev) { crash_dump_frame(fd, idx++, ret_lr); prev = ret_lr; }
        if (next_fp <= cur_fp) break;
        cur_fp = next_fp;
    }
#else
    (void)ucontext_v;
    crash_write(fd, "  (backtrace via ucontext implementado apenas em arm64)\n");
#endif
    if (fd >= 0) close(fd);

    // Re-emite com o handler default: o sistema mata o processo e gera tombstone
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_crash_handler() {
    // Stack alternativo: se o crash for stack overflow, o handler ainda roda
    static char altstack_mem[64 * 1024];
    stack_t ss = {};
    ss.ss_sp = altstack_mem;
    ss.ss_size = sizeof(altstack_mem);
    ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);

    struct sigaction sa = {};
    sa.sa_sigaction = crash_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    const int sigs[] = {SIGSEGV, SIGABRT, SIGFPE, SIGBUS, SIGILL};
    for (int s : sigs) sigaction(s, &sa, nullptr);
}

// ─── State ───
static std::mutex frame_mutex;
static std::vector<uint32_t> frame_buffer;  // RGBA8888
static int fb_width = 0, fb_height = 0;
static bool frame_ready = false;

// ─── HW render state ───
static struct retro_hw_render_callback hw_render = {};
static bool hw_render_enabled = false;
static bool hw_frame = false;             // último frame veio do FBO (guardado por frame_mutex)
static std::atomic<bool> gl_context_dirty{false};  // (re)criar FBO + context_reset no próximo frame
static GLuint hw_fbo = 0, hw_fbo_tex = 0, hw_fbo_depth = 0;
static int hw_fbo_w = 0, hw_fbo_h = 0;
static unsigned av_base_w = 0, av_base_h = 0;
static unsigned av_max_w = 0, av_max_h = 0;
static int screen_w = 1, screen_h = 1;
static int layout_mode = 0;  // 0=fullscreen, 1=side-by-side dual screen

static uintptr_t hw_get_current_framebuffer(void) {
    return (uintptr_t)hw_fbo;
}

static retro_proc_address_t hw_get_proc_address(const char *sym) {
    return (retro_proc_address_t)eglGetProcAddress(sym);
}

// Só pode rodar na GL thread com contexto current (chamada de nativeRunFrame).
// v33: FBO no tamanho BASE da av_info (400×480 no Citra em 1x) — o max
// (4000×4800 ≈ 150MB de VRAM) da v30/v31 dava tela preta, e o teste da v32
// (render direto na tela) provou que o core desenha exatamente base_w×base_h.
static void create_hw_fbo() {
    // ids antigos morreram junto com o contexto EGL anterior; só recria
    int w = av_base_w > 0 ? (int)av_base_w : 640;
    int h = av_base_h > 0 ? (int)av_base_h : 480;

    glGenTextures(1, &hw_fbo_tex);
    glBindTexture(GL_TEXTURE_2D, hw_fbo_tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenFramebuffers(1, &hw_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, hw_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, hw_fbo_tex, 0);

    if (hw_render.depth) {
        glGenRenderbuffers(1, &hw_fbo_depth);
        glBindRenderbuffer(GL_RENDERBUFFER, hw_fbo_depth);
        glRenderbufferStorage(GL_RENDERBUFFER,
            hw_render.stencil ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24, w, h);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, hw_fbo_depth);
        if (hw_render.stencil) {
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, hw_fbo_depth);
        }
    }

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT | (hw_render.depth ? GL_DEPTH_BUFFER_BIT : 0)
            | (hw_render.stencil ? GL_STENCIL_BUFFER_BIT : 0));
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    hw_fbo_w = w;
    hw_fbo_h = h;
    LOGI("HW FBO criado: %dx%d fbo=%u tex=%u depth=%d stencil=%d status=0x%x",
         w, h, hw_fbo, hw_fbo_tex, hw_render.depth, hw_render.stencil, status);
}

// ─── Apresentação nativa do frame HW (v42) ───
// O Citra tem um state tracker GL interno que assume que NADA muda o estado
// entre retro_run()s. O reset "cru" da v41 (glBindBuffer(GL_ARRAY_BUFFER,0)
// etc.) dessincronizava esse cache: no AccelerateDrawBatchInternal o
// state.Apply() pulava o rebind (cache hit), glMapBufferRange rodava no
// buffer 0, retornava nullptr e o core fazia memcpy pra 0x0 → SIGSEGV.
// E o draw do Java usava client-side arrays com o VAO do Citra ainda
// bindado (GL_INVALID_OPERATION em ES3) → tela preta.
// Solução: desenhar o FBO na tela AQUI, com VAO/VBO/programa próprios e
// save/restore EXATO de todo estado tocado — o cache do core nunca vê
// divergência. O Java não faz nenhuma chamada GL no caminho HW.
static GLuint pres_program = 0, pres_vao = 0, pres_vbo = 0;

static GLuint pres_compile_shader(GLenum type, const char *src) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = GL_FALSE;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(sh, sizeof(log), nullptr, log);
        LOGE("Presenter shader falhou: %s", log);
    }
    return sh;
}

// Só na GL thread com contexto current (mesma regra do create_hw_fbo)
static void create_presenter() {
    // Alpha forçado em 1.0: o FBO do core pode ter alpha lixo e a surface
    // da janela é RGBA8888 (compositor faria blend)
    const char *vs_src =
        "#version 300 es\n"
        "layout(location=0) in vec2 aPos;\n"
        "layout(location=1) in vec2 aTex;\n"
        "out vec2 vTex;\n"
        "void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }\n";
    const char *fs_src =
        "#version 300 es\n"
        "precision mediump float;\n"
        "in vec2 vTex;\n"
        "out vec4 frag;\n"
        "uniform sampler2D uTex;\n"
        "void main(){ frag = vec4(texture(uTex, vTex).rgb, 1.0); }\n";

    GLuint vs = pres_compile_shader(GL_VERTEX_SHADER, vs_src);
    GLuint fs = pres_compile_shader(GL_FRAGMENT_SHADER, fs_src);
    pres_program = glCreateProgram();
    glAttachShader(pres_program, vs);
    glAttachShader(pres_program, fs);
    glLinkProgram(pres_program);
    glDeleteShader(vs);
    glDeleteShader(fs);

    // Roda antes do context_reset do core (estado GL ainda "nosso"), mas
    // restaura bindings mesmo assim por segurança
    GLint prev_vao = 0, prev_abuf = 0, prev_prog = 0;
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prev_vao);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &prev_abuf);
    glGetIntegerv(GL_CURRENT_PROGRAM, &prev_prog);

    glGenVertexArrays(1, &pres_vao);
    glGenBuffers(1, &pres_vbo);
    glBindVertexArray(pres_vao);
    glBindBuffer(GL_ARRAY_BUFFER, pres_vbo);
    glBufferData(GL_ARRAY_BUFFER, 16 * sizeof(float), nullptr, GL_STREAM_DRAW);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 16, (const void *)0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 16, (const void *)8);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);

    glUseProgram(pres_program);
    glUniform1i(glGetUniformLocation(pres_program, "uTex"), 0);

    glUseProgram((GLuint)prev_prog);
    glBindVertexArray((GLuint)prev_vao);
    glBindBuffer(GL_ARRAY_BUFFER, (GLuint)prev_abuf);
    LOGI("Presenter criado: program=%u vao=%u vbo=%u", pres_program, pres_vao, pres_vbo);
}

// Desenha o frame (HW via FBO ou SW via frame_buffer) na tela.
// Todo estado GL alterado é salvo e restaurado.
static void present_hw_frame() {
    if (!pres_program) return;

    // ─── FPS counter ───
    {
        static long last_fps_sec = 0;
        static int frame_count = 0;
        frame_count++;
        struct timeval tv;
        gettimeofday(&tv, nullptr);
        if (tv.tv_sec != last_fps_sec) {
            LOGI("FPS: %d", frame_count);
            frame_count = 0;
            last_fps_sec = tv.tv_sec;
        }
    }

    GLint prev_draw_fbo = 0, prev_read_fbo = 0, prev_prog = 0, prev_vao = 0;
    GLint prev_abuf = 0, prev_active = 0, prev_tex0 = 0, prev_samp0 = 0;
    GLint vp[4];
    GLfloat cc[4];
    GLboolean cmask[4];
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &prev_draw_fbo);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &prev_read_fbo);
    glGetIntegerv(GL_CURRENT_PROGRAM, &prev_prog);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prev_vao);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &prev_abuf);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &prev_active);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_tex0);
    glGetIntegerv(GL_SAMPLER_BINDING, &prev_samp0);
    glGetIntegerv(GL_VIEWPORT, vp);
    glGetFloatv(GL_COLOR_CLEAR_VALUE, cc);
    glGetBooleanv(GL_COLOR_WRITEMASK, cmask);
    GLboolean en_scissor = glIsEnabled(GL_SCISSOR_TEST);
    GLboolean en_depth   = glIsEnabled(GL_DEPTH_TEST);
    GLboolean en_stencil = glIsEnabled(GL_STENCIL_TEST);
    GLboolean en_blend   = glIsEnabled(GL_BLEND);
    GLboolean en_cull    = glIsEnabled(GL_CULL_FACE);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glViewport(0, 0, screen_w, screen_h);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    int fw, fh;
    {
        std::lock_guard<std::mutex> flock(frame_mutex);
        fw = fb_width;
        fh = fb_height;
    }
    if (fw <= 0) fw = hw_fbo_w;
    if (fh <= 0) fh = hw_fbo_h;

    // Decide se frame veio do FBO (HW) ou pixel data (SW)
    bool is_hw_frame;
    {
        std::lock_guard<std::mutex> flock(frame_mutex);
        is_hw_frame = hw_frame;
    }

    float game_aspect = (float)fw / (float)fh;
    float screen_aspect = (float)screen_w / (float)screen_h;
    float dw = 1.f, dh = 1.f;
    if (game_aspect > screen_aspect) dh = screen_aspect / game_aspect;
    else                             dw = game_aspect / screen_aspect;

    // bottom_left_origin (GL): HW = sem flip, SW = flip (pixel Y=0 no topo)
    float v0 = is_hw_frame ? 0.f : 1.f;
    float v1 = is_hw_frame ? 1.f : 0.f;
    const float verts[16] = {
        -dw, -dh, 0.f, v0,
         dw, -dh, 1.f, v0,
        -dw,  dh, 0.f, v1,
         dw,  dh, 1.f, v1,
    };
    glBindVertexArray(pres_vao);
    glBindBuffer(GL_ARRAY_BUFFER, pres_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STREAM_DRAW);
    glUseProgram(pres_program);
    glActiveTexture(GL_TEXTURE0);

    // Decide qual textura usar: HW (FBO) ou SW (CPU pixel data)
    GLuint pres_tex = 0;
    if (is_hw_frame && hw_fbo_tex) {
        pres_tex = hw_fbo_tex;
    } else {
        // SW: upload frame_buffer como textura temporária
        std::lock_guard<std::mutex> flock(frame_mutex);
        if (!frame_buffer.empty()) {
            glGenTextures(1, &pres_tex);
            glBindTexture(GL_TEXTURE_2D, pres_tex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, fb_width, fb_height,
                         0, GL_RGBA, GL_UNSIGNED_BYTE, frame_buffer.data());
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
    }
    if (!pres_tex) return;

    glBindTexture(GL_TEXTURE_2D, pres_tex);
    glBindSampler(0, 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glBindSampler(0, (GLuint)prev_samp0);
    glBindTexture(GL_TEXTURE_2D, (GLuint)prev_tex0);
    glActiveTexture((GLenum)prev_active);
    // SW: deleta textura temporária depois de restaurar o bind original
    if (!is_hw_frame && pres_tex != hw_fbo_tex && pres_tex != 0) {
        glDeleteTextures(1, &pres_tex);
    }
    glUseProgram((GLuint)prev_prog);
    glBindVertexArray((GLuint)prev_vao);
    glBindBuffer(GL_ARRAY_BUFFER, (GLuint)prev_abuf);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)prev_draw_fbo);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)prev_read_fbo);
    glViewport(vp[0], vp[1], vp[2], vp[3]);
    glClearColor(cc[0], cc[1], cc[2], cc[3]);
    glColorMask(cmask[0], cmask[1], cmask[2], cmask[3]);
    if (en_scissor) glEnable(GL_SCISSOR_TEST);
    if (en_depth)   glEnable(GL_DEPTH_TEST);
    if (en_stencil) glEnable(GL_STENCIL_TEST);
    if (en_blend)   glEnable(GL_BLEND);
    if (en_cull)    glEnable(GL_CULL_FACE);
}

static char system_dir[512] = {0};  // System directory for BIOS files
static char save_dir[512] = {0};    // Save directory for SRAM/save files
static std::vector<uint8_t> rom_data;  // ROM em memória p/ cores sem need_fullpath
static int pixel_format = 0;
static double av_fps = 60.0;
static double av_sample_rate = 44100.0;

static std::mutex audio_mutex;
static std::vector<int16_t> audio_buffer;

// Input state: 16 buttons for port 0, all false initially
static bool input_state[16] = {false};

// Audio player buffer
static const int AUDIO_BUF_SAMPLES = 4096;
static int16_t audio_buf[AUDIO_BUF_SAMPLES * 2];

// ─── Environment callback ───
enum retro_log_level { RETRO_LOG_DEBUG = 0, RETRO_LOG_INFO, RETRO_LOG_WARN, RETRO_LOG_ERROR };

static void core_log_cb(enum retro_log_level level, const char *fmt, ...) {
    char msg[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(msg, sizeof(msg), fmt, args);
    va_end(args);
    int prio = (level >= RETRO_LOG_ERROR) ? ANDROID_LOG_ERROR
             : (level == RETRO_LOG_WARN) ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    emuhub_log(prio, "[core] %s", msg);  // logcat + arquivo (sobe pro R2)
}

struct retro_log_callback { void (*log)(enum retro_log_level, const char *, ...); };

static bool environment_cb(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            *(bool *)data = true;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            __android_log_print(ANDROID_LOG_INFO, "WRAPPER", "GET_SYSTEM_DIRECTORY: system_dir[0]=%d", system_dir[0]);
            if (system_dir[0] == '\0') { __android_log_print(ANDROID_LOG_WARN, "WRAPPER", "GET_SYSTEM_DIRECTORY: empty"); return false; }
            *(const char **)data = system_dir;
            __android_log_print(ANDROID_LOG_INFO, "WRAPPER", "GET_SYSTEM_DIRECTORY: returning %s", system_dir);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            if (save_dir[0] == '\0') {
                // Fallback para system_dir se save_dir não foi definido
                if (system_dir[0] == '\0') return false;
                *(const char **)data = system_dir;
            } else {
                *(const char **)data = save_dir;
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            int fmt = *(const int *)data;
            if (fmt != RETRO_PIXEL_FORMAT_0RGB1555 &&
                fmt != RETRO_PIXEL_FORMAT_XRGB8888 &&
                fmt != RETRO_PIXEL_FORMAT_RGB565) {
                LOGE("Unsupported pixel format requested: %d", fmt);
                return false;
            }
            pixel_format = fmt;
            LOGI("Pixel format set: %d (%s)", fmt,
                 fmt == RETRO_PIXEL_FORMAT_XRGB8888 ? "XRGB8888"
                 : fmt == RETRO_PIXEL_FORMAT_RGB565 ? "RGB565" : "0RGB1555");
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable *var = (struct retro_variable *)data;
            if (!var || !var->key) return false;
            // Valores fixos para cores que exigem configuração mínima via
            // GET_VARIABLE (sem elas, retro_load_game() falha). Strings
            // estáticas: o core guarda o ponteiro, não pode ser stack.
            static const struct { const char *key; const char *value; } kVars[] = {
                // mupen64plus_next — angrylion é o único RDP por software;
                // gliden64/parallel exigem SET_HW_RENDER (GL/Vulkan), que
                // esta bridge não fornece, e retro_load_game() falharia.
                {"mupen64plus-rdp-plugin",        "angrylion"},
                {"mupen64plus-rsp-plugin",        "hle"},
                {"mupen64plus-cpucore",           "dynamic_recompiler"},
                {"mupen64plus-43screensize",      "640x480"},
                {"mupen64plus-FrameDuping",       "True"},
                // citra — HW render via SET_HW_RENDER (GLES3, desde a v30)
                {"citra_use_cpu_jit",             "enabled"},
                {"citra_use_hw_renderer",         "enabled"},
                {"citra_use_hw_shader",           "enabled"},
                {"citra_use_hw_shaders",          "enabled"},
                {"citra_use_shader_jit",          "enabled"},
                {"citra_resolution_factor",       "1x (Native)"},
                {"citra_layout_option",           "Default Top-Bottom Screen"},
                {"citra_swap_screen",             "Top"},
                {"citra_swap_screen_mode",        "Default"},
                {"citra_use_virtual_sd",          "enabled"},
                {"citra_use_libretro_save_path",  "LibRetro Default"},
                {"citra_is_new_3ds",              "disabled"},
                {"citra_region_value",            "Auto"},
                {"citra_language",                "English"},
                {"citra_use_gdbstub",             "disabled"},
                {"citra_cpu_scale",               "1.0"},
                {"citra_texture_filter",          "None"},
                {"citra_texture_sampling",        "Nearest"},
                {"citra_dump_textures",           "disabled"},
                {"citra_custom_textures",         "disabled"},
                {"citra_use_hw_shader_cache",     "disabled"},
                {"citra_use_acc_mul",             "disabled"},
                {"citra_mouse_touchscreen",       "disabled"},
                {"citra_touch_touchscreen",       "enabled"},
                {"citra_render_touchscreen",      "enabled"},
                {"citra_deadzone",                "15"},
                {"citra_analog_function",         "Absolute"},
                // panda3ds — renderer por software; sem SET_HW_RENDER na
                // bridge, GL/Vulkan crasharia no primeiro retro_run()
                {"panda3ds_renderer",             "software"},
                {"panda3ds_use_vulkan",           "disabled"},
                {"panda3ds_resolution_factor",    "1x"},
                {"panda3ds_use_cpu_jit",          "enabled"},
                // Play! PS2 — baseline: full speed EE, no frame skip for real FPS measurement
                {"play_ee_clock_ratio",           "100%"},
                {"play_frame_skip",               "0"},
                // PCSX2 — software (libretro renderiza pra pixel buffer e passa
                // por s_video_cb, não usa RETRO_ENVIRONMENT_SET_HW_RENDER)
                {"pcsx2_bios",                    "auto"},
                {"pcsx2_fast_boot",               "enabled"},
                {"pcsx2_renderer",                "software"},
                {"pcsx2_upscale_multiplier",      "1"},
                {"pcsx2_blending_accuracy",       "basic"},
                {"pcsx2_texture_filtering",       "bilinear_ps2"},
                {"pcsx2_dithering",               "2"},
                {"pcsx2_mipmapping",              "disabled"},
                {"pcsx2_deinterlace_mode",        "0"},
                {"pcsx2_fxaa",                    "disabled"},
                {"pcsx2_cas_mode",                "disabled"},
                {"pcsx2_aspect_ratio",            "auto"},
                {"pcsx2_multitap",                "disabled"},
                {"pcsx2_lightgun",                "disabled"},
                {"pcsx2_trilinear_filtering",     "off"},
                {"pcsx2_anisotropic_filtering",   "0"},
            };
            for (const auto &v : kVars) {
                if (strcmp(var->key, v.key) == 0) {
                    var->value = v.value;
                    LOGI("GET_VARIABLE %s = %s", v.key, v.value);
                    return true;
                }
            }
            var->value = nullptr;
            return false;  // core usa o default dele
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            return true;  // aceita a declaração; valores vêm de GET_VARIABLE
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            *(bool *)data = false;  // nossas variáveis nunca mudam em runtime
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LANGUAGE: {
            *(unsigned *)data = 0;  // RETRO_LANGUAGE_ENGLISH
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            ((struct retro_log_callback *)data)->log = core_log_cb;
            return true;
        }
        case 37: { // RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO
            const auto *av = (const struct retro_system_av_info *)data;
            if (av && av->geometry.base_width > 0 && av->geometry.base_height > 0) {
                av_base_w = av->geometry.base_width;
                av_base_h = av->geometry.base_height;
                av_max_w = av->geometry.max_width;
                av_max_h = av->geometry.max_height;
                av_fps = av->timing.fps > 0 ? av->timing.fps : 60.0;
                av_sample_rate = av->timing.sample_rate > 0 ? av->timing.sample_rate : 44100.0;
                {
                    std::lock_guard<std::mutex> flock(frame_mutex);
                    fb_width = (int)av->geometry.base_width;
                    fb_height = (int)av->geometry.base_height;
                }
                LOGI("SET_SYSTEM_AV_INFO: %dx%d (max %dx%d) fps=%.2f rate=%.0f",
                     av->geometry.base_width, av->geometry.base_height,
                     av->geometry.max_width, av->geometry.max_height,
                     av->timing.fps, av->timing.sample_rate);
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: {
            *(unsigned *)data = RETRO_HW_CONTEXT_OPENGLES3;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            auto *cb = (struct retro_hw_render_callback *)data;
            if (cb->context_type != RETRO_HW_CONTEXT_OPENGLES2 &&
                cb->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
                cb->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION) {
                LOGE("SET_HW_RENDER: context_type=%d nao suportado (so GLES)",
                     cb->context_type);
                return false;
            }
            // O core preencheu context_reset/context_destroy (dele, nós
            // chamamos); nós preenchemos DE VOLTA no struct dele o que o
            // frontend fornece
            cb->get_current_framebuffer = hw_get_current_framebuffer;
            cb->get_proc_address = hw_get_proc_address;
            hw_render = *cb;  // cópia com os callbacks do core
            hw_render_enabled = true;
            gl_context_dirty.store(true);  // FBO + context_reset no 1º frame
            LOGI("SET_HW_RENDER aceito: type=%d v%u.%u depth=%d stencil=%d bottom_left=%d",
                 cb->context_type, cb->version_major, cb->version_minor,
                 cb->depth, cb->stencil, cb->bottom_left_origin);
            return true;
        }
        default:
            LOGI("environment_cb: cmd %u nao tratado (experimental=%d)",
                 cmd & 0xFFFFu, (cmd & 0x10000u) ? 1 : 0);
            return false;
    }
}

static void video_cb(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;  // frame dupe: keep last frame
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        // Frame renderizado pelo core direto no nosso FBO; NÃO dereferenciar
        std::lock_guard<std::mutex> lock(frame_mutex);
        fb_width = (int)width;
        fb_height = (int)height;
        hw_frame = true;
        frame_ready = true;
        return;
    }
    std::lock_guard<std::mutex> lock(frame_mutex);
    hw_frame = false;
    // Convert from core's pixel format to RGBA8888 (little-endian: R in low byte)
    fb_width = width;
    fb_height = height;
    frame_buffer.resize((size_t)width * height);

    const uint8_t *src = (const uint8_t *)data;
    switch (pixel_format) {
        case RETRO_PIXEL_FORMAT_XRGB8888:
            for (unsigned y = 0; y < height; y++) {
                const uint32_t *row = (const uint32_t *)(src + y * pitch);
                uint32_t *dst = frame_buffer.data() + (size_t)y * width;
                for (unsigned x = 0; x < width; x++) {
                    uint32_t px = row[x];
                    uint8_t r = (px >> 16) & 0xFF;
                    uint8_t g = (px >> 8) & 0xFF;
                    uint8_t b = px & 0xFF;
                    dst[x] = 0xFF000000u | ((uint32_t)b << 16) | ((uint32_t)g << 8) | r;
                }
            }
            break;
        case RETRO_PIXEL_FORMAT_RGB565:
            for (unsigned y = 0; y < height; y++) {
                const uint16_t *row = (const uint16_t *)(src + y * pitch);
                uint32_t *dst = frame_buffer.data() + (size_t)y * width;
                for (unsigned x = 0; x < width; x++) {
                    uint16_t px = row[x];
                    uint8_t r = (px >> 11) & 0x1F;
                    uint8_t g = (px >> 5) & 0x3F;
                    uint8_t b = px & 0x1F;
                    uint8_t r8 = (r << 3) | (r >> 2);
                    uint8_t g8 = (g << 2) | (g >> 4);
                    uint8_t b8 = (b << 3) | (b >> 2);
                    dst[x] = 0xFF000000u | ((uint32_t)b8 << 16) | ((uint32_t)g8 << 8) | r8;
                }
            }
            break;
        case RETRO_PIXEL_FORMAT_0RGB1555:
        default:
            for (unsigned y = 0; y < height; y++) {
                const uint16_t *row = (const uint16_t *)(src + y * pitch);
                uint32_t *dst = frame_buffer.data() + (size_t)y * width;
                for (unsigned x = 0; x < width; x++) {
                    uint16_t px = row[x];
                    uint8_t r = (px >> 10) & 0x1F;
                    uint8_t g = (px >> 5) & 0x1F;
                    uint8_t b = px & 0x1F;
                    uint8_t r8 = (r << 3) | (r >> 2);
                    uint8_t g8 = (g << 3) | (g >> 2);
                    uint8_t b8 = (b << 3) | (b >> 2);
                    dst[x] = 0xFF000000u | ((uint32_t)b8 << 16) | ((uint32_t)g8 << 8) | r8;
                }
            }
            break;
    }
    frame_ready = true;
}

static void audio_sample_cb(int16_t left, int16_t right) {
    std::lock_guard<std::mutex> lock(audio_mutex);
    audio_buffer.push_back(left);
    audio_buffer.push_back(right);
}

static size_t audio_sample_batch_cb(const int16_t *data, size_t frames) {
    std::lock_guard<std::mutex> lock(audio_mutex);
    // Cap at ~0.5s of stereo audio to avoid unbounded growth if Java stops draining
    const size_t max_samples = (size_t)(av_sample_rate) ;
    if (audio_buffer.size() + frames * 2 > max_samples) {
        size_t excess = audio_buffer.size() + frames * 2 - max_samples;
        if (excess >= audio_buffer.size()) audio_buffer.clear();
        else audio_buffer.erase(audio_buffer.begin(), audio_buffer.begin() + excess);
    }
    size_t old_size = audio_buffer.size();
    audio_buffer.resize(old_size + frames * 2);
    memcpy(audio_buffer.data() + old_size, data, frames * 2 * sizeof(int16_t));
    return frames;
}

static int input_poll_cb(void) {
    return 0;
}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port == 0 && device == RETRO_DEVICE_JOYPAD && id < 16) {
        return input_state[id] ? 1 : 0;
    }
    return 0;
}

// ─── JNI ───
extern "C" {
 
JNIEXPORT jboolean JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeLoadCore(JNIEnv *env, jobject thiz,
    jstring core_path) {
    const char *path = env->GetStringUTFChars(core_path, nullptr);
    LOGI("Loading core: %s", path);
    
    core_handle = dlopen(path, RTLD_LAZY | RTLD_LOCAL);
    if (!core_handle) {
        LOGE("Failed to dlopen core: %s", dlerror());
        env->ReleaseStringUTFChars(core_path, path);
        return JNI_FALSE;
    }
    
    // Load symbols
    #define LOAD_SYM(name) \
        *(void**)(&name) = dlsym(core_handle, #name); \
        if (!name) { LOGE("Missing symbol: %s", #name); dlclose(core_handle); core_handle = nullptr; return JNI_FALSE; }
    
    LOAD_SYM(retro_init);
    LOAD_SYM(retro_deinit);
    LOAD_SYM(retro_api_version);
    LOAD_SYM(retro_get_system_info);
    LOAD_SYM(retro_get_system_av_info);
    LOAD_SYM(retro_set_environment);
    LOAD_SYM(retro_set_video_refresh);
    LOAD_SYM(retro_set_audio_sample);
    LOAD_SYM(retro_set_audio_sample_batch);
    LOAD_SYM(retro_set_input_poll);
    LOAD_SYM(retro_set_input_state);
    LOAD_SYM(retro_reset);
    LOAD_SYM(retro_load_game);
    LOAD_SYM(retro_unload_game);
    LOAD_SYM(retro_run);
    LOAD_SYM(retro_serialize_size);
    LOAD_SYM(retro_serialize);
    LOAD_SYM(retro_unserialize);
    
    // Símbolos opcionais (podem não existir em alguns cores)
    *(void**)(&retro_get_memory_data) = dlsym(core_handle, "retro_get_memory_data");
    *(void**)(&retro_get_memory_size) = dlsym(core_handle, "retro_get_memory_size");
    LOGI("Memory API: data=%s size=%s",
         retro_get_memory_data ? "YES" : "NO",
         retro_get_memory_size ? "YES" : "NO");
    
    #undef LOAD_SYM
    
    // Set callbacks
    LOGI("Setting environment callbacks...");
    retro_set_environment(environment_cb);
    LOGI("retro_set_environment OK");
    retro_set_video_refresh(video_cb);
    LOGI("retro_set_video_refresh OK");
    retro_set_audio_sample(audio_sample_cb);
    retro_set_audio_sample(audio_sample_cb);
    retro_set_audio_sample_batch(audio_sample_batch_cb);
    retro_set_input_poll(input_poll_cb);
    retro_set_input_state(input_state_cb);

    // Play! PS2 core requires JavaVM to be set before retro_init
    // (CJavaVM::m_vm static, crashes in AttachCurrentThread otherwise)
    JavaVM* jvm = nullptr;
    env->GetJavaVM(&jvm);
    if (jvm) {
        typedef void (*SetJavaVM_t)(JavaVM*);
        SetJavaVM_t setJavaVM = (SetJavaVM_t)dlsym(core_handle,
            "_ZN9Framework7CJavaVM9SetJavaVMEP7_JavaVM");
        if (setJavaVM) {
            setJavaVM(jvm);
            LOGI("JavaVM set for Play! PS2 core");
        } else {
            LOGI("CJavaVM::SetJavaVM not found in core (non-Play core?)");
        }
    }

    LOGI("Calling retro_init...");
    retro_init();
    LOGI("retro_init OK");
    core_loaded.store(true);
    LOGI("Core initialized, API version: %u", retro_api_version());
    
    struct retro_system_info sys_info;
    retro_get_system_info(&sys_info);
    LOGI("Core: %s v%s, extensions: %s, need_fullpath: %d",
        sys_info.library_name, sys_info.library_version,
        sys_info.valid_extensions, sys_info.need_fullpath);
    
    env->ReleaseStringUTFChars(core_path, path);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeLoadGame(JNIEnv *env, jobject thiz,
    jstring rom_path) {
    if (!core_handle) return JNI_FALSE;
    
    const char *path = env->GetStringUTFChars(rom_path, nullptr);

    // Confere que o arquivo existe/é legível antes de entregar ao core
    FILE *f = fopen(path, "rb");
    if (!f) {
        LOGE("Failed to open ROM: %s", path);
        env->ReleaseStringUTFChars(rom_path, path);
        return JNI_FALSE;
    }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);

    // Check if core needs full path
    struct retro_system_info sys_info;
    retro_get_system_info(&sys_info);

    struct retro_game_info game_info;
    // Se o core precisa de fullpath OU o arquivo é zip/7z/imagem de disco
    // (precisa de descompressão/leitura pelo caminho), passa como fullpath
    bool use_fullpath = sys_info.need_fullpath;
    if (!use_fullpath) {
        const char *ext = strrchr(path, '.');
        if (ext && (strcasecmp(ext, ".zip") == 0 || strcasecmp(ext, ".7z") == 0
                 || strcasecmp(ext, ".cdi") == 0 || strcasecmp(ext, ".gdi") == 0
                 || strcasecmp(ext, ".chd") == 0 || strcasecmp(ext, ".cue") == 0
                 || strcasecmp(ext, ".3ds") == 0 || strcasecmp(ext, ".cci") == 0
                 || strcasecmp(ext, ".cxi") == 0)) {
            use_fullpath = true;
            LOGI("Container/large format detected, passing as fullpath instead of memory");
        }
    }

    if (use_fullpath) {
        fclose(f);
        game_info.path = path;
        game_info.data = nullptr;
        game_info.size = 0;
        game_info.meta = nullptr;
        LOGI("Loading by fullpath: %s (%ld bytes)", path, size);
    } else {
        rom_data.resize((size_t)size);
        size_t read = fread(rom_data.data(), 1, (size_t)size, f);
        fclose(f);
        if (read != (size_t)size) {
            LOGE("Short read on ROM: %zu of %ld bytes", read, size);
            rom_data.clear();
            env->ReleaseStringUTFChars(rom_path, path);
            return JNI_FALSE;
        }
        game_info.path = path;
        game_info.data = rom_data.data();
        game_info.size = (size_t)size;
        game_info.meta = nullptr;
        LOGI("Loading in-memory: %s (%ld bytes)", path, size);
    }

    bool ok = retro_load_game(&game_info);

    env->ReleaseStringUTFChars(rom_path, path);
    
    if (ok) {
        game_loaded.store(true);
        struct retro_system_av_info av_info;
        retro_get_system_av_info(&av_info);
        av_base_w = av_info.geometry.base_width;
        av_base_h = av_info.geometry.base_height;
        av_max_w = av_info.geometry.max_width;
        av_max_h = av_info.geometry.max_height;
        // CRÍTICO p/ cores HW: se o core não chamar video_cb(), fb_width/
        // fb_height ficariam 0 e o onDrawFrame() do Java não desenharia nada
        if (av_info.geometry.base_width > 0 && av_info.geometry.base_height > 0) {
            std::lock_guard<std::mutex> flock(frame_mutex);
            fb_width = (int)av_info.geometry.base_width;
            fb_height = (int)av_info.geometry.base_height;
        }
        av_fps = av_info.timing.fps > 0 ? av_info.timing.fps : 60.0;
        av_sample_rate = av_info.timing.sample_rate > 0 ? av_info.timing.sample_rate : 44100.0;
        LOGI("Game loaded: %dx%d, aspect=%.2f, fps=%.2f, sample_rate=%.0f",
            av_info.geometry.base_width, av_info.geometry.base_height,
            av_info.geometry.aspect_ratio, av_info.timing.fps,
            av_info.timing.sample_rate);
    } else {
        LOGE("Failed to load game");
    }
    
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeRunFrame(JNIEnv *env, jobject thiz) {
    if (!core_loaded.load() || !game_loaded.load()) return;
    std::lock_guard<std::mutex> lock(run_mutex);
    if (core_loaded.load() && game_loaded.load() && core_handle) {
        // Contexto GL novo (1ª vez ou recriado no resume): FBO + context_reset
        // do core, obrigatoriamente aqui — GL thread com contexto current
        if (hw_render_enabled && gl_context_dirty.exchange(false)) {
            create_hw_fbo();
            create_presenter();
            if (hw_render.context_reset) {
                LOGI("Chamando context_reset do core");
                hw_render.context_reset();
            }
        }
        retro_run();
        if (hw_render_enabled) {
            // Video CB pode ter sido chamado com pixel data (SW fallback)
            // ou RETRO_HW_FRAME_BUFFER_VALID; só marca como HW se o core
            // realmente renderizou no FBO
            std::lock_guard<std::mutex> flock(frame_mutex);
            if (!frame_ready) {
                // video_cb() não foi chamada: assume que o core renderizou
                // no FBO (HW path sem video_cb)
                hw_frame = true;
                frame_ready = true;
            }
            // Se frame_ready já true, video_cb() definiu hw_frame corretamente
        }
            // v42: NENHUM reset de estado — o state tracker do core assume
            // que nada muda entre retro_run()s. A apresentação salva e
            // restaura tudo que toca.
            present_hw_frame();
    }
}

JNIEXPORT void JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeSetScreenSize(JNIEnv *env, jobject thiz,
    jint w, jint h) {
    screen_w = w > 0 ? w : 1;
    screen_h = h > 0 ? h : 1;
}

JNIEXPORT jint JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeGetHwTextureId(JNIEnv *env, jobject thiz) {
    return (jint)hw_fbo_tex;
}

JNIEXPORT jint JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeGetLayoutMode(JNIEnv *env, jobject thiz) {
    return layout_mode;
}

JNIEXPORT void JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeSetLayoutMode(JNIEnv *env, jobject thiz,
    jint mode) {
    layout_mode = mode;
    LOGI("Layout mode set to %d", mode);
}

JNIEXPORT void JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeGLContextCreated(JNIEnv *env, jobject thiz) {
    gl_context_dirty.store(true);
    LOGI("GL context (re)criado pela GLSurfaceView");
}

JNIEXPORT jboolean JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeIsHwRender(JNIEnv *env, jobject thiz) {
    return hw_render_enabled ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeGetFBWidth(JNIEnv *env, jobject thiz) {
    return fb_width;
}

JNIEXPORT jint JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeGetFBHeight(JNIEnv *env, jobject thiz) {
    return fb_height;
}

JNIEXPORT jboolean JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeGetFrameBuffer(JNIEnv *env, jobject thiz,
    jintArray buf) {
    std::lock_guard<std::mutex> lock(frame_mutex);
    // Return the latest frame even if not new, so the renderer never draws black
    if (frame_buffer.empty()) return JNI_FALSE;

    jsize len = env->GetArrayLength(buf);
    if (len < (jsize)frame_buffer.size()) return JNI_FALSE;

    env->SetIntArrayRegion(buf, 0, frame_buffer.size(), (const jint *)frame_buffer.data());
    frame_ready = false;
    return JNI_TRUE;
}

JNIEXPORT jdouble JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeGetFPS(JNIEnv *env, jobject thiz) {
    return av_fps;
}

JNIEXPORT jint JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeGetSampleRate(JNIEnv *env, jobject thiz) {
    return (jint)av_sample_rate;
}

JNIEXPORT jint JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeReadAudio(JNIEnv *env, jobject thiz,
    jshortArray buf) {
    std::lock_guard<std::mutex> lock(audio_mutex);
    if (audio_buffer.empty()) return 0;
    jsize len = env->GetArrayLength(buf);
    size_t count = audio_buffer.size() < (size_t)len ? audio_buffer.size() : (size_t)len;
    env->SetShortArrayRegion(buf, 0, count, audio_buffer.data());
    audio_buffer.erase(audio_buffer.begin(), audio_buffer.begin() + count);
    return (jint)count;
}

JNIEXPORT void JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeSetSystemDir(JNIEnv *env, jobject thiz,
    jstring dir) {
    const char *d = env->GetStringUTFChars(dir, nullptr);
    strncpy(system_dir, d, sizeof(system_dir) - 1);
    system_dir[sizeof(system_dir) - 1] = '\0';
    env->ReleaseStringUTFChars(dir, d);
}

JNIEXPORT void JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeSetSaveDir(JNIEnv *env, jobject thiz,
    jstring dir) {
    const char *d = env->GetStringUTFChars(dir, nullptr);
    strncpy(save_dir, d, sizeof(save_dir) - 1);
    save_dir[sizeof(save_dir) - 1] = '\0';
    env->ReleaseStringUTFChars(dir, d);
    LOGI("Save dir set: %s", save_dir);
}

JNIEXPORT void JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeSetButton(JNIEnv *env, jobject thiz,
    jint id, jboolean pressed) {
    if (id >= 0 && id < 16) {
        input_state[id] = pressed;
    }
}

JNIEXPORT void JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeReset(JNIEnv *env, jobject thiz) {
    if (!core_loaded.load() || !game_loaded.load()) return;
    std::lock_guard<std::mutex> lock(run_mutex);
    if (core_loaded.load() && game_loaded.load() && core_handle) retro_reset();
}

JNIEXPORT void JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeSetCrashLogPath(JNIEnv *env, jobject thiz,
    jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    strncpy(crash_log_path, p, sizeof(crash_log_path) - 1);
    crash_log_path[sizeof(crash_log_path) - 1] = '\0';
    env->ReleaseStringUTFChars(path, p);
    install_crash_handler();
    LOGI("Crash handler instalado, log: %s", crash_log_path);
}

JNIEXPORT jlong JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeGetSerializeSize(JNIEnv *env, jobject thiz) {
    if (!retro_serialize_size || !core_loaded.load() || !game_loaded.load()) return 0;
    std::lock_guard<std::mutex> lock(run_mutex);
    size_t sz = retro_serialize_size();
    LOGI("Serialize size: %zu bytes", sz);
    return (jlong)sz;
}

JNIEXPORT jboolean JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeSerialize(JNIEnv *env, jobject thiz,
    jbyteArray buf) {
    if (!retro_serialize || !core_loaded.load() || !game_loaded.load()) return JNI_FALSE;
    jsize len = env->GetArrayLength(buf);
    jbyte *data = env->GetByteArrayElements(buf, nullptr);
    std::lock_guard<std::mutex> lock(run_mutex);
    bool ok = retro_serialize(data, (size_t)len);
    env->ReleaseByteArrayElements(buf, data, 0);
    if (!ok) LOGE("retro_serialize() failed");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeUnserialize(JNIEnv *env, jobject thiz,
    jbyteArray buf) {
    if (!retro_unserialize || !core_loaded.load() || !game_loaded.load()) return JNI_FALSE;
    jsize len = env->GetArrayLength(buf);
    jbyte *data = env->GetByteArrayElements(buf, nullptr);
    std::lock_guard<std::mutex> lock(run_mutex);
    bool ok = retro_unserialize(data, (size_t)len);
    env->ReleaseByteArrayElements(buf, data, 0);
    if (!ok) LOGE("retro_unserialize() failed");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeGetSram(JNIEnv *env, jobject thiz) {
    if (!retro_get_memory_data || !retro_get_memory_size ||
        !core_loaded.load() || !game_loaded.load()) return nullptr;
    size_t sz = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (sz == 0) return nullptr;
    void *data = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!data) return nullptr;
    jbyteArray result = env->NewByteArray((jsize)sz);
    if (result) {
        env->SetByteArrayRegion(result, 0, (jsize)sz, (const jbyte *)data);
        LOGI("SRAM read: %zu bytes", sz);
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeSetSram(JNIEnv *env, jobject thiz,
    jbyteArray buf) {
    if (!retro_get_memory_data || !retro_get_memory_size ||
        !core_loaded.load() || !game_loaded.load()) return JNI_FALSE;
    size_t sz = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (sz == 0) return JNI_FALSE;
    void *data = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!data) return JNI_FALSE;
    jsize len = env->GetArrayLength(buf);
    size_t copy_sz = (size_t)len < sz ? (size_t)len : sz;
    env->GetByteArrayRegion(buf, 0, (jsize)copy_sz, (jbyte *)data);
    LOGI("SRAM written: %zu bytes", copy_sz);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_emuhub_app_EmulatorActivity_nativeUnload(JNIEnv *env, jobject thiz) {
    // Sinaliza primeiro: quem ainda não entrou em retro_run() desiste;
    // quem já entrou termina o frame antes de pegarmos o mutex.
    core_loaded.store(false);
    std::lock_guard<std::mutex> lock(run_mutex);
    if (core_handle) {
        // context_destroy do core precisa de contexto GL current — o Java
        // chama nativeUnload via queueEvent na GL thread; se cair no
        // fallback (UI thread, sem contexto), pula
        if (hw_render_enabled && hw_render.context_destroy &&
            eglGetCurrentContext() != EGL_NO_CONTEXT) {
            LOGI("Chamando context_destroy do core");
            hw_render.context_destroy();
        }
        // Só descarrega o jogo se ele chegou a carregar; retro_unload_game()
        // após retro_load_game()==false crasha (ex.: Citra, fault_addr=0x8)
        if (game_loaded.load()) {
            retro_unload_game();
            game_loaded.store(false);
        }
        retro_deinit();
        dlclose(core_handle);
        core_handle = nullptr;
    }
    hw_render_enabled = false;
    memset(&hw_render, 0, sizeof(hw_render));
    gl_context_dirty.store(false);
    // ids GL morrem com o contexto EGL da activity; só zera as referências
    pres_program = pres_vao = pres_vbo = 0;
    hw_fbo = hw_fbo_tex = hw_fbo_depth = 0;
    hw_fbo_w = hw_fbo_h = 0;
    hw_frame = false;
    av_max_w = av_max_h = 0;
    fb_width = 0;
    fb_height = 0;
    frame_buffer.clear();
    frame_ready = false;
    audio_buffer.clear();
    rom_data.clear();
    rom_data.shrink_to_fit();
    pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
}


} // extern "C"

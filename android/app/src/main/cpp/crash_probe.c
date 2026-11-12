// Native crash probe: catches SIGSEGV/SIGBUS/SIGABRT/... and records where the
// process actually died before letting it die.
//
// Why it is needed: 红果's packer ends the process with a native SIGSEGV
// (ApplicationExitInfo: reason=5 / status=11). The Java side sees nothing and
// logcat only keeps a protobuf dump, so the only way to tell "their fixed
// suicide point" from "our environment made them dereference NULL" is to read
// the faulting pc/lr and backtrace ourselves.

#include <android/log.h>
#include <fcntl.h>
#include <inttypes.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#define PROBE_TAG "CrashProbe"
#define MAPS_BUFFER_SIZE (256 * 1024)
#define MAX_FRAMES 40

static int g_fd = -1;
static volatile sig_atomic_t g_in_handler = 0;
static char g_maps[MAPS_BUFFER_SIZE];

/* ---------- async-signal-safe helpers ---------- */

static void out_raw(const char *text, size_t length) {
    if (g_fd >= 0) {
        ssize_t ignored = write(g_fd, text, length);
        (void) ignored;
    }
}

static void out_str(const char *text) {
    out_raw(text, strlen(text));
}

static void out_hex(uintptr_t value) {
    char buffer[20];
    static const char digits[] = "0123456789abcdef";
    buffer[0] = '0';
    buffer[1] = 'x';
    int index = 18;
    buffer[19] = '\0';
    if (value == 0) {
        buffer[18] = '0';
        out_raw(buffer + 18, 1);
        return;
    }
    while (value != 0 && index > 1) {
        buffer[index--] = digits[value & 0xF];
        value >>= 4;
    }
    out_raw(buffer + index + 1, 18 - index);
}

static void out_dec(long value) {
    char buffer[24];
    int length = snprintf(buffer, sizeof(buffer), "%ld", value);
    if (length > 0) out_raw(buffer, (size_t) length);
}

static void out_hex_line(const char *label, uintptr_t value) {
    out_str(label);
    out_hex(value);
    out_str("\n");
}

/* ---------- /proc/self/maps resolution ---------- */

static size_t load_maps(void) {
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return 0;
    size_t total = 0;
    while (total < sizeof(g_maps) - 1) {
        ssize_t read_bytes = read(fd, g_maps + total, sizeof(g_maps) - 1 - total);
        if (read_bytes <= 0) break;
        total += (size_t) read_bytes;
    }
    close(fd);
    g_maps[total] = '\0';
    return total;
}

/* Writes "<module>+0x<offset> <perms>" for the mapping containing address. */
static void resolve_address(uintptr_t address) {
    if (address == 0) {
        out_str("<null>\n");
        return;
    }
    char *cursor = g_maps;
    while (cursor != NULL && *cursor != '\0') {
        char *line_end = strchr(cursor, '\n');
        if (line_end == NULL) line_end = cursor + strlen(cursor);

        uintptr_t start = 0;
        uintptr_t end = 0;
        char *dash = strchr(cursor, '-');
        if (dash != NULL && dash < line_end) {
            start = strtoull(cursor, NULL, 16);
            end = strtoull(dash + 1, NULL, 16);
            if (address >= start && address < end) {
                /* "<start>-<end> perms offset dev inode pathname" */
                char *perms = strchr(dash, ' ');
                char *path = NULL;
                if (perms != NULL) {
                    char *fields = perms;
                    for (int i = 0; i < 4 && fields != NULL; i++) {
                        fields = strchr(fields + 1, ' ');
                    }
                    if (fields != NULL) {
                        char *name = fields + 1;
                        while (*name == ' ') name++;
                        if (name < line_end) path = name;
                    }
                }
                if (path != NULL) {
                    size_t length = (size_t) (line_end - path);
                    out_raw(path, length);
                } else {
                    out_str("<?>");
                }
                out_str("+");
                out_hex(address - start);
                return;
            }
        }
        if (*line_end == '\0') break;
        cursor = line_end + 1;
    }
    out_str("<unknown>\n");
}

/* ---------- unwind ---------- */

struct unwind_state {
    int count;
};

static _Unwind_Reason_Code unwind_callback(struct _Unwind_Context *context, void *arg) {
    struct unwind_state *state = (struct unwind_state *) arg;
    uintptr_t pc = (uintptr_t) _Unwind_GetIP(context);
    if (pc == 0) return _URC_END_OF_STACK;
    out_str("  frame ");
    out_dec(state->count);
    out_str(": ");
    out_hex(pc);
    out_str(" ");
    resolve_address(pc);
    out_str("\n");
    state->count++;
    return state->count >= MAX_FRAMES ? _URC_END_OF_STACK : _URC_NO_REASON;
}

/* ---------- handler ---------- */

static void write_thread_name(void) {
    char path[64];
    long tid = (long) syscall(__NR_gettid);
    snprintf(path, sizeof(path), "/proc/self/task/%ld/comm", tid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return;
    char name[32];
    ssize_t read_bytes = read(fd, name, sizeof(name) - 1);
    close(fd);
    if (read_bytes <= 0) return;
    name[read_bytes] = '\0';
    char *newline = strchr(name, '\n');
    if (newline != NULL) *newline = '\0';
    out_str("thread=");
    out_str(name);
    out_str("\n");
}

static void crash_handler(int signal_number, siginfo_t *info, void *context) {
    if (g_in_handler) {
        /* Second fault inside the handler: give up and let the process die. */
        signal(signal_number, SIG_DFL);
        raise(signal_number);
        return;
    }
    g_in_handler = 1;

    out_str("\n==== native crash ====\n");
    out_str("timestamp=");
    out_dec((long) time(NULL));
    out_str("\nsignal=");
    out_dec(signal_number);
    out_str(" code=");
    out_dec(info != NULL ? info->si_code : -1);
    out_str("\nfault_address=");
    out_hex((uintptr_t) (info != NULL ? info->si_addr : NULL));
    out_str("\n");
    write_thread_name();

    uintptr_t pc = 0;
    uintptr_t lr = 0;
    uintptr_t sp = 0;
#if defined(__aarch64__)
    if (context != NULL) {
        ucontext_t *uc = (ucontext_t *) context;
        pc = (uintptr_t) uc->uc_mcontext.pc;
        lr = (uintptr_t) uc->uc_mcontext.regs[30];
        sp = (uintptr_t) uc->uc_mcontext.sp;
    }
#elif defined(__arm__)
    if (context != NULL) {
        ucontext_t *uc = (ucontext_t *) context;
        pc = (uintptr_t) uc->uc_mcontext.arm_pc;
        lr = (uintptr_t) uc->uc_mcontext.arm_lr;
        sp = (uintptr_t) uc->uc_mcontext.arm_sp;
    }
#else
    (void) context;
#endif

    load_maps();
    out_hex_line("pc=", pc);
    out_str("  pc -> ");
    resolve_address(pc);
    out_str("\n");
    out_hex_line("lr=", lr);
    out_str("  lr -> ");
    resolve_address(lr);
    out_str("\n");
    out_hex_line("sp=", sp);
    out_str("\nbacktrace:\n");

    struct unwind_state state = {0};
    _Unwind_Backtrace(unwind_callback, &state);
    out_str("==== end ====\n");
    if (g_fd >= 0) fsync(g_fd);

    /* Behave like the default handler: AMS/tombstone machinery stays intact. */
    signal(signal_number, SIG_DFL);
    raise(signal_number);
}

/* ---------- JNI ---------- */

JNIEXPORT jboolean JNICALL
Java_com_adv_bb_app_guest_CrashProbe_nativeInstall(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    if (path == NULL) return JNI_FALSE;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (chars == NULL) return JNI_FALSE;

    if (g_fd < 0) {
        g_fd = open(chars, O_WRONLY | O_CREAT | O_APPEND, 0600);
    }
    (*env)->ReleaseStringUTFChars(env, path, chars);
    if (g_fd < 0) return JNI_FALSE;

    static char alt_stack[SIGSTKSZ * 4];
    stack_t stack;
    memset(&stack, 0, sizeof(stack));
    stack.ss_sp = alt_stack;
    stack.ss_size = sizeof(alt_stack);
    sigaltstack(&stack, NULL);

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = crash_handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    sigaction(SIGSEGV, &action, NULL);
    sigaction(SIGBUS, &action, NULL);
    sigaction(SIGABRT, &action, NULL);
    sigaction(SIGILL, &action, NULL);
    sigaction(SIGFPE, &action, NULL);

    __android_log_print(ANDROID_LOG_INFO, PROBE_TAG, "handler installed");
    return JNI_TRUE;
}

#include <jni.h>
#include <pty.h>
#include <poll.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct {
    int master_fd;
    pid_t child_pid;
    int closed;
} terminal_pty;

static void throw_state(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
}

static char **copy_string_array(JNIEnv *env, jobjectArray source) {
    jsize count = (*env)->GetArrayLength(env, source);
    char **items = calloc((size_t)count + 1, sizeof(char *));
    if (!items) return NULL;
    for (jsize i = 0; i < count; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, source, i);
        if (!value) goto fail;
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        if (!utf) {
            (*env)->DeleteLocalRef(env, value);
            goto fail;
        }
        items[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
        if (!items[i]) goto fail;
    }
    return items;
fail:
    for (jsize i = 0; i < count; i++) free(items[i]);
    free(items);
    return NULL;
}

static void free_string_array(char **items) {
    if (!items) return;
    for (size_t i = 0; items[i]; i++) free(items[i]);
    free(items);
}

JNIEXPORT jlong JNICALL
Java_io_github_m96chan_droidrunner_terminal_NativePty_nativeSpawn(
        JNIEnv *env, jobject self, jobjectArray argv_array,
        jobjectArray env_array, jint rows, jint columns) {
    (void)self;
    char **argv = copy_string_array(env, argv_array);
    char **envp = copy_string_array(env, env_array);
    if (!argv || !envp || !argv[0] || argv[0][0] == '\0') {
        free_string_array(argv);
        free_string_array(envp);
        throw_state(env, "Invalid PTY launch arguments");
        return 0;
    }

    terminal_pty *session = calloc(1, sizeof(terminal_pty));
    if (!session) {
        free_string_array(argv);
        free_string_array(envp);
        throw_state(env, "Unable to allocate PTY session");
        return 0;
    }
    session->master_fd = -1;
    struct winsize size = {0};
    size.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    size.ws_col = (unsigned short)(columns > 0 ? columns : 80);

    pid_t child = forkpty(&session->master_fd, NULL, NULL, &size);
    if (child < 0) {
        int saved = errno;
        free(session);
        free_string_array(argv);
        free_string_array(envp);
        throw_state(env, strerror(saved));
        return 0;
    }
    if (child == 0) {
        execve(argv[0], argv, envp);
        _exit(127);
    }

    session->child_pid = child;
    free_string_array(argv);
    free_string_array(envp);
    return (jlong)(intptr_t)session;
}

JNIEXPORT jint JNICALL
Java_io_github_m96chan_droidrunner_terminal_NativePty_nativeRead(
        JNIEnv *env, jobject self, jlong handle, jbyteArray target,
        jint offset, jint length) {
    (void)self;
    terminal_pty *session = (terminal_pty *)(intptr_t)handle;
    if (!session || session->closed || session->master_fd < 0) return 0;
    jsize capacity = (*env)->GetArrayLength(env, target);
    if (offset < 0 || length < 0 || offset > capacity || length > capacity - offset)
        return -EINVAL;
    if (length == 0) return 0;

    struct pollfd descriptor = { .fd = session->master_fd, .events = POLLIN | POLLHUP | POLLERR };
    int ready;
    do { ready = poll(&descriptor, 1, 250); } while (ready < 0 && errno == EINTR);
    if (ready == 0) return -EAGAIN;
    if (ready < 0) return -errno;

    jbyte *buffer = malloc((size_t)length);
    if (!buffer) return -ENOMEM;
    ssize_t count;
    do { count = read(session->master_fd, buffer, (size_t)length); }
    while (count < 0 && errno == EINTR);
    if (count > 0) (*env)->SetByteArrayRegion(env, target, offset, (jsize)count, buffer);
    int saved = errno;
    free(buffer);
    if (count > 0) return (jint)count;
    if (count == 0 || saved == EIO || saved == EBADF) return 0;
    return -saved;
}

JNIEXPORT jint JNICALL
Java_io_github_m96chan_droidrunner_terminal_NativePty_nativeWrite(
        JNIEnv *env, jobject self, jlong handle, jbyteArray source,
        jint offset, jint length) {
    (void)self;
    terminal_pty *session = (terminal_pty *)(intptr_t)handle;
    if (!session || session->closed || session->master_fd < 0) return -EBADF;
    jsize capacity = (*env)->GetArrayLength(env, source);
    if (offset < 0 || length < 0 || offset > capacity || length > capacity - offset)
        return -EINVAL;
    jbyte *bytes = malloc((size_t)length);
    if (!bytes && length != 0) return -ENOMEM;
    if (length) (*env)->GetByteArrayRegion(env, source, offset, length, bytes);
    ssize_t count;
    do { count = write(session->master_fd, bytes, (size_t)length); }
    while (count < 0 && errno == EINTR);
    int saved = errno;
    free(bytes);
    return count >= 0 ? (jint)count : -saved;
}

JNIEXPORT jboolean JNICALL
Java_io_github_m96chan_droidrunner_terminal_NativePty_nativeResize(
        JNIEnv *env, jobject self, jlong handle, jint rows, jint columns) {
    (void)env; (void)self;
    terminal_pty *session = (terminal_pty *)(intptr_t)handle;
    if (!session || session->closed || session->master_fd < 0 || rows < 1 || columns < 1)
        return JNI_FALSE;
    struct winsize size = {0};
    size.ws_row = (unsigned short)rows;
    size.ws_col = (unsigned short)columns;
    return ioctl(session->master_fd, TIOCSWINSZ, &size) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_m96chan_droidrunner_terminal_NativePty_nativeClose(
        JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    terminal_pty *session = (terminal_pty *)(intptr_t)handle;
    if (!session || session->closed) return;
    session->closed = 1;
    if (session->child_pid > 0) {
        kill(-session->child_pid, SIGHUP);
        int status;
        pid_t result = 0;
        for (int i = 0; i < 30 && result == 0; i++) {
            result = waitpid(session->child_pid, &status, WNOHANG);
            if (result == 0) {
                struct timespec pause = { .tv_sec = 0, .tv_nsec = 10000000 };
                nanosleep(&pause, NULL);
            }
        }
        if (result == 0) {
            kill(-session->child_pid, SIGKILL);
            while (waitpid(session->child_pid, &status, 0) < 0 && errno == EINTR) { }
        }
        session->child_pid = -1;
    }
    if (session->master_fd >= 0) {
        close(session->master_fd);
        session->master_fd = -1;
    }
}

JNIEXPORT void JNICALL
Java_io_github_m96chan_droidrunner_terminal_NativePty_nativeRelease(
        JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    terminal_pty *session = (terminal_pty *)(intptr_t)handle;
    if (!session) return;
    if (!session->closed)
        Java_io_github_m96chan_droidrunner_terminal_NativePty_nativeClose(env, self, handle);
    free(session);
}

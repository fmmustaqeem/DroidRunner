package io.github.m96chan.droidrunner.terminal

/**
 * Thin JNI wrapper around Android's Bionic forkpty(3). The caller owns the
 * reader thread and must stop it before calling release().
 *
 * Return values from read/write are byte counts, zero for EOF, or negative
 * errno values. A read timeout is -EAGAIN (11 on Android).
 */
internal object NativePty {
    init {
        System.loadLibrary("terminal_pty")
    }

    external fun nativeSpawn(
        argv: Array<String>,
        environment: Array<String>,
        rows: Int,
        columns: Int,
    ): Long

    external fun nativeRead(handle: Long, target: ByteArray, offset: Int, length: Int): Int

    external fun nativeWrite(handle: Long, source: ByteArray, offset: Int, length: Int): Int

    external fun nativeResize(handle: Long, rows: Int, columns: Int): Boolean

    /** Sends SIGHUP to the PTY process group, then SIGKILL if it does not exit. */
    external fun nativeClose(handle: Long)

    /** Frees the native session object after the reader thread has stopped. */
    external fun nativeRelease(handle: Long)
}

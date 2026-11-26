package io.github.m96chan.droidrunner.runner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps PRoot's symlink-backed hard-link emulation confined to Terminal. */
class TerminalProotCommandTest {
    private val nativeDir = "/data/app/io.github.m96chan.droidrunner-1/lib/arm64"
    private val cacheTmp = "/data/user/0/io.github.m96chan.droidrunner/cache/proot-tmp"
    private val runtimeDir = File("/data/user/0/io.github.m96chan.droidrunner/files/runner-runtime")
    private val guestCommand = listOf("/bin/bash", "--noprofile", "--norc")

    private fun terminalInvocation() = RunnerCommand.terminalProotCommand(
        nativeDir, cacheTmp, runtimeDir, guestCommand,
    )

    @Test fun terminalEnablesLinkEmulationBeforeTheGuestCommand() {
        val command = terminalInvocation().command()
        assertEquals("$nativeDir/libproot.so", command.first())
        assertEquals("-l", command[1])
        assertTrue(command.indexOf("-l") < command.indexOf("/usr/bin/env"))
        assertEquals(guestCommand, command.takeLast(guestCommand.size))
    }

    @Test fun terminalKeepsTheHostRuntimeConfiguration() {
        val env = terminalInvocation().environment()
        assertEquals("$nativeDir/libproot-loader.so", env["PROOT_LOADER"])
        assertEquals("$nativeDir/libproot-loader32.so", env["PROOT_LOADER_32"])
        assertEquals(cacheTmp, env["PROOT_TMP_DIR"])
    }

    @Test fun officialRunnerInvocationDoesNotEnableTerminalLinkEmulation() {
        val command = RunnerCommand.prootCommand(
            nativeDir, cacheTmp, runtimeDir, listOf("/home/runner/run.sh"),
        ).command()
        assertFalse(command.contains("-l"))
    }
}

package io.github.m96chan.droidrunner.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.m96chan.droidrunner.runner.RunnerCommand
import io.github.m96chan.droidrunner.runtime.RuntimeInstaller
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

private val TerminalBackground = Color(0xFF101418)
private val TerminalText = Color(0xFFD6E2E8)
private val TerminalGreen = Color(0xFF75D69C)

/**
 * App-private shell in the installed Ubuntu rootfs. This is deliberately a
 * separate Process from RunnerService: stopping or closing the terminal never
 * signals the GitHub Actions listener.
 *
 * Android Process streams are not a PTY. This first version is a persistent,
 * line-oriented Bash console; full-screen curses programs, job control and
 * terminal resize semantics require a native PTY implementation.
 */
private class TerminalSession(
    runtime: RuntimeInstaller,
    nativeLibraryDir: String,
    cacheDir: java.io.File,
    private val onLine: (String) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    fun start() {
        check(runtime.installed) { "Linux runtime is not installed." }
        val command = RunnerCommand.prootCommand(
            nativeDir = nativeLibraryDir,
            prootTmpDir = java.io.File(cacheDir, "proot-tmp").apply { mkdirs() }.absolutePath,
            dir = runtime.runtimeDir,
            command = listOf("/bin/bash", "--noprofile", "--norc"),
        )
        command.redirectErrorStream(true)
        process = command.start()
        writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
        Thread({
            try {
                BufferedReader(InputStreamReader(process!!.inputStream)).useLines { lines ->
                    lines.forEach { if (!closed.get()) onLine(it) }
                }
            } catch (failure: Throwable) {
                if (!closed.get()) onLine("[terminal read error] ${failure.message}")
            } finally {
                if (!closed.get()) onLine("[shell exited]")
            }
        }, "DroidRunner-Terminal-Output").apply { isDaemon = true; start() }
        onLine("Ubuntu shell ready. Type commands below.")
    }

    fun send(command: String) {
        check(!closed.get()) { "Terminal is stopped." }
        val output = writer ?: error("Terminal has not started.")
        output.write(command)
        output.newLine()
        output.flush()
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { writer?.close() }
        process?.destroy()
        process = null
        writer = null
    }
}

@Composable
fun TerminalScreen(
    runtime: RuntimeInstaller,
    nativeLibraryDir: String,
    cacheDir: java.io.File,
    onBack: () -> Unit,
) {
    val lines = remember { mutableStateListOf<String>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val appendLine: (String) -> Unit = { line ->
        mainHandler.post {
            lines.add(line)
            if (lines.size > 1200) repeat(lines.size - 1200) { lines.removeAt(0) }
        }
    }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Starting shell…") }
    val scroll = rememberScrollState()
    val keyboard = LocalSoftwareKeyboardController.current
    val session = remember(runtime) {
        TerminalSession(runtime, nativeLibraryDir, cacheDir, appendLine)
    }

    DisposableEffect(session) {
        try {
            session.start()
            status = "Connected · Ubuntu / PRoot"
        } catch (failure: Throwable) {
            status = "Unable to start: ${failure.message}"
            lines.add(status)
        }
        onDispose { session.stop() }
    }
    LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }

    Column(
        Modifier.fillMaxSize().background(TerminalBackground).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Linux Terminal", color = TerminalGreen)
            OutlinedButton(onClick = onBack) { Text("Dashboard") }
        }
        Text(status, color = TerminalGreen)
        Column(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF080B0E))
                .verticalScroll(scroll).padding(8.dp),
        ) {
            lines.forEach { line ->
                Text(line, color = TerminalText, fontFamily = FontFamily.Monospace)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Ubuntu command") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val command = input
                    if (command.isNotBlank()) {
                        lines.add("$ $command")
                        runCatching { session.send(command) }
                            .onFailure { lines.add("[send error] ${it.message}") }
                        input = ""
                    }
                    keyboard?.hide()
                }),
            )
            Button(onClick = {
                val command = input
                if (command.isNotBlank()) {
                    lines.add("$ $command")
                    runCatching { session.send(command) }
                        .onFailure { lines.add("[send error] ${it.message}") }
                    input = ""
                }
            }) { Text("Run") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                session.stop()
                status = "Stopped"
                lines.add("[terminal stopped]")
            }) { Text("Stop shell") }
        }
    }
}

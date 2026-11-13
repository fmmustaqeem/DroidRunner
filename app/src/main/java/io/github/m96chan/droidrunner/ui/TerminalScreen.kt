package io.github.m96chan.droidrunner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m96chan.droidrunner.runner.RunnerCommand
import io.github.m96chan.droidrunner.runtime.RuntimeInstaller
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

private val TerminalBackground = Color(0xFF101418)
private val TerminalText = Color.White
private val TerminalInput = Color(0xFF39FF14)
private val TerminalGreen = Color(0xFF75D69C)
private const val TERMINAL_HISTORY_PREFS = "fm_droid_terminal_history"
private const val TERMINAL_HISTORY_KEY = "output_lines"
private const val TERMINAL_FONT_SIZE_KEY = "font_size_sp"
private const val TERMINAL_LIGHT_THEME_KEY = "light_theme"
private const val TERMINAL_HISTORY_LIMIT = 1200

/**
 * App-private shell in the installed Ubuntu rootfs. This is deliberately a
 * separate Process from RunnerService: stopping or closing the terminal never
 * signals the GitHub Actions listener.
 *
 * Android Process streams are not a PTY. This is a persistent, line-oriented
 * Bash console; full-screen curses programs, job control, raw Ctrl-C and
 * terminal resize semantics require a native PTY implementation.
 */
private class TerminalSession(
    private val runtime: RuntimeInstaller,
    private val nativeLibraryDir: String,
    private val cacheDir: java.io.File,
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
        // This process has no PTY. "dumb" avoids tools assuming xterm features
        // and prevents the common "TERM environment variable not set" warning.
        command.environment()["TERM"] = "dumb"
        command.redirectErrorStream(true)
        val running = command.start()
        process = running
        writer = BufferedWriter(OutputStreamWriter(running.outputStream))
        Thread({
            try {
                BufferedReader(InputStreamReader(running.inputStream)).useLines { lines ->
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
    val context = LocalContext.current
    val historyPrefs = remember(context) {
        context.getSharedPreferences(TERMINAL_HISTORY_PREFS, android.content.Context.MODE_PRIVATE)
    }
    val savedHistory = remember(historyPrefs) {
        historyPrefs.getString(TERMINAL_HISTORY_KEY, null)
            ?.takeIf { it.isNotEmpty() }
            ?.split('\n')
            ?.takeLast(TERMINAL_HISTORY_LIMIT)
            .orEmpty()
    }
    // "Clear screen" only clears this display buffer. The persisted transcript
    // remains intact and is restored the next time the Terminal is opened.
    val lines = remember(historyPrefs) { mutableStateListOf<String>().apply { addAll(savedHistory) } }
    val history = remember(historyPrefs) { mutableStateListOf<String>().apply { addAll(savedHistory) } }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val appendLine: (String) -> Unit = { line ->
        mainHandler.post {
            lines.add(line)
            history.add(line)
            if (lines.size > TERMINAL_HISTORY_LIMIT) {
                repeat(lines.size - TERMINAL_HISTORY_LIMIT) { lines.removeAt(0) }
            }
            if (history.size > TERMINAL_HISTORY_LIMIT) {
                repeat(history.size - TERMINAL_HISTORY_LIMIT) { history.removeAt(0) }
            }
        }
    }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var status by remember { mutableStateOf("Starting shell…") }
    var fontSize by remember {
        mutableStateOf(historyPrefs.getFloat(TERMINAL_FONT_SIZE_KEY, 14f).coerceIn(10f, 24f))
    }
    var lightTheme by remember { mutableStateOf(historyPrefs.getBoolean(TERMINAL_LIGHT_THEME_KEY, false)) }
    val scroll = rememberScrollState()
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val background = if (lightTheme) Color(0xFFF4F6F8) else TerminalBackground
    val outputBackground = if (lightTheme) Color.White else Color(0xFF080B0E)
    val outputText = if (lightTheme) Color(0xFF18212B) else TerminalText
    val commandText = if (lightTheme) Color(0xFF087A24) else TerminalInput
    val session = remember(runtime) {
        TerminalSession(runtime, nativeLibraryDir, cacheDir, appendLine)
    }

    DisposableEffect(session) {
        try {
            session.start()
            status = "Connected · Ubuntu / PRoot · line mode"
        } catch (failure: Throwable) {
            status = "Unable to start: ${failure.message}"
            appendLine(status)
        }
        onDispose { session.stop() }
    }

    LaunchedEffect(history.toList()) {
        historyPrefs.edit()
            .putString(TERMINAL_HISTORY_KEY, history.takeLast(TERMINAL_HISTORY_LIMIT).joinToString("\n"))
            .apply()
    }
    LaunchedEffect(fontSize) {
        historyPrefs.edit().putFloat(TERMINAL_FONT_SIZE_KEY, fontSize).apply()
    }
    LaunchedEffect(lightTheme) {
        historyPrefs.edit().putBoolean(TERMINAL_LIGHT_THEME_KEY, lightTheme).apply()
    }
    LaunchedEffect(lines.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .imePadding()
            .navigationBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Linux Terminal", color = TerminalGreen)
            OutlinedButton(onClick = onBack) { Text("Dashboard") }
        }
        Text(status, color = TerminalGreen)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                val text = lines.joinToString("\n")
                if (text.isNotEmpty()) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Terminal output", text))
                }
            }) { Text("Copy all") }
            OutlinedButton(onClick = {
                val pasted = clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                if (!pasted.isNullOrEmpty()) {
                    input = TextFieldValue(input.text + pasted)
                }
            }) { Text("Paste") }
            OutlinedButton(onClick = { lines.clear() }) { Text("Clear screen") }
            OutlinedButton(onClick = { lightTheme = !lightTheme }) {
                Text(if (lightTheme) "Dark" else "Light")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(10f) }) { Text("A−") }
            Text("Font ${fontSize.toInt()}sp", color = if (lightTheme) Color.DarkGray else TerminalText)
            OutlinedButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(24f) }) { Text("A+") }
        }
        SelectionContainer {
            Column(
                Modifier.weight(1f).fillMaxWidth().background(outputBackground)
                    .verticalScroll(scroll).padding(8.dp),
            ) {
                lines.forEach { line ->
                    val lineColor = if (line.startsWith("$ ")) commandText else outputText
                    Text(
                        line,
                        color = lineColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(
                    color = commandText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp,
                ),
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Ubuntu command") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val command = input.text
                    if (command.isNotBlank()) {
                        appendLine("$ $command")
                        runCatching { session.send(command) }
                            .onFailure { appendLine("[send error] ${it.message}") }
                        input = TextFieldValue("")
                    }
                    keyboard?.hide()
                }),
            )
            Button(onClick = {
                val command = input.text
                if (command.isNotBlank()) {
                    appendLine("$ $command")
                    runCatching { session.send(command) }
                        .onFailure { appendLine("[send error] ${it.message}") }
                    input = TextFieldValue("")
                }
            }) { Text("Run") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                session.stop()
                status = "Stopped"
                appendLine("[terminal stopped]")
            }) { Text("Stop shell") }
        }
    }
}

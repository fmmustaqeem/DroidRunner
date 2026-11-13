package io.github.m96chan.droidrunner

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.m96chan.droidrunner.device.DeviceCapabilities
import io.github.m96chan.droidrunner.monitor.SystemMonitor
import io.github.m96chan.droidrunner.runner.RunnerService
import io.github.m96chan.droidrunner.runner.RunnerState
import io.github.m96chan.droidrunner.runner.RunnerStatus
import io.github.m96chan.droidrunner.runtime.RuntimeInstaller
import io.github.m96chan.droidrunner.security.SecretStore
import io.github.m96chan.droidrunner.ui.DashboardScreen
import io.github.m96chan.droidrunner.ui.FloatingOverlayService
import io.github.m96chan.droidrunner.ui.SetupScreen
import io.github.m96chan.droidrunner.ui.TerminalScreen
import io.github.m96chan.droidrunner.ui.theme.BtopTheme
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RunnerStatus.attach(this)
        setContent {
            BtopTheme {
                val capabilities = remember { DeviceCapabilities.detect() }
                val runtime = remember { RuntimeInstaller(this) }
                val secretStore = remember { SecretStore(this) }
                val monitor = remember { SystemMonitor(this) }
                val configuredAtLaunch = remember { File(runtime.runtimeDir, ".configured").isFile }
                // First run drops the user straight into setup.
                var showSetup by remember { mutableStateOf(!configuredAtLaunch) }
                var showTerminal by remember { mutableStateOf(false) }
                val notificationPermission =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                val runner by RunnerStatus.snapshot.collectAsState()

                fun startRunner() {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    startForegroundService(Intent(this, RunnerService::class.java))
                }

                // A configured device is a runner: bring it up on launch.
                LaunchedEffect(Unit) {
                    if (configuredAtLaunch && runtime.installed &&
                        RunnerStatus.snapshot.value.state == RunnerState.STOPPED
                    ) {
                        startRunner()
                    }
                }

                BackHandler(enabled = showSetup || showTerminal) {
                    if (showTerminal) showTerminal = false else showSetup = false
                }

                if (showSetup) {
                    SetupScreen(
                        capabilities = capabilities,
                        runtime = runtime,
                        secretStore = secretStore,
                        onClose = { showSetup = false },
                        // Repairing a runtime from setup leaves a device that
                        // is a runner again; the launch-time autostart above
                        // has already run, so bring it up here (issue #46).
                        onStartRunner = { startRunner() },
                    )
                } else if (showTerminal) {
                    TerminalScreen(
                        runtime = runtime,
                        nativeLibraryDir = applicationInfo.nativeLibraryDir,
                        cacheDir = cacheDir,
                        onBack = { showTerminal = false },
                    )
                } else {
                    DashboardScreen(
                        capabilities = capabilities,
                        runtime = runtime,
                        monitor = monitor,
                        onOpenSetup = { showSetup = true },
                        onOpenTerminal = { showTerminal = true },
                        onToggleFloating = { toggleFloatingWindow() },
                        onStartRunner = { startRunner() },
                        onStopRunner = {
                            startService(Intent(this, RunnerService::class.java).setAction(RunnerService.ACTION_STOP))
                        },
                    )
                }
            }
        }
    }

    private fun toggleFloatingWindow() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }
        if (FloatingOverlayService.isShowing) {
            stopService(Intent(this, FloatingOverlayService::class.java))
        } else {
            startService(Intent(this, FloatingOverlayService::class.java))
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Settings.canDrawOverlays(this) &&
            RunnerStatus.snapshot.value.state != RunnerState.STOPPED
        ) {
            runCatching { startService(Intent(this, FloatingOverlayService::class.java)) }
        }
    }
}

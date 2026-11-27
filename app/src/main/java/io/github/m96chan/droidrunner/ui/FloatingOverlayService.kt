package io.github.m96chan.droidrunner.ui

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import io.github.m96chan.droidrunner.runner.RunnerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Independent, informational overlay. The Runner remains owned by RunnerService. */
class FloatingOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var label: TextView
    private lateinit var params: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null
    private var attached = false

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        label = TextView(this).apply {
            setTextColor(0xFFE8EDF7.toInt())
            textSize = 12f
            setPadding(18, 14, 18, 14)
            background = GradientDrawable().apply {
                setColor(0xEE11151F.toInt())
                cornerRadius = 18f
                setStroke(2, 0xFF52D0DB.toInt())
            }
            text = "DroidRunner\nStarting…"
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
        }
        label.setOnTouchListener(DragListener())
        runCatching {
            windowManager.addView(label, params)
            attached = true
            isShowing = true
        }.onFailure { stopSelf() }
        collector = scope.launch {
            RunnerStatus.snapshot.collect { runner ->
                val job = runner.currentJob?.let { "\n$it" }.orEmpty()
                label.text = "● ${runner.state.label()}$job\nok:${runner.jobsSucceeded}  fail:${runner.jobsFailed}"
            }
        }
    }

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0
        private var downY = 0
        private var startX = 0
        private var startY = 0
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX.toInt()
                    downY = event.rawY.toInt()
                    startX = params.x
                    startY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + event.rawX.toInt() - downX
                    params.y = startY + event.rawY.toInt() - downY
                    if (attached) runCatching { windowManager.updateViewLayout(label, params) }
                    return true
                }
            }
            return event.action == MotionEvent.ACTION_UP
        }
    }

    override fun onDestroy() {
        isShowing = false
        collector?.cancel()
        scope.cancel()
        if (attached) runCatching { windowManager.removeView(label) }
        attached = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile var isShowing: Boolean = false
            private set
    }
}

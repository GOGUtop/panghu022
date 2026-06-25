package com.xixijiuguan.mysillytavernxixi

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Toast
import kotlin.math.abs

class FloatingService : Service() {

    companion object {
        private const val NOTIF_CHANNEL_ID = "float_ball_guard"
        private const val NOTIF_CHANNEL_NAME = "🐕 小狗守护通道"
        private const val NOTIF_ID = 10086

        const val ACTION_TOGGLE_APP = "com.xixijiuguan.action.TOGGLE_APP"
        const val ACTION_PIP = "com.xixijiuguan.action.PIP"
        const val ACTION_WAKE_BALL = "com.xixijiuguan.action.WAKE_BALL"
        const val ACTION_HIDE_BALL = "com.xixijiuguan.action.HIDE_BALL"
        const val ACTION_SHOW_BALL = "com.xixijiuguan.action.SHOW_BALL"
    }

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isMoved = false
    private var touchDownTime = 0L

    private var isAdsorbed = false
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { snapToEdge() }

    private var isManuallyHidden = false

    override fun onBind(intent: Intent?): IBinder? = null

    // 🔥 改成 START_NOT_STICKY：用户杀进程后系统不会自动重启服务
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        when (intent?.action) {
            ACTION_TOGGLE_APP -> { handleClickAction(); return START_NOT_STICKY }
            ACTION_PIP -> { handleLongClickAction(); return START_NOT_STICKY }
            ACTION_WAKE_BALL -> {
                isManuallyHidden = false
                rebuildFloatView()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "🐕 小狗回来啦汪～", Toast.LENGTH_SHORT).show()
                }
                updateNotification()
                return START_NOT_STICKY
            }
            ACTION_HIDE_BALL -> {
                isManuallyHidden = true
                removeFloatView()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "🙈 小狗藏起来了", Toast.LENGTH_SHORT).show()
                }
                updateNotification()
                return START_NOT_STICKY
            }
            ACTION_SHOW_BALL -> {
                isManuallyHidden = false
                rebuildFloatView()
                updateNotification()
                return START_NOT_STICKY
            }
        }

        if (!isManuallyHidden && (floatView == null || floatView?.parent == null)) {
            rebuildFloatView()
        }
        return START_NOT_STICKY  // ✅ 关键修改
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        rebuildFloatView()
        // ❌ 移除 watchdogRunnable，不再每 15s 自检重建
    }

    private fun startAsForeground() {
        try {
            ensureNotificationChannel()
            startForeground(NOTIF_ID, buildControlNotification())
        } catch (_: Exception) {}
    }

    private fun updateNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    try { startForeground(NOTIF_ID, buildControlNotification()) } catch (_: Exception) {}
                    return
                }
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildControlNotification())
        } catch (_: Exception) {}
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIF_CHANNEL_ID, NOTIF_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "酒馆控制面板（备用方案）"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildControlNotification(): Notification {
        val flagImmutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            flagImmutable
        )

        val toggleIntent = Intent(this, FloatingService::class.java).apply { action = ACTION_TOGGLE_APP }
        val togglePi = PendingIntent.getService(this, 101, toggleIntent, flagImmutable)
        val toggleAction = buildAction(android.R.drawable.ic_menu_revert, "🏠 切换", togglePi)

        val pipIntent = Intent(this, FloatingService::class.java).apply { action = ACTION_PIP }
        val pipPi = PendingIntent.getService(this, 102, pipIntent, flagImmutable)
        val pipAction = buildAction(android.R.drawable.ic_menu_crop, "🖼️ 小窗", pipPi)

        val wakeIntent = Intent(this, FloatingService::class.java).apply { action = ACTION_WAKE_BALL }
        val wakePi = PendingIntent.getService(this, 105, wakeIntent, flagImmutable)
        val wakeAction = buildAction(android.R.drawable.ic_popup_sync, "🔄 重新召唤", wakePi)

        val title = if (isManuallyHidden) "🐕 小狗守护中（已隐藏）" else "🐕 小狗在守护酒馆"
        val text = "通知栏控制面板 · 备用方案"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(contentPi)
                .addAction(toggleAction)
                .addAction(pipAction)
                .addAction(wakeAction)
                .setStyle(Notification.BigTextStyle().bigText(
                    "$text\n\n🏠 切换：前台时回桌面 / 后台时拉回酒馆\n🖼️ 小窗：进入画中画悬浮窗口\n🔄 重新召唤：悬浮球消失时一键复活"
                ))
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(contentPi)
                .addAction(toggleAction)
                .addAction(pipAction)
                .addAction(wakeAction)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }

    @Suppress("DEPRECATION")
    private fun buildAction(icon: Int, title: String, pi: PendingIntent): Notification.Action {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, icon), title, pi
            ).build()
        } else {
            Notification.Action.Builder(icon, title, pi).build()
        }
    }

    private fun removeFloatView() {
        try {
            floatView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
            floatView = null
        } catch (_: Exception) {}
    }

    private fun rebuildFloatView() {
        try {
            removeFloatView()
            if (!Settings.canDrawOverlays(this)) return

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100; y = 300
            }

            floatView = LayoutInflater.from(this).inflate(R.layout.layout_float_ball, null)
            try {
                windowManager.addView(floatView, layoutParams)
            } catch (e: Exception) { floatView = null; return }

            floatView?.viewTreeObserver?.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    floatView?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                    snapToEdge()
                }
            })
            setupInteractions()
        } catch (_: Exception) {}
    }

    private fun setupInteractions() {
        floatView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    hideHandler.removeCallbacks(hideRunnable)
                    initialX = layoutParams.x; initialY = layoutParams.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isMoved = false; touchDownTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX; val dy = event.rawY - initialTouchY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isMoved = true; isAdsorbed = false
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        try { windowManager.updateViewLayout(floatView, layoutParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoved) snapToEdge()
                    else {
                        if (isAdsorbed) showFully()
                        else {
                            val dur = System.currentTimeMillis() - touchDownTime
                            if (dur > 400) { handleLongClickAction(); snapToEdge() }
                            else { handleClickAction(); snapToEdge() }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val view = floatView ?: return
        val sw = resources.displayMetrics.widthPixels
        val fw = view.width; if (fw == 0) return
        val hide = (fw * 0.6f).toInt()
        val target = if (layoutParams.x + fw / 2 < sw / 2) -hide else sw - fw + hide
        ValueAnimator.ofInt(layoutParams.x, target).apply {
            duration = 300
            addUpdateListener {
                layoutParams.x = it.animatedValue as Int
                try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
            }
            start()
        }
        isAdsorbed = true
    }

    private fun showFully() {
        val view = floatView ?: return
        val sw = resources.displayMetrics.widthPixels
        val fw = view.width
        val target = if (layoutParams.x < sw / 2) 0 else sw - fw
        ValueAnimator.ofInt(layoutParams.x, target).apply {
            duration = 300
            addUpdateListener {
                layoutParams.x = it.animatedValue as Int
                try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
            }
            start()
        }
        isAdsorbed = false
        hideHandler.postDelayed(hideRunnable, 3000)
    }

    private fun handleClickAction() {
        floatView?.let {
            ObjectAnimator.ofFloat(it, View.ROTATION, 0f, 360f).apply {
                duration = 600; interpolator = LinearInterpolator(); start()
            }
        }
        if (MainActivity.isAppInPip) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        } else if (MainActivity.isAppInForeground) {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK; addCategory(Intent.CATEGORY_HOME)
            })
        } else {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
        }
    }

    private fun handleLongClickAction() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("ENTER_PIP_MODE", true)
        })
    }

    // ❌ 移除 onTaskRemoved 里的 AlarmManager 自重启逻辑
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务划掉时，老老实实停止服务
        stopSelf()
    }

    // ❌ 移除 onDestroy 里的 AlarmManager 自重启
    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacksAndMessages(null)
        removeFloatView()
    }
}
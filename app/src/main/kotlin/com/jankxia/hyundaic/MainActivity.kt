package com.jankxia.hyundaic

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jankxia.hyundaic.audio.MicPermission
import com.jankxia.hyundaic.connection.CarHome
import com.jankxia.hyundaic.connection.ConnectionController
import com.jankxia.hyundaic.connection.DisplayArea
import com.jankxia.hyundaic.connection.Notice
import com.jankxia.hyundaic.platform.log.Log
import com.jankxia.hyundaic.platform.net.VpnConsent
import com.jankxia.hyundaic.settings.AppSettings
import com.jankxia.hyundaic.settings.AudioPreferences
import com.jankxia.hyundaic.startup.BootStart
import com.jankxia.hyundaic.startup.BootStartPolicy
import com.jankxia.hyundaic.wireless.WirelessController

/**
 * 唯一的界面（传统 View 骨架，安卓 4.4 适配版）：首页有线状态 + 开始/停止/重试；设置页、全屏投屏页、
 * 无线界面待后续阶段补回。连接本身在 [ConnectionController] 和前台服务里，界面关掉不影响。
 *
 * 插线拉起、开机自动打开、开机静默连接等业务调度沿用原逻辑；开始前的授权按 通知 → 录音 → VPN 顺序，
 * 框架 [Activity] 权限回调与 [startActivityForResult] 实现。
 */
class MainActivity : Activity() {

    private enum class StartKind { START, USB_ATTACHED, BOOT }

    private lateinit var settings: AppSettings
    private var pendingStart: StartKind? = null

    private val controller: ConnectionController get() = (application as HyundaiHcApp).connection
    private val wireless: WirelessController get() = (application as HyundaiHcApp).wireless

    private lateinit var statusView: TextView
    private lateinit var noticeView: TextView
    private lateinit var pinView: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var retryBtn: Button
    private lateinit var grantVpnBtn: Button
    private lateinit var resetTrustBtn: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            renderState()
            mainHandler.postDelayed(this, POLL_MS)
        }
    }

    private val hostListener = ConnectionController.HostListener { mainHandler.post { renderState() } }

    override fun onResume() {
        super.onResume()
        CarHome.attach(this)
        mainHandler.post(poll)
        // 记下底部导航栏的实际高度（不管此刻显不显示），「全屏显示」关时按它让出画面、算声明尺寸。
        window.decorView.post {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView) ?: return@post
            val bar = insets.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            if (bar > 0) DisplayArea.measuredNavigationBarHeight = bar
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(poll)
        CarHome.detach(this)
        super.onPause()
    }

    /**
     * 界面没处理的方向盘、媒体键交给 [com.jankxia.hyundaic.input.MediaKeys]：有能发键的连接时转给手机并吃掉，
     * 系统就不再把它派给别的媒体应用；没有连接时照常交给系统。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        com.jankxia.hyundaic.input.MediaKeys.onWindowKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        com.jankxia.hyundaic.input.MediaKeys.onWindowKey(event) || super.onKeyUp(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        buildLayout()
        controller.addHostListener(hostListener)
        if (savedInstanceState == null) handleLaunch(intent)
    }

    override fun onStart() {
        super.onStart()
        wireless.onAppForeground()
    }

    override fun onDestroy() {
        controller.removeHostListener(hostListener)
        mainHandler.removeCallbacks(poll)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    // ---------------------------------------------------------------- 界面骨架

    private fun buildLayout() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
        }
        statusView = TextView(this).apply { textSize = 16f; setPadding(0, pad, 0, 0) }
        noticeView = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, 0) }
        pinView = TextView(this).apply { textSize = 18f; setPadding(0, pad, 0, 0) }

        startBtn = Button(this).apply { text = getString(R.string.action_start); setOnClickListener { requestStart(StartKind.START) } }
        stopBtn = Button(this).apply { text = getString(R.string.action_stop); setOnClickListener { controller.stop() } }
        retryBtn = Button(this).apply { text = getString(R.string.action_retry_now); setOnClickListener { controller.retryNow() } }
        grantVpnBtn = Button(this).apply { text = getString(R.string.action_grant_vpn); setOnClickListener { requestStart(StartKind.START) } }
        resetTrustBtn = Button(this).apply { text = getString(R.string.action_reset_trust); setOnClickListener { controller.resetTrustStoreAndStart() } }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad, 0, 0)
            addView(startBtn)
            addView(stopBtn)
            addView(retryBtn)
            addView(grantVpnBtn)
            addView(resetTrustBtn)
        }

        root.addView(title)
        root.addView(statusView)
        root.addView(noticeView)
        root.addView(pinView)
        root.addView(buttons)
        setContentView(root)
    }

    /** 按当前连接状态刷新骨架上的文本与按钮；完整三屏与无线界面待后续阶段补回。 */
    private fun renderState() {
        val s = controller.state.value
        statusView.text = "阶段：${s.phase}　步骤：${s.step ?: "—"}"
        noticeView.text = s.notice?.toString() ?: ""
        pinView.text = if (s.pinCode != null) "连接码：${s.pinCode}" else ""

        val identityLoaded = IdentityLoader.load(this) is IdentityLoader.Result.Loaded
        val idle = s.phase == com.jankxia.hyundaic.connection.Phase.IDLE
        startBtn.isEnabled = idle && identityLoaded
        stopBtn.isEnabled = s.phase != com.jankxia.hyundaic.connection.Phase.STOPPING
        retryBtn.visibility = if (s.phase == com.jankxia.hyundaic.connection.Phase.REPLUG_REQUIRED) View.VISIBLE else View.GONE
        grantVpnBtn.visibility = if (idle && s.notice is Notice.VpnConsentRequired) View.VISIBLE else View.GONE
        resetTrustBtn.visibility = if (idle && s.notice is Notice.TrustStoreCorrupt) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- 启动流程

    private fun handleLaunch(intent: Intent?) {
        if (BootStart.isBootLaunch(intent)) {
            if (BootStart.waitForCable(this)) {
                Log.i(TAG, "开机自动打开：开始等插线")
                requestStart(StartKind.BOOT)
            } else {
                Log.i(TAG, "开机自动打开：不等插线（插线时由系统拉起本应用再开始有线），无线自动连手机")
            }
        } else if (intent?.action == BootStartPolicy.ACTION_SHOW_PROJECTION) {
            Log.i(TAG, "开机静默连接：手机连上了，界面带到前台")
        } else {
            handleUsbAttach(intent)
        }
    }

    private fun handleUsbAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        // 任务最初是插线拉起的话，从「最近任务」重开时系统会把那次的 Intent 再给一遍，那不是插线。
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // 被「设备接入」拉起，说明用户在系统框里同意过用本应用打开手机：之后有线会话先等系统自动授权。
        settings.usbAttachAccepted = true
        Log.i(TAG, "插线拉起")
        requestStart(StartKind.USB_ATTACHED)
    }

    private fun requestStart(kind: StartKind) {
        if (wireless.blocksWired) {
            // 两种连接同时只能有一种：插线拉起时只记一笔，用户点开始时给出原因。
            Log.i(TAG, "无线连接正在进行，不开始有线连接（$kind）")
            if (kind == StartKind.START) controller.reportBlocked(Notice.StartFailed(getString(R.string.wireless_notice_wireless_active)))
            return
        }
        pendingStart = kind
        askNextPermission()
    }

    /** 开始前的运行时权限按顺序问，每样只自动问一次；问完（同意与否）接着 [continueStart]。 */
    private fun askNextPermission() {
        if (pendingStart == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settings.notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            settings.notificationPermissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
            return
        }
        val audio = AudioPreferences(this)
        if (audio.micEnabled && !audio.micPermissionAsked && !MicPermission.granted(this)) {
            audio.micPermissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        continueStart()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_NOTIFICATION -> askNextPermission()
            REQ_MIC -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    Log.i(TAG, "用户没有给录音权限：这次不声明车机麦克风")
                    Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
                }
                askNextPermission()
            }
        }
    }

    private fun continueStart() {
        val kind = pendingStart ?: return
        when (val consent = VpnConsent.check(this)) {
            VpnConsent.Status.Granted -> {
                pendingStart = null
                dispatch(kind)
            }
            is VpnConsent.Status.NeedsUserConsent -> try {
                startActivityForResult(consent.intent, REQ_VPN)
            } catch (e: ActivityNotFoundException) {
                pendingStart = null
                controller.reportBlocked(Notice.VpnUnavailable(getString(R.string.vpn_dialog_missing)))
            }
            is VpnConsent.Status.Unavailable -> {
                pendingStart = null
                controller.reportBlocked(Notice.VpnUnavailable(consent.reason))
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VPN) return
        val kind = pendingStart
        pendingStart = null
        if (kind == null) return
        if (resultCode == RESULT_OK && VpnConsent.isGranted(this)) {
            dispatch(kind)
        } else {
            Log.i(TAG, "用户没有同意 VPN 授权")
            controller.reportBlocked(Notice.VpnConsentRequired)
        }
    }

    private fun dispatch(kind: StartKind) {
        // 无线在被动等已配对的手机回连（已不再自动进入）的话先让出。
        wireless.yieldToWired()
        when (kind) {
            StartKind.START, StartKind.BOOT -> controller.start()
            StartKind.USB_ATTACHED -> controller.onUsbAttached()
        }
    }

    private companion object {
        const val TAG = "HyundaiHC-App"
        const val REQ_NOTIFICATION = 1
        const val REQ_MIC = 2
        const val REQ_VPN = 3
        const val POLL_MS = 500L
    }
}

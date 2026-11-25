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
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.jankxia.hyundaic.platform.wifi.WifiConnector
import com.jankxia.hyundaic.settings.AppSettings
import com.jankxia.hyundaic.settings.AudioPreferences
import com.jankxia.hyundaic.settings.WirelessPreferences
import com.jankxia.hyundaic.ui.QrCodes
import com.jankxia.hyundaic.startup.BootStart
import com.jankxia.hyundaic.startup.BootStartPolicy
import com.jankxia.hyundaic.wireless.WirelessController
import com.jankxia.hyundaic.wireless.WirelessPermissions

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
    private lateinit var hotspotNameInput: EditText
    private lateinit var hotspotPassInput: EditText
    private lateinit var hotspotEnable: CheckBox
    private lateinit var hotspotFiveGhz: CheckBox
    private lateinit var hotspotStatusView: TextView
    private lateinit var wirelessStatusView: TextView
    private lateinit var qrView: ImageView
    private var lastQrContent: String? = null
    private lateinit var startWirelessBtn: Button
    private lateinit var stopWirelessBtn: Button
    /** 授权完接着做的事（开始无线连接 / 让车机连上热点），权限回调里执行。 */
    private var pendingWirelessAction: (() -> Unit)? = null

    /** 只有需要时才显示的那一排按钮（重试 / 授权 VPN / 重置信任库）。 */
    private lateinit var extraButtonsRow: LinearLayout

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
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
        })

        statusView = TextView(this).apply { textSize = 16f; setPadding(0, dp(6), 0, 0) }
        // 空文本的提示行不占位置：原来给它们留固定内边距，界面上会凭空多出一段空白。
        noticeView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        pinView = TextView(this).apply {
            textSize = 18f
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        root.addView(statusView)
        root.addView(noticeView)
        root.addView(pinView)

        startBtn = actionButton(getString(R.string.action_start)) { requestStart(StartKind.START) }
        stopBtn = actionButton(getString(R.string.action_stop)) { controller.stop() }
        retryBtn = actionButton(getString(R.string.action_retry_now)) { controller.retryNow() }
        grantVpnBtn = actionButton(getString(R.string.action_grant_vpn)) { requestStart(StartKind.START) }
        resetTrustBtn = actionButton(getString(R.string.action_reset_trust)) { controller.resetTrustStoreAndStart() }
        retryBtn.visibility = View.GONE
        grantVpnBtn.visibility = View.GONE
        resetTrustBtn.visibility = View.GONE

        // 一行按钮按个数等分宽度：横屏下一行塞五个按钮，后面的会被压成两个字宽。
        root.addView(buttonRow(startBtn, stopBtn))
        extraButtonsRow = buttonRow(retryBtn, grantVpnBtn, resetTrustBtn).apply { visibility = View.GONE }
        root.addView(extraButtonsRow)

        root.addView(buildWirelessSection())
        root.addView(buildHotspotConfig())

        // 车机横屏高度只有 720px，热点配置区排在下面很容易出界；用 ScrollView 兜住，出界也能滚到。
        setContentView(ScrollView(this).apply {
            isFillViewport = false
            addView(root)
        })
        loadHotspotConfig()
    }

    /** 最小无线入口：开始/停止 + 状态行 + 二维码。 */
    private fun buildWirelessSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(sectionTitle("无线连接"))

        wirelessStatusView = TextView(this).apply { textSize = 14f; setPadding(0, dp(6), 0, 0) }
        qrView = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        startWirelessBtn = actionButton("开始无线连接") { ensureWirelessPermissions { wireless.start() } }
        stopWirelessBtn = actionButton("停止无线") { wireless.stop() }

        box.addView(wirelessStatusView)
        box.addView(
            qrView,
            LinearLayout.LayoutParams(dp(240), dp(240)).apply {
                topMargin = dp(12)
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        box.addView(buttonRow(startWirelessBtn, stopWirelessBtn))
        return box
    }

    /**
     * 手机热点配置：这台车机没有开热点的能力，只是 WiFi 客户端，所以由手机开个人热点、车机连它。
     * 车主把手机热点的名称和密码填这里，「让车机连上」直接替车主连；勾上启用后，开始无线连接时
     * 就用车机当前连着的这个热点（见 [com.jankxia.hyundaic.platform.wifi.CarHotspot]）。
     */
    private fun buildHotspotConfig(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(sectionTitle("手机热点（车机连它）"))
        box.addView(TextView(this).apply {
            text = "车机不开热点，只连手机：手机开个人热点（建议 2.4 GHz），把名称和密码填这里，点「让车机连上」"
            textSize = 13f
            setPadding(0, dp(6), 0, dp(4))
        })
        hotspotNameInput = EditText(this).apply {
            hint = "手机热点名称（SSID）"
            textSize = 14f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            inputField(this)
        }
        hotspotPassInput = EditText(this).apply {
            hint = "手机热点密码"
            textSize = 14f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            inputField(this)
        }
        hotspotFiveGhz = CheckBox(this).apply { text = "手机热点用 5 GHz"; textSize = 14f; minHeight = dp(40) }
        hotspotEnable = CheckBox(this).apply {
            text = "启用（开始无线连接前，车机连这个热点）"
            textSize = 14f
            minHeight = dp(40)
        }

        // 车机横屏高度有限：两样配置并排，能把「启用 + 保存」一起留在首屏。
        box.addView(
            fieldRow(this, hotspotNameInput, hotspotPassInput),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) },
        )
        box.addView(fieldRow(this, hotspotFiveGhz, hotspotEnable))
        hotspotStatusView = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        box.addView(
            buttonRow(
                actionButton("保存热点配置") { saveHotspotConfig() },
                actionButton("让车机连上") { connectHotspotNow() },
            ),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) },
        )
        box.addView(hotspotStatusView)
        return box
    }

    /** 输入框在深色主题下只有一条下划线，车机上不好看出能点：加一层浅底色和内部留白。 */
    private fun inputField(view: EditText): EditText = view.apply {
        setBackgroundColor(0x22FFFFFF)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    /** 一行并排放若干个控件，宽度按个数等分。 */
    private fun fieldRow(context: android.content.Context, vararg views: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { index, view ->
                addView(
                    view,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = dp(10)
                    },
                )
            }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 15f
        minHeight = dp(48)
        setOnClickListener { onClick() }
    }

    /** 一行按钮，宽度按个数等分。 */
    private fun buttonRow(vararg buttons: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, 0)
        buttons.forEachIndexed { index, view ->
            addView(
                view,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(10)
                },
            )
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, dp(16), 0, 0)
    }

    private fun loadHotspotConfig() {
        val p = WirelessPreferences(this)
        hotspotNameInput.setText(p.carHotspotName)
        hotspotPassInput.setText(p.carHotspotPassword)
        hotspotFiveGhz.isChecked = p.carHotspotFiveGhz
        hotspotEnable.isChecked = p.carHotspot
    }

    private fun saveHotspotConfig() {
        val p = WirelessPreferences(this)
        val name = hotspotNameInput.text.toString().trim()
        val pass = hotspotPassInput.text.toString().trim()
        if (hotspotEnable.isChecked && (name.isEmpty() || pass.isEmpty())) {
            Toast.makeText(this, "勾了启用就要填手机热点名称和密码", Toast.LENGTH_SHORT).show()
            return
        }
        p.carHotspotName = name
        p.carHotspotPassword = pass
        p.carHotspotFiveGhz = hotspotFiveGhz.isChecked
        p.carHotspot = hotspotEnable.isChecked
        // 几种「给手机准备的 WiFi 网络」二选一：选了车机连手机热点就关掉 WiFi 直连。
        if (p.carHotspot) p.wifiDirect = false
        Toast.makeText(this, "已保存，点「让车机连上」现在就连", Toast.LENGTH_SHORT).show()
    }

    /**
     * 让车机连上填的手机热点（车机是 WiFi 客户端，见 [WifiConnector]）。先确认权限，再在后台线程连，
     * 结果写回状态行；连之前先把名称密码存下来，「开始无线连接」用的就是这一份。
     */
    private fun connectHotspotNow() {
        val name = hotspotNameInput.text.toString().trim()
        val pass = hotspotPassInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "先填手机热点名称", Toast.LENGTH_SHORT).show()
            return
        }
        ensureWirelessPermissions { connectHotspot(name, pass) }
    }

    private fun connectHotspot(name: String, pass: String) {
        WirelessPreferences(this).apply {
            carHotspotName = name
            carHotspotPassword = pass
            carHotspotFiveGhz = hotspotFiveGhz.isChecked
        }
        hotspotStatusView.visibility = View.VISIBLE
        hotspotStatusView.text = "正在连「$name」…"
        val appContext = applicationContext
        Thread({
            val result = WifiConnector(appContext).connect(name, pass)
            // 连上了就顺手把这个热点设为默认：不然后面点「开始无线连接」会落回「车机自己开热点」那条路。
            val connected = result is WifiConnector.Result.Connected
            if (connected) {
                WirelessPreferences(appContext).apply {
                    carHotspotName = name
                    carHotspotPassword = pass
                    carHotspotFiveGhz = hotspotFiveGhz.isChecked
                    carHotspot = true
                    wifiDirect = false
                }
            }
            mainHandler.post {
                if (connected) hotspotEnable.isChecked = true
                hotspotStatusView.text = when (result) {
                    is WifiConnector.Result.Connected -> "已连上「${result.ssid}」，车机地址 ${result.ipAddress}；已设为默认"
                    is WifiConnector.Result.Failed -> "没连上：${result.reason}"
                }
            }
        }, "HyundaiHC-WifiConnect").apply { isDaemon = true }.start()
    }

    /** 无线连接要的运行时权限（安卓 6 起改 WiFi、读热点列表要位置）。缺就当场问，问完接着做 [next]。 */
    private fun ensureWirelessPermissions(next: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            next()
            return
        }
        if (wirelessPermissionMissing().isEmpty()) {
            next()
            return
        }
        pendingWirelessAction = next
        requestPermissions(
            WirelessPermissions.toRequest(Build.VERSION.SDK_INT, askNotifications = false).toTypedArray(),
            REQ_WIRELESS,
        )
    }

    private fun wirelessPermissionMissing(): List<String> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            emptyList()
        } else {
            WirelessPermissions.missing(Build.VERSION.SDK_INT) {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        }

    /** 按当前连接状态刷新骨架上的文本与按钮；完整三屏与无线界面待后续阶段补回。 */
    private fun renderState() {
        val s = controller.state.value
        statusView.text = "阶段：${s.phase}　步骤：${s.step ?: "—"}"
        val notice = s.notice?.toString()
        noticeView.text = notice ?: ""
        noticeView.visibility = if (notice == null) View.GONE else View.VISIBLE
        val pin = s.pinCode
        pinView.text = if (pin != null) "连接码：$pin" else ""
        pinView.visibility = if (pin == null) View.GONE else View.VISIBLE

        val identityLoaded = IdentityLoader.load(this) is IdentityLoader.Result.Loaded
        val idle = s.phase == com.jankxia.hyundaic.connection.Phase.IDLE
        startBtn.isEnabled = idle && identityLoaded
        stopBtn.isEnabled = s.phase != com.jankxia.hyundaic.connection.Phase.STOPPING
        retryBtn.visibility = if (s.phase == com.jankxia.hyundaic.connection.Phase.REPLUG_REQUIRED) View.VISIBLE else View.GONE
        grantVpnBtn.visibility = if (idle && s.notice is Notice.VpnConsentRequired) View.VISIBLE else View.GONE
        resetTrustBtn.visibility = if (idle && s.notice is Notice.TrustStoreCorrupt) View.VISIBLE else View.GONE
        extraButtonsRow.visibility = if (
            retryBtn.visibility == View.VISIBLE || grantVpnBtn.visibility == View.VISIBLE ||
            resetTrustBtn.visibility == View.VISIBLE
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }

        val w = wireless.state.value
        wirelessStatusView.text = buildString {
            append("无线阶段：").append(w.phase)
            w.step?.let { append("　步骤：").append(it) }
            if (w.attempt > 0) append("　第 ").append(w.attempt).append(" 次")
            w.notice?.let { append("\n原因：").append(it) }
            w.pinCode?.let { append("\n连接码：").append(it) }
            // 二维码内容是一长串 URI，只在二维码图里体现；写进文本会把版面撑乱，要看细节去日志。
        }
        startWirelessBtn.isEnabled = !w.active && idle
        stopWirelessBtn.isEnabled = w.active

        // 二维码只在内容变化时重编（轮询每 500ms 跑一次，重编一张图没必要）。
        if (w.qrCode != lastQrContent) {
            lastQrContent = w.qrCode
            val qr = w.qrCode
            if (qr == null) {
                qrView.visibility = View.GONE
                qrView.setImageDrawable(null)
            } else {
                val px = (240 * resources.displayMetrics.density).toInt()
                qrView.setImageBitmap(QrCodes.bitmap(qr, px))
                qrView.visibility = View.VISIBLE
            }
        }
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
        // API 23 以下没有运行时权限机制：安装即授予，框架也没有 requestPermissions 方法，直接走流程。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            continueStart()
            return
        }
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
            REQ_WIRELESS -> {
                val action = pendingWirelessAction
                pendingWirelessAction = null
                val still = wirelessPermissionMissing()
                if (still.isEmpty()) {
                    action?.invoke()
                } else {
                    Log.i(TAG, "无线权限还缺 ${still.joinToString()}：这次做不了，等车主去系统设置里开")
                    Toast.makeText(
                        this,
                        "还缺${if (still.contains(WirelessPermissions.FINE_LOCATION)) "「位置」" else ""}权限：请到系统设置 → 应用 → HyundaiHC → 权限 里打开",
                        Toast.LENGTH_LONG,
                    ).show()
                }
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
        const val REQ_WIRELESS = 4
        const val POLL_MS = 500L
    }
}

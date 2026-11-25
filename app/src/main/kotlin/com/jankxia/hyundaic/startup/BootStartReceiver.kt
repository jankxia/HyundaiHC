package com.jankxia.hyundaic.startup

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.jankxia.hyundaic.MainActivity
import com.jankxia.hyundaic.HyundaiHcApp
import com.jankxia.hyundaic.platform.log.Log

/**
 * 开机广播接收器（清单里默认停用，设置「开机自动打开 HyundaiHC」打开时才启用）。冷启动和熄火后再点火都走这里。
 *
 * 默认只打开界面，不起服务，连接要的前台服务由界面打开后发起（[BootLaunchWatch]）。
 * 设置「开机静默连接」开着时不打开界面，在后台起前台服务等待（[SilentBootWatch]），后台开始不了再改走打开界面。
 *
 * 车机上加了「延迟打开」（[BootDelayPolicy]）：收到开机广播不马上打开，先排一个闹钟，到点由 [BootDelayReceiver] 接着做，
 * 免得抢在系统前面、被桌面盖住或打不开。
 */
class BootStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BootStartPolicy.isBootBroadcast(intent.action)) return
        val app = context.applicationContext
        val seconds = BootStart.launchDelaySeconds(app)
        // 排闹钟是瞬时的，排上就不占着这条广播了；排不上（极少）当场打开，别让车主干等。
        if (BootStart.scheduleDelayedLaunch(app, seconds)) return
        startBootAction(app, goAsync())
    }
}

/** 延迟到点：把开机那一步接着做完。闹钟由 [BootStart.scheduleDelayedLaunch] 排，应用没在运行时系统会把进程拉起来。 */
class BootDelayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BootDelayPolicy.ACTION_DELAYED_LAUNCH) return
        Log.i(BootStart.TAG, "开机自启：延迟到点，打开界面（系统已运行 ${SystemClock.elapsedRealtime() / 1000} 秒）")
        startBootAction(context.applicationContext, goAsync())
    }
}

/** 收到开机广播（或延迟到点）之后的共同动作：静默连接开着走后台等待，否则打开界面。 */
internal fun startBootAction(app: Context, pending: BroadcastReceiver.PendingResult?) {
    if (app is HyundaiHcApp && BootStart.isSilent(app)) {
        SilentBootWatch(app, pending).start()
    } else {
        BootLaunchWatch(app, pending).start()
    }
}

/**
 * 打开界面并看它在不在前台：[BootStartPolicy.CHECK_DELAY_MS] 后不在（没出来，或者出来后被盖住了）就再开一次，
 * 再过同样久按那时的情况记下结果。全在主线程上。
 *
 * 开机广播是逐个接收器排队送的，占着它后面的应用都得等：界面一到前台（进程已在前台，不会被收掉）就先放手，
 * 看门狗接着靠主线程的定时器跑完；界面一直没出来才占满两轮，让进程活到补开那一下。
 */
internal class BootLaunchWatch(
    private val app: Context,
    private val pending: BroadcastReceiver.PendingResult?,
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private val application = app as? Application
    private val atMillis = System.currentTimeMillis()
    private val sinceBootMillis = SystemClock.elapsedRealtime()
    private val installStamp = BootStart.installedAtMillis(app)
    private var inFront = false
    private var everShown = false
    private var retried = false
    private var released = false
    private var done = false

    fun start() {
        val kind = if (BootStartPolicy.isFreshBoot(sinceBootMillis)) "系统刚启动" else "系统已运行 ${sinceBootMillis / 60_000} 分钟"
        val overlay = BootStart.canDrawOverlays(app)
        Log.i(BootStart.TAG, "收到开机广播（$kind），打开界面；悬浮窗权限：${if (overlay) "有" else "没有"}，安卓 API ${Build.VERSION.SDK_INT}")
        // 先记一笔：进程之后被收掉的话，设置页至少知道开机广播送到了。
        record(BootOutcome.PENDING)
        application?.registerActivityLifecycleCallbacks(this)
        if (!open()) {
            finish(BootOutcome.FAILED)
            return
        }
        handler.postDelayed(::check, BootStartPolicy.CHECK_DELAY_MS)
    }

    private fun open(): Boolean = try {
        app.startActivity(BootStart.launchIntent(app))
        true
    } catch (e: RuntimeException) {
        Log.e(BootStart.TAG, "打开界面失败", e)
        false
    }

    private fun check() {
        if (done) return
        if (BootStartPolicy.shouldRetry(inFront, retried)) {
            retried = true
            Log.w(BootStart.TAG, "界面不在前台（${if (everShown) "打开过、被盖住了" else "没出来"}），再开一次")
            open()
            handler.postDelayed(::check, BootStartPolicy.CHECK_DELAY_MS)
            return
        }
        finish(BootStartPolicy.outcome(inFront, everShown, Build.VERSION.SDK_INT, BootStart.canDrawOverlays(app)))
    }

    private fun finish(outcome: BootOutcome) {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        application?.unregisterActivityLifecycleCallbacks(this)
        record(outcome)
        Log.i(BootStart.TAG, "开机打开界面的结果：$outcome")
        releaseBroadcast()
    }

    private fun releaseBroadcast() {
        if (released) return
        released = true
        pending?.finish()
    }

    private fun record(outcome: BootOutcome) {
        try {
            BootStart.saveRecord(app, BootRecord(atMillis, sinceBootMillis, outcome, installStamp))
        } catch (e: RuntimeException) {
            Log.w(BootStart.TAG, "记录开机结果失败：${e.message}")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        inFront = true
        everShown = true
        releaseBroadcast()
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is MainActivity) inFront = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}


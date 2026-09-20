package com.voicechanger.app.overlay

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.voicechanger.app.domain.PipelineMetrics
import com.voicechanger.app.domain.PipelineState
import com.voicechanger.app.domain.ProcessorMode
import com.voicechanger.app.service.VoiceChangerService

/**
 * 前台悬浮窗控制器。
 * - 支持 完整面板 / 缩小胶囊两态，点击胶囊恢复面板；
 * - 支持拖动：完整态拖动整窗；胶囊态拖动并记忆位置，下次收起停在上次位置（默认右上角）；
 * - 完整面板为 NOT_TOUCH_MODAL：点击悬浮窗外自动收起为胶囊，事件透传下层应用；
 * - 交互走 PendingIntent 到服务（与服务解耦）；
 * - 所有 WindowManager/View 操作统一主线程。
 */
class OverlayController(private val context: Context) {

    companion object {
        private const val TAG = "VC/Overlay"
        private const val ID_STATUS = 0x0F001
        private const val ID_METRICS = 0x0F002
        private const val ID_BTN_MONITOR = 0x0F003
        private const val ID_BTN_MUTE = 0x0F004
        private const val ID_BTN_NOISE = 0x0F005
        private const val ID_BTN_STOP = 0x0F006
        private const val ID_BTN_MODE = 0x0F007
        private const val ID_BTN_MINIMIZE = 0x0F008
        private const val ID_BTN_EXPAND = 0x0F009
        private const val ID_PILL = 0x0F00A
        private const val ID_PILL_TEXT = 0x0F00B

        private const val MODE_FULL = 0
        private const val MODE_PILL = 1
    }

    private var wm: WindowManager? = null
    private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var mode = MODE_FULL

    // 折叠前的位置（用于恢复）
    private var lastX = 0
    private var lastY = 0

    // 胶囊（缩小态）的记忆位置；-1 表示尚未自定义，使用默认右上角
    private var pillX = -1
    private var pillY = -1

    private val mainHandler = Handler(Looper.getMainLooper())

    fun canShow(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show() = mainHandler.post { showOnMain() }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOnMain() {
        if (root != null) return
        if (!canShow()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted, overlay skipped")
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        this.wm = wm

        // 全量构建两个子视图：fullPanel（完整） / pill（缩小胶囊）
        val fullPanel = buildFullPanel()
        val pill = buildPill()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            background = null
            addView(fullPanel, lpWrap())
            addView(pill, lpWrap())
        }
        fullPanel.visibility = View.VISIBLE
        pill.visibility = View.GONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(160)
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var isDragging = false
        container.setOnTouchListener { v, ev ->
            val w = v.width
            val h = v.height
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y
                    isDragging = false
                    // 完整面板：点击悬浮窗外 → 自动收起（缩小为胶囊）。
                    // 返回 false 不消费事件，下层应用照常收到点击。
                    if (mode == MODE_FULL) {
                        val touchX = ev.rawX - lp.x
                        val touchY = ev.rawY - lp.y
                        if (touchX < 0f || touchY < 0f || touchX >= w || touchY >= h) {
                            minimize()
                            return@setOnTouchListener false
                        }
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == MODE_PILL) {
                        // 胶囊态：拖动整个胶囊并记忆位置
                        lp.x = startX + (ev.rawX - downX).toInt()
                        lp.y = startY + (ev.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                        pillX = lp.x
                        pillY = lp.y
                        true
                    } else {
                        // 完整面板：拖动整窗
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (!isDragging && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) {
                            isDragging = true
                        }
                        if (isDragging) {
                            lp.x = startX + dx.toInt()
                            lp.y = startY + dy.toInt()
                            runCatching { wm.updateViewLayout(v, lp) }
                        }
                        true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (mode == MODE_PILL) {
                        pillX = lp.x
                        pillY = lp.y
                    }
                    false
                }
                else -> false
            }
        }

        // 点击胶囊 → 展开
        pill.setOnClickListener { expand() }

        try {
            wm.addView(container, lp)
            root = container
            this.lp = lp
            mode = MODE_FULL
            lastX = lp.x
            lastY = lp.y
            Log.i(TAG, "overlay shown")
        } catch (t: Throwable) {
            Log.w(TAG, "overlay add failed: ${t.message}")
        }
    }

    /** 缩小：隐藏完整面板，显示胶囊；位置为记忆位置，首次默认右上角。 */
    fun minimize() = mainHandler.post {
        val container = root as? LinearLayout ?: return@post
        val fullPanel = container.getChildAt(0)
        val pill = container.getChildAt(1)
        fullPanel.visibility = View.GONE
        pill.visibility = View.VISIBLE
        mode = MODE_PILL

        // 记录当前坐标，随后缩小为胶囊
        lastX = lp?.x ?: lastX
        lastY = lp?.y ?: lastY
        lp?.let { p ->
            p.gravity = Gravity.TOP or Gravity.START
            // 等胶囊完成布局后按实际宽度定位：
            // 若用户曾拖动过胶囊则停在上次位置，否则默认吸附右上角
            container.post {
                if (pillX < 0 || pillY < 0) {
                    val screenW = context.resources.displayMetrics.widthPixels
                    val pillW = pill.width.takeIf { it > 0 } ?: dp(120)
                    p.x = screenW - pillW - dp(12)
                    p.y = dp(60)
                } else {
                    p.x = pillX
                    p.y = pillY
                }
                runCatching { wm?.updateViewLayout(container, p) }
            }
        }
        Log.i(TAG, "overlay minimized to corner")
    }

    /** 展开：恢复完整面板。 */
    fun expand() = mainHandler.post {
        val r = root ?: return@post
        val container = r as LinearLayout
        val fullPanel = container.getChildAt(0)
        val pill = container.getChildAt(1)
        fullPanel.visibility = View.VISIBLE
        pill.visibility = View.GONE
        mode = MODE_FULL
        // 恢复折叠前位置
        lp?.let { p ->
            p.gravity = Gravity.TOP or Gravity.START
            p.x = lastX
            p.y = lastY
            runCatching { wm?.updateViewLayout(container, p) }
        }
        Log.i(TAG, "overlay expanded")
    }

    fun update(state: PipelineState, mode: ProcessorMode, muted: Boolean,
              monitor: Boolean, noise: Boolean, metrics: PipelineMetrics) {
        mainHandler.post { updateOnMain(state, mode, muted, monitor, noise, metrics) }
    }

    private fun updateOnMain(state: PipelineState, mode: ProcessorMode, muted: Boolean,
                              monitor: Boolean, noise: Boolean, metrics: PipelineMetrics) {
        val r = root ?: return
        r.findViewById<TextView>(ID_STATUS)?.text = buildStatusText(state, mode, muted, monitor, noise)
        r.findViewById<TextView>(ID_METRICS)?.text =
            "cap=${metrics.captureFrames} proc=${metrics.processedFrames} " +
            "under=${metrics.underruns} drop=${metrics.inputDropped}/${metrics.outputDropped} " +
            "rms=${String.format("%.3f", metrics.rms)}"
        r.findViewById<Button>(ID_BTN_MONITOR)?.setActive(monitor)
        r.findViewById<Button>(ID_BTN_MUTE)?.setActive(muted)
        r.findViewById<Button>(ID_BTN_NOISE)?.setActive(noise)
        // 胶囊上的模式文本
        r.findViewById<TextView>(ID_PILL_TEXT)?.text = pillText(state, mode, muted, monitor)
    }

    fun dismiss() = mainHandler.post {
        val r = root ?: return@post
        root = null
        lp = null
        runCatching { wm?.removeView(r) }
        wm = null
        Log.i(TAG, "overlay dismissed")
    }

    /** 供服务读取当前是否显示（避免重复 show）。 */
    fun isShowing(): Boolean = root != null

    // ---------------- builders ----------------

    private fun buildFullPanel(): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(222, 22, 24, 30))
            }
        }
        val statusTv = TextView(context).apply {
            id = ID_STATUS
            setTextColor(Color.WHITE)
            setTextSize(14f)
            setTypeface(Typeface.DEFAULT_BOLD)
            text = "Voice Changer"
        }
        val metricsTv = TextView(context).apply {
            id = ID_METRICS
            setTextColor(Color.argb(190, 255, 255, 255))
            setTextSize(10f)
            text = ""
        }

        val btnRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnRow1.addView(makeButton("返听", ID_BTN_MONITOR) { sendAction(VoiceChangerService.ACTION_TOGGLE_MONITOR) })
        btnRow1.addView(makeButton("静音", ID_BTN_MUTE) { sendAction(VoiceChangerService.ACTION_TOGGLE_MUTE) })
        btnRow1.addView(makeButton("降噪", ID_BTN_NOISE) { sendAction(VoiceChangerService.ACTION_TOGGLE_NOISE) })

        val btnRow2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnRow2.addView(makeButton("模式⇄", ID_BTN_MODE) { sendAction(VoiceChangerService.ACTION_CYCLE_MODE) })
        btnRow2.addView(makeButton("缩小", ID_BTN_MINIMIZE) { minimize() })
        btnRow2.addView(makeButton("停止", ID_BTN_STOP) { sendAction(VoiceChangerService.ACTION_STOP) })

        layout.addView(statusTv)
        layout.addView(metricsTv, lpWrap().apply { topMargin = dp(2) })
        layout.addView(btnRow1, lpWrap().apply { topMargin = dp(8) })
        layout.addView(btnRow2, lpWrap().apply { topMargin = dp(4) })
        return layout
    }

    /** 右上角小胶囊：单行显示“VC 模式·状态”，点击展开。 */
    private fun buildPill(): LinearLayout {
        val pill = LinearLayout(context).apply {
            id = ID_PILL
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(240, 22, 24, 30))
            }
        }
        val dot = TextView(context).apply {
            text = "●"
            setTextColor(0xFF2EBD85.toInt())
            setTextSize(12f)
        }
        val text = TextView(context).apply {
            id = ID_PILL_TEXT
            setTextColor(Color.WHITE)
            setTextSize(13f)
            setTypeface(Typeface.DEFAULT_BOLD)
            text = "VC 变声"
            setPadding(dp(6), 0, 0, 0)
        }
        pill.addView(dot)
        pill.addView(text)
        return pill
    }

    private fun pillText(state: PipelineState, mode: ProcessorMode,
                          muted: Boolean, monitor: Boolean): String {
        val modeL = when (mode) {
            ProcessorMode.PASSTHROUGH -> "直通"
            ProcessorMode.INTERNAL -> "变声"
            ProcessorMode.AI_MEANVC -> "AI声线"
            ProcessorMode.AI_OPENVOICE -> "AI(OV)"
            ProcessorMode.AI_FREEVC -> "AI(FC)"
            ProcessorMode.AI_DDSP -> "AI(DSP)"
            ProcessorMode.LOOPBACK_SOCKET -> "外部"
            ProcessorMode.MUTE -> "静音"
        }
        val flags = buildList {
            if (muted) add("静")
            if (monitor) add("返")
        }
        val running = state is PipelineState.Running
        return "VC $modeL${if (running) "●" else "○"}${if (flags.isEmpty()) "" else "(${flags.joinToString("/")})"}"
    }

    private fun buildStatusText(state: PipelineState, mode: ProcessorMode,
                                 muted: Boolean, monitor: Boolean, noise: Boolean): String {
        val st = when (state) {
            is PipelineState.Running -> "● 运行"
            is PipelineState.Error -> "● 错误 ${state.code}"
            is PipelineState.Recovering -> "● 恢复中"
            PipelineState.Idle -> "○ 停止"
            else -> "○ ${state.javaClass.simpleName}"
        }
        val fl = buildList {
            if (muted) add("静音")
            if (monitor) add("返听")
            if (!noise) add("降噪关")
        }
        val modeL = when (mode) {
            ProcessorMode.PASSTHROUGH -> "直通"
            ProcessorMode.INTERNAL -> "变声"
            ProcessorMode.AI_MEANVC -> "AI声线"
            ProcessorMode.AI_OPENVOICE -> "AI(OV)"
            ProcessorMode.AI_FREEVC -> "AI(FC)"
            ProcessorMode.AI_DDSP -> "AI(DSP)"
            ProcessorMode.LOOPBACK_SOCKET -> "外部"
            ProcessorMode.MUTE -> "静音"
        }
        return "VC $st｜$modeL${if (fl.isEmpty()) "" else " [${fl.joinToString("/")}]"}"
    }

    private fun sendAction(action: String) {
        val pi = PendingIntent.getService(
            context, action.hashCode(),
            Intent(context, VoiceChangerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { pi.send() }
    }

    private fun makeButton(label: String, id: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            this.id = id; text = label; textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(90, 255, 255, 255))
            }
        }

    private fun Button.setActive(active: Boolean) {
        (background as? GradientDrawable)?.setColor(
            if (active) Color.argb(255, 244, 152, 66) else Color.argb(90, 255, 255, 255)
        )
        setTextColor(if (active) Color.BLACK else Color.WHITE)
    }

    private fun lpWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
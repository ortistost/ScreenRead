package com.example.listcounter

import android.content.ClipData
import android.content.ClipboardManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

object Detector {
    const val DARK_LUM = 62     // bundan koyu piksel "kenar" sayılır (0-255 parlaklık)
    const val SCALE = 2         // maske yarı çözünürlükte çıkarılır (hız)
    const val GRAY_F = 4        // kaydırma takibi için gri küçültme oranı
    const val MAX_SHIFT_ERR = 28f

    class Box(val x: Int, val y: Int, val w: Int, val h: Int)
    class Comp(val box: Box, val touchesEdge: Boolean, val pixels: Int)
    class Gray(val g: IntArray, val gw: Int, val gh: Int)

    private fun lum(p: Int): Int =
        (77 * ((p shr 16) and 255) + 151 * ((p shr 8) and 255) + 28 * (p and 255)) shr 8

    // Koyu piksellerin bağlı bileşenleri (8 komşuluk). Kutular tam çözünürlük pikselidir.
    fun components(px: IntArray, w: Int, h: Int): List<Comp> {
        val mw = w / SCALE
        val mh = h / SCALE
        if (mw < 4 || mh < 4) return emptyList()
        val dark = BooleanArray(mw * mh)
        for (y in 0 until mh) {
            val row = y * SCALE * w
            for (x in 0 until mw) dark[y * mw + x] = lum(px[row + x * SCALE]) < DARK_LUM
        }
        val seen = BooleanArray(mw * mh)
        val stack = IntArray(mw * mh)
        val out = ArrayList<Comp>()
        for (start in dark.indices) {
            if (!dark[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var x0 = Int.MAX_VALUE; var x1 = -1; var y0 = Int.MAX_VALUE; var y1 = -1
            var n = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % mw
                val cy = cur / mw
                n++
                if (cx < x0) x0 = cx
                if (cx > x1) x1 = cx
                if (cy < y0) y0 = cy
                if (cy > y1) y1 = cy
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= mw || ny >= mh) continue
                    val ni = ny * mw + nx
                    if (dark[ni] && !seen[ni]) { seen[ni] = true; stack[sp++] = ni }
                }
            }
            if (n < 20) continue
            out.add(
                Comp(
                    Box(x0 * SCALE, y0 * SCALE, (x1 - x0 + 1) * SCALE, (y1 - y0 + 1) * SCALE),
                    x0 == 0 || y0 == 0 || x1 == mw - 1 || y1 == mh - 1, n
                )
            )
        }
        return out
    }

    // Tek düğmeyi çevreleyen alandan düğme boyutu: en büyük koyu çerçeve bileşeni (alanın en az %40'ı)
    fun templateSize(px: IntArray, w: Int, h: Int): Pair<Int, Int>? {
        val best = components(px, w, h)
            .filter { it.box.w >= w * 0.4 && it.box.h >= h * 0.4 }
            .maxByOrNull { it.box.w.toLong() * it.box.h } ?: return null
        return best.box.w to best.box.h
    }

    // Boyutu şablona (±tol) uyan, alan kenarına değmeyen çerçeveler = düğmeler
    fun findButtons(px: IntArray, w: Int, h: Int, tw: Int, th: Int, tol: Float = 0.15f): List<Box> =
        components(px, w, h)
            .filter { !it.touchesEdge }
            .filter {
                abs(it.box.w - tw) <= tol * tw + SCALE && abs(it.box.h - th) <= tol * th + SCALE
            }
            .map { it.box }
            .sortedWith(compareBy({ it.y / (th / 2).coerceAtLeast(1) }, { it.x }))

    fun gray(px: IntArray, w: Int, h: Int): Gray {
        val gw = w / GRAY_F
        val gh = h / GRAY_F
        val g = IntArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var s = 0
            for (oy in 0 until GRAY_F step 2) for (ox in 0 until GRAY_F step 2)
                s += lum(px[(gy * GRAY_F + oy) * w + gx * GRAY_F + ox])
            g[gy * gw + gx] = s / 4
        }
        return Gray(g, gw, gh)
    }

    // cur[y] ~ prev[y + s]. s > 0: içerik yukarı kaydı (liste aşağı kaydırıldı).
    // Dönüş: (s gri-satır, ortalama hata). Eşitlikte küçük |s| tercih edilir.
    fun estimateShift(prev: Gray, cur: Gray): Pair<Int, Float>? {
        if (prev.gw != cur.gw || prev.gh != cur.gh) return null
        val gw = cur.gw
        val gh = cur.gh
        val maxS = (gh * 0.75f).toInt()
        var bestS = 0
        var bestE = Float.MAX_VALUE
        for (k in 0..maxS) {
            for (sg in if (k == 0) intArrayOf(1) else intArrayOf(1, -1)) {
                val s = k * sg
                val y0 = if (s < 0) -s else 0
                val y1 = if (s > 0) gh - s else gh
                if (y1 - y0 < gh / 4) continue
                var sum = 0L
                var cnt = 0
                var y = y0
                while (y < y1) {
                    var x = 0
                    while (x < gw) {
                        sum += abs(cur.g[y * gw + x] - prev.g[(y + s) * gw + x])
                        cnt++
                        x += 2
                    }
                    y += 2
                }
                if (cnt == 0) continue
                val e = sum.toFloat() / cnt
                if (e < bestE * 0.97f) { bestE = e; bestS = s }
            }
        }
        return if (bestE == Float.MAX_VALUE) null else bestS to bestE
    }

    // Aynı düğmeyi tekrar saymayan kayıt. Konumlar liste başına göre (genel) koordinattır.
    class Counter {
        private val gx = ArrayList<Int>()
        private val gy = ArrayList<Int>()
        private val reads = ArrayList<ArrayList<String>>()
        var offset = 0
            private set

        val total: Int get() = gx.size

        fun reset() { gx.clear(); gy.clear(); reads.clear(); offset = 0 }

        fun scroll(px: Int) { offset += px }

        fun register(boxes: List<Box>, tw: Int, th: Int): List<Int> {
            val out = ArrayList<Int>()
            for (b in boxes) {
                val y = b.y + offset
                var found = -1
                for (i in gx.indices) {
                    if (abs(gx[i] - b.x) < tw / 2 && abs(gy[i] - y) < th / 2) { found = i; break }
                }
                if (found < 0) { gx.add(b.x); gy.add(y); reads.add(ArrayList()); found = gx.size - 1 }
                else gy[found] = (gy[found] + y) / 2
                out.add(found)
            }
            return out
        }

        fun readCount(i: Int) = reads[i].size
        fun addRead(i: Int, s: String) { if (i in reads.indices) reads[i].add(s) }

        // En çok tekrarlanan okuma (eşitlikte uzun olan); hiç okuma yoksa null
        fun best(i: Int): String? {
            if (i !in reads.indices || reads[i].isEmpty()) return null
            return reads[i].groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
                .first().key
        }

        // Gösterim sırası: satır satır, soldan sağa (en sol üst = 1). Dönüş: kayıt numaraları sırayla.
        fun order(th: Int): List<Int> {
            val idx = gx.indices.sortedBy { gy[it] }
            val rows = ArrayList<ArrayList<Int>>()
            var rowStart = 0
            for (i in idx) {
                if (rows.isEmpty() || gy[i] - rowStart > th / 2) { rows.add(ArrayList()); rowStart = gy[i] }
                rows.last().add(i)
            }
            val out = ArrayList<Int>()
            for (r in rows) out.addAll(r.sortedBy { gx[it] })
            return out
        }
    }
}

class CounterService : AccessibilityService() {

    private class Shown(val rect: RectF, val number: Int)

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("counter", MODE_PRIVATE) }

    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var selectLayer: View? = null
    private var outline: OutlineView? = null
    private var statusTv: TextView? = null
    private var startTv: TextView? = null
    private var a1Tv: TextView? = null
    private var a2Tv: TextView? = null
    private var copyTv: TextView? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val inflight = HashSet<Int>()

    @Volatile private var running = false
    @Volatile private var shotBusy = false
    private val counter = Detector.Counter()
    private var prevGray: Detector.Gray? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) { ui.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } }
    private fun removeView(v: View?) { if (v != null) try { wm.removeView(v) } catch (_: Exception) {} }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile var instance: CounterService? = null
    }

    private var bubble: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            showOutline()
            showPanel()
            refreshPanel()
            setPanelVisible(!prefs.getBoolean("hidden", false))
            toast("Liste Sayaç hazır")
        } catch (e: Exception) {
            toast("Panel açılamadı: ${e.message}")
        }
    }

    fun isPanelVisible(): Boolean = panel?.visibility == View.VISIBLE

    fun setPanelVisible(v: Boolean) {
        ui.post {
            prefs.edit().putBoolean("hidden", !v).apply()
            panel?.visibility = if (v) View.VISIBLE else View.GONE
            if (v) { removeView(bubble); bubble = null } else showBubble()
        }
    }

    // Panel gizliyken ekranda kalan, sürüklenebilen küçük düğme; dokununca panel açılır
    private fun showBubble() {
        if (bubble != null) return
        val tv = TextView(this).apply {
            text = "☰"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E61E88E5")); setStroke(dp(1), Color.WHITE)
            }
        }
        val sz = dp(46)
        val lp = WindowManager.LayoutParams(
            sz, sz, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = prefs.getInt("bx", dp(8)); y = prefs.getInt("by", dp(120)) }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - dx) > dp(8) || abs(e.rawY - dy) > dp(8)) moved = true
                    if (moved) {
                        val b = wm.currentWindowMetrics.bounds
                        lp.x = (sx + (e.rawX - dx).toInt()).coerceIn(0, max(0, b.width() - sz))
                        lp.y = (sy + (e.rawY - dy).toInt()).coerceIn(0, max(0, b.height() - sz))
                        try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP ->
                    if (!moved) setPanelVisible(true)
                    else prefs.edit().putInt("bx", lp.x).putInt("by", lp.y).apply()
            }
            true
        }
        try { wm.addView(tv, lp); bubble = tv } catch (_: Exception) {}
    }

    override fun onDestroy() {
        instance = null
        running = false
        removeView(bubble); bubble = null
        ui.removeCallbacksAndMessages(null)
        removeView(selectLayer); selectLayer = null
        removeView(outline); outline = null
        removeView(panel); panel = null
        try { recognizer.close() } catch (_: Exception) {}
        bg.shutdown()
        super.onDestroy()
    }

    // ---------- Panel (her zaman üstte) ----------

    private fun btn(label: String, color: String, act: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.parseColor(color)); cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(10), dp(7), dp(10), dp(7))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(5) }
        setOnClickListener { act() }
    }

    private fun showPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6101820")); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        val head = TextView(this).apply {
            text = "⠿ Liste Sayaç (sürükle)"
            textSize = 12f
            setTextColor(Color.parseColor("#9FB0C7"))
            setPadding(0, 0, 0, dp(2))
        }
        box.addView(head)
        val a1 = btn("1. alan: TEK düğme", "#1E88E5") { pickArea(1) }
        val a2 = btn("2. alan: 3'lü liste", "#00897B") { pickArea(2) }
        a1Tv = a1; a2Tv = a2
        box.addView(a1); box.addView(a2)
        val st = btn("Başlat", "#43A047") { toggle() }
        startTv = st
        box.addView(st)
        box.addView(btn("Sıfırla", "#607D8B") { resetCount() })
        val cp = btn("Kopyala", "#3A4658") { copyList() }
        copyTv = cp
        box.addView(cp)
        box.addView(btn("Gizle", "#455A64") { setPanelVisible(false) })
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, 0)
        }
        statusTv = status
        box.addView(status)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(60) }

        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        head.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    val b = wm.currentWindowMetrics.bounds
                    lp.x = (sx + (e.rawX - dx).toInt()).coerceIn(0, max(0, b.width() - box.width))
                    lp.y = (sy + (e.rawY - dy).toInt()).coerceIn(0, max(0, b.height() - box.height))
                    try { wm.updateViewLayout(box, lp) } catch (_: Exception) {}
                }
            }
            true
        }
        wm.addView(box, lp)
        panel = box
        panelLp = lp
    }

    private fun raisePanel() {
        val p = panel ?: return
        val l = panelLp ?: return
        try { wm.removeView(p) } catch (_: Exception) {}
        try { wm.addView(p, l) } catch (_: Exception) {}
    }

    private fun areaSet(n: Int) = prefs.contains("a${n}x1")
    private fun tw() = prefs.getInt("tw", 0)
    private fun th() = prefs.getInt("th", 0)

    private fun refreshPanel() {
        ui.post {
            a1Tv?.text = if (areaSet(1) && tw() > 0) "1. alan ✓ (${tw()}×${th()} px)" else "1. alan: TEK düğme"
            a2Tv?.text = if (areaSet(2)) "2. alan ✓" else "2. alan: 3'lü liste"
            startTv?.text = if (running) "Durdur" else "Başlat"
            (startTv?.background as? GradientDrawable)?.setColor(Color.parseColor(if (running) "#E53935" else "#43A047"))
            val canCopy = !running && synchronized(counter) { counter.total } > 0
            (copyTv?.background as? GradientDrawable)?.setColor(Color.parseColor(if (canCopy) "#1E88E5" else "#3A4658"))
            copyTv?.alpha = if (canCopy) 1f else 0.4f
        }
    }

    private fun setStatus(t: String) { ui.post { statusTv?.text = t } }

    // ---------- Alan seçme (sürükle) ----------

    private inner class SelectView(ctx: Context, private val done: (FloatArray?) -> Unit) : View(ctx) {
        private val fill = Paint().apply { style = Paint.Style.FILL; color = Color.parseColor("#3300E5FF") }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat(); color = Color.parseColor("#00E5FF")
        }
        private var x0 = 0f; private var y0 = 0f; private var x1 = 0f; private var y1 = 0f
        private var drawing = false

        init { setBackgroundColor(Color.parseColor("#44000000")) }

        override fun onDraw(c: Canvas) {
            if (!drawing) return
            val r = RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
            c.drawRect(r, fill); c.drawRect(r, edge)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; x1 = e.x; y1 = e.y; drawing = true }
                MotionEvent.ACTION_MOVE -> { x1 = e.x; y1 = e.y }
                MotionEvent.ACTION_UP -> {
                    x1 = e.x; y1 = e.y; drawing = false
                    val w = width.toFloat(); val h = height.toFloat()
                    val l = min(x0, x1) / w; val r = max(x0, x1) / w
                    val t = min(y0, y1) / h; val b = max(y0, y1) / h
                    if (r - l < 0.02f || b - t < 0.01f) done(null)
                    else done(floatArrayOf(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f)))
                }
                MotionEvent.ACTION_CANCEL -> { drawing = false; done(null) }
            }
            invalidate()
            return true
        }
    }

    private fun pickArea(n: Int) {
        if (running) { toast("Önce durdur"); return }
        if (selectLayer != null) { removeView(selectLayer); selectLayer = null; return }
        var holder: View? = null
        val v = SelectView(this) { fr ->
            removeView(holder); selectLayer = null
            if (fr == null) toast("Alan çok küçük, tekrar dene") else saveArea(n, fr)
        }
        holder = v
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try { wm.addView(v, lp) } catch (_: Exception) { toast("Alan seçimi açılamadı"); return }
        selectLayer = v
        raisePanel()
        toast(if (n == 1) "TEK bir düğmeyi sürükleyerek sıkıca çevrele" else "3'lü düğmelerin bulunduğu liste alanını sürükleyerek çevrele")
    }

    private fun saveArea(n: Int, f: FloatArray) {
        prefs.edit().putFloat("a${n}x1", f[0]).putFloat("a${n}y1", f[1])
            .putFloat("a${n}x2", f[2]).putFloat("a${n}y2", f[3]).apply()
        if (n == 2) { refreshPanel(); toast("2. alan kaydedildi"); return }
        // 1. alan: düğme şekli (boyutu) hemen ölçülür
        prefs.edit().remove("tw").remove("th").apply()
        ui.postDelayed({
            shot { bmp ->
                if (bmp == null) { toast("Ekran alınamadı, 1. alanı tekrar seç"); return@shot }
                val px = cropPixels(bmp, f)
                val size = px?.let { Detector.templateSize(it.first, it.second, it.third) }
                if (size == null) {
                    toast("Düğme çerçevesi bulunamadı. Sadece bir düğmeyi, kenarlarıyla birlikte sıkıca çevrele")
                } else {
                    prefs.edit().putInt("tw", size.first).putInt("th", size.second).apply()
                    toast("Düğme şekli alındı: ${size.first}×${size.second} px")
                }
                refreshPanel()
            }
        }, 350)
    }

    // ---------- Ekran görüntüsü ----------

    // cb arka plan iş parçacığında çağrılır; bitmap yalnızca cb boyunca geçerli
    private fun shot(cb: (Bitmap?) -> Unit) {
        if (shotBusy) { cb(null); return }
        shotBusy = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, bg, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var bmp: Bitmap? = null
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                    } catch (_: Exception) {
                    } finally {
                        result.hardwareBuffer.close()
                        shotBusy = false
                    }
                    try { cb(bmp) } finally { bmp?.recycle() }
                }

                override fun onFailure(errorCode: Int) { shotBusy = false; cb(null) }
            })
        } catch (_: Exception) {
            shotBusy = false
            cb(null)
        }
    }

    // (pikseller, genişlik, yükseklik) ve sol/üst piksel ofseti
    private fun cropPixels(bmp: Bitmap, f: FloatArray): Triple<IntArray, Int, Int>? {
        val l = (f[0] * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val t = (f[1] * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val r = (f[2] * bmp.width).toInt().coerceIn(l + 1, bmp.width)
        val b = (f[3] * bmp.height).toInt().coerceIn(t + 1, bmp.height)
        val w = r - l; val h = b - t
        if (w < 8 || h < 8) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, l, t, w, h)
        return Triple(px, w, h)
    }

    // ---------- Sayma ----------

    private fun area(n: Int): FloatArray? =
        if (!areaSet(n)) null
        else floatArrayOf(prefs.getFloat("a${n}x1", 0f), prefs.getFloat("a${n}y1", 0f),
            prefs.getFloat("a${n}x2", 0f), prefs.getFloat("a${n}y2", 0f))

    private fun toggle() {
        if (running) { stopCount(); return }
        if (selectLayer != null) { removeView(selectLayer); selectLayer = null }
        if (area(2) == null) { toast("Önce 2. alanı (3'lü liste) seç"); return }
        if (tw() <= 0 || th() <= 0) { toast("Önce 1. alanı (tek düğme) seç, şekil alınmalı"); return }
        synchronized(counter) { counter.reset() }
        prevGray = null
        running = true
        refreshPanel()
        setStatus("Sayı: 0 (listeyi yavaşça aşağı kaydır)")
        ui.post(tick)
    }

    private fun stopCount() {
        running = false
        ui.removeCallbacks(tick)
        val n = synchronized(counter) { counter.total }
        outline?.set(emptyList())
        refreshPanel()
        setStatus("Bitti: toplam $n düğme (Kopyala ile listeyi al)")
        toast("Toplam $n düğme")
    }

    private fun resetCount() {
        if (running) { toast("Önce durdur"); return }
        synchronized(counter) { counter.reset() }
        prevGray = null
        outline?.set(emptyList())
        refreshPanel()
        setStatus("Sıfırlandı")
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            shot { bmp -> if (bmp != null) process(bmp) }
            ui.postDelayed(this, 400)
        }
    }

    // Arka plan iş parçacığı
    private fun process(bmp: Bitmap) {
        if (!running) return
        val a = area(2) ?: return
        val tw = tw(); val th = th()
        val crop = cropPixels(bmp, a) ?: return
        val px = crop.first; val w = crop.second; val h = crop.third
        val l = (a[0] * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val t = (a[1] * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val screen = wm.currentWindowMetrics.bounds
        val kx = screen.width().toFloat() / bmp.width
        val ky = screen.height().toFloat() / bmp.height

        val boxes = Detector.findButtons(px, w, h, tw, th)
        val gr = Detector.gray(px, w, h)
        val prev = prevGray
        var ok = true
        if (prev != null && prev.gw == gr.gw && prev.gh == gr.gh) {
            val r = Detector.estimateShift(prev, gr)
            if (r == null || r.second > Detector.MAX_SHIFT_ERR) ok = false
            else synchronized(counter) { counter.scroll(r.first * Detector.GRAY_F) }
        }
        prevGray = gr

        if (!ok) {
            setStatus("Sayı: ${synchronized(counter) { counter.total }} — takip kayboldu, daha yavaş kaydır")
            ui.post { outline?.set(emptyList()) }
            return
        }
        val rank = HashMap<Int, Int>()
        val info = synchronized(counter) {
            val ids = counter.register(boxes, tw, th)
            counter.order(th).forEachIndexed { n, i -> rank[i] = n + 1 }
            Triple(ids, counter.total, (0 until counter.total).count { counter.best(it) != null })
        }
        val idx = info.first
        val total = info.second
        val readN = info.third
        val shown = boxes.mapIndexed { i, b ->
            Shown(RectF((l + b.x) * kx, (t + b.y) * ky, (l + b.x + b.w) * kx, (t + b.y + b.h) * ky), rank[idx[i]] ?: 0)
        }
        for (i in boxes.indices) ocrButton(bmp, boxes[i], l, t, idx[i])
        ui.post { if (running) outline?.set(shown) }
        setStatus("Sayı: $total, okunan: $readN (ekranda ${boxes.size})")
    }

    // ---------- Yazı okuma (düğme şeklinden bağımsız; sadece sayılan düğmenin metni) ----------

    private fun clean(s: String) = s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    // Beyaz yazı siyah, gerisi beyaz: koyu kontur ve mavi zemin silinir. 2x büyütülür.
    private fun binarize(src: Bitmap): Bitmap {
        val w = src.width * 2; val h = src.height * 2
        val big = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        big.getPixels(px, 0, w, 0, 0, w, h)
        if (big !== src) big.recycle()
        for (i in px.indices) {
            val p = px[i]
            val lum = (77 * ((p shr 16) and 255) + 151 * ((p shr 8) and 255) + 28 * (p and 255)) shr 8
            px[i] = if (lum > 175) Color.BLACK else Color.WHITE
        }
        val out = Bitmap.createBitmap(w + 32, h + 32, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        out.setPixels(px, 0, w, 16, 16, w, h)
        return out
    }

    private fun ocrButton(bmp: Bitmap, b: Detector.Box, l: Int, t: Int, idx: Int) {
        synchronized(counter) {
            if (counter.readCount(idx) >= 4 || inflight.contains(idx)) return
            inflight.add(idx)
        }
        val ix = (b.w * 0.08f).toInt(); val iy = (b.h * 0.12f).toInt()
        val x = (l + b.x + ix).coerceIn(0, bmp.width - 1)
        val y = (t + b.y + iy).coerceIn(0, bmp.height - 1)
        val cw = (b.w - 2 * ix).coerceAtMost(bmp.width - x)
        val ch = (b.h - 2 * iy).coerceAtMost(bmp.height - y)
        if (cw < 16 || ch < 10) { synchronized(counter) { inflight.remove(idx) }; return }
        val crop = try { Bitmap.createBitmap(bmp, x, y, cw, ch) } catch (_: Exception) {
            synchronized(counter) { inflight.remove(idx) }; return
        }
        val bin = binarize(crop)
        fun done(text: String?) {
            synchronized(counter) {
                inflight.remove(idx)
                if (!text.isNullOrEmpty() && idx < counter.total) counter.addRead(idx, text)
            }
        }
        recognizer.process(InputImage.fromBitmap(bin, 0))
            .addOnSuccessListener { r ->
                val tx = clean(r.text)
                bin.recycle()
                if (tx.isNotEmpty()) { crop.recycle(); done(tx) }
                else {
                    // yedek: özgün renkli kırpma
                    recognizer.process(InputImage.fromBitmap(crop, 0))
                        .addOnSuccessListener { r2 -> crop.recycle(); done(clean(r2.text)) }
                        .addOnFailureListener { crop.recycle(); done(null) }
                }
            }
            .addOnFailureListener { bin.recycle(); crop.recycle(); done(null) }
    }

    private fun copyList() {
        val (running0, lines) = synchronized(counter) {
            running to counter.order(th()).mapIndexed { n, i -> "${n + 1}. ${counter.best(i) ?: "(okunamadı)"}" }
        }
        if (running0 || lines.isEmpty()) { toast("Önce başlat, en az 1 düğme oku, sonra Durdur"); return }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("liste", lines.joinToString("\n")))
        toast("Kopyalandı (${lines.size} satır)")
    }

    // ---------- Koyu mavi çerçeve + numara ----------
    // Rengin parlaklığı Detector.DARK_LUM üstünde: görüntüye girse bile "koyu kenar" sayılmaz.

    private inner class OutlineView(ctx: Context) : View(ctx) {
        private var items: List<Shown> = emptyList()
        private val loc = IntArray(2)
        private val navy = Color.parseColor("#2B3DDB")
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat(); color = navy
        }
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = navy }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
            textSize = dp(15).toFloat()
        }

        fun set(l: List<Shown>) { items = l; invalidate() }

        override fun onDraw(c: Canvas) {
            getLocationOnScreen(loc)
            val pad = dp(3).toFloat()
            for (s in items) {
                val r = RectF(s.rect.left - loc[0] - pad, s.rect.top - loc[1] - pad,
                    s.rect.right - loc[0] + pad, s.rect.bottom - loc[1] + pad)
                c.drawRoundRect(r, dp(8).toFloat(), dp(8).toFloat(), stroke)
                val rad = dp(13).toFloat()
                val cx = r.left + rad; val cy = r.top + rad
                c.drawCircle(cx, cy, rad, badge)
                val fm = txt.fontMetrics
                c.drawText(s.number.toString(), cx, cy - (fm.ascent + fm.descent) / 2f, txt)
            }
        }
    }

    private fun showOutline() {
        val v = OutlineView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        outline = v
        wm.addView(v, lp)
    }

}

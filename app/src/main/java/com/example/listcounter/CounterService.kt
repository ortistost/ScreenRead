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

    class Box(val x: Int, val y: Int, val w: Int, val h: Int)
    class Comp(val box: Box, val touchesEdge: Boolean, val pixels: Int)

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

    // Kaba (yarı çözünürlük) kutuyu tam çözünürlükte netleştirir: kutunun çevresindeki koyu piksellerin sınırı.
    fun refine(px: IntArray, w: Int, h: Int, b: Box): Box {
        val pad = 4
        val x0 = (b.x - pad).coerceAtLeast(0); val x1 = (b.x + b.w + pad).coerceAtMost(w) - 1
        val y0 = (b.y - pad).coerceAtLeast(0); val y1 = (b.y + b.h + pad).coerceAtMost(h) - 1
        var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
        for (y in y0..y1) for (x in x0..x1) {
            if (lum(px[y * w + x]) < DARK_LUM) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) b else Box(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    // Düğmenin içinin (kenar payı atılmış) 32x10'luk gri özeti: aynı yazı = benzer özet, yeri önemli değil.
    const val SIG_W = 32
    const val SIG_H = 10
    const val SIG_TOL = 10

    fun signature(px: IntArray, w: Int, h: Int, b: Box): IntArray {
        val ix = (b.w * 0.08f).toInt(); val iy = (b.h * 0.12f).toInt()
        val x0 = b.x + ix; val y0 = b.y + iy
        val cw = b.w - 2 * ix; val ch = b.h - 2 * iy
        val out = IntArray(SIG_W * SIG_H)
        for (j in 0 until SIG_H) for (i in 0 until SIG_W) {
            val xs = x0 + i * cw / SIG_W; val xe = maxOf(xs + 1, x0 + (i + 1) * cw / SIG_W)
            val ys = y0 + j * ch / SIG_H; val ye = maxOf(ys + 1, y0 + (j + 1) * ch / SIG_H)
            var sum = 0; var n = 0
            for (y in ys until minOf(ye, h)) for (x in xs until minOf(xe, w)) { sum += lum(px[y * w + x]); n++ }
            out[j * SIG_W + i] = if (n > 0) sum / n else 0
        }
        return out
    }

    // Ekranda görülen düğmeleri hatırlar. Aynı düğme (aynı özet) tekrar görülünce yeni sayılmaz,
    // eski numarasını alır. Numara = ilk görülme sırası (1'den).
    class Registry {
        private val sigs = ArrayList<IntArray>()
        private val reads = ArrayList<ArrayList<String>>()

        val total: Int get() = sigs.size

        fun reset() { sigs.clear(); reads.clear() }

        // Kayıtlı düğmelerden en benzeyeni (fark < SIG_TOL) ya da -1
        fun find(sig: IntArray): Int {
            var best = -1
            var bestD = Int.MAX_VALUE
            for (i in sigs.indices) {
                val o = sigs[i]
                var sum = 0
                for (k in sig.indices) sum += abs(sig[k] - o[k])
                val d = sum / sig.size
                if (d < bestD) { bestD = d; best = i }
            }
            return if (bestD < SIG_TOL) best else -1
        }

        // Varsa eskisinin numarasını (0'dan), yoksa yeni kayıt açıp onun numarasını döndürür
        fun idOf(sig: IntArray): Int {
            val f = find(sig)
            if (f >= 0) return f
            sigs.add(sig); reads.add(ArrayList())
            return sigs.size - 1
        }

        fun readCount(i: Int) = reads[i].size
        fun addRead(i: Int, s: String) { if (i in reads.indices) reads[i].add(s) }

        fun best(i: Int): String? {
            if (i !in reads.indices || reads[i].isEmpty()) return null
            return reads[i].groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
                .first().key
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
    private var copyTv: TextView? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val inflight = HashSet<Int>()

    @Volatile private var running = false
    @Volatile private var shotBusy = false
    private val counter = Detector.Registry()

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
        val a1 = btn("Alan seç: TEK düğme", "#1E88E5") { pickArea(1) }
        a1Tv = a1
        box.addView(a1)
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
            a1Tv?.text = if (tw() > 0) "Düğme şekli ✓ (${tw()}×${th()} px)" else "Alan seç: TEK düğme"
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
        toast("TEK bir düğmeyi, kenarlarıyla birlikte sürükleyerek sıkıca çevrele")
    }

    private fun saveArea(n: Int, f: FloatArray) {
        prefs.edit().putFloat("a${n}x1", f[0]).putFloat("a${n}y1", f[1])
            .putFloat("a${n}x2", f[2]).putFloat("a${n}y2", f[3]).apply()
        // 1. alan: düğme şekli (boyutu) hemen ölçülür
        prefs.edit().remove("tw").remove("th").apply()
        ui.postDelayed({
            shot { bmp ->
                if (bmp == null) { toast("Ekran alınamadı, alanı tekrar seç"); return@shot }
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

    private fun toggle() {
        if (running) { stopCount(); return }
        if (selectLayer != null) { removeView(selectLayer); selectLayer = null }
        if (tw() <= 0 || th() <= 0) { toast("Önce tek bir düğmenin alanını seç, şekil alınmalı"); return }
        synchronized(counter) { counter.reset() }
        running = true
        refreshPanel()
        setStatus("Sayı: 0 (listeyi kaydır)")
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
    // Arka plan iş parçacığı: ekrandaki TÜM düğmeleri bulur, tanır (hatırlar) ve numaralar
    private fun process(bmp: Bitmap) {
        if (!running) return
        val tw = tw(); val th = th()
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val screen = wm.currentWindowMetrics.bounds
        val kx = screen.width().toFloat() / w
        val ky = screen.height().toFloat() / h

        val boxes = Detector.findButtons(px, w, h, tw, th).map { Detector.refine(px, w, h, it) }
        val sigs = boxes.map { Detector.signature(px, w, h, it) }
        val info = synchronized(counter) {
            val ids = sigs.map { counter.idOf(it) }
            Triple(ids, counter.total, (0 until counter.total).count { counter.best(it) != null })
        }
        val ids = info.first
        val shown = boxes.mapIndexed { i, b ->
            Shown(RectF(b.x * kx, b.y * ky, (b.x + b.w) * kx, (b.y + b.h) * ky), ids[i] + 1)
        }
        for (i in boxes.indices) ocrButton(bmp, boxes[i], 0, 0, ids[i])
        ui.post { if (running) outline?.set(shown) }
        setStatus("Sayı: ${info.second}, okunan: ${info.third} (ekranda ${boxes.size})")
    }

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
            running to (0 until counter.total).map { "${it + 1}. ${counter.best(it) ?: "(okunamadı)"}" }
        }
        if (running0 || lines.isEmpty()) { toast("Önce başlat, en az 1 düğme oku, sonra Durdur"); return }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("liste", lines.joinToString("\n")))
        toast("Kopyalandı (${lines.size} satır)")
    }

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

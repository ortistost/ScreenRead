package com.example.listcounter

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (28 * d).toInt(), (20 * d).toInt(), (28 * d).toInt())
            setBackgroundColor(Color.parseColor("#0B1B3A"))
        }
        fun tv(t: String, size: Float, c: Int = Color.WHITE) = TextView(this).apply {
            text = t; textSize = size; setTextColor(c); setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        col.addView(tv("Liste Sayaç  v$ver", 22f))
        status = tv("", 16f, Color.parseColor("#FFD54F"))
        col.addView(status)
        col.addView(Button(this).apply {
            text = "Paneli GÖSTER"
            setOnClickListener {
                val s = CounterService.instance
                if (s == null) status.text = "Servis kapalı: önce Erişilebilirlik'ten etkinleştir"
                else { s.setPanelVisible(true); refresh() }
            }
        })
        col.addView(Button(this).apply {
            text = "Paneli GİZLE"
            setOnClickListener {
                val s = CounterService.instance
                if (s == null) status.text = "Servis kapalı: önce Erişilebilirlik'ten etkinleştir"
                else { s.setPanelVisible(false); refresh() }
            }
        })
        col.addView(Button(this).apply {
            text = "Erişilebilirlik ayarlarını aç"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(tv(
            "Erişilebilirlik servisi hep açık kalsın; paneli buradan veya panelin \"Gizle\" düğmesinden gizle/göster. " +
                "Panel gizliyken ekranda ☰ düğmesi kalır (sürüklenir, dokununca panel açılır).\n\n" +
                "Alan seç: tek bir düğmeyi çevrele (şekil örneği). Başlat: listeyi kaydır; ekrandaki tüm düğmeler numaralanır, " +
                "görülenler hatırlanır, tekrar sayılmaz. Durdur: toplam sayı. Kopyala: \"1. Ad (KOD)\" listesini panoya alır.",
            14f, Color.parseColor("#C9D6F2")
        ))
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val s = CounterService.instance
        status.text = when {
            s == null -> "Servis kapalı. Erişilebilirlik'ten \"Liste Sayaç\" servisini etkinleştir."
            s.isPanelVisible() -> "Servis çalışıyor, panel görünür."
            else -> "Servis çalışıyor, panel gizli (☰ düğmesi ekranda)."
        }
    }
}

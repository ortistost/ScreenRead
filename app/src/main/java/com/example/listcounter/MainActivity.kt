package com.example.listcounter

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
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
        col.addView(tv(
            "1) Aşağıdaki düğmeden Erişilebilirlik ayarlarını aç, \"Liste Sayaç\" servisini etkinleştir.\n" +
                "2) Servis açılınca ekranın üstünde her zaman görünen küçük bir panel çıkar.\n" +
                "3) \"1. alan\": sürükleyerek TEK bir düğmeyi çevrele (şekil örneği).\n" +
                "4) \"2. alan\": sürükleyerek listenin (3'lü satırların) bulunduğu alanı çevrele.\n" +
                "5) \"Başlat\": listeyi yavaşça aşağı kaydır. Düğmeler koyu mavi çerçeve ve numarayla işaretlenir, yazıları okunur.\n" +
                "6) En alta inince \"Durdur\". Sonra \"Kopyala\" ile listeyi al.",
            15f, Color.parseColor("#C9D6F2")
        ))
        col.addView(Button(this).apply {
            text = "Erişilebilirlik ayarlarını aç"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(tv(
            "Güncelleme: yeni APK'yı eskisinin üstüne direkt yükleyebilirsin.",
            13f, Color.parseColor("#8FA3CC")
        ).apply { gravity = Gravity.START })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}

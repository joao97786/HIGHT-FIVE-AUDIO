package com.blockstudios.hifiv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private lateinit var btn: Button
    private var devices: List<AudioDeviceInfo> = emptyList()
    private var on = false
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40); setBackgroundColor(Color.parseColor("#0b0f0e")) }
        fun tv(t: String, size: Float = 14f) = TextView(this).apply { text = t; textSize = size; setTextColor(Color.parseColor("#00ffcc")) }
        root.addView(tv("BLOCK STUDIOS · HI-FIV", 20f))
        root.addView(tv("Saída (caixa Bluetooth / fone):"))
        spinner = Spinner(this); root.addView(spinner)
        fun slider(name: String, min: Int, max: Int, init: Int, fmt: (Int) -> String, set: (Int) -> Unit) {
            val label = tv("$name: ${fmt(init)}")
            val sb = SeekBar(this).apply { this.max = max - min; progress = init - min }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { set(p + min); label.text = "$name: ${fmt(p + min)}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            root.addView(label); root.addView(sb)
        }
        slider("Graves", -12, 12, 6, { "$it dB" }) { Params.bass = it.toDouble() }
        slider("Voz / Médios", -12, 12, 4, { "$it dB" }) { Params.voice = it.toDouble() }
        slider("Agudos", -12, 12, 0, { "$it dB" }) { Params.treble = it.toDouble() }
        slider("Volume", 0, 150, 100, { "$it%" }) { Params.master = it / 100.0 }
        slider("Anti-explosão (grave)", -30, -3, -9, { "$it dB" }) { Params.axThrDb = it.toDouble() }
        slider("Atraso", 0, 1000, 0, { "$it ms" }) { Params.delayMs = it }
        btn = Button(this).apply { text = "INICIAR" }; root.addView(btn)
        status = tv("Parado."); root.addView(status)
        root.addView(tv("Dica: o som original do celular continua tocando. Mande o áudio para a caixa e silencie o celular, ou deixe o original no fone e o processado na caixa.", 11f))
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)); setBackgroundColor(Color.parseColor("#0b0f0e")) })

        val am = getSystemService(AudioManager::class.java)
        devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Padrão do aparelho") + devices.map { "${it.productName} (tipo ${it.type})" })

        btn.setOnClickListener {
            if (on) { startService(Intent(this, HifivService::class.java).setAction("STOP")); on = false; btn.text = "INICIAR"; status.text = "Parado." }
            else startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 100)
        }
        ui.post(object : Runnable { override fun run() {
            if (on) status.text = "Ativo · limitador geral: %.1f dB · anti-explosão: %.1f dB".format(Params.grPeak, Params.grBass)
            ui.postDelayed(this, 300) } })
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 100 && res == RESULT_OK && data != null) {
            val sel = spinner.selectedItemPosition
            val i = Intent(this, HifivService::class.java).putExtra("code", res).putExtra("data", data)
                .putExtra("deviceId", if (sel > 0) devices[sel - 1].id else -1)
            startForegroundService(i); on = true; btn.text = "PARAR"
        }
    }
}

package com.blockstudios.hifiv

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Process
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.tanh

/** Captura o áudio dos outros apps, processa (EQ + anti-explosão + limitador) e toca na saída escolhida. */
class HifivService : Service() {
    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("hifiv", "HI-FIV", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "hifiv").setContentTitle("HI-FIV ativo")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)   // antes de getMediaProjection (Android 14+)

        val code = intent!!.getIntExtra("code", 0)
        val data = intent.getParcelableExtra<Intent>("data")!!
        val deviceId = intent.getIntExtra("deviceId", -1)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        running = true
        worker = Thread { loop(deviceId) }.also { it.priority = Thread.MAX_PRIORITY; it.start() }
        return START_NOT_STICKY
    }

    private fun loop(deviceId: Int) {
        val sr = 48000
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(Process.myUid()).build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val rec = AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(cfg)
            .setBufferSizeInBytes(8192 * 4).build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(8192 * 4)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        if (deviceId >= 0) {
            val dev = getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
            if (dev != null) track.preferredDevice = dev
        }

        val fs = sr.toDouble()
        fun two() = arrayOf(Biquad(), Biquad())
        val bassEq = two(); val voiceEq = two(); val trebEq = two(); val lp1 = two(); val lp2 = two()
        lp1.forEach { it.lowpass(fs, 150.0, 0.7071) }; lp2.forEach { it.lowpass(fs, 150.0, 0.7071) }
        val bassLim = LookLim(sr, 20.0, 6.0, 300.0)   // anti-explosão: só na faixa de graves
        val peakLim = LookLim(sr, 12.0, 4.0, 250.0)   // limitador geral
        val dl = DoubleArray(sr); val dr = DoubleArray(sr); var wp = 0
        var last = doubleArrayOf(1e9, 1e9, 1e9)

        val buf = ShortArray(960)
        rec.startRecording(); track.play()
        var blocks = 0
        while (running) {
            val got = rec.read(buf, 0, buf.size)
            if (got <= 0) continue
            // atualiza filtros só quando os controles mudam
            val cur = doubleArrayOf(Params.bass, Params.voice, Params.treble)
            if (!cur.contentEquals(last)) {
                for (c in 0..1) { bassEq[c].lowShelf(fs, 80.0, cur[0]); voiceEq[c].peaking(fs, 2500.0, 1.0, cur[1]); trebEq[c].highShelf(fs, 10000.0, cur[2]) }
                last = cur
            }
            val thrB = Math.pow(10.0, Params.axThrDb / 20); val mst = Params.master
            val dSamp = (Params.delayMs * sr / 1000).coerceIn(0, sr - 1)
            var i = 0
            while (i + 1 < got) {
                var l = buf[i] / 32768.0; var r = buf[i + 1] / 32768.0
                l = trebEq[0].process(voiceEq[0].process(bassEq[0].process(l))) * mst
                r = trebEq[1].process(voiceEq[1].process(bassEq[1].process(r))) * mst
                // anti-explosão: saída = original - graves*(1 - ganho)
                val ll = lp2[0].process(lp1[0].process(l)); val lr = lp2[1].process(lp1[1].process(r))
                val gb = bassLim.step(l, r, ll, lr, max(abs(ll), abs(lr)), thrB)
                l = bassLim.o0 - bassLim.q0 * (1 - gb); r = bassLim.o1 - bassLim.q1 * (1 - gb)
                val gp = peakLim.step(l, r, l, r, max(abs(l), abs(r)), 0.89)
                l = peakLim.o0 * gp; r = peakLim.o1 * gp
                // atraso (compensar a latência da caixa)
                dl[wp] = l; dr[wp] = r
                val rp = (wp - dSamp + sr) % sr
                l = dl[rp]; r = dr[rp]; wp = (wp + 1) % sr
                // rede de segurança: arredonda só acima de 0,95
                if (abs(l) > 0.95) l = Math.signum(l) * (0.95 + 0.05 * tanh((abs(l) - 0.95) / 0.05))
                if (abs(r) > 0.95) r = Math.signum(r) * (0.95 + 0.05 * tanh((abs(r) - 0.95) / 0.05))
                buf[i] = (l * 32767).toInt().coerceIn(-32768, 32767).toShort()
                buf[i + 1] = (r * 32767).toInt().coerceIn(-32768, 32767).toShort()
                i += 2
            }
            track.write(buf, 0, got)
            if (++blocks % 20 == 0) {
                Params.grBass = 20 * log10(max(1e-4, bassLim.minG)); Params.grPeak = 20 * log10(max(1e-4, peakLim.minG))
                bassLim.minG = 1.0; peakLim.minG = 1.0
            }
        }
        rec.stop(); rec.release(); track.stop(); track.release()
    }

    override fun onDestroy() {
        running = false; worker?.join(500); projection?.stop(); super.onDestroy()
    }
}

package com.blockstudios.hifiv

import kotlin.math.*

/** Parâmetros compartilhados entre a tela e o serviço de áudio. */
object Params {
    @Volatile var bass = 6.0; @Volatile var voice = 4.0; @Volatile var treble = 0.0
    @Volatile var master = 1.0; @Volatile var axThrDb = -9.0; @Volatile var delayMs = 0
    @Volatile var grBass = 0.0; @Volatile var grPeak = 0.0
}

class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var z1 = 0.0; private var z2 = 0.0
    fun set(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
        b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = na1 / a0; a2 = na2 / a0
    }
    fun process(x: Double): Double { val y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y }

    // Fórmulas RBJ (Audio EQ Cookbook)
    fun lowShelf(fs: Double, hz: Double, db: Double) {
        val A = 10.0.pow(db / 40); val w = 2 * PI * hz / fs; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val k = 2 * sqrt(A) * al
        set(A * ((A + 1) - (A - 1) * c + k), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - k),
            (A + 1) + (A - 1) * c + k, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - k)
    }
    fun highShelf(fs: Double, hz: Double, db: Double) {
        val A = 10.0.pow(db / 40); val w = 2 * PI * hz / fs; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val k = 2 * sqrt(A) * al
        set(A * ((A + 1) + (A - 1) * c + k), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - k),
            (A + 1) - (A - 1) * c + k, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - k)
    }
    fun peaking(fs: Double, hz: Double, q: Double, db: Double) {
        val A = 10.0.pow(db / 40); val w = 2 * PI * hz / fs; val c = cos(w); val al = sin(w) / (2 * q)
        set(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A)
    }
    fun lowpass(fs: Double, hz: Double, q: Double) {
        val w = 2 * PI * hz / fs; val c = cos(w); val al = sin(w) / (2 * q)
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
    }
}

/**
 * Limitador "lookahead": abaixa o ganho ANTES do pico chegar (sem cortar a onda).
 * x = sinal que sai atrasado (o0/o1); s = sinal secundário atrasado (q0/q1, usado no limitador de graves).
 */
class LookLim(sr: Int, lookMs: Double, atkMs: Double, relMs: Double) {
    private val n = max(16, (lookMs * sr / 1000).toInt())
    private val d0 = DoubleArray(n); private val d1 = DoubleArray(n)
    private val e0 = DoubleArray(n); private val e1 = DoubleArray(n)
    private val tg = DoubleArray(n) { 1.0 }
    private var pos = 0; private var g = 1.0; private var m = 1.0; private var cnt = 0
    private val aAtk = 1 - exp(-1.0 / (atkMs / 1000 * sr)); private val aRel = 1 - exp(-1.0 / (relMs / 1000 * sr))
    var o0 = 0.0; var o1 = 0.0; var q0 = 0.0; var q1 = 0.0; var minG = 1.0

    fun step(x0: Double, x1: Double, s0: Double, s1: Double, peak: Double, thr: Double): Double {
        val target = if (peak > thr) thr / peak else 1.0
        o0 = d0[pos]; o1 = d1[pos]; q0 = e0[pos]; q1 = e1[pos]
        if ((cnt++ and 7) == 0) { var mm = 1.0; for (k in 0 until n) if (tg[k] < mm) mm = tg[k]; m = mm }
        val mm = min(m, target)
        d0[pos] = x0; d1[pos] = x1; e0[pos] = s0; e1[pos] = s1; tg[pos] = target
        pos = (pos + 1) % n
        g += (mm - g) * (if (mm < g) aAtk else aRel)
        if (g < minG) minG = g
        return g
    }
}

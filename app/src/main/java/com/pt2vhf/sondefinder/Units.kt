package com.pt2vhf.sondefinder

/** Display units chosen in Settings (altitude m/ft, temperature °C/°F). Data stays metric. */
object Units {
    const val FT_PER_M = 3.28084
    fun alt(m: Double, feet: Boolean): Double = if (feet) m * FT_PER_M else m
    fun temp(c: Double, fahrenheit: Boolean): Double = if (fahrenheit) c * 9.0 / 5.0 + 32.0 else c
}

package com.arix.screenshare

/** Capture/encode limits. longSide = max(width, height) of the captured frame in pixels. */
enum class Quality(val label: String, val longSide: Int, val fps: Int, val maxBitrateBps: Int) {
    LOW("Low (saves data)", 960, 15, 1_500_000),
    BALANCED("Balanced", 1280, 30, 3_000_000),
    HIGH("High (best on LAN)", 1920, 30, 6_000_000);

    companion object {
        fun fromName(name: String?): Quality = values().firstOrNull { it.name == name } ?: BALANCED
    }
}

/** Pure helpers (no Android classes) so they can be unit-tested on a JVM. */
object CaptureMath {
    /** Scales (w,h) down so the longer side is <= longSide, keeping aspect ratio; both results even and >= 2. */
    fun scaledSize(w: Int, h: Int, longSide: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        val scale = if (long > longSide) longSide.toDouble() / long else 1.0
        fun even(v: Double): Int = maxOf(2, (v.toInt() / 2) * 2)
        return even(w * scale) to even(h * scale)
    }
}

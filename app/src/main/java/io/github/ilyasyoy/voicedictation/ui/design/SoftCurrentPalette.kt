package io.github.ilyasyoy.voicedictation.ui.design

/** Shared by Compose screens and the native accessibility overlay. No dynamic provider colors. */
internal object SoftCurrentPalette {
    const val Paper = 0xFFF5F1FA.toInt()
    const val Surface = 0xFFFCF9FF.toInt()
    const val Plum = 0xFF30293F.toInt()
    const val Muted = 0xFF746A82.toInt()
    const val Accent = 0xFF7856B1.toInt()
    const val Outline = 0xFFDAD0E7.toInt()
    const val Night = 0xFF211D2B.toInt()
    const val NightSurface = 0xFF302A3D.toInt()
    const val NightText = 0xFFF2EAFB.toInt()
    val Layers = intArrayOf(0xFFA88BCF.toInt(), 0xFFB6A0D8.toInt(), 0xFFC2B0E0.toInt(),
        0xFFCCBEE6.toInt(), 0xFFD6CBEA.toInt(), 0xFFE1D8EF.toInt())
}

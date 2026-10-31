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
    val Layers = intArrayOf(0xFF6C38B7.toInt(), 0xFF8753C9.toInt(), 0xFF9768D3.toInt(),
        0xFFA67EDB.toInt(), 0xFFB18EDF.toInt(), 0xFFBF9DE6.toInt())
    val NeutralLayers = intArrayOf(0xFF8A8793.toInt(),0xFF9F9CA8.toInt(),0xFFAAA7B1.toInt(),
        0xFFB5B2BC.toInt(),0xFFC1BEC7.toInt(),0xFFCCC9D1.toInt())
    val ErrorLayers = intArrayOf(0xFFBA1D37.toInt(),0xFFCE2945.toInt(),0xFFDE3851.toInt(),
        0xFFE84A61.toInt(),0xFFEE6377.toInt(),0xFFF17C8B.toInt())
}

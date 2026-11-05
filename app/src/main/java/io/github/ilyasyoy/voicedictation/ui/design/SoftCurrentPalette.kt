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
    val Layers = intArrayOf(0xFF9459E0.toInt(), 0xFF8744D6.toInt(), 0xFF843FE0.toInt(),
        0xFF934FE9.toInt(), 0xFFA060EF.toInt(), 0xFFAD73F3.toInt())
    val NeutralLayers = intArrayOf(0xFF8A8793.toInt(),0xFF9F9CA8.toInt(),0xFFAAA7B1.toInt(),
        0xFFB5B2BC.toInt(),0xFFC1BEC7.toInt(),0xFFCCC9D1.toInt())
    val ErrorLayers = intArrayOf(0xFFD54054.toInt(),0xFFC82D46.toInt(),0xFFDE3851.toInt(),
        0xFFE84A61.toInt(),0xFFEE6377.toInt(),0xFFF17C8B.toInt())
    // Yellow/amber stays far from the red error family, including the large central fill.
    val ProcessingLayers = intArrayOf(0xFFA56B00.toInt(),0xFFB97D00.toInt(),0xFFCA9009.toInt(),
        0xFFD99F18.toInt(),0xFFE5AE2C.toInt(),0xFFF0BD45.toInt())

    fun layers(visual: CurrentVisual): IntArray = when (visual) {
        CurrentVisual.RECORDING -> Layers
        CurrentVisual.CONNECTING, CurrentVisual.PROCESSING -> ProcessingLayers
        CurrentVisual.ERROR -> ErrorLayers
        else -> NeutralLayers
    }
    fun ink(visual: CurrentVisual): Int = when (visual) {
        CurrentVisual.RECORDING -> 0xFF281043.toInt()
        CurrentVisual.CONNECTING, CurrentVisual.PROCESSING -> 0xFF493000.toInt()
        CurrentVisual.ERROR -> 0xFF4B0713.toInt()
        else -> 0xFF403B49.toInt()
    }
}

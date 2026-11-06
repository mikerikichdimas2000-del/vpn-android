package com.dryakhlitsyn.vpn

import android.content.Context
import android.content.res.Configuration

/** Colour set resolved from the current light/dark configuration. */
data class Palette(
    val dark: Boolean,
    val background: Int,
    val surface: Int,
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    val divider: Int,
    val cardBorder: Int,
    val accent: Int,
    val chipBackground: Int,
    val chipBorder: Int,
    val danger: Int,
    val switchOn: Int,
    val switchOff: Int,
    val switchKnob: Int,
)

object Palettes {
    fun resolve(context: Context): Palette {
        val colors = context.resources
        val dark = (colors.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return Palette(
            dark = dark,
            background = colors.getColor(R.color.bg),
            surface = colors.getColor(R.color.surface),
            primary = colors.getColor(R.color.text_primary),
            secondary = colors.getColor(R.color.text_secondary),
            tertiary = colors.getColor(R.color.text_tertiary),
            divider = colors.getColor(R.color.divider),
            cardBorder = colors.getColor(R.color.card_border),
            accent = colors.getColor(R.color.accent),
            chipBackground = colors.getColor(R.color.chip_bg),
            chipBorder = colors.getColor(R.color.chip_border),
            danger = colors.getColor(R.color.danger),
            switchOn = if (dark) 0xFFF2F3F5.toInt() else 0xFF0A0A0C.toInt(),
            switchOff = if (dark) 0xFF26282E.toInt() else 0xFFDCDDE2.toInt(),
            switchKnob = if (dark) 0xFF0A0A0C.toInt() else 0xFFFFFFFF.toInt(),
        )
    }
}

package com.mooncc.teamspeak9.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * TeamSpeak9 palette. Modelled on the official TeamSpeak desktop look:
 * near-black chrome, slightly lifted panels, a single blue accent, and
 * saturated status colours for talking / muted / away badges.
 */

val TsBlue = Color(0xFF3E8FF7)
val TsBlueDark = Color(0xFF1F63C4)
val TsBlueLight = Color(0xFF8FBEFF)

/** Window background — the darkest layer. */
val TsBackground = Color(0xFF0B0F14)

/** Toolbars, tab strips and the channel tree pane. */
val TsChrome = Color(0xFF121820)

/** Cards, dialogs, list panels. */
val TsSurface = Color(0xFF161D26)

/** Hovered / selected rows, input fields, bottom control bar. */
val TsSurfaceVariant = Color(0xFF1D2631)

/** Highest elevation: menus, popovers. */
val TsSurfaceHigh = Color(0xFF232E3B)

val TsOutline = Color(0xFF2C3846)
val TsOutlineStrong = Color(0xFF3C4A5C)

val TsOnSurface = Color(0xFFE8EDF4)
val TsOnSurfaceVariant = Color(0xFF9BA9BC)
val TsOnSurfaceMuted = Color(0xFF6E7C8E)

val TsError = Color(0xFFE5484D)
val TsSuccess = Color(0xFF3DD68C)
val TsWarning = Color(0xFFF5A524)

val TsTalking = Color(0xFF3DD68C)
val TsMuted = Color(0xFFE5484D)
val TsAway = Color(0xFFF5A524)

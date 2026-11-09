package app.wifigram.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00A86B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8F2DD),
    onSecondaryContainer = Color(0xFF003824),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF111418),
    surface = Color.White,
    onSurface = Color(0xFF111418),
    surfaceVariant = Color(0xFFE8ECF3),
    onSurfaceVariant = Color(0xFF5A6472),
    error = Color(0xFFD93025),
    onError = Color.White,
    outline = Color(0xFFD3D9E3)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF06294F),
    primaryContainer = Color(0xFF17457E),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF4FD8A0),
    onSecondary = Color(0xFF003824),
    secondaryContainer = Color(0xFF00513A),
    onSecondaryContainer = Color(0xFFC8F2DD),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE4E8EE),
    surface = Color(0xFF151A21),
    onSurface = Color(0xFFE4E8EE),
    surfaceVariant = Color(0xFF232A34),
    onSurfaceVariant = Color(0xFFA6B0BD),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0906),
    outline = Color(0xFF39414D)
)

/** The green of the call button, as in the usual phone app. */
val CallGreen = Color(0xFF2E9E5B)
val CallRed = Color(0xFFD93025)

/** The colours of the words in the chat, as in Telegram. */
data class ChatColors(
    val mine: Color,
    val mineText: Color,
    val theirs: Color,
    val theirsText: Color
)

@Composable
fun chatColors(): ChatColors = if (isSystemInDarkTheme()) {
    ChatColors(
        mine = Color(0xFF2B5278),
        mineText = Color(0xFFE9F1FA),
        theirs = Color(0xFF1C2430),
        theirsText = Color(0xFFE4E8EE)
    )
} else {
    ChatColors(
        mine = Color(0xFFDCF0FF),
        mineText = Color(0xFF111418),
        theirs = Color(0xFFFFFFFF),
        theirsText = Color(0xFF111418)
    )
}

@Composable
fun WifigramTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

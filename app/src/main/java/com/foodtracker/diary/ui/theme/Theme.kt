package com.foodtracker.diary.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val PastelColors = lightColorScheme(
    primary = Color(0xFF6B9360),
    onPrimary = Color.White,
    secondary = Color(0xFFE66F9D),
    tertiary = Color(0xFF4AA7C5),
    background = Color(0xFFFFF7FB),
    surface = Color(0xFFFFFFFC),
    surfaceVariant = Color(0xFFF1E6FF),
    primaryContainer = Color(0xFFDFF3D8),
    secondaryContainer = Color(0xFFFFE2EE),
    tertiaryContainer = Color(0xFFDDF5FF),
    onBackground = Color(0xFF302B31),
    onSurface = Color(0xFF302B31),
)

private val BerryColors = lightColorScheme(
    primary = Color(0xFF9B4D6D),
    onPrimary = Color.White,
    secondary = Color(0xFF5D7F95),
    tertiary = Color(0xFFE6A64F),
    background = Color(0xFFFFF4F7),
    surface = Color(0xFFFFFCFB),
    surfaceVariant = Color(0xFFF8DDE6),
    primaryContainer = Color(0xFFF5D7E2),
    secondaryContainer = Color(0xFFDCECF3),
    tertiaryContainer = Color(0xFFFFE7BC),
    onBackground = Color(0xFF30282B),
    onSurface = Color(0xFF30282B),
)

private val MintColors = lightColorScheme(
    primary = Color(0xFF4F8F7B),
    onPrimary = Color.White,
    secondary = Color(0xFFD8797C),
    tertiary = Color(0xFF7C71B8),
    background = Color(0xFFF5FFF9),
    surface = Color(0xFFFFFCF8),
    surfaceVariant = Color(0xFFDDF2E8),
    primaryContainer = Color(0xFFD8F1E6),
    secondaryContainer = Color(0xFFFFE0DD),
    tertiaryContainer = Color(0xFFE8E4FF),
    onBackground = Color(0xFF28302B),
    onSurface = Color(0xFF28302B),
)

private val SunnyColors = lightColorScheme(
    primary = Color(0xFF927333),
    onPrimary = Color.White,
    secondary = Color(0xFFDD7774),
    tertiary = Color(0xFF5E8A6A),
    background = Color(0xFFFFFAEA),
    surface = Color(0xFFFFFCF4),
    surfaceVariant = Color(0xFFFFEAB8),
    primaryContainer = Color(0xFFFFE5A5),
    secondaryContainer = Color(0xFFFFDFDC),
    tertiaryContainer = Color(0xFFDFF0D7),
    onBackground = Color(0xFF332D23),
    onSurface = Color(0xFF332D23),
)

@Composable
fun FoodDiaryTheme(themeId: String = "pastel", content: @Composable () -> Unit) {
    val colors = when (themeId) {
        "berry" -> BerryColors
        "mint" -> MintColors
        "sunny" -> SunnyColors
        else -> PastelColors
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
        ),
        typography = MaterialTheme.typography,
        content = content,
    )
}

package com.foodtracker.diary.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DiaryColors = lightColorScheme(
    primary = Color(0xFF6F9A63),
    onPrimary = Color.White,
    secondary = Color(0xFFE87A78),
    tertiary = Color(0xFFF4B95D),
    background = Color(0xFFFFF7EE),
    surface = Color(0xFFFFFCF8),
    surfaceVariant = Color(0xFFF8E8D7),
    primaryContainer = Color(0xFFE7F0D5),
    secondaryContainer = Color(0xFFFFDFDC),
    tertiaryContainer = Color(0xFFFFE6B8),
    onBackground = Color(0xFF322D2A),
    onSurface = Color(0xFF322D2A),
)

@Composable
fun FoodDiaryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DiaryColors,
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

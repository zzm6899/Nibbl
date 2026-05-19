package com.foodtracker.diary.data

data class FoodLog(
    val id: String,
    val timestamp: Long,
    val imagePath: String,
    val originalImagePath: String,
    val title: String,
    val category: DrinkCategory,
    val caffeineMg: Int?,
    val cafe: String,
    val locationName: String,
    val latitude: Double?,
    val longitude: Double?,
    val friendNames: List<String>,
    val sticker: String = "",
    val calories: Int? = null,
    val priceCents: Int? = null,
    val rating: Int? = null,
    val orderDetails: String = "",
    val isWishlist: Boolean = false,
    val reaction: String = "",
    val favorite: Boolean = false,
)

data class FoodLogDaySummary(
    val totalEntries: Int,
    val totalCaffeineMg: Int,
    val totalCalories: Int,
    val totalSpendCents: Int,
    val categoryCounts: Map<DrinkCategory, Int>,
    val cafeCount: Int,
    val friendCount: Int,
    val favoriteCount: Int,
    val wishlistCount: Int,
)

class DrinkCategory(
    val id: String,
    val label: String,
    val colorArgb: Int,
    val builtIn: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is DrinkCategory && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = label

    companion object {
        val Matcha = DrinkCategory("matcha", "Matcha", 0xFFDCECC7.toInt(), true)
        val Coffee = DrinkCategory("coffee", "Coffee", 0xFFEAD4C2.toInt(), true)
        val Tea = DrinkCategory("tea", "Tea", 0xFFFFE6B8.toInt(), true)
        val Drink = DrinkCategory("drink", "Drink", 0xFFD8EFF1.toInt(), true)
        val Snack = DrinkCategory("snack", "Snack", 0xFFFFDFDC.toInt(), true)
        val Boba = DrinkCategory("boba", "Boba", 0xFFE9D7FF.toInt(), true)
        val Smoothie = DrinkCategory("smoothie", "Smoothie", 0xFFD8F5E5.toInt(), true)
        val Meal = DrinkCategory("meal", "Meal", 0xFFFFE2C7.toInt(), true)
        val Dessert = DrinkCategory("dessert", "Dessert", 0xFFFFD7EB.toInt(), true)

        val defaults = listOf(Matcha, Coffee, Tea, Drink, Snack, Boba, Smoothie, Meal, Dessert)

        fun custom(label: String, colorArgb: Int): DrinkCategory {
            val cleanLabel = label.trim().ifBlank { "Other" }.take(28)
            return DrinkCategory(cleanLabel.toCategoryId(), cleanLabel, colorArgb, false)
        }

        fun find(raw: String): DrinkCategory? {
            val normalized = raw.trim()
            return defaults.firstOrNull {
                it.id.equals(normalized, ignoreCase = true) || it.label.equals(normalized, ignoreCase = true)
            }
        }
    }
}

fun String.toCategoryId(): String {
    val normalized = trim()
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
    return normalized.ifBlank { "custom" }.take(36)
}

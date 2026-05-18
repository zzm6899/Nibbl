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
)

data class FoodLogDaySummary(
    val totalEntries: Int,
    val totalCaffeineMg: Int,
    val categoryCounts: Map<DrinkCategory, Int>,
    val cafeCount: Int,
    val friendCount: Int,
)

enum class DrinkCategory(val label: String) {
    Matcha("Matcha"),
    Coffee("Coffee"),
    Tea("Tea"),
    Drink("Drink"),
    Snack("Snack"),
}

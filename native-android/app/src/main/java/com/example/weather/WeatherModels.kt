package com.example.weather

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class CityPlace(
    val name: String,
    val region: String,
    val country: String,
    val latitude: Double,
    val longitude: Double
) {
    val cacheKey: String
        get() = String.format(Locale.US, "%.4f_%.4f", latitude, longitude)

    val subtitle: String
        get() = listOf(region, country).filter { it.isNotBlank() }.distinct().joinToString("، ")

    companion object {
        fun piranshahr() = CityPlace("پیرانشهر", "آذربایجان غربی", "ایران", 36.701, 45.141)
    }
}

data class HourForecast(
    val time: String,
    val temperature: Double,
    val rainProbability: Int,
    val weatherCode: Int
)

data class DayForecast(
    val date: String,
    val minTemperature: Double,
    val maxTemperature: Double,
    val rainProbability: Int,
    val weatherCode: Int,
    val uvIndex: Double
) {
    fun label(): String = try {
        LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("fa", "IR"))
    } catch (_: Exception) {
        date
    }
}

data class WeatherSnapshot(
    val city: CityPlace,
    val temperature: Double,
    val feelsLike: Double,
    val humidity: Int,
    val windSpeed: Double,
    val pressure: Double,
    val weatherCode: Int,
    val airQuality: Int?,
    val hourly: List<HourForecast>,
    val daily: List<DayForecast>,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    val nextThreeHourRain: Int
        get() = hourly.take(3).maxOfOrNull { it.rainProbability } ?: 0

    fun toJson(): JSONObject {
        val hourlyArray = JSONArray()
        hourly.forEach {
            hourlyArray.put(JSONObject()
                .put("time", it.time)
                .put("temperature", it.temperature)
                .put("rainProbability", it.rainProbability)
                .put("weatherCode", it.weatherCode))
        }
        val dailyArray = JSONArray()
        daily.forEach {
            dailyArray.put(JSONObject()
                .put("date", it.date)
                .put("minTemperature", it.minTemperature)
                .put("maxTemperature", it.maxTemperature)
                .put("rainProbability", it.rainProbability)
                .put("weatherCode", it.weatherCode)
                .put("uvIndex", it.uvIndex))
        }
        return JSONObject()
            .put("city", city.name)
            .put("region", city.region)
            .put("country", city.country)
            .put("latitude", city.latitude)
            .put("longitude", city.longitude)
            .put("temperature", temperature)
            .put("feelsLike", feelsLike)
            .put("humidity", humidity)
            .put("windSpeed", windSpeed)
            .put("pressure", pressure)
            .put("weatherCode", weatherCode)
            .put("airQuality", airQuality ?: JSONObject.NULL)
            .put("hourly", hourlyArray)
            .put("daily", dailyArray)
            .put("fetchedAt", fetchedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): WeatherSnapshot {
            val city = CityPlace(
                name = json.optString("city", "پیرانشهر"),
                region = json.optString("region"),
                country = json.optString("country"),
                latitude = json.optDouble("latitude", 36.701),
                longitude = json.optDouble("longitude", 45.141)
            )
            val hourlyJson = json.optJSONArray("hourly") ?: JSONArray()
            val hourly = (0 until hourlyJson.length()).mapNotNull { index ->
                val item = hourlyJson.optJSONObject(index) ?: return@mapNotNull null
                HourForecast(
                    item.optString("time"),
                    item.optDouble("temperature", 0.0),
                    item.optInt("rainProbability"),
                    item.optInt("weatherCode")
                )
            }
            val dailyJson = json.optJSONArray("daily") ?: JSONArray()
            val daily = (0 until dailyJson.length()).mapNotNull { index ->
                val item = dailyJson.optJSONObject(index) ?: return@mapNotNull null
                DayForecast(
                    item.optString("date"),
                    item.optDouble("minTemperature", 0.0),
                    item.optDouble("maxTemperature", 0.0),
                    item.optInt("rainProbability"),
                    item.optInt("weatherCode"),
                    item.optDouble("uvIndex", 0.0)
                )
            }
            val aqiValue = json.opt("airQuality")
            return WeatherSnapshot(
                city = city,
                temperature = json.optDouble("temperature"),
                feelsLike = json.optDouble("feelsLike"),
                humidity = json.optInt("humidity"),
                windSpeed = json.optDouble("windSpeed"),
                pressure = json.optDouble("pressure"),
                weatherCode = json.optInt("weatherCode"),
                airQuality = if (aqiValue == null || aqiValue == JSONObject.NULL) null else (aqiValue as? Number)?.toInt(),
                hourly = hourly,
                daily = daily,
                fetchedAt = json.optLong("fetchedAt", System.currentTimeMillis())
            )
        }
    }
}

fun weatherIcon(code: Int): String = when (code) {
    0 -> "☀️"
    1 -> "🌤️"
    2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 80 -> "🌦️"
    61, 63, 65, 81 -> "🌧️"
    71, 73 -> "🌨️"
    75, 77 -> "❄️"
    82, 95, 96, 99 -> "⛈️"
    else -> "🌤️"
}

fun weatherDescription(code: Int): String = when (code) {
    0 -> "آسمان صاف"
    1 -> "عمدتاً صاف"
    2 -> "نیمه‌ابری"
    3 -> "ابری"
    45 -> "مه"
    48 -> "مه یخ‌زده"
    51 -> "نم‌نم باران سبک"
    53 -> "نم‌نم باران"
    55 -> "نم‌نم شدید"
    61 -> "باران سبک"
    63 -> "باران"
    65 -> "باران شدید"
    71 -> "برف سبک"
    73 -> "برف"
    75 -> "برف شدید"
    80 -> "رگبار سبک"
    81 -> "رگبار"
    82 -> "رگبار شدید"
    95 -> "رعدوبرق"
    96, 99 -> "رعدوبرق و تگرگ"
    else -> "وضعیت جوی نامشخص"
}

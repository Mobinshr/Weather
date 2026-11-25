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
        fun piranshahr() = CityPlace("پیرانشهر", "آذربایجان غربی", "ایران", 36.6959, 45.1413)
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
    val uvIndex: Double,
    val sunrise: String,
    val sunset: String
) {
    fun label(): String = try {
        LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.FULL, Locale("fa", "IR"))
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
    val isDay: Boolean,
    val currentTime: String,
    val airQuality: Int?,
    val hourly: List<HourForecast>,
    val daily: List<DayForecast>,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    val nextSixHourRain: Pair<Int, String?>
        get() {
            val peak = nextSixHours().maxByOrNull { it.rainProbability }
            return (peak?.rainProbability ?: 0) to peak?.time
        }

    fun next24Hours(): List<HourForecast> {
        if (hourly.isEmpty()) return emptyList()
        val hourKey = currentTime.take(13)
        val start = hourly.indexOfFirst { it.time.take(13) >= hourKey }.let { if (it < 0) 0 else it }
        return hourly.drop(start).take(24)
    }

    fun nextSixHours(): List<HourForecast> = next24Hours().take(6)

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
                .put("uvIndex", it.uvIndex)
                .put("sunrise", it.sunrise)
                .put("sunset", it.sunset))
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
            .put("isDay", isDay)
            .put("currentTime", currentTime)
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
                latitude = json.optDouble("latitude", 36.6959),
                longitude = json.optDouble("longitude", 45.1413)
            )
            val hourlyJson = json.optJSONArray("hourly") ?: JSONArray()
            val hourly = (0 until hourlyJson.length()).mapNotNull { index ->
                val item = hourlyJson.optJSONObject(index) ?: return@mapNotNull null
                HourForecast(
                    item.optString("time"),
                    item.optDouble("temperature", 0.0),
                    item.optInt("rainProbability").coerceIn(0, 100),
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
                    item.optInt("rainProbability").coerceIn(0, 100),
                    item.optInt("weatherCode"),
                    item.optDouble("uvIndex", 0.0),
                    item.optString("sunrise"),
                    item.optString("sunset")
                )
            }
            val aqiValue = json.opt("airQuality")
            return WeatherSnapshot(
                city = city,
                temperature = json.optDouble("temperature"),
                feelsLike = json.optDouble("feelsLike", json.optDouble("temperature")),
                humidity = json.optInt("humidity"),
                windSpeed = json.optDouble("windSpeed"),
                pressure = json.optDouble("pressure"),
                weatherCode = json.optInt("weatherCode"),
                isDay = json.optBoolean("isDay", true),
                currentTime = json.optString("currentTime"),
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
    51, 53, 55 -> "🌦️"
    56, 57, 61, 63, 65, 66, 67 -> "🌧️"
    71, 73 -> "🌨️"
    75, 77, 85, 86 -> "❄️"
    80 -> "🌦️"
    81, 82 -> "🌧️"
    95, 96, 99 -> "⛈️"
    else -> "🌡️"
}

fun weatherDescription(code: Int): String = when (code) {
    0 -> "آسمان صاف"
    1 -> "کم‌ابر"
    2 -> "نیمه‌ابری"
    3 -> "ابری"
    45 -> "مه‌آلود"
    48 -> "مه یخی"
    51, 53, 55 -> "نم‌نم باران"
    56, 57 -> "نم‌نم یخ‌زده"
    61 -> "باران ملایم"
    63 -> "باران"
    65 -> "باران شدید"
    66, 67 -> "باران یخ‌زده"
    71 -> "برف ملایم"
    73 -> "برف"
    75 -> "برف سنگین"
    77 -> "دانه‌برف"
    80 -> "رگبار"
    81 -> "رگبار"
    82 -> "رگبار شدید"
    85, 86 -> "رگبار برف"
    95 -> "رعدوبرق"
    96, 99 -> "رعدوبرق و تگرگ"
    else -> "نامشخص"
}

data class WeatherPalette(val top: androidx.compose.ui.graphics.Color, val bottom: androidx.compose.ui.graphics.Color)

fun weatherPalette(code: Int, isDay: Boolean): WeatherPalette {
    val kind = when (code) {
        0, 1 -> "clear"
        2 -> "partly"
        3, 45, 48 -> "cloud"
        in 51..67, in 80..82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        else -> "storm"
    }
    val values = when (kind) {
        "clear" -> if (isDay) 0xFF2A6FD6 to 0xFF79B8F5 else 0xFF0B1230 to 0xFF273A73
        "partly" -> if (isDay) 0xFF3A73C4 to 0xFF8FB4DE else 0xFF121A3A to 0xFF33447A
        "cloud" -> if (isDay) 0xFF6D7B8F to 0xFFA7B2C1 else 0xFF1A202E to 0xFF3A4457
        "rain" -> if (isDay) 0xFF445A7A to 0xFF8196B4 else 0xFF0F1626 to 0xFF2A3855
        "snow" -> if (isDay) 0xFF6E88AD to 0xFFC5D6EA else 0xFF1C2744 to 0xFF47598A
        else -> if (isDay) 0xFF2B2C4A to 0xFF5D5683 else 0xFF090A1A to 0xFF292447
    }
    return WeatherPalette(
        androidx.compose.ui.graphics.Color(values.first),
        androidx.compose.ui.graphics.Color(values.second)
    )
}

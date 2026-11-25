package com.example.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

object WeatherApi {
    suspend fun fetch(city: CityPlace): WeatherSnapshot = withContext(Dispatchers.IO) {
        val forecastUrl = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${city.latitude}&longitude=${city.longitude}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,pressure_msl" +
            "&hourly=temperature_2m,precipitation_probability,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7"
        val json = requestJson(forecastUrl)
        val current = json.getJSONObject("current")
        val hourlyJson = json.getJSONObject("hourly")
        val dailyJson = json.getJSONObject("daily")
        val aqi = runCatching {
            requestJson(
                "https://air-quality-api.open-meteo.com/v1/air-quality" +
                    "?latitude=${city.latitude}&longitude=${city.longitude}&current=european_aqi&timezone=auto"
            ).getJSONObject("current").optDouble("european_aqi").takeIf { it.isFinite() }?.roundToInt()
        }.getOrNull()

        val times = hourlyJson.optJSONArray("time") ?: JSONArray()
        val temperatures = hourlyJson.optJSONArray("temperature_2m") ?: JSONArray()
        val probabilities = hourlyJson.optJSONArray("precipitation_probability") ?: JSONArray()
        val codes = hourlyJson.optJSONArray("weather_code") ?: JSONArray()
        val hourlySize = minOf(times.length(), temperatures.length(), probabilities.length(), codes.length())
        val hourly = (0 until hourlySize).map { index ->
            HourForecast(
                times.optString(index),
                temperatures.optDouble(index, 0.0),
                probabilities.optDouble(index, 0.0).roundToInt().coerceIn(0, 100),
                codes.optInt(index)
            )
        }

        val dayTimes = dailyJson.optJSONArray("time") ?: JSONArray()
        val minTemps = dailyJson.optJSONArray("temperature_2m_min") ?: JSONArray()
        val maxTemps = dailyJson.optJSONArray("temperature_2m_max") ?: JSONArray()
        val dayProbabilities = dailyJson.optJSONArray("precipitation_probability_max") ?: JSONArray()
        val dayCodes = dailyJson.optJSONArray("weather_code") ?: JSONArray()
        val uv = dailyJson.optJSONArray("uv_index_max") ?: JSONArray()
        val dailySize = minOf(dayTimes.length(), minTemps.length(), maxTemps.length(), dayCodes.length())
        val daily = (0 until dailySize).map { index ->
            DayForecast(
                dayTimes.optString(index),
                minTemps.optDouble(index, 0.0),
                maxTemps.optDouble(index, 0.0),
                dayProbabilities.optDouble(index, 0.0).roundToInt().coerceIn(0, 100),
                dayCodes.optInt(index),
                uv.optDouble(index, 0.0)
            )
        }

        val snapshot = WeatherSnapshot(
            city = city,
            temperature = current.getDouble("temperature_2m"),
            feelsLike = current.optDouble("apparent_temperature", current.getDouble("temperature_2m")),
            humidity = current.optDouble("relative_humidity_2m").roundToInt().coerceIn(0, 100),
            windSpeed = current.optDouble("wind_speed_10m"),
            pressure = current.optDouble("pressure_msl"),
            weatherCode = current.optInt("weather_code"),
            airQuality = aqi,
            hourly = hourly,
            daily = daily
        )
        require(snapshot.hourly.isNotEmpty() && snapshot.daily.isNotEmpty()) { "دادهٔ پیش‌بینی ناقص است" }
        snapshot
    }

    suspend fun searchCities(query: String): List<CityPlace> = withContext(Dispatchers.IO) {
        val normalized = query.trim().take(80)
        if (normalized.length < 2) return@withContext emptyList()
        val languages = listOf("fa", "en")
        val merged = LinkedHashMap<String, CityPlace>()
        var hadSuccess = false
        languages.forEach { language ->
            runCatching {
                val encoded = URLEncoder.encode(normalized, "UTF-8")
                requestJson("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=100&language=$language&format=json")
            }.onSuccess { response ->
                hadSuccess = true
                val results = response.optJSONArray("results") ?: JSONArray()
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val lat = item.optDouble("latitude", Double.NaN)
                    val lon = item.optDouble("longitude", Double.NaN)
                    val name = item.optString("name").trim()
                    if (name.isBlank() || !lat.isFinite() || !lon.isFinite()) continue
                    val city = CityPlace(
                        name = name,
                        region = item.optString("admin1"),
                        country = item.optString("country"),
                        latitude = lat,
                        longitude = lon
                    )
                    val key = city.cacheKey
                    val old = merged[key]
                    if (old == null || (old.name.none { it.code in 0x0600..0x06FF } && name.any { it.code in 0x0600..0x06FF })) {
                        merged[key] = city
                    }
                }
            }
        }
        if (!hadSuccess) throw IOException("جستجوی شهرها در دسترس نیست")
        merged.values.take(100)
    }

    suspend fun reverseGeocode(latitude: Double, longitude: Double): CityPlace = withContext(Dispatchers.IO) {
        val url = "https://api.bigdatacloud.net/data/reverse-geocode-client" +
            "?latitude=$latitude&longitude=$longitude&localityLanguage=fa"
        val result = requestJson(url)
        val name = sequenceOf(
            result.optString("city"),
            result.optString("locality"),
            result.optString("principalSubdivision")
        ).firstOrNull { it.isNotBlank() } ?: "موقعیت من"
        CityPlace(
            name = name,
            region = result.optString("principalSubdivision"),
            country = result.optString("countryName"),
            latitude = latitude,
            longitude = longitude
        )
    }

    private fun requestJson(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "QandilNative/3.6.0")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            if (status !in 200..299) throw IOException("HTTP $status")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}

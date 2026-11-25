package com.example.weather

import android.content.Context
import org.json.JSONObject

class WeatherRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("qandil_native", Context.MODE_PRIVATE)

    fun loadSavedCity(): CityPlace {
        val saved = preferences.getString("last_city", null) ?: return CityPlace.piranshahr()
        return runCatching {
            val json = JSONObject(saved)
            CityPlace(
                json.optString("name", "پیرانشهر"),
                json.optString("region"),
                json.optString("country"),
                json.optDouble("latitude", 36.6959),
                json.optDouble("longitude", 45.1413)
            )
        }.getOrDefault(CityPlace.piranshahr())
    }

    fun saveCity(city: CityPlace) {
        preferences.edit().putString(
            "last_city",
            JSONObject()
                .put("name", city.name)
                .put("region", city.region)
                .put("country", city.country)
                .put("latitude", city.latitude)
                .put("longitude", city.longitude)
                .toString()
        ).apply()
    }

    fun notificationsEnabled(): Boolean = preferences.getBoolean("rain_notifications", false)

    fun setNotificationsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("rain_notifications", enabled).apply()
    }

    suspend fun loadWeather(city: CityPlace, forceRefresh: Boolean = false): WeatherSnapshot {
        val cached = readCache(city)
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cached.fetchedAt <= FRESH_MS) {
            return cached
        }
        return try {
            val fresh = WeatherApi.fetch(city)
            writeCache(city, fresh)
            fresh
        } catch (error: Exception) {
            if (cached != null) cached else throw error
        }
    }

    private fun readCache(city: CityPlace): WeatherSnapshot? {
        val jsonText = preferences.getString(cachePreferenceKey(city), null) ?: return null
        val parsed = runCatching { WeatherSnapshot.fromJson(JSONObject(jsonText)) }.getOrNull() ?: return null
        val age = System.currentTimeMillis() - parsed.fetchedAt
        if (age < 0 || age > MAX_AGE_MS) {
            preferences.edit().remove(cachePreferenceKey(city)).apply()
            return null
        }
        return parsed
    }

    private fun writeCache(city: CityPlace, snapshot: WeatherSnapshot) {
        val key = cachePreferenceKey(city)
        val keys = preferences.getStringSet(CACHE_KEYS, emptySet())?.toMutableList() ?: mutableListOf()
        keys.remove(key)
        keys.add(0, key)
        val editor = preferences.edit().putString(key, snapshot.toJson().toString())
        while (keys.size > MAX_CACHED_CITIES) {
            editor.remove(keys.removeAt(keys.lastIndex))
        }
        editor.putStringSet(CACHE_KEYS, keys.toSet()).apply()
    }

    // New cache namespace: old 3.6.0 entries do not contain sunrise/sunset and is_day.
    private fun cachePreferenceKey(city: CityPlace) = "weather_v2_${city.cacheKey}"

    companion object {
        private const val CACHE_KEYS = "cache_keys_v2"
        private const val MAX_CACHED_CITIES = 12
        private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val FRESH_MS = 15L * 60L * 1000L
    }
}

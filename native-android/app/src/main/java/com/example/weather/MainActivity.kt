package com.example.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(31, 75, 138)
        window.navigationBarColor = android.graphics.Color.rgb(12, 30, 58)
        setContent { MaterialTheme { QandilWeatherApp() } }
    }
}

@Composable
private fun QandilWeatherApp() {
    val context = LocalContext.current
    val repository = remember { WeatherRepository(context) }
    val scope = rememberCoroutineScope()
    var city by remember { mutableStateOf(repository.loadSavedCity()) }
    var weather by remember { mutableStateOf<WeatherSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("در حال آماده‌سازی") }
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<CityPlace>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var notificationsEnabled by remember { mutableStateOf(repository.notificationsEnabled()) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            requestLastOrCurrentLocation(context,
                onLocation = { latitude, longitude ->
                    scope.launch {
                        status = "در حال تشخیص شهر…"
                        runCatching { WeatherApi.reverseGeocode(latitude, longitude) }
                            .onSuccess { city = it; query = ""; suggestions = emptyList() }
                            .onFailure { status = "شهر پیدا شد؛ دریافت نام شهر ممکن نشد" }
                    }
                },
                onFailure = { status = "موقعیت GPS در دسترس نیست؛ دوباره تلاش کن" }
            )
        } else {
            status = "مجوز موقعیت مکانی داده نشد"
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsEnabled = granted
        repository.setNotificationsEnabled(granted)
        status = if (granted) "هشدار بارش فعال شد" else "مجوز اعلان داده نشد"
    }

    LaunchedEffect(city.cacheKey, refreshKey) {
        loading = true
        error = null
        repository.saveCity(city)
        try {
            val loaded = repository.loadWeather(city, forceRefresh = refreshKey > 0)
            weather = loaded
            status = if (System.currentTimeMillis() - loaded.fetchedAt > 15L * 60L * 1000L) {
                "نمایش دادهٔ ذخیره‌شده"
            } else {
                "به‌روزرسانی: " + SimpleDateFormat("HH:mm", Locale("fa", "IR")).format(Date(loaded.fetchedAt))
            }
            if (notificationsEnabled && loaded.nextThreeHourRain >= 60) {
                showRainNotification(context, loaded)
            }
        } catch (e: Exception) {
            error = e.message ?: "دریافت اطلاعات آب‌وهوا ناموفق بود"
            status = "اتصال برقرار نشد"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(query) {
        val clean = query.trim()
        if (clean.length < 2) {
            suggestions = emptyList()
        } else {
            delay(350)
            suggestions = runCatching { WeatherApi.searchCities(clean) }.getOrDefault(emptyList())
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF397BC4), Color(0xFF183B70), Color(0xFF0B1D3B)))
        )
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("قندیل", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("آب‌وهوای شهرها", color = Color.White.copy(alpha = .78f), fontSize = 12.sp)
                }
                IconButton(onClick = {
                    if (notificationsEnabled) {
                        notificationsEnabled = false
                        repository.setNotificationsEnabled(false)
                        status = "هشدار بارش خاموش شد"
                    } else if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        notificationsEnabled = true
                        repository.setNotificationsEnabled(true)
                        status = "هشدار بارش فعال شد"
                        weather?.let { if (it.nextThreeHourRain >= 60) showRainNotification(context, it) }
                    }
                }) {
                    Icon(Icons.Default.Notifications, contentDescription = "اعلان بارش",
                        tint = if (notificationsEnabled) Color(0xFFFFD180) else Color.White)
                }
                IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    ) {
                        requestLastOrCurrentLocation(context,
                            onLocation = { lat, lon ->
                                scope.launch {
                                    runCatching { WeatherApi.reverseGeocode(lat, lon) }
                                        .onSuccess { city = it; query = ""; suggestions = emptyList() }
                                        .onFailure { status = "نام شهر از موقعیت پیدا نشد" }
                                }
                            },
                            onFailure = { status = "موقعیت GPS در دسترس نیست" }
                        )
                    } else {
                        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }) {
                    Icon(Icons.Default.LocationOn, contentDescription = "موقعیت من", tint = Color.White)
                }
                IconButton(onClick = { refreshKey++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "به‌روزرسانی", tint = Color.White)
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("جستجوی شهر به فارسی یا انگلیسی", color = Color.White.copy(alpha = .65f)) },
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions.Default,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White)
                )
                if (suggestions.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF183455))
                    ) {
                        LazyColumn(Modifier.height(180.dp)) {
                            items(suggestions) { result ->
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        city = result
                                        query = ""
                                        suggestions = emptyList()
                                    }.padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(result.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                    if (result.subtitle.isNotBlank()) Text(result.subtitle, color = Color(0xFFB6CBE0), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (loading && weather == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else if (weather != null) {
                val data = weather!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (data.nextThreeHourRain >= 60) {
                        item {
                            WeatherCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🌧️", fontSize = 28.sp)
                                    Column(Modifier.padding(start = 10.dp)) {
                                        Text("احتمال بارش در پیش است", color = Color.White, fontWeight = FontWeight.Bold)
                                        Text("احتمال بارش در سه ساعت آینده تا ${data.nextThreeHourRain}٪ می‌رسد.", color = Color.White.copy(alpha = .82f), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(data.city.name, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Medium)
                            if (data.city.subtitle.isNotBlank()) Text(data.city.subtitle, color = Color.White.copy(alpha = .75f), fontSize = 12.sp)
                            Text(weatherIcon(data.weatherCode), fontSize = 68.sp)
                            Text("${data.temperature.toInt()}°", color = Color.White, fontSize = 82.sp, fontWeight = FontWeight.Light)
                            Text(weatherDescription(data.weatherCode), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("احساس‌شده ${data.feelsLike.toInt()}°  ·  بیشینه ${data.daily.firstOrNull()?.maxTemperature?.toInt() ?: "—"}°  /  کمینه ${data.daily.firstOrNull()?.minTemperature?.toInt() ?: "—"}°",
                                color = Color.White.copy(alpha = .82f), fontSize = 13.sp, textAlign = TextAlign.Center)
                            Text(status, color = Color.White.copy(alpha = .65f), fontSize = 11.sp)
                        }
                    }
                    item {
                        WeatherCard {
                            Text("پیش‌بینی ساعتی", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                items(data.hourly.take(24)) { hour ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(hour.time.substringAfter('T').take(5), color = Color.White.copy(alpha = .8f), fontSize = 11.sp)
                                        Text(weatherIcon(hour.weatherCode), fontSize = 23.sp, modifier = Modifier.padding(vertical = 8.dp))
                                        Text("${hour.temperature.toInt()}°", color = Color.White, fontWeight = FontWeight.SemiBold)
                                        Text("${hour.rainProbability}٪", color = Color(0xFFB8DFFF), fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        WeatherCard {
                            Text("پیش‌بینی ۷ روز آینده", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            data.daily.forEach { day ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(day.label(), color = Color.White, modifier = Modifier.weight(1f))
                                    Text(weatherIcon(day.weatherCode), fontSize = 22.sp, modifier = Modifier.weight(.7f), textAlign = TextAlign.Center)
                                    Text("${day.rainProbability}٪", color = Color(0xFFB9DFFF), fontSize = 12.sp, modifier = Modifier.weight(.6f), textAlign = TextAlign.Center)
                                    Text("${day.minTemperature.toInt()}°", color = Color.White.copy(alpha = .65f), modifier = Modifier.weight(.6f), textAlign = TextAlign.Center)
                                    Text("${day.maxTemperature.toInt()}°", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(.6f), textAlign = TextAlign.End)
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard("رطوبت", "${data.humidity}٪", "💧", Modifier.weight(1f))
                            MetricCard("باد", "${data.windSpeed.toInt()} km/h", "💨", Modifier.weight(1f))
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard("فشار", "${data.pressure.toInt()} hPa", "🌡️", Modifier.weight(1f))
                            MetricCard("کیفیت هوا", data.airQuality?.toString() ?: "—", "🌿", Modifier.weight(1f))
                        }
                    }
                    item {
                        Text("منبع داده: Open-Meteo  ·  ${if (loading) "در حال به‌روزرسانی…" else " "}",
                            color = Color.White.copy(alpha = .55f), fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            } else if (error != null) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("دریافت اطلاعات ممکن نشد", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(error ?: "", color = Color.White.copy(alpha = .75f), textAlign = TextAlign.Center, fontSize = 12.sp)
                    TextButton(onClick = { refreshKey++ }) { Text("تلاش دوباره", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun WeatherCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .14f))
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun MetricCard(title: String, value: String, icon: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .13f))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 22.sp)
            Text(title, color = Color.White.copy(alpha = .76f), fontSize = 12.sp)
            Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}

@SuppressLint("MissingPermission")
private fun requestLastOrCurrentLocation(
    context: Context,
    onLocation: (Double, Double) -> Unit,
    onFailure: () -> Unit
) {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = manager.getProviders(true).filter {
        it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
    }
    val recent = providers.mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.time }
    if (recent != null && System.currentTimeMillis() - recent.time < 5 * 60 * 1000L) {
        onLocation(recent.latitude, recent.longitude)
        return
    }
    var completed = false
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (completed) return
            completed = true
            runCatching { manager.removeUpdates(this) }
            onLocation(location.latitude, location.longitude)
        }
        @Deprecated("Deprecated by Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    val provider = providers.firstOrNull() ?: run { onFailure(); return }
    try {
        manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
        Handler(Looper.getMainLooper()).postDelayed({
            if (!completed) {
                completed = true
                runCatching { manager.removeUpdates(listener) }
                onFailure()
            }
        }, 18_000L)
    } catch (_: Exception) {
        onFailure()
    }
}

private fun showRainNotification(context: Context, weather: WeatherSnapshot) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(
            android.app.NotificationChannel("qandil_rain", "هشدار بارش قندیل", android.app.NotificationManager.IMPORTANCE_HIGH)
        )
    }
    val notification = NotificationCompat.Builder(context, "qandil_rain")
        .setSmallIcon(R.drawable.ic_stat_weather)
        .setContentTitle("احتمال بارش در ${weather.city.name}")
        .setContentText("احتمال بارش سه ساعت آینده تا ${weather.nextThreeHourRain}٪")
        .setStyle(NotificationCompat.BigTextStyle().bigText("پیش‌بینی هواشناسی احتمال بارش را در سه ساعت آینده تا ${weather.nextThreeHourRain} درصد نشان می‌دهد."))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()
    runCatching { NotificationManagerCompat.from(context).notify(3501, notification) }
}

package com.example.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.roundToInt

private const val REFRESH_INTERVAL_MS = 15L * 60L * 1000L
private const val RAIN_ALERT_THRESHOLD = 60
private const val TODAY_RAIN_THRESHOLD = 70
private const val RAIN_WINDOW_HOURS = 6

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!showCrashReportIfAvailable { showWeatherContent() }) {
            showWeatherContent()
        }
    }

    private fun showWeatherContent() {
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
    var isOnline by remember { mutableStateOf(hasInternet(context)) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    val palette = weatherPalette(weather?.weatherCode ?: 0, weather?.isDay ?: true)

    SideEffect {
        (context as? ComponentActivity)?.window?.let { window ->
            window.statusBarColor = palette.top.toArgbInt()
            window.navigationBarColor = palette.bottom.toArgbInt()
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            requestLastOrCurrentLocation(context,
                onLocation = { lat, lon ->
                    scope.launch {
                        status = "در حال تشخیص شهر…"
                        runCatching { WeatherApi.reverseGeocode(lat, lon) }
                            .onSuccess { city = it; query = ""; suggestions = emptyList() }
                            .onFailure { status = "شهر پیدا شد؛ دریافت نام آن ممکن نشد" }
                    }
                },
                onFailure = { status = "موقعیت GPS در دسترس نیست؛ دوباره تلاش کن" }
            )
        } else status = "مجوز موقعیت مکانی داده نشد"
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsEnabled = granted
        repository.setNotificationsEnabled(granted)
        status = if (granted) "اعلان بارش فعال شد" else "مجوز اعلان داده نشد"
        if (granted) {
            scheduleRainMonitoring(context)
            weather?.let { sendRainNotifications(context, it) }
        } else {
            cancelRainMonitoring(context)
        }
    }

    LaunchedEffect(Unit) {
        if (notificationsEnabled) scheduleRainMonitoring(context)
        while (true) {
            clock = System.currentTimeMillis()
            delay(60_000)
        }
    }

    LaunchedEffect(city.cacheKey, refreshKey) {
        loading = true
        error = null
        isOnline = hasInternet(context)
        repository.saveCity(city)
        try {
            val loaded = repository.loadWeather(city, forceRefresh = refreshKey > 0)
            weather = loaded
            val age = System.currentTimeMillis() - loaded.fetchedAt
            status = when {
                !hasInternet(context) -> "نمایش اطلاعات ذخیره‌شده · آفلاین"
                age > REFRESH_INTERVAL_MS -> "نمایش آخرین اطلاعات ذخیره‌شده"
                else -> "آخرین دریافت: " + SimpleDateFormat("HH:mm", Locale("fa", "IR")).format(Date(loaded.fetchedAt))
            }
            if (notificationsEnabled) sendRainNotifications(context, loaded)
        } catch (exception: Exception) {
            error = exception.message ?: "دریافت اطلاعات آب‌وهوا ناموفق بود"
            status = "اتصال برقرار نشد"
        } finally {
            loading = false
            isOnline = hasInternet(context)
        }
    }

    LaunchedEffect(weather?.fetchedAt) {
        val stamp = weather?.fetchedAt ?: return@LaunchedEffect
        val wait = (stamp + REFRESH_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(1_000L)
        delay(wait)
        refreshKey++
    }

    LaunchedEffect(query) {
        val clean = query.trim()
        if (clean.length < 2) suggestions = emptyList()
        else {
            delay(350)
            suggestions = runCatching { WeatherApi.searchCities(clean) }.getOrDefault(emptyList())
        }
    }

    val data = weather
    val rainInfo = data?.nextSixHourRain
    val nextRainProbability = rainInfo?.first ?: 0
    val nextRainTime = rainInfo?.second
    val todayRain = data?.daily?.firstOrNull()?.rainProbability ?: 0
    val remainingMs = data?.let { (it.fetchedAt + REFRESH_INTERVAL_MS - clock).coerceAtLeast(0L) } ?: 0L

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(palette.top, palette.bottom)))) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp).padding(top = 12.dp, bottom = 34.dp)
        ) {
            SearchToolbar(
                query, { query = it }, isOnline, notificationsEnabled,
                onLocation = {
                    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (fine || coarse) {
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
                    } else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                onNotifications = {
                    if (notificationsEnabled) {
                        notificationsEnabled = false
                        repository.setNotificationsEnabled(false)
                        cancelRainMonitoring(context)
                        status = "اعلان‌ها خاموش شد"
                    } else if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else {
                        notificationsEnabled = true
                        repository.setNotificationsEnabled(true)
                        scheduleRainMonitoring(context)
                        status = "اعلان بارش فعال شد"
                        data?.let { sendRainNotifications(context, it) }
                    }
                },
                onRefresh = { refreshKey++ },
                loading = loading
            )

            if (suggestions.isNotEmpty()) {
                SearchSuggestions(suggestions) {
                    city = it
                    query = ""
                    suggestions = emptyList()
                }
            }

            if (data != null && nextRainProbability >= RAIN_ALERT_THRESHOLD) {
                RainBanner(data.city.name, nextRainProbability, nextRainTime?.let { formatTime(it) } ?: "—")
            }
            if (data != null && notificationsEnabled) {
                NotificationMonitorBar(nextRainProbability, nextRainTime?.let { formatTime(it) }, todayRain)
            }

            when {
                data != null -> {
                    WeatherHero(data)
                    TemperatureChart(data.next24Hours())
                    PrecipitationChart(data.next24Hours())
                    HourlyForecast(data.next24Hours())
                    WeeklyForecast(data.daily, data.temperature)
                    WeatherStats(data)
                    FooterInfo(status, isOnline, data.fetchedAt, remainingMs)
                }
                loading -> {
                    Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(Modifier.height(14.dp))
                            Text("در حال دریافت اطلاعات…", color = Color.White)
                        }
                    }
                }
                else -> {
                    Column(Modifier.fillMaxWidth().padding(vertical = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("دریافت اطلاعات آب‌وهوا ممکن نشد", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: "اتصال اینترنت را بررسی کن", color = Color.White.copy(alpha = .82f), textAlign = TextAlign.Center)
                        TextButton(onClick = { refreshKey++ }) { Text("تلاش دوباره", color = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    online: Boolean,
    notificationsEnabled: Boolean,
    onLocation: () -> Unit,
    onNotifications: () -> Unit,
    onRefresh: () -> Unit,
    loading: Boolean
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(Color.White.copy(alpha = .19f))
            .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) Color(0xFF4ADE80) else Color(0xFFEF4444)))
        Text("  🔍", fontSize = 15.sp)
        Box(Modifier.weight(1f).padding(horizontal = 5.dp)) {
            if (query.isEmpty()) Text("جستجوی شهر", color = Color.White.copy(alpha = .67f), fontSize = 14.sp)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp)
            )
        }
        IconButton(onClick = onLocation, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.LocationOn, contentDescription = "موقعیت من", tint = Color.White)
        }
        IconButton(onClick = onNotifications, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.Notifications, contentDescription = "اعلان بارش", tint = if (notificationsEnabled) Color(0xFFFFE082) else Color.White)
        }
        IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = "به‌روزرسانی", tint = Color.White)
        }
        AppUpdateControl()
    }
}

@Composable
private fun SearchSuggestions(suggestions: List<CityPlace>, onSelect: (CityPlace) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF21A2A43))
    ) {
        Column {
            suggestions.take(12).forEach { result ->
                Column(Modifier.fillMaxWidth().clickable { onSelect(result) }.padding(horizontal = 15.dp, vertical = 10.dp)) {
                    Text(result.name, color = Color.White, fontWeight = FontWeight.Bold)
                    if (result.subtitle.isNotBlank()) Text(result.subtitle, color = Color.White.copy(alpha = .72f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun RainBanner(cityName: String, probability: Int, peakTime: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(15.dp))
            .background(Brush.horizontalGradient(listOf(Color(0x66FF8A3D), Color(0x33FFC857))))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🌧️", fontSize = 25.sp)
        Column(Modifier.padding(start = 10.dp)) {
            Text("هشدار بارش · " + cityName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("احتمال بارش تا ساعت " + peakTime + " به " + fa(probability) + "٪ می‌رسد. آماده باشید.", color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun NotificationMonitorBar(probability: Int, time: String?, todayProbability: Int) {
    val alert = probability >= RAIN_ALERT_THRESHOLD || todayProbability >= TODAY_RAIN_THRESHOLD
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp))
            .background(if (alert) Color(0x44FF9F5A) else Color(0x332CDA89))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔔", fontSize = 15.sp)
        Spacer(Modifier.width(9.dp))
        val line = when {
            probability >= RAIN_ALERT_THRESHOLD -> "بارش پیش‌بینی‌شده · احتمال " + fa(probability) + "٪ تا ساعت " + (time ?: "—")
            todayProbability >= TODAY_RAIN_THRESHOLD -> "امروز بارانی است · احتمال " + fa(todayProbability) + "٪"
            else -> "در حال پایش · بیشینه احتمال بارش " + fa(RAIN_WINDOW_HOURS) + " ساعت آینده: " + fa(probability) + "٪"
        }
        Text(line, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun WeatherHero(data: WeatherSnapshot) {
    Column(
        Modifier.fillMaxWidth().padding(top = 35.dp, bottom = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(data.city.name, color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Normal)
        if (data.city.subtitle.isNotBlank()) Text(data.city.subtitle, color = Color.White.copy(alpha = .76f), fontSize = 13.sp)
        Text(weatherIcon(data.weatherCode), fontSize = 53.sp, modifier = Modifier.padding(top = 7.dp))
        Text(fa(data.temperature.roundToInt()) + "°", color = Color.White, fontSize = 88.sp, fontWeight = FontWeight.ExtraLight, lineHeight = 94.sp)
        Text(weatherDescription(data.weatherCode), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "بیشینه " + fa(data.daily.firstOrNull()?.maxTemperature?.roundToInt() ?: 0) + "°   کمینه " + fa(data.daily.firstOrNull()?.minTemperature?.roundToInt() ?: 0) + "°",
            color = Color.White.copy(alpha = .9f), fontSize = 16.sp, modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun WeatherCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = .18f))
            .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 15.dp)
    ) {
        Text(title, color = Color.White.copy(alpha = .83f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun TemperatureChart(hours: List<HourForecast>) {
    WeatherCard("📈 دمای ۲۴ ساعت آینده") {
        if (hours.size < 2) Text("پیش‌بینی دمایی در دسترس نیست", color = Color.White)
        else {
            Canvas(Modifier.fillMaxWidth().height(145.dp)) {
                val left = 12.dp.toPx()
                val right = 8.dp.toPx()
                val top = 13.dp.toPx()
                val bottom = 12.dp.toPx()
                val chartWidth = size.width - left - right
                val chartHeight = size.height - top - bottom
                val values = hours.map { it.temperature }
                val low = (values.minOrNull() ?: 0.0) - 2.0
                val high = (values.maxOrNull() ?: 1.0) + 2.0
                val span = (high - low).coerceAtLeast(1.0)
                repeat(4) { index ->
                    val y = top + chartHeight * index / 3f
                    drawLine(Color.White.copy(alpha = .16f), Offset(left, y), Offset(size.width - right, y), 1.dp.toPx())
                }
                val points = values.mapIndexed { index, value ->
                    Offset(left + chartWidth * index / (values.size - 1).toFloat(), top + chartHeight - (((value - low) / span).toFloat() * chartHeight))
                }
                val fill = Path().apply {
                    moveTo(points.first().x, top + chartHeight)
                    points.forEach { lineTo(it.x, it.y) }
                    lineTo(points.last().x, top + chartHeight)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(Color.White.copy(alpha = .28f), Color.White.copy(alpha = .01f))))
                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(line, Color.White, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
                points.forEachIndexed { index, point ->
                    if (index % 4 == 0 || index == points.lastIndex) drawCircle(Color.White, 3.1.dp.toPx(), point)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("اکنون", "۶", "۱۲", "۱۸", "۲۴ ساعت").forEach { Text(it, color = Color.White.copy(alpha = .78f), fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun PrecipitationChart(hours: List<HourForecast>) {
    WeatherCard("💧 احتمال بارش ۲۴ ساعت آینده") {
        if (hours.isEmpty()) Text("اطلاعات بارش در دسترس نیست", color = Color.White)
        else {
            Canvas(Modifier.fillMaxWidth().height(116.dp)) {
                val left = 5.dp.toPx()
                val right = 5.dp.toPx()
                val top = 8.dp.toPx()
                val base = size.height - 8.dp.toPx()
                val slot = (size.width - left - right) / hours.size
                val barWidth = (slot * .62f).coerceAtLeast(2.dp.toPx())
                hours.forEachIndexed { index, hour ->
                    val h = (base - top) * hour.rainProbability / 100f
                    val x = left + slot * index + (slot - barWidth) / 2f
                    drawRoundRect(Color.White.copy(alpha = .17f), Offset(x, top), Size(barWidth, base - top), cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2))
                    if (hour.rainProbability > 0) {
                        drawRoundRect(
                            Brush.verticalGradient(listOf(Color(0xFFB9E7FF), Color(0xFF70B9F1))),
                            Offset(x, base - h), Size(barWidth, h.coerceAtLeast(2.dp.toPx())),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2)
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("۰٪", "۲۵٪", "۵۰٪", "۷۵٪", "۱۰۰٪").forEach { Text(it, color = Color.White.copy(alpha = .75f), fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun HourlyForecast(hours: List<HourForecast>) {
    WeatherCard("🕐 پیش‌بینی ساعتی") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            hours.forEachIndexed { index, hour ->
                Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (index == 0) "اکنون" else formatTime(hour.time), color = Color.White.copy(alpha = .84f), fontSize = 11.sp)
                    Text(weatherIcon(hour.weatherCode), fontSize = 24.sp, modifier = Modifier.padding(vertical = 7.dp))
                    Text(fa(hour.temperature.roundToInt()) + "°", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(fa(hour.rainProbability) + "٪", color = Color(0xFFD2EDFF), fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun WeeklyForecast(days: List<DayForecast>, currentTemp: Double) {
    WeatherCard("📅 پیش‌بینی ۷ روزه") {
        val low = days.minOfOrNull { it.minTemperature } ?: -10.0
        val high = days.maxOfOrNull { it.maxTemperature } ?: 40.0
        val span = (high - low).coerceAtLeast(1.0)
        days.forEachIndexed { index, day ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (index == 0) "امروز" else day.label(),
                    color = Color.White, fontSize = 13.sp,
                    fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(70.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(weatherIcon(day.weatherCode), fontSize = 21.sp, modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
                Text(fa(day.minTemperature.roundToInt()) + "°", color = Color.White.copy(alpha = .66f), fontSize = 12.sp, modifier = Modifier.width(34.dp))
                Canvas(Modifier.weight(1f).height(10.dp)) {
                    val start = ((day.minTemperature - low) / span).toFloat().coerceIn(0f, 1f)
                    val end = ((day.maxTemperature - low) / span).toFloat().coerceIn(0f, 1f)
                    val left = size.width * start
                    val width = (size.width * (end - start)).coerceAtLeast(5.dp.toPx())
                    drawRoundRect(Color.Black.copy(alpha = .13f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(temperatureColor(day.minTemperature), temperatureColor(day.maxTemperature))),
                        Offset(left, 0f), Size(width.coerceAtMost(size.width - left), size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                    )
                    if (index == 0) {
                        val current = ((currentTemp - low) / span).toFloat().coerceIn(0f, 1f)
                        drawCircle(Color.White, 3.5.dp.toPx(), Offset(size.width * current, size.height / 2))
                    }
                }
                Text(fa(day.maxTemperature.roundToInt()) + "°", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(35.dp), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun WeatherStats(data: WeatherSnapshot) {
    val today = data.daily.firstOrNull()
    val uv = today?.uvIndex ?: 0.0
    val uvLabel = when {
        uv < 3 -> "کم"
        uv < 6 -> "متوسط"
        uv < 8 -> "زیاد"
        uv < 11 -> "خیلی زیاد"
        else -> "شدید"
    }
    val feelDiff = data.feelsLike - data.temperature
    val feelNote = when {
        feelDiff > 1 -> "گرم‌تر از دمای واقعی"
        feelDiff < -1 -> "سردتر از دمای واقعی"
        else -> "نزدیک به دمای واقعی"
    }
    val humidityNote = when {
        data.humidity < 30 -> "هوای خشک"
        data.humidity < 60 -> "رطوبت مطلوب"
        else -> "هوای مرطوب"
    }
    val windNote = when {
        data.windSpeed < 12 -> "نسیم ملایم"
        data.windSpeed < 30 -> "باد متوسط"
        else -> "باد قوی"
    }
    val aqi = data.airQuality
    val aqiLabel = when {
        aqi == null -> "نامشخص"
        aqi <= 20 -> "خوب"
        aqi <= 40 -> "قابل قبول"
        aqi <= 60 -> "متوسط"
        aqi <= 80 -> "ناسالم"
        else -> "بسیار ناسالم"
    }
    val pressureNote = when {
        data.pressure < 1000 -> "کم‌فشار"
        data.pressure > 1020 -> "پرفشار"
        else -> "نرمال"
    }
    val sunrise = today?.sunrise?.let { formatTime(it) } ?: "—"
    val sunset = today?.sunset?.let { formatTime(it) } ?: "—"
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("☀️ شاخص UV", fa(uv.roundToInt()), uvLabel, "بیشینه امروز", Modifier.weight(1f), uv / 11.0)
            StatCard("🌇 غروب", sunset, "طلوع: " + sunrise, "زمان محلی شهر", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("💨 باد", fa(data.windSpeed.roundToInt()), "کیلومتر بر ساعت", windNote, Modifier.weight(1f))
            StatCard("🌡️ حس واقعی", fa(data.feelsLike.roundToInt()) + "°", feelNote, "دمای احساس‌شده", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("💧 رطوبت", fa(data.humidity) + "٪", humidityNote, "رطوبت نسبی", Modifier.weight(1f))
            StatCard("☔ احتمال بارش", fa(today?.rainProbability ?: 0) + "٪", "بیشینه امروز", "پیش‌بینی روزانه", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("🌬️ کیفیت هوا", aqi?.let { fa(it) } ?: "—", aqiLabel, "شاخص اروپایی (EAQI)", Modifier.weight(1f))
            StatCard("📊 فشار هوا", fa(data.pressure.roundToInt()), "هکتوپاسکال", pressureNote, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    level: String,
    note: String,
    modifier: Modifier = Modifier,
    progress: Double? = null
) {
    Column(
        modifier.padding(top = 12.dp).defaultMinSize(minHeight = 150.dp)
            .clip(RoundedCornerShape(19.dp)).background(Color.White.copy(alpha = .18f))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Text(title, color = Color.White.copy(alpha = .82f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(value, color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(level, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (progress != null) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(8.dp)) {
                Box(
                    Modifier.align(Alignment.CenterStart).fillMaxWidth().height(5.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(Color(0xFF3EC46D), Color(0xFFF5D63D), Color(0xFFF5963D), Color(0xFFE8453C), Color(0xFFA24BD6))))
                )
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    drawCircle(
                        Color.White,
                        4.dp.toPx(),
                        Offset(size.width * progress.toFloat().coerceIn(0f, 1f), size.height / 2)
                    )
                }
            }
        }
        Text(note, color = Color.White.copy(alpha = .78f), fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun FooterInfo(status: String, online: Boolean, fetchedAt: Long, remainingMs: Long) {
    val minutes = remainingMs / 60_000L
    val seconds = (remainingMs % 60_000L) / 1_000L
    val updateTime = SimpleDateFormat("HH:mm", Locale("fa", "IR")).format(Date(fetchedAt))
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("منبع داده: Open-Meteo", color = Color.White.copy(alpha = .65f), fontSize = 11.sp)
        Text("ساخته شده توسط mobin", color = Color.White.copy(alpha = .65f), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        Text("نسخه برنامه: " + BuildConfig.VERSION_NAME, color = Color.White.copy(alpha = .65f), fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

private fun formatTime(value: String): String {
    val match = Regex("(\\d{2}:\\d{2})").find(value)
    return match?.value?.let { faTime(it) } ?: "—"
}

private fun faTime(value: String): String = value.map { char ->
    when (char) {
        '0' -> '۰'; '1' -> '۱'; '2' -> '۲'; '3' -> '۳'; '4' -> '۴'
        '5' -> '۵'; '6' -> '۶'; '7' -> '۷'; '8' -> '۸'; '9' -> '۹'
        else -> char
    }
}.joinToString("")

private fun fa(value: Int): String = faTime(value.toString())

private fun temperatureColor(value: Double): Color = when {
    value <= 5 -> Color(0xFF64B5F6)
    value <= 15 -> lerp(Color(0xFF64B5F6), Color(0xFF82C878), ((value - 5) / 10).toFloat())
    value <= 25 -> lerp(Color(0xFF82C878), Color(0xFFF5D65D), ((value - 15) / 10).toFloat())
    value <= 35 -> lerp(Color(0xFFF5D65D), Color(0xFFF5963D), ((value - 25) / 10).toFloat())
    else -> Color(0xFFFF6B5E)
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

private fun hasInternet(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@SuppressLint("MissingPermission")
private fun requestLastOrCurrentLocation(
    context: Context,
    onLocation: (Double, Double) -> Unit,
    onFailure: () -> Unit
) {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = manager.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
    val recent = providers.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }.maxByOrNull { it.time }
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

internal fun sendRainNotifications(context: Context, weather: WeatherSnapshot) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(
            android.app.NotificationChannel("qandil_rain", "هشدار بارش قندیل", android.app.NotificationManager.IMPORTANCE_HIGH)
        )
    }
    val preferences = context.getSharedPreferences("qandil_native", Context.MODE_PRIVATE)
    val next = weather.nextSixHourRain
    val dailyProbability = weather.daily.firstOrNull()?.rainProbability ?: 0
    val peakTime = next.second.orEmpty()
    val cityKey = weather.city.cacheKey
    if (next.first >= RAIN_ALERT_THRESHOLD && peakTime.isNotBlank()) {
        val key = "rain_alert_" + cityKey + "_" + peakTime
        if (!preferences.getBoolean(key, false)) {
            postRainNotification(context, 3501, "بارش در " + weather.city.name,
                "تا ساعت " + formatTime(peakTime) + " احتمال بارش " + fa(next.first) + "٪ پیش‌بینی شده است. ☔")
            preferences.edit().putBoolean(key, true).apply()
        }
    }
    val dayKey = "rain_day_" + cityKey + "_" + weather.daily.firstOrNull()?.date.orEmpty()
    if (dailyProbability >= TODAY_RAIN_THRESHOLD && !preferences.getBoolean(dayKey, false)) {
        postRainNotification(context, 3502, "امروز در " + weather.city.name + " بارانی است",
            "احتمال بارش امروز تا " + fa(dailyProbability) + "٪ پیش‌بینی شده است. چتر را فراموش نکنید!")
        preferences.edit().putBoolean(dayKey, true).apply()
    }
}

private fun postRainNotification(context: Context, id: Int, title: String, message: String) {
    val notification = NotificationCompat.Builder(context, "qandil_rain")
        .setSmallIcon(R.drawable.ic_stat_weather)
        .setContentTitle(title)
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()
    runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
}

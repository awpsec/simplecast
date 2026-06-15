package com.awper.lightweather

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private val Ink = Color(0xFFE8E4DC)
private val Paper = Color.Black
private val Rule = Color(0xFF5E5A52)
private val PreviewWeatherCode: Int? = null
private const val BackgroundCheckAction = "com.awper.lightweather.BACKGROUND_CHECK"
private const val BackgroundIntervalMs = 10 * 60 * 1000L

class MainActivity : ComponentActivity() {
    private var touchStartX = 0f
    private var touchStartY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 3f, fontScale = 1f)) {
                WeatherApp()
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
            }
            MotionEvent.ACTION_UP -> {
                GestureRouter.onSwipe?.invoke(event.x - touchStartX, event.y - touchStartY, touchStartX, touchStartY)
                GestureRouter.onMove?.invoke(0f, 0f)
            }
            MotionEvent.ACTION_MOVE -> {
                GestureRouter.onMove?.invoke(event.x - touchStartX, event.y - touchStartY)
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

private object GestureRouter {
    var onSwipe: ((dx: Float, dy: Float, startX: Float, startY: Float) -> Unit)? = null
    var onMove: ((dx: Float, dy: Float) -> Unit)? = null
}

private enum class AppPane { Search, Home, Settings }

class BackgroundCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackgroundCheckAction) return
        val pendingResult = goAsync()
        Thread {
            try {
                val prefs = context.getSharedPreferences("simplecast", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("allowBackground", false)) return@Thread
                val home = prefs.savedPlace("home")
                val lat = home?.latitude ?: 42.3601
                val lon = home?.longitude ?: -71.0589
                runBackgroundWeatherCheck(lat, lon)
                prefs.edit().putLong("lastBackgroundCheck", System.currentTimeMillis()).apply()
                setBackgroundChecks(context, true)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}

private fun setBackgroundChecks(context: Context, enabled: Boolean) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pendingIntent = backgroundCheckIntent(context)
    if (!enabled) {
        alarmManager.cancel(pendingIntent)
        return
    }
    alarmManager.set(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        SystemClock.elapsedRealtime() + BackgroundIntervalMs,
        pendingIntent
    )
}

private fun backgroundCheckIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    1001,
    Intent(context, BackgroundCheckReceiver::class.java).setAction(BackgroundCheckAction),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

private fun runBackgroundWeatherCheck(latitude: Double, longitude: Double) {
    val url = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=$latitude&longitude=$longitude" +
        "&current=temperature_2m,weather_code" +
        "&temperature_unit=fahrenheit&forecast_days=1&timezone=auto"
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    connection.inputStream.bufferedReader().use { it.readText() }
}

@Composable
private fun WeatherApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("simplecast", Context.MODE_PRIVATE) }
    var weatherState by remember { mutableStateOf(prefs.cachedWeatherState() ?: WeatherState.Loading("finding location")) }
    var pane by remember { mutableStateOf(AppPane.Home) }
    var homePlace by remember { mutableStateOf(prefs.savedPlace("home")) }
    var allowBackground by remember { mutableStateOf(prefs.getBoolean("allowBackground", false)) }
    var temperatureUnit by remember { mutableStateOf(prefs.getString("temperatureUnit", "fahrenheit") ?: "fahrenheit") }
    var showIntro by remember { mutableStateOf(!prefs.getBoolean("introSeen", false)) }
    var refreshNonce by remember { mutableStateOf(0) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshNonce++
    }

    LaunchedEffect(Unit) {
        setBackgroundChecks(context, allowBackground)
    }

    LaunchedEffect(refreshNonce) {
        val hasLocation = context.hasLocationPermission()
        if (!hasLocation) {
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }

        val previousState = weatherState

        if (homePlace != null) {
            weatherState = runCatching {
                fetchWeather(homePlace!!.latitude, homePlace!!.longitude, fallback = false, locationLabel = homePlace!!.title.lowercase(), temperatureUnit = temperatureUnit, cachePrefs = prefs)
            }.getOrElse { error ->
                if (previousState is WeatherState.Ready) previousState else WeatherState.Error(error.message ?: "weather unavailable")
            }
            return@LaunchedEffect
        }

        val lastDevicePlace = prefs.savedPlace("lastDevice")
        if (lastDevicePlace != null && previousState !is WeatherState.Ready) {
            weatherState = runCatching {
                fetchWeather(lastDevicePlace.latitude, lastDevicePlace.longitude, fallback = false, locationLabel = lastDevicePlace.title.lowercase(), temperatureUnit = temperatureUnit, cachePrefs = prefs)
            }.getOrElse { error ->
                WeatherState.Error(error.message ?: "weather unavailable")
            }
        }

        val point = if (hasLocation) context.bestCurrentPoint() else null
        val fallback = point == null && lastDevicePlace == null
        val lat = point?.latitude ?: lastDevicePlace?.latitude ?: 42.3601
        val lon = point?.longitude ?: lastDevicePlace?.longitude ?: -71.0589
        val locationLabel = point?.let { context.locationLabel(it.latitude, it.longitude) }
            ?: lastDevicePlace?.title?.lowercase()
            ?: "boston"

        val shouldFetchCheckedLocation = point == null || lastDevicePlace == null || distanceMeters(
            lastDevicePlace.latitude,
            lastDevicePlace.longitude,
            lat,
            lon
        ) > 2_000f

        if (shouldFetchCheckedLocation || previousState !is WeatherState.Ready) {
            weatherState = runCatching { fetchWeather(lat, lon, fallback, locationLabel, temperatureUnit = temperatureUnit, cachePrefs = prefs) }
                .onSuccess {
                    if (point != null) {
                        prefs.edit().putPlace("lastDevice", Place(locationLabel, point.latitude, point.longitude)).apply()
                    }
                }
                .getOrElse { error ->
                    if (weatherState is WeatherState.Ready) weatherState else WeatherState.Error(error.message ?: "weather unavailable")
                }
        } else if (point != null) {
            prefs.edit().putPlace("lastDevice", Place(locationLabel, point.latitude, point.longitude)).apply()
        }
    }

    WeatherScreen(
        weatherState = weatherState,
        pane = pane,
        allowBackground = allowBackground,
        homePlace = homePlace,
        temperatureUnit = temperatureUnit,
        showIntro = showIntro,
        onPaneChange = { pane = it },
        onRetry = { refreshNonce++ },
        onAllowBackgroundChange = {
            allowBackground = it
            prefs.edit().putBoolean("allowBackground", it).apply()
            setBackgroundChecks(context, it)
        },
        onHomePlaceChange = {
            homePlace = it
            prefs.edit().putPlace("home", it).apply()
            refreshNonce++
        },
        onTemperatureUnitChange = {
            temperatureUnit = it
            prefs.edit().putString("temperatureUnit", it).apply()
            refreshNonce++
        },
        onIntroDismiss = {
            showIntro = false
            prefs.edit().putBoolean("introSeen", true).apply()
        }
    )
}

@Composable
private fun WeatherScreen(
    weatherState: WeatherState,
    pane: AppPane,
    allowBackground: Boolean,
    homePlace: Place?,
    temperatureUnit: String,
    showIntro: Boolean,
    onPaneChange: (AppPane) -> Unit,
    onRetry: () -> Unit,
    onAllowBackgroundChange: (Boolean) -> Unit,
    onHomePlaceChange: (Place?) -> Unit,
    onTemperatureUnitChange: (String) -> Unit,
    onIntroDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(
                horizontal = if (pane == AppPane.Home) 44.dp else 28.dp,
                vertical = if (pane == AppPane.Home) 22.dp else 0.dp
            )
    ) {
        when (pane) {
            AppPane.Search -> SearchPane(temperatureUnit = temperatureUnit, onClose = { onPaneChange(AppPane.Home) })
            AppPane.Settings -> SettingsPane(
                allowBackground = allowBackground,
                homePlace = homePlace,
                temperatureUnit = temperatureUnit,
                onAllowBackgroundChange = onAllowBackgroundChange,
                onHomePlaceChange = onHomePlaceChange,
                onTemperatureUnitChange = onTemperatureUnitChange,
                onClose = { onPaneChange(AppPane.Home) }
            )
            AppPane.Home -> when (weatherState) {
                is WeatherState.Loading -> CenterMessage(weatherState.label)
                is WeatherState.Error -> CenterMessage("weather unavailable\n${weatherState.message}\ntap to retry", onRetry)
                is WeatherState.Ready -> Forecast(weatherState.forecast, onRetry, onPaneChange)
            }
        }
        if (showIntro) IntroPopup(onDismiss = onIntroDismiss)
    }
}

@Composable
private fun IntroPopup(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .clickable { onDismiss() }
            .padding(horizontal = 38.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Rule)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TextLine("swipe left for settings", size = 18)
            Spacer(Modifier.height(12.dp))
            TextLine("swipe right for lookups", size = 18)
            Spacer(Modifier.height(12.dp))
            TextLine("enjoy your day!", size = 18)
            Spacer(Modifier.height(26.dp))
            TextLine("tap anywhere", size = 12, color = Rule)
        }
    }
}

@Composable
private fun Forecast(forecast: Forecast, onRetry: () -> Unit, onPaneChange: (AppPane) -> Unit) {
    var page by remember { mutableStateOf(0) }
    var hourPage by remember(forecast.hours) { mutableStateOf(0) }
    var dayPage by remember(forecast.days) { mutableStateOf(0) }
    var selectedDay by remember(forecast.days) { mutableStateOf<LocalDate?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hourPageCount = forecast.hours.chunked(4).size.coerceAtLeast(1)
    val dayPageCount = forecast.days.take(10).chunked(5).size.coerceAtLeast(1)

    DisposableEffect(page, hourPage, dayPage, selectedDay, hourPageCount, dayPageCount) {
        GestureRouter.onSwipe = { dx, dy, _, startY ->
            if (page == 0 && dy > 170f && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                if (!refreshing) {
                    refreshing = true
                    scope.launch {
                        delay(1_650)
                        onRetry()
                        delay(350)
                        refreshing = false
                    }
                }
            } else if (kotlin.math.abs(dy) > kotlin.math.abs(dx) && kotlin.math.abs(dy) > 120f) {
                if (page == 0 && dy < 0f) {
                    page = 1
                } else if (page == 1 && selectedDay == null && dy < 0f && dayPage < dayPageCount - 1) {
                    dayPage++
                } else if (page == 1 && selectedDay == null && dy > 0f && dayPage > 0) {
                    dayPage--
                } else if (page == 1 && selectedDay == null && dy > 0f) {
                    page = 0
                }
            }
            if (page == 0 && kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > 90f) {
                if (startY > 820f) {
                    if (dx < 0f && hourPage < hourPageCount - 1) hourPage++
                    if (dx > 0f && hourPage > 0) hourPage--
                } else {
                    if (dx < 0f) onPaneChange(AppPane.Settings)
                    if (dx > 0f) onPaneChange(AppPane.Search)
                }
            }
            if (page == 1 && kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > 90f) {
                if (selectedDay != null) {
                    if (dx > 0f) selectedDay = null
                } else {
                    if (dx < 0f && dayPage < dayPageCount - 1) dayPage++
                    if (dx > 0f && dayPage > 0) dayPage--
                }
            }
        }
        GestureRouter.onMove = { _, _ -> }
        onDispose {
            GestureRouter.onSwipe = null
            GestureRouter.onMove = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .offset(y = if (refreshing) 12.dp else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PullDots(visible = refreshing)

        if (page == 0) {
            CurrentAndHourlyPage(forecast, hourPage)
        } else {
            TenDayPage(forecast, dayPage, selectedDay, onSelectDay = { selectedDay = it }, onBack = { selectedDay = null })
        }
    }
}

@Composable
private fun CurrentAndHourlyPage(forecast: Forecast, hourPage: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(22.dp))
        val displayCode = PreviewWeatherCode ?: forecast.current.displayCode
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherGlyph(displayCode, Modifier.size(66.dp), animated = true)
            Spacer(Modifier.width(24.dp))
            TextLine(formatTemp(forecast.current.temperature, forecast.unitSymbol), size = 50, weight = FontWeight.Light)
        }
        Spacer(Modifier.height(12.dp))
        TextLine(weatherLabel(displayCode), size = 19)
        Spacer(Modifier.height(6.dp))
        TextLine(forecast.locationLabel.lowercase(), size = 13)

        Spacer(Modifier.height(24.dp))
        TextLine("hourly", size = 14, color = Rule)
        Spacer(Modifier.height(12.dp))
        HourlyPager(forecast.hours, hourPage, forecast.unitSymbol)
    }
}

@Composable
private fun TenDayPage(
    forecast: Forecast,
    dayPage: Int,
    selectedDay: LocalDate?,
    onSelectDay: (LocalDate) -> Unit,
    onBack: () -> Unit
) {
    if (selectedDay != null) {
        DayHourlyPage(forecast, selectedDay, onBack)
        return
    }

    val pages = forecast.days.take(10).chunked(5).ifEmpty { listOf(emptyList()) }
    val safePage = dayPage.coerceIn(0, pages.lastIndex)

    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(10.dp))
        TextLine("10 day", size = 12, color = Rule)
        Spacer(Modifier.height(10.dp))
        pages[safePage].forEach { day ->
            ForecastRow(
                left = formatDay(day.date),
                code = day.code,
                right = "${formatTemp(day.high, forecast.unitSymbol)} / ${formatTemp(day.low, forecast.unitSymbol)}",
                onClick = { onSelectDay(day.date) }
            )
        }
        Spacer(Modifier.height(12.dp))
        TextLine("${safePage + 1}/${pages.size}", size = 12, color = Rule)
    }
}

@Composable
private fun DayHourlyPage(forecast: Forecast, date: LocalDate, onBack: () -> Unit) {
    val hours = forecast.allHours.filter { it.time.toLocalDate() == date }
    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopArrow(onBack)
        TextLine(formatDay(date).lowercase(), size = 18)
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            hours.forEach { hour ->
                ForecastRow(
                    left = formatHour(hour.time).lowercase(),
                    code = hour.code,
                    right = formatTemp(hour.temperature, forecast.unitSymbol)
                )
            }
        }
    }
}

@Composable
private fun SearchPane(temperatureUnit: String, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var places by remember { mutableStateOf(emptyList<Place>()) }
    var selectedPlace by remember { mutableStateOf<Place?>(null) }
    var selectedState by remember { mutableStateOf<WeatherState?>(null) }
    var selectedHourPage by remember { mutableStateOf(0) }
    val selectedHourPageCount = (selectedState as? WeatherState.Ready)
        ?.forecast
        ?.hours
        ?.chunked(4)
        ?.size
        ?.coerceAtLeast(1) ?: 1

    DisposableEffect(selectedPlace, selectedHourPage, selectedHourPageCount) {
        GestureRouter.onSwipe = { dx, dy, _, _ ->
            if (selectedPlace == null && kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx < -90f) {
                onClose()
            } else if (selectedPlace != null && kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > 90f) {
                if (dx < 0f && selectedHourPage < selectedHourPageCount - 1) selectedHourPage++
                if (dx > 0f && selectedHourPage > 0) selectedHourPage--
            }
        }
        GestureRouter.onMove = { _, _ -> }
        onDispose {
            GestureRouter.onSwipe = null
            GestureRouter.onMove = null
        }
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        places = if (trimmed.length >= 2) {
            delay(250)
            try {
                searchPlaces(trimmed)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                places
            }
        } else {
            emptyList()
        }
    }

    LaunchedEffect(selectedPlace) {
        val place = selectedPlace ?: return@LaunchedEffect
        selectedHourPage = 0
        selectedState = WeatherState.Loading("loading weather")
        selectedState = runCatching { fetchWeather(place.latitude, place.longitude, fallback = false, locationLabel = place.title.lowercase(), temperatureUnit = temperatureUnit) }
            .getOrElse { WeatherState.Error(it.message ?: "weather unavailable") }
    }

    if (selectedPlace != null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            TopArrow { selectedPlace = null; selectedState = null }
            TextLine(selectedPlace!!.title.lowercase(), size = 18)
            Spacer(Modifier.height(18.dp))
            when (val state = selectedState) {
                is WeatherState.Ready -> SearchForecast(state.forecast, selectedHourPage)
                is WeatherState.Error -> CenterMessage("weather unavailable")
                else -> CenterMessage("loading weather")
            }
        }
        return
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopArrow(onClose)
        SearchField(value = query, onValueChange = { query = it }, placeholder = "search place")
        Spacer(Modifier.height(22.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            places.forEach { place ->
                PlaceRow(place = place) { selectedPlace = place }
            }
        }
    }
}

@Composable
private fun SettingsPane(
    allowBackground: Boolean,
    homePlace: Place?,
    temperatureUnit: String,
    onAllowBackgroundChange: (Boolean) -> Unit,
    onHomePlaceChange: (Place?) -> Unit,
    onTemperatureUnitChange: (String) -> Unit,
    onClose: () -> Unit
) {
    var homeQuery by remember { mutableStateOf("") }
    var places by remember { mutableStateOf(emptyList<Place>()) }

    DisposableEffect(Unit) {
        GestureRouter.onSwipe = { dx, dy, _, _ ->
            if (kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx > 90f) onClose()
        }
        GestureRouter.onMove = { _, _ -> }
        onDispose {
            GestureRouter.onSwipe = null
            GestureRouter.onMove = null
        }
    }

    LaunchedEffect(homeQuery) {
        val trimmed = homeQuery.trim()
        places = if (trimmed.length >= 2) {
            delay(250)
            try {
                searchPlaces(trimmed)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                places
            }
        } else {
            emptyList()
        }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopArrow(onClose)
        TextLine("settings", size = 22)
        Spacer(Modifier.height(34.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextLine("allow in background", size = 18, modifier = Modifier.weight(1f), align = TextAlign.Start)
            OvalSwitch(checked = allowBackground) { onAllowBackgroundChange(!allowBackground) }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextLine("temperature", size = 18, modifier = Modifier.weight(1f), align = TextAlign.Start)
            UnitChoice(label = "f", selected = temperatureUnit == "fahrenheit") { onTemperatureUnitChange("fahrenheit") }
            Spacer(Modifier.width(10.dp))
            UnitChoice(label = "c", selected = temperatureUnit == "celsius") { onTemperatureUnitChange("celsius") }
        }
        Spacer(Modifier.height(34.dp))
        TextLine("home location", size = 14, color = Rule)
        Spacer(Modifier.height(12.dp))
        SearchField(value = homeQuery, onValueChange = { homeQuery = it }, placeholder = "search home")
        if (homePlace != null && homeQuery.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            TextLine("current: ${homePlace.title.lowercase()}", size = 13, color = Rule)
        }
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            places.forEach { place ->
                PlaceRow(place = place) {
                    homeQuery = place.title
                    onHomePlaceChange(place)
                    places = emptyList()
                }
            }
        }
    }
}

@Composable
private fun SearchForecast(forecast: Forecast, hourPage: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CurrentAndHourlyPage(forecast, hourPage = hourPage)
        Spacer(Modifier.height(28.dp))
        TextLine("10 day", size = 12, color = Rule)
        Spacer(Modifier.height(10.dp))
        forecast.days.take(10).forEach { day ->
            ForecastRow(formatDay(day.date), day.code, "${formatTemp(day.high, forecast.unitSymbol)} / ${formatTemp(day.low, forecast.unitSymbol)}")
        }
    }
}

@Composable
private fun UnitChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) Ink else Rule)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        TextLine(label, size = 16, color = if (selected) Ink else Rule)
    }
}

@Composable
private fun TopArrow(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.text.BasicText(
            text = "<",
            modifier = Modifier
                .clickable { onClick() }
                .padding(top = 2.dp, bottom = 8.dp, end = 18.dp),
            style = androidx.compose.ui.text.TextStyle(color = Ink, fontFamily = FontFamily.Default, fontSize = 28.sp)
        )
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .border(1.dp, Rule)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) TextLine(placeholder, size = 18, color = Rule, align = TextAlign.Start)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontFamily = FontFamily.Default, fontSize = 18.sp)
        )
    }
}

@Composable
private fun PlaceRow(place: Place, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .border(1.dp, Rule)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextLine(place.title.lowercase(), size = 17, modifier = Modifier.weight(1f), align = TextAlign.Start)
    }
}

@Composable
private fun OvalSwitch(checked: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .width(58.dp)
            .height(30.dp)
            .border(1.dp, Ink, shape)
            .background(if (checked) Ink else Paper, shape)
            .clickable { onClick() }
    )
}

@Composable
private fun PullDots(visible: Boolean) {
    var tick by remember { mutableStateOf(0) }
    if (visible) {
        LaunchedEffect(Unit) {
            tick = 0
            delay(450)
            tick = 1
            delay(450)
            tick = 2
        }
    }

    Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) {
        if (visible) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val count = tick + 1
                repeat(count) {
                    Box(
                        Modifier
                            .size(4.dp)
                            .background(Ink)
                    )
                }
            }
        }
    }
}

@Composable
private fun HourlyPager(hours: List<HourForecast>, hourPage: Int, unitSymbol: String) {
    val pages = hours.chunked(4).ifEmpty { listOf(emptyList()) }
    val safePage = hourPage.coerceIn(0, pages.lastIndex)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(118.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        pages[safePage].forEachIndexed { index, hour ->
            HourCard(
                hour = hour,
                isCurrent = safePage == 0 && index == 0,
                unitSymbol = unitSymbol,
                modifier = Modifier.weight(1f)
            )
        }
        repeat(4 - pages[safePage].size) {
            Spacer(Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(12.dp))
    TextLine("${safePage + 1}/${pages.size}", size = 12, color = Rule)
}

@Composable
private fun HourCard(hour: HourForecast, isCurrent: Boolean, unitSymbol: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(108.dp)
            .border(if (isCurrent) 3.dp else 1.dp, if (isCurrent) Ink else Rule)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        TextLine(formatHour(hour.time).lowercase(), size = 13)
        Spacer(Modifier.height(6.dp))
        WeatherGlyph(hour.code, Modifier.size(22.dp), animated = false)
        Spacer(Modifier.height(6.dp))
        TextLine(formatTemp(hour.temperature, unitSymbol), size = 16)
    }
}

@Composable
private fun ForecastRow(left: String, code: Int, right: String, onClick: (() -> Unit)? = null) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .height(52.dp)
        .border(1.dp, Rule)
        .then(if (onClick == null) Modifier else Modifier.clickable { onClick() })
        .padding(horizontal = 20.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextLine(left.lowercase(), size = 17, modifier = Modifier.weight(1f), align = TextAlign.Start)
        WeatherGlyph(code, Modifier.size(30.dp), animated = false)
        TextLine(right, size = 17, modifier = Modifier.weight(1f), align = TextAlign.End)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Spacer(Modifier.height(28.dp))
    TextLine(title, size = 17, weight = FontWeight.Normal)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun CenterMessage(message: String, onTap: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.text.BasicText(
            text = message.lowercase(),
            style = androidx.compose.ui.text.TextStyle(
                color = Ink,
                fontFamily = FontFamily.Default,
                fontSize = 18.sp,
                letterSpacing = 0.sp,
                textAlign = TextAlign.Center
            ),
            modifier = if (onTap == null) Modifier else Modifier
                .clickable { onTap() }
                .padding(32.dp)
        )
    }
}

@Composable
private fun TextLine(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Center,
    tracking: Boolean = false,
    color: Color = Ink
) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(
            color = color,
            fontFamily = FontFamily.Default,
            fontSize = size.sp,
            lineHeight = size.sp,
            fontWeight = weight,
            letterSpacing = if (tracking) 3.sp else 0.sp,
            textAlign = align
        )
    )
}

@Composable
private fun WeatherGlyph(code: Int, modifier: Modifier = Modifier, animated: Boolean = true) {
    Image(
        painter = painterResource(id = weatherIconRes(code)),
        contentDescription = weatherLabel(code),
        modifier = modifier
    )
}

private fun weatherIconRes(code: Int): Int = when {
    code == -1 -> R.drawable.ic_weather_wind
    code == 0 -> R.drawable.ic_weather_sun
    code == 1 -> R.drawable.ic_weather_cloud_sun
    code in 2..3 -> R.drawable.ic_weather_cloud
    code in 45..48 -> R.drawable.ic_weather_fog
    code in 56..57 || code in 66..67 || code in 96..99 -> R.drawable.ic_weather_snow
    code in 51..67 || code in 80..82 -> R.drawable.ic_weather_rain
    code in 71..77 || code in 85..86 -> R.drawable.ic_weather_snow
    code in 95..99 -> R.drawable.ic_weather_storm
    else -> R.drawable.ic_weather_cloud
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloud(stroke: Stroke, phase: Float = 0f) {
    val w = size.width
    val h = size.height
    val breathe = kotlin.math.sin(phase * Math.PI.toFloat() * 2f) * w * 0.012f
    drawArc(Ink, 180f, 180f, false, topLeft = Offset(w * 0.18f - breathe, h * 0.38f), size = Size(w * 0.30f, h * 0.28f), style = stroke)
    drawArc(Ink, 180f, 180f, false, topLeft = Offset(w * 0.35f, h * 0.26f - breathe * 0.35f), size = Size(w * 0.34f + breathe * 0.8f, h * 0.40f), style = stroke)
    drawArc(Ink, 180f, 180f, false, topLeft = Offset(w * 0.58f + breathe, h * 0.40f), size = Size(w * 0.24f, h * 0.26f), style = stroke)
    drawLine(Ink, Offset(w * 0.24f - breathe, h * 0.66f), Offset(w * 0.78f + breathe, h * 0.66f), strokeWidth = stroke.width, cap = StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFullCloud(stroke: Stroke, phase: Float = 0f) {
    drawCloud(stroke, phase)
    val w = size.width
    val h = size.height
    val breathe = kotlin.math.sin(phase * Math.PI.toFloat() * 2f) * w * 0.008f
    drawArc(Ink, 180f, 180f, false, topLeft = Offset(w * 0.28f - breathe, h * 0.46f), size = Size(w * 0.22f, h * 0.18f), style = stroke)
    drawArc(Ink, 180f, 180f, false, topLeft = Offset(w * 0.50f + breathe, h * 0.45f), size = Size(w * 0.20f, h * 0.18f), style = stroke)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPartlyCloudy(stroke: Stroke, phase: Float = 0f) {
    val w = size.width
    val h = size.height
    val peek = kotlin.math.sin(phase * Math.PI.toFloat() * 2f) * w * 0.015f
    val sunCenter = Offset(w * 0.62f, h * 0.36f - peek)
    val sunRadius = w * 0.15f
    drawCircle(Ink, radius = sunRadius, center = sunCenter, style = stroke)
    for (i in 0 until 5) {
        val angle = Math.toRadians((-120 + i * 30).toDouble())
        drawLine(
            Ink,
            Offset(sunCenter.x + kotlin.math.cos(angle).toFloat() * sunRadius * 1.35f, sunCenter.y + kotlin.math.sin(angle).toFloat() * sunRadius * 1.35f),
            Offset(sunCenter.x + kotlin.math.cos(angle).toFloat() * sunRadius * 1.65f, sunCenter.y + kotlin.math.sin(angle).toFloat() * sunRadius * 1.65f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
    drawCloud(stroke, phase)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOvercast(stroke: Stroke, phase: Float = 0f) {
    val w = size.width
    val h = size.height
    drawCloud(stroke, phase)
    val spread = 1f + kotlin.math.sin(phase * Math.PI.toFloat() * 2f) * 0.18f
    val starts = listOf(0.40f to 0.76f, 0.50f to 0.78f, 0.60f to 0.76f)
    val lengths = listOf(0.12f, 0.18f, 0.12f)
    for (i in starts.indices) {
        val (x, y) = starts[i]
        val dx = (i - 1) * 0.035f * spread
        drawLine(
            Ink,
            Offset(w * x, h * y),
            Offset(w * (x + dx), h * (y + lengths[i] * spread)),
            strokeWidth = stroke.width * 0.75f,
            cap = StrokeCap.Round
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFog(width: Float, phase: Float = 0f) {
    val w = size.width
    val h = size.height
    val shift = kotlin.math.sin(phase * Math.PI.toFloat() * 2f) * w * 0.025f
    for ((index, y) in listOf(0.38f, 0.52f, 0.66f).withIndex()) {
        val inset = if (index == 1) 0.18f else 0.26f
        drawLine(
            Ink,
            Offset(w * inset + shift, h * y),
            Offset(w * (1f - inset) + shift, h * y),
            strokeWidth = width,
            cap = StrokeCap.Round
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWindCondition(width: Float, phase: Float = 0f) {
    val w = size.width
    val h = size.height
    val shift = kotlin.math.sin(phase * Math.PI.toFloat() * 2f) * w * 0.025f
    for ((index, y) in listOf(0.36f, 0.52f, 0.68f).withIndex()) {
        val start = listOf(0.18f, 0.12f, 0.22f)[index]
        val end = listOf(0.72f, 0.66f, 0.58f)[index]
        drawLine(Ink, Offset(w * start + shift, h * y), Offset(w * end + shift, h * y), strokeWidth = width, cap = StrokeCap.Round)
        drawArc(
            Ink,
            270f,
            210f,
            false,
            topLeft = Offset(w * (end - 0.075f) + shift, h * (y - 0.075f)),
            size = Size(w * 0.15f, h * 0.15f),
            style = Stroke(width = width, cap = StrokeCap.Round)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWind(width: Float, phase: Float = 0f) {
    val w = size.width
    val h = size.height
    val shift = ((phase * 2f) % 1f) * w * 0.10f
    for ((y, len) in listOf(0.76f to 0.34f, 0.86f to 0.25f)) {
        drawLine(
            Ink,
            Offset(w * (0.26f + shift / w), h * y),
            Offset(w * (0.26f + len + shift / w), h * y),
            strokeWidth = width * 0.75f,
            cap = StrokeCap.Round
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRain(width: Float, phase: Float = 0f) {
    val drops = listOf(
        0.30f to 0.00f,
        0.42f to 0.37f,
        0.54f to 0.14f,
        0.66f to 0.51f,
        0.36f to 0.72f,
        0.48f to 0.88f,
        0.60f to 0.66f,
        0.72f to 0.24f
    )
    for ((x, offset) in drops) {
        val t = (phase + offset) % 1f
        val y = 0.66f + t * 0.38f
        if (y > 0.98f) continue
        drawLine(
            Ink,
            Offset(size.width * x, size.height * y),
            Offset(size.width * (x - 0.025f), size.height * (y + 0.08f)),
            strokeWidth = width * 0.85f,
            cap = StrokeCap.Round
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSnow(width: Float, phase: Float = 0f) {
    val flakes = listOf(
        0.30f to 0.00f,
        0.43f to 0.31f,
        0.56f to 0.14f,
        0.68f to 0.47f,
        0.36f to 0.63f,
        0.50f to 0.82f,
        0.62f to 0.72f,
        0.74f to 0.21f
    )
    for ((x, offset) in flakes) {
        val t = (phase + offset) % 1f
        val sway = kotlin.math.sin((t + offset) * Math.PI.toFloat() * 2f) * 0.025f
        val y = 0.68f + t * 0.30f
        if (y > 0.96f) continue
        drawCircle(
            Ink,
            radius = width * 0.52f,
            center = Offset(size.width * (x + sway), size.height * y)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSleet(width: Float, phase: Float = 0f) {
    drawRain(width * 0.82f, phase)
    drawSnow(width * 0.95f, (phase + 0.38f) % 1f)
}

private suspend fun fetchWeather(
    latitude: Double,
    longitude: Double,
    fallback: Boolean,
    locationLabel: String,
    temperatureUnit: String = "fahrenheit",
    cachePrefs: SharedPreferences? = null
): WeatherState.Ready = withContext(Dispatchers.IO) {
    val unit = if (temperatureUnit == "celsius") "celsius" else "fahrenheit"
    val url = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=$latitude&longitude=$longitude" +
        "&current=temperature_2m,weather_code,wind_speed_10m" +
        "&hourly=temperature_2m,weather_code" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
        "&temperature_unit=$unit&forecast_days=10&timezone=auto"
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000

    val body = connection.inputStream.bufferedReader().use { it.readText() }
    cachePrefs?.edit()
        ?.putString("cachedWeatherBody", body)
        ?.putBoolean("cachedWeatherFallback", fallback)
        ?.putString("cachedWeatherLocation", locationLabel)
        ?.putString("cachedWeatherUnit", unit)
        ?.apply()
    parseWeatherBody(body, fallback, locationLabel, unit)
}

private fun parseWeatherBody(body: String, fallback: Boolean, locationLabel: String, temperatureUnit: String): WeatherState.Ready {
    val json = JSONObject(body)
    val currentJson = json.getJSONObject("current")
    val hourlyJson = json.getJSONObject("hourly")
    val dailyJson = json.getJSONObject("daily")

    val now = LocalDateTime.parse(currentJson.getString("time"))
    val hours = mutableListOf<HourForecast>()
    val allHours = mutableListOf<HourForecast>()
    val hourTimes = hourlyJson.getJSONArray("time")
    val hourTemps = hourlyJson.getJSONArray("temperature_2m")
    val hourCodes = hourlyJson.getJSONArray("weather_code")
    for (i in 0 until hourTimes.length()) {
        val time = LocalDateTime.parse(hourTimes.getString(i))
        val hour = HourForecast(time, hourTemps.getDouble(i), hourCodes.getInt(i))
        if (!time.isBefore(now)) {
            allHours += hour
            if (hours.size < 16) hours += hour
        }
    }

    val days = mutableListOf<DayForecast>()
    val dayTimes = dailyJson.getJSONArray("time")
    val highs = dailyJson.getJSONArray("temperature_2m_max")
    val lows = dailyJson.getJSONArray("temperature_2m_min")
    val dayCodes = dailyJson.getJSONArray("weather_code")
    for (i in 0 until dayTimes.length()) {
        days += DayForecast(LocalDate.parse(dayTimes.getString(i)), highs.getDouble(i), lows.getDouble(i), dayCodes.getInt(i))
    }

    return WeatherState.Ready(
        Forecast(
            current = CurrentForecast(
                temperature = currentJson.getDouble("temperature_2m"),
                code = currentJson.getInt("weather_code"),
                windSpeed = currentJson.optDouble("wind_speed_10m", 0.0)
            ),
            hours = hours,
            allHours = allHours,
            days = days,
            usedFallbackLocation = fallback,
            locationLabel = locationLabel,
            unitSymbol = if (temperatureUnit == "celsius") "°C" else "°F"
        )
    )
}

private suspend fun searchPlaces(query: String): List<Place> = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=25&language=en&format=json"
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    val body = connection.inputStream.bufferedReader().use { it.readText() }
    val results = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
    buildList {
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val name = item.getString("name")
            val admin = item.optString("admin1", "")
            val country = item.optString("country_code", item.optString("country", ""))
            val title = listOf(name, admin, country).filter { it.isNotBlank() }.joinToString(", ")
            add(Place(title, item.getDouble("latitude"), item.getDouble("longitude")))
        }
    }
}

private fun SharedPreferences.savedPlace(prefix: String): Place? {
    if (!contains("${prefix}_lat") || !contains("${prefix}_lon")) return null
    return Place(
        title = getString("${prefix}_title", "") ?: "",
        latitude = Double.fromBits(getLong("${prefix}_lat", 0L)),
        longitude = Double.fromBits(getLong("${prefix}_lon", 0L))
    )
}

private fun SharedPreferences.cachedWeatherState(): WeatherState.Ready? {
    val body = getString("cachedWeatherBody", null) ?: return null
    val fallback = getBoolean("cachedWeatherFallback", false)
    val location = getString("cachedWeatherLocation", null) ?: return null
    val unit = getString("cachedWeatherUnit", "fahrenheit") ?: "fahrenheit"
    return runCatching { parseWeatherBody(body, fallback, location, unit) }.getOrNull()
}

private fun SharedPreferences.Editor.putPlace(prefix: String, place: Place?): SharedPreferences.Editor {
    return if (place == null) {
        remove("${prefix}_title").remove("${prefix}_lat").remove("${prefix}_lon")
    } else {
        putString("${prefix}_title", place.title)
            .putLong("${prefix}_lat", place.latitude.toBits())
            .putLong("${prefix}_lon", place.longitude.toBits())
    }
}

private fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun Context.lastKnownPoint(): Location? {
    val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return runCatching {
        manager.getProviders(true)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()
}

private suspend fun Context.bestCurrentPoint(): Location? {
    val lastKnown = lastKnownPoint()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return lastKnown
    val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return lastKnown
    return runCatching {
        withTimeoutOrNull(2_500) {
            suspendCancellableCoroutine<Location?> { continuation ->
            val cancellationSignal = CancellationSignal()
            manager.getCurrentLocation(provider, cancellationSignal, mainExecutor) { location ->
                continuation.resume(location ?: lastKnown)
            }
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            }
        }
    }.getOrNull() ?: lastKnown
}

private suspend fun Context.locationLabel(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
    runCatching {
        val result = Geocoder(this@locationLabel, Locale.getDefault())
            .getFromLocation(latitude, longitude, 1)
            ?.firstOrNull()
        listOfNotNull(result?.locality, result?.adminArea)
            .distinct()
            .joinToString(", ")
            .ifBlank { null }
    }.getOrNull()
}

private fun distanceMeters(startLat: Double, startLon: Double, endLat: Double, endLon: Double): Float {
    val result = FloatArray(1)
    Location.distanceBetween(startLat, startLon, endLat, endLon, result)
    return result[0]
}

private fun formatHour(time: LocalDateTime): String = time.format(DateTimeFormatter.ofPattern("h a"))

private fun formatDay(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEE M/d"))

private fun formatTemp(value: Double, unitSymbol: String): String = "${value.roundToInt()}$unitSymbol"

private fun weatherLabel(code: Int): String = when (code) {
    -1 -> "wind"
    0 -> "clear"
    1 -> "partly cloudy"
    2 -> "clouds"
    3 -> "overcast"
    45, 48 -> "fog"
    in 56..57, in 66..67, in 96..99 -> "sleet"
    in 51..67, in 80..82 -> "rain"
    in 71..77, in 85..86 -> "snow"
    in 95..99 -> "storm"
    else -> "weather"
}

private sealed interface WeatherState {
    data class Loading(val label: String) : WeatherState
    data class Ready(val forecast: Forecast) : WeatherState
    data class Error(val message: String) : WeatherState
}

private data class Forecast(
    val current: CurrentForecast,
    val hours: List<HourForecast>,
    val allHours: List<HourForecast>,
    val days: List<DayForecast>,
    val usedFallbackLocation: Boolean,
    val locationLabel: String,
    val unitSymbol: String
)

private data class CurrentForecast(val temperature: Double, val code: Int, val windSpeed: Double) {
    val displayCode: Int = if (windSpeed >= 35.0 && code in 0..3) -1 else code
}
private data class HourForecast(val time: LocalDateTime, val temperature: Double, val code: Int)
private data class DayForecast(val date: LocalDate, val high: Double, val low: Double, val code: Int)
private data class Place(val title: String, val latitude: Double, val longitude: Double)

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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

private val Ink = Color(0xFFE8E4DC)
private val Paper = Color.Black
private val Rule = Color(0xFF5E5A52)
private const val BackgroundCheckAction = "com.awper.lightweather.BACKGROUND_CHECK"
private const val BackgroundIntervalMs = 10 * 60 * 1000L
private const val CacheFreshMs = 10 * 60 * 1000L
private const val CacheDisplayMaxMs = 45 * 60 * 1000L
private const val HttpConnectTimeoutMs = 2_500
private const val HttpReadTimeoutMs = 3_500
private const val LocationTimeoutMs = 700L
private const val DefaultLatitude = 42.3601
private const val DefaultLongitude = -71.0589
private const val DragSlopPx = 24f
private const val SettleThreshold = 0.18f
private val SlideSpec = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)

private enum class DragAxis { None, Horizontal, Vertical }
private enum class DragMode { None, Pane, VerticalHome, DayBack, TenDayBack }

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
                GestureRouter.onDown?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                GestureRouter.onDrag?.invoke(event.x - touchStartX, event.y - touchStartY, touchStartY)
            }
            MotionEvent.ACTION_UP -> {
                GestureRouter.onRelease?.invoke(event.x - touchStartX, event.y - touchStartY, touchStartY)
            }
            MotionEvent.ACTION_CANCEL -> {
                GestureRouter.onCancel?.invoke()
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

private object GestureRouter {
    var onDown: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float, startY: Float) -> Unit)? = null
    var onRelease: ((dx: Float, dy: Float, startY: Float) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
}

class BackgroundCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackgroundCheckAction) return
        val pendingResult = goAsync()
        Thread {
            try {
                val prefs = context.getSharedPreferences("simplecast", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("allowBackground", false)) return@Thread
                val home = prefs.savedPlace("home")
                val lat = home?.latitude ?: DefaultLatitude
                val lon = home?.longitude ?: DefaultLongitude
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
    val connection = openMeteoConnection(url)
    connection.inputStream.bufferedReader().use { it.readText() }
}

private suspend fun animateFloat(from: Float, to: Float, onValue: (Float) -> Unit) {
    if (abs(from - to) < 0.001f) {
        onValue(to)
        return
    }
    animate(initialValue = from, targetValue = to, animationSpec = SlideSpec) { value, _ ->
        onValue(value)
    }
}

private fun settleStep(rest: Float, current: Float, min: Float, max: Float): Float {
    val delta = current - rest
    val stepped = when {
        delta <= -SettleThreshold -> rest - 1f
        delta >= SettleThreshold -> rest + 1f
        else -> rest
    }
    return stepped.coerceIn(min, max)
}

@Composable
private fun WeatherApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("simplecast", Context.MODE_PRIVATE) }
    var weatherState by remember {
        mutableStateOf<WeatherState>(prefs.displayCachedWeatherState() ?: WeatherState.Loading("loading weather"))
    }
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

    LaunchedEffect(refreshNonce, homePlace, temperatureUnit) {
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
        val unit = if (temperatureUnit == "celsius") "celsius" else "fahrenheit"
        val cacheAgeMs = System.currentTimeMillis() - prefs.getLong("cachedWeatherSavedAt", 0L)
        val cacheUnit = prefs.getString("cachedWeatherUnit", "fahrenheit")
        val cacheIsFresh = cacheUnit == unit && cacheAgeMs in 0 until CacheFreshMs && previousState is WeatherState.Ready

        val home = homePlace
        val lastDevice = prefs.savedPlace("lastDevice")
        val seedPlace = home ?: lastDevice
        val seedLat = seedPlace?.latitude ?: DefaultLatitude
        val seedLon = seedPlace?.longitude ?: DefaultLongitude
        val seedLabel = seedPlace?.title?.lowercase() ?: "boston"
        val seedFallback = seedPlace == null

        if (cacheIsFresh && refreshNonce == 0) {
            if (home == null && hasLocation) {
                val point = context.bestCurrentPoint()
                if (point != null) {
                    val provisional = lastDevice?.title?.lowercase() ?: seedLabel
                    prefs.edit().putPlace("lastDevice", Place(provisional, point.latitude, point.longitude)).apply()
                    if (lastDevice == null || distanceMeters(lastDevice.latitude, lastDevice.longitude, point.latitude, point.longitude) > 2_000f) {
                        weatherState = runCatching {
                            fetchWeather(point.latitude, point.longitude, fallback = false, locationLabel = provisional, temperatureUnit = temperatureUnit, cachePrefs = prefs)
                        }.getOrElse { previousState }
                    }
                    val label = context.locationLabel(point.latitude, point.longitude) ?: provisional
                    if (label != provisional) {
                        prefs.edit().putPlace("lastDevice", Place(label, point.latitude, point.longitude)).apply()
                        if (weatherState is WeatherState.Ready) {
                            val ready = weatherState as WeatherState.Ready
                            weatherState = ready.copy(forecast = ready.forecast.copy(locationLabel = label))
                        }
                    }
                }
            }
            return@LaunchedEffect
        }

        if (previousState !is WeatherState.Ready) {
            weatherState = WeatherState.Loading("loading weather")
        }

        coroutineScope {
            val immediateFetch = async {
                runCatching {
                    fetchWeather(seedLat, seedLon, seedFallback, seedLabel, temperatureUnit = temperatureUnit, cachePrefs = prefs)
                }
            }
            val locationFetch = async {
                if (home == null && hasLocation) context.bestCurrentPoint() else null
            }

            val immediate = immediateFetch.await()
            weatherState = immediate.getOrElse { error ->
                if (previousState is WeatherState.Ready) previousState
                else WeatherState.Error(error.message ?: "weather unavailable")
            }

            val point = locationFetch.await()
            if (point != null && home == null) {
                val movedFar = lastDevice == null || distanceMeters(
                    lastDevice.latitude,
                    lastDevice.longitude,
                    point.latitude,
                    point.longitude
                ) > 2_000f
                val provisional = lastDevice?.title?.lowercase() ?: "current location"
                prefs.edit().putPlace("lastDevice", Place(provisional, point.latitude, point.longitude)).apply()
                if (movedFar || seedFallback) {
                    weatherState = runCatching {
                        fetchWeather(point.latitude, point.longitude, fallback = false, locationLabel = provisional, temperatureUnit = temperatureUnit, cachePrefs = prefs)
                    }.getOrElse { weatherState }
                }
                val label = context.locationLabel(point.latitude, point.longitude) ?: provisional
                if (label != provisional) {
                    prefs.edit().putPlace("lastDevice", Place(label, point.latitude, point.longitude)).apply()
                    if (weatherState is WeatherState.Ready) {
                        val ready = weatherState as WeatherState.Ready
                        weatherState = ready.copy(forecast = ready.forecast.copy(locationLabel = label))
                    }
                }
            }
        }
    }

    WeatherScreen(
        weatherState = weatherState,
        allowBackground = allowBackground,
        homePlace = homePlace,
        temperatureUnit = temperatureUnit,
        showIntro = showIntro,
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
    allowBackground: Boolean,
    homePlace: Place?,
    temperatureUnit: String,
    showIntro: Boolean,
    onRetry: () -> Unit,
    onAllowBackgroundChange: (Boolean) -> Unit,
    onHomePlaceChange: (Place?) -> Unit,
    onTemperatureUnitChange: (String) -> Unit,
    onIntroDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var panePos by remember { mutableStateOf(1f) }
    var verticalPos by remember { mutableStateOf(0f) }
    var dayPos by remember { mutableStateOf(0f) }
    var pullPx by remember { mutableStateOf(0f) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var visibleDay by remember { mutableStateOf<LocalDate?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var dragAxis by remember { mutableStateOf(DragAxis.None) }
    var dragMode by remember { mutableStateOf(DragMode.None) }
    var restPane by remember { mutableStateOf(1f) }
    var restVertical by remember { mutableStateOf(0f) }
    var restDay by remember { mutableStateOf(0f) }
    var animationJob by remember { mutableStateOf<Job?>(null) }
    val weatherStateRef = rememberUpdatedState(weatherState)
    val onRetryRef = rememberUpdatedState(onRetry)

    fun runAnim(block: suspend () -> Unit) {
        animationJob?.cancel()
        animationJob = scope.launch {
            try {
                block()
            } catch (_: CancellationException) {
            }
        }
    }

    fun goToPane(target: Float) {
        val start = panePos
        runAnim { animateFloat(start, target) { panePos = it } }
    }

    fun goToVertical(target: Float) {
        val start = verticalPos
        runAnim { animateFloat(start, target) { verticalPos = it } }
    }

    fun openDay(date: LocalDate) {
        selectedDay = date
        visibleDay = date
        val start = dayPos
        runAnim { animateFloat(start, 1f) { dayPos = it } }
    }

    fun closeDay() {
        val start = dayPos
        runAnim {
            animateFloat(start, 0f) { dayPos = it }
            selectedDay = null
            visibleDay = null
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .clipToBounds()
    ) {
        val pageWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val pageHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val headerLimit = pageHeight * 0.45f

        DisposableEffect(pageWidth, pageHeight) {
            GestureRouter.onDown = {
                animationJob?.cancel()
                dragAxis = DragAxis.None
                dragMode = DragMode.None
            }
            GestureRouter.onDrag = drag@{ dx, dy, startY ->
                if (dragAxis == DragAxis.None) {
                    if (abs(dx) < DragSlopPx && abs(dy) < DragSlopPx) return@drag
                    restPane = panePos
                    restVertical = verticalPos
                    restDay = dayPos
                    dragAxis = if (abs(dx) > abs(dy)) DragAxis.Horizontal else DragAxis.Vertical
                    val onHome = restPane > 0.5f && restPane < 1.5f
                    dragMode = if (dragAxis == DragAxis.Horizontal) {
                        when {
                            onHome && restVertical > 0.5f && visibleDay != null && restDay > 0.05f -> DragMode.DayBack
                            onHome && restVertical > 0.5f -> DragMode.TenDayBack
                            onHome && restVertical < 0.5f && startY > headerLimit && weatherStateRef.value is WeatherState.Ready -> DragMode.None
                            else -> DragMode.Pane
                        }
                    } else {
                        if (onHome && restVertical < 0.5f && visibleDay == null) DragMode.VerticalHome else DragMode.None
                    }
                }

                when (dragMode) {
                    DragMode.Pane -> {
                        val min = (restPane - 1f).coerceAtLeast(0f)
                        val max = (restPane + 1f).coerceAtMost(2f)
                        panePos = (restPane - dx / pageWidth).coerceIn(min, max)
                    }
                    DragMode.VerticalHome -> {
                        if (dy > 0f && restVertical < 0.02f) {
                            pullPx = (dy * 0.28f).coerceIn(0f, 110f)
                            verticalPos = 0f
                        } else {
                            pullPx = 0f
                            verticalPos = (restVertical - dy / pageHeight).coerceIn(0f, 1f)
                        }
                    }
                    DragMode.DayBack -> {
                        dayPos = (1f - (dx / pageWidth).coerceAtLeast(0f)).coerceIn(0f, 1f)
                    }
                    DragMode.TenDayBack -> {
                        verticalPos = (1f - (dx / pageWidth).coerceAtLeast(0f)).coerceIn(0f, 1f)
                    }
                    DragMode.None -> Unit
                }
            }
            GestureRouter.onRelease = { dx, dy, _ ->
                val mode = dragMode
                dragAxis = DragAxis.None
                dragMode = DragMode.None
                when (mode) {
                    DragMode.Pane -> {
                        val target = settleStep(restPane, panePos, 0f, 2f)
                        goToPane(target)
                    }
                    DragMode.VerticalHome -> {
                        val shouldRefresh = pullPx > 52f || dy > 150f
                        val pullStart = pullPx
                        val verticalTarget = settleStep(restVertical, verticalPos, 0f, 1f)
                        if (shouldRefresh && !refreshing) {
                            refreshing = true
                            onRetryRef.value()
                        }
                        runAnim {
                            try {
                                animateFloat(pullStart, 0f) { pullPx = it }
                                animateFloat(verticalPos, verticalTarget) { verticalPos = it }
                            } finally {
                                refreshing = false
                            }
                        }
                    }
                    DragMode.DayBack -> {
                        if (dayPos < 1f - SettleThreshold) closeDay() else runAnim { animateFloat(dayPos, 1f) { dayPos = it } }
                    }
                    DragMode.TenDayBack -> {
                        goToVertical(if (verticalPos < 1f - SettleThreshold) 0f else 1f)
                    }
                    DragMode.None -> {
                        val paneTarget = panePos.roundToInt().toFloat().coerceIn(0f, 2f)
                        val verticalTarget = if (verticalPos >= 0.5f) 1f else 0f
                        val dayTarget = when {
                            dayPos >= 0.5f -> 1f
                            dayPos > 0.02f -> 0f
                            else -> dayPos
                        }
                        runAnim {
                            animateFloat(panePos, paneTarget) { panePos = it }
                            animateFloat(verticalPos, verticalTarget) { verticalPos = it }
                            animateFloat(dayPos, dayTarget) { dayPos = it }
                            if (dayTarget == 0f && visibleDay != null) {
                                selectedDay = null
                                visibleDay = null
                            }
                        }
                    }
                }
            }
            GestureRouter.onCancel = {
                val mode = dragMode
                dragAxis = DragAxis.None
                dragMode = DragMode.None
                when (mode) {
                    DragMode.Pane -> goToPane(restPane)
                    DragMode.VerticalHome -> {
                        val pullStart = pullPx
                        runAnim {
                            animateFloat(pullStart, 0f) { pullPx = it }
                            animateFloat(verticalPos, restVertical) { verticalPos = it }
                        }
                    }
                    DragMode.DayBack -> runAnim { animateFloat(dayPos, restDay) { dayPos = it } }
                    DragMode.TenDayBack -> goToVertical(restVertical)
                    DragMode.None -> Unit
                }
            }
            onDispose {
                GestureRouter.onDown = null
                GestureRouter.onDrag = null
                GestureRouter.onRelease = null
                GestureRouter.onCancel = null
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(((0f - panePos) * pageWidth).roundToInt(), 0) }
                .padding(horizontal = 28.dp)
        ) {
            SearchPane(temperatureUnit = temperatureUnit, onClose = { goToPane(1f) })
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(((1f - panePos) * pageWidth).roundToInt(), 0) }
                .padding(horizontal = 44.dp, vertical = 22.dp)
                .clipToBounds()
        ) {
            when (weatherState) {
                is WeatherState.Loading -> CenterMessage(weatherState.label)
                is WeatherState.Error -> CenterMessage("weather unavailable\n${weatherState.message}\ntap to retry", onRetry)
                is WeatherState.Ready -> ForecastPager(
                    forecast = weatherState.forecast,
                    verticalPos = verticalPos,
                    dayPos = dayPos,
                    pullPx = pullPx,
                    refreshing = refreshing,
                    visibleDay = visibleDay,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    onSelectDay = ::openDay,
                    onBackToHome = { goToVertical(0f) },
                    onBackFromDay = ::closeDay
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(((2f - panePos) * pageWidth).roundToInt(), 0) }
                .padding(horizontal = 28.dp)
        ) {
            SettingsPane(
                allowBackground = allowBackground,
                homePlace = homePlace,
                temperatureUnit = temperatureUnit,
                onAllowBackgroundChange = onAllowBackgroundChange,
                onHomePlaceChange = onHomePlaceChange,
                onTemperatureUnitChange = onTemperatureUnitChange,
                onClose = { goToPane(1f) }
            )
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
            TextLine("swipe up for 10 day", size = 18)
            Spacer(Modifier.height(12.dp))
            TextLine("enjoy your day!", size = 18)
            Spacer(Modifier.height(26.dp))
            TextLine("tap anywhere", size = 12, color = Rule)
        }
    }
}

@Composable
private fun ForecastPager(
    forecast: Forecast,
    verticalPos: Float,
    dayPos: Float,
    pullPx: Float,
    refreshing: Boolean,
    visibleDay: LocalDate?,
    pageWidth: Float,
    pageHeight: Float,
    onSelectDay: (LocalDate) -> Unit,
    onBackToHome: () -> Unit,
    onBackFromDay: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, ((0f - verticalPos) * pageHeight).roundToInt() + pullPx.roundToInt()) },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PullDots(pullPx = pullPx, refreshing = refreshing)
            CurrentAndHourlyPage(forecast)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, ((1f - verticalPos) * pageHeight).roundToInt()) }
                .background(Paper)
        ) {
            TenDayPage(
                forecast = forecast,
                scrollEnabled = verticalPos > 0.97f && dayPos < 0.03f,
                onSelectDay = onSelectDay,
                onBackToHome = onBackToHome
            )
            if (visibleDay != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(((1f - dayPos) * pageWidth).roundToInt(), 0) }
                        .background(Paper)
                ) {
                    DayHourlyPage(forecast, visibleDay, dayPos > 0.97f, onBackFromDay)
                }
            }
        }
    }
}

@Composable
private fun CurrentAndHourlyPage(forecast: Forecast, showTenDayHint: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(22.dp))
        val displayCode = forecast.current.displayCode
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherGlyph(displayCode, Modifier.size(66.dp))
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
        HourlyStrip(forecast.hours, forecast.unitSymbol)
        if (showTenDayHint) {
            Spacer(Modifier.height(28.dp))
            TextLine("swipe up for 10 day", size = 12, color = Rule)
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun TenDayPage(
    forecast: Forecast,
    scrollEnabled: Boolean,
    onSelectDay: (LocalDate) -> Unit,
    onBackToHome: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopArrow(onBackToHome)
        TextLine("10 day", size = 12, color = Rule)
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            userScrollEnabled = scrollEnabled
        ) {
            items(forecast.days.take(10), key = { it.date.toString() }) { day ->
                ForecastRow(
                    left = formatDay(day.date),
                    code = day.code,
                    right = "${formatTemp(day.high, forecast.unitSymbol)} / ${formatTemp(day.low, forecast.unitSymbol)}",
                    onClick = { onSelectDay(day.date) }
                )
            }
            item {
                Spacer(Modifier.height(18.dp))
                TextLine("tap a day for hourly", size = 12, color = Rule)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DayHourlyPage(forecast: Forecast, date: LocalDate, scrollEnabled: Boolean, onBack: () -> Unit) {
    val day = forecast.days.firstOrNull { it.date == date }
    val hours = forecast.allHours.filter { it.time.toLocalDate() == date }
    val now = LocalDateTime.now()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopArrow(onBack)
        TextLine(formatDay(date).lowercase(), size = 18)
        if (day != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WeatherGlyph(day.code, Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                TextLine(
                    "${formatTemp(day.high, forecast.unitSymbol)} / ${formatTemp(day.low, forecast.unitSymbol)}",
                    size = 16
                )
            }
            Spacer(Modifier.height(4.dp))
            TextLine(weatherLabel(day.code), size = 13, color = Rule)
        }
        Spacer(Modifier.height(14.dp))
        if (hours.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TextLine("no hourly data", size = 14, color = Rule)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                userScrollEnabled = scrollEnabled
            ) {
                items(hours, key = { it.time.toString() }) { hour ->
                    val isNow = date == now.toLocalDate() && hour.time.hour == now.hour
                    ForecastRow(
                        left = formatHour(hour.time).lowercase(),
                        code = hour.code,
                        right = formatTemp(hour.temperature, forecast.unitSymbol),
                        emphasized = isNow
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun SearchPane(temperatureUnit: String, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var places by remember { mutableStateOf(emptyList<Place>()) }
    var selectedPlace by remember { mutableStateOf<Place?>(null) }
    var visiblePlace by remember { mutableStateOf<Place?>(null) }
    var selectedState by remember { mutableStateOf<WeatherState?>(null) }
    var placePos by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    fun openPlace(place: Place) {
        selectedPlace = place
        visiblePlace = place
        val start = placePos
        scope.launch {
            try {
                animateFloat(start, 1f) { placePos = it }
            } catch (_: CancellationException) {
            }
        }
    }

    fun closePlace() {
        selectedPlace = null
        val start = placePos
        scope.launch {
            try {
                animateFloat(start, 0f) { placePos = it }
                visiblePlace = null
                selectedState = null
            } catch (_: CancellationException) {
            }
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
        selectedState = WeatherState.Loading("loading weather")
        selectedState = runCatching {
            fetchWeather(
                place.latitude,
                place.longitude,
                fallback = false,
                locationLabel = place.title.lowercase(),
                temperatureUnit = temperatureUnit
            )
        }.getOrElse { WeatherState.Error(it.message ?: "weather unavailable") }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset((-placePos * width).roundToInt(), 0) },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopArrow(onClose)
            SearchField(value = query, onValueChange = { query = it }, placeholder = "search place")
            Spacer(Modifier.height(22.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(places, key = { "${it.title}-${it.latitude}-${it.longitude}" }) { place ->
                    PlaceRow(place = place) { openPlace(place) }
                }
            }
        }

        if (visiblePlace != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(((1f - placePos) * width).roundToInt(), 0) }
                    .background(Paper),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TopArrow { closePlace() }
                TextLine(visiblePlace!!.title.lowercase(), size = 18)
                Spacer(Modifier.height(18.dp))
                when (val state = selectedState) {
                    is WeatherState.Ready -> SearchForecast(state.forecast)
                    is WeatherState.Error -> CenterMessage("weather unavailable")
                    else -> CenterMessage("loading weather")
                }
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
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(places, key = { "${it.title}-${it.latitude}-${it.longitude}" }) { place ->
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
private fun SearchForecast(forecast: Forecast) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CurrentAndHourlyPage(forecast, showTenDayHint = false)
        Spacer(Modifier.height(28.dp))
        TextLine("10 day", size = 12, color = Rule)
        Spacer(Modifier.height(10.dp))
        forecast.days.take(10).forEach { day ->
            ForecastRow(
                formatDay(day.date),
                day.code,
                "${formatTemp(day.high, forecast.unitSymbol)} / ${formatTemp(day.low, forecast.unitSymbol)}"
            )
        }
        Spacer(Modifier.height(20.dp))
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
private fun PullDots(pullPx: Float, refreshing: Boolean) {
    val visible = refreshing || pullPx > 6f
    val count = when {
        refreshing -> 3
        pullPx > 64f -> 3
        pullPx > 32f -> 2
        else -> 1
    }
    Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) {
        if (visible) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun HourlyStrip(hours: List<HourForecast>, unitSymbol: String) {
    if (hours.isEmpty()) {
        TextLine("no hourly data", size = 13, color = Rule)
        return
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(end = 8.dp)
    ) {
        itemsIndexed(hours, key = { _, hour -> hour.time.toString() }) { index, hour ->
            HourCard(
                hour = hour,
                isCurrent = index == 0,
                unitSymbol = unitSymbol,
                modifier = Modifier.width(72.dp)
            )
        }
    }
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
        WeatherGlyph(hour.code, Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        TextLine(formatTemp(hour.temperature, unitSymbol), size = 16)
    }
}

@Composable
private fun ForecastRow(
    left: String,
    code: Int,
    right: String,
    onClick: (() -> Unit)? = null,
    emphasized: Boolean = false
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .height(52.dp)
        .border(if (emphasized) 2.dp else 1.dp, if (emphasized) Ink else Rule)
        .then(if (onClick == null) Modifier else Modifier.clickable { onClick() })
        .padding(horizontal = 20.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextLine(left.lowercase(), size = 17, modifier = Modifier.weight(1f), align = TextAlign.Start)
        WeatherGlyph(code, Modifier.size(30.dp))
        TextLine(right, size = 17, modifier = Modifier.weight(1f), align = TextAlign.End)
    }
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
private fun WeatherGlyph(code: Int, modifier: Modifier = Modifier) {
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

private fun openMeteoConnection(url: String): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = HttpConnectTimeoutMs
    connection.readTimeout = HttpReadTimeoutMs
    connection.setRequestProperty("Accept", "application/json")
    return connection
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
    val connection = openMeteoConnection(url)

    val body = connection.inputStream.bufferedReader().use { it.readText() }
    cachePrefs?.edit()
        ?.putString("cachedWeatherBody", body)
        ?.putBoolean("cachedWeatherFallback", fallback)
        ?.putString("cachedWeatherLocation", locationLabel)
        ?.putString("cachedWeatherUnit", unit)
        ?.putLong("cachedWeatherSavedAt", System.currentTimeMillis())
        ?.apply()
    parseWeatherBody(body, fallback, locationLabel, unit)
}

private fun parseWeatherBody(
    body: String,
    @Suppress("UNUSED_PARAMETER") fallback: Boolean,
    locationLabel: String,
    temperatureUnit: String,
    referenceNow: LocalDateTime? = null
): WeatherState.Ready {
    val json = JSONObject(body)
    val currentJson = json.getJSONObject("current")
    val hourlyJson = json.getJSONObject("hourly")
    val dailyJson = json.getJSONObject("daily")

    val apiNow = LocalDateTime.parse(currentJson.getString("time"))
    val now = referenceNow ?: apiNow
    val hours = mutableListOf<HourForecast>()
    val allHours = mutableListOf<HourForecast>()
    val hourTimes = hourlyJson.getJSONArray("time")
    val hourTemps = hourlyJson.getJSONArray("temperature_2m")
    val hourCodes = hourlyJson.getJSONArray("weather_code")
    for (i in 0 until hourTimes.length()) {
        val time = LocalDateTime.parse(hourTimes.getString(i))
        val hour = HourForecast(time, hourTemps.getDouble(i), hourCodes.getInt(i))
        allHours += hour
        if (!time.isBefore(now) && hours.size < 16) {
            hours += hour
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
            locationLabel = locationLabel,
            unitSymbol = if (temperatureUnit == "celsius") "°C" else "°F"
        )
    )
}

private suspend fun searchPlaces(query: String): List<Place> = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=25&language=en&format=json"
    val connection = openMeteoConnection(url)
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

private fun SharedPreferences.displayCachedWeatherState(): WeatherState.Ready? {
    val savedAt = getLong("cachedWeatherSavedAt", 0L)
    if (savedAt <= 0L) return null
    val ageMs = System.currentTimeMillis() - savedAt
    if (ageMs !in 0 until CacheDisplayMaxMs) return null

    val body = getString("cachedWeatherBody", null) ?: return null
    val fallback = getBoolean("cachedWeatherFallback", false)
    val location = getString("cachedWeatherLocation", null) ?: return null
    val unit = getString("cachedWeatherUnit", "fahrenheit") ?: "fahrenheit"
    val parsed = runCatching {
        parseWeatherBody(body, fallback, location, unit, referenceNow = LocalDateTime.now())
    }.getOrNull() ?: return null

    val today = LocalDate.now()
    val coversToday = parsed.forecast.days.any { !it.date.isBefore(today) }
    if (!coversToday || parsed.forecast.hours.isEmpty()) return null
    return parsed
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
        withTimeoutOrNull(LocationTimeoutMs) {
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
    val locationLabel: String,
    val unitSymbol: String
)

private data class CurrentForecast(val temperature: Double, val code: Int, val windSpeed: Double) {
    val displayCode: Int = if (windSpeed >= 35.0 && code in 0..3) -1 else code
}

private data class HourForecast(val time: LocalDateTime, val temperature: Double, val code: Int)
private data class DayForecast(val date: LocalDate, val high: Double, val low: Double, val code: Int)
private data class Place(val title: String, val latitude: Double, val longitude: Double)

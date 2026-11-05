package com.classmemory.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.core.content.ContextCompat
import com.classmemory.app.service.RecordingService
import com.classmemory.app.benchmark.DebugBenchmarkActivity
import com.classmemory.app.benchmark.DebugBenchmarkService
import com.classmemory.app.i18n.AppLanguage
import com.classmemory.app.i18n.tr
import com.classmemory.app.ui.*
import com.classmemory.app.ui.theme.ClassMemoryTheme
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguage.initialize(this)
        setContent {
          ClassMemoryTheme {
            var page by rememberSaveable(stateSaver = screenSaver) { mutableStateOf<Screen>(Screen.Home) }
            val reviewStateHolder = rememberSaveableStateHolder()
            var openHotwordManager by rememberSaveable { mutableStateOf(false) }
            var permissionMessage by remember { mutableStateOf<String?>(null) }
            var startupError by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                if (RecordingService.state.value.sessionId != null) {
                    page = Screen.Recording
                } else if (!DebugBenchmarkService.state.value.running) runCatching {
                    (application as ClassMemoryApp).repository.recoverAbandonedSessions()
                }.onFailure { startupError = tr("无法恢复上次课堂记录：${it.message ?: "数据库初始化失败"}", "Couldn't restore the previous class: ${it.message ?: "Database initialization failed"}") }
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                if (result[Manifest.permission.RECORD_AUDIO] == true) {
                    permissionMessage = null
                    runCatching { RecordingService.start(this); page = Screen.Recording }
                        .onFailure { permissionMessage = tr("无法启动课堂记录服务：${it.message ?: "系统拒绝启动"}", "Couldn't start the recording service: ${it.message ?: "The system refused to start it"}") }
                } else permissionMessage = tr("未授予麦克风权限，无法开始记录。请在系统设置中允许麦克风。", "Microphone permission is required to start recording. Allow it in system settings.")
            }
            LaunchedEffect(permissionMessage) {
                permissionMessage?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            }
            LaunchedEffect(startupError) {
                startupError?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            }
            BackHandler(enabled = page is Screen.Detail || page is Screen.Settings) {
                val detail = page as? Screen.Detail
                page = detail?.returnReviewId?.let { Screen.Review(it) } ?: Screen.Home
            }
            when (val current = page) {
                Screen.Home -> HomeScreen(
                    repo = (application as ClassMemoryApp).repository,
                    onStart = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            permission.launch(buildList { add(Manifest.permission.RECORD_AUDIO); if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray())
                        } else runCatching { RecordingService.start(this); page = Screen.Recording }
                            .onFailure { permissionMessage = tr("无法启动课堂记录服务：${it.message ?: "系统拒绝启动"}", "Couldn't start the recording service: ${it.message ?: "The system refused to start it"}") }
                    },
                    onImport = {
                        runCatching {
                            startActivity(Intent(this, DebugBenchmarkActivity::class.java))
                        }.onFailure {
                            Toast.makeText(this, tr("当前安装包不支持导入音频", "Audio import is not supported in this build"), Toast.LENGTH_LONG).show()
                        }
                    },
                    showImport = true,
                    onDetail = { page = Screen.Detail(it) },
                    onSettings = { page = Screen.Settings },
                    onManageHotwords = { openHotwordManager = true; page = Screen.Settings },
                    onLanguageSelected = { english ->
                        AppLanguage.saveEnglish(this, english)
                        window.decorView.post {
                            if (!isFinishing && !isDestroyed) recreate()
                        }
                    },
                    reviewRuntime = (application as ClassMemoryApp).reviewRuntime,
                    onReview = { page = Screen.Review(it) },
                )
                Screen.Recording -> RecordingScreen(
                    repo = (application as ClassMemoryApp).repository,
                    onStop = { RecordingService.stop(this) },
                    onFinished = { page = Screen.Home },
                    onBoardPhotoCaptured = { sessionId, capturedAtMs, relativePath ->
                        (application as ClassMemoryApp).repository.addBoardPhoto(sessionId, capturedAtMs, relativePath)
                    },
                )
                is Screen.Detail -> SessionDetailScreen(
                    (application as ClassMemoryApp).repository, current.id,
                    onReview = { page = Screen.Review(current.id) },
                    initialTimelineKey = current.timelineKey, initialPhotoId = current.photoId,
                    onBack = { page = current.returnReviewId?.let { Screen.Review(it) } ?: Screen.Home },
                )
                is Screen.Review -> reviewStateHolder.SaveableStateProvider(current.id) { ReviewScreen(
                    repo = (application as ClassMemoryApp).repository,
                    runtime = (application as ClassMemoryApp).reviewRuntime,
                    sessionId = current.id,
                    onBack = { page = Screen.Detail(current.id) },
                    onSource = { source -> page = Screen.Detail(source.sessionId, "transcript:${source.segmentId}", returnReviewId = current.id) },
                    onTranscript = { page = Screen.Detail(current.id, returnReviewId = current.id) },
                    onSettings = { page = Screen.ReviewSettings(current.id) },
                ) }
                is Screen.ReviewSettings -> ReviewConnectionSettings(onBack = {
                    page = current.returnReviewId?.let { Screen.Review(it) } ?: Screen.Settings
                })
                Screen.Settings -> SettingsScreen(
                    (application as ClassMemoryApp).repository,
                    onBack = { page = Screen.Home },
                    openHotwordManager = openHotwordManager,
                    onHotwordManagerOpened = { openHotwordManager = false },
                    onOpenClass = { page = Screen.Detail(it) },
                    onReviewSettings = { page = Screen.ReviewSettings(null) },
                )
            }
          }
        }
    }
}

sealed interface Screen {
    data object Home : Screen
    data object Recording : Screen
    data object Settings : Screen
    data class Detail(val id: String, val timelineKey: String? = null, val photoId: String? = null, val returnReviewId: String? = null) : Screen
    data class Review(val id: String) : Screen
    data class ReviewSettings(val returnReviewId: String?) : Screen
}

private val screenSaver = Saver<Screen, String>(
    save = { screen -> when (screen) {
        Screen.Home -> "home"
        Screen.Recording -> "home"
        Screen.Settings -> "settings"
        is Screen.Detail -> "detail2:" + JSONObject().put("id", screen.id).put("key", screen.timelineKey).put("photo", screen.photoId).put("review", screen.returnReviewId).toString()
        is Screen.Review -> "review:${screen.id}"
        is Screen.ReviewSettings -> "reviewsettings:${screen.returnReviewId.orEmpty()}"
    } },
    restore = { route -> when {
        route == "home" -> Screen.Home
        route == "recording" -> Screen.Home
        route == "settings" -> Screen.Settings
        route.startsWith("detail:") -> Screen.Detail(route.removePrefix("detail:"))
        route.startsWith("detail2:") -> runCatching {
            val obj = JSONObject(route.removePrefix("detail2:"))
            Screen.Detail(obj.getString("id"), obj.optString("key").ifBlank { null }, obj.optString("photo").ifBlank { null }, obj.optString("review").ifBlank { null })
        }.getOrDefault(Screen.Home)
        route.startsWith("review:") -> Screen.Review(route.removePrefix("review:"))
        route.startsWith("reviewsettings:") -> Screen.ReviewSettings(route.removePrefix("reviewsettings:").ifBlank { null })
        else -> Screen.Home
    } },
)

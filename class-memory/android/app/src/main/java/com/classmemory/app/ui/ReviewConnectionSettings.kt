package com.classmemory.app.ui

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.classmemory.app.i18n.tr
import com.classmemory.app.review.ReviewConnection
import com.classmemory.app.review.ReviewConnectionMode
import com.classmemory.app.review.ReviewConnectionPreferences
import com.classmemory.app.ui.theme.ClassMemoryColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReviewConnectionSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { ReviewConnectionPreferences.loadWithStatus(context) }
    var mode by remember { mutableStateOf(initial.connection.mode) }
    var url by remember { mutableStateOf(initial.connection.baseUrl) }
    var token by remember { mutableStateOf(initial.connection.accessToken) }
    var apiKey by remember { mutableStateOf("") }
    var savedApiKey by remember { mutableStateOf(initial.connection.apiKey) }
    var model by remember { mutableStateOf(initial.connection.model) }
    var credentialWarning by remember { mutableStateOf(initial.credentialWarning) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
    }
    DisposableEffect(activity) {
        val window = activity?.window
        val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) == WindowManager.LayoutParams.FLAG_SECURE
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    BackHandler(enabled = saving) { }
    BackHandler(enabled = !saving, onBack = onBack)
    Scaffold(containerColor = ClassMemoryColors.current.background, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !saving) { Text(tr("返回", "Back")) }
            Text(tr("复习服务设置", "Review service settings"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ConnectionChoice(
                tr("手机直连 DeepSeek", "Connect to DeepSeek on this phone"),
                mode == ReviewConnectionMode.DIRECT_DEEPSEEK, !saving,
            ) { mode = ReviewConnectionMode.DIRECT_DEEPSEEK; message = null }
            ConnectionChoice(
                tr("自建服务", "Self-hosted service"),
                mode == ReviewConnectionMode.PROXY, !saving,
            ) { mode = ReviewConnectionMode.PROXY; message = null }
            if (mode == ReviewConnectionMode.DIRECT_DEEPSEEK) {
                Text(tr("填入自己的 DeepSeek API Key，手机即可联网生成复盘和问答。模型调用费用由你的 DeepSeek 账户承担。", "Enter your DeepSeek API key to generate reviews and answers on this phone. Requests are billed to your DeepSeek account."))
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it; message = null },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving,
                    label = { Text(if (savedApiKey.isBlank()) "DeepSeek API Key" else tr("API Key 已保存，填写可替换", "API key saved — enter a replacement")) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
                Text(tr("API Key 使用此手机的安全密钥加密保存。", "The API key is encrypted using this device's secure key."))
                credentialWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(tr("模型", "Model"), style = MaterialTheme.typography.titleSmall)
                for (choice in listOf("deepseek-flash", "deepseek-v4-pro")) {
                    ConnectionChoice(choice, model == choice, !saving) { model = choice; message = null }
                }
                TextButton(
                    enabled = !saving && (savedApiKey.isNotBlank() || apiKey.isNotBlank() || credentialWarning != null),
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { ReviewConnectionPreferences.clearApiKey(context) }
                                apiKey = ""
                                savedApiKey = ""
                                credentialWarning = null
                                message = tr("API Key 已清除", "API key cleared")
                            } catch (_: Exception) {
                                message = tr("清除失败，请重试", "Could not clear the key. Try again.")
                            } finally { saving = false }
                        }
                    },
                ) { Text(tr("清除 API Key", "Clear API key")) }
            } else {
                Text(tr("填写自己的课堂分析服务地址。留空时暂停该服务的在线分析。", "Enter your classroom analysis service URL. Leave it empty to disable analysis through this service."))
                OutlinedTextField(
                    value = url, onValueChange = { url = it; message = null },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(tr("HTTPS 服务地址", "HTTPS service URL")) },
                    placeholder = { Text("https://your-service.example") }, enabled = !saving,
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it; message = null },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(tr("代理访问令牌（可选）", "Proxy access token (optional)")) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    enabled = !saving,
                )
                Text(tr("此模式由你的服务端配置模型供应商密钥。", "Configure the model provider key on your server for this mode."))
            }
            Text(tr("发送前可预览课堂资料。音频和板书图片保留在本地。", "Preview classroom material before sending it. Audio and board photos stay on the device."))
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Button(enabled = !saving, onClick = {
                saving = true
                val connection = ReviewConnection(
                    baseUrl = url.trim(), accessToken = token, mode = mode,
                    apiKey = apiKey.ifBlank { savedApiKey }, model = model,
                )
                scope.launch {
                    try {
                        connection.validate()
                        withContext(Dispatchers.IO) { ReviewConnectionPreferences.save(context, connection) }
                        savedApiKey = connection.apiKey
                        apiKey = ""
                        credentialWarning = null
                        message = tr("设置已保存", "Settings saved")
                    } catch (error: IllegalArgumentException) {
                        message = error.message ?: tr("请检查设置", "Check the settings")
                    } catch (_: Exception) {
                        message = tr("无法安全保存设置，请清除旧密钥后重试", "Could not securely save settings. Clear the old key and try again.")
                    } finally { saving = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) tr("保存中…", "Saving…") else tr("保存", "Save")) }
        }
    }
}

@Composable
private fun ConnectionChoice(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

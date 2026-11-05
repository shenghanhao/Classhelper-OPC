package com.classmemory.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.classmemory.app.i18n.tr
import com.classmemory.app.review.ReviewConnection
import com.classmemory.app.review.ReviewConnectionPreferences
import com.classmemory.app.ui.theme.ClassMemoryColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReviewConnectionSettings(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val initial = remember { ReviewConnectionPreferences.load(context) }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var token by remember { mutableStateOf(initial.accessToken) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = ClassMemoryColors.current.background, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(tr("返回", "Back")) }
            Text(tr("复习服务设置", "Review service settings"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(tr("填写自己的课堂分析代理地址。留空时停用在线分析，已保存的课堂和复习资料仍可查看。", "Enter your classroom analysis proxy URL. Leave it empty to disable online analysis; saved classrooms and review material remain available."))
            OutlinedTextField(value = url, onValueChange = { url = it; message = null }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(tr("HTTPS 服务地址", "HTTPS service URL")) }, placeholder = { Text("https://your-service.example") }, enabled = !saving)
            OutlinedTextField(value = token, onValueChange = { token = it; message = null }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(tr("代理访问令牌（可选）", "Proxy access token (optional)")) }, visualTransformation = PasswordVisualTransformation(), enabled = !saving)
            Text(tr("这里仅保存代理访问令牌；DeepSeek 等模型供应商的 API Key 由服务端配置。每次发送课堂内容前会展示上传预览并征求同意。", "Only a proxy access token belongs here. Configure the model provider API key on your server. Classroom content is previewed for consent before each analysis."), style = MaterialTheme.typography.bodyMedium)
            if (url.isBlank()) Text(tr("未配置：在线分析已停用", "Not configured: online analysis is disabled"), color = MaterialTheme.colorScheme.secondary)
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Button(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { ReviewConnectionPreferences.save(context, ReviewConnection(url.trim(), token.trim())) }
                        message = tr("设置已保存", "Settings saved")
                    } catch (error: Exception) { message = error.message ?: tr("保存失败", "Could not save") }
                    finally { saving = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) tr("保存中…", "Saving…") else tr("保存", "Save")) }
        }
    }
}

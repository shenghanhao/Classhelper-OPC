package com.classmemory.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.i18n.tr
import com.classmemory.app.repository.SessionRepository
import com.classmemory.app.review.ReviewPhotoRef
import kotlinx.coroutines.CancellationException

@Composable
internal fun ReviewBoardPhotos(photos: List<ReviewPhotoRef>, repo: SessionRepository, onClose: () -> Unit) {
    if (photos.isEmpty()) return
    var selectedIndex by rememberSaveable(photos.map { it.id }) { mutableIntStateOf(0) }
    val ref = photos[selectedIndex.coerceIn(photos.indices)]
    val result by produceState<Pair<String, BoardPhoto?>?>(null, ref) {
        value = null
        val photo = try { repo.getReviewBoardPhoto(ref) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        value = ref.id to photo
    }
    val loaded = result?.first == ref.id
    val photo = if (loaded) result?.second else null
    val scope = rememberCoroutineScope()
    val navigation: @Composable () -> Unit = {
        if (photos.size > 1) LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(photos, key = { _, item -> item.id }) { index, item ->
                FilterChip(
                    selected = index == selectedIndex,
                    onClick = { selectedIndex = index },
                    label = { Text("${index + 1} · ${formatBoardPhotoTime(item.atMs)}") },
                )
            }
        }
    }
    if (photo != null) BoardPhotoViewer(
        photo = photo,
        ownerScope = scope,
        deleting = false,
        deletePhoto = repo::deleteBoardPhoto,
        onDeletePending = {},
        onDeleteCompleted = {},
        onClose = onClose,
        allowDelete = false,
        photoNavigation = navigation,
    ) else AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("关联板书", "Related board photos")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                navigation()
                Text(if (loaded) tr("图片已删除或无法读取，可选择其他板书。", "This photo was deleted or is unavailable. You can select another photo.") else tr("正在加载图片…", "Loading image…"))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("关闭", "Close")) } },
    )
}

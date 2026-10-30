package com.edgevoice.agent.ui.history

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.edgevoice.agent.core.history.HistoryMemoryRecord
import com.edgevoice.agent.core.history.HistoryStatus
import com.edgevoice.agent.core.history.HistoryUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 当前一次保存/检索的证据；点按打开原照片，不把离散帧包装成录像。 */
@Composable
fun HistoryEvidenceCard(
    state: HistoryUiState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onConfirmSave: () -> Unit = {},
    onDiscardSave: () -> Unit = {},
) {
    if (state.status == HistoryStatus.IDLE && state.records.isEmpty()) return
    var selected by remember { mutableStateOf<HistoryMemoryRecord?>(null) }
    val pendingJpeg = state.pendingJpeg
    var showPendingPhoto by remember(pendingJpeg) { mutableStateOf(false) }
    val pendingImage = remember(pendingJpeg) { pendingJpeg?.let { decode(it, 480) } }
    val working = state.status == HistoryStatus.SAVING || state.status == HistoryStatus.SEARCHING ||
        state.status == HistoryStatus.COMPARING
    val title = when (state.status) {
        HistoryStatus.IDLE -> "历史证据"
        HistoryStatus.CONFIRM_SAVE -> "请确认是否保存"
        HistoryStatus.SAVING -> "正在保存记物记录"
        HistoryStatus.SAVED -> "记物记录已保存"
        HistoryStatus.SEARCHING -> "正在查找历史记录"
        HistoryStatus.COMPARING -> "正在结合历史画面回答"
        HistoryStatus.RESULT -> "找到历史记录"
        HistoryStatus.NO_MATCH -> "没有找到相关记录"
        HistoryStatus.EVIDENCE_UNAVAILABLE -> "历史照片暂不可用"
        HistoryStatus.ERROR -> "历史操作未完成"
    }
    Surface(
        modifier = modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xDD182033),
        tonalElevation = 1.dp,
    ) {
        if (state.status == HistoryStatus.CONFIRM_SAVE) {
            Column(
                Modifier.padding(if (compact) 8.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.labelMedium, color = Color(0xFFBCEAD6))
                        Text(
                            state.message ?: "点按确认后才会将这张照片存入历史记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD5DCE8),
                            maxLines = if (compact) 2 else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (pendingImage != null) {
                        Image(
                            pendingImage,
                            "待保存照片，点击放大查看",
                            Modifier.size(if (compact) 56.dp else 72.dp).clickable { showPendingPhoto = true },
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text("照片不可预览，请取消后重拍", color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDiscardSave) { Text("取消") }
                    TextButton(onClick = onConfirmSave, enabled = pendingImage != null) { Text("确认保存") }
                }
            }
        } else if (compact) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.records.isEmpty() && working) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = Color(0xFFBCEAD6), maxLines = 1)
                    state.message?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD5DCE8), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.records.take(3).forEach { record ->
                        val image = remember(record.recordId, record.jpeg) { decode(record.jpeg, 96) }
                        if (image != null) {
                            Image(
                                image,
                                "历史照片：${record.objectName}，点击查看",
                                Modifier.size(42.dp).clickable { selected = record },
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                Modifier.size(42.dp)
                                    .background(Color(0xFF2D374B))
                                    .clickable { selected = record },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("无照片", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                            }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (working) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(title, style = MaterialTheme.typography.labelMedium, color = Color(0xFFBCEAD6))
                }
                state.message?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD5DCE8), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (state.records.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.records.take(3), key = { it.recordId }) { record ->
                            EvidenceItem(record, onClick = { selected = record })
                        }
                    }
                    Text("最后记录不代表当前位置 · 点照片查看", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9BA9BF))
                }
            }
        }
    }
    selected?.let { record -> EvidencePhotoDialog(record, onDismiss = { selected = null }) }
    if (showPendingPhoto && pendingImage != null) {
        Dialog(onDismissRequest = { showPendingPhoto = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().padding(16.dp), color = Color(0xFF151D2E), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("待保存的照片", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Image(pendingImage, "待保存照片原图", Modifier.fillMaxWidth().heightIn(max = 430.dp), contentScale = ContentScale.Fit)
                    Text("确认后才会存入历史记录。", color = Color(0xFFD5DCE8), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showPendingPhoto = false }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun EvidenceItem(record: HistoryMemoryRecord, onClick: () -> Unit) {
    Row(
        Modifier.width(244.dp).clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val image = remember(record.recordId, record.jpeg) { decode(record.jpeg, 180) }
        if (image != null) {
            Image(image, "历史照片：${record.objectName}，${timeLabel(record.capturedAtMs)}", Modifier.size(62.dp), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(62.dp).background(Color(0xFF2D374B)), contentAlignment = Alignment.Center) {
                Text("无可用照片", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(record.objectName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(timeLabel(record.capturedAtMs), style = MaterialTheme.typography.labelSmall, color = Color(0xFFB1BDD0))
            if (record.location.isNotBlank()) Text(record.location, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD5DCE8), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (record.source == "user_recorded") "位置与备注来自你的口述" else "来源：${record.source}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9BA9BF), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EvidencePhotoDialog(record: HistoryMemoryRecord, onDismiss: () -> Unit) {
    val image = remember(record.recordId, record.jpeg) { decode(record.jpeg, 1600) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), color = Color(0xFF151D2E), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(record.objectName, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("拍摄于 ${timeLabel(record.capturedAtMs)}", color = Color(0xFFB1BDD0), style = MaterialTheme.typography.bodySmall)
                if (image != null) {
                    Image(image, "历史原照片：${record.objectName}", Modifier.fillMaxWidth().heightIn(max = 430.dp), contentScale = ContentScale.Fit)
                } else {
                    Text("照片无法解码，不能据此比较当前物品", color = Color(0xFFFFB4AB))
                }
                if (record.location.isNotBlank()) Text("口述位置：${record.location}", color = Color(0xFFD5DCE8), style = MaterialTheme.typography.bodySmall)
                if (record.note.isNotBlank()) Text("备注：${record.note}", color = Color(0xFFD5DCE8), style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Text("这是当时留下的记录，不保证物品现在仍在原处。", color = Color(0xFF9BA9BF), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

private fun decode(jpeg: ByteArray, targetEdge: Int) = runCatching {
    val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, dimensions)
    if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0 || dimensions.outWidth > 4096 || dimensions.outHeight > 4096) return@runCatching null
    var sample = 1
    while (maxOf(dimensions.outWidth, dimensions.outHeight) / sample > targetEdge * 2) sample *= 2
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

private fun timeLabel(timestamp: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))

package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.local.NoteEntity
import java.io.File
import kotlin.math.roundToInt

@Composable
fun NoteAttachmentImagePreview(
    imagePath: String,
    contentDescription: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    heightDp: Int = 180,
    onClick: (() -> Unit)? = null
) {
    if (imagePath.isBlank()) return
    val imageFile = remember(imagePath) { File(imagePath) }
    val displayAccent = adaptiveAccent(accentColor)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, displayAccent.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp),
            contentAlignment = Alignment.Center
        ) {
            if (imageFile.exists()) {
                AsyncImage(
                    model = imageFile,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "ছবি পাওয়া যায়নি",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (onClick != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.58f),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ZoomIn,
                            contentDescription = "বড় করে দেখুন",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "জুম / দেখুন",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingDraggableNoteImageOverlay(
    note: NoteEntity,
    accentColor: Color,
    onOpenFullScreen: () -> Unit,
    onClose: () -> Unit
) {
    if (note.imagePath.isBlank()) return
    val imageFile = remember(note.imagePath) { File(note.imagePath) }
    val displayAccent = adaptiveAccent(accentColor)
    var expanded by remember(note.id) { mutableStateOf(false) }
    val cardWidth = if (expanded) 280.dp else 196.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        var boxW by remember { mutableStateOf(with(density) { cardWidth.toPx() }) }
        var boxH by remember { mutableStateOf(with(density) { 210.dp.toPx() }) }

        var offsetX by remember(note.id) {
            mutableStateOf(((maxW - boxW).coerceAtLeast(0f)) / 2f)
        }
        var offsetY by remember(note.id) {
            mutableStateOf(with(density) { 92.dp.toPx() }.coerceIn(0f, (maxH - boxH).coerceAtLeast(0f)))
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 10.dp,
            shadowElevation = 14.dp,
            border = BorderStroke(1.5.dp, displayAccent),
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .width(cardWidth)
                .onSizeChanged {
                    boxW = it.width.toFloat()
                    boxH = it.height.toFloat()
                    offsetX = offsetX.coerceIn(0f, (maxW - boxW).coerceAtLeast(0f))
                    offsetY = offsetY.coerceIn(0f, (maxH - boxH).coerceAtLeast(0f))
                }
                .pointerInput(note.id, maxW, maxH) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, (maxW - boxW).coerceAtLeast(0f))
                        offsetY = (offsetY + dragAmount.y).coerceIn(0f, (maxH - boxH).coerceAtLeast(0f))
                    }
                }
                .testTag("floating_image_overlay")
        ) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenWith,
                        contentDescription = "টেনে সরান",
                        tint = displayAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = note.content.ifBlank { "ভাসমান চিত্র" },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(28.dp).testTag("floating_image_resize_button")
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                            contentDescription = "ছোট বা বড় করুন",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(28.dp).testTag("close_floating_image_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "ভাসমান চিত্র বন্ধ করুন",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (expanded) 220.dp else 136.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onOpenFullScreen),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageFile.exists()) {
                        AsyncImage(
                            model = imageFile,
                            contentDescription = "ভাসমান অধ্যায়ের চিত্র",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Rounded.Image, contentDescription = null)
                    }
                }

                Text(
                    text = "টেনে স্ক্রিনের যেকোনো স্থানে রাখুন • ট্যাপ করলে বড় হবে",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun NoteImageViewerDialog(
    imagePath: String,
    title: String,
    onDismiss: () -> Unit
) {
    val imageFile = remember(imagePath) { File(imagePath) }
    var scale by remember(imagePath) { mutableFloatStateOf(1f) }
    var panOffset by remember(imagePath) { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.94f),
            contentColor = Color.White,
            modifier = Modifier
                .fillMaxSize()
                .testTag("note_image_viewer_dialog")
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(imagePath) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = newScale
                                if (newScale > 1f) {
                                    panOffset += pan
                                } else {
                                    panOffset = Offset.Zero
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (imageFile.exists()) {
                        AsyncImage(
                            model = imageFile,
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = panOffset.x,
                                    translationY = panOffset.y
                                )
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.BrokenImage, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                            Text("ছবিটি পাওয়া যায়নি", color = Color.White)
                        }
                    }
                }

                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title.ifBlank { "অধ্যায়ের চিত্র" },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (scale > 1.05f) {
                        FilledTonalButton(
                            onClick = {
                                scale = 1f
                                panOffset = Offset.Zero
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("রিসেট", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .testTag("close_image_viewer_button")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "বন্ধ করুন", tint = Color.White)
                    }
                }

                Text(
                    text = "দুই আঙুলে জুম এবং টেনে যেকোনো দিকে সরান",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                )
            }
        }
    }
}

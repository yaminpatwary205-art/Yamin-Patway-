package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File
import java.util.UUID

sealed interface RichNoteSegment {
    data class Text(val text: String) : RichNoteSegment
    data class Image(val ref: String, val file: File) : RichNoteSegment
}

sealed interface NoteEditorBlock {
    val id: String

    data class TextBlock(
        override val id: String,
        val value: TextFieldValue
    ) : NoteEditorBlock

    data class ImageBlock(
        override val id: String,
        val imageRef: String
    ) : NoteEditorBlock
}

object RichNoteCodec {
    private val IMG_REGEX = Regex("""\[\[IMG:([^\]]+)]]""")

    fun resolveImageFile(filesDir: File, ref: String): File {
        val clean = ref.trim()
        val notesDir = File(filesDir, "note_images")
        if (clean.startsWith("/")) {
            val direct = File(clean)
            if (direct.exists()) return direct
            return File(notesDir, direct.name)
        }
        return File(notesDir, clean)
    }

    fun extractImageRefs(content: String, legacyImagePath: String = ""): List<String> {
        val refs = IMG_REGEX.findAll(content)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        val legacy = legacyImagePath.trim()
        if (legacy.isNotEmpty() && legacy !in refs) {
            refs.add(legacy)
        }
        return refs
    }

    fun stripImageMarkers(content: String, fallbackIfEmpty: String = "চিত্র নোট"): String {
        val hadImage = IMG_REGEX.containsMatchIn(content)
        val stripped = IMG_REGEX.replace(content, "\n")
            .lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        return if (stripped.isBlank() && hadImage) fallbackIfEmpty else stripped
    }

    fun parseSegments(
        content: String,
        legacyImagePath: String = "",
        filesDir: File
    ): List<RichNoteSegment> {
        val result = mutableListOf<RichNoteSegment>()
        var cursor = 0
        for (match in IMG_REGEX.findAll(content)) {
            val textBefore = content.substring(cursor, match.range.first).trim('\r', '\n')
            if (textBefore.isNotBlank()) {
                result += RichNoteSegment.Text(textBefore)
            }
            val ref = match.groupValues[1].trim()
            if (ref.isNotEmpty()) {
                result += RichNoteSegment.Image(ref, resolveImageFile(filesDir, ref))
            }
            cursor = match.range.last + 1
        }
        val tail = content.substring(cursor).trim('\r', '\n')
        if (tail.isNotBlank() && !(tail == "চিত্র নোট" && result.any { it is RichNoteSegment.Image })) {
            result += RichNoteSegment.Text(tail)
        }
        val legacy = legacyImagePath.trim()
        if (legacy.isNotEmpty() && result.none { it is RichNoteSegment.Image && it.ref == legacy }) {
            result += RichNoteSegment.Image(legacy, resolveImageFile(filesDir, legacy))
        }
        return result
    }

    fun toEditorBlocks(content: String, legacyImagePath: String = ""): List<NoteEditorBlock> {
        val blocks = mutableListOf<NoteEditorBlock>()
        var cursor = 0
        for (match in IMG_REGEX.findAll(content)) {
            val before = content.substring(cursor, match.range.first).trim('\r', '\n')
            if (before.isNotEmpty() || blocks.isEmpty()) {
                blocks += NoteEditorBlock.TextBlock(
                    newBlockId(),
                    TextFieldValue(before, androidx.compose.ui.text.TextRange(before.length))
                )
            }
            val ref = match.groupValues[1].trim()
            if (ref.isNotEmpty()) {
                blocks += NoteEditorBlock.ImageBlock(newBlockId(), ref)
            }
            cursor = match.range.last + 1
        }
        val tail = content.substring(cursor).trim('\r', '\n')
        val cleanTail = if (tail == "চিত্র নোট" && blocks.any { it is NoteEditorBlock.ImageBlock }) "" else tail
        val legacy = legacyImagePath.trim()
        if (legacy.isNotEmpty() && blocks.none { it is NoteEditorBlock.ImageBlock && it.imageRef == legacy }) {
            if (cleanTail.isNotEmpty() || blocks.isEmpty()) {
                blocks += NoteEditorBlock.TextBlock(
                    newBlockId(),
                    TextFieldValue(cleanTail, androidx.compose.ui.text.TextRange(cleanTail.length))
                )
            }
            blocks += NoteEditorBlock.ImageBlock(newBlockId(), legacy)
            blocks += NoteEditorBlock.TextBlock(newBlockId(), TextFieldValue(""))
        } else {
            if (blocks.isEmpty() || blocks.last() !is NoteEditorBlock.TextBlock || cleanTail.isNotEmpty()) {
                blocks += NoteEditorBlock.TextBlock(
                    newBlockId(),
                    TextFieldValue(cleanTail, androidx.compose.ui.text.TextRange(cleanTail.length))
                )
            }
        }
        return blocks
    }

    fun insertImageAtCursor(
        blocks: List<NoteEditorBlock>,
        activeTextBlockId: String?,
        imageRef: String
    ): Pair<List<NoteEditorBlock>, String> {
        val cleanRef = imageRef.trim()
        if (cleanRef.isEmpty()) {
            val fallbackId = (blocks.firstOrNull { it is NoteEditorBlock.TextBlock }?.id) ?: newBlockId()
            return blocks to fallbackId
        }
        val current = blocks.toMutableList()
        val targetIdx = current.indexOfFirst { it is NoteEditorBlock.TextBlock && it.id == activeTextBlockId }
            .let { idx -> if (idx >= 0) idx else current.indexOfLast { it is NoteEditorBlock.TextBlock } }

        val newImg = NoteEditorBlock.ImageBlock(newBlockId(), cleanRef)
        val afterId = newBlockId()

        if (targetIdx < 0) {
            current += newImg
            current += NoteEditorBlock.TextBlock(afterId, TextFieldValue(""))
            return current to afterId
        }

        val target = current[targetIdx] as NoteEditorBlock.TextBlock
        val fullText = target.value.text
        val splitPos = target.value.selection.start.coerceIn(0, fullText.length)
        val beforeText = fullText.substring(0, splitPos).trimEnd('\r', '\n')
        val afterText = fullText.substring(splitPos).trimStart('\r', '\n')

        val replacement = mutableListOf<NoteEditorBlock>()
        if (beforeText.isNotEmpty() || targetIdx == 0) {
            replacement += NoteEditorBlock.TextBlock(target.id, TextFieldValue(beforeText))
        }
        replacement += newImg
        replacement += NoteEditorBlock.TextBlock(afterId, TextFieldValue(afterText))

        current.removeAt(targetIdx)
        current.addAll(targetIdx, replacement)
        return current to afterId
    }

    fun serializeBlocks(blocks: List<NoteEditorBlock>): String {
        val parts = mutableListOf<String>()
        for (block in blocks) {
            when (block) {
                is NoteEditorBlock.TextBlock -> {
                    val t = block.value.text.trim()
                    if (t.isNotEmpty()) parts += t
                }
                is NoteEditorBlock.ImageBlock -> {
                    val ref = block.imageRef.trim()
                    if (ref.isNotEmpty()) parts += "[[IMG:$ref]]"
                }
            }
        }
        return parts.joinToString("\n").take(10000)
    }

    fun newBlockId(): String = "blk_" + UUID.randomUUID().toString().replace("-", "").take(10)
}

@Composable
fun RichNoteContentView(
    content: String,
    legacyImagePath: String = "",
    accentColor: Color,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    imageHeightDp: Int = 180,
    onImageClick: (RichNoteSegment.Image) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val segments = remember(content, legacyImagePath) {
        RichNoteCodec.parseSegments(content, legacyImagePath, context.filesDir)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (segments.isEmpty()) {
            Text(
                text = content,
                style = textStyle,
                color = textColor
            )
        } else {
            segments.forEachIndexed { index, segment ->
                when (segment) {
                    is RichNoteSegment.Text -> {
                        Text(
                            text = segment.text,
                            style = textStyle,
                            color = textColor
                        )
                    }
                    is RichNoteSegment.Image -> {
                        NoteAttachmentImagePreview(
                            imageFile = segment.file,
                            contentDescription = "নোটের চিত্র ${index + 1}",
                            accentColor = accentColor,
                            heightDp = imageHeightDp,
                            modifier = Modifier.testTag("inline_note_image_$index"),
                            onClick = { onImageClick(segment) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NoteAttachmentImagePreview(
    imageFile: File,
    contentDescription: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    heightDp: Int = 180,
    onClick: (() -> Unit)? = null
) {
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
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
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
                    color = Color.Black.copy(alpha = 0.56f),
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
                            text = "জুম",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

private data class DrawStroke(
    val color: Color,
    val widthPx: Float,
    val points: List<Offset>
)

@Composable
fun DrawingDiagramDialog(
    onSaveBitmap: (Bitmap) -> Unit,
    onDismiss: () -> Unit
) {
    val penColors = listOf(
        Color(0xFF111827),
        Color(0xFF2563EB),
        Color(0xFFDC2626),
        Color(0xFF059669)
    )
    var selectedColor by remember { mutableStateOf(penColors[0]) }
    var strokes by remember { mutableStateOf<List<DrawStroke>>(emptyList()) }
    var activePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var boardWidthPx by remember { mutableIntStateOf(800) }
    var boardHeightPx by remember { mutableIntStateOf(600) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("drawing_diagram_dialog")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "হাতে চিত্র / ডায়াগ্রাম আঁকুন",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "বন্ধ করুন")
                    }
                }

                // Pen color + Undo / Clear controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        penColors.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (selectedColor == color) 3.dp else 1.dp,
                                        color = if (selectedColor == color) MaterialTheme.colorScheme.primary else Color.LightGray,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = color }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = { if (strokes.isNotEmpty()) strokes = strokes.dropLast(1) },
                            enabled = strokes.isNotEmpty()
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "পেছনে")
                        }
                        IconButton(
                            onClick = {
                                strokes = emptyList()
                                activePoints = emptyList()
                            },
                            enabled = strokes.isNotEmpty()
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "সব মুছুন")
                        }
                    }
                }

                // Drawing Board
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        .onSizeChanged { size ->
                            if (size.width > 0 && size.height > 0) {
                                boardWidthPx = size.width
                                boardHeightPx = size.height
                            }
                        }
                        .pointerInput(selectedColor) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    activePoints = listOf(start)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    activePoints = activePoints + change.position
                                },
                                onDragEnd = {
                                    if (activePoints.isNotEmpty()) {
                                        strokes = strokes + DrawStroke(selectedColor, 7f, activePoints)
                                        activePoints = emptyList()
                                    }
                                },
                                onDragCancel = {
                                    activePoints = emptyList()
                                }
                            )
                        }
                        .pointerInput(selectedColor) {
                            detectTapGestures { tap ->
                                strokes = strokes + DrawStroke(selectedColor, 7f, listOf(tap, tap + Offset(1f, 1f)))
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        fun drawOne(stroke: DrawStroke) {
                            if (stroke.points.isEmpty()) return
                            val path = Path().apply {
                                moveTo(stroke.points.first().x, stroke.points.first().y)
                                for (i in 1 until stroke.points.size) {
                                    lineTo(stroke.points[i].x, stroke.points[i].y)
                                }
                            }
                            drawPath(
                                path = path,
                                color = stroke.color,
                                style = Stroke(
                                    width = stroke.widthPx,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                        strokes.forEach { drawOne(it) }
                        if (activePoints.isNotEmpty()) {
                            drawOne(DrawStroke(selectedColor, 7f, activePoints))
                        }
                    }
                    if (strokes.isEmpty() && activePoints.isEmpty()) {
                        Text(
                            text = "আঙুল দিয়ে এখানে চিত্র আঁকুন...",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) { Text("বাতিল") }

                    Button(
                        onClick = {
                            val bmp = renderStrokesToBitmap(strokes, boardWidthPx, boardHeightPx)
                            onSaveBitmap(bmp)
                        },
                        enabled = strokes.isNotEmpty(),
                        modifier = Modifier.weight(1f).testTag("save_drawn_diagram_button")
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("নোটের মাঝে যোগ")
                    }
                }
            }
        }
    }
}

private fun renderStrokesToBitmap(
    strokes: List<DrawStroke>,
    srcWidth: Int,
    srcHeight: Int
): Bitmap {
    val w = srcWidth.coerceIn(320, 1400)
    val h = srcHeight.coerceIn(240, 1400)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)

    val paint = AndroidPaint().apply {
        isAntiAlias = true
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
    }

    for (stroke in strokes) {
        if (stroke.points.isEmpty()) continue
        paint.color = stroke.color.toArgb()
        paint.strokeWidth = stroke.widthPx
        val path = AndroidPath()
        val first = stroke.points.first()
        path.moveTo(first.x, first.y)
        for (i in 1 until stroke.points.size) {
            val pt = stroke.points[i]
            path.lineTo(pt.x, pt.y)
        }
        canvas.drawPath(path, paint)
    }
    return bmp
}

@Composable
fun NoteImageViewerDialog(
    imageFile: File,
    title: String,
    onDismiss: () -> Unit
) {
    var scale by remember(imageFile.absolutePath) { mutableFloatStateOf(1f) }
    var panOffset by remember(imageFile.absolutePath) { mutableStateOf(Offset.Zero) }

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
                        .pointerInput(imageFile.absolutePath) {
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

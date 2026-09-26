package com.clarklevis.dsh.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.text.Spanned
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.SchemeHandler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Node

private const val CHAT_IMAGE_SCHEME = "dsh-chat-image"
private const val MAXIMUM_IMAGE_EDGE_PX = 1024
private const val PREVIEW_IMAGE_EDGE_PX = 2048
private const val MAXIMUM_CACHED_IMAGE_BYTES = 12 * 1024 * 1024
private const val IMAGE_READ_TIMEOUT_MILLIS = 30_000L

/** 会话工作区里的一张图片：目标路径按会话工作目录换算成工作区相对路径，字节经网关的
 * 文件通道读取。 */
internal class ChatImageScope(
    val sessionId: String,
    val workingDirectory: String?,
    val readBytes: suspend (String) -> ByteArray?
) {
    val identity: String = "$sessionId\u0000${workingDirectory.orEmpty()}"

    private val cachedBytes = LinkedHashMap<String, ByteArray>(4, 0.75f, true)

    fun cacheBytes(path: String, bytes: ByteArray) {
        if (bytes.size > MAXIMUM_CACHED_IMAGE_BYTES) return
        synchronized(cachedBytes) {
            cachedBytes[path] = bytes
            var total = cachedBytes.values.sumOf(ByteArray::size)
            val iterator = cachedBytes.entries.iterator()
            while (total > MAXIMUM_CACHED_IMAGE_BYTES && iterator.hasNext()) {
                total -= iterator.next().value.size
                iterator.remove()
            }
        }
    }

    /** 点开预览时按更大的边长重新解码，取不到原图字节时返回 null。 */
    suspend fun previewImage(path: String): Bitmap? {
        val cached = synchronized(cachedBytes) { cachedBytes[path] }
        val bytes = cached ?: readBytes(path)?.also { cacheBytes(path, it) } ?: return null
        return decodeScaled(bytes, PREVIEW_IMAGE_EDGE_PX)
    }
}

internal data class MarkdownImageTap(
    val relativePath: String?,
    val bitmap: Bitmap
)

/** 取回正文里点中的那张已解码图片；点到别处或图片尚未加载时返回 null。 */
internal fun markdownImageAt(textView: TextView, x: Float, y: Float): MarkdownImageTap? {
    val layout = textView.layout ?: return null
    val spanned = textView.text as? Spanned ?: return null
    val vertical = (y - textView.totalPaddingTop + textView.scrollY).toInt()
    if (vertical < 0 || vertical > layout.height) return null
    val line = layout.getLineForVertical(vertical)
    val horizontal = x - textView.totalPaddingLeft + textView.scrollLeft
    if (horizontal < layout.getLineLeft(line) || horizontal > layout.getLineRight(line)) return null
    val offset = layout.getOffsetForHorizontal(line, horizontal)
    val span = spanned.getSpans(
        offset.coerceAtLeast(0),
        (offset + 1).coerceAtMost(spanned.length),
        AsyncDrawableSpan::class.java
    ).firstOrNull() ?: return null
    val drawable = span.drawable
    val bitmap = (drawable.result as? BitmapDrawable)?.bitmap ?: return null
    val relativePath = drawable.destination
        .takeIf { it.startsWith("$CHAT_IMAGE_SCHEME://") }
        ?.let { Uri.parse(it).path?.removePrefix("/") }
    return MarkdownImageTap(relativePath, bitmap)
}

@Composable
internal fun rememberChatImageScope(stateHolder: AndroidSharedStateHolder): ChatImageScope? {
    val sessionId = stateHolder.snapshot.selectedSessionId
    val workingDirectory = stateHolder.snapshot.sessions
        .firstOrNull { it.id == sessionId }
        ?.cwd
    return remember(sessionId, workingDirectory) {
        sessionId?.let { id ->
            ChatImageScope(id, workingDirectory) { path ->
                stateHolder.readWorkspaceFileBytes(id, path)
            }
        }
    }
}

/** markdown 图片目标与工作区相对路径之间的换算。 */
internal object ChatImagePaths {

    fun workspaceRelativePath(destination: String, workingDirectory: String?): String? {
        val trimmed = destination.trim()
        if (trimmed.isEmpty()) return null
        val uri = Uri.parse(trimmed)
        val scheme = uri.scheme
        val path = when {
            scheme.isNullOrEmpty() -> Uri.decode(trimmed)
            scheme == "file" -> uri.path ?: return null
            else -> return null
        }.substringBefore('#').substringBefore('?')
        if (path.isEmpty()) return null
        val relative = if (path.startsWith('/')) {
            val cwd = workingDirectory?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty) ?: return null
            if (!path.startsWith("$cwd/")) return null
            path.removePrefix("$cwd/")
        } else {
            path.removePrefix("./")
        }
        if (relative.isEmpty()) return null
        if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return relative
    }

    fun displayDestination(relativePath: String): String =
        "$CHAT_IMAGE_SCHEME:///${Uri.encode(relativePath)}"
}

/**
 * 渲染前把工作区内的图片目标改写成带 scheme 的地址，交给 [ChatImageSchemeHandler] 读取。
 * 工作区之外的地址保持原样。
 */
internal class ChatImageRewritePlugin(
    private val workingDirectory: String?
) : AbstractMarkwonPlugin() {

    override fun beforeRender(node: Node) {
        node.accept(object : AbstractVisitor() {
            override fun visit(image: Image) {
                val relative = ChatImagePaths.workspaceRelativePath(
                    image.destination,
                    workingDirectory
                ) ?: return
                image.destination = ChatImagePaths.displayDestination(relative)
            }
        })
    }
}

internal class ChatImageSchemeHandler(
    private val context: Context,
    private val scope: ChatImageScope
) : SchemeHandler() {

    override fun supportedSchemes(): Collection<String> = listOf(CHAT_IMAGE_SCHEME)

    override fun handle(raw: String, uri: Uri): ImageItem {
        val relative = uri.path?.removePrefix("/")?.takeIf(String::isNotEmpty)
            ?: throw IllegalStateException("chat image destination is invalid: $raw")
        val bytes = runBlocking {
            withTimeoutOrNull(IMAGE_READ_TIMEOUT_MILLIS) { scope.readBytes(relative) }
        } ?: throw IllegalStateException("chat image is unavailable: $relative")
        scope.cacheBytes(relative, bytes)
        val bitmap = decodeScaled(bytes, MAXIMUM_IMAGE_EDGE_PX)
            ?: throw IllegalStateException("chat image cannot be decoded: $relative")
        return ImageItem.withResult(BitmapDrawable(context.resources, bitmap))
    }
}

private fun decodeScaled(bytes: ByteArray, maxEdgePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxEdgePx) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )
}

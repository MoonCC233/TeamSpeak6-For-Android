package dev.tsdroid.ui.component

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Environment
import android.provider.MediaStore
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WallpaperCacheManager {
    private var cacheDir: File? = null
    private const val MAX_CACHE_SIZE_MB_DEFAULT = 100L

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "wallpaper_cache").also { it.mkdirs() }
    }

    fun getCacheDir(): File? = cacheDir

    fun getCachedFiles(): List<File> {
        return cacheDir?.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun getCachedFilesCount(): Int = getCachedFiles().size

    fun getCacheSizeBytes(): Long {
        return getCachedFiles().sumOf { it.length() }
    }

    fun getCacheSizeMB(): Float = getCacheSizeBytes() / (1024f * 1024f)

    fun getRandomCachedFile(): File? {
        val files = getCachedFiles()
        if (files.isEmpty()) return null
        return files.random()
    }

    /**
     * Fetches [imageUrl] and decodes it: one request, not two.
     *
     * The bytes are written straight into the file the cache would keep them in, and the bitmap is
     * decoded from there. Filling the cache used to be a *second* download of the same image, which
     * doubled both the data and the wait of every cold start — and that wait is the splash.
     *
     * The file is dropped again when the user's cache budget is already spent, or when the image does
     * not decode (an error page, say): the wallpaper of this session is still shown, it is just not
     * kept, and a broken image never becomes the random fallback of a later start.
     */
    suspend fun fetchAndDecode(context: Context, imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        val dir = cacheDir ?: return@withContext null
        val file = File(dir, fileNameFor(imageUrl))
        val wasCached = file.exists()
        // Whether these bytes may stay. Measured before they are added, so a start on a cache that is
        // already at the user's limit still shows a wallpaper without growing the cache any further.
        val keep = wasCached ||
            getCacheSizeBytes() < SettingsCacheSizeHelper.getMaxCacheSize(context) * 1024L * 1024L
        try {
            if (!wasCached) {
                val conn = URL(imageUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                conn.disconnect()
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            // A download that is not kept (or that would not decode) is dropped again: a broken file
            // in the cache would only be picked up as the random fallback on some later start.
            if (bitmap == null || !keep) file.delete()
            bitmap
        } catch (_: Exception) {
            if (!wasCached) file.delete()
            null
        }
    }

    private fun fileNameFor(imageUrl: String) = imageUrl.hashCode().toString() + ".jpg"

    fun clearCache() {
        cacheDir?.listFiles()?.forEach { it.delete() }
    }

    fun deleteFile(file: File) {
        if (file.parentFile == cacheDir) file.delete()
    }
}

object SettingsCacheSizeHelper {
    private const val KEY_MAX_CACHE_SIZE = "wallpaper_max_cache_size_mb"
    private const val MAX_CACHE_SIZE_DEFAULT = 100L

    fun getMaxCacheSize(context: Context): Long {
        val prefs = context.getSharedPreferences("wallpaper_cache_prefs", Context.MODE_PRIVATE)
        return prefs.getLong(KEY_MAX_CACHE_SIZE, MAX_CACHE_SIZE_DEFAULT)
    }

    fun setMaxCacheSize(context: Context, sizeMB: Long) {
        val prefs = context.getSharedPreferences("wallpaper_cache_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_MAX_CACHE_SIZE, sizeMB).apply()
    }
}

/**
 * Copies cached wallpapers into the device gallery (`Pictures/TS6 Droid`).
 *
 * The cache stores whatever bytes the server sent under a fixed `.jpg` name, and the anime
 * endpoints serve PNG as often as JPEG. The real type is therefore sniffed from the file header
 * before publishing: an image registered under the wrong MIME type shows up as a broken thumbnail
 * in most galleries.
 */
object WallpaperExporter {

    private const val ALBUM = "TS6 Droid"

    /** Writes [files] into the gallery; returns how many of them were saved. */
    suspend fun export(context: Context, files: List<File>): Int = withContext(Dispatchers.IO) {
        files.count { exportOne(context, it) }
    }

    /** Writes a single cached file into the gallery. */
    suspend fun export(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
        exportOne(context, file)
    }

    private fun exportOne(context: Context, file: File): Boolean = try {
        val (extension, mimeType) = sniff(file)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "TS6_${stamp}_${file.nameWithoutExtension.take(6)}.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            // Hidden until the bytes are in place, so no gallery ever sees a half-written image.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val copied = try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } != null
        } catch (_: Exception) {
            false
        }
        if (copied) {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } else {
            context.contentResolver.delete(uri, null, null)
        }
        copied
    } catch (_: Exception) {
        false
    }

    /** Extension and MIME type of [file], read from its magic bytes. */
    private fun sniff(file: File): Pair<String, String> {
        val header = ByteArray(12)
        val read = try {
            file.inputStream().use { it.read(header) }
        } catch (_: Exception) {
            -1
        }
        return when {
            read >= 8 && matches(header, 0, 0x89, 0x50, 0x4E, 0x47) -> "png" to "image/png"
            read >= 3 && matches(header, 0, 0xFF, 0xD8, 0xFF) -> "jpg" to "image/jpeg"
            read >= 6 && matches(header, 0, 0x47, 0x49, 0x46, 0x38) -> "gif" to "image/gif"
            read >= 12 && matches(header, 0, 0x52, 0x49, 0x46, 0x46) &&
                matches(header, 8, 0x57, 0x45, 0x42, 0x50) -> "webp" to "image/webp"
            else -> "jpg" to "image/jpeg"
        }
    }

    private fun matches(bytes: ByteArray, offset: Int, vararg expected: Int): Boolean =
        expected.indices.all { (bytes[offset + it].toInt() and 0xFF) == expected[it] }
}

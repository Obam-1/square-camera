package com.squarecamera.app

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** Turning camera frames into photos in the phone's Camera folder, where Google Photos finds them. */
object Photos {
    private const val PREFIX = "SQ_"

    private val collection: Uri get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * Rotates a frame upright, cuts its center to [ratio] (width ÷ height when upright; flipped
     * for a sideways shot) and mirrors it for selfies, so the photo matches the viewfinder.
     */
    fun process(bitmap: Bitmap, rotationDegrees: Int, ratio: Float, mirror: Boolean): ByteArray {
        val turned = rotationDegrees % 180 != 0
        val uprightW = if (turned) bitmap.height else bitmap.width
        val uprightH = if (turned) bitmap.width else bitmap.height
        val target = if (uprightW > uprightH) 1f / ratio else ratio

        var cropW = uprightW
        var cropH = uprightH
        if (uprightW.toFloat() / uprightH > target) cropW = min(uprightW, (uprightH * target).roundToInt())
        else cropH = min(uprightH, (uprightW / target).roundToInt())

        // The crop is centered, so it's the same rectangle in the sensor's frame, just turned.
        val srcW = if (turned) cropH else cropW
        val srcH = if (turned) cropW else cropH
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val photo = Bitmap.createBitmap(bitmap, (bitmap.width - srcW) / 2, (bitmap.height - srcH) / 2, srcW, srcH, matrix, true)
        return ByteArrayOutputStream().use { out ->
            photo.compress(Bitmap.CompressFormat.JPEG, 95, out)
            out.toByteArray()
        }
    }

    /** Saves to DCIM/Camera, like the Pixel camera does. */
    fun save(context: Context, jpeg: ByteArray): Uri {
        val now = System.currentTimeMillis()
        val name = PREFIX + SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date(now)) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: error("Couldn't create the photo file")
        try {
            resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: error("Couldn't write the photo")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** The newest photo this app took, for the thumbnail. */
    fun latest(context: Context): Uri? {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$PREFIX%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c -> if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0)) }
        return null
    }

    fun thumbnail(context: Context, uri: Uri): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()

    /** Opens the photo in Google Photos (or the phone's gallery). */
    fun open(context: Context, uri: Uri) {
        fun intent(action: String) = Intent(action).setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent("com.android.camera.action.REVIEW"))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(intent(Intent.ACTION_VIEW))
        }
    }
}

package com.family.locationsender.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface

/**
 * Image utilities used when the user picks a profile photo from the gallery
 * or takes one with the camera.
 *
 * Reads EXIF orientation and rotates / flips the bitmap accordingly so the
 * profile image is stored upright. Without this, photos coming out of the
 * stock camera app (especially on Samsung / Huawei) are visually upright in
 * the gallery but are saved as raw bytes with an Orientation EXIF tag —
 * which means our server / web side would render them rotated by 90° / 180°.
 */
object ImageUtils {

    private const val TAG = "FLS-ImageUtils"

    /** Decode + auto-rotate a bitmap from a content URI. Returns null on error. */
    fun decodeOriented(context: Context, uri: Uri): Bitmap? {
        return try {
            val bmp = context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                BitmapFactory.decodeStream(input)
            } ?: return null
            val orientation = readOrientation(context, uri)
            applyOrientation(bmp, orientation)
        } catch (t: Throwable) {
            Log.e(TAG, "decodeOriented failed", t)
            null
        }
    }

    /**
     * Apply the saved EXIF orientation of a Bitmap if we can read it from a
     * URI. Use when the bitmap came from a separate source (e.g. camera
     * TakePicturePreview, which usually has no EXIF, but some OEMs do).
     */
    fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bmp // ORIENTATION_NORMAL or undefined
        }
        return try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        } catch (t: Throwable) {
            Log.e(TAG, "applyOrientation rotation failed", t)
            bmp
        }
    }

    private fun readOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) ExifInterface.ORIENTATION_NORMAL
                else ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "readOrientation failed", t)
            ExifInterface.ORIENTATION_NORMAL
        }
    }
}

package ru.profstroyservices.armdriver.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

// Камера пишет поворот снимка в EXIF, а не поворачивает пиксели;
// BitmapFactory этот тег не читает. Без поворота здесь вертикальный снимок
// уходил в 1С лёжа на боку: при сжатии JPEG перекодируется, и тег теряется
// вместе с остальным EXIF.
internal fun decodeUpright(file: File, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    ) ?: return null

    val degrees = runCatching { ExifInterface(file.absolutePath).rotationDegrees }.getOrDefault(0)
    if (degrees == 0) return decoded
    val rotated = Bitmap.createBitmap(
        decoded, 0, 0, decoded.width, decoded.height,
        Matrix().apply { postRotate(degrees.toFloat()) },
        true
    )
    if (rotated !== decoded) decoded.recycle()
    return rotated
}

package ru.profstroyservices.armdriver.data.repository

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoDecodingTest {

    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    // Горизонтальный кадр: левая половина красная, правая синяя.
    private fun writeLandscape(name: String, orientation: Int?): File {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLUE)
            clipRect(0, 0, 100, 100)
            drawColor(Color.RED)
        }
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (orientation != null) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    // Так камера сохраняет вертикальный снимок: пиксели как с сенсора,
    // поворот только в EXIF.
    @Test
    fun exifRotationIsAppliedToPixels() {
        val file = writeLandscape("rot90.jpg", ExifInterface.ORIENTATION_ROTATE_90)

        val upright = checkNotNull(decodeUpright(file, 2000))

        assertEquals(100, upright.width)
        assertEquals(200, upright.height)
        // Поворот на 90° по часовой: левая (красная) половина уходит наверх.
        assertTrue(Color.red(upright.getPixel(50, 20)) > 200)
        assertTrue(Color.blue(upright.getPixel(50, 180)) > 200)
    }

    @Test
    fun imageWithoutExifIsUnchanged() {
        val file = writeLandscape("plain.jpg", null)

        val decoded = checkNotNull(decodeUpright(file, 2000))

        assertEquals(200, decoded.width)
        assertEquals(100, decoded.height)
    }

    @Test
    fun largeImageIsDownsampledToLimit() {
        val file = writeLandscape("big.jpg", ExifInterface.ORIENTATION_ROTATE_90)

        val decoded = checkNotNull(decodeUpright(file, 60))

        assertTrue(decoded.width <= 60 && decoded.height <= 60)
    }
}

package ru.profstroyservices.armdriver.ui.camera

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import ru.profstroyservices.armdriver.ui.settings.extractDriverGuid
import java.util.concurrent.TimeUnit

// Проверяет, что модель ML Kit действительно лежит в APK и читает QR
// с GUID водителя — без живой камеры, на сгенерированной картинке.
@RunWith(AndroidJUnit4::class)
class QrRecognitionTest {

    private fun qrBitmap(content: String): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 400, 400)
        return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }

    @Test
    fun driverGuidQrIsRecognizedAndParsed() {
        val guid = "1cb0cea4-e22b-45b6-b43b-8ce59aa77d30"
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )

        val codes = Tasks.await(scanner.process(InputImage.fromBitmap(qrBitmap(guid), 0)), 30, TimeUnit.SECONDS)
        scanner.close()

        val raw = codes.single().rawValue
        assertEquals(guid, raw)
        assertEquals(guid, extractDriverGuid(checkNotNull(raw)))
    }
}

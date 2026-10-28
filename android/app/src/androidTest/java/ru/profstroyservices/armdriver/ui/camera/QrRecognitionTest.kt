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
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import ru.profstroyservices.armdriver.ui.settings.parseDriverQr
import java.util.concurrent.TimeUnit

// Проверяет, что модель ML Kit действительно лежит в APK и читает QR
// с GUID водителя — без живой камеры, на сгенерированной картинке.
@RunWith(AndroidJUnit4::class)
class QrRecognitionTest {

    private fun qrBitmap(content: String): Bitmap {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, 400, 400,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
        )
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
        assertEquals(guid, parseDriverQr(checkNotNull(raw))?.driverId)
    }

    // ФИО по-русски в QR (формат, предложенный 1С): кириллица должна дойти
    // до экрана без «кракозябр».
    @Test
    fun driverQrWithCyrillicNameIsRecognized() {
        val guid = "1cb0cea4-e22b-45b6-b43b-8ce59aa77d30"
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )

        val codes = Tasks.await(
            scanner.process(InputImage.fromBitmap(qrBitmap("$guid\nКостенко Павел Сергеевич"), 0)),
            30, TimeUnit.SECONDS
        )
        scanner.close()

        val qr = checkNotNull(parseDriverQr(checkNotNull(codes.single().rawValue)))
        assertEquals(guid, qr.driverId)
        assertEquals("Костенко Павел Сергеевич", qr.name)
    }
}

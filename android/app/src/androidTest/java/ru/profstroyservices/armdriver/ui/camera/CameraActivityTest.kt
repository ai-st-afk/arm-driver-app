package ru.profstroyservices.armdriver.ui.camera

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.PhotoKind
import ru.profstroyservices.armdriver.data.repository.decodeUpright
import java.io.File

// Настоящая камера устройства (на эмуляторе — виртуальная сцена) в
// отдельном окне: пошаговая съёмка разгрузки и QR-сканер.
@RunWith(AndroidJUnit4::class)
class CameraActivityTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val context = instrumentation.targetContext

    @Before
    fun grantCamera() {
        automation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)?.let { return it }
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }

    private fun screenshot(name: String) {
        File(context.cacheDir, name).outputStream().use {
            automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
        if (!condition()) screenshot("camera-activity-failure.png")
        assertTrue(condition())
    }

    private fun click(label: String) {
        var target = checkNotNull(node(label)) { "нет «$label» на экране" }
        while (!target.isClickable) target = checkNotNull(target.parent)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun shoot() {
        waitFor { node("Сделать снимок") != null }
        SystemClock.sleep(1_500)
        click("Сделать снимок")
        waitFor(timeoutMs = 20_000) { node("Переснять") != null }
    }

    @Test
    fun unloadingTakesAllStepsWithExtraDocumentSheet() {
        val intent = CapturePhotos().createIntent(context, PhotoCaptureRequest(EventTypes.RAZGRUZILSYA, 0))
        ActivityScenario.launchActivityForResult<CameraActivity>(intent).use { scenario ->
            waitFor { node("Сфотографируйте грузовик сзади") != null && node("Шаг 1 из 3") != null }

            // Кнопка спуска целиком над системными кнопками — на Xiaomi она
            // уходила под «Домой» и не нажималась.
            var navBarTop = 0
            scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)!!
                navBarTop = activity.window.decorView.height -
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            }
            waitFor { node("Сделать снимок") != null }
            val shutter = Rect().also { checkNotNull(node("Сделать снимок")).getBoundsInScreen(it) }
            SystemClock.sleep(1_000)
            screenshot("camera-step1.png")
            assertTrue("кнопка ${shutter.bottom} заходит под панель навигации $navBarTop", shutter.bottom <= navBarTop)

            shoot()
            click("Далее")
            waitFor { node("Сфотографируйте грузовик сбоку") != null && node("Шаг 2 из 3") != null }
            shoot()
            click("Далее")
            waitFor { node("Сфотографируйте документ") != null && node("Шаг 3 из 3") != null }
            shoot()
            SystemClock.sleep(1_000)
            screenshot("camera-review-document.png")
            click("Ещё лист")
            waitFor { node("Сфотографируйте документ: лист 2") != null }
            shoot()
            click("Готово")

            val result = CapturePhotos().parseResult(scenario.result.resultCode, scenario.result.resultData)
            assertTrue(result.completed)
            assertEquals(
                listOf(PhotoKind.TRUCK_REAR, PhotoKind.TRUCK_SIDE, PhotoKind.DOCUMENT, PhotoKind.DOCUMENT),
                result.photos.map { it.kind }
            )
            result.photos.forEach { photo ->
                assertTrue(decodeUpright(File(photo.path), 2000) != null)
                File(photo.path).delete()
            }
        }
    }

    // Вышел посреди съёмки — принятые кадры возвращаются, этап не закрыт.
    @Test
    fun cancelReturnsAcceptedPhotosAsIncomplete() {
        val intent = CapturePhotos().createIntent(context, PhotoCaptureRequest(EventTypes.ZAGRUZILSYA_V_PUT, 0))
        ActivityScenario.launchActivityForResult<CameraActivity>(intent).use { scenario ->
            shoot()
            click("Далее")
            waitFor { node("Сфотографируйте грузовик сбоку") != null }
            click("Отмена")

            val result = CapturePhotos().parseResult(scenario.result.resultCode, scenario.result.resultData)
            assertFalse(result.completed)
            assertEquals(listOf(PhotoKind.TRUCK_REAR), result.photos.map { it.kind })
            result.photos.forEach { File(it.path).delete() }
        }
    }

    // Живые кадры идут через ML Kit без падений, а сцена без QR не
    // выдаётся за распознанный код. Само распознавание — QrRecognitionTest.
    @Test
    fun qrScannerRunsWithoutFalseHits() {
        val intent = ScanDriverQr().createIntent(context, Unit)
        ActivityScenario.launchActivityForResult<CameraActivity>(intent).use { scenario ->
            waitFor { node("Наведите камеру на QR-код водителя") != null }
            SystemClock.sleep(4_000)
            screenshot("camera-qr.png")
            assertTrue(node("Наведите камеру на QR-код водителя") != null)
            click("Отмена")
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
    }
}

package ru.profstroyservices.armdriver.ui.camera

import android.Manifest
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.profstroyservices.armdriver.MainActivity
import ru.profstroyservices.armdriver.ui.theme.ArmDriverTheme
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// Живые кадры камеры идут через ML Kit без падений, а сцена без QR не
// выдаётся за распознанный код. Само распознавание — QrRecognitionTest.
@RunWith(AndroidJUnit4::class)
class QrScanDialogTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation

    @Before
    fun grantPermissions() {
        val pkg = instrumentation.targetContext.packageName
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).forEach { automation.grantRuntimePermission(pkg, it) }
    }

    private fun visible(label: String): Boolean {
        var found = false
        fun visit(node: AccessibilityNodeInfo) {
            if (node.text?.toString() == label) found = true
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        automation.rootInActiveWindow?.let(::visit)
        return found
    }

    @Test
    fun scannerRunsOnLiveFramesWithoutFalseHits() {
        val scanned = AtomicBoolean(false)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    ArmDriverTheme {
                        QrScanDialog(onScanned = { scanned.set(true) }, onCancel = {})
                    }
                }
            }

            val until = SystemClock.uptimeMillis() + 10_000
            while (!visible("Наведите камеру на QR-код водителя") && SystemClock.uptimeMillis() < until) {
                SystemClock.sleep(100)
            }
            assertTrue(visible("Наведите камеру на QR-код водителя"))
            SystemClock.sleep(4_000)
            File(instrumentation.targetContext.cacheDir, "qr-scan.png").outputStream().use {
                automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue(visible("Наведите камеру на QR-код водителя"))
        }

        assertFalse(scanned.get())
    }
}

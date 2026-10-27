package ru.profstroyservices.armdriver.ui.camera

import android.Manifest
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.profstroyservices.armdriver.MainActivity
import ru.profstroyservices.armdriver.data.repository.decodeUpright
import ru.profstroyservices.armdriver.ui.theme.ArmDriverTheme
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// Сквозной путь через настоящую камеру устройства (на эмуляторе —
// виртуальная сцена): подсказка видна, снимок пишется в файл, после
// «Готово» вызывающий получает читаемое фото.
@RunWith(AndroidJUnit4::class)
class GuidedPhotoDialogTest {

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

    private fun nodes(label: String): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) matches.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        automation.rootInActiveWindow?.let(::visit)
        return matches
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
        if (!condition()) {
            File(instrumentation.targetContext.cacheDir, "guided-photo-failure.png").outputStream().use {
                automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertTrue(condition())
    }

    private fun screenshot(name: String) {
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun click(label: String) {
        var node = nodes(label).first()
        while (!node.isClickable) node = checkNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test
    fun captureReviewAndConfirm() {
        val target = File(instrumentation.targetContext.cacheDir, "guided-photo.jpg").apply { delete() }
        val captured = AtomicBoolean(false)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    ArmDriverTheme {
                        GuidedPhotoDialog(
                            prompt = "Сфотографируйте документ",
                            hint = null,
                            target = target,
                            onCaptured = { captured.set(true) },
                            onCancel = {}
                        )
                    }
                }
            }

            waitFor { nodes("Сфотографируйте документ").isNotEmpty() && nodes("Сделать снимок").isNotEmpty() }
            // Камера эмулятора поднимается не мгновенно — даём превью стартовать.
            SystemClock.sleep(2_000)
            screenshot("guided-photo-aim.png")
            click("Сделать снимок")

            waitFor(timeoutMs = 20_000) { nodes("Готово").isNotEmpty() }
            assertTrue(nodes("Переснять").isNotEmpty())
            screenshot("guided-photo-review.png")
            click("Готово")

            waitFor { captured.get() }
        }

        assertTrue(target.length() > 0)
        assertNotNull(decodeUpright(target, 2000))
    }
}

package ru.profstroyservices.armdriver.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.profstroyservices.armdriver.MainActivity
import ru.profstroyservices.armdriver.data.network.CargoPositionDto
import ru.profstroyservices.armdriver.data.network.CargoPositionsDto
import ru.profstroyservices.armdriver.ui.components.CargoDetails
import ru.profstroyservices.armdriver.ui.theme.ArmDriverTheme
import java.io.File

@RunWith(AndroidJUnit4::class)
class CargoDetailsTest {
    @Test
    fun longCargoListCanExpandAndCollapseInsideScrollableScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun nodes(text: String): List<AccessibilityNodeInfo> {
            val matches = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                if (node.text?.contains(text) == true) matches.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            automation.rootInActiveWindow?.let(::visit)
            return matches
        }
        fun waitFor(condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 5_000
            while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
            if (!condition()) {
                File(instrumentation.targetContext.cacheDir, "cargo-failure.png").outputStream().use {
                    automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            assertTrue(condition())
        }
        fun clickHeader() {
            var node = nodes("Товары (500)").first()
            while (!node.isClickable) node = checkNotNull(node.parent)
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun screenshot(name: String) {
            val file = File(instrumentation.targetContext.cacheDir, name)
            file.outputStream().use { automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    ArmDriverTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                            CargoDetails(
                                CargoPositionsDto(List(500) { i ->
                                    CargoPositionDto("$i", "Товар $i", "шт", "1.000", "0.100", "0.200")
                                }), "50.000", "100.000"
                            )
                        }
                    }
                }
            }
            waitFor { nodes("Товары (500)").isNotEmpty() }
            assertTrue(nodes("Товар 0").isEmpty())
            screenshot("cargo-collapsed.png")
            clickHeader()
            waitFor { nodes("Товар 0").isNotEmpty() }
            assertTrue(nodes("Товар 499").isEmpty())
            assertTrue(nodes("Общий вес").isNotEmpty())
            screenshot("cargo-expanded.png")
            clickHeader()
            waitFor { nodes("Товар 0").isEmpty() }
        }
    }
}

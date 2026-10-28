package ru.profstroyservices.armdriver.ui.camera

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Отдаёт содержимое первого распознанного QR и больше не сканирует —
// что внутри и подходит ли оно, решает вызывающий (см. parseDriverQr).
@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScanScreen(onScanned: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    var error by remember { mutableStateOf<String?>(null) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }
    val analysis = remember {
        val handled = AtomicBoolean(false)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(executor) { frame ->
                    val image = frame.image
                    if (image == null || handled.get()) {
                        frame.close()
                        return@setAnalyzer
                    }
                    scanner.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                        .addOnSuccessListener(mainExecutor) { codes ->
                            val value = codes.firstNotNullOfOrNull { it.rawValue }
                            if (value != null && handled.compareAndSet(false, true)) currentOnScanned(value)
                        }
                        .addOnCompleteListener { frame.close() }
                }
            }
    }
    DisposableEffect(Unit) {
        onDispose {
            analysis.clearAnalyzer()
            scanner.close()
            executor.shutdown()
        }
    }

    BackHandler(onBack = onCancel)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            useCase = analysis,
            onError = { e ->
                Log.e("QrScanScreen", "Camera bind failed", e)
                error = "Камера недоступна. Введите идентификатор вручную."
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(240.dp)
                .border(3.dp, Color.White, RoundedCornerShape(16.dp))
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Наведите камеру на QR-код водителя",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(text = "QR из карточки водителя в 1С", style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            error?.let {
                Text(text = it, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFFF8A80))
            }
            TextButton(onClick = onCancel) {
                Text("Отмена", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

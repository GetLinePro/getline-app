package pro.getline.vpn

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import zxingcpp.BarcodeReader

internal sealed interface QrScanResult {
    data class Success(val content: String) : QrScanResult
    data object UserCanceled : QrScanResult
    data object MissingPermission : QrScanResult
    data object Error : QrScanResult
}

internal class ScanQrCode : ActivityResultContract<Unit, QrScanResult>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(context, QrScannerActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): QrScanResult {
        if (resultCode == Activity.RESULT_OK) {
            return intent?.getStringExtra(QrScannerActivity.EXTRA_CONTENT)
                ?.let(QrScanResult::Success)
                ?: QrScanResult.Error
        }

        return when {
            intent?.getBooleanExtra(QrScannerActivity.EXTRA_MISSING_PERMISSION, false) == true ->
                QrScanResult.MissingPermission
            intent?.getBooleanExtra(QrScannerActivity.EXTRA_ERROR, false) == true ->
                QrScanResult.Error
            else -> QrScanResult.UserCanceled
        }
    }
}

class QrScannerActivity : AppCompatActivity() {
    private val resultDelivered = AtomicBoolean()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val barcodeReader = BarcodeReader(
        BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.QR_CODE),
            tryHarder = true,
            tryInvert = true,
            maxNumberOfSymbols = 1,
        )
    )

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                deliverResult(
                    resultCode = RESULT_CANCELED,
                    data = Intent().putExtra(EXTRA_MISSING_PERMISSION, true),
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)
        setResult(RESULT_CANCELED)

        findViewById<MaterialButton>(R.id.qr_scanner_cancel).setOnClickListener {
            finish()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        resultDelivered.compareAndSet(false, true)
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun finish() {
        resultDelivered.compareAndSet(false, true)
        super.finish()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider =
                        findViewById<PreviewView>(R.id.qr_scanner_preview).surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { image ->
                    try {
                        val result = barcodeReader.read(image).firstOrNull() ?: return@setAnalyzer
                        val content = result.text
                            ?: result.bytes?.toString(Charsets.UTF_8).orEmpty()

                        deliverResult(
                            resultCode = RESULT_OK,
                            data = Intent().putExtra(EXTRA_CONTENT, content),
                        )
                    } catch (_: Exception) {
                        deliverResult(
                            resultCode = RESULT_CANCELED,
                            data = Intent().putExtra(EXTRA_ERROR, true),
                        )
                    } finally {
                        image.close()
                    }
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (_: Exception) {
                deliverResult(
                    resultCode = RESULT_CANCELED,
                    data = Intent().putExtra(EXTRA_ERROR, true),
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun deliverResult(resultCode: Int, data: Intent) {
        if (!resultDelivered.compareAndSet(false, true)) return

        runOnUiThread {
            setResult(resultCode, data)
            finish()
        }
    }

    companion object {
        internal const val EXTRA_CONTENT = "qr_content"
        internal const val EXTRA_MISSING_PERMISSION = "qr_missing_permission"
        internal const val EXTRA_ERROR = "qr_error"
    }
}

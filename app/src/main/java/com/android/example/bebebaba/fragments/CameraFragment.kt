package com.android.example.bebebaba.fragments

import android.annotation.SuppressLint
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.android.example.bebebaba.R
import com.android.example.bebebaba.databinding.FragCameraBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment() {

    private val TAG = "Bebebaba"

    private var _fragCameraBinding: FragCameraBinding? = null
    private val fragCameraBinding get() = _fragCameraBinding!!
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var objectDetector: ObjectDetector
    private lateinit var inputImageView: ImageView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragCameraBinding = FragCameraBinding.inflate(inflater, container, false)
        inputImageView = fragCameraBinding.imageView
        return fragCameraBinding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _fragCameraBinding = null
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (!PermissionFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                CameraFragmentDirections.actionCamToPerm()
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val options = ObjectDetectorOptions.Builder().enableClassification().build()
        objectDetector = ObjectDetection.getClient(options)

//        커스텀 모델 빌드
//        val localModel = LocalModel.Builder().setAssetFilePath("model.tflite").build()
//        val customDetectorOptions = CustomObjectDetectorOptions.Builder(localModel)
//            .setDetectorMode(CustomObjectDetectorOptions.STREAM_MODE)
//            .enableClassification()
//            .setClassificationConfidenceThreshold(0.5f)
//            .setMaxPerObjectLabelCount(2)
//            .build()
//        objectDetector = ObjectDetection.getClient(customDetectorOptions)

        cameraExecutor = Executors.newSingleThreadExecutor()

        fragCameraBinding.viewFinder.post {
            setUpCamera()
        }
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera setup failed.")
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
        val preview = Preview.Builder().build()
        val imageAnalyzer = ImageAnalysis.Builder().build().also {
            it.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image =
                        InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                    objectDetector.process(image)
                        .addOnSuccessListener { detectedObjects ->
                            onResults(detectedObjects, image)
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                }
            }
        }

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview.setSurfaceProvider(fragCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    fun onResults(
        detectedObjects: List<DetectedObject>,
        image: InputImage
    ) {
        val results = detectedObjects.map {
            var text = "Unknown"
            Log.v("box", "boundingBox: ${it.boundingBox}")

            if (it.labels.isNotEmpty()) {
                val firstLabel = it.labels.first()
                text = "${firstLabel.text}, ${firstLabel.confidence.times(100).toInt()}%"
            }
            BoxWithText(it.boundingBox, text)
        }

        Log.v("results", results.toString())

        val paintedResults = drawDetectionResult(results, image.width, image.height)

        inputImageView.setImageBitmap(paintedResults)

        inputImageView.invalidate()
    }

    private fun drawDetectionResult(
        results: List<BoxWithText>,
        imageWidth: Int,
        imageHeight: Int
    ): Bitmap {
        val outputBitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val pen = Paint()

        results.forEach {
            pen.color = Color.RED
            pen.strokeWidth = 4F
            pen.style = Paint.Style.STROKE
            val box = it.box
            canvas.drawRect(box, pen)

            val tagSize = Rect(0, 0, 0, 0)

            pen.style = Paint.Style.FILL_AND_STROKE
            pen.color = Color.GREEN
            pen.strokeWidth = 2F

            pen.textSize = 46F
            pen.getTextBounds(it.text, 0, it.text.length, tagSize)
            val fontSize: Float = pen.textSize * box.width() / tagSize.width()

            if (fontSize < pen.textSize) pen.textSize = fontSize

            var margin = (box.width() - tagSize.width()) / 2.0F
            if (margin < 0F) margin = 0F
            canvas.drawText(
                it.text, box.left + margin,
                box.top + tagSize.height().times(1F), pen
            )
        }
        return outputBitmap
    }

}

data class BoxWithText(val box: Rect, val text: String)

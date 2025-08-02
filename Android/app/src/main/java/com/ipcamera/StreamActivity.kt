package com.ipcamera

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ipcamera.databinding.StreamActivityBinding
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class StreamActivity : AppCompatActivity() {

    private lateinit var binding: StreamActivityBinding
    private val TAG = "StreamActivity"

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var mediaCodec: MediaCodec? = null
    private var encoderSurface: Surface? = null

    @Volatile
    private var isStreaming = false

    @Volatile
    private var socket: Socket? = null

    private val socketExecutor = Executors.newSingleThreadExecutor()

    private lateinit var cameraHandler: Handler
    private lateinit var cameraThread: HandlerThread

    private val TIMEOUT_US = 10000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = StreamActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startCameraThread()

        binding.btnSave.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }
    }

    private fun startCameraThread() {
        cameraThread = HandlerThread("CameraBackground").also { it.start() }
        cameraHandler = Handler(cameraThread.looper)
    }

    private fun stopCameraThread() {
        cameraThread.quitSafely()
        try {
            cameraThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startStreaming() {
        val ipAddress = SettingsPreferences(this.applicationContext).getIpAddress()
        if (ipAddress.isNullOrBlank()) {
            Toast.makeText(this, "Invalid IP address", Toast.LENGTH_SHORT).show()
            return
        }

        isStreaming = true
        binding.tvStatus.text = "Connecting..."

        setupEncoder()

        openCameraAndStartSession()

        socketExecutor.execute {
            try {
                val (ip, portStr) = ipAddress.split(":")
                val port = portStr.toInt()
                socket = Socket(ip, port)
                val socketWriter = DataOutputStream(socket!!.getOutputStream())

                runOnUiThread {
                    binding.tvStatus.text = "Streaming to: $ipAddress"
                    binding.btnSave.text = "Stop streaming"
                }

                val codec = mediaCodec ?: throw IllegalStateException("Encoder not initialized")
                val bufferInfo = MediaCodec.BufferInfo()

                while (isStreaming) {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outputBufferIndex >= 0) {
                        val encodedBuffer = codec.getOutputBuffer(outputBufferIndex)
                        encodedBuffer?.let {
                            val outData = ByteArray(bufferInfo.size)
                            it.get(outData)
                            it.clear()

                            socketWriter.writeInt(outData.size)
                            socketWriter.write(outData)
                            socketWriter.flush()

                            Log.d(TAG, "Sent frame size=${outData.size} bytes")
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = codec.outputFormat
                        Log.d(TAG, "Encoder output format changed: $newFormat")
                    } else {

                    }
                }

                socketWriter.close()
                socket?.close()
                socket = null

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Could not connect or stream: $e", Toast.LENGTH_LONG).show()
                    binding.tvStatus.text = "Status: Disconnected"
                    binding.btnSave.text = "Start streaming"
                }
                stopStreaming()
            }
        }
    }

    private fun stopStreaming() {
        isStreaming = false

        socketExecutor.execute {
            try {
                socket?.close()
                socket = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        closeCamera()
        releaseEncoder()

        runOnUiThread {
            binding.tvStatus.text = "Status: Disconnected"
            binding.btnSave.text = "Start streaming"
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraAndStartSession() {
        val cameraManager = getSystemService(CameraManager::class.java) ?: return
        val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera

                val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    encoderSurface?.let { addTarget(it) }

                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(60, 60))
                }

                camera.createCaptureSession(listOfNotNull(encoderSurface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(captureRequestBuilder.build(), null, cameraHandler)
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera capture session configuration failed")
                    }
                }, cameraHandler)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                camera.close()
                cameraDevice = null
            }
        }, cameraHandler)
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun setupEncoder() {
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000) 
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) 
            }
            mediaCodec = MediaCodec.createEncoderByType("video/avc")
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Encoder initialization failed", Toast.LENGTH_LONG).show()
        }
    }

    private fun releaseEncoder() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            encoderSurface?.release()
            encoderSurface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        stopCameraThread()
    }
}

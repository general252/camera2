package com.sample.cameraopenglnative;

import android.Manifest;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public class Camera2 {
    static String TAG = "Camera2";

    Handler backgroundHandler = null;

    CameraManager manager = null;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    Surface cameraTargetSurface1, cameraTargetSurface2, yuvSurface;
    ImageReader imageReader;
    ImageReader.OnImageAvailableListener imageReaderOnImageAvailableListener;

    public Camera2(CameraManager manager, Handler backgroundHandler) {
        this.manager = manager;
        this.backgroundHandler = backgroundHandler;
    }


    // -----------------------
    // Camera 打开与预览（修改为只将 Camera 输出到 OpenGLRenderer 的摄像头 Surface）
    // -----------------------
    @RequiresPermission(Manifest.permission.CAMERA)
    public void Open(Surface cameraTargetSurface1, Surface cameraTargetSurface2, ImageReader.OnImageAvailableListener imageReaderOnImageAvailableListener) throws CameraAccessException {
        for (String cameraId : manager.getCameraIdList()) {
            Log.d(TAG, String.format("cameraId: %s", cameraId));

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            int[] availableModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            for (int mode : availableModes) {
                if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
                    Log.d(TAG, "支持 连续自动对焦（适合拍照场景），自动调整焦点以适应画面变化");
                } else if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
                    Log.d(TAG, "支持 连续自动对焦（适合视频录制），对焦速度较慢但更平滑");
                }
            }

            // 获取摄像头方向
            int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Log.d(TAG, "Orientation: " + sensorOrientation);

            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                // 获取设备支持的输出分辨率
                StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (configMap != null) {
                    Size[] resolutions = configMap.getOutputSizes(ImageFormat.YUV_420_888);
                    for (Size s : resolutions) {
                        Log.d(TAG, String.format("size: %d x %d", s.getWidth(), s.getHeight()));
                    }
                }
            }
        }
        String cameraId = manager.getCameraIdList()[0];

        this.imageReaderOnImageAvailableListener = imageReaderOnImageAvailableListener;
        if (false) {
            imageReader = ImageReader.newInstance(720, 1280, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(imageReader -> {
                if (this.imageReaderOnImageAvailableListener != null) {
                    this.imageReaderOnImageAvailableListener.onImageAvailable(imageReader);
                    return;
                }

                Image image = imageReader.acquireLatestImage();
                if (image != null) {
                    if (false) {
                        Image.Plane[] planes = image.getPlanes();
                        ByteBuffer yBuffer = planes[0].getBuffer(); // Y 分量
                        ByteBuffer uBuffer = planes[1].getBuffer(); // U 分量
                        ByteBuffer vBuffer = planes[2].getBuffer(); // V 分量

                        byte[] yData = new byte[yBuffer.remaining()];
                        byte[] uData = new byte[uBuffer.remaining()];
                        byte[] vData = new byte[vBuffer.remaining()];

                        yBuffer.get(yData);
                        uBuffer.get(uData);
                        vBuffer.get(vData);
                    }

                    // 处理数据或转换为其他格式（如 NV21）
                    image.close(); // 必须关闭以释放资源
                }
            }, backgroundHandler);

            this.yuvSurface = imageReader.getSurface();
        }

        this.cameraTargetSurface1 = cameraTargetSurface1;
        this.cameraTargetSurface2 = cameraTargetSurface2;
        manager.openCamera(cameraId, stateCallback, backgroundHandler);
    }

    CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;

            var outputs = new ArrayList<Surface>();
            outputs.add(cameraTargetSurface1);
            if (cameraTargetSurface2 != null) {
                outputs.add(cameraTargetSurface2);
            }
            //outputs.add(yuvSurface);

            try {
                camera.createCaptureSession(
                        outputs,
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                captureSession = session;
                                try {
                                    CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                    // 自动对焦模式 连续自动对焦（适合视频录制），对焦速度较慢但更平滑
                                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 30));
                                    // 只添加摄像头输出目标
                                    builder.addTarget(cameraTargetSurface1);
                                    if (cameraTargetSurface2 != null) {
                                        builder.addTarget(cameraTargetSurface2);
                                    }
                                    //builder.addTarget(yuvSurface

                                    session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                                } catch (CameraAccessException e) {
                                    Log.e(TAG, "设置重复请求失败", e);
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                Log.e(TAG, "CameraCaptureSession 配置失败");
                            }
                        },
                        backgroundHandler
                );
            } catch (CameraAccessException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            Log.e(TAG, "打开 Camera 失败，错误码：" + error);
        }
    };

    public void Close() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }
}

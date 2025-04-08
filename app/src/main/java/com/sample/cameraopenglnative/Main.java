package com.sample.cameraopenglnative;

import static android.content.Context.CAMERA_SERVICE;

import android.Manifest;
import android.content.Context;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.TextureView;


import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;


public class Main {
    static String TAG = "cc";

    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private final int videoWidth = 720, videoHeight = 1280; // 确认 width 和 height 是编码器支持的分辨率（需 16 对齐）。

    Camera2 camera2 = null; //
    MediaCodec.Callback mediaCallback;
    Encoder encoder1, encoder2;
    OpenGLRender render;
    Surface previewSurface = null;

    /**
     * <uses-permission android:name="android.permission.CAMERA" />
     * <uses-feature android:name="android.hardware.camera" />
     * <p>
     * <uses-permission android:name="android.permission.INTERNET"/>
     * <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
     * <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
     * <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
     */


    @RequiresPermission(allOf = {Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA})
    void Open(CameraManager manager, TextureView previewTextureView, MediaCodec.Callback mediaCallback, ImageReader.OnImageAvailableListener imageReaderOnImageAvailableListener) throws CameraAccessException {
        // stopBackgroundThread();
        startBackgroundThread();

        this.mediaCallback = mediaCallback;

        // 注意：传入编码的 Surface（MediaCodec.createInputSurface()）以及预览的 Surface（若有预览则从 TextureView 创建）
        if (previewTextureView != null) {
            // 使用 TextureView 的 SurfaceTexture 生成预览 Surface
            previewSurface = new Surface(previewTextureView.getSurfaceTexture());
        }

        encoder1 = new Encoder(videoWidth, videoHeight, backgroundHandler); // 仅使用编码时, 请颠倒长宽
        encoder1.setCallback(_mediaCallback);
        encoder1.Open();

        encoder2 = new Encoder(864, 1056, backgroundHandler); // 仅使用编码时, 请颠倒长宽
        encoder2.setCallback(_mediaCallback2);
        encoder2.Open();

        render = new OpenGLRender(previewSurface);
        render.setEncoderSurface1(encoder1.getEncoderInputSurface(), encoder1.videoWidth, encoder1.videoHeight);
        render.setEncoderSurface2(encoder2.getEncoderInputSurface(), encoder2.videoWidth, encoder2.videoHeight);
        render.setRotationDegree(270);
        render.Init();


        camera2 = new Camera2(manager, backgroundHandler);

        camera2.Open(render.getCameraSurface1(), render.getCameraSurface2(), imageReaderOnImageAvailableListener);
        // camera2.Open(previewSurface, imageReaderOnImageAvailableListener);
        // camera2.Open(encoder.getEncoderInputSurface(), imageReaderOnImageAvailableListener);
    }


    boolean isWriteToFile = true;

    void writeToFile(FileOutputStream fos, MediaCodec codec, int index, MediaCodec.BufferInfo info) {
        if (!isWriteToFile) {
            codec.releaseOutputBuffer(index, false);
            return;
        }

        if (fos == null) {
            return;
        }

        ByteBuffer buffer = codec.getOutputBuffer(index);
        if (buffer != null) {
            byte[] data = new byte[info.size];
            buffer.get(data);
            try {
                fos.write(data);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        codec.releaseOutputBuffer(index, false);
    }

    FileOutputStream fos1 = null;
    // 收到数据保存到文件
    MediaCodec.Callback _mediaCallback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int i) {
            if (mediaCallback != null) {
                mediaCallback.onInputBufferAvailable(mediaCodec, i);
                return;
            }
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            Log.d(TAG, String.format("callback1 %d", info.size));
            if (false && mediaCallback != null) {
                mediaCallback.onOutputBufferAvailable(codec, index, info);
                return;
            }

            if (fos1 == null) {
                String externalStorageState = android.os.Environment.getExternalStorageState();
                Log.d(TAG, "externalStorageState: " + externalStorageState);

                if (externalStorageState.equals(android.os.Environment.MEDIA_MOUNTED)) {
                    // Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CameraOpenglH264");
                    if (!dir.exists()) {
                        if (!dir.mkdirs()) {
                            Log.d(TAG, "mkdirs fail");
                            return;
                        }
                    }

                    File file = new File(dir, "outfile1.h264");
                    try {
                        fos1 = new FileOutputStream(file);
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            writeToFile(fos1, codec, index, info);
        }

        @Override
        public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
            if (mediaCallback != null) {
                mediaCallback.onError(mediaCodec, e);
            }
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
            if (mediaCallback != null) {
                mediaCallback.onOutputFormatChanged(mediaCodec, mediaFormat);
            }
        }
    };

    FileOutputStream fos2 = null;
    MediaCodec.Callback _mediaCallback2 = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int i) {
            if (mediaCallback != null) {
                mediaCallback.onInputBufferAvailable(mediaCodec, i);
            }
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            Log.d(TAG, String.format("callback2 %d", info.size));

            if (fos2 == null) {
                String externalStorageState = android.os.Environment.getExternalStorageState();
                Log.d(TAG, "externalStorageState: " + externalStorageState);

                if (externalStorageState.equals(android.os.Environment.MEDIA_MOUNTED)) {
                    // Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CameraOpenglH264");
                    if (!dir.exists()) {
                        if (!dir.mkdirs()) {
                            Log.d(TAG, "mkdirs fail");
                            return;
                        }
                    }

                    File file = new File(dir, "outfile2.h264");
                    try {
                        fos2 = new FileOutputStream(file);
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            writeToFile(fos2, codec, index, info);
        }

        @Override
        public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
            if (mediaCallback != null) {
                mediaCallback.onError(mediaCodec, e);
            }
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
            if (mediaCallback != null) {
                mediaCallback.onOutputFormatChanged(mediaCodec, mediaFormat);
            }
        }
    };


    public void Close() {
        if (camera2 != null) {
            camera2.Close();
        }
        if (encoder1 != null) {
            encoder1.Close();
        }
        if (encoder2 != null) {
            encoder2.Close();
        }
        if (render != null) {
            render.Close();
        }
        stopBackgroundThread();
    }


    // -----------------------
    // 后台线程管理
    // -----------------------
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackgroundThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "停止后台线程异常", e);
            }
        }
    }

}

package com.sample.cameraopenglnative;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.widget.TextView;

import com.sample.cameraopenglnative.databinding.ActivityMainBinding;

import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'cameraopenglnative' library on application startup.
    static {
        System.loadLibrary("cameraopenglnative");
    }

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Example of a call to a native method
        TextView tv = binding.sampleText;
        tv.setText(stringFromJNI());


        previewTextureView = new TextureView(this);
        previewTextureView.setSurfaceTextureListener(textureViewListener);
        setContentView(previewTextureView);

        if (previewTextureView.isAvailable()) {
            // starWorker();
        }
    }

    /**
     * A native method that is implemented by the 'cameraopenglnative' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();

    public native void onPacket(ByteBuffer data, int size);

    public native void onYUV(ByteBuffer y, int ySize, ByteBuffer u, int uSize, ByteBuffer v, int vSize);

    @Override
    protected void onDestroy() {
        super.onDestroy();

        main.Close();
    }

    @Override
    protected void onPause() {
        super.onPause();

        main.Close();
    }

    static String TAG = "cc";
    TextureView previewTextureView;
    Main main = new Main();

    // 存储权限
    int REQUEST_CODE_STORAGE_PERMISSION = 1000;
    int REQUEST_CODE_CAMERA_PERMISSION = 1001;

    void starWorker() {
        checkPermission();

        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            main.Open(manager, previewTextureView, mediaCodecCallback, imageAvailableListener);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    TextureView.SurfaceTextureListener textureViewListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
            starWorker();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {

        }
    };

    MediaCodec.Callback mediaCodecCallback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int i) {

        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int index, @NonNull MediaCodec.BufferInfo bufferInfo) {
            ByteBuffer buffer = mediaCodec.getOutputBuffer(index);
            if (buffer != null) {
                int s = buffer.remaining();
                onPacket(buffer, s);

                Log.d(TAG, String.format("onOutputBufferAvailable: %d", s));
            }
            mediaCodec.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {

        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {

        }
    };

    ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            Image image = imageReader.acquireLatestImage();
            if (image != null) {

                if (true) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer y = planes[0].getBuffer(); // Y 分量
                    ByteBuffer u = planes[1].getBuffer(); // U 分量
                    ByteBuffer v = planes[2].getBuffer(); // V 分量

                    int ys = y.remaining();
                    int us = u.remaining();
                    int vs = v.remaining();


                    //onYUV(y, ys, u, us, v, vs);
                    Log.d(TAG, String.format("onImageAvailable: %d", ys + us + vs));
                }

                // 处理数据或转换为其他格式（如 NV21）
                image.close(); // 必须关闭以释放资源
            }
        }
    };

    void checkPermission() {
        // 检查是否已授予存储权限
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // 权限未授予，请求权限
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.CAMERA}, REQUEST_CODE_STORAGE_PERMISSION);
        } else {
            // 权限已授予，执行需要存储权限的操作
            Log.d(TAG, "存储权限已授予");
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // 权限未授予，请求权限
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE_CAMERA_PERMISSION);
        } else {
            // 权限已授予，执行需要存储权限的操作
            Log.d(TAG, "摄像头权限已授予");
        }
    }

}
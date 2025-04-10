package com.sample.cameraopenglnative;

import android.content.res.AssetManager;

import java.nio.ByteBuffer;

public class Native {
    // Used to load the 'cameraopenglnative' library on application startup.
    static {
        System.loadLibrary("cameraopenglnative");
    }


    /**
     * A native method that is implemented by the 'cameraopenglnative' native library,
     * which is packaged with this application.
     */
    public static native String stringFromJNI();

    public static native void onPacket(ByteBuffer data, int size);

    public static native void onYUV(ByteBuffer y, int ySize, ByteBuffer u, int uSize, ByteBuffer v, int vSize);

    public static native void onFrame(int fboId, int w, int h);
    public static native void setAssetManager(AssetManager assetManager);
}

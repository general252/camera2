package com.sample.cameraopenglnative;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class Encoder {

    static String TAG = "Encoder";
    private MediaCodec mediaCodec;
    private Surface encoderInputSurface;
    Handler backgroundHandler;

    public int videoWidth = 720, videoHeight = 1280;
    MediaCodec.Callback callback = null;

    public Encoder(int videoWidth, int videoHeight, Handler backgroundHandler) {
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.backgroundHandler = backgroundHandler;
    }

    public void setCallback(MediaCodec.Callback callback) {
        this.callback = callback;
    }

    public void Open() {
        try {
            int bitRate = videoWidth * videoHeight * 5;
            String codecId = MediaFormat.MIMETYPE_VIDEO_AVC;

            mediaCodec = MediaCodec.createEncoderByType(codecId);
            MediaFormat format = MediaFormat.createVideoFormat(codecId, videoWidth, videoHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5); // 关键帧间隔（秒）

            if (callback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mediaCodec.setCallback(callback, backgroundHandler);
            }

            // 从 API 级别 19（Android 4.4 KitKat）开始，
            // MediaCodec 提供了 setParameters 方法，允许在编码过程中动态调整参数，包括比特率
            // 在编码过程中动态调整视频编码器的目标比特率
            if (false) {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitRate);
                mediaCodec.setParameters(params);
            }

            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInputSurface = mediaCodec.createInputSurface();
            boolean ss = encoderInputSurface.isValid();
            mediaCodec.start();
        } catch (Exception e) {
            Log.e(TAG, "setupEncoder 异常", e);
        }
    }

    public Surface getEncoderInputSurface() {
        return this.encoderInputSurface;
    }

    public void Close() {
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "释放 MediaCodec 异常", e);
            }
        }
    }
}

package com.sample.cameraopenglnative;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class OpenGLRender {
    private static final String TAG = "OpenGLRender";
    static final int fontSize = 32;

    // 摄像头输出纹理及 SurfaceTexture
    private SurfaceTexture cameraSurfaceTexture1, cameraSurfaceTexture2;
    private int cameraTextureId1, cameraTextureId2;

    // EGL 对象及两个输出 EGLSurface
    private EGLDisplay eglDisplay;
    private EGLContext eglContext;

    android.view.Surface previewSurface;
    android.view.Surface encoderSurface1, encoderSurface2;
    int encoderSurface1Width, encoderSurface1Height;
    int encoderSurface2Width, encoderSurface2Height;
    private EGLSurface encoderEGLSurface1, encoderEGLSurface2;
    private EGLSurface previewEGLSurface; // 若预览为 null，则只输出编码

    // shader 程序
    private int cameraProgram;  // 用于绘制摄像头外部 OES 纹理
    private int textProgram;    // 用于绘制 2D 纹理

    // 全屏顶点数据（用于绘制摄像头纹理和 FBO 纹理）
    private FloatBuffer fullScreenBuffer;

    // 离屏 FBO 相关
    private int fboId1, fboId2;
    private int fboTextureId1, fboTextureId2;

    // 用于计算时间戳区域大小（单位：像素）
    private int overlayWidth, overlayHeight;

    // 时间戳相关
    private int timestampTextureId;
    private String lastTimestamp = "";
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    int rotationDegree = 270;


    static float[] rotatedTexCoordsDefault = new float[]{
            0.00f, 1.00f,
            1.00f, 1.00f,
            0.00f, 0.00f,
            1.00f, 0.00f
    };
    static float[] rotatedTexCoords90 = new float[]{
            1.00f, 0.00f,
            1.00f, 1.00f,
            0.00f, 0.00f,
            0.00f, 1.00f
    };
    static float[] rotatedTexCoords180 = new float[]{
            1.00f, 1.00f,
            0.00f, 1.00f,
            1.00f, 0.00f,
            0.00f, 0.00f
    };
    static float[] rotatedTexCoords270 = new float[]{
            0.00f, 1.00f,
            0.00f, 0.00f,
            1.00f, 1.00f,
            1.00f, 0.00f
    };

    // 顶点和片段着色器代码
    // attribute 关键字用于声明顶点属性，这些属性是每个顶点独有的数据，由 CPU 传递给顶点着色器。
    // vec4 是一个四维向量，aPosition 表示顶点的位置，通常包含 x, y, z, w 四个分量。
    // vec2 是一个二维向量，aTexCoord 表示顶点的纹理坐标，包含 s 和 t 两个分量。
    // varying 关键字用于声明可变变量，这些变量用于在顶点着色器和片段着色器之间传递数据。
    // vTexCoord 是一个二维向量，用于将顶点的纹理坐标传递给片段着色器。
    // main() 是顶点着色器的入口函数，每个顶点都会执行一次该函数。
    // gl_Position 是一个内置变量，用于指定顶点的最终位置，这里直接将 aPosition 赋值给 gl_Position，表示不进行任何变换。
    // vTexCoord 是之前声明的可变变量，将 aTexCoord 赋值给 vTexCoord，以便将纹理坐标传递给片段着色器。
    // 这段顶点着色器代码的主要功能是将输入的顶点位置直接作为最终的顶点位置输出，并将顶点的纹理坐标传递给片段着色器。它没有进行任何复杂的变换，如平移、旋转、缩放等，适用于简单的图形渲染场景。
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vTexCoord = aTexCoord;\n" +
                    "}";

    // 片段着色器：用于绘制外部 OES 纹理（Camera2 预览）
    // #extension 是 GLSL 中的预处理指令，用于启用特定的 OpenGL ES 扩展。
    // GL_OES_EGL_image_external 是一个扩展，允许在 OpenGL ES 2.0 中使用外部纹理（如摄像头输出的纹理）。
    // require 表示这个扩展是必需的，如果不支持该扩展，着色器将无法编译。
    // precision 关键字用于指定浮点数的精度。
    // mediump 表示中等精度，在大多数移动设备上可以提供较好的性能和质量平衡。
    // varying 关键字用于声明可变变量，这些变量用于在顶点着色器和片段着色器之间传递数据。
    // vec2 是一个二维向量，vTexCoord 表示从顶点着色器传递过来的纹理坐标。
    // uniform 关键字用于声明统一变量，这些变量在整个渲染过程中保持不变。
    // samplerExternalOES 是一个特殊的采样器类型，用于采样外部 OES 纹理。
    // sTexture 是采样器的名称，用于引用外部纹理。
    // main() 是片段着色器的入口函数，每个像素都会执行一次该函数。
    // gl_FragColor 是一个内置变量，用于指定当前像素的最终颜色。
    // texture2D(sTexture, vTexCoord) 是一个纹理采样函数，用于从 sTexture 中采样颜色，采样的位置由 vTexCoord 指定。
    // 这段片段着色器代码的主要功能是从外部 OES 纹理中采样颜色，并将其作为最终的像素颜色输出。它通过 vTexCoord 变量从顶点着色器获取纹理坐标，使用 sTexture 采样器从外部纹理中读取颜色值。
    private static final String FRAGMENT_SHADER_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                    "}";

    // 片段着色器：用于绘制 2D 纹理（时间戳叠加）
    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform sampler2D sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                    "}";

    /**
     * 构造函数接受编码与预览两个 Surface（若预览为 null，则只输出编码）
     */
    public OpenGLRender(android.view.Surface previewSurface) {
        this.previewSurface = previewSurface;
    }

    public void setEncoderSurface1(android.view.Surface encoderSurface, int w, int h) {
        this.encoderSurface1 = encoderSurface;
        this.encoderSurface1Width = w;
        this.encoderSurface1Height = h;
    }

    public void setEncoderSurface2(android.view.Surface encoderSurface, int w, int h) {
        this.encoderSurface2 = encoderSurface;
        this.encoderSurface2Width = w;
        this.encoderSurface2Height = h;
    }

    public void Init() {
        initEGL();
        initGL1(this.encoderSurface1Width, this.encoderSurface1Height);
        initGL2(this.encoderSurface2Width, this.encoderSurface2Height);

        // 初始化时间戳纹理
        timestampTextureId = createEmptyTexture();
    }

    /**
     * 初始化 EGL，上下文共享两个输出 Surface
     */
    private void initEGL() {
        // 1. 获取 EGL 显示设备
        // 获取默认的 EGL 显示设备，返回的 eglDisplay 用于后续的 EGL 操作。
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);

        // 初始化 EGL 显示设备，version 数组用于存储 EGL 版本信息。
        int[] version = new int[2];
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);


        // 2. 选择 EGL 配置
        // attribList: 定义了所需的 EGL 配置属性，包括红、绿、蓝、透明度通道的位数，以及支持的渲染类型（这里指定为 OpenGL ES 2.0）。
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                // 添加多重采样属性
                //EGL14.EGL_SAMPLE_BUFFERS, 1,
                //EGL14.EGL_SAMPLES, 4, // 采样数，可以根据需要调整
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        // 根据 attribList 选择合适的 EGL 配置，结果存储在 configs 数组中，numConfigs 存储实际找到的配置数量。
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);
        // 取第一个匹配的配置作为最终使用的 EGL 配置。
        EGLConfig eglConfig = configs[0];


        // 3. 创建 EGL 上下文
        // contextAttribs：定义了 EGL 上下文的属性，这里指定使用 OpenGL ES 2.0 版本
        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        // EGL14.eglCreateContext：创建一个新的 EGL 上下文，EGL14.EGL_NO_CONTEXT 表示不共享上下文。
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);


        // 4. 创建 EGL 表面
        // surfaceAttribs：定义了 EGL 表面的属性，这里设置为空。
        int[] surfaceAttribs = {EGL14.EGL_NONE};
        // EGL14.eglCreateWindowSurface：根据传入的 Surface 对象创建 EGL 窗口表面，分别用于编码输出和预览输出。如果 previewSurface 为 null，则不创建预览的 EGL 表面。
        // 创建编码输出的 EGLSurface
        encoderEGLSurface1 = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface1, surfaceAttribs, 0);
        if (encoderSurface2 != null) {
            encoderEGLSurface2 = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface2, surfaceAttribs, 0);
        }
        // 若预览 Surface 存在，则创建预览 EGLSurface
        if (previewSurface != null) {
            previewEGLSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, previewSurface, surfaceAttribs, 0);
        } else {
            previewEGLSurface = null;
        }

        // 绑定 EGL 上下文和表面
        // 初始时绑定编码输出的 EGLSurface
        // EGL14.eglMakeCurrent：将指定的 EGL 上下文和表面绑定到当前线程，这里将编码输出的 EGL 表面绑定到当前上下文，后续的 OpenGL ES 操作将在该表面上进行。
        EGL14.eglMakeCurrent(eglDisplay, encoderEGLSurface1, encoderEGLSurface1, eglContext);
    }

    /**
     * 初始化 OpenGL：创建摄像头 OES 纹理、FBO、编译 shader、设置顶点数据
     */
    private void initGL1(int videoWidth, int videoHeight) {
        // 创建摄像头 OES 纹理
        cameraTextureId1 = createOESTextureObject();
        // SurfaceTexture(cameraTextureId)：使用该纹理 ID 创建一个 SurfaceTexture 对象，用于接收摄像头的纹理数据。
        cameraSurfaceTexture1 = new SurfaceTexture(cameraTextureId1);
        cameraSurfaceTexture1.setDefaultBufferSize(videoHeight, videoWidth); // 旋转了90度导致
        // setOnFrameAvailableListener：为 SurfaceTexture 设置一个监听器，当摄像头有新的帧数据可用时，会触发 onFrameAvailable 回调，在回调中调用 drawFrame() 方法进行渲染。
        cameraSurfaceTexture1.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                // 摄像头新帧到达时回调
                drawFrame(1, cameraSurfaceTexture1, encoderEGLSurface1, cameraTextureId1, fboTextureId1, fboId1, encoderSurface1Width, encoderSurface1Height);
            }
        });

        // 全屏顶点数据（用于绘制摄像头纹理和 FBO 输出）
        // vertices：定义了一个二维数组，包含四个顶点的位置和纹理坐标，用于绘制全屏的矩形。每个顶点由四个浮点数表示，前两个是顶点的位置坐标，后两个是纹理坐标
        float[] vertices = {
                -1.00f, -1.00f, 0.00f, 1.00f,
                1.00f, -1.00f, 1.00f, 1.00f,
                -1.00f, 1.00f, 0.00f, 0.00f,
                1.00f, 1.00f, 1.00f, 0.00f,
        };
        // ByteBuffer.allocateDirect：分配一个直接字节缓冲区，用于存储顶点数据。
        // order(ByteOrder.nativeOrder())：设置字节顺序为本地字节顺序。
        // asFloatBuffer()：将字节缓冲区转换为浮点缓冲区。
        fullScreenBuffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        // put(vertices).position(0)：将顶点数据写入浮点缓冲区，并将缓冲区的位置重置为 0。
        fullScreenBuffer.put(vertices).position(0);


        // 编译 shader 程序
        cameraProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES);
        textProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);

        // 创建离屏 FBO 及附着的颜色纹理
        int[] fbo = new int[1];
        int[] fboTex = new int[1];

        // glGenFramebuffers：生成一个帧缓冲对象，并将其 ID 存储在 fbo 数组中。
        GLES20.glGenFramebuffers(1, fbo, 0);
        fboId1 = fbo[0];

        // glGenTextures：生成一个纹理对象，并将其 ID 存储在 fboTex 数组中。
        GLES20.glGenTextures(1, fboTex, 0);
        fboTextureId1 = fboTex[0];

        // glBindTexture：绑定纹理对象，使其成为当前活动的纹理。
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId1);
        // glTexImage2D：为纹理对象分配内存，并指定纹理的格式、大小和数据。
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, videoWidth, videoHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        // glTexParameterf：设置纹理的过滤参数，使用线性过滤。
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // glBindFramebuffer：绑定帧缓冲对象，使其成为当前活动的帧缓冲。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId1);
        // glFramebufferTexture2D：将纹理对象附加到帧缓冲的颜色附件上。
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureId1, 0);
        // glCheckFramebufferStatus：检查帧缓冲的状态是否完整，如果不完整则输出错误日志。
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer not complete");
        }
        // glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)：解绑帧缓冲对象，恢复默认的帧缓冲。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        // 设置颜色缓冲区的清除值为黑色（RGBA: 0,0,0,1）
        GLES20.glClearColor(0.3f, 0.3f, 0.3f, 1.0f);
    }


    private void initGL2(int videoWidth, int videoHeight) {
        // 创建摄像头 OES 纹理
        cameraTextureId2 = createOESTextureObject();
        // SurfaceTexture(cameraTextureId)：使用该纹理 ID 创建一个 SurfaceTexture 对象，用于接收摄像头的纹理数据。
        cameraSurfaceTexture2 = new SurfaceTexture(cameraTextureId2);
        cameraSurfaceTexture2.setDefaultBufferSize(videoHeight, videoWidth); // 旋转了90度导致
        // setOnFrameAvailableListener：为 SurfaceTexture 设置一个监听器，当摄像头有新的帧数据可用时，会触发 onFrameAvailable 回调，在回调中调用 drawFrame() 方法进行渲染。
        cameraSurfaceTexture2.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                // 摄像头新帧到达时回调
                drawFrame(2, cameraSurfaceTexture2, encoderEGLSurface2, cameraTextureId2, fboTextureId2, fboId2, encoderSurface2Width, encoderSurface2Height);
            }
        });

        // 全屏顶点数据（用于绘制摄像头纹理和 FBO 输出）
        // vertices：定义了一个二维数组，包含四个顶点的位置和纹理坐标，用于绘制全屏的矩形。每个顶点由四个浮点数表示，前两个是顶点的位置坐标，后两个是纹理坐标
        float[] vertices = {
                -1.00f, -1.00f, 0.00f, 1.00f,
                1.00f, -1.00f, 1.00f, 1.00f,
                -1.00f, 1.00f, 0.00f, 0.00f,
                1.00f, 1.00f, 1.00f, 0.00f,
        };
        // ByteBuffer.allocateDirect：分配一个直接字节缓冲区，用于存储顶点数据。
        // order(ByteOrder.nativeOrder())：设置字节顺序为本地字节顺序。
        // asFloatBuffer()：将字节缓冲区转换为浮点缓冲区。
        fullScreenBuffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        // put(vertices).position(0)：将顶点数据写入浮点缓冲区，并将缓冲区的位置重置为 0。
        fullScreenBuffer.put(vertices).position(0);


        // 编译 shader 程序
        cameraProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES);
        textProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);

        // 创建离屏 FBO 及附着的颜色纹理
        int[] fbo = new int[1];
        int[] fboTex = new int[1];

        // glGenFramebuffers：生成一个帧缓冲对象，并将其 ID 存储在 fbo 数组中。
        GLES20.glGenFramebuffers(1, fbo, 0);
        fboId2 = fbo[0];

        // glGenTextures：生成一个纹理对象，并将其 ID 存储在 fboTex 数组中。
        GLES20.glGenTextures(1, fboTex, 0);
        fboTextureId2 = fboTex[0];

        // glBindTexture：绑定纹理对象，使其成为当前活动的纹理。
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId2);
        // glTexImage2D：为纹理对象分配内存，并指定纹理的格式、大小和数据。
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, videoWidth, videoHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        // glTexParameterf：设置纹理的过滤参数，使用线性过滤。
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // glBindFramebuffer：绑定帧缓冲对象，使其成为当前活动的帧缓冲。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId2);
        // glFramebufferTexture2D：将纹理对象附加到帧缓冲的颜色附件上。
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureId2, 0);
        // glCheckFramebufferStatus：检查帧缓冲的状态是否完整，如果不完整则输出错误日志。
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer not complete");
        }
        // glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)：解绑帧缓冲对象，恢复默认的帧缓冲。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        // 设置颜色缓冲区的清除值为黑色（RGBA: 0,0,0,1）
        GLES20.glClearColor(0.3f, 0.3f, 0.3f, 1.0f);
    }


    // 创建摄像头 OES 纹理对象
    private int createOESTextureObject() {
        // int[] texture = new int[1];：创建一个长度为 1 的整数数组 texture，用于存储生成的纹理对象的 ID。
        int[] texture = new int[1];
        // GLES20.glGenTextures(1, texture, 0);：调用 OpenGL ES 2.0 的 glGenTextures 函数生成 1 个纹理对象，生成的纹理 ID 会被存储在 texture 数组的第一个元素中。这里的 1 表示生成的纹理数量，texture 是存储纹理 ID 的数组，0 表示从数组的第 0 个位置开始存储。
        GLES20.glGenTextures(1, texture, 0);

        // GLES20.glBindTexture：将指定的纹理对象绑定到当前的纹理单元。这里使用 GLES11Ext.GL_TEXTURE_EXTERNAL_OES 作为纹理目标，表示使用外部 OES 纹理，这种纹理通常用于处理摄像头输出的视频流。texture[0] 是之前生成的纹理对象的 ID。
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0]);

        // GLES20.glTexParameterf：用于设置纹理的参数。这里设置了两个过滤参数：
        // GLES20.GL_TEXTURE_MIN_FILTER：当纹理被缩小（即纹理图像的尺寸大于显示区域的尺寸）时使用的过滤方法。GLES20.GL_NEAREST 表示使用最近邻过滤，即选择离采样点最近的纹理像素颜色作为采样结果，这种方法速度快，但可能会导致图像出现锯齿。
        // GLES20.GL_TEXTURE_MAG_FILTER：当纹理被放大（即纹理图像的尺寸小于显示区域的尺寸）时使用的过滤方法。GLES20.GL_LINEAR 表示使用线性过滤，即对周围的纹理像素进行加权平均，得到更平滑的图像效果。
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // GLES20.glTexParameteri：同样用于设置纹理的参数，不过这里使用整数类型。设置了两个环绕参数：
        // GLES20.GL_TEXTURE_WRAP_S：表示纹理在 S 方向（通常对应纹理的水平方向）的环绕方式。GLES20.GL_CLAMP_TO_EDGE 表示当纹理坐标超出 [0, 1] 范围时，使用纹理边缘的颜色。
        // GLES20.GL_TEXTURE_WRAP_T：表示纹理在 T 方向（通常对应纹理的垂直方向）的环绕方式。同样使用 GLES20.GL_CLAMP_TO_EDGE，确保纹理边缘不会出现重复或镜像的情况。
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        return texture[0];
    }

    // 返回用于摄像头输出的 SurfaceTexture
    public SurfaceTexture getCameraSurfaceTexture1() {
        return cameraSurfaceTexture1;
    }

    public Surface getCameraSurface1() {
        return new Surface(cameraSurfaceTexture1);
    }

    public Surface getCameraSurface2() {
        return new Surface(cameraSurfaceTexture2);
    }

    public void setRotationDegree(int rotationDegree) {
        this.rotationDegree = (rotationDegree + 3600) % 360;
    }

    /**
     * 渲染流程：
     * 1. 先将摄像头预览及时间戳叠加绘制到离屏 FBO 中
     * 2. 分别切换到预览和编码的 EGLSurface，将 FBO 的纹理绘制到输出目标上
     */
    public void drawFrame(int encoderIndex, SurfaceTexture cameraSurfaceTexture, EGLSurface encoderEGLSurface, int cameraTextureId, int fboTextureId, int fboId, int videoWidth, int videoHeight) {
        // 更新摄像头纹理数据
        // 调用 SurfaceTexture 的 updateTexImage 方法，用于更新摄像头的纹理数据，确保使用的是最新的摄像头帧
        cameraSurfaceTexture.updateTexImage();

        // 渲染到离屏 FBO
        // GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)：将指定的帧缓冲对象（FBO）绑定为当前的帧缓冲，后续的渲染操作将作用于该 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        // GLES20.glViewport(0, 0, videoWidth, videoHeight)：设置视口，即指定 OpenGL 渲染的区域，这里使用视频的宽度和高度。
        GLES20.glViewport(0, 0, videoWidth, videoHeight);
        // GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)：清除颜色缓冲区，将视口区域的颜色设置为默认颜色。
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 绘制摄像头
        // 调用 drawCameraTexture 方法，将摄像头的外部 OES 纹理绘制到当前绑定的 FBO 中
        drawCameraTexture(cameraTextureId);

        // 叠加时间戳
        // 更新时间戳纹理
        updateTimestampTexture();
        // drawTexturedQuad(textTextureId)：调用该方法将时间戳纹理叠加到 FBO 中的图像上。
        drawTexturedQuad(timestampTextureId, videoWidth, videoHeight);

        // GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)：解绑 FBO，恢复默认的帧缓冲。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (encoderIndex == 1) {
            frameCount++;
            if (frameCount % 30 == 0) {
                Native.onFrame(fboId, videoWidth, videoHeight);
            }
        }


        // ② 输出到预览 EGLSurface（改为根据实际预览 Surface 尺寸设置 viewport）
        if (encoderIndex == 1 && previewEGLSurface != null) {
            // EGL14.eglMakeCurrent(eglDisplay, previewEGLSurface, previewEGLSurface, eglContext)：将预览的 EGLSurface 设置为当前的渲染目标，同时绑定 EGL 上下文。
            EGL14.eglMakeCurrent(eglDisplay, previewEGLSurface, previewEGLSurface, eglContext);

            // EGL14.eglQuerySurface：查询预览 EGLSurface 的宽度和高度。
            int[] width = new int[1];
            int[] height = new int[1];
            EGL14.eglQuerySurface(eglDisplay, previewEGLSurface, EGL14.EGL_WIDTH, width, 0);
            EGL14.eglQuerySurface(eglDisplay, previewEGLSurface, EGL14.EGL_HEIGHT, height, 0);
            int previewWidth = width[0];
            int previewHeight = height[0];

            // GLES20.glViewport(0, 0, previewWidth, previewHeight)：根据预览 EGLSurface 的实际尺寸设置视口。
            // GLES20.glViewport(0, 0, previewWidth, previewHeight);
            // adjustViewport(previewWidth, previewHeight);
            // 调整预览视口
            adjustViewport(previewWidth, previewHeight, videoWidth, videoHeight);

            // GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)：清除预览视口的颜色缓冲区。
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            // 绘制离屏 FBO 纹理到预览 Surface，确保全屏显示
            // drawFboTexture()：调用该方法将离屏 FBO 中的纹理绘制到预览 EGLSurface 上。
            drawFboTexture(fboTextureId);
            // EGL14.eglSwapBuffers(eglDisplay, previewEGLSurface)：交换前后缓冲区，将渲染结果显示在预览界面上。
            EGL14.eglSwapBuffers(eglDisplay, previewEGLSurface);
        }

        if (encoderEGLSurface != null) {
            // ③ 输出到编码目标（使用 videoWidth 和 videoHeight）
            // 将编码的 EGLSurface 设置为当前的渲染目标，同时绑定 EGL 上下文。
            EGL14.eglMakeCurrent(eglDisplay, encoderEGLSurface, encoderEGLSurface, eglContext);
            // 使用视频的宽度和高度设置视口。
            GLES20.glViewport(0, 0, videoWidth, videoHeight);
            // 调整编码视口
            //adjustViewport(encoderSurface1Width, encoderSurface1Height, videoWidth, videoHeight);
            // 清除编码视口的颜色缓冲区。
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            // 调用该方法将离屏 FBO 中的纹理绘制到编码 EGLSurface 上。
            drawFboTexture(fboTextureId);
            // 交换前后缓冲区，将渲染结果传递给编码器进行编码。
            EGL14.eglSwapBuffers(eglDisplay, encoderEGLSurface);
        }
    }
    int frameCount = 0;

    void adjustViewport(int surfaceWidth, int surfaceHeight, int videoWidth, int videoHeight) {
        float surfaceAspectRatio = (float) surfaceWidth / surfaceHeight;
        float videoAspectRatio = (float) videoWidth / videoHeight;

        // 根据旋转角度调整宽高比
        if (rotationDegree == 90 || rotationDegree == 270) {
            // videoAspectRatio = (float) videoHeight / videoWidth;
        } else {
            videoAspectRatio = (float) videoWidth / videoHeight;
        }

        int viewportWidth, viewportHeight;
        int viewportX = 0, viewportY = 0;

        if (surfaceAspectRatio > videoAspectRatio) {
            // 屏幕更宽，上下留黑边
            viewportWidth = (int) (surfaceHeight * videoAspectRatio);
            viewportHeight = surfaceHeight;
            viewportX = (surfaceWidth - viewportWidth) / 2;
        } else {
            // 屏幕更高，左右留黑边
            viewportWidth = surfaceWidth;
            viewportHeight = (int) (surfaceWidth / videoAspectRatio);
            viewportY = (surfaceHeight - viewportHeight) / 2;
        }

        GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
    }


    // 绘制摄像头 OES 纹理到当前绑定的 FBO（使用 cameraProgram）
    private void drawCameraTexture(int cameraTextureId) {
        GLES20.glUseProgram(cameraProgram);

        int aPosition = GLES20.glGetAttribLocation(cameraProgram, "aPosition");
        int aTexCoord = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord");
        int uTexture = GLES20.glGetUniformLocation(cameraProgram, "sTexture");


        // 原始的全屏顶点数据
        float[] vertices = {
                -1.00f, -1.00f, 0.00f, 1.00f,
                1.00f, -1.00f, 1.00f, 1.00f,
                -1.00f, 1.00f, 0.00f, 0.00f,
                1.00f, 1.00f, 1.00f, 0.00f,
        };

        // 根据旋转度数调整纹理坐标
        float[] rotatedTexCoords;

        switch (rotationDegree) {
            case 90:
                rotatedTexCoords = rotatedTexCoords90;
                break;
            case 180:
                rotatedTexCoords = rotatedTexCoords180;
                break;
            case 270:
                rotatedTexCoords = rotatedTexCoords270;
                break;
            default:
                rotatedTexCoords = rotatedTexCoordsDefault;
                break;
        }

        // 重新组合顶点数据，包含旋转后的纹理坐标
        float[] rotatedVertices = new float[16];
        for (int i = 0; i < 4; i++) {
            rotatedVertices[i * 4] = vertices[i * 4];
            rotatedVertices[i * 4 + 1] = vertices[i * 4 + 1];
            rotatedVertices[i * 4 + 2] = rotatedTexCoords[i * 2];
            rotatedVertices[i * 4 + 3] = rotatedTexCoords[i * 2 + 1];
        }

        // 创建新的顶点缓冲区
        FloatBuffer rotatedBuffer = ByteBuffer.allocateDirect(rotatedVertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        rotatedBuffer.put(rotatedVertices).position(0);

        // 设置顶点位置属性
        rotatedBuffer.position(0);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 4 * 4, rotatedBuffer);
        GLES20.glEnableVertexAttribArray(aPosition);

        // 设置纹理坐标属性
        rotatedBuffer.position(2);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 4 * 4, rotatedBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);

        // 激活并绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(uTexture, 0);

        // 绘制三角形带
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }


    /**
     * 修改后的方法：在左上角叠加时间戳纹理
     */
    private void drawTexturedQuad(int textureId, int videoWidth, int videoHeight) {
        GLES20.glUseProgram(textProgram);

        // 启用混合（Blending），使时间戳透明背景正确显示：
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA); // 标准 alpha 混合

        // 获取 attribute 与 uniform 位置
        int aPosition = GLES20.glGetAttribLocation(textProgram, "aPosition");
        int aTexCoord = GLES20.glGetAttribLocation(textProgram, "aTexCoord");
        int uTexture = GLES20.glGetUniformLocation(textProgram, "sTexture");

        // 根据文本尺寸计算 NDC 坐标（左上角区域）
        float w = (overlayWidth * 2.0f) / videoWidth;
        float h = (overlayHeight * 2.0f) / videoHeight;
        float o = h * 3.0f / 2.0f;
        float x1 = -1.0f + o;
        float y1 = -1.0f + o;
        float[] overlayVertices = {
                x1, y1 - h, 0.0f, 0.0f,  // 左下
                x1 + w, y1 - h, 1.0f, 0.0f,  // 右下
                x1, y1, 0.0f, 1.0f,  // 左上
                x1 + w, y1, 1.0f, 1.0f   // 右上
        };
        FloatBuffer overlayBuffer = ByteBuffer.allocateDirect(overlayVertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        overlayBuffer.put(overlayVertices).position(0);

        // 设置叠加层顶点数据
        overlayBuffer.position(0);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 4 * 4, overlayBuffer);
        GLES20.glEnableVertexAttribArray(aPosition);
        overlayBuffer.position(2);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 4 * 4, overlayBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);

        // 激活并绑定 2D 纹理（时间戳纹理）
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(uTexture, 0);

        // 绘制叠加层（使用三角形条带绘制四个顶点）
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 可选：禁用混合（如果后续绘制不需要混合）
        // GLES20.glDisable(GLES20.GL_BLEND);
    }

    // 将离屏 FBO 的纹理绘制到当前 EGLSurface上（使用 textProgram）
    private void drawFboTexture(int fboTextureId) {
        // 激活指定的着色器程序
        GLES20.glUseProgram(textProgram);

        // 获取 attribute 与 uniform 位置
        int aPosition = GLES20.glGetAttribLocation(textProgram, "aPosition"); // 获取顶点着色器中属性变量的位置。aPosition 对应顶点的位置
        int aTexCoord = GLES20.glGetAttribLocation(textProgram, "aTexCoord"); // 获取顶点着色器中属性变量的位置。aTexCoord 对应纹理坐标
        int uTexture = GLES20.glGetUniformLocation(textProgram, "sTexture");  // 获取片段着色器中统一变量的位置。sTexture 是用于采样纹理的采样器。

        FloatBuffer overlayBuffer = fullScreenBuffer;

        overlayBuffer.position(0); // 将缓冲区的位置指针设置为 0，准备读取顶点位置数据。
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 4 * 4, overlayBuffer); // 指定顶点属性的数据源。aPosition 表示顶点位置属性，2 表示每个顶点有 2 个分量（x 和 y），GLES20.GL_FLOAT 表示数据类型为浮点数，false 表示不进行归一化，4 * 4 表示每个顶点数据的总字节数，fullScreenBuffer 是数据源。
        GLES20.glEnableVertexAttribArray(aPosition); // 启用指定的顶点属性数组。

        overlayBuffer.position(2); // 将缓冲区的位置指针设置为 2，准备读取纹理坐标数据。
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 4 * 4, overlayBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord); // 启用指定的顶点属性数组。


        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); // 激活指定的纹理单元。GLES20.GL_TEXTURE0 表示纹理单元 0。
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId); // 将指定的纹理对象绑定到当前激活的纹理单元上。fboTextureId 是离屏 FBO 附着的颜色纹理的 ID。
        GLES20.glUniform1i(uTexture, 0); // 设置片段着色器中采样器的纹理单元编号。uTexture 是采样器的位置，0 表示使用纹理单元 0。
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4); // 使用当前的顶点属性数组绘制图形。GLES20.GL_TRIANGLE_STRIP 表示绘制模式为三角形带，0 表示从顶点数组的第一个顶点开始绘制，4 表示绘制 4 个顶点。
    }

    /**
     * 创建空纹理
     */
    private int createEmptyTexture() {
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return texture[0];
    }

    /**
     * 更新时间戳纹理
     */
    private void updateTimestampTexture() {
        String currentTimestamp = dateFormat.format(new Date());
        if (!currentTimestamp.equals(lastTimestamp)) {
            lastTimestamp = currentTimestamp;

            // 创建包含时间戳的 Bitmap
            Bitmap bitmap = createTimestampBitmap(currentTimestamp);

            // 更新纹理
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, timestampTextureId);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);

            bitmap.recycle();
        }
    }

    /**
     * 创建包含时间戳的 Bitmap
     */
    private Bitmap createTimestampBitmap(String timestamp) {
        Paint paint = new Paint();
        paint.setTextSize(fontSize);
        paint.setAntiAlias(true);
        paint.setColor(android.graphics.Color.WHITE);

        Rect bounds = new Rect();
        paint.getTextBounds(timestamp, 0, timestamp.length(), bounds);

        overlayWidth = bounds.width();
        overlayHeight = bounds.height();

        Bitmap bitmap = Bitmap.createBitmap(overlayWidth, overlayHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawText(timestamp, 0, overlayHeight, paint);

        return bitmap;
    }


    // 辅助方法：加载 shader 并返回 shader 对象 ID
    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    // 辅助方法：创建 shader 程序
    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);

        int program = GLES20.glCreateProgram();

        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        return program;
    }

    // 释放资源：销毁 EGLSurface、上下文及释放 OpenGL 对象
    public void Close() {
        if (eglDisplay != null) {
            if (encoderEGLSurface1 != null) {
                EGL14.eglDestroySurface(eglDisplay, encoderEGLSurface1);
            }
            if (encoderEGLSurface2 != null) {
                EGL14.eglDestroySurface(eglDisplay, encoderEGLSurface2);
            }
            if (previewEGLSurface != null) {
                EGL14.eglDestroySurface(eglDisplay, previewEGLSurface);
            }
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
    }
}

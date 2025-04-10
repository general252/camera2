#include <jni.h>
#include <string>
#include <android/log.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_sample_cameraopenglnative_Native_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_sample_cameraopenglnative_Native_onPacket(JNIEnv *env, jobject thiz, jobject data,
                                                   jint size) {
    // TODO: implement onPacket()
    uint8_t *nativeData = static_cast<uint8_t *>(env->GetDirectBufferAddress(data));
    jlong capacity = env->GetDirectBufferCapacity(data);

    printf("%p %d %lld", nativeData, size, capacity);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_sample_cameraopenglnative_Native_onYUV(JNIEnv *env, jobject thiz, jobject y,
                                                jint y_size, jobject u, jint u_size,
                                                jobject v, jint v_size) {
    uint8_t *nativeData = static_cast<uint8_t *>(env->GetDirectBufferAddress(y));
    jlong capacity = env->GetDirectBufferCapacity(y);

    printf("%p %d %lld", nativeData, y_size, capacity);
}


#include <GLES2/gl2.h>  // OpenGL ES 2.0
#include <GLES/gl.h>    // OpenGL ES 1.1
#include <GLES3/gl3.h>
#include "yolov11.h"


#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

AAssetManager *nativeAssetManager = nullptr;
std::mutex mtx;
std::string buffer;
bool haveData = false;

extern "C"
JNIEXPORT void JNICALL
Java_com_sample_cameraopenglnative_Native_onFrame(JNIEnv *env, jclass clazz, jint fbo_id, jint w,
                                                  jint h) {
    int fboId = fbo_id;
    int width = w;
    int height = h;

    static bool first = true;

    if (first) {
        first = false;

        int size = width * height * 4;
        buffer.resize(size);

        new std::thread([=](){

            YoloV11 yolo;
            bool ok = yolo.Load(nativeAssetManager,"model.ncnn.param", "model.ncnn.bin" );
            if (!ok){
                return;
            }

            while (true) {
                mtx.lock();
                if (haveData) {
                    char* ptr = buffer.data();
                    // 将内存数据加载到 OpenCV 的 Mat 对象中
                    cv::Mat imageRGBA(height, width, CV_8UC4, ptr);

                    // 如果需要，可以将 RGBA 转换为 BGR 格式（OpenCV 默认使用 BGR）
                    cv::Mat bgr;
                    cv::cvtColor(imageRGBA, bgr, cv::COLOR_RGBA2BGR);

                    std::vector<Object> objects;
                    yolo.Detect(bgr, objects);

                    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "识别到 %d 个对象", objects.size());
                    for (int i = 0; i < objects.size(); ++i) {
                        auto obj = objects[i];
                        std::string name = getLabelName(obj.label);

                        __android_log_print(ANDROID_LOG_DEBUG, "ncnn",
                                            "[%d] %d %s %5.2f (%5.2f, %5.2f) (%5.2f, %5.2f)",
                                            i + 1, obj.label, name.data(), obj.prob,
                                            obj.rect.x, obj.rect.y, obj.rect.width, obj.rect.height);
                    }
                }

                haveData = false;
                mtx.unlock();

                std::this_thread::sleep_for(std::chrono::milliseconds(5));
            }
        });
    }

    if (haveData) {
        return;
    }

    std::lock_guard<std::mutex> lock(mtx);
    // writeToFile("/storage/emulated/0/Download/CameraOpenglH264/output.raw", (char*)buffer, size);
    // 1
    // auto bgr = cv::imread("/storage/emulated/0/Download/CameraOpenglH264/bus.jpg", 1);

    // 2
    char* ptr = buffer.data();
    glBindFramebuffer(GL_FRAMEBUFFER, fboId);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, ptr);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    haveData = true;
}



extern "C"
JNIEXPORT void JNICALL
Java_com_sample_cameraopenglnative_Native_setAssetManager(JNIEnv *env, jclass clazz,  jobject asset_manager) {
    nativeAssetManager = AAssetManager_fromJava(env, asset_manager);
}
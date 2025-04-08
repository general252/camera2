#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_sample_cameraopenglnative_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}
extern "C"
JNIEXPORT void JNICALL
Java_com_sample_cameraopenglnative_MainActivity_onPacket(JNIEnv *env, jobject thiz, jobject data, jint size) {
    // TODO: implement onPacket()
    uint8_t* nativeData = static_cast<uint8_t*>(env->GetDirectBufferAddress(data));
    jlong capacity = env->GetDirectBufferCapacity(data);

    printf("%p %d %lld", nativeData, size, capacity);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_sample_cameraopenglnative_MainActivity_onYUV(JNIEnv *env, jobject thiz, jobject y,
                                                      jint y_size, jobject u, jint u_size,
                                                      jobject v, jint v_size) {
    uint8_t* nativeData = static_cast<uint8_t*>(env->GetDirectBufferAddress(y));
    jlong capacity = env->GetDirectBufferCapacity(y);

    printf("%p %d %lld", nativeData, y_size, capacity);
}
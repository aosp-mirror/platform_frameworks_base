#include "jni.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkCamera.h"

static jfieldID gNativeInstanceFieldID;

static void Camera_constructor(JNIEnv* env, jobject obj) {
    Sk3DView* view = new Sk3DView;
    env->SetIntField(obj, gNativeInstanceFieldID, (int)view);
}

static void Camera_destructor(JNIEnv* env, jobject obj) {
    delete (Sk3DView*)env->GetIntField(obj, gNativeInstanceFieldID);
}

static void Camera_save(JNIEnv* env, jobject obj) {
    Sk3DView* v = (Sk3DView*)env->GetIntField(obj, gNativeInstanceFieldID);
    v->save();
}

static void Camera_restore(JNIEnv* env, jobject obj) {
    Sk3DView* v = (Sk3DView*)env->GetIntField(obj, gNativeInstanceFieldID);
    v->restore();
}

static void Camera_translate(JNIEnv* env, jobject obj,
                             float dx, float dy, float dz) {
    Sk3DView* v = (Sk3DView*)env->GetIntField(obj, gNativeInstanceFieldID);
    v->translate(SkFloatToScalar(dx), SkFloatToScalar(dy), SkFloatToScalar(dz));
}

static void Camera_rotateX(JNIEnv* env, jobject obj, float degrees) {
    Sk3DView* v = (Sk3DView*)env->GetIntField(obj, gNativeInstanceFieldID);
    v->rotateX(SkFloatToScalar(degrees));
}

static void Camera_rotateY(JNIEnv* env, jobject obj, float degrees) {
    Sk3DView* v = (Sk3DView*)env->GetIntField(obj, gNativeInstanceFieldID);
    v->rotateY(SkFloatToScalar(degrees));
}

static void Camera_rotateZ(JNIEnv* env, jobject obj, float degrees) {
    Sk3DView* v = (Sk3DView*)env->GetIntField(obj, gNativeInstanceFieldID);
    v->rotateZ(SkFloatToScalar(degrees));
}

static void Camera_getMatrix(JNIEnv* env, jobject obj, int native_matrix) {
    Sk3DView* v = (Sk3DView*)env->GetIntField(obj, gNativeInstanceFieldID);
    v->getMatrix((SkMatrix*)native_matrix);
}

static void Camera_applyToCanvas(JNIEnv* env, jobject obj, int native_canvas) {
    Sk3DView* v = (Sk3DView*)env->GetIntField(obj, gNativeInstanceFieldID);
    v->applyToCanvas((SkCanvas*)native_canvas);
}

static float Camera_dotWithNormal(JNIEnv* env, jobject obj,
                                  float x, float y, float z) {
    Sk3DView* v = (Sk3DView*)env->GetIntField(obj, gNativeInstanceFieldID);
    SkScalar dot = v->dotWithNormal(SkFloatToScalar(x), SkFloatToScalar(y),
                                    SkFloatToScalar(z));
    return SkScalarToFloat(dot);
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gCameraMethods[] = {
    /* name, signature, funcPtr */

    { "nativeConstructor",   "()V",    (void*)Camera_constructor   },
    { "nativeDestructor",    "()V",    (void*)Camera_destructor    },
    { "save",                "()V",    (void*)Camera_save          },
    { "restore",             "()V",    (void*)Camera_restore       },
    { "translate",           "(FFF)V", (void*)Camera_translate     },
    { "rotateX",             "(F)V",   (void*)Camera_rotateX       },
    { "rotateY",             "(F)V",   (void*)Camera_rotateY       },
    { "rotateZ",             "(F)V",   (void*)Camera_rotateZ       },
    { "nativeGetMatrix",     "(I)V",   (void*)Camera_getMatrix     },
    { "nativeApplyToCanvas", "(I)V",   (void*)Camera_applyToCanvas },
    { "dotWithNormal",       "(FFF)F", (void*)Camera_dotWithNormal }
};

int register_android_graphics_Camera(JNIEnv* env);
int register_android_graphics_Camera(JNIEnv* env) {
    jclass clazz = env->FindClass("android/graphics/Camera");
    if (clazz == 0) {
        return -1;
    }
    gNativeInstanceFieldID = env->GetFieldID(clazz, "native_instance", "I");
    if (gNativeInstanceFieldID == 0) {
        return -1;
    }
    return android::AndroidRuntime::registerNativeMethods(env,
                                               "android/graphics/Camera",
                                               gCameraMethods,
                                               SK_ARRAY_COUNT(gCameraMethods));
}


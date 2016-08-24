#include "jni.h"
#include "core_jni_helpers.h"

#include "SkCamera.h"

#include "GraphicsJNI.h"
#include <hwui/Canvas.h>

static jfieldID gNativeInstanceFieldID;

static void Camera_constructor(JNIEnv* env, jobject obj) {
    Sk3DView* view = new Sk3DView;
    env->SetLongField(obj, gNativeInstanceFieldID, reinterpret_cast<jlong>(view));
}

static void Camera_destructor(JNIEnv* env, jobject obj) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* view = reinterpret_cast<Sk3DView*>(viewHandle);
    delete view;
}

static void Camera_save(JNIEnv* env, jobject obj) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    v->save();
}

static void Camera_restore(JNIEnv* env, jobject obj) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    v->restore();
}

static void Camera_translate(JNIEnv* env, jobject obj,
                             jfloat dx, jfloat dy, jfloat dz) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    v->translate(dx, dy, dz);
}

static void Camera_rotateX(JNIEnv* env, jobject obj, jfloat degrees) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    v->rotateX(degrees);
}

static void Camera_rotateY(JNIEnv* env, jobject obj, jfloat degrees) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    v->rotateY(degrees);
}

static void Camera_rotateZ(JNIEnv* env, jobject obj, jfloat degrees) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    v->rotateZ(degrees);
}

static void Camera_rotate(JNIEnv* env, jobject obj, jfloat x, jfloat y, jfloat z) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    v->rotateX(x);
    v->rotateY(y);
    v->rotateZ(z);
}

static void Camera_setLocation(JNIEnv* env, jobject obj, jfloat x, jfloat y, jfloat z) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    v->setCameraLocation(x, y, z);
}

static jfloat Camera_getLocationX(JNIEnv* env, jobject obj) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    return SkScalarToFloat(v->getCameraLocationX());
}

static jfloat Camera_getLocationY(JNIEnv* env, jobject obj) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    return SkScalarToFloat(v->getCameraLocationY());
}

static jfloat Camera_getLocationZ(JNIEnv* env, jobject obj) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    return SkScalarToFloat(v->getCameraLocationZ());
}

static void Camera_getMatrix(JNIEnv* env, jobject obj, jlong matrixHandle) {
    SkMatrix* native_matrix =  reinterpret_cast<SkMatrix*>(matrixHandle);
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    v->getMatrix(native_matrix);
}

static void Camera_applyToCanvas(JNIEnv* env, jobject obj, jlong canvasHandle) {
    SkCanvas* canvas = reinterpret_cast<android::Canvas*>(canvasHandle)->asSkCanvas();
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    v->applyToCanvas(canvas);
}

static jfloat Camera_dotWithNormal(JNIEnv* env, jobject obj,
                                  jfloat x, jfloat y, jfloat z) {
    jlong viewHandle = env->GetLongField(obj, gNativeInstanceFieldID);
    Sk3DView* v = reinterpret_cast<Sk3DView*>(viewHandle);
    SkScalar dot = v->dotWithNormal(x, y, z);
    return SkScalarToFloat(dot);
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static const JNINativeMethod gCameraMethods[] = {
    /* name, signature, funcPtr */

    { "nativeConstructor",   "()V",    (void*)Camera_constructor   },
    { "nativeDestructor",    "()V",    (void*)Camera_destructor    },
    { "save",                "()V",    (void*)Camera_save          },
    { "restore",             "()V",    (void*)Camera_restore       },
    { "translate",           "(FFF)V", (void*)Camera_translate     },
    { "rotateX",             "(F)V",   (void*)Camera_rotateX       },
    { "rotateY",             "(F)V",   (void*)Camera_rotateY       },
    { "rotateZ",             "(F)V",   (void*)Camera_rotateZ       },
    { "rotate",              "(FFF)V", (void*)Camera_rotate        },
    { "setLocation",         "(FFF)V", (void*)Camera_setLocation   },
    { "getLocationX",        "()F",    (void*)Camera_getLocationX  },
    { "getLocationY",        "()F",    (void*)Camera_getLocationY  },
    { "getLocationZ",        "()F",    (void*)Camera_getLocationZ  },
    { "nativeGetMatrix",     "(J)V",   (void*)Camera_getMatrix     },
    { "nativeApplyToCanvas", "(J)V",   (void*)Camera_applyToCanvas },
    { "dotWithNormal",       "(FFF)F", (void*)Camera_dotWithNormal }
};

int register_android_graphics_Camera(JNIEnv* env) {
    jclass clazz = android::FindClassOrDie(env, "android/graphics/Camera");
    gNativeInstanceFieldID = android::GetFieldIDOrDie(env, clazz, "native_instance", "J");
    return android::RegisterMethodsOrDie(env, "android/graphics/Camera", gCameraMethods,
                                         NELEM(gCameraMethods));
}

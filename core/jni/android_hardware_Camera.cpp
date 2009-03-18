/*
**
** Copyright 2008, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

//#define LOG_NDEBUG 0
#define LOG_TAG "Camera-JNI"
#include <utils/Log.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <ui/Surface.h>
#include <ui/Camera.h>
#include <utils/IMemory.h>

using namespace android;

enum CallbackMessageID {
    kShutterCallback = 0,
    kRawCallback = 1,
    kJpegCallback = 2,
    kPreviewCallback = 3,
    kAutoFocusCallback = 4,
    kErrorCallback = 5
};

enum CameraError {
    kCameraErrorUnknown = 1,
    kCameraErrorMediaServer = 100
};


struct fields_t {
    jfieldID    context;
    jfieldID    surface;
    jmethodID   post_event;
};

static fields_t fields;
static Mutex sLock;

struct camera_context_t {
    jobject     mCameraJObjectWeak;     // weak reference to java object
    jclass      mCameraJClass;          // strong reference to java class
    sp<Camera>  mCamera;                // strong reference to native object 
};

sp<Camera> get_native_camera(JNIEnv *env, jobject thiz, camera_context_t** pContext)
{
    sp<Camera> camera;
    Mutex::Autolock _l(sLock);
    camera_context_t* context = reinterpret_cast<camera_context_t*>(env->GetIntField(thiz, fields.context));
    if (context != NULL) {
        camera = context->mCamera;
    }
    LOGV("get_native_camera: context=%p, camera=%p", context, camera.get());
    if (camera == 0) {
        jniThrowException(env, "java/lang/RuntimeException", "Method called after release()");
    }

    if (pContext != NULL) *pContext = context;
    return camera;
}

static void err_callback(status_t err, void *cookie)
{
    camera_context_t* context = reinterpret_cast<camera_context_t*>(cookie);
    if ((context == NULL) || (context->mCamera == 0)) return;

    LOGV("err_callback: context=%p, camera=%p", context, context->mCamera.get());

    int error;
    switch (err) {
    case DEAD_OBJECT:
        error = kCameraErrorMediaServer;
        break;
    default:
        error = kCameraErrorUnknown;
        break;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        LOGE("err_callback on dead VM");
        return;
    }
    env->CallStaticVoidMethod(context->mCameraJClass, fields.post_event,
            context->mCameraJObjectWeak, kErrorCallback, error, 0, NULL);
}

// connect to camera service
static void android_hardware_Camera_native_setup(JNIEnv *env, jobject thiz, jobject weak_this)
{
    sp<Camera> camera = Camera::connect();

    if (camera == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }

    // make sure camera hardware is alive
    if (camera->getStatus() != NO_ERROR) {
        jniThrowException(env, "java/io/IOException", "Camera initialization failed");
        return;
    }

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        LOGE("Can't find android/hardware/Camera");
        // XXX no idea what to throw here, can this even happen?
        jniThrowException(env, "java/lang/Exception", NULL);
        return;
    }

    // We use a weak reference so the Camera object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    camera_context_t* context = new camera_context_t;
    context->mCameraJObjectWeak = env->NewGlobalRef(weak_this);
    context->mCameraJClass = (jclass)env->NewGlobalRef(clazz);
    context->mCamera = camera;

    // save context in opaque field
    env->SetIntField(thiz, fields.context, (int)context);

    LOGV("native_setup: mCameraJObjectWeak=%x, camera_obj=%x, context=%p",
            (int)context->mCameraJObjectWeak, (int)thiz, context);

    // set error callback
    camera->setErrorCallback(err_callback, context);
}

// disconnect from camera service
// It's okay to call this when the native camera context is already null.
// This handles the case where the user has called release() and the
// finalizer is invoked later.
static void android_hardware_Camera_release(JNIEnv *env, jobject thiz)
{
    camera_context_t* context = NULL;
    sp<Camera> camera;
    {
        Mutex::Autolock _l(sLock);
        context = reinterpret_cast<camera_context_t*>(env->GetIntField(thiz, fields.context));

        // Make sure we do not attempt to callback on a deleted Java object.
        env->SetIntField(thiz, fields.context, 0);
    }

    // clean up if release has not been called before
    if (context != NULL) {
        camera = context->mCamera;
        context->mCamera.clear();
        LOGV("native_release: context=%p camera=%p", context, camera.get());

        // clear callbacks
        if (camera != NULL) {
            camera->setPreviewCallback(NULL, NULL, FRAME_CALLBACK_FLAG_NOOP);
            camera->setErrorCallback(NULL, NULL);
            camera->disconnect();
            env->DeleteGlobalRef(context->mCameraJObjectWeak);
            env->DeleteGlobalRef(context->mCameraJClass);
        }

        // remove context to prevent further Java access
        delete context;
    }
}

static void android_hardware_Camera_setPreviewDisplay(JNIEnv *env, jobject thiz, jobject jSurface)
{
    LOGV("setPreviewDisplay");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    sp<Surface> surface = reinterpret_cast<Surface*>(env->GetIntField(jSurface, fields.surface));
    if (camera->setPreviewDisplay(surface) != NO_ERROR) {
        jniThrowException(env, "java/io/IOException", "setPreviewDisplay failed");
    }
}

static void preview_callback(const sp<IMemory>& mem, void *cookie)
{
    LOGV("preview_callback");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        LOGE("preview_callback on dead VM");
        return;
    }
    camera_context_t* context = reinterpret_cast<camera_context_t*>(cookie);
    if ((context == NULL) || (context->mCamera == 0)) {
        LOGW("context or camera is NULL in preview_callback");
        return;
    }
    LOGV("native_release: context=%p camera=%p", context, context->mCamera.get());

    int arg1 = 0, arg2 = 0;
    jobject obj = NULL;

    ssize_t offset;
    size_t size;
    sp<IMemoryHeap> heap = mem->getMemory(&offset, &size);

    uint8_t *data = ((uint8_t *)heap->base()) + offset;

    jbyteArray array = env->NewByteArray(size);
    if (array == NULL) {
        LOGE("Couldn't allocate byte array for YUV data");
        env->ExceptionClear();
        return;
    }

    jbyte *bytes = env->GetByteArrayElements(array, NULL);
    memcpy(bytes, data, size);
    env->ReleaseByteArrayElements(array, bytes, 0);

    obj = array;

    env->CallStaticVoidMethod(context->mCameraJClass, fields.post_event,
            context->mCameraJObjectWeak, kPreviewCallback, arg1, arg2, obj);
    env->DeleteLocalRef(array);
}

static void android_hardware_Camera_startPreview(JNIEnv *env, jobject thiz)
{
    LOGV("startPreview");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    if (camera->startPreview() != NO_ERROR) {
        jniThrowException(env, "java/io/IOException", "startPreview failed");
        return;
    }
}

static void android_hardware_Camera_stopPreview(JNIEnv *env, jobject thiz)
{
    LOGV("stopPreview");
    sp<Camera> c = get_native_camera(env, thiz, NULL);
    if (c == 0) return;

    c->stopPreview();
}

static bool android_hardware_Camera_previewEnabled(JNIEnv *env, jobject thiz)
{
    LOGV("previewEnabled");
    sp<Camera> c = get_native_camera(env, thiz, NULL);
    if (c == 0) return false;

    return c->previewEnabled();
}

static void android_hardware_Camera_setHasPreviewCallback(JNIEnv *env, jobject thiz, jboolean installed, jboolean oneshot)
{
    // Important: Only install preview_callback if the Java code has called
    // setPreviewCallback() with a non-null value, otherwise we'd pay to memcpy
    // each preview frame for nothing.
    camera_context_t* context;
    sp<Camera> camera = get_native_camera(env, thiz, &context);
    if (camera == 0) return;

    int callback_flag;
    if (installed) {
        callback_flag = oneshot ? FRAME_CALLBACK_FLAG_BARCODE_SCANNER : FRAME_CALLBACK_FLAG_CAMERA;
    } else {
        callback_flag = FRAME_CALLBACK_FLAG_NOOP;
    }
    camera->setPreviewCallback(installed ? preview_callback : NULL, context, callback_flag);
}

static void autofocus_callback_impl(bool success, void *cookie)
{
    LOGV("autoFocusCallback");
    camera_context_t* context = reinterpret_cast<camera_context_t*>(cookie);

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        LOGE("autofocus_callback on dead VM");
        return;
    }
    env->CallStaticVoidMethod(context->mCameraJClass, fields.post_event,
            context->mCameraJObjectWeak, kAutoFocusCallback, success, 0, NULL);
}

static void android_hardware_Camera_autoFocus(JNIEnv *env, jobject thiz)
{
    LOGV("autoFocus");
    camera_context_t* context;
    sp<Camera> c = get_native_camera(env, thiz, &context);
    if (c == 0) return;

    c->setAutoFocusCallback(autofocus_callback_impl, context);
    if (c->autoFocus() != NO_ERROR) {
        jniThrowException(env, "java/io/IOException", "autoFocus failed");
    }
}

static void jpeg_callback(const sp<IMemory>& mem, void *cookie)
{
    LOGV("jpegCallback");
    camera_context_t* context = reinterpret_cast<camera_context_t*>(cookie);

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        LOGE("jpeg`_callback on dead VM");
        return;
    }
    int arg1 = 0, arg2 = 0;
    jobject obj = NULL;

    if (mem == NULL) {
        env->CallStaticVoidMethod(context->mCameraJClass, fields.post_event,
                                  context->mCameraJObjectWeak, kJpegCallback, arg1, arg2, NULL);
        return;
    }
    ssize_t offset;
    size_t size;
    sp<IMemoryHeap> heap = mem->getMemory(&offset, &size);
    LOGV("jpeg_callback: mem off=%d, size=%d", offset, size);

    uint8_t *heap_base = (uint8_t *)heap->base();
    if (heap_base == NULL) {
        LOGE("YUV heap is NULL");
        return;
    }

    uint8_t *data = heap_base + offset;

    jbyteArray array = env->NewByteArray(size);
    if (array == NULL) {
        LOGE("Couldn't allocate byte array for JPEG data");
        env->ExceptionClear();
        return;
    }

    jbyte *bytes = env->GetByteArrayElements(array, NULL);
    memcpy(bytes, data, size);
    env->ReleaseByteArrayElements(array, bytes, 0);

    obj = array;

    env->CallStaticVoidMethod(context->mCameraJClass, fields.post_event,
                              context->mCameraJObjectWeak, kJpegCallback, arg1, arg2, obj);
    env->DeleteLocalRef(array);
}

static void shutter_callback_impl(void *cookie)
{
    LOGV("shutterCallback");
    camera_context_t* context = reinterpret_cast<camera_context_t*>(cookie);

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        LOGE("shutter_callback on dead VM");
        return;
    }
    env->CallStaticVoidMethod(context->mCameraJClass, fields.post_event,
                              context->mCameraJObjectWeak, kShutterCallback, 0, 0, NULL);
}

static void raw_callback(const sp<IMemory>& mem __attribute__((unused)),
                         void *cookie)
{
    LOGV("rawCallback");
    camera_context_t* context = reinterpret_cast<camera_context_t*>(cookie);

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        LOGE("raw_callback on dead VM");
        return;
    }
    env->CallStaticVoidMethod(context->mCameraJClass, fields.post_event,
                              context->mCameraJObjectWeak, kRawCallback, 0, 0, NULL);
}

static void android_hardware_Camera_takePicture(JNIEnv *env, jobject thiz)
{
    LOGV("takePicture");
    camera_context_t* context;
    sp<Camera> camera = get_native_camera(env, thiz, &context);
    if (camera == 0) return;

    camera->setShutterCallback(shutter_callback_impl, context);
    camera->setRawCallback(raw_callback, context);
    camera->setJpegCallback(jpeg_callback, context);
    if (camera->takePicture() != NO_ERROR) {
        jniThrowException(env, "java/io/IOException", "takePicture failed");
        return;
    }

    return;
}

static void android_hardware_Camera_setParameters(JNIEnv *env, jobject thiz, jstring params)
{
    LOGV("setParameters");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    const jchar* str = env->GetStringCritical(params, 0);
    String8 params8;
    if (params) {
        params8 = String8(str, env->GetStringLength(params));
        env->ReleaseStringCritical(params, str);
    }
    if (camera->setParameters(params8) != NO_ERROR) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "setParameters failed");
        return;
    }
}

static jstring android_hardware_Camera_getParameters(JNIEnv *env, jobject thiz)
{
    LOGV("getParameters");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return 0;

    return env->NewStringUTF(camera->getParameters().string());
}

static void android_hardware_Camera_reconnect(JNIEnv *env, jobject thiz)
{
    LOGV("reconnect");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    if (camera->reconnect() != NO_ERROR) {
        jniThrowException(env, "java/io/IOException", "reconnect failed");
        return;
    }
}

static jint android_hardware_Camera_lock(JNIEnv *env, jobject thiz)
{
    LOGV("lock");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return INVALID_OPERATION;
    return (jint) camera->lock();
}

static jint android_hardware_Camera_unlock(JNIEnv *env, jobject thiz)
{
    LOGV("unlock");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return INVALID_OPERATION;
    return (jint) camera->unlock();
}

//-------------------------------------------------

static JNINativeMethod camMethods[] = {
  { "native_setup",
    "(Ljava/lang/Object;)V",
    (void*)android_hardware_Camera_native_setup },
  { "native_release",
    "()V",
    (void*)android_hardware_Camera_release },
  { "setPreviewDisplay",
    "(Landroid/view/Surface;)V",
    (void *)android_hardware_Camera_setPreviewDisplay },
  { "startPreview",
    "()V",
    (void *)android_hardware_Camera_startPreview },
  { "stopPreview",
    "()V",
    (void *)android_hardware_Camera_stopPreview },
  { "previewEnabled",
    "()Z",
    (void *)android_hardware_Camera_previewEnabled },
  { "setHasPreviewCallback",
    "(ZZ)V",
    (void *)android_hardware_Camera_setHasPreviewCallback },
  { "native_autoFocus",
    "()V",
    (void *)android_hardware_Camera_autoFocus },
  { "native_takePicture",
    "()V",
    (void *)android_hardware_Camera_takePicture },
  { "native_setParameters",
    "(Ljava/lang/String;)V",
    (void *)android_hardware_Camera_setParameters },
  { "native_getParameters",
    "()Ljava/lang/String;",
    (void *)android_hardware_Camera_getParameters },
  { "reconnect",
    "()V",
    (void*)android_hardware_Camera_reconnect },
  { "lock",
    "()I",
    (void*)android_hardware_Camera_lock },
  { "unlock",
    "()I",
    (void*)android_hardware_Camera_unlock },
};

struct field {
    const char *class_name;
    const char *field_name;
    const char *field_type;
    jfieldID   *jfield;
};

static int find_fields(JNIEnv *env, field *fields, int count)
{
    for (int i = 0; i < count; i++) {
        field *f = &fields[i];
        jclass clazz = env->FindClass(f->class_name);
        if (clazz == NULL) {
            LOGE("Can't find %s", f->class_name);
            return -1;
        }

        jfieldID field = env->GetFieldID(clazz, f->field_name, f->field_type);
        if (field == NULL) {
            LOGE("Can't find %s.%s", f->class_name, f->field_name);
            return -1;
        }

        *(f->jfield) = field;
    }

    return 0;
}

// Get all the required offsets in java class and register native functions
int register_android_hardware_Camera(JNIEnv *env)
{
    field fields_to_find[] = {
        { "android/hardware/Camera", "mNativeContext",   "I", &fields.context },
        { "android/view/Surface",    "mSurface",         "I", &fields.surface }
    };

    if (find_fields(env, fields_to_find, NELEM(fields_to_find)) < 0)
        return -1;

    jclass clazz = env->FindClass("android/hardware/Camera");
    fields.post_event = env->GetStaticMethodID(clazz, "postEventFromNative",
                                               "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    if (fields.post_event == NULL) {
        LOGE("Can't find android/hardware/Camera.postEventFromNative");
        return -1;
    }


    // Register native functions
    return AndroidRuntime::registerNativeMethods(env, "android/hardware/Camera",
                                              camMethods, NELEM(camMethods));
}


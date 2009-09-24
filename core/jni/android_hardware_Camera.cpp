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

struct fields_t {
    jfieldID    context;
    jfieldID    surface;
    jmethodID   post_event;
};

static fields_t fields;
static Mutex sLock;

// provides persistent context for calls from native code to Java
class JNICameraContext: public CameraListener
{
public:
    JNICameraContext(JNIEnv* env, jobject weak_this, jclass clazz, const sp<Camera>& camera);
    ~JNICameraContext() { release(); }
    virtual void notify(int32_t msgType, int32_t ext1, int32_t ext2);
    virtual void postData(int32_t msgType, const sp<IMemory>& dataPtr);
    virtual void postDataTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr);
    sp<Camera> getCamera() { Mutex::Autolock _l(mLock); return mCamera; }
    void release();

private:
    void copyAndPost(JNIEnv* env, const sp<IMemory>& dataPtr, int msgType);

    jobject     mCameraJObjectWeak;     // weak reference to java object
    jclass      mCameraJClass;          // strong reference to java class
    sp<Camera>  mCamera;                // strong reference to native object 
    Mutex       mLock;
};

sp<Camera> get_native_camera(JNIEnv *env, jobject thiz, JNICameraContext** pContext)
{
    sp<Camera> camera;
    Mutex::Autolock _l(sLock);
    JNICameraContext* context = reinterpret_cast<JNICameraContext*>(env->GetIntField(thiz, fields.context));
    if (context != NULL) {
        camera = context->getCamera();
    }
    LOGV("get_native_camera: context=%p, camera=%p", context, camera.get());
    if (camera == 0) {
        jniThrowException(env, "java/lang/RuntimeException", "Method called after release()");
    }

    if (pContext != NULL) *pContext = context;
    return camera;
}

JNICameraContext::JNICameraContext(JNIEnv* env, jobject weak_this, jclass clazz, const sp<Camera>& camera)
{
    mCameraJObjectWeak = env->NewGlobalRef(weak_this);
    mCameraJClass = (jclass)env->NewGlobalRef(clazz);
    mCamera = camera;
}

void JNICameraContext::release()
{
    LOGV("release");
    Mutex::Autolock _l(mLock);
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    if (mCameraJObjectWeak != NULL) {
        env->DeleteGlobalRef(mCameraJObjectWeak);
        mCameraJObjectWeak = NULL;
    }
    if (mCameraJClass != NULL) {
        env->DeleteGlobalRef(mCameraJClass);
        mCameraJClass = NULL;
    }
    mCamera.clear();
}

void JNICameraContext::notify(int32_t msgType, int32_t ext1, int32_t ext2)
{
    LOGV("notify");

    // VM pointer will be NULL if object is released
    Mutex::Autolock _l(mLock);
    if (mCameraJObjectWeak == NULL) {
        LOGW("callback on dead camera object");
        return;
    }
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->CallStaticVoidMethod(mCameraJClass, fields.post_event,
            mCameraJObjectWeak, msgType, ext1, ext2, NULL);
}

void JNICameraContext::copyAndPost(JNIEnv* env, const sp<IMemory>& dataPtr, int msgType)
{
    jbyteArray obj = NULL;

    // allocate Java byte array and copy data
    if (dataPtr != NULL) {
        ssize_t offset;
        size_t size;
        sp<IMemoryHeap> heap = dataPtr->getMemory(&offset, &size);
        LOGV("postData: off=%d, size=%d", offset, size);
        uint8_t *heapBase = (uint8_t*)heap->base();

        if (heapBase != NULL) {
            const jbyte* data = reinterpret_cast<const jbyte*>(heapBase + offset);
            obj = env->NewByteArray(size);
            if (obj == NULL) {
                LOGE("Couldn't allocate byte array for JPEG data");
                env->ExceptionClear();
            } else {
                env->SetByteArrayRegion(obj, 0, size, data);
            }
        } else {
            LOGE("image heap is NULL");
        }
    }

    // post image data to Java
    env->CallStaticVoidMethod(mCameraJClass, fields.post_event,
            mCameraJObjectWeak, msgType, 0, 0, obj);
    if (obj) {
        env->DeleteLocalRef(obj);
    }
}

void JNICameraContext::postData(int32_t msgType, const sp<IMemory>& dataPtr)
{
    // VM pointer will be NULL if object is released
    Mutex::Autolock _l(mLock);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (mCameraJObjectWeak == NULL) {
        LOGW("callback on dead camera object");
        return;
    }

    // return data based on callback type
    switch(msgType) {
    case CAMERA_MSG_VIDEO_FRAME:
        // should never happen
        break;
    // don't return raw data to Java
    case CAMERA_MSG_RAW_IMAGE:
        LOGV("rawCallback");
        env->CallStaticVoidMethod(mCameraJClass, fields.post_event,
                mCameraJObjectWeak, msgType, 0, 0, NULL);
        break;
    default:
        // TODO: Change to LOGV
        LOGD("dataCallback(%d, %p)", msgType, dataPtr.get());
        copyAndPost(env, dataPtr, msgType);
        break;
    }
}

void JNICameraContext::postDataTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr)
{
    // TODO: plumb up to Java. For now, just drop the timestamp
    postData(msgType, dataPtr);
}

// connect to camera service
static void android_hardware_Camera_native_setup(JNIEnv *env, jobject thiz, jobject weak_this)
{
    sp<Camera> camera = Camera::connect();

    if (camera == NULL) {
        jniThrowException(env, "java/lang/RuntimeException",
                          "Fail to connect to camera service");
        return;
    }

    // make sure camera hardware is alive
    if (camera->getStatus() != NO_ERROR) {
        jniThrowException(env, "java/lang/RuntimeException", "Camera initialization failed");
        return;
    }

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Can't find android/hardware/Camera");
        return;
    }

    // We use a weak reference so the Camera object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    sp<JNICameraContext> context = new JNICameraContext(env, weak_this, clazz, camera);
    context->incStrong(thiz);
    camera->setListener(context);

    // save context in opaque field
    env->SetIntField(thiz, fields.context, (int)context.get());
}

// disconnect from camera service
// It's okay to call this when the native camera context is already null.
// This handles the case where the user has called release() and the
// finalizer is invoked later.
static void android_hardware_Camera_release(JNIEnv *env, jobject thiz)
{
    // TODO: Change to LOGV
    LOGD("release camera");
    JNICameraContext* context = NULL;
    sp<Camera> camera;
    {
        Mutex::Autolock _l(sLock);
        context = reinterpret_cast<JNICameraContext*>(env->GetIntField(thiz, fields.context));

        // Make sure we do not attempt to callback on a deleted Java object.
        env->SetIntField(thiz, fields.context, 0);
    }

    // clean up if release has not been called before
    if (context != NULL) {
        camera = context->getCamera();
        context->release();
        LOGV("native_release: context=%p camera=%p", context, camera.get());

        // clear callbacks
        if (camera != NULL) {
            camera->setPreviewCallbackFlags(FRAME_CALLBACK_FLAG_NOOP);
            camera->disconnect();
        }

        // remove context to prevent further Java access
        context->decStrong(thiz);
    }
}

static void android_hardware_Camera_setPreviewDisplay(JNIEnv *env, jobject thiz, jobject jSurface)
{
    LOGV("setPreviewDisplay");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    sp<Surface> surface = NULL;
    if (jSurface != NULL) {
        surface = reinterpret_cast<Surface*>(env->GetIntField(jSurface, fields.surface));
    }
    if (camera->setPreviewDisplay(surface) != NO_ERROR) {
        jniThrowException(env, "java/io/IOException", "setPreviewDisplay failed");
    }
}

static void android_hardware_Camera_startPreview(JNIEnv *env, jobject thiz)
{
    LOGV("startPreview");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    if (camera->startPreview() != NO_ERROR) {
        jniThrowException(env, "java/lang/RuntimeException", "startPreview failed");
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
    JNICameraContext* context;
    sp<Camera> camera = get_native_camera(env, thiz, &context);
    if (camera == 0) return;

    int callback_flag;
    if (installed) {
        callback_flag = oneshot ? FRAME_CALLBACK_FLAG_BARCODE_SCANNER : FRAME_CALLBACK_FLAG_CAMERA;
    } else {
        callback_flag = FRAME_CALLBACK_FLAG_NOOP;
    }
    camera->setPreviewCallbackFlags(callback_flag);
}

static void android_hardware_Camera_autoFocus(JNIEnv *env, jobject thiz)
{
    LOGV("autoFocus");
    JNICameraContext* context;
    sp<Camera> c = get_native_camera(env, thiz, &context);
    if (c == 0) return;

    if (c->autoFocus() != NO_ERROR) {
        jniThrowException(env, "java/lang/RuntimeException", "autoFocus failed");
    }
}

static void android_hardware_Camera_takePicture(JNIEnv *env, jobject thiz)
{
    LOGV("takePicture");
    JNICameraContext* context;
    sp<Camera> camera = get_native_camera(env, thiz, &context);
    if (camera == 0) return;

    if (camera->takePicture() != NO_ERROR) {
        jniThrowException(env, "java/lang/RuntimeException", "takePicture failed");
        return;
    }
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
        jniThrowException(env, "java/lang/RuntimeException", "setParameters failed");
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


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
#include <android_runtime/android_graphics_SurfaceTexture.h>
#include <android_runtime/android_view_Surface.h>

#include <cutils/properties.h>
#include <utils/Vector.h>
#include <utils/Errors.h>

#include <gui/GLConsumer.h>
#include <gui/Surface.h>
#include <camera/Camera.h>
#include <binder/IMemory.h>

using namespace android;

enum {
    // Keep up to date with Camera.java
    CAMERA_HAL_API_VERSION_NORMAL_CONNECT = -2,
};

struct fields_t {
    jfieldID    context;
    jfieldID    facing;
    jfieldID    orientation;
    jfieldID    canDisableShutterSound;
    jfieldID    face_rect;
    jfieldID    face_score;
    jfieldID    face_id;
    jfieldID    face_left_eye;
    jfieldID    face_right_eye;
    jfieldID    face_mouth;
    jfieldID    rect_left;
    jfieldID    rect_top;
    jfieldID    rect_right;
    jfieldID    rect_bottom;
    jfieldID    point_x;
    jfieldID    point_y;
    jmethodID   post_event;
    jmethodID   rect_constructor;
    jmethodID   face_constructor;
    jmethodID   point_constructor;
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
    virtual void postData(int32_t msgType, const sp<IMemory>& dataPtr,
                          camera_frame_metadata_t *metadata);
    virtual void postDataTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr);
    void postMetadata(JNIEnv *env, int32_t msgType, camera_frame_metadata_t *metadata);
    void addCallbackBuffer(JNIEnv *env, jbyteArray cbb, int msgType);
    void setCallbackMode(JNIEnv *env, bool installed, bool manualMode);
    sp<Camera> getCamera() { Mutex::Autolock _l(mLock); return mCamera; }
    bool isRawImageCallbackBufferAvailable() const;
    void release();

private:
    void copyAndPost(JNIEnv* env, const sp<IMemory>& dataPtr, int msgType);
    void clearCallbackBuffers_l(JNIEnv *env, Vector<jbyteArray> *buffers);
    void clearCallbackBuffers_l(JNIEnv *env);
    jbyteArray getCallbackBuffer(JNIEnv *env, Vector<jbyteArray> *buffers, size_t bufferSize);

    jobject     mCameraJObjectWeak;     // weak reference to java object
    jclass      mCameraJClass;          // strong reference to java class
    sp<Camera>  mCamera;                // strong reference to native object
    jclass      mFaceClass;  // strong reference to Face class
    jclass      mRectClass;  // strong reference to Rect class
    jclass      mPointClass;  // strong reference to Point class
    Mutex       mLock;

    /*
     * Global reference application-managed raw image buffer queue.
     *
     * Manual-only mode is supported for raw image callbacks, which is
     * set whenever method addCallbackBuffer() with msgType =
     * CAMERA_MSG_RAW_IMAGE is called; otherwise, null is returned
     * with raw image callbacks.
     */
    Vector<jbyteArray> mRawImageCallbackBuffers;

    /*
     * Application-managed preview buffer queue and the flags
     * associated with the usage of the preview buffer callback.
     */
    Vector<jbyteArray> mCallbackBuffers; // Global reference application managed byte[]
    bool mManualBufferMode;              // Whether to use application managed buffers.
    bool mManualCameraCallbackSet;       // Whether the callback has been set, used to
                                         // reduce unnecessary calls to set the callback.
};

bool JNICameraContext::isRawImageCallbackBufferAvailable() const
{
    return !mRawImageCallbackBuffers.isEmpty();
}

sp<Camera> get_native_camera(JNIEnv *env, jobject thiz, JNICameraContext** pContext)
{
    sp<Camera> camera;
    Mutex::Autolock _l(sLock);
    JNICameraContext* context = reinterpret_cast<JNICameraContext*>(env->GetLongField(thiz, fields.context));
    if (context != NULL) {
        camera = context->getCamera();
    }
    ALOGV("get_native_camera: context=%p, camera=%p", context, camera.get());
    if (camera == 0) {
        jniThrowRuntimeException(env,
                "Camera is being used after Camera.release() was called");
    }

    if (pContext != NULL) *pContext = context;
    return camera;
}

JNICameraContext::JNICameraContext(JNIEnv* env, jobject weak_this, jclass clazz, const sp<Camera>& camera)
{
    mCameraJObjectWeak = env->NewGlobalRef(weak_this);
    mCameraJClass = (jclass)env->NewGlobalRef(clazz);
    mCamera = camera;

    jclass faceClazz = env->FindClass("android/hardware/Camera$Face");
    mFaceClass = (jclass) env->NewGlobalRef(faceClazz);

    jclass rectClazz = env->FindClass("android/graphics/Rect");
    mRectClass = (jclass) env->NewGlobalRef(rectClazz);

    jclass pointClazz = env->FindClass("android/graphics/Point");
    mPointClass = (jclass) env->NewGlobalRef(pointClazz);

    mManualBufferMode = false;
    mManualCameraCallbackSet = false;
}

void JNICameraContext::release()
{
    ALOGV("release");
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
    if (mFaceClass != NULL) {
        env->DeleteGlobalRef(mFaceClass);
        mFaceClass = NULL;
    }
    if (mRectClass != NULL) {
        env->DeleteGlobalRef(mRectClass);
        mRectClass = NULL;
    }
    if (mPointClass != NULL) {
        env->DeleteGlobalRef(mPointClass);
        mPointClass = NULL;
    }
    clearCallbackBuffers_l(env);
    mCamera.clear();
}

void JNICameraContext::notify(int32_t msgType, int32_t ext1, int32_t ext2)
{
    ALOGV("notify");

    // VM pointer will be NULL if object is released
    Mutex::Autolock _l(mLock);
    if (mCameraJObjectWeak == NULL) {
        ALOGW("callback on dead camera object");
        return;
    }
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    /*
     * If the notification or msgType is CAMERA_MSG_RAW_IMAGE_NOTIFY, change it
     * to CAMERA_MSG_RAW_IMAGE since CAMERA_MSG_RAW_IMAGE_NOTIFY is not exposed
     * to the Java app.
     */
    if (msgType == CAMERA_MSG_RAW_IMAGE_NOTIFY) {
        msgType = CAMERA_MSG_RAW_IMAGE;
    }

    env->CallStaticVoidMethod(mCameraJClass, fields.post_event,
            mCameraJObjectWeak, msgType, ext1, ext2, NULL);
}

jbyteArray JNICameraContext::getCallbackBuffer(
        JNIEnv* env, Vector<jbyteArray>* buffers, size_t bufferSize)
{
    jbyteArray obj = NULL;

    // Vector access should be protected by lock in postData()
    if (!buffers->isEmpty()) {
        ALOGV("Using callback buffer from queue of length %d", buffers->size());
        jbyteArray globalBuffer = buffers->itemAt(0);
        buffers->removeAt(0);

        obj = (jbyteArray)env->NewLocalRef(globalBuffer);
        env->DeleteGlobalRef(globalBuffer);

        if (obj != NULL) {
            jsize bufferLength = env->GetArrayLength(obj);
            if ((int)bufferLength < (int)bufferSize) {
                ALOGE("Callback buffer was too small! Expected %d bytes, but got %d bytes!",
                    bufferSize, bufferLength);
                env->DeleteLocalRef(obj);
                return NULL;
            }
        }
    }

    return obj;
}

void JNICameraContext::copyAndPost(JNIEnv* env, const sp<IMemory>& dataPtr, int msgType)
{
    jbyteArray obj = NULL;

    // allocate Java byte array and copy data
    if (dataPtr != NULL) {
        ssize_t offset;
        size_t size;
        sp<IMemoryHeap> heap = dataPtr->getMemory(&offset, &size);
        ALOGV("copyAndPost: off=%zd, size=%zu", offset, size);
        uint8_t *heapBase = (uint8_t*)heap->base();

        if (heapBase != NULL) {
            const jbyte* data = reinterpret_cast<const jbyte*>(heapBase + offset);

            if (msgType == CAMERA_MSG_RAW_IMAGE) {
                obj = getCallbackBuffer(env, &mRawImageCallbackBuffers, size);
            } else if (msgType == CAMERA_MSG_PREVIEW_FRAME && mManualBufferMode) {
                obj = getCallbackBuffer(env, &mCallbackBuffers, size);

                if (mCallbackBuffers.isEmpty()) {
                    ALOGV("Out of buffers, clearing callback!");
                    mCamera->setPreviewCallbackFlags(CAMERA_FRAME_CALLBACK_FLAG_NOOP);
                    mManualCameraCallbackSet = false;

                    if (obj == NULL) {
                        return;
                    }
                }
            } else {
                ALOGV("Allocating callback buffer");
                obj = env->NewByteArray(size);
            }

            if (obj == NULL) {
                ALOGE("Couldn't allocate byte array for JPEG data");
                env->ExceptionClear();
            } else {
                env->SetByteArrayRegion(obj, 0, size, data);
            }
        } else {
            ALOGE("image heap is NULL");
        }
    }

    // post image data to Java
    env->CallStaticVoidMethod(mCameraJClass, fields.post_event,
            mCameraJObjectWeak, msgType, 0, 0, obj);
    if (obj) {
        env->DeleteLocalRef(obj);
    }
}

void JNICameraContext::postData(int32_t msgType, const sp<IMemory>& dataPtr,
                                camera_frame_metadata_t *metadata)
{
    // VM pointer will be NULL if object is released
    Mutex::Autolock _l(mLock);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (mCameraJObjectWeak == NULL) {
        ALOGW("callback on dead camera object");
        return;
    }

    int32_t dataMsgType = msgType & ~CAMERA_MSG_PREVIEW_METADATA;

    // return data based on callback type
    switch (dataMsgType) {
        case CAMERA_MSG_VIDEO_FRAME:
            // should never happen
            break;

        // For backward-compatibility purpose, if there is no callback
        // buffer for raw image, the callback returns null.
        case CAMERA_MSG_RAW_IMAGE:
            ALOGV("rawCallback");
            if (mRawImageCallbackBuffers.isEmpty()) {
                env->CallStaticVoidMethod(mCameraJClass, fields.post_event,
                        mCameraJObjectWeak, dataMsgType, 0, 0, NULL);
            } else {
                copyAndPost(env, dataPtr, dataMsgType);
            }
            break;

        // There is no data.
        case 0:
            break;

        default:
            ALOGV("dataCallback(%d, %p)", dataMsgType, dataPtr.get());
            copyAndPost(env, dataPtr, dataMsgType);
            break;
    }

    // post frame metadata to Java
    if (metadata && (msgType & CAMERA_MSG_PREVIEW_METADATA)) {
        postMetadata(env, CAMERA_MSG_PREVIEW_METADATA, metadata);
    }
}

void JNICameraContext::postDataTimestamp(nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr)
{
    // TODO: plumb up to Java. For now, just drop the timestamp
    postData(msgType, dataPtr, NULL);
}

void JNICameraContext::postMetadata(JNIEnv *env, int32_t msgType, camera_frame_metadata_t *metadata)
{
    jobjectArray obj = NULL;
    obj = (jobjectArray) env->NewObjectArray(metadata->number_of_faces,
                                             mFaceClass, NULL);
    if (obj == NULL) {
        ALOGE("Couldn't allocate face metadata array");
        return;
    }

    for (int i = 0; i < metadata->number_of_faces; i++) {
        jobject face = env->NewObject(mFaceClass, fields.face_constructor);
        env->SetObjectArrayElement(obj, i, face);

        jobject rect = env->NewObject(mRectClass, fields.rect_constructor);
        env->SetIntField(rect, fields.rect_left, metadata->faces[i].rect[0]);
        env->SetIntField(rect, fields.rect_top, metadata->faces[i].rect[1]);
        env->SetIntField(rect, fields.rect_right, metadata->faces[i].rect[2]);
        env->SetIntField(rect, fields.rect_bottom, metadata->faces[i].rect[3]);

        env->SetObjectField(face, fields.face_rect, rect);
        env->SetIntField(face, fields.face_score, metadata->faces[i].score);

        bool optionalFields = metadata->faces[i].id != 0
            && metadata->faces[i].left_eye[0] != -2000 && metadata->faces[i].left_eye[1] != -2000
            && metadata->faces[i].right_eye[0] != -2000 && metadata->faces[i].right_eye[1] != -2000
            && metadata->faces[i].mouth[0] != -2000 && metadata->faces[i].mouth[1] != -2000;
        if (optionalFields) {
            int32_t id = metadata->faces[i].id;
            env->SetIntField(face, fields.face_id, id);

            jobject leftEye = env->NewObject(mPointClass, fields.point_constructor);
            env->SetIntField(leftEye, fields.point_x, metadata->faces[i].left_eye[0]);
            env->SetIntField(leftEye, fields.point_y, metadata->faces[i].left_eye[1]);
            env->SetObjectField(face, fields.face_left_eye, leftEye);
            env->DeleteLocalRef(leftEye);

            jobject rightEye = env->NewObject(mPointClass, fields.point_constructor);
            env->SetIntField(rightEye, fields.point_x, metadata->faces[i].right_eye[0]);
            env->SetIntField(rightEye, fields.point_y, metadata->faces[i].right_eye[1]);
            env->SetObjectField(face, fields.face_right_eye, rightEye);
            env->DeleteLocalRef(rightEye);

            jobject mouth = env->NewObject(mPointClass, fields.point_constructor);
            env->SetIntField(mouth, fields.point_x, metadata->faces[i].mouth[0]);
            env->SetIntField(mouth, fields.point_y, metadata->faces[i].mouth[1]);
            env->SetObjectField(face, fields.face_mouth, mouth);
            env->DeleteLocalRef(mouth);
        }

        env->DeleteLocalRef(face);
        env->DeleteLocalRef(rect);
    }
    env->CallStaticVoidMethod(mCameraJClass, fields.post_event,
            mCameraJObjectWeak, msgType, 0, 0, obj);
    env->DeleteLocalRef(obj);
}

void JNICameraContext::setCallbackMode(JNIEnv *env, bool installed, bool manualMode)
{
    Mutex::Autolock _l(mLock);
    mManualBufferMode = manualMode;
    mManualCameraCallbackSet = false;

    // In order to limit the over usage of binder threads, all non-manual buffer
    // callbacks use CAMERA_FRAME_CALLBACK_FLAG_BARCODE_SCANNER mode now.
    //
    // Continuous callbacks will have the callback re-registered from handleMessage.
    // Manual buffer mode will operate as fast as possible, relying on the finite supply
    // of buffers for throttling.

    if (!installed) {
        mCamera->setPreviewCallbackFlags(CAMERA_FRAME_CALLBACK_FLAG_NOOP);
        clearCallbackBuffers_l(env, &mCallbackBuffers);
    } else if (mManualBufferMode) {
        if (!mCallbackBuffers.isEmpty()) {
            mCamera->setPreviewCallbackFlags(CAMERA_FRAME_CALLBACK_FLAG_CAMERA);
            mManualCameraCallbackSet = true;
        }
    } else {
        mCamera->setPreviewCallbackFlags(CAMERA_FRAME_CALLBACK_FLAG_BARCODE_SCANNER);
        clearCallbackBuffers_l(env, &mCallbackBuffers);
    }
}

void JNICameraContext::addCallbackBuffer(
        JNIEnv *env, jbyteArray cbb, int msgType)
{
    ALOGV("addCallbackBuffer: 0x%x", msgType);
    if (cbb != NULL) {
        Mutex::Autolock _l(mLock);
        switch (msgType) {
            case CAMERA_MSG_PREVIEW_FRAME: {
                jbyteArray callbackBuffer = (jbyteArray)env->NewGlobalRef(cbb);
                mCallbackBuffers.push(callbackBuffer);

                ALOGV("Adding callback buffer to queue, %d total",
                        mCallbackBuffers.size());

                // We want to make sure the camera knows we're ready for the
                // next frame. This may have come unset had we not had a
                // callbackbuffer ready for it last time.
                if (mManualBufferMode && !mManualCameraCallbackSet) {
                    mCamera->setPreviewCallbackFlags(CAMERA_FRAME_CALLBACK_FLAG_CAMERA);
                    mManualCameraCallbackSet = true;
                }
                break;
            }
            case CAMERA_MSG_RAW_IMAGE: {
                jbyteArray callbackBuffer = (jbyteArray)env->NewGlobalRef(cbb);
                mRawImageCallbackBuffers.push(callbackBuffer);
                break;
            }
            default: {
                jniThrowException(env,
                        "java/lang/IllegalArgumentException",
                        "Unsupported message type");
                return;
            }
        }
    } else {
       ALOGE("Null byte array!");
    }
}

void JNICameraContext::clearCallbackBuffers_l(JNIEnv *env)
{
    clearCallbackBuffers_l(env, &mCallbackBuffers);
    clearCallbackBuffers_l(env, &mRawImageCallbackBuffers);
}

void JNICameraContext::clearCallbackBuffers_l(JNIEnv *env, Vector<jbyteArray> *buffers) {
    ALOGV("Clearing callback buffers, %d remained", buffers->size());
    while (!buffers->isEmpty()) {
        env->DeleteGlobalRef(buffers->top());
        buffers->pop();
    }
}

static jint android_hardware_Camera_getNumberOfCameras(JNIEnv *env, jobject thiz)
{
    return Camera::getNumberOfCameras();
}

static void android_hardware_Camera_getCameraInfo(JNIEnv *env, jobject thiz,
    jint cameraId, jobject info_obj)
{
    CameraInfo cameraInfo;
    status_t rc = Camera::getCameraInfo(cameraId, &cameraInfo);
    if (rc != NO_ERROR) {
        jniThrowRuntimeException(env, "Fail to get camera info");
        return;
    }
    env->SetIntField(info_obj, fields.facing, cameraInfo.facing);
    env->SetIntField(info_obj, fields.orientation, cameraInfo.orientation);

    char value[PROPERTY_VALUE_MAX];
    property_get("ro.camera.sound.forced", value, "0");
    jboolean canDisableShutterSound = (strncmp(value, "0", 2) == 0);
    env->SetBooleanField(info_obj, fields.canDisableShutterSound,
            canDisableShutterSound);
}

// connect to camera service
static jint android_hardware_Camera_native_setup(JNIEnv *env, jobject thiz,
    jobject weak_this, jint cameraId, jint halVersion, jstring clientPackageName)
{
    // Convert jstring to String16
    const char16_t *rawClientName = env->GetStringChars(clientPackageName, NULL);
    jsize rawClientNameLen = env->GetStringLength(clientPackageName);
    String16 clientName(rawClientName, rawClientNameLen);
    env->ReleaseStringChars(clientPackageName, rawClientName);

    sp<Camera> camera;
    if (halVersion == CAMERA_HAL_API_VERSION_NORMAL_CONNECT) {
        // Default path: hal version is don't care, do normal camera connect.
        camera = Camera::connect(cameraId, clientName,
                Camera::USE_CALLING_UID);
    } else {
        jint status = Camera::connectLegacy(cameraId, halVersion, clientName,
                Camera::USE_CALLING_UID, camera);
        if (status != NO_ERROR) {
            return status;
        }
    }

    if (camera == NULL) {
        return -EACCES;
    }

    // make sure camera hardware is alive
    if (camera->getStatus() != NO_ERROR) {
        return NO_INIT;
    }

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        // This should never happen
        jniThrowRuntimeException(env, "Can't find android/hardware/Camera");
        return INVALID_OPERATION;
    }

    // We use a weak reference so the Camera object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    sp<JNICameraContext> context = new JNICameraContext(env, weak_this, clazz, camera);
    context->incStrong((void*)android_hardware_Camera_native_setup);
    camera->setListener(context);

    // save context in opaque field
    env->SetLongField(thiz, fields.context, (jlong)context.get());
    return NO_ERROR;
}

// disconnect from camera service
// It's okay to call this when the native camera context is already null.
// This handles the case where the user has called release() and the
// finalizer is invoked later.
static void android_hardware_Camera_release(JNIEnv *env, jobject thiz)
{
    ALOGV("release camera");
    JNICameraContext* context = NULL;
    sp<Camera> camera;
    {
        Mutex::Autolock _l(sLock);
        context = reinterpret_cast<JNICameraContext*>(env->GetLongField(thiz, fields.context));

        // Make sure we do not attempt to callback on a deleted Java object.
        env->SetLongField(thiz, fields.context, 0);
    }

    // clean up if release has not been called before
    if (context != NULL) {
        camera = context->getCamera();
        context->release();
        ALOGV("native_release: context=%p camera=%p", context, camera.get());

        // clear callbacks
        if (camera != NULL) {
            camera->setPreviewCallbackFlags(CAMERA_FRAME_CALLBACK_FLAG_NOOP);
            camera->disconnect();
        }

        // remove context to prevent further Java access
        context->decStrong((void*)android_hardware_Camera_native_setup);
    }
}

static void android_hardware_Camera_setPreviewSurface(JNIEnv *env, jobject thiz, jobject jSurface)
{
    ALOGV("setPreviewSurface");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    sp<IGraphicBufferProducer> gbp;
    sp<Surface> surface;
    if (jSurface) {
        surface = android_view_Surface_getSurface(env, jSurface);
        if (surface != NULL) {
            gbp = surface->getIGraphicBufferProducer();
        }
    }

    if (camera->setPreviewTarget(gbp) != NO_ERROR) {
        jniThrowException(env, "java/io/IOException", "setPreviewTexture failed");
    }
}

static void android_hardware_Camera_setPreviewTexture(JNIEnv *env,
        jobject thiz, jobject jSurfaceTexture)
{
    ALOGV("setPreviewTexture");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    sp<IGraphicBufferProducer> producer = NULL;
    if (jSurfaceTexture != NULL) {
        producer = SurfaceTexture_getProducer(env, jSurfaceTexture);
        if (producer == NULL) {
            jniThrowException(env, "java/lang/IllegalArgumentException",
                    "SurfaceTexture already released in setPreviewTexture");
            return;
        }

    }

    if (camera->setPreviewTarget(producer) != NO_ERROR) {
        jniThrowException(env, "java/io/IOException",
                "setPreviewTexture failed");
    }
}

static void android_hardware_Camera_setPreviewCallbackSurface(JNIEnv *env,
        jobject thiz, jobject jSurface)
{
    ALOGV("setPreviewCallbackSurface");
    JNICameraContext* context;
    sp<Camera> camera = get_native_camera(env, thiz, &context);
    if (camera == 0) return;

    sp<IGraphicBufferProducer> gbp;
    sp<Surface> surface;
    if (jSurface) {
        surface = android_view_Surface_getSurface(env, jSurface);
        if (surface != NULL) {
            gbp = surface->getIGraphicBufferProducer();
        }
    }
    // Clear out normal preview callbacks
    context->setCallbackMode(env, false, false);
    // Then set up callback surface
    if (camera->setPreviewCallbackTarget(gbp) != NO_ERROR) {
        jniThrowException(env, "java/io/IOException", "setPreviewCallbackTarget failed");
    }
}

static void android_hardware_Camera_startPreview(JNIEnv *env, jobject thiz)
{
    ALOGV("startPreview");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    if (camera->startPreview() != NO_ERROR) {
        jniThrowRuntimeException(env, "startPreview failed");
        return;
    }
}

static void android_hardware_Camera_stopPreview(JNIEnv *env, jobject thiz)
{
    ALOGV("stopPreview");
    sp<Camera> c = get_native_camera(env, thiz, NULL);
    if (c == 0) return;

    c->stopPreview();
}

static jboolean android_hardware_Camera_previewEnabled(JNIEnv *env, jobject thiz)
{
    ALOGV("previewEnabled");
    sp<Camera> c = get_native_camera(env, thiz, NULL);
    if (c == 0) return JNI_FALSE;

    return c->previewEnabled() ? JNI_TRUE : JNI_FALSE;
}

static void android_hardware_Camera_setHasPreviewCallback(JNIEnv *env, jobject thiz, jboolean installed, jboolean manualBuffer)
{
    ALOGV("setHasPreviewCallback: installed:%d, manualBuffer:%d", (int)installed, (int)manualBuffer);
    // Important: Only install preview_callback if the Java code has called
    // setPreviewCallback() with a non-null value, otherwise we'd pay to memcpy
    // each preview frame for nothing.
    JNICameraContext* context;
    sp<Camera> camera = get_native_camera(env, thiz, &context);
    if (camera == 0) return;

    // setCallbackMode will take care of setting the context flags and calling
    // camera->setPreviewCallbackFlags within a mutex for us.
    context->setCallbackMode(env, installed, manualBuffer);
}

static void android_hardware_Camera_addCallbackBuffer(JNIEnv *env, jobject thiz, jbyteArray bytes, jint msgType) {
    ALOGV("addCallbackBuffer: 0x%x", msgType);

    JNICameraContext* context = reinterpret_cast<JNICameraContext*>(env->GetLongField(thiz, fields.context));

    if (context != NULL) {
        context->addCallbackBuffer(env, bytes, msgType);
    }
}

static void android_hardware_Camera_autoFocus(JNIEnv *env, jobject thiz)
{
    ALOGV("autoFocus");
    JNICameraContext* context;
    sp<Camera> c = get_native_camera(env, thiz, &context);
    if (c == 0) return;

    if (c->autoFocus() != NO_ERROR) {
        jniThrowRuntimeException(env, "autoFocus failed");
    }
}

static void android_hardware_Camera_cancelAutoFocus(JNIEnv *env, jobject thiz)
{
    ALOGV("cancelAutoFocus");
    JNICameraContext* context;
    sp<Camera> c = get_native_camera(env, thiz, &context);
    if (c == 0) return;

    if (c->cancelAutoFocus() != NO_ERROR) {
        jniThrowRuntimeException(env, "cancelAutoFocus failed");
    }
}

static void android_hardware_Camera_takePicture(JNIEnv *env, jobject thiz, jint msgType)
{
    ALOGV("takePicture");
    JNICameraContext* context;
    sp<Camera> camera = get_native_camera(env, thiz, &context);
    if (camera == 0) return;

    /*
     * When CAMERA_MSG_RAW_IMAGE is requested, if the raw image callback
     * buffer is available, CAMERA_MSG_RAW_IMAGE is enabled to get the
     * notification _and_ the data; otherwise, CAMERA_MSG_RAW_IMAGE_NOTIFY
     * is enabled to receive the callback notification but no data.
     *
     * Note that CAMERA_MSG_RAW_IMAGE_NOTIFY is not exposed to the
     * Java application.
     */
    if (msgType & CAMERA_MSG_RAW_IMAGE) {
        ALOGV("Enable raw image callback buffer");
        if (!context->isRawImageCallbackBufferAvailable()) {
            ALOGV("Enable raw image notification, since no callback buffer exists");
            msgType &= ~CAMERA_MSG_RAW_IMAGE;
            msgType |= CAMERA_MSG_RAW_IMAGE_NOTIFY;
        }
    }

    if (camera->takePicture(msgType) != NO_ERROR) {
        jniThrowRuntimeException(env, "takePicture failed");
        return;
    }
}

static void android_hardware_Camera_setParameters(JNIEnv *env, jobject thiz, jstring params)
{
    ALOGV("setParameters");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    const jchar* str = env->GetStringCritical(params, 0);
    String8 params8;
    if (params) {
        params8 = String8(str, env->GetStringLength(params));
        env->ReleaseStringCritical(params, str);
    }
    if (camera->setParameters(params8) != NO_ERROR) {
        jniThrowRuntimeException(env, "setParameters failed");
        return;
    }
}

static jstring android_hardware_Camera_getParameters(JNIEnv *env, jobject thiz)
{
    ALOGV("getParameters");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return 0;

    String8 params8 = camera->getParameters();
    if (params8.isEmpty()) {
        jniThrowRuntimeException(env, "getParameters failed (empty parameters)");
        return 0;
    }
    return env->NewStringUTF(params8.string());
}

static void android_hardware_Camera_reconnect(JNIEnv *env, jobject thiz)
{
    ALOGV("reconnect");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    if (camera->reconnect() != NO_ERROR) {
        jniThrowException(env, "java/io/IOException", "reconnect failed");
        return;
    }
}

static void android_hardware_Camera_lock(JNIEnv *env, jobject thiz)
{
    ALOGV("lock");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    if (camera->lock() != NO_ERROR) {
        jniThrowRuntimeException(env, "lock failed");
    }
}

static void android_hardware_Camera_unlock(JNIEnv *env, jobject thiz)
{
    ALOGV("unlock");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    if (camera->unlock() != NO_ERROR) {
        jniThrowRuntimeException(env, "unlock failed");
    }
}

static void android_hardware_Camera_startSmoothZoom(JNIEnv *env, jobject thiz, jint value)
{
    ALOGV("startSmoothZoom");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    status_t rc = camera->sendCommand(CAMERA_CMD_START_SMOOTH_ZOOM, value, 0);
    if (rc == BAD_VALUE) {
        char msg[64];
        sprintf(msg, "invalid zoom value=%d", value);
        jniThrowException(env, "java/lang/IllegalArgumentException", msg);
    } else if (rc != NO_ERROR) {
        jniThrowRuntimeException(env, "start smooth zoom failed");
    }
}

static void android_hardware_Camera_stopSmoothZoom(JNIEnv *env, jobject thiz)
{
    ALOGV("stopSmoothZoom");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    if (camera->sendCommand(CAMERA_CMD_STOP_SMOOTH_ZOOM, 0, 0) != NO_ERROR) {
        jniThrowRuntimeException(env, "stop smooth zoom failed");
    }
}

static void android_hardware_Camera_setDisplayOrientation(JNIEnv *env, jobject thiz,
        jint value)
{
    ALOGV("setDisplayOrientation");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    if (camera->sendCommand(CAMERA_CMD_SET_DISPLAY_ORIENTATION, value, 0) != NO_ERROR) {
        jniThrowRuntimeException(env, "set display orientation failed");
    }
}

static jboolean android_hardware_Camera_enableShutterSound(JNIEnv *env, jobject thiz,
        jboolean enabled)
{
    ALOGV("enableShutterSound");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return JNI_FALSE;

    int32_t value = (enabled == JNI_TRUE) ? 1 : 0;
    status_t rc = camera->sendCommand(CAMERA_CMD_ENABLE_SHUTTER_SOUND, value, 0);
    if (rc == NO_ERROR) {
        return JNI_TRUE;
    } else if (rc == PERMISSION_DENIED) {
        return JNI_FALSE;
    } else {
        jniThrowRuntimeException(env, "enable shutter sound failed");
        return JNI_FALSE;
    }
}

static void android_hardware_Camera_startFaceDetection(JNIEnv *env, jobject thiz,
        jint type)
{
    ALOGV("startFaceDetection");
    JNICameraContext* context;
    sp<Camera> camera = get_native_camera(env, thiz, &context);
    if (camera == 0) return;

    status_t rc = camera->sendCommand(CAMERA_CMD_START_FACE_DETECTION, type, 0);
    if (rc == BAD_VALUE) {
        char msg[64];
        snprintf(msg, sizeof(msg), "invalid face detection type=%d", type);
        jniThrowException(env, "java/lang/IllegalArgumentException", msg);
    } else if (rc != NO_ERROR) {
        jniThrowRuntimeException(env, "start face detection failed");
    }
}

static void android_hardware_Camera_stopFaceDetection(JNIEnv *env, jobject thiz)
{
    ALOGV("stopFaceDetection");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    if (camera->sendCommand(CAMERA_CMD_STOP_FACE_DETECTION, 0, 0) != NO_ERROR) {
        jniThrowRuntimeException(env, "stop face detection failed");
    }
}

static void android_hardware_Camera_enableFocusMoveCallback(JNIEnv *env, jobject thiz, jint enable)
{
    ALOGV("enableFocusMoveCallback");
    sp<Camera> camera = get_native_camera(env, thiz, NULL);
    if (camera == 0) return;

    if (camera->sendCommand(CAMERA_CMD_ENABLE_FOCUS_MOVE_MSG, enable, 0) != NO_ERROR) {
        jniThrowRuntimeException(env, "enable focus move callback failed");
    }
}

//-------------------------------------------------

static JNINativeMethod camMethods[] = {
  { "getNumberOfCameras",
    "()I",
    (void *)android_hardware_Camera_getNumberOfCameras },
  { "_getCameraInfo",
    "(ILandroid/hardware/Camera$CameraInfo;)V",
    (void*)android_hardware_Camera_getCameraInfo },
  { "native_setup",
    "(Ljava/lang/Object;IILjava/lang/String;)I",
    (void*)android_hardware_Camera_native_setup },
  { "native_release",
    "()V",
    (void*)android_hardware_Camera_release },
  { "setPreviewSurface",
    "(Landroid/view/Surface;)V",
    (void *)android_hardware_Camera_setPreviewSurface },
  { "setPreviewTexture",
    "(Landroid/graphics/SurfaceTexture;)V",
    (void *)android_hardware_Camera_setPreviewTexture },
  { "setPreviewCallbackSurface",
    "(Landroid/view/Surface;)V",
    (void *)android_hardware_Camera_setPreviewCallbackSurface },
  { "startPreview",
    "()V",
    (void *)android_hardware_Camera_startPreview },
  { "_stopPreview",
    "()V",
    (void *)android_hardware_Camera_stopPreview },
  { "previewEnabled",
    "()Z",
    (void *)android_hardware_Camera_previewEnabled },
  { "setHasPreviewCallback",
    "(ZZ)V",
    (void *)android_hardware_Camera_setHasPreviewCallback },
  { "_addCallbackBuffer",
    "([BI)V",
    (void *)android_hardware_Camera_addCallbackBuffer },
  { "native_autoFocus",
    "()V",
    (void *)android_hardware_Camera_autoFocus },
  { "native_cancelAutoFocus",
    "()V",
    (void *)android_hardware_Camera_cancelAutoFocus },
  { "native_takePicture",
    "(I)V",
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
    "()V",
    (void*)android_hardware_Camera_lock },
  { "unlock",
    "()V",
    (void*)android_hardware_Camera_unlock },
  { "startSmoothZoom",
    "(I)V",
    (void *)android_hardware_Camera_startSmoothZoom },
  { "stopSmoothZoom",
    "()V",
    (void *)android_hardware_Camera_stopSmoothZoom },
  { "setDisplayOrientation",
    "(I)V",
    (void *)android_hardware_Camera_setDisplayOrientation },
  { "_enableShutterSound",
    "(Z)Z",
    (void *)android_hardware_Camera_enableShutterSound },
  { "_startFaceDetection",
    "(I)V",
    (void *)android_hardware_Camera_startFaceDetection },
  { "_stopFaceDetection",
    "()V",
    (void *)android_hardware_Camera_stopFaceDetection},
  { "enableFocusMoveCallback",
    "(I)V",
    (void *)android_hardware_Camera_enableFocusMoveCallback},
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
            ALOGE("Can't find %s", f->class_name);
            return -1;
        }

        jfieldID field = env->GetFieldID(clazz, f->field_name, f->field_type);
        if (field == NULL) {
            ALOGE("Can't find %s.%s", f->class_name, f->field_name);
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
        { "android/hardware/Camera", "mNativeContext",   "J", &fields.context },
        { "android/hardware/Camera$CameraInfo", "facing",   "I", &fields.facing },
        { "android/hardware/Camera$CameraInfo", "orientation",   "I", &fields.orientation },
        { "android/hardware/Camera$CameraInfo", "canDisableShutterSound",   "Z",
          &fields.canDisableShutterSound },
        { "android/hardware/Camera$Face", "rect", "Landroid/graphics/Rect;", &fields.face_rect },
        { "android/hardware/Camera$Face", "leftEye", "Landroid/graphics/Point;", &fields.face_left_eye},
        { "android/hardware/Camera$Face", "rightEye", "Landroid/graphics/Point;", &fields.face_right_eye},
        { "android/hardware/Camera$Face", "mouth", "Landroid/graphics/Point;", &fields.face_mouth},
        { "android/hardware/Camera$Face", "score", "I", &fields.face_score },
        { "android/hardware/Camera$Face", "id", "I", &fields.face_id},
        { "android/graphics/Rect", "left", "I", &fields.rect_left },
        { "android/graphics/Rect", "top", "I", &fields.rect_top },
        { "android/graphics/Rect", "right", "I", &fields.rect_right },
        { "android/graphics/Rect", "bottom", "I", &fields.rect_bottom },
        { "android/graphics/Point", "x", "I", &fields.point_x},
        { "android/graphics/Point", "y", "I", &fields.point_y},
    };

    if (find_fields(env, fields_to_find, NELEM(fields_to_find)) < 0)
        return -1;

    jclass clazz = env->FindClass("android/hardware/Camera");
    fields.post_event = env->GetStaticMethodID(clazz, "postEventFromNative",
                                               "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    if (fields.post_event == NULL) {
        ALOGE("Can't find android/hardware/Camera.postEventFromNative");
        return -1;
    }

    clazz = env->FindClass("android/graphics/Rect");
    fields.rect_constructor = env->GetMethodID(clazz, "<init>", "()V");
    if (fields.rect_constructor == NULL) {
        ALOGE("Can't find android/graphics/Rect.Rect()");
        return -1;
    }

    clazz = env->FindClass("android/hardware/Camera$Face");
    fields.face_constructor = env->GetMethodID(clazz, "<init>", "()V");
    if (fields.face_constructor == NULL) {
        ALOGE("Can't find android/hardware/Camera$Face.Face()");
        return -1;
    }

    clazz = env->FindClass("android/graphics/Point");
    fields.point_constructor = env->GetMethodID(clazz, "<init>", "()V");
    if (fields.point_constructor == NULL) {
        ALOGE("Can't find android/graphics/Point()");
        return -1;
    }

    // Register native functions
    return AndroidRuntime::registerNativeMethods(env, "android/hardware/Camera",
                                              camMethods, NELEM(camMethods));
}

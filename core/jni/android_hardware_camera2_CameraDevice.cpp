/*
**
** Copyright 2024, The Android Open Source Project
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

// #define LOG_NDEBUG 0

#define ATRACE_TAG ATRACE_TAG_CAMERA

#include <memory>
#define LOG_TAG "CameraDevice-JNI"
#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/Trace.h>
#include <vector>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "android_os_Parcel.h"
#include "core_jni_helpers.h"
#include <android/binder_parcel_jni.h>
#include <android/hardware/camera2/ICameraDeviceUser.h>
#include <aidl/android/hardware/common/fmq/MQDescriptor.h>
#include <aidl/android/hardware/common/fmq/SynchronizedReadWrite.h>
#include <fmq/AidlMessageQueue.h>
#include <camera/CameraMetadata.h>

using namespace android;

using ::android::AidlMessageQueue;
using ResultMetadataQueue = AidlMessageQueue<int8_t, SynchronizedReadWrite>;

class FMQReader {
    public:
    FMQReader(MQDescriptor<int8_t, SynchronizedReadWrite> &resultMQ) {
        mCaptureResultMetadataQueue = std::make_shared<ResultMetadataQueue>(resultMQ);
    }
    std::shared_ptr<CameraMetadata> readOneResultMetadata(long metadataSize);
    private:
    std::shared_ptr<ResultMetadataQueue> mCaptureResultMetadataQueue = nullptr;
};

std::shared_ptr<CameraMetadata> FMQReader::readOneResultMetadata(long metadataSize) {
    ATRACE_CALL();
    if (metadataSize == 0) {
        return nullptr;
    }
    auto metadataVec = std::make_unique<int8_t []>(metadataSize);
    bool read = mCaptureResultMetadataQueue->read(metadataVec.get(), metadataSize);
    if (!read) {
        ALOGE("%s capture metadata could't be read from fmq", __FUNCTION__);
        return nullptr;
    }

    // Takes ownership of metadataVec, this doesn't copy
    std::shared_ptr<CameraMetadata> retVal =
            std::make_shared<CameraMetadata>(
                    reinterpret_cast<camera_metadata_t *>(metadataVec.release()));
    return retVal;
}

extern "C" {

static jlong CameraDevice_createFMQReader(JNIEnv *env, jclass thiz,
        jobject resultParcel) {
    AParcel *resultAParcel = AParcel_fromJavaParcel(env, resultParcel);
    if (resultAParcel == nullptr) {
        ALOGE("%s: Error creating result parcel", __FUNCTION__);
        return 0;
    }
    AParcel_setDataPosition(resultAParcel, 0);

    MQDescriptor<int8_t, SynchronizedReadWrite> resultMQ;
    if (resultMQ.readFromParcel(resultAParcel) != OK) {
        ALOGE("%s: read from result parcel failed", __FUNCTION__);
        return 0;
    }
    return reinterpret_cast<jlong>(new std::shared_ptr<FMQReader>(
            new FMQReader(resultMQ)));
}

static std::shared_ptr<FMQReader>* FMQReader_getSharedPtr(jlong fmqReaderLongPtr) {
    return reinterpret_cast<std::shared_ptr<FMQReader>* >(fmqReaderLongPtr);
}

static jlong CameraDevice_readResultMetadata(JNIEnv *env, jclass thiz, jlong ptr,
        jlong metadataSize) {
    ALOGV("%s", __FUNCTION__);

    FMQReader *fmqReader = FMQReader_getSharedPtr(ptr)->get();
    auto metadataSp = fmqReader->readOneResultMetadata(metadataSize);
    auto retVal = new std::shared_ptr<CameraMetadata>(metadataSp);
    return reinterpret_cast<jlong>(retVal);
}

static void CameraDevice_close(JNIEnv *env, jclass thiz, jlong ptr) {
    ALOGV("%s", __FUNCTION__);

    auto fmqPtr = FMQReader_getSharedPtr(ptr);
    if (fmqPtr != nullptr) {
        delete fmqPtr;
    }
}

}

//-------------------------------------------------
#define CAMERA_DEVICE_CLASS_NAME "android/hardware/camera2/impl/CameraDeviceImpl"
static const JNINativeMethod gCameraDeviceMethods[] = {
// static methods
  { "nativeCreateFMQReader",
    "(Landroid/os/Parcel;)J",
    (void *)CameraDevice_createFMQReader},
  { "nativeReadResultMetadata",
    "(JJ)J",
    (void *)CameraDevice_readResultMetadata},
  { "nativeClose",
    "(J)V",
    (void*)CameraDevice_close},
// instance methods
};


// Get all the required offsets in java class and register native functions
int register_android_hardware_camera2_CameraDevice(JNIEnv *env)
{
    // Register native functions
    return RegisterMethodsOrDie(env,
            CAMERA_DEVICE_CLASS_NAME,
            gCameraDeviceMethods,
            NELEM(gCameraDeviceMethods));
}

extern "C" {

} // extern "C"
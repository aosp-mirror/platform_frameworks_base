/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// #define LOG_NDEBUG 0

#define LOG_TAG "MtpDeviceJNI"
#include "utils/Log.h"

#include <stdio.h>
#include <assert.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>

#include <memory>
#include <string>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"
#include "core_jni_helpers.h"
#include "nativehelper/ScopedLocalRef.h"
#include "private/android_filesystem_config.h"

#include "MtpTypes.h"
#include "MtpDevice.h"
#include "MtpDeviceInfo.h"
#include "MtpStorageInfo.h"
#include "MtpObjectInfo.h"
#include "MtpProperty.h"

using namespace android;

// ----------------------------------------------------------------------------

namespace {

static jfieldID field_context;

jclass clazz_deviceInfo;
jclass clazz_storageInfo;
jclass clazz_objectInfo;
jclass clazz_event;
jclass clazz_io_exception;
jclass clazz_operation_canceled_exception;

jmethodID constructor_deviceInfo;
jmethodID constructor_storageInfo;
jmethodID constructor_objectInfo;
jmethodID constructor_event;

// MtpDeviceInfo fields
static jfieldID field_deviceInfo_manufacturer;
static jfieldID field_deviceInfo_model;
static jfieldID field_deviceInfo_version;
static jfieldID field_deviceInfo_serialNumber;
static jfieldID field_deviceInfo_operationsSupported;
static jfieldID field_deviceInfo_eventsSupported;
static jfieldID field_deviceInfo_devicePropertySupported;

// MtpStorageInfo fields
static jfieldID field_storageInfo_storageId;
static jfieldID field_storageInfo_maxCapacity;
static jfieldID field_storageInfo_freeSpace;
static jfieldID field_storageInfo_description;
static jfieldID field_storageInfo_volumeIdentifier;

// MtpObjectInfo fields
static jfieldID field_objectInfo_handle;
static jfieldID field_objectInfo_storageId;
static jfieldID field_objectInfo_format;
static jfieldID field_objectInfo_protectionStatus;
static jfieldID field_objectInfo_compressedSize;
static jfieldID field_objectInfo_thumbFormat;
static jfieldID field_objectInfo_thumbCompressedSize;
static jfieldID field_objectInfo_thumbPixWidth;
static jfieldID field_objectInfo_thumbPixHeight;
static jfieldID field_objectInfo_imagePixWidth;
static jfieldID field_objectInfo_imagePixHeight;
static jfieldID field_objectInfo_imagePixDepth;
static jfieldID field_objectInfo_parent;
static jfieldID field_objectInfo_associationType;
static jfieldID field_objectInfo_associationDesc;
static jfieldID field_objectInfo_sequenceNumber;
static jfieldID field_objectInfo_name;
static jfieldID field_objectInfo_dateCreated;
static jfieldID field_objectInfo_dateModified;
static jfieldID field_objectInfo_keywords;

// MtpEvent fields
static jfieldID field_event_eventCode;
static jfieldID field_event_parameter1;
static jfieldID field_event_parameter2;
static jfieldID field_event_parameter3;

// Initializer for  the jclasses, jfieldIDs and jmethodIDs declared above. This method must be
// invoked before using these static fields for JNI accesses.
static void initializeJavaIDs(JNIEnv* env) {
    static std::once_flag sJniInitialized;

    std::call_once(sJniInitialized, [](JNIEnv* env) {
        clazz_deviceInfo =
            (jclass)env->NewGlobalRef(FindClassOrDie(env, "android/mtp/MtpDeviceInfo"));
        constructor_deviceInfo = GetMethodIDOrDie(env, clazz_deviceInfo, "<init>", "()V");
        field_deviceInfo_manufacturer =
            GetFieldIDOrDie(env, clazz_deviceInfo, "mManufacturer", "Ljava/lang/String;");
        field_deviceInfo_model =
            GetFieldIDOrDie(env, clazz_deviceInfo, "mModel", "Ljava/lang/String;");
        field_deviceInfo_version =
            GetFieldIDOrDie(env, clazz_deviceInfo, "mVersion", "Ljava/lang/String;");
        field_deviceInfo_serialNumber =
            GetFieldIDOrDie(env, clazz_deviceInfo, "mSerialNumber", "Ljava/lang/String;");
        field_deviceInfo_operationsSupported =
            GetFieldIDOrDie(env, clazz_deviceInfo, "mOperationsSupported", "[I");
        field_deviceInfo_eventsSupported =
            GetFieldIDOrDie(env, clazz_deviceInfo, "mEventsSupported", "[I");
        field_deviceInfo_devicePropertySupported =
            GetFieldIDOrDie(env, clazz_deviceInfo, "mDevicePropertySupported", "[I");

        clazz_storageInfo =
            (jclass)env->NewGlobalRef(FindClassOrDie(env, "android/mtp/MtpStorageInfo"));
        constructor_storageInfo = GetMethodIDOrDie(env, clazz_storageInfo, "<init>", "()V");
        field_storageInfo_storageId = GetFieldIDOrDie(env, clazz_storageInfo, "mStorageId", "I");
        field_storageInfo_maxCapacity =
            GetFieldIDOrDie(env, clazz_storageInfo, "mMaxCapacity", "J");
        field_storageInfo_freeSpace =
            GetFieldIDOrDie(env, clazz_storageInfo, "mFreeSpace", "J");
        field_storageInfo_description =
            GetFieldIDOrDie(env, clazz_storageInfo, "mDescription", "Ljava/lang/String;");
        field_storageInfo_volumeIdentifier =
            GetFieldIDOrDie(env, clazz_storageInfo, "mVolumeIdentifier", "Ljava/lang/String;");

        clazz_objectInfo =
            (jclass)env->NewGlobalRef(FindClassOrDie(env, "android/mtp/MtpObjectInfo"));
        constructor_objectInfo = GetMethodIDOrDie(env, clazz_objectInfo, "<init>", "()V");
        field_objectInfo_handle = GetFieldIDOrDie(env, clazz_objectInfo, "mHandle", "I");
        field_objectInfo_storageId = GetFieldIDOrDie(env, clazz_objectInfo, "mStorageId", "I");
        field_objectInfo_format = GetFieldIDOrDie(env, clazz_objectInfo, "mFormat", "I");
        field_objectInfo_protectionStatus =
            GetFieldIDOrDie(env, clazz_objectInfo, "mProtectionStatus", "I");
        field_objectInfo_compressedSize =
            GetFieldIDOrDie(env, clazz_objectInfo, "mCompressedSize", "I");
        field_objectInfo_thumbFormat = GetFieldIDOrDie(env, clazz_objectInfo, "mThumbFormat", "I");
        field_objectInfo_thumbCompressedSize =
            GetFieldIDOrDie(env, clazz_objectInfo, "mThumbCompressedSize", "I");
        field_objectInfo_thumbPixWidth =
            GetFieldIDOrDie(env, clazz_objectInfo, "mThumbPixWidth", "I");
        field_objectInfo_thumbPixHeight =
            GetFieldIDOrDie(env, clazz_objectInfo, "mThumbPixHeight", "I");
        field_objectInfo_imagePixWidth =
            GetFieldIDOrDie(env, clazz_objectInfo, "mImagePixWidth", "I");
        field_objectInfo_imagePixHeight =
            GetFieldIDOrDie(env, clazz_objectInfo, "mImagePixHeight", "I");
        field_objectInfo_imagePixDepth =
                GetFieldIDOrDie(env, clazz_objectInfo, "mImagePixDepth", "I");
        field_objectInfo_parent = GetFieldIDOrDie(env, clazz_objectInfo, "mParent", "I");
        field_objectInfo_associationType =
                GetFieldIDOrDie(env, clazz_objectInfo, "mAssociationType", "I");
        field_objectInfo_associationDesc =
                GetFieldIDOrDie(env, clazz_objectInfo, "mAssociationDesc", "I");
        field_objectInfo_sequenceNumber =
                GetFieldIDOrDie(env, clazz_objectInfo, "mSequenceNumber", "I");
        field_objectInfo_name =
                GetFieldIDOrDie(env, clazz_objectInfo, "mName", "Ljava/lang/String;");
        field_objectInfo_dateCreated = GetFieldIDOrDie(env, clazz_objectInfo, "mDateCreated", "J");
        field_objectInfo_dateModified =
                GetFieldIDOrDie(env, clazz_objectInfo, "mDateModified", "J");
        field_objectInfo_keywords =
                GetFieldIDOrDie(env, clazz_objectInfo, "mKeywords", "Ljava/lang/String;");

        clazz_event = (jclass)env->NewGlobalRef(FindClassOrDie(env, "android/mtp/MtpEvent"));
        constructor_event = GetMethodIDOrDie(env, clazz_event, "<init>", "()V");
        field_event_eventCode = GetFieldIDOrDie(env, clazz_event, "mEventCode", "I");
        field_event_parameter1 = GetFieldIDOrDie(env, clazz_event, "mParameter1", "I");
        field_event_parameter2 = GetFieldIDOrDie(env, clazz_event, "mParameter2", "I");
        field_event_parameter3 = GetFieldIDOrDie(env, clazz_event, "mParameter3", "I");

        const jclass clazz = FindClassOrDie(env, "android/mtp/MtpDevice");
        field_context = GetFieldIDOrDie(env, clazz, "mNativeContext", "J");

        clazz_io_exception = (jclass)env->NewGlobalRef(FindClassOrDie(env, "java/io/IOException"));
        clazz_operation_canceled_exception =
            (jclass)env->NewGlobalRef(FindClassOrDie(env, "android/os/OperationCanceledException"));
    }, env);
}

class JavaArrayWriter {
public:
    JavaArrayWriter(JNIEnv* env, jbyteArray array) :
        mEnv(env), mArray(array), mSize(mEnv->GetArrayLength(mArray)) {}
    bool write(void* data, uint32_t offset, uint32_t length) {
        if (static_cast<uint32_t>(mSize) < offset + length) {
            return false;
        }
        mEnv->SetByteArrayRegion(mArray, offset, length, static_cast<jbyte*>(data));
        return true;
    }
    static bool writeTo(void* data, uint32_t offset, uint32_t length, void* clientData) {
        return static_cast<JavaArrayWriter*>(clientData)->write(data, offset, length);
    }

private:
    JNIEnv* mEnv;
    jbyteArray mArray;
    jsize mSize;
};

}

MtpDevice* get_device_from_object(JNIEnv* env, jobject javaDevice)
{
    // get_device_from_object() is called by the majority of JNI methods in this file. Call
    // `initializeJavaIDs()` here to ensure JNI methodID's, fieldIDs and classes are initialized
    // before use.
    initializeJavaIDs(env);

    return (MtpDevice*)env->GetLongField(javaDevice, field_context);
}

void fill_jobject_from_object_info(JNIEnv* env, jobject object, MtpObjectInfo* objectInfo) {
    if (objectInfo->mHandle)
        env->SetIntField(object, field_objectInfo_handle, objectInfo->mHandle);
    if (objectInfo->mStorageID)
        env->SetIntField(object, field_objectInfo_storageId, objectInfo->mStorageID);
    if (objectInfo->mFormat)
        env->SetIntField(object, field_objectInfo_format, objectInfo->mFormat);
    if (objectInfo->mProtectionStatus)
        env->SetIntField(object, field_objectInfo_protectionStatus, objectInfo->mProtectionStatus);
    if (objectInfo->mCompressedSize)
        env->SetIntField(object, field_objectInfo_compressedSize, objectInfo->mCompressedSize);
    if (objectInfo->mThumbFormat)
        env->SetIntField(object, field_objectInfo_thumbFormat, objectInfo->mThumbFormat);
    if (objectInfo->mThumbCompressedSize) {
        env->SetIntField(object, field_objectInfo_thumbCompressedSize,
                objectInfo->mThumbCompressedSize);
    }
    if (objectInfo->mThumbPixWidth)
        env->SetIntField(object, field_objectInfo_thumbPixWidth, objectInfo->mThumbPixWidth);
    if (objectInfo->mThumbPixHeight)
        env->SetIntField(object, field_objectInfo_thumbPixHeight, objectInfo->mThumbPixHeight);
    if (objectInfo->mImagePixWidth)
        env->SetIntField(object, field_objectInfo_imagePixWidth, objectInfo->mImagePixWidth);
    if (objectInfo->mImagePixHeight)
        env->SetIntField(object, field_objectInfo_imagePixHeight, objectInfo->mImagePixHeight);
    if (objectInfo->mImagePixDepth)
        env->SetIntField(object, field_objectInfo_imagePixDepth, objectInfo->mImagePixDepth);
    if (objectInfo->mParent)
        env->SetIntField(object, field_objectInfo_parent, objectInfo->mParent);
    if (objectInfo->mAssociationType)
        env->SetIntField(object, field_objectInfo_associationType, objectInfo->mAssociationType);
    if (objectInfo->mAssociationDesc)
        env->SetIntField(object, field_objectInfo_associationDesc, objectInfo->mAssociationDesc);
    if (objectInfo->mSequenceNumber)
        env->SetIntField(object, field_objectInfo_sequenceNumber, objectInfo->mSequenceNumber);
    if (objectInfo->mName)
        env->SetObjectField(object, field_objectInfo_name, env->NewStringUTF(objectInfo->mName));
    if (objectInfo->mDateCreated)
        env->SetLongField(object, field_objectInfo_dateCreated, objectInfo->mDateCreated * 1000LL);
    if (objectInfo->mDateModified) {
        env->SetLongField(object, field_objectInfo_dateModified,
                objectInfo->mDateModified * 1000LL);
    }
    if (objectInfo->mKeywords) {
        env->SetObjectField(object, field_objectInfo_keywords,
            env->NewStringUTF(objectInfo->mKeywords));
    }
}

// ----------------------------------------------------------------------------

static jboolean
android_mtp_MtpDevice_open(JNIEnv *env, jobject thiz, jstring deviceName, jint fd)
{
    const char *deviceNameStr = env->GetStringUTFChars(deviceName, NULL);
    if (deviceNameStr == NULL) {
        return JNI_FALSE;
    }

    // The passed in fd is maintained by the UsbDeviceConnection
    fd = dup(fd);

    MtpDevice* device = MtpDevice::open(deviceNameStr, fd);
    env->ReleaseStringUTFChars(deviceName, deviceNameStr);

    // The jfieldID `field_context` needs to be initialized before use below.
    initializeJavaIDs(env);
    if (device)
        env->SetLongField(thiz, field_context,  (jlong)device);
    return (jboolean)(device != NULL);
}

static void
android_mtp_MtpDevice_close(JNIEnv *env, jobject thiz)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (device) {
        device->close();
        delete device;
        env->SetLongField(thiz, field_context, 0);
    }
}

static jobject
android_mtp_MtpDevice_get_device_info(JNIEnv *env, jobject thiz)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGD("android_mtp_MtpDevice_get_device_info device is null");
        return NULL;
    }
    std::unique_ptr<MtpDeviceInfo> deviceInfo(device->getDeviceInfo());
    if (!deviceInfo) {
        ALOGD("android_mtp_MtpDevice_get_device_info deviceInfo is null");
        return NULL;
    }
    jobject info = env->NewObject(clazz_deviceInfo, constructor_deviceInfo);
    if (info == NULL) {
        ALOGE("Could not create a MtpDeviceInfo object");
        return NULL;
    }

    if (deviceInfo->mManufacturer)
        env->SetObjectField(info, field_deviceInfo_manufacturer,
            env->NewStringUTF(deviceInfo->mManufacturer));
    if (deviceInfo->mModel)
        env->SetObjectField(info, field_deviceInfo_model,
            env->NewStringUTF(deviceInfo->mModel));
    if (deviceInfo->mVersion)
        env->SetObjectField(info, field_deviceInfo_version,
            env->NewStringUTF(deviceInfo->mVersion));
    if (deviceInfo->mSerial)
        env->SetObjectField(info, field_deviceInfo_serialNumber,
            env->NewStringUTF(deviceInfo->mSerial));
    assert(deviceInfo->mOperations);
    {
        const size_t size = deviceInfo->mOperations->size();
        ScopedLocalRef<jintArray> operations(env, static_cast<jintArray>(env->NewIntArray(size)));
        {
            ScopedIntArrayRW elements(env, operations.get());
            if (elements.get() == NULL) {
                ALOGE("Could not create operationsSupported element.");
                return NULL;
            }
            for (size_t i = 0; i < size; ++i) {
                elements[i] = static_cast<int>(deviceInfo->mOperations->at(i));
            }
            env->SetObjectField(info, field_deviceInfo_operationsSupported, operations.get());
        }
    }
    assert(deviceInfo->mEvents);
    {
        const size_t size = deviceInfo->mEvents->size();
        ScopedLocalRef<jintArray> events(env, static_cast<jintArray>(env->NewIntArray(size)));
        {
            ScopedIntArrayRW elements(env, events.get());
            if (elements.get() == NULL) {
                ALOGE("Could not create eventsSupported element.");
                return NULL;
            }
            for (size_t i = 0; i < size; ++i) {
                elements[i] = static_cast<int>(deviceInfo->mEvents->at(i));
            }
            env->SetObjectField(info, field_deviceInfo_eventsSupported, events.get());
        }
    }

    assert(deviceInfo->mDeviceProperties);
    {
        const size_t size = deviceInfo->mDeviceProperties->size();
        ScopedLocalRef<jintArray> events(env, static_cast<jintArray>(env->NewIntArray(size)));
        {
            ScopedIntArrayRW elements(env, events.get());
            if (elements.get() == NULL) {
                ALOGE("Could not create devicePropertySupported element.");
                return NULL;
            }
            for (size_t i = 0; i < size; ++i) {
                elements[i] = static_cast<int>(deviceInfo->mDeviceProperties->at(i));
            }
            env->SetObjectField(info, field_deviceInfo_devicePropertySupported, events.get());
        }
    }

    return info;
}

static jint
android_mtp_MtpDevice_set_device_property_init_version(JNIEnv *env, jobject thiz,
                                                       jstring property_str) {
    MtpDevice* const device = get_device_from_object(env, thiz);

    if (!device) {
        ALOGD("%s device is null\n", __func__);
        env->ThrowNew(clazz_io_exception, "Failed to obtain MtpDevice.");
        return -1;
    }

    const char *propertyStr = env->GetStringUTFChars(property_str, NULL);
    if (propertyStr == NULL) {
        return -1;
    }

    MtpProperty property(MTP_DEVICE_PROPERTY_SESSION_INITIATOR_VERSION_INFO, MTP_TYPE_STR, true);
    if (property.getDataType() != MTP_TYPE_STR) {
        env->ThrowNew(clazz_io_exception, "Unexpected property data type.");
        return -1;
    }

    property.setCurrentValue(propertyStr);
    if (!device->setDevicePropValueStr(&property)) {
        env->ThrowNew(clazz_io_exception, "Failed to obtain property value.");
        return -1;
    }

    env->ReleaseStringUTFChars(property_str, propertyStr);

    return 0;
}

static jintArray
android_mtp_MtpDevice_get_storage_ids(JNIEnv *env, jobject thiz)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (!device)
        return NULL;
    MtpStorageIDList* storageIDs = device->getStorageIDs();
    if (!storageIDs)
        return NULL;

    int length = storageIDs->size();
    jintArray array = env->NewIntArray(length);
    // FIXME is this cast safe?
    env->SetIntArrayRegion(array, 0, length, (const jint *)storageIDs->data());

    delete storageIDs;
    return array;
}

static jobject
android_mtp_MtpDevice_get_storage_info(JNIEnv *env, jobject thiz, jint storageID)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (!device)
        return NULL;
    MtpStorageInfo* storageInfo = device->getStorageInfo(storageID);
    if (!storageInfo)
        return NULL;

    jobject info = env->NewObject(clazz_storageInfo, constructor_storageInfo);
    if (info == NULL) {
        ALOGE("Could not create a MtpStorageInfo object");
        delete storageInfo;
        return NULL;
    }

    if (storageInfo->mStorageID)
        env->SetIntField(info, field_storageInfo_storageId, storageInfo->mStorageID);
    if (storageInfo->mMaxCapacity)
        env->SetLongField(info, field_storageInfo_maxCapacity, storageInfo->mMaxCapacity);
    if (storageInfo->mFreeSpaceBytes)
        env->SetLongField(info, field_storageInfo_freeSpace, storageInfo->mFreeSpaceBytes);
    if (storageInfo->mStorageDescription)
        env->SetObjectField(info, field_storageInfo_description,
            env->NewStringUTF(storageInfo->mStorageDescription));
    if (storageInfo->mVolumeIdentifier)
        env->SetObjectField(info, field_storageInfo_volumeIdentifier,
            env->NewStringUTF(storageInfo->mVolumeIdentifier));

    delete storageInfo;
    return info;
}

static jintArray
android_mtp_MtpDevice_get_object_handles(JNIEnv *env, jobject thiz,
        jint storageID, jint format, jint objectID)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (!device)
        return NULL;
    MtpObjectHandleList* handles = device->getObjectHandles(storageID, format, objectID);
    if (!handles)
        return NULL;

    int length = handles->size();
    jintArray array = env->NewIntArray(length);
    // FIXME is this cast safe?
    env->SetIntArrayRegion(array, 0, length, (const jint *)handles->data());

    delete handles;
    return array;
}

static jobject
android_mtp_MtpDevice_get_object_info(JNIEnv *env, jobject thiz, jint objectID)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (!device)
        return NULL;
    MtpObjectInfo* objectInfo = device->getObjectInfo(objectID);
    if (!objectInfo)
        return NULL;
    jobject info = env->NewObject(clazz_objectInfo, constructor_objectInfo);
    if (info == NULL) {
        ALOGE("Could not create a MtpObjectInfo object");
        delete objectInfo;
        return NULL;
    }

    fill_jobject_from_object_info(env, info, objectInfo);
    delete objectInfo;
    return info;
}

bool check_uint32_arg(JNIEnv *env, const char* name, jlong value, uint32_t* out) {
    if (value < 0 || 0xffffffff < value) {
        jniThrowException(
                env,
                "java/lang/IllegalArgumentException",
                (std::string("argument must be a 32-bit unsigned integer: ") + name).c_str());
        return false;
    }
    *out = static_cast<uint32_t>(value);
    return true;
}

static jbyteArray
android_mtp_MtpDevice_get_object(JNIEnv *env, jobject thiz, jint objectID, jlong objectSizeLong)
{
    uint32_t objectSize;
    if (!check_uint32_arg(env, "objectSize", objectSizeLong, &objectSize)) {
        return nullptr;
    }

    MtpDevice* device = get_device_from_object(env, thiz);
    if (!device) {
        return nullptr;
    }

    ScopedLocalRef<jbyteArray> array(env, env->NewByteArray(objectSize));
    if (!array.get()) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return nullptr;
    }

    JavaArrayWriter writer(env, array.get());

    if (device->readObject(objectID, JavaArrayWriter::writeTo, objectSize, &writer)) {
        return array.release();
    }
    return nullptr;
}

static jlong
android_mtp_MtpDevice_get_partial_object(JNIEnv *env,
                                         jobject thiz,
                                         jint objectID,
                                         jlong offsetLong,
                                         jlong sizeLong,
                                         jbyteArray array)
{
    if (!array) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Array must not be null.");
        return -1;
    }

    uint32_t offset;
    uint32_t size;
    if (!check_uint32_arg(env, "offset", offsetLong, &offset) ||
            !check_uint32_arg(env, "size", sizeLong, &size)) {
        return -1;
    }

    MtpDevice* const device = get_device_from_object(env, thiz);
    if (!device) {
        jniThrowException(env, "java/io/IOException", "Failed to obtain MtpDevice.");
        return -1;
    }

    JavaArrayWriter writer(env, array);
    uint32_t written_size;
    const bool success = device->readPartialObject(
            objectID, offset, size, &written_size, JavaArrayWriter::writeTo, &writer);
    if (!success) {
        jniThrowException(env, "java/io/IOException", "Failed to read data.");
        return -1;
    }
    return static_cast<jlong>(written_size);
}

static jint
android_mtp_MtpDevice_get_partial_object_64(JNIEnv *env,
                                            jobject thiz,
                                            jint objectID,
                                            jlong offset,
                                            jlong size,
                                            jbyteArray array) {
    if (!array) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Array must not be null.");
        return -1;
    }

    if (offset < 0) {
        jniThrowException(
                env,
                "java/lang/IllegalArgumentException",
                "Offset argument must not be a negative value.");
        return -1;
    }

    if (size < 0 || 0xffffffffL < size) {
        jniThrowException(
                env,
                "java/lang/IllegalArgumentException",
                "Size argument must be a 32-bit unsigned integer.");
        return -1;
    }

    MtpDevice* const device = get_device_from_object(env, thiz);
    if (!device) {
        jniThrowException(env, "java/io/IOException", "Failed to obtain MtpDevice.");
        return -1;
    }

    const uint32_t native_object_handle = static_cast<uint32_t>(objectID);
    const uint64_t native_offset = static_cast<uint64_t>(offset);
    const uint32_t native_size = static_cast<uint32_t>(size);

    JavaArrayWriter writer(env, array);
    uint32_t written_size;
    const bool success = device->readPartialObject64(
            native_object_handle,
            native_offset,
            native_size,
            &written_size,
            JavaArrayWriter::writeTo,
            &writer);
    if (!success) {
        jniThrowException(env, "java/io/IOException", "Failed to read data.");
        return -1;
    }
    return static_cast<jint>(written_size);
}

static jbyteArray
android_mtp_MtpDevice_get_thumbnail(JNIEnv *env, jobject thiz, jint objectID)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (!device)
        return NULL;

    int length;
    void* thumbnail = device->getThumbnail(objectID, length);
    if (! thumbnail)
        return NULL;
    jbyteArray array = env->NewByteArray(length);
    env->SetByteArrayRegion(array, 0, length, (const jbyte *)thumbnail);

    free(thumbnail);
    return array;
}

static jboolean
android_mtp_MtpDevice_delete_object(JNIEnv *env, jobject thiz, jint object_id)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (device && device->deleteObject(object_id)) {
        return JNI_TRUE;
    } else {
        return JNI_FALSE;
    }
}

static jint
android_mtp_MtpDevice_get_parent(JNIEnv *env, jobject thiz, jint object_id)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (device)
        return static_cast<jint>(device->getParent(object_id));
    else
        return -1;
}

static jint
android_mtp_MtpDevice_get_storage_id(JNIEnv *env, jobject thiz, jint object_id)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (device)
        return static_cast<jint>(device->getStorageID(object_id));
    else
        return -1;
}

static jboolean
android_mtp_MtpDevice_import_file(JNIEnv *env, jobject thiz, jint object_id, jstring dest_path)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (device) {
        const char *destPathStr = env->GetStringUTFChars(dest_path, NULL);
        if (destPathStr == NULL) {
            return JNI_FALSE;
        }

        jboolean result = device->readObject(object_id, destPathStr, AID_SDCARD_RW, 0664);
        env->ReleaseStringUTFChars(dest_path, destPathStr);
        return result;
    }

    return JNI_FALSE;
}

static jboolean
android_mtp_MtpDevice_import_file_to_fd(JNIEnv *env, jobject thiz, jint object_id, jint fd)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (device)
        return device->readObject(object_id, fd);
    else
        return JNI_FALSE;
}

static jboolean
android_mtp_MtpDevice_send_object(
        JNIEnv *env, jobject thiz, jint object_id, jlong sizeLong, jint fd)
{
    uint32_t size;
    if (!check_uint32_arg(env, "size", sizeLong, &size))
        return JNI_FALSE;

    MtpDevice* device = get_device_from_object(env, thiz);
    if (!device)
        return JNI_FALSE;

    return device->sendObject(object_id, size, fd);
}

static jobject
android_mtp_MtpDevice_send_object_info(JNIEnv *env, jobject thiz, jobject info)
{
    MtpDevice* device = get_device_from_object(env, thiz);
    if (!device) {
        return NULL;
    }

    // Updating existing objects is not supported.
    if (env->GetIntField(info, field_objectInfo_handle) != -1) {
        return NULL;
    }

    MtpObjectInfo* object_info = new MtpObjectInfo(-1);
    object_info->mStorageID = env->GetIntField(info, field_objectInfo_storageId);
    object_info->mFormat = env->GetIntField(info, field_objectInfo_format);
    object_info->mProtectionStatus = env->GetIntField(info, field_objectInfo_protectionStatus);
    object_info->mCompressedSize = env->GetIntField(info, field_objectInfo_compressedSize);
    object_info->mThumbFormat = env->GetIntField(info, field_objectInfo_thumbFormat);
    object_info->mThumbCompressedSize =
            env->GetIntField(info, field_objectInfo_thumbCompressedSize);
    object_info->mThumbPixWidth = env->GetIntField(info, field_objectInfo_thumbPixWidth);
    object_info->mThumbPixHeight = env->GetIntField(info, field_objectInfo_thumbPixHeight);
    object_info->mImagePixWidth = env->GetIntField(info, field_objectInfo_imagePixWidth);
    object_info->mImagePixHeight = env->GetIntField(info, field_objectInfo_imagePixHeight);
    object_info->mImagePixDepth = env->GetIntField(info, field_objectInfo_imagePixDepth);
    object_info->mParent = env->GetIntField(info, field_objectInfo_parent);
    object_info->mAssociationType = env->GetIntField(info, field_objectInfo_associationType);
    object_info->mAssociationDesc = env->GetIntField(info, field_objectInfo_associationDesc);
    object_info->mSequenceNumber = env->GetIntField(info, field_objectInfo_sequenceNumber);

    jstring name_jstring = (jstring) env->GetObjectField(info, field_objectInfo_name);
    if (name_jstring != NULL) {
        const char* name_string = env->GetStringUTFChars(name_jstring, NULL);
        object_info->mName = strdup(name_string);
        env->ReleaseStringUTFChars(name_jstring, name_string);
    }

    object_info->mDateCreated = env->GetLongField(info, field_objectInfo_dateCreated) / 1000LL;
    object_info->mDateModified = env->GetLongField(info, field_objectInfo_dateModified) / 1000LL;

    jstring keywords_jstring = (jstring) env->GetObjectField(info, field_objectInfo_keywords);
    if (keywords_jstring != NULL) {
        const char* keywords_string = env->GetStringUTFChars(keywords_jstring, NULL);
        object_info->mKeywords = strdup(keywords_string);
        env->ReleaseStringUTFChars(keywords_jstring, keywords_string);
    }

    int object_handle = device->sendObjectInfo(object_info);
    if (object_handle == -1) {
        delete object_info;
        return NULL;
    }

    object_info->mHandle = object_handle;
    jobject result = env->NewObject(clazz_objectInfo, constructor_objectInfo);
    if (result == NULL) {
        ALOGE("Could not create a MtpObjectInfo object");
        delete object_info;
        return NULL;
    }

    fill_jobject_from_object_info(env, result, object_info);
    delete object_info;
    return result;
}

static jint android_mtp_MtpDevice_submit_event_request(JNIEnv *env, jobject thiz)
{
    MtpDevice* const device = get_device_from_object(env, thiz);
    if (!device) {
        env->ThrowNew(clazz_io_exception, "");
        return -1;
    }
    return device->submitEventRequest();
}

static jobject android_mtp_MtpDevice_reap_event_request(JNIEnv *env, jobject thiz, jint seq)
{
    MtpDevice* const device = get_device_from_object(env, thiz);
    if (!device) {
        env->ThrowNew(clazz_io_exception, "");
        return NULL;
    }
    uint32_t parameters[3];
    const int eventCode = device->reapEventRequest(seq, &parameters);
    if (eventCode <= 0) {
        env->ThrowNew(clazz_operation_canceled_exception, "");
        return NULL;
    }
    jobject result = env->NewObject(clazz_event, constructor_event);
    env->SetIntField(result, field_event_eventCode, eventCode);
    env->SetIntField(result, field_event_parameter1, static_cast<jint>(parameters[0]));
    env->SetIntField(result, field_event_parameter2, static_cast<jint>(parameters[1]));
    env->SetIntField(result, field_event_parameter3, static_cast<jint>(parameters[2]));
    return result;
}

static void android_mtp_MtpDevice_discard_event_request(JNIEnv *env, jobject thiz, jint seq)
{
    MtpDevice* const device = get_device_from_object(env, thiz);
    if (!device) {
        return;
    }
    device->discardEventRequest(seq);
}

// Returns object size in 64-bit integer. If the MTP device does not support the property, it
// throws IOException.
static jlong android_mtp_MtpDevice_get_object_size_long(
        JNIEnv *env, jobject thiz, jint handle, jint format) {
    MtpDevice* const device = get_device_from_object(env, thiz);
    if (!device) {
        env->ThrowNew(clazz_io_exception, "Failed to obtain MtpDevice.");
        return 0;
    }

    std::unique_ptr<MtpProperty> property(
            device->getObjectPropDesc(MTP_PROPERTY_OBJECT_SIZE, format));
    if (!property) {
        env->ThrowNew(clazz_io_exception, "Failed to obtain property desc.");
        return 0;
    }

    if (property->getDataType() != MTP_TYPE_UINT64) {
        env->ThrowNew(clazz_io_exception, "Unexpected property data type.");
        return 0;
    }

    if (!device->getObjectPropValue(handle, property.get())) {
        env->ThrowNew(clazz_io_exception, "Failed to obtain property value.");
        return 0;
    }

    const jlong object_size = static_cast<jlong>(property->getCurrentValue().u.u64);
    if (object_size < 0) {
        env->ThrowNew(clazz_io_exception, "Object size is too large to express as jlong.");
        return 0;
    }

    return object_size;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gMethods[] = {
    {"native_open",             "(Ljava/lang/String;I)Z",
                                        (void *)android_mtp_MtpDevice_open},
    {"native_close",            "()V",  (void *)android_mtp_MtpDevice_close},
    {"native_get_device_info",  "()Landroid/mtp/MtpDeviceInfo;",
                                        (void *)android_mtp_MtpDevice_get_device_info},
    {"native_set_device_property_init_version",  "(Ljava/lang/String;)I",
                        (void *)android_mtp_MtpDevice_set_device_property_init_version},
    {"native_get_storage_ids",  "()[I", (void *)android_mtp_MtpDevice_get_storage_ids},
    {"native_get_storage_info", "(I)Landroid/mtp/MtpStorageInfo;",
                                        (void *)android_mtp_MtpDevice_get_storage_info},
    {"native_get_object_handles","(III)[I",
                                        (void *)android_mtp_MtpDevice_get_object_handles},
    {"native_get_object_info",  "(I)Landroid/mtp/MtpObjectInfo;",
                                        (void *)android_mtp_MtpDevice_get_object_info},
    {"native_get_object",       "(IJ)[B",(void *)android_mtp_MtpDevice_get_object},
    {"native_get_partial_object", "(IJJ[B)J", (void *)android_mtp_MtpDevice_get_partial_object},
    {"native_get_partial_object_64", "(IJJ[B)I",
                                        (void *)android_mtp_MtpDevice_get_partial_object_64},
    {"native_get_thumbnail",    "(I)[B",(void *)android_mtp_MtpDevice_get_thumbnail},
    {"native_delete_object",    "(I)Z", (void *)android_mtp_MtpDevice_delete_object},
    {"native_get_parent",       "(I)I", (void *)android_mtp_MtpDevice_get_parent},
    {"native_get_storage_id",   "(I)I", (void *)android_mtp_MtpDevice_get_storage_id},
    {"native_import_file",      "(ILjava/lang/String;)Z",
                                        (void *)android_mtp_MtpDevice_import_file},
    {"native_import_file",      "(II)Z",(void *)android_mtp_MtpDevice_import_file_to_fd},
    {"native_send_object",      "(IJI)Z",(void *)android_mtp_MtpDevice_send_object},
    {"native_send_object_info", "(Landroid/mtp/MtpObjectInfo;)Landroid/mtp/MtpObjectInfo;",
                                        (void *)android_mtp_MtpDevice_send_object_info},
    {"native_submit_event_request",  "()I", (void *)android_mtp_MtpDevice_submit_event_request},
    {"native_reap_event_request",   "(I)Landroid/mtp/MtpEvent;",
                                            (void *)android_mtp_MtpDevice_reap_event_request},
    {"native_discard_event_request", "(I)V", (void *)android_mtp_MtpDevice_discard_event_request},

    {"native_get_object_size_long", "(II)J", (void *)android_mtp_MtpDevice_get_object_size_long},
};

int register_android_mtp_MtpDevice(JNIEnv *env)
{
    ALOGD("register_android_mtp_MtpDevice\n");
    return AndroidRuntime::registerNativeMethods(env,
                "android/mtp/MtpDevice", gMethods, NELEM(gMethods));
}

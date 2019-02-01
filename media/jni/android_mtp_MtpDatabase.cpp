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

#define LOG_TAG "MtpDatabaseJNI"
#include "utils/Log.h"
#include "utils/String8.h"

#include "android_media_Utils.h"
#include "mtp.h"
#include "IMtpDatabase.h"
#include "MtpDataPacket.h"
#include "MtpObjectInfo.h"
#include "MtpProperty.h"
#include "MtpStringBuffer.h"
#include "MtpUtils.h"

#include "src/piex_types.h"
#include "src/piex.h"

extern "C" {
#include "libexif/exif-content.h"
#include "libexif/exif-data.h"
#include "libexif/exif-tag.h"
#include "libexif/exif-utils.h"
}

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <jni.h>
#include <media/stagefright/NuMediaExtractor.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>

#include <assert.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdio.h>
#include <unistd.h>

using namespace android;

// ----------------------------------------------------------------------------

static jmethodID method_beginSendObject;
static jmethodID method_endSendObject;
static jmethodID method_rescanFile;
static jmethodID method_getObjectList;
static jmethodID method_getNumObjects;
static jmethodID method_getSupportedPlaybackFormats;
static jmethodID method_getSupportedCaptureFormats;
static jmethodID method_getSupportedObjectProperties;
static jmethodID method_getSupportedDeviceProperties;
static jmethodID method_setObjectProperty;
static jmethodID method_getDeviceProperty;
static jmethodID method_setDeviceProperty;
static jmethodID method_getObjectPropertyList;
static jmethodID method_getObjectInfo;
static jmethodID method_getObjectFilePath;
static jmethodID method_beginDeleteObject;
static jmethodID method_endDeleteObject;
static jmethodID method_beginMoveObject;
static jmethodID method_endMoveObject;
static jmethodID method_beginCopyObject;
static jmethodID method_endCopyObject;
static jmethodID method_getObjectReferences;
static jmethodID method_setObjectReferences;

static jfieldID field_context;

// MtpPropertyList methods
static jmethodID method_getCode;
static jmethodID method_getCount;
static jmethodID method_getObjectHandles;
static jmethodID method_getPropertyCodes;
static jmethodID method_getDataTypes;
static jmethodID method_getLongValues;
static jmethodID method_getStringValues;


IMtpDatabase* getMtpDatabase(JNIEnv *env, jobject database) {
    return (IMtpDatabase *)env->GetLongField(database, field_context);
}

// ----------------------------------------------------------------------------

class MtpDatabase : public IMtpDatabase {
private:
    jobject         mDatabase;
    jintArray       mIntBuffer;
    jlongArray      mLongBuffer;
    jcharArray      mStringBuffer;

public:
                                    MtpDatabase(JNIEnv *env, jobject client);
    virtual                         ~MtpDatabase();
    void                            cleanup(JNIEnv *env);

    virtual MtpObjectHandle         beginSendObject(const char* path,
                                            MtpObjectFormat format,
                                            MtpObjectHandle parent,
                                            MtpStorageID storage);

    virtual void                    endSendObject(MtpObjectHandle handle, bool succeeded);

    virtual void                    rescanFile(const char* path,
                                            MtpObjectHandle handle,
                                            MtpObjectFormat format);

    virtual MtpObjectHandleList*    getObjectList(MtpStorageID storageID,
                                    MtpObjectFormat format,
                                    MtpObjectHandle parent);

    virtual int                     getNumObjects(MtpStorageID storageID,
                                            MtpObjectFormat format,
                                            MtpObjectHandle parent);

    // callee should delete[] the results from these
    // results can be NULL
    virtual MtpObjectFormatList*    getSupportedPlaybackFormats();
    virtual MtpObjectFormatList*    getSupportedCaptureFormats();
    virtual MtpObjectPropertyList*  getSupportedObjectProperties(MtpObjectFormat format);
    virtual MtpDevicePropertyList*  getSupportedDeviceProperties();

    virtual MtpResponseCode         getObjectPropertyValue(MtpObjectHandle handle,
                                            MtpObjectProperty property,
                                            MtpDataPacket& packet);

    virtual MtpResponseCode         setObjectPropertyValue(MtpObjectHandle handle,
                                            MtpObjectProperty property,
                                            MtpDataPacket& packet);

    virtual MtpResponseCode         getDevicePropertyValue(MtpDeviceProperty property,
                                            MtpDataPacket& packet);

    virtual MtpResponseCode         setDevicePropertyValue(MtpDeviceProperty property,
                                            MtpDataPacket& packet);

    virtual MtpResponseCode         resetDeviceProperty(MtpDeviceProperty property);

    virtual MtpResponseCode         getObjectPropertyList(MtpObjectHandle handle,
                                            uint32_t format, uint32_t property,
                                            int groupCode, int depth,
                                            MtpDataPacket& packet);

    virtual MtpResponseCode         getObjectInfo(MtpObjectHandle handle,
                                            MtpObjectInfo& info);

    virtual void*                   getThumbnail(MtpObjectHandle handle, size_t& outThumbSize);

    virtual MtpResponseCode         getObjectFilePath(MtpObjectHandle handle,
                                            MtpStringBuffer& outFilePath,
                                            int64_t& outFileLength,
                                            MtpObjectFormat& outFormat);
    virtual MtpResponseCode         beginDeleteObject(MtpObjectHandle handle);
    virtual void                    endDeleteObject(MtpObjectHandle handle, bool succeeded);

    bool                            getObjectPropertyInfo(MtpObjectProperty property, int& type);
    bool                            getDevicePropertyInfo(MtpDeviceProperty property, int& type);

    virtual MtpObjectHandleList*    getObjectReferences(MtpObjectHandle handle);

    virtual MtpResponseCode         setObjectReferences(MtpObjectHandle handle,
                                            MtpObjectHandleList* references);

    virtual MtpProperty*            getObjectPropertyDesc(MtpObjectProperty property,
                                            MtpObjectFormat format);

    virtual MtpProperty*            getDevicePropertyDesc(MtpDeviceProperty property);

    virtual MtpResponseCode         beginMoveObject(MtpObjectHandle handle, MtpObjectHandle newParent,
                                            MtpStorageID newStorage);

    virtual void                    endMoveObject(MtpObjectHandle oldParent, MtpObjectHandle newParent,
                                            MtpStorageID oldStorage, MtpStorageID newStorage,
                                             MtpObjectHandle handle, bool succeeded);

    virtual MtpResponseCode         beginCopyObject(MtpObjectHandle handle, MtpObjectHandle newParent,
                                            MtpStorageID newStorage);
    virtual void                    endCopyObject(MtpObjectHandle handle, bool succeeded);

};

// ----------------------------------------------------------------------------

static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

// ----------------------------------------------------------------------------

MtpDatabase::MtpDatabase(JNIEnv *env, jobject client)
    :   mDatabase(env->NewGlobalRef(client)),
        mIntBuffer(NULL),
        mLongBuffer(NULL),
        mStringBuffer(NULL)
{
    // create buffers for out arguments
    // we don't need to be thread-safe so this is OK
    jintArray intArray = env->NewIntArray(3);
    if (!intArray) {
        return; // Already threw.
    }
    mIntBuffer = (jintArray)env->NewGlobalRef(intArray);
    jlongArray longArray = env->NewLongArray(2);
    if (!longArray) {
        return; // Already threw.
    }
    mLongBuffer = (jlongArray)env->NewGlobalRef(longArray);
    // Needs to be long enough to hold a file path for getObjectFilePath()
    jcharArray charArray = env->NewCharArray(PATH_MAX + 1);
    if (!charArray) {
        return; // Already threw.
    }
    mStringBuffer = (jcharArray)env->NewGlobalRef(charArray);
}

void MtpDatabase::cleanup(JNIEnv *env) {
    env->DeleteGlobalRef(mDatabase);
    env->DeleteGlobalRef(mIntBuffer);
    env->DeleteGlobalRef(mLongBuffer);
    env->DeleteGlobalRef(mStringBuffer);
}

MtpDatabase::~MtpDatabase() {
}

MtpObjectHandle MtpDatabase::beginSendObject(const char* path,
                                               MtpObjectFormat format,
                                               MtpObjectHandle parent,
                                               MtpStorageID storage) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jstring pathStr = env->NewStringUTF(path);
    MtpObjectHandle result = env->CallIntMethod(mDatabase, method_beginSendObject,
            pathStr, (jint)format, (jint)parent, (jint)storage);

    if (pathStr)
        env->DeleteLocalRef(pathStr);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

void MtpDatabase::endSendObject(MtpObjectHandle handle, bool succeeded) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mDatabase, method_endSendObject, (jint)handle, (jboolean)succeeded);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

void MtpDatabase::rescanFile(const char* path, MtpObjectHandle handle,
                                  MtpObjectFormat format) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jstring pathStr = env->NewStringUTF(path);
    env->CallVoidMethod(mDatabase, method_rescanFile, pathStr,
                        (jint)handle, (jint)format);

    if (pathStr)
        env->DeleteLocalRef(pathStr);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

MtpObjectHandleList* MtpDatabase::getObjectList(MtpStorageID storageID,
                                                  MtpObjectFormat format,
                                                  MtpObjectHandle parent) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jintArray array = (jintArray)env->CallObjectMethod(mDatabase, method_getObjectList,
                (jint)storageID, (jint)format, (jint)parent);
    if (!array)
        return NULL;
    MtpObjectHandleList* list = new MtpObjectHandleList();
    jint* handles = env->GetIntArrayElements(array, 0);
    jsize length = env->GetArrayLength(array);
    for (int i = 0; i < length; i++)
        list->push_back(handles[i]);
    env->ReleaseIntArrayElements(array, handles, 0);
    env->DeleteLocalRef(array);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return list;
}

int MtpDatabase::getNumObjects(MtpStorageID storageID,
                                 MtpObjectFormat format,
                                 MtpObjectHandle parent) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    int result = env->CallIntMethod(mDatabase, method_getNumObjects,
                (jint)storageID, (jint)format, (jint)parent);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

MtpObjectFormatList* MtpDatabase::getSupportedPlaybackFormats() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jintArray array = (jintArray)env->CallObjectMethod(mDatabase,
            method_getSupportedPlaybackFormats);
    if (!array)
        return NULL;
    MtpObjectFormatList* list = new MtpObjectFormatList();
    jint* formats = env->GetIntArrayElements(array, 0);
    jsize length = env->GetArrayLength(array);
    for (int i = 0; i < length; i++)
        list->push_back(formats[i]);
    env->ReleaseIntArrayElements(array, formats, 0);
    env->DeleteLocalRef(array);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return list;
}

MtpObjectFormatList* MtpDatabase::getSupportedCaptureFormats() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jintArray array = (jintArray)env->CallObjectMethod(mDatabase,
            method_getSupportedCaptureFormats);
    if (!array)
        return NULL;
    MtpObjectFormatList* list = new MtpObjectFormatList();
    jint* formats = env->GetIntArrayElements(array, 0);
    jsize length = env->GetArrayLength(array);
    for (int i = 0; i < length; i++)
        list->push_back(formats[i]);
    env->ReleaseIntArrayElements(array, formats, 0);
    env->DeleteLocalRef(array);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return list;
}

MtpObjectPropertyList* MtpDatabase::getSupportedObjectProperties(MtpObjectFormat format) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jintArray array = (jintArray)env->CallObjectMethod(mDatabase,
            method_getSupportedObjectProperties, (jint)format);
    if (!array)
        return NULL;
    MtpObjectPropertyList* list = new MtpObjectPropertyList();
    jint* properties = env->GetIntArrayElements(array, 0);
    jsize length = env->GetArrayLength(array);
    for (int i = 0; i < length; i++)
        list->push_back(properties[i]);
    env->ReleaseIntArrayElements(array, properties, 0);
    env->DeleteLocalRef(array);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return list;
}

MtpDevicePropertyList* MtpDatabase::getSupportedDeviceProperties() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jintArray array = (jintArray)env->CallObjectMethod(mDatabase,
            method_getSupportedDeviceProperties);
    if (!array)
        return NULL;
    MtpDevicePropertyList* list = new MtpDevicePropertyList();
    jint* properties = env->GetIntArrayElements(array, 0);
    jsize length = env->GetArrayLength(array);
    for (int i = 0; i < length; i++)
        list->push_back(properties[i]);
    env->ReleaseIntArrayElements(array, properties, 0);
    env->DeleteLocalRef(array);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return list;
}

MtpResponseCode MtpDatabase::getObjectPropertyValue(MtpObjectHandle handle,
                                                      MtpObjectProperty property,
                                                      MtpDataPacket& packet) {
    static_assert(sizeof(jint) >= sizeof(MtpObjectHandle),
                  "Casting MtpObjectHandle to jint loses a value");
    static_assert(sizeof(jint) >= sizeof(MtpObjectProperty),
                  "Casting MtpObjectProperty to jint loses a value");
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject list = env->CallObjectMethod(
            mDatabase,
            method_getObjectPropertyList,
            static_cast<jint>(handle),
            0,
            static_cast<jint>(property),
            0,
            0);
    MtpResponseCode result = env->CallIntMethod(list, method_getCode);
    jint count = env->CallIntMethod(list, method_getCount);
    if (count != 1)
        result = MTP_RESPONSE_GENERAL_ERROR;

    if (result == MTP_RESPONSE_OK) {
        jintArray objectHandlesArray = (jintArray)env->CallObjectMethod(list, method_getObjectHandles);
        jintArray propertyCodesArray = (jintArray)env->CallObjectMethod(list, method_getPropertyCodes);
        jintArray dataTypesArray = (jintArray)env->CallObjectMethod(list, method_getDataTypes);
        jlongArray longValuesArray = (jlongArray)env->CallObjectMethod(list, method_getLongValues);
        jobjectArray stringValuesArray = (jobjectArray)env->CallObjectMethod(list, method_getStringValues);

        jint* objectHandles = env->GetIntArrayElements(objectHandlesArray, 0);
        jint* propertyCodes = env->GetIntArrayElements(propertyCodesArray, 0);
        jint* dataTypes = env->GetIntArrayElements(dataTypesArray, 0);
        jlong* longValues = env->GetLongArrayElements(longValuesArray, 0);

        int type = dataTypes[0];
        jlong longValue = (longValues ? longValues[0] : 0);

        switch (type) {
            case MTP_TYPE_INT8:
                packet.putInt8(longValue);
                break;
            case MTP_TYPE_UINT8:
                packet.putUInt8(longValue);
                break;
            case MTP_TYPE_INT16:
                packet.putInt16(longValue);
                break;
            case MTP_TYPE_UINT16:
                packet.putUInt16(longValue);
                break;
            case MTP_TYPE_INT32:
                packet.putInt32(longValue);
                break;
            case MTP_TYPE_UINT32:
                packet.putUInt32(longValue);
                break;
            case MTP_TYPE_INT64:
                packet.putInt64(longValue);
                break;
            case MTP_TYPE_UINT64:
                packet.putUInt64(longValue);
                break;
            case MTP_TYPE_INT128:
                packet.putInt128(longValue);
                break;
            case MTP_TYPE_UINT128:
                packet.putUInt128(longValue);
                break;
            case MTP_TYPE_STR:
            {
                jstring stringValue = (jstring)env->GetObjectArrayElement(stringValuesArray, 0);
                const char* str = (stringValue ? env->GetStringUTFChars(stringValue, NULL) : NULL);
                if (stringValue) {
                    packet.putString(str);
                    env->ReleaseStringUTFChars(stringValue, str);
                } else {
                    packet.putEmptyString();
                }
                env->DeleteLocalRef(stringValue);
                break;
             }
            default:
                ALOGE("unsupported type in getObjectPropertyValue\n");
                result = MTP_RESPONSE_INVALID_OBJECT_PROP_FORMAT;
        }
        env->ReleaseIntArrayElements(objectHandlesArray, objectHandles, 0);
        env->ReleaseIntArrayElements(propertyCodesArray, propertyCodes, 0);
        env->ReleaseIntArrayElements(dataTypesArray, dataTypes, 0);
        env->ReleaseLongArrayElements(longValuesArray, longValues, 0);

        env->DeleteLocalRef(objectHandlesArray);
        env->DeleteLocalRef(propertyCodesArray);
        env->DeleteLocalRef(dataTypesArray);
        env->DeleteLocalRef(longValuesArray);
        env->DeleteLocalRef(stringValuesArray);
    }

    env->DeleteLocalRef(list);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

static bool readLongValue(int type, MtpDataPacket& packet, jlong& longValue) {
    switch (type) {
        case MTP_TYPE_INT8: {
            int8_t temp;
            if (!packet.getInt8(temp)) return false;
            longValue = temp;
            break;
        }
        case MTP_TYPE_UINT8: {
            uint8_t temp;
            if (!packet.getUInt8(temp)) return false;
            longValue = temp;
            break;
        }
        case MTP_TYPE_INT16: {
            int16_t temp;
            if (!packet.getInt16(temp)) return false;
            longValue = temp;
            break;
        }
        case MTP_TYPE_UINT16: {
            uint16_t temp;
            if (!packet.getUInt16(temp)) return false;
            longValue = temp;
            break;
        }
        case MTP_TYPE_INT32: {
            int32_t temp;
            if (!packet.getInt32(temp)) return false;
            longValue = temp;
            break;
        }
        case MTP_TYPE_UINT32: {
            uint32_t temp;
            if (!packet.getUInt32(temp)) return false;
            longValue = temp;
            break;
        }
        case MTP_TYPE_INT64: {
            int64_t temp;
            if (!packet.getInt64(temp)) return false;
            longValue = temp;
            break;
        }
        case MTP_TYPE_UINT64: {
            uint64_t temp;
            if (!packet.getUInt64(temp)) return false;
            longValue = temp;
            break;
        }
        default:
            ALOGE("unsupported type in readLongValue");
            return false;
    }
    return true;
}

MtpResponseCode MtpDatabase::setObjectPropertyValue(MtpObjectHandle handle,
                                                      MtpObjectProperty property,
                                                      MtpDataPacket& packet) {
    int         type;

    if (!getObjectPropertyInfo(property, type))
        return MTP_RESPONSE_OBJECT_PROP_NOT_SUPPORTED;

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jlong longValue = 0;
    jstring stringValue = NULL;
    MtpResponseCode result = MTP_RESPONSE_INVALID_OBJECT_PROP_FORMAT;

    if (type == MTP_TYPE_STR) {
        MtpStringBuffer buffer;
        if (!packet.getString(buffer)) goto fail;
        stringValue = env->NewStringUTF((const char *)buffer);
    } else {
        if (!readLongValue(type, packet, longValue)) goto fail;
    }

    result = env->CallIntMethod(mDatabase, method_setObjectProperty,
                (jint)handle, (jint)property, longValue, stringValue);
    if (stringValue)
        env->DeleteLocalRef(stringValue);

fail:
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

MtpResponseCode MtpDatabase::getDevicePropertyValue(MtpDeviceProperty property,
                                                      MtpDataPacket& packet) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    int type;

    if (!getDevicePropertyInfo(property, type))
        return MTP_RESPONSE_DEVICE_PROP_NOT_SUPPORTED;

    jint result = env->CallIntMethod(mDatabase, method_getDeviceProperty,
                (jint)property, mLongBuffer, mStringBuffer);
    if (result != MTP_RESPONSE_OK) {
        checkAndClearExceptionFromCallback(env, __FUNCTION__);
        return result;
    }

    jlong* longValues = env->GetLongArrayElements(mLongBuffer, 0);
    jlong longValue = longValues[0];
    env->ReleaseLongArrayElements(mLongBuffer, longValues, 0);

    switch (type) {
        case MTP_TYPE_INT8:
            packet.putInt8(longValue);
            break;
        case MTP_TYPE_UINT8:
            packet.putUInt8(longValue);
            break;
        case MTP_TYPE_INT16:
            packet.putInt16(longValue);
            break;
        case MTP_TYPE_UINT16:
            packet.putUInt16(longValue);
            break;
        case MTP_TYPE_INT32:
            packet.putInt32(longValue);
            break;
        case MTP_TYPE_UINT32:
            packet.putUInt32(longValue);
            break;
        case MTP_TYPE_INT64:
            packet.putInt64(longValue);
            break;
        case MTP_TYPE_UINT64:
            packet.putUInt64(longValue);
            break;
        case MTP_TYPE_INT128:
            packet.putInt128(longValue);
            break;
        case MTP_TYPE_UINT128:
            packet.putInt128(longValue);
            break;
        case MTP_TYPE_STR:
        {
            jchar* str = env->GetCharArrayElements(mStringBuffer, 0);
            packet.putString(str);
            env->ReleaseCharArrayElements(mStringBuffer, str, 0);
            break;
        }
        default:
            ALOGE("unsupported type in getDevicePropertyValue\n");
            return MTP_RESPONSE_INVALID_DEVICE_PROP_FORMAT;
    }

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return MTP_RESPONSE_OK;
}

MtpResponseCode MtpDatabase::setDevicePropertyValue(MtpDeviceProperty property,
                                                      MtpDataPacket& packet) {
    int         type;

    if (!getDevicePropertyInfo(property, type))
        return MTP_RESPONSE_DEVICE_PROP_NOT_SUPPORTED;

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jlong longValue = 0;
    jstring stringValue = NULL;
    MtpResponseCode result = MTP_RESPONSE_INVALID_DEVICE_PROP_FORMAT;

    if (type == MTP_TYPE_STR) {
        MtpStringBuffer buffer;
        if (!packet.getString(buffer)) goto fail;
        stringValue = env->NewStringUTF((const char *)buffer);
    } else {
        if (!readLongValue(type, packet, longValue)) goto fail;
    }

    result = env->CallIntMethod(mDatabase, method_setDeviceProperty,
                (jint)property, longValue, stringValue);
    if (stringValue)
        env->DeleteLocalRef(stringValue);

fail:
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

MtpResponseCode MtpDatabase::resetDeviceProperty(MtpDeviceProperty /*property*/) {
    return -1;
}

MtpResponseCode MtpDatabase::getObjectPropertyList(MtpObjectHandle handle,
                                                     uint32_t format, uint32_t property,
                                                     int groupCode, int depth,
                                                     MtpDataPacket& packet) {
    static_assert(sizeof(jint) >= sizeof(MtpObjectHandle),
                  "Casting MtpObjectHandle to jint loses a value");
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject list = env->CallObjectMethod(
            mDatabase,
            method_getObjectPropertyList,
            static_cast<jint>(handle),
            static_cast<jint>(format),
            static_cast<jint>(property),
            static_cast<jint>(groupCode),
            static_cast<jint>(depth));
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    if (!list)
        return MTP_RESPONSE_GENERAL_ERROR;
    int count = env->CallIntMethod(list, method_getCount);
    MtpResponseCode result = env->CallIntMethod(list, method_getCode);

    packet.putUInt32(count);
    if (count > 0) {
        jintArray objectHandlesArray = (jintArray)env->CallObjectMethod(list, method_getObjectHandles);
        jintArray propertyCodesArray = (jintArray)env->CallObjectMethod(list, method_getPropertyCodes);
        jintArray dataTypesArray = (jintArray)env->CallObjectMethod(list, method_getDataTypes);
        jlongArray longValuesArray = (jlongArray)env->CallObjectMethod(list, method_getLongValues);
        jobjectArray stringValuesArray = (jobjectArray)env->CallObjectMethod(list, method_getStringValues);

        jint* objectHandles = env->GetIntArrayElements(objectHandlesArray, 0);
        jint* propertyCodes = env->GetIntArrayElements(propertyCodesArray, 0);
        jint* dataTypes = env->GetIntArrayElements(dataTypesArray, 0);
        jlong* longValues = (longValuesArray ? env->GetLongArrayElements(longValuesArray, 0) : NULL);

        for (int i = 0; i < count; i++) {
            packet.putUInt32(objectHandles[i]);
            packet.putUInt16(propertyCodes[i]);
            int type = dataTypes[i];
            packet.putUInt16(type);

            if (type == MTP_TYPE_STR) {
                jstring value = (jstring)env->GetObjectArrayElement(stringValuesArray, i);
                const char *valueStr = (value ? env->GetStringUTFChars(value, NULL) : NULL);
                if (valueStr) {
                    packet.putString(valueStr);
                    env->ReleaseStringUTFChars(value, valueStr);
                } else {
                    packet.putEmptyString();
                }
                env->DeleteLocalRef(value);
                continue;
            }

            if (!longValues) {
                ALOGE("bad longValuesArray value in MyMtpDatabase::getObjectPropertyList");
                continue;
            }

            switch (type) {
                case MTP_TYPE_INT8:
                    packet.putInt8(longValues[i]);
                    break;
                case MTP_TYPE_UINT8:
                    packet.putUInt8(longValues[i]);
                    break;
                case MTP_TYPE_INT16:
                    packet.putInt16(longValues[i]);
                    break;
                case MTP_TYPE_UINT16:
                    packet.putUInt16(longValues[i]);
                    break;
                case MTP_TYPE_INT32:
                    packet.putInt32(longValues[i]);
                    break;
                case MTP_TYPE_UINT32:
                    packet.putUInt32(longValues[i]);
                    break;
                case MTP_TYPE_INT64:
                    packet.putInt64(longValues[i]);
                    break;
                case MTP_TYPE_UINT64:
                    packet.putUInt64(longValues[i]);
                    break;
                case MTP_TYPE_INT128:
                    packet.putInt128(longValues[i]);
                    break;
                case MTP_TYPE_UINT128:
                    packet.putUInt128(longValues[i]);
                    break;
                default:
                    ALOGE("bad or unsupported data type in MtpDatabase::getObjectPropertyList");
                    break;
            }
        }

        env->ReleaseIntArrayElements(objectHandlesArray, objectHandles, 0);
        env->ReleaseIntArrayElements(propertyCodesArray, propertyCodes, 0);
        env->ReleaseIntArrayElements(dataTypesArray, dataTypes, 0);
        env->ReleaseLongArrayElements(longValuesArray, longValues, 0);

        env->DeleteLocalRef(objectHandlesArray);
        env->DeleteLocalRef(propertyCodesArray);
        env->DeleteLocalRef(dataTypesArray);
        env->DeleteLocalRef(longValuesArray);
        env->DeleteLocalRef(stringValuesArray);
    }

    env->DeleteLocalRef(list);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

static void foreachentry(ExifEntry *entry, void* /* user */) {
    char buf[1024];
    ALOGI("entry %x, format %d, size %d: %s",
            entry->tag, entry->format, entry->size, exif_entry_get_value(entry, buf, sizeof(buf)));
}

static void foreachcontent(ExifContent *content, void *user) {
    ALOGI("content %d", exif_content_get_ifd(content));
    exif_content_foreach_entry(content, foreachentry, user);
}

static long getLongFromExifEntry(ExifEntry *e) {
    ExifByteOrder o = exif_data_get_byte_order(e->parent->parent);
    return exif_get_long(e->data, o);
}

static ExifData *getExifFromExtractor(const char *path) {
    std::unique_ptr<uint8_t[]> exifBuf;
    ExifData *exifdata = NULL;

    FILE *fp = fopen (path, "rb");
    if (!fp) {
        ALOGE("failed to open file");
        return NULL;
    }

    sp<NuMediaExtractor> extractor = new NuMediaExtractor();
    fseek(fp, 0L, SEEK_END);
    if (extractor->setDataSource(fileno(fp), 0, ftell(fp)) != OK) {
        ALOGE("failed to setDataSource");
        fclose(fp);
        return NULL;
    }

    off64_t offset;
    size_t size;
    if (extractor->getExifOffsetSize(&offset, &size) != OK) {
        fclose(fp);
        return NULL;
    }

    exifBuf.reset(new uint8_t[size]);
    fseek(fp, offset, SEEK_SET);
    if (fread(exifBuf.get(), 1, size, fp) == size) {
        exifdata = exif_data_new_from_data(exifBuf.get(), size);
    }

    fclose(fp);
    return exifdata;
}

MtpResponseCode MtpDatabase::getObjectInfo(MtpObjectHandle handle,
                                             MtpObjectInfo& info) {
    MtpStringBuffer path;
    int64_t         length;
    MtpObjectFormat format;

    MtpResponseCode result = getObjectFilePath(handle, path, length, format);
    if (result != MTP_RESPONSE_OK) {
        return result;
    }
    info.mCompressedSize = (length > 0xFFFFFFFFLL ? 0xFFFFFFFF : (uint32_t)length);

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (!env->CallBooleanMethod(mDatabase, method_getObjectInfo,
                (jint)handle, mIntBuffer, mStringBuffer, mLongBuffer)) {
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    }

    jint* intValues = env->GetIntArrayElements(mIntBuffer, 0);
    info.mStorageID = intValues[0];
    info.mFormat = intValues[1];
    info.mParent = intValues[2];
    env->ReleaseIntArrayElements(mIntBuffer, intValues, 0);

    jlong* longValues = env->GetLongArrayElements(mLongBuffer, 0);
    info.mDateCreated = longValues[0];
    info.mDateModified = longValues[1];
    env->ReleaseLongArrayElements(mLongBuffer, longValues, 0);

    if ((false)) {
        info.mAssociationType = (format == MTP_FORMAT_ASSOCIATION ?
                                MTP_ASSOCIATION_TYPE_GENERIC_FOLDER :
                                MTP_ASSOCIATION_TYPE_UNDEFINED);
    }
    info.mAssociationType = MTP_ASSOCIATION_TYPE_UNDEFINED;

    jchar* str = env->GetCharArrayElements(mStringBuffer, 0);
    MtpStringBuffer temp(str);
    info.mName = strdup(temp);
    env->ReleaseCharArrayElements(mStringBuffer, str, 0);

    // read EXIF data for thumbnail information
    switch (info.mFormat) {
        case MTP_FORMAT_EXIF_JPEG:
        case MTP_FORMAT_HEIF:
        case MTP_FORMAT_JFIF: {
            ExifData *exifdata;
            if (info.mFormat == MTP_FORMAT_HEIF) {
                exifdata = getExifFromExtractor(path);
            } else {
                exifdata = exif_data_new_from_file(path);
            }
            if (exifdata) {
                if ((false)) {
                    exif_data_foreach_content(exifdata, foreachcontent, NULL);
                }

                ExifEntry *w = exif_content_get_entry(
                        exifdata->ifd[EXIF_IFD_EXIF], EXIF_TAG_PIXEL_X_DIMENSION);
                ExifEntry *h = exif_content_get_entry(
                        exifdata->ifd[EXIF_IFD_EXIF], EXIF_TAG_PIXEL_Y_DIMENSION);
                info.mThumbCompressedSize = exifdata->data ? exifdata->size : 0;
                info.mThumbFormat = MTP_FORMAT_EXIF_JPEG;
                info.mImagePixWidth = w ? getLongFromExifEntry(w) : 0;
                info.mImagePixHeight = h ? getLongFromExifEntry(h) : 0;
                exif_data_unref(exifdata);
            }
            break;
        }

        // Except DNG, all supported RAW image formats are not defined in PTP 1.2 specification.
        // Most of RAW image formats are based on TIFF or TIFF/EP. To render Fuji's RAF format,
        // it checks MTP_FORMAT_DEFINED case since it's designed as a custom format.
        case MTP_FORMAT_DNG:
        case MTP_FORMAT_TIFF:
        case MTP_FORMAT_TIFF_EP:
        case MTP_FORMAT_DEFINED: {
            String8 temp(path);
            std::unique_ptr<FileStream> stream(new FileStream(temp));
            piex::PreviewImageData image_data;
            if (!GetExifFromRawImage(stream.get(), temp, image_data)) {
                // Couldn't parse EXIF data from a image file via piex.
                break;
            }

            info.mThumbCompressedSize = image_data.thumbnail.length;
            info.mThumbFormat = MTP_FORMAT_EXIF_JPEG;
            info.mImagePixWidth = image_data.full_width;
            info.mImagePixHeight = image_data.full_height;

            break;
        }
    }

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return MTP_RESPONSE_OK;
}

void* MtpDatabase::getThumbnail(MtpObjectHandle handle, size_t& outThumbSize) {
    MtpStringBuffer path;
    int64_t length;
    MtpObjectFormat format;
    void* result = NULL;
    outThumbSize = 0;

    if (getObjectFilePath(handle, path, length, format) == MTP_RESPONSE_OK) {
        switch (format) {
            case MTP_FORMAT_EXIF_JPEG:
            case MTP_FORMAT_HEIF:
            case MTP_FORMAT_JFIF: {
                ExifData *exifdata;
                if (format == MTP_FORMAT_HEIF) {
                    exifdata = getExifFromExtractor(path);
                } else {
                    exifdata = exif_data_new_from_file(path);
                }
                if (exifdata) {
                    if (exifdata->data) {
                        result = malloc(exifdata->size);
                        if (result) {
                            memcpy(result, exifdata->data, exifdata->size);
                            outThumbSize = exifdata->size;
                        }
                    }
                    exif_data_unref(exifdata);
                }
                break;
            }

            // See the above comment on getObjectInfo() method.
            case MTP_FORMAT_DNG:
            case MTP_FORMAT_TIFF:
            case MTP_FORMAT_TIFF_EP:
            case MTP_FORMAT_DEFINED: {
                String8 temp(path);
                std::unique_ptr<FileStream> stream(new FileStream(temp));
                piex::PreviewImageData image_data;
                if (!GetExifFromRawImage(stream.get(), temp, image_data)) {
                    // Couldn't parse EXIF data from a image file via piex.
                    break;
                }

                if (image_data.thumbnail.length == 0
                        || image_data.thumbnail.format != ::piex::Image::kJpegCompressed) {
                    // No thumbnail or non jpeg thumbnail.
                    break;
                }

                result = malloc(image_data.thumbnail.length);
                if (result) {
                    piex::Error err = stream.get()->GetData(
                            image_data.thumbnail.offset,
                            image_data.thumbnail.length,
                            (std::uint8_t *)result);
                    if (err == piex::Error::kOk) {
                        outThumbSize = image_data.thumbnail.length;
                    } else {
                        free(result);
                        result = NULL;
                    }
                }
                break;
            }
        }
    }

    return result;
}

MtpResponseCode MtpDatabase::getObjectFilePath(MtpObjectHandle handle,
                                                 MtpStringBuffer& outFilePath,
                                                 int64_t& outFileLength,
                                                 MtpObjectFormat& outFormat) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jint result = env->CallIntMethod(mDatabase, method_getObjectFilePath,
                (jint)handle, mStringBuffer, mLongBuffer);
    if (result != MTP_RESPONSE_OK) {
        checkAndClearExceptionFromCallback(env, __FUNCTION__);
        return result;
    }

    jchar* str = env->GetCharArrayElements(mStringBuffer, 0);
    outFilePath.set(str);
    env->ReleaseCharArrayElements(mStringBuffer, str, 0);

    jlong* longValues = env->GetLongArrayElements(mLongBuffer, 0);
    outFileLength = longValues[0];
    outFormat = longValues[1];
    env->ReleaseLongArrayElements(mLongBuffer, longValues, 0);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

MtpResponseCode MtpDatabase::beginDeleteObject(MtpObjectHandle handle) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    MtpResponseCode result = env->CallIntMethod(mDatabase, method_beginDeleteObject, (jint)handle);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

void MtpDatabase::endDeleteObject(MtpObjectHandle handle, bool succeeded) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mDatabase, method_endDeleteObject, (jint)handle, (jboolean) succeeded);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

MtpResponseCode MtpDatabase::beginMoveObject(MtpObjectHandle handle, MtpObjectHandle newParent,
        MtpStorageID newStorage) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    MtpResponseCode result = env->CallIntMethod(mDatabase, method_beginMoveObject,
                (jint)handle, (jint)newParent, (jint) newStorage);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

void MtpDatabase::endMoveObject(MtpObjectHandle oldParent, MtpObjectHandle newParent,
                                            MtpStorageID oldStorage, MtpStorageID newStorage,
                                             MtpObjectHandle handle, bool succeeded) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mDatabase, method_endMoveObject,
                (jint)oldParent, (jint) newParent, (jint) oldStorage, (jint) newStorage,
                (jint) handle, (jboolean) succeeded);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

MtpResponseCode MtpDatabase::beginCopyObject(MtpObjectHandle handle, MtpObjectHandle newParent,
        MtpStorageID newStorage) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    MtpResponseCode result = env->CallIntMethod(mDatabase, method_beginCopyObject,
                (jint)handle, (jint)newParent, (jint) newStorage);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

void MtpDatabase::endCopyObject(MtpObjectHandle handle, bool succeeded) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mDatabase, method_endCopyObject, (jint)handle, (jboolean)succeeded);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}


struct PropertyTableEntry {
    MtpObjectProperty   property;
    int                 type;
};

static const PropertyTableEntry   kObjectPropertyTable[] = {
    {   MTP_PROPERTY_STORAGE_ID,        MTP_TYPE_UINT32     },
    {   MTP_PROPERTY_OBJECT_FORMAT,     MTP_TYPE_UINT16     },
    {   MTP_PROPERTY_PROTECTION_STATUS, MTP_TYPE_UINT16     },
    {   MTP_PROPERTY_OBJECT_SIZE,       MTP_TYPE_UINT64     },
    {   MTP_PROPERTY_OBJECT_FILE_NAME,  MTP_TYPE_STR        },
    {   MTP_PROPERTY_DATE_MODIFIED,     MTP_TYPE_STR        },
    {   MTP_PROPERTY_PARENT_OBJECT,     MTP_TYPE_UINT32     },
    {   MTP_PROPERTY_PERSISTENT_UID,    MTP_TYPE_UINT128    },
    {   MTP_PROPERTY_NAME,              MTP_TYPE_STR        },
    {   MTP_PROPERTY_DISPLAY_NAME,      MTP_TYPE_STR        },
    {   MTP_PROPERTY_DATE_ADDED,        MTP_TYPE_STR        },
    {   MTP_PROPERTY_ARTIST,            MTP_TYPE_STR        },
    {   MTP_PROPERTY_ALBUM_NAME,        MTP_TYPE_STR        },
    {   MTP_PROPERTY_ALBUM_ARTIST,      MTP_TYPE_STR        },
    {   MTP_PROPERTY_TRACK,             MTP_TYPE_UINT16     },
    {   MTP_PROPERTY_ORIGINAL_RELEASE_DATE, MTP_TYPE_STR    },
    {   MTP_PROPERTY_GENRE,             MTP_TYPE_STR        },
    {   MTP_PROPERTY_COMPOSER,          MTP_TYPE_STR        },
    {   MTP_PROPERTY_DURATION,          MTP_TYPE_UINT32     },
    {   MTP_PROPERTY_DESCRIPTION,       MTP_TYPE_STR        },
    {   MTP_PROPERTY_AUDIO_WAVE_CODEC,  MTP_TYPE_UINT32     },
    {   MTP_PROPERTY_BITRATE_TYPE,      MTP_TYPE_UINT16     },
    {   MTP_PROPERTY_AUDIO_BITRATE,     MTP_TYPE_UINT32     },
    {   MTP_PROPERTY_NUMBER_OF_CHANNELS,MTP_TYPE_UINT16     },
    {   MTP_PROPERTY_SAMPLE_RATE,       MTP_TYPE_UINT32     },
};

static const PropertyTableEntry   kDevicePropertyTable[] = {
    {   MTP_DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER,    MTP_TYPE_STR },
    {   MTP_DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME,       MTP_TYPE_STR },
    {   MTP_DEVICE_PROPERTY_IMAGE_SIZE,                 MTP_TYPE_STR },
    {   MTP_DEVICE_PROPERTY_BATTERY_LEVEL,              MTP_TYPE_UINT8 },
    {   MTP_DEVICE_PROPERTY_PERCEIVED_DEVICE_TYPE,      MTP_TYPE_UINT32 },
};

bool MtpDatabase::getObjectPropertyInfo(MtpObjectProperty property, int& type) {
    int count = sizeof(kObjectPropertyTable) / sizeof(kObjectPropertyTable[0]);
    const PropertyTableEntry* entry = kObjectPropertyTable;
    for (int i = 0; i < count; i++, entry++) {
        if (entry->property == property) {
            type = entry->type;
            return true;
        }
    }
    return false;
}

bool MtpDatabase::getDevicePropertyInfo(MtpDeviceProperty property, int& type) {
    int count = sizeof(kDevicePropertyTable) / sizeof(kDevicePropertyTable[0]);
    const PropertyTableEntry* entry = kDevicePropertyTable;
    for (int i = 0; i < count; i++, entry++) {
        if (entry->property == property) {
            type = entry->type;
            return true;
        }
    }
    return false;
}

MtpObjectHandleList* MtpDatabase::getObjectReferences(MtpObjectHandle handle) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jintArray array = (jintArray)env->CallObjectMethod(mDatabase, method_getObjectReferences,
                (jint)handle);
    if (!array)
        return NULL;
    MtpObjectHandleList* list = new MtpObjectHandleList();
    jint* handles = env->GetIntArrayElements(array, 0);
    jsize length = env->GetArrayLength(array);
    for (int i = 0; i < length; i++)
        list->push_back(handles[i]);
    env->ReleaseIntArrayElements(array, handles, 0);
    env->DeleteLocalRef(array);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return list;
}

MtpResponseCode MtpDatabase::setObjectReferences(MtpObjectHandle handle,
                                                   MtpObjectHandleList* references) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    int count = references->size();
    jintArray array = env->NewIntArray(count);
    if (!array) {
        ALOGE("out of memory in setObjectReferences");
        return false;
    }
    jint* handles = env->GetIntArrayElements(array, 0);
     for (int i = 0; i < count; i++)
        handles[i] = (*references)[i];
    env->ReleaseIntArrayElements(array, handles, 0);
    MtpResponseCode result = env->CallIntMethod(mDatabase, method_setObjectReferences,
                (jint)handle, array);
    env->DeleteLocalRef(array);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

MtpProperty* MtpDatabase::getObjectPropertyDesc(MtpObjectProperty property,
                                                  MtpObjectFormat format) {
    static const int channelEnum[] = {
                                        1,  // mono
                                        2,  // stereo
                                        3,  // 2.1
                                        4,  // 3
                                        5,  // 3.1
                                        6,  // 4
                                        7,  // 4.1
                                        8,  // 5
                                        9,  // 5.1
                                    };
    static const int bitrateEnum[] = {
                                        1,  // fixed rate
                                        2,  // variable rate
                                     };

    MtpProperty* result = NULL;
    switch (property) {
        case MTP_PROPERTY_OBJECT_FORMAT:
            // use format as default value
            result = new MtpProperty(property, MTP_TYPE_UINT16, false, format);
            break;
        case MTP_PROPERTY_PROTECTION_STATUS:
        case MTP_PROPERTY_TRACK:
            result = new MtpProperty(property, MTP_TYPE_UINT16);
            break;
        case MTP_PROPERTY_STORAGE_ID:
        case MTP_PROPERTY_PARENT_OBJECT:
        case MTP_PROPERTY_DURATION:
        case MTP_PROPERTY_AUDIO_WAVE_CODEC:
            result = new MtpProperty(property, MTP_TYPE_UINT32);
            break;
        case MTP_PROPERTY_OBJECT_SIZE:
            result = new MtpProperty(property, MTP_TYPE_UINT64);
            break;
        case MTP_PROPERTY_PERSISTENT_UID:
            result = new MtpProperty(property, MTP_TYPE_UINT128);
            break;
        case MTP_PROPERTY_NAME:
        case MTP_PROPERTY_DISPLAY_NAME:
        case MTP_PROPERTY_ARTIST:
        case MTP_PROPERTY_ALBUM_NAME:
        case MTP_PROPERTY_ALBUM_ARTIST:
        case MTP_PROPERTY_GENRE:
        case MTP_PROPERTY_COMPOSER:
        case MTP_PROPERTY_DESCRIPTION:
            result = new MtpProperty(property, MTP_TYPE_STR);
            break;
        case MTP_PROPERTY_DATE_MODIFIED:
        case MTP_PROPERTY_DATE_ADDED:
        case MTP_PROPERTY_ORIGINAL_RELEASE_DATE:
            result = new MtpProperty(property, MTP_TYPE_STR);
            result->setFormDateTime();
            break;
        case MTP_PROPERTY_OBJECT_FILE_NAME:
            // We allow renaming files and folders
            result = new MtpProperty(property, MTP_TYPE_STR, true);
            break;
        case MTP_PROPERTY_BITRATE_TYPE:
             result = new MtpProperty(property, MTP_TYPE_UINT16);
            result->setFormEnum(bitrateEnum, sizeof(bitrateEnum)/sizeof(bitrateEnum[0]));
            break;
        case MTP_PROPERTY_AUDIO_BITRATE:
            result = new MtpProperty(property, MTP_TYPE_UINT32);
            result->setFormRange(1, 1536000, 1);
            break;
        case MTP_PROPERTY_NUMBER_OF_CHANNELS:
            result = new MtpProperty(property, MTP_TYPE_UINT16);
            result->setFormEnum(channelEnum, sizeof(channelEnum)/sizeof(channelEnum[0]));
            break;
        case MTP_PROPERTY_SAMPLE_RATE:
            result = new MtpProperty(property, MTP_TYPE_UINT32);
            result->setFormRange(8000, 48000, 1);
            break;
    }

    return result;
}

MtpProperty* MtpDatabase::getDevicePropertyDesc(MtpDeviceProperty property) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    MtpProperty* result = NULL;
    bool writable = false;

    // get current value
    jint ret = env->CallIntMethod(mDatabase, method_getDeviceProperty,
        (jint)property, mLongBuffer, mStringBuffer);
    if (ret == MTP_RESPONSE_OK) {
        switch (property) {
            case MTP_DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER:
            case MTP_DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME:
                writable = true;
                // fall through
            case MTP_DEVICE_PROPERTY_IMAGE_SIZE:
            {
                result = new MtpProperty(property, MTP_TYPE_STR, writable);
                jchar* str = env->GetCharArrayElements(mStringBuffer, 0);
                result->setCurrentValue(str);
                // for read-only properties it is safe to assume current value is default value
                if (!writable)
                    result->setDefaultValue(str);
                env->ReleaseCharArrayElements(mStringBuffer, str, 0);
                break;
            }
            case MTP_DEVICE_PROPERTY_BATTERY_LEVEL:
            {
                result = new MtpProperty(property, MTP_TYPE_UINT8);
                jlong* arr = env->GetLongArrayElements(mLongBuffer, 0);
                result->setFormRange(0, arr[1], 1);
                result->mCurrentValue.u.u8 = (uint8_t) arr[0];
                env->ReleaseLongArrayElements(mLongBuffer, arr, 0);
                break;
            }
            case MTP_DEVICE_PROPERTY_PERCEIVED_DEVICE_TYPE:
            {
                jlong* arr = env->GetLongArrayElements(mLongBuffer, 0);
                result = new MtpProperty(property, MTP_TYPE_UINT32);
                result->mCurrentValue.u.u32 = (uint32_t) arr[0];
                env->ReleaseLongArrayElements(mLongBuffer, arr, 0);
                break;
            }
            default:
                ALOGE("Unrecognized property %x", property);
        }
    } else {
        ALOGE("unable to read device property, response: %04X", ret);
    }

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

// ----------------------------------------------------------------------------

static void
android_mtp_MtpDatabase_setup(JNIEnv *env, jobject thiz)
{
    MtpDatabase* database = new MtpDatabase(env, thiz);
    env->SetLongField(thiz, field_context, (jlong)database);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

static void
android_mtp_MtpDatabase_finalize(JNIEnv *env, jobject thiz)
{
    MtpDatabase* database = (MtpDatabase *)env->GetLongField(thiz, field_context);
    database->cleanup(env);
    delete database;
    env->SetLongField(thiz, field_context, 0);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

static jstring
android_mtp_MtpPropertyGroup_format_date_time(JNIEnv *env, jobject /*thiz*/, jlong seconds)
{
    char    date[20];
    formatDateTime(seconds, date, sizeof(date));
    return env->NewStringUTF(date);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gMtpDatabaseMethods[] = {
    {"native_setup",            "()V",  (void *)android_mtp_MtpDatabase_setup},
    {"native_finalize",         "()V",  (void *)android_mtp_MtpDatabase_finalize},
};

static const JNINativeMethod gMtpPropertyGroupMethods[] = {
    {"format_date_time",        "(J)Ljava/lang/String;",
                                        (void *)android_mtp_MtpPropertyGroup_format_date_time},
};

#define GET_METHOD_ID(name, jclass, signature)                              \
    method_##name = env->GetMethodID(jclass, #name, signature);             \
    if (method_##name == NULL) {                                            \
        ALOGE("Can't find " #name);                                         \
        return -1;                                                          \
    }                                                                       \

int register_android_mtp_MtpDatabase(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/mtp/MtpDatabase");
    if (clazz == NULL) {
        ALOGE("Can't find android/mtp/MtpDatabase");
        return -1;
    }
    GET_METHOD_ID(beginSendObject, clazz, "(Ljava/lang/String;III)I");
    GET_METHOD_ID(endSendObject, clazz, "(IZ)V");
    GET_METHOD_ID(rescanFile, clazz, "(Ljava/lang/String;II)V");
    GET_METHOD_ID(getObjectList, clazz, "(III)[I");
    GET_METHOD_ID(getNumObjects, clazz, "(III)I");
    GET_METHOD_ID(getSupportedPlaybackFormats, clazz, "()[I");
    GET_METHOD_ID(getSupportedCaptureFormats, clazz, "()[I");
    GET_METHOD_ID(getSupportedObjectProperties, clazz, "(I)[I");
    GET_METHOD_ID(getSupportedDeviceProperties, clazz, "()[I");
    GET_METHOD_ID(setObjectProperty, clazz, "(IIJLjava/lang/String;)I");
    GET_METHOD_ID(getDeviceProperty, clazz, "(I[J[C)I");
    GET_METHOD_ID(setDeviceProperty, clazz, "(IJLjava/lang/String;)I");
    GET_METHOD_ID(getObjectPropertyList, clazz, "(IIIII)Landroid/mtp/MtpPropertyList;");
    GET_METHOD_ID(getObjectInfo, clazz, "(I[I[C[J)Z");
    GET_METHOD_ID(getObjectFilePath, clazz, "(I[C[J)I");
    GET_METHOD_ID(beginDeleteObject, clazz, "(I)I");
    GET_METHOD_ID(endDeleteObject, clazz, "(IZ)V");
    GET_METHOD_ID(beginMoveObject, clazz, "(III)I");
    GET_METHOD_ID(endMoveObject, clazz, "(IIIIIZ)V");
    GET_METHOD_ID(beginCopyObject, clazz, "(III)I");
    GET_METHOD_ID(endCopyObject, clazz, "(IZ)V");
    GET_METHOD_ID(getObjectReferences, clazz, "(I)[I");
    GET_METHOD_ID(setObjectReferences, clazz, "(I[I)I");

    field_context = env->GetFieldID(clazz, "mNativeContext", "J");
    if (field_context == NULL) {
        ALOGE("Can't find MtpDatabase.mNativeContext");
        return -1;
    }

    clazz = env->FindClass("android/mtp/MtpPropertyList");
    if (clazz == NULL) {
        ALOGE("Can't find android/mtp/MtpPropertyList");
        return -1;
    }
    GET_METHOD_ID(getCode, clazz, "()I");
    GET_METHOD_ID(getCount, clazz, "()I");
    GET_METHOD_ID(getObjectHandles, clazz, "()[I");
    GET_METHOD_ID(getPropertyCodes, clazz, "()[I");
    GET_METHOD_ID(getDataTypes, clazz, "()[I");
    GET_METHOD_ID(getLongValues, clazz, "()[J");
    GET_METHOD_ID(getStringValues, clazz, "()[Ljava/lang/String;");

    if (AndroidRuntime::registerNativeMethods(env,
                "android/mtp/MtpDatabase", gMtpDatabaseMethods, NELEM(gMtpDatabaseMethods)))
        return -1;

    return AndroidRuntime::registerNativeMethods(env,
                "android/mtp/MtpPropertyGroup", gMtpPropertyGroupMethods, NELEM(gMtpPropertyGroupMethods));
}

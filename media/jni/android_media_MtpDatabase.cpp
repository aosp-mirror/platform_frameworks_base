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

#include <stdio.h>
#include <assert.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include "MtpDatabase.h"
#include "MtpDataPacket.h"
#include "MtpProperty.h"
#include "MtpUtils.h"
#include "mtp.h"

using namespace android;

// ----------------------------------------------------------------------------

static jmethodID method_beginSendObject;
static jmethodID method_endSendObject;
static jmethodID method_getObjectList;
static jmethodID method_getNumObjects;
static jmethodID method_getSupportedPlaybackFormats;
static jmethodID method_getSupportedCaptureFormats;
static jmethodID method_getSupportedObjectProperties;
static jmethodID method_getSupportedDeviceProperties;
static jmethodID method_getObjectProperty;
static jmethodID method_setObjectProperty;
static jmethodID method_getObjectInfo;
static jmethodID method_getObjectFilePath;
static jmethodID method_deleteFile;
static jmethodID method_getObjectReferences;
static jmethodID method_setObjectReferences;
static jfieldID field_context;

MtpDatabase* getMtpDatabase(JNIEnv *env, jobject database) {
    return (MtpDatabase *)env->GetIntField(database, field_context);
}

#ifdef HAVE_ANDROID_OS
// ----------------------------------------------------------------------------

class MyMtpDatabase : public MtpDatabase {
private:
    jobject         mDatabase;
    jintArray       mIntBuffer;
    jlongArray      mLongBuffer;
    jcharArray      mStringBuffer;

public:
                                    MyMtpDatabase(JNIEnv *env, jobject client);
    virtual                         ~MyMtpDatabase();
    void                            cleanup(JNIEnv *env);

    virtual MtpObjectHandle         beginSendObject(const char* path,
                                            MtpObjectFormat format,
                                            MtpObjectHandle parent,
                                            MtpStorageID storage,
                                            uint64_t size,
                                            time_t modified);

    virtual void                    endSendObject(const char* path,
                                            MtpObjectHandle handle,
                                            MtpObjectFormat format,
                                            bool succeeded);

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

    virtual MtpResponseCode         getObjectInfo(MtpObjectHandle handle,
                                            MtpDataPacket& packet);

    virtual MtpResponseCode         getObjectFilePath(MtpObjectHandle handle,
                                            MtpString& filePath,
                                            int64_t& fileLength);
    virtual MtpResponseCode         deleteFile(MtpObjectHandle handle);

    bool                            getPropertyInfo(MtpObjectProperty property, int& type);

    virtual MtpObjectHandleList*    getObjectReferences(MtpObjectHandle handle);

    virtual MtpResponseCode         setObjectReferences(MtpObjectHandle handle,
                                            MtpObjectHandleList* references);

    virtual MtpProperty*            getObjectPropertyDesc(MtpObjectProperty property,
                                            MtpObjectFormat format);

    virtual MtpProperty*            getDevicePropertyDesc(MtpDeviceProperty property);
};

// ----------------------------------------------------------------------------

static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        LOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

// ----------------------------------------------------------------------------

MyMtpDatabase::MyMtpDatabase(JNIEnv *env, jobject client)
    :   mDatabase(env->NewGlobalRef(client)),
        mIntBuffer(NULL),
        mLongBuffer(NULL),
        mStringBuffer(NULL)
{
    jintArray intArray;
    jlongArray longArray;
    jcharArray charArray;

    // create buffers for out arguments
    // we don't need to be thread-safe so this is OK
    intArray = env->NewIntArray(3);
    if (!intArray)
        goto out_of_memory;
    mIntBuffer = (jintArray)env->NewGlobalRef(intArray);
    longArray = env->NewLongArray(2);
    if (!longArray)
        goto out_of_memory;
    mLongBuffer = (jlongArray)env->NewGlobalRef(longArray);
    charArray = env->NewCharArray(256);
    if (!charArray)
        goto out_of_memory;
    mStringBuffer = (jcharArray)env->NewGlobalRef(charArray);
    return;

out_of_memory:
    env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), NULL);
}

void MyMtpDatabase::cleanup(JNIEnv *env) {
    env->DeleteGlobalRef(mDatabase);
    env->DeleteGlobalRef(mIntBuffer);
    env->DeleteGlobalRef(mLongBuffer);
    env->DeleteGlobalRef(mStringBuffer);
}

MyMtpDatabase::~MyMtpDatabase() {
}

MtpObjectHandle MyMtpDatabase::beginSendObject(const char* path,
                                            MtpObjectFormat format,
                                            MtpObjectHandle parent,
                                            MtpStorageID storage,
                                            uint64_t size,
                                            time_t modified) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    MtpObjectHandle result = env->CallIntMethod(mDatabase, method_beginSendObject,
            env->NewStringUTF(path), (jint)format, (jint)parent, (jint)storage,
            (jlong)size, (jlong)modified);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

void MyMtpDatabase::endSendObject(const char* path, MtpObjectHandle handle,
                                MtpObjectFormat format, bool succeeded) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mDatabase, method_endSendObject, env->NewStringUTF(path),
                        (jint)handle, (jint)format, (jboolean)succeeded);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
}

MtpObjectHandleList* MyMtpDatabase::getObjectList(MtpStorageID storageID,
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
        list->push(handles[i]);
    env->ReleaseIntArrayElements(array, handles, 0);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return list;
}

int MyMtpDatabase::getNumObjects(MtpStorageID storageID,
                                MtpObjectFormat format,
                                MtpObjectHandle parent) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    int result = env->CallIntMethod(mDatabase, method_getNumObjects,
                (jint)storageID, (jint)format, (jint)parent);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

MtpObjectFormatList* MyMtpDatabase::getSupportedPlaybackFormats() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jintArray array = (jintArray)env->CallObjectMethod(mDatabase,
            method_getSupportedPlaybackFormats);
    if (!array)
        return NULL;
    MtpObjectFormatList* list = new MtpObjectFormatList();
    jint* formats = env->GetIntArrayElements(array, 0);
    jsize length = env->GetArrayLength(array);
    for (int i = 0; i < length; i++)
        list->push(formats[i]);
    env->ReleaseIntArrayElements(array, formats, 0);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return list;
}

MtpObjectFormatList* MyMtpDatabase::getSupportedCaptureFormats() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jintArray array = (jintArray)env->CallObjectMethod(mDatabase,
            method_getSupportedCaptureFormats);
    if (!array)
        return NULL;
    MtpObjectFormatList* list = new MtpObjectFormatList();
    jint* formats = env->GetIntArrayElements(array, 0);
    jsize length = env->GetArrayLength(array);
    for (int i = 0; i < length; i++)
        list->push(formats[i]);
    env->ReleaseIntArrayElements(array, formats, 0);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return list;
}

MtpObjectPropertyList* MyMtpDatabase::getSupportedObjectProperties(MtpObjectFormat format) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jintArray array = (jintArray)env->CallObjectMethod(mDatabase,
            method_getSupportedObjectProperties, (jint)format);
    if (!array)
        return NULL;
    MtpObjectPropertyList* list = new MtpObjectPropertyList();
    jint* properties = env->GetIntArrayElements(array, 0);
    jsize length = env->GetArrayLength(array);
    for (int i = 0; i < length; i++)
        list->push(properties[i]);
    env->ReleaseIntArrayElements(array, properties, 0);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return list;
}

MtpDevicePropertyList* MyMtpDatabase::getSupportedDeviceProperties() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jintArray array = (jintArray)env->CallObjectMethod(mDatabase,
            method_getSupportedDeviceProperties);
    if (!array)
        return NULL;
    MtpDevicePropertyList* list = new MtpDevicePropertyList();
    jint* properties = env->GetIntArrayElements(array, 0);
    jsize length = env->GetArrayLength(array);
    for (int i = 0; i < length; i++)
        list->push(properties[i]);
    env->ReleaseIntArrayElements(array, properties, 0);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return list;
}

MtpResponseCode MyMtpDatabase::getObjectPropertyValue(MtpObjectHandle handle,
                                            MtpObjectProperty property,
                                            MtpDataPacket& packet) {
    int         type;

    if (!getPropertyInfo(property, type))
        return MTP_RESPONSE_INVALID_OBJECT_PROP_CODE;

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jint result = env->CallIntMethod(mDatabase, method_getObjectProperty,
                (jint)handle, (jint)property, mLongBuffer, mStringBuffer);
    if (result != MTP_RESPONSE_OK)
        return result;

    jlong* longValues = env->GetLongArrayElements(mLongBuffer, 0);
    jlong longValue = longValues[0];
    env->ReleaseLongArrayElements(mLongBuffer, longValues, 0);

    // special case MTP_PROPERTY_DATE_MODIFIED, which is a string to MTP
    // but stored internally as a uint64
    if (property == MTP_PROPERTY_DATE_MODIFIED) {
        char    date[20];
        formatDateTime(longValue, date, sizeof(date));
        packet.putString(date);
        return MTP_RESPONSE_OK;
    }

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
            LOGE("unsupported object type\n");
            return MTP_RESPONSE_INVALID_OBJECT_HANDLE;
    }

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return MTP_RESPONSE_OK;
}

MtpResponseCode MyMtpDatabase::setObjectPropertyValue(MtpObjectHandle handle,
                                            MtpObjectProperty property,
                                            MtpDataPacket& packet) {
    return -1;
}

MtpResponseCode MyMtpDatabase::getDevicePropertyValue(MtpDeviceProperty property,
                                            MtpDataPacket& packet) {
    return -1;
}

MtpResponseCode MyMtpDatabase::setDevicePropertyValue(MtpDeviceProperty property,
                                            MtpDataPacket& packet) {
    return -1;
}

MtpResponseCode MyMtpDatabase::resetDeviceProperty(MtpDeviceProperty property) {
    return -1;
}

MtpResponseCode MyMtpDatabase::getObjectInfo(MtpObjectHandle handle,
                                            MtpDataPacket& packet) {
    char    date[20];

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jboolean result = env->CallBooleanMethod(mDatabase, method_getObjectInfo,
                (jint)handle, mIntBuffer, mStringBuffer, mLongBuffer);
    if (!result)
        return MTP_RESPONSE_INVALID_OBJECT_HANDLE;

    jint* intValues = env->GetIntArrayElements(mIntBuffer, 0);
    MtpStorageID storageID = intValues[0];
    MtpObjectFormat format = intValues[1];
    MtpObjectHandle parent = intValues[2];
    env->ReleaseIntArrayElements(mIntBuffer, intValues, 0);

    jlong* longValues = env->GetLongArrayElements(mLongBuffer, 0);
    uint64_t size = longValues[0];
    uint64_t modified = longValues[1];
    env->ReleaseLongArrayElements(mLongBuffer, longValues, 0);

//    int associationType = (format == MTP_FORMAT_ASSOCIATION ?
//                            MTP_ASSOCIATION_TYPE_GENERIC_FOLDER :
//                            MTP_ASSOCIATION_TYPE_UNDEFINED);
    int associationType = MTP_ASSOCIATION_TYPE_UNDEFINED;

    packet.putUInt32(storageID);
    packet.putUInt16(format);
    packet.putUInt16(0);   // protection status
    packet.putUInt32((size > 0xFFFFFFFFLL ? 0xFFFFFFFF : size));
    packet.putUInt16(0);   // thumb format
    packet.putUInt32(0);   // thumb compressed size
    packet.putUInt32(0);   // thumb pix width
    packet.putUInt32(0);   // thumb pix height
    packet.putUInt32(0);   // image pix width
    packet.putUInt32(0);   // image pix height
    packet.putUInt32(0);   // image bit depth
    packet.putUInt32(parent);
    packet.putUInt16(associationType);
    packet.putUInt32(0);   // association desc
    packet.putUInt32(0);   // sequence number

    jchar* str = env->GetCharArrayElements(mStringBuffer, 0);
    packet.putString(str);   // file name
    env->ReleaseCharArrayElements(mStringBuffer, str, 0);

    packet.putEmptyString();
    formatDateTime(modified, date, sizeof(date));
    packet.putString(date);   // date modified
    packet.putEmptyString();   // keywords

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return MTP_RESPONSE_OK;
}

MtpResponseCode MyMtpDatabase::getObjectFilePath(MtpObjectHandle handle,
                                            MtpString& filePath,
                                            int64_t& fileLength) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jint result = env->CallIntMethod(mDatabase, method_getObjectFilePath,
                (jint)handle, mStringBuffer, mLongBuffer);
    if (result != MTP_RESPONSE_OK)
        return result;

    jchar* str = env->GetCharArrayElements(mStringBuffer, 0);
    filePath.setTo(str, strlen16(str));
    env->ReleaseCharArrayElements(mStringBuffer, str, 0);

    jlong* longValues = env->GetLongArrayElements(mLongBuffer, 0);
    fileLength = longValues[0];
    env->ReleaseLongArrayElements(mLongBuffer, longValues, 0);
    
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

MtpResponseCode MyMtpDatabase::deleteFile(MtpObjectHandle handle) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    MtpResponseCode result = env->CallIntMethod(mDatabase, method_deleteFile, (jint)handle);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

struct PropertyTableEntry {
    MtpObjectProperty   property;
    int                 type;
};

static const PropertyTableEntry   kPropertyTable[] = {
    {   MTP_PROPERTY_PARENT_OBJECT,     MTP_TYPE_UINT32 },
    {   MTP_PROPERTY_STORAGE_ID,        MTP_TYPE_UINT32 },
    {   MTP_PROPERTY_OBJECT_FORMAT,     MTP_TYPE_UINT16 },
    {   MTP_PROPERTY_OBJECT_FILE_NAME,  MTP_TYPE_STR    },
    {   MTP_PROPERTY_OBJECT_SIZE,       MTP_TYPE_UINT64 },
    {   MTP_PROPERTY_DATE_MODIFIED,     MTP_TYPE_STR    },
};

bool MyMtpDatabase::getPropertyInfo(MtpObjectProperty property, int& type) {
    int count = sizeof(kPropertyTable) / sizeof(kPropertyTable[0]);
    const PropertyTableEntry* entry = kPropertyTable;
    for (int i = 0; i < count; i++, entry++) {
        if (entry->property == property) {
            type = entry->type;
            return true;
        }
    }
    return false;
}

MtpObjectHandleList* MyMtpDatabase::getObjectReferences(MtpObjectHandle handle) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jintArray array = (jintArray)env->CallObjectMethod(mDatabase, method_getObjectReferences,
                (jint)handle);
    if (!array)
        return NULL;
    MtpObjectHandleList* list = new MtpObjectHandleList();
    jint* handles = env->GetIntArrayElements(array, 0);
    jsize length = env->GetArrayLength(array);
    for (int i = 0; i < length; i++)
        list->push(handles[i]);
    env->ReleaseIntArrayElements(array, handles, 0);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return list;
}

MtpResponseCode MyMtpDatabase::setObjectReferences(MtpObjectHandle handle,
                                                    MtpObjectHandleList* references) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    int count = references->size();
    jintArray array = env->NewIntArray(count);
    if (!array) {
        LOGE("out of memory in setObjectReferences");
        return false;
    }
    jint* handles = env->GetIntArrayElements(array, 0);
     for (int i = 0; i < count; i++)
        handles[i] = (*references)[i];
    env->ReleaseIntArrayElements(array, handles, 0);
    MtpResponseCode result = env->CallIntMethod(mDatabase, method_setObjectReferences,
                (jint)handle, array);

    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return result;
}

MtpProperty* MyMtpDatabase::getObjectPropertyDesc(MtpObjectProperty property,
                                            MtpObjectFormat format) {
    MtpProperty* result = NULL;
    switch (property) {
        case MTP_PROPERTY_OBJECT_FORMAT:
        case MTP_PROPERTY_PROTECTION_STATUS:
            result = new MtpProperty(property, MTP_TYPE_UINT16);
            break;
        case MTP_PROPERTY_STORAGE_ID:
        case MTP_PROPERTY_PARENT_OBJECT:
            result = new MtpProperty(property, MTP_TYPE_UINT32);
            break;
        case MTP_PROPERTY_OBJECT_SIZE:
            result = new MtpProperty(property, MTP_TYPE_UINT64);
            break;
        case MTP_PROPERTY_PERSISTENT_UID:
            result = new MtpProperty(property, MTP_TYPE_UINT128);
            break;
        case MTP_PROPERTY_OBJECT_FILE_NAME:
        case MTP_PROPERTY_DATE_MODIFIED:
            result = new MtpProperty(property, MTP_TYPE_STR);
            break;
    }

    return result;
}

MtpProperty* MyMtpDatabase::getDevicePropertyDesc(MtpDeviceProperty property) {
    return NULL;
}

#endif // HAVE_ANDROID_OS

// ----------------------------------------------------------------------------

static void
android_media_MtpDatabase_setup(JNIEnv *env, jobject thiz)
{
#ifdef HAVE_ANDROID_OS
    LOGD("setup\n");
    MyMtpDatabase* database = new MyMtpDatabase(env, thiz);
    env->SetIntField(thiz, field_context, (int)database);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
#endif
}

static void
android_media_MtpDatabase_finalize(JNIEnv *env, jobject thiz)
{
#ifdef HAVE_ANDROID_OS
    LOGD("finalize\n");
    MyMtpDatabase* database = (MyMtpDatabase *)env->GetIntField(thiz, field_context);
    database->cleanup(env);
    delete database;
    env->SetIntField(thiz, field_context, 0);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
#endif
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"native_setup",            "()V",  (void *)android_media_MtpDatabase_setup},
    {"native_finalize",         "()V",  (void *)android_media_MtpDatabase_finalize},
};

static const char* const kClassPathName = "android/media/MtpDatabase";

int register_android_media_MtpDatabase(JNIEnv *env)
{
    jclass clazz;

    LOGD("register_android_media_MtpDatabase\n");

    clazz = env->FindClass("android/media/MtpDatabase");
    if (clazz == NULL) {
        LOGE("Can't find android/media/MtpDatabase");
        return -1;
    }
    method_beginSendObject = env->GetMethodID(clazz, "beginSendObject", "(Ljava/lang/String;IIIJJ)I");
    if (method_beginSendObject == NULL) {
        LOGE("Can't find beginSendObject");
        return -1;
    }
    method_endSendObject = env->GetMethodID(clazz, "endSendObject", "(Ljava/lang/String;IIZ)V");
    if (method_endSendObject == NULL) {
        LOGE("Can't find endSendObject");
        return -1;
    }
    method_getObjectList = env->GetMethodID(clazz, "getObjectList", "(III)[I");
    if (method_getObjectList == NULL) {
        LOGE("Can't find getObjectList");
        return -1;
    }
    method_getNumObjects = env->GetMethodID(clazz, "getNumObjects", "(III)I");
    if (method_getNumObjects == NULL) {
        LOGE("Can't find getNumObjects");
        return -1;
    }
    method_getSupportedPlaybackFormats = env->GetMethodID(clazz, "getSupportedPlaybackFormats", "()[I");
    if (method_getSupportedPlaybackFormats == NULL) {
        LOGE("Can't find getSupportedPlaybackFormats");
        return -1;
    }
    method_getSupportedCaptureFormats = env->GetMethodID(clazz, "getSupportedCaptureFormats", "()[I");
    if (method_getSupportedCaptureFormats == NULL) {
        LOGE("Can't find getSupportedCaptureFormats");
        return -1;
    }
    method_getSupportedObjectProperties = env->GetMethodID(clazz, "getSupportedObjectProperties", "(I)[I");
    if (method_getSupportedObjectProperties == NULL) {
        LOGE("Can't find getSupportedObjectProperties");
        return -1;
    }
    method_getSupportedDeviceProperties = env->GetMethodID(clazz, "getSupportedDeviceProperties", "()[I");
    if (method_getSupportedDeviceProperties == NULL) {
        LOGE("Can't find getSupportedDeviceProperties");
        return -1;
    }
    method_getObjectProperty = env->GetMethodID(clazz, "getObjectProperty", "(II[J[C)I");
    if (method_getObjectProperty == NULL) {
        LOGE("Can't find getObjectProperty");
        return -1;
    }
    method_getObjectInfo = env->GetMethodID(clazz, "getObjectInfo", "(I[I[C[J)Z");
    if (method_getObjectInfo == NULL) {
        LOGE("Can't find getObjectInfo");
        return -1;
    }
    method_getObjectFilePath = env->GetMethodID(clazz, "getObjectFilePath", "(I[C[J)I");
    if (method_getObjectFilePath == NULL) {
        LOGE("Can't find getObjectFilePath");
        return -1;
    }
    method_deleteFile = env->GetMethodID(clazz, "deleteFile", "(I)I");
    if (method_deleteFile == NULL) {
        LOGE("Can't find deleteFile");
        return -1;
    }
    method_getObjectReferences = env->GetMethodID(clazz, "getObjectReferences", "(I)[I");
    if (method_getObjectReferences == NULL) {
        LOGE("Can't find getObjectReferences");
        return -1;
    }
    method_setObjectReferences = env->GetMethodID(clazz, "setObjectReferences", "(I[I)I");
    if (method_setObjectReferences == NULL) {
        LOGE("Can't find setObjectReferences");
        return -1;
    }
    field_context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (field_context == NULL) {
        LOGE("Can't find MtpDatabase.mNativeContext");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MtpDatabase", gMethods, NELEM(gMethods));
}

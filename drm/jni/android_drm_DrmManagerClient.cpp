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

//#define LOG_NDEBUG 0
#define LOG_TAG "android_drm_DrmManagerClient"
#include <utils/Log.h>

#include <jni.h>
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <drm/DrmInfo.h>
#include <drm/DrmRights.h>
#include <drm/DrmInfoEvent.h>
#include <drm/DrmInfoStatus.h>
#include <drm/DrmInfoRequest.h>
#include <drm/DrmSupportInfo.h>
#include <drm/DrmConstraints.h>
#include <drm/DrmMetadata.h>
#include <drm/DrmConvertedStatus.h>
#include <drm/drm_framework_common.h>

#include <DrmManagerClientImpl.h>

using namespace android;

/**
 * Utility class used to extract the value from the provided java object.
 * May need to add some utility function to create java object.
 */
class Utility {
public:
    static String8 getStringValue(JNIEnv* env, jobject object, const char* fieldName);

    static char* getByteArrayValue(
            JNIEnv* env, jobject object, const char* fieldName, int* dataLength);

    static char* getByteArrayValue(
            JNIEnv* env, jbyteArray byteArray, int* dataLength);

    static String8 getStringValue(JNIEnv* env, jstring string);

    static int getIntValue(JNIEnv* env, jobject object, const char* fieldName);
};

String8 Utility::getStringValue(JNIEnv* env, jobject object, const char* fieldName) {
    String8 dataString("");

    /* Look for the instance field with the name fieldName */
    jfieldID fieldID
        = env->GetFieldID(env->GetObjectClass(object), fieldName , "Ljava/lang/String;");

    if (NULL != fieldID) {
        jstring valueString = (jstring) env->GetObjectField(object, fieldID);

        if (NULL != valueString && valueString != env->NewStringUTF("")) {
            char* bytes = const_cast< char* > (env->GetStringUTFChars(valueString, NULL));

            const int length = strlen(bytes) + 1;
            char *data = new char[length];
            strncpy(data, bytes, length);
            dataString = String8(data);

            env->ReleaseStringUTFChars(valueString, bytes);
            delete [] data; data = NULL;
        } else {
            ALOGV("Failed to retrieve the data from the field %s", fieldName);
        }
    }
    return dataString;
}

String8 Utility::getStringValue(JNIEnv* env, jstring string) {
    String8 dataString("");

    if (NULL != string && string != env->NewStringUTF("")) {
        char* bytes = const_cast< char* > (env->GetStringUTFChars(string, NULL));

        const int length = strlen(bytes) + 1;
        char *data = new char[length];
        strncpy(data, bytes, length);
        dataString = String8(data);

        env->ReleaseStringUTFChars(string, bytes);
        delete [] data; data = NULL;
    }
    return dataString;
}

char* Utility::getByteArrayValue(
            JNIEnv* env, jobject object, const char* fieldName, int* dataLength) {
    char* data = NULL;
    *dataLength = 0;

    jfieldID fieldID = env->GetFieldID(env->GetObjectClass(object), fieldName , "[B");

    if (NULL != fieldID) {
        jbyteArray byteArray = (jbyteArray) env->GetObjectField(object, fieldID);
        if (NULL != byteArray) {
            jint length = env->GetArrayLength(byteArray);

            *dataLength = length;
            if (0 < *dataLength) {
                data = new char[length];
                env->GetByteArrayRegion(byteArray, (jint)0, length, (jbyte *) data);
            }
        }
    }
    return data;
}

char* Utility::getByteArrayValue(JNIEnv* env, jbyteArray byteArray, int* dataLength) {
    char* data = NULL;
    if (NULL != byteArray) {
        jint length = env->GetArrayLength(byteArray);

        *dataLength = length;
        if (0 < *dataLength) {
            data = new char[length];
            env->GetByteArrayRegion(byteArray, (jint)0, length, (jbyte *) data);
        }
    }
    return data;
}

int Utility::getIntValue(JNIEnv* env, jobject object, const char* fieldName) {
    jfieldID fieldID;
    int intValue = -1;

    /* Get a reference to obj’s class */
    jclass clazz = env->GetObjectClass(object);
    /* Look for the instance field with the name fieldName */
    fieldID = env->GetFieldID(clazz, fieldName , "I");

    if (NULL != fieldID) {
        intValue = (int) env->GetIntField(object, fieldID);
    }

    return intValue;
}

class JNIOnInfoListener : public DrmManagerClient::OnInfoListener {
public:
    JNIOnInfoListener(JNIEnv* env, jobject thiz, jobject weak_thiz);

    virtual ~JNIOnInfoListener();
    void onInfo(const DrmInfoEvent& event);

private:
    JNIOnInfoListener();
    jclass mClass;
    jobject mObject;
};

JNIOnInfoListener::JNIOnInfoListener(JNIEnv* env, jobject thiz, jobject weak_thiz) {
    jclass clazz = env->GetObjectClass(thiz);

    if (clazz == NULL) {
        ALOGE("Can't find android/drm/DrmManagerClient");
        jniThrowException(env, "java/lang/Exception", NULL);
        return;
    }
    mClass = (jclass)env->NewGlobalRef(clazz);
    mObject  = env->NewGlobalRef(weak_thiz);
}

JNIOnInfoListener::~JNIOnInfoListener() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mObject);
    env->DeleteGlobalRef(mClass);
}

void JNIOnInfoListener::onInfo(const DrmInfoEvent& event) {
    jint uniqueId = event.getUniqueId();
    jint type = event.getType();
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jstring message = env->NewStringUTF(event.getMessage().string());
    ALOGV("JNIOnInfoListener::onInfo => %d | %d | %s", uniqueId, type, event.getMessage().string());

    env->CallStaticVoidMethod(
            mClass,
            env->GetStaticMethodID(mClass, "notify", "(Ljava/lang/Object;IILjava/lang/String;)V"),
            mObject, uniqueId, type, message);
}

static Mutex sLock;

static sp<DrmManagerClientImpl> setDrmManagerClientImpl(
            JNIEnv* env, jobject thiz, const sp<DrmManagerClientImpl>& client) {
    Mutex::Autolock l(sLock);
    jclass clazz = env->FindClass("android/drm/DrmManagerClient");
    jfieldID fieldId = env->GetFieldID(clazz, "mNativeContext", "I");

    sp<DrmManagerClientImpl> old = (DrmManagerClientImpl*)env->GetIntField(thiz, fieldId);
    if (client.get()) {
        client->incStrong(thiz);
    }
    if (old != 0) {
        old->decStrong(thiz);
    }
    env->SetIntField(thiz, fieldId, (int)client.get());
    return old;
}

static sp<DrmManagerClientImpl> getDrmManagerClientImpl(JNIEnv* env, jobject thiz) {
    Mutex::Autolock l(sLock);
    jclass clazz = env->FindClass("android/drm/DrmManagerClient");
    jfieldID fieldId = env->GetFieldID(clazz, "mNativeContext", "I");

    DrmManagerClientImpl* const client = (DrmManagerClientImpl*)env->GetIntField(thiz, fieldId);
    return sp<DrmManagerClientImpl>(client);
}

static jint android_drm_DrmManagerClient_initialize(
        JNIEnv* env, jobject thiz, jobject weak_thiz) {
    ALOGV("initialize - Enter");

    int uniqueId = 0;
    sp<DrmManagerClientImpl> drmManager = DrmManagerClientImpl::create(&uniqueId, false);
    drmManager->addClient(uniqueId);

    // Set the listener to DrmManager
    sp<DrmManagerClient::OnInfoListener> listener = new JNIOnInfoListener(env, thiz, weak_thiz);
    drmManager->setOnInfoListener(uniqueId, listener);

    setDrmManagerClientImpl(env, thiz, drmManager);
    ALOGV("initialize - Exit");

    return uniqueId;
}

static void android_drm_DrmManagerClient_finalize(JNIEnv* env, jobject thiz, jint uniqueId) {
    ALOGV("finalize - Enter");
    DrmManagerClientImpl::remove(uniqueId);
    getDrmManagerClientImpl(env, thiz)->setOnInfoListener(uniqueId, NULL);

    sp<DrmManagerClientImpl> oldClient = setDrmManagerClientImpl(env, thiz, NULL);
    if (oldClient != NULL) {
        oldClient->setOnInfoListener(uniqueId, NULL);
        oldClient->removeClient(uniqueId);
    }
    ALOGV("finalize - Exit");
}

static jobject android_drm_DrmManagerClient_getConstraintsFromContent(
            JNIEnv* env, jobject thiz, jint uniqueId, jstring jpath, jint usage) {
    ALOGV("GetConstraints - Enter");

    const String8 pathString = Utility::getStringValue(env, jpath);
    DrmConstraints* pConstraints
        = getDrmManagerClientImpl(env, thiz)->getConstraints(uniqueId, &pathString, usage);

    jclass localRef = env->FindClass("android/content/ContentValues");
    jobject constraints = NULL;

    if (NULL != localRef && NULL != pConstraints) {
        // Get the constructor id
        jmethodID constructorId = env->GetMethodID(localRef, "<init>", "()V");
        // create the java DrmConstraints object
        constraints = env->NewObject(localRef, constructorId);

        DrmConstraints::KeyIterator keyIt = pConstraints->keyIterator();

        while (keyIt.hasNext()) {
            String8 key = keyIt.next();

            // insert the entry<constraintKey, constraintValue> to newly created java object
            if (DrmConstraints::EXTENDED_METADATA == key) {
                const char* value = pConstraints->getAsByteArray(&key);
                if (NULL != value) {
                    jbyteArray dataArray = env->NewByteArray(strlen(value));
                    env->SetByteArrayRegion(dataArray, 0, strlen(value), (jbyte*)value);
                    env->CallVoidMethod(
                        constraints, env->GetMethodID(localRef, "put", "(Ljava/lang/String;[B)V"),
                                     env->NewStringUTF(key.string()), dataArray);
                }
            } else {
                String8 value = pConstraints->get(key);
                env->CallVoidMethod(
                    constraints,
                    env->GetMethodID(localRef, "put", "(Ljava/lang/String;Ljava/lang/String;)V"),
                env->NewStringUTF(key.string()), env->NewStringUTF(value.string()));
            }
        }
    }

    delete pConstraints; pConstraints = NULL;
    ALOGV("GetConstraints - Exit");
    return constraints;
}

static jobject android_drm_DrmManagerClient_getMetadataFromContent(
            JNIEnv* env, jobject thiz, jint uniqueId, jstring jpath) {
    ALOGV("GetMetadata - Enter");
    const String8 pathString = Utility::getStringValue(env, jpath);
    DrmMetadata* pMetadata =
            getDrmManagerClientImpl(env, thiz)->getMetadata(uniqueId, &pathString);

    jobject metadata = NULL;

    jclass localRef = NULL;
    localRef = env->FindClass("android/content/ContentValues");
    if (NULL != localRef && NULL != pMetadata) {
        // Get the constructor id
        jmethodID constructorId = NULL;
        constructorId = env->GetMethodID(localRef, "<init>", "()V");
        if (NULL != constructorId) {
            // create the java DrmMetadata object
            metadata = env->NewObject(localRef, constructorId);
            if (NULL != metadata) {
                DrmMetadata::KeyIterator keyIt = pMetadata->keyIterator();
                while (keyIt.hasNext()) {
                    String8 key = keyIt.next();
                    // insert the entry<constraintKey, constraintValue>
                    // to newly created java object
                    String8 value = pMetadata->get(key);
                    env->CallVoidMethod(metadata, env->GetMethodID(localRef, "put",
                            "(Ljava/lang/String;Ljava/lang/String;)V"),
                    env->NewStringUTF(key.string()), env->NewStringUTF(value.string()));
                }
            }
        }
    }
    delete pMetadata; pMetadata = NULL;
    ALOGV("GetMetadata - Exit");
    return metadata;
}

static jobjectArray android_drm_DrmManagerClient_getAllSupportInfo(
            JNIEnv* env, jobject thiz, jint uniqueId) {
    ALOGV("GetAllSupportInfo - Enter");
    DrmSupportInfo* drmSupportInfoArray = NULL;

    int length = 0;
    getDrmManagerClientImpl(env, thiz)->getAllSupportInfo(uniqueId, &length, &drmSupportInfoArray);

    jclass clazz = env->FindClass("android/drm/DrmSupportInfo");

    jobjectArray array = (jobjectArray)env->NewObjectArray(length, clazz, NULL);

    for (int i = 0; i < length; i++) {
        DrmSupportInfo info = drmSupportInfoArray[i];

        jobject drmSupportInfo = env->NewObject(clazz, env->GetMethodID(clazz, "<init>", "()V"));

        jmethodID addMimeTypeId
            = env->GetMethodID(clazz, "addMimeType", "(Ljava/lang/String;)V");
        jmethodID addFileSuffixId
            = env->GetMethodID(clazz, "addFileSuffix", "(Ljava/lang/String;)V");

        env->CallVoidMethod(
            drmSupportInfo, env->GetMethodID(clazz, "setDescription", "(Ljava/lang/String;)V"),
            env->NewStringUTF(info.getDescription().string()));

        DrmSupportInfo::MimeTypeIterator iterator = info.getMimeTypeIterator();
        while (iterator.hasNext()) {
            String8  value = iterator.next();
            env->CallVoidMethod(drmSupportInfo, addMimeTypeId, env->NewStringUTF(value.string()));
        }

        DrmSupportInfo::FileSuffixIterator it = info.getFileSuffixIterator();
        while (it.hasNext()) {
            String8 value = it.next();
            env->CallVoidMethod(
                drmSupportInfo, addFileSuffixId, env->NewStringUTF(value.string()));
        }

        env->SetObjectArrayElement(array, i, drmSupportInfo);
    }

    delete [] drmSupportInfoArray; drmSupportInfoArray = NULL;
    ALOGV("GetAllSupportInfo - Exit");
    return array;
}

static void android_drm_DrmManagerClient_installDrmEngine(
            JNIEnv* env, jobject thiz, jint uniqueId, jstring engineFilePath) {
    ALOGV("installDrmEngine - Enter");
    //getDrmManagerClient(env, thiz)
    //  ->installDrmEngine(uniqueId, Utility::getStringValue(env, engineFilePath));
    ALOGV("installDrmEngine - Exit");
}

static jint android_drm_DrmManagerClient_saveRights(
            JNIEnv* env, jobject thiz, jint uniqueId,
            jobject drmRights, jstring rightsPath, jstring contentPath) {
    ALOGV("saveRights - Enter");
    int result = DRM_ERROR_UNKNOWN;
    int dataLength = 0;
    char* mData =  Utility::getByteArrayValue(env, drmRights, "mData", &dataLength);

    if (NULL != mData) {
        DrmRights rights(DrmBuffer(mData, dataLength),
                Utility::getStringValue(env, drmRights, "mMimeType"),
                Utility::getStringValue(env, drmRights, "mAccountId"),
                Utility::getStringValue(env, drmRights, "mSubscriptionId"));
        result = getDrmManagerClientImpl(env, thiz)
            ->saveRights(uniqueId, rights, Utility::getStringValue(env, rightsPath),
                                Utility::getStringValue(env, contentPath));
    }

    delete mData; mData = NULL;
    ALOGV("saveRights - Exit");
    return result;
}

static jboolean android_drm_DrmManagerClient_canHandle(
            JNIEnv* env, jobject thiz, jint uniqueId, jstring path, jstring mimeType) {
    ALOGV("canHandle - Enter");
    jboolean result
        = getDrmManagerClientImpl(env, thiz)
            ->canHandle(uniqueId, Utility::getStringValue(env, path),
                    Utility::getStringValue(env, mimeType));
    ALOGV("canHandle - Exit");
    return result;
}

static jobject android_drm_DrmManagerClient_processDrmInfo(
            JNIEnv* env, jobject thiz, jint uniqueId, jobject drmInfoObject) {
    ALOGV("processDrmInfo - Enter");
    int dataLength = 0;
    const String8 mMimeType =  Utility::getStringValue(env, drmInfoObject, "mMimeType");
    char* mData =  Utility::getByteArrayValue(env, drmInfoObject, "mData", &dataLength);
    int mInfoType = Utility::getIntValue(env, drmInfoObject, "mInfoType");

    const DrmBuffer buffer(mData, dataLength);
    DrmInfo drmInfo(mInfoType, buffer, mMimeType);

    jclass clazz = env->FindClass("android/drm/DrmInfo");
    jobject keyIterator
        = env->CallObjectMethod(drmInfoObject,
                env->GetMethodID(clazz, "keyIterator", "()Ljava/util/Iterator;"));

    jmethodID hasNextId = env->GetMethodID(env->FindClass("java/util/Iterator"), "hasNext", "()Z");

    while (env->CallBooleanMethod(keyIterator, hasNextId)) {
        jstring key = (jstring) env->CallObjectMethod(keyIterator,
                env->GetMethodID(env->FindClass("java/util/Iterator"),
                "next", "()Ljava/lang/Object;"));

        jobject valueObject = env->CallObjectMethod(drmInfoObject,
                env->GetMethodID(clazz, "get", "(Ljava/lang/String;)Ljava/lang/Object;"), key);

        jstring valString = NULL;
        if (NULL != valueObject) {
            valString = (jstring) env->CallObjectMethod(valueObject,
                env->GetMethodID(env->FindClass("java/lang/Object"),
                "toString", "()Ljava/lang/String;"));
        }

        String8 keyString = Utility::getStringValue(env, key);
        String8 valueString = Utility::getStringValue(env, valString);
        ALOGV("Key: %s | Value: %s", keyString.string(), valueString.string());

        drmInfo.put(keyString, valueString);
    }

    DrmInfoStatus* pDrmInfoStatus
        = getDrmManagerClientImpl(env, thiz)->processDrmInfo(uniqueId, &drmInfo);

    jclass localRef = env->FindClass("android/drm/DrmInfoStatus");
    jobject drmInfoStatus = NULL;

    if (NULL != localRef && NULL != pDrmInfoStatus) {
        int statusCode = pDrmInfoStatus->statusCode;
        int infoType = pDrmInfoStatus->infoType;

        jbyteArray dataArray = NULL;
        if (NULL != pDrmInfoStatus->drmBuffer) {
            int length = pDrmInfoStatus->drmBuffer->length;
            dataArray = env->NewByteArray(length);
            env->SetByteArrayRegion(
                dataArray, 0, length, (jbyte*) pDrmInfoStatus->drmBuffer->data);

            delete [] pDrmInfoStatus->drmBuffer->data;
            delete pDrmInfoStatus->drmBuffer; pDrmInfoStatus->drmBuffer = NULL;
        }
        jclass clazz = env->FindClass("android/drm/ProcessedData");
        jmethodID constructorId
            = env->GetMethodID(clazz, "<init>", "([BLjava/lang/String;Ljava/lang/String;)V");
        jobject processedData = env->NewObject(clazz, constructorId, dataArray,
                    env->NewStringUTF((drmInfo.get(DrmInfoRequest::ACCOUNT_ID)).string()),
                    env->NewStringUTF((drmInfo.get(DrmInfoRequest::SUBSCRIPTION_ID)).string()));

        constructorId
            = env->GetMethodID(localRef,
                "<init>", "(IILandroid/drm/ProcessedData;Ljava/lang/String;)V");

        drmInfoStatus = env->NewObject(localRef, constructorId, statusCode, infoType,
                processedData, env->NewStringUTF(pDrmInfoStatus->mimeType.string()));
    }

    delete mData; mData = NULL;
    delete pDrmInfoStatus; pDrmInfoStatus = NULL;

    ALOGV("processDrmInfo - Exit");
    return drmInfoStatus;
}

static jobject android_drm_DrmManagerClient_acquireDrmInfo(
            JNIEnv* env, jobject thiz, jint uniqueId, jobject drmInfoRequest) {
    ALOGV("acquireDrmInfo Enter");
    const String8 mMimeType =  Utility::getStringValue(env, drmInfoRequest, "mMimeType");
    int mInfoType = Utility::getIntValue(env, drmInfoRequest, "mInfoType");

    DrmInfoRequest drmInfoReq(mInfoType, mMimeType);

    jclass clazz = env->FindClass("android/drm/DrmInfoRequest");
    jobject keyIterator
        = env->CallObjectMethod(drmInfoRequest,
                env->GetMethodID(clazz, "keyIterator", "()Ljava/util/Iterator;"));

    jmethodID hasNextId = env->GetMethodID(env->FindClass("java/util/Iterator"), "hasNext", "()Z");

    while (env->CallBooleanMethod(keyIterator, hasNextId)) {
        jstring key
            = (jstring) env->CallObjectMethod(keyIterator,
                env->GetMethodID(env->FindClass("java/util/Iterator"),
                                "next", "()Ljava/lang/Object;"));

        jstring value = (jstring) env->CallObjectMethod(drmInfoRequest,
                env->GetMethodID(clazz, "get", "(Ljava/lang/String;)Ljava/lang/Object;"), key);

        String8 keyString = Utility::getStringValue(env, key);
        String8 valueString = Utility::getStringValue(env, value);
        ALOGV("Key: %s | Value: %s", keyString.string(), valueString.string());

        drmInfoReq.put(keyString, valueString);
    }

    DrmInfo* pDrmInfo = getDrmManagerClientImpl(env, thiz)->acquireDrmInfo(uniqueId, &drmInfoReq);

    jobject drmInfoObject = NULL;

    if (NULL != pDrmInfo) {
        jclass localRef = env->FindClass("android/drm/DrmInfo");

        if (NULL != localRef) {
            int length = pDrmInfo->getData().length;

            jbyteArray dataArray = env->NewByteArray(length);
            env->SetByteArrayRegion(dataArray, 0, length, (jbyte*)pDrmInfo->getData().data);

            drmInfoObject
                = env->NewObject(localRef,
                    env->GetMethodID(localRef, "<init>", "(I[BLjava/lang/String;)V"),
                    mInfoType, dataArray, env->NewStringUTF(pDrmInfo->getMimeType().string()));

            DrmInfo::KeyIterator it = pDrmInfo->keyIterator();
            jmethodID putMethodId
                = env->GetMethodID(localRef, "put", "(Ljava/lang/String;Ljava/lang/Object;)V");

            while (it.hasNext()) {
                String8 key = it.next();
                String8 value = pDrmInfo->get(key);

                env->CallVoidMethod(drmInfoObject, putMethodId,
                    env->NewStringUTF(key.string()), env->NewStringUTF(value.string()));
            }
        }
        delete [] pDrmInfo->getData().data;
    }

    delete pDrmInfo; pDrmInfo = NULL;

    ALOGV("acquireDrmInfo Exit");
    return drmInfoObject;
}

static jint android_drm_DrmManagerClient_getDrmObjectType(
            JNIEnv* env, jobject thiz, jint uniqueId, jstring path, jstring mimeType) {
    ALOGV("getDrmObjectType Enter");
    int drmObjectType
        = getDrmManagerClientImpl(env, thiz)
            ->getDrmObjectType(uniqueId, Utility::getStringValue(env, path),
                                Utility::getStringValue(env, mimeType));
    ALOGV("getDrmObjectType Exit");
    return drmObjectType;
}

static jstring android_drm_DrmManagerClient_getOriginalMimeType(
            JNIEnv* env, jobject thiz, jint uniqueId, jstring path) {
    ALOGV("getOriginalMimeType Enter");
    String8 mimeType
        = getDrmManagerClientImpl(env, thiz)
            ->getOriginalMimeType(uniqueId, Utility::getStringValue(env, path));
    ALOGV("getOriginalMimeType Exit");
    return env->NewStringUTF(mimeType.string());
}

static jint android_drm_DrmManagerClient_checkRightsStatus(
            JNIEnv* env, jobject thiz, jint uniqueId, jstring path, int action) {
    ALOGV("getOriginalMimeType Enter");
    int rightsStatus
        = getDrmManagerClientImpl(env, thiz)
            ->checkRightsStatus(uniqueId, Utility::getStringValue(env, path), action);
    ALOGV("getOriginalMimeType Exit");
    return rightsStatus;
}

static jint android_drm_DrmManagerClient_removeRights(
            JNIEnv* env, jobject thiz, jint uniqueId, jstring path) {
    ALOGV("removeRights");
    return getDrmManagerClientImpl(env, thiz)
               ->removeRights(uniqueId, Utility::getStringValue(env, path));
}

static jint android_drm_DrmManagerClient_removeAllRights(
            JNIEnv* env, jobject thiz, jint uniqueId) {
    ALOGV("removeAllRights");
    return getDrmManagerClientImpl(env, thiz)->removeAllRights(uniqueId);
}

static jint android_drm_DrmManagerClient_openConvertSession(
            JNIEnv* env, jobject thiz, jint uniqueId, jstring mimeType) {
    ALOGV("openConvertSession Enter");
    int convertId
        = getDrmManagerClientImpl(env, thiz)
            ->openConvertSession(uniqueId, Utility::getStringValue(env, mimeType));
    ALOGV("openConvertSession Exit");
    return convertId;
}

static jobject android_drm_DrmManagerClient_convertData(
            JNIEnv* env, jobject thiz, jint uniqueId, jint convertId, jbyteArray inputData) {
    ALOGV("convertData Enter");

    int dataLength = 0;
    char* mData = Utility::getByteArrayValue(env, inputData, &dataLength);
    const DrmBuffer buffer(mData, dataLength);

    DrmConvertedStatus* pDrmConvertedStatus
            = getDrmManagerClientImpl(env, thiz)->convertData(uniqueId, convertId, &buffer);

    jclass localRef = env->FindClass("android/drm/DrmConvertedStatus");

    jobject drmConvertedStatus = NULL;

    if (NULL != localRef && NULL != pDrmConvertedStatus) {
        int statusCode = pDrmConvertedStatus->statusCode;

        jbyteArray dataArray = NULL;
        if (NULL != pDrmConvertedStatus->convertedData) {
            int length = pDrmConvertedStatus->convertedData->length;
            dataArray = env->NewByteArray(length);
            env->SetByteArrayRegion(dataArray, 0, length,
                            (jbyte*) pDrmConvertedStatus->convertedData->data);

            delete [] pDrmConvertedStatus->convertedData->data;
            delete pDrmConvertedStatus->convertedData; pDrmConvertedStatus->convertedData = NULL;
        }
        jmethodID constructorId = env->GetMethodID(localRef, "<init>", "(I[BI)V");
        drmConvertedStatus
            = env->NewObject(localRef, constructorId,
                             statusCode, dataArray, pDrmConvertedStatus->offset);
    }

    delete mData; mData = NULL;
    delete pDrmConvertedStatus; pDrmConvertedStatus = NULL;

    ALOGV("convertData - Exit");
    return drmConvertedStatus;
}

static jobject android_drm_DrmManagerClient_closeConvertSession(
            JNIEnv* env, jobject thiz, int uniqueId, jint convertId) {

    ALOGV("closeConvertSession Enter");

    DrmConvertedStatus* pDrmConvertedStatus
                = getDrmManagerClientImpl(env, thiz)->closeConvertSession(uniqueId, convertId);

    jclass localRef = env->FindClass("android/drm/DrmConvertedStatus");

    jobject drmConvertedStatus = NULL;

    if (NULL != localRef && NULL != pDrmConvertedStatus) {
        int statusCode = pDrmConvertedStatus->statusCode;

        jbyteArray dataArray = NULL;
        if (NULL != pDrmConvertedStatus->convertedData) {
            int length = pDrmConvertedStatus->convertedData->length;
            dataArray = env->NewByteArray(length);
            env->SetByteArrayRegion(
                dataArray, 0, length, (jbyte*) pDrmConvertedStatus->convertedData->data);

            delete [] pDrmConvertedStatus->convertedData->data;
            delete pDrmConvertedStatus->convertedData; pDrmConvertedStatus->convertedData = NULL;
        }
        jmethodID constructorId = env->GetMethodID(localRef, "<init>", "(I[BI)V");
        drmConvertedStatus
            = env->NewObject(localRef, constructorId,
                             statusCode, dataArray, pDrmConvertedStatus->offset);
    }

    delete pDrmConvertedStatus; pDrmConvertedStatus = NULL;

    ALOGV("closeConvertSession - Exit");
    return drmConvertedStatus;
}

static JNINativeMethod nativeMethods[] = {

    {"_initialize", "(Ljava/lang/Object;)I",
                                    (void*)android_drm_DrmManagerClient_initialize},

    {"_finalize", "(I)V",
                                    (void*)android_drm_DrmManagerClient_finalize},

    {"_getConstraints", "(ILjava/lang/String;I)Landroid/content/ContentValues;",
                                    (void*)android_drm_DrmManagerClient_getConstraintsFromContent},

    {"_getMetadata", "(ILjava/lang/String;)Landroid/content/ContentValues;",
                                    (void*)android_drm_DrmManagerClient_getMetadataFromContent},

    {"_getAllSupportInfo", "(I)[Landroid/drm/DrmSupportInfo;",
                                    (void*)android_drm_DrmManagerClient_getAllSupportInfo},

    {"_installDrmEngine", "(ILjava/lang/String;)V",
                                    (void*)android_drm_DrmManagerClient_installDrmEngine},

    {"_canHandle", "(ILjava/lang/String;Ljava/lang/String;)Z",
                                    (void*)android_drm_DrmManagerClient_canHandle},

    {"_processDrmInfo", "(ILandroid/drm/DrmInfo;)Landroid/drm/DrmInfoStatus;",
                                    (void*)android_drm_DrmManagerClient_processDrmInfo},

    {"_acquireDrmInfo", "(ILandroid/drm/DrmInfoRequest;)Landroid/drm/DrmInfo;",
                                    (void*)android_drm_DrmManagerClient_acquireDrmInfo},

    {"_saveRights", "(ILandroid/drm/DrmRights;Ljava/lang/String;Ljava/lang/String;)I",
                                    (void*)android_drm_DrmManagerClient_saveRights},

    {"_getDrmObjectType", "(ILjava/lang/String;Ljava/lang/String;)I",
                                    (void*)android_drm_DrmManagerClient_getDrmObjectType},

    {"_getOriginalMimeType", "(ILjava/lang/String;)Ljava/lang/String;",
                                    (void*)android_drm_DrmManagerClient_getOriginalMimeType},

    {"_checkRightsStatus", "(ILjava/lang/String;I)I",
                                    (void*)android_drm_DrmManagerClient_checkRightsStatus},

    {"_removeRights", "(ILjava/lang/String;)I",
                                    (void*)android_drm_DrmManagerClient_removeRights},

    {"_removeAllRights", "(I)I",
                                    (void*)android_drm_DrmManagerClient_removeAllRights},

    {"_openConvertSession", "(ILjava/lang/String;)I",
                                    (void*)android_drm_DrmManagerClient_openConvertSession},

    {"_convertData", "(II[B)Landroid/drm/DrmConvertedStatus;",
                                    (void*)android_drm_DrmManagerClient_convertData},

    {"_closeConvertSession", "(II)Landroid/drm/DrmConvertedStatus;",
                                    (void*)android_drm_DrmManagerClient_closeConvertSession},
};

static int registerNativeMethods(JNIEnv* env) {
    int result = -1;

    /* look up the class */
    jclass clazz = env->FindClass("android/drm/DrmManagerClient");

    if (NULL != clazz) {
        if (env->RegisterNatives(clazz, nativeMethods, sizeof(nativeMethods)
                / sizeof(nativeMethods[0])) == JNI_OK) {
            result = 0;
        }
    }
    return result;
}

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) == JNI_OK) {
        if (NULL != env && registerNativeMethods(env) == 0) {
            result = JNI_VERSION_1_4;
        }
    }
    return result;
}


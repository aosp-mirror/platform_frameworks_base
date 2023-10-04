/*
 * Copyright 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaDrm-JNI"
#include <utils/Log.h>

#include "android_media_MediaDrm.h"
#include "android_media_MediaMetricsJNI.h"
#include "android_os_Parcel.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"
#include "android_os_Parcel.h"
#include "jni.h"
#include <nativehelper/JNIHelp.h>

#include <android/hardware/drm/1.3/IDrmFactory.h>
#include <binder/Parcel.h>
#include <binder/PersistableBundle.h>
#include <cutils/properties.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaErrors.h>
#include <mediadrm/DrmMetricsConsumer.h>
#include <mediadrm/DrmUtils.h>
#include <mediadrm/IDrmMetricsConsumer.h>
#include <mediadrm/IDrm.h>
#include <utils/Vector.h>
#include <map>
#include <string>

using ::android::os::PersistableBundle;
namespace drm = ::android::hardware::drm;

namespace android {

#define FIND_CLASS(var, className) \
    var = env->FindClass(className); \
    LOG_FATAL_IF(! (var), "Unable to find class %s", className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(! (var), "Unable to find field %s", fieldName);

#define GET_METHOD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetMethodID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(! (var), "Unable to find method %s", fieldName);

#define GET_STATIC_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetStaticFieldID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(! (var), "Unable to find field %s", fieldName);

#define GET_STATIC_METHOD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetStaticMethodID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(! (var), "Unable to find static method %s", fieldName);

#define GET_STATIC_OBJECT_FIELD(var, clazz, fieldId) \
    var = env->GetStaticObjectField(clazz, fieldId); \
    LOG_FATAL_IF(! (var), "Unable to find static object field %p", fieldId);


struct RequestFields {
    jfieldID data;
    jfieldID defaultUrl;
    jfieldID requestType;
};

struct ArrayListFields {
    jmethodID init;
    jmethodID add;
};

struct HashmapFields {
    jmethodID init;
    jmethodID get;
    jmethodID put;
    jmethodID entrySet;
};

struct SetFields {
    jmethodID iterator;
};

struct IteratorFields {
    jmethodID next;
    jmethodID hasNext;
};

struct EntryFields {
    jmethodID getKey;
    jmethodID getValue;
};

struct EventTypes {
    jint kEventProvisionRequired;
    jint kEventKeyRequired;
    jint kEventKeyExpired;
    jint kEventVendorDefined;
    jint kEventSessionReclaimed;
} gEventTypes;

struct EventWhat {
    jint kWhatDrmEvent;
    jint kWhatExpirationUpdate;
    jint kWhatKeyStatusChange;
    jint kWhatSessionLostState;
} gEventWhat;

struct KeyTypes {
    jint kKeyTypeStreaming;
    jint kKeyTypeOffline;
    jint kKeyTypeRelease;
} gKeyTypes;

struct KeyRequestTypes {
    jint kKeyRequestTypeInitial;
    jint kKeyRequestTypeRenewal;
    jint kKeyRequestTypeRelease;
    jint kKeyRequestTypeNone;
    jint kKeyRequestTypeUpdate;
} gKeyRequestTypes;

struct CertificateTypes {
    jint kCertificateTypeNone;
    jint kCertificateTypeX509;
} gCertificateTypes;

struct CertificateFields {
    jfieldID wrappedPrivateKey;
    jfieldID certificateData;
};

struct StateExceptionFields {
    jmethodID init;
    jclass classId;
};

struct SessionExceptionFields {
    jmethodID init;
    jclass classId;
    jfieldID errorCode;
};

struct SessionExceptionErrorCodes {
    jint kErrorUnknown;
    jint kResourceContention;
} gSessionExceptionErrorCodes;

struct HDCPLevels {
    jint kHdcpLevelUnknown;
    jint kHdcpNone;
    jint kHdcpV1;
    jint kHdcpV2;
    jint kHdcpV2_1;
    jint kHdcpV2_2;
    jint kHdcpV2_3;
    jint kHdcpNoOutput;
} gHdcpLevels;

struct SecurityLevels {
    jint kSecurityLevelUnknown;
    jint kSecurityLevelMax;
    jint kSecurityLevelSwSecureCrypto;
    jint kSecurityLevelSwSecureDecode;
    jint kSecurityLevelHwSecureCrypto;
    jint kSecurityLevelHwSecureDecode;
    jint kSecurityLevelHwSecureAll;
} gSecurityLevels;

struct OfflineLicenseState {
    jint kOfflineLicenseStateUsable;
    jint kOfflineLicenseStateReleased;
    jint kOfflineLicenseStateUnknown;
} gOfflineLicenseStates;

struct KeyStatusFields {
    jmethodID init;
    jclass classId;
};

struct LogMessageFields {
    jmethodID init;
    jclass classId;
};

struct DrmExceptionFields {
    jmethodID init;
    jclass classId;
};

struct fields_t {
    jfieldID context;
    jmethodID post_event;
    RequestFields keyRequest;
    RequestFields provisionRequest;
    ArrayListFields arraylist;
    HashmapFields hashmap;
    SetFields set;
    IteratorFields iterator;
    EntryFields entry;
    CertificateFields certificate;
    StateExceptionFields stateException;
    SessionExceptionFields sessionException;
    jclass certificateClassId;
    jclass hashmapClassId;
    jclass arraylistClassId;
    jclass stringClassId;
    jobject bundleCreator;
    jmethodID createFromParcelId;
    jclass parcelCreatorClassId;
    KeyStatusFields keyStatus;
    LogMessageFields logMessage;
    std::map<std::string, DrmExceptionFields> exceptionCtors;
};

static fields_t gFields;

namespace {

jbyteArray hidlVectorToJByteArray(const hardware::hidl_vec<uint8_t> &vector) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    size_t length = vector.size();
    jbyteArray result = env->NewByteArray(length);
    if (result != NULL) {
        env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte *>(vector.data()));
    }
    return result;
}

jobject hidlLogMessagesToJavaList(JNIEnv *env, const Vector<drm::V1_4::LogMessage> &logs) {
    jclass clazz = gFields.arraylistClassId;
    jobject arrayList = env->NewObject(clazz, gFields.arraylist.init);
    clazz = gFields.logMessage.classId;
    for (auto log: logs) {
        jobject jLog = env->NewObject(clazz, gFields.logMessage.init,
                static_cast<jlong>(log.timeMs),
                static_cast<jint>(log.priority),
                env->NewStringUTF(log.message.c_str()));
        env->CallBooleanMethod(arrayList, gFields.arraylist.add, jLog);
    }
    return arrayList;
}

void resolveDrmExceptionCtor(JNIEnv *env, const char *className) {
    jclass clazz;
    jmethodID init;
    FIND_CLASS(clazz, className);
    GET_METHOD_ID(init, clazz, "<init>", "(Ljava/lang/String;III)V");
    gFields.exceptionCtors[std::string(className)] = {
        .init = init,
        .classId = static_cast<jclass>(env->NewGlobalRef(clazz))
        };
}

void drmThrowException(JNIEnv* env, const char *className, const DrmStatus &err, const char *msg) {
    using namespace android::jnihelp;

    if (gFields.exceptionCtors.count(std::string(className)) == 0) {
        jniThrowException(env, className, msg);
    } else {
        jstring _detailMessage = CreateExceptionMsg(env, msg);
        jobject exception = env->NewObject(gFields.exceptionCtors[std::string(className)].classId,
            gFields.exceptionCtors[std::string(className)].init, _detailMessage,
            err.getCdmErr(), err.getOemErr(), err.getContext());
        env->Throw(static_cast<jthrowable>(exception));
        if (_detailMessage != NULL) {
            env->DeleteLocalRef(_detailMessage);
        }
    }
}
}  // namespace anonymous

// ----------------------------------------------------------------------------
// ref-counted object for callbacks
class JNIDrmListener: public DrmListener
{
public:
    JNIDrmListener(JNIEnv* env, jobject thiz, jobject weak_thiz);
    ~JNIDrmListener();
    virtual void notify(DrmPlugin::EventType eventType, int extra, const ListenerArgs *arg = NULL);
private:
    JNIDrmListener();
    jclass      mClass;     // Reference to MediaDrm class
    jobject     mObject;    // Weak ref to MediaDrm Java object to call on
};

JNIDrmListener::JNIDrmListener(JNIEnv* env, jobject thiz, jobject weak_thiz)
{
    // Hold onto the MediaDrm class for use in calling the static method
    // that posts events to the application thread.
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find android/media/MediaDrm");
        jniThrowException(env, "java/lang/Exception",
                          "Can't find android/media/MediaDrm");
        return;
    }
    mClass = (jclass)env->NewGlobalRef(clazz);

    // We use a weak reference so the MediaDrm object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    mObject  = env->NewGlobalRef(weak_thiz);
}

JNIDrmListener::~JNIDrmListener()
{
    // remove global references
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mObject);
    env->DeleteGlobalRef(mClass);
}

void JNIDrmListener::notify(DrmPlugin::EventType eventType, int extra,
                            const ListenerArgs *args)
{
    jint jwhat;
    jint jeventType = 0;

    // translate DrmPlugin event types into their java equivalents
    switch (eventType) {
        case DrmPlugin::kDrmPluginEventProvisionRequired:
            jwhat = gEventWhat.kWhatDrmEvent;
            jeventType = gEventTypes.kEventProvisionRequired;
            break;
        case DrmPlugin::kDrmPluginEventKeyNeeded:
            jwhat = gEventWhat.kWhatDrmEvent;
            jeventType = gEventTypes.kEventKeyRequired;
            break;
        case DrmPlugin::kDrmPluginEventKeyExpired:
            jwhat = gEventWhat.kWhatDrmEvent;
            jeventType = gEventTypes.kEventKeyExpired;
            break;
        case DrmPlugin::kDrmPluginEventVendorDefined:
            jwhat = gEventWhat.kWhatDrmEvent;
            jeventType = gEventTypes.kEventVendorDefined;
            break;
        case DrmPlugin::kDrmPluginEventSessionReclaimed:
            jwhat = gEventWhat.kWhatDrmEvent;
            jeventType = gEventTypes.kEventSessionReclaimed;
            break;
        case DrmPlugin::kDrmPluginEventExpirationUpdate:
            jwhat = gEventWhat.kWhatExpirationUpdate;
            break;
         case DrmPlugin::kDrmPluginEventKeysChange:
            jwhat = gEventWhat.kWhatKeyStatusChange;
            break;
         case DrmPlugin::kDrmPluginEventSessionLostState:
            jwhat = gEventWhat.kWhatSessionLostState;
            break;
        default:
            ALOGE("Invalid event DrmPlugin::EventType %d, ignored", (int)eventType);
            return;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (args) {
        env->CallStaticVoidMethod(mClass, gFields.post_event, mObject,
                jwhat, jeventType, extra,
                args->jSessionId, args->jData, args->jExpirationTime,
                args->jKeyStatusList, args->jHasNewUsableKey);
    }

    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while notifying an event.");
        LOGW_EX(env);
        env->ExceptionClear();
    }
}

jint MediaErrorToJavaError(status_t err) {
#define STATUS_CASE(status) \
    case status: \
        return J##status

    switch (err) {
        STATUS_CASE(ERROR_DRM_UNKNOWN);
        STATUS_CASE(ERROR_DRM_NO_LICENSE);
        STATUS_CASE(ERROR_DRM_LICENSE_EXPIRED);
        STATUS_CASE(ERROR_DRM_RESOURCE_BUSY);
        STATUS_CASE(ERROR_DRM_INSUFFICIENT_OUTPUT_PROTECTION);
        STATUS_CASE(ERROR_DRM_SESSION_NOT_OPENED);
        STATUS_CASE(ERROR_DRM_CANNOT_HANDLE);
        STATUS_CASE(ERROR_DRM_INSUFFICIENT_SECURITY);
        STATUS_CASE(ERROR_DRM_FRAME_TOO_LARGE);
        STATUS_CASE(ERROR_DRM_SESSION_LOST_STATE);
        STATUS_CASE(ERROR_DRM_CERTIFICATE_MALFORMED);
        STATUS_CASE(ERROR_DRM_CERTIFICATE_MISSING);
        STATUS_CASE(ERROR_DRM_CRYPTO_LIBRARY);
        STATUS_CASE(ERROR_DRM_GENERIC_OEM);
        STATUS_CASE(ERROR_DRM_GENERIC_PLUGIN);
        STATUS_CASE(ERROR_DRM_INIT_DATA);
        STATUS_CASE(ERROR_DRM_KEY_NOT_LOADED);
        STATUS_CASE(ERROR_DRM_LICENSE_PARSE);
        STATUS_CASE(ERROR_DRM_LICENSE_POLICY);
        STATUS_CASE(ERROR_DRM_LICENSE_RELEASE);
        STATUS_CASE(ERROR_DRM_LICENSE_REQUEST_REJECTED);
        STATUS_CASE(ERROR_DRM_LICENSE_RESTORE);
        STATUS_CASE(ERROR_DRM_LICENSE_STATE);
        STATUS_CASE(ERROR_DRM_MEDIA_FRAMEWORK);
        STATUS_CASE(ERROR_DRM_PROVISIONING_CERTIFICATE);
        STATUS_CASE(ERROR_DRM_PROVISIONING_CONFIG);
        STATUS_CASE(ERROR_DRM_PROVISIONING_PARSE);
        STATUS_CASE(ERROR_DRM_PROVISIONING_REQUEST_REJECTED);
        STATUS_CASE(ERROR_DRM_PROVISIONING_RETRY);
        STATUS_CASE(ERROR_DRM_RESOURCE_CONTENTION);
        STATUS_CASE(ERROR_DRM_SECURE_STOP_RELEASE);
        STATUS_CASE(ERROR_DRM_STORAGE_READ);
        STATUS_CASE(ERROR_DRM_STORAGE_WRITE);
        STATUS_CASE(ERROR_DRM_ZERO_SUBSAMPLES);
#undef STATUS_CASE
    }
    return static_cast<jint>(err);
}

static void throwStateException(JNIEnv *env, const char *msg, const DrmStatus &err) {
    ALOGE("Illegal state exception: %s (%d)", msg, static_cast<status_t>(err));

    jint jerr = MediaErrorToJavaError(err);
    jobject exception = env->NewObject(gFields.stateException.classId,
            gFields.stateException.init, env->NewStringUTF(msg), static_cast<int>(jerr),
            err.getCdmErr(), err.getOemErr(), err.getContext());
    env->Throw(static_cast<jthrowable>(exception));
}

static void throwSessionException(JNIEnv *env, const char *msg, const DrmStatus &err) {
    ALOGE("Session exception: %s (%d)", msg, static_cast<status_t>(err));

    jint jErrorCode = 0;
    switch(err) {
        case ERROR_DRM_RESOURCE_CONTENTION:
            jErrorCode = gSessionExceptionErrorCodes.kResourceContention;
            break;
        default:
            break;
    }

    jobject exception = env->NewObject(gFields.sessionException.classId,
            gFields.sessionException.init,
            env->NewStringUTF(msg),
            jErrorCode,
            err.getCdmErr(),
            err.getOemErr(),
            err.getContext());

    env->Throw(static_cast<jthrowable>(exception));
}

static bool isSessionException(status_t err) {
    return err == ERROR_DRM_RESOURCE_CONTENTION;
}

static bool throwExceptionAsNecessary(
        JNIEnv *env, const sp<IDrm> &drm, const DrmStatus &err, const char *msg = NULL) {
    std::string msgStr;
    if (drm != NULL && err != OK) {
        msgStr = DrmUtils::GetExceptionMessage(err, msg, drm);
        msg = msgStr.c_str();
    }

    if (err == BAD_VALUE || err == ERROR_DRM_CANNOT_HANDLE) {
        jniThrowException(env, "java/lang/IllegalArgumentException", msg);
        return true;
    } else if (err == ERROR_UNSUPPORTED) {
        jniThrowException(env, "java/lang/UnsupportedOperationException", msg);
        return true;
    } else if (err == ERROR_DRM_NOT_PROVISIONED) {
        drmThrowException(env, "android/media/NotProvisionedException", err, msg);
        return true;
    } else if (err == ERROR_DRM_RESOURCE_BUSY) {
        drmThrowException(env, "android/media/ResourceBusyException", err, msg);
        return true;
    } else if (err == ERROR_DRM_DEVICE_REVOKED) {
        drmThrowException(env, "android/media/DeniedByServerException", err, msg);
        return true;
    } else if (err == DEAD_OBJECT) {
        jniThrowException(env, "android/media/MediaDrmResetException", msg);
        return true;
    } else if (isSessionException(err)) {
        throwSessionException(env, msg, err);
        return true;
    } else if (err != OK) {
        throwStateException(env, msg, err);
        return true;
    }
    return false;
}

static sp<IDrm> GetDrm(JNIEnv *env, jobject thiz) {
    JDrm *jdrm = (JDrm *)env->GetLongField(thiz, gFields.context);
    return jdrm ? jdrm->getDrm() : NULL;
}

JDrm::JDrm(
        JNIEnv *env, jobject thiz, const uint8_t uuid[16],
        const String8 &appPackageName) {
    mObject = env->NewWeakGlobalRef(thiz);
    mDrm = MakeDrm(uuid, appPackageName);
    if (mDrm != NULL) {
        mDrm->setListener(this);
    }
}

JDrm::~JDrm() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->DeleteWeakGlobalRef(mObject);
    mObject = NULL;
}

// static
sp<IDrm> JDrm::MakeDrm() {
    return DrmUtils::MakeDrm();
}

// static
sp<IDrm> JDrm::MakeDrm(const uint8_t uuid[16], const String8 &appPackageName) {
    sp<IDrm> drm = MakeDrm();

    if (drm == NULL) {
        return NULL;
    }

    DrmStatus err = drm->createPlugin(uuid, appPackageName);

    if (err != OK) {
        return NULL;
    }

    return drm;
}

status_t JDrm::setListener(const sp<DrmListener>& listener) {
    Mutex::Autolock lock(mLock);
    mListener = listener;
    return OK;
}

void JDrm::notify(DrmPlugin::EventType eventType, int extra, const ListenerArgs *args) {
    sp<DrmListener> listener;
    mLock.lock();
    listener = mListener;
    mLock.unlock();

    if (listener != NULL) {
        Mutex::Autolock lock(mNotifyLock);
        listener->notify(eventType, extra, args);
    }
}

void JDrm::sendEvent(
        DrmPlugin::EventType eventType,
        const hardware::hidl_vec<uint8_t> &sessionId,
        const hardware::hidl_vec<uint8_t> &data) {
    ListenerArgs args{
        .jSessionId = hidlVectorToJByteArray(sessionId),
        .jData = hidlVectorToJByteArray(data),
    };
    notify(eventType, 0, &args);
}

void JDrm::sendExpirationUpdate(
        const hardware::hidl_vec<uint8_t> &sessionId,
        int64_t expiryTimeInMS) {
    ListenerArgs args{
        .jSessionId = hidlVectorToJByteArray(sessionId),
        .jExpirationTime = expiryTimeInMS,
    };
    notify(DrmPlugin::kDrmPluginEventExpirationUpdate, 0, &args);
}

void JDrm::sendKeysChange(
        const hardware::hidl_vec<uint8_t> &sessionId,
        const std::vector<DrmKeyStatus> &keyStatusList,
        bool hasNewUsableKey) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass clazz = gFields.arraylistClassId;
    jobject arrayList = env->NewObject(clazz, gFields.arraylist.init);
    clazz = gFields.keyStatus.classId;
    for (const auto &keyStatus : keyStatusList) {
        jbyteArray jKeyId(hidlVectorToJByteArray(keyStatus.keyId));
        jint jStatusCode(keyStatus.type);
        jobject jKeyStatus = env->NewObject(clazz, gFields.keyStatus.init, jKeyId, jStatusCode);
        env->CallBooleanMethod(arrayList, gFields.arraylist.add, jKeyStatus);
    }
    ListenerArgs args{
        .jSessionId = hidlVectorToJByteArray(sessionId),
        .jKeyStatusList = arrayList,
        .jHasNewUsableKey = hasNewUsableKey,
    };
    notify(DrmPlugin::kDrmPluginEventKeysChange, 0, &args);
}

void JDrm::sendSessionLostState(
        const hardware::hidl_vec<uint8_t> &sessionId) {
    ListenerArgs args{
        .jSessionId = hidlVectorToJByteArray(sessionId),
    };
    notify(DrmPlugin::kDrmPluginEventSessionLostState, 0, &args);
}

void JDrm::disconnect() {
    if (mDrm != NULL) {
        mDrm->destroyPlugin();
        mDrm.clear();
    }
}


// static
status_t JDrm::IsCryptoSchemeSupported(const uint8_t uuid[16], const String8 &mimeType,
                                       DrmPlugin::SecurityLevel securityLevel, bool *isSupported) {
    sp<IDrm> drm = MakeDrm();

    if (drm == NULL) {
        return BAD_VALUE;
    }

    return drm->isCryptoSchemeSupported(uuid, mimeType, securityLevel, isSupported);
}

status_t JDrm::initCheck() const {
    return mDrm == NULL ? NO_INIT : OK;
}

// JNI conversion utilities
static Vector<uint8_t> JByteArrayToVector(JNIEnv *env, jbyteArray const &byteArray) {
    Vector<uint8_t> vector;
    size_t length = env->GetArrayLength(byteArray);
    vector.insertAt((size_t)0, length);
    env->GetByteArrayRegion(byteArray, 0, length, (jbyte *)vector.editArray());
    return vector;
}

static jbyteArray VectorToJByteArray(JNIEnv *env, Vector<uint8_t> const &vector) {
    size_t length = vector.size();
    jbyteArray result = env->NewByteArray(length);
    if (result != NULL) {
        env->SetByteArrayRegion(result, 0, length, (jbyte *)vector.array());
    }
    return result;
}

static String8 JStringToString8(JNIEnv *env, jstring const &jstr) {
    String8 result;

    const char *s = env->GetStringUTFChars(jstr, NULL);
    if (s) {
        result = s;
        env->ReleaseStringUTFChars(jstr, s);
    }
    return result;
}

/*
    import java.util.HashMap;
    import java.util.Set;
    import java.Map.Entry;
    import jav.util.Iterator;

    HashMap<k, v> hm;
    Set<Entry<k, v>> s = hm.entrySet();
    Iterator i = s.iterator();
    Entry e = s.next();
*/

static KeyedVector<String8, String8> HashMapToKeyedVector(
    JNIEnv *env, jobject &hashMap, bool* pIsOK) {
    jclass clazz = gFields.stringClassId;
    KeyedVector<String8, String8> keyedVector;
    *pIsOK = true;

    jobject entrySet = env->CallObjectMethod(hashMap, gFields.hashmap.entrySet);
    if (entrySet) {
        jobject iterator = env->CallObjectMethod(entrySet, gFields.set.iterator);
        if (iterator) {
            jboolean hasNext = env->CallBooleanMethod(iterator, gFields.iterator.hasNext);
            while (hasNext) {
                jobject entry = env->CallObjectMethod(iterator, gFields.iterator.next);
                if (entry) {
                    jobject obj = env->CallObjectMethod(entry, gFields.entry.getKey);
                    if (obj == NULL || !env->IsInstanceOf(obj, clazz)) {
                        jniThrowException(env, "java/lang/IllegalArgumentException",
                                          "HashMap key is not a String");
                        env->DeleteLocalRef(entry);
                        *pIsOK = false;
                        break;
                    }
                    jstring jkey = static_cast<jstring>(obj);

                    obj = env->CallObjectMethod(entry, gFields.entry.getValue);
                    if (obj == NULL || !env->IsInstanceOf(obj, clazz)) {
                        jniThrowException(env, "java/lang/IllegalArgumentException",
                                          "HashMap value is not a String");
                        env->DeleteLocalRef(entry);
                        *pIsOK = false;
                        break;
                    }
                    jstring jvalue = static_cast<jstring>(obj);

                    String8 key = JStringToString8(env, jkey);
                    String8 value = JStringToString8(env, jvalue);
                    keyedVector.add(key, value);

                    env->DeleteLocalRef(jkey);
                    env->DeleteLocalRef(jvalue);
                    hasNext = env->CallBooleanMethod(iterator, gFields.iterator.hasNext);
                }
                env->DeleteLocalRef(entry);
            }
            env->DeleteLocalRef(iterator);
        }
        env->DeleteLocalRef(entrySet);
    }
    return keyedVector;
}

static jobject KeyedVectorToHashMap (JNIEnv *env, KeyedVector<String8, String8> const &map) {
    jclass clazz = gFields.hashmapClassId;
    jobject hashMap = env->NewObject(clazz, gFields.hashmap.init);
    for (size_t i = 0; i < map.size(); ++i) {
        jstring jkey = env->NewStringUTF(map.keyAt(i).string());
        jstring jvalue = env->NewStringUTF(map.valueAt(i).string());
        env->CallObjectMethod(hashMap, gFields.hashmap.put, jkey, jvalue);
        env->DeleteLocalRef(jkey);
        env->DeleteLocalRef(jvalue);
    }
    return hashMap;
}

static jobject ListOfVectorsToArrayListOfByteArray(JNIEnv *env,
                                                   List<Vector<uint8_t>> list) {
    jclass clazz = gFields.arraylistClassId;
    jobject arrayList = env->NewObject(clazz, gFields.arraylist.init);
    List<Vector<uint8_t>>::iterator iter = list.begin();
    while (iter != list.end()) {
        jbyteArray byteArray = VectorToJByteArray(env, *iter);
        env->CallBooleanMethod(arrayList, gFields.arraylist.add, byteArray);
        env->DeleteLocalRef(byteArray);
        iter++;
    }

    return arrayList;
}

}  // namespace android

using namespace android;

static sp<JDrm> setDrm(
        JNIEnv *env, jobject thiz, const sp<JDrm> &drm) {
    sp<JDrm> old = (JDrm *)env->GetLongField(thiz, gFields.context);
    if (drm != NULL) {
        drm->incStrong(thiz);
    }
    if (old != NULL) {
        old->decStrong(thiz);
    }
    env->SetLongField(thiz, gFields.context, reinterpret_cast<jlong>(drm.get()));

    return old;
}

static bool CheckDrm(JNIEnv *env, const sp<IDrm> &drm) {
    if (drm == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "MediaDrm obj is null");
        return false;
    }
    return true;
}

static bool CheckSession(JNIEnv *env, const sp<IDrm> &drm, jbyteArray const &jsessionId)
{
    if (!CheckDrm(env, drm)) {
        return false;
    }

    if (jsessionId == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "sessionId is null");
        return false;
    }
    return true;
}

static void android_media_MediaDrm_native_release(JNIEnv *env, jobject thiz) {
    sp<JDrm> drm = setDrm(env, thiz, NULL);
    if (drm != NULL) {
        drm->setListener(NULL);
        drm->disconnect();
    }
}

static void android_media_MediaDrm_native_init(JNIEnv *env) {
    jclass clazz;
    FIND_CLASS(clazz, "android/media/MediaDrm");
    GET_FIELD_ID(gFields.context, clazz, "mNativeContext", "J");
    GET_STATIC_METHOD_ID(gFields.post_event, clazz, "postEventFromNative",
                         "(Ljava/lang/Object;III[B[BJLjava/util/List;Z)V");

    jfieldID field;
    GET_STATIC_FIELD_ID(field, clazz, "EVENT_PROVISION_REQUIRED", "I");
    gEventTypes.kEventProvisionRequired = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "EVENT_KEY_REQUIRED", "I");
    gEventTypes.kEventKeyRequired = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "EVENT_KEY_EXPIRED", "I");
    gEventTypes.kEventKeyExpired = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "EVENT_VENDOR_DEFINED", "I");
    gEventTypes.kEventVendorDefined = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "EVENT_SESSION_RECLAIMED", "I");
    gEventTypes.kEventSessionReclaimed = env->GetStaticIntField(clazz, field);

    GET_STATIC_FIELD_ID(field, clazz, "DRM_EVENT", "I");
    gEventWhat.kWhatDrmEvent = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "EXPIRATION_UPDATE", "I");
    gEventWhat.kWhatExpirationUpdate = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "KEY_STATUS_CHANGE", "I");
    gEventWhat.kWhatKeyStatusChange = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "SESSION_LOST_STATE", "I");
    gEventWhat.kWhatSessionLostState = env->GetStaticIntField(clazz, field);

    GET_STATIC_FIELD_ID(field, clazz, "KEY_TYPE_STREAMING", "I");
    gKeyTypes.kKeyTypeStreaming = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "KEY_TYPE_OFFLINE", "I");
    gKeyTypes.kKeyTypeOffline = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "KEY_TYPE_RELEASE", "I");
    gKeyTypes.kKeyTypeRelease = env->GetStaticIntField(clazz, field);

    GET_STATIC_FIELD_ID(field, clazz, "CERTIFICATE_TYPE_NONE", "I");
    gCertificateTypes.kCertificateTypeNone = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "CERTIFICATE_TYPE_X509", "I");
    gCertificateTypes.kCertificateTypeX509 = env->GetStaticIntField(clazz, field);

    GET_STATIC_FIELD_ID(field, clazz, "HDCP_LEVEL_UNKNOWN", "I");
    gHdcpLevels.kHdcpLevelUnknown = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "HDCP_NONE", "I");
    gHdcpLevels.kHdcpNone = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "HDCP_V1", "I");
    gHdcpLevels.kHdcpV1 = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "HDCP_V2", "I");
    gHdcpLevels.kHdcpV2 = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "HDCP_V2_1", "I");
    gHdcpLevels.kHdcpV2_1 = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "HDCP_V2_2", "I");
    gHdcpLevels.kHdcpV2_2 = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "HDCP_V2_3", "I");
    gHdcpLevels.kHdcpV2_3 = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "HDCP_NO_DIGITAL_OUTPUT", "I");
    gHdcpLevels.kHdcpNoOutput = env->GetStaticIntField(clazz, field);

    GET_STATIC_FIELD_ID(field, clazz, "SECURITY_LEVEL_UNKNOWN", "I");
    gSecurityLevels.kSecurityLevelUnknown = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "SECURITY_LEVEL_SW_SECURE_CRYPTO", "I");
    gSecurityLevels.kSecurityLevelSwSecureCrypto = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "SECURITY_LEVEL_SW_SECURE_DECODE", "I");
    gSecurityLevels.kSecurityLevelSwSecureDecode = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "SECURITY_LEVEL_HW_SECURE_CRYPTO", "I");
    gSecurityLevels.kSecurityLevelHwSecureCrypto = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "SECURITY_LEVEL_HW_SECURE_DECODE", "I");
    gSecurityLevels.kSecurityLevelHwSecureDecode = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "SECURITY_LEVEL_HW_SECURE_ALL", "I");
    gSecurityLevels.kSecurityLevelHwSecureAll = env->GetStaticIntField(clazz, field);

    GET_STATIC_FIELD_ID(field, clazz, "OFFLINE_LICENSE_STATE_USABLE", "I");
    gOfflineLicenseStates.kOfflineLicenseStateUsable = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "OFFLINE_LICENSE_STATE_RELEASED", "I");
    gOfflineLicenseStates.kOfflineLicenseStateReleased = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "OFFLINE_LICENSE_STATE_UNKNOWN", "I");
    gOfflineLicenseStates.kOfflineLicenseStateUnknown = env->GetStaticIntField(clazz, field);

    GET_STATIC_FIELD_ID(field, clazz, "SECURITY_LEVEL_HW_SECURE_CRYPTO", "I");

    jmethodID getMaxSecurityLevel;
    GET_STATIC_METHOD_ID(getMaxSecurityLevel, clazz, "getMaxSecurityLevel", "()I");
    gSecurityLevels.kSecurityLevelMax = env->CallStaticIntMethod(clazz, getMaxSecurityLevel);

    FIND_CLASS(clazz, "android/media/MediaDrm$KeyRequest");
    GET_FIELD_ID(gFields.keyRequest.data, clazz, "mData", "[B");
    GET_FIELD_ID(gFields.keyRequest.defaultUrl, clazz, "mDefaultUrl", "Ljava/lang/String;");
    GET_FIELD_ID(gFields.keyRequest.requestType, clazz, "mRequestType", "I");

    GET_STATIC_FIELD_ID(field, clazz, "REQUEST_TYPE_INITIAL", "I");
    gKeyRequestTypes.kKeyRequestTypeInitial = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "REQUEST_TYPE_RENEWAL", "I");
    gKeyRequestTypes.kKeyRequestTypeRenewal = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "REQUEST_TYPE_RELEASE", "I");
    gKeyRequestTypes.kKeyRequestTypeRelease = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "REQUEST_TYPE_NONE", "I");
    gKeyRequestTypes.kKeyRequestTypeNone = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "REQUEST_TYPE_UPDATE", "I");
    gKeyRequestTypes.kKeyRequestTypeUpdate = env->GetStaticIntField(clazz, field);

    FIND_CLASS(clazz, "android/media/MediaDrm$ProvisionRequest");
    GET_FIELD_ID(gFields.provisionRequest.data, clazz, "mData", "[B");
    GET_FIELD_ID(gFields.provisionRequest.defaultUrl, clazz, "mDefaultUrl", "Ljava/lang/String;");

    FIND_CLASS(clazz, "android/media/MediaDrm$Certificate");
    GET_FIELD_ID(gFields.certificate.wrappedPrivateKey, clazz, "mWrappedKey", "[B");
    GET_FIELD_ID(gFields.certificate.certificateData, clazz, "mCertificateData", "[B");
    gFields.certificateClassId = static_cast<jclass>(env->NewGlobalRef(clazz));

    // Metrics-related fields and classes.
    FIND_CLASS(clazz, "android/os/PersistableBundle");
    jfieldID bundleCreatorId;
    GET_STATIC_FIELD_ID(bundleCreatorId, clazz, "CREATOR",
                        "Landroid/os/Parcelable$Creator;");
    jobject bundleCreator;
    GET_STATIC_OBJECT_FIELD(bundleCreator, clazz, bundleCreatorId);
    gFields.bundleCreator = static_cast<jobject>(env->NewGlobalRef(bundleCreator));
    FIND_CLASS(clazz, "android/os/Parcelable$Creator");
    GET_METHOD_ID(gFields.createFromParcelId, clazz, "createFromParcel",
                  "(Landroid/os/Parcel;)Ljava/lang/Object;");
    gFields.parcelCreatorClassId = static_cast<jclass>(env->NewGlobalRef(clazz));

    FIND_CLASS(clazz, "java/util/ArrayList");
    GET_METHOD_ID(gFields.arraylist.init, clazz, "<init>", "()V");
    GET_METHOD_ID(gFields.arraylist.add, clazz, "add", "(Ljava/lang/Object;)Z");

    FIND_CLASS(clazz, "java/util/HashMap");
    GET_METHOD_ID(gFields.hashmap.init, clazz, "<init>", "()V");
    GET_METHOD_ID(gFields.hashmap.get, clazz, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
    GET_METHOD_ID(gFields.hashmap.put, clazz, "put",
                  "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    GET_METHOD_ID(gFields.hashmap.entrySet, clazz, "entrySet", "()Ljava/util/Set;");

    FIND_CLASS(clazz, "java/util/Set");
    GET_METHOD_ID(gFields.set.iterator, clazz, "iterator", "()Ljava/util/Iterator;");

    FIND_CLASS(clazz, "java/util/Iterator");
    GET_METHOD_ID(gFields.iterator.next, clazz, "next", "()Ljava/lang/Object;");
    GET_METHOD_ID(gFields.iterator.hasNext, clazz, "hasNext", "()Z");

    FIND_CLASS(clazz, "java/util/Map$Entry");
    GET_METHOD_ID(gFields.entry.getKey, clazz, "getKey", "()Ljava/lang/Object;");
    GET_METHOD_ID(gFields.entry.getValue, clazz, "getValue", "()Ljava/lang/Object;");

    FIND_CLASS(clazz, "java/util/HashMap");
    gFields.hashmapClassId = static_cast<jclass>(env->NewGlobalRef(clazz));

    FIND_CLASS(clazz, "java/lang/String");
    gFields.stringClassId = static_cast<jclass>(env->NewGlobalRef(clazz));

    FIND_CLASS(clazz, "java/util/ArrayList");
    gFields.arraylistClassId = static_cast<jclass>(env->NewGlobalRef(clazz));

    FIND_CLASS(clazz, "android/media/MediaDrm$MediaDrmStateException");
    GET_METHOD_ID(gFields.stateException.init, clazz, "<init>", "(Ljava/lang/String;IIII)V");
    gFields.stateException.classId = static_cast<jclass>(env->NewGlobalRef(clazz));

    FIND_CLASS(clazz, "android/media/MediaDrm$SessionException");
    GET_METHOD_ID(gFields.sessionException.init, clazz, "<init>", "(Ljava/lang/String;IIII)V");
    gFields.sessionException.classId = static_cast<jclass>(env->NewGlobalRef(clazz));
    GET_FIELD_ID(gFields.sessionException.errorCode, clazz, "mErrorCode", "I");

    GET_STATIC_FIELD_ID(field, clazz, "ERROR_UNKNOWN", "I");
    gSessionExceptionErrorCodes.kErrorUnknown = env->GetStaticIntField(clazz, field);
    GET_STATIC_FIELD_ID(field, clazz, "ERROR_RESOURCE_CONTENTION", "I");
    gSessionExceptionErrorCodes.kResourceContention = env->GetStaticIntField(clazz, field);

    FIND_CLASS(clazz, "android/media/MediaDrm$KeyStatus");
    gFields.keyStatus.classId = static_cast<jclass>(env->NewGlobalRef(clazz));
    GET_METHOD_ID(gFields.keyStatus.init, clazz, "<init>", "([BI)V");

    FIND_CLASS(clazz, "android/media/MediaDrm$LogMessage");
    gFields.logMessage.classId = static_cast<jclass>(env->NewGlobalRef(clazz));
    GET_METHOD_ID(gFields.logMessage.init, clazz, "<init>", "(JILjava/lang/String;)V");

    resolveDrmExceptionCtor(env, "android/media/NotProvisionedException");
    resolveDrmExceptionCtor(env, "android/media/ResourceBusyException");
    resolveDrmExceptionCtor(env, "android/media/DeniedByServerException");
}

static void android_media_MediaDrm_native_setup(
        JNIEnv *env, jobject thiz,
        jobject weak_this, jbyteArray uuidObj, jstring jappPackageName) {

    if (uuidObj == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "uuid is null");
        return;
    }

    Vector<uint8_t> uuid = JByteArrayToVector(env, uuidObj);

    if (uuid.size() != 16) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "invalid UUID size, expected 16 bytes");
        return;
    }

    String8 packageName;
    if (jappPackageName == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "application package name cannot be null");
        return;
    }

    packageName = JStringToString8(env, jappPackageName);
    sp<JDrm> drm = new JDrm(env, thiz, uuid.array(), packageName);

    status_t err = drm->initCheck();

    if (err != OK) {
        auto logs(DrmUtils::gLogBuf.getLogs());
        auto msg(DrmUtils::GetExceptionMessage(err, "Failed to instantiate drm object", logs));
        jniThrowException(
                env,
                "android/media/UnsupportedSchemeException",
                msg.c_str());
        return;
    }

    sp<JNIDrmListener> listener = new JNIDrmListener(env, thiz, weak_this);
    drm->setListener(listener);
    setDrm(env, thiz, drm);
}

DrmPlugin::SecurityLevel jintToSecurityLevel(jint jlevel) {
    DrmPlugin::SecurityLevel level;

    if (jlevel == gSecurityLevels.kSecurityLevelMax) {
        level = DrmPlugin::kSecurityLevelMax;
    }  else if (jlevel == gSecurityLevels.kSecurityLevelSwSecureCrypto) {
        level = DrmPlugin::kSecurityLevelSwSecureCrypto;
    } else if (jlevel == gSecurityLevels.kSecurityLevelSwSecureDecode) {
        level = DrmPlugin::kSecurityLevelSwSecureDecode;
    } else if (jlevel == gSecurityLevels.kSecurityLevelHwSecureCrypto) {
        level = DrmPlugin::kSecurityLevelHwSecureCrypto;
    } else if (jlevel == gSecurityLevels.kSecurityLevelHwSecureDecode) {
        level = DrmPlugin::kSecurityLevelHwSecureDecode;
    } else if (jlevel == gSecurityLevels.kSecurityLevelHwSecureAll) {
        level = DrmPlugin::kSecurityLevelHwSecureAll;
    } else {
        level = DrmPlugin::kSecurityLevelUnknown;
    }
    return level;
}

static jbyteArray android_media_MediaDrm_getSupportedCryptoSchemesNative(JNIEnv *env) {
    sp<IDrm> drm = android::DrmUtils::MakeDrm();
    if (drm == NULL) return env->NewByteArray(0);

    std::vector<uint8_t> bv;
    drm->getSupportedSchemes(bv);
    jbyteArray jUuidBytes = env->NewByteArray(bv.size());
    env->SetByteArrayRegion(jUuidBytes, 0, bv.size(), reinterpret_cast<const jbyte *>(bv.data()));
    return jUuidBytes;
}

static jboolean android_media_MediaDrm_isCryptoSchemeSupportedNative(
        JNIEnv *env, jobject /* thiz */, jbyteArray uuidObj, jstring jmimeType,
        jint jSecurityLevel) {

    if (uuidObj == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return false;
    }

    Vector<uint8_t> uuid = JByteArrayToVector(env, uuidObj);

    if (uuid.size() != 16) {
        jniThrowException(
                env,
                "java/lang/IllegalArgumentException",
                "invalid UUID size, expected 16 bytes");
        return false;
    }

    String8 mimeType;
    if (jmimeType != NULL) {
        mimeType = JStringToString8(env, jmimeType);
    }
    DrmPlugin::SecurityLevel securityLevel = jintToSecurityLevel(jSecurityLevel);

    bool isSupported;
    status_t err = JDrm::IsCryptoSchemeSupported(uuid.array(), mimeType,
            securityLevel, &isSupported);

    if (throwExceptionAsNecessary(env, NULL, err, "Failed to query crypto scheme support")) {
        return false;
    }
    return isSupported;
}

static jbyteArray android_media_MediaDrm_openSession(
        JNIEnv *env, jobject thiz, jint jlevel) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return NULL;
    }

    Vector<uint8_t> sessionId;
    DrmPlugin::SecurityLevel level = jintToSecurityLevel(jlevel);
    if (level == DrmPlugin::kSecurityLevelUnknown) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid security level");
        return NULL;
    }

    DrmStatus err = drm->openSession(level, sessionId);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to open session")) {
        return NULL;
    }

    return VectorToJByteArray(env, sessionId);
}

static void android_media_MediaDrm_closeSession(
    JNIEnv *env, jobject thiz, jbyteArray jsessionId) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckSession(env, drm, jsessionId)) {
        return;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));

    DrmStatus err = drm->closeSession(sessionId);

    throwExceptionAsNecessary(env, drm, err, "Failed to close session");
}

static jobject android_media_MediaDrm_getKeyRequest(
    JNIEnv *env, jobject thiz, jbyteArray jsessionId, jbyteArray jinitData,
    jstring jmimeType, jint jkeyType, jobject joptParams) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));

    Vector<uint8_t> initData;
    if (jinitData != NULL) {
        initData = JByteArrayToVector(env, jinitData);
    }

    String8 mimeType;
    if (jmimeType != NULL) {
        mimeType = JStringToString8(env, jmimeType);
    }

    DrmPlugin::KeyType keyType;
    if (jkeyType == gKeyTypes.kKeyTypeStreaming) {
        keyType = DrmPlugin::kKeyType_Streaming;
    } else if (jkeyType == gKeyTypes.kKeyTypeOffline) {
        keyType = DrmPlugin::kKeyType_Offline;
    } else if (jkeyType == gKeyTypes.kKeyTypeRelease) {
        keyType = DrmPlugin::kKeyType_Release;
    } else {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "invalid keyType");
        return NULL;
    }

    KeyedVector<String8, String8> optParams;
    if (joptParams != NULL) {
        bool isOK;
        optParams = HashMapToKeyedVector(env, joptParams, &isOK);
        if (!isOK) {
            return NULL;
        }
    }

    Vector<uint8_t> request;
    String8 defaultUrl;
    DrmPlugin::KeyRequestType keyRequestType;

    DrmStatus err = drm->getKeyRequest(sessionId, initData, mimeType, keyType, optParams, request,
                                       defaultUrl, &keyRequestType);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get key request")) {
        return NULL;
    }

    // Fill out return obj
    jclass clazz;
    FIND_CLASS(clazz, "android/media/MediaDrm$KeyRequest");

    jobject keyObj = NULL;

    if (clazz) {
        keyObj = env->AllocObject(clazz);
        jbyteArray jrequest = VectorToJByteArray(env, request);
        env->SetObjectField(keyObj, gFields.keyRequest.data, jrequest);

        jstring jdefaultUrl = env->NewStringUTF(defaultUrl.string());
        env->SetObjectField(keyObj, gFields.keyRequest.defaultUrl, jdefaultUrl);

        switch (keyRequestType) {
            case DrmPlugin::kKeyRequestType_Initial:
                env->SetIntField(keyObj, gFields.keyRequest.requestType,
                        gKeyRequestTypes.kKeyRequestTypeInitial);
                break;
            case DrmPlugin::kKeyRequestType_Renewal:
                env->SetIntField(keyObj, gFields.keyRequest.requestType,
                        gKeyRequestTypes.kKeyRequestTypeRenewal);
                break;
            case DrmPlugin::kKeyRequestType_Release:
                env->SetIntField(keyObj, gFields.keyRequest.requestType,
                        gKeyRequestTypes.kKeyRequestTypeRelease);
                break;
            case DrmPlugin::kKeyRequestType_None:
                env->SetIntField(keyObj, gFields.keyRequest.requestType,
                        gKeyRequestTypes.kKeyRequestTypeNone);
                break;
            case DrmPlugin::kKeyRequestType_Update:
                env->SetIntField(keyObj, gFields.keyRequest.requestType,
                        gKeyRequestTypes.kKeyRequestTypeUpdate);
                break;

            default:
                throwStateException(env, "DRM plugin failure: unknown key request type",
                        ERROR_DRM_UNKNOWN);
                break;
        }
    }

    return keyObj;
}

static jbyteArray android_media_MediaDrm_provideKeyResponse(
    JNIEnv *env, jobject thiz, jbyteArray jsessionId, jbyteArray jresponse) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));

    if (jresponse == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "key response is null");
        return NULL;
    }
    Vector<uint8_t> response(JByteArrayToVector(env, jresponse));
    Vector<uint8_t> keySetId;

    DrmStatus err = drm->provideKeyResponse(sessionId, response, keySetId);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to handle key response")) {
        return NULL;
    }
    return VectorToJByteArray(env, keySetId);
}

static void android_media_MediaDrm_removeKeys(
    JNIEnv *env, jobject thiz, jbyteArray jkeysetId) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return;
    }

    if (jkeysetId == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "keySetId is null");
        return;
    }

    Vector<uint8_t> keySetId(JByteArrayToVector(env, jkeysetId));

    DrmStatus err = drm->removeKeys(keySetId);

    throwExceptionAsNecessary(env, drm, err, "Failed to remove keys");
}

static void android_media_MediaDrm_restoreKeys(
    JNIEnv *env, jobject thiz, jbyteArray jsessionId,
    jbyteArray jkeysetId) {

    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckSession(env, drm, jsessionId)) {
        return;
    }

    if (jkeysetId == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    Vector<uint8_t> keySetId(JByteArrayToVector(env, jkeysetId));

    DrmStatus err = drm->restoreKeys(sessionId, keySetId);

    throwExceptionAsNecessary(env, drm, err, "Failed to restore keys");
}

static jobject android_media_MediaDrm_queryKeyStatus(
    JNIEnv *env, jobject thiz, jbyteArray jsessionId) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }
    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));

    KeyedVector<String8, String8> infoMap;

    DrmStatus err = drm->queryKeyStatus(sessionId, infoMap);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to query key status")) {
        return NULL;
    }

    return KeyedVectorToHashMap(env, infoMap);
}

static jobject android_media_MediaDrm_getProvisionRequestNative(
    JNIEnv *env, jobject thiz, jint jcertType, jstring jcertAuthority) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return NULL;
    }

    Vector<uint8_t> request;
    String8 defaultUrl;

    String8 certType;
    if (jcertType == gCertificateTypes.kCertificateTypeX509) {
        certType = "X.509";
    } else if (jcertType == gCertificateTypes.kCertificateTypeNone) {
        certType = "none";
    } else {
        certType = "invalid";
    }

    String8 certAuthority = JStringToString8(env, jcertAuthority);
    DrmStatus err = drm->getProvisionRequest(certType, certAuthority, request, defaultUrl);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get provision request")) {
        return NULL;
    }

    // Fill out return obj
    jclass clazz;
    FIND_CLASS(clazz, "android/media/MediaDrm$ProvisionRequest");

    jobject provisionObj = NULL;

    if (clazz) {
        provisionObj = env->AllocObject(clazz);
        jbyteArray jrequest = VectorToJByteArray(env, request);
        env->SetObjectField(provisionObj, gFields.provisionRequest.data, jrequest);

        jstring jdefaultUrl = env->NewStringUTF(defaultUrl.string());
        env->SetObjectField(provisionObj, gFields.provisionRequest.defaultUrl, jdefaultUrl);
    }

    return provisionObj;
}

static jobject android_media_MediaDrm_provideProvisionResponseNative(
    JNIEnv *env, jobject thiz, jbyteArray jresponse) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return NULL;
    }

    if (jresponse == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "provision response is null");
        return NULL;
    }

    Vector<uint8_t> response(JByteArrayToVector(env, jresponse));
    Vector<uint8_t> certificate, wrappedKey;

    DrmStatus err = drm->provideProvisionResponse(response, certificate, wrappedKey);

    // Fill out return obj
    jclass clazz = gFields.certificateClassId;

    jobject certificateObj = NULL;

    if (clazz && certificate.size() && wrappedKey.size()) {
        certificateObj = env->AllocObject(clazz);
        jbyteArray jcertificate = VectorToJByteArray(env, certificate);
        env->SetObjectField(certificateObj, gFields.certificate.certificateData, jcertificate);

        jbyteArray jwrappedKey = VectorToJByteArray(env, wrappedKey);
        env->SetObjectField(certificateObj, gFields.certificate.wrappedPrivateKey, jwrappedKey);
    }

    throwExceptionAsNecessary(env, drm, err, "Failed to handle provision response");
    return certificateObj;
}

static jobject android_media_MediaDrm_getSecureStops(
    JNIEnv *env, jobject thiz) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return NULL;
    }

    List<Vector<uint8_t>> secureStops;

    DrmStatus err = drm->getSecureStops(secureStops);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get secure stops")) {
        return NULL;
    }

    return ListOfVectorsToArrayListOfByteArray(env, secureStops);
}

static jobject android_media_MediaDrm_getSecureStopIds(
    JNIEnv *env, jobject thiz) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return NULL;
    }

    List<Vector<uint8_t>> secureStopIds;

    DrmStatus err = drm->getSecureStopIds(secureStopIds);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get secure stop Ids")) {
        return NULL;
    }

    return ListOfVectorsToArrayListOfByteArray(env, secureStopIds);
}

static jbyteArray android_media_MediaDrm_getSecureStop(
    JNIEnv *env, jobject thiz, jbyteArray ssid) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return NULL;
    }

    Vector<uint8_t> secureStop;

    DrmStatus err = drm->getSecureStop(JByteArrayToVector(env, ssid), secureStop);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get secure stop")) {
        return NULL;
    }

    return VectorToJByteArray(env, secureStop);
}

static void android_media_MediaDrm_releaseSecureStops(
    JNIEnv *env, jobject thiz, jbyteArray jssRelease) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return;
    }

    Vector<uint8_t> ssRelease(JByteArrayToVector(env, jssRelease));

    DrmStatus err = drm->releaseSecureStops(ssRelease);

    throwExceptionAsNecessary(env, drm, err, "Failed to release secure stops");
}

static void android_media_MediaDrm_removeSecureStop(
        JNIEnv *env, jobject thiz, jbyteArray ssid) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return;
    }

    DrmStatus err = drm->removeSecureStop(JByteArrayToVector(env, ssid));

    throwExceptionAsNecessary(env, drm, err, "Failed to remove secure stop");
}

static void android_media_MediaDrm_removeAllSecureStops(
    JNIEnv *env, jobject thiz) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return;
    }

    DrmStatus err = drm->removeAllSecureStops();

    throwExceptionAsNecessary(env, drm, err, "Failed to remove all secure stops");
}


static jint HdcpLevelTojint(DrmPlugin::HdcpLevel level) {
    switch(level) {
    case DrmPlugin::kHdcpLevelUnknown:
        return gHdcpLevels.kHdcpLevelUnknown;
    case DrmPlugin::kHdcpNone:
        return gHdcpLevels.kHdcpNone;
    case DrmPlugin::kHdcpV1:
        return gHdcpLevels.kHdcpV1;
    case DrmPlugin::kHdcpV2:
        return gHdcpLevels.kHdcpV2;
    case DrmPlugin::kHdcpV2_1:
        return gHdcpLevels.kHdcpV2_1;
    case DrmPlugin::kHdcpV2_2:
        return gHdcpLevels.kHdcpV2_2;
    case DrmPlugin::kHdcpV2_3:
        return gHdcpLevels.kHdcpV2_3;
    case DrmPlugin::kHdcpNoOutput:
        return gHdcpLevels.kHdcpNoOutput;
    }
    return gHdcpLevels.kHdcpNone;
}

static jint android_media_MediaDrm_getConnectedHdcpLevel(JNIEnv *env,
        jobject thiz) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return gHdcpLevels.kHdcpNone;
    }

    DrmPlugin::HdcpLevel connected = DrmPlugin::kHdcpNone;
    DrmPlugin::HdcpLevel max = DrmPlugin::kHdcpNone;

    DrmStatus err = drm->getHdcpLevels(&connected, &max);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get HDCP levels")) {
        return gHdcpLevels.kHdcpLevelUnknown;
    }
    return HdcpLevelTojint(connected);
}

static jint android_media_MediaDrm_getMaxHdcpLevel(JNIEnv *env,
        jobject thiz) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return gHdcpLevels.kHdcpLevelUnknown;
    }

    DrmPlugin::HdcpLevel connected = DrmPlugin::kHdcpLevelUnknown;
    DrmPlugin::HdcpLevel max = DrmPlugin::kHdcpLevelUnknown;

    DrmStatus err = drm->getHdcpLevels(&connected, &max);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get HDCP levels")) {
        return gHdcpLevels.kHdcpLevelUnknown;
    }
    return HdcpLevelTojint(max);
}

static jint android_media_MediaDrm_getOpenSessionCount(JNIEnv *env,
        jobject thiz) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return 0;
    }

    uint32_t open = 0, max = 0;
    DrmStatus err = drm->getNumberOfSessions(&open, &max);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get number of sessions")) {
        return 0;
    }
    return open;
}

static jint android_media_MediaDrm_getMaxSessionCount(JNIEnv *env,
        jobject thiz) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return 0;
    }

    uint32_t open = 0, max = 0;
    DrmStatus err = drm->getNumberOfSessions(&open, &max);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get number of sessions")) {
        return 0;
    }
    return max;
}

static jint android_media_MediaDrm_getSecurityLevel(JNIEnv *env,
        jobject thiz, jbyteArray jsessionId) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckSession(env, drm, jsessionId)) {
        return gSecurityLevels.kSecurityLevelUnknown;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));

    DrmPlugin::SecurityLevel level = DrmPlugin::kSecurityLevelUnknown;

    DrmStatus err = drm->getSecurityLevel(sessionId, &level);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get security level")) {
        return gSecurityLevels.kSecurityLevelUnknown;
    }

    switch(level) {
    case DrmPlugin::kSecurityLevelSwSecureCrypto:
        return gSecurityLevels.kSecurityLevelSwSecureCrypto;
    case DrmPlugin::kSecurityLevelSwSecureDecode:
        return gSecurityLevels.kSecurityLevelSwSecureDecode;
    case DrmPlugin::kSecurityLevelHwSecureCrypto:
        return gSecurityLevels.kSecurityLevelHwSecureCrypto;
    case DrmPlugin::kSecurityLevelHwSecureDecode:
        return gSecurityLevels.kSecurityLevelHwSecureDecode;
    case DrmPlugin::kSecurityLevelHwSecureAll:
        return gSecurityLevels.kSecurityLevelHwSecureAll;
    default:
        return gSecurityLevels.kSecurityLevelUnknown;
    }
}

static jobject android_media_MediaDrm_getOfflineLicenseKeySetIds(
    JNIEnv *env, jobject thiz) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return NULL;
    }

    List<Vector<uint8_t> > keySetIds;

    DrmStatus err = drm->getOfflineLicenseKeySetIds(keySetIds);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get offline key set Ids")) {
        return NULL;
    }

    return ListOfVectorsToArrayListOfByteArray(env, keySetIds);
}

static void android_media_MediaDrm_removeOfflineLicense(
        JNIEnv *env, jobject thiz, jbyteArray keySetId) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return;
    }

    DrmStatus err = drm->removeOfflineLicense(JByteArrayToVector(env, keySetId));

    throwExceptionAsNecessary(env, drm, err, "Failed to remove offline license");
}

static jint android_media_MediaDrm_getOfflineLicenseState(JNIEnv *env,
        jobject thiz, jbyteArray jkeySetId) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return gOfflineLicenseStates.kOfflineLicenseStateUnknown;
    }

    Vector<uint8_t> keySetId(JByteArrayToVector(env, jkeySetId));

    DrmPlugin::OfflineLicenseState state = DrmPlugin::kOfflineLicenseStateUnknown;

    DrmStatus err = drm->getOfflineLicenseState(keySetId, &state);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get offline license state")) {
        return gOfflineLicenseStates.kOfflineLicenseStateUnknown;
    }

    switch(state) {
    case DrmPlugin::kOfflineLicenseStateUsable:
        return gOfflineLicenseStates.kOfflineLicenseStateUsable;
    case DrmPlugin::kOfflineLicenseStateReleased:
        return gOfflineLicenseStates.kOfflineLicenseStateReleased;
    default:
        return gOfflineLicenseStates.kOfflineLicenseStateUnknown;
    }
}

static jstring android_media_MediaDrm_getPropertyString(
    JNIEnv *env, jobject thiz, jstring jname) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return NULL;
    }

    if (jname == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "property name String is null");
        return NULL;
    }

    String8 name = JStringToString8(env, jname);
    String8 value;

    DrmStatus err = drm->getPropertyString(name, value);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get property")) {
        return NULL;
    }

    return env->NewStringUTF(value.string());
}

static jbyteArray android_media_MediaDrm_getPropertyByteArray(
    JNIEnv *env, jobject thiz, jstring jname) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return NULL;
    }

    if (jname == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "property name String is null");
        return NULL;
    }

    String8 name = JStringToString8(env, jname);
    Vector<uint8_t> value;

    DrmStatus err = drm->getPropertyByteArray(name, value);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to get property")) {
        return NULL;
    }

    return VectorToJByteArray(env, value);
}

static void android_media_MediaDrm_setPropertyString(
    JNIEnv *env, jobject thiz, jstring jname, jstring jvalue) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return;
    }

    if (jname == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "property name String is null");
        return;
    }

    if (jvalue == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "property value String is null");
        return;
    }

    String8 name = JStringToString8(env, jname);
    String8 value = JStringToString8(env, jvalue);

    DrmStatus err = drm->setPropertyString(name, value);

    throwExceptionAsNecessary(env, drm, err, "Failed to set property");
}

static void android_media_MediaDrm_setPropertyByteArray(
    JNIEnv *env, jobject thiz, jstring jname, jbyteArray jvalue) {
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return;
    }

    if (jname == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "property name String is null");
        return;
    }

    if (jvalue == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "property value byte array is null");
        return;
    }

    String8 name = JStringToString8(env, jname);
    Vector<uint8_t> value = JByteArrayToVector(env, jvalue);

    DrmStatus err = drm->setPropertyByteArray(name, value);

    throwExceptionAsNecessary(env, drm, err, "Failed to set property");
}

static void android_media_MediaDrm_setCipherAlgorithmNative(
    JNIEnv *env, jobject /* thiz */, jobject jdrm, jbyteArray jsessionId,
    jstring jalgorithm) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return;
    }

    if (jalgorithm == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "algorithm String is null");
        return;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    String8 algorithm = JStringToString8(env, jalgorithm);

    DrmStatus err = drm->setCipherAlgorithm(sessionId, algorithm);

    throwExceptionAsNecessary(env, drm, err, "Failed to set cipher algorithm");
}

static void android_media_MediaDrm_setMacAlgorithmNative(
    JNIEnv *env, jobject /* thiz */, jobject jdrm, jbyteArray jsessionId,
    jstring jalgorithm) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return;
    }

    if (jalgorithm == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "algorithm String is null");
        return;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    String8 algorithm = JStringToString8(env, jalgorithm);

    DrmStatus err = drm->setMacAlgorithm(sessionId, algorithm);

    throwExceptionAsNecessary(env, drm, err, "Failed to set mac algorithm");
}


static jbyteArray android_media_MediaDrm_encryptNative(
    JNIEnv *env, jobject /* thiz */, jobject jdrm, jbyteArray jsessionId,
    jbyteArray jkeyId, jbyteArray jinput, jbyteArray jiv) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }

    if (jkeyId == NULL || jinput == NULL || jiv == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "required argument is null");
        return NULL;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    Vector<uint8_t> keyId(JByteArrayToVector(env, jkeyId));
    Vector<uint8_t> input(JByteArrayToVector(env, jinput));
    Vector<uint8_t> iv(JByteArrayToVector(env, jiv));
    Vector<uint8_t> output;

    DrmStatus err = drm->encrypt(sessionId, keyId, input, iv, output);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to encrypt")) {
        return NULL;
    }

    return VectorToJByteArray(env, output);
}

static jbyteArray android_media_MediaDrm_decryptNative(
    JNIEnv *env, jobject /* thiz */, jobject jdrm, jbyteArray jsessionId,
    jbyteArray jkeyId, jbyteArray jinput, jbyteArray jiv) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }

    if (jkeyId == NULL || jinput == NULL || jiv == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "required argument is null");
        return NULL;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    Vector<uint8_t> keyId(JByteArrayToVector(env, jkeyId));
    Vector<uint8_t> input(JByteArrayToVector(env, jinput));
    Vector<uint8_t> iv(JByteArrayToVector(env, jiv));
    Vector<uint8_t> output;

    DrmStatus err = drm->decrypt(sessionId, keyId, input, iv, output);
    if (throwExceptionAsNecessary(env, drm, err, "Failed to decrypt")) {
        return NULL;
    }

    return VectorToJByteArray(env, output);
}

static jbyteArray android_media_MediaDrm_signNative(
    JNIEnv *env, jobject /* thiz */, jobject jdrm, jbyteArray jsessionId,
    jbyteArray jkeyId, jbyteArray jmessage) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }

    if (jkeyId == NULL || jmessage == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "required argument is null");
        return NULL;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    Vector<uint8_t> keyId(JByteArrayToVector(env, jkeyId));
    Vector<uint8_t> message(JByteArrayToVector(env, jmessage));
    Vector<uint8_t> signature;

    DrmStatus err = drm->sign(sessionId, keyId, message, signature);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to sign")) {
        return NULL;
    }

    return VectorToJByteArray(env, signature);
}

static jboolean android_media_MediaDrm_verifyNative(
    JNIEnv *env, jobject /* thiz */, jobject jdrm, jbyteArray jsessionId,
    jbyteArray jkeyId, jbyteArray jmessage, jbyteArray jsignature) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return false;
    }

    if (jkeyId == NULL || jmessage == NULL || jsignature == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "required argument is null");
        return false;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    Vector<uint8_t> keyId(JByteArrayToVector(env, jkeyId));
    Vector<uint8_t> message(JByteArrayToVector(env, jmessage));
    Vector<uint8_t> signature(JByteArrayToVector(env, jsignature));
    bool match;

    DrmStatus err = drm->verify(sessionId, keyId, message, signature, match);

    throwExceptionAsNecessary(env, drm, err, "Failed to verify");
    return match;
}

static jobject
android_media_MediaDrm_native_getMetrics(JNIEnv *env, jobject thiz)
{
    sp<IDrm> drm = GetDrm(env, thiz);

    if (!CheckDrm(env, drm)) {
        return NULL;
    }

    // Retrieve current metrics snapshot from drm.
    PersistableBundle metrics;
    sp<IDrmMetricsConsumer> consumer(new DrmMetricsConsumer(&metrics));
    DrmStatus err = drm->getMetrics(consumer);
    if (err != OK) {
        ALOGE("getMetrics failed: %d", (int)err);
        return (jobject) NULL;
    }

    return MediaMetricsJNI::nativeToJavaPersistableBundle(env, &metrics);
}

static jbyteArray android_media_MediaDrm_signRSANative(
    JNIEnv *env, jobject /* thiz */, jobject jdrm, jbyteArray jsessionId,
    jstring jalgorithm, jbyteArray jwrappedKey, jbyteArray jmessage) {

    sp<IDrm> drm = GetDrm(env, jdrm);

    if (!CheckSession(env, drm, jsessionId)) {
        return NULL;
    }

    if (jalgorithm == NULL || jwrappedKey == NULL || jmessage == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "required argument is null");
        return NULL;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));
    String8 algorithm = JStringToString8(env, jalgorithm);
    Vector<uint8_t> wrappedKey(JByteArrayToVector(env, jwrappedKey));
    Vector<uint8_t> message(JByteArrayToVector(env, jmessage));
    Vector<uint8_t> signature;

    DrmStatus err = drm->signRSA(sessionId, algorithm, message, wrappedKey, signature);

    if (throwExceptionAsNecessary(env, drm, err, "Failed to sign")) {
        return NULL;
    }

    return VectorToJByteArray(env, signature);
}

static jboolean android_media_MediaDrm_requiresSecureDecoder(
        JNIEnv *env, jobject thiz, jstring jmimeType,
        jint jSecurityLevel) {
    sp<IDrm> drm = GetDrm(env, thiz);
    if (!CheckDrm(env, drm)) {
        return JNI_FALSE;
    }

    String8 mimeType;
    if (jmimeType != NULL) {
        mimeType = JStringToString8(env, jmimeType);
    }

    DrmPlugin::SecurityLevel securityLevel = jintToSecurityLevel(jSecurityLevel);
    if (securityLevel == DrmPlugin::kSecurityLevelUnknown) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid security level");
        return JNI_FALSE;
    }

    bool required = false;
    DrmStatus err = OK;
    if (securityLevel == DrmPlugin::kSecurityLevelMax) {
        err = drm->requiresSecureDecoder(mimeType.c_str(), &required);
    } else {
        err = drm->requiresSecureDecoder(mimeType.c_str(), securityLevel, &required);
    }
    if (throwExceptionAsNecessary(env, drm, err, "Failed to query secure decoder requirement")) {
        return false;
    }
    return required;
}

static void android_media_MediaDrm_setPlaybackId(
        JNIEnv *env, jobject thiz, jbyteArray jsessionId,
        jstring jplaybackId) {
    sp<IDrm> drm = GetDrm(env, thiz);
    if (!CheckSession(env, drm, jsessionId)) {
        return;
    }

    Vector<uint8_t> sessionId(JByteArrayToVector(env, jsessionId));

    String8 playbackId;
    if (jplaybackId != NULL) {
        playbackId = JStringToString8(env, jplaybackId);
    }
    DrmStatus err = drm->setPlaybackId(sessionId, playbackId.c_str());
    throwExceptionAsNecessary(env, drm, err, "Failed to set playbackId");
}

static jobject android_media_MediaDrm_getLogMessages(
        JNIEnv *env, jobject thiz) {
    sp<IDrm> drm = GetDrm(env, thiz);
    if (!CheckDrm(env, drm)) {
        return NULL;
    }

    Vector<drm::V1_4::LogMessage> logs;
    DrmStatus err = drm->getLogMessages(logs);
    ALOGI("drm->getLogMessages %zu logs", logs.size());
    if (throwExceptionAsNecessary(env, drm, err, "Failed to get log messages")) {
        return NULL;
    }
    return hidlLogMessagesToJavaList(env, logs);
}

static const JNINativeMethod gMethods[] = {
    { "native_release", "()V", (void *)android_media_MediaDrm_native_release },

    { "native_init", "()V", (void *)android_media_MediaDrm_native_init },

    { "native_setup", "(Ljava/lang/Object;[BLjava/lang/String;)V",
      (void *)android_media_MediaDrm_native_setup },

    { "getSupportedCryptoSchemesNative", "()[B",
      (void *)android_media_MediaDrm_getSupportedCryptoSchemesNative },

    { "isCryptoSchemeSupportedNative", "([BLjava/lang/String;I)Z",
      (void *)android_media_MediaDrm_isCryptoSchemeSupportedNative },

    { "openSessionNative", "(I)[B",
      (void *)android_media_MediaDrm_openSession },

    { "closeSessionNative", "([B)V",
      (void *)android_media_MediaDrm_closeSession },

    { "getKeyRequestNative", "([B[BLjava/lang/String;ILjava/util/HashMap;)"
      "Landroid/media/MediaDrm$KeyRequest;",
      (void *)android_media_MediaDrm_getKeyRequest },

    { "provideKeyResponse", "([B[B)[B",
      (void *)android_media_MediaDrm_provideKeyResponse },

    { "removeKeys", "([B)V",
      (void *)android_media_MediaDrm_removeKeys },

    { "restoreKeys", "([B[B)V",
      (void *)android_media_MediaDrm_restoreKeys },

    { "queryKeyStatus", "([B)Ljava/util/HashMap;",
      (void *)android_media_MediaDrm_queryKeyStatus },

    { "getProvisionRequestNative", "(ILjava/lang/String;)Landroid/media/MediaDrm$ProvisionRequest;",
      (void *)android_media_MediaDrm_getProvisionRequestNative },

    { "provideProvisionResponseNative", "([B)Landroid/media/MediaDrm$Certificate;",
      (void *)android_media_MediaDrm_provideProvisionResponseNative },

    { "getSecureStops", "()Ljava/util/List;",
      (void *)android_media_MediaDrm_getSecureStops },

    { "getSecureStopIds", "()Ljava/util/List;",
      (void *)android_media_MediaDrm_getSecureStopIds },

    { "getSecureStop", "([B)[B",
      (void *)android_media_MediaDrm_getSecureStop },

    { "releaseSecureStops", "([B)V",
      (void *)android_media_MediaDrm_releaseSecureStops },

    { "removeSecureStop", "([B)V",
      (void *)android_media_MediaDrm_removeSecureStop },

    { "removeAllSecureStops", "()V",
      (void *)android_media_MediaDrm_removeAllSecureStops },

    { "getConnectedHdcpLevel", "()I",
      (void *)android_media_MediaDrm_getConnectedHdcpLevel },

    { "getMaxHdcpLevel", "()I",
      (void *)android_media_MediaDrm_getMaxHdcpLevel },

    { "getOpenSessionCount", "()I",
      (void *)android_media_MediaDrm_getOpenSessionCount },

    { "getMaxSessionCount", "()I",
      (void *)android_media_MediaDrm_getMaxSessionCount },

    { "getSecurityLevel", "([B)I",
      (void *)android_media_MediaDrm_getSecurityLevel },

    { "removeOfflineLicense", "([B)V",
      (void *)android_media_MediaDrm_removeOfflineLicense },

    { "getOfflineLicenseKeySetIds", "()Ljava/util/List;",
      (void *)android_media_MediaDrm_getOfflineLicenseKeySetIds },

    { "getOfflineLicenseState", "([B)I",
      (void *)android_media_MediaDrm_getOfflineLicenseState },

    { "getPropertyString", "(Ljava/lang/String;)Ljava/lang/String;",
      (void *)android_media_MediaDrm_getPropertyString },

    { "getPropertyByteArray", "(Ljava/lang/String;)[B",
      (void *)android_media_MediaDrm_getPropertyByteArray },

    { "setPropertyString", "(Ljava/lang/String;Ljava/lang/String;)V",
      (void *)android_media_MediaDrm_setPropertyString },

    { "setPropertyByteArray", "(Ljava/lang/String;[B)V",
      (void *)android_media_MediaDrm_setPropertyByteArray },

    { "setCipherAlgorithmNative",
      "(Landroid/media/MediaDrm;[BLjava/lang/String;)V",
      (void *)android_media_MediaDrm_setCipherAlgorithmNative },

    { "setMacAlgorithmNative",
      "(Landroid/media/MediaDrm;[BLjava/lang/String;)V",
      (void *)android_media_MediaDrm_setMacAlgorithmNative },

    { "encryptNative", "(Landroid/media/MediaDrm;[B[B[B[B)[B",
      (void *)android_media_MediaDrm_encryptNative },

    { "decryptNative", "(Landroid/media/MediaDrm;[B[B[B[B)[B",
      (void *)android_media_MediaDrm_decryptNative },

    { "signNative", "(Landroid/media/MediaDrm;[B[B[B)[B",
      (void *)android_media_MediaDrm_signNative },

    { "verifyNative", "(Landroid/media/MediaDrm;[B[B[B[B)Z",
      (void *)android_media_MediaDrm_verifyNative },

    { "signRSANative", "(Landroid/media/MediaDrm;[BLjava/lang/String;[B[B)[B",
      (void *)android_media_MediaDrm_signRSANative },

    { "getMetricsNative", "()Landroid/os/PersistableBundle;",
      (void *)android_media_MediaDrm_native_getMetrics },

    { "requiresSecureDecoder", "(Ljava/lang/String;I)Z",
      (void *)android_media_MediaDrm_requiresSecureDecoder },

    { "setPlaybackId", "([BLjava/lang/String;)V",
      (void *)android_media_MediaDrm_setPlaybackId },

    { "getLogMessages", "()Ljava/util/List;",
      (void *)android_media_MediaDrm_getLogMessages },
};

int register_android_media_Drm(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaDrm", gMethods, NELEM(gMethods));
}
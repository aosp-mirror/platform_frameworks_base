/*
 * Copyright (C) 2016 The Android Open Source Project
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
#define LOG_TAG "android_os_HwParcel"
#include <android-base/logging.h>

#include "android_os_HwParcel.h"

#include "android_os_HwBinder.h"
#include "android_os_HwBlob.h"
#include "android_os_HwRemoteBinder.h"

#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <hidl/HidlTransportSupport.h>
#include <hidl/Status.h>
#include <nativehelper/ScopedLocalRef.h>

#include "core_jni_helpers.h"

using android::AndroidRuntime;

using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;

#define PACKAGE_PATH    "android/os"
#define CLASS_NAME      "HwParcel"
#define CLASS_PATH      PACKAGE_PATH "/" CLASS_NAME

namespace android {

static struct fields_t {
    jfieldID contextID;
    jmethodID constructID;

} gFields;

void signalExceptionForError(JNIEnv *env, status_t err, bool canThrowRemoteException) {
    switch (err) {
        case OK:
            break;

        case NO_MEMORY:
        {
            jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
            break;
        }

        case INVALID_OPERATION:
        {
            jniThrowException(
                    env, "java/lang/UnsupportedOperationException", NULL);
            break;
        }

        case BAD_VALUE:
        {
            jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
            break;
        }

        case -ERANGE:
        case BAD_INDEX:
        {
            jniThrowException(env, "java/lang/IndexOutOfBoundsException", NULL);
            break;
        }

        case BAD_TYPE:
        {
            jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
            break;
        }

        case NAME_NOT_FOUND:
        {
            jniThrowException(env, "java/util/NoSuchElementException", NULL);
            break;
        }

        case PERMISSION_DENIED:
        {
            jniThrowException(env, "java/lang/SecurityException", NULL);
            break;
        }

        case NO_INIT:
        {
            jniThrowException(
                    env, "java/lang/RuntimeException", "Not initialized");
            break;
        }

        case ALREADY_EXISTS:
        {
            jniThrowException(
                    env, "java/lang/RuntimeException", "Item already exists");
            break;
        }

        default:
        {
            std::stringstream ss;
            ss << "HwBinder Error: (" << err << ")";

            jniThrowException(
                    env,
                    canThrowRemoteException ? "android/os/RemoteException" : "java/lang/RuntimeException",
                    ss.str().c_str());

            break;
        }
    }
}

// static
void JHwParcel::InitClass(JNIEnv *env) {
    ScopedLocalRef<jclass> clazz(
            env, FindClassOrDie(env, CLASS_PATH));

    gFields.contextID =
        GetFieldIDOrDie(env, clazz.get(), "mNativeContext", "J");

    gFields.constructID = GetMethodIDOrDie(env, clazz.get(), "<init>", "(Z)V");
}

// static
sp<JHwParcel> JHwParcel::SetNativeContext(
        JNIEnv *env, jobject thiz, const sp<JHwParcel> &context) {
    sp<JHwParcel> old = (JHwParcel *)env->GetLongField(thiz, gFields.contextID);

    if (context != NULL) {
        context->incStrong(NULL /* id */);
    }

    if (old != NULL) {
        old->decStrong(NULL /* id */);
    }

    env->SetLongField(thiz, gFields.contextID, (long)context.get());

    return old;
}

// static
sp<JHwParcel> JHwParcel::GetNativeContext(JNIEnv *env, jobject thiz) {
    return (JHwParcel *)env->GetLongField(thiz, gFields.contextID);
}

JHwParcel::JHwParcel(JNIEnv *env, jobject thiz)
    : mParcel(NULL),
      mOwnsParcel(false),
      mTransactCallback(nullptr),
      mWasSent(false) {
}

JHwParcel::~JHwParcel() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    mStorage.release(env);

    setParcel(NULL, false /* assumeOwnership */);
}

hardware::Parcel *JHwParcel::getParcel() {
    return mParcel;
}

EphemeralStorage *JHwParcel::getStorage() {
    return &mStorage;
}

void JHwParcel::setParcel(hardware::Parcel *parcel, bool assumeOwnership) {
    if (mParcel && mOwnsParcel) {
        delete mParcel;
    }

    mParcel = parcel;
    mOwnsParcel = assumeOwnership;
}

// static
jobject JHwParcel::NewObject(JNIEnv *env) {
    ScopedLocalRef<jclass> clazz(env, FindClassOrDie(env, CLASS_PATH));

    jmethodID constructID =
        GetMethodIDOrDie(env, clazz.get(), "<init>", "(Z)V");

    return env->NewObject(clazz.get(), constructID, false /* allocate */);
}

void JHwParcel::setTransactCallback(
        ::android::hardware::IBinder::TransactCallback cb) {
    mTransactCallback = cb;
}

void JHwParcel::send() {
    CHECK(mTransactCallback != nullptr);
    CHECK(mParcel != nullptr);

    mTransactCallback(*mParcel);
    mTransactCallback = nullptr;

    mWasSent = true;
}

bool JHwParcel::wasSent() const {
    return mWasSent;
}

}  // namespace android

////////////////////////////////////////////////////////////////////////////////

using namespace android;

static void releaseNativeContext(void *nativeContext) {
    sp<JHwParcel> parcel = (JHwParcel *)nativeContext;

    if (parcel != NULL) {
        parcel->decStrong(NULL /* id */);
    }
}

static jlong JHwParcel_native_init(JNIEnv *env) {
    JHwParcel::InitClass(env);

    return reinterpret_cast<jlong>(&releaseNativeContext);
}

static void JHwParcel_native_setup(
        JNIEnv *env, jobject thiz, jboolean allocate) {
    sp<JHwParcel> context = new JHwParcel(env, thiz);

    if (allocate) {
        context->setParcel(new hardware::Parcel, true /* assumeOwnership */);
    }

    JHwParcel::SetNativeContext(env, thiz, context);
}

static void JHwParcel_native_writeInterfaceToken(
        JNIEnv *env, jobject thiz, jstring interfaceNameObj) {
    if (interfaceNameObj == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    const jchar *interfaceName = env->GetStringCritical(interfaceNameObj, NULL);
    if (interfaceName) {
        String8 nameCopy = String8(String16(
                reinterpret_cast<const char16_t *>(interfaceName),
                env->GetStringLength(interfaceNameObj)));

        env->ReleaseStringCritical(interfaceNameObj, interfaceName);
        interfaceName = NULL;

        hardware::Parcel *parcel =
            JHwParcel::GetNativeContext(env, thiz)->getParcel();

        status_t err = parcel->writeInterfaceToken(nameCopy.string());
        signalExceptionForError(env, err);
    }
}

static void JHwParcel_native_enforceInterface(
        JNIEnv *env, jobject thiz, jstring interfaceNameObj) {
    // XXX original binder Parcel enforceInterface implementation does some
    // mysterious things regarding strictModePolicy(), figure out if we need
    // that here as well.
    if (interfaceNameObj == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    const jchar *interfaceName = env->GetStringCritical(interfaceNameObj, NULL);
    if (interfaceName) {
        String8 interfaceNameCopy = String8(String16(
                reinterpret_cast<const char16_t *>(interfaceName),
                env->GetStringLength(interfaceNameObj)));

        env->ReleaseStringCritical(interfaceNameObj, interfaceName);
        interfaceName = NULL;

        hardware::Parcel *parcel =
            JHwParcel::GetNativeContext(env, thiz)->getParcel();

        bool valid = parcel->enforceInterface(interfaceNameCopy.string());

        if (!valid) {
            jniThrowException(
                    env,
                    "java/lang/SecurityException",
                    "HWBinder invocation to an incorrect interface");
        }
    }
}

#define DEFINE_PARCEL_WRITER(Suffix,Type)                               \
static void JHwParcel_native_write ## Suffix(                           \
        JNIEnv *env, jobject thiz, Type val) {                          \
    hardware::Parcel *parcel =                                          \
        JHwParcel::GetNativeContext(env, thiz)->getParcel();            \
                                                                        \
    status_t err = parcel->write ## Suffix(val);                        \
    signalExceptionForError(env, err);                                  \
}

#define DEFINE_PARCEL_READER(Suffix,Type)                               \
static Type JHwParcel_native_read ## Suffix(                            \
        JNIEnv *env, jobject thiz) {                                    \
    hardware::Parcel *parcel =                                          \
        JHwParcel::GetNativeContext(env, thiz)->getParcel();            \
                                                                        \
    Type val;                                                           \
    status_t err = parcel->read ## Suffix(&val);                        \
    signalExceptionForError(env, err);                                  \
                                                                        \
    return val;                                                         \
}

DEFINE_PARCEL_WRITER(Bool,jboolean)
DEFINE_PARCEL_WRITER(Int8,jbyte)
DEFINE_PARCEL_WRITER(Int16,jshort)
DEFINE_PARCEL_WRITER(Int32,jint)
DEFINE_PARCEL_WRITER(Int64,jlong)
DEFINE_PARCEL_WRITER(Float,jfloat)
DEFINE_PARCEL_WRITER(Double,jdouble)

DEFINE_PARCEL_READER(Int8,jbyte)
DEFINE_PARCEL_READER(Int16,jshort)
DEFINE_PARCEL_READER(Int32,jint)
DEFINE_PARCEL_READER(Int64,jlong)
DEFINE_PARCEL_READER(Float,jfloat)
DEFINE_PARCEL_READER(Double,jdouble)

static jboolean JHwParcel_native_readBool(JNIEnv *env, jobject thiz) {
    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    bool val;
    status_t err = parcel->readBool(&val);
    signalExceptionForError(env, err);

    return (jboolean)val;
}

static void JHwParcel_native_writeStatus(
        JNIEnv *env, jobject thiz, jint statusCode) {
    using hardware::Status;

    Status status;
    switch (statusCode) {
        case 0:  // kStatusSuccess
            status = Status::ok();
            break;
        case -1:  // kStatusError
            status = Status::fromStatusT(UNKNOWN_ERROR);
            break;
        default:
            CHECK(!"Should not be here");
    }

    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    status_t err = ::android::hardware::writeToParcel(status, parcel);
    signalExceptionForError(env, err);
}

static void JHwParcel_native_verifySuccess(JNIEnv *env, jobject thiz) {
    using hardware::Status;

    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    Status status;
    status_t err = ::android::hardware::readFromParcel(&status, *parcel);
    signalExceptionForError(env, err);

    if (!status.isOk()) {
        signalExceptionForError(env, UNKNOWN_ERROR, true /* canThrowRemoteException */);
    }
}

static void JHwParcel_native_release(
        JNIEnv *env, jobject thiz) {
    JHwParcel::GetNativeContext(env, thiz)->setParcel(NULL, false /* assumeOwnership */);
}

static void JHwParcel_native_releaseTemporaryStorage(
        JNIEnv *env, jobject thiz) {
    JHwParcel::GetNativeContext(env, thiz)->getStorage()->release(env);
}

static void JHwParcel_native_send(JNIEnv *env, jobject thiz) {
    JHwParcel::GetNativeContext(env, thiz)->send();
}

static void JHwParcel_native_writeString(
        JNIEnv *env, jobject thiz, jstring valObj) {
    if (valObj == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    sp<JHwParcel> impl = JHwParcel::GetNativeContext(env, thiz);

    const hidl_string *s =
        impl->getStorage()->allocTemporaryString(env, valObj);

    hardware::Parcel *parcel = impl->getParcel();

    size_t parentHandle;
    status_t err = parcel->writeBuffer(s, sizeof(*s), &parentHandle);

    if (err == OK) {
        err = ::android::hardware::writeEmbeddedToParcel(
                *s, parcel, parentHandle, 0 /* parentOffset */);
    }

    signalExceptionForError(env, err);
}

#define DEFINE_PARCEL_VECTOR_WRITER(Suffix,Type)                               \
static void JHwParcel_native_write ## Suffix ## Vector(                        \
        JNIEnv *env, jobject thiz, Type ## Array valObj) {                     \
    if (valObj == NULL) {                                                      \
        jniThrowException(env, "java/lang/NullPointerException", NULL);        \
        return;                                                                \
    }                                                                          \
                                                                               \
    sp<JHwParcel> impl = JHwParcel::GetNativeContext(env, thiz);               \
                                                                               \
    const hidl_vec<Type> *vec =                                                \
        impl->getStorage()->allocTemporary ## Suffix ## Vector(env, valObj);   \
                                                                               \
    hardware::Parcel *parcel = impl->getParcel();                              \
                                                                               \
    size_t parentHandle;                                                       \
    status_t err = parcel->writeBuffer(vec, sizeof(*vec), &parentHandle);      \
                                                                               \
    if (err == OK) {                                                           \
        size_t childHandle;                                                    \
                                                                               \
        err = ::android::hardware::writeEmbeddedToParcel(                      \
                *vec,                                                          \
                parcel,                                                        \
                parentHandle,                                                  \
                0 /* parentOffset */,                                          \
                &childHandle);                                                 \
    }                                                                          \
                                                                               \
    signalExceptionForError(env, err);                                         \
}

DEFINE_PARCEL_VECTOR_WRITER(Int8,jbyte)
DEFINE_PARCEL_VECTOR_WRITER(Int16,jshort)
DEFINE_PARCEL_VECTOR_WRITER(Int32,jint)
DEFINE_PARCEL_VECTOR_WRITER(Int64,jlong)
DEFINE_PARCEL_VECTOR_WRITER(Float,jfloat)
DEFINE_PARCEL_VECTOR_WRITER(Double,jdouble)

static void JHwParcel_native_writeBoolVector(
        JNIEnv *env, jobject thiz, jbooleanArray valObj) {
    if (valObj == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    sp<JHwParcel> impl = JHwParcel::GetNativeContext(env, thiz);

    void *vecPtr =
        impl->getStorage()->allocTemporaryStorage(sizeof(hidl_vec<bool>));

    hidl_vec<bool> *vec = new (vecPtr) hidl_vec<bool>;

    jsize len = env->GetArrayLength(valObj);

    jboolean *src = env->GetBooleanArrayElements(valObj, nullptr);

    bool *dst =
        (bool *)impl->getStorage()->allocTemporaryStorage(len * sizeof(bool));

    for (jsize i = 0; i < len; ++i) {
        dst[i] = src[i];
    }

    env->ReleaseBooleanArrayElements(valObj, src, 0 /* mode */);
    src = nullptr;

    vec->setToExternal(dst, len);

    hardware::Parcel *parcel = impl->getParcel();

    size_t parentHandle;
    status_t err = parcel->writeBuffer(vec, sizeof(*vec), &parentHandle);

    if (err == OK) {
        size_t childHandle;

        err = ::android::hardware::writeEmbeddedToParcel(
                *vec,
                parcel,
                parentHandle,
                0 /* parentOffset */,
                &childHandle);
    }

    signalExceptionForError(env, err);
}

static void JHwParcel_native_writeStrongBinder(
        JNIEnv *env, jobject thiz, jobject binderObj) {
    sp<hardware::IBinder> binder;
    if (binderObj != NULL) {
        ScopedLocalRef<jclass> hwBinderKlass(
                env, FindClassOrDie(env, PACKAGE_PATH "/HwBinder"));

        ScopedLocalRef<jclass> hwRemoteBinderKlass(
                env, FindClassOrDie(env, PACKAGE_PATH "/HwRemoteBinder"));

        if (env->IsInstanceOf(binderObj, hwBinderKlass.get())) {
            binder = JHwBinder::GetNativeBinder(env, binderObj);
        } else if (env->IsInstanceOf(binderObj, hwRemoteBinderKlass.get())) {
            binder = JHwRemoteBinder::GetNativeContext(
                    env, binderObj)->getBinder();
        } else {
            signalExceptionForError(env, INVALID_OPERATION);
            return;
        }
    }

    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    status_t err = parcel->writeStrongBinder(binder);
    signalExceptionForError(env, err);
}

static jstring MakeStringObjFromHidlString(JNIEnv *env, const hidl_string &s) {
    String16 utf16String(s.c_str(), s.size());

    return env->NewString(
            reinterpret_cast<const jchar *>(utf16String.string()),
            utf16String.size());
}

static jstring JHwParcel_native_readString(JNIEnv *env, jobject thiz) {
    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    size_t parentHandle;

    const hidl_string *s;
    status_t err = parcel->readBuffer(sizeof(*s), &parentHandle,
            reinterpret_cast<const void**>(&s));

    if (err != OK) {
        signalExceptionForError(env, err);
        return NULL;
    }

    err = ::android::hardware::readEmbeddedFromParcel(
            const_cast<hidl_string &>(*s),
            *parcel, parentHandle, 0 /* parentOffset */);

    if (err != OK) {
        signalExceptionForError(env, err);
        return NULL;
    }

    return MakeStringObjFromHidlString(env, *s);
}

#define DEFINE_PARCEL_VECTOR_READER(Suffix,Type,NewType)                       \
static Type ## Array JHwParcel_native_read ## Suffix ## Vector(                \
        JNIEnv *env, jobject thiz) {                                           \
    hardware::Parcel *parcel =                                                 \
        JHwParcel::GetNativeContext(env, thiz)->getParcel();                   \
    size_t parentHandle;                                                       \
                                                                               \
    const hidl_vec<Type> *vec;                                                 \
    status_t err = parcel->readBuffer(sizeof(*vec), &parentHandle,             \
            reinterpret_cast<const void**>(&vec));                             \
                                                                               \
    if (err != OK) {                                                           \
        signalExceptionForError(env, err);                                     \
        return NULL;                                                           \
    }                                                                          \
                                                                               \
    size_t childHandle;                                                        \
                                                                               \
    err = ::android::hardware::readEmbeddedFromParcel(                         \
                const_cast<hidl_vec<Type> &>(*vec),                            \
                *parcel,                                                       \
                parentHandle,                                                  \
                0 /* parentOffset */,                                          \
                &childHandle);                                                 \
                                                                               \
    if (err != OK) {                                                           \
        signalExceptionForError(env, err);                                     \
        return NULL;                                                           \
    }                                                                          \
                                                                               \
    Type ## Array valObj = env->New ## NewType ## Array(vec->size());          \
    env->Set ## NewType ## ArrayRegion(valObj, 0, vec->size(), &(*vec)[0]);    \
                                                                               \
    return valObj;                                                             \
}

DEFINE_PARCEL_VECTOR_READER(Int8,jbyte,Byte)
DEFINE_PARCEL_VECTOR_READER(Int16,jshort,Short)
DEFINE_PARCEL_VECTOR_READER(Int32,jint,Int)
DEFINE_PARCEL_VECTOR_READER(Int64,jlong,Long)
DEFINE_PARCEL_VECTOR_READER(Float,jfloat,Float)
DEFINE_PARCEL_VECTOR_READER(Double,jdouble,Double)

static jbooleanArray JHwParcel_native_readBoolVector(
        JNIEnv *env, jobject thiz) {
    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    size_t parentHandle;

    const hidl_vec<bool> *vec;
    status_t err = parcel->readBuffer(sizeof(*vec), &parentHandle,
            reinterpret_cast<const void**>(&vec));

    if (err != OK) {
        signalExceptionForError(env, err);
        return NULL;
    }

    size_t childHandle;

    err = ::android::hardware::readEmbeddedFromParcel(
                const_cast<hidl_vec<bool> &>(*vec),
                *parcel,
                parentHandle,
                0 /* parentOffset */,
                &childHandle);

    if (err != OK) {
        signalExceptionForError(env, err);
        return NULL;
    }

    jbooleanArray valObj = env->NewBooleanArray(vec->size());

    for (size_t i = 0; i < vec->size(); ++i) {
        jboolean x = (*vec)[i];
        env->SetBooleanArrayRegion(valObj, i, 1, &x);
    }

    return valObj;
}

static jobjectArray MakeStringArray(
        JNIEnv *env, const hidl_string *array, size_t size) {
    ScopedLocalRef<jclass> stringKlass(
            env,
            env->FindClass("java/lang/String"));

    // XXX Why can't I use ScopedLocalRef<> for the arrayObj and the stringObjs?

    jobjectArray arrayObj = env->NewObjectArray(size, stringKlass.get(), NULL);

    for (size_t i = 0; i < size; ++i) {
        jstring stringObj = MakeStringObjFromHidlString(env, array[i]);

        env->SetObjectArrayElement(
                arrayObj,
                i,
                stringObj);
    }

    return arrayObj;
}

static jobjectArray JHwParcel_native_readStringVector(
        JNIEnv *env, jobject thiz) {
    typedef hidl_vec<hidl_string> string_vec;

    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    size_t parentHandle;

    const string_vec *vec;
    status_t err = parcel->readBuffer(sizeof(*vec), &parentHandle,
            reinterpret_cast<const void **>(&vec));

    if (err != OK) {
        signalExceptionForError(env, err);
        return NULL;
    }

    size_t childHandle;
    err = ::android::hardware::readEmbeddedFromParcel(
            const_cast<string_vec &>(*vec),
            *parcel, parentHandle, 0 /* parentOffset */, &childHandle);

    for (size_t i = 0; (err == OK) && (i < vec->size()); ++i) {
        err = android::hardware::readEmbeddedFromParcel(
                    const_cast<hidl_string &>((*vec)[i]),
                    *parcel,
                    childHandle,
                    i * sizeof(hidl_string) /* parentOffset */);
    }

    if (err != OK) {
        signalExceptionForError(env, err);
        return NULL;
    }

    return MakeStringArray(env, &(*vec)[0], vec->size());
}

static void JHwParcel_native_writeStringVector(
        JNIEnv *env, jobject thiz, jobjectArray arrayObj) {
    typedef hidl_vec<hidl_string> string_vec;

    if (arrayObj == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    jsize len = env->GetArrayLength(arrayObj);

    sp<JHwParcel> impl = JHwParcel::GetNativeContext(env, thiz);

    void *vecPtr =
        impl->getStorage()->allocTemporaryStorage(sizeof(string_vec));

    string_vec *vec = new (vecPtr) string_vec;

    hidl_string *strings = impl->getStorage()->allocStringArray(len);
    vec->setToExternal(strings, len);

    for (jsize i = 0; i < len; ++i) {
        ScopedLocalRef<jstring> stringObj(
                env,
                (jstring)env->GetObjectArrayElement(arrayObj, i));

        const hidl_string *s =
            impl->getStorage()->allocTemporaryString(env, stringObj.get());

        strings[i].setToExternal(s->c_str(), s->size());
    }

    hardware::Parcel *parcel = impl->getParcel();

    size_t parentHandle;
    status_t err = parcel->writeBuffer(vec, sizeof(*vec), &parentHandle);

    if (err == OK) {
        size_t childHandle;
        err = ::android::hardware::writeEmbeddedToParcel(
                *vec,
                parcel,
                parentHandle,
                0 /* parentOffset */,
                &childHandle);

        for (size_t i = 0; (err == OK) && (i < vec->size()); ++i) {
            err = ::android::hardware::writeEmbeddedToParcel(
                    (*vec)[i],
                    parcel,
                    childHandle,
                    i * sizeof(hidl_string));
        }
    }

    signalExceptionForError(env, err);
}

static jobject JHwParcel_native_readStrongBinder(JNIEnv *env, jobject thiz) {
    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    sp<hardware::IBinder> binder = parcel->readStrongBinder();

    if (binder == NULL) {
        return NULL;
    }

    return JHwRemoteBinder::NewObject(env, binder);
}

static jobject JHwParcel_native_readBuffer(JNIEnv *env, jobject thiz,
                                           jlong expectedSize) {
    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    size_t handle;
    const void *ptr;

    if (expectedSize < 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return nullptr;
    }

    status_t status = parcel->readBuffer(expectedSize, &handle, &ptr);

    if (status != OK) {
        jniThrowException(env, "java/util/NoSuchElementException", NULL);
        return nullptr;
    }

    return JHwBlob::NewObject(env, ptr, handle);
}

static jobject JHwParcel_native_readEmbeddedBuffer(
        JNIEnv *env, jobject thiz, jlong expectedSize,
        jlong parentHandle, jlong offset, jboolean nullable) {
    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    size_t childHandle;

    const void *ptr;
    status_t status =
        parcel->readNullableEmbeddedBuffer(expectedSize,
                &childHandle, parentHandle, offset, &ptr);

    if (expectedSize < 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return nullptr;
    }

    if (status != OK) {
        jniThrowException(env, "java/util/NoSuchElementException", NULL);
        return 0;
    } else if (status == OK && !nullable && ptr == nullptr) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return 0;
    }

    return JHwBlob::NewObject(env, ptr, childHandle);
}

static void JHwParcel_native_writeBuffer(
        JNIEnv *env, jobject thiz, jobject blobObj) {
    if (blobObj == nullptr) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, blobObj);
    status_t err = blob->writeToParcel(parcel);

    if (err != OK) {
        signalExceptionForError(env, err);
    }
}

static JNINativeMethod gMethods[] = {
    { "native_init", "()J", (void *)JHwParcel_native_init },
    { "native_setup", "(Z)V", (void *)JHwParcel_native_setup },

    { "writeInterfaceToken", "(Ljava/lang/String;)V",
        (void *)JHwParcel_native_writeInterfaceToken },

    { "writeBool", "(Z)V", (void *)JHwParcel_native_writeBool },
    { "writeInt8", "(B)V", (void *)JHwParcel_native_writeInt8 },
    { "writeInt16", "(S)V", (void *)JHwParcel_native_writeInt16 },
    { "writeInt32", "(I)V", (void *)JHwParcel_native_writeInt32 },
    { "writeInt64", "(J)V", (void *)JHwParcel_native_writeInt64 },
    { "writeFloat", "(F)V", (void *)JHwParcel_native_writeFloat },
    { "writeDouble", "(D)V", (void *)JHwParcel_native_writeDouble },

    { "writeString", "(Ljava/lang/String;)V",
        (void *)JHwParcel_native_writeString },

    { "writeBoolVector", "([Z)V", (void *)JHwParcel_native_writeBoolVector },
    { "writeInt8Vector", "([B)V", (void *)JHwParcel_native_writeInt8Vector },
    { "writeInt16Vector", "([S)V", (void *)JHwParcel_native_writeInt16Vector },
    { "writeInt32Vector", "([I)V", (void *)JHwParcel_native_writeInt32Vector },
    { "writeInt64Vector", "([J)V", (void *)JHwParcel_native_writeInt64Vector },
    { "writeFloatVector", "([F)V", (void *)JHwParcel_native_writeFloatVector },

    { "writeDoubleVector", "([D)V",
        (void *)JHwParcel_native_writeDoubleVector },

    { "writeStringVector", "([Ljava/lang/String;)V",
        (void *)JHwParcel_native_writeStringVector },

    { "writeStrongBinder", "(L" PACKAGE_PATH "/IHwBinder;)V",
        (void *)JHwParcel_native_writeStrongBinder },

    { "enforceInterface", "(Ljava/lang/String;)V",
        (void *)JHwParcel_native_enforceInterface },

    { "readBool", "()Z", (void *)JHwParcel_native_readBool },
    { "readInt8", "()B", (void *)JHwParcel_native_readInt8 },
    { "readInt16", "()S", (void *)JHwParcel_native_readInt16 },
    { "readInt32", "()I", (void *)JHwParcel_native_readInt32 },
    { "readInt64", "()J", (void *)JHwParcel_native_readInt64 },
    { "readFloat", "()F", (void *)JHwParcel_native_readFloat },
    { "readDouble", "()D", (void *)JHwParcel_native_readDouble },

    { "readString", "()Ljava/lang/String;",
        (void *)JHwParcel_native_readString },

    { "readBoolVectorAsArray", "()[Z",
        (void *)JHwParcel_native_readBoolVector },

    { "readInt8VectorAsArray", "()[B",
        (void *)JHwParcel_native_readInt8Vector },

    { "readInt16VectorAsArray", "()[S",
        (void *)JHwParcel_native_readInt16Vector },

    { "readInt32VectorAsArray", "()[I",
        (void *)JHwParcel_native_readInt32Vector },

    { "readInt64VectorAsArray", "()[J",
        (void *)JHwParcel_native_readInt64Vector },

    { "readFloatVectorAsArray", "()[F",
        (void *)JHwParcel_native_readFloatVector },

    { "readDoubleVectorAsArray", "()[D",
        (void *)JHwParcel_native_readDoubleVector },

    { "readStringVectorAsArray", "()[Ljava/lang/String;",
        (void *)JHwParcel_native_readStringVector },

    { "readStrongBinder", "()L" PACKAGE_PATH "/IHwBinder;",
        (void *)JHwParcel_native_readStrongBinder },

    { "writeStatus", "(I)V", (void *)JHwParcel_native_writeStatus },

    { "verifySuccess", "()V", (void *)JHwParcel_native_verifySuccess },

    { "releaseTemporaryStorage", "()V",
        (void *)JHwParcel_native_releaseTemporaryStorage },

    { "send", "()V", (void *)JHwParcel_native_send },

    { "readBuffer", "(J)L" PACKAGE_PATH "/HwBlob;",
        (void *)JHwParcel_native_readBuffer },

    { "readEmbeddedBuffer", "(JJJZ)L" PACKAGE_PATH "/HwBlob;",
        (void *)JHwParcel_native_readEmbeddedBuffer },

    { "writeBuffer", "(L" PACKAGE_PATH "/HwBlob;)V",
        (void *)JHwParcel_native_writeBuffer },

    { "release", "()V",
        (void *)JHwParcel_native_release },

};

namespace android {

int register_android_os_HwParcel(JNIEnv *env) {
    return RegisterMethodsOrDie(env, CLASS_PATH, gMethods, NELEM(gMethods));
}

}  // namespace android

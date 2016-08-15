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
#include "android_os_HwRemoteBinder.h"

#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <hwbinder/Status.h>
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

void signalExceptionForError(JNIEnv *env, status_t err) {
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
            jniThrowException(
                    env, "java/lang/RuntimeException", "Unknown error");

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
    jclass clazz = env->GetObjectClass(thiz);
    CHECK(clazz != NULL);

    mClass = (jclass)env->NewGlobalRef(clazz);
    mObject = env->NewWeakGlobalRef(thiz);
}

JHwParcel::~JHwParcel() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    mStorage.release(env);

    setParcel(NULL, false /* assumeOwnership */);

    env->DeleteWeakGlobalRef(mObject);
    mObject = NULL;

    env->DeleteGlobalRef(mClass);
    mClass = NULL;
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

    return env->NewObject(
            clazz.get(), gFields.constructID, false /* allocate */);
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
        hardware::Parcel *parcel =
            JHwParcel::GetNativeContext(env, thiz)->getParcel();

        status_t err = parcel->writeInterfaceToken(
                String16(
                    reinterpret_cast<const char16_t *>(interfaceName),
                    env->GetStringLength(interfaceNameObj)));

        env->ReleaseStringCritical(interfaceNameObj, interfaceName);
        interfaceName = NULL;

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
        hardware::Parcel *parcel =
            JHwParcel::GetNativeContext(env, thiz)->getParcel();

        bool valid = parcel->enforceInterface(
                String16(
                    reinterpret_cast<const char16_t *>(interfaceName),
                    env->GetStringLength(interfaceNameObj)));

        env->ReleaseStringCritical(interfaceNameObj, interfaceName);
        interfaceName = NULL;

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

    status_t err = status.writeToParcel(parcel);
    signalExceptionForError(env, err);
}

static void JHwParcel_native_verifySuccess(JNIEnv *env, jobject thiz) {
    using hardware::Status;

    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    Status status;
    status_t err = status.readFromParcel(*parcel);
    signalExceptionForError(env, err);
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
        err = s->writeEmbeddedToParcel(
                parcel, parentHandle, 0 /* parentOffset */);
    }

    signalExceptionForError(env, err);
}

#define DEFINE_PARCEL_ARRAY_WRITER(Suffix,Type)                                \
static void JHwParcel_native_write ## Suffix ## Array(                         \
        JNIEnv *env, jobject thiz, jint size, Type ## Array valObj) {          \
    if (valObj == NULL) {                                                      \
        jniThrowException(env, "java/lang/NullPointerException", NULL);        \
        return;                                                                \
    }                                                                          \
                                                                               \
    jsize len = env->GetArrayLength(valObj);                                   \
                                                                               \
    if (len != size) {                                                         \
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);    \
        return;                                                                \
    }                                                                          \
                                                                               \
    sp<JHwParcel> impl = JHwParcel::GetNativeContext(env, thiz);               \
                                                                               \
    const Type *val =                                                          \
        impl->getStorage()->allocTemporary ## Suffix ## Array(env, valObj);    \
                                                                               \
    hardware::Parcel *parcel = impl->getParcel();                              \
                                                                               \
    size_t parentHandle;                                                       \
    status_t err = parcel->writeBuffer(                                        \
            val, size * sizeof(*val), &parentHandle);                          \
                                                                               \
    signalExceptionForError(env, err);                                         \
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
        err = vec->writeEmbeddedToParcel(                                      \
                parcel,                                                        \
                parentHandle,                                                  \
                0 /* parentOffset */,                                          \
                &childHandle);                                                 \
    }                                                                          \
                                                                               \
    signalExceptionForError(env, err);                                         \
}

DEFINE_PARCEL_ARRAY_WRITER(Int8,jbyte)
DEFINE_PARCEL_ARRAY_WRITER(Int16,jshort)
DEFINE_PARCEL_ARRAY_WRITER(Int32,jint)
DEFINE_PARCEL_ARRAY_WRITER(Int64,jlong)
DEFINE_PARCEL_ARRAY_WRITER(Float,jfloat)
DEFINE_PARCEL_ARRAY_WRITER(Double,jdouble)

DEFINE_PARCEL_VECTOR_WRITER(Int8,jbyte)
DEFINE_PARCEL_VECTOR_WRITER(Int16,jshort)
DEFINE_PARCEL_VECTOR_WRITER(Int32,jint)
DEFINE_PARCEL_VECTOR_WRITER(Int64,jlong)
DEFINE_PARCEL_VECTOR_WRITER(Float,jfloat)
DEFINE_PARCEL_VECTOR_WRITER(Double,jdouble)

static void JHwParcel_native_writeStrongBinder(
        JNIEnv *env, jobject thiz, jobject binderObj) {
    sp<hardware::IBinder> binder;
    if (binderObj != NULL) {
        ScopedLocalRef<jclass> hwBinderKlass(
                env, FindClassOrDie(env, PACKAGE_PATH "/HwBinder"));

        ScopedLocalRef<jclass> hwRemoteBinderKlass(
                env, FindClassOrDie(env, PACKAGE_PATH "/HwRemoteBinder"));

        if (env->IsInstanceOf(binderObj, hwBinderKlass.get())) {
            binder = JHwBinder::GetNativeContext(env, binderObj);
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

    const hidl_string *s = static_cast<const hidl_string *>(
            parcel->readBuffer(&parentHandle));

    if (s == NULL) {
        signalExceptionForError(env, UNKNOWN_ERROR);
        return NULL;
    }

    status_t err = const_cast<hidl_string *>(s)->readEmbeddedFromParcel(
            *parcel, parentHandle, 0 /* parentOffset */);

    if (err != OK) {
        signalExceptionForError(env, err);
        return NULL;
    }

    return MakeStringObjFromHidlString(env, *s);
}

#define DEFINE_PARCEL_ARRAY_READER(Suffix,Type,NewType)                        \
static Type ## Array JHwParcel_native_read ## Suffix ## Array(                 \
        JNIEnv *env, jobject thiz, jint size) {                                \
    hardware::Parcel *parcel =                                                 \
        JHwParcel::GetNativeContext(env, thiz)->getParcel();                   \
                                                                               \
    size_t parentHandle;                                                       \
    const Type *val = static_cast<const Type *>(                               \
            parcel->readBuffer(&parentHandle));                                \
                                                                               \
    Type ## Array valObj = env->New ## NewType ## Array(size);                 \
    env->Set ## NewType ## ArrayRegion(valObj, 0, size, val);                  \
                                                                               \
    return valObj;                                                             \
}

#define DEFINE_PARCEL_VECTOR_READER(Suffix,Type,NewType)                       \
static Type ## Array JHwParcel_native_read ## Suffix ## Vector(                \
        JNIEnv *env, jobject thiz) {                                           \
    hardware::Parcel *parcel =                                                 \
        JHwParcel::GetNativeContext(env, thiz)->getParcel();                   \
                                                                               \
    size_t parentHandle;                                                       \
                                                                               \
    const hidl_vec<Type> *vec =                                                \
        (const hidl_vec<Type> *)parcel->readBuffer(&parentHandle);             \
                                                                               \
    if (vec == NULL) {                                                         \
        signalExceptionForError(env, UNKNOWN_ERROR);                           \
        return NULL;                                                           \
    }                                                                          \
                                                                               \
    size_t childHandle;                                                        \
                                                                               \
    status_t err = const_cast<hidl_vec<Type> *>(vec)                           \
        ->readEmbeddedFromParcel(                                              \
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

DEFINE_PARCEL_ARRAY_READER(Int8,jbyte,Byte)
DEFINE_PARCEL_ARRAY_READER(Int16,jshort,Short)
DEFINE_PARCEL_ARRAY_READER(Int32,jint,Int)
DEFINE_PARCEL_ARRAY_READER(Int64,jlong,Long)
DEFINE_PARCEL_ARRAY_READER(Float,jfloat,Float)
DEFINE_PARCEL_ARRAY_READER(Double,jdouble,Double)

DEFINE_PARCEL_VECTOR_READER(Int8,jbyte,Byte)
DEFINE_PARCEL_VECTOR_READER(Int16,jshort,Short)
DEFINE_PARCEL_VECTOR_READER(Int32,jint,Int)
DEFINE_PARCEL_VECTOR_READER(Int64,jlong,Long)
DEFINE_PARCEL_VECTOR_READER(Float,jfloat,Float)
DEFINE_PARCEL_VECTOR_READER(Double,jdouble,Double)

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

static jobjectArray JHwParcel_native_readStringArray(
        JNIEnv *env, jobject thiz, jint size) {
    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    size_t parentHandle;
    const hidl_string *val = static_cast<const hidl_string *>(
            parcel->readBuffer(&parentHandle));

    if (val == NULL) {
        signalExceptionForError(env, UNKNOWN_ERROR);
        return NULL;
    }

    status_t err = OK;
    for (jint i = 0; (err == OK) && (i < size); ++i) {
        err = const_cast<hidl_string *>(&val[i])
            ->readEmbeddedFromParcel(
                    *parcel,
                    parentHandle,
                    i * sizeof(hidl_string));
    }

    if (err != OK) {
        signalExceptionForError(env, err);
        return NULL;
    }

    return MakeStringArray(env, val, size);
}

static void JHwParcel_native_writeStringArray(
        JNIEnv *env, jobject thiz, jint size, jobjectArray arrayObj) {
    if (arrayObj == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    jsize len = env->GetArrayLength(arrayObj);

    if (len != size) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    sp<JHwParcel> impl = JHwParcel::GetNativeContext(env, thiz);

    hidl_string *strings = impl->getStorage()->allocStringArray(len);

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
    status_t err = parcel->writeBuffer(
            strings, sizeof(hidl_string) * len, &parentHandle);

    for (jsize i = 0; (err == OK) && (i < len); ++i) {
        err = strings[i].writeEmbeddedToParcel(
                parcel, parentHandle, i * sizeof(hidl_string));
    }

    signalExceptionForError(env, err);
}

static jobjectArray JHwParcel_native_readStringVector(
        JNIEnv *env, jobject thiz) {
    typedef hidl_vec<hidl_string> string_vec;

    hardware::Parcel *parcel =
        JHwParcel::GetNativeContext(env, thiz)->getParcel();

    size_t parentHandle;

    const string_vec *vec=
        (const string_vec *)parcel->readBuffer(&parentHandle);

    if (vec == NULL) {
        signalExceptionForError(env, UNKNOWN_ERROR);
        return NULL;
    }

    size_t childHandle;
    status_t err = const_cast<string_vec *>(vec)->readEmbeddedFromParcel(
            *parcel, parentHandle, 0 /* parentOffset */, &childHandle);

    for (size_t i = 0; (err == OK) && (i < vec->size()); ++i) {
        err = const_cast<hidl_vec<hidl_string> *>(vec)
            ->readEmbeddedFromParcel(
                    *parcel,
                    childHandle,
                    i * sizeof(hidl_string),
                    nullptr /* childHandle */);
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

    string_vec *vec =
        (string_vec *)impl->getStorage()->allocTemporaryStorage(
                sizeof(string_vec));

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
        err = vec->writeEmbeddedToParcel(
                parcel,
                parentHandle,
                0 /* parentOffset */,
                &childHandle);

        for (size_t i = 0; (err == OK) && (i < vec->size()); ++i) {
            err = (*vec)[i].writeEmbeddedToParcel(
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

static JNINativeMethod gMethods[] = {
    { "native_init", "()J", (void *)JHwParcel_native_init },
    { "native_setup", "(Z)V", (void *)JHwParcel_native_setup },

    { "writeInterfaceToken", "(Ljava/lang/String;)V",
        (void *)JHwParcel_native_writeInterfaceToken },

    { "writeInt8", "(B)V", (void *)JHwParcel_native_writeInt8 },
    { "writeInt16", "(S)V", (void *)JHwParcel_native_writeInt16 },
    { "writeInt32", "(I)V", (void *)JHwParcel_native_writeInt32 },
    { "writeInt64", "(J)V", (void *)JHwParcel_native_writeInt64 },
    { "writeFloat", "(F)V", (void *)JHwParcel_native_writeFloat },
    { "writeDouble", "(D)V", (void *)JHwParcel_native_writeDouble },

    { "writeString", "(Ljava/lang/String;)V",
        (void *)JHwParcel_native_writeString },

    { "writeInt8Array", "(I[B)V", (void *)JHwParcel_native_writeInt8Array },
    { "writeInt8Vector", "([B)V", (void *)JHwParcel_native_writeInt8Vector },
    { "writeInt16Array", "(I[S)V", (void *)JHwParcel_native_writeInt16Array },
    { "writeInt16Vector", "([S)V", (void *)JHwParcel_native_writeInt16Vector },
    { "writeInt32Array", "(I[I)V", (void *)JHwParcel_native_writeInt32Array },
    { "writeInt32Vector", "([I)V", (void *)JHwParcel_native_writeInt32Vector },
    { "writeInt64Array", "(I[J)V", (void *)JHwParcel_native_writeInt64Array },
    { "writeInt64Vector", "([J)V", (void *)JHwParcel_native_writeInt64Vector },
    { "writeFloatArray", "(I[F)V", (void *)JHwParcel_native_writeFloatArray },
    { "writeFloatVector", "([F)V", (void *)JHwParcel_native_writeFloatVector },
    { "writeDoubleArray", "(I[D)V", (void *)JHwParcel_native_writeDoubleArray },

    { "writeDoubleVector", "([D)V",
        (void *)JHwParcel_native_writeDoubleVector },

    { "writeStringArray", "(I[Ljava/lang/String;)V",
        (void *)JHwParcel_native_writeStringArray },

    { "writeStringVector", "([Ljava/lang/String;)V",
        (void *)JHwParcel_native_writeStringVector },

    { "writeStrongBinder", "(L" PACKAGE_PATH "/IHwBinder;)V",
        (void *)JHwParcel_native_writeStrongBinder },

    { "enforceInterface", "(Ljava/lang/String;)V",
        (void *)JHwParcel_native_enforceInterface },

    { "readInt8", "()B", (void *)JHwParcel_native_readInt8 },
    { "readInt16", "()S", (void *)JHwParcel_native_readInt16 },
    { "readInt32", "()I", (void *)JHwParcel_native_readInt32 },
    { "readInt64", "()J", (void *)JHwParcel_native_readInt64 },
    { "readFloat", "()F", (void *)JHwParcel_native_readFloat },
    { "readDouble", "()D", (void *)JHwParcel_native_readDouble },

    { "readString", "()Ljava/lang/String;",
        (void *)JHwParcel_native_readString },

    { "readInt8Array", "(I)[B", (void *)JHwParcel_native_readInt8Array },
    { "readInt8Vector", "()[B", (void *)JHwParcel_native_readInt8Vector },
    { "readInt16Array", "(I)[S", (void *)JHwParcel_native_readInt16Array },
    { "readInt16Vector", "()[S", (void *)JHwParcel_native_readInt16Vector },
    { "readInt32Array", "(I)[I", (void *)JHwParcel_native_readInt32Array },
    { "readInt32Vector", "()[I", (void *)JHwParcel_native_readInt32Vector },
    { "readInt64Array", "(I)[J", (void *)JHwParcel_native_readInt64Array },
    { "readInt64Vector", "()[J", (void *)JHwParcel_native_readInt64Vector },
    { "readFloatArray", "(I)[F", (void *)JHwParcel_native_readFloatArray },
    { "readFloatVector", "()[F", (void *)JHwParcel_native_readFloatVector },
    { "readDoubleArray", "(I)[D", (void *)JHwParcel_native_readDoubleArray },
    { "readDoubleVector", "()[D", (void *)JHwParcel_native_readDoubleVector },

    { "readStringArray", "(I)[Ljava/lang/String;",
        (void *)JHwParcel_native_readStringArray },

    { "readStringVector", "()[Ljava/lang/String;",
        (void *)JHwParcel_native_readStringVector },

    { "readStrongBinder", "()L" PACKAGE_PATH "/IHwBinder;",
        (void *)JHwParcel_native_readStrongBinder },

    { "writeStatus", "(I)V", (void *)JHwParcel_native_writeStatus },

    { "verifySuccess", "()V", (void *)JHwParcel_native_verifySuccess },

    { "releaseTemporaryStorage", "()V",
        (void *)JHwParcel_native_releaseTemporaryStorage },

    { "send", "()V", (void *)JHwParcel_native_send },
};

namespace android {

int register_android_os_HwParcel(JNIEnv *env) {
    return RegisterMethodsOrDie(env, CLASS_PATH, gMethods, NELEM(gMethods));
}

}  // namespace android

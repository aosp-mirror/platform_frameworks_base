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
#undef ANDROID_UTILS_REF_BASE_DISABLE_IMPLICIT_CONSTRUCTION // TODO:remove this and fix code

//#define LOG_NDEBUG 0
#define LOG_TAG "android_os_HwBlob"
#include <android-base/logging.h>

#include "android_os_HwBlob.h"

#include "android_os_HwParcel.h"
#include "android_os_NativeHandle.h"

#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <hidl/Status.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include "core_jni_helpers.h"

using android::AndroidRuntime;
using android::hardware::hidl_handle;
using android::hardware::hidl_string;

#define PACKAGE_PATH    "android/os"
#define CLASS_NAME      "HwBlob"
#define CLASS_PATH      PACKAGE_PATH "/" CLASS_NAME

namespace android {

static struct fields_t {
    jfieldID contextID;
    jmethodID constructID;

} gFields;

// static
void JHwBlob::InitClass(JNIEnv *env) {
    ScopedLocalRef<jclass> clazz(
            env, FindClassOrDie(env, CLASS_PATH));

    gFields.contextID =
        GetFieldIDOrDie(env, clazz.get(), "mNativeContext", "J");

    gFields.constructID = GetMethodIDOrDie(env, clazz.get(), "<init>", "(I)V");
}

// static
sp<JHwBlob> JHwBlob::SetNativeContext(
        JNIEnv *env, jobject thiz, const sp<JHwBlob> &context) {
    sp<JHwBlob> old = (JHwBlob *)env->GetLongField(thiz, gFields.contextID);

    if (context != nullptr) {
        context->incStrong(nullptr /* id */);
    }

    if (old != nullptr) {
        old->decStrong(nullptr /* id */);
    }

    env->SetLongField(thiz, gFields.contextID, (long)context.get());

    return old;
}

// static
sp<JHwBlob> JHwBlob::GetNativeContext(JNIEnv *env, jobject thiz) {
    return (JHwBlob *)env->GetLongField(thiz, gFields.contextID);
}

JHwBlob::JHwBlob(JNIEnv *env, jobject thiz, size_t size)
    : mBuffer(nullptr),
      mSize(size),
      mType(BlobType::GENERIC),
      mOwnsBuffer(true),
      mHandle(0) {
    if (size > 0) {
        mBuffer = calloc(size, 1);
    }
}

JHwBlob::~JHwBlob() {
    if (mOwnsBuffer) {
        free(mBuffer);
        mBuffer = nullptr;
    }
}

void JHwBlob::setTo(const void *ptr, size_t handle) {
    CHECK_EQ(mSize, 0u);
    CHECK(mBuffer == nullptr);

    mBuffer = const_cast<void *>(ptr);
    mSize = SIZE_MAX;  // XXX
    mOwnsBuffer = false;
    mHandle = handle;
}

status_t JHwBlob::getHandle(size_t *handle) const {
    if (mOwnsBuffer) {
        return INVALID_OPERATION;
    }

    *handle = mHandle;

    return OK;
}

status_t JHwBlob::read(size_t offset, void *data, size_t size) const {
    if (offset + size > mSize) {
        return -ERANGE;
    }

    memcpy(data, (const uint8_t *)mBuffer + offset, size);

    return OK;
}

status_t JHwBlob::write(size_t offset, const void *data, size_t size) {
    if (offset + size > mSize) {
        return -ERANGE;
    }

    memcpy((uint8_t *)mBuffer + offset, data, size);

    return OK;
}

status_t JHwBlob::getString(size_t offset, const hidl_string **s) const {
    if ((offset + sizeof(hidl_string)) > mSize) {
        return -ERANGE;
    }

    *s = reinterpret_cast<const hidl_string *>(
            (const uint8_t *)mBuffer + offset);

    return OK;
}

const void *JHwBlob::data() const {
    return mBuffer;
}

void *JHwBlob::data() {
    return mBuffer;
}

size_t JHwBlob::size() const {
    return mSize;
}

void JHwBlob::specializeBlobTo(BlobType type) {
    CHECK_EQ(static_cast<int>(mType), static_cast<int>(BlobType::GENERIC));
    mType = type;
}

JHwBlob::BlobType JHwBlob::type() const {
    return mType;
}

status_t JHwBlob::putBlob(size_t offset, const sp<JHwBlob> &blob) {
    size_t index = mSubBlobs.add();
    BlobInfo *info = &mSubBlobs.editItemAt(index);

    info->mOffset = offset;
    info->mBlob = blob;

    const void *data = blob->data();

    return write(offset, &data, sizeof(data));
}

status_t JHwBlob::writeToParcel(hardware::Parcel *parcel) const {
    CHECK_EQ(static_cast<int>(mType), static_cast<int>(BlobType::GENERIC));

    size_t handle = 0;
    status_t err = parcel->writeBuffer(data(), size(), &handle);

    if (err != OK) {
        return err;
    }

    return writeSubBlobsToParcel(parcel, handle);
}

status_t JHwBlob::writeEmbeddedToParcel(
        hardware::Parcel *parcel,
        size_t parentHandle,
        size_t parentOffset) const {
    size_t handle = 0;
    status_t err = OK;

    switch (mType) {
        case BlobType::GENERIC: {
            err = parcel->writeEmbeddedBuffer(data(), size(), &handle, parentHandle, parentOffset);
            break;
        }
        case BlobType::NATIVE_HANDLE: {
            err = parcel->writeEmbeddedNativeHandle(
                    static_cast<const native_handle *>(data()), parentHandle, parentOffset);

            CHECK(mSubBlobs.empty());
            break;
        }
        default: { err = INVALID_OPERATION; }
    }

    if (err != OK) {
        return err;
    }

    return writeSubBlobsToParcel(parcel, handle);
}

status_t JHwBlob::writeSubBlobsToParcel(hardware::Parcel *parcel,
        size_t parentHandle) const {
    for (size_t i = 0; i < mSubBlobs.size(); ++i) {
        const BlobInfo &info = mSubBlobs[i];
        status_t err = info.mBlob->writeEmbeddedToParcel(parcel, parentHandle, info.mOffset);

        if (err != OK) {
            return err;
        }
    }

    return OK;
}

// static
jobject JHwBlob::NewObject(JNIEnv *env, const void *ptr, size_t handle) {
    jobject obj = JHwBlob::NewObject(env, 0 /* size */);
    JHwBlob::GetNativeContext(env, obj)->setTo(ptr, handle);

    return obj;
}

// static
jobject JHwBlob::NewObject(JNIEnv *env, size_t size) {
    ScopedLocalRef<jclass> clazz(env, FindClassOrDie(env, CLASS_PATH));

    jmethodID constructID =
        GetMethodIDOrDie(env, clazz.get(), "<init>", "(I)V");

    // XXX Again cannot refer to gFields.constructID because InitClass may
    // not have been called yet.

    // Cases:
    // - this originates from another process (something so large should not fit
    //   in the binder buffer, and it should be rejected by the binder driver)
    // - if this is used in process, this code makes too many heap copies (in
    //   order to retrofit HIDL's scatter-gather format to java types) to
    //   justify passing such a large amount of data over this path. So the
    //   alternative (updating the constructor and other code to accept other
    //   types, should also probably not be taken in this case).
    CHECK_LE(size, static_cast<size_t>(std::numeric_limits<jint>::max()));

    return env->NewObject(clazz.get(), constructID, static_cast<jint>(size));
}

}  // namespace android

////////////////////////////////////////////////////////////////////////////////

using namespace android;

static void releaseNativeContext(void *nativeContext) {
    sp<JHwBlob> parcel = (JHwBlob *)nativeContext;

    if (parcel != nullptr) {
        parcel->decStrong(nullptr /* id */);
    }
}

static jlong JHwBlob_native_init(JNIEnv *env, jclass /*clazz*/) {
    JHwBlob::InitClass(env);

    return reinterpret_cast<jlong>(&releaseNativeContext);
}

static void JHwBlob_native_setup(
        JNIEnv *env, jobject thiz, jint size) {
    sp<JHwBlob> context = new JHwBlob(env, thiz, size);

    JHwBlob::SetNativeContext(env, thiz, context);
}

#define DEFINE_BLOB_GETTER(Suffix,Type)                                        \
static Type JHwBlob_native_get ## Suffix(                                      \
        JNIEnv *env, jobject thiz, jlong offset) {                             \
    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);                   \
                                                                               \
    Type x;                                                                    \
    status_t err = blob->read(offset, &x, sizeof(x));                          \
                                                                               \
    if (err != OK) {                                                           \
        signalExceptionForError(env, err);                                     \
        return 0;                                                              \
    }                                                                          \
                                                                               \
    return x;                                                                  \
}

DEFINE_BLOB_GETTER(Int8,jbyte)
DEFINE_BLOB_GETTER(Int16,jshort)
DEFINE_BLOB_GETTER(Int32,jint)
DEFINE_BLOB_GETTER(Int64,jlong)
DEFINE_BLOB_GETTER(Float,jfloat)
DEFINE_BLOB_GETTER(Double,jdouble)

static jboolean JHwBlob_native_getBool(
        JNIEnv *env, jobject thiz, jlong offset) {
    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);

    bool x;
    status_t err = blob->read(offset, &x, sizeof(x));

    if (err != OK) {
        signalExceptionForError(env, err);
        return 0;
    }

    return (jboolean)x;
}

static jstring JHwBlob_native_getString(
        JNIEnv *env, jobject thiz, jlong offset) {
    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);

    const hidl_string *s;
    status_t err = blob->getString(offset, &s);

    if (err != OK) {
        signalExceptionForError(env, err);
        return nullptr;
    }

    return env->NewStringUTF(s->c_str());
}

static jlong JHwBlob_native_getFieldHandle(JNIEnv* env,
                                           jobject thiz,
                                           jlong offset) {
    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);

    return reinterpret_cast<jlong>(blob->data()) + offset;
}

#define DEFINE_BLOB_ARRAY_COPIER(Suffix,Type,NewType)                          \
static void JHwBlob_native_copyTo ## Suffix ## Array(                          \
        JNIEnv *env,                                                           \
        jobject thiz,                                                          \
        jlong offset,                                                          \
        Type ## Array array,                                                   \
        jint size) {                                                           \
    if (array == nullptr) {                                                    \
        jniThrowException(env, "java/lang/NullPointerException", nullptr);     \
        return;                                                                \
    }                                                                          \
                                                                               \
    if (env->GetArrayLength(array) < size) {                                   \
        signalExceptionForError(env, BAD_VALUE);                               \
        return;                                                                \
    }                                                                          \
                                                                               \
    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);                   \
                                                                               \
    if ((offset + size * sizeof(Type)) > blob->size()) {                       \
        signalExceptionForError(env, -ERANGE);                                 \
        return;                                                                \
    }                                                                          \
                                                                               \
    env->Set ## NewType ## ArrayRegion(                                        \
            array,                                                             \
            0 /* start */,                                                     \
            size,                                                              \
            reinterpret_cast<const Type *>(                                    \
                static_cast<const uint8_t *>(blob->data()) + offset));         \
}

DEFINE_BLOB_ARRAY_COPIER(Int8,jbyte,Byte)
DEFINE_BLOB_ARRAY_COPIER(Int16,jshort,Short)
DEFINE_BLOB_ARRAY_COPIER(Int32,jint,Int)
DEFINE_BLOB_ARRAY_COPIER(Int64,jlong,Long)
DEFINE_BLOB_ARRAY_COPIER(Float,jfloat,Float)
DEFINE_BLOB_ARRAY_COPIER(Double,jdouble,Double)

static void JHwBlob_native_copyToBoolArray(
        JNIEnv *env,
        jobject thiz,
        jlong offset,
        jbooleanArray array,
        jint size) {
    if (array == nullptr) {
        jniThrowException(env, "java/lang/NullPointerException", nullptr);
        return;
    }

    if (env->GetArrayLength(array) < size) {
        signalExceptionForError(env, BAD_VALUE);
        return;
    }

    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);

    if ((offset + size * sizeof(bool)) > blob->size()) {
        signalExceptionForError(env, -ERANGE);
        return;
    }

    const bool *src =
        reinterpret_cast<const bool *>(
                static_cast<const uint8_t *>(blob->data()) + offset);

    jboolean *dst = env->GetBooleanArrayElements(array, nullptr /* isCopy */);

    for (jint i = 0; i < size; ++i) {
        dst[i] = src[i];
    }

    env->ReleaseBooleanArrayElements(array, dst, 0 /* mode */);
    dst = nullptr;
}

#define DEFINE_BLOB_PUTTER(Suffix,Type)                                        \
static void JHwBlob_native_put ## Suffix(                                      \
        JNIEnv *env, jobject thiz, jlong offset, Type x) {                     \
                                                                               \
    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);                   \
                                                                               \
    status_t err = blob->write(offset, &x, sizeof(x));                         \
                                                                               \
    if (err != OK) {                                                           \
        signalExceptionForError(env, err);                                     \
    }                                                                          \
}

DEFINE_BLOB_PUTTER(Int8,jbyte)
DEFINE_BLOB_PUTTER(Int16,jshort)
DEFINE_BLOB_PUTTER(Int32,jint)
DEFINE_BLOB_PUTTER(Int64,jlong)
DEFINE_BLOB_PUTTER(Float,jfloat)
DEFINE_BLOB_PUTTER(Double,jdouble)

static void JHwBlob_native_putBool(
        JNIEnv *env, jobject thiz, jlong offset, jboolean x) {

    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);

    bool b = (bool)x;
    status_t err = blob->write(offset, &b, sizeof(b));

    if (err != OK) {
        signalExceptionForError(env, err);
    }
}

static void JHwBlob_native_putString(
        JNIEnv *env, jobject thiz, jlong offset, jstring stringObj) {
    if (stringObj == nullptr) {
        jniThrowException(env, "java/lang/NullPointerException", nullptr);
        return;
    }

    const char *s = env->GetStringUTFChars(stringObj, nullptr);

    if (s == nullptr) {
        return;
    }

    size_t size = strlen(s) + 1;
    ScopedLocalRef<jobject> subBlobObj(env, JHwBlob::NewObject(env, size));
    sp<JHwBlob> subBlob = JHwBlob::GetNativeContext(env, subBlobObj.get());
    subBlob->write(0 /* offset */, s, size);

    env->ReleaseStringUTFChars(stringObj, s);
    s = nullptr;

    hidl_string tmp;
    tmp.setToExternal(static_cast<const char *>(subBlob->data()), size - 1);

    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);
    blob->write(offset, &tmp, sizeof(tmp));
    blob->putBlob(offset + hidl_string::kOffsetOfBuffer, subBlob);
}

static void JHwBlob_native_putNativeHandle(JNIEnv *env, jobject thiz,
        jlong offset, jobject jHandle) {
    std::unique_ptr<native_handle_t, int(*)(native_handle_t*)> nativeHandle(
            JNativeHandle::MakeCppNativeHandle(env, jHandle, nullptr /* storage */),
            native_handle_delete);

    size_t size = 0;
    if (nativeHandle != nullptr) {
        size = sizeof(native_handle_t) + nativeHandle->numFds * sizeof(int)
               + nativeHandle->numInts * sizeof(int);
    }

    ScopedLocalRef<jobject> subBlobObj(env, JHwBlob::NewObject(env, size));
    sp<JHwBlob> subBlob = JHwBlob::GetNativeContext(env, subBlobObj.get());
    subBlob->specializeBlobTo(JHwBlob::BlobType::NATIVE_HANDLE);
    subBlob->write(0 /* offset */, nativeHandle.get(), size);

    hidl_handle cppHandle;
    cppHandle.setTo(static_cast<native_handle_t *>(subBlob->data()), false /* shouldOwn */);

    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);
    blob->write(offset, &cppHandle, sizeof(cppHandle));
    blob->putBlob(offset + hidl_handle::kOffsetOfNativeHandle, subBlob);
}

#define DEFINE_BLOB_ARRAY_PUTTER(Suffix,Type,NewType)                          \
static void JHwBlob_native_put ## Suffix ## Array(                             \
        JNIEnv *env, jobject thiz, jlong offset, Type ## Array array) {        \
    Scoped ## NewType ## ArrayRO autoArray(env, array);                        \
                                                                               \
    if (array == nullptr) {                                                    \
        /* NullpointerException already pending */                             \
        return;                                                                \
    }                                                                          \
                                                                               \
    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);                   \
                                                                               \
    status_t err = blob->write(                                                \
            offset, autoArray.get(), autoArray.size() * sizeof(Type));         \
                                                                               \
    if (err != OK) {                                                           \
        signalExceptionForError(env, err);                                     \
    }                                                                          \
}

DEFINE_BLOB_ARRAY_PUTTER(Int8,jbyte,Byte)
DEFINE_BLOB_ARRAY_PUTTER(Int16,jshort,Short)
DEFINE_BLOB_ARRAY_PUTTER(Int32,jint,Int)
DEFINE_BLOB_ARRAY_PUTTER(Int64,jlong,Long)
DEFINE_BLOB_ARRAY_PUTTER(Float,jfloat,Float)
DEFINE_BLOB_ARRAY_PUTTER(Double,jdouble,Double)

static void JHwBlob_native_putBoolArray(
        JNIEnv *env, jobject thiz, jlong offset, jbooleanArray array) {
    ScopedBooleanArrayRO autoArray(env, array);

    if (array == nullptr) {
        /* NullpointerException already pending */
        return;
    }

    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);

    if ((offset + autoArray.size() * sizeof(bool)) > blob->size()) {
        signalExceptionForError(env, -ERANGE);
        return;
    }

    const jboolean *src = autoArray.get();

    bool *dst = reinterpret_cast<bool *>(
            static_cast<uint8_t *>(blob->data()) + offset);

    for (size_t i = 0; i < autoArray.size(); ++i) {
        dst[i] = src[i];
    }
}

static void JHwBlob_native_putBlob(
        JNIEnv *env, jobject thiz, jlong offset, jobject blobObj) {
    if (blobObj == nullptr) {
        jniThrowException(env, "java/lang/NullPointerException", nullptr);
        return;
    }

    sp<JHwBlob> blob = JHwBlob::GetNativeContext(env, thiz);
    sp<JHwBlob> subBlob = JHwBlob::GetNativeContext(env, blobObj);

    blob->putBlob(offset, subBlob);
}

static jlong JHwBlob_native_handle(JNIEnv *env, jobject thiz) {
    size_t handle;
    status_t err = JHwBlob::GetNativeContext(env, thiz)->getHandle(&handle);

    if (err != OK) {
        signalExceptionForError(env, err);
        return 0;
    }

    return handle;
}

static JNINativeMethod gMethods[] = {
    { "native_init", "()J", (void *)JHwBlob_native_init },
    { "native_setup", "(I)V", (void *)JHwBlob_native_setup },

    { "getBool", "(J)Z", (void *)JHwBlob_native_getBool },
    { "getInt8", "(J)B", (void *)JHwBlob_native_getInt8 },
    { "getInt16", "(J)S", (void *)JHwBlob_native_getInt16 },
    { "getInt32", "(J)I", (void *)JHwBlob_native_getInt32 },
    { "getInt64", "(J)J", (void *)JHwBlob_native_getInt64 },
    { "getFloat", "(J)F", (void *)JHwBlob_native_getFloat },
    { "getDouble", "(J)D", (void *)JHwBlob_native_getDouble },
    { "getString", "(J)Ljava/lang/String;", (void *)JHwBlob_native_getString },
    { "getFieldHandle", "(J)J", (void*) JHwBlob_native_getFieldHandle},

    { "copyToBoolArray", "(J[ZI)V", (void *)JHwBlob_native_copyToBoolArray },
    { "copyToInt8Array", "(J[BI)V", (void *)JHwBlob_native_copyToInt8Array },
    { "copyToInt16Array", "(J[SI)V", (void *)JHwBlob_native_copyToInt16Array },
    { "copyToInt32Array", "(J[II)V", (void *)JHwBlob_native_copyToInt32Array },
    { "copyToInt64Array", "(J[JI)V", (void *)JHwBlob_native_copyToInt64Array },
    { "copyToFloatArray", "(J[FI)V", (void *)JHwBlob_native_copyToFloatArray },
    { "copyToDoubleArray", "(J[DI)V", (void *)JHwBlob_native_copyToDoubleArray },

    { "putBool", "(JZ)V", (void *)JHwBlob_native_putBool },
    { "putInt8", "(JB)V", (void *)JHwBlob_native_putInt8 },
    { "putInt16", "(JS)V", (void *)JHwBlob_native_putInt16 },
    { "putInt32", "(JI)V", (void *)JHwBlob_native_putInt32 },
    { "putInt64", "(JJ)V", (void *)JHwBlob_native_putInt64 },
    { "putFloat", "(JF)V", (void *)JHwBlob_native_putFloat },
    { "putDouble", "(JD)V", (void *)JHwBlob_native_putDouble },
    { "putString", "(JLjava/lang/String;)V", (void *)JHwBlob_native_putString },
    { "putNativeHandle", "(JL" PACKAGE_PATH "/NativeHandle;)V",
        (void*)JHwBlob_native_putNativeHandle },

    { "putBoolArray", "(J[Z)V", (void *)JHwBlob_native_putBoolArray },
    { "putInt8Array", "(J[B)V", (void *)JHwBlob_native_putInt8Array },
    { "putInt16Array", "(J[S)V", (void *)JHwBlob_native_putInt16Array },
    { "putInt32Array", "(J[I)V", (void *)JHwBlob_native_putInt32Array },
    { "putInt64Array", "(J[J)V", (void *)JHwBlob_native_putInt64Array },
    { "putFloatArray", "(J[F)V", (void *)JHwBlob_native_putFloatArray },
    { "putDoubleArray", "(J[D)V", (void *)JHwBlob_native_putDoubleArray },

    { "putBlob", "(JL" PACKAGE_PATH "/HwBlob;)V",
        (void *)JHwBlob_native_putBlob },

    { "handle", "()J", (void *)JHwBlob_native_handle },
};

namespace android {

int register_android_os_HwBlob(JNIEnv *env) {
    return RegisterMethodsOrDie(env, CLASS_PATH, gMethods, NELEM(gMethods));
}

}  // namespace android


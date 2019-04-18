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
#define LOG_TAG "android_os_HwBlob"
#include <android-base/logging.h>

#include "android_os_HwBlob.h"

#include "android_os_HwParcel.h"

#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <hidl/Status.h>
#include <nativehelper/ScopedLocalRef.h>

#include "core_jni_helpers.h"

using android::AndroidRuntime;
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
sp<JHwBlob> JHwBlob::GetNativeContext(JNIEnv *env, jobject thiz) {
    return (JHwBlob *)env->GetLongField(thiz, gFields.contextID);
}

JHwBlob::JHwBlob(JNIEnv *env, jobject thiz, size_t size)
    : mBuffer(nullptr),
      mSize(size),
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

size_t JHwBlob::size() const {
    return mSize;
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
    size_t handle;
    status_t err = parcel->writeBuffer(data(), size(), &handle);

    if (err != OK) {
        return err;
    }

    for (size_t i = 0; i < mSubBlobs.size(); ++i) {
        const BlobInfo &info = mSubBlobs[i];

        err = info.mBlob->writeEmbeddedToParcel(parcel, handle, info.mOffset);

        if (err != OK) {
            return err;
        }
    }

    return OK;
}

status_t JHwBlob::writeEmbeddedToParcel(
        hardware::Parcel *parcel,
        size_t parentHandle,
        size_t parentOffset) const {
    size_t handle;
    status_t err = parcel->writeEmbeddedBuffer(
            data(), size(), &handle, parentHandle, parentOffset);

    if (err != OK) {
        return err;
    }

    for (size_t i = 0; i < mSubBlobs.size(); ++i) {
        const BlobInfo &info = mSubBlobs[i];

        err = info.mBlob->writeEmbeddedToParcel(parcel, handle, info.mOffset);

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

    return env->NewObject(clazz.get(), constructID, size);
}

}  // namespace android

////////////////////////////////////////////////////////////////////////////////

using namespace android;

static void releaseNativeContext(void *nativeContext) {
    sp<JHwBlob> parcel = (JHwBlob *)nativeContext;

    if (parcel != NULL) {
        parcel->decStrong(NULL /* id */);
    }
}

static jlong JHwBlob_native_init(JNIEnv *env) {
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

    { "putBool", "(JZ)V", (void *)JHwBlob_native_putBool },
    { "putInt8", "(JB)V", (void *)JHwBlob_native_putInt8 },
    { "putInt16", "(JS)V", (void *)JHwBlob_native_putInt16 },
    { "putInt32", "(JI)V", (void *)JHwBlob_native_putInt32 },
    { "putInt64", "(JJ)V", (void *)JHwBlob_native_putInt64 },
    { "putFloat", "(JF)V", (void *)JHwBlob_native_putFloat },
    { "putDouble", "(JD)V", (void *)JHwBlob_native_putDouble },
    { "putString", "(JLjava/lang/String;)V", (void *)JHwBlob_native_putString },

    { "putBlob", "(JL" PACKAGE_PATH "/HwBlob;)V",
        (void *)JHwBlob_native_putBlob },

    { "handle", "()J", (void *)JHwBlob_native_handle },
};

namespace android {

int register_android_os_HwBlob(JNIEnv *env) {
    return RegisterMethodsOrDie(env, CLASS_PATH, gMethods, NELEM(gMethods));
}

}  // namespace android


/*
 * Copyright 2019, The Android Open Source Project
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

// #define LOG_NDEBUG 0
#define LOG_TAG "AndroidMediaStreams"

#include <utils/Log.h>
#include "android_media_Streams.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/AMessage.h>

#include <nativehelper/ScopedLocalRef.h>

namespace android {

AssetStream::AssetStream(SkStream* stream)
    : mStream(stream), mPosition(0) {
}

AssetStream::~AssetStream() {
}

piex::Error AssetStream::GetData(
        const size_t offset, const size_t length, std::uint8_t* data) {
    // Seek first.
    if (mPosition != offset) {
        if (!mStream->seek(offset)) {
            return piex::Error::kFail;
        }
    }

    // Read bytes.
    size_t size = mStream->read((void*)data, length);
    mPosition = offset + size;

    return size == length ? piex::Error::kOk : piex::Error::kFail;
}

BufferedStream::BufferedStream(SkStream* stream)
    : mStream(stream) {
}

BufferedStream::~BufferedStream() {
}

piex::Error BufferedStream::GetData(
        const size_t offset, const size_t length, std::uint8_t* data) {
    // Seek first.
    if (offset + length > mStreamBuffer.bytesWritten()) {
        size_t sizeToRead = offset + length - mStreamBuffer.bytesWritten();
        if (sizeToRead <= kMinSizeToRead) {
            sizeToRead = kMinSizeToRead;
        }

        void* tempBuffer = malloc(sizeToRead);
        if (tempBuffer == NULL) {
          return piex::Error::kFail;
        }

        size_t bytesRead = mStream->read(tempBuffer, sizeToRead);
        if (bytesRead != sizeToRead) {
            free(tempBuffer);
            return piex::Error::kFail;
        }
        mStreamBuffer.write(tempBuffer, bytesRead);
        free(tempBuffer);
    }

    // Read bytes.
    if (mStreamBuffer.read((void*)data, offset, length)) {
        return piex::Error::kOk;
    } else {
        return piex::Error::kFail;
    }
}

FileStream::FileStream(const int fd)
    : mPosition(0) {
    mFile = fdopen(fd, "r");
    if (mFile == NULL) {
        return;
    }
}

FileStream::FileStream(const String8 filename)
    : mPosition(0) {
    mFile = fopen(filename.string(), "r");
    if (mFile == NULL) {
        return;
    }
}

FileStream::~FileStream() {
    if (mFile != NULL) {
        fclose(mFile);
        mFile = NULL;
    }
}

piex::Error FileStream::GetData(
        const size_t offset, const size_t length, std::uint8_t* data) {
    if (mFile == NULL) {
        return piex::Error::kFail;
    }

    // Seek first.
    if (mPosition != offset) {
        fseek(mFile, offset, SEEK_SET);
    }

    // Read bytes.
    size_t size = fread((void*)data, sizeof(std::uint8_t), length, mFile);
    mPosition += size;

    // Handle errors and verify the size.
    if (ferror(mFile) || size != length) {
        ALOGV("GetData read failed: (offset: %zu, length: %zu)", offset, length);
        return piex::Error::kFail;
    }
    return piex::Error::kOk;
}

bool FileStream::exists() const {
    return mFile != NULL;
}

bool GetExifFromRawImage(
        piex::StreamInterface* stream, const String8& filename,
        piex::PreviewImageData& image_data) {
    // Reset the PreviewImageData to its default.
    image_data = piex::PreviewImageData();

    if (!piex::IsRaw(stream)) {
        // Format not supported.
        ALOGV("Format not supported: %s", filename.string());
        return false;
    }

    piex::Error err = piex::GetPreviewImageData(stream, &image_data);

    if (err != piex::Error::kOk) {
        // The input data seems to be broken.
        ALOGV("Raw image not detected: %s (piex error code: %d)", filename.string(), (int32_t)err);
        return false;
    }

    return true;
}

bool ConvertKeyValueArraysToKeyedVector(
        JNIEnv *env, jobjectArray keys, jobjectArray values,
        KeyedVector<String8, String8>* keyedVector) {

    int nKeyValuePairs = 0;
    bool failed = false;
    if (keys != NULL && values != NULL) {
        nKeyValuePairs = env->GetArrayLength(keys);
        failed = (nKeyValuePairs != env->GetArrayLength(values));
    }

    if (!failed) {
        failed = ((keys != NULL && values == NULL) ||
                  (keys == NULL && values != NULL));
    }

    if (failed) {
        ALOGE("keys and values arrays have different length");
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return false;
    }

    for (int i = 0; i < nKeyValuePairs; ++i) {
        // No need to check on the ArrayIndexOutOfBoundsException, since
        // it won't happen here.
        jstring key = (jstring) env->GetObjectArrayElement(keys, i);
        jstring value = (jstring) env->GetObjectArrayElement(values, i);

        const char* keyStr = env->GetStringUTFChars(key, NULL);
        if (!keyStr) {  // OutOfMemoryError
            return false;
        }

        const char* valueStr = env->GetStringUTFChars(value, NULL);
        if (!valueStr) {  // OutOfMemoryError
            env->ReleaseStringUTFChars(key, keyStr);
            return false;
        }

        keyedVector->add(String8(keyStr), String8(valueStr));

        env->ReleaseStringUTFChars(key, keyStr);
        env->ReleaseStringUTFChars(value, valueStr);
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(value);
    }
    return true;
}

static jobject makeIntegerObject(JNIEnv *env, int32_t value) {
    ScopedLocalRef<jclass> clazz(env, env->FindClass("java/lang/Integer"));
    CHECK(clazz.get() != NULL);

    jmethodID integerConstructID =
        env->GetMethodID(clazz.get(), "<init>", "(I)V");
    CHECK(integerConstructID != NULL);

    return env->NewObject(clazz.get(), integerConstructID, value);
}

static jobject makeLongObject(JNIEnv *env, int64_t value) {
    ScopedLocalRef<jclass> clazz(env, env->FindClass("java/lang/Long"));
    CHECK(clazz.get() != NULL);

    jmethodID longConstructID = env->GetMethodID(clazz.get(), "<init>", "(J)V");
    CHECK(longConstructID != NULL);

    return env->NewObject(clazz.get(), longConstructID, value);
}

static jobject makeFloatObject(JNIEnv *env, float value) {
    ScopedLocalRef<jclass> clazz(env, env->FindClass("java/lang/Float"));
    CHECK(clazz.get() != NULL);

    jmethodID floatConstructID =
        env->GetMethodID(clazz.get(), "<init>", "(F)V");
    CHECK(floatConstructID != NULL);

    return env->NewObject(clazz.get(), floatConstructID, value);
}

static jobject makeByteBufferObject(
        JNIEnv *env, const void *data, size_t size) {
    jbyteArray byteArrayObj = env->NewByteArray(size);
    env->SetByteArrayRegion(byteArrayObj, 0, size, (const jbyte *)data);

    ScopedLocalRef<jclass> clazz(env, env->FindClass("java/nio/ByteBuffer"));
    CHECK(clazz.get() != NULL);

    jmethodID byteBufWrapID =
        env->GetStaticMethodID(
                clazz.get(), "wrap", "([B)Ljava/nio/ByteBuffer;");
    CHECK(byteBufWrapID != NULL);

    jobject byteBufObj = env->CallStaticObjectMethod(
            clazz.get(), byteBufWrapID, byteArrayObj);

    env->DeleteLocalRef(byteArrayObj); byteArrayObj = NULL;

    return byteBufObj;
}

static void SetMapInt32(
        JNIEnv *env, jobject hashMapObj, jmethodID hashMapPutID,
        const char *key, int32_t value) {
    jstring keyObj = env->NewStringUTF(key);
    jobject valueObj = makeIntegerObject(env, value);

    env->CallObjectMethod(hashMapObj, hashMapPutID, keyObj, valueObj);

    env->DeleteLocalRef(valueObj); valueObj = NULL;
    env->DeleteLocalRef(keyObj); keyObj = NULL;
}

status_t ConvertMessageToMap(
        JNIEnv *env, const sp<AMessage> &msg, jobject *map) {
    ScopedLocalRef<jclass> hashMapClazz(
            env, env->FindClass("java/util/HashMap"));

    if (hashMapClazz.get() == NULL) {
        return -EINVAL;
    }

    jmethodID hashMapConstructID =
        env->GetMethodID(hashMapClazz.get(), "<init>", "()V");

    if (hashMapConstructID == NULL) {
        return -EINVAL;
    }

    jmethodID hashMapPutID =
        env->GetMethodID(
                hashMapClazz.get(),
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    if (hashMapPutID == NULL) {
        return -EINVAL;
    }

    jobject hashMap = env->NewObject(hashMapClazz.get(), hashMapConstructID);

    for (size_t i = 0; i < msg->countEntries(); ++i) {
        AMessage::Type valueType;
        const char *key = msg->getEntryNameAt(i, &valueType);

        if (!strncmp(key, "android._", 9)) {
            // don't expose private keys (starting with android._)
            continue;
        }

        jobject valueObj = NULL;

        switch (valueType) {
            case AMessage::kTypeInt32:
            {
                int32_t val;
                CHECK(msg->findInt32(key, &val));

                valueObj = makeIntegerObject(env, val);
                break;
            }

            case AMessage::kTypeInt64:
            {
                int64_t val;
                CHECK(msg->findInt64(key, &val));

                valueObj = makeLongObject(env, val);
                break;
            }

            case AMessage::kTypeFloat:
            {
                float val;
                CHECK(msg->findFloat(key, &val));

                valueObj = makeFloatObject(env, val);
                break;
            }

            case AMessage::kTypeString:
            {
                AString val;
                CHECK(msg->findString(key, &val));

                valueObj = env->NewStringUTF(val.c_str());
                break;
            }

            case AMessage::kTypeBuffer:
            {
                sp<ABuffer> buffer;
                CHECK(msg->findBuffer(key, &buffer));

                valueObj = makeByteBufferObject(
                        env, buffer->data(), buffer->size());
                break;
            }

            case AMessage::kTypeRect:
            {
                int32_t left, top, right, bottom;
                CHECK(msg->findRect(key, &left, &top, &right, &bottom));

                SetMapInt32(
                        env,
                        hashMap,
                        hashMapPutID,
                        AStringPrintf("%s-left", key).c_str(),
                        left);

                SetMapInt32(
                        env,
                        hashMap,
                        hashMapPutID,
                        AStringPrintf("%s-top", key).c_str(),
                        top);

                SetMapInt32(
                        env,
                        hashMap,
                        hashMapPutID,
                        AStringPrintf("%s-right", key).c_str(),
                        right);

                SetMapInt32(
                        env,
                        hashMap,
                        hashMapPutID,
                        AStringPrintf("%s-bottom", key).c_str(),
                        bottom);
                break;
            }

            default:
                break;
        }

        if (valueObj != NULL) {
            jstring keyObj = env->NewStringUTF(key);

            env->CallObjectMethod(hashMap, hashMapPutID, keyObj, valueObj);

            env->DeleteLocalRef(keyObj); keyObj = NULL;
            env->DeleteLocalRef(valueObj); valueObj = NULL;
        }
    }

    *map = hashMap;

    return OK;
}

status_t ConvertKeyValueArraysToMessage(
        JNIEnv *env, jobjectArray keys, jobjectArray values,
        sp<AMessage> *out) {
    ScopedLocalRef<jclass> stringClass(env, env->FindClass("java/lang/String"));
    CHECK(stringClass.get() != NULL);
    ScopedLocalRef<jclass> integerClass(env, env->FindClass("java/lang/Integer"));
    CHECK(integerClass.get() != NULL);
    ScopedLocalRef<jclass> longClass(env, env->FindClass("java/lang/Long"));
    CHECK(longClass.get() != NULL);
    ScopedLocalRef<jclass> floatClass(env, env->FindClass("java/lang/Float"));
    CHECK(floatClass.get() != NULL);
    ScopedLocalRef<jclass> byteBufClass(env, env->FindClass("java/nio/ByteBuffer"));
    CHECK(byteBufClass.get() != NULL);

    sp<AMessage> msg = new AMessage;

    jsize numEntries = 0;

    if (keys != NULL) {
        if (values == NULL) {
            return -EINVAL;
        }

        numEntries = env->GetArrayLength(keys);

        if (numEntries != env->GetArrayLength(values)) {
            return -EINVAL;
        }
    } else if (values != NULL) {
        return -EINVAL;
    }

    for (jsize i = 0; i < numEntries; ++i) {
        jobject keyObj = env->GetObjectArrayElement(keys, i);

        if (!env->IsInstanceOf(keyObj, stringClass.get())) {
            return -EINVAL;
        }

        const char *tmp = env->GetStringUTFChars((jstring)keyObj, NULL);

        if (tmp == NULL) {
            return -ENOMEM;
        }

        AString key = tmp;

        env->ReleaseStringUTFChars((jstring)keyObj, tmp);
        tmp = NULL;

        if (key.startsWith("android._")) {
            // don't propagate private keys (starting with android._)
            continue;
        }

        jobject valueObj = env->GetObjectArrayElement(values, i);

        if (env->IsInstanceOf(valueObj, stringClass.get())) {
            const char *value = env->GetStringUTFChars((jstring)valueObj, NULL);

            if (value == NULL) {
                return -ENOMEM;
            }

            msg->setString(key.c_str(), value);

            env->ReleaseStringUTFChars((jstring)valueObj, value);
            value = NULL;
        } else if (env->IsInstanceOf(valueObj, integerClass.get())) {
            jmethodID intValueID =
                env->GetMethodID(integerClass.get(), "intValue", "()I");
            CHECK(intValueID != NULL);

            jint value = env->CallIntMethod(valueObj, intValueID);

            msg->setInt32(key.c_str(), value);
        } else if (env->IsInstanceOf(valueObj, longClass.get())) {
            jmethodID longValueID =
                env->GetMethodID(longClass.get(), "longValue", "()J");
            CHECK(longValueID != NULL);

            jlong value = env->CallLongMethod(valueObj, longValueID);

            msg->setInt64(key.c_str(), value);
        } else if (env->IsInstanceOf(valueObj, floatClass.get())) {
            jmethodID floatValueID =
                env->GetMethodID(floatClass.get(), "floatValue", "()F");
            CHECK(floatValueID != NULL);

            jfloat value = env->CallFloatMethod(valueObj, floatValueID);

            msg->setFloat(key.c_str(), value);
        } else if (env->IsInstanceOf(valueObj, byteBufClass.get())) {
            jmethodID positionID =
                env->GetMethodID(byteBufClass.get(), "position", "()I");
            CHECK(positionID != NULL);

            jmethodID limitID =
                env->GetMethodID(byteBufClass.get(), "limit", "()I");
            CHECK(limitID != NULL);

            jint position = env->CallIntMethod(valueObj, positionID);
            jint limit = env->CallIntMethod(valueObj, limitID);

            sp<ABuffer> buffer = new ABuffer(limit - position);

            void *data = env->GetDirectBufferAddress(valueObj);

            if (data != NULL) {
                memcpy(buffer->data(),
                       (const uint8_t *)data + position,
                       buffer->size());
            } else {
                jmethodID arrayID =
                    env->GetMethodID(byteBufClass.get(), "array", "()[B");
                CHECK(arrayID != NULL);

                jbyteArray byteArray =
                    (jbyteArray)env->CallObjectMethod(valueObj, arrayID);
                CHECK(byteArray != NULL);

                env->GetByteArrayRegion(
                        byteArray,
                        position,
                        buffer->size(),
                        (jbyte *)buffer->data());

                env->DeleteLocalRef(byteArray); byteArray = NULL;
            }

            msg->setBuffer(key.c_str(), buffer);
        }
    }

    *out = msg;

    return OK;
}

}  // namespace android


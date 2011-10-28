/*
 * Copyright (C) 2007 The Android Open Source Project
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

#undef LOG_TAG
#define LOG_TAG "CursorWindow"

#include <jni.h>
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/String16.h>
#include <utils/Unicode.h>

#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "binder/CursorWindow.h"
#include "sqlite3_exception.h"
#include "android_util_Binder.h"

namespace android {

static struct {
    jfieldID data;
    jfieldID sizeCopied;
} gCharArrayBufferClassInfo;

static jstring gEmptyString;

static void throwExceptionWithRowCol(JNIEnv* env, jint row, jint column) {
    String8 msg;
    msg.appendFormat("Couldn't read row %d, col %d from CursorWindow.  "
            "Make sure the Cursor is initialized correctly before accessing data from it.",
            row, column);
    jniThrowException(env, "java/lang/IllegalStateException", msg.string());
}

static void throwUnknownTypeException(JNIEnv * env, jint type) {
    String8 msg;
    msg.appendFormat("UNKNOWN type %d", type);
    jniThrowException(env, "java/lang/IllegalStateException", msg.string());
}

static jint nativeInitializeEmpty(JNIEnv* env, jclass clazz,
        jint cursorWindowSize, jboolean localOnly) {
    CursorWindow* window = new CursorWindow(cursorWindowSize);
    if (!window) {
        return 0;
    }
    if (!window->initBuffer(localOnly)) {
        delete window;
        return 0;
    }

    LOG_WINDOW("nativeInitializeEmpty: window = %p", window);
    return reinterpret_cast<jint>(window);
}

static jint nativeInitializeFromBinder(JNIEnv* env, jclass clazz, jobject binderObj) {
    sp<IMemory> memory = interface_cast<IMemory>(ibinderForJavaObject(env, binderObj));
    if (memory == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "Couldn't get native binder");
        return 0;
    }

    CursorWindow* window = new CursorWindow();
    if (!window) {
        return 0;
    }
    if (!window->setMemory(memory)) {
        delete window;
        return 0;
    }

    LOG_WINDOW("nativeInitializeFromBinder: numRows = %d, numColumns = %d, window = %p",
            window->getNumRows(), window->getNumColumns(), window);
    return reinterpret_cast<jint>(window);
}

static void nativeDispose(JNIEnv* env, jclass clazz, jint windowPtr) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    if (window) {
        LOG_WINDOW("Closing window %p", window);
        delete window;
    }
}

static jobject nativeGetBinder(JNIEnv * env, jclass clazz, jint windowPtr) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    if (window) {
        sp<IMemory> memory = window->getMemory();
        if (memory != NULL) {
            sp<IBinder> binder = memory->asBinder();
            return javaObjectForIBinder(env, binder);
        }
    }
    return NULL;
}

static void nativeClear(JNIEnv * env, jclass clazz, jint windowPtr) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    LOG_WINDOW("Clearing window %p", window);
    window->clear();
}

static jint nativeGetNumRows(JNIEnv* env, jclass clazz, jint windowPtr) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    return window->getNumRows();
}

static jboolean nativeSetNumColumns(JNIEnv* env, jclass clazz, jint windowPtr,
        jint columnNum) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    return window->setNumColumns(columnNum);
}

static jboolean nativeAllocRow(JNIEnv* env, jclass clazz, jint windowPtr) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    return window->allocRow() != NULL;
}

static void nativeFreeLastRow(JNIEnv* env, jclass clazz, jint windowPtr) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    window->freeLastRow();
}

static jint nativeGetType(JNIEnv* env, jclass clazz, jint windowPtr,
        jint row, jint column) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    LOG_WINDOW("returning column type affinity for %d,%d from %p", row, column, window);

    field_slot_t* fieldSlot = window->getFieldSlotWithCheck(row, column);
    if (!fieldSlot) {
        // FIXME: This is really broken but we have CTS tests that depend
        // on this legacy behavior.
        //throwExceptionWithRowCol(env, row, column);
        return FIELD_TYPE_NULL;
    }
    return fieldSlot->type;
}

static jbyteArray nativeGetBlob(JNIEnv* env, jclass clazz, jint windowPtr,
        jint row, jint column) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    LOG_WINDOW("Getting blob for %d,%d from %p", row, column, window);

    field_slot_t* fieldSlot = window->getFieldSlotWithCheck(row, column);
    if (!fieldSlot) {
        throwExceptionWithRowCol(env, row, column);
        return NULL;
    }

    uint8_t type = fieldSlot->type;
    if (type == FIELD_TYPE_BLOB || type == FIELD_TYPE_STRING) {
        uint32_t size = fieldSlot->data.buffer.size;
        jbyteArray byteArray = env->NewByteArray(size);
        if (!byteArray) {
            env->ExceptionClear();
            throw_sqlite3_exception(env, "Native could not create new byte[]");
            return NULL;
        }
        env->SetByteArrayRegion(byteArray, 0, size,
                reinterpret_cast<jbyte*>(window->offsetToPtr(fieldSlot->data.buffer.offset)));
        return byteArray;
    } else if (type == FIELD_TYPE_INTEGER) {
        throw_sqlite3_exception(env, "INTEGER data in nativeGetBlob ");
    } else if (type == FIELD_TYPE_FLOAT) {
        throw_sqlite3_exception(env, "FLOAT data in nativeGetBlob ");
    } else if (type == FIELD_TYPE_NULL) {
        // do nothing
    } else {
        throwUnknownTypeException(env, type);
    }
    return NULL;
}

static jstring nativeGetString(JNIEnv* env, jclass clazz, jint windowPtr,
        jint row, jint column) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    LOG_WINDOW("Getting string for %d,%d from %p", row, column, window);

    field_slot_t* fieldSlot = window->getFieldSlotWithCheck(row, column);
    if (!fieldSlot) {
        throwExceptionWithRowCol(env, row, column);
        return NULL;
    }

    uint8_t type = fieldSlot->type;
    if (type == FIELD_TYPE_STRING) {
        uint32_t size = fieldSlot->data.buffer.size;
#if WINDOW_STORAGE_UTF8
        return size > 1 ? env->NewStringUTF(window->getFieldSlotValueString(fieldSlot))
                : gEmptyString;
#else
        size_t chars = size / sizeof(char16_t);
        return chars ? env->NewString(reinterpret_cast<jchar*>(
                window->getFieldSlotValueString(fieldSlot)), chars)
                : gEmptyString;
#endif
    } else if (type == FIELD_TYPE_INTEGER) {
        int64_t value = window->getFieldSlotValueLong(fieldSlot);
        char buf[32];
        snprintf(buf, sizeof(buf), "%lld", value);
        return env->NewStringUTF(buf);
    } else if (type == FIELD_TYPE_FLOAT) {
        double value = window->getFieldSlotValueDouble(fieldSlot);
        char buf[32];
        snprintf(buf, sizeof(buf), "%g", value);
        return env->NewStringUTF(buf);
    } else if (type == FIELD_TYPE_NULL) {
        return NULL;
    } else if (type == FIELD_TYPE_BLOB) {
        throw_sqlite3_exception(env, "Unable to convert BLOB to string");
        return NULL;
    } else {
        throwUnknownTypeException(env, type);
        return NULL;
    }
}

static jcharArray allocCharArrayBuffer(JNIEnv* env, jobject bufferObj, size_t size) {
    jcharArray dataObj = jcharArray(env->GetObjectField(bufferObj,
            gCharArrayBufferClassInfo.data));
    if (dataObj && size) {
        jsize capacity = env->GetArrayLength(dataObj);
        if (size_t(capacity) < size) {
            env->DeleteLocalRef(dataObj);
            dataObj = NULL;
        }
    }
    if (!dataObj) {
        jsize capacity = size;
        if (capacity < 64) {
            capacity = 64;
        }
        dataObj = env->NewCharArray(capacity); // might throw OOM
        if (dataObj) {
            env->SetObjectField(bufferObj, gCharArrayBufferClassInfo.data, dataObj);
        }
    }
    return dataObj;
}

static void fillCharArrayBufferUTF(JNIEnv* env, jobject bufferObj,
        const char* str, size_t len) {
    ssize_t size = utf8_to_utf16_length(reinterpret_cast<const uint8_t*>(str), len);
    if (size < 0) {
        size = 0; // invalid UTF8 string
    }
    jcharArray dataObj = allocCharArrayBuffer(env, bufferObj, size);
    if (dataObj) {
        if (size) {
            jchar* data = static_cast<jchar*>(env->GetPrimitiveArrayCritical(dataObj, NULL));
            utf8_to_utf16(reinterpret_cast<const uint8_t*>(str), len,
                    reinterpret_cast<char16_t*>(data));
            env->ReleasePrimitiveArrayCritical(dataObj, data, 0);
        }
        env->SetIntField(bufferObj, gCharArrayBufferClassInfo.sizeCopied, size);
    }
}

#if !WINDOW_STORAGE_UTF8
static void fillCharArrayBuffer(JNIEnv* env, jobject bufferObj,
        const char16_t* str, size_t len) {
    jcharArray dataObj = allocCharArrayBuffer(env, bufferObj, len);
    if (dataObj) {
        if (len) {
            jchar* data = static_cast<jchar*>(env->GetPrimitiveArrayCritical(dataObj, NULL));
            memcpy(data, str, len * sizeof(jchar));
            env->ReleasePrimitiveArrayCritical(dataObj, data, 0);
        }
        env->SetIntField(bufferObj, gCharArrayBufferClassInfo.sizeCopied, len);
    }
}
#endif

static void clearCharArrayBuffer(JNIEnv* env, jobject bufferObj) {
    jcharArray dataObj = allocCharArrayBuffer(env, bufferObj, 0);
    if (dataObj) {
        env->SetIntField(bufferObj, gCharArrayBufferClassInfo.sizeCopied, 0);
    }
}

static void nativeCopyStringToBuffer(JNIEnv* env, jclass clazz, jint windowPtr,
        jint row, jint column, jobject bufferObj) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    LOG_WINDOW("Copying string for %d,%d from %p", row, column, window);

    field_slot_t* fieldSlot = window->getFieldSlotWithCheck(row, column);
    if (!fieldSlot) {
        throwExceptionWithRowCol(env, row, column);
        return;
    }

    uint8_t type = fieldSlot->type;
    if (type == FIELD_TYPE_STRING) {
        uint32_t size = fieldSlot->data.buffer.size;
#if WINDOW_STORAGE_UTF8
        if (size > 1) {
            fillCharArrayBufferUTF(env, bufferObj,
                    window->getFieldSlotValueString(fieldSlot), size - 1);
        } else {
            clearCharArrayBuffer(env, bufferObj);
        }
#else
        size_t chars = size / sizeof(char16_t);
        if (chars) {
            fillCharArrayBuffer(env, bufferObj,
                    window->getFieldSlotValueString(fieldSlot), chars);
        } else {
            clearCharArrayBuffer(env, bufferObj);
        }
#endif
    } else if (type == FIELD_TYPE_INTEGER) {
        int64_t value = window->getFieldSlotValueLong(fieldSlot);
        char buf[32];
        snprintf(buf, sizeof(buf), "%lld", value);
        fillCharArrayBufferUTF(env, bufferObj, buf, strlen(buf));
    } else if (type == FIELD_TYPE_FLOAT) {
        double value = window->getFieldSlotValueDouble(fieldSlot);
        char buf[32];
        snprintf(buf, sizeof(buf), "%g", value);
        fillCharArrayBufferUTF(env, bufferObj, buf, strlen(buf));
    } else if (type == FIELD_TYPE_NULL) {
        clearCharArrayBuffer(env, bufferObj);
    } else if (type == FIELD_TYPE_BLOB) {
        throw_sqlite3_exception(env, "Unable to convert BLOB to string");
    } else {
        throwUnknownTypeException(env, type);
    }
}

static jlong nativeGetLong(JNIEnv* env, jclass clazz, jint windowPtr,
        jint row, jint column) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    LOG_WINDOW("Getting long for %d,%d from %p", row, column, window);

    field_slot_t* fieldSlot = window->getFieldSlotWithCheck(row, column);
    if (!fieldSlot) {
        throwExceptionWithRowCol(env, row, column);
        return 0;
    }

    uint8_t type = fieldSlot->type;
    if (type == FIELD_TYPE_INTEGER) {
        return window->getFieldSlotValueLong(fieldSlot);
    } else if (type == FIELD_TYPE_STRING) {
        uint32_t size = fieldSlot->data.buffer.size;
#if WINDOW_STORAGE_UTF8
        return size > 1 ? strtoll(window->getFieldSlotValueString(fieldSlot), NULL, 0) : 0L;
#else
        size_t chars = size / sizeof(char16_t);
        return chars ? strtoll(String8(window->getFieldSlotValueString(fieldSlot), chars)
                .string(), NULL, 0) : 0L;
#endif
    } else if (type == FIELD_TYPE_FLOAT) {
        return jlong(window->getFieldSlotValueDouble(fieldSlot));
    } else if (type == FIELD_TYPE_NULL) {
        return 0;
    } else if (type == FIELD_TYPE_BLOB) {
        throw_sqlite3_exception(env, "Unable to convert BLOB to long");
        return 0;
    } else {
        throwUnknownTypeException(env, type);
        return 0;
    }
}

static jdouble nativeGetDouble(JNIEnv* env, jclass clazz, jint windowPtr,
        jint row, jint column) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    LOG_WINDOW("Getting double for %d,%d from %p", row, column, window);

    field_slot_t* fieldSlot = window->getFieldSlotWithCheck(row, column);
    if (!fieldSlot) {
        throwExceptionWithRowCol(env, row, column);
        return 0.0;
    }

    uint8_t type = fieldSlot->type;
    if (type == FIELD_TYPE_FLOAT) {
        return window->getFieldSlotValueDouble(fieldSlot);
    } else if (type == FIELD_TYPE_STRING) {
        uint32_t size = fieldSlot->data.buffer.size;
#if WINDOW_STORAGE_UTF8
        return size > 1 ? strtod(window->getFieldSlotValueString(fieldSlot), NULL) : 0.0;
#else
        size_t chars = size / sizeof(char16_t);
        return chars ? strtod(String8(window->getFieldSlotValueString(fieldSlot), chars)
                .string(), NULL) : 0.0;
#endif
    } else if (type == FIELD_TYPE_INTEGER) {
        return jdouble(window->getFieldSlotValueLong(fieldSlot));
    } else if (type == FIELD_TYPE_NULL) {
        return 0.0;
    } else if (type == FIELD_TYPE_BLOB) {
        throw_sqlite3_exception(env, "Unable to convert BLOB to double");
        return 0.0;
    } else {
        throwUnknownTypeException(env, type);
        return 0.0;
    }
}

static jboolean nativePutBlob(JNIEnv* env, jclass clazz, jint windowPtr,
        jbyteArray valueObj, jint row, jint column) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    field_slot_t * fieldSlot = window->getFieldSlotWithCheck(row, column);
    if (fieldSlot == NULL) {
        LOG_WINDOW(" getFieldSlotWithCheck error ");
        return false;
    }

    jsize len = env->GetArrayLength(valueObj);
    uint32_t offset = window->alloc(len);
    if (!offset) {
        LOG_WINDOW("Failed allocating %u bytes", len);
        return false;
    }

    void* value = env->GetPrimitiveArrayCritical(valueObj, NULL);
    window->copyIn(offset, static_cast<const uint8_t*>(value), len);
    env->ReleasePrimitiveArrayCritical(valueObj, value, JNI_ABORT);

    fieldSlot->type = FIELD_TYPE_BLOB;
    fieldSlot->data.buffer.offset = offset;
    fieldSlot->data.buffer.size = len;
    LOG_WINDOW("%d,%d is BLOB with %u bytes @ %d", row, column, len, offset);
    return true;
}

static jboolean nativePutString(JNIEnv* env, jclass clazz, jint windowPtr,
        jstring valueObj, jint row, jint column) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    field_slot_t * fieldSlot = window->getFieldSlotWithCheck(row, column);
    if (fieldSlot == NULL) {
        LOG_WINDOW(" getFieldSlotWithCheck error ");
        return false;
    }

#if WINDOW_STORAGE_UTF8
    size_t size = env->GetStringUTFLength(valueObj) + 1;
    const char* valueStr = env->GetStringUTFChars(valueObj, NULL);
#else
    size_t size = env->GetStringLength(valueObj) * sizeof(jchar);
    const jchar* valueStr = env->GetStringChars(valueObj, NULL);
#endif
    if (!valueStr) {
        LOG_WINDOW("value can't be transfer to UTFChars");
        return false;
    }

    uint32_t offset = window->alloc(size);
    if (!offset) {
        LOG_WINDOW("Failed allocating %u bytes", size);
#if WINDOW_STORAGE_UTF8
        env->ReleaseStringUTFChars(valueObj, valueStr);
#else
        env->ReleaseStringChars(valueObj, valueStr);
#endif
        return false;
    }

    window->copyIn(offset, reinterpret_cast<const uint8_t*>(valueStr), size);

#if WINDOW_STORAGE_UTF8
    env->ReleaseStringUTFChars(valueObj, valueStr);
#else
    env->ReleaseStringChars(valueObj, valueStr);
#endif

    fieldSlot->type = FIELD_TYPE_STRING;
    fieldSlot->data.buffer.offset = offset;
    fieldSlot->data.buffer.size = size;
    LOG_WINDOW("%d,%d is TEXT with %u bytes @ %d", row, column, size, offset);
    return true;
}

static jboolean nativePutLong(JNIEnv* env, jclass clazz, jint windowPtr,
        jlong value, jint row, jint column) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    if (!window->putLong(row, column, value)) {
        LOG_WINDOW(" getFieldSlotWithCheck error ");
        return false;
    }

    LOG_WINDOW("%d,%d is INTEGER 0x%016llx", row, column, value);
    return true;
}

static jboolean nativePutDouble(JNIEnv* env, jclass clazz, jint windowPtr,
        jdouble value, jint row, jint column) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    if (!window->putDouble(row, column, value)) {
        LOG_WINDOW(" getFieldSlotWithCheck error ");
        return false;
    }

    LOG_WINDOW("%d,%d is FLOAT %lf", row, column, value);
    return true;
}

static jboolean nativePutNull(JNIEnv* env, jclass clazz, jint windowPtr,
        jint row, jint column) {
    CursorWindow* window = reinterpret_cast<CursorWindow*>(windowPtr);
    if (!window->putNull(row, column)) {
        LOG_WINDOW(" getFieldSlotWithCheck error ");
        return false;
    }

    LOG_WINDOW("%d,%d is NULL", row, column);
    return true;
}

static JNINativeMethod sMethods[] =
{
    /* name, signature, funcPtr */
    { "nativeInitializeEmpty", "(IZ)I",
            (void*)nativeInitializeEmpty },
    { "nativeInitializeFromBinder", "(Landroid/os/IBinder;)I",
            (void*)nativeInitializeFromBinder },
    { "nativeDispose", "(I)V",
            (void*)nativeDispose },
    { "nativeGetBinder", "(I)Landroid/os/IBinder;",
            (void*)nativeGetBinder },
    { "nativeClear", "(I)V",
            (void*)nativeClear },
    { "nativeGetNumRows", "(I)I",
            (void*)nativeGetNumRows },
    { "nativeSetNumColumns", "(II)Z",
            (void*)nativeSetNumColumns },
    { "nativeAllocRow", "(I)Z",
            (void*)nativeAllocRow },
    { "nativeFreeLastRow", "(I)V",
            (void*)nativeFreeLastRow },
    { "nativeGetType", "(III)I",
            (void*)nativeGetType },
    { "nativeGetBlob", "(III)[B",
            (void*)nativeGetBlob },
    { "nativeGetString", "(III)Ljava/lang/String;",
            (void*)nativeGetString },
    { "nativeGetLong", "(III)J",
            (void*)nativeGetLong },
    { "nativeGetDouble", "(III)D",
            (void*)nativeGetDouble },
    { "nativeCopyStringToBuffer", "(IIILandroid/database/CharArrayBuffer;)V",
            (void*)nativeCopyStringToBuffer },
    { "nativePutBlob", "(I[BII)Z",
            (void*)nativePutBlob },
    { "nativePutString", "(ILjava/lang/String;II)Z",
            (void*)nativePutString },
    { "nativePutLong", "(IJII)Z",
            (void*)nativePutLong },
    { "nativePutDouble", "(IDII)Z",
            (void*)nativePutDouble },
    { "nativePutNull", "(III)Z",
            (void*)nativePutNull },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_database_CursorWindow(JNIEnv * env)
{
    jclass clazz;
    FIND_CLASS(clazz, "android/database/CharArrayBuffer");

    GET_FIELD_ID(gCharArrayBufferClassInfo.data, clazz,
            "data", "[C");
    GET_FIELD_ID(gCharArrayBufferClassInfo.sizeCopied, clazz,
            "sizeCopied", "I");

    gEmptyString = jstring(env->NewGlobalRef(env->NewStringUTF("")));
    LOG_FATAL_IF(!gEmptyString, "Unable to create empty string");

    return AndroidRuntime::registerNativeMethods(env, "android/database/CursorWindow",
            sMethods, NELEM(sMethods));
}

} // namespace android

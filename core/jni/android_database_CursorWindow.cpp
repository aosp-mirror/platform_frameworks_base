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

#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "binder/CursorWindow.h"
#include "sqlite3_exception.h"
#include "android_util_Binder.h"


namespace android {

static jfieldID gWindowField;
static jfieldID gBufferField;
static jfieldID gSizeCopiedField;

#define GET_WINDOW(env, object) ((CursorWindow *)env->GetIntField(object, gWindowField))
#define SET_WINDOW(env, object, window) (env->SetIntField(object, gWindowField, (int)window))
#define SET_BUFFER(env, object, buf) (env->SetObjectField(object, gBufferField, buf))
#define SET_SIZE_COPIED(env, object, size) (env->SetIntField(object, gSizeCopiedField, size))

CursorWindow * get_window_from_object(JNIEnv * env, jobject javaWindow)
{
    return GET_WINDOW(env, javaWindow);
}

static jint native_init_empty(JNIEnv * env, jobject object, jint cursorWindowSize,
        jboolean localOnly)
{
    uint8_t * data;
    size_t size;
    CursorWindow * window;

    window = new CursorWindow(cursorWindowSize);
    if (!window) {
        return 1;
    }
    if (!window->initBuffer(localOnly)) {
        delete window;
        return 1;
    }

    LOG_WINDOW("native_init_empty: window = %p", window);
    SET_WINDOW(env, object, window);
    return 0;
}

static jint native_init_memory(JNIEnv * env, jobject object, jobject memObj)
{
    sp<IMemory> memory = interface_cast<IMemory>(ibinderForJavaObject(env, memObj));
    if (memory == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "Couldn't get native binder");
        return 1;
    }

    CursorWindow * window = new CursorWindow();
    if (!window) {
        return 1;
    }
    if (!window->setMemory(memory)) {
        delete window;
        return 1;
    }

    LOG_WINDOW("native_init_memory: numRows = %d, numColumns = %d, window = %p", window->getNumRows(), window->getNumColumns(), window);
    SET_WINDOW(env, object, window);
    return 0;
}

static jobject native_getBinder(JNIEnv * env, jobject object)
{
    CursorWindow * window = GET_WINDOW(env, object);
    if (window) {
        sp<IMemory> memory = window->getMemory();
        if (memory != NULL) {
            sp<IBinder> binder = memory->asBinder();
            return javaObjectForIBinder(env, binder);
        }
    }
    return NULL;
}

static void native_clear(JNIEnv * env, jobject object)
{
    CursorWindow * window = GET_WINDOW(env, object);
LOG_WINDOW("Clearing window %p", window);
    if (window == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "clear() called after close()");
        return;
    }
    window->clear();
}

static void native_close(JNIEnv * env, jobject object)
{
    CursorWindow * window = GET_WINDOW(env, object);
    if (window) {
LOG_WINDOW("Closing window %p", window);
        delete window;
        SET_WINDOW(env, object, 0);
    }
}

static void throwExceptionWithRowCol(JNIEnv * env, jint row, jint column)
{
    char buf[200];
    snprintf(buf, sizeof(buf), "Couldn't read row %d, col %d from CursorWindow. Make sure the Cursor is initialized correctly before accessing data from it",
            row, column);
    jniThrowException(env, "java/lang/IllegalStateException", buf);
}

static void throwUnknowTypeException(JNIEnv * env, jint type)
{
    char buf[80];
    snprintf(buf, sizeof(buf), "UNKNOWN type %d", type);
    jniThrowException(env, "java/lang/IllegalStateException", buf);
}

static jlong getLong_native(JNIEnv * env, jobject object, jint row, jint column)
{
    int32_t err;
    CursorWindow * window = GET_WINDOW(env, object);
LOG_WINDOW("Getting long for %d,%d from %p", row, column, window);

    field_slot_t field;
    err = window->read_field_slot(row, column, &field);
    if (err != 0) {
        throwExceptionWithRowCol(env, row, column);
        return 0;
    }

    uint8_t type = field.type;
    if (type == FIELD_TYPE_INTEGER) {
        int64_t value;
        if (window->getLong(row, column, &value)) {
            return value;
        }
        return 0;
    } else if (type == FIELD_TYPE_STRING) {
        uint32_t size = field.data.buffer.size;
        if (size > 0) {
#if WINDOW_STORAGE_UTF8
            return strtoll((char const *)window->offsetToPtr(field.data.buffer.offset), NULL, 0);
#else
            String8 ascii((char16_t *) window->offsetToPtr(field.data.buffer.offset), size / 2);
            char const * str = ascii.string();
            return strtoll(str, NULL, 0);
#endif
        } else {
            return 0;
        }
    } else if (type == FIELD_TYPE_FLOAT) {
        double value;
        if (window->getDouble(row, column, &value)) {
            return value;
        }
        return 0;
    } else if (type == FIELD_TYPE_NULL) {
        return 0;
    } else if (type == FIELD_TYPE_BLOB) {
        throw_sqlite3_exception(env, "Unable to convert BLOB to long");
        return 0;
    } else {
        throwUnknowTypeException(env, type);
        return 0;
    }
}

static jbyteArray getBlob_native(JNIEnv* env, jobject object, jint row, jint column)
{
    int32_t err;
    CursorWindow * window = GET_WINDOW(env, object);
LOG_WINDOW("Getting blob for %d,%d from %p", row, column, window);

    field_slot_t field;
    err = window->read_field_slot(row, column, &field);
    if (err != 0) {
        throwExceptionWithRowCol(env, row, column);
        return NULL;
    }

    uint8_t type = field.type;
    if (type == FIELD_TYPE_BLOB || type == FIELD_TYPE_STRING) {
        jbyteArray byteArray = env->NewByteArray(field.data.buffer.size);
        if (!byteArray) {
            throw_sqlite3_exception(env, "Native could not create new byte[]");
            return NULL;
        }
        env->SetByteArrayRegion(byteArray, 0, field.data.buffer.size,
            (const jbyte*)window->offsetToPtr(field.data.buffer.offset));
        return byteArray;
    } else if (type == FIELD_TYPE_INTEGER) {
        throw_sqlite3_exception(env, "INTEGER data in getBlob_native ");
    } else if (type == FIELD_TYPE_FLOAT) {
        throw_sqlite3_exception(env, "FLOAT data in getBlob_native ");
    } else if (type == FIELD_TYPE_NULL) {
        // do nothing
    } else {
        throwUnknowTypeException(env, type);
    }
    return NULL;
}

static jstring getString_native(JNIEnv* env, jobject object, jint row, jint column)
{
    int32_t err;
    CursorWindow * window = GET_WINDOW(env, object);
LOG_WINDOW("Getting string for %d,%d from %p", row, column, window);

    field_slot_t field;
    err = window->read_field_slot(row, column, &field);
    if (err != 0) {
        throwExceptionWithRowCol(env, row, column);
        return NULL;
    }

    uint8_t type = field.type;
    if (type == FIELD_TYPE_STRING) {
        uint32_t size = field.data.buffer.size;
        if (size > 0) {
#if WINDOW_STORAGE_UTF8
            // Pass size - 1 since the UTF8 is null terminated and we don't want a null terminator on the UTF16 string
            String16 utf16((char const *)window->offsetToPtr(field.data.buffer.offset), size - 1);
            return env->NewString((jchar const *)utf16.string(), utf16.size());
#else
            return env->NewString((jchar const *)window->offsetToPtr(field.data.buffer.offset), size / 2);
#endif
        } else {
            return env->NewStringUTF("");
        }
    } else if (type == FIELD_TYPE_INTEGER) {
        int64_t value;
        if (window->getLong(row, column, &value)) {
            char buf[32];
            snprintf(buf, sizeof(buf), "%lld", value);
            return env->NewStringUTF(buf);
        }
        return NULL;
    } else if (type == FIELD_TYPE_FLOAT) {
        double value;
        if (window->getDouble(row, column, &value)) {
            char buf[32];
            snprintf(buf, sizeof(buf), "%g", value);
            return env->NewStringUTF(buf);
        }
        return NULL;
    } else if (type == FIELD_TYPE_NULL) {
        return NULL;
    } else if (type == FIELD_TYPE_BLOB) {
        throw_sqlite3_exception(env, "Unable to convert BLOB to string");
        return NULL;
    } else {
        throwUnknowTypeException(env, type);
        return NULL;
    }
}

/**
 * Use this only to convert characters that are known to be within the
 * 0-127 range for direct conversion to UTF-16
 */
static jint charToJchar(const char* src, jchar* dst, jint bufferSize)
{
    int32_t len = strlen(src);

    if (bufferSize < len) {
        len = bufferSize;
    }

    for (int i = 0; i < len; i++) {
        *dst++ = (*src++ & 0x7F);
    }
    return len;
}

static jcharArray copyStringToBuffer_native(JNIEnv* env, jobject object, jint row,
                                      jint column, jint bufferSize, jobject buf)
{
    int32_t err;
    CursorWindow * window = GET_WINDOW(env, object);
LOG_WINDOW("Copying string for %d,%d from %p", row, column, window);

    field_slot_t field;
    err = window->read_field_slot(row, column, &field);
    if (err != 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "Unable to get field slot");
        return NULL;
    }

    jcharArray buffer = (jcharArray)env->GetObjectField(buf, gBufferField);
    if (buffer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", "buf should not be null");
        return NULL;
    }
    jchar* dst = env->GetCharArrayElements(buffer, NULL);
    uint8_t type = field.type;
    uint32_t sizeCopied = 0;
    jcharArray newArray = NULL;
    if (type == FIELD_TYPE_STRING) {
        uint32_t size = field.data.buffer.size;
        if (size > 0) {
#if WINDOW_STORAGE_UTF8
            // Pass size - 1 since the UTF8 is null terminated and we don't want a null terminator on the UTF16 string
            String16 utf16((char const *)window->offsetToPtr(field.data.buffer.offset), size - 1);
            int32_t strSize = utf16.size();
            if (strSize > bufferSize || dst == NULL) {
                newArray = env->NewCharArray(strSize);
                env->SetCharArrayRegion(newArray, 0, strSize, (jchar const *)utf16.string());
            } else {
                memcpy(dst, (jchar const *)utf16.string(), strSize * 2);
            }
            sizeCopied = strSize;
#else
            sizeCopied = size/2 + size % 2;
            if (size > bufferSize * 2 || dst == NULL) {
                newArray = env->NewCharArray(sizeCopied);
                memcpy(newArray, (jchar const *)window->offsetToPtr(field.data.buffer.offset), size);
            } else {
                memcpy(dst, (jchar const *)window->offsetToPtr(field.data.buffer.offset), size);
            }
#endif
        }
    } else if (type == FIELD_TYPE_INTEGER) {
        int64_t value;
        if (window->getLong(row, column, &value)) {
            char buf[32];
            int len;
            snprintf(buf, sizeof(buf), "%lld", value);
            sizeCopied = charToJchar(buf, dst, bufferSize);
         }
    } else if (type == FIELD_TYPE_FLOAT) {
        double value;
        if (window->getDouble(row, column, &value)) {
            char tempbuf[32];
            snprintf(tempbuf, sizeof(tempbuf), "%g", value);
            sizeCopied = charToJchar(tempbuf, dst, bufferSize);
        }
    } else if (type == FIELD_TYPE_NULL) {
    } else if (type == FIELD_TYPE_BLOB) {
        throw_sqlite3_exception(env, "Unable to convert BLOB to string");
    } else {
        LOGE("Unknown field type %d", type);
        throw_sqlite3_exception(env, "UNKNOWN type in copyStringToBuffer_native()");
    }
    SET_SIZE_COPIED(env, buf, sizeCopied);
    env->ReleaseCharArrayElements(buffer, dst, JNI_OK);
    return newArray;
}

static jdouble getDouble_native(JNIEnv* env, jobject object, jint row, jint column)
{
    int32_t err;
    CursorWindow * window = GET_WINDOW(env, object);
LOG_WINDOW("Getting double for %d,%d from %p", row, column, window);

    field_slot_t field;
    err = window->read_field_slot(row, column, &field);
    if (err != 0) {
        throwExceptionWithRowCol(env, row, column);
        return 0.0;
    }

    uint8_t type = field.type;
    if (type == FIELD_TYPE_FLOAT) {
        double value;
        if (window->getDouble(row, column, &value)) {
            return value;
        }
        return 0.0;
    } else if (type == FIELD_TYPE_STRING) {
        uint32_t size = field.data.buffer.size;
        if (size > 0) {
#if WINDOW_STORAGE_UTF8
            return strtod((char const *)window->offsetToPtr(field.data.buffer.offset), NULL);
#else
            String8 ascii((char16_t *) window->offsetToPtr(field.data.buffer.offset), size / 2);
            char const * str = ascii.string();
            return strtod(str, NULL);
#endif
        } else {
            return 0.0;
        }
    } else if (type == FIELD_TYPE_INTEGER) {
        int64_t value;
        if (window->getLong(row, column, &value)) {
            return (double) value;
        }
        return 0.0;
    } else if (type == FIELD_TYPE_NULL) {
        return 0.0;
    } else if (type == FIELD_TYPE_BLOB) {
        throw_sqlite3_exception(env, "Unable to convert BLOB to double");
        return 0.0;
    } else {
        throwUnknowTypeException(env, type);
        return 0.0;
    }
}

bool isNull_native(CursorWindow *window, jint row, jint column)
{
    LOG_WINDOW("Checking for NULL at %d,%d from %p", row, column, window);

    bool isNull;
    if (window->getNull(row, column, &isNull)) {
        return isNull;
    }

    //TODO throw execption?
    return true;
}

static jint getNumRows(JNIEnv * env, jobject object)
{
    CursorWindow * window = GET_WINDOW(env, object);
    return window->getNumRows();
}

static jboolean setNumColumns(JNIEnv * env, jobject object, jint columnNum)
{
    CursorWindow * window = GET_WINDOW(env, object);
    return window->setNumColumns(columnNum);
}

static jboolean allocRow(JNIEnv * env, jobject object)
{
    CursorWindow * window = GET_WINDOW(env, object);
    return window->allocRow() != NULL;
}

static jboolean putBlob_native(JNIEnv * env, jobject object, jbyteArray value, jint row, jint col)
{
    CursorWindow * window = GET_WINDOW(env, object);
    if (!value) {
        LOG_WINDOW("How did a null value send to here");
        return false;
    }
    field_slot_t * fieldSlot = window->getFieldSlotWithCheck(row, col);
    if (fieldSlot == NULL) {
        LOG_WINDOW(" getFieldSlotWithCheck error ");
        return false;
    }

    jint len = env->GetArrayLength(value);
    int offset = window->alloc(len);
    if (!offset) {
        LOG_WINDOW("Failed allocating %u bytes", len);
        return false;
    }
    jbyte * bytes = env->GetByteArrayElements(value, NULL);
    window->copyIn(offset, (uint8_t const *)bytes, len);

    // This must be updated after the call to alloc(), since that
    // may move the field around in the window
    fieldSlot->type = FIELD_TYPE_BLOB;
    fieldSlot->data.buffer.offset = offset;
    fieldSlot->data.buffer.size = len;
    env->ReleaseByteArrayElements(value, bytes, JNI_ABORT);
    LOG_WINDOW("%d,%d is BLOB with %u bytes @ %d", row, col, len, offset);
    return true;
}

static jboolean putString_native(JNIEnv * env, jobject object, jstring value, jint row, jint col)
{
    CursorWindow * window = GET_WINDOW(env, object);
    if (!value) {
        LOG_WINDOW("How did a null value send to here");
        return false;
    }
    field_slot_t * fieldSlot = window->getFieldSlotWithCheck(row, col);
    if (fieldSlot == NULL) {
        LOG_WINDOW(" getFieldSlotWithCheck error ");
        return false;
    }

#if WINDOW_STORAGE_UTF8
    int len = env->GetStringUTFLength(value) + 1;
    char const * valStr = env->GetStringUTFChars(value, NULL);
#else
    int len = env->GetStringLength(value);
    // GetStringLength return number of chars and one char takes 2 bytes
    len *= 2;
    const jchar* valStr = env->GetStringChars(value, NULL);
#endif
    if (!valStr) {
        LOG_WINDOW("value can't be transfer to UTFChars");
        return false;
    }

    int offset = window->alloc(len);
    if (!offset) {
        LOG_WINDOW("Failed allocating %u bytes", len);
#if WINDOW_STORAGE_UTF8
        env->ReleaseStringUTFChars(value, valStr);
#else
        env->ReleaseStringChars(value, valStr);
#endif
        return false;
    }

    window->copyIn(offset, (uint8_t const *)valStr, len);

    // This must be updated after the call to alloc(), since that
    // may move the field around in the window
    fieldSlot->type = FIELD_TYPE_STRING;
    fieldSlot->data.buffer.offset = offset;
    fieldSlot->data.buffer.size = len;

    LOG_WINDOW("%d,%d is TEXT with %u bytes @ %d", row, col, len, offset);
#if WINDOW_STORAGE_UTF8
    env->ReleaseStringUTFChars(value, valStr);
#else
    env->ReleaseStringChars(value, valStr);
#endif

    return true;
}

static jboolean putLong_native(JNIEnv * env, jobject object, jlong value, jint row, jint col)
{
    CursorWindow * window = GET_WINDOW(env, object);
    if (!window->putLong(row, col, value)) {
        LOG_WINDOW(" getFieldSlotWithCheck error ");
        return false;
    }

    LOG_WINDOW("%d,%d is INTEGER 0x%016llx", row, col, value);

    return true;
}

static jboolean putDouble_native(JNIEnv * env, jobject object, jdouble value, jint row, jint col)
{
    CursorWindow * window = GET_WINDOW(env, object);
    if (!window->putDouble(row, col, value)) {
        LOG_WINDOW(" getFieldSlotWithCheck error ");
        return false;
    }

    LOG_WINDOW("%d,%d is FLOAT %lf", row, col, value);

    return true;
}

static jboolean putNull_native(JNIEnv * env, jobject object, jint row, jint col)
{
    CursorWindow * window = GET_WINDOW(env, object);
    if (!window->putNull(row, col)) {
        LOG_WINDOW(" getFieldSlotWithCheck error ");
        return false;
    }

    LOG_WINDOW("%d,%d is NULL", row, col);

    return true;
}

// free the last row
static void freeLastRow(JNIEnv * env, jobject object) {
    CursorWindow * window = GET_WINDOW(env, object);
    window->freeLastRow();
}

static jint getType_native(JNIEnv* env, jobject object, jint row, jint column)
{
    int32_t err;
    CursorWindow * window = GET_WINDOW(env, object);
    LOG_WINDOW("returning column type affinity for %d,%d from %p", row, column, window);

    if (isNull_native(window, row, column)) {
      return FIELD_TYPE_NULL;
    }

    field_slot_t field;
    err = window->read_field_slot(row, column, &field);
    if (err != 0) {
        throwExceptionWithRowCol(env, row, column);
        return NULL;
    }

    return field.type;
}

static JNINativeMethod sMethods[] =
{
     /* name, signature, funcPtr */
    {"native_init", "(IZ)I", (void *)native_init_empty},
    {"native_init", "(Landroid/os/IBinder;)I", (void *)native_init_memory},
    {"native_getBinder", "()Landroid/os/IBinder;", (void *)native_getBinder},
    {"native_clear", "()V", (void *)native_clear},
    {"close_native", "()V", (void *)native_close},
    {"getLong_native", "(II)J", (void *)getLong_native},
    {"getBlob_native", "(II)[B", (void *)getBlob_native},
    {"getString_native", "(II)Ljava/lang/String;", (void *)getString_native},
    {"copyStringToBuffer_native", "(IIILandroid/database/CharArrayBuffer;)[C", (void *)copyStringToBuffer_native},
    {"getDouble_native", "(II)D", (void *)getDouble_native},
    {"getNumRows_native", "()I", (void *)getNumRows},
    {"setNumColumns_native", "(I)Z", (void *)setNumColumns},
    {"allocRow_native", "()Z", (void *)allocRow},
    {"putBlob_native", "([BII)Z", (void *)putBlob_native},
    {"putString_native", "(Ljava/lang/String;II)Z", (void *)putString_native},
    {"putLong_native", "(JII)Z", (void *)putLong_native},
    {"putDouble_native", "(DII)Z", (void *)putDouble_native},
    {"freeLastRow_native", "()V", (void *)freeLastRow},
    {"putNull_native", "(II)Z", (void *)putNull_native},
    {"getType_native", "(II)I", (void *)getType_native},
};

int register_android_database_CursorWindow(JNIEnv * env)
{
    jclass clazz;

    clazz = env->FindClass("android/database/CursorWindow");
    if (clazz == NULL) {
        LOGE("Can't find android/database/CursorWindow");
        return -1;
    }

    gWindowField = env->GetFieldID(clazz, "nWindow", "I");

    if (gWindowField == NULL) {
        LOGE("Error locating fields");
        return -1;
    }

    clazz =  env->FindClass("android/database/CharArrayBuffer");
    if (clazz == NULL) {
        LOGE("Can't find android/database/CharArrayBuffer");
        return -1;
    }

    gBufferField = env->GetFieldID(clazz, "data", "[C");

    if (gBufferField == NULL) {
        LOGE("Error locating fields data in CharArrayBuffer");
        return -1;
    }

    gSizeCopiedField = env->GetFieldID(clazz, "sizeCopied", "I");

    if (gSizeCopiedField == NULL) {
        LOGE("Error locating fields sizeCopied in CharArrayBuffer");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env, "android/database/CursorWindow",
            sMethods, NELEM(sMethods));
}

} // namespace android

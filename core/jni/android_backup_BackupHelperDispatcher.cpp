/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "BackupHelperDispatcher_native"
#include <utils/Log.h>

#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

#include "core_jni_helpers.h"

#define VERSION_1_HEADER 0x01706c48  // 'Hlp'1 little endian

namespace android
{

struct chunk_header_v1 {
    int headerSize;
    int version;
    int dataSize; // corresponds to Header.chunkSize
    int nameLength; // not including the NULL terminator, which is not written to the file
};

static jfieldID s_chunkSizeField = 0;
static jfieldID s_keyPrefixField = 0;

static jint
readHeader_native(JNIEnv* env, jobject clazz, jobject headerObj, jobject fdObj)
{
    chunk_header_v1 flattenedHeader;
    ssize_t amt;
    String8 keyPrefix;
    char* buf;

    int fd = jniGetFDFromFileDescriptor(env, fdObj);

    amt = read(fd, &flattenedHeader.headerSize, sizeof(flattenedHeader.headerSize));
    if (amt != sizeof(flattenedHeader.headerSize)) {
        return (jint) -1;
    }

    int remainingHeader = flattenedHeader.headerSize - sizeof(flattenedHeader.headerSize);

    if (flattenedHeader.headerSize < (int)sizeof(chunk_header_v1)) {
        ALOGW("Skipping unknown header: %d bytes", flattenedHeader.headerSize);
        if (remainingHeader > 0) {
            lseek(fd, remainingHeader, SEEK_CUR);
            // >0 means skip this chunk
            return (jint) 1;
        }
    }

    amt = read(fd, &flattenedHeader.version,
            sizeof(chunk_header_v1)-sizeof(flattenedHeader.headerSize));
    if (amt <= 0) {
        ALOGW("Failed reading chunk header");
        return (jint) -1;
    }
    remainingHeader -= sizeof(chunk_header_v1)-sizeof(flattenedHeader.headerSize);

    if (flattenedHeader.version != VERSION_1_HEADER) {
        ALOGW("Skipping unknown header version: 0x%08x, %d bytes", flattenedHeader.version,
                flattenedHeader.headerSize);
        if (remainingHeader > 0) {
            lseek(fd, remainingHeader, SEEK_CUR);
            // >0 means skip this chunk
            return (jint) 1;
        }
    }

#if 0
    ALOGD("chunk header:");
    ALOGD("  headerSize=%d", flattenedHeader.headerSize);
    ALOGD("  version=0x%08x", flattenedHeader.version);
    ALOGD("  dataSize=%d", flattenedHeader.dataSize);
    ALOGD("  nameLength=%d", flattenedHeader.nameLength);
#endif

    if (flattenedHeader.dataSize < 0 || flattenedHeader.nameLength < 0 ||
            remainingHeader < flattenedHeader.nameLength) {
        ALOGW("Malformed V1 header remainingHeader=%d dataSize=%d nameLength=%d", remainingHeader,
                flattenedHeader.dataSize, flattenedHeader.nameLength);
        return (jint) -1;
    }

    buf = keyPrefix.lockBuffer(flattenedHeader.nameLength);
    if (buf == NULL) {
        ALOGW("unable to allocate %d bytes", flattenedHeader.nameLength);
        return (jint) -1;
    }

    amt = read(fd, buf, flattenedHeader.nameLength);
    buf[flattenedHeader.nameLength] = 0;

    keyPrefix.unlockBuffer(flattenedHeader.nameLength);

    remainingHeader -= flattenedHeader.nameLength;

    if (remainingHeader > 0) {
        lseek(fd, remainingHeader, SEEK_CUR);
    }

    env->SetIntField(headerObj, s_chunkSizeField, flattenedHeader.dataSize);
    env->SetObjectField(headerObj, s_keyPrefixField, env->NewStringUTF(keyPrefix.string()));

    return (jint) 0;
}

static jint
skipChunk_native(JNIEnv* env, jobject clazz, jobject fdObj, jint bytesToSkip)
{
    int fd = jniGetFDFromFileDescriptor(env, fdObj);

    lseek(fd, bytesToSkip, SEEK_CUR);

    return (jint) 0;
}

static int
padding_len(int len)
{
    len = len % 4;
    return len == 0 ? len : 4 - len;
}

static jint
allocateHeader_native(JNIEnv* env, jobject clazz, jobject headerObj, jobject fdObj)
{
    int pos;
    jstring nameObj;
    int nameLength;
    int namePadding;
    int headerSize;

    int fd = jniGetFDFromFileDescriptor(env, fdObj);

    nameObj = (jstring)env->GetObjectField(headerObj, s_keyPrefixField);

    nameLength = env->GetStringUTFLength(nameObj);
    namePadding = padding_len(nameLength);

    headerSize = sizeof(chunk_header_v1) + nameLength + namePadding;

    pos = lseek(fd, 0, SEEK_CUR);

    lseek(fd, headerSize, SEEK_CUR);

    return (jint) pos;
}

static jint
writeHeader_native(JNIEnv* env, jobject clazz, jobject headerObj, jobject fdObj, jint pos)
{
    int err;
    chunk_header_v1 header;
    int namePadding;
    int prevPos;
    jstring nameObj;
    const char* buf;

    int fd = jniGetFDFromFileDescriptor(env, fdObj);
    prevPos = lseek(fd, 0, SEEK_CUR);

    nameObj = (jstring)env->GetObjectField(headerObj, s_keyPrefixField);
    header.nameLength = env->GetStringUTFLength(nameObj);
    namePadding = padding_len(header.nameLength);

    header.headerSize = sizeof(chunk_header_v1) + header.nameLength + namePadding;
    header.version = VERSION_1_HEADER;
    header.dataSize = prevPos - (pos + header.headerSize);

    lseek(fd, pos, SEEK_SET);
    err = write(fd, &header, sizeof(chunk_header_v1));
    if (err != sizeof(chunk_header_v1)) {
        return (jint) errno;
    }

    buf = env->GetStringUTFChars(nameObj, NULL);
    err = write(fd, buf, header.nameLength);
    env->ReleaseStringUTFChars(nameObj, buf);
    if (err != header.nameLength) {
        return (jint) errno;
    }

    if (namePadding != 0) {
        int zero = 0;
        err = write(fd, &zero, namePadding);
        if (err != namePadding) {
            return (jint) errno;
        }
    }

    lseek(fd, prevPos, SEEK_SET);
    return (jint) 0;
}

static const JNINativeMethod g_methods[] = {
    { "readHeader_native",
       "(Landroid/app/backup/BackupHelperDispatcher$Header;Ljava/io/FileDescriptor;)I",
       (void*)readHeader_native },
    { "skipChunk_native",
        "(Ljava/io/FileDescriptor;I)I",
        (void*)skipChunk_native },
    { "allocateHeader_native",
        "(Landroid/app/backup/BackupHelperDispatcher$Header;Ljava/io/FileDescriptor;)I",
        (void*)allocateHeader_native },
    { "writeHeader_native",
       "(Landroid/app/backup/BackupHelperDispatcher$Header;Ljava/io/FileDescriptor;I)I",
       (void*)writeHeader_native },
};

int register_android_backup_BackupHelperDispatcher(JNIEnv* env)
{
    jclass clazz = FindClassOrDie(env, "android/app/backup/BackupHelperDispatcher$Header");
    s_chunkSizeField = GetFieldIDOrDie(env, clazz, "chunkSize", "I");
    s_keyPrefixField = GetFieldIDOrDie(env, clazz, "keyPrefix", "Ljava/lang/String;");

    return RegisterMethodsOrDie(env, "android/app/backup/BackupHelperDispatcher", g_methods,
                                NELEM(g_methods));
}

}

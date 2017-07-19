/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "StrictJarFile"

#include <memory>
#include <string>

#include <log/log.h>

#include <nativehelper/JNIHelp.h>
#include <nativehelper/JniConstants.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedUtfChars.h>
#include "jni.h"
#include "ziparchive/zip_archive.h"

namespace android {

// The method ID for ZipEntry.<init>(String,String,JJJIII[BJJ)
static jmethodID zipEntryCtor;

static void throwIoException(JNIEnv* env, const int32_t errorCode) {
  jniThrowException(env, "java/io/IOException", ErrorCodeString(errorCode));
}

static jobject newZipEntry(JNIEnv* env, const ZipEntry& entry, jstring entryName) {
  return env->NewObject(JniConstants::zipEntryClass,
                        zipEntryCtor,
                        entryName,
                        NULL,  // comment
                        static_cast<jlong>(entry.crc32),
                        static_cast<jlong>(entry.compressed_length),
                        static_cast<jlong>(entry.uncompressed_length),
                        static_cast<jint>(entry.method),
                        static_cast<jint>(0),  // time
                        NULL,  // byte[] extra
                        static_cast<jlong>(entry.offset));
}

static jlong StrictJarFile_nativeOpenJarFile(JNIEnv* env, jobject, jstring name, jint fd) {
  // Name argument is used for logging, and can be any string.
  ScopedUtfChars nameChars(env, name);
  if (nameChars.c_str() == NULL) {
    return static_cast<jlong>(-1);
  }

  ZipArchiveHandle handle;
  int32_t error = OpenArchiveFd(fd, nameChars.c_str(), &handle,
      false /* owned by Java side */);
  if (error) {
    CloseArchive(handle);
    throwIoException(env, error);
    return static_cast<jlong>(-1);
  }

  return reinterpret_cast<jlong>(handle);
}

class IterationHandle {
 public:
  IterationHandle() :
    cookie_(NULL) {
  }

  void** CookieAddress() {
    return &cookie_;
  }

  ~IterationHandle() {
    EndIteration(cookie_);
  }

 private:
  void* cookie_;
};


static jlong StrictJarFile_nativeStartIteration(JNIEnv* env, jobject, jlong nativeHandle,
                                                jstring prefix) {
  ScopedUtfChars prefixChars(env, prefix);
  if (prefixChars.c_str() == NULL) {
    return static_cast<jlong>(-1);
  }

  IterationHandle* handle = new IterationHandle();
  int32_t error = 0;
  if (prefixChars.size() == 0) {
    error = StartIteration(reinterpret_cast<ZipArchiveHandle>(nativeHandle),
                           handle->CookieAddress(), NULL, NULL);
  } else {
    ZipString entry_name(prefixChars.c_str());
    error = StartIteration(reinterpret_cast<ZipArchiveHandle>(nativeHandle),
                           handle->CookieAddress(), &entry_name, NULL);
  }

  if (error) {
    throwIoException(env, error);
    return static_cast<jlong>(-1);
  }

  return reinterpret_cast<jlong>(handle);
}

static jobject StrictJarFile_nativeNextEntry(JNIEnv* env, jobject, jlong iterationHandle) {
  ZipEntry data;
  ZipString entryName;

  IterationHandle* handle = reinterpret_cast<IterationHandle*>(iterationHandle);
  const int32_t error = Next(*handle->CookieAddress(), &data, &entryName);
  if (error) {
    delete handle;
    return NULL;
  }

  std::unique_ptr<char[]> entryNameCString(new char[entryName.name_length + 1]);
  memcpy(entryNameCString.get(), entryName.name, entryName.name_length);
  entryNameCString[entryName.name_length] = '\0';
  ScopedLocalRef<jstring> entryNameString(env, env->NewStringUTF(entryNameCString.get()));

  return newZipEntry(env, data, entryNameString.get());
}

static jobject StrictJarFile_nativeFindEntry(JNIEnv* env, jobject, jlong nativeHandle,
                                             jstring entryName) {
  ScopedUtfChars entryNameChars(env, entryName);
  if (entryNameChars.c_str() == NULL) {
    return NULL;
  }

  ZipEntry data;
  const int32_t error = FindEntry(reinterpret_cast<ZipArchiveHandle>(nativeHandle),
                                  ZipString(entryNameChars.c_str()), &data);
  if (error) {
    return NULL;
  }

  return newZipEntry(env, data, entryName);
}

static void StrictJarFile_nativeClose(JNIEnv*, jobject, jlong nativeHandle) {
  CloseArchive(reinterpret_cast<ZipArchiveHandle>(nativeHandle));
}

static JNINativeMethod gMethods[] = {
  NATIVE_METHOD(StrictJarFile, nativeOpenJarFile, "(Ljava/lang/String;I)J"),
  NATIVE_METHOD(StrictJarFile, nativeStartIteration, "(JLjava/lang/String;)J"),
  NATIVE_METHOD(StrictJarFile, nativeNextEntry, "(J)Ljava/util/zip/ZipEntry;"),
  NATIVE_METHOD(StrictJarFile, nativeFindEntry, "(JLjava/lang/String;)Ljava/util/zip/ZipEntry;"),
  NATIVE_METHOD(StrictJarFile, nativeClose, "(J)V"),
};

int register_android_util_jar_StrictJarFile(JNIEnv* env) {
  jniRegisterNativeMethods(env, "android/util/jar/StrictJarFile", gMethods, NELEM(gMethods));

  zipEntryCtor = env->GetMethodID(JniConstants::zipEntryClass, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;JJJII[BJ)V");
  LOG_ALWAYS_FATAL_IF(zipEntryCtor == NULL, "Unable to find ZipEntry.<init>");

  return 0;
}

}; // namespace android

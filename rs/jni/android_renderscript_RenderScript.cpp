/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

#define LOG_TAG "RenderScript_jni"

#include <stdlib.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <math.h>
#include <utils/misc.h>
#include <inttypes.h>

#include <androidfw/Asset.h>
#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_view_Surface.h"
#include "android_runtime/android_util_AssetManager.h"
#include "android/graphics/GraphicsJNI.h"

#include <rs.h>
#include <rsEnv.h>
#include <gui/Surface.h>
#include <gui/GLConsumer.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>

//#define LOG_API ALOGE
static constexpr bool kLogApi = false;

using namespace android;

template <typename... T>
void UNUSED(T... t) {}

#define PER_ARRAY_TYPE(flag, fnc, readonly, ...) {                                      \
    jint len = 0;                                                                       \
    void *ptr = nullptr;                                                                \
    void *srcPtr = nullptr;                                                             \
    size_t typeBytes = 0;                                                               \
    jint relFlag = 0;                                                                   \
    if (readonly) {                                                                     \
        /* The on-release mode should only be JNI_ABORT for read-only accesses. */      \
        /* readonly = true, also indicates we are copying to the allocation   . */      \
        relFlag = JNI_ABORT;                                                            \
    }                                                                                   \
    switch(dataType) {                                                                  \
    case RS_TYPE_FLOAT_32:                                                              \
        len = _env->GetArrayLength((jfloatArray)data);                                  \
        ptr = _env->GetFloatArrayElements((jfloatArray)data, flag);                     \
        if (ptr == nullptr) {                                                           \
            ALOGE("Failed to get Java array elements.");                                \
            return;                                                                     \
        }                                                                               \
        typeBytes = 4;                                                                  \
        if (usePadding) {                                                               \
            srcPtr = ptr;                                                               \
            len = len / 3 * 4;                                                          \
            if (count == 0) {                                                           \
                count = len / 4;                                                        \
            }                                                                           \
            ptr = malloc (len * typeBytes);                                             \
            if (readonly) {                                                             \
                copyWithPadding(ptr, srcPtr, mSize, count);                             \
                fnc(__VA_ARGS__);                                                       \
            } else {                                                                    \
                fnc(__VA_ARGS__);                                                       \
                copyWithUnPadding(srcPtr, ptr, mSize, count);                           \
            }                                                                           \
            free(ptr);                                                                  \
            ptr = srcPtr;                                                               \
        } else {                                                                        \
            fnc(__VA_ARGS__);                                                           \
        }                                                                               \
        _env->ReleaseFloatArrayElements((jfloatArray)data, (jfloat *)ptr, relFlag);     \
        return;                                                                         \
    case RS_TYPE_FLOAT_64:                                                              \
        len = _env->GetArrayLength((jdoubleArray)data);                                 \
        ptr = _env->GetDoubleArrayElements((jdoubleArray)data, flag);                   \
        if (ptr == nullptr) {                                                           \
            ALOGE("Failed to get Java array elements.");                                \
            return;                                                                     \
        }                                                                               \
        typeBytes = 8;                                                                  \
        if (usePadding) {                                                               \
            srcPtr = ptr;                                                               \
            len = len / 3 * 4;                                                          \
            if (count == 0) {                                                           \
                count = len / 4;                                                        \
            }                                                                           \
            ptr = malloc (len * typeBytes);                                             \
            if (readonly) {                                                             \
                copyWithPadding(ptr, srcPtr, mSize, count);                             \
                fnc(__VA_ARGS__);                                                       \
            } else {                                                                    \
                fnc(__VA_ARGS__);                                                       \
                copyWithUnPadding(srcPtr, ptr, mSize, count);                           \
            }                                                                           \
            free(ptr);                                                                  \
            ptr = srcPtr;                                                               \
        } else {                                                                        \
            fnc(__VA_ARGS__);                                                           \
        }                                                                               \
        _env->ReleaseDoubleArrayElements((jdoubleArray)data, (jdouble *)ptr, relFlag);  \
        return;                                                                         \
    case RS_TYPE_SIGNED_8:                                                              \
    case RS_TYPE_UNSIGNED_8:                                                            \
        len = _env->GetArrayLength((jbyteArray)data);                                   \
        ptr = _env->GetByteArrayElements((jbyteArray)data, flag);                       \
        if (ptr == nullptr) {                                                           \
            ALOGE("Failed to get Java array elements.");                                \
            return;                                                                     \
        }                                                                               \
        typeBytes = 1;                                                                  \
        if (usePadding) {                                                               \
            srcPtr = ptr;                                                               \
            len = len / 3 * 4;                                                          \
            if (count == 0) {                                                           \
                count = len / 4;                                                        \
            }                                                                           \
            ptr = malloc (len * typeBytes);                                             \
            if (readonly) {                                                             \
                copyWithPadding(ptr, srcPtr, mSize, count);                             \
                fnc(__VA_ARGS__);                                                       \
            } else {                                                                    \
                fnc(__VA_ARGS__);                                                       \
                copyWithUnPadding(srcPtr, ptr, mSize, count);                           \
            }                                                                           \
            free(ptr);                                                                  \
            ptr = srcPtr;                                                               \
        } else {                                                                        \
            fnc(__VA_ARGS__);                                                           \
        }                                                                               \
        _env->ReleaseByteArrayElements((jbyteArray)data, (jbyte*)ptr, relFlag);         \
        return;                                                                         \
    case RS_TYPE_SIGNED_16:                                                             \
    case RS_TYPE_UNSIGNED_16:                                                           \
    case RS_TYPE_FLOAT_16:                                                              \
        len = _env->GetArrayLength((jshortArray)data);                                  \
        ptr = _env->GetShortArrayElements((jshortArray)data, flag);                     \
        if (ptr == nullptr) {                                                           \
            ALOGE("Failed to get Java array elements.");                                \
            return;                                                                     \
        }                                                                               \
        typeBytes = 2;                                                                  \
        if (usePadding) {                                                               \
            srcPtr = ptr;                                                               \
            len = len / 3 * 4;                                                          \
            if (count == 0) {                                                           \
                count = len / 4;                                                        \
            }                                                                           \
            ptr = malloc (len * typeBytes);                                             \
            if (readonly) {                                                             \
                copyWithPadding(ptr, srcPtr, mSize, count);                             \
                fnc(__VA_ARGS__);                                                       \
            } else {                                                                    \
                fnc(__VA_ARGS__);                                                       \
                copyWithUnPadding(srcPtr, ptr, mSize, count);                           \
            }                                                                           \
            free(ptr);                                                                  \
            ptr = srcPtr;                                                               \
        } else {                                                                        \
            fnc(__VA_ARGS__);                                                           \
        }                                                                               \
        _env->ReleaseShortArrayElements((jshortArray)data, (jshort *)ptr, relFlag);     \
        return;                                                                         \
    case RS_TYPE_SIGNED_32:                                                             \
    case RS_TYPE_UNSIGNED_32:                                                           \
        len = _env->GetArrayLength((jintArray)data);                                    \
        ptr = _env->GetIntArrayElements((jintArray)data, flag);                         \
        if (ptr == nullptr) {                                                           \
            ALOGE("Failed to get Java array elements.");                                \
            return;                                                                     \
        }                                                                               \
        typeBytes = 4;                                                                  \
        if (usePadding) {                                                               \
            srcPtr = ptr;                                                               \
            len = len / 3 * 4;                                                          \
            if (count == 0) {                                                           \
                count = len / 4;                                                        \
            }                                                                           \
            ptr = malloc (len * typeBytes);                                             \
            if (readonly) {                                                             \
                copyWithPadding(ptr, srcPtr, mSize, count);                             \
                fnc(__VA_ARGS__);                                                       \
            } else {                                                                    \
                fnc(__VA_ARGS__);                                                       \
                copyWithUnPadding(srcPtr, ptr, mSize, count);                           \
            }                                                                           \
            free(ptr);                                                                  \
            ptr = srcPtr;                                                               \
        } else {                                                                        \
            fnc(__VA_ARGS__);                                                           \
        }                                                                               \
        _env->ReleaseIntArrayElements((jintArray)data, (jint *)ptr, relFlag);           \
        return;                                                                         \
    case RS_TYPE_SIGNED_64:                                                             \
    case RS_TYPE_UNSIGNED_64:                                                           \
        len = _env->GetArrayLength((jlongArray)data);                                   \
        ptr = _env->GetLongArrayElements((jlongArray)data, flag);                       \
        if (ptr == nullptr) {                                                           \
            ALOGE("Failed to get Java array elements.");                                \
            return;                                                                     \
        }                                                                               \
        typeBytes = 8;                                                                  \
        if (usePadding) {                                                               \
            srcPtr = ptr;                                                               \
            len = len / 3 * 4;                                                          \
            if (count == 0) {                                                           \
                count = len / 4;                                                        \
            }                                                                           \
            ptr = malloc (len * typeBytes);                                             \
            if (readonly) {                                                             \
                copyWithPadding(ptr, srcPtr, mSize, count);                             \
                fnc(__VA_ARGS__);                                                       \
            } else {                                                                    \
                fnc(__VA_ARGS__);                                                       \
                copyWithUnPadding(srcPtr, ptr, mSize, count);                           \
            }                                                                           \
            free(ptr);                                                                  \
            ptr = srcPtr;                                                               \
        } else {                                                                        \
            fnc(__VA_ARGS__);                                                           \
        }                                                                               \
        _env->ReleaseLongArrayElements((jlongArray)data, (jlong *)ptr, relFlag);        \
        return;                                                                         \
    default:                                                                            \
        break;                                                                          \
    }                                                                                   \
    UNUSED(len, ptr, srcPtr, typeBytes, relFlag);                                       \
}


class AutoJavaStringToUTF8 {
public:
    AutoJavaStringToUTF8(JNIEnv* env, jstring str) : fEnv(env), fJStr(str) {
        fCStr = env->GetStringUTFChars(str, nullptr);
        fLength = env->GetStringUTFLength(str);
    }
    ~AutoJavaStringToUTF8() {
        fEnv->ReleaseStringUTFChars(fJStr, fCStr);
    }
    const char* c_str() const { return fCStr; }
    jsize length() const { return fLength; }

private:
    JNIEnv*     fEnv;
    jstring     fJStr;
    const char* fCStr;
    jsize       fLength;
};

class AutoJavaStringArrayToUTF8 {
public:
    AutoJavaStringArrayToUTF8(JNIEnv* env, jobjectArray strings, jsize stringsLength)
    : mEnv(env), mStrings(strings), mStringsLength(stringsLength) {
        mCStrings = nullptr;
        mSizeArray = nullptr;
        if (stringsLength > 0) {
            mCStrings = (const char **)calloc(stringsLength, sizeof(char *));
            mSizeArray = (size_t*)calloc(stringsLength, sizeof(size_t));
            for (jsize ct = 0; ct < stringsLength; ct ++) {
                jstring s = (jstring)mEnv->GetObjectArrayElement(mStrings, ct);
                mCStrings[ct] = mEnv->GetStringUTFChars(s, nullptr);
                mSizeArray[ct] = mEnv->GetStringUTFLength(s);
            }
        }
    }
    ~AutoJavaStringArrayToUTF8() {
        for (jsize ct=0; ct < mStringsLength; ct++) {
            jstring s = (jstring)mEnv->GetObjectArrayElement(mStrings, ct);
            mEnv->ReleaseStringUTFChars(s, mCStrings[ct]);
        }
        free(mCStrings);
        free(mSizeArray);
    }
    const char **c_str() const { return mCStrings; }
    size_t *c_str_len() const { return mSizeArray; }
    jsize length() const { return mStringsLength; }

private:
    JNIEnv      *mEnv;
    jobjectArray mStrings;
    const char **mCStrings;
    size_t      *mSizeArray;
    jsize        mStringsLength;
};

// ---------------------------------------------------------------------------

static jfieldID gContextId = 0;

static void _nInit(JNIEnv *_env, jclass _this)
{
    gContextId             = _env->GetFieldID(_this, "mContext", "J");
}

// ---------------------------------------------------------------------------

static void copyWithPadding(void* ptr, void* srcPtr, int mSize, int count) {
    int sizeBytesPad = mSize * 4;
    int sizeBytes = mSize * 3;
    uint8_t *dst = static_cast<uint8_t *>(ptr);
    uint8_t *src = static_cast<uint8_t *>(srcPtr);
    for (int i = 0; i < count; i++) {
        memcpy(dst, src, sizeBytes);
        dst += sizeBytesPad;
        src += sizeBytes;
    }
}

static void copyWithUnPadding(void* ptr, void* srcPtr, int mSize, int count) {
    int sizeBytesPad = mSize * 4;
    int sizeBytes = mSize * 3;
    uint8_t *dst = static_cast<uint8_t *>(ptr);
    uint8_t *src = static_cast<uint8_t *>(srcPtr);
    for (int i = 0; i < count; i++) {
        memcpy(dst, src, sizeBytes);
        dst += sizeBytes;
        src += sizeBytesPad;
    }
}


// ---------------------------------------------------------------------------
static void
nContextFinish(JNIEnv *_env, jobject _this, jlong con)
{
    if (kLogApi) {
        ALOGD("nContextFinish, con(%p)", (RsContext)con);
    }
    rsContextFinish((RsContext)con);
}

static jlong
nClosureCreate(JNIEnv *_env, jobject _this, jlong con, jlong kernelID,
               jlong returnValue, jlongArray fieldIDArray,
               jlongArray valueArray, jintArray sizeArray,
               jlongArray depClosureArray, jlongArray depFieldIDArray) {
  jlong ret = 0;

  jlong* jFieldIDs = _env->GetLongArrayElements(fieldIDArray, nullptr);
  jsize fieldIDs_length = _env->GetArrayLength(fieldIDArray);
  if (jFieldIDs == nullptr) {
      ALOGE("Failed to get Java array elements: fieldIDs.");
      return ret;
  }

  jlong* jValues = _env->GetLongArrayElements(valueArray, nullptr);
  jsize values_length = _env->GetArrayLength(valueArray);
  if (jValues == nullptr) {
      ALOGE("Failed to get Java array elements: values.");
      return ret;
  }

  jint* jSizes = _env->GetIntArrayElements(sizeArray, nullptr);
  jsize sizes_length = _env->GetArrayLength(sizeArray);
  if (jSizes == nullptr) {
      ALOGE("Failed to get Java array elements: sizes.");
      return ret;
  }

  jlong* jDepClosures =
      _env->GetLongArrayElements(depClosureArray, nullptr);
  jsize depClosures_length = _env->GetArrayLength(depClosureArray);
  if (jDepClosures == nullptr) {
      ALOGE("Failed to get Java array elements: depClosures.");
      return ret;
  }

  jlong* jDepFieldIDs =
      _env->GetLongArrayElements(depFieldIDArray, nullptr);
  jsize depFieldIDs_length = _env->GetArrayLength(depFieldIDArray);
  if (jDepFieldIDs == nullptr) {
      ALOGE("Failed to get Java array elements: depFieldIDs.");
      return ret;
  }

  size_t numValues, numDependencies;
  RsScriptFieldID* fieldIDs;
  RsClosure* depClosures;
  RsScriptFieldID* depFieldIDs;

  if (fieldIDs_length != values_length || values_length != sizes_length) {
      ALOGE("Unmatched field IDs, values, and sizes in closure creation.");
      goto exit;
  }

  numValues = (size_t)fieldIDs_length;

  if (depClosures_length != depFieldIDs_length) {
      ALOGE("Unmatched closures and field IDs for dependencies in closure creation.");
      goto exit;
  }

  numDependencies = (size_t)depClosures_length;

  if (numDependencies > numValues) {
      ALOGE("Unexpected number of dependencies in closure creation");
      goto exit;
  }

  if (numValues > RS_CLOSURE_MAX_NUMBER_ARGS_AND_BINDINGS) {
      ALOGE("Too many arguments or globals in closure creation");
      goto exit;
  }

  fieldIDs = (RsScriptFieldID*)alloca(sizeof(RsScriptFieldID) * numValues);
  if (fieldIDs == nullptr) {
      goto exit;
  }

  for (size_t i = 0; i < numValues; i++) {
    fieldIDs[i] = (RsScriptFieldID)jFieldIDs[i];
  }

  depClosures = (RsClosure*)alloca(sizeof(RsClosure) * numDependencies);
  if (depClosures == nullptr) {
      goto exit;
  }

  for (size_t i = 0; i < numDependencies; i++) {
    depClosures[i] = (RsClosure)jDepClosures[i];
  }

  depFieldIDs = (RsScriptFieldID*)alloca(sizeof(RsScriptFieldID) * numDependencies);
  if (depFieldIDs == nullptr) {
      goto exit;
  }

  for (size_t i = 0; i < numDependencies; i++) {
    depFieldIDs[i] = (RsClosure)jDepFieldIDs[i];
  }

  ret = (jlong)(uintptr_t)rsClosureCreate(
      (RsContext)con, (RsScriptKernelID)kernelID, (RsAllocation)returnValue,
      fieldIDs, numValues, jValues, numValues,
      (int*)jSizes, numValues,
      depClosures, numDependencies,
      depFieldIDs, numDependencies);

exit:

  _env->ReleaseLongArrayElements(depFieldIDArray, jDepFieldIDs, JNI_ABORT);
  _env->ReleaseLongArrayElements(depClosureArray, jDepClosures, JNI_ABORT);
  _env->ReleaseIntArrayElements (sizeArray,       jSizes,       JNI_ABORT);
  _env->ReleaseLongArrayElements(valueArray,      jValues,      JNI_ABORT);
  _env->ReleaseLongArrayElements(fieldIDArray,    jFieldIDs,    JNI_ABORT);

  return ret;
}

static jlong
nInvokeClosureCreate(JNIEnv *_env, jobject _this, jlong con, jlong invokeID,
                     jbyteArray paramArray, jlongArray fieldIDArray, jlongArray valueArray,
                     jintArray sizeArray) {
  jlong ret = 0;

  jbyte* jParams = _env->GetByteArrayElements(paramArray, nullptr);
  jsize jParamLength = _env->GetArrayLength(paramArray);
  if (jParams == nullptr) {
      ALOGE("Failed to get Java array elements: params.");
      return ret;
  }

  jlong* jFieldIDs = _env->GetLongArrayElements(fieldIDArray, nullptr);
  jsize fieldIDs_length = _env->GetArrayLength(fieldIDArray);
  if (jFieldIDs == nullptr) {
      ALOGE("Failed to get Java array elements: fieldIDs.");
      return ret;
  }

  jlong* jValues = _env->GetLongArrayElements(valueArray, nullptr);
  jsize values_length = _env->GetArrayLength(valueArray);
  if (jValues == nullptr) {
      ALOGE("Failed to get Java array elements: values.");
      return ret;
  }

  jint* jSizes = _env->GetIntArrayElements(sizeArray, nullptr);
  jsize sizes_length = _env->GetArrayLength(sizeArray);
  if (jSizes == nullptr) {
      ALOGE("Failed to get Java array elements: sizes.");
      return ret;
  }

  size_t numValues;
  RsScriptFieldID* fieldIDs;

  if (fieldIDs_length != values_length || values_length != sizes_length) {
      ALOGE("Unmatched field IDs, values, and sizes in closure creation.");
      goto exit;
  }

  numValues = (size_t) fieldIDs_length;

  if (numValues > RS_CLOSURE_MAX_NUMBER_ARGS_AND_BINDINGS) {
      ALOGE("Too many arguments or globals in closure creation");
      goto exit;
  }

  fieldIDs = (RsScriptFieldID*)alloca(sizeof(RsScriptFieldID) * numValues);
  if (fieldIDs == nullptr) {
      goto exit;
  }

  for (size_t i = 0; i< numValues; i++) {
    fieldIDs[i] = (RsScriptFieldID)jFieldIDs[i];
  }

  ret = (jlong)(uintptr_t)rsInvokeClosureCreate(
      (RsContext)con, (RsScriptInvokeID)invokeID, jParams, jParamLength,
      fieldIDs, numValues, jValues, numValues,
      (int*)jSizes, numValues);

exit:

  _env->ReleaseIntArrayElements (sizeArray,       jSizes,       JNI_ABORT);
  _env->ReleaseLongArrayElements(valueArray,      jValues,      JNI_ABORT);
  _env->ReleaseLongArrayElements(fieldIDArray,    jFieldIDs,    JNI_ABORT);
  _env->ReleaseByteArrayElements(paramArray,      jParams,      JNI_ABORT);

  return ret;
}

static void
nClosureSetArg(JNIEnv *_env, jobject _this, jlong con, jlong closureID,
               jint index, jlong value, jint size) {
  // Size is signed with -1 indicating the value is an Allocation
  rsClosureSetArg((RsContext)con, (RsClosure)closureID, (uint32_t)index,
                  (uintptr_t)value, size);
}

static void
nClosureSetGlobal(JNIEnv *_env, jobject _this, jlong con, jlong closureID,
                  jlong fieldID, jlong value, jint size) {
  // Size is signed with -1 indicating the value is an Allocation
  rsClosureSetGlobal((RsContext)con, (RsClosure)closureID,
                     (RsScriptFieldID)fieldID, (int64_t)value, size);
}

static long
nScriptGroup2Create(JNIEnv *_env, jobject _this, jlong con, jstring name,
                    jstring cacheDir, jlongArray closureArray) {
  jlong ret = 0;

  AutoJavaStringToUTF8 nameUTF(_env, name);
  AutoJavaStringToUTF8 cacheDirUTF(_env, cacheDir);

  jlong* jClosures = _env->GetLongArrayElements(closureArray, nullptr);
  jsize numClosures = _env->GetArrayLength(closureArray);
  if (jClosures == nullptr) {
      ALOGE("Failed to get Java array elements: closures.");
      return ret;
  }

  RsClosure* closures;

  if (numClosures > (jsize) RS_SCRIPT_GROUP_MAX_NUMBER_CLOSURES) {
    ALOGE("Too many closures in script group");
    goto exit;
  }

  closures = (RsClosure*)alloca(sizeof(RsClosure) * numClosures);
  if (closures == nullptr) {
      goto exit;
  }

  for (int i = 0; i < numClosures; i++) {
    closures[i] = (RsClosure)jClosures[i];
  }

  ret = (jlong)(uintptr_t)rsScriptGroup2Create(
      (RsContext)con, nameUTF.c_str(), nameUTF.length(),
      cacheDirUTF.c_str(), cacheDirUTF.length(),
      closures, numClosures);

exit:

  _env->ReleaseLongArrayElements(closureArray, jClosures, JNI_ABORT);

  return ret;
}

static void
nScriptGroup2Execute(JNIEnv *_env, jobject _this, jlong con, jlong groupID) {
  rsScriptGroupExecute((RsContext)con, (RsScriptGroup2)groupID);
}

static void
nScriptIntrinsicBLAS_Single(JNIEnv *_env, jobject _this, jlong con, jlong id, jint func, jint TransA,
                            jint TransB, jint Side, jint Uplo, jint Diag, jint M, jint N, jint K,
                            jfloat alpha, jlong A, jlong B, jfloat beta, jlong C, jint incX, jint incY,
                            jint KL, jint KU) {
    RsBlasCall call;
    memset(&call, 0, sizeof(call));
    call.func = (RsBlasFunction)func;
    call.transA = (RsBlasTranspose)TransA;
    call.transB = (RsBlasTranspose)TransB;
    call.side = (RsBlasSide)Side;
    call.uplo = (RsBlasUplo)Uplo;
    call.diag = (RsBlasDiag)Diag;
    call.M = M;
    call.N = N;
    call.K = K;
    call.alpha.f = alpha;
    call.beta.f = beta;
    call.incX = incX;
    call.incY = incY;
    call.KL = KL;
    call.KU = KU;

    RsAllocation in_allocs[3];
    in_allocs[0] = (RsAllocation)A;
    in_allocs[1] = (RsAllocation)B;
    in_allocs[2] = (RsAllocation)C;

    rsScriptForEachMulti((RsContext)con, (RsScript)id, 0,
                         in_allocs, NELEM(in_allocs), nullptr,
                         &call, sizeof(call), nullptr, 0);
}

static void
nScriptIntrinsicBLAS_Double(JNIEnv *_env, jobject _this, jlong con, jlong id, jint func, jint TransA,
                            jint TransB, jint Side, jint Uplo, jint Diag, jint M, jint N, jint K,
                            jdouble alpha, jlong A, jlong B, jdouble beta, jlong C, jint incX, jint incY,
                            jint KL, jint KU) {
    RsBlasCall call;
    memset(&call, 0, sizeof(call));
    call.func = (RsBlasFunction)func;
    call.transA = (RsBlasTranspose)TransA;
    call.transB = (RsBlasTranspose)TransB;
    call.side = (RsBlasSide)Side;
    call.uplo = (RsBlasUplo)Uplo;
    call.diag = (RsBlasDiag)Diag;
    call.M = M;
    call.N = N;
    call.K = K;
    call.alpha.d = alpha;
    call.beta.d = beta;
    call.incX = incX;
    call.incY = incY;
    call.KL = KL;
    call.KU = KU;

    RsAllocation in_allocs[3];
    in_allocs[0] = (RsAllocation)A;
    in_allocs[1] = (RsAllocation)B;
    in_allocs[2] = (RsAllocation)C;

    rsScriptForEachMulti((RsContext)con, (RsScript)id, 0,
                         in_allocs, NELEM(in_allocs), nullptr,
                         &call, sizeof(call), nullptr, 0);
}

static void
nScriptIntrinsicBLAS_Complex(JNIEnv *_env, jobject _this, jlong con, jlong id, jint func, jint TransA,
                             jint TransB, jint Side, jint Uplo, jint Diag, jint M, jint N, jint K,
                             jfloat alphaX, jfloat alphaY, jlong A, jlong B, jfloat betaX,
                             jfloat betaY, jlong C, jint incX, jint incY, jint KL, jint KU) {
    RsBlasCall call;
    memset(&call, 0, sizeof(call));
    call.func = (RsBlasFunction)func;
    call.transA = (RsBlasTranspose)TransA;
    call.transB = (RsBlasTranspose)TransB;
    call.side = (RsBlasSide)Side;
    call.uplo = (RsBlasUplo)Uplo;
    call.diag = (RsBlasDiag)Diag;
    call.M = M;
    call.N = N;
    call.K = K;
    call.alpha.c.r = alphaX;
    call.alpha.c.i = alphaY;
    call.beta.c.r = betaX;
    call.beta.c.i = betaY;
    call.incX = incX;
    call.incY = incY;
    call.KL = KL;
    call.KU = KU;

    RsAllocation in_allocs[3];
    in_allocs[0] = (RsAllocation)A;
    in_allocs[1] = (RsAllocation)B;
    in_allocs[2] = (RsAllocation)C;

    rsScriptForEachMulti((RsContext)con, (RsScript)id, 0,
                         in_allocs, NELEM(in_allocs), nullptr,
                         &call, sizeof(call), nullptr, 0);
}

static void
nScriptIntrinsicBLAS_Z(JNIEnv *_env, jobject _this, jlong con, jlong id, jint func, jint TransA,
                       jint TransB, jint Side, jint Uplo, jint Diag, jint M, jint N, jint K,
                       jdouble alphaX, jdouble alphaY, jlong A, jlong B, jdouble betaX,
                       jdouble betaY, jlong C, jint incX, jint incY, jint KL, jint KU) {
    RsBlasCall call;
    memset(&call, 0, sizeof(call));
    call.func = (RsBlasFunction)func;
    call.transA = (RsBlasTranspose)TransA;
    call.transB = (RsBlasTranspose)TransB;
    call.side = (RsBlasSide)Side;
    call.uplo = (RsBlasUplo)Uplo;
    call.diag = (RsBlasDiag)Diag;
    call.M = M;
    call.N = N;
    call.K = K;
    call.alpha.z.r = alphaX;
    call.alpha.z.i = alphaY;
    call.beta.z.r = betaX;
    call.beta.z.i = betaY;
    call.incX = incX;
    call.incY = incY;
    call.KL = KL;
    call.KU = KU;

    RsAllocation in_allocs[3];
    in_allocs[0] = (RsAllocation)A;
    in_allocs[1] = (RsAllocation)B;
    in_allocs[2] = (RsAllocation)C;

    rsScriptForEachMulti((RsContext)con, (RsScript)id, 0,
                         in_allocs, NELEM(in_allocs), nullptr,
                         &call, sizeof(call), nullptr, 0);
}


static void
nScriptIntrinsicBLAS_BNNM(JNIEnv *_env, jobject _this, jlong con, jlong id, jint M, jint N, jint K,
                                             jlong A, jint a_offset, jlong B, jint b_offset, jlong C, jint c_offset,
                                             jint c_mult_int) {
    RsBlasCall call;
    memset(&call, 0, sizeof(call));
    call.func = RsBlas_bnnm;
    call.M = M;
    call.N = N;
    call.K = K;
    call.a_offset = a_offset & 0xFF;
    call.b_offset = b_offset & 0xFF;
    call.c_offset = c_offset;
    call.c_mult_int = c_mult_int;

    RsAllocation in_allocs[3];
    in_allocs[0] = (RsAllocation)A;
    in_allocs[1] = (RsAllocation)B;
    in_allocs[2] = (RsAllocation)C;

    rsScriptForEachMulti((RsContext)con, (RsScript)id, 0,
                         in_allocs, NELEM(in_allocs), nullptr,
                         &call, sizeof(call), nullptr, 0);
}


static void
nAssignName(JNIEnv *_env, jobject _this, jlong con, jlong obj, jbyteArray str)
{
    if (kLogApi) {
        ALOGD("nAssignName, con(%p), obj(%p)", (RsContext)con, (void *)obj);
    }
    jint len = _env->GetArrayLength(str);
    jbyte * cptr = (jbyte *) _env->GetPrimitiveArrayCritical(str, 0);
    if (cptr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return;
    }

    rsAssignName((RsContext)con, (void *)obj, (const char *)cptr, len);
    _env->ReleasePrimitiveArrayCritical(str, cptr, JNI_ABORT);
}

static jstring
nGetName(JNIEnv *_env, jobject _this, jlong con, jlong obj)
{
    if (kLogApi) {
        ALOGD("nGetName, con(%p), obj(%p)", (RsContext)con, (void *)obj);
    }
    const char *name = nullptr;
    rsaGetName((RsContext)con, (void *)obj, &name);
    if(name == nullptr || strlen(name) == 0) {
        return nullptr;
    }
    return _env->NewStringUTF(name);
}

static void
nObjDestroy(JNIEnv *_env, jobject _this, jlong con, jlong obj)
{
    if (kLogApi) {
        ALOGD("nObjDestroy, con(%p) obj(%p)", (RsContext)con, (void *)obj);
    }
    rsObjDestroy((RsContext)con, (void *)obj);
}

// ---------------------------------------------------------------------------

static jlong
nDeviceCreate(JNIEnv *_env, jobject _this)
{
    if (kLogApi) {
        ALOGD("nDeviceCreate");
    }
    return (jlong)(uintptr_t)rsDeviceCreate();
}

static void
nDeviceDestroy(JNIEnv *_env, jobject _this, jlong dev)
{
    if (kLogApi) {
        ALOGD("nDeviceDestroy");
    }
    return rsDeviceDestroy((RsDevice)dev);
}

static void
nDeviceSetConfig(JNIEnv *_env, jobject _this, jlong dev, jint p, jint value)
{
    if (kLogApi) {
        ALOGD("nDeviceSetConfig  dev(%p), param(%i), value(%i)", (void *)dev, p, value);
    }
    return rsDeviceSetConfig((RsDevice)dev, (RsDeviceParam)p, value);
}

static jlong
nContextCreate(JNIEnv *_env, jobject _this, jlong dev, jint flags, jint sdkVer, jint contextType)
{
    if (kLogApi) {
        ALOGD("nContextCreate");
    }
    return (jlong)(uintptr_t)rsContextCreate((RsDevice)dev, 0, sdkVer, (RsContextType)contextType, flags);
}

static jlong
nContextCreateGL(JNIEnv *_env, jobject _this, jlong dev, jint ver, jint sdkVer,
                 jint colorMin, jint colorPref,
                 jint alphaMin, jint alphaPref,
                 jint depthMin, jint depthPref,
                 jint stencilMin, jint stencilPref,
                 jint samplesMin, jint samplesPref, jfloat samplesQ,
                 jint dpi)
{
    RsSurfaceConfig sc;
    sc.alphaMin = alphaMin;
    sc.alphaPref = alphaPref;
    sc.colorMin = colorMin;
    sc.colorPref = colorPref;
    sc.depthMin = depthMin;
    sc.depthPref = depthPref;
    sc.samplesMin = samplesMin;
    sc.samplesPref = samplesPref;
    sc.samplesQ = samplesQ;

    if (kLogApi) {
        ALOGD("nContextCreateGL");
    }
    return (jlong)(uintptr_t)rsContextCreateGL((RsDevice)dev, ver, sdkVer, sc, dpi);
}

static void
nContextSetPriority(JNIEnv *_env, jobject _this, jlong con, jint p)
{
    if (kLogApi) {
        ALOGD("ContextSetPriority, con(%p), priority(%i)", (RsContext)con, p);
    }
    rsContextSetPriority((RsContext)con, p);
}

static void
nContextSetCacheDir(JNIEnv *_env, jobject _this, jlong con, jstring cacheDir)
{
    AutoJavaStringToUTF8 cacheDirUTF(_env, cacheDir);

    if (kLogApi) {
        ALOGD("ContextSetCacheDir, con(%p), cacheDir(%s)", (RsContext)con, cacheDirUTF.c_str());
    }
    rsContextSetCacheDir((RsContext)con, cacheDirUTF.c_str(), cacheDirUTF.length());
}



static void
nContextSetSurface(JNIEnv *_env, jobject _this, jlong con, jint width, jint height, jobject wnd)
{
    if (kLogApi) {
        ALOGD("nContextSetSurface, con(%p), width(%i), height(%i), surface(%p)", (RsContext)con,
              width, height, (Surface *)wnd);
    }

    ANativeWindow * window = nullptr;
    if (wnd == nullptr) {

    } else {
        window = android_view_Surface_getNativeWindow(_env, wnd).get();
    }

    rsContextSetSurface((RsContext)con, width, height, window);
}

static void
nContextDestroy(JNIEnv *_env, jobject _this, jlong con)
{
    if (kLogApi) {
        ALOGD("nContextDestroy, con(%p)", (RsContext)con);
    }
    rsContextDestroy((RsContext)con);
}

static void
nContextDump(JNIEnv *_env, jobject _this, jlong con, jint bits)
{
    if (kLogApi) {
        ALOGD("nContextDump, con(%p)  bits(%i)", (RsContext)con, bits);
    }
    rsContextDump((RsContext)con, bits);
}

static void
nContextPause(JNIEnv *_env, jobject _this, jlong con)
{
    if (kLogApi) {
        ALOGD("nContextPause, con(%p)", (RsContext)con);
    }
    rsContextPause((RsContext)con);
}

static void
nContextResume(JNIEnv *_env, jobject _this, jlong con)
{
    if (kLogApi) {
        ALOGD("nContextResume, con(%p)", (RsContext)con);
    }
    rsContextResume((RsContext)con);
}


static jstring
nContextGetErrorMessage(JNIEnv *_env, jobject _this, jlong con)
{
    if (kLogApi) {
        ALOGD("nContextGetErrorMessage, con(%p)", (RsContext)con);
    }
    char buf[1024];

    size_t receiveLen;
    uint32_t subID;
    int id = rsContextGetMessage((RsContext)con,
                                 buf, sizeof(buf),
                                 &receiveLen, sizeof(receiveLen),
                                 &subID, sizeof(subID));
    if (!id && receiveLen) {
        ALOGV("message receive buffer too small.  %zu", receiveLen);
    }
    return _env->NewStringUTF(buf);
}

static jint
nContextGetUserMessage(JNIEnv *_env, jobject _this, jlong con, jintArray data)
{
    jint len = _env->GetArrayLength(data);
    if (kLogApi) {
        ALOGD("nContextGetMessage, con(%p), len(%i)", (RsContext)con, len);
    }
    jint *ptr = _env->GetIntArrayElements(data, nullptr);
    if (ptr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return 0;
    }
    size_t receiveLen;
    uint32_t subID;
    int id = rsContextGetMessage((RsContext)con,
                                 ptr, len * 4,
                                 &receiveLen, sizeof(receiveLen),
                                 &subID, sizeof(subID));
    if (!id && receiveLen) {
        ALOGV("message receive buffer too small.  %zu", receiveLen);
    }
    _env->ReleaseIntArrayElements(data, ptr, 0);
    return (jint)id;
}

static jint
nContextPeekMessage(JNIEnv *_env, jobject _this, jlong con, jintArray auxData)
{
    if (kLogApi) {
        ALOGD("nContextPeekMessage, con(%p)", (RsContext)con);
    }
    jint *auxDataPtr = _env->GetIntArrayElements(auxData, nullptr);
    if (auxDataPtr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return 0;
    }
    size_t receiveLen;
    uint32_t subID;
    int id = rsContextPeekMessage((RsContext)con, &receiveLen, sizeof(receiveLen),
                                  &subID, sizeof(subID));
    auxDataPtr[0] = (jint)subID;
    auxDataPtr[1] = (jint)receiveLen;
    _env->ReleaseIntArrayElements(auxData, auxDataPtr, 0);
    return (jint)id;
}

static void nContextInitToClient(JNIEnv *_env, jobject _this, jlong con)
{
    if (kLogApi) {
        ALOGD("nContextInitToClient, con(%p)", (RsContext)con);
    }
    rsContextInitToClient((RsContext)con);
}

static void nContextDeinitToClient(JNIEnv *_env, jobject _this, jlong con)
{
    if (kLogApi) {
        ALOGD("nContextDeinitToClient, con(%p)", (RsContext)con);
    }
    rsContextDeinitToClient((RsContext)con);
}

static void
nContextSendMessage(JNIEnv *_env, jobject _this, jlong con, jint id, jintArray data)
{
    jint *ptr = nullptr;
    jint len = 0;
    if (data) {
        len = _env->GetArrayLength(data);
        ptr = _env->GetIntArrayElements(data, nullptr);
        if (ptr == nullptr) {
            ALOGE("Failed to get Java array elements");
            return;
        }
    }
    if (kLogApi) {
        ALOGD("nContextSendMessage, con(%p), id(%i), len(%i)", (RsContext)con, id, len);
    }
    rsContextSendMessage((RsContext)con, id, (const uint8_t *)ptr, len * sizeof(int));
    if (data) {
        _env->ReleaseIntArrayElements(data, ptr, JNI_ABORT);
    }
}



static jlong
nElementCreate(JNIEnv *_env, jobject _this, jlong con, jlong type, jint kind, jboolean norm,
               jint size)
{
    if (kLogApi) {
        ALOGD("nElementCreate, con(%p), type(%" PRId64 "), kind(%i), norm(%i), size(%i)", (RsContext)con,
              type, kind, norm, size);
    }
    return (jlong)(uintptr_t)rsElementCreate((RsContext)con, (RsDataType)type, (RsDataKind)kind,
                                             norm, size);
}

static jlong
nElementCreate2(JNIEnv *_env, jobject _this, jlong con,
                jlongArray _ids, jobjectArray _names, jintArray _arraySizes)
{
    int fieldCount = _env->GetArrayLength(_ids);
    if (kLogApi) {
        ALOGD("nElementCreate2, con(%p)", (RsContext)con);
    }

    jlong *jIds = _env->GetLongArrayElements(_ids, nullptr);
    if (jIds == nullptr) {
        ALOGE("Failed to get Java array elements: ids");
        return 0;
    }
    jint *jArraySizes = _env->GetIntArrayElements(_arraySizes, nullptr);
    if (jArraySizes == nullptr) {
        ALOGE("Failed to get Java array elements: arraySizes");
        return 0;
    }

    RsElement *ids = (RsElement*)malloc(fieldCount * sizeof(RsElement));
    uint32_t *arraySizes = (uint32_t *)malloc(fieldCount * sizeof(uint32_t));

    for(int i = 0; i < fieldCount; i ++) {
        ids[i] = (RsElement)jIds[i];
        arraySizes[i] = (uint32_t)jArraySizes[i];
    }

    AutoJavaStringArrayToUTF8 names(_env, _names, fieldCount);

    const char **nameArray = names.c_str();
    size_t *sizeArray = names.c_str_len();

    jlong id = (jlong)(uintptr_t)rsElementCreate2((RsContext)con,
                                     (const RsElement *)ids, fieldCount,
                                     nameArray, fieldCount * sizeof(size_t),  sizeArray,
                                     (const uint32_t *)arraySizes, fieldCount);

    free(ids);
    free(arraySizes);
    _env->ReleaseLongArrayElements(_ids, jIds, JNI_ABORT);
    _env->ReleaseIntArrayElements(_arraySizes, jArraySizes, JNI_ABORT);

    return (jlong)(uintptr_t)id;
}

static void
nElementGetNativeData(JNIEnv *_env, jobject _this, jlong con, jlong id, jintArray _elementData)
{
    int dataSize = _env->GetArrayLength(_elementData);
    if (kLogApi) {
        ALOGD("nElementGetNativeData, con(%p)", (RsContext)con);
    }

    // we will pack mType; mKind; mNormalized; mVectorSize; NumSubElements
    assert(dataSize == 5);

    uintptr_t elementData[5];
    rsaElementGetNativeData((RsContext)con, (RsElement)id, elementData, dataSize);

    for(jint i = 0; i < dataSize; i ++) {
        const jint data = (jint)elementData[i];
        _env->SetIntArrayRegion(_elementData, i, 1, &data);
    }
}


static void
nElementGetSubElements(JNIEnv *_env, jobject _this, jlong con, jlong id,
                       jlongArray _IDs,
                       jobjectArray _names,
                       jintArray _arraySizes)
{
    uint32_t dataSize = _env->GetArrayLength(_IDs);
    if (kLogApi) {
        ALOGD("nElementGetSubElements, con(%p)", (RsContext)con);
    }

    uintptr_t *ids = (uintptr_t*)malloc(dataSize * sizeof(uintptr_t));
    const char **names = (const char **)malloc(dataSize * sizeof(const char *));
    uint32_t *arraySizes = (uint32_t *)malloc(dataSize * sizeof(uint32_t));

    rsaElementGetSubElements((RsContext)con, (RsElement)id, ids, names, arraySizes,
                             (uint32_t)dataSize);

    for(uint32_t i = 0; i < dataSize; i++) {
        const jlong id = (jlong)(uintptr_t)ids[i];
        const jint arraySize = (jint)arraySizes[i];
        _env->SetObjectArrayElement(_names, i, _env->NewStringUTF(names[i]));
        _env->SetLongArrayRegion(_IDs, i, 1, &id);
        _env->SetIntArrayRegion(_arraySizes, i, 1, &arraySize);
    }

    free(ids);
    free(names);
    free(arraySizes);
}

// -----------------------------------

static jlong
nTypeCreate(JNIEnv *_env, jobject _this, jlong con, jlong eid,
            jint dimx, jint dimy, jint dimz, jboolean mips, jboolean faces, jint yuv)
{
    if (kLogApi) {
        ALOGD("nTypeCreate, con(%p) eid(%p), x(%i), y(%i), z(%i), mips(%i), faces(%i), yuv(%i)",
              (RsContext)con, (void*)eid, dimx, dimy, dimz, mips, faces, yuv);
    }

    return (jlong)(uintptr_t)rsTypeCreate((RsContext)con, (RsElement)eid, dimx, dimy, dimz, mips,
                                          faces, yuv);
}

static void
nTypeGetNativeData(JNIEnv *_env, jobject _this, jlong con, jlong id, jlongArray _typeData)
{
    // We are packing 6 items: mDimX; mDimY; mDimZ;
    // mDimLOD; mDimFaces; mElement; into typeData
    int elementCount = _env->GetArrayLength(_typeData);

    assert(elementCount == 6);
    if (kLogApi) {
        ALOGD("nTypeGetNativeData, con(%p)", (RsContext)con);
    }

    uintptr_t typeData[6];
    rsaTypeGetNativeData((RsContext)con, (RsType)id, typeData, 6);

    for(jint i = 0; i < elementCount; i ++) {
        const jlong data = (jlong)(uintptr_t)typeData[i];
        _env->SetLongArrayRegion(_typeData, i, 1, &data);
    }
}

// -----------------------------------

static jlong
nAllocationCreateTyped(JNIEnv *_env, jobject _this, jlong con, jlong type, jint mips, jint usage,
                       jlong pointer)
{
    if (kLogApi) {
        ALOGD("nAllocationCreateTyped, con(%p), type(%p), mip(%i), usage(%i), ptr(%p)",
              (RsContext)con, (RsElement)type, mips, usage, (void *)pointer);
    }
    return (jlong)(uintptr_t) rsAllocationCreateTyped((RsContext)con, (RsType)type,
                                                      (RsAllocationMipmapControl)mips,
                                                      (uint32_t)usage, (uintptr_t)pointer);
}

static void
nAllocationSyncAll(JNIEnv *_env, jobject _this, jlong con, jlong a, jint bits)
{
    if (kLogApi) {
        ALOGD("nAllocationSyncAll, con(%p), a(%p), bits(0x%08x)", (RsContext)con, (RsAllocation)a,
              bits);
    }
    rsAllocationSyncAll((RsContext)con, (RsAllocation)a, (RsAllocationUsageType)bits);
}

static void
nAllocationSetupBufferQueue(JNIEnv *_env, jobject _this, jlong con, jlong alloc, jint numAlloc)
{
    if (kLogApi) {
        ALOGD("nAllocationSetupBufferQueue, con(%p), alloc(%p), numAlloc(%d)", (RsContext)con,
              (RsAllocation)alloc, numAlloc);
    }
    rsAllocationSetupBufferQueue((RsContext)con, (RsAllocation)alloc, (uint32_t)numAlloc);
}

static void
nAllocationShareBufferQueue(JNIEnv *_env, jobject _this, jlong con, jlong alloc1, jlong alloc2)
{
    if (kLogApi) {
        ALOGD("nAllocationShareBufferQueue, con(%p), alloc1(%p), alloc2(%p)", (RsContext)con,
              (RsAllocation)alloc1, (RsAllocation)alloc2);
    }

    rsAllocationShareBufferQueue((RsContext)con, (RsAllocation)alloc1, (RsAllocation)alloc2);
}

static jobject
nAllocationGetSurface(JNIEnv *_env, jobject _this, jlong con, jlong a)
{
    if (kLogApi) {
        ALOGD("nAllocationGetSurface, con(%p), a(%p)", (RsContext)con, (RsAllocation)a);
    }

    IGraphicBufferProducer *v = (IGraphicBufferProducer *)rsAllocationGetSurface((RsContext)con,
                                                                                 (RsAllocation)a);
    sp<IGraphicBufferProducer> bp = v;
    v->decStrong(nullptr);

    jobject o = android_view_Surface_createFromIGraphicBufferProducer(_env, bp);
    return o;
}

static void
nAllocationSetSurface(JNIEnv *_env, jobject _this, jlong con, jlong alloc, jobject sur)
{
    if (kLogApi) {
        ALOGD("nAllocationSetSurface, con(%p), alloc(%p), surface(%p)", (RsContext)con,
              (RsAllocation)alloc, (Surface *)sur);
    }

    sp<Surface> s;
    if (sur != 0) {
        s = android_view_Surface_getSurface(_env, sur);
    }

    rsAllocationSetSurface((RsContext)con, (RsAllocation)alloc,
                           static_cast<ANativeWindow *>(s.get()));
}

static void
nAllocationIoSend(JNIEnv *_env, jobject _this, jlong con, jlong alloc)
{
    if (kLogApi) {
        ALOGD("nAllocationIoSend, con(%p), alloc(%p)", (RsContext)con, (RsAllocation)alloc);
    }
    rsAllocationIoSend((RsContext)con, (RsAllocation)alloc);
}

static jlong
nAllocationIoReceive(JNIEnv *_env, jobject _this, jlong con, jlong alloc)
{
    if (kLogApi) {
        ALOGD("nAllocationIoReceive, con(%p), alloc(%p)", (RsContext)con, (RsAllocation)alloc);
    }
    return (jlong) rsAllocationIoReceive((RsContext)con, (RsAllocation)alloc);
}

static void
nAllocationGenerateMipmaps(JNIEnv *_env, jobject _this, jlong con, jlong alloc)
{
    if (kLogApi) {
        ALOGD("nAllocationGenerateMipmaps, con(%p), a(%p)", (RsContext)con, (RsAllocation)alloc);
    }
    rsAllocationGenerateMipmaps((RsContext)con, (RsAllocation)alloc);
}

static jlong
nAllocationCreateFromBitmap(JNIEnv *_env, jobject _this, jlong con, jlong type, jint mip,
                            jobject jbitmap, jint usage)
{
    SkBitmap bitmap;
    GraphicsJNI::getSkBitmap(_env, jbitmap, &bitmap);

    bitmap.lockPixels();
    const void* ptr = bitmap.getPixels();
    jlong id = (jlong)(uintptr_t)rsAllocationCreateFromBitmap((RsContext)con,
                                                  (RsType)type, (RsAllocationMipmapControl)mip,
                                                  ptr, bitmap.getSize(), usage);
    bitmap.unlockPixels();
    return id;
}

static jlong
nAllocationCreateBitmapBackedAllocation(JNIEnv *_env, jobject _this, jlong con, jlong type,
                                        jint mip, jobject jbitmap, jint usage)
{
    SkBitmap bitmap;
    GraphicsJNI::getSkBitmap(_env, jbitmap, &bitmap);

    bitmap.lockPixels();
    const void* ptr = bitmap.getPixels();
    jlong id = (jlong)(uintptr_t)rsAllocationCreateTyped((RsContext)con,
                                            (RsType)type, (RsAllocationMipmapControl)mip,
                                            (uint32_t)usage, (uintptr_t)ptr);
    bitmap.unlockPixels();
    return id;
}

static jlong
nAllocationCubeCreateFromBitmap(JNIEnv *_env, jobject _this, jlong con, jlong type, jint mip,
                                jobject jbitmap, jint usage)
{
    SkBitmap bitmap;
    GraphicsJNI::getSkBitmap(_env, jbitmap, &bitmap);

    bitmap.lockPixels();
    const void* ptr = bitmap.getPixels();
    jlong id = (jlong)(uintptr_t)rsAllocationCubeCreateFromBitmap((RsContext)con,
                                                      (RsType)type, (RsAllocationMipmapControl)mip,
                                                      ptr, bitmap.getSize(), usage);
    bitmap.unlockPixels();
    return id;
}

static void
nAllocationCopyFromBitmap(JNIEnv *_env, jobject _this, jlong con, jlong alloc, jobject jbitmap)
{
    SkBitmap bitmap;
    GraphicsJNI::getSkBitmap(_env, jbitmap, &bitmap);
    int w = bitmap.width();
    int h = bitmap.height();

    bitmap.lockPixels();
    const void* ptr = bitmap.getPixels();
    rsAllocation2DData((RsContext)con, (RsAllocation)alloc, 0, 0,
                       0, RS_ALLOCATION_CUBEMAP_FACE_POSITIVE_X,
                       w, h, ptr, bitmap.getSize(), 0);
    bitmap.unlockPixels();
}

static void
nAllocationCopyToBitmap(JNIEnv *_env, jobject _this, jlong con, jlong alloc, jobject jbitmap)
{
    SkBitmap bitmap;
    GraphicsJNI::getSkBitmap(_env, jbitmap, &bitmap);

    bitmap.lockPixels();
    void* ptr = bitmap.getPixels();
    rsAllocationCopyToBitmap((RsContext)con, (RsAllocation)alloc, ptr, bitmap.getSize());
    bitmap.unlockPixels();
    bitmap.notifyPixelsChanged();
}

// Copies from the Java object data into the Allocation pointed to by _alloc.
static void
nAllocationData1D(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jint offset, jint lod,
                  jint count, jobject data, jint sizeBytes, jint dataType, jint mSize,
                  jboolean usePadding)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    if (kLogApi) {
        ALOGD("nAllocation1DData, con(%p), adapter(%p), offset(%i), count(%i), sizeBytes(%i), "
              "dataType(%i)", (RsContext)con, (RsAllocation)alloc, offset, count, sizeBytes,
              dataType);
    }
    PER_ARRAY_TYPE(nullptr, rsAllocation1DData, true,
                   (RsContext)con, alloc, offset, lod, count, ptr, sizeBytes);
}

static void
nAllocationElementData(JNIEnv *_env, jobject _this, jlong con, jlong alloc,
                       jint xoff, jint yoff, jint zoff,
                       jint lod, jint compIdx, jbyteArray data, jint sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    if (kLogApi) {
        ALOGD("nAllocationElementData, con(%p), alloc(%p), xoff(%i), yoff(%i), zoff(%i), comp(%i), len(%i), "
              "sizeBytes(%i)", (RsContext)con, (RsAllocation)alloc, xoff, yoff, zoff, compIdx, len,
              sizeBytes);
    }
    jbyte *ptr = _env->GetByteArrayElements(data, nullptr);
    if (ptr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return;
    }
    rsAllocationElementData((RsContext)con, (RsAllocation)alloc,
                            xoff, yoff, zoff,
                            lod, ptr, sizeBytes, compIdx);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}


// Copies from the Java object data into the Allocation pointed to by _alloc.
static void
nAllocationData2D(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jint xoff, jint yoff, jint lod, jint _face,
                  jint w, jint h, jobject data, jint sizeBytes, jint dataType, jint mSize,
                  jboolean usePadding)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    RsAllocationCubemapFace face = (RsAllocationCubemapFace)_face;
    if (kLogApi) {
        ALOGD("nAllocation2DData, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i) "
              "type(%i)", (RsContext)con, alloc, xoff, yoff, w, h, sizeBytes, dataType);
    }
    int count = w * h;
    PER_ARRAY_TYPE(nullptr, rsAllocation2DData, true,
                   (RsContext)con, alloc, xoff, yoff, lod, face, w, h, ptr, sizeBytes, 0);
}

// Copies from the Allocation pointed to by srcAlloc into the Allocation
// pointed to by dstAlloc.
static void
nAllocationData2D_alloc(JNIEnv *_env, jobject _this, jlong con,
                        jlong dstAlloc, jint dstXoff, jint dstYoff,
                        jint dstMip, jint dstFace,
                        jint width, jint height,
                        jlong srcAlloc, jint srcXoff, jint srcYoff,
                        jint srcMip, jint srcFace)
{
    if (kLogApi) {
        ALOGD("nAllocation2DData_s, con(%p), dstAlloc(%p), dstXoff(%i), dstYoff(%i),"
              " dstMip(%i), dstFace(%i), width(%i), height(%i),"
              " srcAlloc(%p), srcXoff(%i), srcYoff(%i), srcMip(%i), srcFace(%i)",
              (RsContext)con, (RsAllocation)dstAlloc, dstXoff, dstYoff, dstMip, dstFace,
              width, height, (RsAllocation)srcAlloc, srcXoff, srcYoff, srcMip, srcFace);
    }

    rsAllocationCopy2DRange((RsContext)con,
                            (RsAllocation)dstAlloc,
                            dstXoff, dstYoff,
                            dstMip, dstFace,
                            width, height,
                            (RsAllocation)srcAlloc,
                            srcXoff, srcYoff,
                            srcMip, srcFace);
}

// Copies from the Java object data into the Allocation pointed to by _alloc.
static void
nAllocationData3D(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jint xoff, jint yoff, jint zoff, jint lod,
                  jint w, jint h, jint d, jobject data, jint sizeBytes, jint dataType,
                  jint mSize, jboolean usePadding)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    if (kLogApi) {
        ALOGD("nAllocation3DData, con(%p), alloc(%p), xoff(%i), yoff(%i), zoff(%i), lod(%i), w(%i),"
              " h(%i), d(%i), sizeBytes(%i)", (RsContext)con, (RsAllocation)alloc, xoff, yoff, zoff,
              lod, w, h, d, sizeBytes);
    }
    int count = w * h * d;
    PER_ARRAY_TYPE(nullptr, rsAllocation3DData, true,
                   (RsContext)con, alloc, xoff, yoff, zoff, lod, w, h, d, ptr, sizeBytes, 0);
}

// Copies from the Allocation pointed to by srcAlloc into the Allocation
// pointed to by dstAlloc.
static void
nAllocationData3D_alloc(JNIEnv *_env, jobject _this, jlong con,
                        jlong dstAlloc, jint dstXoff, jint dstYoff, jint dstZoff,
                        jint dstMip,
                        jint width, jint height, jint depth,
                        jlong srcAlloc, jint srcXoff, jint srcYoff, jint srcZoff,
                        jint srcMip)
{
    if (kLogApi) {
        ALOGD("nAllocationData3D_alloc, con(%p), dstAlloc(%p), dstXoff(%i), dstYoff(%i),"
              " dstMip(%i), width(%i), height(%i),"
              " srcAlloc(%p), srcXoff(%i), srcYoff(%i), srcMip(%i)",
              (RsContext)con, (RsAllocation)dstAlloc, dstXoff, dstYoff, dstMip,
              width, height, (RsAllocation)srcAlloc, srcXoff, srcYoff, srcMip);
    }

    rsAllocationCopy3DRange((RsContext)con,
                            (RsAllocation)dstAlloc,
                            dstXoff, dstYoff, dstZoff, dstMip,
                            width, height, depth,
                            (RsAllocation)srcAlloc,
                            srcXoff, srcYoff, srcZoff, srcMip);
}


// Copies from the Allocation pointed to by _alloc into the Java object data.
static void
nAllocationRead(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jobject data, jint dataType,
                jint mSize, jboolean usePadding)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    if (kLogApi) {
        ALOGD("nAllocationRead, con(%p), alloc(%p)", (RsContext)con, (RsAllocation)alloc);
    }
    int count = 0;
    PER_ARRAY_TYPE(0, rsAllocationRead, false,
                   (RsContext)con, alloc, ptr, len * typeBytes);
}

// Copies from the Allocation pointed to by _alloc into the Java object data.
static void
nAllocationRead1D(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jint offset, jint lod,
                  jint count, jobject data, jint sizeBytes, jint dataType,
                  jint mSize, jboolean usePadding)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    if (kLogApi) {
        ALOGD("nAllocation1DRead, con(%p), adapter(%p), offset(%i), count(%i), sizeBytes(%i), "
              "dataType(%i)", (RsContext)con, alloc, offset, count, sizeBytes, dataType);
    }
    PER_ARRAY_TYPE(0, rsAllocation1DRead, false,
                   (RsContext)con, alloc, offset, lod, count, ptr, sizeBytes);
}

// Copies from the Element in the Allocation pointed to by _alloc into the Java array data.
static void
nAllocationElementRead(JNIEnv *_env, jobject _this, jlong con, jlong alloc,
                       jint xoff, jint yoff, jint zoff,
                       jint lod, jint compIdx, jbyteArray data, jint sizeBytes)
{
    jint len = _env->GetArrayLength(data);
    if (kLogApi) {
        ALOGD("nAllocationElementRead, con(%p), alloc(%p), xoff(%i), yoff(%i), zoff(%i), comp(%i), len(%i), "
              "sizeBytes(%i)", (RsContext)con, (RsAllocation)alloc, xoff, yoff, zoff, compIdx, len,
              sizeBytes);
    }
    jbyte *ptr = _env->GetByteArrayElements(data, nullptr);
    if (ptr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return;
    }
    rsAllocationElementRead((RsContext)con, (RsAllocation)alloc,
                            xoff, yoff, zoff,
                            lod, ptr, sizeBytes, compIdx);
    _env->ReleaseByteArrayElements(data, ptr, 0);
}

// Copies from the Allocation pointed to by _alloc into the Java object data.
static void
nAllocationRead2D(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jint xoff, jint yoff, jint lod, jint _face,
                  jint w, jint h, jobject data, jint sizeBytes, jint dataType,
                  jint mSize, jboolean usePadding)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    RsAllocationCubemapFace face = (RsAllocationCubemapFace)_face;
    if (kLogApi) {
        ALOGD("nAllocation2DRead, con(%p), adapter(%p), xoff(%i), yoff(%i), w(%i), h(%i), len(%i) "
              "type(%i)", (RsContext)con, alloc, xoff, yoff, w, h, sizeBytes, dataType);
    }
    int count = w * h;
    PER_ARRAY_TYPE(0, rsAllocation2DRead, false,
                   (RsContext)con, alloc, xoff, yoff, lod, face, w, h, ptr, sizeBytes, 0);
}

// Copies from the Allocation pointed to by _alloc into the Java object data.
static void
nAllocationRead3D(JNIEnv *_env, jobject _this, jlong con, jlong _alloc, jint xoff, jint yoff, jint zoff, jint lod,
                  jint w, jint h, jint d, jobject data, int sizeBytes, int dataType,
                  jint mSize, jboolean usePadding)
{
    RsAllocation *alloc = (RsAllocation *)_alloc;
    if (kLogApi) {
        ALOGD("nAllocation3DRead, con(%p), alloc(%p), xoff(%i), yoff(%i), zoff(%i), lod(%i), w(%i),"
              " h(%i), d(%i), sizeBytes(%i)", (RsContext)con, (RsAllocation)alloc, xoff, yoff, zoff,
              lod, w, h, d, sizeBytes);
    }
    int count = w * h * d;
    PER_ARRAY_TYPE(nullptr, rsAllocation3DRead, false,
                   (RsContext)con, alloc, xoff, yoff, zoff, lod, w, h, d, ptr, sizeBytes, 0);
}

static jlong
nAllocationGetType(JNIEnv *_env, jobject _this, jlong con, jlong a)
{
    if (kLogApi) {
        ALOGD("nAllocationGetType, con(%p), a(%p)", (RsContext)con, (RsAllocation)a);
    }
    return (jlong)(uintptr_t) rsaAllocationGetType((RsContext)con, (RsAllocation)a);
}

static void
nAllocationResize1D(JNIEnv *_env, jobject _this, jlong con, jlong alloc, jint dimX)
{
    if (kLogApi) {
        ALOGD("nAllocationResize1D, con(%p), alloc(%p), sizeX(%i)", (RsContext)con,
              (RsAllocation)alloc, dimX);
    }
    rsAllocationResize1D((RsContext)con, (RsAllocation)alloc, dimX);
}


static jlong
nAllocationAdapterCreate(JNIEnv *_env, jobject _this, jlong con, jlong basealloc, jlong type)
{
    if (kLogApi) {
        ALOGD("nAllocationAdapterCreate, con(%p), base(%p), type(%p)",
              (RsContext)con, (RsAllocation)basealloc, (RsElement)type);
    }
    return (jlong)(uintptr_t) rsAllocationAdapterCreate((RsContext)con, (RsType)type,
                                                        (RsAllocation)basealloc);

}

static void
nAllocationAdapterOffset(JNIEnv *_env, jobject _this, jlong con, jlong alloc,
                        jint x, jint y, jint z, jint face, jint lod,
                        jint a1, jint a2, jint a3, jint a4)
{
    uint32_t params[] = {
        (uint32_t)x, (uint32_t)y, (uint32_t)z, (uint32_t)face,
        (uint32_t)lod, (uint32_t)a1, (uint32_t)a2, (uint32_t)a3, (uint32_t)a4
    };
    if (kLogApi) {
        ALOGD("nAllocationAdapterOffset, con(%p), alloc(%p), x(%i), y(%i), z(%i), face(%i), lod(%i), arrays(%i %i %i %i)",
              (RsContext)con, (RsAllocation)alloc, x, y, z, face, lod, a1, a2, a3, a4);
    }
    rsAllocationAdapterOffset((RsContext)con, (RsAllocation)alloc,
                              params, sizeof(params));
}


// -----------------------------------

static jlong
nFileA3DCreateFromAssetStream(JNIEnv *_env, jobject _this, jlong con, jlong native_asset)
{
    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    ALOGV("______nFileA3D %p", asset);

    jlong id = (jlong)(uintptr_t)rsaFileA3DCreateFromMemory((RsContext)con, asset->getBuffer(false), asset->getLength());
    return id;
}

static jlong
nFileA3DCreateFromAsset(JNIEnv *_env, jobject _this, jlong con, jobject _assetMgr, jstring _path)
{
    AssetManager* mgr = assetManagerForJavaObject(_env, _assetMgr);
    if (mgr == nullptr) {
        return 0;
    }

    AutoJavaStringToUTF8 str(_env, _path);
    Asset* asset = mgr->open(str.c_str(), Asset::ACCESS_BUFFER);
    if (asset == nullptr) {
        return 0;
    }

    jlong id = (jlong)(uintptr_t)rsaFileA3DCreateFromAsset((RsContext)con, asset);
    return id;
}

static jlong
nFileA3DCreateFromFile(JNIEnv *_env, jobject _this, jlong con, jstring fileName)
{
    AutoJavaStringToUTF8 fileNameUTF(_env, fileName);
    jlong id = (jlong)(uintptr_t)rsaFileA3DCreateFromFile((RsContext)con, fileNameUTF.c_str());

    return id;
}

static jint
nFileA3DGetNumIndexEntries(JNIEnv *_env, jobject _this, jlong con, jlong fileA3D)
{
    int32_t numEntries = 0;
    rsaFileA3DGetNumIndexEntries((RsContext)con, &numEntries, (RsFile)fileA3D);
    return (jint)numEntries;
}

static void
nFileA3DGetIndexEntries(JNIEnv *_env, jobject _this, jlong con, jlong fileA3D, jint numEntries, jintArray _ids, jobjectArray _entries)
{
    ALOGV("______nFileA3D %p", (RsFile) fileA3D);
    RsFileIndexEntry *fileEntries = (RsFileIndexEntry*)malloc((uint32_t)numEntries * sizeof(RsFileIndexEntry));

    rsaFileA3DGetIndexEntries((RsContext)con, fileEntries, (uint32_t)numEntries, (RsFile)fileA3D);

    for(jint i = 0; i < numEntries; i ++) {
        _env->SetObjectArrayElement(_entries, i, _env->NewStringUTF(fileEntries[i].objectName));
        _env->SetIntArrayRegion(_ids, i, 1, (const jint*)&fileEntries[i].classID);
    }

    free(fileEntries);
}

static jlong
nFileA3DGetEntryByIndex(JNIEnv *_env, jobject _this, jlong con, jlong fileA3D, jint index)
{
    ALOGV("______nFileA3D %p", (RsFile) fileA3D);
    jlong id = (jlong)(uintptr_t)rsaFileA3DGetEntryByIndex((RsContext)con, (uint32_t)index, (RsFile)fileA3D);
    return id;
}

// -----------------------------------

static jlong
nFontCreateFromFile(JNIEnv *_env, jobject _this, jlong con,
                    jstring fileName, jfloat fontSize, jint dpi)
{
    AutoJavaStringToUTF8 fileNameUTF(_env, fileName);
    jlong id = (jlong)(uintptr_t)rsFontCreateFromFile((RsContext)con,
                                         fileNameUTF.c_str(), fileNameUTF.length(),
                                         fontSize, dpi);

    return id;
}

static jlong
nFontCreateFromAssetStream(JNIEnv *_env, jobject _this, jlong con,
                           jstring name, jfloat fontSize, jint dpi, jlong native_asset)
{
    Asset* asset = reinterpret_cast<Asset*>(native_asset);
    AutoJavaStringToUTF8 nameUTF(_env, name);

    jlong id = (jlong)(uintptr_t)rsFontCreateFromMemory((RsContext)con,
                                           nameUTF.c_str(), nameUTF.length(),
                                           fontSize, dpi,
                                           asset->getBuffer(false), asset->getLength());
    return id;
}

static jlong
nFontCreateFromAsset(JNIEnv *_env, jobject _this, jlong con, jobject _assetMgr, jstring _path,
                     jfloat fontSize, jint dpi)
{
    AssetManager* mgr = assetManagerForJavaObject(_env, _assetMgr);
    if (mgr == nullptr) {
        return 0;
    }

    AutoJavaStringToUTF8 str(_env, _path);
    Asset* asset = mgr->open(str.c_str(), Asset::ACCESS_BUFFER);
    if (asset == nullptr) {
        return 0;
    }

    jlong id = (jlong)(uintptr_t)rsFontCreateFromMemory((RsContext)con,
                                           str.c_str(), str.length(),
                                           fontSize, dpi,
                                           asset->getBuffer(false), asset->getLength());
    delete asset;
    return id;
}

// -----------------------------------

static void
nScriptBindAllocation(JNIEnv *_env, jobject _this, jlong con, jlong script, jlong alloc, jint slot)
{
    if (kLogApi) {
        ALOGD("nScriptBindAllocation, con(%p), script(%p), alloc(%p), slot(%i)", (RsContext)con,
              (RsScript)script, (RsAllocation)alloc, slot);
    }
    rsScriptBindAllocation((RsContext)con, (RsScript)script, (RsAllocation)alloc, slot);
}

static void
nScriptSetVarI(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jint val)
{
    if (kLogApi) {
        ALOGD("nScriptSetVarI, con(%p), s(%p), slot(%i), val(%i)", (RsContext)con, (void *)script,
              slot, val);
    }
    rsScriptSetVarI((RsContext)con, (RsScript)script, slot, val);
}

static jint
nScriptGetVarI(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot)
{
    if (kLogApi) {
        ALOGD("nScriptGetVarI, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    }
    int value = 0;
    rsScriptGetVarV((RsContext)con, (RsScript)script, slot, &value, sizeof(value));
    return value;
}

static void
nScriptSetVarObj(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jlong val)
{
    if (kLogApi) {
        ALOGD("nScriptSetVarObj, con(%p), s(%p), slot(%i), val(%" PRId64 ")", (RsContext)con, (void *)script,
              slot, val);
    }
    rsScriptSetVarObj((RsContext)con, (RsScript)script, slot, (RsObjectBase)val);
}

static void
nScriptSetVarJ(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jlong val)
{
    if (kLogApi) {
        ALOGD("nScriptSetVarJ, con(%p), s(%p), slot(%i), val(%" PRId64 ")", (RsContext)con, (void *)script,
              slot, val);
    }
    rsScriptSetVarJ((RsContext)con, (RsScript)script, slot, val);
}

static jlong
nScriptGetVarJ(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot)
{
    if (kLogApi) {
        ALOGD("nScriptGetVarJ, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    }
    jlong value = 0;
    rsScriptGetVarV((RsContext)con, (RsScript)script, slot, &value, sizeof(value));
    return value;
}

static void
nScriptSetVarF(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, float val)
{
    if (kLogApi) {
        ALOGD("nScriptSetVarF, con(%p), s(%p), slot(%i), val(%f)", (RsContext)con, (void *)script,
              slot, val);
    }
    rsScriptSetVarF((RsContext)con, (RsScript)script, slot, val);
}

static jfloat
nScriptGetVarF(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot)
{
    if (kLogApi) {
        ALOGD("nScriptGetVarF, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    }
    jfloat value = 0;
    rsScriptGetVarV((RsContext)con, (RsScript)script, slot, &value, sizeof(value));
    return value;
}

static void
nScriptSetVarD(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, double val)
{
    if (kLogApi) {
        ALOGD("nScriptSetVarD, con(%p), s(%p), slot(%i), val(%lf)", (RsContext)con, (void *)script,
              slot, val);
    }
    rsScriptSetVarD((RsContext)con, (RsScript)script, slot, val);
}

static jdouble
nScriptGetVarD(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot)
{
    if (kLogApi) {
        ALOGD("nScriptGetVarD, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    }
    jdouble value = 0;
    rsScriptGetVarV((RsContext)con, (RsScript)script, slot, &value, sizeof(value));
    return value;
}

static void
nScriptSetVarV(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jbyteArray data)
{
    if (kLogApi) {
        ALOGD("nScriptSetVarV, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    }
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, nullptr);
    if (ptr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return;
    }
    rsScriptSetVarV((RsContext)con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nScriptGetVarV(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jbyteArray data)
{
    if (kLogApi) {
        ALOGD("nScriptSetVarV, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    }
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, nullptr);
    if (ptr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return;
    }
    rsScriptGetVarV((RsContext)con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, 0);
}

static void
nScriptSetVarVE(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jbyteArray data,
                jlong elem, jintArray dims)
{
    if (kLogApi) {
        ALOGD("nScriptSetVarVE, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    }
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, nullptr);
    if (ptr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return;
    }
    jint dimsLen = _env->GetArrayLength(dims) * sizeof(int);
    jint *dimsPtr = _env->GetIntArrayElements(dims, nullptr);
    if (dimsPtr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return;
    }
    rsScriptSetVarVE((RsContext)con, (RsScript)script, slot, ptr, len, (RsElement)elem,
                     (const uint32_t*) dimsPtr, dimsLen);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
    _env->ReleaseIntArrayElements(dims, dimsPtr, JNI_ABORT);
}


static void
nScriptSetTimeZone(JNIEnv *_env, jobject _this, jlong con, jlong script, jbyteArray timeZone)
{
    if (kLogApi) {
        ALOGD("nScriptCSetTimeZone, con(%p), s(%p)", (RsContext)con, (void *)script);
    }

    jint length = _env->GetArrayLength(timeZone);
    jbyte* timeZone_ptr;
    timeZone_ptr = (jbyte *) _env->GetPrimitiveArrayCritical(timeZone, (jboolean *)0);
    if (timeZone_ptr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return;
    }

    rsScriptSetTimeZone((RsContext)con, (RsScript)script, (const char *)timeZone_ptr, length);

    if (timeZone_ptr) {
        _env->ReleasePrimitiveArrayCritical(timeZone, timeZone_ptr, 0);
    }
}

static void
nScriptInvoke(JNIEnv *_env, jobject _this, jlong con, jlong obj, jint slot)
{
    if (kLogApi) {
        ALOGD("nScriptInvoke, con(%p), script(%p)", (RsContext)con, (void *)obj);
    }
    rsScriptInvoke((RsContext)con, (RsScript)obj, slot);
}

static void
nScriptInvokeV(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot, jbyteArray data)
{
    if (kLogApi) {
        ALOGD("nScriptInvokeV, con(%p), s(%p), slot(%i)", (RsContext)con, (void *)script, slot);
    }
    jint len = _env->GetArrayLength(data);
    jbyte *ptr = _env->GetByteArrayElements(data, nullptr);
    if (ptr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return;
    }
    rsScriptInvokeV((RsContext)con, (RsScript)script, slot, ptr, len);
    _env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

static void
nScriptForEach(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot,
               jlongArray ains, jlong aout, jbyteArray params,
               jintArray limits)
{
    if (kLogApi) {
        ALOGD("nScriptForEach, con(%p), s(%p), slot(%i) ains(%p) aout(%" PRId64 ")", (RsContext)con, (void *)script, slot, ains, aout);
    }

    jint   in_len = 0;
    jlong *in_ptr = nullptr;

    RsAllocation *in_allocs = nullptr;

    if (ains != nullptr) {
        in_len = _env->GetArrayLength(ains);
        if (in_len > (jint)RS_KERNEL_MAX_ARGUMENTS) {
            ALOGE("Too many arguments in kernel launch.");
            // TODO (b/20758983): Report back to Java and throw an exception
            return;
        }

        in_ptr = _env->GetLongArrayElements(ains, nullptr);
        if (in_ptr == nullptr) {
            ALOGE("Failed to get Java array elements");
            return;
        }

        if (sizeof(RsAllocation) == sizeof(jlong)) {
            in_allocs = (RsAllocation*)in_ptr;
        } else {
            // Convert from 64-bit jlong types to the native pointer type.

            in_allocs = (RsAllocation*)alloca(in_len * sizeof(RsAllocation));
            if (in_allocs == nullptr) {
                ALOGE("Failed launching kernel for lack of memory.");
                _env->ReleaseLongArrayElements(ains, in_ptr, JNI_ABORT);
                return;
            }

            for (int index = in_len; --index >= 0;) {
                in_allocs[index] = (RsAllocation)in_ptr[index];
            }
        }
    }

    jint   param_len = 0;
    jbyte *param_ptr = nullptr;

    if (params != nullptr) {
        param_len = _env->GetArrayLength(params);
        param_ptr = _env->GetByteArrayElements(params, nullptr);
        if (param_ptr == nullptr) {
            ALOGE("Failed to get Java array elements");
            return;
        }
    }

    RsScriptCall sc, *sca = nullptr;
    uint32_t sc_size = 0;

    jint  limit_len = 0;
    jint *limit_ptr = nullptr;

    if (limits != nullptr) {
        limit_len = _env->GetArrayLength(limits);
        limit_ptr = _env->GetIntArrayElements(limits, nullptr);
        if (limit_ptr == nullptr) {
            ALOGE("Failed to get Java array elements");
            return;
        }

        assert(limit_len == 6);
        UNUSED(limit_len);  // As the assert might not be compiled.

        sc.xStart     = limit_ptr[0];
        sc.xEnd       = limit_ptr[1];
        sc.yStart     = limit_ptr[2];
        sc.yEnd       = limit_ptr[3];
        sc.zStart     = limit_ptr[4];
        sc.zEnd       = limit_ptr[5];
        sc.strategy   = RS_FOR_EACH_STRATEGY_DONT_CARE;
        sc.arrayStart = 0;
        sc.arrayEnd = 0;
        sc.array2Start = 0;
        sc.array2End = 0;
        sc.array3Start = 0;
        sc.array3End = 0;
        sc.array4Start = 0;
        sc.array4End = 0;

        sca = &sc;
        // sc_size is required, but unused, by the runtime and drivers.
        sc_size = sizeof(sc);
    }

    rsScriptForEachMulti((RsContext)con, (RsScript)script, slot,
                         in_allocs, in_len, (RsAllocation)aout,
                         param_ptr, param_len, sca, sc_size);

    if (ains != nullptr) {
        _env->ReleaseLongArrayElements(ains, in_ptr, JNI_ABORT);
    }

    if (params != nullptr) {
        _env->ReleaseByteArrayElements(params, param_ptr, JNI_ABORT);
    }

    if (limits != nullptr) {
        _env->ReleaseIntArrayElements(limits, limit_ptr, JNI_ABORT);
    }
}

static void
nScriptReduce(JNIEnv *_env, jobject _this, jlong con, jlong script, jint slot,
              jlongArray ains, jlong aout, jintArray limits)
{
    if (kLogApi) {
        ALOGD("nScriptReduce, con(%p), s(%p), slot(%i) ains(%p) aout(%" PRId64 ")", (RsContext)con, (void *)script, slot, ains, aout);
    }

    if (ains == nullptr) {
        ALOGE("At least one input required.");
        // TODO (b/20758983): Report back to Java and throw an exception
        return;
    }
    jint in_len = _env->GetArrayLength(ains);
    if (in_len > (jint)RS_KERNEL_MAX_ARGUMENTS) {
        ALOGE("Too many arguments in kernel launch.");
        // TODO (b/20758983): Report back to Java and throw an exception
        return;
    }

    jlong *in_ptr = _env->GetLongArrayElements(ains, nullptr);
    if (in_ptr == nullptr) {
        ALOGE("Failed to get Java array elements");
        // TODO (b/20758983): Report back to Java and throw an exception
        return;
    }

    RsAllocation *in_allocs = nullptr;
    if (sizeof(RsAllocation) == sizeof(jlong)) {
        in_allocs = (RsAllocation*)in_ptr;
    } else {
        // Convert from 64-bit jlong types to the native pointer type.

        in_allocs = (RsAllocation*)alloca(in_len * sizeof(RsAllocation));
        if (in_allocs == nullptr) {
            ALOGE("Failed launching kernel for lack of memory.");
            // TODO (b/20758983): Report back to Java and throw an exception
            _env->ReleaseLongArrayElements(ains, in_ptr, JNI_ABORT);
            return;
        }

        for (int index = in_len; --index >= 0;) {
            in_allocs[index] = (RsAllocation)in_ptr[index];
        }
    }

    RsScriptCall sc, *sca = nullptr;
    uint32_t sc_size = 0;

    jint  limit_len = 0;
    jint *limit_ptr = nullptr;

    if (limits != nullptr) {
        limit_len = _env->GetArrayLength(limits);
        limit_ptr = _env->GetIntArrayElements(limits, nullptr);
        if (limit_ptr == nullptr) {
            ALOGE("Failed to get Java array elements");
            // TODO (b/20758983): Report back to Java and throw an exception
            return;
        }

        assert(limit_len == 6);
        UNUSED(limit_len);  // As the assert might not be compiled.

        sc.xStart     = limit_ptr[0];
        sc.xEnd       = limit_ptr[1];
        sc.yStart     = limit_ptr[2];
        sc.yEnd       = limit_ptr[3];
        sc.zStart     = limit_ptr[4];
        sc.zEnd       = limit_ptr[5];
        sc.strategy   = RS_FOR_EACH_STRATEGY_DONT_CARE;
        sc.arrayStart = 0;
        sc.arrayEnd = 0;
        sc.array2Start = 0;
        sc.array2End = 0;
        sc.array3Start = 0;
        sc.array3End = 0;
        sc.array4Start = 0;
        sc.array4End = 0;

        sca = &sc;
        sc_size = sizeof(sc);
    }

    rsScriptReduce((RsContext)con, (RsScript)script, slot,
                   in_allocs, in_len, (RsAllocation)aout,
                   sca, sc_size);

    _env->ReleaseLongArrayElements(ains, in_ptr, JNI_ABORT);

    if (limits != nullptr) {
        _env->ReleaseIntArrayElements(limits, limit_ptr, JNI_ABORT);
    }
}

// -----------------------------------

static jlong
nScriptCCreate(JNIEnv *_env, jobject _this, jlong con,
               jstring resName, jstring cacheDir,
               jbyteArray scriptRef, jint length)
{
    if (kLogApi) {
        ALOGD("nScriptCCreate, con(%p)", (RsContext)con);
    }

    AutoJavaStringToUTF8 resNameUTF(_env, resName);
    AutoJavaStringToUTF8 cacheDirUTF(_env, cacheDir);
    jlong ret = 0;
    jbyte* script_ptr = nullptr;
    jint _exception = 0;
    jint remaining;
    if (!scriptRef) {
        _exception = 1;
        //jniThrowException(_env, "java/lang/IllegalArgumentException", "script == null");
        goto exit;
    }
    if (length < 0) {
        _exception = 1;
        //jniThrowException(_env, "java/lang/IllegalArgumentException", "length < 0");
        goto exit;
    }
    remaining = _env->GetArrayLength(scriptRef);
    if (remaining < length) {
        _exception = 1;
        //jniThrowException(_env, "java/lang/IllegalArgumentException",
        //        "length > script.length - offset");
        goto exit;
    }
    script_ptr = (jbyte *)
        _env->GetPrimitiveArrayCritical(scriptRef, (jboolean *)0);
    if (script_ptr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return ret;
    }

    //rsScriptCSetText((RsContext)con, (const char *)script_ptr, length);

    ret = (jlong)(uintptr_t)rsScriptCCreate((RsContext)con,
                                resNameUTF.c_str(), resNameUTF.length(),
                                cacheDirUTF.c_str(), cacheDirUTF.length(),
                                (const char *)script_ptr, length);

exit:
    if (script_ptr) {
        _env->ReleasePrimitiveArrayCritical(scriptRef, script_ptr,
                _exception ? JNI_ABORT: 0);
    }

    return (jlong)(uintptr_t)ret;
}

static jlong
nScriptIntrinsicCreate(JNIEnv *_env, jobject _this, jlong con, jint id, jlong eid)
{
    if (kLogApi) {
        ALOGD("nScriptIntrinsicCreate, con(%p) id(%i) element(%p)", (RsContext)con, id,
              (void *)eid);
    }
    return (jlong)(uintptr_t)rsScriptIntrinsicCreate((RsContext)con, id, (RsElement)eid);
}

static jlong
nScriptKernelIDCreate(JNIEnv *_env, jobject _this, jlong con, jlong sid, jint slot, jint sig)
{
    if (kLogApi) {
        ALOGD("nScriptKernelIDCreate, con(%p) script(%p), slot(%i), sig(%i)", (RsContext)con,
              (void *)sid, slot, sig);
    }
    return (jlong)(uintptr_t)rsScriptKernelIDCreate((RsContext)con, (RsScript)sid, slot, sig);
}

static jlong
nScriptInvokeIDCreate(JNIEnv *_env, jobject _this, jlong con, jlong sid, jint slot)
{
    if (kLogApi) {
        ALOGD("nScriptInvokeIDCreate, con(%p) script(%p), slot(%i)", (RsContext)con,
              (void *)sid, slot);
    }
    return (jlong)(uintptr_t)rsScriptInvokeIDCreate((RsContext)con, (RsScript)sid, slot);
}

static jlong
nScriptFieldIDCreate(JNIEnv *_env, jobject _this, jlong con, jlong sid, jint slot)
{
    if (kLogApi) {
        ALOGD("nScriptFieldIDCreate, con(%p) script(%p), slot(%i)", (RsContext)con, (void *)sid,
              slot);
    }
    return (jlong)(uintptr_t)rsScriptFieldIDCreate((RsContext)con, (RsScript)sid, slot);
}

static jlong
nScriptGroupCreate(JNIEnv *_env, jobject _this, jlong con, jlongArray _kernels, jlongArray _src,
    jlongArray _dstk, jlongArray _dstf, jlongArray _types)
{
    if (kLogApi) {
        ALOGD("nScriptGroupCreate, con(%p)", (RsContext)con);
    }

    jlong id = 0;

    RsScriptKernelID* kernelsPtr;
    jint kernelsLen = _env->GetArrayLength(_kernels);
    jlong *jKernelsPtr = _env->GetLongArrayElements(_kernels, nullptr);

    RsScriptKernelID* srcPtr;
    jint srcLen = _env->GetArrayLength(_src);
    jlong *jSrcPtr = _env->GetLongArrayElements(_src, nullptr);

    RsScriptKernelID* dstkPtr;
    jint dstkLen = _env->GetArrayLength(_dstk);
    jlong *jDstkPtr = _env->GetLongArrayElements(_dstk, nullptr);

    RsScriptKernelID* dstfPtr;
    jint dstfLen = _env->GetArrayLength(_dstf);
    jlong *jDstfPtr = _env->GetLongArrayElements(_dstf, nullptr);

    RsType* typesPtr;
    jint typesLen = _env->GetArrayLength(_types);
    jlong *jTypesPtr = _env->GetLongArrayElements(_types, nullptr);

    if (jKernelsPtr == nullptr) {
        ALOGE("Failed to get Java array elements: kernels");
        goto cleanup;
    }
    if (jSrcPtr == nullptr) {
        ALOGE("Failed to get Java array elements: src");
        goto cleanup;
    }
    if (jDstkPtr == nullptr) {
        ALOGE("Failed to get Java array elements: dstk");
        goto cleanup;
    }
    if (jDstfPtr == nullptr) {
        ALOGE("Failed to get Java array elements: dstf");
        goto cleanup;
    }
    if (jTypesPtr == nullptr) {
        ALOGE("Failed to get Java array elements: types");
        goto cleanup;
    }

    kernelsPtr = (RsScriptKernelID*) malloc(sizeof(RsScriptKernelID) * kernelsLen);
    for(int i = 0; i < kernelsLen; ++i) {
        kernelsPtr[i] = (RsScriptKernelID)jKernelsPtr[i];
    }

    srcPtr = (RsScriptKernelID*) malloc(sizeof(RsScriptKernelID) * srcLen);
    for(int i = 0; i < srcLen; ++i) {
        srcPtr[i] = (RsScriptKernelID)jSrcPtr[i];
    }

    dstkPtr = (RsScriptKernelID*) malloc(sizeof(RsScriptKernelID) * dstkLen);
    for(int i = 0; i < dstkLen; ++i) {
        dstkPtr[i] = (RsScriptKernelID)jDstkPtr[i];
    }

    dstfPtr = (RsScriptKernelID*) malloc(sizeof(RsScriptKernelID) * dstfLen);
    for(int i = 0; i < dstfLen; ++i) {
        dstfPtr[i] = (RsScriptKernelID)jDstfPtr[i];
    }

    typesPtr = (RsType*) malloc(sizeof(RsType) * typesLen);
    for(int i = 0; i < typesLen; ++i) {
        typesPtr[i] = (RsType)jTypesPtr[i];
    }

    id = (jlong)(uintptr_t)rsScriptGroupCreate((RsContext)con,
                               (RsScriptKernelID *)kernelsPtr, kernelsLen * sizeof(RsScriptKernelID),
                               (RsScriptKernelID *)srcPtr, srcLen * sizeof(RsScriptKernelID),
                               (RsScriptKernelID *)dstkPtr, dstkLen * sizeof(RsScriptKernelID),
                               (RsScriptFieldID *)dstfPtr, dstfLen * sizeof(RsScriptKernelID),
                               (RsType *)typesPtr, typesLen * sizeof(RsType));

    free(kernelsPtr);
    free(srcPtr);
    free(dstkPtr);
    free(dstfPtr);
    free(typesPtr);

cleanup:
    if (jKernelsPtr != nullptr) {
        _env->ReleaseLongArrayElements(_kernels, jKernelsPtr, 0);
    }
    if (jSrcPtr != nullptr) {
        _env->ReleaseLongArrayElements(_src, jSrcPtr, 0);
    }
    if (jDstkPtr != nullptr) {
        _env->ReleaseLongArrayElements(_dstk, jDstkPtr, 0);
    }
    if (jDstfPtr != nullptr) {
        _env->ReleaseLongArrayElements(_dstf, jDstfPtr, 0);
    }
    if (jTypesPtr != nullptr) {
        _env->ReleaseLongArrayElements(_types, jTypesPtr, 0);
    }

    return id;
}

static void
nScriptGroupSetInput(JNIEnv *_env, jobject _this, jlong con, jlong gid, jlong kid, jlong alloc)
{
    if (kLogApi) {
        ALOGD("nScriptGroupSetInput, con(%p) group(%p), kernelId(%p), alloc(%p)", (RsContext)con,
              (void *)gid, (void *)kid, (void *)alloc);
    }
    rsScriptGroupSetInput((RsContext)con, (RsScriptGroup)gid, (RsScriptKernelID)kid, (RsAllocation)alloc);
}

static void
nScriptGroupSetOutput(JNIEnv *_env, jobject _this, jlong con, jlong gid, jlong kid, jlong alloc)
{
    if (kLogApi) {
        ALOGD("nScriptGroupSetOutput, con(%p) group(%p), kernelId(%p), alloc(%p)", (RsContext)con,
              (void *)gid, (void *)kid, (void *)alloc);
    }
    rsScriptGroupSetOutput((RsContext)con, (RsScriptGroup)gid, (RsScriptKernelID)kid, (RsAllocation)alloc);
}

static void
nScriptGroupExecute(JNIEnv *_env, jobject _this, jlong con, jlong gid)
{
    if (kLogApi) {
        ALOGD("nScriptGroupSetOutput, con(%p) group(%p)", (RsContext)con, (void *)gid);
    }
    rsScriptGroupExecute((RsContext)con, (RsScriptGroup)gid);
}

// ---------------------------------------------------------------------------

static jlong
nProgramStoreCreate(JNIEnv *_env, jobject _this, jlong con,
                    jboolean colorMaskR, jboolean colorMaskG, jboolean colorMaskB, jboolean colorMaskA,
                    jboolean depthMask, jboolean ditherEnable,
                    jint srcFunc, jint destFunc,
                    jint depthFunc)
{
    if (kLogApi) {
        ALOGD("nProgramStoreCreate, con(%p)", (RsContext)con);
    }
    return (jlong)(uintptr_t)rsProgramStoreCreate((RsContext)con, colorMaskR, colorMaskG, colorMaskB, colorMaskA,
                                      depthMask, ditherEnable, (RsBlendSrcFunc)srcFunc,
                                      (RsBlendDstFunc)destFunc, (RsDepthFunc)depthFunc);
}

// ---------------------------------------------------------------------------

static void
nProgramBindConstants(JNIEnv *_env, jobject _this, jlong con, jlong vpv, jint slot, jlong a)
{
    if (kLogApi) {
        ALOGD("nProgramBindConstants, con(%p), vpf(%p), sloat(%i), a(%p)", (RsContext)con,
              (RsProgramVertex)vpv, slot, (RsAllocation)a);
    }
    rsProgramBindConstants((RsContext)con, (RsProgram)vpv, slot, (RsAllocation)a);
}

static void
nProgramBindTexture(JNIEnv *_env, jobject _this, jlong con, jlong vpf, jint slot, jlong a)
{
    if (kLogApi) {
        ALOGD("nProgramBindTexture, con(%p), vpf(%p), slot(%i), a(%p)", (RsContext)con,
              (RsProgramFragment)vpf, slot, (RsAllocation)a);
    }
    rsProgramBindTexture((RsContext)con, (RsProgramFragment)vpf, slot, (RsAllocation)a);
}

static void
nProgramBindSampler(JNIEnv *_env, jobject _this, jlong con, jlong vpf, jint slot, jlong a)
{
    if (kLogApi) {
        ALOGD("nProgramBindSampler, con(%p), vpf(%p), slot(%i), a(%p)", (RsContext)con,
              (RsProgramFragment)vpf, slot, (RsSampler)a);
    }
    rsProgramBindSampler((RsContext)con, (RsProgramFragment)vpf, slot, (RsSampler)a);
}

// ---------------------------------------------------------------------------

static jlong
nProgramFragmentCreate(JNIEnv *_env, jobject _this, jlong con, jstring shader,
                       jobjectArray texNames, jlongArray params)
{
    AutoJavaStringToUTF8 shaderUTF(_env, shader);
    jlong *jParamPtr = _env->GetLongArrayElements(params, nullptr);
    jint paramLen = _env->GetArrayLength(params);
    if (jParamPtr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return 0;
    }

    int texCount = _env->GetArrayLength(texNames);
    AutoJavaStringArrayToUTF8 names(_env, texNames, texCount);
    const char ** nameArray = names.c_str();
    size_t* sizeArray = names.c_str_len();

    if (kLogApi) {
        ALOGD("nProgramFragmentCreate, con(%p), paramLen(%i)", (RsContext)con, paramLen);
    }

    uintptr_t * paramPtr = (uintptr_t*) malloc(sizeof(uintptr_t) * paramLen);
    for(int i = 0; i < paramLen; ++i) {
        paramPtr[i] = (uintptr_t)jParamPtr[i];
    }
    jlong ret = (jlong)(uintptr_t)rsProgramFragmentCreate((RsContext)con, shaderUTF.c_str(), shaderUTF.length(),
                                             nameArray, texCount, sizeArray,
                                             paramPtr, paramLen);

    free(paramPtr);
    _env->ReleaseLongArrayElements(params, jParamPtr, JNI_ABORT);
    return ret;
}


// ---------------------------------------------------------------------------

static jlong
nProgramVertexCreate(JNIEnv *_env, jobject _this, jlong con, jstring shader,
                     jobjectArray texNames, jlongArray params)
{
    AutoJavaStringToUTF8 shaderUTF(_env, shader);
    jlong *jParamPtr = _env->GetLongArrayElements(params, nullptr);
    jint paramLen = _env->GetArrayLength(params);
    if (jParamPtr == nullptr) {
        ALOGE("Failed to get Java array elements");
        return 0;
    }

    if (kLogApi) {
        ALOGD("nProgramVertexCreate, con(%p), paramLen(%i)", (RsContext)con, paramLen);
    }

    int texCount = _env->GetArrayLength(texNames);
    AutoJavaStringArrayToUTF8 names(_env, texNames, texCount);
    const char ** nameArray = names.c_str();
    size_t* sizeArray = names.c_str_len();

    uintptr_t * paramPtr = (uintptr_t*) malloc(sizeof(uintptr_t) * paramLen);
    for(int i = 0; i < paramLen; ++i) {
        paramPtr[i] = (uintptr_t)jParamPtr[i];
    }

    jlong ret = (jlong)(uintptr_t)rsProgramVertexCreate((RsContext)con, shaderUTF.c_str(), shaderUTF.length(),
                                           nameArray, texCount, sizeArray,
                                           paramPtr, paramLen);

    free(paramPtr);
    _env->ReleaseLongArrayElements(params, jParamPtr, JNI_ABORT);
    return ret;
}

// ---------------------------------------------------------------------------

static jlong
nProgramRasterCreate(JNIEnv *_env, jobject _this, jlong con, jboolean pointSprite, jint cull)
{
    if (kLogApi) {
        ALOGD("nProgramRasterCreate, con(%p), pointSprite(%i), cull(%i)", (RsContext)con,
              pointSprite, cull);
    }
    return (jlong)(uintptr_t)rsProgramRasterCreate((RsContext)con, pointSprite, (RsCullMode)cull);
}


// ---------------------------------------------------------------------------

static void
nContextBindRootScript(JNIEnv *_env, jobject _this, jlong con, jlong script)
{
    if (kLogApi) {
        ALOGD("nContextBindRootScript, con(%p), script(%p)", (RsContext)con, (RsScript)script);
    }
    rsContextBindRootScript((RsContext)con, (RsScript)script);
}

static void
nContextBindProgramStore(JNIEnv *_env, jobject _this, jlong con, jlong pfs)
{
    if (kLogApi) {
        ALOGD("nContextBindProgramStore, con(%p), pfs(%p)", (RsContext)con, (RsProgramStore)pfs);
    }
    rsContextBindProgramStore((RsContext)con, (RsProgramStore)pfs);
}

static void
nContextBindProgramFragment(JNIEnv *_env, jobject _this, jlong con, jlong pf)
{
    if (kLogApi) {
        ALOGD("nContextBindProgramFragment, con(%p), pf(%p)", (RsContext)con,
              (RsProgramFragment)pf);
    }
    rsContextBindProgramFragment((RsContext)con, (RsProgramFragment)pf);
}

static void
nContextBindProgramVertex(JNIEnv *_env, jobject _this, jlong con, jlong pf)
{
    if (kLogApi) {
        ALOGD("nContextBindProgramVertex, con(%p), pf(%p)", (RsContext)con, (RsProgramVertex)pf);
    }
    rsContextBindProgramVertex((RsContext)con, (RsProgramVertex)pf);
}

static void
nContextBindProgramRaster(JNIEnv *_env, jobject _this, jlong con, jlong pf)
{
    if (kLogApi) {
        ALOGD("nContextBindProgramRaster, con(%p), pf(%p)", (RsContext)con, (RsProgramRaster)pf);
    }
    rsContextBindProgramRaster((RsContext)con, (RsProgramRaster)pf);
}


// ---------------------------------------------------------------------------

static jlong
nSamplerCreate(JNIEnv *_env, jobject _this, jlong con, jint magFilter, jint minFilter,
               jint wrapS, jint wrapT, jint wrapR, jfloat aniso)
{
    if (kLogApi) {
        ALOGD("nSamplerCreate, con(%p)", (RsContext)con);
    }
    return (jlong)(uintptr_t)rsSamplerCreate((RsContext)con,
                                 (RsSamplerValue)magFilter,
                                 (RsSamplerValue)minFilter,
                                 (RsSamplerValue)wrapS,
                                 (RsSamplerValue)wrapT,
                                 (RsSamplerValue)wrapR,
                                 aniso);
}

// ---------------------------------------------------------------------------

static jlong
nMeshCreate(JNIEnv *_env, jobject _this, jlong con, jlongArray _vtx, jlongArray _idx, jintArray _prim)
{
    if (kLogApi) {
        ALOGD("nMeshCreate, con(%p)", (RsContext)con);
    }

    jlong id = 0;

    RsAllocation* vtxPtr;
    jint vtxLen = _env->GetArrayLength(_vtx);
    jlong *jVtxPtr = _env->GetLongArrayElements(_vtx, nullptr);

    RsAllocation* idxPtr;
    jint idxLen = _env->GetArrayLength(_idx);
    jlong *jIdxPtr = _env->GetLongArrayElements(_idx, nullptr);

    jint primLen = _env->GetArrayLength(_prim);
    jint *primPtr = _env->GetIntArrayElements(_prim, nullptr);

    if (jVtxPtr == nullptr) {
        ALOGE("Failed to get Java array elements: vtx");
        goto cleanupMesh;
    }
    if (jIdxPtr == nullptr) {
        ALOGE("Failed to get Java array elements: idx");
        goto cleanupMesh;
    }
    if (primPtr == nullptr) {
        ALOGE("Failed to get Java array elements: prim");
        goto cleanupMesh;
    }

    vtxPtr = (RsAllocation*) malloc(sizeof(RsAllocation) * vtxLen);
    for(int i = 0; i < vtxLen; ++i) {
        vtxPtr[i] = (RsAllocation)(uintptr_t)jVtxPtr[i];
    }

    idxPtr = (RsAllocation*) malloc(sizeof(RsAllocation) * idxLen);
    for(int i = 0; i < idxLen; ++i) {
        idxPtr[i] = (RsAllocation)(uintptr_t)jIdxPtr[i];
    }

    id = (jlong)(uintptr_t)rsMeshCreate((RsContext)con,
                                        (RsAllocation *)vtxPtr, vtxLen,
                                        (RsAllocation *)idxPtr, idxLen,
                                        (uint32_t *)primPtr, primLen);

    free(vtxPtr);
    free(idxPtr);

cleanupMesh:
    if (jVtxPtr != nullptr) {
        _env->ReleaseLongArrayElements(_vtx, jVtxPtr, 0);
    }
    if (jIdxPtr != nullptr) {
        _env->ReleaseLongArrayElements(_idx, jIdxPtr, 0);
    }
    if (primPtr != nullptr) {
        _env->ReleaseIntArrayElements(_prim, primPtr, 0);
    }

    return id;
}

static jint
nMeshGetVertexBufferCount(JNIEnv *_env, jobject _this, jlong con, jlong mesh)
{
    if (kLogApi) {
        ALOGD("nMeshGetVertexBufferCount, con(%p), Mesh(%p)", (RsContext)con, (RsMesh)mesh);
    }
    jint vtxCount = 0;
    rsaMeshGetVertexBufferCount((RsContext)con, (RsMesh)mesh, &vtxCount);
    return vtxCount;
}

static jint
nMeshGetIndexCount(JNIEnv *_env, jobject _this, jlong con, jlong mesh)
{
    if (kLogApi) {
        ALOGD("nMeshGetIndexCount, con(%p), Mesh(%p)", (RsContext)con, (RsMesh)mesh);
    }
    jint idxCount = 0;
    rsaMeshGetIndexCount((RsContext)con, (RsMesh)mesh, &idxCount);
    return idxCount;
}

static void
nMeshGetVertices(JNIEnv *_env, jobject _this, jlong con, jlong mesh, jlongArray _ids, jint numVtxIDs)
{
    if (kLogApi) {
        ALOGD("nMeshGetVertices, con(%p), Mesh(%p)", (RsContext)con, (RsMesh)mesh);
    }

    RsAllocation *allocs = (RsAllocation*)malloc((uint32_t)numVtxIDs * sizeof(RsAllocation));
    rsaMeshGetVertices((RsContext)con, (RsMesh)mesh, allocs, (uint32_t)numVtxIDs);

    for(jint i = 0; i < numVtxIDs; i ++) {
        const jlong alloc = (jlong)(uintptr_t)allocs[i];
        _env->SetLongArrayRegion(_ids, i, 1, &alloc);
    }

    free(allocs);
}

static void
nMeshGetIndices(JNIEnv *_env, jobject _this, jlong con, jlong mesh, jlongArray _idxIds, jintArray _primitives, jint numIndices)
{
    if (kLogApi) {
        ALOGD("nMeshGetVertices, con(%p), Mesh(%p)", (RsContext)con, (RsMesh)mesh);
    }

    RsAllocation *allocs = (RsAllocation*)malloc((uint32_t)numIndices * sizeof(RsAllocation));
    uint32_t *prims= (uint32_t*)malloc((uint32_t)numIndices * sizeof(uint32_t));

    rsaMeshGetIndices((RsContext)con, (RsMesh)mesh, allocs, prims, (uint32_t)numIndices);

    for(jint i = 0; i < numIndices; i ++) {
        const jlong alloc = (jlong)(uintptr_t)allocs[i];
        const jint prim = (jint)prims[i];
        _env->SetLongArrayRegion(_idxIds, i, 1, &alloc);
        _env->SetIntArrayRegion(_primitives, i, 1, &prim);
    }

    free(allocs);
    free(prims);
}

static jint
nSystemGetPointerSize(JNIEnv *_env, jobject _this) {
    return (jint)sizeof(void*);
}

static jobject
nAllocationGetByteBuffer(JNIEnv *_env, jobject _this, jlong con, jlong alloc,
                        jlongArray strideArr, jint xBytesSize,
                        jint dimY, jint dimZ) {
    if (kLogApi) {
        ALOGD("nAllocationGetByteBuffer, con(%p), alloc(%p)", (RsContext)con, (RsAllocation)alloc);
    }

    jlong *jStridePtr = _env->GetLongArrayElements(strideArr, nullptr);
    if (jStridePtr == nullptr) {
        ALOGE("Failed to get Java array elements: strideArr");
        return 0;
    }

    size_t strideIn = xBytesSize;
    void* ptr = nullptr;
    if (alloc != 0) {
        ptr = rsAllocationGetPointer((RsContext)con, (RsAllocation)alloc, 0,
                                     RS_ALLOCATION_CUBEMAP_FACE_POSITIVE_X, 0, 0,
                                     &strideIn, sizeof(size_t));
    }

    jobject byteBuffer = nullptr;
    if (ptr != nullptr) {
        size_t bufferSize = strideIn;
        jStridePtr[0] = strideIn;
        if (dimY > 0) {
            bufferSize *= dimY;
        }
        if (dimZ > 0) {
            bufferSize *= dimZ;
        }
        byteBuffer = _env->NewDirectByteBuffer(ptr, (jlong) bufferSize);
    }
    _env->ReleaseLongArrayElements(strideArr, jStridePtr, 0);
    return byteBuffer;
}
// ---------------------------------------------------------------------------


static const char *classPathName = "android/renderscript/RenderScript";

static const JNINativeMethod methods[] = {
{"_nInit",                         "()V",                                     (void*)_nInit },

{"nDeviceCreate",                  "()J",                                     (void*)nDeviceCreate },
{"nDeviceDestroy",                 "(J)V",                                    (void*)nDeviceDestroy },
{"nDeviceSetConfig",               "(JII)V",                                  (void*)nDeviceSetConfig },
{"nContextGetUserMessage",         "(J[I)I",                                  (void*)nContextGetUserMessage },
{"nContextGetErrorMessage",        "(J)Ljava/lang/String;",                   (void*)nContextGetErrorMessage },
{"nContextPeekMessage",            "(J[I)I",                                  (void*)nContextPeekMessage },

{"nContextInitToClient",           "(J)V",                                    (void*)nContextInitToClient },
{"nContextDeinitToClient",         "(J)V",                                    (void*)nContextDeinitToClient },


// All methods below are thread protected in java.
{"rsnContextCreate",                 "(JIII)J",                               (void*)nContextCreate },
{"rsnContextCreateGL",               "(JIIIIIIIIIIIIFI)J",                    (void*)nContextCreateGL },
{"rsnContextFinish",                 "(J)V",                                  (void*)nContextFinish },
{"rsnContextSetPriority",            "(JI)V",                                 (void*)nContextSetPriority },
{"rsnContextSetCacheDir",            "(JLjava/lang/String;)V",                (void*)nContextSetCacheDir },
{"rsnContextSetSurface",             "(JIILandroid/view/Surface;)V",          (void*)nContextSetSurface },
{"rsnContextDestroy",                "(J)V",                                  (void*)nContextDestroy },
{"rsnContextDump",                   "(JI)V",                                 (void*)nContextDump },
{"rsnContextPause",                  "(J)V",                                  (void*)nContextPause },
{"rsnContextResume",                 "(J)V",                                  (void*)nContextResume },
{"rsnContextSendMessage",            "(JI[I)V",                               (void*)nContextSendMessage },
{"rsnClosureCreate",                 "(JJJ[J[J[I[J[J)J",                      (void*)nClosureCreate },
{"rsnInvokeClosureCreate",           "(JJ[B[J[J[I)J",                         (void*)nInvokeClosureCreate },
{"rsnClosureSetArg",                 "(JJIJI)V",                              (void*)nClosureSetArg },
{"rsnClosureSetGlobal",              "(JJJJI)V",                              (void*)nClosureSetGlobal },
{"rsnAssignName",                    "(JJ[B)V",                               (void*)nAssignName },
{"rsnGetName",                       "(JJ)Ljava/lang/String;",                (void*)nGetName },
{"rsnObjDestroy",                    "(JJ)V",                                 (void*)nObjDestroy },

{"rsnFileA3DCreateFromFile",         "(JLjava/lang/String;)J",                (void*)nFileA3DCreateFromFile },
{"rsnFileA3DCreateFromAssetStream",  "(JJ)J",                                 (void*)nFileA3DCreateFromAssetStream },
{"rsnFileA3DCreateFromAsset",        "(JLandroid/content/res/AssetManager;Ljava/lang/String;)J",            (void*)nFileA3DCreateFromAsset },
{"rsnFileA3DGetNumIndexEntries",     "(JJ)I",                                 (void*)nFileA3DGetNumIndexEntries },
{"rsnFileA3DGetIndexEntries",        "(JJI[I[Ljava/lang/String;)V",           (void*)nFileA3DGetIndexEntries },
{"rsnFileA3DGetEntryByIndex",        "(JJI)J",                                (void*)nFileA3DGetEntryByIndex },

{"rsnFontCreateFromFile",            "(JLjava/lang/String;FI)J",              (void*)nFontCreateFromFile },
{"rsnFontCreateFromAssetStream",     "(JLjava/lang/String;FIJ)J",             (void*)nFontCreateFromAssetStream },
{"rsnFontCreateFromAsset",        "(JLandroid/content/res/AssetManager;Ljava/lang/String;FI)J",            (void*)nFontCreateFromAsset },

{"rsnElementCreate",                 "(JJIZI)J",                              (void*)nElementCreate },
{"rsnElementCreate2",                "(J[J[Ljava/lang/String;[I)J",           (void*)nElementCreate2 },
{"rsnElementGetNativeData",          "(JJ[I)V",                               (void*)nElementGetNativeData },
{"rsnElementGetSubElements",         "(JJ[J[Ljava/lang/String;[I)V",          (void*)nElementGetSubElements },

{"rsnTypeCreate",                    "(JJIIIZZI)J",                           (void*)nTypeCreate },
{"rsnTypeGetNativeData",             "(JJ[J)V",                               (void*)nTypeGetNativeData },

{"rsnAllocationCreateTyped",         "(JJIIJ)J",                               (void*)nAllocationCreateTyped },
{"rsnAllocationCreateFromBitmap",    "(JJILandroid/graphics/Bitmap;I)J",      (void*)nAllocationCreateFromBitmap },
{"rsnAllocationCreateBitmapBackedAllocation",    "(JJILandroid/graphics/Bitmap;I)J",      (void*)nAllocationCreateBitmapBackedAllocation },
{"rsnAllocationCubeCreateFromBitmap","(JJILandroid/graphics/Bitmap;I)J",      (void*)nAllocationCubeCreateFromBitmap },

{"rsnAllocationCopyFromBitmap",      "(JJLandroid/graphics/Bitmap;)V",        (void*)nAllocationCopyFromBitmap },
{"rsnAllocationCopyToBitmap",        "(JJLandroid/graphics/Bitmap;)V",        (void*)nAllocationCopyToBitmap },

{"rsnAllocationSyncAll",             "(JJI)V",                                (void*)nAllocationSyncAll },
{"rsnAllocationSetupBufferQueue",    "(JJI)V",                                (void*)nAllocationSetupBufferQueue },
{"rsnAllocationShareBufferQueue",    "(JJJ)V",                                (void*)nAllocationShareBufferQueue },
{"rsnAllocationGetSurface",          "(JJ)Landroid/view/Surface;",            (void*)nAllocationGetSurface },
{"rsnAllocationSetSurface",          "(JJLandroid/view/Surface;)V",           (void*)nAllocationSetSurface },
{"rsnAllocationIoSend",              "(JJ)V",                                 (void*)nAllocationIoSend },
{"rsnAllocationIoReceive",           "(JJ)J",                                 (void*)nAllocationIoReceive },
{"rsnAllocationData1D",              "(JJIIILjava/lang/Object;IIIZ)V",        (void*)nAllocationData1D },
{"rsnAllocationElementData",         "(JJIIIII[BI)V",                         (void*)nAllocationElementData },
{"rsnAllocationData2D",              "(JJIIIIIILjava/lang/Object;IIIZ)V",     (void*)nAllocationData2D },
{"rsnAllocationData2D",              "(JJIIIIIIJIIII)V",                      (void*)nAllocationData2D_alloc },
{"rsnAllocationData3D",              "(JJIIIIIIILjava/lang/Object;IIIZ)V",    (void*)nAllocationData3D },
{"rsnAllocationData3D",              "(JJIIIIIIIJIIII)V",                     (void*)nAllocationData3D_alloc },
{"rsnAllocationRead",                "(JJLjava/lang/Object;IIZ)V",            (void*)nAllocationRead },
{"rsnAllocationRead1D",              "(JJIIILjava/lang/Object;IIIZ)V",        (void*)nAllocationRead1D },
{"rsnAllocationElementRead",         "(JJIIIII[BI)V",                         (void*)nAllocationElementRead },
{"rsnAllocationRead2D",              "(JJIIIIIILjava/lang/Object;IIIZ)V",     (void*)nAllocationRead2D },
{"rsnAllocationRead3D",              "(JJIIIIIIILjava/lang/Object;IIIZ)V",    (void*)nAllocationRead3D },
{"rsnAllocationGetType",             "(JJ)J",                                 (void*)nAllocationGetType},
{"rsnAllocationResize1D",            "(JJI)V",                                (void*)nAllocationResize1D },
{"rsnAllocationGenerateMipmaps",     "(JJ)V",                                 (void*)nAllocationGenerateMipmaps },

{"rsnAllocationAdapterCreate",       "(JJJ)J",                                (void*)nAllocationAdapterCreate },
{"rsnAllocationAdapterOffset",       "(JJIIIIIIIII)V",                        (void*)nAllocationAdapterOffset },

{"rsnScriptBindAllocation",          "(JJJI)V",                               (void*)nScriptBindAllocation },
{"rsnScriptSetTimeZone",             "(JJ[B)V",                               (void*)nScriptSetTimeZone },
{"rsnScriptInvoke",                  "(JJI)V",                                (void*)nScriptInvoke },
{"rsnScriptInvokeV",                 "(JJI[B)V",                              (void*)nScriptInvokeV },

{"rsnScriptForEach",                 "(JJI[JJ[B[I)V",                         (void*)nScriptForEach },
{"rsnScriptReduce",                  "(JJI[JJ[I)V",                           (void*)nScriptReduce },

{"rsnScriptSetVarI",                 "(JJII)V",                               (void*)nScriptSetVarI },
{"rsnScriptGetVarI",                 "(JJI)I",                                (void*)nScriptGetVarI },
{"rsnScriptSetVarJ",                 "(JJIJ)V",                               (void*)nScriptSetVarJ },
{"rsnScriptGetVarJ",                 "(JJI)J",                                (void*)nScriptGetVarJ },
{"rsnScriptSetVarF",                 "(JJIF)V",                               (void*)nScriptSetVarF },
{"rsnScriptGetVarF",                 "(JJI)F",                                (void*)nScriptGetVarF },
{"rsnScriptSetVarD",                 "(JJID)V",                               (void*)nScriptSetVarD },
{"rsnScriptGetVarD",                 "(JJI)D",                                (void*)nScriptGetVarD },
{"rsnScriptSetVarV",                 "(JJI[B)V",                              (void*)nScriptSetVarV },
{"rsnScriptGetVarV",                 "(JJI[B)V",                              (void*)nScriptGetVarV },
{"rsnScriptSetVarVE",                "(JJI[BJ[I)V",                           (void*)nScriptSetVarVE },
{"rsnScriptSetVarObj",               "(JJIJ)V",                               (void*)nScriptSetVarObj },

{"rsnScriptCCreate",                 "(JLjava/lang/String;Ljava/lang/String;[BI)J",  (void*)nScriptCCreate },
{"rsnScriptIntrinsicCreate",         "(JIJ)J",                                (void*)nScriptIntrinsicCreate },
{"rsnScriptKernelIDCreate",          "(JJII)J",                               (void*)nScriptKernelIDCreate },
{"rsnScriptInvokeIDCreate",          "(JJI)J",                                (void*)nScriptInvokeIDCreate },
{"rsnScriptFieldIDCreate",           "(JJI)J",                                (void*)nScriptFieldIDCreate },
{"rsnScriptGroupCreate",             "(J[J[J[J[J[J)J",                        (void*)nScriptGroupCreate },
{"rsnScriptGroup2Create",            "(JLjava/lang/String;Ljava/lang/String;[J)J", (void*)nScriptGroup2Create },
{"rsnScriptGroupSetInput",           "(JJJJ)V",                               (void*)nScriptGroupSetInput },
{"rsnScriptGroupSetOutput",          "(JJJJ)V",                               (void*)nScriptGroupSetOutput },
{"rsnScriptGroupExecute",            "(JJ)V",                                 (void*)nScriptGroupExecute },
{"rsnScriptGroup2Execute",           "(JJ)V",                                 (void*)nScriptGroup2Execute },

{"rsnScriptIntrinsicBLAS_Single",    "(JJIIIIIIIIIFJJFJIIII)V",               (void*)nScriptIntrinsicBLAS_Single },
{"rsnScriptIntrinsicBLAS_Double",    "(JJIIIIIIIIIDJJDJIIII)V",               (void*)nScriptIntrinsicBLAS_Double },
{"rsnScriptIntrinsicBLAS_Complex",   "(JJIIIIIIIIIFFJJFFJIIII)V",             (void*)nScriptIntrinsicBLAS_Complex },
{"rsnScriptIntrinsicBLAS_Z",         "(JJIIIIIIIIIDDJJDDJIIII)V",             (void*)nScriptIntrinsicBLAS_Z },

{"rsnScriptIntrinsicBLAS_BNNM",      "(JJIIIJIJIJII)V",                       (void*)nScriptIntrinsicBLAS_BNNM },

{"rsnProgramStoreCreate",            "(JZZZZZZIII)J",                         (void*)nProgramStoreCreate },

{"rsnProgramBindConstants",          "(JJIJ)V",                               (void*)nProgramBindConstants },
{"rsnProgramBindTexture",            "(JJIJ)V",                               (void*)nProgramBindTexture },
{"rsnProgramBindSampler",            "(JJIJ)V",                               (void*)nProgramBindSampler },

{"rsnProgramFragmentCreate",         "(JLjava/lang/String;[Ljava/lang/String;[J)J",              (void*)nProgramFragmentCreate },
{"rsnProgramRasterCreate",           "(JZI)J",                                (void*)nProgramRasterCreate },
{"rsnProgramVertexCreate",           "(JLjava/lang/String;[Ljava/lang/String;[J)J",              (void*)nProgramVertexCreate },

{"rsnContextBindRootScript",         "(JJ)V",                                 (void*)nContextBindRootScript },
{"rsnContextBindProgramStore",       "(JJ)V",                                 (void*)nContextBindProgramStore },
{"rsnContextBindProgramFragment",    "(JJ)V",                                 (void*)nContextBindProgramFragment },
{"rsnContextBindProgramVertex",      "(JJ)V",                                 (void*)nContextBindProgramVertex },
{"rsnContextBindProgramRaster",      "(JJ)V",                                 (void*)nContextBindProgramRaster },

{"rsnSamplerCreate",                 "(JIIIIIF)J",                            (void*)nSamplerCreate },

{"rsnMeshCreate",                    "(J[J[J[I)J",                            (void*)nMeshCreate },

{"rsnMeshGetVertexBufferCount",      "(JJ)I",                                 (void*)nMeshGetVertexBufferCount },
{"rsnMeshGetIndexCount",             "(JJ)I",                                 (void*)nMeshGetIndexCount },
{"rsnMeshGetVertices",               "(JJ[JI)V",                              (void*)nMeshGetVertices },
{"rsnMeshGetIndices",                "(JJ[J[II)V",                            (void*)nMeshGetIndices },

{"rsnSystemGetPointerSize",          "()I",                                   (void*)nSystemGetPointerSize },
{"rsnAllocationGetByteBuffer",       "(JJ[JIII)Ljava/nio/ByteBuffer;",        (void*)nAllocationGetByteBuffer },
};

static int registerFuncs(JNIEnv *_env)
{
    return android::AndroidRuntime::registerNativeMethods(
            _env, classPathName, methods, NELEM(methods));
}

// ---------------------------------------------------------------------------

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = nullptr;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed\n");
        goto bail;
    }
    assert(env != nullptr);

    if (registerFuncs(env) < 0) {
        ALOGE("ERROR: Renderscript native registration failed\n");
        goto bail;
    }

    /* success -- return valid version number */
    result = JNI_VERSION_1_4;

bail:
    return result;
}

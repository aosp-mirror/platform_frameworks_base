/*
**
** Copyright 2009, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

// This source file is automatically generated

#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>

#include <assert.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

static int initialized = 0;

static jclass nioAccessClass;
static jclass bufferClass;
static jclass OOMEClass;
static jclass UOEClass;
static jclass IAEClass;
static jclass AIOOBEClass;
static jmethodID getBasePointerID;
static jmethodID getBaseArrayID;
static jmethodID getBaseArrayOffsetID;
static jfieldID positionID;
static jfieldID limitID;
static jfieldID elementSizeShiftID;

/* Cache method IDs each time the class is loaded. */

static void
nativeClassInitBuffer(JNIEnv *_env)
{
    jclass nioAccessClassLocal = _env->FindClass("java/nio/NIOAccess");
    nioAccessClass = (jclass) _env->NewGlobalRef(nioAccessClassLocal);

    jclass bufferClassLocal = _env->FindClass("java/nio/Buffer");
    bufferClass = (jclass) _env->NewGlobalRef(bufferClassLocal);

    getBasePointerID = _env->GetStaticMethodID(nioAccessClass,
            "getBasePointer", "(Ljava/nio/Buffer;)J");
    getBaseArrayID = _env->GetStaticMethodID(nioAccessClass,
            "getBaseArray", "(Ljava/nio/Buffer;)Ljava/lang/Object;");
    getBaseArrayOffsetID = _env->GetStaticMethodID(nioAccessClass,
            "getBaseArrayOffset", "(Ljava/nio/Buffer;)I");

    positionID = _env->GetFieldID(bufferClass, "position", "I");
    limitID = _env->GetFieldID(bufferClass, "limit", "I");
    elementSizeShiftID =
        _env->GetFieldID(bufferClass, "_elementSizeShift", "I");
}


static void
nativeClassInit(JNIEnv *_env, jclass glImplClass)
{
    nativeClassInitBuffer(_env);

    jclass IAEClassLocal =
        _env->FindClass("java/lang/IllegalArgumentException");
    jclass OOMEClassLocal =
         _env->FindClass("java/lang/OutOfMemoryError");
    jclass UOEClassLocal =
         _env->FindClass("java/lang/UnsupportedOperationException");
    jclass AIOOBEClassLocal =
         _env->FindClass("java/lang/ArrayIndexOutOfBoundsException");

    IAEClass = (jclass) _env->NewGlobalRef(IAEClassLocal);
    OOMEClass = (jclass) _env->NewGlobalRef(OOMEClassLocal);
    UOEClass = (jclass) _env->NewGlobalRef(UOEClassLocal);
    AIOOBEClass = (jclass) _env->NewGlobalRef(AIOOBEClassLocal);
}

static void *
getPointer(JNIEnv *_env, jobject buffer, jarray *array, jint *remaining)
{
    jint position;
    jint limit;
    jint elementSizeShift;
    jlong pointer;
    jint offset;
    void *data;

    position = _env->GetIntField(buffer, positionID);
    limit = _env->GetIntField(buffer, limitID);
    elementSizeShift = _env->GetIntField(buffer, elementSizeShiftID);
    *remaining = (limit - position) << elementSizeShift;
    pointer = _env->CallStaticLongMethod(nioAccessClass,
            getBasePointerID, buffer);
    if (pointer != 0L) {
        *array = NULL;
        return (void *) (jint) pointer;
    }
    
    *array = (jarray) _env->CallStaticObjectMethod(nioAccessClass,
            getBaseArrayID, buffer);
    offset = _env->CallStaticIntMethod(nioAccessClass,
            getBaseArrayOffsetID, buffer);
    data = _env->GetPrimitiveArrayCritical(*array, (jboolean *) 0);
    
    return (void *) ((char *) data + offset);
}


static void
releasePointer(JNIEnv *_env, jarray array, void *data, jboolean commit)
{
    _env->ReleasePrimitiveArrayCritical(array, data,
					   commit ? 0 : JNI_ABORT);
}

// --------------------------------------------------------------------------

/* GLbitfield glQueryMatrixxOES ( GLfixed *mantissa, GLint *exponent ) */
static jint
android_glQueryMatrixxOES___3II_3II
  (JNIEnv *_env, jobject _this, jintArray mantissa_ref, jint mantissaOffset, jintArray exponent_ref, jint exponentOffset) {
    jint _exception = 0;
    GLbitfield _returnValue = -1;
    GLfixed *mantissa_base = (GLfixed *) 0;
    jint _mantissaRemaining;
    GLfixed *mantissa = (GLfixed *) 0;
    GLint *exponent_base = (GLint *) 0;
    jint _exponentRemaining;
    GLint *exponent = (GLint *) 0;

    if (!mantissa_ref) {
        _exception = 1;
        _env->ThrowNew(IAEClass, "mantissa == null");
        goto exit;
    }
    if (mantissaOffset < 0) {
        _exception = 1;
        _env->ThrowNew(IAEClass, "mantissaOffset < 0");
        goto exit;
    }
    _mantissaRemaining = _env->GetArrayLength(mantissa_ref) - mantissaOffset;
    if (_mantissaRemaining < 16) {
        _exception = 1;
        _env->ThrowNew(IAEClass, "length - mantissaOffset < 16");
        goto exit;
    }
    mantissa_base = (GLfixed *)
        _env->GetPrimitiveArrayCritical(mantissa_ref, (jboolean *)0);
    mantissa = mantissa_base + mantissaOffset;

    if (!exponent_ref) {
        _exception = 1;
        _env->ThrowNew(IAEClass, "exponent == null");
        goto exit;
    }
    if (exponentOffset < 0) {
        _exception = 1;
        _env->ThrowNew(IAEClass, "exponentOffset < 0");
        goto exit;
    }
    _exponentRemaining = _env->GetArrayLength(exponent_ref) - exponentOffset;
    if (_exponentRemaining < 16) {
        _exception = 1;
        _env->ThrowNew(IAEClass, "length - exponentOffset < 16");
        goto exit;
    }
    exponent_base = (GLint *)
        _env->GetPrimitiveArrayCritical(exponent_ref, (jboolean *)0);
    exponent = exponent_base + exponentOffset;

    _returnValue = glQueryMatrixxOES(
        (GLfixed *)mantissa,
        (GLint *)exponent
    );

exit:
    if (exponent_base) {
        _env->ReleasePrimitiveArrayCritical(exponent_ref, exponent_base,
            _exception ? JNI_ABORT: 0);
    }
    if (mantissa_base) {
        _env->ReleasePrimitiveArrayCritical(mantissa_ref, mantissa_base,
            _exception ? JNI_ABORT: 0);
    }
    return _returnValue;
}

/* GLbitfield glQueryMatrixxOES ( GLfixed *mantissa, GLint *exponent ) */
static jint
android_glQueryMatrixxOES__Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jobject mantissa_buf, jobject exponent_buf) {
    jint _exception = 0;
    jarray _mantissaArray = (jarray) 0;
    jarray _exponentArray = (jarray) 0;
    GLbitfield _returnValue = -1;
    jint _mantissaRemaining;
    GLfixed *mantissa = (GLfixed *) 0;
    jint _exponentRemaining;
    GLint *exponent = (GLint *) 0;

    mantissa = (GLfixed *)getPointer(_env, mantissa_buf, &_mantissaArray, &_mantissaRemaining);
    if (_mantissaRemaining < 16) {
        _exception = 1;
        _env->ThrowNew(IAEClass, "remaining() < 16");
        goto exit;
    }
    exponent = (GLint *)getPointer(_env, exponent_buf, &_exponentArray, &_exponentRemaining);
    if (_exponentRemaining < 16) {
        _exception = 1;
        _env->ThrowNew(IAEClass, "remaining() < 16");
        goto exit;
    }
    _returnValue = glQueryMatrixxOES(
        (GLfixed *)mantissa,
        (GLint *)exponent
    );

exit:
    if (_mantissaArray) {
        releasePointer(_env, _mantissaArray, exponent, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exponentArray) {
        releasePointer(_env, _exponentArray, mantissa, _exception ? JNI_FALSE : JNI_TRUE);
    }
    return _returnValue;
}

static const char *classPathName = "android/opengl/GLES10Ext";

static JNINativeMethod methods[] = {
{"_nativeClassInit", "()V", (void*)nativeClassInit },
{"glQueryMatrixxOES", "([II[II)I", (void *) android_glQueryMatrixxOES___3II_3II },
{"glQueryMatrixxOES", "(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;)I", (void *) android_glQueryMatrixxOES__Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2 },
};

int register_android_opengl_jni_GLES10Ext(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));
    return err;
}

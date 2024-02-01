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

#pragma GCC diagnostic ignored "-Wunused-variable"
#pragma GCC diagnostic ignored "-Wunused-but-set-variable"
#pragma GCC diagnostic ignored "-Wunused-function"

#include <GLES/gl.h>
#include <GLES/glext.h>

#include <jni.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>
#include <assert.h>


/* special calls implemented in Android's GLES wrapper used to more
 * efficiently bound-check passed arrays */
extern "C" {
#ifdef GL_VERSION_ES_CM_1_1
GL_API void GL_APIENTRY glColorPointerBounds(GLint size, GLenum type, GLsizei stride,
        const GLvoid *ptr, GLsizei count);
GL_API void GL_APIENTRY glNormalPointerBounds(GLenum type, GLsizei stride,
        const GLvoid *pointer, GLsizei count);
GL_API void GL_APIENTRY glTexCoordPointerBounds(GLint size, GLenum type,
        GLsizei stride, const GLvoid *pointer, GLsizei count);
GL_API void GL_APIENTRY glVertexPointerBounds(GLint size, GLenum type,
        GLsizei stride, const GLvoid *pointer, GLsizei count);
GL_API void GL_APIENTRY glPointSizePointerOESBounds(GLenum type,
        GLsizei stride, const GLvoid *pointer, GLsizei count);
GL_API void GL_APIENTRY glMatrixIndexPointerOESBounds(GLint size, GLenum type,
        GLsizei stride, const GLvoid *pointer, GLsizei count);
GL_API void GL_APIENTRY glWeightPointerOESBounds(GLint size, GLenum type,
        GLsizei stride, const GLvoid *pointer, GLsizei count);
#endif
#ifdef GL_ES_VERSION_2_0
static void glVertexAttribPointerBounds(GLuint indx, GLint size, GLenum type,
        GLboolean normalized, GLsizei stride, const GLvoid *pointer, GLsizei count) {
    glVertexAttribPointer(indx, size, type, normalized, stride, pointer);
}
#endif
#ifdef GL_ES_VERSION_3_0
static void glVertexAttribIPointerBounds(GLuint indx, GLint size, GLenum type,
        GLsizei stride, const GLvoid *pointer, GLsizei count) {
    glVertexAttribIPointer(indx, size, type, stride, pointer);
}
#endif
}

static void
nativeClassInit(JNIEnv *_env, jclass glImplClass)
{
}

static void *
getPointer(JNIEnv *_env, jobject buffer, jarray *array, jint *remaining, jint *offset)
{
    jint position;
    jint limit;
    jint elementSizeShift;
    jlong pointer;

    pointer = jniGetNioBufferFields(_env, buffer, &position, &limit, &elementSizeShift);
    *remaining = (limit - position) << elementSizeShift;
    if (pointer != 0L) {
        *array = nullptr;
        pointer += position << elementSizeShift;
        return reinterpret_cast<void*>(pointer);
    }

    *array = jniGetNioBufferBaseArray(_env, buffer);
    *offset = jniGetNioBufferBaseArrayOffset(_env, buffer);
    return nullptr;
}

class ByteArrayGetter {
public:
    static void* Get(JNIEnv* _env, jbyteArray array, jboolean* is_copy) {
        return _env->GetByteArrayElements(array, is_copy);
    }
};
class BooleanArrayGetter {
public:
    static void* Get(JNIEnv* _env, jbooleanArray array, jboolean* is_copy) {
        return _env->GetBooleanArrayElements(array, is_copy);
    }
};
class CharArrayGetter {
public:
    static void* Get(JNIEnv* _env, jcharArray array, jboolean* is_copy) {
        return _env->GetCharArrayElements(array, is_copy);
    }
};
class ShortArrayGetter {
public:
    static void* Get(JNIEnv* _env, jshortArray array, jboolean* is_copy) {
        return _env->GetShortArrayElements(array, is_copy);
    }
};
class IntArrayGetter {
public:
    static void* Get(JNIEnv* _env, jintArray array, jboolean* is_copy) {
        return _env->GetIntArrayElements(array, is_copy);
    }
};
class LongArrayGetter {
public:
    static void* Get(JNIEnv* _env, jlongArray array, jboolean* is_copy) {
        return _env->GetLongArrayElements(array, is_copy);
    }
};
class FloatArrayGetter {
public:
    static void* Get(JNIEnv* _env, jfloatArray array, jboolean* is_copy) {
        return _env->GetFloatArrayElements(array, is_copy);
    }
};
class DoubleArrayGetter {
public:
    static void* Get(JNIEnv* _env, jdoubleArray array, jboolean* is_copy) {
        return _env->GetDoubleArrayElements(array, is_copy);
    }
};

template<typename JTYPEARRAY, typename ARRAYGETTER>
static void*
getArrayPointer(JNIEnv *_env, JTYPEARRAY array, jboolean* is_copy) {
    return ARRAYGETTER::Get(_env, array, is_copy);
}

class ByteArrayReleaser {
public:
    static void Release(JNIEnv* _env, jbyteArray array, jbyte* data, jboolean commit) {
        _env->ReleaseByteArrayElements(array, data, commit ? 0 : JNI_ABORT);
    }
};
class BooleanArrayReleaser {
public:
    static void Release(JNIEnv* _env, jbooleanArray array, jboolean* data, jboolean commit) {
        _env->ReleaseBooleanArrayElements(array, data, commit ? 0 : JNI_ABORT);
    }
};
class CharArrayReleaser {
public:
    static void Release(JNIEnv* _env, jcharArray array, jchar* data, jboolean commit) {
        _env->ReleaseCharArrayElements(array, data, commit ? 0 : JNI_ABORT);
    }
};
class ShortArrayReleaser {
public:
    static void Release(JNIEnv* _env, jshortArray array, jshort* data, jboolean commit) {
        _env->ReleaseShortArrayElements(array, data, commit ? 0 : JNI_ABORT);
    }
};
class IntArrayReleaser {
public:
    static void Release(JNIEnv* _env, jintArray array, jint* data, jboolean commit) {
        _env->ReleaseIntArrayElements(array, data, commit ? 0 : JNI_ABORT);
    }
};
class LongArrayReleaser {
public:
    static void Release(JNIEnv* _env, jlongArray array, jlong* data, jboolean commit) {
        _env->ReleaseLongArrayElements(array, data, commit ? 0 : JNI_ABORT);
    }
};
class FloatArrayReleaser {
public:
    static void Release(JNIEnv* _env, jfloatArray array, jfloat* data, jboolean commit) {
        _env->ReleaseFloatArrayElements(array, data, commit ? 0 : JNI_ABORT);
    }
};
class DoubleArrayReleaser {
public:
    static void Release(JNIEnv* _env, jdoubleArray array, jdouble* data, jboolean commit) {
        _env->ReleaseDoubleArrayElements(array, data, commit ? 0 : JNI_ABORT);
    }
};

template<typename JTYPEARRAY, typename NTYPEARRAY, typename ARRAYRELEASER>
static void
releaseArrayPointer(JNIEnv *_env, JTYPEARRAY array, NTYPEARRAY data, jboolean commit) {
    ARRAYRELEASER::Release(_env, array, data, commit);
}

static void
releasePointer(JNIEnv *_env, jarray array, void *data, jboolean commit)
{
    _env->ReleasePrimitiveArrayCritical(array, data,
                       commit ? 0 : JNI_ABORT);
}

static void *
getDirectBufferPointer(JNIEnv *_env, jobject buffer) {
    jint position;
    jint limit;
    jint elementSizeShift;
    jlong pointer;
    pointer = jniGetNioBufferFields(_env, buffer, &position, &limit, &elementSizeShift);
    if (pointer == 0) {
        jniThrowException(_env, "java/lang/IllegalArgumentException",
                          "Must use a native order direct Buffer");
        return nullptr;
    }
    pointer += position << elementSizeShift;
    return reinterpret_cast<void*>(pointer);
}

// --------------------------------------------------------------------------

/*
 * returns the number of values glGet returns for a given pname.
 *
 * The code below is written such that pnames requiring only one values
 * are the default (and are not explicitely tested for). This makes the
 * checking code much shorter/readable/efficient.
 *
 * This means that unknown pnames (e.g.: extensions) will default to 1. If
 * that unknown pname needs more than 1 value, then the validation check
 * is incomplete and the app may crash if it passed the wrong number params.
 */
static int getNeededCount(GLint pname) {
    int needed = 1;
#ifdef GL_ES_VERSION_3_0
    // GLES 3.x pnames
    switch (pname) {
        case GL_MAX_VIEWPORT_DIMS:
            needed = 2;
            break;

        case GL_PROGRAM_BINARY_FORMATS:
            glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, &needed);
            break;
    }
#endif

#ifdef GL_ES_VERSION_2_0
    // GLES 2.x pnames
    switch (pname) {
        case GL_ALIASED_LINE_WIDTH_RANGE:
        case GL_ALIASED_POINT_SIZE_RANGE:
            needed = 2;
            break;

        case GL_BLEND_COLOR:
        case GL_COLOR_CLEAR_VALUE:
        case GL_COLOR_WRITEMASK:
        case GL_SCISSOR_BOX:
        case GL_VIEWPORT:
            needed = 4;
            break;

        case GL_COMPRESSED_TEXTURE_FORMATS:
            glGetIntegerv(GL_NUM_COMPRESSED_TEXTURE_FORMATS, &needed);
            break;

        case GL_SHADER_BINARY_FORMATS:
            glGetIntegerv(GL_NUM_SHADER_BINARY_FORMATS, &needed);
            break;
    }
#endif

#ifdef GL_VERSION_ES_CM_1_1
    // GLES 1.x pnames
    switch (pname) {
        case GL_ALIASED_LINE_WIDTH_RANGE:
        case GL_ALIASED_POINT_SIZE_RANGE:
        case GL_DEPTH_RANGE:
        case GL_SMOOTH_LINE_WIDTH_RANGE:
        case GL_SMOOTH_POINT_SIZE_RANGE:
            needed = 2;
            break;

        case GL_CURRENT_NORMAL:
        case GL_POINT_DISTANCE_ATTENUATION:
            needed = 3;
            break;

        case GL_COLOR_CLEAR_VALUE:
        case GL_COLOR_WRITEMASK:
        case GL_CURRENT_COLOR:
        case GL_CURRENT_TEXTURE_COORDS:
        case GL_FOG_COLOR:
        case GL_LIGHT_MODEL_AMBIENT:
        case GL_SCISSOR_BOX:
        case GL_VIEWPORT:
            needed = 4;
            break;

        case GL_MODELVIEW_MATRIX:
        case GL_PROJECTION_MATRIX:
        case GL_TEXTURE_MATRIX:
            needed = 16;
            break;

        case GL_COMPRESSED_TEXTURE_FORMATS:
            glGetIntegerv(GL_NUM_COMPRESSED_TEXTURE_FORMATS, &needed);
            break;
    }
#endif
    return needed;
}

template <typename JTYPEARRAY, typename ARRAYGETTER, typename NTYPEARRAY,
          typename ARRAYRELEASER, typename CTYPE, void GET(GLenum, CTYPE*)>
static void
get
  (JNIEnv *_env, jobject _this, jint pname, JTYPEARRAY params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType;
    const char * _exceptionMessage;
    CTYPE *params_base = (CTYPE *) 0;
    jint _remaining;
    CTYPE *params = (CTYPE *) 0;
    int _needed = 0;

    if (!params_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    _needed = getNeededCount(pname);
    // if we didn't find this pname, we just assume the user passed
    // an array of the right size -- this might happen with extensions
    // or if we forget an enum here.
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < needed";
        goto exit;
    }
    params_base = (CTYPE *) getArrayPointer<JTYPEARRAY, ARRAYGETTER>(
        _env, params_ref, (jboolean *)0);
    params = params_base + offset;

    GET(
        (GLenum)pname,
        (CTYPE *)params
    );

exit:
    if (params_base) {
        releaseArrayPointer<JTYPEARRAY, NTYPEARRAY, ARRAYRELEASER>(
            _env, params_ref, params_base, !_exception);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}


template <typename CTYPE, typename JTYPEARRAY, typename ARRAYGETTER, typename NTYPEARRAY,
          typename ARRAYRELEASER, void GET(GLenum, CTYPE*)>
static void
getarray
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType;
    const char * _exceptionMessage;
    JTYPEARRAY _array = (JTYPEARRAY) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    CTYPE *params = (CTYPE *) 0;
    int _needed = 0;

    params = (CTYPE *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    _remaining /= sizeof(CTYPE);    // convert from bytes to item count
    _needed = getNeededCount(pname);
    // if we didn't find this pname, we just assume the user passed
    // an array of the right size -- this might happen with extensions
    // or if we forget an enum here.
    if (_needed>0 && _remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *) getArrayPointer<JTYPEARRAY, ARRAYGETTER>(
            _env, _array, (jboolean *) 0);
        params = (CTYPE *) (_paramsBase + _bufferOffset);
    }
    GET(
        (GLenum)pname,
        (CTYPE *)params
    );

exit:
    if (_array) {
        releaseArrayPointer<JTYPEARRAY, NTYPEARRAY, ARRAYRELEASER>(
            _env, _array, (NTYPEARRAY)params, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

// --------------------------------------------------------------------------
/* GLbitfield glQueryMatrixxOES ( GLfixed *mantissa, GLint *exponent ) */
static jint
android_glQueryMatrixxOES___3II_3II
  (JNIEnv *_env, jobject _this, jintArray mantissa_ref, jint mantissaOffset, jintArray exponent_ref, jint exponentOffset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLbitfield _returnValue = -1;
    GLfixed *mantissa_base = (GLfixed *) 0;
    jint _mantissaRemaining;
    GLfixed *mantissa = (GLfixed *) 0;
    GLint *exponent_base = (GLint *) 0;
    jint _exponentRemaining;
    GLint *exponent = (GLint *) 0;

    if (!mantissa_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "mantissa == null";
        goto exit;
    }
    if (mantissaOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "mantissaOffset < 0";
        goto exit;
    }
    _mantissaRemaining = _env->GetArrayLength(mantissa_ref) - mantissaOffset;
    if (_mantissaRemaining < 16) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - mantissaOffset < 16 < needed";
        goto exit;
    }
    mantissa_base = (GLfixed *)
        _env->GetIntArrayElements(mantissa_ref, (jboolean *)0);
    mantissa = mantissa_base + mantissaOffset;

    if (!exponent_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "exponent == null";
        goto exit;
    }
    if (exponentOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "exponentOffset < 0";
        goto exit;
    }
    _exponentRemaining = _env->GetArrayLength(exponent_ref) - exponentOffset;
    if (_exponentRemaining < 16) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - exponentOffset < 16 < needed";
        goto exit;
    }
    exponent_base = (GLint *)
        _env->GetIntArrayElements(exponent_ref, (jboolean *)0);
    exponent = exponent_base + exponentOffset;

    _returnValue = glQueryMatrixxOES(
        (GLfixed *)mantissa,
        (GLint *)exponent
    );

exit:
    if (exponent_base) {
        _env->ReleaseIntArrayElements(exponent_ref, (jint*)exponent_base,
            _exception ? JNI_ABORT: 0);
    }
    if (mantissa_base) {
        _env->ReleaseIntArrayElements(mantissa_ref, (jint*)mantissa_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
        return (jint)0;
    }
    return (jint)_returnValue;
}

/* GLbitfield glQueryMatrixxOES ( GLfixed *mantissa, GLint *exponent ) */
static jint
android_glQueryMatrixxOES__Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jobject mantissa_buf, jobject exponent_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _mantissaArray = (jintArray) 0;
    jint _mantissaBufferOffset = (jint) 0;
    jintArray _exponentArray = (jintArray) 0;
    jint _exponentBufferOffset = (jint) 0;
    GLbitfield _returnValue = -1;
    jint _mantissaRemaining;
    GLfixed *mantissa = (GLfixed *) 0;
    jint _exponentRemaining;
    GLint *exponent = (GLint *) 0;

    if (!mantissa_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "mantissa == null";
        goto exit;
    }
    mantissa = (GLfixed *)getPointer(_env, mantissa_buf, (jarray*)&_mantissaArray, &_mantissaRemaining, &_mantissaBufferOffset);
    if (_mantissaRemaining < 16) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 16 < needed";
        goto exit;
    }
    if (!exponent_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "exponent == null";
        goto exit;
    }
    exponent = (GLint *)getPointer(_env, exponent_buf, (jarray*)&_exponentArray, &_exponentRemaining, &_exponentBufferOffset);
    if (_exponentRemaining < 16) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 16 < needed";
        goto exit;
    }
    if (mantissa == NULL) {
        char * _mantissaBase = (char *)_env->GetIntArrayElements(_mantissaArray, (jboolean *) 0);
        mantissa = (GLfixed *) (_mantissaBase + _mantissaBufferOffset);
    }
    if (exponent == NULL) {
        char * _exponentBase = (char *)_env->GetIntArrayElements(_exponentArray, (jboolean *) 0);
        exponent = (GLint *) (_exponentBase + _exponentBufferOffset);
    }
    _returnValue = glQueryMatrixxOES(
        (GLfixed *)mantissa,
        (GLint *)exponent
    );

exit:
    if (_exponentArray) {
        _env->ReleaseIntArrayElements(_exponentArray, (jint*)exponent, _exception ? JNI_ABORT : 0);
    }
    if (_mantissaArray) {
        _env->ReleaseIntArrayElements(_mantissaArray, (jint*)mantissa, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
        return (jint)0;
    }
    return (jint)_returnValue;
}

static const char *classPathName = "android/opengl/GLES10Ext";

static const JNINativeMethod methods[] = {
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

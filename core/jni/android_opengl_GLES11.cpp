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
/* void glBindBuffer ( GLenum target, GLuint buffer ) */
static void
android_glBindBuffer__II
  (JNIEnv *_env, jobject _this, jint target, jint buffer) {
    glBindBuffer(
        (GLenum)target,
        (GLuint)buffer
    );
}

/* void glBufferData ( GLenum target, GLsizeiptr size, const GLvoid *data, GLenum usage ) */
static void
android_glBufferData__IILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint target, jint size, jobject data_buf, jint usage) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *data = (GLvoid *) 0;

    if (data_buf) {
        data = (GLvoid *)getPointer(_env, data_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
        if (_remaining < size) {
            _exception = 1;
            _exceptionType = "java/lang/IllegalArgumentException";
            _exceptionMessage = "remaining() < size < needed";
            goto exit;
        }
    }
    if (data_buf && data == NULL) {
        char * _dataBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        data = (GLvoid *) (_dataBase + _bufferOffset);
    }
    glBufferData(
        (GLenum)target,
        (GLsizeiptr)size,
        (GLvoid *)data,
        (GLenum)usage
    );

exit:
    if (_array) {
        releasePointer(_env, _array, (void *)((char *)data - _bufferOffset), JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glBufferSubData ( GLenum target, GLintptr offset, GLsizeiptr size, const GLvoid *data ) */
static void
android_glBufferSubData__IIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint offset, jint size, jobject data_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *data = (GLvoid *) 0;

    if (!data_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "data == null";
        goto exit;
    }
    data = (GLvoid *)getPointer(_env, data_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < size) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < size < needed";
        goto exit;
    }
    if (data == NULL) {
        char * _dataBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        data = (GLvoid *) (_dataBase + _bufferOffset);
    }
    glBufferSubData(
        (GLenum)target,
        (GLintptr)offset,
        (GLsizeiptr)size,
        (GLvoid *)data
    );

exit:
    if (_array) {
        releasePointer(_env, _array, (void *)((char *)data - _bufferOffset), JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glClipPlanef ( GLenum plane, const GLfloat *equation ) */
static void
android_glClipPlanef__I_3FI
  (JNIEnv *_env, jobject _this, jint plane, jfloatArray equation_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *equation_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *equation = (GLfloat *) 0;

    if (!equation_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "equation == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(equation_ref) - offset;
    equation_base = (GLfloat *)
        _env->GetFloatArrayElements(equation_ref, (jboolean *)0);
    equation = equation_base + offset;

    glClipPlanef(
        (GLenum)plane,
        (GLfloat *)equation
    );

exit:
    if (equation_base) {
        _env->ReleaseFloatArrayElements(equation_ref, (jfloat*)equation_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glClipPlanef ( GLenum plane, const GLfloat *equation ) */
static void
android_glClipPlanef__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint plane, jobject equation_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *equation = (GLfloat *) 0;

    if (!equation_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "equation == null";
        goto exit;
    }
    equation = (GLfloat *)getPointer(_env, equation_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (equation == NULL) {
        char * _equationBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        equation = (GLfloat *) (_equationBase + _bufferOffset);
    }
    glClipPlanef(
        (GLenum)plane,
        (GLfloat *)equation
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)equation, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glClipPlanex ( GLenum plane, const GLfixed *equation ) */
static void
android_glClipPlanex__I_3II
  (JNIEnv *_env, jobject _this, jint plane, jintArray equation_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfixed *equation_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *equation = (GLfixed *) 0;

    if (!equation_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "equation == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(equation_ref) - offset;
    equation_base = (GLfixed *)
        _env->GetIntArrayElements(equation_ref, (jboolean *)0);
    equation = equation_base + offset;

    glClipPlanex(
        (GLenum)plane,
        (GLfixed *)equation
    );

exit:
    if (equation_base) {
        _env->ReleaseIntArrayElements(equation_ref, (jint*)equation_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glClipPlanex ( GLenum plane, const GLfixed *equation ) */
static void
android_glClipPlanex__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint plane, jobject equation_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfixed *equation = (GLfixed *) 0;

    if (!equation_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "equation == null";
        goto exit;
    }
    equation = (GLfixed *)getPointer(_env, equation_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (equation == NULL) {
        char * _equationBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        equation = (GLfixed *) (_equationBase + _bufferOffset);
    }
    glClipPlanex(
        (GLenum)plane,
        (GLfixed *)equation
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)equation, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glColor4ub ( GLubyte red, GLubyte green, GLubyte blue, GLubyte alpha ) */
static void
android_glColor4ub__BBBB
  (JNIEnv *_env, jobject _this, jbyte red, jbyte green, jbyte blue, jbyte alpha) {
    glColor4ub(
        (GLubyte)red,
        (GLubyte)green,
        (GLubyte)blue,
        (GLubyte)alpha
    );
}

/* void glColorPointer ( GLint size, GLenum type, GLsizei stride, GLint offset ) */
static void
android_glColorPointer__IIII
  (JNIEnv *_env, jobject _this, jint size, jint type, jint stride, jint offset) {
    glColorPointer(
        (GLint)size,
        (GLenum)type,
        (GLsizei)stride,
        reinterpret_cast<GLvoid *>(offset)
    );
}

/* void glDeleteBuffers ( GLsizei n, const GLuint *buffers ) */
static void
android_glDeleteBuffers__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray buffers_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *buffers_base = (GLuint *) 0;
    jint _remaining;
    GLuint *buffers = (GLuint *) 0;

    if (!buffers_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "buffers == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(buffers_ref) - offset;
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < n < needed";
        goto exit;
    }
    buffers_base = (GLuint *)
        _env->GetIntArrayElements(buffers_ref, (jboolean *)0);
    buffers = buffers_base + offset;

    glDeleteBuffers(
        (GLsizei)n,
        (GLuint *)buffers
    );

exit:
    if (buffers_base) {
        _env->ReleaseIntArrayElements(buffers_ref, (jint*)buffers_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteBuffers ( GLsizei n, const GLuint *buffers ) */
static void
android_glDeleteBuffers__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject buffers_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *buffers = (GLuint *) 0;

    if (!buffers_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "buffers == null";
        goto exit;
    }
    buffers = (GLuint *)getPointer(_env, buffers_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (buffers == NULL) {
        char * _buffersBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        buffers = (GLuint *) (_buffersBase + _bufferOffset);
    }
    glDeleteBuffers(
        (GLsizei)n,
        (GLuint *)buffers
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)buffers, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDrawElements ( GLenum mode, GLsizei count, GLenum type, GLint offset ) */
static void
android_glDrawElements__IIII
  (JNIEnv *_env, jobject _this, jint mode, jint count, jint type, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    glDrawElements(
        (GLenum)mode,
        (GLsizei)count,
        (GLenum)type,
        reinterpret_cast<GLvoid *>(offset)
    );
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenBuffers ( GLsizei n, GLuint *buffers ) */
static void
android_glGenBuffers__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray buffers_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *buffers_base = (GLuint *) 0;
    jint _remaining;
    GLuint *buffers = (GLuint *) 0;

    if (!buffers_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "buffers == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(buffers_ref) - offset;
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < n < needed";
        goto exit;
    }
    buffers_base = (GLuint *)
        _env->GetIntArrayElements(buffers_ref, (jboolean *)0);
    buffers = buffers_base + offset;

    glGenBuffers(
        (GLsizei)n,
        (GLuint *)buffers
    );

exit:
    if (buffers_base) {
        _env->ReleaseIntArrayElements(buffers_ref, (jint*)buffers_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenBuffers ( GLsizei n, GLuint *buffers ) */
static void
android_glGenBuffers__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject buffers_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *buffers = (GLuint *) 0;

    if (!buffers_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "buffers == null";
        goto exit;
    }
    buffers = (GLuint *)getPointer(_env, buffers_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (buffers == NULL) {
        char * _buffersBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        buffers = (GLuint *) (_buffersBase + _bufferOffset);
    }
    glGenBuffers(
        (GLsizei)n,
        (GLuint *)buffers
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)buffers, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetBooleanv ( GLenum pname, GLboolean *params ) */
static void
android_glGetBooleanv__I_3ZI
  (JNIEnv *_env, jobject _this, jint pname, jbooleanArray params_ref, jint offset) {
    get<jbooleanArray, BooleanArrayGetter, jboolean*, BooleanArrayReleaser, GLboolean, glGetBooleanv>(
        _env, _this, pname, params_ref, offset);
}

/* void glGetBooleanv ( GLenum pname, GLboolean *params ) */
static void
android_glGetBooleanv__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    getarray<GLboolean, jintArray, IntArrayGetter, jint*, IntArrayReleaser, glGetBooleanv>(
        _env, _this, pname, params_buf);
}
/* void glGetBufferParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetBufferParameteriv__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint *params_base = (GLint *) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

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
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    params_base = (GLint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetBufferParameteriv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetBufferParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetBufferParameteriv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetBufferParameteriv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetClipPlanef ( GLenum pname, GLfloat *eqn ) */
static void
android_glGetClipPlanef__I_3FI
  (JNIEnv *_env, jobject _this, jint pname, jfloatArray eqn_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *eqn_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *eqn = (GLfloat *) 0;

    if (!eqn_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "eqn == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(eqn_ref) - offset;
    if (_remaining < 4) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 4 < needed";
        goto exit;
    }
    eqn_base = (GLfloat *)
        _env->GetFloatArrayElements(eqn_ref, (jboolean *)0);
    eqn = eqn_base + offset;

    glGetClipPlanef(
        (GLenum)pname,
        (GLfloat *)eqn
    );

exit:
    if (eqn_base) {
        _env->ReleaseFloatArrayElements(eqn_ref, (jfloat*)eqn_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetClipPlanef ( GLenum pname, GLfloat *eqn ) */
static void
android_glGetClipPlanef__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject eqn_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *eqn = (GLfloat *) 0;

    if (!eqn_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "eqn == null";
        goto exit;
    }
    eqn = (GLfloat *)getPointer(_env, eqn_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 4) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 4 < needed";
        goto exit;
    }
    if (eqn == NULL) {
        char * _eqnBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        eqn = (GLfloat *) (_eqnBase + _bufferOffset);
    }
    glGetClipPlanef(
        (GLenum)pname,
        (GLfloat *)eqn
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)eqn, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetClipPlanex ( GLenum pname, GLfixed *eqn ) */
static void
android_glGetClipPlanex__I_3II
  (JNIEnv *_env, jobject _this, jint pname, jintArray eqn_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfixed *eqn_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *eqn = (GLfixed *) 0;

    if (!eqn_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "eqn == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(eqn_ref) - offset;
    if (_remaining < 4) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 4 < needed";
        goto exit;
    }
    eqn_base = (GLfixed *)
        _env->GetIntArrayElements(eqn_ref, (jboolean *)0);
    eqn = eqn_base + offset;

    glGetClipPlanex(
        (GLenum)pname,
        (GLfixed *)eqn
    );

exit:
    if (eqn_base) {
        _env->ReleaseIntArrayElements(eqn_ref, (jint*)eqn_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetClipPlanex ( GLenum pname, GLfixed *eqn ) */
static void
android_glGetClipPlanex__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject eqn_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfixed *eqn = (GLfixed *) 0;

    if (!eqn_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "eqn == null";
        goto exit;
    }
    eqn = (GLfixed *)getPointer(_env, eqn_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 4) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 4 < needed";
        goto exit;
    }
    if (eqn == NULL) {
        char * _eqnBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        eqn = (GLfixed *) (_eqnBase + _bufferOffset);
    }
    glGetClipPlanex(
        (GLenum)pname,
        (GLfixed *)eqn
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)eqn, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetFixedv ( GLenum pname, GLfixed *params ) */
static void
android_glGetFixedv__I_3II
  (JNIEnv *_env, jobject _this, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

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
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetFixedv(
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetFixedv ( GLenum pname, GLfixed *params ) */
static void
android_glGetFixedv__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfixed *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glGetFixedv(
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetFloatv ( GLenum pname, GLfloat *params ) */
static void
android_glGetFloatv__I_3FI
  (JNIEnv *_env, jobject _this, jint pname, jfloatArray params_ref, jint offset) {
    get<jfloatArray, FloatArrayGetter, jfloat*, FloatArrayReleaser, GLfloat, glGetFloatv>(
        _env, _this, pname, params_ref, offset);
}

/* void glGetFloatv ( GLenum pname, GLfloat *params ) */
static void
android_glGetFloatv__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    getarray<GLfloat, jfloatArray, FloatArrayGetter, jfloat*, FloatArrayReleaser, glGetFloatv>(
        _env, _this, pname, params_buf);
}
/* void glGetLightfv ( GLenum light, GLenum pname, GLfloat *params ) */
static void
android_glGetLightfv__II_3FI
  (JNIEnv *_env, jobject _this, jint light, jint pname, jfloatArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

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
    int _needed;
    switch (pname) {
#if defined(GL_SPOT_DIRECTION)
        case GL_SPOT_DIRECTION:
#endif // defined(GL_SPOT_DIRECTION)
            _needed = 3;
            break;
#if defined(GL_AMBIENT)
        case GL_AMBIENT:
#endif // defined(GL_AMBIENT)
#if defined(GL_DIFFUSE)
        case GL_DIFFUSE:
#endif // defined(GL_DIFFUSE)
#if defined(GL_SPECULAR)
        case GL_SPECULAR:
#endif // defined(GL_SPECULAR)
#if defined(GL_EMISSION)
        case GL_EMISSION:
#endif // defined(GL_EMISSION)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < needed";
        goto exit;
    }
    params_base = (GLfloat *)
        _env->GetFloatArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetLightfv(
        (GLenum)light,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (params_base) {
        _env->ReleaseFloatArrayElements(params_ref, (jfloat*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetLightfv ( GLenum light, GLenum pname, GLfloat *params ) */
static void
android_glGetLightfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint light, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfloat *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    int _needed;
    switch (pname) {
#if defined(GL_SPOT_DIRECTION)
        case GL_SPOT_DIRECTION:
#endif // defined(GL_SPOT_DIRECTION)
            _needed = 3;
            break;
#if defined(GL_AMBIENT)
        case GL_AMBIENT:
#endif // defined(GL_AMBIENT)
#if defined(GL_DIFFUSE)
        case GL_DIFFUSE:
#endif // defined(GL_DIFFUSE)
#if defined(GL_SPECULAR)
        case GL_SPECULAR:
#endif // defined(GL_SPECULAR)
#if defined(GL_EMISSION)
        case GL_EMISSION:
#endif // defined(GL_EMISSION)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        params = (GLfloat *) (_paramsBase + _bufferOffset);
    }
    glGetLightfv(
        (GLenum)light,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetLightxv ( GLenum light, GLenum pname, GLfixed *params ) */
static void
android_glGetLightxv__II_3II
  (JNIEnv *_env, jobject _this, jint light, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

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
    int _needed;
    switch (pname) {
#if defined(GL_SPOT_DIRECTION)
        case GL_SPOT_DIRECTION:
#endif // defined(GL_SPOT_DIRECTION)
            _needed = 3;
            break;
#if defined(GL_AMBIENT)
        case GL_AMBIENT:
#endif // defined(GL_AMBIENT)
#if defined(GL_DIFFUSE)
        case GL_DIFFUSE:
#endif // defined(GL_DIFFUSE)
#if defined(GL_SPECULAR)
        case GL_SPECULAR:
#endif // defined(GL_SPECULAR)
#if defined(GL_EMISSION)
        case GL_EMISSION:
#endif // defined(GL_EMISSION)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < needed";
        goto exit;
    }
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetLightxv(
        (GLenum)light,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetLightxv ( GLenum light, GLenum pname, GLfixed *params ) */
static void
android_glGetLightxv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint light, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfixed *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    int _needed;
    switch (pname) {
#if defined(GL_SPOT_DIRECTION)
        case GL_SPOT_DIRECTION:
#endif // defined(GL_SPOT_DIRECTION)
            _needed = 3;
            break;
#if defined(GL_AMBIENT)
        case GL_AMBIENT:
#endif // defined(GL_AMBIENT)
#if defined(GL_DIFFUSE)
        case GL_DIFFUSE:
#endif // defined(GL_DIFFUSE)
#if defined(GL_SPECULAR)
        case GL_SPECULAR:
#endif // defined(GL_SPECULAR)
#if defined(GL_EMISSION)
        case GL_EMISSION:
#endif // defined(GL_EMISSION)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glGetLightxv(
        (GLenum)light,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetMaterialfv ( GLenum face, GLenum pname, GLfloat *params ) */
static void
android_glGetMaterialfv__II_3FI
  (JNIEnv *_env, jobject _this, jint face, jint pname, jfloatArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

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
    int _needed;
    switch (pname) {
#if defined(GL_AMBIENT)
        case GL_AMBIENT:
#endif // defined(GL_AMBIENT)
#if defined(GL_DIFFUSE)
        case GL_DIFFUSE:
#endif // defined(GL_DIFFUSE)
#if defined(GL_SPECULAR)
        case GL_SPECULAR:
#endif // defined(GL_SPECULAR)
#if defined(GL_EMISSION)
        case GL_EMISSION:
#endif // defined(GL_EMISSION)
#if defined(GL_AMBIENT_AND_DIFFUSE)
        case GL_AMBIENT_AND_DIFFUSE:
#endif // defined(GL_AMBIENT_AND_DIFFUSE)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < needed";
        goto exit;
    }
    params_base = (GLfloat *)
        _env->GetFloatArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetMaterialfv(
        (GLenum)face,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (params_base) {
        _env->ReleaseFloatArrayElements(params_ref, (jfloat*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetMaterialfv ( GLenum face, GLenum pname, GLfloat *params ) */
static void
android_glGetMaterialfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint face, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfloat *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    int _needed;
    switch (pname) {
#if defined(GL_AMBIENT)
        case GL_AMBIENT:
#endif // defined(GL_AMBIENT)
#if defined(GL_DIFFUSE)
        case GL_DIFFUSE:
#endif // defined(GL_DIFFUSE)
#if defined(GL_SPECULAR)
        case GL_SPECULAR:
#endif // defined(GL_SPECULAR)
#if defined(GL_EMISSION)
        case GL_EMISSION:
#endif // defined(GL_EMISSION)
#if defined(GL_AMBIENT_AND_DIFFUSE)
        case GL_AMBIENT_AND_DIFFUSE:
#endif // defined(GL_AMBIENT_AND_DIFFUSE)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        params = (GLfloat *) (_paramsBase + _bufferOffset);
    }
    glGetMaterialfv(
        (GLenum)face,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetMaterialxv ( GLenum face, GLenum pname, GLfixed *params ) */
static void
android_glGetMaterialxv__II_3II
  (JNIEnv *_env, jobject _this, jint face, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

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
    int _needed;
    switch (pname) {
#if defined(GL_AMBIENT)
        case GL_AMBIENT:
#endif // defined(GL_AMBIENT)
#if defined(GL_DIFFUSE)
        case GL_DIFFUSE:
#endif // defined(GL_DIFFUSE)
#if defined(GL_SPECULAR)
        case GL_SPECULAR:
#endif // defined(GL_SPECULAR)
#if defined(GL_EMISSION)
        case GL_EMISSION:
#endif // defined(GL_EMISSION)
#if defined(GL_AMBIENT_AND_DIFFUSE)
        case GL_AMBIENT_AND_DIFFUSE:
#endif // defined(GL_AMBIENT_AND_DIFFUSE)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < needed";
        goto exit;
    }
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetMaterialxv(
        (GLenum)face,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetMaterialxv ( GLenum face, GLenum pname, GLfixed *params ) */
static void
android_glGetMaterialxv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint face, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfixed *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    int _needed;
    switch (pname) {
#if defined(GL_AMBIENT)
        case GL_AMBIENT:
#endif // defined(GL_AMBIENT)
#if defined(GL_DIFFUSE)
        case GL_DIFFUSE:
#endif // defined(GL_DIFFUSE)
#if defined(GL_SPECULAR)
        case GL_SPECULAR:
#endif // defined(GL_SPECULAR)
#if defined(GL_EMISSION)
        case GL_EMISSION:
#endif // defined(GL_EMISSION)
#if defined(GL_AMBIENT_AND_DIFFUSE)
        case GL_AMBIENT_AND_DIFFUSE:
#endif // defined(GL_AMBIENT_AND_DIFFUSE)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glGetMaterialxv(
        (GLenum)face,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexEnvfv ( GLenum env, GLenum pname, GLfloat *params ) */
static void
android_glGetTexEnvfv__II_3FI
  (JNIEnv *_env, jobject _this, jint env, jint pname, jfloatArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

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
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < needed";
        goto exit;
    }
    params_base = (GLfloat *)
        _env->GetFloatArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexEnvfv(
        (GLenum)env,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (params_base) {
        _env->ReleaseFloatArrayElements(params_ref, (jfloat*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexEnvfv ( GLenum env, GLenum pname, GLfloat *params ) */
static void
android_glGetTexEnvfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint env, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfloat *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        params = (GLfloat *) (_paramsBase + _bufferOffset);
    }
    glGetTexEnvfv(
        (GLenum)env,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexEnviv ( GLenum env, GLenum pname, GLint *params ) */
static void
android_glGetTexEnviv__II_3II
  (JNIEnv *_env, jobject _this, jint env, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint *params_base = (GLint *) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

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
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < needed";
        goto exit;
    }
    params_base = (GLint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexEnviv(
        (GLenum)env,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexEnviv ( GLenum env, GLenum pname, GLint *params ) */
static void
android_glGetTexEnviv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint env, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetTexEnviv(
        (GLenum)env,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexEnvxv ( GLenum env, GLenum pname, GLfixed *params ) */
static void
android_glGetTexEnvxv__II_3II
  (JNIEnv *_env, jobject _this, jint env, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

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
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < needed";
        goto exit;
    }
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexEnvxv(
        (GLenum)env,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexEnvxv ( GLenum env, GLenum pname, GLfixed *params ) */
static void
android_glGetTexEnvxv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint env, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfixed *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glGetTexEnvxv(
        (GLenum)env,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexParameterfv ( GLenum target, GLenum pname, GLfloat *params ) */
static void
android_glGetTexParameterfv__II_3FI
  (JNIEnv *_env, jobject _this, jint target, jint pname, jfloatArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

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
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    params_base = (GLfloat *)
        _env->GetFloatArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexParameterfv(
        (GLenum)target,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (params_base) {
        _env->ReleaseFloatArrayElements(params_ref, (jfloat*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexParameterfv ( GLenum target, GLenum pname, GLfloat *params ) */
static void
android_glGetTexParameterfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfloat *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        params = (GLfloat *) (_paramsBase + _bufferOffset);
    }
    glGetTexParameterfv(
        (GLenum)target,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetTexParameteriv__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint *params_base = (GLint *) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

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
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    params_base = (GLint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexParameteriv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetTexParameteriv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetTexParameteriv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexParameterxv ( GLenum target, GLenum pname, GLfixed *params ) */
static void
android_glGetTexParameterxv__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

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
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexParameterxv(
        (GLenum)target,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexParameterxv ( GLenum target, GLenum pname, GLfixed *params ) */
static void
android_glGetTexParameterxv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfixed *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glGetTexParameterxv(
        (GLenum)target,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLboolean glIsBuffer ( GLuint buffer ) */
static jboolean
android_glIsBuffer__I
  (JNIEnv *_env, jobject _this, jint buffer) {
    GLboolean _returnValue;
    _returnValue = glIsBuffer(
        (GLuint)buffer
    );
    return (jboolean)_returnValue;
}

/* GLboolean glIsEnabled ( GLenum cap ) */
static jboolean
android_glIsEnabled__I
  (JNIEnv *_env, jobject _this, jint cap) {
    GLboolean _returnValue;
    _returnValue = glIsEnabled(
        (GLenum)cap
    );
    return (jboolean)_returnValue;
}

/* GLboolean glIsTexture ( GLuint texture ) */
static jboolean
android_glIsTexture__I
  (JNIEnv *_env, jobject _this, jint texture) {
    GLboolean _returnValue;
    _returnValue = glIsTexture(
        (GLuint)texture
    );
    return (jboolean)_returnValue;
}

/* void glNormalPointer ( GLenum type, GLsizei stride, GLint offset ) */
static void
android_glNormalPointer__III
  (JNIEnv *_env, jobject _this, jint type, jint stride, jint offset) {
    glNormalPointer(
        (GLenum)type,
        (GLsizei)stride,
        reinterpret_cast<GLvoid *>(offset)
    );
}

/* void glPointParameterf ( GLenum pname, GLfloat param ) */
static void
android_glPointParameterf__IF
  (JNIEnv *_env, jobject _this, jint pname, jfloat param) {
    glPointParameterf(
        (GLenum)pname,
        (GLfloat)param
    );
}

/* void glPointParameterfv ( GLenum pname, const GLfloat *params ) */
static void
android_glPointParameterfv__I_3FI
  (JNIEnv *_env, jobject _this, jint pname, jfloatArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

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
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    params_base = (GLfloat *)
        _env->GetFloatArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glPointParameterfv(
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (params_base) {
        _env->ReleaseFloatArrayElements(params_ref, (jfloat*)params_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glPointParameterfv ( GLenum pname, const GLfloat *params ) */
static void
android_glPointParameterfv__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfloat *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        params = (GLfloat *) (_paramsBase + _bufferOffset);
    }
    glPointParameterfv(
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)params, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glPointParameterx ( GLenum pname, GLfixed param ) */
static void
android_glPointParameterx__II
  (JNIEnv *_env, jobject _this, jint pname, jint param) {
    glPointParameterx(
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glPointParameterxv ( GLenum pname, const GLfixed *params ) */
static void
android_glPointParameterxv__I_3II
  (JNIEnv *_env, jobject _this, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

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
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glPointParameterxv(
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glPointParameterxv ( GLenum pname, const GLfixed *params ) */
static void
android_glPointParameterxv__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfixed *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glPointParameterxv(
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glPointSizePointerOES ( GLenum type, GLsizei stride, const GLvoid *pointer ) */
static void
android_glPointSizePointerOESBounds__IILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint type, jint stride, jobject pointer_buf, jint remaining) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *pointer = (GLvoid *) 0;

    if (pointer_buf) {
        pointer = (GLvoid *) getDirectBufferPointer(_env, pointer_buf);
        if ( ! pointer ) {
            return;
        }
    }
    glPointSizePointerOESBounds(
        (GLenum)type,
        (GLsizei)stride,
        (GLvoid *)pointer,
        (GLsizei)remaining
    );
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexCoordPointer ( GLint size, GLenum type, GLsizei stride, GLint offset ) */
static void
android_glTexCoordPointer__IIII
  (JNIEnv *_env, jobject _this, jint size, jint type, jint stride, jint offset) {
    glTexCoordPointer(
        (GLint)size,
        (GLenum)type,
        (GLsizei)stride,
        reinterpret_cast<GLvoid *>(offset)
    );
}

/* void glTexEnvi ( GLenum target, GLenum pname, GLint param ) */
static void
android_glTexEnvi__III
  (JNIEnv *_env, jobject _this, jint target, jint pname, jint param) {
    glTexEnvi(
        (GLenum)target,
        (GLenum)pname,
        (GLint)param
    );
}

/* void glTexEnviv ( GLenum target, GLenum pname, const GLint *params ) */
static void
android_glTexEnviv__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint *params_base = (GLint *) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

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
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < needed";
        goto exit;
    }
    params_base = (GLint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glTexEnviv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexEnviv ( GLenum target, GLenum pname, const GLint *params ) */
static void
android_glTexEnviv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 1;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glTexEnviv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexParameterfv ( GLenum target, GLenum pname, const GLfloat *params ) */
static void
android_glTexParameterfv__II_3FI
  (JNIEnv *_env, jobject _this, jint target, jint pname, jfloatArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

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
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    params_base = (GLfloat *)
        _env->GetFloatArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glTexParameterfv(
        (GLenum)target,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (params_base) {
        _env->ReleaseFloatArrayElements(params_ref, (jfloat*)params_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexParameterfv ( GLenum target, GLenum pname, const GLfloat *params ) */
static void
android_glTexParameterfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfloat *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        params = (GLfloat *) (_paramsBase + _bufferOffset);
    }
    glTexParameterfv(
        (GLenum)target,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)params, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexParameteri ( GLenum target, GLenum pname, GLint param ) */
static void
android_glTexParameteri__III
  (JNIEnv *_env, jobject _this, jint target, jint pname, jint param) {
    glTexParameteri(
        (GLenum)target,
        (GLenum)pname,
        (GLint)param
    );
}

/* void glTexParameteriv ( GLenum target, GLenum pname, const GLint *params ) */
static void
android_glTexParameteriv__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint *params_base = (GLint *) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

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
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    params_base = (GLint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glTexParameteriv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexParameteriv ( GLenum target, GLenum pname, const GLint *params ) */
static void
android_glTexParameteriv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glTexParameteriv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexParameterxv ( GLenum target, GLenum pname, const GLfixed *params ) */
static void
android_glTexParameterxv__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

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
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glTexParameterxv(
        (GLenum)target,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexParameterxv ( GLenum target, GLenum pname, const GLfixed *params ) */
static void
android_glTexParameterxv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLfixed *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glTexParameterxv(
        (GLenum)target,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexPointer ( GLint size, GLenum type, GLsizei stride, GLint offset ) */
static void
android_glVertexPointer__IIII
  (JNIEnv *_env, jobject _this, jint size, jint type, jint stride, jint offset) {
    glVertexPointer(
        (GLint)size,
        (GLenum)type,
        (GLsizei)stride,
        reinterpret_cast<GLvoid *>(offset)
    );
}

static const char *classPathName = "android/opengl/GLES11";

static const JNINativeMethod methods[] = {
{"_nativeClassInit", "()V", (void*)nativeClassInit },
{"glBindBuffer", "(II)V", (void *) android_glBindBuffer__II },
{"glBufferData", "(IILjava/nio/Buffer;I)V", (void *) android_glBufferData__IILjava_nio_Buffer_2I },
{"glBufferSubData", "(IIILjava/nio/Buffer;)V", (void *) android_glBufferSubData__IIILjava_nio_Buffer_2 },
{"glClipPlanef", "(I[FI)V", (void *) android_glClipPlanef__I_3FI },
{"glClipPlanef", "(ILjava/nio/FloatBuffer;)V", (void *) android_glClipPlanef__ILjava_nio_FloatBuffer_2 },
{"glClipPlanex", "(I[II)V", (void *) android_glClipPlanex__I_3II },
{"glClipPlanex", "(ILjava/nio/IntBuffer;)V", (void *) android_glClipPlanex__ILjava_nio_IntBuffer_2 },
{"glColor4ub", "(BBBB)V", (void *) android_glColor4ub__BBBB },
{"glColorPointer", "(IIII)V", (void *) android_glColorPointer__IIII },
{"glDeleteBuffers", "(I[II)V", (void *) android_glDeleteBuffers__I_3II },
{"glDeleteBuffers", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteBuffers__ILjava_nio_IntBuffer_2 },
{"glDrawElements", "(IIII)V", (void *) android_glDrawElements__IIII },
{"glGenBuffers", "(I[II)V", (void *) android_glGenBuffers__I_3II },
{"glGenBuffers", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenBuffers__ILjava_nio_IntBuffer_2 },
{"glGetBooleanv", "(I[ZI)V", (void *) android_glGetBooleanv__I_3ZI },
{"glGetBooleanv", "(ILjava/nio/IntBuffer;)V", (void *) android_glGetBooleanv__ILjava_nio_IntBuffer_2 },
{"glGetBufferParameteriv", "(II[II)V", (void *) android_glGetBufferParameteriv__II_3II },
{"glGetBufferParameteriv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetBufferParameteriv__IILjava_nio_IntBuffer_2 },
{"glGetClipPlanef", "(I[FI)V", (void *) android_glGetClipPlanef__I_3FI },
{"glGetClipPlanef", "(ILjava/nio/FloatBuffer;)V", (void *) android_glGetClipPlanef__ILjava_nio_FloatBuffer_2 },
{"glGetClipPlanex", "(I[II)V", (void *) android_glGetClipPlanex__I_3II },
{"glGetClipPlanex", "(ILjava/nio/IntBuffer;)V", (void *) android_glGetClipPlanex__ILjava_nio_IntBuffer_2 },
{"glGetFixedv", "(I[II)V", (void *) android_glGetFixedv__I_3II },
{"glGetFixedv", "(ILjava/nio/IntBuffer;)V", (void *) android_glGetFixedv__ILjava_nio_IntBuffer_2 },
{"glGetFloatv", "(I[FI)V", (void *) android_glGetFloatv__I_3FI },
{"glGetFloatv", "(ILjava/nio/FloatBuffer;)V", (void *) android_glGetFloatv__ILjava_nio_FloatBuffer_2 },
{"glGetLightfv", "(II[FI)V", (void *) android_glGetLightfv__II_3FI },
{"glGetLightfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glGetLightfv__IILjava_nio_FloatBuffer_2 },
{"glGetLightxv", "(II[II)V", (void *) android_glGetLightxv__II_3II },
{"glGetLightxv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetLightxv__IILjava_nio_IntBuffer_2 },
{"glGetMaterialfv", "(II[FI)V", (void *) android_glGetMaterialfv__II_3FI },
{"glGetMaterialfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glGetMaterialfv__IILjava_nio_FloatBuffer_2 },
{"glGetMaterialxv", "(II[II)V", (void *) android_glGetMaterialxv__II_3II },
{"glGetMaterialxv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetMaterialxv__IILjava_nio_IntBuffer_2 },
{"glGetTexEnvfv", "(II[FI)V", (void *) android_glGetTexEnvfv__II_3FI },
{"glGetTexEnvfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glGetTexEnvfv__IILjava_nio_FloatBuffer_2 },
{"glGetTexEnviv", "(II[II)V", (void *) android_glGetTexEnviv__II_3II },
{"glGetTexEnviv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexEnviv__IILjava_nio_IntBuffer_2 },
{"glGetTexEnvxv", "(II[II)V", (void *) android_glGetTexEnvxv__II_3II },
{"glGetTexEnvxv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexEnvxv__IILjava_nio_IntBuffer_2 },
{"glGetTexParameterfv", "(II[FI)V", (void *) android_glGetTexParameterfv__II_3FI },
{"glGetTexParameterfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glGetTexParameterfv__IILjava_nio_FloatBuffer_2 },
{"glGetTexParameteriv", "(II[II)V", (void *) android_glGetTexParameteriv__II_3II },
{"glGetTexParameteriv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexParameteriv__IILjava_nio_IntBuffer_2 },
{"glGetTexParameterxv", "(II[II)V", (void *) android_glGetTexParameterxv__II_3II },
{"glGetTexParameterxv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexParameterxv__IILjava_nio_IntBuffer_2 },
{"glIsBuffer", "(I)Z", (void *) android_glIsBuffer__I },
{"glIsEnabled", "(I)Z", (void *) android_glIsEnabled__I },
{"glIsTexture", "(I)Z", (void *) android_glIsTexture__I },
{"glNormalPointer", "(III)V", (void *) android_glNormalPointer__III },
{"glPointParameterf", "(IF)V", (void *) android_glPointParameterf__IF },
{"glPointParameterfv", "(I[FI)V", (void *) android_glPointParameterfv__I_3FI },
{"glPointParameterfv", "(ILjava/nio/FloatBuffer;)V", (void *) android_glPointParameterfv__ILjava_nio_FloatBuffer_2 },
{"glPointParameterx", "(II)V", (void *) android_glPointParameterx__II },
{"glPointParameterxv", "(I[II)V", (void *) android_glPointParameterxv__I_3II },
{"glPointParameterxv", "(ILjava/nio/IntBuffer;)V", (void *) android_glPointParameterxv__ILjava_nio_IntBuffer_2 },
{"glPointSizePointerOESBounds", "(IILjava/nio/Buffer;I)V", (void *) android_glPointSizePointerOESBounds__IILjava_nio_Buffer_2I },
{"glTexCoordPointer", "(IIII)V", (void *) android_glTexCoordPointer__IIII },
{"glTexEnvi", "(III)V", (void *) android_glTexEnvi__III },
{"glTexEnviv", "(II[II)V", (void *) android_glTexEnviv__II_3II },
{"glTexEnviv", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexEnviv__IILjava_nio_IntBuffer_2 },
{"glTexParameterfv", "(II[FI)V", (void *) android_glTexParameterfv__II_3FI },
{"glTexParameterfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glTexParameterfv__IILjava_nio_FloatBuffer_2 },
{"glTexParameteri", "(III)V", (void *) android_glTexParameteri__III },
{"glTexParameteriv", "(II[II)V", (void *) android_glTexParameteriv__II_3II },
{"glTexParameteriv", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexParameteriv__IILjava_nio_IntBuffer_2 },
{"glTexParameterxv", "(II[II)V", (void *) android_glTexParameterxv__II_3II },
{"glTexParameterxv", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexParameterxv__IILjava_nio_IntBuffer_2 },
{"glVertexPointer", "(IIII)V", (void *) android_glVertexPointer__IIII },
};

int register_android_opengl_jni_GLES11(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));
    return err;
}

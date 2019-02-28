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
#pragma GCC diagnostic ignored "-Wunused-function"

#include <GLES/gl.h>
#include <GLES/glext.h>

#include <jni.h>
#include <nativehelper/JNIHelp.h>
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
/* void glActiveTexture ( GLenum texture ) */
static void
android_glActiveTexture__I
  (JNIEnv *_env, jobject _this, jint texture) {
    glActiveTexture(
        (GLenum)texture
    );
}

/* void glAlphaFunc ( GLenum func, GLclampf ref ) */
static void
android_glAlphaFunc__IF
  (JNIEnv *_env, jobject _this, jint func, jfloat ref) {
    glAlphaFunc(
        (GLenum)func,
        (GLclampf)ref
    );
}

/* void glAlphaFuncx ( GLenum func, GLclampx ref ) */
static void
android_glAlphaFuncx__II
  (JNIEnv *_env, jobject _this, jint func, jint ref) {
    glAlphaFuncx(
        (GLenum)func,
        (GLclampx)ref
    );
}

/* void glBindTexture ( GLenum target, GLuint texture ) */
static void
android_glBindTexture__II
  (JNIEnv *_env, jobject _this, jint target, jint texture) {
    glBindTexture(
        (GLenum)target,
        (GLuint)texture
    );
}

/* void glBlendFunc ( GLenum sfactor, GLenum dfactor ) */
static void
android_glBlendFunc__II
  (JNIEnv *_env, jobject _this, jint sfactor, jint dfactor) {
    glBlendFunc(
        (GLenum)sfactor,
        (GLenum)dfactor
    );
}

/* void glClear ( GLbitfield mask ) */
static void
android_glClear__I
  (JNIEnv *_env, jobject _this, jint mask) {
    glClear(
        (GLbitfield)mask
    );
}

/* void glClearColor ( GLclampf red, GLclampf green, GLclampf blue, GLclampf alpha ) */
static void
android_glClearColor__FFFF
  (JNIEnv *_env, jobject _this, jfloat red, jfloat green, jfloat blue, jfloat alpha) {
    glClearColor(
        (GLclampf)red,
        (GLclampf)green,
        (GLclampf)blue,
        (GLclampf)alpha
    );
}

/* void glClearColorx ( GLclampx red, GLclampx green, GLclampx blue, GLclampx alpha ) */
static void
android_glClearColorx__IIII
  (JNIEnv *_env, jobject _this, jint red, jint green, jint blue, jint alpha) {
    glClearColorx(
        (GLclampx)red,
        (GLclampx)green,
        (GLclampx)blue,
        (GLclampx)alpha
    );
}

/* void glClearDepthf ( GLclampf depth ) */
static void
android_glClearDepthf__F
  (JNIEnv *_env, jobject _this, jfloat depth) {
    glClearDepthf(
        (GLclampf)depth
    );
}

/* void glClearDepthx ( GLclampx depth ) */
static void
android_glClearDepthx__I
  (JNIEnv *_env, jobject _this, jint depth) {
    glClearDepthx(
        (GLclampx)depth
    );
}

/* void glClearStencil ( GLint s ) */
static void
android_glClearStencil__I
  (JNIEnv *_env, jobject _this, jint s) {
    glClearStencil(
        (GLint)s
    );
}

/* void glClientActiveTexture ( GLenum texture ) */
static void
android_glClientActiveTexture__I
  (JNIEnv *_env, jobject _this, jint texture) {
    glClientActiveTexture(
        (GLenum)texture
    );
}

/* void glColor4f ( GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha ) */
static void
android_glColor4f__FFFF
  (JNIEnv *_env, jobject _this, jfloat red, jfloat green, jfloat blue, jfloat alpha) {
    glColor4f(
        (GLfloat)red,
        (GLfloat)green,
        (GLfloat)blue,
        (GLfloat)alpha
    );
}

/* void glColor4x ( GLfixed red, GLfixed green, GLfixed blue, GLfixed alpha ) */
static void
android_glColor4x__IIII
  (JNIEnv *_env, jobject _this, jint red, jint green, jint blue, jint alpha) {
    glColor4x(
        (GLfixed)red,
        (GLfixed)green,
        (GLfixed)blue,
        (GLfixed)alpha
    );
}

/* void glColorMask ( GLboolean red, GLboolean green, GLboolean blue, GLboolean alpha ) */
static void
android_glColorMask__ZZZZ
  (JNIEnv *_env, jobject _this, jboolean red, jboolean green, jboolean blue, jboolean alpha) {
    glColorMask(
        (GLboolean)red,
        (GLboolean)green,
        (GLboolean)blue,
        (GLboolean)alpha
    );
}

/* void glColorPointer ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer ) */
static void
android_glColorPointerBounds__IIILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint size, jint type, jint stride, jobject pointer_buf, jint remaining) {
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
    glColorPointerBounds(
        (GLint)size,
        (GLenum)type,
        (GLsizei)stride,
        (GLvoid *)pointer,
        (GLsizei)remaining
    );
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glCompressedTexImage2D ( GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height, GLint border, GLsizei imageSize, const GLvoid *data ) */
static void
android_glCompressedTexImage2D__IIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint internalformat, jint width, jint height, jint border, jint imageSize, jobject data_buf) {
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
    if (data == NULL) {
        char * _dataBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        data = (GLvoid *) (_dataBase + _bufferOffset);
    }
    glCompressedTexImage2D(
        (GLenum)target,
        (GLint)level,
        (GLenum)internalformat,
        (GLsizei)width,
        (GLsizei)height,
        (GLint)border,
        (GLsizei)imageSize,
        (GLvoid *)data
    );

exit:
    if (_array) {
        releasePointer(_env, _array, data, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glCompressedTexSubImage2D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLsizei imageSize, const GLvoid *data ) */
static void
android_glCompressedTexSubImage2D__IIIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint xoffset, jint yoffset, jint width, jint height, jint format, jint imageSize, jobject data_buf) {
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
    if (data == NULL) {
        char * _dataBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        data = (GLvoid *) (_dataBase + _bufferOffset);
    }
    glCompressedTexSubImage2D(
        (GLenum)target,
        (GLint)level,
        (GLint)xoffset,
        (GLint)yoffset,
        (GLsizei)width,
        (GLsizei)height,
        (GLenum)format,
        (GLsizei)imageSize,
        (GLvoid *)data
    );

exit:
    if (_array) {
        releasePointer(_env, _array, data, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glCopyTexImage2D ( GLenum target, GLint level, GLenum internalformat, GLint x, GLint y, GLsizei width, GLsizei height, GLint border ) */
static void
android_glCopyTexImage2D__IIIIIIII
  (JNIEnv *_env, jobject _this, jint target, jint level, jint internalformat, jint x, jint y, jint width, jint height, jint border) {
    glCopyTexImage2D(
        (GLenum)target,
        (GLint)level,
        (GLenum)internalformat,
        (GLint)x,
        (GLint)y,
        (GLsizei)width,
        (GLsizei)height,
        (GLint)border
    );
}

/* void glCopyTexSubImage2D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint x, GLint y, GLsizei width, GLsizei height ) */
static void
android_glCopyTexSubImage2D__IIIIIIII
  (JNIEnv *_env, jobject _this, jint target, jint level, jint xoffset, jint yoffset, jint x, jint y, jint width, jint height) {
    glCopyTexSubImage2D(
        (GLenum)target,
        (GLint)level,
        (GLint)xoffset,
        (GLint)yoffset,
        (GLint)x,
        (GLint)y,
        (GLsizei)width,
        (GLsizei)height
    );
}

/* void glCullFace ( GLenum mode ) */
static void
android_glCullFace__I
  (JNIEnv *_env, jobject _this, jint mode) {
    glCullFace(
        (GLenum)mode
    );
}

/* void glDeleteTextures ( GLsizei n, const GLuint *textures ) */
static void
android_glDeleteTextures__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray textures_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *textures_base = (GLuint *) 0;
    jint _remaining;
    GLuint *textures = (GLuint *) 0;

    if (!textures_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "textures == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(textures_ref) - offset;
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < n < needed";
        goto exit;
    }
    textures_base = (GLuint *)
        _env->GetIntArrayElements(textures_ref, (jboolean *)0);
    textures = textures_base + offset;

    glDeleteTextures(
        (GLsizei)n,
        (GLuint *)textures
    );

exit:
    if (textures_base) {
        _env->ReleaseIntArrayElements(textures_ref, (jint*)textures_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteTextures ( GLsizei n, const GLuint *textures ) */
static void
android_glDeleteTextures__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject textures_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *textures = (GLuint *) 0;

    if (!textures_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "textures == null";
        goto exit;
    }
    textures = (GLuint *)getPointer(_env, textures_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (textures == NULL) {
        char * _texturesBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        textures = (GLuint *) (_texturesBase + _bufferOffset);
    }
    glDeleteTextures(
        (GLsizei)n,
        (GLuint *)textures
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)textures, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDepthFunc ( GLenum func ) */
static void
android_glDepthFunc__I
  (JNIEnv *_env, jobject _this, jint func) {
    glDepthFunc(
        (GLenum)func
    );
}

/* void glDepthMask ( GLboolean flag ) */
static void
android_glDepthMask__Z
  (JNIEnv *_env, jobject _this, jboolean flag) {
    glDepthMask(
        (GLboolean)flag
    );
}

/* void glDepthRangef ( GLclampf zNear, GLclampf zFar ) */
static void
android_glDepthRangef__FF
  (JNIEnv *_env, jobject _this, jfloat zNear, jfloat zFar) {
    glDepthRangef(
        (GLclampf)zNear,
        (GLclampf)zFar
    );
}

/* void glDepthRangex ( GLclampx zNear, GLclampx zFar ) */
static void
android_glDepthRangex__II
  (JNIEnv *_env, jobject _this, jint zNear, jint zFar) {
    glDepthRangex(
        (GLclampx)zNear,
        (GLclampx)zFar
    );
}

/* void glDisable ( GLenum cap ) */
static void
android_glDisable__I
  (JNIEnv *_env, jobject _this, jint cap) {
    glDisable(
        (GLenum)cap
    );
}

/* void glDisableClientState ( GLenum array ) */
static void
android_glDisableClientState__I
  (JNIEnv *_env, jobject _this, jint array) {
    glDisableClientState(
        (GLenum)array
    );
}

/* void glDrawArrays ( GLenum mode, GLint first, GLsizei count ) */
static void
android_glDrawArrays__III
  (JNIEnv *_env, jobject _this, jint mode, jint first, jint count) {
    glDrawArrays(
        (GLenum)mode,
        (GLint)first,
        (GLsizei)count
    );
}

/* void glDrawElements ( GLenum mode, GLsizei count, GLenum type, const GLvoid *indices ) */
static void
android_glDrawElements__IIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint mode, jint count, jint type, jobject indices_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *indices = (GLvoid *) 0;

    if (!indices_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "indices == null";
        goto exit;
    }
    indices = (GLvoid *)getPointer(_env, indices_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < count) {
        _exception = 1;
        _exceptionType = "java/lang/ArrayIndexOutOfBoundsException";
        _exceptionMessage = "remaining() < count < needed";
        goto exit;
    }
    if (indices == NULL) {
        char * _indicesBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        indices = (GLvoid *) (_indicesBase + _bufferOffset);
    }
    glDrawElements(
        (GLenum)mode,
        (GLsizei)count,
        (GLenum)type,
        (GLvoid *)indices
    );

exit:
    if (_array) {
        releasePointer(_env, _array, indices, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glEnable ( GLenum cap ) */
static void
android_glEnable__I
  (JNIEnv *_env, jobject _this, jint cap) {
    glEnable(
        (GLenum)cap
    );
}

/* void glEnableClientState ( GLenum array ) */
static void
android_glEnableClientState__I
  (JNIEnv *_env, jobject _this, jint array) {
    glEnableClientState(
        (GLenum)array
    );
}

/* void glFinish ( void ) */
static void
android_glFinish__
  (JNIEnv *_env, jobject _this) {
    glFinish();
}

/* void glFlush ( void ) */
static void
android_glFlush__
  (JNIEnv *_env, jobject _this) {
    glFlush();
}

/* void glFogf ( GLenum pname, GLfloat param ) */
static void
android_glFogf__IF
  (JNIEnv *_env, jobject _this, jint pname, jfloat param) {
    glFogf(
        (GLenum)pname,
        (GLfloat)param
    );
}

/* void glFogfv ( GLenum pname, const GLfloat *params ) */
static void
android_glFogfv__I_3FI
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
    int _needed;
    switch (pname) {
#if defined(GL_FOG_COLOR)
        case GL_FOG_COLOR:
#endif // defined(GL_FOG_COLOR)
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

    glFogfv(
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

/* void glFogfv ( GLenum pname, const GLfloat *params ) */
static void
android_glFogfv__ILjava_nio_FloatBuffer_2
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
    int _needed;
    switch (pname) {
#if defined(GL_FOG_COLOR)
        case GL_FOG_COLOR:
#endif // defined(GL_FOG_COLOR)
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
    glFogfv(
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

/* void glFogx ( GLenum pname, GLfixed param ) */
static void
android_glFogx__II
  (JNIEnv *_env, jobject _this, jint pname, jint param) {
    glFogx(
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glFogxv ( GLenum pname, const GLfixed *params ) */
static void
android_glFogxv__I_3II
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
    int _needed;
    switch (pname) {
#if defined(GL_FOG_COLOR)
        case GL_FOG_COLOR:
#endif // defined(GL_FOG_COLOR)
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

    glFogxv(
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

/* void glFogxv ( GLenum pname, const GLfixed *params ) */
static void
android_glFogxv__ILjava_nio_IntBuffer_2
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
    int _needed;
    switch (pname) {
#if defined(GL_FOG_COLOR)
        case GL_FOG_COLOR:
#endif // defined(GL_FOG_COLOR)
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
    glFogxv(
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

/* void glFrontFace ( GLenum mode ) */
static void
android_glFrontFace__I
  (JNIEnv *_env, jobject _this, jint mode) {
    glFrontFace(
        (GLenum)mode
    );
}

/* void glFrustumf ( GLfloat left, GLfloat right, GLfloat bottom, GLfloat top, GLfloat zNear, GLfloat zFar ) */
static void
android_glFrustumf__FFFFFF
  (JNIEnv *_env, jobject _this, jfloat left, jfloat right, jfloat bottom, jfloat top, jfloat zNear, jfloat zFar) {
    glFrustumf(
        (GLfloat)left,
        (GLfloat)right,
        (GLfloat)bottom,
        (GLfloat)top,
        (GLfloat)zNear,
        (GLfloat)zFar
    );
}

/* void glFrustumx ( GLfixed left, GLfixed right, GLfixed bottom, GLfixed top, GLfixed zNear, GLfixed zFar ) */
static void
android_glFrustumx__IIIIII
  (JNIEnv *_env, jobject _this, jint left, jint right, jint bottom, jint top, jint zNear, jint zFar) {
    glFrustumx(
        (GLfixed)left,
        (GLfixed)right,
        (GLfixed)bottom,
        (GLfixed)top,
        (GLfixed)zNear,
        (GLfixed)zFar
    );
}

/* void glGenTextures ( GLsizei n, GLuint *textures ) */
static void
android_glGenTextures__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray textures_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *textures_base = (GLuint *) 0;
    jint _remaining;
    GLuint *textures = (GLuint *) 0;

    if (!textures_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "textures == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(textures_ref) - offset;
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < n < needed";
        goto exit;
    }
    textures_base = (GLuint *)
        _env->GetIntArrayElements(textures_ref, (jboolean *)0);
    textures = textures_base + offset;

    glGenTextures(
        (GLsizei)n,
        (GLuint *)textures
    );

exit:
    if (textures_base) {
        _env->ReleaseIntArrayElements(textures_ref, (jint*)textures_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenTextures ( GLsizei n, GLuint *textures ) */
static void
android_glGenTextures__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject textures_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *textures = (GLuint *) 0;

    if (!textures_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "textures == null";
        goto exit;
    }
    textures = (GLuint *)getPointer(_env, textures_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (textures == NULL) {
        char * _texturesBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        textures = (GLuint *) (_texturesBase + _bufferOffset);
    }
    glGenTextures(
        (GLsizei)n,
        (GLuint *)textures
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)textures, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLenum glGetError ( void ) */
static jint
android_glGetError__
  (JNIEnv *_env, jobject _this) {
    GLenum _returnValue;
    _returnValue = glGetError();
    return (jint)_returnValue;
}

/* void glGetIntegerv ( GLenum pname, GLint *params ) */
static void
android_glGetIntegerv__I_3II
  (JNIEnv *_env, jobject _this, jint pname, jintArray params_ref, jint offset) {
    get<jintArray, IntArrayGetter, jint*, IntArrayReleaser, GLint, glGetIntegerv>(
        _env, _this, pname, params_ref, offset);
}

/* void glGetIntegerv ( GLenum pname, GLint *params ) */
static void
android_glGetIntegerv__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    getarray<GLint, jintArray, IntArrayGetter, jint*, IntArrayReleaser, glGetIntegerv>(
        _env, _this, pname, params_buf);
}
/* const GLubyte * glGetString ( GLenum name ) */
static jstring android_glGetString(JNIEnv* _env, jobject, jint name) {
    const char* chars = (const char*) glGetString((GLenum) name);
    return _env->NewStringUTF(chars);
}
/* void glHint ( GLenum target, GLenum mode ) */
static void
android_glHint__II
  (JNIEnv *_env, jobject _this, jint target, jint mode) {
    glHint(
        (GLenum)target,
        (GLenum)mode
    );
}

/* void glLightModelf ( GLenum pname, GLfloat param ) */
static void
android_glLightModelf__IF
  (JNIEnv *_env, jobject _this, jint pname, jfloat param) {
    glLightModelf(
        (GLenum)pname,
        (GLfloat)param
    );
}

/* void glLightModelfv ( GLenum pname, const GLfloat *params ) */
static void
android_glLightModelfv__I_3FI
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
    int _needed;
    switch (pname) {
#if defined(GL_LIGHT_MODEL_AMBIENT)
        case GL_LIGHT_MODEL_AMBIENT:
#endif // defined(GL_LIGHT_MODEL_AMBIENT)
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

    glLightModelfv(
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

/* void glLightModelfv ( GLenum pname, const GLfloat *params ) */
static void
android_glLightModelfv__ILjava_nio_FloatBuffer_2
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
    int _needed;
    switch (pname) {
#if defined(GL_LIGHT_MODEL_AMBIENT)
        case GL_LIGHT_MODEL_AMBIENT:
#endif // defined(GL_LIGHT_MODEL_AMBIENT)
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
    glLightModelfv(
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

/* void glLightModelx ( GLenum pname, GLfixed param ) */
static void
android_glLightModelx__II
  (JNIEnv *_env, jobject _this, jint pname, jint param) {
    glLightModelx(
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glLightModelxv ( GLenum pname, const GLfixed *params ) */
static void
android_glLightModelxv__I_3II
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
    int _needed;
    switch (pname) {
#if defined(GL_LIGHT_MODEL_AMBIENT)
        case GL_LIGHT_MODEL_AMBIENT:
#endif // defined(GL_LIGHT_MODEL_AMBIENT)
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

    glLightModelxv(
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

/* void glLightModelxv ( GLenum pname, const GLfixed *params ) */
static void
android_glLightModelxv__ILjava_nio_IntBuffer_2
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
    int _needed;
    switch (pname) {
#if defined(GL_LIGHT_MODEL_AMBIENT)
        case GL_LIGHT_MODEL_AMBIENT:
#endif // defined(GL_LIGHT_MODEL_AMBIENT)
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
    glLightModelxv(
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

/* void glLightf ( GLenum light, GLenum pname, GLfloat param ) */
static void
android_glLightf__IIF
  (JNIEnv *_env, jobject _this, jint light, jint pname, jfloat param) {
    glLightf(
        (GLenum)light,
        (GLenum)pname,
        (GLfloat)param
    );
}

/* void glLightfv ( GLenum light, GLenum pname, const GLfloat *params ) */
static void
android_glLightfv__II_3FI
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

    glLightfv(
        (GLenum)light,
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

/* void glLightfv ( GLenum light, GLenum pname, const GLfloat *params ) */
static void
android_glLightfv__IILjava_nio_FloatBuffer_2
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
    glLightfv(
        (GLenum)light,
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

/* void glLightx ( GLenum light, GLenum pname, GLfixed param ) */
static void
android_glLightx__III
  (JNIEnv *_env, jobject _this, jint light, jint pname, jint param) {
    glLightx(
        (GLenum)light,
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glLightxv ( GLenum light, GLenum pname, const GLfixed *params ) */
static void
android_glLightxv__II_3II
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

    glLightxv(
        (GLenum)light,
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

/* void glLightxv ( GLenum light, GLenum pname, const GLfixed *params ) */
static void
android_glLightxv__IILjava_nio_IntBuffer_2
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
    glLightxv(
        (GLenum)light,
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

/* void glLineWidth ( GLfloat width ) */
static void
android_glLineWidth__F
  (JNIEnv *_env, jobject _this, jfloat width) {
    glLineWidth(
        (GLfloat)width
    );
}

/* void glLineWidthx ( GLfixed width ) */
static void
android_glLineWidthx__I
  (JNIEnv *_env, jobject _this, jint width) {
    glLineWidthx(
        (GLfixed)width
    );
}

/* void glLoadIdentity ( void ) */
static void
android_glLoadIdentity__
  (JNIEnv *_env, jobject _this) {
    glLoadIdentity();
}

/* void glLoadMatrixf ( const GLfloat *m ) */
static void
android_glLoadMatrixf___3FI
  (JNIEnv *_env, jobject _this, jfloatArray m_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *m_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *m = (GLfloat *) 0;

    if (!m_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "m == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(m_ref) - offset;
    m_base = (GLfloat *)
        _env->GetFloatArrayElements(m_ref, (jboolean *)0);
    m = m_base + offset;

    glLoadMatrixf(
        (GLfloat *)m
    );

exit:
    if (m_base) {
        _env->ReleaseFloatArrayElements(m_ref, (jfloat*)m_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glLoadMatrixf ( const GLfloat *m ) */
static void
android_glLoadMatrixf__Ljava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jobject m_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *m = (GLfloat *) 0;

    if (!m_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "m == null";
        goto exit;
    }
    m = (GLfloat *)getPointer(_env, m_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (m == NULL) {
        char * _mBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        m = (GLfloat *) (_mBase + _bufferOffset);
    }
    glLoadMatrixf(
        (GLfloat *)m
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)m, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glLoadMatrixx ( const GLfixed *m ) */
static void
android_glLoadMatrixx___3II
  (JNIEnv *_env, jobject _this, jintArray m_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfixed *m_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *m = (GLfixed *) 0;

    if (!m_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "m == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(m_ref) - offset;
    m_base = (GLfixed *)
        _env->GetIntArrayElements(m_ref, (jboolean *)0);
    m = m_base + offset;

    glLoadMatrixx(
        (GLfixed *)m
    );

exit:
    if (m_base) {
        _env->ReleaseIntArrayElements(m_ref, (jint*)m_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glLoadMatrixx ( const GLfixed *m ) */
static void
android_glLoadMatrixx__Ljava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jobject m_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfixed *m = (GLfixed *) 0;

    if (!m_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "m == null";
        goto exit;
    }
    m = (GLfixed *)getPointer(_env, m_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (m == NULL) {
        char * _mBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        m = (GLfixed *) (_mBase + _bufferOffset);
    }
    glLoadMatrixx(
        (GLfixed *)m
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)m, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glLogicOp ( GLenum opcode ) */
static void
android_glLogicOp__I
  (JNIEnv *_env, jobject _this, jint opcode) {
    glLogicOp(
        (GLenum)opcode
    );
}

/* void glMaterialf ( GLenum face, GLenum pname, GLfloat param ) */
static void
android_glMaterialf__IIF
  (JNIEnv *_env, jobject _this, jint face, jint pname, jfloat param) {
    glMaterialf(
        (GLenum)face,
        (GLenum)pname,
        (GLfloat)param
    );
}

/* void glMaterialfv ( GLenum face, GLenum pname, const GLfloat *params ) */
static void
android_glMaterialfv__II_3FI
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

    glMaterialfv(
        (GLenum)face,
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

/* void glMaterialfv ( GLenum face, GLenum pname, const GLfloat *params ) */
static void
android_glMaterialfv__IILjava_nio_FloatBuffer_2
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
    glMaterialfv(
        (GLenum)face,
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

/* void glMaterialx ( GLenum face, GLenum pname, GLfixed param ) */
static void
android_glMaterialx__III
  (JNIEnv *_env, jobject _this, jint face, jint pname, jint param) {
    glMaterialx(
        (GLenum)face,
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glMaterialxv ( GLenum face, GLenum pname, const GLfixed *params ) */
static void
android_glMaterialxv__II_3II
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

    glMaterialxv(
        (GLenum)face,
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

/* void glMaterialxv ( GLenum face, GLenum pname, const GLfixed *params ) */
static void
android_glMaterialxv__IILjava_nio_IntBuffer_2
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
    glMaterialxv(
        (GLenum)face,
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

/* void glMatrixMode ( GLenum mode ) */
static void
android_glMatrixMode__I
  (JNIEnv *_env, jobject _this, jint mode) {
    glMatrixMode(
        (GLenum)mode
    );
}

/* void glMultMatrixf ( const GLfloat *m ) */
static void
android_glMultMatrixf___3FI
  (JNIEnv *_env, jobject _this, jfloatArray m_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *m_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *m = (GLfloat *) 0;

    if (!m_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "m == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(m_ref) - offset;
    m_base = (GLfloat *)
        _env->GetFloatArrayElements(m_ref, (jboolean *)0);
    m = m_base + offset;

    glMultMatrixf(
        (GLfloat *)m
    );

exit:
    if (m_base) {
        _env->ReleaseFloatArrayElements(m_ref, (jfloat*)m_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glMultMatrixf ( const GLfloat *m ) */
static void
android_glMultMatrixf__Ljava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jobject m_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *m = (GLfloat *) 0;

    if (!m_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "m == null";
        goto exit;
    }
    m = (GLfloat *)getPointer(_env, m_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (m == NULL) {
        char * _mBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        m = (GLfloat *) (_mBase + _bufferOffset);
    }
    glMultMatrixf(
        (GLfloat *)m
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)m, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glMultMatrixx ( const GLfixed *m ) */
static void
android_glMultMatrixx___3II
  (JNIEnv *_env, jobject _this, jintArray m_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfixed *m_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *m = (GLfixed *) 0;

    if (!m_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "m == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(m_ref) - offset;
    m_base = (GLfixed *)
        _env->GetIntArrayElements(m_ref, (jboolean *)0);
    m = m_base + offset;

    glMultMatrixx(
        (GLfixed *)m
    );

exit:
    if (m_base) {
        _env->ReleaseIntArrayElements(m_ref, (jint*)m_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glMultMatrixx ( const GLfixed *m ) */
static void
android_glMultMatrixx__Ljava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jobject m_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfixed *m = (GLfixed *) 0;

    if (!m_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "m == null";
        goto exit;
    }
    m = (GLfixed *)getPointer(_env, m_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (m == NULL) {
        char * _mBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        m = (GLfixed *) (_mBase + _bufferOffset);
    }
    glMultMatrixx(
        (GLfixed *)m
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)m, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glMultiTexCoord4f ( GLenum target, GLfloat s, GLfloat t, GLfloat r, GLfloat q ) */
static void
android_glMultiTexCoord4f__IFFFF
  (JNIEnv *_env, jobject _this, jint target, jfloat s, jfloat t, jfloat r, jfloat q) {
    glMultiTexCoord4f(
        (GLenum)target,
        (GLfloat)s,
        (GLfloat)t,
        (GLfloat)r,
        (GLfloat)q
    );
}

/* void glMultiTexCoord4x ( GLenum target, GLfixed s, GLfixed t, GLfixed r, GLfixed q ) */
static void
android_glMultiTexCoord4x__IIIII
  (JNIEnv *_env, jobject _this, jint target, jint s, jint t, jint r, jint q) {
    glMultiTexCoord4x(
        (GLenum)target,
        (GLfixed)s,
        (GLfixed)t,
        (GLfixed)r,
        (GLfixed)q
    );
}

/* void glNormal3f ( GLfloat nx, GLfloat ny, GLfloat nz ) */
static void
android_glNormal3f__FFF
  (JNIEnv *_env, jobject _this, jfloat nx, jfloat ny, jfloat nz) {
    glNormal3f(
        (GLfloat)nx,
        (GLfloat)ny,
        (GLfloat)nz
    );
}

/* void glNormal3x ( GLfixed nx, GLfixed ny, GLfixed nz ) */
static void
android_glNormal3x__III
  (JNIEnv *_env, jobject _this, jint nx, jint ny, jint nz) {
    glNormal3x(
        (GLfixed)nx,
        (GLfixed)ny,
        (GLfixed)nz
    );
}

/* void glNormalPointer ( GLenum type, GLsizei stride, const GLvoid *pointer ) */
static void
android_glNormalPointerBounds__IILjava_nio_Buffer_2I
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
    glNormalPointerBounds(
        (GLenum)type,
        (GLsizei)stride,
        (GLvoid *)pointer,
        (GLsizei)remaining
    );
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glOrthof ( GLfloat left, GLfloat right, GLfloat bottom, GLfloat top, GLfloat zNear, GLfloat zFar ) */
static void
android_glOrthof__FFFFFF
  (JNIEnv *_env, jobject _this, jfloat left, jfloat right, jfloat bottom, jfloat top, jfloat zNear, jfloat zFar) {
    glOrthof(
        (GLfloat)left,
        (GLfloat)right,
        (GLfloat)bottom,
        (GLfloat)top,
        (GLfloat)zNear,
        (GLfloat)zFar
    );
}

/* void glOrthox ( GLfixed left, GLfixed right, GLfixed bottom, GLfixed top, GLfixed zNear, GLfixed zFar ) */
static void
android_glOrthox__IIIIII
  (JNIEnv *_env, jobject _this, jint left, jint right, jint bottom, jint top, jint zNear, jint zFar) {
    glOrthox(
        (GLfixed)left,
        (GLfixed)right,
        (GLfixed)bottom,
        (GLfixed)top,
        (GLfixed)zNear,
        (GLfixed)zFar
    );
}

/* void glPixelStorei ( GLenum pname, GLint param ) */
static void
android_glPixelStorei__II
  (JNIEnv *_env, jobject _this, jint pname, jint param) {
    glPixelStorei(
        (GLenum)pname,
        (GLint)param
    );
}

/* void glPointSize ( GLfloat size ) */
static void
android_glPointSize__F
  (JNIEnv *_env, jobject _this, jfloat size) {
    glPointSize(
        (GLfloat)size
    );
}

/* void glPointSizex ( GLfixed size ) */
static void
android_glPointSizex__I
  (JNIEnv *_env, jobject _this, jint size) {
    glPointSizex(
        (GLfixed)size
    );
}

/* void glPolygonOffset ( GLfloat factor, GLfloat units ) */
static void
android_glPolygonOffset__FF
  (JNIEnv *_env, jobject _this, jfloat factor, jfloat units) {
    glPolygonOffset(
        (GLfloat)factor,
        (GLfloat)units
    );
}

/* void glPolygonOffsetx ( GLfixed factor, GLfixed units ) */
static void
android_glPolygonOffsetx__II
  (JNIEnv *_env, jobject _this, jint factor, jint units) {
    glPolygonOffsetx(
        (GLfixed)factor,
        (GLfixed)units
    );
}

/* void glPopMatrix ( void ) */
static void
android_glPopMatrix__
  (JNIEnv *_env, jobject _this) {
    glPopMatrix();
}

/* void glPushMatrix ( void ) */
static void
android_glPushMatrix__
  (JNIEnv *_env, jobject _this) {
    glPushMatrix();
}

/* void glReadPixels ( GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, GLvoid *pixels ) */
static void
android_glReadPixels__IIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint x, jint y, jint width, jint height, jint format, jint type, jobject pixels_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *pixels = (GLvoid *) 0;

    if (!pixels_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "pixels == null";
        goto exit;
    }
    pixels = (GLvoid *)getPointer(_env, pixels_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (pixels == NULL) {
        char * _pixelsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        pixels = (GLvoid *) (_pixelsBase + _bufferOffset);
    }
    glReadPixels(
        (GLint)x,
        (GLint)y,
        (GLsizei)width,
        (GLsizei)height,
        (GLenum)format,
        (GLenum)type,
        (GLvoid *)pixels
    );

exit:
    if (_array) {
        releasePointer(_env, _array, pixels, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glRotatef ( GLfloat angle, GLfloat x, GLfloat y, GLfloat z ) */
static void
android_glRotatef__FFFF
  (JNIEnv *_env, jobject _this, jfloat angle, jfloat x, jfloat y, jfloat z) {
    glRotatef(
        (GLfloat)angle,
        (GLfloat)x,
        (GLfloat)y,
        (GLfloat)z
    );
}

/* void glRotatex ( GLfixed angle, GLfixed x, GLfixed y, GLfixed z ) */
static void
android_glRotatex__IIII
  (JNIEnv *_env, jobject _this, jint angle, jint x, jint y, jint z) {
    glRotatex(
        (GLfixed)angle,
        (GLfixed)x,
        (GLfixed)y,
        (GLfixed)z
    );
}

/* void glSampleCoverage ( GLclampf value, GLboolean invert ) */
static void
android_glSampleCoverage__FZ
  (JNIEnv *_env, jobject _this, jfloat value, jboolean invert) {
    glSampleCoverage(
        (GLclampf)value,
        (GLboolean)invert
    );
}

/* void glSampleCoveragex ( GLclampx value, GLboolean invert ) */
static void
android_glSampleCoveragex__IZ
  (JNIEnv *_env, jobject _this, jint value, jboolean invert) {
    glSampleCoveragex(
        (GLclampx)value,
        (GLboolean)invert
    );
}

/* void glScalef ( GLfloat x, GLfloat y, GLfloat z ) */
static void
android_glScalef__FFF
  (JNIEnv *_env, jobject _this, jfloat x, jfloat y, jfloat z) {
    glScalef(
        (GLfloat)x,
        (GLfloat)y,
        (GLfloat)z
    );
}

/* void glScalex ( GLfixed x, GLfixed y, GLfixed z ) */
static void
android_glScalex__III
  (JNIEnv *_env, jobject _this, jint x, jint y, jint z) {
    glScalex(
        (GLfixed)x,
        (GLfixed)y,
        (GLfixed)z
    );
}

/* void glScissor ( GLint x, GLint y, GLsizei width, GLsizei height ) */
static void
android_glScissor__IIII
  (JNIEnv *_env, jobject _this, jint x, jint y, jint width, jint height) {
    glScissor(
        (GLint)x,
        (GLint)y,
        (GLsizei)width,
        (GLsizei)height
    );
}

/* void glShadeModel ( GLenum mode ) */
static void
android_glShadeModel__I
  (JNIEnv *_env, jobject _this, jint mode) {
    glShadeModel(
        (GLenum)mode
    );
}

/* void glStencilFunc ( GLenum func, GLint ref, GLuint mask ) */
static void
android_glStencilFunc__III
  (JNIEnv *_env, jobject _this, jint func, jint ref, jint mask) {
    glStencilFunc(
        (GLenum)func,
        (GLint)ref,
        (GLuint)mask
    );
}

/* void glStencilMask ( GLuint mask ) */
static void
android_glStencilMask__I
  (JNIEnv *_env, jobject _this, jint mask) {
    glStencilMask(
        (GLuint)mask
    );
}

/* void glStencilOp ( GLenum fail, GLenum zfail, GLenum zpass ) */
static void
android_glStencilOp__III
  (JNIEnv *_env, jobject _this, jint fail, jint zfail, jint zpass) {
    glStencilOp(
        (GLenum)fail,
        (GLenum)zfail,
        (GLenum)zpass
    );
}

/* void glTexCoordPointer ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer ) */
static void
android_glTexCoordPointerBounds__IIILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint size, jint type, jint stride, jobject pointer_buf, jint remaining) {
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
    glTexCoordPointerBounds(
        (GLint)size,
        (GLenum)type,
        (GLsizei)stride,
        (GLvoid *)pointer,
        (GLsizei)remaining
    );
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexEnvf ( GLenum target, GLenum pname, GLfloat param ) */
static void
android_glTexEnvf__IIF
  (JNIEnv *_env, jobject _this, jint target, jint pname, jfloat param) {
    glTexEnvf(
        (GLenum)target,
        (GLenum)pname,
        (GLfloat)param
    );
}

/* void glTexEnvfv ( GLenum target, GLenum pname, const GLfloat *params ) */
static void
android_glTexEnvfv__II_3FI
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

    glTexEnvfv(
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

/* void glTexEnvfv ( GLenum target, GLenum pname, const GLfloat *params ) */
static void
android_glTexEnvfv__IILjava_nio_FloatBuffer_2
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
    glTexEnvfv(
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

/* void glTexEnvx ( GLenum target, GLenum pname, GLfixed param ) */
static void
android_glTexEnvx__III
  (JNIEnv *_env, jobject _this, jint target, jint pname, jint param) {
    glTexEnvx(
        (GLenum)target,
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glTexEnvxv ( GLenum target, GLenum pname, const GLfixed *params ) */
static void
android_glTexEnvxv__II_3II
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

    glTexEnvxv(
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

/* void glTexEnvxv ( GLenum target, GLenum pname, const GLfixed *params ) */
static void
android_glTexEnvxv__IILjava_nio_IntBuffer_2
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
    glTexEnvxv(
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

/* void glTexImage2D ( GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLint border, GLenum format, GLenum type, const GLvoid *pixels ) */
static void
android_glTexImage2D__IIIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint internalformat, jint width, jint height, jint border, jint format, jint type, jobject pixels_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *pixels = (GLvoid *) 0;

    if (pixels_buf) {
        pixels = (GLvoid *)getPointer(_env, pixels_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    }
    if (pixels_buf && pixels == NULL) {
        char * _pixelsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        pixels = (GLvoid *) (_pixelsBase + _bufferOffset);
    }
    glTexImage2D(
        (GLenum)target,
        (GLint)level,
        (GLint)internalformat,
        (GLsizei)width,
        (GLsizei)height,
        (GLint)border,
        (GLenum)format,
        (GLenum)type,
        (GLvoid *)pixels
    );
    if (_array) {
        releasePointer(_env, _array, pixels, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexParameterf ( GLenum target, GLenum pname, GLfloat param ) */
static void
android_glTexParameterf__IIF
  (JNIEnv *_env, jobject _this, jint target, jint pname, jfloat param) {
    glTexParameterf(
        (GLenum)target,
        (GLenum)pname,
        (GLfloat)param
    );
}

/* void glTexParameterx ( GLenum target, GLenum pname, GLfixed param ) */
static void
android_glTexParameterx__III
  (JNIEnv *_env, jobject _this, jint target, jint pname, jint param) {
    glTexParameterx(
        (GLenum)target,
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glTexSubImage2D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const GLvoid *pixels ) */
static void
android_glTexSubImage2D__IIIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint xoffset, jint yoffset, jint width, jint height, jint format, jint type, jobject pixels_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *pixels = (GLvoid *) 0;

    if (pixels_buf) {
        pixels = (GLvoid *)getPointer(_env, pixels_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    }
    if (pixels_buf && pixels == NULL) {
        char * _pixelsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        pixels = (GLvoid *) (_pixelsBase + _bufferOffset);
    }
    glTexSubImage2D(
        (GLenum)target,
        (GLint)level,
        (GLint)xoffset,
        (GLint)yoffset,
        (GLsizei)width,
        (GLsizei)height,
        (GLenum)format,
        (GLenum)type,
        (GLvoid *)pixels
    );
    if (_array) {
        releasePointer(_env, _array, pixels, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTranslatef ( GLfloat x, GLfloat y, GLfloat z ) */
static void
android_glTranslatef__FFF
  (JNIEnv *_env, jobject _this, jfloat x, jfloat y, jfloat z) {
    glTranslatef(
        (GLfloat)x,
        (GLfloat)y,
        (GLfloat)z
    );
}

/* void glTranslatex ( GLfixed x, GLfixed y, GLfixed z ) */
static void
android_glTranslatex__III
  (JNIEnv *_env, jobject _this, jint x, jint y, jint z) {
    glTranslatex(
        (GLfixed)x,
        (GLfixed)y,
        (GLfixed)z
    );
}

/* void glVertexPointer ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer ) */
static void
android_glVertexPointerBounds__IIILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint size, jint type, jint stride, jobject pointer_buf, jint remaining) {
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
    glVertexPointerBounds(
        (GLint)size,
        (GLenum)type,
        (GLsizei)stride,
        (GLvoid *)pointer,
        (GLsizei)remaining
    );
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glViewport ( GLint x, GLint y, GLsizei width, GLsizei height ) */
static void
android_glViewport__IIII
  (JNIEnv *_env, jobject _this, jint x, jint y, jint width, jint height) {
    glViewport(
        (GLint)x,
        (GLint)y,
        (GLsizei)width,
        (GLsizei)height
    );
}

static const char *classPathName = "android/opengl/GLES10";

static const JNINativeMethod methods[] = {
{"_nativeClassInit", "()V", (void*)nativeClassInit },
{"glActiveTexture", "(I)V", (void *) android_glActiveTexture__I },
{"glAlphaFunc", "(IF)V", (void *) android_glAlphaFunc__IF },
{"glAlphaFuncx", "(II)V", (void *) android_glAlphaFuncx__II },
{"glBindTexture", "(II)V", (void *) android_glBindTexture__II },
{"glBlendFunc", "(II)V", (void *) android_glBlendFunc__II },
{"glClear", "(I)V", (void *) android_glClear__I },
{"glClearColor", "(FFFF)V", (void *) android_glClearColor__FFFF },
{"glClearColorx", "(IIII)V", (void *) android_glClearColorx__IIII },
{"glClearDepthf", "(F)V", (void *) android_glClearDepthf__F },
{"glClearDepthx", "(I)V", (void *) android_glClearDepthx__I },
{"glClearStencil", "(I)V", (void *) android_glClearStencil__I },
{"glClientActiveTexture", "(I)V", (void *) android_glClientActiveTexture__I },
{"glColor4f", "(FFFF)V", (void *) android_glColor4f__FFFF },
{"glColor4x", "(IIII)V", (void *) android_glColor4x__IIII },
{"glColorMask", "(ZZZZ)V", (void *) android_glColorMask__ZZZZ },
{"glColorPointerBounds", "(IIILjava/nio/Buffer;I)V", (void *) android_glColorPointerBounds__IIILjava_nio_Buffer_2I },
{"glCompressedTexImage2D", "(IIIIIIILjava/nio/Buffer;)V", (void *) android_glCompressedTexImage2D__IIIIIIILjava_nio_Buffer_2 },
{"glCompressedTexSubImage2D", "(IIIIIIIILjava/nio/Buffer;)V", (void *) android_glCompressedTexSubImage2D__IIIIIIIILjava_nio_Buffer_2 },
{"glCopyTexImage2D", "(IIIIIIII)V", (void *) android_glCopyTexImage2D__IIIIIIII },
{"glCopyTexSubImage2D", "(IIIIIIII)V", (void *) android_glCopyTexSubImage2D__IIIIIIII },
{"glCullFace", "(I)V", (void *) android_glCullFace__I },
{"glDeleteTextures", "(I[II)V", (void *) android_glDeleteTextures__I_3II },
{"glDeleteTextures", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteTextures__ILjava_nio_IntBuffer_2 },
{"glDepthFunc", "(I)V", (void *) android_glDepthFunc__I },
{"glDepthMask", "(Z)V", (void *) android_glDepthMask__Z },
{"glDepthRangef", "(FF)V", (void *) android_glDepthRangef__FF },
{"glDepthRangex", "(II)V", (void *) android_glDepthRangex__II },
{"glDisable", "(I)V", (void *) android_glDisable__I },
{"glDisableClientState", "(I)V", (void *) android_glDisableClientState__I },
{"glDrawArrays", "(III)V", (void *) android_glDrawArrays__III },
{"glDrawElements", "(IIILjava/nio/Buffer;)V", (void *) android_glDrawElements__IIILjava_nio_Buffer_2 },
{"glEnable", "(I)V", (void *) android_glEnable__I },
{"glEnableClientState", "(I)V", (void *) android_glEnableClientState__I },
{"glFinish", "()V", (void *) android_glFinish__ },
{"glFlush", "()V", (void *) android_glFlush__ },
{"glFogf", "(IF)V", (void *) android_glFogf__IF },
{"glFogfv", "(I[FI)V", (void *) android_glFogfv__I_3FI },
{"glFogfv", "(ILjava/nio/FloatBuffer;)V", (void *) android_glFogfv__ILjava_nio_FloatBuffer_2 },
{"glFogx", "(II)V", (void *) android_glFogx__II },
{"glFogxv", "(I[II)V", (void *) android_glFogxv__I_3II },
{"glFogxv", "(ILjava/nio/IntBuffer;)V", (void *) android_glFogxv__ILjava_nio_IntBuffer_2 },
{"glFrontFace", "(I)V", (void *) android_glFrontFace__I },
{"glFrustumf", "(FFFFFF)V", (void *) android_glFrustumf__FFFFFF },
{"glFrustumx", "(IIIIII)V", (void *) android_glFrustumx__IIIIII },
{"glGenTextures", "(I[II)V", (void *) android_glGenTextures__I_3II },
{"glGenTextures", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenTextures__ILjava_nio_IntBuffer_2 },
{"glGetError", "()I", (void *) android_glGetError__ },
{"glGetIntegerv", "(I[II)V", (void *) android_glGetIntegerv__I_3II },
{"glGetIntegerv", "(ILjava/nio/IntBuffer;)V", (void *) android_glGetIntegerv__ILjava_nio_IntBuffer_2 },
{"glGetString", "(I)Ljava/lang/String;", (void *) android_glGetString },
{"glHint", "(II)V", (void *) android_glHint__II },
{"glLightModelf", "(IF)V", (void *) android_glLightModelf__IF },
{"glLightModelfv", "(I[FI)V", (void *) android_glLightModelfv__I_3FI },
{"glLightModelfv", "(ILjava/nio/FloatBuffer;)V", (void *) android_glLightModelfv__ILjava_nio_FloatBuffer_2 },
{"glLightModelx", "(II)V", (void *) android_glLightModelx__II },
{"glLightModelxv", "(I[II)V", (void *) android_glLightModelxv__I_3II },
{"glLightModelxv", "(ILjava/nio/IntBuffer;)V", (void *) android_glLightModelxv__ILjava_nio_IntBuffer_2 },
{"glLightf", "(IIF)V", (void *) android_glLightf__IIF },
{"glLightfv", "(II[FI)V", (void *) android_glLightfv__II_3FI },
{"glLightfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glLightfv__IILjava_nio_FloatBuffer_2 },
{"glLightx", "(III)V", (void *) android_glLightx__III },
{"glLightxv", "(II[II)V", (void *) android_glLightxv__II_3II },
{"glLightxv", "(IILjava/nio/IntBuffer;)V", (void *) android_glLightxv__IILjava_nio_IntBuffer_2 },
{"glLineWidth", "(F)V", (void *) android_glLineWidth__F },
{"glLineWidthx", "(I)V", (void *) android_glLineWidthx__I },
{"glLoadIdentity", "()V", (void *) android_glLoadIdentity__ },
{"glLoadMatrixf", "([FI)V", (void *) android_glLoadMatrixf___3FI },
{"glLoadMatrixf", "(Ljava/nio/FloatBuffer;)V", (void *) android_glLoadMatrixf__Ljava_nio_FloatBuffer_2 },
{"glLoadMatrixx", "([II)V", (void *) android_glLoadMatrixx___3II },
{"glLoadMatrixx", "(Ljava/nio/IntBuffer;)V", (void *) android_glLoadMatrixx__Ljava_nio_IntBuffer_2 },
{"glLogicOp", "(I)V", (void *) android_glLogicOp__I },
{"glMaterialf", "(IIF)V", (void *) android_glMaterialf__IIF },
{"glMaterialfv", "(II[FI)V", (void *) android_glMaterialfv__II_3FI },
{"glMaterialfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glMaterialfv__IILjava_nio_FloatBuffer_2 },
{"glMaterialx", "(III)V", (void *) android_glMaterialx__III },
{"glMaterialxv", "(II[II)V", (void *) android_glMaterialxv__II_3II },
{"glMaterialxv", "(IILjava/nio/IntBuffer;)V", (void *) android_glMaterialxv__IILjava_nio_IntBuffer_2 },
{"glMatrixMode", "(I)V", (void *) android_glMatrixMode__I },
{"glMultMatrixf", "([FI)V", (void *) android_glMultMatrixf___3FI },
{"glMultMatrixf", "(Ljava/nio/FloatBuffer;)V", (void *) android_glMultMatrixf__Ljava_nio_FloatBuffer_2 },
{"glMultMatrixx", "([II)V", (void *) android_glMultMatrixx___3II },
{"glMultMatrixx", "(Ljava/nio/IntBuffer;)V", (void *) android_glMultMatrixx__Ljava_nio_IntBuffer_2 },
{"glMultiTexCoord4f", "(IFFFF)V", (void *) android_glMultiTexCoord4f__IFFFF },
{"glMultiTexCoord4x", "(IIIII)V", (void *) android_glMultiTexCoord4x__IIIII },
{"glNormal3f", "(FFF)V", (void *) android_glNormal3f__FFF },
{"glNormal3x", "(III)V", (void *) android_glNormal3x__III },
{"glNormalPointerBounds", "(IILjava/nio/Buffer;I)V", (void *) android_glNormalPointerBounds__IILjava_nio_Buffer_2I },
{"glOrthof", "(FFFFFF)V", (void *) android_glOrthof__FFFFFF },
{"glOrthox", "(IIIIII)V", (void *) android_glOrthox__IIIIII },
{"glPixelStorei", "(II)V", (void *) android_glPixelStorei__II },
{"glPointSize", "(F)V", (void *) android_glPointSize__F },
{"glPointSizex", "(I)V", (void *) android_glPointSizex__I },
{"glPolygonOffset", "(FF)V", (void *) android_glPolygonOffset__FF },
{"glPolygonOffsetx", "(II)V", (void *) android_glPolygonOffsetx__II },
{"glPopMatrix", "()V", (void *) android_glPopMatrix__ },
{"glPushMatrix", "()V", (void *) android_glPushMatrix__ },
{"glReadPixels", "(IIIIIILjava/nio/Buffer;)V", (void *) android_glReadPixels__IIIIIILjava_nio_Buffer_2 },
{"glRotatef", "(FFFF)V", (void *) android_glRotatef__FFFF },
{"glRotatex", "(IIII)V", (void *) android_glRotatex__IIII },
{"glSampleCoverage", "(FZ)V", (void *) android_glSampleCoverage__FZ },
{"glSampleCoveragex", "(IZ)V", (void *) android_glSampleCoveragex__IZ },
{"glScalef", "(FFF)V", (void *) android_glScalef__FFF },
{"glScalex", "(III)V", (void *) android_glScalex__III },
{"glScissor", "(IIII)V", (void *) android_glScissor__IIII },
{"glShadeModel", "(I)V", (void *) android_glShadeModel__I },
{"glStencilFunc", "(III)V", (void *) android_glStencilFunc__III },
{"glStencilMask", "(I)V", (void *) android_glStencilMask__I },
{"glStencilOp", "(III)V", (void *) android_glStencilOp__III },
{"glTexCoordPointerBounds", "(IIILjava/nio/Buffer;I)V", (void *) android_glTexCoordPointerBounds__IIILjava_nio_Buffer_2I },
{"glTexEnvf", "(IIF)V", (void *) android_glTexEnvf__IIF },
{"glTexEnvfv", "(II[FI)V", (void *) android_glTexEnvfv__II_3FI },
{"glTexEnvfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glTexEnvfv__IILjava_nio_FloatBuffer_2 },
{"glTexEnvx", "(III)V", (void *) android_glTexEnvx__III },
{"glTexEnvxv", "(II[II)V", (void *) android_glTexEnvxv__II_3II },
{"glTexEnvxv", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexEnvxv__IILjava_nio_IntBuffer_2 },
{"glTexImage2D", "(IIIIIIIILjava/nio/Buffer;)V", (void *) android_glTexImage2D__IIIIIIIILjava_nio_Buffer_2 },
{"glTexParameterf", "(IIF)V", (void *) android_glTexParameterf__IIF },
{"glTexParameterx", "(III)V", (void *) android_glTexParameterx__III },
{"glTexSubImage2D", "(IIIIIIIILjava/nio/Buffer;)V", (void *) android_glTexSubImage2D__IIIIIIIILjava_nio_Buffer_2 },
{"glTranslatef", "(FFF)V", (void *) android_glTranslatef__FFF },
{"glTranslatex", "(III)V", (void *) android_glTranslatex__III },
{"glVertexPointerBounds", "(IIILjava/nio/Buffer;I)V", (void *) android_glVertexPointerBounds__IIILjava_nio_Buffer_2I },
{"glViewport", "(IIII)V", (void *) android_glViewport__IIII },
};

int register_android_opengl_jni_GLES10(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));
    return err;
}

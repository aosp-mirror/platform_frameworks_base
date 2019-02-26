/*
**
** Copyright 2013, The Android Open Source Project
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

#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>
#include <assert.h>

static jclass nioAccessClass;
static jclass bufferClass;
static jmethodID getBasePointerID;
static jmethodID getBaseArrayID;
static jmethodID getBaseArrayOffsetID;
static jfieldID positionID;
static jfieldID limitID;
static jfieldID elementSizeShiftID;


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

/* Cache method IDs each time the class is loaded. */

static void
nativeClassInit(JNIEnv *_env, jclass glImplClass)
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

static void *
getPointer(JNIEnv *_env, jobject buffer, jarray *array, jint *remaining, jint *offset)
{
    jint position;
    jint limit;
    jint elementSizeShift;
    jlong pointer;

    position = _env->GetIntField(buffer, positionID);
    limit = _env->GetIntField(buffer, limitID);
    elementSizeShift = _env->GetIntField(buffer, elementSizeShiftID);
    *remaining = (limit - position) << elementSizeShift;
    pointer = _env->CallStaticLongMethod(nioAccessClass,
            getBasePointerID, buffer);
    if (pointer != 0L) {
        *array = NULL;
        return reinterpret_cast<void*>(pointer);
    }

    *array = (jarray) _env->CallStaticObjectMethod(nioAccessClass,
            getBaseArrayID, buffer);
    *offset = _env->CallStaticIntMethod(nioAccessClass,
            getBaseArrayOffsetID, buffer);

    return NULL;
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
    char* buf = (char*) _env->GetDirectBufferAddress(buffer);
    if (buf) {
        jint position = _env->GetIntField(buffer, positionID);
        jint elementSizeShift = _env->GetIntField(buffer, elementSizeShiftID);
        buf += position << elementSizeShift;
    } else {
        jniThrowException(_env, "java/lang/IllegalArgumentException",
                          "Must use a native order direct Buffer");
    }
    return (void*) buf;
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
/* void glReadBuffer ( GLenum mode ) */
static void
android_glReadBuffer__I
  (JNIEnv *_env, jobject _this, jint mode) {
    glReadBuffer(
        (GLenum)mode
    );
}

/* void glDrawRangeElements ( GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const GLvoid *indices ) */
static void
android_glDrawRangeElements__IIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint mode, jint start, jint end, jint count, jint type, jobject indices_buf) {
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
    if (indices == NULL) {
        char * _indicesBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        indices = (GLvoid *) (_indicesBase + _bufferOffset);
    }
    glDrawRangeElements(
        (GLenum)mode,
        (GLuint)start,
        (GLuint)end,
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

/* void glDrawRangeElements ( GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, GLsizei offset ) */
static void
android_glDrawRangeElements__IIIIII
  (JNIEnv *_env, jobject _this, jint mode, jint start, jint end, jint count, jint type, jint offset) {
    glDrawRangeElements(
        (GLenum)mode,
        (GLuint)start,
        (GLuint)end,
        (GLsizei)count,
        (GLenum)type,
        reinterpret_cast<GLvoid *>(offset)
    );
}

/* void glTexImage3D ( GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLsizei depth, GLint border, GLenum format, GLenum type, const GLvoid *pixels ) */
static void
android_glTexImage3D__IIIIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint internalformat, jint width, jint height, jint depth, jint border, jint format, jint type, jobject pixels_buf) {
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
    glTexImage3D(
        (GLenum)target,
        (GLint)level,
        (GLint)internalformat,
        (GLsizei)width,
        (GLsizei)height,
        (GLsizei)depth,
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

/* void glTexImage3D ( GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLsizei depth, GLint border, GLenum format, GLenum type, GLsizei offset ) */
static void
android_glTexImage3D__IIIIIIIIII
  (JNIEnv *_env, jobject _this, jint target, jint level, jint internalformat, jint width, jint height, jint depth, jint border, jint format, jint type, jint offset) {
    glTexImage3D(
        (GLenum)target,
        (GLint)level,
        (GLint)internalformat,
        (GLsizei)width,
        (GLsizei)height,
        (GLsizei)depth,
        (GLint)border,
        (GLenum)format,
        (GLenum)type,
        reinterpret_cast<GLvoid *>(offset)
    );
}

/* void glTexSubImage3D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, const GLvoid *pixels ) */
static void
android_glTexSubImage3D__IIIIIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint xoffset, jint yoffset, jint zoffset, jint width, jint height, jint depth, jint format, jint type, jobject pixels_buf) {
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
    glTexSubImage3D(
        (GLenum)target,
        (GLint)level,
        (GLint)xoffset,
        (GLint)yoffset,
        (GLint)zoffset,
        (GLsizei)width,
        (GLsizei)height,
        (GLsizei)depth,
        (GLenum)format,
        (GLenum)type,
        (GLvoid *)pixels
    );

exit:
    if (_array) {
        releasePointer(_env, _array, pixels, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexSubImage3D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, GLsizei offset ) */
static void
android_glTexSubImage3D__IIIIIIIIIII
  (JNIEnv *_env, jobject _this, jint target, jint level, jint xoffset, jint yoffset, jint zoffset, jint width, jint height, jint depth, jint format, jint type, jint offset) {
    glTexSubImage3D(
        (GLenum)target,
        (GLint)level,
        (GLint)xoffset,
        (GLint)yoffset,
        (GLint)zoffset,
        (GLsizei)width,
        (GLsizei)height,
        (GLsizei)depth,
        (GLenum)format,
        (GLenum)type,
        reinterpret_cast<GLvoid *>(offset)
    );
}

/* void glCopyTexSubImage3D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLint x, GLint y, GLsizei width, GLsizei height ) */
static void
android_glCopyTexSubImage3D__IIIIIIIII
  (JNIEnv *_env, jobject _this, jint target, jint level, jint xoffset, jint yoffset, jint zoffset, jint x, jint y, jint width, jint height) {
    glCopyTexSubImage3D(
        (GLenum)target,
        (GLint)level,
        (GLint)xoffset,
        (GLint)yoffset,
        (GLint)zoffset,
        (GLint)x,
        (GLint)y,
        (GLsizei)width,
        (GLsizei)height
    );
}

/* void glCompressedTexImage3D ( GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height, GLsizei depth, GLint border, GLsizei imageSize, const GLvoid *data ) */
static void
android_glCompressedTexImage3D__IIIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint internalformat, jint width, jint height, jint depth, jint border, jint imageSize, jobject data_buf) {
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
    glCompressedTexImage3D(
        (GLenum)target,
        (GLint)level,
        (GLenum)internalformat,
        (GLsizei)width,
        (GLsizei)height,
        (GLsizei)depth,
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

/* void glCompressedTexImage3D ( GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height, GLsizei depth, GLint border, GLsizei imageSize, GLsizei offset ) */
static void
android_glCompressedTexImage3D__IIIIIIIII
  (JNIEnv *_env, jobject _this, jint target, jint level, jint internalformat, jint width, jint height, jint depth, jint border, jint imageSize, jint offset) {
    glCompressedTexImage3D(
        (GLenum)target,
        (GLint)level,
        (GLenum)internalformat,
        (GLsizei)width,
        (GLsizei)height,
        (GLsizei)depth,
        (GLint)border,
        (GLsizei)imageSize,
        reinterpret_cast<GLvoid *>(offset)
    );
}

/* void glCompressedTexSubImage3D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLsizei imageSize, const GLvoid *data ) */
static void
android_glCompressedTexSubImage3D__IIIIIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint xoffset, jint yoffset, jint zoffset, jint width, jint height, jint depth, jint format, jint imageSize, jobject data_buf) {
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
    glCompressedTexSubImage3D(
        (GLenum)target,
        (GLint)level,
        (GLint)xoffset,
        (GLint)yoffset,
        (GLint)zoffset,
        (GLsizei)width,
        (GLsizei)height,
        (GLsizei)depth,
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

/* void glCompressedTexSubImage3D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLsizei imageSize, GLsizei offset ) */
static void
android_glCompressedTexSubImage3D__IIIIIIIIIII
  (JNIEnv *_env, jobject _this, jint target, jint level, jint xoffset, jint yoffset, jint zoffset, jint width, jint height, jint depth, jint format, jint imageSize, jint offset) {
    glCompressedTexSubImage3D(
        (GLenum)target,
        (GLint)level,
        (GLint)xoffset,
        (GLint)yoffset,
        (GLint)zoffset,
        (GLsizei)width,
        (GLsizei)height,
        (GLsizei)depth,
        (GLenum)format,
        (GLsizei)imageSize,
        reinterpret_cast<GLvoid *>(offset)
    );
}

/* void glGenQueries ( GLsizei n, GLuint *ids ) */
static void
android_glGenQueries__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray ids_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *ids_base = (GLuint *) 0;
    jint _remaining;
    GLuint *ids = (GLuint *) 0;

    if (!ids_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "ids == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(ids_ref) - offset;
    ids_base = (GLuint *)
        _env->GetIntArrayElements(ids_ref, (jboolean *)0);
    ids = ids_base + offset;

    glGenQueries(
        (GLsizei)n,
        (GLuint *)ids
    );

exit:
    if (ids_base) {
        _env->ReleaseIntArrayElements(ids_ref, (jint*)ids_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenQueries ( GLsizei n, GLuint *ids ) */
static void
android_glGenQueries__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject ids_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *ids = (GLuint *) 0;

    if (!ids_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "ids == null";
        goto exit;
    }
    ids = (GLuint *)getPointer(_env, ids_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (ids == NULL) {
        char * _idsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        ids = (GLuint *) (_idsBase + _bufferOffset);
    }
    glGenQueries(
        (GLsizei)n,
        (GLuint *)ids
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)ids, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteQueries ( GLsizei n, const GLuint *ids ) */
static void
android_glDeleteQueries__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray ids_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *ids_base = (GLuint *) 0;
    jint _remaining;
    GLuint *ids = (GLuint *) 0;

    if (!ids_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "ids == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(ids_ref) - offset;
    ids_base = (GLuint *)
        _env->GetIntArrayElements(ids_ref, (jboolean *)0);
    ids = ids_base + offset;

    glDeleteQueries(
        (GLsizei)n,
        (GLuint *)ids
    );

exit:
    if (ids_base) {
        _env->ReleaseIntArrayElements(ids_ref, (jint*)ids_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteQueries ( GLsizei n, const GLuint *ids ) */
static void
android_glDeleteQueries__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject ids_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *ids = (GLuint *) 0;

    if (!ids_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "ids == null";
        goto exit;
    }
    ids = (GLuint *)getPointer(_env, ids_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (ids == NULL) {
        char * _idsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        ids = (GLuint *) (_idsBase + _bufferOffset);
    }
    glDeleteQueries(
        (GLsizei)n,
        (GLuint *)ids
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)ids, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLboolean glIsQuery ( GLuint id ) */
static jboolean
android_glIsQuery__I
  (JNIEnv *_env, jobject _this, jint id) {
    GLboolean _returnValue;
    _returnValue = glIsQuery(
        (GLuint)id
    );
    return (jboolean)_returnValue;
}

/* void glBeginQuery ( GLenum target, GLuint id ) */
static void
android_glBeginQuery__II
  (JNIEnv *_env, jobject _this, jint target, jint id) {
    glBeginQuery(
        (GLenum)target,
        (GLuint)id
    );
}

/* void glEndQuery ( GLenum target ) */
static void
android_glEndQuery__I
  (JNIEnv *_env, jobject _this, jint target) {
    glEndQuery(
        (GLenum)target
    );
}

/* void glGetQueryiv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetQueryiv__II_3II
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
    params_base = (GLint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetQueryiv(
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

/* void glGetQueryiv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetQueryiv__IILjava_nio_IntBuffer_2
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetQueryiv(
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

/* void glGetQueryObjectuiv ( GLuint id, GLenum pname, GLuint *params ) */
static void
android_glGetQueryObjectuiv__II_3II
  (JNIEnv *_env, jobject _this, jint id, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *params_base = (GLuint *) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

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
    params_base = (GLuint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetQueryObjectuiv(
        (GLuint)id,
        (GLenum)pname,
        (GLuint *)params
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

/* void glGetQueryObjectuiv ( GLuint id, GLenum pname, GLuint *params ) */
static void
android_glGetQueryObjectuiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint id, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLuint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLuint *) (_paramsBase + _bufferOffset);
    }
    glGetQueryObjectuiv(
        (GLuint)id,
        (GLenum)pname,
        (GLuint *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLboolean glUnmapBuffer ( GLenum target ) */
static jboolean
android_glUnmapBuffer__I
  (JNIEnv *_env, jobject _this, jint target) {
    GLboolean _returnValue;
    _returnValue = glUnmapBuffer(
        (GLenum)target
    );
    return (jboolean)_returnValue;
}

/* void glGetBufferPointerv ( GLenum target, GLenum pname, GLvoid** params ) */
static jobject
android_glGetBufferPointerv__II
  (JNIEnv *_env, jobject _this, jint target, jint pname) {
    GLint64 _mapLength;
    GLvoid* _p;
    glGetBufferParameteri64v((GLenum)target, GL_BUFFER_MAP_LENGTH, &_mapLength);
    glGetBufferPointerv((GLenum)target, (GLenum)pname, &_p);
    return _env->NewDirectByteBuffer(_p, _mapLength);
}

/* void glDrawBuffers ( GLsizei n, const GLenum *bufs ) */
static void
android_glDrawBuffers__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray bufs_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLenum *bufs_base = (GLenum *) 0;
    jint _remaining;
    GLenum *bufs = (GLenum *) 0;

    if (!bufs_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "bufs == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(bufs_ref) - offset;
    bufs_base = (GLenum *)
        _env->GetIntArrayElements(bufs_ref, (jboolean *)0);
    bufs = bufs_base + offset;

    glDrawBuffers(
        (GLsizei)n,
        (GLenum *)bufs
    );

exit:
    if (bufs_base) {
        _env->ReleaseIntArrayElements(bufs_ref, (jint*)bufs_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDrawBuffers ( GLsizei n, const GLenum *bufs ) */
static void
android_glDrawBuffers__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject bufs_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLenum *bufs = (GLenum *) 0;

    if (!bufs_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "bufs == null";
        goto exit;
    }
    bufs = (GLenum *)getPointer(_env, bufs_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (bufs == NULL) {
        char * _bufsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        bufs = (GLenum *) (_bufsBase + _bufferOffset);
    }
    glDrawBuffers(
        (GLsizei)n,
        (GLenum *)bufs
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)bufs, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix2x3fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix2x3fv__IIZ_3FI
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *value_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLfloat *)
        _env->GetFloatArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniformMatrix2x3fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (value_base) {
        _env->ReleaseFloatArrayElements(value_ref, (jfloat*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix2x3fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix2x3fv__IIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLfloat *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        value = (GLfloat *) (_valueBase + _bufferOffset);
    }
    glUniformMatrix2x3fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix3x2fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix3x2fv__IIZ_3FI
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *value_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLfloat *)
        _env->GetFloatArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniformMatrix3x2fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (value_base) {
        _env->ReleaseFloatArrayElements(value_ref, (jfloat*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix3x2fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix3x2fv__IIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLfloat *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        value = (GLfloat *) (_valueBase + _bufferOffset);
    }
    glUniformMatrix3x2fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix2x4fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix2x4fv__IIZ_3FI
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *value_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLfloat *)
        _env->GetFloatArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniformMatrix2x4fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (value_base) {
        _env->ReleaseFloatArrayElements(value_ref, (jfloat*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix2x4fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix2x4fv__IIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLfloat *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        value = (GLfloat *) (_valueBase + _bufferOffset);
    }
    glUniformMatrix2x4fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix4x2fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix4x2fv__IIZ_3FI
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *value_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLfloat *)
        _env->GetFloatArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniformMatrix4x2fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (value_base) {
        _env->ReleaseFloatArrayElements(value_ref, (jfloat*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix4x2fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix4x2fv__IIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLfloat *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        value = (GLfloat *) (_valueBase + _bufferOffset);
    }
    glUniformMatrix4x2fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix3x4fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix3x4fv__IIZ_3FI
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *value_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLfloat *)
        _env->GetFloatArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniformMatrix3x4fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (value_base) {
        _env->ReleaseFloatArrayElements(value_ref, (jfloat*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix3x4fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix3x4fv__IIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLfloat *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        value = (GLfloat *) (_valueBase + _bufferOffset);
    }
    glUniformMatrix3x4fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix4x3fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix4x3fv__IIZ_3FI
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *value_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLfloat *)
        _env->GetFloatArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniformMatrix4x3fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (value_base) {
        _env->ReleaseFloatArrayElements(value_ref, (jfloat*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix4x3fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix4x3fv__IIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLfloat *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        value = (GLfloat *) (_valueBase + _bufferOffset);
    }
    glUniformMatrix4x3fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glBlitFramebuffer ( GLint srcX0, GLint srcY0, GLint srcX1, GLint srcY1, GLint dstX0, GLint dstY0, GLint dstX1, GLint dstY1, GLbitfield mask, GLenum filter ) */
static void
android_glBlitFramebuffer__IIIIIIIIII
  (JNIEnv *_env, jobject _this, jint srcX0, jint srcY0, jint srcX1, jint srcY1, jint dstX0, jint dstY0, jint dstX1, jint dstY1, jint mask, jint filter) {
    glBlitFramebuffer(
        (GLint)srcX0,
        (GLint)srcY0,
        (GLint)srcX1,
        (GLint)srcY1,
        (GLint)dstX0,
        (GLint)dstY0,
        (GLint)dstX1,
        (GLint)dstY1,
        (GLbitfield)mask,
        (GLenum)filter
    );
}

/* void glRenderbufferStorageMultisample ( GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height ) */
static void
android_glRenderbufferStorageMultisample__IIIII
  (JNIEnv *_env, jobject _this, jint target, jint samples, jint internalformat, jint width, jint height) {
    glRenderbufferStorageMultisample(
        (GLenum)target,
        (GLsizei)samples,
        (GLenum)internalformat,
        (GLsizei)width,
        (GLsizei)height
    );
}

/* void glFramebufferTextureLayer ( GLenum target, GLenum attachment, GLuint texture, GLint level, GLint layer ) */
static void
android_glFramebufferTextureLayer__IIIII
  (JNIEnv *_env, jobject _this, jint target, jint attachment, jint texture, jint level, jint layer) {
    glFramebufferTextureLayer(
        (GLenum)target,
        (GLenum)attachment,
        (GLuint)texture,
        (GLint)level,
        (GLint)layer
    );
}

/* GLvoid * glMapBufferRange ( GLenum target, GLintptr offset, GLsizeiptr length, GLbitfield access ) */
static jobject
android_glMapBufferRange__IIII
  (JNIEnv *_env, jobject _this, jint target, jint offset, jint length, jint access) {
    GLvoid* _p = glMapBufferRange((GLenum)target,
            (GLintptr)offset, (GLsizeiptr)length, (GLbitfield)access);
    jobject _buf = (jobject)0;
    if (_p) {
        _buf = _env->NewDirectByteBuffer(_p, length);
    }
    return _buf;
}

/* void glFlushMappedBufferRange ( GLenum target, GLintptr offset, GLsizeiptr length ) */
static void
android_glFlushMappedBufferRange__III
  (JNIEnv *_env, jobject _this, jint target, jint offset, jint length) {
    glFlushMappedBufferRange(
        (GLenum)target,
        (GLintptr)offset,
        (GLsizeiptr)length
    );
}

/* void glBindVertexArray ( GLuint array ) */
static void
android_glBindVertexArray__I
  (JNIEnv *_env, jobject _this, jint array) {
    glBindVertexArray(
        (GLuint)array
    );
}

/* void glDeleteVertexArrays ( GLsizei n, const GLuint *arrays ) */
static void
android_glDeleteVertexArrays__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray arrays_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *arrays_base = (GLuint *) 0;
    jint _remaining;
    GLuint *arrays = (GLuint *) 0;

    if (!arrays_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "arrays == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(arrays_ref) - offset;
    arrays_base = (GLuint *)
        _env->GetIntArrayElements(arrays_ref, (jboolean *)0);
    arrays = arrays_base + offset;

    glDeleteVertexArrays(
        (GLsizei)n,
        (GLuint *)arrays
    );

exit:
    if (arrays_base) {
        _env->ReleaseIntArrayElements(arrays_ref, (jint*)arrays_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteVertexArrays ( GLsizei n, const GLuint *arrays ) */
static void
android_glDeleteVertexArrays__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject arrays_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *arrays = (GLuint *) 0;

    if (!arrays_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "arrays == null";
        goto exit;
    }
    arrays = (GLuint *)getPointer(_env, arrays_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (arrays == NULL) {
        char * _arraysBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        arrays = (GLuint *) (_arraysBase + _bufferOffset);
    }
    glDeleteVertexArrays(
        (GLsizei)n,
        (GLuint *)arrays
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)arrays, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenVertexArrays ( GLsizei n, GLuint *arrays ) */
static void
android_glGenVertexArrays__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray arrays_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *arrays_base = (GLuint *) 0;
    jint _remaining;
    GLuint *arrays = (GLuint *) 0;

    if (!arrays_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "arrays == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(arrays_ref) - offset;
    arrays_base = (GLuint *)
        _env->GetIntArrayElements(arrays_ref, (jboolean *)0);
    arrays = arrays_base + offset;

    glGenVertexArrays(
        (GLsizei)n,
        (GLuint *)arrays
    );

exit:
    if (arrays_base) {
        _env->ReleaseIntArrayElements(arrays_ref, (jint*)arrays_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenVertexArrays ( GLsizei n, GLuint *arrays ) */
static void
android_glGenVertexArrays__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject arrays_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *arrays = (GLuint *) 0;

    if (!arrays_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "arrays == null";
        goto exit;
    }
    arrays = (GLuint *)getPointer(_env, arrays_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (arrays == NULL) {
        char * _arraysBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        arrays = (GLuint *) (_arraysBase + _bufferOffset);
    }
    glGenVertexArrays(
        (GLsizei)n,
        (GLuint *)arrays
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)arrays, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLboolean glIsVertexArray ( GLuint array ) */
static jboolean
android_glIsVertexArray__I
  (JNIEnv *_env, jobject _this, jint array) {
    GLboolean _returnValue;
    _returnValue = glIsVertexArray(
        (GLuint)array
    );
    return (jboolean)_returnValue;
}

/* void glGetIntegeri_v ( GLenum target, GLuint index, GLint *data ) */
static void
android_glGetIntegeri_v__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint index, jintArray data_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint *data_base = (GLint *) 0;
    jint _remaining;
    GLint *data = (GLint *) 0;

    if (!data_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "data == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(data_ref) - offset;
    data_base = (GLint *)
        _env->GetIntArrayElements(data_ref, (jboolean *)0);
    data = data_base + offset;

    glGetIntegeri_v(
        (GLenum)target,
        (GLuint)index,
        (GLint *)data
    );

exit:
    if (data_base) {
        _env->ReleaseIntArrayElements(data_ref, (jint*)data_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetIntegeri_v ( GLenum target, GLuint index, GLint *data ) */
static void
android_glGetIntegeri_v__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint index, jobject data_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *data = (GLint *) 0;

    if (!data_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "data == null";
        goto exit;
    }
    data = (GLint *)getPointer(_env, data_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (data == NULL) {
        char * _dataBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        data = (GLint *) (_dataBase + _bufferOffset);
    }
    glGetIntegeri_v(
        (GLenum)target,
        (GLuint)index,
        (GLint *)data
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)data, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glBeginTransformFeedback ( GLenum primitiveMode ) */
static void
android_glBeginTransformFeedback__I
  (JNIEnv *_env, jobject _this, jint primitiveMode) {
    glBeginTransformFeedback(
        (GLenum)primitiveMode
    );
}

/* void glEndTransformFeedback ( void ) */
static void
android_glEndTransformFeedback__
  (JNIEnv *_env, jobject _this) {
    glEndTransformFeedback();
}

/* void glBindBufferRange ( GLenum target, GLuint index, GLuint buffer, GLintptr offset, GLsizeiptr size ) */
static void
android_glBindBufferRange__IIIII
  (JNIEnv *_env, jobject _this, jint target, jint index, jint buffer, jint offset, jint size) {
    glBindBufferRange(
        (GLenum)target,
        (GLuint)index,
        (GLuint)buffer,
        (GLintptr)offset,
        (GLsizeiptr)size
    );
}

/* void glBindBufferBase ( GLenum target, GLuint index, GLuint buffer ) */
static void
android_glBindBufferBase__III
  (JNIEnv *_env, jobject _this, jint target, jint index, jint buffer) {
    glBindBufferBase(
        (GLenum)target,
        (GLuint)index,
        (GLuint)buffer
    );
}

/* void glTransformFeedbackVaryings ( GLuint program, GLsizei count, const GLchar *varyings, GLenum bufferMode ) */
static
void
android_glTransformFeedbackVaryings
    (JNIEnv *_env, jobject _this, jint program, jobjectArray varyings_ref, jint bufferMode) {
    jint _exception = 0;
    const char* _exceptionType = NULL;
    const char* _exceptionMessage = NULL;
    jint _count = 0, _i;
    const char** _varyings = NULL;
    const char* _varying = NULL;

    if (!varyings_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "varyings == null";
        goto exit;
    }

    _count = _env->GetArrayLength(varyings_ref);
    _varyings = (const char**)calloc(_count, sizeof(const char*));
    for (_i = 0; _i < _count; _i++) {
        jstring _varying = (jstring)_env->GetObjectArrayElement(varyings_ref, _i);
        if (!_varying) {
            _exception = 1;
            _exceptionType = "java/lang/IllegalArgumentException";
            _exceptionMessage = "null varyings element";
            goto exit;
        }
        _varyings[_i] = _env->GetStringUTFChars(_varying, 0);
    }

    glTransformFeedbackVaryings(program, _count, _varyings, bufferMode);

exit:
    for (_i = _count - 1; _i >= 0; _i--) {
        if (_varyings[_i]) {
            jstring _varying = (jstring)_env->GetObjectArrayElement(varyings_ref, _i);
            if (_varying) {
                _env->ReleaseStringUTFChars(_varying, _varyings[_i]);
            }
        }
    }
    free(_varyings);
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTransformFeedbackVarying ( GLuint program, GLuint index, GLsizei bufSize, GLsizei *length, GLint *size, GLenum *type, GLchar *name ) */
static void
android_glGetTransformFeedbackVarying__III_3II_3II_3II_3BI
  (JNIEnv *_env, jobject _this, jint program, jint index, jint bufsize, jintArray length_ref, jint lengthOffset, jintArray size_ref, jint sizeOffset, jintArray type_ref, jint typeOffset, jbyteArray name_ref, jint nameOffset) {
    jint _exception = 0;
    const char * _exceptionType;
    const char * _exceptionMessage;
    GLsizei *length_base = (GLsizei *) 0;
    jint _lengthRemaining;
    GLsizei *length = (GLsizei *) 0;
    GLint *size_base = (GLint *) 0;
    jint _sizeRemaining;
    GLint *size = (GLint *) 0;
    GLenum *type_base = (GLenum *) 0;
    jint _typeRemaining;
    GLenum *type = (GLenum *) 0;
    char *name_base = (char *) 0;
    jint _nameRemaining;
    char *name = (char *) 0;

    if (length_ref) {
        if (lengthOffset < 0) {
            _exception = 1;
            _exceptionType = "java/lang/IllegalArgumentException";
            _exceptionMessage = "lengthOffset < 0";
            goto exit;
        }
        _lengthRemaining = _env->GetArrayLength(length_ref) - lengthOffset;
        length_base = (GLsizei *)
            _env->GetIntArrayElements(length_ref, (jboolean *)0);
        length = length_base + lengthOffset;
    }

    if (!size_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "size == null";
        goto exit;
    }
    if (sizeOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "sizeOffset < 0";
        goto exit;
    }
    _sizeRemaining = _env->GetArrayLength(size_ref) - sizeOffset;
    size_base = (GLint *)
        _env->GetIntArrayElements(size_ref, (jboolean *)0);
    size = size_base + sizeOffset;

    if (!type_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "type == null";
        goto exit;
    }
    if (typeOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "typeOffset < 0";
        goto exit;
    }
    _typeRemaining = _env->GetArrayLength(type_ref) - typeOffset;
    type_base = (GLenum *)
        _env->GetIntArrayElements(type_ref, (jboolean *)0);
    type = type_base + typeOffset;

    if (!name_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "name == null";
        goto exit;
    }
    if (nameOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "nameOffset < 0";
        goto exit;
    }
    _nameRemaining = _env->GetArrayLength(name_ref) - nameOffset;
    name_base = (char *)
        _env->GetByteArrayElements(name_ref, (jboolean *)0);
    name = name_base + nameOffset;

    glGetTransformFeedbackVarying(
        (GLuint)program,
        (GLuint)index,
        (GLsizei)bufsize,
        (GLsizei *)length,
        (GLint *)size,
        (GLenum *)type,
        (char *)name
    );

exit:
    if (name_base) {
        _env->ReleaseByteArrayElements(name_ref, (jbyte*)name_base,
            _exception ? JNI_ABORT: 0);
    }
    if (type_base) {
        _env->ReleaseIntArrayElements(type_ref, (jint*)type_base,
            _exception ? JNI_ABORT: 0);
    }
    if (size_base) {
        _env->ReleaseIntArrayElements(size_ref, (jint*)size_base,
            _exception ? JNI_ABORT: 0);
    }
    if (length_base) {
        _env->ReleaseIntArrayElements(length_ref, (jint*)length_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTransformFeedbackVarying ( GLuint program, GLuint index, GLsizei bufSize, GLsizei *length, GLint *size, GLenum *type, GLchar *name ) */
static void
android_glGetTransformFeedbackVarying__IIILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2B
  (JNIEnv *_env, jobject _this, jint program, jint index, jint bufsize, jobject length_buf, jobject size_buf, jobject type_buf, jbyte name) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "deprecated");
}

/* void glGetTransformFeedbackVarying ( GLuint program, GLuint index, GLsizei bufSize, GLsizei *length, GLint *size, GLenum *type, GLchar *name ) */
static void
android_glGetTransformFeedbackVarying__IIILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_ByteBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint index, jint bufsize, jobject length_buf, jobject size_buf, jobject type_buf, jobject name_buf) {
    jintArray _lengthArray = (jintArray) 0;
    jint _lengthBufferOffset = (jint) 0;
    jintArray _sizeArray = (jintArray) 0;
    jint _sizeBufferOffset = (jint) 0;
    jintArray _typeArray = (jintArray) 0;
    jint _typeBufferOffset = (jint) 0;
    jbyteArray _nameArray = (jbyteArray)0;
    jint _nameBufferOffset = (jint)0;
    jint _lengthRemaining;
    GLsizei *length = (GLsizei *) 0;
    jint _sizeRemaining;
    GLint *size = (GLint *) 0;
    jint _typeRemaining;
    GLenum *type = (GLenum *) 0;
    jint _nameRemaining;
    GLchar* name = (GLchar*)0;


    length = (GLsizei *)getPointer(_env, length_buf, (jarray*)&_lengthArray, &_lengthRemaining, &_lengthBufferOffset);
    size = (GLint *)getPointer(_env, size_buf, (jarray*)&_sizeArray, &_sizeRemaining, &_sizeBufferOffset);
    type = (GLenum *)getPointer(_env, type_buf, (jarray*)&_typeArray, &_typeRemaining, &_typeBufferOffset);
    name = (GLchar*)getPointer(_env, name_buf, (jarray*)&_nameArray, &_nameRemaining, &_nameBufferOffset);
    if (length == NULL) {
        char * _lengthBase = (char *)_env->GetIntArrayElements(_lengthArray, (jboolean *) 0);
        length = (GLsizei *) (_lengthBase + _lengthBufferOffset);
    }
    if (size == NULL) {
        char * _sizeBase = (char *)_env->GetIntArrayElements(_sizeArray, (jboolean *) 0);
        size = (GLint *) (_sizeBase + _sizeBufferOffset);
    }
    if (type == NULL) {
        char * _typeBase = (char *)_env->GetIntArrayElements(_typeArray, (jboolean *) 0);
        type = (GLenum *) (_typeBase + _typeBufferOffset);
    }
    if (name == NULL) {
        char* _nameBase = (char *)_env->GetByteArrayElements(_nameArray, (jboolean*)0);
        name = (GLchar *) (_nameBase + _nameBufferOffset);
    }
    glGetTransformFeedbackVarying(
        (GLuint)program,
        (GLuint)index,
        (GLsizei)bufsize,
        (GLsizei *)length,
        (GLint *)size,
        (GLenum *)type,
        (GLchar*)name
    );
    if (_typeArray) {
        releaseArrayPointer<jintArray, jint*, IntArrayReleaser>(_env, _typeArray, (jint*)type, JNI_TRUE);
    }
    if (_sizeArray) {
        releaseArrayPointer<jintArray, jint*, IntArrayReleaser>(_env, _sizeArray, (jint*)size, JNI_TRUE);
    }
    if (_lengthArray) {
        releaseArrayPointer<jintArray, jint*, IntArrayReleaser>(_env, _lengthArray, (jint*)length, JNI_TRUE);
    }
    if (_nameArray) {
        releaseArrayPointer<jbyteArray, jbyte*, ByteArrayReleaser>(_env, _nameArray, (jbyte*)name, JNI_TRUE);
    }
}

/* void glGetTransformFeedbackVarying ( GLuint program, GLuint index, GLsizei bufSize, GLsizei *length, GLint *size, GLenum *type, GLchar *name ) */
static jstring
android_glGetTransformFeedbackVarying1
  (JNIEnv *_env, jobject _this, jint program, jint index, jintArray size_ref, jint sizeOffset, jintArray type_ref, jint typeOffset) {
    jint _exception = 0;
    const char * _exceptionType;
    const char * _exceptionMessage;
    GLint *size_base = (GLint *) 0;
    jint _sizeRemaining;
    GLint *size = (GLint *) 0;
    GLenum *type_base = (GLenum *) 0;
    jint _typeRemaining;
    GLenum *type = (GLenum *) 0;

    jstring result = 0;

    GLint len = 0;
    glGetProgramiv((GLuint)program, GL_ACTIVE_ATTRIBUTE_MAX_LENGTH, &len);
    if (!len) {
        return _env->NewStringUTF("");
    }
    char* buf = (char*) malloc(len);

    if (buf == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "out of memory");
        return NULL;
    }
    if (!size_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "size == null";
        goto exit;
    }
    if (sizeOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "sizeOffset < 0";
        goto exit;
    }
    _sizeRemaining = _env->GetArrayLength(size_ref) - sizeOffset;
    size_base = (GLint *)
        _env->GetIntArrayElements(size_ref, (jboolean *)0);
    size = size_base + sizeOffset;

    if (!type_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "type == null";
        goto exit;
    }
    if (typeOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "typeOffset < 0";
        goto exit;
    }
    _typeRemaining = _env->GetArrayLength(type_ref) - typeOffset;
    type_base = (GLenum *)
        _env->GetIntArrayElements(type_ref, (jboolean *)0);
    type = type_base + typeOffset;

    glGetTransformFeedbackVarying(
        (GLuint)program,
        (GLuint)index,
        (GLsizei)len,
        NULL,
        (GLint *)size,
        (GLenum *)type,
        (char *)buf
    );
exit:
    if (type_base) {
        _env->ReleaseIntArrayElements(type_ref, (jint*)type_base,
            _exception ? JNI_ABORT: 0);
    }
    if (size_base) {
        _env->ReleaseIntArrayElements(size_ref, (jint*)size_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception != 1) {
        result = _env->NewStringUTF(buf);
    }
    if (buf) {
        free(buf);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    if (result == 0) {
        result = _env->NewStringUTF("");
    }

    return result;
}

/* void glGetTransformFeedbackVarying ( GLuint program, GLuint index, GLsizei bufSize, GLsizei *length, GLint *size, GLenum *type, GLchar *name ) */
static jstring
android_glGetTransformFeedbackVarying2
  (JNIEnv *_env, jobject _this, jint program, jint index, jobject size_buf, jobject type_buf) {
    jintArray _sizeArray = (jintArray) 0;
    jint _sizeBufferOffset = (jint) 0;
    jintArray _typeArray = (jintArray) 0;
    jint _typeBufferOffset = (jint) 0;
    jint _lengthRemaining;
    GLsizei *length = (GLsizei *) 0;
    jint _sizeRemaining;
    GLint *size = (GLint *) 0;
    jint _typeRemaining;
    GLenum *type = (GLenum *) 0;

    jstring result = 0;

    GLint len = 0;
    glGetProgramiv((GLuint)program, GL_ACTIVE_ATTRIBUTE_MAX_LENGTH, &len);
    if (!len) {
        return _env->NewStringUTF("");
    }
    char* buf = (char*) malloc(len);

    if (buf == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "out of memory");
        return NULL;
    }

    size = (GLint *)getPointer(_env, size_buf, (jarray*)&_sizeArray, &_sizeRemaining, &_sizeBufferOffset);
    type = (GLenum *)getPointer(_env, type_buf, (jarray*)&_typeArray, &_typeRemaining, &_typeBufferOffset);
    if (size == NULL) {
        char * _sizeBase = (char *)_env->GetIntArrayElements(_sizeArray, (jboolean *) 0);
        size = (GLint *) (_sizeBase + _sizeBufferOffset);
    }
    if (type == NULL) {
        char * _typeBase = (char *)_env->GetIntArrayElements(_typeArray, (jboolean *) 0);
        type = (GLenum *) (_typeBase + _typeBufferOffset);
    }
    glGetTransformFeedbackVarying(
        (GLuint)program,
        (GLuint)index,
        (GLsizei)len,
        NULL,
        (GLint *)size,
        (GLenum *)type,
        (char *)buf
    );

    if (_typeArray) {
        releaseArrayPointer<jintArray, jint*, IntArrayReleaser>(_env, _typeArray, (jint*)type, JNI_TRUE);
    }
    if (_sizeArray) {
        releaseArrayPointer<jintArray, jint*, IntArrayReleaser>(_env, _sizeArray, (jint*)size, JNI_TRUE);
    }
    result = _env->NewStringUTF(buf);
    if (buf) {
        free(buf);
    }
    return result;
}
/* void glVertexAttribIPointer ( GLuint index, GLint size, GLenum type, GLsizei stride, const GLvoid *pointer ) */
static void
android_glVertexAttribIPointerBounds__IIIILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint index, jint size, jint type, jint stride, jobject pointer_buf, jint remaining) {
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
    glVertexAttribIPointerBounds(
        (GLuint)index,
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

/* void glVertexAttribIPointer ( GLuint index, GLint size, GLenum type, GLsizei stride, GLsizei offset ) */
static void
android_glVertexAttribIPointer__IIIII
  (JNIEnv *_env, jobject _this, jint index, jint size, jint type, jint stride, jint offset) {
    glVertexAttribIPointer(
        (GLuint)index,
        (GLint)size,
        (GLenum)type,
        (GLsizei)stride,
        reinterpret_cast<GLvoid *>(offset)
    );
}

/* void glGetVertexAttribIiv ( GLuint index, GLenum pname, GLint *params ) */
static void
android_glGetVertexAttribIiv__II_3II
  (JNIEnv *_env, jobject _this, jint index, jint pname, jintArray params_ref, jint offset) {
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
    params_base = (GLint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetVertexAttribIiv(
        (GLuint)index,
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

/* void glGetVertexAttribIiv ( GLuint index, GLenum pname, GLint *params ) */
static void
android_glGetVertexAttribIiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint index, jint pname, jobject params_buf) {
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetVertexAttribIiv(
        (GLuint)index,
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

/* void glGetVertexAttribIuiv ( GLuint index, GLenum pname, GLuint *params ) */
static void
android_glGetVertexAttribIuiv__II_3II
  (JNIEnv *_env, jobject _this, jint index, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *params_base = (GLuint *) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

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
    params_base = (GLuint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetVertexAttribIuiv(
        (GLuint)index,
        (GLenum)pname,
        (GLuint *)params
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

/* void glGetVertexAttribIuiv ( GLuint index, GLenum pname, GLuint *params ) */
static void
android_glGetVertexAttribIuiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint index, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLuint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLuint *) (_paramsBase + _bufferOffset);
    }
    glGetVertexAttribIuiv(
        (GLuint)index,
        (GLenum)pname,
        (GLuint *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexAttribI4i ( GLuint index, GLint x, GLint y, GLint z, GLint w ) */
static void
android_glVertexAttribI4i__IIIII
  (JNIEnv *_env, jobject _this, jint index, jint x, jint y, jint z, jint w) {
    glVertexAttribI4i(
        (GLuint)index,
        (GLint)x,
        (GLint)y,
        (GLint)z,
        (GLint)w
    );
}

/* void glVertexAttribI4ui ( GLuint index, GLuint x, GLuint y, GLuint z, GLuint w ) */
static void
android_glVertexAttribI4ui__IIIII
  (JNIEnv *_env, jobject _this, jint index, jint x, jint y, jint z, jint w) {
    glVertexAttribI4ui(
        (GLuint)index,
        (GLuint)x,
        (GLuint)y,
        (GLuint)z,
        (GLuint)w
    );
}

/* void glVertexAttribI4iv ( GLuint index, const GLint *v ) */
static void
android_glVertexAttribI4iv__I_3II
  (JNIEnv *_env, jobject _this, jint index, jintArray v_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint *v_base = (GLint *) 0;
    jint _remaining;
    GLint *v = (GLint *) 0;

    if (!v_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "v == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(v_ref) - offset;
    v_base = (GLint *)
        _env->GetIntArrayElements(v_ref, (jboolean *)0);
    v = v_base + offset;

    glVertexAttribI4iv(
        (GLuint)index,
        (GLint *)v
    );

exit:
    if (v_base) {
        _env->ReleaseIntArrayElements(v_ref, (jint*)v_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexAttribI4iv ( GLuint index, const GLint *v ) */
static void
android_glVertexAttribI4iv__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint index, jobject v_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *v = (GLint *) 0;

    if (!v_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "v == null";
        goto exit;
    }
    v = (GLint *)getPointer(_env, v_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (v == NULL) {
        char * _vBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        v = (GLint *) (_vBase + _bufferOffset);
    }
    glVertexAttribI4iv(
        (GLuint)index,
        (GLint *)v
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)v, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexAttribI4uiv ( GLuint index, const GLuint *v ) */
static void
android_glVertexAttribI4uiv__I_3II
  (JNIEnv *_env, jobject _this, jint index, jintArray v_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *v_base = (GLuint *) 0;
    jint _remaining;
    GLuint *v = (GLuint *) 0;

    if (!v_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "v == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(v_ref) - offset;
    v_base = (GLuint *)
        _env->GetIntArrayElements(v_ref, (jboolean *)0);
    v = v_base + offset;

    glVertexAttribI4uiv(
        (GLuint)index,
        (GLuint *)v
    );

exit:
    if (v_base) {
        _env->ReleaseIntArrayElements(v_ref, (jint*)v_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexAttribI4uiv ( GLuint index, const GLuint *v ) */
static void
android_glVertexAttribI4uiv__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint index, jobject v_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *v = (GLuint *) 0;

    if (!v_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "v == null";
        goto exit;
    }
    v = (GLuint *)getPointer(_env, v_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (v == NULL) {
        char * _vBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        v = (GLuint *) (_vBase + _bufferOffset);
    }
    glVertexAttribI4uiv(
        (GLuint)index,
        (GLuint *)v
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)v, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetUniformuiv ( GLuint program, GLint location, GLuint *params ) */
static void
android_glGetUniformuiv__II_3II
  (JNIEnv *_env, jobject _this, jint program, jint location, jintArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *params_base = (GLuint *) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

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
    params_base = (GLuint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetUniformuiv(
        (GLuint)program,
        (GLint)location,
        (GLuint *)params
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

/* void glGetUniformuiv ( GLuint program, GLint location, GLuint *params ) */
static void
android_glGetUniformuiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLuint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLuint *) (_paramsBase + _bufferOffset);
    }
    glGetUniformuiv(
        (GLuint)program,
        (GLint)location,
        (GLuint *)params
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLint glGetFragDataLocation ( GLuint program, const GLchar *name ) */
static jint
android_glGetFragDataLocation__ILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint program, jstring name) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint _returnValue = 0;
    const char* _nativename = 0;

    if (!name) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "name == null";
        goto exit;
    }
    _nativename = _env->GetStringUTFChars(name, 0);

    _returnValue = glGetFragDataLocation(
        (GLuint)program,
        (GLchar *)_nativename
    );

exit:
    if (_nativename) {
        _env->ReleaseStringUTFChars(name, _nativename);
    }

    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
        return (jint)0;
    }
    return (jint)_returnValue;
}

/* void glUniform1ui ( GLint location, GLuint v0 ) */
static void
android_glUniform1ui__II
  (JNIEnv *_env, jobject _this, jint location, jint v0) {
    glUniform1ui(
        (GLint)location,
        (GLuint)v0
    );
}

/* void glUniform2ui ( GLint location, GLuint v0, GLuint v1 ) */
static void
android_glUniform2ui__III
  (JNIEnv *_env, jobject _this, jint location, jint v0, jint v1) {
    glUniform2ui(
        (GLint)location,
        (GLuint)v0,
        (GLuint)v1
    );
}

/* void glUniform3ui ( GLint location, GLuint v0, GLuint v1, GLuint v2 ) */
static void
android_glUniform3ui__IIII
  (JNIEnv *_env, jobject _this, jint location, jint v0, jint v1, jint v2) {
    glUniform3ui(
        (GLint)location,
        (GLuint)v0,
        (GLuint)v1,
        (GLuint)v2
    );
}

/* void glUniform4ui ( GLint location, GLuint v0, GLuint v1, GLuint v2, GLuint v3 ) */
static void
android_glUniform4ui__IIIII
  (JNIEnv *_env, jobject _this, jint location, jint v0, jint v1, jint v2, jint v3) {
    glUniform4ui(
        (GLint)location,
        (GLuint)v0,
        (GLuint)v1,
        (GLuint)v2,
        (GLuint)v3
    );
}

/* void glUniform1uiv ( GLint location, GLsizei count, const GLuint *value ) */
static void
android_glUniform1uiv__II_3II
  (JNIEnv *_env, jobject _this, jint location, jint count, jintArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *value_base = (GLuint *) 0;
    jint _remaining;
    GLuint *value = (GLuint *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLuint *)
        _env->GetIntArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniform1uiv(
        (GLint)location,
        (GLsizei)count,
        (GLuint *)value
    );

exit:
    if (value_base) {
        _env->ReleaseIntArrayElements(value_ref, (jint*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform1uiv ( GLint location, GLsizei count, const GLuint *value ) */
static void
android_glUniform1uiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *value = (GLuint *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLuint *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        value = (GLuint *) (_valueBase + _bufferOffset);
    }
    glUniform1uiv(
        (GLint)location,
        (GLsizei)count,
        (GLuint *)value
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform2uiv ( GLint location, GLsizei count, const GLuint *value ) */
static void
android_glUniform2uiv__II_3II
  (JNIEnv *_env, jobject _this, jint location, jint count, jintArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *value_base = (GLuint *) 0;
    jint _remaining;
    GLuint *value = (GLuint *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLuint *)
        _env->GetIntArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniform2uiv(
        (GLint)location,
        (GLsizei)count,
        (GLuint *)value
    );

exit:
    if (value_base) {
        _env->ReleaseIntArrayElements(value_ref, (jint*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform2uiv ( GLint location, GLsizei count, const GLuint *value ) */
static void
android_glUniform2uiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *value = (GLuint *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLuint *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        value = (GLuint *) (_valueBase + _bufferOffset);
    }
    glUniform2uiv(
        (GLint)location,
        (GLsizei)count,
        (GLuint *)value
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform3uiv ( GLint location, GLsizei count, const GLuint *value ) */
static void
android_glUniform3uiv__II_3II
  (JNIEnv *_env, jobject _this, jint location, jint count, jintArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *value_base = (GLuint *) 0;
    jint _remaining;
    GLuint *value = (GLuint *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLuint *)
        _env->GetIntArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniform3uiv(
        (GLint)location,
        (GLsizei)count,
        (GLuint *)value
    );

exit:
    if (value_base) {
        _env->ReleaseIntArrayElements(value_ref, (jint*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform3uiv ( GLint location, GLsizei count, const GLuint *value ) */
static void
android_glUniform3uiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *value = (GLuint *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLuint *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        value = (GLuint *) (_valueBase + _bufferOffset);
    }
    glUniform3uiv(
        (GLint)location,
        (GLsizei)count,
        (GLuint *)value
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform4uiv ( GLint location, GLsizei count, const GLuint *value ) */
static void
android_glUniform4uiv__II_3II
  (JNIEnv *_env, jobject _this, jint location, jint count, jintArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *value_base = (GLuint *) 0;
    jint _remaining;
    GLuint *value = (GLuint *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLuint *)
        _env->GetIntArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniform4uiv(
        (GLint)location,
        (GLsizei)count,
        (GLuint *)value
    );

exit:
    if (value_base) {
        _env->ReleaseIntArrayElements(value_ref, (jint*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform4uiv ( GLint location, GLsizei count, const GLuint *value ) */
static void
android_glUniform4uiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *value = (GLuint *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLuint *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        value = (GLuint *) (_valueBase + _bufferOffset);
    }
    glUniform4uiv(
        (GLint)location,
        (GLsizei)count,
        (GLuint *)value
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glClearBufferiv ( GLenum buffer, GLint drawbuffer, const GLint *value ) */
static void
android_glClearBufferiv__II_3II
  (JNIEnv *_env, jobject _this, jint buffer, jint drawbuffer, jintArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint *value_base = (GLint *) 0;
    jint _remaining;
    GLint *value = (GLint *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLint *)
        _env->GetIntArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glClearBufferiv(
        (GLenum)buffer,
        (GLint)drawbuffer,
        (GLint *)value
    );

exit:
    if (value_base) {
        _env->ReleaseIntArrayElements(value_ref, (jint*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glClearBufferiv ( GLenum buffer, GLint drawbuffer, const GLint *value ) */
static void
android_glClearBufferiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint buffer, jint drawbuffer, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *value = (GLint *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLint *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        value = (GLint *) (_valueBase + _bufferOffset);
    }
    glClearBufferiv(
        (GLenum)buffer,
        (GLint)drawbuffer,
        (GLint *)value
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glClearBufferuiv ( GLenum buffer, GLint drawbuffer, const GLuint *value ) */
static void
android_glClearBufferuiv__II_3II
  (JNIEnv *_env, jobject _this, jint buffer, jint drawbuffer, jintArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *value_base = (GLuint *) 0;
    jint _remaining;
    GLuint *value = (GLuint *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLuint *)
        _env->GetIntArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glClearBufferuiv(
        (GLenum)buffer,
        (GLint)drawbuffer,
        (GLuint *)value
    );

exit:
    if (value_base) {
        _env->ReleaseIntArrayElements(value_ref, (jint*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glClearBufferuiv ( GLenum buffer, GLint drawbuffer, const GLuint *value ) */
static void
android_glClearBufferuiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint buffer, jint drawbuffer, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *value = (GLuint *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLuint *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        value = (GLuint *) (_valueBase + _bufferOffset);
    }
    glClearBufferuiv(
        (GLenum)buffer,
        (GLint)drawbuffer,
        (GLuint *)value
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glClearBufferfv ( GLenum buffer, GLint drawbuffer, const GLfloat *value ) */
static void
android_glClearBufferfv__II_3FI
  (JNIEnv *_env, jobject _this, jint buffer, jint drawbuffer, jfloatArray value_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *value_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(value_ref) - offset;
    value_base = (GLfloat *)
        _env->GetFloatArrayElements(value_ref, (jboolean *)0);
    value = value_base + offset;

    glClearBufferfv(
        (GLenum)buffer,
        (GLint)drawbuffer,
        (GLfloat *)value
    );

exit:
    if (value_base) {
        _env->ReleaseFloatArrayElements(value_ref, (jfloat*)value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glClearBufferfv ( GLenum buffer, GLint drawbuffer, const GLfloat *value ) */
static void
android_glClearBufferfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint buffer, jint drawbuffer, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    if (!value_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "value == null";
        goto exit;
    }
    value = (GLfloat *)getPointer(_env, value_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        value = (GLfloat *) (_valueBase + _bufferOffset);
    }
    glClearBufferfv(
        (GLenum)buffer,
        (GLint)drawbuffer,
        (GLfloat *)value
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)value, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glClearBufferfi ( GLenum buffer, GLint drawbuffer, GLfloat depth, GLint stencil ) */
static void
android_glClearBufferfi__IIFI
  (JNIEnv *_env, jobject _this, jint buffer, jint drawbuffer, jfloat depth, jint stencil) {
    glClearBufferfi(
        (GLenum)buffer,
        (GLint)drawbuffer,
        (GLfloat)depth,
        (GLint)stencil
    );
}

/* const GLubyte * glGetStringi ( GLenum name, GLuint index ) */
static jstring
android_glGetStringi__II
  (JNIEnv *_env, jobject _this, jint name, jint index) {
    const GLubyte* _chars = glGetStringi((GLenum)name, (GLuint)index);
    return _env->NewStringUTF((const char*)_chars);
}

/* void glCopyBufferSubData ( GLenum readTarget, GLenum writeTarget, GLintptr readOffset, GLintptr writeOffset, GLsizeiptr size ) */
static void
android_glCopyBufferSubData__IIIII
  (JNIEnv *_env, jobject _this, jint readTarget, jint writeTarget, jint readOffset, jint writeOffset, jint size) {
    glCopyBufferSubData(
        (GLenum)readTarget,
        (GLenum)writeTarget,
        (GLintptr)readOffset,
        (GLintptr)writeOffset,
        (GLsizeiptr)size
    );
}

/* void glGetUniformIndices ( GLuint program, GLsizei uniformCount, const GLchar *const *uniformNames, GLuint *uniformIndices ) */
static
void
android_glGetUniformIndices_array
    (JNIEnv *_env, jobject _this, jint program, jobjectArray uniformNames_ref, jintArray uniformIndices_ref, jint uniformIndicesOffset) {
    jint _exception = 0;
    const char* _exceptionType = NULL;
    const char* _exceptionMessage = NULL;
    jint _count = 0;
    jint _i;
    const char** _names = NULL;
    GLuint* _indices_base = NULL;
    GLuint* _indices = NULL;

    if (!uniformNames_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "uniformNames == null";
        goto exit;
    }
    _count = _env->GetArrayLength(uniformNames_ref);
    _names = (const char**)calloc(_count, sizeof(const char*));
    for (_i = 0; _i < _count; _i++) {
        jstring _name = (jstring)_env->GetObjectArrayElement(uniformNames_ref, _i);
        if (!_name) {
            _exception = 1;
            _exceptionType = "java/lang/IllegalArgumentException";
            _exceptionMessage = "null uniformNames element";
            goto exit;
        }
        _names[_i] = _env->GetStringUTFChars(_name, 0);
    }

    if (!uniformIndices_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "uniformIndices == null";
        goto exit;
    }
    if (uniformIndicesOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "uniformIndicesOffset < 0";
        goto exit;
    }
    if (_env->GetArrayLength(uniformIndices_ref) - uniformIndicesOffset < _count) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "not enough space in uniformIndices";
        goto exit;
    }
    _indices_base = (GLuint*)_env->GetIntArrayElements(
            uniformIndices_ref, 0);
    _indices = _indices_base + uniformIndicesOffset;

    glGetUniformIndices(program, _count, _names, _indices);

exit:
    if (_indices_base) {
        _env->ReleaseIntArrayElements(uniformIndices_ref, (jint*)_indices_base,
            _exception ? JNI_ABORT : 0);
    }
    for (_i = _count - 1; _i >= 0; _i--) {
        if (_names[_i]) {
            jstring _name = (jstring)_env->GetObjectArrayElement(uniformNames_ref, _i);
            if (_name) {
                _env->ReleaseStringUTFChars(_name, _names[_i]);
            }
        }
    }
    free(_names);
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetUniformIndices ( GLuint program, GLsizei uniformCount, const GLchar *const *uniformNames, GLuint *uniformIndices ) */
static
void
android_glGetUniformIndices_buffer
    (JNIEnv *_env, jobject _this, jint program, jobjectArray uniformNames_ref, jobject uniformIndices_buf) {
    jint _exception = 0;
    const char* _exceptionType = NULL;
    const char* _exceptionMessage = NULL;
    jint _count = 0;
    jint _i;
    const char** _names = NULL;
    jintArray _uniformIndicesArray = (jintArray)0;
    jint _uniformIndicesRemaining;
    jint _uniformIndicesOffset = 0;
    GLuint* _indices = NULL;
    char* _indicesBase = NULL;

    if (!uniformNames_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "uniformNames == null";
        goto exit;
    }
    if (!uniformIndices_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "uniformIndices == null";
        goto exit;
    }

    _count = _env->GetArrayLength(uniformNames_ref);
    _names = (const char**)calloc(_count, sizeof(const char*));
    for (_i = 0; _i < _count; _i++) {
        jstring _name = (jstring)_env->GetObjectArrayElement(uniformNames_ref, _i);
        if (!_name) {
            _exception = 1;
            _exceptionType = "java/lang/IllegalArgumentException";
            _exceptionMessage = "null uniformNames element";
            goto exit;
        }
        _names[_i] = _env->GetStringUTFChars(_name, 0);
    }

    _indices = (GLuint*)getPointer(_env, uniformIndices_buf,
            (jarray*)&_uniformIndicesArray, &_uniformIndicesRemaining,
            &_uniformIndicesOffset);
    if (!_indices) {
        _indicesBase = (char*)_env->GetIntArrayElements(
            _uniformIndicesArray, 0);
        _indices = (GLuint*)(_indicesBase + _uniformIndicesOffset);
    }
    if (_uniformIndicesRemaining < _count) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "not enough space in uniformIndices";
        goto exit;
    }

    glGetUniformIndices(program, _count, _names, _indices);

exit:
    if (_uniformIndicesArray) {
        releaseArrayPointer<jintArray, jint*, IntArrayReleaser>(
            _env, _uniformIndicesArray, (jint*)_indicesBase, JNI_TRUE);
    }
    for (_i = _count - 1; _i >= 0; _i--) {
        if (_names[_i]) {
            jstring _name = (jstring)_env->GetObjectArrayElement(uniformNames_ref, _i);
            if (_name) {
                _env->ReleaseStringUTFChars(_name, _names[_i]);
            }
        }
    }
    free(_names);
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}
/* void glGetActiveUniformsiv ( GLuint program, GLsizei uniformCount, const GLuint *uniformIndices, GLenum pname, GLint *params ) */
static void
android_glGetActiveUniformsiv__II_3III_3II
  (JNIEnv *_env, jobject _this, jint program, jint uniformCount, jintArray uniformIndices_ref, jint uniformIndicesOffset, jint pname, jintArray params_ref, jint paramsOffset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *uniformIndices_base = (GLuint *) 0;
    jint _uniformIndicesRemaining;
    GLuint *uniformIndices = (GLuint *) 0;
    GLint *params_base = (GLint *) 0;
    jint _paramsRemaining;
    GLint *params = (GLint *) 0;

    if (!uniformIndices_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "uniformIndices == null";
        goto exit;
    }
    if (uniformIndicesOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "uniformIndicesOffset < 0";
        goto exit;
    }
    _uniformIndicesRemaining = _env->GetArrayLength(uniformIndices_ref) - uniformIndicesOffset;
    uniformIndices_base = (GLuint *)
        _env->GetIntArrayElements(uniformIndices_ref, (jboolean *)0);
    uniformIndices = uniformIndices_base + uniformIndicesOffset;

    if (!params_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    if (paramsOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "paramsOffset < 0";
        goto exit;
    }
    _paramsRemaining = _env->GetArrayLength(params_ref) - paramsOffset;
    params_base = (GLint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + paramsOffset;

    glGetActiveUniformsiv(
        (GLuint)program,
        (GLsizei)uniformCount,
        (GLuint *)uniformIndices,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (uniformIndices_base) {
        _env->ReleaseIntArrayElements(uniformIndices_ref, (jint*)uniformIndices_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetActiveUniformsiv ( GLuint program, GLsizei uniformCount, const GLuint *uniformIndices, GLenum pname, GLint *params ) */
static void
android_glGetActiveUniformsiv__IILjava_nio_IntBuffer_2ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint uniformCount, jobject uniformIndices_buf, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _uniformIndicesArray = (jintArray) 0;
    jint _uniformIndicesBufferOffset = (jint) 0;
    jintArray _paramsArray = (jintArray) 0;
    jint _paramsBufferOffset = (jint) 0;
    jint _uniformIndicesRemaining;
    GLuint *uniformIndices = (GLuint *) 0;
    jint _paramsRemaining;
    GLint *params = (GLint *) 0;

    if (!uniformIndices_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "uniformIndices == null";
        goto exit;
    }
    uniformIndices = (GLuint *)getPointer(_env, uniformIndices_buf, (jarray*)&_uniformIndicesArray, &_uniformIndicesRemaining, &_uniformIndicesBufferOffset);
    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLint *)getPointer(_env, params_buf, (jarray*)&_paramsArray, &_paramsRemaining, &_paramsBufferOffset);
    if (uniformIndices == NULL) {
        char * _uniformIndicesBase = (char *)_env->GetIntArrayElements(_uniformIndicesArray, (jboolean *) 0);
        uniformIndices = (GLuint *) (_uniformIndicesBase + _uniformIndicesBufferOffset);
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_paramsArray, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _paramsBufferOffset);
    }
    glGetActiveUniformsiv(
        (GLuint)program,
        (GLsizei)uniformCount,
        (GLuint *)uniformIndices,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_paramsArray) {
        _env->ReleaseIntArrayElements(_paramsArray, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_uniformIndicesArray) {
        _env->ReleaseIntArrayElements(_uniformIndicesArray, (jint*)uniformIndices, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLuint glGetUniformBlockIndex ( GLuint program, const GLchar *uniformBlockName ) */
static jint
android_glGetUniformBlockIndex__ILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint program, jstring uniformBlockName) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint _returnValue = 0;
    const char* _nativeuniformBlockName = 0;

    if (!uniformBlockName) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "uniformBlockName == null";
        goto exit;
    }
    _nativeuniformBlockName = _env->GetStringUTFChars(uniformBlockName, 0);

    _returnValue = glGetUniformBlockIndex(
        (GLuint)program,
        (GLchar *)_nativeuniformBlockName
    );

exit:
    if (_nativeuniformBlockName) {
        _env->ReleaseStringUTFChars(uniformBlockName, _nativeuniformBlockName);
    }

    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
        return (jint)0;
    }
    return (jint)_returnValue;
}

/* void glGetActiveUniformBlockiv ( GLuint program, GLuint uniformBlockIndex, GLenum pname, GLint *params ) */
static void
android_glGetActiveUniformBlockiv__III_3II
  (JNIEnv *_env, jobject _this, jint program, jint uniformBlockIndex, jint pname, jintArray params_ref, jint offset) {
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
    params_base = (GLint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetActiveUniformBlockiv(
        (GLuint)program,
        (GLuint)uniformBlockIndex,
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

/* void glGetActiveUniformBlockiv ( GLuint program, GLuint uniformBlockIndex, GLenum pname, GLint *params ) */
static void
android_glGetActiveUniformBlockiv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint uniformBlockIndex, jint pname, jobject params_buf) {
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetActiveUniformBlockiv(
        (GLuint)program,
        (GLuint)uniformBlockIndex,
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

/* void glGetActiveUniformBlockName ( GLuint program, GLuint uniformBlockIndex, GLsizei bufSize, GLsizei *length, GLchar *uniformBlockName ) */
static void
android_glGetActiveUniformBlockName_III_3II_3BI
    (JNIEnv* _env, jobject _this, jint program, jint uniformBlockIndex, int bufSize, jintArray length_ref, jint lengthOffset, jbyteArray name_ref, jint nameOffset) {
    jint _exception = 0;
    const char* _exceptionType;
    const char* _exceptionMessage;
    GLsizei* _length_base = (GLsizei*)0;
    jint _lengthRemaining;
    GLsizei* _length = (GLsizei*)0;
    GLchar* _name_base = (GLchar*)0;
    jint _nameRemaining;
    GLchar* _name = (GLchar*)0;

    if (length_ref) {
        if (lengthOffset < 0) {
            _exception = 1;
            _exceptionType = "java/lang/IllegalArgumentException";
            _exceptionMessage = "lengthOffset < 0";
            goto exit;
        }
        _lengthRemaining = _env->GetArrayLength(length_ref) - lengthOffset;
        _length_base = (GLsizei*)_env->GetIntArrayElements(
                length_ref, (jboolean*)0);
        _length = _length_base + lengthOffset;
    }

    if (!name_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "uniformBlockName == null";
        goto exit;
    }
    if (nameOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "uniformBlockNameOffset < 0";
        goto exit;
    }
    _nameRemaining = _env->GetArrayLength(name_ref) - nameOffset;
    _name_base = (GLchar*)_env->GetByteArrayElements(
            name_ref, (jboolean*)0);
    _name = _name_base + nameOffset;

    glGetActiveUniformBlockName(
        (GLuint)program,
        (GLuint)uniformBlockIndex,
        (GLsizei)bufSize,
        (GLsizei*)_length,
        (GLchar*)_name
    );

exit:
    if (_name_base) {
        _env->ReleaseByteArrayElements(name_ref, (jbyte*)_name_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_length_base) {
        _env->ReleaseIntArrayElements(length_ref, (jint*)_length_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetActiveUniformBlockName ( GLuint program, GLuint uniformBlockIndex, GLsizei bufSize, GLsizei *length, GLchar *uniformBlockName ) */
static void
android_glGetActiveUniformBlockName_IILjava_nio_Buffer_2Ljava_nio_Buffer_2
    (JNIEnv* _env, jobject _this, jint program, jint uniformBlockIndex, jobject length_buf, jobject uniformBlockName_buf) {
    jint _exception = 0;
    const char* _exceptionType;
    const char* _exceptionMessage;
    jarray _lengthArray = (jarray)0;
    jint _lengthBufferOffset = (jint)0;
    GLsizei* _length = (GLsizei*)0;
    jint _lengthRemaining;
    jarray _nameArray = (jarray)0;
    jint _nameBufferOffset = (jint)0;
    GLchar* _name = (GLchar*)0;
    jint _nameRemaining;

    _length = (GLsizei*)getPointer(_env, length_buf, &_lengthArray, &_lengthRemaining, &_lengthBufferOffset);
    if (_length == NULL) {
        GLsizei* _lengthBase = (GLsizei*)_env->GetPrimitiveArrayCritical(_lengthArray, (jboolean*)0);
        _length = (GLsizei*)(_lengthBase + _lengthBufferOffset);
    }

    _name = (GLchar*)getPointer(_env, uniformBlockName_buf, &_nameArray, &_nameRemaining, &_nameBufferOffset);
    if (_name == NULL) {
        GLchar* _nameBase = (GLchar*)_env->GetPrimitiveArrayCritical(_nameArray, (jboolean*)0);
        _name = (GLchar*)(_nameBase + _nameBufferOffset);
    }

    glGetActiveUniformBlockName(
        (GLuint)program,
        (GLuint)uniformBlockIndex,
        (GLsizei)_nameRemaining,
        _length, _name
    );
    if (_nameArray) {
        releasePointer(_env, _nameArray, _name, JNI_TRUE);
    }
    if (_lengthArray) {
        releasePointer(_env, _lengthArray, _length, JNI_TRUE);
    }
}

/* void glGetActiveUniformBlockName ( GLuint program, GLuint uniformBlockIndex, GLsizei bufSize, GLsizei *length, GLchar *uniformBlockName ) */
static jstring
android_glGetActiveUniformBlockName_II
    (JNIEnv *_env, jobject _this, jint program, jint uniformBlockIndex) {
    GLint len = 0;
    glGetActiveUniformBlockiv((GLuint)program, (GLuint)uniformBlockIndex,
            GL_UNIFORM_BLOCK_NAME_LENGTH, &len);
    GLchar* name = (GLchar*)malloc(len);
    glGetActiveUniformBlockName((GLuint)program, (GLuint)uniformBlockIndex,
        len, NULL, name);
    jstring result = _env->NewStringUTF(name);
    free(name);
    return result;
}
/* void glUniformBlockBinding ( GLuint program, GLuint uniformBlockIndex, GLuint uniformBlockBinding ) */
static void
android_glUniformBlockBinding__III
  (JNIEnv *_env, jobject _this, jint program, jint uniformBlockIndex, jint uniformBlockBinding) {
    glUniformBlockBinding(
        (GLuint)program,
        (GLuint)uniformBlockIndex,
        (GLuint)uniformBlockBinding
    );
}

/* void glDrawArraysInstanced ( GLenum mode, GLint first, GLsizei count, GLsizei instanceCount ) */
static void
android_glDrawArraysInstanced__IIII
  (JNIEnv *_env, jobject _this, jint mode, jint first, jint count, jint instanceCount) {
    glDrawArraysInstanced(
        (GLenum)mode,
        (GLint)first,
        (GLsizei)count,
        (GLsizei)instanceCount
    );
}

/* void glDrawElementsInstanced ( GLenum mode, GLsizei count, GLenum type, const GLvoid *indices, GLsizei instanceCount ) */
static void
android_glDrawElementsInstanced__IIILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint mode, jint count, jint type, jobject indices_buf, jint instanceCount) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *indices = (GLvoid *) 0;

    indices = (GLvoid *)getPointer(_env, indices_buf, &_array, &_remaining, &_bufferOffset);
    if (indices == NULL) {
        char * _indicesBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        indices = (GLvoid *) (_indicesBase + _bufferOffset);
    }
    glDrawElementsInstanced(
        (GLenum)mode,
        (GLsizei)count,
        (GLenum)type,
        (GLvoid *)indices,
        (GLsizei)instanceCount
    );
    if (_array) {
        releasePointer(_env, _array, indices, JNI_FALSE);
    }
}

/* void glDrawElementsInstanced ( GLenum mode, GLsizei count, GLenum type, const GLvoid *indices, GLsizei instanceCount ) */
static void
android_glDrawElementsInstanced__IIIII
  (JNIEnv *_env, jobject _this, jint mode, jint count, jint type, jint indicesOffset, jint instanceCount) {
    glDrawElementsInstanced(
        (GLenum)mode,
        (GLsizei)count,
        (GLenum)type,
        (GLvoid *)static_cast<uintptr_t>(indicesOffset),
        (GLsizei)instanceCount
    );
}
/* GLsync glFenceSync ( GLenum condition, GLbitfield flags ) */
static jlong
android_glFenceSync__II
  (JNIEnv *_env, jobject _this, jint condition, jint flags) {
    GLsync _returnValue;
    _returnValue = glFenceSync(
        (GLenum)condition,
        (GLbitfield)flags
    );
    return (jlong)_returnValue;
}

/* GLboolean glIsSync ( GLsync sync ) */
static jboolean
android_glIsSync__J
  (JNIEnv *_env, jobject _this, jlong sync) {
    GLboolean _returnValue;
    _returnValue = glIsSync(
        (GLsync)sync
    );
    return (jboolean)_returnValue;
}

/* void glDeleteSync ( GLsync sync ) */
static void
android_glDeleteSync__J
  (JNIEnv *_env, jobject _this, jlong sync) {
    glDeleteSync(
        (GLsync)sync
    );
}

/* GLenum glClientWaitSync ( GLsync sync, GLbitfield flags, GLuint64 timeout ) */
static jint
android_glClientWaitSync__JIJ
  (JNIEnv *_env, jobject _this, jlong sync, jint flags, jlong timeout) {
    GLenum _returnValue;
    _returnValue = glClientWaitSync(
        (GLsync)sync,
        (GLbitfield)flags,
        (GLuint64)timeout
    );
    return (jint)_returnValue;
}

/* void glWaitSync ( GLsync sync, GLbitfield flags, GLuint64 timeout ) */
static void
android_glWaitSync__JIJ
  (JNIEnv *_env, jobject _this, jlong sync, jint flags, jlong timeout) {
    glWaitSync(
        (GLsync)sync,
        (GLbitfield)flags,
        (GLuint64)timeout
    );
}

/* void glGetInteger64v ( GLenum pname, GLint64 *params ) */
static void
android_glGetInteger64v__I_3JI
  (JNIEnv *_env, jobject _this, jint pname, jlongArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint64 *params_base = (GLint64 *) 0;
    jint _remaining;
    GLint64 *params = (GLint64 *) 0;

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
    params_base = (GLint64 *)
        _env->GetLongArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetInteger64v(
        (GLenum)pname,
        (GLint64 *)params
    );

exit:
    if (params_base) {
        _env->ReleaseLongArrayElements(params_ref, (jlong*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetInteger64v ( GLenum pname, GLint64 *params ) */
static void
android_glGetInteger64v__ILjava_nio_LongBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jlongArray _array = (jlongArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint64 *params = (GLint64 *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLint64 *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetLongArrayElements(_array, (jboolean *) 0);
        params = (GLint64 *) (_paramsBase + _bufferOffset);
    }
    glGetInteger64v(
        (GLenum)pname,
        (GLint64 *)params
    );

exit:
    if (_array) {
        _env->ReleaseLongArrayElements(_array, (jlong*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetSynciv ( GLsync sync, GLenum pname, GLsizei bufSize, GLsizei *length, GLint *values ) */
static void
android_glGetSynciv__JII_3II_3II
  (JNIEnv *_env, jobject _this, jlong sync, jint pname, jint bufSize, jintArray length_ref, jint lengthOffset, jintArray values_ref, jint valuesOffset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLsizei *length_base = (GLsizei *) 0;
    jint _lengthRemaining;
    GLsizei *length = (GLsizei *) 0;
    GLint *values_base = (GLint *) 0;
    jint _valuesRemaining;
    GLint *values = (GLint *) 0;

    if (length_ref) {
        if (lengthOffset < 0) {
            _exception = 1;
            _exceptionType = "java/lang/IllegalArgumentException";
            _exceptionMessage = "lengthOffset < 0";
            goto exit;
        }
        _lengthRemaining = _env->GetArrayLength(length_ref) - lengthOffset;
        length_base = (GLsizei *)
            _env->GetIntArrayElements(length_ref, (jboolean *)0);
        length = length_base + lengthOffset;
    }

    if (!values_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "values == null";
        goto exit;
    }
    if (valuesOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "valuesOffset < 0";
        goto exit;
    }
    _valuesRemaining = _env->GetArrayLength(values_ref) - valuesOffset;
    values_base = (GLint *)
        _env->GetIntArrayElements(values_ref, (jboolean *)0);
    values = values_base + valuesOffset;

    glGetSynciv(
        (GLsync)sync,
        (GLenum)pname,
        (GLsizei)bufSize,
        (GLsizei *)length,
        (GLint *)values
    );

exit:
    if (values_base) {
        _env->ReleaseIntArrayElements(values_ref, (jint*)values_base,
            _exception ? JNI_ABORT: 0);
    }
    if (length_base) {
        _env->ReleaseIntArrayElements(length_ref, (jint*)length_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetSynciv ( GLsync sync, GLenum pname, GLsizei bufSize, GLsizei *length, GLint *values ) */
static void
android_glGetSynciv__JIILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jlong sync, jint pname, jint bufSize, jobject length_buf, jobject values_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _lengthArray = (jintArray) 0;
    jint _lengthBufferOffset = (jint) 0;
    jintArray _valuesArray = (jintArray) 0;
    jint _valuesBufferOffset = (jint) 0;
    jint _lengthRemaining;
    GLsizei *length = (GLsizei *) 0;
    jint _valuesRemaining;
    GLint *values = (GLint *) 0;

    if (length_buf) {
        length = (GLsizei *)getPointer(_env, length_buf, (jarray*)&_lengthArray, &_lengthRemaining, &_lengthBufferOffset);
    }
    if (!values_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "values == null";
        goto exit;
    }
    values = (GLint *)getPointer(_env, values_buf, (jarray*)&_valuesArray, &_valuesRemaining, &_valuesBufferOffset);
    if (length_buf && length == NULL) {
        char * _lengthBase = (char *)_env->GetIntArrayElements(_lengthArray, (jboolean *) 0);
        length = (GLsizei *) (_lengthBase + _lengthBufferOffset);
    }
    if (values == NULL) {
        char * _valuesBase = (char *)_env->GetIntArrayElements(_valuesArray, (jboolean *) 0);
        values = (GLint *) (_valuesBase + _valuesBufferOffset);
    }
    glGetSynciv(
        (GLsync)sync,
        (GLenum)pname,
        (GLsizei)bufSize,
        (GLsizei *)length,
        (GLint *)values
    );

exit:
    if (_valuesArray) {
        _env->ReleaseIntArrayElements(_valuesArray, (jint*)values, _exception ? JNI_ABORT : 0);
    }
    if (_lengthArray) {
        _env->ReleaseIntArrayElements(_lengthArray, (jint*)length, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetInteger64i_v ( GLenum target, GLuint index, GLint64 *data ) */
static void
android_glGetInteger64i_v__II_3JI
  (JNIEnv *_env, jobject _this, jint target, jint index, jlongArray data_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint64 *data_base = (GLint64 *) 0;
    jint _remaining;
    GLint64 *data = (GLint64 *) 0;

    if (!data_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "data == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(data_ref) - offset;
    data_base = (GLint64 *)
        _env->GetLongArrayElements(data_ref, (jboolean *)0);
    data = data_base + offset;

    glGetInteger64i_v(
        (GLenum)target,
        (GLuint)index,
        (GLint64 *)data
    );

exit:
    if (data_base) {
        _env->ReleaseLongArrayElements(data_ref, (jlong*)data_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetInteger64i_v ( GLenum target, GLuint index, GLint64 *data ) */
static void
android_glGetInteger64i_v__IILjava_nio_LongBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint index, jobject data_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jlongArray _array = (jlongArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint64 *data = (GLint64 *) 0;

    if (!data_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "data == null";
        goto exit;
    }
    data = (GLint64 *)getPointer(_env, data_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (data == NULL) {
        char * _dataBase = (char *)_env->GetLongArrayElements(_array, (jboolean *) 0);
        data = (GLint64 *) (_dataBase + _bufferOffset);
    }
    glGetInteger64i_v(
        (GLenum)target,
        (GLuint)index,
        (GLint64 *)data
    );

exit:
    if (_array) {
        _env->ReleaseLongArrayElements(_array, (jlong*)data, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetBufferParameteri64v ( GLenum target, GLenum pname, GLint64 *params ) */
static void
android_glGetBufferParameteri64v__II_3JI
  (JNIEnv *_env, jobject _this, jint target, jint pname, jlongArray params_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint64 *params_base = (GLint64 *) 0;
    jint _remaining;
    GLint64 *params = (GLint64 *) 0;

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
    params_base = (GLint64 *)
        _env->GetLongArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetBufferParameteri64v(
        (GLenum)target,
        (GLenum)pname,
        (GLint64 *)params
    );

exit:
    if (params_base) {
        _env->ReleaseLongArrayElements(params_ref, (jlong*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetBufferParameteri64v ( GLenum target, GLenum pname, GLint64 *params ) */
static void
android_glGetBufferParameteri64v__IILjava_nio_LongBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jlongArray _array = (jlongArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint64 *params = (GLint64 *) 0;

    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLint64 *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetLongArrayElements(_array, (jboolean *) 0);
        params = (GLint64 *) (_paramsBase + _bufferOffset);
    }
    glGetBufferParameteri64v(
        (GLenum)target,
        (GLenum)pname,
        (GLint64 *)params
    );

exit:
    if (_array) {
        _env->ReleaseLongArrayElements(_array, (jlong*)params, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenSamplers ( GLsizei count, GLuint *samplers ) */
static void
android_glGenSamplers__I_3II
  (JNIEnv *_env, jobject _this, jint count, jintArray samplers_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *samplers_base = (GLuint *) 0;
    jint _remaining;
    GLuint *samplers = (GLuint *) 0;

    if (!samplers_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "samplers == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(samplers_ref) - offset;
    samplers_base = (GLuint *)
        _env->GetIntArrayElements(samplers_ref, (jboolean *)0);
    samplers = samplers_base + offset;

    glGenSamplers(
        (GLsizei)count,
        (GLuint *)samplers
    );

exit:
    if (samplers_base) {
        _env->ReleaseIntArrayElements(samplers_ref, (jint*)samplers_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenSamplers ( GLsizei count, GLuint *samplers ) */
static void
android_glGenSamplers__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint count, jobject samplers_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *samplers = (GLuint *) 0;

    if (!samplers_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "samplers == null";
        goto exit;
    }
    samplers = (GLuint *)getPointer(_env, samplers_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (samplers == NULL) {
        char * _samplersBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        samplers = (GLuint *) (_samplersBase + _bufferOffset);
    }
    glGenSamplers(
        (GLsizei)count,
        (GLuint *)samplers
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)samplers, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteSamplers ( GLsizei count, const GLuint *samplers ) */
static void
android_glDeleteSamplers__I_3II
  (JNIEnv *_env, jobject _this, jint count, jintArray samplers_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *samplers_base = (GLuint *) 0;
    jint _remaining;
    GLuint *samplers = (GLuint *) 0;

    if (!samplers_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "samplers == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(samplers_ref) - offset;
    samplers_base = (GLuint *)
        _env->GetIntArrayElements(samplers_ref, (jboolean *)0);
    samplers = samplers_base + offset;

    glDeleteSamplers(
        (GLsizei)count,
        (GLuint *)samplers
    );

exit:
    if (samplers_base) {
        _env->ReleaseIntArrayElements(samplers_ref, (jint*)samplers_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteSamplers ( GLsizei count, const GLuint *samplers ) */
static void
android_glDeleteSamplers__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint count, jobject samplers_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *samplers = (GLuint *) 0;

    if (!samplers_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "samplers == null";
        goto exit;
    }
    samplers = (GLuint *)getPointer(_env, samplers_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (samplers == NULL) {
        char * _samplersBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        samplers = (GLuint *) (_samplersBase + _bufferOffset);
    }
    glDeleteSamplers(
        (GLsizei)count,
        (GLuint *)samplers
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)samplers, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLboolean glIsSampler ( GLuint sampler ) */
static jboolean
android_glIsSampler__I
  (JNIEnv *_env, jobject _this, jint sampler) {
    GLboolean _returnValue;
    _returnValue = glIsSampler(
        (GLuint)sampler
    );
    return (jboolean)_returnValue;
}

/* void glBindSampler ( GLuint unit, GLuint sampler ) */
static void
android_glBindSampler__II
  (JNIEnv *_env, jobject _this, jint unit, jint sampler) {
    glBindSampler(
        (GLuint)unit,
        (GLuint)sampler
    );
}

/* void glSamplerParameteri ( GLuint sampler, GLenum pname, GLint param ) */
static void
android_glSamplerParameteri__III
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jint param) {
    glSamplerParameteri(
        (GLuint)sampler,
        (GLenum)pname,
        (GLint)param
    );
}

/* void glSamplerParameteriv ( GLuint sampler, GLenum pname, const GLint *param ) */
static void
android_glSamplerParameteriv__II_3II
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jintArray param_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint *param_base = (GLint *) 0;
    jint _remaining;
    GLint *param = (GLint *) 0;

    if (!param_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "param == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(param_ref) - offset;
    param_base = (GLint *)
        _env->GetIntArrayElements(param_ref, (jboolean *)0);
    param = param_base + offset;

    glSamplerParameteriv(
        (GLuint)sampler,
        (GLenum)pname,
        (GLint *)param
    );

exit:
    if (param_base) {
        _env->ReleaseIntArrayElements(param_ref, (jint*)param_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glSamplerParameteriv ( GLuint sampler, GLenum pname, const GLint *param ) */
static void
android_glSamplerParameteriv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jobject param_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *param = (GLint *) 0;

    if (!param_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "param == null";
        goto exit;
    }
    param = (GLint *)getPointer(_env, param_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (param == NULL) {
        char * _paramBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        param = (GLint *) (_paramBase + _bufferOffset);
    }
    glSamplerParameteriv(
        (GLuint)sampler,
        (GLenum)pname,
        (GLint *)param
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)param, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glSamplerParameterf ( GLuint sampler, GLenum pname, GLfloat param ) */
static void
android_glSamplerParameterf__IIF
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jfloat param) {
    glSamplerParameterf(
        (GLuint)sampler,
        (GLenum)pname,
        (GLfloat)param
    );
}

/* void glSamplerParameterfv ( GLuint sampler, GLenum pname, const GLfloat *param ) */
static void
android_glSamplerParameterfv__II_3FI
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jfloatArray param_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *param_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *param = (GLfloat *) 0;

    if (!param_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "param == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(param_ref) - offset;
    param_base = (GLfloat *)
        _env->GetFloatArrayElements(param_ref, (jboolean *)0);
    param = param_base + offset;

    glSamplerParameterfv(
        (GLuint)sampler,
        (GLenum)pname,
        (GLfloat *)param
    );

exit:
    if (param_base) {
        _env->ReleaseFloatArrayElements(param_ref, (jfloat*)param_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glSamplerParameterfv ( GLuint sampler, GLenum pname, const GLfloat *param ) */
static void
android_glSamplerParameterfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jobject param_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *param = (GLfloat *) 0;

    if (!param_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "param == null";
        goto exit;
    }
    param = (GLfloat *)getPointer(_env, param_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (param == NULL) {
        char * _paramBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        param = (GLfloat *) (_paramBase + _bufferOffset);
    }
    glSamplerParameterfv(
        (GLuint)sampler,
        (GLenum)pname,
        (GLfloat *)param
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)param, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetSamplerParameteriv ( GLuint sampler, GLenum pname, GLint *params ) */
static void
android_glGetSamplerParameteriv__II_3II
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jintArray params_ref, jint offset) {
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
    params_base = (GLint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetSamplerParameteriv(
        (GLuint)sampler,
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

/* void glGetSamplerParameteriv ( GLuint sampler, GLenum pname, GLint *params ) */
static void
android_glGetSamplerParameteriv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jobject params_buf) {
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetSamplerParameteriv(
        (GLuint)sampler,
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

/* void glGetSamplerParameterfv ( GLuint sampler, GLenum pname, GLfloat *params ) */
static void
android_glGetSamplerParameterfv__II_3FI
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jfloatArray params_ref, jint offset) {
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
    params_base = (GLfloat *)
        _env->GetFloatArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetSamplerParameterfv(
        (GLuint)sampler,
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

/* void glGetSamplerParameterfv ( GLuint sampler, GLenum pname, GLfloat *params ) */
static void
android_glGetSamplerParameterfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jobject params_buf) {
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        params = (GLfloat *) (_paramsBase + _bufferOffset);
    }
    glGetSamplerParameterfv(
        (GLuint)sampler,
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

/* void glVertexAttribDivisor ( GLuint index, GLuint divisor ) */
static void
android_glVertexAttribDivisor__II
  (JNIEnv *_env, jobject _this, jint index, jint divisor) {
    glVertexAttribDivisor(
        (GLuint)index,
        (GLuint)divisor
    );
}

/* void glBindTransformFeedback ( GLenum target, GLuint id ) */
static void
android_glBindTransformFeedback__II
  (JNIEnv *_env, jobject _this, jint target, jint id) {
    glBindTransformFeedback(
        (GLenum)target,
        (GLuint)id
    );
}

/* void glDeleteTransformFeedbacks ( GLsizei n, const GLuint *ids ) */
static void
android_glDeleteTransformFeedbacks__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray ids_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *ids_base = (GLuint *) 0;
    jint _remaining;
    GLuint *ids = (GLuint *) 0;

    if (!ids_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "ids == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(ids_ref) - offset;
    ids_base = (GLuint *)
        _env->GetIntArrayElements(ids_ref, (jboolean *)0);
    ids = ids_base + offset;

    glDeleteTransformFeedbacks(
        (GLsizei)n,
        (GLuint *)ids
    );

exit:
    if (ids_base) {
        _env->ReleaseIntArrayElements(ids_ref, (jint*)ids_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteTransformFeedbacks ( GLsizei n, const GLuint *ids ) */
static void
android_glDeleteTransformFeedbacks__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject ids_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *ids = (GLuint *) 0;

    if (!ids_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "ids == null";
        goto exit;
    }
    ids = (GLuint *)getPointer(_env, ids_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (ids == NULL) {
        char * _idsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        ids = (GLuint *) (_idsBase + _bufferOffset);
    }
    glDeleteTransformFeedbacks(
        (GLsizei)n,
        (GLuint *)ids
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)ids, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenTransformFeedbacks ( GLsizei n, GLuint *ids ) */
static void
android_glGenTransformFeedbacks__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray ids_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *ids_base = (GLuint *) 0;
    jint _remaining;
    GLuint *ids = (GLuint *) 0;

    if (!ids_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "ids == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(ids_ref) - offset;
    ids_base = (GLuint *)
        _env->GetIntArrayElements(ids_ref, (jboolean *)0);
    ids = ids_base + offset;

    glGenTransformFeedbacks(
        (GLsizei)n,
        (GLuint *)ids
    );

exit:
    if (ids_base) {
        _env->ReleaseIntArrayElements(ids_ref, (jint*)ids_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenTransformFeedbacks ( GLsizei n, GLuint *ids ) */
static void
android_glGenTransformFeedbacks__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject ids_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *ids = (GLuint *) 0;

    if (!ids_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "ids == null";
        goto exit;
    }
    ids = (GLuint *)getPointer(_env, ids_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (ids == NULL) {
        char * _idsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        ids = (GLuint *) (_idsBase + _bufferOffset);
    }
    glGenTransformFeedbacks(
        (GLsizei)n,
        (GLuint *)ids
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)ids, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLboolean glIsTransformFeedback ( GLuint id ) */
static jboolean
android_glIsTransformFeedback__I
  (JNIEnv *_env, jobject _this, jint id) {
    GLboolean _returnValue;
    _returnValue = glIsTransformFeedback(
        (GLuint)id
    );
    return (jboolean)_returnValue;
}

/* void glPauseTransformFeedback ( void ) */
static void
android_glPauseTransformFeedback__
  (JNIEnv *_env, jobject _this) {
    glPauseTransformFeedback();
}

/* void glResumeTransformFeedback ( void ) */
static void
android_glResumeTransformFeedback__
  (JNIEnv *_env, jobject _this) {
    glResumeTransformFeedback();
}

/* void glGetProgramBinary ( GLuint program, GLsizei bufSize, GLsizei *length, GLenum *binaryFormat, GLvoid *binary ) */
static void
android_glGetProgramBinary__II_3II_3IILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint program, jint bufSize, jintArray length_ref, jint lengthOffset, jintArray binaryFormat_ref, jint binaryFormatOffset, jobject binary_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    GLsizei *length_base = (GLsizei *) 0;
    jint _lengthRemaining;
    GLsizei *length = (GLsizei *) 0;
    GLenum *binaryFormat_base = (GLenum *) 0;
    jint _binaryFormatRemaining;
    GLenum *binaryFormat = (GLenum *) 0;
    jint _binaryRemaining;
    GLvoid *binary = (GLvoid *) 0;

    if (length_ref) {
        if (lengthOffset < 0) {
            _exception = 1;
            _exceptionType = "java/lang/IllegalArgumentException";
            _exceptionMessage = "lengthOffset < 0";
            goto exit;
        }
        _lengthRemaining = _env->GetArrayLength(length_ref) - lengthOffset;
        length_base = (GLsizei *)
            _env->GetIntArrayElements(length_ref, (jboolean *)0);
        length = length_base + lengthOffset;
    }

    if (!binaryFormat_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "binaryFormat == null";
        goto exit;
    }
    if (binaryFormatOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "binaryFormatOffset < 0";
        goto exit;
    }
    _binaryFormatRemaining = _env->GetArrayLength(binaryFormat_ref) - binaryFormatOffset;
    binaryFormat_base = (GLenum *)
        _env->GetIntArrayElements(binaryFormat_ref, (jboolean *)0);
    binaryFormat = binaryFormat_base + binaryFormatOffset;

    if (!binary_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "binary == null";
        goto exit;
    }
    binary = (GLvoid *)getPointer(_env, binary_buf, (jarray*)&_array, &_binaryRemaining, &_bufferOffset);
    if (binary == NULL) {
        char * _binaryBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        binary = (GLvoid *) (_binaryBase + _bufferOffset);
    }
    glGetProgramBinary(
        (GLuint)program,
        (GLsizei)bufSize,
        (GLsizei *)length,
        (GLenum *)binaryFormat,
        (GLvoid *)binary
    );

exit:
    if (_array) {
        releasePointer(_env, _array, binary, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (binaryFormat_base) {
        _env->ReleaseIntArrayElements(binaryFormat_ref, (jint*)binaryFormat_base,
            _exception ? JNI_ABORT: 0);
    }
    if (length_base) {
        _env->ReleaseIntArrayElements(length_ref, (jint*)length_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetProgramBinary ( GLuint program, GLsizei bufSize, GLsizei *length, GLenum *binaryFormat, GLvoid *binary ) */
static void
android_glGetProgramBinary__IILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint program, jint bufSize, jobject length_buf, jobject binaryFormat_buf, jobject binary_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _lengthArray = (jintArray) 0;
    jint _lengthBufferOffset = (jint) 0;
    jintArray _binaryFormatArray = (jintArray) 0;
    jint _binaryFormatBufferOffset = (jint) 0;
    jintArray _binaryArray = (jintArray) 0;
    jint _binaryBufferOffset = (jint) 0;
    jint _lengthRemaining;
    GLsizei *length = (GLsizei *) 0;
    jint _binaryFormatRemaining;
    GLenum *binaryFormat = (GLenum *) 0;
    jint _binaryRemaining;
    GLvoid *binary = (GLvoid *) 0;

    if (length_buf) {
        length = (GLsizei *)getPointer(_env, length_buf, (jarray*)&_lengthArray, &_lengthRemaining, &_lengthBufferOffset);
    }
    if (!binaryFormat_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "binaryFormat == null";
        goto exit;
    }
    binaryFormat = (GLenum *)getPointer(_env, binaryFormat_buf, (jarray*)&_binaryFormatArray, &_binaryFormatRemaining, &_binaryFormatBufferOffset);
    if (!binary_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "binary == null";
        goto exit;
    }
    binary = (GLvoid *)getPointer(_env, binary_buf, (jarray*)&_binaryArray, &_binaryRemaining, &_binaryBufferOffset);
    if (length_buf && length == NULL) {
        char * _lengthBase = (char *)_env->GetIntArrayElements(_lengthArray, (jboolean *) 0);
        length = (GLsizei *) (_lengthBase + _lengthBufferOffset);
    }
    if (binaryFormat == NULL) {
        char * _binaryFormatBase = (char *)_env->GetIntArrayElements(_binaryFormatArray, (jboolean *) 0);
        binaryFormat = (GLenum *) (_binaryFormatBase + _binaryFormatBufferOffset);
    }
    if (binary == NULL) {
        char * _binaryBase = (char *)_env->GetPrimitiveArrayCritical(_binaryArray, (jboolean *) 0);
        binary = (GLvoid *) (_binaryBase + _binaryBufferOffset);
    }
    glGetProgramBinary(
        (GLuint)program,
        (GLsizei)bufSize,
        (GLsizei *)length,
        (GLenum *)binaryFormat,
        (GLvoid *)binary
    );

exit:
    if (_binaryArray) {
        releasePointer(_env, _binaryArray, binary, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_binaryFormatArray) {
        _env->ReleaseIntArrayElements(_binaryFormatArray, (jint*)binaryFormat, _exception ? JNI_ABORT : 0);
    }
    if (_lengthArray) {
        _env->ReleaseIntArrayElements(_lengthArray, (jint*)length, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glProgramBinary ( GLuint program, GLenum binaryFormat, const GLvoid *binary, GLsizei length ) */
static void
android_glProgramBinary__IILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint program, jint binaryFormat, jobject binary_buf, jint length) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *binary = (GLvoid *) 0;

    if (!binary_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "binary == null";
        goto exit;
    }
    binary = (GLvoid *)getPointer(_env, binary_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (binary == NULL) {
        char * _binaryBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        binary = (GLvoid *) (_binaryBase + _bufferOffset);
    }
    glProgramBinary(
        (GLuint)program,
        (GLenum)binaryFormat,
        (GLvoid *)binary,
        (GLsizei)length
    );

exit:
    if (_array) {
        releasePointer(_env, _array, binary, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glProgramParameteri ( GLuint program, GLenum pname, GLint value ) */
static void
android_glProgramParameteri__III
  (JNIEnv *_env, jobject _this, jint program, jint pname, jint value) {
    glProgramParameteri(
        (GLuint)program,
        (GLenum)pname,
        (GLint)value
    );
}

/* void glInvalidateFramebuffer ( GLenum target, GLsizei numAttachments, const GLenum *attachments ) */
static void
android_glInvalidateFramebuffer__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint numAttachments, jintArray attachments_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLenum *attachments_base = (GLenum *) 0;
    jint _remaining;
    GLenum *attachments = (GLenum *) 0;

    if (!attachments_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attachments == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(attachments_ref) - offset;
    attachments_base = (GLenum *)
        _env->GetIntArrayElements(attachments_ref, (jboolean *)0);
    attachments = attachments_base + offset;

    glInvalidateFramebuffer(
        (GLenum)target,
        (GLsizei)numAttachments,
        (GLenum *)attachments
    );

exit:
    if (attachments_base) {
        _env->ReleaseIntArrayElements(attachments_ref, (jint*)attachments_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glInvalidateFramebuffer ( GLenum target, GLsizei numAttachments, const GLenum *attachments ) */
static void
android_glInvalidateFramebuffer__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint numAttachments, jobject attachments_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLenum *attachments = (GLenum *) 0;

    if (!attachments_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attachments == null";
        goto exit;
    }
    attachments = (GLenum *)getPointer(_env, attachments_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (attachments == NULL) {
        char * _attachmentsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        attachments = (GLenum *) (_attachmentsBase + _bufferOffset);
    }
    glInvalidateFramebuffer(
        (GLenum)target,
        (GLsizei)numAttachments,
        (GLenum *)attachments
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)attachments, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glInvalidateSubFramebuffer ( GLenum target, GLsizei numAttachments, const GLenum *attachments, GLint x, GLint y, GLsizei width, GLsizei height ) */
static void
android_glInvalidateSubFramebuffer__II_3IIIIII
  (JNIEnv *_env, jobject _this, jint target, jint numAttachments, jintArray attachments_ref, jint offset, jint x, jint y, jint width, jint height) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLenum *attachments_base = (GLenum *) 0;
    jint _remaining;
    GLenum *attachments = (GLenum *) 0;

    if (!attachments_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attachments == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(attachments_ref) - offset;
    attachments_base = (GLenum *)
        _env->GetIntArrayElements(attachments_ref, (jboolean *)0);
    attachments = attachments_base + offset;

    glInvalidateSubFramebuffer(
        (GLenum)target,
        (GLsizei)numAttachments,
        (GLenum *)attachments,
        (GLint)x,
        (GLint)y,
        (GLsizei)width,
        (GLsizei)height
    );

exit:
    if (attachments_base) {
        _env->ReleaseIntArrayElements(attachments_ref, (jint*)attachments_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glInvalidateSubFramebuffer ( GLenum target, GLsizei numAttachments, const GLenum *attachments, GLint x, GLint y, GLsizei width, GLsizei height ) */
static void
android_glInvalidateSubFramebuffer__IILjava_nio_IntBuffer_2IIII
  (JNIEnv *_env, jobject _this, jint target, jint numAttachments, jobject attachments_buf, jint x, jint y, jint width, jint height) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLenum *attachments = (GLenum *) 0;

    if (!attachments_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "attachments == null";
        goto exit;
    }
    attachments = (GLenum *)getPointer(_env, attachments_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (attachments == NULL) {
        char * _attachmentsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        attachments = (GLenum *) (_attachmentsBase + _bufferOffset);
    }
    glInvalidateSubFramebuffer(
        (GLenum)target,
        (GLsizei)numAttachments,
        (GLenum *)attachments,
        (GLint)x,
        (GLint)y,
        (GLsizei)width,
        (GLsizei)height
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)attachments, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexStorage2D ( GLenum target, GLsizei levels, GLenum internalformat, GLsizei width, GLsizei height ) */
static void
android_glTexStorage2D__IIIII
  (JNIEnv *_env, jobject _this, jint target, jint levels, jint internalformat, jint width, jint height) {
    glTexStorage2D(
        (GLenum)target,
        (GLsizei)levels,
        (GLenum)internalformat,
        (GLsizei)width,
        (GLsizei)height
    );
}

/* void glTexStorage3D ( GLenum target, GLsizei levels, GLenum internalformat, GLsizei width, GLsizei height, GLsizei depth ) */
static void
android_glTexStorage3D__IIIIII
  (JNIEnv *_env, jobject _this, jint target, jint levels, jint internalformat, jint width, jint height, jint depth) {
    glTexStorage3D(
        (GLenum)target,
        (GLsizei)levels,
        (GLenum)internalformat,
        (GLsizei)width,
        (GLsizei)height,
        (GLsizei)depth
    );
}

/* void glGetInternalformativ ( GLenum target, GLenum internalformat, GLenum pname, GLsizei bufSize, GLint *params ) */
static void
android_glGetInternalformativ__IIII_3II
  (JNIEnv *_env, jobject _this, jint target, jint internalformat, jint pname, jint bufSize, jintArray params_ref, jint offset) {
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
    params_base = (GLint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetInternalformativ(
        (GLenum)target,
        (GLenum)internalformat,
        (GLenum)pname,
        (GLsizei)bufSize,
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

/* void glGetInternalformativ ( GLenum target, GLenum internalformat, GLenum pname, GLsizei bufSize, GLint *params ) */
static void
android_glGetInternalformativ__IIIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint internalformat, jint pname, jint bufSize, jobject params_buf) {
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetInternalformativ(
        (GLenum)target,
        (GLenum)internalformat,
        (GLenum)pname,
        (GLsizei)bufSize,
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

/* void glReadPixels ( GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, GLint offset ) */
static void
android_glReadPixels__IIIIIII
  (JNIEnv *_env, jobject _this, jint x, jint y, jint width, jint height, jint format, jint type, jint offset) {
    glReadPixels(
        (GLint)x,
        (GLint)y,
        (GLsizei)width,
        (GLsizei)height,
        (GLenum)format,
        (GLenum)type,
        reinterpret_cast<GLvoid *>(offset)
    );
}

static const char *classPathName = "android/opengl/GLES30";

static const JNINativeMethod methods[] = {
{"_nativeClassInit", "()V", (void*)nativeClassInit },
{"glReadBuffer", "(I)V", (void *) android_glReadBuffer__I },
{"glDrawRangeElements", "(IIIIILjava/nio/Buffer;)V", (void *) android_glDrawRangeElements__IIIIILjava_nio_Buffer_2 },
{"glDrawRangeElements", "(IIIIII)V", (void *) android_glDrawRangeElements__IIIIII },
{"glTexImage3D", "(IIIIIIIIILjava/nio/Buffer;)V", (void *) android_glTexImage3D__IIIIIIIIILjava_nio_Buffer_2 },
{"glTexImage3D", "(IIIIIIIIII)V", (void *) android_glTexImage3D__IIIIIIIIII },
{"glTexSubImage3D", "(IIIIIIIIIILjava/nio/Buffer;)V", (void *) android_glTexSubImage3D__IIIIIIIIIILjava_nio_Buffer_2 },
{"glTexSubImage3D", "(IIIIIIIIIII)V", (void *) android_glTexSubImage3D__IIIIIIIIIII },
{"glCopyTexSubImage3D", "(IIIIIIIII)V", (void *) android_glCopyTexSubImage3D__IIIIIIIII },
{"glCompressedTexImage3D", "(IIIIIIIILjava/nio/Buffer;)V", (void *) android_glCompressedTexImage3D__IIIIIIIILjava_nio_Buffer_2 },
{"glCompressedTexImage3D", "(IIIIIIIII)V", (void *) android_glCompressedTexImage3D__IIIIIIIII },
{"glCompressedTexSubImage3D", "(IIIIIIIIIILjava/nio/Buffer;)V", (void *) android_glCompressedTexSubImage3D__IIIIIIIIIILjava_nio_Buffer_2 },
{"glCompressedTexSubImage3D", "(IIIIIIIIIII)V", (void *) android_glCompressedTexSubImage3D__IIIIIIIIIII },
{"glGenQueries", "(I[II)V", (void *) android_glGenQueries__I_3II },
{"glGenQueries", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenQueries__ILjava_nio_IntBuffer_2 },
{"glDeleteQueries", "(I[II)V", (void *) android_glDeleteQueries__I_3II },
{"glDeleteQueries", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteQueries__ILjava_nio_IntBuffer_2 },
{"glIsQuery", "(I)Z", (void *) android_glIsQuery__I },
{"glBeginQuery", "(II)V", (void *) android_glBeginQuery__II },
{"glEndQuery", "(I)V", (void *) android_glEndQuery__I },
{"glGetQueryiv", "(II[II)V", (void *) android_glGetQueryiv__II_3II },
{"glGetQueryiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetQueryiv__IILjava_nio_IntBuffer_2 },
{"glGetQueryObjectuiv", "(II[II)V", (void *) android_glGetQueryObjectuiv__II_3II },
{"glGetQueryObjectuiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetQueryObjectuiv__IILjava_nio_IntBuffer_2 },
{"glUnmapBuffer", "(I)Z", (void *) android_glUnmapBuffer__I },
{"glGetBufferPointerv", "(II)Ljava/nio/Buffer;", (void *) android_glGetBufferPointerv__II },
{"glDrawBuffers", "(I[II)V", (void *) android_glDrawBuffers__I_3II },
{"glDrawBuffers", "(ILjava/nio/IntBuffer;)V", (void *) android_glDrawBuffers__ILjava_nio_IntBuffer_2 },
{"glUniformMatrix2x3fv", "(IIZ[FI)V", (void *) android_glUniformMatrix2x3fv__IIZ_3FI },
{"glUniformMatrix2x3fv", "(IIZLjava/nio/FloatBuffer;)V", (void *) android_glUniformMatrix2x3fv__IIZLjava_nio_FloatBuffer_2 },
{"glUniformMatrix3x2fv", "(IIZ[FI)V", (void *) android_glUniformMatrix3x2fv__IIZ_3FI },
{"glUniformMatrix3x2fv", "(IIZLjava/nio/FloatBuffer;)V", (void *) android_glUniformMatrix3x2fv__IIZLjava_nio_FloatBuffer_2 },
{"glUniformMatrix2x4fv", "(IIZ[FI)V", (void *) android_glUniformMatrix2x4fv__IIZ_3FI },
{"glUniformMatrix2x4fv", "(IIZLjava/nio/FloatBuffer;)V", (void *) android_glUniformMatrix2x4fv__IIZLjava_nio_FloatBuffer_2 },
{"glUniformMatrix4x2fv", "(IIZ[FI)V", (void *) android_glUniformMatrix4x2fv__IIZ_3FI },
{"glUniformMatrix4x2fv", "(IIZLjava/nio/FloatBuffer;)V", (void *) android_glUniformMatrix4x2fv__IIZLjava_nio_FloatBuffer_2 },
{"glUniformMatrix3x4fv", "(IIZ[FI)V", (void *) android_glUniformMatrix3x4fv__IIZ_3FI },
{"glUniformMatrix3x4fv", "(IIZLjava/nio/FloatBuffer;)V", (void *) android_glUniformMatrix3x4fv__IIZLjava_nio_FloatBuffer_2 },
{"glUniformMatrix4x3fv", "(IIZ[FI)V", (void *) android_glUniformMatrix4x3fv__IIZ_3FI },
{"glUniformMatrix4x3fv", "(IIZLjava/nio/FloatBuffer;)V", (void *) android_glUniformMatrix4x3fv__IIZLjava_nio_FloatBuffer_2 },
{"glBlitFramebuffer", "(IIIIIIIIII)V", (void *) android_glBlitFramebuffer__IIIIIIIIII },
{"glRenderbufferStorageMultisample", "(IIIII)V", (void *) android_glRenderbufferStorageMultisample__IIIII },
{"glFramebufferTextureLayer", "(IIIII)V", (void *) android_glFramebufferTextureLayer__IIIII },
{"glMapBufferRange", "(IIII)Ljava/nio/Buffer;", (void *) android_glMapBufferRange__IIII },
{"glFlushMappedBufferRange", "(III)V", (void *) android_glFlushMappedBufferRange__III },
{"glBindVertexArray", "(I)V", (void *) android_glBindVertexArray__I },
{"glDeleteVertexArrays", "(I[II)V", (void *) android_glDeleteVertexArrays__I_3II },
{"glDeleteVertexArrays", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteVertexArrays__ILjava_nio_IntBuffer_2 },
{"glGenVertexArrays", "(I[II)V", (void *) android_glGenVertexArrays__I_3II },
{"glGenVertexArrays", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenVertexArrays__ILjava_nio_IntBuffer_2 },
{"glIsVertexArray", "(I)Z", (void *) android_glIsVertexArray__I },
{"glGetIntegeri_v", "(II[II)V", (void *) android_glGetIntegeri_v__II_3II },
{"glGetIntegeri_v", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetIntegeri_v__IILjava_nio_IntBuffer_2 },
{"glBeginTransformFeedback", "(I)V", (void *) android_glBeginTransformFeedback__I },
{"glEndTransformFeedback", "()V", (void *) android_glEndTransformFeedback__ },
{"glBindBufferRange", "(IIIII)V", (void *) android_glBindBufferRange__IIIII },
{"glBindBufferBase", "(III)V", (void *) android_glBindBufferBase__III },
{"glTransformFeedbackVaryings", "(I[Ljava/lang/String;I)V", (void *) android_glTransformFeedbackVaryings },
{"glGetTransformFeedbackVarying", "(III[II[II[II[BI)V", (void *) android_glGetTransformFeedbackVarying__III_3II_3II_3II_3BI },
{"glGetTransformFeedbackVarying", "(IIILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;B)V", (void *) android_glGetTransformFeedbackVarying__IIILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2B },
{"glGetTransformFeedbackVarying", "(IIILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/ByteBuffer;)V", (void *) android_glGetTransformFeedbackVarying__IIILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_ByteBuffer_2 },
{"glGetTransformFeedbackVarying", "(II[II[II)Ljava/lang/String;", (void *) android_glGetTransformFeedbackVarying1 },
{"glGetTransformFeedbackVarying", "(IILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)Ljava/lang/String;", (void *) android_glGetTransformFeedbackVarying2 },
{"glVertexAttribIPointerBounds", "(IIIILjava/nio/Buffer;I)V", (void *) android_glVertexAttribIPointerBounds__IIIILjava_nio_Buffer_2I },
{"glVertexAttribIPointer", "(IIIII)V", (void *) android_glVertexAttribIPointer__IIIII },
{"glGetVertexAttribIiv", "(II[II)V", (void *) android_glGetVertexAttribIiv__II_3II },
{"glGetVertexAttribIiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetVertexAttribIiv__IILjava_nio_IntBuffer_2 },
{"glGetVertexAttribIuiv", "(II[II)V", (void *) android_glGetVertexAttribIuiv__II_3II },
{"glGetVertexAttribIuiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetVertexAttribIuiv__IILjava_nio_IntBuffer_2 },
{"glVertexAttribI4i", "(IIIII)V", (void *) android_glVertexAttribI4i__IIIII },
{"glVertexAttribI4ui", "(IIIII)V", (void *) android_glVertexAttribI4ui__IIIII },
{"glVertexAttribI4iv", "(I[II)V", (void *) android_glVertexAttribI4iv__I_3II },
{"glVertexAttribI4iv", "(ILjava/nio/IntBuffer;)V", (void *) android_glVertexAttribI4iv__ILjava_nio_IntBuffer_2 },
{"glVertexAttribI4uiv", "(I[II)V", (void *) android_glVertexAttribI4uiv__I_3II },
{"glVertexAttribI4uiv", "(ILjava/nio/IntBuffer;)V", (void *) android_glVertexAttribI4uiv__ILjava_nio_IntBuffer_2 },
{"glGetUniformuiv", "(II[II)V", (void *) android_glGetUniformuiv__II_3II },
{"glGetUniformuiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetUniformuiv__IILjava_nio_IntBuffer_2 },
{"glGetFragDataLocation", "(ILjava/lang/String;)I", (void *) android_glGetFragDataLocation__ILjava_lang_String_2 },
{"glUniform1ui", "(II)V", (void *) android_glUniform1ui__II },
{"glUniform2ui", "(III)V", (void *) android_glUniform2ui__III },
{"glUniform3ui", "(IIII)V", (void *) android_glUniform3ui__IIII },
{"glUniform4ui", "(IIIII)V", (void *) android_glUniform4ui__IIIII },
{"glUniform1uiv", "(II[II)V", (void *) android_glUniform1uiv__II_3II },
{"glUniform1uiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glUniform1uiv__IILjava_nio_IntBuffer_2 },
{"glUniform2uiv", "(II[II)V", (void *) android_glUniform2uiv__II_3II },
{"glUniform2uiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glUniform2uiv__IILjava_nio_IntBuffer_2 },
{"glUniform3uiv", "(II[II)V", (void *) android_glUniform3uiv__II_3II },
{"glUniform3uiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glUniform3uiv__IILjava_nio_IntBuffer_2 },
{"glUniform4uiv", "(II[II)V", (void *) android_glUniform4uiv__II_3II },
{"glUniform4uiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glUniform4uiv__IILjava_nio_IntBuffer_2 },
{"glClearBufferiv", "(II[II)V", (void *) android_glClearBufferiv__II_3II },
{"glClearBufferiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glClearBufferiv__IILjava_nio_IntBuffer_2 },
{"glClearBufferuiv", "(II[II)V", (void *) android_glClearBufferuiv__II_3II },
{"glClearBufferuiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glClearBufferuiv__IILjava_nio_IntBuffer_2 },
{"glClearBufferfv", "(II[FI)V", (void *) android_glClearBufferfv__II_3FI },
{"glClearBufferfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glClearBufferfv__IILjava_nio_FloatBuffer_2 },
{"glClearBufferfi", "(IIFI)V", (void *) android_glClearBufferfi__IIFI },
{"glGetStringi", "(II)Ljava/lang/String;", (void *) android_glGetStringi__II },
{"glCopyBufferSubData", "(IIIII)V", (void *) android_glCopyBufferSubData__IIIII },
{"glGetUniformIndices", "(I[Ljava/lang/String;[II)V", (void *) android_glGetUniformIndices_array },
{"glGetUniformIndices", "(I[Ljava/lang/String;Ljava/nio/IntBuffer;)V", (void *) android_glGetUniformIndices_buffer },
{"glGetActiveUniformsiv", "(II[III[II)V", (void *) android_glGetActiveUniformsiv__II_3III_3II },
{"glGetActiveUniformsiv", "(IILjava/nio/IntBuffer;ILjava/nio/IntBuffer;)V", (void *) android_glGetActiveUniformsiv__IILjava_nio_IntBuffer_2ILjava_nio_IntBuffer_2 },
{"glGetUniformBlockIndex", "(ILjava/lang/String;)I", (void *) android_glGetUniformBlockIndex__ILjava_lang_String_2 },
{"glGetActiveUniformBlockiv", "(III[II)V", (void *) android_glGetActiveUniformBlockiv__III_3II },
{"glGetActiveUniformBlockiv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glGetActiveUniformBlockiv__IIILjava_nio_IntBuffer_2 },
{"glGetActiveUniformBlockName", "(III[II[BI)V", (void *) android_glGetActiveUniformBlockName_III_3II_3BI },
{"glGetActiveUniformBlockName", "(IILjava/nio/Buffer;Ljava/nio/Buffer;)V", (void *) android_glGetActiveUniformBlockName_IILjava_nio_Buffer_2Ljava_nio_Buffer_2 },
{"glGetActiveUniformBlockName", "(II)Ljava/lang/String;", (void *) android_glGetActiveUniformBlockName_II },
{"glUniformBlockBinding", "(III)V", (void *) android_glUniformBlockBinding__III },
{"glDrawArraysInstanced", "(IIII)V", (void *) android_glDrawArraysInstanced__IIII },
{"glDrawElementsInstanced", "(IIILjava/nio/Buffer;I)V", (void *) android_glDrawElementsInstanced__IIILjava_nio_Buffer_2I },
{"glDrawElementsInstanced", "(IIIII)V", (void *) android_glDrawElementsInstanced__IIIII },
{"glFenceSync", "(II)J", (void *) android_glFenceSync__II },
{"glIsSync", "(J)Z", (void *) android_glIsSync__J },
{"glDeleteSync", "(J)V", (void *) android_glDeleteSync__J },
{"glClientWaitSync", "(JIJ)I", (void *) android_glClientWaitSync__JIJ },
{"glWaitSync", "(JIJ)V", (void *) android_glWaitSync__JIJ },
{"glGetInteger64v", "(I[JI)V", (void *) android_glGetInteger64v__I_3JI },
{"glGetInteger64v", "(ILjava/nio/LongBuffer;)V", (void *) android_glGetInteger64v__ILjava_nio_LongBuffer_2 },
{"glGetSynciv", "(JII[II[II)V", (void *) android_glGetSynciv__JII_3II_3II },
{"glGetSynciv", "(JIILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)V", (void *) android_glGetSynciv__JIILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2 },
{"glGetInteger64i_v", "(II[JI)V", (void *) android_glGetInteger64i_v__II_3JI },
{"glGetInteger64i_v", "(IILjava/nio/LongBuffer;)V", (void *) android_glGetInteger64i_v__IILjava_nio_LongBuffer_2 },
{"glGetBufferParameteri64v", "(II[JI)V", (void *) android_glGetBufferParameteri64v__II_3JI },
{"glGetBufferParameteri64v", "(IILjava/nio/LongBuffer;)V", (void *) android_glGetBufferParameteri64v__IILjava_nio_LongBuffer_2 },
{"glGenSamplers", "(I[II)V", (void *) android_glGenSamplers__I_3II },
{"glGenSamplers", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenSamplers__ILjava_nio_IntBuffer_2 },
{"glDeleteSamplers", "(I[II)V", (void *) android_glDeleteSamplers__I_3II },
{"glDeleteSamplers", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteSamplers__ILjava_nio_IntBuffer_2 },
{"glIsSampler", "(I)Z", (void *) android_glIsSampler__I },
{"glBindSampler", "(II)V", (void *) android_glBindSampler__II },
{"glSamplerParameteri", "(III)V", (void *) android_glSamplerParameteri__III },
{"glSamplerParameteriv", "(II[II)V", (void *) android_glSamplerParameteriv__II_3II },
{"glSamplerParameteriv", "(IILjava/nio/IntBuffer;)V", (void *) android_glSamplerParameteriv__IILjava_nio_IntBuffer_2 },
{"glSamplerParameterf", "(IIF)V", (void *) android_glSamplerParameterf__IIF },
{"glSamplerParameterfv", "(II[FI)V", (void *) android_glSamplerParameterfv__II_3FI },
{"glSamplerParameterfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glSamplerParameterfv__IILjava_nio_FloatBuffer_2 },
{"glGetSamplerParameteriv", "(II[II)V", (void *) android_glGetSamplerParameteriv__II_3II },
{"glGetSamplerParameteriv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetSamplerParameteriv__IILjava_nio_IntBuffer_2 },
{"glGetSamplerParameterfv", "(II[FI)V", (void *) android_glGetSamplerParameterfv__II_3FI },
{"glGetSamplerParameterfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glGetSamplerParameterfv__IILjava_nio_FloatBuffer_2 },
{"glVertexAttribDivisor", "(II)V", (void *) android_glVertexAttribDivisor__II },
{"glBindTransformFeedback", "(II)V", (void *) android_glBindTransformFeedback__II },
{"glDeleteTransformFeedbacks", "(I[II)V", (void *) android_glDeleteTransformFeedbacks__I_3II },
{"glDeleteTransformFeedbacks", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteTransformFeedbacks__ILjava_nio_IntBuffer_2 },
{"glGenTransformFeedbacks", "(I[II)V", (void *) android_glGenTransformFeedbacks__I_3II },
{"glGenTransformFeedbacks", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenTransformFeedbacks__ILjava_nio_IntBuffer_2 },
{"glIsTransformFeedback", "(I)Z", (void *) android_glIsTransformFeedback__I },
{"glPauseTransformFeedback", "()V", (void *) android_glPauseTransformFeedback__ },
{"glResumeTransformFeedback", "()V", (void *) android_glResumeTransformFeedback__ },
{"glGetProgramBinary", "(II[II[IILjava/nio/Buffer;)V", (void *) android_glGetProgramBinary__II_3II_3IILjava_nio_Buffer_2 },
{"glGetProgramBinary", "(IILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/Buffer;)V", (void *) android_glGetProgramBinary__IILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_Buffer_2 },
{"glProgramBinary", "(IILjava/nio/Buffer;I)V", (void *) android_glProgramBinary__IILjava_nio_Buffer_2I },
{"glProgramParameteri", "(III)V", (void *) android_glProgramParameteri__III },
{"glInvalidateFramebuffer", "(II[II)V", (void *) android_glInvalidateFramebuffer__II_3II },
{"glInvalidateFramebuffer", "(IILjava/nio/IntBuffer;)V", (void *) android_glInvalidateFramebuffer__IILjava_nio_IntBuffer_2 },
{"glInvalidateSubFramebuffer", "(II[IIIIII)V", (void *) android_glInvalidateSubFramebuffer__II_3IIIIII },
{"glInvalidateSubFramebuffer", "(IILjava/nio/IntBuffer;IIII)V", (void *) android_glInvalidateSubFramebuffer__IILjava_nio_IntBuffer_2IIII },
{"glTexStorage2D", "(IIIII)V", (void *) android_glTexStorage2D__IIIII },
{"glTexStorage3D", "(IIIIII)V", (void *) android_glTexStorage3D__IIIIII },
{"glGetInternalformativ", "(IIII[II)V", (void *) android_glGetInternalformativ__IIII_3II },
{"glGetInternalformativ", "(IIIILjava/nio/IntBuffer;)V", (void *) android_glGetInternalformativ__IIIILjava_nio_IntBuffer_2 },
{"glReadPixels", "(IIIIIII)V", (void *) android_glReadPixels__IIIIIII },
};

int register_android_opengl_jni_GLES30(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));
    return err;
}

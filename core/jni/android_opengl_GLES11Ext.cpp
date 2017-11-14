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

static int initialized = 0;

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
/* void glBlendEquationSeparateOES ( GLenum modeRGB, GLenum modeAlpha ) */
static void
android_glBlendEquationSeparateOES__II
  (JNIEnv *_env, jobject _this, jint modeRGB, jint modeAlpha) {
    glBlendEquationSeparateOES(
        (GLenum)modeRGB,
        (GLenum)modeAlpha
    );
}

/* void glBlendFuncSeparateOES ( GLenum srcRGB, GLenum dstRGB, GLenum srcAlpha, GLenum dstAlpha ) */
static void
android_glBlendFuncSeparateOES__IIII
  (JNIEnv *_env, jobject _this, jint srcRGB, jint dstRGB, jint srcAlpha, jint dstAlpha) {
    glBlendFuncSeparateOES(
        (GLenum)srcRGB,
        (GLenum)dstRGB,
        (GLenum)srcAlpha,
        (GLenum)dstAlpha
    );
}

/* void glBlendEquationOES ( GLenum mode ) */
static void
android_glBlendEquationOES__I
  (JNIEnv *_env, jobject _this, jint mode) {
    glBlendEquationOES(
        (GLenum)mode
    );
}

/* void glDrawTexsOES ( GLshort x, GLshort y, GLshort z, GLshort width, GLshort height ) */
static void
android_glDrawTexsOES__SSSSS
  (JNIEnv *_env, jobject _this, jshort x, jshort y, jshort z, jshort width, jshort height) {
    glDrawTexsOES(
        (GLshort)x,
        (GLshort)y,
        (GLshort)z,
        (GLshort)width,
        (GLshort)height
    );
}

/* void glDrawTexiOES ( GLint x, GLint y, GLint z, GLint width, GLint height ) */
static void
android_glDrawTexiOES__IIIII
  (JNIEnv *_env, jobject _this, jint x, jint y, jint z, jint width, jint height) {
    glDrawTexiOES(
        (GLint)x,
        (GLint)y,
        (GLint)z,
        (GLint)width,
        (GLint)height
    );
}

/* void glDrawTexxOES ( GLfixed x, GLfixed y, GLfixed z, GLfixed width, GLfixed height ) */
static void
android_glDrawTexxOES__IIIII
  (JNIEnv *_env, jobject _this, jint x, jint y, jint z, jint width, jint height) {
    glDrawTexxOES(
        (GLfixed)x,
        (GLfixed)y,
        (GLfixed)z,
        (GLfixed)width,
        (GLfixed)height
    );
}

/* void glDrawTexsvOES ( const GLshort *coords ) */
static void
android_glDrawTexsvOES___3SI
  (JNIEnv *_env, jobject _this, jshortArray coords_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLshort *coords_base = (GLshort *) 0;
    jint _remaining;
    GLshort *coords = (GLshort *) 0;

    if (!coords_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "coords == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(coords_ref) - offset;
    if (_remaining < 5) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 5 < needed";
        goto exit;
    }
    coords_base = (GLshort *)
        _env->GetShortArrayElements(coords_ref, (jboolean *)0);
    coords = coords_base + offset;

    glDrawTexsvOES(
        (GLshort *)coords
    );

exit:
    if (coords_base) {
        _env->ReleaseShortArrayElements(coords_ref, (jshort*)coords_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDrawTexsvOES ( const GLshort *coords ) */
static void
android_glDrawTexsvOES__Ljava_nio_ShortBuffer_2
  (JNIEnv *_env, jobject _this, jobject coords_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jshortArray _array = (jshortArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLshort *coords = (GLshort *) 0;

    if (!coords_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "coords == null";
        goto exit;
    }
    coords = (GLshort *)getPointer(_env, coords_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 5) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 5 < needed";
        goto exit;
    }
    if (coords == NULL) {
        char * _coordsBase = (char *)_env->GetShortArrayElements(_array, (jboolean *) 0);
        coords = (GLshort *) (_coordsBase + _bufferOffset);
    }
    glDrawTexsvOES(
        (GLshort *)coords
    );

exit:
    if (_array) {
        _env->ReleaseShortArrayElements(_array, (jshort*)coords, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDrawTexivOES ( const GLint *coords ) */
static void
android_glDrawTexivOES___3II
  (JNIEnv *_env, jobject _this, jintArray coords_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint *coords_base = (GLint *) 0;
    jint _remaining;
    GLint *coords = (GLint *) 0;

    if (!coords_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "coords == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(coords_ref) - offset;
    if (_remaining < 5) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 5 < needed";
        goto exit;
    }
    coords_base = (GLint *)
        _env->GetIntArrayElements(coords_ref, (jboolean *)0);
    coords = coords_base + offset;

    glDrawTexivOES(
        (GLint *)coords
    );

exit:
    if (coords_base) {
        _env->ReleaseIntArrayElements(coords_ref, (jint*)coords_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDrawTexivOES ( const GLint *coords ) */
static void
android_glDrawTexivOES__Ljava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jobject coords_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *coords = (GLint *) 0;

    if (!coords_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "coords == null";
        goto exit;
    }
    coords = (GLint *)getPointer(_env, coords_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 5) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 5 < needed";
        goto exit;
    }
    if (coords == NULL) {
        char * _coordsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        coords = (GLint *) (_coordsBase + _bufferOffset);
    }
    glDrawTexivOES(
        (GLint *)coords
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)coords, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDrawTexxvOES ( const GLfixed *coords ) */
static void
android_glDrawTexxvOES___3II
  (JNIEnv *_env, jobject _this, jintArray coords_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfixed *coords_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *coords = (GLfixed *) 0;

    if (!coords_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "coords == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(coords_ref) - offset;
    if (_remaining < 5) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 5 < needed";
        goto exit;
    }
    coords_base = (GLfixed *)
        _env->GetIntArrayElements(coords_ref, (jboolean *)0);
    coords = coords_base + offset;

    glDrawTexxvOES(
        (GLfixed *)coords
    );

exit:
    if (coords_base) {
        _env->ReleaseIntArrayElements(coords_ref, (jint*)coords_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDrawTexxvOES ( const GLfixed *coords ) */
static void
android_glDrawTexxvOES__Ljava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jobject coords_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfixed *coords = (GLfixed *) 0;

    if (!coords_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "coords == null";
        goto exit;
    }
    coords = (GLfixed *)getPointer(_env, coords_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 5) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 5 < needed";
        goto exit;
    }
    if (coords == NULL) {
        char * _coordsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        coords = (GLfixed *) (_coordsBase + _bufferOffset);
    }
    glDrawTexxvOES(
        (GLfixed *)coords
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)coords, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDrawTexfOES ( GLfloat x, GLfloat y, GLfloat z, GLfloat width, GLfloat height ) */
static void
android_glDrawTexfOES__FFFFF
  (JNIEnv *_env, jobject _this, jfloat x, jfloat y, jfloat z, jfloat width, jfloat height) {
    glDrawTexfOES(
        (GLfloat)x,
        (GLfloat)y,
        (GLfloat)z,
        (GLfloat)width,
        (GLfloat)height
    );
}

/* void glDrawTexfvOES ( const GLfloat *coords ) */
static void
android_glDrawTexfvOES___3FI
  (JNIEnv *_env, jobject _this, jfloatArray coords_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *coords_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *coords = (GLfloat *) 0;

    if (!coords_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "coords == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(coords_ref) - offset;
    if (_remaining < 5) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 5 < needed";
        goto exit;
    }
    coords_base = (GLfloat *)
        _env->GetFloatArrayElements(coords_ref, (jboolean *)0);
    coords = coords_base + offset;

    glDrawTexfvOES(
        (GLfloat *)coords
    );

exit:
    if (coords_base) {
        _env->ReleaseFloatArrayElements(coords_ref, (jfloat*)coords_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDrawTexfvOES ( const GLfloat *coords ) */
static void
android_glDrawTexfvOES__Ljava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jobject coords_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *coords = (GLfloat *) 0;

    if (!coords_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "coords == null";
        goto exit;
    }
    coords = (GLfloat *)getPointer(_env, coords_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < 5) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 5 < needed";
        goto exit;
    }
    if (coords == NULL) {
        char * _coordsBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        coords = (GLfloat *) (_coordsBase + _bufferOffset);
    }
    glDrawTexfvOES(
        (GLfloat *)coords
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)coords, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glEGLImageTargetTexture2DOES ( GLenum target, GLeglImageOES image ) */
static void
android_glEGLImageTargetTexture2DOES__ILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jobject image_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLeglImageOES image = (GLeglImageOES) 0;

    if (!image_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "image == null";
        goto exit;
    }
    image = (GLeglImageOES)getPointer(_env, image_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (image == NULL) {
        char * _imageBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        image = (GLeglImageOES) (_imageBase + _bufferOffset);
    }
    glEGLImageTargetTexture2DOES(
        (GLenum)target,
        (GLeglImageOES)image
    );

exit:
    if (_array) {
        releasePointer(_env, _array, image, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glEGLImageTargetRenderbufferStorageOES ( GLenum target, GLeglImageOES image ) */
static void
android_glEGLImageTargetRenderbufferStorageOES__ILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jobject image_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLeglImageOES image = (GLeglImageOES) 0;

    if (!image_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "image == null";
        goto exit;
    }
    image = (GLeglImageOES)getPointer(_env, image_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (image == NULL) {
        char * _imageBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        image = (GLeglImageOES) (_imageBase + _bufferOffset);
    }
    glEGLImageTargetRenderbufferStorageOES(
        (GLenum)target,
        (GLeglImageOES)image
    );

exit:
    if (_array) {
        releasePointer(_env, _array, image, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glAlphaFuncxOES ( GLenum func, GLclampx ref ) */
static void
android_glAlphaFuncxOES__II
  (JNIEnv *_env, jobject _this, jint func, jint ref) {
    glAlphaFuncxOES(
        (GLenum)func,
        (GLclampx)ref
    );
}

/* void glClearColorxOES ( GLclampx red, GLclampx green, GLclampx blue, GLclampx alpha ) */
static void
android_glClearColorxOES__IIII
  (JNIEnv *_env, jobject _this, jint red, jint green, jint blue, jint alpha) {
    glClearColorxOES(
        (GLclampx)red,
        (GLclampx)green,
        (GLclampx)blue,
        (GLclampx)alpha
    );
}

/* void glClearDepthxOES ( GLclampx depth ) */
static void
android_glClearDepthxOES__I
  (JNIEnv *_env, jobject _this, jint depth) {
    glClearDepthxOES(
        (GLclampx)depth
    );
}

/* void glClipPlanexOES ( GLenum plane, const GLfixed *equation ) */
static void
android_glClipPlanexOES__I_3II
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

    glClipPlanexOES(
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

/* void glClipPlanexOES ( GLenum plane, const GLfixed *equation ) */
static void
android_glClipPlanexOES__ILjava_nio_IntBuffer_2
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
    glClipPlanexOES(
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

/* void glColor4xOES ( GLfixed red, GLfixed green, GLfixed blue, GLfixed alpha ) */
static void
android_glColor4xOES__IIII
  (JNIEnv *_env, jobject _this, jint red, jint green, jint blue, jint alpha) {
    glColor4xOES(
        (GLfixed)red,
        (GLfixed)green,
        (GLfixed)blue,
        (GLfixed)alpha
    );
}

/* void glDepthRangexOES ( GLclampx zNear, GLclampx zFar ) */
static void
android_glDepthRangexOES__II
  (JNIEnv *_env, jobject _this, jint zNear, jint zFar) {
    glDepthRangexOES(
        (GLclampx)zNear,
        (GLclampx)zFar
    );
}

/* void glFogxOES ( GLenum pname, GLfixed param ) */
static void
android_glFogxOES__II
  (JNIEnv *_env, jobject _this, jint pname, jint param) {
    glFogxOES(
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glFogxvOES ( GLenum pname, const GLfixed *params ) */
static void
android_glFogxvOES__I_3II
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

    glFogxvOES(
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

/* void glFogxvOES ( GLenum pname, const GLfixed *params ) */
static void
android_glFogxvOES__ILjava_nio_IntBuffer_2
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
    glFogxvOES(
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

/* void glFrustumxOES ( GLfixed left, GLfixed right, GLfixed bottom, GLfixed top, GLfixed zNear, GLfixed zFar ) */
static void
android_glFrustumxOES__IIIIII
  (JNIEnv *_env, jobject _this, jint left, jint right, jint bottom, jint top, jint zNear, jint zFar) {
    glFrustumxOES(
        (GLfixed)left,
        (GLfixed)right,
        (GLfixed)bottom,
        (GLfixed)top,
        (GLfixed)zNear,
        (GLfixed)zFar
    );
}

/* void glGetClipPlanexOES ( GLenum pname, GLfixed *eqn ) */
static void
android_glGetClipPlanexOES__I_3II
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

    glGetClipPlanexOES(
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

/* void glGetClipPlanexOES ( GLenum pname, GLfixed *eqn ) */
static void
android_glGetClipPlanexOES__ILjava_nio_IntBuffer_2
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
    glGetClipPlanexOES(
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

/* void glGetFixedvOES ( GLenum pname, GLfixed *params ) */
static void
android_glGetFixedvOES__I_3II
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

    glGetFixedvOES(
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

/* void glGetFixedvOES ( GLenum pname, GLfixed *params ) */
static void
android_glGetFixedvOES__ILjava_nio_IntBuffer_2
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
    glGetFixedvOES(
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

/* void glGetLightxvOES ( GLenum light, GLenum pname, GLfixed *params ) */
static void
android_glGetLightxvOES__II_3II
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
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetLightxvOES(
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

/* void glGetLightxvOES ( GLenum light, GLenum pname, GLfixed *params ) */
static void
android_glGetLightxvOES__IILjava_nio_IntBuffer_2
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glGetLightxvOES(
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

/* void glGetMaterialxvOES ( GLenum face, GLenum pname, GLfixed *params ) */
static void
android_glGetMaterialxvOES__II_3II
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
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetMaterialxvOES(
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

/* void glGetMaterialxvOES ( GLenum face, GLenum pname, GLfixed *params ) */
static void
android_glGetMaterialxvOES__IILjava_nio_IntBuffer_2
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glGetMaterialxvOES(
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

/* void glGetTexEnvxvOES ( GLenum env, GLenum pname, GLfixed *params ) */
static void
android_glGetTexEnvxvOES__II_3II
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
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexEnvxvOES(
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

/* void glGetTexEnvxvOES ( GLenum env, GLenum pname, GLfixed *params ) */
static void
android_glGetTexEnvxvOES__IILjava_nio_IntBuffer_2
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glGetTexEnvxvOES(
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

/* void glGetTexParameterxvOES ( GLenum target, GLenum pname, GLfixed *params ) */
static void
android_glGetTexParameterxvOES__II_3II
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
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexParameterxvOES(
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

/* void glGetTexParameterxvOES ( GLenum target, GLenum pname, GLfixed *params ) */
static void
android_glGetTexParameterxvOES__IILjava_nio_IntBuffer_2
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glGetTexParameterxvOES(
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

/* void glLightModelxOES ( GLenum pname, GLfixed param ) */
static void
android_glLightModelxOES__II
  (JNIEnv *_env, jobject _this, jint pname, jint param) {
    glLightModelxOES(
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glLightModelxvOES ( GLenum pname, const GLfixed *params ) */
static void
android_glLightModelxvOES__I_3II
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

    glLightModelxvOES(
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

/* void glLightModelxvOES ( GLenum pname, const GLfixed *params ) */
static void
android_glLightModelxvOES__ILjava_nio_IntBuffer_2
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
    glLightModelxvOES(
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

/* void glLightxOES ( GLenum light, GLenum pname, GLfixed param ) */
static void
android_glLightxOES__III
  (JNIEnv *_env, jobject _this, jint light, jint pname, jint param) {
    glLightxOES(
        (GLenum)light,
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glLightxvOES ( GLenum light, GLenum pname, const GLfixed *params ) */
static void
android_glLightxvOES__II_3II
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
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glLightxvOES(
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

/* void glLightxvOES ( GLenum light, GLenum pname, const GLfixed *params ) */
static void
android_glLightxvOES__IILjava_nio_IntBuffer_2
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glLightxvOES(
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

/* void glLineWidthxOES ( GLfixed width ) */
static void
android_glLineWidthxOES__I
  (JNIEnv *_env, jobject _this, jint width) {
    glLineWidthxOES(
        (GLfixed)width
    );
}

/* void glLoadMatrixxOES ( const GLfixed *m ) */
static void
android_glLoadMatrixxOES___3II
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

    glLoadMatrixxOES(
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

/* void glLoadMatrixxOES ( const GLfixed *m ) */
static void
android_glLoadMatrixxOES__Ljava_nio_IntBuffer_2
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
    glLoadMatrixxOES(
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

/* void glMaterialxOES ( GLenum face, GLenum pname, GLfixed param ) */
static void
android_glMaterialxOES__III
  (JNIEnv *_env, jobject _this, jint face, jint pname, jint param) {
    glMaterialxOES(
        (GLenum)face,
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glMaterialxvOES ( GLenum face, GLenum pname, const GLfixed *params ) */
static void
android_glMaterialxvOES__II_3II
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
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glMaterialxvOES(
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

/* void glMaterialxvOES ( GLenum face, GLenum pname, const GLfixed *params ) */
static void
android_glMaterialxvOES__IILjava_nio_IntBuffer_2
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glMaterialxvOES(
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

/* void glMultMatrixxOES ( const GLfixed *m ) */
static void
android_glMultMatrixxOES___3II
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

    glMultMatrixxOES(
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

/* void glMultMatrixxOES ( const GLfixed *m ) */
static void
android_glMultMatrixxOES__Ljava_nio_IntBuffer_2
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
    glMultMatrixxOES(
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

/* void glMultiTexCoord4xOES ( GLenum target, GLfixed s, GLfixed t, GLfixed r, GLfixed q ) */
static void
android_glMultiTexCoord4xOES__IIIII
  (JNIEnv *_env, jobject _this, jint target, jint s, jint t, jint r, jint q) {
    glMultiTexCoord4xOES(
        (GLenum)target,
        (GLfixed)s,
        (GLfixed)t,
        (GLfixed)r,
        (GLfixed)q
    );
}

/* void glNormal3xOES ( GLfixed nx, GLfixed ny, GLfixed nz ) */
static void
android_glNormal3xOES__III
  (JNIEnv *_env, jobject _this, jint nx, jint ny, jint nz) {
    glNormal3xOES(
        (GLfixed)nx,
        (GLfixed)ny,
        (GLfixed)nz
    );
}

/* void glOrthoxOES ( GLfixed left, GLfixed right, GLfixed bottom, GLfixed top, GLfixed zNear, GLfixed zFar ) */
static void
android_glOrthoxOES__IIIIII
  (JNIEnv *_env, jobject _this, jint left, jint right, jint bottom, jint top, jint zNear, jint zFar) {
    glOrthoxOES(
        (GLfixed)left,
        (GLfixed)right,
        (GLfixed)bottom,
        (GLfixed)top,
        (GLfixed)zNear,
        (GLfixed)zFar
    );
}

/* void glPointParameterxOES ( GLenum pname, GLfixed param ) */
static void
android_glPointParameterxOES__II
  (JNIEnv *_env, jobject _this, jint pname, jint param) {
    glPointParameterxOES(
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glPointParameterxvOES ( GLenum pname, const GLfixed *params ) */
static void
android_glPointParameterxvOES__I_3II
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

    glPointParameterxvOES(
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

/* void glPointParameterxvOES ( GLenum pname, const GLfixed *params ) */
static void
android_glPointParameterxvOES__ILjava_nio_IntBuffer_2
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
    glPointParameterxvOES(
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

/* void glPointSizexOES ( GLfixed size ) */
static void
android_glPointSizexOES__I
  (JNIEnv *_env, jobject _this, jint size) {
    glPointSizexOES(
        (GLfixed)size
    );
}

/* void glPolygonOffsetxOES ( GLfixed factor, GLfixed units ) */
static void
android_glPolygonOffsetxOES__II
  (JNIEnv *_env, jobject _this, jint factor, jint units) {
    glPolygonOffsetxOES(
        (GLfixed)factor,
        (GLfixed)units
    );
}

/* void glRotatexOES ( GLfixed angle, GLfixed x, GLfixed y, GLfixed z ) */
static void
android_glRotatexOES__IIII
  (JNIEnv *_env, jobject _this, jint angle, jint x, jint y, jint z) {
    glRotatexOES(
        (GLfixed)angle,
        (GLfixed)x,
        (GLfixed)y,
        (GLfixed)z
    );
}

/* void glSampleCoveragexOES ( GLclampx value, GLboolean invert ) */
static void
android_glSampleCoveragexOES__IZ
  (JNIEnv *_env, jobject _this, jint value, jboolean invert) {
    glSampleCoveragexOES(
        (GLclampx)value,
        (GLboolean)invert
    );
}

/* void glScalexOES ( GLfixed x, GLfixed y, GLfixed z ) */
static void
android_glScalexOES__III
  (JNIEnv *_env, jobject _this, jint x, jint y, jint z) {
    glScalexOES(
        (GLfixed)x,
        (GLfixed)y,
        (GLfixed)z
    );
}

/* void glTexEnvxOES ( GLenum target, GLenum pname, GLfixed param ) */
static void
android_glTexEnvxOES__III
  (JNIEnv *_env, jobject _this, jint target, jint pname, jint param) {
    glTexEnvxOES(
        (GLenum)target,
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glTexEnvxvOES ( GLenum target, GLenum pname, const GLfixed *params ) */
static void
android_glTexEnvxvOES__II_3II
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
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glTexEnvxvOES(
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

/* void glTexEnvxvOES ( GLenum target, GLenum pname, const GLfixed *params ) */
static void
android_glTexEnvxvOES__IILjava_nio_IntBuffer_2
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glTexEnvxvOES(
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

/* void glTexParameterxOES ( GLenum target, GLenum pname, GLfixed param ) */
static void
android_glTexParameterxOES__III
  (JNIEnv *_env, jobject _this, jint target, jint pname, jint param) {
    glTexParameterxOES(
        (GLenum)target,
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glTexParameterxvOES ( GLenum target, GLenum pname, const GLfixed *params ) */
static void
android_glTexParameterxvOES__II_3II
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
    params_base = (GLfixed *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glTexParameterxvOES(
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

/* void glTexParameterxvOES ( GLenum target, GLenum pname, const GLfixed *params ) */
static void
android_glTexParameterxvOES__IILjava_nio_IntBuffer_2
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
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLfixed *) (_paramsBase + _bufferOffset);
    }
    glTexParameterxvOES(
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

/* void glTranslatexOES ( GLfixed x, GLfixed y, GLfixed z ) */
static void
android_glTranslatexOES__III
  (JNIEnv *_env, jobject _this, jint x, jint y, jint z) {
    glTranslatexOES(
        (GLfixed)x,
        (GLfixed)y,
        (GLfixed)z
    );
}

/* GLboolean glIsRenderbufferOES ( GLuint renderbuffer ) */
static jboolean
android_glIsRenderbufferOES__I
  (JNIEnv *_env, jobject _this, jint renderbuffer) {
    GLboolean _returnValue;
    _returnValue = glIsRenderbufferOES(
        (GLuint)renderbuffer
    );
    return (jboolean)_returnValue;
}

/* void glBindRenderbufferOES ( GLenum target, GLuint renderbuffer ) */
static void
android_glBindRenderbufferOES__II
  (JNIEnv *_env, jobject _this, jint target, jint renderbuffer) {
    glBindRenderbufferOES(
        (GLenum)target,
        (GLuint)renderbuffer
    );
}

/* void glDeleteRenderbuffersOES ( GLsizei n, const GLuint *renderbuffers ) */
static void
android_glDeleteRenderbuffersOES__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray renderbuffers_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *renderbuffers_base = (GLuint *) 0;
    jint _remaining;
    GLuint *renderbuffers = (GLuint *) 0;

    if (!renderbuffers_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "renderbuffers == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(renderbuffers_ref) - offset;
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < n < needed";
        goto exit;
    }
    renderbuffers_base = (GLuint *)
        _env->GetIntArrayElements(renderbuffers_ref, (jboolean *)0);
    renderbuffers = renderbuffers_base + offset;

    glDeleteRenderbuffersOES(
        (GLsizei)n,
        (GLuint *)renderbuffers
    );

exit:
    if (renderbuffers_base) {
        _env->ReleaseIntArrayElements(renderbuffers_ref, (jint*)renderbuffers_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteRenderbuffersOES ( GLsizei n, const GLuint *renderbuffers ) */
static void
android_glDeleteRenderbuffersOES__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject renderbuffers_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *renderbuffers = (GLuint *) 0;

    if (!renderbuffers_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "renderbuffers == null";
        goto exit;
    }
    renderbuffers = (GLuint *)getPointer(_env, renderbuffers_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (renderbuffers == NULL) {
        char * _renderbuffersBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        renderbuffers = (GLuint *) (_renderbuffersBase + _bufferOffset);
    }
    glDeleteRenderbuffersOES(
        (GLsizei)n,
        (GLuint *)renderbuffers
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)renderbuffers, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenRenderbuffersOES ( GLsizei n, GLuint *renderbuffers ) */
static void
android_glGenRenderbuffersOES__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray renderbuffers_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *renderbuffers_base = (GLuint *) 0;
    jint _remaining;
    GLuint *renderbuffers = (GLuint *) 0;

    if (!renderbuffers_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "renderbuffers == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(renderbuffers_ref) - offset;
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < n < needed";
        goto exit;
    }
    renderbuffers_base = (GLuint *)
        _env->GetIntArrayElements(renderbuffers_ref, (jboolean *)0);
    renderbuffers = renderbuffers_base + offset;

    glGenRenderbuffersOES(
        (GLsizei)n,
        (GLuint *)renderbuffers
    );

exit:
    if (renderbuffers_base) {
        _env->ReleaseIntArrayElements(renderbuffers_ref, (jint*)renderbuffers_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenRenderbuffersOES ( GLsizei n, GLuint *renderbuffers ) */
static void
android_glGenRenderbuffersOES__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject renderbuffers_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *renderbuffers = (GLuint *) 0;

    if (!renderbuffers_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "renderbuffers == null";
        goto exit;
    }
    renderbuffers = (GLuint *)getPointer(_env, renderbuffers_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (renderbuffers == NULL) {
        char * _renderbuffersBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        renderbuffers = (GLuint *) (_renderbuffersBase + _bufferOffset);
    }
    glGenRenderbuffersOES(
        (GLsizei)n,
        (GLuint *)renderbuffers
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)renderbuffers, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glRenderbufferStorageOES ( GLenum target, GLenum internalformat, GLsizei width, GLsizei height ) */
static void
android_glRenderbufferStorageOES__IIII
  (JNIEnv *_env, jobject _this, jint target, jint internalformat, jint width, jint height) {
    glRenderbufferStorageOES(
        (GLenum)target,
        (GLenum)internalformat,
        (GLsizei)width,
        (GLsizei)height
    );
}

/* void glGetRenderbufferParameterivOES ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetRenderbufferParameterivOES__II_3II
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

    glGetRenderbufferParameterivOES(
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

/* void glGetRenderbufferParameterivOES ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetRenderbufferParameterivOES__IILjava_nio_IntBuffer_2
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
    glGetRenderbufferParameterivOES(
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

/* GLboolean glIsFramebufferOES ( GLuint framebuffer ) */
static jboolean
android_glIsFramebufferOES__I
  (JNIEnv *_env, jobject _this, jint framebuffer) {
    GLboolean _returnValue;
    _returnValue = glIsFramebufferOES(
        (GLuint)framebuffer
    );
    return (jboolean)_returnValue;
}

/* void glBindFramebufferOES ( GLenum target, GLuint framebuffer ) */
static void
android_glBindFramebufferOES__II
  (JNIEnv *_env, jobject _this, jint target, jint framebuffer) {
    glBindFramebufferOES(
        (GLenum)target,
        (GLuint)framebuffer
    );
}

/* void glDeleteFramebuffersOES ( GLsizei n, const GLuint *framebuffers ) */
static void
android_glDeleteFramebuffersOES__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray framebuffers_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *framebuffers_base = (GLuint *) 0;
    jint _remaining;
    GLuint *framebuffers = (GLuint *) 0;

    if (!framebuffers_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "framebuffers == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(framebuffers_ref) - offset;
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < n < needed";
        goto exit;
    }
    framebuffers_base = (GLuint *)
        _env->GetIntArrayElements(framebuffers_ref, (jboolean *)0);
    framebuffers = framebuffers_base + offset;

    glDeleteFramebuffersOES(
        (GLsizei)n,
        (GLuint *)framebuffers
    );

exit:
    if (framebuffers_base) {
        _env->ReleaseIntArrayElements(framebuffers_ref, (jint*)framebuffers_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteFramebuffersOES ( GLsizei n, const GLuint *framebuffers ) */
static void
android_glDeleteFramebuffersOES__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject framebuffers_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *framebuffers = (GLuint *) 0;

    if (!framebuffers_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "framebuffers == null";
        goto exit;
    }
    framebuffers = (GLuint *)getPointer(_env, framebuffers_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (framebuffers == NULL) {
        char * _framebuffersBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        framebuffers = (GLuint *) (_framebuffersBase + _bufferOffset);
    }
    glDeleteFramebuffersOES(
        (GLsizei)n,
        (GLuint *)framebuffers
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)framebuffers, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenFramebuffersOES ( GLsizei n, GLuint *framebuffers ) */
static void
android_glGenFramebuffersOES__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray framebuffers_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *framebuffers_base = (GLuint *) 0;
    jint _remaining;
    GLuint *framebuffers = (GLuint *) 0;

    if (!framebuffers_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "framebuffers == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(framebuffers_ref) - offset;
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < n < needed";
        goto exit;
    }
    framebuffers_base = (GLuint *)
        _env->GetIntArrayElements(framebuffers_ref, (jboolean *)0);
    framebuffers = framebuffers_base + offset;

    glGenFramebuffersOES(
        (GLsizei)n,
        (GLuint *)framebuffers
    );

exit:
    if (framebuffers_base) {
        _env->ReleaseIntArrayElements(framebuffers_ref, (jint*)framebuffers_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenFramebuffersOES ( GLsizei n, GLuint *framebuffers ) */
static void
android_glGenFramebuffersOES__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject framebuffers_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *framebuffers = (GLuint *) 0;

    if (!framebuffers_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "framebuffers == null";
        goto exit;
    }
    framebuffers = (GLuint *)getPointer(_env, framebuffers_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (framebuffers == NULL) {
        char * _framebuffersBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        framebuffers = (GLuint *) (_framebuffersBase + _bufferOffset);
    }
    glGenFramebuffersOES(
        (GLsizei)n,
        (GLuint *)framebuffers
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)framebuffers, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLenum glCheckFramebufferStatusOES ( GLenum target ) */
static jint
android_glCheckFramebufferStatusOES__I
  (JNIEnv *_env, jobject _this, jint target) {
    GLenum _returnValue;
    _returnValue = glCheckFramebufferStatusOES(
        (GLenum)target
    );
    return (jint)_returnValue;
}

/* void glFramebufferRenderbufferOES ( GLenum target, GLenum attachment, GLenum renderbuffertarget, GLuint renderbuffer ) */
static void
android_glFramebufferRenderbufferOES__IIII
  (JNIEnv *_env, jobject _this, jint target, jint attachment, jint renderbuffertarget, jint renderbuffer) {
    glFramebufferRenderbufferOES(
        (GLenum)target,
        (GLenum)attachment,
        (GLenum)renderbuffertarget,
        (GLuint)renderbuffer
    );
}

/* void glFramebufferTexture2DOES ( GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level ) */
static void
android_glFramebufferTexture2DOES__IIIII
  (JNIEnv *_env, jobject _this, jint target, jint attachment, jint textarget, jint texture, jint level) {
    glFramebufferTexture2DOES(
        (GLenum)target,
        (GLenum)attachment,
        (GLenum)textarget,
        (GLuint)texture,
        (GLint)level
    );
}

/* void glGetFramebufferAttachmentParameterivOES ( GLenum target, GLenum attachment, GLenum pname, GLint *params ) */
static void
android_glGetFramebufferAttachmentParameterivOES__III_3II
  (JNIEnv *_env, jobject _this, jint target, jint attachment, jint pname, jintArray params_ref, jint offset) {
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

    glGetFramebufferAttachmentParameterivOES(
        (GLenum)target,
        (GLenum)attachment,
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

/* void glGetFramebufferAttachmentParameterivOES ( GLenum target, GLenum attachment, GLenum pname, GLint *params ) */
static void
android_glGetFramebufferAttachmentParameterivOES__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint attachment, jint pname, jobject params_buf) {
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
    glGetFramebufferAttachmentParameterivOES(
        (GLenum)target,
        (GLenum)attachment,
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

/* void glGenerateMipmapOES ( GLenum target ) */
static void
android_glGenerateMipmapOES__I
  (JNIEnv *_env, jobject _this, jint target) {
    glGenerateMipmapOES(
        (GLenum)target
    );
}

/* void glCurrentPaletteMatrixOES ( GLuint matrixpaletteindex ) */
static void
android_glCurrentPaletteMatrixOES__I
  (JNIEnv *_env, jobject _this, jint matrixpaletteindex) {
    glCurrentPaletteMatrixOES(
        (GLuint)matrixpaletteindex
    );
}

/* void glLoadPaletteFromModelViewMatrixOES ( void ) */
static void
android_glLoadPaletteFromModelViewMatrixOES__
  (JNIEnv *_env, jobject _this) {
    glLoadPaletteFromModelViewMatrixOES();
}

/* void glMatrixIndexPointerOES ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer ) */
static void
android_glMatrixIndexPointerOESBounds__IIILjava_nio_Buffer_2I
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
    glMatrixIndexPointerOESBounds(
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

/* void glWeightPointerOES ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer ) */
static void
android_glWeightPointerOESBounds__IIILjava_nio_Buffer_2I
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
    glWeightPointerOESBounds(
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

/* void glDepthRangefOES ( GLclampf zNear, GLclampf zFar ) */
static void
android_glDepthRangefOES__FF
  (JNIEnv *_env, jobject _this, jfloat zNear, jfloat zFar) {
    glDepthRangefOES(
        (GLclampf)zNear,
        (GLclampf)zFar
    );
}

/* void glFrustumfOES ( GLfloat left, GLfloat right, GLfloat bottom, GLfloat top, GLfloat zNear, GLfloat zFar ) */
static void
android_glFrustumfOES__FFFFFF
  (JNIEnv *_env, jobject _this, jfloat left, jfloat right, jfloat bottom, jfloat top, jfloat zNear, jfloat zFar) {
    glFrustumfOES(
        (GLfloat)left,
        (GLfloat)right,
        (GLfloat)bottom,
        (GLfloat)top,
        (GLfloat)zNear,
        (GLfloat)zFar
    );
}

/* void glOrthofOES ( GLfloat left, GLfloat right, GLfloat bottom, GLfloat top, GLfloat zNear, GLfloat zFar ) */
static void
android_glOrthofOES__FFFFFF
  (JNIEnv *_env, jobject _this, jfloat left, jfloat right, jfloat bottom, jfloat top, jfloat zNear, jfloat zFar) {
    glOrthofOES(
        (GLfloat)left,
        (GLfloat)right,
        (GLfloat)bottom,
        (GLfloat)top,
        (GLfloat)zNear,
        (GLfloat)zFar
    );
}

/* void glClipPlanefOES ( GLenum plane, const GLfloat *equation ) */
static void
android_glClipPlanefOES__I_3FI
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

    glClipPlanefOES(
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

/* void glClipPlanefOES ( GLenum plane, const GLfloat *equation ) */
static void
android_glClipPlanefOES__ILjava_nio_FloatBuffer_2
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
    glClipPlanefOES(
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

/* void glGetClipPlanefOES ( GLenum pname, GLfloat *eqn ) */
static void
android_glGetClipPlanefOES__I_3FI
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

    glGetClipPlanefOES(
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

/* void glGetClipPlanefOES ( GLenum pname, GLfloat *eqn ) */
static void
android_glGetClipPlanefOES__ILjava_nio_FloatBuffer_2
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
    glGetClipPlanefOES(
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

/* void glClearDepthfOES ( GLclampf depth ) */
static void
android_glClearDepthfOES__F
  (JNIEnv *_env, jobject _this, jfloat depth) {
    glClearDepthfOES(
        (GLclampf)depth
    );
}

/* void glTexGenfOES ( GLenum coord, GLenum pname, GLfloat param ) */
static void
android_glTexGenfOES__IIF
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jfloat param) {
    glTexGenfOES(
        (GLenum)coord,
        (GLenum)pname,
        (GLfloat)param
    );
}

/* void glTexGenfvOES ( GLenum coord, GLenum pname, const GLfloat *params ) */
static void
android_glTexGenfvOES__II_3FI
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jfloatArray params_ref, jint offset) {
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

    glTexGenfvOES(
        (GLenum)coord,
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

/* void glTexGenfvOES ( GLenum coord, GLenum pname, const GLfloat *params ) */
static void
android_glTexGenfvOES__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jobject params_buf) {
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
    glTexGenfvOES(
        (GLenum)coord,
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

/* void glTexGeniOES ( GLenum coord, GLenum pname, GLint param ) */
static void
android_glTexGeniOES__III
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jint param) {
    glTexGeniOES(
        (GLenum)coord,
        (GLenum)pname,
        (GLint)param
    );
}

/* void glTexGenivOES ( GLenum coord, GLenum pname, const GLint *params ) */
static void
android_glTexGenivOES__II_3II
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jintArray params_ref, jint offset) {
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

    glTexGenivOES(
        (GLenum)coord,
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

/* void glTexGenivOES ( GLenum coord, GLenum pname, const GLint *params ) */
static void
android_glTexGenivOES__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jobject params_buf) {
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
    glTexGenivOES(
        (GLenum)coord,
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

/* void glTexGenxOES ( GLenum coord, GLenum pname, GLfixed param ) */
static void
android_glTexGenxOES__III
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jint param) {
    glTexGenxOES(
        (GLenum)coord,
        (GLenum)pname,
        (GLfixed)param
    );
}

/* void glTexGenxvOES ( GLenum coord, GLenum pname, const GLfixed *params ) */
static void
android_glTexGenxvOES__II_3II
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jintArray params_ref, jint offset) {
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

    glTexGenxvOES(
        (GLenum)coord,
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

/* void glTexGenxvOES ( GLenum coord, GLenum pname, const GLfixed *params ) */
static void
android_glTexGenxvOES__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jobject params_buf) {
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
    glTexGenxvOES(
        (GLenum)coord,
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

/* void glGetTexGenfvOES ( GLenum coord, GLenum pname, GLfloat *params ) */
static void
android_glGetTexGenfvOES__II_3FI
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jfloatArray params_ref, jint offset) {
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

    glGetTexGenfvOES(
        (GLenum)coord,
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

/* void glGetTexGenfvOES ( GLenum coord, GLenum pname, GLfloat *params ) */
static void
android_glGetTexGenfvOES__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jobject params_buf) {
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
    glGetTexGenfvOES(
        (GLenum)coord,
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

/* void glGetTexGenivOES ( GLenum coord, GLenum pname, GLint *params ) */
static void
android_glGetTexGenivOES__II_3II
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jintArray params_ref, jint offset) {
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

    glGetTexGenivOES(
        (GLenum)coord,
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

/* void glGetTexGenivOES ( GLenum coord, GLenum pname, GLint *params ) */
static void
android_glGetTexGenivOES__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jobject params_buf) {
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
    glGetTexGenivOES(
        (GLenum)coord,
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

/* void glGetTexGenxvOES ( GLenum coord, GLenum pname, GLfixed *params ) */
static void
android_glGetTexGenxvOES__II_3II
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jintArray params_ref, jint offset) {
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

    glGetTexGenxvOES(
        (GLenum)coord,
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

/* void glGetTexGenxvOES ( GLenum coord, GLenum pname, GLfixed *params ) */
static void
android_glGetTexGenxvOES__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint coord, jint pname, jobject params_buf) {
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
    glGetTexGenxvOES(
        (GLenum)coord,
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

static const char *classPathName = "android/opengl/GLES11Ext";

static const JNINativeMethod methods[] = {
{"_nativeClassInit", "()V", (void*)nativeClassInit },
{"glBlendEquationSeparateOES", "(II)V", (void *) android_glBlendEquationSeparateOES__II },
{"glBlendFuncSeparateOES", "(IIII)V", (void *) android_glBlendFuncSeparateOES__IIII },
{"glBlendEquationOES", "(I)V", (void *) android_glBlendEquationOES__I },
{"glDrawTexsOES", "(SSSSS)V", (void *) android_glDrawTexsOES__SSSSS },
{"glDrawTexiOES", "(IIIII)V", (void *) android_glDrawTexiOES__IIIII },
{"glDrawTexxOES", "(IIIII)V", (void *) android_glDrawTexxOES__IIIII },
{"glDrawTexsvOES", "([SI)V", (void *) android_glDrawTexsvOES___3SI },
{"glDrawTexsvOES", "(Ljava/nio/ShortBuffer;)V", (void *) android_glDrawTexsvOES__Ljava_nio_ShortBuffer_2 },
{"glDrawTexivOES", "([II)V", (void *) android_glDrawTexivOES___3II },
{"glDrawTexivOES", "(Ljava/nio/IntBuffer;)V", (void *) android_glDrawTexivOES__Ljava_nio_IntBuffer_2 },
{"glDrawTexxvOES", "([II)V", (void *) android_glDrawTexxvOES___3II },
{"glDrawTexxvOES", "(Ljava/nio/IntBuffer;)V", (void *) android_glDrawTexxvOES__Ljava_nio_IntBuffer_2 },
{"glDrawTexfOES", "(FFFFF)V", (void *) android_glDrawTexfOES__FFFFF },
{"glDrawTexfvOES", "([FI)V", (void *) android_glDrawTexfvOES___3FI },
{"glDrawTexfvOES", "(Ljava/nio/FloatBuffer;)V", (void *) android_glDrawTexfvOES__Ljava_nio_FloatBuffer_2 },
{"glEGLImageTargetTexture2DOES", "(ILjava/nio/Buffer;)V", (void *) android_glEGLImageTargetTexture2DOES__ILjava_nio_Buffer_2 },
{"glEGLImageTargetRenderbufferStorageOES", "(ILjava/nio/Buffer;)V", (void *) android_glEGLImageTargetRenderbufferStorageOES__ILjava_nio_Buffer_2 },
{"glAlphaFuncxOES", "(II)V", (void *) android_glAlphaFuncxOES__II },
{"glClearColorxOES", "(IIII)V", (void *) android_glClearColorxOES__IIII },
{"glClearDepthxOES", "(I)V", (void *) android_glClearDepthxOES__I },
{"glClipPlanexOES", "(I[II)V", (void *) android_glClipPlanexOES__I_3II },
{"glClipPlanexOES", "(ILjava/nio/IntBuffer;)V", (void *) android_glClipPlanexOES__ILjava_nio_IntBuffer_2 },
{"glColor4xOES", "(IIII)V", (void *) android_glColor4xOES__IIII },
{"glDepthRangexOES", "(II)V", (void *) android_glDepthRangexOES__II },
{"glFogxOES", "(II)V", (void *) android_glFogxOES__II },
{"glFogxvOES", "(I[II)V", (void *) android_glFogxvOES__I_3II },
{"glFogxvOES", "(ILjava/nio/IntBuffer;)V", (void *) android_glFogxvOES__ILjava_nio_IntBuffer_2 },
{"glFrustumxOES", "(IIIIII)V", (void *) android_glFrustumxOES__IIIIII },
{"glGetClipPlanexOES", "(I[II)V", (void *) android_glGetClipPlanexOES__I_3II },
{"glGetClipPlanexOES", "(ILjava/nio/IntBuffer;)V", (void *) android_glGetClipPlanexOES__ILjava_nio_IntBuffer_2 },
{"glGetFixedvOES", "(I[II)V", (void *) android_glGetFixedvOES__I_3II },
{"glGetFixedvOES", "(ILjava/nio/IntBuffer;)V", (void *) android_glGetFixedvOES__ILjava_nio_IntBuffer_2 },
{"glGetLightxvOES", "(II[II)V", (void *) android_glGetLightxvOES__II_3II },
{"glGetLightxvOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetLightxvOES__IILjava_nio_IntBuffer_2 },
{"glGetMaterialxvOES", "(II[II)V", (void *) android_glGetMaterialxvOES__II_3II },
{"glGetMaterialxvOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetMaterialxvOES__IILjava_nio_IntBuffer_2 },
{"glGetTexEnvxvOES", "(II[II)V", (void *) android_glGetTexEnvxvOES__II_3II },
{"glGetTexEnvxvOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexEnvxvOES__IILjava_nio_IntBuffer_2 },
{"glGetTexParameterxvOES", "(II[II)V", (void *) android_glGetTexParameterxvOES__II_3II },
{"glGetTexParameterxvOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexParameterxvOES__IILjava_nio_IntBuffer_2 },
{"glLightModelxOES", "(II)V", (void *) android_glLightModelxOES__II },
{"glLightModelxvOES", "(I[II)V", (void *) android_glLightModelxvOES__I_3II },
{"glLightModelxvOES", "(ILjava/nio/IntBuffer;)V", (void *) android_glLightModelxvOES__ILjava_nio_IntBuffer_2 },
{"glLightxOES", "(III)V", (void *) android_glLightxOES__III },
{"glLightxvOES", "(II[II)V", (void *) android_glLightxvOES__II_3II },
{"glLightxvOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glLightxvOES__IILjava_nio_IntBuffer_2 },
{"glLineWidthxOES", "(I)V", (void *) android_glLineWidthxOES__I },
{"glLoadMatrixxOES", "([II)V", (void *) android_glLoadMatrixxOES___3II },
{"glLoadMatrixxOES", "(Ljava/nio/IntBuffer;)V", (void *) android_glLoadMatrixxOES__Ljava_nio_IntBuffer_2 },
{"glMaterialxOES", "(III)V", (void *) android_glMaterialxOES__III },
{"glMaterialxvOES", "(II[II)V", (void *) android_glMaterialxvOES__II_3II },
{"glMaterialxvOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glMaterialxvOES__IILjava_nio_IntBuffer_2 },
{"glMultMatrixxOES", "([II)V", (void *) android_glMultMatrixxOES___3II },
{"glMultMatrixxOES", "(Ljava/nio/IntBuffer;)V", (void *) android_glMultMatrixxOES__Ljava_nio_IntBuffer_2 },
{"glMultiTexCoord4xOES", "(IIIII)V", (void *) android_glMultiTexCoord4xOES__IIIII },
{"glNormal3xOES", "(III)V", (void *) android_glNormal3xOES__III },
{"glOrthoxOES", "(IIIIII)V", (void *) android_glOrthoxOES__IIIIII },
{"glPointParameterxOES", "(II)V", (void *) android_glPointParameterxOES__II },
{"glPointParameterxvOES", "(I[II)V", (void *) android_glPointParameterxvOES__I_3II },
{"glPointParameterxvOES", "(ILjava/nio/IntBuffer;)V", (void *) android_glPointParameterxvOES__ILjava_nio_IntBuffer_2 },
{"glPointSizexOES", "(I)V", (void *) android_glPointSizexOES__I },
{"glPolygonOffsetxOES", "(II)V", (void *) android_glPolygonOffsetxOES__II },
{"glRotatexOES", "(IIII)V", (void *) android_glRotatexOES__IIII },
{"glSampleCoveragexOES", "(IZ)V", (void *) android_glSampleCoveragexOES__IZ },
{"glScalexOES", "(III)V", (void *) android_glScalexOES__III },
{"glTexEnvxOES", "(III)V", (void *) android_glTexEnvxOES__III },
{"glTexEnvxvOES", "(II[II)V", (void *) android_glTexEnvxvOES__II_3II },
{"glTexEnvxvOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexEnvxvOES__IILjava_nio_IntBuffer_2 },
{"glTexParameterxOES", "(III)V", (void *) android_glTexParameterxOES__III },
{"glTexParameterxvOES", "(II[II)V", (void *) android_glTexParameterxvOES__II_3II },
{"glTexParameterxvOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexParameterxvOES__IILjava_nio_IntBuffer_2 },
{"glTranslatexOES", "(III)V", (void *) android_glTranslatexOES__III },
{"glIsRenderbufferOES", "(I)Z", (void *) android_glIsRenderbufferOES__I },
{"glBindRenderbufferOES", "(II)V", (void *) android_glBindRenderbufferOES__II },
{"glDeleteRenderbuffersOES", "(I[II)V", (void *) android_glDeleteRenderbuffersOES__I_3II },
{"glDeleteRenderbuffersOES", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteRenderbuffersOES__ILjava_nio_IntBuffer_2 },
{"glGenRenderbuffersOES", "(I[II)V", (void *) android_glGenRenderbuffersOES__I_3II },
{"glGenRenderbuffersOES", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenRenderbuffersOES__ILjava_nio_IntBuffer_2 },
{"glRenderbufferStorageOES", "(IIII)V", (void *) android_glRenderbufferStorageOES__IIII },
{"glGetRenderbufferParameterivOES", "(II[II)V", (void *) android_glGetRenderbufferParameterivOES__II_3II },
{"glGetRenderbufferParameterivOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetRenderbufferParameterivOES__IILjava_nio_IntBuffer_2 },
{"glIsFramebufferOES", "(I)Z", (void *) android_glIsFramebufferOES__I },
{"glBindFramebufferOES", "(II)V", (void *) android_glBindFramebufferOES__II },
{"glDeleteFramebuffersOES", "(I[II)V", (void *) android_glDeleteFramebuffersOES__I_3II },
{"glDeleteFramebuffersOES", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteFramebuffersOES__ILjava_nio_IntBuffer_2 },
{"glGenFramebuffersOES", "(I[II)V", (void *) android_glGenFramebuffersOES__I_3II },
{"glGenFramebuffersOES", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenFramebuffersOES__ILjava_nio_IntBuffer_2 },
{"glCheckFramebufferStatusOES", "(I)I", (void *) android_glCheckFramebufferStatusOES__I },
{"glFramebufferRenderbufferOES", "(IIII)V", (void *) android_glFramebufferRenderbufferOES__IIII },
{"glFramebufferTexture2DOES", "(IIIII)V", (void *) android_glFramebufferTexture2DOES__IIIII },
{"glGetFramebufferAttachmentParameterivOES", "(III[II)V", (void *) android_glGetFramebufferAttachmentParameterivOES__III_3II },
{"glGetFramebufferAttachmentParameterivOES", "(IIILjava/nio/IntBuffer;)V", (void *) android_glGetFramebufferAttachmentParameterivOES__IIILjava_nio_IntBuffer_2 },
{"glGenerateMipmapOES", "(I)V", (void *) android_glGenerateMipmapOES__I },
{"glCurrentPaletteMatrixOES", "(I)V", (void *) android_glCurrentPaletteMatrixOES__I },
{"glLoadPaletteFromModelViewMatrixOES", "()V", (void *) android_glLoadPaletteFromModelViewMatrixOES__ },
{"glMatrixIndexPointerOESBounds", "(IIILjava/nio/Buffer;I)V", (void *) android_glMatrixIndexPointerOESBounds__IIILjava_nio_Buffer_2I },
{"glWeightPointerOESBounds", "(IIILjava/nio/Buffer;I)V", (void *) android_glWeightPointerOESBounds__IIILjava_nio_Buffer_2I },
{"glDepthRangefOES", "(FF)V", (void *) android_glDepthRangefOES__FF },
{"glFrustumfOES", "(FFFFFF)V", (void *) android_glFrustumfOES__FFFFFF },
{"glOrthofOES", "(FFFFFF)V", (void *) android_glOrthofOES__FFFFFF },
{"glClipPlanefOES", "(I[FI)V", (void *) android_glClipPlanefOES__I_3FI },
{"glClipPlanefOES", "(ILjava/nio/FloatBuffer;)V", (void *) android_glClipPlanefOES__ILjava_nio_FloatBuffer_2 },
{"glGetClipPlanefOES", "(I[FI)V", (void *) android_glGetClipPlanefOES__I_3FI },
{"glGetClipPlanefOES", "(ILjava/nio/FloatBuffer;)V", (void *) android_glGetClipPlanefOES__ILjava_nio_FloatBuffer_2 },
{"glClearDepthfOES", "(F)V", (void *) android_glClearDepthfOES__F },
{"glTexGenfOES", "(IIF)V", (void *) android_glTexGenfOES__IIF },
{"glTexGenfvOES", "(II[FI)V", (void *) android_glTexGenfvOES__II_3FI },
{"glTexGenfvOES", "(IILjava/nio/FloatBuffer;)V", (void *) android_glTexGenfvOES__IILjava_nio_FloatBuffer_2 },
{"glTexGeniOES", "(III)V", (void *) android_glTexGeniOES__III },
{"glTexGenivOES", "(II[II)V", (void *) android_glTexGenivOES__II_3II },
{"glTexGenivOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexGenivOES__IILjava_nio_IntBuffer_2 },
{"glTexGenxOES", "(III)V", (void *) android_glTexGenxOES__III },
{"glTexGenxvOES", "(II[II)V", (void *) android_glTexGenxvOES__II_3II },
{"glTexGenxvOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexGenxvOES__IILjava_nio_IntBuffer_2 },
{"glGetTexGenfvOES", "(II[FI)V", (void *) android_glGetTexGenfvOES__II_3FI },
{"glGetTexGenfvOES", "(IILjava/nio/FloatBuffer;)V", (void *) android_glGetTexGenfvOES__IILjava_nio_FloatBuffer_2 },
{"glGetTexGenivOES", "(II[II)V", (void *) android_glGetTexGenivOES__II_3II },
{"glGetTexGenivOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexGenivOES__IILjava_nio_IntBuffer_2 },
{"glGetTexGenxvOES", "(II[II)V", (void *) android_glGetTexGenxvOES__II_3II },
{"glGetTexGenxvOES", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexGenxvOES__IILjava_nio_IntBuffer_2 },
};

int register_android_opengl_jni_GLES11Ext(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));
    return err;
}

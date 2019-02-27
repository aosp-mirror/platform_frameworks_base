/*
 * Copyright 2014 The Android Open Source Project
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

// This source file is automatically generated

#pragma GCC diagnostic ignored "-Wunused-variable"
#pragma GCC diagnostic ignored "-Wunused-function"

#include <stdint.h>
#include <GLES3/gl31.h>
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
/* void glDispatchCompute ( GLuint num_groups_x, GLuint num_groups_y, GLuint num_groups_z ) */
static void
android_glDispatchCompute__III
  (JNIEnv *_env, jobject _this, jint num_groups_x, jint num_groups_y, jint num_groups_z) {
    glDispatchCompute(
        (GLuint)num_groups_x,
        (GLuint)num_groups_y,
        (GLuint)num_groups_z
    );
}

/* void glDispatchComputeIndirect ( GLintptr indirect ) */
static void android_glDispatchComputeIndirect(JNIEnv *_env, jobject, jlong indirect) {
    // 'indirect' is a byte offset, not a pointer. GL checks for negative and too-large values.
    // Here we only need to check for successful 64-bit to 32-bit conversion.
    // - jlong is a int64_t (jni.h)
    // - GLintptr is a long (khrplatform.h)
    if (sizeof(GLintptr) != sizeof(jlong) && (indirect < LONG_MIN || indirect > LONG_MAX)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "indirect offset too large");
        return;
    }
    glDispatchComputeIndirect((GLintptr)indirect);
}

/* void glDrawArraysIndirect ( GLenum mode, const void *indirect ) */
static void android_glDrawArraysIndirect(JNIEnv *_env, jobject, int mode, jlong indirect) {
    // In OpenGL ES, 'indirect' is a byte offset into a buffer, not a raw pointer.
    // GL checks for too-large values. Here we only need to check for successful signed 64-bit
    // to unsigned 32-bit conversion.
    if (sizeof(void*) != sizeof(jlong) && indirect > static_cast<jlong>(UINT32_MAX)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "indirect offset too large");
        return;
    }
    glDrawArraysIndirect(mode, (const void*)indirect);
}

/* void glDrawElementsIndirect ( GLenum mode, GLenum type, const void *indirect ) */
static void android_glDrawElementsIndirect(JNIEnv *_env, jobject, jint mode, jint type, jlong indirect) {
    // In OpenGL ES, 'indirect' is a byte offset into a buffer, not a raw pointer.
    // GL checks for too-large values. Here we only need to check for successful signed 64-bit
    // to unsigned 32-bit conversion.
    if (sizeof(void*) != sizeof(jlong) && indirect > static_cast<jlong>(UINT32_MAX)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "indirect offset too large");
        return;
    }
    glDrawElementsIndirect(mode, type, (const void*)indirect);
}

/* void glFramebufferParameteri ( GLenum target, GLenum pname, GLint param ) */
static void
android_glFramebufferParameteri__III
  (JNIEnv *_env, jobject _this, jint target, jint pname, jint param) {
    glFramebufferParameteri(
        (GLenum)target,
        (GLenum)pname,
        (GLint)param
    );
}

/* void glGetFramebufferParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetFramebufferParameteriv__II_3II
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

    glGetFramebufferParameteriv(
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

/* void glGetFramebufferParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetFramebufferParameteriv__IILjava_nio_IntBuffer_2
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
    glGetFramebufferParameteriv(
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

/* void glGetProgramInterfaceiv ( GLuint program, GLenum programInterface, GLenum pname, GLint *params ) */
static void
android_glGetProgramInterfaceiv__III_3II
  (JNIEnv *_env, jobject _this, jint program, jint programInterface, jint pname, jintArray params_ref, jint offset) {
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

    glGetProgramInterfaceiv(
        (GLuint)program,
        (GLenum)programInterface,
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

/* void glGetProgramInterfaceiv ( GLuint program, GLenum programInterface, GLenum pname, GLint *params ) */
static void
android_glGetProgramInterfaceiv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint programInterface, jint pname, jobject params_buf) {
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
    glGetProgramInterfaceiv(
        (GLuint)program,
        (GLenum)programInterface,
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

/* GLuint glGetProgramResourceIndex ( GLuint program, GLenum programInterface, const GLchar *name ) */
static jint
android_glGetProgramResourceIndex__IILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint program, jint programInterface, jstring name) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint _returnValue = 0;
    const char* _nativename = 0;

    if (!name) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "name == null";
        goto exit;
    }
    _nativename = _env->GetStringUTFChars(name, 0);

    _returnValue = glGetProgramResourceIndex(
        (GLuint)program,
        (GLenum)programInterface,
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

/* void glGetProgramResourceName ( GLuint program, GLenum programInterface, GLuint index, GLsizei bufSize, GLsizei *length, GLchar *name ) */
static jstring
android_glGetProgramResourceName
  (JNIEnv *_env, jobject _this, jint program, jint programInterface, jint index) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return NULL;
}

/* void glGetProgramResourceiv ( GLuint program, GLenum programInterface, GLuint index, GLsizei propCount, const GLenum *props, GLsizei bufSize, GLsizei *length, GLint *params ) */
static void
android_glGetProgramResourceiv__IIII_3III_3II_3II
  (JNIEnv *_env, jobject _this, jint program, jint programInterface, jint index, jint propCount, jintArray props_ref, jint propsOffset, jint bufSize, jintArray length_ref, jint lengthOffset, jintArray params_ref, jint paramsOffset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLenum *props_base = (GLenum *) 0;
    jint _propsRemaining;
    GLenum *props = (GLenum *) 0;
    GLsizei *length_base = (GLsizei *) 0;
    jint _lengthRemaining;
    GLsizei *length = (GLsizei *) 0;
    GLint *params_base = (GLint *) 0;
    jint _paramsRemaining;
    GLint *params = (GLint *) 0;

    if (!props_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "props == null";
        goto exit;
    }
    if (propsOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "propsOffset < 0";
        goto exit;
    }
    _propsRemaining = _env->GetArrayLength(props_ref) - propsOffset;
    props_base = (GLenum *)
        _env->GetIntArrayElements(props_ref, (jboolean *)0);
    props = props_base + propsOffset;

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

    glGetProgramResourceiv(
        (GLuint)program,
        (GLenum)programInterface,
        (GLuint)index,
        (GLsizei)propCount,
        (GLenum *)props,
        (GLsizei)bufSize,
        (GLsizei *)length,
        (GLint *)params
    );

exit:
    if (params_base) {
        _env->ReleaseIntArrayElements(params_ref, (jint*)params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (length_base) {
        _env->ReleaseIntArrayElements(length_ref, (jint*)length_base,
            _exception ? JNI_ABORT: 0);
    }
    if (props_base) {
        _env->ReleaseIntArrayElements(props_ref, (jint*)props_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetProgramResourceiv ( GLuint program, GLenum programInterface, GLuint index, GLsizei propCount, const GLenum *props, GLsizei bufSize, GLsizei *length, GLint *params ) */
static void
android_glGetProgramResourceiv__IIIILjava_nio_IntBuffer_2ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint programInterface, jint index, jint propCount, jobject props_buf, jint bufSize, jobject length_buf, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _propsArray = (jintArray) 0;
    jint _propsBufferOffset = (jint) 0;
    jintArray _lengthArray = (jintArray) 0;
    jint _lengthBufferOffset = (jint) 0;
    jintArray _paramsArray = (jintArray) 0;
    jint _paramsBufferOffset = (jint) 0;
    jint _propsRemaining;
    GLenum *props = (GLenum *) 0;
    jint _lengthRemaining;
    GLsizei *length = (GLsizei *) 0;
    jint _paramsRemaining;
    GLint *params = (GLint *) 0;

    if (!props_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "props == null";
        goto exit;
    }
    props = (GLenum *)getPointer(_env, props_buf, (jarray*)&_propsArray, &_propsRemaining, &_propsBufferOffset);
    if (length_buf) {
        length = (GLsizei *)getPointer(_env, length_buf, (jarray*)&_lengthArray, &_lengthRemaining, &_lengthBufferOffset);
    }
    if (!params_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "params == null";
        goto exit;
    }
    params = (GLint *)getPointer(_env, params_buf, (jarray*)&_paramsArray, &_paramsRemaining, &_paramsBufferOffset);
    if (props == NULL) {
        char * _propsBase = (char *)_env->GetIntArrayElements(_propsArray, (jboolean *) 0);
        props = (GLenum *) (_propsBase + _propsBufferOffset);
    }
    if (length_buf && length == NULL) {
        char * _lengthBase = (char *)_env->GetIntArrayElements(_lengthArray, (jboolean *) 0);
        length = (GLsizei *) (_lengthBase + _lengthBufferOffset);
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_paramsArray, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _paramsBufferOffset);
    }
    glGetProgramResourceiv(
        (GLuint)program,
        (GLenum)programInterface,
        (GLuint)index,
        (GLsizei)propCount,
        (GLenum *)props,
        (GLsizei)bufSize,
        (GLsizei *)length,
        (GLint *)params
    );

exit:
    if (_paramsArray) {
        _env->ReleaseIntArrayElements(_paramsArray, (jint*)params, _exception ? JNI_ABORT : 0);
    }
    if (_lengthArray) {
        _env->ReleaseIntArrayElements(_lengthArray, (jint*)length, _exception ? JNI_ABORT : 0);
    }
    if (_propsArray) {
        _env->ReleaseIntArrayElements(_propsArray, (jint*)props, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLint glGetProgramResourceLocation ( GLuint program, GLenum programInterface, const GLchar *name ) */
static jint
android_glGetProgramResourceLocation__IILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint program, jint programInterface, jstring name) {
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

    _returnValue = glGetProgramResourceLocation(
        (GLuint)program,
        (GLenum)programInterface,
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

/* void glUseProgramStages ( GLuint pipeline, GLbitfield stages, GLuint program ) */
static void
android_glUseProgramStages__III
  (JNIEnv *_env, jobject _this, jint pipeline, jint stages, jint program) {
    glUseProgramStages(
        (GLuint)pipeline,
        (GLbitfield)stages,
        (GLuint)program
    );
}

/* void glActiveShaderProgram ( GLuint pipeline, GLuint program ) */
static void
android_glActiveShaderProgram__II
  (JNIEnv *_env, jobject _this, jint pipeline, jint program) {
    glActiveShaderProgram(
        (GLuint)pipeline,
        (GLuint)program
    );
}

/* GLuint glCreateShaderProgramv ( GLenum type, GLsizei count, const GLchar *const *strings ) */
static jint
android_glCreateShaderProgramv
  (JNIEnv *_env, jobject _this, jint type, jobjectArray strings) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLsizei _count;
    const GLchar** _strings = NULL;
    jstring* _jstrings = NULL;
    GLuint _returnValue = 0;

    if (!strings) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "strings == null";
        goto exit;
    }

    _count = _env->GetArrayLength(strings);

    _strings = (const GLchar**) calloc(_count, sizeof(const GLchar*));
    if (!_strings) {
        _exception = 1;
        _exceptionType = "java/lang/OutOfMemoryError";
        _exceptionMessage = "out of memory";
        goto exit;
    }

    _jstrings = (jstring*) calloc(_count, sizeof(jstring));
    if (!_jstrings) {
        _exception = 1;
        _exceptionType = "java/lang/OutOfMemoryError";
        _exceptionMessage = "out of memory";
        goto exit;
    }

    for(int i = 0; i < _count; i++) {
        _jstrings[i] = (jstring) _env->GetObjectArrayElement(strings, i);
        if (!_jstrings[i]) {
            _exception = 1;
            _exceptionType = "java/lang/IllegalArgumentException";
            _exceptionMessage = "strings == null";
            goto exit;
        }
        _strings[i] = _env->GetStringUTFChars(_jstrings[i], 0);
    }

    _returnValue = glCreateShaderProgramv((GLenum)type, _count, _strings);
exit:
    if (_strings && _jstrings) {
        for(int i = 0; i < _count; i++) {
            if (_strings[i] && _jstrings[i]) {
                _env->ReleaseStringUTFChars(_jstrings[i], _strings[i]);
            }
        }
    }
    if (_strings) {
        free(_strings);
    }
    if (_jstrings) {
        free(_jstrings);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return (jint)_returnValue;
}
/* void glBindProgramPipeline ( GLuint pipeline ) */
static void
android_glBindProgramPipeline__I
  (JNIEnv *_env, jobject _this, jint pipeline) {
    glBindProgramPipeline(
        (GLuint)pipeline
    );
}

/* void glDeleteProgramPipelines ( GLsizei n, const GLuint *pipelines ) */
static void
android_glDeleteProgramPipelines__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray pipelines_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *pipelines_base = (GLuint *) 0;
    jint _remaining;
    GLuint *pipelines = (GLuint *) 0;

    if (!pipelines_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "pipelines == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(pipelines_ref) - offset;
    pipelines_base = (GLuint *)
        _env->GetIntArrayElements(pipelines_ref, (jboolean *)0);
    pipelines = pipelines_base + offset;

    glDeleteProgramPipelines(
        (GLsizei)n,
        (GLuint *)pipelines
    );

exit:
    if (pipelines_base) {
        _env->ReleaseIntArrayElements(pipelines_ref, (jint*)pipelines_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteProgramPipelines ( GLsizei n, const GLuint *pipelines ) */
static void
android_glDeleteProgramPipelines__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject pipelines_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *pipelines = (GLuint *) 0;

    if (!pipelines_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "pipelines == null";
        goto exit;
    }
    pipelines = (GLuint *)getPointer(_env, pipelines_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (pipelines == NULL) {
        char * _pipelinesBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        pipelines = (GLuint *) (_pipelinesBase + _bufferOffset);
    }
    glDeleteProgramPipelines(
        (GLsizei)n,
        (GLuint *)pipelines
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)pipelines, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenProgramPipelines ( GLsizei n, GLuint *pipelines ) */
static void
android_glGenProgramPipelines__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray pipelines_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *pipelines_base = (GLuint *) 0;
    jint _remaining;
    GLuint *pipelines = (GLuint *) 0;

    if (!pipelines_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "pipelines == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(pipelines_ref) - offset;
    pipelines_base = (GLuint *)
        _env->GetIntArrayElements(pipelines_ref, (jboolean *)0);
    pipelines = pipelines_base + offset;

    glGenProgramPipelines(
        (GLsizei)n,
        (GLuint *)pipelines
    );

exit:
    if (pipelines_base) {
        _env->ReleaseIntArrayElements(pipelines_ref, (jint*)pipelines_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenProgramPipelines ( GLsizei n, GLuint *pipelines ) */
static void
android_glGenProgramPipelines__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject pipelines_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *pipelines = (GLuint *) 0;

    if (!pipelines_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "pipelines == null";
        goto exit;
    }
    pipelines = (GLuint *)getPointer(_env, pipelines_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (pipelines == NULL) {
        char * _pipelinesBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        pipelines = (GLuint *) (_pipelinesBase + _bufferOffset);
    }
    glGenProgramPipelines(
        (GLsizei)n,
        (GLuint *)pipelines
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)pipelines, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLboolean glIsProgramPipeline ( GLuint pipeline ) */
static jboolean
android_glIsProgramPipeline__I
  (JNIEnv *_env, jobject _this, jint pipeline) {
    GLboolean _returnValue;
    _returnValue = glIsProgramPipeline(
        (GLuint)pipeline
    );
    return (jboolean)_returnValue;
}

/* void glGetProgramPipelineiv ( GLuint pipeline, GLenum pname, GLint *params ) */
static void
android_glGetProgramPipelineiv__II_3II
  (JNIEnv *_env, jobject _this, jint pipeline, jint pname, jintArray params_ref, jint offset) {
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

    glGetProgramPipelineiv(
        (GLuint)pipeline,
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

/* void glGetProgramPipelineiv ( GLuint pipeline, GLenum pname, GLint *params ) */
static void
android_glGetProgramPipelineiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint pipeline, jint pname, jobject params_buf) {
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
    glGetProgramPipelineiv(
        (GLuint)pipeline,
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

/* void glProgramUniform1i ( GLuint program, GLint location, GLint v0 ) */
static void
android_glProgramUniform1i__III
  (JNIEnv *_env, jobject _this, jint program, jint location, jint v0) {
    glProgramUniform1i(
        (GLuint)program,
        (GLint)location,
        (GLint)v0
    );
}

/* void glProgramUniform2i ( GLuint program, GLint location, GLint v0, GLint v1 ) */
static void
android_glProgramUniform2i__IIII
  (JNIEnv *_env, jobject _this, jint program, jint location, jint v0, jint v1) {
    glProgramUniform2i(
        (GLuint)program,
        (GLint)location,
        (GLint)v0,
        (GLint)v1
    );
}

/* void glProgramUniform3i ( GLuint program, GLint location, GLint v0, GLint v1, GLint v2 ) */
static void
android_glProgramUniform3i__IIIII
  (JNIEnv *_env, jobject _this, jint program, jint location, jint v0, jint v1, jint v2) {
    glProgramUniform3i(
        (GLuint)program,
        (GLint)location,
        (GLint)v0,
        (GLint)v1,
        (GLint)v2
    );
}

/* void glProgramUniform4i ( GLuint program, GLint location, GLint v0, GLint v1, GLint v2, GLint v3 ) */
static void
android_glProgramUniform4i__IIIIII
  (JNIEnv *_env, jobject _this, jint program, jint location, jint v0, jint v1, jint v2, jint v3) {
    glProgramUniform4i(
        (GLuint)program,
        (GLint)location,
        (GLint)v0,
        (GLint)v1,
        (GLint)v2,
        (GLint)v3
    );
}

/* void glProgramUniform1ui ( GLuint program, GLint location, GLuint v0 ) */
static void
android_glProgramUniform1ui__III
  (JNIEnv *_env, jobject _this, jint program, jint location, jint v0) {
    glProgramUniform1ui(
        (GLuint)program,
        (GLint)location,
        (GLuint)v0
    );
}

/* void glProgramUniform2ui ( GLuint program, GLint location, GLuint v0, GLuint v1 ) */
static void
android_glProgramUniform2ui__IIII
  (JNIEnv *_env, jobject _this, jint program, jint location, jint v0, jint v1) {
    glProgramUniform2ui(
        (GLuint)program,
        (GLint)location,
        (GLuint)v0,
        (GLuint)v1
    );
}

/* void glProgramUniform3ui ( GLuint program, GLint location, GLuint v0, GLuint v1, GLuint v2 ) */
static void
android_glProgramUniform3ui__IIIII
  (JNIEnv *_env, jobject _this, jint program, jint location, jint v0, jint v1, jint v2) {
    glProgramUniform3ui(
        (GLuint)program,
        (GLint)location,
        (GLuint)v0,
        (GLuint)v1,
        (GLuint)v2
    );
}

/* void glProgramUniform4ui ( GLuint program, GLint location, GLuint v0, GLuint v1, GLuint v2, GLuint v3 ) */
static void
android_glProgramUniform4ui__IIIIII
  (JNIEnv *_env, jobject _this, jint program, jint location, jint v0, jint v1, jint v2, jint v3) {
    glProgramUniform4ui(
        (GLuint)program,
        (GLint)location,
        (GLuint)v0,
        (GLuint)v1,
        (GLuint)v2,
        (GLuint)v3
    );
}

/* void glProgramUniform1f ( GLuint program, GLint location, GLfloat v0 ) */
static void
android_glProgramUniform1f__IIF
  (JNIEnv *_env, jobject _this, jint program, jint location, jfloat v0) {
    glProgramUniform1f(
        (GLuint)program,
        (GLint)location,
        (GLfloat)v0
    );
}

/* void glProgramUniform2f ( GLuint program, GLint location, GLfloat v0, GLfloat v1 ) */
static void
android_glProgramUniform2f__IIFF
  (JNIEnv *_env, jobject _this, jint program, jint location, jfloat v0, jfloat v1) {
    glProgramUniform2f(
        (GLuint)program,
        (GLint)location,
        (GLfloat)v0,
        (GLfloat)v1
    );
}

/* void glProgramUniform3f ( GLuint program, GLint location, GLfloat v0, GLfloat v1, GLfloat v2 ) */
static void
android_glProgramUniform3f__IIFFF
  (JNIEnv *_env, jobject _this, jint program, jint location, jfloat v0, jfloat v1, jfloat v2) {
    glProgramUniform3f(
        (GLuint)program,
        (GLint)location,
        (GLfloat)v0,
        (GLfloat)v1,
        (GLfloat)v2
    );
}

/* void glProgramUniform4f ( GLuint program, GLint location, GLfloat v0, GLfloat v1, GLfloat v2, GLfloat v3 ) */
static void
android_glProgramUniform4f__IIFFFF
  (JNIEnv *_env, jobject _this, jint program, jint location, jfloat v0, jfloat v1, jfloat v2, jfloat v3) {
    glProgramUniform4f(
        (GLuint)program,
        (GLint)location,
        (GLfloat)v0,
        (GLfloat)v1,
        (GLfloat)v2,
        (GLfloat)v3
    );
}

/* void glProgramUniform1iv ( GLuint program, GLint location, GLsizei count, const GLint *value ) */
static void
android_glProgramUniform1iv__III_3II
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jintArray value_ref, jint offset) {
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

    glProgramUniform1iv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform1iv ( GLuint program, GLint location, GLsizei count, const GLint *value ) */
static void
android_glProgramUniform1iv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jobject value_buf) {
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
    glProgramUniform1iv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform2iv ( GLuint program, GLint location, GLsizei count, const GLint *value ) */
static void
android_glProgramUniform2iv__III_3II
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jintArray value_ref, jint offset) {
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

    glProgramUniform2iv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform2iv ( GLuint program, GLint location, GLsizei count, const GLint *value ) */
static void
android_glProgramUniform2iv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jobject value_buf) {
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
    glProgramUniform2iv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform3iv ( GLuint program, GLint location, GLsizei count, const GLint *value ) */
static void
android_glProgramUniform3iv__III_3II
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jintArray value_ref, jint offset) {
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

    glProgramUniform3iv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform3iv ( GLuint program, GLint location, GLsizei count, const GLint *value ) */
static void
android_glProgramUniform3iv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jobject value_buf) {
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
    glProgramUniform3iv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform4iv ( GLuint program, GLint location, GLsizei count, const GLint *value ) */
static void
android_glProgramUniform4iv__III_3II
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jintArray value_ref, jint offset) {
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

    glProgramUniform4iv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform4iv ( GLuint program, GLint location, GLsizei count, const GLint *value ) */
static void
android_glProgramUniform4iv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jobject value_buf) {
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
    glProgramUniform4iv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform1uiv ( GLuint program, GLint location, GLsizei count, const GLuint *value ) */
static void
android_glProgramUniform1uiv__III_3II
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jintArray value_ref, jint offset) {
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

    glProgramUniform1uiv(
        (GLuint)program,
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

/* void glProgramUniform1uiv ( GLuint program, GLint location, GLsizei count, const GLuint *value ) */
static void
android_glProgramUniform1uiv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jobject value_buf) {
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
    glProgramUniform1uiv(
        (GLuint)program,
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

/* void glProgramUniform2uiv ( GLuint program, GLint location, GLsizei count, const GLuint *value ) */
static void
android_glProgramUniform2uiv__III_3II
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jintArray value_ref, jint offset) {
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

    glProgramUniform2uiv(
        (GLuint)program,
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

/* void glProgramUniform2uiv ( GLuint program, GLint location, GLsizei count, const GLuint *value ) */
static void
android_glProgramUniform2uiv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jobject value_buf) {
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
    glProgramUniform2uiv(
        (GLuint)program,
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

/* void glProgramUniform3uiv ( GLuint program, GLint location, GLsizei count, const GLuint *value ) */
static void
android_glProgramUniform3uiv__III_3II
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jintArray value_ref, jint offset) {
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

    glProgramUniform3uiv(
        (GLuint)program,
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

/* void glProgramUniform3uiv ( GLuint program, GLint location, GLsizei count, const GLuint *value ) */
static void
android_glProgramUniform3uiv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jobject value_buf) {
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
    glProgramUniform3uiv(
        (GLuint)program,
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

/* void glProgramUniform4uiv ( GLuint program, GLint location, GLsizei count, const GLuint *value ) */
static void
android_glProgramUniform4uiv__III_3II
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jintArray value_ref, jint offset) {
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

    glProgramUniform4uiv(
        (GLuint)program,
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

/* void glProgramUniform4uiv ( GLuint program, GLint location, GLsizei count, const GLuint *value ) */
static void
android_glProgramUniform4uiv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jobject value_buf) {
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
    glProgramUniform4uiv(
        (GLuint)program,
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

/* void glProgramUniform1fv ( GLuint program, GLint location, GLsizei count, const GLfloat *value ) */
static void
android_glProgramUniform1fv__III_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jfloatArray value_ref, jint offset) {
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

    glProgramUniform1fv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform1fv ( GLuint program, GLint location, GLsizei count, const GLfloat *value ) */
static void
android_glProgramUniform1fv__IIILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jobject value_buf) {
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
    glProgramUniform1fv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform2fv ( GLuint program, GLint location, GLsizei count, const GLfloat *value ) */
static void
android_glProgramUniform2fv__III_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jfloatArray value_ref, jint offset) {
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

    glProgramUniform2fv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform2fv ( GLuint program, GLint location, GLsizei count, const GLfloat *value ) */
static void
android_glProgramUniform2fv__IIILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jobject value_buf) {
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
    glProgramUniform2fv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform3fv ( GLuint program, GLint location, GLsizei count, const GLfloat *value ) */
static void
android_glProgramUniform3fv__III_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jfloatArray value_ref, jint offset) {
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

    glProgramUniform3fv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform3fv ( GLuint program, GLint location, GLsizei count, const GLfloat *value ) */
static void
android_glProgramUniform3fv__IIILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jobject value_buf) {
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
    glProgramUniform3fv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform4fv ( GLuint program, GLint location, GLsizei count, const GLfloat *value ) */
static void
android_glProgramUniform4fv__III_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jfloatArray value_ref, jint offset) {
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

    glProgramUniform4fv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniform4fv ( GLuint program, GLint location, GLsizei count, const GLfloat *value ) */
static void
android_glProgramUniform4fv__IIILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jobject value_buf) {
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
    glProgramUniform4fv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)count,
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

/* void glProgramUniformMatrix2fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix2fv__IIIZ_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
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

    glProgramUniformMatrix2fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix2fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix2fv__IIIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jobject value_buf) {
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
    glProgramUniformMatrix2fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix3fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix3fv__IIIZ_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
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

    glProgramUniformMatrix3fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix3fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix3fv__IIIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jobject value_buf) {
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
    glProgramUniformMatrix3fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix4fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix4fv__IIIZ_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
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

    glProgramUniformMatrix4fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix4fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix4fv__IIIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jobject value_buf) {
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
    glProgramUniformMatrix4fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix2x3fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix2x3fv__IIIZ_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
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

    glProgramUniformMatrix2x3fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix2x3fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix2x3fv__IIIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jobject value_buf) {
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
    glProgramUniformMatrix2x3fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix3x2fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix3x2fv__IIIZ_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
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

    glProgramUniformMatrix3x2fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix3x2fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix3x2fv__IIIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jobject value_buf) {
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
    glProgramUniformMatrix3x2fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix2x4fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix2x4fv__IIIZ_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
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

    glProgramUniformMatrix2x4fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix2x4fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix2x4fv__IIIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jobject value_buf) {
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
    glProgramUniformMatrix2x4fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix4x2fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix4x2fv__IIIZ_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
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

    glProgramUniformMatrix4x2fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix4x2fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix4x2fv__IIIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jobject value_buf) {
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
    glProgramUniformMatrix4x2fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix3x4fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix3x4fv__IIIZ_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
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

    glProgramUniformMatrix3x4fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix3x4fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix3x4fv__IIIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jobject value_buf) {
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
    glProgramUniformMatrix3x4fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix4x3fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix4x3fv__IIIZ_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jfloatArray value_ref, jint offset) {
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

    glProgramUniformMatrix4x3fv(
        (GLuint)program,
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

/* void glProgramUniformMatrix4x3fv ( GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glProgramUniformMatrix4x3fv__IIIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint count, jboolean transpose, jobject value_buf) {
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
    glProgramUniformMatrix4x3fv(
        (GLuint)program,
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

/* void glValidateProgramPipeline ( GLuint pipeline ) */
static void
android_glValidateProgramPipeline__I
  (JNIEnv *_env, jobject _this, jint pipeline) {
    glValidateProgramPipeline(
        (GLuint)pipeline
    );
}

#include <stdlib.h>

/* void glGetProgramPipelineInfoLog ( GLuint shader, GLsizei maxLength, GLsizei* length, GLchar* infoLog ) */
static jstring android_glGetProgramPipelineInfoLog(JNIEnv *_env, jobject, jint shader) {
    GLint infoLen = 0;
    glGetProgramPipelineiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
    if (!infoLen) {
        return _env->NewStringUTF("");
    }
    char* buf = (char*) malloc(infoLen);
    if (buf == NULL) {
        jniThrowException(_env, "java/lang/OutOfMemoryError", "out of memory");
        return NULL;
    }
    glGetProgramPipelineInfoLog(shader, infoLen, NULL, buf);
    jstring result = _env->NewStringUTF(buf);
    free(buf);
    return result;
}
/* void glBindImageTexture ( GLuint unit, GLuint texture, GLint level, GLboolean layered, GLint layer, GLenum access, GLenum format ) */
static void
android_glBindImageTexture__IIIZIII
  (JNIEnv *_env, jobject _this, jint unit, jint texture, jint level, jboolean layered, jint layer, jint access, jint format) {
    glBindImageTexture(
        (GLuint)unit,
        (GLuint)texture,
        (GLint)level,
        (GLboolean)layered,
        (GLint)layer,
        (GLenum)access,
        (GLenum)format
    );
}

/* void glGetBooleani_v ( GLenum target, GLuint index, GLboolean *data ) */
static void
android_glGetBooleani_v__II_3ZI
  (JNIEnv *_env, jobject _this, jint target, jint index, jbooleanArray data_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLboolean *data_base = (GLboolean *) 0;
    jint _remaining;
    GLboolean *data = (GLboolean *) 0;

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
    data_base = (GLboolean *)
        _env->GetBooleanArrayElements(data_ref, (jboolean *)0);
    data = data_base + offset;

    glGetBooleani_v(
        (GLenum)target,
        (GLuint)index,
        (GLboolean *)data
    );

exit:
    if (data_base) {
        _env->ReleaseBooleanArrayElements(data_ref, (jboolean*)data_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetBooleani_v ( GLenum target, GLuint index, GLboolean *data ) */
static void
android_glGetBooleani_v__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint index, jobject data_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLboolean *data = (GLboolean *) 0;

    if (!data_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "data == null";
        goto exit;
    }
    data = (GLboolean *)getPointer(_env, data_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (data == NULL) {
        char * _dataBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        data = (GLboolean *) (_dataBase + _bufferOffset);
    }
    glGetBooleani_v(
        (GLenum)target,
        (GLuint)index,
        (GLboolean *)data
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)data, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glMemoryBarrier ( GLbitfield barriers ) */
static void
android_glMemoryBarrier__I
  (JNIEnv *_env, jobject _this, jint barriers) {
    glMemoryBarrier(
        (GLbitfield)barriers
    );
}

/* void glMemoryBarrierByRegion ( GLbitfield barriers ) */
static void
android_glMemoryBarrierByRegion__I
  (JNIEnv *_env, jobject _this, jint barriers) {
    glMemoryBarrierByRegion(
        (GLbitfield)barriers
    );
}

/* void glTexStorage2DMultisample ( GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height, GLboolean fixedsamplelocations ) */
static void
android_glTexStorage2DMultisample__IIIIIZ
  (JNIEnv *_env, jobject _this, jint target, jint samples, jint internalformat, jint width, jint height, jboolean fixedsamplelocations) {
    glTexStorage2DMultisample(
        (GLenum)target,
        (GLsizei)samples,
        (GLenum)internalformat,
        (GLsizei)width,
        (GLsizei)height,
        (GLboolean)fixedsamplelocations
    );
}

/* void glGetMultisamplefv ( GLenum pname, GLuint index, GLfloat *val ) */
static void
android_glGetMultisamplefv__II_3FI
  (JNIEnv *_env, jobject _this, jint pname, jint index, jfloatArray val_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *val_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *val = (GLfloat *) 0;

    if (!val_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "val == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(val_ref) - offset;
    val_base = (GLfloat *)
        _env->GetFloatArrayElements(val_ref, (jboolean *)0);
    val = val_base + offset;

    glGetMultisamplefv(
        (GLenum)pname,
        (GLuint)index,
        (GLfloat *)val
    );

exit:
    if (val_base) {
        _env->ReleaseFloatArrayElements(val_ref, (jfloat*)val_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetMultisamplefv ( GLenum pname, GLuint index, GLfloat *val ) */
static void
android_glGetMultisamplefv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jint index, jobject val_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *val = (GLfloat *) 0;

    if (!val_buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "val == null";
        goto exit;
    }
    val = (GLfloat *)getPointer(_env, val_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (val == NULL) {
        char * _valBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        val = (GLfloat *) (_valBase + _bufferOffset);
    }
    glGetMultisamplefv(
        (GLenum)pname,
        (GLuint)index,
        (GLfloat *)val
    );

exit:
    if (_array) {
        _env->ReleaseFloatArrayElements(_array, (jfloat*)val, _exception ? JNI_ABORT : 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glSampleMaski ( GLuint maskNumber, GLbitfield mask ) */
static void
android_glSampleMaski__II
  (JNIEnv *_env, jobject _this, jint maskNumber, jint mask) {
    glSampleMaski(
        (GLuint)maskNumber,
        (GLbitfield)mask
    );
}

/* void glGetTexLevelParameteriv ( GLenum target, GLint level, GLenum pname, GLint *params ) */
static void
android_glGetTexLevelParameteriv__III_3II
  (JNIEnv *_env, jobject _this, jint target, jint level, jint pname, jintArray params_ref, jint offset) {
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

    glGetTexLevelParameteriv(
        (GLenum)target,
        (GLint)level,
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

/* void glGetTexLevelParameteriv ( GLenum target, GLint level, GLenum pname, GLint *params ) */
static void
android_glGetTexLevelParameteriv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint pname, jobject params_buf) {
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
    glGetTexLevelParameteriv(
        (GLenum)target,
        (GLint)level,
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

/* void glGetTexLevelParameterfv ( GLenum target, GLint level, GLenum pname, GLfloat *params ) */
static void
android_glGetTexLevelParameterfv__III_3FI
  (JNIEnv *_env, jobject _this, jint target, jint level, jint pname, jfloatArray params_ref, jint offset) {
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

    glGetTexLevelParameterfv(
        (GLenum)target,
        (GLint)level,
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

/* void glGetTexLevelParameterfv ( GLenum target, GLint level, GLenum pname, GLfloat *params ) */
static void
android_glGetTexLevelParameterfv__IIILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint pname, jobject params_buf) {
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
    glGetTexLevelParameterfv(
        (GLenum)target,
        (GLint)level,
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

/* void glBindVertexBuffer ( GLuint bindingindex, GLuint buffer, GLintptr offset, GLsizei stride ) */
static void
android_glBindVertexBuffer__IIJI
  (JNIEnv *_env, jobject _this, jint bindingindex, jint buffer, jlong offset, jint stride) {
    if (sizeof(GLintptr) != sizeof(jlong) && (offset < LONG_MIN || offset > LONG_MAX)) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset too large");
        return;
    }
    glBindVertexBuffer(
        (GLuint)bindingindex,
        (GLuint)buffer,
        (GLintptr)offset,
        (GLsizei)stride
    );
}
/* void glVertexAttribFormat ( GLuint attribindex, GLint size, GLenum type, GLboolean normalized, GLuint relativeoffset ) */
static void
android_glVertexAttribFormat__IIIZI
  (JNIEnv *_env, jobject _this, jint attribindex, jint size, jint type, jboolean normalized, jint relativeoffset) {
    glVertexAttribFormat(
        (GLuint)attribindex,
        (GLint)size,
        (GLenum)type,
        (GLboolean)normalized,
        (GLuint)relativeoffset
    );
}

/* void glVertexAttribIFormat ( GLuint attribindex, GLint size, GLenum type, GLuint relativeoffset ) */
static void
android_glVertexAttribIFormat__IIII
  (JNIEnv *_env, jobject _this, jint attribindex, jint size, jint type, jint relativeoffset) {
    glVertexAttribIFormat(
        (GLuint)attribindex,
        (GLint)size,
        (GLenum)type,
        (GLuint)relativeoffset
    );
}

/* void glVertexAttribBinding ( GLuint attribindex, GLuint bindingindex ) */
static void
android_glVertexAttribBinding__II
  (JNIEnv *_env, jobject _this, jint attribindex, jint bindingindex) {
    glVertexAttribBinding(
        (GLuint)attribindex,
        (GLuint)bindingindex
    );
}

/* void glVertexBindingDivisor ( GLuint bindingindex, GLuint divisor ) */
static void
android_glVertexBindingDivisor__II
  (JNIEnv *_env, jobject _this, jint bindingindex, jint divisor) {
    glVertexBindingDivisor(
        (GLuint)bindingindex,
        (GLuint)divisor
    );
}

static const char *classPathName = "android/opengl/GLES31";

static const JNINativeMethod methods[] = {
{"_nativeClassInit", "()V", (void*)nativeClassInit },
{"glDispatchCompute", "(III)V", (void *) android_glDispatchCompute__III },
{"glDispatchComputeIndirect", "(J)V", (void *) android_glDispatchComputeIndirect },
{"glDrawArraysIndirect", "(IJ)V", (void *) android_glDrawArraysIndirect },
{"glDrawElementsIndirect", "(IIJ)V", (void *) android_glDrawElementsIndirect },
{"glFramebufferParameteri", "(III)V", (void *) android_glFramebufferParameteri__III },
{"glGetFramebufferParameteriv", "(II[II)V", (void *) android_glGetFramebufferParameteriv__II_3II },
{"glGetFramebufferParameteriv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetFramebufferParameteriv__IILjava_nio_IntBuffer_2 },
{"glGetProgramInterfaceiv", "(III[II)V", (void *) android_glGetProgramInterfaceiv__III_3II },
{"glGetProgramInterfaceiv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glGetProgramInterfaceiv__IIILjava_nio_IntBuffer_2 },
{"glGetProgramResourceIndex", "(IILjava/lang/String;)I", (void *) android_glGetProgramResourceIndex__IILjava_lang_String_2 },
{"glGetProgramResourceName", "(III)Ljava/lang/String;", (void *) android_glGetProgramResourceName },
{"glGetProgramResourceiv", "(IIII[III[II[II)V", (void *) android_glGetProgramResourceiv__IIII_3III_3II_3II },
{"glGetProgramResourceiv", "(IIIILjava/nio/IntBuffer;ILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)V", (void *) android_glGetProgramResourceiv__IIIILjava_nio_IntBuffer_2ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2 },
{"glGetProgramResourceLocation", "(IILjava/lang/String;)I", (void *) android_glGetProgramResourceLocation__IILjava_lang_String_2 },
{"glUseProgramStages", "(III)V", (void *) android_glUseProgramStages__III },
{"glActiveShaderProgram", "(II)V", (void *) android_glActiveShaderProgram__II },
{"glCreateShaderProgramv", "(I[Ljava/lang/String;)I", (void *) android_glCreateShaderProgramv },
{"glBindProgramPipeline", "(I)V", (void *) android_glBindProgramPipeline__I },
{"glDeleteProgramPipelines", "(I[II)V", (void *) android_glDeleteProgramPipelines__I_3II },
{"glDeleteProgramPipelines", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteProgramPipelines__ILjava_nio_IntBuffer_2 },
{"glGenProgramPipelines", "(I[II)V", (void *) android_glGenProgramPipelines__I_3II },
{"glGenProgramPipelines", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenProgramPipelines__ILjava_nio_IntBuffer_2 },
{"glIsProgramPipeline", "(I)Z", (void *) android_glIsProgramPipeline__I },
{"glGetProgramPipelineiv", "(II[II)V", (void *) android_glGetProgramPipelineiv__II_3II },
{"glGetProgramPipelineiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetProgramPipelineiv__IILjava_nio_IntBuffer_2 },
{"glProgramUniform1i", "(III)V", (void *) android_glProgramUniform1i__III },
{"glProgramUniform2i", "(IIII)V", (void *) android_glProgramUniform2i__IIII },
{"glProgramUniform3i", "(IIIII)V", (void *) android_glProgramUniform3i__IIIII },
{"glProgramUniform4i", "(IIIIII)V", (void *) android_glProgramUniform4i__IIIIII },
{"glProgramUniform1ui", "(III)V", (void *) android_glProgramUniform1ui__III },
{"glProgramUniform2ui", "(IIII)V", (void *) android_glProgramUniform2ui__IIII },
{"glProgramUniform3ui", "(IIIII)V", (void *) android_glProgramUniform3ui__IIIII },
{"glProgramUniform4ui", "(IIIIII)V", (void *) android_glProgramUniform4ui__IIIIII },
{"glProgramUniform1f", "(IIF)V", (void *) android_glProgramUniform1f__IIF },
{"glProgramUniform2f", "(IIFF)V", (void *) android_glProgramUniform2f__IIFF },
{"glProgramUniform3f", "(IIFFF)V", (void *) android_glProgramUniform3f__IIFFF },
{"glProgramUniform4f", "(IIFFFF)V", (void *) android_glProgramUniform4f__IIFFFF },
{"glProgramUniform1iv", "(III[II)V", (void *) android_glProgramUniform1iv__III_3II },
{"glProgramUniform1iv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glProgramUniform1iv__IIILjava_nio_IntBuffer_2 },
{"glProgramUniform2iv", "(III[II)V", (void *) android_glProgramUniform2iv__III_3II },
{"glProgramUniform2iv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glProgramUniform2iv__IIILjava_nio_IntBuffer_2 },
{"glProgramUniform3iv", "(III[II)V", (void *) android_glProgramUniform3iv__III_3II },
{"glProgramUniform3iv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glProgramUniform3iv__IIILjava_nio_IntBuffer_2 },
{"glProgramUniform4iv", "(III[II)V", (void *) android_glProgramUniform4iv__III_3II },
{"glProgramUniform4iv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glProgramUniform4iv__IIILjava_nio_IntBuffer_2 },
{"glProgramUniform1uiv", "(III[II)V", (void *) android_glProgramUniform1uiv__III_3II },
{"glProgramUniform1uiv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glProgramUniform1uiv__IIILjava_nio_IntBuffer_2 },
{"glProgramUniform2uiv", "(III[II)V", (void *) android_glProgramUniform2uiv__III_3II },
{"glProgramUniform2uiv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glProgramUniform2uiv__IIILjava_nio_IntBuffer_2 },
{"glProgramUniform3uiv", "(III[II)V", (void *) android_glProgramUniform3uiv__III_3II },
{"glProgramUniform3uiv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glProgramUniform3uiv__IIILjava_nio_IntBuffer_2 },
{"glProgramUniform4uiv", "(III[II)V", (void *) android_glProgramUniform4uiv__III_3II },
{"glProgramUniform4uiv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glProgramUniform4uiv__IIILjava_nio_IntBuffer_2 },
{"glProgramUniform1fv", "(III[FI)V", (void *) android_glProgramUniform1fv__III_3FI },
{"glProgramUniform1fv", "(IIILjava/nio/FloatBuffer;)V", (void *) android_glProgramUniform1fv__IIILjava_nio_FloatBuffer_2 },
{"glProgramUniform2fv", "(III[FI)V", (void *) android_glProgramUniform2fv__III_3FI },
{"glProgramUniform2fv", "(IIILjava/nio/FloatBuffer;)V", (void *) android_glProgramUniform2fv__IIILjava_nio_FloatBuffer_2 },
{"glProgramUniform3fv", "(III[FI)V", (void *) android_glProgramUniform3fv__III_3FI },
{"glProgramUniform3fv", "(IIILjava/nio/FloatBuffer;)V", (void *) android_glProgramUniform3fv__IIILjava_nio_FloatBuffer_2 },
{"glProgramUniform4fv", "(III[FI)V", (void *) android_glProgramUniform4fv__III_3FI },
{"glProgramUniform4fv", "(IIILjava/nio/FloatBuffer;)V", (void *) android_glProgramUniform4fv__IIILjava_nio_FloatBuffer_2 },
{"glProgramUniformMatrix2fv", "(IIIZ[FI)V", (void *) android_glProgramUniformMatrix2fv__IIIZ_3FI },
{"glProgramUniformMatrix2fv", "(IIIZLjava/nio/FloatBuffer;)V", (void *) android_glProgramUniformMatrix2fv__IIIZLjava_nio_FloatBuffer_2 },
{"glProgramUniformMatrix3fv", "(IIIZ[FI)V", (void *) android_glProgramUniformMatrix3fv__IIIZ_3FI },
{"glProgramUniformMatrix3fv", "(IIIZLjava/nio/FloatBuffer;)V", (void *) android_glProgramUniformMatrix3fv__IIIZLjava_nio_FloatBuffer_2 },
{"glProgramUniformMatrix4fv", "(IIIZ[FI)V", (void *) android_glProgramUniformMatrix4fv__IIIZ_3FI },
{"glProgramUniformMatrix4fv", "(IIIZLjava/nio/FloatBuffer;)V", (void *) android_glProgramUniformMatrix4fv__IIIZLjava_nio_FloatBuffer_2 },
{"glProgramUniformMatrix2x3fv", "(IIIZ[FI)V", (void *) android_glProgramUniformMatrix2x3fv__IIIZ_3FI },
{"glProgramUniformMatrix2x3fv", "(IIIZLjava/nio/FloatBuffer;)V", (void *) android_glProgramUniformMatrix2x3fv__IIIZLjava_nio_FloatBuffer_2 },
{"glProgramUniformMatrix3x2fv", "(IIIZ[FI)V", (void *) android_glProgramUniformMatrix3x2fv__IIIZ_3FI },
{"glProgramUniformMatrix3x2fv", "(IIIZLjava/nio/FloatBuffer;)V", (void *) android_glProgramUniformMatrix3x2fv__IIIZLjava_nio_FloatBuffer_2 },
{"glProgramUniformMatrix2x4fv", "(IIIZ[FI)V", (void *) android_glProgramUniformMatrix2x4fv__IIIZ_3FI },
{"glProgramUniformMatrix2x4fv", "(IIIZLjava/nio/FloatBuffer;)V", (void *) android_glProgramUniformMatrix2x4fv__IIIZLjava_nio_FloatBuffer_2 },
{"glProgramUniformMatrix4x2fv", "(IIIZ[FI)V", (void *) android_glProgramUniformMatrix4x2fv__IIIZ_3FI },
{"glProgramUniformMatrix4x2fv", "(IIIZLjava/nio/FloatBuffer;)V", (void *) android_glProgramUniformMatrix4x2fv__IIIZLjava_nio_FloatBuffer_2 },
{"glProgramUniformMatrix3x4fv", "(IIIZ[FI)V", (void *) android_glProgramUniformMatrix3x4fv__IIIZ_3FI },
{"glProgramUniformMatrix3x4fv", "(IIIZLjava/nio/FloatBuffer;)V", (void *) android_glProgramUniformMatrix3x4fv__IIIZLjava_nio_FloatBuffer_2 },
{"glProgramUniformMatrix4x3fv", "(IIIZ[FI)V", (void *) android_glProgramUniformMatrix4x3fv__IIIZ_3FI },
{"glProgramUniformMatrix4x3fv", "(IIIZLjava/nio/FloatBuffer;)V", (void *) android_glProgramUniformMatrix4x3fv__IIIZLjava_nio_FloatBuffer_2 },
{"glValidateProgramPipeline", "(I)V", (void *) android_glValidateProgramPipeline__I },
{"glGetProgramPipelineInfoLog", "(I)Ljava/lang/String;", (void *) android_glGetProgramPipelineInfoLog },
{"glBindImageTexture", "(IIIZIII)V", (void *) android_glBindImageTexture__IIIZIII },
{"glGetBooleani_v", "(II[ZI)V", (void *) android_glGetBooleani_v__II_3ZI },
{"glGetBooleani_v", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetBooleani_v__IILjava_nio_IntBuffer_2 },
{"glMemoryBarrier", "(I)V", (void *) android_glMemoryBarrier__I },
{"glMemoryBarrierByRegion", "(I)V", (void *) android_glMemoryBarrierByRegion__I },
{"glTexStorage2DMultisample", "(IIIIIZ)V", (void *) android_glTexStorage2DMultisample__IIIIIZ },
{"glGetMultisamplefv", "(II[FI)V", (void *) android_glGetMultisamplefv__II_3FI },
{"glGetMultisamplefv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glGetMultisamplefv__IILjava_nio_FloatBuffer_2 },
{"glSampleMaski", "(II)V", (void *) android_glSampleMaski__II },
{"glGetTexLevelParameteriv", "(III[II)V", (void *) android_glGetTexLevelParameteriv__III_3II },
{"glGetTexLevelParameteriv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glGetTexLevelParameteriv__IIILjava_nio_IntBuffer_2 },
{"glGetTexLevelParameterfv", "(III[FI)V", (void *) android_glGetTexLevelParameterfv__III_3FI },
{"glGetTexLevelParameterfv", "(IIILjava/nio/FloatBuffer;)V", (void *) android_glGetTexLevelParameterfv__IIILjava_nio_FloatBuffer_2 },
{"glBindVertexBuffer", "(IIJI)V", (void *) android_glBindVertexBuffer__IIJI },
{"glVertexAttribFormat", "(IIIZI)V", (void *) android_glVertexAttribFormat__IIIZI },
{"glVertexAttribIFormat", "(IIII)V", (void *) android_glVertexAttribIFormat__IIII },
{"glVertexAttribBinding", "(II)V", (void *) android_glVertexAttribBinding__II },
{"glVertexBindingDivisor", "(II)V", (void *) android_glVertexBindingDivisor__II },
};

int register_android_opengl_jni_GLES31(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));
    return err;
}

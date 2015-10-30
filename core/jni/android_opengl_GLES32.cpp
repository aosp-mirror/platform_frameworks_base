/*
 * Copyright 2015 The Android Open Source Project
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
#pragma GCC diagnostic ignored "-Wunused-but-set-variable"
#pragma GCC diagnostic ignored "-Wunused-function"

#include <stdint.h>
#include <GLES3/gl32.h>
#include <jni.h>
#include <JNIHelp.h>
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
/* void glBlendBarrier ( void ) */
static void
android_glBlendBarrier__
  (JNIEnv *_env, jobject _this) {
    glBlendBarrier();
}

/* void glCopyImageSubData ( GLuint srcName, GLenum srcTarget, GLint srcLevel, GLint srcX, GLint srcY, GLint srcZ, GLuint dstName, GLenum dstTarget, GLint dstLevel, GLint dstX, GLint dstY, GLint dstZ, GLsizei srcWidth, GLsizei srcHeight, GLsizei srcDepth ) */
static void
android_glCopyImageSubData__IIIIIIIIIIIIIII
  (JNIEnv *_env, jobject _this, jint srcName, jint srcTarget, jint srcLevel, jint srcX, jint srcY, jint srcZ, jint dstName, jint dstTarget, jint dstLevel, jint dstX, jint dstY, jint dstZ, jint srcWidth, jint srcHeight, jint srcDepth) {
    glCopyImageSubData(
        (GLuint)srcName,
        (GLenum)srcTarget,
        (GLint)srcLevel,
        (GLint)srcX,
        (GLint)srcY,
        (GLint)srcZ,
        (GLuint)dstName,
        (GLenum)dstTarget,
        (GLint)dstLevel,
        (GLint)dstX,
        (GLint)dstY,
        (GLint)dstZ,
        (GLsizei)srcWidth,
        (GLsizei)srcHeight,
        (GLsizei)srcDepth
    );
}

/* void glDebugMessageControl ( GLenum source, GLenum type, GLenum severity, GLsizei count, const GLuint *ids, GLboolean enabled ) */
static void
android_glDebugMessageControl__IIII_3IIZ
  (JNIEnv *_env, jobject _this, jint source, jint type, jint severity, jint count, jintArray ids_ref, jint offset, jboolean enabled) {
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
    if (_remaining < count) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < count < needed";
        goto exit;
    }
    ids_base = (GLuint *)
        _env->GetIntArrayElements(ids_ref, (jboolean *)0);
    ids = ids_base + offset;

    glDebugMessageControl(
        (GLenum)source,
        (GLenum)type,
        (GLenum)severity,
        (GLsizei)count,
        (GLuint *)ids,
        (GLboolean)enabled
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

/* void glDebugMessageControl ( GLenum source, GLenum type, GLenum severity, GLsizei count, const GLuint *ids, GLboolean enabled ) */
static void
android_glDebugMessageControl__IIIILjava_nio_IntBuffer_2Z
  (JNIEnv *_env, jobject _this, jint source, jint type, jint severity, jint count, jobject ids_buf, jboolean enabled) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *ids = (GLuint *) 0;

    ids = (GLuint *)getPointer(_env, ids_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < count) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < count < needed";
        goto exit;
    }
    if (ids == NULL) {
        char * _idsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        ids = (GLuint *) (_idsBase + _bufferOffset);
    }
    glDebugMessageControl(
        (GLenum)source,
        (GLenum)type,
        (GLenum)severity,
        (GLsizei)count,
        (GLuint *)ids,
        (GLboolean)enabled
    );

exit:
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)ids, JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDebugMessageInsert ( GLenum source, GLenum type, GLuint id, GLenum severity, GLsizei length, const GLchar *buf ) */
static void
android_glDebugMessageInsert__IIIIILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint source, jint type, jint id, jint severity, jint length, jstring buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    const char* _nativebuf = 0;

    if (!buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "buf == null";
        goto exit;
    }
    _nativebuf = _env->GetStringUTFChars(buf, 0);

    glDebugMessageInsert(
        (GLenum)source,
        (GLenum)type,
        (GLuint)id,
        (GLenum)severity,
        (GLsizei)length,
        (GLchar *)_nativebuf
    );

exit:
    if (_nativebuf) {
        _env->ReleaseStringUTFChars(buf, _nativebuf);
    }

    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDebugMessageCallback ( GLDEBUGPROC callback, const void *userParam ) */
static void
android_glDebugMessageCallback(JNIEnv *_env, jobject _this, jobject callback) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
}
/* GLuint glGetDebugMessageLog ( GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog ) */
static jint
android_glGetDebugMessageLog__II_3II_3II_3II_3II_3II_3BI
  (JNIEnv *_env, jobject _this, jint count, jint bufSize, jintArray sources_ref, jint sourcesOffset, jintArray types_ref, jint typesOffset, jintArray ids_ref, jint idsOffset, jintArray severities_ref, jint severitiesOffset, jintArray lengths_ref, jint lengthsOffset, jbyteArray messageLog_ref, jint messageLogOffset) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return 0;
}

/* GLuint glGetDebugMessageLog ( GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog ) */
static uint
android_glGetDebugMessageLog__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_ByteBuffer_2
  (JNIEnv *_env, jobject _this, jint count, jobject sources_ref, jobject types_ref, jobject ids_ref, jobject severities_ref, jobject lengths_ref, jobject messageLog_ref) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return 0;
}

/* GLuint glGetDebugMessageLog ( GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog ) */
static jobjectArray
android_glGetDebugMessageLog__I_3II_3II_3II_3II
  (JNIEnv *_env, jobject _this, jint count, jintArray sources_ref, jint sourcesOffset, jintArray types_ref, jint typesOffset, jintArray ids_ref, jint idsOffset, jintArray severities_ref, jint severitiesOffset) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return 0;
}

/* GLuint glGetDebugMessageLog ( GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog ) */
static jobjectArray
android_glGetDebugMessageLog__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint count, jobject sources_ref, jobject types_ref, jobject ids_ref, jobject severities_ref) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return 0;
}
/* void glPushDebugGroup ( GLenum source, GLuint id, GLsizei length, const GLchar *message ) */
static void
android_glPushDebugGroup__IIILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint source, jint id, jint length, jstring message) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    const char* _nativemessage = 0;
    jsize _stringlen = 0;

    if (!message) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "message == null";
        goto exit;
    }
    _nativemessage = _env->GetStringUTFChars(message, 0);
    _stringlen = _env->GetStringUTFLength(message);
    if (length > _stringlen) {
        _exception = 1;
        _exceptionType = "java/lang/ArrayIndexOutOfBoundsException";
        _exceptionMessage = "length of message is shorter than length argument";
        goto exit;
    }

    glPushDebugGroup(
        (GLenum)source,
        (GLuint)id,
        (GLsizei)length,
        (GLchar *)_nativemessage
    );

exit:
    if (_nativemessage) {
        _env->ReleaseStringUTFChars(message, _nativemessage);
    }

    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glPopDebugGroup ( void ) */
static void
android_glPopDebugGroup__
  (JNIEnv *_env, jobject _this) {
    glPopDebugGroup();
}

/* void glObjectLabel ( GLenum identifier, GLuint name, GLsizei length, const GLchar *label ) */
static void
android_glObjectLabel__IIILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint identifier, jint name, jint length, jstring label) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    const char* _nativelabel = 0;
    jsize _stringlen = 0;

    if (label) {
        _nativelabel = _env->GetStringUTFChars(label, 0);
        _stringlen = _env->GetStringUTFLength(label);
        if (length > _stringlen) {
            _exception = 1;
            _exceptionType = "java/lang/ArrayIndexOutOfBoundsException";
            _exceptionMessage = "length of label is shorter than length argument";
            goto exit;
        }
    }

    glObjectLabel(
        (GLenum)identifier,
        (GLuint)name,
        (GLsizei)length,
        (GLchar *)_nativelabel
    );

exit:
    if (_nativelabel) {
        _env->ReleaseStringUTFChars(label, _nativelabel);
    }

    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetObjectLabel ( GLenum identifier, GLuint name, GLsizei bufSize, GLsizei *length, GLchar *label ) */
static jstring
android_glGetObjectLabel(JNIEnv *_env, jobject _this, jint identifier, jint name) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return NULL;
}

/* void glObjectPtrLabel ( const void *ptr, GLsizei length, const GLchar *label ) */
static void
android_glObjectPtrLabel(JNIEnv *_env, jobject _this, jlong ptr, jstring label) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
}

/* void glGetObjectPtrLabel ( const void *ptr, GLsizei bufSize, GLsizei *length, GLchar *label ) */
static jstring
android_glGetObjectPtrLabel(JNIEnv *_env, jobject _this, jlong ptr) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return NULL;
}

/* void glGetPointerv ( GLenum pname, void **params ) */
static jlong
android_glGetPointerv(JNIEnv *_env, jobject _this, jint pname) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return NULL;
}

/* void glEnablei ( GLenum target, GLuint index ) */
static void
android_glEnablei__II
  (JNIEnv *_env, jobject _this, jint target, jint index) {
    glEnablei(
        (GLenum)target,
        (GLuint)index
    );
}

/* void glDisablei ( GLenum target, GLuint index ) */
static void
android_glDisablei__II
  (JNIEnv *_env, jobject _this, jint target, jint index) {
    glDisablei(
        (GLenum)target,
        (GLuint)index
    );
}

/* void glBlendEquationi ( GLuint buf, GLenum mode ) */
static void
android_glBlendEquationi__II
  (JNIEnv *_env, jobject _this, jint buf, jint mode) {
    glBlendEquationi(
        (GLuint)buf,
        (GLenum)mode
    );
}

/* void glBlendEquationSeparatei ( GLuint buf, GLenum modeRGB, GLenum modeAlpha ) */
static void
android_glBlendEquationSeparatei__III
  (JNIEnv *_env, jobject _this, jint buf, jint modeRGB, jint modeAlpha) {
    glBlendEquationSeparatei(
        (GLuint)buf,
        (GLenum)modeRGB,
        (GLenum)modeAlpha
    );
}

/* void glBlendFunci ( GLuint buf, GLenum src, GLenum dst ) */
static void
android_glBlendFunci__III
  (JNIEnv *_env, jobject _this, jint buf, jint src, jint dst) {
    glBlendFunci(
        (GLuint)buf,
        (GLenum)src,
        (GLenum)dst
    );
}

/* void glBlendFuncSeparatei ( GLuint buf, GLenum srcRGB, GLenum dstRGB, GLenum srcAlpha, GLenum dstAlpha ) */
static void
android_glBlendFuncSeparatei__IIIII
  (JNIEnv *_env, jobject _this, jint buf, jint srcRGB, jint dstRGB, jint srcAlpha, jint dstAlpha) {
    glBlendFuncSeparatei(
        (GLuint)buf,
        (GLenum)srcRGB,
        (GLenum)dstRGB,
        (GLenum)srcAlpha,
        (GLenum)dstAlpha
    );
}

/* void glColorMaski ( GLuint index, GLboolean r, GLboolean g, GLboolean b, GLboolean a ) */
static void
android_glColorMaski__IZZZZ
  (JNIEnv *_env, jobject _this, jint index, jboolean r, jboolean g, jboolean b, jboolean a) {
    glColorMaski(
        (GLuint)index,
        (GLboolean)r,
        (GLboolean)g,
        (GLboolean)b,
        (GLboolean)a
    );
}

/* GLboolean glIsEnabledi ( GLenum target, GLuint index ) */
static jboolean
android_glIsEnabledi__II
  (JNIEnv *_env, jobject _this, jint target, jint index) {
    GLboolean _returnValue;
    _returnValue = glIsEnabledi(
        (GLenum)target,
        (GLuint)index
    );
    return (jboolean)_returnValue;
}

/* void glDrawElementsBaseVertex ( GLenum mode, GLsizei count, GLenum type, const void *indices, GLint basevertex ) */
static void
android_glDrawElementsBaseVertex__IIILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint mode, jint count, jint type, jobject indices_buf, jint basevertex) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    void *indices = (void *) 0;

    indices = (void *)getPointer(_env, indices_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < count-basevertex) {
        _exception = 1;
        _exceptionType = "java/lang/ArrayIndexOutOfBoundsException";
        _exceptionMessage = "remaining() < count-basevertex < needed";
        goto exit;
    }
    if (indices == NULL) {
        char * _indicesBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        indices = (void *) (_indicesBase + _bufferOffset);
    }
    glDrawElementsBaseVertex(
        (GLenum)mode,
        (GLsizei)count,
        (GLenum)type,
        (void *)indices,
        (GLint)basevertex
    );

exit:
    if (_array) {
        releasePointer(_env, _array, indices, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDrawRangeElementsBaseVertex ( GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const void *indices, GLint basevertex ) */
static void
android_glDrawRangeElementsBaseVertex__IIIIILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint mode, jint start, jint end, jint count, jint type, jobject indices_buf, jint basevertex) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    void *indices = (void *) 0;

    indices = (void *)getPointer(_env, indices_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < count-basevertex) {
        _exception = 1;
        _exceptionType = "java/lang/ArrayIndexOutOfBoundsException";
        _exceptionMessage = "remaining() < count-basevertex < needed";
        goto exit;
    }
    if (indices == NULL) {
        char * _indicesBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        indices = (void *) (_indicesBase + _bufferOffset);
    }
    glDrawRangeElementsBaseVertex(
        (GLenum)mode,
        (GLuint)start,
        (GLuint)end,
        (GLsizei)count,
        (GLenum)type,
        (void *)indices,
        (GLint)basevertex
    );

exit:
    if (_array) {
        releasePointer(_env, _array, indices, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDrawElementsInstancedBaseVertex ( GLenum mode, GLsizei count, GLenum type, const void *indices, GLsizei instanceCount, GLint basevertex ) */
static void
android_glDrawElementsInstancedBaseVertex__IIILjava_nio_Buffer_2II
  (JNIEnv *_env, jobject _this, jint mode, jint count, jint type, jobject indices_buf, jint instanceCount, jint basevertex) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    void *indices = (void *) 0;

    indices = (void *)getPointer(_env, indices_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < count-basevertex) {
        _exception = 1;
        _exceptionType = "java/lang/ArrayIndexOutOfBoundsException";
        _exceptionMessage = "remaining() < count-basevertex < needed";
        goto exit;
    }
    if (indices == NULL) {
        char * _indicesBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        indices = (void *) (_indicesBase + _bufferOffset);
    }
    glDrawElementsInstancedBaseVertex(
        (GLenum)mode,
        (GLsizei)count,
        (GLenum)type,
        (void *)indices,
        (GLsizei)instanceCount,
        (GLint) basevertex
    );

exit:
    if (_array) {
        releasePointer(_env, _array, indices, JNI_FALSE);
    }
}

/* void glDrawElementsInstancedBaseVertex ( GLenum mode, GLsizei count, GLenum type, const void *indices, GLsizei instanceCount, GLint basevertex ) */
static void
android_glDrawElementsInstancedBaseVertex__IIIIII
  (JNIEnv *_env, jobject _this, jint mode, jint count, jint type, jint indicesOffset, jint instanceCount, jint basevertex) {
    glDrawElementsInstancedBaseVertex(
        (GLenum)mode,
        (GLsizei)count,
        (GLenum)type,
        (void *)static_cast<uintptr_t>(indicesOffset),
        (GLsizei)instanceCount,
        (GLint)basevertex
    );
}
/* void glFramebufferTexture ( GLenum target, GLenum attachment, GLuint texture, GLint level ) */
static void
android_glFramebufferTexture__IIII
  (JNIEnv *_env, jobject _this, jint target, jint attachment, jint texture, jint level) {
    glFramebufferTexture(
        (GLenum)target,
        (GLenum)attachment,
        (GLuint)texture,
        (GLint)level
    );
}

/* void glPrimitiveBoundingBox ( GLfloat minX, GLfloat minY, GLfloat minZ, GLfloat minW, GLfloat maxX, GLfloat maxY, GLfloat maxZ, GLfloat maxW ) */
static void
android_glPrimitiveBoundingBox__FFFFFFFF
  (JNIEnv *_env, jobject _this, jfloat minX, jfloat minY, jfloat minZ, jfloat minW, jfloat maxX, jfloat maxY, jfloat maxZ, jfloat maxW) {
    glPrimitiveBoundingBox(
        (GLfloat)minX,
        (GLfloat)minY,
        (GLfloat)minZ,
        (GLfloat)minW,
        (GLfloat)maxX,
        (GLfloat)maxY,
        (GLfloat)maxZ,
        (GLfloat)maxW
    );
}

/* GLenum glGetGraphicsResetStatus ( void ) */
static jint
android_glGetGraphicsResetStatus__
  (JNIEnv *_env, jobject _this) {
    GLenum _returnValue;
    _returnValue = glGetGraphicsResetStatus();
    return (jint)_returnValue;
}

/* void glReadnPixels ( GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, GLsizei bufSize, void *data ) */
static void
android_glReadnPixels__IIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint x, jint y, jint width, jint height, jint format, jint type, jint bufSize, jobject data_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    void *data = (void *) 0;

    data = (void *)getPointer(_env, data_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < bufSize) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < bufSize < needed";
        goto exit;
    }
    if (data == NULL) {
        char * _dataBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        data = (void *) (_dataBase + _bufferOffset);
    }
    glReadnPixels(
        (GLint)x,
        (GLint)y,
        (GLsizei)width,
        (GLsizei)height,
        (GLenum)format,
        (GLenum)type,
        (GLsizei)bufSize,
        (void *)data
    );

exit:
    if (_array) {
        releasePointer(_env, _array, data, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetnUniformfv ( GLuint program, GLint location, GLsizei bufSize, GLfloat *params ) */
static void
android_glGetnUniformfv__III_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jint bufSize, jfloatArray params_ref, jint offset) {
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
    if (_remaining < bufSize) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < bufSize < needed";
        goto exit;
    }
    params_base = (GLfloat *)
        _env->GetFloatArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetnUniformfv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)bufSize,
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

/* void glGetnUniformfv ( GLuint program, GLint location, GLsizei bufSize, GLfloat *params ) */
static void
android_glGetnUniformfv__IIILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint bufSize, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jfloatArray _array = (jfloatArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    params = (GLfloat *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < bufSize) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < bufSize < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetFloatArrayElements(_array, (jboolean *) 0);
        params = (GLfloat *) (_paramsBase + _bufferOffset);
    }
    glGetnUniformfv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)bufSize,
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

/* void glGetnUniformiv ( GLuint program, GLint location, GLsizei bufSize, GLint *params ) */
static void
android_glGetnUniformiv__III_3II
  (JNIEnv *_env, jobject _this, jint program, jint location, jint bufSize, jintArray params_ref, jint offset) {
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
    if (_remaining < bufSize) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < bufSize < needed";
        goto exit;
    }
    params_base = (GLint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetnUniformiv(
        (GLuint)program,
        (GLint)location,
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

/* void glGetnUniformiv ( GLuint program, GLint location, GLsizei bufSize, GLint *params ) */
static void
android_glGetnUniformiv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint bufSize, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < bufSize) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < bufSize < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetnUniformiv(
        (GLuint)program,
        (GLint)location,
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

/* void glGetnUniformuiv ( GLuint program, GLint location, GLsizei bufSize, GLuint *params ) */
static void
android_glGetnUniformuiv__III_3II
  (JNIEnv *_env, jobject _this, jint program, jint location, jint bufSize, jintArray params_ref, jint offset) {
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
    if (_remaining < bufSize) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < bufSize < needed";
        goto exit;
    }
    params_base = (GLuint *)
        _env->GetIntArrayElements(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetnUniformuiv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)bufSize,
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

/* void glGetnUniformuiv ( GLuint program, GLint location, GLsizei bufSize, GLuint *params ) */
static void
android_glGetnUniformuiv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jint bufSize, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

    params = (GLuint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (_remaining < bufSize) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < bufSize < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLuint *) (_paramsBase + _bufferOffset);
    }
    glGetnUniformuiv(
        (GLuint)program,
        (GLint)location,
        (GLsizei)bufSize,
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

/* void glMinSampleShading ( GLfloat value ) */
static void
android_glMinSampleShading__F
  (JNIEnv *_env, jobject _this, jfloat value) {
    glMinSampleShading(
        (GLfloat)value
    );
}

/* void glPatchParameteri ( GLenum pname, GLint value ) */
static void
android_glPatchParameteri__II
  (JNIEnv *_env, jobject _this, jint pname, jint value) {
    glPatchParameteri(
        (GLenum)pname,
        (GLint)value
    );
}

/* void glTexParameterIiv ( GLenum target, GLenum pname, const GLint *params ) */
static void
android_glTexParameterIiv__II_3II
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

    glTexParameterIiv(
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

/* void glTexParameterIiv ( GLenum target, GLenum pname, const GLint *params ) */
static void
android_glTexParameterIiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glTexParameterIiv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, JNI_ABORT);
    }
}

/* void glTexParameterIuiv ( GLenum target, GLenum pname, const GLuint *params ) */
static void
android_glTexParameterIuiv__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint pname, jintArray params_ref, jint offset) {
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

    glTexParameterIuiv(
        (GLenum)target,
        (GLenum)pname,
        (GLuint *)params
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

/* void glTexParameterIuiv ( GLenum target, GLenum pname, const GLuint *params ) */
static void
android_glTexParameterIuiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

    params = (GLuint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLuint *) (_paramsBase + _bufferOffset);
    }
    glTexParameterIuiv(
        (GLenum)target,
        (GLenum)pname,
        (GLuint *)params
    );
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, JNI_ABORT);
    }
}

/* void glGetTexParameterIiv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetTexParameterIiv__II_3II
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

    glGetTexParameterIiv(
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

/* void glGetTexParameterIiv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetTexParameterIiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetTexParameterIiv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, 0);
    }
}

/* void glGetTexParameterIuiv ( GLenum target, GLenum pname, GLuint *params ) */
static void
android_glGetTexParameterIuiv__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint pname, jintArray params_ref, jint offset) {
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

    glGetTexParameterIuiv(
        (GLenum)target,
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

/* void glGetTexParameterIuiv ( GLenum target, GLenum pname, GLuint *params ) */
static void
android_glGetTexParameterIuiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

    params = (GLuint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLuint *) (_paramsBase + _bufferOffset);
    }
    glGetTexParameterIuiv(
        (GLenum)target,
        (GLenum)pname,
        (GLuint *)params
    );
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, 0);
    }
}

/* void glSamplerParameterIiv ( GLuint sampler, GLenum pname, const GLint *param ) */
static void
android_glSamplerParameterIiv__II_3II
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

    glSamplerParameterIiv(
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

/* void glSamplerParameterIiv ( GLuint sampler, GLenum pname, const GLint *param ) */
static void
android_glSamplerParameterIiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jobject param_buf) {
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *param = (GLint *) 0;

    param = (GLint *)getPointer(_env, param_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (param == NULL) {
        char * _paramBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        param = (GLint *) (_paramBase + _bufferOffset);
    }
    glSamplerParameterIiv(
        (GLuint)sampler,
        (GLenum)pname,
        (GLint *)param
    );
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)param, JNI_ABORT);
    }
}

/* void glSamplerParameterIuiv ( GLuint sampler, GLenum pname, const GLuint *param ) */
static void
android_glSamplerParameterIuiv__II_3II
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jintArray param_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLuint *param_base = (GLuint *) 0;
    jint _remaining;
    GLuint *param = (GLuint *) 0;

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
    param_base = (GLuint *)
        _env->GetIntArrayElements(param_ref, (jboolean *)0);
    param = param_base + offset;

    glSamplerParameterIuiv(
        (GLuint)sampler,
        (GLenum)pname,
        (GLuint *)param
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

/* void glSamplerParameterIuiv ( GLuint sampler, GLenum pname, const GLuint *param ) */
static void
android_glSamplerParameterIuiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jobject param_buf) {
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *param = (GLuint *) 0;

    param = (GLuint *)getPointer(_env, param_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (param == NULL) {
        char * _paramBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        param = (GLuint *) (_paramBase + _bufferOffset);
    }
    glSamplerParameterIuiv(
        (GLuint)sampler,
        (GLenum)pname,
        (GLuint *)param
    );
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)param, JNI_ABORT);
    }
}

/* void glGetSamplerParameterIiv ( GLuint sampler, GLenum pname, GLint *params ) */
static void
android_glGetSamplerParameterIiv__II_3II
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

    glGetSamplerParameterIiv(
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

/* void glGetSamplerParameterIiv ( GLuint sampler, GLenum pname, GLint *params ) */
static void
android_glGetSamplerParameterIiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jobject params_buf) {
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetSamplerParameterIiv(
        (GLuint)sampler,
        (GLenum)pname,
        (GLint *)params
    );
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, 0);
    }
}

/* void glGetSamplerParameterIuiv ( GLuint sampler, GLenum pname, GLuint *params ) */
static void
android_glGetSamplerParameterIuiv__II_3II
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jintArray params_ref, jint offset) {
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

    glGetSamplerParameterIuiv(
        (GLuint)sampler,
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

/* void glGetSamplerParameterIuiv ( GLuint sampler, GLenum pname, GLuint *params ) */
static void
android_glGetSamplerParameterIuiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jobject params_buf) {
    jintArray _array = (jintArray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

    params = (GLuint *)getPointer(_env, params_buf, (jarray*)&_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetIntArrayElements(_array, (jboolean *) 0);
        params = (GLuint *) (_paramsBase + _bufferOffset);
    }
    glGetSamplerParameterIuiv(
        (GLuint)sampler,
        (GLenum)pname,
        (GLuint *)params
    );
    if (_array) {
        _env->ReleaseIntArrayElements(_array, (jint*)params, 0);
    }
}

/* void glTexBuffer ( GLenum target, GLenum internalformat, GLuint buffer ) */
static void
android_glTexBuffer__III
  (JNIEnv *_env, jobject _this, jint target, jint internalformat, jint buffer) {
    glTexBuffer(
        (GLenum)target,
        (GLenum)internalformat,
        (GLuint)buffer
    );
}

/* void glTexBufferRange ( GLenum target, GLenum internalformat, GLuint buffer, GLintptr offset, GLsizeiptr size ) */
static void
android_glTexBufferRange__IIIII
  (JNIEnv *_env, jobject _this, jint target, jint internalformat, jint buffer, jint offset, jint size) {
    glTexBufferRange(
        (GLenum)target,
        (GLenum)internalformat,
        (GLuint)buffer,
        (GLintptr)offset,
        (GLsizeiptr)size
    );
}

/* void glTexStorage3DMultisample ( GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height, GLsizei depth, GLboolean fixedsamplelocations ) */
static void
android_glTexStorage3DMultisample__IIIIIIZ
  (JNIEnv *_env, jobject _this, jint target, jint samples, jint internalformat, jint width, jint height, jint depth, jboolean fixedsamplelocations) {
    glTexStorage3DMultisample(
        (GLenum)target,
        (GLsizei)samples,
        (GLenum)internalformat,
        (GLsizei)width,
        (GLsizei)height,
        (GLsizei)depth,
        (GLboolean)fixedsamplelocations
    );
}

static const char *classPathName = "android/opengl/GLES32";

static const JNINativeMethod methods[] = {
{"_nativeClassInit", "()V", (void*)nativeClassInit },
{"glBlendBarrier", "()V", (void *) android_glBlendBarrier__ },
{"glCopyImageSubData", "(IIIIIIIIIIIIIII)V", (void *) android_glCopyImageSubData__IIIIIIIIIIIIIII },
{"glDebugMessageControl", "(IIII[IIZ)V", (void *) android_glDebugMessageControl__IIII_3IIZ },
{"glDebugMessageControl", "(IIIILjava/nio/IntBuffer;Z)V", (void *) android_glDebugMessageControl__IIIILjava_nio_IntBuffer_2Z },
{"glDebugMessageInsert", "(IIIIILjava/lang/String;)V", (void *) android_glDebugMessageInsert__IIIIILjava_lang_String_2 },
{"glDebugMessageCallback", "(Landroid/opengl/GLES32$DebugProc;)V", (void *) android_glDebugMessageCallback },
{"glGetDebugMessageLog", "(II[II[II[II[II[II[BI)I", (void *) android_glGetDebugMessageLog__II_3II_3II_3II_3II_3II_3BI },
{"glGetDebugMessageLog", "(ILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/ByteBuffer;)I", (void *) android_glGetDebugMessageLog__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_ByteBuffer_2 },
{"glGetDebugMessageLog", "(I[II[II[II[II)[Ljava/lang/String;", (void *) android_glGetDebugMessageLog__I_3II_3II_3II_3II },
{"glGetDebugMessageLog", "(ILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;)[Ljava/lang/String;", (void *) android_glGetDebugMessageLog__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2 },
{"glPushDebugGroup", "(IIILjava/lang/String;)V", (void *) android_glPushDebugGroup__IIILjava_lang_String_2 },
{"glPopDebugGroup", "()V", (void *) android_glPopDebugGroup__ },
{"glObjectLabel", "(IIILjava/lang/String;)V", (void *) android_glObjectLabel__IIILjava_lang_String_2 },
{"glGetObjectLabel", "(II)Ljava/lang/String;", (void *) android_glGetObjectLabel },
{"glObjectPtrLabel", "(JLjava/lang/String;)V", (void *) android_glObjectPtrLabel },
{"glGetObjectPtrLabel", "(J)Ljava/lang/String;", (void *) android_glGetObjectPtrLabel },
{"glGetPointerv", "(I)J", (void *) android_glGetPointerv },
{"glEnablei", "(II)V", (void *) android_glEnablei__II },
{"glDisablei", "(II)V", (void *) android_glDisablei__II },
{"glBlendEquationi", "(II)V", (void *) android_glBlendEquationi__II },
{"glBlendEquationSeparatei", "(III)V", (void *) android_glBlendEquationSeparatei__III },
{"glBlendFunci", "(III)V", (void *) android_glBlendFunci__III },
{"glBlendFuncSeparatei", "(IIIII)V", (void *) android_glBlendFuncSeparatei__IIIII },
{"glColorMaski", "(IZZZZ)V", (void *) android_glColorMaski__IZZZZ },
{"glIsEnabledi", "(II)Z", (void *) android_glIsEnabledi__II },
{"glDrawElementsBaseVertex", "(IIILjava/nio/Buffer;I)V", (void *) android_glDrawElementsBaseVertex__IIILjava_nio_Buffer_2I },
{"glDrawRangeElementsBaseVertex", "(IIIIILjava/nio/Buffer;I)V", (void *) android_glDrawRangeElementsBaseVertex__IIIIILjava_nio_Buffer_2I },
{"glDrawElementsInstancedBaseVertex", "(IIILjava/nio/Buffer;II)V", (void *) android_glDrawElementsInstancedBaseVertex__IIILjava_nio_Buffer_2II },
{"glDrawElementsInstancedBaseVertex", "(IIIIII)V", (void *) android_glDrawElementsInstancedBaseVertex__IIIIII },
{"glFramebufferTexture", "(IIII)V", (void *) android_glFramebufferTexture__IIII },
{"glPrimitiveBoundingBox", "(FFFFFFFF)V", (void *) android_glPrimitiveBoundingBox__FFFFFFFF },
{"glGetGraphicsResetStatus", "()I", (void *) android_glGetGraphicsResetStatus__ },
{"glReadnPixels", "(IIIIIIILjava/nio/Buffer;)V", (void *) android_glReadnPixels__IIIIIIILjava_nio_Buffer_2 },
{"glGetnUniformfv", "(III[FI)V", (void *) android_glGetnUniformfv__III_3FI },
{"glGetnUniformfv", "(IIILjava/nio/FloatBuffer;)V", (void *) android_glGetnUniformfv__IIILjava_nio_FloatBuffer_2 },
{"glGetnUniformiv", "(III[II)V", (void *) android_glGetnUniformiv__III_3II },
{"glGetnUniformiv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glGetnUniformiv__IIILjava_nio_IntBuffer_2 },
{"glGetnUniformuiv", "(III[II)V", (void *) android_glGetnUniformuiv__III_3II },
{"glGetnUniformuiv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glGetnUniformuiv__IIILjava_nio_IntBuffer_2 },
{"glMinSampleShading", "(F)V", (void *) android_glMinSampleShading__F },
{"glPatchParameteri", "(II)V", (void *) android_glPatchParameteri__II },
{"glTexParameterIiv", "(II[II)V", (void *) android_glTexParameterIiv__II_3II },
{"glTexParameterIiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexParameterIiv__IILjava_nio_IntBuffer_2 },
{"glTexParameterIuiv", "(II[II)V", (void *) android_glTexParameterIuiv__II_3II },
{"glTexParameterIuiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexParameterIuiv__IILjava_nio_IntBuffer_2 },
{"glGetTexParameterIiv", "(II[II)V", (void *) android_glGetTexParameterIiv__II_3II },
{"glGetTexParameterIiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexParameterIiv__IILjava_nio_IntBuffer_2 },
{"glGetTexParameterIuiv", "(II[II)V", (void *) android_glGetTexParameterIuiv__II_3II },
{"glGetTexParameterIuiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexParameterIuiv__IILjava_nio_IntBuffer_2 },
{"glSamplerParameterIiv", "(II[II)V", (void *) android_glSamplerParameterIiv__II_3II },
{"glSamplerParameterIiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glSamplerParameterIiv__IILjava_nio_IntBuffer_2 },
{"glSamplerParameterIuiv", "(II[II)V", (void *) android_glSamplerParameterIuiv__II_3II },
{"glSamplerParameterIuiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glSamplerParameterIuiv__IILjava_nio_IntBuffer_2 },
{"glGetSamplerParameterIiv", "(II[II)V", (void *) android_glGetSamplerParameterIiv__II_3II },
{"glGetSamplerParameterIiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetSamplerParameterIiv__IILjava_nio_IntBuffer_2 },
{"glGetSamplerParameterIuiv", "(II[II)V", (void *) android_glGetSamplerParameterIuiv__II_3II },
{"glGetSamplerParameterIuiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetSamplerParameterIuiv__IILjava_nio_IntBuffer_2 },
{"glTexBuffer", "(III)V", (void *) android_glTexBuffer__III },
{"glTexBufferRange", "(IIIII)V", (void *) android_glTexBufferRange__IIIII },
{"glTexStorage3DMultisample", "(IIIIIIZ)V", (void *) android_glTexStorage3DMultisample__IIIIIIZ },
};

int register_android_opengl_jni_GLES32(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));
    return err;
}

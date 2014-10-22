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

#include <GLES3/gl31.h>
#include <GLES2/gl2ext.h>

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

template <typename JTYPEARRAY, typename CTYPE, void GET(GLenum, CTYPE*)>
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
    params_base = (CTYPE *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    GET(
        (GLenum)pname,
        (CTYPE *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}


template <typename CTYPE, void GET(GLenum, CTYPE*)>
static void
getarray
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType;
    const char * _exceptionMessage;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    CTYPE *params = (CTYPE *) 0;
    int _needed = 0;

    params = (CTYPE *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
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
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (CTYPE *) (_paramsBase + _bufferOffset);
    }
    GET(
        (GLenum)pname,
        (CTYPE *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

// --------------------------------------------------------------------------
/* void glBlendBarrierKHR ( void ) */
static void
android_glBlendBarrierKHR__
  (JNIEnv *_env, jobject _this) {
    glBlendBarrierKHR();
}

/* void glDebugMessageControlKHR ( GLenum source, GLenum type, GLenum severity, GLsizei count, const GLuint *ids, GLboolean enabled ) */
static void
android_glDebugMessageControlKHR__IIII_3IIZ
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
    ids_base = (GLuint *)
        _env->GetPrimitiveArrayCritical(ids_ref, (jboolean *)0);
    ids = ids_base + offset;

    glDebugMessageControlKHR(
        (GLenum)source,
        (GLenum)type,
        (GLenum)severity,
        (GLsizei)count,
        (GLuint *)ids,
        (GLboolean)enabled
    );

exit:
    if (ids_base) {
        _env->ReleasePrimitiveArrayCritical(ids_ref, ids_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDebugMessageControlKHR ( GLenum source, GLenum type, GLenum severity, GLsizei count, const GLuint *ids, GLboolean enabled ) */
static void
android_glDebugMessageControlKHR__IIIILjava_nio_IntBuffer_2Z
  (JNIEnv *_env, jobject _this, jint source, jint type, jint severity, jint count, jobject ids_buf, jboolean enabled) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *ids = (GLuint *) 0;

    ids = (GLuint *)getPointer(_env, ids_buf, &_array, &_remaining, &_bufferOffset);
    if (ids == NULL) {
        char * _idsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        ids = (GLuint *) (_idsBase + _bufferOffset);
    }
    glDebugMessageControlKHR(
        (GLenum)source,
        (GLenum)type,
        (GLenum)severity,
        (GLsizei)count,
        (GLuint *)ids,
        (GLboolean)enabled
    );
    if (_array) {
        releasePointer(_env, _array, ids, JNI_FALSE);
    }
}

/* void glDebugMessageInsertKHR ( GLenum source, GLenum type, GLuint id, GLenum severity, GLsizei length, const GLchar *buf ) */
static void
android_glDebugMessageInsertKHR__IIIILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint source, jint type, jint id, jint severity, jstring buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    const char* _nativebuf = 0;
    jint _length = 0;

    if (!buf) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "buf == null";
        goto exit;
    }
    _nativebuf = _env->GetStringUTFChars(buf, 0);
    _length = _env->GetStringUTFLength(buf);

    glDebugMessageInsertKHR(
        (GLenum)source,
        (GLenum)type,
        (GLuint)id,
        (GLenum)severity,
        (GLsizei)_length,
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

/* void glDebugMessageCallbackKHR ( GLDEBUGPROCKHR callback, const void *userParam ) */
static void
android_glDebugMessageCallbackKHR(JNIEnv *_env, jobject _this, jobject callback) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
}
/* GLuint glGetDebugMessageLogKHR ( GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog ) */
static jint
android_glGetDebugMessageLogKHR__II_3II_3II_3II_3II_3II_3BI
  (JNIEnv *_env, jobject _this, jint count, jint bufSize, jintArray sources_ref, jint sourcesOffset, jintArray types_ref, jint typesOffset, jintArray ids_ref, jint idsOffset, jintArray severities_ref, jint severitiesOffset, jintArray lengths_ref, jint lengthsOffset, jbyteArray messageLog_ref, jint messageLogOffset) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return 0;
}

/* GLuint glGetDebugMessageLogKHR ( GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog ) */
static uint
android_glGetDebugMessageLogKHR__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_ByteBuffer_2
  (JNIEnv *_env, jobject _this, jint count, jobject sources_ref, jobject types_ref, jobject ids_ref, jobject severities_ref, jobject lengths_ref, jobject messageLog_ref) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return 0;
}

/* GLuint glGetDebugMessageLogKHR ( GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog ) */
static jobjectArray
android_glGetDebugMessageLogKHR__I_3II_3II_3II_3II
  (JNIEnv *_env, jobject _this, jint count, jintArray sources_ref, jint sourcesOffset, jintArray types_ref, jint typesOffset, jintArray ids_ref, jint idsOffset, jintArray severities_ref, jint severitiesOffset) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return 0;
}

/* GLuint glGetDebugMessageLogKHR ( GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog ) */
static jobjectArray
android_glGetDebugMessageLogKHR__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint count, jobject sources_ref, jobject types_ref, jobject ids_ref, jobject severities_ref) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return 0;
}
/* void glPushDebugGroupKHR ( GLenum source, GLuint id, GLsizei length, const GLchar *message ) */
static void
android_glPushDebugGroupKHR__IIILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint source, jint id, jint length, jstring message) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    const char* _nativemessage = 0;

    if (!message) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "message == null";
        goto exit;
    }
    _nativemessage = _env->GetStringUTFChars(message, 0);

    glPushDebugGroupKHR(
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

/* void glPopDebugGroupKHR ( void ) */
static void
android_glPopDebugGroupKHR__
  (JNIEnv *_env, jobject _this) {
    glPopDebugGroupKHR();
}

/* void glObjectLabelKHR ( GLenum identifier, GLuint name, GLsizei length, const GLchar *label ) */
static void
android_glObjectLabelKHR__IIILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint identifier, jint name, jint length, jstring label) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    const char* _nativelabel = 0;

    if (!label) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "label == null";
        goto exit;
    }
    _nativelabel = _env->GetStringUTFChars(label, 0);

    glObjectLabelKHR(
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

/* void glGetObjectLabelKHR ( GLenum identifier, GLuint name, GLsizei bufSize, GLsizei *length, GLchar *label ) */
static jstring
android_glGetObjectLabelKHR(JNIEnv *_env, jobject _this, jint identifier, jint name) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return NULL;
}

/* void glObjectPtrLabelKHR ( const void *ptr, GLsizei length, const GLchar *label ) */
static void
android_glObjectPtrLabelKHR(JNIEnv *_env, jobject _this, jlong ptr, jstring label) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
}

/* void glGetObjectPtrLabelKHR ( const void *ptr, GLsizei bufSize, GLsizei *length, GLchar *label ) */
static jstring
android_glGetObjectPtrLabelKHR(JNIEnv *_env, jobject _this, jlong ptr) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return NULL;
}

/* void glGetPointervKHR ( GLenum pname, void **params ) */
static jobject
android_glGetDebugMessageCallbackKHR(JNIEnv *_env, jobject _this) {
    jniThrowException(_env, "java/lang/UnsupportedOperationException", "not yet implemented");
    return NULL;
}

/* void glMinSampleShadingOES ( GLfloat value ) */
static void
android_glMinSampleShadingOES__F
  (JNIEnv *_env, jobject _this, jfloat value) {
    glMinSampleShadingOES(
        (GLfloat)value
    );
}

/* void glTexStorage3DMultisampleOES ( GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height, GLsizei depth, GLboolean fixedsamplelocations ) */
static void
android_glTexStorage3DMultisampleOES__IIIIIIZ
  (JNIEnv *_env, jobject _this, jint target, jint samples, jint internalformat, jint width, jint height, jint depth, jboolean fixedsamplelocations) {
    glTexStorage3DMultisampleOES(
        (GLenum)target,
        (GLsizei)samples,
        (GLenum)internalformat,
        (GLsizei)width,
        (GLsizei)height,
        (GLsizei)depth,
        (GLboolean)fixedsamplelocations
    );
}

/* void glCopyImageSubDataEXT ( GLuint srcName, GLenum srcTarget, GLint srcLevel, GLint srcX, GLint srcY, GLint srcZ, GLuint dstName, GLenum dstTarget, GLint dstLevel, GLint dstX, GLint dstY, GLint dstZ, GLsizei srcWidth, GLsizei srcHeight, GLsizei srcDepth ) */
static void
android_glCopyImageSubDataEXT__IIIIIIIIIIIIIII
  (JNIEnv *_env, jobject _this, jint srcName, jint srcTarget, jint srcLevel, jint srcX, jint srcY, jint srcZ, jint dstName, jint dstTarget, jint dstLevel, jint dstX, jint dstY, jint dstZ, jint srcWidth, jint srcHeight, jint srcDepth) {
    glCopyImageSubDataEXT(
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

/* void glEnableiEXT ( GLenum target, GLuint index ) */
static void
android_glEnableiEXT__II
  (JNIEnv *_env, jobject _this, jint target, jint index) {
    glEnableiEXT(
        (GLenum)target,
        (GLuint)index
    );
}

/* void glDisableiEXT ( GLenum target, GLuint index ) */
static void
android_glDisableiEXT__II
  (JNIEnv *_env, jobject _this, jint target, jint index) {
    glDisableiEXT(
        (GLenum)target,
        (GLuint)index
    );
}

/* void glBlendEquationiEXT ( GLuint buf, GLenum mode ) */
static void
android_glBlendEquationiEXT__II
  (JNIEnv *_env, jobject _this, jint buf, jint mode) {
    glBlendEquationiEXT(
        (GLuint)buf,
        (GLenum)mode
    );
}

/* void glBlendEquationSeparateiEXT ( GLuint buf, GLenum modeRGB, GLenum modeAlpha ) */
static void
android_glBlendEquationSeparateiEXT__III
  (JNIEnv *_env, jobject _this, jint buf, jint modeRGB, jint modeAlpha) {
    glBlendEquationSeparateiEXT(
        (GLuint)buf,
        (GLenum)modeRGB,
        (GLenum)modeAlpha
    );
}

/* void glBlendFunciEXT ( GLuint buf, GLenum src, GLenum dst ) */
static void
android_glBlendFunciEXT__III
  (JNIEnv *_env, jobject _this, jint buf, jint src, jint dst) {
    glBlendFunciEXT(
        (GLuint)buf,
        (GLenum)src,
        (GLenum)dst
    );
}

/* void glBlendFuncSeparateiEXT ( GLuint buf, GLenum srcRGB, GLenum dstRGB, GLenum srcAlpha, GLenum dstAlpha ) */
static void
android_glBlendFuncSeparateiEXT__IIIII
  (JNIEnv *_env, jobject _this, jint buf, jint srcRGB, jint dstRGB, jint srcAlpha, jint dstAlpha) {
    glBlendFuncSeparateiEXT(
        (GLuint)buf,
        (GLenum)srcRGB,
        (GLenum)dstRGB,
        (GLenum)srcAlpha,
        (GLenum)dstAlpha
    );
}

/* void glColorMaskiEXT ( GLuint index, GLboolean r, GLboolean g, GLboolean b, GLboolean a ) */
static void
android_glColorMaskiEXT__IZZZZ
  (JNIEnv *_env, jobject _this, jint index, jboolean r, jboolean g, jboolean b, jboolean a) {
    glColorMaskiEXT(
        (GLuint)index,
        (GLboolean)r,
        (GLboolean)g,
        (GLboolean)b,
        (GLboolean)a
    );
}

/* GLboolean glIsEnablediEXT ( GLenum target, GLuint index ) */
static jboolean
android_glIsEnablediEXT__II
  (JNIEnv *_env, jobject _this, jint target, jint index) {
    GLboolean _returnValue;
    _returnValue = glIsEnablediEXT(
        (GLenum)target,
        (GLuint)index
    );
    return (jboolean)_returnValue;
}

/* void glFramebufferTextureEXT ( GLenum target, GLenum attachment, GLuint texture, GLint level ) */
static void
android_glFramebufferTextureEXT__IIII
  (JNIEnv *_env, jobject _this, jint target, jint attachment, jint texture, jint level) {
    glFramebufferTextureEXT(
        (GLenum)target,
        (GLenum)attachment,
        (GLuint)texture,
        (GLint)level
    );
}

/* void glPrimitiveBoundingBoxEXT ( GLfloat minX, GLfloat minY, GLfloat minZ, GLfloat minW, GLfloat maxX, GLfloat maxY, GLfloat maxZ, GLfloat maxW ) */
static void
android_glPrimitiveBoundingBoxEXT__FFFFFFFF
  (JNIEnv *_env, jobject _this, jfloat minX, jfloat minY, jfloat minZ, jfloat minW, jfloat maxX, jfloat maxY, jfloat maxZ, jfloat maxW) {
    glPrimitiveBoundingBoxEXT(
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

/* void glPatchParameteriEXT ( GLenum pname, GLint value ) */
static void
android_glPatchParameteriEXT__II
  (JNIEnv *_env, jobject _this, jint pname, jint value) {
    glPatchParameteriEXT(
        (GLenum)pname,
        (GLint)value
    );
}

/* void glTexParameterIivEXT ( GLenum target, GLenum pname, const GLint *params ) */
static void
android_glTexParameterIivEXT__II_3II
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glTexParameterIivEXT(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexParameterIivEXT ( GLenum target, GLenum pname, const GLint *params ) */
static void
android_glTexParameterIivEXT__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glTexParameterIivEXT(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );
    if (_array) {
        releasePointer(_env, _array, params, JNI_FALSE);
    }
}

/* void glTexParameterIuivEXT ( GLenum target, GLenum pname, const GLuint *params ) */
static void
android_glTexParameterIuivEXT__II_3II
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glTexParameterIuivEXT(
        (GLenum)target,
        (GLenum)pname,
        (GLuint *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexParameterIuivEXT ( GLenum target, GLenum pname, const GLuint *params ) */
static void
android_glTexParameterIuivEXT__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

    params = (GLuint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLuint *) (_paramsBase + _bufferOffset);
    }
    glTexParameterIuivEXT(
        (GLenum)target,
        (GLenum)pname,
        (GLuint *)params
    );
    if (_array) {
        releasePointer(_env, _array, params, JNI_FALSE);
    }
}

/* void glGetTexParameterIivEXT ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetTexParameterIivEXT__II_3II
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexParameterIivEXT(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexParameterIivEXT ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetTexParameterIivEXT__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetTexParameterIivEXT(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );
    if (_array) {
        releasePointer(_env, _array, params, JNI_TRUE);
    }
}

/* void glGetTexParameterIuivEXT ( GLenum target, GLenum pname, GLuint *params ) */
static void
android_glGetTexParameterIuivEXT__II_3II
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexParameterIuivEXT(
        (GLenum)target,
        (GLenum)pname,
        (GLuint *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetTexParameterIuivEXT ( GLenum target, GLenum pname, GLuint *params ) */
static void
android_glGetTexParameterIuivEXT__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

    params = (GLuint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLuint *) (_paramsBase + _bufferOffset);
    }
    glGetTexParameterIuivEXT(
        (GLenum)target,
        (GLenum)pname,
        (GLuint *)params
    );
    if (_array) {
        releasePointer(_env, _array, params, JNI_TRUE);
    }
}

/* void glSamplerParameterIivEXT ( GLuint sampler, GLenum pname, const GLint *param ) */
static void
android_glSamplerParameterIivEXT__II_3II
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
        _env->GetPrimitiveArrayCritical(param_ref, (jboolean *)0);
    param = param_base + offset;

    glSamplerParameterIivEXT(
        (GLuint)sampler,
        (GLenum)pname,
        (GLint *)param
    );

exit:
    if (param_base) {
        _env->ReleasePrimitiveArrayCritical(param_ref, param_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glSamplerParameterIivEXT ( GLuint sampler, GLenum pname, const GLint *param ) */
static void
android_glSamplerParameterIivEXT__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jobject param_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *param = (GLint *) 0;

    param = (GLint *)getPointer(_env, param_buf, &_array, &_remaining, &_bufferOffset);
    if (param == NULL) {
        char * _paramBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        param = (GLint *) (_paramBase + _bufferOffset);
    }
    glSamplerParameterIivEXT(
        (GLuint)sampler,
        (GLenum)pname,
        (GLint *)param
    );
    if (_array) {
        releasePointer(_env, _array, param, JNI_FALSE);
    }
}

/* void glSamplerParameterIuivEXT ( GLuint sampler, GLenum pname, const GLuint *param ) */
static void
android_glSamplerParameterIuivEXT__II_3II
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
        _env->GetPrimitiveArrayCritical(param_ref, (jboolean *)0);
    param = param_base + offset;

    glSamplerParameterIuivEXT(
        (GLuint)sampler,
        (GLenum)pname,
        (GLuint *)param
    );

exit:
    if (param_base) {
        _env->ReleasePrimitiveArrayCritical(param_ref, param_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glSamplerParameterIuivEXT ( GLuint sampler, GLenum pname, const GLuint *param ) */
static void
android_glSamplerParameterIuivEXT__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jobject param_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *param = (GLuint *) 0;

    param = (GLuint *)getPointer(_env, param_buf, &_array, &_remaining, &_bufferOffset);
    if (param == NULL) {
        char * _paramBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        param = (GLuint *) (_paramBase + _bufferOffset);
    }
    glSamplerParameterIuivEXT(
        (GLuint)sampler,
        (GLenum)pname,
        (GLuint *)param
    );
    if (_array) {
        releasePointer(_env, _array, param, JNI_FALSE);
    }
}

/* void glGetSamplerParameterIivEXT ( GLuint sampler, GLenum pname, GLint *params ) */
static void
android_glGetSamplerParameterIivEXT__II_3II
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetSamplerParameterIivEXT(
        (GLuint)sampler,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetSamplerParameterIivEXT ( GLuint sampler, GLenum pname, GLint *params ) */
static void
android_glGetSamplerParameterIivEXT__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetSamplerParameterIivEXT(
        (GLuint)sampler,
        (GLenum)pname,
        (GLint *)params
    );
    if (_array) {
        releasePointer(_env, _array, params, JNI_TRUE);
    }
}

/* void glGetSamplerParameterIuivEXT ( GLuint sampler, GLenum pname, GLuint *params ) */
static void
android_glGetSamplerParameterIuivEXT__II_3II
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetSamplerParameterIuivEXT(
        (GLuint)sampler,
        (GLenum)pname,
        (GLuint *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetSamplerParameterIuivEXT ( GLuint sampler, GLenum pname, GLuint *params ) */
static void
android_glGetSamplerParameterIuivEXT__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint sampler, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *params = (GLuint *) 0;

    params = (GLuint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLuint *) (_paramsBase + _bufferOffset);
    }
    glGetSamplerParameterIuivEXT(
        (GLuint)sampler,
        (GLenum)pname,
        (GLuint *)params
    );
    if (_array) {
        releasePointer(_env, _array, params, JNI_TRUE);
    }
}

/* void glTexBufferEXT ( GLenum target, GLenum internalformat, GLuint buffer ) */
static void
android_glTexBufferEXT__III
  (JNIEnv *_env, jobject _this, jint target, jint internalformat, jint buffer) {
    glTexBufferEXT(
        (GLenum)target,
        (GLenum)internalformat,
        (GLuint)buffer
    );
}

/* void glTexBufferRangeEXT ( GLenum target, GLenum internalformat, GLuint buffer, GLintptr offset, GLsizeiptr size ) */
static void
android_glTexBufferRangeEXT__IIIII
  (JNIEnv *_env, jobject _this, jint target, jint internalformat, jint buffer, jint offset, jint size) {
    glTexBufferRangeEXT(
        (GLenum)target,
        (GLenum)internalformat,
        (GLuint)buffer,
        (GLintptr)offset,
        (GLsizeiptr)size
    );
}

static const char *classPathName = "android/opengl/GLES31Ext";

static JNINativeMethod methods[] = {
{"_nativeClassInit", "()V", (void*)nativeClassInit },
{"glBlendBarrierKHR", "()V", (void *) android_glBlendBarrierKHR__ },
{"glDebugMessageControlKHR", "(IIII[IIZ)V", (void *) android_glDebugMessageControlKHR__IIII_3IIZ },
{"glDebugMessageControlKHR", "(IIIILjava/nio/IntBuffer;Z)V", (void *) android_glDebugMessageControlKHR__IIIILjava_nio_IntBuffer_2Z },
{"glDebugMessageInsertKHR", "(IIIILjava/lang/String;)V", (void *) android_glDebugMessageInsertKHR__IIIILjava_lang_String_2 },
{"glDebugMessageCallbackKHR", "(Landroid/opengl/GLES31Ext$DebugProcKHR;)V", (void *) android_glDebugMessageCallbackKHR },
{"glGetDebugMessageLogKHR", "(II[II[II[II[II[II[BI)I", (void *) android_glGetDebugMessageLogKHR__II_3II_3II_3II_3II_3II_3BI },
{"glGetDebugMessageLogKHR", "(ILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/ByteBuffer;)I", (void *) android_glGetDebugMessageLogKHR__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_ByteBuffer_2 },
{"glGetDebugMessageLogKHR", "(I[II[II[II[II)[Ljava/lang/String;", (void *) android_glGetDebugMessageLogKHR__I_3II_3II_3II_3II },
{"glGetDebugMessageLogKHR", "(ILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;)[Ljava/lang/String;", (void *) android_glGetDebugMessageLogKHR__ILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2 },
{"glPushDebugGroupKHR", "(IIILjava/lang/String;)V", (void *) android_glPushDebugGroupKHR__IIILjava_lang_String_2 },
{"glPopDebugGroupKHR", "()V", (void *) android_glPopDebugGroupKHR__ },
{"glObjectLabelKHR", "(IIILjava/lang/String;)V", (void *) android_glObjectLabelKHR__IIILjava_lang_String_2 },
{"glGetObjectLabelKHR", "(II)Ljava/lang/String;", (void *) android_glGetObjectLabelKHR },
{"glObjectPtrLabelKHR", "(JLjava/lang/String;)V", (void *) android_glObjectPtrLabelKHR },
{"glGetObjectPtrLabelKHR", "(J)Ljava/lang/String;", (void *) android_glGetObjectPtrLabelKHR },
{"glGetDebugMessageCallbackKHR", "()Landroid/opengl/GLES31Ext$DebugProcKHR;", (void *) android_glGetDebugMessageCallbackKHR },
{"glMinSampleShadingOES", "(F)V", (void *) android_glMinSampleShadingOES__F },
{"glTexStorage3DMultisampleOES", "(IIIIIIZ)V", (void *) android_glTexStorage3DMultisampleOES__IIIIIIZ },
{"glCopyImageSubDataEXT", "(IIIIIIIIIIIIIII)V", (void *) android_glCopyImageSubDataEXT__IIIIIIIIIIIIIII },
{"glEnableiEXT", "(II)V", (void *) android_glEnableiEXT__II },
{"glDisableiEXT", "(II)V", (void *) android_glDisableiEXT__II },
{"glBlendEquationiEXT", "(II)V", (void *) android_glBlendEquationiEXT__II },
{"glBlendEquationSeparateiEXT", "(III)V", (void *) android_glBlendEquationSeparateiEXT__III },
{"glBlendFunciEXT", "(III)V", (void *) android_glBlendFunciEXT__III },
{"glBlendFuncSeparateiEXT", "(IIIII)V", (void *) android_glBlendFuncSeparateiEXT__IIIII },
{"glColorMaskiEXT", "(IZZZZ)V", (void *) android_glColorMaskiEXT__IZZZZ },
{"glIsEnablediEXT", "(II)Z", (void *) android_glIsEnablediEXT__II },
{"glFramebufferTextureEXT", "(IIII)V", (void *) android_glFramebufferTextureEXT__IIII },
{"glPrimitiveBoundingBoxEXT", "(FFFFFFFF)V", (void *) android_glPrimitiveBoundingBoxEXT__FFFFFFFF },
{"glPatchParameteriEXT", "(II)V", (void *) android_glPatchParameteriEXT__II },
{"glTexParameterIivEXT", "(II[II)V", (void *) android_glTexParameterIivEXT__II_3II },
{"glTexParameterIivEXT", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexParameterIivEXT__IILjava_nio_IntBuffer_2 },
{"glTexParameterIuivEXT", "(II[II)V", (void *) android_glTexParameterIuivEXT__II_3II },
{"glTexParameterIuivEXT", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexParameterIuivEXT__IILjava_nio_IntBuffer_2 },
{"glGetTexParameterIivEXT", "(II[II)V", (void *) android_glGetTexParameterIivEXT__II_3II },
{"glGetTexParameterIivEXT", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexParameterIivEXT__IILjava_nio_IntBuffer_2 },
{"glGetTexParameterIuivEXT", "(II[II)V", (void *) android_glGetTexParameterIuivEXT__II_3II },
{"glGetTexParameterIuivEXT", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexParameterIuivEXT__IILjava_nio_IntBuffer_2 },
{"glSamplerParameterIivEXT", "(II[II)V", (void *) android_glSamplerParameterIivEXT__II_3II },
{"glSamplerParameterIivEXT", "(IILjava/nio/IntBuffer;)V", (void *) android_glSamplerParameterIivEXT__IILjava_nio_IntBuffer_2 },
{"glSamplerParameterIuivEXT", "(II[II)V", (void *) android_glSamplerParameterIuivEXT__II_3II },
{"glSamplerParameterIuivEXT", "(IILjava/nio/IntBuffer;)V", (void *) android_glSamplerParameterIuivEXT__IILjava_nio_IntBuffer_2 },
{"glGetSamplerParameterIivEXT", "(II[II)V", (void *) android_glGetSamplerParameterIivEXT__II_3II },
{"glGetSamplerParameterIivEXT", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetSamplerParameterIivEXT__IILjava_nio_IntBuffer_2 },
{"glGetSamplerParameterIuivEXT", "(II[II)V", (void *) android_glGetSamplerParameterIuivEXT__II_3II },
{"glGetSamplerParameterIuivEXT", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetSamplerParameterIuivEXT__IILjava_nio_IntBuffer_2 },
{"glTexBufferEXT", "(III)V", (void *) android_glTexBufferEXT__III },
{"glTexBufferRangeEXT", "(IIIII)V", (void *) android_glTexBufferRangeEXT__IIIII },
};

int register_android_opengl_jni_GLES31Ext(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));
    return err;
}

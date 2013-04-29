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

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include "jni.h"
#include "JNIHelp.h"
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
        return (void *) (jint) pointer;
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
/* void glActiveTexture ( GLenum texture ) */
static void
android_glActiveTexture__I
  (JNIEnv *_env, jobject _this, jint texture) {
    glActiveTexture(
        (GLenum)texture
    );
}

/* void glAttachShader ( GLuint program, GLuint shader ) */
static void
android_glAttachShader__II
  (JNIEnv *_env, jobject _this, jint program, jint shader) {
    glAttachShader(
        (GLuint)program,
        (GLuint)shader
    );
}

/* void glBindAttribLocation ( GLuint program, GLuint index, const char *name ) */
static void
android_glBindAttribLocation__IILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint program, jint index, jstring name) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    const char* _nativename = 0;

    if (!name) {
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "name == null";
        goto exit;
    }
    _nativename = _env->GetStringUTFChars(name, 0);

    glBindAttribLocation(
        (GLuint)program,
        (GLuint)index,
        (char *)_nativename
    );

exit:
    if (_nativename) {
        _env->ReleaseStringUTFChars(name, _nativename);
    }

    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glBindBuffer ( GLenum target, GLuint buffer ) */
static void
android_glBindBuffer__II
  (JNIEnv *_env, jobject _this, jint target, jint buffer) {
    glBindBuffer(
        (GLenum)target,
        (GLuint)buffer
    );
}

/* void glBindFramebuffer ( GLenum target, GLuint framebuffer ) */
static void
android_glBindFramebuffer__II
  (JNIEnv *_env, jobject _this, jint target, jint framebuffer) {
    glBindFramebuffer(
        (GLenum)target,
        (GLuint)framebuffer
    );
}

/* void glBindRenderbuffer ( GLenum target, GLuint renderbuffer ) */
static void
android_glBindRenderbuffer__II
  (JNIEnv *_env, jobject _this, jint target, jint renderbuffer) {
    glBindRenderbuffer(
        (GLenum)target,
        (GLuint)renderbuffer
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

/* void glBlendColor ( GLclampf red, GLclampf green, GLclampf blue, GLclampf alpha ) */
static void
android_glBlendColor__FFFF
  (JNIEnv *_env, jobject _this, jfloat red, jfloat green, jfloat blue, jfloat alpha) {
    glBlendColor(
        (GLclampf)red,
        (GLclampf)green,
        (GLclampf)blue,
        (GLclampf)alpha
    );
}

/* void glBlendEquation ( GLenum mode ) */
static void
android_glBlendEquation__I
  (JNIEnv *_env, jobject _this, jint mode) {
    glBlendEquation(
        (GLenum)mode
    );
}

/* void glBlendEquationSeparate ( GLenum modeRGB, GLenum modeAlpha ) */
static void
android_glBlendEquationSeparate__II
  (JNIEnv *_env, jobject _this, jint modeRGB, jint modeAlpha) {
    glBlendEquationSeparate(
        (GLenum)modeRGB,
        (GLenum)modeAlpha
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

/* void glBlendFuncSeparate ( GLenum srcRGB, GLenum dstRGB, GLenum srcAlpha, GLenum dstAlpha ) */
static void
android_glBlendFuncSeparate__IIII
  (JNIEnv *_env, jobject _this, jint srcRGB, jint dstRGB, jint srcAlpha, jint dstAlpha) {
    glBlendFuncSeparate(
        (GLenum)srcRGB,
        (GLenum)dstRGB,
        (GLenum)srcAlpha,
        (GLenum)dstAlpha
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
        data = (GLvoid *)getPointer(_env, data_buf, &_array, &_remaining, &_bufferOffset);
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
        releasePointer(_env, _array, data, JNI_FALSE);
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

    data = (GLvoid *)getPointer(_env, data_buf, &_array, &_remaining, &_bufferOffset);
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
        releasePointer(_env, _array, data, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLenum glCheckFramebufferStatus ( GLenum target ) */
static jint
android_glCheckFramebufferStatus__I
  (JNIEnv *_env, jobject _this, jint target) {
    GLenum _returnValue;
    _returnValue = glCheckFramebufferStatus(
        (GLenum)target
    );
    return (jint)_returnValue;
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

/* void glClearDepthf ( GLclampf depth ) */
static void
android_glClearDepthf__F
  (JNIEnv *_env, jobject _this, jfloat depth) {
    glClearDepthf(
        (GLclampf)depth
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

/* void glCompileShader ( GLuint shader ) */
static void
android_glCompileShader__I
  (JNIEnv *_env, jobject _this, jint shader) {
    glCompileShader(
        (GLuint)shader
    );
}

/* void glCompressedTexImage2D ( GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height, GLint border, GLsizei imageSize, const GLvoid *data ) */
static void
android_glCompressedTexImage2D__IIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint internalformat, jint width, jint height, jint border, jint imageSize, jobject data_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *data = (GLvoid *) 0;

    data = (GLvoid *)getPointer(_env, data_buf, &_array, &_remaining, &_bufferOffset);
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
    if (_array) {
        releasePointer(_env, _array, data, JNI_FALSE);
    }
}

/* void glCompressedTexSubImage2D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLsizei imageSize, const GLvoid *data ) */
static void
android_glCompressedTexSubImage2D__IIIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint xoffset, jint yoffset, jint width, jint height, jint format, jint imageSize, jobject data_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *data = (GLvoid *) 0;

    data = (GLvoid *)getPointer(_env, data_buf, &_array, &_remaining, &_bufferOffset);
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
    if (_array) {
        releasePointer(_env, _array, data, JNI_FALSE);
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

/* GLuint glCreateProgram ( void ) */
static jint
android_glCreateProgram__
  (JNIEnv *_env, jobject _this) {
    GLuint _returnValue;
    _returnValue = glCreateProgram();
    return (jint)_returnValue;
}

/* GLuint glCreateShader ( GLenum type ) */
static jint
android_glCreateShader__I
  (JNIEnv *_env, jobject _this, jint type) {
    GLuint _returnValue;
    _returnValue = glCreateShader(
        (GLenum)type
    );
    return (jint)_returnValue;
}

/* void glCullFace ( GLenum mode ) */
static void
android_glCullFace__I
  (JNIEnv *_env, jobject _this, jint mode) {
    glCullFace(
        (GLenum)mode
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
        _env->GetPrimitiveArrayCritical(buffers_ref, (jboolean *)0);
    buffers = buffers_base + offset;

    glDeleteBuffers(
        (GLsizei)n,
        (GLuint *)buffers
    );

exit:
    if (buffers_base) {
        _env->ReleasePrimitiveArrayCritical(buffers_ref, buffers_base,
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
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *buffers = (GLuint *) 0;

    buffers = (GLuint *)getPointer(_env, buffers_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (buffers == NULL) {
        char * _buffersBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        buffers = (GLuint *) (_buffersBase + _bufferOffset);
    }
    glDeleteBuffers(
        (GLsizei)n,
        (GLuint *)buffers
    );

exit:
    if (_array) {
        releasePointer(_env, _array, buffers, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteFramebuffers ( GLsizei n, const GLuint *framebuffers ) */
static void
android_glDeleteFramebuffers__I_3II
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
        _env->GetPrimitiveArrayCritical(framebuffers_ref, (jboolean *)0);
    framebuffers = framebuffers_base + offset;

    glDeleteFramebuffers(
        (GLsizei)n,
        (GLuint *)framebuffers
    );

exit:
    if (framebuffers_base) {
        _env->ReleasePrimitiveArrayCritical(framebuffers_ref, framebuffers_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteFramebuffers ( GLsizei n, const GLuint *framebuffers ) */
static void
android_glDeleteFramebuffers__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject framebuffers_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *framebuffers = (GLuint *) 0;

    framebuffers = (GLuint *)getPointer(_env, framebuffers_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (framebuffers == NULL) {
        char * _framebuffersBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        framebuffers = (GLuint *) (_framebuffersBase + _bufferOffset);
    }
    glDeleteFramebuffers(
        (GLsizei)n,
        (GLuint *)framebuffers
    );

exit:
    if (_array) {
        releasePointer(_env, _array, framebuffers, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteProgram ( GLuint program ) */
static void
android_glDeleteProgram__I
  (JNIEnv *_env, jobject _this, jint program) {
    glDeleteProgram(
        (GLuint)program
    );
}

/* void glDeleteRenderbuffers ( GLsizei n, const GLuint *renderbuffers ) */
static void
android_glDeleteRenderbuffers__I_3II
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
        _env->GetPrimitiveArrayCritical(renderbuffers_ref, (jboolean *)0);
    renderbuffers = renderbuffers_base + offset;

    glDeleteRenderbuffers(
        (GLsizei)n,
        (GLuint *)renderbuffers
    );

exit:
    if (renderbuffers_base) {
        _env->ReleasePrimitiveArrayCritical(renderbuffers_ref, renderbuffers_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteRenderbuffers ( GLsizei n, const GLuint *renderbuffers ) */
static void
android_glDeleteRenderbuffers__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject renderbuffers_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *renderbuffers = (GLuint *) 0;

    renderbuffers = (GLuint *)getPointer(_env, renderbuffers_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (renderbuffers == NULL) {
        char * _renderbuffersBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        renderbuffers = (GLuint *) (_renderbuffersBase + _bufferOffset);
    }
    glDeleteRenderbuffers(
        (GLsizei)n,
        (GLuint *)renderbuffers
    );

exit:
    if (_array) {
        releasePointer(_env, _array, renderbuffers, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glDeleteShader ( GLuint shader ) */
static void
android_glDeleteShader__I
  (JNIEnv *_env, jobject _this, jint shader) {
    glDeleteShader(
        (GLuint)shader
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
        _env->GetPrimitiveArrayCritical(textures_ref, (jboolean *)0);
    textures = textures_base + offset;

    glDeleteTextures(
        (GLsizei)n,
        (GLuint *)textures
    );

exit:
    if (textures_base) {
        _env->ReleasePrimitiveArrayCritical(textures_ref, textures_base,
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
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *textures = (GLuint *) 0;

    textures = (GLuint *)getPointer(_env, textures_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (textures == NULL) {
        char * _texturesBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        textures = (GLuint *) (_texturesBase + _bufferOffset);
    }
    glDeleteTextures(
        (GLsizei)n,
        (GLuint *)textures
    );

exit:
    if (_array) {
        releasePointer(_env, _array, textures, JNI_FALSE);
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

/* void glDetachShader ( GLuint program, GLuint shader ) */
static void
android_glDetachShader__II
  (JNIEnv *_env, jobject _this, jint program, jint shader) {
    glDetachShader(
        (GLuint)program,
        (GLuint)shader
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

/* void glDisableVertexAttribArray ( GLuint index ) */
static void
android_glDisableVertexAttribArray__I
  (JNIEnv *_env, jobject _this, jint index) {
    glDisableVertexAttribArray(
        (GLuint)index
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
        (GLvoid *)offset
    );
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
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

    indices = (GLvoid *)getPointer(_env, indices_buf, &_array, &_remaining, &_bufferOffset);
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

/* void glEnableVertexAttribArray ( GLuint index ) */
static void
android_glEnableVertexAttribArray__I
  (JNIEnv *_env, jobject _this, jint index) {
    glEnableVertexAttribArray(
        (GLuint)index
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

/* void glFramebufferRenderbuffer ( GLenum target, GLenum attachment, GLenum renderbuffertarget, GLuint renderbuffer ) */
static void
android_glFramebufferRenderbuffer__IIII
  (JNIEnv *_env, jobject _this, jint target, jint attachment, jint renderbuffertarget, jint renderbuffer) {
    glFramebufferRenderbuffer(
        (GLenum)target,
        (GLenum)attachment,
        (GLenum)renderbuffertarget,
        (GLuint)renderbuffer
    );
}

/* void glFramebufferTexture2D ( GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level ) */
static void
android_glFramebufferTexture2D__IIIII
  (JNIEnv *_env, jobject _this, jint target, jint attachment, jint textarget, jint texture, jint level) {
    glFramebufferTexture2D(
        (GLenum)target,
        (GLenum)attachment,
        (GLenum)textarget,
        (GLuint)texture,
        (GLint)level
    );
}

/* void glFrontFace ( GLenum mode ) */
static void
android_glFrontFace__I
  (JNIEnv *_env, jobject _this, jint mode) {
    glFrontFace(
        (GLenum)mode
    );
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
        _env->GetPrimitiveArrayCritical(buffers_ref, (jboolean *)0);
    buffers = buffers_base + offset;

    glGenBuffers(
        (GLsizei)n,
        (GLuint *)buffers
    );

exit:
    if (buffers_base) {
        _env->ReleasePrimitiveArrayCritical(buffers_ref, buffers_base,
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
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *buffers = (GLuint *) 0;

    buffers = (GLuint *)getPointer(_env, buffers_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (buffers == NULL) {
        char * _buffersBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        buffers = (GLuint *) (_buffersBase + _bufferOffset);
    }
    glGenBuffers(
        (GLsizei)n,
        (GLuint *)buffers
    );

exit:
    if (_array) {
        releasePointer(_env, _array, buffers, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenerateMipmap ( GLenum target ) */
static void
android_glGenerateMipmap__I
  (JNIEnv *_env, jobject _this, jint target) {
    glGenerateMipmap(
        (GLenum)target
    );
}

/* void glGenFramebuffers ( GLsizei n, GLuint *framebuffers ) */
static void
android_glGenFramebuffers__I_3II
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
        _env->GetPrimitiveArrayCritical(framebuffers_ref, (jboolean *)0);
    framebuffers = framebuffers_base + offset;

    glGenFramebuffers(
        (GLsizei)n,
        (GLuint *)framebuffers
    );

exit:
    if (framebuffers_base) {
        _env->ReleasePrimitiveArrayCritical(framebuffers_ref, framebuffers_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenFramebuffers ( GLsizei n, GLuint *framebuffers ) */
static void
android_glGenFramebuffers__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject framebuffers_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *framebuffers = (GLuint *) 0;

    framebuffers = (GLuint *)getPointer(_env, framebuffers_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (framebuffers == NULL) {
        char * _framebuffersBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        framebuffers = (GLuint *) (_framebuffersBase + _bufferOffset);
    }
    glGenFramebuffers(
        (GLsizei)n,
        (GLuint *)framebuffers
    );

exit:
    if (_array) {
        releasePointer(_env, _array, framebuffers, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenRenderbuffers ( GLsizei n, GLuint *renderbuffers ) */
static void
android_glGenRenderbuffers__I_3II
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
        _env->GetPrimitiveArrayCritical(renderbuffers_ref, (jboolean *)0);
    renderbuffers = renderbuffers_base + offset;

    glGenRenderbuffers(
        (GLsizei)n,
        (GLuint *)renderbuffers
    );

exit:
    if (renderbuffers_base) {
        _env->ReleasePrimitiveArrayCritical(renderbuffers_ref, renderbuffers_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGenRenderbuffers ( GLsizei n, GLuint *renderbuffers ) */
static void
android_glGenRenderbuffers__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject renderbuffers_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *renderbuffers = (GLuint *) 0;

    renderbuffers = (GLuint *)getPointer(_env, renderbuffers_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (renderbuffers == NULL) {
        char * _renderbuffersBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        renderbuffers = (GLuint *) (_renderbuffersBase + _bufferOffset);
    }
    glGenRenderbuffers(
        (GLsizei)n,
        (GLuint *)renderbuffers
    );

exit:
    if (_array) {
        releasePointer(_env, _array, renderbuffers, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
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
        _env->GetPrimitiveArrayCritical(textures_ref, (jboolean *)0);
    textures = textures_base + offset;

    glGenTextures(
        (GLsizei)n,
        (GLuint *)textures
    );

exit:
    if (textures_base) {
        _env->ReleasePrimitiveArrayCritical(textures_ref, textures_base,
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
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLuint *textures = (GLuint *) 0;

    textures = (GLuint *)getPointer(_env, textures_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < n) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < n < needed";
        goto exit;
    }
    if (textures == NULL) {
        char * _texturesBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        textures = (GLuint *) (_texturesBase + _bufferOffset);
    }
    glGenTextures(
        (GLsizei)n,
        (GLuint *)textures
    );

exit:
    if (_array) {
        releasePointer(_env, _array, textures, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetActiveAttrib ( GLuint program, GLuint index, GLsizei bufsize, GLsizei *length, GLint *size, GLenum *type, char *name ) */
static void
android_glGetActiveAttrib__III_3II_3II_3II_3BI
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

    if (!length_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length == null";
        goto exit;
    }
    if (lengthOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "lengthOffset < 0";
        goto exit;
    }
    _lengthRemaining = _env->GetArrayLength(length_ref) - lengthOffset;
    length_base = (GLsizei *)
        _env->GetPrimitiveArrayCritical(length_ref, (jboolean *)0);
    length = length_base + lengthOffset;

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
        _env->GetPrimitiveArrayCritical(size_ref, (jboolean *)0);
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
        _env->GetPrimitiveArrayCritical(type_ref, (jboolean *)0);
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
        _env->GetPrimitiveArrayCritical(name_ref, (jboolean *)0);
    name = name_base + nameOffset;

    glGetActiveAttrib(
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
        _env->ReleasePrimitiveArrayCritical(name_ref, name_base,
            _exception ? JNI_ABORT: 0);
    }
    if (type_base) {
        _env->ReleasePrimitiveArrayCritical(type_ref, type_base,
            _exception ? JNI_ABORT: 0);
    }
    if (size_base) {
        _env->ReleasePrimitiveArrayCritical(size_ref, size_base,
            _exception ? JNI_ABORT: 0);
    }
    if (length_base) {
        _env->ReleasePrimitiveArrayCritical(length_ref, length_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetActiveAttrib ( GLuint program, GLuint index, GLsizei bufsize, GLsizei *length, GLint *size, GLenum *type, char *name ) */
static void
android_glGetActiveAttrib__IIILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2B
  (JNIEnv *_env, jobject _this, jint program, jint index, jint bufsize, jobject length_buf, jobject size_buf, jobject type_buf, jbyte name) {
    jarray _lengthArray = (jarray) 0;
    jint _lengthBufferOffset = (jint) 0;
    jarray _sizeArray = (jarray) 0;
    jint _sizeBufferOffset = (jint) 0;
    jarray _typeArray = (jarray) 0;
    jint _typeBufferOffset = (jint) 0;
    jint _lengthRemaining;
    GLsizei *length = (GLsizei *) 0;
    jint _sizeRemaining;
    GLint *size = (GLint *) 0;
    jint _typeRemaining;
    GLenum *type = (GLenum *) 0;

    length = (GLsizei *)getPointer(_env, length_buf, &_lengthArray, &_lengthRemaining, &_lengthBufferOffset);
    size = (GLint *)getPointer(_env, size_buf, &_sizeArray, &_sizeRemaining, &_sizeBufferOffset);
    type = (GLenum *)getPointer(_env, type_buf, &_typeArray, &_typeRemaining, &_typeBufferOffset);
    if (length == NULL) {
        char * _lengthBase = (char *)_env->GetPrimitiveArrayCritical(_lengthArray, (jboolean *) 0);
        length = (GLsizei *) (_lengthBase + _lengthBufferOffset);
    }
    if (size == NULL) {
        char * _sizeBase = (char *)_env->GetPrimitiveArrayCritical(_sizeArray, (jboolean *) 0);
        size = (GLint *) (_sizeBase + _sizeBufferOffset);
    }
    if (type == NULL) {
        char * _typeBase = (char *)_env->GetPrimitiveArrayCritical(_typeArray, (jboolean *) 0);
        type = (GLenum *) (_typeBase + _typeBufferOffset);
    }
    glGetActiveAttrib(
        (GLuint)program,
        (GLuint)index,
        (GLsizei)bufsize,
        (GLsizei *)length,
        (GLint *)size,
        (GLenum *)type,
        (char *)name
    );
    if (_typeArray) {
        releasePointer(_env, _typeArray, type, JNI_TRUE);
    }
    if (_sizeArray) {
        releasePointer(_env, _sizeArray, size, JNI_TRUE);
    }
    if (_lengthArray) {
        releasePointer(_env, _lengthArray, length, JNI_TRUE);
    }
}

/* void glGetActiveAttrib ( GLuint program, GLuint index, GLsizei bufsize, GLsizei *length, GLint *size, GLenum *type, char *name ) */
static jstring
android_glGetActiveAttrib1
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
        _env->GetPrimitiveArrayCritical(size_ref, (jboolean *)0);
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
        _env->GetPrimitiveArrayCritical(type_ref, (jboolean *)0);
    type = type_base + typeOffset;

    glGetActiveAttrib(
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
        _env->ReleasePrimitiveArrayCritical(type_ref, type_base,
            _exception ? JNI_ABORT: 0);
    }
    if (size_base) {
        _env->ReleasePrimitiveArrayCritical(size_ref, size_base,
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

/* void glGetActiveAttrib ( GLuint program, GLuint index, GLsizei bufsize, GLsizei *length, GLint *size, GLenum *type, char *name ) */
static jstring
android_glGetActiveAttrib2
  (JNIEnv *_env, jobject _this, jint program, jint index, jobject size_buf, jobject type_buf) {
    jarray _sizeArray = (jarray) 0;
    jint _sizeBufferOffset = (jint) 0;
    jarray _typeArray = (jarray) 0;
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

    size = (GLint *)getPointer(_env, size_buf, &_sizeArray, &_sizeRemaining, &_sizeBufferOffset);
    type = (GLenum *)getPointer(_env, type_buf, &_typeArray, &_typeRemaining, &_typeBufferOffset);
    if (size == NULL) {
        char * _sizeBase = (char *)_env->GetPrimitiveArrayCritical(_sizeArray, (jboolean *) 0);
        size = (GLint *) (_sizeBase + _sizeBufferOffset);
    }
    if (type == NULL) {
        char * _typeBase = (char *)_env->GetPrimitiveArrayCritical(_typeArray, (jboolean *) 0);
        type = (GLenum *) (_typeBase + _typeBufferOffset);
    }
    glGetActiveAttrib(
        (GLuint)program,
        (GLuint)index,
        (GLsizei)len,
        NULL,
        (GLint *)size,
        (GLenum *)type,
        (char *)buf
    );

    if (_typeArray) {
        releasePointer(_env, _typeArray, type, JNI_TRUE);
    }
    if (_sizeArray) {
        releasePointer(_env, _sizeArray, size, JNI_TRUE);
    }
    result = _env->NewStringUTF(buf);
    if (buf) {
        free(buf);
    }
    return result;
}
/* void glGetActiveUniform ( GLuint program, GLuint index, GLsizei bufsize, GLsizei *length, GLint *size, GLenum *type, char *name ) */
static void
android_glGetActiveUniform__III_3II_3II_3II_3BI
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

    if (!length_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length == null";
        goto exit;
    }
    if (lengthOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "lengthOffset < 0";
        goto exit;
    }
    _lengthRemaining = _env->GetArrayLength(length_ref) - lengthOffset;
    length_base = (GLsizei *)
        _env->GetPrimitiveArrayCritical(length_ref, (jboolean *)0);
    length = length_base + lengthOffset;

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
        _env->GetPrimitiveArrayCritical(size_ref, (jboolean *)0);
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
        _env->GetPrimitiveArrayCritical(type_ref, (jboolean *)0);
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
        _env->GetPrimitiveArrayCritical(name_ref, (jboolean *)0);
    name = name_base + nameOffset;

    glGetActiveUniform(
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
        _env->ReleasePrimitiveArrayCritical(name_ref, name_base,
            _exception ? JNI_ABORT: 0);
    }
    if (type_base) {
        _env->ReleasePrimitiveArrayCritical(type_ref, type_base,
            _exception ? JNI_ABORT: 0);
    }
    if (size_base) {
        _env->ReleasePrimitiveArrayCritical(size_ref, size_base,
            _exception ? JNI_ABORT: 0);
    }
    if (length_base) {
        _env->ReleasePrimitiveArrayCritical(length_ref, length_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetActiveUniform ( GLuint program, GLuint index, GLsizei bufsize, GLsizei *length, GLint *size, GLenum *type, char *name ) */
static void
android_glGetActiveUniform__IIILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2B
  (JNIEnv *_env, jobject _this, jint program, jint index, jint bufsize, jobject length_buf, jobject size_buf, jobject type_buf, jbyte name) {
    jarray _lengthArray = (jarray) 0;
    jint _lengthBufferOffset = (jint) 0;
    jarray _sizeArray = (jarray) 0;
    jint _sizeBufferOffset = (jint) 0;
    jarray _typeArray = (jarray) 0;
    jint _typeBufferOffset = (jint) 0;
    jint _lengthRemaining;
    GLsizei *length = (GLsizei *) 0;
    jint _sizeRemaining;
    GLint *size = (GLint *) 0;
    jint _typeRemaining;
    GLenum *type = (GLenum *) 0;

    length = (GLsizei *)getPointer(_env, length_buf, &_lengthArray, &_lengthRemaining, &_lengthBufferOffset);
    size = (GLint *)getPointer(_env, size_buf, &_sizeArray, &_sizeRemaining, &_sizeBufferOffset);
    type = (GLenum *)getPointer(_env, type_buf, &_typeArray, &_typeRemaining, &_typeBufferOffset);
    if (length == NULL) {
        char * _lengthBase = (char *)_env->GetPrimitiveArrayCritical(_lengthArray, (jboolean *) 0);
        length = (GLsizei *) (_lengthBase + _lengthBufferOffset);
    }
    if (size == NULL) {
        char * _sizeBase = (char *)_env->GetPrimitiveArrayCritical(_sizeArray, (jboolean *) 0);
        size = (GLint *) (_sizeBase + _sizeBufferOffset);
    }
    if (type == NULL) {
        char * _typeBase = (char *)_env->GetPrimitiveArrayCritical(_typeArray, (jboolean *) 0);
        type = (GLenum *) (_typeBase + _typeBufferOffset);
    }
    glGetActiveUniform(
        (GLuint)program,
        (GLuint)index,
        (GLsizei)bufsize,
        (GLsizei *)length,
        (GLint *)size,
        (GLenum *)type,
        (char *)name
    );
    if (_typeArray) {
        releasePointer(_env, _typeArray, type, JNI_TRUE);
    }
    if (_sizeArray) {
        releasePointer(_env, _sizeArray, size, JNI_TRUE);
    }
    if (_lengthArray) {
        releasePointer(_env, _lengthArray, length, JNI_TRUE);
    }
}

/* void glGetActiveUniform ( GLuint program, GLuint index, GLsizei bufsize, GLsizei *length, GLint *size, GLenum *type, char *name ) */
static jstring
android_glGetActiveUniform1
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
    glGetProgramiv((GLuint)program, GL_ACTIVE_UNIFORM_MAX_LENGTH, &len);
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
        _env->GetPrimitiveArrayCritical(size_ref, (jboolean *)0);
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
        _env->GetPrimitiveArrayCritical(type_ref, (jboolean *)0);
    type = type_base + typeOffset;

    glGetActiveUniform(
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
        _env->ReleasePrimitiveArrayCritical(type_ref, type_base,
            _exception ? JNI_ABORT: 0);
    }
    if (size_base) {
        _env->ReleasePrimitiveArrayCritical(size_ref, size_base,
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

/* void glGetActiveUniform ( GLuint program, GLuint index, GLsizei bufsize, GLsizei *length, GLint *size, GLenum *type, char *name ) */
static jstring
android_glGetActiveUniform2
  (JNIEnv *_env, jobject _this, jint program, jint index, jobject size_buf, jobject type_buf) {
    jarray _sizeArray = (jarray) 0;
    jint _sizeBufferOffset = (jint) 0;
    jarray _typeArray = (jarray) 0;
    jint _typeBufferOffset = (jint) 0;
    jint _sizeRemaining;
    GLint *size = (GLint *) 0;
    jint _typeRemaining;
    GLenum *type = (GLenum *) 0;

    jstring result = 0;
    GLint len = 0;
    glGetProgramiv((GLuint)program, GL_ACTIVE_UNIFORM_MAX_LENGTH, &len);
    if (!len) {
        return _env->NewStringUTF("");
    }
    char* buf = (char*) malloc(len);

    if (buf == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "out of memory");
        return NULL;
    }

    size = (GLint *)getPointer(_env, size_buf, &_sizeArray, &_sizeRemaining, &_sizeBufferOffset);
    type = (GLenum *)getPointer(_env, type_buf, &_typeArray, &_typeRemaining, &_typeBufferOffset);

    if (size == NULL) {
        char * _sizeBase = (char *)_env->GetPrimitiveArrayCritical(_sizeArray, (jboolean *) 0);
        size = (GLint *) (_sizeBase + _sizeBufferOffset);
    }
    if (type == NULL) {
        char * _typeBase = (char *)_env->GetPrimitiveArrayCritical(_typeArray, (jboolean *) 0);
        type = (GLenum *) (_typeBase + _typeBufferOffset);
    }
    glGetActiveUniform(
        (GLuint)program,
        (GLuint)index,
        len,
        NULL,
        (GLint *)size,
        (GLenum *)type,
        (char *)buf
    );

    if (_typeArray) {
        releasePointer(_env, _typeArray, type, JNI_TRUE);
    }
    if (_sizeArray) {
        releasePointer(_env, _sizeArray, size, JNI_TRUE);
    }
    result = _env->NewStringUTF(buf);
    if (buf) {
        free(buf);
    }
    return result;
}
/* void glGetAttachedShaders ( GLuint program, GLsizei maxcount, GLsizei *count, GLuint *shaders ) */
static void
android_glGetAttachedShaders__II_3II_3II
  (JNIEnv *_env, jobject _this, jint program, jint maxcount, jintArray count_ref, jint countOffset, jintArray shaders_ref, jint shadersOffset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLsizei *count_base = (GLsizei *) 0;
    jint _countRemaining;
    GLsizei *count = (GLsizei *) 0;
    GLuint *shaders_base = (GLuint *) 0;
    jint _shadersRemaining;
    GLuint *shaders = (GLuint *) 0;

    if (!count_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "count == null";
        goto exit;
    }
    if (countOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "countOffset < 0";
        goto exit;
    }
    _countRemaining = _env->GetArrayLength(count_ref) - countOffset;
    if (_countRemaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - countOffset < 1 < needed";
        goto exit;
    }
    count_base = (GLsizei *)
        _env->GetPrimitiveArrayCritical(count_ref, (jboolean *)0);
    count = count_base + countOffset;

    if (!shaders_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "shaders == null";
        goto exit;
    }
    if (shadersOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "shadersOffset < 0";
        goto exit;
    }
    _shadersRemaining = _env->GetArrayLength(shaders_ref) - shadersOffset;
    if (_shadersRemaining < maxcount) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - shadersOffset < maxcount < needed";
        goto exit;
    }
    shaders_base = (GLuint *)
        _env->GetPrimitiveArrayCritical(shaders_ref, (jboolean *)0);
    shaders = shaders_base + shadersOffset;

    glGetAttachedShaders(
        (GLuint)program,
        (GLsizei)maxcount,
        (GLsizei *)count,
        (GLuint *)shaders
    );

exit:
    if (shaders_base) {
        _env->ReleasePrimitiveArrayCritical(shaders_ref, shaders_base,
            _exception ? JNI_ABORT: 0);
    }
    if (count_base) {
        _env->ReleasePrimitiveArrayCritical(count_ref, count_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetAttachedShaders ( GLuint program, GLsizei maxcount, GLsizei *count, GLuint *shaders ) */
static void
android_glGetAttachedShaders__IILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint maxcount, jobject count_buf, jobject shaders_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _countArray = (jarray) 0;
    jint _countBufferOffset = (jint) 0;
    jarray _shadersArray = (jarray) 0;
    jint _shadersBufferOffset = (jint) 0;
    jint _countRemaining;
    GLsizei *count = (GLsizei *) 0;
    jint _shadersRemaining;
    GLuint *shaders = (GLuint *) 0;

    if (count_buf) {
        count = (GLsizei *)getPointer(_env, count_buf, &_countArray, &_countRemaining, &_countBufferOffset);
        if (_countRemaining < 1) {
            _exception = 1;
            _exceptionType = "java/lang/IllegalArgumentException";
            _exceptionMessage = "remaining() < 1 < needed";
            goto exit;
        }
    }
    if (shaders_buf) {
        shaders = (GLuint *)getPointer(_env, shaders_buf, &_shadersArray, &_shadersRemaining, &_shadersBufferOffset);
        if (_shadersRemaining < maxcount) {
            _exception = 1;
            _exceptionType = "java/lang/IllegalArgumentException";
            _exceptionMessage = "remaining() < maxcount < needed";
            goto exit;
        }
    }
    if (count_buf && count == NULL) {
        char * _countBase = (char *)_env->GetPrimitiveArrayCritical(_countArray, (jboolean *) 0);
        count = (GLsizei *) (_countBase + _countBufferOffset);
    }
    if (shaders_buf && shaders == NULL) {
        char * _shadersBase = (char *)_env->GetPrimitiveArrayCritical(_shadersArray, (jboolean *) 0);
        shaders = (GLuint *) (_shadersBase + _shadersBufferOffset);
    }
    glGetAttachedShaders(
        (GLuint)program,
        (GLsizei)maxcount,
        (GLsizei *)count,
        (GLuint *)shaders
    );

exit:
    if (_shadersArray) {
        releasePointer(_env, _shadersArray, shaders, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_countArray) {
        releasePointer(_env, _countArray, count, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLint glGetAttribLocation ( GLuint program, const char *name ) */
static jint
android_glGetAttribLocation__ILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint program, jstring name) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint _returnValue = 0;
    const char* _nativename = 0;

    if (!name) {
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "name == null";
        goto exit;
    }
    _nativename = _env->GetStringUTFChars(name, 0);

    _returnValue = glGetAttribLocation(
        (GLuint)program,
        (char *)_nativename
    );

exit:
    if (_nativename) {
        _env->ReleaseStringUTFChars(name, _nativename);
    }

    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return (jint)_returnValue;
}

/* void glGetBooleanv ( GLenum pname, GLboolean *params ) */
static void
android_glGetBooleanv__I_3ZI
  (JNIEnv *_env, jobject _this, jint pname, jbooleanArray params_ref, jint offset) {
    get<jbooleanArray, GLboolean, glGetBooleanv>(_env, _this, pname, params_ref, offset);
}

/* void glGetBooleanv ( GLenum pname, GLboolean *params ) */
static void
android_glGetBooleanv__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    getarray<GLboolean, glGetBooleanv>(_env, _this, pname, params_buf);
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetBufferParameteriv(
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

/* void glGetBufferParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetBufferParameteriv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetBufferParameteriv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
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

/* void glGetFloatv ( GLenum pname, GLfloat *params ) */
static void
android_glGetFloatv__I_3FI
  (JNIEnv *_env, jobject _this, jint pname, jfloatArray params_ref, jint offset) {
    get<jfloatArray, GLfloat, glGetFloatv>(_env, _this, pname, params_ref, offset);
}

/* void glGetFloatv ( GLenum pname, GLfloat *params ) */
static void
android_glGetFloatv__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    getarray<GLfloat, glGetFloatv>(_env, _this, pname, params_buf);
}
/* void glGetFramebufferAttachmentParameteriv ( GLenum target, GLenum attachment, GLenum pname, GLint *params ) */
static void
android_glGetFramebufferAttachmentParameteriv__III_3II
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
    params_base = (GLint *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetFramebufferAttachmentParameteriv(
        (GLenum)target,
        (GLenum)attachment,
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

/* void glGetFramebufferAttachmentParameteriv ( GLenum target, GLenum attachment, GLenum pname, GLint *params ) */
static void
android_glGetFramebufferAttachmentParameteriv__IIILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint attachment, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetFramebufferAttachmentParameteriv(
        (GLenum)target,
        (GLenum)attachment,
        (GLenum)pname,
        (GLint *)params
    );
    if (_array) {
        releasePointer(_env, _array, params, JNI_TRUE);
    }
}

/* void glGetIntegerv ( GLenum pname, GLint *params ) */
static void
android_glGetIntegerv__I_3II
  (JNIEnv *_env, jobject _this, jint pname, jintArray params_ref, jint offset) {
    get<jintArray, GLint, glGetIntegerv>(_env, _this, pname, params_ref, offset);
}

/* void glGetIntegerv ( GLenum pname, GLint *params ) */
static void
android_glGetIntegerv__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    getarray<GLint, glGetIntegerv>(_env, _this, pname, params_buf);
}

/* void glGetProgramiv ( GLuint program, GLenum pname, GLint *params ) */
static void
android_glGetProgramiv__II_3II
  (JNIEnv *_env, jobject _this, jint program, jint pname, jintArray params_ref, jint offset) {
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetProgramiv(
        (GLuint)program,
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

/* void glGetProgramiv ( GLuint program, GLenum pname, GLint *params ) */
static void
android_glGetProgramiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetProgramiv(
        (GLuint)program,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

#include <stdlib.h>

/* void glGetProgramInfoLog ( GLuint shader, GLsizei maxLength, GLsizei* length, GLchar* infoLog ) */
static jstring android_glGetProgramInfoLog(JNIEnv *_env, jobject, jint shader) {
    GLint infoLen = 0;
    glGetProgramiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
    if (!infoLen) {
        return _env->NewStringUTF("");
    }
    char* buf = (char*) malloc(infoLen);
    if (buf == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "out of memory");
        return NULL;
    }
    glGetProgramInfoLog(shader, infoLen, NULL, buf);
    jstring result = _env->NewStringUTF(buf);
    free(buf);
    return result;
}
/* void glGetRenderbufferParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetRenderbufferParameteriv__II_3II
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetRenderbufferParameteriv(
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

/* void glGetRenderbufferParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetRenderbufferParameteriv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetRenderbufferParameteriv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetShaderiv ( GLuint shader, GLenum pname, GLint *params ) */
static void
android_glGetShaderiv__II_3II
  (JNIEnv *_env, jobject _this, jint shader, jint pname, jintArray params_ref, jint offset) {
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetShaderiv(
        (GLuint)shader,
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

/* void glGetShaderiv ( GLuint shader, GLenum pname, GLint *params ) */
static void
android_glGetShaderiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint shader, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetShaderiv(
        (GLuint)shader,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

#include <stdlib.h>

/* void glGetShaderInfoLog ( GLuint shader, GLsizei maxLength, GLsizei* length, GLchar* infoLog ) */
static jstring android_glGetShaderInfoLog(JNIEnv *_env, jobject, jint shader) {
    GLint infoLen = 0;
    glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
    if (!infoLen) {
        return _env->NewStringUTF("");
    }
    char* buf = (char*) malloc(infoLen);
    if (buf == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "out of memory");
        return NULL;
    }
    glGetShaderInfoLog(shader, infoLen, NULL, buf);
    jstring result = _env->NewStringUTF(buf);
    free(buf);
    return result;
}
/* void glGetShaderPrecisionFormat ( GLenum shadertype, GLenum precisiontype, GLint *range, GLint *precision ) */
static void
android_glGetShaderPrecisionFormat__II_3II_3II
  (JNIEnv *_env, jobject _this, jint shadertype, jint precisiontype, jintArray range_ref, jint rangeOffset, jintArray precision_ref, jint precisionOffset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint *range_base = (GLint *) 0;
    jint _rangeRemaining;
    GLint *range = (GLint *) 0;
    GLint *precision_base = (GLint *) 0;
    jint _precisionRemaining;
    GLint *precision = (GLint *) 0;

    if (!range_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "range == null";
        goto exit;
    }
    if (rangeOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "rangeOffset < 0";
        goto exit;
    }
    _rangeRemaining = _env->GetArrayLength(range_ref) - rangeOffset;
    if (_rangeRemaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - rangeOffset < 1 < needed";
        goto exit;
    }
    range_base = (GLint *)
        _env->GetPrimitiveArrayCritical(range_ref, (jboolean *)0);
    range = range_base + rangeOffset;

    if (!precision_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "precision == null";
        goto exit;
    }
    if (precisionOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "precisionOffset < 0";
        goto exit;
    }
    _precisionRemaining = _env->GetArrayLength(precision_ref) - precisionOffset;
    if (_precisionRemaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - precisionOffset < 1 < needed";
        goto exit;
    }
    precision_base = (GLint *)
        _env->GetPrimitiveArrayCritical(precision_ref, (jboolean *)0);
    precision = precision_base + precisionOffset;

    glGetShaderPrecisionFormat(
        (GLenum)shadertype,
        (GLenum)precisiontype,
        (GLint *)range,
        (GLint *)precision
    );

exit:
    if (precision_base) {
        _env->ReleasePrimitiveArrayCritical(precision_ref, precision_base,
            _exception ? JNI_ABORT: 0);
    }
    if (range_base) {
        _env->ReleasePrimitiveArrayCritical(range_ref, range_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetShaderPrecisionFormat ( GLenum shadertype, GLenum precisiontype, GLint *range, GLint *precision ) */
static void
android_glGetShaderPrecisionFormat__IILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint shadertype, jint precisiontype, jobject range_buf, jobject precision_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _rangeArray = (jarray) 0;
    jint _rangeBufferOffset = (jint) 0;
    jarray _precisionArray = (jarray) 0;
    jint _precisionBufferOffset = (jint) 0;
    jint _rangeRemaining;
    GLint *range = (GLint *) 0;
    jint _precisionRemaining;
    GLint *precision = (GLint *) 0;

    range = (GLint *)getPointer(_env, range_buf, &_rangeArray, &_rangeRemaining, &_rangeBufferOffset);
    if (_rangeRemaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    precision = (GLint *)getPointer(_env, precision_buf, &_precisionArray, &_precisionRemaining, &_precisionBufferOffset);
    if (_precisionRemaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (range == NULL) {
        char * _rangeBase = (char *)_env->GetPrimitiveArrayCritical(_rangeArray, (jboolean *) 0);
        range = (GLint *) (_rangeBase + _rangeBufferOffset);
    }
    if (precision == NULL) {
        char * _precisionBase = (char *)_env->GetPrimitiveArrayCritical(_precisionArray, (jboolean *) 0);
        precision = (GLint *) (_precisionBase + _precisionBufferOffset);
    }
    glGetShaderPrecisionFormat(
        (GLenum)shadertype,
        (GLenum)precisiontype,
        (GLint *)range,
        (GLint *)precision
    );

exit:
    if (_precisionArray) {
        releasePointer(_env, _precisionArray, precision, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_rangeArray) {
        releasePointer(_env, _rangeArray, range, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetShaderSource ( GLuint shader, GLsizei bufsize, GLsizei *length, char *source ) */
static void
android_glGetShaderSource__II_3II_3BI
  (JNIEnv *_env, jobject _this, jint shader, jint bufsize, jintArray length_ref, jint lengthOffset, jbyteArray source_ref, jint sourceOffset) {
    jint _exception = 0;
    const char * _exceptionType;
    const char * _exceptionMessage;
    GLsizei *length_base = (GLsizei *) 0;
    jint _lengthRemaining;
    GLsizei *length = (GLsizei *) 0;
    char *source_base = (char *) 0;
    jint _sourceRemaining;
    char *source = (char *) 0;

    if (!length_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length == null";
        goto exit;
    }
    if (lengthOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "lengthOffset < 0";
        goto exit;
    }
    _lengthRemaining = _env->GetArrayLength(length_ref) - lengthOffset;
    length_base = (GLsizei *)
        _env->GetPrimitiveArrayCritical(length_ref, (jboolean *)0);
    length = length_base + lengthOffset;

    if (!source_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "source == null";
        goto exit;
    }
    if (sourceOffset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "sourceOffset < 0";
        goto exit;
    }
    _sourceRemaining = _env->GetArrayLength(source_ref) - sourceOffset;
    source_base = (char *)
        _env->GetPrimitiveArrayCritical(source_ref, (jboolean *)0);
    source = source_base + sourceOffset;

    glGetShaderSource(
        (GLuint)shader,
        (GLsizei)bufsize,
        (GLsizei *)length,
        (char *)source
    );

exit:
    if (source_base) {
        _env->ReleasePrimitiveArrayCritical(source_ref, source_base,
            _exception ? JNI_ABORT: 0);
    }
    if (length_base) {
        _env->ReleasePrimitiveArrayCritical(length_ref, length_base,
            _exception ? JNI_ABORT: 0);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetShaderSource ( GLuint shader, GLsizei bufsize, GLsizei *length, char *source ) */
static void
android_glGetShaderSource__IILjava_nio_IntBuffer_2B
  (JNIEnv *_env, jobject _this, jint shader, jint bufsize, jobject length_buf, jbyte source) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLsizei *length = (GLsizei *) 0;

    length = (GLsizei *)getPointer(_env, length_buf, &_array, &_remaining, &_bufferOffset);
    if (length == NULL) {
        char * _lengthBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        length = (GLsizei *) (_lengthBase + _bufferOffset);
    }
    glGetShaderSource(
        (GLuint)shader,
        (GLsizei)bufsize,
        (GLsizei *)length,
        (char *)source
    );
    if (_array) {
        releasePointer(_env, _array, length, JNI_TRUE);
    }
}

/* void glGetShaderSource ( GLuint shader, GLsizei bufsize, GLsizei *length, char *source ) */
static jstring android_glGetShaderSource(JNIEnv *_env, jobject, jint shader) {
    GLint shaderLen = 0;
    glGetShaderiv((GLuint)shader, GL_SHADER_SOURCE_LENGTH, &shaderLen);
    if (!shaderLen) {
        return _env->NewStringUTF("");
    }
    char* buf = (char*) malloc(shaderLen);
    if (buf == NULL) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "out of memory");
        return NULL;
    }
    glGetShaderSource(shader, shaderLen, NULL, buf);
    jstring result = _env->NewStringUTF(buf);
    free(buf);
    return result;
}
/* const GLubyte * glGetString ( GLenum name ) */
static jstring android_glGetString(JNIEnv* _env, jobject, jint name) {
    const char* chars = (const char*) glGetString((GLenum) name);
    return _env->NewStringUTF(chars);
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexParameterfv(
        (GLenum)target,
        (GLenum)pname,
        (GLfloat *)params
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

/* void glGetTexParameterfv ( GLenum target, GLenum pname, GLfloat *params ) */
static void
android_glGetTexParameterfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    params = (GLfloat *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLfloat *) (_paramsBase + _bufferOffset);
    }
    glGetTexParameterfv(
        (GLenum)target,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexParameteriv(
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

/* void glGetTexParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetTexParameteriv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetTexParameteriv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetUniformfv ( GLuint program, GLint location, GLfloat *params ) */
static void
android_glGetUniformfv__II_3FI
  (JNIEnv *_env, jobject _this, jint program, jint location, jfloatArray params_ref, jint offset) {
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetUniformfv(
        (GLuint)program,
        (GLint)location,
        (GLfloat *)params
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

/* void glGetUniformfv ( GLuint program, GLint location, GLfloat *params ) */
static void
android_glGetUniformfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    params = (GLfloat *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLfloat *) (_paramsBase + _bufferOffset);
    }
    glGetUniformfv(
        (GLuint)program,
        (GLint)location,
        (GLfloat *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetUniformiv ( GLuint program, GLint location, GLint *params ) */
static void
android_glGetUniformiv__II_3II
  (JNIEnv *_env, jobject _this, jint program, jint location, jintArray params_ref, jint offset) {
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetUniformiv(
        (GLuint)program,
        (GLint)location,
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

/* void glGetUniformiv ( GLuint program, GLint location, GLint *params ) */
static void
android_glGetUniformiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint program, jint location, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetUniformiv(
        (GLuint)program,
        (GLint)location,
        (GLint *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* GLint glGetUniformLocation ( GLuint program, const char *name ) */
static jint
android_glGetUniformLocation__ILjava_lang_String_2
  (JNIEnv *_env, jobject _this, jint program, jstring name) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLint _returnValue = 0;
    const char* _nativename = 0;

    if (!name) {
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "name == null";
        goto exit;
    }
    _nativename = _env->GetStringUTFChars(name, 0);

    _returnValue = glGetUniformLocation(
        (GLuint)program,
        (char *)_nativename
    );

exit:
    if (_nativename) {
        _env->ReleaseStringUTFChars(name, _nativename);
    }

    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
    return (jint)_returnValue;
}

/* void glGetVertexAttribfv ( GLuint index, GLenum pname, GLfloat *params ) */
static void
android_glGetVertexAttribfv__II_3FI
  (JNIEnv *_env, jobject _this, jint index, jint pname, jfloatArray params_ref, jint offset) {
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
#if defined(GL_CURRENT_VERTEX_ATTRIB)
        case GL_CURRENT_VERTEX_ATTRIB:
#endif // defined(GL_CURRENT_VERTEX_ATTRIB)
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetVertexAttribfv(
        (GLuint)index,
        (GLenum)pname,
        (GLfloat *)params
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

/* void glGetVertexAttribfv ( GLuint index, GLenum pname, GLfloat *params ) */
static void
android_glGetVertexAttribfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint index, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    params = (GLfloat *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    int _needed;
    switch (pname) {
#if defined(GL_CURRENT_VERTEX_ATTRIB)
        case GL_CURRENT_VERTEX_ATTRIB:
#endif // defined(GL_CURRENT_VERTEX_ATTRIB)
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
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLfloat *) (_paramsBase + _bufferOffset);
    }
    glGetVertexAttribfv(
        (GLuint)index,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glGetVertexAttribiv ( GLuint index, GLenum pname, GLint *params ) */
static void
android_glGetVertexAttribiv__II_3II
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
    int _needed;
    switch (pname) {
#if defined(GL_CURRENT_VERTEX_ATTRIB)
        case GL_CURRENT_VERTEX_ATTRIB:
#endif // defined(GL_CURRENT_VERTEX_ATTRIB)
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetVertexAttribiv(
        (GLuint)index,
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

/* void glGetVertexAttribiv ( GLuint index, GLenum pname, GLint *params ) */
static void
android_glGetVertexAttribiv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint index, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    int _needed;
    switch (pname) {
#if defined(GL_CURRENT_VERTEX_ATTRIB)
        case GL_CURRENT_VERTEX_ATTRIB:
#endif // defined(GL_CURRENT_VERTEX_ATTRIB)
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
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glGetVertexAttribiv(
        (GLuint)index,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
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

/* GLboolean glIsFramebuffer ( GLuint framebuffer ) */
static jboolean
android_glIsFramebuffer__I
  (JNIEnv *_env, jobject _this, jint framebuffer) {
    GLboolean _returnValue;
    _returnValue = glIsFramebuffer(
        (GLuint)framebuffer
    );
    return (jboolean)_returnValue;
}

/* GLboolean glIsProgram ( GLuint program ) */
static jboolean
android_glIsProgram__I
  (JNIEnv *_env, jobject _this, jint program) {
    GLboolean _returnValue;
    _returnValue = glIsProgram(
        (GLuint)program
    );
    return (jboolean)_returnValue;
}

/* GLboolean glIsRenderbuffer ( GLuint renderbuffer ) */
static jboolean
android_glIsRenderbuffer__I
  (JNIEnv *_env, jobject _this, jint renderbuffer) {
    GLboolean _returnValue;
    _returnValue = glIsRenderbuffer(
        (GLuint)renderbuffer
    );
    return (jboolean)_returnValue;
}

/* GLboolean glIsShader ( GLuint shader ) */
static jboolean
android_glIsShader__I
  (JNIEnv *_env, jobject _this, jint shader) {
    GLboolean _returnValue;
    _returnValue = glIsShader(
        (GLuint)shader
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

/* void glLineWidth ( GLfloat width ) */
static void
android_glLineWidth__F
  (JNIEnv *_env, jobject _this, jfloat width) {
    glLineWidth(
        (GLfloat)width
    );
}

/* void glLinkProgram ( GLuint program ) */
static void
android_glLinkProgram__I
  (JNIEnv *_env, jobject _this, jint program) {
    glLinkProgram(
        (GLuint)program
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

/* void glPolygonOffset ( GLfloat factor, GLfloat units ) */
static void
android_glPolygonOffset__FF
  (JNIEnv *_env, jobject _this, jfloat factor, jfloat units) {
    glPolygonOffset(
        (GLfloat)factor,
        (GLfloat)units
    );
}

/* void glReadPixels ( GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, GLvoid *pixels ) */
static void
android_glReadPixels__IIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint x, jint y, jint width, jint height, jint format, jint type, jobject pixels_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *pixels = (GLvoid *) 0;

    pixels = (GLvoid *)getPointer(_env, pixels_buf, &_array, &_remaining, &_bufferOffset);
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
    if (_array) {
        releasePointer(_env, _array, pixels, JNI_TRUE);
    }
}

/* void glReleaseShaderCompiler ( void ) */
static void
android_glReleaseShaderCompiler__
  (JNIEnv *_env, jobject _this) {
    glReleaseShaderCompiler();
}

/* void glRenderbufferStorage ( GLenum target, GLenum internalformat, GLsizei width, GLsizei height ) */
static void
android_glRenderbufferStorage__IIII
  (JNIEnv *_env, jobject _this, jint target, jint internalformat, jint width, jint height) {
    glRenderbufferStorage(
        (GLenum)target,
        (GLenum)internalformat,
        (GLsizei)width,
        (GLsizei)height
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

/* void glShaderBinary ( GLsizei n, const GLuint *shaders, GLenum binaryformat, const GLvoid *binary, GLsizei length ) */
static void
android_glShaderBinary__I_3IIILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint n, jintArray shaders_ref, jint offset, jint binaryformat, jobject binary_buf, jint length) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    GLuint *shaders_base = (GLuint *) 0;
    jint _shadersRemaining;
    GLuint *shaders = (GLuint *) 0;
    jint _binaryRemaining;
    GLvoid *binary = (GLvoid *) 0;

    if (!shaders_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "shaders == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _shadersRemaining = _env->GetArrayLength(shaders_ref) - offset;
    shaders_base = (GLuint *)
        _env->GetPrimitiveArrayCritical(shaders_ref, (jboolean *)0);
    shaders = shaders_base + offset;

    binary = (GLvoid *)getPointer(_env, binary_buf, &_array, &_binaryRemaining, &_bufferOffset);
    if (_binaryRemaining < length) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < length < needed";
        goto exit;
    }
    if (binary == NULL) {
        char * _binaryBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        binary = (GLvoid *) (_binaryBase + _bufferOffset);
    }
    glShaderBinary(
        (GLsizei)n,
        (GLuint *)shaders,
        (GLenum)binaryformat,
        (GLvoid *)binary,
        (GLsizei)length
    );

exit:
    if (_array) {
        releasePointer(_env, _array, binary, JNI_FALSE);
    }
    if (shaders_base) {
        _env->ReleasePrimitiveArrayCritical(shaders_ref, shaders_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glShaderBinary ( GLsizei n, const GLuint *shaders, GLenum binaryformat, const GLvoid *binary, GLsizei length ) */
static void
android_glShaderBinary__ILjava_nio_IntBuffer_2ILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint n, jobject shaders_buf, jint binaryformat, jobject binary_buf, jint length) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _shadersArray = (jarray) 0;
    jint _shadersBufferOffset = (jint) 0;
    jarray _binaryArray = (jarray) 0;
    jint _binaryBufferOffset = (jint) 0;
    jint _shadersRemaining;
    GLuint *shaders = (GLuint *) 0;
    jint _binaryRemaining;
    GLvoid *binary = (GLvoid *) 0;

    shaders = (GLuint *)getPointer(_env, shaders_buf, &_shadersArray, &_shadersRemaining, &_shadersBufferOffset);
    binary = (GLvoid *)getPointer(_env, binary_buf, &_binaryArray, &_binaryRemaining, &_binaryBufferOffset);
    if (_binaryRemaining < length) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < length < needed";
        goto exit;
    }
    if (shaders == NULL) {
        char * _shadersBase = (char *)_env->GetPrimitiveArrayCritical(_shadersArray, (jboolean *) 0);
        shaders = (GLuint *) (_shadersBase + _shadersBufferOffset);
    }
    if (binary == NULL) {
        char * _binaryBase = (char *)_env->GetPrimitiveArrayCritical(_binaryArray, (jboolean *) 0);
        binary = (GLvoid *) (_binaryBase + _binaryBufferOffset);
    }
    glShaderBinary(
        (GLsizei)n,
        (GLuint *)shaders,
        (GLenum)binaryformat,
        (GLvoid *)binary,
        (GLsizei)length
    );

exit:
    if (_binaryArray) {
        releasePointer(_env, _binaryArray, binary, JNI_FALSE);
    }
    if (_shadersArray) {
        releasePointer(_env, _shadersArray, shaders, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}


/* void glShaderSource ( GLuint shader, GLsizei count, const GLchar ** string, const GLint * length ) */
static
void
android_glShaderSource
    (JNIEnv *_env, jobject _this, jint shader, jstring string) {

    if (!string) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "string == null");
        return;
    }

    const char* nativeString = _env->GetStringUTFChars(string, 0);
    const char* strings[] = {nativeString};
    glShaderSource(shader, 1, strings, 0);
    _env->ReleaseStringUTFChars(string, nativeString);
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

/* void glStencilFuncSeparate ( GLenum face, GLenum func, GLint ref, GLuint mask ) */
static void
android_glStencilFuncSeparate__IIII
  (JNIEnv *_env, jobject _this, jint face, jint func, jint ref, jint mask) {
    glStencilFuncSeparate(
        (GLenum)face,
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

/* void glStencilMaskSeparate ( GLenum face, GLuint mask ) */
static void
android_glStencilMaskSeparate__II
  (JNIEnv *_env, jobject _this, jint face, jint mask) {
    glStencilMaskSeparate(
        (GLenum)face,
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

/* void glStencilOpSeparate ( GLenum face, GLenum fail, GLenum zfail, GLenum zpass ) */
static void
android_glStencilOpSeparate__IIII
  (JNIEnv *_env, jobject _this, jint face, jint fail, jint zfail, jint zpass) {
    glStencilOpSeparate(
        (GLenum)face,
        (GLenum)fail,
        (GLenum)zfail,
        (GLenum)zpass
    );
}

/* void glTexImage2D ( GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLint border, GLenum format, GLenum type, const GLvoid *pixels ) */
static void
android_glTexImage2D__IIIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint internalformat, jint width, jint height, jint border, jint format, jint type, jobject pixels_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *pixels = (GLvoid *) 0;

    if (pixels_buf) {
        pixels = (GLvoid *)getPointer(_env, pixels_buf, &_array, &_remaining, &_bufferOffset);
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glTexParameterfv(
        (GLenum)target,
        (GLenum)pname,
        (GLfloat *)params
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

/* void glTexParameterfv ( GLenum target, GLenum pname, const GLfloat *params ) */
static void
android_glTexParameterfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    params = (GLfloat *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLfloat *) (_paramsBase + _bufferOffset);
    }
    glTexParameterfv(
        (GLenum)target,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, JNI_FALSE);
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
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glTexParameteriv(
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

/* void glTexParameteriv ( GLenum target, GLenum pname, const GLint *params ) */
static void
android_glTexParameteriv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (params == NULL) {
        char * _paramsBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        params = (GLint *) (_paramsBase + _bufferOffset);
    }
    glTexParameteriv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glTexSubImage2D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const GLvoid *pixels ) */
static void
android_glTexSubImage2D__IIIIIIIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint level, jint xoffset, jint yoffset, jint width, jint height, jint format, jint type, jobject pixels_buf) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *pixels = (GLvoid *) 0;

    if (pixels_buf) {
        pixels = (GLvoid *)getPointer(_env, pixels_buf, &_array, &_remaining, &_bufferOffset);
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
}

/* void glUniform1f ( GLint location, GLfloat x ) */
static void
android_glUniform1f__IF
  (JNIEnv *_env, jobject _this, jint location, jfloat x) {
    glUniform1f(
        (GLint)location,
        (GLfloat)x
    );
}

/* void glUniform1fv ( GLint location, GLsizei count, const GLfloat *v ) */
static void
android_glUniform1fv__II_3FI
  (JNIEnv *_env, jobject _this, jint location, jint count, jfloatArray v_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *v_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *v = (GLfloat *) 0;

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
    if (_remaining < count) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < count < needed";
        goto exit;
    }
    v_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(v_ref, (jboolean *)0);
    v = v_base + offset;

    glUniform1fv(
        (GLint)location,
        (GLsizei)count,
        (GLfloat *)v
    );

exit:
    if (v_base) {
        _env->ReleasePrimitiveArrayCritical(v_ref, v_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform1fv ( GLint location, GLsizei count, const GLfloat *v ) */
static void
android_glUniform1fv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jobject v_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *v = (GLfloat *) 0;

    v = (GLfloat *)getPointer(_env, v_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < count) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < count < needed";
        goto exit;
    }
    if (v == NULL) {
        char * _vBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        v = (GLfloat *) (_vBase + _bufferOffset);
    }
    glUniform1fv(
        (GLint)location,
        (GLsizei)count,
        (GLfloat *)v
    );

exit:
    if (_array) {
        releasePointer(_env, _array, v, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform1i ( GLint location, GLint x ) */
static void
android_glUniform1i__II
  (JNIEnv *_env, jobject _this, jint location, jint x) {
    glUniform1i(
        (GLint)location,
        (GLint)x
    );
}

/* void glUniform1iv ( GLint location, GLsizei count, const GLint *v ) */
static void
android_glUniform1iv__II_3II
  (JNIEnv *_env, jobject _this, jint location, jint count, jintArray v_ref, jint offset) {
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
    if (_remaining < count) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < count < needed";
        goto exit;
    }
    v_base = (GLint *)
        _env->GetPrimitiveArrayCritical(v_ref, (jboolean *)0);
    v = v_base + offset;

    glUniform1iv(
        (GLint)location,
        (GLsizei)count,
        (GLint *)v
    );

exit:
    if (v_base) {
        _env->ReleasePrimitiveArrayCritical(v_ref, v_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform1iv ( GLint location, GLsizei count, const GLint *v ) */
static void
android_glUniform1iv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jobject v_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *v = (GLint *) 0;

    v = (GLint *)getPointer(_env, v_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < count) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < count < needed";
        goto exit;
    }
    if (v == NULL) {
        char * _vBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        v = (GLint *) (_vBase + _bufferOffset);
    }
    glUniform1iv(
        (GLint)location,
        (GLsizei)count,
        (GLint *)v
    );

exit:
    if (_array) {
        releasePointer(_env, _array, v, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform2f ( GLint location, GLfloat x, GLfloat y ) */
static void
android_glUniform2f__IFF
  (JNIEnv *_env, jobject _this, jint location, jfloat x, jfloat y) {
    glUniform2f(
        (GLint)location,
        (GLfloat)x,
        (GLfloat)y
    );
}

/* void glUniform2fv ( GLint location, GLsizei count, const GLfloat *v ) */
static void
android_glUniform2fv__II_3FI
  (JNIEnv *_env, jobject _this, jint location, jint count, jfloatArray v_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *v_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *v = (GLfloat *) 0;

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
    if (_remaining < count*2) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < count*2 < needed";
        goto exit;
    }
    v_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(v_ref, (jboolean *)0);
    v = v_base + offset;

    glUniform2fv(
        (GLint)location,
        (GLsizei)count,
        (GLfloat *)v
    );

exit:
    if (v_base) {
        _env->ReleasePrimitiveArrayCritical(v_ref, v_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform2fv ( GLint location, GLsizei count, const GLfloat *v ) */
static void
android_glUniform2fv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jobject v_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *v = (GLfloat *) 0;

    v = (GLfloat *)getPointer(_env, v_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < count*2) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < count*2 < needed";
        goto exit;
    }
    if (v == NULL) {
        char * _vBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        v = (GLfloat *) (_vBase + _bufferOffset);
    }
    glUniform2fv(
        (GLint)location,
        (GLsizei)count,
        (GLfloat *)v
    );

exit:
    if (_array) {
        releasePointer(_env, _array, v, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform2i ( GLint location, GLint x, GLint y ) */
static void
android_glUniform2i__III
  (JNIEnv *_env, jobject _this, jint location, jint x, jint y) {
    glUniform2i(
        (GLint)location,
        (GLint)x,
        (GLint)y
    );
}

/* void glUniform2iv ( GLint location, GLsizei count, const GLint *v ) */
static void
android_glUniform2iv__II_3II
  (JNIEnv *_env, jobject _this, jint location, jint count, jintArray v_ref, jint offset) {
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
    if (_remaining < count*2) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < count*2 < needed";
        goto exit;
    }
    v_base = (GLint *)
        _env->GetPrimitiveArrayCritical(v_ref, (jboolean *)0);
    v = v_base + offset;

    glUniform2iv(
        (GLint)location,
        (GLsizei)count,
        (GLint *)v
    );

exit:
    if (v_base) {
        _env->ReleasePrimitiveArrayCritical(v_ref, v_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform2iv ( GLint location, GLsizei count, const GLint *v ) */
static void
android_glUniform2iv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jobject v_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *v = (GLint *) 0;

    v = (GLint *)getPointer(_env, v_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < count*2) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < count*2 < needed";
        goto exit;
    }
    if (v == NULL) {
        char * _vBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        v = (GLint *) (_vBase + _bufferOffset);
    }
    glUniform2iv(
        (GLint)location,
        (GLsizei)count,
        (GLint *)v
    );

exit:
    if (_array) {
        releasePointer(_env, _array, v, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform3f ( GLint location, GLfloat x, GLfloat y, GLfloat z ) */
static void
android_glUniform3f__IFFF
  (JNIEnv *_env, jobject _this, jint location, jfloat x, jfloat y, jfloat z) {
    glUniform3f(
        (GLint)location,
        (GLfloat)x,
        (GLfloat)y,
        (GLfloat)z
    );
}

/* void glUniform3fv ( GLint location, GLsizei count, const GLfloat *v ) */
static void
android_glUniform3fv__II_3FI
  (JNIEnv *_env, jobject _this, jint location, jint count, jfloatArray v_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *v_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *v = (GLfloat *) 0;

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
    if (_remaining < count*3) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < count*3 < needed";
        goto exit;
    }
    v_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(v_ref, (jboolean *)0);
    v = v_base + offset;

    glUniform3fv(
        (GLint)location,
        (GLsizei)count,
        (GLfloat *)v
    );

exit:
    if (v_base) {
        _env->ReleasePrimitiveArrayCritical(v_ref, v_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform3fv ( GLint location, GLsizei count, const GLfloat *v ) */
static void
android_glUniform3fv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jobject v_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *v = (GLfloat *) 0;

    v = (GLfloat *)getPointer(_env, v_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < count*3) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < count*3 < needed";
        goto exit;
    }
    if (v == NULL) {
        char * _vBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        v = (GLfloat *) (_vBase + _bufferOffset);
    }
    glUniform3fv(
        (GLint)location,
        (GLsizei)count,
        (GLfloat *)v
    );

exit:
    if (_array) {
        releasePointer(_env, _array, v, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform3i ( GLint location, GLint x, GLint y, GLint z ) */
static void
android_glUniform3i__IIII
  (JNIEnv *_env, jobject _this, jint location, jint x, jint y, jint z) {
    glUniform3i(
        (GLint)location,
        (GLint)x,
        (GLint)y,
        (GLint)z
    );
}

/* void glUniform3iv ( GLint location, GLsizei count, const GLint *v ) */
static void
android_glUniform3iv__II_3II
  (JNIEnv *_env, jobject _this, jint location, jint count, jintArray v_ref, jint offset) {
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
    if (_remaining < count*3) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < count*3 < needed";
        goto exit;
    }
    v_base = (GLint *)
        _env->GetPrimitiveArrayCritical(v_ref, (jboolean *)0);
    v = v_base + offset;

    glUniform3iv(
        (GLint)location,
        (GLsizei)count,
        (GLint *)v
    );

exit:
    if (v_base) {
        _env->ReleasePrimitiveArrayCritical(v_ref, v_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform3iv ( GLint location, GLsizei count, const GLint *v ) */
static void
android_glUniform3iv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jobject v_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *v = (GLint *) 0;

    v = (GLint *)getPointer(_env, v_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < count*3) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < count*3 < needed";
        goto exit;
    }
    if (v == NULL) {
        char * _vBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        v = (GLint *) (_vBase + _bufferOffset);
    }
    glUniform3iv(
        (GLint)location,
        (GLsizei)count,
        (GLint *)v
    );

exit:
    if (_array) {
        releasePointer(_env, _array, v, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform4f ( GLint location, GLfloat x, GLfloat y, GLfloat z, GLfloat w ) */
static void
android_glUniform4f__IFFFF
  (JNIEnv *_env, jobject _this, jint location, jfloat x, jfloat y, jfloat z, jfloat w) {
    glUniform4f(
        (GLint)location,
        (GLfloat)x,
        (GLfloat)y,
        (GLfloat)z,
        (GLfloat)w
    );
}

/* void glUniform4fv ( GLint location, GLsizei count, const GLfloat *v ) */
static void
android_glUniform4fv__II_3FI
  (JNIEnv *_env, jobject _this, jint location, jint count, jfloatArray v_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *v_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *v = (GLfloat *) 0;

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
    if (_remaining < count*4) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < count*4 < needed";
        goto exit;
    }
    v_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(v_ref, (jboolean *)0);
    v = v_base + offset;

    glUniform4fv(
        (GLint)location,
        (GLsizei)count,
        (GLfloat *)v
    );

exit:
    if (v_base) {
        _env->ReleasePrimitiveArrayCritical(v_ref, v_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform4fv ( GLint location, GLsizei count, const GLfloat *v ) */
static void
android_glUniform4fv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jobject v_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *v = (GLfloat *) 0;

    v = (GLfloat *)getPointer(_env, v_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < count*4) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < count*4 < needed";
        goto exit;
    }
    if (v == NULL) {
        char * _vBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        v = (GLfloat *) (_vBase + _bufferOffset);
    }
    glUniform4fv(
        (GLint)location,
        (GLsizei)count,
        (GLfloat *)v
    );

exit:
    if (_array) {
        releasePointer(_env, _array, v, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform4i ( GLint location, GLint x, GLint y, GLint z, GLint w ) */
static void
android_glUniform4i__IIIII
  (JNIEnv *_env, jobject _this, jint location, jint x, jint y, jint z, jint w) {
    glUniform4i(
        (GLint)location,
        (GLint)x,
        (GLint)y,
        (GLint)z,
        (GLint)w
    );
}

/* void glUniform4iv ( GLint location, GLsizei count, const GLint *v ) */
static void
android_glUniform4iv__II_3II
  (JNIEnv *_env, jobject _this, jint location, jint count, jintArray v_ref, jint offset) {
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
    if (_remaining < count*4) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < count*4 < needed";
        goto exit;
    }
    v_base = (GLint *)
        _env->GetPrimitiveArrayCritical(v_ref, (jboolean *)0);
    v = v_base + offset;

    glUniform4iv(
        (GLint)location,
        (GLsizei)count,
        (GLint *)v
    );

exit:
    if (v_base) {
        _env->ReleasePrimitiveArrayCritical(v_ref, v_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniform4iv ( GLint location, GLsizei count, const GLint *v ) */
static void
android_glUniform4iv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jobject v_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLint *v = (GLint *) 0;

    v = (GLint *)getPointer(_env, v_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < count*4) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < count*4 < needed";
        goto exit;
    }
    if (v == NULL) {
        char * _vBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        v = (GLint *) (_vBase + _bufferOffset);
    }
    glUniform4iv(
        (GLint)location,
        (GLsizei)count,
        (GLint *)v
    );

exit:
    if (_array) {
        releasePointer(_env, _array, v, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix2fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix2fv__IIZ_3FI
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
    if (_remaining < count*4) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < count*4 < needed";
        goto exit;
    }
    value_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniformMatrix2fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (value_base) {
        _env->ReleasePrimitiveArrayCritical(value_ref, value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix2fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix2fv__IIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    value = (GLfloat *)getPointer(_env, value_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < count*4) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < count*4 < needed";
        goto exit;
    }
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        value = (GLfloat *) (_valueBase + _bufferOffset);
    }
    glUniformMatrix2fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (_array) {
        releasePointer(_env, _array, value, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix3fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix3fv__IIZ_3FI
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
    if (_remaining < count*9) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < count*9 < needed";
        goto exit;
    }
    value_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniformMatrix3fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (value_base) {
        _env->ReleasePrimitiveArrayCritical(value_ref, value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix3fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix3fv__IIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    value = (GLfloat *)getPointer(_env, value_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < count*9) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < count*9 < needed";
        goto exit;
    }
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        value = (GLfloat *) (_valueBase + _bufferOffset);
    }
    glUniformMatrix3fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (_array) {
        releasePointer(_env, _array, value, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix4fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix4fv__IIZ_3FI
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
    if (_remaining < count*16) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < count*16 < needed";
        goto exit;
    }
    value_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(value_ref, (jboolean *)0);
    value = value_base + offset;

    glUniformMatrix4fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (value_base) {
        _env->ReleasePrimitiveArrayCritical(value_ref, value_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUniformMatrix4fv ( GLint location, GLsizei count, GLboolean transpose, const GLfloat *value ) */
static void
android_glUniformMatrix4fv__IIZLjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint location, jint count, jboolean transpose, jobject value_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *value = (GLfloat *) 0;

    value = (GLfloat *)getPointer(_env, value_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < count*16) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < count*16 < needed";
        goto exit;
    }
    if (value == NULL) {
        char * _valueBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        value = (GLfloat *) (_valueBase + _bufferOffset);
    }
    glUniformMatrix4fv(
        (GLint)location,
        (GLsizei)count,
        (GLboolean)transpose,
        (GLfloat *)value
    );

exit:
    if (_array) {
        releasePointer(_env, _array, value, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glUseProgram ( GLuint program ) */
static void
android_glUseProgram__I
  (JNIEnv *_env, jobject _this, jint program) {
    glUseProgram(
        (GLuint)program
    );
}

/* void glValidateProgram ( GLuint program ) */
static void
android_glValidateProgram__I
  (JNIEnv *_env, jobject _this, jint program) {
    glValidateProgram(
        (GLuint)program
    );
}

/* void glVertexAttrib1f ( GLuint indx, GLfloat x ) */
static void
android_glVertexAttrib1f__IF
  (JNIEnv *_env, jobject _this, jint indx, jfloat x) {
    glVertexAttrib1f(
        (GLuint)indx,
        (GLfloat)x
    );
}

/* void glVertexAttrib1fv ( GLuint indx, const GLfloat *values ) */
static void
android_glVertexAttrib1fv__I_3FI
  (JNIEnv *_env, jobject _this, jint indx, jfloatArray values_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *values_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *values = (GLfloat *) 0;

    if (!values_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "values == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(values_ref) - offset;
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 1 < needed";
        goto exit;
    }
    values_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(values_ref, (jboolean *)0);
    values = values_base + offset;

    glVertexAttrib1fv(
        (GLuint)indx,
        (GLfloat *)values
    );

exit:
    if (values_base) {
        _env->ReleasePrimitiveArrayCritical(values_ref, values_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexAttrib1fv ( GLuint indx, const GLfloat *values ) */
static void
android_glVertexAttrib1fv__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint indx, jobject values_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *values = (GLfloat *) 0;

    values = (GLfloat *)getPointer(_env, values_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 1) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 1 < needed";
        goto exit;
    }
    if (values == NULL) {
        char * _valuesBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        values = (GLfloat *) (_valuesBase + _bufferOffset);
    }
    glVertexAttrib1fv(
        (GLuint)indx,
        (GLfloat *)values
    );

exit:
    if (_array) {
        releasePointer(_env, _array, values, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexAttrib2f ( GLuint indx, GLfloat x, GLfloat y ) */
static void
android_glVertexAttrib2f__IFF
  (JNIEnv *_env, jobject _this, jint indx, jfloat x, jfloat y) {
    glVertexAttrib2f(
        (GLuint)indx,
        (GLfloat)x,
        (GLfloat)y
    );
}

/* void glVertexAttrib2fv ( GLuint indx, const GLfloat *values ) */
static void
android_glVertexAttrib2fv__I_3FI
  (JNIEnv *_env, jobject _this, jint indx, jfloatArray values_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *values_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *values = (GLfloat *) 0;

    if (!values_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "values == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(values_ref) - offset;
    if (_remaining < 2) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 2 < needed";
        goto exit;
    }
    values_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(values_ref, (jboolean *)0);
    values = values_base + offset;

    glVertexAttrib2fv(
        (GLuint)indx,
        (GLfloat *)values
    );

exit:
    if (values_base) {
        _env->ReleasePrimitiveArrayCritical(values_ref, values_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexAttrib2fv ( GLuint indx, const GLfloat *values ) */
static void
android_glVertexAttrib2fv__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint indx, jobject values_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *values = (GLfloat *) 0;

    values = (GLfloat *)getPointer(_env, values_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 2) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 2 < needed";
        goto exit;
    }
    if (values == NULL) {
        char * _valuesBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        values = (GLfloat *) (_valuesBase + _bufferOffset);
    }
    glVertexAttrib2fv(
        (GLuint)indx,
        (GLfloat *)values
    );

exit:
    if (_array) {
        releasePointer(_env, _array, values, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexAttrib3f ( GLuint indx, GLfloat x, GLfloat y, GLfloat z ) */
static void
android_glVertexAttrib3f__IFFF
  (JNIEnv *_env, jobject _this, jint indx, jfloat x, jfloat y, jfloat z) {
    glVertexAttrib3f(
        (GLuint)indx,
        (GLfloat)x,
        (GLfloat)y,
        (GLfloat)z
    );
}

/* void glVertexAttrib3fv ( GLuint indx, const GLfloat *values ) */
static void
android_glVertexAttrib3fv__I_3FI
  (JNIEnv *_env, jobject _this, jint indx, jfloatArray values_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *values_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *values = (GLfloat *) 0;

    if (!values_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "values == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(values_ref) - offset;
    if (_remaining < 3) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 3 < needed";
        goto exit;
    }
    values_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(values_ref, (jboolean *)0);
    values = values_base + offset;

    glVertexAttrib3fv(
        (GLuint)indx,
        (GLfloat *)values
    );

exit:
    if (values_base) {
        _env->ReleasePrimitiveArrayCritical(values_ref, values_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexAttrib3fv ( GLuint indx, const GLfloat *values ) */
static void
android_glVertexAttrib3fv__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint indx, jobject values_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *values = (GLfloat *) 0;

    values = (GLfloat *)getPointer(_env, values_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 3) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 3 < needed";
        goto exit;
    }
    if (values == NULL) {
        char * _valuesBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        values = (GLfloat *) (_valuesBase + _bufferOffset);
    }
    glVertexAttrib3fv(
        (GLuint)indx,
        (GLfloat *)values
    );

exit:
    if (_array) {
        releasePointer(_env, _array, values, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexAttrib4f ( GLuint indx, GLfloat x, GLfloat y, GLfloat z, GLfloat w ) */
static void
android_glVertexAttrib4f__IFFFF
  (JNIEnv *_env, jobject _this, jint indx, jfloat x, jfloat y, jfloat z, jfloat w) {
    glVertexAttrib4f(
        (GLuint)indx,
        (GLfloat)x,
        (GLfloat)y,
        (GLfloat)z,
        (GLfloat)w
    );
}

/* void glVertexAttrib4fv ( GLuint indx, const GLfloat *values ) */
static void
android_glVertexAttrib4fv__I_3FI
  (JNIEnv *_env, jobject _this, jint indx, jfloatArray values_ref, jint offset) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    GLfloat *values_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *values = (GLfloat *) 0;

    if (!values_ref) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "values == null";
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "offset < 0";
        goto exit;
    }
    _remaining = _env->GetArrayLength(values_ref) - offset;
    if (_remaining < 4) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "length - offset < 4 < needed";
        goto exit;
    }
    values_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(values_ref, (jboolean *)0);
    values = values_base + offset;

    glVertexAttrib4fv(
        (GLuint)indx,
        (GLfloat *)values
    );

exit:
    if (values_base) {
        _env->ReleasePrimitiveArrayCritical(values_ref, values_base,
            JNI_ABORT);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexAttrib4fv ( GLuint indx, const GLfloat *values ) */
static void
android_glVertexAttrib4fv__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint indx, jobject values_buf) {
    jint _exception = 0;
    const char * _exceptionType = NULL;
    const char * _exceptionMessage = NULL;
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLfloat *values = (GLfloat *) 0;

    values = (GLfloat *)getPointer(_env, values_buf, &_array, &_remaining, &_bufferOffset);
    if (_remaining < 4) {
        _exception = 1;
        _exceptionType = "java/lang/IllegalArgumentException";
        _exceptionMessage = "remaining() < 4 < needed";
        goto exit;
    }
    if (values == NULL) {
        char * _valuesBase = (char *)_env->GetPrimitiveArrayCritical(_array, (jboolean *) 0);
        values = (GLfloat *) (_valuesBase + _bufferOffset);
    }
    glVertexAttrib4fv(
        (GLuint)indx,
        (GLfloat *)values
    );

exit:
    if (_array) {
        releasePointer(_env, _array, values, JNI_FALSE);
    }
    if (_exception) {
        jniThrowException(_env, _exceptionType, _exceptionMessage);
    }
}

/* void glVertexAttribPointer ( GLuint indx, GLint size, GLenum type, GLboolean normalized, GLsizei stride, GLint offset ) */
static void
android_glVertexAttribPointer__IIIZII
  (JNIEnv *_env, jobject _this, jint indx, jint size, jint type, jboolean normalized, jint stride, jint offset) {
    glVertexAttribPointer(
        (GLuint)indx,
        (GLint)size,
        (GLenum)type,
        (GLboolean)normalized,
        (GLsizei)stride,
        (GLvoid *)offset
    );
}

/* void glVertexAttribPointer ( GLuint indx, GLint size, GLenum type, GLboolean normalized, GLsizei stride, const GLvoid *ptr ) */
static void
android_glVertexAttribPointerBounds__IIIZILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint indx, jint size, jint type, jboolean normalized, jint stride, jobject ptr_buf, jint remaining) {
    jarray _array = (jarray) 0;
    jint _bufferOffset = (jint) 0;
    jint _remaining;
    GLvoid *ptr = (GLvoid *) 0;

    if (ptr_buf) {
        ptr = (GLvoid *) getDirectBufferPointer(_env, ptr_buf);
        if ( ! ptr ) {
            return;
        }
    }
    glVertexAttribPointerBounds(
        (GLuint)indx,
        (GLint)size,
        (GLenum)type,
        (GLboolean)normalized,
        (GLsizei)stride,
        (GLvoid *)ptr,
        (GLsizei)remaining
    );
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

static const char *classPathName = "android/opengl/GLES20";

static JNINativeMethod methods[] = {
{"_nativeClassInit", "()V", (void*)nativeClassInit },
{"glActiveTexture", "(I)V", (void *) android_glActiveTexture__I },
{"glAttachShader", "(II)V", (void *) android_glAttachShader__II },
{"glBindAttribLocation", "(IILjava/lang/String;)V", (void *) android_glBindAttribLocation__IILjava_lang_String_2 },
{"glBindBuffer", "(II)V", (void *) android_glBindBuffer__II },
{"glBindFramebuffer", "(II)V", (void *) android_glBindFramebuffer__II },
{"glBindRenderbuffer", "(II)V", (void *) android_glBindRenderbuffer__II },
{"glBindTexture", "(II)V", (void *) android_glBindTexture__II },
{"glBlendColor", "(FFFF)V", (void *) android_glBlendColor__FFFF },
{"glBlendEquation", "(I)V", (void *) android_glBlendEquation__I },
{"glBlendEquationSeparate", "(II)V", (void *) android_glBlendEquationSeparate__II },
{"glBlendFunc", "(II)V", (void *) android_glBlendFunc__II },
{"glBlendFuncSeparate", "(IIII)V", (void *) android_glBlendFuncSeparate__IIII },
{"glBufferData", "(IILjava/nio/Buffer;I)V", (void *) android_glBufferData__IILjava_nio_Buffer_2I },
{"glBufferSubData", "(IIILjava/nio/Buffer;)V", (void *) android_glBufferSubData__IIILjava_nio_Buffer_2 },
{"glCheckFramebufferStatus", "(I)I", (void *) android_glCheckFramebufferStatus__I },
{"glClear", "(I)V", (void *) android_glClear__I },
{"glClearColor", "(FFFF)V", (void *) android_glClearColor__FFFF },
{"glClearDepthf", "(F)V", (void *) android_glClearDepthf__F },
{"glClearStencil", "(I)V", (void *) android_glClearStencil__I },
{"glColorMask", "(ZZZZ)V", (void *) android_glColorMask__ZZZZ },
{"glCompileShader", "(I)V", (void *) android_glCompileShader__I },
{"glCompressedTexImage2D", "(IIIIIIILjava/nio/Buffer;)V", (void *) android_glCompressedTexImage2D__IIIIIIILjava_nio_Buffer_2 },
{"glCompressedTexSubImage2D", "(IIIIIIIILjava/nio/Buffer;)V", (void *) android_glCompressedTexSubImage2D__IIIIIIIILjava_nio_Buffer_2 },
{"glCopyTexImage2D", "(IIIIIIII)V", (void *) android_glCopyTexImage2D__IIIIIIII },
{"glCopyTexSubImage2D", "(IIIIIIII)V", (void *) android_glCopyTexSubImage2D__IIIIIIII },
{"glCreateProgram", "()I", (void *) android_glCreateProgram__ },
{"glCreateShader", "(I)I", (void *) android_glCreateShader__I },
{"glCullFace", "(I)V", (void *) android_glCullFace__I },
{"glDeleteBuffers", "(I[II)V", (void *) android_glDeleteBuffers__I_3II },
{"glDeleteBuffers", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteBuffers__ILjava_nio_IntBuffer_2 },
{"glDeleteFramebuffers", "(I[II)V", (void *) android_glDeleteFramebuffers__I_3II },
{"glDeleteFramebuffers", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteFramebuffers__ILjava_nio_IntBuffer_2 },
{"glDeleteProgram", "(I)V", (void *) android_glDeleteProgram__I },
{"glDeleteRenderbuffers", "(I[II)V", (void *) android_glDeleteRenderbuffers__I_3II },
{"glDeleteRenderbuffers", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteRenderbuffers__ILjava_nio_IntBuffer_2 },
{"glDeleteShader", "(I)V", (void *) android_glDeleteShader__I },
{"glDeleteTextures", "(I[II)V", (void *) android_glDeleteTextures__I_3II },
{"glDeleteTextures", "(ILjava/nio/IntBuffer;)V", (void *) android_glDeleteTextures__ILjava_nio_IntBuffer_2 },
{"glDepthFunc", "(I)V", (void *) android_glDepthFunc__I },
{"glDepthMask", "(Z)V", (void *) android_glDepthMask__Z },
{"glDepthRangef", "(FF)V", (void *) android_glDepthRangef__FF },
{"glDetachShader", "(II)V", (void *) android_glDetachShader__II },
{"glDisable", "(I)V", (void *) android_glDisable__I },
{"glDisableVertexAttribArray", "(I)V", (void *) android_glDisableVertexAttribArray__I },
{"glDrawArrays", "(III)V", (void *) android_glDrawArrays__III },
{"glDrawElements", "(IIII)V", (void *) android_glDrawElements__IIII },
{"glDrawElements", "(IIILjava/nio/Buffer;)V", (void *) android_glDrawElements__IIILjava_nio_Buffer_2 },
{"glEnable", "(I)V", (void *) android_glEnable__I },
{"glEnableVertexAttribArray", "(I)V", (void *) android_glEnableVertexAttribArray__I },
{"glFinish", "()V", (void *) android_glFinish__ },
{"glFlush", "()V", (void *) android_glFlush__ },
{"glFramebufferRenderbuffer", "(IIII)V", (void *) android_glFramebufferRenderbuffer__IIII },
{"glFramebufferTexture2D", "(IIIII)V", (void *) android_glFramebufferTexture2D__IIIII },
{"glFrontFace", "(I)V", (void *) android_glFrontFace__I },
{"glGenBuffers", "(I[II)V", (void *) android_glGenBuffers__I_3II },
{"glGenBuffers", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenBuffers__ILjava_nio_IntBuffer_2 },
{"glGenerateMipmap", "(I)V", (void *) android_glGenerateMipmap__I },
{"glGenFramebuffers", "(I[II)V", (void *) android_glGenFramebuffers__I_3II },
{"glGenFramebuffers", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenFramebuffers__ILjava_nio_IntBuffer_2 },
{"glGenRenderbuffers", "(I[II)V", (void *) android_glGenRenderbuffers__I_3II },
{"glGenRenderbuffers", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenRenderbuffers__ILjava_nio_IntBuffer_2 },
{"glGenTextures", "(I[II)V", (void *) android_glGenTextures__I_3II },
{"glGenTextures", "(ILjava/nio/IntBuffer;)V", (void *) android_glGenTextures__ILjava_nio_IntBuffer_2 },
{"glGetActiveAttrib", "(III[II[II[II[BI)V", (void *) android_glGetActiveAttrib__III_3II_3II_3II_3BI },
{"glGetActiveAttrib", "(IIILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;B)V", (void *) android_glGetActiveAttrib__IIILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2B },
{"glGetActiveAttrib", "(II[II[II)Ljava/lang/String;", (void *) android_glGetActiveAttrib1 },
{"glGetActiveAttrib", "(IILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)Ljava/lang/String;", (void *) android_glGetActiveAttrib2 },
{"glGetActiveUniform", "(III[II[II[II[BI)V", (void *) android_glGetActiveUniform__III_3II_3II_3II_3BI },
{"glGetActiveUniform", "(II[II[II)Ljava/lang/String;", (void *) android_glGetActiveUniform1 },
{"glGetActiveUniform", "(IIILjava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;B)V", (void *) android_glGetActiveUniform__IIILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2Ljava_nio_IntBuffer_2B },
{"glGetActiveUniform", "(IILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)Ljava/lang/String;", (void *) android_glGetActiveUniform2 },
{"glGetAttachedShaders", "(II[II[II)V", (void *) android_glGetAttachedShaders__II_3II_3II },
{"glGetAttachedShaders", "(IILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)V", (void *) android_glGetAttachedShaders__IILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2 },
{"glGetAttribLocation", "(ILjava/lang/String;)I", (void *) android_glGetAttribLocation__ILjava_lang_String_2 },
{"glGetBooleanv", "(I[ZI)V", (void *) android_glGetBooleanv__I_3ZI },
{"glGetBooleanv", "(ILjava/nio/IntBuffer;)V", (void *) android_glGetBooleanv__ILjava_nio_IntBuffer_2 },
{"glGetBufferParameteriv", "(II[II)V", (void *) android_glGetBufferParameteriv__II_3II },
{"glGetBufferParameteriv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetBufferParameteriv__IILjava_nio_IntBuffer_2 },
{"glGetError", "()I", (void *) android_glGetError__ },
{"glGetFloatv", "(I[FI)V", (void *) android_glGetFloatv__I_3FI },
{"glGetFloatv", "(ILjava/nio/FloatBuffer;)V", (void *) android_glGetFloatv__ILjava_nio_FloatBuffer_2 },
{"glGetFramebufferAttachmentParameteriv", "(III[II)V", (void *) android_glGetFramebufferAttachmentParameteriv__III_3II },
{"glGetFramebufferAttachmentParameteriv", "(IIILjava/nio/IntBuffer;)V", (void *) android_glGetFramebufferAttachmentParameteriv__IIILjava_nio_IntBuffer_2 },
{"glGetIntegerv", "(I[II)V", (void *) android_glGetIntegerv__I_3II },
{"glGetIntegerv", "(ILjava/nio/IntBuffer;)V", (void *) android_glGetIntegerv__ILjava_nio_IntBuffer_2 },
{"glGetProgramiv", "(II[II)V", (void *) android_glGetProgramiv__II_3II },
{"glGetProgramiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetProgramiv__IILjava_nio_IntBuffer_2 },
{"glGetProgramInfoLog", "(I)Ljava/lang/String;", (void *) android_glGetProgramInfoLog },
{"glGetRenderbufferParameteriv", "(II[II)V", (void *) android_glGetRenderbufferParameteriv__II_3II },
{"glGetRenderbufferParameteriv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetRenderbufferParameteriv__IILjava_nio_IntBuffer_2 },
{"glGetShaderiv", "(II[II)V", (void *) android_glGetShaderiv__II_3II },
{"glGetShaderiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetShaderiv__IILjava_nio_IntBuffer_2 },
{"glGetShaderInfoLog", "(I)Ljava/lang/String;", (void *) android_glGetShaderInfoLog },
{"glGetShaderPrecisionFormat", "(II[II[II)V", (void *) android_glGetShaderPrecisionFormat__II_3II_3II },
{"glGetShaderPrecisionFormat", "(IILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)V", (void *) android_glGetShaderPrecisionFormat__IILjava_nio_IntBuffer_2Ljava_nio_IntBuffer_2 },
{"glGetShaderSource", "(II[II[BI)V", (void *) android_glGetShaderSource__II_3II_3BI },
{"glGetShaderSource", "(IILjava/nio/IntBuffer;B)V", (void *) android_glGetShaderSource__IILjava_nio_IntBuffer_2B },
{"glGetShaderSource", "(I)Ljava/lang/String;", (void *) android_glGetShaderSource },
{"glGetString", "(I)Ljava/lang/String;", (void *) android_glGetString },
{"glGetTexParameterfv", "(II[FI)V", (void *) android_glGetTexParameterfv__II_3FI },
{"glGetTexParameterfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glGetTexParameterfv__IILjava_nio_FloatBuffer_2 },
{"glGetTexParameteriv", "(II[II)V", (void *) android_glGetTexParameteriv__II_3II },
{"glGetTexParameteriv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetTexParameteriv__IILjava_nio_IntBuffer_2 },
{"glGetUniformfv", "(II[FI)V", (void *) android_glGetUniformfv__II_3FI },
{"glGetUniformfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glGetUniformfv__IILjava_nio_FloatBuffer_2 },
{"glGetUniformiv", "(II[II)V", (void *) android_glGetUniformiv__II_3II },
{"glGetUniformiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetUniformiv__IILjava_nio_IntBuffer_2 },
{"glGetUniformLocation", "(ILjava/lang/String;)I", (void *) android_glGetUniformLocation__ILjava_lang_String_2 },
{"glGetVertexAttribfv", "(II[FI)V", (void *) android_glGetVertexAttribfv__II_3FI },
{"glGetVertexAttribfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glGetVertexAttribfv__IILjava_nio_FloatBuffer_2 },
{"glGetVertexAttribiv", "(II[II)V", (void *) android_glGetVertexAttribiv__II_3II },
{"glGetVertexAttribiv", "(IILjava/nio/IntBuffer;)V", (void *) android_glGetVertexAttribiv__IILjava_nio_IntBuffer_2 },
{"glHint", "(II)V", (void *) android_glHint__II },
{"glIsBuffer", "(I)Z", (void *) android_glIsBuffer__I },
{"glIsEnabled", "(I)Z", (void *) android_glIsEnabled__I },
{"glIsFramebuffer", "(I)Z", (void *) android_glIsFramebuffer__I },
{"glIsProgram", "(I)Z", (void *) android_glIsProgram__I },
{"glIsRenderbuffer", "(I)Z", (void *) android_glIsRenderbuffer__I },
{"glIsShader", "(I)Z", (void *) android_glIsShader__I },
{"glIsTexture", "(I)Z", (void *) android_glIsTexture__I },
{"glLineWidth", "(F)V", (void *) android_glLineWidth__F },
{"glLinkProgram", "(I)V", (void *) android_glLinkProgram__I },
{"glPixelStorei", "(II)V", (void *) android_glPixelStorei__II },
{"glPolygonOffset", "(FF)V", (void *) android_glPolygonOffset__FF },
{"glReadPixels", "(IIIIIILjava/nio/Buffer;)V", (void *) android_glReadPixels__IIIIIILjava_nio_Buffer_2 },
{"glReleaseShaderCompiler", "()V", (void *) android_glReleaseShaderCompiler__ },
{"glRenderbufferStorage", "(IIII)V", (void *) android_glRenderbufferStorage__IIII },
{"glSampleCoverage", "(FZ)V", (void *) android_glSampleCoverage__FZ },
{"glScissor", "(IIII)V", (void *) android_glScissor__IIII },
{"glShaderBinary", "(I[IIILjava/nio/Buffer;I)V", (void *) android_glShaderBinary__I_3IIILjava_nio_Buffer_2I },
{"glShaderBinary", "(ILjava/nio/IntBuffer;ILjava/nio/Buffer;I)V", (void *) android_glShaderBinary__ILjava_nio_IntBuffer_2ILjava_nio_Buffer_2I },
{"glShaderSource", "(ILjava/lang/String;)V", (void *) android_glShaderSource },
{"glStencilFunc", "(III)V", (void *) android_glStencilFunc__III },
{"glStencilFuncSeparate", "(IIII)V", (void *) android_glStencilFuncSeparate__IIII },
{"glStencilMask", "(I)V", (void *) android_glStencilMask__I },
{"glStencilMaskSeparate", "(II)V", (void *) android_glStencilMaskSeparate__II },
{"glStencilOp", "(III)V", (void *) android_glStencilOp__III },
{"glStencilOpSeparate", "(IIII)V", (void *) android_glStencilOpSeparate__IIII },
{"glTexImage2D", "(IIIIIIIILjava/nio/Buffer;)V", (void *) android_glTexImage2D__IIIIIIIILjava_nio_Buffer_2 },
{"glTexParameterf", "(IIF)V", (void *) android_glTexParameterf__IIF },
{"glTexParameterfv", "(II[FI)V", (void *) android_glTexParameterfv__II_3FI },
{"glTexParameterfv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glTexParameterfv__IILjava_nio_FloatBuffer_2 },
{"glTexParameteri", "(III)V", (void *) android_glTexParameteri__III },
{"glTexParameteriv", "(II[II)V", (void *) android_glTexParameteriv__II_3II },
{"glTexParameteriv", "(IILjava/nio/IntBuffer;)V", (void *) android_glTexParameteriv__IILjava_nio_IntBuffer_2 },
{"glTexSubImage2D", "(IIIIIIIILjava/nio/Buffer;)V", (void *) android_glTexSubImage2D__IIIIIIIILjava_nio_Buffer_2 },
{"glUniform1f", "(IF)V", (void *) android_glUniform1f__IF },
{"glUniform1fv", "(II[FI)V", (void *) android_glUniform1fv__II_3FI },
{"glUniform1fv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glUniform1fv__IILjava_nio_FloatBuffer_2 },
{"glUniform1i", "(II)V", (void *) android_glUniform1i__II },
{"glUniform1iv", "(II[II)V", (void *) android_glUniform1iv__II_3II },
{"glUniform1iv", "(IILjava/nio/IntBuffer;)V", (void *) android_glUniform1iv__IILjava_nio_IntBuffer_2 },
{"glUniform2f", "(IFF)V", (void *) android_glUniform2f__IFF },
{"glUniform2fv", "(II[FI)V", (void *) android_glUniform2fv__II_3FI },
{"glUniform2fv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glUniform2fv__IILjava_nio_FloatBuffer_2 },
{"glUniform2i", "(III)V", (void *) android_glUniform2i__III },
{"glUniform2iv", "(II[II)V", (void *) android_glUniform2iv__II_3II },
{"glUniform2iv", "(IILjava/nio/IntBuffer;)V", (void *) android_glUniform2iv__IILjava_nio_IntBuffer_2 },
{"glUniform3f", "(IFFF)V", (void *) android_glUniform3f__IFFF },
{"glUniform3fv", "(II[FI)V", (void *) android_glUniform3fv__II_3FI },
{"glUniform3fv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glUniform3fv__IILjava_nio_FloatBuffer_2 },
{"glUniform3i", "(IIII)V", (void *) android_glUniform3i__IIII },
{"glUniform3iv", "(II[II)V", (void *) android_glUniform3iv__II_3II },
{"glUniform3iv", "(IILjava/nio/IntBuffer;)V", (void *) android_glUniform3iv__IILjava_nio_IntBuffer_2 },
{"glUniform4f", "(IFFFF)V", (void *) android_glUniform4f__IFFFF },
{"glUniform4fv", "(II[FI)V", (void *) android_glUniform4fv__II_3FI },
{"glUniform4fv", "(IILjava/nio/FloatBuffer;)V", (void *) android_glUniform4fv__IILjava_nio_FloatBuffer_2 },
{"glUniform4i", "(IIIII)V", (void *) android_glUniform4i__IIIII },
{"glUniform4iv", "(II[II)V", (void *) android_glUniform4iv__II_3II },
{"glUniform4iv", "(IILjava/nio/IntBuffer;)V", (void *) android_glUniform4iv__IILjava_nio_IntBuffer_2 },
{"glUniformMatrix2fv", "(IIZ[FI)V", (void *) android_glUniformMatrix2fv__IIZ_3FI },
{"glUniformMatrix2fv", "(IIZLjava/nio/FloatBuffer;)V", (void *) android_glUniformMatrix2fv__IIZLjava_nio_FloatBuffer_2 },
{"glUniformMatrix3fv", "(IIZ[FI)V", (void *) android_glUniformMatrix3fv__IIZ_3FI },
{"glUniformMatrix3fv", "(IIZLjava/nio/FloatBuffer;)V", (void *) android_glUniformMatrix3fv__IIZLjava_nio_FloatBuffer_2 },
{"glUniformMatrix4fv", "(IIZ[FI)V", (void *) android_glUniformMatrix4fv__IIZ_3FI },
{"glUniformMatrix4fv", "(IIZLjava/nio/FloatBuffer;)V", (void *) android_glUniformMatrix4fv__IIZLjava_nio_FloatBuffer_2 },
{"glUseProgram", "(I)V", (void *) android_glUseProgram__I },
{"glValidateProgram", "(I)V", (void *) android_glValidateProgram__I },
{"glVertexAttrib1f", "(IF)V", (void *) android_glVertexAttrib1f__IF },
{"glVertexAttrib1fv", "(I[FI)V", (void *) android_glVertexAttrib1fv__I_3FI },
{"glVertexAttrib1fv", "(ILjava/nio/FloatBuffer;)V", (void *) android_glVertexAttrib1fv__ILjava_nio_FloatBuffer_2 },
{"glVertexAttrib2f", "(IFF)V", (void *) android_glVertexAttrib2f__IFF },
{"glVertexAttrib2fv", "(I[FI)V", (void *) android_glVertexAttrib2fv__I_3FI },
{"glVertexAttrib2fv", "(ILjava/nio/FloatBuffer;)V", (void *) android_glVertexAttrib2fv__ILjava_nio_FloatBuffer_2 },
{"glVertexAttrib3f", "(IFFF)V", (void *) android_glVertexAttrib3f__IFFF },
{"glVertexAttrib3fv", "(I[FI)V", (void *) android_glVertexAttrib3fv__I_3FI },
{"glVertexAttrib3fv", "(ILjava/nio/FloatBuffer;)V", (void *) android_glVertexAttrib3fv__ILjava_nio_FloatBuffer_2 },
{"glVertexAttrib4f", "(IFFFF)V", (void *) android_glVertexAttrib4f__IFFFF },
{"glVertexAttrib4fv", "(I[FI)V", (void *) android_glVertexAttrib4fv__I_3FI },
{"glVertexAttrib4fv", "(ILjava/nio/FloatBuffer;)V", (void *) android_glVertexAttrib4fv__ILjava_nio_FloatBuffer_2 },
{"glVertexAttribPointer", "(IIIZII)V", (void *) android_glVertexAttribPointer__IIIZII },
{"glVertexAttribPointerBounds", "(IIIZILjava/nio/Buffer;I)V", (void *) android_glVertexAttribPointerBounds__IIIZILjava_nio_Buffer_2I },
{"glViewport", "(IIII)V", (void *) android_glViewport__IIII },
};

int register_android_opengl_jni_GLES20(JNIEnv *_env)
{
    int err;
    err = android::AndroidRuntime::registerNativeMethods(_env, classPathName, methods, NELEM(methods));
    return err;
}

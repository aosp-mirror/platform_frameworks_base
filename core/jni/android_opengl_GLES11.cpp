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

#include "jni.h"
#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>

#include <assert.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

/* special calls implemented in Android's GLES wrapper used to more
 * efficiently bound-check passed arrays */
extern "C" {
GL_API void GL_APIENTRY glPointSizePointerOESBounds(GLenum type, GLsizei stride,
        const GLvoid *ptr, GLsizei count);
}

static int initialized = 0;

static jclass nioAccessClass;
static jclass bufferClass;
static jmethodID getBasePointerID;
static jmethodID getBaseArrayID;
static jmethodID getBaseArrayOffsetID;
static jfieldID positionID;
static jfieldID limitID;
static jfieldID elementSizeShiftID;

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
    jarray _array = (jarray) 0;
    jint _remaining;
    GLvoid *data = (GLvoid *) 0;

    if (data_buf) {
        data = (GLvoid *)getPointer(_env, data_buf, &_array, &_remaining);
        if (_remaining < size) {
            jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < size");
            goto exit;
        }
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
}

/* void glBufferSubData ( GLenum target, GLintptr offset, GLsizeiptr size, const GLvoid *data ) */
static void
android_glBufferSubData__IIILjava_nio_Buffer_2
  (JNIEnv *_env, jobject _this, jint target, jint offset, jint size, jobject data_buf) {
    jarray _array = (jarray) 0;
    jint _remaining;
    GLvoid *data = (GLvoid *) 0;

    data = (GLvoid *)getPointer(_env, data_buf, &_array, &_remaining);
    if (_remaining < size) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < size");
        goto exit;
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
}

/* void glClipPlanef ( GLenum plane, const GLfloat *equation ) */
static void
android_glClipPlanef__I_3FI
  (JNIEnv *_env, jobject _this, jint plane, jfloatArray equation_ref, jint offset) {
    GLfloat *equation_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *equation = (GLfloat *) 0;

    if (!equation_ref) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "equation == null");
        goto exit;
    }
    if (offset < 0) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(equation_ref) - offset;
    equation_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(equation_ref, (jboolean *)0);
    equation = equation_base + offset;

    glClipPlanef(
        (GLenum)plane,
        (GLfloat *)equation
    );

exit:
    if (equation_base) {
        _env->ReleasePrimitiveArrayCritical(equation_ref, equation_base,
            JNI_ABORT);
    }
}

/* void glClipPlanef ( GLenum plane, const GLfloat *equation ) */
static void
android_glClipPlanef__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint plane, jobject equation_buf) {
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfloat *equation = (GLfloat *) 0;

    equation = (GLfloat *)getPointer(_env, equation_buf, &_array, &_remaining);
    glClipPlanef(
        (GLenum)plane,
        (GLfloat *)equation
    );
    if (_array) {
        releasePointer(_env, _array, equation, JNI_FALSE);
    }
}

/* void glClipPlanex ( GLenum plane, const GLfixed *equation ) */
static void
android_glClipPlanex__I_3II
  (JNIEnv *_env, jobject _this, jint plane, jintArray equation_ref, jint offset) {
    GLfixed *equation_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *equation = (GLfixed *) 0;

    if (!equation_ref) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "equation == null");
        goto exit;
    }
    if (offset < 0) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(equation_ref) - offset;
    equation_base = (GLfixed *)
        _env->GetPrimitiveArrayCritical(equation_ref, (jboolean *)0);
    equation = equation_base + offset;

    glClipPlanex(
        (GLenum)plane,
        (GLfixed *)equation
    );

exit:
    if (equation_base) {
        _env->ReleasePrimitiveArrayCritical(equation_ref, equation_base,
            JNI_ABORT);
    }
}

/* void glClipPlanex ( GLenum plane, const GLfixed *equation ) */
static void
android_glClipPlanex__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint plane, jobject equation_buf) {
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfixed *equation = (GLfixed *) 0;

    equation = (GLfixed *)getPointer(_env, equation_buf, &_array, &_remaining);
    glClipPlanex(
        (GLenum)plane,
        (GLfixed *)equation
    );
    if (_array) {
        releasePointer(_env, _array, equation, JNI_FALSE);
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
        (const GLvoid *)offset
    );
}

/* void glDeleteBuffers ( GLsizei n, const GLuint *buffers ) */
static void
android_glDeleteBuffers__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray buffers_ref, jint offset) {
    GLuint *buffers_base = (GLuint *) 0;
    jint _remaining;
    GLuint *buffers = (GLuint *) 0;

    if (!buffers_ref) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "buffers == null");
        goto exit;
    }
    if (offset < 0) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(buffers_ref) - offset;
    if (_remaining < n) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < n");
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
}

/* void glDeleteBuffers ( GLsizei n, const GLuint *buffers ) */
static void
android_glDeleteBuffers__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject buffers_buf) {
    jarray _array = (jarray) 0;
    jint _remaining;
    GLuint *buffers = (GLuint *) 0;

    buffers = (GLuint *)getPointer(_env, buffers_buf, &_array, &_remaining);
    if (_remaining < n) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < n");
        goto exit;
    }
    glDeleteBuffers(
        (GLsizei)n,
        (GLuint *)buffers
    );

exit:
    if (_array) {
        releasePointer(_env, _array, buffers, JNI_FALSE);
    }
}

/* void glDrawElements ( GLenum mode, GLsizei count, GLenum type, GLint offset ) */
static void
android_glDrawElements__IIII
  (JNIEnv *_env, jobject _this, jint mode, jint count, jint type, jint offset) {
    glDrawElements(
        (GLenum)mode,
        (GLsizei)count,
        (GLenum)type,
        (const GLvoid *)offset
    );
}

/* void glGenBuffers ( GLsizei n, GLuint *buffers ) */
static void
android_glGenBuffers__I_3II
  (JNIEnv *_env, jobject _this, jint n, jintArray buffers_ref, jint offset) {
    jint _exception = 0;
    GLuint *buffers_base = (GLuint *) 0;
    jint _remaining;
    GLuint *buffers = (GLuint *) 0;

    if (!buffers_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "buffers == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(buffers_ref) - offset;
    if (_remaining < n) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < n");
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
}

/* void glGenBuffers ( GLsizei n, GLuint *buffers ) */
static void
android_glGenBuffers__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint n, jobject buffers_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLuint *buffers = (GLuint *) 0;

    buffers = (GLuint *)getPointer(_env, buffers_buf, &_array, &_remaining);
    if (_remaining < n) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < n");
        goto exit;
    }
    glGenBuffers(
        (GLsizei)n,
        (GLuint *)buffers
    );

exit:
    if (_array) {
        releasePointer(_env, _array, buffers, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetBooleanv ( GLenum pname, GLboolean *params ) */
static void
android_glGetBooleanv__I_3ZI
  (JNIEnv *_env, jobject _this, jint pname, jbooleanArray params_ref, jint offset) {
    jint _exception = 0;
    GLboolean *params_base = (GLboolean *) 0;
    jint _remaining;
    GLboolean *params = (GLboolean *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    params_base = (GLboolean *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetBooleanv(
        (GLenum)pname,
        (GLboolean *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetBooleanv ( GLenum pname, GLboolean *params ) */
static void
android_glGetBooleanv__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLboolean *params = (GLboolean *) 0;

    params = (GLboolean *)getPointer(_env, params_buf, &_array, &_remaining);
    glGetBooleanv(
        (GLenum)pname,
        (GLboolean *)params
    );
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetBufferParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetBufferParameteriv__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    GLint *params_base = (GLint *) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    if (_remaining < 1) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < 1");
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
}

/* void glGetBufferParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetBufferParameteriv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining);
    if (_remaining < 1) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < 1");
        goto exit;
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
}

/* void glGetClipPlanef ( GLenum pname, GLfloat *eqn ) */
static void
android_glGetClipPlanef__I_3FI
  (JNIEnv *_env, jobject _this, jint pname, jfloatArray eqn_ref, jint offset) {
    jint _exception = 0;
    GLfloat *eqn_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *eqn = (GLfloat *) 0;

    if (!eqn_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "eqn == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(eqn_ref) - offset;
    eqn_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(eqn_ref, (jboolean *)0);
    eqn = eqn_base + offset;

    glGetClipPlanef(
        (GLenum)pname,
        (GLfloat *)eqn
    );

exit:
    if (eqn_base) {
        _env->ReleasePrimitiveArrayCritical(eqn_ref, eqn_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetClipPlanef ( GLenum pname, GLfloat *eqn ) */
static void
android_glGetClipPlanef__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject eqn_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfloat *eqn = (GLfloat *) 0;

    eqn = (GLfloat *)getPointer(_env, eqn_buf, &_array, &_remaining);
    glGetClipPlanef(
        (GLenum)pname,
        (GLfloat *)eqn
    );
    if (_array) {
        releasePointer(_env, _array, eqn, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetClipPlanex ( GLenum pname, GLfixed *eqn ) */
static void
android_glGetClipPlanex__I_3II
  (JNIEnv *_env, jobject _this, jint pname, jintArray eqn_ref, jint offset) {
    jint _exception = 0;
    GLfixed *eqn_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *eqn = (GLfixed *) 0;

    if (!eqn_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "eqn == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(eqn_ref) - offset;
    eqn_base = (GLfixed *)
        _env->GetPrimitiveArrayCritical(eqn_ref, (jboolean *)0);
    eqn = eqn_base + offset;

    glGetClipPlanex(
        (GLenum)pname,
        (GLfixed *)eqn
    );

exit:
    if (eqn_base) {
        _env->ReleasePrimitiveArrayCritical(eqn_ref, eqn_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetClipPlanex ( GLenum pname, GLfixed *eqn ) */
static void
android_glGetClipPlanex__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject eqn_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfixed *eqn = (GLfixed *) 0;

    eqn = (GLfixed *)getPointer(_env, eqn_buf, &_array, &_remaining);
    glGetClipPlanex(
        (GLenum)pname,
        (GLfixed *)eqn
    );
    if (_array) {
        releasePointer(_env, _array, eqn, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetFixedv ( GLenum pname, GLfixed *params ) */
static void
android_glGetFixedv__I_3II
  (JNIEnv *_env, jobject _this, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    params_base = (GLfixed *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetFixedv(
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetFixedv ( GLenum pname, GLfixed *params ) */
static void
android_glGetFixedv__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    params = (GLfixed *)getPointer(_env, params_buf, &_array, &_remaining);
    glGetFixedv(
        (GLenum)pname,
        (GLfixed *)params
    );
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetFloatv ( GLenum pname, GLfloat *params ) */
static void
android_glGetFloatv__I_3FI
  (JNIEnv *_env, jobject _this, jint pname, jfloatArray params_ref, jint offset) {
    jint _exception = 0;
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    params_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetFloatv(
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetFloatv ( GLenum pname, GLfloat *params ) */
static void
android_glGetFloatv__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    params = (GLfloat *)getPointer(_env, params_buf, &_array, &_remaining);
    glGetFloatv(
        (GLenum)pname,
        (GLfloat *)params
    );
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetLightfv ( GLenum light, GLenum pname, GLfloat *params ) */
static void
android_glGetLightfv__II_3FI
  (JNIEnv *_env, jobject _this, jint light, jint pname, jfloatArray params_ref, jint offset) {
    jint _exception = 0;
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    int _needed;
    switch (pname) {
#if defined(GL_SPOT_EXPONENT)
        case GL_SPOT_EXPONENT:
#endif // defined(GL_SPOT_EXPONENT)
#if defined(GL_SPOT_CUTOFF)
        case GL_SPOT_CUTOFF:
#endif // defined(GL_SPOT_CUTOFF)
#if defined(GL_CONSTANT_ATTENUATION)
        case GL_CONSTANT_ATTENUATION:
#endif // defined(GL_CONSTANT_ATTENUATION)
#if defined(GL_LINEAR_ATTENUATION)
        case GL_LINEAR_ATTENUATION:
#endif // defined(GL_LINEAR_ATTENUATION)
#if defined(GL_QUADRATIC_ATTENUATION)
        case GL_QUADRATIC_ATTENUATION:
#endif // defined(GL_QUADRATIC_ATTENUATION)
            _needed = 1;
            break;
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
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < needed");
        goto exit;
    }
    params_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetLightfv(
        (GLenum)light,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetLightfv ( GLenum light, GLenum pname, GLfloat *params ) */
static void
android_glGetLightfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint light, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    params = (GLfloat *)getPointer(_env, params_buf, &_array, &_remaining);
    int _needed;
    switch (pname) {
#if defined(GL_SPOT_EXPONENT)
        case GL_SPOT_EXPONENT:
#endif // defined(GL_SPOT_EXPONENT)
#if defined(GL_SPOT_CUTOFF)
        case GL_SPOT_CUTOFF:
#endif // defined(GL_SPOT_CUTOFF)
#if defined(GL_CONSTANT_ATTENUATION)
        case GL_CONSTANT_ATTENUATION:
#endif // defined(GL_CONSTANT_ATTENUATION)
#if defined(GL_LINEAR_ATTENUATION)
        case GL_LINEAR_ATTENUATION:
#endif // defined(GL_LINEAR_ATTENUATION)
#if defined(GL_QUADRATIC_ATTENUATION)
        case GL_QUADRATIC_ATTENUATION:
#endif // defined(GL_QUADRATIC_ATTENUATION)
            _needed = 1;
            break;
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
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < needed");
        goto exit;
    }
    glGetLightfv(
        (GLenum)light,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetLightxv ( GLenum light, GLenum pname, GLfixed *params ) */
static void
android_glGetLightxv__II_3II
  (JNIEnv *_env, jobject _this, jint light, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    int _needed;
    switch (pname) {
#if defined(GL_SPOT_EXPONENT)
        case GL_SPOT_EXPONENT:
#endif // defined(GL_SPOT_EXPONENT)
#if defined(GL_SPOT_CUTOFF)
        case GL_SPOT_CUTOFF:
#endif // defined(GL_SPOT_CUTOFF)
#if defined(GL_CONSTANT_ATTENUATION)
        case GL_CONSTANT_ATTENUATION:
#endif // defined(GL_CONSTANT_ATTENUATION)
#if defined(GL_LINEAR_ATTENUATION)
        case GL_LINEAR_ATTENUATION:
#endif // defined(GL_LINEAR_ATTENUATION)
#if defined(GL_QUADRATIC_ATTENUATION)
        case GL_QUADRATIC_ATTENUATION:
#endif // defined(GL_QUADRATIC_ATTENUATION)
            _needed = 1;
            break;
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
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < needed");
        goto exit;
    }
    params_base = (GLfixed *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetLightxv(
        (GLenum)light,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetLightxv ( GLenum light, GLenum pname, GLfixed *params ) */
static void
android_glGetLightxv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint light, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    params = (GLfixed *)getPointer(_env, params_buf, &_array, &_remaining);
    int _needed;
    switch (pname) {
#if defined(GL_SPOT_EXPONENT)
        case GL_SPOT_EXPONENT:
#endif // defined(GL_SPOT_EXPONENT)
#if defined(GL_SPOT_CUTOFF)
        case GL_SPOT_CUTOFF:
#endif // defined(GL_SPOT_CUTOFF)
#if defined(GL_CONSTANT_ATTENUATION)
        case GL_CONSTANT_ATTENUATION:
#endif // defined(GL_CONSTANT_ATTENUATION)
#if defined(GL_LINEAR_ATTENUATION)
        case GL_LINEAR_ATTENUATION:
#endif // defined(GL_LINEAR_ATTENUATION)
#if defined(GL_QUADRATIC_ATTENUATION)
        case GL_QUADRATIC_ATTENUATION:
#endif // defined(GL_QUADRATIC_ATTENUATION)
            _needed = 1;
            break;
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
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < needed");
        goto exit;
    }
    glGetLightxv(
        (GLenum)light,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetMaterialfv ( GLenum face, GLenum pname, GLfloat *params ) */
static void
android_glGetMaterialfv__II_3FI
  (JNIEnv *_env, jobject _this, jint face, jint pname, jfloatArray params_ref, jint offset) {
    jint _exception = 0;
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    int _needed;
    switch (pname) {
#if defined(GL_SHININESS)
        case GL_SHININESS:
#endif // defined(GL_SHININESS)
            _needed = 1;
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
#if defined(GL_AMBIENT_AND_DIFFUSE)
        case GL_AMBIENT_AND_DIFFUSE:
#endif // defined(GL_AMBIENT_AND_DIFFUSE)
            _needed = 4;
            break;
        default:
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < needed");
        goto exit;
    }
    params_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetMaterialfv(
        (GLenum)face,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetMaterialfv ( GLenum face, GLenum pname, GLfloat *params ) */
static void
android_glGetMaterialfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint face, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    params = (GLfloat *)getPointer(_env, params_buf, &_array, &_remaining);
    int _needed;
    switch (pname) {
#if defined(GL_SHININESS)
        case GL_SHININESS:
#endif // defined(GL_SHININESS)
            _needed = 1;
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
#if defined(GL_AMBIENT_AND_DIFFUSE)
        case GL_AMBIENT_AND_DIFFUSE:
#endif // defined(GL_AMBIENT_AND_DIFFUSE)
            _needed = 4;
            break;
        default:
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < needed");
        goto exit;
    }
    glGetMaterialfv(
        (GLenum)face,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetMaterialxv ( GLenum face, GLenum pname, GLfixed *params ) */
static void
android_glGetMaterialxv__II_3II
  (JNIEnv *_env, jobject _this, jint face, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    int _needed;
    switch (pname) {
#if defined(GL_SHININESS)
        case GL_SHININESS:
#endif // defined(GL_SHININESS)
            _needed = 1;
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
#if defined(GL_AMBIENT_AND_DIFFUSE)
        case GL_AMBIENT_AND_DIFFUSE:
#endif // defined(GL_AMBIENT_AND_DIFFUSE)
            _needed = 4;
            break;
        default:
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < needed");
        goto exit;
    }
    params_base = (GLfixed *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetMaterialxv(
        (GLenum)face,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetMaterialxv ( GLenum face, GLenum pname, GLfixed *params ) */
static void
android_glGetMaterialxv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint face, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    params = (GLfixed *)getPointer(_env, params_buf, &_array, &_remaining);
    int _needed;
    switch (pname) {
#if defined(GL_SHININESS)
        case GL_SHININESS:
#endif // defined(GL_SHININESS)
            _needed = 1;
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
#if defined(GL_AMBIENT_AND_DIFFUSE)
        case GL_AMBIENT_AND_DIFFUSE:
#endif // defined(GL_AMBIENT_AND_DIFFUSE)
            _needed = 4;
            break;
        default:
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < needed");
        goto exit;
    }
    glGetMaterialxv(
        (GLenum)face,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetTexEnvfv ( GLenum env, GLenum pname, GLfloat *params ) */
static void
android_glGetTexEnvfv__II_3FI
  (JNIEnv *_env, jobject _this, jint env, jint pname, jfloatArray params_ref, jint offset) {
    jint _exception = 0;
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_MODE)
        case GL_TEXTURE_ENV_MODE:
#endif // defined(GL_TEXTURE_ENV_MODE)
#if defined(GL_COMBINE_RGB)
        case GL_COMBINE_RGB:
#endif // defined(GL_COMBINE_RGB)
#if defined(GL_COMBINE_ALPHA)
        case GL_COMBINE_ALPHA:
#endif // defined(GL_COMBINE_ALPHA)
            _needed = 1;
            break;
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < needed");
        goto exit;
    }
    params_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexEnvfv(
        (GLenum)env,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetTexEnvfv ( GLenum env, GLenum pname, GLfloat *params ) */
static void
android_glGetTexEnvfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint env, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    params = (GLfloat *)getPointer(_env, params_buf, &_array, &_remaining);
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_MODE)
        case GL_TEXTURE_ENV_MODE:
#endif // defined(GL_TEXTURE_ENV_MODE)
#if defined(GL_COMBINE_RGB)
        case GL_COMBINE_RGB:
#endif // defined(GL_COMBINE_RGB)
#if defined(GL_COMBINE_ALPHA)
        case GL_COMBINE_ALPHA:
#endif // defined(GL_COMBINE_ALPHA)
            _needed = 1;
            break;
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < needed");
        goto exit;
    }
    glGetTexEnvfv(
        (GLenum)env,
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetTexEnviv ( GLenum env, GLenum pname, GLint *params ) */
static void
android_glGetTexEnviv__II_3II
  (JNIEnv *_env, jobject _this, jint env, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    GLint *params_base = (GLint *) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_MODE)
        case GL_TEXTURE_ENV_MODE:
#endif // defined(GL_TEXTURE_ENV_MODE)
#if defined(GL_COMBINE_RGB)
        case GL_COMBINE_RGB:
#endif // defined(GL_COMBINE_RGB)
#if defined(GL_COMBINE_ALPHA)
        case GL_COMBINE_ALPHA:
#endif // defined(GL_COMBINE_ALPHA)
            _needed = 1;
            break;
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < needed");
        goto exit;
    }
    params_base = (GLint *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexEnviv(
        (GLenum)env,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetTexEnviv ( GLenum env, GLenum pname, GLint *params ) */
static void
android_glGetTexEnviv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint env, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining);
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_MODE)
        case GL_TEXTURE_ENV_MODE:
#endif // defined(GL_TEXTURE_ENV_MODE)
#if defined(GL_COMBINE_RGB)
        case GL_COMBINE_RGB:
#endif // defined(GL_COMBINE_RGB)
#if defined(GL_COMBINE_ALPHA)
        case GL_COMBINE_ALPHA:
#endif // defined(GL_COMBINE_ALPHA)
            _needed = 1;
            break;
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < needed");
        goto exit;
    }
    glGetTexEnviv(
        (GLenum)env,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetTexEnvxv ( GLenum env, GLenum pname, GLfixed *params ) */
static void
android_glGetTexEnvxv__II_3II
  (JNIEnv *_env, jobject _this, jint env, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_MODE)
        case GL_TEXTURE_ENV_MODE:
#endif // defined(GL_TEXTURE_ENV_MODE)
#if defined(GL_COMBINE_RGB)
        case GL_COMBINE_RGB:
#endif // defined(GL_COMBINE_RGB)
#if defined(GL_COMBINE_ALPHA)
        case GL_COMBINE_ALPHA:
#endif // defined(GL_COMBINE_ALPHA)
            _needed = 1;
            break;
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < needed");
        goto exit;
    }
    params_base = (GLfixed *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexEnvxv(
        (GLenum)env,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetTexEnvxv ( GLenum env, GLenum pname, GLfixed *params ) */
static void
android_glGetTexEnvxv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint env, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    params = (GLfixed *)getPointer(_env, params_buf, &_array, &_remaining);
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_MODE)
        case GL_TEXTURE_ENV_MODE:
#endif // defined(GL_TEXTURE_ENV_MODE)
#if defined(GL_COMBINE_RGB)
        case GL_COMBINE_RGB:
#endif // defined(GL_COMBINE_RGB)
#if defined(GL_COMBINE_ALPHA)
        case GL_COMBINE_ALPHA:
#endif // defined(GL_COMBINE_ALPHA)
            _needed = 1;
            break;
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < needed");
        goto exit;
    }
    glGetTexEnvxv(
        (GLenum)env,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
    }
}

/* void glGetTexParameterfv ( GLenum target, GLenum pname, GLfloat *params ) */
static void
android_glGetTexParameterfv__II_3FI
  (JNIEnv *_env, jobject _this, jint target, jint pname, jfloatArray params_ref, jint offset) {
    jint _exception = 0;
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    if (_remaining < 1) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < 1");
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
}

/* void glGetTexParameterfv ( GLenum target, GLenum pname, GLfloat *params ) */
static void
android_glGetTexParameterfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    params = (GLfloat *)getPointer(_env, params_buf, &_array, &_remaining);
    if (_remaining < 1) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < 1");
        goto exit;
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
}

/* void glGetTexParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetTexParameteriv__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    GLint *params_base = (GLint *) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    if (_remaining < 1) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < 1");
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
}

/* void glGetTexParameteriv ( GLenum target, GLenum pname, GLint *params ) */
static void
android_glGetTexParameteriv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining);
    if (_remaining < 1) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < 1");
        goto exit;
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
}

/* void glGetTexParameterxv ( GLenum target, GLenum pname, GLfixed *params ) */
static void
android_glGetTexParameterxv__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint pname, jintArray params_ref, jint offset) {
    jint _exception = 0;
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_ref) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    if (_remaining < 1) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < 1");
        goto exit;
    }
    params_base = (GLfixed *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glGetTexParameterxv(
        (GLenum)target,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            _exception ? JNI_ABORT: 0);
    }
}

/* void glGetTexParameterxv ( GLenum target, GLenum pname, GLfixed *params ) */
static void
android_glGetTexParameterxv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jint _exception = 0;
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    params = (GLfixed *)getPointer(_env, params_buf, &_array, &_remaining);
    if (_remaining < 1) {
        _exception = 1;
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < 1");
        goto exit;
    }
    glGetTexParameterxv(
        (GLenum)target,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, _exception ? JNI_FALSE : JNI_TRUE);
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
    return _returnValue;
}

/* GLboolean glIsEnabled ( GLenum cap ) */
static jboolean
android_glIsEnabled__I
  (JNIEnv *_env, jobject _this, jint cap) {
    GLboolean _returnValue;
    _returnValue = glIsEnabled(
        (GLenum)cap
    );
    return _returnValue;
}

/* GLboolean glIsTexture ( GLuint texture ) */
static jboolean
android_glIsTexture__I
  (JNIEnv *_env, jobject _this, jint texture) {
    GLboolean _returnValue;
    _returnValue = glIsTexture(
        (GLuint)texture
    );
    return _returnValue;
}

/* void glNormalPointer ( GLenum type, GLsizei stride, GLint offset ) */
static void
android_glNormalPointer__III
  (JNIEnv *_env, jobject _this, jint type, jint stride, jint offset) {
    glNormalPointer(
        (GLenum)type,
        (GLsizei)stride,
        (const GLvoid *)offset
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
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_ref) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    if (_remaining < 1) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < 1");
        goto exit;
    }
    params_base = (GLfloat *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glPointParameterfv(
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            JNI_ABORT);
    }
}

/* void glPointParameterfv ( GLenum pname, const GLfloat *params ) */
static void
android_glPointParameterfv__ILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    params = (GLfloat *)getPointer(_env, params_buf, &_array, &_remaining);
    if (_remaining < 1) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < 1");
        goto exit;
    }
    glPointParameterfv(
        (GLenum)pname,
        (GLfloat *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, JNI_FALSE);
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
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_ref) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    if (_remaining < 1) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < 1");
        goto exit;
    }
    params_base = (GLfixed *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glPointParameterxv(
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            JNI_ABORT);
    }
}

/* void glPointParameterxv ( GLenum pname, const GLfixed *params ) */
static void
android_glPointParameterxv__ILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    params = (GLfixed *)getPointer(_env, params_buf, &_array, &_remaining);
    if (_remaining < 1) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < 1");
        goto exit;
    }
    glPointParameterxv(
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, JNI_FALSE);
    }
}

/* void glPointSizePointerOES ( GLenum type, GLsizei stride, const GLvoid *pointer ) */
static void
android_glPointSizePointerOESBounds__IILjava_nio_Buffer_2I
  (JNIEnv *_env, jobject _this, jint type, jint stride, jobject pointer_buf, jint remaining) {
    jarray _array = (jarray) 0;
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
}

/* void glTexCoordPointer ( GLint size, GLenum type, GLsizei stride, GLint offset ) */
static void
android_glTexCoordPointer__IIII
  (JNIEnv *_env, jobject _this, jint size, jint type, jint stride, jint offset) {
    glTexCoordPointer(
        (GLint)size,
        (GLenum)type,
        (GLsizei)stride,
        (const GLvoid *)offset
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
    GLint *params_base = (GLint *) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    if (!params_ref) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_MODE)
        case GL_TEXTURE_ENV_MODE:
#endif // defined(GL_TEXTURE_ENV_MODE)
#if defined(GL_COMBINE_RGB)
        case GL_COMBINE_RGB:
#endif // defined(GL_COMBINE_RGB)
#if defined(GL_COMBINE_ALPHA)
        case GL_COMBINE_ALPHA:
#endif // defined(GL_COMBINE_ALPHA)
            _needed = 1;
            break;
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < needed");
        goto exit;
    }
    params_base = (GLint *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glTexEnviv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            JNI_ABORT);
    }
}

/* void glTexEnviv ( GLenum target, GLenum pname, const GLint *params ) */
static void
android_glTexEnviv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining);
    int _needed;
    switch (pname) {
#if defined(GL_TEXTURE_ENV_MODE)
        case GL_TEXTURE_ENV_MODE:
#endif // defined(GL_TEXTURE_ENV_MODE)
#if defined(GL_COMBINE_RGB)
        case GL_COMBINE_RGB:
#endif // defined(GL_COMBINE_RGB)
#if defined(GL_COMBINE_ALPHA)
        case GL_COMBINE_ALPHA:
#endif // defined(GL_COMBINE_ALPHA)
            _needed = 1;
            break;
#if defined(GL_TEXTURE_ENV_COLOR)
        case GL_TEXTURE_ENV_COLOR:
#endif // defined(GL_TEXTURE_ENV_COLOR)
            _needed = 4;
            break;
        default:
            _needed = 0;
            break;
    }
    if (_remaining < _needed) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < needed");
        goto exit;
    }
    glTexEnviv(
        (GLenum)target,
        (GLenum)pname,
        (GLint *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, JNI_FALSE);
    }
}

/* void glTexParameterfv ( GLenum target, GLenum pname, const GLfloat *params ) */
static void
android_glTexParameterfv__II_3FI
  (JNIEnv *_env, jobject _this, jint target, jint pname, jfloatArray params_ref, jint offset) {
    GLfloat *params_base = (GLfloat *) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    if (!params_ref) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    if (_remaining < 1) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < 1");
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
}

/* void glTexParameterfv ( GLenum target, GLenum pname, const GLfloat *params ) */
static void
android_glTexParameterfv__IILjava_nio_FloatBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfloat *params = (GLfloat *) 0;

    params = (GLfloat *)getPointer(_env, params_buf, &_array, &_remaining);
    if (_remaining < 1) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < 1");
        goto exit;
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
    GLint *params_base = (GLint *) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    if (!params_ref) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    if (_remaining < 1) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < 1");
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
}

/* void glTexParameteriv ( GLenum target, GLenum pname, const GLint *params ) */
static void
android_glTexParameteriv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _remaining;
    GLint *params = (GLint *) 0;

    params = (GLint *)getPointer(_env, params_buf, &_array, &_remaining);
    if (_remaining < 1) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < 1");
        goto exit;
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
}

/* void glTexParameterxv ( GLenum target, GLenum pname, const GLfixed *params ) */
static void
android_glTexParameterxv__II_3II
  (JNIEnv *_env, jobject _this, jint target, jint pname, jintArray params_ref, jint offset) {
    GLfixed *params_base = (GLfixed *) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    if (!params_ref) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "params == null");
        goto exit;
    }
    if (offset < 0) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "offset < 0");
        goto exit;
    }
    _remaining = _env->GetArrayLength(params_ref) - offset;
    if (_remaining < 1) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "length - offset < 1");
        goto exit;
    }
    params_base = (GLfixed *)
        _env->GetPrimitiveArrayCritical(params_ref, (jboolean *)0);
    params = params_base + offset;

    glTexParameterxv(
        (GLenum)target,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (params_base) {
        _env->ReleasePrimitiveArrayCritical(params_ref, params_base,
            JNI_ABORT);
    }
}

/* void glTexParameterxv ( GLenum target, GLenum pname, const GLfixed *params ) */
static void
android_glTexParameterxv__IILjava_nio_IntBuffer_2
  (JNIEnv *_env, jobject _this, jint target, jint pname, jobject params_buf) {
    jarray _array = (jarray) 0;
    jint _remaining;
    GLfixed *params = (GLfixed *) 0;

    params = (GLfixed *)getPointer(_env, params_buf, &_array, &_remaining);
    if (_remaining < 1) {
        jniThrowException(_env, "java/lang/IllegalArgumentException", "remaining() < 1");
        goto exit;
    }
    glTexParameterxv(
        (GLenum)target,
        (GLenum)pname,
        (GLfixed *)params
    );

exit:
    if (_array) {
        releasePointer(_env, _array, params, JNI_FALSE);
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
        (const GLvoid *)offset
    );
}

static const char *classPathName = "android/opengl/GLES11";

static JNINativeMethod methods[] = {
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

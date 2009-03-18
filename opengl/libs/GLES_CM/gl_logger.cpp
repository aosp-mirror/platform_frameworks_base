/*
 ** Copyright 2007, The Android Open Source Project
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

#define LOG_TAG "GLLogger"

#include <ctype.h>
#include <string.h>
#include <errno.h>
#include <dlfcn.h>

#include <sys/ioctl.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <cutils/log.h>
#include <cutils/atomic.h>
#include <cutils/properties.h>

#include <utils/String8.h>

#include "gl_logger.h"

#undef NELEM
#define NELEM(x) (sizeof(x)/sizeof(*(x)))

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

template<typename T>
static int binarySearch(T const sortedArray[], int first, int last, EGLint key)
{
   while (first <= last) {
       int mid = (first + last) / 2;
       if (key > sortedArray[mid].key) {
           first = mid + 1;
       } else if (key < sortedArray[mid].key) {
           last = mid - 1;
       } else {
           return mid;
       }
   }
   return -1;
}

struct pair_t {
    const char* name;
    int         key;
};

static const pair_t gEnumMap[] = {
    #define GLENUM(NAME, VALUE) { #NAME, VALUE },
    #include "gl_enums.in"
    #undef GLENUM
};

// ----------------------------------------------------------------------------

template<typename TYPE>
class GLLogValue {
public:
    GLLogValue(TYPE value) : mValue(value) { }
    const TYPE& getValue() const { return mValue; }
    String8 toString() const {
        return convertToString(mValue);
    }
private:
    const TYPE& mValue;
    String8 convertToString(unsigned int v) const {
        char buf[16];
        snprintf(buf, 16, "%u", v);
        return String8(buf);
    }
    String8 convertToString(unsigned long v) const {
        char buf[16];
        snprintf(buf, 16, "%lu", v);
        return String8(buf);
    }
    String8 convertToString(int v) const {
        char buf[16];
        snprintf(buf, 16, "%d", v);
        return String8(buf);
    }
    String8 convertToString(long v) const {
        char buf[16];
        snprintf(buf, 16, "%ld", v);
        return String8(buf);
    }
    String8 convertToString(float v) const {
        char buf[16];
        snprintf(buf, 16, "%f", v);
        return String8(buf);
    }
    String8 convertToString(void const* v) const {
        char buf[16];
        snprintf(buf, 16, "%p", v);
        return String8(buf);
    }
};

class GLLogEnum : public GLLogValue<GLenum> {
public:
    GLLogEnum(GLenum v) : GLLogValue<GLenum>(v) { }
    String8 toString() const {
        GLenum v = getValue();
        int i = binarySearch<pair_t>(gEnumMap, 0, NELEM(gEnumMap)-1, v);
        if (i >= 0) {
            return String8(gEnumMap[i].name);
        } else {
            char buf[16];
            snprintf(buf, 16, "0x%04x", v);
            return String8(buf);
        }
    }
};

class GLLogClearBitfield : public GLLogValue<GLbitfield> {
public:
    GLLogClearBitfield(GLbitfield v) : GLLogValue<GLbitfield>(v) { }
    String8 toString() const {
        char buf[16];
        snprintf(buf, 16, "0x%08x", getValue());
        return String8(buf);
    }
};

class GLLogBool : public GLLogValue<GLboolean> {
public:
    GLLogBool(GLboolean v) : GLLogValue<GLboolean>(v) { }
    String8 toString() const {
        GLboolean v = getValue();
        if (v == GL_TRUE)   return String8("GL_TRUE");
        if (v == GL_FALSE)  return String8("GL_FALSE");
        return GLLogValue<GLboolean>::toString();
    }
};

class GLLogFixed : public GLLogValue<GLfixed> {
public:
    GLLogFixed(GLfixed v) : GLLogValue<GLfixed>(v) { }
    String8 toString() const {
        char buf[16];
        snprintf(buf, 16, "0x%08x", getValue());
        return String8(buf);
    }
};


template <typename TYPE>
class GLLogBuffer : public GLLogValue<TYPE *> {
public:
    GLLogBuffer(TYPE* buffer, size_t count = -1)
        : GLLogValue<TYPE*>(buffer)
    { // output buffer
    }
    GLLogBuffer(TYPE const* buffer, size_t count = -1)
    : GLLogValue<TYPE*>(const_cast<TYPE*>(buffer))
    { // input buffer
    }
};

class GLLog
{
public:
    GLLog(const char* name) : mNumParams(0) {
        mString.append(name);
        mString.append("(");
    }

    ~GLLog() {
        LOGD("%s);", mString.string());
    }

    GLLog& operator << (unsigned char v) {
        return *this << GLLogValue<unsigned int>(v);
    }
    GLLog& operator << (short v) {
        return *this << GLLogValue<unsigned int>(v);
    }
    GLLog& operator << (unsigned int v) {
        return *this << GLLogValue<unsigned int>(v);
    }
    GLLog& operator << (int v) {
        return *this << GLLogValue<int>(v);
    }
    GLLog& operator << (long v) {
        return *this << GLLogValue<long>(v);
    }
    GLLog& operator << (unsigned long v) {
        return *this << GLLogValue<unsigned long>(v);
    }
    GLLog& operator << (float v) {
        return *this << GLLogValue<float>(v);
    }
    GLLog& operator << (const void* v) {
        return *this << GLLogValue<const void* >(v);
    }

    template <typename TYPE>
    GLLog& operator << (const TYPE& rhs) {
        if (mNumParams > 0)
            mString.append(", ");
        mString.append(rhs.toString());
        mNumParams++;
        return *this;
    }

    const String8& string() const { return mString; }
private:
    GLLog(const GLLog&);

    String8 mString;
    int mNumParams;
};

#define API_ENTRY(api)                      log_##api
#define CALL_GL_API(_x, ...)
#define CALL_GL_API_RETURN(_x, ...)         return(0);

void API_ENTRY(glActiveTexture)(GLenum texture) {
    CALL_GL_API(glActiveTexture, texture);
    GLLog("glActiveTexture") << GLLogEnum(texture);
}

void API_ENTRY(glAlphaFunc)(GLenum func, GLclampf ref) {
    CALL_GL_API(glAlphaFunc, func, ref);
    GLLog("glAlphaFunc") << GLLogEnum(func) << ref;
}

void API_ENTRY(glAlphaFuncx)(GLenum func, GLclampx ref) {
    CALL_GL_API(glAlphaFuncx, func, ref);
    GLLog("glAlphaFuncx") << GLLogEnum(func) << GLLogFixed(ref);
}

void API_ENTRY(glBindTexture)(GLenum target, GLuint texture) {
    CALL_GL_API(glBindTexture, target, texture);
    GLLog("glBindTexture") << GLLogEnum(target) << texture;
}

void API_ENTRY(glBlendFunc)(GLenum sfactor, GLenum dfactor) {
    CALL_GL_API(glBlendFunc, sfactor, dfactor);
    GLLog("glBlendFunc") << GLLogEnum(sfactor) << GLLogEnum(dfactor);
}

void API_ENTRY(glClear)(GLbitfield mask) {
    CALL_GL_API(glClear, mask);
    GLLog("glClear") << GLLogClearBitfield(mask);
}

void API_ENTRY(glClearColor)(GLclampf red, GLclampf green, GLclampf blue, GLclampf alpha) {
    CALL_GL_API(glClearColor, red, green, blue, alpha);
    GLLog("glClearColor") << red << green << blue << alpha;
}

void API_ENTRY(glClearColorx)(GLclampx red, GLclampx green, GLclampx blue, GLclampx alpha) {
    CALL_GL_API(glClearColorx, red, green, blue, alpha);
    GLLog("glClearColorx") << GLLogFixed(red) << GLLogFixed(green) << GLLogFixed(blue) << GLLogFixed(alpha);
}

void API_ENTRY(glClearDepthf)(GLclampf depth) {
    CALL_GL_API(glClearDepthf, depth);
    GLLog("glClearDepthf") << depth;
}

void API_ENTRY(glClearDepthx)(GLclampx depth) {
    CALL_GL_API(glClearDepthx, depth);
    GLLog("glClearDepthx") << GLLogFixed(depth);
}

void API_ENTRY(glClearStencil)(GLint s) {
    CALL_GL_API(glClearStencil, s);
    GLLog("glClearStencil") << s;
}

void API_ENTRY(glClientActiveTexture)(GLenum texture) {
    CALL_GL_API(glClientActiveTexture, texture);
    GLLog("glClientActiveTexture") << GLLogEnum(texture);
}

void API_ENTRY(glColor4f)(GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha) {
    CALL_GL_API(glColor4f, red, green, blue, alpha);
    GLLog("glColor4f") << red << green << blue << alpha;
}

void API_ENTRY(glColor4x)(GLfixed red, GLfixed green, GLfixed blue, GLfixed alpha) {
    CALL_GL_API(glColor4x, red, green, blue, alpha);
    GLLog("glColor4x") << GLLogFixed(red) << GLLogFixed(green) << GLLogFixed(blue) << GLLogFixed(alpha);
}

void API_ENTRY(glColorMask)(GLboolean r, GLboolean g, GLboolean b, GLboolean a) {
    CALL_GL_API(glColorMask, r, g, b, a);
    GLLog("glColorMask") << GLLogBool(r) << GLLogBool(g) << GLLogBool(b) << GLLogBool(a);
}

void API_ENTRY(glColorPointer)(GLint size, GLenum type, GLsizei stride, const GLvoid *ptr)
{
    CALL_GL_API(glColorPointer, size, type, stride, ptr);
    GLLog("glColorPointer") << size << GLLogEnum(type) << stride << ptr;
}

void API_ENTRY(glCompressedTexImage2D)(GLenum target, GLint level, GLenum internalformat,
                            GLsizei width, GLsizei height, GLint border,
                            GLsizei imageSize, const GLvoid *data) {
    CALL_GL_API(glCompressedTexImage2D, target, level, internalformat,
            width, height, border, imageSize, data);
    GLLog("glCompressedTexImage2D")
                << GLLogEnum(target) << level << GLLogEnum(internalformat)
                << width << height << border << imageSize << data;
}

void API_ENTRY(glCompressedTexSubImage2D)( GLenum target, GLint level, GLint xoffset,
                                GLint yoffset, GLsizei width, GLsizei height,
                                GLenum format, GLsizei imageSize,
                                const GLvoid *data) {
    CALL_GL_API(glCompressedTexSubImage2D, target, level, xoffset, yoffset,
            width, height, format, imageSize, data);
    GLLog("glCompressedTexSubImage2D")
            << GLLogEnum(target) << level << xoffset << yoffset
            << width << height << GLLogEnum(format) << imageSize << data;
}

void API_ENTRY(glCopyTexImage2D)(  GLenum target, GLint level, GLenum internalformat,
                        GLint x, GLint y, GLsizei width, GLsizei height,
                        GLint border) {
    CALL_GL_API(glCopyTexImage2D, target, level, internalformat, x, y,
            width, height, border);
    GLLog("glCopyTexImage2D")
            << GLLogEnum(target) << level << GLLogEnum(internalformat)
            << x << y << width << height << border;
}

void API_ENTRY(glCopyTexSubImage2D)(   GLenum target, GLint level, GLint xoffset,
                            GLint yoffset, GLint x, GLint y, GLsizei width,
                            GLsizei height) {
    CALL_GL_API(glCopyTexSubImage2D, target, level, xoffset, yoffset, x, y,
            width, height);
    GLLog("glCopyTexSubImage2D")
            << GLLogEnum(target) << level << xoffset << yoffset
            << x << y << width << height;
}

void API_ENTRY(glCullFace)(GLenum mode) {
    CALL_GL_API(glCullFace, mode);
    GLLog("glCullFace") << GLLogEnum(mode);
}

void API_ENTRY(glDeleteTextures)(GLsizei n, const GLuint *textures) {
    CALL_GL_API(glDeleteTextures, n, textures);
    GLLog("glDeleteTextures") << n << GLLogBuffer<GLuint>(textures, n);
}

void API_ENTRY(glDepthFunc)(GLenum func) {
    CALL_GL_API(glDepthFunc, func);
    GLLog("glDepthFunc") << GLLogEnum(func);
}

void API_ENTRY(glDepthMask)(GLboolean flag) {
    CALL_GL_API(glDepthMask, flag);
    GLLog("glDepthMask") << GLLogBool(flag);
}

void API_ENTRY(glDepthRangef)(GLclampf zNear, GLclampf zFar) {
    CALL_GL_API(glDepthRangef, zNear, zFar);
    GLLog("glDepthRangef") << zNear << zFar;
}

void API_ENTRY(glDepthRangex)(GLclampx zNear, GLclampx zFar) {
    CALL_GL_API(glDepthRangex, zNear, zFar);
    GLLog("glDepthRangex") << GLLogFixed(zNear) << GLLogFixed(zFar);
}

void API_ENTRY(glDisable)(GLenum cap) {
    CALL_GL_API(glDisable, cap);
    GLLog("glDisable") << GLLogEnum(cap);
}

void API_ENTRY(glDisableClientState)(GLenum array) {
    CALL_GL_API(glDisableClientState, array);
    GLLog("glDisableClientState") << GLLogEnum(array);
}

void API_ENTRY(glDrawArrays)(GLenum mode, GLint first, GLsizei count) {
    CALL_GL_API(glDrawArrays, mode, first, count);
    GLLog("glDrawArrays") << GLLogEnum(mode) << first << count;
}

void API_ENTRY(glDrawElements)(GLenum mode, GLsizei count,
                    GLenum type, const GLvoid *indices) {
    CALL_GL_API(glDrawElements, mode, count, type, indices);
    GLLog log("glDrawElements");
    log << GLLogEnum(mode) << count << GLLogEnum(type);
    if (type == GL_UNSIGNED_BYTE) {
        log << GLLogBuffer<GLubyte>(static_cast<const GLubyte*>(indices), count);
    } else {
        log << GLLogBuffer<GLushort>(static_cast<const GLushort*>(indices), count);
    }
    log;
}

void API_ENTRY(glEnable)(GLenum cap) {
    CALL_GL_API(glEnable, cap);
    GLLog("glEnable") << GLLogEnum(cap);
}

void API_ENTRY(glEnableClientState)(GLenum array) {
    CALL_GL_API(glEnableClientState, array);
    GLLog("glEnableClientState") << GLLogEnum(array);
}

void API_ENTRY(glFinish)(void) {
    CALL_GL_API(glFinish);
    GLLog("glFinish");
}

void API_ENTRY(glFlush)(void) {
    CALL_GL_API(glFlush);
    GLLog("glFlush");
}

void API_ENTRY(glFogf)(GLenum pname, GLfloat param) {
    CALL_GL_API(glFogf, pname, param);
    GLLog("glFogf") << GLLogEnum(pname) << param;
}

void API_ENTRY(glFogfv)(GLenum pname, const GLfloat *params) {
    CALL_GL_API(glFogfv, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glFogfv") << GLLogEnum(pname) << GLLogBuffer<GLfloat>(params);
}

void API_ENTRY(glFogx)(GLenum pname, GLfixed param) {
    CALL_GL_API(glFogx, pname, param);
    GLLog("glFogx") << GLLogEnum(pname) << GLLogFixed(param);
}

void API_ENTRY(glFogxv)(GLenum pname, const GLfixed *params) {
    CALL_GL_API(glFogxv, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glFogfx") << GLLogEnum(pname) << GLLogBuffer<GLfixed>(params);
}

void API_ENTRY(glFrontFace)(GLenum mode) {
    CALL_GL_API(glFrontFace, mode);
    GLLog("glFrontFace") << GLLogEnum(mode);
 }

void API_ENTRY(glFrustumf)(GLfloat left, GLfloat right,
                GLfloat bottom, GLfloat top,
                GLfloat zNear, GLfloat zFar) {
    CALL_GL_API(glFrustumf, left, right, bottom, top, zNear, zFar);
    GLLog("glFrustumf") << left << right << bottom << top << zNear << zFar;
}

void API_ENTRY(glFrustumx)(GLfixed left, GLfixed right,
                GLfixed bottom, GLfixed top,
                GLfixed zNear, GLfixed zFar) {
    CALL_GL_API(glFrustumx, left, right, bottom, top, zNear, zFar);
    GLLog("glFrustumx")
            << GLLogFixed(left) << GLLogFixed(right)
            << GLLogFixed(bottom) << GLLogFixed(top)
            << GLLogFixed(zNear) << GLLogFixed(zFar);
}

void API_ENTRY(glGenTextures)(GLsizei n, GLuint *textures) {
    CALL_GL_API(glGenTextures, n, textures);
    GLLog("glGenTextures") << n << GLLogBuffer<GLuint>(textures, n);
}

GLenum API_ENTRY(glGetError)(void) {
    GLLog("glGetError");
    CALL_GL_API_RETURN(glGetError);
}

void API_ENTRY(glGetIntegerv)(GLenum pname, GLint *params) {
    CALL_GL_API(glGetIntegerv, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetIntegerv") << GLLogEnum(pname) << GLLogBuffer<GLint>(params);
}

const GLubyte * API_ENTRY(glGetString)(GLenum name) {
    GLLog("glGetString") << GLLogEnum(name);
    CALL_GL_API_RETURN(glGetString, name);
}

void API_ENTRY(glHint)(GLenum target, GLenum mode) {
    CALL_GL_API(glHint, target, mode);
    GLLog("GLenum") << GLLogEnum(target) << GLLogEnum(mode);
}

void API_ENTRY(glLightModelf)(GLenum pname, GLfloat param) {
    CALL_GL_API(glLightModelf, pname, param);
    GLLog("glLightModelf") << GLLogEnum(pname) << param;
}

void API_ENTRY(glLightModelfv)(GLenum pname, const GLfloat *params) {
    CALL_GL_API(glLightModelfv, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glLightModelfv") << GLLogEnum(pname) << GLLogBuffer<GLfloat>(params);
}

void API_ENTRY(glLightModelx)(GLenum pname, GLfixed param) {
    CALL_GL_API(glLightModelx, pname, param);
    GLLog("glLightModelx") << GLLogEnum(pname) << GLLogFixed(param);
}

void API_ENTRY(glLightModelxv)(GLenum pname, const GLfixed *params) {
    CALL_GL_API(glLightModelxv, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glLightModelxv") << GLLogEnum(pname) << GLLogBuffer<GLfixed>(params);
}

void API_ENTRY(glLightf)(GLenum light, GLenum pname, GLfloat param) {
    CALL_GL_API(glLightf, light, pname, param);
    GLLog("glLightf") << GLLogEnum(light) << GLLogEnum(pname) << param;
}

void API_ENTRY(glLightfv)(GLenum light, GLenum pname, const GLfloat *params) {
    CALL_GL_API(glLightfv, light, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glLightfv") << GLLogEnum(light) << GLLogEnum(pname) << GLLogBuffer<GLfloat>(params);
}

void API_ENTRY(glLightx)(GLenum light, GLenum pname, GLfixed param) {
   CALL_GL_API(glLightx, light, pname, param);
   GLLog("glLightx") << GLLogEnum(light) << GLLogEnum(pname) << GLLogFixed(param);
}

void API_ENTRY(glLightxv)(GLenum light, GLenum pname, const GLfixed *params) {
    CALL_GL_API(glLightxv, light, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glLightxv") << GLLogEnum(light) << GLLogEnum(pname) << GLLogBuffer<GLfixed>(params);
}

void API_ENTRY(glLineWidth)(GLfloat width) {
    CALL_GL_API(glLineWidth, width);
    GLLog("glLineWidth") << width;
}

void API_ENTRY(glLineWidthx)(GLfixed width) {
    CALL_GL_API(glLineWidthx, width);
    GLLog("glLineWidth") << GLLogFixed(width);
}

void API_ENTRY(glLoadIdentity)(void) {
    CALL_GL_API(glLoadIdentity);
    GLLog("glLoadIdentity");
}

void API_ENTRY(glLoadMatrixf)(const GLfloat *m) {
    CALL_GL_API(glLoadMatrixf, m);
    GLLog("glLoadMatrixf") << GLLogBuffer<GLfloat>(m, 16);
}

void API_ENTRY(glLoadMatrixx)(const GLfixed *m) {
    CALL_GL_API(glLoadMatrixx, m);
    GLLog("glLoadMatrixx") << GLLogBuffer<GLfixed>(m, 16);
}

void API_ENTRY(glLogicOp)(GLenum opcode) {
    CALL_GL_API(glLogicOp, opcode);
    GLLog("glLogicOp") << GLLogEnum(opcode);
}

void API_ENTRY(glMaterialf)(GLenum face, GLenum pname, GLfloat param) {
    CALL_GL_API(glMaterialf, face, pname, param);
    GLLog("glMaterialf") << GLLogEnum(face) << GLLogEnum(pname) << param;
}

void API_ENTRY(glMaterialfv)(GLenum face, GLenum pname, const GLfloat *params) {
    CALL_GL_API(glMaterialfv, face, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glMaterialfv") << GLLogEnum(face) << GLLogEnum(pname) << GLLogBuffer<GLfloat>(params);
}

void API_ENTRY(glMaterialx)(GLenum face, GLenum pname, GLfixed param) {
    CALL_GL_API(glMaterialx, face, pname, param);
    GLLog("glMaterialx") << GLLogEnum(face) << GLLogEnum(pname) << GLLogFixed(param);
}

void API_ENTRY(glMaterialxv)(GLenum face, GLenum pname, const GLfixed *params) {
    CALL_GL_API(glMaterialxv, face, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glMaterialxv") << GLLogEnum(face) << GLLogEnum(pname) << GLLogBuffer<GLfixed>(params);
}

void API_ENTRY(glMatrixMode)(GLenum mode) {
    CALL_GL_API(glMatrixMode, mode);
    GLLog("glMatrixMode") << GLLogEnum(mode);
}

void API_ENTRY(glMultMatrixf)(const GLfloat *m) {
    CALL_GL_API(glMultMatrixf, m);
    GLLog("glMultMatrixf") << GLLogBuffer<GLfloat>(m, 16);
}

void API_ENTRY(glMultMatrixx)(const GLfixed *m) {
    CALL_GL_API(glMultMatrixx, m);
    GLLog("glMultMatrixx") << GLLogBuffer<GLfixed>(m, 16);
}

void API_ENTRY(glMultiTexCoord4f)(GLenum target, GLfloat s, GLfloat t, GLfloat r, GLfloat q) {
    CALL_GL_API(glMultiTexCoord4f, target, s, t, r, q);
    GLLog("glMultiTexCoord4f") << GLLogEnum(target) << s << t << r << q;
}

void API_ENTRY(glMultiTexCoord4x)(GLenum target, GLfixed s, GLfixed t, GLfixed r, GLfixed q) {
    CALL_GL_API(glMultiTexCoord4x, target, s, t, r, q);
    GLLog("glMultiTexCoord4x") << GLLogEnum(target)
        << GLLogFixed(s) << GLLogFixed(t) << GLLogFixed(r) << GLLogFixed(q);
}

void API_ENTRY(glNormal3f)(GLfloat nx, GLfloat ny, GLfloat nz) {
    CALL_GL_API(glNormal3f, nx, ny, nz);
    GLLog("glNormal3f") << nx << ny << nz;
}

void API_ENTRY(glNormal3x)(GLfixed nx, GLfixed ny, GLfixed nz) {
    CALL_GL_API(glNormal3x, nx, ny, nz);
    GLLog("glNormal3x") << GLLogFixed(nx) << GLLogFixed(ny) << GLLogFixed(nz);
}

void API_ENTRY(glNormalPointer)(GLenum type, GLsizei stride, const GLvoid *pointer) {
    CALL_GL_API(glNormalPointer, type, stride, pointer);
    GLLog("glNormalPointer") << GLLogEnum(type) << stride << pointer;
}

void API_ENTRY(glOrthof)(  GLfloat left, GLfloat right,
                GLfloat bottom, GLfloat top,
                GLfloat zNear, GLfloat zFar) {
    CALL_GL_API(glOrthof, left, right, bottom, top, zNear, zFar);
    GLLog("glOrthof") << left << right << bottom << top << zNear << zFar;
}

void API_ENTRY(glOrthox)(  GLfixed left, GLfixed right,
                GLfixed bottom, GLfixed top,
                GLfixed zNear, GLfixed zFar) {
    CALL_GL_API(glOrthox, left, right, bottom, top, zNear, zFar);
    GLLog("glOrthox") << GLLogFixed(left) << GLLogFixed(right)
            << GLLogFixed(bottom) << GLLogFixed(top)
            << GLLogFixed(zNear) << GLLogFixed(zFar);
}

void API_ENTRY(glPixelStorei)(GLenum pname, GLint param) {
    CALL_GL_API(glPixelStorei, pname, param);
    GLLog("glPixelStorei") << GLLogEnum(pname) << param;
}

void API_ENTRY(glPointSize)(GLfloat size) {
    CALL_GL_API(glPointSize, size);
    GLLog("glPointSize") << size;
}

void API_ENTRY(glPointSizex)(GLfixed size) {
    CALL_GL_API(glPointSizex, size);
    GLLog("glPointSizex") << GLLogFixed(size);
}

void API_ENTRY(glPolygonOffset)(GLfloat factor, GLfloat units) {
    CALL_GL_API(glPolygonOffset, factor, units);
    GLLog("glPolygonOffset") << factor << units;
}

void API_ENTRY(glPolygonOffsetx)(GLfixed factor, GLfixed units) {
    CALL_GL_API(glPolygonOffsetx, factor, units);
    GLLog("glPolygonOffsetx") << GLLogFixed(factor) << GLLogFixed(units);
}

void API_ENTRY(glPopMatrix)(void) {
    CALL_GL_API(glPopMatrix);
    GLLog("glPopMatrix");
}

void API_ENTRY(glPushMatrix)(void) {
    CALL_GL_API(glPushMatrix);
    GLLog("glPushMatrix");
}

void API_ENTRY(glReadPixels)(  GLint x, GLint y, GLsizei width, GLsizei height,
                    GLenum format, GLenum type, GLvoid *pixels) {
    CALL_GL_API(glReadPixels, x, y, width, height, format, type, pixels);
    // XXX: we need to compute the size of this buffer
    GLLog("glReadPixels") << x << y << width << height << GLLogEnum(format) << GLLogEnum(type)
            << GLLogBuffer<unsigned char>(static_cast<unsigned char *>(pixels));
}

void API_ENTRY(glRotatef)(GLfloat angle, GLfloat x, GLfloat y, GLfloat z) {
    CALL_GL_API(glRotatef, angle, x, y, z);
    GLLog("glRotatef") << angle << x << y << z;
}

void API_ENTRY(glRotatex)(GLfixed angle, GLfixed x, GLfixed y, GLfixed z) {
    CALL_GL_API(glRotatex, angle, x, y, z);
    GLLog("glRotatex") << GLLogFixed(angle) << GLLogFixed(x) << GLLogFixed(y) << GLLogFixed(z);
}

void API_ENTRY(glSampleCoverage)(GLclampf value, GLboolean invert) {
    CALL_GL_API(glSampleCoverage, value, invert);
    GLLog("glSampleCoverage") << value << GLLogBool(invert);
}

void API_ENTRY(glSampleCoveragex)(GLclampx value, GLboolean invert) {
    CALL_GL_API(glSampleCoveragex, value, invert);
    GLLog("glSampleCoveragex") << GLLogFixed(value) << GLLogBool(invert);
}

void API_ENTRY(glScalef)(GLfloat x, GLfloat y, GLfloat z) {
    CALL_GL_API(glScalef, x, y, z);
    GLLog("glScalef") << x << y << z;
}

void API_ENTRY(glScalex)(GLfixed x, GLfixed y, GLfixed z) {
    CALL_GL_API(glScalex, x, y, z);
    GLLog("glScalex") << GLLogFixed(x) << GLLogFixed(y) << GLLogFixed(z);
}

void API_ENTRY(glScissor)(GLint x, GLint y, GLsizei width, GLsizei height) {
    CALL_GL_API(glScissor, x, y, width, height);
    GLLog("glScissor") << x << y << width << height;
}

void API_ENTRY(glShadeModel)(GLenum mode) {
    CALL_GL_API(glShadeModel, mode);
    GLLog("glShadeModel") << GLLogEnum(mode);
}

void API_ENTRY(glStencilFunc)(GLenum func, GLint ref, GLuint mask) {
    CALL_GL_API(glStencilFunc, func, ref, mask);
    GLLog("glStencilFunc") << GLLogEnum(func) << ref << mask;
}

void API_ENTRY(glStencilMask)(GLuint mask) {
    CALL_GL_API(glStencilMask, mask);
    GLLog("glStencilMask") << mask;
}

void API_ENTRY(glStencilOp)(GLenum fail, GLenum zfail, GLenum zpass) {
    CALL_GL_API(glStencilOp, fail, zfail, zpass);
    GLLog("glStencilOp") << GLLogEnum(fail) << GLLogEnum(zfail) << GLLogEnum(zpass);
}

void API_ENTRY(glTexCoordPointer)( GLint size, GLenum type,
                        GLsizei stride, const GLvoid *pointer) {
    CALL_GL_API(glTexCoordPointer, size, type, stride, pointer);
    GLLog("glTexCoordPointer") << size << GLLogEnum(type) << stride << pointer;
}

void API_ENTRY(glTexEnvf)(GLenum target, GLenum pname, GLfloat param) {
    CALL_GL_API(glTexEnvf, target, pname, param);
    GLLog("glTexEnvf") << GLLogEnum(target) << GLLogEnum(pname) << param;
}

void API_ENTRY(glTexEnvfv)(GLenum target, GLenum pname, const GLfloat *params) {
    CALL_GL_API(glTexEnvfv, target, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glTexEnvx") << GLLogEnum(target) << GLLogEnum(pname) << GLLogBuffer<GLfloat>(params);
}

void API_ENTRY(glTexEnvx)(GLenum target, GLenum pname, GLfixed param) {
    CALL_GL_API(glTexEnvx, target, pname, param);
    GLLog("glTexEnvx") << GLLogEnum(target) << GLLogEnum(pname) << GLLogFixed(param);
}

void API_ENTRY(glTexEnvxv)(GLenum target, GLenum pname, const GLfixed *params) {
    CALL_GL_API(glTexEnvxv, target, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glTexEnvxv") << GLLogEnum(target) << GLLogEnum(pname) << GLLogBuffer<GLfixed>(params);
}

void API_ENTRY(glTexImage2D)(  GLenum target, GLint level, GLint internalformat,
                    GLsizei width, GLsizei height, GLint border, GLenum format,
                    GLenum type, const GLvoid *pixels) {
    CALL_GL_API(glTexImage2D, target, level, internalformat, width, height,
            border, format, type, pixels);
    GLLog("glTexImage2D") << GLLogEnum(target) << level << GLLogEnum(internalformat)
            << width << height << border << GLLogEnum(format) << GLLogEnum(type)
            << GLLogBuffer<unsigned char>( static_cast<const unsigned char *>(pixels));
}

void API_ENTRY(glTexParameterf)(GLenum target, GLenum pname, GLfloat param) {
    CALL_GL_API(glTexParameterf, target, pname, param);
    GLLog("glTexParameterf") << GLLogEnum(target) << GLLogEnum(pname) << param;
}

void API_ENTRY(glTexParameterx)(GLenum target, GLenum pname, GLfixed param) {
    CALL_GL_API(glTexParameterx, target, pname, param);
    GLLog("glTexParameterx") << GLLogEnum(target) << GLLogEnum(pname) << GLLogFixed(param);
}

void API_ENTRY(glTexSubImage2D)(   GLenum target, GLint level, GLint xoffset,
                        GLint yoffset, GLsizei width, GLsizei height,
                        GLenum format, GLenum type, const GLvoid *pixels) {
    CALL_GL_API(glTexSubImage2D, target, level, xoffset, yoffset,
            width, height, format, type, pixels);
    GLLog("glTexSubImage2D") << GLLogEnum(target) << level << xoffset << yoffset
            << width << height << GLLogEnum(format) << GLLogEnum(type)
            << GLLogBuffer<unsigned char>( static_cast<const unsigned char *>(pixels));
}

void API_ENTRY(glTranslatef)(GLfloat x, GLfloat y, GLfloat z) {
    CALL_GL_API(glTranslatef, x, y, z);
    GLLog("glTranslatef") << x << y << z;
}

void API_ENTRY(glTranslatex)(GLfixed x, GLfixed y, GLfixed z) {
    CALL_GL_API(glTranslatex, x, y, z);
    GLLog("glTranslatex") << GLLogFixed(x) << GLLogFixed(y) << GLLogFixed(z);
}

void API_ENTRY(glVertexPointer)(   GLint size, GLenum type,
                        GLsizei stride, const GLvoid *pointer) {
    CALL_GL_API(glVertexPointer, size, type, stride, pointer);
    GLLog("glVertexPointer") << size << GLLogEnum(type) << stride << pointer;
}

void API_ENTRY(glViewport)(GLint x, GLint y, GLsizei width, GLsizei height) {
    CALL_GL_API(glViewport, x, y, width, height);
    GLLog("glViewport") << x << y << width << height;
}

// ES 1.1
void API_ENTRY(glClipPlanef)(GLenum plane, const GLfloat *equation) {
    CALL_GL_API(glClipPlanef, plane, equation);
    GLLog("glClipPlanef") << GLLogEnum(plane) << GLLogBuffer<GLfloat>(equation, 4);
}
void API_ENTRY(glClipPlanex)(GLenum plane, const GLfixed *equation) {
    CALL_GL_API(glClipPlanex, plane, equation);
    GLLog("glClipPlanex") << GLLogEnum(plane) << GLLogBuffer<GLfixed>(equation, 4);
}
void API_ENTRY(glBindBuffer)(GLenum target, GLuint buffer) {
    CALL_GL_API(glBindBuffer, target, buffer);
    GLLog("glBindBuffer") << GLLogEnum(target) << buffer;
}
void API_ENTRY(glBufferData)(GLenum target, GLsizeiptr size, const GLvoid* data, GLenum usage) {
    CALL_GL_API(glBufferData, target, size, data, usage);
    GLLog("glBufferData") << GLLogEnum(target) << size
        << GLLogBuffer<unsigned char>(static_cast<const unsigned char*>(data), size);
}
void API_ENTRY(glBufferSubData)(GLenum target, GLintptr offset, GLsizeiptr size, const GLvoid* data) {
    CALL_GL_API(glBufferSubData, target, offset, size, data);
    GLLog("glBufferSubData") << GLLogEnum(target) << offset << size
        << GLLogBuffer<unsigned char>(static_cast<const unsigned char*>(data), size);
}
void API_ENTRY(glDeleteBuffers)(GLsizei n, const GLuint* buffers) {
    CALL_GL_API(glDeleteBuffers, n, buffers);
    GLLog("glDeleteBuffers") << n << GLLogBuffer<GLuint>(buffers, n);
}
void API_ENTRY(glGenBuffers)(GLsizei n, GLuint* buffers) {
    CALL_GL_API(glGenBuffers, n, buffers);
    GLLog("glGenBuffers") << n << GLLogBuffer<GLuint>(buffers, n);
}
void API_ENTRY(glGetBooleanv)(GLenum pname, GLboolean *params) {
    CALL_GL_API(glGetBooleanv, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetBooleanv") << GLLogEnum(pname) << GLLogBuffer<GLboolean>(params);
}
void API_ENTRY(glGetFixedv)(GLenum pname, GLfixed *params) {
    CALL_GL_API(glGetFixedv, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetFixedv") << GLLogEnum(pname) << GLLogBuffer<GLfixed>(params);
}
void API_ENTRY(glGetFloatv)(GLenum pname, GLfloat *params) {
    CALL_GL_API(glGetFloatv, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetFloatv") << GLLogEnum(pname) << GLLogBuffer<GLfloat>(params);
}
void API_ENTRY(glGetPointerv)(GLenum pname, void **params) {
    CALL_GL_API(glGetPointerv, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetPointerv") << GLLogEnum(pname) << GLLogBuffer<void*>(params);
}
void API_ENTRY(glGetBufferParameteriv)(GLenum target, GLenum pname, GLint *params) {
    // XXX: we need to compute the size of this buffer
    CALL_GL_API(glGetBufferParameteriv, target, pname, params);
    GLLog("glGetBufferParameteriv") << GLLogEnum(target) << GLLogEnum(pname) << GLLogBuffer<GLint>(params);
}
void API_ENTRY(glGetClipPlanef)(GLenum pname, GLfloat eqn[4]) {
    CALL_GL_API(glGetClipPlanef, pname, eqn);
    GLLog("glGetClipPlanef") << GLLogEnum(pname) << GLLogBuffer<GLfloat>(eqn, 4);
}
void API_ENTRY(glGetClipPlanex)(GLenum pname, GLfixed eqn[4]) {
    CALL_GL_API(glGetClipPlanex, pname, eqn);
    GLLog("glGetClipPlanex") << GLLogEnum(pname) << GLLogBuffer<GLfixed>(eqn, 4);
}
void API_ENTRY(glGetLightxv)(GLenum light, GLenum pname, GLfixed *params) {
    CALL_GL_API(glGetLightxv, light, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetLightxv") << GLLogEnum(light) << GLLogEnum(pname) << GLLogBuffer<GLfixed>(params);
}
void API_ENTRY(glGetLightfv)(GLenum light, GLenum pname, GLfloat *params) {
    CALL_GL_API(glGetLightfv, light, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetLightfv") << GLLogEnum(light) << GLLogEnum(pname) << GLLogBuffer<GLfloat>(params);
}
void API_ENTRY(glGetMaterialxv)(GLenum face, GLenum pname, GLfixed *params) {
    CALL_GL_API(glGetMaterialxv, face, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetMaterialxv") << GLLogEnum(face) << GLLogEnum(pname) << GLLogBuffer<GLfixed>(params);
}
void API_ENTRY(glGetMaterialfv)(GLenum face, GLenum pname, GLfloat *params) {
    CALL_GL_API(glGetMaterialfv, face, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetMaterialfv") << GLLogEnum(face) << GLLogEnum(pname) << GLLogBuffer<GLfloat>(params);
}
void API_ENTRY(glGetTexEnvfv)(GLenum env, GLenum pname, GLfloat *params) {
    CALL_GL_API(glGetTexEnvfv, env, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetTexEnvfv") << GLLogEnum(env) << GLLogEnum(pname) << GLLogBuffer<GLfloat>(params);
}
void API_ENTRY(glGetTexEnviv)(GLenum env, GLenum pname, GLint *params) {
    CALL_GL_API(glGetTexEnviv, env, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetTexEnviv") << GLLogEnum(env) << GLLogEnum(pname) << GLLogBuffer<GLint>(params);
}
void API_ENTRY(glGetTexEnvxv)(GLenum env, GLenum pname, GLfixed *params) {
    CALL_GL_API(glGetTexEnvxv, env, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetTexEnvxv") << GLLogEnum(env) << GLLogEnum(pname) << GLLogBuffer<GLfixed>(params);
}
void API_ENTRY(glGetTexParameterfv)(GLenum target, GLenum pname, GLfloat *params) {
    CALL_GL_API(glGetTexParameterfv, target, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetTexParameterfv") << GLLogEnum(target) << GLLogEnum(pname) << GLLogBuffer<GLfloat>(params);
}
void API_ENTRY(glGetTexParameteriv)(GLenum target, GLenum pname, GLint *params) {
    CALL_GL_API(glGetTexParameteriv, target, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetTexParameteriv") << GLLogEnum(target) << GLLogEnum(pname) << GLLogBuffer<GLint>(params);
}
void API_ENTRY(glGetTexParameterxv)(GLenum target, GLenum pname, GLfixed *params) {
    CALL_GL_API(glGetTexParameterxv, target, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glGetTexParameterxv") << GLLogEnum(target) << GLLogEnum(pname) << GLLogBuffer<GLfixed>(params);
}
GLboolean API_ENTRY(glIsBuffer)(GLuint buffer) {
    GLLog("glIsBuffer") << buffer;
    CALL_GL_API_RETURN(glIsBuffer, buffer);
}
GLboolean API_ENTRY(glIsEnabled)(GLenum cap) {
    GLLog("glIsEnabled") << GLLogEnum(cap);
    CALL_GL_API_RETURN(glIsEnabled, cap);
}
GLboolean API_ENTRY(glIsTexture)(GLuint texture) {
    GLLog("glIsTexture") << texture;
    CALL_GL_API_RETURN(glIsTexture, texture);
}
void API_ENTRY(glPointParameterf)(GLenum pname, GLfloat param) {
    CALL_GL_API(glPointParameterf, pname, param);
    GLLog("glPointParameterf") << GLLogEnum(pname) << param;
}
void API_ENTRY(glPointParameterfv)(GLenum pname, const GLfloat *params) {
    CALL_GL_API(glPointParameterfv, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glPointParameterfv") << GLLogEnum(pname) << GLLogBuffer<GLfloat>(params);
}
void API_ENTRY(glPointParameterx)(GLenum pname, GLfixed param) {
    CALL_GL_API(glPointParameterx, pname, param);
    GLLog("glPointParameterx") << GLLogEnum(pname) << GLLogFixed(param);
}
void API_ENTRY(glPointParameterxv)(GLenum pname, const GLfixed *params) {
    CALL_GL_API(glPointParameterxv, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glPointParameterxv") << GLLogEnum(pname) << GLLogBuffer<GLfixed>(params);
}
void API_ENTRY(glColor4ub)(GLubyte red, GLubyte green, GLubyte blue, GLubyte alpha) {
    CALL_GL_API(glColor4ub, red, green, blue, alpha);
    GLLog("glColor4ub") << red << green << blue << alpha;
}
void API_ENTRY(glTexEnvi)(GLenum target, GLenum pname, GLint param) {
    CALL_GL_API(glTexEnvi, target, pname, param);
    GLLog("glTexEnvi") << GLLogEnum(target) << GLLogEnum(pname) << param;
}
void API_ENTRY(glTexEnviv)(GLenum target, GLenum pname, const GLint *params) {
    CALL_GL_API(glTexEnviv, target, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glTexEnviv") << GLLogEnum(target) << GLLogEnum(pname) << GLLogBuffer<GLint>(params);
}

void API_ENTRY(glTexParameterfv)(GLenum target, GLenum pname, const GLfloat *params) {
    CALL_GL_API(glTexParameterfv, target, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glTexParameterfv") << GLLogEnum(target) << GLLogEnum(pname) << GLLogBuffer<GLfloat>(params);
}

void API_ENTRY(glTexParameteriv)(GLenum target, GLenum pname, const GLint *params) {
    CALL_GL_API(glTexParameteriv, target, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glTexParameteriv") << GLLogEnum(target) << GLLogEnum(pname) << GLLogBuffer<GLint>(params);
}

void API_ENTRY(glTexParameteri)(GLenum target, GLenum pname, GLint param) {
    CALL_GL_API(glTexParameteri, target, pname, param);
    GLLog("glTexParameteri") << GLLogEnum(target) << GLLogEnum(pname) << param;
}
void API_ENTRY(glTexParameterxv)(GLenum target, GLenum pname, const GLfixed *params) {
    CALL_GL_API(glTexParameterxv, target, pname, params);
    // XXX: we need to compute the size of this buffer
    GLLog("glTexParameterxv") << GLLogEnum(target) << GLLogEnum(pname) << GLLogBuffer<GLfixed>(params);
}
void API_ENTRY(glPointSizePointerOES)(GLenum type, GLsizei stride, const GLvoid *pointer) {
    CALL_GL_API(glPointSizePointerOES, type, stride, pointer);
    GLLog("glPointSizePointerOES") << GLLogEnum(type) << stride << pointer;
}

// Extensions
void API_ENTRY(glDrawTexsOES)(GLshort x , GLshort y, GLshort z, GLshort w, GLshort h) {
    CALL_GL_API(glDrawTexsOES, x, y, z, w, h);
    GLLog("glDrawTexsOES") << x << y << z << w << h;
}
void API_ENTRY(glDrawTexiOES)(GLint x, GLint y, GLint z, GLint w, GLint h) {
    CALL_GL_API(glDrawTexiOES, x, y, z, w, h);
    GLLog("glDrawTexiOES") << x << y << z << w << h;
}
void API_ENTRY(glDrawTexfOES)(GLfloat x, GLfloat y, GLfloat z, GLfloat w, GLfloat h) {
    CALL_GL_API(glDrawTexfOES, x, y, z, w, h);
    GLLog("glDrawTexfOES") << x << y << z << w << h;
}
void API_ENTRY(glDrawTexxOES)(GLfixed x, GLfixed y, GLfixed z, GLfixed w, GLfixed h) {
    CALL_GL_API(glDrawTexxOES, x, y, z, w, h);
    GLLog("glDrawTexfOES") << GLLogFixed(x) << GLLogFixed(y) << GLLogFixed(z) << GLLogFixed(w) << GLLogFixed(h);
}
void API_ENTRY(glDrawTexsvOES)(const GLshort* coords) {
    CALL_GL_API(glDrawTexsvOES, coords);
    GLLog("glDrawTexsvOES") << GLLogBuffer<GLshort>(coords, 5);
}
void API_ENTRY(glDrawTexivOES)(const GLint* coords) {
    CALL_GL_API(glDrawTexivOES, coords);
    GLLog("glDrawTexivOES") << GLLogBuffer<GLint>(coords, 5);
}
void API_ENTRY(glDrawTexfvOES)(const GLfloat* coords) {
    CALL_GL_API(glDrawTexfvOES, coords);
    GLLog("glDrawTexfvOES") << GLLogBuffer<GLfloat>(coords, 5);
}
void API_ENTRY(glDrawTexxvOES)(const GLfixed* coords) {
    CALL_GL_API(glDrawTexxvOES, coords);
    GLLog("glDrawTexxvOES") << GLLogBuffer<GLfixed>(coords, 5);
}
GLbitfield API_ENTRY(glQueryMatrixxOES)(GLfixed* mantissa, GLint* exponent) {
    GLLog("glQueryMatrixxOES") << GLLogBuffer<GLfixed>(mantissa, 16) << GLLogBuffer<GLfixed>(exponent, 16);
    CALL_GL_API_RETURN(glQueryMatrixxOES, mantissa, exponent);
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

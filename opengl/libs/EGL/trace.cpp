/*
 ** Copyright 2010, The Android Open Source Project
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

#if EGL_TRACE

#include <stdarg.h>
#include <stdlib.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <cutils/log.h>

#include "hooks.h"

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

struct GLenumString {
    GLenum e;
    const char* s;
};

#undef GL_ENUM
#define GL_ENUM(VAL,NAME) {VAL, #NAME},

static GLenumString g_enumnames[] = {
#include "enums.in"
};
#undef GL_ENUM

static int compareGLEnum(const void* a, const void* b) {
    return ((const GLenumString*) a)->e - ((const GLenumString*) b)->e;
}

static const char* GLEnumToString(GLenum e) {
    GLenumString key = {e, ""};
    const GLenumString* result = (const GLenumString*) bsearch(
        &key, g_enumnames,
        sizeof(g_enumnames) / sizeof(g_enumnames[0]),
        sizeof(g_enumnames[0]), compareGLEnum);
    if (result) {
        return result->s;
    }
    return NULL;
}

static GLenumString g_bitfieldNames[] = {
    {0x00004000, "GL_COLOR_BUFFER_BIT"},
    {0x00000400, "GL_STENCIL_BUFFER_BIT"},
    {0x00000100, "GL_DEPTH_BUFFER_BIT"}
};


static void TraceGLShaderSource(GLuint shader, GLsizei count,
    const GLchar** string, const GLint* length) {
    LOGD("const char* shaderSrc[] = {");
    for (GLsizei i = 0; i < count; i++) {
        const char* comma = i < count-1 ? "," : "";
        const GLchar* s = string[i];
        if (length) {
            GLint len = length[i];
            LOGD("    \"%*s\"%s", len, s, comma);
        } else {
            LOGD("    \"%s\"%s", s, comma);
        }
    }
    LOGD("};");
    if (length) {
        LOGD("const GLint* shaderLength[] = {");
        for (GLsizei i = 0; i < count; i++) {
            const char* comma = i < count-1 ? "," : "";
            GLint len = length[i];
            LOGD("    \"%d\"%s", len, comma);
        }
        LOGD("};");
        LOGD("glShaderSource(%u, %u, shaderSrc, shaderLength);",
            shader, count);
    } else {
        LOGD("glShaderSource(%u, %u, shaderSrc, (const GLint*) 0);",
            shader, count);
    }
}

static void TraceGL(const char* name, int numArgs, ...) {
    va_list argp;
    va_start(argp, numArgs);
    if (strcmp(name, "glShaderSource") == 0) {
        va_arg(argp, const char*);
        GLuint shader = va_arg(argp, GLuint);
        va_arg(argp, const char*);
        GLsizei count = va_arg(argp, GLsizei);
        va_arg(argp, const char*);
        const GLchar** string = (const GLchar**) va_arg(argp, void*);
        va_arg(argp, const char*);
        const GLint* length = (const GLint*) va_arg(argp, void*);
        TraceGLShaderSource(shader, count, string, length);
        va_end(argp);
        return;
    }
    const int lineSize = 500;
    char line[lineSize];
    int line_index = 0;
    #define APPEND(...) \
        line_index += snprintf(line + line_index, lineSize-line_index, __VA_ARGS__);
    APPEND("%s(", name);
    for (int i = 0; i < numArgs; i++) {
        if (i > 0) {
            APPEND(", ");
        }
        const char* type = va_arg(argp, const char*);
        bool isPtr = type[strlen(type)-1] == '*'
            || strcmp(type, "GLeglImageOES") == 0;
        if (isPtr) {
            const void* arg = va_arg(argp, const void*);
            APPEND("(%s) 0x%08x", type, (size_t) arg);
        } else if (strcmp(type, "GLbitfield") == 0) {
            size_t arg = va_arg(argp, size_t);
            bool first = true;
            for (size_t i = 0; i < sizeof(g_bitfieldNames) / sizeof(g_bitfieldNames[0]); i++) {
                const GLenumString* b = &g_bitfieldNames[i];
                if (b->e & arg) {
                    if (first) {
                        first = false;
                    } else {
                        APPEND(" | ");
                    }
                    APPEND("%s", b->s);
                    arg &= ~b->e;
                }
            }
            if (first || arg != 0) {
                if (!first) {
                    APPEND(" | ");
                }
                APPEND("0x%08x", arg);
            }
        } else if (strcmp(type, "GLboolean") == 0) {
            int arg = va_arg(argp, int);
            APPEND("%s", arg ? "GL_TRUE" : "GL_FALSE");
        } else if (strcmp(type, "GLclampf") == 0) {
            double arg = va_arg(argp, double);
            APPEND("%g", arg);
        } else if (strcmp(type, "GLenum") == 0) {
            GLenum arg = va_arg(argp, int);
            const char* s = GLEnumToString(arg);
            if (s) {
                APPEND("%s", s);
            } else {
                APPEND("0x%x", arg);
            }
        } else if (strcmp(type, "GLfixed") == 0) {
            int arg = va_arg(argp, int);
            APPEND("0x%08x", arg);
        } else if (strcmp(type, "GLfloat") == 0) {
            double arg = va_arg(argp, double);
            APPEND("%g", arg);
        } else if (strcmp(type, "GLint") == 0) {
            int arg = va_arg(argp, int);
            const char* s = NULL;
            if (strcmp(name, "glTexParameteri") == 0) {
                s = GLEnumToString(arg);
            }
            if (s) {
                APPEND("%s", s);
            } else {
                APPEND("%d", arg);
            }
        } else if (strcmp(type, "GLintptr") == 0) {
            int arg = va_arg(argp, unsigned int);
            APPEND("%u", arg);
        } else if (strcmp(type, "GLsizei") == 0) {
            int arg = va_arg(argp, size_t);
            APPEND("%u", arg);
        } else if (strcmp(type, "GLsizeiptr") == 0) {
            int arg = va_arg(argp, size_t);
            APPEND("%u", arg);
        } else if (strcmp(type, "GLuint") == 0) {
            int arg = va_arg(argp, unsigned int);
            APPEND("%u", arg);
        } else {
            APPEND("/* ??? %s */", type);
            break;
        }
    }
    APPEND(");");
    line[lineSize-1] = '\0';
    LOGD("%s", line);
    va_end(argp);
}

#undef TRACE_GL_VOID
#undef TRACE_GL

#define TRACE_GL_VOID(_api, _args, _argList, ...)                         \
static void Tracing_ ## _api _args {                                      \
    TraceGL(#_api, __VA_ARGS__);                                          \
    gl_hooks_t::gl_t const * const _c = &getGLTraceThreadSpecific()->gl;  \
    _c->_api _argList;                                                    \
}

#define TRACE_GL(_type, _api, _args, _argList, ...)                       \
static _type Tracing_ ## _api _args {                                     \
    TraceGL(#_api, __VA_ARGS__);                                          \
    gl_hooks_t::gl_t const * const _c = &getGLTraceThreadSpecific()->gl;  \
    return _c->_api _argList;                                             \
}

extern "C" {
#include "../trace.in"
}
#undef TRACE_GL_VOID
#undef TRACE_GL

#define GL_ENTRY(_r, _api, ...) Tracing_ ## _api,

EGLAPI gl_hooks_t gHooksTrace = {
    {
        #include "entries.in"
    },
    {
        {0}
    }
};
#undef GL_ENTRY

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

#endif // EGL_TRACE

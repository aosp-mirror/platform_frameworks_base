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

#include "egl_tls.h"
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

static const char* GLbooleanToString(GLboolean arg) {
    return arg ? "GL_TRUE" : "GL_FALSE";
}

static GLenumString g_bitfieldNames[] = {
    {0x00004000, "GL_COLOR_BUFFER_BIT"},
    {0x00000400, "GL_STENCIL_BUFFER_BIT"},
    {0x00000100, "GL_DEPTH_BUFFER_BIT"}
};

class StringBuilder {
    static const int lineSize = 500;
    char line[lineSize];
    int line_index;
public:
    StringBuilder() {
        line_index = 0;
        line[0] = '\0';
    }
    void append(const char* fmt, ...) {
        va_list argp;
        va_start(argp, fmt);
        line_index += vsnprintf(line + line_index, lineSize-line_index, fmt, argp);
        va_end(argp);
    }
    const char* getString() {
        line_index = 0;
        line[lineSize-1] = '\0';
        return line;
    }
};


static void TraceGLShaderSource(GLuint shader, GLsizei count,
    const GLchar** string, const GLint* length) {
    ALOGD("const char* shaderSrc[] = {");
    for (GLsizei i = 0; i < count; i++) {
        const char* comma = i < count-1 ? "," : "";
        const GLchar* s = string[i];
        if (length) {
            GLint len = length[i];
            ALOGD("    \"%*s\"%s", len, s, comma);
        } else {
            ALOGD("    \"%s\"%s", s, comma);
        }
    }
    ALOGD("};");
    if (length) {
        ALOGD("const GLint* shaderLength[] = {");
        for (GLsizei i = 0; i < count; i++) {
            const char* comma = i < count-1 ? "," : "";
            GLint len = length[i];
            ALOGD("    \"%d\"%s", len, comma);
        }
        ALOGD("};");
        ALOGD("glShaderSource(%u, %u, shaderSrc, shaderLength);",
            shader, count);
    } else {
        ALOGD("glShaderSource(%u, %u, shaderSrc, (const GLint*) 0);",
            shader, count);
    }
}

static void TraceValue(int elementCount, char type,
        GLsizei chunkCount, GLsizei chunkSize, const void* value) {
    StringBuilder stringBuilder;
    GLsizei count = chunkCount * chunkSize;
    bool isFloat = type == 'f';
    const char* typeString = isFloat ? "GLfloat" : "GLint";
    ALOGD("const %s value[] = {", typeString);
    for (GLsizei i = 0; i < count; i++) {
        StringBuilder builder;
        builder.append("    ");
        for (int e = 0; e < elementCount; e++) {
            const char* comma = ", ";
            if (e == elementCount-1) {
                if (i == count - 1) {
                    comma = "";
                } else {
                    comma = ",";
                }
            }
            if (isFloat) {
                builder.append("%g%s", * (GLfloat*) value, comma);
                value = (void*) (((GLfloat*) value) + 1);
            } else {
                builder.append("%d%s", * (GLint*) value, comma);
                value = (void*) (((GLint*) value) + 1);
            }
        }
        ALOGD("%s", builder.getString());
        if (chunkSize > 1 && i < count-1
                && (i % chunkSize) == (chunkSize-1)) {
            ALOGD("%s", ""); // Print a blank line.
        }
    }
    ALOGD("};");
}

static void TraceUniformv(int elementCount, char type,
        GLuint location, GLsizei count, const void* value) {
    TraceValue(elementCount, type, count, 1, value);
    ALOGD("glUniform%d%c(%u, %u, value);", elementCount, type, location, count);
}

static void TraceUniformMatrix(int matrixSideLength,
        GLuint location, GLsizei count, GLboolean transpose, const void* value) {
    TraceValue(matrixSideLength, 'f', count, matrixSideLength, value);
    ALOGD("glUniformMatrix%dfv(%u, %u, %s, value);", matrixSideLength, location, count,
            GLbooleanToString(transpose));
}

static void TraceGL(const char* name, int numArgs, ...) {
    va_list argp;
    va_start(argp, numArgs);
    int nameLen = strlen(name);

    // glShaderSource
    if (nameLen == 14 && strcmp(name, "glShaderSource") == 0) {
        va_arg(argp, const char*);
        GLuint shader = va_arg(argp, GLuint);
        va_arg(argp, const char*);
        GLsizei count = va_arg(argp, GLsizei);
        va_arg(argp, const char*);
        const GLchar** string = (const GLchar**) va_arg(argp, void*);
        va_arg(argp, const char*);
        const GLint* length = (const GLint*) va_arg(argp, void*);
        va_end(argp);
        TraceGLShaderSource(shader, count, string, length);
        return;
    }

    // glUniformXXv

    if (nameLen == 12 && strncmp(name, "glUniform", 9) == 0 && name[11] == 'v') {
        int elementCount = name[9] - '0'; // 1..4
        char type = name[10]; // 'f' or 'i'
        va_arg(argp, const char*);
        GLuint location = va_arg(argp, GLuint);
        va_arg(argp, const char*);
        GLsizei count = va_arg(argp, GLsizei);
        va_arg(argp, const char*);
        const void* value = (const void*) va_arg(argp, void*);
        va_end(argp);
        TraceUniformv(elementCount, type, location, count, value);
        return;
    }

    // glUniformMatrixXfv

    if (nameLen == 18 && strncmp(name, "glUniformMatrix", 15) == 0
            && name[16] == 'f' && name[17] == 'v') {
        int matrixSideLength = name[15] - '0'; // 2..4
        va_arg(argp, const char*);
        GLuint location = va_arg(argp, GLuint);
        va_arg(argp, const char*);
        GLsizei count = va_arg(argp, GLsizei);
        va_arg(argp, const char*);
        GLboolean transpose = (GLboolean) va_arg(argp, int);
        va_arg(argp, const char*);
        const void* value = (const void*) va_arg(argp, void*);
        va_end(argp);
        TraceUniformMatrix(matrixSideLength, location, count, transpose, value);
        return;
    }

    StringBuilder builder;
    builder.append("%s(", name);
    for (int i = 0; i < numArgs; i++) {
        if (i > 0) {
            builder.append(", ");
        }
        const char* type = va_arg(argp, const char*);
        bool isPtr = type[strlen(type)-1] == '*'
            || strcmp(type, "GLeglImageOES") == 0;
        if (isPtr) {
            const void* arg = va_arg(argp, const void*);
            builder.append("(%s) 0x%08x", type, (size_t) arg);
        } else if (strcmp(type, "GLbitfield") == 0) {
            size_t arg = va_arg(argp, size_t);
            bool first = true;
            for (size_t i = 0; i < sizeof(g_bitfieldNames) / sizeof(g_bitfieldNames[0]); i++) {
                const GLenumString* b = &g_bitfieldNames[i];
                if (b->e & arg) {
                    if (first) {
                        first = false;
                    } else {
                        builder.append(" | ");
                    }
                    builder.append("%s", b->s);
                    arg &= ~b->e;
                }
            }
            if (first || arg != 0) {
                if (!first) {
                    builder.append(" | ");
                }
                builder.append("0x%08x", arg);
            }
        } else if (strcmp(type, "GLboolean") == 0) {
            GLboolean arg = va_arg(argp, int);
            builder.append("%s", GLbooleanToString(arg));
        } else if (strcmp(type, "GLclampf") == 0) {
            double arg = va_arg(argp, double);
            builder.append("%g", arg);
        } else if (strcmp(type, "GLenum") == 0) {
            GLenum arg = va_arg(argp, int);
            const char* s = GLEnumToString(arg);
            if (s) {
                builder.append("%s", s);
            } else {
                builder.append("0x%x", arg);
            }
        } else if (strcmp(type, "GLfixed") == 0) {
            int arg = va_arg(argp, int);
            builder.append("0x%08x", arg);
        } else if (strcmp(type, "GLfloat") == 0) {
            double arg = va_arg(argp, double);
            builder.append("%g", arg);
        } else if (strcmp(type, "GLint") == 0) {
            int arg = va_arg(argp, int);
            const char* s = NULL;
            if (strcmp(name, "glTexParameteri") == 0) {
                s = GLEnumToString(arg);
            }
            if (s) {
                builder.append("%s", s);
            } else {
                builder.append("%d", arg);
            }
        } else if (strcmp(type, "GLintptr") == 0) {
            int arg = va_arg(argp, unsigned int);
            builder.append("%u", arg);
        } else if (strcmp(type, "GLsizei") == 0) {
            int arg = va_arg(argp, size_t);
            builder.append("%u", arg);
        } else if (strcmp(type, "GLsizeiptr") == 0) {
            int arg = va_arg(argp, size_t);
            builder.append("%u", arg);
        } else if (strcmp(type, "GLuint") == 0) {
            int arg = va_arg(argp, unsigned int);
            builder.append("%u", arg);
        } else {
            builder.append("/* ??? %s */", type);
            break;
        }
    }
    builder.append(");");
    ALOGD("%s", builder.getString());
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
    TraceGL(#_api, __VA_ARGS__);                                        \
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


#undef TRACE_GL_VOID
#undef TRACE_GL

// define the ES 1.0 Debug_gl* functions as Tracing_gl functions
#define TRACE_GL_VOID(_api, _args, _argList, ...)                         \
static void Debug_ ## _api _args {                                      \
    TraceGL(#_api, __VA_ARGS__);                                          \
    gl_hooks_t::gl_t const * const _c = &getGLTraceThreadSpecific()->gl;  \
    _c->_api _argList;                                                    \
}

#define TRACE_GL(_type, _api, _args, _argList, ...)                       \
static _type Debug_ ## _api _args {                                     \
    TraceGL(#_api, __VA_ARGS__);                                        \
    gl_hooks_t::gl_t const * const _c = &getGLTraceThreadSpecific()->gl;  \
    return _c->_api _argList;                                             \
}

extern "C" {
#include "../debug.in"
}

#undef TRACE_GL_VOID
#undef TRACE_GL

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

#endif // EGL_TRACE

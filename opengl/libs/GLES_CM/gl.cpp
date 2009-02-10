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

#define LOG_TAG "GLES_CM"

#include <ctype.h>
#include <string.h>
#include <errno.h>

#include <sys/ioctl.h>

#include <GLES/gl.h>
#include <GLES/glext.h>

#include <cutils/log.h>
#include <cutils/properties.h>

#include "hooks.h"

using namespace android;

// ----------------------------------------------------------------------------
// extensions for the framework
// ----------------------------------------------------------------------------

void glColorPointerBounds(GLint size, GLenum type, GLsizei stride,
        const GLvoid *ptr, GLsizei count) {
    glColorPointer(size, type, stride, ptr);
}
void glNormalPointerBounds(GLenum type, GLsizei stride,
        const GLvoid *pointer, GLsizei count) {
    glNormalPointer(type, stride, pointer);
}
void glTexCoordPointerBounds(GLint size, GLenum type,
        GLsizei stride, const GLvoid *pointer, GLsizei count) {
    glTexCoordPointer(size, type, stride, pointer);
}
void glVertexPointerBounds(GLint size, GLenum type,
        GLsizei stride, const GLvoid *pointer, GLsizei count) {
    glVertexPointer(size, type, stride, pointer);
}

// ----------------------------------------------------------------------------
// Actual GL entry-points
// ----------------------------------------------------------------------------

#if GL_LOGGER
#   include "gl_logger.h"
#   define GL_LOGGER_IMPL(_x) _x
#else
#   define GL_LOGGER_IMPL(_x)
#endif

#undef API_ENTRY
#undef CALL_GL_API
#undef CALL_GL_API_RETURN

#if USE_FAST_TLS_KEY

    #define API_ENTRY(_api) __attribute__((naked)) _api

    #define CALL_GL_API(_api, ...)                              \
         asm volatile(                                          \
            "mov   r12, #0xFFFF0FFF   \n"                       \
            "ldr   r12, [r12, #-15]   \n"                       \
            "ldr   r12, [r12, %[tls]] \n"                       \
            "cmp   r12, #0            \n"                       \
            "ldrne pc,  [r12, %[api]] \n"                       \
            "bx    lr                 \n"                       \
            :                                                   \
            : [tls] "J"(TLS_SLOT_OPENGL_API*4),                 \
              [api] "J"(__builtin_offsetof(gl_hooks_t, gl._api))    \
            :                                                   \
            );
    
    #define CALL_GL_API_RETURN(_api, ...) \
        CALL_GL_API(_api, __VA_ARGS__) \
        return 0; // placate gcc's warnings. never reached.

#else

    #define API_ENTRY(_api) _api

    #define CALL_GL_API(_api, ...)                                      \
        gl_hooks_t::gl_t const * const _c = &getGlThreadSpecific()->gl; \
        GL_LOGGER_IMPL( log_##_api(__VA_ARGS__); )                      \
        _c->_api(__VA_ARGS__)
    
    #define CALL_GL_API_RETURN(_api, ...)                               \
        gl_hooks_t::gl_t const * const _c = &getGlThreadSpecific()->gl; \
        GL_LOGGER_IMPL( log_##_api(__VA_ARGS__); )                      \
        return _c->_api(__VA_ARGS__)

#endif

extern "C" {
#include "gl_api.in"
}

#undef API_ENTRY
#undef CALL_GL_API
#undef CALL_GL_API_RETURN


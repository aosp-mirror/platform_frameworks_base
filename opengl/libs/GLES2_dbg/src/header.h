/*
 ** Copyright 2011, The Android Open Source Project
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

#include <stdlib.h>
#include <ctype.h>
#include <string.h>
#include <errno.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <cutils/log.h>
#include <utils/Timers.h>
#include <../../../libcore/include/StaticAssert.h>

#define EGL_TRACE 1
#include "hooks.h"

#include "glesv2dbg.h"

#define GL_ENTRY(_r, _api, ...) _r Debug_##_api ( __VA_ARGS__ );
#include "glesv2dbg_functions.h"

#include "debugger_message.pb.h"

using namespace android;
using namespace com::android;

#define API_ENTRY(_api) Debug_##_api

#ifndef __location__
#define __HIERALLOC_STRING_0__(s)   #s
#define __HIERALLOC_STRING_1__(s)   __HIERALLOC_STRING_0__(s)
#define __HIERALLOC_STRING_2__      __HIERALLOC_STRING_1__(__LINE__)
#define __location__                __FILE__ ":" __HIERALLOC_STRING_2__
#endif

#undef assert
#define assert(expr) if (!(expr)) { LOGD("\n*\n*\n* assert: %s at %s \n*\n*", #expr, __location__); int * x = 0; *x = 5; }
//#undef LOGD
//#define LOGD(...)

namespace android
{

struct DbgContext {
    const unsigned version; // 0 is GLES1, 1 is GLES2
    const gl_hooks_t * const hooks;
    const unsigned MAX_VERTEX_ATTRIBS;
    
    struct VertexAttrib {
        GLenum type; // element data type
        unsigned size; // number of data per element
        unsigned stride; // calculated number of bytes between elements
        const void * ptr;
        unsigned elemSize; // calculated number of bytes per element
        GLuint buffer; // buffer name
        GLboolean normalized : 1;
        GLboolean enabled : 1;
        VertexAttrib() : type(0), size(0), stride(0), ptr(NULL), elemSize(0),
                buffer(0), normalized(0), enabled(0) {}
    } * vertexAttribs;
    bool hasNonVBOAttribs; // whether any enabled vertexAttrib is user pointer

    struct VBO {
        const GLuint name;
        const GLenum target;
        VBO * next;
        void * data; // malloc/free
        unsigned size; // in bytes
        VBO(const GLuint name, const GLenum target, VBO * head) : name(name),
                target(target), next(head), data(NULL), size(0) {}
    } * indexBuffers; // linked list of all index buffers
    VBO * indexBuffer; // currently bound index buffer

    GLuint program;
    unsigned maxAttrib; // number of slots used by program

    DbgContext(const unsigned version, const gl_hooks_t * const hooks, const unsigned MAX_VERTEX_ATTRIBS);
    ~DbgContext();

    void Fetch(const unsigned index, std::string * const data) const;

    void glUseProgram(GLuint program);
    void glEnableVertexAttribArray(GLuint index);
    void glDisableVertexAttribArray(GLuint index);
    void glVertexAttribPointer(GLuint indx, GLint size, GLenum type, GLboolean normalized, GLsizei stride, const GLvoid* ptr);
    void glBindBuffer(GLenum target, GLuint buffer);
    void glBufferData(GLenum target, GLsizeiptr size, const GLvoid* data, GLenum usage);
    void glBufferSubData(GLenum target, GLintptr offset, GLsizeiptr size, const GLvoid* data);
    void glDeleteBuffers(GLsizei n, const GLuint *buffers);
};


DbgContext * getDbgContextThreadSpecific();
#define DBGCONTEXT(ctx) DbgContext * const ctx = getDbgContextThreadSpecific();

struct FunctionCall {
    virtual const int * operator()(gl_hooks_t::gl_t const * const _c, glesv2debugger::Message & msg) = 0;
    virtual ~FunctionCall() {}
};

// move these into DbgContext as static
extern bool capture;
extern int timeMode; // SYSTEM_TIME_

extern int clientSock, serverSock;

unsigned GetBytesPerPixel(const GLenum format, const GLenum type);

// every Debug_gl* function calls this to send message to client and possibly receive commands
int * MessageLoop(FunctionCall & functionCall, glesv2debugger::Message & msg,
                  const bool expectResponse, const glesv2debugger::Message_Function function);

void Receive(glesv2debugger::Message & cmd);
float Send(const glesv2debugger::Message & msg, glesv2debugger::Message & cmd);
void SetProp(const glesv2debugger::Message & cmd);
}; // namespace android {

/*
 * Copyright 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <cutils/log.h>
#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include "gltrace.pb.h"
#include "gltrace_api.h"
#include "gltrace_context.h"
#include "gltrace_fixup.h"

namespace android {
namespace gltrace {

unsigned getBytesPerTexel(const GLenum format, const GLenum type) {
    /*
    Description from glTexImage2D spec:

    Data is read from data as a sequence of unsigned bytes or shorts, depending on type.
    When type is GL_UNSIGNED_BYTE, each of the bytes is interpreted as one color component.
    When type is one of GL_UNSIGNED_SHORT_5_6_5, GL_UNSIGNED_SHORT_4_4_4_4, or
    GL_UNSIGNED_SHORT_5_5_5_1, each unsigned short value is interpreted as containing all
    the components for a single texel, with the color components arranged according to
    format. Color components are treated as groups of one, two, three, or four values,
    again based on format. Groups of components are referred to as texels.

    width × height texels are read from memory, starting at location data. By default,
    these texels are taken from adjacent memory locations, except that after all width
    texels are read, the read pointer is advanced to the next four-byte boundary.
    The four-byte row alignment is specified by glPixelStorei with argument
    GL_UNPACK_ALIGNMENT, and it can be set to one, two, four, or eight bytes.
    */

    switch (type) {
    case GL_UNSIGNED_SHORT_5_6_5:
    case GL_UNSIGNED_SHORT_4_4_4_4:
    case GL_UNSIGNED_SHORT_5_5_5_1:
        return 2;
    case GL_UNSIGNED_BYTE:
        break;
    default:
        ALOGE("GetBytesPerPixel: unknown type %x", type);
    }

    switch (format) {
    case GL_ALPHA:
    case GL_LUMINANCE:
        return 1;
    case GL_LUMINANCE_ALPHA:
        return 2;
    case GL_RGB:
        return 3;
    case GL_RGBA:
    case 0x80E1: // GL_BGRA_EXT
        return 4;
    default:
        ALOGE("GetBytesPerPixel: unknown format %x", format);
    }

    return 1;   // in doubt...
}

void fixup_GenericFloatArray(int argIndex, int nFloats, GLMessage *glmsg, void *src) {
    GLMessage_DataType *arg_floatarray = glmsg->mutable_args(argIndex);
    GLfloat *floatp = (GLfloat *)src;

    if (floatp == NULL) {
        return;
    }

    arg_floatarray->set_type(GLMessage::DataType::FLOAT);
    arg_floatarray->set_isarray(true);
    arg_floatarray->clear_floatvalue();

    for (int i = 0; i < nFloats; i++, floatp++) {
        arg_floatarray->add_floatvalue(*floatp);
    }
}

void fixup_GenericIntArray(int argIndex, int nInts, GLMessage *glmsg, void *src) {
    GLMessage_DataType *arg_intarray = glmsg->mutable_args(argIndex);
    GLint *intp = (GLint *)src;

    if (intp == NULL) {
        return;
    }

    arg_intarray->set_type(GLMessage::DataType::INT);
    arg_intarray->set_isarray(true);
    arg_intarray->clear_intvalue();

    for (int i = 0; i < nInts; i++, intp++) {
        arg_intarray->add_intvalue(*intp);
    }
}

void fixup_GenericEnumArray(int argIndex, int nEnums, GLMessage *glmsg, void *src) {
    // fixup as if they were ints
    fixup_GenericIntArray(argIndex, nEnums, glmsg, src);

    // and then set the data type to be enum
    GLMessage_DataType *arg_enumarray = glmsg->mutable_args(argIndex);
    arg_enumarray->set_type(GLMessage::DataType::ENUM);
}

/** Generic helper function: extract pointer at argIndex and
    replace it with the C style string at *pointer */
void fixup_CStringPtr(int argIndex, GLMessage *glmsg, void *src) {
    GLMessage_DataType *arg = glmsg->mutable_args(argIndex);
    GLchar *ptr = (GLchar *) src;

    arg->set_type(GLMessage::DataType::CHAR);
    arg->set_isarray(true);
    arg->add_charvalue(ptr);
}

void fixup_glGetString(GLMessage *glmsg, void *pointersToFixup[]) {
    /* const GLubyte* GLTrace_glGetString(GLenum name) */
    GLMessage_DataType *ret = glmsg->mutable_returnvalue();
    GLchar *ptr = (GLchar *) pointersToFixup[0];

    if (ptr != NULL) {
        ret->set_type(GLMessage::DataType::CHAR);
        ret->set_isarray(true);
        ret->add_charvalue(ptr);
    }
}

/* Add the contents of the framebuffer to the protobuf message */
void fixup_addFBContents(GLTraceContext *context, GLMessage *glmsg, FBBinding fbToRead) {
    void *fbcontents;
    unsigned fbsize, fbwidth, fbheight;
    context->getCompressedFB(&fbcontents, &fbsize, &fbwidth, &fbheight, fbToRead);

    GLMessage_FrameBuffer *fb = glmsg->mutable_fb();
    fb->set_width(fbwidth);
    fb->set_height(fbheight);
    fb->add_contents(fbcontents, fbsize);
}

/** Common fixup routing for glTexImage2D & glTexSubImage2D. */
void fixup_glTexImage(int widthIndex, int heightIndex, GLMessage *glmsg, void *dataSrc) {
    GLMessage_DataType arg_width  = glmsg->args(widthIndex);
    GLMessage_DataType arg_height = glmsg->args(heightIndex);

    GLMessage_DataType arg_format = glmsg->args(6);
    GLMessage_DataType arg_type   = glmsg->args(7);
    GLMessage_DataType *arg_data  = glmsg->mutable_args(8);

    GLsizei width  = arg_width.intvalue(0);
    GLsizei height = arg_height.intvalue(0);
    GLenum format  = arg_format.intvalue(0);
    GLenum type    = arg_type.intvalue(0);
    void *data     = (void *) dataSrc;

    int bytesPerTexel = getBytesPerTexel(format, type);

    arg_data->set_type(GLMessage::DataType::BYTE);
    arg_data->clear_rawbytes();

    if (data != NULL) {
        arg_data->set_isarray(true);
        arg_data->add_rawbytes(data, bytesPerTexel * width * height);
    } else {
        arg_data->set_isarray(false);
        arg_data->set_type(GLMessage::DataType::VOID);
    }
}


void fixup_glTexImage2D(GLMessage *glmsg, void *pointersToFixup[]) {
    /* void glTexImage2D(GLenum target,
                        GLint level,
                        GLint internalformat,
                        GLsizei width,
                        GLsizei height,
                        GLint border,
                        GLenum format,
                        GLenum type,
                        const GLvoid *data); 
    */
    int widthIndex = 3;
    int heightIndex = 4;
    fixup_glTexImage(widthIndex, heightIndex, glmsg, pointersToFixup[0]);
}

void fixup_glTexSubImage2D(GLMessage *glmsg, void *pointersToFixup[]) {
    /*
    void glTexSubImage2D(GLenum target,
                        GLint level,
                        GLint xoffset,
                        GLint yoffset,
                        GLsizei width,
                        GLsizei height,
                        GLenum format,
                        GLenum type,
                        const GLvoid * data);
    */
    int widthIndex = 4;
    int heightIndex = 5;
    fixup_glTexImage(widthIndex, heightIndex, glmsg, pointersToFixup[0]);
}

void fixup_glShaderSource(GLMessage *glmsg, void *pointersToFixup[]) {
    /* void glShaderSource(GLuint shader, GLsizei count, const GLchar** string, 
                                    const GLint* length) */
    GLMessage_DataType arg_count  = glmsg->args(1);
    GLMessage_DataType arg_lenp   = glmsg->args(3);
    GLMessage_DataType *arg_strpp = glmsg->mutable_args(2);

    GLsizei count = arg_count.intvalue(0);
    GLchar **stringpp = (GLchar **) pointersToFixup[0];
    GLint *lengthp = (GLint *) pointersToFixup[1];

    arg_strpp->set_type(GLMessage::DataType::CHAR);
    arg_strpp->set_isarray(true);
    arg_strpp->clear_charvalue();

    ::std::string src = "";
    for (int i = 0; i < count; i++) {
        if (lengthp != NULL)
            src.append(*stringpp, *lengthp);
        else
            src.append(*stringpp);  // assume null terminated
        stringpp++;
        lengthp++;
    }

    arg_strpp->add_charvalue(src);
}

void fixup_glUniformGenericInteger(int argIndex, int nIntegers, GLMessage *glmsg,
                                                                    void *pointersToFixup[]) {
    /* void glUniform?iv(GLint location, GLsizei count, const GLint *value); */
    fixup_GenericIntArray(argIndex, nIntegers, glmsg, pointersToFixup[0]);
}

void fixup_glUniformGeneric(int argIndex, int nFloats, GLMessage *glmsg, void *src) {
    fixup_GenericFloatArray(argIndex, nFloats, glmsg, src);
}

void fixup_glUniformMatrixGeneric(int matrixSize, GLMessage *glmsg, void *pointersToFixup[]) {
    /* void glUniformMatrix?fv(GLint location, GLsizei count, GLboolean transpose, 
                                                                const GLfloat* value) */
    GLMessage_DataType arg_count  = glmsg->args(1);
    int n_matrices = arg_count.intvalue(0);
    fixup_glUniformGeneric(3, matrixSize * matrixSize * n_matrices, glmsg, pointersToFixup[0]);
}

void fixup_glBufferData(int sizeIndex, int dataIndex, GLMessage *glmsg, void *pointersToFixup[]) {
    /* void glBufferSubData(GLenum target, GLintptr offset, GLsizeiptr size, const GLvoid* data) */
    /* void glBufferData(GLenum target, GLsizeiptr size, const GLvoid* data, GLenum usage) */
    GLsizeiptr size = glmsg->args(sizeIndex).intvalue(0);

    GLMessage_DataType *arg_datap = glmsg->mutable_args(dataIndex);
    GLvoid *datap = (GLvoid *) pointersToFixup[0];

    if (datap == NULL) {
        // glBufferData can be called with a NULL data pointer
        return;
    }

    arg_datap->set_type(GLMessage::DataType::VOID);
    arg_datap->set_isarray(true);
    arg_datap->clear_intvalue();

    arg_datap->add_rawbytes(datap, size);
}

void fixup_glGenGeneric(GLMessage *glmsg, void *pointersToFixup[]) {
    /* void glGen*(GLsizei n, GLuint * buffers); */
    GLMessage_DataType arg_n  = glmsg->args(0);
    GLsizei n = arg_n.intvalue(0);

    fixup_GenericIntArray(1, n, glmsg, pointersToFixup[0]);
}

void fixup_glDeleteGeneric(GLMessage *glmsg, void *pointersToFixup[]) {
    /* void glDelete*(GLsizei n, GLuint *buffers); */
    GLMessage_DataType arg_n  = glmsg->args(0);
    GLsizei n = arg_n.intvalue(0);

    fixup_GenericIntArray(1, n, glmsg, pointersToFixup[0]);
}

void fixup_glGetBooleanv(GLMessage *glmsg, void *pointersToFixup[]) {
    /* void glGetBooleanv(GLenum pname, GLboolean *params); */
    GLMessage_DataType *arg_params = glmsg->mutable_args(1);
    GLboolean *src = (GLboolean*) pointersToFixup[0];

    arg_params->set_type(GLMessage::DataType::BOOL);
    arg_params->set_isarray(true);
    arg_params->clear_boolvalue();
    arg_params->add_boolvalue(*src);
}

void fixup_glGetFloatv(GLMessage *glmsg, void *pointersToFixup[]) {
    /* void glGetFloatv(GLenum pname, GLfloat *params); */
    GLMessage_DataType *arg_params = glmsg->mutable_args(1);
    GLfloat *src = (GLfloat*) pointersToFixup[0];

    arg_params->set_type(GLMessage::DataType::FLOAT);
    arg_params->set_isarray(true);
    arg_params->clear_floatvalue();
    arg_params->add_floatvalue(*src);
}

void fixup_glLinkProgram(GLMessage *glmsg) {
    /* void glLinkProgram(GLuint program); */
    GLuint program = glmsg->args(0).intvalue(0);

    /* We don't have to fixup this call, but as soon as a program is linked,
       we obtain information about all active attributes and uniforms to
       pass on to the debugger. Note that in order to pass this info to
       the debugger, all we need to do is call the trace versions of the
       necessary calls. */

    GLint n, maxNameLength;
    GLchar *name;
    GLint size;
    GLenum type;

    // obtain info regarding active attributes
    GLTrace_glGetProgramiv(program, GL_ACTIVE_ATTRIBUTES, &n);
    GLTrace_glGetProgramiv(program, GL_ACTIVE_ATTRIBUTE_MAX_LENGTH, &maxNameLength);

    name = (GLchar *) malloc(maxNameLength);
    for (int i = 0; i < n; i++) {
        GLTrace_glGetActiveAttrib(program, i, maxNameLength, NULL, &size, &type, name);
    }
    free(name);

    // obtain info regarding active uniforms
    GLTrace_glGetProgramiv(program, GL_ACTIVE_UNIFORMS, &n);
    GLTrace_glGetProgramiv(program, GL_ACTIVE_UNIFORM_MAX_LENGTH, &maxNameLength);

    name = (GLchar *) malloc(maxNameLength);
    for (int i = 0; i < n; i++) {
        GLTrace_glGetActiveUniform(program, i, maxNameLength, NULL, &size, &type, name);
    }
    free(name);
}

/** Given a glGetActive[Uniform|Attrib] call, obtain the location
 *  of the variable of given name in the call.
 */
int getShaderVariableLocation(GLTraceContext *context, GLMessage *glmsg, GLchar *name) {
    GLMessage_Function func = glmsg->function();
    if (func != GLMessage::glGetActiveAttrib && func != GLMessage::glGetActiveUniform) {
        return -1;
    }

    int program = glmsg->args(0).intvalue(0);

    if (func == GLMessage::glGetActiveAttrib) {
        return context->hooks->gl.glGetAttribLocation(program, name);
    } else {
        return context->hooks->gl.glGetUniformLocation(program, name);
    }
}

void fixup_glGetActiveAttribOrUniform(GLTraceContext *context, GLMessage *glmsg, 
                                                                void *pointersToFixup[]) {
    /* void glGetActiveAttrib(GLuint program, GLuint index, GLsizei bufsize,
                GLsizei* length, GLint* size, GLenum* type, GLchar* name); */
    /* void glGetActiveUniform(GLuint program, GLuint index, GLsizei bufsize,
                GLsizei* length, GLint* size, GLenum* type, GLchar* name) */

    fixup_GenericIntArray(3, 1, glmsg, pointersToFixup[0]);     // length
    fixup_GenericIntArray(4, 1, glmsg, pointersToFixup[1]);     // size
    fixup_GenericEnumArray(5, 1, glmsg, pointersToFixup[2]);    // type
    fixup_CStringPtr(6, glmsg, pointersToFixup[3]);             // name

    // The index argument in the glGetActive[Attrib|Uniform] functions
    // does not correspond to the actual location index as used in
    // glUniform*() or glVertexAttrib*() to actually upload the data.
    // In order to make things simpler for the debugger, we also pass
    // a hidden location argument that stores the actual location.
    // append the location value to the end of the argument list
    int location = getShaderVariableLocation(context, glmsg, (GLchar*)pointersToFixup[3]);
    GLMessage_DataType *arg_location = glmsg->add_args();
    arg_location->set_isarray(false);
    arg_location->set_type(GLMessage::DataType::INT);
    arg_location->add_intvalue(location);
}

void fixupGLMessage(GLTraceContext *context, nsecs_t wallStart, nsecs_t wallEnd,
                                             nsecs_t threadStart, nsecs_t threadEnd,
                                             GLMessage *glmsg, void *pointersToFixup[]) {
    // for all messages, set the current context id
    glmsg->set_context_id(context->getId());

    // set start time and duration
    glmsg->set_start_time(wallStart);
    glmsg->set_duration((unsigned)(wallEnd - wallStart));
    glmsg->set_threadtime((unsigned)(threadEnd - threadStart));

    // do any custom message dependent processing
    switch (glmsg->function()) {
    case GLMessage::glDeleteBuffers:      /* glDeleteBuffers(GLsizei n, GLuint *buffers); */
    case GLMessage::glDeleteFramebuffers: /* glDeleteFramebuffers(GLsizei n, GLuint *buffers); */
    case GLMessage::glDeleteRenderbuffers:/* glDeleteRenderbuffers(GLsizei n, GLuint *buffers); */
    case GLMessage::glDeleteTextures:     /* glDeleteTextures(GLsizei n, GLuint *textures); */
        fixup_glDeleteGeneric(glmsg, pointersToFixup);
        break;
    case GLMessage::glGenBuffers:        /* void glGenBuffers(GLsizei n, GLuint *buffers); */
    case GLMessage::glGenFramebuffers:   /* void glGenFramebuffers(GLsizei n, GLuint *buffers); */
    case GLMessage::glGenRenderbuffers:  /* void glGenFramebuffers(GLsizei n, GLuint *buffers); */
    case GLMessage::glGenTextures:       /* void glGenTextures(GLsizei n, GLuint *textures); */
        fixup_glGenGeneric(glmsg, pointersToFixup);
        break;
    case GLMessage::glLinkProgram:       /* void glLinkProgram(GLuint program); */
        fixup_glLinkProgram(glmsg);
        break;
    case GLMessage::glGetActiveAttrib:
        fixup_glGetActiveAttribOrUniform(context, glmsg, pointersToFixup);
        break;
    case GLMessage::glGetActiveUniform:
        fixup_glGetActiveAttribOrUniform(context, glmsg, pointersToFixup);
        break;
    case GLMessage::glBindAttribLocation:
        /* void glBindAttribLocation(GLuint program, GLuint index, const GLchar* name); */
        fixup_CStringPtr(2, glmsg, pointersToFixup[0]);
        break;
    case GLMessage::glGetAttribLocation:  
    case GLMessage::glGetUniformLocation: 
        /* int glGetAttribLocation(GLuint program, const GLchar* name) */
        /* int glGetUniformLocation(GLuint program, const GLchar* name) */
        fixup_CStringPtr(1, glmsg, pointersToFixup[0]);
        break;
    case GLMessage::glGetBooleanv:
        fixup_glGetBooleanv(glmsg, pointersToFixup);
        break;
    case GLMessage::glGetFloatv:
        fixup_glGetFloatv(glmsg, pointersToFixup);
        break;
    case GLMessage::glGetIntegerv:        /* void glGetIntegerv(GLenum pname, GLint *params); */
        fixup_GenericIntArray(1, 1, glmsg, pointersToFixup[0]);
        break;
    case GLMessage::glGetProgramiv:
    case GLMessage::glGetRenderbufferParameteriv:
    case GLMessage::glGetShaderiv:
        /* void glGetProgramiv(GLuint program, GLenum pname, GLint* params) */
        /* void glGetRenderbufferParameteriv(GLenum target, GLenum pname, GLint* params) */
        /* void glGetShaderiv(GLuint shader, GLenum pname, GLint* params) */
        fixup_GenericIntArray(2, 1, glmsg, pointersToFixup[0]);
        break;
    case GLMessage::glGetString:
        fixup_glGetString(glmsg, pointersToFixup);
        break;
    case GLMessage::glTexImage2D:
        if (context->getGlobalTraceState()->shouldCollectTextureDataOnGlTexImage()) {
            fixup_glTexImage2D(glmsg, pointersToFixup);
        }
        break;
    case GLMessage::glTexSubImage2D:
        if (context->getGlobalTraceState()->shouldCollectTextureDataOnGlTexImage()) {
            fixup_glTexSubImage2D(glmsg, pointersToFixup);
        }
        break;
    case GLMessage::glShaderSource:
        fixup_glShaderSource(glmsg, pointersToFixup);
        break;
    case GLMessage::glUniform1iv:
        /* void glUniform1iv(GLint location, GLsizei count, const GLint *value); */
        fixup_glUniformGenericInteger(2, 1, glmsg, pointersToFixup);
        break;
    case GLMessage::glUniform2iv:
        /* void glUniform2iv(GLint location, GLsizei count, const GLint *value); */
        fixup_glUniformGenericInteger(2, 2, glmsg, pointersToFixup);
        break;
    case GLMessage::glUniform3iv:
        /* void glUniform3iv(GLint location, GLsizei count, const GLint *value); */
        fixup_glUniformGenericInteger(2, 3, glmsg, pointersToFixup);
        break;
    case GLMessage::glUniform4iv:
        /* void glUniform4iv(GLint location, GLsizei count, const GLint *value); */
        fixup_glUniformGenericInteger(2, 4, glmsg, pointersToFixup);
        break;
    case GLMessage::glUniform1fv:
        /* void glUniform1fv(GLint location, GLsizei count, const GLfloat *value); */
        fixup_glUniformGeneric(2, 1, glmsg, pointersToFixup[0]);
        break;
    case GLMessage::glUniform2fv:
        /* void glUniform2fv(GLint location, GLsizei count, const GLfloat *value); */
        fixup_glUniformGeneric(2, 2, glmsg, pointersToFixup[0]);
        break;
    case GLMessage::glUniform3fv:
        /* void glUniform3fv(GLint location, GLsizei count, const GLfloat *value); */
        fixup_glUniformGeneric(2, 3, glmsg, pointersToFixup[0]);
        break;
    case GLMessage::glUniform4fv:
        /* void glUniform4fv(GLint location, GLsizei count, const GLfloat *value); */
        fixup_glUniformGeneric(2, 4, glmsg, pointersToFixup[0]);
        break;
    case GLMessage::glUniformMatrix2fv:
        /* void glUniformMatrix2fv(GLint location, GLsizei count, GLboolean transpose,
                                                                    const GLfloat* value) */
        fixup_glUniformMatrixGeneric(2, glmsg, pointersToFixup);
        break;
    case GLMessage::glUniformMatrix3fv:
        /* void glUniformMatrix2fv(GLint location, GLsizei count, GLboolean transpose,
                                                                    const GLfloat* value) */
        fixup_glUniformMatrixGeneric(3, glmsg, pointersToFixup);
        break;
    case GLMessage::glUniformMatrix4fv:
        /* void glUniformMatrix4fv(GLint location, GLsizei count, GLboolean transpose,
                                                                    const GLfloat* value) */
        fixup_glUniformMatrixGeneric(4, glmsg, pointersToFixup);
        break;
    case GLMessage::glBufferData:
        /* void glBufferData(GLenum target, GLsizeiptr size, const GLvoid* data, GLenum usage) */
        fixup_glBufferData(1, 2, glmsg, pointersToFixup);
        break;
    case GLMessage::glBufferSubData:
        /* void glBufferSubData(GLenum target, GLintptr offset, GLsizeiptr size, const GLvoid* data) */
        fixup_glBufferData(2, 3, glmsg, pointersToFixup);
        break;
    case GLMessage::glDrawArrays:
        /* void glDrawArrays(GLenum mode, GLint first, GLsizei count) */
        if (context->getGlobalTraceState()->shouldCollectFbOnGlDraw()) {
            fixup_addFBContents(context, glmsg, CURRENTLY_BOUND_FB);
        }
        break;
    case GLMessage::glDrawElements:
        /* void glDrawElements(GLenum mode, GLsizei count, GLenum type, const GLvoid* indices) */
        if (context->getGlobalTraceState()->shouldCollectFbOnGlDraw()) {
            fixup_addFBContents(context, glmsg, CURRENTLY_BOUND_FB);
        }
        break;
    case GLMessage::glPushGroupMarkerEXT:
        /* void PushGroupMarkerEXT(sizei length, const char *marker); */
        fixup_CStringPtr(1, glmsg, pointersToFixup[0]);
        break;
    case GLMessage::glInsertEventMarkerEXT:
        /* void InsertEventMarkerEXT(sizei length, const char *marker); */
        fixup_CStringPtr(1, glmsg, pointersToFixup[0]);
        break;
    default:
        break;
    }
}

};
};

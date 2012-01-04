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
#include <GLES2/gl2.h>

#include "gltrace.pb.h"
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
        LOGE("GetBytesPerPixel: unknown type %x", type);
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
        LOGE("GetBytesPerPixel: unknown format %x", format);
    }

    return 1;   // in doubt...
}

/** Generic helper function: extract pointer at argIndex and
    replace it with the C style string at *pointer */
void fixup_CStringPtr(int argIndex, GLMessage *glmsg) {
    GLMessage_DataType *arg = glmsg->mutable_args(argIndex);
    GLchar *ptr = (GLchar *)arg->intvalue(0);

    arg->set_type(GLMessage::DataType::CHAR);
    arg->set_isarray(true);
    arg->add_charvalue(ptr);
}

void fixup_glGetString(GLMessage *glmsg) {
    /* const GLubyte* GLTrace_glGetString(GLenum name) */
    GLMessage_DataType *ret = glmsg->mutable_returnvalue();
    GLchar *ptr = (GLchar *)ret->intvalue(0);

    if (ptr != NULL) {
        ret->set_type(GLMessage::DataType::CHAR);
        ret->set_isarray(true);
        ret->add_charvalue(ptr);
    }
}

/* Add the contents of the framebuffer to the protobuf message */
void fixup_addFBContents(GLMessage *glmsg, FBBinding fbToRead) {
    void *fbcontents;
    unsigned fbsize, fbwidth, fbheight;
    getGLTraceContext()->getCompressedFB(&fbcontents, &fbsize, &fbwidth, &fbheight, fbToRead);

    GLMessage_FrameBuffer *fb = glmsg->mutable_fb();
    fb->set_width(fbwidth);
    fb->set_height(fbheight);
    fb->add_contents(fbcontents, fbsize);
}

void fixup_glTexImage2D(GLMessage *glmsg) {
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
    GLMessage_DataType arg_width  = glmsg->args(3);
    GLMessage_DataType arg_height = glmsg->args(4);
    GLMessage_DataType arg_format = glmsg->args(6);
    GLMessage_DataType arg_type   = glmsg->args(7);
    GLMessage_DataType *arg_data  = glmsg->mutable_args(8);

    GLsizei width  = arg_width.intvalue(0);
    GLsizei height = arg_height.intvalue(0);
    GLenum format  = arg_format.intvalue(0);
    GLenum type    = arg_type.intvalue(0);
    void *data     = (void *)arg_data->intvalue(0);

    int bytesPerTexel = getBytesPerTexel(format, type);

    arg_data->set_type(GLMessage::DataType::BYTE);
    arg_data->set_isarray(true);
    arg_data->clear_rawbytes();

    if (data != NULL) {
        arg_data->add_rawbytes(data, bytesPerTexel * width * height);
    } else {
        LOGE("fixup_glTexImage2D: image data is NULL.\n");
        arg_data->set_type(GLMessage::DataType::VOID);
        // FIXME:
        // This will create the texture, but it will be uninitialized. 
        // It can later be initialized with glTexSubImage2D or by
        // attaching an FBO to it and rendering into the FBO.
    }
}

void fixup_glShaderSource(GLMessage *glmsg) {
    /* void glShaderSource(GLuint shader, GLsizei count, const GLchar** string, 
                                    const GLint* length) */
    GLMessage_DataType arg_count  = glmsg->args(1);
    GLMessage_DataType arg_lenp   = glmsg->args(3);
    GLMessage_DataType *arg_strpp = glmsg->mutable_args(2);

    GLsizei count = arg_count.intvalue(0);
    GLchar **stringpp = (GLchar **)arg_strpp->intvalue(0);
    GLint *lengthp = (GLint *)arg_lenp.intvalue(0);

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

void fixup_glUniformGeneric(int argIndex, int nFloats, GLMessage *glmsg) {
    GLMessage_DataType *arg_values = glmsg->mutable_args(argIndex);
    GLfloat *src = (GLfloat*)arg_values->intvalue(0);

    arg_values->set_type(GLMessage::DataType::FLOAT);
    arg_values->set_isarray(true);
    arg_values->clear_floatvalue();

    for (int i = 0; i < nFloats; i++) {
        arg_values->add_floatvalue(*src++);
    }
}

void fixup_glUniformMatrixGeneric(int matrixSize, GLMessage *glmsg) {
    /* void glUniformMatrix?fv(GLint location, GLsizei count, GLboolean transpose, 
                                                                const GLfloat* value) */
    GLMessage_DataType arg_count  = glmsg->args(1);
    int n_matrices = arg_count.intvalue(0);
    fixup_glUniformGeneric(3, matrixSize * matrixSize * n_matrices, glmsg);
}

void fixup_GenericIntArray(int argIndex, int nInts, GLMessage *glmsg) {
    GLMessage_DataType *arg_intarray = glmsg->mutable_args(argIndex);
    GLint *intp = (GLint *)arg_intarray->intvalue(0);

    arg_intarray->set_type(GLMessage::DataType::INT);
    arg_intarray->set_isarray(true);
    arg_intarray->clear_intvalue();

    for (int i = 0; i < nInts; i++, intp++) {
        arg_intarray->add_intvalue(*intp);
    }
}

void fixup_glGenGeneric(GLMessage *glmsg) {
    /* void glGen*(GLsizei n, GLuint * buffers); */
    GLMessage_DataType arg_n  = glmsg->args(0);
    GLsizei n = arg_n.intvalue(0);

    fixup_GenericIntArray(1, n, glmsg);
}

void fixup_glGetBooleanv(GLMessage *glmsg) {
    /* void glGetBooleanv(GLenum pname, GLboolean *params); */
    GLMessage_DataType *arg_params = glmsg->mutable_args(1);
    GLboolean *src = (GLboolean*)arg_params->intvalue(0);

    arg_params->set_type(GLMessage::DataType::BOOL);
    arg_params->set_isarray(true);
    arg_params->clear_boolvalue();
    arg_params->add_boolvalue(*src);
}

void fixup_glGetFloatv(GLMessage *glmsg) {
    /* void glGetFloatv(GLenum pname, GLfloat *params); */
    GLMessage_DataType *arg_params = glmsg->mutable_args(1);
    GLfloat *src = (GLfloat*)arg_params->intvalue(0);

    arg_params->set_type(GLMessage::DataType::FLOAT);
    arg_params->set_isarray(true);
    arg_params->clear_floatvalue();
    arg_params->add_floatvalue(*src);
}

void fixupGLMessage(GLMessage *glmsg) {
    switch (glmsg->function()) {
    case GLMessage::glGenBuffers:        /* void glGenBuffers(GLsizei n, GLuint * buffers); */
    case GLMessage::glGenFramebuffers:   /* void glGenFramebuffers(GLsizei n, GLuint * buffers); */
    case GLMessage::glGenRenderbuffers:  /* void glGenFramebuffers(GLsizei n, GLuint * buffers); */
    case GLMessage::glGenTextures:       /* void glGenTextures(GLsizei n, GLuint * buffers); */
        fixup_glGenGeneric(glmsg);
        break;
    case GLMessage::glGetAttribLocation:  
    case GLMessage::glGetUniformLocation: 
        /* int glGetAttribLocation(GLuint program, const GLchar* name) */
        /* int glGetUniformLocation(GLuint program, const GLchar* name) */
        fixup_CStringPtr(1, glmsg);
        break;
    case GLMessage::glGetBooleanv:
        fixup_glGetBooleanv(glmsg);
        break;
    case GLMessage::glGetFloatv:
        fixup_glGetFloatv(glmsg);
        break;
    case GLMessage::glGetIntegerv:        /* void glGetIntegerv(GLenum pname, GLint *params); */
        fixup_GenericIntArray(1, 1, glmsg);
        break;
    case GLMessage::glGetProgramiv:
    case GLMessage::glGetRenderbufferParameteriv:
    case GLMessage::glGetShaderiv:
        /* void glGetProgramiv(GLuint program, GLenum pname, GLint* params) */
        /* void glGetRenderbufferParameteriv(GLenum target, GLenum pname, GLint* params) */
        /* void glGetShaderiv(GLuint shader, GLenum pname, GLint* params) */
        fixup_GenericIntArray(2, 1, glmsg);
        break;
    case GLMessage::glGetString:
        fixup_glGetString(glmsg);
        break;
    case GLMessage::glTexImage2D:
        fixup_glTexImage2D(glmsg);
        break;
    case GLMessage::glShaderSource:
        fixup_glShaderSource(glmsg);
        break;
    case GLMessage::glUniformMatrix2fv:
        /* void glUniformMatrix2fv(GLint location, GLsizei count, GLboolean transpose,
                                                                    const GLfloat* value) */
        fixup_glUniformMatrixGeneric(2, glmsg);
        break;
    case GLMessage::glUniformMatrix3fv:
        /* void glUniformMatrix2fv(GLint location, GLsizei count, GLboolean transpose,
                                                                    const GLfloat* value) */
        fixup_glUniformMatrixGeneric(3, glmsg);
        break;
    case GLMessage::glUniformMatrix4fv:
        /* void glUniformMatrix4fv(GLint location, GLsizei count, GLboolean transpose,
                                                                    const GLfloat* value) */
        fixup_glUniformMatrixGeneric(4, glmsg);
        break;
    case GLMessage::glDrawArrays:
    case GLMessage::glDrawElements:
        /* void glDrawArrays(GLenum mode, GLint first, GLsizei count) */
        /* void glDrawElements(GLenum mode, GLsizei count, GLenum type, const GLvoid* indices) */
        fixup_addFBContents(glmsg, CURRENTLY_BOUND_FB);
        break;
    default:
        break;
    }
}

};
};

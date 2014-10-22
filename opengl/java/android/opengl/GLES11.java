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

package android.opengl;

import java.nio.Buffer;

public class GLES11 extends GLES10 {
    public static final int GL_ACTIVE_TEXTURE                          = 0x84E0;
    public static final int GL_ADD_SIGNED                              = 0x8574;
    public static final int GL_ALPHA_SCALE                             = 0x0D1C;
    public static final int GL_ALPHA_TEST_FUNC                         = 0x0BC1;
    public static final int GL_ALPHA_TEST_REF                          = 0x0BC2;
    public static final int GL_ARRAY_BUFFER                            = 0x8892;
    public static final int GL_ARRAY_BUFFER_BINDING                    = 0x8894;
    public static final int GL_BLEND_DST                               = 0x0BE0;
    public static final int GL_BLEND_SRC                               = 0x0BE1;
    public static final int GL_BUFFER_ACCESS                           = 0x88BB;
    public static final int GL_BUFFER_SIZE                             = 0x8764;
    public static final int GL_BUFFER_USAGE                            = 0x8765;
    public static final int GL_CLIENT_ACTIVE_TEXTURE                   = 0x84E1;
    public static final int GL_CLIP_PLANE0                             = 0x3000;
    public static final int GL_CLIP_PLANE1                             = 0x3001;
    public static final int GL_CLIP_PLANE2                             = 0x3002;
    public static final int GL_CLIP_PLANE3                             = 0x3003;
    public static final int GL_CLIP_PLANE4                             = 0x3004;
    public static final int GL_CLIP_PLANE5                             = 0x3005;
    public static final int GL_COLOR_ARRAY_BUFFER_BINDING              = 0x8898;
    public static final int GL_COLOR_ARRAY_POINTER                     = 0x8090;
    public static final int GL_COLOR_ARRAY_SIZE                        = 0x8081;
    public static final int GL_COLOR_ARRAY_STRIDE                      = 0x8083;
    public static final int GL_COLOR_ARRAY_TYPE                        = 0x8082;
    public static final int GL_COLOR_CLEAR_VALUE                       = 0x0C22;
    public static final int GL_COLOR_WRITEMASK                         = 0x0C23;
    public static final int GL_COMBINE                                 = 0x8570;
    public static final int GL_COMBINE_ALPHA                           = 0x8572;
    public static final int GL_COMBINE_RGB                             = 0x8571;
    public static final int GL_CONSTANT                                = 0x8576;
    public static final int GL_COORD_REPLACE_OES                       = 0x8862;
    public static final int GL_CULL_FACE_MODE                          = 0x0B45;
    public static final int GL_CURRENT_COLOR                           = 0x0B00;
    public static final int GL_CURRENT_NORMAL                          = 0x0B02;
    public static final int GL_CURRENT_TEXTURE_COORDS                  = 0x0B03;
    public static final int GL_DEPTH_CLEAR_VALUE                       = 0x0B73;
    public static final int GL_DEPTH_FUNC                              = 0x0B74;
    public static final int GL_DEPTH_RANGE                             = 0x0B70;
    public static final int GL_DEPTH_WRITEMASK                         = 0x0B72;
    public static final int GL_DOT3_RGB                                = 0x86AE;
    public static final int GL_DOT3_RGBA                               = 0x86AF;
    public static final int GL_DYNAMIC_DRAW                            = 0x88E8;
    public static final int GL_ELEMENT_ARRAY_BUFFER                    = 0x8893;
    public static final int GL_ELEMENT_ARRAY_BUFFER_BINDING            = 0x8895;
    public static final int GL_FRONT_FACE                              = 0x0B46;
    public static final int GL_GENERATE_MIPMAP                         = 0x8191;
    public static final int GL_GENERATE_MIPMAP_HINT                    = 0x8192;
    public static final int GL_INTERPOLATE                             = 0x8575;
    public static final int GL_LINE_WIDTH                              = 0x0B21;
    public static final int GL_LOGIC_OP_MODE                           = 0x0BF0;
    public static final int GL_MATRIX_MODE                             = 0x0BA0;
    public static final int GL_MAX_CLIP_PLANES                         = 0x0D32;
    public static final int GL_MODELVIEW_MATRIX                        = 0x0BA6;
    public static final int GL_MODELVIEW_MATRIX_FLOAT_AS_INT_BITS_OES  = 0x898D;
    public static final int GL_MODELVIEW_STACK_DEPTH                   = 0x0BA3;
    public static final int GL_NORMAL_ARRAY_BUFFER_BINDING             = 0x8897;
    public static final int GL_NORMAL_ARRAY_POINTER                    = 0x808F;
    public static final int GL_NORMAL_ARRAY_STRIDE                     = 0x807F;
    public static final int GL_NORMAL_ARRAY_TYPE                       = 0x807E;
    public static final int GL_OPERAND0_ALPHA                          = 0x8598;
    public static final int GL_OPERAND0_RGB                            = 0x8590;
    public static final int GL_OPERAND1_ALPHA                          = 0x8599;
    public static final int GL_OPERAND1_RGB                            = 0x8591;
    public static final int GL_OPERAND2_ALPHA                          = 0x859A;
    public static final int GL_OPERAND2_RGB                            = 0x8592;
    public static final int GL_POINT_DISTANCE_ATTENUATION              = 0x8129;
    public static final int GL_POINT_FADE_THRESHOLD_SIZE               = 0x8128;
    public static final int GL_POINT_SIZE                              = 0x0B11;
    public static final int GL_POINT_SIZE_ARRAY_BUFFER_BINDING_OES     = 0x8B9F;
    public static final int GL_POINT_SIZE_ARRAY_OES                    = 0x8B9C;
    public static final int GL_POINT_SIZE_ARRAY_POINTER_OES            = 0x898C;
    public static final int GL_POINT_SIZE_ARRAY_STRIDE_OES             = 0x898B;
    public static final int GL_POINT_SIZE_ARRAY_TYPE_OES               = 0x898A;
    public static final int GL_POINT_SIZE_MAX                          = 0x8127;
    public static final int GL_POINT_SIZE_MIN                          = 0x8126;
    public static final int GL_POINT_SPRITE_OES                        = 0x8861;
    public static final int GL_POLYGON_OFFSET_FACTOR                   = 0x8038;
    public static final int GL_POLYGON_OFFSET_UNITS                    = 0x2A00;
    public static final int GL_PREVIOUS                                = 0x8578;
    public static final int GL_PRIMARY_COLOR                           = 0x8577;
    public static final int GL_PROJECTION_MATRIX                       = 0x0BA7;
    public static final int GL_PROJECTION_MATRIX_FLOAT_AS_INT_BITS_OES = 0x898E;
    public static final int GL_PROJECTION_STACK_DEPTH                  = 0x0BA4;
    public static final int GL_RGB_SCALE                               = 0x8573;
    public static final int GL_SAMPLE_BUFFERS                          = 0x80A8;
    public static final int GL_SAMPLE_COVERAGE_INVERT                  = 0x80AB;
    public static final int GL_SAMPLE_COVERAGE_VALUE                   = 0x80AA;
    public static final int GL_SAMPLES                                 = 0x80A9;
    public static final int GL_SCISSOR_BOX                             = 0x0C10;
    public static final int GL_SHADE_MODEL                             = 0x0B54;
    public static final int GL_SRC0_ALPHA                              = 0x8588;
    public static final int GL_SRC0_RGB                                = 0x8580;
    public static final int GL_SRC1_ALPHA                              = 0x8589;
    public static final int GL_SRC1_RGB                                = 0x8581;
    public static final int GL_SRC2_ALPHA                              = 0x858A;
    public static final int GL_SRC2_RGB                                = 0x8582;
    public static final int GL_STATIC_DRAW                             = 0x88E4;
    public static final int GL_STENCIL_CLEAR_VALUE                     = 0x0B91;
    public static final int GL_STENCIL_FAIL                            = 0x0B94;
    public static final int GL_STENCIL_FUNC                            = 0x0B92;
    public static final int GL_STENCIL_PASS_DEPTH_FAIL                 = 0x0B95;
    public static final int GL_STENCIL_PASS_DEPTH_PASS                 = 0x0B96;
    public static final int GL_STENCIL_REF                             = 0x0B97;
    public static final int GL_STENCIL_VALUE_MASK                      = 0x0B93;
    public static final int GL_STENCIL_WRITEMASK                       = 0x0B98;
    public static final int GL_SUBTRACT                                = 0x84E7;
    public static final int GL_TEXTURE_BINDING_2D                      = 0x8069;
    public static final int GL_TEXTURE_COORD_ARRAY_BUFFER_BINDING      = 0x889A;
    public static final int GL_TEXTURE_COORD_ARRAY_POINTER             = 0x8092;
    public static final int GL_TEXTURE_COORD_ARRAY_SIZE                = 0x8088;
    public static final int GL_TEXTURE_COORD_ARRAY_STRIDE              = 0x808A;
    public static final int GL_TEXTURE_COORD_ARRAY_TYPE                = 0x8089;
    public static final int GL_TEXTURE_MATRIX                          = 0x0BA8;
    public static final int GL_TEXTURE_MATRIX_FLOAT_AS_INT_BITS_OES    = 0x898F;
    public static final int GL_TEXTURE_STACK_DEPTH                     = 0x0BA5;
    public static final int GL_VERTEX_ARRAY_BUFFER_BINDING             = 0x8896;
    public static final int GL_VERTEX_ARRAY_POINTER                    = 0x808E;
    public static final int GL_VERTEX_ARRAY_SIZE                       = 0x807A;
    public static final int GL_VERTEX_ARRAY_STRIDE                     = 0x807C;
    public static final int GL_VERTEX_ARRAY_TYPE                       = 0x807B;
    public static final int GL_VIEWPORT                                = 0x0BA2;
    public static final int GL_WRITE_ONLY                              = 0x88B9;

    native private static void _nativeClassInit();
    static {
        _nativeClassInit();
    }

    private static Buffer _pointSizePointerOES;
    // C function void glBindBuffer ( GLenum target, GLuint buffer )

    public static native void glBindBuffer(
        int target,
        int buffer
    );

    // C function void glBufferData ( GLenum target, GLsizeiptr size, const GLvoid *data, GLenum usage )

    public static native void glBufferData(
        int target,
        int size,
        java.nio.Buffer data,
        int usage
    );

    // C function void glBufferSubData ( GLenum target, GLintptr offset, GLsizeiptr size, const GLvoid *data )

    public static native void glBufferSubData(
        int target,
        int offset,
        int size,
        java.nio.Buffer data
    );

    // C function void glClipPlanef ( GLenum plane, const GLfloat *equation )

    public static native void glClipPlanef(
        int plane,
        float[] equation,
        int offset
    );

    // C function void glClipPlanef ( GLenum plane, const GLfloat *equation )

    public static native void glClipPlanef(
        int plane,
        java.nio.FloatBuffer equation
    );

    // C function void glClipPlanex ( GLenum plane, const GLfixed *equation )

    public static native void glClipPlanex(
        int plane,
        int[] equation,
        int offset
    );

    // C function void glClipPlanex ( GLenum plane, const GLfixed *equation )

    public static native void glClipPlanex(
        int plane,
        java.nio.IntBuffer equation
    );

    // C function void glColor4ub ( GLubyte red, GLubyte green, GLubyte blue, GLubyte alpha )

    public static native void glColor4ub(
        byte red,
        byte green,
        byte blue,
        byte alpha
    );

    // C function void glColorPointer ( GLint size, GLenum type, GLsizei stride, GLint offset )

    public static native void glColorPointer(
        int size,
        int type,
        int stride,
        int offset
    );

    // C function void glDeleteBuffers ( GLsizei n, const GLuint *buffers )

    public static native void glDeleteBuffers(
        int n,
        int[] buffers,
        int offset
    );

    // C function void glDeleteBuffers ( GLsizei n, const GLuint *buffers )

    public static native void glDeleteBuffers(
        int n,
        java.nio.IntBuffer buffers
    );

    // C function void glDrawElements ( GLenum mode, GLsizei count, GLenum type, GLint offset )

    public static native void glDrawElements(
        int mode,
        int count,
        int type,
        int offset
    );

    // C function void glGenBuffers ( GLsizei n, GLuint *buffers )

    public static native void glGenBuffers(
        int n,
        int[] buffers,
        int offset
    );

    // C function void glGenBuffers ( GLsizei n, GLuint *buffers )

    public static native void glGenBuffers(
        int n,
        java.nio.IntBuffer buffers
    );

    // C function void glGetBooleanv ( GLenum pname, GLboolean *params )

    public static native void glGetBooleanv(
        int pname,
        boolean[] params,
        int offset
    );

    // C function void glGetBooleanv ( GLenum pname, GLboolean *params )

    public static native void glGetBooleanv(
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetBufferParameteriv ( GLenum target, GLenum pname, GLint *params )

    public static native void glGetBufferParameteriv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetBufferParameteriv ( GLenum target, GLenum pname, GLint *params )

    public static native void glGetBufferParameteriv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetClipPlanef ( GLenum pname, GLfloat *eqn )

    public static native void glGetClipPlanef(
        int pname,
        float[] eqn,
        int offset
    );

    // C function void glGetClipPlanef ( GLenum pname, GLfloat *eqn )

    public static native void glGetClipPlanef(
        int pname,
        java.nio.FloatBuffer eqn
    );

    // C function void glGetClipPlanex ( GLenum pname, GLfixed *eqn )

    public static native void glGetClipPlanex(
        int pname,
        int[] eqn,
        int offset
    );

    // C function void glGetClipPlanex ( GLenum pname, GLfixed *eqn )

    public static native void glGetClipPlanex(
        int pname,
        java.nio.IntBuffer eqn
    );

    // C function void glGetFixedv ( GLenum pname, GLfixed *params )

    public static native void glGetFixedv(
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetFixedv ( GLenum pname, GLfixed *params )

    public static native void glGetFixedv(
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetFloatv ( GLenum pname, GLfloat *params )

    public static native void glGetFloatv(
        int pname,
        float[] params,
        int offset
    );

    // C function void glGetFloatv ( GLenum pname, GLfloat *params )

    public static native void glGetFloatv(
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glGetLightfv ( GLenum light, GLenum pname, GLfloat *params )

    public static native void glGetLightfv(
        int light,
        int pname,
        float[] params,
        int offset
    );

    // C function void glGetLightfv ( GLenum light, GLenum pname, GLfloat *params )

    public static native void glGetLightfv(
        int light,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glGetLightxv ( GLenum light, GLenum pname, GLfixed *params )

    public static native void glGetLightxv(
        int light,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetLightxv ( GLenum light, GLenum pname, GLfixed *params )

    public static native void glGetLightxv(
        int light,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetMaterialfv ( GLenum face, GLenum pname, GLfloat *params )

    public static native void glGetMaterialfv(
        int face,
        int pname,
        float[] params,
        int offset
    );

    // C function void glGetMaterialfv ( GLenum face, GLenum pname, GLfloat *params )

    public static native void glGetMaterialfv(
        int face,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glGetMaterialxv ( GLenum face, GLenum pname, GLfixed *params )

    public static native void glGetMaterialxv(
        int face,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetMaterialxv ( GLenum face, GLenum pname, GLfixed *params )

    public static native void glGetMaterialxv(
        int face,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetTexEnvfv ( GLenum env, GLenum pname, GLfloat *params )

    public static native void glGetTexEnvfv(
        int env,
        int pname,
        float[] params,
        int offset
    );

    // C function void glGetTexEnvfv ( GLenum env, GLenum pname, GLfloat *params )

    public static native void glGetTexEnvfv(
        int env,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glGetTexEnviv ( GLenum env, GLenum pname, GLint *params )

    public static native void glGetTexEnviv(
        int env,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetTexEnviv ( GLenum env, GLenum pname, GLint *params )

    public static native void glGetTexEnviv(
        int env,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetTexEnvxv ( GLenum env, GLenum pname, GLfixed *params )

    public static native void glGetTexEnvxv(
        int env,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetTexEnvxv ( GLenum env, GLenum pname, GLfixed *params )

    public static native void glGetTexEnvxv(
        int env,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetTexParameterfv ( GLenum target, GLenum pname, GLfloat *params )

    public static native void glGetTexParameterfv(
        int target,
        int pname,
        float[] params,
        int offset
    );

    // C function void glGetTexParameterfv ( GLenum target, GLenum pname, GLfloat *params )

    public static native void glGetTexParameterfv(
        int target,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glGetTexParameteriv ( GLenum target, GLenum pname, GLint *params )

    public static native void glGetTexParameteriv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetTexParameteriv ( GLenum target, GLenum pname, GLint *params )

    public static native void glGetTexParameteriv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetTexParameterxv ( GLenum target, GLenum pname, GLfixed *params )

    public static native void glGetTexParameterxv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetTexParameterxv ( GLenum target, GLenum pname, GLfixed *params )

    public static native void glGetTexParameterxv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function GLboolean glIsBuffer ( GLuint buffer )

    public static native boolean glIsBuffer(
        int buffer
    );

    // C function GLboolean glIsEnabled ( GLenum cap )

    public static native boolean glIsEnabled(
        int cap
    );

    // C function GLboolean glIsTexture ( GLuint texture )

    public static native boolean glIsTexture(
        int texture
    );

    // C function void glNormalPointer ( GLenum type, GLsizei stride, GLint offset )

    public static native void glNormalPointer(
        int type,
        int stride,
        int offset
    );

    // C function void glPointParameterf ( GLenum pname, GLfloat param )

    public static native void glPointParameterf(
        int pname,
        float param
    );

    // C function void glPointParameterfv ( GLenum pname, const GLfloat *params )

    public static native void glPointParameterfv(
        int pname,
        float[] params,
        int offset
    );

    // C function void glPointParameterfv ( GLenum pname, const GLfloat *params )

    public static native void glPointParameterfv(
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glPointParameterx ( GLenum pname, GLfixed param )

    public static native void glPointParameterx(
        int pname,
        int param
    );

    // C function void glPointParameterxv ( GLenum pname, const GLfixed *params )

    public static native void glPointParameterxv(
        int pname,
        int[] params,
        int offset
    );

    // C function void glPointParameterxv ( GLenum pname, const GLfixed *params )

    public static native void glPointParameterxv(
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glPointSizePointerOES ( GLenum type, GLsizei stride, const GLvoid *pointer )

    private static native void glPointSizePointerOESBounds(
        int type,
        int stride,
        java.nio.Buffer pointer,
        int remaining
    );

    public static void glPointSizePointerOES(
        int type,
        int stride,
        java.nio.Buffer pointer
    ) {
        glPointSizePointerOESBounds(
            type,
            stride,
            pointer,
            pointer.remaining()
        );
        if (((type == GL_FLOAT) ||
             (type == GL_FIXED)) &&
            (stride >= 0)) {
            _pointSizePointerOES = pointer;
        }
    }

    // C function void glTexCoordPointer ( GLint size, GLenum type, GLsizei stride, GLint offset )

    public static native void glTexCoordPointer(
        int size,
        int type,
        int stride,
        int offset
    );

    // C function void glTexEnvi ( GLenum target, GLenum pname, GLint param )

    public static native void glTexEnvi(
        int target,
        int pname,
        int param
    );

    // C function void glTexEnviv ( GLenum target, GLenum pname, const GLint *params )

    public static native void glTexEnviv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glTexEnviv ( GLenum target, GLenum pname, const GLint *params )

    public static native void glTexEnviv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glTexParameterfv ( GLenum target, GLenum pname, const GLfloat *params )

    public static native void glTexParameterfv(
        int target,
        int pname,
        float[] params,
        int offset
    );

    // C function void glTexParameterfv ( GLenum target, GLenum pname, const GLfloat *params )

    public static native void glTexParameterfv(
        int target,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glTexParameteri ( GLenum target, GLenum pname, GLint param )

    public static native void glTexParameteri(
        int target,
        int pname,
        int param
    );

    // C function void glTexParameteriv ( GLenum target, GLenum pname, const GLint *params )

    public static native void glTexParameteriv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glTexParameteriv ( GLenum target, GLenum pname, const GLint *params )

    public static native void glTexParameteriv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glTexParameterxv ( GLenum target, GLenum pname, const GLfixed *params )

    public static native void glTexParameterxv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glTexParameterxv ( GLenum target, GLenum pname, const GLfixed *params )

    public static native void glTexParameterxv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glVertexPointer ( GLint size, GLenum type, GLsizei stride, GLint offset )

    public static native void glVertexPointer(
        int size,
        int type,
        int stride,
        int offset
    );

}

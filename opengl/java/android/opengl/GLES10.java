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

public class GLES10 {
    public static final int GL_ADD                                   = 0x0104;
    public static final int GL_ALIASED_LINE_WIDTH_RANGE              = 0x846E;
    public static final int GL_ALIASED_POINT_SIZE_RANGE              = 0x846D;
    public static final int GL_ALPHA                                 = 0x1906;
    public static final int GL_ALPHA_BITS                            = 0x0D55;
    public static final int GL_ALPHA_TEST                            = 0x0BC0;
    public static final int GL_ALWAYS                                = 0x0207;
    public static final int GL_AMBIENT                               = 0x1200;
    public static final int GL_AMBIENT_AND_DIFFUSE                   = 0x1602;
    public static final int GL_AND                                   = 0x1501;
    public static final int GL_AND_INVERTED                          = 0x1504;
    public static final int GL_AND_REVERSE                           = 0x1502;
    public static final int GL_BACK                                  = 0x0405;
    public static final int GL_BLEND                                 = 0x0BE2;
    public static final int GL_BLUE_BITS                             = 0x0D54;
    public static final int GL_BYTE                                  = 0x1400;
    public static final int GL_CCW                                   = 0x0901;
    public static final int GL_CLAMP_TO_EDGE                         = 0x812F;
    public static final int GL_CLEAR                                 = 0x1500;
    public static final int GL_COLOR_ARRAY                           = 0x8076;
    public static final int GL_COLOR_BUFFER_BIT                      = 0x4000;
    public static final int GL_COLOR_LOGIC_OP                        = 0x0BF2;
    public static final int GL_COLOR_MATERIAL                        = 0x0B57;
    public static final int GL_COMPRESSED_TEXTURE_FORMATS            = 0x86A3;
    public static final int GL_CONSTANT_ATTENUATION                  = 0x1207;
    public static final int GL_COPY                                  = 0x1503;
    public static final int GL_COPY_INVERTED                         = 0x150C;
    public static final int GL_CULL_FACE                             = 0x0B44;
    public static final int GL_CW                                    = 0x0900;
    public static final int GL_DECAL                                 = 0x2101;
    public static final int GL_DECR                                  = 0x1E03;
    public static final int GL_DEPTH_BITS                            = 0x0D56;
    public static final int GL_DEPTH_BUFFER_BIT                      = 0x0100;
    public static final int GL_DEPTH_TEST                            = 0x0B71;
    public static final int GL_DIFFUSE                               = 0x1201;
    public static final int GL_DITHER                                = 0x0BD0;
    public static final int GL_DONT_CARE                             = 0x1100;
    public static final int GL_DST_ALPHA                             = 0x0304;
    public static final int GL_DST_COLOR                             = 0x0306;
    public static final int GL_EMISSION                              = 0x1600;
    public static final int GL_EQUAL                                 = 0x0202;
    public static final int GL_EQUIV                                 = 0x1509;
    public static final int GL_EXP                                   = 0x0800;
    public static final int GL_EXP2                                  = 0x0801;
    public static final int GL_EXTENSIONS                            = 0x1F03;
    public static final int GL_FALSE                                 = 0;
    public static final int GL_FASTEST                               = 0x1101;
    public static final int GL_FIXED                                 = 0x140C;
    public static final int GL_FLAT                                  = 0x1D00;
    public static final int GL_FLOAT                                 = 0x1406;
    public static final int GL_FOG                                   = 0x0B60;
    public static final int GL_FOG_COLOR                             = 0x0B66;
    public static final int GL_FOG_DENSITY                           = 0x0B62;
    public static final int GL_FOG_END                               = 0x0B64;
    public static final int GL_FOG_HINT                              = 0x0C54;
    public static final int GL_FOG_MODE                              = 0x0B65;
    public static final int GL_FOG_START                             = 0x0B63;
    public static final int GL_FRONT                                 = 0x0404;
    public static final int GL_FRONT_AND_BACK                        = 0x0408;
    public static final int GL_GEQUAL                                = 0x0206;
    public static final int GL_GREATER                               = 0x0204;
    public static final int GL_GREEN_BITS                            = 0x0D53;
    public static final int GL_IMPLEMENTATION_COLOR_READ_FORMAT_OES  = 0x8B9B;
    public static final int GL_IMPLEMENTATION_COLOR_READ_TYPE_OES    = 0x8B9A;
    public static final int GL_INCR                                  = 0x1E02;
    public static final int GL_INVALID_ENUM                          = 0x0500;
    public static final int GL_INVALID_OPERATION                     = 0x0502;
    public static final int GL_INVALID_VALUE                         = 0x0501;
    public static final int GL_INVERT                                = 0x150A;
    public static final int GL_KEEP                                  = 0x1E00;
    public static final int GL_LEQUAL                                = 0x0203;
    public static final int GL_LESS                                  = 0x0201;
    public static final int GL_LIGHT_MODEL_AMBIENT                   = 0x0B53;
    public static final int GL_LIGHT_MODEL_TWO_SIDE                  = 0x0B52;
    public static final int GL_LIGHT0                                = 0x4000;
    public static final int GL_LIGHT1                                = 0x4001;
    public static final int GL_LIGHT2                                = 0x4002;
    public static final int GL_LIGHT3                                = 0x4003;
    public static final int GL_LIGHT4                                = 0x4004;
    public static final int GL_LIGHT5                                = 0x4005;
    public static final int GL_LIGHT6                                = 0x4006;
    public static final int GL_LIGHT7                                = 0x4007;
    public static final int GL_LIGHTING                              = 0x0B50;
    public static final int GL_LINE_LOOP                             = 0x0002;
    public static final int GL_LINE_SMOOTH                           = 0x0B20;
    public static final int GL_LINE_SMOOTH_HINT                      = 0x0C52;
    public static final int GL_LINE_STRIP                            = 0x0003;
    public static final int GL_LINEAR                                = 0x2601;
    public static final int GL_LINEAR_ATTENUATION                    = 0x1208;
    public static final int GL_LINEAR_MIPMAP_LINEAR                  = 0x2703;
    public static final int GL_LINEAR_MIPMAP_NEAREST                 = 0x2701;
    public static final int GL_LINES                                 = 0x0001;
    public static final int GL_LUMINANCE                             = 0x1909;
    public static final int GL_LUMINANCE_ALPHA                       = 0x190A;
    public static final int GL_MAX_ELEMENTS_INDICES                  = 0x80E9;
    public static final int GL_MAX_ELEMENTS_VERTICES                 = 0x80E8;
    public static final int GL_MAX_LIGHTS                            = 0x0D31;
    public static final int GL_MAX_MODELVIEW_STACK_DEPTH             = 0x0D36;
    public static final int GL_MAX_PROJECTION_STACK_DEPTH            = 0x0D38;
    public static final int GL_MAX_TEXTURE_SIZE                      = 0x0D33;
    public static final int GL_MAX_TEXTURE_STACK_DEPTH               = 0x0D39;
    public static final int GL_MAX_TEXTURE_UNITS                     = 0x84E2;
    public static final int GL_MAX_VIEWPORT_DIMS                     = 0x0D3A;
    public static final int GL_MODELVIEW                             = 0x1700;
    public static final int GL_MODULATE                              = 0x2100;
    public static final int GL_MULTISAMPLE                           = 0x809D;
    public static final int GL_NAND                                  = 0x150E;
    public static final int GL_NEAREST                               = 0x2600;
    public static final int GL_NEAREST_MIPMAP_LINEAR                 = 0x2702;
    public static final int GL_NEAREST_MIPMAP_NEAREST                = 0x2700;
    public static final int GL_NEVER                                 = 0x0200;
    public static final int GL_NICEST                                = 0x1102;
    public static final int GL_NO_ERROR                              = 0;
    public static final int GL_NOOP                                  = 0x1505;
    public static final int GL_NOR                                   = 0x1508;
    public static final int GL_NORMAL_ARRAY                          = 0x8075;
    public static final int GL_NORMALIZE                             = 0x0BA1;
    public static final int GL_NOTEQUAL                              = 0x0205;
    public static final int GL_NUM_COMPRESSED_TEXTURE_FORMATS        = 0x86A2;
    public static final int GL_ONE                                   = 1;
    public static final int GL_ONE_MINUS_DST_ALPHA                   = 0x0305;
    public static final int GL_ONE_MINUS_DST_COLOR                   = 0x0307;
    public static final int GL_ONE_MINUS_SRC_ALPHA                   = 0x0303;
    public static final int GL_ONE_MINUS_SRC_COLOR                   = 0x0301;
    public static final int GL_OR                                    = 0x1507;
    public static final int GL_OR_INVERTED                           = 0x150D;
    public static final int GL_OR_REVERSE                            = 0x150B;
    public static final int GL_OUT_OF_MEMORY                         = 0x0505;
    public static final int GL_PACK_ALIGNMENT                        = 0x0D05;
    public static final int GL_PALETTE4_R5_G6_B5_OES                 = 0x8B92;
    public static final int GL_PALETTE4_RGB5_A1_OES                  = 0x8B94;
    public static final int GL_PALETTE4_RGB8_OES                     = 0x8B90;
    public static final int GL_PALETTE4_RGBA4_OES                    = 0x8B93;
    public static final int GL_PALETTE4_RGBA8_OES                    = 0x8B91;
    public static final int GL_PALETTE8_R5_G6_B5_OES                 = 0x8B97;
    public static final int GL_PALETTE8_RGB5_A1_OES                  = 0x8B99;
    public static final int GL_PALETTE8_RGB8_OES                     = 0x8B95;
    public static final int GL_PALETTE8_RGBA4_OES                    = 0x8B98;
    public static final int GL_PALETTE8_RGBA8_OES                    = 0x8B96;
    public static final int GL_PERSPECTIVE_CORRECTION_HINT           = 0x0C50;
    public static final int GL_POINT_SMOOTH                          = 0x0B10;
    public static final int GL_POINT_SMOOTH_HINT                     = 0x0C51;
    public static final int GL_POINTS                                = 0x0000;
    public static final int GL_POINT_FADE_THRESHOLD_SIZE             = 0x8128;
    public static final int GL_POINT_SIZE                            = 0x0B11;
    public static final int GL_POLYGON_OFFSET_FILL                   = 0x8037;
    public static final int GL_POLYGON_SMOOTH_HINT                   = 0x0C53;
    public static final int GL_POSITION                              = 0x1203;
    public static final int GL_PROJECTION                            = 0x1701;
    public static final int GL_QUADRATIC_ATTENUATION                 = 0x1209;
    public static final int GL_RED_BITS                              = 0x0D52;
    public static final int GL_RENDERER                              = 0x1F01;
    public static final int GL_REPEAT                                = 0x2901;
    public static final int GL_REPLACE                               = 0x1E01;
    public static final int GL_RESCALE_NORMAL                        = 0x803A;
    public static final int GL_RGB                                   = 0x1907;
    public static final int GL_RGBA                                  = 0x1908;
    public static final int GL_SAMPLE_ALPHA_TO_COVERAGE              = 0x809E;
    public static final int GL_SAMPLE_ALPHA_TO_ONE                   = 0x809F;
    public static final int GL_SAMPLE_COVERAGE                       = 0x80A0;
    public static final int GL_SCISSOR_TEST                          = 0x0C11;
    public static final int GL_SET                                   = 0x150F;
    public static final int GL_SHININESS                             = 0x1601;
    public static final int GL_SHORT                                 = 0x1402;
    public static final int GL_SMOOTH                                = 0x1D01;
    public static final int GL_SMOOTH_LINE_WIDTH_RANGE               = 0x0B22;
    public static final int GL_SMOOTH_POINT_SIZE_RANGE               = 0x0B12;
    public static final int GL_SPECULAR                              = 0x1202;
    public static final int GL_SPOT_CUTOFF                           = 0x1206;
    public static final int GL_SPOT_DIRECTION                        = 0x1204;
    public static final int GL_SPOT_EXPONENT                         = 0x1205;
    public static final int GL_SRC_ALPHA                             = 0x0302;
    public static final int GL_SRC_ALPHA_SATURATE                    = 0x0308;
    public static final int GL_SRC_COLOR                             = 0x0300;
    public static final int GL_STACK_OVERFLOW                        = 0x0503;
    public static final int GL_STACK_UNDERFLOW                       = 0x0504;
    public static final int GL_STENCIL_BITS                          = 0x0D57;
    public static final int GL_STENCIL_BUFFER_BIT                    = 0x0400;
    public static final int GL_STENCIL_TEST                          = 0x0B90;
    public static final int GL_SUBPIXEL_BITS                         = 0x0D50;
    public static final int GL_TEXTURE                               = 0x1702;
    public static final int GL_TEXTURE_2D                            = 0x0DE1;
    public static final int GL_TEXTURE_COORD_ARRAY                   = 0x8078;
    public static final int GL_TEXTURE_ENV                           = 0x2300;
    public static final int GL_TEXTURE_ENV_COLOR                     = 0x2201;
    public static final int GL_TEXTURE_ENV_MODE                      = 0x2200;
    public static final int GL_TEXTURE_MAG_FILTER                    = 0x2800;
    public static final int GL_TEXTURE_MIN_FILTER                    = 0x2801;
    public static final int GL_TEXTURE_WRAP_S                        = 0x2802;
    public static final int GL_TEXTURE_WRAP_T                        = 0x2803;
    public static final int GL_TEXTURE0                              = 0x84C0;
    public static final int GL_TEXTURE1                              = 0x84C1;
    public static final int GL_TEXTURE2                              = 0x84C2;
    public static final int GL_TEXTURE3                              = 0x84C3;
    public static final int GL_TEXTURE4                              = 0x84C4;
    public static final int GL_TEXTURE5                              = 0x84C5;
    public static final int GL_TEXTURE6                              = 0x84C6;
    public static final int GL_TEXTURE7                              = 0x84C7;
    public static final int GL_TEXTURE8                              = 0x84C8;
    public static final int GL_TEXTURE9                              = 0x84C9;
    public static final int GL_TEXTURE10                             = 0x84CA;
    public static final int GL_TEXTURE11                             = 0x84CB;
    public static final int GL_TEXTURE12                             = 0x84CC;
    public static final int GL_TEXTURE13                             = 0x84CD;
    public static final int GL_TEXTURE14                             = 0x84CE;
    public static final int GL_TEXTURE15                             = 0x84CF;
    public static final int GL_TEXTURE16                             = 0x84D0;
    public static final int GL_TEXTURE17                             = 0x84D1;
    public static final int GL_TEXTURE18                             = 0x84D2;
    public static final int GL_TEXTURE19                             = 0x84D3;
    public static final int GL_TEXTURE20                             = 0x84D4;
    public static final int GL_TEXTURE21                             = 0x84D5;
    public static final int GL_TEXTURE22                             = 0x84D6;
    public static final int GL_TEXTURE23                             = 0x84D7;
    public static final int GL_TEXTURE24                             = 0x84D8;
    public static final int GL_TEXTURE25                             = 0x84D9;
    public static final int GL_TEXTURE26                             = 0x84DA;
    public static final int GL_TEXTURE27                             = 0x84DB;
    public static final int GL_TEXTURE28                             = 0x84DC;
    public static final int GL_TEXTURE29                             = 0x84DD;
    public static final int GL_TEXTURE30                             = 0x84DE;
    public static final int GL_TEXTURE31                             = 0x84DF;
    public static final int GL_TRIANGLE_FAN                          = 0x0006;
    public static final int GL_TRIANGLE_STRIP                        = 0x0005;
    public static final int GL_TRIANGLES                             = 0x0004;
    public static final int GL_TRUE                                  = 1;
    public static final int GL_UNPACK_ALIGNMENT                      = 0x0CF5;
    public static final int GL_UNSIGNED_BYTE                         = 0x1401;
    public static final int GL_UNSIGNED_SHORT                        = 0x1403;
    public static final int GL_UNSIGNED_SHORT_4_4_4_4                = 0x8033;
    public static final int GL_UNSIGNED_SHORT_5_5_5_1                = 0x8034;
    public static final int GL_UNSIGNED_SHORT_5_6_5                  = 0x8363;
    public static final int GL_VENDOR                                = 0x1F00;
    public static final int GL_VERSION                               = 0x1F02;
    public static final int GL_VERTEX_ARRAY                          = 0x8074;
    public static final int GL_XOR                                   = 0x1506;
    public static final int GL_ZERO                                  = 0;

    native private static void _nativeClassInit();
    static {
        _nativeClassInit();
    }

    private static Buffer _colorPointer;
    private static Buffer _normalPointer;
    private static Buffer _texCoordPointer;
    private static Buffer _vertexPointer;

    // C function void glActiveTexture ( GLenum texture )

    public static native void glActiveTexture(
        int texture
    );

    // C function void glAlphaFunc ( GLenum func, GLclampf ref )

    public static native void glAlphaFunc(
        int func,
        float ref
    );

    // C function void glAlphaFuncx ( GLenum func, GLclampx ref )

    public static native void glAlphaFuncx(
        int func,
        int ref
    );

    // C function void glBindTexture ( GLenum target, GLuint texture )

    public static native void glBindTexture(
        int target,
        int texture
    );

    // C function void glBlendFunc ( GLenum sfactor, GLenum dfactor )

    public static native void glBlendFunc(
        int sfactor,
        int dfactor
    );

    // C function void glClear ( GLbitfield mask )

    public static native void glClear(
        int mask
    );

    // C function void glClearColor ( GLclampf red, GLclampf green, GLclampf blue, GLclampf alpha )

    public static native void glClearColor(
        float red,
        float green,
        float blue,
        float alpha
    );

    // C function void glClearColorx ( GLclampx red, GLclampx green, GLclampx blue, GLclampx alpha )

    public static native void glClearColorx(
        int red,
        int green,
        int blue,
        int alpha
    );

    // C function void glClearDepthf ( GLclampf depth )

    public static native void glClearDepthf(
        float depth
    );

    // C function void glClearDepthx ( GLclampx depth )

    public static native void glClearDepthx(
        int depth
    );

    // C function void glClearStencil ( GLint s )

    public static native void glClearStencil(
        int s
    );

    // C function void glClientActiveTexture ( GLenum texture )

    public static native void glClientActiveTexture(
        int texture
    );

    // C function void glColor4f ( GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha )

    public static native void glColor4f(
        float red,
        float green,
        float blue,
        float alpha
    );

    // C function void glColor4x ( GLfixed red, GLfixed green, GLfixed blue, GLfixed alpha )

    public static native void glColor4x(
        int red,
        int green,
        int blue,
        int alpha
    );

    // C function void glColorMask ( GLboolean red, GLboolean green, GLboolean blue, GLboolean alpha )

    public static native void glColorMask(
        boolean red,
        boolean green,
        boolean blue,
        boolean alpha
    );

    // C function void glColorPointer ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer )

    private static native void glColorPointerBounds(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer,
        int remaining
    );

    public static void glColorPointer(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer
    ) {
        glColorPointerBounds(
            size,
            type,
            stride,
            pointer,
            pointer.remaining()
        );
        if ((size == 4) &&
            ((type == GL_FLOAT) ||
             (type == GL_UNSIGNED_BYTE) ||
             (type == GL_FIXED)) &&
            (stride >= 0)) {
            _colorPointer = pointer;
        }
    }

    // C function void glCompressedTexImage2D ( GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height, GLint border, GLsizei imageSize, const GLvoid *data )

    public static native void glCompressedTexImage2D(
        int target,
        int level,
        int internalformat,
        int width,
        int height,
        int border,
        int imageSize,
        java.nio.Buffer data
    );

    // C function void glCompressedTexSubImage2D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLsizei imageSize, const GLvoid *data )

    public static native void glCompressedTexSubImage2D(
        int target,
        int level,
        int xoffset,
        int yoffset,
        int width,
        int height,
        int format,
        int imageSize,
        java.nio.Buffer data
    );

    // C function void glCopyTexImage2D ( GLenum target, GLint level, GLenum internalformat, GLint x, GLint y, GLsizei width, GLsizei height, GLint border )

    public static native void glCopyTexImage2D(
        int target,
        int level,
        int internalformat,
        int x,
        int y,
        int width,
        int height,
        int border
    );

    // C function void glCopyTexSubImage2D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint x, GLint y, GLsizei width, GLsizei height )

    public static native void glCopyTexSubImage2D(
        int target,
        int level,
        int xoffset,
        int yoffset,
        int x,
        int y,
        int width,
        int height
    );

    // C function void glCullFace ( GLenum mode )

    public static native void glCullFace(
        int mode
    );

    // C function void glDeleteTextures ( GLsizei n, const GLuint *textures )

    public static native void glDeleteTextures(
        int n,
        int[] textures,
        int offset
    );

    // C function void glDeleteTextures ( GLsizei n, const GLuint *textures )

    public static native void glDeleteTextures(
        int n,
        java.nio.IntBuffer textures
    );

    // C function void glDepthFunc ( GLenum func )

    public static native void glDepthFunc(
        int func
    );

    // C function void glDepthMask ( GLboolean flag )

    public static native void glDepthMask(
        boolean flag
    );

    // C function void glDepthRangef ( GLclampf zNear, GLclampf zFar )

    public static native void glDepthRangef(
        float zNear,
        float zFar
    );

    // C function void glDepthRangex ( GLclampx zNear, GLclampx zFar )

    public static native void glDepthRangex(
        int zNear,
        int zFar
    );

    // C function void glDisable ( GLenum cap )

    public static native void glDisable(
        int cap
    );

    // C function void glDisableClientState ( GLenum array )

    public static native void glDisableClientState(
        int array
    );

    // C function void glDrawArrays ( GLenum mode, GLint first, GLsizei count )

    public static native void glDrawArrays(
        int mode,
        int first,
        int count
    );

    // C function void glDrawElements ( GLenum mode, GLsizei count, GLenum type, const GLvoid *indices )

    public static native void glDrawElements(
        int mode,
        int count,
        int type,
        java.nio.Buffer indices
    );

    // C function void glEnable ( GLenum cap )

    public static native void glEnable(
        int cap
    );

    // C function void glEnableClientState ( GLenum array )

    public static native void glEnableClientState(
        int array
    );

    // C function void glFinish ( void )

    public static native void glFinish(
    );

    // C function void glFlush ( void )

    public static native void glFlush(
    );

    // C function void glFogf ( GLenum pname, GLfloat param )

    public static native void glFogf(
        int pname,
        float param
    );

    // C function void glFogfv ( GLenum pname, const GLfloat *params )

    public static native void glFogfv(
        int pname,
        float[] params,
        int offset
    );

    // C function void glFogfv ( GLenum pname, const GLfloat *params )

    public static native void glFogfv(
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glFogx ( GLenum pname, GLfixed param )

    public static native void glFogx(
        int pname,
        int param
    );

    // C function void glFogxv ( GLenum pname, const GLfixed *params )

    public static native void glFogxv(
        int pname,
        int[] params,
        int offset
    );

    // C function void glFogxv ( GLenum pname, const GLfixed *params )

    public static native void glFogxv(
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glFrontFace ( GLenum mode )

    public static native void glFrontFace(
        int mode
    );

    // C function void glFrustumf ( GLfloat left, GLfloat right, GLfloat bottom, GLfloat top, GLfloat zNear, GLfloat zFar )

    public static native void glFrustumf(
        float left,
        float right,
        float bottom,
        float top,
        float zNear,
        float zFar
    );

    // C function void glFrustumx ( GLfixed left, GLfixed right, GLfixed bottom, GLfixed top, GLfixed zNear, GLfixed zFar )

    public static native void glFrustumx(
        int left,
        int right,
        int bottom,
        int top,
        int zNear,
        int zFar
    );

    // C function void glGenTextures ( GLsizei n, GLuint *textures )

    public static native void glGenTextures(
        int n,
        int[] textures,
        int offset
    );

    // C function void glGenTextures ( GLsizei n, GLuint *textures )

    public static native void glGenTextures(
        int n,
        java.nio.IntBuffer textures
    );

    // C function GLenum glGetError ( void )

    public static native int glGetError(
    );

    // C function void glGetIntegerv ( GLenum pname, GLint *params )

    public static native void glGetIntegerv(
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetIntegerv ( GLenum pname, GLint *params )

    public static native void glGetIntegerv(
        int pname,
        java.nio.IntBuffer params
    );

    // C function const GLubyte * glGetString ( GLenum name )

    public static native String glGetString(
        int name
    );
    // C function void glHint ( GLenum target, GLenum mode )

    public static native void glHint(
        int target,
        int mode
    );

    // C function void glLightModelf ( GLenum pname, GLfloat param )

    public static native void glLightModelf(
        int pname,
        float param
    );

    // C function void glLightModelfv ( GLenum pname, const GLfloat *params )

    public static native void glLightModelfv(
        int pname,
        float[] params,
        int offset
    );

    // C function void glLightModelfv ( GLenum pname, const GLfloat *params )

    public static native void glLightModelfv(
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glLightModelx ( GLenum pname, GLfixed param )

    public static native void glLightModelx(
        int pname,
        int param
    );

    // C function void glLightModelxv ( GLenum pname, const GLfixed *params )

    public static native void glLightModelxv(
        int pname,
        int[] params,
        int offset
    );

    // C function void glLightModelxv ( GLenum pname, const GLfixed *params )

    public static native void glLightModelxv(
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glLightf ( GLenum light, GLenum pname, GLfloat param )

    public static native void glLightf(
        int light,
        int pname,
        float param
    );

    // C function void glLightfv ( GLenum light, GLenum pname, const GLfloat *params )

    public static native void glLightfv(
        int light,
        int pname,
        float[] params,
        int offset
    );

    // C function void glLightfv ( GLenum light, GLenum pname, const GLfloat *params )

    public static native void glLightfv(
        int light,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glLightx ( GLenum light, GLenum pname, GLfixed param )

    public static native void glLightx(
        int light,
        int pname,
        int param
    );

    // C function void glLightxv ( GLenum light, GLenum pname, const GLfixed *params )

    public static native void glLightxv(
        int light,
        int pname,
        int[] params,
        int offset
    );

    // C function void glLightxv ( GLenum light, GLenum pname, const GLfixed *params )

    public static native void glLightxv(
        int light,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glLineWidth ( GLfloat width )

    public static native void glLineWidth(
        float width
    );

    // C function void glLineWidthx ( GLfixed width )

    public static native void glLineWidthx(
        int width
    );

    // C function void glLoadIdentity ( void )

    public static native void glLoadIdentity(
    );

    // C function void glLoadMatrixf ( const GLfloat *m )

    public static native void glLoadMatrixf(
        float[] m,
        int offset
    );

    // C function void glLoadMatrixf ( const GLfloat *m )

    public static native void glLoadMatrixf(
        java.nio.FloatBuffer m
    );

    // C function void glLoadMatrixx ( const GLfixed *m )

    public static native void glLoadMatrixx(
        int[] m,
        int offset
    );

    // C function void glLoadMatrixx ( const GLfixed *m )

    public static native void glLoadMatrixx(
        java.nio.IntBuffer m
    );

    // C function void glLogicOp ( GLenum opcode )

    public static native void glLogicOp(
        int opcode
    );

    // C function void glMaterialf ( GLenum face, GLenum pname, GLfloat param )

    public static native void glMaterialf(
        int face,
        int pname,
        float param
    );

    // C function void glMaterialfv ( GLenum face, GLenum pname, const GLfloat *params )

    public static native void glMaterialfv(
        int face,
        int pname,
        float[] params,
        int offset
    );

    // C function void glMaterialfv ( GLenum face, GLenum pname, const GLfloat *params )

    public static native void glMaterialfv(
        int face,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glMaterialx ( GLenum face, GLenum pname, GLfixed param )

    public static native void glMaterialx(
        int face,
        int pname,
        int param
    );

    // C function void glMaterialxv ( GLenum face, GLenum pname, const GLfixed *params )

    public static native void glMaterialxv(
        int face,
        int pname,
        int[] params,
        int offset
    );

    // C function void glMaterialxv ( GLenum face, GLenum pname, const GLfixed *params )

    public static native void glMaterialxv(
        int face,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glMatrixMode ( GLenum mode )

    public static native void glMatrixMode(
        int mode
    );

    // C function void glMultMatrixf ( const GLfloat *m )

    public static native void glMultMatrixf(
        float[] m,
        int offset
    );

    // C function void glMultMatrixf ( const GLfloat *m )

    public static native void glMultMatrixf(
        java.nio.FloatBuffer m
    );

    // C function void glMultMatrixx ( const GLfixed *m )

    public static native void glMultMatrixx(
        int[] m,
        int offset
    );

    // C function void glMultMatrixx ( const GLfixed *m )

    public static native void glMultMatrixx(
        java.nio.IntBuffer m
    );

    // C function void glMultiTexCoord4f ( GLenum target, GLfloat s, GLfloat t, GLfloat r, GLfloat q )

    public static native void glMultiTexCoord4f(
        int target,
        float s,
        float t,
        float r,
        float q
    );

    // C function void glMultiTexCoord4x ( GLenum target, GLfixed s, GLfixed t, GLfixed r, GLfixed q )

    public static native void glMultiTexCoord4x(
        int target,
        int s,
        int t,
        int r,
        int q
    );

    // C function void glNormal3f ( GLfloat nx, GLfloat ny, GLfloat nz )

    public static native void glNormal3f(
        float nx,
        float ny,
        float nz
    );

    // C function void glNormal3x ( GLfixed nx, GLfixed ny, GLfixed nz )

    public static native void glNormal3x(
        int nx,
        int ny,
        int nz
    );

    // C function void glNormalPointer ( GLenum type, GLsizei stride, const GLvoid *pointer )

    private static native void glNormalPointerBounds(
        int type,
        int stride,
        java.nio.Buffer pointer,
        int remaining
    );

    public static void glNormalPointer(
        int type,
        int stride,
        java.nio.Buffer pointer
    ) {
        glNormalPointerBounds(
            type,
            stride,
            pointer,
            pointer.remaining()
        );
        if (((type == GL_FLOAT) ||
             (type == GL_BYTE) ||
             (type == GL_SHORT) ||
             (type == GL_FIXED)) &&
            (stride >= 0)) {
            _normalPointer = pointer;
        }
    }

    // C function void glOrthof ( GLfloat left, GLfloat right, GLfloat bottom, GLfloat top, GLfloat zNear, GLfloat zFar )

    public static native void glOrthof(
        float left,
        float right,
        float bottom,
        float top,
        float zNear,
        float zFar
    );

    // C function void glOrthox ( GLfixed left, GLfixed right, GLfixed bottom, GLfixed top, GLfixed zNear, GLfixed zFar )

    public static native void glOrthox(
        int left,
        int right,
        int bottom,
        int top,
        int zNear,
        int zFar
    );

    // C function void glPixelStorei ( GLenum pname, GLint param )

    public static native void glPixelStorei(
        int pname,
        int param
    );

    // C function void glPointSize ( GLfloat size )

    public static native void glPointSize(
        float size
    );

    // C function void glPointSizex ( GLfixed size )

    public static native void glPointSizex(
        int size
    );

    // C function void glPolygonOffset ( GLfloat factor, GLfloat units )

    public static native void glPolygonOffset(
        float factor,
        float units
    );

    // C function void glPolygonOffsetx ( GLfixed factor, GLfixed units )

    public static native void glPolygonOffsetx(
        int factor,
        int units
    );

    // C function void glPopMatrix ( void )

    public static native void glPopMatrix(
    );

    // C function void glPushMatrix ( void )

    public static native void glPushMatrix(
    );

    // C function void glReadPixels ( GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, GLvoid *pixels )

    public static native void glReadPixels(
        int x,
        int y,
        int width,
        int height,
        int format,
        int type,
        java.nio.Buffer pixels
    );

    // C function void glRotatef ( GLfloat angle, GLfloat x, GLfloat y, GLfloat z )

    public static native void glRotatef(
        float angle,
        float x,
        float y,
        float z
    );

    // C function void glRotatex ( GLfixed angle, GLfixed x, GLfixed y, GLfixed z )

    public static native void glRotatex(
        int angle,
        int x,
        int y,
        int z
    );

    // C function void glSampleCoverage ( GLclampf value, GLboolean invert )

    public static native void glSampleCoverage(
        float value,
        boolean invert
    );

    // C function void glSampleCoveragex ( GLclampx value, GLboolean invert )

    public static native void glSampleCoveragex(
        int value,
        boolean invert
    );

    // C function void glScalef ( GLfloat x, GLfloat y, GLfloat z )

    public static native void glScalef(
        float x,
        float y,
        float z
    );

    // C function void glScalex ( GLfixed x, GLfixed y, GLfixed z )

    public static native void glScalex(
        int x,
        int y,
        int z
    );

    // C function void glScissor ( GLint x, GLint y, GLsizei width, GLsizei height )

    public static native void glScissor(
        int x,
        int y,
        int width,
        int height
    );

    // C function void glShadeModel ( GLenum mode )

    public static native void glShadeModel(
        int mode
    );

    // C function void glStencilFunc ( GLenum func, GLint ref, GLuint mask )

    public static native void glStencilFunc(
        int func,
        int ref,
        int mask
    );

    // C function void glStencilMask ( GLuint mask )

    public static native void glStencilMask(
        int mask
    );

    // C function void glStencilOp ( GLenum fail, GLenum zfail, GLenum zpass )

    public static native void glStencilOp(
        int fail,
        int zfail,
        int zpass
    );

    // C function void glTexCoordPointer ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer )

    private static native void glTexCoordPointerBounds(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer,
        int remaining
    );

    public static void glTexCoordPointer(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer
    ) {
        glTexCoordPointerBounds(
            size,
            type,
            stride,
            pointer,
            pointer.remaining()
        );
        if (((size == 2) ||
             (size == 3) ||
             (size == 4)) &&
            ((type == GL_FLOAT) ||
             (type == GL_BYTE) ||
             (type == GL_SHORT) ||
             (type == GL_FIXED)) &&
            (stride >= 0)) {
            _texCoordPointer = pointer;
        }
    }

    // C function void glTexEnvf ( GLenum target, GLenum pname, GLfloat param )

    public static native void glTexEnvf(
        int target,
        int pname,
        float param
    );

    // C function void glTexEnvfv ( GLenum target, GLenum pname, const GLfloat *params )

    public static native void glTexEnvfv(
        int target,
        int pname,
        float[] params,
        int offset
    );

    // C function void glTexEnvfv ( GLenum target, GLenum pname, const GLfloat *params )

    public static native void glTexEnvfv(
        int target,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glTexEnvx ( GLenum target, GLenum pname, GLfixed param )

    public static native void glTexEnvx(
        int target,
        int pname,
        int param
    );

    // C function void glTexEnvxv ( GLenum target, GLenum pname, const GLfixed *params )

    public static native void glTexEnvxv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glTexEnvxv ( GLenum target, GLenum pname, const GLfixed *params )

    public static native void glTexEnvxv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glTexImage2D ( GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLint border, GLenum format, GLenum type, const GLvoid *pixels )

    public static native void glTexImage2D(
        int target,
        int level,
        int internalformat,
        int width,
        int height,
        int border,
        int format,
        int type,
        java.nio.Buffer pixels
    );

    // C function void glTexParameterf ( GLenum target, GLenum pname, GLfloat param )

    public static native void glTexParameterf(
        int target,
        int pname,
        float param
    );

    // C function void glTexParameterx ( GLenum target, GLenum pname, GLfixed param )

    public static native void glTexParameterx(
        int target,
        int pname,
        int param
    );

    // C function void glTexSubImage2D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const GLvoid *pixels )

    public static native void glTexSubImage2D(
        int target,
        int level,
        int xoffset,
        int yoffset,
        int width,
        int height,
        int format,
        int type,
        java.nio.Buffer pixels
    );

    // C function void glTranslatef ( GLfloat x, GLfloat y, GLfloat z )

    public static native void glTranslatef(
        float x,
        float y,
        float z
    );

    // C function void glTranslatex ( GLfixed x, GLfixed y, GLfixed z )

    public static native void glTranslatex(
        int x,
        int y,
        int z
    );

    // C function void glVertexPointer ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer )

    private static native void glVertexPointerBounds(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer,
        int remaining
    );

    public static void glVertexPointer(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer
    ) {
        glVertexPointerBounds(
            size,
            type,
            stride,
            pointer,
            pointer.remaining()
        );
        if (((size == 2) ||
             (size == 3) ||
             (size == 4)) &&
            ((type == GL_FLOAT) ||
             (type == GL_BYTE) ||
             (type == GL_SHORT) ||
             (type == GL_FIXED)) &&
            (stride >= 0)) {
            _vertexPointer = pointer;
        }
    }

    // C function void glViewport ( GLint x, GLint y, GLsizei width, GLsizei height )

    public static native void glViewport(
        int x,
        int y,
        int width,
        int height
    );

}

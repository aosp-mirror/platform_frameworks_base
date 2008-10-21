/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef __gl_h_
#define __gl_h_

#include <stdint.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

/*****************************************************************************/

typedef int8_t              GLbyte;         // b
typedef int16_t             GLshort;        // s
typedef int32_t             GLint;          // i
typedef ssize_t             GLsizei;        // i
typedef int32_t             GLfixed;        // x
typedef int32_t             GLclampx;       // x
typedef float               GLfloat;        // f
typedef float               GLclampf;       // f
typedef uint8_t             GLubyte;        // ub
typedef uint8_t             GLboolean;      // ub
typedef uint16_t            GLushort;       // us
typedef uint32_t            GLuint;         // ui
typedef unsigned int        GLenum;         // ui
typedef unsigned int        GLbitfield;     // ui
typedef void                GLvoid;
typedef intptr_t            GLintptr; 
typedef int                 GLsizeiptr; 
typedef GLintptr            GLintptrARB; 
typedef GLsizeiptr          GLsizeiptrARB; 

/*****************************************************************************/

#define GL_VERSION_ES_CM_1_0                1
#define GL_VERSION_ES_CL_1_0                1
#define GL_VERSION_ES_CM_1_1                1
#define GL_VERSION_ES_CL_1_1                1

#define GL_OES_byte_coordinates             1
#define GL_OES_fixed_point                  1
#define GL_OES_single_precision             1
#define GL_OES_read_format                  1
#define GL_OES_compressed_paletted_texture  1
#define GL_OES_draw_texture                 1
#define GL_OES_matrix_get                   1
#define GL_OES_query_matrix                 1
#define GL_OES_vertex_buffer_object         1
#define GL_OES_point_size_array             1
#define GL_OES_point_sprite                 1
#define GL_ARB_texture_non_power_of_two     1

/*****************************************************************************/
/* OpenGL ES 1.0 names */

#define GL_FALSE                            0
#define GL_TRUE                             1

/* begin mode */
#define GL_POINTS                           0x0000
#define GL_LINES                            0x0001
#define GL_LINE_LOOP                        0x0002
#define GL_LINE_STRIP                       0x0003
#define GL_TRIANGLES                        0x0004
#define GL_TRIANGLE_STRIP                   0x0005
#define GL_TRIANGLE_FAN                     0x0006

/* clear mask  */
#define GL_DEPTH_BUFFER_BIT                 0x00000100
#define GL_STENCIL_BUFFER_BIT               0x00000400
#define GL_COLOR_BUFFER_BIT                 0x00004000

/* enable/disable */
#define GL_FOG                              0x0B60
#define GL_LIGHTING                         0x0B50
#define GL_TEXTURE_2D                       0x0DE1
#define GL_CULL_FACE                        0x0B44
#define GL_ALPHA_TEST                       0x0BC0
#define GL_BLEND                            0x0BE2
#define GL_COLOR_LOGIC_OP                   0x0BF2
#define GL_DITHER                           0x0BD0
#define GL_STENCIL_TEST                     0x0B90
#define GL_DEPTH_TEST                       0x0B71
#define GL_POINT_SMOOTH                     0x0B10
#define GL_LINE_SMOOTH                      0x0B20
#define GL_SCISSOR_TEST                     0x0C11
#define GL_COLOR_MATERIAL                   0x0B57
#define GL_NORMALIZE                        0x0BA1
#define GL_RESCALE_NORMAL                   0x803A
#define GL_POLYGON_OFFSET_FILL              0x8037
#define GL_VERTEX_ARRAY                     0x8074
#define GL_NORMAL_ARRAY                     0x8075
#define GL_COLOR_ARRAY                      0x8076
#define GL_TEXTURE_COORD_ARRAY              0x8078
#define GL_MULTISAMPLE                      0x809D
#define GL_SAMPLE_ALPHA_TO_COVERAGE         0x809E
#define GL_SAMPLE_ALPHA_TO_ONE              0x809F
#define GL_SAMPLE_COVERAGE                  0x80A0

/* gets */
#define GL_SMOOTH_POINT_SIZE_RANGE          0x0B12
#define GL_SMOOTH_LINE_WIDTH_RANGE          0x0B22
#define GL_ALIASED_POINT_SIZE_RANGE         0x846D
#define GL_ALIASED_LINE_WIDTH_RANGE         0x846E
#define GL_MAX_LIGHTS                       0x0D31
#define GL_MAX_CLIP_PLANES                  0x0D32
#define GL_MAX_TEXTURE_SIZE                 0x0D33
#define GL_MAX_MODELVIEW_STACK_DEPTH        0x0D36
#define GL_MAX_PROJECTION_STACK_DEPTH       0x0D38
#define GL_MAX_TEXTURE_STACK_DEPTH          0x0D39
#define GL_MAX_VIEWPORT_DIMS                0x0D3A
#define GL_MAX_ELEMENTS_VERTICES            0x80E8
#define GL_MAX_ELEMENTS_INDICES             0x80E9
#define GL_MAX_TEXTURE_UNITS                0x84E2
#define GL_NUM_COMPRESSED_TEXTURE_FORMATS   0x86A2
#define GL_COMPRESSED_TEXTURE_FORMATS       0x86A3
#define GL_SUBPIXEL_BITS                    0x0D50
#define GL_RED_BITS                         0x0D52
#define GL_GREEN_BITS                       0x0D53
#define GL_BLUE_BITS                        0x0D54
#define GL_ALPHA_BITS                       0x0D55
#define GL_DEPTH_BITS                       0x0D56
#define GL_STENCIL_BITS                     0x0D57

/* clip planes */
#define GL_CLIP_PLANE0                      0x3000
#define GL_CLIP_PLANE1                      0x3001
#define GL_CLIP_PLANE2                      0x3002
#define GL_CLIP_PLANE3                      0x3003
#define GL_CLIP_PLANE4                      0x3004
#define GL_CLIP_PLANE5                      0x3005

/* errors */
#define GL_NO_ERROR                         0
#define GL_INVALID_ENUM                     0x0500
#define GL_INVALID_VALUE                    0x0501
#define GL_INVALID_OPERATION                0x0502
#define GL_STACK_OVERFLOW                   0x0503
#define GL_STACK_UNDERFLOW                  0x0504
#define GL_OUT_OF_MEMORY                    0x0505

/* fog */
#define GL_EXP                              0x0800
#define GL_EXP2                             0x0801
#define GL_FOG_DENSITY                      0x0B62
#define GL_FOG_START                        0x0B63
#define GL_FOG_END                          0x0B64
#define GL_FOG_MODE                         0x0B65
#define GL_FOG_COLOR                        0x0B66

/* culling */
#define GL_CW                               0x0900
#define GL_CCW                              0x0901

#define GL_FRONT                            0x0404
#define GL_BACK                             0x0405
#define GL_FRONT_AND_BACK                   0x0408

/* hints */
#define GL_DONT_CARE                        0x1100
#define GL_FASTEST                          0x1101
#define GL_NICEST                           0x1102

#define GL_PERSPECTIVE_CORRECTION_HINT      0x0C50
#define GL_POINT_SMOOTH_HINT                0x0C51
#define GL_LINE_SMOOTH_HINT                 0x0C52
#define GL_POLYGON_SMOOTH_HINT              0x0C53
#define GL_FOG_HINT                         0x0C54
#define GL_GENERATE_MIPMAP_HINT             0x8192

/* lights */
#define GL_LIGHT_MODEL_AMBIENT              0x0B53
#define GL_LIGHT_MODEL_TWO_SIDE             0x0B52

#define GL_AMBIENT                          0x1200
#define GL_DIFFUSE                          0x1201
#define GL_SPECULAR                         0x1202
#define GL_POSITION                         0x1203
#define GL_SPOT_DIRECTION                   0x1204
#define GL_SPOT_EXPONENT                    0x1205
#define GL_SPOT_CUTOFF                      0x1206
#define GL_CONSTANT_ATTENUATION             0x1207
#define GL_LINEAR_ATTENUATION               0x1208
#define GL_QUADRATIC_ATTENUATION            0x1209

#define GL_LIGHT0                           0x4000
#define GL_LIGHT1                           0x4001
#define GL_LIGHT2                           0x4002
#define GL_LIGHT3                           0x4003
#define GL_LIGHT4                           0x4004
#define GL_LIGHT5                           0x4005
#define GL_LIGHT6                           0x4006
#define GL_LIGHT7                           0x4007

/* material */
#define GL_EMISSION                         0x1600
#define GL_SHININESS                        0x1601
#define GL_AMBIENT_AND_DIFFUSE              0x1602

/* matrix */
#define GL_MODELVIEW                        0x1700
#define GL_PROJECTION                       0x1701
#define GL_TEXTURE                          0x1702

/* types */
#define GL_BYTE                             0x1400
#define GL_UNSIGNED_BYTE                    0x1401
#define GL_SHORT                            0x1402
#define GL_UNSIGNED_SHORT                   0x1403
#define GL_FLOAT                            0x1406
#define GL_FIXED                            0x140C

/* pixel formats */
#define GL_ALPHA                            0x1906
#define GL_RGB                              0x1907
#define GL_RGBA                             0x1908
#define GL_LUMINANCE                        0x1909
#define GL_LUMINANCE_ALPHA                  0x190A

/* pixel store */
#define GL_UNPACK_ALIGNMENT                 0x0CF5
#define GL_PACK_ALIGNMENT                   0x0D05

/* pixel types */
#define GL_UNSIGNED_SHORT_4_4_4_4           0x8033
#define GL_UNSIGNED_SHORT_5_5_5_1           0x8034
#define GL_UNSIGNED_SHORT_5_6_5             0x8363

/* logic op */
#define GL_CLEAR                            0x1500   // 0
#define GL_AND                              0x1501   // s & d
#define GL_AND_REVERSE                      0x1502   // s & ~d
#define GL_COPY                             0x1503   // s
#define GL_AND_INVERTED                     0x1504   // ~s & d
#define GL_NOOP                             0x1505   // d
#define GL_XOR                              0x1506   // s ^ d
#define GL_OR                               0x1507   // s | d
#define GL_NOR                              0x1508   // ~(s | d)
#define GL_EQUIV                            0x1509   // ~(s ^ d)
#define GL_INVERT                           0x150A   // ~d
#define GL_OR_REVERSE                       0x150B   // s | ~d
#define GL_COPY_INVERTED                    0x150C   // ~s 
#define GL_OR_INVERTED                      0x150D   // ~s | d
#define GL_NAND                             0x150E   // ~(s & d)
#define GL_SET                              0x150F   // 1

/* shade model */
#define GL_FLAT                             0x1D00
#define GL_SMOOTH                           0x1D01

/* strings */
#define GL_VENDOR                           0x1F00
#define GL_RENDERER                         0x1F01
#define GL_VERSION                          0x1F02
#define GL_EXTENSIONS                       0x1F03

/* stencil */
#define GL_KEEP                             0x1E00
#define GL_REPLACE                          0x1E01
#define GL_INCR                             0x1E02
#define GL_DECR                             0x1E03

/* alpha & stencil */
#define GL_NEVER                            0x0200
#define GL_LESS                             0x0201
#define GL_EQUAL                            0x0202
#define GL_LEQUAL                           0x0203
#define GL_GREATER                          0x0204
#define GL_NOTEQUAL                         0x0205
#define GL_GEQUAL                           0x0206
#define GL_ALWAYS                           0x0207

/* blending equation & function */
#define GL_ZERO                             0           // SD
#define GL_ONE                              1           // SD
#define GL_SRC_COLOR                        0x0300      //  D
#define GL_ONE_MINUS_SRC_COLOR              0x0301      //  D
#define GL_SRC_ALPHA                        0x0302      // SD
#define GL_ONE_MINUS_SRC_ALPHA              0x0303      // SD
#define GL_DST_ALPHA                        0x0304      // SD
#define GL_ONE_MINUS_DST_ALPHA              0x0305      // SD
#define GL_DST_COLOR                        0x0306      // S
#define GL_ONE_MINUS_DST_COLOR              0x0307      // S
#define GL_SRC_ALPHA_SATURATE               0x0308      // S
    
/* Texture parameter name */
#define GL_TEXTURE_MIN_FILTER               0x2801
#define GL_TEXTURE_MAG_FILTER               0x2800
#define GL_TEXTURE_WRAP_S                   0x2802
#define GL_TEXTURE_WRAP_T                   0x2803
#define GL_GENERATE_MIPMAP                  0x8191
#define GL_TEXTURE_CROP_RECT_OES            0x8B9D

/* Texture Filter */
#define GL_NEAREST                          0x2600
#define GL_LINEAR                           0x2601
#define GL_NEAREST_MIPMAP_NEAREST           0x2700
#define GL_LINEAR_MIPMAP_NEAREST            0x2701
#define GL_NEAREST_MIPMAP_LINEAR            0x2702
#define GL_LINEAR_MIPMAP_LINEAR             0x2703

/* Texture Wrap Mode */
#define GL_CLAMP                            0x2900
#define GL_REPEAT                           0x2901
#define GL_CLAMP_TO_EDGE                    0x812F

/* Texture Env Mode */
#define GL_REPLACE                          0x1E01
#define GL_MODULATE                         0x2100
#define GL_DECAL                            0x2101
#define GL_ADD                              0x0104

/* Texture Env Parameter */
#define GL_TEXTURE_ENV_MODE                 0x2200
#define GL_TEXTURE_ENV_COLOR                0x2201

/* Texture Env Target */
#define GL_TEXTURE_ENV                      0x2300

/* TMUs */
#define GL_TEXTURE0                         0x84C0
#define GL_TEXTURE1                         0x84C1
#define GL_TEXTURE2                         0x84C2
#define GL_TEXTURE3                         0x84C3
#define GL_TEXTURE4                         0x84C4
#define GL_TEXTURE5                         0x84C5
#define GL_TEXTURE6                         0x84C6
#define GL_TEXTURE7                         0x84C7
#define GL_TEXTURE8                         0x84C8
#define GL_TEXTURE9                         0x84C9
#define GL_TEXTURE10                        0x84CA
#define GL_TEXTURE11                        0x84CB
#define GL_TEXTURE12                        0x84CC
#define GL_TEXTURE13                        0x84CD
#define GL_TEXTURE14                        0x84CE
#define GL_TEXTURE15                        0x84CF
#define GL_TEXTURE16                        0x84D0
#define GL_TEXTURE17                        0x84D1
#define GL_TEXTURE18                        0x84D2
#define GL_TEXTURE19                        0x84D3
#define GL_TEXTURE20                        0x84D4
#define GL_TEXTURE21                        0x84D5
#define GL_TEXTURE22                        0x84D6
#define GL_TEXTURE23                        0x84D7
#define GL_TEXTURE24                        0x84D8
#define GL_TEXTURE25                        0x84D9
#define GL_TEXTURE26                        0x84DA
#define GL_TEXTURE27                        0x84DB
#define GL_TEXTURE28                        0x84DC
#define GL_TEXTURE29                        0x84DD
#define GL_TEXTURE30                        0x84DE
#define GL_TEXTURE31                        0x84DF

/*****************************************************************************/
/* OpenGL ES 1.1 additions */

#define GL_ARRAY_BUFFER                         0x8892
#define GL_ELEMENT_ARRAY_BUFFER                 0x8893

#define GL_STATIC_DRAW                          0x88E4
#define GL_DYNAMIC_DRAW                         0x88E8

#define GL_BUFFER_SIZE                          0x8764
#define GL_BUFFER_USAGE                         0x8765

#define GL_ARRAY_BUFFER_BINDING                 0x8894
#define GL_ELEMENT_ARRAY_BUFFER_BINDING         0x8895
#define GL_VERTEX_ARRAY_BUFFER_BINDING          0x8896
#define GL_NORMAL_ARRAY_BUFFER_BINDING          0x8897
#define GL_COLOR_ARRAY_BUFFER_BINDING           0x8898
#define GL_TEXTURE_COORD_ARRAY_BUFFER_BINDING   0x889A

/*****************************************************************************/
/* Required extensions */

#define GL_PALETTE4_RGB8_OES                        0x8B90
#define GL_PALETTE4_RGBA8_OES                       0x8B91
#define GL_PALETTE4_R5_G6_B5_OES                    0x8B92
#define GL_PALETTE4_RGBA4_OES                       0x8B93
#define GL_PALETTE4_RGB5_A1_OES                     0x8B94
#define GL_PALETTE8_RGB8_OES                        0x8B95
#define GL_PALETTE8_RGBA8_OES                       0x8B96
#define GL_PALETTE8_R5_G6_B5_OES                    0x8B97
#define GL_PALETTE8_RGBA4_OES                       0x8B98
#define GL_PALETTE8_RGB5_A1_OES                     0x8B99

#define GL_IMPLEMENTATION_COLOR_READ_TYPE_OES       0x8B9A
#define GL_IMPLEMENTATION_COLOR_READ_FORMAT_OES     0x8B9B

#define GL_POINT_SPRITE_OES                         0x8861
#define GL_COORD_REPLACE_OES                        0x8862

#define GL_POINT_SIZE_ARRAY_OES                     0x8B9C
#define GL_POINT_SIZE_ARRAY_TYPE_OES                0x898A
#define GL_POINT_SIZE_ARRAY_STRIDE_OES              0x898B
#define GL_POINT_SIZE_ARRAY_POINTER_OES             0x898C
#define GL_POINT_SIZE_ARRAY_BUFFER_BINDING_OES      0x8B9F

/*****************************************************************************/
/* Extensions */

#define GL_MODELVIEW_MATRIX_FLOAT_AS_INT_BITS_OES   0x898D
#define GL_PROJECTION_MATRIX_FLOAT_AS_INT_BITS_OES  0x898E
#define GL_TEXTURE_MATRIX_FLOAT_AS_INT_BITS_OES     0x898F

#define GL_DIRECT_TEXTURE_2D_QUALCOMM               0x7E80




/*****************************************************************************/
/* OpenGL ES 1.0 functions */

void glActiveTexture(GLenum texture);
void glAlphaFunc(GLenum func, GLclampf ref);
void glAlphaFuncx(GLenum func, GLclampx ref);
void glBindTexture(GLenum target, GLuint texture);
void glBlendFunc(GLenum sfactor, GLenum dfactor);
void glClear(GLbitfield mask);
void glClearColor(GLclampf red, GLclampf green, GLclampf blue, GLclampf alpha);
void glClearColorx(GLclampx red, GLclampx green, GLclampx blue, GLclampx alpha);
void glClearDepthf(GLclampf depth);
void glClearDepthx(GLclampx depth);
void glClearStencil(GLint s);
void glClientActiveTexture(GLenum texture);
void glColor4f(GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha);
void glColor4x(GLfixed red, GLfixed green, GLfixed blue, GLfixed alpha);
void glColorMask(GLboolean r, GLboolean g, GLboolean b, GLboolean a);
void glColorPointer(GLint size, GLenum type, GLsizei stride, const GLvoid *ptr);
void glCompressedTexImage2D(GLenum target, GLint level, GLenum internalformat,
        GLsizei width, GLsizei height, GLint border,
        GLsizei imageSize, const GLvoid *data);
void glCompressedTexSubImage2D( GLenum target, GLint level, GLint xoffset,
        GLint yoffset, GLsizei width, GLsizei height,
        GLenum format, GLsizei imageSize,
        const GLvoid *data);
void glCopyTexImage2D(  GLenum target, GLint level, GLenum internalformat,
        GLint x, GLint y, GLsizei width, GLsizei height,
        GLint border);
void glCopyTexSubImage2D(   GLenum target, GLint level, GLint xoffset,
        GLint yoffset, GLint x, GLint y, GLsizei width,
        GLsizei height);
void glCullFace(GLenum mode);
void glDeleteTextures(GLsizei n, const GLuint *textures);
void glDepthFunc(GLenum func);
void glDepthMask(GLboolean flag);
void glDepthRangef(GLclampf zNear, GLclampf zFar);
void glDepthRangex(GLclampx zNear, GLclampx zFar);
void glDisable(GLenum cap);
void glDisableClientState(GLenum array);
void glDrawArrays(GLenum mode, GLint first, GLsizei count);
void glDrawElements(GLenum mode, GLsizei count,
        GLenum type, const GLvoid *indices);
void glEnable(GLenum cap);
void glEnableClientState(GLenum array);
void glFinish(void);
void glFlush(void);
void glFogf(GLenum pname, GLfloat param);
void glFogfv(GLenum pname, const GLfloat *params);
void glFogx(GLenum pname, GLfixed param);
void glFogxv(GLenum pname, const GLfixed *params);
void glFrontFace(GLenum mode);
void glFrustumf(GLfloat left, GLfloat right,
        GLfloat bottom, GLfloat top,
        GLfloat zNear, GLfloat zFar);
void glFrustumx(GLfixed left, GLfixed right,
        GLfixed bottom, GLfixed top,
        GLfixed zNear, GLfixed zFar);
void glGenTextures(GLsizei n, GLuint *textures);
GLenum glGetError(void);
void glGetIntegerv(GLenum pname, GLint *params);
const GLubyte * glGetString(GLenum name);
void glHint(GLenum target, GLenum mode);
void glLightModelf(GLenum pname, GLfloat param);
void glLightModelfv(GLenum pname, const GLfloat *params);
void glLightModelx(GLenum pname, GLfixed param);
void glLightModelxv(GLenum pname, const GLfixed *params);
void glLightf(GLenum light, GLenum pname, GLfloat param);
void glLightfv(GLenum light, GLenum pname, const GLfloat *params);
void glLightx(GLenum light, GLenum pname, GLfixed param);
void glLightxv(GLenum light, GLenum pname, const GLfixed *params);
void glLineWidth(GLfloat width);
void glLineWidthx(GLfixed width);
void glLoadIdentity(void);
void glLoadMatrixf(const GLfloat *m);
void glLoadMatrixx(const GLfixed *m);
void glLogicOp(GLenum opcode);
void glMaterialf(GLenum face, GLenum pname, GLfloat param);
void glMaterialfv(GLenum face, GLenum pname, const GLfloat *params);
void glMaterialx(GLenum face, GLenum pname, GLfixed param);
void glMaterialxv(GLenum face, GLenum pname, const GLfixed *params);
void glMatrixMode(GLenum mode);
void glMultMatrixf(const GLfloat *m);
void glMultMatrixx(const GLfixed *m);
void glMultiTexCoord4f(GLenum target, GLfloat s, GLfloat t, GLfloat r, GLfloat q);
void glMultiTexCoord4x(GLenum target, GLfixed s, GLfixed t, GLfixed r, GLfixed q);
void glNormal3f(GLfloat nx, GLfloat ny, GLfloat nz);
void glNormal3x(GLfixed nx, GLfixed ny, GLfixed nz);
void glNormalPointer(GLenum type, GLsizei stride, const GLvoid *pointer);
void glOrthof(  GLfloat left, GLfloat right,
        GLfloat bottom, GLfloat top,
        GLfloat zNear, GLfloat zFar);
void glOrthox(  GLfixed left, GLfixed right,
        GLfixed bottom, GLfixed top,
        GLfixed zNear, GLfixed zFar);
void glPixelStorei(GLenum pname, GLint param);
void glPointSize(GLfloat size);
void glPointSizex(GLfixed size);
void glPolygonOffset(GLfloat factor, GLfloat units);
void glPolygonOffsetx(GLfixed factor, GLfixed units);
void glPopMatrix(void);
void glPushMatrix(void);
void glReadPixels(  GLint x, GLint y, GLsizei width, GLsizei height,
        GLenum format, GLenum type, GLvoid *pixels);
void glRotatef(GLfloat angle, GLfloat x, GLfloat y, GLfloat z);
void glRotatex(GLfixed angle, GLfixed x, GLfixed y, GLfixed z);
void glSampleCoverage(GLclampf value, GLboolean invert);
void glSampleCoveragex(GLclampx value, GLboolean invert);
void glScalef(GLfloat x, GLfloat y, GLfloat z);
void glScalex(GLfixed x, GLfixed y, GLfixed z);
void glScissor(GLint x, GLint y, GLsizei width, GLsizei height);
void glShadeModel(GLenum mode);
void glStencilFunc(GLenum func, GLint ref, GLuint mask);
void glStencilMask(GLuint mask);
void glStencilOp(GLenum fail, GLenum zfail, GLenum zpass);
void glTexCoordPointer( GLint size, GLenum type,
        GLsizei stride, const GLvoid *pointer);
void glTexEnvf(GLenum target, GLenum pname, GLfloat param);
void glTexEnvfv(GLenum target, GLenum pname, const GLfloat *params);
void glTexEnvx(GLenum target, GLenum pname, GLfixed param);
void glTexEnvxv(GLenum target, GLenum pname, const GLfixed *params);
void glTexImage2D(  GLenum target, GLint level, GLenum internalformat,
        GLsizei width, GLsizei height, GLint border, GLenum format,
        GLenum type, const GLvoid *pixels);
void glTexParameterf(GLenum target, GLenum pname, GLfloat param);
void glTexParameterx(GLenum target, GLenum pname, GLfixed param);
void glTexSubImage2D(   GLenum target, GLint level, GLint xoffset,
        GLint yoffset, GLsizei width, GLsizei height,
        GLenum format, GLenum type, const GLvoid *pixels);
void glTranslatef(GLfloat x, GLfloat y, GLfloat z);
void glTranslatex(GLfixed x, GLfixed y, GLfixed z);
void glVertexPointer(   GLint size, GLenum type,
        GLsizei stride, const GLvoid *pointer);
void glViewport(GLint x, GLint y, GLsizei width, GLsizei height);

/*****************************************************************************/
/* OpenGL ES 1.1 functions */

void glClipPlanef(GLenum plane, const GLfloat* equation);
void glClipPlanex(GLenum plane, const GLfixed* equation);

void glBindBuffer(GLenum target, GLuint buffer);
void glBufferData(GLenum target, GLsizeiptr size, const GLvoid* data, GLenum usage);
void glBufferSubData(GLenum target, GLintptr offset, GLsizeiptr size, const GLvoid* data);
void glDeleteBuffers(GLsizei n, const GLuint* buffers);
void glGenBuffers(GLsizei n, GLuint* buffers);

void glGetBooleanv(GLenum pname, GLboolean *params);
void glGetFixedv(GLenum pname, GLfixed *params);
void glGetFloatv(GLenum pname, GLfloat *params);
void glGetPointerv(GLenum pname, void **params);
void glGetBufferParameteriv(GLenum target, GLenum pname, GLint *params);
void glGetClipPlanef(GLenum pname, GLfloat eqn[4]);
void glGetClipPlanex(GLenum pname, GLfixed eqn[4]);
void glGetLightxv(GLenum light, GLenum pname, GLfixed *params);
void glGetLightfv(GLenum light, GLenum pname, GLfloat *params);
void glGetMaterialxv(GLenum face, GLenum pname, GLfixed *params);
void glGetMaterialfv(GLenum face, GLenum pname, GLfloat *params);
void glGetTexEnvfv(GLenum env, GLenum pname, GLfloat *params);
void glGetTexEnviv(GLenum env, GLenum pname, GLint *params);
void glGetTexEnvxv(GLenum env, GLenum pname, GLfixed *params);
void glGetTexParameterfv(GLenum target, GLenum pname, GLfloat *params);
void glGetTexParameteriv(GLenum target, GLenum pname, GLint *params);
void glGetTexParameterxv(GLenum target, GLenum pname, GLfixed *params);
GLboolean glIsBuffer(GLuint buffer);
GLboolean glIsEnabled(GLenum cap);
GLboolean glIsTexture(GLuint texture);
void glPointParameterf(GLenum pname, GLfloat param);
void glPointParameterfv(GLenum pname, const GLfloat *params);
void glPointParameterx(GLenum pname, GLfixed param);
void glPointParameterxv(GLenum pname, const GLfixed *params);
void glColor4ub(GLubyte red, GLubyte green, GLubyte blue, GLubyte alpha);
void glTexEnvi(GLenum target, GLenum pname, GLint param);
void glTexEnviv(GLenum target, GLenum pname, const GLint *params);
void glTexParameterfv(GLenum target, GLenum pname, const GLfloat *params);
void glTexParameteriv(GLenum target, GLenum pname, const GLint *params);
void glTexParameteri(GLenum target, GLenum pname, GLint param);
void glTexParameterxv(GLenum target, GLenum pname, const GLfixed *params);

/*****************************************************************************/
/* Required extensions functions */

void glPointSizePointerOES(GLenum type, GLsizei stride, const GLvoid *pointer);


/*****************************************************************************/
/* Extensions functions */

void glDrawTexsOES(GLshort x , GLshort y, GLshort z, GLshort w, GLshort h);
void glDrawTexiOES(GLint x, GLint y, GLint z, GLint w, GLint h);
void glDrawTexfOES(GLfloat x, GLfloat y, GLfloat z, GLfloat w, GLfloat h);
void glDrawTexxOES(GLfixed x, GLfixed y, GLfixed z, GLfixed w, GLfixed h);
void glDrawTexsvOES(const GLshort* coords);
void glDrawTexivOES(const GLint* coords);
void glDrawTexfvOES(const GLfloat* coords);
void glDrawTexxvOES(const GLfixed* coords);
GLbitfield glQueryMatrixxOES(GLfixed* mantissa, GLint* exponent);

/* called by dalvik */
void glColorPointerBounds(GLint size, GLenum type, GLsizei stride,
        const GLvoid *ptr, GLsizei count);
void glNormalPointerBounds(GLenum type, GLsizei stride,
        const GLvoid *pointer, GLsizei count);
void glTexCoordPointerBounds(GLint size, GLenum type,
        GLsizei stride, const GLvoid *pointer, GLsizei count);
void glVertexPointerBounds(GLint size, GLenum type,
        GLsizei stride, const GLvoid *pointer, GLsizei count);

#ifdef __cplusplus
}
#endif

#endif /* __gl_h_ */

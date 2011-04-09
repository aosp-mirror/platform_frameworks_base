/* //device/java/android/javax/microedition/khronos/opengles/GL11ExtensionPack.java
**
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

// This source file is automatically generated

package javax.microedition.khronos.opengles;

public interface GL11ExtensionPack extends GL {
    int GL_BLEND_DST_ALPHA                                  = 0x80CA;
    int GL_BLEND_DST_RGB                                    = 0x80C8;
    int GL_BLEND_EQUATION                                   = 0x8009;
    int GL_BLEND_EQUATION_ALPHA                             = 0x883D;
    int GL_BLEND_EQUATION_RGB                               = 0x8009;
    int GL_BLEND_SRC_ALPHA                                  = 0x80CB;
    int GL_BLEND_SRC_RGB                                    = 0x80C9;
    int GL_COLOR_ATTACHMENT0_OES                            = 0x8CE0;
    int GL_COLOR_ATTACHMENT1_OES                            = 0x8CE1;
    int GL_COLOR_ATTACHMENT2_OES                            = 0x8CE2;
    int GL_COLOR_ATTACHMENT3_OES                            = 0x8CE3;
    int GL_COLOR_ATTACHMENT4_OES                            = 0x8CE4;
    int GL_COLOR_ATTACHMENT5_OES                            = 0x8CE5;
    int GL_COLOR_ATTACHMENT6_OES                            = 0x8CE6;
    int GL_COLOR_ATTACHMENT7_OES                            = 0x8CE7;
    int GL_COLOR_ATTACHMENT8_OES                            = 0x8CE8;
    int GL_COLOR_ATTACHMENT9_OES                            = 0x8CE9;
    int GL_COLOR_ATTACHMENT10_OES                           = 0x8CEA;
    int GL_COLOR_ATTACHMENT11_OES                           = 0x8CEB;
    int GL_COLOR_ATTACHMENT12_OES                           = 0x8CEC;
    int GL_COLOR_ATTACHMENT13_OES                           = 0x8CED;
    int GL_COLOR_ATTACHMENT14_OES                           = 0x8CEE;
    int GL_COLOR_ATTACHMENT15_OES                           = 0x8CEF;
    int GL_DECR_WRAP                                        = 0x8508;
    int GL_DEPTH_ATTACHMENT_OES                             = 0x8D00;
    int GL_DEPTH_COMPONENT                                  = 0x1902;
    int GL_DEPTH_COMPONENT16                                = 0x81A5;
    int GL_DEPTH_COMPONENT24                                = 0x81A6;
    int GL_DEPTH_COMPONENT32                                = 0x81A7;
    int GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME_OES           = 0x8CD1;
    int GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE_OES           = 0x8CD0;
    int GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE_OES = 0x8CD3;
    int GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL_OES         = 0x8CD2;
    int GL_FRAMEBUFFER_BINDING_OES                          = 0x8CA6;
    int GL_FRAMEBUFFER_COMPLETE_OES                         = 0x8CD5;
    int GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT_OES            = 0x8CD6;
    int GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS_OES            = 0x8CD9;
    int GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER_OES           = 0x8CDB;
    int GL_FRAMEBUFFER_INCOMPLETE_FORMATS_OES               = 0x8CDA;
    int GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT_OES    = 0x8CD7;
    int GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER_OES           = 0x8CDC;
    int GL_FRAMEBUFFER_OES                                  = 0x8D40;
    int GL_FRAMEBUFFER_UNSUPPORTED_OES                      = 0x8CDD;
    int GL_FUNC_ADD                                         = 0x8006;
    int GL_FUNC_REVERSE_SUBTRACT                            = 0x800B;
    int GL_FUNC_SUBTRACT                                    = 0x800A;
    int GL_INCR_WRAP                                        = 0x8507;
    int GL_INVALID_FRAMEBUFFER_OPERATION_OES                = 0x0506;
    int GL_MAX_COLOR_ATTACHMENTS_OES                        = 0x8CDF;
    int GL_MAX_CUBE_MAP_TEXTURE_SIZE                        = 0x851C;
    int GL_MAX_RENDERBUFFER_SIZE_OES                        = 0x84E8;
    int GL_MIRRORED_REPEAT                                  = 0x8370;
    int GL_NORMAL_MAP                                       = 0x8511;
    int GL_REFLECTION_MAP                                   = 0x8512;
    int GL_RENDERBUFFER_ALPHA_SIZE_OES                      = 0x8D53;
    int GL_RENDERBUFFER_BINDING_OES                         = 0x8CA7;
    int GL_RENDERBUFFER_BLUE_SIZE_OES                       = 0x8D52;
    int GL_RENDERBUFFER_DEPTH_SIZE_OES                      = 0x8D54;
    int GL_RENDERBUFFER_GREEN_SIZE_OES                      = 0x8D51;
    int GL_RENDERBUFFER_HEIGHT_OES                          = 0x8D43;
    int GL_RENDERBUFFER_INTERNAL_FORMAT_OES                 = 0x8D44;
    int GL_RENDERBUFFER_OES                                 = 0x8D41;
    int GL_RENDERBUFFER_RED_SIZE_OES                        = 0x8D50;
    int GL_RENDERBUFFER_STENCIL_SIZE_OES                    = 0x8D55;
    int GL_RENDERBUFFER_WIDTH_OES                           = 0x8D42;
    int GL_RGB5_A1                                          = 0x8057;
    int GL_RGB565_OES                                       = 0x8D62;
    int GL_RGB8                                             = 0x8051;
    int GL_RGBA4                                            = 0x8056;
    int GL_RGBA8                                            = 0x8058;
    int GL_STENCIL_ATTACHMENT_OES                           = 0x8D20;
    int GL_STENCIL_INDEX                                    = 0x1901;
    int GL_STENCIL_INDEX1_OES                               = 0x8D46;
    int GL_STENCIL_INDEX4_OES                               = 0x8D47;
    int GL_STENCIL_INDEX8_OES                               = 0x8D48;
    int GL_STR                                              = -1;
    int GL_TEXTURE_BINDING_CUBE_MAP                         = 0x8514;
    int GL_TEXTURE_CUBE_MAP                                 = 0x8513;
    int GL_TEXTURE_CUBE_MAP_NEGATIVE_X                      = 0x8516;
    int GL_TEXTURE_CUBE_MAP_NEGATIVE_Y                      = 0x8518;
    int GL_TEXTURE_CUBE_MAP_NEGATIVE_Z                      = 0x851A;
    int GL_TEXTURE_CUBE_MAP_POSITIVE_X                      = 0x8515;
    int GL_TEXTURE_CUBE_MAP_POSITIVE_Y                      = 0x8517;
    int GL_TEXTURE_CUBE_MAP_POSITIVE_Z                      = 0x8519;
    int GL_TEXTURE_GEN_MODE                                 = 0x2500;
    int GL_TEXTURE_GEN_STR                                  = 0x8D60;

    void glBindFramebufferOES(
        int target,
        int framebuffer
    );

    void glBindRenderbufferOES(
        int target,
        int renderbuffer
    );

    void glBindTexture(
        int target,
        int texture
    );

    void glBlendEquation(
        int mode
    );

    void glBlendEquationSeparate(
        int modeRGB,
        int modeAlpha
    );

    void glBlendFuncSeparate(
        int srcRGB,
        int dstRGB,
        int srcAlpha,
        int dstAlpha
    );

    int glCheckFramebufferStatusOES(
        int target
    );

    void glCompressedTexImage2D(
        int target,
        int level,
        int internalformat,
        int width,
        int height,
        int border,
        int imageSize,
        java.nio.Buffer data
    );

    void glCopyTexImage2D(
        int target,
        int level,
        int internalformat,
        int x,
        int y,
        int width,
        int height,
        int border
    );

    void glDeleteFramebuffersOES(
        int n,
        int[] framebuffers,
        int offset
    );

    void glDeleteFramebuffersOES(
        int n,
        java.nio.IntBuffer framebuffers
    );

    void glDeleteRenderbuffersOES(
        int n,
        int[] renderbuffers,
        int offset
    );

    void glDeleteRenderbuffersOES(
        int n,
        java.nio.IntBuffer renderbuffers
    );

    void glEnable(
        int cap
    );

    void glFramebufferRenderbufferOES(
        int target,
        int attachment,
        int renderbuffertarget,
        int renderbuffer
    );

    void glFramebufferTexture2DOES(
        int target,
        int attachment,
        int textarget,
        int texture,
        int level
    );

    void glGenerateMipmapOES(
        int target
    );

    void glGenFramebuffersOES(
        int n,
        int[] framebuffers,
        int offset
    );

    void glGenFramebuffersOES(
        int n,
        java.nio.IntBuffer framebuffers
    );

    void glGenRenderbuffersOES(
        int n,
        int[] renderbuffers,
        int offset
    );

    void glGenRenderbuffersOES(
        int n,
        java.nio.IntBuffer renderbuffers
    );

    void glGetFramebufferAttachmentParameterivOES(
        int target,
        int attachment,
        int pname,
        int[] params,
        int offset
    );

    void glGetFramebufferAttachmentParameterivOES(
        int target,
        int attachment,
        int pname,
        java.nio.IntBuffer params
    );

    void glGetIntegerv(
        int pname,
        int[] params,
        int offset
    );

    void glGetIntegerv(
        int pname,
        java.nio.IntBuffer params
    );

    void glGetRenderbufferParameterivOES(
        int target,
        int pname,
        int[] params,
        int offset
    );

    void glGetRenderbufferParameterivOES(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    void glGetTexGenfv(
        int coord,
        int pname,
        float[] params,
        int offset
    );

    void glGetTexGenfv(
        int coord,
        int pname,
        java.nio.FloatBuffer params
    );

    void glGetTexGeniv(
        int coord,
        int pname,
        int[] params,
        int offset
    );

    void glGetTexGeniv(
        int coord,
        int pname,
        java.nio.IntBuffer params
    );

    void glGetTexGenxv(
        int coord,
        int pname,
        int[] params,
        int offset
    );

    void glGetTexGenxv(
        int coord,
        int pname,
        java.nio.IntBuffer params
    );

    boolean glIsFramebufferOES(
        int framebuffer
    );

    boolean glIsRenderbufferOES(
        int renderbuffer
    );

    void glRenderbufferStorageOES(
        int target,
        int internalformat,
        int width,
        int height
    );

    void glStencilOp(
        int fail,
        int zfail,
        int zpass
    );

    void glTexEnvf(
        int target,
        int pname,
        float param
    );

    void glTexEnvfv(
        int target,
        int pname,
        float[] params,
        int offset
    );

    void glTexEnvfv(
        int target,
        int pname,
        java.nio.FloatBuffer params
    );

    void glTexEnvx(
        int target,
        int pname,
        int param
    );

    void glTexEnvxv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    void glTexEnvxv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    void glTexGenf(
        int coord,
        int pname,
        float param
    );

    void glTexGenfv(
        int coord,
        int pname,
        float[] params,
        int offset
    );

    void glTexGenfv(
        int coord,
        int pname,
        java.nio.FloatBuffer params
    );

    void glTexGeni(
        int coord,
        int pname,
        int param
    );

    void glTexGeniv(
        int coord,
        int pname,
        int[] params,
        int offset
    );

    void glTexGeniv(
        int coord,
        int pname,
        java.nio.IntBuffer params
    );

    void glTexGenx(
        int coord,
        int pname,
        int param
    );

    void glTexGenxv(
        int coord,
        int pname,
        int[] params,
        int offset
    );

    void glTexGenxv(
        int coord,
        int pname,
        java.nio.IntBuffer params
    );

    void glTexParameterf(
        int target,
        int pname,
        float param
    );

}

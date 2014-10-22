/*
 * Copyright 2014 The Android Open Source Project
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

// This source file is automatically generated

package android.opengl;

public class GLES31Ext {

    // GL_KHR_blend_equation_advanced
    public static final int GL_BLEND_ADVANCED_COHERENT_KHR                          = 0x9285;
    public static final int GL_MULTIPLY_KHR                                         = 0x9294;
    public static final int GL_SCREEN_KHR                                           = 0x9295;
    public static final int GL_OVERLAY_KHR                                          = 0x9296;
    public static final int GL_DARKEN_KHR                                           = 0x9297;
    public static final int GL_LIGHTEN_KHR                                          = 0x9298;
    public static final int GL_COLORDODGE_KHR                                       = 0x9299;
    public static final int GL_COLORBURN_KHR                                        = 0x929A;
    public static final int GL_HARDLIGHT_KHR                                        = 0x929B;
    public static final int GL_SOFTLIGHT_KHR                                        = 0x929C;
    public static final int GL_DIFFERENCE_KHR                                       = 0x929E;
    public static final int GL_EXCLUSION_KHR                                        = 0x92A0;
    public static final int GL_HSL_HUE_KHR                                          = 0x92AD;
    public static final int GL_HSL_SATURATION_KHR                                   = 0x92AE;
    public static final int GL_HSL_COLOR_KHR                                        = 0x92AF;
    public static final int GL_HSL_LUMINOSITY_KHR                                   = 0x92B0;

    // GL_KHR_debug
    public static final int GL_DEBUG_OUTPUT_SYNCHRONOUS_KHR                         = 0x8242;
    public static final int GL_DEBUG_NEXT_LOGGED_MESSAGE_LENGTH_KHR                 = 0x8243;
    public static final int GL_DEBUG_CALLBACK_FUNCTION_KHR                          = 0x8244;
    public static final int GL_DEBUG_CALLBACK_USER_PARAM_KHR                        = 0x8245;
    public static final int GL_DEBUG_SOURCE_API_KHR                                 = 0x8246;
    public static final int GL_DEBUG_SOURCE_WINDOW_SYSTEM_KHR                       = 0x8247;
    public static final int GL_DEBUG_SOURCE_SHADER_COMPILER_KHR                     = 0x8248;
    public static final int GL_DEBUG_SOURCE_THIRD_PARTY_KHR                         = 0x8249;
    public static final int GL_DEBUG_SOURCE_APPLICATION_KHR                         = 0x824A;
    public static final int GL_DEBUG_SOURCE_OTHER_KHR                               = 0x824B;
    public static final int GL_DEBUG_TYPE_ERROR_KHR                                 = 0x824C;
    public static final int GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR_KHR                   = 0x824D;
    public static final int GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR_KHR                    = 0x824E;
    public static final int GL_DEBUG_TYPE_PORTABILITY_KHR                           = 0x824F;
    public static final int GL_DEBUG_TYPE_PERFORMANCE_KHR                           = 0x8250;
    public static final int GL_DEBUG_TYPE_OTHER_KHR                                 = 0x8251;
    public static final int GL_DEBUG_TYPE_MARKER_KHR                                = 0x8268;
    public static final int GL_DEBUG_TYPE_PUSH_GROUP_KHR                            = 0x8269;
    public static final int GL_DEBUG_TYPE_POP_GROUP_KHR                             = 0x826A;
    public static final int GL_DEBUG_SEVERITY_NOTIFICATION_KHR                      = 0x826B;
    public static final int GL_MAX_DEBUG_GROUP_STACK_DEPTH_KHR                      = 0x826C;
    public static final int GL_DEBUG_GROUP_STACK_DEPTH_KHR                          = 0x826D;
    public static final int GL_BUFFER_KHR                                           = 0x82E0;
    public static final int GL_SHADER_KHR                                           = 0x82E1;
    public static final int GL_PROGRAM_KHR                                          = 0x82E2;
    public static final int GL_VERTEX_ARRAY_KHR                                     = 0x8074;
    public static final int GL_QUERY_KHR                                            = 0x82E3;
    public static final int GL_SAMPLER_KHR                                          = 0x82E6;
    public static final int GL_MAX_LABEL_LENGTH_KHR                                 = 0x82E8;
    public static final int GL_MAX_DEBUG_MESSAGE_LENGTH_KHR                         = 0x9143;
    public static final int GL_MAX_DEBUG_LOGGED_MESSAGES_KHR                        = 0x9144;
    public static final int GL_DEBUG_LOGGED_MESSAGES_KHR                            = 0x9145;
    public static final int GL_DEBUG_SEVERITY_HIGH_KHR                              = 0x9146;
    public static final int GL_DEBUG_SEVERITY_MEDIUM_KHR                            = 0x9147;
    public static final int GL_DEBUG_SEVERITY_LOW_KHR                               = 0x9148;
    public static final int GL_DEBUG_OUTPUT_KHR                                     = 0x92E0;
    public static final int GL_CONTEXT_FLAG_DEBUG_BIT_KHR                           = 0x00000002;
    public static final int GL_STACK_OVERFLOW_KHR                                   = 0x0503;
    public static final int GL_STACK_UNDERFLOW_KHR                                  = 0x0504;

    // GL_KHR_texture_compression_astc_ldr
    public static final int GL_COMPRESSED_RGBA_ASTC_4x4_KHR                         = 0x93B0;
    public static final int GL_COMPRESSED_RGBA_ASTC_5x4_KHR                         = 0x93B1;
    public static final int GL_COMPRESSED_RGBA_ASTC_5x5_KHR                         = 0x93B2;
    public static final int GL_COMPRESSED_RGBA_ASTC_6x5_KHR                         = 0x93B3;
    public static final int GL_COMPRESSED_RGBA_ASTC_6x6_KHR                         = 0x93B4;
    public static final int GL_COMPRESSED_RGBA_ASTC_8x5_KHR                         = 0x93B5;
    public static final int GL_COMPRESSED_RGBA_ASTC_8x6_KHR                         = 0x93B6;
    public static final int GL_COMPRESSED_RGBA_ASTC_8x8_KHR                         = 0x93B7;
    public static final int GL_COMPRESSED_RGBA_ASTC_10x5_KHR                        = 0x93B8;
    public static final int GL_COMPRESSED_RGBA_ASTC_10x6_KHR                        = 0x93B9;
    public static final int GL_COMPRESSED_RGBA_ASTC_10x8_KHR                        = 0x93BA;
    public static final int GL_COMPRESSED_RGBA_ASTC_10x10_KHR                       = 0x93BB;
    public static final int GL_COMPRESSED_RGBA_ASTC_12x10_KHR                       = 0x93BC;
    public static final int GL_COMPRESSED_RGBA_ASTC_12x12_KHR                       = 0x93BD;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_4x4_KHR                 = 0x93D0;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_5x4_KHR                 = 0x93D1;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_5x5_KHR                 = 0x93D2;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_6x5_KHR                 = 0x93D3;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_6x6_KHR                 = 0x93D4;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_8x5_KHR                 = 0x93D5;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_8x6_KHR                 = 0x93D6;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_8x8_KHR                 = 0x93D7;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_10x5_KHR                = 0x93D8;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_10x6_KHR                = 0x93D9;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_10x8_KHR                = 0x93DA;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_10x10_KHR               = 0x93DB;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_12x10_KHR               = 0x93DC;
    public static final int GL_COMPRESSED_SRGB8_ALPHA8_ASTC_12x12_KHR               = 0x93DD;

    // GL_OES_sample_shading
    public static final int GL_SAMPLE_SHADING_OES                                   = 0x8C36;
    public static final int GL_MIN_SAMPLE_SHADING_VALUE_OES                         = 0x8C37;

    // GL_OES_shader_multisample_interpolation
    public static final int GL_MIN_FRAGMENT_INTERPOLATION_OFFSET_OES                = 0x8E5B;
    public static final int GL_MAX_FRAGMENT_INTERPOLATION_OFFSET_OES                = 0x8E5C;
    public static final int GL_FRAGMENT_INTERPOLATION_OFFSET_BITS_OES               = 0x8E5D;

    // GL_OES_texture_stencil8
    public static final int GL_STENCIL_INDEX_OES                                    = 0x1901;
    public static final int GL_STENCIL_INDEX8_OES                                   = 0x8D48;

    // GL_OES_texture_storage_multisample_2d_array
    public static final int GL_TEXTURE_2D_MULTISAMPLE_ARRAY_OES                     = 0x9102;
    public static final int GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY_OES             = 0x9105;
    public static final int GL_SAMPLER_2D_MULTISAMPLE_ARRAY_OES                     = 0x910B;
    public static final int GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY_OES                 = 0x910C;
    public static final int GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY_OES        = 0x910D;

    // GL_EXT_geometry_shader
    public static final int GL_GEOMETRY_SHADER_EXT                                  = 0x8DD9;
    public static final int GL_GEOMETRY_SHADER_BIT_EXT                              = 0x00000004;
    public static final int GL_GEOMETRY_LINKED_VERTICES_OUT_EXT                     = 0x8916;
    public static final int GL_GEOMETRY_LINKED_INPUT_TYPE_EXT                       = 0x8917;
    public static final int GL_GEOMETRY_LINKED_OUTPUT_TYPE_EXT                      = 0x8918;
    public static final int GL_GEOMETRY_SHADER_INVOCATIONS_EXT                      = 0x887F;
    public static final int GL_LAYER_PROVOKING_VERTEX_EXT                           = 0x825E;
    public static final int GL_LINES_ADJACENCY_EXT                                  = 0x000A;
    public static final int GL_LINE_STRIP_ADJACENCY_EXT                             = 0x000B;
    public static final int GL_TRIANGLES_ADJACENCY_EXT                              = 0x000C;
    public static final int GL_TRIANGLE_STRIP_ADJACENCY_EXT                         = 0x000D;
    public static final int GL_MAX_GEOMETRY_UNIFORM_COMPONENTS_EXT                  = 0x8DDF;
    public static final int GL_MAX_GEOMETRY_UNIFORM_BLOCKS_EXT                      = 0x8A2C;
    public static final int GL_MAX_COMBINED_GEOMETRY_UNIFORM_COMPONENTS_EXT         = 0x8A32;
    public static final int GL_MAX_GEOMETRY_INPUT_COMPONENTS_EXT                    = 0x9123;
    public static final int GL_MAX_GEOMETRY_OUTPUT_COMPONENTS_EXT                   = 0x9124;
    public static final int GL_MAX_GEOMETRY_OUTPUT_VERTICES_EXT                     = 0x8DE0;
    public static final int GL_MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS_EXT             = 0x8DE1;
    public static final int GL_MAX_GEOMETRY_SHADER_INVOCATIONS_EXT                  = 0x8E5A;
    public static final int GL_MAX_GEOMETRY_TEXTURE_IMAGE_UNITS_EXT                 = 0x8C29;
    public static final int GL_MAX_GEOMETRY_ATOMIC_COUNTER_BUFFERS_EXT              = 0x92CF;
    public static final int GL_MAX_GEOMETRY_ATOMIC_COUNTERS_EXT                     = 0x92D5;
    public static final int GL_MAX_GEOMETRY_IMAGE_UNIFORMS_EXT                      = 0x90CD;
    public static final int GL_MAX_GEOMETRY_SHADER_STORAGE_BLOCKS_EXT               = 0x90D7;
    public static final int GL_FIRST_VERTEX_CONVENTION_EXT                          = 0x8E4D;
    public static final int GL_LAST_VERTEX_CONVENTION_EXT                           = 0x8E4E;
    public static final int GL_UNDEFINED_VERTEX_EXT                                 = 0x8260;
    public static final int GL_PRIMITIVES_GENERATED_EXT                             = 0x8C87;
    public static final int GL_FRAMEBUFFER_DEFAULT_LAYERS_EXT                       = 0x9312;
    public static final int GL_MAX_FRAMEBUFFER_LAYERS_EXT                           = 0x9317;
    public static final int GL_FRAMEBUFFER_INCOMPLETE_LAYER_TARGETS_EXT             = 0x8DA8;
    public static final int GL_FRAMEBUFFER_ATTACHMENT_LAYERED_EXT                   = 0x8DA7;
    public static final int GL_REFERENCED_BY_GEOMETRY_SHADER_EXT                    = 0x9309;

    // GL_EXT_primitive_bounding_box
    public static final int GL_PRIMITIVE_BOUNDING_BOX_EXT                           = 0x92BE;

    // GL_EXT_tessellation_shader
    public static final int GL_PATCHES_EXT                                          = 0x000E;
    public static final int GL_PATCH_VERTICES_EXT                                   = 0x8E72;
    public static final int GL_TESS_CONTROL_OUTPUT_VERTICES_EXT                     = 0x8E75;
    public static final int GL_TESS_GEN_MODE_EXT                                    = 0x8E76;
    public static final int GL_TESS_GEN_SPACING_EXT                                 = 0x8E77;
    public static final int GL_TESS_GEN_VERTEX_ORDER_EXT                            = 0x8E78;
    public static final int GL_TESS_GEN_POINT_MODE_EXT                              = 0x8E79;
    public static final int GL_ISOLINES_EXT                                         = 0x8E7A;
    public static final int GL_QUADS_EXT                                            = 0x0007;
    public static final int GL_FRACTIONAL_ODD_EXT                                   = 0x8E7B;
    public static final int GL_FRACTIONAL_EVEN_EXT                                  = 0x8E7C;
    public static final int GL_MAX_PATCH_VERTICES_EXT                               = 0x8E7D;
    public static final int GL_MAX_TESS_GEN_LEVEL_EXT                               = 0x8E7E;
    public static final int GL_MAX_TESS_CONTROL_UNIFORM_COMPONENTS_EXT              = 0x8E7F;
    public static final int GL_MAX_TESS_EVALUATION_UNIFORM_COMPONENTS_EXT           = 0x8E80;
    public static final int GL_MAX_TESS_CONTROL_TEXTURE_IMAGE_UNITS_EXT             = 0x8E81;
    public static final int GL_MAX_TESS_EVALUATION_TEXTURE_IMAGE_UNITS_EXT          = 0x8E82;
    public static final int GL_MAX_TESS_CONTROL_OUTPUT_COMPONENTS_EXT               = 0x8E83;
    public static final int GL_MAX_TESS_PATCH_COMPONENTS_EXT                        = 0x8E84;
    public static final int GL_MAX_TESS_CONTROL_TOTAL_OUTPUT_COMPONENTS_EXT         = 0x8E85;
    public static final int GL_MAX_TESS_EVALUATION_OUTPUT_COMPONENTS_EXT            = 0x8E86;
    public static final int GL_MAX_TESS_CONTROL_UNIFORM_BLOCKS_EXT                  = 0x8E89;
    public static final int GL_MAX_TESS_EVALUATION_UNIFORM_BLOCKS_EXT               = 0x8E8A;
    public static final int GL_MAX_TESS_CONTROL_INPUT_COMPONENTS_EXT                = 0x886C;
    public static final int GL_MAX_TESS_EVALUATION_INPUT_COMPONENTS_EXT             = 0x886D;
    public static final int GL_MAX_COMBINED_TESS_CONTROL_UNIFORM_COMPONENTS_EXT     = 0x8E1E;
    public static final int GL_MAX_COMBINED_TESS_EVALUATION_UNIFORM_COMPONENTS_EXT  = 0x8E1F;
    public static final int GL_MAX_TESS_CONTROL_ATOMIC_COUNTER_BUFFERS_EXT          = 0x92CD;
    public static final int GL_MAX_TESS_EVALUATION_ATOMIC_COUNTER_BUFFERS_EXT       = 0x92CE;
    public static final int GL_MAX_TESS_CONTROL_ATOMIC_COUNTERS_EXT                 = 0x92D3;
    public static final int GL_MAX_TESS_EVALUATION_ATOMIC_COUNTERS_EXT              = 0x92D4;
    public static final int GL_MAX_TESS_CONTROL_IMAGE_UNIFORMS_EXT                  = 0x90CB;
    public static final int GL_MAX_TESS_EVALUATION_IMAGE_UNIFORMS_EXT               = 0x90CC;
    public static final int GL_MAX_TESS_CONTROL_SHADER_STORAGE_BLOCKS_EXT           = 0x90D8;
    public static final int GL_MAX_TESS_EVALUATION_SHADER_STORAGE_BLOCKS_EXT        = 0x90D9;
    public static final int GL_PRIMITIVE_RESTART_FOR_PATCHES_SUPPORTED              = 0x8221;
    public static final int GL_IS_PER_PATCH_EXT                                     = 0x92E7;
    public static final int GL_REFERENCED_BY_TESS_CONTROL_SHADER_EXT                = 0x9307;
    public static final int GL_REFERENCED_BY_TESS_EVALUATION_SHADER_EXT             = 0x9308;
    public static final int GL_TESS_CONTROL_SHADER_EXT                              = 0x8E88;
    public static final int GL_TESS_EVALUATION_SHADER_EXT                           = 0x8E87;
    public static final int GL_TESS_CONTROL_SHADER_BIT_EXT                          = 0x00000008;
    public static final int GL_TESS_EVALUATION_SHADER_BIT_EXT                       = 0x00000010;

    // GL_EXT_texture_border_clamp
    public static final int GL_TEXTURE_BORDER_COLOR_EXT                             = 0x1004;
    public static final int GL_CLAMP_TO_BORDER_EXT                                  = 0x812D;

    // GL_EXT_texture_buffer
    public static final int GL_TEXTURE_BUFFER_EXT                                   = 0x8C2A;
    public static final int GL_TEXTURE_BUFFER_BINDING_EXT                           = 0x8C2A;
    public static final int GL_MAX_TEXTURE_BUFFER_SIZE_EXT                          = 0x8C2B;
    public static final int GL_TEXTURE_BINDING_BUFFER_EXT                           = 0x8C2C;
    public static final int GL_TEXTURE_BUFFER_DATA_STORE_BINDING_EXT                = 0x8C2D;
    public static final int GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT_EXT                  = 0x919F;
    public static final int GL_SAMPLER_BUFFER_EXT                                   = 0x8DC2;
    public static final int GL_INT_SAMPLER_BUFFER_EXT                               = 0x8DD0;
    public static final int GL_UNSIGNED_INT_SAMPLER_BUFFER_EXT                      = 0x8DD8;
    public static final int GL_IMAGE_BUFFER_EXT                                     = 0x9051;
    public static final int GL_INT_IMAGE_BUFFER_EXT                                 = 0x905C;
    public static final int GL_UNSIGNED_INT_IMAGE_BUFFER_EXT                        = 0x9067;
    public static final int GL_TEXTURE_BUFFER_OFFSET_EXT                            = 0x919D;
    public static final int GL_TEXTURE_BUFFER_SIZE_EXT                              = 0x919E;

    // GL_EXT_texture_cube_map_array
    public static final int GL_TEXTURE_CUBE_MAP_ARRAY_EXT                           = 0x9009;
    public static final int GL_TEXTURE_BINDING_CUBE_MAP_ARRAY_EXT                   = 0x900A;
    public static final int GL_SAMPLER_CUBE_MAP_ARRAY_EXT                           = 0x900C;
    public static final int GL_SAMPLER_CUBE_MAP_ARRAY_SHADOW_EXT                    = 0x900D;
    public static final int GL_INT_SAMPLER_CUBE_MAP_ARRAY_EXT                       = 0x900E;
    public static final int GL_UNSIGNED_INT_SAMPLER_CUBE_MAP_ARRAY_EXT              = 0x900F;
    public static final int GL_IMAGE_CUBE_MAP_ARRAY_EXT                             = 0x9054;
    public static final int GL_INT_IMAGE_CUBE_MAP_ARRAY_EXT                         = 0x905F;
    public static final int GL_UNSIGNED_INT_IMAGE_CUBE_MAP_ARRAY_EXT                = 0x906A;

    // GL_EXT_texture_sRGB_decode
    public static final int GL_TEXTURE_SRGB_DECODE_EXT                              = 0x8A48;
    public static final int GL_DECODE_EXT                                           = 0x8A49;
    public static final int GL_SKIP_DECODE_EXT                                      = 0x8A4A;

    native private static void _nativeClassInit();
    static {
        _nativeClassInit();
    }

    private GLES31Ext() {}
    // C function void glBlendBarrierKHR ( void )

    public static native void glBlendBarrierKHR(
    );

    // C function void glDebugMessageControlKHR ( GLenum source, GLenum type, GLenum severity, GLsizei count, const GLuint *ids, GLboolean enabled )

    public static native void glDebugMessageControlKHR(
        int source,
        int type,
        int severity,
        int count,
        int[] ids,
        int offset,
        boolean enabled
    );

    // C function void glDebugMessageControlKHR ( GLenum source, GLenum type, GLenum severity, GLsizei count, const GLuint *ids, GLboolean enabled )

    public static native void glDebugMessageControlKHR(
        int source,
        int type,
        int severity,
        int count,
        java.nio.IntBuffer ids,
        boolean enabled
    );

    // C function void glDebugMessageInsertKHR ( GLenum source, GLenum type, GLuint id, GLenum severity, GLsizei length, const GLchar *buf )

    public static native void glDebugMessageInsertKHR(
        int source,
        int type,
        int id,
        int severity,
        String buf
    );

    // C function void glDebugMessageCallbackKHR ( GLDEBUGPROCKHR callback, const void *userParam )

    public interface DebugProcKHR {
        void onMessage(int source, int type, int id, int severity, String message);
    }

    public static native void glDebugMessageCallbackKHR(DebugProcKHR callback);

    // C function GLuint glGetDebugMessageLogKHR ( GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog )

    public static native int glGetDebugMessageLogKHR(
        int count,
        int bufSize,
        int[] sources,
        int sourcesOffset,
        int[] types,
        int typesOffset,
        int[] ids,
        int idsOffset,
        int[] severities,
        int severitiesOffset,
        int[] lengths,
        int lengthsOffset,
        byte[] messageLog,
        int messageLogOffset);

    // C function GLuint glGetDebugMessageLogKHR ( GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog )

    public static native int glGetDebugMessageLogKHR(
        int count,
        java.nio.IntBuffer sources,
        java.nio.IntBuffer types,
        java.nio.IntBuffer ids,
        java.nio.IntBuffer severities,
        java.nio.IntBuffer lengths,
        java.nio.ByteBuffer messageLog);

    // C function GLuint glGetDebugMessageLogKHR ( GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog )

    public static native String[] glGetDebugMessageLogKHR(
        int count,
        int[] sources,
        int sourcesOffset,
        int[] types,
        int typesOffset,
        int[] ids,
        int idsOffset,
        int[] severities,
        int severitiesOffset);

    // C function GLuint glGetDebugMessageLogKHR ( GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog )

    public static native String[] glGetDebugMessageLogKHR(
        int count,
        java.nio.IntBuffer sources,
        java.nio.IntBuffer types,
        java.nio.IntBuffer ids,
        java.nio.IntBuffer severities);

    // C function void glPushDebugGroupKHR ( GLenum source, GLuint id, GLsizei length, const GLchar *message )

    public static native void glPushDebugGroupKHR(
        int source,
        int id,
        int length,
        String message
    );

    // C function void glPopDebugGroupKHR ( void )

    public static native void glPopDebugGroupKHR(
    );

    // C function void glObjectLabelKHR ( GLenum identifier, GLuint name, GLsizei length, const GLchar *label )

    public static native void glObjectLabelKHR(
        int identifier,
        int name,
        int length,
        String label
    );

    // C function void glGetObjectLabelKHR ( GLenum identifier, GLuint name, GLsizei bufSize, GLsizei *length, GLchar *label )

    public static native String glGetObjectLabelKHR(int identifier, int name);

    // C function void glObjectPtrLabelKHR ( const void *ptr, GLsizei length, const GLchar *label )

    public static native void glObjectPtrLabelKHR(long ptr, String label);

    // C function void glGetObjectPtrLabelKHR ( const void *ptr, GLsizei bufSize, GLsizei *length, GLchar *label )

    public static native String glGetObjectPtrLabelKHR(long ptr);

    // C function void glGetPointervKHR ( GLenum pname, void **params )

    public static native DebugProcKHR glGetDebugMessageCallbackKHR();

    // C function void glMinSampleShadingOES ( GLfloat value )

    public static native void glMinSampleShadingOES(
        float value
    );

    // C function void glTexStorage3DMultisampleOES ( GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height, GLsizei depth, GLboolean fixedsamplelocations )

    public static native void glTexStorage3DMultisampleOES(
        int target,
        int samples,
        int internalformat,
        int width,
        int height,
        int depth,
        boolean fixedsamplelocations
    );

    // C function void glCopyImageSubDataEXT ( GLuint srcName, GLenum srcTarget, GLint srcLevel, GLint srcX, GLint srcY, GLint srcZ, GLuint dstName, GLenum dstTarget, GLint dstLevel, GLint dstX, GLint dstY, GLint dstZ, GLsizei srcWidth, GLsizei srcHeight, GLsizei srcDepth )

    public static native void glCopyImageSubDataEXT(
        int srcName,
        int srcTarget,
        int srcLevel,
        int srcX,
        int srcY,
        int srcZ,
        int dstName,
        int dstTarget,
        int dstLevel,
        int dstX,
        int dstY,
        int dstZ,
        int srcWidth,
        int srcHeight,
        int srcDepth
    );

    // C function void glEnableiEXT ( GLenum target, GLuint index )

    public static native void glEnableiEXT(
        int target,
        int index
    );

    // C function void glDisableiEXT ( GLenum target, GLuint index )

    public static native void glDisableiEXT(
        int target,
        int index
    );

    // C function void glBlendEquationiEXT ( GLuint buf, GLenum mode )

    public static native void glBlendEquationiEXT(
        int buf,
        int mode
    );

    // C function void glBlendEquationSeparateiEXT ( GLuint buf, GLenum modeRGB, GLenum modeAlpha )

    public static native void glBlendEquationSeparateiEXT(
        int buf,
        int modeRGB,
        int modeAlpha
    );

    // C function void glBlendFunciEXT ( GLuint buf, GLenum src, GLenum dst )

    public static native void glBlendFunciEXT(
        int buf,
        int src,
        int dst
    );

    // C function void glBlendFuncSeparateiEXT ( GLuint buf, GLenum srcRGB, GLenum dstRGB, GLenum srcAlpha, GLenum dstAlpha )

    public static native void glBlendFuncSeparateiEXT(
        int buf,
        int srcRGB,
        int dstRGB,
        int srcAlpha,
        int dstAlpha
    );

    // C function void glColorMaskiEXT ( GLuint index, GLboolean r, GLboolean g, GLboolean b, GLboolean a )

    public static native void glColorMaskiEXT(
        int index,
        boolean r,
        boolean g,
        boolean b,
        boolean a
    );

    // C function GLboolean glIsEnablediEXT ( GLenum target, GLuint index )

    public static native boolean glIsEnablediEXT(
        int target,
        int index
    );

    // C function void glFramebufferTextureEXT ( GLenum target, GLenum attachment, GLuint texture, GLint level )

    public static native void glFramebufferTextureEXT(
        int target,
        int attachment,
        int texture,
        int level
    );

    // C function void glPrimitiveBoundingBoxEXT ( GLfloat minX, GLfloat minY, GLfloat minZ, GLfloat minW, GLfloat maxX, GLfloat maxY, GLfloat maxZ, GLfloat maxW )

    public static native void glPrimitiveBoundingBoxEXT(
        float minX,
        float minY,
        float minZ,
        float minW,
        float maxX,
        float maxY,
        float maxZ,
        float maxW
    );

    // C function void glPatchParameteriEXT ( GLenum pname, GLint value )

    public static native void glPatchParameteriEXT(
        int pname,
        int value
    );

    // C function void glTexParameterIivEXT ( GLenum target, GLenum pname, const GLint *params )

    public static native void glTexParameterIivEXT(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glTexParameterIivEXT ( GLenum target, GLenum pname, const GLint *params )

    public static native void glTexParameterIivEXT(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glTexParameterIuivEXT ( GLenum target, GLenum pname, const GLuint *params )

    public static native void glTexParameterIuivEXT(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glTexParameterIuivEXT ( GLenum target, GLenum pname, const GLuint *params )

    public static native void glTexParameterIuivEXT(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetTexParameterIivEXT ( GLenum target, GLenum pname, GLint *params )

    public static native void glGetTexParameterIivEXT(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetTexParameterIivEXT ( GLenum target, GLenum pname, GLint *params )

    public static native void glGetTexParameterIivEXT(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetTexParameterIuivEXT ( GLenum target, GLenum pname, GLuint *params )

    public static native void glGetTexParameterIuivEXT(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetTexParameterIuivEXT ( GLenum target, GLenum pname, GLuint *params )

    public static native void glGetTexParameterIuivEXT(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glSamplerParameterIivEXT ( GLuint sampler, GLenum pname, const GLint *param )

    public static native void glSamplerParameterIivEXT(
        int sampler,
        int pname,
        int[] param,
        int offset
    );

    // C function void glSamplerParameterIivEXT ( GLuint sampler, GLenum pname, const GLint *param )

    public static native void glSamplerParameterIivEXT(
        int sampler,
        int pname,
        java.nio.IntBuffer param
    );

    // C function void glSamplerParameterIuivEXT ( GLuint sampler, GLenum pname, const GLuint *param )

    public static native void glSamplerParameterIuivEXT(
        int sampler,
        int pname,
        int[] param,
        int offset
    );

    // C function void glSamplerParameterIuivEXT ( GLuint sampler, GLenum pname, const GLuint *param )

    public static native void glSamplerParameterIuivEXT(
        int sampler,
        int pname,
        java.nio.IntBuffer param
    );

    // C function void glGetSamplerParameterIivEXT ( GLuint sampler, GLenum pname, GLint *params )

    public static native void glGetSamplerParameterIivEXT(
        int sampler,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetSamplerParameterIivEXT ( GLuint sampler, GLenum pname, GLint *params )

    public static native void glGetSamplerParameterIivEXT(
        int sampler,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetSamplerParameterIuivEXT ( GLuint sampler, GLenum pname, GLuint *params )

    public static native void glGetSamplerParameterIuivEXT(
        int sampler,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetSamplerParameterIuivEXT ( GLuint sampler, GLenum pname, GLuint *params )

    public static native void glGetSamplerParameterIuivEXT(
        int sampler,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glTexBufferEXT ( GLenum target, GLenum internalformat, GLuint buffer )

    public static native void glTexBufferEXT(
        int target,
        int internalformat,
        int buffer
    );

    // C function void glTexBufferRangeEXT ( GLenum target, GLenum internalformat, GLuint buffer, GLintptr offset, GLsizeiptr size )

    public static native void glTexBufferRangeEXT(
        int target,
        int internalformat,
        int buffer,
        int offset,
        int size
    );

}

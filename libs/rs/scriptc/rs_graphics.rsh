/*
 * Copyright (C) 2011 The Android Open Source Project
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

/** @file rs_graphics.rsh
 *  \brief Renderscript graphics API
 *
 *  A set of graphics functions used by Renderscript.
 *
 */
#ifndef __RS_GRAPHICS_RSH__
#define __RS_GRAPHICS_RSH__

// These are API 15 once it get official
typedef enum {
    RS_DEPTH_FUNC_ALWAYS,
    RS_DEPTH_FUNC_LESS,
    RS_DEPTH_FUNC_LEQUAL,
    RS_DEPTH_FUNC_GREATER,
    RS_DEPTH_FUNC_GEQUAL,
    RS_DEPTH_FUNC_EQUAL,
    RS_DEPTH_FUNC_NOTEQUAL
} rs_depth_func;

typedef enum {
    RS_BLEND_SRC_ZERO,                  // 0
    RS_BLEND_SRC_ONE,                   // 1
    RS_BLEND_SRC_DST_COLOR,             // 2
    RS_BLEND_SRC_ONE_MINUS_DST_COLOR,   // 3
    RS_BLEND_SRC_SRC_ALPHA,             // 4
    RS_BLEND_SRC_ONE_MINUS_SRC_ALPHA,   // 5
    RS_BLEND_SRC_DST_ALPHA,             // 6
    RS_BLEND_SRC_ONE_MINUS_DST_ALPHA,   // 7
    RS_BLEND_SRC_SRC_ALPHA_SATURATE     // 8
} rs_blend_src_func;

typedef enum {
    RS_BLEND_DST_ZERO,                  // 0
    RS_BLEND_DST_ONE,                   // 1
    RS_BLEND_DST_SRC_COLOR,             // 2
    RS_BLEND_DST_ONE_MINUS_SRC_COLOR,   // 3
    RS_BLEND_DST_SRC_ALPHA,             // 4
    RS_BLEND_DST_ONE_MINUS_SRC_ALPHA,   // 5
    RS_BLEND_DST_DST_ALPHA,             // 6
    RS_BLEND_DST_ONE_MINUS_DST_ALPHA    // 7
} rs_blend_dst_func;

typedef enum {
    RS_CULL_BACK,
    RS_CULL_FRONT,
    RS_CULL_NONE
} rs_cull_mode;

typedef enum {
    RS_SAMPLER_NEAREST,
    RS_SAMPLER_LINEAR,
    RS_SAMPLER_LINEAR_MIP_LINEAR,
    RS_SAMPLER_WRAP,
    RS_SAMPLER_CLAMP,
    RS_SAMPLER_LINEAR_MIP_NEAREST,
} rs_sampler_value;

#if (defined(RS_VERSION) && (RS_VERSION >= 14))
/**
 * Set the color target used for all subsequent rendering calls
 * @param colorTarget
 * @param slot
 */
extern void __attribute__((overloadable))
    rsgBindColorTarget(rs_allocation colorTarget, uint slot);

/**
 * Clear the previously set color target
 * @param slot
 */
extern void __attribute__((overloadable))
    rsgClearColorTarget(uint slot);

/**
 * Set the depth target used for all subsequent rendering calls
 * @param depthTarget
 */
extern void __attribute__((overloadable))
    rsgBindDepthTarget(rs_allocation depthTarget);

/**
 * Clear the previously set depth target
 */
extern void __attribute__((overloadable))
    rsgClearDepthTarget(void);

/**
 * Clear all color and depth targets and resume rendering into
 * the framebuffer
 */
extern void __attribute__((overloadable))
    rsgClearAllRenderTargets(void);

/**
 * Force Renderscript to finish all rendering commands
 */
extern uint __attribute__((overloadable))
    rsgFinish(void);

#endif //defined(RS_VERSION) && (RS_VERSION >= 14)

/**
 * Bind a new ProgramFragment to the rendering context.
 *
 * @param pf
 */
extern void __attribute__((overloadable))
    rsgBindProgramFragment(rs_program_fragment pf);

/**
 * Bind a new ProgramStore to the rendering context.
 *
 * @param ps
 */
extern void __attribute__((overloadable))
    rsgBindProgramStore(rs_program_store ps);


/**
 * @hide
 * Get program store depth function
 *
 * @param ps
 */
extern rs_depth_func __attribute__((overloadable))
    rsgProgramStoreGetDepthFunc(rs_program_store ps);

/**
 * @hide
 * Get program store depth mask
 *
 * @param ps
 */
extern bool __attribute__((overloadable))
    rsgProgramStoreGetDepthMask(rs_program_store ps);
/**
 * @hide
 * Get program store red component color mask
 *
 * @param ps
 */
extern bool __attribute__((overloadable))
    rsgProgramStoreGetColorMaskR(rs_program_store ps);

/**
 * @hide
 * Get program store green component color mask
 *
 * @param ps
 */
extern bool __attribute__((overloadable))
    rsgProgramStoreGetColorMaskG(rs_program_store ps);

/**
 * @hide
 * Get program store blur component color mask
 *
 * @param ps
 */
extern bool __attribute__((overloadable))
    rsgProgramStoreGetColorMaskB(rs_program_store ps);

/**
 * @hide
 * Get program store alpha component color mask
 *
 * @param ps
 */
extern bool __attribute__((overloadable))
    rsgProgramStoreGetColorMaskA(rs_program_store ps);

/**
 * @hide
 * Get program store blend source function
 *
 * @param ps
 */
extern rs_blend_src_func __attribute__((overloadable))
        rsgProgramStoreGetBlendSrcFunc(rs_program_store ps);

/**
 * @hide
 * Get program store blend destination function
 *
 * @param ps
 */
extern rs_blend_dst_func __attribute__((overloadable))
    rsgProgramStoreGetBlendDstFunc(rs_program_store ps);

/**
 * @hide
 * Get program store dither state
 *
 * @param ps
 */
extern bool __attribute__((overloadable))
    rsgProgramStoreGetDitherEnabled(rs_program_store ps);


/**
 * Bind a new ProgramVertex to the rendering context.
 *
 * @param pv
 */
extern void __attribute__((overloadable))
    rsgBindProgramVertex(rs_program_vertex pv);

/**
 * Bind a new ProgramRaster to the rendering context.
 *
 * @param pr
 */
extern void __attribute__((overloadable))
    rsgBindProgramRaster(rs_program_raster pr);

/**
 * @hide
 * Get program raster point sprite state
 *
 * @param pr
 */
extern bool __attribute__((overloadable))
    rsgProgramRasterGetPointSpriteEnabled(rs_program_raster pr);

/**
 * @hide
 * Get program raster cull mode
 *
 * @param pr
 */
extern rs_cull_mode __attribute__((overloadable))
    rsgProgramRasterGetCullMode(rs_program_raster pr);

/**
 * Bind a new Sampler object to a ProgramFragment.  The sampler will
 * operate on the texture bound at the matching slot.
 *
 * @param slot
 */
extern void __attribute__((overloadable))
    rsgBindSampler(rs_program_fragment, uint slot, rs_sampler);

/**
 * @hide
 * Get sampler minification value
 *
 * @param pr
 */
extern rs_sampler_value __attribute__((overloadable))
    rsgSamplerGetMinification(rs_sampler s);

/**
 * @hide
 * Get sampler magnification value
 *
 * @param pr
 */
extern rs_sampler_value __attribute__((overloadable))
    rsgSamplerGetMagnification(rs_sampler s);

/**
 * @hide
 * Get sampler wrap S value
 *
 * @param pr
 */
extern rs_sampler_value __attribute__((overloadable))
    rsgSamplerGetWrapS(rs_sampler s);

/**
 * @hide
 * Get sampler wrap T value
 *
 * @param pr
 */
extern rs_sampler_value __attribute__((overloadable))
    rsgSamplerGetWrapT(rs_sampler s);

/**
 * @hide
 * Get sampler anisotropy
 *
 * @param pr
 */
extern float __attribute__((overloadable))
    rsgSamplerGetAnisotropy(rs_sampler s);

/**
 * Bind a new Allocation object to a ProgramFragment.  The
 * Allocation must be a valid texture for the Program.  The sampling
 * of the texture will be controled by the Sampler bound at the
 * matching slot.
 *
 * @param slot
 */
extern void __attribute__((overloadable))
    rsgBindTexture(rs_program_fragment, uint slot, rs_allocation);

/**
 * Load the projection matrix for a currently bound fixed function
 * vertex program. Calling this function with a custom vertex shader
 * would result in an error.
 * @param proj projection matrix
 */
extern void __attribute__((overloadable))
    rsgProgramVertexLoadProjectionMatrix(const rs_matrix4x4 *proj);
/**
 * Load the model matrix for a currently bound fixed function
 * vertex program. Calling this function with a custom vertex shader
 * would result in an error.
 * @param model model matrix
 */
extern void __attribute__((overloadable))
    rsgProgramVertexLoadModelMatrix(const rs_matrix4x4 *model);
/**
 * Load the texture matrix for a currently bound fixed function
 * vertex program. Calling this function with a custom vertex shader
 * would result in an error.
 * @param tex texture matrix
 */
extern void __attribute__((overloadable))
    rsgProgramVertexLoadTextureMatrix(const rs_matrix4x4 *tex);
/**
 * Get the projection matrix for a currently bound fixed function
 * vertex program. Calling this function with a custom vertex shader
 * would result in an error.
 * @param proj matrix to store the current projection matrix into
 */
extern void __attribute__((overloadable))
    rsgProgramVertexGetProjectionMatrix(rs_matrix4x4 *proj);

/**
 * Set the constant color for a fixed function emulation program.
 *
 * @param pf
 * @param r
 * @param g
 * @param b
 * @param a
 */
extern void __attribute__((overloadable))
    rsgProgramFragmentConstantColor(rs_program_fragment pf, float r, float g, float b, float a);

/**
 * Get the width of the current rendering surface.
 *
 * @return uint
 */
extern uint __attribute__((overloadable))
    rsgGetWidth(void);

/**
 * Get the height of the current rendering surface.
 *
 * @return uint
 */
extern uint __attribute__((overloadable))
    rsgGetHeight(void);


/**
 * Sync the contents of an allocation from its SCRIPT memory space to its HW
 * memory spaces.
 *
 * @param alloc
 */
extern void __attribute__((overloadable))
    rsgAllocationSyncAll(rs_allocation alloc);

#if (defined(RS_VERSION) && (RS_VERSION >= 14))

/**
 * Sync the contents of an allocation from memory space
 * specified by source.
 *
 * @param alloc
 * @param source
 */
extern void __attribute__((overloadable))
    rsgAllocationSyncAll(rs_allocation alloc,
                         rs_allocation_usage_type source);

#endif //defined(RS_VERSION) && (RS_VERSION >= 14)

/**
 * Low performance utility function for drawing a simple rectangle.  Not
 * intended for drawing large quantities of geometry.
 *
 * @param x1
 * @param y1
 * @param x2
 * @param y2
 * @param z
 */
extern void __attribute__((overloadable))
    rsgDrawRect(float x1, float y1, float x2, float y2, float z);

/**
 * Low performance utility function for drawing a simple quad.  Not intended for
 * drawing large quantities of geometry.
 *
 * @param x1
 * @param y1
 * @param z1
 * @param x2
 * @param y2
 * @param z2
 * @param x3
 * @param y3
 * @param z3
 * @param x4
 * @param y4
 * @param z4
 */
extern void __attribute__((overloadable))
    rsgDrawQuad(float x1, float y1, float z1,
                float x2, float y2, float z2,
                float x3, float y3, float z3,
                float x4, float y4, float z4);


/**
 * Low performance utility function for drawing a textured quad.  Not intended
 * for drawing large quantities of geometry.
 *
 * @param x1
 * @param y1
 * @param z1
 * @param u1
 * @param v1
 * @param x2
 * @param y2
 * @param z2
 * @param u2
 * @param v2
 * @param x3
 * @param y3
 * @param z3
 * @param u3
 * @param v3
 * @param x4
 * @param y4
 * @param z4
 * @param u4
 * @param v4
 */
extern void __attribute__((overloadable))
    rsgDrawQuadTexCoords(float x1, float y1, float z1, float u1, float v1,
                         float x2, float y2, float z2, float u2, float v2,
                         float x3, float y3, float z3, float u3, float v3,
                         float x4, float y4, float z4, float u4, float v4);


/**
 * Low performance function for drawing rectangles in screenspace.  This
 * function uses the default passthough ProgramVertex.  Any bound ProgramVertex
 * is ignored.  This function has considerable overhead and should not be used
 * for drawing in shipping applications.
 *
 * @param x
 * @param y
 * @param z
 * @param w
 * @param h
 */
extern void __attribute__((overloadable))
    rsgDrawSpriteScreenspace(float x, float y, float z, float w, float h);

extern void __attribute__((overloadable))
    rsgDrawPath(rs_path p);

/**
 * Draw a mesh using the current context state.  The whole mesh is
 * rendered.
 *
 * @param ism
 */
extern void __attribute__((overloadable))
    rsgDrawMesh(rs_mesh ism);
/**
 * Draw part of a mesh using the current context state.
 * @param ism mesh object to render
 * @param primitiveIndex for meshes that contain multiple primitive groups
 *        this parameter specifies the index of the group to draw.
 */
extern void __attribute__((overloadable))
    rsgDrawMesh(rs_mesh ism, uint primitiveIndex);
/**
 * Draw specified index range of part of a mesh using the current context state.
 * @param ism mesh object to render
 * @param primitiveIndex for meshes that contain multiple primitive groups
 *        this parameter specifies the index of the group to draw.
 * @param start starting index in the range
 * @param len number of indices to draw
 */
extern void __attribute__((overloadable))
    rsgDrawMesh(rs_mesh ism, uint primitiveIndex, uint start, uint len);

/**
 * Clears the rendering surface to the specified color.
 *
 * @param r
 * @param g
 * @param b
 * @param a
 */
extern void __attribute__((overloadable))
    rsgClearColor(float r, float g, float b, float a);

/**
 * Clears the depth suface to the specified value.
 */
extern void __attribute__((overloadable))
    rsgClearDepth(float value);
/**
 * Draws text given a string and location
 */
extern void __attribute__((overloadable))
    rsgDrawText(const char *, int x, int y);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsgDrawText(rs_allocation, int x, int y);
/**
 * Binds the font object to be used for all subsequent font rendering calls
 * @param font object to bind
 */
extern void __attribute__((overloadable))
    rsgBindFont(rs_font font);
/**
 * Sets the font color for all subsequent rendering calls
 * @param r red component
 * @param g green component
 * @param b blue component
 * @param a alpha component
 */
extern void __attribute__((overloadable))
    rsgFontColor(float r, float g, float b, float a);
/**
 * Returns the bounding box of the text relative to (0, 0)
 * Any of left, right, top, bottom could be NULL
 */
extern void __attribute__((overloadable))
    rsgMeasureText(const char *, int *left, int *right, int *top, int *bottom);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsgMeasureText(rs_allocation, int *left, int *right, int *top, int *bottom);
/**
 * Computes an axis aligned bounding box of a mesh object
 */
extern void __attribute__((overloadable))
    rsgMeshComputeBoundingBox(rs_mesh mesh, float *minX, float *minY, float *minZ,
                                                float *maxX, float *maxY, float *maxZ);
/**
 * \overload
 */
__inline__ static void __attribute__((overloadable, always_inline))
rsgMeshComputeBoundingBox(rs_mesh mesh, float3 *bBoxMin, float3 *bBoxMax) {
    float x1, y1, z1, x2, y2, z2;
    rsgMeshComputeBoundingBox(mesh, &x1, &y1, &z1, &x2, &y2, &z2);
    bBoxMin->x = x1;
    bBoxMin->y = y1;
    bBoxMin->z = z1;
    bBoxMax->x = x2;
    bBoxMax->y = y2;
    bBoxMax->z = z2;
}

#endif


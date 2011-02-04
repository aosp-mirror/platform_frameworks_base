#ifndef __RS_GRAPHICS_RSH__
#define __RS_GRAPHICS_RSH__


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
 * Bind a new Sampler object to a ProgramFragment.  The sampler will
 * operate on the texture bound at the matching slot.
 *
 * @param slot
 */
extern void __attribute__((overloadable))
    rsgBindSampler(rs_program_fragment, uint slot, rs_sampler);

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


extern void __attribute__((overloadable))
    rsgProgramVertexLoadProjectionMatrix(const rs_matrix4x4 *);
extern void __attribute__((overloadable))
    rsgProgramVertexLoadModelMatrix(const rs_matrix4x4 *);
extern void __attribute__((overloadable))
    rsgProgramVertexLoadTextureMatrix(const rs_matrix4x4 *);

extern void __attribute__((overloadable))
    rsgProgramVertexGetProjectionMatrix(rs_matrix4x4 *);

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

/**
 * Draw a mesh of geometry using the current context state.  The whole mesh is
 * rendered.
 *
 * @param ism
 */
extern void __attribute__((overloadable))
    rsgDrawMesh(rs_mesh ism);
extern void __attribute__((overloadable))
    rsgDrawMesh(rs_mesh ism, uint primitiveIndex);
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
 *
 */
extern void __attribute__((overloadable))
    rsgClearDepth(float value);

extern void __attribute__((overloadable))
    rsgDrawText(const char *, int x, int y);
extern void __attribute__((overloadable))
    rsgDrawText(rs_allocation, int x, int y);
extern void __attribute__((overloadable))
    rsgBindFont(rs_font);
extern void __attribute__((overloadable))
    rsgFontColor(float, float, float, float);
// Returns the bounding box of the text relative to (0, 0)
// Any of left, right, top, bottom could be NULL
extern void __attribute__((overloadable))
    rsgMeasureText(const char *, int *left, int *right, int *top, int *bottom);
extern void __attribute__((overloadable))
    rsgMeasureText(rs_allocation, int *left, int *right, int *top, int *bottom);

extern void __attribute__((overloadable))
    rsgMeshComputeBoundingBox(rs_mesh mesh, float *minX, float *minY, float *minZ,
                                                float *maxX, float *maxY, float *maxZ);
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


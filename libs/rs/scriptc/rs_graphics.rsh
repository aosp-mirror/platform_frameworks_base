#ifndef __RS_GRAPHICS_RSH__
#define __RS_GRAPHICS_RSH__

#include "rs_math.rsh"


// Bind a ProgramFragment to the RS context.
extern void __attribute__((overloadable))
    rsgBindProgramFragment(rs_program_fragment);
extern void __attribute__((overloadable))
    rsgBindProgramStore(rs_program_store);
extern void __attribute__((overloadable))
    rsgBindProgramVertex(rs_program_vertex);
extern void __attribute__((overloadable))
    rsgBindProgramRaster(rs_program_raster);

extern void __attribute__((overloadable))
    rsgBindSampler(rs_program_fragment, uint slot, rs_sampler);
extern void __attribute__((overloadable))
    rsgBindTexture(rs_program_fragment, uint slot, rs_allocation);

extern void __attribute__((overloadable))
    rsgProgramVertexLoadProjectionMatrix(const rs_matrix4x4 *);
extern void __attribute__((overloadable))
    rsgProgramVertexLoadModelMatrix(const rs_matrix4x4 *);
extern void __attribute__((overloadable))
    rsgProgramVertexLoadTextureMatrix(const rs_matrix4x4 *);

extern uint __attribute__((overloadable))
    rsgGetWidth(void);
extern uint __attribute__((overloadable))
    rsgGetHeight(void);

extern void __attribute__((overloadable))
    rsgUploadToTexture(rs_allocation);
extern void __attribute__((overloadable))
    rsgUploadToTexture(rs_allocation, uint mipLevel);
extern void __attribute__((overloadable))
    rsgUploadToBufferObject(rs_allocation);

extern void __attribute__((overloadable))
    rsgDrawRect(float x1, float y1, float x2, float y2, float z);
extern void __attribute__((overloadable))
    rsgDrawQuad(float x1, float y1, float z1,
                float x2, float y2, float z2,
                float x3, float y3, float z3,
                float x4, float y4, float z4);
extern void __attribute__((overloadable))
    rsgDrawQuadTexCoords(float x1, float y1, float z1, float u1, float v1,
                         float x2, float y2, float z2, float u2, float v2,
                         float x3, float y3, float z3, float u3, float v3,
                         float x4, float y4, float z4, float u4, float v4);
extern void __attribute__((overloadable))
    rsgDrawSpriteScreenspace(float x, float y, float z, float w, float h);

extern void __attribute__((overloadable))
    rsgDrawMesh(rs_mesh ism);
extern void __attribute__((overloadable))
    rsgDrawMesh(rs_mesh ism, uint primitiveIndex);
extern void __attribute__((overloadable))
    rsgDrawMesh(rs_mesh ism, uint primitiveIndex, uint start, uint len);

extern void __attribute__((overloadable))
    rsgClearColor(float, float, float, float);
extern void __attribute__((overloadable))
    rsgClearDepth(float);

extern void __attribute__((overloadable))
    rsgDrawText(const char *, int x, int y);
extern void __attribute__((overloadable))
    rsgDrawText(rs_allocation, int x, int y);
extern void __attribute__((overloadable))
    rsgBindFont(rs_font);
extern void __attribute__((overloadable))
    rsgFontColor(float, float, float, float);

///////////////////////////////////////////////////////
// misc
extern void __attribute__((overloadable))
    color(float, float, float, float);

#endif


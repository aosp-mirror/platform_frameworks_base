#ifndef __RS_GRAPHICS_RSH__
#define __RS_GRAPHICS_RSH__

#include "rs_math.rsh"


// context
extern void rsgBindProgramFragment(rs_program_fragment);
extern void rsgBindProgramStore(rs_program_store);
extern void rsgBindProgramVertex(rs_program_vertex);
extern void rsgBindProgramRaster(rs_program_raster);

extern void rsgBindSampler(rs_program_fragment, int slot, rs_sampler);
extern void rsgBindTexture(rs_program_fragment, int slot, rs_allocation);

extern void rsgProgramVertexLoadModelMatrix(const rs_matrix4x4 *);
extern void rsgProgramVertexLoadTextureMatrix(const rs_matrix4x4 *);

extern int rsgGetWidth();
extern int rsgGetHeight();

extern void __attribute__((overloadable)) rsgUploadToTexture(rs_allocation);
extern void __attribute__((overloadable)) rsgUploadToTexture(rs_allocation, int mipLevel);
extern void rsgUploadToBufferObject(rs_allocation);
//extern void rsgUploadMesh(rs_mesh);

extern void rsgDrawRect(float x1, float y1, float x2, float y2, float z);
extern void rsgDrawQuad(float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4);
extern void rsgDrawQuadTexCoords(float x1, float y1, float z1, float u1, float v1, float x2, float y2, float z2, float u2, float v2, float x3, float y3, float z3, float u3, float v3, float x4, float y4, float z4, float u4, float v4);
//extern void rsgDrawSprite(float x, float y, float z, float w, float h);
extern void rsgDrawSpriteScreenspace(float x, float y, float z, float w, float h);
extern void rsgDrawLine(float x1, float y1, float z1, float x2, float y2, float z2);
extern void rsgDrawPoint(float x1, float y1, float z1);
extern void __attribute__((overloadable)) rsgDrawSimpleMesh(rs_mesh ism);
extern void __attribute__((overloadable)) rsgDrawSimpleMesh(rs_mesh ism, int start, int len);

extern void rsgClearColor(float, float, float, float);
extern void rsgClearDepth(float);

///////////////////////////////////////////////////////
// misc
extern void color(float, float, float, float);
extern void hsb(float, float, float, float);
extern void hsbToRgb(float, float, float, float*);
extern int hsbToAbgr(float, float, float, float);

#endif


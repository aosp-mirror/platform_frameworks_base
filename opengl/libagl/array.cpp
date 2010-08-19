/*
** Copyright 2006, The Android Open Source Project
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

#include <stdlib.h>
#include <stdio.h>

#include "context.h"
#include "fp.h"
#include "state.h"
#include "matrix.h"
#include "vertex.h"
#include "light.h"
#include "primitives.h"
#include "texture.h"
#include "BufferObjectManager.h"

// ----------------------------------------------------------------------------

#define VC_CACHE_STATISTICS     0
#define VC_CACHE_TYPE_NONE      0
#define VC_CACHE_TYPE_INDEXED   1
#define VC_CACHE_TYPE_LRU       2
#define VC_CACHE_TYPE           VC_CACHE_TYPE_INDEXED

#if VC_CACHE_STATISTICS
#include <utils/Timers.h>
#endif

// ----------------------------------------------------------------------------

namespace android {

static void validate_arrays(ogles_context_t* c, GLenum mode);

static void compileElements__generic(ogles_context_t*,
        vertex_t*, GLint, GLsizei);
static void compileElement__generic(ogles_context_t*,
        vertex_t*, GLint);

static void drawPrimitivesPoints(ogles_context_t*, GLint, GLsizei);
static void drawPrimitivesLineStrip(ogles_context_t*, GLint, GLsizei);
static void drawPrimitivesLineLoop(ogles_context_t*, GLint, GLsizei);
static void drawPrimitivesLines(ogles_context_t*, GLint, GLsizei);
static void drawPrimitivesTriangleStrip(ogles_context_t*, GLint, GLsizei);
static void drawPrimitivesTriangleFan(ogles_context_t*, GLint, GLsizei);
static void drawPrimitivesTriangles(ogles_context_t*, GLint, GLsizei);

static void drawIndexedPrimitivesPoints(ogles_context_t*,
        GLsizei, const GLvoid*);
static void drawIndexedPrimitivesLineStrip(ogles_context_t*,
        GLsizei, const GLvoid*);
static void drawIndexedPrimitivesLineLoop(ogles_context_t*,
        GLsizei, const GLvoid*);
static void drawIndexedPrimitivesLines(ogles_context_t*,
        GLsizei, const GLvoid*);
static void drawIndexedPrimitivesTriangleStrip(ogles_context_t*,
        GLsizei, const GLvoid*);
static void drawIndexedPrimitivesTriangleFan(ogles_context_t*,
        GLsizei, const GLvoid*);
static void drawIndexedPrimitivesTriangles(ogles_context_t*,
        GLsizei, const GLvoid*);

// ----------------------------------------------------------------------------

typedef void (*arrays_prims_fct_t)(ogles_context_t*, GLint, GLsizei);
static const arrays_prims_fct_t drawArraysPrims[] = {
    drawPrimitivesPoints,
    drawPrimitivesLines,
    drawPrimitivesLineLoop,
    drawPrimitivesLineStrip,
    drawPrimitivesTriangles,
    drawPrimitivesTriangleStrip,
    drawPrimitivesTriangleFan
};

typedef void (*elements_prims_fct_t)(ogles_context_t*, GLsizei, const GLvoid*);
static const elements_prims_fct_t drawElementsPrims[] = {
    drawIndexedPrimitivesPoints,
    drawIndexedPrimitivesLines,
    drawIndexedPrimitivesLineLoop,
    drawIndexedPrimitivesLineStrip,
    drawIndexedPrimitivesTriangles,
    drawIndexedPrimitivesTriangleStrip,
    drawIndexedPrimitivesTriangleFan
};

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

void ogles_init_array(ogles_context_t* c)
{
    c->arrays.vertex.size = 4;
    c->arrays.vertex.type = GL_FLOAT;
    c->arrays.color.size = 4;
    c->arrays.color.type = GL_FLOAT;
    c->arrays.normal.size = 4;
    c->arrays.normal.type = GL_FLOAT;
    for (int i=0 ; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
        c->arrays.texture[i].size = 4;
        c->arrays.texture[i].type = GL_FLOAT;
    }
    c->vc.init();

    if (!c->vc.vBuffer) {
        // this could have failed
        ogles_error(c, GL_OUT_OF_MEMORY);
    }
}

void ogles_uninit_array(ogles_context_t* c)
{
    c->vc.uninit();
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Array fetchers
#endif

static void currentColor(ogles_context_t* c, GLfixed* v, const GLvoid*) {
    memcpy(v, c->current.color.v, sizeof(vec4_t));
}
static void currentColor_clamp(ogles_context_t* c, GLfixed* v, const GLvoid*) {
    memcpy(v, c->currentColorClamped.v, sizeof(vec4_t));
}
static void currentNormal(ogles_context_t* c, GLfixed* v, const GLvoid*) {
    memcpy(v, c->currentNormal.v, sizeof(vec3_t));
}
static void currentTexCoord(ogles_context_t* c, GLfixed* v, const GLvoid*) {
    memcpy(v, c->current.texture[c->arrays.tmu].v, sizeof(vec4_t));
}


static void fetchNop(ogles_context_t*, GLfixed*, const GLvoid*) {
}
static void fetch2b(ogles_context_t*, GLfixed* v, const GLbyte* p) {
    v[0] = gglIntToFixed(p[0]);
    v[1] = gglIntToFixed(p[1]);
}
static void fetch2s(ogles_context_t*, GLfixed* v, const GLshort* p) {
    v[0] = gglIntToFixed(p[0]);
    v[1] = gglIntToFixed(p[1]);
}
static void fetch2x(ogles_context_t*, GLfixed* v, const GLfixed* p) {
    memcpy(v, p, 2*sizeof(GLfixed));
}
static void fetch2f(ogles_context_t*, GLfixed* v, const GLfloat* p) {
    v[0] = gglFloatToFixed(p[0]);
    v[1] = gglFloatToFixed(p[1]);
}
static void fetch3b(ogles_context_t*, GLfixed* v, const GLbyte* p) {
    v[0] = gglIntToFixed(p[0]);
    v[1] = gglIntToFixed(p[1]);
    v[2] = gglIntToFixed(p[2]);
}
static void fetch3s(ogles_context_t*, GLfixed* v, const GLshort* p) {
    v[0] = gglIntToFixed(p[0]);
    v[1] = gglIntToFixed(p[1]);
    v[2] = gglIntToFixed(p[2]);
}
static void fetch3x(ogles_context_t*, GLfixed* v, const GLfixed* p) {
    memcpy(v, p, 3*sizeof(GLfixed));
}
static void fetch3f(ogles_context_t*, GLfixed* v, const GLfloat* p) {
    v[0] = gglFloatToFixed(p[0]);
    v[1] = gglFloatToFixed(p[1]);
    v[2] = gglFloatToFixed(p[2]);
}
static void fetch4b(ogles_context_t*, GLfixed* v, const GLbyte* p) {
    v[0] = gglIntToFixed(p[0]);
    v[1] = gglIntToFixed(p[1]);
    v[2] = gglIntToFixed(p[2]);
    v[3] = gglIntToFixed(p[3]);
}
static void fetch4s(ogles_context_t*, GLfixed* v, const GLshort* p) {
    v[0] = gglIntToFixed(p[0]);
    v[1] = gglIntToFixed(p[1]);
    v[2] = gglIntToFixed(p[2]);
    v[3] = gglIntToFixed(p[3]);
}
static void fetch4x(ogles_context_t*, GLfixed* v, const GLfixed* p) {
    memcpy(v, p, 4*sizeof(GLfixed));
}
static void fetch4f(ogles_context_t*, GLfixed* v, const GLfloat* p) {
    v[0] = gglFloatToFixed(p[0]);
    v[1] = gglFloatToFixed(p[1]);
    v[2] = gglFloatToFixed(p[2]);
    v[3] = gglFloatToFixed(p[3]);
}
static void fetchExpand4ub(ogles_context_t*, GLfixed* v, const GLubyte* p) {
    v[0] = GGL_UB_TO_X(p[0]);
    v[1] = GGL_UB_TO_X(p[1]);
    v[2] = GGL_UB_TO_X(p[2]);
    v[3] = GGL_UB_TO_X(p[3]);
}
static void fetchClamp4x(ogles_context_t*, GLfixed* v, const GLfixed* p) {
    v[0] = gglClampx(p[0]);
    v[1] = gglClampx(p[1]);
    v[2] = gglClampx(p[2]);
    v[3] = gglClampx(p[3]);
}
static void fetchClamp4f(ogles_context_t*, GLfixed* v, const GLfloat* p) {
    v[0] = gglClampx(gglFloatToFixed(p[0]));
    v[1] = gglClampx(gglFloatToFixed(p[1]));
    v[2] = gglClampx(gglFloatToFixed(p[2]));
    v[3] = gglClampx(gglFloatToFixed(p[3]));
}
static void fetchExpand3ub(ogles_context_t*, GLfixed* v, const GLubyte* p) {
    v[0] = GGL_UB_TO_X(p[0]);
    v[1] = GGL_UB_TO_X(p[1]);
    v[2] = GGL_UB_TO_X(p[2]);
    v[3] = 0x10000;
}
static void fetchClamp3x(ogles_context_t*, GLfixed* v, const GLfixed* p) {
    v[0] = gglClampx(p[0]);
    v[1] = gglClampx(p[1]);
    v[2] = gglClampx(p[2]);
    v[3] = 0x10000;
}
static void fetchClamp3f(ogles_context_t*, GLfixed* v, const GLfloat* p) {
    v[0] = gglClampx(gglFloatToFixed(p[0]));
    v[1] = gglClampx(gglFloatToFixed(p[1]));
    v[2] = gglClampx(gglFloatToFixed(p[2]));
    v[3] = 0x10000;
}
static void fetchExpand3b(ogles_context_t*, GLfixed* v, const GLbyte* p) {
    v[0] = GGL_B_TO_X(p[0]);
    v[1] = GGL_B_TO_X(p[1]);
    v[2] = GGL_B_TO_X(p[2]);
}
static void fetchExpand3s(ogles_context_t*, GLfixed* v, const GLshort* p) {
    v[0] = GGL_S_TO_X(p[0]);
    v[1] = GGL_S_TO_X(p[1]);
    v[2] = GGL_S_TO_X(p[2]);
}

typedef array_t::fetcher_t fn_t;

static const fn_t color_fct[2][16] = { // size={3,4}, type={ub,f,x}
    { 0, (fn_t)fetchExpand3ub, 0, 0, 0, 0,
         (fn_t)fetch3f, 0, 0, 0, 0, 0,
         (fn_t)fetch3x },
    { 0, (fn_t)fetchExpand4ub, 0, 0, 0, 0,
         (fn_t)fetch4f, 0, 0, 0, 0, 0,
         (fn_t)fetch4x },
};
static const fn_t color_clamp_fct[2][16] = { // size={3,4}, type={ub,f,x}
    { 0, (fn_t)fetchExpand3ub, 0, 0, 0, 0,
         (fn_t)fetchClamp3f, 0, 0, 0, 0, 0,
         (fn_t)fetchClamp3x },
    { 0, (fn_t)fetchExpand4ub, 0, 0, 0, 0,
         (fn_t)fetchClamp4f, 0, 0, 0, 0, 0,
         (fn_t)fetchClamp4x },
};
static const fn_t normal_fct[1][16] = { // size={3}, type={b,s,f,x}
    { (fn_t)fetchExpand3b, 0,
      (fn_t)fetchExpand3s, 0, 0, 0,
      (fn_t)fetch3f, 0, 0, 0, 0, 0,
      (fn_t)fetch3x },
};
static const fn_t vertex_fct[3][16] = { // size={2,3,4}, type={b,s,f,x}
    { (fn_t)fetch2b, 0,
      (fn_t)fetch2s, 0, 0, 0,
      (fn_t)fetch2f, 0, 0, 0, 0, 0,
      (fn_t)fetch3x },
    { (fn_t)fetch3b, 0,
      (fn_t)fetch3s, 0, 0, 0,
      (fn_t)fetch3f, 0, 0, 0, 0, 0,
      (fn_t)fetch3x },
    { (fn_t)fetch4b, 0,
      (fn_t)fetch4s, 0, 0, 0,
      (fn_t)fetch4f, 0, 0, 0, 0, 0,
      (fn_t)fetch4x }
};
static const fn_t texture_fct[3][16] = { // size={2,3,4}, type={b,s,f,x}
    { (fn_t)fetch2b, 0,
      (fn_t)fetch2s, 0, 0, 0,
      (fn_t)fetch2f, 0, 0, 0, 0, 0,
      (fn_t)fetch2x },
    { (fn_t)fetch3b, 0,
      (fn_t)fetch3s, 0, 0, 0,
      (fn_t)fetch3f, 0, 0, 0, 0, 0,
      (fn_t)fetch3x },
    { (fn_t)fetch4b, 0,
      (fn_t)fetch4s, 0, 0, 0,
      (fn_t)fetch4f, 0, 0, 0, 0, 0,
      (fn_t)fetch4x }
};

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark array_t
#endif

void array_t::init(
        GLint size, GLenum type, GLsizei stride,
        const GLvoid *pointer, const buffer_t* bo, GLsizei count)
{
    if (!stride) {
        stride = size;
        switch (type) {
        case GL_SHORT:
        case GL_UNSIGNED_SHORT:
            stride *= 2;
            break;
        case GL_FLOAT:
        case GL_FIXED:
            stride *= 4;
            break;
        }
    }
    this->size = size;
    this->type = type;
    this->stride = stride;
    this->pointer = pointer;
    this->bo = bo;
    this->bounds = count;
}

inline void array_t::resolve()
{
    physical_pointer = (bo) ? (bo->data + uintptr_t(pointer)) : pointer;
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark vertex_cache_t
#endif

void vertex_cache_t::init()
{
    // make sure the size of vertex_t allows cache-line alignment
    CTA<(sizeof(vertex_t) & 0x1F) == 0> assertAlignedSize;

    const int align = 32;
    const size_t s = VERTEX_BUFFER_SIZE + VERTEX_CACHE_SIZE;
    const size_t size = s*sizeof(vertex_t) + align;
    base = malloc(size);
    if (base) {
        memset(base, 0, size);
        vBuffer = (vertex_t*)((size_t(base) + align - 1) & ~(align-1));
        vCache = vBuffer + VERTEX_BUFFER_SIZE;
        sequence = 0;
    }
}

void vertex_cache_t::uninit()
{
    free(base);
    base = vBuffer = vCache = 0;
}

void vertex_cache_t::clear()
{
#if VC_CACHE_STATISTICS
    startTime = systemTime(SYSTEM_TIME_THREAD);
    total = 0;
    misses = 0;
#endif

#if VC_CACHE_TYPE == VC_CACHE_TYPE_LRU
    vertex_t* v = vBuffer;
    size_t count = VERTEX_BUFFER_SIZE + VERTEX_CACHE_SIZE;
    do {
        v->mru = 0;
        v++;
    } while (--count);
#endif

    sequence += INDEX_SEQ;
    if (sequence >= 0x80000000LU) {
        sequence = INDEX_SEQ;
        vertex_t* v = vBuffer;
        size_t count = VERTEX_BUFFER_SIZE + VERTEX_CACHE_SIZE;
        do {
            v->index = 0;
            v++;
        } while (--count);
    }
}

void vertex_cache_t::dump_stats(GLenum mode)
{
#if VC_CACHE_STATISTICS
    nsecs_t time = systemTime(SYSTEM_TIME_THREAD) - startTime;
    uint32_t hits = total - misses;
    uint32_t prim_count;
    switch (mode) {
    case GL_POINTS:             prim_count = total;         break;
    case GL_LINE_STRIP:         prim_count = total - 1;     break;
    case GL_LINE_LOOP:          prim_count = total - 1;     break;
    case GL_LINES:              prim_count = total / 2;     break;
    case GL_TRIANGLE_STRIP:     prim_count = total - 2;     break;
    case GL_TRIANGLE_FAN:       prim_count = total - 2;     break;
    case GL_TRIANGLES:          prim_count = total / 3;     break;
    default:    return;
    }
    printf( "total=%5u, hits=%5u, miss=%5u, hitrate=%3u%%,"
            " prims=%5u, time=%6u us, prims/s=%d, v/t=%f\n",
            total, hits, misses, (hits*100)/total,
            prim_count, int(ns2us(time)), int(prim_count*float(seconds(1))/time),
            float(misses) / prim_count);
#endif
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

static __attribute__((noinline))
void enableDisableClientState(ogles_context_t* c, GLenum array, bool enable)
{
    const int tmu = c->arrays.activeTexture;
    array_t* a;
    switch (array) {
    case GL_COLOR_ARRAY:            a = &c->arrays.color;           break;
    case GL_NORMAL_ARRAY:           a = &c->arrays.normal;          break;
    case GL_TEXTURE_COORD_ARRAY:    a = &c->arrays.texture[tmu];    break;
    case GL_VERTEX_ARRAY:           a = &c->arrays.vertex;          break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    a->enable = enable ? GL_TRUE : GL_FALSE;
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Vertex Cache
#endif

static __attribute__((noinline))
vertex_t* cache_vertex(ogles_context_t* c, vertex_t* v, uint32_t index)
{
    #if VC_CACHE_STATISTICS
        c->vc.misses++;
    #endif
    if (ggl_unlikely(v->locked)) {
        // we're just looking for an entry in the cache that is not locked.
        // and we know that there cannot be more than 2 locked entries
        // because a triangle needs at most 3 vertices.
        // We never use the first and second entries because they might be in
        // use by the striper or faner. Any other entry will do as long as
        // it's not locked.
        // We compute directly the index of a "free" entry from the locked
        // state of v[2] and v[3].
        v = c->vc.vBuffer + 2;
        v += v[0].locked | (v[1].locked<<1);
    }
    // note: compileElement clears v->flags
    c->arrays.compileElement(c, v, index);
    v->locked = 1;
    return v;
}

static __attribute__((noinline))
vertex_t* fetch_vertex(ogles_context_t* c, size_t index)
{
    index |= c->vc.sequence;

#if VC_CACHE_TYPE == VC_CACHE_TYPE_INDEXED

    vertex_t* const v = c->vc.vCache +
            (index & (vertex_cache_t::VERTEX_CACHE_SIZE-1));

    if (ggl_likely(v->index == index)) {
        v->locked = 1;
        return v;
    }
    return cache_vertex(c, v, index);

#elif VC_CACHE_TYPE == VC_CACHE_TYPE_LRU

    vertex_t* v = c->vc.vCache +
            (index & ((vertex_cache_t::VERTEX_CACHE_SIZE-1)>>1))*2;

    // always record LRU in v[0]
    if (ggl_likely(v[0].index == index)) {
        v[0].locked = 1;
        v[0].mru = 0;
        return &v[0];
    }

    if (ggl_likely(v[1].index == index)) {
        v[1].locked = 1;
        v[0].mru = 1;
        return &v[1];
    }

    const int lru = 1 - v[0].mru;
    v[0].mru = lru;
    return cache_vertex(c, &v[lru], index);

#elif VC_CACHE_TYPE == VC_CACHE_TYPE_NONE

    // just for debugging...
    vertex_t* v = c->vc.vBuffer + 2;
    return cache_vertex(c, v, index);

#endif
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Primitive Assembly
#endif

void drawPrimitivesPoints(ogles_context_t* c, GLint first, GLsizei count)
{
    if (ggl_unlikely(count < 1))
        return;

    // vertex cache size must be multiple of 1
    const GLsizei vcs =
            (vertex_cache_t::VERTEX_BUFFER_SIZE +
             vertex_cache_t::VERTEX_CACHE_SIZE);
    do {
        vertex_t* v = c->vc.vBuffer;
        GLsizei num = count > vcs ? vcs : count;
        c->arrays.cull = vertex_t::CLIP_ALL;
        c->arrays.compileElements(c, v, first, num);
        first += num;
        count -= num;
        if (!c->arrays.cull) {
            // quick/trivial reject of the whole batch
            do {
                const uint32_t cc = v[0].flags;
                if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
                    c->prims.renderPoint(c, v);
                v++;
                num--;
            } while (num);
        }
    } while (count);
}

// ----------------------------------------------------------------------------

void drawPrimitivesLineStrip(ogles_context_t* c, GLint first, GLsizei count)
{
    if (ggl_unlikely(count < 2))
        return;

    vertex_t *v, *v0, *v1;
    c->arrays.cull = vertex_t::CLIP_ALL;
    c->arrays.compileElement(c, c->vc.vBuffer, first);
    first += 1;
    count -= 1;

    // vertex cache size must be multiple of 1
    const GLsizei vcs =
        (vertex_cache_t::VERTEX_BUFFER_SIZE +
         vertex_cache_t::VERTEX_CACHE_SIZE - 1);
    do {
        v0 = c->vc.vBuffer + 0;
        v  = c->vc.vBuffer + 1;
        GLsizei num = count > vcs ? vcs : count;
        c->arrays.compileElements(c, v, first, num);
        first += num;
        count -= num;
        if (!c->arrays.cull) {
            // quick/trivial reject of the whole batch
            do {
                v1 = v++;
                const uint32_t cc = v0->flags & v1->flags;
                if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
                    c->prims.renderLine(c, v0, v1);
                v0 = v1;
                num--;
            } while (num);
        }
        // copy back the last processed vertex
        c->vc.vBuffer[0] = *v0;
        c->arrays.cull = v0->flags & vertex_t::CLIP_ALL;
    } while (count);
}

void drawPrimitivesLineLoop(ogles_context_t* c, GLint first, GLsizei count)
{
    if (ggl_unlikely(count < 2))
        return;
    drawPrimitivesLineStrip(c, first, count);
    if (ggl_likely(count >= 3)) {
        vertex_t* v0 = c->vc.vBuffer;
        vertex_t* v1 = c->vc.vBuffer + 1;
        c->arrays.compileElement(c, v1, first);
        const uint32_t cc = v0->flags & v1->flags;
        if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
            c->prims.renderLine(c, v0, v1);
    }
}

void drawPrimitivesLines(ogles_context_t* c, GLint first, GLsizei count)
{
    if (ggl_unlikely(count < 2))
        return;

    // vertex cache size must be multiple of 2
    const GLsizei vcs =
        ((vertex_cache_t::VERTEX_BUFFER_SIZE +
        vertex_cache_t::VERTEX_CACHE_SIZE) / 2) * 2;
    do {
        vertex_t* v = c->vc.vBuffer;
        GLsizei num = count > vcs ? vcs : count;
        c->arrays.cull = vertex_t::CLIP_ALL;
        c->arrays.compileElements(c, v, first, num);
        first += num;
        count -= num;
        if (!c->arrays.cull) {
            // quick/trivial reject of the whole batch
            num -= 2;
            do {
                const uint32_t cc = v[0].flags & v[1].flags;
                if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
                    c->prims.renderLine(c, v, v+1);
                v += 2;
                num -= 2;
            } while (num >= 0);
        }
    } while (count >= 2);
}

// ----------------------------------------------------------------------------

static void drawPrimitivesTriangleFanOrStrip(ogles_context_t* c,
        GLint first, GLsizei count, int winding)
{
    // winding == 2 : fan
    // winding == 1 : strip

    if (ggl_unlikely(count < 3))
        return;

    vertex_t *v, *v0, *v1, *v2;
    c->arrays.cull = vertex_t::CLIP_ALL;
    c->arrays.compileElements(c, c->vc.vBuffer, first, 2);
    first += 2;
    count -= 2;

    // vertex cache size must be multiple of 2. This is extremely important
    // because it allows us to preserve the same winding when the whole
    // batch is culled. We also need 2 extra vertices in the array, because
    // we always keep the two first ones.
    const GLsizei vcs =
        ((vertex_cache_t::VERTEX_BUFFER_SIZE +
          vertex_cache_t::VERTEX_CACHE_SIZE - 2) / 2) * 2;
    do {
        v0 = c->vc.vBuffer + 0;
        v1 = c->vc.vBuffer + 1;
        v  = c->vc.vBuffer + 2;
        GLsizei num = count > vcs ? vcs : count;
        c->arrays.compileElements(c, v, first, num);
        first += num;
        count -= num;
        if (!c->arrays.cull) {
            // quick/trivial reject of the whole batch
            do {
                v2 = v++;
                const uint32_t cc = v0->flags & v1->flags & v2->flags;
                if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
                    c->prims.renderTriangle(c, v0, v1, v2);
                swap(((winding^=1) ? v1 : v0), v2);
                num--;
            } while (num);
        }
        if (count) {
            v0 = c->vc.vBuffer + 2 + vcs - 2;
            v1 = c->vc.vBuffer + 2 + vcs - 1;
            if ((winding&2) == 0) {
                // for strips copy back the two last compiled vertices
                c->vc.vBuffer[0] = *v0;
            }
            c->vc.vBuffer[1] = *v1;
            c->arrays.cull = v0->flags & v1->flags & vertex_t::CLIP_ALL;
        }
    } while (count > 0);
}

void drawPrimitivesTriangleStrip(ogles_context_t* c,
        GLint first, GLsizei count) {
    drawPrimitivesTriangleFanOrStrip(c, first, count, 1);
}

void drawPrimitivesTriangleFan(ogles_context_t* c,
        GLint first, GLsizei count) {
    drawPrimitivesTriangleFanOrStrip(c, first, count, 2);
}

void drawPrimitivesTriangles(ogles_context_t* c, GLint first, GLsizei count)
{
    if (ggl_unlikely(count < 3))
        return;

    // vertex cache size must be multiple of 3
    const GLsizei vcs =
        ((vertex_cache_t::VERTEX_BUFFER_SIZE +
        vertex_cache_t::VERTEX_CACHE_SIZE) / 3) * 3;
    do {
        vertex_t* v = c->vc.vBuffer;
        GLsizei num = count > vcs ? vcs : count;
        c->arrays.cull = vertex_t::CLIP_ALL;
        c->arrays.compileElements(c, v, first, num);
        first += num;
        count -= num;
        if (!c->arrays.cull) {
            // quick/trivial reject of the whole batch
            num -= 3;
            do {
                const uint32_t cc = v[0].flags & v[1].flags & v[2].flags;
                if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
                    c->prims.renderTriangle(c, v, v+1, v+2);
                v += 3;
                num -= 3;
            } while (num >= 0);
        }
    } while (count >= 3);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

// this looks goofy, but gcc does a great job with this...
static inline unsigned int read_index(int type, const GLvoid*& p) {
    unsigned int r;
    if (type) {
        r = *(const GLubyte*)p;
        p = (const GLubyte*)p + 1;
    } else {
        r = *(const GLushort*)p;
        p = (const GLushort*)p + 1;
    }
    return r;
}

// ----------------------------------------------------------------------------

void drawIndexedPrimitivesPoints(ogles_context_t* c,
        GLsizei count, const GLvoid *indices)
{
    if (ggl_unlikely(count < 1))
        return;
    const int type = (c->arrays.indicesType == GL_UNSIGNED_BYTE);
    do {
        vertex_t * v = fetch_vertex(c, read_index(type, indices));
        if (ggl_likely(!(v->flags & vertex_t::CLIP_ALL)))
            c->prims.renderPoint(c, v);
        v->locked = 0;
        count--;
    } while(count);
}

// ----------------------------------------------------------------------------

void drawIndexedPrimitivesLineStrip(ogles_context_t* c,
        GLsizei count, const GLvoid *indices)
{
    if (ggl_unlikely(count < 2))
        return;

    vertex_t * const v = c->vc.vBuffer;
    vertex_t* v0 = v;
    vertex_t* v1;

    const int type = (c->arrays.indicesType == GL_UNSIGNED_BYTE);
    c->arrays.compileElement(c, v0, read_index(type, indices));
    count -= 1;
    do {
        v1 = fetch_vertex(c, read_index(type, indices));
        const uint32_t cc = v0->flags & v1->flags;
        if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
            c->prims.renderLine(c, v0, v1);
        v0->locked = 0;
        v0 = v1;
        count--;
    } while (count);
    v1->locked = 0;
}

void drawIndexedPrimitivesLineLoop(ogles_context_t* c,
        GLsizei count, const GLvoid *indices)
{
    if (ggl_unlikely(count <= 2)) {
        drawIndexedPrimitivesLines(c, count, indices);
        return;
    }

    vertex_t * const v = c->vc.vBuffer;
    vertex_t* v0 = v;
    vertex_t* v1;

    const int type = (c->arrays.indicesType == GL_UNSIGNED_BYTE);
    c->arrays.compileElement(c, v0, read_index(type, indices));
    count -= 1;
    do {
        v1 = fetch_vertex(c, read_index(type, indices));
        const uint32_t cc = v0->flags & v1->flags;
        if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
            c->prims.renderLine(c, v0, v1);
        v0->locked = 0;
        v0 = v1;
        count--;
    } while (count);
    v1->locked = 0;

    v1 = c->vc.vBuffer;
    const uint32_t cc = v0->flags & v1->flags;
    if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
        c->prims.renderLine(c, v0, v1);
}

void drawIndexedPrimitivesLines(ogles_context_t* c,
        GLsizei count, const GLvoid *indices)
{
    if (ggl_unlikely(count < 2))
        return;

    count -= 2;
    const int type = (c->arrays.indicesType == GL_UNSIGNED_BYTE);
    do {
        vertex_t* const v0 = fetch_vertex(c, read_index(type, indices));
        vertex_t* const v1 = fetch_vertex(c, read_index(type, indices));
        const uint32_t cc = v0->flags & v1->flags;
        if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
            c->prims.renderLine(c, v0, v1);
        v0->locked = 0;
        v1->locked = 0;
        count -= 2;
    } while (count >= 0);
}

// ----------------------------------------------------------------------------

static void drawIndexedPrimitivesTriangleFanOrStrip(ogles_context_t* c,
        GLsizei count, const GLvoid *indices, int winding)
{
    // winding == 2 : fan
    // winding == 1 : strip

    if (ggl_unlikely(count < 3))
        return;

    vertex_t * const v = c->vc.vBuffer;
    vertex_t* v0 = v;
    vertex_t* v1 = v+1;
    vertex_t* v2;

    const int type = (c->arrays.indicesType == GL_UNSIGNED_BYTE);
    c->arrays.compileElement(c, v0, read_index(type, indices));
    c->arrays.compileElement(c, v1, read_index(type, indices));
    count -= 2;

    // note: GCC 4.1.1 here makes a prety interesting optimization
    // where it duplicates the loop below based on c->arrays.indicesType

    do {
        v2 = fetch_vertex(c, read_index(type, indices));
        const uint32_t cc = v0->flags & v1->flags & v2->flags;
        if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
            c->prims.renderTriangle(c, v0, v1, v2);
        vertex_t* & consumed = ((winding^=1) ? v1 : v0);
        consumed->locked = 0;
        consumed = v2;
        count--;
    } while (count);
    v0->locked = v1->locked = 0;
    v2->locked = 0;
}

void drawIndexedPrimitivesTriangleStrip(ogles_context_t* c,
        GLsizei count, const GLvoid *indices) {
    drawIndexedPrimitivesTriangleFanOrStrip(c, count, indices, 1);
}

void drawIndexedPrimitivesTriangleFan(ogles_context_t* c,
        GLsizei count, const GLvoid *indices) {
    drawIndexedPrimitivesTriangleFanOrStrip(c, count, indices, 2);
}

void drawIndexedPrimitivesTriangles(ogles_context_t* c,
        GLsizei count, const GLvoid *indices)
{
    if (ggl_unlikely(count < 3))
        return;

    count -= 3;
    if (ggl_likely(c->arrays.indicesType == GL_UNSIGNED_SHORT)) {
        // This case is probably our most common case...
        uint16_t const * p = (uint16_t const *)indices;
        do {
            vertex_t* const v0 = fetch_vertex(c, *p++);
            vertex_t* const v1 = fetch_vertex(c, *p++);
            vertex_t* const v2 = fetch_vertex(c, *p++);
            const uint32_t cc = v0->flags & v1->flags & v2->flags;
            if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
                c->prims.renderTriangle(c, v0, v1, v2);
            v0->locked = 0;
            v1->locked = 0;
            v2->locked = 0;
            count -= 3;
        } while (count >= 0);
    } else {
        uint8_t const * p = (uint8_t const *)indices;
        do {
            vertex_t* const v0 = fetch_vertex(c, *p++);
            vertex_t* const v1 = fetch_vertex(c, *p++);
            vertex_t* const v2 = fetch_vertex(c, *p++);
            const uint32_t cc = v0->flags & v1->flags & v2->flags;
            if (ggl_likely(!(cc & vertex_t::CLIP_ALL)))
                c->prims.renderTriangle(c, v0, v1, v2);
            v0->locked = 0;
            v1->locked = 0;
            v2->locked = 0;
            count -= 3;
        } while (count >= 0);
    }
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Array compilers
#endif

void compileElement__generic(ogles_context_t* c,
        vertex_t* v, GLint first)
{
    v->flags = 0;
    v->index = first;
    first &= vertex_cache_t::INDEX_MASK;
    const GLubyte* vp = c->arrays.vertex.element(first);
    v->obj.z = 0;
    v->obj.w = 0x10000;
    c->arrays.vertex.fetch(c, v->obj.v, vp);
    c->arrays.mvp_transform(&c->transforms.mvp, &v->clip, &v->obj);
    c->arrays.perspective(c, v);
}

void compileElements__generic(ogles_context_t* c,
        vertex_t* v, GLint first, GLsizei count)
{
    const GLubyte* vp = c->arrays.vertex.element(
            first & vertex_cache_t::INDEX_MASK);
    const size_t stride = c->arrays.vertex.stride;
    transform_t const* const mvp = &c->transforms.mvp;
    do {
        v->flags = 0;
        v->index = first++;
        v->obj.z = 0;
        v->obj.w = 0x10000;
        c->arrays.vertex.fetch(c, v->obj.v, vp);
        c->arrays.mvp_transform(mvp, &v->clip, &v->obj);
        c->arrays.perspective(c, v);
        vp += stride;
        v++;
    } while (--count);
}

/*
void compileElements__3x_full(ogles_context_t* c,
        vertex_t* v, GLint first, GLsizei count)
{
    const GLfixed* vp = (const GLfixed*)c->arrays.vertex.element(first);
    const size_t stride = c->arrays.vertex.stride / 4;
//    const GLfixed* const& m = c->transforms.mvp.matrix.m;

    GLfixed m[16];
    memcpy(&m, c->transforms.mvp.matrix.m, sizeof(m));

    do {
        const GLfixed rx = vp[0];
        const GLfixed ry = vp[1];
        const GLfixed rz = vp[2];
        vp += stride;
        v->index = first++;
        v->clip.x = mla3a(rx, m[ 0], ry, m[ 4], rz, m[ 8], m[12]);
        v->clip.y = mla3a(rx, m[ 1], ry, m[ 5], rz, m[ 9], m[13]);
        v->clip.z = mla3a(rx, m[ 2], ry, m[ 6], rz, m[10], m[14]);
        v->clip.w = mla3a(rx, m[ 3], ry, m[ 7], rz, m[11], m[15]);

        const GLfixed w = v->clip.w;
        uint32_t clip = 0;
        if (v->clip.x < -w)   clip |= vertex_t::CLIP_L;
        if (v->clip.x >  w)   clip |= vertex_t::CLIP_R;
        if (v->clip.y < -w)   clip |= vertex_t::CLIP_B;
        if (v->clip.y >  w)   clip |= vertex_t::CLIP_T;
        if (v->clip.z < -w)   clip |= vertex_t::CLIP_N;
        if (v->clip.z >  w)   clip |= vertex_t::CLIP_F;
        v->flags = clip;
        c->arrays.cull &= clip;

        //c->arrays.perspective(c, v);
        v++;
    } while (--count);
}
*/

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark clippers
#endif

static void clipVec4(vec4_t& nv,
        GLfixed t, const vec4_t& s, const vec4_t& p)
{
    for (int i=0; i<4 ; i++)
        nv.v[i] = gglMulAddx(t, s.v[i] - p.v[i], p.v[i], 28);
}

static void clipVertex(ogles_context_t* c, vertex_t* nv,
        GLfixed t, const vertex_t* s, const vertex_t* p)
{
    clipVec4(nv->clip, t, s->clip, p->clip);
    nv->fog = gglMulAddx(t, s->fog - p->fog, p->fog, 28);
    ogles_vertex_project(c, nv);
    nv->flags |=  vertex_t::LIT | vertex_t::EYE | vertex_t::TT;
    nv->flags &= ~vertex_t::CLIP_ALL;
}

static void clipVertexC(ogles_context_t* c, vertex_t* nv,
        GLfixed t, const vertex_t* s, const vertex_t* p)
{
    clipVec4(nv->color, t, s->color, p->color);
    clipVertex(c, nv, t, s, p);
}

static void clipVertexT(ogles_context_t* c, vertex_t* nv,
        GLfixed t, const vertex_t* s, const vertex_t* p)
{
    for (int i=0 ; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
        if (c->rasterizer.state.texture[i].enable)
            clipVec4(nv->texture[i], t, s->texture[i], p->texture[i]);
    }
    clipVertex(c, nv, t, s, p);
}

static void clipVertexAll(ogles_context_t* c, vertex_t* nv,
        GLfixed t, const vertex_t* s, const vertex_t* p)
{
    clipVec4(nv->color, t, s->color, p->color);
    clipVertexT(c, nv, t, s, p);
}

static void clipEye(ogles_context_t* c, vertex_t* nv,
        GLfixed t, const vertex_t* s, const vertex_t* p)
{
    nv->clear();
    c->arrays.clipVertex(c, nv, t, p, s);
    clipVec4(nv->eye, t, s->eye, p->eye);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

void validate_arrays(ogles_context_t* c, GLenum mode)
{
    uint32_t enables = c->rasterizer.state.enables;

    // Perspective correction is not need if Ortho transform, but
    // the user can still provide the w coordinate manually, so we can't
    // automatically turn it off (in fact we could when the 4th coordinate
    // is not spcified in the vertex array).
    // W interpolation is never needed for points.
    GLboolean perspective =
        c->perspective && mode!=GL_POINTS && (enables & GGL_ENABLE_TMUS);
    c->rasterizer.procs.enableDisable(c, GGL_W_LERP, perspective);

    // set anti-aliasing
    GLboolean smooth = GL_FALSE;
    switch (mode) {
    case GL_POINTS:
        smooth = c->point.smooth;
        break;
    case GL_LINES:
    case GL_LINE_LOOP:
    case GL_LINE_STRIP:
        smooth = c->line.smooth;
        break;
    }
    if (((enables & GGL_ENABLE_AA)?1:0) != smooth)
        c->rasterizer.procs.enableDisable(c, GGL_AA, smooth);

    // set the shade model for this primitive
    c->rasterizer.procs.shadeModel(c,
            (mode == GL_POINTS) ? GL_FLAT : c->lighting.shadeModel);

    // compute all the matrices we'll need...
    uint32_t want =
            transform_state_t::MVP |
            transform_state_t::VIEWPORT;
    if (c->lighting.enable) { // needs normal transforms and eye coords
        want |= transform_state_t::MVUI;
        want |= transform_state_t::MODELVIEW;
    }
    if (enables & GGL_ENABLE_TMUS) { // needs texture transforms
        want |= transform_state_t::TEXTURE;
    }
    if (c->clipPlanes.enable || (enables & GGL_ENABLE_FOG)) {
        want |= transform_state_t::MODELVIEW; // needs eye coords
    }
    ogles_validate_transform(c, want);

    // textures...
    if (enables & GGL_ENABLE_TMUS)
        ogles_validate_texture(c);

    // vertex compilers
    c->arrays.compileElement = compileElement__generic;
    c->arrays.compileElements = compileElements__generic;

    // vertex transform
    c->arrays.mvp_transform =
        c->transforms.mvp.pointv[c->arrays.vertex.size - 2];

    c->arrays.mv_transform =
        c->transforms.modelview.transform.pointv[c->arrays.vertex.size - 2];

    /*
     * ***********************************************************************
     *  pick fetchers
     * ***********************************************************************
     */

    array_machine_t& am = c->arrays;
    am.vertex.fetch = fetchNop;
    am.normal.fetch = currentNormal;
    am.color.fetch = currentColor;

    if (am.vertex.enable) {
        am.vertex.resolve();
        if (am.vertex.bo || am.vertex.pointer) {
            am.vertex.fetch = vertex_fct[am.vertex.size-2][am.vertex.type & 0xF];
        }
    }

    if (am.normal.enable) {
        am.normal.resolve();
        if (am.normal.bo || am.normal.pointer) {
            am.normal.fetch = normal_fct[am.normal.size-3][am.normal.type & 0xF];
        }
    }

    if (am.color.enable) {
        am.color.resolve();
        if (c->lighting.enable) {
            if (am.color.bo || am.color.pointer) {
                am.color.fetch = color_fct[am.color.size-3][am.color.type & 0xF];
            }
        } else {
            if (am.color.bo || am.color.pointer) {
                am.color.fetch = color_clamp_fct[am.color.size-3][am.color.type & 0xF];
            }
        }
    }

    int activeTmuCount = 0;
    for (int i=0 ; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
        am.texture[i].fetch = currentTexCoord;
        if (c->rasterizer.state.texture[i].enable) {

            // texture fetchers...
            if (am.texture[i].enable) {
                am.texture[i].resolve();
                if (am.texture[i].bo || am.texture[i].pointer) {
                    am.texture[i].fetch = texture_fct[am.texture[i].size-2][am.texture[i].type & 0xF];
                }
            }

            // texture transform...
            const int index = c->arrays.texture[i].size - 2;
            c->arrays.tex_transform[i] =
                c->transforms.texture[i].transform.pointv[index];

            am.tmu = i;
            activeTmuCount++;
        }
    }

    // pick the vertex-clipper
    uint32_t clipper = 0;
    // we must reload 'enables' here
    enables = c->rasterizer.state.enables;
    if (enables & GGL_ENABLE_SMOOTH)
        clipper |= 1;   // we need to interpolate colors
    if (enables & GGL_ENABLE_TMUS)
        clipper |= 2;   // we need to interpolate textures
    switch (clipper) {
    case 0: c->arrays.clipVertex = clipVertex;      break;
    case 1: c->arrays.clipVertex = clipVertexC;     break;
    case 2: c->arrays.clipVertex = clipVertexT;     break;
    case 3: c->arrays.clipVertex = clipVertexAll;   break;
    }
    c->arrays.clipEye = clipEye;

    // pick the primitive rasterizer
    ogles_validate_primitives(c);
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

using namespace android;

#if 0
#pragma mark -
#pragma mark array API
#endif

void glVertexPointer(
    GLint size, GLenum type, GLsizei stride, const GLvoid *pointer)
{
    ogles_context_t* c = ogles_context_t::get();
    if (size<2 || size>4 || stride<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    switch (type) {
    case GL_BYTE:
    case GL_SHORT:
    case GL_FIXED:
    case GL_FLOAT:
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->arrays.vertex.init(size, type, stride, pointer, c->arrays.array_buffer, 0);
}

void glColorPointer(
    GLint size, GLenum type, GLsizei stride, const GLvoid *pointer)
{
    ogles_context_t* c = ogles_context_t::get();
    if (size!=4 || stride<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    switch (type) {
    case GL_UNSIGNED_BYTE:
    case GL_FIXED:
    case GL_FLOAT:
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->arrays.color.init(size, type, stride, pointer, c->arrays.array_buffer, 0);
}

void glNormalPointer(
    GLenum type, GLsizei stride, const GLvoid *pointer)
{
    ogles_context_t* c = ogles_context_t::get();
    if (stride<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    switch (type) {
    case GL_BYTE:
    case GL_SHORT:
    case GL_FIXED:
    case GL_FLOAT:
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->arrays.normal.init(3, type, stride, pointer, c->arrays.array_buffer, 0);
}

void glTexCoordPointer(
    GLint size, GLenum type, GLsizei stride, const GLvoid *pointer)
{
    ogles_context_t* c = ogles_context_t::get();
    if (size<2 || size>4 || stride<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    switch (type) {
    case GL_BYTE:
    case GL_SHORT:
    case GL_FIXED:
    case GL_FLOAT:
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    const int tmu = c->arrays.activeTexture;
    c->arrays.texture[tmu].init(size, type, stride, pointer,
            c->arrays.array_buffer, 0);
}


void glEnableClientState(GLenum array) {
    ogles_context_t* c = ogles_context_t::get();
    enableDisableClientState(c, array, true);
}

void glDisableClientState(GLenum array) {
    ogles_context_t* c = ogles_context_t::get();
    enableDisableClientState(c, array, false);
}

void glClientActiveTexture(GLenum texture)
{
    ogles_context_t* c = ogles_context_t::get();
    if (texture<GL_TEXTURE0 || texture>=GL_TEXTURE0+GGL_TEXTURE_UNIT_COUNT) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->arrays.activeTexture = texture - GL_TEXTURE0;
}

void glDrawArrays(GLenum mode, GLint first, GLsizei count)
{
    ogles_context_t* c = ogles_context_t::get();
    if (count<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    switch (mode) {
    case GL_POINTS:
    case GL_LINE_STRIP:
    case GL_LINE_LOOP:
    case GL_LINES:
    case GL_TRIANGLE_STRIP:
    case GL_TRIANGLE_FAN:
    case GL_TRIANGLES:
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }

    if (count == 0 || !c->arrays.vertex.enable)
        return;
    if ((c->cull.enable) && (c->cull.cullFace == GL_FRONT_AND_BACK))
        return; // all triangles are culled


    validate_arrays(c, mode);

    const uint32_t enables = c->rasterizer.state.enables;
    if (enables & GGL_ENABLE_TMUS)
        ogles_lock_textures(c);

    drawArraysPrims[mode](c, first, count);

    if (enables & GGL_ENABLE_TMUS)
        ogles_unlock_textures(c);

#if VC_CACHE_STATISTICS
    c->vc.total = count;
    c->vc.dump_stats(mode);
#endif
}

void glDrawElements(
    GLenum mode, GLsizei count, GLenum type, const GLvoid *indices)
{
    ogles_context_t* c = ogles_context_t::get();
    if (count<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    switch (mode) {
    case GL_POINTS:
    case GL_LINE_STRIP:
    case GL_LINE_LOOP:
    case GL_LINES:
    case GL_TRIANGLE_STRIP:
    case GL_TRIANGLE_FAN:
    case GL_TRIANGLES:
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    switch (type) {
    case GL_UNSIGNED_BYTE:
    case GL_UNSIGNED_SHORT:
        c->arrays.indicesType = type;
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if (count == 0 || !c->arrays.vertex.enable)
        return;
    if ((c->cull.enable) && (c->cull.cullFace == GL_FRONT_AND_BACK))
        return; // all triangles are culled

    // clear the vertex-cache
    c->vc.clear();
    validate_arrays(c, mode);

    // if indices are in a buffer object, the pointer is treated as an
    // offset in that buffer.
    if (c->arrays.element_array_buffer) {
        indices = c->arrays.element_array_buffer->data + uintptr_t(indices);
    }

    const uint32_t enables = c->rasterizer.state.enables;
    if (enables & GGL_ENABLE_TMUS)
        ogles_lock_textures(c);

    drawElementsPrims[mode](c, count, indices);
    
    if (enables & GGL_ENABLE_TMUS)
        ogles_unlock_textures(c);

    
#if VC_CACHE_STATISTICS
    c->vc.total = count;
    c->vc.dump_stats(mode);
#endif
}

// ----------------------------------------------------------------------------
// buffers
// ----------------------------------------------------------------------------

void glBindBuffer(GLenum target, GLuint buffer)
{
    ogles_context_t* c = ogles_context_t::get();
    if ((target!=GL_ARRAY_BUFFER) && (target!=GL_ELEMENT_ARRAY_BUFFER)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    // create a buffer object, or bind an existing one
    buffer_t const* bo = 0;
    if (buffer) {
        bo = c->bufferObjectManager->bind(buffer);
        if (!bo) {
            ogles_error(c, GL_OUT_OF_MEMORY);
            return;
        }
    }
    ((target == GL_ARRAY_BUFFER) ?
            c->arrays.array_buffer : c->arrays.element_array_buffer) = bo;
}

void glBufferData(GLenum target, GLsizeiptr size, const GLvoid* data, GLenum usage)
{
    ogles_context_t* c = ogles_context_t::get();
    if ((target!=GL_ARRAY_BUFFER) && (target!=GL_ELEMENT_ARRAY_BUFFER)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if (size<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    if ((usage!=GL_STATIC_DRAW) && (usage!=GL_DYNAMIC_DRAW)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    buffer_t const* bo = ((target == GL_ARRAY_BUFFER) ?
            c->arrays.array_buffer : c->arrays.element_array_buffer);

    if (bo == 0) {
        // can't modify buffer 0
        ogles_error(c, GL_INVALID_OPERATION);
        return;
    }

    buffer_t* edit_bo = const_cast<buffer_t*>(bo);
    if (c->bufferObjectManager->allocateStore(edit_bo, size, usage) != 0) {
        ogles_error(c, GL_OUT_OF_MEMORY);
        return;
    }
    if (data) {
        memcpy(bo->data, data, size);
    }
}

void glBufferSubData(GLenum target, GLintptr offset, GLsizeiptr size, const GLvoid* data)
{
    ogles_context_t* c = ogles_context_t::get();
    if ((target!=GL_ARRAY_BUFFER) && (target!=GL_ELEMENT_ARRAY_BUFFER)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if (offset<0 || size<0 || data==0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    buffer_t const* bo = ((target == GL_ARRAY_BUFFER) ?
            c->arrays.array_buffer : c->arrays.element_array_buffer);

    if (bo == 0) {
        // can't modify buffer 0
        ogles_error(c, GL_INVALID_OPERATION);
        return;
    }
    if (offset+size > bo->size) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    memcpy(bo->data + offset, data, size);
}

void glDeleteBuffers(GLsizei n, const GLuint* buffers)
{
    ogles_context_t* c = ogles_context_t::get();
    if (n<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    for (int i=0 ; i<n ; i++) {
        GLuint name = buffers[i];
        if (name) {
            // unbind bound deleted buffers...
            if (c->arrays.element_array_buffer) {
                if (c->arrays.element_array_buffer->name == name) {
                    c->arrays.element_array_buffer = 0;
                }
            }
            if (c->arrays.array_buffer) {
                if (c->arrays.array_buffer->name == name) {
                    c->arrays.array_buffer = 0;
                }
            }
            if (c->arrays.vertex.bo) {
                if (c->arrays.vertex.bo->name == name) {
                    c->arrays.vertex.bo = 0;
                }
            }
            if (c->arrays.normal.bo) {
                if (c->arrays.normal.bo->name == name) {
                    c->arrays.normal.bo = 0;
                }
            }
            if (c->arrays.color.bo) {
                if (c->arrays.color.bo->name == name) {
                    c->arrays.color.bo = 0;
                }
            }
            for (int t=0 ; t<GGL_TEXTURE_UNIT_COUNT ; t++) {
                if (c->arrays.texture[t].bo) {
                    if (c->arrays.texture[t].bo->name == name) {
                        c->arrays.texture[t].bo = 0;
                    }
                }
            }
        }
    }
    c->bufferObjectManager->deleteBuffers(n, buffers);
    c->bufferObjectManager->recycleTokens(n, buffers);
}

void glGenBuffers(GLsizei n, GLuint* buffers)
{
    ogles_context_t* c = ogles_context_t::get();
    if (n<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    c->bufferObjectManager->getToken(n, buffers);
}

/* libs/opengles/primitives.cpp
**
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

#include <stdio.h>
#include <stdlib.h>
#include <math.h>

#include "context.h"
#include "primitives.h"
#include "light.h"
#include "matrix.h"
#include "vertex.h"
#include "fp.h"
#include "TextureObjectManager.h"

extern "C" void iterators0032(const void* that,
        int32_t* it, int32_t c0, int32_t c1, int32_t c2);

namespace android {

// ----------------------------------------------------------------------------

static void primitive_point(ogles_context_t* c, vertex_t* v);
static void primitive_line(ogles_context_t* c, vertex_t* v0, vertex_t* v1);
static void primitive_clip_triangle(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2);

static void primitive_nop_point(ogles_context_t* c, vertex_t* v);
static void primitive_nop_line(ogles_context_t* c, vertex_t* v0, vertex_t* v1);
static void primitive_nop_triangle(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2);

static inline bool cull_triangle(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2);

static void lerp_triangle(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2);

static void lerp_texcoords(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2);

static void lerp_texcoords_w(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2);

static void triangle(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2);

static void clip_triangle(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2);

static unsigned int clip_line(ogles_context_t* c,
        vertex_t* s, vertex_t* p);

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

static void lightTriangleDarkSmooth(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    if (!(v0->flags & vertex_t::LIT)) {
        v0->flags |= vertex_t::LIT;
        const GLvoid* cp = c->arrays.color.element(
                v0->index & vertex_cache_t::INDEX_MASK);
        c->arrays.color.fetch(c, v0->color.v, cp);
    }
    if (!(v1->flags & vertex_t::LIT)) {
        v1->flags |= vertex_t::LIT;
        const GLvoid* cp = c->arrays.color.element(
                v1->index & vertex_cache_t::INDEX_MASK);
        c->arrays.color.fetch(c, v1->color.v, cp);
    }
    if(!(v2->flags & vertex_t::LIT)) {
        v2->flags |= vertex_t::LIT;
        const GLvoid* cp = c->arrays.color.element(
                v2->index & vertex_cache_t::INDEX_MASK);
        c->arrays.color.fetch(c, v2->color.v, cp);
    }
}

static void lightTriangleDarkFlat(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    if (!(v2->flags & vertex_t::LIT)) {
        v2->flags |= vertex_t::LIT;
        const GLvoid* cp = c->arrays.color.element(
                v2->index & vertex_cache_t::INDEX_MASK);
        c->arrays.color.fetch(c, v2->color.v, cp);
    }
    // configure the rasterizer here, before we clip
    c->rasterizer.procs.color4xv(c, v2->color.v);
}

static void lightTriangleSmooth(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    if (!(v0->flags & vertex_t::LIT))
        c->lighting.lightVertex(c, v0);
    if (!(v1->flags & vertex_t::LIT))
        c->lighting.lightVertex(c, v1);
    if(!(v2->flags & vertex_t::LIT))
        c->lighting.lightVertex(c, v2);
}

static void lightTriangleFlat(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    if (!(v2->flags & vertex_t::LIT))
        c->lighting.lightVertex(c, v2);
    // configure the rasterizer here, before we clip
    c->rasterizer.procs.color4xv(c, v2->color.v);
}

// The fog versions...

static inline
void lightVertexDarkSmoothFog(ogles_context_t* c, vertex_t* v)
{
    if (!(v->flags & vertex_t::LIT)) {
        v->flags |= vertex_t::LIT;
        v->fog = c->fog.fog(c, v->eye.z);
        const GLvoid* cp = c->arrays.color.element(
                v->index & vertex_cache_t::INDEX_MASK);
        c->arrays.color.fetch(c, v->color.v, cp);
    }
}
static inline
void lightVertexDarkFlatFog(ogles_context_t* c, vertex_t* v)
{
    if (!(v->flags & vertex_t::LIT)) {
        v->flags |= vertex_t::LIT;
        v->fog = c->fog.fog(c, v->eye.z);
    }
}
static inline
void lightVertexSmoothFog(ogles_context_t* c, vertex_t* v)
{
    if (!(v->flags & vertex_t::LIT)) {
        v->fog = c->fog.fog(c, v->eye.z);
        c->lighting.lightVertex(c, v);
    }
}

static void lightTriangleDarkSmoothFog(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    lightVertexDarkSmoothFog(c, v0);
    lightVertexDarkSmoothFog(c, v1);
    lightVertexDarkSmoothFog(c, v2);
}

static void lightTriangleDarkFlatFog(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    lightVertexDarkFlatFog(c, v0);
    lightVertexDarkFlatFog(c, v1);
    lightVertexDarkSmoothFog(c, v2);
    // configure the rasterizer here, before we clip
    c->rasterizer.procs.color4xv(c, v2->color.v);
}

static void lightTriangleSmoothFog(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    lightVertexSmoothFog(c, v0);
    lightVertexSmoothFog(c, v1);
    lightVertexSmoothFog(c, v2);
}

static void lightTriangleFlatFog(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    lightVertexDarkFlatFog(c, v0);
    lightVertexDarkFlatFog(c, v1);
    lightVertexSmoothFog(c, v2);
    // configure the rasterizer here, before we clip
    c->rasterizer.procs.color4xv(c, v2->color.v);
}



typedef void (*light_primitive_t)(ogles_context_t*,
        vertex_t*, vertex_t*, vertex_t*);

// fog 0x4, light 0x2, smooth 0x1
static const light_primitive_t lightPrimitive[8] = {
    lightTriangleDarkFlat,          // no fog | dark  | flat
    lightTriangleDarkSmooth,        // no fog | dark  | smooth
    lightTriangleFlat,              // no fog | light | flat
    lightTriangleSmooth,            // no fog | light | smooth
    lightTriangleDarkFlatFog,       // fog    | dark  | flat
    lightTriangleDarkSmoothFog,     // fog    | dark  | smooth
    lightTriangleFlatFog,           // fog    | light | flat
    lightTriangleSmoothFog          // fog    | light | smooth
};

void ogles_validate_primitives(ogles_context_t* c)
{
    const uint32_t enables = c->rasterizer.state.enables;

    // set up the lighting/shading/smoothing/fogging function
    int index = enables & GGL_ENABLE_SMOOTH ? 0x1 : 0;
    index |= c->lighting.enable ? 0x2 : 0;
    index |= enables & GGL_ENABLE_FOG ? 0x4 : 0;
    c->lighting.lightTriangle = lightPrimitive[index];
    
    // set up the primitive renderers
    if (ggl_likely(c->arrays.vertex.enable)) {
        c->prims.renderPoint    = primitive_point;
        c->prims.renderLine     = primitive_line;
        c->prims.renderTriangle = primitive_clip_triangle;
    } else {
        c->prims.renderPoint    = primitive_nop_point;
        c->prims.renderLine     = primitive_nop_line;
        c->prims.renderTriangle = primitive_nop_triangle;
    }
}

// ----------------------------------------------------------------------------

void compute_iterators_t::initTriangle(
        vertex_t const* v0, vertex_t const* v1, vertex_t const* v2)
{
    m_dx01 = v1->window.x - v0->window.x;
    m_dy10 = v0->window.y - v1->window.y;
    m_dx20 = v0->window.x - v2->window.x;
    m_dy02 = v2->window.y - v0->window.y;
    m_area = m_dx01*m_dy02 + (-m_dy10)*m_dx20;
}

void compute_iterators_t::initLine(
        vertex_t const* v0, vertex_t const* v1)
{
    m_dx01 = m_dy02 = v1->window.x - v0->window.x;
    m_dy10 = m_dx20 = v0->window.y - v1->window.y;
    m_area = m_dx01*m_dy02 + (-m_dy10)*m_dx20;
}

void compute_iterators_t::initLerp(vertex_t const* v0, uint32_t enables)
{
    m_x0 = v0->window.x;
    m_y0 = v0->window.y;
    const GGLcoord area = (m_area + TRI_HALF) >> TRI_FRACTION_BITS;
    const GGLcoord minArea = 2; // cannot be inverted
    // triangles with an area smaller than 1.0 are not smooth-shaded

    int q=0, s=0, d=0;
    if (abs(area) >= minArea) {
        // Here we do some voodoo magic, to compute a suitable scale
        // factor for deltas/area:

        // First compute the 1/area with full 32-bits precision,
        // gglRecipQNormalized returns a number [-0.5, 0.5[ and an exponent.
        d = gglRecipQNormalized(area, &q);

        // Then compute the minimum left-shift to not overflow the muls
        // below. 
        s = 32 - gglClz(abs(m_dy02)|abs(m_dy10)|abs(m_dx01)|abs(m_dx20));

        // We'll keep 16-bits of precision for deltas/area. So we need
        // to shift everything left an extra 15 bits.
        s += 15;
        
        // make sure all final shifts are not > 32, because gglMulx
        // can't handle it.
        if (s < q) s = q;
        if (s > 32) {
            d >>= 32-s;
            s = 32;
        }
    }

    m_dx01 = gglMulx(m_dx01, d, s);
    m_dy10 = gglMulx(m_dy10, d, s);
    m_dx20 = gglMulx(m_dx20, d, s);
    m_dy02 = gglMulx(m_dy02, d, s);
    m_area_scale = 32 + q - s;
    m_scale = 0;

    if (enables & GGL_ENABLE_TMUS) {
        const int A = gglClz(abs(m_dy02)|abs(m_dy10)|abs(m_dx01)|abs(m_dx20));
        const int B = gglClz(abs(m_x0)|abs(m_y0));
        m_scale = max(0, 32 - (A + 16)) +
                  max(0, 32 - (B + TRI_FRACTION_BITS)) + 1;
    }
}

int compute_iterators_t::iteratorsScale(GGLfixed* it,
        int32_t c0, int32_t c1, int32_t c2) const
{
    int32_t dc01 = c1 - c0;
    int32_t dc02 = c2 - c0;
    const int A = gglClz(abs(c0));
    const int B = gglClz(abs(dc01)|abs(dc02));
    const int scale = min(A, B - m_scale) - 2;
    if (scale >= 0) {
        c0   <<= scale;
        dc01 <<= scale;
        dc02 <<= scale;
    } else {
        c0   >>= -scale;
        dc01 >>= -scale;
        dc02 >>= -scale;
    }
    const int s = m_area_scale;
    int32_t dcdx = gglMulAddx(dc01, m_dy02, gglMulx(dc02, m_dy10, s), s);
    int32_t dcdy = gglMulAddx(dc02, m_dx01, gglMulx(dc01, m_dx20, s), s);
    int32_t c = c0 - (gglMulAddx(dcdx, m_x0, 
            gglMulx(dcdy, m_y0, TRI_FRACTION_BITS), TRI_FRACTION_BITS));
    it[0] = c;
    it[1] = dcdx;
    it[2] = dcdy;
    return scale;
}

void compute_iterators_t::iterators1616(GGLfixed* it,
        GGLfixed c0, GGLfixed c1, GGLfixed c2) const
{
    const GGLfixed dc01 = c1 - c0;
    const GGLfixed dc02 = c2 - c0;
    // 16.16 x 16.16 == 32.32 --> 16.16
    const int s = m_area_scale;
    int32_t dcdx = gglMulAddx(dc01, m_dy02, gglMulx(dc02, m_dy10, s), s);
    int32_t dcdy = gglMulAddx(dc02, m_dx01, gglMulx(dc01, m_dx20, s), s);
    int32_t c = c0 - (gglMulAddx(dcdx, m_x0,
            gglMulx(dcdy, m_y0, TRI_FRACTION_BITS), TRI_FRACTION_BITS));
    it[0] = c;
    it[1] = dcdx;
    it[2] = dcdy;
}

void compute_iterators_t::iterators0032(int64_t* it,
        int32_t c0, int32_t c1, int32_t c2) const
{
    const int s = m_area_scale - 16;
    int32_t dc01 = (c1 - c0)>>s;
    int32_t dc02 = (c2 - c0)>>s;
    // 16.16 x 16.16 == 32.32
    int64_t dcdx = gglMulii(dc01, m_dy02) + gglMulii(dc02, m_dy10);
    int64_t dcdy = gglMulii(dc02, m_dx01) + gglMulii(dc01, m_dx20);
    it[ 0] = (c0<<16) - ((dcdx*m_x0 + dcdy*m_y0)>>4);
    it[ 1] = dcdx;
    it[ 2] = dcdy;
}

#if defined(__arm__) && !defined(__thumb__)
inline void compute_iterators_t::iterators0032(int32_t* it,
        int32_t c0, int32_t c1, int32_t c2) const
{
    ::iterators0032(this, it, c0, c1, c2);
}
#else
void compute_iterators_t::iterators0032(int32_t* it,
        int32_t c0, int32_t c1, int32_t c2) const
{
    int64_t it64[3];
    iterators0032(it64, c0, c1, c2);
    it[0] = it64[0];
    it[1] = it64[1];
    it[2] = it64[2];
}
#endif

// ----------------------------------------------------------------------------

static inline int32_t clampZ(GLfixed z) CONST;
int32_t clampZ(GLfixed z) {
    z = (z & ~(z>>31));
    if (z >= 0x10000)
        z = 0xFFFF;
    return z;
}

static __attribute__((noinline))
void fetch_texcoord_impl(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    vertex_t* const vtx[3] = { v0, v1, v2 };
    array_t const * const texcoordArray = c->arrays.texture;
    
    for (int i=0 ; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
        if (!(c->rasterizer.state.texture[i].enable))
            continue;
        
        for (int j=0 ; j<3 ; j++) {
            vertex_t* const v = vtx[j];
            if (v->flags & vertex_t::TT)
                continue;

            // NOTE: here we could compute automatic texgen
            // such as sphere/cube maps, instead of fetching them
            // from the textcoord array.

            vec4_t& coords = v->texture[i];
            const GLubyte* tp = texcoordArray[i].element(
                    v->index & vertex_cache_t::INDEX_MASK);
            texcoordArray[i].fetch(c, coords.v, tp);

            // transform texture coordinates...
            coords.Q = 0x10000;
            const transform_t& tr = c->transforms.texture[i].transform; 
            if (ggl_unlikely(tr.ops)) {
                c->arrays.tex_transform[i](&tr, &coords, &coords);
            }

            // divide by Q
            const GGLfixed q = coords.Q;
            if (ggl_unlikely(q != 0x10000)) {
                const int32_t qinv = gglRecip28(q);
                coords.S = gglMulx(coords.S, qinv, 28);
                coords.T = gglMulx(coords.T, qinv, 28);
            }
        }
    }
    v0->flags |= vertex_t::TT;
    v1->flags |= vertex_t::TT;
    v2->flags |= vertex_t::TT;
}

inline void fetch_texcoord(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    const uint32_t enables = c->rasterizer.state.enables;
    if (!(enables & GGL_ENABLE_TMUS))
        return;

    // Fetch & transform texture coordinates...
    if (ggl_likely(v0->flags & v1->flags & v2->flags & vertex_t::TT)) {
        // already done for all three vertices, bail...
        return;
    }
    fetch_texcoord_impl(c, v0, v1, v2);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Point
#endif

void primitive_nop_point(ogles_context_t*, vertex_t*) {
}

void primitive_point(ogles_context_t* c, vertex_t* v)
{
    // lighting & clamping...
    const uint32_t enables = c->rasterizer.state.enables;

    if (ggl_unlikely(!(v->flags & vertex_t::LIT))) {
        if (c->lighting.enable) {
            c->lighting.lightVertex(c, v);
        } else {
            v->flags |= vertex_t::LIT;
            const GLvoid* cp = c->arrays.color.element(
                    v->index & vertex_cache_t::INDEX_MASK);
            c->arrays.color.fetch(c, v->color.v, cp);
        }
        if (enables & GGL_ENABLE_FOG) {
            v->fog = c->fog.fog(c, v->eye.z);
        }
    }

    // XXX: we don't need to do that each-time
    // if color array and lighting not enabled 
    c->rasterizer.procs.color4xv(c, v->color.v);

    // XXX: look into ES point-sprite extension
    if (enables & GGL_ENABLE_TMUS) {
        fetch_texcoord(c, v,v,v);
        for (int i=0 ; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
            if (!c->rasterizer.state.texture[i].enable) 
                continue;
            int32_t itt[8];
            itt[1] = itt[2] = itt[4] = itt[5] = 0;
            itt[6] = itt[7] = 16; // XXX: check that
            if (c->rasterizer.state.texture[i].s_wrap == GGL_CLAMP) {
                int width = c->textures.tmu[i].texture->surface.width;
                itt[0] = v->texture[i].S * width;
                itt[6] = 0;
            }
            if (c->rasterizer.state.texture[i].t_wrap == GGL_CLAMP) {
                int height = c->textures.tmu[i].texture->surface.height;
                itt[3] = v->texture[i].T * height;
                itt[7] = 0;
            }
            c->rasterizer.procs.texCoordGradScale8xv(c, i, itt);
        }
    }
    
    if (enables & GGL_ENABLE_DEPTH_TEST) {
        int32_t itz[3];
        itz[0] = clampZ(v->window.z) * 0x00010001;
        itz[1] = itz[2] = 0;
        c->rasterizer.procs.zGrad3xv(c, itz);
    }

    if (enables & GGL_ENABLE_FOG) {
        GLfixed itf[3];
        itf[0] = v->fog;
        itf[1] = itf[2] = 0;
        c->rasterizer.procs.fogGrad3xv(c, itf);
    }

    // Render our point...
    c->rasterizer.procs.pointx(c, v->window.v, c->point.size);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Line
#endif

void primitive_nop_line(ogles_context_t*, vertex_t*, vertex_t*) {
}

void primitive_line(ogles_context_t* c, vertex_t* v0, vertex_t* v1)
{
    // get texture coordinates
    fetch_texcoord(c, v0, v1, v1);

    // light/shade the vertices first (they're copied below)
    c->lighting.lightTriangle(c, v0, v1, v1);

    // clip the line if needed
    if (ggl_unlikely((v0->flags | v1->flags) & vertex_t::CLIP_ALL)) {
        unsigned int count = clip_line(c, v0, v1);
        if (ggl_unlikely(count == 0))
            return;
    }

    // compute iterators...
    const uint32_t enables = c->rasterizer.state.enables;
    const uint32_t mask =   GGL_ENABLE_TMUS |
                            GGL_ENABLE_SMOOTH |
                            GGL_ENABLE_W | 
                            GGL_ENABLE_FOG |
                            GGL_ENABLE_DEPTH_TEST;

    if (ggl_unlikely(enables & mask)) {
        c->lerp.initLine(v0, v1);
        lerp_triangle(c, v0, v1, v0);
    }

    // render our line
    c->rasterizer.procs.linex(c, v0->window.v, v1->window.v, c->line.width);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Triangle
#endif

void primitive_nop_triangle(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2) {
}

void primitive_clip_triangle(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    uint32_t cc = (v0->flags | v1->flags | v2->flags) & vertex_t::CLIP_ALL;
    if (ggl_likely(!cc)) {
        // code below must be as optimized as possible, this is the
        // common code path.

        // This triangle is not clipped, test if it's culled
        // unclipped triangle...
        c->lerp.initTriangle(v0, v1, v2);
        if (cull_triangle(c, v0, v1, v2))
            return; // culled!

        // Fetch all texture coordinates if needed
        fetch_texcoord(c, v0, v1, v2);

        // light (or shade) our triangle!
        c->lighting.lightTriangle(c, v0, v1, v2);

        triangle(c, v0, v1, v2);
        return;
    }

    // The assumption here is that we're not going to clip very often,
    // and even more rarely will we clip a triangle that ends up
    // being culled out. So it's okay to light the vertices here, even though
    // in a few cases we won't render the triangle (if culled).

    // Fetch texture coordinates...
    fetch_texcoord(c, v0, v1, v2);

    // light (or shade) our triangle!
    c->lighting.lightTriangle(c, v0, v1, v2);

    clip_triangle(c, v0, v1, v2);
}

// -----------------------------------------------------------------------

void triangle(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    // compute iterators...
    const uint32_t enables = c->rasterizer.state.enables;
    const uint32_t mask =   GGL_ENABLE_TMUS |
                            GGL_ENABLE_SMOOTH |
                            GGL_ENABLE_W | 
                            GGL_ENABLE_FOG |
                            GGL_ENABLE_DEPTH_TEST;

    if (ggl_likely(enables & mask))
        lerp_triangle(c, v0, v1, v2);

    c->rasterizer.procs.trianglex(c, v0->window.v, v1->window.v, v2->window.v);
}

void lerp_triangle(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    const uint32_t enables = c->rasterizer.state.enables;
    c->lerp.initLerp(v0, enables);

    // set up texture iterators
    if (enables & GGL_ENABLE_TMUS) {
        if (enables & GGL_ENABLE_W) {
            lerp_texcoords_w(c, v0, v1, v2);
        } else {
            lerp_texcoords(c, v0, v1, v2);
        }
    }

    // set up the color iterators
    const compute_iterators_t& lerp = c->lerp;
    if (enables & GGL_ENABLE_SMOOTH) {
        GLfixed itc[12];
        for (int i=0 ; i<4 ; i++) {
            const GGLcolor c0 = v0->color.v[i] * 255;
            const GGLcolor c1 = v1->color.v[i] * 255;
            const GGLcolor c2 = v2->color.v[i] * 255;
            lerp.iterators1616(&itc[i*3], c0, c1, c2);
        }
        c->rasterizer.procs.colorGrad12xv(c, itc);
    }

    if (enables & GGL_ENABLE_DEPTH_TEST) {
        int32_t itz[3];
        const int32_t v0z = clampZ(v0->window.z);
        const int32_t v1z = clampZ(v1->window.z);
        const int32_t v2z = clampZ(v2->window.z);
        if (ggl_unlikely(c->polygonOffset.enable)) {
            const int32_t units = (c->polygonOffset.units << 16);
            const GLfixed factor = c->polygonOffset.factor;
            if (factor) {
                int64_t itz64[3];
                lerp.iterators0032(itz64, v0z, v1z, v2z);
                int64_t maxDepthSlope = max(itz64[1], itz64[2]);
                itz[0] = uint32_t(itz64[0]) 
                        + uint32_t((maxDepthSlope*factor)>>16) + units;
                itz[1] = uint32_t(itz64[1]);
                itz[2] = uint32_t(itz64[2]);
            } else {
                lerp.iterators0032(itz, v0z, v1z, v2z);
                itz[0] += units; 
            }
        } else {
            lerp.iterators0032(itz, v0z, v1z, v2z);
        }
        c->rasterizer.procs.zGrad3xv(c, itz);
    }    

    if (ggl_unlikely(enables & GGL_ENABLE_FOG)) {
        GLfixed itf[3];
        lerp.iterators1616(itf, v0->fog, v1->fog, v2->fog);
        c->rasterizer.procs.fogGrad3xv(c, itf);
    }
}


static inline
int compute_lod(ogles_context_t* c, int i,
        int32_t s0, int32_t t0, int32_t s1, int32_t t1, int32_t s2, int32_t t2)
{
    // Compute mipmap level / primitive
    // rho = sqrt( texelArea / area )
    // lod = log2( rho )
    // lod = log2( texelArea / area ) / 2
    // lod = (log2( texelArea ) - log2( area )) / 2
    const compute_iterators_t& lerp = c->lerp;
    const GGLcoord area = abs(lerp.area());
    const int w = c->textures.tmu[i].texture->surface.width;
    const int h = c->textures.tmu[i].texture->surface.height;
    const int shift = 16 + (16 - TRI_FRACTION_BITS);
    int32_t texelArea = abs( gglMulx(s1-s0, t2-t0, shift) -
            gglMulx(s2-s0, t1-t0, shift) )*w*h;
    int log2TArea = (32-TRI_FRACTION_BITS  -1) - gglClz(texelArea);
    int log2Area  = (32-TRI_FRACTION_BITS*2-1) - gglClz(area);
    int lod = (log2TArea - log2Area + 1) >> 1;
    return lod;
}

void lerp_texcoords(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    const compute_iterators_t& lerp = c->lerp;
    int32_t itt[8] __attribute__((aligned(16)));
    for (int i=0 ; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
        const texture_t& tmu = c->rasterizer.state.texture[i];
        if (!tmu.enable) 
            continue;

        // compute the jacobians using block floating-point
        int32_t s0 = v0->texture[i].S;
        int32_t t0 = v0->texture[i].T;
        int32_t s1 = v1->texture[i].S;
        int32_t t1 = v1->texture[i].T;
        int32_t s2 = v2->texture[i].S;
        int32_t t2 = v2->texture[i].T;

        const GLenum min_filter = c->textures.tmu[i].texture->min_filter;
        if (ggl_unlikely(min_filter >= GL_NEAREST_MIPMAP_NEAREST)) {
            int lod = compute_lod(c, i, s0, t0, s1, t1, s2, t2);
            c->rasterizer.procs.bindTextureLod(c, i,
                    &c->textures.tmu[i].texture->mip(lod));
        }

        // premultiply (s,t) when clampling
        if (tmu.s_wrap == GGL_CLAMP) {
            const int width = tmu.surface.width;
            s0 *= width;
            s1 *= width;
            s2 *= width;
        }
        if (tmu.t_wrap == GGL_CLAMP) {
            const int height = tmu.surface.height;
            t0 *= height;
            t1 *= height;
            t2 *= height;
        }
        itt[6] = -lerp.iteratorsScale(itt+0, s0, s1, s2);
        itt[7] = -lerp.iteratorsScale(itt+3, t0, t1, t2);
        c->rasterizer.procs.texCoordGradScale8xv(c, i, itt);
    }
}

void lerp_texcoords_w(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    const compute_iterators_t& lerp = c->lerp;
    int32_t itt[8] __attribute__((aligned(16)));
    int32_t itw[3];

    // compute W's scale to 2.30
    int32_t w0 = v0->window.w;
    int32_t w1 = v1->window.w;
    int32_t w2 = v2->window.w;
    int wscale = 32 - gglClz(w0|w1|w2);

    // compute the jacobian using block floating-point    
    int sc = lerp.iteratorsScale(itw, w0, w1, w2);
    sc +=  wscale - 16;
    c->rasterizer.procs.wGrad3xv(c, itw);

    for (int i=0 ; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
        const texture_t& tmu = c->rasterizer.state.texture[i];
        if (!tmu.enable) 
            continue;

        // compute the jacobians using block floating-point
        int32_t s0 = v0->texture[i].S;
        int32_t t0 = v0->texture[i].T;
        int32_t s1 = v1->texture[i].S;
        int32_t t1 = v1->texture[i].T;
        int32_t s2 = v2->texture[i].S;
        int32_t t2 = v2->texture[i].T;

        const GLenum min_filter = c->textures.tmu[i].texture->min_filter;
        if (ggl_unlikely(min_filter >= GL_NEAREST_MIPMAP_NEAREST)) {
            int lod = compute_lod(c, i, s0, t0, s1, t1, s2, t2);
            c->rasterizer.procs.bindTextureLod(c, i,
                    &c->textures.tmu[i].texture->mip(lod));
        }

        // premultiply (s,t) when clampling
        if (tmu.s_wrap == GGL_CLAMP) {
            const int width = tmu.surface.width;
            s0 *= width;
            s1 *= width;
            s2 *= width;
        }
        if (tmu.t_wrap == GGL_CLAMP) {
            const int height = tmu.surface.height;
            t0 *= height;
            t1 *= height;
            t2 *= height;
        }

        s0 = gglMulx(s0, w0, wscale);
        t0 = gglMulx(t0, w0, wscale);
        s1 = gglMulx(s1, w1, wscale);
        t1 = gglMulx(t1, w1, wscale);
        s2 = gglMulx(s2, w2, wscale);
        t2 = gglMulx(t2, w2, wscale);

        itt[6] = sc - lerp.iteratorsScale(itt+0, s0, s1, s2);
        itt[7] = sc - lerp.iteratorsScale(itt+3, t0, t1, t2);
        c->rasterizer.procs.texCoordGradScale8xv(c, i, itt);
    }
}


static inline
bool cull_triangle(ogles_context_t* c, vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    if (ggl_likely(c->cull.enable)) {
        const GLenum winding = (c->lerp.area() > 0) ? GL_CW : GL_CCW;
        const GLenum face = (winding == c->cull.frontFace) ? GL_FRONT : GL_BACK;
        if (face == c->cull.cullFace)
            return true; // culled!
    }
    return false;
}

static inline
GLfixed frustumPlaneDist(int plane, const vec4_t& s)
{
    const GLfixed d = s.v[ plane >> 1 ];
    return  ((plane & 1) ? (s.w - d) : (s.w + d)); 
}

static inline
int32_t clipDivide(GLfixed a, GLfixed b) {
    // returns a 4.28 fixed-point
    return gglMulDivi(1LU<<28, a, b);
} 

void clip_triangle(ogles_context_t* c,
        vertex_t* v0, vertex_t* v1, vertex_t* v2)
{
    uint32_t all_cc = (v0->flags | v1->flags | v2->flags) & vertex_t::CLIP_ALL;

    vertex_t *p0, *p1, *p2;
    const int MAX_CLIPPING_PLANES = 6 + OGLES_MAX_CLIP_PLANES;
    const int MAX_VERTICES = 3;

    // Temporary buffer to hold the new vertices. Each plane can add up to 
    // two new vertices (because the polygon is convex).
    // We need one extra element, to handle an overflow case when
    // the polygon degenerates into something non convex.
    vertex_t buffer[MAX_CLIPPING_PLANES * 2 + 1];   // ~3KB
    vertex_t* buf = buffer;

    // original list of vertices (polygon to clip, in fact this
    // function works with an arbitrary polygon).
    vertex_t* in[3] = { v0, v1, v2 };
    
    // output lists (we need 2, which we use back and forth)
    // (maximum outpout list's size is MAX_CLIPPING_PLANES + MAX_VERTICES)
    // 2 more elements for overflow when non convex polygons.
    vertex_t* out[2][MAX_CLIPPING_PLANES + MAX_VERTICES + 2];
    unsigned int outi = 0;
    
    // current input list
    vertex_t** ivl = in;

    // 3 input vertices, 0 in the output list, first plane
    unsigned int ic = 3;

    // User clip-planes first, the clipping is always done in eye-coordinate
    // this is basically the same algorithm than for the view-volume
    // clipping, except for the computation of the distance (vertex, plane)
    // and the fact that we need to compute the eye-coordinates of each
    // new vertex we create.
    
    if (ggl_unlikely(all_cc & vertex_t::USER_CLIP_ALL))
    {
        unsigned int plane = 0;
        uint32_t cc = (all_cc & vertex_t::USER_CLIP_ALL) >> 8;
        do {
            if (cc & 1) {        
                // pointers to our output list (head and current)
                vertex_t** const ovl = &out[outi][0];
                vertex_t** output = ovl;
                unsigned int oc = 0;
                unsigned int sentinel = 0;
                // previous vertex, compute distance to the plane
                vertex_t* s = ivl[ic-1];
                const vec4_t& equation = c->clipPlanes.plane[plane].equation;
                GLfixed sd = dot4(equation.v, s->eye.v);
                // clip each vertex against this plane...
                for (unsigned int i=0 ; i<ic ; i++) {            
                    vertex_t* p = ivl[i];
                    const GLfixed pd = dot4(equation.v, p->eye.v);
                    if (sd >= 0) {
                        if (pd >= 0) {
                            // both inside
                            *output++ = p;
                            oc++;
                        } else {
                            // s inside, p outside (exiting)
                            const GLfixed t = clipDivide(sd, sd-pd);
                            c->arrays.clipEye(c, buf, t, p, s);
                            *output++ = buf++;
                            oc++;
                            if (++sentinel >= 3)
                                return; // non-convex polygon!
                        }
                    } else {
                        if (pd >= 0) {
                            // s outside (entering)
                            if (pd) {
                                const GLfixed t = clipDivide(pd, pd-sd);
                                c->arrays.clipEye(c, buf, t, s, p);
                                *output++ = buf++;
                                oc++;
                                if (++sentinel >= 3)
                                    return; // non-convex polygon!
                            }
                            *output++ = p;
                            oc++;
                        } else {
                           // both outside
                        }
                    }
                    s = p;
                    sd = pd;
                }
                // output list become the new input list
                if (oc<3)
                    return; // less than 3 vertices left? we're done!
                ivl = ovl;
                ic = oc;
                outi = 1-outi;
            }
            cc >>= 1;
            plane++;
        } while (cc);
    }

    // frustum clip-planes
    if (all_cc & vertex_t::FRUSTUM_CLIP_ALL)
    {
        unsigned int plane = 0;
        uint32_t cc = all_cc & vertex_t::FRUSTUM_CLIP_ALL;
        do {
            if (cc & 1) {        
                // pointers to our output list (head and current)
                vertex_t** const ovl = &out[outi][0];
                vertex_t** output = ovl;
                unsigned int oc = 0;
                unsigned int sentinel = 0;
                // previous vertex, compute distance to the plane
                vertex_t* s = ivl[ic-1];
                GLfixed sd = frustumPlaneDist(plane, s->clip);
                // clip each vertex against this plane...
                for (unsigned int i=0 ; i<ic ; i++) {            
                    vertex_t* p = ivl[i];
                    const GLfixed pd = frustumPlaneDist(plane, p->clip);
                    if (sd >= 0) {
                        if (pd >= 0) {
                            // both inside
                            *output++ = p;
                            oc++;
                        } else {
                            // s inside, p outside (exiting)
                            const GLfixed t = clipDivide(sd, sd-pd);
                            c->arrays.clipVertex(c, buf, t, p, s);
                            *output++ = buf++;
                            oc++;
                            if (++sentinel >= 3)
                                return; // non-convex polygon!
                        }
                    } else {
                        if (pd >= 0) {
                            // s outside (entering)
                            if (pd) {
                                const GLfixed t = clipDivide(pd, pd-sd);
                                c->arrays.clipVertex(c, buf, t, s, p);
                                *output++ = buf++;
                                oc++;
                                if (++sentinel >= 3)
                                    return; // non-convex polygon!
                            }
                            *output++ = p;
                            oc++;
                        } else {
                           // both outside
                        }
                    }
                    s = p;
                    sd = pd;
                }
                // output list become the new input list
                if (oc<3)
                    return; // less than 3 vertices left? we're done!
                ivl = ovl;
                ic = oc;
                outi = 1-outi;
            }
            cc >>= 1;
            plane++;
        } while (cc);
    }
    
    // finally we can render our triangles...
    p0 = ivl[0];
    p1 = ivl[1];
    for (unsigned int i=2 ; i<ic ; i++) {
        p2 = ivl[i];
        c->lerp.initTriangle(p0, p1, p2);
        if (cull_triangle(c, p0, p1, p2)) {
            p1 = p2;
            continue; // culled!
        }
        triangle(c, p0, p1, p2);
        p1 = p2;
    }
}

unsigned int clip_line(ogles_context_t* c, vertex_t* s, vertex_t* p)
{
    const uint32_t all_cc = (s->flags | p->flags) & vertex_t::CLIP_ALL;

    if (ggl_unlikely(all_cc & vertex_t::USER_CLIP_ALL))
    {
        unsigned int plane = 0;
        uint32_t cc = (all_cc & vertex_t::USER_CLIP_ALL) >> 8;
        do {
            if (cc & 1) {
                const vec4_t& equation = c->clipPlanes.plane[plane].equation;
                const GLfixed sd = dot4(equation.v, s->eye.v);
                const GLfixed pd = dot4(equation.v, p->eye.v);
                if (sd >= 0) {
                    if (pd >= 0) {
                        // both inside
                    } else {
                        // s inside, p outside (exiting)
                        const GLfixed t = clipDivide(sd, sd-pd);
                        c->arrays.clipEye(c, p, t, p, s);
                    }
                } else {
                    if (pd >= 0) {
                        // s outside (entering)
                        if (pd) {
                            const GLfixed t = clipDivide(pd, pd-sd);
                            c->arrays.clipEye(c, s, t, s, p);
                        }
                    } else {
                       // both outside
                       return 0;
                    }
                }
            }
            cc >>= 1;
            plane++;
        } while (cc);
    }

    // frustum clip-planes
    if (all_cc & vertex_t::FRUSTUM_CLIP_ALL)
    {
        unsigned int plane = 0;
        uint32_t cc = all_cc & vertex_t::FRUSTUM_CLIP_ALL;
        do {
            if (cc & 1) {
                const GLfixed sd = frustumPlaneDist(plane, s->clip);
                const GLfixed pd = frustumPlaneDist(plane, p->clip);
                if (sd >= 0) {
                    if (pd >= 0) {
                        // both inside
                    } else {
                        // s inside, p outside (exiting)
                        const GLfixed t = clipDivide(sd, sd-pd);
                        c->arrays.clipVertex(c, p, t, p, s);
                    }
                } else {
                    if (pd >= 0) {
                        // s outside (entering)
                        if (pd) {
                            const GLfixed t = clipDivide(pd, pd-sd);
                            c->arrays.clipVertex(c, s, t, s, p);
                        }
                    } else {
                       // both outside
                       return 0;
                    }
                }
            }
            cc >>= 1;
            plane++;
        } while (cc);
    }

    return 2;
}


}; // namespace android

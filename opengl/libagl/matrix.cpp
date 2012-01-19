/* libs/opengles/matrix.cpp
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

#include <stdlib.h>
#include <stdio.h>

#include "context.h"
#include "fp.h"
#include "state.h"
#include "matrix.h"
#include "vertex.h"
#include "light.h"

#if defined(__arm__) && defined(__thumb__)
#warning "matrix.cpp should not be compiled in thumb on ARM."
#endif

#define I(_i, _j) ((_j)+ 4*(_i))

namespace android {

// ----------------------------------------------------------------------------

static const GLfloat gIdentityf[16] = { 1,0,0,0,
                                        0,1,0,0,
                                        0,0,1,0,
                                        0,0,0,1 };

static const matrixx_t gIdentityx = { 
            {   0x10000,0,0,0,
                0,0x10000,0,0,
                0,0,0x10000,0,
                0,0,0,0x10000
            }
        };

static void point2__nop(transform_t const*, vec4_t* c, vec4_t const* o);
static void point3__nop(transform_t const*, vec4_t* c, vec4_t const* o);
static void point4__nop(transform_t const*, vec4_t* c, vec4_t const* o);
static void normal__nop(transform_t const*, vec4_t* c, vec4_t const* o);
static void point2__generic(transform_t const*, vec4_t* c, vec4_t const* o);
static void point3__generic(transform_t const*, vec4_t* c, vec4_t const* o);
static void point4__generic(transform_t const*, vec4_t* c, vec4_t const* o);
static void point3__mvui(transform_t const*, vec4_t* c, vec4_t const* o);
static void point4__mvui(transform_t const*, vec4_t* c, vec4_t const* o);

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

void ogles_init_matrix(ogles_context_t* c)
{
    c->transforms.modelview.init(OGLES_MODELVIEW_STACK_DEPTH);
    c->transforms.projection.init(OGLES_PROJECTION_STACK_DEPTH);
    for (int i=0; i<GGL_TEXTURE_UNIT_COUNT ; i++)
        c->transforms.texture[i].init(OGLES_TEXTURE_STACK_DEPTH);

    c->transforms.current = &c->transforms.modelview;
    c->transforms.matrixMode = GL_MODELVIEW;
    c->transforms.dirty =   transform_state_t::VIEWPORT | 
                            transform_state_t::MVUI |
                            transform_state_t::MVIT |
                            transform_state_t::MVP;
    c->transforms.mvp.loadIdentity();
    c->transforms.mvp4.loadIdentity();
    c->transforms.mvit4.loadIdentity();
    c->transforms.mvui.loadIdentity();
    c->transforms.vpt.loadIdentity();
    c->transforms.vpt.zNear = 0.0f;
    c->transforms.vpt.zFar  = 1.0f;
}

void ogles_uninit_matrix(ogles_context_t* c)
{
    c->transforms.modelview.uninit();
    c->transforms.projection.uninit();
    for (int i=0; i<GGL_TEXTURE_UNIT_COUNT ; i++)
        c->transforms.texture[i].uninit();
}

static void validate_perspective(ogles_context_t* c, vertex_t* v)
{
    const uint32_t enables = c->rasterizer.state.enables;
    c->arrays.perspective = (c->clipPlanes.enable) ?
        ogles_vertex_clipAllPerspective3D : ogles_vertex_perspective3D;
    if (enables & (GGL_ENABLE_DEPTH_TEST|GGL_ENABLE_FOG)) {
        c->arrays.perspective = ogles_vertex_perspective3DZ;
        if (c->clipPlanes.enable || (enables&GGL_ENABLE_FOG))
            c->arrays.perspective = ogles_vertex_clipAllPerspective3DZ;
    }
    if ((c->arrays.vertex.size != 4) &&
        (c->transforms.mvp4.flags & transform_t::FLAGS_2D_PROJECTION)) {
        c->arrays.perspective = ogles_vertex_perspective2D;
    }
    c->arrays.perspective(c, v);
}

void ogles_invalidate_perspective(ogles_context_t* c)
{
    c->arrays.perspective = validate_perspective;
}

void ogles_validate_transform_impl(ogles_context_t* c, uint32_t want)
{
    int dirty = c->transforms.dirty & want;

    // Validate the modelview
    if (dirty & transform_state_t::MODELVIEW) {
        c->transforms.modelview.validate();
    }

    // Validate the projection stack (in fact, it's never needed)
    if (dirty & transform_state_t::PROJECTION) {
        c->transforms.projection.validate();
    }

    // Validate the viewport transformation
    if (dirty & transform_state_t::VIEWPORT) {
        vp_transform_t& vpt = c->transforms.vpt;
        vpt.transform.matrix.load(vpt.matrix);
        vpt.transform.picker();
    }

    // We need to update the mvp (used to transform each vertex)
    if (dirty & transform_state_t::MVP) {
        c->transforms.update_mvp();
        // invalidate perspective (divide by W) and view volume clipping
        ogles_invalidate_perspective(c);
    }

    // Validate the mvui (for normal transformation)
    if (dirty & transform_state_t::MVUI) {
        c->transforms.update_mvui();
        ogles_invalidate_lighting_mvui(c);
    }

    // Validate the texture stack
    if (dirty & transform_state_t::TEXTURE) {
        for (int i=0; i<GGL_TEXTURE_UNIT_COUNT ; i++)
            c->transforms.texture[i].validate();
    }

    // Validate the mvit4 (user-clip planes)
    if (dirty & transform_state_t::MVIT) {
        c->transforms.update_mvit();
    }

    c->transforms.dirty &= ~want;
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark transform_t
#endif

void transform_t::loadIdentity() {
    matrix = gIdentityx;
    flags = 0;
    ops = OP_IDENTITY;
    point2 = point2__nop;
    point3 = point3__nop;
    point4 = point4__nop;
}


static inline
int notZero(GLfixed v) {
    return abs(v) & ~0x3;
}

static inline
int notOne(GLfixed v) {
    return notZero(v - 0x10000);
}

void transform_t::picker()
{
    const GLfixed* const m = matrix.m;

    // XXX: picker needs to be smarter
    flags = 0;
    ops = OP_ALL;
    point2 = point2__generic;
    point3 = point3__generic;
    point4 = point4__generic;
    
    // find out if this is a 2D projection
    if (!(notZero(m[3]) | notZero(m[7]) | notZero(m[11]) | notOne(m[15]))) {
        flags |= FLAGS_2D_PROJECTION;
    }
}

void mvui_transform_t::picker()
{
    flags = 0;
    ops = OP_ALL;
    point3 = point3__mvui;
    point4 = point4__mvui;
}

void transform_t::dump(const char* what)
{
    GLfixed const * const m = matrix.m;
    ALOGD("%s:", what);
    for (int i=0 ; i<4 ; i++)
        ALOGD("[%08x %08x %08x %08x] [%f %f %f %f]\n",
            m[I(0,i)], m[I(1,i)], m[I(2,i)], m[I(3,i)],
            fixedToFloat(m[I(0,i)]),
            fixedToFloat(m[I(1,i)]), 
            fixedToFloat(m[I(2,i)]),
            fixedToFloat(m[I(3,i)]));
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark matrixx_t
#endif

void matrixx_t::load(const matrixf_t& rhs) {
    GLfixed* xp = m;
    GLfloat const* fp = rhs.elements();
    unsigned int i = 16;
    do {
        const GLfloat f = *fp++;
        *xp++ = isZerof(f) ? 0 : gglFloatToFixed(f);
    } while (--i);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark matrixf_t
#endif

void matrixf_t::multiply(matrixf_t& r, const matrixf_t& lhs, const matrixf_t& rhs)
{
    GLfloat const* const m = lhs.m;
    for (int i=0 ; i<4 ; i++) {
        register const float rhs_i0 = rhs.m[ I(i,0) ];
        register float ri0 = m[ I(0,0) ] * rhs_i0;
        register float ri1 = m[ I(0,1) ] * rhs_i0;
        register float ri2 = m[ I(0,2) ] * rhs_i0;
        register float ri3 = m[ I(0,3) ] * rhs_i0;
        for (int j=1 ; j<4 ; j++) {
            register const float rhs_ij = rhs.m[ I(i,j) ];
            ri0 += m[ I(j,0) ] * rhs_ij;
            ri1 += m[ I(j,1) ] * rhs_ij;
            ri2 += m[ I(j,2) ] * rhs_ij;
            ri3 += m[ I(j,3) ] * rhs_ij;
        }
        r.m[ I(i,0) ] = ri0;
        r.m[ I(i,1) ] = ri1;
        r.m[ I(i,2) ] = ri2;
        r.m[ I(i,3) ] = ri3;
    }
}

void matrixf_t::dump(const char* what) {
    ALOGD("%s", what);
    ALOGD("[ %9f %9f %9f %9f ]", m[I(0,0)], m[I(1,0)], m[I(2,0)], m[I(3,0)]);
    ALOGD("[ %9f %9f %9f %9f ]", m[I(0,1)], m[I(1,1)], m[I(2,1)], m[I(3,1)]);
    ALOGD("[ %9f %9f %9f %9f ]", m[I(0,2)], m[I(1,2)], m[I(2,2)], m[I(3,2)]);
    ALOGD("[ %9f %9f %9f %9f ]", m[I(0,3)], m[I(1,3)], m[I(2,3)], m[I(3,3)]);
}

void matrixf_t::loadIdentity() {
    memcpy(m, gIdentityf, sizeof(m));
}

void matrixf_t::set(const GLfixed* rhs) {
    load(rhs);
}

void matrixf_t::set(const GLfloat* rhs) {
    load(rhs);
}

void matrixf_t::load(const GLfixed* rhs) {
    GLfloat* fp = m;
    unsigned int i = 16;
    do {
        *fp++ = fixedToFloat(*rhs++);
    } while (--i);
}

void matrixf_t::load(const GLfloat* rhs) {
    memcpy(m, rhs, sizeof(m));
}

void matrixf_t::load(const matrixf_t& rhs) {
    operator = (rhs);
}

void matrixf_t::multiply(const matrixf_t& rhs) {
    matrixf_t r;
    multiply(r, *this, rhs);
    operator = (r);
}

void matrixf_t::translate(GLfloat x, GLfloat y, GLfloat z) {
    for (int i=0 ; i<4 ; i++) {
        m[12+i] += m[i]*x + m[4+i]*y + m[8+i]*z;
    }
}

void matrixf_t::scale(GLfloat x, GLfloat y, GLfloat z) {
    for (int i=0 ; i<4 ; i++) {
        m[  i] *= x;
        m[4+i] *= y;
        m[8+i] *= z;
    }
}

void matrixf_t::rotate(GLfloat a, GLfloat x, GLfloat y, GLfloat z)
{
    matrixf_t rotation;
    GLfloat* r = rotation.m;
    GLfloat c, s;
    r[3] = 0;   r[7] = 0;   r[11]= 0;
    r[12]= 0;   r[13]= 0;   r[14]= 0;   r[15]= 1;
    a *= GLfloat(M_PI / 180.0f);
    sincosf(a, &s, &c);
    if (isOnef(x) && isZerof(y) && isZerof(z)) {
        r[5] = c;   r[10]= c;
        r[6] = s;   r[9] = -s;
        r[1] = 0;   r[2] = 0;
        r[4] = 0;   r[8] = 0;
        r[0] = 1;
    } else if (isZerof(x) && isOnef(y) && isZerof(z)) {
        r[0] = c;   r[10]= c;
        r[8] = s;   r[2] = -s;
        r[1] = 0;   r[4] = 0;
        r[6] = 0;   r[9] = 0;
        r[5] = 1;
    } else if (isZerof(x) && isZerof(y) && isOnef(z)) {
        r[0] = c;   r[5] = c;
        r[1] = s;   r[4] = -s;
        r[2] = 0;   r[6] = 0;
        r[8] = 0;   r[9] = 0;
        r[10]= 1;
    } else {
        const GLfloat len = sqrtf(x*x + y*y + z*z);
        if (!isOnef(len)) {
            const GLfloat recipLen = reciprocalf(len);
            x *= recipLen;
            y *= recipLen;
            z *= recipLen;
        }
        const GLfloat nc = 1.0f - c;
        const GLfloat xy = x * y;
        const GLfloat yz = y * z;
        const GLfloat zx = z * x;
        const GLfloat xs = x * s;
        const GLfloat ys = y * s;
        const GLfloat zs = z * s;		
        r[ 0] = x*x*nc +  c;    r[ 4] =  xy*nc - zs;    r[ 8] =  zx*nc + ys;
        r[ 1] =  xy*nc + zs;    r[ 5] = y*y*nc +  c;    r[ 9] =  yz*nc - xs;
        r[ 2] =  zx*nc - ys;    r[ 6] =  yz*nc + xs;    r[10] = z*z*nc +  c;
    }
    multiply(rotation);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark matrix_stack_t
#endif

void matrix_stack_t::init(int depth) {
    stack = new matrixf_t[depth];
    ops = new uint8_t[depth];
    maxDepth = depth;
    depth = 0;
    dirty = 0;
    loadIdentity();
}

void matrix_stack_t::uninit() {
    delete [] stack;
    delete [] ops;
}

void matrix_stack_t::loadIdentity() {
    transform.loadIdentity();
    stack[depth].loadIdentity();
    ops[depth] = OP_IDENTITY;
}

void matrix_stack_t::load(const GLfixed* rhs)
{   
    memcpy(transform.matrix.m, rhs, sizeof(transform.matrix.m));
    stack[depth].load(rhs);
    ops[depth] = OP_ALL;    // TODO: we should look at the matrix
}

void matrix_stack_t::load(const GLfloat* rhs)
{
    stack[depth].load(rhs);
    ops[depth] = OP_ALL;    // TODO: we should look at the matrix
}

void matrix_stack_t::multiply(const matrixf_t& rhs)
{    
    stack[depth].multiply(rhs);
    ops[depth] = OP_ALL;    // TODO: we should look at the matrix
}

void matrix_stack_t::translate(GLfloat x, GLfloat y, GLfloat z)
{
    stack[depth].translate(x,y,z);
    ops[depth] |= OP_TRANSLATE;
}

void matrix_stack_t::scale(GLfloat x, GLfloat y, GLfloat z)
{
    stack[depth].scale(x,y,z);
    if (x==y && y==z) {
        ops[depth] |= OP_UNIFORM_SCALE;
    } else {
        ops[depth] |= OP_SCALE;
    }
}

void matrix_stack_t::rotate(GLfloat a, GLfloat x, GLfloat y, GLfloat z)
{
    stack[depth].rotate(a,x,y,z);
    ops[depth] |= OP_ROTATE;
}

void matrix_stack_t::validate()
{
    if (dirty & DO_FLOAT_TO_FIXED) {
        transform.matrix.load(top());
    }
    if (dirty & DO_PICKER) {
        transform.picker();
    }
    dirty = 0;
}

GLint matrix_stack_t::push()
{
    if (depth >= (maxDepth-1)) {
        return GL_STACK_OVERFLOW;
    }
    stack[depth+1] = stack[depth];
    ops[depth+1] = ops[depth];
    depth++;
    return 0;
}

GLint matrix_stack_t::pop()
{
    if (depth == 0) {
        return GL_STACK_UNDERFLOW;
    }
    depth--;
    return 0;
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark vp_transform_t
#endif

void vp_transform_t::loadIdentity() {
    transform.loadIdentity();
    matrix.loadIdentity();
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark transform_state_t
#endif

void transform_state_t::invalidate()
{
    switch (matrixMode) {
    case GL_MODELVIEW:  dirty |= MODELVIEW  | MVP | MVUI | MVIT;    break;
    case GL_PROJECTION: dirty |= PROJECTION | MVP;                  break;
    case GL_TEXTURE:    dirty |= TEXTURE    | MVP;                  break;
    }
    current->dirty =    matrix_stack_t::DO_PICKER |
                        matrix_stack_t::DO_FLOAT_TO_FIXED;
}

void transform_state_t::update_mvp()
{
    matrixf_t temp_mvp;
    matrixf_t::multiply(temp_mvp, projection.top(), modelview.top());
    mvp4.matrix.load(temp_mvp);
    mvp4.picker();

    if (mvp4.flags & transform_t::FLAGS_2D_PROJECTION) {
        // the mvp matrix doesn't transform W, in this case we can
        // premultiply it with the viewport transformation. In addition to
        // being more efficient, this is also much more accurate and in fact
        // is needed for 2D drawing with a resulting 1:1 mapping.
        matrixf_t mvpv;
        matrixf_t::multiply(mvpv, vpt.matrix, temp_mvp);
        mvp.matrix.load(mvpv);
        mvp.picker();
    } else {
        mvp = mvp4;
    }
}

static inline 
GLfloat det22(GLfloat a, GLfloat b, GLfloat c, GLfloat d) {
    return a*d - b*c;
}

static inline
GLfloat ndet22(GLfloat a, GLfloat b, GLfloat c, GLfloat d) {
    return b*c - a*d;
}

static __attribute__((noinline))
void invert(GLfloat* inverse, const GLfloat* src)
{
    double t;
    int i, j, k, swap;
    GLfloat tmp[4][4];
    
    memcpy(inverse, gIdentityf, sizeof(gIdentityf));
    memcpy(tmp, src, sizeof(GLfloat)*16);
    
    for (i = 0; i < 4; i++) {
        // look for largest element in column
        swap = i;
        for (j = i + 1; j < 4; j++) {
            if (fabs(tmp[j][i]) > fabs(tmp[i][i])) {
                swap = j;
            }
        }
        
        if (swap != i) {
            /* swap rows. */
            for (k = 0; k < 4; k++) {
                t = tmp[i][k];
                tmp[i][k] = tmp[swap][k];
                tmp[swap][k] = t;
                
                t = inverse[i*4+k];
                inverse[i*4+k] = inverse[swap*4+k];
                inverse[swap*4+k] = t;
            }
        }
        
        t = 1.0f / tmp[i][i];
        for (k = 0; k < 4; k++) {
            tmp[i][k] *= t;
            inverse[i*4+k] *= t;
        }
        for (j = 0; j < 4; j++) {
            if (j != i) {
                t = tmp[j][i];
                for (k = 0; k < 4; k++) {
                    tmp[j][k] -= tmp[i][k]*t;
                    inverse[j*4+k] -= inverse[i*4+k]*t;
                }
            }
        }
    }
}

void transform_state_t::update_mvit()
{
    GLfloat r[16];
    const GLfloat* const mv = modelview.top().elements();
    invert(r, mv);
    // convert to fixed-point and transpose
    GLfixed* const x = mvit4.matrix.m;
    for (int i=0 ; i<4 ; i++)
        for (int j=0 ; j<4 ; j++)
            x[I(i,j)] = gglFloatToFixed(r[I(j,i)]);
    mvit4.picker();
}

void transform_state_t::update_mvui()
{
    GLfloat r[16];
    const GLfloat* const mv = modelview.top().elements();
    
    /*
    When evaluating the lighting equation in eye-space, normals
    are transformed by the upper 3x3 modelview inverse-transpose.
    http://www.opengl.org/documentation/specs/version1.1/glspec1.1/node26.html

    (note that inverse-transpose is distributive).
    Also note that:
        l(obj) = inv(modelview).l(eye) for local light
        l(obj) =  tr(modelview).l(eye) for infinite light
    */

    invert(r, mv);

    GLfixed* const x = mvui.matrix.m;

#if OBJECT_SPACE_LIGHTING
    for (int i=0 ; i<4 ; i++)
        for (int j=0 ; j<4 ; j++)
            x[I(i,j)] = gglFloatToFixed(r[I(i,j)]);
#else
    for (int i=0 ; i<4 ; i++)
        for (int j=0 ; j<4 ; j++)
            x[I(i,j)] = gglFloatToFixed(r[I(j,i)]);
#endif

    mvui.picker();
}


// ----------------------------------------------------------------------------
// transformation and matrices API
// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark transformation and matrices API
#endif

int ogles_surfaceport(ogles_context_t* c, GLint x, GLint y)
{
    c->viewport.surfaceport.x = x;
    c->viewport.surfaceport.y = y;

    ogles_viewport(c, 
            c->viewport.x,
            c->viewport.y,
            c->viewport.w,
            c->viewport.h);

    ogles_scissor(c,
            c->viewport.scissor.x,
            c->viewport.scissor.y,
            c->viewport.scissor.w,
            c->viewport.scissor.h);

    return 0;
}

void ogles_scissor(ogles_context_t* c, 
        GLint x, GLint y, GLsizei w, GLsizei h)
{
    if ((w|h) < 0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    c->viewport.scissor.x = x;
    c->viewport.scissor.y = y;
    c->viewport.scissor.w = w;
    c->viewport.scissor.h = h;
    
    x += c->viewport.surfaceport.x;
    y += c->viewport.surfaceport.y;

    y = c->rasterizer.state.buffers.color.height - (y + h);
    c->rasterizer.procs.scissor(c, x, y, w, h);
}

void ogles_viewport(ogles_context_t* c,
        GLint x, GLint y, GLsizei w, GLsizei h)
{
    if ((w|h)<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    c->viewport.x = x;
    c->viewport.y = y;
    c->viewport.w = w;
    c->viewport.h = h;

    x += c->viewport.surfaceport.x;
    y += c->viewport.surfaceport.y;

    GLint H = c->rasterizer.state.buffers.color.height;
    GLfloat sx = div2f(w);
    GLfloat ox = sx + x;
    GLfloat sy = div2f(h);
    GLfloat oy = sy - y + (H - h);

    GLfloat near = c->transforms.vpt.zNear;
    GLfloat far  = c->transforms.vpt.zFar;
    GLfloat A = div2f(far - near);
    GLfloat B = div2f(far + near);

    // compute viewport matrix
    GLfloat* const f = c->transforms.vpt.matrix.editElements();
    f[0] = sx;  f[4] = 0;   f[ 8] = 0;  f[12] = ox;
    f[1] = 0;   f[5] =-sy;  f[ 9] = 0;  f[13] = oy;
    f[2] = 0;   f[6] = 0;   f[10] = A;  f[14] = B;
    f[3] = 0;   f[7] = 0;   f[11] = 0;  f[15] = 1;
    c->transforms.dirty |= transform_state_t::VIEWPORT;
    if (c->transforms.mvp4.flags & transform_t::FLAGS_2D_PROJECTION)
        c->transforms.dirty |= transform_state_t::MVP;
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark matrix * vertex
#endif

void point2__generic(transform_t const* mx, vec4_t* lhs, vec4_t const* rhs) {
    const GLfixed* const m = mx->matrix.m;
    const GLfixed rx = rhs->x;
    const GLfixed ry = rhs->y;
    lhs->x = mla2a(rx, m[ 0], ry, m[ 4], m[12]); 
    lhs->y = mla2a(rx, m[ 1], ry, m[ 5], m[13]);
    lhs->z = mla2a(rx, m[ 2], ry, m[ 6], m[14]);
    lhs->w = mla2a(rx, m[ 3], ry, m[ 7], m[15]);
}

void point3__generic(transform_t const* mx, vec4_t* lhs, vec4_t const* rhs) {
    const GLfixed* const m = mx->matrix.m;
    const GLfixed rx = rhs->x;
    const GLfixed ry = rhs->y;
    const GLfixed rz = rhs->z;
    lhs->x = mla3a(rx, m[ 0], ry, m[ 4], rz, m[ 8], m[12]); 
    lhs->y = mla3a(rx, m[ 1], ry, m[ 5], rz, m[ 9], m[13]);
    lhs->z = mla3a(rx, m[ 2], ry, m[ 6], rz, m[10], m[14]);
    lhs->w = mla3a(rx, m[ 3], ry, m[ 7], rz, m[11], m[15]);
}

void point4__generic(transform_t const* mx, vec4_t* lhs, vec4_t const* rhs) {
    const GLfixed* const m = mx->matrix.m;
    const GLfixed rx = rhs->x;
    const GLfixed ry = rhs->y;
    const GLfixed rz = rhs->z;
    const GLfixed rw = rhs->w;
    lhs->x = mla4(rx, m[ 0], ry, m[ 4], rz, m[ 8], rw, m[12]); 
    lhs->y = mla4(rx, m[ 1], ry, m[ 5], rz, m[ 9], rw, m[13]);
    lhs->z = mla4(rx, m[ 2], ry, m[ 6], rz, m[10], rw, m[14]);
    lhs->w = mla4(rx, m[ 3], ry, m[ 7], rz, m[11], rw, m[15]);
}

void point3__mvui(transform_t const* mx, vec4_t* lhs, vec4_t const* rhs) {
    // this is used for transforming light positions back to object space.
    // w is used as a switch for directional lights, so we need
    // to preserve it.
    const GLfixed* const m = mx->matrix.m;
    const GLfixed rx = rhs->x;
    const GLfixed ry = rhs->y;
    const GLfixed rz = rhs->z;
    lhs->x = mla3(rx, m[ 0], ry, m[ 4], rz, m[ 8]);
    lhs->y = mla3(rx, m[ 1], ry, m[ 5], rz, m[ 9]);
    lhs->z = mla3(rx, m[ 2], ry, m[ 6], rz, m[10]);
    lhs->w = 0;
}

void point4__mvui(transform_t const* mx, vec4_t* lhs, vec4_t const* rhs) {
    // this is used for transforming light positions back to object space.
    // w is used as a switch for directional lights, so we need
    // to preserve it.
    const GLfixed* const m = mx->matrix.m;
    const GLfixed rx = rhs->x;
    const GLfixed ry = rhs->y;
    const GLfixed rz = rhs->z;
    const GLfixed rw = rhs->w;
    lhs->x = mla4(rx, m[ 0], ry, m[ 4], rz, m[ 8], rw, m[12]);
    lhs->y = mla4(rx, m[ 1], ry, m[ 5], rz, m[ 9], rw, m[13]);
    lhs->z = mla4(rx, m[ 2], ry, m[ 6], rz, m[10], rw, m[14]);
    lhs->w = rw;
}

void point2__nop(transform_t const*, vec4_t* lhs, vec4_t const* rhs) {
    lhs->z = 0;
    lhs->w = 0x10000;
    if (lhs != rhs) {
        lhs->x = rhs->x;
        lhs->y = rhs->y;
    }
}

void point3__nop(transform_t const*, vec4_t* lhs, vec4_t const* rhs) {
    lhs->w = 0x10000;
    if (lhs != rhs) {
        lhs->x = rhs->x;
        lhs->y = rhs->y;
        lhs->z = rhs->z;
    }
}

void point4__nop(transform_t const*, vec4_t* lhs, vec4_t const* rhs) {
    if (lhs != rhs)
        *lhs = *rhs;
}


static void frustumf(
            GLfloat left, GLfloat right, 
            GLfloat bottom, GLfloat top,
            GLfloat zNear, GLfloat zFar,
            ogles_context_t* c)
    {
    if (cmpf(left,right) ||
        cmpf(top, bottom) ||
        cmpf(zNear, zFar) ||
        isZeroOrNegativef(zNear) ||
        isZeroOrNegativef(zFar))
    {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    const GLfloat r_width  = reciprocalf(right - left);
    const GLfloat r_height = reciprocalf(top - bottom);
    const GLfloat r_depth  = reciprocalf(zNear - zFar);
    const GLfloat x = mul2f(zNear * r_width);
    const GLfloat y = mul2f(zNear * r_height);
    const GLfloat A = mul2f((right + left) * r_width);
    const GLfloat B = (top + bottom) * r_height;
    const GLfloat C = (zFar + zNear) * r_depth;
    const GLfloat D = mul2f(zFar * zNear * r_depth);
    GLfloat f[16];
    f[ 0] = x;
    f[ 5] = y;
    f[ 8] = A;
    f[ 9] = B;
    f[10] = C;
    f[14] = D;
    f[11] = -1.0f;
    f[ 1] = f[ 2] = f[ 3] =
    f[ 4] = f[ 6] = f[ 7] =
    f[12] = f[13] = f[15] = 0.0f;

    matrixf_t rhs;
    rhs.set(f);
    c->transforms.current->multiply(rhs);
    c->transforms.invalidate();
}

static void orthof( 
        GLfloat left, GLfloat right, 
        GLfloat bottom, GLfloat top,
        GLfloat zNear, GLfloat zFar,
        ogles_context_t* c)
{
    if (cmpf(left,right) ||
        cmpf(top, bottom) ||
        cmpf(zNear, zFar))
    {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    const GLfloat r_width  = reciprocalf(right - left);
    const GLfloat r_height = reciprocalf(top - bottom);
    const GLfloat r_depth  = reciprocalf(zFar - zNear);
    const GLfloat x =  mul2f(r_width);
    const GLfloat y =  mul2f(r_height);
    const GLfloat z = -mul2f(r_depth);
    const GLfloat tx = -(right + left) * r_width;
    const GLfloat ty = -(top + bottom) * r_height;
    const GLfloat tz = -(zFar + zNear) * r_depth;
    GLfloat f[16];
    f[ 0] = x;
    f[ 5] = y;
    f[10] = z;
    f[12] = tx;
    f[13] = ty;
    f[14] = tz;
    f[15] = 1.0f;
    f[ 1] = f[ 2] = f[ 3] =
    f[ 4] = f[ 6] = f[ 7] =
    f[ 8] = f[ 9] = f[11] = 0.0f;
    matrixf_t rhs;
    rhs.set(f);
    c->transforms.current->multiply(rhs);
    c->transforms.invalidate();
}

static void depthRangef(GLclampf zNear, GLclampf zFar, ogles_context_t* c)
{
    zNear = clampToZerof(zNear > 1 ? 1 : zNear);
    zFar  = clampToZerof(zFar  > 1 ? 1 : zFar);
    GLfloat* const f = c->transforms.vpt.matrix.editElements();
    f[10] = div2f(zFar - zNear);
    f[14] = div2f(zFar + zNear);
    c->transforms.dirty |= transform_state_t::VIEWPORT;
    c->transforms.vpt.zNear = zNear;
    c->transforms.vpt.zFar  = zFar;
}


// ----------------------------------------------------------------------------
}; // namespace android

using namespace android;

void glMatrixMode(GLenum mode)
{
    ogles_context_t* c = ogles_context_t::get();
    matrix_stack_t* stack = 0;
    switch (mode) {
    case GL_MODELVIEW:
        stack = &c->transforms.modelview;
        break;
    case GL_PROJECTION:
        stack = &c->transforms.projection;
        break;
    case GL_TEXTURE:
        stack = &c->transforms.texture[c->textures.active];
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->transforms.matrixMode = mode;
    c->transforms.current = stack;
}

void glLoadIdentity()
{
    ogles_context_t* c = ogles_context_t::get();
    c->transforms.current->loadIdentity(); // also loads the GLfixed transform
    c->transforms.invalidate();
    c->transforms.current->dirty = 0;
}

void glLoadMatrixf(const GLfloat* m)
{
    ogles_context_t* c = ogles_context_t::get();
    c->transforms.current->load(m);
    c->transforms.invalidate();
}

void glLoadMatrixx(const GLfixed* m)
{
    ogles_context_t* c = ogles_context_t::get();
    c->transforms.current->load(m); // also loads the GLfixed transform
    c->transforms.invalidate();
    c->transforms.current->dirty &= ~matrix_stack_t::DO_FLOAT_TO_FIXED;
}

void glMultMatrixf(const GLfloat* m)
{
    ogles_context_t* c = ogles_context_t::get();
    matrixf_t rhs;
    rhs.set(m);
    c->transforms.current->multiply(rhs);
    c->transforms.invalidate();
}

void glMultMatrixx(const GLfixed* m)
{
    ogles_context_t* c = ogles_context_t::get();
    matrixf_t rhs;
    rhs.set(m);
    c->transforms.current->multiply(rhs);
    c->transforms.invalidate();
}

void glPopMatrix()
{
    ogles_context_t* c = ogles_context_t::get();
    GLint err = c->transforms.current->pop();
    if (ggl_unlikely(err)) {
        ogles_error(c, err);
        return;
    }
    c->transforms.invalidate();
}

void glPushMatrix()
{
    ogles_context_t* c = ogles_context_t::get();
    GLint err = c->transforms.current->push();
    if (ggl_unlikely(err)) {
        ogles_error(c, err);
        return;
    }
    c->transforms.invalidate();
}

void glFrustumf(
        GLfloat left, GLfloat right, 
        GLfloat bottom, GLfloat top,
        GLfloat zNear, GLfloat zFar)
{
    ogles_context_t* c = ogles_context_t::get();
    frustumf(left, right, bottom, top, zNear, zFar, c);
}

void glFrustumx( 
        GLfixed left, GLfixed right,
        GLfixed bottom, GLfixed top,
        GLfixed zNear, GLfixed zFar)
{
    ogles_context_t* c = ogles_context_t::get();
    frustumf( fixedToFloat(left), fixedToFloat(right),
              fixedToFloat(bottom), fixedToFloat(top),
              fixedToFloat(zNear), fixedToFloat(zFar),
              c);
}

void glOrthof( 
        GLfloat left, GLfloat right, 
        GLfloat bottom, GLfloat top,
        GLfloat zNear, GLfloat zFar)
{
    ogles_context_t* c = ogles_context_t::get();
    orthof(left, right, bottom, top, zNear, zFar, c);
}

void glOrthox(
        GLfixed left, GLfixed right,
        GLfixed bottom, GLfixed top,
        GLfixed zNear, GLfixed zFar)
{
    ogles_context_t* c = ogles_context_t::get();
    orthof( fixedToFloat(left), fixedToFloat(right),
            fixedToFloat(bottom), fixedToFloat(top),
            fixedToFloat(zNear), fixedToFloat(zFar),
            c);
}

void glRotatef(GLfloat a, GLfloat x, GLfloat y, GLfloat z)
{
    ogles_context_t* c = ogles_context_t::get();
    c->transforms.current->rotate(a, x, y, z);
    c->transforms.invalidate();
}

void glRotatex(GLfixed a, GLfixed x, GLfixed y, GLfixed z)
{
    ogles_context_t* c = ogles_context_t::get();
    c->transforms.current->rotate( 
            fixedToFloat(a), fixedToFloat(x),
            fixedToFloat(y), fixedToFloat(z));
    c->transforms.invalidate();
}

void glScalef(GLfloat x, GLfloat y, GLfloat z)
{
    ogles_context_t* c = ogles_context_t::get();
    c->transforms.current->scale(x, y, z);
    c->transforms.invalidate();
}

void glScalex(GLfixed x, GLfixed y, GLfixed z)
{
    ogles_context_t* c = ogles_context_t::get();
    c->transforms.current->scale(
            fixedToFloat(x), fixedToFloat(y), fixedToFloat(z));
    c->transforms.invalidate();
}

void glTranslatef(GLfloat x, GLfloat y, GLfloat z)
{
    ogles_context_t* c = ogles_context_t::get();
    c->transforms.current->translate(x, y, z);
    c->transforms.invalidate();
}

void glTranslatex(GLfixed x, GLfixed y, GLfixed z)
{
    ogles_context_t* c = ogles_context_t::get();
    c->transforms.current->translate(
            fixedToFloat(x), fixedToFloat(y), fixedToFloat(z));
    c->transforms.invalidate();
}

void glScissor(GLint x, GLint y, GLsizei w, GLsizei h)
{
    ogles_context_t* c = ogles_context_t::get();
    ogles_scissor(c, x, y, w, h);
}

void glViewport(GLint x, GLint y, GLsizei w, GLsizei h)
{
    ogles_context_t* c = ogles_context_t::get();
    ogles_viewport(c, x, y, w, h);
}

void glDepthRangef(GLclampf zNear, GLclampf zFar)
{
    ogles_context_t* c = ogles_context_t::get();
    depthRangef(zNear, zFar, c);
}

void glDepthRangex(GLclampx zNear, GLclampx zFar)
{
    ogles_context_t* c = ogles_context_t::get();
    depthRangef(fixedToFloat(zNear), fixedToFloat(zFar), c);
}

void glPolygonOffsetx(GLfixed factor, GLfixed units)
{
    ogles_context_t* c = ogles_context_t::get();
    c->polygonOffset.factor = factor;
    c->polygonOffset.units = units;
}

void glPolygonOffset(GLfloat factor, GLfloat units)
{
    ogles_context_t* c = ogles_context_t::get();
    c->polygonOffset.factor = gglFloatToFixed(factor);
    c->polygonOffset.units = gglFloatToFixed(units);
}

GLbitfield glQueryMatrixxOES(GLfixed* m, GLint* e)
{
    ogles_context_t* c = ogles_context_t::get();
    GLbitfield status = 0;
    GLfloat const* f = c->transforms.current->top().elements();
    for  (int i=0 ; i<16 ; i++) {
        if (isnan(f[i]) || isinf(f[i])) {
            status |= 1<<i;
            continue;
        }
        e[i] = exponent(f[i]) - 7;
        m[i] = mantissa(f[i]);
    }
    return status;
}

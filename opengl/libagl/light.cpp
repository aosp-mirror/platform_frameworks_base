/* libs/opengles/light.cpp
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
#include "context.h"
#include "fp.h"
#include "light.h"
#include "state.h"
#include "matrix.h"


#if defined(__arm__) && defined(__thumb__)
#warning "light.cpp should not be compiled in thumb on ARM."
#endif

namespace android {

// ----------------------------------------------------------------------------

static void invalidate_lighting(ogles_context_t* c);
static void lightVertexValidate(ogles_context_t* c, vertex_t* v);
static void lightVertexNop(ogles_context_t* c, vertex_t* v);
static void lightVertex(ogles_context_t* c, vertex_t* v);
static void lightVertexMaterial(ogles_context_t* c, vertex_t* v);

static inline void vscale3(GLfixed* d, const GLfixed* m, GLfixed s);

static __attribute__((noinline))
void vnorm3(GLfixed* d, const GLfixed* a);

static inline void vsa3(GLfixed* d,
    const GLfixed* m, GLfixed s, const GLfixed* a);
static inline void vss3(GLfixed* d,
    const GLfixed* m, GLfixed s, const GLfixed* a);
static inline void vmla3(GLfixed* d,
    const GLfixed* m0, const GLfixed* m1, const GLfixed* a);
static inline void vmul3(GLfixed* d,
    const GLfixed* m0, const GLfixed* m1);

static GLfixed fog_linear(ogles_context_t* c, GLfixed z);
static GLfixed fog_exp(ogles_context_t* c, GLfixed z);
static GLfixed fog_exp2(ogles_context_t* c, GLfixed z);


// ----------------------------------------------------------------------------

static void init_white(vec4_t& c) {
    c.r = c.g = c.b = c.a = 0x10000;
}

void ogles_init_light(ogles_context_t* c)
{
    for (unsigned int i=0 ; i<OGLES_MAX_LIGHTS ; i++) {
        c->lighting.lights[i].ambient.a = 0x10000;
        c->lighting.lights[i].position.z = 0x10000;
        c->lighting.lights[i].spotDir.z = -0x10000;
        c->lighting.lights[i].spotCutoff = gglIntToFixed(180);
        c->lighting.lights[i].attenuation[0] = 0x10000;
    }
    init_white(c->lighting.lights[0].diffuse);
    init_white(c->lighting.lights[0].specular);

    c->lighting.front.ambient.r =
    c->lighting.front.ambient.g =
    c->lighting.front.ambient.b = gglFloatToFixed(0.2f);
    c->lighting.front.ambient.a = 0x10000;
    c->lighting.front.diffuse.r =
    c->lighting.front.diffuse.g =
    c->lighting.front.diffuse.b = gglFloatToFixed(0.8f);
    c->lighting.front.diffuse.a = 0x10000;
    c->lighting.front.specular.a = 0x10000;
    c->lighting.front.emission.a = 0x10000;

    c->lighting.lightModel.ambient.r =
    c->lighting.lightModel.ambient.g =
    c->lighting.lightModel.ambient.b = gglFloatToFixed(0.2f);
    c->lighting.lightModel.ambient.a = 0x10000;

    c->lighting.colorMaterial.face = GL_FRONT_AND_BACK;
    c->lighting.colorMaterial.mode = GL_AMBIENT_AND_DIFFUSE;

    c->fog.mode = GL_EXP;
    c->fog.fog = fog_exp;
    c->fog.density = 0x10000;
    c->fog.end = 0x10000;
    c->fog.invEndMinusStart = 0x10000;

    invalidate_lighting(c);
       
    c->rasterizer.procs.shadeModel(c, GL_SMOOTH);
    c->lighting.shadeModel = GL_SMOOTH;
}

void ogles_uninit_light(ogles_context_t* c)
{
}

static inline int32_t clampF(GLfixed f) CONST;
int32_t clampF(GLfixed f) {
    f = (f & ~(f>>31));
    if (f >= 0x10000)
        f = 0x10000;
    return f;
}

static GLfixed fog_linear(ogles_context_t* c, GLfixed z) {
    return clampF(gglMulx((c->fog.end - ((z<0)?-z:z)), c->fog.invEndMinusStart));
}

static GLfixed fog_exp(ogles_context_t* c, GLfixed z) {
    const float e = fixedToFloat(gglMulx(c->fog.density, ((z<0)?-z:z)));
    return clampF(gglFloatToFixed(fastexpf(-e)));
}

static GLfixed fog_exp2(ogles_context_t* c, GLfixed z) {
    const float e = fixedToFloat(gglMulx(c->fog.density, z));
    return clampF(gglFloatToFixed(fastexpf(-e*e)));
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark math helpers
#endif

static inline
void vscale3(GLfixed* d, const GLfixed* m, GLfixed s) {
    d[0] = gglMulx(m[0], s);
    d[1] = gglMulx(m[1], s);
    d[2] = gglMulx(m[2], s);
}

static inline
void vsa3(GLfixed* d, const GLfixed* m, GLfixed s, const GLfixed* a) {
    d[0] = gglMulAddx(m[0], s, a[0]);
    d[1] = gglMulAddx(m[1], s, a[1]);
    d[2] = gglMulAddx(m[2], s, a[2]);
}

static inline
void vss3(GLfixed* d, const GLfixed* m, GLfixed s, const GLfixed* a) {
    d[0] = gglMulSubx(m[0], s, a[0]);
    d[1] = gglMulSubx(m[1], s, a[1]);
    d[2] = gglMulSubx(m[2], s, a[2]);
}

static inline
void vmla3(GLfixed* d,
        const GLfixed* m0, const GLfixed* m1, const GLfixed* a)
{
    d[0] = gglMulAddx(m0[0], m1[0], a[0]);
    d[1] = gglMulAddx(m0[1], m1[1], a[1]);
    d[2] = gglMulAddx(m0[2], m1[2], a[2]);
}

static inline
void vmul3(GLfixed* d, const GLfixed* m0, const GLfixed* m1) {
    d[0] = gglMulx(m0[0], m1[0]);
    d[1] = gglMulx(m0[1], m1[1]);
    d[2] = gglMulx(m0[2], m1[2]);
}

void vnorm3(GLfixed* d, const GLfixed* a)
{
    // we must take care of overflows when normalizing a vector
    GLfixed n;
    int32_t x = a[0];   x = x>=0 ? x : -x;
    int32_t y = a[1];   y = y>=0 ? y : -y;
    int32_t z = a[2];   z = z>=0 ? z : -z;
    if (ggl_likely(x<=0x6800 && y<=0x6800 && z<= 0x6800)) {
        // in this case this will all fit on 32 bits
        n = x*x + y*y + z*z;
        n = gglSqrtRecipx(n);
        n <<= 8;
    } else {
        // here norm^2 is at least 0x7EC00000 (>>32 == 0.495117)
        n = vsquare3(x, y, z);
        n = gglSqrtRecipx(n);
    }
    vscale3(d, a, n);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark lighting equations
#endif

static inline void light_picker(ogles_context_t* c)
{
    if (ggl_likely(!c->lighting.enable)) {
        c->lighting.lightVertex = lightVertexNop;
        return;
    }
    if (c->lighting.colorMaterial.enable) {
        c->lighting.lightVertex = lightVertexMaterial;
    } else {
        c->lighting.lightVertex = lightVertex;
    }
}

static inline void validate_light_mvi(ogles_context_t* c)
{
    uint32_t en = c->lighting.enabledLights;
    // Vector from object to viewer, in eye coordinates
    while (en) {
        const int i = 31 - gglClz(en);
        en &= ~(1<<i);
        light_t& l = c->lighting.lights[i];
#if OBJECT_SPACE_LIGHTING
        c->transforms.mvui.point4(&c->transforms.mvui,
                &l.objPosition, &l.position);
#else
        l.objPosition = l.position;
#endif
        vnorm3(l.normalizedObjPosition.v, l.objPosition.v);
    }
    const vec4_t eyeViewer = { 0, 0, 0x10000, 0 };
#if OBJECT_SPACE_LIGHTING
    c->transforms.mvui.point3(&c->transforms.mvui,
            &c->lighting.objViewer, &eyeViewer);
    vnorm3(c->lighting.objViewer.v, c->lighting.objViewer.v);
#else
    c->lighting.objViewer = eyeViewer;
#endif
}

static inline void validate_light(ogles_context_t* c)
{
    // if colorMaterial is enabled, we get the color from the vertex
    if (!c->lighting.colorMaterial.enable) {
        material_t& material = c->lighting.front;
        uint32_t en = c->lighting.enabledLights;
        while (en) {
            const int i = 31 - gglClz(en);
            en &= ~(1<<i);
            light_t& l = c->lighting.lights[i];
            vmul3(l.implicitAmbient.v,  material.ambient.v,  l.ambient.v);
            vmul3(l.implicitDiffuse.v,  material.diffuse.v,  l.diffuse.v);
            vmul3(l.implicitSpecular.v, material.specular.v, l.specular.v);
            
            // this is just a flag to tell if we have a specular component
            l.implicitSpecular.v[3] =
                    l.implicitSpecular.r |
                    l.implicitSpecular.g |
                    l.implicitSpecular.b;
            
            l.rConstAttenuation = (l.attenuation[1] | l.attenuation[2])==0;
            if (l.rConstAttenuation)
                l.rConstAttenuation = gglRecipFast(l.attenuation[0]);
        }
        // emission and ambient for the whole scene
        vmla3(  c->lighting.implicitSceneEmissionAndAmbient.v,
                c->lighting.lightModel.ambient.v,
                material.ambient.v, 
                material.emission.v);
        c->lighting.implicitSceneEmissionAndAmbient.a = material.diffuse.a;
    }
    validate_light_mvi(c);
}

void invalidate_lighting(ogles_context_t* c)
{
    // TODO: pick lightVertexValidate or lightVertexValidateMVI
    // instead of systematically the heavier lightVertexValidate()
    c->lighting.lightVertex = lightVertexValidate;
}

void ogles_invalidate_lighting_mvui(ogles_context_t* c)
{
    invalidate_lighting(c);
}

void lightVertexNop(ogles_context_t*, vertex_t* v)
{
    // we should never end-up here
}

void lightVertexValidateMVI(ogles_context_t* c, vertex_t* v)
{
    validate_light_mvi(c);
    light_picker(c);
    c->lighting.lightVertex(c, v);
}

void lightVertexValidate(ogles_context_t* c, vertex_t* v)
{
    validate_light(c);
    light_picker(c);
    c->lighting.lightVertex(c, v);
}

void lightVertexMaterial(ogles_context_t* c, vertex_t* v)
{
    // fetch the material color
    const GLvoid* cp = c->arrays.color.element(
            v->index & vertex_cache_t::INDEX_MASK);
    c->arrays.color.fetch(c, v->color.v, cp);

    // acquire the color-material from the vertex
    material_t& material = c->lighting.front;
    material.ambient =
    material.diffuse = v->color;
    // implicit arguments need to be computed per/vertex
    uint32_t en = c->lighting.enabledLights;
    while (en) {
        const int i = 31 - gglClz(en);
        en &= ~(1<<i);
        light_t& l = c->lighting.lights[i];
        vmul3(l.implicitAmbient.v,  material.ambient.v,  l.ambient.v);
        vmul3(l.implicitDiffuse.v,  material.diffuse.v,  l.diffuse.v);
        vmul3(l.implicitSpecular.v, material.specular.v, l.specular.v);
        // this is just a flag to tell if we have a specular component
        l.implicitSpecular.v[3] =
                l.implicitSpecular.r |
                l.implicitSpecular.g |
                l.implicitSpecular.b;
    }
    // emission and ambient for the whole scene
    vmla3(  c->lighting.implicitSceneEmissionAndAmbient.v,
            c->lighting.lightModel.ambient.v,
            material.ambient.v, 
            material.emission.v);
    c->lighting.implicitSceneEmissionAndAmbient.a = material.diffuse.a;

    // now we can light our vertex as usual
    lightVertex(c, v);
}

void lightVertex(ogles_context_t* c, vertex_t* v)
{
    // emission and ambient for the whole scene
    vec4_t r = c->lighting.implicitSceneEmissionAndAmbient;
    const vec4_t objViewer = c->lighting.objViewer;

    uint32_t en = c->lighting.enabledLights;
    if (ggl_likely(en)) {
        // since we do the lighting in object-space, we don't need to
        // transform each normal. However, we might still have to normalize
        // it if GL_NORMALIZE is enabled.
        vec4_t n;
        c->arrays.normal.fetch(c, n.v,
            c->arrays.normal.element(v->index & vertex_cache_t::INDEX_MASK));

#if !OBJECT_SPACE_LIGHTING
        c->transforms.mvui.point3(&c->transforms.mvui, &n, &n);
#endif

        // TODO: right now we handle GL_RESCALE_NORMALS as if it were
        // GL_NORMALIZE. We could optimize this by  scaling mvui 
        // appropriately instead.
        if (c->transforms.rescaleNormals)
            vnorm3(n.v, n.v);

        const material_t& material = c->lighting.front;
        const int twoSide = c->lighting.lightModel.twoSide;

        while (en) {
            const int i = 31 - gglClz(en);
            en &= ~(1<<i);
            const light_t& l = c->lighting.lights[i];
            
            vec4_t d, t;
            GLfixed s;
            GLfixed sqDist = 0x10000;

            // compute vertex-to-light vector
            if (ggl_unlikely(l.position.w)) {
                // lightPos/1.0 - vertex/vertex.w == lightPos*vertex.w - vertex
                vss3(d.v, l.objPosition.v, v->obj.w, v->obj.v);
                sqDist = dot3(d.v, d.v);
                vscale3(d.v, d.v, gglSqrtRecipx(sqDist));
            } else {
                // TODO: avoid copy here
                d = l.normalizedObjPosition;
            }

            // ambient & diffuse
            s = dot3(n.v, d.v);
            s = (s<0) ? (twoSide?(-s):0) : s;
            vsa3(t.v, l.implicitDiffuse.v, s, l.implicitAmbient.v);

            // specular
            if (ggl_unlikely(s && l.implicitSpecular.v[3])) {
                vec4_t h;
                h.x = d.x + objViewer.x;
                h.y = d.y + objViewer.y;
                h.z = d.z + objViewer.z;
                vnorm3(h.v, h.v);
                s = dot3(n.v, h.v);
                s = (s<0) ? (twoSide?(-s):0) : s;
                if (s > 0) {
                    s = gglPowx(s, material.shininess);
                    vsa3(t.v, l.implicitSpecular.v, s, t.v);
                }
            }

            // spot
            if (ggl_unlikely(l.spotCutoff != gglIntToFixed(180))) {
                GLfixed spotAtt = -dot3(l.normalizedSpotDir.v, d.v);
                if (spotAtt >= l.spotCutoffCosine) {
                    vscale3(t.v, t.v, gglPowx(spotAtt, l.spotExp));
                }
            }

            // attenuation
            if (ggl_unlikely(l.position.w)) {
                if (l.rConstAttenuation) {
                    s = l.rConstAttenuation;
                } else {
                    s = gglMulAddx(sqDist, l.attenuation[2], l.attenuation[0]);
                    if (l.attenuation[1])
                        s = gglMulAddx(gglSqrtx(sqDist), l.attenuation[1], s);
                    s = gglRecipFast(s);
                }
                vscale3(t.v, t.v, s);
            }

            r.r += t.r;
            r.g += t.g;
            r.b += t.b;
        }
    }
    v->color.r = gglClampx(r.r);
    v->color.g = gglClampx(r.g);
    v->color.b = gglClampx(r.b);
    v->color.a = gglClampx(r.a);
    v->flags |= vertex_t::LIT;
}

static void lightModelx(GLenum pname, GLfixed param, ogles_context_t* c)
{
    if (ggl_unlikely(pname != GL_LIGHT_MODEL_TWO_SIDE)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->lighting.lightModel.twoSide = param ? GL_TRUE : GL_FALSE;
    invalidate_lighting(c);
}

static void lightx(GLenum i, GLenum pname, GLfixed param, ogles_context_t* c)
{
    if (ggl_unlikely(uint32_t(i-GL_LIGHT0) >= OGLES_MAX_LIGHTS)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }

    light_t& light = c->lighting.lights[i-GL_LIGHT0];
    const GLfixed kDegToRad = GLfixed((M_PI * gglIntToFixed(1)) / 180.0f);
    switch (pname) {
    case GL_SPOT_EXPONENT:
        if (GGLfixed(param) >= gglIntToFixed(128)) {
            ogles_error(c, GL_INVALID_VALUE);
            return;
        }
        light.spotExp = param;
        break;
    case GL_SPOT_CUTOFF:
        if (param!=gglIntToFixed(180) && GGLfixed(param)>=gglIntToFixed(90)) {
            ogles_error(c, GL_INVALID_VALUE);
            return;
        }
        light.spotCutoff = param;
        light.spotCutoffCosine = 
                gglFloatToFixed(cosinef((M_PI/(180.0f*65536.0f))*param));
        break;
    case GL_CONSTANT_ATTENUATION:
        if (param < 0) {
            ogles_error(c, GL_INVALID_VALUE);
            return;
        }
        light.attenuation[0] = param;
        break;
    case GL_LINEAR_ATTENUATION:
        if (param < 0) {
            ogles_error(c, GL_INVALID_VALUE);
            return;
        }
        light.attenuation[1] = param;
        break;
    case GL_QUADRATIC_ATTENUATION:
        if (param < 0) {
            ogles_error(c, GL_INVALID_VALUE);
            return;
        }
        light.attenuation[2] = param;
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    invalidate_lighting(c);
}

static void lightxv(GLenum i, GLenum pname, const GLfixed *params, ogles_context_t* c)
{
    if (ggl_unlikely(uint32_t(i-GL_LIGHT0) >= OGLES_MAX_LIGHTS)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }

    GLfixed* what;
    light_t& light = c->lighting.lights[i-GL_LIGHT0];
    switch (pname) {
    case GL_AMBIENT:
        what = light.ambient.v;
        break;
    case GL_DIFFUSE:
        what = light.diffuse.v;
        break;
    case GL_SPECULAR:
        what = light.specular.v;
        break;
    case GL_POSITION: {
        ogles_validate_transform(c, transform_state_t::MODELVIEW);
        transform_t& mv = c->transforms.modelview.transform;
        mv.point4(&mv, &light.position, reinterpret_cast<vec4_t const*>(params));
        invalidate_lighting(c);
        return;
    }
    case GL_SPOT_DIRECTION: {
#if OBJECT_SPACE_LIGHTING
        ogles_validate_transform(c, transform_state_t::MVUI);
        transform_t& mvui = c->transforms.mvui;
        mvui.point3(&mvui, &light.spotDir, reinterpret_cast<vec4_t const*>(params));
#else
        light.spotDir = *reinterpret_cast<vec4_t const*>(params);
#endif
        vnorm3(light.normalizedSpotDir.v, light.spotDir.v);
        invalidate_lighting(c);
        return;
    }
    default:
        lightx(i, pname, params[0], c);
        return;
    }
    what[0] = params[0];
    what[1] = params[1];
    what[2] = params[2];
    what[3] = params[3];
    invalidate_lighting(c);
}

static void materialx(GLenum face, GLenum pname, GLfixed param, ogles_context_t* c)
{
    if (ggl_unlikely(face != GL_FRONT_AND_BACK)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if (ggl_unlikely(pname != GL_SHININESS)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->lighting.front.shininess = param;
    invalidate_lighting(c);
}

static void fogx(GLenum pname, GLfixed param, ogles_context_t* c)
{
    switch (pname) {
    case GL_FOG_DENSITY:
        if (param >= 0) {
            c->fog.density = param;
            break;
        }
        ogles_error(c, GL_INVALID_VALUE);
        break;
    case GL_FOG_START:
        c->fog.start = param;
        c->fog.invEndMinusStart = gglRecip(c->fog.end - c->fog.start);
        break;
    case GL_FOG_END:
        c->fog.end = param;
        c->fog.invEndMinusStart = gglRecip(c->fog.end - c->fog.start);
        break;
    case GL_FOG_MODE:
        switch (param) {
        case GL_LINEAR:
            c->fog.mode = param;
            c->fog.fog = fog_linear;
            break;
        case GL_EXP:
            c->fog.mode = param;
            c->fog.fog = fog_exp;
            break;
        case GL_EXP2:
            c->fog.mode = param;
            c->fog.fog = fog_exp2;
            break;
        default:
            ogles_error(c, GL_INVALID_ENUM);
            break;
        }
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        break;
    }
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

using namespace android;

#if 0
#pragma mark -
#pragma mark lighting APIs
#endif

void glShadeModel(GLenum mode)
{
    ogles_context_t* c = ogles_context_t::get();
    if (ggl_unlikely(mode != GL_SMOOTH && mode != GL_FLAT)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->lighting.shadeModel = mode;
}

void glLightModelf(GLenum pname, GLfloat param)
{
    ogles_context_t* c = ogles_context_t::get();
    lightModelx(pname, gglFloatToFixed(param), c);
}

void glLightModelx(GLenum pname, GLfixed param)
{
    ogles_context_t* c = ogles_context_t::get();
    lightModelx(pname, param, c);
}

void glLightModelfv(GLenum pname, const GLfloat *params)
{
    ogles_context_t* c = ogles_context_t::get();
    if (pname == GL_LIGHT_MODEL_TWO_SIDE) {
        lightModelx(pname, gglFloatToFixed(params[0]), c);
        return;
    }

    if (ggl_unlikely(pname != GL_LIGHT_MODEL_AMBIENT)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }

    c->lighting.lightModel.ambient.r = gglFloatToFixed(params[0]);
    c->lighting.lightModel.ambient.g = gglFloatToFixed(params[1]);
    c->lighting.lightModel.ambient.b = gglFloatToFixed(params[2]);
    c->lighting.lightModel.ambient.a = gglFloatToFixed(params[3]);
    invalidate_lighting(c);
}

void glLightModelxv(GLenum pname, const GLfixed *params)
{
    ogles_context_t* c = ogles_context_t::get();
    if (pname == GL_LIGHT_MODEL_TWO_SIDE) {
        lightModelx(pname, params[0], c);
        return;
    }

    if (ggl_unlikely(pname != GL_LIGHT_MODEL_AMBIENT)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }

    c->lighting.lightModel.ambient.r = params[0];
    c->lighting.lightModel.ambient.g = params[1];
    c->lighting.lightModel.ambient.b = params[2];
    c->lighting.lightModel.ambient.a = params[3];
    invalidate_lighting(c);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

void glLightf(GLenum i, GLenum pname, GLfloat param)
{
    ogles_context_t* c = ogles_context_t::get();
    lightx(i, pname, gglFloatToFixed(param), c);
}

void glLightx(GLenum i, GLenum pname, GLfixed param)
{
    ogles_context_t* c = ogles_context_t::get();
    lightx(i, pname, param, c);
}

void glLightfv(GLenum i, GLenum pname, const GLfloat *params)
{
    ogles_context_t* c = ogles_context_t::get();
    switch (pname) {
    case GL_SPOT_EXPONENT:
    case GL_SPOT_CUTOFF:
    case GL_CONSTANT_ATTENUATION:
    case GL_LINEAR_ATTENUATION:
    case GL_QUADRATIC_ATTENUATION:
        lightx(i, pname, gglFloatToFixed(params[0]), c);
        return;
    }

    GLfixed paramsx[4];
    paramsx[0] = gglFloatToFixed(params[0]);
    paramsx[1] = gglFloatToFixed(params[1]);
    paramsx[2] = gglFloatToFixed(params[2]);
    if (pname != GL_SPOT_DIRECTION)
        paramsx[3] = gglFloatToFixed(params[3]);

    lightxv(i, pname, paramsx, c);
}

void glLightxv(GLenum i, GLenum pname, const GLfixed *params)
{
    ogles_context_t* c = ogles_context_t::get();
    lightxv(i, pname, params, c);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

void glMaterialf(GLenum face, GLenum pname, GLfloat param)
{
    ogles_context_t* c = ogles_context_t::get();
    materialx(face, pname, gglFloatToFixed(param), c);
}

void glMaterialx(GLenum face, GLenum pname, GLfixed param)
{
    ogles_context_t* c = ogles_context_t::get();
    materialx(face, pname, param, c);
}

void glMaterialfv(
    GLenum face, GLenum pname, const GLfloat *params)
{
    ogles_context_t* c = ogles_context_t::get();
    if (ggl_unlikely(face != GL_FRONT_AND_BACK)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    GLfixed* what=0;
    GLfixed* other=0;
    switch (pname) {
    case GL_AMBIENT:    what = c->lighting.front.ambient.v; break;
    case GL_DIFFUSE:    what = c->lighting.front.diffuse.v; break;
    case GL_SPECULAR:   what = c->lighting.front.specular.v; break;
    case GL_EMISSION:   what = c->lighting.front.emission.v; break;
    case GL_AMBIENT_AND_DIFFUSE:
        what  = c->lighting.front.ambient.v;
        other = c->lighting.front.diffuse.v;
        break;
    case GL_SHININESS:
        c->lighting.front.shininess = gglFloatToFixed(params[0]);
        invalidate_lighting(c);
        return;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    what[0] = gglFloatToFixed(params[0]);
    what[1] = gglFloatToFixed(params[1]);
    what[2] = gglFloatToFixed(params[2]);
    what[3] = gglFloatToFixed(params[3]);
    if (other) {
        other[0] = what[0];
        other[1] = what[1];
        other[2] = what[2];
        other[3] = what[3];
    }
    invalidate_lighting(c);
}

void glMaterialxv(
    GLenum face, GLenum pname, const GLfixed *params)
{
    ogles_context_t* c = ogles_context_t::get();
    if (ggl_unlikely(face != GL_FRONT_AND_BACK)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    GLfixed* what=0;
    GLfixed* other=0;
    switch (pname) {
    case GL_AMBIENT:    what = c->lighting.front.ambient.v; break;
    case GL_DIFFUSE:    what = c->lighting.front.diffuse.v; break;
    case GL_SPECULAR:   what = c->lighting.front.specular.v; break;
    case GL_EMISSION:   what = c->lighting.front.emission.v; break;
    case GL_AMBIENT_AND_DIFFUSE:
        what  = c->lighting.front.ambient.v;
        other = c->lighting.front.diffuse.v;
        break;
    case GL_SHININESS:
        c->lighting.front.shininess = gglFloatToFixed(params[0]);
        invalidate_lighting(c);
        return;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    what[0] = params[0];
    what[1] = params[1];
    what[2] = params[2];
    what[3] = params[3];
    if (other) {
        other[0] = what[0];
        other[1] = what[1];
        other[2] = what[2];
        other[3] = what[3];
    }
    invalidate_lighting(c);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark fog
#endif

void glFogf(GLenum pname, GLfloat param) {
    ogles_context_t* c = ogles_context_t::get();
    GLfixed paramx = (GLfixed)param;
    if (pname != GL_FOG_MODE)
        paramx = gglFloatToFixed(param);
    fogx(pname, paramx, c);
}

void glFogx(GLenum pname, GLfixed param) {
    ogles_context_t* c = ogles_context_t::get();
    fogx(pname, param, c);
}

void glFogfv(GLenum pname, const GLfloat *params)
{
    ogles_context_t* c = ogles_context_t::get();
    if (pname != GL_FOG_COLOR) {
        GLfixed paramx = (GLfixed)params[0];
        if (pname != GL_FOG_MODE)
            paramx = gglFloatToFixed(params[0]);
        fogx(pname, paramx, c);
        return;
    }
    GLfixed paramsx[4];
    paramsx[0] = gglFloatToFixed(params[0]);
    paramsx[1] = gglFloatToFixed(params[1]);
    paramsx[2] = gglFloatToFixed(params[2]);
    paramsx[3] = gglFloatToFixed(params[3]);
    c->rasterizer.procs.fogColor3xv(c, paramsx);
}

void glFogxv(GLenum pname, const GLfixed *params)
{
    ogles_context_t* c = ogles_context_t::get();
    if (pname != GL_FOG_COLOR) {
        fogx(pname, params[0], c);
        return;
    }
    c->rasterizer.procs.fogColor3xv(c, params);
}

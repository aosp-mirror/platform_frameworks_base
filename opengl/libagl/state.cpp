/* libs/opengles/state.cpp
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

#include "context.h"
#include "fp.h"
#include "state.h"
#include "array.h"
#include "matrix.h"
#include "vertex.h"
#include "light.h"
#include "texture.h"
#include "BufferObjectManager.h"
#include "TextureObjectManager.h"

namespace android {

// ----------------------------------------------------------------------------

static char const * const gVendorString     = "Android";
static char const * const gRendererString   = "Android PixelFlinger 1.4";
static char const * const gVersionString    = "OpenGL ES-CM 1.0";
static char const * const gExtensionsString =
    "GL_OES_byte_coordinates "              // OK
    "GL_OES_fixed_point "                   // OK
    "GL_OES_single_precision "              // OK
    "GL_OES_read_format "                   // OK
    "GL_OES_compressed_paletted_texture "   // OK
    "GL_OES_draw_texture "                  // OK
    "GL_OES_matrix_get "                    // OK
    "GL_OES_query_matrix "                  // OK
    //        "GL_OES_point_size_array "              // TODO
    //        "GL_OES_point_sprite "                  // TODO
    "GL_OES_EGL_image "                     // OK
#ifdef GL_OES_compressed_ETC1_RGB8_texture
    "GL_OES_compressed_ETC1_RGB8_texture "  // OK
#endif
    "GL_ARB_texture_compression "           // OK
    "GL_ARB_texture_non_power_of_two "      // OK
    "GL_ANDROID_user_clip_plane "           // OK
    "GL_ANDROID_vertex_buffer_object "      // OK
    "GL_ANDROID_generate_mipmap "           // OK
    ;

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

ogles_context_t *ogles_init(size_t extra)
{
    void* const base = malloc(extra + sizeof(ogles_context_t) + 32);
    if (!base) return 0;

    ogles_context_t *c =
            (ogles_context_t *)((ptrdiff_t(base) + extra + 31) & ~0x1FL);
    memset(c, 0, sizeof(ogles_context_t));
    ggl_init_context(&(c->rasterizer));

    // XXX: this should be passed as an argument
    sp<EGLSurfaceManager> smgr(new EGLSurfaceManager());
    c->surfaceManager = smgr.get();
    c->surfaceManager->incStrong(c);

    sp<EGLBufferObjectManager> bomgr(new EGLBufferObjectManager());
    c->bufferObjectManager = bomgr.get();
    c->bufferObjectManager->incStrong(c);

    ogles_init_array(c);
    ogles_init_matrix(c);
    ogles_init_vertex(c);
    ogles_init_light(c);
    ogles_init_texture(c);

    c->rasterizer.base = base;
    c->point.size = TRI_ONE;
    c->line.width = TRI_ONE;

    // in OpenGL, writing to the depth buffer is enabled by default.
    c->rasterizer.procs.depthMask(c, 1);

    // OpenGL enables dithering by default
    c->rasterizer.procs.enable(c, GL_DITHER);

    return c;
}

void ogles_uninit(ogles_context_t* c)
{
    ogles_uninit_array(c);
    ogles_uninit_matrix(c);
    ogles_uninit_vertex(c);
    ogles_uninit_light(c);
    ogles_uninit_texture(c);
    c->surfaceManager->decStrong(c);
    c->bufferObjectManager->decStrong(c);
    ggl_uninit_context(&(c->rasterizer));
    free(c->rasterizer.base);
}

void _ogles_error(ogles_context_t* c, GLenum error)
{
    if (c->error == GL_NO_ERROR)
        c->error = error;
}

static bool stencilop_valid(GLenum op) {
    switch (op) {
    case GL_KEEP:
    case GL_ZERO:
    case GL_REPLACE:
    case GL_INCR:
    case GL_DECR:
    case GL_INVERT:
        return true;
    }
    return false;
}

static void enable_disable(ogles_context_t* c, GLenum cap, int enabled)
{
    if ((cap >= GL_LIGHT0) && (cap<GL_LIGHT0+OGLES_MAX_LIGHTS)) {
        c->lighting.lights[cap-GL_LIGHT0].enable = enabled;
        c->lighting.enabledLights &= ~(1<<(cap-GL_LIGHT0));
        c->lighting.enabledLights |= (enabled<<(cap-GL_LIGHT0));
        return;
    }

    switch (cap) {
    case GL_POINT_SMOOTH:
        c->point.smooth = enabled;
        break;
    case GL_LINE_SMOOTH:
        c->line.smooth = enabled;
        break;
    case GL_POLYGON_OFFSET_FILL:
        c->polygonOffset.enable = enabled;
        break;
    case GL_CULL_FACE:
        c->cull.enable = enabled;
        break;
    case GL_LIGHTING:
        c->lighting.enable = enabled;
        break;
    case GL_COLOR_MATERIAL:
        c->lighting.colorMaterial.enable = enabled;
        break;
    case GL_NORMALIZE:
    case GL_RESCALE_NORMAL:
        c->transforms.rescaleNormals = enabled ? cap : 0;
        // XXX: invalidate mvit
        break;

    case GL_CLIP_PLANE0:
    case GL_CLIP_PLANE1:
    case GL_CLIP_PLANE2:
    case GL_CLIP_PLANE3:
    case GL_CLIP_PLANE4:
    case GL_CLIP_PLANE5:
        c->clipPlanes.enable &= ~(1<<(cap-GL_CLIP_PLANE0));
        c->clipPlanes.enable |= (enabled<<(cap-GL_CLIP_PLANE0));
        ogles_invalidate_perspective(c);
        break;

    case GL_FOG:
    case GL_DEPTH_TEST:
        ogles_invalidate_perspective(c);
        // fall-through...
    case GL_BLEND:
    case GL_SCISSOR_TEST:
    case GL_ALPHA_TEST:
    case GL_COLOR_LOGIC_OP:
    case GL_DITHER:
    case GL_STENCIL_TEST:
    case GL_TEXTURE_2D:
        // these need to fall through into the rasterizer
        c->rasterizer.procs.enableDisable(c, cap, enabled);
        break;
    case GL_TEXTURE_EXTERNAL_OES:
        c->rasterizer.procs.enableDisable(c, GL_TEXTURE_2D, enabled);
        break;

    case GL_MULTISAMPLE:
    case GL_SAMPLE_ALPHA_TO_COVERAGE:
    case GL_SAMPLE_ALPHA_TO_ONE:
    case GL_SAMPLE_COVERAGE:
        // not supported in this implementation
        break;

    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------
using namespace android;

#if 0
#pragma mark -
#endif

// These ones are super-easy, we're not supporting those features!
void glSampleCoverage(GLclampf value, GLboolean invert) {
}
void glSampleCoveragex(GLclampx value, GLboolean invert) {
}
void glStencilFunc(GLenum func, GLint ref, GLuint mask) {
    ogles_context_t* c = ogles_context_t::get();
    if (func < GL_NEVER || func > GL_ALWAYS) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    // from OpenGL|ES 1.0 sepcification:
    // If there is no stencil buffer, no stencil modification can occur
    // and it is as if the stencil test always passes.
}

void glStencilOp(GLenum fail, GLenum zfail, GLenum zpass) {
    ogles_context_t* c = ogles_context_t::get();
    if ((stencilop_valid(fail) &
         stencilop_valid(zfail) &
         stencilop_valid(zpass)) == 0) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
}

// ----------------------------------------------------------------------------

void glAlphaFunc(GLenum func, GLclampf ref)
{
    glAlphaFuncx(func, gglFloatToFixed(ref));
}

void glCullFace(GLenum mode)
{
    ogles_context_t* c = ogles_context_t::get();
    switch (mode) {
    case GL_FRONT:
    case GL_BACK:
    case GL_FRONT_AND_BACK:
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
    }
    c->cull.cullFace = mode;
}

void glFrontFace(GLenum mode)
{
    ogles_context_t* c = ogles_context_t::get();
    switch (mode) {
    case GL_CW:
    case GL_CCW:
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->cull.frontFace = mode;
}

void glHint(GLenum target, GLenum mode)
{
    ogles_context_t* c = ogles_context_t::get();
    switch (target) {
    case GL_FOG_HINT:
    case GL_GENERATE_MIPMAP_HINT:
    case GL_LINE_SMOOTH_HINT:
        break;
    case GL_POINT_SMOOTH_HINT:
        c->rasterizer.procs.enableDisable(c,
                GGL_POINT_SMOOTH_NICE, mode==GL_NICEST);
        break;
    case GL_PERSPECTIVE_CORRECTION_HINT:
        c->perspective = (mode == GL_NICEST) ? 1 : 0;
        break;
    default:
        ogles_error(c, GL_INVALID_ENUM);
    }
}

void glEnable(GLenum cap) {
    ogles_context_t* c = ogles_context_t::get();
    enable_disable(c, cap, 1);
}
void glDisable(GLenum cap) {
    ogles_context_t* c = ogles_context_t::get();
    enable_disable(c, cap, 0);
}

void glFinish()
{ // nothing to do for our software implementation
}

void glFlush()
{ // nothing to do for our software implementation
}

GLenum glGetError()
{
    // From OpenGL|ES 1.0 specification:
    // If more than one flag has recorded an error, glGetError returns
    // and clears an arbitrary error flag value. Thus, glGetError should
    // always be called in a loop, until it returns GL_NO_ERROR,
    // if all error flags are to be reset.

    ogles_context_t* c = ogles_context_t::get();
    if (c->error) {
        const GLenum ret(c->error);
        c->error = 0;
        return ret;
    }

    if (c->rasterizer.error) {
        const GLenum ret(c->rasterizer.error);
        c->rasterizer.error = 0;
        return ret;
    }

    return GL_NO_ERROR;
}

const GLubyte* glGetString(GLenum string)
{
    switch (string) {
    case GL_VENDOR:     return (const GLubyte*)gVendorString;
    case GL_RENDERER:   return (const GLubyte*)gRendererString;
    case GL_VERSION:    return (const GLubyte*)gVersionString;
    case GL_EXTENSIONS: return (const GLubyte*)gExtensionsString;
    }
    ogles_context_t* c = ogles_context_t::get();
    ogles_error(c, GL_INVALID_ENUM);
    return 0;
}

void glGetIntegerv(GLenum pname, GLint *params)
{
    int i;
    ogles_context_t* c = ogles_context_t::get();
    switch (pname) {
    case GL_ALIASED_POINT_SIZE_RANGE:
        params[0] = 0;
        params[1] = GGL_MAX_ALIASED_POINT_SIZE;
        break;
    case GL_ALIASED_LINE_WIDTH_RANGE:
        params[0] = 0;
        params[1] = GGL_MAX_ALIASED_POINT_SIZE;
        break;
    case GL_ALPHA_BITS: {
        int index = c->rasterizer.state.buffers.color.format;
        GGLFormat const * formats = gglGetPixelFormatTable();
        params[0] = formats[index].ah - formats[index].al;
        break;
        }
    case GL_RED_BITS: {
        int index = c->rasterizer.state.buffers.color.format;
        GGLFormat const * formats = gglGetPixelFormatTable();
        params[0] = formats[index].rh - formats[index].rl;
        break;
        }
    case GL_GREEN_BITS: {
        int index = c->rasterizer.state.buffers.color.format;
        GGLFormat const * formats = gglGetPixelFormatTable();
        params[0] = formats[index].gh - formats[index].gl;
        break;
        }
    case GL_BLUE_BITS: {
        int index = c->rasterizer.state.buffers.color.format;
        GGLFormat const * formats = gglGetPixelFormatTable();
        params[0] = formats[index].bh - formats[index].bl;
        break;
        }
    case GL_COMPRESSED_TEXTURE_FORMATS:
        params[ 0] = GL_PALETTE4_RGB8_OES;
        params[ 1] = GL_PALETTE4_RGBA8_OES;
        params[ 2] = GL_PALETTE4_R5_G6_B5_OES;
        params[ 3] = GL_PALETTE4_RGBA4_OES;
        params[ 4] = GL_PALETTE4_RGB5_A1_OES;
        params[ 5] = GL_PALETTE8_RGB8_OES;
        params[ 6] = GL_PALETTE8_RGBA8_OES;
        params[ 7] = GL_PALETTE8_R5_G6_B5_OES;
        params[ 8] = GL_PALETTE8_RGBA4_OES;
        params[ 9] = GL_PALETTE8_RGB5_A1_OES;
        i = 10;
#ifdef GL_OES_compressed_ETC1_RGB8_texture
        params[i++] = GL_ETC1_RGB8_OES;
#endif
        break;
    case GL_DEPTH_BITS:
        params[0] = c->rasterizer.state.buffers.depth.format ? 0 : 16;
        break;
    case GL_IMPLEMENTATION_COLOR_READ_FORMAT_OES:
        params[0] = GL_RGB;
        break;
    case GL_IMPLEMENTATION_COLOR_READ_TYPE_OES:
        params[0] = GL_UNSIGNED_SHORT_5_6_5;
        break;
    case GL_MAX_LIGHTS:
        params[0] = OGLES_MAX_LIGHTS;
        break;
    case GL_MAX_CLIP_PLANES:
        params[0] = OGLES_MAX_CLIP_PLANES;
        break;
    case GL_MAX_MODELVIEW_STACK_DEPTH:
        params[0] = OGLES_MODELVIEW_STACK_DEPTH;
        break;
    case GL_MAX_PROJECTION_STACK_DEPTH:
        params[0] = OGLES_PROJECTION_STACK_DEPTH;
        break;
    case GL_MAX_TEXTURE_STACK_DEPTH:
        params[0] = OGLES_TEXTURE_STACK_DEPTH;
        break;
    case GL_MAX_TEXTURE_SIZE:
        params[0] = GGL_MAX_TEXTURE_SIZE;
        break;
    case GL_MAX_TEXTURE_UNITS:
        params[0] = GGL_TEXTURE_UNIT_COUNT;
        break;
    case GL_MAX_VIEWPORT_DIMS:
        params[0] = GGL_MAX_VIEWPORT_DIMS;
        params[1] = GGL_MAX_VIEWPORT_DIMS;
        break;
    case GL_NUM_COMPRESSED_TEXTURE_FORMATS:
        params[0] = OGLES_NUM_COMPRESSED_TEXTURE_FORMATS;
        break;
    case GL_SMOOTH_LINE_WIDTH_RANGE:
        params[0] = 0;
        params[1] = GGL_MAX_SMOOTH_LINE_WIDTH;
        break;
    case GL_SMOOTH_POINT_SIZE_RANGE:
        params[0] = 0;
        params[1] = GGL_MAX_SMOOTH_POINT_SIZE;
        break;
    case GL_STENCIL_BITS:
        params[0] = 0;
        break;
    case GL_SUBPIXEL_BITS:
        params[0] = GGL_SUBPIXEL_BITS;
        break;

    case GL_MODELVIEW_MATRIX_FLOAT_AS_INT_BITS_OES:
        memcpy( params,
                c->transforms.modelview.top().elements(),
                16*sizeof(GLint));
        break;
    case GL_PROJECTION_MATRIX_FLOAT_AS_INT_BITS_OES:
        memcpy( params,
                c->transforms.projection.top().elements(),
                16*sizeof(GLint));
        break;
    case GL_TEXTURE_MATRIX_FLOAT_AS_INT_BITS_OES:
        memcpy( params,
                c->transforms.texture[c->textures.active].top().elements(),
                16*sizeof(GLint));
        break;

    default:
        ogles_error(c, GL_INVALID_ENUM);
        break;
    }
}

// ----------------------------------------------------------------------------

void glPointSize(GLfloat size)
{
    ogles_context_t* c = ogles_context_t::get();
    if (size <= 0) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->point.size = TRI_FROM_FIXED(gglFloatToFixed(size));
}

void glPointSizex(GLfixed size)
{
    ogles_context_t* c = ogles_context_t::get();
    if (size <= 0) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->point.size = TRI_FROM_FIXED(size);
}

// ----------------------------------------------------------------------------

void glLineWidth(GLfloat width)
{
    ogles_context_t* c = ogles_context_t::get();
    if (width <= 0) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->line.width = TRI_FROM_FIXED(gglFloatToFixed(width));
}

void glLineWidthx(GLfixed width)
{
    ogles_context_t* c = ogles_context_t::get();
    if (width <= 0) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->line.width = TRI_FROM_FIXED(width);
}

// ----------------------------------------------------------------------------

void glColorMask(GLboolean r, GLboolean g, GLboolean b, GLboolean a) {
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.colorMask(c, r, g, b, a);
}

void glDepthMask(GLboolean flag) {
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.depthMask(c, flag);
}

void glStencilMask(GLuint mask) {
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.stencilMask(c, mask);
}

void glDepthFunc(GLenum func) {
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.depthFunc(c, func);
}

void glLogicOp(GLenum opcode) {
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.logicOp(c, opcode);
}

void glAlphaFuncx(GLenum func, GLclampx ref) {
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.alphaFuncx(c, func, ref);
}

void glBlendFunc(GLenum sfactor, GLenum dfactor) {
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.blendFunc(c, sfactor, dfactor);
}

void glClear(GLbitfield mask) {
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.clear(c, mask);
}

void glClearColorx(GLclampx red, GLclampx green, GLclampx blue, GLclampx alpha) {
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.clearColorx(c, red, green, blue, alpha);
}

void glClearColor(GLclampf r, GLclampf g, GLclampf b, GLclampf a)
{
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.clearColorx(c,
                    gglFloatToFixed(r),
                    gglFloatToFixed(g),
                    gglFloatToFixed(b),
                    gglFloatToFixed(a));
}

void glClearDepthx(GLclampx depth) {
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.clearDepthx(c, depth);
}

void glClearDepthf(GLclampf depth)
{
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.clearDepthx(c, gglFloatToFixed(depth));
}

void glClearStencil(GLint s) {
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.clearStencil(c, s);
}

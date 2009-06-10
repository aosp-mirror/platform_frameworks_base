/*
**
** Copyright 2009, The Android Open Source Project
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
#include "TextureObjectManager.h"

#include <hardware/gralloc.h>
#include <hardware/copybit.h>
#include <private/ui/android_natives_priv.h>
#include "gralloc_priv.h"

// ----------------------------------------------------------------------------

namespace android {

static void textureToCopyBitImage(
        const GGLSurface* surface, buffer_handle_t buffer, copybit_image_t* img) 
{
    // we know private_handle_t is good here
    private_handle_t* hnd = (private_handle_t*)buffer;
    img->w      = surface->stride;
    img->h      = surface->height;
    img->format = surface->format;
    img->offset = hnd->offset;
    img->base   = surface->data;
    img->fd     = hnd->fd;
}

struct clipRectRegion : public copybit_region_t {
    clipRectRegion(ogles_context_t* c) {
        next = iterate;
        int x = c->viewport.scissor.x;
        int y = c->viewport.scissor.y;
        r.l = x;
        r.t = y;
        r.r = x + c->viewport.scissor.w;
        r.b = y + c->viewport.scissor.h;
        firstTime = true;
    }
private:
    static int iterate(copybit_region_t const * self, copybit_rect_t* rect) {
        clipRectRegion* myself = (clipRectRegion*) self;
        if (myself->firstTime) {
            myself->firstTime = false;
            *rect = myself->r;
            return 1;
        }
        return 0;
    }
    mutable copybit_rect_t r;
    mutable bool firstTime;
};

static bool supportedCopybitsFormat(int format) {
    switch (format) {
    case COPYBIT_FORMAT_RGBA_8888:
    case COPYBIT_FORMAT_RGB_565:
    case COPYBIT_FORMAT_BGRA_8888:
    case COPYBIT_FORMAT_RGBA_5551:
    case COPYBIT_FORMAT_RGBA_4444:
    case COPYBIT_FORMAT_YCbCr_422_SP:
    case COPYBIT_FORMAT_YCbCr_420_SP:
        return true;
    default:
        return false;
    }
}

static bool hasAlpha(int format) {
    switch (format) {
    case COPYBIT_FORMAT_RGBA_8888:
    case COPYBIT_FORMAT_BGRA_8888:
    case COPYBIT_FORMAT_RGBA_5551:
    case COPYBIT_FORMAT_RGBA_4444:
        return true;
    default:
        return false;
    }
}

static inline int fixedToByte(GGLfixed val) {
    return (val - (val >> 8)) >> 8;
}

/**
 * Performs a quick check of the rendering state. If this function returns
 * false we cannot use the copybit driver.
 */

static bool checkContext(ogles_context_t* c) {

	// By convention copybitQuickCheckContext() has already returned true.
	// avoid checking the same information again.
	
    if (c->copybits.blitEngine == NULL
            || (c->rasterizer.state.enables
                    & (GGL_ENABLE_DEPTH_TEST|GGL_ENABLE_FOG)) != 0) {
        return false;
    }

    // Note: The drawSurfaceBuffer is only set for destination
    // surfaces types that are supported by the hardware and
    // do not have an alpha channel. So we don't have to re-check that here.

    static const int tmu = 0;
    texture_unit_t& u(c->textures.tmu[tmu]);
    EGLTextureObject* textureObject = u.texture;

    if (!supportedCopybitsFormat(textureObject->surface.format)) {
        return false;
    }
    return true;
}


static bool copybit(GLint x, GLint y,
        GLint w, GLint h,
        EGLTextureObject* textureObject,
        const GLint* crop_rect,
        int transform,
        ogles_context_t* c)
{
    // We assume checkContext has already been called and has already
    // returned true.

    const GGLSurface& cbSurface = c->rasterizer.state.buffers.color.s;

    y = cbSurface.height - (y + h);

    const GLint Ucr = crop_rect[0];
    const GLint Vcr = crop_rect[1];
    const GLint Wcr = crop_rect[2];
    const GLint Hcr = crop_rect[3];

    int32_t dsdx = (Wcr << 16) / w;   // dsdx =  ((Wcr/w)/Wt)*Wt
    int32_t dtdy = ((-Hcr) <<  16) / h;   // dtdy = -((Hcr/h)/Ht)*Ht

    if (dsdx < c->copybits.minScale || dsdx > c->copybits.maxScale
            || dtdy < c->copybits.minScale || dtdy > c->copybits.maxScale) {
        // The requested scale is out of the range the hardware
        // can support.
        return false;
    }

    int32_t texelArea = gglMulx(dtdy, dsdx);
    if (texelArea < FIXED_ONE && textureObject->mag_filter != GL_LINEAR) {
        // Non-linear filtering on a texture enlargement.
        return false;
    }

    if (texelArea > FIXED_ONE && textureObject->min_filter != GL_LINEAR) {
        // Non-linear filtering on an texture shrink.
        return false;
    }

    const uint32_t enables = c->rasterizer.state.enables;
    int planeAlpha = 255;
    static const int tmu = 0;
    texture_t& tev(c->rasterizer.state.texture[tmu]);
    bool srcTextureHasAlpha = hasAlpha(textureObject->surface.format);
    switch (tev.env) {

    case GGL_REPLACE:
        if (!srcTextureHasAlpha) {
            planeAlpha = fixedToByte(c->currentColorClamped.a);
        }
        break;

    case GGL_MODULATE:
        if (! (c->currentColorClamped.r == FIXED_ONE
                && c->currentColorClamped.g == FIXED_ONE
                && c->currentColorClamped.b == FIXED_ONE)) {
            return false;
        }
        planeAlpha = fixedToByte(c->currentColorClamped.a);
        break;

    default:
        // Incompatible texture environment.
        return false;
    }

    bool blending = false;

    if ((enables & GGL_ENABLE_BLENDING)
            && !(c->rasterizer.state.blend.src == GL_ONE
                    && c->rasterizer.state.blend.dst == GL_ZERO)) {
        // Blending is OK if it is
        // the exact kind of blending that the copybits hardware supports.
        // Note: The hardware only supports
        // GL_SRC_ALPHA / GL_ONE_MINUS_SRC_ALPHA,
        // But the surface flinger uses GL_ONE / GL_ONE_MINUS_SRC_ALPHA.
        // We substitute GL_SRC_ALPHA / GL_ONE_MINUS_SRC_ALPHA in that case,
        // because the performance is worth it, even if the results are
        // not correct.
        if (!((c->rasterizer.state.blend.src == GL_SRC_ALPHA
                || c->rasterizer.state.blend.src == GL_ONE)
                && c->rasterizer.state.blend.dst == GL_ONE_MINUS_SRC_ALPHA
                && c->rasterizer.state.blend.alpha_separate == 0)) {
            // Incompatible blend mode.
            return false;
        }
        blending = true;
    } else {
        // No blending is OK if we are not using alpha.
        if (srcTextureHasAlpha || planeAlpha != 255) {
            // Incompatible alpha
            return false;
        }
    }

    if (srcTextureHasAlpha && planeAlpha != 255) {
        // Can't do two types of alpha at once.
        return false;
    }

    // LOGW("calling copybits");

    copybit_device_t* copybit = c->copybits.blitEngine;

    copybit_image_t dst;
    buffer_handle_t target_hnd = c->copybits.drawSurfaceBuffer;
    textureToCopyBitImage(&cbSurface, target_hnd, &dst);
    copybit_rect_t drect = {x, y, x+w, y+h};

    // we know private_handle_t is good here
    copybit_image_t src;
    buffer_handle_t source_hnd = textureObject->buffer->handle;
    textureToCopyBitImage(&textureObject->surface, source_hnd, &src);
    copybit_rect_t srect = { Ucr, Vcr + Hcr, Ucr + Wcr, Vcr };

    copybit->set_parameter(copybit, COPYBIT_TRANSFORM, transform);
    copybit->set_parameter(copybit, COPYBIT_PLANE_ALPHA, planeAlpha);
    copybit->set_parameter(copybit, COPYBIT_DITHER,
            (enables & GGL_ENABLE_DITHER) ? COPYBIT_ENABLE : COPYBIT_DISABLE);

    clipRectRegion it(c);
    copybit->stretch(copybit, &dst, &src, &drect, &srect, &it);
    return true;
}

/*
 * Try to draw a triangle fan with copybit, return false if we fail.
 */
bool drawTrangleFanWithCopybit_impl(ogles_context_t* c, GLint first, GLsizei count) {
    if (! checkContext(c)) {
        return false;
    }

    c->arrays.compileElements(c, c->vc.vBuffer, 0, 4);
    // Is the result a screen aligned rectangle?
    int sx[4];
    int sy[4];
    for (int i = 0; i < 4; i++) {
        GLfixed x = c->vc.vBuffer[i].window.x;
        GLfixed y = c->vc.vBuffer[i].window.y;
        if (x < 0 || y < 0 || (x & 0xf) != 0 || (y & 0xf) != 0) {
            return false;
        }
        sx[i] = x >> 4;
        sy[i] = y >> 4;
    }

    /*
     * This is the pattern we're looking for:
     *    (2)--(3)
     *     |\   |
     *     | \  |
     *     |  \ |
     *     |   \|
     *    (1)--(0)
     *
     */
    int dx[4];
    int dy[4];
    for (int i = 0; i < 4; i++) {
        int i1 = (i + 1) & 3;
        dx[i] = sx[i] - sx[i1];
        dy[i] = sy[i] - sy[i1];
    }
    if (dx[1] | dx[3] | dy[0] | dy[2]) {
        return false;
    }
    if (dx[0] != -dx[2] || dy[1] != -dy[3]) {
        return false;
    }

    int x = sx[1];
    int y = sy[1];
    int w = dx[0];
    int h = dy[3];

    // We expect the texture coordinates to always be the unit square:

    static const GLfixed kExpectedUV[8] = {
        0, 0,
        0, FIXED_ONE,
        FIXED_ONE, FIXED_ONE,
        FIXED_ONE, 0
    };
    {
        const GLfixed* pExpected = &kExpectedUV[0];
        for (int i = 0; i < 4; i++) {
            GLfixed u = c->vc.vBuffer[i].texture[0].x;
            GLfixed v = c->vc.vBuffer[i].texture[0].y;
            if (u != *pExpected++ || v != *pExpected++) {
                return false;
            }
        }
    }

    static const int tmu = 0;
    texture_unit_t& u(c->textures.tmu[tmu]);
    EGLTextureObject* textureObject = u.texture;

    GLint tWidth = textureObject->surface.width;
    GLint tHeight = textureObject->surface.height;
    GLint crop_rect[4] = {0, tHeight, tWidth, -tHeight};

    const GGLSurface& cbSurface = c->rasterizer.state.buffers.color.s;
    y = cbSurface.height - (y + h);

    return copybit(x, y, w, h, textureObject, crop_rect,
            COPYBIT_TRANSFORM_ROT_90, c);
}

/*
 * Try to drawTexiOESWithCopybit, return false if we fail.
 */

bool drawTexiOESWithCopybit_impl(GLint x, GLint y, GLint z,
        GLint w, GLint h, ogles_context_t* c)
{
    // quickly process empty rects
    if ((w|h) <= 0) {
        return true;
    }

    if (! checkContext(c)) {
        return false;
    }

    static const int tmu = 0;
    texture_unit_t& u(c->textures.tmu[tmu]);
    EGLTextureObject* textureObject = u.texture;

    return copybit(x, y, w, h, textureObject, textureObject->crop_rect,
            0, c);
}

} // namespace android


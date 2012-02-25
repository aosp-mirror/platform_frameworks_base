/* libs/opengles/texture.cpp
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
#include "context.h"
#include "fp.h"
#include "state.h"
#include "texture.h"
#include "TextureObjectManager.h"

#include <ETC1/etc1.h>

namespace android {

// ----------------------------------------------------------------------------

static void bindTextureTmu(
    ogles_context_t* c, int tmu, GLuint texture, const sp<EGLTextureObject>& tex);

static __attribute__((noinline))
void generateMipmap(ogles_context_t* c, GLint level);

// ----------------------------------------------------------------------------

#if 0
#pragma mark -
#pragma mark Init
#endif

void ogles_init_texture(ogles_context_t* c)
{
    c->textures.packAlignment   = 4;
    c->textures.unpackAlignment = 4;

    // each context has a default named (0) texture (not shared)
    c->textures.defaultTexture = new EGLTextureObject();
    c->textures.defaultTexture->incStrong(c);

    // bind the default texture to each texture unit
    for (int i=0; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
        bindTextureTmu(c, i, 0, c->textures.defaultTexture);
        memset(c->current.texture[i].v, 0, sizeof(vec4_t));
        c->current.texture[i].Q = 0x10000;
    }
}

void ogles_uninit_texture(ogles_context_t* c)
{
    if (c->textures.ggl)
        gglUninit(c->textures.ggl);
    c->textures.defaultTexture->decStrong(c);
    for (int i=0; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
        if (c->textures.tmu[i].texture)
            c->textures.tmu[i].texture->decStrong(c);
    }
}

static __attribute__((noinline))
void validate_tmu(ogles_context_t* c, int i)
{
    texture_unit_t& u(c->textures.tmu[i]);
    if (u.dirty) {
        u.dirty = 0;
        c->rasterizer.procs.activeTexture(c, i);
        c->rasterizer.procs.bindTexture(c, &(u.texture->surface));
        c->rasterizer.procs.texGeni(c, GGL_S,
                GGL_TEXTURE_GEN_MODE, GGL_AUTOMATIC);
        c->rasterizer.procs.texGeni(c, GGL_T,
                GGL_TEXTURE_GEN_MODE, GGL_AUTOMATIC);
        c->rasterizer.procs.texParameteri(c, GGL_TEXTURE_2D,
                GGL_TEXTURE_WRAP_S, u.texture->wraps);
        c->rasterizer.procs.texParameteri(c, GGL_TEXTURE_2D,
                GGL_TEXTURE_WRAP_T, u.texture->wrapt);
        c->rasterizer.procs.texParameteri(c, GGL_TEXTURE_2D,
                GGL_TEXTURE_MIN_FILTER, u.texture->min_filter);
        c->rasterizer.procs.texParameteri(c, GGL_TEXTURE_2D,
                GGL_TEXTURE_MAG_FILTER, u.texture->mag_filter);

        // disable this texture unit if it's not complete
        if (!u.texture->isComplete()) {
            c->rasterizer.procs.disable(c, GGL_TEXTURE_2D);
        }
    }
}

void ogles_validate_texture(ogles_context_t* c)
{
    for (int i=0 ; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
        if (c->rasterizer.state.texture[i].enable)
            validate_tmu(c, i);
    }
    c->rasterizer.procs.activeTexture(c, c->textures.active);
}

static
void invalidate_texture(ogles_context_t* c, int tmu, uint8_t flags = 0xFF) {
    c->textures.tmu[tmu].dirty = flags;
}

/*
 * If the active textures are EGLImage, they need to be locked before
 * they can be used.
 *
 * FIXME: code below is far from being optimal
 *
 */

void ogles_lock_textures(ogles_context_t* c)
{
    for (int i=0 ; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
        if (c->rasterizer.state.texture[i].enable) {
            texture_unit_t& u(c->textures.tmu[i]);
            ANativeWindowBuffer* native_buffer = u.texture->buffer;
            if (native_buffer) {
                c->rasterizer.procs.activeTexture(c, i);
                hw_module_t const* pModule;
                if (hw_get_module(GRALLOC_HARDWARE_MODULE_ID, &pModule))
                    continue;

                gralloc_module_t const* module =
                    reinterpret_cast<gralloc_module_t const*>(pModule);

                void* vaddr;
                int err = module->lock(module, native_buffer->handle,
                        GRALLOC_USAGE_SW_READ_OFTEN,
                        0, 0, native_buffer->width, native_buffer->height,
                        &vaddr);

                u.texture->setImageBits(vaddr);
                c->rasterizer.procs.bindTexture(c, &(u.texture->surface));
            }
        }
    }
}

void ogles_unlock_textures(ogles_context_t* c)
{
    for (int i=0 ; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
        if (c->rasterizer.state.texture[i].enable) {
            texture_unit_t& u(c->textures.tmu[i]);
            ANativeWindowBuffer* native_buffer = u.texture->buffer;
            if (native_buffer) {
                c->rasterizer.procs.activeTexture(c, i);
                hw_module_t const* pModule;
                if (hw_get_module(GRALLOC_HARDWARE_MODULE_ID, &pModule))
                    continue;

                gralloc_module_t const* module =
                    reinterpret_cast<gralloc_module_t const*>(pModule);

                module->unlock(module, native_buffer->handle);
                u.texture->setImageBits(NULL);
                c->rasterizer.procs.bindTexture(c, &(u.texture->surface));
            }
        }
    }
    c->rasterizer.procs.activeTexture(c, c->textures.active);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Format conversion
#endif

static uint32_t gl2format_table[6][4] = {
    // BYTE, 565, 4444, 5551
    { GGL_PIXEL_FORMAT_A_8,
      0, 0, 0 },                        // GL_ALPHA
    { GGL_PIXEL_FORMAT_RGB_888,
      GGL_PIXEL_FORMAT_RGB_565,
      0, 0 },                           // GL_RGB
    { GGL_PIXEL_FORMAT_RGBA_8888,
      0,
      GGL_PIXEL_FORMAT_RGBA_4444,
      GGL_PIXEL_FORMAT_RGBA_5551 },     // GL_RGBA
    { GGL_PIXEL_FORMAT_L_8,
      0, 0, 0 },                        // GL_LUMINANCE
    { GGL_PIXEL_FORMAT_LA_88,
      0, 0, 0 },                        // GL_LUMINANCE_ALPHA
};

static int32_t convertGLPixelFormat(GLint format, GLenum type)
{
    int32_t fi = -1;
    int32_t ti = -1;
    switch (format) {
    case GL_ALPHA:              fi = 0;     break;
    case GL_RGB:                fi = 1;     break;
    case GL_RGBA:               fi = 2;     break;
    case GL_LUMINANCE:          fi = 3;     break;
    case GL_LUMINANCE_ALPHA:    fi = 4;     break;
    }
    switch (type) {
    case GL_UNSIGNED_BYTE:          ti = 0; break;
    case GL_UNSIGNED_SHORT_5_6_5:   ti = 1; break;
    case GL_UNSIGNED_SHORT_4_4_4_4: ti = 2; break;
    case GL_UNSIGNED_SHORT_5_5_5_1: ti = 3; break;
    }
    if (fi==-1 || ti==-1)
        return 0;
    return gl2format_table[fi][ti];
}

// ----------------------------------------------------------------------------

static GLenum validFormatType(ogles_context_t* c, GLenum format, GLenum type)
{
    GLenum error = 0;
    if (format<GL_ALPHA || format>GL_LUMINANCE_ALPHA) {
        error = GL_INVALID_ENUM;
    }
    if (type != GL_UNSIGNED_BYTE && type != GL_UNSIGNED_SHORT_4_4_4_4 &&
        type != GL_UNSIGNED_SHORT_5_5_5_1 && type != GL_UNSIGNED_SHORT_5_6_5) {
        error = GL_INVALID_ENUM;
    }
    if (type == GL_UNSIGNED_SHORT_5_6_5 && format != GL_RGB) {
        error = GL_INVALID_OPERATION;
    }
    if ((type == GL_UNSIGNED_SHORT_4_4_4_4 ||
         type == GL_UNSIGNED_SHORT_5_5_5_1)  && format != GL_RGBA) {
        error = GL_INVALID_OPERATION;
    }
    if (error) {
        ogles_error(c, error);
    }
    return error;
}

// ----------------------------------------------------------------------------

GGLContext* getRasterizer(ogles_context_t* c)
{
    GGLContext* ggl = c->textures.ggl;
    if (ggl_unlikely(!ggl)) {
        // this is quite heavy the first time...
        gglInit(&ggl);
        if (!ggl) {
            return 0;
        }
        GGLfixed colors[4] = { 0, 0, 0, 0x10000 };
        c->textures.ggl = ggl;
        ggl->activeTexture(ggl, 0);
        ggl->enable(ggl, GGL_TEXTURE_2D);
        ggl->texEnvi(ggl, GGL_TEXTURE_ENV, GGL_TEXTURE_ENV_MODE, GGL_REPLACE);
        ggl->disable(ggl, GGL_DITHER);
        ggl->shadeModel(ggl, GGL_FLAT);
        ggl->color4xv(ggl, colors);
    }
    return ggl;
}

static __attribute__((noinline))
int copyPixels(
        ogles_context_t* c,
        const GGLSurface& dst,
        GLint xoffset, GLint yoffset,
        const GGLSurface& src,
        GLint x, GLint y, GLsizei w, GLsizei h)
{
    if ((dst.format == src.format) &&
        (dst.stride == src.stride) &&
        (dst.width == src.width) &&
        (dst.height == src.height) &&
        (dst.stride > 0) &&
        ((x|y) == 0) &&
        ((xoffset|yoffset) == 0))
    {
        // this is a common case...
        const GGLFormat& pixelFormat(c->rasterizer.formats[src.format]);
        const size_t size = src.height * src.stride * pixelFormat.size;
        memcpy(dst.data, src.data, size);
        return 0;
    }

    // use pixel-flinger to handle all the conversions
    GGLContext* ggl = getRasterizer(c);
    if (!ggl) {
        // the only reason this would fail is because we ran out of memory
        return GL_OUT_OF_MEMORY;
    }

    ggl->colorBuffer(ggl, &dst);
    ggl->bindTexture(ggl, &src);
    ggl->texCoord2i(ggl, x-xoffset, y-yoffset);
    ggl->recti(ggl, xoffset, yoffset, xoffset+w, yoffset+h);
    return 0;
}

// ----------------------------------------------------------------------------

static __attribute__((noinline))
sp<EGLTextureObject> getAndBindActiveTextureObject(ogles_context_t* c)
{
    sp<EGLTextureObject> tex;
    const int active = c->textures.active;
    const GLuint name = c->textures.tmu[active].name;

    // free the reference to the previously bound object
    texture_unit_t& u(c->textures.tmu[active]);
    if (u.texture)
        u.texture->decStrong(c);

    if (name == 0) {
        // 0 is our local texture object, not shared with anyone.
        // But it affects all bound TMUs immediately.
        // (we need to invalidate all units bound to this texture object)
        tex = c->textures.defaultTexture;
        for (int i=0 ; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
            if (c->textures.tmu[i].texture == tex.get())
                invalidate_texture(c, i);
        }
    } else {
        // get a new texture object for that name
        tex = c->surfaceManager->replaceTexture(name);
    }

    // bind this texture to the current active texture unit
    // and add a reference to this texture object
    u.texture = tex.get();
    u.texture->incStrong(c);
    u.name = name;
    invalidate_texture(c, active);
    return tex;
}

void bindTextureTmu(
    ogles_context_t* c, int tmu, GLuint texture, const sp<EGLTextureObject>& tex)
{
    if (tex.get() == c->textures.tmu[tmu].texture)
        return;

    // free the reference to the previously bound object
    texture_unit_t& u(c->textures.tmu[tmu]);
    if (u.texture)
        u.texture->decStrong(c);

    // bind this texture to the current active texture unit
    // and add a reference to this texture object
    u.texture = tex.get();
    u.texture->incStrong(c);
    u.name = texture;
    invalidate_texture(c, tmu);
}

int createTextureSurface(ogles_context_t* c,
        GGLSurface** outSurface, int32_t* outSize, GLint level,
        GLenum format, GLenum type, GLsizei width, GLsizei height,
        GLenum compressedFormat = 0)
{
    // find out which texture is bound to the current unit
    const int active = c->textures.active;
    const GLuint name = c->textures.tmu[active].name;

    // convert the pixelformat to one we can handle
    const int32_t formatIdx = convertGLPixelFormat(format, type);
    if (formatIdx == 0) { // we don't know what to do with this
        return GL_INVALID_OPERATION;
    }

    // figure out the size we need as well as the stride
    const GGLFormat& pixelFormat(c->rasterizer.formats[formatIdx]);
    const int32_t align = c->textures.unpackAlignment-1;
    const int32_t bpr = ((width * pixelFormat.size) + align) & ~align;
    const size_t size = bpr * height;
    const int32_t stride = bpr / pixelFormat.size;

    if (level > 0) {
        const int active = c->textures.active;
        EGLTextureObject* tex = c->textures.tmu[active].texture;
        status_t err = tex->reallocate(level,
                width, height, stride, formatIdx, compressedFormat, bpr);
        if (err != NO_ERROR)
            return GL_OUT_OF_MEMORY;
        GGLSurface& surface = tex->editMip(level);
        *outSurface = &surface;
        *outSize = size;
        return 0;
    }

    sp<EGLTextureObject> tex = getAndBindActiveTextureObject(c);
    status_t err = tex->reallocate(level,
            width, height, stride, formatIdx, compressedFormat, bpr);
    if (err != NO_ERROR)
        return GL_OUT_OF_MEMORY;

    tex->internalformat = format;
    *outSurface = &tex->surface;
    *outSize = size;
    return 0;
}

static size_t dataSizePalette4(int numLevels, int width, int height, int format)
{
    int indexBits = 8;
    int entrySize = 0;
    switch (format) {
    case GL_PALETTE4_RGB8_OES:
        indexBits = 4;
        /* FALLTHROUGH */
    case GL_PALETTE8_RGB8_OES:
        entrySize = 3;
        break;

    case GL_PALETTE4_RGBA8_OES:
        indexBits = 4;
        /* FALLTHROUGH */
    case GL_PALETTE8_RGBA8_OES:
        entrySize = 4;
        break;

    case GL_PALETTE4_R5_G6_B5_OES:
    case GL_PALETTE4_RGBA4_OES:
    case GL_PALETTE4_RGB5_A1_OES:
        indexBits = 4;
        /* FALLTHROUGH */
    case GL_PALETTE8_R5_G6_B5_OES:
    case GL_PALETTE8_RGBA4_OES:
    case GL_PALETTE8_RGB5_A1_OES:
        entrySize = 2;
        break;
    }

    size_t size = (1 << indexBits) * entrySize; // palette size

    for (int i=0 ; i< numLevels ; i++) {
        int w = (width  >> i) ? : 1;
        int h = (height >> i) ? : 1;
        int levelSize = h * ((w * indexBits) / 8) ? : 1;
        size += levelSize;
    }

    return size;
}

static void decodePalette4(const GLvoid *data, int level, int width, int height,
                           void *surface, int stride, int format)

{
    int indexBits = 8;
    int entrySize = 0;
    switch (format) {
    case GL_PALETTE4_RGB8_OES:
        indexBits = 4;
        /* FALLTHROUGH */
    case GL_PALETTE8_RGB8_OES:
        entrySize = 3;
        break;

    case GL_PALETTE4_RGBA8_OES:
        indexBits = 4;
        /* FALLTHROUGH */
    case GL_PALETTE8_RGBA8_OES:
        entrySize = 4;
        break;

    case GL_PALETTE4_R5_G6_B5_OES:
    case GL_PALETTE4_RGBA4_OES:
    case GL_PALETTE4_RGB5_A1_OES:
        indexBits = 4;
        /* FALLTHROUGH */
    case GL_PALETTE8_R5_G6_B5_OES:
    case GL_PALETTE8_RGBA4_OES:
    case GL_PALETTE8_RGB5_A1_OES:
        entrySize = 2;
        break;
    }

    const int paletteSize = (1 << indexBits) * entrySize;

    uint8_t const* pixels = (uint8_t *)data + paletteSize;
    for (int i=0 ; i<level ; i++) {
        int w = (width  >> i) ? : 1;
        int h = (height >> i) ? : 1;
        pixels += h * ((w * indexBits) / 8);
    }
    width  = (width  >> level) ? : 1;
    height = (height >> level) ? : 1;

    if (entrySize == 2) {
        uint8_t const* const palette = (uint8_t*)data;
        for (int y=0 ; y<height ; y++) {
            uint8_t* p = (uint8_t*)surface + y*stride*2;
            if (indexBits == 8) {
                for (int x=0 ; x<width ; x++) {
                    int index = 2 * (*pixels++);
                    *p++ = palette[index + 0];
                    *p++ = palette[index + 1];
                }
            } else {
                for (int x=0 ; x<width ; x+=2) {
                    int v = *pixels++;
                    int index = 2 * (v >> 4);
                    *p++ = palette[index + 0];
                    *p++ = palette[index + 1];
                    if (x+1 < width) {
                        index = 2 * (v & 0xF);
                        *p++ = palette[index + 0];
                        *p++ = palette[index + 1];
                    }
                }
            }
        }
    } else if (entrySize == 3) {
        uint8_t const* const palette = (uint8_t*)data;
        for (int y=0 ; y<height ; y++) {
            uint8_t* p = (uint8_t*)surface + y*stride*3;
            if (indexBits == 8) {
                for (int x=0 ; x<width ; x++) {
                    int index = 3 * (*pixels++);
                    *p++ = palette[index + 0];
                    *p++ = palette[index + 1];
                    *p++ = palette[index + 2];
                }
            } else {
                for (int x=0 ; x<width ; x+=2) {
                    int v = *pixels++;
                    int index = 3 * (v >> 4);
                    *p++ = palette[index + 0];
                    *p++ = palette[index + 1];
                    *p++ = palette[index + 2];
                    if (x+1 < width) {
                        index = 3 * (v & 0xF);
                        *p++ = palette[index + 0];
                        *p++ = palette[index + 1];
                        *p++ = palette[index + 2];
                    }
                }
            }
        }
    } else if (entrySize == 4) {
        uint8_t const* const palette = (uint8_t*)data;
        for (int y=0 ; y<height ; y++) {
            uint8_t* p = (uint8_t*)surface + y*stride*4;
            if (indexBits == 8) {
                for (int x=0 ; x<width ; x++) {
                    int index = 4 * (*pixels++);
                    *p++ = palette[index + 0];
                    *p++ = palette[index + 1];
                    *p++ = palette[index + 2];
                    *p++ = palette[index + 3];
                }
            } else {
                for (int x=0 ; x<width ; x+=2) {
                    int v = *pixels++;
                    int index = 4 * (v >> 4);
                    *p++ = palette[index + 0];
                    *p++ = palette[index + 1];
                    *p++ = palette[index + 2];
                    *p++ = palette[index + 3];
                    if (x+1 < width) {
                        index = 4 * (v & 0xF);
                        *p++ = palette[index + 0];
                        *p++ = palette[index + 1];
                        *p++ = palette[index + 2];
                        *p++ = palette[index + 3];
                    }
                }
            }
        }
    }
}



static __attribute__((noinline))
void set_depth_and_fog(ogles_context_t* c, GGLfixed z)
{
    const uint32_t enables = c->rasterizer.state.enables;
    // we need to compute Zw
    int32_t iterators[3];
    iterators[1] = iterators[2] = 0;
    GGLfixed Zw;
    GGLfixed n = gglFloatToFixed(c->transforms.vpt.zNear);
    GGLfixed f = gglFloatToFixed(c->transforms.vpt.zFar);
    if (z<=0)               Zw = n;
    else if (z>=0x10000)    Zw = f;
    else            Zw = gglMulAddx(z, (f-n), n);
    if (enables & GGL_ENABLE_FOG) {
        // set up fog if needed...
        iterators[0] = c->fog.fog(c, Zw);
        c->rasterizer.procs.fogGrad3xv(c, iterators);
    }
    if (enables & GGL_ENABLE_DEPTH_TEST) {
        // set up z-test if needed...
        int32_t z = (Zw & ~(Zw>>31));
        if (z >= 0x10000)
            z = 0xFFFF;
        iterators[0] = (z << 16) | z;
        c->rasterizer.procs.zGrad3xv(c, iterators);
    }
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark Generate mimaps
#endif

extern status_t buildAPyramid(ogles_context_t* c, EGLTextureObject* tex);

void generateMipmap(ogles_context_t* c, GLint level)
{
    if (level == 0) {
        const int active = c->textures.active;
        EGLTextureObject* tex = c->textures.tmu[active].texture;
        if (tex->generate_mipmap) {
            if (buildAPyramid(c, tex) != NO_ERROR) {
                ogles_error(c, GL_OUT_OF_MEMORY);
                return;
            }
        }
    }
}


static void texParameterx(
        GLenum target, GLenum pname, GLfixed param, ogles_context_t* c)
{
    if (target != GL_TEXTURE_2D && target != GL_TEXTURE_EXTERNAL_OES) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }

    EGLTextureObject* textureObject = c->textures.tmu[c->textures.active].texture;
    switch (pname) {
    case GL_TEXTURE_WRAP_S:
        if ((param == GL_REPEAT) ||
            (param == GL_CLAMP_TO_EDGE)) {
            textureObject->wraps = param;
        } else {
            goto invalid_enum;
        }
        break;
    case GL_TEXTURE_WRAP_T:
        if ((param == GL_REPEAT) ||
            (param == GL_CLAMP_TO_EDGE)) {
            textureObject->wrapt = param;
        } else {
            goto invalid_enum;
        }
        break;
    case GL_TEXTURE_MIN_FILTER:
        if ((param == GL_NEAREST) ||
            (param == GL_LINEAR) ||
            (param == GL_NEAREST_MIPMAP_NEAREST) ||
            (param == GL_LINEAR_MIPMAP_NEAREST) ||
            (param == GL_NEAREST_MIPMAP_LINEAR) ||
            (param == GL_LINEAR_MIPMAP_LINEAR)) {
            textureObject->min_filter = param;
        } else {
            goto invalid_enum;
        }
        break;
    case GL_TEXTURE_MAG_FILTER:
        if ((param == GL_NEAREST) ||
            (param == GL_LINEAR)) {
            textureObject->mag_filter = param;
        } else {
            goto invalid_enum;
        }
        break;
    case GL_GENERATE_MIPMAP:
        textureObject->generate_mipmap = param;
        break;
    default:
invalid_enum:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    invalidate_texture(c, c->textures.active);
}



static void drawTexxOESImp(GLfixed x, GLfixed y, GLfixed z, GLfixed w, GLfixed h,
        ogles_context_t* c)
{
    ogles_lock_textures(c);

    const GGLSurface& cbSurface = c->rasterizer.state.buffers.color.s;
    y = gglIntToFixed(cbSurface.height) - (y + h);
    w >>= FIXED_BITS;
    h >>= FIXED_BITS;

    // set up all texture units
    for (int i=0 ; i<GGL_TEXTURE_UNIT_COUNT ; i++) {
        if (!c->rasterizer.state.texture[i].enable)
            continue;

        int32_t texcoords[8];
        texture_unit_t& u(c->textures.tmu[i]);

        // validate this tmu (bind, wrap, filter)
        validate_tmu(c, i);
        // we CLAMP here, which works with premultiplied (s,t)
        c->rasterizer.procs.texParameteri(c,
                GGL_TEXTURE_2D, GGL_TEXTURE_WRAP_S, GGL_CLAMP);
        c->rasterizer.procs.texParameteri(c,
                GGL_TEXTURE_2D, GGL_TEXTURE_WRAP_T, GGL_CLAMP);
        u.dirty = 0xFF; // XXX: should be more subtle

        EGLTextureObject* textureObject = u.texture;
        const GLint Ucr = textureObject->crop_rect[0] << 16;
        const GLint Vcr = textureObject->crop_rect[1] << 16;
        const GLint Wcr = textureObject->crop_rect[2] << 16;
        const GLint Hcr = textureObject->crop_rect[3] << 16;

        // computes texture coordinates (pre-multiplied)
        int32_t dsdx = Wcr / w;   // dsdx =  ((Wcr/w)/Wt)*Wt
        int32_t dtdy =-Hcr / h;   // dtdy = -((Hcr/h)/Ht)*Ht
        int32_t s0   = Ucr       - gglMulx(dsdx, x); // s0 = Ucr - x * dsdx
        int32_t t0   = (Vcr+Hcr) - gglMulx(dtdy, y); // t0 = (Vcr+Hcr) - y*dtdy
        texcoords[0] = s0;
        texcoords[1] = dsdx;
        texcoords[2] = 0;
        texcoords[3] = t0;
        texcoords[4] = 0;
        texcoords[5] = dtdy;
        texcoords[6] = 0;
        texcoords[7] = 0;
        c->rasterizer.procs.texCoordGradScale8xv(c, i, texcoords);
    }

    const uint32_t enables = c->rasterizer.state.enables;
    if (ggl_unlikely(enables & (GGL_ENABLE_DEPTH_TEST|GGL_ENABLE_FOG)))
        set_depth_and_fog(c, z);

    c->rasterizer.procs.activeTexture(c, c->textures.active);
    c->rasterizer.procs.color4xv(c, c->currentColorClamped.v);
    c->rasterizer.procs.disable(c, GGL_W_LERP);
    c->rasterizer.procs.disable(c, GGL_AA);
    c->rasterizer.procs.shadeModel(c, GL_FLAT);
    c->rasterizer.procs.recti(c,
            gglFixedToIntRound(x),
            gglFixedToIntRound(y),
            gglFixedToIntRound(x)+w,
            gglFixedToIntRound(y)+h);

    ogles_unlock_textures(c);
}

static void drawTexxOES(GLfixed x, GLfixed y, GLfixed z, GLfixed w, GLfixed h,
        ogles_context_t* c)
{
    // quickly reject empty rects
    if ((w|h) <= 0)
        return;

    drawTexxOESImp(x, y, z, w, h, c);
}

static void drawTexiOES(GLint x, GLint y, GLint z, GLint w, GLint h, ogles_context_t* c)
{
    // All coordinates are integer, so if we have only one
    // texture unit active and no scaling is required
    // THEN, we can use our special 1:1 mapping
    // which is a lot faster.

    if (ggl_likely(c->rasterizer.state.enabled_tmu == 1)) {
        const int tmu = 0;
        texture_unit_t& u(c->textures.tmu[tmu]);
        EGLTextureObject* textureObject = u.texture;
        const GLint Wcr = textureObject->crop_rect[2];
        const GLint Hcr = textureObject->crop_rect[3];

        if ((w == Wcr) && (h == -Hcr)) {
            if ((w|h) <= 0) return; // quickly reject empty rects

            if (u.dirty) {
                c->rasterizer.procs.activeTexture(c, tmu);
                c->rasterizer.procs.bindTexture(c, &(u.texture->surface));
                c->rasterizer.procs.texParameteri(c, GGL_TEXTURE_2D,
                        GGL_TEXTURE_MIN_FILTER, u.texture->min_filter);
                c->rasterizer.procs.texParameteri(c, GGL_TEXTURE_2D,
                        GGL_TEXTURE_MAG_FILTER, u.texture->mag_filter);
            }
            c->rasterizer.procs.texGeni(c, GGL_S,
                    GGL_TEXTURE_GEN_MODE, GGL_ONE_TO_ONE);
            c->rasterizer.procs.texGeni(c, GGL_T,
                    GGL_TEXTURE_GEN_MODE, GGL_ONE_TO_ONE);
            u.dirty = 0xFF; // XXX: should be more subtle
            c->rasterizer.procs.activeTexture(c, c->textures.active);

            const GGLSurface& cbSurface = c->rasterizer.state.buffers.color.s;
            y = cbSurface.height - (y + h);
            const GLint Ucr = textureObject->crop_rect[0];
            const GLint Vcr = textureObject->crop_rect[1];
            const GLint s0  = Ucr - x;
            const GLint t0  = (Vcr + Hcr) - y;

            const GLuint tw = textureObject->surface.width;
            const GLuint th = textureObject->surface.height;
            if ((uint32_t(s0+x+w) > tw) || (uint32_t(t0+y+h) > th)) {
                // The GL spec is unclear about what should happen
                // in this case, so we just use the slow case, which
                // at least won't crash
                goto slow_case;
            }

            ogles_lock_textures(c);

            c->rasterizer.procs.texCoord2i(c, s0, t0);
            const uint32_t enables = c->rasterizer.state.enables;
            if (ggl_unlikely(enables & (GGL_ENABLE_DEPTH_TEST|GGL_ENABLE_FOG)))
                set_depth_and_fog(c, gglIntToFixed(z));

            c->rasterizer.procs.color4xv(c, c->currentColorClamped.v);
            c->rasterizer.procs.disable(c, GGL_W_LERP);
            c->rasterizer.procs.disable(c, GGL_AA);
            c->rasterizer.procs.shadeModel(c, GL_FLAT);
            c->rasterizer.procs.recti(c, x, y, x+w, y+h);

            ogles_unlock_textures(c);

            return;
        }
    }

slow_case:
    drawTexxOESImp(
            gglIntToFixed(x), gglIntToFixed(y), gglIntToFixed(z),
            gglIntToFixed(w), gglIntToFixed(h),
            c);
}


}; // namespace android
// ----------------------------------------------------------------------------

using namespace android;


#if 0
#pragma mark -
#pragma mark Texture API
#endif

void glActiveTexture(GLenum texture)
{
    ogles_context_t* c = ogles_context_t::get();
    if (uint32_t(texture-GL_TEXTURE0) > uint32_t(GGL_TEXTURE_UNIT_COUNT)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    c->textures.active = texture - GL_TEXTURE0;
    c->rasterizer.procs.activeTexture(c, c->textures.active);
}

void glBindTexture(GLenum target, GLuint texture)
{
    ogles_context_t* c = ogles_context_t::get();
    if (target != GL_TEXTURE_2D && target != GL_TEXTURE_EXTERNAL_OES) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }

    // Bind or create a texture
    sp<EGLTextureObject> tex;
    if (texture == 0) {
        // 0 is our local texture object
        tex = c->textures.defaultTexture;
    } else {
        tex = c->surfaceManager->texture(texture);
        if (ggl_unlikely(tex == 0)) {
            tex = c->surfaceManager->createTexture(texture);
            if (tex == 0) {
                ogles_error(c, GL_OUT_OF_MEMORY);
                return;
            }
        }
    }
    bindTextureTmu(c, c->textures.active, texture, tex);
}

void glGenTextures(GLsizei n, GLuint *textures)
{
    ogles_context_t* c = ogles_context_t::get();
    if (n<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    // generate unique (shared) texture names
    c->surfaceManager->getToken(n, textures);
}

void glDeleteTextures(GLsizei n, const GLuint *textures)
{
    ogles_context_t* c = ogles_context_t::get();
    if (n<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    // If deleting a bound texture, bind this unit to 0
    for (int t=0 ; t<GGL_TEXTURE_UNIT_COUNT ; t++) {
        if (c->textures.tmu[t].name == 0)
            continue;
        for (int i=0 ; i<n ; i++) {
            if (textures[i] && (textures[i] == c->textures.tmu[t].name)) {
                // bind this tmu to texture 0
                sp<EGLTextureObject> tex(c->textures.defaultTexture);
                bindTextureTmu(c, t, 0, tex);
            }
        }
    }
    c->surfaceManager->deleteTextures(n, textures);
    c->surfaceManager->recycleTokens(n, textures);
}

void glMultiTexCoord4f(
        GLenum target, GLfloat s, GLfloat t, GLfloat r, GLfloat q)
{
    ogles_context_t* c = ogles_context_t::get();
    if (uint32_t(target-GL_TEXTURE0) > uint32_t(GGL_TEXTURE_UNIT_COUNT)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    const int tmu = target-GL_TEXTURE0;
    c->current.texture[tmu].S = gglFloatToFixed(s);
    c->current.texture[tmu].T = gglFloatToFixed(t);
    c->current.texture[tmu].R = gglFloatToFixed(r);
    c->current.texture[tmu].Q = gglFloatToFixed(q);
}

void glMultiTexCoord4x(
        GLenum target, GLfixed s, GLfixed t, GLfixed r, GLfixed q)
{
    ogles_context_t* c = ogles_context_t::get();
    if (uint32_t(target-GL_TEXTURE0) > uint32_t(GGL_TEXTURE_UNIT_COUNT)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    const int tmu = target-GL_TEXTURE0;
    c->current.texture[tmu].S = s;
    c->current.texture[tmu].T = t;
    c->current.texture[tmu].R = r;
    c->current.texture[tmu].Q = q;
}

void glPixelStorei(GLenum pname, GLint param)
{
    ogles_context_t* c = ogles_context_t::get();
    if ((pname != GL_PACK_ALIGNMENT) && (pname != GL_UNPACK_ALIGNMENT)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if ((param<=0 || param>8) || (param & (param-1))) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    if (pname == GL_PACK_ALIGNMENT)
        c->textures.packAlignment = param;
    if (pname == GL_UNPACK_ALIGNMENT)
        c->textures.unpackAlignment = param;
}

void glTexEnvf(GLenum target, GLenum pname, GLfloat param)
{
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.texEnvi(c, target, pname, GLint(param));
}

void glTexEnvfv(
        GLenum target, GLenum pname, const GLfloat *params)
{
    ogles_context_t* c = ogles_context_t::get();
    if (pname == GL_TEXTURE_ENV_MODE) {
        c->rasterizer.procs.texEnvi(c, target, pname, GLint(*params));
        return;
    }
    if (pname == GL_TEXTURE_ENV_COLOR) {
        GGLfixed fixed[4];
        for (int i=0 ; i<4 ; i++)
            fixed[i] = gglFloatToFixed(params[i]);
        c->rasterizer.procs.texEnvxv(c, target, pname, fixed);
        return;
    }
    ogles_error(c, GL_INVALID_ENUM);
}

void glTexEnvx(GLenum target, GLenum pname, GLfixed param)
{
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.texEnvi(c, target, pname, param);
}

void glTexEnvxv(
        GLenum target, GLenum pname, const GLfixed *params)
{
    ogles_context_t* c = ogles_context_t::get();
    c->rasterizer.procs.texEnvxv(c, target, pname, params);
}

void glTexParameteriv(
        GLenum target, GLenum pname, const GLint* params)
{
    ogles_context_t* c = ogles_context_t::get();
    if (target != GL_TEXTURE_2D && target != GL_TEXTURE_EXTERNAL_OES) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }

    EGLTextureObject* textureObject = c->textures.tmu[c->textures.active].texture;
    switch (pname) {
    case GL_TEXTURE_CROP_RECT_OES:
        memcpy(textureObject->crop_rect, params, 4*sizeof(GLint));
        break;
    default:
        texParameterx(target, pname, GLfixed(params[0]), c);
        return;
    }
}

void glTexParameterf(
        GLenum target, GLenum pname, GLfloat param)
{
    ogles_context_t* c = ogles_context_t::get();
    texParameterx(target, pname, GLfixed(param), c);
}

void glTexParameterx(
        GLenum target, GLenum pname, GLfixed param)
{
    ogles_context_t* c = ogles_context_t::get();
    texParameterx(target, pname, param, c);
}

void glTexParameteri(
        GLenum target, GLenum pname, GLint param)
{
    ogles_context_t* c = ogles_context_t::get();
    texParameterx(target, pname, GLfixed(param), c);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#endif

void glCompressedTexImage2D(
        GLenum target, GLint level, GLenum internalformat,
        GLsizei width, GLsizei height, GLint border,
        GLsizei imageSize, const GLvoid *data)
{
    ogles_context_t* c = ogles_context_t::get();
    if (target != GL_TEXTURE_2D) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if (width<0 || height<0 || border!=0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    // "uncompress" the texture since pixelflinger doesn't support
    // any compressed texture format natively.
    GLenum format;
    GLenum type;
    switch (internalformat) {
    case GL_PALETTE8_RGB8_OES:
    case GL_PALETTE4_RGB8_OES:
        format      = GL_RGB;
        type        = GL_UNSIGNED_BYTE;
        break;
    case GL_PALETTE8_RGBA8_OES:
    case GL_PALETTE4_RGBA8_OES:
        format      = GL_RGBA;
        type        = GL_UNSIGNED_BYTE;
        break;
    case GL_PALETTE8_R5_G6_B5_OES:
    case GL_PALETTE4_R5_G6_B5_OES:
        format      = GL_RGB;
        type        = GL_UNSIGNED_SHORT_5_6_5;
        break;
    case GL_PALETTE8_RGBA4_OES:
    case GL_PALETTE4_RGBA4_OES:
        format      = GL_RGBA;
        type        = GL_UNSIGNED_SHORT_4_4_4_4;
        break;
    case GL_PALETTE8_RGB5_A1_OES:
    case GL_PALETTE4_RGB5_A1_OES:
        format      = GL_RGBA;
        type        = GL_UNSIGNED_SHORT_5_5_5_1;
        break;
#ifdef GL_OES_compressed_ETC1_RGB8_texture
    case GL_ETC1_RGB8_OES:
        format      = GL_RGB;
        type        = GL_UNSIGNED_BYTE;
        break;
#endif
    default:
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }

    if (!data || !width || !height) {
        // unclear if this is an error or not...
        return;
    }

    int32_t size;
    GGLSurface* surface;

#ifdef GL_OES_compressed_ETC1_RGB8_texture
    if (internalformat == GL_ETC1_RGB8_OES) {
        GLsizei compressedSize = etc1_get_encoded_data_size(width, height);
        if (compressedSize > imageSize) {
            ogles_error(c, GL_INVALID_VALUE);
            return;
        }
        int error = createTextureSurface(c, &surface, &size,
                level, format, type, width, height);
        if (error) {
            ogles_error(c, error);
            return;
        }
        if (etc1_decode_image(
                (const etc1_byte*)data,
                (etc1_byte*)surface->data,
                width, height, 3, surface->stride*3) != 0) {
            ogles_error(c, GL_INVALID_OPERATION);
        }
        return;
    }
#endif

    // all mipmap levels are specified at once.
    const int numLevels = level<0 ? -level : 1;

    if (dataSizePalette4(numLevels, width, height, format) > imageSize) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    for (int i=0 ; i<numLevels ; i++) {
        int lod_w = (width  >> i) ? : 1;
        int lod_h = (height >> i) ? : 1;
        int error = createTextureSurface(c, &surface, &size,
                i, format, type, lod_w, lod_h);
        if (error) {
            ogles_error(c, error);
            return;
        }
        decodePalette4(data, i, width, height,
                surface->data, surface->stride, internalformat);
    }
}


void glTexImage2D(
        GLenum target, GLint level, GLint internalformat,
        GLsizei width, GLsizei height, GLint border,
        GLenum format, GLenum type, const GLvoid *pixels)
{
    ogles_context_t* c = ogles_context_t::get();
    if (target != GL_TEXTURE_2D) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if (width<0 || height<0 || border!=0 || level < 0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    if (format != (GLenum)internalformat) {
        ogles_error(c, GL_INVALID_OPERATION);
        return;
    }
    if (validFormatType(c, format, type)) {
        return;
    }

    int32_t size = 0;
    GGLSurface* surface = 0;
    int error = createTextureSurface(c, &surface, &size,
            level, format, type, width, height);
    if (error) {
        ogles_error(c, error);
        return;
    }

    if (pixels) {
        const int32_t formatIdx = convertGLPixelFormat(format, type);
        const GGLFormat& pixelFormat(c->rasterizer.formats[formatIdx]);
        const int32_t align = c->textures.unpackAlignment-1;
        const int32_t bpr = ((width * pixelFormat.size) + align) & ~align;
        const size_t size = bpr * height;
        const int32_t stride = bpr / pixelFormat.size;

        GGLSurface userSurface;
        userSurface.version = sizeof(userSurface);
        userSurface.width  = width;
        userSurface.height = height;
        userSurface.stride = stride;
        userSurface.format = formatIdx;
        userSurface.compressedFormat = 0;
        userSurface.data = (GLubyte*)pixels;

        int err = copyPixels(c, *surface, 0, 0, userSurface, 0, 0, width, height);
        if (err) {
            ogles_error(c, err);
            return;
        }
        generateMipmap(c, level);
    }
}

// ----------------------------------------------------------------------------

void glCompressedTexSubImage2D(
        GLenum target, GLint level, GLint xoffset,
        GLint yoffset, GLsizei width, GLsizei height,
        GLenum format, GLsizei imageSize,
        const GLvoid *data)
{
    ogles_context_t* c = ogles_context_t::get();
    ogles_error(c, GL_INVALID_ENUM);
}

void glTexSubImage2D(
        GLenum target, GLint level, GLint xoffset,
        GLint yoffset, GLsizei width, GLsizei height,
        GLenum format, GLenum type, const GLvoid *pixels)
{
    ogles_context_t* c = ogles_context_t::get();
    if (target != GL_TEXTURE_2D) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if (xoffset<0 || yoffset<0 || width<0 || height<0 || level<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    if (validFormatType(c, format, type)) {
        return;
    }

    // find out which texture is bound to the current unit
    const int active = c->textures.active;
    EGLTextureObject* tex = c->textures.tmu[active].texture;
    const GGLSurface& surface(tex->mip(level));

    if (!tex->internalformat || tex->direct) {
        ogles_error(c, GL_INVALID_OPERATION);
        return;
    }

    if (format != tex->internalformat) {
        ogles_error(c, GL_INVALID_OPERATION);
        return;
    }
    if ((xoffset + width  > GLsizei(surface.width)) ||
        (yoffset + height > GLsizei(surface.height))) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    if (!width || !height) {
        return; // okay, but no-op.
    }

    // figure out the size we need as well as the stride
    const int32_t formatIdx = convertGLPixelFormat(format, type);
    if (formatIdx == 0) { // we don't know what to do with this
        ogles_error(c, GL_INVALID_OPERATION);
        return;
    }

    const GGLFormat& pixelFormat(c->rasterizer.formats[formatIdx]);
    const int32_t align = c->textures.unpackAlignment-1;
    const int32_t bpr = ((width * pixelFormat.size) + align) & ~align;
    const size_t size = bpr * height;
    const int32_t stride = bpr / pixelFormat.size;
    GGLSurface userSurface;
    userSurface.version = sizeof(userSurface);
    userSurface.width  = width;
    userSurface.height = height;
    userSurface.stride = stride;
    userSurface.format = formatIdx;
    userSurface.compressedFormat = 0;
    userSurface.data = (GLubyte*)pixels;

    int err = copyPixels(c,
            surface, xoffset, yoffset,
            userSurface, 0, 0, width, height);
    if (err) {
        ogles_error(c, err);
        return;
    }

    generateMipmap(c, level);

    // since we only changed the content of the texture, we don't need
    // to call bindTexture on the main rasterizer.
}

// ----------------------------------------------------------------------------

void glCopyTexImage2D(
        GLenum target, GLint level, GLenum internalformat,
        GLint x, GLint y, GLsizei width, GLsizei height,
        GLint border)
{
    ogles_context_t* c = ogles_context_t::get();
    if (target != GL_TEXTURE_2D) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if (internalformat<GL_ALPHA || internalformat>GL_LUMINANCE_ALPHA) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if (width<0 || height<0 || border!=0 || level<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    GLenum format = 0;
    GLenum type = GL_UNSIGNED_BYTE;
    const GGLSurface& cbSurface = c->rasterizer.state.buffers.color.s;
    const int cbFormatIdx = cbSurface.format;
    switch (cbFormatIdx) {
    case GGL_PIXEL_FORMAT_RGB_565:
        type = GL_UNSIGNED_SHORT_5_6_5;
        break;
    case GGL_PIXEL_FORMAT_RGBA_5551:
        type = GL_UNSIGNED_SHORT_5_5_5_1;
        break;
    case GGL_PIXEL_FORMAT_RGBA_4444:
        type = GL_UNSIGNED_SHORT_4_4_4_4;
        break;
    }
    switch (internalformat) {
    case GL_ALPHA:
    case GL_LUMINANCE_ALPHA:
    case GL_LUMINANCE:
        type = GL_UNSIGNED_BYTE;
        break;
    }

    // figure out the format to use for the new texture
    switch (cbFormatIdx) {
    case GGL_PIXEL_FORMAT_RGBA_8888:
    case GGL_PIXEL_FORMAT_A_8:
    case GGL_PIXEL_FORMAT_RGBA_5551:
    case GGL_PIXEL_FORMAT_RGBA_4444:
        format = internalformat;
        break;
    case GGL_PIXEL_FORMAT_RGBX_8888:
    case GGL_PIXEL_FORMAT_RGB_888:
    case GGL_PIXEL_FORMAT_RGB_565:
    case GGL_PIXEL_FORMAT_L_8:
        switch (internalformat) {
        case GL_LUMINANCE:
        case GL_RGB:
            format = internalformat;
            break;
        }
        break;
    }

    if (format == 0) {
        // invalid combination
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }

    // create the new texture...
    int32_t size;
    GGLSurface* surface;
    int error = createTextureSurface(c, &surface, &size,
            level, format, type, width, height);
    if (error) {
        ogles_error(c, error);
        return;
    }

    // The bottom row is stored first in textures
    GGLSurface txSurface(*surface);
    txSurface.stride = -txSurface.stride;

    // (x,y) is the lower-left corner of colorBuffer
    y = cbSurface.height - (y + height);

    /* The GLES spec says:
     * If any of the pixels within the specified rectangle are outside
     * the framebuffer associated with the current rendering context,
     * then the values obtained for those pixels are undefined.
     */
    if (x+width > GLint(cbSurface.width))
        width = cbSurface.width - x;

    if (y+height > GLint(cbSurface.height))
        height = cbSurface.height - y;

    int err = copyPixels(c,
            txSurface, 0, 0,
            cbSurface, x, y, width, height);
    if (err) {
        ogles_error(c, err);
    }

    generateMipmap(c, level);
}

void glCopyTexSubImage2D(
        GLenum target, GLint level, GLint xoffset, GLint yoffset,
        GLint x, GLint y, GLsizei width, GLsizei height)
{
    ogles_context_t* c = ogles_context_t::get();
    if (target != GL_TEXTURE_2D) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if (xoffset<0 || yoffset<0 || width<0 || height<0 || level<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    if (!width || !height) {
        return; // okay, but no-op.
    }

    // find out which texture is bound to the current unit
    const int active = c->textures.active;
    EGLTextureObject* tex = c->textures.tmu[active].texture;
    const GGLSurface& surface(tex->mip(level));

    if (!tex->internalformat) {
        ogles_error(c, GL_INVALID_OPERATION);
        return;
    }
    if ((xoffset + width  > GLsizei(surface.width)) ||
        (yoffset + height > GLsizei(surface.height))) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    // The bottom row is stored first in textures
    GGLSurface txSurface(surface);
    txSurface.stride = -txSurface.stride;

    // (x,y) is the lower-left corner of colorBuffer
    const GGLSurface& cbSurface = c->rasterizer.state.buffers.color.s;
    y = cbSurface.height - (y + height);

    /* The GLES spec says:
     * If any of the pixels within the specified rectangle are outside
     * the framebuffer associated with the current rendering context,
     * then the values obtained for those pixels are undefined.
     */
    if (x+width > GLint(cbSurface.width))
        width = cbSurface.width - x;

    if (y+height > GLint(cbSurface.height))
        height = cbSurface.height - y;

    int err = copyPixels(c,
            txSurface, xoffset, yoffset,
            cbSurface, x, y, width, height);
    if (err) {
        ogles_error(c, err);
        return;
    }

    generateMipmap(c, level);
}

void glReadPixels(
        GLint x, GLint y, GLsizei width, GLsizei height,
        GLenum format, GLenum type, GLvoid *pixels)
{
    ogles_context_t* c = ogles_context_t::get();
    if ((format != GL_RGBA) && (format != GL_RGB)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if ((type != GL_UNSIGNED_BYTE) && (type != GL_UNSIGNED_SHORT_5_6_5)) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }
    if (width<0 || height<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    if (x<0 || y<0) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    int32_t formatIdx = GGL_PIXEL_FORMAT_NONE;
    if ((format == GL_RGBA) && (type == GL_UNSIGNED_BYTE)) {
        formatIdx = GGL_PIXEL_FORMAT_RGBA_8888;
    } else if ((format == GL_RGB) && (type == GL_UNSIGNED_SHORT_5_6_5)) {
        formatIdx = GGL_PIXEL_FORMAT_RGB_565;
    } else {
        ogles_error(c, GL_INVALID_OPERATION);
        return;
    }

    const GGLSurface& readSurface = c->rasterizer.state.buffers.read.s;
    if ((x+width > GLint(readSurface.width)) ||
            (y+height > GLint(readSurface.height))) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    const GGLFormat& pixelFormat(c->rasterizer.formats[formatIdx]);
    const int32_t align = c->textures.packAlignment-1;
    const int32_t bpr = ((width * pixelFormat.size) + align) & ~align;
    const int32_t stride = bpr / pixelFormat.size;

    GGLSurface userSurface;
    userSurface.version = sizeof(userSurface);
    userSurface.width  = width;
    userSurface.height = height;
    userSurface.stride = -stride; // bottom row is transfered first
    userSurface.format = formatIdx;
    userSurface.compressedFormat = 0;
    userSurface.data = (GLubyte*)pixels;

    // use pixel-flinger to handle all the conversions
    GGLContext* ggl = getRasterizer(c);
    if (!ggl) {
        // the only reason this would fail is because we ran out of memory
        ogles_error(c, GL_OUT_OF_MEMORY);
        return;
    }

    ggl->colorBuffer(ggl, &userSurface);  // destination is user buffer
    ggl->bindTexture(ggl, &readSurface);  // source is read-buffer
    ggl->texCoord2i(ggl, x, readSurface.height - (y + height));
    ggl->recti(ggl, 0, 0, width, height);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark DrawTexture Extension
#endif

void glDrawTexsvOES(const GLshort* coords) {
    ogles_context_t* c = ogles_context_t::get();
    drawTexiOES(coords[0], coords[1], coords[2], coords[3], coords[4], c);
}
void glDrawTexivOES(const GLint* coords) {
    ogles_context_t* c = ogles_context_t::get();
    drawTexiOES(coords[0], coords[1], coords[2], coords[3], coords[4], c);
}
void glDrawTexsOES(GLshort x , GLshort y, GLshort z, GLshort w, GLshort h) {
    ogles_context_t* c = ogles_context_t::get();
    drawTexiOES(x, y, z, w, h, c);
}
void glDrawTexiOES(GLint x, GLint y, GLint z, GLint w, GLint h) {
    ogles_context_t* c = ogles_context_t::get();
    drawTexiOES(x, y, z, w, h, c);
}

void glDrawTexfvOES(const GLfloat* coords) {
    ogles_context_t* c = ogles_context_t::get();
    drawTexxOES(
            gglFloatToFixed(coords[0]),
            gglFloatToFixed(coords[1]),
            gglFloatToFixed(coords[2]),
            gglFloatToFixed(coords[3]),
            gglFloatToFixed(coords[4]),
            c);
}
void glDrawTexxvOES(const GLfixed* coords) {
    ogles_context_t* c = ogles_context_t::get();
    drawTexxOES(coords[0], coords[1], coords[2], coords[3], coords[4], c);
}
void glDrawTexfOES(GLfloat x, GLfloat y, GLfloat z, GLfloat w, GLfloat h){
    ogles_context_t* c = ogles_context_t::get();
    drawTexxOES(
            gglFloatToFixed(x), gglFloatToFixed(y), gglFloatToFixed(z),
            gglFloatToFixed(w), gglFloatToFixed(h),
            c);
}
void glDrawTexxOES(GLfixed x, GLfixed y, GLfixed z, GLfixed w, GLfixed h) {
    ogles_context_t* c = ogles_context_t::get();
    drawTexxOES(x, y, z, w, h, c);
}

// ----------------------------------------------------------------------------
#if 0
#pragma mark -
#pragma mark EGL Image Extension
#endif

void glEGLImageTargetTexture2DOES(GLenum target, GLeglImageOES image)
{
    ogles_context_t* c = ogles_context_t::get();
    if (target != GL_TEXTURE_2D && target != GL_TEXTURE_EXTERNAL_OES) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }

    if (image == EGL_NO_IMAGE_KHR) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    ANativeWindowBuffer* native_buffer = (ANativeWindowBuffer*)image;
    if (native_buffer->common.magic != ANDROID_NATIVE_BUFFER_MAGIC) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    if (native_buffer->common.version != sizeof(ANativeWindowBuffer)) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    // bind it to the texture unit
    sp<EGLTextureObject> tex = getAndBindActiveTextureObject(c);
    tex->setImage(native_buffer);
}

void glEGLImageTargetRenderbufferStorageOES(GLenum target, GLeglImageOES image)
{
    ogles_context_t* c = ogles_context_t::get();
    if (target != GL_RENDERBUFFER_OES) {
        ogles_error(c, GL_INVALID_ENUM);
        return;
    }

    if (image == EGL_NO_IMAGE_KHR) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    ANativeWindowBuffer* native_buffer = (ANativeWindowBuffer*)image;
    if (native_buffer->common.magic != ANDROID_NATIVE_BUFFER_MAGIC) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }
    if (native_buffer->common.version != sizeof(ANativeWindowBuffer)) {
        ogles_error(c, GL_INVALID_VALUE);
        return;
    }

    // well, we're not supporting this extension anyways
}

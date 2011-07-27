/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ANDROID_HWUI_LAYER_H
#define ANDROID_HWUI_LAYER_H

#include <sys/types.h>

#include <GLES2/gl2.h>

#include <ui/Region.h>

#include <SkXfermode.h>

#include "Rect.h"
#include "SkiaColorFilter.h"
#include "Texture.h"
#include "Vertex.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

/**
 * A layer has dimensions and is backed by an OpenGL texture or FBO.
 */
struct Layer {
    Layer(const uint32_t layerWidth, const uint32_t layerHeight) {
        mesh = NULL;
        meshIndices = NULL;
        meshElementCount = 0;
        cacheable = true;
        textureLayer = false;
        renderTarget = GL_TEXTURE_2D;
        texture.width = layerWidth;
        texture.height = layerHeight;
        colorFilter = NULL;
    }

    ~Layer() {
        if (mesh) delete mesh;
        if (meshIndices) delete meshIndices;
    }

    /**
     * Sets this layer's region to a rectangle. Computes the appropriate
     * texture coordinates.
     */
    void setRegionAsRect() {
        const android::Rect& bounds = region.getBounds();
        regionRect.set(bounds.leftTop().x, bounds.leftTop().y,
               bounds.rightBottom().x, bounds.rightBottom().y);

        const float texX = 1.0f / float(texture.width);
        const float texY = 1.0f / float(texture.height);
        const float height = layer.getHeight();
        texCoords.set(
               regionRect.left * texX, (height - regionRect.top) * texY,
               regionRect.right * texX, (height - regionRect.bottom) * texY);

        regionRect.translate(layer.left, layer.top);
    }

    inline uint32_t getWidth() {
        return texture.width;
    }

    inline uint32_t getHeight() {
        return texture.height;
    }

    void setSize(uint32_t width, uint32_t height) {
        texture.width = width;
        texture.height = height;
    }

    inline void setBlend(bool blend) {
        texture.blend = blend;
    }

    inline bool isBlend() {
        return texture.blend;
    }

    inline void setAlpha(int alpha) {
        this->alpha = alpha;
    }

    inline void setAlpha(int alpha, SkXfermode::Mode mode) {
        this->alpha = alpha;
        this->mode = mode;
    }

    inline int getAlpha() {
        return alpha;
    }

    inline SkXfermode::Mode getMode() {
        return mode;
    }

    inline void setEmpty(bool empty) {
        this->empty = empty;
    }

    inline bool isEmpty() {
        return empty;
    }

    inline void setFbo(GLuint fbo) {
        this->fbo = fbo;
    }

    inline GLuint getFbo() {
        return fbo;
    }

    inline GLuint* getTexturePointer() {
        return &texture.id;
    }

    inline GLuint getTexture() {
        return texture.id;
    }

    inline GLenum getRenderTarget() {
        return renderTarget;
    }

    inline void setRenderTarget(GLenum renderTarget) {
        this->renderTarget = renderTarget;
    }

    void setWrap(GLenum wrapS, GLenum wrapT, bool bindTexture = false, bool force = false) {
        texture.setWrap(wrapS, wrapT, bindTexture, force, renderTarget);
    }

    void setFilter(GLenum min, GLenum mag, bool bindTexture = false, bool force = false) {
        texture.setFilter(min, mag,bindTexture, force, renderTarget);
    }

    inline bool isCacheable() {
        return cacheable;
    }

    inline void setCacheable(bool cacheable) {
        this->cacheable = cacheable;
    }

    inline bool isTextureLayer() {
        return textureLayer;
    }

    inline void setTextureLayer(bool textureLayer) {
        this->textureLayer = textureLayer;
    }

    inline SkiaColorFilter* getColorFilter() {
        return colorFilter;
    }

    inline void setColorFilter(SkiaColorFilter* filter) {
        colorFilter = filter;
    }

    inline void bindTexture() {
        glBindTexture(renderTarget, texture.id);
    }

    inline void generateTexture() {
        glGenTextures(1, &texture.id);
    }

    inline void deleteTexture() {
        if (texture.id) glDeleteTextures(1, &texture.id);
    }

    inline void deleteFbo() {
        if (fbo) glDeleteFramebuffers(1, &fbo);
    }

    inline void allocateTexture(GLenum format, GLenum storage) {
        glTexImage2D(renderTarget, 0, format, getWidth(), getHeight(), 0, format, storage, NULL);
    }

    inline mat4& getTexTransform() {
        return texTransform;
    }

    /**
     * Bounds of the layer.
     */
    Rect layer;
    /**
     * Texture coordinates of the layer.
     */
    Rect texCoords;

    /**
     * Dirty region indicating what parts of the layer
     * have been drawn.
     */
    Region region;
    /**
     * If the region is a rectangle, coordinates of the
     * region are stored here.
     */
    Rect regionRect;

    /**
     * If the layer can be rendered as a mesh, this is non-null.
     */
    TextureVertex* mesh;
    uint16_t* meshIndices;
    GLsizei meshElementCount;

private:
    /**
     * Name of the FBO used to render the layer. If the name is 0
     * this layer is not backed by an FBO, but a simple texture.
     */
    GLuint fbo;

    /**
     * Indicates whether this layer has been used already.
     */
    bool empty;

    /**
     * The texture backing this layer.
     */
    Texture texture;

    /**
     * If set to true (by default), the layer can be reused.
     */
    bool cacheable;

    /**
     * When set to true, this layer must be treated as a texture
     * layer.
     */
    bool textureLayer;

    /**
     * Indicates the render target.
     */
    GLenum renderTarget;

    /**
     * Color filter used to draw this layer. Optional.
     */
    SkiaColorFilter* colorFilter;

    /**
     * Opacity of the layer.
     */
    int alpha;
    /**
     * Blending mode of the layer.
     */
    SkXfermode::Mode mode;

    /**
     * Optional texture coordinates transform.
     */
    mat4 texTransform;

}; // struct Layer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_LAYER_H

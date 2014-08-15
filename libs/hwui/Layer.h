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

#include <cutils/compiler.h>
#include <sys/types.h>
#include <utils/StrongPointer.h>

#include <GLES2/gl2.h>

#include <ui/Region.h>

#include <SkPaint.h>
#include <SkXfermode.h>

#include "Matrix.h"
#include "Rect.h"
#include "RenderBuffer.h"
#include "Texture.h"
#include "Vertex.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

// Forward declarations
class Caches;
class RenderState;
class OpenGLRenderer;
class RenderNode;
class DeferredDisplayList;
class DeferStateStruct;

/**
 * A layer has dimensions and is backed by an OpenGL texture or FBO.
 */
class Layer {
public:
    Layer(RenderState& renderState, const uint32_t layerWidth, const uint32_t layerHeight);
    ~Layer();

    static uint32_t computeIdealWidth(uint32_t layerWidth);
    static uint32_t computeIdealHeight(uint32_t layerHeight);

    /**
     * Calling this method will remove (either by recycling or
     * destroying) the associated FBO, if present, and any render
     * buffer (stencil for instance.)
     */
    void removeFbo(bool flush = true);

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

    void setWindowTransform(Matrix4& windowTransform) {
        cachedInvTransformInWindow.loadInverse(windowTransform);
        rendererLightPosDirty = true;
    }

    void updateDeferred(RenderNode* renderNode, int left, int top, int right, int bottom);

    inline uint32_t getWidth() const {
        return texture.width;
    }

    inline uint32_t getHeight() const {
        return texture.height;
    }

    /**
     * Resize the layer and its texture if needed.
     *
     * @param width The new width of the layer
     * @param height The new height of the layer
     *
     * @return True if the layer was resized or nothing happened, false if
     *         a failure occurred during the resizing operation
     */
    bool resize(const uint32_t width, const uint32_t height);

    void setSize(uint32_t width, uint32_t height) {
        texture.width = width;
        texture.height = height;
    }

    ANDROID_API void setPaint(const SkPaint* paint);

    inline void setBlend(bool blend) {
        texture.blend = blend;
    }

    inline bool isBlend() const {
        return texture.blend;
    }

    inline void setForceFilter(bool forceFilter) {
        this->forceFilter = forceFilter;
    }

    inline bool getForceFilter() const {
        return forceFilter;
    }

    inline void setAlpha(int alpha) {
        this->alpha = alpha;
    }

    inline void setAlpha(int alpha, SkXfermode::Mode mode) {
        this->alpha = alpha;
        this->mode = mode;
    }

    inline int getAlpha() const {
        return alpha;
    }

    inline SkXfermode::Mode getMode() const {
        return mode;
    }

    inline void setEmpty(bool empty) {
        this->empty = empty;
    }

    inline bool isEmpty() const {
        return empty;
    }

    inline void setFbo(GLuint fbo) {
        this->fbo = fbo;
    }

    inline GLuint getFbo() const {
        return fbo;
    }

    inline void setStencilRenderBuffer(RenderBuffer* renderBuffer) {
        if (RenderBuffer::isStencilBuffer(renderBuffer->getFormat())) {
            this->stencil = renderBuffer;
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT,
                    GL_RENDERBUFFER, stencil->getName());
        } else {
            ALOGE("The specified render buffer is not a stencil buffer");
        }
    }

    inline RenderBuffer* getStencilRenderBuffer() const {
        return stencil;
    }

    inline GLuint getTexture() const {
        return texture.id;
    }

    inline GLenum getRenderTarget() const {
        return renderTarget;
    }

    inline void setRenderTarget(GLenum renderTarget) {
        this->renderTarget = renderTarget;
    }

    void setWrap(GLenum wrap, bool bindTexture = false, bool force = false) {
        texture.setWrap(wrap, bindTexture, force, renderTarget);
    }

    void setFilter(GLenum filter, bool bindTexture = false, bool force = false) {
        texture.setFilter(filter, bindTexture, force, renderTarget);
    }

    inline bool isCacheable() const {
        return cacheable;
    }

    inline void setCacheable(bool cacheable) {
        this->cacheable = cacheable;
    }

    inline bool isDirty() const {
        return dirty;
    }

    inline void setDirty(bool dirty) {
        this->dirty = dirty;
    }

    inline bool isTextureLayer() const {
        return textureLayer;
    }

    inline void setTextureLayer(bool textureLayer) {
        this->textureLayer = textureLayer;
    }

    inline SkColorFilter* getColorFilter() const {
        return colorFilter;
    }

    ANDROID_API void setColorFilter(SkColorFilter* filter);

    inline void setConvexMask(const SkPath* convexMask) {
        this->convexMask = convexMask;
    }

    inline const SkPath* getConvexMask() {
        return convexMask;
    }

    void bindStencilRenderBuffer() const;

    void bindTexture() const;
    void generateTexture();
    void allocateTexture();
    void deleteTexture();

    /**
     * When the caller frees the texture itself, the caller
     * must call this method to tell this layer that it lost
     * the texture.
     */
    ANDROID_API void clearTexture();

    inline mat4& getTexTransform() {
        return texTransform;
    }

    inline mat4& getTransform() {
        return transform;
    }

    void defer(const OpenGLRenderer& rootRenderer);
    void cancelDefer();
    void flush();
    void render(const OpenGLRenderer& rootRenderer);

    /**
     * Bounds of the layer.
     */
    Rect layer;
    /**
     * Texture coordinates of the layer.
     */
    Rect texCoords;
    /**
     * Clipping rectangle.
     */
    Rect clipRect;

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
    GLsizei meshElementCount;

    /**
     * Used for deferred updates.
     */
    bool deferredUpdateScheduled;
    OpenGLRenderer* renderer;
    sp<RenderNode> renderNode;
    Rect dirtyRect;
    bool debugDrawUpdate;
    bool hasDrawnSinceUpdate;

private:
    void requireRenderer();
    void updateLightPosFromRenderer(const OpenGLRenderer& rootRenderer);

    Caches& caches;

    RenderState& renderState;

    /**
     * Name of the FBO used to render the layer. If the name is 0
     * this layer is not backed by an FBO, but a simple texture.
     */
    GLuint fbo;

    /**
     * The render buffer used as the stencil buffer.
     */
    RenderBuffer* stencil;

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
     * When set to true, this layer is dirty and should be cleared
     * before any rendering occurs.
     */
    bool dirty;

    /**
     * Indicates the render target.
     */
    GLenum renderTarget;

    /**
     * Color filter used to draw this layer. Optional.
     */
    SkColorFilter* colorFilter;

    /**
     * Indicates raster data backing the layer is scaled, requiring filtration.
     */
    bool forceFilter;

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

    /**
     * Optional transform.
     */
    mat4 transform;

    /**
     * Cached transform of layer in window, updated only on creation / resize
     */
    mat4 cachedInvTransformInWindow;
    bool rendererLightPosDirty;

    /**
     * Used to defer display lists when the layer is updated with a
     * display list.
     */
    DeferredDisplayList* deferredList;

    /**
     * This convex path should be used to mask the layer's draw to the screen.
     *
     * Data not owned/managed by layer object.
     */
    const SkPath* convexMask;

}; // struct Layer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_LAYER_H

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
#include <utils/RefBase.h>
#include <memory>

#include <GLES2/gl2.h>
#include <GpuMemoryTracker.h>

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
class RenderNode;
class RenderState;
class OpenGLRenderer;
class DeferredDisplayList;
struct DeferStateStruct;

/**
 * A layer has dimensions and is backed by an OpenGL texture or FBO.
 */
class Layer : public VirtualLightRefBase, GpuMemoryTracker {
public:
    enum class Type {
        Texture,
        DisplayList,
    };

    // layer lifecycle, controlled from outside
    enum class State {
        Uncached = 0,
        InCache = 1,
        FailedToCache = 2,
        RemovedFromCache = 3,
        DeletedFromCache = 4,
        InGarbageList = 5,
    };
    State state; // public for logging/debugging purposes

    Layer(Type type, RenderState& renderState, uint32_t layerWidth, uint32_t layerHeight);
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

        const float texX = 1.0f / float(texture.mWidth);
        const float texY = 1.0f / float(texture.mHeight);
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
        return texture.mWidth;
    }

    inline uint32_t getHeight() const {
        return texture.mHeight;
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
        texture.updateSize(width, height, texture.format());
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

    inline GLuint getTextureId() const {
        return texture.id();
    }

    inline Texture& getTexture() {
        return texture;
    }

    inline GLenum getRenderTarget() const {
        return renderTarget;
    }

    inline void setRenderTarget(GLenum renderTarget) {
        this->renderTarget = renderTarget;
    }

    inline bool isRenderable() const {
        return renderTarget != GL_NONE;
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
        return type == Type::Texture;
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
     * Posts a decStrong call to the appropriate thread.
     * Thread-safe.
     */
    void postDecStrong();

    /**
     * Lost the GL context but the layer is still around, mark it invalid internally
     * so the dtor knows not to do any GL work
     */
    void onGlContextLost();

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
    TextureVertex* mesh = nullptr;
    GLsizei meshElementCount = 0;

    /**
     * Used for deferred updates.
     */
    bool deferredUpdateScheduled = false;
    std::unique_ptr<OpenGLRenderer> renderer;
    sp<RenderNode> renderNode;
    Rect dirtyRect;
    bool debugDrawUpdate = false;
    bool hasDrawnSinceUpdate = false;
    bool wasBuildLayered = false;

private:
    void requireRenderer();
    void updateLightPosFromRenderer(const OpenGLRenderer& rootRenderer);

    Caches& caches;

    RenderState& renderState;

    /**
     * Name of the FBO used to render the layer. If the name is 0
     * this layer is not backed by an FBO, but a simple texture.
     */
    GLuint fbo = 0;

    /**
     * The render buffer used as the stencil buffer.
     */
    RenderBuffer* stencil = nullptr;

    /**
     * Indicates whether this layer has been used already.
     */
    bool empty = true;

    /**
     * The texture backing this layer.
     */
    Texture texture;

    /**
     * If set to true (by default), the layer can be reused.
     */
    bool cacheable = true;

    /**
     * Denotes whether the layer is a DisplayList, or Texture layer.
     */
    const Type type;

    /**
     * When set to true, this layer is dirty and should be cleared
     * before any rendering occurs.
     */
    bool dirty = false;

    /**
     * Indicates the render target.
     */
    GLenum renderTarget = GL_TEXTURE_2D;

    /**
     * Color filter used to draw this layer. Optional.
     */
    SkColorFilter* colorFilter = nullptr;

    /**
     * Indicates raster data backing the layer is scaled, requiring filtration.
     */
    bool forceFilter = false;

    /**
     * Opacity of the layer.
     */
    int alpha = 255;

    /**
     * Blending mode of the layer.
     */
    SkXfermode::Mode mode = SkXfermode::kSrcOver_Mode;

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
    bool rendererLightPosDirty = true;

    /**
     * Used to defer display lists when the layer is updated with a
     * display list.
     */
    std::unique_ptr<DeferredDisplayList> deferredList;

    /**
     * This convex path should be used to mask the layer's draw to the screen.
     *
     * Data not owned/managed by layer object.
     */
    const SkPath* convexMask = nullptr;

}; // struct Layer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_LAYER_H

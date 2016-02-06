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

#ifndef ANDROID_HWUI_OPENGL_RENDERER_H
#define ANDROID_HWUI_OPENGL_RENDERER_H

#include "CanvasState.h"
#include "Debug.h"
#include "Extensions.h"
#include "Matrix.h"
#include "Program.h"
#include "Rect.h"
#include "Snapshot.h"
#include "UvMapper.h"
#include "Vertex.h"
#include "Caches.h"
#include "utils/PaintUtils.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkColorFilter.h>
#include <SkDrawLooper.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkRegion.h>
#include <SkXfermode.h>

#include <utils/Blur.h>
#include <utils/Functor.h>
#include <utils/RefBase.h>
#include <utils/SortedVector.h>

#include <cutils/compiler.h>

#include <androidfw/ResourceTypes.h>

#include <vector>

class SkShader;

namespace android {
namespace uirenderer {

enum class DrawOpMode {
    kImmediate,
    kDefer,
    kFlush
};

class DeferredDisplayState;
struct Glop;
class RenderState;
class RenderNode;
class TextDrawFunctor;
class VertexBuffer;

enum StateDeferFlags {
    kStateDeferFlag_Draw = 0x1,
    kStateDeferFlag_Clip = 0x2
};

enum ClipSideFlags {
    kClipSide_None = 0x0,
    kClipSide_Left = 0x1,
    kClipSide_Top = 0x2,
    kClipSide_Right = 0x4,
    kClipSide_Bottom = 0x8,
    kClipSide_Full = 0xF,
    kClipSide_ConservativeFull = 0x1F
};

enum VertexBufferDisplayFlags {
    kVertexBuffer_Offset = 0x1,
    kVertexBuffer_ShadowInterp = 0x2,
};

/**
 * Defines additional transformation that should be applied by the model view matrix, beyond that of
 * the currentTransform()
 */
enum ModelViewMode {
    /**
     * Used when the model view should simply translate geometry passed to the shader. The resulting
     * matrix will be a simple translation.
     */
    kModelViewMode_Translate = 0,

    /**
     * Used when the model view should translate and scale geometry. The resulting matrix will be a
     * translation + scale. This is frequently used together with VBO 0, the (0,0,1,1) rect.
     */
    kModelViewMode_TranslateAndScale = 1,
};

///////////////////////////////////////////////////////////////////////////////
// Renderer
///////////////////////////////////////////////////////////////////////////////
/**
 * OpenGL Renderer implementation.
 */
class OpenGLRenderer : public CanvasStateClient {
public:
    OpenGLRenderer(RenderState& renderState);
    virtual ~OpenGLRenderer();

    void initProperties();
    void initLight(float lightRadius, uint8_t ambientShadowAlpha,
            uint8_t spotShadowAlpha);
    void setLightCenter(const Vector3& lightCenter);

    /*
     * Prepares the renderer to draw a frame. This method must be invoked
     * at the beginning of each frame. Only the specified rectangle of the
     * frame is assumed to be dirty. A clip will automatically be set to
     * the specified rectangle.
     *
     * @param opaque If true, the target surface is considered opaque
     *               and will not be cleared. If false, the target surface
     *               will be cleared
     */
    virtual void prepareDirty(int viewportWidth, int viewportHeight,
            float left, float top, float right, float bottom, bool opaque);

    /**
     * Indicates the end of a frame. This method must be invoked whenever
     * the caller is done rendering a frame.
     * Returns true if any drawing was done during the frame (the output
     * has changed / is "dirty" and should be displayed to the user).
     */
    virtual bool finish();

    void callDrawGLFunction(Functor* functor, Rect& dirty);

    void pushLayerUpdate(Layer* layer);
    void cancelLayerUpdate(Layer* layer);
    void flushLayerUpdates();
    void markLayersAsBuildLayers();

    virtual int saveLayer(float left, float top, float right, float bottom,
            const SkPaint* paint, int flags) {
        return saveLayer(left, top, right, bottom, paint, flags, nullptr);
    }

    // Specialized saveLayer implementation, which will pass the convexMask to an FBO layer, if
    // created, which will in turn clip to that mask when drawn back/restored.
    int saveLayer(float left, float top, float right, float bottom,
            const SkPaint* paint, int flags, const SkPath* convexMask);

    int saveLayerDeferred(float left, float top, float right, float bottom,
            const SkPaint* paint, int flags);

    void drawRenderNode(RenderNode* displayList, Rect& dirty, int32_t replayFlags = 1);
    void drawLayer(Layer* layer);
    void drawBitmap(const SkBitmap* bitmap, const SkPaint* paint);
    void drawBitmaps(const SkBitmap* bitmap, AssetAtlas::Entry* entry, int bitmapCount,
            TextureVertex* vertices, bool pureTranslate, const Rect& bounds, const SkPaint* paint);
    void drawBitmap(const SkBitmap* bitmap, Rect src, Rect dst,
            const SkPaint* paint);
    void drawBitmapMesh(const SkBitmap* bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint);
    void drawPatches(const SkBitmap* bitmap, AssetAtlas::Entry* entry,
            TextureVertex* vertices, uint32_t indexCount, const SkPaint* paint);
    void drawPatch(const SkBitmap* bitmap, const Patch* mesh, AssetAtlas::Entry* entry,
            float left, float top, float right, float bottom, const SkPaint* paint);
    void drawColor(int color, SkXfermode::Mode mode);
    void drawRect(float left, float top, float right, float bottom,
            const SkPaint* paint);
    void drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint* paint);
    void drawCircle(float x, float y, float radius, const SkPaint* paint);
    void drawOval(float left, float top, float right, float bottom,
            const SkPaint* paint);
    void drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, const SkPaint* paint);
    void drawPath(const SkPath* path, const SkPaint* paint);
    void drawLines(const float* points, int count, const SkPaint* paint);
    void drawPoints(const float* points, int count, const SkPaint* paint);
    void drawTextOnPath(const glyph_t* glyphs, int bytesCount, int count, const SkPath* path,
            float hOffset, float vOffset, const SkPaint* paint);
    void drawText(const glyph_t* glyphs, int bytesCount, int count, float x, float y,
            const float* positions, const SkPaint* paint, float totalAdvance, const Rect& bounds,
            DrawOpMode drawOpMode = DrawOpMode::kImmediate);
    void drawRects(const float* rects, int count, const SkPaint* paint);

    void drawShadow(float casterAlpha,
            const VertexBuffer* ambientShadowVertexBuffer,
            const VertexBuffer* spotShadowVertexBuffer);

    void setDrawFilter(SkDrawFilter* filter);

    /**
     * Store the current display state (most importantly, the current clip and transform), and
     * additionally map the state's bounds from local to window coordinates.
     *
     * Returns true if quick-rejected
     */
    bool storeDisplayState(DeferredDisplayState& state, int stateDeferFlags);
    void restoreDisplayState(const DeferredDisplayState& state, bool skipClipRestore = false);
    void setupMergedMultiDraw(const Rect* clipRect);

    bool isCurrentTransformSimple() {
        return currentTransform()->isSimple();
    }

    Caches& getCaches() {
        return mCaches;
    }

    RenderState& renderState() {
        return mRenderState;
    }

    int getViewportWidth() { return mState.getViewportWidth(); }
    int getViewportHeight() { return mState.getViewportHeight(); }

    /**
     * Scales the alpha on the current snapshot. This alpha value will be modulated
     * with other alpha values when drawing primitives.
     */
    void scaleAlpha(float alpha) { mState.scaleAlpha(alpha); }

    /**
     * Inserts a named event marker in the stream of GL commands.
     */
    void eventMark(const char* name) const;

    /**
     * Inserts a formatted event marker in the stream of GL commands.
     */
    void eventMarkDEBUG(const char *fmt, ...) const;

    /**
     * Inserts a named group marker in the stream of GL commands. This marker
     * can be used by tools to group commands into logical groups. A call to
     * this method must always be followed later on by a call to endMark().
     */
    void startMark(const char* name) const;

    /**
     * Closes the last group marker opened by startMark().
     */
    void endMark() const;

    /**
     * Build the best transform to use to rasterize text given a full
     * transform matrix, and whether filteration is needed.
     *
     * Returns whether filtration is needed
     */
    bool findBestFontTransform(const mat4& transform, SkMatrix* outMatrix) const;

#if DEBUG_MERGE_BEHAVIOR
    void drawScreenSpaceColorRect(float left, float top, float right, float bottom, int color) {
        mCaches.setScissorEnabled(false);

        // should only be called outside of other draw ops, so stencil can only be in test state
        bool stencilWasEnabled = mCaches.stencil.isTestEnabled();
        mCaches.stencil.disable();

        drawColorRect(left, top, right, bottom, color, SkXfermode::kSrcOver_Mode, true);

        if (stencilWasEnabled) mCaches.stencil.enableTest();
        mDirty = true;
    }
#endif

    const Vector3& getLightCenter() const { return mState.currentLightCenter(); }
    float getLightRadius() const { return mLightRadius; }
    uint8_t getAmbientShadowAlpha() const { return mAmbientShadowAlpha; }
    uint8_t getSpotShadowAlpha() const { return mSpotShadowAlpha; }

    ///////////////////////////////////////////////////////////////////
    /// State manipulation

    int getSaveCount() const;
    int save(int flags);
    void restore();
    void restoreToCount(int saveCount);

    void setGlobalMatrix(const Matrix4& matrix) {
        mState.setMatrix(matrix);
    }
    void setLocalMatrix(const Matrix4& matrix);
    void setLocalMatrix(const SkMatrix& matrix);
    void concatMatrix(const SkMatrix& matrix) { mState.concatMatrix(matrix); }

    void translate(float dx, float dy, float dz = 0.0f);
    void rotate(float degrees);
    void scale(float sx, float sy);
    void skew(float sx, float sy);

    void setMatrix(const Matrix4& matrix); // internal only convenience method
    void concatMatrix(const Matrix4& matrix); // internal only convenience method

    const Rect& getLocalClipBounds() const { return mState.getLocalClipBounds(); }
    const Rect& getRenderTargetClipBounds() const { return mState.getRenderTargetClipBounds(); }
    bool quickRejectConservative(float left, float top,
            float right, float bottom) const {
        return mState.quickRejectConservative(left, top, right, bottom);
    }

    bool clipRect(float left, float top,
            float right, float bottom, SkRegion::Op op);
    bool clipPath(const SkPath* path, SkRegion::Op op);
    bool clipRegion(const SkRegion* region, SkRegion::Op op);

    /**
     * Does not support different clipping Ops (that is, every call to setClippingOutline is
     * effectively using SkRegion::kReplaceOp)
     *
     * The clipping outline is independent from the regular clip.
     */
    void setClippingOutline(LinearAllocator& allocator, const Outline* outline);
    void setClippingRoundRect(LinearAllocator& allocator,
            const Rect& rect, float radius, bool highPriority = true);
    void setProjectionPathMask(LinearAllocator& allocator, const SkPath* path);

    inline bool hasRectToRectTransform() const { return mState.hasRectToRectTransform(); }
    inline const mat4* currentTransform() const { return mState.currentTransform(); }

    ///////////////////////////////////////////////////////////////////
    /// CanvasStateClient interface

    virtual void onViewportInitialized() override;
    virtual void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) override;
    virtual GLuint getTargetFbo() const override { return 0; }

    SkPath* allocPathForFrame() {
        std::unique_ptr<SkPath> path(new SkPath());
        SkPath* returnPath = path.get();
        mTempPaths.push_back(std::move(path));
        return returnPath;
    }

    void setBaseTransform(const Matrix4& matrix) { mBaseTransform = matrix; }

protected:
    /**
     * Perform the setup specific to a frame. This method does not
     * issue any OpenGL commands.
     */
    void setupFrameState(int viewportWidth, int viewportHeight,
            float left, float top, float right, float bottom, bool opaque);

    /**
     * Indicates the start of rendering. This method will setup the
     * initial OpenGL state (viewport, clearing the buffer, etc.)
     */
    void startFrame();

    /**
     * Clears the underlying surface if needed.
     */
    virtual void clear(float left, float top, float right, float bottom, bool opaque);

    /**
     * Call this method after updating a layer during a drawing pass.
     */
    void resumeAfterLayer();

    /**
     * This method is called whenever a stencil buffer is required. Subclasses
     * should override this method and call attachStencilBufferToLayer() on the
     * appropriate layer(s).
     */
    virtual void ensureStencilBuffer();

    /**
     * Obtains a stencil render buffer (allocating it if necessary) and
     * attaches it to the specified layer.
     */
    void attachStencilBufferToLayer(Layer* layer);

    /**
     * Draw a rectangle list. Currently only used for the the stencil buffer so that the stencil
     * will have a value of 'n' in every unclipped pixel, where 'n' is the number of rectangles
     * in the list.
     */
    void drawRectangleList(const RectangleList& rectangleList);

    bool quickRejectSetupScissor(float left, float top, float right, float bottom,
            const SkPaint* paint = nullptr);
    bool quickRejectSetupScissor(const Rect& bounds, const SkPaint* paint = nullptr) {
        return quickRejectSetupScissor(bounds.left, bounds.top,
                bounds.right, bounds.bottom, paint);
    }

    /**
     * Compose the layer defined in the current snapshot with the layer
     * defined by the previous snapshot.
     *
     * The current snapshot *must* be a layer (flag kFlagIsLayer set.)
     *
     * @param curent The current snapshot containing the layer to compose
     * @param previous The previous snapshot to compose the current layer with
     */
    virtual void composeLayer(const Snapshot& current, const Snapshot& previous);

    /**
     * Marks the specified region as dirty at the specified bounds.
     */
    void dirtyLayerUnchecked(Rect& bounds, Region* region);

    /**
     * Returns the region of the current layer.
     */
    virtual Region* getRegion() const {
        return mState.currentRegion();
    }

    /**
     * Indicates whether rendering is currently targeted at a layer.
     */
    virtual bool hasLayer() const {
        return (mState.currentFlags() & Snapshot::kFlagFboTarget) && mState.currentRegion();
    }

    /**
     * Renders the specified layer as a textured quad.
     *
     * @param layer The layer to render
     * @param rect The bounds of the layer
     */
    void drawTextureLayer(Layer* layer, const Rect& rect);

    /**
     * Gets the alpha from a layer, accounting for snapshot alpha
     *
     * @param layer The layer from which the alpha is extracted
     */
    inline float getLayerAlpha(const Layer* layer) const;

    /**
     * Set to true to suppress error checks at the end of a frame.
     */
    virtual bool suppressErrorChecks() const {
        return false;
    }

    CanvasState mState;
    Caches& mCaches;
    RenderState& mRenderState;

private:
    enum class GlopRenderType {
        Standard,
        Multi,
        LayerClear
    };

    void renderGlop(const Glop& glop, GlopRenderType type = GlopRenderType::Standard);

    /**
     * Discards the content of the framebuffer if supported by the driver.
     * This method should be called at the beginning of a frame to optimize
     * rendering on some tiler architectures.
     */
    void discardFramebuffer(float left, float top, float right, float bottom);

    /**
     * Sets the clipping rectangle using glScissor. The clip is defined by
     * the current snapshot's clipRect member.
     */
    void setScissorFromClip();

    /**
     * Sets the clipping region using the stencil buffer. The clip region
     * is defined by the current snapshot's clipRegion member.
     */
    void setStencilFromClip();

    /**
     * Given the local bounds of the layer, calculates ...
     */
    void calculateLayerBoundsAndClip(Rect& bounds, Rect& clip, bool fboLayer);

    /**
     * Given the local bounds + clip of the layer, updates current snapshot's empty/invisible
     */
    void updateSnapshotIgnoreForLayer(const Rect& bounds, const Rect& clip,
            bool fboLayer, int alpha);

    /**
     * Creates a new layer stored in the specified snapshot.
     *
     * @param snapshot The snapshot associated with the new layer
     * @param left The left coordinate of the layer
     * @param top The top coordinate of the layer
     * @param right The right coordinate of the layer
     * @param bottom The bottom coordinate of the layer
     * @param alpha The translucency of the layer
     * @param mode The blending mode of the layer
     * @param flags The layer save flags
     * @param mask A mask to use when drawing the layer back, may be empty
     *
     * @return True if the layer was successfully created, false otherwise
     */
    bool createLayer(float left, float top, float right, float bottom,
            const SkPaint* paint, int flags, const SkPath* convexMask);

    /**
     * Creates a new layer stored in the specified snapshot as an FBO.
     *
     * @param layer The layer to store as an FBO
     * @param snapshot The snapshot associated with the new layer
     * @param bounds The bounds of the layer
     */
    bool createFboLayer(Layer* layer, Rect& bounds, Rect& clip);

    /**
     * Compose the specified layer as a region.
     *
     * @param layer The layer to compose
     * @param rect The layer's bounds
     */
    void composeLayerRegion(Layer* layer, const Rect& rect);

    /**
     * Restores the content in layer to the screen, swapping the blend mode,
     * specifically used in the restore() of a saveLayerAlpha().
     *
     * This allows e.g. a layer that would have been drawn on top of existing content (with SrcOver)
     * to be drawn underneath.
     *
     * This will always ignore the canvas transform.
     */
    void composeLayerRectSwapped(Layer* layer, const Rect& rect);

    /**
     * Draws the content in layer to the screen.
     */
    void composeLayerRect(Layer* layer, const Rect& rect);

    /**
     * Clears all the regions corresponding to the current list of layers.
     * This method MUST be invoked before any drawing operation.
     */
    void clearLayerRegions();

    /**
     * Mark the layer as dirty at the specified coordinates. The coordinates
     * are transformed with the supplied matrix.
     */
    void dirtyLayer(const float left, const float top,
            const float right, const float bottom, const Matrix4& transform);

    /**
     * Mark the layer as dirty at the specified coordinates.
     */
    void dirtyLayer(const float left, const float top,
            const float right, const float bottom);

    /**
     * Draws a colored rectangle with the specified color. The specified coordinates
     * are transformed by the current snapshot's transform matrix unless specified
     * otherwise.
     *
     * @param left The left coordinate of the rectangle
     * @param top The top coordinate of the rectangle
     * @param right The right coordinate of the rectangle
     * @param bottom The bottom coordinate of the rectangle
     * @param paint The paint containing the color, blending mode, etc.
     * @param ignoreTransform True if the current transform should be ignored
     */
    void drawColorRect(float left, float top, float right, float bottom,
            const SkPaint* paint, bool ignoreTransform = false);

    /**
     * Draws a series of colored rectangles with the specified color. The specified
     * coordinates are transformed by the current snapshot's transform matrix unless
     * specified otherwise.
     *
     * @param rects A list of rectangles, 4 floats (left, top, right, bottom)
     *              per rectangle
     * @param paint The paint containing the color, blending mode, etc.
     * @param ignoreTransform True if the current transform should be ignored
     * @param dirty True if calling this method should dirty the current layer
     * @param clip True if the rects should be clipped, false otherwise
     */
    void drawColorRects(const float* rects, int count, const SkPaint* paint,
            bool ignoreTransform = false, bool dirty = true, bool clip = true);

    /**
     * Draws the shape represented by the specified path texture.
     * This method invokes drawPathTexture() but takes into account
     * the extra left/top offset and the texture offset to correctly
     * position the final shape.
     *
     * @param left The left coordinate of the shape to render
     * @param top The top coordinate of the shape to render
     * @param texture The texture reprsenting the shape
     * @param paint The paint to draw the shape with
     */
    void drawShape(float left, float top, PathTexture* texture, const SkPaint* paint);

    /**
     * Renders a strip of polygons with the specified paint, used for tessellated geometry.
     *
     * @param vertexBuffer The VertexBuffer to be drawn
     * @param paint The paint to render with
     * @param flags flags with which to draw
     */
    void drawVertexBuffer(float translateX, float translateY, const VertexBuffer& vertexBuffer,
            const SkPaint* paint, int flags = 0);

    /**
     * Convenience for translating method
     */
    void drawVertexBuffer(const VertexBuffer& vertexBuffer,
            const SkPaint* paint, int flags = 0) {
        drawVertexBuffer(0.0f, 0.0f, vertexBuffer, paint, flags);
    }

    /**
     * Renders the convex hull defined by the specified path as a strip of polygons.
     *
     * @param path The hull of the path to draw
     * @param paint The paint to render with
     */
    void drawConvexPath(const SkPath& path, const SkPaint* paint);

   /**
     * Draws shadow layer on text (with optional positions).
     *
     * @param paint The paint to draw the shadow with
     * @param text The text to draw
     * @param count The number of glyphs in the text
     * @param positions The x, y positions of individual glyphs (or NULL)
     * @param fontRenderer The font renderer object
     * @param alpha The alpha value for drawing the shadow
     * @param x The x coordinate where the shadow will be drawn
     * @param y The y coordinate where the shadow will be drawn
     */
    void drawTextShadow(const SkPaint* paint, const glyph_t* glyphs, int count,
            const float* positions, FontRenderer& fontRenderer, int alpha,
            float x, float y);

    /**
     * Draws a path texture. Path textures are alpha8 bitmaps that need special
     * compositing to apply colors/filters/etc.
     *
     * @param texture The texture to render
     * @param x The x coordinate where the texture will be drawn
     * @param y The y coordinate where the texture will be drawn
     * @param paint The paint to draw the texture with
     */
     void drawPathTexture(PathTexture* texture, float x, float y, const SkPaint* paint);

    /**
     * Resets the texture coordinates stored in mMeshVertices. Setting the values
     * back to default is achieved by calling:
     *
     * resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
     *
     * @param u1 The left coordinate of the texture
     * @param v1 The bottom coordinate of the texture
     * @param u2 The right coordinate of the texture
     * @param v2 The top coordinate of the texture
     */
    void resetDrawTextureTexCoords(float u1, float v1, float u2, float v2);

    /**
     * Returns true if the specified paint will draw invisible text.
     */
    bool canSkipText(const SkPaint* paint) const;

    bool updateLayer(Layer* layer, bool inFrame);
    void updateLayers();
    void flushLayers();

#if DEBUG_LAYERS_AS_REGIONS
    /**
     * Renders the specified region as a series of rectangles. This method
     * is used for debugging only.
     */
    void drawRegionRectsDebug(const Region& region);
#endif

    /**
     * Renders the specified region as a series of rectangles. The region
     * must be in screen-space coordinates.
     */
    void drawRegionRects(const SkRegion& region, const SkPaint& paint, bool dirty = false);

    /**
     * Draws the current clip region if any. Only when DEBUG_CLIP_REGIONS
     * is turned on.
     */
    void debugClip();

    void debugOverdraw(bool enable, bool clear);
    void renderOverdraw();
    void countOverdraw();

    /**
     * Should be invoked every time the glScissor is modified.
     */
    inline void dirtyClip() { mState.setDirtyClip(true); }

    inline const UvMapper& getMapper(const Texture* texture) {
        return texture && texture->uvMapper ? *texture->uvMapper : mUvMapper;
    }

    /**
     * Returns a texture object for the specified bitmap. The texture can
     * come from the texture cache or an atlas. If this method returns
     * NULL, the texture could not be found and/or allocated.
     */
    Texture* getTexture(const SkBitmap* bitmap);

    bool reportAndClearDirty() { bool ret = mDirty; mDirty = false; return ret; }
    inline Snapshot* writableSnapshot() { return mState.writableSnapshot(); }
    inline const Snapshot* currentSnapshot() const { return mState.currentSnapshot(); }

    // State used to define the clipping region
    Rect mTilingClip;
    // Is the target render surface opaque
    bool mOpaque;
    // Is a frame currently being rendered
    bool mFrameStarted;

    // Default UV mapper
    const UvMapper mUvMapper;

    // List of rectangles to clear after saveLayer() is invoked
    std::vector<Rect> mLayers;
    // List of layers to update at the beginning of a frame
    std::vector< sp<Layer> > mLayerUpdates;

    // See PROPERTY_DISABLE_SCISSOR_OPTIMIZATION in
    // Properties.h
    bool mScissorOptimizationDisabled;

    bool mSkipOutlineClip;

    // True if anything has been drawn since the last call to
    // reportAndClearDirty()
    bool mDirty;

    // Lighting + shadows
    Vector3 mLightCenter;
    float mLightRadius;
    uint8_t mAmbientShadowAlpha;
    uint8_t mSpotShadowAlpha;

    // Paths kept alive for the duration of the frame
    std::vector<std::unique_ptr<SkPath>> mTempPaths;

    /**
     * Initial transform for a rendering pass; transform from global device
     * coordinates to the current RenderNode's drawing content coordinates,
     * with the RenderNode's RenderProperty transforms already applied.
     * Calling setMatrix(mBaseTransform) will result in drawing at the origin
     * of the DisplayList's recorded surface prior to any Canvas
     * transformation.
     */
    Matrix4 mBaseTransform;

    friend class Layer;
    friend class TextDrawFunctor;
    friend class DrawBitmapOp;
    friend class DrawPatchOp;

}; // class OpenGLRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_OPENGL_RENDERER_H

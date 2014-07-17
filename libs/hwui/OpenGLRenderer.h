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

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <SkBitmap.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkRegion.h>
#include <SkShader.h>
#include <SkXfermode.h>

#include <utils/Functor.h>
#include <utils/RefBase.h>
#include <utils/SortedVector.h>
#include <utils/Vector.h>

#include <cutils/compiler.h>

#include <androidfw/ResourceTypes.h>

#include "Debug.h"
#include "Extensions.h"
#include "Matrix.h"
#include "Program.h"
#include "Rect.h"
#include "Snapshot.h"
#include "Vertex.h"
#include "SkiaShader.h"
#include "SkiaColorFilter.h"
#include "UvMapper.h"
#include "Caches.h"

namespace android {
namespace uirenderer {

struct DrawModifiers {
    DrawModifiers() {
        reset();
    }

    void reset() {
        memset(this, 0, sizeof(DrawModifiers));
    }

    SkiaShader* mShader;
    SkiaColorFilter* mColorFilter;
    float mOverrideLayerAlpha;

    // Drop shadow
    bool mHasShadow;
    float mShadowRadius;
    float mShadowDx;
    float mShadowDy;
    int mShadowColor;

    // Draw filters
    bool mHasDrawFilter;
    int mPaintFilterClearBits;
    int mPaintFilterSetBits;
};

enum StateDeferFlags {
    kStateDeferFlag_Draw = 0x1,
    kStateDeferFlag_Clip = 0x2
};

enum DrawOpMode {
    kDrawOpMode_Immediate,
    kDrawOpMode_Defer,
    kDrawOpMode_Flush
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

///////////////////////////////////////////////////////////////////////////////
// Renderer
///////////////////////////////////////////////////////////////////////////////

class DeferredDisplayState;
class DisplayList;
class TextSetupFunctor;
class VertexBuffer;

/**
 * OpenGL renderer used to draw accelerated 2D graphics. The API is a
 * simplified version of Skia's Canvas API.
 */
class OpenGLRenderer {
public:
    ANDROID_API OpenGLRenderer();
    virtual ~OpenGLRenderer();

    /**
     * Sets the name of this renderer. The name is optional and
     * empty by default. If the pointer is null the name is set
     * to the empty string.
     */
    ANDROID_API void setName(const char* name);

    /**
     * Returns the name of this renderer as UTF8 string.
     * The returned pointer is never null.
     */
    ANDROID_API const char* getName() const;

    /**
     * Read externally defined properties to control the behavior
     * of the renderer.
     */
    ANDROID_API void initProperties();

    /**
     * Indicates whether this renderer executes drawing commands immediately.
     * If this method returns true, the drawing commands will be executed
     * later.
     */
    virtual bool isDeferred();

    /**
     * Sets the dimension of the underlying drawing surface. This method must
     * be called at least once every time the drawing surface changes size.
     *
     * @param width The width in pixels of the underlysing surface
     * @param height The height in pixels of the underlysing surface
     */
    virtual void setViewport(int width, int height);

    /**
     * Prepares the renderer to draw a frame. This method must be invoked
     * at the beginning of each frame. When this method is invoked, the
     * entire drawing surface is assumed to be redrawn.
     *
     * @param opaque If true, the target surface is considered opaque
     *               and will not be cleared. If false, the target surface
     *               will be cleared
     */
    ANDROID_API status_t prepare(bool opaque);

    /**
     * Prepares the renderer to draw a frame. This method must be invoked
     * at the beginning of each frame. Only the specified rectangle of the
     * frame is assumed to be dirty. A clip will automatically be set to
     * the specified rectangle.
     *
     * @param left The left coordinate of the dirty rectangle
     * @param top The top coordinate of the dirty rectangle
     * @param right The right coordinate of the dirty rectangle
     * @param bottom The bottom coordinate of the dirty rectangle
     * @param opaque If true, the target surface is considered opaque
     *               and will not be cleared. If false, the target surface
     *               will be cleared in the specified dirty rectangle
     */
    virtual status_t prepareDirty(float left, float top, float right, float bottom, bool opaque);

    /**
     * Indicates the end of a frame. This method must be invoked whenever
     * the caller is done rendering a frame.
     */
    virtual void finish();

    /**
     * This method must be invoked before handing control over to a draw functor.
     * See callDrawGLFunction() for instance.
     *
     * This command must not be recorded inside display lists.
     */
    virtual void interrupt();

    /**
     * This method must be invoked after getting control back from a draw functor.
     *
     * This command must not be recorded inside display lists.
     */
    virtual void resume();

    ANDROID_API void setCountOverdrawEnabled(bool enabled) {
        mCountOverdraw = enabled;
    }

    ANDROID_API float getOverdraw() {
        return mCountOverdraw ? mOverdraw : 0.0f;
    }

    ANDROID_API status_t invokeFunctors(Rect& dirty);
    ANDROID_API void detachFunctor(Functor* functor);
    ANDROID_API void attachFunctor(Functor* functor);
    virtual status_t callDrawGLFunction(Functor* functor, Rect& dirty);

    ANDROID_API void pushLayerUpdate(Layer* layer);
    ANDROID_API void cancelLayerUpdate(Layer* layer);
    ANDROID_API void clearLayerUpdates();
    ANDROID_API void flushLayerUpdates();

    ANDROID_API int getSaveCount() const;
    virtual int save(int flags);
    virtual void restore();
    virtual void restoreToCount(int saveCount);

    ANDROID_API int saveLayer(float left, float top, float right, float bottom,
            SkPaint* paint, int flags) {
        SkXfermode::Mode mode = SkXfermode::kSrcOver_Mode;
        if (paint) mode = getXfermode(paint->getXfermode());
        return saveLayer(left, top, right, bottom, paint ? paint->getAlpha() : 255, mode, flags);
    }
    ANDROID_API int saveLayerAlpha(float left, float top, float right, float bottom,
            int alpha, int flags) {
        return saveLayer(left, top, right, bottom, alpha, SkXfermode::kSrcOver_Mode, flags);
    }
    virtual int saveLayer(float left, float top, float right, float bottom,
            int alpha, SkXfermode::Mode mode, int flags);

    int saveLayerDeferred(float left, float top, float right, float bottom,
            int alpha, SkXfermode::Mode mode, int flags);

    virtual void translate(float dx, float dy);
    virtual void rotate(float degrees);
    virtual void scale(float sx, float sy);
    virtual void skew(float sx, float sy);

    bool hasRectToRectTransform();
    ANDROID_API void getMatrix(SkMatrix* matrix);
    virtual void setMatrix(SkMatrix* matrix);
    virtual void concatMatrix(SkMatrix* matrix);

    ANDROID_API const Rect& getClipBounds();

    /**
     * Performs a quick reject but adjust the bounds to account for stroke width if necessary,
     * and handling snapOut for AA geometry.
     */
    bool quickRejectPreStroke(float left, float top, float right, float bottom, SkPaint* paint);

    /**
     * Returns false and sets scissor based upon bounds if drawing won't be clipped out
     */
    bool quickReject(float left, float top, float right, float bottom, bool snapOut = false);
    bool quickReject(const Rect& bounds) {
        return quickReject(bounds.left, bounds.top, bounds.right, bounds.bottom);
    }

    /**
     * Same as quickReject, without the scissor, instead returning clipRequired through pointer.
     * clipRequired will be only set if not rejected
     */
    ANDROID_API bool quickRejectNoScissor(float left, float top, float right, float bottom,
            bool snapOut = false, bool* clipRequired = NULL);
    bool quickRejectNoScissor(const Rect& bounds, bool* clipRequired = NULL) {
        return quickRejectNoScissor(bounds.left, bounds.top, bounds.right, bounds.bottom,
                clipRequired);
    }

    virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op);
    virtual bool clipPath(SkPath* path, SkRegion::Op op);
    virtual bool clipRegion(SkRegion* region, SkRegion::Op op);
    virtual Rect* getClipRect();

    virtual status_t drawDisplayList(DisplayList* displayList, Rect& dirty, int32_t replayFlags);
    virtual void outputDisplayList(DisplayList* displayList);
    virtual status_t drawLayer(Layer* layer, float x, float y);
    virtual status_t drawBitmap(SkBitmap* bitmap, float left, float top, SkPaint* paint);
    status_t drawBitmaps(SkBitmap* bitmap, AssetAtlas::Entry* entry, int bitmapCount,
            TextureVertex* vertices, bool pureTranslate, const Rect& bounds, SkPaint* paint);
    virtual status_t drawBitmap(SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint);
    virtual status_t drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, SkPaint* paint);
    virtual status_t drawBitmapData(SkBitmap* bitmap, float left, float top, SkPaint* paint);
    virtual status_t drawBitmapMesh(SkBitmap* bitmap, int meshWidth, int meshHeight,
            float* vertices, int* colors, SkPaint* paint);
    status_t drawPatches(SkBitmap* bitmap, AssetAtlas::Entry* entry,
            TextureVertex* vertices, uint32_t indexCount, SkPaint* paint);
    virtual status_t drawPatch(SkBitmap* bitmap, Res_png_9patch* patch,
            float left, float top, float right, float bottom, SkPaint* paint);
    status_t drawPatch(SkBitmap* bitmap, const Patch* mesh, AssetAtlas::Entry* entry,
            float left, float top, float right, float bottom, SkPaint* paint);
    virtual status_t drawColor(int color, SkXfermode::Mode mode);
    virtual status_t drawRect(float left, float top, float right, float bottom, SkPaint* paint);
    virtual status_t drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, SkPaint* paint);
    virtual status_t drawCircle(float x, float y, float radius, SkPaint* paint);
    virtual status_t drawOval(float left, float top, float right, float bottom, SkPaint* paint);
    virtual status_t drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, SkPaint* paint);
    virtual status_t drawPath(SkPath* path, SkPaint* paint);
    virtual status_t drawLines(float* points, int count, SkPaint* paint);
    virtual status_t drawPoints(float* points, int count, SkPaint* paint);
    virtual status_t drawTextOnPath(const char* text, int bytesCount, int count, SkPath* path,
            float hOffset, float vOffset, SkPaint* paint);
    virtual status_t drawPosText(const char* text, int bytesCount, int count,
            const float* positions, SkPaint* paint);
    virtual status_t drawText(const char* text, int bytesCount, int count, float x, float y,
            const float* positions, SkPaint* paint, float totalAdvance, const Rect& bounds,
            DrawOpMode drawOpMode = kDrawOpMode_Immediate);
    virtual status_t drawRects(const float* rects, int count, SkPaint* paint);

    virtual void resetShader();
    virtual void setupShader(SkiaShader* shader);

    virtual void resetColorFilter();
    virtual void setupColorFilter(SkiaColorFilter* filter);

    virtual void resetShadow();
    virtual void setupShadow(float radius, float dx, float dy, int color);

    virtual void resetPaintFilter();
    virtual void setupPaintFilter(int clearBits, int setBits);

    // If this value is set to < 1.0, it overrides alpha set on layer (see drawBitmap, drawLayer)
    void setOverrideLayerAlpha(float alpha) { mDrawModifiers.mOverrideLayerAlpha = alpha; }

    SkPaint* filterPaint(SkPaint* paint);

    /**
     * Store the current display state (most importantly, the current clip and transform), and
     * additionally map the state's bounds from local to window coordinates.
     *
     * Returns true if quick-rejected
     */
    bool storeDisplayState(DeferredDisplayState& state, int stateDeferFlags);
    void restoreDisplayState(const DeferredDisplayState& state, bool skipClipRestore = false);
    void setupMergedMultiDraw(const Rect* clipRect);

    const DrawModifiers& getDrawModifiers() { return mDrawModifiers; }
    void setDrawModifiers(const DrawModifiers& drawModifiers) { mDrawModifiers = drawModifiers; }

    ANDROID_API bool isCurrentTransformSimple() {
        return mSnapshot->transform->isSimple();
    }

    Caches& getCaches() {
        return mCaches;
    }

    // simple rect clip
    bool isCurrentClipSimple() {
        return mSnapshot->clipRegion->isEmpty();
    }

    int getViewportWidth() { return getSnapshot()->viewport.getWidth(); }
    int getViewportHeight() { return getSnapshot()->viewport.getHeight(); }

    /**
     * Scales the alpha on the current snapshot. This alpha value will be modulated
     * with other alpha values when drawing primitives.
     */
    void scaleAlpha(float alpha) {
        mSnapshot->alpha *= alpha;
    }

    /**
     * Inserts a named event marker in the stream of GL commands.
     */
    void eventMark(const char* name) const;

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
     * Gets the alpha and xfermode out of a paint object. If the paint is null
     * alpha will be 255 and the xfermode will be SRC_OVER. This method does
     * not multiply the paint's alpha by the current snapshot's alpha, and does
     * not replace the alpha with the overrideLayerAlpha
     *
     * @param paint The paint to extract values from
     * @param alpha Where to store the resulting alpha
     * @param mode Where to store the resulting xfermode
     */
    static inline void getAlphaAndModeDirect(SkPaint* paint, int* alpha, SkXfermode::Mode* mode) {
        *mode = getXfermodeDirect(paint);
        *alpha = getAlphaDirect(paint);
    }

    static inline SkXfermode::Mode getXfermodeDirect(SkPaint* paint) {
        if (!paint) return SkXfermode::kSrcOver_Mode;
        return getXfermode(paint->getXfermode());
    }

    static inline int getAlphaDirect(const SkPaint* paint) {
        if (!paint) return 255;
        return paint->getAlpha();
    }

    /**
     * Return the best transform to use to rasterize text given a full
     * transform matrix.
     */
    mat4 findBestFontTransform(const mat4& transform) const;

#if DEBUG_MERGE_BEHAVIOR
    void drawScreenSpaceColorRect(float left, float top, float right, float bottom, int color) {
        mCaches.setScissorEnabled(false);

        // should only be called outside of other draw ops, so stencil can only be in test state
        bool stencilWasEnabled = mCaches.stencil.isTestEnabled();
        mCaches.stencil.disable();

        drawColorRect(left, top, right, bottom, color, SkXfermode::kSrcOver_Mode, true);

        if (stencilWasEnabled) mCaches.stencil.enableTest();
    }
#endif

protected:
    /**
     * Computes the projection matrix, initialize the first snapshot
     * and stores the dimensions of the render target.
     */
    void initViewport(int width, int height);

    /**
     * Perform the setup specific to a frame. This method does not
     * issue any OpenGL commands.
     */
    void setupFrameState(float left, float top, float right, float bottom, bool opaque);

    /**
     * Indicates the start of rendering. This method will setup the
     * initial OpenGL state (viewport, clearing the buffer, etc.)
     */
    status_t startFrame();

    /**
     * Clears the underlying surface if needed.
     */
    virtual status_t clear(float left, float top, float right, float bottom, bool opaque);

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
     * Compose the layer defined in the current snapshot with the layer
     * defined by the previous snapshot.
     *
     * The current snapshot *must* be a layer (flag kFlagIsLayer set.)
     *
     * @param curent The current snapshot containing the layer to compose
     * @param previous The previous snapshot to compose the current layer with
     */
    virtual void composeLayer(sp<Snapshot> current, sp<Snapshot> previous);

    /**
     * Marks the specified region as dirty at the specified bounds.
     */
    void dirtyLayerUnchecked(Rect& bounds, Region* region);

    /**
     * Returns the current snapshot.
     */
    sp<Snapshot> getSnapshot() const {
        return mSnapshot;
    }

    /**
     * Returns the region of the current layer.
     */
    virtual Region* getRegion() const {
        return mSnapshot->region;
    }

    /**
     * Indicates whether rendering is currently targeted at a layer.
     */
    virtual bool hasLayer() const {
        return (mSnapshot->flags & Snapshot::kFlagFboTarget) && mSnapshot->region;
    }

    /**
     * Returns the name of the FBO this renderer is rendering into.
     */
    virtual GLint getTargetFbo() const {
        return 0;
    }

    /**
     * Renders the specified layer as a textured quad.
     *
     * @param layer The layer to render
     * @param rect The bounds of the layer
     */
    void drawTextureLayer(Layer* layer, const Rect& rect);

    /**
     * Gets the alpha and xfermode out of a paint object. If the paint is null
     * alpha will be 255 and the xfermode will be SRC_OVER. Accounts for both
     * snapshot alpha, and overrideLayerAlpha
     *
     * @param paint The paint to extract values from
     * @param alpha Where to store the resulting alpha
     * @param mode Where to store the resulting xfermode
     */
    inline void getAlphaAndMode(SkPaint* paint, int* alpha, SkXfermode::Mode* mode) const;

    /**
     * Gets the alpha from a layer, accounting for snapshot alpha and overrideLayerAlpha
     *
     * @param layer The layer from which the alpha is extracted
     */
    inline float getLayerAlpha(Layer* layer) const;

    /**
     * Safely retrieves the mode from the specified xfermode. If the specified
     * xfermode is null, the mode is assumed to be SkXfermode::kSrcOver_Mode.
     */
    static inline SkXfermode::Mode getXfermode(SkXfermode* mode) {
        SkXfermode::Mode resultMode;
        if (!SkXfermode::AsMode(mode, &resultMode)) {
            resultMode = SkXfermode::kSrcOver_Mode;
        }
        return resultMode;
    }

    /**
     * Set to true to suppress error checks at the end of a frame.
     */
    virtual bool suppressErrorChecks() const {
        return false;
    }

private:
    /**
     * Discards the content of the framebuffer if supported by the driver.
     * This method should be called at the beginning of a frame to optimize
     * rendering on some tiler architectures.
     */
    void discardFramebuffer(float left, float top, float right, float bottom);

    /**
     * Ensures the state of the renderer is the same as the state of
     * the GL context.
     */
    void syncState();

    /**
     * Tells the GPU what part of the screen is about to be redrawn.
     * This method will use the clip rect that we started drawing the
     * frame with.
     * This method needs to be invoked every time getTargetFbo() is
     * bound again.
     */
    void startTiling(const sp<Snapshot>& snapshot, bool opaque = false, bool expand = false);

    /**
     * Tells the GPU what part of the screen is about to be redrawn.
     * This method needs to be invoked every time getTargetFbo() is
     * bound again.
     */
    void startTiling(const Rect& clip, int windowHeight, bool opaque = false, bool expand = false);

    /**
     * Tells the GPU that we are done drawing the frame or that we
     * are switching to another render target.
     */
    void endTiling();

    /**
     * Saves the current state of the renderer as a new snapshot.
     * The new snapshot is saved in mSnapshot and the previous snapshot
     * is linked from mSnapshot->previous.
     *
     * @param flags The save flags; see SkCanvas for more information
     *
     * @return The new save count. This value can be passed to #restoreToCount()
     */
    int saveSnapshot(int flags);

    /**
     * Restores the current snapshot; mSnapshot becomes mSnapshot->previous.
     *
     * @return True if the clip was modified.
     */
    bool restoreSnapshot();

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
     * @param previousFbo The name of the current framebuffer
     *
     * @return True if the layer was successfully created, false otherwise
     */
    bool createLayer(float left, float top, float right, float bottom,
            int alpha, SkXfermode::Mode mode, int flags, GLuint previousFbo);

    /**
     * Creates a new layer stored in the specified snapshot as an FBO.
     *
     * @param layer The layer to store as an FBO
     * @param snapshot The snapshot associated with the new layer
     * @param bounds The bounds of the layer
     * @param previousFbo The name of the current framebuffer
     */
    bool createFboLayer(Layer* layer, Rect& bounds, Rect& clip, GLuint previousFbo);

    /**
     * Compose the specified layer as a region.
     *
     * @param layer The layer to compose
     * @param rect The layer's bounds
     */
    void composeLayerRegion(Layer* layer, const Rect& rect);

    /**
     * Compose the specified layer as a simple rectangle.
     *
     * @param layer The layer to compose
     * @param rect The layer's bounds
     * @param swap If true, the source and destination are swapped
     */
    void composeLayerRect(Layer* layer, const Rect& rect, bool swap = false);

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
            const float right, const float bottom, const mat4 transform);

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
     * @param color The rectangle's ARGB color, defined as a packed 32 bits word
     * @param mode The Skia xfermode to use
     * @param ignoreTransform True if the current transform should be ignored
     */
    void drawColorRect(float left, float top, float right, float bottom,
            int color, SkXfermode::Mode mode, bool ignoreTransform = false);

    /**
     * Draws a series of colored rectangles with the specified color. The specified
     * coordinates are transformed by the current snapshot's transform matrix unless
     * specified otherwise.
     *
     * @param rects A list of rectangles, 4 floats (left, top, right, bottom)
     *              per rectangle
     * @param color The rectangles' ARGB color, defined as a packed 32 bits word
     * @param mode The Skia xfermode to use
     * @param ignoreTransform True if the current transform should be ignored
     * @param dirty True if calling this method should dirty the current layer
     * @param clip True if the rects should be clipped, false otherwise
     */
    status_t drawColorRects(const float* rects, int count, int color,
            SkXfermode::Mode mode, bool ignoreTransform = false,
            bool dirty = true, bool clip = true);

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
    status_t drawShape(float left, float top, const PathTexture* texture, SkPaint* paint);

    /**
     * Draws the specified texture as an alpha bitmap. Alpha bitmaps obey
     * different compositing rules.
     *
     * @param texture The texture to draw with
     * @param left The x coordinate of the bitmap
     * @param top The y coordinate of the bitmap
     * @param paint The paint to render with
     */
    void drawAlphaBitmap(Texture* texture, float left, float top, SkPaint* paint);

    /**
     * Renders a strip of polygons with the specified paint, used for tessellated geometry.
     *
     * @param vertexBuffer The VertexBuffer to be drawn
     * @param paint The paint to render with
     * @param useOffset Offset the vertexBuffer (used in drawing non-AA lines)
     */
    status_t drawVertexBuffer(const VertexBuffer& vertexBuffer, SkPaint* paint,
            bool useOffset = false);

    /**
     * Renders the convex hull defined by the specified path as a strip of polygons.
     *
     * @param path The hull of the path to draw
     * @param paint The paint to render with
     */
    status_t drawConvexPath(const SkPath& path, SkPaint* paint);

    /**
     * Draws a textured rectangle with the specified texture. The specified coordinates
     * are transformed by the current snapshot's transform matrix.
     *
     * @param left The left coordinate of the rectangle
     * @param top The top coordinate of the rectangle
     * @param right The right coordinate of the rectangle
     * @param bottom The bottom coordinate of the rectangle
     * @param texture The texture name to map onto the rectangle
     * @param alpha An additional translucency parameter, between 0.0f and 1.0f
     * @param mode The blending mode
     * @param blend True if the texture contains an alpha channel
     */
    void drawTextureRect(float left, float top, float right, float bottom, GLuint texture,
            float alpha, SkXfermode::Mode mode, bool blend);

    /**
     * Draws a textured rectangle with the specified texture. The specified coordinates
     * are transformed by the current snapshot's transform matrix.
     *
     * @param left The left coordinate of the rectangle
     * @param top The top coordinate of the rectangle
     * @param right The right coordinate of the rectangle
     * @param bottom The bottom coordinate of the rectangle
     * @param texture The texture to use
     * @param paint The paint containing the alpha, blending mode, etc.
     */
    void drawTextureRect(float left, float top, float right, float bottom,
            Texture* texture, SkPaint* paint);

    /**
     * Draws a textured mesh with the specified texture. If the indices are omitted,
     * the mesh is drawn as a simple quad. The mesh pointers become offsets when a
     * VBO is bound.
     *
     * @param left The left coordinate of the rectangle
     * @param top The top coordinate of the rectangle
     * @param right The right coordinate of the rectangle
     * @param bottom The bottom coordinate of the rectangle
     * @param texture The texture name to map onto the rectangle
     * @param alpha An additional translucency parameter, between 0.0f and 1.0f
     * @param mode The blending mode
     * @param blend True if the texture contains an alpha channel
     * @param vertices The vertices that define the mesh
     * @param texCoords The texture coordinates of each vertex
     * @param elementsCount The number of elements in the mesh, required by indices
     * @param swapSrcDst Whether or not the src and dst blending operations should be swapped
     * @param ignoreTransform True if the current transform should be ignored
     * @param vbo The VBO used to draw the mesh
     * @param ignoreScale True if the model view matrix should not be scaled
     * @param dirty True if calling this method should dirty the current layer
     */
    void drawTextureMesh(float left, float top, float right, float bottom, GLuint texture,
            float alpha, SkXfermode::Mode mode, bool blend,
            GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
            bool swapSrcDst = false, bool ignoreTransform = false, GLuint vbo = 0,
            bool ignoreScale = false, bool dirty = true);

    void drawIndexedTextureMesh(float left, float top, float right, float bottom, GLuint texture,
            float alpha, SkXfermode::Mode mode, bool blend,
            GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
            bool swapSrcDst = false, bool ignoreTransform = false, GLuint vbo = 0,
            bool ignoreScale = false, bool dirty = true);

    void drawAlpha8TextureMesh(float left, float top, float right, float bottom,
            GLuint texture, bool hasColor, int color, int alpha, SkXfermode::Mode mode,
            GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
            bool ignoreTransform, bool ignoreScale = false, bool dirty = true);

    /**
     * Draws the specified list of vertices as quads using indexed GL_TRIANGLES.
     * If the number of vertices to draw exceeds the number of indices we have
     * pre-allocated, this method will generate several glDrawElements() calls.
     */
    void drawIndexedQuads(Vertex* mesh, GLsizei quadsCount);

    /**
     * Draws text underline and strike-through if needed.
     *
     * @param text The text to decor
     * @param bytesCount The number of bytes in the text
     * @param totalAdvance The total advance in pixels, defines underline/strikethrough length
     * @param x The x coordinate where the text will be drawn
     * @param y The y coordinate where the text will be drawn
     * @param paint The paint to draw the text with
     */
    void drawTextDecorations(const char* text, int bytesCount, float totalAdvance,
            float x, float y, SkPaint* paint);

   /**
     * Draws shadow layer on text (with optional positions).
     *
     * @param paint The paint to draw the shadow with
     * @param text The text to draw
     * @param bytesCount The number of bytes in the text
     * @param count The number of glyphs in the text
     * @param positions The x, y positions of individual glyphs (or NULL)
     * @param fontRenderer The font renderer object
     * @param alpha The alpha value for drawing the shadow
     * @param mode The xfermode for drawing the shadow
     * @param x The x coordinate where the shadow will be drawn
     * @param y The y coordinate where the shadow will be drawn
     */
    void drawTextShadow(SkPaint* paint, const char* text, int bytesCount, int count,
            const float* positions, FontRenderer& fontRenderer, int alpha, SkXfermode::Mode mode,
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
     void drawPathTexture(const PathTexture* texture, float x, float y, SkPaint* paint);

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

    /**
     * Binds the specified texture. The texture unit must have been selected
     * prior to calling this method.
     */
    inline void bindTexture(GLuint texture) {
        mCaches.bindTexture(texture);
    }

    /**
     * Binds the specified EGLImage texture. The texture unit must have been selected
     * prior to calling this method.
     */
    inline void bindExternalTexture(GLuint texture) {
        mCaches.bindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
    }

    /**
     * Enable or disable blending as necessary. This function sets the appropriate
     * blend function based on the specified xfermode.
     */
    inline void chooseBlending(bool blend, SkXfermode::Mode mode, ProgramDescription& description,
            bool swapSrcDst = false);

    /**
     * Use the specified program with the current GL context. If the program is already
     * in use, it will not be bound again. If it is not in use, the current program is
     * marked unused and the specified program becomes used and becomes the new
     * current program.
     *
     * @param program The program to use
     *
     * @return true If the specified program was already in use, false otherwise.
     */
    inline bool useProgram(Program* program);

    /**
     * Invoked before any drawing operation. This sets required state.
     */
    void setupDraw(bool clear = true);

    /**
     * Various methods to setup OpenGL rendering.
     */
    void setupDrawWithTexture(bool isAlpha8 = false);
    void setupDrawWithTextureAndColor(bool isAlpha8 = false);
    void setupDrawWithExternalTexture();
    void setupDrawNoTexture();
    void setupDrawAA();
    void setupDrawColor(int color, int alpha);
    void setupDrawColor(float r, float g, float b, float a);
    void setupDrawAlpha8Color(int color, int alpha);
    void setupDrawTextGamma(const SkPaint* paint);
    void setupDrawShader();
    void setupDrawColorFilter();
    void setupDrawBlending(SkXfermode::Mode mode = SkXfermode::kSrcOver_Mode,
            bool swapSrcDst = false);
    void setupDrawBlending(bool blend = true, SkXfermode::Mode mode = SkXfermode::kSrcOver_Mode,
            bool swapSrcDst = false);
    void setupDrawProgram();
    void setupDrawDirtyRegionsDisabled();
    void setupDrawModelViewIdentity(bool offset = false);
    void setupDrawModelView(float left, float top, float right, float bottom,
            bool ignoreTransform = false, bool ignoreModelView = false);
    void setupDrawModelViewTranslate(float left, float top, float right, float bottom,
            bool ignoreTransform = false);
    void setupDrawColorUniforms();
    void setupDrawPureColorUniforms();
    void setupDrawShaderIdentityUniforms();
    void setupDrawShaderUniforms(bool ignoreTransform = false);
    void setupDrawColorFilterUniforms();
    void setupDrawSimpleMesh();
    void setupDrawTexture(GLuint texture);
    void setupDrawExternalTexture(GLuint texture);
    void setupDrawTextureTransform();
    void setupDrawTextureTransformUniforms(mat4& transform);
    void setupDrawTextGammaUniforms();
    void setupDrawMesh(GLvoid* vertices, GLvoid* texCoords = NULL, GLuint vbo = 0);
    void setupDrawMesh(GLvoid* vertices, GLvoid* texCoords, GLvoid* colors);
    void setupDrawMeshIndices(GLvoid* vertices, GLvoid* texCoords, GLuint vbo = 0);
    void setupDrawIndexedVertices(GLvoid* vertices);
    void accountForClear(SkXfermode::Mode mode);

    bool updateLayer(Layer* layer, bool inFrame);
    void updateLayers();
    void flushLayers();

    /**
     * Renders the specified region as a series of rectangles. This method
     * is used for debugging only.
     */
    void drawRegionRects(const Region& region);

    /**
     * Renders the specified region as a series of rectangles. The region
     * must be in screen-space coordinates.
     */
    void drawRegionRects(const SkRegion& region, int color, SkXfermode::Mode mode,
            bool dirty = false);

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
    inline void dirtyClip() {
        mDirtyClip = true;
    }

    inline mat4& currentTransform() const {
        return *mSnapshot->transform;
    }

    inline const UvMapper& getMapper(const Texture* texture) {
        return texture && texture->uvMapper ? *texture->uvMapper : mUvMapper;
    }

    /**
     * Returns a texture object for the specified bitmap. The texture can
     * come from the texture cache or an atlas. If this method returns
     * NULL, the texture could not be found and/or allocated.
     */
    Texture* getTexture(SkBitmap* bitmap);

    // Dimensions of the drawing surface
    int mWidth, mHeight;

    // Matrix used for ortho projection in shaders
    mat4 mOrthoMatrix;

    // Model-view matrix used to position/size objects
    mat4 mModelView;

    // Number of saved states
    int mSaveCount;
    // Base state
    sp<Snapshot> mFirstSnapshot;
    // Current state
    sp<Snapshot> mSnapshot;
    // State used to define the clipping region
    Rect mTilingClip;
    // Is the target render surface opaque
    bool mOpaque;
    // Is a frame currently being rendered
    bool mFrameStarted;

    // Used to draw textured quads
    TextureVertex mMeshVertices[4];

    // Default UV mapper
    const UvMapper mUvMapper;

    // shader, filters, and shadow
    DrawModifiers mDrawModifiers;
    SkPaint mFilteredPaint;

    // Various caches
    Caches& mCaches;
    Extensions& mExtensions;

    // List of rectangles to clear after saveLayer() is invoked
    Vector<Rect*> mLayers;
    // List of functors to invoke after a frame is drawn
    SortedVector<Functor*> mFunctors;
    // List of layers to update at the beginning of a frame
    Vector<Layer*> mLayerUpdates;

    // Indicates whether the clip must be restored
    bool mDirtyClip;

    // The following fields are used to setup drawing
    // Used to describe the shaders to generate
    ProgramDescription mDescription;
    // Color description
    bool mColorSet;
    float mColorA, mColorR, mColorG, mColorB;
    // Indicates that the shader should get a color
    bool mSetShaderColor;
    // Current texture unit
    GLuint mTextureUnit;
    // Track dirty regions, true by default
    bool mTrackDirtyRegions;
    // Indicate whether we are drawing an opaque frame
    bool mOpaqueFrame;

    // See PROPERTY_DISABLE_SCISSOR_OPTIMIZATION in
    // Properties.h
    bool mScissorOptimizationDisabled;

    // No-ops start/endTiling when set
    bool mSuppressTiling;

    // If true, this renderer will setup drawing to emulate
    // an increment stencil buffer in the color buffer
    bool mCountOverdraw;
    float mOverdraw;

    // Optional name of the renderer
    String8 mName;

    friend class DisplayListRenderer;
    friend class Layer;
    friend class TextSetupFunctor;
    friend class DrawBitmapOp;
    friend class DrawPatchOp;

}; // class OpenGLRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_OPENGL_RENDERER_H

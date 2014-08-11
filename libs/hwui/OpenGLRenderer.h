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
#include <SkCanvas.h>
#include <SkColorFilter.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkRegion.h>
#include <SkXfermode.h>

#include <utils/Blur.h>
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
#include "Renderer.h"
#include "Snapshot.h"
#include "StatefulBaseRenderer.h"
#include "UvMapper.h"
#include "Vertex.h"
#include "Caches.h"
#include "CanvasProperty.h"

class SkShader;

namespace android {
namespace uirenderer {

class DeferredDisplayState;
class RenderState;
class RenderNode;
class TextSetupFunctor;
class VertexBuffer;

struct DrawModifiers {
    DrawModifiers() {
        reset();
    }

    void reset() {
        memset(this, 0, sizeof(DrawModifiers));
    }

    float mOverrideLayerAlpha;

    // Draw filters
    bool mHasDrawFilter;
    int mPaintFilterClearBits;
    int mPaintFilterSetBits;
};

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
    kVertexBuffer_ShadowAA = 0x2,
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
class OpenGLRenderer : public StatefulBaseRenderer {
public:
    OpenGLRenderer(RenderState& renderState);
    virtual ~OpenGLRenderer();

    void initProperties();
    void initLight(const Vector3& lightCenter, float lightRadius,
            uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha);

    virtual void onViewportInitialized();
    virtual status_t prepareDirty(float left, float top, float right, float bottom, bool opaque);
    virtual void finish();

    void setCountOverdrawEnabled(bool enabled) {
        mCountOverdraw = enabled;
    }

    float getOverdraw() {
        return mCountOverdraw ? mOverdraw : 0.0f;
    }

    virtual status_t callDrawGLFunction(Functor* functor, Rect& dirty);

    void pushLayerUpdate(Layer* layer);
    void cancelLayerUpdate(Layer* layer);
    void clearLayerUpdates();
    void flushLayerUpdates();

    virtual int saveLayer(float left, float top, float right, float bottom,
            const SkPaint* paint, int flags) {
        return saveLayer(left, top, right, bottom, paint, flags, NULL);
    }

    // Specialized saveLayer implementation, which will pass the convexMask to an FBO layer, if
    // created, which will in turn clip to that mask when drawn back/restored.
    int saveLayer(float left, float top, float right, float bottom,
            const SkPaint* paint, int flags, const SkPath* convexMask);

    int saveLayerDeferred(float left, float top, float right, float bottom,
            const SkPaint* paint, int flags);

    virtual status_t drawRenderNode(RenderNode* displayList, Rect& dirty, int32_t replayFlags = 1);
    virtual status_t drawLayer(Layer* layer, float x, float y);
    virtual status_t drawBitmap(const SkBitmap* bitmap, const SkPaint* paint);
    status_t drawBitmaps(const SkBitmap* bitmap, AssetAtlas::Entry* entry, int bitmapCount,
            TextureVertex* vertices, bool pureTranslate, const Rect& bounds, const SkPaint* paint);
    virtual status_t drawBitmap(const SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint);
    virtual status_t drawBitmapData(const SkBitmap* bitmap, const SkPaint* paint);
    virtual status_t drawBitmapMesh(const SkBitmap* bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint);
    status_t drawPatches(const SkBitmap* bitmap, AssetAtlas::Entry* entry,
            TextureVertex* vertices, uint32_t indexCount, const SkPaint* paint);
    virtual status_t drawPatch(const SkBitmap* bitmap, const Res_png_9patch* patch,
            float left, float top, float right, float bottom, const SkPaint* paint);
    status_t drawPatch(const SkBitmap* bitmap, const Patch* mesh, AssetAtlas::Entry* entry,
            float left, float top, float right, float bottom, const SkPaint* paint);
    virtual status_t drawColor(int color, SkXfermode::Mode mode);
    virtual status_t drawRect(float left, float top, float right, float bottom,
            const SkPaint* paint);
    virtual status_t drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint* paint);
    virtual status_t drawCircle(float x, float y, float radius, const SkPaint* paint);
    virtual status_t drawOval(float left, float top, float right, float bottom,
            const SkPaint* paint);
    virtual status_t drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, const SkPaint* paint);
    virtual status_t drawPath(const SkPath* path, const SkPaint* paint);
    virtual status_t drawLines(const float* points, int count, const SkPaint* paint);
    virtual status_t drawPoints(const float* points, int count, const SkPaint* paint);
    virtual status_t drawTextOnPath(const char* text, int bytesCount, int count, const SkPath* path,
            float hOffset, float vOffset, const SkPaint* paint);
    virtual status_t drawPosText(const char* text, int bytesCount, int count,
            const float* positions, const SkPaint* paint);
    virtual status_t drawText(const char* text, int bytesCount, int count, float x, float y,
            const float* positions, const SkPaint* paint, float totalAdvance, const Rect& bounds,
            DrawOpMode drawOpMode = kDrawOpMode_Immediate);
    virtual status_t drawRects(const float* rects, int count, const SkPaint* paint);

    status_t drawShadow(float casterAlpha,
            const VertexBuffer* ambientShadowVertexBuffer, const VertexBuffer* spotShadowVertexBuffer);

    virtual void resetPaintFilter();
    virtual void setupPaintFilter(int clearBits, int setBits);

    // If this value is set to < 1.0, it overrides alpha set on layer (see drawBitmap, drawLayer)
    void setOverrideLayerAlpha(float alpha) { mDrawModifiers.mOverrideLayerAlpha = alpha; }

    const SkPaint* filterPaint(const SkPaint* paint);

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

    bool isCurrentTransformSimple() {
        return currentTransform()->isSimple();
    }

    Caches& getCaches() {
        return mCaches;
    }

    // simple rect clip
    bool isCurrentClipSimple() {
        return mSnapshot->clipRegion->isEmpty();
    }

    int getViewportWidth() { return currentSnapshot()->getViewportWidth(); }
    int getViewportHeight() { return currentSnapshot()->getViewportHeight(); }

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
     * Gets the alpha and xfermode out of a paint object. If the paint is null
     * alpha will be 255 and the xfermode will be SRC_OVER. This method does
     * not multiply the paint's alpha by the current snapshot's alpha, and does
     * not replace the alpha with the overrideLayerAlpha
     *
     * @param paint The paint to extract values from
     * @param alpha Where to store the resulting alpha
     * @param mode Where to store the resulting xfermode
     */
    static inline void getAlphaAndModeDirect(const SkPaint* paint, int* alpha, SkXfermode::Mode* mode) {
        *mode = getXfermodeDirect(paint);
        *alpha = getAlphaDirect(paint);
    }

    static inline SkXfermode::Mode getXfermodeDirect(const SkPaint* paint) {
        if (!paint) return SkXfermode::kSrcOver_Mode;
        return getXfermode(paint->getXfermode());
    }

    static inline int getAlphaDirect(const SkPaint* paint) {
        if (!paint) return 255;
        return paint->getAlpha();
    }

    struct TextShadow {
        SkScalar radius;
        float dx;
        float dy;
        SkColor color;
    };

    static inline bool getTextShadow(const SkPaint* paint, TextShadow* textShadow) {
        SkDrawLooper::BlurShadowRec blur;
        if (paint && paint->getLooper() && paint->getLooper()->asABlurShadow(&blur)) {
            if (textShadow) {
                textShadow->radius = Blur::convertSigmaToRadius(blur.fSigma);
                textShadow->dx = blur.fOffset.fX;
                textShadow->dy = blur.fOffset.fY;
                textShadow->color = blur.fColor;
            }
            return true;
        }
        return false;
    }

    static inline bool hasTextShadow(const SkPaint* paint) {
        return getTextShadow(paint, NULL);
    }

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
    }
#endif

    const Vector3& getLightCenter() const { return mLightCenter; }
    float getLightRadius() const { return mLightRadius; }

protected:
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

    bool quickRejectSetupScissor(float left, float top, float right, float bottom,
            const SkPaint* paint = NULL);
    bool quickRejectSetupScissor(const Rect& bounds, const SkPaint* paint = NULL) {
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
    virtual GLuint getTargetFbo() const {
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
    inline void getAlphaAndMode(const SkPaint* paint, int* alpha, SkXfermode::Mode* mode) const;

    /**
     * Gets the alpha from a layer, accounting for snapshot alpha and overrideLayerAlpha
     *
     * @param layer The layer from which the alpha is extracted
     */
    inline float getLayerAlpha(const Layer* layer) const;

    /**
     * Safely retrieves the ColorFilter from the given Paint. If the paint is
     * null then null is returned.
     */
    static inline SkColorFilter* getColorFilter(const SkPaint* paint) {
        return paint ? paint->getColorFilter() : NULL;
    }

    /**
     * Safely retrieves the Shader from the given Paint. If the paint is
     * null then null is returned.
     */
    static inline const SkShader* getShader(const SkPaint* paint) {
        return paint ? paint->getShader() : NULL;
    }

    /**
     * Set to true to suppress error checks at the end of a frame.
     */
    virtual bool suppressErrorChecks() const {
        return false;
    }

    inline RenderState& renderState() { return mRenderState; }

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
     * This method will use the current layer space clip rect.
     * This method needs to be invoked every time getTargetFbo() is
     * bound again.
     */
    void startTilingCurrentClip(bool opaque = false, bool expand = false);

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

    void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored);

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
    status_t drawColorRects(const float* rects, int count, const SkPaint* paint,
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
    status_t drawShape(float left, float top, const PathTexture* texture, const SkPaint* paint);

    /**
     * Draws the specified texture as an alpha bitmap. Alpha bitmaps obey
     * different compositing rules.
     *
     * @param texture The texture to draw with
     * @param left The x coordinate of the bitmap
     * @param top The y coordinate of the bitmap
     * @param paint The paint to render with
     */
    void drawAlphaBitmap(Texture* texture, float left, float top, const SkPaint* paint);

    /**
     * Renders a strip of polygons with the specified paint, used for tessellated geometry.
     *
     * @param vertexBuffer The VertexBuffer to be drawn
     * @param paint The paint to render with
     * @param flags flags with which to draw
     */
    status_t drawVertexBuffer(float translateX, float translateY, const VertexBuffer& vertexBuffer,
            const SkPaint* paint, int flags = 0);

    /**
     * Convenience for translating method
     */
    status_t drawVertexBuffer(const VertexBuffer& vertexBuffer,
            const SkPaint* paint, int flags = 0) {
        return drawVertexBuffer(0.0f, 0.0f, vertexBuffer, paint, flags);
    }

    /**
     * Renders the convex hull defined by the specified path as a strip of polygons.
     *
     * @param path The hull of the path to draw
     * @param paint The paint to render with
     */
    status_t drawConvexPath(const SkPath& path, const SkPaint* paint);

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
            Texture* texture, const SkPaint* paint);

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
     * @param paint The paint containing the alpha, blending mode, colorFilter, etc.
     * @param blend True if the texture contains an alpha channel
     * @param vertices The vertices that define the mesh
     * @param texCoords The texture coordinates of each vertex
     * @param elementsCount The number of elements in the mesh, required by indices
     * @param swapSrcDst Whether or not the src and dst blending operations should be swapped
     * @param ignoreTransform True if the current transform should be ignored
     * @param vbo The VBO used to draw the mesh
     * @param modelViewMode Defines whether the model view matrix should be scaled
     * @param dirty True if calling this method should dirty the current layer
     */
    void drawTextureMesh(float left, float top, float right, float bottom, GLuint texture,
            const SkPaint* paint, bool blend,
            GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
            bool swapSrcDst = false, bool ignoreTransform = false, GLuint vbo = 0,
            ModelViewMode modelViewMode = kModelViewMode_TranslateAndScale, bool dirty = true);

    void drawIndexedTextureMesh(float left, float top, float right, float bottom, GLuint texture,
            const SkPaint* paint, bool blend,
            GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
            bool swapSrcDst = false, bool ignoreTransform = false, GLuint vbo = 0,
            ModelViewMode modelViewMode = kModelViewMode_TranslateAndScale, bool dirty = true);

    void drawAlpha8TextureMesh(float left, float top, float right, float bottom,
            GLuint texture, const SkPaint* paint,
            GLvoid* vertices, GLvoid* texCoords, GLenum drawMode, GLsizei elementsCount,
            bool ignoreTransform, ModelViewMode modelViewMode = kModelViewMode_TranslateAndScale,
            bool dirty = true);

    /**
     * Draws the specified list of vertices as quads using indexed GL_TRIANGLES.
     * If the number of vertices to draw exceeds the number of indices we have
     * pre-allocated, this method will generate several glDrawElements() calls.
     */
    void issueIndexedQuadDraw(Vertex* mesh, GLsizei quadsCount);

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
    void drawTextDecorations(float totalAdvance, float x, float y, const SkPaint* paint);

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
     * @param x The x coordinate where the shadow will be drawn
     * @param y The y coordinate where the shadow will be drawn
     */
    void drawTextShadow(const SkPaint* paint, const char* text, int bytesCount, int count,
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
     void drawPathTexture(const PathTexture* texture, float x, float y, const SkPaint* paint);

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
    void setupDrawAA(bool useShadowInterp);
    void setupDrawColor(int color, int alpha);
    void setupDrawColor(float r, float g, float b, float a);
    void setupDrawAlpha8Color(int color, int alpha);
    void setupDrawTextGamma(const SkPaint* paint);
    void setupDrawShader(const SkShader* shader);
    void setupDrawColorFilter(const SkColorFilter* filter);
    void setupDrawBlending(const Layer* layer, bool swapSrcDst = false);
    void setupDrawBlending(const SkPaint* paint, bool blend = true, bool swapSrcDst = false);
    void setupDrawProgram();
    void setupDrawDirtyRegionsDisabled();

    /**
     * Setup the current program matrices based upon the nature of the geometry.
     *
     * @param mode If kModelViewMode_Translate, the geometry must be translated by the left and top
     * parameters. If kModelViewMode_TranslateAndScale, the geometry that exists in the (0,0, 1,1)
     * space must be scaled up and translated to fill the quad provided in (l,t,r,b). These
     * transformations are stored in the modelView matrix and uploaded to the shader.
     *
     * @param offset Set to true if the the matrix should be fudged (translated) slightly to disambiguate
     * geometry pixel positioning. See Vertex::GeometryFudgeFactor().
     *
     * @param ignoreTransform Set to true if l,t,r,b coordinates already in layer space,
     * currentTransform() will be ignored. (e.g. when drawing clip in layer coordinates to stencil,
     * or when simple translation has been extracted)
     */
    void setupDrawModelView(ModelViewMode mode, bool offset,
            float left, float top, float right, float bottom, bool ignoreTransform = false);
    void setupDrawColorUniforms(bool hasShader);
    void setupDrawPureColorUniforms();

    /**
     * Setup uniforms for the current shader.
     *
     * @param shader SkShader on the current paint.
     *
     * @param ignoreTransform Set to true to ignore the transform in shader.
     */
    void setupDrawShaderUniforms(const SkShader* shader, bool ignoreTransform = false);
    void setupDrawColorFilterUniforms(const SkColorFilter* paint);
    void setupDrawSimpleMesh();
    void setupDrawTexture(GLuint texture);
    void setupDrawExternalTexture(GLuint texture);
    void setupDrawTextureTransform();
    void setupDrawTextureTransformUniforms(mat4& transform);
    void setupDrawTextGammaUniforms();
    void setupDrawMesh(const GLvoid* vertices, const GLvoid* texCoords = NULL, GLuint vbo = 0);
    void setupDrawMesh(const GLvoid* vertices, const GLvoid* texCoords, const GLvoid* colors);
    void setupDrawMeshIndices(const GLvoid* vertices, const GLvoid* texCoords, GLuint vbo = 0);
    void setupDrawIndexedVertices(GLvoid* vertices);
    void accountForClear(SkXfermode::Mode mode);

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
    inline void dirtyClip() {
        mDirtyClip = true;
    }

    inline const UvMapper& getMapper(const Texture* texture) {
        return texture && texture->uvMapper ? *texture->uvMapper : mUvMapper;
    }

    /**
     * Returns a texture object for the specified bitmap. The texture can
     * come from the texture cache or an atlas. If this method returns
     * NULL, the texture could not be found and/or allocated.
     */
    Texture* getTexture(const SkBitmap* bitmap);

    /**
     * Model-view matrix used to position/size objects
     *
     * Stores operation-local modifications to the draw matrix that aren't incorporated into the
     * currentTransform().
     *
     * If generated with kModelViewMode_Translate, mModelViewMatrix will reflect an x/y offset,
     * e.g. the offset in drawLayer(). If generated with kModelViewMode_TranslateAndScale,
     * mModelViewMatrix will reflect a translation and scale, e.g. the translation and scale
     * required to make VBO 0 (a rect of (0,0,1,1)) scaled to match the x,y offset, and width/height
     * of a bitmap.
     *
     * Used as input to SkiaShader transformation.
     */
    mat4 mModelViewMatrix;

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
    RenderState& mRenderState;

    // List of rectangles to clear after saveLayer() is invoked
    Vector<Rect*> mLayers;
    // List of layers to update at the beginning of a frame
    Vector<Layer*> mLayerUpdates;

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

    bool mSkipOutlineClip;

    // Lighting + shadows
    Vector3 mLightCenter;
    float mLightRadius;
    uint8_t mAmbientShadowAlpha;
    uint8_t mSpotShadowAlpha;

    friend class Layer;
    friend class TextSetupFunctor;
    friend class DrawBitmapOp;
    friend class DrawPatchOp;

}; // class OpenGLRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_OPENGL_RENDERER_H

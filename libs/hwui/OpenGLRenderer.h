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
#include <utils/Vector.h>

#include <cutils/compiler.h>

#include "Debug.h"
#include "Extensions.h"
#include "Matrix.h"
#include "Program.h"
#include "Rect.h"
#include "Snapshot.h"
#include "Vertex.h"
#include "SkiaShader.h"
#include "SkiaColorFilter.h"
#include "Caches.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Renderer
///////////////////////////////////////////////////////////////////////////////

class DisplayList;

/**
 * OpenGL renderer used to draw accelerated 2D graphics. The API is a
 * simplified version of Skia's Canvas API.
 */
class OpenGLRenderer {
public:
    ANDROID_API OpenGLRenderer();
    virtual ~OpenGLRenderer();

    virtual void setViewport(int width, int height);

    ANDROID_API void prepare(bool opaque);
    virtual void prepareDirty(float left, float top, float right, float bottom, bool opaque);
    virtual void finish();

    // These two calls must not be recorded in display lists
    virtual void interrupt();
    virtual void resume();

    virtual bool callDrawGLFunction(Functor *functor, Rect& dirty);

    ANDROID_API int getSaveCount() const;
    virtual int save(int flags);
    virtual void restore();
    virtual void restoreToCount(int saveCount);

    virtual int saveLayer(float left, float top, float right, float bottom,
            SkPaint* p, int flags);
    virtual int saveLayerAlpha(float left, float top, float right, float bottom,
            int alpha, int flags);

    virtual void translate(float dx, float dy);
    virtual void rotate(float degrees);
    virtual void scale(float sx, float sy);
    virtual void skew(float sx, float sy);

    ANDROID_API void getMatrix(SkMatrix* matrix);
    virtual void setMatrix(SkMatrix* matrix);
    virtual void concatMatrix(SkMatrix* matrix);

    ANDROID_API const Rect& getClipBounds();
    ANDROID_API bool quickReject(float left, float top, float right, float bottom);
    virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op);

    virtual bool drawDisplayList(DisplayList* displayList, uint32_t width, uint32_t height,
            Rect& dirty, uint32_t level = 0);
    virtual void outputDisplayList(DisplayList* displayList, uint32_t level = 0);
    virtual void drawLayer(Layer* layer, float x, float y, SkPaint* paint);
    virtual void drawBitmap(SkBitmap* bitmap, float left, float top, SkPaint* paint);
    virtual void drawBitmap(SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint);
    virtual void drawBitmap(SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, SkPaint* paint);
    virtual void drawBitmapMesh(SkBitmap* bitmap, int meshWidth, int meshHeight,
            float* vertices, int* colors, SkPaint* paint);
    virtual void drawPatch(SkBitmap* bitmap, const int32_t* xDivs, const int32_t* yDivs,
            const uint32_t* colors, uint32_t width, uint32_t height, int8_t numColors,
            float left, float top, float right, float bottom, SkPaint* paint);
    virtual void drawColor(int color, SkXfermode::Mode mode);
    virtual void drawRect(float left, float top, float right, float bottom, SkPaint* paint);
    virtual void drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, SkPaint* paint);
    virtual void drawCircle(float x, float y, float radius, SkPaint* paint);
    virtual void drawOval(float left, float top, float right, float bottom, SkPaint* paint);
    virtual void drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, SkPaint* paint);
    virtual void drawPath(SkPath* path, SkPaint* paint);
    virtual void drawLines(float* points, int count, SkPaint* paint);
    virtual void drawPoints(float* points, int count, SkPaint* paint);
    virtual void drawText(const char* text, int bytesCount, int count, float x, float y,
            SkPaint* paint, float length = -1.0f);

    virtual void resetShader();
    virtual void setupShader(SkiaShader* shader);

    virtual void resetColorFilter();
    virtual void setupColorFilter(SkiaColorFilter* filter);

    virtual void resetShadow();
    virtual void setupShadow(float radius, float dx, float dy, int color);

protected:
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
    sp<Snapshot> getSnapshot() {
        return mSnapshot;
    }

    /**
     * Returns the region of the current layer.
     */
    virtual Region* getRegion() {
        return mSnapshot->region;
    }

    /**
     * Indicates whether rendering is currently targeted at a layer.
     */
    virtual bool hasLayer() {
        return (mSnapshot->flags & Snapshot::kFlagFboTarget) && mSnapshot->region;
    }

    /**
     * Returns the name of the FBO this renderer is rendering into.
     */
    virtual GLint getTargetFbo() {
        return 0;
    }

    /**
     * Renders the specified layer as a textured quad.
     *
     * @param layer The layer to render
     * @param rect The bounds of the layer
     */
    void drawTextureLayer(Layer* layer, const Rect& rect);

private:
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
    bool createLayer(sp<Snapshot> snapshot, float left, float top, float right, float bottom,
            int alpha, SkXfermode::Mode mode, int flags, GLuint previousFbo);

    /**
     * Creates a new layer stored in the specified snapshot as an FBO.
     *
     * @param layer The layer to store as an FBO
     * @param snapshot The snapshot associated with the new layer
     * @param bounds The bounds of the layer
     * @param previousFbo The name of the current framebuffer
     */
    bool createFboLayer(Layer* layer, Rect& bounds, sp<Snapshot> snapshot,
            GLuint previousFbo);

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
     * are transformed by the current snapshot's transform matrix.
     *
     * @param left The left coordinate of the rectangle
     * @param top The top coordinate of the rectangle
     * @param right The right coordinate of the rectangle
     * @param bottom The bottom coordinate of the rectangle
     * @param color The rectangle's ARGB color, defined as a packed 32 bits word
     * @param mode The Skia xfermode to use
     * @param ignoreTransform True if the current transform should be ignored
     * @param ignoreBlending True if the blending is set by the caller
     */
    void drawColorRect(float left, float top, float right, float bottom,
            int color, SkXfermode::Mode mode, bool ignoreTransform = false);

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
    void drawShape(float left, float top, const PathTexture* texture, SkPaint* paint);

    /**
     * Renders the rect defined by the specified bounds as a shape.
     * This will render the rect using a path texture, which is used to render
     * rects with stroke effects.
     *
     * @param left The left coordinate of the rect to draw
     * @param top The top coordinate of the rect to draw
     * @param right The right coordinate of the rect to draw
     * @param bottom The bottom coordinate of the rect to draw
     * @param p The paint to draw the rect with
     */
    void drawRectAsShape(float left, float top, float right, float bottom, SkPaint* p);

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
     * Renders the rect defined by the specified bounds as an anti-aliased rect.
     *
     * @param left The left coordinate of the rect to draw
     * @param top The top coordinate of the rect to draw
     * @param right The right coordinate of the rect to draw
     * @param bottom The bottom coordinate of the rect to draw
     * @param color The color of the rect
     * @param mode The blending mode to draw the rect
     */
    void drawAARect(float left, float top, float right, float bottom,
            int color, SkXfermode::Mode mode);

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

    /**
     * Draws text underline and strike-through if needed.
     *
     * @param text The text to decor
     * @param bytesCount The number of bytes in the text
     * @param length The length in pixels of the text, can be <= 0.0f to force a measurement
     * @param x The x coordinate where the text will be drawn
     * @param y The y coordinate where the text will be drawn
     * @param paint The paint to draw the text with
     */
    void drawTextDecorations(const char* text, int bytesCount, float length,
            float x, float y, SkPaint* paint);

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
     * Gets the alpha and xfermode out of a paint object. If the paint is null
     * alpha will be 255 and the xfermode will be SRC_OVER.
     *
     * @param paint The paint to extract values from
     * @param alpha Where to store the resulting alpha
     * @param mode Where to store the resulting xfermode
     */
    inline void getAlphaAndMode(SkPaint* paint, int* alpha, SkXfermode::Mode* mode);

    /**
     * Binds the specified texture. The texture unit must have been selected
     * prior to calling this method.
     */
    inline void bindTexture(GLuint texture) {
        glBindTexture(GL_TEXTURE_2D, texture);
    }

    /**
     * Binds the specified EGLImage texture. The texture unit must have been selected
     * prior to calling this method.
     */
    inline void bindExternalTexture(GLuint texture) {
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
    }

    /**
     * Enable or disable blending as necessary. This function sets the appropriate
     * blend function based on the specified xfermode.
     */
    inline void chooseBlending(bool blend, SkXfermode::Mode mode, ProgramDescription& description,
            bool swapSrcDst = false);

    /**
     * Safely retrieves the mode from the specified xfermode. If the specified
     * xfermode is null, the mode is assumed to be SkXfermode::kSrcOver_Mode.
     */
    inline SkXfermode::Mode getXfermode(SkXfermode* mode);

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
    void setupDrawWithExternalTexture();
    void setupDrawAALine();
    void setupDrawPoint(float pointSize);
    void setupDrawColor(int color);
    void setupDrawColor(int color, int alpha);
    void setupDrawColor(float r, float g, float b, float a);
    void setupDrawAlpha8Color(int color, int alpha);
    void setupDrawAlpha8Color(float r, float g, float b, float a);
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
    void setupDrawPointUniforms();
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
    void setupDrawMesh(GLvoid* vertices, GLvoid* texCoords = NULL, GLuint vbo = 0);
    void setupDrawVertices(GLvoid* vertices);
    void setupDrawAALine(GLvoid* vertices, GLvoid* distanceCoords, GLvoid* lengthCoords,
            float strokeWidth);
    void finishDrawTexture();
    void accountForClear(SkXfermode::Mode mode);

    void drawRegionRects(const Region& region);

    /**
     * Should be invoked every time the glScissor is modified.
     */
    inline void dirtyClip() {
        mDirtyClip = true;
    }

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

    // Shaders
    SkiaShader* mShader;

    // Color filters
    SkiaColorFilter* mColorFilter;

    // Used to draw textured quads
    TextureVertex mMeshVertices[4];

    // Drop shadow
    bool mHasShadow;
    float mShadowRadius;
    float mShadowDx;
    float mShadowDy;
    int mShadowColor;

    // Various caches
    Caches& mCaches;

    // List of rectagnles to clear after saveLayer() is invoked
    Vector<Rect*> mLayers;

    // Indentity matrix
    const mat4 mIdentity;

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
    // Texture coordinates slot
    int mTexCoordsSlot;

    friend class DisplayListRenderer;

}; // class OpenGLRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_OPENGL_RENDERER_H

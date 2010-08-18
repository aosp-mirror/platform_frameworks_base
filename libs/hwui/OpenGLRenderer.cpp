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

#define LOG_TAG "OpenGLRenderer"

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <SkCanvas.h>
#include <SkTypeface.h>

#include <cutils/properties.h>
#include <utils/Log.h>

#include "OpenGLRenderer.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define DEFAULT_TEXTURE_CACHE_SIZE 20.0f
#define DEFAULT_LAYER_CACHE_SIZE 6.0f
#define DEFAULT_PATH_CACHE_SIZE 6.0f
#define DEFAULT_PATCH_CACHE_SIZE 100
#define DEFAULT_GRADIENT_CACHE_SIZE 0.5f
#define DEFAULT_DROP_SHADOW_CACHE_SIZE 1.0f

#define REQUIRED_TEXTURE_UNITS_COUNT 3

// Converts a number of mega-bytes into bytes
#define MB(s) s * 1024 * 1024

// Generates simple and textured vertices
#define FV(x, y, u, v) { { x, y }, { u, v } }

///////////////////////////////////////////////////////////////////////////////
// Globals
///////////////////////////////////////////////////////////////////////////////

// This array is never used directly but used as a memcpy source in the
// OpenGLRenderer constructor
static const TextureVertex gMeshVertices[] = {
        FV(0.0f, 0.0f, 0.0f, 0.0f),
        FV(1.0f, 0.0f, 1.0f, 0.0f),
        FV(0.0f, 1.0f, 0.0f, 1.0f),
        FV(1.0f, 1.0f, 1.0f, 1.0f)
};
static const GLsizei gMeshStride = sizeof(TextureVertex);
static const GLsizei gMeshCount = 4;

/**
 * Structure mapping Skia xfermodes to OpenGL blending factors.
 */
struct Blender {
    SkXfermode::Mode mode;
    GLenum src;
    GLenum dst;
}; // struct Blender

// In this array, the index of each Blender equals the value of the first
// entry. For instance, gBlends[1] == gBlends[SkXfermode::kSrc_Mode]
static const Blender gBlends[] = {
        { SkXfermode::kClear_Mode,   GL_ZERO,                 GL_ZERO },
        { SkXfermode::kSrc_Mode,     GL_ONE,                  GL_ZERO },
        { SkXfermode::kDst_Mode,     GL_ZERO,                 GL_ONE },
        { SkXfermode::kSrcOver_Mode, GL_ONE,                  GL_ONE_MINUS_SRC_ALPHA },
        { SkXfermode::kDstOver_Mode, GL_ONE_MINUS_DST_ALPHA,  GL_ONE },
        { SkXfermode::kSrcIn_Mode,   GL_DST_ALPHA,            GL_ZERO },
        { SkXfermode::kDstIn_Mode,   GL_ZERO,                 GL_SRC_ALPHA },
        { SkXfermode::kSrcOut_Mode,  GL_ONE_MINUS_DST_ALPHA,  GL_ZERO },
        { SkXfermode::kDstOut_Mode,  GL_ZERO,                 GL_ONE_MINUS_SRC_ALPHA },
        { SkXfermode::kSrcATop_Mode, GL_DST_ALPHA,            GL_ONE_MINUS_SRC_ALPHA },
        { SkXfermode::kDstATop_Mode, GL_ONE_MINUS_DST_ALPHA,  GL_SRC_ALPHA },
        { SkXfermode::kXor_Mode,     GL_ONE_MINUS_DST_ALPHA,  GL_ONE_MINUS_SRC_ALPHA }
};

static const GLenum gTextureUnits[] = {
        GL_TEXTURE0,
        GL_TEXTURE1,
        GL_TEXTURE2
};

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

OpenGLRenderer::OpenGLRenderer():
        mBlend(false), mLastSrcMode(GL_ZERO), mLastDstMode(GL_ZERO),
        mTextureCache(MB(DEFAULT_TEXTURE_CACHE_SIZE)),
        mLayerCache(MB(DEFAULT_LAYER_CACHE_SIZE)),
        mGradientCache(MB(DEFAULT_GRADIENT_CACHE_SIZE)),
        mPathCache(MB(DEFAULT_PATH_CACHE_SIZE)),
        mPatchCache(DEFAULT_PATCH_CACHE_SIZE),
        mDropShadowCache(MB(DEFAULT_DROP_SHADOW_CACHE_SIZE)) {
    LOGD("Create OpenGLRenderer");

    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_TEXTURE_CACHE_SIZE, property, NULL) > 0) {
        LOGD("  Setting texture cache size to %sMB", property);
        mTextureCache.setMaxSize(MB(atof(property)));
    } else {
        LOGD("  Using default texture cache size of %.2fMB", DEFAULT_TEXTURE_CACHE_SIZE);
    }

    if (property_get(PROPERTY_LAYER_CACHE_SIZE, property, NULL) > 0) {
        LOGD("  Setting layer cache size to %sMB", property);
        mLayerCache.setMaxSize(MB(atof(property)));
    } else {
        LOGD("  Using default layer cache size of %.2fMB", DEFAULT_LAYER_CACHE_SIZE);
    }

    if (property_get(PROPERTY_GRADIENT_CACHE_SIZE, property, NULL) > 0) {
        LOGD("  Setting gradient cache size to %sMB", property);
        mGradientCache.setMaxSize(MB(atof(property)));
    } else {
        LOGD("  Using default gradient cache size of %.2fMB", DEFAULT_GRADIENT_CACHE_SIZE);
    }

    if (property_get(PROPERTY_PATH_CACHE_SIZE, property, NULL) > 0) {
        LOGD("  Setting path cache size to %sMB", property);
        mPathCache.setMaxSize(MB(atof(property)));
    } else {
        LOGD("  Using default path cache size of %.2fMB", DEFAULT_PATH_CACHE_SIZE);
    }

    if (property_get(PROPERTY_DROP_SHADOW_CACHE_SIZE, property, NULL) > 0) {
        LOGD("  Setting drop shadow cache size to %sMB", property);
        mDropShadowCache.setMaxSize(MB(atof(property)));
    } else {
        LOGD("  Using default drop shadow cache size of %.2fMB", DEFAULT_DROP_SHADOW_CACHE_SIZE);
    }
    mDropShadowCache.setFontRenderer(mFontRenderer);

    mCurrentProgram = NULL;
    mShader = NULL;
    mColorFilter = NULL;
    mHasShadow = false;

    memcpy(mMeshVertices, gMeshVertices, sizeof(gMeshVertices));

    mFirstSnapshot = new Snapshot;

    GLint maxTextureUnits;
    glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &maxTextureUnits);
    if (maxTextureUnits < REQUIRED_TEXTURE_UNITS_COUNT) {
        LOGW("At least %d texture units are required!", REQUIRED_TEXTURE_UNITS_COUNT);
    }
}

OpenGLRenderer::~OpenGLRenderer() {
    LOGD("Destroy OpenGLRenderer");

    mTextureCache.clear();
    mLayerCache.clear();
    mGradientCache.clear();
    mPathCache.clear();
    mPatchCache.clear();
    mProgramCache.clear();
    mDropShadowCache.clear();
}

///////////////////////////////////////////////////////////////////////////////
// Setup
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setViewport(int width, int height) {
    glViewport(0, 0, width, height);
    mOrthoMatrix.loadOrtho(0, width, height, 0, -1, 1);

    mWidth = width;
    mHeight = height;
    mFirstSnapshot->height = height;
    mFirstSnapshot->viewport.set(0, 0, width, height);
}

void OpenGLRenderer::prepare() {
    mSnapshot = new Snapshot(mFirstSnapshot);
    mSaveCount = 1;

    glDisable(GL_SCISSOR_TEST);

    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glEnable(GL_SCISSOR_TEST);
    glScissor(0, 0, mWidth, mHeight);

    mSnapshot->setClip(0.0f, 0.0f, mWidth, mHeight);
}

///////////////////////////////////////////////////////////////////////////////
// State management
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::getSaveCount() const {
    return mSaveCount;
}

int OpenGLRenderer::save(int flags) {
    return saveSnapshot();
}

void OpenGLRenderer::restore() {
    if (mSaveCount > 1 && restoreSnapshot()) {
        setScissorFromClip();
    }
}

void OpenGLRenderer::restoreToCount(int saveCount) {
    if (saveCount < 1) saveCount = 1;

    bool restoreClip = false;

    while (mSaveCount > saveCount) {
        restoreClip |= restoreSnapshot();
    }

    if (restoreClip) {
        setScissorFromClip();
    }
}

int OpenGLRenderer::saveSnapshot() {
    mSnapshot = new Snapshot(mSnapshot);
    return mSaveCount++;
}

bool OpenGLRenderer::restoreSnapshot() {
    bool restoreClip = mSnapshot->flags & Snapshot::kFlagClipSet;
    bool restoreLayer = mSnapshot->flags & Snapshot::kFlagIsLayer;
    bool restoreOrtho = mSnapshot->flags & Snapshot::kFlagDirtyOrtho;

    sp<Snapshot> current = mSnapshot;
    sp<Snapshot> previous = mSnapshot->previous;

    if (restoreOrtho) {
        Rect& r = previous->viewport;
        glViewport(r.left, r.top, r.right, r.bottom);
        mOrthoMatrix.load(current->orthoMatrix);
    }

    if (restoreLayer) {
        composeLayer(current, previous);
    }

    bool skip = mSnapshot->skip;
    if (!skip) {
        mSaveCount--;
    }
    mSnapshot = previous;

    if (!skip) {
        return restoreClip;
    } else {
        bool restorePreviousClip = restoreSnapshot();
        return restoreClip || restorePreviousClip;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

int OpenGLRenderer::saveLayer(float left, float top, float right, float bottom,
        const SkPaint* p, int flags) {
    int count = saveSnapshot();

    int alpha = 255;
    SkXfermode::Mode mode;

    if (p) {
        alpha = p->getAlpha();
        const bool isMode = SkXfermode::IsMode(p->getXfermode(), &mode);
        if (!isMode) {
            // Assume SRC_OVER
            mode = SkXfermode::kSrcOver_Mode;
        }
    } else {
        mode = SkXfermode::kSrcOver_Mode;
    }

    createLayer(mSnapshot, left, top, right, bottom, alpha, mode, flags);

    return count;
}

int OpenGLRenderer::saveLayerAlpha(float left, float top, float right, float bottom,
        int alpha, int flags) {
    int count = saveSnapshot();
    createLayer(mSnapshot, left, top, right, bottom, alpha, SkXfermode::kSrcOver_Mode, flags);
    return count;
}

bool OpenGLRenderer::createLayer(sp<Snapshot> snapshot, float left, float top,
        float right, float bottom, int alpha, SkXfermode::Mode mode,int flags) {
    LAYER_LOGD("Requesting layer %fx%f", right - left, bottom - top);
    LAYER_LOGD("Layer cache size = %d", mLayerCache.getSize());

    GLuint previousFbo = snapshot->previous.get() ? snapshot->previous->fbo : 0;
    LayerSize size(right - left, bottom - top);

    Layer* layer = mLayerCache.get(size, previousFbo);
    if (!layer) {
        return false;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, layer->fbo);

    // Clear the FBO
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST);

    layer->mode = mode;
    layer->alpha = alpha / 255.0f;
    layer->layer.set(left, top, right, bottom);

    // Save the layer in the snapshot
    snapshot->flags |= Snapshot::kFlagIsLayer;
    snapshot->layer = layer;
    snapshot->fbo = layer->fbo;

    // Creates a new snapshot to draw into the FBO
    saveSnapshot();
    mSaveCount--;

    mSnapshot->skip = true;
    mSnapshot->transform.loadTranslate(-left, -top, 0.0f);
    mSnapshot->setClip(0.0f, 0.0f, right - left, bottom - top);
    mSnapshot->viewport.set(0.0f, 0.0f, right - left, bottom - top);
    mSnapshot->height = bottom - top;

    setScissorFromClip();

    mSnapshot->flags = Snapshot::kFlagDirtyOrtho | Snapshot::kFlagClipSet;
    mSnapshot->orthoMatrix.load(mOrthoMatrix);

    // Change the ortho projection
    glViewport(0, 0, right - left, bottom - top);
    // Don't flip the FBO, it will get flipped when drawing back to the framebuffer
    mOrthoMatrix.loadOrtho(0.0f, right - left, 0.0f, bottom - top, -1.0f, 1.0f);

    return true;
}

void OpenGLRenderer::composeLayer(sp<Snapshot> current, sp<Snapshot> previous) {
    if (!current->layer) {
        LOGE("Attempting to compose a layer that does not exist");
        return;
    }

    // Unbind current FBO and restore previous one
    // Most of the time, previous->fbo will be 0 to bind the default buffer
    glBindFramebuffer(GL_FRAMEBUFFER, previous->fbo);

    // Restore the clip from the previous snapshot
    const Rect& clip = previous->clipRect;
    glScissor(clip.left, mHeight - clip.bottom, clip.getWidth(), clip.getHeight());

    Layer* layer = current->layer;
    const Rect& rect = layer->layer;

    drawTextureRect(rect.left, rect.top, rect.right, rect.bottom,
            layer->texture, layer->alpha, layer->mode, layer->blend);

    LayerSize size(rect.getWidth(), rect.getHeight());
    // Failing to add the layer to the cache should happen only if the
    // layer is too large
    if (!mLayerCache.put(size, layer)) {
        LAYER_LOGD("Deleting layer");

        glDeleteFramebuffers(1, &layer->fbo);
        glDeleteTextures(1, &layer->texture);

        delete layer;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Transforms
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::translate(float dx, float dy) {
    mSnapshot->transform.translate(dx, dy, 0.0f);
}

void OpenGLRenderer::rotate(float degrees) {
    mSnapshot->transform.rotate(degrees, 0.0f, 0.0f, 1.0f);
}

void OpenGLRenderer::scale(float sx, float sy) {
    mSnapshot->transform.scale(sx, sy, 1.0f);
}

void OpenGLRenderer::setMatrix(SkMatrix* matrix) {
    mSnapshot->transform.load(*matrix);
}

void OpenGLRenderer::getMatrix(SkMatrix* matrix) {
    mSnapshot->transform.copyTo(*matrix);
}

void OpenGLRenderer::concatMatrix(SkMatrix* matrix) {
    mat4 m(*matrix);
    mSnapshot->transform.multiply(m);
}

///////////////////////////////////////////////////////////////////////////////
// Clipping
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setScissorFromClip() {
    const Rect& clip = mSnapshot->clipRect;
    glScissor(clip.left, mSnapshot->height - clip.bottom, clip.getWidth(), clip.getHeight());
}

const Rect& OpenGLRenderer::getClipBounds() {
    return mSnapshot->getLocalClip();
}

bool OpenGLRenderer::quickReject(float left, float top, float right, float bottom) {
    Rect r(left, top, right, bottom);
    mSnapshot->transform.mapRect(r);
    return !mSnapshot->clipRect.intersects(r);
}

bool OpenGLRenderer::clipRect(float left, float top, float right, float bottom, SkRegion::Op op) {
    bool clipped = mSnapshot->clip(left, top, right, bottom, op);
    if (clipped) {
        setScissorFromClip();
    }
    return !mSnapshot->clipRect.isEmpty();
}

///////////////////////////////////////////////////////////////////////////////
// Drawing
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap, float left, float top, const SkPaint* paint) {
    const float right = left + bitmap->width();
    const float bottom = top + bitmap->height();

    if (quickReject(left, top, right, bottom)) {
        return;
    }

    glActiveTexture(GL_TEXTURE0);
    const Texture* texture = mTextureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    drawTextureRect(left, top, right, bottom, texture, paint);
}

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap, const SkMatrix* matrix, const SkPaint* paint) {
    Rect r(0.0f, 0.0f, bitmap->width(), bitmap->height());
    const mat4 transform(*matrix);
    transform.mapRect(r);

    if (quickReject(r.left, r.top, r.right, r.bottom)) {
        return;
    }

    glActiveTexture(GL_TEXTURE0);
    const Texture* texture = mTextureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    drawTextureRect(r.left, r.top, r.right, r.bottom, texture, paint);
}

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap,
         float srcLeft, float srcTop, float srcRight, float srcBottom,
         float dstLeft, float dstTop, float dstRight, float dstBottom,
         const SkPaint* paint) {
    if (quickReject(dstLeft, dstTop, dstRight, dstBottom)) {
        return;
    }

    glActiveTexture(GL_TEXTURE0);
    const Texture* texture = mTextureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    const float width = texture->width;
    const float height = texture->height;

    const float u1 = srcLeft / width;
    const float v1 = srcTop / height;
    const float u2 = srcRight / width;
    const float v2 = srcBottom / height;

    resetDrawTextureTexCoords(u1, v1, u2, v2);

    drawTextureRect(dstLeft, dstTop, dstRight, dstBottom, texture, paint);

    resetDrawTextureTexCoords(0.0f, 0.0f, 1.0f, 1.0f);
}

void OpenGLRenderer::drawPatch(SkBitmap* bitmap, Res_png_9patch* patch,
        float left, float top, float right, float bottom, const SkPaint* paint) {
    if (quickReject(left, top, right, bottom)) {
        return;
    }

    glActiveTexture(GL_TEXTURE0);
    const Texture* texture = mTextureCache.get(bitmap);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    Patch* mesh = mPatchCache.get(patch);
    mesh->updateVertices(bitmap, left, top, right, bottom,
            &patch->xDivs[0], &patch->yDivs[0], patch->numXDivs, patch->numYDivs);

    // Specify right and bottom as +1.0f from left/top to prevent scaling since the
    // patch mesh already defines the final size
    drawTextureMesh(left, top, left + 1.0f, top + 1.0f, texture->id, alpha / 255.0f,
            mode, texture->blend, &mesh->vertices[0].position[0],
            &mesh->vertices[0].texture[0], mesh->indices, mesh->indicesCount);
}

void OpenGLRenderer::drawColor(int color, SkXfermode::Mode mode) {
    const Rect& clip = mSnapshot->clipRect;
    drawColorRect(clip.left, clip.top, clip.right, clip.bottom, color, mode, true);
}

void OpenGLRenderer::drawRect(float left, float top, float right, float bottom, const SkPaint* p) {
    if (quickReject(left, top, right, bottom)) {
        return;
    }

    SkXfermode::Mode mode;

    const bool isMode = SkXfermode::IsMode(p->getXfermode(), &mode);
    if (!isMode) {
        // Assume SRC_OVER
        mode = SkXfermode::kSrcOver_Mode;
    }

    // Skia draws using the color's alpha channel if < 255
    // Otherwise, it uses the paint's alpha
    int color = p->getColor();
    if (((color >> 24) & 0xff) == 255) {
        color |= p->getAlpha() << 24;
    }

    drawColorRect(left, top, right, bottom, color, mode);
}

void OpenGLRenderer::drawText(const char* text, int bytesCount, int count,
        float x, float y, SkPaint* paint) {
    if (text == NULL || count == 0 || (paint->getAlpha() == 0 && paint->getXfermode() == NULL)) {
        return;
    }

    float length = -1.0f;
    switch (paint->getTextAlign()) {
        case SkPaint::kCenter_Align:
            length = paint->measureText(text, bytesCount);
            x -= length / 2.0f;
            break;
        case SkPaint::kRight_Align:
            length = paint->measureText(text, bytesCount);
            x -= length;
            break;
        default:
            break;
    }

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    mFontRenderer.setFont(paint, SkTypeface::UniqueID(paint->getTypeface()), paint->getTextSize());
    if (mHasShadow) {
        glActiveTexture(gTextureUnits[0]);
        const ShadowTexture* shadow = mDropShadowCache.get(paint, text, bytesCount,
                count, mShadowRadius);
        const AutoTexture autoCleanup(shadow);

        setupShadow(shadow, x, y, mode);

        // Draw the mesh
        glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);
        glDisableVertexAttribArray(mCurrentProgram->getAttrib("texCoords"));
    }

    uint32_t color = paint->getColor();
    const GLfloat a = alpha / 255.0f;
    const GLfloat r = a * ((color >> 16) & 0xFF) / 255.0f;
    const GLfloat g = a * ((color >>  8) & 0xFF) / 255.0f;
    const GLfloat b = a * ((color      ) & 0xFF) / 255.0f;

    GLuint textureUnit = 0;
    glActiveTexture(gTextureUnits[textureUnit]);

    setupTextureAlpha8(mFontRenderer.getTexture(), 0, 0, textureUnit, x, y, r, g, b, a,
            mode, false, true);

    const Rect& clip = mSnapshot->getLocalClip();
    mFontRenderer.renderText(paint, &clip, text, 0, bytesCount, count, x, y);

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glDisableVertexAttribArray(mCurrentProgram->getAttrib("texCoords"));

    drawTextDecorations(text, bytesCount, length, x, y, paint);
}

void OpenGLRenderer::drawPath(SkPath* path, SkPaint* paint) {
    GLuint textureUnit = 0;
    glActiveTexture(gTextureUnits[textureUnit]);

    const PathTexture* texture = mPathCache.get(path, paint);
    if (!texture) return;
    const AutoTexture autoCleanup(texture);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    uint32_t color = paint->getColor();
    const GLfloat a = alpha / 255.0f;
    const GLfloat r = a * ((color >> 16) & 0xFF) / 255.0f;
    const GLfloat g = a * ((color >>  8) & 0xFF) / 255.0f;
    const GLfloat b = a * ((color      ) & 0xFF) / 255.0f;

    const float x = texture->left - texture->offset;
    const float y = texture->top - texture->offset;

    setupTextureAlpha8(texture, textureUnit, x, y, r, g, b, a, mode, true, true);

    // Draw the mesh
    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);
    glDisableVertexAttribArray(mCurrentProgram->getAttrib("texCoords"));
}

///////////////////////////////////////////////////////////////////////////////
// Shaders
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetShader() {
    mShader = NULL;
}

void OpenGLRenderer::setupShader(SkiaShader* shader) {
    mShader = shader;
    if (mShader) {
        mShader->set(&mTextureCache, &mGradientCache);
    }
}

///////////////////////////////////////////////////////////////////////////////
// Color filters
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetColorFilter() {
    mColorFilter = NULL;
}

void OpenGLRenderer::setupColorFilter(SkiaColorFilter* filter) {
    mColorFilter = filter;
}

///////////////////////////////////////////////////////////////////////////////
// Drop shadow
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::resetShadow() {
    mHasShadow = false;
}

void OpenGLRenderer::setupShadow(float radius, float dx, float dy, int color) {
    mHasShadow = true;
    mShadowRadius = radius;
    mShadowDx = dx;
    mShadowDy = dy;
    mShadowColor = color;
}

///////////////////////////////////////////////////////////////////////////////
// Drawing implementation
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setupShadow(const ShadowTexture* texture, float x, float y,
        SkXfermode::Mode mode) {
    const float sx = x - texture->left + mShadowDx;
    const float sy = y - texture->top + mShadowDy;

    const GLfloat a = ((mShadowColor >> 24) & 0xFF) / 255.0f;
    const GLfloat r = a * ((mShadowColor >> 16) & 0xFF) / 255.0f;
    const GLfloat g = a * ((mShadowColor >>  8) & 0xFF) / 255.0f;
    const GLfloat b = a * ((mShadowColor      ) & 0xFF) / 255.0f;

    GLuint textureUnit = 0;
    setupTextureAlpha8(texture, textureUnit, sx, sy, r, g, b, a, mode, true, false);
}

void OpenGLRenderer::setupTextureAlpha8(const Texture* texture, GLuint& textureUnit,
        float x, float y, float r, float g, float b, float a, SkXfermode::Mode mode,
        bool transforms, bool applyFilters) {
    setupTextureAlpha8(texture->id, texture->width, texture->height, textureUnit,
            x, y, r, g, b, a, mode, transforms, applyFilters);
}

void OpenGLRenderer::setupTextureAlpha8(GLuint texture, uint32_t width, uint32_t height,
        GLuint& textureUnit, float x, float y, float r, float g, float b, float a,
        SkXfermode::Mode mode, bool transforms, bool applyFilters) {
     // Describe the required shaders
     ProgramDescription description;
     description.hasTexture = true;
     description.hasAlpha8Texture = true;

     if (applyFilters) {
         if (mShader) {
             mShader->describe(description, mExtensions);
         }
         if (mColorFilter) {
             mColorFilter->describe(description, mExtensions);
         }
     }

     // Build and use the appropriate shader
     useProgram(mProgramCache.get(description));

     // Setup the blending mode
     chooseBlending(true, mode);
     bindTexture(texture, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, textureUnit);
     glUniform1i(mCurrentProgram->getUniform("sampler"), textureUnit);

     int texCoordsSlot = mCurrentProgram->getAttrib("texCoords");
     glEnableVertexAttribArray(texCoordsSlot);

     // Setup attributes
     glVertexAttribPointer(mCurrentProgram->position, 2, GL_FLOAT, GL_FALSE,
             gMeshStride, &mMeshVertices[0].position[0]);
     glVertexAttribPointer(texCoordsSlot, 2, GL_FLOAT, GL_FALSE,
             gMeshStride, &mMeshVertices[0].texture[0]);

     // Setup uniforms
     if (transforms) {
         mModelView.loadTranslate(x, y, 0.0f);
         mModelView.scale(width, height, 1.0f);
     } else {
         mModelView.loadIdentity();
     }
     mCurrentProgram->set(mOrthoMatrix, mModelView, mSnapshot->transform);
     glUniform4f(mCurrentProgram->color, r, g, b, a);

     textureUnit++;
     if (applyFilters) {
         // Setup attributes and uniforms required by the shaders
         if (mShader) {
             mShader->setupProgram(mCurrentProgram, mModelView, *mSnapshot, &textureUnit);
         }
         if (mColorFilter) {
             mColorFilter->setupProgram(mCurrentProgram);
         }
     }
}

#define kStdStrikeThru_Offset   (-6.0f / 21.0f)
#define kStdUnderline_Offset    (1.0f / 9.0f)
#define kStdUnderline_Thickness (1.0f / 18.0f)

void OpenGLRenderer::drawTextDecorations(const char* text, int bytesCount, float length,
        float x, float y, SkPaint* paint) {
    // Handle underline and strike-through
    uint32_t flags = paint->getFlags();
    if (flags & (SkPaint::kUnderlineText_Flag | SkPaint::kStrikeThruText_Flag)) {
        float underlineWidth = length;
        // If length is > 0.0f, we already measured the text for the text alignment
        if (length <= 0.0f) {
            underlineWidth = paint->measureText(text, bytesCount);
        }

        float offsetX = 0;
        switch (paint->getTextAlign()) {
            case SkPaint::kCenter_Align:
                offsetX = underlineWidth * 0.5f;
                break;
            case SkPaint::kRight_Align:
                offsetX = underlineWidth;
                break;
            default:
                break;
        }

        if (underlineWidth > 0.0f) {
            float textSize = paint->getTextSize();
            float height = textSize * kStdUnderline_Thickness;

            float left = x - offsetX;
            float top = 0.0f;
            float right = left + underlineWidth;
            float bottom = 0.0f;

            if (flags & SkPaint::kUnderlineText_Flag) {
                top = y + textSize * kStdUnderline_Offset;
                bottom = top + height;
                drawRect(left, top, right, bottom, paint);
            }

            if (flags & SkPaint::kStrikeThruText_Flag) {
                top = y + textSize * kStdStrikeThru_Offset;
                bottom = top + height;
                drawRect(left, top, right, bottom, paint);
            }
        }
    }
}

void OpenGLRenderer::drawColorRect(float left, float top, float right, float bottom,
        int color, SkXfermode::Mode mode, bool ignoreTransform) {
    // If a shader is set, preserve only the alpha
    if (mShader) {
        color |= 0x00ffffff;
    }

    // Render using pre-multiplied alpha
    const int alpha = (color >> 24) & 0xFF;
    const GLfloat a = alpha / 255.0f;
    const GLfloat r = a * ((color >> 16) & 0xFF) / 255.0f;
    const GLfloat g = a * ((color >>  8) & 0xFF) / 255.0f;
    const GLfloat b = a * ((color      ) & 0xFF) / 255.0f;

    GLuint textureUnit = 0;

    // Setup the blending mode
    chooseBlending(alpha < 255 || (mShader && mShader->blend()), mode);

    // Describe the required shaders
    ProgramDescription description;
    if (mShader) {
        mShader->describe(description, mExtensions);
    }
    if (mColorFilter) {
        mColorFilter->describe(description, mExtensions);
    }

    // Build and use the appropriate shader
    useProgram(mProgramCache.get(description));

    // Setup attributes
    glVertexAttribPointer(mCurrentProgram->position, 2, GL_FLOAT, GL_FALSE,
            gMeshStride, &mMeshVertices[0].position[0]);

    // Setup uniforms
    mModelView.loadTranslate(left, top, 0.0f);
    mModelView.scale(right - left, bottom - top, 1.0f);
    if (!ignoreTransform) {
        mCurrentProgram->set(mOrthoMatrix, mModelView, mSnapshot->transform);
    } else {
        mat4 identity;
        mCurrentProgram->set(mOrthoMatrix, mModelView, identity);
    }
    glUniform4f(mCurrentProgram->color, r, g, b, a);

    // Setup attributes and uniforms required by the shaders
    if (mShader) {
        mShader->setupProgram(mCurrentProgram, mModelView, *mSnapshot, &textureUnit);
    }
    if (mColorFilter) {
        mColorFilter->setupProgram(mCurrentProgram);
    }

    // Draw the mesh
    glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        const Texture* texture, const SkPaint* paint) {
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    drawTextureMesh(left, top, right, bottom, texture->id, alpha / 255.0f, mode, texture->blend,
            &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0], NULL);
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        GLuint texture, float alpha, SkXfermode::Mode mode, bool blend) {
    drawTextureMesh(left, top, right, bottom, texture, alpha, mode, blend,
            &mMeshVertices[0].position[0], &mMeshVertices[0].texture[0], NULL);
}

void OpenGLRenderer::drawTextureMesh(float left, float top, float right, float bottom,
        GLuint texture, float alpha, SkXfermode::Mode mode, bool blend,
        GLvoid* vertices, GLvoid* texCoords, GLvoid* indices, GLsizei elementsCount) {
    ProgramDescription description;
    description.hasTexture = true;
    if (mColorFilter) {
        mColorFilter->describe(description, mExtensions);
    }

    mModelView.loadTranslate(left, top, 0.0f);
    mModelView.scale(right - left, bottom - top, 1.0f);

    useProgram(mProgramCache.get(description));
    mCurrentProgram->set(mOrthoMatrix, mModelView, mSnapshot->transform);

    chooseBlending(blend || alpha < 1.0f, mode);

    // Texture
    bindTexture(texture, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, 0);
    glUniform1i(mCurrentProgram->getUniform("sampler"), 0);

    // Always premultiplied
    glUniform4f(mCurrentProgram->color, alpha, alpha, alpha, alpha);

    // Mesh
    int texCoordsSlot = mCurrentProgram->getAttrib("texCoords");
    glEnableVertexAttribArray(texCoordsSlot);
    glVertexAttribPointer(mCurrentProgram->position, 2, GL_FLOAT, GL_FALSE,
            gMeshStride, vertices);
    glVertexAttribPointer(texCoordsSlot, 2, GL_FLOAT, GL_FALSE, gMeshStride, texCoords);

    // Color filter
    if (mColorFilter) {
        mColorFilter->setupProgram(mCurrentProgram);
    }

    if (!indices) {
        glDrawArrays(GL_TRIANGLE_STRIP, 0, gMeshCount);
    } else {
        glDrawElements(GL_TRIANGLES, elementsCount, GL_UNSIGNED_SHORT, indices);
    }
    glDisableVertexAttribArray(texCoordsSlot);
}

void OpenGLRenderer::chooseBlending(bool blend, SkXfermode::Mode mode, bool isPremultiplied) {
    blend = blend || mode != SkXfermode::kSrcOver_Mode;
    if (blend) {
        if (!mBlend) {
            glEnable(GL_BLEND);
        }

        GLenum sourceMode = gBlends[mode].src;
        GLenum destMode = gBlends[mode].dst;
        if (!isPremultiplied && sourceMode == GL_ONE) {
            sourceMode = GL_SRC_ALPHA;
        }

        if (sourceMode != mLastSrcMode || destMode != mLastDstMode) {
            glBlendFunc(sourceMode, destMode);
            mLastSrcMode = sourceMode;
            mLastDstMode = destMode;
        }
    } else if (mBlend) {
        glDisable(GL_BLEND);
    }
    mBlend = blend;
}

bool OpenGLRenderer::useProgram(Program* program) {
    if (!program->isInUse()) {
        if (mCurrentProgram != NULL) mCurrentProgram->remove();
        program->use();
        mCurrentProgram = program;
        return false;
    }
    return true;
}

void OpenGLRenderer::resetDrawTextureTexCoords(float u1, float v1, float u2, float v2) {
    TextureVertex* v = &mMeshVertices[0];
    TextureVertex::setUV(v++, u1, v1);
    TextureVertex::setUV(v++, u2, v1);
    TextureVertex::setUV(v++, u1, v2);
    TextureVertex::setUV(v++, u2, v2);
}

void OpenGLRenderer::getAlphaAndMode(const SkPaint* paint, int* alpha, SkXfermode::Mode* mode) {
    if (paint) {
        const bool isMode = SkXfermode::IsMode(paint->getXfermode(), mode);
        if (!isMode) {
            // Assume SRC_OVER
            *mode = SkXfermode::kSrcOver_Mode;
        }

        // Skia draws using the color's alpha channel if < 255
        // Otherwise, it uses the paint's alpha
        int color = paint->getColor();
        *alpha = (color >> 24) & 0xFF;
        if (*alpha == 255) {
            *alpha = paint->getAlpha();
        }
    } else {
        *mode = SkXfermode::kSrcOver_Mode;
        *alpha = 255;
    }
}

void OpenGLRenderer::bindTexture(GLuint texture, GLenum wrapS, GLenum wrapT, GLuint textureUnit) {
    glActiveTexture(gTextureUnits[textureUnit]);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapS);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapT);
}

}; // namespace uirenderer
}; // namespace android

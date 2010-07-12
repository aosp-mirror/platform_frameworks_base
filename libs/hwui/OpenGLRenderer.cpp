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

#include <cutils/properties.h>
#include <utils/Log.h>

#include "OpenGLRenderer.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// These properties are defined in mega-bytes
#define PROPERTY_TEXTURE_CACHE_SIZE "ro.hwui.texture_cache_size"
#define PROPERTY_LAYER_CACHE_SIZE "ro.hwui.layer_cache_size"

#define DEFAULT_TEXTURE_CACHE_SIZE 20
#define DEFAULT_LAYER_CACHE_SIZE 10
#define DEFAULT_PATCH_CACHE_SIZE 100

// Converts a number of mega-bytes into bytes
#define MB(s) s * 1024 * 1024

// Generates simple and textured vertices
#define SV(x, y) { { x, y } }
#define FV(x, y, u, v) { { x, y }, { u, v } }

///////////////////////////////////////////////////////////////////////////////
// Globals
///////////////////////////////////////////////////////////////////////////////

static const SimpleVertex gDrawColorVertices[] = {
        SV(0.0f, 0.0f),
        SV(1.0f, 0.0f),
        SV(0.0f, 1.0f),
        SV(1.0f, 1.0f)
};
static const GLsizei gDrawColorVertexStride = sizeof(SimpleVertex);
static const GLsizei gDrawColorVertexCount = 4;

// This array is never used directly but used as a memcpy source in the
// OpenGLRenderer constructor
static const TextureVertex gDrawTextureVertices[] = {
        FV(0.0f, 0.0f, 0.0f, 0.0f),
        FV(1.0f, 0.0f, 1.0f, 0.0f),
        FV(0.0f, 1.0f, 0.0f, 1.0f),
        FV(1.0f, 1.0f, 1.0f, 1.0f)
};
static const GLsizei gDrawTextureVertexStride = sizeof(TextureVertex);
static const GLsizei gDrawTextureVertexCount = 4;

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

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

OpenGLRenderer::OpenGLRenderer():
        mBlend(false), mLastSrcMode(GL_ZERO), mLastDstMode(GL_ZERO),
        mTextureCache(MB(DEFAULT_TEXTURE_CACHE_SIZE)),
        mLayerCache(MB(DEFAULT_LAYER_CACHE_SIZE)),
        mPatchCache(DEFAULT_PATCH_CACHE_SIZE) {
    LOGD("Create OpenGLRenderer");

    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_TEXTURE_CACHE_SIZE, property, NULL) > 0) {
        LOGD("  Setting texture cache size to %sMB", property);
        mTextureCache.setMaxSize(MB(atoi(property)));
    } else {
        LOGD("  Using default texture cache size of %dMB", DEFAULT_TEXTURE_CACHE_SIZE);
    }

    if (property_get(PROPERTY_LAYER_CACHE_SIZE, property, NULL) > 0) {
        LOGD("  Setting layer cache size to %sMB", property);
        mLayerCache.setMaxSize(MB(atoi(property)));
    } else {
        LOGD("  Using default layer cache size of %dMB", DEFAULT_LAYER_CACHE_SIZE);
    }

    mDrawColorShader = new DrawColorProgram;
    mDrawTextureShader = new DrawTextureProgram;

    memcpy(mDrawTextureVertices, gDrawTextureVertices, sizeof(gDrawTextureVertices));
}

OpenGLRenderer::~OpenGLRenderer() {
    LOGD("Destroy OpenGLRenderer");

    mTextureCache.clear();
    mLayerCache.clear();
}

///////////////////////////////////////////////////////////////////////////////
// Setup
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setViewport(int width, int height) {
    glViewport(0, 0, width, height);

    mat4 ortho;
    ortho.loadOrtho(0, width, height, 0, -1, 1);
    ortho.copyTo(mOrthoMatrix);

    mWidth = width;
    mHeight = height;
    mFirstSnapshot.height = height;
}

void OpenGLRenderer::prepare() {
    mSnapshot = &mFirstSnapshot;
    mSaveCount = 0;

    glDisable(GL_SCISSOR_TEST);

    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glEnable(GL_SCISSOR_TEST);
    glScissor(0, 0, mWidth, mHeight);

    mSnapshot->clipRect.set(0.0f, 0.0f, mWidth, mHeight);
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
    if (mSaveCount == 0) return;

    if (restoreSnapshot()) {
        setScissorFromClip();
    }
}

void OpenGLRenderer::restoreToCount(int saveCount) {
    if (saveCount <= 0 || saveCount > mSaveCount) return;

    bool restoreClip = false;

    while (mSaveCount != saveCount - 1) {
        restoreClip |= restoreSnapshot();
    }

    if (restoreClip) {
        setScissorFromClip();
    }
}

int OpenGLRenderer::saveSnapshot() {
    mSnapshot = new Snapshot(mSnapshot);
    return ++mSaveCount;
}

bool OpenGLRenderer::restoreSnapshot() {
    bool restoreClip = mSnapshot->flags & Snapshot::kFlagClipSet;
    bool restoreLayer = mSnapshot->flags & Snapshot::kFlagIsLayer;
    bool restoreOrtho = mSnapshot->flags & Snapshot::kFlagDirtyOrtho;

    sp<Snapshot> current = mSnapshot;
    sp<Snapshot> previous = mSnapshot->previous;

    if (restoreOrtho) {
        memcpy(mOrthoMatrix, current->orthoMatrix, sizeof(mOrthoMatrix));
    }

    if (restoreLayer) {
        composeLayer(current, previous);
    }

    mSnapshot = previous;
    mSaveCount--;

    return restoreClip;
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
    const Rect& clip = previous->getMappedClip();
    glScissor(clip.left, mHeight - clip.bottom, clip.getWidth(), clip.getHeight());

    Layer* layer = current->layer;

    // Compute the correct texture coordinates for the FBO texture
    // The texture is currently as big as the window but drawn with
    // a quad of the appropriate size
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

    LAYER_LOGD("Requesting layer %dx%d", size.width, size.height);
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

    // Save the layer in the snapshot
    snapshot->flags |= Snapshot::kFlagIsLayer;
    layer->mode = mode;
    layer->alpha = alpha / 255.0f;
    layer->layer.set(left, top, right, bottom);

    snapshot->layer = layer;
    snapshot->fbo = layer->fbo;

    // Creates a new snapshot to draw into the FBO
    saveSnapshot();
    // TODO: This doesn't preserve other transformations (check Skia first)
    mSnapshot->transform.loadTranslate(-left, -top, 0.0f);
    mSnapshot->clipRect.set(left, top, right, bottom);
    mSnapshot->height = bottom - top;
    setScissorFromClip();

    mSnapshot->flags = Snapshot::kFlagDirtyTransform | Snapshot::kFlagDirtyOrtho |
            Snapshot::kFlagClipSet;
    memcpy(mSnapshot->orthoMatrix, mOrthoMatrix, sizeof(mOrthoMatrix));

    // Change the ortho projection
    mat4 ortho;
    ortho.loadOrtho(0.0f, right - left, bottom - top, 0.0f, 0.0f, 1.0f);
    ortho.copyTo(mOrthoMatrix);

    return true;
}

///////////////////////////////////////////////////////////////////////////////
// Transforms
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::translate(float dx, float dy) {
    mSnapshot->transform.translate(dx, dy, 0.0f);
    mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

void OpenGLRenderer::rotate(float degrees) {
    mSnapshot->transform.rotate(degrees, 0.0f, 0.0f, 1.0f);
    mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

void OpenGLRenderer::scale(float sx, float sy) {
    mSnapshot->transform.scale(sx, sy, 1.0f);
    mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

void OpenGLRenderer::setMatrix(SkMatrix* matrix) {
    mSnapshot->transform.load(*matrix);
    mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

void OpenGLRenderer::getMatrix(SkMatrix* matrix) {
    mSnapshot->transform.copyTo(*matrix);
}

void OpenGLRenderer::concatMatrix(SkMatrix* matrix) {
    mat4 m(*matrix);
    mSnapshot->transform.multiply(m);
    mSnapshot->flags |= Snapshot::kFlagDirtyTransform;
}

///////////////////////////////////////////////////////////////////////////////
// Clipping
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::setScissorFromClip() {
    const Rect& clip = mSnapshot->getMappedClip();
    glScissor(clip.left, mSnapshot->height - clip.bottom, clip.getWidth(), clip.getHeight());
}

const Rect& OpenGLRenderer::getClipBounds() {
    return mSnapshot->clipRect;
}

bool OpenGLRenderer::quickReject(float left, float top, float right, float bottom) {
    /*
     * The documentation of quickReject() indicates that the specified rect
     * is transformed before being compared to the clip rect. However, the
     * clip rect is not stored transformed in the snapshot and can thus be
     * compared directly
     *
     * The following code can be used instead to performed a mapped comparison:
     *
     *     mSnapshot->transform.mapRect(r);
     *     const Rect& clip = mSnapshot->getMappedClip();
     *     return !clip.intersects(r);
     */
    Rect r(left, top, right, bottom);
    return !mSnapshot->clipRect.intersects(r);
}

bool OpenGLRenderer::clipRect(float left, float top, float right, float bottom) {
    bool clipped = mSnapshot->clipRect.intersect(left, top, right, bottom);
    if (clipped) {
        mSnapshot->flags |= Snapshot::kFlagClipSet;
        setScissorFromClip();
    }
    return clipped;
}

///////////////////////////////////////////////////////////////////////////////
// Drawing
///////////////////////////////////////////////////////////////////////////////

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap, float left, float top, const SkPaint* paint) {
    const Texture* texture = mTextureCache.get(bitmap);
    drawTextureRect(left, top, left + texture->width, top + texture->height, texture, paint);
}

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap, const SkMatrix* matrix, const SkPaint* paint) {
    Rect r(0.0f, 0.0f, bitmap->width(), bitmap->height());
    const mat4 transform(*matrix);
    transform.mapRect(r);

    const Texture* texture = mTextureCache.get(bitmap);
    drawTextureRect(r.left, r.top, r.right, r.bottom, texture, paint);
}

void OpenGLRenderer::drawBitmap(SkBitmap* bitmap,
         float srcLeft, float srcTop, float srcRight, float srcBottom,
         float dstLeft, float dstTop, float dstRight, float dstBottom,
         const SkPaint* paint) {
    const Texture* texture = mTextureCache.get(bitmap);

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
    const Texture* texture = mTextureCache.get(bitmap);

    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    Patch* mesh = mPatchCache.get(patch);
    mesh->updateVertices(bitmap, left, top, right, bottom,
            &patch->xDivs[0], &patch->yDivs[0], patch->numXDivs, patch->numYDivs);

    // Specify right and bottom as +1.0f from left/top to prevent scaling since the
    // patch mesh already defines the final size
    drawTextureMesh(left, top, left + 1.0f, top + 1.0f, texture->id, alpha / 255.0f, mode,
            texture->blend, true, &mesh->vertices[0].position[0],
            &mesh->vertices[0].texture[0], mesh->indices, mesh->indicesCount);
}

void OpenGLRenderer::drawColor(int color, SkXfermode::Mode mode) {
    const Rect& clip = mSnapshot->clipRect;
    drawColorRect(clip.left, clip.top, clip.right, clip.bottom, color, mode);
}

void OpenGLRenderer::drawRect(float left, float top, float right, float bottom, const SkPaint* p) {
    SkXfermode::Mode mode;

    const bool isMode = SkXfermode::IsMode(p->getXfermode(), &mode);
    if (!isMode) {
        // Assume SRC_OVER
        mode = SkXfermode::kSrcOver_Mode;
    }

    // Skia draws using the color's alpha channel if < 255
    // Otherwise, it uses the paint's alpha
    int color = p->getColor();
    if (((color >> 24) & 0xFF) == 255) {
        color |= p->getAlpha() << 24;
    }

    drawColorRect(left, top, right, bottom, color, mode);
}

void OpenGLRenderer::drawColorRect(float left, float top, float right, float bottom,
        int color, SkXfermode::Mode mode) {
    const int alpha = (color >> 24) & 0xFF;
    const GLfloat a = alpha                  / 255.0f;
    const GLfloat r = ((color >> 16) & 0xFF) / 255.0f;
    const GLfloat g = ((color >>  8) & 0xFF) / 255.0f;
    const GLfloat b = ((color      ) & 0xFF) / 255.0f;

    chooseBlending(alpha < 255, mode, true);

    mModelView.loadTranslate(left, top, 0.0f);
    mModelView.scale(right - left, bottom - top, 1.0f);

    mDrawColorShader->use(&mOrthoMatrix[0], mModelView, mSnapshot->transform);

    const GLvoid* p = &gDrawColorVertices[0].position[0];

    glEnableVertexAttribArray(mDrawColorShader->position);
    glVertexAttribPointer(mDrawColorShader->position, 2, GL_FLOAT, GL_FALSE,
            gDrawColorVertexStride, p);
    glUniform4f(mDrawColorShader->color, r, g, b, a);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, gDrawColorVertexCount);

    glDisableVertexAttribArray(mDrawColorShader->position);
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        const Texture* texture, const SkPaint* paint, bool isPremultiplied) {
    int alpha;
    SkXfermode::Mode mode;
    getAlphaAndMode(paint, &alpha, &mode);

    drawTextureMesh(left, top, right, bottom, texture->id, alpha / 255.0f, mode, texture->blend,
            isPremultiplied, &mDrawTextureVertices[0].position[0],
            &mDrawTextureVertices[0].texture[0], NULL);
}

void OpenGLRenderer::drawTextureRect(float left, float top, float right, float bottom,
        GLuint texture, float alpha, SkXfermode::Mode mode, bool blend, bool isPremultiplied) {
    drawTextureMesh(left, top, right, bottom, texture, alpha, mode, blend, isPremultiplied,
            &mDrawTextureVertices[0].position[0], &mDrawTextureVertices[0].texture[0], NULL);
}

void OpenGLRenderer::drawTextureMesh(float left, float top, float right, float bottom,
        GLuint texture, float alpha, SkXfermode::Mode mode, bool blend, bool isPremultiplied,
        GLvoid* vertices, GLvoid* texCoords, GLvoid* indices, GLsizei elementsCount) {
    mModelView.loadTranslate(left, top, 0.0f);
    mModelView.scale(right - left, bottom - top, 1.0f);

    mDrawTextureShader->use(&mOrthoMatrix[0], mModelView, mSnapshot->transform);

    chooseBlending(blend || alpha < 1.0f, mode, isPremultiplied);

    glBindTexture(GL_TEXTURE_2D, texture);

    // TODO handle tiling and filtering here

    glActiveTexture(GL_TEXTURE0);
    glUniform1i(mDrawTextureShader->sampler, 0);
    glUniform4f(mDrawTextureShader->color, 1.0f, 1.0f, 1.0f, alpha);

    glEnableVertexAttribArray(mDrawTextureShader->position);
    glVertexAttribPointer(mDrawTextureShader->position, 2, GL_FLOAT, GL_FALSE,
            gDrawTextureVertexStride, vertices);

    glEnableVertexAttribArray(mDrawTextureShader->texCoords);
    glVertexAttribPointer(mDrawTextureShader->texCoords, 2, GL_FLOAT, GL_FALSE,
            gDrawTextureVertexStride, texCoords);

    if (!indices) {
        glDrawArrays(GL_TRIANGLE_STRIP, 0, gDrawTextureVertexCount);
    } else {
        glDrawElements(GL_TRIANGLES, elementsCount, GL_UNSIGNED_SHORT, indices);
    }

    glDisableVertexAttribArray(mDrawTextureShader->position);
    glDisableVertexAttribArray(mDrawTextureShader->texCoords);

    glBindTexture(GL_TEXTURE_2D, 0);
}

void OpenGLRenderer::chooseBlending(bool blend, SkXfermode::Mode mode, bool isPremultiplied) {
    // In theory we should not blend if the mode is Src, but it's rare enough
    // that it's not worth it
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

void OpenGLRenderer::resetDrawTextureTexCoords(float u1, float v1, float u2, float v2) {
    TextureVertex* v = &mDrawTextureVertices[0];
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

}; // namespace uirenderer
}; // namespace android

/*
 * Copyright (C) 2015 The Android Open Source Project
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
#include "GlopBuilder.h"

#include "Caches.h"
#include "Glop.h"
#include "Matrix.h"
#include "Texture.h"
#include "renderstate/MeshState.h"
#include "renderstate/RenderState.h"
#include "utils/PaintUtils.h"

#include <GLES2/gl2.h>
#include <SkPaint.h>

namespace android {
namespace uirenderer {

GlopBuilder::GlopBuilder(RenderState& renderState, Caches& caches, Glop* outGlop)
        : mRenderState(renderState)
        , mCaches(caches)
        , mOutGlop(outGlop){
}

GlopBuilder& GlopBuilder::setMeshUnitQuad() {
    mOutGlop->mesh.vertexFlags = static_cast<VertexAttribFlags>(0);
    mOutGlop->mesh.primitiveMode = GL_TRIANGLE_STRIP;
    mOutGlop->mesh.vertexBufferObject = mRenderState.meshState().getUnitQuadVBO();
    mOutGlop->mesh.indexBufferObject = 0;
    mOutGlop->mesh.vertexCount = 4;
    mOutGlop->mesh.stride = kTextureVertexStride;
    return *this;
}

GlopBuilder& GlopBuilder::setTransformAndRect(ModelViewMode mode,
        const Matrix4& ortho, const Matrix4& transform,
        float left, float top, float right, float bottom, bool offset) {
    mOutGlop->transform.ortho.load(ortho);

    mOutGlop->transform.modelView.loadTranslate(left, top, 0.0f);
    if (mode == kModelViewMode_TranslateAndScale) {
        mOutGlop->transform.modelView.scale(right - left, bottom - top, 1.0f);
    }

    mOutGlop->transform.canvas.load(transform);

    mOutGlop->transform.offset = offset;

    mOutGlop->bounds.set(left, top, right, bottom);
    mOutGlop->transform.canvas.mapRect(mOutGlop->bounds);
    return *this;
}

GlopBuilder& GlopBuilder::setPaint(const SkPaint* paint, float alphaScale) {
    // TODO: support null paint
    const SkShader* shader = paint->getShader();
    const SkColorFilter* colorFilter = paint->getColorFilter();

    SkXfermode::Mode mode = PaintUtils::getXfermode(paint->getXfermode());
    if (mode != SkXfermode::kClear_Mode) {
        int color = paint->getColor();
        float alpha = (SkColorGetA(color) / 255.0f) * alphaScale;
        if (shader) {
            // shader discards color channels
            color |= 0x00FFFFFF;
        }
        mOutGlop->fill.color = {
                alpha,
                alpha * SkColorGetR(color),
                alpha * SkColorGetG(color),
                alpha * SkColorGetB(color)
        };
    } else {
        mOutGlop->fill.color = { 1, 0, 0, 0 };
    }
    const bool SWAP_SRC_DST = false;
    const bool HAS_FRAMEBUFFER_FETCH = false; //mExtensions.hasFramebufferFetch();

    mOutGlop->blend = {GL_ZERO, GL_ZERO};
    if (mOutGlop->fill.color.a < 1.0f
            || (shader && !shader->isOpaque())
            || PaintUtils::isBlendedColorFilter(colorFilter)
            || mode != SkXfermode::kSrcOver_Mode) {
        if (CC_LIKELY(mode <= SkXfermode::kScreen_Mode)) {
            Blend::getFactors(mode, SWAP_SRC_DST,
                    &mOutGlop->blend.src, &mOutGlop->blend.dst);
        } else {
            // These blend modes are not supported by OpenGL directly and have
            // to be implemented using shaders. Since the shader will perform
            // the blending, don't enable GL blending off here
            // If the blend mode cannot be implemented using shaders, fall
            // back to the default SrcOver blend mode instead
            if (CC_UNLIKELY(HAS_FRAMEBUFFER_FETCH)) {
                mDescription.framebufferMode = mode;
                mDescription.swapSrcDst = SWAP_SRC_DST;
                // blending in shader, don't enable
            } else {
                // unsupported
                Blend::getFactors(SkXfermode::kSrcOver_Mode, SWAP_SRC_DST,
                        &mOutGlop->blend.src, &mOutGlop->blend.dst);
            }
        }
    }

    return *this;
}

GlopBuilder& GlopBuilder::setTexture(Texture* texture) {
    LOG_ALWAYS_FATAL("not yet supported");
    return *this;
}

void GlopBuilder::build() {
    mDescription.modulate = mOutGlop->fill.color.a < 1.0f;
    mOutGlop->fill.program = mCaches.programCache.get(mDescription);
}

} /* namespace uirenderer */
} /* namespace android */


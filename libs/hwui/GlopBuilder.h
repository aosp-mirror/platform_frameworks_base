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
#ifndef RENDERSTATE_GLOPBUILDER_H
#define RENDERSTATE_GLOPBUILDER_H

#include "OpenGLRenderer.h"
#include "Program.h"
#include "utils/Macros.h"

class SkPaint;
class SkShader;

namespace android {
namespace uirenderer {

class Caches;
class Matrix4;
class RenderState;
class Texture;
class VertexBuffer;
struct Glop;

class GlopBuilder {
    PREVENT_COPY_AND_ASSIGN(GlopBuilder);
public:
    GlopBuilder(RenderState& renderState, Caches& caches, Glop* outGlop);

    GlopBuilder& setMeshUnitQuad();
    GlopBuilder& setMeshTexturedUnitQuad(const UvMapper* uvMapper, bool isAlphaMaskTexture);
    GlopBuilder& setMeshVertexBuffer(const VertexBuffer& vertexBuffer, bool shadowInterp);
    GlopBuilder& setMeshIndexedQuads(void* vertexData, int quadCount);

    GlopBuilder& setFillPaint(const SkPaint& paint, float alphaScale);
    GlopBuilder& setFillTexturePaint(Texture& texture, bool isAlphaMaskTexture,
            const SkPaint* paint, float alphaScale);
    GlopBuilder& setFillPathTexturePaint(Texture& texture,
            const SkPaint& paint, float alphaScale);

    GlopBuilder& setTransformClip(const Matrix4& ortho, const Matrix4& transform, bool fudgingOffset);

    GlopBuilder& setModelViewMapUnitToRect(const Rect destination);
    GlopBuilder& setModelViewMapUnitToRectSnap(const Rect destination);
    GlopBuilder& setModelViewOffsetRect(float offsetX, float offsetY, const Rect source);

    GlopBuilder& setRoundRectClipState(const RoundRectClipState* roundRectClipState);

    void build();
private:
    void setFill(int color, float alphaScale, SkXfermode::Mode mode,
            const SkShader* shader, const SkColorFilter* colorFilter);

    enum StageFlags {
        kInitialStage = 0,
        kMeshStage = 1 << 0,
        kTransformStage = 1 << 1,
        kModelViewStage = 1 << 2,
        kFillStage = 1 << 3,
        kRoundRectClipStage = 1 << 4,
        kAllStages = kMeshStage | kFillStage | kTransformStage | kModelViewStage | kRoundRectClipStage,
    } mStageFlags;

    ProgramDescription mDescription;
    RenderState& mRenderState;
    Caches& mCaches;
    const SkShader* mShader;
    Glop* mOutGlop;
};

} /* namespace uirenderer */
} /* namespace android */

#endif // RENDERSTATE_GLOPBUILDER_H

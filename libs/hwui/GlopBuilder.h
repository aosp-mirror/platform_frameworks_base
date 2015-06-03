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

#include "Glop.h"
#include "OpenGLRenderer.h"
#include "Program.h"
#include "renderstate/Blend.h"
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

namespace TextureFillFlags {
    enum {
        None = 0,
        IsAlphaMaskTexture = 1 << 0,
        ForceFilter = 1 << 1,
    };
}

class GlopBuilder {
    PREVENT_COPY_AND_ASSIGN(GlopBuilder);
public:
    GlopBuilder(RenderState& renderState, Caches& caches, Glop* outGlop);

    GlopBuilder& setMeshUnitQuad();
    GlopBuilder& setMeshTexturedUnitQuad(const UvMapper* uvMapper);
    GlopBuilder& setMeshTexturedUvQuad(const UvMapper* uvMapper, const Rect uvs);
    GlopBuilder& setMeshVertexBuffer(const VertexBuffer& vertexBuffer, bool shadowInterp);
    GlopBuilder& setMeshIndexedQuads(Vertex* vertexData, int quadCount);
    GlopBuilder& setMeshTexturedMesh(TextureVertex* vertexData, int elementCount); // TODO: use indexed quads
    GlopBuilder& setMeshColoredTexturedMesh(ColorTextureVertex* vertexData, int elementCount); // TODO: use indexed quads
    GlopBuilder& setMeshTexturedIndexedQuads(TextureVertex* vertexData, int elementCount); // TODO: take quadCount
    GlopBuilder& setMeshPatchQuads(const Patch& patch);

    GlopBuilder& setFillPaint(const SkPaint& paint, float alphaScale);
    GlopBuilder& setFillTexturePaint(Texture& texture, const int textureFillFlags,
            const SkPaint* paint, float alphaScale);
    GlopBuilder& setFillPathTexturePaint(PathTexture& texture,
            const SkPaint& paint, float alphaScale);
    GlopBuilder& setFillShadowTexturePaint(ShadowTexture& texture, int shadowColor,
            const SkPaint& paint, float alphaScale);
    GlopBuilder& setFillBlack();
    GlopBuilder& setFillClear();
    GlopBuilder& setFillLayer(Texture& texture, const SkColorFilter* colorFilter,
            float alpha, SkXfermode::Mode mode, Blend::ModeOrderSwap modeUsage);
    GlopBuilder& setFillTextureLayer(Layer& layer, float alpha);

    GlopBuilder& setTransform(const Snapshot& snapshot, const int transformFlags) {
        setTransform(snapshot.getOrthoMatrix(), *snapshot.transform, transformFlags);
        return *this;
    }

    GlopBuilder& setModelViewMapUnitToRect(const Rect destination);
    GlopBuilder& setModelViewMapUnitToRectSnap(const Rect destination);
    GlopBuilder& setModelViewMapUnitToRectOptionalSnap(bool snap, const Rect& destination) {
        if (snap) {
            return setModelViewMapUnitToRectSnap(destination);
        } else {
            return setModelViewMapUnitToRect(destination);
        }
    }
    GlopBuilder& setModelViewOffsetRect(float offsetX, float offsetY, const Rect source);
    GlopBuilder& setModelViewOffsetRectSnap(float offsetX, float offsetY, const Rect source);
    GlopBuilder& setModelViewOffsetRectOptionalSnap(bool snap,
            float offsetX, float offsetY, const Rect& source) {
        if (snap) {
            return setModelViewOffsetRectSnap(offsetX, offsetY, source);
        } else {
            return setModelViewOffsetRect(offsetX, offsetY, source);
        }
    }

    GlopBuilder& setRoundRectClipState(const RoundRectClipState* roundRectClipState);

    void build();
private:
    void setFill(int color, float alphaScale,
            SkXfermode::Mode mode, Blend::ModeOrderSwap modeUsage,
            const SkShader* shader, const SkColorFilter* colorFilter);
    void setTransform(const Matrix4& ortho, const Matrix4& canvas,
            const int transformFlags);

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

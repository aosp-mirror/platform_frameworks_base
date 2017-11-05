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

#pragma once

#include "Glop.h"
#include "Program.h"
#include "renderstate/Blend.h"
#include "utils/Macros.h"

class SkPaint;
class SkShader;

namespace android {
namespace uirenderer {

class Caches;
class GlLayer;
class Matrix4;
class Patch;
class RenderState;
class Texture;
class UvMapper;
class VertexBuffer;
struct PathTexture;
struct ShadowTexture;

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

    GlopBuilder& setMeshTexturedIndexedVbo(GLuint vbo, GLsizei elementCount);
    GlopBuilder& setMeshUnitQuad();
    GlopBuilder& setMeshTexturedUnitQuad(const UvMapper* uvMapper);
    GlopBuilder& setMeshTexturedUvQuad(const UvMapper* uvMapper, const Rect uvs);
    GlopBuilder& setMeshVertexBuffer(const VertexBuffer& vertexBuffer);
    GlopBuilder& setMeshIndexedQuads(Vertex* vertexData, int quadCount);
    GlopBuilder& setMeshColoredTexturedMesh(ColorTextureVertex* vertexData,
                                            int elementCount);  // TODO: use indexed quads
    GlopBuilder& setMeshTexturedIndexedQuads(TextureVertex* vertexData,
                                             int elementCount);  // TODO: take quadCount
    GlopBuilder& setMeshPatchQuads(const Patch& patch);

    GlopBuilder& setFillPaint(const SkPaint& paint, float alphaScale,
                              bool shadowInterp = false);  // TODO: avoid boolean with default
    GlopBuilder& setFillTexturePaint(Texture& texture, const int textureFillFlags,
                                     const SkPaint* paint, float alphaScale);
    GlopBuilder& setFillPathTexturePaint(PathTexture& texture, const SkPaint& paint,
                                         float alphaScale);
    GlopBuilder& setFillShadowTexturePaint(ShadowTexture& texture, int shadowColor,
                                           const SkPaint& paint, float alphaScale);
    GlopBuilder& setFillBlack();
    GlopBuilder& setFillClear();
    GlopBuilder& setFillLayer(Texture& texture, const SkColorFilter* colorFilter, float alpha,
                              SkBlendMode mode, Blend::ModeOrderSwap modeUsage);
    GlopBuilder& setFillTextureLayer(GlLayer& layer, float alpha);
    // TODO: setFillLayer normally forces its own wrap & filter mode,
    // which isn't always correct.
    GlopBuilder& setFillExternalTexture(Texture& texture, Matrix4& textureTransform,
                                        bool requiresFilter);

    GlopBuilder& setTransform(const Matrix4& canvas, const int transformFlags);

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
    GlopBuilder& setModelViewOffsetRectOptionalSnap(bool snap, float offsetX, float offsetY,
                                                    const Rect& source) {
        if (snap) {
            return setModelViewOffsetRectSnap(offsetX, offsetY, source);
        } else {
            return setModelViewOffsetRect(offsetX, offsetY, source);
        }
    }
    GlopBuilder& setModelViewIdentityEmptyBounds() {
        // pass empty rect since not needed for damage / snap
        return setModelViewOffsetRect(0, 0, Rect());
    }

    GlopBuilder& setRoundRectClipState(const RoundRectClipState* roundRectClipState);

    GlopBuilder& setGammaCorrection(bool enabled);

    void build();

    static void dump(const Glop& glop);

private:
    void setFill(int color, float alphaScale, SkBlendMode mode, Blend::ModeOrderSwap modeUsage,
                 const SkShader* shader, const SkColorFilter* colorFilter);

    enum StageFlags {
        kInitialStage = 0,
        kMeshStage = 1 << 0,
        kTransformStage = 1 << 1,
        kModelViewStage = 1 << 2,
        kFillStage = 1 << 3,
        kRoundRectClipStage = 1 << 4,
        kAllStages =
                kMeshStage | kFillStage | kTransformStage | kModelViewStage | kRoundRectClipStage,
    } mStageFlags;

    ProgramDescription mDescription;
    RenderState& mRenderState;
    Caches& mCaches;
    const SkShader* mShader;
    Glop* mOutGlop;
};

} /* namespace uirenderer */
} /* namespace android */

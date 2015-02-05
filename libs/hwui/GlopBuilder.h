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

namespace android {
namespace uirenderer {

class Caches;
struct Glop;
class RenderState;
class Texture;
class Matrix4;

class GlopBuilder {
    PREVENT_COPY_AND_ASSIGN(GlopBuilder);
public:
    GlopBuilder(RenderState& renderState, Caches& caches, Glop* outGlop);
    GlopBuilder& setMeshUnitQuad();
    GlopBuilder& setTransformAndRect(ModelViewMode mode,
            const Matrix4& ortho, const Matrix4& transform,
            float left, float top, float right, float bottom, bool offset);
    GlopBuilder& setPaint(const SkPaint* paint, float alphaScale);
    GlopBuilder& setTexture(Texture* texture);
    void build();
private:
    ProgramDescription mDescription;
    RenderState& mRenderState;
    Caches& mCaches;
    Glop* mOutGlop;
};

} /* namespace uirenderer */
} /* namespace android */

#endif // RENDERSTATE_GLOPBUILDER_H

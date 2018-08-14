/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <gtest/gtest.h>

#include "Glop.h"
#include "GlopBuilder.h"
#include "Rect.h"
#include "tests/common/TestUtils.h"
#include "utils/Color.h"

#include <SkPaint.h>

using namespace android::uirenderer;

static void expectFillEq(Glop::Fill& expectedFill, Glop::Fill& builtFill) {
    EXPECT_EQ(expectedFill.colorEnabled, builtFill.colorEnabled);
    if (expectedFill.colorEnabled) EXPECT_EQ(expectedFill.color, builtFill.color);

    EXPECT_EQ(expectedFill.filterMode, builtFill.filterMode);
    if (expectedFill.filterMode == ProgramDescription::ColorFilterMode::Blend) {
        EXPECT_EQ(expectedFill.filter.color, builtFill.filter.color);
    } else if (expectedFill.filterMode == ProgramDescription::ColorFilterMode::Matrix) {
        Glop::Fill::Filter::Matrix& expectedMatrix = expectedFill.filter.matrix;
        Glop::Fill::Filter::Matrix& builtMatrix = expectedFill.filter.matrix;
        EXPECT_TRUE(std::memcmp(expectedMatrix.matrix, builtMatrix.matrix,
                                sizeof(Glop::Fill::Filter::Matrix::matrix)));
        EXPECT_TRUE(std::memcmp(expectedMatrix.vector, builtMatrix.vector,
                                sizeof(Glop::Fill::Filter::Matrix::vector)));
    }
    EXPECT_EQ(expectedFill.skiaShaderData.skiaShaderType, builtFill.skiaShaderData.skiaShaderType);
    EXPECT_EQ(expectedFill.texture.clamp, builtFill.texture.clamp);
    EXPECT_EQ(expectedFill.texture.filter, builtFill.texture.filter);
    EXPECT_TRUE((expectedFill.texture.texture && builtFill.texture.texture) ||
                (!expectedFill.texture.texture && !builtFill.texture.texture));
    if (expectedFill.texture.texture) {
        EXPECT_EQ(expectedFill.texture.texture->target(), builtFill.texture.texture->target());
    }
    EXPECT_EQ(expectedFill.texture.textureTransform, builtFill.texture.textureTransform);
}

static void expectBlendEq(Glop::Blend& expectedBlend, Glop::Blend& builtBlend) {
    EXPECT_EQ(expectedBlend.src, builtBlend.src);
    EXPECT_EQ(expectedBlend.dst, builtBlend.dst);
}

static void expectMeshEq(Glop::Mesh& expectedMesh, Glop::Mesh& builtMesh) {
    EXPECT_EQ(expectedMesh.elementCount, builtMesh.elementCount);
    EXPECT_EQ(expectedMesh.primitiveMode, builtMesh.primitiveMode);
    EXPECT_EQ(expectedMesh.indices.indices, builtMesh.indices.indices);
    EXPECT_EQ(expectedMesh.indices.bufferObject, builtMesh.indices.bufferObject);
    EXPECT_EQ(expectedMesh.vertices.attribFlags, builtMesh.vertices.attribFlags);
    EXPECT_EQ(expectedMesh.vertices.bufferObject, builtMesh.vertices.bufferObject);
    EXPECT_EQ(expectedMesh.vertices.color, builtMesh.vertices.color);
    EXPECT_EQ(expectedMesh.vertices.position, builtMesh.vertices.position);
    EXPECT_EQ(expectedMesh.vertices.stride, builtMesh.vertices.stride);
    EXPECT_EQ(expectedMesh.vertices.texCoord, builtMesh.vertices.texCoord);

    if (builtMesh.vertices.position) {
        for (int i = 0; i < 4; i++) {
            TextureVertex& expectedVertex = expectedMesh.mappedVertices[i];
            TextureVertex& builtVertex = builtMesh.mappedVertices[i];
            EXPECT_EQ(expectedVertex.u, builtVertex.u);
            EXPECT_EQ(expectedVertex.v, builtVertex.v);
            EXPECT_EQ(expectedVertex.x, builtVertex.x);
            EXPECT_EQ(expectedVertex.y, builtVertex.y);
        }
    }
}

static void expectTransformEq(Glop::Transform& expectedTransform, Glop::Transform& builtTransform) {
    EXPECT_EQ(expectedTransform.canvas, builtTransform.canvas);
    EXPECT_EQ(expectedTransform.modelView, builtTransform.modelView);
    EXPECT_EQ(expectedTransform.transformFlags, expectedTransform.transformFlags);
}

static void expectGlopEq(Glop& expectedGlop, Glop& builtGlop) {
    expectBlendEq(expectedGlop.blend, builtGlop.blend);
    expectFillEq(expectedGlop.fill, builtGlop.fill);
    expectMeshEq(expectedGlop.mesh, builtGlop.mesh);
    expectTransformEq(expectedGlop.transform, builtGlop.transform);
}

static std::unique_ptr<Glop> blackUnitQuadGlop(RenderState& renderState) {
    std::unique_ptr<Glop> glop(new Glop());
    glop->blend = {GL_ZERO, GL_ZERO};
    glop->mesh.elementCount = 4;
    glop->mesh.primitiveMode = GL_TRIANGLE_STRIP;
    glop->mesh.indices.indices = nullptr;
    glop->mesh.indices.bufferObject = GL_ZERO;
    glop->mesh.vertices = {renderState.meshState().getUnitQuadVBO(),
                           VertexAttribFlags::None,
                           nullptr,
                           nullptr,
                           nullptr,
                           kTextureVertexStride};
    glop->transform.modelView.loadIdentity();
    glop->fill.colorEnabled = true;
    glop->fill.color.set(Color::Black);
    glop->fill.skiaShaderData.skiaShaderType = kNone_SkiaShaderType;
    glop->fill.filterMode = ProgramDescription::ColorFilterMode::None;
    glop->fill.texture = {nullptr, GL_INVALID_ENUM, GL_INVALID_ENUM, nullptr};
    return glop;
}

RENDERTHREAD_OPENGL_PIPELINE_TEST(GlopBuilder, rectSnapTest) {
    RenderState& renderState = renderThread.renderState();
    Caches& caches = Caches::getInstance();
    SkPaint paint;
    Rect dest(1, 1, 100, 100);
    Matrix4 simpleTranslate;
    simpleTranslate.loadTranslate(0.7, 0.7, 0);
    Glop glop;
    GlopBuilder(renderState, caches, &glop)
            .setRoundRectClipState(nullptr)
            .setMeshUnitQuad()
            .setFillPaint(paint, 1.0f)
            .setTransform(simpleTranslate, TransformFlags::None)
            .setModelViewMapUnitToRectSnap(dest)
            .build();

    std::unique_ptr<Glop> goldenGlop(blackUnitQuadGlop(renderState));
    // Rect(1,1,100,100) is the set destination,
    // so unit quad should be translated by (1,1) and scaled by (99, 99)
    // Tricky part: because translate (0.7, 0.7) and snapping were set in glopBuilder,
    // unit quad also should be translate by additional (0.3, 0.3) to snap to exact pixels.
    goldenGlop->transform.modelView.loadTranslate(1.3, 1.3, 0);
    goldenGlop->transform.modelView.scale(99, 99, 1);
    goldenGlop->transform.canvas = simpleTranslate;
    goldenGlop->fill.texture.filter = GL_NEAREST;
    expectGlopEq(*goldenGlop, glop);
}

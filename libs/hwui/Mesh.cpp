/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "Mesh.h"

#include <GLES/gl.h>
#include <SkMesh.h>

#include "SafeMath.h"

namespace android {

static size_t min_vcount_for_mode(SkMesh::Mode mode) {
    switch (mode) {
        case SkMesh::Mode::kTriangles:
            return 3;
        case SkMesh::Mode::kTriangleStrip:
            return 3;
    }
    return 1;
}

// Re-implementation of SkMesh::validate to validate user side that their mesh is valid.
std::tuple<bool, SkString> Mesh::validate() {
#define FAIL_MESH_VALIDATE(...) return std::make_tuple(false, SkStringPrintf(__VA_ARGS__))
    if (!mMeshSpec) {
        FAIL_MESH_VALIDATE("MeshSpecification is required.");
    }
    if (mBufferData->vertexData().empty()) {
        FAIL_MESH_VALIDATE("VertexBuffer is required.");
    }

    size_t vertexStride = mMeshSpec->stride();
    size_t vertexCount = mBufferData->vertexCount();
    size_t vertexOffset = mBufferData->vertexOffset();
    SafeMath sm;
    size_t vertexSize = sm.mul(vertexStride, vertexCount);
    if (sm.add(vertexSize, vertexOffset) > mBufferData->vertexData().size()) {
        FAIL_MESH_VALIDATE(
                "The vertex buffer offset and vertex count reads beyond the end of the"
                " vertex buffer.");
    }

    if (vertexOffset % vertexStride != 0) {
        FAIL_MESH_VALIDATE("The vertex offset (%zu) must be a multiple of the vertex stride (%zu).",
                           vertexOffset, vertexStride);
    }

    if (size_t uniformSize = mMeshSpec->uniformSize()) {
        if (!mUniformBuilder.fUniforms || mUniformBuilder.fUniforms->size() < uniformSize) {
            FAIL_MESH_VALIDATE("The uniform data is %zu bytes but must be at least %zu.",
                               mUniformBuilder.fUniforms->size(), uniformSize);
        }
    }

    auto modeToStr = [](SkMesh::Mode m) {
        switch (m) {
            case SkMesh::Mode::kTriangles:
                return "triangles";
            case SkMesh::Mode::kTriangleStrip:
                return "triangle-strip";
        }
        return "unknown";
    };

    size_t indexCount = mBufferData->indexCount();
    size_t indexOffset = mBufferData->indexOffset();
    if (!mBufferData->indexData().empty()) {
        if (indexCount < min_vcount_for_mode(mMode)) {
            FAIL_MESH_VALIDATE("%s mode requires at least %zu indices but index count is %zu.",
                               modeToStr(mMode), min_vcount_for_mode(mMode), indexCount);
        }
        size_t isize = sm.mul(sizeof(uint16_t), indexCount);
        if (sm.add(isize, indexOffset) > mBufferData->indexData().size()) {
            FAIL_MESH_VALIDATE(
                    "The index buffer offset and index count reads beyond the end of the"
                    " index buffer.");
        }
        // If we allow 32 bit indices then this should enforce 4 byte alignment in that case.
        if (!SkIsAlign2(indexOffset)) {
            FAIL_MESH_VALIDATE("The index offset must be a multiple of 2.");
        }
    } else {
        if (vertexCount < min_vcount_for_mode(mMode)) {
            FAIL_MESH_VALIDATE("%s mode requires at least %zu vertices but vertex count is %zu.",
                               modeToStr(mMode), min_vcount_for_mode(mMode), vertexCount);
        }
        LOG_ALWAYS_FATAL_IF(indexCount != 0);
        LOG_ALWAYS_FATAL_IF(indexOffset != 0);
    }

    if (!sm.ok()) {
        FAIL_MESH_VALIDATE("Overflow");
    }
#undef FAIL_MESH_VALIDATE
    return {true, {}};
}

}  // namespace android

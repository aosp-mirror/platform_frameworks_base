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

static size_t min_vcount_for_mode(SkMesh::Mode mode) {
    switch (mode) {
        case SkMesh::Mode::kTriangles:
            return 3;
        case SkMesh::Mode::kTriangleStrip:
            return 3;
    }
}

// Re-implementation of SkMesh::validate to validate user side that their mesh is valid.
std::tuple<bool, SkString> Mesh::validate() {
#define FAIL_MESH_VALIDATE(...) return std::make_tuple(false, SkStringPrintf(__VA_ARGS__))
    if (!mMeshSpec) {
        FAIL_MESH_VALIDATE("MeshSpecification is required.");
    }
    if (mVertexBufferData.empty()) {
        FAIL_MESH_VALIDATE("VertexBuffer is required.");
    }

    auto meshStride = mMeshSpec->stride();
    auto meshMode = SkMesh::Mode(mMode);
    SafeMath sm;
    size_t vsize = sm.mul(meshStride, mVertexCount);
    if (sm.add(vsize, mVertexOffset) > mVertexBufferData.size()) {
        FAIL_MESH_VALIDATE(
                "The vertex buffer offset and vertex count reads beyond the end of the"
                " vertex buffer.");
    }

    if (mVertexOffset % meshStride != 0) {
        FAIL_MESH_VALIDATE("The vertex offset (%zu) must be a multiple of the vertex stride (%zu).",
                           mVertexOffset, meshStride);
    }

    if (size_t uniformSize = mMeshSpec->uniformSize()) {
        if (!mBuilder->fUniforms || mBuilder->fUniforms->size() < uniformSize) {
            FAIL_MESH_VALIDATE("The uniform data is %zu bytes but must be at least %zu.",
                               mBuilder->fUniforms->size(), uniformSize);
        }
    }

    auto modeToStr = [](SkMesh::Mode m) {
        switch (m) {
            case SkMesh::Mode::kTriangles:
                return "triangles";
            case SkMesh::Mode::kTriangleStrip:
                return "triangle-strip";
        }
    };
    if (!mIndexBufferData.empty()) {
        if (mIndexCount < min_vcount_for_mode(meshMode)) {
            FAIL_MESH_VALIDATE("%s mode requires at least %zu indices but index count is %zu.",
                               modeToStr(meshMode), min_vcount_for_mode(meshMode), mIndexCount);
        }
        size_t isize = sm.mul(sizeof(uint16_t), mIndexCount);
        if (sm.add(isize, mIndexOffset) > mIndexBufferData.size()) {
            FAIL_MESH_VALIDATE(
                    "The index buffer offset and index count reads beyond the end of the"
                    " index buffer.");
        }
        // If we allow 32 bit indices then this should enforce 4 byte alignment in that case.
        if (!SkIsAlign2(mIndexOffset)) {
            FAIL_MESH_VALIDATE("The index offset must be a multiple of 2.");
        }
    } else {
        if (mVertexCount < min_vcount_for_mode(meshMode)) {
            FAIL_MESH_VALIDATE("%s mode requires at least %zu vertices but vertex count is %zu.",
                               modeToStr(meshMode), min_vcount_for_mode(meshMode), mVertexCount);
        }
        LOG_ALWAYS_FATAL_IF(mIndexCount != 0);
        LOG_ALWAYS_FATAL_IF(mIndexOffset != 0);
    }

    if (!sm.ok()) {
        FAIL_MESH_VALIDATE("Overflow");
    }
#undef FAIL_MESH_VALIDATE
    return {true, {}};
}

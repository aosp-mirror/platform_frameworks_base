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

#ifndef MESH_H_
#define MESH_H_

#include <SkMesh.h>
#include <include/gpu/ganesh/GrDirectContext.h>
#include <include/gpu/ganesh/SkMeshGanesh.h>
#include <jni.h>
#include <log/log.h>

#include <utility>

namespace android {

class MeshUniformBuilder {
public:
    struct MeshUniform {
        template <typename T>
        std::enable_if_t<std::is_trivially_copyable<T>::value, MeshUniform> operator=(
                const T& val) {
            if (!fVar) {
                LOG_FATAL("Assigning to missing variable");
            } else if (sizeof(val) != fVar->sizeInBytes()) {
                LOG_FATAL("Incorrect value size");
            } else {
                void* dst = reinterpret_cast<void*>(
                        reinterpret_cast<uint8_t*>(fOwner->writableUniformData()) + fVar->offset);
                memcpy(dst, &val, sizeof(val));
            }
        }

        MeshUniform& operator=(const SkMatrix& val) {
            if (!fVar) {
                LOG_FATAL("Assigning to missing variable");
            } else if (fVar->sizeInBytes() != 9 * sizeof(float)) {
                LOG_FATAL("Incorrect value size");
            } else {
                float* data = reinterpret_cast<float*>(
                        reinterpret_cast<uint8_t*>(fOwner->writableUniformData()) + fVar->offset);
                data[0] = val.get(0);
                data[1] = val.get(3);
                data[2] = val.get(6);
                data[3] = val.get(1);
                data[4] = val.get(4);
                data[5] = val.get(7);
                data[6] = val.get(2);
                data[7] = val.get(5);
                data[8] = val.get(8);
            }
            return *this;
        }

        template <typename T>
        bool set(const T val[], const int count) {
            static_assert(std::is_trivially_copyable<T>::value, "Value must be trivial copyable");
            if (!fVar) {
                LOG_FATAL("Assigning to missing variable");
                return false;
            } else if (sizeof(T) * count != fVar->sizeInBytes()) {
                LOG_FATAL("Incorrect value size");
                return false;
            } else {
                void* dst = reinterpret_cast<void*>(
                        reinterpret_cast<uint8_t*>(fOwner->writableUniformData()) + fVar->offset);
                memcpy(dst, val, sizeof(T) * count);
            }
            return true;
        }

        MeshUniformBuilder* fOwner;
        const SkRuntimeEffect::Uniform* fVar;
    };
    MeshUniform uniform(std::string_view name) { return {this, fMeshSpec->findUniform(name)}; }

    explicit MeshUniformBuilder(sk_sp<SkMeshSpecification> meshSpec) {
        fMeshSpec = sk_sp(meshSpec);
        fUniforms = (SkData::MakeZeroInitialized(meshSpec->uniformSize()));
    }

    sk_sp<SkData> fUniforms;

private:
    void* writableUniformData() {
        if (!fUniforms->unique()) {
            fUniforms = SkData::MakeWithCopy(fUniforms->data(), fUniforms->size());
        }
        return fUniforms->writable_data();
    }

    sk_sp<SkMeshSpecification> fMeshSpec;
};

// Storage for CPU and GPU copies of the vertex and index data of a mesh.
class MeshBufferData {
public:
    MeshBufferData(std::vector<uint8_t> vertexData, int32_t vertexCount, int32_t vertexOffset,
                   std::vector<uint8_t> indexData, int32_t indexCount, int32_t indexOffset)
            : mVertexCount(vertexCount)
            , mVertexOffset(vertexOffset)
            , mIndexCount(indexCount)
            , mIndexOffset(indexOffset)
            , mVertexData(std::move(vertexData))
            , mIndexData(std::move(indexData)) {}

    void updateBuffers(GrDirectContext* context) const {
        GrDirectContext::DirectContextID currentId = context == nullptr
                                                             ? GrDirectContext::DirectContextID()
                                                             : context->directContextID();
        if (currentId == mSkiaBuffers.fGenerationId && mSkiaBuffers.fVertexBuffer != nullptr) {
            // Nothing to update since the Android API does not support partial updates yet.
            return;
        }

        mSkiaBuffers.fVertexBuffer =
#ifdef __ANDROID__
                SkMeshes::MakeVertexBuffer(context, mVertexData.data(), mVertexData.size());
#else
                SkMeshes::MakeVertexBuffer(mVertexData.data(), mVertexData.size());
#endif
        if (mIndexCount != 0) {
            mSkiaBuffers.fIndexBuffer =
#ifdef __ANDROID__
                    SkMeshes::MakeIndexBuffer(context, mIndexData.data(), mIndexData.size());
#else
                    SkMeshes::MakeIndexBuffer(mIndexData.data(), mIndexData.size());
#endif
        }
        mSkiaBuffers.fGenerationId = currentId;
    }

    SkMesh::VertexBuffer* vertexBuffer() const { return mSkiaBuffers.fVertexBuffer.get(); }

    sk_sp<SkMesh::VertexBuffer> refVertexBuffer() const { return mSkiaBuffers.fVertexBuffer; }
    int32_t vertexCount() const { return mVertexCount; }
    int32_t vertexOffset() const { return mVertexOffset; }

    sk_sp<SkMesh::IndexBuffer> refIndexBuffer() const { return mSkiaBuffers.fIndexBuffer; }
    int32_t indexCount() const { return mIndexCount; }
    int32_t indexOffset() const { return mIndexOffset; }

    const std::vector<uint8_t>& vertexData() const { return mVertexData; }
    const std::vector<uint8_t>& indexData() const { return mIndexData; }

private:
    struct CachedSkiaBuffers {
        sk_sp<SkMesh::VertexBuffer> fVertexBuffer;
        sk_sp<SkMesh::IndexBuffer> fIndexBuffer;
        GrDirectContext::DirectContextID fGenerationId = GrDirectContext::DirectContextID();
    };

    mutable CachedSkiaBuffers mSkiaBuffers;
    int32_t mVertexCount = 0;
    int32_t mVertexOffset = 0;
    int32_t mIndexCount = 0;
    int32_t mIndexOffset = 0;
    std::vector<uint8_t> mVertexData;
    std::vector<uint8_t> mIndexData;
};

class Mesh {
public:
    // A snapshot of the mesh for use by the render thread.
    //
    // After a snapshot is taken, future uniform changes to the original Mesh will not modify the
    // uniforms returned by makeSkMesh.
    class Snapshot {
    public:
        Snapshot() = delete;
        Snapshot(const Snapshot&) = default;
        Snapshot(Snapshot&&) = default;
        Snapshot& operator=(const Snapshot&) = default;
        Snapshot& operator=(Snapshot&&) = default;
        ~Snapshot() = default;

        const SkMesh& getSkMesh() const {
            SkMesh::VertexBuffer* vertexBuffer = mBufferData->vertexBuffer();
            LOG_FATAL_IF(vertexBuffer == nullptr,
                         "Attempt to obtain SkMesh when vertexBuffer has not been created, did you "
                         "forget to call MeshBufferData::updateBuffers with a GrDirectContext?");
            if (vertexBuffer != mMesh.vertexBuffer()) mMesh = makeSkMesh();
            return mMesh;
        }

    private:
        friend class Mesh;

        Snapshot(sk_sp<SkMeshSpecification> meshSpec, SkMesh::Mode mode,
                 std::shared_ptr<const MeshBufferData> bufferData, sk_sp<const SkData> uniforms,
                 const SkRect& bounds)
                : mMeshSpec(std::move(meshSpec))
                , mMode(mode)
                , mBufferData(std::move(bufferData))
                , mUniforms(std::move(uniforms))
                , mBounds(bounds) {}

        SkMesh makeSkMesh() const {
            const MeshBufferData& d = *mBufferData;
            if (d.indexCount() != 0) {
                return SkMesh::MakeIndexed(mMeshSpec, mMode, d.refVertexBuffer(), d.vertexCount(),
                                           d.vertexOffset(), d.refIndexBuffer(), d.indexCount(),
                                           d.indexOffset(), mUniforms,
                                           SkSpan<SkRuntimeEffect::ChildPtr>(), mBounds)
                        .mesh;
            }
            return SkMesh::Make(mMeshSpec, mMode, d.refVertexBuffer(), d.vertexCount(),
                                d.vertexOffset(), mUniforms, SkSpan<SkRuntimeEffect::ChildPtr>(),
                                mBounds)
                    .mesh;
        }

        mutable SkMesh mMesh;
        sk_sp<SkMeshSpecification> mMeshSpec;
        SkMesh::Mode mMode;
        std::shared_ptr<const MeshBufferData> mBufferData;
        sk_sp<const SkData> mUniforms;
        SkRect mBounds;
    };

    Mesh(sk_sp<SkMeshSpecification> meshSpec, SkMesh::Mode mode, std::vector<uint8_t> vertexData,
         int32_t vertexCount, int32_t vertexOffset, const SkRect& bounds)
            : Mesh(std::move(meshSpec), mode, std::move(vertexData), vertexCount, vertexOffset,
                   /* indexData = */ {}, /* indexCount = */ 0, /* indexOffset = */ 0, bounds) {}

    Mesh(sk_sp<SkMeshSpecification> meshSpec, SkMesh::Mode mode, std::vector<uint8_t> vertexData,
         int32_t vertexCount, int32_t vertexOffset, std::vector<uint8_t> indexData,
         int32_t indexCount, int32_t indexOffset, const SkRect& bounds)
            : mMeshSpec(std::move(meshSpec))
            , mMode(mode)
            , mBufferData(std::make_shared<MeshBufferData>(std::move(vertexData), vertexCount,
                                                           vertexOffset, std::move(indexData),
                                                           indexCount, indexOffset))
            , mUniformBuilder(mMeshSpec)
            , mBounds(bounds) {}

    Mesh(Mesh&&) = default;

    Mesh& operator=(Mesh&&) = default;

    [[nodiscard]] std::tuple<bool, SkString> validate();

    std::shared_ptr<const MeshBufferData> refBufferData() const { return mBufferData; }

    Snapshot takeSnapshot() const {
        return Snapshot(mMeshSpec, mMode, mBufferData, mUniformBuilder.fUniforms, mBounds);
    }

    MeshUniformBuilder* uniformBuilder() { return &mUniformBuilder; }

private:
    sk_sp<SkMeshSpecification> mMeshSpec;
    SkMesh::Mode mMode;
    std::shared_ptr<MeshBufferData> mBufferData;
    MeshUniformBuilder mUniformBuilder;
    SkRect mBounds;
};

}  // namespace android

#endif  // MESH_H_

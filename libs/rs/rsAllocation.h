/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_STRUCTURED_ALLOCATION_H
#define ANDROID_STRUCTURED_ALLOCATION_H

#include "rsType.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class Program;

class Allocation : public ObjectBase {
    // The graphics equilivent of malloc.  The allocation contains a structure of elements.

public:
    struct Hal {
        void * drv;

        struct State {
            ObjectBaseRef<const Type> type;
            void * mallocPtr;

            uint32_t usageFlags;
            RsAllocationMipmapControl mipmapControl;

            // Cached fields from the Type and Element
            // to prevent pointer chasing in critical loops.
            uint32_t dimensionX;
            uint32_t dimensionY;
            uint32_t dimensionZ;
            uint32_t elementSizeBytes;
            bool hasMipmaps;
            bool hasFaces;
            bool hasReferences;
        };
        State state;
    };
    Hal mHal;

    Allocation(Context *rsc, const Type *, uint32_t usages,
               RsAllocationMipmapControl mc = RS_ALLOCATION_MIPMAP_NONE);

    virtual ~Allocation();
    void updateCache();

    void setCpuWritable(bool);
    void setGpuWritable(bool);
    void setCpuReadable(bool);
    void setGpuReadable(bool);

    bool fixAllocation();

    void * getPtr() const {return mHal.state.mallocPtr;}
    const Type * getType() const {return mHal.state.type.get();}

    void syncAll(Context *rsc, RsAllocationUsageType src);

    void deferredUploadToTexture(const Context *rsc);
    void uploadToTexture(const Context *rsc);
    uint32_t getTextureID() const {return mTextureID;}

    void deferredAllocateRenderTarget(const Context *rsc);
    void allocateRenderTarget(const Context *rsc);
    uint32_t getRenderTargetID() const {return mRenderTargetID;}

    uint32_t getGLTarget() const;

    void deferredUploadToBufferObject(const Context *rsc);
    void uploadToBufferObject(const Context *rsc);
    uint32_t getBufferObjectID() const {return mBufferID;}

    void copyRange1D(Context *rsc, const Allocation *src, int32_t srcOff, int32_t destOff, int32_t len);

    void resize1D(Context *rsc, uint32_t dimX);
    void resize2D(Context *rsc, uint32_t dimX, uint32_t dimY);

    void data(Context *rsc, uint32_t xoff, uint32_t lod, uint32_t count, const void *data, uint32_t sizeBytes);
    void data(Context *rsc, uint32_t xoff, uint32_t yoff, uint32_t lod, RsAllocationCubemapFace face,
                 uint32_t w, uint32_t h, const void *data, uint32_t sizeBytes);
    void data(Context *rsc, uint32_t xoff, uint32_t yoff, uint32_t zoff, uint32_t lod, RsAllocationCubemapFace face,
                 uint32_t w, uint32_t h, uint32_t d, const void *data, uint32_t sizeBytes);

    void elementData(Context *rsc, uint32_t x,
                        const void *data, uint32_t elementOff, uint32_t sizeBytes);
    void elementData(Context *rsc, uint32_t x, uint32_t y,
                        const void *data, uint32_t elementOff, uint32_t sizeBytes);

    void read(void *data);

    void enableGLVertexBuffers() const;
    void setupGLIndexBuffers() const;

    void addProgramToDirty(const Program *);
    void removeProgramToDirty(const Program *);

    virtual void dumpLOGV(const char *prefix) const;
    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_ALLOCATION; }
    static Allocation *createFromStream(Context *rsc, IStream *stream);

    virtual void uploadCheck(Context *rsc);

    bool getIsScript() const {
        return (mHal.state.usageFlags & RS_ALLOCATION_USAGE_SCRIPT) != 0;
    }
    bool getIsTexture() const {
        return (mHal.state.usageFlags & RS_ALLOCATION_USAGE_GRAPHICS_TEXTURE) != 0;
    }
    bool getIsRenderTarget() const {
        return (mHal.state.usageFlags & RS_ALLOCATION_USAGE_GRAPHICS_RENDER_TARGET) != 0;
    }
    bool getIsBufferObject() const {
        return (mHal.state.usageFlags & RS_ALLOCATION_USAGE_GRAPHICS_VERTEX) != 0;
    }

    void incRefs(const void *ptr, size_t ct, size_t startOff = 0) const;
    void decRefs(const void *ptr, size_t ct, size_t startOff = 0) const;

    void sendDirty() const;
    bool getHasGraphicsMipmaps() const {
        return mHal.state.mipmapControl != RS_ALLOCATION_MIPMAP_NONE;
    }


protected:
    Vector<const Program *> mToDirtyList;

    // Is we have a non-null user bitmap callback we do not own the bits and
    // instead call this function to free the memort when its time.
    RsBitmapCallback_t mUserBitmapCallback;
    void *mUserBitmapCallbackData;

    // Usage restrictions
    bool mCpuWrite;
    bool mCpuRead;
    bool mGpuWrite;
    bool mGpuRead;

    // more usage hint data from the application
    // which can be used by a driver to pick the best memory type.
    // Likely ignored for now
    float mReadWriteRatio;
    float mUpdateSize;


    // Is this a legal structure to be used as a texture source.
    // Initially this will require 1D or 2D and color data
    uint32_t mTextureID;

    // Is this a legal structure to be used as a vertex source.
    // Initially this will require 1D and x(yzw).  Additional per element data
    // is allowed.
    uint32_t mBufferID;

    // Is this a legal structure to be used as an FBO render target
    uint32_t mRenderTargetID;

    bool mUploadDeferred;

private:
    void init(Context *rsc, const Type *);
    void upload2DTexture(bool isFirstUpload);
    void update2DTexture(const void *ptr, uint32_t xoff, uint32_t yoff,
                         uint32_t lod, RsAllocationCubemapFace face, uint32_t w, uint32_t h);

    void allocScriptMemory();
    void freeScriptMemory();

};

}
}
#endif


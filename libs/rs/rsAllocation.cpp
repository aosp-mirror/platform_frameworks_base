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

#include "rsContext.h"

using namespace android;
using namespace android::renderscript;

Allocation::Allocation(const Type *type)
{
    mPtr = NULL;

    mCpuWrite = false;
    mCpuRead = false;
    mGpuWrite = false;
    mGpuRead = false;

    mReadWriteRatio = 0;
    mUpdateSize = 0;

    mIsTexture = false;
    mTextureID = 0;

    mIsVertexBuffer = false;
    mBufferID = 0;

    mType.set(type);
    mPtr = malloc(mType->getSizeBytes());
    if (!mPtr) {
        LOGE("Allocation::Allocation, alloc failure");
    }

}

Allocation::~Allocation()
{
}

void Allocation::setCpuWritable(bool)
{
}

void Allocation::setGpuWritable(bool)
{
}

void Allocation::setCpuReadable(bool)
{
}

void Allocation::setGpuReadable(bool)
{
}

bool Allocation::fixAllocation()
{
    return false;
}

void Allocation::uploadToTexture(uint32_t lodOffset)
{
    //rsAssert(!mTextureId);
    rsAssert(lodOffset < mType->getLODCount());

    //LOGE("uploadToTexture  %i,  lod %i", mTextureID, lodOffset);

    if (!mTextureID) {
        glGenTextures(1, &mTextureID);
    }
    glBindTexture(GL_TEXTURE_2D, mTextureID);

    Adapter2D adapt(this);
    for(uint32_t lod = 0; (lod + lodOffset) < mType->getLODCount(); lod++) {
        adapt.setLOD(lod+lodOffset);

        uint16_t * ptr = static_cast<uint16_t *>(adapt.getElement(0,0));
        glTexImage2D(GL_TEXTURE_2D, lod, GL_RGB, 
                     adapt.getDimX(), adapt.getDimY(), 
                     0, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, ptr);
    }
}

void Allocation::uploadToBufferObject()
{
    rsAssert(!mType->getDimY());
    rsAssert(!mType->getDimZ());

    //LOGE("uploadToTexture  %i,  lod %i", mTextureID, lodOffset);

    if (!mBufferID) {
        glGenBuffers(1, &mBufferID);
    }
    glBindBuffer(GL_ARRAY_BUFFER, mBufferID);
    glBufferData(GL_ARRAY_BUFFER, mType->getSizeBytes(), getPtr(), GL_DYNAMIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void Allocation::data(const void *data)
{
    memcpy(mPtr, data, mType->getSizeBytes());
}

void Allocation::subData(uint32_t xoff, uint32_t count, const void *data)
{
    uint32_t eSize = mType->getElementSizeBytes();
    uint8_t * ptr = static_cast<uint8_t *>(mPtr);
    ptr += eSize * xoff;
    memcpy(ptr, data, count * eSize);
}

void Allocation::subData(uint32_t xoff, uint32_t yoff, 
             uint32_t w, uint32_t h, const void *data)
{
    uint32_t eSize = mType->getElementSizeBytes();
    uint32_t lineSize = eSize * w;
    uint32_t destW = mType->getDimX();

    const uint8_t *src = static_cast<const uint8_t *>(data);
    uint8_t *dst = static_cast<uint8_t *>(mPtr);
    dst += eSize * (xoff + yoff * destW);
    for (uint32_t line=yoff; line < (yoff+h); line++) {
        uint8_t * ptr = static_cast<uint8_t *>(mPtr);
        memcpy(dst, src, lineSize);
        src += lineSize;
        dst += destW * eSize;
    }
}

void Allocation::subData(uint32_t xoff, uint32_t yoff, uint32_t zoff,
             uint32_t w, uint32_t h, uint32_t d, const void *data)
{
}



/////////////////
// 


namespace android {
namespace renderscript {

RsAllocation rsi_AllocationCreateTyped(Context *rsc, RsType vtype)
{
    const Type * type = static_cast<const Type *>(vtype);

    Allocation * alloc = new Allocation(type);
    return alloc;
}

RsAllocation rsi_AllocationCreatePredefSized(Context *rsc, RsElementPredefined t, size_t count)
{
    RsElement e = rsi_ElementGetPredefined(rsc, t);
    return rsi_AllocationCreateSized(rsc, e, count);
}

RsAllocation rsi_AllocationCreateSized(Context *rsc, RsElement e, size_t count)
{
    Type * type = new Type();
    type->setDimX(count);
    type->setElement(static_cast<Element *>(e));
    type->compute();
    return rsi_AllocationCreateTyped(rsc, type);
}

void rsi_AllocationUploadToTexture(Context *rsc, RsAllocation va, uint32_t baseMipLevel)
{
    Allocation *alloc = static_cast<Allocation *>(va);
    alloc->uploadToTexture(baseMipLevel);
}

void rsi_AllocationUploadToBufferObject(Context *rsc, RsAllocation va)
{
    Allocation *alloc = static_cast<Allocation *>(va);
    alloc->uploadToBufferObject();
}

void rsi_AllocationDestroy(Context *rsc, RsAllocation)
{
}

static void mip(const Adapter2D &out, const Adapter2D &in)
{
    uint32_t w = out.getDimX();
    uint32_t h = out.getDimY();

    for (uint32_t y=0; y < w; y++) {
        uint16_t *oPtr = static_cast<uint16_t *>(out.getElement(0, y));
        const uint16_t *i1 = static_cast<uint16_t *>(in.getElement(0, y*2));
        const uint16_t *i2 = static_cast<uint16_t *>(in.getElement(0, y*2+1));

        for (uint32_t x=0; x < h; x++) {
            *oPtr = rsBoxFilter565(i1[0], i1[2], i2[0], i2[1]);
            oPtr ++;
            i1 += 2;
            i2 += 2;
        }
    }

}


RsAllocation rsi_AllocationCreateFromBitmap(Context *rsc, const char *file, bool genMips)
{
    typedef struct _Win3xBitmapHeader
    {
       uint16_t type;
       uint32_t totalSize;
       uint32_t reserved;
       uint32_t offset;
       int32_t hdrSize;            /* Size of this header in bytes */
       int32_t width;           /* Image width in pixels */
       int32_t height;          /* Image height in pixels */
       int16_t planes;          /* Number of color planes */
       int16_t bpp;             /* Number of bits per pixel */
       /* Fields added for Windows 3.x follow this line */
       int32_t compression;     /* Compression methods used */
       int32_t sizeOfBitmap;    /* Size of bitmap in bytes */
       int32_t horzResolution;  /* Horizontal resolution in pixels per meter */
       int32_t vertResolution;  /* Vertical resolution in pixels per meter */
       int32_t colorsUsed;      /* Number of colors in the image */
       int32_t colorsImportant; /* Minimum number of important colors */
    } __attribute__((__packed__)) WIN3XBITMAPHEADER;

    _Win3xBitmapHeader hdr;

    FILE *f = fopen(file, "rb");
    if (f == NULL) {
        LOGE("rsAllocationCreateFromBitmap failed to open file %s", file);
        return NULL;
    }
    memset(&hdr, 0, sizeof(hdr));
    fread(&hdr, sizeof(hdr), 1, f);

    if (hdr.bpp != 24) {
        LOGE("Unsuported BMP type");
        fclose(f);
        return NULL;
    }

    int32_t texWidth = rsHigherPow2(hdr.width);
    int32_t texHeight = rsHigherPow2(hdr.height);

    rsi_TypeBegin(rsc, rsi_ElementGetPredefined(rsc, RS_ELEMENT_RGB_565));
    rsi_TypeAdd(rsc, RS_DIMENSION_X, texWidth);
    rsi_TypeAdd(rsc, RS_DIMENSION_Y, texHeight);
    if (genMips) {
        rsi_TypeAdd(rsc, RS_DIMENSION_LOD, 1);
    }
    RsType type = rsi_TypeCreate(rsc);

    RsAllocation vTexAlloc = rsi_AllocationCreateTyped(rsc, type);
    Allocation *texAlloc = static_cast<Allocation *>(vTexAlloc);
    texAlloc->incRef();
    if (texAlloc == NULL) {
        LOGE("Memory allocation failure");
        fclose(f);
        return NULL;
    }

    // offset to letterbox if height is not pow2
    Adapter2D adapt(texAlloc);
    uint8_t * fileInBuf = new uint8_t[texWidth * 3];
    uint32_t yOffset = (hdr.width - hdr.height) / 2;
    uint16_t *tmp = static_cast<uint16_t *>(adapt.getElement(0, yOffset));

    for (int y=0; y < hdr.height; y++) {
        fseek(f, hdr.offset + (y*hdr.width*3), SEEK_SET);
        fread(fileInBuf, 1, hdr.width * 3, f);
        for(int x=0; x < hdr.width; x++) {
            *tmp = rs888to565(fileInBuf[x*3], fileInBuf[x*3 + 1], fileInBuf[x*3 + 2]);
            tmp++;
        }
    }

    fclose(f);
    delete [] fileInBuf;

    if (genMips) {
        Adapter2D adapt2(texAlloc);
        for(uint32_t lod=0; lod < (texAlloc->getType()->getLODCount() -1); lod++) {
            adapt.setLOD(lod);
            adapt2.setLOD(lod + 1);
            mip(adapt2, adapt);
        }
    }

    return texAlloc;
}


void rsi_AllocationData(Context *rsc, RsAllocation va, const void *data)
{
    Allocation *a = static_cast<Allocation *>(va);
    a->data(data);
}

void rsi_Allocation1DSubData(Context *rsc, RsAllocation va, uint32_t xoff, uint32_t count, const void *data)
{
    Allocation *a = static_cast<Allocation *>(va);
    a->subData(xoff, count, data);
}

void rsi_Allocation2DSubData(Context *rsc, RsAllocation va, uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h, const void *data)
{
    Allocation *a = static_cast<Allocation *>(va);
    a->subData(xoff, yoff, w, h, data);
}


}
}

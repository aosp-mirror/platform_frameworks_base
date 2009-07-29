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

#include <GLES/gl.h>
#include <GLES/glext.h>

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

    GLenum type = mType->getElement()->getGLType();
    GLenum format = mType->getElement()->getGLFormat();

    if (!type || !format) {
        return;
    }

    if (!mTextureID) {
        glGenTextures(1, &mTextureID);
    }
    glBindTexture(GL_TEXTURE_2D, mTextureID);

    Adapter2D adapt(this);
    for(uint32_t lod = 0; (lod + lodOffset) < mType->getLODCount(); lod++) {
        adapt.setLOD(lod+lodOffset);

        uint16_t * ptr = static_cast<uint16_t *>(adapt.getElement(0,0));
        glTexImage2D(GL_TEXTURE_2D, lod, format,
                     adapt.getDimX(), adapt.getDimY(),
                     0, format, type, ptr);
    }
}

void Allocation::uploadToBufferObject()
{
    rsAssert(!mType->getDimY());
    rsAssert(!mType->getDimZ());

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
    alloc->incRef();
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

static void mip565(const Adapter2D &out, const Adapter2D &in)
{
    uint32_t w = out.getDimX();
    uint32_t h = out.getDimY();

    for (uint32_t y=0; y < h; y++) {
        uint16_t *oPtr = static_cast<uint16_t *>(out.getElement(0, y));
        const uint16_t *i1 = static_cast<uint16_t *>(in.getElement(0, y*2));
        const uint16_t *i2 = static_cast<uint16_t *>(in.getElement(0, y*2+1));

        for (uint32_t x=0; x < w; x++) {
            *oPtr = rsBoxFilter565(i1[0], i1[1], i2[0], i2[1]);
            oPtr ++;
            i1 += 2;
            i2 += 2;
        }
    }
}

static void mip8888(const Adapter2D &out, const Adapter2D &in)
{
    uint32_t w = out.getDimX();
    uint32_t h = out.getDimY();

    for (uint32_t y=0; y < h; y++) {
        uint32_t *oPtr = static_cast<uint32_t *>(out.getElement(0, y));
        const uint32_t *i1 = static_cast<uint32_t *>(in.getElement(0, y*2));
        const uint32_t *i2 = static_cast<uint32_t *>(in.getElement(0, y*2+1));

        for (uint32_t x=0; x < w; x++) {
            *oPtr = rsBoxFilter8888(i1[0], i1[1], i2[0], i2[1]);
            oPtr ++;
            i1 += 2;
            i2 += 2;
        }
    }
}

static void mip(const Adapter2D &out, const Adapter2D &in)
{
    switch(out.getBaseType()->getElement()->getSizeBits()) {
    case 32:
        mip8888(out, in);
        break;
    case 16:
        mip565(out, in);
        break;

    }

}

typedef void (*ElementConverter_t)(void *dst, const void *src, uint32_t count);

static void elementConverter_cpy_16(void *dst, const void *src, uint32_t count)
{
    memcpy(dst, src, count * 2);
}
static void elementConverter_cpy_8(void *dst, const void *src, uint32_t count)
{
    memcpy(dst, src, count);
}
static void elementConverter_cpy_32(void *dst, const void *src, uint32_t count)
{
    memcpy(dst, src, count * 4);
}


static void elementConverter_888_to_565(void *dst, const void *src, uint32_t count)
{
    uint16_t *d = static_cast<uint16_t *>(dst);
    const uint8_t *s = static_cast<const uint8_t *>(src);

    while(count--) {
        *d = rs888to565(s[0], s[1], s[2]);
        d++;
        s+= 3;
    }
}

static void elementConverter_8888_to_565(void *dst, const void *src, uint32_t count)
{
    uint16_t *d = static_cast<uint16_t *>(dst);
    const uint8_t *s = static_cast<const uint8_t *>(src);

    while(count--) {
        *d = rs888to565(s[0], s[1], s[2]);
        d++;
        s+= 4;
    }
}

static ElementConverter_t pickConverter(RsElementPredefined dstFmt, RsElementPredefined srcFmt)
{
    if ((dstFmt == RS_ELEMENT_RGB_565) &&
        (srcFmt == RS_ELEMENT_RGB_565)) {
        return elementConverter_cpy_16;
    }

    if ((dstFmt == RS_ELEMENT_RGB_565) &&
        (srcFmt == RS_ELEMENT_RGB_888)) {
        return elementConverter_888_to_565;
    }

    if ((dstFmt == RS_ELEMENT_RGB_565) &&
        (srcFmt == RS_ELEMENT_RGBA_8888)) {
        return elementConverter_8888_to_565;
    }

    if ((dstFmt == RS_ELEMENT_RGBA_8888) &&
        (srcFmt == RS_ELEMENT_RGBA_8888)) {
        return elementConverter_cpy_32;
    }

    LOGE("pickConverter, unsuported combo, src %i,  dst %i", srcFmt, dstFmt);
    return 0;
}


RsAllocation rsi_AllocationCreateFromBitmap(Context *rsc, uint32_t w, uint32_t h, RsElementPredefined dstFmt, RsElementPredefined srcFmt,  bool genMips, const void *data)
{
    rsAssert(!(w & (w-1)));
    rsAssert(!(h & (h-1)));

    //LOGE("rsi_AllocationCreateFromBitmap %i %i %i %i %i", w, h, dstFmt, srcFmt, genMips);
    rsi_TypeBegin(rsc, rsi_ElementGetPredefined(rsc, dstFmt));
    rsi_TypeAdd(rsc, RS_DIMENSION_X, w);
    rsi_TypeAdd(rsc, RS_DIMENSION_Y, h);
    if (genMips) {
        rsi_TypeAdd(rsc, RS_DIMENSION_LOD, 1);
    }
    RsType type = rsi_TypeCreate(rsc);

    RsAllocation vTexAlloc = rsi_AllocationCreateTyped(rsc, type);
    Allocation *texAlloc = static_cast<Allocation *>(vTexAlloc);
    if (texAlloc == NULL) {
        LOGE("Memory allocation failure");
        return NULL;
    }
    texAlloc->incRef();

    ElementConverter_t cvt = pickConverter(dstFmt, srcFmt);
    cvt(texAlloc->getPtr(), data, w * h);

    if (genMips) {
        Adapter2D adapt(texAlloc);
        Adapter2D adapt2(texAlloc);
        for(uint32_t lod=0; lod < (texAlloc->getType()->getLODCount() -1); lod++) {
            adapt.setLOD(lod);
            adapt2.setLOD(lod + 1);
            mip(adapt2, adapt);
        }
    }

    return texAlloc;
}

static uint32_t fmtToBits(RsElementPredefined fmt)
{
    return 16;
}

RsAllocation rsi_AllocationCreateFromBitmapBoxed(Context *rsc, uint32_t w, uint32_t h, RsElementPredefined dstFmt, RsElementPredefined srcFmt, bool genMips, const void *data)
{
    uint32_t w2 = rsHigherPow2(w);
    uint32_t h2 = rsHigherPow2(h);

    if ((w2 == w) && (h2 == h)) {
        return rsi_AllocationCreateFromBitmap(rsc, w, h, dstFmt, srcFmt, genMips, data);
    }

    uint32_t bpp = fmtToBits(srcFmt) >> 3;
    size_t size = w2 * h2 * bpp;
    uint8_t *tmp = static_cast<uint8_t *>(malloc(size));
    memset(tmp, 0, size);

    const uint8_t * src = static_cast<const uint8_t *>(data);
    for (uint32_t y = 0; y < h; y++) {
        uint8_t * ydst = &tmp[y + ((h2 - h) >> 1)];
        memcpy(&ydst[(w2 - w) >> 1], src, w * bpp);
        src += h * bpp;
    }

    RsAllocation ret = rsi_AllocationCreateFromBitmap(rsc, w2, h2, dstFmt, srcFmt, genMips, tmp);
    free(tmp);
    return ret;




}


RsAllocation rsi_AllocationCreateFromFile(Context *rsc, const char *file, bool genMips)
{
    bool use32bpp = false;

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

    if (use32bpp) {
        rsi_TypeBegin(rsc, rsi_ElementGetPredefined(rsc, RS_ELEMENT_RGBA_8888));
    } else {
        rsi_TypeBegin(rsc, rsi_ElementGetPredefined(rsc, RS_ELEMENT_RGB_565));
    }
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

    if (use32bpp) {
        uint8_t *tmp = static_cast<uint8_t *>(adapt.getElement(0, yOffset));
        for (int y=0; y < hdr.height; y++) {
            fseek(f, hdr.offset + (y*hdr.width*3), SEEK_SET);
            fread(fileInBuf, 1, hdr.width * 3, f);
            for(int x=0; x < hdr.width; x++) {
                tmp[0] = fileInBuf[x*3 + 2];
                tmp[1] = fileInBuf[x*3 + 1];
                tmp[2] = fileInBuf[x*3];
                tmp[3] = 0xff;
                tmp += 4;
            }
        }
    } else {
        uint16_t *tmp = static_cast<uint16_t *>(adapt.getElement(0, yOffset));
        for (int y=0; y < hdr.height; y++) {
            fseek(f, hdr.offset + (y*hdr.width*3), SEEK_SET);
            fread(fileInBuf, 1, hdr.width * 3, f);
            for(int x=0; x < hdr.width; x++) {
                *tmp = rs888to565(fileInBuf[x*3 + 2], fileInBuf[x*3 + 1], fileInBuf[x*3]);
                tmp++;
            }
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

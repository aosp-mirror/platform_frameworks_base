/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_NDEBUG 0
#define LOG_TAG "YUVImage"

#include <media/stagefright/YUVImage.h>
#include <ui/Rect.h>
#include <media/stagefright/MediaDebug.h>

namespace android {

YUVImage::YUVImage(YUVFormat yuvFormat, int32_t width, int32_t height) {
    mYUVFormat = yuvFormat;
    mWidth = width;
    mHeight = height;

    size_t numberOfBytes = bufferSize(yuvFormat, width, height);
    uint8_t *buffer = new uint8_t[numberOfBytes];
    mBuffer = buffer;
    mOwnBuffer = true;

    initializeYUVPointers();
}

YUVImage::YUVImage(YUVFormat yuvFormat, int32_t width, int32_t height, uint8_t *buffer) {
    mYUVFormat = yuvFormat;
    mWidth = width;
    mHeight = height;
    mBuffer = buffer;
    mOwnBuffer = false;

    initializeYUVPointers();
}

//static
size_t YUVImage::bufferSize(YUVFormat yuvFormat, int32_t width, int32_t height) {
    int32_t numberOfPixels = width*height;
    size_t numberOfBytes = 0;
    if (yuvFormat == YUV420Planar || yuvFormat == YUV420SemiPlanar) {
        // Y takes numberOfPixels bytes and U/V take numberOfPixels/4 bytes each.
        numberOfBytes = (size_t)(numberOfPixels + (numberOfPixels >> 1));
    } else {
        ALOGE("Format not supported");
    }
    return numberOfBytes;
}

bool YUVImage::initializeYUVPointers() {
    int32_t numberOfPixels = mWidth * mHeight;

    if (mYUVFormat == YUV420Planar) {
        mYdata = (uint8_t *)mBuffer;
        mUdata = mYdata + numberOfPixels;
        mVdata = mUdata + (numberOfPixels >> 2);
    } else if (mYUVFormat == YUV420SemiPlanar) {
        // U and V channels are interleaved as VUVUVU.
        // So V data starts at the end of Y channel and
        // U data starts right after V's start.
        mYdata = (uint8_t *)mBuffer;
        mVdata = mYdata + numberOfPixels;
        mUdata = mVdata + 1;
    } else {
        ALOGE("Format not supported");
        return false;
    }
    return true;
}

YUVImage::~YUVImage() {
    if (mOwnBuffer) delete[] mBuffer;
}

bool YUVImage::getOffsets(int32_t x, int32_t y,
        int32_t *yOffset, int32_t *uOffset, int32_t *vOffset) const {
    *yOffset = y*mWidth + x;

    int32_t uvOffset = (y >> 1) * (mWidth >> 1) + (x >> 1);
    if (mYUVFormat == YUV420Planar) {
        *uOffset = uvOffset;
        *vOffset = uvOffset;
    } else if (mYUVFormat == YUV420SemiPlanar) {
        // Since U and V channels are interleaved, offsets need
        // to be doubled.
        *uOffset = 2*uvOffset;
        *vOffset = 2*uvOffset;
    } else {
        ALOGE("Format not supported");
        return false;
    }

    return true;
}

bool YUVImage::getOffsetIncrementsPerDataRow(
        int32_t *yDataOffsetIncrement,
        int32_t *uDataOffsetIncrement,
        int32_t *vDataOffsetIncrement) const {
    *yDataOffsetIncrement = mWidth;

    int32_t uvDataOffsetIncrement = mWidth >> 1;

    if (mYUVFormat == YUV420Planar) {
        *uDataOffsetIncrement = uvDataOffsetIncrement;
        *vDataOffsetIncrement = uvDataOffsetIncrement;
    } else if (mYUVFormat == YUV420SemiPlanar) {
        // Since U and V channels are interleaved, offsets need
        // to be doubled.
        *uDataOffsetIncrement = 2*uvDataOffsetIncrement;
        *vDataOffsetIncrement = 2*uvDataOffsetIncrement;
    } else {
        ALOGE("Format not supported");
        return false;
    }

    return true;
}

uint8_t* YUVImage::getYAddress(int32_t offset) const {
    return mYdata + offset;
}

uint8_t* YUVImage::getUAddress(int32_t offset) const {
    return mUdata + offset;
}

uint8_t* YUVImage::getVAddress(int32_t offset) const {
    return mVdata + offset;
}

bool YUVImage::getYUVAddresses(int32_t x, int32_t y,
        uint8_t **yAddr, uint8_t **uAddr, uint8_t **vAddr) const {
    int32_t yOffset;
    int32_t uOffset;
    int32_t vOffset;
    if (!getOffsets(x, y, &yOffset, &uOffset, &vOffset)) return false;

    *yAddr = getYAddress(yOffset);
    *uAddr = getUAddress(uOffset);
    *vAddr = getVAddress(vOffset);

    return true;
}

bool YUVImage::validPixel(int32_t x, int32_t y) const {
    return (x >= 0 && x < mWidth &&
            y >= 0 && y < mHeight);
}

bool YUVImage::getPixelValue(int32_t x, int32_t y,
        uint8_t *yPtr, uint8_t *uPtr, uint8_t *vPtr) const {
    CHECK(validPixel(x, y));

    uint8_t *yAddr;
    uint8_t *uAddr;
    uint8_t *vAddr;
    if (!getYUVAddresses(x, y, &yAddr, &uAddr, &vAddr)) return false;

    *yPtr = *yAddr;
    *uPtr = *uAddr;
    *vPtr = *vAddr;

    return true;
}

bool YUVImage::setPixelValue(int32_t x, int32_t y,
        uint8_t yValue, uint8_t uValue, uint8_t vValue) {
    CHECK(validPixel(x, y));

    uint8_t *yAddr;
    uint8_t *uAddr;
    uint8_t *vAddr;
    if (!getYUVAddresses(x, y, &yAddr, &uAddr, &vAddr)) return false;

    *yAddr = yValue;
    *uAddr = uValue;
    *vAddr = vValue;

    return true;
}

void YUVImage::fastCopyRectangle420Planar(
        const Rect& srcRect,
        int32_t destStartX, int32_t destStartY,
        const YUVImage &srcImage, YUVImage &destImage) {
    CHECK(srcImage.mYUVFormat == YUV420Planar);
    CHECK(destImage.mYUVFormat == YUV420Planar);

    int32_t srcStartX = srcRect.left;
    int32_t srcStartY = srcRect.top;
    int32_t width = srcRect.width();
    int32_t height = srcRect.height();

    // Get source and destination start addresses
    uint8_t *ySrcAddrBase;
    uint8_t *uSrcAddrBase;
    uint8_t *vSrcAddrBase;
    srcImage.getYUVAddresses(srcStartX, srcStartY,
            &ySrcAddrBase, &uSrcAddrBase, &vSrcAddrBase);

    uint8_t *yDestAddrBase;
    uint8_t *uDestAddrBase;
    uint8_t *vDestAddrBase;
    destImage.getYUVAddresses(destStartX, destStartY,
            &yDestAddrBase, &uDestAddrBase, &vDestAddrBase);

    // Get source and destination offset increments incurred in going
    // from one data row to next.
    int32_t ySrcOffsetIncrement;
    int32_t uSrcOffsetIncrement;
    int32_t vSrcOffsetIncrement;
    srcImage.getOffsetIncrementsPerDataRow(
            &ySrcOffsetIncrement, &uSrcOffsetIncrement, &vSrcOffsetIncrement);

    int32_t yDestOffsetIncrement;
    int32_t uDestOffsetIncrement;
    int32_t vDestOffsetIncrement;
    destImage.getOffsetIncrementsPerDataRow(
            &yDestOffsetIncrement, &uDestOffsetIncrement, &vDestOffsetIncrement);

    // Copy Y
    {
        size_t numberOfYBytesPerRow = (size_t) width;
        uint8_t *ySrcAddr = ySrcAddrBase;
        uint8_t *yDestAddr = yDestAddrBase;
        for (int32_t offsetY = 0; offsetY < height; ++offsetY) {
            memcpy(yDestAddr, ySrcAddr, numberOfYBytesPerRow);

            ySrcAddr += ySrcOffsetIncrement;
            yDestAddr += yDestOffsetIncrement;
        }
    }

    // Copy U
    {
        size_t numberOfUBytesPerRow = (size_t) (width >> 1);
        uint8_t *uSrcAddr = uSrcAddrBase;
        uint8_t *uDestAddr = uDestAddrBase;
        // Every other row has an entry for U/V channel values. Hence only
        // go half the height.
        for (int32_t offsetY = 0; offsetY < (height >> 1); ++offsetY) {
            memcpy(uDestAddr, uSrcAddr, numberOfUBytesPerRow);

            uSrcAddr += uSrcOffsetIncrement;
            uDestAddr += uDestOffsetIncrement;
        }
    }

    // Copy V
    {
        size_t numberOfVBytesPerRow = (size_t) (width >> 1);
        uint8_t *vSrcAddr = vSrcAddrBase;
        uint8_t *vDestAddr = vDestAddrBase;
        // Every other pixel row has a U/V data row. Hence only go half the height.
        for (int32_t offsetY = 0; offsetY < (height >> 1); ++offsetY) {
            memcpy(vDestAddr, vSrcAddr, numberOfVBytesPerRow);

            vSrcAddr += vSrcOffsetIncrement;
            vDestAddr += vDestOffsetIncrement;
        }
    }
}

void YUVImage::fastCopyRectangle420SemiPlanar(
        const Rect& srcRect,
        int32_t destStartX, int32_t destStartY,
        const YUVImage &srcImage, YUVImage &destImage) {
    CHECK(srcImage.mYUVFormat == YUV420SemiPlanar);
    CHECK(destImage.mYUVFormat == YUV420SemiPlanar);

    int32_t srcStartX = srcRect.left;
    int32_t srcStartY = srcRect.top;
    int32_t width = srcRect.width();
    int32_t height = srcRect.height();

    // Get source and destination start addresses
    uint8_t *ySrcAddrBase;
    uint8_t *uSrcAddrBase;
    uint8_t *vSrcAddrBase;
    srcImage.getYUVAddresses(srcStartX, srcStartY,
            &ySrcAddrBase, &uSrcAddrBase, &vSrcAddrBase);

    uint8_t *yDestAddrBase;
    uint8_t *uDestAddrBase;
    uint8_t *vDestAddrBase;
    destImage.getYUVAddresses(destStartX, destStartY,
            &yDestAddrBase, &uDestAddrBase, &vDestAddrBase);

    // Get source and destination offset increments incurred in going
    // from one data row to next.
    int32_t ySrcOffsetIncrement;
    int32_t uSrcOffsetIncrement;
    int32_t vSrcOffsetIncrement;
    srcImage.getOffsetIncrementsPerDataRow(
            &ySrcOffsetIncrement, &uSrcOffsetIncrement, &vSrcOffsetIncrement);

    int32_t yDestOffsetIncrement;
    int32_t uDestOffsetIncrement;
    int32_t vDestOffsetIncrement;
    destImage.getOffsetIncrementsPerDataRow(
            &yDestOffsetIncrement, &uDestOffsetIncrement, &vDestOffsetIncrement);

    // Copy Y
    {
        size_t numberOfYBytesPerRow = (size_t) width;
        uint8_t *ySrcAddr = ySrcAddrBase;
        uint8_t *yDestAddr = yDestAddrBase;
        for (int32_t offsetY = 0; offsetY < height; ++offsetY) {
            memcpy(yDestAddr, ySrcAddr, numberOfYBytesPerRow);

            ySrcAddr = ySrcAddr + ySrcOffsetIncrement;
            yDestAddr = yDestAddr + yDestOffsetIncrement;
        }
    }

    // Copy UV
    {
        // UV are interleaved. So number of UV bytes per row is 2*(width/2).
        size_t numberOfUVBytesPerRow = (size_t) width;
        uint8_t *vSrcAddr = vSrcAddrBase;
        uint8_t *vDestAddr = vDestAddrBase;
        // Every other pixel row has a U/V data row. Hence only go half the height.
        for (int32_t offsetY = 0; offsetY < (height >> 1); ++offsetY) {
            memcpy(vDestAddr, vSrcAddr, numberOfUVBytesPerRow);

            vSrcAddr += vSrcOffsetIncrement;
            vDestAddr += vDestOffsetIncrement;
        }
    }
}

// static
bool YUVImage::fastCopyRectangle(
        const Rect& srcRect,
        int32_t destStartX, int32_t destStartY,
        const YUVImage &srcImage, YUVImage &destImage) {
    if (srcImage.mYUVFormat == destImage.mYUVFormat) {
        if (srcImage.mYUVFormat == YUV420Planar) {
            fastCopyRectangle420Planar(
                    srcRect,
                    destStartX, destStartY,
                    srcImage, destImage);
        } else if (srcImage.mYUVFormat == YUV420SemiPlanar) {
            fastCopyRectangle420SemiPlanar(
                    srcRect,
                    destStartX, destStartY,
                    srcImage, destImage);
        }
        return true;
    }
    return false;
}

uint8_t clamp(uint8_t v, uint8_t minValue, uint8_t maxValue) {
    CHECK(maxValue >= minValue);

    if (v < minValue) return minValue;
    else if (v > maxValue) return maxValue;
    else return v;
}

void YUVImage::yuv2rgb(uint8_t yValue, uint8_t uValue, uint8_t vValue,
        uint8_t *r, uint8_t *g, uint8_t *b) const {
    *r = yValue + (1.370705 * (vValue-128));
    *g = yValue - (0.698001 * (vValue-128)) - (0.337633 * (uValue-128));
    *b = yValue + (1.732446 * (uValue-128));

    *r = clamp(*r, 0, 255);
    *g = clamp(*g, 0, 255);
    *b = clamp(*b, 0, 255);
}

bool YUVImage::writeToPPM(const char *filename) const {
    FILE *fp = fopen(filename, "w");
    if (fp == NULL) {
        return false;
    }
    fprintf(fp, "P3\n");
    fprintf(fp, "%d %d\n", mWidth, mHeight);
    fprintf(fp, "255\n");
    for (int32_t y = 0; y < mHeight; ++y) {
        for (int32_t x = 0; x < mWidth; ++x) {
            uint8_t yValue;
            uint8_t uValue;
            uint8_t vValue;
            getPixelValue(x, y, &yValue, &uValue, & vValue);

            uint8_t rValue;
            uint8_t gValue;
            uint8_t bValue;
            yuv2rgb(yValue, uValue, vValue, &rValue, &gValue, &bValue);

            fprintf(fp, "%d %d %d\n", (int32_t)rValue, (int32_t)gValue, (int32_t)bValue);
        }
    }
    fclose(fp);
    return true;
}

}  // namespace android

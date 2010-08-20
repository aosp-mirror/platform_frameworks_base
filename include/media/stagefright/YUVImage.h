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

// A container class to hold YUV data and provide various utilities,
// e.g. to set/get pixel values.
// Supported formats:
//  - YUV420 Planar
//  - YUV420 Semi Planar
//
//  Currently does not support variable strides.
//
//  Implementation: Two simple abstractions are done to simplify access
//  to YUV channels for different formats:
//  - initializeYUVPointers() sets up pointers (mYdata, mUdata, mVdata) to
//  point to the right start locations of the different channel data depending
//  on the format.
//  - getOffsets() returns the correct offset for the different channels
//  depending on the format.
//  Location of any pixel's YUV channels can then be easily computed using these.
//

#ifndef YUV_IMAGE_H_

#define YUV_IMAGE_H_

#include <stdint.h>
#include <cstring>

namespace android {

class Rect;

class YUVImage {
public:
    // Supported YUV formats
    enum YUVFormat {
        YUV420Planar,
        YUV420SemiPlanar
    };

    // Constructs an image with the given size, format. Also allocates and owns
    // the required memory.
    YUVImage(YUVFormat yuvFormat, int32_t width, int32_t height);

    // Constructs an image with the given size, format. The memory is provided
    // by the caller and we don't own it.
    YUVImage(YUVFormat yuvFormat, int32_t width, int32_t height, uint8_t *buffer);

    // Destructor to delete the memory if it owns it.
    ~YUVImage();

    // Returns the size of the buffer required to store the YUV data for the given
    // format and geometry. Useful when the caller wants to allocate the requisite
    // memory.
    static size_t bufferSize(YUVFormat yuvFormat, int32_t width, int32_t height);

    int32_t width() const {return mWidth;}
    int32_t height() const {return mHeight;}

    // Returns true if pixel is the range [0, width-1] x [0, height-1]
    // and false otherwise.
    bool validPixel(int32_t x, int32_t y) const;

    // Get the pixel YUV value at pixel (x,y).
    // Note that the range of x is [0, width-1] and the range of y is [0, height-1].
    // Returns true if get was successful and false otherwise.
    bool getPixelValue(int32_t x, int32_t y,
            uint8_t *yPtr, uint8_t *uPtr, uint8_t *vPtr) const;

    // Set the pixel YUV value at pixel (x,y).
    // Note that the range of x is [0, width-1] and the range of y is [0, height-1].
    // Returns true if set was successful and false otherwise.
    bool setPixelValue(int32_t x, int32_t y,
            uint8_t yValue, uint8_t uValue, uint8_t vValue);

    // Uses memcpy to copy an entire row of data
    static void fastCopyRectangle420Planar(
            const Rect& srcRect,
            int32_t destStartX, int32_t destStartY,
            const YUVImage &srcImage, YUVImage &destImage);

    // Uses memcpy to copy an entire row of data
    static void fastCopyRectangle420SemiPlanar(
            const Rect& srcRect,
            int32_t destStartX, int32_t destStartY,
            const YUVImage &srcImage, YUVImage &destImage);

    // Tries to use memcopy to copy entire rows of data.
    // Returns false if fast copy is not possible for the passed image formats.
    static bool fastCopyRectangle(
            const Rect& srcRect,
            int32_t destStartX, int32_t destStartY,
            const YUVImage &srcImage, YUVImage &destImage);

    // Convert the given YUV value to RGB.
    void yuv2rgb(uint8_t yValue, uint8_t uValue, uint8_t vValue,
        uint8_t *r, uint8_t *g, uint8_t *b) const;

    // Write the image to a human readable PPM file.
    // Returns true if write was succesful and false otherwise.
    bool writeToPPM(const char *filename) const;

private:
    // YUV Format of the image.
    YUVFormat mYUVFormat;

    int32_t mWidth;
    int32_t mHeight;

    // Pointer to the memory buffer.
    uint8_t *mBuffer;

    // Boolean telling whether we own the memory buffer.
    bool mOwnBuffer;

    // Pointer to start of the Y data plane.
    uint8_t *mYdata;

    // Pointer to start of the U data plane. Note that in case of interleaved formats like
    // YUV420 semiplanar, mUdata points to the start of the U data in the UV plane.
    uint8_t *mUdata;

    // Pointer to start of the V data plane. Note that in case of interleaved formats like
    // YUV420 semiplanar, mVdata points to the start of the V data in the UV plane.
    uint8_t *mVdata;

    // Initialize the pointers mYdata, mUdata, mVdata to point to the right locations for
    // the given format and geometry.
    // Returns true if initialize was succesful and false otherwise.
    bool initializeYUVPointers();

    // For the given pixel location, this returns the offset of the location of y, u and v
    // data from the corresponding base pointers -- mYdata, mUdata, mVdata.
    // Note that the range of x is [0, width-1] and the range of y is [0, height-1].
    // Returns true if getting offsets was succesful and false otherwise.
    bool getOffsets(int32_t x, int32_t y,
        int32_t *yOffset, int32_t *uOffset, int32_t *vOffset) const;

    // Returns the offset increments incurred in going from one data row to the next data row
    // for the YUV channels. Note that this corresponds to data rows and not pixel rows.
    // E.g. depending on formats, U/V channels may have only one data row corresponding
    // to two pixel rows.
    bool getOffsetIncrementsPerDataRow(
        int32_t *yDataOffsetIncrement,
        int32_t *uDataOffsetIncrement,
        int32_t *vDataOffsetIncrement) const;

    // Given the offset return the address of the corresponding channel's data.
    uint8_t* getYAddress(int32_t offset) const;
    uint8_t* getUAddress(int32_t offset) const;
    uint8_t* getVAddress(int32_t offset) const;

    // Given the pixel location, returns the address of the corresponding channel's data.
    // Note that the range of x is [0, width-1] and the range of y is [0, height-1].
    bool getYUVAddresses(int32_t x, int32_t y,
        uint8_t **yAddr, uint8_t **uAddr, uint8_t **vAddr) const;

    // Disallow implicit casting and copying.
    YUVImage(const YUVImage &);
    YUVImage &operator=(const YUVImage &);
};

}  // namespace android

#endif  // YUV_IMAGE_H_

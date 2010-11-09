/*
**
** Copyright (C) 2008 The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#ifndef ANDROID_VIDEO_FRAME_H
#define ANDROID_VIDEO_FRAME_H

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <utils/Log.h>

namespace android {

// A simple buffer to hold binary data
class MediaAlbumArt 
{
public:
    MediaAlbumArt(): mSize(0), mData(0) {}

    explicit MediaAlbumArt(const char* url) {
        mSize = 0;
        mData = NULL;
        FILE *in = fopen(url, "r");
        if (!in) {
            return;
        }
        fseek(in, 0, SEEK_END);
        mSize = ftell(in);  // Allocating buffer of size equals to the external file size.
        if (mSize == 0 || (mData = new uint8_t[mSize]) == NULL) {
            fclose(in);
            if (mSize != 0) {
                mSize = 0;
            }
            return;
        }
        rewind(in);
        if (fread(mData, 1, mSize, in) != mSize) {  // Read failed.
            delete[] mData;
            mData = NULL;
            mSize = 0;
            return;
        }
        fclose(in);
    }

    MediaAlbumArt(const MediaAlbumArt& copy) { 
        mSize = copy.mSize; 
        mData = NULL;  // initialize it first 
        if (mSize > 0 && copy.mData != NULL) {
           mData = new uint8_t[copy.mSize];
           if (mData != NULL) {
               memcpy(mData, copy.mData, mSize);
           } else {
               mSize = 0;
           }
        }
    }

    ~MediaAlbumArt() {
        if (mData != 0) {
            delete[] mData;
        }
    }

    // Intentional public access modifier:
    // We have to know the internal structure in order to share it between
    // processes?
    uint32_t mSize;            // Number of bytes in mData
    uint8_t* mData;            // Actual binary data
};

// Represents a color converted (RGB-based) video frame
// with bitmap pixels stored in FrameBuffer
class VideoFrame
{
public:
    VideoFrame(): mWidth(0), mHeight(0), mDisplayWidth(0), mDisplayHeight(0), mSize(0), mData(0) {}
 
    VideoFrame(const VideoFrame& copy) {
        mWidth = copy.mWidth;
        mHeight = copy.mHeight;
        mDisplayWidth = copy.mDisplayWidth;
        mDisplayHeight = copy.mDisplayHeight;
        mSize = copy.mSize;
        mData = NULL;  // initialize it first
        if (mSize > 0 && copy.mData != NULL) {
            mData = new uint8_t[mSize];
            if (mData != NULL) {
                memcpy(mData, copy.mData, mSize);
            } else {
                mSize = 0;
            }
        }
    }

    ~VideoFrame() {
        if (mData != 0) {
            delete[] mData;
        }
    }

    // Intentional public access modifier:
    uint32_t mWidth;
    uint32_t mHeight;
    uint32_t mDisplayWidth;
    uint32_t mDisplayHeight;
    uint32_t mSize;            // Number of bytes in mData
    uint8_t* mData;            // Actual binary data
    int32_t  mRotationAngle;   // rotation angle, clockwise
};

}; // namespace android

#endif // ANDROID_VIDEO_FRAME_H

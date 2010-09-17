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

#ifndef ANDROID_UI_LINE_H
#define ANDROID_UI_LINE_H

#include <GLES2/gl2.h>

#include <cmath>

#include <sys/types.h>

#include "Patch.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Globals
///////////////////////////////////////////////////////////////////////////////

// Alpha8 texture used to perform texture anti-aliasing
static const uint8_t gLineTexture[] = {
        0,   0,   0,   0, 0,
        0, 255, 255, 255, 0,
        0, 255, 255, 255, 0,
        0, 255, 255, 255, 0,
        0,   0,   0,   0, 0
};
static const GLsizei gLineTextureWidth = 5;
static const GLsizei gLineTextureHeight = 5;
static const float gLineAABias = 1.0f;

///////////////////////////////////////////////////////////////////////////////
// Line
///////////////////////////////////////////////////////////////////////////////

class Line {
public:
    Line(): mXDivsCount(2), mYDivsCount(2) {
        mPatch = new Patch(mXDivsCount, mYDivsCount);
        mXDivs = new int32_t[mXDivsCount];
        mYDivs = new int32_t[mYDivsCount];

        mXDivs[0] = mYDivs[0] = 2;
        mXDivs[1] = mYDivs[1] = 3;

        glGenTextures(1, &mTexture);
        glBindTexture(GL_TEXTURE_2D, mTexture);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, gLineTextureWidth, gLineTextureHeight, 0,
                GL_ALPHA, GL_UNSIGNED_BYTE, gLineTexture);
    }

    ~Line() {
        delete mPatch;
        delete[] mXDivs;
        delete[] mYDivs;

        glDeleteTextures(1, &mTexture);
    }

    void update(float x1, float y1, float x2, float y2, float lineWidth, float& tx, float& ty) {
        const float length = sqrtf((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));
        const float half = lineWidth * 0.5f;

        mPatch->updateVertices(gLineTextureWidth, gLineTextureHeight,
                -gLineAABias, -half - gLineAABias, length + gLineAABias, half + gLineAABias,
                mXDivs, mYDivs, mXDivsCount, mYDivsCount);

        tx = -gLineAABias;
        ty = -half - gLineAABias;
    }

    inline GLvoid* getVertices() const {
        return &mPatch->vertices[0].position[0];
    }

    inline GLvoid* getTexCoords() const {
        return &mPatch->vertices[0].texture[0];
    }

    inline GLsizei getElementsCount() const {
        return mPatch->verticesCount;
    }

    inline GLuint getTexture() const {
        return mTexture;
    }

private:
    uint32_t mXDivsCount;
    uint32_t mYDivsCount;

    int32_t* mXDivs;
    int32_t* mYDivs;

    Patch* mPatch;

    GLuint mTexture;
}; // class Line

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_LINE_H

/*
 * Copyright (C) 2008 The Android Open Source Project
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

#include <utils/Log.h>
#include <malloc.h>
#include <string.h>

#include "RenderScript.h"
#include "Element.h"
#include "Type.h"

void Type::calcElementCount() {
    bool hasLod = hasMipmaps();
    uint32_t x = getX();
    uint32_t y = getY();
    uint32_t z = getZ();
    uint32_t faces = 1;
    if (hasFaces()) {
        faces = 6;
    }
    if (x == 0) {
        x = 1;
    }
    if (y == 0) {
        y = 1;
    }
    if (z == 0) {
        z = 1;
    }

    uint32_t count = x * y * z * faces;
    while (hasLod && ((x > 1) || (y > 1) || (z > 1))) {
        if(x > 1) {
            x >>= 1;
        }
        if(y > 1) {
            y >>= 1;
        }
        if(z > 1) {
            z >>= 1;
        }

        count += x * y * z * faces;
    }
    mElementCount = count;
}


Type::Type(void *id, RenderScript *rs) : BaseObj(id, rs) {
    mDimX = 0;
    mDimY = 0;
    mDimZ = 0;
    mDimMipmaps = false;
    mDimFaces = false;
    mElement = NULL;
}

void Type::updateFromNative() {
    // We have 6 integer to obtain mDimX; mDimY; mDimZ;
    // mDimLOD; mDimFaces; mElement;

    /*
    int[] dataBuffer = new int[6];
    mRS.nTypeGetNativeData(getID(), dataBuffer);

    mDimX = dataBuffer[0];
    mDimY = dataBuffer[1];
    mDimZ = dataBuffer[2];
    mDimMipmaps = dataBuffer[3] == 1 ? true : false;
    mDimFaces = dataBuffer[4] == 1 ? true : false;

    int elementID = dataBuffer[5];
    if(elementID != 0) {
        mElement = new Element(elementID, mRS);
        mElement.updateFromNative();
    }
    calcElementCount();
    */
}

Type::Builder::Builder(RenderScript *rs, const Element *e) {
    mRS = rs;
    mElement = e;
    mDimX = 0;
    mDimY = 0;
    mDimZ = 0;
    mDimMipmaps = false;
    mDimFaces = false;
}

void Type::Builder::setX(uint32_t value) {
    if(value < 1) {
        ALOGE("Values of less than 1 for Dimension X are not valid.");
    }
    mDimX = value;
}

void Type::Builder::setY(int value) {
    if(value < 1) {
        ALOGE("Values of less than 1 for Dimension Y are not valid.");
    }
    mDimY = value;
}

void Type::Builder::setMipmaps(bool value) {
    mDimMipmaps = value;
}

void Type::Builder::setFaces(bool value) {
    mDimFaces = value;
}

const Type * Type::Builder::create() {
    ALOGE(" %i %i %i %i %i", mDimX, mDimY, mDimZ, mDimFaces, mDimMipmaps);
    if (mDimZ > 0) {
        if ((mDimX < 1) || (mDimY < 1)) {
            ALOGE("Both X and Y dimension required when Z is present.");
        }
        if (mDimFaces) {
            ALOGE("Cube maps not supported with 3D types.");
        }
    }
    if (mDimY > 0) {
        if (mDimX < 1) {
            ALOGE("X dimension required when Y is present.");
        }
    }
    if (mDimFaces) {
        if (mDimY < 1) {
            ALOGE("Cube maps require 2D Types.");
        }
    }

    void * id = rsTypeCreate(mRS->mContext, mElement->getID(), mDimX, mDimY, mDimZ, mDimMipmaps, mDimFaces);
    Type *t = new Type(id, mRS);
    t->mElement = mElement;
    t->mDimX = mDimX;
    t->mDimY = mDimY;
    t->mDimZ = mDimZ;
    t->mDimMipmaps = mDimMipmaps;
    t->mDimFaces = mDimFaces;

    t->calcElementCount();
    return t;
}


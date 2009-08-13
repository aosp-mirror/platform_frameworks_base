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

#include "rsComponent.h"
#include <GLES/gl.h>

using namespace android;
using namespace android::renderscript;


Component::Component()
{
    mType = FLOAT;
    mKind = NONE;
    mIsNormalized = false;
    mBits = 0;
}

Component::Component(
    DataKind dk, DataType dt,
    bool isNormalized, uint32_t bits, const char * name)
{
    mType = dt;
    mKind = dk;
    mIsNormalized = isNormalized;
    mBits = bits;
    if (name) {
        mName = name;
    }
}

const char * Component::getCType() const
{
    switch(mType) {
    case FLOAT:
        return "float";
    case SIGNED:
    case UNSIGNED:
        switch(mBits) {
        case 32:
            return "int";
        case 16:
            return "short";
        case 8:
            return "char";
        }
        break;
    }
    return NULL;
}

Component::~Component()
{
}

uint32_t Component::getGLType() const
{
    switch(mType) {
    case RS_TYPE_FLOAT:
        rsAssert(mBits == 32);
        return GL_FLOAT;
    case RS_TYPE_SIGNED:
        switch(mBits) {
        case 32:
            return 0;//GL_INT;
        case 16:
            return GL_SHORT;
        case 8:
            return GL_BYTE;
        }
        break;
    case RS_TYPE_UNSIGNED:
        switch(mBits) {
        case 32:
            return 0;//GL_UNSIGNED_INT;
        case 16:
            return GL_UNSIGNED_SHORT;
        case 8:
            return GL_UNSIGNED_BYTE;
        }
        break;
    }
    //rsAssert(!"Bad type");
    //LOGE("mType %i, mKind %i, mBits %i, mIsNormalized %i", mType, mKind, mBits, mIsNormalized);
    return 0;
}



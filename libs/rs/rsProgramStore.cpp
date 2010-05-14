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
#include "rsProgramStore.h"

#include <GLES/gl.h>
#include <GLES/glext.h>

using namespace android;
using namespace android::renderscript;


ProgramStore::ProgramStore(Context *rsc) :
    Program(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mDitherEnable = true;
    mBlendEnable = false;
    mColorRWriteEnable = true;
    mColorGWriteEnable = true;
    mColorBWriteEnable = true;
    mColorAWriteEnable = true;
    mBlendSrc = GL_ONE;
    mBlendDst = GL_ZERO;


    mDepthTestEnable = false;
    mDepthWriteEnable = true;
    mDepthFunc = GL_LESS;


}

ProgramStore::~ProgramStore()
{
}

void ProgramStore::setupGL(const Context *rsc, ProgramStoreState *state)
{
    if (state->mLast.get() == this) {
        return;
    }
    state->mLast.set(this);

    glColorMask(mColorRWriteEnable,
                mColorGWriteEnable,
                mColorBWriteEnable,
                mColorAWriteEnable);
    if (mBlendEnable) {
        glEnable(GL_BLEND);
        glBlendFunc(mBlendSrc, mBlendDst);
    } else {
        glDisable(GL_BLEND);
    }

    //LOGE("pfs  %i, %i, %x", mDepthWriteEnable, mDepthTestEnable, mDepthFunc);

    glDepthMask(mDepthWriteEnable);
    if(mDepthTestEnable || mDepthWriteEnable) {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(mDepthFunc);
    } else {
        glDisable(GL_DEPTH_TEST);
    }

    if (mDitherEnable) {
        glEnable(GL_DITHER);
    } else {
        glDisable(GL_DITHER);
    }
}

void ProgramStore::setupGL2(const Context *rsc, ProgramStoreState *state)
{
    if (state->mLast.get() == this) {
        return;
    }
    state->mLast.set(this);

    glColorMask(mColorRWriteEnable,
                mColorGWriteEnable,
                mColorBWriteEnable,
                mColorAWriteEnable);
    if (mBlendEnable) {
        glEnable(GL_BLEND);
        glBlendFunc(mBlendSrc, mBlendDst);
    } else {
        glDisable(GL_BLEND);
    }

    //LOGE("pfs  %i, %i, %x", mDepthWriteEnable, mDepthTestEnable, mDepthFunc);

    glDepthMask(mDepthWriteEnable);
    if(mDepthTestEnable || mDepthWriteEnable) {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(mDepthFunc);
    } else {
        glDisable(GL_DEPTH_TEST);
    }

    if (mDitherEnable) {
        glEnable(GL_DITHER);
    } else {
        glDisable(GL_DITHER);
    }
}


void ProgramStore::setDitherEnable(bool enable)
{
    mDitherEnable = enable;
}

void ProgramStore::setDepthFunc(RsDepthFunc func)
{
    mDepthTestEnable = true;

    switch(func) {
    case RS_DEPTH_FUNC_ALWAYS:
        mDepthTestEnable = false;
        mDepthFunc = GL_ALWAYS;
        break;
    case RS_DEPTH_FUNC_LESS:
        mDepthFunc = GL_LESS;
        break;
    case RS_DEPTH_FUNC_LEQUAL:
        mDepthFunc = GL_LEQUAL;
        break;
    case RS_DEPTH_FUNC_GREATER:
        mDepthFunc = GL_GREATER;
        break;
    case RS_DEPTH_FUNC_GEQUAL:
        mDepthFunc = GL_GEQUAL;
        break;
    case RS_DEPTH_FUNC_EQUAL:
        mDepthFunc = GL_EQUAL;
        break;
    case RS_DEPTH_FUNC_NOTEQUAL:
        mDepthFunc = GL_NOTEQUAL;
        break;
    }
}

void ProgramStore::setDepthMask(bool mask)
{
    mDepthWriteEnable = mask;
}

void ProgramStore::setBlendFunc(RsBlendSrcFunc src, RsBlendDstFunc dst)
{
    mBlendEnable = true;
    if ((src == RS_BLEND_SRC_ONE) &&
        (dst == RS_BLEND_DST_ZERO)) {
        mBlendEnable = false;
    }

    switch(src) {
    case RS_BLEND_SRC_ZERO:
        mBlendSrc = GL_ZERO;
        break;
    case RS_BLEND_SRC_ONE:
        mBlendSrc = GL_ONE;
        break;
    case RS_BLEND_SRC_DST_COLOR:
        mBlendSrc = GL_DST_COLOR;
        break;
    case RS_BLEND_SRC_ONE_MINUS_DST_COLOR:
        mBlendSrc = GL_ONE_MINUS_DST_COLOR;
        break;
    case RS_BLEND_SRC_SRC_ALPHA:
        mBlendSrc = GL_SRC_ALPHA;
        break;
    case RS_BLEND_SRC_ONE_MINUS_SRC_ALPHA:
        mBlendSrc = GL_ONE_MINUS_SRC_ALPHA;
        break;
    case RS_BLEND_SRC_DST_ALPHA:
        mBlendSrc = GL_DST_ALPHA;
        break;
    case RS_BLEND_SRC_ONE_MINUS_DST_ALPHA:
        mBlendSrc = GL_ONE_MINUS_DST_ALPHA;
        break;
    case RS_BLEND_SRC_SRC_ALPHA_SATURATE:
        mBlendSrc = GL_SRC_ALPHA_SATURATE;
        break;
    }

    switch(dst) {
    case RS_BLEND_DST_ZERO:
        mBlendDst = GL_ZERO;
        break;
    case RS_BLEND_DST_ONE:
        mBlendDst = GL_ONE;
        break;
    case RS_BLEND_DST_SRC_COLOR:
        mBlendDst = GL_SRC_COLOR;
        break;
    case RS_BLEND_DST_ONE_MINUS_SRC_COLOR:
        mBlendDst = GL_ONE_MINUS_SRC_COLOR;
        break;
    case RS_BLEND_DST_SRC_ALPHA:
        mBlendDst = GL_SRC_ALPHA;
        break;
    case RS_BLEND_DST_ONE_MINUS_SRC_ALPHA:
        mBlendDst = GL_ONE_MINUS_SRC_ALPHA;
        break;
    case RS_BLEND_DST_DST_ALPHA:
        mBlendDst = GL_DST_ALPHA;
        break;
    case RS_BLEND_DST_ONE_MINUS_DST_ALPHA:
        mBlendDst = GL_ONE_MINUS_DST_ALPHA;
        break;
    }
}

void ProgramStore::setColorMask(bool r, bool g, bool b, bool a)
{
    mColorRWriteEnable = r;
    mColorGWriteEnable = g;
    mColorBWriteEnable = b;
    mColorAWriteEnable = a;
}


ProgramStoreState::ProgramStoreState()
{
    mPFS = NULL;
}

ProgramStoreState::~ProgramStoreState()
{
    delete mPFS;

}

void ProgramStoreState::init(Context *rsc, int32_t w, int32_t h)
{
    ProgramStore *pfs = new ProgramStore(rsc);
    mDefault.set(pfs);
}

void ProgramStoreState::deinit(Context *rsc)
{
    mDefault.clear();
    mLast.clear();
}


namespace android {
namespace renderscript {

void rsi_ProgramStoreBegin(Context * rsc, RsElement in, RsElement out)
{
    delete rsc->mStateFragmentStore.mPFS;
    rsc->mStateFragmentStore.mPFS = new ProgramStore(rsc);

}

void rsi_ProgramStoreDepthFunc(Context *rsc, RsDepthFunc func)
{
    rsc->mStateFragmentStore.mPFS->setDepthFunc(func);
}

void rsi_ProgramStoreDepthMask(Context *rsc, bool mask)
{
    rsc->mStateFragmentStore.mPFS->setDepthMask(mask);
}

void rsi_ProgramStoreColorMask(Context *rsc, bool r, bool g, bool b, bool a)
{
    rsc->mStateFragmentStore.mPFS->setColorMask(r, g, b, a);
}

void rsi_ProgramStoreBlendFunc(Context *rsc, RsBlendSrcFunc src, RsBlendDstFunc dst)
{
    rsc->mStateFragmentStore.mPFS->setBlendFunc(src, dst);
}

RsProgramStore rsi_ProgramStoreCreate(Context *rsc)
{
    ProgramStore *pfs = rsc->mStateFragmentStore.mPFS;
    pfs->incUserRef();
    rsc->mStateFragmentStore.mPFS = 0;
    return pfs;
}

void rsi_ProgramStoreDither(Context *rsc, bool enable)
{
    rsc->mStateFragmentStore.mPFS->setDitherEnable(enable);
}


}
}

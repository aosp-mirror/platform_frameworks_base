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

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>

#include <hardware/hardware.h>

#include <cutils/log.h>

#include <EGL/egl.h>

#include "HWComposer.h"

namespace android {
// ---------------------------------------------------------------------------

HWComposer::HWComposer()
    : mModule(0), mHwc(0), mList(0),
      mDpy(EGL_NO_DISPLAY), mSur(EGL_NO_SURFACE)
{
    int err = hw_get_module(HWC_HARDWARE_MODULE_ID, &mModule);
    LOGW_IF(err, "%s module not found", HWC_HARDWARE_MODULE_ID);
    if (err == 0) {
        err = hwc_open(mModule, &mHwc);
        LOGE_IF(err, "%s device failed to initialize (%s)",
                HWC_HARDWARE_COMPOSER, strerror(-err));
    }
}

HWComposer::~HWComposer() {
    free(mList);
    if (mHwc) {
        hwc_close(mHwc);
    }
}

status_t HWComposer::initCheck() const {
    return mHwc ? NO_ERROR : NO_INIT;
}

void HWComposer::setFrameBuffer(EGLDisplay dpy, EGLSurface sur) {
    mDpy = (hwc_display_t)dpy;
    mSur = (hwc_surface_t)sur;
}

status_t HWComposer::createWorkList(size_t numLayers) {
    if (mHwc && (!mList || mList->numHwLayers < numLayers)) {
        free(mList);
        size_t size = sizeof(hwc_layer_list) + numLayers*sizeof(hwc_layer_t);
        mList = (hwc_layer_list_t*)malloc(size);
        mList->flags = HWC_GEOMETRY_CHANGED;
        mList->numHwLayers = numLayers;
    }
    return NO_ERROR;
}

status_t HWComposer::prepare() const {
    int err = mHwc->prepare(mHwc, mList);
    return (status_t)err;
}

status_t HWComposer::commit() const {
    int err = mHwc->set(mHwc, mDpy, mSur, mList);
    mList->flags &= ~HWC_GEOMETRY_CHANGED;
    return (status_t)err;
}

HWComposer::iterator HWComposer::begin() {
    return mList ? &(mList->hwLayers[0]) : NULL;
}

HWComposer::iterator HWComposer::end() {
    return mList ? &(mList->hwLayers[mList->numHwLayers]) : NULL;
}

// ---------------------------------------------------------------------------
}; // namespace android

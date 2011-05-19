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

#include "rsDevice.h"
#include "rsContext.h"

using namespace android;
using namespace android::renderscript;

Device::Device() {
    mForceSW = false;
}

Device::~Device() {
}

void Device::addContext(Context *rsc) {
    mContexts.push(rsc);
}

void Device::removeContext(Context *rsc) {
    for (size_t idx=0; idx < mContexts.size(); idx++) {
        if (mContexts[idx] == rsc) {
            mContexts.removeAt(idx);
            break;
        }
    }
}

RsDevice rsDeviceCreate() {
    Device * d = new Device();
    return d;
}

void rsDeviceDestroy(RsDevice dev) {
    Device * d = static_cast<Device *>(dev);
    delete d;
}

void rsDeviceSetConfig(RsDevice dev, RsDeviceParam p, int32_t value) {
    Device * d = static_cast<Device *>(dev);
    if (p == RS_DEVICE_PARAM_FORCE_SOFTWARE_GL) {
        d->mForceSW = value != 0;
        return;
    }
    rsAssert(0);
}


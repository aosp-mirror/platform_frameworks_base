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

#include "OMXPVCodecsPlugin.h"

#include "pv_omxcore.h"

#include <media/stagefright/MediaDebug.h>

namespace android {

OMXPVCodecsPlugin::OMXPVCodecsPlugin() {
    OMX_MasterInit();
}

OMXPVCodecsPlugin::~OMXPVCodecsPlugin() {
    OMX_MasterDeinit();
}

OMX_ERRORTYPE OMXPVCodecsPlugin::makeComponentInstance(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component) {
    OMX_ERRORTYPE err = OMX_MasterGetHandle(
            reinterpret_cast<OMX_HANDLETYPE *>(component),
            const_cast<char *>(name),
            appData,
            const_cast<OMX_CALLBACKTYPE *>(callbacks));

    if (err != OMX_ErrorNone) {
        return err;
    }

    // PV is not even filling this in...
    (*component)->ComponentDeInit = &OMX_MasterFreeHandle;

    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMXPVCodecsPlugin::enumerateComponents(
        OMX_STRING name,
        size_t size,
        OMX_U32 index) {
    return OMX_MasterComponentNameEnum(name, size, index);
}

}  // namespace android

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

#include "OMXSoftwareCodecsPlugin.h"

#include <string.h>

namespace android {

typedef OMX_ERRORTYPE (*ComponentFactory)(
        const OMX_CALLBACKTYPE *callbacks, OMX_PTR appData,
        OMX_COMPONENTTYPE **component);

static const struct ComponentInfo {
    const char *mName;
    ComponentFactory mFactory;
} kComponentInfos[] = {
};

OMXSoftwareCodecsPlugin::OMXSoftwareCodecsPlugin() {
}

OMX_ERRORTYPE OMXSoftwareCodecsPlugin::makeComponentInstance(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component) {
    *component = NULL;

    const size_t kNumComponentInfos =
        sizeof(kComponentInfos) / sizeof(kComponentInfos[0]);

    for (size_t i = 0; i < kNumComponentInfos; ++i) {
        if (!strcmp(kComponentInfos[i].mName, name)) {
            return (*kComponentInfos[i].mFactory)(
                    callbacks, appData, component);
        }
    }

    return OMX_ErrorInvalidComponentName;
}

OMX_ERRORTYPE OMXSoftwareCodecsPlugin::destroyComponentInstance(
        OMX_COMPONENTTYPE *component) {
    return (*component->ComponentDeInit)(component);
}

OMX_ERRORTYPE OMXSoftwareCodecsPlugin::enumerateComponents(
        OMX_STRING name,
        size_t size,
        OMX_U32 index) {
    const size_t kNumComponentInfos =
        sizeof(kComponentInfos) / sizeof(kComponentInfos[0]);

    if (index >= kNumComponentInfos) {
        return OMX_ErrorNoMore;
    }

    strncpy(name, kComponentInfos[index].mName, size - 1);
    name[size - 1] = '\0';

    return OMX_ErrorNone;
}

}  // namespace android

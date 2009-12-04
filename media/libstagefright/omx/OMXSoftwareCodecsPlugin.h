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

#ifndef OMX_SOFTWARE_CODECS_PLUGIN_H_

#define OMX_SOFTWARE_CODECS_PLUGIN_H_

#include <media/stagefright/OMXPluginBase.h>

namespace android {

struct OMXSoftwareCodecsPlugin : public OMXPluginBase {
    OMXSoftwareCodecsPlugin();

    virtual OMX_ERRORTYPE makeComponentInstance(
            const char *name,
            const OMX_CALLBACKTYPE *callbacks,
            OMX_PTR appData,
            OMX_COMPONENTTYPE **component);

    virtual OMX_ERRORTYPE destroyComponentInstance(
            OMX_COMPONENTTYPE *component);

    virtual OMX_ERRORTYPE enumerateComponents(
            OMX_STRING name,
            size_t size,
            OMX_U32 index);

private:
    OMXSoftwareCodecsPlugin(const OMXSoftwareCodecsPlugin &);
    OMXSoftwareCodecsPlugin &operator=(const OMXSoftwareCodecsPlugin &);
};

}  // namespace android

#endif  // OMX_SOFTWARE_CODECS_PLUGIN_H_


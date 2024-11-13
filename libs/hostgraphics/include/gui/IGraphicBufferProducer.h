/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef ANDROID_GUI_IGRAPHICBUFFERPRODUCER_H
#define ANDROID_GUI_IGRAPHICBUFFERPRODUCER_H

#include <ui/GraphicBuffer.h>
#include <utils/RefBase.h>

namespace android {

class IGraphicBufferProducer : virtual public RefBase {
public:
    enum class DisconnectMode {
        // Disconnect only the specified API.
        Api,
        // Disconnect any API originally connected from the process calling disconnect.
        AllLocal
    };

    virtual int query(int what, int* value) = 0;

    virtual status_t requestBuffer(int slot, sp<GraphicBuffer>* buf) = 0;
};

} // namespace android

#endif // ANDROID_GUI_IGRAPHICBUFFERPRODUCER_H

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

#ifndef ANDROID_GUI_BUFFERQUEUE_H
#define ANDROID_GUI_BUFFERQUEUE_H

#include <gui/BufferItem.h>
#include <gui/IGraphicBufferConsumer.h>
#include <gui/IGraphicBufferProducer.h>

namespace android {

class BufferQueue {
public:
    enum { INVALID_BUFFER_SLOT = BufferItem::INVALID_BUFFER_SLOT };
    enum { NO_BUFFER_AVAILABLE = IGraphicBufferConsumer::NO_BUFFER_AVAILABLE };

    static void createBufferQueue(sp<IGraphicBufferProducer>* outProducer,
            sp<IGraphicBufferConsumer>* outConsumer);
};

} // namespace android

#endif // ANDROID_GUI_BUFFERQUEUE_H

/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include <gui/DisplayEventReceiver.h>
#include <utils/Log.h>
#include <utils/Looper.h>

namespace android {

class DisplayEventDispatcher : public LooperCallback {
public:
    explicit DisplayEventDispatcher(const sp<Looper>& looper,
            ISurfaceComposer::VsyncSource vsyncSource = ISurfaceComposer::eVsyncSourceApp);

    status_t initialize();
    void dispose();
    status_t scheduleVsync();

protected:
    virtual ~DisplayEventDispatcher() = default;

private:
    sp<Looper> mLooper;
    DisplayEventReceiver mReceiver;
    bool mWaitingForVsync;

    virtual void dispatchVsync(nsecs_t timestamp, int32_t id, uint32_t count) = 0;
    virtual void dispatchHotplug(nsecs_t timestamp, int32_t id, bool connected) = 0;

    virtual int handleEvent(int receiveFd, int events, void* data);
    bool processPendingEvents(nsecs_t* outTimestamp, int32_t* id, uint32_t* outCount);
};
}

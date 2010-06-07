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

#ifndef A_HANDLER_H_

#define A_HANDLER_H_

#include <media/stagefright/foundation/ALooper.h>
#include <utils/RefBase.h>

namespace android {

struct AMessage;

struct AHandler : public RefBase {
    AHandler()
        : mID(0) {
    }

    ALooper::handler_id id() const {
        return mID;
    }

protected:
    virtual void onMessageReceived(const sp<AMessage> &msg) = 0;

private:
    friend struct ALooperRoster;

    ALooper::handler_id mID;

    void setID(ALooper::handler_id id) {
        mID = id;
    }

    DISALLOW_EVIL_CONSTRUCTORS(AHandler);
};

}  // namespace android

#endif  // A_HANDLER_H_

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

#ifndef UDP_PUSHER_H_

#define UDP_PUSHER_H_

#include <media/stagefright/foundation/AHandler.h>

#include <stdio.h>
#include <arpa/inet.h>

namespace android {

struct UDPPusher : public AHandler {
    UDPPusher(const char *filename, unsigned port);

    void start();

protected:
    virtual ~UDPPusher();
    virtual void onMessageReceived(const sp<AMessage> &msg);

private:
    enum {
        kWhatPush = 'push'
    };

    FILE *mFile;
    int mSocket;
    struct sockaddr_in mRemoteAddr;

    uint32_t mFirstTimeMs;
    int64_t mFirstTimeUs;

    bool onPush();

    DISALLOW_EVIL_CONSTRUCTORS(UDPPusher);
};

}  // namespace android

#endif  // UDP_PUSHER_H_

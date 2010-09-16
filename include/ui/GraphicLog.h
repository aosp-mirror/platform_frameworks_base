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

#ifndef _UI_GRAPHIC_LOG_H
#define _UI_GRAPHIC_LOG_H

#include <utils/Singleton.h>
#include <cutils/compiler.h>

namespace android {

class GraphicLog : public Singleton<GraphicLog>
{
    int32_t mEnabled;
    static void logImpl(int32_t tag, int32_t buffer);
    static void logImpl(int32_t tag, int32_t identity, int32_t buffer);

public:
    enum {
        SF_APP_DEQUEUE_BEFORE   = 60100,
        SF_APP_DEQUEUE_AFTER    = 60101,
        SF_APP_LOCK_BEFORE      = 60102,
        SF_APP_LOCK_AFTER       = 60103,
        SF_APP_QUEUE            = 60104,

        SF_REPAINT              = 60105,
        SF_COMPOSITION_COMPLETE = 60106,
        SF_UNLOCK_CLIENTS       = 60107,
        SF_SWAP_BUFFERS         = 60108,
        SF_REPAINT_DONE         = 60109,

        SF_FB_POST_BEFORE       = 60110,
        SF_FB_POST_AFTER        = 60111,
        SF_FB_DEQUEUE_BEFORE    = 60112,
        SF_FB_DEQUEUE_AFTER     = 60113,
        SF_FB_LOCK_BEFORE       = 60114,
        SF_FB_LOCK_AFTER        = 60115,
    };

    inline void log(int32_t tag, int32_t buffer) {
        if (CC_UNLIKELY(mEnabled))
            logImpl(tag, buffer);
    }
    inline void log(int32_t tag, int32_t identity, int32_t buffer) {
        if (CC_UNLIKELY(mEnabled))
            logImpl(tag, identity, buffer);
    }

    GraphicLog();

    void setEnabled(bool enable);
};

}

#endif // _UI_GRAPHIC_LOG_H


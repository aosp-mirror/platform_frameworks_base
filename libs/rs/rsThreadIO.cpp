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

#include "rsContext.h"

#include "rsThreadIO.h"

using namespace android;
using namespace android::renderscript;

ThreadIO::ThreadIO()
{
    mToCore.init(16 * 1024);
    mToClient.init(1024);
}

ThreadIO::~ThreadIO()
{
}

void ThreadIO::shutdown()
{
    mToCore.shutdown();
}

bool ThreadIO::playCoreCommands(Context *con, bool waitForCommand)
{
    bool ret = false;
    while(!mToCore.isEmpty() || waitForCommand) {
        uint32_t cmdID = 0;
        uint32_t cmdSize = 0;
        ret = true;
        if (con->props.mLogTimes) {
            con->timerSet(Context::RS_TIMER_IDLE);
        }
        const void * data = mToCore.get(&cmdID, &cmdSize);
        if (!cmdSize) {
            // exception occured, probably shutdown.
            return false;
        }
        if (con->props.mLogTimes) {
            con->timerSet(Context::RS_TIMER_INTERNAL);
        }
        waitForCommand = false;
        //LOGV("playCoreCommands 3 %i %i", cmdID, cmdSize);

        gPlaybackFuncs[cmdID](con, data);
        mToCore.next();
    }
    return ret;
}



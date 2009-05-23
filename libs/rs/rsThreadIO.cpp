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

#include <utils/Log.h>

#include "rsThreadIO.h"

using namespace android;
using namespace android::renderscript;

ThreadIO *android::renderscript::gIO = NULL;

ThreadIO::ThreadIO()
{
    mToCore.init(16 * 1024);
}

ThreadIO::~ThreadIO()
{
}

void ThreadIO::playCoreCommands(Context *con)
{
    //LOGE("playCoreCommands 1");
    uint32_t cmdID = 0;
    uint32_t cmdSize = 0;
    while(!mToCore.isEmpty()) {
        //LOGE("playCoreCommands 2");
        const void * data = mToCore.get(&cmdID, &cmdSize);
        //LOGE("playCoreCommands 3 %i %i", cmdID, cmdSize);

        gPlaybackFuncs[cmdID](con, data);
        //LOGE("playCoreCommands 4");

        mToCore.next();
        //LOGE("playCoreCommands 5");
    }
}



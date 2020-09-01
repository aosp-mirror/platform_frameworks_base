/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.dump;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Service for dumping extremely verbose content during a bug report
 *
 * Our primary service, SystemUIService, is dumped during the CRITICAL section of a bug report.
 * This has some advantages (we get to go first!), but also imposes strict limitations on how much
 * we can dump. This service exists to handle any content that is too large to be safely dumped
 * within those constraints, namely log buffers. It's dumped during the NORMAL section, along with
 * all other services.
 */
public class SystemUIAuxiliaryDumpService extends Service {
    private final DumpHandler mDumpHandler;

    @Inject
    public SystemUIAuxiliaryDumpService(DumpHandler dumpHandler) {
        mDumpHandler = dumpHandler;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // Simulate the NORMAL priority arg being passed to us
        mDumpHandler.dump(
                fd,
                pw,
                new String[] { DumpHandler.PRIORITY_ARG, DumpHandler.PRIORITY_ARG_NORMAL });
    }
}

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

package com.android.systemui.onehanded;

import android.content.Context;

import com.android.systemui.Dumpable;
import com.android.systemui.SystemUI;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.CommandQueue;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A service that controls UI of the one handed mode function.
 */
@Singleton
public class OneHandedUI extends SystemUI implements CommandQueue.Callbacks, Dumpable {
    private static final String TAG = "OneHandedUI";

    private final OneHandedManager mOneHandedManager;
    private final CommandQueue mCommandQueue;

    @Inject
    public OneHandedUI(Context context,
            CommandQueue commandQueue,
            OneHandedManager oneHandedManager,
            DumpManager dumpManager) {
        super(context);

        mCommandQueue = commandQueue;
        /* TODO(b/154290458) define a boolean system properties "support_one_handed_mode"
            boolean supportOneHanded = SystemProperties.getBoolean("support_one_handed_mode");
            if (!supportOneHanded) return; */
        mOneHandedManager = oneHandedManager;
    }

    @Override
    public void start() {
        /* TODO(b/154290458) define a boolean system properties "support_one_handed_mode"
            boolean supportOneHanded = SystemProperties.getBoolean("support_one_handed_mode");
            if (!supportOneHanded) return; */
        mCommandQueue.addCallback(this);
    }

    /**
     * Trigger one handed more
     */
    public void startOneHanded() {
        mOneHandedManager.startOneHanded();
        // TODO (b/149366439) MetricsEvent add here, user start OneHanded
    }

    /**
     * Dismiss one handed more
     */
    public void stopOneHanded() {
        mOneHandedManager.stopOneHanded();
        // TODO (b/149366439) MetricsEvent add here, user stop OneHanded
    }

    /**
     * Dump all one handed data of states
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final String innerPrefix = "  ";
        pw.println(TAG + "one handed states: ");

        if (mOneHandedManager != null) {
            ((OneHandedManagerImpl) mOneHandedManager).dump(fd, pw, args);
        }
    }
}

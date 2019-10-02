/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.tv;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.CommandQueue;


/**
 * Status bar implementation for "large screen" products that mostly present no on-screen nav.
 * Serves as a collection of UI components, rather than showing its own UI.
 * The following is the list of elements that constitute the TV-specific status bar:
 * <ul>
 * <li> {@link AudioRecordingDisclosureBar} - shown whenever applications are conducting audio
 * recording, discloses the responsible applications </li>
 * </ul>
 */
public class TvStatusBar extends SystemUI implements CommandQueue.Callbacks {

    @Override
    public void start() {
        putComponent(TvStatusBar.class, this);

        final IStatusBarService barService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        final CommandQueue commandQueue = getComponent(CommandQueue.class);
        commandQueue.addCallback(this);
        try {
            barService.registerStatusBar(commandQueue);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }

        new AudioRecordingDisclosureBar(mContext).start();
    }
}

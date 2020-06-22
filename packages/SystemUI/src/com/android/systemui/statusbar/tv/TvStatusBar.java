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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.SystemUI;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.tv.micdisclosure.AudioRecordingDisclosureBar;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/**
 * Status bar implementation for "large screen" products that mostly present no on-screen nav.
 * Serves as a collection of UI components, rather than showing its own UI.
 * The following is the list of elements that constitute the TV-specific status bar:
 * <ul>
 * <li> {@link AudioRecordingDisclosureBar} - shown whenever applications are conducting audio
 * recording, discloses the responsible applications </li>
 * </ul>
 */
@Singleton
public class TvStatusBar extends SystemUI implements CommandQueue.Callbacks {

    private static final String ACTION_OPEN_TV_NOTIFICATIONS_PANEL =
            "com.android.tv.action.OPEN_NOTIFICATIONS_PANEL";

    private final CommandQueue mCommandQueue;
    private final Lazy<AssistManager> mAssistManagerLazy;

    @Inject
    public TvStatusBar(Context context, CommandQueue commandQueue,
            Lazy<AssistManager> assistManagerLazy) {
        super(context);
        mCommandQueue = commandQueue;
        mAssistManagerLazy = assistManagerLazy;
    }

    @Override
    public void start() {
        final IStatusBarService barService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mCommandQueue.addCallback(this);
        try {
            barService.registerStatusBar(mCommandQueue);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }
    }

    @Override
    public void animateExpandNotificationsPanel() {
        startSystemActivity(new Intent(ACTION_OPEN_TV_NOTIFICATIONS_PANEL));
    }

    private void startSystemActivity(Intent intent) {
        PackageManager pm = mContext.getPackageManager();
        ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_SYSTEM_ONLY);
        if (ri != null && ri.activityInfo != null) {
            intent.setPackage(ri.activityInfo.packageName);
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        }
    }

    @Override
    public void startAssist(Bundle args) {
        mAssistManagerLazy.get().startAssist(args);
    }
}

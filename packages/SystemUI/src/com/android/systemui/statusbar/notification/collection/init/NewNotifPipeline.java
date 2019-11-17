/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.init;

import android.util.Log;

import com.android.systemui.DumpController;
import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifListBuilderImpl;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Initialization code for the new notification pipeline.
 */
@Singleton
public class NewNotifPipeline implements Dumpable {
    private final NotifCollection mNotifCollection;
    private final NotifListBuilderImpl mNotifPipeline;
    private final DumpController mDumpController;

    private final FakePipelineConsumer mFakePipelineConsumer = new FakePipelineConsumer();

    @Inject
    public NewNotifPipeline(
            NotifCollection notifCollection,
            NotifListBuilderImpl notifPipeline,
            DumpController dumpController) {
        mNotifCollection = notifCollection;
        mNotifPipeline = notifPipeline;
        mDumpController = dumpController;
    }

    /** Hooks the new pipeline up to NotificationManager */
    public void initialize(
            NotificationListener notificationService) {
        mFakePipelineConsumer.attach(mNotifPipeline);
        mNotifPipeline.attach(mNotifCollection);
        mNotifCollection.attach(notificationService);

        Log.d(TAG, "Notif pipeline initialized");

        mDumpController.registerDumpable("NotifPipeline", this);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mFakePipelineConsumer.dump(fd, pw, args);
    }

    private static final String TAG = "NewNotifPipeline";
}

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
import com.android.systemui.statusbar.notification.collection.coordinator.NotifCoordinators;
import com.android.systemui.statusbar.notification.collection.notifcollection.GroupCoalescer;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Initialization code for the new notification pipeline.
 */
@Singleton
public class NewNotifPipeline implements Dumpable {
    private final GroupCoalescer mGroupCoalescer;
    private final NotifCollection mNotifCollection;
    private final NotifListBuilderImpl mNotifPipeline;
    private final NotifCoordinators mNotifPluggableCoordinators;
    private final DumpController mDumpController;

    private final FakePipelineConsumer mFakePipelineConsumer = new FakePipelineConsumer();

    @Inject
    public NewNotifPipeline(
            GroupCoalescer groupCoalescer,
            NotifCollection notifCollection,
            NotifListBuilderImpl notifPipeline,
            NotifCoordinators notifCoordinators,
            DumpController dumpController) {
        mGroupCoalescer = groupCoalescer;
        mNotifCollection = notifCollection;
        mNotifPipeline = notifPipeline;
        mNotifPluggableCoordinators = notifCoordinators;
        mDumpController = dumpController;
    }

    /** Hooks the new pipeline up to NotificationManager */
    public void initialize(
            NotificationListener notificationService) {

        mDumpController.registerDumpable("NotifPipeline", this);

        // Wire up coordinators
        mFakePipelineConsumer.attach(mNotifPipeline);
        mNotifPluggableCoordinators.attach(mNotifCollection, mNotifPipeline);

        // Wire up pipeline
        mNotifPipeline.attach(mNotifCollection);
        mNotifCollection.attach(mGroupCoalescer);
        mGroupCoalescer.attach(notificationService);

        Log.d(TAG, "Notif pipeline initialized");
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mFakePipelineConsumer.dump(fd, pw, args);
        mNotifPluggableCoordinators.dump(fd, pw, args);
        mGroupCoalescer.dump(fd, pw, args);
    }

    private static final String TAG = "NewNotifPipeline";
}

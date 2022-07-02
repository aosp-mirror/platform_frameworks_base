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

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifInflaterImpl;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.PipelineDumpable;
import com.android.systemui.statusbar.notification.collection.PipelineDumper;
import com.android.systemui.statusbar.notification.collection.ShadeListBuilder;
import com.android.systemui.statusbar.notification.collection.coalescer.GroupCoalescer;
import com.android.systemui.statusbar.notification.collection.coordinator.NotifCoordinators;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.collection.render.NotifStackController;
import com.android.systemui.statusbar.notification.collection.render.RenderStageManager;
import com.android.systemui.statusbar.notification.collection.render.ShadeViewManager;
import com.android.systemui.statusbar.notification.collection.render.ShadeViewManagerFactory;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Initialization code for the new notification pipeline.
 */
@SysUISingleton
public class NotifPipelineInitializer implements Dumpable, PipelineDumpable {
    private final NotifPipeline mPipelineWrapper;
    private final GroupCoalescer mGroupCoalescer;
    private final NotifCollection mNotifCollection;
    private final ShadeListBuilder mListBuilder;
    private final RenderStageManager mRenderStageManager;
    private final NotifCoordinators mNotifPluggableCoordinators;
    private final NotifInflaterImpl mNotifInflater;
    private final DumpManager mDumpManager;
    private final ShadeViewManagerFactory mShadeViewManagerFactory;

    /* These are saved just for dumping. */
    private ShadeViewManager mShadeViewManager;
    private NotificationListener mNotificationService;

    @Inject
    public NotifPipelineInitializer(
            NotifPipeline pipelineWrapper,
            GroupCoalescer groupCoalescer,
            NotifCollection notifCollection,
            ShadeListBuilder listBuilder,
            RenderStageManager renderStageManager,
            NotifCoordinators notifCoordinators,
            NotifInflaterImpl notifInflater,
            DumpManager dumpManager,
            ShadeViewManagerFactory shadeViewManagerFactory
    ) {
        mPipelineWrapper = pipelineWrapper;
        mGroupCoalescer = groupCoalescer;
        mNotifCollection = notifCollection;
        mListBuilder = listBuilder;
        mRenderStageManager = renderStageManager;
        mNotifPluggableCoordinators = notifCoordinators;
        mDumpManager = dumpManager;
        mNotifInflater = notifInflater;
        mShadeViewManagerFactory = shadeViewManagerFactory;
    }

    /** Hooks the new pipeline up to NotificationManager */
    public void initialize(
            NotificationListener notificationService,
            NotificationRowBinderImpl rowBinder,
            NotificationListContainer listContainer,
            NotifStackController stackController) {
        mDumpManager.registerDumpable("NotifPipeline", this);

        mNotificationService = notificationService;

        // Setup inflation
        mNotifInflater.setRowBinder(rowBinder);

        // Wire up coordinators
        mNotifPluggableCoordinators.attach(mPipelineWrapper);

        // Wire up pipeline
        mShadeViewManager = mShadeViewManagerFactory.create(listContainer, stackController);
        mShadeViewManager.attach(mRenderStageManager);
        mRenderStageManager.attach(mListBuilder);
        mListBuilder.attach(mNotifCollection);
        mNotifCollection.attach(mGroupCoalescer);
        mGroupCoalescer.attach(mNotificationService);

        Log.d(TAG, "Notif pipeline initialized."
                + " rendering=" + true);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        dumpPipeline(new PipelineDumper(pw));
    }

    @Override
    public void dumpPipeline(@NonNull PipelineDumper d) {
        d.println("STAGE 0: SETUP");
        d.dump("notifPluggableCoordinators", mNotifPluggableCoordinators);
        d.println("");

        d.println("STAGE 1: LISTEN");
        d.dump("notificationService", mNotificationService);
        d.println("");

        d.println("STAGE 2: BATCH EVENTS");
        d.dump("groupCoalescer", mGroupCoalescer);
        d.println("");

        d.println("STAGE 3: COLLECT");
        d.dump("notifCollection", mNotifCollection);
        d.println("");

        d.println("STAGE 4: BUILD LIST");
        d.dump("listBuilder", mListBuilder);
        d.println("");

        d.println("STAGE 5: DISPATCH RENDER");
        d.dump("renderStageManager", mRenderStageManager);
        d.println("");

        d.println("STAGE 6: UPDATE SHADE");
        d.dump("shadeViewManager", mShadeViewManager);
    }

    private static final String TAG = "NotifPipeline";
}

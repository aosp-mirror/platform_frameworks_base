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

package com.android.systemui.statusbar.notification.collection.coordinator;

import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifListBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles the attachment of the {@link NotifListBuilder} and {@link NotifCollection} to the
 * {@link Coordinator}s, so that the Coordinators can register their respective callbacks.
 */
@Singleton
public class NotifCoordinators implements Dumpable {
    private static final String TAG = "NotifCoordinators";
    private final List<Coordinator> mCoordinators = new ArrayList<>();

    /**
     * Creates all the coordinators.
     */
    @Inject
    public NotifCoordinators(
            FeatureFlags featureFlags,
            KeyguardCoordinator keyguardCoordinator,
            RankingCoordinator rankingCoordinator,
            ForegroundCoordinator foregroundCoordinator,
            DeviceProvisionedCoordinator deviceProvisionedCoordinator,
            PreparationCoordinator preparationCoordinator) {
        mCoordinators.add(keyguardCoordinator);
        mCoordinators.add(rankingCoordinator);
        mCoordinators.add(foregroundCoordinator);
        mCoordinators.add(deviceProvisionedCoordinator);
        if (featureFlags.isNewNotifPipelineRenderingEnabled()) {
            mCoordinators.add(preparationCoordinator);
        }
        // TODO: add new Coordinators here! (b/145134683, b/112656837)
    }

    /**
     * Sends the initialized notifListBuilder and notifCollection to each
     * coordinator to indicate the notifListBuilder is ready to accept {@link Pluggable}s
     * and the notifCollection is ready to accept {@link NotifCollectionListener}s and
     * {@link NotifLifetimeExtender}s.
     */
    public void attach(NotifCollection notifCollection, NotifListBuilder notifListBuilder) {
        for (Coordinator c : mCoordinators) {
            c.attach(notifCollection, notifListBuilder);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(TAG + ":");
        for (Coordinator c : mCoordinators) {
            pw.println("\t" + c.getClass());
        }
    }
}

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

package com.android.internal.app;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.util.FrameworkStatsLog;

/**
 * Standard implementation of ChooserActivityLogger interface.
 * @hide
 */
public class ChooserActivityLoggerImpl implements ChooserActivityLogger {
    private static final int SHARESHEET_INSTANCE_ID_MAX = (1 << 13);

    private UiEventLogger mUiEventLogger = new UiEventLoggerImpl();
    // A small per-notification ID, used for statsd logging.
    private InstanceId mInstanceId;
    private static InstanceIdSequence sInstanceIdSequence;

    @Override
    public void logShareStarted(int eventId, String packageName, String mimeType,
            int appProvidedDirect, int appProvidedApp, boolean isWorkprofile, int previewType,
            String intent) {
        FrameworkStatsLog.write(FrameworkStatsLog.SHARESHEET_STARTED,
                /* event_id = 1 */ SharesheetStartedEvent.SHARE_STARTED.getId(),
                /* package_name = 2 */ packageName,
                /* instance_id = 3 */ getInstanceId().getId(),
                /* mime_type = 4 */ mimeType,
                /* num_app_provided_direct_targets = 5 */ appProvidedDirect,
                /* num_app_provided_app_targets = 6 */ appProvidedApp,
                /* is_workprofile = 7 */ isWorkprofile,
                /* previewType = 8 */ typeFromPreviewInt(previewType),
                /* intentType = 9 */ typeFromIntentString(intent),
                /* num_provided_custom_actions = 10 */ 0,
                /* reselection_action_provided = 11 */ false);
    }

    @Override
    public void logShareTargetSelected(int targetType, String packageName, int positionPicked,
            boolean isPinned) {
        FrameworkStatsLog.write(FrameworkStatsLog.RANKING_SELECTED,
                /* event_id = 1 */ SharesheetTargetSelectedEvent.fromTargetType(targetType).getId(),
                /* package_name = 2 */ packageName,
                /* instance_id = 3 */ getInstanceId().getId(),
                /* position_picked = 4 */ positionPicked,
                /* is_pinned = 5 */ isPinned);
    }

    @Override
    public void log(UiEventLogger.UiEventEnum event, InstanceId instanceId) {
        mUiEventLogger.logWithInstanceId(
                event,
                0,
                null,
                instanceId);
    }

    @Override
    public InstanceId getInstanceId() {
        if (mInstanceId == null) {
            if (sInstanceIdSequence == null) {
                sInstanceIdSequence = new InstanceIdSequence(SHARESHEET_INSTANCE_ID_MAX);
            }
            mInstanceId = sInstanceIdSequence.newInstanceId();
        }
        return mInstanceId;
    }

}

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
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.FrameworkStatsLog;

import java.util.ArrayList;
import java.util.List;

public class ChooserActivityLoggerFake implements ChooserActivityLogger {
    static class CallRecord {
        // shared fields between all logs
        public int atomId;
        public String packageName;
        public InstanceId instanceId;

        // generic log field
        public UiEventLogger.UiEventEnum event;

        // share started fields
        public String mimeType;
        public int appProvidedDirect;
        public int appProvidedApp;
        public boolean isWorkprofile;
        public int previewType;
        public String intent;

        // share completed fields
        public int targetType;
        public int positionPicked;
        public boolean isPinned;

        CallRecord(int atomId, UiEventLogger.UiEventEnum eventId,
                String packageName, InstanceId instanceId) {
            this.atomId = atomId;
            this.packageName = packageName;
            this.instanceId = instanceId;
            this.event = eventId;
        }

        CallRecord(int atomId, String packageName, InstanceId instanceId, String mimeType,
                int appProvidedDirect, int appProvidedApp, boolean isWorkprofile, int previewType,
                String intent) {
            this.atomId = atomId;
            this.packageName = packageName;
            this.instanceId = instanceId;
            this.mimeType = mimeType;
            this.appProvidedDirect = appProvidedDirect;
            this.appProvidedApp = appProvidedApp;
            this.isWorkprofile = isWorkprofile;
            this.previewType = previewType;
            this.intent = intent;
        }

        CallRecord(int atomId, String packageName, InstanceId instanceId, int targetType,
                int positionPicked, boolean isPinned) {
            this.atomId = atomId;
            this.packageName = packageName;
            this.instanceId = instanceId;
            this.targetType = targetType;
            this.positionPicked = positionPicked;
            this.isPinned = isPinned;
        }

    }
    private List<CallRecord> mCalls = new ArrayList<>();

    public int numCalls() {
        return mCalls.size();
    }

    List<CallRecord> getCalls() {
        return mCalls;
    }

    CallRecord get(int index) {
        return mCalls.get(index);
    }

    UiEventLogger.UiEventEnum event(int index) {
        return mCalls.get(index).event;
    }

    public void removeCallsForUiEventsOfType(int uiEventType) {
        mCalls.removeIf(
                call ->
                        (call.atomId == FrameworkStatsLog.UI_EVENT_REPORTED)
                                && (call.event.getId() == uiEventType));
    }

    @Override
    public void logShareStarted(int eventId, String packageName, String mimeType,
            int appProvidedDirect, int appProvidedApp, boolean isWorkprofile, int previewType,
            String intent) {
        mCalls.add(new CallRecord(FrameworkStatsLog.SHARESHEET_STARTED, packageName,
                getInstanceId(), mimeType, appProvidedDirect, appProvidedApp, isWorkprofile,
                previewType, intent));
    }

    @Override
    public void logShareTargetSelected(int targetType, String packageName, int positionPicked,
            boolean isPinned) {
        mCalls.add(new CallRecord(FrameworkStatsLog.RANKING_SELECTED, packageName, getInstanceId(),
                SharesheetTargetSelectedEvent.fromTargetType(targetType).getId(), positionPicked,
                isPinned));
    }

    @Override
    public void log(UiEventLogger.UiEventEnum event, InstanceId instanceId) {
        mCalls.add(new CallRecord(FrameworkStatsLog.UI_EVENT_REPORTED,
                    event, "", instanceId));
    }

    @Override
    public InstanceId getInstanceId() {
        return InstanceId.fakeInstanceId(-1);
    }
}

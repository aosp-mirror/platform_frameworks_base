/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;

import android.content.ComponentName;
import android.os.IBinder;

/**
 * Class needed to expose some {@link ActivityRecord} fields in order to provide
 * {@link android.service.voice.VoiceInteractionSession#onHandleAssist(AssistState)}
 *
 * @hide
 */
public class ActivityAssistInfo {
    private final IBinder mActivityToken;
    private final IBinder mAssistToken;
    private final int mTaskId;
    private final ComponentName mComponentName;

    public ActivityAssistInfo(ActivityRecord activityRecord) {
        this.mActivityToken = activityRecord.token;
        this.mAssistToken = activityRecord.assistToken;
        this.mTaskId = activityRecord.getTask().mTaskId;
        this.mComponentName = activityRecord.mActivityComponent;
    }

    /** @hide */
    public IBinder getActivityToken() {
        return mActivityToken;
    }

    /** @hide */
    public IBinder getAssistToken() {
        return mAssistToken;
    }

    /** @hide */
    public int getTaskId() {
        return mTaskId;
    }

    /** @hide */
    public ComponentName getComponentName() {
        return mComponentName;
    }
}

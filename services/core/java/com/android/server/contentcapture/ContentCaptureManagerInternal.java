/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.contentcapture;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.os.Bundle;
import android.os.IBinder;
import android.service.contentcapture.ActivityEvent.ActivityEventType;

/**
 * ContentCapture Manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class ContentCaptureManagerInternal {

    /**
     * Checks whether the given {@code uid} owns the
     * {@link android.service.contentcapture.ContentCaptureService} implementation associated with
     * the given {@code userId}.
     */
    public abstract boolean isContentCaptureServiceForUser(int uid, @UserIdInt int userId);

    /**
     * Notifies the intelligence service of new assist data for the given activity.
     *
     * @return {@code false} if there was no service set for the given user
     */
    public abstract boolean sendActivityAssistData(@UserIdInt int userId,
            @NonNull IBinder activityToken, @NonNull Bundle data);

    /**
     * Gets the content capture options for the given user and package, or {@code null} if the
     * package is not whitelisted by the service.
     *
     * <p><b>NOTE: </b>this method is called by the {@code ActivityManager} service and hence cannot
     * hold the main service lock.
     */
    @Nullable
    public abstract ContentCaptureOptions getOptionsForPackage(@UserIdInt int userId,
            @NonNull String packageName);

    /**
     * Notifies the intelligence service of a high-level activity event for the given user.
     */
    public abstract void notifyActivityEvent(@UserIdInt int userId,
            @NonNull ComponentName activityComponent, @ActivityEventType int eventType);
}

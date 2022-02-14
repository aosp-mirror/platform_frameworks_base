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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Callback to intercept activity starts and possibly block/redirect them.
 */
public abstract class ActivityInterceptorCallback {
    /**
     * Intercept the launch intent based on various signals. If an interception happened, returns
     * a new/existing non-null {@link Intent} which may redirect to another activity.
     *
     * @return null if no interception occurred, or a non-null intent which replaces the
     * existing intent.
     */
    public abstract @Nullable Intent intercept(ActivityInterceptorInfo info);

    /**
     * The unique id of each interceptor which determines the order it will execute in.
     */
    @IntDef(suffix = { "_ORDERED_ID" }, value = {
            FIRST_ORDERED_ID,
            LAST_ORDERED_ID // Update this when adding new ids
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OrderedId {}

    /**
     * The first id, used by the framework to determine the valid range of ids.
     */
    static final int FIRST_ORDERED_ID = 0;

    /**
     * The final id, used by the framework to determine the valid range of ids. Update this when
     * adding new ids.
     */
    static final int LAST_ORDERED_ID = FIRST_ORDERED_ID;

    /**
     * Data class for storing the various arguments needed for activity interception.
     */
    public static final class ActivityInterceptorInfo {
        public final int realCallingUid;
        public final int realCallingPid;
        public final int userId;
        public final String callingPackage;
        public final String callingFeatureId;
        public final Intent intent;
        public final ResolveInfo rInfo;
        public final ActivityInfo aInfo;
        public final String resolvedType;
        public final int callingPid;
        public final int callingUid;
        public final ActivityOptions checkedOptions;

        public ActivityInterceptorInfo(int realCallingUid, int realCallingPid, int userId,
                String callingPackage, String callingFeatureId, Intent intent,
                ResolveInfo rInfo, ActivityInfo aInfo, String resolvedType, int callingPid,
                int callingUid, ActivityOptions checkedOptions) {
            this.realCallingUid = realCallingUid;
            this.realCallingPid = realCallingPid;
            this.userId = userId;
            this.callingPackage = callingPackage;
            this.callingFeatureId = callingFeatureId;
            this.intent = intent;
            this.rInfo = rInfo;
            this.aInfo = aInfo;
            this.resolvedType = resolvedType;
            this.callingPid = callingPid;
            this.callingUid = callingUid;
            this.checkedOptions = checkedOptions;
        }
    }
}

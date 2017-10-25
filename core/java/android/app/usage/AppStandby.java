/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app.usage;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Set of constants for app standby buckets and reasons. Apps will be moved into different buckets
 * that affect how frequently they can run in the background or perform other battery-consuming
 * actions. Buckets will be assigned based on how frequently or when the system thinks the user
 * is likely to use the app.
 * @hide
 */
public class AppStandby {

    /** The app was used very recently, currently in use or likely to be used very soon. */
    public static final int STANDBY_BUCKET_ACTIVE = 0;

    // Leave some gap in case we want to increase the number of buckets

    /** The app was used recently and/or likely to be used in the next few hours  */
    public static final int STANDBY_BUCKET_WORKING_SET = 3;

    // Leave some gap in case we want to increase the number of buckets

    /** The app was used in the last few days and/or likely to be used in the next few days */
    public static final int STANDBY_BUCKET_FREQUENT = 6;

    // Leave some gap in case we want to increase the number of buckets

    /** The app has not be used for several days and/or is unlikely to be used for several days */
    public static final int STANDBY_BUCKET_RARE = 9;

    // Leave some gap in case we want to increase the number of buckets

    /** The app has never been used. */
    public static final int STANDBY_BUCKET_NEVER = 12;

    /** Reason for bucketing -- default initial state */
    public static final String REASON_DEFAULT = "default";

    /** Reason for bucketing -- timeout */
    public static final String REASON_TIMEOUT = "timeout";

    /** Reason for bucketing -- usage */
    public static final String REASON_USAGE = "usage";

    /** Reason for bucketing -- forced by user / shell command */
    public static final String REASON_FORCED = "forced";

    /**
     * Reason for bucketing -- predicted. This is a prefix and the UID of the bucketeer will
     * be appended.
     */
    public static final String REASON_PREDICTED = "predicted";

    @IntDef(flag = false, value = {
            STANDBY_BUCKET_ACTIVE,
            STANDBY_BUCKET_WORKING_SET,
            STANDBY_BUCKET_FREQUENT,
            STANDBY_BUCKET_RARE,
            STANDBY_BUCKET_NEVER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StandbyBuckets {}
}

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

package android.app.usage;

import static junit.framework.Assert.fail;

import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ArrayUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

/**
 * These tests verify that all fields defined in {@link UsageStats} and {@link UsageEvents.Event}
 * are all known fields. This ensures that newly added fields or refactorings are accounted for in
 * the usagestatsservice.proto and usagestatsservice_v2.proto files.
 *
 * Note: verification for {@link com.android.server.usage.IntervalStats} fields is located in
 * {@link com.android.server.usage.IntervalStatsTests}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class UsageStatsPersistenceTest {

    // All fields in this list are defined in UsageStats and persisted - please ensure they're
    // defined correctly in both usagestatsservice.proto and usagestatsservice_v2.proto
    private static final String[] USAGESTATS_PERSISTED_FIELDS = {"mBeginTimeStamp", "mEndTimeStamp",
            "mPackageName", "mPackageToken", "mLastEvent", "mAppLaunchCount", "mChooserCounts",
            "mLastTimeUsed", "mTotalTimeInForeground", "mLastTimeForegroundServiceUsed",
            "mTotalTimeForegroundServiceUsed", "mLastTimeVisible", "mTotalTimeVisible",
            "mLastTimeComponentUsed"};
    // All fields in this list are defined in UsageStats but not persisted
    private static final String[] USAGESTATS_IGNORED_FIELDS = {"CREATOR", "mActivities",
            "mForegroundServices", "mLaunchCount", "mChooserCountsObfuscated"};

    @Test
    public void testUsageStatsFields() {
        final UsageStats stats = new UsageStats();
        final Field[] fields = stats.getClass().getDeclaredFields();
        for (Field field : fields) {
            if (!(ArrayUtils.contains(USAGESTATS_PERSISTED_FIELDS, field.getName())
                    || ArrayUtils.contains(USAGESTATS_IGNORED_FIELDS, field.getName()))) {
                fail("Found an unknown field: " + field.getName() + ". Please correctly update "
                        + "either USAGESTATS_PERSISTED_FIELDS or USAGESTATS_IGNORED_FIELDS.");
            }
        }
    }

    // All fields in this list are defined in UsageEvents.Event and persisted - please ensure
    // they're defined correctly in both usagestatsservice.proto and usagestatsservice_v2.proto
    private static final String[] USAGEEVENTS_PERSISTED_FIELDS = {"mPackage", "mPackageToken",
            "mClass", "mClassToken", "mTimeStamp", "mFlags", "mEventType", "mConfiguration",
            "mShortcutId", "mShortcutIdToken", "mBucketAndReason", "mInstanceId",
            "mNotificationChannelId", "mNotificationChannelIdToken", "mTaskRootPackage",
            "mTaskRootPackageToken", "mTaskRootClass", "mTaskRootClassToken", "mLocusId",
            "mLocusIdToken"};
    // All fields in this list are defined in UsageEvents.Event but not persisted
    private static final String[] USAGEEVENTS_IGNORED_FIELDS = {"mAction", "mContentAnnotations",
            "mContentType", "DEVICE_EVENT_PACKAGE_NAME", "FLAG_IS_PACKAGE_INSTANT_APP",
            "VALID_FLAG_BITS", "UNASSIGNED_TOKEN", "MAX_EVENT_TYPE"};
    // All fields in this list are final constants defining event types and not persisted
    private static final String[] EVENT_TYPES = {"NONE", "ACTIVITY_DESTROYED", "ACTIVITY_PAUSED",
            "ACTIVITY_RESUMED", "ACTIVITY_STOPPED", "APP_COMPONENT_USED", "CHOOSER_ACTION",
            "CONFIGURATION_CHANGE", "CONTINUE_PREVIOUS_DAY", "CONTINUING_FOREGROUND_SERVICE",
            "DEVICE_SHUTDOWN", "DEVICE_STARTUP", "END_OF_DAY", "FLUSH_TO_DISK",
            "FOREGROUND_SERVICE_START", "FOREGROUND_SERVICE_STOP", "KEYGUARD_HIDDEN",
            "KEYGUARD_SHOWN", "LOCUS_ID_SET", "MOVE_TO_BACKGROUND", "MOVE_TO_FOREGROUND",
            "NOTIFICATION_INTERRUPTION", "NOTIFICATION_SEEN", "ROLLOVER_FOREGROUND_SERVICE",
            "SCREEN_INTERACTIVE", "SCREEN_NON_INTERACTIVE", "SHORTCUT_INVOCATION", "SLICE_PINNED",
            "SLICE_PINNED_PRIV", "STANDBY_BUCKET_CHANGED", "SYSTEM_INTERACTION", "USER_INTERACTION",
            "USER_STOPPED", "USER_UNLOCKED"};

    @Test
    public void testUsageEventsFields() {
        final UsageEvents.Event event = new UsageEvents.Event();
        final Field[] fields = event.getClass().getDeclaredFields();
        for (Field field : fields) {
            final String name = field.getName();
            if (!(ArrayUtils.contains(USAGEEVENTS_PERSISTED_FIELDS, name)
                    || ArrayUtils.contains(USAGEEVENTS_IGNORED_FIELDS, name)
                    || ArrayUtils.contains(EVENT_TYPES, name))) {
                fail("Found an unknown field: " + name + ". Please correctly update either "
                        + "USAGEEVENTS_PERSISTED_FIELDS or USAGEEVENTS_IGNORED_FIELDS. If this "
                        + "field is a new event type, please update EVENT_TYPES instead.");
            }
        }
    }
}

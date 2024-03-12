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
package com.android.server.usage;

import static android.app.usage.UsageEvents.Event.MAX_EVENT_TYPE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import android.app.usage.Flags;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.res.Configuration;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ArrayUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IntervalStatsTests {
    private static final int NUMBER_OF_PACKAGES = 7;
    private static final int NUMBER_OF_EVENTS_PER_PACKAGE = 200;
    private static final int NUMBER_OF_EVENTS = NUMBER_OF_PACKAGES * NUMBER_OF_EVENTS_PER_PACKAGE;

    private long mEndTime = 0;

    private void populateIntervalStats(IntervalStats intervalStats) {
        final int timeProgression = 23;
        long time = System.currentTimeMillis() - (NUMBER_OF_EVENTS * timeProgression);

        intervalStats.majorVersion = 7;
        intervalStats.minorVersion = 8;
        intervalStats.beginTime = time;
        intervalStats.interactiveTracker.count = 2;
        intervalStats.interactiveTracker.duration = 111111;
        intervalStats.nonInteractiveTracker.count = 3;
        intervalStats.nonInteractiveTracker.duration = 222222;
        intervalStats.keyguardShownTracker.count = 4;
        intervalStats.keyguardShownTracker.duration = 333333;
        intervalStats.keyguardHiddenTracker.count = 5;
        intervalStats.keyguardHiddenTracker.duration = 4444444;

        for (int i = 0; i < NUMBER_OF_EVENTS; i++) {
            UsageEvents.Event event = new UsageEvents.Event();
            final int packageInt = ((i / 3) % NUMBER_OF_PACKAGES); // clusters of 3 events
            event.mPackage = "fake.package.name" + packageInt;
            if (packageInt == 3) {
                // Third app is an instant app
                event.mFlags |= UsageEvents.Event.FLAG_IS_PACKAGE_INSTANT_APP;
            }

            final int instanceId = i % 11;
            event.mClass = ".fake.class.name" + instanceId;
            event.mTimeStamp = time;
            event.mEventType = i % (MAX_EVENT_TYPE + 1); //"random" event type
            event.mInstanceId = instanceId;


            final int rootPackageInt = (i % 5); // 5 "apps" start each task
            event.mTaskRootPackage = "fake.package.name" + rootPackageInt;

            final int rootClassInt = i % 6;
            event.mTaskRootClass = ".fake.class.name" + rootClassInt;

            switch (event.mEventType) {
                case UsageEvents.Event.CONFIGURATION_CHANGE:
                    event.mConfiguration = new Configuration(); //empty config
                    break;
                case UsageEvents.Event.SHORTCUT_INVOCATION:
                    event.mShortcutId = "shortcut" + (i % 8); //"random" shortcut
                    break;
                case UsageEvents.Event.STANDBY_BUCKET_CHANGED:
                    //"random" bucket and reason
                    event.mBucketAndReason = (((i % 5 + 1) * 10) << 16) & (i % 5 + 1) << 8;
                    break;
                case UsageEvents.Event.NOTIFICATION_INTERRUPTION:
                    event.mNotificationChannelId = "channel" + (i % 5); //"random" channel
                    break;
                case UsageEvents.Event.LOCUS_ID_SET:
                    event.mLocusId = "locus" + (i % 7); //"random" locus
                    break;
                case UsageEvents.Event.USER_INTERACTION:
                    if (Flags.userInteractionTypeApi()) {
                        // "random" user interaction extras.
                        PersistableBundle extras = new PersistableBundle();
                        extras.putString(UsageStatsManager.EXTRA_EVENT_CATEGORY,
                                "fake.namespace.category" + (i % 13));
                        extras.putString(UsageStatsManager.EXTRA_EVENT_ACTION,
                                "fakeaction" + (i % 13));
                        event.mExtras = extras;
                    }
                    break;
            }

            intervalStats.addEvent(event);
            intervalStats.update(event.mPackage, event.mClass, event.mTimeStamp, event.mEventType,
                    event.mInstanceId);

            time += timeProgression; // Arbitrary progression of time
        }
        mEndTime = time;

        final Configuration config1 = new Configuration();
        config1.fontScale = 3.3f;
        config1.mcc = 4;
        intervalStats.getOrCreateConfigurationStats(config1);

        final Configuration config2 = new Configuration();
        config2.mnc = 5;
        config2.setLocale(new Locale("en", "US"));
        intervalStats.getOrCreateConfigurationStats(config2);

        intervalStats.activeConfiguration = config2;
    }

    @Test
    public void testObfuscation() {
        final IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats);

        final PackagesTokenData packagesTokenData = new PackagesTokenData();
        intervalStats.obfuscateData(packagesTokenData);

        // data is populated with 7 different "apps"
        assertEquals(packagesTokenData.tokensToPackagesMap.size(), NUMBER_OF_PACKAGES);
        assertEquals(packagesTokenData.packagesToTokensMap.size(), NUMBER_OF_PACKAGES);
        assertEquals(packagesTokenData.counter, NUMBER_OF_PACKAGES + 1);

        assertEquals(intervalStats.events.size(), NUMBER_OF_EVENTS);
        assertEquals(intervalStats.packageStats.size(), NUMBER_OF_PACKAGES);
    }

    @Test
    public void testDeobfuscation() {
        final IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats);

        final PackagesTokenData packagesTokenData = new PackagesTokenData();
        intervalStats.obfuscateData(packagesTokenData);
        intervalStats.deobfuscateData(packagesTokenData);

        // ensure deobfuscation doesn't update any of the mappings data
        assertEquals(packagesTokenData.tokensToPackagesMap.size(), NUMBER_OF_PACKAGES);
        assertEquals(packagesTokenData.packagesToTokensMap.size(), NUMBER_OF_PACKAGES);
        assertEquals(packagesTokenData.counter, NUMBER_OF_PACKAGES + 1);

        // ensure deobfuscation didn't remove any events or usage stats
        assertEquals(intervalStats.events.size(), NUMBER_OF_EVENTS);
        assertEquals(intervalStats.packageStats.size(), NUMBER_OF_PACKAGES);
    }

    @Test
    public void testBadDataOnDeobfuscation() {
        final IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats);

        final PackagesTokenData packagesTokenData = new PackagesTokenData();
        intervalStats.obfuscateData(packagesTokenData);
        intervalStats.packageStats.clear();

        // remove the mapping for token 2
        packagesTokenData.tokensToPackagesMap.remove(2);

        intervalStats.deobfuscateData(packagesTokenData);
        // deobfuscation should have removed all events mapped to package token 2
        assertEquals(intervalStats.events.size(),
                NUMBER_OF_EVENTS - NUMBER_OF_EVENTS_PER_PACKAGE - 1);
        assertEquals(intervalStats.packageStats.size(), NUMBER_OF_PACKAGES - 1);
    }

    @Test
    public void testBadPackageDataOnDeobfuscation() {
        final IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats);

        final PackagesTokenData packagesTokenData = new PackagesTokenData();
        intervalStats.obfuscateData(packagesTokenData);
        intervalStats.packageStats.clear();

        // remove mapping number 2 within package 3 (random)
        packagesTokenData.tokensToPackagesMap.valueAt(3).remove(2);

        intervalStats.deobfuscateData(packagesTokenData);
        // deobfuscation should not have removed all events for a package - however, it's possible
        // that some events were removed because of how shortcut and notification events are handled
        assertTrue(intervalStats.events.size() > NUMBER_OF_EVENTS - NUMBER_OF_EVENTS_PER_PACKAGE);
        assertEquals(intervalStats.packageStats.size(), NUMBER_OF_PACKAGES);
    }

    // All fields in this list are defined in IntervalStats and persisted - please ensure they're
    // defined correctly in both usagestatsservice.proto and usagestatsservice_v2.proto
    private static final String[] INTERVALSTATS_PERSISTED_FIELDS = {"beginTime", "endTime",
            "mStringCache", "majorVersion", "minorVersion", "interactiveTracker",
            "nonInteractiveTracker", "keyguardShownTracker", "keyguardHiddenTracker",
            "packageStats", "configurations", "activeConfiguration", "events"};
    // All fields in this list are defined in IntervalStats but not persisted
    private static final String[] INTERVALSTATS_IGNORED_FIELDS = {"lastTimeSaved",
            "packageStatsObfuscated", "CURRENT_MAJOR_VERSION", "CURRENT_MINOR_VERSION", "TAG"};

    @Test
    public void testIntervalStatsFieldsAreKnown() {
        final IntervalStats stats = new IntervalStats();
        final Field[] fields = stats.getClass().getDeclaredFields();
        for (Field field : fields) {
            if (!(ArrayUtils.contains(INTERVALSTATS_PERSISTED_FIELDS, field.getName())
                    || ArrayUtils.contains(INTERVALSTATS_IGNORED_FIELDS, field.getName()))) {
                fail("Found an unknown field: " + field.getName() + ". Please correctly update "
                        + "either INTERVALSTATS_PERSISTED_FIELDS or INTERVALSTATS_IGNORED_FIELDS.");
            }
        }
    }
}

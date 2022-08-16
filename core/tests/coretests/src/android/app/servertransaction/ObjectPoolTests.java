/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.app.servertransaction.TestUtils.config;
import static android.app.servertransaction.TestUtils.mergedConfig;
import static android.app.servertransaction.TestUtils.referrerIntentList;
import static android.app.servertransaction.TestUtils.resultInfoList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.app.ActivityOptions;
import android.app.servertransaction.TestUtils.LaunchActivityItemBuilder;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Supplier;

/**
 * Tests for {@link ObjectPool}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:ObjectPoolTests
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ObjectPoolTests {

    // 1. Check if two obtained objects from pool are not the same.
    // 2. Check if the state of the object is cleared after recycling.
    // 3. Check if the same object is obtained from pool after recycling.

    @Test
    public void testRecycleActivityConfigurationChangeItem() {
        ActivityConfigurationChangeItem emptyItem =
                ActivityConfigurationChangeItem.obtain(Configuration.EMPTY);
        ActivityConfigurationChangeItem item = ActivityConfigurationChangeItem.obtain(config());
        assertNotSame(item, emptyItem);
        assertFalse(item.equals(emptyItem));

        item.recycle();
        assertEquals(item, emptyItem);

        ActivityConfigurationChangeItem item2 = ActivityConfigurationChangeItem.obtain(config());
        assertSame(item, item2);
        assertFalse(item2.equals(emptyItem));
    }

    @Test
    public void testRecycleActivityResultItem() {
        ActivityResultItem emptyItem = ActivityResultItem.obtain(null);
        ActivityResultItem item = ActivityResultItem.obtain(resultInfoList());
        assertNotSame(item, emptyItem);
        assertFalse(item.equals(emptyItem));

        item.recycle();
        assertEquals(item, emptyItem);

        ActivityResultItem item2 = ActivityResultItem.obtain(resultInfoList());
        assertSame(item, item2);
        assertFalse(item2.equals(emptyItem));
    }

    @Test
    public void testRecycleConfigurationChangeItem() {
        ConfigurationChangeItem emptyItem = ConfigurationChangeItem.obtain(null);
        ConfigurationChangeItem item = ConfigurationChangeItem.obtain(config());
        assertNotSame(item, emptyItem);
        assertFalse(item.equals(emptyItem));

        item.recycle();
        assertEquals(item, emptyItem);

        ConfigurationChangeItem item2 = ConfigurationChangeItem.obtain(config());
        assertSame(item, item2);
        assertFalse(item2.equals(emptyItem));
    }

    @Test
    public void testRecycleDestroyActivityItem() {
        DestroyActivityItem emptyItem = DestroyActivityItem.obtain(false, 0);
        DestroyActivityItem item = DestroyActivityItem.obtain(true, 117);
        assertNotSame(item, emptyItem);
        assertFalse(item.equals(emptyItem));

        item.recycle();
        assertEquals(item, emptyItem);

        DestroyActivityItem item2 = DestroyActivityItem.obtain(true, 14);
        assertSame(item, item2);
        assertFalse(item2.equals(emptyItem));
    }

    @Test
    public void testRecycleLaunchActivityItem() {
        Intent intent = new Intent("action");
        int ident = 57;
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.flags = 42;
        activityInfo.setMaxAspectRatio(2.4f);
        activityInfo.launchToken = "token";
        activityInfo.applicationInfo = new ApplicationInfo();
        activityInfo.packageName = "packageName";
        activityInfo.name = "name";
        Configuration overrideConfig = new Configuration();
        overrideConfig.assetsSeq = 5;
        CompatibilityInfo compat = CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
        String referrer = "referrer";
        int procState = 4;
        Bundle bundle = new Bundle();
        bundle.putString("key", "value");
        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putInt("k", 4);
        IBinder assistToken = new Binder();
        IBinder shareableActivityToken = new Binder();

        Supplier<LaunchActivityItem> itemSupplier = () -> new LaunchActivityItemBuilder()
                .setIntent(intent).setIdent(ident).setInfo(activityInfo).setCurConfig(config())
                .setOverrideConfig(overrideConfig).setCompatInfo(compat).setReferrer(referrer)
                .setProcState(procState).setState(bundle).setPersistentState(persistableBundle)
                .setPendingResults(resultInfoList()).setPendingNewIntents(referrerIntentList())
                .setIsForward(true).setAssistToken(assistToken)
                .setShareableActivityToken(shareableActivityToken)
                .setTaskFragmentToken(new Binder()).build();

        LaunchActivityItem emptyItem = new LaunchActivityItemBuilder().build();
        LaunchActivityItem item = itemSupplier.get();
        assertNotSame(item, emptyItem);
        assertFalse(item.equals(emptyItem));

        item.recycle();
        assertEquals(item, emptyItem);

        LaunchActivityItem item2 = itemSupplier.get();
        assertSame(item, item2);
        assertFalse(item2.equals(emptyItem));
    }

    @Test
    public void testRecycleActivityRelaunchItem() {
        ActivityRelaunchItem emptyItem = ActivityRelaunchItem.obtain(null, null, 0, null, false);
        Configuration overrideConfig = new Configuration();
        overrideConfig.assetsSeq = 5;
        ActivityRelaunchItem item = ActivityRelaunchItem.obtain(resultInfoList(),
                referrerIntentList(), 42, mergedConfig(), true);
        assertNotSame(item, emptyItem);
        assertFalse(item.equals(emptyItem));

        item.recycle();
        assertEquals(item, emptyItem);

        ActivityRelaunchItem item2 = ActivityRelaunchItem.obtain(resultInfoList(),
                referrerIntentList(), 42, mergedConfig(), true);
        assertSame(item, item2);
        assertFalse(item2.equals(emptyItem));
    }

    @Test
    public void testRecycleMoveToDisplayItem() {
        MoveToDisplayItem emptyItem = MoveToDisplayItem.obtain(0, Configuration.EMPTY);
        MoveToDisplayItem item = MoveToDisplayItem.obtain(4, config());
        assertNotSame(item, emptyItem);
        assertFalse(item.equals(emptyItem));

        item.recycle();
        assertEquals(item, emptyItem);

        MoveToDisplayItem item2 = MoveToDisplayItem.obtain(3, config());
        assertSame(item, item2);
        assertFalse(item2.equals(emptyItem));
    }

    @Test
    public void testRecycleNewIntentItem() {
        NewIntentItem emptyItem = NewIntentItem.obtain(null, false);
        NewIntentItem item = NewIntentItem.obtain(referrerIntentList(), false);
        assertNotSame(item, emptyItem);
        assertFalse(item.equals(emptyItem));

        item.recycle();
        assertEquals(item, emptyItem);

        NewIntentItem item2 = NewIntentItem.obtain(referrerIntentList(), false);
        assertSame(item, item2);
        assertFalse(item2.equals(emptyItem));
    }

    @Test
    public void testRecyclePauseActivityItemItem() {
        PauseActivityItem emptyItem = PauseActivityItem.obtain(false, false, 0, false);
        PauseActivityItem item = PauseActivityItem.obtain(true, true, 5, true);
        assertNotSame(item, emptyItem);
        assertFalse(item.equals(emptyItem));

        item.recycle();
        assertEquals(item, emptyItem);

        PauseActivityItem item2 = PauseActivityItem.obtain(true, false, 5, true);
        assertSame(item, item2);
        assertFalse(item2.equals(emptyItem));
    }

    @Test
    public void testRecycleResumeActivityItem() {
        ResumeActivityItem emptyItem = ResumeActivityItem.obtain(false);
        ResumeActivityItem item = ResumeActivityItem.obtain(3, true);
        assertNotSame(item, emptyItem);
        assertFalse(item.equals(emptyItem));

        item.recycle();
        assertEquals(item, emptyItem);

        ResumeActivityItem item2 = ResumeActivityItem.obtain(2, true);
        assertSame(item, item2);
        assertFalse(item2.equals(emptyItem));
    }

    @Test
    public void testRecycleStartActivityItem() {
        StartActivityItem emptyItem = StartActivityItem.obtain(null /* activityOptions */);
        StartActivityItem item = StartActivityItem.obtain(ActivityOptions.makeBasic());
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        StartActivityItem item2 = StartActivityItem.obtain(
                ActivityOptions.makeBasic().setLaunchDisplayId(10));
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecycleStopItem() {
        StopActivityItem emptyItem = StopActivityItem.obtain(0);
        StopActivityItem item = StopActivityItem.obtain(4);
        assertNotSame(item, emptyItem);
        assertFalse(item.equals(emptyItem));

        item.recycle();
        assertEquals(item, emptyItem);

        StopActivityItem item2 = StopActivityItem.obtain(3);
        assertSame(item, item2);
        assertFalse(item2.equals(emptyItem));
    }

    @Test
    public void testRecycleClientTransaction() {
        ClientTransaction emptyItem = ClientTransaction.obtain(null, null);
        ClientTransaction item = ClientTransaction.obtain(null, new Binder());
        assertNotSame(item, emptyItem);
        assertFalse(item.equals(emptyItem));

        item.recycle();
        assertEquals(item, emptyItem);

        ClientTransaction item2 = ClientTransaction.obtain(null, new Binder());
        assertSame(item, item2);
        assertFalse(item2.equals(emptyItem));
    }
}

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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.app.servertransaction.TestUtils.LaunchActivityItemBuilder;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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

    @Mock
    private IApplicationThread mApplicationThread;
    @Mock
    private IBinder mActivityToken;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    // 1. Check if two obtained objects from pool are not the same.
    // 2. Check if the state of the object is cleared after recycling.
    // 3. Check if the same object is obtained from pool after recycling.

    @Test
    public void testRecycleActivityConfigurationChangeItem() {
        ActivityConfigurationChangeItem emptyItem = ActivityConfigurationChangeItem.obtain(
                null /* activityToken */, Configuration.EMPTY);
        ActivityConfigurationChangeItem item = ActivityConfigurationChangeItem.obtain(
                mActivityToken, config());
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        ActivityConfigurationChangeItem item2 = ActivityConfigurationChangeItem.obtain(
                mActivityToken, config());
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecycleActivityResultItem() {
        ActivityResultItem emptyItem = ActivityResultItem.obtain(
                null /* activityToken */, null /* resultInfoList */);
        ActivityResultItem item = ActivityResultItem.obtain(mActivityToken, resultInfoList());
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        ActivityResultItem item2 = ActivityResultItem.obtain(mActivityToken, resultInfoList());
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecycleConfigurationChangeItem() {
        ConfigurationChangeItem emptyItem = ConfigurationChangeItem.obtain(null, 0);
        ConfigurationChangeItem item = ConfigurationChangeItem.obtain(config(), 1);
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        ConfigurationChangeItem item2 = ConfigurationChangeItem.obtain(config(), 1);
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecycleDestroyActivityItem() {
        DestroyActivityItem emptyItem = DestroyActivityItem.obtain(
                null /* activityToken */, false, 0);
        DestroyActivityItem item = DestroyActivityItem.obtain(mActivityToken, true, 117);
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        DestroyActivityItem item2 = DestroyActivityItem.obtain(mActivityToken, true, 14);
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecycleLaunchActivityItem() {
        final IBinder activityToken = new Binder();
        final Intent intent = new Intent("action");
        int ident = 57;
        final ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.flags = 42;
        activityInfo.setMaxAspectRatio(2.4f);
        activityInfo.launchToken = "token";
        activityInfo.applicationInfo = new ApplicationInfo();
        activityInfo.packageName = "packageName";
        activityInfo.name = "name";
        final Configuration overrideConfig = new Configuration();
        overrideConfig.assetsSeq = 5;
        final String referrer = "referrer";
        int procState = 4;
        final Bundle bundle = new Bundle();
        bundle.putString("key", "value");
        final PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putInt("k", 4);
        final IBinder assistToken = new Binder();
        final IBinder shareableActivityToken = new Binder();
        int deviceId = 3;

        final Supplier<LaunchActivityItem> itemSupplier = () -> new LaunchActivityItemBuilder()
                .setActivityToken(activityToken)
                .setIntent(intent)
                .setIdent(ident)
                .setInfo(activityInfo)
                .setCurConfig(config())
                .setOverrideConfig(overrideConfig)
                .setReferrer(referrer)
                .setProcState(procState)
                .setState(bundle)
                .setPersistentState(persistableBundle)
                .setPendingResults(resultInfoList())
                .setPendingNewIntents(referrerIntentList())
                .setIsForward(true)
                .setAssistToken(assistToken)
                .setShareableActivityToken(shareableActivityToken)
                .setTaskFragmentToken(new Binder())
                .setDeviceId(deviceId)
                .build();

        LaunchActivityItem emptyItem = new LaunchActivityItemBuilder().build();
        LaunchActivityItem item = itemSupplier.get();
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        LaunchActivityItem item2 = itemSupplier.get();
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecycleActivityRelaunchItem() {
        ActivityRelaunchItem emptyItem = ActivityRelaunchItem.obtain(
                null /* activityToken */, null, null, 0, null, false);
        Configuration overrideConfig = new Configuration();
        overrideConfig.assetsSeq = 5;
        ActivityRelaunchItem item = ActivityRelaunchItem.obtain(mActivityToken, resultInfoList(),
                referrerIntentList(), 42, mergedConfig(), true);
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        ActivityRelaunchItem item2 = ActivityRelaunchItem.obtain(mActivityToken, resultInfoList(),
                referrerIntentList(), 42, mergedConfig(), true);
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecycleMoveToDisplayItem() {
        MoveToDisplayItem emptyItem = MoveToDisplayItem.obtain(
                null /* activityToken */, 0, Configuration.EMPTY);
        MoveToDisplayItem item = MoveToDisplayItem.obtain(mActivityToken, 4, config());
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        MoveToDisplayItem item2 = MoveToDisplayItem.obtain(mActivityToken, 3, config());
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecycleNewIntentItem() {
        NewIntentItem emptyItem = NewIntentItem.obtain(
                null /* activityToken */, null /* intents */, false /* resume */);
        NewIntentItem item = NewIntentItem.obtain(mActivityToken, referrerIntentList(), false);
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        NewIntentItem item2 = NewIntentItem.obtain(mActivityToken, referrerIntentList(), false);
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecyclePauseActivityItemItem() {
        PauseActivityItem emptyItem = PauseActivityItem.obtain(
                null /* activityToken */, false, false, 0, false, false);
        PauseActivityItem item = PauseActivityItem.obtain(
                mActivityToken, true, true, 5, true, true);
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        PauseActivityItem item2 = PauseActivityItem.obtain(
                mActivityToken, true, false, 5, true, true);
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecycleResumeActivityItem() {
        ResumeActivityItem emptyItem = ResumeActivityItem.obtain(
                null /* activityToken */, false, false);
        ResumeActivityItem item = ResumeActivityItem.obtain(mActivityToken, 3, true, false);
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        ResumeActivityItem item2 = ResumeActivityItem.obtain(mActivityToken, 2, true, false);
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecycleStartActivityItem() {
        StartActivityItem emptyItem = StartActivityItem.obtain(
                null /* activityToken */, null /* activityOptions */);
        StartActivityItem item = StartActivityItem.obtain(mActivityToken,
                ActivityOptions.makeBasic());
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        StartActivityItem item2 = StartActivityItem.obtain(mActivityToken,
                ActivityOptions.makeBasic().setLaunchDisplayId(10));
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecycleStopItem() {
        StopActivityItem emptyItem = StopActivityItem.obtain(null /* activityToken */, 0);
        StopActivityItem item = StopActivityItem.obtain(mActivityToken, 4);
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        StopActivityItem item2 = StopActivityItem.obtain(mActivityToken, 3);
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }

    @Test
    public void testRecycleClientTransaction() {
        ClientTransaction emptyItem = ClientTransaction.obtain(null);
        ClientTransaction item = ClientTransaction.obtain(mApplicationThread);
        assertNotSame(item, emptyItem);
        assertNotEquals(item, emptyItem);

        item.recycle();
        assertEquals(item, emptyItem);

        ClientTransaction item2 = ClientTransaction.obtain(mApplicationThread);
        assertSame(item, item2);
        assertNotEquals(item2, emptyItem);
    }
}

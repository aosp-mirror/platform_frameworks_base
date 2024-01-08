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

import android.annotation.NonNull;
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
        testRecycle(() -> ActivityConfigurationChangeItem.obtain(mActivityToken, config()));
    }

    @Test
    public void testRecycleActivityResultItem() {
        testRecycle(() -> ActivityResultItem.obtain(mActivityToken, resultInfoList()));
    }

    @Test
    public void testRecycleConfigurationChangeItem() {
        testRecycle(() -> ConfigurationChangeItem.obtain(config(), 1));
    }

    @Test
    public void testRecycleDestroyActivityItem() {
        testRecycle(() -> DestroyActivityItem.obtain(mActivityToken, true, 117));
    }

    @Test
    public void testRecycleLaunchActivityItem() {
        final IBinder activityToken = new Binder();
        final Intent intent = new Intent("action");
        final int ident = 57;
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
        final int procState = 4;
        final Bundle bundle = new Bundle();
        bundle.putString("key", "value");
        final PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putInt("k", 4);
        final IBinder assistToken = new Binder();
        final IBinder shareableActivityToken = new Binder();
        final int deviceId = 3;
        final IBinder taskFragmentToken = new Binder();

        testRecycle(() -> new LaunchActivityItemBuilder(
                activityToken, intent, activityInfo)
                .setIdent(ident)
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
                .setTaskFragmentToken(taskFragmentToken)
                .setDeviceId(deviceId)
                .build());
    }

    @Test
    public void testRecycleActivityRelaunchItem() {
        testRecycle(() -> ActivityRelaunchItem.obtain(mActivityToken,
                resultInfoList(), referrerIntentList(), 42, mergedConfig(), true));
    }

    @Test
    public void testRecycleMoveToDisplayItem() {
        testRecycle(() -> MoveToDisplayItem.obtain(mActivityToken, 4, config()));
    }

    @Test
    public void testRecycleNewIntentItem() {
        testRecycle(() -> NewIntentItem.obtain(mActivityToken, referrerIntentList(), false));
    }

    @Test
    public void testRecyclePauseActivityItemItem() {
        testRecycle(() -> PauseActivityItem.obtain(mActivityToken, true, true, 5, true, true));
    }

    @Test
    public void testRecycleResumeActivityItem() {
        testRecycle(() -> ResumeActivityItem.obtain(mActivityToken, 3, true, false));
    }

    @Test
    public void testRecycleStartActivityItem() {
        testRecycle(() -> StartActivityItem.obtain(mActivityToken,
                new ActivityOptions.SceneTransitionInfo()));
    }

    @Test
    public void testRecycleStopItem() {
        testRecycle(() -> StopActivityItem.obtain(mActivityToken, 4));
    }

    @Test
    public void testRecycleClientTransaction() {
        testRecycle(() -> ClientTransaction.obtain(mApplicationThread));
    }

    private void testRecycle(@NonNull Supplier<? extends ObjectPoolItem> obtain) {
        // Reuse the same object after recycle.
        final ObjectPoolItem item = obtain.get();
        item.recycle();
        final ObjectPoolItem item2 = obtain.get();

        assertSame(item, item2);

        // Create new object when the pool is empty.
        final ObjectPoolItem item3 = obtain.get();

        assertNotSame(item, item3);
        assertEquals(item, item3);

        // Reset fields after recycle.
        item.recycle();

        assertNotEquals(item, item3);

        // Recycled objects are equal.
        item3.recycle();

        assertEquals(item, item3);
    }
}

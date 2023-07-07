/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server;

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;
import android.os.Looper;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;

import androidx.test.core.app.ApplicationProvider;

import com.android.internal.R;
import com.android.internal.util.test.BroadcastInterceptingContext;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DockObserverTest {

    @Rule
    public TestableContext mContext =
            new TestableContext(ApplicationProvider.getApplicationContext(), null);

    private final BroadcastInterceptingContext mInterceptingContext =
            new BroadcastInterceptingContext(mContext);

    BroadcastInterceptingContext.FutureIntent updateExtconDockState(DockObserver observer,
            String extconDockState) {
        BroadcastInterceptingContext.FutureIntent futureIntent =
                mInterceptingContext.nextBroadcastIntent(Intent.ACTION_DOCK_EVENT);
        observer.setDockStateFromProviderForTesting(
                DockObserver.ExtconStateProvider.fromString(extconDockState));
        TestableLooper.get(this).processAllMessages();
        return futureIntent;
    }

    DockObserver observerWithMappingConfig(String[] configEntries) {
        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_dockExtconStateMapping,
                configEntries);
        return new DockObserver(mInterceptingContext);
    }

    void assertDockEventIntentWithExtraThenUndock(DockObserver observer, String extconDockState,
            int expectedExtra) throws ExecutionException, InterruptedException {
        assertThat(updateExtconDockState(observer, extconDockState)
                .get().getIntExtra(Intent.EXTRA_DOCK_STATE, -1))
                .isEqualTo(expectedExtra);
        assertThat(updateExtconDockState(observer, "DOCK=0")
                .get().getIntExtra(Intent.EXTRA_DOCK_STATE, -1))
                .isEqualTo(Intent.EXTRA_DOCK_STATE_UNDOCKED);
    }

    void setDeviceProvisioned(boolean provisioned) {
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.DEVICE_PROVISIONED,
                provisioned ? 1 : 0);
    }

    @Before
    public void setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
    }

    @Test
    public void testDockIntentBroadcast_onlyAfterBootReady()
            throws ExecutionException, InterruptedException {
        DockObserver observer = new DockObserver(mInterceptingContext);
        BroadcastInterceptingContext.FutureIntent futureIntent =
                updateExtconDockState(observer, "DOCK=1");
        updateExtconDockState(observer, "DOCK=1").assertNotReceived();
        // Last boot phase reached
        observer.onBootPhase(SystemService.PHASE_ACTIVITY_MANAGER_READY);
        TestableLooper.get(this).processAllMessages();
        assertThat(futureIntent.get().getIntExtra(Intent.EXTRA_DOCK_STATE, -1))
                .isEqualTo(Intent.EXTRA_DOCK_STATE_DESK);
    }

    @Test
    public void testDockIntentBroadcast_customConfigResource()
            throws ExecutionException, InterruptedException {
        DockObserver observer = observerWithMappingConfig(
                new String[] {"2,KEY1=1,KEY2=2", "3,KEY3=3"});
        observer.onBootPhase(SystemService.PHASE_ACTIVITY_MANAGER_READY);

        // Mapping should not match
        assertDockEventIntentWithExtraThenUndock(observer, "DOCK=1",
                Intent.EXTRA_DOCK_STATE_DESK);
        assertDockEventIntentWithExtraThenUndock(observer, "DOCK=1\nKEY1=1",
                Intent.EXTRA_DOCK_STATE_DESK);
        assertDockEventIntentWithExtraThenUndock(observer, "DOCK=1\nKEY2=2",
                Intent.EXTRA_DOCK_STATE_DESK);

        // 1st mapping now matches
        assertDockEventIntentWithExtraThenUndock(observer, "DOCK=1\nKEY2=2\nKEY1=1",
                Intent.EXTRA_DOCK_STATE_CAR);

        // 2nd mapping now matches
        assertDockEventIntentWithExtraThenUndock(observer, "DOCK=1\nKEY3=3",
                Intent.EXTRA_DOCK_STATE_LE_DESK);
    }

    @Test
    public void testDockIntentBroadcast_customConfigResourceWithWildcard()
            throws ExecutionException, InterruptedException {
        DockObserver observer = observerWithMappingConfig(new String[] {
                "2,KEY2=2",
                "3,KEY3=3",
                "4"
        });
        observer.onBootPhase(SystemService.PHASE_ACTIVITY_MANAGER_READY);
        assertDockEventIntentWithExtraThenUndock(observer, "DOCK=1\nKEY5=5",
                Intent.EXTRA_DOCK_STATE_HE_DESK);
    }

    @Test
    public void testDockIntentBroadcast_deviceNotProvisioned()
            throws ExecutionException, InterruptedException {
        DockObserver observer = new DockObserver(mInterceptingContext);
        // Set the device as not provisioned.
        setDeviceProvisioned(false);
        observer.onBootPhase(SystemService.PHASE_ACTIVITY_MANAGER_READY);

        BroadcastInterceptingContext.FutureIntent futureIntent =
                updateExtconDockState(observer, "DOCK=1");
        TestableLooper.get(this).processAllMessages();
        // Verify no broadcast was sent as device was not provisioned.
        futureIntent.assertNotReceived();

        // Ensure we send the broadcast when the device is provisioned.
        setDeviceProvisioned(true);
        TestableLooper.get(this).processAllMessages();
        assertThat(futureIntent.get().getIntExtra(Intent.EXTRA_DOCK_STATE, -1))
                .isEqualTo(Intent.EXTRA_DOCK_STATE_DESK);
    }
}

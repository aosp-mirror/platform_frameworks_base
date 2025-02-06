/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.systemui.lowlightclock;

import static com.android.dream.lowlight.LowLightDreamManager.AMBIENT_LIGHT_MODE_LOW_LIGHT;
import static com.android.dream.lowlight.LowLightDreamManager.AMBIENT_LIGHT_MODE_REGULAR;
import static com.android.systemui.keyguard.ScreenLifecycle.SCREEN_ON;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.dream.lowlight.LowLightDreamManager;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.shared.condition.Condition;
import com.android.systemui.shared.condition.Monitor;

import dagger.Lazy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class LowLightMonitorTest extends SysuiTestCase {

    @Mock
    private Lazy<LowLightDreamManager> mLowLightDreamManagerLazy;
    @Mock
    private LowLightDreamManager mLowLightDreamManager;
    @Mock
    private Monitor mMonitor;
    @Mock
    private ScreenLifecycle mScreenLifecycle;
    @Mock
    private LowLightLogger mLogger;

    private LowLightMonitor mLowLightMonitor;

    @Mock
    Lazy<Set<Condition>> mLazyConditions;

    @Mock
    private PackageManager mPackageManager;

    @Mock
    private ComponentName mDreamComponent;

    Condition mCondition = mock(Condition.class);
    Set<Condition> mConditionSet = Set.of(mCondition);

    @Captor
    ArgumentCaptor<Monitor.Subscription> mPreconditionsSubscriptionCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mLowLightDreamManagerLazy.get()).thenReturn(mLowLightDreamManager);
        when(mLazyConditions.get()).thenReturn(mConditionSet);
        mLowLightMonitor = new LowLightMonitor(mLowLightDreamManagerLazy,
            mMonitor, mLazyConditions, mScreenLifecycle, mLogger, mDreamComponent,
                mPackageManager);
    }

    @Test
    public void testSetAmbientLowLightWhenInLowLight() {
        mLowLightMonitor.onConditionsChanged(true);
        // Verify setting low light when condition is true
        verify(mLowLightDreamManager).setAmbientLightMode(AMBIENT_LIGHT_MODE_LOW_LIGHT);
    }

    @Test
    public void testExitAmbientLowLightWhenNotInLowLight() {
        mLowLightMonitor.onConditionsChanged(true);
        mLowLightMonitor.onConditionsChanged(false);
        // Verify ambient light toggles back to light mode regular
        verify(mLowLightDreamManager).setAmbientLightMode(AMBIENT_LIGHT_MODE_REGULAR);
    }

    @Test
    public void testStartMonitorLowLightConditionsWhenScreenTurnsOn() {
        mLowLightMonitor.onScreenTurnedOn();

        // Verify subscribing to low light conditions monitor when screen turns on.
        verify(mMonitor).addSubscription(any());
    }

    @Test
    public void testStopMonitorLowLightConditionsWhenScreenTurnsOff() {
        final Monitor.Subscription.Token token = mock(Monitor.Subscription.Token.class);
        when(mMonitor.addSubscription(any())).thenReturn(token);
        mLowLightMonitor.onScreenTurnedOn();

        // Verify removing subscription when screen turns off.
        mLowLightMonitor.onScreenTurnedOff();
        verify(mMonitor).removeSubscription(token);
    }

    @Test
    public void testSubscribeToLowLightConditionsOnlyOnceWhenScreenTurnsOn() {
        final Monitor.Subscription.Token token = mock(Monitor.Subscription.Token.class);
        when(mMonitor.addSubscription(any())).thenReturn(token);

        mLowLightMonitor.onScreenTurnedOn();
        mLowLightMonitor.onScreenTurnedOn();
        // Verify subscription is only added once.
        verify(mMonitor, times(1)).addSubscription(any());
    }

    @Test
    public void testSubscribedToExpectedConditions() {
        final Monitor.Subscription.Token token = mock(Monitor.Subscription.Token.class);
        when(mMonitor.addSubscription(any())).thenReturn(token);

        mLowLightMonitor.onScreenTurnedOn();
        mLowLightMonitor.onScreenTurnedOn();
        Set<Condition> conditions = captureConditions();
        // Verify Monitor is subscribed to the expected conditions
        assertThat(conditions).isEqualTo(mConditionSet);
    }

    @Test
    public void testNotUnsubscribeIfNotSubscribedWhenScreenTurnsOff() {
        mLowLightMonitor.onScreenTurnedOff();

        // Verify doesn't remove subscription since there is none.
        verify(mMonitor, never()).removeSubscription(any());
    }

    @Test
    public void testSubscribeIfScreenIsOnWhenStarting() {
        when(mScreenLifecycle.getScreenState()).thenReturn(SCREEN_ON);
        mLowLightMonitor.start();
        // Verify to add subscription on start if the screen state is on
        verify(mMonitor, times(1)).addSubscription(any());
    }

    @Test
    public void testNoSubscribeIfDreamNotPresent() {
        LowLightMonitor lowLightMonitor = new LowLightMonitor(mLowLightDreamManagerLazy,
                mMonitor, mLazyConditions, mScreenLifecycle, mLogger, null, mPackageManager);
        when(mScreenLifecycle.getScreenState()).thenReturn(SCREEN_ON);
        lowLightMonitor.start();
        verify(mScreenLifecycle, never()).addObserver(any());
    }

    private Set<Condition> captureConditions() {
        verify(mMonitor).addSubscription(mPreconditionsSubscriptionCaptor.capture());
        return mPreconditionsSubscriptionCaptor.getValue().getConditions();
    }
}

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

package com.android.systemui.statusbar.phone;

import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.BEHAVIOR_DEFAULT;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.Display;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.lifecycle.Observer;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.notification.collection.NotifLiveData;
import com.android.systemui.statusbar.notification.collection.NotifLiveDataStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Objects;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class LegacyLightsOutNotifControllerTest extends SysuiTestCase {
    private static final int LIGHTS_ON = 0;
    private static final int LIGHTS_OUT = APPEARANCE_LOW_PROFILE_BARS;

    @Mock private NotifLiveData<Boolean> mHasActiveNotifs;
    @Mock private NotifLiveDataStore mNotifLiveDataStore;
    @Mock private CommandQueue mCommandQueue;
    @Mock private WindowManager mWindowManager;
    @Mock private Display mDisplay;

    @Captor private ArgumentCaptor<Observer<Boolean>> mObserverCaptor;
    @Captor private ArgumentCaptor<CommandQueue.Callbacks> mCallbacksCaptor;

    private View mLightsOutView;
    private LegacyLightsOutNotifController mLightsOutNotifController;
    private int mDisplayId;
    private Observer<Boolean> mHaActiveNotifsObserver;
    private CommandQueue.Callbacks mCallbacks;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDisplayId = mContext.getDisplayId();
        mLightsOutView = new View(mContext);
        when(mWindowManager.getDefaultDisplay()).thenReturn(mDisplay);
        when(mDisplay.getDisplayId()).thenReturn(mDisplayId);
        when(mNotifLiveDataStore.getHasActiveNotifs()).thenReturn(mHasActiveNotifs);
        when(mHasActiveNotifs.getValue()).thenReturn(false);

        mLightsOutNotifController = new LegacyLightsOutNotifController(
                mLightsOutView,
                mWindowManager,
                mNotifLiveDataStore,
                mCommandQueue);
        mLightsOutNotifController.init();
        mLightsOutNotifController.onViewAttached();

        // Capture the entry listener object so we can simulate events in tests below
        verify(mHasActiveNotifs).addSyncObserver(mObserverCaptor.capture());
        mHaActiveNotifsObserver = Objects.requireNonNull(mObserverCaptor.getValue());

        // Capture the callback object so we can simulate callback events in tests below
        verify(mCommandQueue).addCallback(mCallbacksCaptor.capture());
        mCallbacks = Objects.requireNonNull(mCallbacksCaptor.getValue());
    }

    @Test
    public void testAreLightsOut_lightsOut() {
        mCallbacks.onSystemBarAttributesChanged(
                mDisplayId /* display id */,
                LIGHTS_OUT /* appearance */,
                null /* appearanceRegions */,
                false /* navbarColorManagedByIme */,
                BEHAVIOR_DEFAULT,
                WindowInsets.Type.defaultVisible(),
                null /* packageName */,
                null /* letterboxDetails */);
        assertTrue(mLightsOutNotifController.areLightsOut());
    }

    @Test
    public void testAreLightsOut_lightsOn() {
        mCallbacks.onSystemBarAttributesChanged(
                mDisplayId /* display id */,
                LIGHTS_ON /* appearance */,
                null /* appearanceRegions */,
                false /* navbarColorManagedByIme */,
                BEHAVIOR_DEFAULT,
                WindowInsets.Type.defaultVisible(),
                null /* packageName */,
                null /* letterboxDetails */);
        assertFalse(mLightsOutNotifController.areLightsOut());
    }

    @Test
    public void testIsShowingDot_visible() {
        mLightsOutView.setVisibility(View.VISIBLE);
        mLightsOutView.setAlpha(1.0f);
        assertTrue(mLightsOutNotifController.isShowingDot());
    }

    @Test
    public void testIsShowingDot_gone() {
        mLightsOutView.setVisibility(View.GONE);
        mLightsOutView.setAlpha(0f);
        assertFalse(mLightsOutNotifController.isShowingDot());
    }

    @Test
    public void testLightsOut_withNotifs_onSystemBarAttributesChanged() {
        // GIVEN active visible notifications
        when(mHasActiveNotifs.getValue()).thenReturn(true);

        // WHEN lights out
        mCallbacks.onSystemBarAttributesChanged(
                mDisplayId /* display id */,
                LIGHTS_OUT /* appearance */,
                null /* appearanceRegions */,
                false /* navbarColorManagedByIme */,
                BEHAVIOR_DEFAULT,
                WindowInsets.Type.defaultVisible(),
                null /* packageName */,
                null /* letterboxDetails */);

        // THEN we should show dot
        assertTrue(mLightsOutNotifController.shouldShowDot());
        assertIsShowingDot(true);
    }

    @Test
    public void testLightsOut_withoutNotifs_onSystemBarAttributesChanged() {
        // GIVEN no active visible notifications
        when(mHasActiveNotifs.getValue()).thenReturn(false);

        // WHEN lights out
        mCallbacks.onSystemBarAttributesChanged(
                mDisplayId /* display id */,
                LIGHTS_OUT /* appearance */,
                null /* appearanceRegions */,
                false /* navbarColorManagedByIme */,
                BEHAVIOR_DEFAULT,
                WindowInsets.Type.defaultVisible(),
                null /* packageName */,
                null /* letterboxDetails */);

        // THEN we shouldn't show the dot
        assertFalse(mLightsOutNotifController.shouldShowDot());
        assertIsShowingDot(false);
    }

    @Test
    public void testLightsOn_afterLightsOut_onSystemBarAttributesChanged() {
        // GIVEN active visible notifications
        when(mHasActiveNotifs.getValue()).thenReturn(true);

        // WHEN lights on
        mCallbacks.onSystemBarAttributesChanged(
                mDisplayId /* display id */,
                LIGHTS_ON /* appearance */,
                null /* appearanceRegions */,
                false /* navbarColorManagedByIme */,
                BEHAVIOR_DEFAULT,
                WindowInsets.Type.defaultVisible(),
                null /* packageName */,
                null /* letterboxDetails */);

        // THEN we shouldn't show the dot
        assertFalse(mLightsOutNotifController.shouldShowDot());
        assertIsShowingDot(false);
    }

    @Test
    public void testEntryAdded() {
        // GIVEN no visible notifications and lights out
        when(mHasActiveNotifs.getValue()).thenReturn(false);
        mLightsOutNotifController.mAppearance = LIGHTS_OUT;
        mLightsOutNotifController.updateLightsOutView();
        assertIsShowingDot(false);

        // WHEN an active notification is added
        when(mHasActiveNotifs.getValue()).thenReturn(true);
        assertTrue(mLightsOutNotifController.shouldShowDot());
        mHaActiveNotifsObserver.onChanged(true);

        // THEN we should see the dot view
        assertIsShowingDot(true);
    }

    @Test
    public void testEntryRemoved() {
        // GIVEN a visible notification and lights out
        when(mHasActiveNotifs.getValue()).thenReturn(true);
        mLightsOutNotifController.mAppearance = LIGHTS_OUT;
        mLightsOutNotifController.updateLightsOutView();
        assertIsShowingDot(true);

        // WHEN all active notifications are removed
        when(mHasActiveNotifs.getValue()).thenReturn(false);
        assertFalse(mLightsOutNotifController.shouldShowDot());
        mHaActiveNotifsObserver.onChanged(false);

        // THEN we shouldn't see the dot view
        assertIsShowingDot(false);
    }

    private void assertIsShowingDot(boolean isShowing) {
        // cancel the animation so we can check the end state
        final ViewPropertyAnimator animation = mLightsOutView.animate();
        if (animation != null) {
            animation.cancel();
        }

        if (isShowing) {
            assertTrue(mLightsOutNotifController.isShowingDot());
        } else {
            assertFalse(mLightsOutNotifController.isShowingDot());
        }
    }
}

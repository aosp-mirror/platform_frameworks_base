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

package com.android.systemui.complication;

import static com.android.systemui.complication.Complication.COMPLICATION_TYPE_MEDIA_ENTRY;
import static com.android.systemui.flags.Flags.DREAM_MEDIA_TAP_TO_OPEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.content.Intent;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.complication.dagger.DreamMediaEntryComplicationComponent;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.media.controls.ui.controller.MediaCarouselController;
import com.android.systemui.media.dream.MediaDreamComplication;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DreamMediaEntryComplicationTest extends SysuiTestCase {
    @Mock
    private DreamMediaEntryComplicationComponent.Factory mComponentFactory;

    @Mock
    private View mView;

    @Mock
    private DreamOverlayStateController mDreamOverlayStateController;

    @Mock
    private MediaDreamComplication mMediaComplication;

    @Mock
    private MediaCarouselController mMediaCarouselController;

    @Mock
    private ActivityStarter mActivityStarter;

    @Mock
    private ActivityIntentHelper mActivityIntentHelper;

    @Mock
    private KeyguardStateController mKeyguardStateController;

    @Mock
    private NotificationLockscreenUserManager mLockscreenUserManager;

    @Mock
    private FeatureFlags mFeatureFlags;

    @Mock
    private PendingIntent mPendingIntent;

    private final Intent mIntent = new Intent("android.test.TEST_ACTION");
    private final Integer mCurrentUserId = 99;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mFeatureFlags.isEnabled(DREAM_MEDIA_TAP_TO_OPEN)).thenReturn(false);
    }

    @Test
    public void testGetRequiredTypeAvailability() {
        final DreamMediaEntryComplication complication =
                new DreamMediaEntryComplication(mComponentFactory);
        assertThat(complication.getRequiredTypeAvailability()).isEqualTo(
                COMPLICATION_TYPE_MEDIA_ENTRY);
    }

    /**
     * Ensures clicking media entry chip adds/removes media complication.
     */
    @Test
    public void testClickToOpenUMO() {
        final DreamMediaEntryComplication.DreamMediaEntryViewController viewController =
                new DreamMediaEntryComplication.DreamMediaEntryViewController(
                        mView,
                        mDreamOverlayStateController,
                        mMediaComplication,
                        mMediaCarouselController,
                        mActivityStarter,
                        mActivityIntentHelper,
                        mKeyguardStateController,
                        mLockscreenUserManager,
                        mFeatureFlags);

        final ArgumentCaptor<View.OnClickListener> clickListenerCaptor =
                ArgumentCaptor.forClass(View.OnClickListener.class);
        verify(mView).setOnClickListener(clickListenerCaptor.capture());

        clickListenerCaptor.getValue().onClick(mView);
        verify(mView).setSelected(true);
        verify(mDreamOverlayStateController).addComplication(mMediaComplication);
        clickListenerCaptor.getValue().onClick(mView);
        verify(mView).setSelected(false);
        verify(mDreamOverlayStateController).removeComplication(mMediaComplication);
    }

    /**
     * Ensures media complication is removed when the view is detached.
     */
    @Test
    public void testOnViewDetached() {
        final DreamMediaEntryComplication.DreamMediaEntryViewController viewController =
                new DreamMediaEntryComplication.DreamMediaEntryViewController(
                        mView,
                        mDreamOverlayStateController,
                        mMediaComplication,
                        mMediaCarouselController,
                        mActivityStarter,
                        mActivityIntentHelper,
                        mKeyguardStateController,
                        mLockscreenUserManager,
                        mFeatureFlags);

        viewController.onViewDetached();
        verify(mView).setSelected(false);
        verify(mDreamOverlayStateController).removeComplication(mMediaComplication);
    }

    /**
     * Ensures clicking media entry chip opens media when flag is set.
     */
    @Test
    public void testClickToOpenMediaOverLockscreen() {
        when(mFeatureFlags.isEnabled(DREAM_MEDIA_TAP_TO_OPEN)).thenReturn(true);

        when(mMediaCarouselController.getCurrentVisibleMediaContentIntent()).thenReturn(
                mPendingIntent);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mPendingIntent.getIntent()).thenReturn(mIntent);
        when(mLockscreenUserManager.getCurrentUserId()).thenReturn(mCurrentUserId);

        final DreamMediaEntryComplication.DreamMediaEntryViewController viewController =
                new DreamMediaEntryComplication.DreamMediaEntryViewController(
                        mView,
                        mDreamOverlayStateController,
                        mMediaComplication,
                        mMediaCarouselController,
                        mActivityStarter,
                        mActivityIntentHelper,
                        mKeyguardStateController,
                        mLockscreenUserManager,
                        mFeatureFlags);
        viewController.onViewAttached();

        final ArgumentCaptor<View.OnClickListener> clickListenerCaptor =
                ArgumentCaptor.forClass(View.OnClickListener.class);
        verify(mView).setOnClickListener(clickListenerCaptor.capture());

        when(mActivityIntentHelper.wouldShowOverLockscreen(mIntent, mCurrentUserId)).thenReturn(
                true);

        clickListenerCaptor.getValue().onClick(mView);
        verify(mActivityStarter).startActivity(mIntent, true, null, true);
    }

    /**
     * Ensures clicking media entry chip opens media when flag is set.
     */
    @Test
    public void testClickToOpenMediaDismissingLockscreen() {
        when(mFeatureFlags.isEnabled(DREAM_MEDIA_TAP_TO_OPEN)).thenReturn(true);

        when(mMediaCarouselController.getCurrentVisibleMediaContentIntent()).thenReturn(
                mPendingIntent);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mPendingIntent.getIntent()).thenReturn(mIntent);
        when(mLockscreenUserManager.getCurrentUserId()).thenReturn(mCurrentUserId);

        final DreamMediaEntryComplication.DreamMediaEntryViewController viewController =
                new DreamMediaEntryComplication.DreamMediaEntryViewController(
                        mView,
                        mDreamOverlayStateController,
                        mMediaComplication,
                        mMediaCarouselController,
                        mActivityStarter,
                        mActivityIntentHelper,
                        mKeyguardStateController,
                        mLockscreenUserManager,
                        mFeatureFlags);
        viewController.onViewAttached();

        final ArgumentCaptor<View.OnClickListener> clickListenerCaptor =
                ArgumentCaptor.forClass(View.OnClickListener.class);
        verify(mView).setOnClickListener(clickListenerCaptor.capture());

        when(mActivityIntentHelper.wouldShowOverLockscreen(mIntent, mCurrentUserId)).thenReturn(
                false);

        clickListenerCaptor.getValue().onClick(mView);
        verify(mActivityStarter).postStartActivityDismissingKeyguard(mPendingIntent, null);
    }
}

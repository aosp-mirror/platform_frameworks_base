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

package com.android.systemui.media.dialog;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.drawable.IconCompat;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.phone.ShadeController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class MediaOutputBaseDialogTest extends SysuiTestCase {

    private static final String TEST_PACKAGE = "test_package";

    // Mock
    private MediaOutputBaseAdapter mMediaOutputBaseAdapter = mock(MediaOutputBaseAdapter.class);
    private MediaSessionManager mMediaSessionManager = mock(MediaSessionManager.class);
    private LocalBluetoothManager mLocalBluetoothManager = mock(LocalBluetoothManager.class);
    private ShadeController mShadeController = mock(ShadeController.class);
    private ActivityStarter mStarter = mock(ActivityStarter.class);
    private NotificationEntryManager mNotificationEntryManager =
            mock(NotificationEntryManager.class);
    private final UiEventLogger mUiEventLogger = mock(UiEventLogger.class);
    private final DialogLaunchAnimator mDialogLaunchAnimator = mock(DialogLaunchAnimator.class);

    private MediaOutputBaseDialogImpl mMediaOutputBaseDialogImpl;
    private MediaOutputController mMediaOutputController;
    private int mHeaderIconRes;
    private IconCompat mIconCompat;
    private CharSequence mHeaderTitle;
    private CharSequence mHeaderSubtitle;

    @Before
    public void setUp() {
        mMediaOutputController = new MediaOutputController(mContext, TEST_PACKAGE, false,
                mMediaSessionManager, mLocalBluetoothManager, mShadeController, mStarter,
                mNotificationEntryManager, mUiEventLogger, mDialogLaunchAnimator);
        mMediaOutputBaseDialogImpl = new MediaOutputBaseDialogImpl(mContext,
                mMediaOutputController);
        mMediaOutputBaseDialogImpl.onCreate(new Bundle());
    }

    @Test
    public void refresh_withIconRes_iconIsVisible() {
        mHeaderIconRes = 1;
        mMediaOutputBaseDialogImpl.refresh();
        final ImageView view = mMediaOutputBaseDialogImpl.mDialogView.requireViewById(
                R.id.header_icon);

        assertThat(view.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void refresh_withIconCompat_iconIsVisible() {
        mIconCompat = mock(IconCompat.class);
        mMediaOutputBaseDialogImpl.refresh();
        final ImageView view = mMediaOutputBaseDialogImpl.mDialogView.requireViewById(
                R.id.header_icon);

        assertThat(view.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void refresh_noIcon_iconLayoutNotVisible() {
        mHeaderIconRes = 0;
        mIconCompat = null;
        mMediaOutputBaseDialogImpl.refresh();
        final ImageView view = mMediaOutputBaseDialogImpl.mDialogView.requireViewById(
                R.id.header_icon);

        assertThat(view.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void refresh_checkTitle() {
        mHeaderTitle = "test_string";

        mMediaOutputBaseDialogImpl.refresh();
        final TextView titleView = mMediaOutputBaseDialogImpl.mDialogView.requireViewById(
                R.id.header_title);

        assertThat(titleView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(titleView.getText()).isEqualTo(mHeaderTitle);
    }

    @Test
    public void refresh_withSubtitle_checkSubtitle() {
        mHeaderSubtitle = "test_string";

        mMediaOutputBaseDialogImpl.refresh();
        final TextView subtitleView = mMediaOutputBaseDialogImpl.mDialogView.requireViewById(
                R.id.header_subtitle);

        assertThat(subtitleView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(subtitleView.getText()).isEqualTo(mHeaderSubtitle);
    }

    @Test
    public void refresh_noSubtitle_checkSubtitle() {
        mMediaOutputBaseDialogImpl.refresh();
        final TextView subtitleView = mMediaOutputBaseDialogImpl.mDialogView.requireViewById(
                R.id.header_subtitle);

        assertThat(subtitleView.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void refresh_inDragging_notUpdateAdapter() {
        when(mMediaOutputBaseAdapter.isDragging()).thenReturn(true);
        mMediaOutputBaseDialogImpl.refresh();

        verify(mMediaOutputBaseAdapter, never()).notifyDataSetChanged();
    }

    @Test
    public void refresh_notInDragging_verifyUpdateAdapter() {
        when(mMediaOutputBaseAdapter.getCurrentActivePosition()).thenReturn(-1);
        when(mMediaOutputBaseAdapter.isDragging()).thenReturn(false);
        mMediaOutputBaseDialogImpl.refresh();

        verify(mMediaOutputBaseAdapter).notifyDataSetChanged();
    }

    class MediaOutputBaseDialogImpl extends MediaOutputBaseDialog {

        MediaOutputBaseDialogImpl(Context context, MediaOutputController mediaOutputController) {
            super(context, mediaOutputController);

            mAdapter = mMediaOutputBaseAdapter;
        }

        @Override
        int getHeaderIconRes() {
            return mHeaderIconRes;
        }

        @Override
        IconCompat getHeaderIcon() {
            return mIconCompat;
        }

        @Override
        int getHeaderIconSize() {
            return 10;
        }

        @Override
        CharSequence getHeaderText() {
            return mHeaderTitle;
        }

        @Override
        CharSequence getHeaderSubtitle() {
            return mHeaderSubtitle;
        }

        @Override
        int getStopButtonVisibility() {
            return 0;
        }
    }
}

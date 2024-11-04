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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.PowerExemptionManager;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.drawable.IconCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.media.LocalMediaManager;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestCaseExtKt;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.kosmos.Kosmos;
import com.android.systemui.media.nearby.NearbyMediaDevicesManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.volume.panel.domain.interactor.VolumePanelGlobalStateInteractor;
import com.android.systemui.volume.panel.domain.interactor.VolumePanelGlobalStateInteractorKosmosKt;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class MediaOutputBaseDialogTest extends SysuiTestCase {

    private static final String TEST_PACKAGE = "test_package";

    private final Kosmos mKosmos = SysuiTestCaseExtKt.testKosmos(this);

    // Mock
    private MediaOutputBaseAdapter mMediaOutputBaseAdapter = mock(MediaOutputBaseAdapter.class);
    private MediaController mMediaController = mock(MediaController.class);
    private PlaybackState mPlaybackState = mock(PlaybackState.class);
    private MediaSessionManager mMediaSessionManager = mock(MediaSessionManager.class);
    private LocalBluetoothManager mLocalBluetoothManager = mock(LocalBluetoothManager.class);
    private final LocalBluetoothProfileManager mLocalBluetoothProfileManager = mock(
            LocalBluetoothProfileManager.class);
    private final LocalBluetoothLeBroadcast mLocalBluetoothLeBroadcast = mock(
            LocalBluetoothLeBroadcast.class);
    private ActivityStarter mStarter = mock(ActivityStarter.class);
    private BroadcastSender mBroadcastSender = mock(BroadcastSender.class);
    private final CommonNotifCollection mNotifCollection = mock(CommonNotifCollection.class);
    private NearbyMediaDevicesManager mNearbyMediaDevicesManager = mock(
            NearbyMediaDevicesManager.class);
    private final DialogTransitionAnimator mDialogTransitionAnimator = mock(
            DialogTransitionAnimator.class);
    private final AudioManager mAudioManager = mock(AudioManager.class);
    private PowerExemptionManager mPowerExemptionManager = mock(PowerExemptionManager.class);
    private KeyguardManager mKeyguardManager = mock(KeyguardManager.class);
    private FeatureFlags mFlags = mock(FeatureFlags.class);
    private UserTracker mUserTracker = mock(UserTracker.class);

    private List<MediaController> mMediaControllers = new ArrayList<>();
    private MediaOutputBaseDialogImpl mMediaOutputBaseDialogImpl;
    private MediaSwitchingController mMediaSwitchingController;
    private int mHeaderIconRes;
    private IconCompat mIconCompat;
    private CharSequence mHeaderTitle;
    private CharSequence mHeaderSubtitle;
    private String mStopText;
    private boolean mIsBroadcasting;
    private boolean mIsBroadcastIconVisibility;


    @Before
    public void setUp() {
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalBluetoothProfileManager);
        final CachedBluetoothDeviceManager cachedBluetoothDeviceManager = mock(
                CachedBluetoothDeviceManager.class);
        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(
                cachedBluetoothDeviceManager);
        when(cachedBluetoothDeviceManager.findDevice(any())).thenReturn(null);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(null);
        when(mMediaController.getPlaybackState()).thenReturn(mPlaybackState);
        when(mPlaybackState.getState()).thenReturn(PlaybackState.STATE_NONE);
        when(mMediaController.getPackageName()).thenReturn(TEST_PACKAGE);
        mMediaControllers.add(mMediaController);
        when(mMediaSessionManager.getActiveSessions(any())).thenReturn(mMediaControllers);
        VolumePanelGlobalStateInteractor volumePanelGlobalStateInteractor =
                VolumePanelGlobalStateInteractorKosmosKt.getVolumePanelGlobalStateInteractor(
                        mKosmos);

        mMediaSwitchingController =
                new MediaSwitchingController(
                        mContext,
                        TEST_PACKAGE,
                        mContext.getUser(),
                        /* token */ null,
                        mMediaSessionManager,
                        mLocalBluetoothManager,
                        mStarter,
                        mNotifCollection,
                        mDialogTransitionAnimator,
                        mNearbyMediaDevicesManager,
                        mAudioManager,
                        mPowerExemptionManager,
                        mKeyguardManager,
                        mFlags,
                        volumePanelGlobalStateInteractor,
                        mUserTracker);

        // Using a fake package will cause routing operations to fail, so we intercept
        // scanning-related operations.
        mMediaSwitchingController.mLocalMediaManager = mock(LocalMediaManager.class);
        doNothing().when(mMediaSwitchingController.mLocalMediaManager).startScan();
        doNothing().when(mMediaSwitchingController.mLocalMediaManager).stopScan();

        mMediaOutputBaseDialogImpl =
                new MediaOutputBaseDialogImpl(
                        mContext, mBroadcastSender, mMediaSwitchingController);
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
        mIconCompat = IconCompat.createWithBitmap(
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
        when(mMediaOutputBaseAdapter.getController()).thenReturn(mMediaSwitchingController);

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
    public void refresh_broadcastIconVisibilityOff_broadcastIconLayoutNotVisible() {
        mIsBroadcastIconVisibility = false;

        mMediaOutputBaseDialogImpl.refresh();
        final ImageView view = mMediaOutputBaseDialogImpl.mDialogView.requireViewById(
                R.id.broadcast_icon);

        assertThat(view.getVisibility()).isEqualTo(View.GONE);
    }
    @Test
    public void refresh_broadcastIconVisibilityOn_broadcastIconLayoutVisible() {
        mIsBroadcastIconVisibility = true;

        mMediaOutputBaseDialogImpl.refresh();
        final ImageView view = mMediaOutputBaseDialogImpl.mDialogView.requireViewById(
                R.id.broadcast_icon);

        assertThat(view.getVisibility()).isEqualTo(View.VISIBLE);
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
    public void refresh_inDragging_directSetRefreshingToFalse() {
        when(mMediaOutputBaseAdapter.isDragging()).thenReturn(true);
        mMediaOutputBaseDialogImpl.refresh();

        assertThat(mMediaSwitchingController.isRefreshing()).isFalse();
    }

    @Test
    public void refresh_notInDragging_verifyUpdateAdapter() {
        when(mMediaOutputBaseAdapter.getCurrentActivePosition()).thenReturn(-1);
        when(mMediaOutputBaseAdapter.isDragging()).thenReturn(false);
        mMediaOutputBaseDialogImpl.refresh();

        verify(mMediaOutputBaseAdapter).updateItems();
    }

    @Test
    public void dismissDialog_closesDialogByBroadcastSender() {
        mMediaOutputBaseDialogImpl.dismissDialog();

        verify(mBroadcastSender).closeSystemDialogs();
    }

    @Test
    public void whenBroadcasting_verifyLeBroadcastServiceCallBackIsRegisteredAndUnregistered() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        mIsBroadcasting = true;

        mMediaOutputBaseDialogImpl.start();
        verify(mLocalBluetoothLeBroadcast).registerServiceCallBack(any(), any());

        mMediaOutputBaseDialogImpl.stop();
        verify(mLocalBluetoothLeBroadcast).unregisterServiceCallBack(any());
    }

    @Test
    public void
            whenNotBroadcasting_verifyLeBroadcastServiceCallBackIsNotRegisteredOrUnregistered() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        mIsBroadcasting = false;

        mMediaOutputBaseDialogImpl.start();
        mMediaOutputBaseDialogImpl.stop();

        verify(mLocalBluetoothLeBroadcast, never()).registerServiceCallBack(any(), any());
        verify(mLocalBluetoothLeBroadcast, never()).unregisterServiceCallBack(any());
    }

    @Test
    public void
            whenNotBroadcasting_verifyLeBroadcastServiceCallBackIsUnregisteredIfProfileEnabled() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        mIsBroadcasting = true;

        mMediaOutputBaseDialogImpl.start();
        verify(mLocalBluetoothLeBroadcast).registerServiceCallBack(any(), any());

        mIsBroadcasting = false;
        mMediaOutputBaseDialogImpl.stop();
        verify(mLocalBluetoothLeBroadcast).unregisterServiceCallBack(any());
    }

    @Test
    public void refresh_checkStopText() {
        mStopText = "test_string";
        mMediaOutputBaseDialogImpl.refresh();
        final Button stop = mMediaOutputBaseDialogImpl.mDialogView.requireViewById(R.id.stop);

        assertThat(stop.getText().toString()).isEqualTo(mStopText);
    }

    class MediaOutputBaseDialogImpl extends MediaOutputBaseDialog {

        MediaOutputBaseDialogImpl(
                Context context,
                BroadcastSender broadcastSender,
                MediaSwitchingController mediaSwitchingController) {
            super(
                    context,
                    broadcastSender,
                    mediaSwitchingController, /* includePlaybackAndAppMetadata */
                    true);

            mAdapter = mMediaOutputBaseAdapter;
        }

        @Override
        IconCompat getAppSourceIcon() {
            return null;
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

        @Override
        public boolean isBroadcastSupported() {
            return mIsBroadcasting;
        }

        @Override
        public CharSequence getStopButtonText() {
            return mStopText;
        }

        @Override
        public int getBroadcastIconVisibility() {
            return mIsBroadcastIconVisibility ? View.VISIBLE : View.GONE;
        }
    }
}

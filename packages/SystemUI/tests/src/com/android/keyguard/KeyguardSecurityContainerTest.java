/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.systemBars;

import static com.android.keyguard.KeyguardSecurityContainer.MODE_DEFAULT;
import static com.android.keyguard.KeyguardSecurityContainer.MODE_ONE_HANDED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.policy.UserSwitcherController.UserRecord;
import com.android.systemui.util.settings.GlobalSettings;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper()
public class KeyguardSecurityContainerTest extends SysuiTestCase {
    private static final int SCREEN_WIDTH = 1600;
    private static final int FAKE_MEASURE_SPEC =
            View.MeasureSpec.makeMeasureSpec(SCREEN_WIDTH, View.MeasureSpec.EXACTLY);

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();

    @Mock
    private WindowInsetsController mWindowInsetsController;
    @Mock
    private KeyguardSecurityViewFlipper mSecurityViewFlipper;
    @Mock
    private GlobalSettings mGlobalSettings;
    @Mock
    private FalsingManager mFalsingManager;
    @Mock
    private UserSwitcherController mUserSwitcherController;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Captor
    private ArgumentCaptor<FrameLayout.LayoutParams> mLayoutCaptor;

    private KeyguardSecurityContainer mKeyguardSecurityContainer;
    private FrameLayout.LayoutParams mSecurityViewFlipperLayoutParams;

    @Before
    public void setup() {
        // Needed here, otherwise when mKeyguardSecurityContainer is created below, it'll cache
        // the real references (rather than the TestableResources that this call creates).
        mContext.ensureTestableResources();
        mSecurityViewFlipperLayoutParams = new FrameLayout.LayoutParams(
                MATCH_PARENT, MATCH_PARENT);

        when(mSecurityViewFlipper.getWindowInsetsController()).thenReturn(mWindowInsetsController);
        when(mSecurityViewFlipper.getLayoutParams()).thenReturn(mSecurityViewFlipperLayoutParams);
        mKeyguardSecurityContainer = new KeyguardSecurityContainer(getContext());
        mKeyguardSecurityContainer.mSecurityViewFlipper = mSecurityViewFlipper;
        mKeyguardSecurityContainer.addView(mSecurityViewFlipper, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        when(mUserSwitcherController.getCurrentUserName()).thenReturn("Test User");
        when(mUserSwitcherController.getKeyguardStateController())
                .thenReturn(mKeyguardStateController);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
    }

    @Test
    public void onMeasure_usesHalfWidthWithOneHandedModeEnabled() {
        mKeyguardSecurityContainer.initMode(MODE_ONE_HANDED, mGlobalSettings, mFalsingManager,
                mUserSwitcherController);

        int halfWidthMeasureSpec =
                View.MeasureSpec.makeMeasureSpec(SCREEN_WIDTH / 2, View.MeasureSpec.EXACTLY);
        mKeyguardSecurityContainer.onMeasure(FAKE_MEASURE_SPEC, FAKE_MEASURE_SPEC);

        verify(mSecurityViewFlipper).measure(halfWidthMeasureSpec, FAKE_MEASURE_SPEC);
    }

    @Test
    public void onMeasure_usesFullWidthWithOneHandedModeDisabled() {
        mKeyguardSecurityContainer.initMode(MODE_DEFAULT, mGlobalSettings, mFalsingManager,
                mUserSwitcherController);

        mKeyguardSecurityContainer.measure(FAKE_MEASURE_SPEC, FAKE_MEASURE_SPEC);
        verify(mSecurityViewFlipper).measure(FAKE_MEASURE_SPEC, FAKE_MEASURE_SPEC);
    }

    @Test
    public void onMeasure_respectsViewInsets() {
        int imeInsetAmount = 100;
        int systemBarInsetAmount = 10;

        mKeyguardSecurityContainer.initMode(MODE_DEFAULT, mGlobalSettings, mFalsingManager,
                mUserSwitcherController);

        Insets imeInset = Insets.of(0, 0, 0, imeInsetAmount);
        Insets systemBarInset = Insets.of(0, 0, 0, systemBarInsetAmount);

        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(ime(), imeInset)
                .setInsetsIgnoringVisibility(systemBars(), systemBarInset)
                .build();

        // It's reduced by the max of the systembar and IME, so just subtract IME inset.
        int expectedHeightMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                SCREEN_WIDTH - imeInsetAmount, View.MeasureSpec.EXACTLY);

        mKeyguardSecurityContainer.onApplyWindowInsets(insets);
        mKeyguardSecurityContainer.measure(FAKE_MEASURE_SPEC, FAKE_MEASURE_SPEC);
        verify(mSecurityViewFlipper).measure(FAKE_MEASURE_SPEC, expectedHeightMeasureSpec);
    }

    @Test
    public void onMeasure_respectsViewInsets_largerSystembar() {
        int imeInsetAmount = 0;
        int systemBarInsetAmount = 10;

        mKeyguardSecurityContainer.initMode(MODE_DEFAULT, mGlobalSettings, mFalsingManager,
                mUserSwitcherController);

        Insets imeInset = Insets.of(0, 0, 0, imeInsetAmount);
        Insets systemBarInset = Insets.of(0, 0, 0, systemBarInsetAmount);

        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(ime(), imeInset)
                .setInsetsIgnoringVisibility(systemBars(), systemBarInset)
                .build();

        int expectedHeightMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                SCREEN_WIDTH - systemBarInsetAmount, View.MeasureSpec.EXACTLY);

        mKeyguardSecurityContainer.onApplyWindowInsets(insets);
        mKeyguardSecurityContainer.measure(FAKE_MEASURE_SPEC, FAKE_MEASURE_SPEC);
        verify(mSecurityViewFlipper).measure(FAKE_MEASURE_SPEC, expectedHeightMeasureSpec);
    }

    private void setupForUpdateKeyguardPosition(boolean oneHandedMode) {
        int mode = oneHandedMode ? MODE_ONE_HANDED : MODE_DEFAULT;
        mKeyguardSecurityContainer.initMode(mode, mGlobalSettings, mFalsingManager,
                mUserSwitcherController);

        mKeyguardSecurityContainer.measure(FAKE_MEASURE_SPEC, FAKE_MEASURE_SPEC);
        mKeyguardSecurityContainer.layout(0, 0, SCREEN_WIDTH, SCREEN_WIDTH);

        // Clear any interactions with the mock so we know the interactions definitely come from the
        // below testing.
        reset(mSecurityViewFlipper);
    }

    @Test
    public void updatePosition_movesKeyguard() {
        setupForUpdateKeyguardPosition(/* oneHandedMode= */ true);
        mKeyguardSecurityContainer.updatePositionByTouchX(
                mKeyguardSecurityContainer.getWidth() - 1f);

        verify(mGlobalSettings).putInt(Settings.Global.ONE_HANDED_KEYGUARD_SIDE,
                Settings.Global.ONE_HANDED_KEYGUARD_SIDE_RIGHT);
        verify(mSecurityViewFlipper).setTranslationX(
                mKeyguardSecurityContainer.getWidth() - mSecurityViewFlipper.getWidth());

        mKeyguardSecurityContainer.updatePositionByTouchX(1f);
        verify(mGlobalSettings).putInt(Settings.Global.ONE_HANDED_KEYGUARD_SIDE,
                Settings.Global.ONE_HANDED_KEYGUARD_SIDE_LEFT);

        verify(mSecurityViewFlipper).setTranslationX(0.0f);
    }

    @Test
    public void updatePosition_doesntMoveTwoHandedKeyguard() {
        setupForUpdateKeyguardPosition(/* oneHandedMode= */ false);

        mKeyguardSecurityContainer.updatePositionByTouchX(
                mKeyguardSecurityContainer.getWidth() - 1f);
        verify(mSecurityViewFlipper, never()).setTranslationX(anyInt());

        mKeyguardSecurityContainer.updatePositionByTouchX(1f);
        verify(mSecurityViewFlipper, never()).setTranslationX(anyInt());
    }

    @Test
    public void testUserSwitcherModeViewGravityLandscape() {
        // GIVEN one user has been setup and in landscape
        when(mUserSwitcherController.getUsers()).thenReturn(buildUserRecords(1));
        Configuration config = new Configuration();
        config.orientation = Configuration.ORIENTATION_LANDSCAPE;
        when(getContext().getResources().getConfiguration()).thenReturn(config);

        // WHEN UserSwitcherViewMode is initialized and config has changed
        setupUserSwitcher();
        reset(mSecurityViewFlipper);
        when(mSecurityViewFlipper.getLayoutParams()).thenReturn(mSecurityViewFlipperLayoutParams);
        mKeyguardSecurityContainer.onConfigurationChanged(config);

        // THEN views are oriented side by side
        verify(mSecurityViewFlipper).setLayoutParams(mLayoutCaptor.capture());
        assertThat(mLayoutCaptor.getValue().gravity).isEqualTo(Gravity.RIGHT | Gravity.BOTTOM);
        ViewGroup userSwitcher = mKeyguardSecurityContainer.findViewById(
                R.id.keyguard_bouncer_user_switcher);
        assertThat(((FrameLayout.LayoutParams) userSwitcher.getLayoutParams()).gravity)
                .isEqualTo(Gravity.LEFT | Gravity.CENTER_VERTICAL);
    }

    @Test
    public void testUserSwitcherModeViewGravityPortrait() {
        // GIVEN one user has been setup and in landscape
        when(mUserSwitcherController.getUsers()).thenReturn(buildUserRecords(1));
        Configuration config = new Configuration();
        config.orientation = Configuration.ORIENTATION_PORTRAIT;
        when(getContext().getResources().getConfiguration()).thenReturn(config);

        // WHEN UserSwitcherViewMode is initialized and config has changed
        setupUserSwitcher();
        reset(mSecurityViewFlipper);
        when(mSecurityViewFlipper.getLayoutParams()).thenReturn(mSecurityViewFlipperLayoutParams);
        mKeyguardSecurityContainer.onConfigurationChanged(config);

        // THEN views are both centered horizontally
        verify(mSecurityViewFlipper).setLayoutParams(mLayoutCaptor.capture());
        assertThat(mLayoutCaptor.getValue().gravity).isEqualTo(Gravity.CENTER_HORIZONTAL);
        ViewGroup userSwitcher = mKeyguardSecurityContainer.findViewById(
                R.id.keyguard_bouncer_user_switcher);
        assertThat(((FrameLayout.LayoutParams) userSwitcher.getLayoutParams()).gravity)
                .isEqualTo(Gravity.CENTER_HORIZONTAL);
    }

    @Test
    public void testLessThanTwoUsersDoesNotAllowDropDown() {
        // GIVEN one user has been setup
        when(mUserSwitcherController.getUsers()).thenReturn(buildUserRecords(1));

        // WHEN UserSwitcherViewMode is initialized
        setupUserSwitcher();

        // THEN the UserSwitcher anchor should not be clickable
        ViewGroup anchor = mKeyguardSecurityContainer.findViewById(R.id.user_switcher_anchor);
        assertThat(anchor.isClickable()).isFalse();
    }

    @Test
    public void testTwoOrMoreUsersDoesAllowDropDown() {
        // GIVEN one user has been setup
        when(mUserSwitcherController.getUsers()).thenReturn(buildUserRecords(2));

        // WHEN UserSwitcherViewMode is initialized
        setupUserSwitcher();

        // THEN the UserSwitcher anchor should not be clickable
        ViewGroup anchor = mKeyguardSecurityContainer.findViewById(R.id.user_switcher_anchor);
        assertThat(anchor.isClickable()).isTrue();
    }

    private void setupUserSwitcher() {
        mKeyguardSecurityContainer.initMode(KeyguardSecurityContainer.MODE_USER_SWITCHER,
                mGlobalSettings, mFalsingManager, mUserSwitcherController);
    }

    private ArrayList<UserRecord> buildUserRecords(int count) {
        ArrayList<UserRecord> users = new ArrayList<>();
        for (int i = 0; i < count; ++i) {
            UserInfo info = new UserInfo(i /* id */, "Name: " + i, null /* iconPath */,
                    0 /* flags */);
            users.add(new UserRecord(info, null, false /* isGuest */, false /* isCurrent */,
                    false /* isAddUser */, false /* isRestricted */, true /* isSwitchToEnabled */,
                    false /* isAddSupervisedUser */));
        }
        return users;
    }
}

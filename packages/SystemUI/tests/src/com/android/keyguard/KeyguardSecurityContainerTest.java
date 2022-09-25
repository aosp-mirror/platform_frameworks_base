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

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.provider.Settings.Global.ONE_HANDED_KEYGUARD_SIDE;
import static android.provider.Settings.Global.ONE_HANDED_KEYGUARD_SIDE_LEFT;
import static android.provider.Settings.Global.ONE_HANDED_KEYGUARD_SIDE_RIGHT;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.systemBars;

import static com.android.keyguard.KeyguardSecurityContainer.MODE_DEFAULT;
import static com.android.keyguard.KeyguardSecurityContainer.MODE_ONE_HANDED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.user.data.source.UserRecord;
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

    private static final int VIEW_WIDTH = 1600;

    private int mScreenWidth;
    private int mFakeMeasureSpec;

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();

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

        when(mSecurityViewFlipper.getLayoutParams()).thenReturn(mSecurityViewFlipperLayoutParams);
        mKeyguardSecurityContainer = new KeyguardSecurityContainer(getContext());
        mKeyguardSecurityContainer.mSecurityViewFlipper = mSecurityViewFlipper;
        mKeyguardSecurityContainer.addView(mSecurityViewFlipper, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        when(mUserSwitcherController.getCurrentUserName()).thenReturn("Test User");
        when(mUserSwitcherController.isKeyguardShowing()).thenReturn(true);

        mScreenWidth = getUiDevice().getDisplayWidth();
        mFakeMeasureSpec = View
                .MeasureSpec.makeMeasureSpec(mScreenWidth, View.MeasureSpec.EXACTLY);
    }

    @Test
    public void onMeasure_usesHalfWidthWithOneHandedModeEnabled() {
        mKeyguardSecurityContainer.initMode(MODE_ONE_HANDED, mGlobalSettings, mFalsingManager,
                mUserSwitcherController);

        int halfWidthMeasureSpec =
                View.MeasureSpec.makeMeasureSpec(mScreenWidth / 2, View.MeasureSpec.EXACTLY);
        mKeyguardSecurityContainer.onMeasure(mFakeMeasureSpec, mFakeMeasureSpec);

        verify(mSecurityViewFlipper).measure(halfWidthMeasureSpec, mFakeMeasureSpec);
    }

    @Test
    public void onMeasure_usesFullWidthWithOneHandedModeDisabled() {
        mKeyguardSecurityContainer.initMode(MODE_DEFAULT, mGlobalSettings, mFalsingManager,
                mUserSwitcherController);

        mKeyguardSecurityContainer.measure(mFakeMeasureSpec, mFakeMeasureSpec);
        verify(mSecurityViewFlipper).measure(mFakeMeasureSpec, mFakeMeasureSpec);
    }

    @Test
    public void onMeasure_respectsViewInsets() {
        int paddingBottom = getContext().getResources()
                .getDimensionPixelSize(R.dimen.keyguard_security_view_bottom_margin);
        int imeInsetAmount = paddingBottom + 1;
        int systemBarInsetAmount = 0;

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
                mScreenWidth - imeInsetAmount, View.MeasureSpec.EXACTLY);

        mKeyguardSecurityContainer.onApplyWindowInsets(insets);
        mKeyguardSecurityContainer.measure(mFakeMeasureSpec, mFakeMeasureSpec);
        verify(mSecurityViewFlipper).measure(mFakeMeasureSpec, expectedHeightMeasureSpec);
    }

    @Test
    public void onMeasure_respectsViewInsets_largerSystembar() {
        int imeInsetAmount = 0;
        int paddingBottom = getContext().getResources()
                .getDimensionPixelSize(R.dimen.keyguard_security_view_bottom_margin);
        int systemBarInsetAmount = paddingBottom + 1;

        mKeyguardSecurityContainer.initMode(MODE_DEFAULT, mGlobalSettings, mFalsingManager,
                mUserSwitcherController);

        Insets imeInset = Insets.of(0, 0, 0, imeInsetAmount);
        Insets systemBarInset = Insets.of(0, 0, 0, systemBarInsetAmount);

        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(ime(), imeInset)
                .setInsetsIgnoringVisibility(systemBars(), systemBarInset)
                .build();

        int expectedHeightMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                mScreenWidth - systemBarInsetAmount, View.MeasureSpec.EXACTLY);

        mKeyguardSecurityContainer.onApplyWindowInsets(insets);
        mKeyguardSecurityContainer.measure(mFakeMeasureSpec, mFakeMeasureSpec);
        verify(mSecurityViewFlipper).measure(mFakeMeasureSpec, expectedHeightMeasureSpec);
    }

    private void setupForUpdateKeyguardPosition(boolean oneHandedMode) {
        int mode = oneHandedMode ? MODE_ONE_HANDED : MODE_DEFAULT;
        mKeyguardSecurityContainer.initMode(mode, mGlobalSettings, mFalsingManager,
                mUserSwitcherController);

        mKeyguardSecurityContainer.measure(mFakeMeasureSpec, mFakeMeasureSpec);
        mKeyguardSecurityContainer.layout(0, 0, mScreenWidth, mScreenWidth);

        // Clear any interactions with the mock so we know the interactions definitely come from the
        // below testing.
        reset(mSecurityViewFlipper);
    }

    @Test
    public void updatePosition_movesKeyguard() {
        setupForUpdateKeyguardPosition(/* oneHandedMode= */ true);
        mKeyguardSecurityContainer.updatePositionByTouchX(
                mKeyguardSecurityContainer.getWidth() - 1f);

        verify(mGlobalSettings).putInt(ONE_HANDED_KEYGUARD_SIDE, ONE_HANDED_KEYGUARD_SIDE_RIGHT);
        assertSecurityTranslationX(
                mKeyguardSecurityContainer.getWidth() - mSecurityViewFlipper.getWidth());

        mKeyguardSecurityContainer.updatePositionByTouchX(1f);
        verify(mGlobalSettings).putInt(ONE_HANDED_KEYGUARD_SIDE, ONE_HANDED_KEYGUARD_SIDE_LEFT);

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
    public void testUserSwitcherModeViewPositionLandscape() {
        // GIVEN one user has been setup and in landscape
        when(mUserSwitcherController.getUsers()).thenReturn(buildUserRecords(1));
        Configuration landscapeConfig = configuration(ORIENTATION_LANDSCAPE);
        when(getContext().getResources().getConfiguration()).thenReturn(landscapeConfig);

        // WHEN UserSwitcherViewMode is initialized and config has changed
        setupUserSwitcher();
        mKeyguardSecurityContainer.onConfigurationChanged(landscapeConfig);

        // THEN views are oriented side by side
        assertSecurityGravity(Gravity.LEFT | Gravity.BOTTOM);
        assertUserSwitcherGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        assertSecurityTranslationX(
                mKeyguardSecurityContainer.getWidth() - mSecurityViewFlipper.getWidth());
        assertUserSwitcherTranslationX(0f);

    }

    @Test
    public void testUserSwitcherModeViewGravityPortrait() {
        // GIVEN one user has been setup and in landscape
        when(mUserSwitcherController.getUsers()).thenReturn(buildUserRecords(1));
        Configuration portraitConfig = configuration(ORIENTATION_PORTRAIT);
        when(getContext().getResources().getConfiguration()).thenReturn(portraitConfig);

        // WHEN UserSwitcherViewMode is initialized and config has changed
        setupUserSwitcher();
        reset(mSecurityViewFlipper);
        when(mSecurityViewFlipper.getLayoutParams()).thenReturn(mSecurityViewFlipperLayoutParams);
        mKeyguardSecurityContainer.onConfigurationChanged(portraitConfig);

        // THEN views are both centered horizontally
        assertSecurityGravity(Gravity.CENTER_HORIZONTAL);
        assertUserSwitcherGravity(Gravity.CENTER_HORIZONTAL);
        assertSecurityTranslationX(0);
        assertUserSwitcherTranslationX(0);
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
        ArrayList<UserRecord> records = buildUserRecords(2);
        when(mUserSwitcherController.getCurrentUserRecord()).thenReturn(records.get(0));
        when(mUserSwitcherController.getUsers()).thenReturn(records);

        // WHEN UserSwitcherViewMode is initialized
        setupUserSwitcher();

        // THEN the UserSwitcher anchor should not be clickable
        ViewGroup anchor = mKeyguardSecurityContainer.findViewById(R.id.user_switcher_anchor);
        assertThat(anchor.isClickable()).isTrue();
    }

    @Test
    public void testTouchesAreRecognizedAsBeingOnTheOtherSideOfSecurity() {
        setupUserSwitcher();
        setViewWidth(VIEW_WIDTH);

        // security is on the right side by default
        assertThat(mKeyguardSecurityContainer.isTouchOnTheOtherSideOfSecurity(
                touchEventLeftSide())).isTrue();
        assertThat(mKeyguardSecurityContainer.isTouchOnTheOtherSideOfSecurity(
                touchEventRightSide())).isFalse();

        // move security to the left side
        when(mGlobalSettings.getInt(any(), anyInt())).thenReturn(ONE_HANDED_KEYGUARD_SIDE_LEFT);
        mKeyguardSecurityContainer.onConfigurationChanged(new Configuration());

        assertThat(mKeyguardSecurityContainer.isTouchOnTheOtherSideOfSecurity(
                touchEventLeftSide())).isFalse();
        assertThat(mKeyguardSecurityContainer.isTouchOnTheOtherSideOfSecurity(
                touchEventRightSide())).isTrue();
    }

    @Test
    public void testSecuritySwitchesSidesInLandscapeUserSwitcherMode() {
        when(getContext().getResources().getConfiguration())
                .thenReturn(configuration(ORIENTATION_LANDSCAPE));
        setupUserSwitcher();

        // switch sides
        when(mGlobalSettings.getInt(any(), anyInt())).thenReturn(ONE_HANDED_KEYGUARD_SIDE_LEFT);
        mKeyguardSecurityContainer.onConfigurationChanged(new Configuration());

        assertSecurityTranslationX(0);
        assertUserSwitcherTranslationX(
                mKeyguardSecurityContainer.getWidth() - mSecurityViewFlipper.getWidth());
    }

    private Configuration configuration(@Configuration.Orientation int orientation) {
        Configuration config = new Configuration();
        config.orientation = orientation;
        return config;
    }

    private void assertSecurityTranslationX(float translation) {
        verify(mSecurityViewFlipper).setTranslationX(translation);
    }

    private void assertUserSwitcherTranslationX(float translation) {
        ViewGroup userSwitcher = mKeyguardSecurityContainer.findViewById(
                R.id.keyguard_bouncer_user_switcher);
        assertThat(userSwitcher.getTranslationX()).isEqualTo(translation);
    }

    private void assertUserSwitcherGravity(@Gravity.GravityFlags int gravity) {
        ViewGroup userSwitcher = mKeyguardSecurityContainer.findViewById(
                R.id.keyguard_bouncer_user_switcher);
        assertThat(((FrameLayout.LayoutParams) userSwitcher.getLayoutParams()).gravity)
                .isEqualTo(gravity);
    }

    private void assertSecurityGravity(@Gravity.GravityFlags int gravity) {
        verify(mSecurityViewFlipper, atLeastOnce()).setLayoutParams(mLayoutCaptor.capture());
        assertThat(mLayoutCaptor.getValue().gravity).isEqualTo(gravity);
    }

    private void setViewWidth(int width) {
        mKeyguardSecurityContainer.setRight(width);
        mKeyguardSecurityContainer.setLeft(0);
    }

    private MotionEvent touchEventLeftSide() {
        return MotionEvent.obtain(
                /* downTime= */0,
                /* eventTime= */0,
                MotionEvent.ACTION_DOWN,
                /* x= */VIEW_WIDTH / 3f,
                /* y= */0,
                /* metaState= */0);
    }

    private MotionEvent touchEventRightSide() {
        return MotionEvent.obtain(
                /* downTime= */0,
                /* eventTime= */0,
                MotionEvent.ACTION_DOWN,
                /* x= */(VIEW_WIDTH / 3f) * 2,
                /* y= */0,
                /* metaState= */0);
    }

    private void setupUserSwitcher() {
        when(mGlobalSettings.getInt(any(), anyInt())).thenReturn(ONE_HANDED_KEYGUARD_SIDE_RIGHT);
        mKeyguardSecurityContainer.initMode(KeyguardSecurityContainer.MODE_USER_SWITCHER,
                mGlobalSettings, mFalsingManager, mUserSwitcherController);
        // reset mSecurityViewFlipper so setup doesn't influence test verifications
        reset(mSecurityViewFlipper);
        when(mSecurityViewFlipper.getLayoutParams()).thenReturn(mSecurityViewFlipperLayoutParams);
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

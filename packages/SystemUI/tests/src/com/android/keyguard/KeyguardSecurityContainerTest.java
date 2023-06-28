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
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.systemBars;

import static androidx.constraintlayout.widget.ConstraintSet.CHAIN_SPREAD;
import static androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT;
import static androidx.constraintlayout.widget.ConstraintSet.PARENT_ID;
import static androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT;

import static com.android.keyguard.KeyguardSecurityContainer.MODE_DEFAULT;
import static com.android.keyguard.KeyguardSecurityContainer.MODE_ONE_HANDED;
import static com.android.keyguard.KeyguardSecurityContainer.MODE_USER_SWITCHER;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;

import androidx.constraintlayout.widget.ConstraintSet;
import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingA11yDelegate;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.user.data.source.UserRecord;
import com.android.systemui.util.settings.GlobalSettings;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper()
public class KeyguardSecurityContainerTest extends SysuiTestCase {

    private static final int VIEW_WIDTH = 1600;
    private static final int VIEW_HEIGHT = 900;

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();

    private KeyguardSecurityViewFlipper mSecurityViewFlipper;
    @Mock
    private GlobalSettings mGlobalSettings;
    @Mock
    private FalsingManager mFalsingManager;
    @Mock
    private UserSwitcherController mUserSwitcherController;
    @Mock
    private FalsingA11yDelegate mFalsingA11yDelegate;

    private KeyguardSecurityContainer mKeyguardSecurityContainer;

    @Before
    public void setup() {
        // Needed here, otherwise when mKeyguardSecurityContainer is created below, it'll cache
        // the real references (rather than the TestableResources that this call creates).
        mContext.ensureTestableResources();

        mSecurityViewFlipper = new KeyguardSecurityViewFlipper(getContext());
        mSecurityViewFlipper.setId(View.generateViewId());
        mKeyguardSecurityContainer = new KeyguardSecurityContainer(getContext());
        mKeyguardSecurityContainer.setRight(VIEW_WIDTH);
        mKeyguardSecurityContainer.setLeft(0);
        mKeyguardSecurityContainer.setTop(0);
        mKeyguardSecurityContainer.setBottom(VIEW_HEIGHT);
        mKeyguardSecurityContainer.setId(View.generateViewId());
        mKeyguardSecurityContainer.mSecurityViewFlipper = mSecurityViewFlipper;
        mKeyguardSecurityContainer.addView(mSecurityViewFlipper, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        when(mUserSwitcherController.getCurrentUserName()).thenReturn("Test User");
        when(mUserSwitcherController.isKeyguardShowing()).thenReturn(true);
    }

    @Test
    public void testOnApplyWindowInsets() {
        int paddingBottom = getContext().getResources()
                .getDimensionPixelSize(R.dimen.keyguard_security_view_bottom_margin);
        int imeInsetAmount = paddingBottom + 1;
        int systemBarInsetAmount = 0;
        initMode(MODE_DEFAULT);

        Insets imeInset = Insets.of(0, 0, 0, imeInsetAmount);
        Insets systemBarInset = Insets.of(0, 0, 0, systemBarInsetAmount);

        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(ime(), imeInset)
                .setInsetsIgnoringVisibility(systemBars(), systemBarInset)
                .build();

        mKeyguardSecurityContainer.onApplyWindowInsets(insets);
        assertThat(mKeyguardSecurityContainer.getPaddingBottom()).isEqualTo(imeInsetAmount);
    }

    @Test
    public void testOnApplyWindowInsets_largerSystembar() {
        int imeInsetAmount = 0;
        int paddingBottom = getContext().getResources()
                .getDimensionPixelSize(R.dimen.keyguard_security_view_bottom_margin);
        int systemBarInsetAmount = paddingBottom + 1;

        initMode(MODE_DEFAULT);

        Insets imeInset = Insets.of(0, 0, 0, imeInsetAmount);
        Insets systemBarInset = Insets.of(0, 0, 0, systemBarInsetAmount);

        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(ime(), imeInset)
                .setInsetsIgnoringVisibility(systemBars(), systemBarInset)
                .build();

        mKeyguardSecurityContainer.onApplyWindowInsets(insets);
        assertThat(mKeyguardSecurityContainer.getPaddingBottom()).isEqualTo(systemBarInsetAmount);
    }

    @Test
    public void testOnApplyWindowInsets_disappearAnimation_paddingNotSet() {
        int paddingBottom = getContext().getResources()
                .getDimensionPixelSize(R.dimen.keyguard_security_view_bottom_margin);
        int imeInsetAmount = paddingBottom + 1;
        int systemBarInsetAmount = 0;
        initMode(MODE_DEFAULT);

        Insets imeInset = Insets.of(0, 0, 0, imeInsetAmount);
        Insets systemBarInset = Insets.of(0, 0, 0, systemBarInsetAmount);

        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(ime(), imeInset)
                .setInsetsIgnoringVisibility(systemBars(), systemBarInset)
                .build();

        ensureViewFlipperIsMocked();
        mKeyguardSecurityContainer.startDisappearAnimation(
                KeyguardSecurityModel.SecurityMode.Password);
        mKeyguardSecurityContainer.onApplyWindowInsets(insets);
        assertThat(mKeyguardSecurityContainer.getPaddingBottom()).isNotEqualTo(imeInsetAmount);
    }

    @Test
    public void testDefaultViewMode() {
        initMode(MODE_ONE_HANDED);
        initMode(MODE_DEFAULT);
        ConstraintSet.Constraint viewFlipperConstraint =
                getViewConstraint(mSecurityViewFlipper.getId());
        assertThat(viewFlipperConstraint.layout.topToTop).isEqualTo(PARENT_ID);
        assertThat(viewFlipperConstraint.layout.startToStart).isEqualTo(PARENT_ID);
        assertThat(viewFlipperConstraint.layout.endToEnd).isEqualTo(PARENT_ID);
        assertThat(viewFlipperConstraint.layout.bottomToBottom).isEqualTo(PARENT_ID);
    }

    @Test
    public void updatePosition_movesKeyguard() {
        setupForUpdateKeyguardPosition(/* oneHandedMode= */ true);
        mKeyguardSecurityContainer.updatePositionByTouchX(
                mKeyguardSecurityContainer.getWidth() - 1f);

        verify(mGlobalSettings).putInt(ONE_HANDED_KEYGUARD_SIDE, ONE_HANDED_KEYGUARD_SIDE_RIGHT);
        ConstraintSet.Constraint viewFlipperConstraint =
                getViewConstraint(mSecurityViewFlipper.getId());
        assertThat(viewFlipperConstraint.layout.widthPercent).isEqualTo(0.5f);
        assertThat(viewFlipperConstraint.layout.topToTop).isEqualTo(PARENT_ID);
        assertThat(viewFlipperConstraint.layout.rightToRight).isEqualTo(PARENT_ID);
        assertThat(viewFlipperConstraint.layout.leftToLeft).isEqualTo(-1);

        mKeyguardSecurityContainer.updatePositionByTouchX(1f);
        verify(mGlobalSettings).putInt(ONE_HANDED_KEYGUARD_SIDE, ONE_HANDED_KEYGUARD_SIDE_LEFT);

        viewFlipperConstraint =
                getViewConstraint(mSecurityViewFlipper.getId());
        assertThat(viewFlipperConstraint.layout.widthPercent).isEqualTo(0.5f);
        assertThat(viewFlipperConstraint.layout.topToTop).isEqualTo(PARENT_ID);
        assertThat(viewFlipperConstraint.layout.leftToLeft).isEqualTo(PARENT_ID);
        assertThat(viewFlipperConstraint.layout.rightToRight).isEqualTo(-1);
    }

    @Test
    public void updatePosition_doesntMoveTwoHandedKeyguard() {
        setupForUpdateKeyguardPosition(/* oneHandedMode= */ false);

        mKeyguardSecurityContainer.updatePositionByTouchX(
                mKeyguardSecurityContainer.getWidth() - 1f);
        ConstraintSet.Constraint viewFlipperConstraint =
                getViewConstraint(mSecurityViewFlipper.getId());
        assertThat(viewFlipperConstraint.layout.rightToRight).isEqualTo(-1);
        assertThat(viewFlipperConstraint.layout.leftToLeft).isEqualTo(-1);

        mKeyguardSecurityContainer.updatePositionByTouchX(1f);
        viewFlipperConstraint =
                getViewConstraint(mSecurityViewFlipper.getId());
        assertThat(viewFlipperConstraint.layout.rightToRight).isEqualTo(-1);
        assertThat(viewFlipperConstraint.layout.leftToLeft).isEqualTo(-1);
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

        ConstraintSet.Constraint viewFlipperConstraint =
                getViewConstraint(mSecurityViewFlipper.getId());
        ConstraintSet.Constraint userSwitcherConstraint =
                getViewConstraint(R.id.keyguard_bouncer_user_switcher);
        assertThat(viewFlipperConstraint.layout.rightToRight).isEqualTo(PARENT_ID);
        assertThat(viewFlipperConstraint.layout.leftToRight).isEqualTo(
                R.id.keyguard_bouncer_user_switcher);
        assertThat(userSwitcherConstraint.layout.leftToLeft).isEqualTo(PARENT_ID);
        assertThat(userSwitcherConstraint.layout.rightToLeft).isEqualTo(
                mSecurityViewFlipper.getId());
        assertThat(viewFlipperConstraint.layout.topToTop).isEqualTo(PARENT_ID);
        assertThat(viewFlipperConstraint.layout.bottomToBottom).isEqualTo(PARENT_ID);
        assertThat(userSwitcherConstraint.layout.topToTop).isEqualTo(PARENT_ID);
        assertThat(userSwitcherConstraint.layout.bottomToBottom).isEqualTo(PARENT_ID);
        assertThat(viewFlipperConstraint.layout.horizontalChainStyle).isEqualTo(CHAIN_SPREAD);
        assertThat(userSwitcherConstraint.layout.horizontalChainStyle).isEqualTo(CHAIN_SPREAD);
        assertThat(viewFlipperConstraint.layout.mHeight).isEqualTo(MATCH_CONSTRAINT);
        assertThat(userSwitcherConstraint.layout.mHeight).isEqualTo(MATCH_CONSTRAINT);
    }

    @Test
    public void testUserSwitcherModeViewPositionPortrait() {
        // GIVEN one user has been setup and in landscape
        when(mUserSwitcherController.getUsers()).thenReturn(buildUserRecords(1));
        Configuration portraitConfig = configuration(ORIENTATION_PORTRAIT);
        when(getContext().getResources().getConfiguration()).thenReturn(portraitConfig);

        // WHEN UserSwitcherViewMode is initialized and config has changed
        setupUserSwitcher();
        mKeyguardSecurityContainer.onConfigurationChanged(portraitConfig);

        ConstraintSet.Constraint viewFlipperConstraint =
                getViewConstraint(mSecurityViewFlipper.getId());
        ConstraintSet.Constraint userSwitcherConstraint =
                getViewConstraint(R.id.keyguard_bouncer_user_switcher);

        assertThat(viewFlipperConstraint.layout.topToBottom).isEqualTo(
                R.id.keyguard_bouncer_user_switcher);
        assertThat(viewFlipperConstraint.layout.bottomToBottom).isEqualTo(PARENT_ID);
        assertThat(userSwitcherConstraint.layout.topToTop).isEqualTo(PARENT_ID);
        assertThat(userSwitcherConstraint.layout.bottomToTop).isEqualTo(
                mSecurityViewFlipper.getId());
        assertThat(userSwitcherConstraint.layout.topMargin).isEqualTo(
                getContext().getResources().getDimensionPixelSize(
                        R.dimen.bouncer_user_switcher_y_trans));
        assertThat(viewFlipperConstraint.layout.leftToLeft).isEqualTo(PARENT_ID);
        assertThat(viewFlipperConstraint.layout.rightToRight).isEqualTo(PARENT_ID);
        assertThat(userSwitcherConstraint.layout.leftToLeft).isEqualTo(PARENT_ID);
        assertThat(userSwitcherConstraint.layout.rightToRight).isEqualTo(PARENT_ID);
        assertThat(viewFlipperConstraint.layout.verticalChainStyle).isEqualTo(CHAIN_SPREAD);
        assertThat(userSwitcherConstraint.layout.verticalChainStyle).isEqualTo(CHAIN_SPREAD);
        assertThat(viewFlipperConstraint.layout.mHeight).isEqualTo(MATCH_CONSTRAINT);
        assertThat(userSwitcherConstraint.layout.mHeight).isEqualTo(WRAP_CONTENT);
        assertThat(userSwitcherConstraint.layout.mWidth).isEqualTo(WRAP_CONTENT);
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
    public void testOnDensityOrFontScaleChanged() {
        setupUserSwitcher();
        View oldUserSwitcher = mKeyguardSecurityContainer.findViewById(
                R.id.keyguard_bouncer_user_switcher);
        mKeyguardSecurityContainer.onDensityOrFontScaleChanged();
        View newUserSwitcher = mKeyguardSecurityContainer.findViewById(
                R.id.keyguard_bouncer_user_switcher);
        assertThat(oldUserSwitcher).isNotEqualTo(newUserSwitcher);
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

        ConstraintSet.Constraint viewFlipperConstraint = getViewConstraint(
                mSecurityViewFlipper.getId());
        assertThat(viewFlipperConstraint.layout.leftToLeft).isEqualTo(PARENT_ID);
    }

    @Test
    public void testPlayBackAnimation() {
        OnBackAnimationCallback backCallback = mKeyguardSecurityContainer.getBackCallback();
        backCallback.onBackStarted(createBackEvent(0, 0));
        mKeyguardSecurityContainer.getBackCallback().onBackProgressed(
                createBackEvent(0, 1));
        assertThat(mKeyguardSecurityContainer.getScaleX()).isEqualTo(
                KeyguardSecurityContainer.MIN_BACK_SCALE);
        assertThat(mKeyguardSecurityContainer.getScaleY()).isEqualTo(
                KeyguardSecurityContainer.MIN_BACK_SCALE);

        // reset scale
        mKeyguardSecurityContainer.resetScale();
        assertThat(mKeyguardSecurityContainer.getScaleX()).isEqualTo(1);
        assertThat(mKeyguardSecurityContainer.getScaleY()).isEqualTo(1);
    }

    @Test
    public void testDisappearAnimationPassword() {
        ensureViewFlipperIsMocked();
        KeyguardPasswordView keyguardPasswordView = mock(KeyguardPasswordView.class);
        when(mSecurityViewFlipper.getSecurityView()).thenReturn(keyguardPasswordView);

        mKeyguardSecurityContainer
                .startDisappearAnimation(KeyguardSecurityModel.SecurityMode.Password);
        verify(keyguardPasswordView).setDisappearAnimationListener(any());
    }

    private BackEvent createBackEvent(float touchX, float progress) {
        return new BackEvent(0, 0, progress, BackEvent.EDGE_LEFT);
    }

    private Configuration configuration(@Configuration.Orientation int orientation) {
        Configuration config = new Configuration();
        config.orientation = orientation;
        return config;
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
        initMode(MODE_USER_SWITCHER);
    }

    private ArrayList<UserRecord> buildUserRecords(int count) {
        ArrayList<UserRecord> users = new ArrayList<>();
        for (int i = 0; i < count; ++i) {
            UserInfo info = new UserInfo(i /* id */, "Name: " + i, null /* iconPath */,
                    0 /* flags */);
            users.add(new UserRecord(info, null, false /* isGuest */, false /* isCurrent */,
                    false /* isAddUser */, false /* isRestricted */, true /* isSwitchToEnabled */,
                    false /* isAddSupervisedUser */, null /* enforcedAdmin */,
                    false /* isManageUsers */));
        }
        return users;
    }

    private void setupForUpdateKeyguardPosition(boolean oneHandedMode) {
        int mode = oneHandedMode ? MODE_ONE_HANDED : MODE_DEFAULT;
        initMode(mode);
    }

    /** Get the ConstraintLayout constraint of the view. */
    private ConstraintSet.Constraint getViewConstraint(int viewId) {
        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.clone(mKeyguardSecurityContainer);
        return constraintSet.getConstraint(viewId);
    }

    private void initMode(int mode) {
        mKeyguardSecurityContainer.initMode(mode, mGlobalSettings, mFalsingManager,
                mUserSwitcherController, () -> {
                }, mFalsingA11yDelegate);
    }

    private void ensureViewFlipperIsMocked() {
        mSecurityViewFlipper = mock(KeyguardSecurityViewFlipper.class);
        KeyguardPasswordView keyguardPasswordView = mock(KeyguardPasswordView.class);
        when(mSecurityViewFlipper.getSecurityView()).thenReturn(keyguardPasswordView);
        mKeyguardSecurityContainer.mSecurityViewFlipper = mSecurityViewFlipper;
    }

}

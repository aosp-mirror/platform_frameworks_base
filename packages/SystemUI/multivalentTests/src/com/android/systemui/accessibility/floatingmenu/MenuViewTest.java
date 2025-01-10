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

package com.android.systemui.accessibility.floatingmenu;

import static android.app.UiModeManager.MODE_NIGHT_YES;

import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.accessibility.common.ShortcutConstants;
import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.settingslib.bluetooth.HearingAidDeviceManager;
import com.android.systemui.Flags;
import com.android.systemui.Prefs;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.accessibility.utils.TestUtils;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

/** Tests for {@link MenuView}. */
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class MenuViewTest extends SysuiTestCase {
    private static final int INDEX_MENU_ITEM = 0;
    private int mNightMode;
    private UiModeManager mUiModeManager;
    private MenuView mMenuView;
    private MenuView mMenuViewSpy;
    private String mLastPosition;
    private MenuViewAppearance mStubMenuViewAppearance;
    private MenuViewModel mMenuViewModel;
    private final List<String> mShortcutTargets = new ArrayList<>();

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private AccessibilityManager mAccessibilityManager;

    @Mock
    private HearingAidDeviceManager mHearingAidDeviceManager;

    @Mock
    private MenuView.OnTargetFeaturesChangeListener mOnTargetFeaturesChangeListener;

    private SysuiTestableContext mSpyContext;

    @Before
    public void setUp() throws Exception {
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
        mNightMode = mUiModeManager.getNightMode();
        mUiModeManager.setNightMode(MODE_NIGHT_YES);

        // Programmatically update the resource's configuration to night mode to reduce flakiness
        Configuration nightConfig = new Configuration(mContext.getResources().getConfiguration());
        nightConfig.uiMode = Configuration.UI_MODE_NIGHT_YES;
        mContext.getResources().updateConfiguration(nightConfig,
                mContext.getResources().getDisplayMetrics(), null);

        mSpyContext = spy(mContext);
        doNothing().when(mSpyContext).startActivity(any());

        mContext.addMockSystemService(Context.ACCESSIBILITY_SERVICE, mAccessibilityManager);
        mShortcutTargets.add(MAGNIFICATION_CONTROLLER_NAME);
        doReturn(mShortcutTargets)
                .when(mAccessibilityManager)
                .getAccessibilityShortcutTargets(anyInt());

        final SecureSettings secureSettings = TestUtils.mockSecureSettings(mContext);
        mMenuViewModel =
                new MenuViewModel(
                    mContext, mAccessibilityManager, secureSettings, mHearingAidDeviceManager);
        final WindowManager stubWindowManager = mContext.getSystemService(WindowManager.class);
        mStubMenuViewAppearance = new MenuViewAppearance(mSpyContext, stubWindowManager);
        mMenuView =
                new MenuView(mSpyContext, mMenuViewModel, mStubMenuViewAppearance, secureSettings);
        mMenuView.setOnTargetFeaturesChangeListener(mOnTargetFeaturesChangeListener);
        mLastPosition = Prefs.getString(mSpyContext,
                Prefs.Key.ACCESSIBILITY_FLOATING_MENU_POSITION, /* defaultValue= */ null);

        mMenuViewSpy =
                spy(
                        new MenuView(
                                mSpyContext,
                                mMenuViewModel,
                                mStubMenuViewAppearance,
                                secureSettings));
    }

    @Test
    public void onConfigurationChanged_updateViewModel() {
        mMenuViewSpy.onConfigurationChanged(/* newConfig= */ null);

        verify(mMenuViewSpy).loadLayoutResources();
    }

    @Test
    public void insetsOnDarkTheme_menuOnLeft_matchInsets() {
        // In dark theme, the inset is not 0 to avoid weird spacing issue between the menu and
        // the edge of the screen.
        mMenuView.onConfigurationChanged(/* newConfig= */ null);
        final InstantInsetLayerDrawable insetLayerDrawable =
                (InstantInsetLayerDrawable) mMenuView.getBackground();
        final boolean areInsetsMatched = insetLayerDrawable.getLayerInsetLeft(INDEX_MENU_ITEM) != 0
                && insetLayerDrawable.getLayerInsetRight(INDEX_MENU_ITEM) == 0;

        assertThat(areInsetsMatched).isTrue();
    }

    @Test
    public void onDraggingStart_matchInsets() {
        mMenuView.onDraggingStart();
        final InstantInsetLayerDrawable insetLayerDrawable =
                (InstantInsetLayerDrawable) mMenuView.getBackground();

        assertThat(insetLayerDrawable.getLayerInsetLeft(INDEX_MENU_ITEM)).isEqualTo(0);
        assertThat(insetLayerDrawable.getLayerInsetTop(INDEX_MENU_ITEM)).isEqualTo(0);
        assertThat(insetLayerDrawable.getLayerInsetRight(INDEX_MENU_ITEM)).isEqualTo(0);
        assertThat(insetLayerDrawable.getLayerInsetBottom(INDEX_MENU_ITEM)).isEqualTo(0);
    }

    @Test
    public void onAnimationend_updatePositionForSharedPreference() {
        final float percentageX = 0.0f;
        final float percentageY = 0.5f;

        mMenuView.persistPositionAndUpdateEdge(new Position(percentageX, percentageY));
        final String positionString = Prefs.getString(mContext,
                Prefs.Key.ACCESSIBILITY_FLOATING_MENU_POSITION, /* defaultValue= */ null);
        final Position position = Position.fromString(positionString);

        assertThat(position.getPercentageX()).isEqualTo(percentageX);
        assertThat(position.getPercentageY()).isEqualTo(percentageY);
    }

    @Test
    public void onEdgeChangedIfNeeded_moveToLeftEdge_matchRadii() {
        final Rect draggableBounds = mStubMenuViewAppearance.getMenuDraggableBounds();
        mMenuView.setTranslationX(draggableBounds.right);

        mMenuView.setTranslationX(draggableBounds.left);
        mMenuView.onEdgeChangedIfNeeded();
        final float[] radii = getMenuViewGradient().getCornerRadii();

        assertThat(radii[0]).isEqualTo(0.0f);
        assertThat(radii[1]).isEqualTo(0.0f);
        assertThat(radii[6]).isEqualTo(0.0f);
        assertThat(radii[7]).isEqualTo(0.0f);
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_RADII_ANIMATION)
    public void onEdgeChanged_startsRadiiAnimation() {
        final RadiiAnimator radiiAnimator = getRadiiAnimator();
        mMenuView.onEdgeChanged();
        assertThat(radiiAnimator.isStarted()).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_RADII_ANIMATION)
    public void onDraggingStart_startsRadiiAnimation() {
        final RadiiAnimator radiiAnimator = getRadiiAnimator();
        mMenuView.onDraggingStart();
        assertThat(radiiAnimator.isStarted()).isTrue();
    }

    @Test
    @DisableFlags(Flags.FLAG_FLOATING_MENU_NOTIFY_TARGETS_CHANGED_ON_STRICT_DIFF)
    public void onTargetFeaturesChanged_listenerCalled_flagDisabled() {
        // Call show() to start observing the target features change listener.
        mMenuView.show();

        // The target features change listener should be called when the observer is added.
        verify(mOnTargetFeaturesChangeListener, times(1)).onChange(any());

        // When the target features list changes, the listener should be called.
        mMenuViewModel.onTargetFeaturesChanged(
                List.of(
                        new TestAccessibilityTarget(mContext, 123),
                        new TestAccessibilityTarget(mContext, 456)));
        verify(mOnTargetFeaturesChangeListener, times(2)).onChange(any());

        // Double check that when the target features list changes, the listener should be called.
        List<AccessibilityTarget> newFeaturesList =
                List.of(
                        new TestAccessibilityTarget(mContext, 123),
                        new TestAccessibilityTarget(mContext, 789),
                        new TestAccessibilityTarget(mContext, 456));
        mMenuViewModel.onTargetFeaturesChanged(newFeaturesList);
        verify(mOnTargetFeaturesChangeListener, times(3)).onChange(any());

        // When the target features list doesn't change, the listener will still be called.
        mMenuViewModel.onTargetFeaturesChanged(newFeaturesList);
        verify(mOnTargetFeaturesChangeListener, times(4)).onChange(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_FLOATING_MENU_NOTIFY_TARGETS_CHANGED_ON_STRICT_DIFF)
    public void onTargetFeaturesChanged_listenerCalled_flagEnabled() {
        // Call show() to start observing the target features change listener.
        mMenuView.show();

        // The target features change listener should be called when the observer is added.
        verify(mOnTargetFeaturesChangeListener, times(1)).onChange(any());

        // When the target features list changes, the listener should be called.
        mMenuViewModel.onTargetFeaturesChanged(
                List.of(
                        new TestAccessibilityTarget(mContext, 123),
                        new TestAccessibilityTarget(mContext, 456)));
        verify(mOnTargetFeaturesChangeListener, times(2)).onChange(any());

        // Double check that when the target features list changes, the listener should be called.
        List<AccessibilityTarget> newFeaturesList =
                List.of(
                        new TestAccessibilityTarget(mContext, 123),
                        new TestAccessibilityTarget(mContext, 789),
                        new TestAccessibilityTarget(mContext, 456));
        mMenuViewModel.onTargetFeaturesChanged(newFeaturesList);
        verify(mOnTargetFeaturesChangeListener, times(3)).onChange(any());

        // When the target features list doesn't change, the listener should not be called again.
        mMenuViewModel.onTargetFeaturesChanged(newFeaturesList);
        verify(mOnTargetFeaturesChangeListener, times(3)).onChange(any());

        // When the target features list changes order (but the UIDs of the targets don't change),
        // the listener should be called.
        mMenuViewModel.onTargetFeaturesChanged(
                List.of(
                        new TestAccessibilityTarget(mContext, 789),
                        new TestAccessibilityTarget(mContext, 123),
                        new TestAccessibilityTarget(mContext, 456)));
        verify(mOnTargetFeaturesChangeListener, times(4)).onChange(any());
    }

    private InstantInsetLayerDrawable getMenuViewInsetLayer() {
        return (InstantInsetLayerDrawable) mMenuView.getBackground();
    }

    private GradientDrawable getMenuViewGradient() {
        return (GradientDrawable) getMenuViewInsetLayer().getDrawable(INDEX_MENU_ITEM);
    }

    private RadiiAnimator getRadiiAnimator() {
        final RadiiAnimator radiiAnimator = mMenuView.getMenuAnimationController().mRadiiAnimator;
        if (radiiAnimator.isStarted()) {
            radiiAnimator.skipAnimationToEnd();
        }
        assertThat(radiiAnimator.isStarted()).isFalse();
        return radiiAnimator;
    }

    /** Simplified AccessibilityTarget for testing MenuView. */
    private static class TestAccessibilityTarget extends AccessibilityTarget {
        TestAccessibilityTarget(Context context, int uid) {
            // Set fields unused by tests to defaults that allow test compilation.
            super(
                    context,
                    ShortcutConstants.UserShortcutType.SOFTWARE,
                    0,
                    false,
                    MAGNIFICATION_COMPONENT_NAME.flattenToString(),
                    uid,
                    null,
                    null,
                    null);
        }
    }

    @After
    public void tearDown() throws Exception {
        mUiModeManager.setNightMode(mNightMode);
        Prefs.putString(mContext, Prefs.Key.ACCESSIBILITY_FLOATING_MENU_POSITION, mLastPosition);
    }
}

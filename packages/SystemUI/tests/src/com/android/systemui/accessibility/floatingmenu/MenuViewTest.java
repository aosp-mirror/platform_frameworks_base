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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.UiModeManager;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.platform.test.annotations.EnableFlags;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

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

/** Tests for {@link MenuView}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class MenuViewTest extends SysuiTestCase {
    private static final int INDEX_MENU_ITEM = 0;
    private int mNightMode;
    private UiModeManager mUiModeManager;
    private MenuView mMenuView;
    private String mLastPosition;
    private MenuViewAppearance mStubMenuViewAppearance;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private AccessibilityManager mAccessibilityManager;

    private SysuiTestableContext mSpyContext;

    @Before
    public void setUp() throws Exception {
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
        mNightMode = mUiModeManager.getNightMode();
        mUiModeManager.setNightMode(MODE_NIGHT_YES);

        mSpyContext = spy(mContext);
        doNothing().when(mSpyContext).startActivity(any());

        final SecureSettings secureSettings = TestUtils.mockSecureSettings();
        final MenuViewModel stubMenuViewModel = new MenuViewModel(mContext, mAccessibilityManager,
                secureSettings);
        final WindowManager stubWindowManager = mContext.getSystemService(WindowManager.class);
        mStubMenuViewAppearance = new MenuViewAppearance(mSpyContext, stubWindowManager);
        mMenuView = spy(new MenuView(mSpyContext, stubMenuViewModel, mStubMenuViewAppearance,
                secureSettings));
        mLastPosition = Prefs.getString(mSpyContext,
                Prefs.Key.ACCESSIBILITY_FLOATING_MENU_POSITION, /* defaultValue= */ null);
    }

    @Test
    public void onConfigurationChanged_updateViewModel() {
        mMenuView.onConfigurationChanged(/* newConfig= */ null);

        verify(mMenuView).loadLayoutResources();
    }

    @Test
    public void insetsOnDarkTheme_menuOnLeft_matchInsets() {
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

    @After
    public void tearDown() throws Exception {
        mUiModeManager.setNightMode(mNightMode);
        Prefs.putString(mContext, Prefs.Key.ACCESSIBILITY_FLOATING_MENU_POSITION, mLastPosition);
    }
}

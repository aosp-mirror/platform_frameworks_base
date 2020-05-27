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

package com.android.systemui.car.navigationbar;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.keyguard.AlphaOptimizedImageButton;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.tests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class CarNavigationButtonTest extends SysuiTestCase {

    private static final String DEFAULT_BUTTON_ACTIVITY_NAME =
            "com.android.car.carlauncher/.CarLauncher";
    private static final String APP_GRID_BUTTON_ACTIVITY_NAME =
            "com.android.car.carlauncher/.AppGridActivity";
    private static final String BROADCAST_ACTION_NAME =
            "android.car.intent.action.TOGGLE_HVAC_CONTROLS";

    private ActivityManager mActivityManager;
    // LinearLayout with CarNavigationButtons with different configurations.
    private LinearLayout mTestView;
    // Does not have any selection state which is the default configuration.
    private CarNavigationButton mDefaultButton;

    @Before
    public void setUp() {
        mContext = spy(mContext);
        mTestView = (LinearLayout) LayoutInflater.from(mContext).inflate(
                R.layout.car_navigation_button_test, /* root= */ null);
        mDefaultButton = mTestView.findViewById(R.id.default_no_selection_state);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
    }

    @Test
    public void onCreate_iconIsVisible() {
        AlphaOptimizedImageButton icon = mDefaultButton.findViewById(
                R.id.car_nav_button_icon_image);

        assertThat(icon.getDrawable()).isNotNull();
    }

    @Test
    public void onSelected_selectedIconDefined_togglesIcon() {
        mDefaultButton.setSelected(true);
        Drawable selectedIconDrawable = ((AlphaOptimizedImageButton) mDefaultButton.findViewById(
                R.id.car_nav_button_icon_image)).getDrawable();


        mDefaultButton.setSelected(false);
        Drawable unselectedIconDrawable = ((AlphaOptimizedImageButton) mDefaultButton.findViewById(
                R.id.car_nav_button_icon_image)).getDrawable();

        assertThat(selectedIconDrawable).isNotEqualTo(unselectedIconDrawable);
    }

    @Test
    public void onSelected_selectedIconUndefined_displaysSameIcon() {
        CarNavigationButton selectedIconUndefinedButton = mTestView.findViewById(
                R.id.selected_icon_undefined);

        selectedIconUndefinedButton.setSelected(true);
        Drawable selectedIconDrawable = ((AlphaOptimizedImageButton) mDefaultButton.findViewById(
                R.id.car_nav_button_icon_image)).getDrawable();


        selectedIconUndefinedButton.setSelected(false);
        Drawable unselectedIconDrawable = ((AlphaOptimizedImageButton) mDefaultButton.findViewById(
                R.id.car_nav_button_icon_image)).getDrawable();

        assertThat(selectedIconDrawable).isEqualTo(unselectedIconDrawable);
    }

    @Test
    public void onUnselected_doesNotHighlightWhenSelected_applySelectedAlpha() {
        mDefaultButton.setSelected(false);

        assertThat(mDefaultButton.getAlpha()).isEqualTo(
                CarNavigationButton.DEFAULT_SELECTED_ALPHA);
    }

    @Test
    public void onSelected_doesNotHighlightWhenSelected_applySelectedAlpha() {
        mDefaultButton.setSelected(true);

        assertThat(mDefaultButton.getAlpha()).isEqualTo(
                CarNavigationButton.DEFAULT_SELECTED_ALPHA);
    }

    @Test
    public void onUnselected_highlightWhenSelected_applyDefaultUnselectedAlpha() {
        CarNavigationButton highlightWhenSelectedButton = mTestView.findViewById(
                R.id.highlightable_no_more_button);
        highlightWhenSelectedButton.setSelected(false);

        assertThat(highlightWhenSelectedButton.getAlpha()).isEqualTo(
                CarNavigationButton.DEFAULT_UNSELECTED_ALPHA);
    }

    @Test
    public void onSelected_highlightWhenSelected_applyDefaultSelectedAlpha() {
        CarNavigationButton highlightWhenSelectedButton = mTestView.findViewById(
                R.id.highlightable_no_more_button);
        highlightWhenSelectedButton.setSelected(true);

        assertThat(highlightWhenSelectedButton.getAlpha()).isEqualTo(
                CarNavigationButton.DEFAULT_SELECTED_ALPHA);
    }

    @Test
    public void onSelected_doesNotShowMoreWhenSelected_doesNotShowMoreIcon() {
        mDefaultButton.setSelected(true);
        AlphaOptimizedImageButton moreIcon = mDefaultButton.findViewById(
                R.id.car_nav_button_more_icon);

        assertThat(moreIcon.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onSelected_showMoreWhenSelected_showsMoreIcon() {
        CarNavigationButton showMoreWhenSelected = mTestView.findViewById(
                R.id.not_highlightable_more_button);
        showMoreWhenSelected.setSelected(true);
        AlphaOptimizedImageButton moreIcon = showMoreWhenSelected.findViewById(
                R.id.car_nav_button_more_icon);

        assertThat(moreIcon.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void onUnselected_showMoreWhenSelected_doesNotShowMoreIcon() {
        CarNavigationButton showMoreWhenSelected = mTestView.findViewById(
                R.id.highlightable_no_more_button);
        showMoreWhenSelected.setSelected(true);
        showMoreWhenSelected.setSelected(false);
        AlphaOptimizedImageButton moreIcon = showMoreWhenSelected.findViewById(
                R.id.car_nav_button_more_icon);

        assertThat(moreIcon.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onUnselected_withAppIcon_showsAppIcon() {
        CarNavigationButton roleBasedButton = mTestView.findViewById(R.id.role_based_button);
        Drawable appIcon = getContext().getDrawable(R.drawable.ic_android);

        roleBasedButton.setSelected(false);
        roleBasedButton.setAppIcon(appIcon);

        Drawable currentDrawable = ((AlphaOptimizedImageButton) roleBasedButton.findViewById(
                R.id.car_nav_button_icon_image)).getDrawable();

        assertThat(currentDrawable).isEqualTo(appIcon);
    }

    @Test
    public void onUnselected_withAppIcon_applyUnselectedAlpha() {
        CarNavigationButton roleBasedButton = mTestView.findViewById(R.id.role_based_button);

        roleBasedButton.setSelected(false);
        roleBasedButton.setAppIcon(getContext().getDrawable(R.drawable.ic_android));

        assertThat(roleBasedButton.getAlpha()).isEqualTo(
                CarNavigationButton.DEFAULT_UNSELECTED_ALPHA);
    }

    @Test
    public void onSelected_withAppIcon_showsAppIconWithSelectedAlpha() {
        CarNavigationButton roleBasedButton = mTestView.findViewById(R.id.role_based_button);
        Drawable appIcon = getContext().getDrawable(R.drawable.ic_android);

        roleBasedButton.setSelected(true);
        roleBasedButton.setAppIcon(appIcon);

        Drawable currentDrawable = ((AlphaOptimizedImageButton) roleBasedButton.findViewById(
                R.id.car_nav_button_icon_image)).getDrawable();

        assertThat(currentDrawable).isEqualTo(appIcon);
    }

    @Test
    public void onSelected_withAppIcon_applySelectedAlpha() {
        CarNavigationButton roleBasedButton = mTestView.findViewById(R.id.role_based_button);

        roleBasedButton.setSelected(true);
        roleBasedButton.setAppIcon(getContext().getDrawable(R.drawable.ic_android));

        assertThat(roleBasedButton.getAlpha()).isEqualTo(
                CarNavigationButton.DEFAULT_SELECTED_ALPHA);
    }

    @Test
    public void onClick_launchesIntentActivity() {
        mDefaultButton.performClick();

        assertThat(getCurrentActivityName()).isEqualTo(DEFAULT_BUTTON_ACTIVITY_NAME);

        CarNavigationButton appGridButton = mTestView.findViewById(R.id.app_grid_activity);
        appGridButton.performClick();

        assertThat(getCurrentActivityName()).isEqualTo(APP_GRID_BUTTON_ACTIVITY_NAME);
    }

    @Test
    public void onLongClick_longIntentDefined_launchesLongIntentActivity() {
        mDefaultButton.performClick();

        assertThat(getCurrentActivityName()).isEqualTo(DEFAULT_BUTTON_ACTIVITY_NAME);

        CarNavigationButton appGridButton = mTestView.findViewById(
                R.id.long_click_app_grid_activity);
        appGridButton.performLongClick();

        assertThat(getCurrentActivityName()).isEqualTo(APP_GRID_BUTTON_ACTIVITY_NAME);
    }

    @Test
    public void onClick_useBroadcast_broadcastsIntent() {
        CarNavigationButton appGridButton = mTestView.findViewById(R.id.broadcast);
        appGridButton.performClick();

        verify(mContext).sendBroadcastAsUser(argThat(new ArgumentMatcher<Intent>() {
            @Override
            public boolean matches(Intent argument) {
                return argument.getAction().equals(BROADCAST_ACTION_NAME);
            }
        }), any());
    }

    @Test
    public void onSetUnseen_hasUnseen_showsUnseenIndicator() {
        mDefaultButton.setUnseen(true);
        ImageView hasUnseenIndicator = mDefaultButton.findViewById(R.id.car_nav_button_unseen_icon);

        assertThat(hasUnseenIndicator.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void onSetUnseen_doesNotHaveUnseen_hidesUnseenIndicator() {
        mDefaultButton.setUnseen(false);
        ImageView hasUnseenIndicator = mDefaultButton.findViewById(R.id.car_nav_button_unseen_icon);

        assertThat(hasUnseenIndicator.getVisibility()).isEqualTo(View.GONE);
    }

    private String getCurrentActivityName() {
        return mActivityManager.getRunningTasks(1).get(0).topActivity.flattenToShortString();
    }
}

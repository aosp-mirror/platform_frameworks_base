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

package com.android.systemui.shade;

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.MotionEvent.BUTTON_SECONDARY;
import static android.view.MotionEvent.BUTTON_STYLUS_PRIMARY;

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;

import com.android.systemui.res.R;
import com.android.systemui.plugins.qs.QS;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class QuickSettingsControllerTest extends QuickSettingsControllerBaseTest {

    @Test
    public void testCloseQsSideEffects() {
        enableSplitShade(true);
        mQsController.setExpandImmediate(true);
        mQsController.setExpanded(true);
        mQsController.closeQs();

        assertThat(mQsController.getExpanded()).isEqualTo(false);
        assertThat(mQsController.isExpandImmediate()).isEqualTo(false);
    }

    @Test
    public void testLargeScreenHeaderMadeActiveForLargeScreen() {
        mStatusBarStateController.setState(SHADE);
        when(mResources.getBoolean(R.bool.config_use_large_screen_shade_header)).thenReturn(true);
        mQsController.updateResources();
        verify(mShadeHeaderController).setLargeScreenActive(true);

        when(mResources.getBoolean(R.bool.config_use_large_screen_shade_header)).thenReturn(false);
        mQsController.updateResources();
        verify(mShadeHeaderController).setLargeScreenActive(false);
    }

    @Test
    public void testPanelStaysOpenWhenClosingQs() {
        mQsController.setShadeExpansion(/* shadeExpandedHeight= */ 1, /* expandedFraction=*/ 1);

        float shadeExpandedHeight = mQsController.getShadeExpandedHeight();
        mQsController.animateCloseQs(false);

        assertThat(mQsController.getShadeExpandedHeight()).isEqualTo(shadeExpandedHeight);
    }

    @Test
    public void interceptTouchEvent_withinQs_shadeExpanded_startsQsTracking() {
        mQsController.setQs(mQs);

        mQsController.setShadeExpansion(/* shadeExpandedHeight= */ 1, /* expandedFraction=*/ 1);
        mQsController.onIntercept(
                createMotionEvent(0, 0, ACTION_DOWN));
        mQsController.onIntercept(
                createMotionEvent(0, 500, ACTION_MOVE));

        assertThat(mQsController.isTracking()).isTrue();
    }

    @Test
    public void interceptTouchEvent_withinQs_shadeExpanded_inSplitShade_doesNotStartQsTracking() {
        enableSplitShade(true);
        mQsController.setQs(mQs);

        mQsController.setShadeExpansion(/* shadeExpandedHeight= */ 1, /* expandedFraction=*/ 1);
        mQsController.onIntercept(
                createMotionEvent(0, 0, ACTION_DOWN));
        mQsController.onIntercept(
                createMotionEvent(0, 500, ACTION_MOVE));

        assertThat(mQsController.isTracking()).isFalse();
    }

    @Test
    public void interceptTouch_downBetweenFullyCollapsedAndExpanded() {
        mQsController.setQs(mQs);
        when(mQs.getDesiredHeight()).thenReturn(QS_FRAME_BOTTOM);
        mQsController.onHeightChanged();
        mQsController.setExpansionHeight(QS_FRAME_BOTTOM / 2f);

        assertThat(mQsController.onIntercept(
                createMotionEvent(0, QS_FRAME_BOTTOM / 2, ACTION_DOWN))).isTrue();
    }

    @Test
    public void onTouch_moveActionSetsCorrectExpansionHeight() {
        mQsController.setQs(mQs);
        when(mQs.getDesiredHeight()).thenReturn(QS_FRAME_BOTTOM);
        mQsController.onHeightChanged();
        mQsController.setExpansionHeight(QS_FRAME_BOTTOM / 2f);
        mQsController.handleTouch(
                createMotionEvent(0, QS_FRAME_BOTTOM / 4, ACTION_DOWN), false, false);
        assertThat(mQsController.isTracking()).isTrue();
        mQsController.handleTouch(
                createMotionEvent(0, QS_FRAME_BOTTOM / 4 + 1, ACTION_MOVE), false, false);

        assertThat(mQsController.getExpansionHeight()).isEqualTo(QS_FRAME_BOTTOM / 2 + 1);
    }

    @Test
    public void handleTouch_downActionInQsArea() {
        mQsController.setQs(mQs);
        mQsController.setBarState(SHADE);
        mQsController.setShadeExpansion(/* shadeExpandedHeight= */ 1, /* expandedFraction=*/ 0.5f);

        MotionEvent event =
                createMotionEvent(QS_FRAME_WIDTH / 2, QS_FRAME_BOTTOM / 2, ACTION_DOWN);
        mQsController.handleTouch(event, false, false);

        assertThat(mQsController.isTracking()).isTrue();
        assertThat(mQsController.getInitialTouchY()).isEqualTo(QS_FRAME_BOTTOM / 2);
    }

    @Test
    public void handleTouch_qsTouchedWhileCollapsingDisablesTracking() {
        mQsController.handleTouch(
                createMotionEvent(0, QS_FRAME_BOTTOM, ACTION_DOWN), false, false);
        mQsController.setLastShadeFlingWasExpanding(false);
        mQsController.handleTouch(
                createMotionEvent(0, QS_FRAME_BOTTOM / 2, ACTION_MOVE), false, true);
        MotionEvent secondTouch = createMotionEvent(0, QS_FRAME_TOP, ACTION_DOWN);
        mQsController.handleTouch(secondTouch, false, true);
        assertThat(mQsController.isTracking()).isFalse();
    }

    @Test
    public void handleTouch_qsTouchedWhileExpanding() {
        mQsController.setQs(mQs);
        mQsController.handleTouch(
                createMotionEvent(100, 100, ACTION_DOWN), false, false);
        mQsController.handleTouch(
                createMotionEvent(0, QS_FRAME_BOTTOM / 2, ACTION_MOVE), false, false);
        mQsController.setLastShadeFlingWasExpanding(true);
        mQsController.handleTouch(
                createMotionEvent(0, QS_FRAME_TOP, ACTION_DOWN), false, false);
        assertThat(mQsController.isTracking()).isTrue();
    }

    @Test
    public void handleTouch_isConflictingExpansionGestureSet() {
        assertThat(mQsController.isConflictingExpansionGesture()).isFalse();
        mQsController.setShadeExpansion(/* shadeExpandedHeight= */ 1, /* expandedFraction=*/ 1);
        mQsController.handleTouch(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */), false, false);
        assertThat(mQsController.isConflictingExpansionGesture()).isTrue();
    }

    @Test
    public void handleTouch_isConflictingExpansionGestureSet_cancel() {
        mQsController.setShadeExpansion(/* shadeExpandedHeight= */ 1, /* expandedFraction=*/ 1);
        mQsController.handleTouch(createMotionEvent(0, 0, ACTION_DOWN), false, false);
        assertThat(mQsController.isConflictingExpansionGesture()).isTrue();
        mQsController.handleTouch(createMotionEvent(0, 0, ACTION_UP), true, true);
        assertThat(mQsController.isConflictingExpansionGesture()).isFalse();
    }

    @Test
    public void handleTouch_twoFingerExpandPossibleConditions() {
        assertThat(mQsController.isTwoFingerExpandPossible()).isFalse();
        mQsController.handleTouch(createMotionEvent(0, 0, ACTION_DOWN), true, false);
        assertThat(mQsController.isTwoFingerExpandPossible()).isTrue();
    }

    @Test
    public void handleTouch_twoFingerDrag() {
        mQsController.setQs(mQs);
        mQsController.setStatusBarMinHeight(1);
        mQsController.setTwoFingerExpandPossible(true);
        mQsController.handleTouch(
                createMultitouchMotionEvent(ACTION_POINTER_DOWN), false, false);
        assertThat(mQsController.isExpandImmediate()).isTrue();
        verify(mQs).setListening(true);
    }

    @Test
    public void onQsFragmentAttached_fullWidth_setsFullWidthTrueOnQS() {
        setIsFullWidth(true);
        mFragmentListener.onFragmentViewCreated(QS.TAG, mQSFragment);

        verify(mQSFragment).setIsNotificationPanelFullWidth(true);
    }

    @Test
    public void onQsFragmentAttached_notFullWidth_setsFullWidthFalseOnQS() {
        setIsFullWidth(false);
        mFragmentListener.onFragmentViewCreated(QS.TAG, mQSFragment);

        verify(mQSFragment).setIsNotificationPanelFullWidth(false);
    }

    @Test
    public void setQsExpansion_lockscreenShadeTransitionInProgress_usesLockscreenSquishiness() {
        float squishinessFraction = 0.456f;
        mQsController.setQs(mQs);
        when(mLockscreenShadeTransitionController.getQsSquishTransitionFraction())
                .thenReturn(squishinessFraction);
        when(mNotificationStackScrollLayoutController.getNotificationSquishinessFraction())
                .thenReturn(0.987f);
        // Call setTransitionToFullShadeAmount to get into the full shade transition in progress
        // state.
        mLockscreenShadeTransitionCallback.setTransitionToFullShadeAmount(234, false, 0);

        mQsController.setExpansionHeight(123);

        // First for setTransitionToFullShadeAmount and then setQsExpansion
        verify(mQs, times(2)).setQsExpansion(anyFloat(), anyFloat(), anyFloat(),
                eq(squishinessFraction)
        );
    }

    @Test
    public void setQsExpansion_lockscreenShadeTransitionNotInProgress_usesStandardSquishiness() {
        float lsSquishinessFraction = 0.456f;
        float nsslSquishinessFraction = 0.987f;
        mQsController.setQs(mQs);
        when(mLockscreenShadeTransitionController.getQsSquishTransitionFraction())
                .thenReturn(lsSquishinessFraction);
        when(mNotificationStackScrollLayoutController.getNotificationSquishinessFraction())
                .thenReturn(nsslSquishinessFraction);

        mQsController.setExpansionHeight(123);

        verify(mQs).setQsExpansion(anyFloat(), anyFloat(), anyFloat(), eq(nsslSquishinessFraction)
        );
    }

    @Test
    public void updateExpansion_expandImmediateOrAlreadyExpanded_usesFullSquishiness() {
        mQsController.setQs(mQs);
        when(mQs.getDesiredHeight()).thenReturn(100);
        mQsController.onHeightChanged();

        mQsController.setExpandImmediate(true);
        mQsController.setExpanded(false);
        mQsController.updateExpansion();
        mQsController.setExpandImmediate(false);
        mQsController.setExpanded(true);
        mQsController.updateExpansion();
        verify(mQs, times(2)).setQsExpansion(0, 0, 0, 1);
    }

    @Test
    public void updateQsState_fullscreenTrue() {
        mQsController.setExpanded(true);
        mQsController.updateQsState();
        assertThat(mShadeRepository.getLegacyQsFullscreen().getValue()).isTrue();
    }

    @Test
    public void updateQsState_fullscreenFalse() {
        mQsController.setExpanded(false);
        mQsController.updateQsState();
        assertThat(mShadeRepository.getLegacyQsFullscreen().getValue()).isFalse();
    }

    @Test
    public void shadeExpanded_onKeyguard() {
        mStatusBarStateController.setState(KEYGUARD);
        // set maxQsExpansion in NPVC
        int maxQsExpansion = 123;
        mQsController.setQs(mQs);
        when(mQs.getDesiredHeight()).thenReturn(maxQsExpansion);

        int oldMaxHeight = mQsController.updateHeightsOnShadeLayoutChange();
        mQsController.handleShadeLayoutChanged(oldMaxHeight);

        mQsController.setExpansionHeight(maxQsExpansion);
        assertThat(mQsController.computeExpansionFraction()).isEqualTo(1f);
    }

    @Test
    public void handleTouch_splitShadeAndtouchXOutsideQs() {
        enableSplitShade(true);

        assertThat(mQsController.handleTouch(createMotionEvent(
                        QS_FRAME_WIDTH + 1, QS_FRAME_BOTTOM - 1, ACTION_DOWN),
                false, false)).isFalse();
    }

    @Test
    public void isOpenQsEvent_twoFingerDrag() {
        assertThat(mQsController.isOpenQsEvent(
                createMultitouchMotionEvent(ACTION_POINTER_DOWN))).isTrue();
    }

    @Test
    public void isOpenQsEvent_stylusButtonClickDrag() {
        MotionEvent event = createMotionEvent(0, 0, ACTION_DOWN);
        event.setButtonState(BUTTON_STYLUS_PRIMARY);

        assertThat(mQsController.isOpenQsEvent(event)).isTrue();
    }

    @Test
    public void isOpenQsEvent_mouseButtonClickDrag() {
        MotionEvent event = createMotionEvent(0, 0, ACTION_DOWN);
        event.setButtonState(BUTTON_SECONDARY);

        assertThat(mQsController.isOpenQsEvent(event)).isTrue();
    }

    @Test
    public void shadeClosed_onLockscreen_inSplitShade_setsQsNotVisible() {
        mQsController.setQs(mQs);
        enableSplitShade(true);
        lockScreen();

        closeLockedQS();

        assertQsVisible(false);
    }

    @Test
    public void shadeOpened_onLockscreen_inSplitShade_setsQsVisible() {
        mQsController.setQs(mQs);
        enableSplitShade(true);
        lockScreen();

        openLockedQS();

        assertQsVisible(true);
    }

    @Test
    public void shadeClosed_onLockscreen_inSingleShade_setsQsNotVisible() {
        mQsController.setQs(mQs);
        enableSplitShade(false);
        lockScreen();

        closeLockedQS();

        verify(mQs).setQsVisible(false);
    }

    @Test
    public void shadeOpened_onLockscreen_inSingleShade_setsQsVisible() {
        mQsController.setQs(mQs);
        enableSplitShade(false);
        lockScreen();

        openLockedQS();

        verify(mQs).setQsVisible(true);
    }

    @Test
    public void calculateBottomCornerRadius_scrimScaleMax() {
        when(mScrimController.getBackScaling()).thenReturn(1.0f);
        assertThat(mQsController.calculateBottomCornerRadius(0.0f)).isEqualTo(0);
    }

    @Test
    public void calculateBottomCornerRadius_scrimScaleMin() {
        when(mScrimController.getBackScaling())
                .thenReturn(mNotificationPanelViewController.SHADE_BACK_ANIM_MIN_SCALE);
        assertThat(mQsController.calculateBottomCornerRadius(0.0f))
                .isEqualTo(mQsController.getScrimCornerRadius());
    }

    @Test
    public void calculateBottomCornerRadius_scrimScaleCutoff() {
        float ratio = 1 / mQsController.calculateBottomRadiusProgress();
        float cutoffScale = 1 - mNotificationPanelViewController.SHADE_BACK_ANIM_MIN_SCALE / ratio;
        when(mScrimController.getBackScaling())
                .thenReturn(cutoffScale);
        assertThat(mQsController.calculateBottomCornerRadius(0.0f))
                .isEqualTo(mQsController.getScrimCornerRadius());
    }

    @Test
    public void disallowTouches_nullQs_false() {
        mQsController.setQs(null);
        assertThat(mQsController.disallowTouches()).isFalse();
    }

    private void lockScreen() {
        mQsController.setBarState(KEYGUARD);
    }

    private void openLockedQS() {
        when(mLockscreenShadeTransitionController.getQSDragProgress())
                .thenReturn((float) DEFAULT_HEIGHT);
        mLockscreenShadeTransitionCallback.setTransitionToFullShadeAmount(
                /* pxAmount= */ DEFAULT_HEIGHT,
                /* animate=*/ false,
                /* delay= */ 0
        );
    }

    private void closeLockedQS() {
        when(mLockscreenShadeTransitionController.getQSDragProgress()).thenReturn(0f);
        mLockscreenShadeTransitionCallback.setTransitionToFullShadeAmount(
                /* pxAmount= */ 0,
                /* animate=*/ false,
                /* delay= */ 0
        );
    }

    private void setSplitShadeHeightProperties() {
        // In split shade, min = max
        when(mQs.getQsMinExpansionHeight()).thenReturn(DEFAULT_MIN_HEIGHT_SPLIT_SHADE);
        when(mQs.getDesiredHeight()).thenReturn(DEFAULT_HEIGHT);
        mQsController.updateMinHeight();
        mQsController.onHeightChanged();
    }

    private void setDefaultHeightProperties() {
        when(mQs.getQsMinExpansionHeight()).thenReturn(DEFAULT_MIN_HEIGHT);
        when(mQs.getDesiredHeight()).thenReturn(DEFAULT_HEIGHT);
        mQsController.updateMinHeight();
        mQsController.onHeightChanged();
    }

    private static MotionEvent createMotionEvent(int x, int y, int action) {
        return MotionEvent.obtain(0, 0, action, x, y, 0);
    }

    // Creates an empty multitouch event for now
    private static MotionEvent createMultitouchMotionEvent(int action) {
        return MotionEvent.obtain(0, 0, action, 2,
                new MotionEvent.PointerProperties[] {
                        new MotionEvent.PointerProperties(),
                        new MotionEvent.PointerProperties()
                },
                new MotionEvent.PointerCoords[] {
                        new MotionEvent.PointerCoords(),
                        new MotionEvent.PointerCoords()
                }, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private void enableSplitShade(boolean enabled) {
        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(enabled);
        mQsController.updateResources();
        if (enabled) {
            setSplitShadeHeightProperties();
        } else {
            setDefaultHeightProperties();
        }
    }

    private void setIsFullWidth(boolean fullWidth) {
        mQsController.setNotificationPanelFullWidth(fullWidth);
        triggerLayoutChange();
    }

    private void triggerLayoutChange() {
        int oldMaxHeight = mQsController.updateHeightsOnShadeLayoutChange();
        mQsController.handleShadeLayoutChanged(oldMaxHeight);
    }

    private void assertQsVisible(boolean visible) {
        ArgumentCaptor<Boolean> visibilityCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(mQs, atLeastOnce()).setQsVisible(visibilityCaptor.capture());
        List<Boolean> allVisibilities = visibilityCaptor.getAllValues();
        boolean lastVisibility = allVisibilities.get(allVisibilities.size() - 1);
        assertThat(lastVisibility).isEqualTo(visible);
    }
}

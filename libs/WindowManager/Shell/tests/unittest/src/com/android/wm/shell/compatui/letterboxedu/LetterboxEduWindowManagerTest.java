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

package com.android.wm.shell.compatui.letterboxedu;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.TaskInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.transition.Transitions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link LetterboxEduWindowManager}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:LetterboxEduWindowManagerTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class LetterboxEduWindowManagerTest extends ShellTestCase {

    private static final int USER_ID_1 = 1;
    private static final int USER_ID_2 = 2;

    private static final String PREF_KEY_1 = String.valueOf(USER_ID_1);
    private static final String PREF_KEY_2 = String.valueOf(USER_ID_2);

    private static final int TASK_ID = 1;

    private static final int TASK_WIDTH = 200;
    private static final int TASK_HEIGHT = 100;
    private static final int DISPLAY_CUTOUT_TOP = 5;
    private static final int DISPLAY_CUTOUT_BOTTOM = 10;
    private static final int DISPLAY_CUTOUT_HORIZONTAL = 20;

    @Captor
    private ArgumentCaptor<WindowManager.LayoutParams> mWindowAttrsCaptor;
    @Captor
    private ArgumentCaptor<Runnable> mEndCallbackCaptor;
    @Captor
    private ArgumentCaptor<Runnable> mRunOnIdleCaptor;

    @Mock private LetterboxEduAnimationController mAnimationController;
    @Mock private SyncTransactionQueue mSyncTransactionQueue;
    @Mock private ShellTaskOrganizer.TaskListener mTaskListener;
    @Mock private SurfaceControlViewHost mViewHost;
    @Mock private Transitions mTransitions;
    @Mock private Runnable mOnDismissCallback;

    private SharedPreferences mSharedPreferences;
    @Nullable
    private Boolean mInitialPrefValue1 = null;
    @Nullable
    private Boolean mInitialPrefValue2 = null;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mSharedPreferences = mContext.getSharedPreferences(
                LetterboxEduWindowManager.HAS_SEEN_LETTERBOX_EDUCATION_PREF_NAME,
                Context.MODE_PRIVATE);
        if (mSharedPreferences.contains(PREF_KEY_1)) {
            mInitialPrefValue1 = mSharedPreferences.getBoolean(PREF_KEY_1, /* default= */ false);
            mSharedPreferences.edit().remove(PREF_KEY_1).apply();
        }
        if (mSharedPreferences.contains(PREF_KEY_2)) {
            mInitialPrefValue2 = mSharedPreferences.getBoolean(PREF_KEY_2, /* default= */ false);
            mSharedPreferences.edit().remove(PREF_KEY_2).apply();
        }
    }

    @After
    public void tearDown() {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        if (mInitialPrefValue1 == null) {
            editor.remove(PREF_KEY_1);
        } else {
            editor.putBoolean(PREF_KEY_1, mInitialPrefValue1);
        }
        if (mInitialPrefValue2 == null) {
            editor.remove(PREF_KEY_2);
        } else {
            editor.putBoolean(PREF_KEY_2, mInitialPrefValue2);
        }
        editor.apply();
    }

    @Test
    public void testCreateLayout_notEligible_doesNotCreateLayout() {
        LetterboxEduWindowManager windowManager = createWindowManager(/* eligible= */ false);

        assertFalse(windowManager.createLayout(/* canShow= */ true));

        assertNull(windowManager.mLayout);
    }

    @Test
    public void testCreateLayout_taskBarEducationIsShowing_doesNotCreateLayout() {
        LetterboxEduWindowManager windowManager = createWindowManager(/* eligible= */
                true, USER_ID_1, /* isTaskbarEduShowing= */ true);

        assertFalse(windowManager.createLayout(/* canShow= */ true));

        assertNull(windowManager.mLayout);
    }

    @Test
    public void testCreateLayout_canShowFalse_returnsTrueButDoesNotCreateLayout() {
        LetterboxEduWindowManager windowManager = createWindowManager(/* eligible= */ true);

        assertTrue(windowManager.createLayout(/* canShow= */ false));

        assertFalse(mSharedPreferences.getBoolean(PREF_KEY_1, /* default= */ false));
        assertNull(windowManager.mLayout);
    }

    @Test
    public void testCreateLayout_canShowTrue_createsLayoutCorrectly() {
        LetterboxEduWindowManager windowManager = createWindowManager(/* eligible= */ true);

        assertTrue(windowManager.createLayout(/* canShow= */ true));

        LetterboxEduDialogLayout layout = windowManager.mLayout;
        assertNotNull(layout);
        verify(mViewHost).setView(eq(layout), mWindowAttrsCaptor.capture());
        verifyLayout(layout, mWindowAttrsCaptor.getValue(), /* expectedWidth= */ TASK_WIDTH,
                /* expectedHeight= */ TASK_HEIGHT, /* expectedExtraTopMargin= */ DISPLAY_CUTOUT_TOP,
                /* expectedExtraBottomMargin= */ DISPLAY_CUTOUT_BOTTOM);
        View dialogTitle = layout.getDialogTitle();
        assertNotNull(dialogTitle);
        spyOn(dialogTitle);

        // The education shouldn't be marked as seen until enter animation is done.
        assertFalse(mSharedPreferences.getBoolean(PREF_KEY_1, /* default= */ false));
        // Clicking the layout does nothing until enter animation is done.
        layout.performClick();
        verify(mAnimationController, never()).startExitAnimation(any(), any());
        // The dialog title shouldn't be focused for Accessibility until enter animation is done.
        verify(dialogTitle, never()).sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);

        verifyAndFinishEnterAnimation(layout);

        assertTrue(mSharedPreferences.getBoolean(PREF_KEY_1, /* default= */ false));
        verify(dialogTitle).sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        // Exit animation should start following a click on the layout.
        layout.performClick();

        // Window manager isn't released until exit animation is done.
        verify(windowManager, never()).release();

        // Verify multiple clicks are ignored.
        layout.performClick();

        verifyAndFinishExitAnimation(layout);

        verify(windowManager).release();
        verify(mOnDismissCallback).run();
    }

    @Test
    public void testCreateLayout_alreadyShownToUser_createsLayoutForOtherUserOnly() {
        LetterboxEduWindowManager windowManager = createWindowManager(/* eligible= */ true,
                USER_ID_1, /* isTaskbarEduShowing= */ false);

        assertTrue(windowManager.createLayout(/* canShow= */ true));

        assertNotNull(windowManager.mLayout);
        verifyAndFinishEnterAnimation(windowManager.mLayout);
        assertTrue(mSharedPreferences.getBoolean(PREF_KEY_1, /* default= */ false));

        windowManager.release();
        windowManager = createWindowManager(/* eligible= */ true,
                USER_ID_1, /* isTaskbarEduShowing= */ false);

        assertFalse(windowManager.createLayout(/* canShow= */ true));
        assertNull(windowManager.mLayout);

        clearInvocations(mTransitions, mAnimationController);

        windowManager = createWindowManager(/* eligible= */ true,
                USER_ID_2, /* isTaskbarEduShowing= */ false);

        assertTrue(windowManager.createLayout(/* canShow= */ true));

        assertNotNull(windowManager.mLayout);
        verifyAndFinishEnterAnimation(windowManager.mLayout);
        assertTrue(mSharedPreferences.getBoolean(PREF_KEY_1, /* default= */ false));
    }

    @Test
    public void testCreateLayout_windowManagerReleasedBeforeTransitionsIsIdle_doesNotStartAnim() {
        LetterboxEduWindowManager windowManager = createWindowManager(/* eligible= */ true);

        assertTrue(windowManager.createLayout(/* canShow= */ true));
        assertNotNull(windowManager.mLayout);

        verify(mTransitions).runOnIdle(mRunOnIdleCaptor.capture());

        windowManager.release();

        mRunOnIdleCaptor.getValue().run();

        verify(mAnimationController, never()).startEnterAnimation(any(), any());
        assertFalse(mSharedPreferences.getBoolean(PREF_KEY_1, /* default= */ false));
    }

    @Test
    public void testUpdateCompatInfo_updatesLayoutCorrectly() {
        LetterboxEduWindowManager windowManager = createWindowManager(/* eligible= */ true);

        assertTrue(windowManager.createLayout(/* canShow= */ true));
        LetterboxEduDialogLayout layout = windowManager.mLayout;
        assertNotNull(layout);

        assertTrue(windowManager.updateCompatInfo(
                createTaskInfo(/* eligible= */ true, USER_ID_1, new Rect(50, 25, 150, 75)),
                mTaskListener, /* canShow= */ true));

        verifyLayout(layout, layout.getLayoutParams(), /* expectedWidth= */ 100,
                /* expectedHeight= */ 50, /* expectedExtraTopMargin= */ 0,
                /* expectedExtraBottomMargin= */ 0);
        verify(mViewHost).relayout(mWindowAttrsCaptor.capture());
        assertThat(mWindowAttrsCaptor.getValue()).isEqualTo(layout.getLayoutParams());

        // Window manager should be released (without animation) when eligible becomes false.
        assertFalse(windowManager.updateCompatInfo(createTaskInfo(/* eligible= */ false),
                mTaskListener, /* canShow= */ true));

        verify(windowManager).release();
        verify(mOnDismissCallback, never()).run();
        verify(mAnimationController, never()).startExitAnimation(any(), any());
        assertNull(windowManager.mLayout);
    }

    @Test
    public void testUpdateCompatInfo_notEligibleUntilUpdate_createsLayoutAfterUpdate() {
        LetterboxEduWindowManager windowManager = createWindowManager(/* eligible= */ false);

        assertFalse(windowManager.createLayout(/* canShow= */ true));
        assertNull(windowManager.mLayout);

        assertTrue(windowManager.updateCompatInfo(createTaskInfo(/* eligible= */ true),
                mTaskListener, /* canShow= */ true));

        assertNotNull(windowManager.mLayout);
    }

    @Test
    public void testUpdateCompatInfo_canShowFalse_doesNothing() {
        LetterboxEduWindowManager windowManager = createWindowManager(/* eligible= */ true);

        assertTrue(windowManager.createLayout(/* canShow= */ false));
        assertNull(windowManager.mLayout);

        assertTrue(windowManager.updateCompatInfo(createTaskInfo(/* eligible= */ true),
                mTaskListener, /* canShow= */ false));

        assertNull(windowManager.mLayout);
        verify(mViewHost, never()).relayout(any());
    }

    @Test
    public void testUpdateDisplayLayout_updatesLayoutCorrectly() {
        LetterboxEduWindowManager windowManager = createWindowManager(/* eligible= */ true);

        assertTrue(windowManager.createLayout(/* canShow= */ true));
        LetterboxEduDialogLayout layout = windowManager.mLayout;
        assertNotNull(layout);

        int newDisplayCutoutTop = DISPLAY_CUTOUT_TOP + 7;
        int newDisplayCutoutBottom = DISPLAY_CUTOUT_BOTTOM + 9;
        windowManager.updateDisplayLayout(createDisplayLayout(
                Insets.of(DISPLAY_CUTOUT_HORIZONTAL, newDisplayCutoutTop,
                        DISPLAY_CUTOUT_HORIZONTAL, newDisplayCutoutBottom)));

        verifyLayout(layout, layout.getLayoutParams(), /* expectedWidth= */ TASK_WIDTH,
                /* expectedHeight= */ TASK_HEIGHT, /* expectedExtraTopMargin= */
                newDisplayCutoutTop, /* expectedExtraBottomMargin= */ newDisplayCutoutBottom);
        verify(mViewHost).relayout(mWindowAttrsCaptor.capture());
        assertThat(mWindowAttrsCaptor.getValue()).isEqualTo(layout.getLayoutParams());
    }

    @Test
    public void testRelease_animationIsCancelled() {
        LetterboxEduWindowManager windowManager = createWindowManager(/* eligible= */ true);

        assertTrue(windowManager.createLayout(/* canShow= */ true));
        windowManager.release();

        verify(mAnimationController).cancelAnimation();
    }

    private void verifyLayout(LetterboxEduDialogLayout layout, ViewGroup.LayoutParams params,
            int expectedWidth, int expectedHeight, int expectedExtraTopMargin,
            int expectedExtraBottomMargin) {
        assertThat(params.width).isEqualTo(expectedWidth);
        assertThat(params.height).isEqualTo(expectedHeight);
        MarginLayoutParams dialogParams =
                (MarginLayoutParams) layout.getDialogContainer().getLayoutParams();
        int verticalMargin = (int) mContext.getResources().getDimension(
                R.dimen.letterbox_education_dialog_margin);
        assertThat(dialogParams.topMargin).isEqualTo(verticalMargin + expectedExtraTopMargin);
        assertThat(dialogParams.bottomMargin).isEqualTo(verticalMargin + expectedExtraBottomMargin);
    }

    private void verifyAndFinishEnterAnimation(LetterboxEduDialogLayout layout) {
        verify(mTransitions).runOnIdle(mRunOnIdleCaptor.capture());

        // startEnterAnimation isn't called until run-on-idle runnable is called.
        verify(mAnimationController, never()).startEnterAnimation(any(), any());

        mRunOnIdleCaptor.getValue().run();

        verify(mAnimationController).startEnterAnimation(eq(layout), mEndCallbackCaptor.capture());
        mEndCallbackCaptor.getValue().run();
    }

    private void verifyAndFinishExitAnimation(LetterboxEduDialogLayout layout) {
        verify(mAnimationController).startExitAnimation(eq(layout), mEndCallbackCaptor.capture());
        mEndCallbackCaptor.getValue().run();
    }

    private LetterboxEduWindowManager createWindowManager(boolean eligible) {
        return createWindowManager(eligible, USER_ID_1, /* isTaskbarEduShowing= */ false);
    }

    private LetterboxEduWindowManager createWindowManager(boolean eligible,
            int userId, boolean isTaskbarEduShowing) {
        LetterboxEduWindowManager windowManager = new LetterboxEduWindowManager(mContext,
                createTaskInfo(eligible, userId), mSyncTransactionQueue, mTaskListener,
                createDisplayLayout(), mTransitions, mOnDismissCallback,
                mAnimationController);

        spyOn(windowManager);
        doReturn(mViewHost).when(windowManager).createSurfaceViewHost();
        doReturn(isTaskbarEduShowing).when(windowManager).isTaskbarEduShowing();

        return windowManager;
    }

    private DisplayLayout createDisplayLayout() {
        return createDisplayLayout(
                Insets.of(DISPLAY_CUTOUT_HORIZONTAL, DISPLAY_CUTOUT_TOP, DISPLAY_CUTOUT_HORIZONTAL,
                        DISPLAY_CUTOUT_BOTTOM));
    }

    private DisplayLayout createDisplayLayout(Insets insets) {
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = TASK_WIDTH;
        displayInfo.logicalHeight = TASK_HEIGHT;
        displayInfo.displayCutout = new DisplayCutout(
                insets, null, null, null, null);
        return new DisplayLayout(displayInfo,
                mContext.getResources(), /* hasNavigationBar= */ false, /* hasStatusBar= */ false);
    }

    private static TaskInfo createTaskInfo(boolean eligible) {
        return createTaskInfo(eligible, USER_ID_1);
    }

    private static TaskInfo createTaskInfo(boolean eligible, int userId) {
        return createTaskInfo(eligible, userId, new Rect(0, 0, TASK_WIDTH, TASK_HEIGHT));
    }

    private static TaskInfo createTaskInfo(boolean eligible, int userId, Rect bounds) {
        ActivityManager.RunningTaskInfo taskInfo = new ActivityManager.RunningTaskInfo();
        taskInfo.userId = userId;
        taskInfo.taskId = TASK_ID;
        taskInfo.topActivityEligibleForLetterboxEducation = eligible;
        taskInfo.configuration.windowConfiguration.setBounds(bounds);
        return taskInfo;
    }
}

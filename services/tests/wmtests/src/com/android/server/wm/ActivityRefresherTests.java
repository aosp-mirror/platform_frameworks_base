/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE;
import static android.app.servertransaction.ActivityLifecycleItem.ON_STOP;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.servertransaction.RefreshCallbackItem;
import android.app.servertransaction.ResumeActivityItem;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link ActivityRefresher}.
 *
 * <p>Build/Install/Run:
 *  atest WmTests:ActivityRefresherTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ActivityRefresherTests extends WindowTestsBase {
    private Handler mMockHandler;
    private LetterboxConfiguration mLetterboxConfiguration;

    private ActivityRecord mActivity;
    private ActivityRefresher mActivityRefresher;

    private ActivityRefresher.Evaluator mEvaluatorFalse =
            (activity, newConfig, lastReportedConfig) -> false;

    private ActivityRefresher.Evaluator mEvaluatorTrue =
            (activity, newConfig, lastReportedConfig) -> true;

    private final Configuration mNewConfig = new Configuration();
    private final Configuration mOldConfig = new Configuration();

    @Before
    public void setUp() throws Exception {
        mLetterboxConfiguration = mDisplayContent.mWmService.mLetterboxConfiguration;
        spyOn(mLetterboxConfiguration);
        when(mLetterboxConfiguration.isCameraCompatTreatmentEnabled())
                .thenReturn(true);
        when(mLetterboxConfiguration.isCameraCompatRefreshEnabled())
                .thenReturn(true);
        when(mLetterboxConfiguration.isCameraCompatRefreshCycleThroughStopEnabled())
                .thenReturn(true);

        mMockHandler = mock(Handler.class);
        when(mMockHandler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(
                invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                });

        mActivityRefresher = new ActivityRefresher(mDisplayContent.mWmService, mMockHandler);
    }

    @Test
    public void testShouldRefreshActivity_refreshDisabled() throws Exception {
        when(mLetterboxConfiguration.isCameraCompatRefreshEnabled())
                .thenReturn(false);
        configureActivityAndDisplay();
        mActivityRefresher.addEvaluator(mEvaluatorTrue);

        mActivityRefresher.onActivityConfigurationChanging(mActivity, mNewConfig, mOldConfig);

        assertActivityRefreshRequested(/* refreshRequested= */ false);
    }

    @Test
    public void testShouldRefreshActivity_refreshDisabledForActivity() throws Exception {
        configureActivityAndDisplay();
        when(mActivity.mAppCompatController.getAppCompatCameraOverrides()
                .shouldRefreshActivityForCameraCompat()).thenReturn(false);
        mActivityRefresher.addEvaluator(mEvaluatorTrue);

        mActivityRefresher.onActivityConfigurationChanging(mActivity, mNewConfig, mOldConfig);

        assertActivityRefreshRequested(/* refreshRequested= */ false);
    }

    @Test
    public void testShouldRefreshActivity_noRefreshTriggerers() throws Exception {
        configureActivityAndDisplay();

        mActivityRefresher.onActivityConfigurationChanging(mActivity, mNewConfig, mOldConfig);

        assertActivityRefreshRequested(/* refreshRequested= */ false);
    }

    @Test
    public void testShouldRefreshActivity_refreshTriggerersReturnFalse() throws Exception {
        configureActivityAndDisplay();
        mActivityRefresher.addEvaluator(mEvaluatorFalse);

        mActivityRefresher.onActivityConfigurationChanging(mActivity, mNewConfig, mOldConfig);

        assertActivityRefreshRequested(/* refreshRequested= */ false);
    }

    @Test
    public void testShouldRefreshActivity_anyRefreshTriggerersReturnTrue() throws Exception {
        configureActivityAndDisplay();
        mActivityRefresher.addEvaluator(mEvaluatorFalse);
        mActivityRefresher.addEvaluator(mEvaluatorTrue);

        mActivityRefresher.onActivityConfigurationChanging(mActivity, mNewConfig, mOldConfig);

        assertActivityRefreshRequested(/* refreshRequested= */ true);
    }

    @Test
    public void testOnActivityConfigurationChanging_cycleThroughStopDisabled()
            throws Exception {
        mActivityRefresher.addEvaluator(mEvaluatorTrue);
        when(mLetterboxConfiguration.isCameraCompatRefreshCycleThroughStopEnabled())
                .thenReturn(false);
        configureActivityAndDisplay();

        mActivityRefresher.onActivityConfigurationChanging(mActivity, mNewConfig, mOldConfig);

        assertActivityRefreshRequested(/* refreshRequested */ true, /* cycleThroughStop */ false);
    }

    @Test
    public void testOnActivityConfigurationChanging_cycleThroughPauseEnabledForApp()
            throws Exception {
        configureActivityAndDisplay();
        mActivityRefresher.addEvaluator(mEvaluatorTrue);
        doReturn(true)
                .when(mActivity.mAppCompatController.getAppCompatCameraOverrides())
                    .shouldRefreshActivityViaPauseForCameraCompat();

        mActivityRefresher.onActivityConfigurationChanging(mActivity, mNewConfig, mOldConfig);

        assertActivityRefreshRequested(/* refreshRequested */ true, /* cycleThroughStop */ false);
    }

    @Test
    public void testOnActivityRefreshed_setIsRefreshRequestedToFalse() throws Exception {
        configureActivityAndDisplay();
        mActivityRefresher.addEvaluator(mEvaluatorTrue);
        doReturn(true)
                .when(mActivity.mAppCompatController.getAppCompatCameraOverrides())
                    .shouldRefreshActivityViaPauseForCameraCompat();

        mActivityRefresher.onActivityRefreshed(mActivity);

        assertActivityRefreshRequested(false);
    }

    private void assertActivityRefreshRequested(boolean refreshRequested) throws Exception {
        assertActivityRefreshRequested(refreshRequested, /* cycleThroughStop*/ true);
    }

    private void assertActivityRefreshRequested(boolean refreshRequested,
            boolean cycleThroughStop) throws Exception {
        verify(mActivity.mAppCompatController.getAppCompatCameraOverrides(),
                times(refreshRequested ? 1 : 0)).setIsRefreshRequested(true);

        final RefreshCallbackItem refreshCallbackItem = RefreshCallbackItem.obtain(mActivity.token,
                cycleThroughStop ? ON_STOP : ON_PAUSE);
        final ResumeActivityItem resumeActivityItem = ResumeActivityItem.obtain(mActivity.token,
                /* isForward */ false, /* shouldSendCompatFakeFocus */ false);

        verify(mActivity.mAtmService.getLifecycleManager(), times(refreshRequested ? 1 : 0))
                .scheduleTransactionAndLifecycleItems(mActivity.app.getThread(),
                        refreshCallbackItem, resumeActivityItem);
    }

    private void configureActivityAndDisplay() {
        mActivity = new TaskBuilder(mSupervisor)
                .setCreateActivity(true)
                .setDisplay(mDisplayContent)
                // Set the component to be that of the test class in order to enable compat changes
                .setComponent(ComponentName.createRelative(mContext,
                        ActivityRefresherTests.class.getName()))
                .setWindowingMode(WINDOWING_MODE_FREEFORM)
                .build()
                .getTopMostActivity();

        spyOn(mActivity.mLetterboxUiController);
        spyOn(mActivity.mAppCompatController.getAppCompatCameraOverrides());
        doReturn(true).when(mActivity).inFreeformWindowingMode();
        doReturn(true).when(mActivity.mAppCompatController
                .getAppCompatCameraOverrides()).shouldRefreshActivityForCameraCompat();
    }
}

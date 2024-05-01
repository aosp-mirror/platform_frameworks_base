/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.screenshot.appclips;

import static android.app.Activity.RESULT_OK;

import static com.android.systemui.screenshot.appclips.AppClipsEvent.SCREENSHOT_FOR_NOTE_ACCEPTED;
import static com.android.systemui.screenshot.appclips.AppClipsEvent.SCREENSHOT_FOR_NOTE_CANCELLED;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Process;
import android.os.ResultReceiver;
import android.testing.AndroidTestingRunner;
import android.view.Display;
import android.widget.ImageView;

import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.intercepting.SingleActivityFactory;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;
import com.android.systemui.screenshot.ImageExporter;
import com.android.systemui.settings.UserTracker;

import com.google.common.util.concurrent.Futures;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

@RunWith(AndroidTestingRunner.class)
public final class AppClipsActivityTest extends SysuiTestCase {

    private static final int TEST_UID = 42;
    private static final int TEST_USER_ID = 43;
    private static final Bitmap TEST_BITMAP = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
    private static final String TEST_URI_STRING = "www.test-uri.com";
    private static final Uri TEST_URI = Uri.parse(TEST_URI_STRING);
    private static final BiConsumer<Integer, Bundle> FAKE_CONSUMER = (unUsed1, unUsed2) -> {};
    private static final String TEST_CALLING_PACKAGE = "test-calling-package";

    @Mock
    private AppClipsCrossProcessHelper mAppClipsCrossProcessHelper;
    @Mock
    private ImageExporter mImageExporter;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private UiEventLogger mUiEventLogger;

    private AppClipsActivity mActivity;

    // Using the deprecated ActivityTestRule and SingleActivityFactory to help with injecting mocks.
    private final SingleActivityFactory<AppClipsActivityTestable> mFactory =
            new SingleActivityFactory<>(AppClipsActivityTestable.class) {
                @Override
                protected AppClipsActivityTestable create(Intent unUsed) {
                    return new AppClipsActivityTestable(
                            new AppClipsViewModel.Factory(mAppClipsCrossProcessHelper,
                                    mImageExporter, getContext().getMainExecutor(),
                                    directExecutor()), mPackageManager, mUserTracker,
                            mUiEventLogger);
                }
            };

    @Rule
    public final ActivityTestRule<AppClipsActivityTestable> mActivityRule =
            new ActivityTestRule<>(mFactory, false, false);

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        MockitoAnnotations.initMocks(this);

        when(mUserTracker.getUserId()).thenReturn(TEST_USER_ID);
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = TEST_UID;
        when(mPackageManager.getApplicationInfoAsUser(eq(TEST_CALLING_PACKAGE),
                any(ApplicationInfoFlags.class), eq(TEST_USER_ID))).thenReturn(applicationInfo);

        when(mAppClipsCrossProcessHelper.takeScreenshot()).thenReturn(TEST_BITMAP);
        ImageExporter.Result result = new ImageExporter.Result();
        result.uri = TEST_URI;
        when(mImageExporter.export(any(Executor.class), any(UUID.class), any(Bitmap.class),
                eq(Process.myUserHandle()), eq(Display.DEFAULT_DISPLAY)))
                .thenReturn(Futures.immediateFuture(result));
    }

    @After
    public void tearDown() {
        mActivityRule.finishActivity();
    }

    @Test
    public void appClipsLaunched_screenshotDisplayed() {
        launchActivity();

        assertThat(((ImageView) mActivity.findViewById(R.id.preview)).getDrawable()).isNotNull();
    }

    @Test
    @Ignore("b/315848285")
    public void screenshotDisplayed_userConsented_screenshotExportedSuccessfully() {
        ResultReceiver resultReceiver = createResultReceiver((resultCode, data) -> {
            assertThat(resultCode).isEqualTo(RESULT_OK);
            assertThat(
                    data.getParcelable(AppClipsTrampolineActivity.EXTRA_SCREENSHOT_URI, Uri.class))
                    .isEqualTo(TEST_URI);
            assertThat(data.getInt(Intent.EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE))
                    .isEqualTo(Intent.CAPTURE_CONTENT_FOR_NOTE_SUCCESS);
        });

        launchActivity(resultReceiver);
        runOnMainThread(() -> mActivity.findViewById(R.id.save).performClick());
        waitForIdleSync();

        assertThat(mActivity.isFinishing()).isTrue();
        verify(mUiEventLogger).log(SCREENSHOT_FOR_NOTE_ACCEPTED, TEST_UID, TEST_CALLING_PACKAGE);
    }

    @Test
    public void screenshotDisplayed_userDeclined() {
        ResultReceiver resultReceiver = createResultReceiver((resultCode, data) -> {
            assertThat(resultCode).isEqualTo(RESULT_OK);
            assertThat(data.getInt(Intent.EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE))
                    .isEqualTo(Intent.CAPTURE_CONTENT_FOR_NOTE_USER_CANCELED);
            assertThat(data.keySet().contains(AppClipsTrampolineActivity.EXTRA_SCREENSHOT_URI))
                    .isFalse();
        });

        launchActivity(resultReceiver);
        runOnMainThread(() -> mActivity.findViewById(R.id.cancel).performClick());
        waitForIdleSync();

        assertThat(mActivity.isFinishing()).isTrue();
        verify(mUiEventLogger).log(SCREENSHOT_FOR_NOTE_CANCELLED, TEST_UID, TEST_CALLING_PACKAGE);
    }

    private void launchActivity() {
        launchActivity(createResultReceiver(FAKE_CONSUMER));
    }

    private void launchActivity(ResultReceiver resultReceiver) {
        Intent intent = new Intent()
                .putExtra(AppClipsTrampolineActivity.EXTRA_RESULT_RECEIVER, resultReceiver)
                .putExtra(AppClipsTrampolineActivity.EXTRA_CALLING_PACKAGE_NAME,
                        TEST_CALLING_PACKAGE);

        mActivity = mActivityRule.launchActivity(intent);
        waitForIdleSync();
    }

    private ResultReceiver createResultReceiver(
            BiConsumer<Integer, Bundle> resultReceiverConsumer) {
        ResultReceiver testReceiver = new ResultReceiver(mContext.getMainThreadHandler()) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                resultReceiverConsumer.accept(resultCode, resultData);
            }
        };

        Parcel parcel = Parcel.obtain();
        testReceiver.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        testReceiver  = ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return testReceiver;
    }

    private void runOnMainThread(Runnable runnable) {
        mContext.getMainExecutor().execute(runnable);
    }

    public static class AppClipsActivityTestable extends AppClipsActivity {

        public AppClipsActivityTestable(AppClipsViewModel.Factory viewModelFactory,
                PackageManager packageManager,
                UserTracker userTracker,
                UiEventLogger uiEventLogger) {
            super(viewModelFactory, packageManager, userTracker, uiEventLogger);
        }
    }
}

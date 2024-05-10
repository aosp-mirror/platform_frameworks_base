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

package com.android.systemui.logcat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.app.ILogAccessDialogCallback;
import com.android.systemui.res.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@LargeTest
public class LogAccessDialogActivityTest extends SysuiTestCase {

    public static final String EXTRA_CALLBACK = "EXTRA_CALLBACK";
    private final DialogCallbackTestable mDialogCallback = new DialogCallbackTestable();
    @Rule
    public ActivityScenarioRule<DialogTestable> mActivityScenarioRule =
            new ActivityScenarioRule<>(getIntent());

    static final class DialogCallbackTestable extends ILogAccessDialogCallback.Stub {

        int mNumOfApprove = 0;
        int mNumOfDecline = 0;
        CountDownLatch mCountDownLatch = new CountDownLatch(1);

        @Override
        public void approveAccessForClient(int i, String s) {
            mNumOfApprove++;
            mCountDownLatch.countDown();
        }

        @Override
        public void declineAccessForClient(int i, String s) {
            mNumOfDecline++;
            mCountDownLatch.countDown();
        }
    }

    @Before
    public void setUp() {
        mDialogCallback.mNumOfDecline = 0;
        mDialogCallback.mNumOfApprove = 0;
        mDialogCallback.mCountDownLatch = new CountDownLatch(1);
    }

    private Intent getIntent() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        final Intent intent = new Intent(context, DialogTestable.class);

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, "packageName");
        intent.putExtra(Intent.EXTRA_UID, 1);

        intent.putExtra(EXTRA_CALLBACK, mDialogCallback.asBinder());
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        return intent;
    }

    @Test
    public void test_dialogDisappear_withoutClick_autoDeclined() throws InterruptedException {
        ActivityScenario<DialogTestable> activityScenario = mActivityScenarioRule.getScenario();
        activityScenario.onActivity(Activity::finish);

        assertTrue(mDialogCallback.mCountDownLatch.await(2, TimeUnit.SECONDS));
        assertEquals(mDialogCallback.mNumOfDecline, 1);
        assertEquals(mDialogCallback.mNumOfApprove, 0);

    }

    @Test
    public void test_clickAllow() throws InterruptedException {
        ActivityScenario<DialogTestable> activityScenario = mActivityScenarioRule.getScenario();

        activityScenario.onActivity(activity -> {
            View allowButton =
                    activity.mAlertView.findViewById(R.id.log_access_dialog_allow_button);
            assertNotNull(allowButton);
            allowButton.performClick();
        });

        assertTrue(mDialogCallback.mCountDownLatch.await(10, TimeUnit.SECONDS));
        assertEquals(mDialogCallback.mNumOfDecline, 0);
        assertEquals(mDialogCallback.mNumOfApprove, 1);
    }

    @Test
    public void test_clickDeny() throws InterruptedException {
        ActivityScenario<DialogTestable> activityScenario = mActivityScenarioRule.getScenario();

        activityScenario.onActivity(activity -> {
            View denyButton =
                    activity.mAlertView.findViewById(R.id.log_access_dialog_deny_button);
            assertNotNull(denyButton);
            denyButton.performClick();
        });

        assertTrue(mDialogCallback.mCountDownLatch.await(10, TimeUnit.SECONDS));
        assertEquals(mDialogCallback.mNumOfDecline, 1);
        assertEquals(mDialogCallback.mNumOfApprove, 0);
    }

    public static class DialogTestable extends LogAccessDialogActivity {

        @Override
        protected String getTitleString(Context context, String callingPackage, int uid) {
            return "DialogTitle";
        }
    }
}

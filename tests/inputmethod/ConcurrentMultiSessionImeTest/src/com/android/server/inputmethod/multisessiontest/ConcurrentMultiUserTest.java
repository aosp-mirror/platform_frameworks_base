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

package com.android.server.inputmethod.multisessiontest;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.compatibility.common.util.concurrentuser.ConcurrentUserActivityUtils.getResponderUserId;
import static com.android.compatibility.common.util.concurrentuser.ConcurrentUserActivityUtils.launchActivityAsUserSync;
import static com.android.compatibility.common.util.concurrentuser.ConcurrentUserActivityUtils.sendBundleAndWaitForReply;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.KEY_REQUEST_CODE;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.KEY_RESULT_CODE;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REPLY_IME_HIDDEN;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REQUEST_IME_STATUS;

import static com.google.common.truth.Truth.assertWithMessage;

import android.content.ComponentName;
import android.os.Bundle;

import androidx.test.core.app.ActivityScenario;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
@Ignore("b/345557347")
public final class ConcurrentMultiUserTest {

    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final ComponentName TEST_ACTIVITY = new ComponentName(
            getInstrumentation().getTargetContext().getPackageName(),
            MainActivity.class.getName());

    private ActivityScenario<MainActivity> mActivityScenario;
    private MainActivity mActivity;
    private int mPeerUserId;

    @Before
    public void setUp() {
        // Launch passenger activity.
        mPeerUserId = getResponderUserId();
        launchActivityAsUserSync(TEST_ACTIVITY, mPeerUserId);

        // Launch driver activity.
        mActivityScenario = ActivityScenario.launch(MainActivity.class);
        mActivityScenario.onActivity(activity -> mActivity = activity);
    }

    @After
    public void tearDown() {
        if (mActivityScenario != null) {
            mActivityScenario.close();
        }
    }

    @Test
    public void driverShowImeNotAffectPassenger() {
        assertDriverImeHidden();
        assertPassengerImeHidden();

        showDriverImeAndAssert();
        assertPassengerImeHidden();
    }

    private void assertDriverImeHidden() {
        assertWithMessage("Driver IME should be hidden")
                .that(mActivity.isMyImeVisible()).isFalse();
    }

    private void assertPassengerImeHidden() {
        final Bundle bundleToSend = new Bundle();
        bundleToSend.putInt(KEY_REQUEST_CODE, REQUEST_IME_STATUS);
        Bundle receivedBundle = sendBundleAndWaitForReply(TEST_ACTIVITY.getPackageName(),
                mPeerUserId, bundleToSend);
        assertWithMessage("Passenger IME should be hidden")
                .that(receivedBundle.getInt(KEY_RESULT_CODE)).isEqualTo(REPLY_IME_HIDDEN);
    }

    private void showDriverImeAndAssert() {
        mActivity.showMyImeAndWait();
    }
}

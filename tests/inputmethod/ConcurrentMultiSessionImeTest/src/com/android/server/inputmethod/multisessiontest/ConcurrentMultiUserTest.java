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

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.compatibility.common.util.concurrentuser.ConcurrentUserActivityUtils.getResponderUserId;
import static com.android.compatibility.common.util.concurrentuser.ConcurrentUserActivityUtils.launchActivityAsUserSync;
import static com.android.compatibility.common.util.concurrentuser.ConcurrentUserActivityUtils.sendBundleAndWaitForReply;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.KEY_DISPLAY_ID;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.KEY_EDITTEXT_CENTER;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.KEY_IME_SHOWN;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.KEY_REQUEST_CODE;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REQUEST_DISPLAY_ID;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REQUEST_EDITTEXT_POSITION;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REQUEST_HIDE_IME;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REQUEST_IME_STATUS;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REQUEST_SHOW_IME;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.test.core.app.ActivityScenario;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.compatibility.common.util.SystemUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.List;

@RunWith(BedsteadJUnit4.class)
public final class ConcurrentMultiUserTest {

    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final ComponentName TEST_ACTIVITY = new ComponentName(
            getInstrumentation().getTargetContext().getPackageName(),
            MainActivity.class.getName());
    private final Context mContext = getInstrumentation().getTargetContext();
    private final InputMethodManager mInputMethodManager =
            mContext.getSystemService(InputMethodManager.class);
    private final UiAutomation mUiAutomation = getInstrumentation().getUiAutomation();

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
        mUiAutomation.adoptShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL);
    }

    @After
    public void tearDown() {
        mUiAutomation.dropShellPermissionIdentity();
        if (mActivityScenario != null) {
            mActivityScenario.close();
        }
    }

    @Test
    public void driverShowImeNotAffectPassenger() throws Exception {
        assertDriverImeHidden();
        assertPassengerImeHidden();

        showDriverImeAndAssert();
        assertPassengerImeHidden();
    }

    @Test
    @Ignore("b/352823913")
    public void passengerShowImeNotAffectDriver() throws Exception {
        assertDriverImeHidden();
        assertPassengerImeHidden();

        showPassengerImeAndAssert();
        assertDriverImeHidden();
    }

    @Test
    public void driverHideImeNotAffectPassenger() throws Exception {
        showDriverImeAndAssert();
        showPassengerImeAndAssert();

        hideDriverImeAndAssert();
        assertPassengerImeShown();
    }

    @Test
    public void passengerHideImeNotAffectDriver() throws Exception {
        showDriverImeAndAssert();
        showPassengerImeAndAssert();

        hidePassengerImeAndAssert();
        assertDriverImeShown();
    }

    @Test
    public void imeListNotEmpty() {
        List<InputMethodInfo> driverImeList = mInputMethodManager.getInputMethodList();
        assertWithMessage("Driver IME list shouldn't be empty")
                .that(driverImeList.isEmpty()).isFalse();

        List<InputMethodInfo> passengerImeList =
                mInputMethodManager.getInputMethodListAsUser(mPeerUserId);
        assertWithMessage("Passenger IME list shouldn't be empty")
                .that(passengerImeList.isEmpty()).isFalse();
    }

    @Test
    public void enabledImeListNotEmpty() {
        List<InputMethodInfo> driverEnabledImeList =
                mInputMethodManager.getEnabledInputMethodList();
        assertWithMessage("Driver enabled IME list shouldn't be empty")
                .that(driverEnabledImeList.isEmpty()).isFalse();

        List<InputMethodInfo> passengerEnabledImeList =
                mInputMethodManager.getEnabledInputMethodListAsUser(UserHandle.of(mPeerUserId));
        assertWithMessage("Passenger enabled IME list shouldn't be empty")
                .that(passengerEnabledImeList.isEmpty()).isFalse();
    }

    @Test
    public void currentImeNotNull() {
        InputMethodInfo driverIme = mInputMethodManager.getCurrentInputMethodInfo();
        assertWithMessage("Driver IME shouldn't be null").that(driverIme).isNotNull();

        InputMethodInfo passengerIme =
                mInputMethodManager.getCurrentInputMethodInfoAsUser(UserHandle.of(mPeerUserId));
        assertWithMessage("Passenger IME shouldn't be null")
                .that(passengerIme).isNotNull();
    }

    @Test
    public void enableDisableImePerUser() throws IOException {
        UserHandle driver = UserHandle.of(mContext.getUserId());
        UserHandle passenger = UserHandle.of(mPeerUserId);
        enableDisableImeForUser(driver, passenger);
        enableDisableImeForUser(passenger, driver);
    }

    @Test
    public void setImePerUser() throws IOException {
        UserHandle driver = UserHandle.of(mContext.getUserId());
        UserHandle passenger = UserHandle.of(mPeerUserId);
        setImeForUser(driver, passenger);
        setImeForUser(passenger, driver);
    }

    private void assertDriverImeShown() {
        assertWithMessage("Driver IME should be shown")
                .that(mActivity.isMyImeVisible()).isTrue();
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
                .that(receivedBundle.getBoolean(KEY_IME_SHOWN, /* defaultValue= */ true)).isFalse();
    }

    private void assertPassengerImeShown() {
        final Bundle bundleToSend = new Bundle();
        bundleToSend.putInt(KEY_REQUEST_CODE, REQUEST_IME_STATUS);
        Bundle receivedBundle = sendBundleAndWaitForReply(TEST_ACTIVITY.getPackageName(),
                mPeerUserId, bundleToSend);
        assertWithMessage("Passenger IME should be shown")
                .that(receivedBundle.getBoolean(KEY_IME_SHOWN)).isTrue();
    }

    private void showDriverImeAndAssert() throws Exception {
        //  WindowManagerInternal only allows the top focused display to show IME, so this method
        //  taps the driver display in case it is not the top focused display.
        moveDriverDisplayToTop();

        mActivity.showMyImeAndWait();
    }

    private void hideDriverImeAndAssert() {
        mActivity.hideMyImeAndWait();
    }

    private void showPassengerImeAndAssert() throws Exception {
        // WindowManagerInternal only allows the top focused display to show IME, so this method
        // taps the passenger display in case it is not the top focused display.
        movePassengerDisplayToTop();

        Bundle bundleToSend = new Bundle();
        bundleToSend.putInt(KEY_REQUEST_CODE, REQUEST_SHOW_IME);
        Bundle receivedBundle = sendBundleAndWaitForReply(TEST_ACTIVITY.getPackageName(),
                mPeerUserId, bundleToSend);

        assertWithMessage("Passenger IME should be shown")
                .that(receivedBundle.getBoolean(KEY_IME_SHOWN)).isTrue();
    }

    private void hidePassengerImeAndAssert() {
        Bundle bundleToSend = new Bundle();
        bundleToSend.putInt(KEY_REQUEST_CODE, REQUEST_HIDE_IME);
        Bundle receivedBundle = sendBundleAndWaitForReply(TEST_ACTIVITY.getPackageName(),
                mPeerUserId, bundleToSend);

        assertWithMessage("Passenger IME should be hidden")
                .that(receivedBundle.getBoolean(KEY_IME_SHOWN, /* defaultValue= */ true)).isFalse();
    }

    private void moveDriverDisplayToTop() throws Exception {
        float[] driverEditTextCenter = mActivity.getEditTextCenter();
        SystemUtil.runShellCommand(mUiAutomation, String.format("input tap %f %f",
                driverEditTextCenter[0], driverEditTextCenter[1]));
    }

    private void movePassengerDisplayToTop() throws Exception {
        final Bundle bundleToSend = new Bundle();
        bundleToSend.putInt(KEY_REQUEST_CODE, REQUEST_EDITTEXT_POSITION);
        Bundle receivedBundle = sendBundleAndWaitForReply(TEST_ACTIVITY.getPackageName(),
                mPeerUserId, bundleToSend);
        final float[] passengerEditTextCenter = receivedBundle.getFloatArray(KEY_EDITTEXT_CENTER);

        bundleToSend.putInt(KEY_REQUEST_CODE, REQUEST_DISPLAY_ID);
        receivedBundle = sendBundleAndWaitForReply(TEST_ACTIVITY.getPackageName(),
                mPeerUserId, bundleToSend);
        final int passengerDisplayId = receivedBundle.getInt(KEY_DISPLAY_ID);
        SystemUtil.runShellCommand(mUiAutomation, String.format("input -d %d tap %f %f",
                passengerDisplayId, passengerEditTextCenter[0], passengerEditTextCenter[1]));
    }

    /**
     * Disables/enables IME for {@code user1}, then verifies that the IME settings for {@code user1}
     * has changed as expected and {@code user2} stays the same.
     */
    private void enableDisableImeForUser(UserHandle user1, UserHandle user2) throws IOException {
        List<InputMethodInfo> user1EnabledImeList =
                mInputMethodManager.getEnabledInputMethodListAsUser(user1);
        List<InputMethodInfo> user2EnabledImeList =
                mInputMethodManager.getEnabledInputMethodListAsUser(user2);

        // Disable an IME for user1.
        InputMethodInfo imeToDisable = user1EnabledImeList.get(0);
        SystemUtil.runShellCommand(mUiAutomation,
                "ime disable --user " + user1.getIdentifier() + " " + imeToDisable.getId());
        List<InputMethodInfo> user1EnabledImeList2 =
                mInputMethodManager.getEnabledInputMethodListAsUser(user1);
        List<InputMethodInfo> user2EnabledImeList2 =
                mInputMethodManager.getEnabledInputMethodListAsUser(user2);
        assertWithMessage("User " + user1.getIdentifier() + " IME " + imeToDisable.getId()
                + " should be disabled")
                .that(user1EnabledImeList2.contains(imeToDisable)).isFalse();
        assertWithMessage("Disabling user " + user1.getIdentifier()
                + " IME shouldn't affect user " + user2.getIdentifier())
                .that(user2EnabledImeList2.containsAll(user2EnabledImeList)
                        && user2EnabledImeList.containsAll(user2EnabledImeList2))
                .isTrue();

        // Enable the IME.
        SystemUtil.runShellCommand(mUiAutomation,
                "ime enable --user " + user1.getIdentifier() + " " + imeToDisable.getId());
        List<InputMethodInfo> user1EnabledImeList3 =
                mInputMethodManager.getEnabledInputMethodListAsUser(user1);
        List<InputMethodInfo> user2EnabledImeList3 =
                mInputMethodManager.getEnabledInputMethodListAsUser(user2);
        assertWithMessage("User " + user1.getIdentifier() + " IME " + imeToDisable.getId()
                + " should be enabled").that(user1EnabledImeList3.contains(imeToDisable)).isTrue();
        assertWithMessage("Enabling user " + user1.getIdentifier()
                + " IME shouldn't affect user " + user2.getIdentifier())
                .that(user2EnabledImeList2.containsAll(user2EnabledImeList3)
                        && user2EnabledImeList3.containsAll(user2EnabledImeList2))
                .isTrue();
    }

    /**
     * Sets/resets IME for {@code user1}, then verifies that the IME settings for {@code user1}
     * has changed as expected and {@code user2} stays the same.
     */
    private void setImeForUser(UserHandle user1, UserHandle user2) throws IOException {
        // Reset IME for user1.
        SystemUtil.runShellCommand(mUiAutomation,
                "ime reset --user " + user1.getIdentifier());

        List<InputMethodInfo> user1EnabledImeList =
                mInputMethodManager.getEnabledInputMethodListAsUser(user1);
        assumeTrue("There must be at least two IME to test", user1EnabledImeList.size() >= 2);
        InputMethodInfo user1Ime = mInputMethodManager.getCurrentInputMethodInfoAsUser(user1);
        InputMethodInfo user2Ime = mInputMethodManager.getCurrentInputMethodInfoAsUser(user2);

        // Set to another IME for user1.
        InputMethodInfo anotherIme = null;
        for (InputMethodInfo info : user1EnabledImeList) {
            if (!info.equals(user1Ime)) {
                anotherIme = info;
            }
        }
        SystemUtil.runShellCommand(mUiAutomation,
                "ime set --user " + user1.getIdentifier() + " " + anotherIme.getId());
        InputMethodInfo user1Ime2 = mInputMethodManager.getCurrentInputMethodInfoAsUser(user1);
        InputMethodInfo user2Ime2 = mInputMethodManager.getCurrentInputMethodInfoAsUser(user2);
        assertWithMessage("The current IME for user " + user1.getIdentifier() + " is wrong")
                .that(user1Ime2).isEqualTo(anotherIme);
        assertWithMessage("The current IME for user " + user2.getIdentifier() + " shouldn't change")
                .that(user2Ime2).isEqualTo(user2Ime);

        // Reset IME for user1.
        SystemUtil.runShellCommand(mUiAutomation,
                "ime reset --user " + user1.getIdentifier());
        InputMethodInfo user1Ime3 = mInputMethodManager.getCurrentInputMethodInfoAsUser(user1);
        InputMethodInfo user2Ime3 = mInputMethodManager.getCurrentInputMethodInfoAsUser(user2);
        assertWithMessage("The current IME for user " + user1.getIdentifier() + " is wrong")
                .that(user1Ime3).isEqualTo(user1Ime);
        assertWithMessage("The current IME for user " + user2.getIdentifier() + " shouldn't change")
                .that(user2Ime3).isEqualTo(user2Ime);
    }
}

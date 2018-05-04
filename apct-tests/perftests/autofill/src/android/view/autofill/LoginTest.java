/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package android.view.autofill;

import android.app.Activity;
import android.os.Looper;
import android.os.Bundle;
import android.perftests.utils.PerfStatusReporter;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.StubActivity;
import android.provider.Settings;
import android.support.test.rule.ActivityTestRule;
import android.support.test.InstrumentationRegistry;
import com.android.perftests.autofill.R;

import java.util.Locale;
import java.util.Collection;
import java.util.Arrays;

import org.junit.Test;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.runner.RunWith;

import static android.view.autofill.AutofillManager.AutofillCallback.EVENT_INPUT_HIDDEN;
import static android.view.autofill.AutofillManager.AutofillCallback.EVENT_INPUT_SHOWN;

public class LoginTest extends AbstractAutofillPerfTestCase {

    private EditText mUsername;
    private EditText mPassword;
    private AutofillManager mAfm;

    public LoginTest() {
        super(R.layout.test_autofill_login);
    }

    @Override
    protected void onCreate(StubActivity activity) {
        View root = activity.getWindow().getDecorView();
        mUsername = root.findViewById(R.id.username);
        mPassword = root.findViewById(R.id.password);
        mAfm = activity.getSystemService(AutofillManager.class);
    }

    /**
     * This is the baseline test for focusing the 2 views when autofill is disabled.
     */
    @Test
    public void testFocus_noService() throws Throwable {
        resetService();

        focusTest(false);
    }

    /**
     * This time the service is called, but it returns a {@code null} response so the UI behaves
     * as if autofill was disabled.
     */
    @Test
    public void testFocus_serviceDoesNotAutofill() throws Throwable {
        MyAutofillService.newCannedResponse().reply();
        setService();

        focusTest(true);
    }

    /**
     * Now the service returns autofill data, for both username and password.
     */
    @Test
    public void testFocus_autofillBothFields() throws Throwable {
        MyAutofillService.newCannedResponse()
                .setUsername(mUsername.getAutofillId(), "user")
                .setPassword(mPassword.getAutofillId(), "pass")
                .reply();
        setService();

        focusTest(true);
    }

    /**
     * Now the service returns autofill data, but just for username.
     */
    @Test
    public void testFocus_autofillUsernameOnly() throws Throwable {
        // Must set ignored ids so focus on password does not trigger new requests
        MyAutofillService.newCannedResponse()
                .setUsername(mUsername.getAutofillId(), "user")
                .setIgnored(mPassword.getAutofillId())
                .reply();
        setService();

        focusTest(true);
    }

    private void focusTest(boolean waitForService) throws Throwable {
        // Must first focus in a field to trigger autofill and wait for service response
        // outside the loop
        mActivityRule.runOnUiThread(() -> mUsername.requestFocus());
        if (waitForService) {
            MyAutofillService.getLastFillRequest();
        }
        mActivityRule.runOnUiThread(() -> {
            BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
            while (state.keepRunning()) {
                mUsername.requestFocus();
                mPassword.requestFocus();
            }
        });
    }

    /**
     * This is the baseline test for changing the 2 views when autofill is disabled.
     */
    @Test
    public void testChange_noService() throws Throwable {
        resetService();

        changeTest(false);
    }

    /**
     * This time the service is called, but it returns a {@code null} response so the UI behaves
     * as if autofill was disabled.
     */
    @Test
    public void testChange_serviceDoesNotAutofill() throws Throwable {
        MyAutofillService.newCannedResponse().reply();
        setService();

        changeTest(true);
    }

    /**
     * Now the service returns autofill data, for both username and password.
     */
    @Test
    public void testChange_autofillBothFields() throws Throwable {
        MyAutofillService.newCannedResponse()
                .setUsername(mUsername.getAutofillId(), "user")
                .setPassword(mPassword.getAutofillId(), "pass")
                .reply();
        setService();

        changeTest(true);
    }

    /**
     * Now the service returns autofill data, but just for username.
     */
    @Test
    public void testChange_autofillUsernameOnly() throws Throwable {
        // Must set ignored ids so focus on password does not trigger new requests
        MyAutofillService.newCannedResponse()
                .setUsername(mUsername.getAutofillId(), "user")
                .setIgnored(mPassword.getAutofillId())
                .reply();
        setService();

        changeTest(true);
    }

    private void changeTest(boolean waitForService) throws Throwable {
        // Must first focus in a field to trigger autofill and wait for service response
        // outside the loop
        mActivityRule.runOnUiThread(() -> mUsername.requestFocus());
        if (waitForService) {
            MyAutofillService.getLastFillRequest();
        }
        mActivityRule.runOnUiThread(() -> {

            BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
            while (state.keepRunning()) {
                mUsername.setText("");
                mUsername.setText("a");
                mPassword.setText("");
                mPassword.setText("x");
            }
        });
    }

    @Test
    public void testCallbacks() throws Throwable {
        MyAutofillService.newCannedResponse()
                .setUsername(mUsername.getAutofillId(), "user")
                .setPassword(mPassword.getAutofillId(), "pass")
                .reply();
        setService();

        MyAutofillCallback callback = new MyAutofillCallback();
        mAfm.registerCallback(callback);

        // Must first focus in a field to trigger autofill and wait for service response
        // outside the loop
        mActivityRule.runOnUiThread(() -> mUsername.requestFocus());
        MyAutofillService.getLastFillRequest();
        callback.expectEvent(mUsername, EVENT_INPUT_SHOWN);

        // Now focus on password to prepare loop state
        mActivityRule.runOnUiThread(() -> mPassword.requestFocus());
        callback.expectEvent(mUsername, EVENT_INPUT_HIDDEN);
        callback.expectEvent(mPassword, EVENT_INPUT_SHOWN);

        // NOTE: we cannot run the whole loop inside the UI thread, because the autofill callback
        // is called on it, which would cause a deadlock on expectEvent().
        try {
            BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
            while (state.keepRunning()) {
                mActivityRule.runOnUiThread(() -> mUsername.requestFocus());
                callback.expectEvent(mPassword, EVENT_INPUT_HIDDEN);
                callback.expectEvent(mUsername, EVENT_INPUT_SHOWN);
                mActivityRule.runOnUiThread(() -> mPassword.requestFocus());
                callback.expectEvent(mUsername, EVENT_INPUT_HIDDEN);
                callback.expectEvent(mPassword, EVENT_INPUT_SHOWN);
            }

            // Sanity check
            callback.assertNoAsyncErrors();
        } finally {
            mAfm.unregisterCallback(callback);
        }
    }
}

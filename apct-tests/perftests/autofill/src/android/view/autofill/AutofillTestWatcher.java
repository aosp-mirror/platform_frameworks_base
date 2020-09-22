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

package android.view.autofill;

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Custom {@link TestWatcher} that does the setup and reset tasks for the tests.
 */
final class AutofillTestWatcher extends TestWatcher {

    private static final String TAG = "AutofillTestWatcher";
    private static final long GENERIC_TIMEOUT_MS = 10_000;

    private static ServiceWatcher sServiceWatcher;

    private String mOriginalLogLevel;

    @Override
    protected void starting(Description description) {
        super.starting(description);

        enableVerboseLog();
        MyAutofillService.resetStaticState();
        MyAutofillService.setEnabled(true);
        setServiceWatcher();
    }

    @Override
    protected void finished(Description description) {
        super.finished(description);

        restoreLogLevel();
        disableService();
        clearServiceWatcher();
    }

    void waitServiceConnect() throws InterruptedException {
        if (sServiceWatcher != null) {
            Log.d(TAG, "waitServiceConnect()");
            sServiceWatcher.waitOnConnected();
        }
    }

    private void enableService() {
        MyAutofillService.resetStaticState();
        MyAutofillService.setEnabled(true);
    }

    private void disableService() {
        // Must disable service so calls are ignored in case of errors during the test case;
        // otherwise, other tests will fail because these calls are made in the UI thread (as both
        // the service, the tests, and the app run in the same process).
        MyAutofillService.setEnabled(false);
    }

    private void enableVerboseLog() {
        mOriginalLogLevel = runShellCommand("cmd autofill get log_level");
        Log.d(TAG, "enableVerboseLog(), mOriginalLogLevel=" + mOriginalLogLevel);
        if (!mOriginalLogLevel.equals("verbose")) {
            runShellCommand("cmd autofill set log_level verbose");
        }
    }

    private void restoreLogLevel() {
        Log.w(TAG, "restoreLogLevel to " + mOriginalLogLevel);
        if (!mOriginalLogLevel.equals("verbose")) {
            runShellCommand("cmd autofill set log_level %s", mOriginalLogLevel);
        }
    }

    private static void setServiceWatcher() {
        if (sServiceWatcher == null) {
            sServiceWatcher = new ServiceWatcher();
        }
    }

    private static void clearServiceWatcher() {
        if (sServiceWatcher != null) {
            sServiceWatcher = null;
        }
    }

    public static final class ServiceWatcher {
        private final CountDownLatch mConnected = new CountDownLatch(1);

        public static void onConnected() {
            Log.i(TAG, "onConnected:  sServiceWatcher=" + sServiceWatcher);

            sServiceWatcher.mConnected.countDown();
        }

        @NonNull
        public void waitOnConnected() throws InterruptedException {
            await(mConnected, "not connected");
        }

        private void await(@NonNull CountDownLatch latch, @NonNull String fmt,
                @Nullable Object... args)
                throws InterruptedException {
            final boolean called = latch.await(GENERIC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!called) {
                throw new IllegalStateException(String.format(fmt, args)
                        + " in " + GENERIC_TIMEOUT_MS + "ms");
            }
        }
    }
}

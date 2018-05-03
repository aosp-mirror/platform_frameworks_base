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

import android.os.Looper;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.SettingsHelper;
import android.perftests.utils.SettingsStateKeeperRule;
import android.perftests.utils.ShellHelper;
import android.view.View;
import android.perftests.utils.StubActivity;
import android.provider.Settings;
import android.support.test.filters.LargeTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.InstrumentationRegistry;

import com.android.perftests.core.R;

import java.util.Locale;
import java.util.Collection;
import java.util.Arrays;

import org.junit.Test;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

/**
 * Base class for all autofill tests.
 */
@LargeTest
public abstract class AbstractAutofillPerfTestCase {

    @ClassRule
    public static final SettingsStateKeeperRule mServiceSettingsKeeper =
            new SettingsStateKeeperRule(InstrumentationRegistry.getTargetContext(),
                    Settings.Secure.AUTOFILL_SERVICE);

    @Rule
    public ActivityTestRule<StubActivity> mActivityRule =
            new ActivityTestRule<StubActivity>(StubActivity.class);

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private final int mLayoutId;

    protected AbstractAutofillPerfTestCase(int layoutId) {
        mLayoutId = layoutId;
    }

    /**
     * Prepares the activity so that by the time the test is run it has reference to its fields.
     */
    @Before
    public void prepareActivity() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            assertTrue("We should be running on the main thread",
                    Looper.getMainLooper().getThread() == Thread.currentThread());
            assertTrue("We should be running on the main thread",
                    Looper.myLooper() == Looper.getMainLooper());
            StubActivity activity = mActivityRule.getActivity();
            activity.setContentView(mLayoutId);
            onCreate(activity);
        });
    }

    @Before
    public void enableService() {
        MyAutofillService.resetStaticState();
        MyAutofillService.setEnabled(true);
    }

    @After
    public void disableService() {
        // Must disable service so calls are ignored in case of errors during the test case;
        // otherwise, other tests will fail because these calls are made in the UI thread (as both
        // the service, the tests, and the app run in the same process).
        MyAutofillService.setEnabled(false);
    }

    /**
     * Initializes the {@link StubActivity} after it was launched.
     */
    protected abstract void onCreate(StubActivity activity);

    /**
     * Uses the {@code settings} binary to set the autofill service.
     */
    protected void setService() {
        SettingsHelper.syncSet(InstrumentationRegistry.getTargetContext(),
                SettingsHelper.NAMESPACE_SECURE,
                Settings.Secure.AUTOFILL_SERVICE,
                MyAutofillService.COMPONENT_NAME);
    }

    /**
     * Uses the {@code settings} binary to reset the autofill service.
     */
    protected void resetService() {
        SettingsHelper.syncDelete(InstrumentationRegistry.getTargetContext(),
                SettingsHelper.NAMESPACE_SECURE,
                Settings.Secure.AUTOFILL_SERVICE);
    }
}

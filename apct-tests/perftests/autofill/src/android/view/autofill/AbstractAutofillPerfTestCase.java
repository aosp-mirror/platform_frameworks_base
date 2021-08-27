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
 * limitations under the License.
 */

package android.view.autofill;

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.PerfTestActivity;
import android.perftests.utils.SettingsStateKeeperRule;
import android.provider.Settings;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.RuleChain;

/**
 * Base class for all autofill tests.
 */
public abstract class AbstractAutofillPerfTestCase {

    private static final String TAG = "AbstractAutofillPerfTestCase";

    @ClassRule
    public static final SettingsStateKeeperRule mServiceSettingsKeeper =
            new SettingsStateKeeperRule(InstrumentationRegistry.getTargetContext(),
                    Settings.Secure.AUTOFILL_SERVICE);

    protected final AutofillTestWatcher mTestWatcher = MyAutofillService.getTestWatcher();
    protected ActivityTestRule<PerfTestActivity> mActivityRule =
            new ActivityTestRule<>(PerfTestActivity.class);
    protected PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Rule
    public final RuleChain mAllRules = RuleChain
            .outerRule(mTestWatcher)
            .around(mPerfStatusReporter)
            .around(mActivityRule);

    private final int mLayoutId;

    protected AbstractAutofillPerfTestCase(int layoutId) {
        mLayoutId = layoutId;
    }

    @BeforeClass
    public static void disableDefaultAugmentedService() {
        Log.v(TAG, "@BeforeClass: disableDefaultAugmentedService()");
        setDefaultAugmentedAutofillServiceEnabled(false);
    }

    @AfterClass
    public static void enableDefaultAugmentedService() {
        Log.v(TAG, "@AfterClass: enableDefaultAugmentedService()");
        setDefaultAugmentedAutofillServiceEnabled(true);
    }

    /**
     * Enables / disables the default augmented autofill service.
     */
    private static void setDefaultAugmentedAutofillServiceEnabled(boolean enabled) {
        Log.d(TAG, "setDefaultAugmentedAutofillServiceEnabled(): " + enabled);
        runShellCommand("cmd autofill set default-augmented-service-enabled 0 %s",
                Boolean.toString(enabled));
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
            PerfTestActivity activity = mActivityRule.getActivity();
            activity.setContentView(mLayoutId);
            onCreate(activity);
        });
    }

    /**
     * Initializes the {@link PerfTestActivity} after it was launched.
     */
    protected abstract void onCreate(PerfTestActivity activity);
}

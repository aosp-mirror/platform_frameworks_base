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
package android.view.contentcapture;

import static android.view.contentcapture.CustomTestActivity.INTENT_EXTRA_CUSTOM_VIEWS;
import static android.view.contentcapture.CustomTestActivity.INTENT_EXTRA_LAYOUT_ID;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.UserHandle;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.PerfTestActivity;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.compatibility.common.util.ActivitiesWatcher;
import com.android.compatibility.common.util.ActivitiesWatcher.ActivityWatcher;
import com.android.perftests.contentcapture.R;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;

/**
 * Base class for all content capture tests.
 */
public abstract class AbstractContentCapturePerfTestCase {

    private static final String TAG = AbstractContentCapturePerfTestCase.class.getSimpleName();
    protected static final long GENERIC_TIMEOUT_MS = 5_000;

    private static int sOriginalStayOnWhilePluggedIn;
    protected static final Instrumentation sInstrumentation = getInstrumentation();
    protected static final Context sContext = sInstrumentation.getTargetContext();

    protected ActivitiesWatcher mActivitiesWatcher;

    /** A simple activity as the task root to reduce the noise of pause and animation time. */
    protected Activity mEntryActivity;

    private MyContentCaptureService.ServiceWatcher mServiceWatcher;

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Rule
    public TestRule mServiceDisablerRule = (base, description) -> {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    base.evaluate();
                } finally {
                    Log.v(TAG, "@mServiceDisablerRule: safelyDisableService()");
                    safelyDisableService();
                }
            }
        };
    };

    private void safelyDisableService() {
        try {
            resetService();
            MyContentCaptureService.resetStaticState();

            if (mServiceWatcher != null) {
                mServiceWatcher.waitOnDestroy();
            }
        } catch (Throwable t) {
            Log.e(TAG, "error disabling service", t);
        }
    }

    /**
     * Sets the content capture service.
     */
    private static void setService(@NonNull String service) {
        final int userId = getCurrentUserId();
        Log.d(TAG, "Setting service for user " + userId + " to " + service);
        // TODO(b/123540602): use @TestingAPI to get max duration constant
        runShellCommand("cmd content_capture set temporary-service %d %s 119000", userId, service);
    }

    /**
     * Resets the content capture service.
     */
    private static void resetService() {
        final int userId = getCurrentUserId();
        Log.d(TAG, "Resetting back user " + userId + " to default service");
        runShellCommand("cmd content_capture set temporary-service %d", userId);
    }

    private static int getCurrentUserId() {
        return UserHandle.myUserId();
    }

    @BeforeClass
    public static void setStayAwake() {
        Log.v(TAG, "@BeforeClass: setStayAwake()");
        // Some test cases will restart the activity, and stay awake is necessary to ensure that
        // the test will not screen off during the test.
        // Keeping the activity screen on is not enough, screen off may occur between the activity
        // finished and the next start
        final int stayOnWhilePluggedIn = Settings.Global.getInt(sContext.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);
        sOriginalStayOnWhilePluggedIn = -1;
        if (stayOnWhilePluggedIn != BatteryManager.BATTERY_PLUGGED_ANY) {
            sOriginalStayOnWhilePluggedIn = stayOnWhilePluggedIn;
            // Keep the device awake during testing.
            setStayOnWhilePluggedIn(BatteryManager.BATTERY_PLUGGED_ANY);
        }
    }

    @AfterClass
    public static void resetStayAwake() {
        Log.v(TAG, "@AfterClass: resetStayAwake()");
        if (sOriginalStayOnWhilePluggedIn != -1) {
            setStayOnWhilePluggedIn(sOriginalStayOnWhilePluggedIn);
        }
    }

    private static void setStayOnWhilePluggedIn(int value) {
        runShellCommand(String.format("settings put global %s %d",
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, value));
    }

    @BeforeClass
    public static void setAllowSelf() {
        final ContentCaptureOptions options = new ContentCaptureOptions(null);
        Log.v(TAG, "@BeforeClass: setAllowSelf(): options=" + options);
        sContext.getApplicationContext().setContentCaptureOptions(options);
    }

    @AfterClass
    public static void unsetAllowSelf() {
        Log.v(TAG, "@AfterClass: unsetAllowSelf()");
        clearOptions();
    }

    protected static void clearOptions() {
        sContext.getApplicationContext().setContentCaptureOptions(null);
    }

    @BeforeClass
    public static void disableDefaultService() {
        Log.v(TAG, "@BeforeClass: disableDefaultService()");
        setDefaultServiceEnabled(false);
    }

    @AfterClass
    public static void enableDefaultService() {
        Log.v(TAG, "@AfterClass: enableDefaultService()");
        setDefaultServiceEnabled(true);
    }

    /**
     * Enables / disables the default service.
     */
    private static void setDefaultServiceEnabled(boolean enabled) {
        final int userId = getCurrentUserId();
        Log.d(TAG, "setDefaultServiceEnabled(user=" + userId + ", enabled= " + enabled + ")");
        runShellCommand("cmd content_capture set default-service-enabled %d %s", userId,
                Boolean.toString(enabled));
    }

    @Before
    public void prepareDevice() throws Exception {
        Log.v(TAG, "@Before: prepareDevice()");

        // Unlock screen.
        runShellCommand("input keyevent KEYCODE_WAKEUP");

        // Dismiss keyguard, in case it's set as "Swipe to unlock".
        runShellCommand("wm dismiss-keyguard");

        // Collapse notifications.
        runShellCommand("cmd statusbar collapse");
    }

    @Before
    public void registerLifecycleCallback() {
        Log.v(TAG, "@Before: Registering lifecycle callback");
        final Application app = (Application) sContext.getApplicationContext();
        mActivitiesWatcher = new ActivitiesWatcher(GENERIC_TIMEOUT_MS);
        app.registerActivityLifecycleCallbacks(mActivitiesWatcher);
    }

    @After
    public void unregisterLifecycleCallback() {
        Log.d(TAG, "@After: Unregistering lifecycle callback: " + mActivitiesWatcher);
        if (mActivitiesWatcher != null) {
            final Application app = (Application) sContext.getApplicationContext();
            app.unregisterActivityLifecycleCallbacks(mActivitiesWatcher);
        }
    }

    @Before
    public void setUp() {
        mEntryActivity = sInstrumentation.startActivitySync(
                PerfTestActivity.createLaunchIntent(sInstrumentation.getContext()));
    }

    @After
    public void tearDown() {
        mEntryActivity.finishAndRemoveTask();
    }

    /**
     * Sets {@link MyContentCaptureService} as the service for the current user and waits until
     * its created, then add the perf test package into allow list.
     */
    public MyContentCaptureService enableService() throws InterruptedException {
        if (mServiceWatcher != null) {
            throw new IllegalStateException("There Can Be Only One!");
        }

        mServiceWatcher = MyContentCaptureService.setServiceWatcher();
        setService(MyContentCaptureService.SERVICE_NAME);
        mServiceWatcher.setAllowSelf();
        return mServiceWatcher.waitOnCreate();
    }

    /** Wait for session paused. */
    public void waitForSessionPaused() throws InterruptedException {
        mServiceWatcher.waitSessionPaused();
    }

    @NonNull
    protected ActivityWatcher startWatcher() {
        return mActivitiesWatcher.watch(CustomTestActivity.class);
    }

    /**
     * Launch test activity with default login layout
     */
    protected CustomTestActivity launchActivity() {
        return launchActivity(R.layout.test_login_activity, 0);
    }

    /**
     * Returns the intent which will launch CustomTestActivity.
     */
    protected Intent getLaunchIntent(int layoutId, int numViews) {
        final Intent intent = new Intent(sContext, CustomTestActivity.class)
                // Use NEW_TASK because the context is not activity. It is still in the same task
                // of PerfTestActivity because of the same task affinity. Use NO_ANIMATION because
                // this test focuses on launch time instead of animation duration.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(INTENT_EXTRA_LAYOUT_ID, layoutId);
        intent.putExtra(INTENT_EXTRA_CUSTOM_VIEWS, numViews);
        return intent;
    }

    /**
     * Launch test activity with give layout and parameter
     */
    protected CustomTestActivity launchActivity(int layoutId, int numViews) {
        final Intent intent = getLaunchIntent(layoutId, numViews);
        return (CustomTestActivity) sInstrumentation.startActivitySync(intent);
    }
}

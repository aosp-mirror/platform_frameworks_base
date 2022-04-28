/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.window;

import static junit.framework.Assert.fail;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.annotation.NonNull;
import android.app.ActivityTaskManager;
import android.app.EmptyActivity;
import android.app.Instrumentation;
import android.os.RemoteException;
import android.support.test.uiautomator.UiDevice;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Integration test for back navigation
 */
public class BackNavigationTest {

    @Rule
    public final ActivityScenarioRule<EmptyActivity> mScenarioRule =
            new ActivityScenarioRule<>(EmptyActivity.class);
    private ActivityScenario<EmptyActivity> mScenario;
    private Instrumentation mInstrumentation;

    @Before
    public void setup() {
        mScenario = mScenarioRule.getScenario();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        try {
            UiDevice.getInstance(mInstrumentation).wakeUp();
        } catch (RemoteException ignored) {
        }
        mInstrumentation.getUiAutomation().adoptShellPermissionIdentity();
    }

    @Test
    public void registerCallback_initialized() {
        CountDownLatch latch = registerBackCallback();
        mScenario.moveToState(Lifecycle.State.RESUMED);
        assertCallbackIsCalled(latch);
    }

    @Test
    public void registerCallback_created() {
        mScenario.moveToState(Lifecycle.State.CREATED);
        CountDownLatch latch = registerBackCallback();
        mScenario.moveToState(Lifecycle.State.STARTED);
        mScenario.moveToState(Lifecycle.State.RESUMED);
        assertCallbackIsCalled(latch);
    }

    @Test
    public void registerCallback_resumed() {
        mScenario.moveToState(Lifecycle.State.CREATED);
        mScenario.moveToState(Lifecycle.State.STARTED);
        mScenario.moveToState(Lifecycle.State.RESUMED);
        CountDownLatch latch = registerBackCallback();
        assertCallbackIsCalled(latch);
    }

    private void assertCallbackIsCalled(CountDownLatch latch) {
        try {
            mInstrumentation.getUiAutomation().waitForIdle(500, 1000);
            BackNavigationInfo info = ActivityTaskManager.getService().startBackNavigation();
            assertNotNull("BackNavigationInfo is null", info);
            assertNotNull("OnBackInvokedCallback is null", info.getOnBackInvokedCallback());
            info.getOnBackInvokedCallback().onBackInvoked();
            assertTrue(latch.await(500, TimeUnit.MILLISECONDS));
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        } catch (InterruptedException ex) {
            fail("Application died before invoking the callback.\n" + ex.getMessage());
        } catch (TimeoutException ex) {
            fail(ex.getMessage());
        }
    }

    @NonNull
    private CountDownLatch registerBackCallback() {
        CountDownLatch backInvokedLatch = new CountDownLatch(1);
        CountDownLatch backRegisteredLatch = new CountDownLatch(1);
        mScenario.onActivity(activity -> {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    0, backInvokedLatch::countDown
            );
            backRegisteredLatch.countDown();
        });
        try {
            if (!backRegisteredLatch.await(100, TimeUnit.MILLISECONDS)) {
                fail("Back callback was not registered on the Activity thread. This might be "
                        + "an error with the test itself.");
            }
        } catch (InterruptedException e) {
            fail(e.getMessage());
        }
        return backInvokedLatch;
    }
}

/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.uiautomator.testrunner;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;

import com.android.uiautomator.core.InstrumentationUiAutomatorBridge;
import com.android.uiautomator.core.UiDevice;

/**
 * UI Automator test case that is executed on the device.
 */
public class UiAutomatorTestCase extends InstrumentationTestCase {

    private Bundle mParams;
    private IAutomationSupport mAutomationSupport;

    /**
     * Get current instance of {@link UiDevice}. Works similar to calling the static
     * {@link UiDevice#getInstance()} from anywhere in the test classes.
     * @since API Level 16
     */
    public UiDevice getUiDevice() {
        return UiDevice.getInstance();
    }

    /**
     * Get command line parameters. On the command line when passing <code>-e key value</code>
     * pairs, the {@link Bundle} will have the key value pairs conveniently available to the
     * tests.
     * @since API Level 16
     */
    public Bundle getParams() {
        return mParams;
    }

    void setAutomationSupport(IAutomationSupport automationSupport) {
        mAutomationSupport = automationSupport;
    }

    /**
     * Provides support for running tests to report interim status
     *
     * @return IAutomationSupport
     * @since API Level 16
     * @deprecated Use {@link Instrumentation#sendStatus(int, Bundle)} instead
     */
    public IAutomationSupport getAutomationSupport() {
        if (mAutomationSupport == null) {
            mAutomationSupport = new InstrumentationAutomationSupport(getInstrumentation());
        }
        return mAutomationSupport;
    }

    /**
     * Initializes this test case.
     *
     * @param params Instrumentation arguments.
     */
    void initialize(Bundle params) {
        mParams = params;

        // check if this is a monkey test mode
        String monkeyVal = mParams.getString("monkey");
        if (monkeyVal != null) {
            // only if the monkey key is specified, we alter the state of monkey
            // else we should leave things as they are.
            getInstrumentation().getUiAutomation().setRunAsMonkey(Boolean.valueOf(monkeyVal));
        }

        UiDevice.getInstance().initialize(new InstrumentationUiAutomatorBridge(
                getInstrumentation().getContext(),
                getInstrumentation().getUiAutomation()));
    }

    /**
     * Calls {@link SystemClock#sleep(long)} to sleep
     * @param ms is in milliseconds.
     * @since API Level 16
     */
    public void sleep(long ms) {
        SystemClock.sleep(ms);
    }
}

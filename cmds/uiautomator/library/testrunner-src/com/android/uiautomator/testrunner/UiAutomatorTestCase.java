/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.app.ActivityThread;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.uiautomator.core.UiDevice;

import junit.framework.TestCase;

import java.util.List;

/**
 * UI automation test should extend this class. This class provides access
 * to the following:
 * {@link UiDevice} instance
 * {@link Bundle} for command line parameters.
 * @since API Level 16
 * @deprecated New tests should be written using UI Automator 2.0 which is available as part of the
 * Android Testing Support Library.
 */
@Deprecated
public class UiAutomatorTestCase extends TestCase {

    private static final String DISABLE_IME = "disable_ime";
    private static final String DUMMY_IME_PACKAGE = "com.android.testing.dummyime";
    private static final int NOT_A_SUBTYPE_ID = -1;

    private UiDevice mUiDevice;
    private Bundle mParams;
    private IAutomationSupport mAutomationSupport;
    private boolean mShouldDisableIme = false;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mShouldDisableIme = "true".equals(mParams.getString(DISABLE_IME));
        if (mShouldDisableIme) {
            setDummyIme();
        }
    }

    @Override
    protected void tearDown() throws Exception {
        if (mShouldDisableIme) {
            restoreActiveIme();
        }
        super.tearDown();
    }

    /**
     * Get current instance of {@link UiDevice}. Works similar to calling the static
     * {@link UiDevice#getInstance()} from anywhere in the test classes.
     * @since API Level 16
     */
    public UiDevice getUiDevice() {
        return mUiDevice;
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

    /**
     * Provides support for running tests to report interim status
     *
     * @return IAutomationSupport
     * @since API Level 16
     */
    public IAutomationSupport getAutomationSupport() {
        return mAutomationSupport;
    }

    /**
     * package private
     * @param uiDevice
     */
    void setUiDevice(UiDevice uiDevice) {
        mUiDevice = uiDevice;
    }

    /**
     * package private
     * @param params
     */
    void setParams(Bundle params) {
        mParams = params;
    }

    void setAutomationSupport(IAutomationSupport automationSupport) {
        mAutomationSupport = automationSupport;
    }

    /**
     * Calls {@link SystemClock#sleep(long)} to sleep
     * @param ms is in milliseconds.
     * @since API Level 16
     */
    public void sleep(long ms) {
        SystemClock.sleep(ms);
    }

    private void setDummyIme() {
        Context context = ActivityThread.currentApplication();
        if (context == null) {
            throw new RuntimeException("ActivityThread.currentApplication() is null.");
        }
        InputMethodManager im = (InputMethodManager) context.getSystemService(
                Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> infos = im.getInputMethodList();
        String id = null;
        for (InputMethodInfo info : infos) {
            if (DUMMY_IME_PACKAGE.equals(info.getComponent().getPackageName())) {
                id = info.getId();
            }
        }
        if (id == null) {
            throw new RuntimeException(String.format(
                    "Required testing fixture missing: IME package (%s)", DUMMY_IME_PACKAGE));
        }
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        Settings.Secure.putInt(resolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE,
                NOT_A_SUBTYPE_ID);
        Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, id);
    }

    private void restoreActiveIme() {
        // TODO: figure out a way to restore active IME
        // Currently retrieving active IME requires querying secure settings provider, which is hard
        // to do without a Context; so the caveat here is that to make the post test device usable,
        // the active IME needs to be manually switched.
    }
}

/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import android.content.Context;
import android.content.Intent;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.BySelector;
import android.support.test.uiautomator.Configurator;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;

import java.util.concurrent.TimeoutException;

public class FilesActivityUiTest extends InstrumentationTestCase {

    private static final String TAG = "FilesActivityUiTest";
    private static final String TARGET_PKG = "com.android.documentsui";
    private static final String LAUNCHER_PKG = "com.android.launcher";
    private static final int ONE_SECOND = 1000;
    private static final int FIVE_SECONDS = 5 * ONE_SECOND;

    private ActionBar mBar;
    private UiDevice mDevice;
    private Context mContext;

    public void setUp() throws TimeoutException {
        // Initialize UiDevice instance.
        mDevice = UiDevice.getInstance(getInstrumentation());

        Configurator.getInstance().setToolType(MotionEvent.TOOL_TYPE_MOUSE);

        // Start from the home screen.
        mDevice.pressHome();
        mDevice.wait(Until.hasObject(By.pkg(LAUNCHER_PKG).depth(0)), FIVE_SECONDS);

        // Launch app.
        mContext = getInstrumentation().getContext();
        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(TARGET_PKG);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivity(intent);

        // Wait for the app to appear.
        mDevice.wait(Until.hasObject(By.pkg(TARGET_PKG).depth(0)), FIVE_SECONDS);
        mDevice.waitForIdle();

        mBar = new ActionBar();
    }

    public void testSwitchMode() throws Exception {
        UiObject2 mode = mBar.gridMode(100);
        if (mode != null) {
            mode.click();
            assertNotNull(mBar.listMode(ONE_SECOND));
        } else {
            mBar.listMode(100).click();
            assertNotNull(mBar.gridMode(ONE_SECOND));
        }
    }

    private class ActionBar {

        public UiObject2 gridMode(int timeout) {
            // Note that we're using By.desc rather than By.res, because of b/25285770
            BySelector selector = By.desc("Grid view");
            if (timeout > 0) {
                mDevice.wait(Until.findObject(selector), timeout);
            }
            return mDevice.findObject(selector);
        }

        public UiObject2 listMode(int timeout) {
            // Note that we're using By.desc rather than By.res, because of b/25285770
            BySelector selector = By.desc("List view");
            if (timeout > 0) {
                mDevice.wait(Until.findObject(selector), timeout);
            }
            return mDevice.findObject(selector);
        }
    }
}

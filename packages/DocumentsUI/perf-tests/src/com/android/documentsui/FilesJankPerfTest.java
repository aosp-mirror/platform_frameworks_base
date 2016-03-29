/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.documentsui.StressProvider.DEFAULT_AUTHORITY;
import static com.android.documentsui.StressProvider.STRESS_ROOT_0_ID;
import static com.android.documentsui.StressProvider.STRESS_ROOT_2_ID;

import android.app.Activity;
import android.os.RemoteException;
import android.test.suitebuilder.annotation.LargeTest;

import android.content.Intent;
import android.content.Context;
import android.support.test.jank.JankTest;
import android.support.test.jank.JankTestBase;
import android.support.test.uiautomator.UiDevice;
import android.support.test.jank.GfxMonitor;
import android.support.test.uiautomator.UiScrollable;
import android.util.Log;

import com.android.documentsui.FilesActivity;
import com.android.documentsui.bots.RootsListBot;
import com.android.documentsui.bots.DirectoryListBot;

@LargeTest
public class FilesJankPerfTest extends JankTestBase {
    private static final String DOCUMENTSUI_PACKAGE = "com.android.documentsui";
    private static final int MAX_FLINGS = 10;
    private static final int BOT_TIMEOUT = 5000;

    private RootsListBot mRootsListBot;
    private DirectoryListBot mDirListBot;
    private Activity mActivity = null;

    public void setUpInLoop() {
        final UiDevice device = UiDevice.getInstance(getInstrumentation());
        final Context context = getInstrumentation().getTargetContext();
        mRootsListBot = new RootsListBot(device, context, BOT_TIMEOUT);
        mDirListBot = new DirectoryListBot(device, context, BOT_TIMEOUT);

        final Intent intent = new Intent(context, FilesActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mActivity = getInstrumentation().startActivitySync(intent);
    }

    public void tearDownInLoop() {
        if (mActivity != null) {
            mActivity.finish();
            mActivity = null;
        }
    }

    public void setupAndOpenInLoop() throws Exception {
        setUpInLoop();
        openRoot();
    }

    public void openRoot() throws Exception {
        mRootsListBot.openRoot(STRESS_ROOT_2_ID);
    }

    @JankTest(expectedFrames=0, beforeLoop="setUpInLoop", afterLoop="tearDownInLoop")
    @GfxMonitor(processName=DOCUMENTSUI_PACKAGE)
    public void testOpenRootJankPerformance() throws Exception {
        openRoot();
        getInstrumentation().waitForIdleSync();
    }

    @JankTest(expectedFrames=0, beforeLoop="setupAndOpenInLoop", afterLoop="tearDownInLoop")
    @GfxMonitor(processName=DOCUMENTSUI_PACKAGE)
    public void testFlingJankPerformance() throws Exception {
        new UiScrollable(mDirListBot.findDocumentsList().getSelector()).flingToEnd(MAX_FLINGS);
        getInstrumentation().waitForIdleSync();
    }
}

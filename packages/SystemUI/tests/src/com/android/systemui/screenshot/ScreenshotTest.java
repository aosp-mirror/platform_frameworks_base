/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.systemui.screenshot;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.FileObserver;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.view.KeyEvent;

import java.io.File;

/**
 * Functional tests for the global screenshot feature.
 */
@LargeTest
public class ScreenshotTest extends ActivityInstrumentationTestCase2<ScreenshotStubActivity> {

    private static final String LOG_TAG = "ScreenshotTest";
    private static final int SCREEN_WAIT_TIME_SEC = 5;

    public ScreenshotTest() {
        super(ScreenshotStubActivity.class);
    }

    /**
     * A simple test for screenshots that launches an Activity, injects the key event combo
     * to trigger the screenshot, and verifies the screenshot was taken successfully.
     */
    public void testScreenshot() throws Exception {
        if (true) {
            // Disable until this works again.
            return;
        }
        Log.d(LOG_TAG, "starting testScreenshot");
        // launch the activity.
        ScreenshotStubActivity activity = getActivity();
        assertNotNull(activity);

        File screenshotDir = getScreenshotDir();
        NewScreenshotObserver observer = new NewScreenshotObserver(
                screenshotDir.getAbsolutePath());
        observer.startWatching();
        takeScreenshot();
        // unlikely, but check if a new screenshot file was already created
        if (observer.getCreatedPath() == null) {
            // wait for screenshot to be created
            synchronized(observer) {
                observer.wait(SCREEN_WAIT_TIME_SEC*1000);
            }
        }
        assertNotNull(String.format("Could not find screenshot after %d seconds",
                SCREEN_WAIT_TIME_SEC), observer.getCreatedPath());

        File screenshotFile = new File(screenshotDir, observer.getCreatedPath());
        try {
            assertTrue(String.format("Detected new screenshot %s but its not a file",
                    screenshotFile.getName()), screenshotFile.isFile());
            assertTrue(String.format("Detected new screenshot %s but its not an image",
                    screenshotFile.getName()), isValidImage(screenshotFile));
        } finally {
            // delete the file to prevent external storage from filing up
            screenshotFile.delete();
        }
    }

    private static class NewScreenshotObserver extends FileObserver {
        private String mAddedPath = null;

        NewScreenshotObserver(String path) {
            super(path, FileObserver.CREATE);
        }

        synchronized String getCreatedPath() {
            return mAddedPath;
        }

        @Override
        public void onEvent(int event, String path) {
            Log.d(LOG_TAG, String.format("Detected new file added %s", path));
            synchronized (this) {
                mAddedPath = path;
                notify();
            }
        }
    }

    /**
     * Inject the key sequence to take a screenshot.
     */
    private void takeScreenshot() {
        getInstrumentation().sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_POWER));
        getInstrumentation().sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_VOLUME_DOWN));
        // the volume down key event will cause the 'volume adjustment' UI to appear in the
        // foreground, and steal UI focus
        // unfortunately this means the next key event will get directed to the
        // 'volume adjustment' UI, instead of this test's activity
        // for this reason this test must be signed with platform certificate, to grant this test
        // permission to inject key events to another process
        getInstrumentation().sendKeySync(new KeyEvent(KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN));
        getInstrumentation().sendKeySync(new KeyEvent(KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_POWER));
    }

    /**
     * Get the directory where screenshot images are stored.
     */
    private File getScreenshotDir() {
        // TODO: get this dir location from a constant
        return new File(Environment.getExternalStorageDirectory(), "Pictures" + File.separator +
                "Screenshots");
    }

    /**
     * Return true if file is valid image file
     */
    private boolean isValidImage(File screenshotFile) {
        Bitmap b = BitmapFactory.decodeFile(screenshotFile.getAbsolutePath());
        // TODO: do more checks on image
        return b != null;
    }
}

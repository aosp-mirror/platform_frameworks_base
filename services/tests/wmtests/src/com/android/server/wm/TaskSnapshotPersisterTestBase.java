/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.graphics.GraphicBuffer.USAGE_HW_TEXTURE;
import static android.graphics.GraphicBuffer.USAGE_SW_READ_RARELY;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.ActivityManager.TaskSnapshot;
import android.content.ComponentName;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.UserManager;

import org.junit.After;
import org.junit.Before;

import java.io.File;

/**
 * Base class for tests that use a {@link TaskSnapshotPersister}.
 */
class TaskSnapshotPersisterTestBase extends WindowTestsBase {

    private static final Rect TEST_INSETS = new Rect(10, 20, 30, 40);
    static final File FILES_DIR = getInstrumentation().getTargetContext().getFilesDir();

    TaskSnapshotPersister mPersister;
    TaskSnapshotLoader mLoader;
    int mTestUserId;

    @Before
    public void setUp() {
        final UserManager um = UserManager.get(getInstrumentation().getTargetContext());
        mTestUserId = um.getUserHandle();
        mPersister = new TaskSnapshotPersister(mWm, userId -> FILES_DIR);
        mLoader = new TaskSnapshotLoader(mPersister);
        mPersister.start();
    }

    @After
    public void tearDown() {
        cleanDirectory();
    }

    private void cleanDirectory() {
        final File[] files = new File(FILES_DIR, "snapshots").listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.isDirectory()) {
                file.delete();
            }
        }
    }

    TaskSnapshot createSnapshot() {
        return new TaskSnapshotBuilder()
                .build();
    }

    /**
     * Builds a TaskSnapshot.
     */
    static class TaskSnapshotBuilder {

        private float mScale = 1f;
        private boolean mReducedResolution = false;
        private boolean mIsRealSnapshot = true;
        private boolean mIsTranslucent = false;
        private int mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        private int mSystemUiVisibility = 0;

        TaskSnapshotBuilder setScale(float scale) {
            mScale = scale;
            return this;
        }

        TaskSnapshotBuilder setReducedResolution(boolean reducedResolution) {
            mReducedResolution = reducedResolution;
            return this;
        }

        TaskSnapshotBuilder setIsRealSnapshot(boolean isRealSnapshot) {
            mIsRealSnapshot = isRealSnapshot;
            return this;
        }

        TaskSnapshotBuilder setIsTranslucent(boolean isTranslucent) {
            mIsTranslucent = isTranslucent;
            return this;
        }

        TaskSnapshotBuilder setWindowingMode(int windowingMode) {
            mWindowingMode = windowingMode;
            return this;
        }

        TaskSnapshotBuilder setSystemUiVisibility(int systemUiVisibility) {
            mSystemUiVisibility = systemUiVisibility;
            return this;
        }

        TaskSnapshot build() {
            final GraphicBuffer buffer = GraphicBuffer.create(100, 100, PixelFormat.RGBA_8888,
                    USAGE_HW_TEXTURE | USAGE_SW_READ_RARELY | USAGE_SW_READ_RARELY);
            Canvas c = buffer.lockCanvas();
            c.drawColor(Color.RED);
            buffer.unlockCanvasAndPost(c);
            return new TaskSnapshot(new ComponentName("", ""), buffer,
                    ColorSpace.get(ColorSpace.Named.SRGB), ORIENTATION_PORTRAIT, TEST_INSETS,
                    mReducedResolution, mScale, mIsRealSnapshot,
                    mWindowingMode, mSystemUiVisibility, mIsTranslucent);
        }
    }
}

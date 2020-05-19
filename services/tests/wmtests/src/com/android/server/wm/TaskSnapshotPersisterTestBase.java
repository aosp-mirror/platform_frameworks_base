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

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.ActivityManager.TaskSnapshot;
import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.view.Surface;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.File;
import java.util.function.Predicate;

/**
 * Base class for tests that use a {@link TaskSnapshotPersister}.
 */
class TaskSnapshotPersisterTestBase extends WindowTestsBase {

    private static final Rect TEST_INSETS = new Rect(10, 20, 30, 40);
    static final File FILES_DIR = getInstrumentation().getTargetContext().getFilesDir();
    static final long MOCK_SNAPSHOT_ID = 12345678;

    private ContextWrapper mContextSpy;
    private Resources mResourcesSpy;
    TaskSnapshotPersister mPersister;
    TaskSnapshotLoader mLoader;
    int mTestUserId;
    float mHighResScale;
    float mLowResScale;

    TaskSnapshotPersisterTestBase(float highResScale, float lowResScale) {
        super();
        mHighResScale = highResScale;
        mLowResScale = lowResScale;
    }

    @BeforeClass
    public static void setUpOnce() {
        final UserManagerInternal userManager = mock(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, userManager);
    }

    @AfterClass
    public static void tearDownOnce() {
        LocalServices.removeServiceForTest(UserManagerInternal.class);
    }

    @Before
    public void setUp() {
        final UserManager um = UserManager.get(getInstrumentation().getTargetContext());
        mTestUserId = um.getUserHandle();

        final UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        when(userManagerInternal.isUserUnlocked(mTestUserId)).thenReturn(true);

        mContextSpy = spy(new ContextWrapper(mWm.mContext));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);
        when(mResourcesSpy.getFloat(
                com.android.internal.R.dimen.config_highResTaskSnapshotScale))
                .thenReturn(mHighResScale);
        when(mResourcesSpy.getFloat(
                com.android.internal.R.dimen.config_lowResTaskSnapshotScale))
                .thenReturn(mLowResScale);

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

    protected static void assertTrueForFiles(File[] files, Predicate<File> predicate,
            String message) {
        for (File file : files) {
            assertTrue(file.getName() + message, predicate.test(file));
        }
    }

    /**
     * Builds a TaskSnapshot.
     */
    static class TaskSnapshotBuilder {
        private static final int SNAPSHOT_WIDTH = 100;
        private static final int SNAPSHOT_HEIGHT = 100;

        private float mScaleFraction = 1f;
        private boolean mIsRealSnapshot = true;
        private boolean mIsTranslucent = false;
        private int mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        private int mSystemUiVisibility = 0;
        private int mRotation = Surface.ROTATION_0;

        TaskSnapshotBuilder() {
        }

        TaskSnapshotBuilder setScaleFraction(float scale) {
            mScaleFraction = scale;
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

        TaskSnapshotBuilder setRotation(int rotation) {
            mRotation = rotation;
            return this;
        }

        TaskSnapshot build() {
            // To satisfy existing tests, ensure the graphics buffer is always 100x100, and
            // compute the ize of the task according to mScaleFraction.
            Point taskSize = new Point((int) (SNAPSHOT_WIDTH / mScaleFraction),
                    (int) (SNAPSHOT_HEIGHT / mScaleFraction));
            final GraphicBuffer buffer = GraphicBuffer.create(SNAPSHOT_WIDTH, SNAPSHOT_HEIGHT,
                    PixelFormat.RGBA_8888,
                    USAGE_HW_TEXTURE | USAGE_SW_READ_RARELY | USAGE_SW_READ_RARELY);
            Canvas c = buffer.lockCanvas();
            c.drawColor(Color.RED);
            buffer.unlockCanvasAndPost(c);
            return new TaskSnapshot(MOCK_SNAPSHOT_ID, new ComponentName("", ""), buffer,
                    ColorSpace.get(ColorSpace.Named.SRGB), ORIENTATION_PORTRAIT,
                    mRotation, taskSize, TEST_INSETS,
                    // When building a TaskSnapshot with the Builder class, isLowResolution
                    // is always false. Low-res snapshots are only created when loading from
                    // disk.
                    false /* isLowResolution */,
                    mIsRealSnapshot, mWindowingMode, mSystemUiVisibility, mIsTranslucent);
        }
    }
}

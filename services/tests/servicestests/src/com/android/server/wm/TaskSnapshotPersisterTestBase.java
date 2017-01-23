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
 * limitations under the License
 */

package com.android.server.wm;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.graphics.GraphicBuffer.USAGE_HW_TEXTURE;
import static android.graphics.GraphicBuffer.USAGE_SW_READ_RARELY;

import android.app.ActivityManager.TaskSnapshot;
import android.content.pm.UserInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.UserManager;
import android.support.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.File;

/**
 * Base class for tests that use a {@link TaskSnapshotPersister}.
 */
class TaskSnapshotPersisterTestBase extends WindowTestsBase {

    private static final String TEST_USER_NAME = "TaskSnapshotPersisterTest User";
    private static final Rect TEST_INSETS = new Rect(10, 20, 30, 40);

    TaskSnapshotPersister mPersister;
    TaskSnapshotLoader mLoader;
    static int sTestUserId;
    static File sFilesDir;
    private static UserManager sUserManager;

    @BeforeClass
    public static void setUpUser() {
        sUserManager = UserManager.get(InstrumentationRegistry.getContext());
        sTestUserId = createUser(TEST_USER_NAME, 0);
        sFilesDir = InstrumentationRegistry.getContext().getFilesDir();
    }

    @AfterClass
    public static void tearDownUser() {
        removeUser(sTestUserId);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mPersister = new TaskSnapshotPersister(
                userId -> sFilesDir);
        mLoader = new TaskSnapshotLoader(mPersister);
        mPersister.start();
    }

    @After
    public void tearDown() throws Exception {
        cleanDirectory();
    }

    private static int createUser(String name, int flags) {
        UserInfo user = sUserManager.createUser(name, flags);
        if (user == null) {
            Assert.fail("Error while creating the test user: " + TEST_USER_NAME);
        }
        return user.id;
    }

    private static void removeUser(int userId) {
        if (!sUserManager.removeUser(userId)) {
            Assert.fail("Error while removing the test user: " + TEST_USER_NAME);
        }
    }

    private void cleanDirectory() {
        for (File file : new File(sFilesDir, "snapshots").listFiles()) {
            if (!file.isDirectory()) {
                file.delete();
            }
        }
    }

    TaskSnapshot createSnapshot() {
        GraphicBuffer buffer = GraphicBuffer.create(100, 100, PixelFormat.RGBA_8888,
                USAGE_HW_TEXTURE | USAGE_SW_READ_RARELY | USAGE_SW_READ_RARELY);
        Canvas c = buffer.lockCanvas();
        c.drawColor(Color.RED);
        buffer.unlockCanvasAndPost(c);
        return new TaskSnapshot(buffer, ORIENTATION_PORTRAIT, TEST_INSETS);
    }
}

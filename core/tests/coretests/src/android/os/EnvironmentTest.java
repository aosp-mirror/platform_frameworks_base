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

package android.os;

import static android.os.Environment.HAS_ANDROID;
import static android.os.Environment.HAS_DCIM;
import static android.os.Environment.HAS_DOWNLOADS;
import static android.os.Environment.HAS_OTHER;
import static android.os.Environment.classifyExternalStorageDirectory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.AppOpsManager;
import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public class EnvironmentTest {
    private File dir;

    private static Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    /**
     * Sets {@code mode} for the given {@code ops} and the given {@code uid}.
     *
     * <p>This method drops shell permission identity.
     */
    private static void setAppOpsModeForUid(int uid, int mode, String... ops) {
        if (ops == null) {
            return;
        }
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity();
        try {
            for (String op : ops) {
                getContext().getSystemService(AppOpsManager.class).setUidMode(op, uid, mode);
            }
        } finally {
            InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Before
    public void setUp() throws Exception {
        dir = getContext().getDir("testing", Context.MODE_PRIVATE);
        FileUtils.deleteContents(dir);
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(dir);
    }

    @Test
    public void testClassify_empty() {
        assertEquals(0, classifyExternalStorageDirectory(dir));
    }

    @Test
    public void testClassify_emptyDirs() {
        Environment.buildPath(dir, "DCIM").mkdirs();
        Environment.buildPath(dir, "DCIM", "January").mkdirs();
        Environment.buildPath(dir, "Downloads").mkdirs();
        Environment.buildPath(dir, "LOST.DIR").mkdirs();
        assertEquals(0, classifyExternalStorageDirectory(dir));
    }

    @Test
    public void testClassify_emptyFactory() throws Exception {
        Environment.buildPath(dir, "autorun.inf").createNewFile();
        Environment.buildPath(dir, "LaunchU3.exe").createNewFile();
        Environment.buildPath(dir, "LaunchPad.zip").createNewFile();
        assertEquals(0, classifyExternalStorageDirectory(dir));
    }

    @Test
    public void testClassify_photos() throws Exception {
        Environment.buildPath(dir, "DCIM").mkdirs();
        Environment.buildPath(dir, "DCIM", "IMG_1024.JPG").createNewFile();
        Environment.buildPath(dir, "Download").mkdirs();
        Environment.buildPath(dir, "Download", "foobar.pdf").createNewFile();
        assertEquals(HAS_DCIM | HAS_DOWNLOADS, classifyExternalStorageDirectory(dir));
    }

    @Test
    public void testClassify_other() throws Exception {
        Environment.buildPath(dir, "Android").mkdirs();
        Environment.buildPath(dir, "Android", "com.example").mkdirs();
        Environment.buildPath(dir, "Android", "com.example", "internal.dat").createNewFile();
        Environment.buildPath(dir, "Linux").mkdirs();
        Environment.buildPath(dir, "Linux", "install-amd64-minimal-20170907.iso").createNewFile();
        assertEquals(HAS_ANDROID | HAS_OTHER, classifyExternalStorageDirectory(dir));
    }

    @Test
    public void testClassify_otherRoot() throws Exception {
        Environment.buildPath(dir, "Taxes.pdf").createNewFile();
        assertEquals(HAS_OTHER, classifyExternalStorageDirectory(dir));
    }

    @Test
    public void testIsExternalStorageManager() throws Exception {
        assertFalse(Environment.isExternalStorageManager());
        try {
            setAppOpsModeForUid(Process.myUid(), AppOpsManager.MODE_ALLOWED,
                    AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE);
            assertTrue(Environment.isExternalStorageManager());
        } finally {
            setAppOpsModeForUid(Process.myUid(), AppOpsManager.MODE_DEFAULT,
                    AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE);
        }
    }
}

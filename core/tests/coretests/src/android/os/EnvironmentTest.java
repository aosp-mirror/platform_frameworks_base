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

    private Context getContext() {
        return InstrumentationRegistry.getContext();
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
}

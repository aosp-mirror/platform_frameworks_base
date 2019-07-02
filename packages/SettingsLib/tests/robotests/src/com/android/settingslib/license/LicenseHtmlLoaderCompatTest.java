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

package com.android.settingslib.license;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = LicenseHtmlLoaderCompatTest.ShadowLicenseHtmlLoaderCompat.class)
public class LicenseHtmlLoaderCompatTest {

    @Mock
    private Context mContext;
    private LicenseHtmlLoaderCompat mLoader;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLoader = new LicenseHtmlLoaderCompat(mContext);
    }

    @After
    public void tearDown() {
        ShadowLicenseHtmlLoaderCompat.reset();
    }

    @Test
    public void testLoadInBackground() {
        ArrayList<File> xmlFiles = new ArrayList<>();
        xmlFiles.add(new File("test.xml"));
        File cachedHtmlFile = new File("test.html");

        setupFakeData(xmlFiles, cachedHtmlFile, true, true);

        assertThat(mLoader.loadInBackground()).isEqualTo(cachedHtmlFile);
    }

    @Test
    public void testLoadInBackgroundWithNoVaildXmlFiles() {
        ArrayList<File> xmlFiles = new ArrayList<>();
        File cachedHtmlFile = new File("test.html");

        setupFakeData(xmlFiles, cachedHtmlFile, true, true);

        assertThat(mLoader.loadInBackground()).isNull();
    }

    @Test
    public void testLoadInBackgroundWithNonOutdatedCachedHtmlFile() {
        ArrayList<File> xmlFiles = new ArrayList<>();
        xmlFiles.add(new File("test.xml"));
        File cachedHtmlFile = new File("test.html");

        setupFakeData(xmlFiles, cachedHtmlFile, false, true);

        assertThat(mLoader.loadInBackground()).isEqualTo(cachedHtmlFile);
    }

    @Test
    public void testLoadInBackgroundWithGenerateHtmlFileFailed() {
        ArrayList<File> xmlFiles = new ArrayList<>();
        xmlFiles.add(new File("test.xml"));
        File cachedHtmlFile = new File("test.html");

        setupFakeData(xmlFiles, cachedHtmlFile, true, false);

        assertThat(mLoader.loadInBackground()).isNull();
    }

    void setupFakeData(ArrayList<File> xmlFiles,
            File cachedHtmlFile, boolean isCachedHtmlFileOutdated,
            boolean generateHtmlFileSucceeded) {

        ShadowLicenseHtmlLoaderCompat.sValidXmlFiles = xmlFiles;
        ShadowLicenseHtmlLoaderCompat.sCachedHtmlFile = cachedHtmlFile;
        ShadowLicenseHtmlLoaderCompat.sIsCachedHtmlFileOutdated = isCachedHtmlFileOutdated;
        ShadowLicenseHtmlLoaderCompat.sGenerateHtmlFileSucceeded = generateHtmlFileSucceeded;
    }

    @Implements(LicenseHtmlLoaderCompat.class)
    public static class ShadowLicenseHtmlLoaderCompat {

        private static List<File> sValidXmlFiles;
        private static File sCachedHtmlFile;
        private static boolean sIsCachedHtmlFileOutdated;
        private static boolean sGenerateHtmlFileSucceeded;

        @Resetter
        public static void reset() {
            sValidXmlFiles = null;
            sCachedHtmlFile = null;
            sIsCachedHtmlFileOutdated = false;
            sGenerateHtmlFileSucceeded = false;
        }

        @Implementation
        protected List<File> getVaildXmlFiles() {
            return sValidXmlFiles;
        }

        @Implementation
        protected File getCachedHtmlFile(Context context) {
            return sCachedHtmlFile;
        }

        @Implementation
        protected boolean isCachedHtmlFileOutdated(List<File> xmlFiles,
                File cachedHtmlFile) {
            return sIsCachedHtmlFileOutdated;
        }

        @Implementation
        protected boolean generateHtmlFile(Context context, List<File> xmlFiles,
                File htmlFile) {
            return sGenerateHtmlFileSucceeded;
        }
    }
}

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
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;

import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.ArrayList;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class LicenseHtmlLoaderTest {
    @Mock
    private Context mContext;

    LicenseHtmlLoader newLicenseHtmlLoader(ArrayList<File> xmlFiles,
            File cachedHtmlFile, boolean isCachedHtmlFileOutdated,
            boolean generateHtmlFileSucceeded) {
        LicenseHtmlLoader loader = spy(new LicenseHtmlLoader(mContext));
        doReturn(xmlFiles).when(loader).getVaildXmlFiles();
        doReturn(cachedHtmlFile).when(loader).getCachedHtmlFile();
        doReturn(isCachedHtmlFileOutdated).when(loader).isCachedHtmlFileOutdated(any(), any());
        doReturn(generateHtmlFileSucceeded).when(loader).generateHtmlFile(any(), any());
        return loader;
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testLoadInBackground() {
        ArrayList<File> xmlFiles = new ArrayList();
        xmlFiles.add(new File("test.xml"));
        File cachedHtmlFile = new File("test.html");

        LicenseHtmlLoader loader = newLicenseHtmlLoader(xmlFiles, cachedHtmlFile, true, true);

        assertThat(loader.loadInBackground()).isEqualTo(cachedHtmlFile);
        verify(loader).generateHtmlFile(any(), any());
    }

    @Test
    public void testLoadInBackgroundWithNoVaildXmlFiles() {
        ArrayList<File> xmlFiles = new ArrayList();
        File cachedHtmlFile = new File("test.html");

        LicenseHtmlLoader loader = newLicenseHtmlLoader(xmlFiles, cachedHtmlFile, true, true);

        assertThat(loader.loadInBackground()).isNull();
        verify(loader, never()).generateHtmlFile(any(), any());
    }

    @Test
    public void testLoadInBackgroundWithNonOutdatedCachedHtmlFile() {
        ArrayList<File> xmlFiles = new ArrayList();
        xmlFiles.add(new File("test.xml"));
        File cachedHtmlFile = new File("test.html");

        LicenseHtmlLoader loader = newLicenseHtmlLoader(xmlFiles, cachedHtmlFile, false, true);

        assertThat(loader.loadInBackground()).isEqualTo(cachedHtmlFile);
        verify(loader, never()).generateHtmlFile(any(), any());
    }

    @Test
    public void testLoadInBackgroundWithGenerateHtmlFileFailed() {
        ArrayList<File> xmlFiles = new ArrayList();
        xmlFiles.add(new File("test.xml"));
        File cachedHtmlFile = new File("test.html");

        LicenseHtmlLoader loader = newLicenseHtmlLoader(xmlFiles, cachedHtmlFile, true, false);

        assertThat(loader.loadInBackground()).isNull();
        verify(loader).generateHtmlFile(any(), any());
    }
}

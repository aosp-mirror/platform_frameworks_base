/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.protolog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.zip.GZIPOutputStream;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class LegacyProtoLogViewerConfigReaderTest {
    private static final String TEST_VIEWER_CONFIG = "{\n"
            + "  \"version\": \"1.0.0\",\n"
            + "  \"messages\": {\n"
            + "    \"70933285\": {\n"
            + "      \"message\": \"Test completed successfully: %b\",\n"
            + "      \"level\": \"ERROR\",\n"
            + "      \"group\": \"GENERIC_WM\"\n"
            + "    },\n"
            + "    \"1792430067\": {\n"
            + "      \"message\": \"Attempted to add window to a display that does not exist: %d."
            + "  Aborting.\",\n"
            + "      \"level\": \"WARN\",\n"
            + "      \"group\": \"GENERIC_WM\"\n"
            + "    },\n"
            + "    \"1352021864\": {\n"
            + "      \"message\": \"Test 2\",\n"
            + "      \"level\": \"WARN\",\n"
            + "      \"group\": \"GENERIC_WM\"\n"
            + "    },\n"
            + "    \"409412266\": {\n"
            + "      \"message\": \"Window %s is already added\",\n"
            + "      \"level\": \"WARN\",\n"
            + "      \"group\": \"GENERIC_WM\"\n"
            + "    }\n"
            + "  },\n"
            + "  \"groups\": {\n"
            + "    \"GENERIC_WM\": {\n"
            + "      \"tag\": \"WindowManager\"\n"
            + "    }\n"
            + "  }\n"
            + "}\n";


    private LegacyProtoLogViewerConfigReader
            mConfig = new LegacyProtoLogViewerConfigReader();
    private File mTestViewerConfig;

    @Before
    public void setUp() throws IOException {
        mTestViewerConfig = File.createTempFile("testConfig", ".json.gz");
        OutputStreamWriter writer = new OutputStreamWriter(
                new GZIPOutputStream(new FileOutputStream(mTestViewerConfig)));
        writer.write(TEST_VIEWER_CONFIG);
        writer.close();
    }

    @After
    public void tearDown() {
        //noinspection ResultOfMethodCallIgnored
        mTestViewerConfig.delete();
    }

    @Test
    public void getViewerString_notLoaded() {
        assertNull(mConfig.getViewerString(1));
    }

    @Test
    public void loadViewerConfig() {
        mConfig.loadViewerConfig(msg -> {}, mTestViewerConfig.getAbsolutePath());
        assertEquals("Test completed successfully: %b", mConfig.getViewerString(70933285));
        assertEquals("Test 2", mConfig.getViewerString(1352021864));
        assertEquals("Window %s is already added", mConfig.getViewerString(409412266));
        assertNull(mConfig.getViewerString(1));
    }

    @Test
    public void loadViewerConfig_invalidFile() {
        mConfig.loadViewerConfig(msg -> {}, "/tmp/unknown/file/does/not/exist");
        // No exception is thrown.
        assertNull(mConfig.getViewerString(1));
    }
}

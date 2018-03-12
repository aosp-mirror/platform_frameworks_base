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

package android.view.textclassifier.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import android.metrics.LogMaker;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArrayMap;
import android.view.textclassifier.GenerateLinksLogger;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class GenerateLinksLoggerTest {

    private static final String PACKAGE_NAME = "packageName";
    private static final String ZERO = "0";
    private static final int LATENCY_MS = 123;

    @Test
    public void testLogGenerateLinks() {
        final String phoneText = "+12122537077";
        final String addressText = "1600 Amphitheater Parkway, Mountain View, CA";
        final String testText = "The number is " + phoneText + ", the address is " + addressText;
        final int phoneOffset = testText.indexOf(phoneText);
        final int addressOffset = testText.indexOf(addressText);

        final Map<String, Float> phoneEntityScores = new ArrayMap<>();
        phoneEntityScores.put(TextClassifier.TYPE_PHONE, 0.9f);
        phoneEntityScores.put(TextClassifier.TYPE_OTHER, 0.1f);
        final Map<String, Float> addressEntityScores = new ArrayMap<>();
        addressEntityScores.put(TextClassifier.TYPE_ADDRESS, 1f);

        TextLinks links = new TextLinks.Builder(testText)
                .addLink(phoneOffset, phoneOffset + phoneText.length(), phoneEntityScores)
                .addLink(addressOffset, addressOffset + addressText.length(), addressEntityScores)
                .build();

        // Set up mock.
        MetricsLogger metricsLogger = mock(MetricsLogger.class);
        ArgumentCaptor<LogMaker> logMakerCapture = ArgumentCaptor.forClass(LogMaker.class);
        doNothing().when(metricsLogger).write(logMakerCapture.capture());

        // Generate the log.
        GenerateLinksLogger logger = new GenerateLinksLogger(1 /* sampleRate */, metricsLogger);
        logger.logGenerateLinks(testText, links, PACKAGE_NAME, LATENCY_MS);

        // Validate.
        List<LogMaker> logs = logMakerCapture.getAllValues();
        assertEquals(3, logs.size());
        assertHasLog(logs, "" /* entityType */, 2, phoneText.length() + addressText.length(),
                testText.length());
        assertHasLog(logs, TextClassifier.TYPE_ADDRESS, 1, addressText.length(),
                testText.length());
        assertHasLog(logs, TextClassifier.TYPE_PHONE, 1, phoneText.length(),
                testText.length());
    }

    private void assertHasLog(List<LogMaker> logs, String entityType, int numLinks,
            int linkTextLength, int textLength) {
        for (LogMaker log : logs) {
            if (!entityType.equals(getEntityType(log))) {
                continue;
            }
            assertEquals(PACKAGE_NAME, log.getPackageName());
            assertNotNull(Objects.toString(log.getTaggedData(MetricsEvent.FIELD_LINKIFY_CALL_ID)));
            assertEquals(numLinks, getIntValue(log, MetricsEvent.FIELD_LINKIFY_NUM_LINKS));
            assertEquals(linkTextLength, getIntValue(log, MetricsEvent.FIELD_LINKIFY_LINK_LENGTH));
            assertEquals(textLength, getIntValue(log, MetricsEvent.FIELD_LINKIFY_TEXT_LENGTH));
            assertEquals(LATENCY_MS, getIntValue(log, MetricsEvent.FIELD_LINKIFY_LATENCY));
            return;
        }
        fail("No log for entity type \"" + entityType + "\"");
    }

    private static String getEntityType(LogMaker log) {
        return Objects.toString(log.getTaggedData(MetricsEvent.FIELD_LINKIFY_ENTITY_TYPE), "");
    }

    private static int getIntValue(LogMaker log, int eventField) {
        return Integer.parseInt(Objects.toString(log.getTaggedData(eventField), ZERO));
    }
}

/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.libcore.regression;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xml.sax.InputSource;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.Arrays;
import java.util.Collection;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

// http://code.google.com/p/android/issues/detail?id=18102
@RunWith(JUnitParamsRunner.class)
@LargeTest
public final class XMLEntitiesPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public static Collection<Object[]> getData() {
        return Arrays.asList(
                new Object[][] {
                    {10, 0},
                    {10, 0.5f},
                    {10, 1.0f},
                    {100, 0},
                    {100, 0.5f},
                    {100, 1.0f},
                    {1000, 0},
                    {1000, 0.5f},
                    {1000, 1.0f}
                });
    }

    private XmlPullParserFactory mXmlPullParserFactory;
    private DocumentBuilderFactory mDocumentBuilderFactory;

    /** a string like {@code <doc>&amp;&amp;++</doc>}. */
    private String mXml;

    public void setUp(int length, float entityFraction) throws Exception {
        mXmlPullParserFactory = XmlPullParserFactory.newInstance();
        mDocumentBuilderFactory = DocumentBuilderFactory.newInstance();

        StringBuilder xmlBuilder = new StringBuilder();
        xmlBuilder.append("<doc>");
        for (int i = 0; i < (length * entityFraction); i++) {
            xmlBuilder.append("&amp;");
        }
        while (xmlBuilder.length() < length) {
            xmlBuilder.append("+");
        }
        xmlBuilder.append("</doc>");
        mXml = xmlBuilder.toString();
    }

    @Test
    @Parameters(method = "getData")
    public void timeXmlParser(int length, float entityFraction) throws Exception {
        setUp(length, entityFraction);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            XmlPullParser parser = mXmlPullParserFactory.newPullParser();
            parser.setInput(new StringReader(mXml));
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                // Keep running
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeDocumentBuilder(int length, float entityFraction) throws Exception {
        setUp(length, entityFraction);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            DocumentBuilder documentBuilder = mDocumentBuilderFactory.newDocumentBuilder();
            documentBuilder.parse(new InputSource(new StringReader(mXml)));
        }
    }
}

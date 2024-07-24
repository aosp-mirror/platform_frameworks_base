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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.util.Xml;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import libcore.util.XmlObjectFactory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Compares various kinds of method invocation.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class XmlSerializerPerfTest {

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeFastSerializer_nonIndent_depth100() throws IOException {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            XmlSerializer serializer = Xml.newFastSerializer();
            runTest(serializer, 100);
        }
    }

    @Test
    public void timeFastSerializer_indent_depth100() throws IOException {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            XmlSerializer serializer = Xml.newFastSerializer();
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            runTest(serializer, 100);
        }
    }

    @Test
    public void timeKXmlSerializer_nonIndent_depth100() throws IOException {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            XmlSerializer serializer = XmlObjectFactory.newXmlSerializer();
            runTest(serializer, 100);
        }
    }

    @Test
    public void timeKXmlSerializer_indent_depth100() throws IOException {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            XmlSerializer serializer = XmlObjectFactory.newXmlSerializer();
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            runTest(serializer, 100);
        }
    }

    private void runTest(XmlSerializer serializer, int depth) throws IOException {
        File file = File.createTempFile(XmlSerializerPerfTest.class.getSimpleName(), "tmp");
        try (OutputStream out = new FileOutputStream(file)) {
            serializer.setOutput(out, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            writeContent(serializer, depth);
            serializer.endDocument();
        }
    }

    private void writeContent(XmlSerializer serializer, int depth) throws IOException {
        serializer.startTag(null, "tag");
        serializer.attribute(null, "attribute", "value1");
        if (depth > 0) {
            writeContent(serializer, depth - 1);
        }
        serializer.endTag(null, "tag");
    }

}

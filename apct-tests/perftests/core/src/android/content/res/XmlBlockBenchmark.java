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

package android.content.res;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.content.Context;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.text.TextUtils;
import android.util.Log;

import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.perftests.core.R;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.function.Function;
import java.util.function.Supplier;

@LargeTest
public class XmlBlockBenchmark {
    private static final String TAG = "XmlBlockBenchmark";
    private static final String NAMESPACE_ANDROID = "http://schemas.android.com/apk/res/android";

    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    private XmlBlock.Parser mParser;

    private void cleanCache() {
        if (mParser != null) {
            mParser.close();
        }

        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Resources resources = context.getResources();
        resources.getImpl().clearAllCaches();
        Log.d(TAG, "cleanCache");
    }

    private XmlBlock.Parser getNewParser() {
        cleanCache();

        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Resources resources = context.getResources();
        return (XmlBlock.Parser) resources.getXml(R.layout.linear_layout_for_xmlblock_benchmark);
    }

    @Before
    public void setUp() {
        mParser = getNewParser();
    }

    @After
    public void tearDown() {
        cleanCache();
    }

    int safeNext() throws XmlPullParserException, IOException {
        while (true) {
            int parseState = mParser.next();
            if (parseState == START_TAG) {
                return parseState;
            } else if (parseState == END_DOCUMENT) {
                mParser = getNewParser();
            }
        }
    }

    @Test
    public void throwNpeCausedByNullDocument() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        mParser.close();
        while (state.keepRunning()) {
            try {
                mParser.getClassAttribute();
            } catch (NullPointerException e) {
                continue;
            }
            Assert.fail("It shouldn't be here!");
        }
    }

    @Test
    public void getNext() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            int parseState = mParser.next();
            state.pauseTiming();
            if (parseState == END_DOCUMENT) {
                mParser = getNewParser();
            }
            state.resumeTiming();
        }
    }

    private <T> void benchmarkTagFunction(BenchmarkState state, String name,
            Supplier<T> measureTarget)
            throws XmlPullParserException, IOException {
        while (state.keepRunning()) {
            state.pauseTiming();
            int parseState = safeNext();

            if (parseState != END_DOCUMENT) {
                final String tagName = mParser.getName();
                state.resumeTiming();
                final T value = measureTarget.get();
                state.pauseTiming();
                Log.d(TAG,
                        TextUtils.formatSimple("%s() in tag %s is %s", name, tagName, value));
            }
            state.resumeTiming();
        }
    }

    @Test
    public void getNamespace() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkTagFunction(state, "getNamespace", () -> mParser.getNamespace());
    }

    @Test
    public void getName() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkTagFunction(state, "getName", () -> mParser.getName());
    }

    @Test
    public void getText() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkTagFunction(state, "getText", () -> mParser.getText());
    }

    @Test
    public void getLineNumber() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkTagFunction(state, "getLineNumber", () -> mParser.getLineNumber());
    }

    @Test
    public void getAttributeCount() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkTagFunction(state, "getAttributeCount", () -> mParser.getAttributeCount());
    }

    private <T> void benchmarkAttributeFunction(BenchmarkState state, String name,
            Function<Integer, T> measureTarget)
            throws XmlPullParserException, IOException {
        boolean needNext = true;
        boolean needGetCount = false;
        int attributeCount = 0;
        int i = 0;
        while (state.keepRunning()) {
            state.pauseTiming();
            if (needNext) {
                int parseState = safeNext();
                if (parseState == START_TAG) {
                    needNext = false;
                    needGetCount = true;
                }
            }

            if (needGetCount) {
                attributeCount = mParser.getAttributeCount();
                needGetCount = false;
                i = 0;
            }

            if (i < attributeCount) {
                final String tagName = mParser.getName();
                final String attributeName = mParser.getAttributeName(i);
                state.resumeTiming();
                final T value = measureTarget.apply(i);
                state.pauseTiming();
                Log.d(TAG,
                        TextUtils.formatSimple("%s(%d:%s) in tag %s is %s", name, i, attributeName,
                                tagName, value));
                i++;
            }

            if (i >= attributeCount) {
                needNext = true;
            }
            state.resumeTiming();
        }
    }

    @Test
    public void getAttributeNamespace() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkAttributeFunction(state, "getAttributeNamespace",
                attributeIndex -> mParser.getAttributeNamespace(attributeIndex));
    }

    @Test
    public void getAttributeName() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkAttributeFunction(state, "getAttributeName",
                attributeIndex -> mParser.getAttributeName(attributeIndex));
    }

    @Test
    public void getAttributeNameResource() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkAttributeFunction(state, "getAttributeNameResource",
                attributeIndex -> mParser.getAttributeNameResource(attributeIndex));
    }

    /**
     * benchmark {@link android.content.res.XmlBlock#nativeGetAttributeDataType(long, int)} and
     * {@link android.content.res.XmlBlock#nativeGetAttributeData(long, int)}
     */
    @Test
    public void getAttributeDataXXX() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkAttributeFunction(state, "getAttributeDataXXX",
                attributeIndex -> mParser.getAttributeValue(attributeIndex));
    }

    @Test
    public void getSourceResId() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkTagFunction(state, "getSourceResId", () -> mParser.getSourceResId());
    }

    @Test
    public void getIdAttribute() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkTagFunction(state, "getIdAttribute", () -> mParser.getIdAttribute());
    }

    @Test
    public void getClassAttribute() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkTagFunction(state, "getClassAttribute", () -> mParser.getClassAttribute());
    }

    @Test
    public void getStyleAttribute() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkTagFunction(state, "getStyleAttribute", () -> mParser.getStyleAttribute());
    }

    @Test
    public void getAttributeIndex() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        benchmarkTagFunction(state, "getAttributeValue",
                () -> mParser.getAttributeValue(NAMESPACE_ANDROID, "layout_width"));
    }

    @Test
    public void parseOneXmlDocument() throws XmlPullParserException, IOException {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            mParser = getNewParser();
            state.resumeTiming();

            int parseState;
            while ((parseState = mParser.next()) != END_DOCUMENT) {
                if (parseState == START_DOCUMENT) {
                    state.pauseTiming();
                    Log.d(TAG, "parseOneXmlDocument: start document");
                    state.resumeTiming();
                } else if (parseState == START_TAG) {
                    final String tagName = mParser.getName();
                    state.pauseTiming();
                    Log.d(TAG, TextUtils.formatSimple("parseOneXmlDocument: tag %s {[", tagName));
                    state.resumeTiming();
                    for (int i = 0, count = mParser.getAttributeCount(); i < count; i++) {
                        final String attributeName = mParser.getAttributeName(i);
                        final String attributeValue = mParser.getAttributeValue(i);

                        state.pauseTiming();
                        Log.d(TAG, TextUtils.formatSimple(
                                "parseOneXmlDocument: attribute %d {%s = %s},", i, attributeName,
                                attributeValue));
                        state.resumeTiming();
                    }
                    state.pauseTiming();
                    Log.d(TAG, "parseOneXmlDocument: ]");
                    state.resumeTiming();
                } else if (parseState == END_TAG) {
                    state.pauseTiming();
                    Log.d(TAG, "parseOneXmlDocument: }");
                    state.resumeTiming();
                } else {
                    final String text = mParser.getText();
                    state.pauseTiming();
                    Log.d(TAG, TextUtils.formatSimple(
                            "parseOneXmlDocument: parseState = %d, text = %s", parseState, text));
                    state.resumeTiming();
                }
            }
        }
    }
}

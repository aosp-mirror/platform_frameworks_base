/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.view.textclassifier;

import android.content.Context;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.service.textclassifier.TextClassifierService;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Collections;

@LargeTest
public class TextClassifierPerfTest {
    private static final String TEXT = " Oh hi Mark, the number is (323) 654-6192.\n"
            + "Anyway, I'll meet you at 1600 Pennsylvania Avenue NW.\n"
            + "My flight is LX 38 and I'll arrive at 8:00pm.\n"
            + "Also, check out www.google.com.\n";

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private TextClassifier mTextClassifier;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getTargetContext();
        mTextClassifier = TextClassifierService.getDefaultTextClassifierImplementation(context);
    }

    @Test
    public void testClassifyText() {
        TextClassification.Request request =
                new TextClassification.Request.Builder(TEXT, 0, TEXT.length()).build();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mTextClassifier.classifyText(request);
        }
    }

    @Test
    public void testSuggestSelection() {
        // Trying to select the phone number.
        TextSelection.Request request =
                new TextSelection.Request.Builder(
                        TEXT,
                        /* startIndex= */ 28,
                        /* endIndex= */29)
                        .build();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mTextClassifier.suggestSelection(request);
        }
    }

    @Test
    public void testGenerateLinks() {
        TextLinks.Request request =
                new TextLinks.Request.Builder(TEXT).build();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mTextClassifier.generateLinks(request);
        }
    }

    @Test
    public void testSuggestConversationActions() {
        ConversationActions.Message message =
                new ConversationActions.Message.Builder(
                        ConversationActions.Message.PERSON_USER_OTHERS)
                        .setText(TEXT)
                        .build();
        ConversationActions.Request request = new ConversationActions.Request.Builder(
                Collections.singletonList(message))
                .build();

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mTextClassifier.suggestConversationActions(request);
        }
    }

    @Test
    public void testDetectLanguage() {
        TextLanguage.Request request = new TextLanguage.Request.Builder(TEXT).build();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mTextClassifier.detectLanguage(request);
        }
    }
}

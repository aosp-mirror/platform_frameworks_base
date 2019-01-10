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
package android.textclassifier;

import android.content.Context;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLanguage;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Random;

@RunWith(Parameterized.class)
@LargeTest
public class TextClassifierPerfTest {
    /** Request contains meaning text, rather than garbled text. */
    private static final int ACTUAL_REQUEST = 0;
    private static final String RANDOM_CHAR_SET = "abcdefghijklmnopqrstuvwxyz0123456789";

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameterized.Parameters(name = "size={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{ACTUAL_REQUEST}, {10}, {100}, {1000}});
    }

    private TextClassifier mTextClassifier;
    private final int mSize;

    public TextClassifierPerfTest(int size) {
        mSize = size;
    }

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getTargetContext();
        TextClassificationManager textClassificationManager =
                context.getSystemService(TextClassificationManager.class);
        mTextClassifier = textClassificationManager.getTextClassifier();
    }

    @Test
    public void testSuggestConversationActions() {
        String text = mSize == ACTUAL_REQUEST ? "Where are you?" : generateRandomString(mSize);
        ConversationActions.Request request = createConversationActionsRequest(text);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mTextClassifier.suggestConversationActions(request);
        }
    }

    @Test
    public void testDetectLanguage() {
        String text = mSize == ACTUAL_REQUEST
                ? "これは日本語のテキストです" : generateRandomString(mSize);
        TextLanguage.Request request = createTextLanguageRequest(text);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mTextClassifier.detectLanguage(request);
        }
    }

    private static ConversationActions.Request createConversationActionsRequest(CharSequence text) {
        ConversationActions.Message message =
                new ConversationActions.Message.Builder(
                        ConversationActions.Message.PERSON_USER_REMOTE)
                        .setText(text)
                        .build();
        return new ConversationActions.Request.Builder(Collections.singletonList(message))
                .build();
    }

    private static TextLanguage.Request createTextLanguageRequest(CharSequence text) {
        return new TextLanguage.Request.Builder(text).build();
    }

    private static String generateRandomString(int length) {
        Random random = new Random();
        StringBuilder stringBuilder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = random.nextInt(RANDOM_CHAR_SET.length());
            stringBuilder.append(RANDOM_CHAR_SET.charAt(index));
        }
        return stringBuilder.toString();
    }
}

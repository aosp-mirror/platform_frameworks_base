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

package android.view.textclassifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

import android.os.LocaleList;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassificationResult;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLanguage;
import android.view.textclassifier.TextSelection;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Locale;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassificationManagerTest {

    private static final LocaleList LOCALES = LocaleList.forLanguageTags("en");

    private TextClassificationManager mTcm;
    private TextClassifier mClassifier;

    @Before
    public void setup() {
        mTcm = InstrumentationRegistry.getTargetContext()
                .getSystemService(TextClassificationManager.class);
        mTcm.setTextClassifier(null);
        mClassifier = mTcm.getTextClassifier();
    }

    @Test
    public void testSmartSelection() {
        if (isTextClassifierDisabled()) return;

        String text = "Contact me at droid@android.com";
        String selected = "droid";
        String suggested = "droid@android.com";
        int startIndex = text.indexOf(selected);
        int endIndex = startIndex + selected.length();
        int smartStartIndex = text.indexOf(suggested);
        int smartEndIndex = smartStartIndex + suggested.length();

        assertThat(mClassifier.suggestSelection(text, startIndex, endIndex, LOCALES),
                isTextSelection(smartStartIndex, smartEndIndex, TextClassifier.TYPE_EMAIL));
    }

    @Test
    public void testSmartSelection_url() {
        if (isTextClassifierDisabled()) return;

        String text = "Visit http://www.android.com for more information";
        String selected = "http";
        String suggested = "http://www.android.com";
        int startIndex = text.indexOf(selected);
        int endIndex = startIndex + selected.length();
        int smartStartIndex = text.indexOf(suggested);
        int smartEndIndex = smartStartIndex + suggested.length();

        assertThat(mClassifier.suggestSelection(text, startIndex, endIndex, LOCALES),
                isTextSelection(smartStartIndex, smartEndIndex, TextClassifier.TYPE_URL));
    }

    @Test
    public void testTextClassificationResult() {
        if (isTextClassifierDisabled()) return;

        String text = "Contact me at droid@android.com";
        String classifiedText = "droid@android.com";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        assertThat(mClassifier.getTextClassificationResult(text, startIndex, endIndex, LOCALES),
                isTextClassificationResult(classifiedText, TextClassifier.TYPE_EMAIL));
    }

    @Test
    public void testTextClassificationResult_url() {
        if (isTextClassifierDisabled()) return;

        String text = "Visit http://www.android.com for more information";
        String classifiedText = "http://www.android.com";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        assertThat(mClassifier.getTextClassificationResult(text, startIndex, endIndex, LOCALES),
                isTextClassificationResult(classifiedText, TextClassifier.TYPE_URL));
    }

    @Test
    public void testLanguageDetection() {
        if (isTextClassifierDisabled()) return;

        String text = "This is a piece of English text";
        assertThat(mTcm.detectLanguages(text), isDetectedLanguage("en"));

        text = "Das ist ein deutscher Text";
        assertThat(mTcm.detectLanguages(text), isDetectedLanguage("de"));

        text = "これは日本語のテキストです";
        assertThat(mTcm.detectLanguages(text), isDetectedLanguage("ja"));
    }

    @Test
    public void testSetTextClassifier() {
        TextClassifier classifier = mock(TextClassifier.class);
        mTcm.setTextClassifier(classifier);
        assertEquals(classifier, mTcm.getTextClassifier());
    }

    private boolean isTextClassifierDisabled() {
        return mClassifier == TextClassifier.NO_OP;
    }

    private static Matcher<TextSelection> isTextSelection(
            final int startIndex, final int endIndex, final String type) {
        return new BaseMatcher<TextSelection>() {
            @Override
            public boolean matches(Object o) {
                if (o instanceof TextSelection) {
                    TextSelection selection = (TextSelection) o;
                    return startIndex == selection.getSelectionStartIndex()
                            && endIndex == selection.getSelectionEndIndex()
                            && selection.getEntityCount() > 0
                            && type.equals(selection.getEntity(0));
                }
                return false;
            }

            @Override
            public void describeTo(Description description) {
                description.appendValue(
                        String.format("%d, %d, %s", startIndex, endIndex, type));
            }
        };
    }

    private static Matcher<TextClassificationResult> isTextClassificationResult(
            final String text, final String type) {
        return new BaseMatcher<TextClassificationResult>() {
            @Override
            public boolean matches(Object o) {
                if (o instanceof TextClassificationResult) {
                    TextClassificationResult result = (TextClassificationResult) o;
                    return text.equals(result.getText())
                            && result.getEntityCount() > 0
                            && type.equals(result.getEntity(0));
                    // TODO: Include other properties.
                }
                return false;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("text=").appendValue(text)
                        .appendText(", type=").appendValue(type);
            }
        };
    }

    private static Matcher<List<TextLanguage>> isDetectedLanguage(final String language) {
        return new BaseMatcher<List<TextLanguage>>() {
            @Override
            public boolean matches(Object o) {
                if (o instanceof List) {
                    List languages = (List) o;
                    if (!languages.isEmpty()) {
                        Object o1 = languages.get(0);
                        if (o1 instanceof TextLanguage) {
                            TextLanguage lang = (TextLanguage) o1;
                            return lang.getLanguageCount() > 0
                                    && new Locale(language).getLanguage()
                                            .equals(lang.getLanguage(0).getLanguage());
                        }
                    }
                }
                return false;
            }

            @Override
            public void describeTo(Description description) {
                description.appendValue(String.format("%s", language));
            }
        };
    }
}

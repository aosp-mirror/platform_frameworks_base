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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import android.os.LocaleList;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collection;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassificationManagerTest {

    private static final LocaleList LOCALES = LocaleList.forLanguageTags("en");
    private static final String NO_TYPE = null;

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
    public void testSmartSelection_nullLocaleList() {
        if (isTextClassifierDisabled()) return;

        String text = "Contact me at droid@android.com";
        String selected = "droid";
        String suggested = "droid@android.com";
        int startIndex = text.indexOf(selected);
        int endIndex = startIndex + selected.length();
        int smartStartIndex = text.indexOf(suggested);
        int smartEndIndex = smartStartIndex + suggested.length();
        LocaleList nullLocales = null;

        assertThat(mClassifier.suggestSelection(text, startIndex, endIndex, nullLocales),
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
    public void testSmartSelection_withEmoji() {
        if (isTextClassifierDisabled()) return;

        String text = "\uD83D\uDE02 Hello.";
        String selected = "Hello";
        int startIndex = text.indexOf(selected);
        int endIndex = startIndex + selected.length();

        assertThat(mClassifier.suggestSelection(text, startIndex, endIndex, LOCALES),
                isTextSelection(startIndex, endIndex, NO_TYPE));
    }

    @Test
    public void testClassifyText() {
        if (isTextClassifierDisabled()) return;

        String text = "Contact me at droid@android.com";
        String classifiedText = "droid@android.com";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        assertThat(mClassifier.classifyText(text, startIndex, endIndex, LOCALES),
                isTextClassification(
                        classifiedText,
                        TextClassifier.TYPE_EMAIL,
                        "mailto:" + classifiedText));
    }

    @Test
    public void testTextClassifyText_url() {
        if (isTextClassifierDisabled()) return;

        String text = "Visit www.android.com for more information";
        String classifiedText = "www.android.com";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        assertThat(mClassifier.classifyText(text, startIndex, endIndex, LOCALES),
                isTextClassification(
                        classifiedText,
                        TextClassifier.TYPE_URL,
                        "http://" + classifiedText));
    }

    @Test
    public void testTextClassifyText_url_inCaps() {
        if (isTextClassifierDisabled()) return;

        String text = "Visit HTTP://ANDROID.COM for more information";
        String classifiedText = "HTTP://ANDROID.COM";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        assertThat(mClassifier.classifyText(text, startIndex, endIndex, LOCALES),
                isTextClassification(
                        classifiedText,
                        TextClassifier.TYPE_URL,
                        "http://ANDROID.COM"));
    }

    @Test
    public void testTextClassifyText_nullLocaleList() {
        if (isTextClassifierDisabled()) return;

        String text = "Contact me at droid@android.com";
        String classifiedText = "droid@android.com";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        LocaleList nullLocales = null;
        assertThat(mClassifier.classifyText(text, startIndex, endIndex, nullLocales),
                isTextClassification(
                        classifiedText,
                        TextClassifier.TYPE_EMAIL,
                        "mailto:" + classifiedText));
    }

    @Test
    public void testGenerateLinks() {
        if (isTextClassifierDisabled()) return;

        checkGenerateLinksFindsLink(
                "The number is +12122537077. See you tonight!",
                "+12122537077",
                TextClassifier.TYPE_PHONE);

        checkGenerateLinksFindsLink(
                "The address is 1600 Amphitheater Parkway, Mountain View, CA. See you tonight!",
                "1600 Amphitheater Parkway, Mountain View, CA",
                TextClassifier.TYPE_ADDRESS);

        // TODO: Add more entity types when the model supports them.
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

    private void checkGenerateLinksFindsLink(String text, String classifiedText, String type) {
        assertTrue(text.contains(classifiedText));
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();

        Collection<TextLinks.TextLink> links = mClassifier.generateLinks(text, null).getLinks();
        for (TextLinks.TextLink link : links) {
            if (text.subSequence(link.getStart(), link.getEnd()).equals(classifiedText)) {
                assertEquals(type, link.getEntity(0));
                assertEquals(startIndex, link.getStart());
                assertEquals(endIndex, link.getEnd());
                assertTrue(link.getConfidenceScore(type) > .9);
                return;
            }
        }
        fail(); // Subsequence was not identified.
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
                            && typeMatches(selection, type);
                }
                return false;
            }

            private boolean typeMatches(TextSelection selection, String type) {
                return type == null
                        || (selection.getEntityCount() > 0
                                && type.trim().equalsIgnoreCase(selection.getEntity(0)));
            }

            @Override
            public void describeTo(Description description) {
                description.appendValue(
                        String.format("%d, %d, %s", startIndex, endIndex, type));
            }
        };
    }

    private static Matcher<TextClassification> isTextClassification(
            final String text, final String type, final String intentUri) {
        return new BaseMatcher<TextClassification>() {
            @Override
            public boolean matches(Object o) {
                if (o instanceof TextClassification) {
                    TextClassification result = (TextClassification) o;
                    final boolean typeRequirementSatisfied;
                    String scheme;
                    switch (type) {
                        case TextClassifier.TYPE_EMAIL:
                            scheme = result.getIntent().getData().getScheme();
                            typeRequirementSatisfied = "mailto".equals(scheme);
                            break;
                        case TextClassifier.TYPE_URL:
                            scheme = result.getIntent().getData().getScheme();
                            typeRequirementSatisfied = "http".equals(scheme)
                                    || "https".equals(scheme);
                            break;
                        default:
                            typeRequirementSatisfied = true;
                    }

                    return typeRequirementSatisfied
                            && text.equals(result.getText())
                            && result.getEntityCount() > 0
                            && type.equals(result.getEntity(0))
                            && intentUri.equals(result.getIntent().getDataString());
                    // TODO: Include other properties.
                }
                return false;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("text=").appendValue(text)
                        .appendText(", type=").appendValue(type)
                        .appendText(", intent.data=").appendValue(intentUri);
            }
        };
    }
}

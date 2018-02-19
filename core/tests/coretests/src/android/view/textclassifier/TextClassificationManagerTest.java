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

import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
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

import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassificationManagerTest {

    private static final LocaleList LOCALES = LocaleList.forLanguageTags("en");
    private static final String NO_TYPE = null;

    private TextClassificationManager mTcm;
    private TextClassifier mClassifier;
    private TextSelection.Options mSelectionOptions;
    private TextClassification.Options mClassificationOptions;
    private TextLinks.Options mLinksOptions;

    @Before
    public void setup() {
        mTcm = InstrumentationRegistry.getTargetContext()
                .getSystemService(TextClassificationManager.class);
        mTcm.setTextClassifier(null);
        mClassifier = mTcm.getTextClassifier();
        mSelectionOptions = new TextSelection.Options().setDefaultLocales(LOCALES);
        mClassificationOptions = new TextClassification.Options().setDefaultLocales(LOCALES);
        mLinksOptions = new TextLinks.Options().setDefaultLocales(LOCALES);
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

        TextSelection selection = mClassifier.suggestSelection(
                text, startIndex, endIndex, mSelectionOptions);
        assertThat(selection,
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

        TextSelection selection = mClassifier.suggestSelection(
                text, startIndex, endIndex, mSelectionOptions);
        assertThat(selection,
                isTextSelection(smartStartIndex, smartEndIndex, TextClassifier.TYPE_URL));
    }

    @Test
    public void testSmartSelection_withEmoji() {
        if (isTextClassifierDisabled()) return;

        String text = "\uD83D\uDE02 Hello.";
        String selected = "Hello";
        int startIndex = text.indexOf(selected);
        int endIndex = startIndex + selected.length();

        TextSelection selection = mClassifier.suggestSelection(
                text, startIndex, endIndex, mSelectionOptions);
        assertThat(selection,
                isTextSelection(startIndex, endIndex, NO_TYPE));
    }

    @Test
    public void testClassifyText() {
        if (isTextClassifierDisabled()) return;

        String text = "Contact me at droid@android.com";
        String classifiedText = "droid@android.com";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();

        TextClassification classification = mClassifier.classifyText(
                text, startIndex, endIndex, mClassificationOptions);
        assertThat(classification,
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

        TextClassification classification = mClassifier.classifyText(
                text, startIndex, endIndex, mClassificationOptions);
        assertThat(classification,
                isTextClassification(
                        classifiedText,
                        TextClassifier.TYPE_URL,
                        "http://" + classifiedText));
    }

    @Test
    public void testTextClassifyText_address() {
        if (isTextClassifierDisabled()) return;

        String text = "Brandschenkestrasse 110, Zürich, Switzerland";
        TextClassification classification = mClassifier.classifyText(
                text, 0, text.length(), mClassificationOptions);
        assertThat(classification,
                isTextClassification(
                        text,
                        TextClassifier.TYPE_ADDRESS,
                        "geo:0,0?q=Brandschenkestrasse+110%2C+Z%C3%BCrich%2C+Switzerland"));
    }

    @Test
    public void testTextClassifyText_url_inCaps() {
        if (isTextClassifierDisabled()) return;

        String text = "Visit HTTP://ANDROID.COM for more information";
        String classifiedText = "HTTP://ANDROID.COM";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();

        TextClassification classification = mClassifier.classifyText(
                text, startIndex, endIndex, mClassificationOptions);
        assertThat(classification,
                isTextClassification(
                        classifiedText,
                        TextClassifier.TYPE_URL,
                        "http://ANDROID.COM"));
    }

    @Test
    public void testGenerateLinks_phone() {
        if (isTextClassifierDisabled()) return;
        String text = "The number is +12122537077. See you tonight!";
        assertThat(mClassifier.generateLinks(text, null),
                isTextLinksContaining(text, "+12122537077", TextClassifier.TYPE_PHONE));
    }

    @Test
    public void testGenerateLinks_exclude() {
        if (isTextClassifierDisabled()) return;
        String text = "The number is +12122537077. See you tonight!";
        assertThat(mClassifier.generateLinks(text, mLinksOptions.setEntityConfig(
                new TextClassifier.EntityConfig(TextClassifier.ENTITY_PRESET_ALL)
                        .excludeEntities(TextClassifier.TYPE_PHONE))),
                not(isTextLinksContaining(text, "+12122537077", TextClassifier.TYPE_PHONE)));
    }

    @Test
    public void testGenerateLinks_none_config() {
        if (isTextClassifierDisabled()) return;

        String text = "The number is +12122537077. See you tonight!";
        assertThat(mClassifier.generateLinks(text, mLinksOptions.setEntityConfig(
                new TextClassifier.EntityConfig(TextClassifier.ENTITY_PRESET_NONE))),
                not(isTextLinksContaining(text, "+12122537077", TextClassifier.TYPE_PHONE)));
    }

    @Test
    public void testGenerateLinks_address() {
        if (isTextClassifierDisabled()) return;
        String text = "The address is 1600 Amphitheater Parkway, Mountain View, CA. See you!";
        assertThat(mClassifier.generateLinks(text, null),
                isTextLinksContaining(text, "1600 Amphitheater Parkway, Mountain View, CA",
                        TextClassifier.TYPE_ADDRESS));
    }

    @Test
    public void testGenerateLinks_include() {
        if (isTextClassifierDisabled()) return;
        String text = "The address is 1600 Amphitheater Parkway, Mountain View, CA. See you!";
        assertThat(mClassifier.generateLinks(text, mLinksOptions.setEntityConfig(
                new TextClassifier.EntityConfig(TextClassifier.ENTITY_PRESET_NONE)
                        .includeEntities(TextClassifier.TYPE_ADDRESS))),
                isTextLinksContaining(text, "1600 Amphitheater Parkway, Mountain View, CA",
                        TextClassifier.TYPE_ADDRESS));
    }

    @Test
    public void testGenerateLinks_maxLength() {
        if (isTextClassifierDisabled()) return;
        char[] manySpaces = new char[mClassifier.getMaxGenerateLinksTextLength()];
        Arrays.fill(manySpaces, ' ');
        TextLinks links = mClassifier.generateLinks(new String(manySpaces), null);
        assertTrue(links.getLinks().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGenerateLinks_tooLong() {
        if (isTextClassifierDisabled()) {
            throw new IllegalArgumentException("pass if disabled");
        }
        char[] manySpaces = new char[mClassifier.getMaxGenerateLinksTextLength() + 1];
        Arrays.fill(manySpaces, ' ');
        mClassifier.generateLinks(new String(manySpaces), null);
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

    private static Matcher<TextLinks> isTextLinksContaining(
            final String text, final String substring, final String type) {
        return new BaseMatcher<TextLinks>() {

            @Override
            public void describeTo(Description description) {
                description.appendText("text=").appendValue(text)
                        .appendText(", substring=").appendValue(substring)
                        .appendText(", type=").appendValue(type);
            }

            @Override
            public boolean matches(Object o) {
                if (o instanceof TextLinks) {
                    for (TextLinks.TextLink link : ((TextLinks) o).getLinks()) {
                        if (text.subSequence(link.getStart(), link.getEnd()).equals(substring)) {
                            return type.equals(link.getEntity(0));
                        }
                    }
                }
                return false;
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
                        case TextClassifier.TYPE_ADDRESS:
                            scheme = result.getIntent().getData().getScheme();
                            typeRequirementSatisfied = "geo".equals(scheme);
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

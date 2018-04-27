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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.LocaleList;
import android.service.textclassifier.TextClassifierService;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassificationManagerTest {

    private static final LocaleList LOCALES = LocaleList.forLanguageTags("en-US");
    private static final String NO_TYPE = null;

    private Context mContext;
    private TextClassificationManager mTcm;
    private TextClassifier mClassifier;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getTargetContext();
        mTcm = mContext.getSystemService(TextClassificationManager.class);
        // Test with the local textClassifier only. (We only bundle "en" model by default).
        // It's hard to reliably test the results of the device's TextClassifierServiceImpl here.
        mClassifier = mTcm.getTextClassifier(TextClassifier.LOCAL);
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
        TextSelection.Request request = new TextSelection.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextSelection selection = mClassifier.suggestSelection(request);
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
        TextSelection.Request request = new TextSelection.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextSelection selection = mClassifier.suggestSelection(request);
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
        TextSelection.Request request = new TextSelection.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextSelection selection = mClassifier.suggestSelection(request);
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
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification classification = mClassifier.classifyText(request);
        assertThat(classification, isTextClassification(classifiedText, TextClassifier.TYPE_EMAIL));
    }

    @Test
    public void testTextClassifyText_url() {
        if (isTextClassifierDisabled()) return;

        String text = "Visit www.android.com for more information";
        String classifiedText = "www.android.com";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification classification = mClassifier.classifyText(request);
        assertThat(classification, isTextClassification(classifiedText, TextClassifier.TYPE_URL));
    }

    @Test
    public void testTextClassifyText_address() {
        if (isTextClassifierDisabled()) return;

        String text = "Brandschenkestrasse 110, Zürich, Switzerland";
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, 0, text.length())
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification classification = mClassifier.classifyText(request);
        assertThat(classification, isTextClassification(text, TextClassifier.TYPE_ADDRESS));
    }

    @Test
    public void testTextClassifyText_url_inCaps() {
        if (isTextClassifierDisabled()) return;

        String text = "Visit HTTP://ANDROID.COM for more information";
        String classifiedText = "HTTP://ANDROID.COM";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification classification = mClassifier.classifyText(request);
        assertThat(classification, isTextClassification(classifiedText, TextClassifier.TYPE_URL));
    }

    @Test
    public void testTextClassifyText_date() {
        if (isTextClassifierDisabled()) return;

        String text = "Let's meet on January 9, 2018.";
        String classifiedText = "January 9, 2018";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification classification = mClassifier.classifyText(request);
        assertThat(classification, isTextClassification(classifiedText, TextClassifier.TYPE_DATE));
    }

    @Test
    public void testTextClassifyText_datetime() {
        if (isTextClassifierDisabled()) return;

        String text = "Let's meet 2018/01/01 10:30:20.";
        String classifiedText = "2018/01/01 10:30:20";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification classification = mClassifier.classifyText(request);
        assertThat(classification,
                isTextClassification(classifiedText, TextClassifier.TYPE_DATE_TIME));
    }

    @Test
    public void testGenerateLinks_phone() {
        if (isTextClassifierDisabled()) return;
        String text = "The number is +12122537077. See you tonight!";
        TextLinks.Request request = new TextLinks.Request.Builder(text).build();
        assertThat(mClassifier.generateLinks(request),
                isTextLinksContaining(text, "+12122537077", TextClassifier.TYPE_PHONE));
    }

    @Test
    public void testGenerateLinks_exclude() {
        if (isTextClassifierDisabled()) return;
        String text = "You want apple@banana.com. See you tonight!";
        List<String> hints = Collections.EMPTY_LIST;
        List<String> included = Collections.EMPTY_LIST;
        List<String> excluded = Arrays.asList(TextClassifier.TYPE_EMAIL);
        TextLinks.Request request = new TextLinks.Request.Builder(text)
                .setEntityConfig(TextClassifier.EntityConfig.create(hints, included, excluded))
                .setDefaultLocales(LOCALES)
                .build();
        assertThat(mClassifier.generateLinks(request),
                not(isTextLinksContaining(text, "apple@banana.com", TextClassifier.TYPE_EMAIL)));
    }

    @Test
    public void testGenerateLinks_explicit_address() {
        if (isTextClassifierDisabled()) return;
        String text = "The address is 1600 Amphitheater Parkway, Mountain View, CA. See you!";
        List<String> explicit = Arrays.asList(TextClassifier.TYPE_ADDRESS);
        TextLinks.Request request = new TextLinks.Request.Builder(text)
                .setEntityConfig(TextClassifier.EntityConfig.createWithExplicitEntityList(explicit))
                .setDefaultLocales(LOCALES)
                .build();
        assertThat(mClassifier.generateLinks(request),
                isTextLinksContaining(text, "1600 Amphitheater Parkway, Mountain View, CA",
                        TextClassifier.TYPE_ADDRESS));
    }

    @Test
    public void testGenerateLinks_exclude_override() {
        if (isTextClassifierDisabled()) return;
        String text = "You want apple@banana.com. See you tonight!";
        List<String> hints = Collections.EMPTY_LIST;
        List<String> included = Arrays.asList(TextClassifier.TYPE_EMAIL);
        List<String> excluded = Arrays.asList(TextClassifier.TYPE_EMAIL);
        TextLinks.Request request = new TextLinks.Request.Builder(text)
                .setEntityConfig(TextClassifier.EntityConfig.create(hints, included, excluded))
                .setDefaultLocales(LOCALES)
                .build();
        assertThat(mClassifier.generateLinks(request),
                not(isTextLinksContaining(text, "apple@banana.com", TextClassifier.TYPE_EMAIL)));
    }

    @Test
    public void testGenerateLinks_maxLength() {
        if (isTextClassifierDisabled()) return;
        char[] manySpaces = new char[mClassifier.getMaxGenerateLinksTextLength()];
        Arrays.fill(manySpaces, ' ');
        TextLinks.Request request = new TextLinks.Request.Builder(new String(manySpaces)).build();
        TextLinks links = mClassifier.generateLinks(request);
        assertTrue(links.getLinks().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGenerateLinks_tooLong() {
        if (isTextClassifierDisabled()) {
            throw new IllegalArgumentException("pass if disabled");
        }
        char[] manySpaces = new char[mClassifier.getMaxGenerateLinksTextLength() + 1];
        Arrays.fill(manySpaces, ' ');
        TextLinks.Request request = new TextLinks.Request.Builder(new String(manySpaces)).build();
        mClassifier.generateLinks(request);
    }

    @Test
    public void testSetTextClassifier() {
        TextClassifier classifier = mock(TextClassifier.class);
        mTcm.setTextClassifier(classifier);
        assertEquals(classifier, mTcm.getTextClassifier());
    }

    @Test
    public void testGetLocalTextClassifier() {
        assertTrue(mTcm.getTextClassifier(TextClassifier.LOCAL) instanceof TextClassifierImpl);
    }
    @Test
    public void testGetSystemTextClassifier() {
        assertTrue(
                TextClassifierService.getServiceComponentName(mContext) == null
                || mTcm.getTextClassifier(TextClassifier.SYSTEM) instanceof SystemTextClassifier);
    }

    @Test
    public void testCannotResolveIntent() {
        final PackageManager fakePackageMgr = mock(PackageManager.class);

        ResolveInfo validInfo = mContext.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_DIAL).setData(Uri.parse("tel:+12122537077")), 0);
        // Make packageManager fail when it gets the following intent:
        ArgumentMatcher<Intent> toFailIntent =
                intent -> intent.getAction().equals(Intent.ACTION_INSERT_OR_EDIT);

        when(fakePackageMgr.resolveActivity(any(Intent.class), anyInt())).thenReturn(validInfo);
        when(fakePackageMgr.resolveActivity(argThat(toFailIntent), anyInt())).thenReturn(null);

        ContextWrapper fakeContext = new ContextWrapper(mContext) {
            @Override
            public PackageManager getPackageManager() {
                return fakePackageMgr;
            }
        };

        TextClassifier fallback = TextClassifier.NO_OP;
        TextClassifier classifier = new TextClassifierImpl(
                fakeContext, TextClassificationConstants.loadFromString(null), fallback);

        String text = "Contact me at +12122537077";
        String classifiedText = "+12122537077";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification result = classifier.classifyText(request);
        TextClassification fallbackResult = fallback.classifyText(request);

        // classifier should not totally fail in which case it returns a fallback result.
        // It should skip the failing intent and return a result for non-failing intents.
        assertFalse(result.getActions().isEmpty());
        assertNotSame(result, fallbackResult);
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
            final String text, final String type) {
        return new BaseMatcher<TextClassification>() {
            @Override
            public boolean matches(Object o) {
                if (o instanceof TextClassification) {
                    TextClassification result = (TextClassification) o;
                    return text.equals(result.getText())
                            && result.getEntityCount() > 0
                            && type.equals(result.getEntity(0));
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
}

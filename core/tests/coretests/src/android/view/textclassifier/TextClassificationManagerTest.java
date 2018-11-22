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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassificationManagerTest {

    private static final LocaleList LOCALES = LocaleList.forLanguageTags("en-US");

    private Context mContext;
    private TextClassificationManager mTcm;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getTargetContext();
        mTcm = mContext.getSystemService(TextClassificationManager.class);
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
}

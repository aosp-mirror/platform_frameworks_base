/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.text.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * LinkifyTest tests {@link Linkify}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LinkifyTest {

    private static final LocaleList LOCALE_LIST_US = new LocaleList(Locale.US);
    private LocaleList mOriginalLocales;
    private Context mContext;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getContext();
        mOriginalLocales = LocaleList.getDefault();
        LocaleList.setDefault(LOCALE_LIST_US);
    }

    @After
    public void teardown() {
        LocaleList.setDefault(mOriginalLocales);
    }

    @Test
    public void testNothing() {
        TextView tv;

        tv = new TextView(createUsEnglishContext());
        tv.setText("Hey, foo@google.com, call 415-555-1212.");

        assertFalse(tv.getMovementMethod() instanceof LinkMovementMethod);
        assertTrue(tv.getUrls().length == 0);
    }

    @Test
    public void testNormal() {
        TextView tv;

        tv = new TextView(createUsEnglishContext());
        tv.setAutoLinkMask(Linkify.ALL);
        tv.setText("Hey, foo@google.com, call 415-555-1212.");

        assertTrue(tv.getMovementMethod() instanceof LinkMovementMethod);
        assertTrue(tv.getUrls().length == 2);
    }

    @Test
    public void testUnclickable() {
        TextView tv;

        tv = new TextView(createUsEnglishContext());
        tv.setAutoLinkMask(Linkify.ALL);
        tv.setLinksClickable(false);
        tv.setText("Hey, foo@google.com, call 415-555-1212.");

        assertFalse(tv.getMovementMethod() instanceof LinkMovementMethod);
        assertTrue(tv.getUrls().length == 2);
    }

    private Context createUsEnglishContext() {
        final Configuration overrideConfig = new Configuration();
        overrideConfig.setLocales(LOCALE_LIST_US);
        return mContext.createConfigurationContext(overrideConfig);
    }
}

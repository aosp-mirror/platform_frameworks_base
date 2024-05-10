/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = LocaleList.class)
public class LocaleListTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    @SmallTest
    public void testConstructor() throws Exception {
        LocaleList ll;
        ll = new LocaleList(Locale.forLanguageTag("fr"), null);
        assertEquals("fr", ll.toLanguageTags());

        ll = new LocaleList(Locale.forLanguageTag("fr"), LocaleList.getEmptyLocaleList());
        assertEquals("fr", ll.toLanguageTags());

        ll = new LocaleList(Locale.forLanguageTag("fr"), LocaleList.forLanguageTags("fr"));
        assertEquals("fr", ll.toLanguageTags());

        ll = new LocaleList(Locale.forLanguageTag("fr"), LocaleList.forLanguageTags("de"));
        assertEquals("fr,de", ll.toLanguageTags());

        ll = new LocaleList(Locale.forLanguageTag("fr"), LocaleList.forLanguageTags("de,ja"));
        assertEquals("fr,de,ja", ll.toLanguageTags());

        ll = new LocaleList(Locale.forLanguageTag("fr"), LocaleList.forLanguageTags("de,fr,ja"));
        assertEquals("fr,de,ja", ll.toLanguageTags());

        ll = new LocaleList(Locale.forLanguageTag("fr"), LocaleList.forLanguageTags("de,fr"));
        assertEquals("fr,de", ll.toLanguageTags());

        ll = new LocaleList(Locale.forLanguageTag("fr"), LocaleList.forLanguageTags("fr,de"));
        assertEquals("fr,de", ll.toLanguageTags());
    }

    @Test
    @SmallTest
    public void testConstructor_nullThrows() throws Exception {
        try {
            final LocaleList ll = new LocaleList(null, LocaleList.getEmptyLocaleList());
            fail("Constructing with locale and locale list should throw with a null locale.");
        } catch (Throwable e) {
            assertEquals(NullPointerException.class, e.getClass());
        }
    }

    @Test
    @SmallTest
    public void testGetDefault_localeSetDefaultCalledButNoChangeNecessary() throws Exception {
        final Locale originalLocale = Locale.getDefault();
        final LocaleList originalLocaleList = LocaleList.getDefault();
        final int originalLocaleIndex = originalLocaleList.indexOf(originalLocale);

        // This simulates a situation potentially set by the system processes
        LocaleList.setDefault(LocaleList.forLanguageTags("ae,en,ja"), 1 /* en */);

        // check our assumptions about input
        assertEquals("en", Locale.getDefault().toLanguageTag());
        final LocaleList firstResult = LocaleList.getDefault();
        assertEquals("ae,en,ja", LocaleList.getDefault().toLanguageTags());

        Locale.setDefault(Locale.forLanguageTag("ae"));
        assertSame(firstResult, LocaleList.getDefault());

        // restore the original values
        LocaleList.setDefault(originalLocaleList, originalLocaleIndex);
    }

    @Test
    @SmallTest
    public void testIntersection() {
        LocaleList localesWithN = new LocaleList(
                Locale.ENGLISH,
                Locale.FRENCH,
                Locale.GERMAN,
                Locale.ITALIAN,
                Locale.JAPANESE,
                Locale.KOREAN,
                Locale.CHINESE,
                Locale.SIMPLIFIED_CHINESE,
                Locale.TRADITIONAL_CHINESE,
                Locale.FRANCE,
                Locale.GERMANY,
                Locale.JAPAN,
                Locale.CANADA,
                Locale.CANADA_FRENCH);
        LocaleList localesWithE = new LocaleList(
                Locale.ENGLISH,
                Locale.FRENCH,
                Locale.GERMAN,
                Locale.JAPANESE,
                Locale.KOREAN,
                Locale.CHINESE,
                Locale.SIMPLIFIED_CHINESE,
                Locale.TRADITIONAL_CHINESE,
                Locale.FRANCE,
                Locale.GERMANY,
                Locale.CANADA_FRENCH);
        LocaleList localesWithNAndE = new LocaleList(
                Locale.ENGLISH,
                Locale.FRENCH,
                Locale.GERMAN,
                Locale.JAPANESE,
                Locale.KOREAN,
                Locale.CHINESE,
                Locale.SIMPLIFIED_CHINESE,
                Locale.TRADITIONAL_CHINESE,
                Locale.FRANCE,
                Locale.GERMANY,
                Locale.CANADA_FRENCH);

        assertEquals(localesWithNAndE, new LocaleList(localesWithE.getIntersection(localesWithN)));
    }
}

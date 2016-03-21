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

package android.provider;

import java.util.Locale;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

/**
 * A provider of user defined words for input methods to use for predictive text input.
 * Applications and input methods may add words into the dictionary. Words can have associated
 * frequency information and locale information.
 *
 * <p><strong>NOTE: </strong>Starting on API 23, the user dictionary is only accessible through
 * IME and spellchecker.
 */
public class UserDictionary {

    /** Authority string for this provider. */
    public static final String AUTHORITY = "user_dictionary";

    /**
     * The content:// style URL for this provider
     */
    public static final Uri CONTENT_URI =
        Uri.parse("content://" + AUTHORITY);

    private static final int FREQUENCY_MIN = 0;
    private static final int FREQUENCY_MAX = 255;

    /**
     * Contains the user defined words.
     */
    public static class Words implements BaseColumns {
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
                Uri.parse("content://" + AUTHORITY + "/words");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of words.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.google.userword";

        /**
         * The MIME type of a {@link #CONTENT_URI} sub-directory of a single word.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.google.userword";

        public static final String _ID = BaseColumns._ID;

        /**
         * The word column.
         * <p>TYPE: TEXT</p>
         */
        public static final String WORD = "word";

        /**
         * The frequency column. A value between 1 and 255. Higher values imply higher frequency.
         * <p>TYPE: INTEGER</p>
         */
        public static final String FREQUENCY = "frequency";

        /**
         * The locale that this word belongs to. Null if it pertains to all
         * locales. Locale is as defined by the string returned by Locale.toString().
         * <p>TYPE: TEXT</p>
         */
        public static final String LOCALE = "locale";

        /**
         * The uid of the application that inserted the word.
         * <p>TYPE: INTEGER</p>
         */
        public static final String APP_ID = "appid";

        /**
         * An optional shortcut for this word. When the shortcut is typed, supporting IMEs should
         * suggest the word in this row as an alternate spelling too.
         */
        public static final String SHORTCUT = "shortcut";

        /**
         * @deprecated Use {@link #addWord(Context, String, int, String, Locale)}.
         */
        @Deprecated
        public static final int LOCALE_TYPE_ALL = 0;

        /**
         * @deprecated Use {@link #addWord(Context, String, int, String, Locale)}.
         */
        @Deprecated
        public static final int LOCALE_TYPE_CURRENT = 1;

        /**
         * Sort by descending order of frequency.
         */
        public static final String DEFAULT_SORT_ORDER = FREQUENCY + " DESC";

        /** Adds a word to the dictionary, with the given frequency and the specified
         *  specified locale type.
         *
         *  @deprecated Please use
         *  {@link #addWord(Context, String, int, String, Locale)} instead.
         *
         *  @param context the current application context
         *  @param word the word to add to the dictionary. This should not be null or
         *  empty.
         *  @param localeType the locale type for this word. It should be one of
         *  {@link #LOCALE_TYPE_ALL} or {@link #LOCALE_TYPE_CURRENT}.
         */
        @Deprecated
        public static void addWord(Context context, String word,
                int frequency, int localeType) {

            if (localeType != LOCALE_TYPE_ALL && localeType != LOCALE_TYPE_CURRENT) {
                return;
            }

            final Locale locale;

            if (localeType == LOCALE_TYPE_CURRENT) {
                locale = Locale.getDefault();
            } else {
                locale = null;
            }

            addWord(context, word, frequency, null, locale);
        }

        /** Adds a word to the dictionary, with the given frequency and the specified
         *  locale type.
         *
         *  @param context the current application context
         *  @param word the word to add to the dictionary. This should not be null or
         *  empty.
         *  @param shortcut optional shortcut spelling for this word. When the shortcut
         *  is typed, the word may be suggested by applications that support it. May be null.
         *  @param locale the locale to insert the word for, or null to insert the word
         *  for all locales.
         */
        public static void addWord(Context context, String word,
                int frequency, String shortcut, Locale locale) {
            final ContentResolver resolver = context.getContentResolver();

            if (TextUtils.isEmpty(word)) {
                return;
            }

            if (frequency < FREQUENCY_MIN) frequency = FREQUENCY_MIN;
            if (frequency > FREQUENCY_MAX) frequency = FREQUENCY_MAX;

            final int COLUMN_COUNT = 5;
            ContentValues values = new ContentValues(COLUMN_COUNT);

            values.put(WORD, word);
            values.put(FREQUENCY, frequency);
            values.put(LOCALE, null == locale ? null : locale.toString());
            values.put(APP_ID, 0); // TODO: Get App UID
            values.put(SHORTCUT, shortcut);

            Uri result = resolver.insert(CONTENT_URI, values);
            // It's ok if the insert doesn't succeed because the word
            // already exists.
        }
    }
}

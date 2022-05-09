/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.text;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.PluralsRes;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources;
import android.icu.lang.UCharacter;
import android.icu.text.CaseMap;
import android.icu.text.Edits;
import android.icu.util.ULocale;
import android.os.Parcel;
import android.os.Parcelable;
import android.sysprop.DisplayProperties;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AccessibilityClickableSpan;
import android.text.style.AccessibilityReplacementSpan;
import android.text.style.AccessibilityURLSpan;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.CharacterStyle;
import android.text.style.EasyEditSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.LineHeightSpan;
import android.text.style.LocaleSpan;
import android.text.style.ParagraphStyle;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.text.style.ScaleXSpan;
import android.text.style.SpellCheckSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuggestionRangeSpan;
import android.text.style.SuggestionSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TextAppearanceSpan;
import android.text.style.TtsSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.text.style.UpdateAppearance;
import android.util.Log;
import android.util.Printer;
import android.view.View;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.reflect.Array;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class TextUtils {
    private static final String TAG = "TextUtils";

    // Zero-width character used to fill ellipsized strings when codepoint length must be preserved.
    /* package */ static final char ELLIPSIS_FILLER = '\uFEFF'; // ZERO WIDTH NO-BREAK SPACE

    // TODO: Based on CLDR data, these need to be localized for Dzongkha (dz) and perhaps
    // Hong Kong Traditional Chinese (zh-Hant-HK), but that may need to depend on the actual word
    // being ellipsized and not the locale.
    private static final String ELLIPSIS_NORMAL = "\u2026"; // HORIZONTAL ELLIPSIS (…)
    private static final String ELLIPSIS_TWO_DOTS = "\u2025"; // TWO DOT LEADER (‥)

    private static final int LINE_FEED_CODE_POINT = 10;
    private static final int NBSP_CODE_POINT = 160;

    /**
     * Flags for {@link #makeSafeForPresentation(String, int, float, int)}
     *
     * @hide
     */
    @Retention(SOURCE)
    @IntDef(flag = true, prefix = "CLEAN_STRING_FLAG_",
            value = {SAFE_STRING_FLAG_TRIM, SAFE_STRING_FLAG_SINGLE_LINE,
                    SAFE_STRING_FLAG_FIRST_LINE})
    public @interface SafeStringFlags {}

    /**
     * Remove {@link Character#isWhitespace(int) whitespace} and non-breaking spaces from the edges
     * of the label.
     *
     * @see #makeSafeForPresentation(String, int, float, int)
     */
    public static final int SAFE_STRING_FLAG_TRIM = 0x1;

    /**
     * Force entire string into single line of text (no newlines). Cannot be set at the same time as
     * {@link #SAFE_STRING_FLAG_FIRST_LINE}.
     *
     * @see #makeSafeForPresentation(String, int, float, int)
     */
    public static final int SAFE_STRING_FLAG_SINGLE_LINE = 0x2;

    /**
     * Return only first line of text (truncate at first newline). Cannot be set at the same time as
     * {@link #SAFE_STRING_FLAG_SINGLE_LINE}.
     *
     * @see #makeSafeForPresentation(String, int, float, int)
     */
    public static final int SAFE_STRING_FLAG_FIRST_LINE = 0x4;

    /** {@hide} */
    @NonNull
    public static String getEllipsisString(@NonNull TextUtils.TruncateAt method) {
        return (method == TextUtils.TruncateAt.END_SMALL) ? ELLIPSIS_TWO_DOTS : ELLIPSIS_NORMAL;
    }


    private TextUtils() { /* cannot be instantiated */ }

    public static void getChars(CharSequence s, int start, int end,
                                char[] dest, int destoff) {
        Class<? extends CharSequence> c = s.getClass();

        if (c == String.class)
            ((String) s).getChars(start, end, dest, destoff);
        else if (c == StringBuffer.class)
            ((StringBuffer) s).getChars(start, end, dest, destoff);
        else if (c == StringBuilder.class)
            ((StringBuilder) s).getChars(start, end, dest, destoff);
        else if (s instanceof GetChars)
            ((GetChars) s).getChars(start, end, dest, destoff);
        else {
            for (int i = start; i < end; i++)
                dest[destoff++] = s.charAt(i);
        }
    }

    public static int indexOf(CharSequence s, char ch) {
        return indexOf(s, ch, 0);
    }

    public static int indexOf(CharSequence s, char ch, int start) {
        Class<? extends CharSequence> c = s.getClass();

        if (c == String.class)
            return ((String) s).indexOf(ch, start);

        return indexOf(s, ch, start, s.length());
    }

    public static int indexOf(CharSequence s, char ch, int start, int end) {
        Class<? extends CharSequence> c = s.getClass();

        if (s instanceof GetChars || c == StringBuffer.class ||
            c == StringBuilder.class || c == String.class) {
            final int INDEX_INCREMENT = 500;
            char[] temp = obtain(INDEX_INCREMENT);

            while (start < end) {
                int segend = start + INDEX_INCREMENT;
                if (segend > end)
                    segend = end;

                getChars(s, start, segend, temp, 0);

                int count = segend - start;
                for (int i = 0; i < count; i++) {
                    if (temp[i] == ch) {
                        recycle(temp);
                        return i + start;
                    }
                }

                start = segend;
            }

            recycle(temp);
            return -1;
        }

        for (int i = start; i < end; i++)
            if (s.charAt(i) == ch)
                return i;

        return -1;
    }

    public static int lastIndexOf(CharSequence s, char ch) {
        return lastIndexOf(s, ch, s.length() - 1);
    }

    public static int lastIndexOf(CharSequence s, char ch, int last) {
        Class<? extends CharSequence> c = s.getClass();

        if (c == String.class)
            return ((String) s).lastIndexOf(ch, last);

        return lastIndexOf(s, ch, 0, last);
    }

    public static int lastIndexOf(CharSequence s, char ch,
                                  int start, int last) {
        if (last < 0)
            return -1;
        if (last >= s.length())
            last = s.length() - 1;

        int end = last + 1;

        Class<? extends CharSequence> c = s.getClass();

        if (s instanceof GetChars || c == StringBuffer.class ||
            c == StringBuilder.class || c == String.class) {
            final int INDEX_INCREMENT = 500;
            char[] temp = obtain(INDEX_INCREMENT);

            while (start < end) {
                int segstart = end - INDEX_INCREMENT;
                if (segstart < start)
                    segstart = start;

                getChars(s, segstart, end, temp, 0);

                int count = end - segstart;
                for (int i = count - 1; i >= 0; i--) {
                    if (temp[i] == ch) {
                        recycle(temp);
                        return i + segstart;
                    }
                }

                end = segstart;
            }

            recycle(temp);
            return -1;
        }

        for (int i = end - 1; i >= start; i--)
            if (s.charAt(i) == ch)
                return i;

        return -1;
    }

    public static int indexOf(CharSequence s, CharSequence needle) {
        return indexOf(s, needle, 0, s.length());
    }

    public static int indexOf(CharSequence s, CharSequence needle, int start) {
        return indexOf(s, needle, start, s.length());
    }

    public static int indexOf(CharSequence s, CharSequence needle,
                              int start, int end) {
        int nlen = needle.length();
        if (nlen == 0)
            return start;

        char c = needle.charAt(0);

        for (;;) {
            start = indexOf(s, c, start);
            if (start > end - nlen) {
                break;
            }

            if (start < 0) {
                return -1;
            }

            if (regionMatches(s, start, needle, 0, nlen)) {
                return start;
            }

            start++;
        }
        return -1;
    }

    public static boolean regionMatches(CharSequence one, int toffset,
                                        CharSequence two, int ooffset,
                                        int len) {
        int tempLen = 2 * len;
        if (tempLen < len) {
            // Integer overflow; len is unreasonably large
            throw new IndexOutOfBoundsException();
        }
        char[] temp = obtain(tempLen);

        getChars(one, toffset, toffset + len, temp, 0);
        getChars(two, ooffset, ooffset + len, temp, len);

        boolean match = true;
        for (int i = 0; i < len; i++) {
            if (temp[i] != temp[i + len]) {
                match = false;
                break;
            }
        }

        recycle(temp);
        return match;
    }

    /**
     * Create a new String object containing the given range of characters
     * from the source string.  This is different than simply calling
     * {@link CharSequence#subSequence(int, int) CharSequence.subSequence}
     * in that it does not preserve any style runs in the source sequence,
     * allowing a more efficient implementation.
     */
    public static String substring(CharSequence source, int start, int end) {
        if (source instanceof String)
            return ((String) source).substring(start, end);
        if (source instanceof StringBuilder)
            return ((StringBuilder) source).substring(start, end);
        if (source instanceof StringBuffer)
            return ((StringBuffer) source).substring(start, end);

        char[] temp = obtain(end - start);
        getChars(source, start, end, temp, 0);
        String ret = new String(temp, 0, end - start);
        recycle(temp);

        return ret;
    }


    /**
     * Returns the longest prefix of a string for which the UTF-8 encoding fits into the given
     * number of bytes, with the additional guarantee that the string is not truncated in the middle
     * of a valid surrogate pair.
     *
     * <p>Unpaired surrogates are counted as taking 3 bytes of storage. However, a subsequent
     * attempt to actually encode a string containing unpaired surrogates is likely to be rejected
     * by the UTF-8 implementation.
     *
     * (copied from google/thirdparty)
     *
     * @param str a string
     * @param maxbytes the maximum number of UTF-8 encoded bytes
     * @return the beginning of the string, so that it uses at most maxbytes bytes in UTF-8
     * @throws IndexOutOfBoundsException if maxbytes is negative
     *
     * @hide
     */
    public static String truncateStringForUtf8Storage(String str, int maxbytes) {
        if (maxbytes < 0) {
            throw new IndexOutOfBoundsException();
        }

        int bytes = 0;
        for (int i = 0, len = str.length(); i < len; i++) {
            char c = str.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (c < Character.MIN_SURROGATE
                    || c > Character.MAX_SURROGATE
                    || str.codePointAt(i) < Character.MIN_SUPPLEMENTARY_CODE_POINT) {
                bytes += 3;
            } else {
                bytes += 4;
                i += (bytes > maxbytes) ? 0 : 1;
            }
            if (bytes > maxbytes) {
                return str.substring(0, i);
            }
        }
        return str;
    }


    /**
     * Returns a string containing the tokens joined by delimiters.
     *
     * @param delimiter a CharSequence that will be inserted between the tokens. If null, the string
     *     "null" will be used as the delimiter.
     * @param tokens an array objects to be joined. Strings will be formed from the objects by
     *     calling object.toString(). If tokens is null, a NullPointerException will be thrown. If
     *     tokens is an empty array, an empty string will be returned.
     */
    public static String join(@NonNull CharSequence delimiter, @NonNull Object[] tokens) {
        final int length = tokens.length;
        if (length == 0) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(tokens[0]);
        for (int i = 1; i < length; i++) {
            sb.append(delimiter);
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    /**
     * Returns a string containing the tokens joined by delimiters.
     *
     * @param delimiter a CharSequence that will be inserted between the tokens. If null, the string
     *     "null" will be used as the delimiter.
     * @param tokens an array objects to be joined. Strings will be formed from the objects by
     *     calling object.toString(). If tokens is null, a NullPointerException will be thrown. If
     *     tokens is empty, an empty string will be returned.
     */
    public static String join(@NonNull CharSequence delimiter, @NonNull Iterable tokens) {
        final Iterator<?> it = tokens.iterator();
        if (!it.hasNext()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(it.next());
        while (it.hasNext()) {
            sb.append(delimiter);
            sb.append(it.next());
        }
        return sb.toString();
    }

    /**
     *
     * This method yields the same result as {@code text.split(expression, -1)} except that if
     * {@code text.isEmpty()} then this method returns an empty array whereas
     * {@code "".split(expression, -1)} would have returned an array with a single {@code ""}.
     *
     * The {@code -1} means that trailing empty Strings are not removed from the result; for
     * example split("a,", ","  ) returns {"a", ""}. Note that whether a leading zero-width match
     * can result in a leading {@code ""} depends on whether your app
     * {@link android.content.pm.ApplicationInfo#targetSdkVersion targets an SDK version}
     * {@code <= 28}; see {@link Pattern#split(CharSequence, int)}.
     *
     * @param text the string to split
     * @param expression the regular expression to match
     * @return an array of strings. The array will be empty if text is empty
     *
     * @throws NullPointerException if expression or text is null
     */
    public static String[] split(String text, String expression) {
        if (text.length() == 0) {
            return EMPTY_STRING_ARRAY;
        } else {
            return text.split(expression, -1);
        }
    }

    /**
     * Splits a string on a pattern. This method yields the same result as
     * {@code pattern.split(text, -1)} except that if {@code text.isEmpty()} then this method
     * returns an empty array whereas {@code pattern.split("", -1)} would have returned an array
     * with a single {@code ""}.
     *
     * The {@code -1} means that trailing empty Strings are not removed from the result;
     * Note that whether a leading zero-width match can result in a leading {@code ""} depends
     * on whether your app {@link android.content.pm.ApplicationInfo#targetSdkVersion targets
     * an SDK version} {@code <= 28}; see {@link Pattern#split(CharSequence, int)}.
     *
     * @param text the string to split
     * @param pattern the regular expression to match
     * @return an array of strings. The array will be empty if text is empty
     *
     * @throws NullPointerException if expression or text is null
     */
    public static String[] split(String text, Pattern pattern) {
        if (text.length() == 0) {
            return EMPTY_STRING_ARRAY;
        } else {
            return pattern.split(text, -1);
        }
    }

    /**
     * An interface for splitting strings according to rules that are opaque to the user of this
     * interface. This also has less overhead than split, which uses regular expressions and
     * allocates an array to hold the results.
     *
     * <p>The most efficient way to use this class is:
     *
     * <pre>
     * // Once
     * TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(delimiter);
     *
     * // Once per string to split
     * splitter.setString(string);
     * for (String s : splitter) {
     *     ...
     * }
     * </pre>
     */
    public interface StringSplitter extends Iterable<String> {
        public void setString(String string);
    }

    /**
     * A simple string splitter.
     *
     * <p>If the final character in the string to split is the delimiter then no empty string will
     * be returned for the empty string after that delimeter. That is, splitting <tt>"a,b,"</tt> on
     * comma will return <tt>"a", "b"</tt>, not <tt>"a", "b", ""</tt>.
     */
    public static class SimpleStringSplitter implements StringSplitter, Iterator<String> {
        private String mString;
        private char mDelimiter;
        private int mPosition;
        private int mLength;

        /**
         * Initializes the splitter. setString may be called later.
         * @param delimiter the delimeter on which to split
         */
        public SimpleStringSplitter(char delimiter) {
            mDelimiter = delimiter;
        }

        /**
         * Sets the string to split
         * @param string the string to split
         */
        public void setString(String string) {
            mString = string;
            mPosition = 0;
            mLength = mString.length();
        }

        public Iterator<String> iterator() {
            return this;
        }

        public boolean hasNext() {
            return mPosition < mLength;
        }

        public String next() {
            int end = mString.indexOf(mDelimiter, mPosition);
            if (end == -1) {
                end = mLength;
            }
            String nextString = mString.substring(mPosition, end);
            mPosition = end + 1; // Skip the delimiter.
            return nextString;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    public static CharSequence stringOrSpannedString(CharSequence source) {
        if (source == null)
            return null;
        if (source instanceof SpannedString)
            return source;
        if (source instanceof Spanned)
            return new SpannedString(source);

        return source.toString();
    }

    /**
     * Returns true if the string is null or 0-length.
     * @param str the string to be examined
     * @return true if str is null or zero length
     */
    public static boolean isEmpty(@Nullable CharSequence str) {
        return str == null || str.length() == 0;
    }

    /** {@hide} */
    public static String nullIfEmpty(@Nullable String str) {
        return isEmpty(str) ? null : str;
    }

    /** {@hide} */
    public static String emptyIfNull(@Nullable String str) {
        return str == null ? "" : str;
    }

    /** {@hide} */
    public static String firstNotEmpty(@Nullable String a, @NonNull String b) {
        return !isEmpty(a) ? a : Preconditions.checkStringNotEmpty(b);
    }

    /** {@hide} */
    public static int length(@Nullable String s) {
        return s != null ? s.length() : 0;
    }

    /**
     * @return interned string if it's null.
     * @hide
     */
    public static String safeIntern(String s) {
        return (s != null) ? s.intern() : null;
    }

    /**
     * Returns the length that the specified CharSequence would have if
     * spaces and ASCII control characters were trimmed from the start and end,
     * as by {@link String#trim}.
     */
    public static int getTrimmedLength(CharSequence s) {
        int len = s.length();

        int start = 0;
        while (start < len && s.charAt(start) <= ' ') {
            start++;
        }

        int end = len;
        while (end > start && s.charAt(end - 1) <= ' ') {
            end--;
        }

        return end - start;
    }

    /**
     * Returns true if a and b are equal, including if they are both null.
     * <p><i>Note: In platform versions 1.1 and earlier, this method only worked well if
     * both the arguments were instances of String.</i></p>
     * @param a first CharSequence to check
     * @param b second CharSequence to check
     * @return true if a and b are equal
     */
    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        int length;
        if (a != null && b != null && (length = a.length()) == b.length()) {
            if (a instanceof String && b instanceof String) {
                return a.equals(b);
            } else {
                for (int i = 0; i < length; i++) {
                    if (a.charAt(i) != b.charAt(i)) return false;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * This function only reverses individual {@code char}s and not their associated
     * spans. It doesn't support surrogate pairs (that correspond to non-BMP code points), combining
     * sequences or conjuncts either.
     * @deprecated Do not use.
     */
    @Deprecated
    public static CharSequence getReverse(CharSequence source, int start, int end) {
        return new Reverser(source, start, end);
    }

    private static class Reverser
    implements CharSequence, GetChars
    {
        public Reverser(CharSequence source, int start, int end) {
            mSource = source;
            mStart = start;
            mEnd = end;
        }

        public int length() {
            return mEnd - mStart;
        }

        public CharSequence subSequence(int start, int end) {
            char[] buf = new char[end - start];

            getChars(start, end, buf, 0);
            return new String(buf);
        }

        @Override
        public String toString() {
            return subSequence(0, length()).toString();
        }

        public char charAt(int off) {
            return (char) UCharacter.getMirror(mSource.charAt(mEnd - 1 - off));
        }

        @SuppressWarnings("deprecation")
        public void getChars(int start, int end, char[] dest, int destoff) {
            TextUtils.getChars(mSource, start + mStart, end + mStart,
                               dest, destoff);
            AndroidCharacter.mirror(dest, 0, end - start);

            int len = end - start;
            int n = (end - start) / 2;
            for (int i = 0; i < n; i++) {
                char tmp = dest[destoff + i];

                dest[destoff + i] = dest[destoff + len - i - 1];
                dest[destoff + len - i - 1] = tmp;
            }
        }

        private CharSequence mSource;
        private int mStart;
        private int mEnd;
    }

    /** @hide */
    public static final int ALIGNMENT_SPAN = 1;
    /** @hide */
    public static final int FIRST_SPAN = ALIGNMENT_SPAN;
    /** @hide */
    public static final int FOREGROUND_COLOR_SPAN = 2;
    /** @hide */
    public static final int RELATIVE_SIZE_SPAN = 3;
    /** @hide */
    public static final int SCALE_X_SPAN = 4;
    /** @hide */
    public static final int STRIKETHROUGH_SPAN = 5;
    /** @hide */
    public static final int UNDERLINE_SPAN = 6;
    /** @hide */
    public static final int STYLE_SPAN = 7;
    /** @hide */
    public static final int BULLET_SPAN = 8;
    /** @hide */
    public static final int QUOTE_SPAN = 9;
    /** @hide */
    public static final int LEADING_MARGIN_SPAN = 10;
    /** @hide */
    public static final int URL_SPAN = 11;
    /** @hide */
    public static final int BACKGROUND_COLOR_SPAN = 12;
    /** @hide */
    public static final int TYPEFACE_SPAN = 13;
    /** @hide */
    public static final int SUPERSCRIPT_SPAN = 14;
    /** @hide */
    public static final int SUBSCRIPT_SPAN = 15;
    /** @hide */
    public static final int ABSOLUTE_SIZE_SPAN = 16;
    /** @hide */
    public static final int TEXT_APPEARANCE_SPAN = 17;
    /** @hide */
    public static final int ANNOTATION = 18;
    /** @hide */
    public static final int SUGGESTION_SPAN = 19;
    /** @hide */
    public static final int SPELL_CHECK_SPAN = 20;
    /** @hide */
    public static final int SUGGESTION_RANGE_SPAN = 21;
    /** @hide */
    public static final int EASY_EDIT_SPAN = 22;
    /** @hide */
    public static final int LOCALE_SPAN = 23;
    /** @hide */
    public static final int TTS_SPAN = 24;
    /** @hide */
    public static final int ACCESSIBILITY_CLICKABLE_SPAN = 25;
    /** @hide */
    public static final int ACCESSIBILITY_URL_SPAN = 26;
    /** @hide */
    public static final int LINE_BACKGROUND_SPAN = 27;
    /** @hide */
    public static final int LINE_HEIGHT_SPAN = 28;
    /** @hide */
    public static final int ACCESSIBILITY_REPLACEMENT_SPAN = 29;
    /** @hide */
    public static final int LAST_SPAN = ACCESSIBILITY_REPLACEMENT_SPAN;

    /**
     * Flatten a CharSequence and whatever styles can be copied across processes
     * into the parcel.
     */
    public static void writeToParcel(@Nullable CharSequence cs, @NonNull Parcel p,
            int parcelableFlags) {
        if (cs instanceof Spanned) {
            p.writeInt(0);
            p.writeString8(cs.toString());

            Spanned sp = (Spanned) cs;
            Object[] os = sp.getSpans(0, cs.length(), Object.class);

            // note to people adding to this: check more specific types
            // before more generic types.  also notice that it uses
            // "if" instead of "else if" where there are interfaces
            // so one object can be several.

            for (int i = 0; i < os.length; i++) {
                Object o = os[i];
                Object prop = os[i];

                if (prop instanceof CharacterStyle) {
                    prop = ((CharacterStyle) prop).getUnderlying();
                }

                if (prop instanceof ParcelableSpan) {
                    final ParcelableSpan ps = (ParcelableSpan) prop;
                    final int spanTypeId = ps.getSpanTypeIdInternal();
                    if (spanTypeId < FIRST_SPAN || spanTypeId > LAST_SPAN) {
                        Log.e(TAG, "External class \"" + ps.getClass().getSimpleName()
                                + "\" is attempting to use the frameworks-only ParcelableSpan"
                                + " interface");
                    } else {
                        p.writeInt(spanTypeId);
                        ps.writeToParcelInternal(p, parcelableFlags);
                        writeWhere(p, sp, o);
                    }
                }
            }

            p.writeInt(0);
        } else {
            p.writeInt(1);
            if (cs != null) {
                p.writeString8(cs.toString());
            } else {
                p.writeString8(null);
            }
        }
    }

    private static void writeWhere(Parcel p, Spanned sp, Object o) {
        p.writeInt(sp.getSpanStart(o));
        p.writeInt(sp.getSpanEnd(o));
        p.writeInt(sp.getSpanFlags(o));
    }

    public static final Parcelable.Creator<CharSequence> CHAR_SEQUENCE_CREATOR
            = new Parcelable.Creator<CharSequence>() {
        /**
         * Read and return a new CharSequence, possibly with styles,
         * from the parcel.
         */
        public CharSequence createFromParcel(Parcel p) {
            int kind = p.readInt();

            String string = p.readString8();
            if (string == null) {
                return null;
            }

            if (kind == 1) {
                return string;
            }

            SpannableString sp = new SpannableString(string);

            while (true) {
                kind = p.readInt();

                if (kind == 0)
                    break;

                switch (kind) {
                case ALIGNMENT_SPAN:
                    readSpan(p, sp, new AlignmentSpan.Standard(p));
                    break;

                case FOREGROUND_COLOR_SPAN:
                    readSpan(p, sp, new ForegroundColorSpan(p));
                    break;

                case RELATIVE_SIZE_SPAN:
                    readSpan(p, sp, new RelativeSizeSpan(p));
                    break;

                case SCALE_X_SPAN:
                    readSpan(p, sp, new ScaleXSpan(p));
                    break;

                case STRIKETHROUGH_SPAN:
                    readSpan(p, sp, new StrikethroughSpan(p));
                    break;

                case UNDERLINE_SPAN:
                    readSpan(p, sp, new UnderlineSpan(p));
                    break;

                case STYLE_SPAN:
                    readSpan(p, sp, new StyleSpan(p));
                    break;

                case BULLET_SPAN:
                    readSpan(p, sp, new BulletSpan(p));
                    break;

                case QUOTE_SPAN:
                    readSpan(p, sp, new QuoteSpan(p));
                    break;

                case LEADING_MARGIN_SPAN:
                    readSpan(p, sp, new LeadingMarginSpan.Standard(p));
                    break;

                case URL_SPAN:
                    readSpan(p, sp, new URLSpan(p));
                    break;

                case BACKGROUND_COLOR_SPAN:
                    readSpan(p, sp, new BackgroundColorSpan(p));
                    break;

                case TYPEFACE_SPAN:
                    readSpan(p, sp, new TypefaceSpan(p));
                    break;

                case SUPERSCRIPT_SPAN:
                    readSpan(p, sp, new SuperscriptSpan(p));
                    break;

                case SUBSCRIPT_SPAN:
                    readSpan(p, sp, new SubscriptSpan(p));
                    break;

                case ABSOLUTE_SIZE_SPAN:
                    readSpan(p, sp, new AbsoluteSizeSpan(p));
                    break;

                case TEXT_APPEARANCE_SPAN:
                    readSpan(p, sp, new TextAppearanceSpan(p));
                    break;

                case ANNOTATION:
                    readSpan(p, sp, new Annotation(p));
                    break;

                case SUGGESTION_SPAN:
                    readSpan(p, sp, new SuggestionSpan(p));
                    break;

                case SPELL_CHECK_SPAN:
                    readSpan(p, sp, new SpellCheckSpan(p));
                    break;

                case SUGGESTION_RANGE_SPAN:
                    readSpan(p, sp, new SuggestionRangeSpan(p));
                    break;

                case EASY_EDIT_SPAN:
                    readSpan(p, sp, new EasyEditSpan(p));
                    break;

                case LOCALE_SPAN:
                    readSpan(p, sp, new LocaleSpan(p));
                    break;

                case TTS_SPAN:
                    readSpan(p, sp, new TtsSpan(p));
                    break;

                case ACCESSIBILITY_CLICKABLE_SPAN:
                    readSpan(p, sp, new AccessibilityClickableSpan(p));
                    break;

                case ACCESSIBILITY_URL_SPAN:
                    readSpan(p, sp, new AccessibilityURLSpan(p));
                    break;

                case LINE_BACKGROUND_SPAN:
                    readSpan(p, sp, new LineBackgroundSpan.Standard(p));
                    break;

                case LINE_HEIGHT_SPAN:
                    readSpan(p, sp, new LineHeightSpan.Standard(p));
                    break;

                case ACCESSIBILITY_REPLACEMENT_SPAN:
                    readSpan(p, sp, new AccessibilityReplacementSpan(p));
                    break;

                default:
                    throw new RuntimeException("bogus span encoding " + kind);
                }
            }

            return sp;
        }

        public CharSequence[] newArray(int size)
        {
            return new CharSequence[size];
        }
    };

    /**
     * Debugging tool to print the spans in a CharSequence.  The output will
     * be printed one span per line.  If the CharSequence is not a Spanned,
     * then the entire string will be printed on a single line.
     */
    public static void dumpSpans(CharSequence cs, Printer printer, String prefix) {
        if (cs instanceof Spanned) {
            Spanned sp = (Spanned) cs;
            Object[] os = sp.getSpans(0, cs.length(), Object.class);

            for (int i = 0; i < os.length; i++) {
                Object o = os[i];
                printer.println(prefix + cs.subSequence(sp.getSpanStart(o),
                        sp.getSpanEnd(o)) + ": "
                        + Integer.toHexString(System.identityHashCode(o))
                        + " " + o.getClass().getCanonicalName()
                         + " (" + sp.getSpanStart(o) + "-" + sp.getSpanEnd(o)
                         + ") fl=#" + sp.getSpanFlags(o));
            }
        } else {
            printer.println(prefix + cs + ": (no spans)");
        }
    }

    /**
     * Return a new CharSequence in which each of the source strings is
     * replaced by the corresponding element of the destinations.
     */
    public static CharSequence replace(CharSequence template,
                                       String[] sources,
                                       CharSequence[] destinations) {
        SpannableStringBuilder tb = new SpannableStringBuilder(template);

        for (int i = 0; i < sources.length; i++) {
            int where = indexOf(tb, sources[i]);

            if (where >= 0)
                tb.setSpan(sources[i], where, where + sources[i].length(),
                           Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        for (int i = 0; i < sources.length; i++) {
            int start = tb.getSpanStart(sources[i]);
            int end = tb.getSpanEnd(sources[i]);

            if (start >= 0) {
                tb.replace(start, end, destinations[i]);
            }
        }

        return tb;
    }

    /**
     * Replace instances of "^1", "^2", etc. in the
     * <code>template</code> CharSequence with the corresponding
     * <code>values</code>.  "^^" is used to produce a single caret in
     * the output.  Only up to 9 replacement values are supported,
     * "^10" will be produce the first replacement value followed by a
     * '0'.
     *
     * @param template the input text containing "^1"-style
     * placeholder values.  This object is not modified; a copy is
     * returned.
     *
     * @param values CharSequences substituted into the template.  The
     * first is substituted for "^1", the second for "^2", and so on.
     *
     * @return the new CharSequence produced by doing the replacement
     *
     * @throws IllegalArgumentException if the template requests a
     * value that was not provided, or if more than 9 values are
     * provided.
     */
    public static CharSequence expandTemplate(CharSequence template,
                                              CharSequence... values) {
        if (values.length > 9) {
            throw new IllegalArgumentException("max of 9 values are supported");
        }

        SpannableStringBuilder ssb = new SpannableStringBuilder(template);

        try {
            int i = 0;
            while (i < ssb.length()) {
                if (ssb.charAt(i) == '^') {
                    char next = ssb.charAt(i+1);
                    if (next == '^') {
                        ssb.delete(i+1, i+2);
                        ++i;
                        continue;
                    } else if (Character.isDigit(next)) {
                        int which = Character.getNumericValue(next) - 1;
                        if (which < 0) {
                            throw new IllegalArgumentException(
                                "template requests value ^" + (which+1));
                        }
                        if (which >= values.length) {
                            throw new IllegalArgumentException(
                                "template requests value ^" + (which+1) +
                                "; only " + values.length + " provided");
                        }
                        ssb.replace(i, i+2, values[which]);
                        i += values[which].length();
                        continue;
                    }
                }
                ++i;
            }
        } catch (IndexOutOfBoundsException ignore) {
            // happens when ^ is the last character in the string.
        }
        return ssb;
    }

    public static int getOffsetBefore(CharSequence text, int offset) {
        if (offset == 0)
            return 0;
        if (offset == 1)
            return 0;

        char c = text.charAt(offset - 1);

        if (c >= '\uDC00' && c <= '\uDFFF') {
            char c1 = text.charAt(offset - 2);

            if (c1 >= '\uD800' && c1 <= '\uDBFF')
                offset -= 2;
            else
                offset -= 1;
        } else {
            offset -= 1;
        }

        if (text instanceof Spanned) {
            ReplacementSpan[] spans = ((Spanned) text).getSpans(offset, offset,
                                                       ReplacementSpan.class);

            for (int i = 0; i < spans.length; i++) {
                int start = ((Spanned) text).getSpanStart(spans[i]);
                int end = ((Spanned) text).getSpanEnd(spans[i]);

                if (start < offset && end > offset)
                    offset = start;
            }
        }

        return offset;
    }

    public static int getOffsetAfter(CharSequence text, int offset) {
        int len = text.length();

        if (offset == len)
            return len;
        if (offset == len - 1)
            return len;

        char c = text.charAt(offset);

        if (c >= '\uD800' && c <= '\uDBFF') {
            char c1 = text.charAt(offset + 1);

            if (c1 >= '\uDC00' && c1 <= '\uDFFF')
                offset += 2;
            else
                offset += 1;
        } else {
            offset += 1;
        }

        if (text instanceof Spanned) {
            ReplacementSpan[] spans = ((Spanned) text).getSpans(offset, offset,
                                                       ReplacementSpan.class);

            for (int i = 0; i < spans.length; i++) {
                int start = ((Spanned) text).getSpanStart(spans[i]);
                int end = ((Spanned) text).getSpanEnd(spans[i]);

                if (start < offset && end > offset)
                    offset = end;
            }
        }

        return offset;
    }

    private static void readSpan(Parcel p, Spannable sp, Object o) {
        sp.setSpan(o, p.readInt(), p.readInt(), p.readInt());
    }

    /**
     * Copies the spans from the region <code>start...end</code> in
     * <code>source</code> to the region
     * <code>destoff...destoff+end-start</code> in <code>dest</code>.
     * Spans in <code>source</code> that begin before <code>start</code>
     * or end after <code>end</code> but overlap this range are trimmed
     * as if they began at <code>start</code> or ended at <code>end</code>.
     *
     * @throws IndexOutOfBoundsException if any of the copied spans
     * are out of range in <code>dest</code>.
     */
    public static void copySpansFrom(Spanned source, int start, int end,
                                     Class kind,
                                     Spannable dest, int destoff) {
        if (kind == null) {
            kind = Object.class;
        }

        Object[] spans = source.getSpans(start, end, kind);

        for (int i = 0; i < spans.length; i++) {
            int st = source.getSpanStart(spans[i]);
            int en = source.getSpanEnd(spans[i]);
            int fl = source.getSpanFlags(spans[i]);

            if (st < start)
                st = start;
            if (en > end)
                en = end;

            dest.setSpan(spans[i], st - start + destoff, en - start + destoff,
                         fl);
        }
    }

    /**
     * Transforms a CharSequences to uppercase, copying the sources spans and keeping them spans as
     * much as possible close to their relative original places. In the case the the uppercase
     * string is identical to the sources, the source itself is returned instead of being copied.
     *
     * If copySpans is set, source must be an instance of Spanned.
     *
     * {@hide}
     */
    @NonNull
    public static CharSequence toUpperCase(@Nullable Locale locale, @NonNull CharSequence source,
            boolean copySpans) {
        final Edits edits = new Edits();
        if (!copySpans) { // No spans. Just uppercase the characters.
            final StringBuilder result = CaseMap.toUpper().apply(
                    locale, source, new StringBuilder(), edits);
            return edits.hasChanges() ? result : source;
        }

        final SpannableStringBuilder result = CaseMap.toUpper().apply(
                locale, source, new SpannableStringBuilder(), edits);
        if (!edits.hasChanges()) {
            // No changes happened while capitalizing. We can return the source as it was.
            return source;
        }

        final Edits.Iterator iterator = edits.getFineIterator();
        final int sourceLength = source.length();
        final Spanned spanned = (Spanned) source;
        final Object[] spans = spanned.getSpans(0, sourceLength, Object.class);
        for (Object span : spans) {
            final int sourceStart = spanned.getSpanStart(span);
            final int sourceEnd = spanned.getSpanEnd(span);
            final int flags = spanned.getSpanFlags(span);
            // Make sure the indices are not at the end of the string, since in that case
            // iterator.findSourceIndex() would fail.
            final int destStart = sourceStart == sourceLength ? result.length() :
                    toUpperMapToDest(iterator, sourceStart);
            final int destEnd = sourceEnd == sourceLength ? result.length() :
                    toUpperMapToDest(iterator, sourceEnd);
            result.setSpan(span, destStart, destEnd, flags);
        }
        return result;
    }

    // helper method for toUpperCase()
    private static int toUpperMapToDest(Edits.Iterator iterator, int sourceIndex) {
        // Guaranteed to succeed if sourceIndex < source.length().
        iterator.findSourceIndex(sourceIndex);
        if (sourceIndex == iterator.sourceIndex()) {
            return iterator.destinationIndex();
        }
        // We handle the situation differently depending on if we are in the changed slice or an
        // unchanged one: In an unchanged slice, we can find the exact location the span
        // boundary was before and map there.
        //
        // But in a changed slice, we need to treat the whole destination slice as an atomic unit.
        // We adjust the span boundary to the end of that slice to reduce of the chance of adjacent
        // spans in the source overlapping in the result. (The choice for the end vs the beginning
        // is somewhat arbitrary, but was taken because we except to see slightly more spans only
        // affecting a base character compared to spans only affecting a combining character.)
        if (iterator.hasChange()) {
            return iterator.destinationIndex() + iterator.newLength();
        } else {
            // Move the index 1:1 along with this unchanged piece of text.
            return iterator.destinationIndex() + (sourceIndex - iterator.sourceIndex());
        }
    }

    public enum TruncateAt {
        START,
        MIDDLE,
        END,
        MARQUEE,
        /**
         * @hide
         */
        @UnsupportedAppUsage
        END_SMALL
    }

    public interface EllipsizeCallback {
        /**
         * This method is called to report that the specified region of
         * text was ellipsized away by a call to {@link #ellipsize}.
         */
        public void ellipsized(int start, int end);
    }

    /**
     * Returns the original text if it fits in the specified width
     * given the properties of the specified Paint,
     * or, if it does not fit, a truncated
     * copy with ellipsis character added at the specified edge or center.
     */
    public static CharSequence ellipsize(CharSequence text,
                                         TextPaint p,
                                         float avail, TruncateAt where) {
        return ellipsize(text, p, avail, where, false, null);
    }

    /**
     * Returns the original text if it fits in the specified width
     * given the properties of the specified Paint,
     * or, if it does not fit, a copy with ellipsis character added
     * at the specified edge or center.
     * If <code>preserveLength</code> is specified, the returned copy
     * will be padded with zero-width spaces to preserve the original
     * length and offsets instead of truncating.
     * If <code>callback</code> is non-null, it will be called to
     * report the start and end of the ellipsized range.  TextDirection
     * is determined by the first strong directional character.
     */
    public static CharSequence ellipsize(CharSequence text,
                                         TextPaint paint,
                                         float avail, TruncateAt where,
                                         boolean preserveLength,
                                         @Nullable EllipsizeCallback callback) {
        return ellipsize(text, paint, avail, where, preserveLength, callback,
                TextDirectionHeuristics.FIRSTSTRONG_LTR,
                getEllipsisString(where));
    }

    /**
     * Returns the original text if it fits in the specified width
     * given the properties of the specified Paint,
     * or, if it does not fit, a copy with ellipsis character added
     * at the specified edge or center.
     * If <code>preserveLength</code> is specified, the returned copy
     * will be padded with zero-width spaces to preserve the original
     * length and offsets instead of truncating.
     * If <code>callback</code> is non-null, it will be called to
     * report the start and end of the ellipsized range.
     *
     * @hide
     */
    public static CharSequence ellipsize(CharSequence text,
            TextPaint paint,
            float avail, TruncateAt where,
            boolean preserveLength,
            @Nullable EllipsizeCallback callback,
            TextDirectionHeuristic textDir, String ellipsis) {

        int len = text.length();

        MeasuredParagraph mt = null;
        try {
            mt = MeasuredParagraph.buildForMeasurement(paint, text, 0, text.length(), textDir, mt);
            float width = mt.getWholeWidth();

            if (width <= avail) {
                if (callback != null) {
                    callback.ellipsized(0, 0);
                }

                return text;
            }

            // XXX assumes ellipsis string does not require shaping and
            // is unaffected by style
            float ellipsiswid = paint.measureText(ellipsis);
            avail -= ellipsiswid;

            int left = 0;
            int right = len;
            if (avail < 0) {
                // it all goes
            } else if (where == TruncateAt.START) {
                right = len - mt.breakText(len, false, avail);
            } else if (where == TruncateAt.END || where == TruncateAt.END_SMALL) {
                left = mt.breakText(len, true, avail);
            } else {
                right = len - mt.breakText(len, false, avail / 2);
                avail -= mt.measure(right, len);
                left = mt.breakText(right, true, avail);
            }

            if (callback != null) {
                callback.ellipsized(left, right);
            }

            final char[] buf = mt.getChars();
            Spanned sp = text instanceof Spanned ? (Spanned) text : null;

            final int removed = right - left;
            final int remaining = len - removed;
            if (preserveLength) {
                if (remaining > 0 && removed >= ellipsis.length()) {
                    ellipsis.getChars(0, ellipsis.length(), buf, left);
                    left += ellipsis.length();
                } // else skip the ellipsis
                for (int i = left; i < right; i++) {
                    buf[i] = ELLIPSIS_FILLER;
                }
                String s = new String(buf, 0, len);
                if (sp == null) {
                    return s;
                }
                SpannableString ss = new SpannableString(s);
                copySpansFrom(sp, 0, len, Object.class, ss, 0);
                return ss;
            }

            if (remaining == 0) {
                return "";
            }

            if (sp == null) {
                StringBuilder sb = new StringBuilder(remaining + ellipsis.length());
                sb.append(buf, 0, left);
                sb.append(ellipsis);
                sb.append(buf, right, len - right);
                return sb.toString();
            }

            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append(text, 0, left);
            ssb.append(ellipsis);
            ssb.append(text, right, len);
            return ssb;
        } finally {
            if (mt != null) {
                mt.recycle();
            }
        }
    }

    /**
     * Formats a list of CharSequences by repeatedly inserting the separator between them,
     * but stopping when the resulting sequence is too wide for the specified width.
     *
     * This method actually tries to fit the maximum number of elements. So if {@code "A, 11 more"
     * fits}, {@code "A, B, 10 more"} doesn't fit, but {@code "A, B, C, 9 more"} fits again (due to
     * the glyphs for the digits being very wide, for example), it returns
     * {@code "A, B, C, 9 more"}. Because of this, this method may be inefficient for very long
     * lists.
     *
     * Note that the elements of the returned value, as well as the string for {@code moreId}, will
     * be bidi-wrapped using {@link BidiFormatter#unicodeWrap} based on the locale of the input
     * Context. If the input {@code Context} is null, the default BidiFormatter from
     * {@link BidiFormatter#getInstance()} will be used.
     *
     * @param context the {@code Context} to get the {@code moreId} resource from. If {@code null},
     *     an ellipsis (U+2026) would be used for {@code moreId}.
     * @param elements the list to format
     * @param separator a separator, such as {@code ", "}
     * @param paint the Paint with which to measure the text
     * @param avail the horizontal width available for the text (in pixels)
     * @param moreId the resource ID for the pluralized string to insert at the end of sequence when
     *     some of the elements don't fit.
     *
     * @return the formatted CharSequence. If even the shortest sequence (e.g. {@code "A, 11 more"})
     *     doesn't fit, it will return an empty string.
     */

    public static CharSequence listEllipsize(@Nullable Context context,
            @Nullable List<CharSequence> elements, @NonNull String separator,
            @NonNull TextPaint paint, @FloatRange(from=0.0,fromInclusive=false) float avail,
            @PluralsRes int moreId) {
        if (elements == null) {
            return "";
        }
        final int totalLen = elements.size();
        if (totalLen == 0) {
            return "";
        }

        final Resources res;
        final BidiFormatter bidiFormatter;
        if (context == null) {
            res = null;
            bidiFormatter = BidiFormatter.getInstance();
        } else {
            res = context.getResources();
            bidiFormatter = BidiFormatter.getInstance(res.getConfiguration().getLocales().get(0));
        }

        final SpannableStringBuilder output = new SpannableStringBuilder();
        final int[] endIndexes = new int[totalLen];
        for (int i = 0; i < totalLen; i++) {
            output.append(bidiFormatter.unicodeWrap(elements.get(i)));
            if (i != totalLen - 1) {  // Insert a separator, except at the very end.
                output.append(separator);
            }
            endIndexes[i] = output.length();
        }

        for (int i = totalLen - 1; i >= 0; i--) {
            // Delete the tail of the string, cutting back to one less element.
            output.delete(endIndexes[i], output.length());

            final int remainingElements = totalLen - i - 1;
            if (remainingElements > 0) {
                CharSequence morePiece = (res == null) ?
                        ELLIPSIS_NORMAL :
                        res.getQuantityString(moreId, remainingElements, remainingElements);
                morePiece = bidiFormatter.unicodeWrap(morePiece);
                output.append(morePiece);
            }

            final float width = paint.measureText(output, 0, output.length());
            if (width <= avail) {  // The string fits.
                return output;
            }
        }
        return "";  // Nothing fits.
    }

    /**
     * Converts a CharSequence of the comma-separated form "Andy, Bob,
     * Charles, David" that is too wide to fit into the specified width
     * into one like "Andy, Bob, 2 more".
     *
     * @param text the text to truncate
     * @param p the Paint with which to measure the text
     * @param avail the horizontal width available for the text (in pixels)
     * @param oneMore the string for "1 more" in the current locale
     * @param more the string for "%d more" in the current locale
     *
     * @deprecated Do not use. This is not internationalized, and has known issues
     * with right-to-left text, languages that have more than one plural form, languages
     * that use a different character as a comma-like separator, etc.
     * Use {@link #listEllipsize} instead.
     */
    @Deprecated
    public static CharSequence commaEllipsize(CharSequence text,
                                              TextPaint p, float avail,
                                              String oneMore,
                                              String more) {
        return commaEllipsize(text, p, avail, oneMore, more,
                TextDirectionHeuristics.FIRSTSTRONG_LTR);
    }

    /**
     * @hide
     */
    @Deprecated
    public static CharSequence commaEllipsize(CharSequence text, TextPaint p,
         float avail, String oneMore, String more, TextDirectionHeuristic textDir) {

        MeasuredParagraph mt = null;
        MeasuredParagraph tempMt = null;
        try {
            int len = text.length();
            mt = MeasuredParagraph.buildForMeasurement(p, text, 0, len, textDir, mt);
            final float width = mt.getWholeWidth();
            if (width <= avail) {
                return text;
            }

            char[] buf = mt.getChars();

            int commaCount = 0;
            for (int i = 0; i < len; i++) {
                if (buf[i] == ',') {
                    commaCount++;
                }
            }

            int remaining = commaCount + 1;

            int ok = 0;
            String okFormat = "";

            int w = 0;
            int count = 0;
            float[] widths = mt.getWidths().getRawArray();

            for (int i = 0; i < len; i++) {
                w += widths[i];

                if (buf[i] == ',') {
                    count++;

                    String format;
                    // XXX should not insert spaces, should be part of string
                    // XXX should use plural rules and not assume English plurals
                    if (--remaining == 1) {
                        format = " " + oneMore;
                    } else {
                        format = " " + String.format(more, remaining);
                    }

                    // XXX this is probably ok, but need to look at it more
                    tempMt = MeasuredParagraph.buildForMeasurement(
                            p, format, 0, format.length(), textDir, tempMt);
                    float moreWid = tempMt.getWholeWidth();

                    if (w + moreWid <= avail) {
                        ok = i + 1;
                        okFormat = format;
                    }
                }
            }

            SpannableStringBuilder out = new SpannableStringBuilder(okFormat);
            out.insert(0, text, 0, ok);
            return out;
        } finally {
            if (mt != null) {
                mt.recycle();
            }
            if (tempMt != null) {
                tempMt.recycle();
            }
        }
    }

    // Returns true if the character's presence could affect RTL layout.
    //
    // In order to be fast, the code is intentionally rough and quite conservative in its
    // considering inclusion of any non-BMP or surrogate characters or anything in the bidi
    // blocks or any bidi formatting characters with a potential to affect RTL layout.
    /* package */
    static boolean couldAffectRtl(char c) {
        return (0x0590 <= c && c <= 0x08FF) ||  // RTL scripts
                c == 0x200E ||  // Bidi format character
                c == 0x200F ||  // Bidi format character
                (0x202A <= c && c <= 0x202E) ||  // Bidi format characters
                (0x2066 <= c && c <= 0x2069) ||  // Bidi format characters
                (0xD800 <= c && c <= 0xDFFF) ||  // Surrogate pairs
                (0xFB1D <= c && c <= 0xFDFF) ||  // Hebrew and Arabic presentation forms
                (0xFE70 <= c && c <= 0xFEFE);  // Arabic presentation forms
    }

    // Returns true if there is no character present that may potentially affect RTL layout.
    // Since this calls couldAffectRtl() above, it's also quite conservative, in the way that
    // it may return 'false' (needs bidi) although careful consideration may tell us it should
    // return 'true' (does not need bidi).
    /* package */
    static boolean doesNotNeedBidi(char[] text, int start, int len) {
        final int end = start + len;
        for (int i = start; i < end; i++) {
            if (couldAffectRtl(text[i])) {
                return false;
            }
        }
        return true;
    }

    /* package */ static char[] obtain(int len) {
        char[] buf;

        synchronized (sLock) {
            buf = sTemp;
            sTemp = null;
        }

        if (buf == null || buf.length < len)
            buf = ArrayUtils.newUnpaddedCharArray(len);

        return buf;
    }

    /* package */ static void recycle(char[] temp) {
        if (temp.length > 1000)
            return;

        synchronized (sLock) {
            sTemp = temp;
        }
    }

    /**
     * Html-encode the string.
     * @param s the string to be encoded
     * @return the encoded string
     */
    public static String htmlEncode(String s) {
        StringBuilder sb = new StringBuilder();
        char c;
        for (int i = 0; i < s.length(); i++) {
            c = s.charAt(i);
            switch (c) {
            case '<':
                sb.append("&lt;"); //$NON-NLS-1$
                break;
            case '>':
                sb.append("&gt;"); //$NON-NLS-1$
                break;
            case '&':
                sb.append("&amp;"); //$NON-NLS-1$
                break;
            case '\'':
                //http://www.w3.org/TR/xhtml1
                // The named character reference &apos; (the apostrophe, U+0027) was introduced in
                // XML 1.0 but does not appear in HTML. Authors should therefore use &#39; instead
                // of &apos; to work as expected in HTML 4 user agents.
                sb.append("&#39;"); //$NON-NLS-1$
                break;
            case '"':
                sb.append("&quot;"); //$NON-NLS-1$
                break;
            default:
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Returns a CharSequence concatenating the specified CharSequences,
     * retaining their spans if any.
     *
     * If there are no parameters, an empty string will be returned.
     *
     * If the number of parameters is exactly one, that parameter is returned as output, even if it
     * is null.
     *
     * If the number of parameters is at least two, any null CharSequence among the parameters is
     * treated as if it was the string <code>"null"</code>.
     *
     * If there are paragraph spans in the source CharSequences that satisfy paragraph boundary
     * requirements in the sources but would no longer satisfy them in the concatenated
     * CharSequence, they may get extended in the resulting CharSequence or not retained.
     */
    public static CharSequence concat(CharSequence... text) {
        if (text.length == 0) {
            return "";
        }

        if (text.length == 1) {
            return text[0];
        }

        boolean spanned = false;
        for (CharSequence piece : text) {
            if (piece instanceof Spanned) {
                spanned = true;
                break;
            }
        }

        if (spanned) {
            final SpannableStringBuilder ssb = new SpannableStringBuilder();
            for (CharSequence piece : text) {
                // If a piece is null, we append the string "null" for compatibility with the
                // behavior of StringBuilder and the behavior of the concat() method in earlier
                // versions of Android.
                ssb.append(piece == null ? "null" : piece);
            }
            return new SpannedString(ssb);
        } else {
            final StringBuilder sb = new StringBuilder();
            for (CharSequence piece : text) {
                sb.append(piece);
            }
            return sb.toString();
        }
    }

    /**
     * Returns whether the given CharSequence contains any printable characters.
     */
    public static boolean isGraphic(CharSequence str) {
        final int len = str.length();
        for (int cp, i=0; i<len; i+=Character.charCount(cp)) {
            cp = Character.codePointAt(str, i);
            int gc = Character.getType(cp);
            if (gc != Character.CONTROL
                    && gc != Character.FORMAT
                    && gc != Character.SURROGATE
                    && gc != Character.UNASSIGNED
                    && gc != Character.LINE_SEPARATOR
                    && gc != Character.PARAGRAPH_SEPARATOR
                    && gc != Character.SPACE_SEPARATOR) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether this character is a printable character.
     *
     * This does not support non-BMP characters and should not be used.
     *
     * @deprecated Use {@link #isGraphic(CharSequence)} instead.
     */
    @Deprecated
    public static boolean isGraphic(char c) {
        int gc = Character.getType(c);
        return     gc != Character.CONTROL
                && gc != Character.FORMAT
                && gc != Character.SURROGATE
                && gc != Character.UNASSIGNED
                && gc != Character.LINE_SEPARATOR
                && gc != Character.PARAGRAPH_SEPARATOR
                && gc != Character.SPACE_SEPARATOR;
    }

    /**
     * Returns whether the given CharSequence contains only digits.
     */
    public static boolean isDigitsOnly(CharSequence str) {
        final int len = str.length();
        for (int cp, i = 0; i < len; i += Character.charCount(cp)) {
            cp = Character.codePointAt(str, i);
            if (!Character.isDigit(cp)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @hide
     */
    public static boolean isPrintableAscii(final char c) {
        final int asciiFirst = 0x20;
        final int asciiLast = 0x7E;  // included
        return (asciiFirst <= c && c <= asciiLast) || c == '\r' || c == '\n';
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public static boolean isPrintableAsciiOnly(final CharSequence str) {
        final int len = str.length();
        for (int i = 0; i < len; i++) {
            if (!isPrintableAscii(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Capitalization mode for {@link #getCapsMode}: capitalize all
     * characters.  This value is explicitly defined to be the same as
     * {@link InputType#TYPE_TEXT_FLAG_CAP_CHARACTERS}.
     */
    public static final int CAP_MODE_CHARACTERS
            = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;

    /**
     * Capitalization mode for {@link #getCapsMode}: capitalize the first
     * character of all words.  This value is explicitly defined to be the same as
     * {@link InputType#TYPE_TEXT_FLAG_CAP_WORDS}.
     */
    public static final int CAP_MODE_WORDS
            = InputType.TYPE_TEXT_FLAG_CAP_WORDS;

    /**
     * Capitalization mode for {@link #getCapsMode}: capitalize the first
     * character of each sentence.  This value is explicitly defined to be the same as
     * {@link InputType#TYPE_TEXT_FLAG_CAP_SENTENCES}.
     */
    public static final int CAP_MODE_SENTENCES
            = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;

    /**
     * Determine what caps mode should be in effect at the current offset in
     * the text.  Only the mode bits set in <var>reqModes</var> will be
     * checked.  Note that the caps mode flags here are explicitly defined
     * to match those in {@link InputType}.
     *
     * @param cs The text that should be checked for caps modes.
     * @param off Location in the text at which to check.
     * @param reqModes The modes to be checked: may be any combination of
     * {@link #CAP_MODE_CHARACTERS}, {@link #CAP_MODE_WORDS}, and
     * {@link #CAP_MODE_SENTENCES}.
     *
     * @return Returns the actual capitalization modes that can be in effect
     * at the current position, which is any combination of
     * {@link #CAP_MODE_CHARACTERS}, {@link #CAP_MODE_WORDS}, and
     * {@link #CAP_MODE_SENTENCES}.
     */
    public static int getCapsMode(CharSequence cs, int off, int reqModes) {
        if (off < 0) {
            return 0;
        }

        int i;
        char c;
        int mode = 0;

        if ((reqModes&CAP_MODE_CHARACTERS) != 0) {
            mode |= CAP_MODE_CHARACTERS;
        }
        if ((reqModes&(CAP_MODE_WORDS|CAP_MODE_SENTENCES)) == 0) {
            return mode;
        }

        // Back over allowed opening punctuation.

        for (i = off; i > 0; i--) {
            c = cs.charAt(i - 1);

            if (c != '"' && c != '\'' &&
                Character.getType(c) != Character.START_PUNCTUATION) {
                break;
            }
        }

        // Start of paragraph, with optional whitespace.

        int j = i;
        while (j > 0 && ((c = cs.charAt(j - 1)) == ' ' || c == '\t')) {
            j--;
        }
        if (j == 0 || cs.charAt(j - 1) == '\n') {
            return mode | CAP_MODE_WORDS;
        }

        // Or start of word if we are that style.

        if ((reqModes&CAP_MODE_SENTENCES) == 0) {
            if (i != j) mode |= CAP_MODE_WORDS;
            return mode;
        }

        // There must be a space if not the start of paragraph.

        if (i == j) {
            return mode;
        }

        // Back over allowed closing punctuation.

        for (; j > 0; j--) {
            c = cs.charAt(j - 1);

            if (c != '"' && c != '\'' &&
                Character.getType(c) != Character.END_PUNCTUATION) {
                break;
            }
        }

        if (j > 0) {
            c = cs.charAt(j - 1);

            if (c == '.' || c == '?' || c == '!') {
                // Do not capitalize if the word ends with a period but
                // also contains a period, in which case it is an abbreviation.

                if (c == '.') {
                    for (int k = j - 2; k >= 0; k--) {
                        c = cs.charAt(k);

                        if (c == '.') {
                            return mode;
                        }

                        if (!Character.isLetter(c)) {
                            break;
                        }
                    }
                }

                return mode | CAP_MODE_SENTENCES;
            }
        }

        return mode;
    }

    /**
     * Does a comma-delimited list 'delimitedString' contain a certain item?
     * (without allocating memory)
     *
     * @hide
     */
    public static boolean delimitedStringContains(
            String delimitedString, char delimiter, String item) {
        if (isEmpty(delimitedString) || isEmpty(item)) {
            return false;
        }
        int pos = -1;
        int length = delimitedString.length();
        while ((pos = delimitedString.indexOf(item, pos + 1)) != -1) {
            if (pos > 0 && delimitedString.charAt(pos - 1) != delimiter) {
                continue;
            }
            int expectedDelimiterPos = pos + item.length();
            if (expectedDelimiterPos == length) {
                // Match at end of string.
                return true;
            }
            if (delimitedString.charAt(expectedDelimiterPos) == delimiter) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes empty spans from the <code>spans</code> array.
     *
     * When parsing a Spanned using {@link Spanned#nextSpanTransition(int, int, Class)}, empty spans
     * will (correctly) create span transitions, and calling getSpans on a slice of text bounded by
     * one of these transitions will (correctly) include the empty overlapping span.
     *
     * However, these empty spans should not be taken into account when layouting or rendering the
     * string and this method provides a way to filter getSpans' results accordingly.
     *
     * @param spans A list of spans retrieved using {@link Spanned#getSpans(int, int, Class)} from
     * the <code>spanned</code>
     * @param spanned The Spanned from which spans were extracted
     * @return A subset of spans where empty spans ({@link Spanned#getSpanStart(Object)}  ==
     * {@link Spanned#getSpanEnd(Object)} have been removed. The initial order is preserved
     * @hide
     */
    @SuppressWarnings("unchecked")
    public static <T> T[] removeEmptySpans(T[] spans, Spanned spanned, Class<T> klass) {
        T[] copy = null;
        int count = 0;

        for (int i = 0; i < spans.length; i++) {
            final T span = spans[i];
            final int start = spanned.getSpanStart(span);
            final int end = spanned.getSpanEnd(span);

            if (start == end) {
                if (copy == null) {
                    copy = (T[]) Array.newInstance(klass, spans.length - 1);
                    System.arraycopy(spans, 0, copy, 0, i);
                    count = i;
                }
            } else {
                if (copy != null) {
                    copy[count] = span;
                    count++;
                }
            }
        }

        if (copy != null) {
            T[] result = (T[]) Array.newInstance(klass, count);
            System.arraycopy(copy, 0, result, 0, count);
            return result;
        } else {
            return spans;
        }
    }

    /**
     * Pack 2 int values into a long, useful as a return value for a range
     * @see #unpackRangeStartFromLong(long)
     * @see #unpackRangeEndFromLong(long)
     * @hide
     */
    @UnsupportedAppUsage
    public static long packRangeInLong(int start, int end) {
        return (((long) start) << 32) | end;
    }

    /**
     * Get the start value from a range packed in a long by {@link #packRangeInLong(int, int)}
     * @see #unpackRangeEndFromLong(long)
     * @see #packRangeInLong(int, int)
     * @hide
     */
    @UnsupportedAppUsage
    public static int unpackRangeStartFromLong(long range) {
        return (int) (range >>> 32);
    }

    /**
     * Get the end value from a range packed in a long by {@link #packRangeInLong(int, int)}
     * @see #unpackRangeStartFromLong(long)
     * @see #packRangeInLong(int, int)
     * @hide
     */
    @UnsupportedAppUsage
    public static int unpackRangeEndFromLong(long range) {
        return (int) (range & 0x00000000FFFFFFFFL);
    }

    /**
     * Return the layout direction for a given Locale
     *
     * @param locale the Locale for which we want the layout direction. Can be null.
     * @return the layout direction. This may be one of:
     * {@link android.view.View#LAYOUT_DIRECTION_LTR} or
     * {@link android.view.View#LAYOUT_DIRECTION_RTL}.
     *
     * Be careful: this code will need to be updated when vertical scripts will be supported
     */
    public static int getLayoutDirectionFromLocale(Locale locale) {
        return ((locale != null && !locale.equals(Locale.ROOT)
                        && ULocale.forLocale(locale).isRightToLeft())
                // If forcing into RTL layout mode, return RTL as default
                || DisplayProperties.debug_force_rtl().orElse(false))
            ? View.LAYOUT_DIRECTION_RTL
            : View.LAYOUT_DIRECTION_LTR;
    }

    /**
     * Simple alternative to {@link String#format} which purposefully supports
     * only a small handful of substitutions to improve execution speed.
     * Benchmarking reveals this optimized alternative performs 6.5x faster for
     * a typical format string.
     * <p>
     * Below is a summary of the limited grammar supported by this method; if
     * you need advanced features, please continue using {@link String#format}.
     * <ul>
     * <li>{@code %b} for {@code boolean}
     * <li>{@code %c} for {@code char}
     * <li>{@code %d} for {@code int} or {@code long}
     * <li>{@code %f} for {@code float} or {@code double}
     * <li>{@code %s} for {@code String}
     * <li>{@code %x} for hex representation of {@code int} or {@code long}
     * <li>{@code %%} for literal {@code %}
     * <li>{@code %04d} style grammar to specify the argument width, such as
     * {@code %04d} to prefix an {@code int} with zeros or {@code %10b} to
     * prefix a {@code boolean} with spaces
     * </ul>
     *
     * @throws IllegalArgumentException if the format string or arguments don't
     *             match the supported grammar described above.
     * @hide
     */
    public static @NonNull String formatSimple(@NonNull String format, Object... args) {
        final StringBuilder sb = new StringBuilder(format);
        int j = 0;
        for (int i = 0; i < sb.length(); ) {
            if (sb.charAt(i) == '%') {
                char code = sb.charAt(i + 1);

                // Decode any argument width request
                char prefixChar = '\0';
                int prefixLen = 0;
                int consume = 2;
                while ('0' <= code && code <= '9') {
                    if (prefixChar == '\0') {
                        prefixChar = (code == '0') ? '0' : ' ';
                    }
                    prefixLen *= 10;
                    prefixLen += Character.digit(code, 10);
                    consume += 1;
                    code = sb.charAt(i + consume - 1);
                }

                final String repl;
                switch (code) {
                    case 'b': {
                        if (j == args.length) {
                            throw new IllegalArgumentException("Too few arguments");
                        }
                        final Object arg = args[j++];
                        if (arg instanceof Boolean) {
                            repl = Boolean.toString((boolean) arg);
                        } else {
                            repl = Boolean.toString(arg != null);
                        }
                        break;
                    }
                    case 'c':
                    case 'd':
                    case 'f':
                    case 's': {
                        if (j == args.length) {
                            throw new IllegalArgumentException("Too few arguments");
                        }
                        final Object arg = args[j++];
                        repl = String.valueOf(arg);
                        break;
                    }
                    case 'x': {
                        if (j == args.length) {
                            throw new IllegalArgumentException("Too few arguments");
                        }
                        final Object arg = args[j++];
                        if (arg instanceof Integer) {
                            repl = Integer.toHexString((int) arg);
                        } else if (arg instanceof Long) {
                            repl = Long.toHexString((long) arg);
                        } else {
                            throw new IllegalArgumentException(
                                    "Unsupported hex type " + arg.getClass());
                        }
                        break;
                    }
                    case '%': {
                        repl = "%";
                        break;
                    }
                    default: {
                        throw new IllegalArgumentException("Unsupported format code " + code);
                    }
                }

                sb.replace(i, i + consume, repl);

                // Apply any argument width request
                final int prefixInsert = (prefixChar == '0' && repl.charAt(0) == '-') ? 1 : 0;
                for (int k = repl.length(); k < prefixLen; k++) {
                    sb.insert(i + prefixInsert, prefixChar);
                }
                i += Math.max(repl.length(), prefixLen);
            } else {
                i++;
            }
        }
        if (j != args.length) {
            throw new IllegalArgumentException("Too many arguments");
        }
        return sb.toString();
    }

    /**
     * Returns whether or not the specified spanned text has a style span.
     * @hide
     */
    public static boolean hasStyleSpan(@NonNull Spanned spanned) {
        Preconditions.checkArgument(spanned != null);
        final Class<?>[] styleClasses = {
                CharacterStyle.class, ParagraphStyle.class, UpdateAppearance.class};
        for (Class<?> clazz : styleClasses) {
            if (spanned.nextSpanTransition(-1, spanned.length(), clazz) < spanned.length()) {
                return true;
            }
        }
        return false;
    }

    /**
     * If the {@code charSequence} is instance of {@link Spanned}, creates a new copy and
     * {@link NoCopySpan}'s are removed from the copy. Otherwise the given {@code charSequence} is
     * returned as it is.
     *
     * @hide
     */
    @Nullable
    public static CharSequence trimNoCopySpans(@Nullable CharSequence charSequence) {
        if (charSequence != null && charSequence instanceof Spanned) {
            // SpannableStringBuilder copy constructor trims NoCopySpans.
            return new SpannableStringBuilder(charSequence);
        }
        return charSequence;
    }

    /**
     * Prepends {@code start} and appends {@code end} to a given {@link StringBuilder}
     *
     * @hide
     */
    public static void wrap(StringBuilder builder, String start, String end) {
        builder.insert(0, start);
        builder.append(end);
    }

    /**
     * Intent size limitations prevent sending over a megabyte of data. Limit
     * text length to 100K characters - 200KB.
     */
    private static final int PARCEL_SAFE_TEXT_LENGTH = 100000;

    /**
     * Trims the text to {@link #PARCEL_SAFE_TEXT_LENGTH} length. Returns the string as it is if
     * the length() is smaller than {@link #PARCEL_SAFE_TEXT_LENGTH}. Used for text that is parceled
     * into a {@link Parcelable}.
     *
     * @hide
     */
    @Nullable
    public static <T extends CharSequence> T trimToParcelableSize(@Nullable T text) {
        return trimToSize(text, PARCEL_SAFE_TEXT_LENGTH);
    }

    /**
     * Trims the text to {@code size} length. Returns the string as it is if the length() is
     * smaller than {@code size}. If chars at {@code size-1} and {@code size} is a surrogate
     * pair, returns a CharSequence of length {@code size-1}.
     *
     * @param size length of the result, should be greater than 0
     *
     * @hide
     */
    @Nullable
    public static <T extends CharSequence> T trimToSize(@Nullable T text,
            @IntRange(from = 1) int size) {
        Preconditions.checkArgument(size > 0);
        if (TextUtils.isEmpty(text) || text.length() <= size) return text;
        if (Character.isHighSurrogate(text.charAt(size - 1))
                && Character.isLowSurrogate(text.charAt(size))) {
            size = size - 1;
        }
        return (T) text.subSequence(0, size);
    }

    /**
     * Trims the {@code text} to the first {@code size} characters and adds an ellipsis if the
     * resulting string is shorter than the input. This will result in an output string which is
     * longer than {@code size} for most inputs.
     *
     * @param size length of the result, should be greater than 0
     *
     * @hide
     */
    @Nullable
    public static <T extends CharSequence> T trimToLengthWithEllipsis(@Nullable T text,
            @IntRange(from = 1) int size) {
        T trimmed = trimToSize(text, size);
        if (text != null && trimmed.length() < text.length()) {
            trimmed = (T) (trimmed.toString() + "...");
        }
        return trimmed;
    }

    private static boolean isNewline(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.PARAGRAPH_SEPARATOR || type == Character.LINE_SEPARATOR
                || codePoint == LINE_FEED_CODE_POINT;
    }

    private static boolean isWhiteSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || codePoint == NBSP_CODE_POINT;
    }

    /** @hide */
    @Nullable
    public static String withoutPrefix(@Nullable String prefix, @Nullable String str) {
        if (prefix == null || str == null) return str;
        return str.startsWith(prefix) ? str.substring(prefix.length()) : str;
    }

    /**
     * Remove html, remove bad characters, and truncate string.
     *
     * <p>This method is meant to remove common mistakes and nefarious formatting from strings that
     * were loaded from untrusted sources (such as other packages).
     *
     * <p>This method first {@link Html#fromHtml treats the string like HTML} and then ...
     * <ul>
     * <li>Removes new lines or truncates at first new line
     * <li>Trims the white-space off the end
     * <li>Truncates the string
     * </ul>
     * ... if specified.
     *
     * @param unclean The input string
     * @param maxCharactersToConsider The maximum number of characters of {@code unclean} to
     *                                consider from the input string. {@code 0} disables this
     *                                feature.
     * @param ellipsizeDip Assuming maximum length of the string (in dip), assuming font size 42.
     *                     This is roughly 50 characters for {@code ellipsizeDip == 1000}.<br />
     *                     Usually ellipsizing should be left to the view showing the string. If a
     *                     string is used as an input to another string, it might be useful to
     *                     control the length of the input string though. {@code 0} disables this
     *                     feature.
     * @param flags Flags controlling cleaning behavior (Can be {@link #SAFE_STRING_FLAG_TRIM},
     *              {@link #SAFE_STRING_FLAG_SINGLE_LINE},
     *              and {@link #SAFE_STRING_FLAG_FIRST_LINE})
     *
     * @return The cleaned string
     */
    public static @NonNull CharSequence makeSafeForPresentation(@NonNull String unclean,
            @IntRange(from = 0) int maxCharactersToConsider,
            @FloatRange(from = 0) float ellipsizeDip, @SafeStringFlags int flags) {
        boolean onlyKeepFirstLine = ((flags & SAFE_STRING_FLAG_FIRST_LINE) != 0);
        boolean forceSingleLine = ((flags & SAFE_STRING_FLAG_SINGLE_LINE) != 0);
        boolean trim = ((flags & SAFE_STRING_FLAG_TRIM) != 0);

        Preconditions.checkNotNull(unclean);
        Preconditions.checkArgumentNonnegative(maxCharactersToConsider);
        Preconditions.checkArgumentNonNegative(ellipsizeDip, "ellipsizeDip");
        Preconditions.checkFlagsArgument(flags, SAFE_STRING_FLAG_TRIM
                | SAFE_STRING_FLAG_SINGLE_LINE | SAFE_STRING_FLAG_FIRST_LINE);
        Preconditions.checkArgument(!(onlyKeepFirstLine && forceSingleLine),
                "Cannot set SAFE_STRING_FLAG_SINGLE_LINE and SAFE_STRING_FLAG_FIRST_LINE at the"
                        + "same time");

        String shortString;
        if (maxCharactersToConsider > 0) {
            shortString = unclean.substring(0, Math.min(unclean.length(), maxCharactersToConsider));
        } else {
            shortString = unclean;
        }

        // Treat string as HTML. This
        // - converts HTML symbols: e.g. &szlig; -> ß
        // - applies some HTML tags: e.g. <br> -> \n
        // - removes invalid characters such as \b
        // - removes html styling, such as <b>
        // - applies html formatting: e.g. a<p>b</p>c -> a\n\nb\n\nc
        // - replaces some html tags by "object replacement" markers: <img> -> \ufffc
        // - Removes leading white space
        // - Removes all trailing white space beside a single space
        // - Collapses double white space
        StringWithRemovedChars gettingCleaned = new StringWithRemovedChars(
                Html.fromHtml(shortString).toString());

        int firstNonWhiteSpace = -1;
        int firstTrailingWhiteSpace = -1;

        // Remove new lines (if requested) and control characters.
        int uncleanLength = gettingCleaned.length();
        for (int offset = 0; offset < uncleanLength; ) {
            int codePoint = gettingCleaned.codePointAt(offset);
            int type = Character.getType(codePoint);
            int codePointLen = Character.charCount(codePoint);
            boolean isNewline = isNewline(codePoint);

            if (onlyKeepFirstLine && isNewline) {
                gettingCleaned.removeAllCharAfter(offset);
                break;
            } else if (forceSingleLine && isNewline) {
                gettingCleaned.removeRange(offset, offset + codePointLen);
            } else if (type == Character.CONTROL && !isNewline) {
                gettingCleaned.removeRange(offset, offset + codePointLen);
            } else if (trim && !isWhiteSpace(codePoint)) {
                // This is only executed if the code point is not removed
                if (firstNonWhiteSpace == -1) {
                    firstNonWhiteSpace = offset;
                }
                firstTrailingWhiteSpace = offset + codePointLen;
            }

            offset += codePointLen;
        }

        if (trim) {
            // Remove leading and trailing white space
            if (firstNonWhiteSpace == -1) {
                // No non whitespace found, remove all
                gettingCleaned.removeAllCharAfter(0);
            } else {
                if (firstNonWhiteSpace > 0) {
                    gettingCleaned.removeAllCharBefore(firstNonWhiteSpace);
                }
                if (firstTrailingWhiteSpace < uncleanLength) {
                    gettingCleaned.removeAllCharAfter(firstTrailingWhiteSpace);
                }
            }
        }

        if (ellipsizeDip == 0) {
            return gettingCleaned.toString();
        } else {
            // Truncate
            final TextPaint paint = new TextPaint();
            paint.setTextSize(42);

            return TextUtils.ellipsize(gettingCleaned.toString(), paint, ellipsizeDip,
                    TextUtils.TruncateAt.END);
        }
    }

    /**
     * A special string manipulation class. Just records removals and executes the when onString()
     * is called.
     */
    private static class StringWithRemovedChars {
        /** The original string */
        private final String mOriginal;

        /**
         * One bit per char in string. If bit is set, character needs to be removed. If whole
         * bit field is not initialized nothing needs to be removed.
         */
        private BitSet mRemovedChars;

        StringWithRemovedChars(@NonNull String original) {
            mOriginal = original;
        }

        /**
         * Mark all chars in a range {@code [firstRemoved - firstNonRemoved[} (not including
         * firstNonRemoved) as removed.
         */
        void removeRange(int firstRemoved, int firstNonRemoved) {
            if (mRemovedChars == null) {
                mRemovedChars = new BitSet(mOriginal.length());
            }

            mRemovedChars.set(firstRemoved, firstNonRemoved);
        }

        /**
         * Remove all characters before {@code firstNonRemoved}.
         */
        void removeAllCharBefore(int firstNonRemoved) {
            if (mRemovedChars == null) {
                mRemovedChars = new BitSet(mOriginal.length());
            }

            mRemovedChars.set(0, firstNonRemoved);
        }

        /**
         * Remove all characters after and including {@code firstRemoved}.
         */
        void removeAllCharAfter(int firstRemoved) {
            if (mRemovedChars == null) {
                mRemovedChars = new BitSet(mOriginal.length());
            }

            mRemovedChars.set(firstRemoved, mOriginal.length());
        }

        @Override
        public String toString() {
            // Common case, no chars removed
            if (mRemovedChars == null) {
                return mOriginal;
            }

            StringBuilder sb = new StringBuilder(mOriginal.length());
            for (int i = 0; i < mOriginal.length(); i++) {
                if (!mRemovedChars.get(i)) {
                    sb.append(mOriginal.charAt(i));
                }
            }

            return sb.toString();
        }

        /**
         * Return length or the original string
         */
        int length() {
            return mOriginal.length();
        }

        /**
         * Return codePoint of original string at a certain {@code offset}
         */
        int codePointAt(int offset) {
            return mOriginal.codePointAt(offset);
        }
    }

    private static Object sLock = new Object();

    private static char[] sTemp = null;

    private static String[] EMPTY_STRING_ARRAY = new String[]{};
}

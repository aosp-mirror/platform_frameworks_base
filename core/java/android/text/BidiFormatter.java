/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.view.View;

import static android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR;

import java.util.Locale;

/**
 * Utility class for formatting text for display in a potentially opposite-directionality context
 * without garbling. The directionality of the context is set at formatter creation and the
 * directionality of the text can be either estimated or passed in when known.
 *
 * <p>To support versions lower than {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2},
 * you can use the support library's {@link android.support.v4.text.BidiFormatter} class.
 *
 * <p>These APIs provides the following functionality:
 * <p>
 * 1. Bidi Wrapping
 * When text in one language is mixed into a document in another, opposite-directionality language,
 * e.g. when an English business name is embedded in some Hebrew text, both the inserted string
 * and the text surrounding it may be displayed incorrectly unless the inserted string is explicitly
 * separated from the surrounding text in a "wrapper" that:
 * <p>
 * - Declares its directionality so that the string is displayed correctly. This can be done in
 *   Unicode bidi formatting codes by {@link #unicodeWrap} and similar methods.
 * <p>
 * - Isolates the string's directionality, so it does not unduly affect the surrounding content.
 *   Currently, this can only be done using invisible Unicode characters of the same direction as
 *   the context (LRM or RLM) in addition to the directionality declaration above, thus "resetting"
 *   the directionality to that of the context. The "reset" may need to be done at both ends of the
 *   string. Without "reset" after the string, the string will "stick" to a number or logically
 *   separate opposite-direction text that happens to follow it in-line (even if separated by
 *   neutral content like spaces and punctuation). Without "reset" before the string, the same can
 *   happen there, but only with more opposite-direction text, not a number. One approach is to
 *   "reset" the direction only after each string, on the theory that if the preceding opposite-
 *   direction text is itself bidi-wrapped, the "reset" after it will prevent the sticking. (Doing
 *   the "reset" only before each string definitely does not work because we do not want to require
 *   bidi-wrapping numbers, and a bidi-wrapped opposite-direction string could be followed by a
 *   number.) Still, the safest policy is to do the "reset" on both ends of each string, since RTL
 *   message translations often contain untranslated Latin-script brand names and technical terms,
 *   and one of these can be followed by a bidi-wrapped inserted value. On the other hand, when one
 *   has such a message, it is best to do the "reset" manually in the message translation itself,
 *   since the message's opposite-direction text could be followed by an inserted number, which we
 *   would not bidi-wrap anyway. Thus, "reset" only after the string is the current default. In an
 *   alternative to "reset", recent additions to the HTML, CSS, and Unicode standards allow the
 *   isolation to be part of the directionality declaration. This form of isolation is better than
 *   "reset" because it takes less space, does not require knowing the context directionality, has a
 *   gentler effect than "reset", and protects both ends of the string. However, we do not yet allow
 *   using it because required platforms do not yet support it.
 * <p>
 * Providing these wrapping services is the basic purpose of the bidi formatter.
 * <p>
 * 2. Directionality estimation
 * How does one know whether a string about to be inserted into surrounding text has the same
 * directionality? Well, in many cases, one knows that this must be the case when writing the code
 * doing the insertion, e.g. when a localized message is inserted into a localized page. In such
 * cases there is no need to involve the bidi formatter at all. In some other cases, it need not be
 * the same as the context, but is either constant (e.g. urls are always LTR) or otherwise known.
 * In the remaining cases, e.g. when the string is user-entered or comes from a database, the
 * language of the string (and thus its directionality) is not known a priori, and must be
 * estimated at run-time. The bidi formatter can do this automatically using the default
 * first-strong estimation algorithm. It can also be configured to use a custom directionality
 * estimation object.
 */
public final class BidiFormatter {

    /**
     * The default text direction heuristic.
     */
    private static TextDirectionHeuristic DEFAULT_TEXT_DIRECTION_HEURISTIC = FIRSTSTRONG_LTR;

    /**
     * Unicode "Left-To-Right Embedding" (LRE) character.
     */
    private static final char LRE = '\u202A';

    /**
     * Unicode "Right-To-Left Embedding" (RLE) character.
     */
    private static final char RLE = '\u202B';

    /**
     * Unicode "Pop Directional Formatting" (PDF) character.
     */
    private static final char PDF = '\u202C';

    /**
     *  Unicode "Left-To-Right Mark" (LRM) character.
     */
    private static final char LRM = '\u200E';

    /*
     * Unicode "Right-To-Left Mark" (RLM) character.
     */
    private static final char RLM = '\u200F';

    /*
     * String representation of LRM
     */
    private static final String LRM_STRING = Character.toString(LRM);

    /*
     * String representation of RLM
     */
    private static final String RLM_STRING = Character.toString(RLM);

    /**
     * Empty string constant.
     */
    private static final String EMPTY_STRING = "";

    /**
     * A class for building a BidiFormatter with non-default options.
     */
    public static final class Builder {
        private boolean mIsRtlContext;
        private int mFlags;
        private TextDirectionHeuristic mTextDirectionHeuristic;

        /**
         * Constructor.
         *
         */
        public Builder() {
            initialize(isRtlLocale(Locale.getDefault()));
        }

        /**
         * Constructor.
         *
         * @param rtlContext Whether the context directionality is RTL.
         */
        public Builder(boolean rtlContext) {
            initialize(rtlContext);
        }

        /**
         * Constructor.
         *
         * @param locale The context locale.
         */
        public Builder(Locale locale) {
            initialize(isRtlLocale(locale));
        }

        /**
         * Initializes the builder with the given context directionality and default options.
         *
         * @param isRtlContext Whether the context is RTL or not.
         */
        private void initialize(boolean isRtlContext) {
            mIsRtlContext = isRtlContext;
            mTextDirectionHeuristic = DEFAULT_TEXT_DIRECTION_HEURISTIC;
            mFlags = DEFAULT_FLAGS;
        }

        /**
         * Specifies whether the BidiFormatter to be built should also "reset" directionality before
         * a string being bidi-wrapped, not just after it. The default is true.
         */
        public Builder stereoReset(boolean stereoReset) {
            if (stereoReset) {
                mFlags |= FLAG_STEREO_RESET;
            } else {
                mFlags &= ~FLAG_STEREO_RESET;
            }
            return this;
        }

        /**
         * Specifies the default directionality estimation algorithm to be used by the BidiFormatter.
         * By default, uses the first-strong heuristic.
         *
         * @param heuristic the {@code TextDirectionHeuristic} to use.
         * @return the builder itself.
         */
        public Builder setTextDirectionHeuristic(TextDirectionHeuristic heuristic) {
            mTextDirectionHeuristic = heuristic;
            return this;
        }

        private static BidiFormatter getDefaultInstanceFromContext(boolean isRtlContext) {
            return isRtlContext ? DEFAULT_RTL_INSTANCE : DEFAULT_LTR_INSTANCE;
        }

        /**
         * @return A BidiFormatter with the specified options.
         */
        public BidiFormatter build() {
            if (mFlags == DEFAULT_FLAGS &&
                    mTextDirectionHeuristic == DEFAULT_TEXT_DIRECTION_HEURISTIC) {
                return getDefaultInstanceFromContext(mIsRtlContext);
            }
            return new BidiFormatter(mIsRtlContext, mFlags, mTextDirectionHeuristic);
        }
    }

    //
    private static final int FLAG_STEREO_RESET = 2;
    private static final int DEFAULT_FLAGS = FLAG_STEREO_RESET;

    private static final BidiFormatter DEFAULT_LTR_INSTANCE = new BidiFormatter(
            false /* LTR context */,
            DEFAULT_FLAGS,
            DEFAULT_TEXT_DIRECTION_HEURISTIC);

    private static final BidiFormatter DEFAULT_RTL_INSTANCE = new BidiFormatter(
            true /* RTL context */,
            DEFAULT_FLAGS,
            DEFAULT_TEXT_DIRECTION_HEURISTIC);

    private final boolean mIsRtlContext;
    private final int mFlags;
    private final TextDirectionHeuristic mDefaultTextDirectionHeuristic;

    /**
     * Factory for creating an instance of BidiFormatter for the default locale directionality.
     *
     */
    public static BidiFormatter getInstance() {
        return new Builder().build();
    }

    /**
     * Factory for creating an instance of BidiFormatter given the context directionality.
     *
     * @param rtlContext Whether the context directionality is RTL.
     */
    public static BidiFormatter getInstance(boolean rtlContext) {
        return new Builder(rtlContext).build();
    }

    /**
     * Factory for creating an instance of BidiFormatter given the context locale.
     *
     * @param locale The context locale.
     */
    public static BidiFormatter getInstance(Locale locale) {
        return new Builder(locale).build();
    }

    /**
     * @param isRtlContext Whether the context directionality is RTL or not.
     * @param flags The option flags.
     * @param heuristic The default text direction heuristic.
     */
    private BidiFormatter(boolean isRtlContext, int flags, TextDirectionHeuristic heuristic) {
        mIsRtlContext = isRtlContext;
        mFlags = flags;
        mDefaultTextDirectionHeuristic = heuristic;
    }

    /**
     * @return Whether the context directionality is RTL
     */
    public boolean isRtlContext() {
        return mIsRtlContext;
    }

    /**
     * @return Whether directionality "reset" should also be done before a string being
     * bidi-wrapped, not just after it.
     */
    public boolean getStereoReset() {
        return (mFlags & FLAG_STEREO_RESET) != 0;
    }

    /**
     * Returns a Unicode bidi mark matching the context directionality (LRM or RLM) if either the
     * overall or the exit directionality of a given string is opposite to the context directionality.
     * Putting this after the string (including its directionality declaration wrapping) prevents it
     * from "sticking" to other opposite-directionality text or a number appearing after it inline
     * with only neutral content in between. Otherwise returns the empty string. While the exit
     * directionality is determined by scanning the end of the string, the overall directionality is
     * given explicitly by a heuristic to estimate the {@code str}'s directionality.
     *
     * @param str String after which the mark may need to appear.
     * @param heuristic The text direction heuristic that will be used to estimate the {@code str}'s
     *                  directionality.
     * @return LRM for RTL text in LTR context; RLM for LTR text in RTL context;
     *     else, the empty string.
     *
     * @hide
     */
    public String markAfter(String str, TextDirectionHeuristic heuristic) {
        final boolean isRtl = heuristic.isRtl(str, 0, str.length());
        // getExitDir() is called only if needed (short-circuit).
        if (!mIsRtlContext && (isRtl || getExitDir(str) == DIR_RTL)) {
            return LRM_STRING;
        }
        if (mIsRtlContext && (!isRtl || getExitDir(str) == DIR_LTR)) {
            return RLM_STRING;
        }
        return EMPTY_STRING;
    }

    /**
     * Returns a Unicode bidi mark matching the context directionality (LRM or RLM) if either the
     * overall or the entry directionality of a given string is opposite to the context
     * directionality. Putting this before the string (including its directionality declaration
     * wrapping) prevents it from "sticking" to other opposite-directionality text appearing before
     * it inline with only neutral content in between. Otherwise returns the empty string. While the
     * entry directionality is determined by scanning the beginning of the string, the overall
     * directionality is given explicitly by a heuristic to estimate the {@code str}'s directionality.
     *
     * @param str String before which the mark may need to appear.
     * @param heuristic The text direction heuristic that will be used to estimate the {@code str}'s
     *                  directionality.
     * @return LRM for RTL text in LTR context; RLM for LTR text in RTL context;
     *     else, the empty string.
     *
     * @hide
     */
    public String markBefore(String str, TextDirectionHeuristic heuristic) {
        final boolean isRtl = heuristic.isRtl(str, 0, str.length());
        // getEntryDir() is called only if needed (short-circuit).
        if (!mIsRtlContext && (isRtl || getEntryDir(str) == DIR_RTL)) {
            return LRM_STRING;
        }
        if (mIsRtlContext && (!isRtl || getEntryDir(str) == DIR_LTR)) {
            return RLM_STRING;
        }
        return EMPTY_STRING;
    }

    /**
     * Estimates the directionality of a string using the default text direction heuristic.
     *
     * @param str String whose directionality is to be estimated.
     * @return true if {@code str}'s estimated overall directionality is RTL. Otherwise returns
     *          false.
     */
    public boolean isRtl(String str) {
        return mDefaultTextDirectionHeuristic.isRtl(str, 0, str.length());
    }

    /**
     * Formats a string of given directionality for use in plain-text output of the context
     * directionality, so an opposite-directionality string is neither garbled nor garbles its
     * surroundings. This makes use of Unicode bidi formatting characters.
     * <p>
     * The algorithm: In case the given directionality doesn't match the context directionality, wraps
     * the string with Unicode bidi formatting characters: RLE+{@code str}+PDF for RTL text, or
     * LRE+{@code str}+PDF for LTR text.
     * <p>
     * If {@code isolate}, directionally isolates the string so that it does not garble its
     * surroundings. Currently, this is done by "resetting" the directionality after the string by
     * appending a trailing Unicode bidi mark matching the context directionality (LRM or RLM) when
     * either the overall directionality or the exit directionality of the string is opposite to
     * that of the context. Unless the formatter was built using
     * {@link Builder#stereoReset(boolean)} with a {@code false} argument, also prepends a Unicode
     * bidi mark matching the context directionality when either the overall directionality or the
     * entry directionality of the string is opposite to that of the context. Note that as opposed
     * to the overall directionality, the entry and exit directionalities are determined from the
     * string itself.
     * <p>
     * Does *not* do HTML-escaping.
     *
     * @param str The input string.
     * @param heuristic The algorithm to be used to estimate the string's overall direction.
     *        See {@link TextDirectionHeuristics} for pre-defined heuristics.
     * @param isolate Whether to directionally isolate the string to prevent it from garbling the
     *     content around it
     * @return Input string after applying the above processing. {@code null} if {@code str} is
     *     {@code null}.
     */
    public String unicodeWrap(String str, TextDirectionHeuristic heuristic, boolean isolate) {
        if (str == null) return null;
        final boolean isRtl = heuristic.isRtl(str, 0, str.length());
        StringBuilder result = new StringBuilder();
        if (getStereoReset() && isolate) {
            result.append(markBefore(str,
                    isRtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR));
        }
        if (isRtl != mIsRtlContext) {
            result.append(isRtl ? RLE : LRE);
            result.append(str);
            result.append(PDF);
        } else {
            result.append(str);
        }
        if (isolate) {
            result.append(markAfter(str,
                    isRtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR));
        }
        return result.toString();
    }

    /**
     * Operates like {@link #unicodeWrap(String, TextDirectionHeuristic, boolean)}, but assumes
     * {@code isolate} is true.
     *
     * @param str The input string.
     * @param heuristic The algorithm to be used to estimate the string's overall direction.
     *        See {@link TextDirectionHeuristics} for pre-defined heuristics.
     * @return Input string after applying the above processing.
     */
    public String unicodeWrap(String str, TextDirectionHeuristic heuristic) {
        return unicodeWrap(str, heuristic, true /* isolate */);
    }

    /**
     * Operates like {@link #unicodeWrap(String, TextDirectionHeuristic, boolean)}, but uses the
     * formatter's default direction estimation algorithm.
     *
     * @param str The input string.
     * @param isolate Whether to directionally isolate the string to prevent it from garbling the
     *     content around it
     * @return Input string after applying the above processing.
     */
    public String unicodeWrap(String str, boolean isolate) {
        return unicodeWrap(str, mDefaultTextDirectionHeuristic, isolate);
    }

    /**
     * Operates like {@link #unicodeWrap(String, TextDirectionHeuristic, boolean)}, but uses the
     * formatter's default direction estimation algorithm and assumes {@code isolate} is true.
     *
     * @param str The input string.
     * @return Input string after applying the above processing.
     */
    public String unicodeWrap(String str) {
        return unicodeWrap(str, mDefaultTextDirectionHeuristic, true /* isolate */);
    }

    /**
     * Helper method to return true if the Locale directionality is RTL.
     *
     * @param locale The Locale whose directionality will be checked to be RTL or LTR
     * @return true if the {@code locale} directionality is RTL. False otherwise.
     */
    private static boolean isRtlLocale(Locale locale) {
        return (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL);
    }

    /**
     * Enum for directionality type.
     */
    private static final int DIR_LTR = -1;
    private static final int DIR_UNKNOWN = 0;
    private static final int DIR_RTL = +1;

    /**
     * Returns the directionality of the last character with strong directionality in the string, or
     * DIR_UNKNOWN if none was encountered. For efficiency, actually scans backwards from the end of
     * the string. Treats a non-BN character between an LRE/RLE/LRO/RLO and its matching PDF as a
     * strong character, LTR after LRE/LRO, and RTL after RLE/RLO. The results are undefined for a
     * string containing unbalanced LRE/RLE/LRO/RLO/PDF characters. The intended use is to check
     * whether a logically separate item that starts with a number or a character of the string's
     * exit directionality and follows this string inline (not counting any neutral characters in
     * between) would "stick" to it in an opposite-directionality context, thus being displayed in
     * an incorrect position. An LRM or RLM character (the one of the context's directionality)
     * between the two will prevent such sticking.
     *
     * @param str the string to check.
     */
    private static int getExitDir(String str) {
        return new DirectionalityEstimator(str, false /* isHtml */).getExitDir();
    }

    /**
     * Returns the directionality of the first character with strong directionality in the string,
     * or DIR_UNKNOWN if none was encountered. Treats a non-BN character between an
     * LRE/RLE/LRO/RLO and its matching PDF as a strong character, LTR after LRE/LRO, and RTL after
     * RLE/RLO. The results are undefined for a string containing unbalanced LRE/RLE/LRO/RLO/PDF
     * characters. The intended use is to check whether a logically separate item that ends with a
     * character of the string's entry directionality and precedes the string inline (not counting
     * any neutral characters in between) would "stick" to it in an opposite-directionality context,
     * thus being displayed in an incorrect position. An LRM or RLM character (the one of the
     * context's directionality) between the two will prevent such sticking.
     *
     * @param str the string to check.
     */
    private static int getEntryDir(String str) {
        return new DirectionalityEstimator(str, false /* isHtml */).getEntryDir();
    }

    /**
     * An object that estimates the directionality of a given string by various methods.
     *
     */
    private static class DirectionalityEstimator {

        // Internal static variables and constants.

        /**
         * Size of the bidi character class cache. The results of the Character.getDirectionality()
         * calls on the lowest DIR_TYPE_CACHE_SIZE codepoints are kept in an array for speed.
         * The 0x700 value is designed to leave all the European and Near Eastern languages in the
         * cache. It can be reduced to 0x180, restricting the cache to the Western European
         * languages.
         */
        private static final int DIR_TYPE_CACHE_SIZE = 0x700;

        /**
         * The bidi character class cache.
         */
        private static final byte DIR_TYPE_CACHE[];

        static {
            DIR_TYPE_CACHE = new byte[DIR_TYPE_CACHE_SIZE];
            for (int i = 0; i < DIR_TYPE_CACHE_SIZE; i++) {
                DIR_TYPE_CACHE[i] = Character.getDirectionality(i);
            }
        }

        // Internal instance variables.

        /**
         * The text to be scanned.
         */
        private final String text;

        /**
         * Whether the text to be scanned is to be treated as HTML, i.e. skipping over tags and
         * entities when looking for the next / preceding dir type.
         */
        private final boolean isHtml;

        /**
         * The length of the text in chars.
         */
        private final int length;

        /**
         * The current position in the text.
         */
        private int charIndex;

        /**
         * The char encountered by the last dirTypeForward or dirTypeBackward call. If it
         * encountered a supplementary codepoint, this contains a char that is not a valid
         * codepoint. This is ok, because this member is only used to detect some well-known ASCII
         * syntax, e.g. "http://" and the beginning of an HTML tag or entity.
         */
        private char lastChar;

        /**
         * Constructor.
         *
         * @param text The string to scan.
         * @param isHtml Whether the text to be scanned is to be treated as HTML, i.e. skipping over
         *     tags and entities.
         */
        DirectionalityEstimator(String text, boolean isHtml) {
            this.text = text;
            this.isHtml = isHtml;
            length = text.length();
        }

        /**
         * Returns the directionality of the first character with strong directionality in the
         * string, or DIR_UNKNOWN if none was encountered. Treats a non-BN character between an
         * LRE/RLE/LRO/RLO and its matching PDF as a strong character, LTR after LRE/LRO, and RTL
         * after RLE/RLO. The results are undefined for a string containing unbalanced
         * LRE/RLE/LRO/RLO/PDF characters.
         */
        int getEntryDir() {
            // The reason for this method name, as opposed to getFirstStrongDir(), is that
            // "first strong" is a commonly used description of Unicode's estimation algorithm,
            // but the two must treat formatting characters quite differently. Thus, we are staying
            // away from both "first" and "last" in these method names to avoid confusion.
            charIndex = 0;
            int embeddingLevel = 0;
            int embeddingLevelDir = DIR_UNKNOWN;
            int firstNonEmptyEmbeddingLevel = 0;
            while (charIndex < length && firstNonEmptyEmbeddingLevel == 0) {
                switch (dirTypeForward()) {
                    case Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING:
                    case Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE:
                        ++embeddingLevel;
                        embeddingLevelDir = DIR_LTR;
                        break;
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING:
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE:
                        ++embeddingLevel;
                        embeddingLevelDir = DIR_RTL;
                        break;
                    case Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT:
                        --embeddingLevel;
                        // To restore embeddingLevelDir to its previous value, we would need a
                        // stack, which we want to avoid. Thus, at this point we do not know the
                        // current embedding's directionality.
                        embeddingLevelDir = DIR_UNKNOWN;
                        break;
                    case Character.DIRECTIONALITY_BOUNDARY_NEUTRAL:
                        break;
                    case Character.DIRECTIONALITY_LEFT_TO_RIGHT:
                        if (embeddingLevel == 0) {
                            return DIR_LTR;
                        }
                        firstNonEmptyEmbeddingLevel = embeddingLevel;
                        break;
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT:
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:
                        if (embeddingLevel == 0) {
                            return DIR_RTL;
                        }
                        firstNonEmptyEmbeddingLevel = embeddingLevel;
                        break;
                    default:
                        firstNonEmptyEmbeddingLevel = embeddingLevel;
                        break;
                }
            }

            // We have either found a non-empty embedding or scanned the entire string finding
            // neither a non-empty embedding nor a strong character outside of an embedding.
            if (firstNonEmptyEmbeddingLevel == 0) {
                // We have not found a non-empty embedding. Thus, the string contains neither a
                // non-empty embedding nor a strong character outside of an embedding.
                return DIR_UNKNOWN;
            }

            // We have found a non-empty embedding.
            if (embeddingLevelDir != DIR_UNKNOWN) {
                // We know the directionality of the non-empty embedding.
                return embeddingLevelDir;
            }

            // We do not remember the directionality of the non-empty embedding we found. So, we go
            // backwards to find the start of the non-empty embedding and get its directionality.
            while (charIndex > 0) {
                switch (dirTypeBackward()) {
                    case Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING:
                    case Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE:
                        if (firstNonEmptyEmbeddingLevel == embeddingLevel) {
                            return DIR_LTR;
                        }
                        --embeddingLevel;
                        break;
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING:
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE:
                        if (firstNonEmptyEmbeddingLevel == embeddingLevel) {
                            return DIR_RTL;
                        }
                        --embeddingLevel;
                        break;
                    case Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT:
                        ++embeddingLevel;
                        break;
                }
            }
            // We should never get here.
            return DIR_UNKNOWN;
        }

        /**
         * Returns the directionality of the last character with strong directionality in the
         * string, or DIR_UNKNOWN if none was encountered. For efficiency, actually scans backwards
         * from the end of the string. Treats a non-BN character between an LRE/RLE/LRO/RLO and its
         * matching PDF as a strong character, LTR after LRE/LRO, and RTL after RLE/RLO. The results
         * are undefined for a string containing unbalanced LRE/RLE/LRO/RLO/PDF characters.
         */
        int getExitDir() {
            // The reason for this method name, as opposed to getLastStrongDir(), is that "last
            // strong" sounds like the exact opposite of "first strong", which is a commonly used
            // description of Unicode's estimation algorithm (getUnicodeDir() above), but the two
            // must treat formatting characters quite differently. Thus, we are staying away from
            // both "first" and "last" in these method names to avoid confusion.
            charIndex = length;
            int embeddingLevel = 0;
            int lastNonEmptyEmbeddingLevel = 0;
            while (charIndex > 0) {
                switch (dirTypeBackward()) {
                    case Character.DIRECTIONALITY_LEFT_TO_RIGHT:
                        if (embeddingLevel == 0) {
                            return DIR_LTR;
                        }
                        if (lastNonEmptyEmbeddingLevel == 0) {
                            lastNonEmptyEmbeddingLevel = embeddingLevel;
                        }
                        break;
                    case Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING:
                    case Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE:
                        if (lastNonEmptyEmbeddingLevel == embeddingLevel) {
                            return DIR_LTR;
                        }
                        --embeddingLevel;
                        break;
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT:
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:
                        if (embeddingLevel == 0) {
                            return DIR_RTL;
                        }
                        if (lastNonEmptyEmbeddingLevel == 0) {
                            lastNonEmptyEmbeddingLevel = embeddingLevel;
                        }
                        break;
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING:
                    case Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE:
                        if (lastNonEmptyEmbeddingLevel == embeddingLevel) {
                            return DIR_RTL;
                        }
                        --embeddingLevel;
                        break;
                    case Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT:
                        ++embeddingLevel;
                        break;
                    case Character.DIRECTIONALITY_BOUNDARY_NEUTRAL:
                        break;
                    default:
                        if (lastNonEmptyEmbeddingLevel == 0) {
                            lastNonEmptyEmbeddingLevel = embeddingLevel;
                        }
                        break;
                }
            }
            return DIR_UNKNOWN;
        }

        // Internal methods

        /**
         * Gets the bidi character class, i.e. Character.getDirectionality(), of a given char, using
         * a cache for speed. Not designed for supplementary codepoints, whose results we do not
         * cache.
         */
        private static byte getCachedDirectionality(char c) {
            return c < DIR_TYPE_CACHE_SIZE ? DIR_TYPE_CACHE[c] : Character.getDirectionality(c);
        }

        /**
         * Returns the Character.DIRECTIONALITY_... value of the next codepoint and advances
         * charIndex. If isHtml, and the codepoint is '<' or '&', advances through the tag/entity,
         * and returns Character.DIRECTIONALITY_WHITESPACE. For an entity, it would be best to
         * figure out the actual character, and return its dirtype, but treating it as whitespace is
         * good enough for our purposes.
         *
         * @throws java.lang.IndexOutOfBoundsException if called when charIndex >= length or < 0.
         */
        byte dirTypeForward() {
            lastChar = text.charAt(charIndex);
            if (Character.isHighSurrogate(lastChar)) {
                int codePoint = Character.codePointAt(text, charIndex);
                charIndex += Character.charCount(codePoint);
                return Character.getDirectionality(codePoint);
            }
            charIndex++;
            byte dirType = getCachedDirectionality(lastChar);
            if (isHtml) {
                // Process tags and entities.
                if (lastChar == '<') {
                    dirType = skipTagForward();
                } else if (lastChar == '&') {
                    dirType = skipEntityForward();
                }
            }
            return dirType;
        }

        /**
         * Returns the Character.DIRECTIONALITY_... value of the preceding codepoint and advances
         * charIndex backwards. If isHtml, and the codepoint is the end of a complete HTML tag or
         * entity, advances over the whole tag/entity and returns
         * Character.DIRECTIONALITY_WHITESPACE. For an entity, it would be best to figure out the
         * actual character, and return its dirtype, but treating it as whitespace is good enough
         * for our purposes.
         *
         * @throws java.lang.IndexOutOfBoundsException if called when charIndex > length or <= 0.
         */
        byte dirTypeBackward() {
            lastChar = text.charAt(charIndex - 1);
            if (Character.isLowSurrogate(lastChar)) {
                int codePoint = Character.codePointBefore(text, charIndex);
                charIndex -= Character.charCount(codePoint);
                return Character.getDirectionality(codePoint);
            }
            charIndex--;
            byte dirType = getCachedDirectionality(lastChar);
            if (isHtml) {
                // Process tags and entities.
                if (lastChar == '>') {
                    dirType = skipTagBackward();
                } else if (lastChar == ';') {
                    dirType = skipEntityBackward();
                }
            }
            return dirType;
        }

        /**
         * Advances charIndex forward through an HTML tag (after the opening &lt; has already been
         * read) and returns Character.DIRECTIONALITY_WHITESPACE. If there is no matching &gt;,
         * does not change charIndex and returns Character.DIRECTIONALITY_OTHER_NEUTRALS (for the
         * &lt; that hadn't been part of a tag after all).
         */
        private byte skipTagForward() {
            int initialCharIndex = charIndex;
            while (charIndex < length) {
                lastChar = text.charAt(charIndex++);
                if (lastChar == '>') {
                    // The end of the tag.
                    return Character.DIRECTIONALITY_WHITESPACE;
                }
                if (lastChar == '"' || lastChar == '\'') {
                    // Skip over a quoted attribute value inside the tag.
                    char quote = lastChar;
                    while (charIndex < length && (lastChar = text.charAt(charIndex++)) != quote) {}
                }
            }
            // The original '<' wasn't the start of a tag after all.
            charIndex = initialCharIndex;
            lastChar = '<';
            return Character.DIRECTIONALITY_OTHER_NEUTRALS;
        }

        /**
         * Advances charIndex backward through an HTML tag (after the closing &gt; has already been
         * read) and returns Character.DIRECTIONALITY_WHITESPACE. If there is no matching &lt;, does
         * not change charIndex and returns Character.DIRECTIONALITY_OTHER_NEUTRALS (for the &gt;
         * that hadn't been part of a tag after all). Nevertheless, the running time for calling
         * skipTagBackward() in a loop remains linear in the size of the text, even for a text like
         * "&gt;&gt;&gt;&gt;", because skipTagBackward() also stops looking for a matching &lt;
         * when it encounters another &gt;.
         */
        private byte skipTagBackward() {
            int initialCharIndex = charIndex;
            while (charIndex > 0) {
                lastChar = text.charAt(--charIndex);
                if (lastChar == '<') {
                    // The start of the tag.
                    return Character.DIRECTIONALITY_WHITESPACE;
                }
                if (lastChar == '>') {
                    break;
                }
                if (lastChar == '"' || lastChar == '\'') {
                    // Skip over a quoted attribute value inside the tag.
                    char quote = lastChar;
                    while (charIndex > 0 && (lastChar = text.charAt(--charIndex)) != quote) {}
                }
            }
            // The original '>' wasn't the end of a tag after all.
            charIndex = initialCharIndex;
            lastChar = '>';
            return Character.DIRECTIONALITY_OTHER_NEUTRALS;
        }

        /**
         * Advances charIndex forward through an HTML character entity tag (after the opening
         * &amp; has already been read) and returns Character.DIRECTIONALITY_WHITESPACE. It would be
         * best to figure out the actual character and return its dirtype, but this is good enough.
         */
        private byte skipEntityForward() {
            while (charIndex < length && (lastChar = text.charAt(charIndex++)) != ';') {}
            return Character.DIRECTIONALITY_WHITESPACE;
        }

        /**
         * Advances charIndex backward through an HTML character entity tag (after the closing ;
         * has already been read) and returns Character.DIRECTIONALITY_WHITESPACE. It would be best
         * to figure out the actual character and return its dirtype, but this is good enough.
         * If there is no matching &amp;, does not change charIndex and returns
         * Character.DIRECTIONALITY_OTHER_NEUTRALS (for the ';' that did not start an entity after
         * all). Nevertheless, the running time for calling skipEntityBackward() in a loop remains
         * linear in the size of the text, even for a text like ";;;;;;;", because skipTagBackward()
         * also stops looking for a matching &amp; when it encounters another ;.
         */
        private byte skipEntityBackward() {
            int initialCharIndex = charIndex;
            while (charIndex > 0) {
                lastChar = text.charAt(--charIndex);
                if (lastChar == '&') {
                    return Character.DIRECTIONALITY_WHITESPACE;
                }
                if (lastChar == ';') {
                    break;
                }
            }
            charIndex = initialCharIndex;
            lastChar = ';';
            return Character.DIRECTIONALITY_OTHER_NEUTRALS;
        }
    }
}
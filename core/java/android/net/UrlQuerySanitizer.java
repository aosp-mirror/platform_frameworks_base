/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * Sanitizes the Query portion of a URL. Simple example:
 * <pre class="prettyprint">
 * UrlQuerySanitizer sanitizer = new UrlQuerySanitizer();
 * sanitizer.setAllowUnregisteredParamaters(true);
 * sanitizer.parseUrl("http://example.com/?name=Joe+User");
 * String name = sanitizer.getValue("name"));
 * // name now contains "Joe_User"
 * </pre>
 *
 * Register ValueSanitizers to customize the way individual
 * parameters are sanitized:
 * <pre class="prettyprint">
 * UrlQuerySanitizer sanitizer = new UrlQuerySanitizer();
 * sanitizer.registerParamater("name", UrlQuerySanitizer.createSpaceLegal());
 * sanitizer.parseUrl("http://example.com/?name=Joe+User");
 * String name = sanitizer.getValue("name"));
 * // name now contains "Joe User". (The string is first decoded, which
 * // converts the '+' to a ' '. Then the string is sanitized, which
 * // converts the ' ' to an '_'. (The ' ' is converted because the default
 * unregistered parameter sanitizer does not allow any special characters,
 * and ' ' is a special character.)
 * </pre>
 * <p>
 * There are several ways to create ValueSanitizers. In order of increasing
 * sophistication:
* </p>
 * <ol>
 * <li>Call one of the UrlQuerySanitizer.createXXX() methods.
 * <li>Construct your own instance of
 * UrlQuerySanitizer.IllegalCharacterValueSanitizer.
 * <li>Subclass UrlQuerySanitizer.ValueSanitizer to define your own value
 * sanitizer.
 * </ol>
 *
 */
public class UrlQuerySanitizer {

    /**
     * A simple tuple that holds parameter-value pairs.
     *
     */
    public class ParameterValuePair {
        /**
         * Construct a parameter-value tuple.
         * @param parameter an unencoded parameter
         * @param value an unencoded value
         */
        public ParameterValuePair(String parameter,
                String value) {
            mParameter = parameter;
            mValue = value;
        }
        /**
         * The unencoded parameter
         */
        public String mParameter;
        /**
         * The unencoded value
         */
        public String mValue;
    }

    final private HashMap<String, ValueSanitizer> mSanitizers =
        new HashMap<String, ValueSanitizer>();
    final private HashMap<String, String> mEntries =
        new HashMap<String, String>();
    final private ArrayList<ParameterValuePair> mEntriesList =
        new ArrayList<ParameterValuePair>();
    private boolean mAllowUnregisteredParamaters;
    private boolean mPreferFirstRepeatedParameter;
    private ValueSanitizer mUnregisteredParameterValueSanitizer =
        getAllIllegal();

    /**
     * A functor used to sanitize a single query value.
     *
     */
    public static interface ValueSanitizer {
        /**
         * Sanitize an unencoded value.
         * @param value
         * @return the sanitized unencoded value
         */
        public String sanitize(String value);
    }

    /**
     * Sanitize values based on which characters they contain. Illegal
     * characters are replaced with either space or '_', depending upon
     * whether space is a legal character or not.
     */
    public static class IllegalCharacterValueSanitizer implements
        ValueSanitizer {
        private int mFlags;

        /**
         * Allow space (' ') characters.
         */
        public final static int SPACE_OK =              1 << 0;
        /**
         * Allow whitespace characters other than space. The
         * other whitespace characters are
         * '\t' '\f' '\n' '\r' and '\0x000b' (vertical tab)
         */
        public final static int OTHER_WHITESPACE_OK =  1 << 1;
        /**
         * Allow characters with character codes 128 to 255.
         */
        public final static int NON_7_BIT_ASCII_OK =    1 << 2;
        /**
         * Allow double quote characters. ('"')
         */
        public final static int DQUOTE_OK =             1 << 3;
        /**
         * Allow single quote characters. ('\'')
         */
        public final static int SQUOTE_OK =             1 << 4;
        /**
         * Allow less-than characters. ('<')
         */
        public final static int LT_OK =                 1 << 5;
        /**
         * Allow greater-than characters. ('>')
         */
        public final static int GT_OK =                 1 << 6;
        /**
         * Allow ampersand characters ('&')
         */
        public final static int AMP_OK =                1 << 7;
        /**
         * Allow percent-sign characters ('%')
         */
        public final static int PCT_OK =                1 << 8;
        /**
         * Allow nul characters ('\0')
         */
        public final static int NUL_OK =                1 << 9;
        /**
         * Allow text to start with a script URL
         * such as "javascript:" or "vbscript:"
         */
        public final static int SCRIPT_URL_OK =         1 << 10;

        /**
         * Mask with all fields set to OK
         */
        public final static int ALL_OK =                0x7ff;

        /**
         * Mask with both regular space and other whitespace OK
         */
        public final static int ALL_WHITESPACE_OK =
            SPACE_OK | OTHER_WHITESPACE_OK;


        // Common flag combinations:

        /**
         * <ul>
         * <li>Deny all special characters.
         * <li>Deny script URLs.
         * </ul>
         */
        public final static int ALL_ILLEGAL =
            0;
        /**
         * <ul>
         * <li>Allow all special characters except Nul. ('\0').
         * <li>Allow script URLs.
         * </ul>
         */
        public final static int ALL_BUT_NUL_LEGAL =
            ALL_OK & ~NUL_OK;
        /**
         * <ul>
         * <li>Allow all special characters except for:
         * <ul>
         *  <li>whitespace characters
         *  <li>Nul ('\0')
         * </ul>
         * <li>Allow script URLs.
         * </ul>
         */
        public final static int ALL_BUT_WHITESPACE_LEGAL =
            ALL_OK & ~(ALL_WHITESPACE_OK | NUL_OK);
        /**
         * <ul>
         * <li>Allow characters used by encoded URLs.
         * <li>Deny script URLs.
         * </ul>
         */
        public final static int URL_LEGAL =
            NON_7_BIT_ASCII_OK | SQUOTE_OK | AMP_OK | PCT_OK;
        /**
         * <ul>
         * <li>Allow characters used by encoded URLs.
         * <li>Allow spaces.
         * <li>Deny script URLs.
         * </ul>
         */
        public final static int URL_AND_SPACE_LEGAL =
            URL_LEGAL | SPACE_OK;
        /**
         * <ul>
         * <li>Allow ampersand.
         * <li>Deny script URLs.
         * </ul>
         */
        public final static int AMP_LEGAL =
            AMP_OK;
        /**
         * <ul>
         * <li>Allow ampersand.
         * <li>Allow space.
         * <li>Deny script URLs.
         * </ul>
         */
        public final static int AMP_AND_SPACE_LEGAL =
            AMP_OK | SPACE_OK;
        /**
         * <ul>
         * <li>Allow space.
         * <li>Deny script URLs.
         * </ul>
         */
        public final static int SPACE_LEGAL =
            SPACE_OK;
        /**
         * <ul>
         * <li>Allow all but.
         * <ul>
         *  <li>Nul ('\0')
         *  <li>Angle brackets ('<', '>')
         * </ul>
         * <li>Deny script URLs.
         * </ul>
         */
        public final static int ALL_BUT_NUL_AND_ANGLE_BRACKETS_LEGAL =
            ALL_OK & ~(NUL_OK | LT_OK | GT_OK);

        /**
         *  Script URL definitions
         */

        private final static String JAVASCRIPT_PREFIX = "javascript:";

        private final static String VBSCRIPT_PREFIX = "vbscript:";

        private final static int MIN_SCRIPT_PREFIX_LENGTH = Math.min(
                JAVASCRIPT_PREFIX.length(), VBSCRIPT_PREFIX.length());

        /**
         * Construct a sanitizer. The parameters set the behavior of the
         * sanitizer.
         * @param flags some combination of the XXX_OK flags.
         */
        public IllegalCharacterValueSanitizer(
            int flags) {
            mFlags = flags;
        }
        /**
         * Sanitize a value.
         * <ol>
         * <li>If script URLs are not OK, they will be removed.
         * <li>If neither spaces nor other white space is OK, then
         * white space will be trimmed from the beginning and end of
         * the URL. (Just the actual white space characters are trimmed, not
         * other control codes.)
         * <li> Illegal characters will be replaced with
         * either ' ' or '_', depending on whether a space is itself a
         * legal character.
         * </ol>
         * @param value
         * @return the sanitized value
         */
        public String sanitize(String value) {
            if (value == null) {
                return null;
            }
            int length = value.length();
            if ((mFlags & SCRIPT_URL_OK) == 0) {
                if (length >= MIN_SCRIPT_PREFIX_LENGTH) {
                    String asLower = value.toLowerCase(Locale.ROOT);
                    if (asLower.startsWith(JAVASCRIPT_PREFIX)  ||
                        asLower.startsWith(VBSCRIPT_PREFIX)) {
                        return "";
                    }
                }
            }

            // If whitespace isn't OK, get rid of whitespace at beginning
            // and end of value.
            if ( (mFlags & ALL_WHITESPACE_OK) == 0) {
                value = trimWhitespace(value);
                // The length could have changed, so we need to correct
                // the length variable.
                length = value.length();
            }

            StringBuilder stringBuilder = new StringBuilder(length);
            for(int i = 0; i < length; i++) {
                char c = value.charAt(i);
                if (!characterIsLegal(c)) {
                    if ((mFlags & SPACE_OK) != 0) {
                        c = ' ';
                    }
                    else {
                        c = '_';
                    }
                }
                stringBuilder.append(c);
            }
            return stringBuilder.toString();
        }

        /**
         * Trim whitespace from the beginning and end of a string.
         * <p>
         * Note: can't use {@link String#trim} because {@link String#trim} has a
         * different definition of whitespace than we want.
         * @param value the string to trim
         * @return the trimmed string
         */
        private String trimWhitespace(String value) {
            int start = 0;
            int last = value.length() - 1;
            int end = last;
            while (start <= end && isWhitespace(value.charAt(start))) {
                start++;
            }
            while (end >= start && isWhitespace(value.charAt(end))) {
                end--;
            }
            if (start == 0 && end == last) {
                return value;
            }
            return value.substring(start, end + 1);
        }

        /**
         * Check if c is whitespace.
         * @param c character to test
         * @return true if c is a whitespace character
         */
        private boolean isWhitespace(char c) {
            switch(c) {
            case ' ':
            case '\t':
            case '\f':
            case '\n':
            case '\r':
            case 11: /* VT */
                return true;
            default:
                return false;
            }
        }

        /**
         * Check whether an individual character is legal. Uses the
         * flag bit-set passed into the constructor.
         * @param c
         * @return true if c is a legal character
         */
        private boolean characterIsLegal(char c) {
            switch(c) {
            case ' ' : return (mFlags & SPACE_OK) != 0;
            case '\t': case '\f': case '\n': case '\r': case 11: /* VT */
              return (mFlags & OTHER_WHITESPACE_OK) != 0;
            case '\"': return (mFlags & DQUOTE_OK) != 0;
            case '\'': return (mFlags & SQUOTE_OK) != 0;
            case '<' : return (mFlags & LT_OK) != 0;
            case '>' : return (mFlags & GT_OK) != 0;
            case '&' : return (mFlags & AMP_OK) != 0;
            case '%' : return (mFlags & PCT_OK) != 0;
            case '\0': return (mFlags & NUL_OK) != 0;
            default  : return (c >= 32 && c < 127) ||
                ((c >= 128) && ((mFlags & NON_7_BIT_ASCII_OK) != 0));
            }
        }
    }

    /**
     * Get the current value sanitizer used when processing
     * unregistered parameter values.
     * <p>
     * <b>Note:</b> The default unregistered parameter value sanitizer is
     * one that doesn't allow any special characters, similar to what
     * is returned by calling createAllIllegal.
     *
     * @return the current ValueSanitizer used to sanitize unregistered
     * parameter values.
     */
    public ValueSanitizer getUnregisteredParameterValueSanitizer() {
        return mUnregisteredParameterValueSanitizer;
    }

    /**
     * Set the value sanitizer used when processing unregistered
     * parameter values.
     * @param sanitizer set the ValueSanitizer used to sanitize unregistered
     * parameter values.
     */
    public void setUnregisteredParameterValueSanitizer(
            ValueSanitizer sanitizer) {
        mUnregisteredParameterValueSanitizer = sanitizer;
    }


    // Private fields for singleton sanitizers:

    private static final ValueSanitizer sAllIllegal =
        new IllegalCharacterValueSanitizer(
                IllegalCharacterValueSanitizer.ALL_ILLEGAL);

    private static final ValueSanitizer sAllButNulLegal =
        new IllegalCharacterValueSanitizer(
                IllegalCharacterValueSanitizer.ALL_BUT_NUL_LEGAL);

    private static final ValueSanitizer sAllButWhitespaceLegal =
        new IllegalCharacterValueSanitizer(
                IllegalCharacterValueSanitizer.ALL_BUT_WHITESPACE_LEGAL);

    private static final ValueSanitizer sURLLegal =
        new IllegalCharacterValueSanitizer(
                IllegalCharacterValueSanitizer.URL_LEGAL);

    private static final ValueSanitizer sUrlAndSpaceLegal =
        new IllegalCharacterValueSanitizer(
                IllegalCharacterValueSanitizer.URL_AND_SPACE_LEGAL);

    private static final ValueSanitizer sAmpLegal =
        new IllegalCharacterValueSanitizer(
                IllegalCharacterValueSanitizer.AMP_LEGAL);

    private static final ValueSanitizer sAmpAndSpaceLegal =
        new IllegalCharacterValueSanitizer(
                IllegalCharacterValueSanitizer.AMP_AND_SPACE_LEGAL);

    private static final ValueSanitizer sSpaceLegal =
        new IllegalCharacterValueSanitizer(
                IllegalCharacterValueSanitizer.SPACE_LEGAL);

    private static final ValueSanitizer sAllButNulAndAngleBracketsLegal =
        new IllegalCharacterValueSanitizer(
                IllegalCharacterValueSanitizer.ALL_BUT_NUL_AND_ANGLE_BRACKETS_LEGAL);

    /**
     * Return a value sanitizer that does not allow any special characters,
     * and also does not allow script URLs.
     * @return a value sanitizer
     */
    public static final ValueSanitizer getAllIllegal() {
        return sAllIllegal;
    }

    /**
     * Return a value sanitizer that allows everything except Nul ('\0')
     * characters. Script URLs are allowed.
     * @return a value sanitizer
     */
    public static final ValueSanitizer getAllButNulLegal() {
        return sAllButNulLegal;
    }
    /**
     * Return a value sanitizer that allows everything except Nul ('\0')
     * characters, space (' '), and other whitespace characters.
     * Script URLs are allowed.
     * @return a value sanitizer
     */
    public static final ValueSanitizer getAllButWhitespaceLegal() {
        return sAllButWhitespaceLegal;
    }
    /**
     * Return a value sanitizer that allows all the characters used by
     * encoded URLs. Does not allow script URLs.
     * @return a value sanitizer
     */
    public static final ValueSanitizer getUrlLegal() {
        return sURLLegal;
    }
    /**
     * Return a value sanitizer that allows all the characters used by
     * encoded URLs and allows spaces, which are not technically legal
     * in encoded URLs, but commonly appear anyway.
     * Does not allow script URLs.
     * @return a value sanitizer
     */
    public static final ValueSanitizer getUrlAndSpaceLegal() {
        return sUrlAndSpaceLegal;
    }
    /**
     * Return a value sanitizer that does not allow any special characters
     * except ampersand ('&'). Does not allow script URLs.
     * @return a value sanitizer
     */
    public static final ValueSanitizer getAmpLegal() {
        return sAmpLegal;
    }
    /**
     * Return a value sanitizer that does not allow any special characters
     * except ampersand ('&') and space (' '). Does not allow script URLs.
     * @return a value sanitizer
     */
    public static final ValueSanitizer getAmpAndSpaceLegal() {
        return sAmpAndSpaceLegal;
    }
    /**
     * Return a value sanitizer that does not allow any special characters
     * except space (' '). Does not allow script URLs.
     * @return a value sanitizer
     */
    public static final ValueSanitizer getSpaceLegal() {
        return sSpaceLegal;
    }
    /**
     * Return a value sanitizer that allows any special characters
     * except angle brackets ('<' and '>') and Nul ('\0').
     * Allows script URLs.
     * @return a value sanitizer
     */
    public static final ValueSanitizer getAllButNulAndAngleBracketsLegal() {
        return sAllButNulAndAngleBracketsLegal;
    }

    /**
     * Constructs a UrlQuerySanitizer.
     * <p>
     * Defaults:
     * <ul>
     * <li>unregistered parameters are not allowed.
     * <li>the last instance of a repeated parameter is preferred.
     * <li>The default value sanitizer is an AllIllegal value sanitizer.
     * <ul>
     */
    public UrlQuerySanitizer() {
    }

    /**
     * Constructs a UrlQuerySanitizer and parses a URL.
     * This constructor is provided for convenience when the
     * default parsing behavior is acceptable.
     * <p>
     * Because the URL is parsed before the constructor returns, there isn't
     * a chance to configure the sanitizer to change the parsing behavior.
     * <p>
     * <code>
     * UrlQuerySanitizer sanitizer = new UrlQuerySanitizer(myUrl);
     * String name = sanitizer.getValue("name");
     * </code>
     * <p>
     * Defaults:
     * <ul>
     * <li>unregistered parameters <em>are</em> allowed.
     * <li>the last instance of a repeated parameter is preferred.
     * <li>The default value sanitizer is an AllIllegal value sanitizer.
     * <ul>
     */
    public UrlQuerySanitizer(String url) {
        setAllowUnregisteredParamaters(true);
        parseUrl(url);
    }

    /**
     * Parse the query parameters out of an encoded URL.
     * Works by extracting the query portion from the URL and then
     * calling parseQuery(). If there is no query portion it is
     * treated as if the query portion is an empty string.
     * @param url the encoded URL to parse.
     */
    public void parseUrl(String url) {
        int queryIndex = url.indexOf('?');
        String query;
        if (queryIndex >= 0) {
            query = url.substring(queryIndex + 1);
        }
        else {
            query = "";
        }
        parseQuery(query);
    }

    /**
     * Parse a query. A query string is any number of parameter-value clauses
     * separated by any non-zero number of ampersands. A parameter-value clause
     * is a parameter followed by an equal sign, followed by a value. If the
     * equal sign is missing, the value is assumed to be the empty string.
     * @param query the query to parse.
     */
    public void parseQuery(String query) {
        clear();
        // Split by '&'
        StringTokenizer tokenizer = new StringTokenizer(query, "&");
        while(tokenizer.hasMoreElements()) {
            String attributeValuePair = tokenizer.nextToken();
            if (attributeValuePair.length() > 0) {
                int assignmentIndex = attributeValuePair.indexOf('=');
                if (assignmentIndex < 0) {
                    // No assignment found, treat as if empty value
                    parseEntry(attributeValuePair, "");
                }
                else {
                    parseEntry(attributeValuePair.substring(0, assignmentIndex),
                            attributeValuePair.substring(assignmentIndex + 1));
                }
            }
        }
    }

    /**
     * Get a set of all of the parameters found in the sanitized query.
     * <p>
     * Note: Do not modify this set. Treat it as a read-only set.
     * @return all the parameters found in the current query.
     */
    public Set<String> getParameterSet() {
        return mEntries.keySet();
    }

    /**
     * An array list of all of the parameter-value pairs in the sanitized
     * query, in the order they appeared in the query. May contain duplicate
     * parameters.
     * <p class="note"><b>Note:</b> Do not modify this list. Treat it as a read-only list.</p>
     */
    public List<ParameterValuePair> getParameterList() {
        return mEntriesList;
    }

    /**
     * Check if a parameter exists in the current sanitized query.
     * @param parameter the unencoded name of a parameter.
     * @return true if the parameter exists in the current sanitized queary.
     */
    public boolean hasParameter(String parameter) {
        return mEntries.containsKey(parameter);
    }

    /**
     * Get the value for a parameter in the current sanitized query.
     * Returns null if the parameter does not
     * exit.
     * @param parameter the unencoded name of a parameter.
     * @return the sanitized unencoded value of the parameter,
     * or null if the parameter does not exist.
     */
    public String getValue(String parameter) {
        return mEntries.get(parameter);
    }

    /**
     * Register a value sanitizer for a particular parameter. Can also be used
     * to replace or remove an already-set value sanitizer.
     * <p>
     * Registering a non-null value sanitizer for a particular parameter
     * makes that parameter a registered parameter.
     * @param parameter an unencoded parameter name
     * @param valueSanitizer the value sanitizer to use for a particular
     * parameter. May be null in order to unregister that parameter.
     * @see #getAllowUnregisteredParamaters()
     */
    public void registerParameter(String parameter,
            ValueSanitizer valueSanitizer) {
        if (valueSanitizer == null) {
            mSanitizers.remove(parameter);
        }
        mSanitizers.put(parameter, valueSanitizer);
    }

    /**
     * Register a value sanitizer for an array of parameters.
     * @param parameters An array of unencoded parameter names.
     * @param valueSanitizer
     * @see #registerParameter
     */
    public void registerParameters(String[] parameters,
            ValueSanitizer valueSanitizer) {
        int length = parameters.length;
        for(int i = 0; i < length; i++) {
            mSanitizers.put(parameters[i], valueSanitizer);
        }
    }

    /**
     * Set whether or not unregistered parameters are allowed. If they
     * are not allowed, then they will be dropped when a query is sanitized.
     * <p>
     * Defaults to false.
     * @param allowUnregisteredParamaters true to allow unregistered parameters.
     * @see #getAllowUnregisteredParamaters()
     */
    public void setAllowUnregisteredParamaters(
            boolean allowUnregisteredParamaters) {
        mAllowUnregisteredParamaters = allowUnregisteredParamaters;
    }

    /**
     * Get whether or not unregistered parameters are allowed. If not
     * allowed, they will be dropped when a query is parsed.
     * @return true if unregistered parameters are allowed.
     * @see #setAllowUnregisteredParamaters(boolean)
     */
    public boolean getAllowUnregisteredParamaters() {
        return mAllowUnregisteredParamaters;
    }

    /**
     * Set whether or not the first occurrence of a repeated parameter is
     * preferred. True means the first repeated parameter is preferred.
     * False means that the last repeated parameter is preferred.
     * <p>
     * The preferred parameter is the one that is returned when getParameter
     * is called.
     * <p>
     * defaults to false.
     * @param preferFirstRepeatedParameter True if the first repeated
     * parameter is preferred.
     * @see #getPreferFirstRepeatedParameter()
     */
    public void setPreferFirstRepeatedParameter(
            boolean preferFirstRepeatedParameter) {
        mPreferFirstRepeatedParameter = preferFirstRepeatedParameter;
    }

    /**
     * Get whether or not the first occurrence of a repeated parameter is
     * preferred.
     * @return true if the first occurrence of a repeated parameter is
     * preferred.
     * @see #setPreferFirstRepeatedParameter(boolean)
     */
    public boolean getPreferFirstRepeatedParameter() {
        return mPreferFirstRepeatedParameter;
    }

    /**
     * Parse an escaped parameter-value pair. The default implementation
     * unescapes both the parameter and the value, then looks up the
     * effective value sanitizer for the parameter and uses it to sanitize
     * the value. If all goes well then addSanitizedValue is called with
     * the unescaped parameter and the sanitized unescaped value.
     * @param parameter an escaped parameter
     * @param value an unsanitized escaped value
     */
    protected void parseEntry(String parameter, String value) {
        String unescapedParameter = unescape(parameter);
         ValueSanitizer valueSanitizer =
            getEffectiveValueSanitizer(unescapedParameter);

        if (valueSanitizer == null) {
            return;
        }
        String unescapedValue = unescape(value);
        String sanitizedValue = valueSanitizer.sanitize(unescapedValue);
        addSanitizedEntry(unescapedParameter, sanitizedValue);
    }

    /**
     * Record a sanitized parameter-value pair. Override if you want to
     * do additional filtering or validation.
     * @param parameter an unescaped parameter
     * @param value a sanitized unescaped value
     */
    protected void addSanitizedEntry(String parameter, String value) {
        mEntriesList.add(
                new ParameterValuePair(parameter, value));
        if (mPreferFirstRepeatedParameter) {
            if (mEntries.containsKey(parameter)) {
                return;
            }
        }
        mEntries.put(parameter, value);
    }

    /**
     * Get the value sanitizer for a parameter. Returns null if there
     * is no value sanitizer registered for the parameter.
     * @param parameter the unescaped parameter
     * @return the currently registered value sanitizer for this parameter.
     * @see #registerParameter(String, android.net.UrlQuerySanitizer.ValueSanitizer)
     */
    public ValueSanitizer getValueSanitizer(String parameter) {
        return mSanitizers.get(parameter);
    }

    /**
     * Get the effective value sanitizer for a parameter. Like getValueSanitizer,
     * except if there is no value sanitizer registered for a parameter, and
     * unregistered parameters are allowed, then the default value sanitizer is
     * returned.
     * @param parameter an unescaped parameter
     * @return the effective value sanitizer for a parameter.
     */
    public ValueSanitizer getEffectiveValueSanitizer(String parameter) {
        ValueSanitizer sanitizer = getValueSanitizer(parameter);
        if (sanitizer == null && mAllowUnregisteredParamaters) {
            sanitizer = getUnregisteredParameterValueSanitizer();
        }
        return sanitizer;
    }

    /**
     * Unescape an escaped string.
     * <ul>
     * <li>'+' characters are replaced by
     * ' ' characters.
     * <li>Valid "%xx" escape sequences are replaced by the
     * corresponding unescaped character.
     * <li>Invalid escape sequences such as %1z", are passed through unchanged.
     * <ol>
     * @param string the escaped string
     * @return the unescaped string.
     */
    private static final Pattern plusOrPercent = Pattern.compile("[+%]");
    public String unescape(String string) {
        final Matcher matcher = plusOrPercent.matcher(string);
        if (!matcher.find()) return string;
        final int firstEscape = matcher.start();

        int length = string.length();

        StringBuilder stringBuilder = new StringBuilder(length);
        stringBuilder.append(string.substring(0, firstEscape));
        for (int i = firstEscape; i < length; i++) {
            char c = string.charAt(i);
            if (c == '+') {
                c = ' ';
            } else if (c == '%' && i + 2 < length) {
                char c1 = string.charAt(i + 1);
                char c2 = string.charAt(i + 2);
                if (isHexDigit(c1) && isHexDigit(c2)) {
                    c = (char) (decodeHexDigit(c1) * 16 + decodeHexDigit(c2));
                    i += 2;
                }
            }
            stringBuilder.append(c);
        }
        return stringBuilder.toString();
    }

    /**
     * Test if a character is a hexidecimal digit. Both upper case and lower
     * case hex digits are allowed.
     * @param c the character to test
     * @return true if c is a hex digit.
     */
    protected boolean isHexDigit(char c) {
        return decodeHexDigit(c) >= 0;
    }

    /**
     * Convert a character that represents a hexidecimal digit into an integer.
     * If the character is not a hexidecimal digit, then -1 is returned.
     * Both upper case and lower case hex digits are allowed.
     * @param c the hexidecimal digit.
     * @return the integer value of the hexidecimal digit.
     */

    protected int decodeHexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        else if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        else if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        else {
            return -1;
        }
    }

    /**
     * Clear the existing entries. Called to get ready to parse a new
     * query string.
     */
    protected void clear() {
        mEntries.clear();
        mEntriesList.clear();
    }
}


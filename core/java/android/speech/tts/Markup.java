package android.speech.tts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class that provides markup to a synthesis request to control aspects of speech.
 * <p>
 * Markup itself is a feature agnostic data format; the {@link Utterance} class defines the currently
 * available set of features and should be used to construct instances of the Markup class.
 * </p>
 * <p>
 * A marked up sentence is a tree. Each node has a type, an optional plain text, a set of
 * parameters, and a list of children.
 * The <b>type</b> defines what it contains, e.g. "text", "date", "measure", etc. A Markup node
 * can be either a part of sentence (often a leaf node), or node altering some property of its
 * children (node with children). The top level node has to be of type "utterance" and its children
 * are synthesized in order.
 * The <b>plain text</b> is optional except for the top level node. If the synthesis engine does not
 * support Markup at all, it should use the plain text of the top level node. If an engine does not
 * recognize or support a node type, it will try to use the plain text of that node if provided. If
 * the plain text is null, it will synthesize its children in order.
 * <b>Parameters</b> are key-value pairs specific to each node type. In case of a date node the
 * parameters may be for example "month: 7" and "day: 10".
 * The <b>nested markups</b> are children and can for example be used to nest semiotic classes (a
 * measure may have a node of type "decimal" as its child) or to modify some property of its
 * children. See "plain text" on how they are processed if the parent of the children is unknown to
 * the engine.
 * <p>
 */
public final class Markup implements Parcelable {

    private String mType;
    private String mPlainText;

    private Bundle mParameters = new Bundle();
    private List<Markup> mNestedMarkups = new ArrayList<Markup>();

    private static final String TYPE = "type";
    private static final String PLAIN_TEXT = "plain_text";
    private static final String MARKUP = "markup";

    private static final String IDENTIFIER_REGEX = "([0-9a-z_]+)";
    private static final Pattern legalIdentifierPattern = Pattern.compile(IDENTIFIER_REGEX);

    /**
     * Constructs an empty markup.
     */
    public Markup() {}

    /**
     * Constructs a markup of the given type.
     */
    public Markup(String type) {
        setType(type);
    }

    /**
     * Returns the type of this node; can be null.
     */
    public String getType() {
        return mType;
    }

    /**
     * Sets the type of this node. can be null. May only contain [0-9a-z_].
     */
    public void setType(String type) {
        if (type != null) {
            Matcher matcher = legalIdentifierPattern.matcher(type);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Type cannot be empty and may only contain " +
                                                   "0-9, a-z and underscores.");
            }
        }
        mType = type;
    }

    /**
     * Returns this node's plain text; can be null.
     */
    public String getPlainText() {
        return mPlainText;
    }

    /**
     * Sets this nodes's plain text; can be null.
     */
    public void setPlainText(String plainText) {
        mPlainText = plainText;
    }

    /**
     * Adds or modifies a parameter.
     * @param key The key; may only contain [0-9a-z_] and cannot be "type" or "plain_text".
     * @param value The value.
     * @throws An {@link IllegalArgumentException} if the key is null or empty.
     * @return this
     */
    public Markup setParameter(String key, String value) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty.");
        }
        if (key.equals("type")) {
            throw new IllegalArgumentException("Key cannot be \"type\".");
        }
        if (key.equals("plain_text")) {
            throw new IllegalArgumentException("Key cannot be \"plain_text\".");
        }
        Matcher matcher = legalIdentifierPattern.matcher(key);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Key may only contain 0-9, a-z and underscores.");
        }

        if (value != null) {
            mParameters.putString(key, value);
        } else {
            removeParameter(key);
        }
        return this;
    }

    /**
     * Removes the parameter with the given key
     */
    public void removeParameter(String key) {
        mParameters.remove(key);
    }

    /**
     * Returns the value of the parameter.
     * @param key The parameter key.
     * @return The value of the parameter or null if the parameter is not set.
     */
    public String getParameter(String key) {
        return mParameters.getString(key);
    }

    /**
     * Returns the number of parameters that have been set.
     */
    public int parametersSize() {
        return mParameters.size();
    }

    /**
     * Appends a child to the list of children
     * @param markup The child.
     * @return This instance.
     * @throws {@link IllegalArgumentException} if markup is null.
     */
    public Markup addNestedMarkup(Markup markup) {
        if (markup == null) {
            throw new IllegalArgumentException("Nested markup cannot be null");
        }
        mNestedMarkups.add(markup);
        return this;
    }

    /**
     * Removes the given node from its children.
     * @param markup The child to remove.
     * @return True if this instance was modified by this operation, false otherwise.
     */
    public boolean removeNestedMarkup(Markup markup) {
        return mNestedMarkups.remove(markup);
    }

    /**
     * Returns the index'th child.
     * @param i The index of the child.
     * @return The child.
     * @throws {@link IndexOutOfBoundsException} if i < 0 or i >= nestedMarkupSize()
     */
    public Markup getNestedMarkup(int i) {
        return mNestedMarkups.get(i);
    }


    /**
     * Returns the number of children.
     */
    public int nestedMarkupSize() {
        return mNestedMarkups.size();
    }

    /**
     * Returns a string representation of this Markup instance. Can be deserialized back to a Markup
     * instance with markupFromString().
     */
    public String toString() {
        StringBuilder out = new StringBuilder();
        if (mType != null) {
            out.append(TYPE + ": \"" + mType + "\"");
        }
        if (mPlainText != null) {
            out.append(out.length() > 0 ? " " : "");
            out.append(PLAIN_TEXT + ": \"" + escapeQuotedString(mPlainText) + "\"");
        }
        // Sort the parameters alphabetically by key so we have a stable output.
        SortedMap<String, String> sortedMap = new TreeMap<String, String>();
        for (String key : mParameters.keySet()) {
            sortedMap.put(key, mParameters.getString(key));
        }
        for (Map.Entry<String, String> entry : sortedMap.entrySet()) {
            out.append(out.length() > 0 ? " " : "");
            out.append(entry.getKey() + ": \"" + escapeQuotedString(entry.getValue()) + "\"");
        }
        for (Markup m : mNestedMarkups) {
            out.append(out.length() > 0 ? " " : "");
            String nestedStr = m.toString();
            if (nestedStr.isEmpty()) {
                out.append(MARKUP + " {}");
            } else {
                out.append(MARKUP + " { " + m.toString() + " }");
            }
        }
        return out.toString();
    }

    /**
     * Escapes backslashes and double quotes in the plain text and parameter values before this
     * instance is written to a string.
     * @param str The string to escape.
     * @return The escaped string.
     */
    private static String escapeQuotedString(String str) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '"') {
                out.append("\\\"");
            } else if (str.charAt(i) == '\\') {
                out.append("\\\\");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The reverse of the escape method, returning plain text and parameter values to their original
     * form.
     * @param str An escaped string.
     * @return The unescaped string.
     */
    private static String unescapeQuotedString(String str) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\\') {
                i++;
                if (i >= str.length()) {
                    throw new IllegalArgumentException("Unterminated escape sequence in string: " +
                                                       str);
                }
                c = str.charAt(i);
                if (c == '\\') {
                    out.append("\\");
                } else if (c == '"') {
                    out.append("\"");
                } else {
                    throw new IllegalArgumentException("Unsupported escape sequence: \\" + c +
                                                       " in string " + str);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Returns true if the given string consists only of whitespace.
     * @param str The string to check.
     * @return True if the given string consists only of whitespace.
     */
    private static boolean isWhitespace(String str) {
        return Pattern.matches("\\s*", str);
    }

    /**
     * Parses the given string, and overrides the values of this instance with those contained
     * in the given string.
     * @param str The string to parse; can have superfluous whitespace.
     * @return An empty string on success, else the remainder of the string that could not be
     *     parsed.
     */
    private String fromReadableString(String str) {
        while (!isWhitespace(str)) {
            String newStr = matchValue(str);
            if (newStr == null) {
                newStr = matchMarkup(str);

                if (newStr == null) {
                    return str;
                }
            }
            str = newStr;
        }
        return "";
    }

    // Matches: key : "value"
    // where key is an identifier and value can contain escaped quotes
    // there may be superflouous whitespace
    // The value string may contain quotes and backslashes.
    private static final String OPTIONAL_WHITESPACE = "\\s*";
    private static final String VALUE_REGEX = "((\\\\.|[^\\\"])*)";
    private static final String KEY_VALUE_REGEX =
            "\\A" + OPTIONAL_WHITESPACE +                                         // start of string
            IDENTIFIER_REGEX + OPTIONAL_WHITESPACE + ":" + OPTIONAL_WHITESPACE +  // key:
            "\"" + VALUE_REGEX + "\"";                                            // "value"
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(KEY_VALUE_REGEX);

    /**
     * Tries to match a key-value pair at the start of the string. If found, add that as a parameter
     * of this instance.
     * @param str The string to parse.
     * @return The remainder of the string without the parsed key-value pair on success, else null.
     */
    private String matchValue(String str) {
        // Matches: key: "value"
        Matcher matcher = KEY_VALUE_PATTERN.matcher(str);
        if (!matcher.find()) {
            return null;
        }
        String key = matcher.group(1);
        String value = matcher.group(2);

        if (key == null || value == null) {
            return null;
        }
        String unescapedValue = unescapeQuotedString(value);
        if (key.equals(TYPE)) {
            this.mType = unescapedValue;
        } else if (key.equals(PLAIN_TEXT)) {
            this.mPlainText = unescapedValue;
        } else {
            setParameter(key, unescapedValue);
        }

        return str.substring(matcher.group(0).length());
    }

    // matches 'markup {'
    private static final Pattern OPEN_MARKUP_PATTERN =
            Pattern.compile("\\A" + OPTIONAL_WHITESPACE + MARKUP + OPTIONAL_WHITESPACE + "\\{");
    // matches '}'
    private static final Pattern CLOSE_MARKUP_PATTERN =
            Pattern.compile("\\A" + OPTIONAL_WHITESPACE + "\\}");

    /**
     * Tries to parse a Markup specification from the start of the string. If so, add that markup to
     * the list of nested Markup's of this instance.
     * @param str The string to parse.
     * @return The remainder of the string without the parsed Markup on success, else null.
     */
    private String matchMarkup(String str) {
        // find and strip "markup {"
        Matcher matcher = OPEN_MARKUP_PATTERN.matcher(str);

        if (!matcher.find()) {
            return null;
        }
        String strRemainder = str.substring(matcher.group(0).length());
        // parse and strip markup contents
        Markup nestedMarkup = new Markup();
        strRemainder = nestedMarkup.fromReadableString(strRemainder);

        // find and strip "}"
        Matcher matcherClose = CLOSE_MARKUP_PATTERN.matcher(strRemainder);
        if (!matcherClose.find()) {
            return null;
        }
        strRemainder = strRemainder.substring(matcherClose.group(0).length());

        // Everything parsed, add markup
        this.addNestedMarkup(nestedMarkup);

        // Return remainder
        return strRemainder;
    }

    /**
     * Returns a Markup instance from the string representation generated by toString().
     * @param string The string representation generated by toString().
     * @return The new Markup instance.
     * @throws {@link IllegalArgumentException} if the input cannot be correctly parsed.
     */
    public static Markup markupFromString(String string) throws IllegalArgumentException {
        Markup m = new Markup();
        if (m.fromReadableString(string).isEmpty()) {
            return m;
        } else {
            throw new IllegalArgumentException("Cannot parse input to Markup");
        }
    }

    /**
     * Compares the specified object with this Markup for equality.
     * @return True if the given object is a Markup instance with the same type, plain text,
     * parameters and the nested markups are also equal to each other and in the same order.
     */
    @Override
    public boolean equals(Object o) {
        if ( this == o ) return true;
        if ( !(o instanceof Markup) ) return false;
        Markup m = (Markup) o;

        if (nestedMarkupSize() != this.nestedMarkupSize()) {
            return false;
        }

        if (!(mType == null ? m.mType == null : mType.equals(m.mType))) {
            return false;
        }
        if (!(mPlainText == null ? m.mPlainText == null : mPlainText.equals(m.mPlainText))) {
            return false;
        }
        if (!equalBundles(mParameters, m.mParameters)) {
            return false;
        }

        for (int i = 0; i < this.nestedMarkupSize(); i++) {
            if (!mNestedMarkups.get(i).equals(m.mNestedMarkups.get(i))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks if two bundles are equal to each other. Used by equals(o).
     */
    private boolean equalBundles(Bundle one, Bundle two) {
        if (one == null || two == null) {
            return false;
        }

        if(one.size() != two.size()) {
            return false;
        }

        Set<String> valuesOne = one.keySet();
        for(String key : valuesOne) {
            Object valueOne = one.get(key);
            Object valueTwo = two.get(key);
            if (valueOne instanceof Bundle && valueTwo instanceof Bundle &&
                !equalBundles((Bundle) valueOne, (Bundle) valueTwo)) {
                return false;
            } else if (valueOne == null) {
                if (valueTwo != null || !two.containsKey(key)) {
                    return false;
                }
            } else if(!valueOne.equals(valueTwo)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns an unmodifiable list of the children.
     * @return An unmodifiable list of children that throws an {@link UnsupportedOperationException}
     *     if an attempt is made to modify it
     */
    public List<Markup> getNestedMarkups() {
        return Collections.unmodifiableList(mNestedMarkups);
    }

    /**
     * @hide
     */
    public Markup(Parcel in) {
        mType = in.readString();
        mPlainText = in.readString();
        mParameters = in.readBundle();
        in.readList(mNestedMarkups, Markup.class.getClassLoader());
    }

    /**
     * Creates a deep copy of the given markup.
     */
    public Markup(Markup markup) {
        mType = markup.mType;
        mPlainText = markup.mPlainText;
        mParameters = markup.mParameters;
        for (Markup nested : markup.getNestedMarkups()) {
            addNestedMarkup(new Markup(nested));
        }
    }

    /**
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mType);
        dest.writeString(mPlainText);
        dest.writeBundle(mParameters);
        dest.writeList(mNestedMarkups);
    }

    /**
     * @hide
     */
    public static final Parcelable.Creator CREATOR = new Parcelable.Creator() {
        public Markup createFromParcel(Parcel in) {
            return new Markup(in);
        }

        public Markup[] newArray(int size) {
            return new Markup[size];
        }
    };
}


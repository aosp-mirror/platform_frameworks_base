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

package android.content;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PatternMatcher;
import android.util.AndroidException;
import android.util.Log;
import android.util.Printer;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

/**
 * Structured description of Intent values to be matched.  An IntentFilter can
 * match against actions, categories, and data (either via its type, scheme,
 * and/or path) in an Intent.  It also includes a "priority" value which is
 * used to order multiple matching filters.
 *
 * <p>IntentFilter objects are often created in XML as part of a package's
 * {@link android.R.styleable#AndroidManifest AndroidManifest.xml} file,
 * using {@link android.R.styleable#AndroidManifestIntentFilter intent-filter}
 * tags.
 *
 * <p>There are three Intent characteristics you can filter on: the
 * <em>action</em>, <em>data</em>, and <em>categories</em>.  For each of these
 * characteristics you can provide
 * multiple possible matching values (via {@link #addAction},
 * {@link #addDataType}, {@link #addDataScheme} {@link #addDataAuthority},
 * {@link #addDataPath}, and {@link #addCategory}, respectively).
 * For actions, the field
 * will not be tested if no values have been given (treating it as a wildcard);
 * if no data characteristics are specified, however, then the filter will
 * only match intents that contain no data.
 *
 * <p>The data characteristic is
 * itself divided into three attributes: type, scheme, authority, and path.
 * Any that are
 * specified must match the contents of the Intent.  If you specify a scheme
 * but no type, only Intent that does not have a type (such as mailto:) will
 * match; a content: URI will never match because they always have a MIME type
 * that is supplied by their content provider.  Specifying a type with no scheme
 * has somewhat special meaning: it will match either an Intent with no URI
 * field, or an Intent with a content: or file: URI.  If you specify neither,
 * then only an Intent with no data or type will match.  To specify an authority,
 * you must also specify one or more schemes that it is associated with.
 * To specify a path, you also must specify both one or more authorities and
 * one or more schemes it is associated with.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about how to create and resolve intents, read the
 * <a href="{@docRoot}guide/topics/intents/intents-filters.html">Intents and Intent Filters</a>
 * developer guide.</p>
 * </div>
 *
 * <h3>Filter Rules</h3>
 * <p>A match is based on the following rules.  Note that
 * for an IntentFilter to match an Intent, three conditions must hold:
 * the <strong>action</strong> and <strong>category</strong> must match, and
 * the data (both the <strong>data type</strong> and
 * <strong>data scheme+authority+path</strong> if specified) must match.
 *
 * <p><strong>Action</strong> matches if any of the given values match the
 * Intent action, <em>or</em> if no actions were specified in the filter.
 *
 * <p><strong>Data Type</strong> matches if any of the given values match the
 * Intent type.  The Intent
 * type is determined by calling {@link Intent#resolveType}.  A wildcard can be
 * used for the MIME sub-type, in both the Intent and IntentFilter, so that the
 * type "audio/*" will match "audio/mpeg", "audio/aiff", "audio/*", etc.
 * <em>Note that MIME type matching here is <b>case sensitive</b>, unlike
 * formal RFC MIME types!</em>  You should thus always use lower case letters
 * for your MIME types.
 *
 * <p><strong>Data Scheme</strong> matches if any of the given values match the
 * Intent data's scheme.
 * The Intent scheme is determined by calling {@link Intent#getData}
 * and {@link android.net.Uri#getScheme} on that URI.
 * <em>Note that scheme matching here is <b>case sensitive</b>, unlike
 * formal RFC schemes!</em>  You should thus always use lower case letters
 * for your schemes.
 *
 * <p><strong>Data Authority</strong> matches if any of the given values match
 * the Intent's data authority <em>and</em> one of the data scheme's in the filter
 * has matched the Intent, <em>or</em> no authories were supplied in the filter.
 * The Intent authority is determined by calling
 * {@link Intent#getData} and {@link android.net.Uri#getAuthority} on that URI.
 * <em>Note that authority matching here is <b>case sensitive</b>, unlike
 * formal RFC host names!</em>  You should thus always use lower case letters
 * for your authority.
 * 
 * <p><strong>Data Path</strong> matches if any of the given values match the
 * Intent's data path <em>and</em> both a scheme and authority in the filter
 * has matched against the Intent, <em>or</em> no paths were supplied in the
 * filter.  The Intent authority is determined by calling
 * {@link Intent#getData} and {@link android.net.Uri#getPath} on that URI.
 *
 * <p><strong>Categories</strong> match if <em>all</em> of the categories in
 * the Intent match categories given in the filter.  Extra categories in the
 * filter that are not in the Intent will not cause the match to fail.  Note
 * that unlike the action, an IntentFilter with no categories
 * will only match an Intent that does not have any categories.
 */
public class IntentFilter implements Parcelable {
    private static final String SGLOB_STR = "sglob";
    private static final String PREFIX_STR = "prefix";
    private static final String LITERAL_STR = "literal";
    private static final String PATH_STR = "path";
    private static final String PORT_STR = "port";
    private static final String HOST_STR = "host";
    private static final String AUTH_STR = "auth";
    private static final String SCHEME_STR = "scheme";
    private static final String TYPE_STR = "type";
    private static final String CAT_STR = "cat";
    private static final String NAME_STR = "name";
    private static final String ACTION_STR = "action";

    /**
     * The filter {@link #setPriority} value at which system high-priority
     * receivers are placed; that is, receivers that should execute before
     * application code. Applications should never use filters with this or
     * higher priorities.
     *
     * @see #setPriority
     */
    public static final int SYSTEM_HIGH_PRIORITY = 1000;

    /**
     * The filter {@link #setPriority} value at which system low-priority
     * receivers are placed; that is, receivers that should execute after
     * application code. Applications should never use filters with this or
     * lower priorities.
     *
     * @see #setPriority
     */
    public static final int SYSTEM_LOW_PRIORITY = -1000;

    /**
     * The part of a match constant that describes the category of match
     * that occurred.  May be either {@link #MATCH_CATEGORY_EMPTY},
     * {@link #MATCH_CATEGORY_SCHEME}, {@link #MATCH_CATEGORY_HOST},
     * {@link #MATCH_CATEGORY_PORT},
     * {@link #MATCH_CATEGORY_PATH}, or {@link #MATCH_CATEGORY_TYPE}.  Higher
     * values indicate a better match.
     */
    public static final int MATCH_CATEGORY_MASK = 0xfff0000;

    /**
     * The part of a match constant that applies a quality adjustment to the
     * basic category of match.  The value {@link #MATCH_ADJUSTMENT_NORMAL}
     * is no adjustment; higher numbers than that improve the quality, while
     * lower numbers reduce it.
     */
    public static final int MATCH_ADJUSTMENT_MASK = 0x000ffff;

    /**
     * Quality adjustment applied to the category of match that signifies
     * the default, base value; higher numbers improve the quality while
     * lower numbers reduce it.
     */
    public static final int MATCH_ADJUSTMENT_NORMAL = 0x8000;

    /**
     * The filter matched an intent that had no data specified.
     */
    public static final int MATCH_CATEGORY_EMPTY = 0x0100000;
    /**
     * The filter matched an intent with the same data URI scheme.
     */
    public static final int MATCH_CATEGORY_SCHEME = 0x0200000;
    /**
     * The filter matched an intent with the same data URI scheme and
     * authority host.
     */
    public static final int MATCH_CATEGORY_HOST = 0x0300000;
    /**
     * The filter matched an intent with the same data URI scheme and
     * authority host and port.
     */
    public static final int MATCH_CATEGORY_PORT = 0x0400000;
    /**
     * The filter matched an intent with the same data URI scheme,
     * authority, and path.
     */
    public static final int MATCH_CATEGORY_PATH = 0x0500000;
    /**
     * The filter matched an intent with the same data MIME type.
     */
    public static final int MATCH_CATEGORY_TYPE = 0x0600000;

    /**
     * The filter didn't match due to different MIME types.
     */
    public static final int NO_MATCH_TYPE = -1;
    /**
     * The filter didn't match due to different data URIs.
     */
    public static final int NO_MATCH_DATA = -2;
    /**
     * The filter didn't match due to different actions.
     */
    public static final int NO_MATCH_ACTION = -3;
    /**
     * The filter didn't match because it required one or more categories
     * that were not in the Intent.
     */
    public static final int NO_MATCH_CATEGORY = -4;

    private int mPriority;
    private final ArrayList<String> mActions;
    private ArrayList<String> mCategories = null;
    private ArrayList<String> mDataSchemes = null;
    private ArrayList<AuthorityEntry> mDataAuthorities = null;
    private ArrayList<PatternMatcher> mDataPaths = null;
    private ArrayList<String> mDataTypes = null;
    private boolean mHasPartialTypes = false;

    // These functions are the start of more optimized code for managing
    // the string sets...  not yet implemented.

    private static int findStringInSet(String[] set, String string,
            int[] lengths, int lenPos) {
        if (set == null) return -1;
        final int N = lengths[lenPos];
        for (int i=0; i<N; i++) {
            if (set[i].equals(string)) return i;
        }
        return -1;
    }

    private static String[] addStringToSet(String[] set, String string,
            int[] lengths, int lenPos) {
        if (findStringInSet(set, string, lengths, lenPos) >= 0) return set;
        if (set == null) {
            set = new String[2];
            set[0] = string;
            lengths[lenPos] = 1;
            return set;
        }
        final int N = lengths[lenPos];
        if (N < set.length) {
            set[N] = string;
            lengths[lenPos] = N+1;
            return set;
        }

        String[] newSet = new String[(N*3)/2 + 2];
        System.arraycopy(set, 0, newSet, 0, N);
        set = newSet;
        set[N] = string;
        lengths[lenPos] = N+1;
        return set;
    }

    private static String[] removeStringFromSet(String[] set, String string,
            int[] lengths, int lenPos) {
        int pos = findStringInSet(set, string, lengths, lenPos);
        if (pos < 0) return set;
        final int N = lengths[lenPos];
        if (N > (set.length/4)) {
            int copyLen = N-(pos+1);
            if (copyLen > 0) {
                System.arraycopy(set, pos+1, set, pos, copyLen);
            }
            set[N-1] = null;
            lengths[lenPos] = N-1;
            return set;
        }

        String[] newSet = new String[set.length/3];
        if (pos > 0) System.arraycopy(set, 0, newSet, 0, pos);
        if ((pos+1) < N) System.arraycopy(set, pos+1, newSet, pos, N-(pos+1));
        return newSet;
    }

    /**
     * This exception is thrown when a given MIME type does not have a valid
     * syntax.
     */
    public static class MalformedMimeTypeException extends AndroidException {
        public MalformedMimeTypeException() {
        }

        public MalformedMimeTypeException(String name) {
            super(name);
        }
    };

    /**
     * Create a new IntentFilter instance with a specified action and MIME
     * type, where you know the MIME type is correctly formatted.  This catches
     * the {@link MalformedMimeTypeException} exception that the constructor
     * can call and turns it into a runtime exception.
     *
     * @param action The action to match, i.e. Intent.ACTION_VIEW.
     * @param dataType The type to match, i.e. "vnd.android.cursor.dir/person".
     *
     * @return A new IntentFilter for the given action and type.
     *
     * @see #IntentFilter(String, String)
     */
    public static IntentFilter create(String action, String dataType) {
        try {
            return new IntentFilter(action, dataType);
        } catch (MalformedMimeTypeException e) {
            throw new RuntimeException("Bad MIME type", e);
        }
    }

    /**
     * New empty IntentFilter.
     */
    public IntentFilter() {
        mPriority = 0;
        mActions = new ArrayList<String>();
    }

    /**
     * New IntentFilter that matches a single action with no data.  If
     * no data characteristics are subsequently specified, then the
     * filter will only match intents that contain no data.
     *
     * @param action The action to match, i.e. Intent.ACTION_MAIN.
     */
    public IntentFilter(String action) {
        mPriority = 0;
        mActions = new ArrayList<String>();
        addAction(action);
    }

    /**
     * New IntentFilter that matches a single action and data type.
     *
     * <p><em>Note: MIME type matching in the Android framework is
     * case-sensitive, unlike formal RFC MIME types.  As a result,
     * you should always write your MIME types with lower case letters,
     * and any MIME types you receive from outside of Android should be
     * converted to lower case before supplying them here.</em></p>
     *
     * <p>Throws {@link MalformedMimeTypeException} if the given MIME type is
     * not syntactically correct.
     *
     * @param action The action to match, i.e. Intent.ACTION_VIEW.
     * @param dataType The type to match, i.e. "vnd.android.cursor.dir/person".
     *
     */
    public IntentFilter(String action, String dataType)
        throws MalformedMimeTypeException {
        mPriority = 0;
        mActions = new ArrayList<String>();
        addAction(action);
        addDataType(dataType);
    }

    /**
     * New IntentFilter containing a copy of an existing filter.
     *
     * @param o The original filter to copy.
     */
    public IntentFilter(IntentFilter o) {
        mPriority = o.mPriority;
        mActions = new ArrayList<String>(o.mActions);
        if (o.mCategories != null) {
            mCategories = new ArrayList<String>(o.mCategories);
        }
        if (o.mDataTypes != null) {
            mDataTypes = new ArrayList<String>(o.mDataTypes);
        }
        if (o.mDataSchemes != null) {
            mDataSchemes = new ArrayList<String>(o.mDataSchemes);
        }
        if (o.mDataAuthorities != null) {
            mDataAuthorities = new ArrayList<AuthorityEntry>(o.mDataAuthorities);
        }
        if (o.mDataPaths != null) {
            mDataPaths = new ArrayList<PatternMatcher>(o.mDataPaths);
        }
        mHasPartialTypes = o.mHasPartialTypes;
    }

    /**
     * Modify priority of this filter.  The default priority is 0. Positive
     * values will be before the default, lower values will be after it.
     * Applications must use a value that is larger than
     * {@link #SYSTEM_LOW_PRIORITY} and smaller than
     * {@link #SYSTEM_HIGH_PRIORITY} .
     *
     * @param priority The new priority value.
     *
     * @see #getPriority
     * @see #SYSTEM_LOW_PRIORITY
     * @see #SYSTEM_HIGH_PRIORITY
     */
    public final void setPriority(int priority) {
        mPriority = priority;
    }

    /**
     * Return the priority of this filter.
     *
     * @return The priority of the filter.
     *
     * @see #setPriority
     */
    public final int getPriority() {
        return mPriority;
    }

    /**
     * Add a new Intent action to match against.  If any actions are included
     * in the filter, then an Intent's action must be one of those values for
     * it to match.  If no actions are included, the Intent action is ignored.
     *
     * @param action Name of the action to match, i.e. Intent.ACTION_VIEW.
     */
    public final void addAction(String action) {
        if (!mActions.contains(action)) {
            mActions.add(action.intern());
        }
    }

    /**
     * Return the number of actions in the filter.
     */
    public final int countActions() {
        return mActions.size();
    }

    /**
     * Return an action in the filter.
     */
    public final String getAction(int index) {
        return mActions.get(index);
    }

    /**
     * Is the given action included in the filter?  Note that if the filter
     * does not include any actions, false will <em>always</em> be returned.
     *
     * @param action The action to look for.
     *
     * @return True if the action is explicitly mentioned in the filter.
     */
    public final boolean hasAction(String action) {
        return action != null && mActions.contains(action);
    }

    /**
     * Match this filter against an Intent's action.  If the filter does not
     * specify any actions, the match will always fail.
     *
     * @param action The desired action to look for.
     *
     * @return True if the action is listed in the filter.
     */
    public final boolean matchAction(String action) {
        return hasAction(action);
    }

    /**
     * Return an iterator over the filter's actions.  If there are no actions,
     * returns null.
     */
    public final Iterator<String> actionsIterator() {
        return mActions != null ? mActions.iterator() : null;
    }

    /**
     * Add a new Intent data type to match against.  If any types are
     * included in the filter, then an Intent's data must be <em>either</em>
     * one of these types <em>or</em> a matching scheme.  If no data types
     * are included, then an Intent will only match if it specifies no data.
     *
     * <p><em>Note: MIME type matching in the Android framework is
     * case-sensitive, unlike formal RFC MIME types.  As a result,
     * you should always write your MIME types with lower case letters,
     * and any MIME types you receive from outside of Android should be
     * converted to lower case before supplying them here.</em></p>
     *
     * <p>Throws {@link MalformedMimeTypeException} if the given MIME type is
     * not syntactically correct.
     *
     * @param type Name of the data type to match, i.e. "vnd.android.cursor.dir/person".
     *
     * @see #matchData
     */
    public final void addDataType(String type)
        throws MalformedMimeTypeException {
        final int slashpos = type.indexOf('/');
        final int typelen = type.length();
        if (slashpos > 0 && typelen >= slashpos+2) {
            if (mDataTypes == null) mDataTypes = new ArrayList<String>();
            if (typelen == slashpos+2 && type.charAt(slashpos+1) == '*') {
                String str = type.substring(0, slashpos);
                if (!mDataTypes.contains(str)) {
                    mDataTypes.add(str.intern());
                }
                mHasPartialTypes = true;
            } else {
                if (!mDataTypes.contains(type)) {
                    mDataTypes.add(type.intern());
                }
            }
            return;
        }

        throw new MalformedMimeTypeException(type);
    }

    /**
     * Is the given data type included in the filter?  Note that if the filter
     * does not include any type, false will <em>always</em> be returned.
     *
     * @param type The data type to look for.
     *
     * @return True if the type is explicitly mentioned in the filter.
     */
    public final boolean hasDataType(String type) {
        return mDataTypes != null && findMimeType(type);
    }

    /**
     * Return the number of data types in the filter.
     */
    public final int countDataTypes() {
        return mDataTypes != null ? mDataTypes.size() : 0;
    }

    /**
     * Return a data type in the filter.
     */
    public final String getDataType(int index) {
        return mDataTypes.get(index);
    }

    /**
     * Return an iterator over the filter's data types.
     */
    public final Iterator<String> typesIterator() {
        return mDataTypes != null ? mDataTypes.iterator() : null;
    }

    /**
     * Add a new Intent data scheme to match against.  If any schemes are
     * included in the filter, then an Intent's data must be <em>either</em>
     * one of these schemes <em>or</em> a matching data type.  If no schemes
     * are included, then an Intent will match only if it includes no data.
     *
     * <p><em>Note: scheme matching in the Android framework is
     * case-sensitive, unlike formal RFC schemes.  As a result,
     * you should always write your schemes with lower case letters,
     * and any schemes you receive from outside of Android should be
     * converted to lower case before supplying them here.</em></p>
     *
     * @param scheme Name of the scheme to match, i.e. "http".
     *
     * @see #matchData
     */
    public final void addDataScheme(String scheme) {
        if (mDataSchemes == null) mDataSchemes = new ArrayList<String>();
        if (!mDataSchemes.contains(scheme)) {
            mDataSchemes.add(scheme.intern());
        }
    }

    /**
     * Return the number of data schemes in the filter.
     */
    public final int countDataSchemes() {
        return mDataSchemes != null ? mDataSchemes.size() : 0;
    }

    /**
     * Return a data scheme in the filter.
     */
    public final String getDataScheme(int index) {
        return mDataSchemes.get(index);
    }

    /**
     * Is the given data scheme included in the filter?  Note that if the
     * filter does not include any scheme, false will <em>always</em> be
     * returned.
     *
     * @param scheme The data scheme to look for.
     *
     * @return True if the scheme is explicitly mentioned in the filter.
     */
    public final boolean hasDataScheme(String scheme) {
        return mDataSchemes != null && mDataSchemes.contains(scheme);
    }

    /**
     * Return an iterator over the filter's data schemes.
     */
    public final Iterator<String> schemesIterator() {
        return mDataSchemes != null ? mDataSchemes.iterator() : null;
    }

    /**
     * This is an entry for a single authority in the Iterator returned by
     * {@link #authoritiesIterator()}.
     */
    public final static class AuthorityEntry {
        private final String mOrigHost;
        private final String mHost;
        private final boolean mWild;
        private final int mPort;

        public AuthorityEntry(String host, String port) {
            mOrigHost = host;
            mWild = host.length() > 0 && host.charAt(0) == '*';
            mHost = mWild ? host.substring(1).intern() : host;
            mPort = port != null ? Integer.parseInt(port) : -1;
        }

        AuthorityEntry(Parcel src) {
            mOrigHost = src.readString();
            mHost = src.readString();
            mWild = src.readInt() != 0;
            mPort = src.readInt();
        }

        void writeToParcel(Parcel dest) {
            dest.writeString(mOrigHost);
            dest.writeString(mHost);
            dest.writeInt(mWild ? 1 : 0);
            dest.writeInt(mPort);
        }

        public String getHost() {
            return mOrigHost;
        }

        public int getPort() {
            return mPort;
        }

        /**
         * Determine whether this AuthorityEntry matches the given data Uri.
         * <em>Note that this comparison is case-sensitive, unlike formal
         * RFC host names.  You thus should always normalize to lower-case.</em>
         * 
         * @param data The Uri to match.
         * @return Returns either {@link IntentFilter#NO_MATCH_DATA},
         * {@link IntentFilter#MATCH_CATEGORY_PORT}, or
         * {@link IntentFilter#MATCH_CATEGORY_HOST}.
         */
        public int match(Uri data) {
            String host = data.getHost();
            if (host == null) {
                return NO_MATCH_DATA;
            }
            if (false) Log.v("IntentFilter",
                    "Match host " + host + ": " + mHost);
            if (mWild) {
                if (host.length() < mHost.length()) {
                    return NO_MATCH_DATA;
                }
                host = host.substring(host.length()-mHost.length());
            }
            if (host.compareToIgnoreCase(mHost) != 0) {
                return NO_MATCH_DATA;
            }
            if (mPort >= 0) {
                if (mPort != data.getPort()) {
                    return NO_MATCH_DATA;
                }
                return MATCH_CATEGORY_PORT;
            }
            return MATCH_CATEGORY_HOST;
        }
    };

    /**
     * Add a new Intent data authority to match against.  The filter must
     * include one or more schemes (via {@link #addDataScheme}) for the
     * authority to be considered.  If any authorities are
     * included in the filter, then an Intent's data must match one of
     * them.  If no authorities are included, then only the scheme must match.
     *
     * <p><em>Note: host name in the Android framework is
     * case-sensitive, unlike formal RFC host names.  As a result,
     * you should always write your host names with lower case letters,
     * and any host names you receive from outside of Android should be
     * converted to lower case before supplying them here.</em></p>
     *
     * @param host The host part of the authority to match.  May start with a
     *             single '*' to wildcard the front of the host name.
     * @param port Optional port part of the authority to match.  If null, any
     *             port is allowed.
     *
     * @see #matchData
     * @see #addDataScheme
     */
    public final void addDataAuthority(String host, String port) {
        if (mDataAuthorities == null) mDataAuthorities =
                new ArrayList<AuthorityEntry>();
        if (port != null) port = port.intern();
        mDataAuthorities.add(new AuthorityEntry(host.intern(), port));
    }

    /**
     * Return the number of data authorities in the filter.
     */
    public final int countDataAuthorities() {
        return mDataAuthorities != null ? mDataAuthorities.size() : 0;
    }

    /**
     * Return a data authority in the filter.
     */
    public final AuthorityEntry getDataAuthority(int index) {
        return mDataAuthorities.get(index);
    }

    /**
     * Is the given data authority included in the filter?  Note that if the
     * filter does not include any authorities, false will <em>always</em> be
     * returned.
     *
     * @param data The data whose authority is being looked for.
     *
     * @return Returns true if the data string matches an authority listed in the
     *         filter.
     */
    public final boolean hasDataAuthority(Uri data) {
        return matchDataAuthority(data) >= 0;
    }

    /**
     * Return an iterator over the filter's data authorities.
     */
    public final Iterator<AuthorityEntry> authoritiesIterator() {
        return mDataAuthorities != null ? mDataAuthorities.iterator() : null;
    }

    /**
     * Add a new Intent data path to match against.  The filter must
     * include one or more schemes (via {@link #addDataScheme}) <em>and</em>
     * one or more authorities (via {@link #addDataAuthority}) for the
     * path to be considered.  If any paths are
     * included in the filter, then an Intent's data must match one of
     * them.  If no paths are included, then only the scheme/authority must
     * match.
     *
     * <p>The path given here can either be a literal that must directly
     * match or match against a prefix, or it can be a simple globbing pattern.
     * If the latter, you can use '*' anywhere in the pattern to match zero
     * or more instances of the previous character, '.' as a wildcard to match
     * any character, and '\' to escape the next character.
     *
     * @param path Either a raw string that must exactly match the file
     * path, or a simple pattern, depending on <var>type</var>.
     * @param type Determines how <var>path</var> will be compared to
     * determine a match: either {@link PatternMatcher#PATTERN_LITERAL},
     * {@link PatternMatcher#PATTERN_PREFIX}, or
     * {@link PatternMatcher#PATTERN_SIMPLE_GLOB}.
     *
     * @see #matchData
     * @see #addDataScheme
     * @see #addDataAuthority
     */
    public final void addDataPath(String path, int type) {
        if (mDataPaths == null) mDataPaths = new ArrayList<PatternMatcher>();
        mDataPaths.add(new PatternMatcher(path.intern(), type));
    }

    /**
     * Return the number of data paths in the filter.
     */
    public final int countDataPaths() {
        return mDataPaths != null ? mDataPaths.size() : 0;
    }

    /**
     * Return a data path in the filter.
     */
    public final PatternMatcher getDataPath(int index) {
        return mDataPaths.get(index);
    }

    /**
     * Is the given data path included in the filter?  Note that if the
     * filter does not include any paths, false will <em>always</em> be
     * returned.
     *
     * @param data The data path to look for.  This is without the scheme
     *             prefix.
     *
     * @return True if the data string matches a path listed in the
     *         filter.
     */
    public final boolean hasDataPath(String data) {
        if (mDataPaths == null) {
            return false;
        }
        final int numDataPaths = mDataPaths.size();
        for (int i = 0; i < numDataPaths; i++) {
            final PatternMatcher pe = mDataPaths.get(i);
            if (pe.match(data)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return an iterator over the filter's data paths.
     */
    public final Iterator<PatternMatcher> pathsIterator() {
        return mDataPaths != null ? mDataPaths.iterator() : null;
    }

    /**
     * Match this intent filter against the given Intent data.  This ignores
     * the data scheme -- unlike {@link #matchData}, the authority will match
     * regardless of whether there is a matching scheme.
     *
     * @param data The data whose authority is being looked for.
     *
     * @return Returns either {@link #MATCH_CATEGORY_HOST},
     * {@link #MATCH_CATEGORY_PORT}, {@link #NO_MATCH_DATA}.
     */
    public final int matchDataAuthority(Uri data) {
        if (mDataAuthorities == null) {
            return NO_MATCH_DATA;
        }
        final int numDataAuthorities = mDataAuthorities.size();
        for (int i = 0; i < numDataAuthorities; i++) {
            final AuthorityEntry ae = mDataAuthorities.get(i);
            int match = ae.match(data);
            if (match >= 0) {
                return match;
            }
        }
        return NO_MATCH_DATA;
    }

    /**
     * Match this filter against an Intent's data (type, scheme and path). If
     * the filter does not specify any types and does not specify any
     * schemes/paths, the match will only succeed if the intent does not
     * also specify a type or data.
     *
     * <p>Be aware that to match against an authority, you must also specify a base
     * scheme the authority is in.  To match against a data path, both a scheme
     * and authority must be specified.  If the filter does not specify any
     * types or schemes that it matches against, it is considered to be empty
     * (any authority or data path given is ignored, as if it were empty as
     * well).
     *
     * <p><em>Note: MIME type, Uri scheme, and host name matching in the
     * Android framework is case-sensitive, unlike the formal RFC definitions.
     * As a result, you should always write these elements with lower case letters,
     * and normalize any MIME types or Uris you receive from
     * outside of Android to ensure these elements are lower case before
     * supplying them here.</em></p>
     *
     * @param type The desired data type to look for, as returned by
     *             Intent.resolveType().
     * @param scheme The desired data scheme to look for, as returned by
     *               Intent.getScheme().
     * @param data The full data string to match against, as supplied in
     *             Intent.data.
     *
     * @return Returns either a valid match constant (a combination of
     * {@link #MATCH_CATEGORY_MASK} and {@link #MATCH_ADJUSTMENT_MASK}),
     * or one of the error codes {@link #NO_MATCH_TYPE} if the type didn't match
     * or {@link #NO_MATCH_DATA} if the scheme/path didn't match.
     *
     * @see #match
     */
    public final int matchData(String type, String scheme, Uri data) {
        final ArrayList<String> types = mDataTypes;
        final ArrayList<String> schemes = mDataSchemes;
        final ArrayList<AuthorityEntry> authorities = mDataAuthorities;
        final ArrayList<PatternMatcher> paths = mDataPaths;

        int match = MATCH_CATEGORY_EMPTY;

        if (types == null && schemes == null) {
            return ((type == null && data == null)
                ? (MATCH_CATEGORY_EMPTY+MATCH_ADJUSTMENT_NORMAL) : NO_MATCH_DATA);
        }

        if (schemes != null) {
            if (schemes.contains(scheme != null ? scheme : "")) {
                match = MATCH_CATEGORY_SCHEME;
            } else {
                return NO_MATCH_DATA;
            }

            if (authorities != null) {
                int authMatch = matchDataAuthority(data);
                if (authMatch >= 0) {
                    if (paths == null) {
                        match = authMatch;
                    } else if (hasDataPath(data.getPath())) {
                        match = MATCH_CATEGORY_PATH;
                    } else {
                        return NO_MATCH_DATA;
                    }
                } else {
                    return NO_MATCH_DATA;
                }
            }
        } else {
            // Special case: match either an Intent with no data URI,
            // or with a scheme: URI.  This is to give a convenience for
            // the common case where you want to deal with data in a
            // content provider, which is done by type, and we don't want
            // to force everyone to say they handle content: or file: URIs.
            if (scheme != null && !"".equals(scheme)
                    && !"content".equals(scheme)
                    && !"file".equals(scheme)) {
                return NO_MATCH_DATA;
            }
        }

        if (types != null) {
            if (findMimeType(type)) {
                match = MATCH_CATEGORY_TYPE;
            } else {
                return NO_MATCH_TYPE;
            }
        } else {
            // If no MIME types are specified, then we will only match against
            // an Intent that does not have a MIME type.
            if (type != null) {
                return NO_MATCH_TYPE;
            }
        }

        return match + MATCH_ADJUSTMENT_NORMAL;
    }

    /**
     * Add a new Intent category to match against.  The semantics of
     * categories is the opposite of actions -- an Intent includes the
     * categories that it requires, all of which must be included in the
     * filter in order to match.  In other words, adding a category to the
     * filter has no impact on matching unless that category is specified in
     * the intent.
     *
     * @param category Name of category to match, i.e. Intent.CATEGORY_EMBED.
     */
    public final void addCategory(String category) {
        if (mCategories == null) mCategories = new ArrayList<String>();
        if (!mCategories.contains(category)) {
            mCategories.add(category.intern());
        }
    }

    /**
     * Return the number of categories in the filter.
     */
    public final int countCategories() {
        return mCategories != null ? mCategories.size() : 0;
    }

    /**
     * Return a category in the filter.
     */
    public final String getCategory(int index) {
        return mCategories.get(index);
    }

    /**
     * Is the given category included in the filter?
     *
     * @param category The category that the filter supports.
     *
     * @return True if the category is explicitly mentioned in the filter.
     */
    public final boolean hasCategory(String category) {
        return mCategories != null && mCategories.contains(category);
    }

    /**
     * Return an iterator over the filter's categories.
     *
     * @return Iterator if this filter has categories or {@code null} if none.
     */
    public final Iterator<String> categoriesIterator() {
        return mCategories != null ? mCategories.iterator() : null;
    }

    /**
     * Match this filter against an Intent's categories.  Each category in
     * the Intent must be specified by the filter; if any are not in the
     * filter, the match fails.
     *
     * @param categories The categories included in the intent, as returned by
     *                   Intent.getCategories().
     *
     * @return If all categories match (success), null; else the name of the
     *         first category that didn't match.
     */
    public final String matchCategories(Set<String> categories) {
        if (categories == null) {
            return null;
        }

        Iterator<String> it = categories.iterator();

        if (mCategories == null) {
            return it.hasNext() ? it.next() : null;
        }

        while (it.hasNext()) {
            final String category = it.next();
            if (!mCategories.contains(category)) {
                return category;
            }
        }

        return null;
    }

    /**
     * Test whether this filter matches the given <var>intent</var>.
     *
     * @param intent The Intent to compare against.
     * @param resolve If true, the intent's type will be resolved by calling
     *                Intent.resolveType(); otherwise a simple match against
     *                Intent.type will be performed.
     * @param logTag Tag to use in debugging messages.
     *
     * @return Returns either a valid match constant (a combination of
     * {@link #MATCH_CATEGORY_MASK} and {@link #MATCH_ADJUSTMENT_MASK}),
     * or one of the error codes {@link #NO_MATCH_TYPE} if the type didn't match,
     * {@link #NO_MATCH_DATA} if the scheme/path didn't match,
     * {@link #NO_MATCH_ACTION if the action didn't match, or
     * {@link #NO_MATCH_CATEGORY} if one or more categories didn't match.
     *
     * @return How well the filter matches.  Negative if it doesn't match,
     *         zero or positive positive value if it does with a higher
     *         value representing a better match.
     *
     * @see #match(String, String, String, android.net.Uri , Set, String)
     */
    public final int match(ContentResolver resolver, Intent intent,
            boolean resolve, String logTag) {
        String type = resolve ? intent.resolveType(resolver) : intent.getType();
        return match(intent.getAction(), type, intent.getScheme(),
                     intent.getData(), intent.getCategories(), logTag);
    }

    /**
     * Test whether this filter matches the given intent data.  A match is
     * only successful if the actions and categories in the Intent match
     * against the filter, as described in {@link IntentFilter}; in that case,
     * the match result returned will be as per {@link #matchData}.
     *
     * @param action The intent action to match against (Intent.getAction).
     * @param type The intent type to match against (Intent.resolveType()).
     * @param scheme The data scheme to match against (Intent.getScheme()).
     * @param data The data URI to match against (Intent.getData()).
     * @param categories The categories to match against
     *                   (Intent.getCategories()).
     * @param logTag Tag to use in debugging messages.
     *
     * @return Returns either a valid match constant (a combination of
     * {@link #MATCH_CATEGORY_MASK} and {@link #MATCH_ADJUSTMENT_MASK}),
     * or one of the error codes {@link #NO_MATCH_TYPE} if the type didn't match,
     * {@link #NO_MATCH_DATA} if the scheme/path didn't match,
     * {@link #NO_MATCH_ACTION if the action didn't match, or
     * {@link #NO_MATCH_CATEGORY} if one or more categories didn't match.
     *
     * @see #matchData
     * @see Intent#getAction
     * @see Intent#resolveType
     * @see Intent#getScheme
     * @see Intent#getData
     * @see Intent#getCategories
     */
    public final int match(String action, String type, String scheme,
            Uri data, Set<String> categories, String logTag) {
        if (action != null && !matchAction(action)) {
            if (false) Log.v(
                logTag, "No matching action " + action + " for " + this);
            return NO_MATCH_ACTION;
        }

        int dataMatch = matchData(type, scheme, data);
        if (dataMatch < 0) {
            if (false) {
                if (dataMatch == NO_MATCH_TYPE) {
                    Log.v(logTag, "No matching type " + type
                          + " for " + this);
                }
                if (dataMatch == NO_MATCH_DATA) {
                    Log.v(logTag, "No matching scheme/path " + data
                          + " for " + this);
                }
            }
            return dataMatch;
        }

        String categoryMismatch = matchCategories(categories);
        if (categoryMismatch != null) {
            if (false) {
                Log.v(logTag, "No matching category " + categoryMismatch + " for " + this);
            }
            return NO_MATCH_CATEGORY;
        }

        // It would be nice to treat container activities as more
        // important than ones that can be embedded, but this is not the way...
        if (false) {
            if (categories != null) {
                dataMatch -= mCategories.size() - categories.size();
            }
        }

        return dataMatch;
    }

    /**
     * Write the contents of the IntentFilter as an XML stream.
     */
    public void writeToXml(XmlSerializer serializer) throws IOException {
        int N = countActions();
        for (int i=0; i<N; i++) {
            serializer.startTag(null, ACTION_STR);
            serializer.attribute(null, NAME_STR, mActions.get(i));
            serializer.endTag(null, ACTION_STR);
        }
        N = countCategories();
        for (int i=0; i<N; i++) {
            serializer.startTag(null, CAT_STR);
            serializer.attribute(null, NAME_STR, mCategories.get(i));
            serializer.endTag(null, CAT_STR);
        }
        N = countDataTypes();
        for (int i=0; i<N; i++) {
            serializer.startTag(null, TYPE_STR);
            String type = mDataTypes.get(i);
            if (type.indexOf('/') < 0) type = type + "/*";
            serializer.attribute(null, NAME_STR, type);
            serializer.endTag(null, TYPE_STR);
        }
        N = countDataSchemes();
        for (int i=0; i<N; i++) {
            serializer.startTag(null, SCHEME_STR);
            serializer.attribute(null, NAME_STR, mDataSchemes.get(i));
            serializer.endTag(null, SCHEME_STR);
        }
        N = countDataAuthorities();
        for (int i=0; i<N; i++) {
            serializer.startTag(null, AUTH_STR);
            AuthorityEntry ae = mDataAuthorities.get(i);
            serializer.attribute(null, HOST_STR, ae.getHost());
            if (ae.getPort() >= 0) {
                serializer.attribute(null, PORT_STR, Integer.toString(ae.getPort()));
            }
            serializer.endTag(null, AUTH_STR);
        }
        N = countDataPaths();
        for (int i=0; i<N; i++) {
            serializer.startTag(null, PATH_STR);
            PatternMatcher pe = mDataPaths.get(i);
            switch (pe.getType()) {
                case PatternMatcher.PATTERN_LITERAL:
                    serializer.attribute(null, LITERAL_STR, pe.getPath());
                    break;
                case PatternMatcher.PATTERN_PREFIX:
                    serializer.attribute(null, PREFIX_STR, pe.getPath());
                    break;
                case PatternMatcher.PATTERN_SIMPLE_GLOB:
                    serializer.attribute(null, SGLOB_STR, pe.getPath());
                    break;
            }
            serializer.endTag(null, PATH_STR);
        }
    }

    public void readFromXml(XmlPullParser parser) throws XmlPullParserException,
            IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(ACTION_STR)) {
                String name = parser.getAttributeValue(null, NAME_STR);
                if (name != null) {
                    addAction(name);
                }
            } else if (tagName.equals(CAT_STR)) {
                String name = parser.getAttributeValue(null, NAME_STR);
                if (name != null) {
                    addCategory(name);
                }
            } else if (tagName.equals(TYPE_STR)) {
                String name = parser.getAttributeValue(null, NAME_STR);
                if (name != null) {
                    try {
                        addDataType(name);
                    } catch (MalformedMimeTypeException e) {
                    }
                }
            } else if (tagName.equals(SCHEME_STR)) {
                String name = parser.getAttributeValue(null, NAME_STR);
                if (name != null) {
                    addDataScheme(name);
                }
            } else if (tagName.equals(AUTH_STR)) {
                String host = parser.getAttributeValue(null, HOST_STR);
                String port = parser.getAttributeValue(null, PORT_STR);
                if (host != null) {
                    addDataAuthority(host, port);
                }
            } else if (tagName.equals(PATH_STR)) {
                String path = parser.getAttributeValue(null, LITERAL_STR);
                if (path != null) {
                    addDataPath(path, PatternMatcher.PATTERN_LITERAL);
                } else if ((path=parser.getAttributeValue(null, PREFIX_STR)) != null) {
                    addDataPath(path, PatternMatcher.PATTERN_PREFIX);
                } else if ((path=parser.getAttributeValue(null, SGLOB_STR)) != null) {
                    addDataPath(path, PatternMatcher.PATTERN_SIMPLE_GLOB);
                }
            } else {
                Log.w("IntentFilter", "Unknown tag parsing IntentFilter: " + tagName);
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    public void dump(Printer du, String prefix) {
        StringBuilder sb = new StringBuilder(256);
        if (mActions.size() > 0) {
            Iterator<String> it = mActions.iterator();
            while (it.hasNext()) {
                sb.setLength(0);
                sb.append(prefix); sb.append("Action: \"");
                        sb.append(it.next()); sb.append("\"");
                du.println(sb.toString());
            }
        }
        if (mCategories != null) {
            Iterator<String> it = mCategories.iterator();
            while (it.hasNext()) {
                sb.setLength(0);
                sb.append(prefix); sb.append("Category: \"");
                        sb.append(it.next()); sb.append("\"");
                du.println(sb.toString());
            }
        }
        if (mDataSchemes != null) {
            Iterator<String> it = mDataSchemes.iterator();
            while (it.hasNext()) {
                sb.setLength(0);
                sb.append(prefix); sb.append("Scheme: \"");
                        sb.append(it.next()); sb.append("\"");
                du.println(sb.toString());
            }
        }
        if (mDataAuthorities != null) {
            Iterator<AuthorityEntry> it = mDataAuthorities.iterator();
            while (it.hasNext()) {
                AuthorityEntry ae = it.next();
                sb.setLength(0);
                sb.append(prefix); sb.append("Authority: \"");
                        sb.append(ae.mHost); sb.append("\": ");
                        sb.append(ae.mPort);
                if (ae.mWild) sb.append(" WILD");
                du.println(sb.toString());
            }
        }
        if (mDataPaths != null) {
            Iterator<PatternMatcher> it = mDataPaths.iterator();
            while (it.hasNext()) {
                PatternMatcher pe = it.next();
                sb.setLength(0);
                sb.append(prefix); sb.append("Path: \"");
                        sb.append(pe); sb.append("\"");
                du.println(sb.toString());
            }
        }
        if (mDataTypes != null) {
            Iterator<String> it = mDataTypes.iterator();
            while (it.hasNext()) {
                sb.setLength(0);
                sb.append(prefix); sb.append("Type: \"");
                        sb.append(it.next()); sb.append("\"");
                du.println(sb.toString());
            }
        }
        if (mPriority != 0 || mHasPartialTypes) {
            sb.setLength(0);
            sb.append(prefix); sb.append("mPriority="); sb.append(mPriority);
                    sb.append(", mHasPartialTypes="); sb.append(mHasPartialTypes);
            du.println(sb.toString());
        }
    }

    public static final Parcelable.Creator<IntentFilter> CREATOR
            = new Parcelable.Creator<IntentFilter>() {
        public IntentFilter createFromParcel(Parcel source) {
            return new IntentFilter(source);
        }

        public IntentFilter[] newArray(int size) {
            return new IntentFilter[size];
        }
    };

    public final int describeContents() {
        return 0;
    }

    public final void writeToParcel(Parcel dest, int flags) {
        dest.writeStringList(mActions);
        if (mCategories != null) {
            dest.writeInt(1);
            dest.writeStringList(mCategories);
        } else {
            dest.writeInt(0);
        }
        if (mDataSchemes != null) {
            dest.writeInt(1);
            dest.writeStringList(mDataSchemes);
        } else {
            dest.writeInt(0);
        }
        if (mDataTypes != null) {
            dest.writeInt(1);
            dest.writeStringList(mDataTypes);
        } else {
            dest.writeInt(0);
        }
        if (mDataAuthorities != null) {
            final int N = mDataAuthorities.size();
            dest.writeInt(N);
            for (int i=0; i<N; i++) {
                mDataAuthorities.get(i).writeToParcel(dest);
            }
        } else {
            dest.writeInt(0);
        }
        if (mDataPaths != null) {
            final int N = mDataPaths.size();
            dest.writeInt(N);
            for (int i=0; i<N; i++) {
                mDataPaths.get(i).writeToParcel(dest, 0);
            }
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(mPriority);
        dest.writeInt(mHasPartialTypes ? 1 : 0);
    }

    /**
     * For debugging -- perform a check on the filter, return true if it passed
     * or false if it failed.
     *
     * {@hide}
     */
    public boolean debugCheck() {
        return true;

        // This code looks for intent filters that do not specify data.
        /*
        if (mActions != null && mActions.size() == 1
                && mActions.contains(Intent.ACTION_MAIN)) {
            return true;
        }

        if (mDataTypes == null && mDataSchemes == null) {
            Log.w("IntentFilter", "QUESTIONABLE INTENT FILTER:");
            dump(Log.WARN, "IntentFilter", "  ");
            return false;
        }

        return true;
        */
    }

    private IntentFilter(Parcel source) {
        mActions = new ArrayList<String>();
        source.readStringList(mActions);
        if (source.readInt() != 0) {
            mCategories = new ArrayList<String>();
            source.readStringList(mCategories);
        }
        if (source.readInt() != 0) {
            mDataSchemes = new ArrayList<String>();
            source.readStringList(mDataSchemes);
        }
        if (source.readInt() != 0) {
            mDataTypes = new ArrayList<String>();
            source.readStringList(mDataTypes);
        }
        int N = source.readInt();
        if (N > 0) {
            mDataAuthorities = new ArrayList<AuthorityEntry>();
            for (int i=0; i<N; i++) {
                mDataAuthorities.add(new AuthorityEntry(source));
            }
        }
        N = source.readInt();
        if (N > 0) {
            mDataPaths = new ArrayList<PatternMatcher>();
            for (int i=0; i<N; i++) {
                mDataPaths.add(new PatternMatcher(source));
            }
        }
        mPriority = source.readInt();
        mHasPartialTypes = source.readInt() > 0;
    }

    private final boolean findMimeType(String type) {
        final ArrayList<String> t = mDataTypes;

        if (type == null) {
            return false;
        }

        if (t.contains(type)) {
            return true;
        }

        // Deal with an Intent wanting to match every type in the IntentFilter.
        final int typeLength = type.length();
        if (typeLength == 3 && type.equals("*/*")) {
            return !t.isEmpty();
        }

        // Deal with this IntentFilter wanting to match every Intent type.
        if (mHasPartialTypes && t.contains("*")) {
            return true;
        }

        final int slashpos = type.indexOf('/');
        if (slashpos > 0) {
            if (mHasPartialTypes && t.contains(type.substring(0, slashpos))) {
                return true;
            }
            if (typeLength == slashpos+2 && type.charAt(slashpos+1) == '*') {
                // Need to look through all types for one that matches
                // our base...
                final int numTypes = t.size();
                for (int i = 0; i < numTypes; i++) {
                    final String v = t.get(i);
                    if (type.regionMatches(0, v, 0, slashpos+1)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}

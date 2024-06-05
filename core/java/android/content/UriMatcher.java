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

import android.compat.annotation.UnsupportedAppUsage;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
Utility class to aid in matching URIs in content providers.

<p>To use this class, build up a tree of <code>UriMatcher</code> objects.
For example:
<pre>
    private static final int PEOPLE = 1;
    private static final int PEOPLE_ID = 2;
    private static final int PEOPLE_PHONES = 3;
    private static final int PEOPLE_PHONES_ID = 4;
    private static final int PEOPLE_CONTACTMETHODS = 7;
    private static final int PEOPLE_CONTACTMETHODS_ID = 8;

    private static final int DELETED_PEOPLE = 20;

    private static final int PHONES = 9;
    private static final int PHONES_ID = 10;
    private static final int PHONES_FILTER = 14;

    private static final int CONTACTMETHODS = 18;
    private static final int CONTACTMETHODS_ID = 19;

    private static final int CALLS = 11;
    private static final int CALLS_ID = 12;
    private static final int CALLS_FILTER = 15;

    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static
    {
        sURIMatcher.addURI("contacts", "people", PEOPLE);
        sURIMatcher.addURI("contacts", "people/#", PEOPLE_ID);
        sURIMatcher.addURI("contacts", "people/#/phones", PEOPLE_PHONES);
        sURIMatcher.addURI("contacts", "people/#/phones/#", PEOPLE_PHONES_ID);
        sURIMatcher.addURI("contacts", "people/#/contact_methods", PEOPLE_CONTACTMETHODS);
        sURIMatcher.addURI("contacts", "people/#/contact_methods/#", PEOPLE_CONTACTMETHODS_ID);
        sURIMatcher.addURI("contacts", "deleted_people", DELETED_PEOPLE);
        sURIMatcher.addURI("contacts", "phones", PHONES);
        sURIMatcher.addURI("contacts", "phones/filter/*", PHONES_FILTER);
        sURIMatcher.addURI("contacts", "phones/#", PHONES_ID);
        sURIMatcher.addURI("contacts", "contact_methods", CONTACTMETHODS);
        sURIMatcher.addURI("contacts", "contact_methods/#", CONTACTMETHODS_ID);
        sURIMatcher.addURI("call_log", "calls", CALLS);
        sURIMatcher.addURI("call_log", "calls/filter/*", CALLS_FILTER);
        sURIMatcher.addURI("call_log", "calls/#", CALLS_ID);
    }
</pre>
<p>Starting from API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, paths can start
 with a leading slash.  For example:
<pre>
        sURIMatcher.addURI("contacts", "/people", PEOPLE);
</pre>
<p>Then when you need to match against a URI, call {@link #match}, providing
the URL that you have been given.  You can use the result to build a query,
return a type, insert or delete a row, or whatever you need, without duplicating
all of the if-else logic that you would otherwise need.  For example:
<pre>
    public String getType(Uri url)
    {
        int match = sURIMatcher.match(url);
        switch (match)
        {
            case PEOPLE:
                return "vnd.android.cursor.dir/person";
            case PEOPLE_ID:
                return "vnd.android.cursor.item/person";
... snip ...
                return "vnd.android.cursor.dir/snail-mail";
            case PEOPLE_ADDRESS_ID:
                return "vnd.android.cursor.item/snail-mail";
            default:
                return null;
        }
    }
</pre>
instead of:
<pre>
    public String getType(Uri url)
    {
        List<String> pathSegments = url.getPathSegments();
        if (pathSegments.size() >= 2) {
            if ("people".equals(pathSegments.get(1))) {
                if (pathSegments.size() == 2) {
                    return "vnd.android.cursor.dir/person";
                } else if (pathSegments.size() == 3) {
                    return "vnd.android.cursor.item/person";
... snip ...
                    return "vnd.android.cursor.dir/snail-mail";
                } else if (pathSegments.size() == 3) {
                    return "vnd.android.cursor.item/snail-mail";
                }
            }
        }
        return null;
    }
</pre>
*/
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class UriMatcher
{
    public static final int NO_MATCH = -1;
    /**
     * Creates the root node of the URI tree.
     *
     * @param code the code to match for the root URI
     */
    public UriMatcher(int code)
    {
        mCode = code;
        mWhich = -1;
        mChildren = new ArrayList<UriMatcher>();
        mText = null;
    }

    private UriMatcher(int which, String text)
    {
        mCode = NO_MATCH;
        mWhich = which;
        mChildren = new ArrayList<UriMatcher>();
        mText = text;
    }

    /**
     * Add a URI to match, and the code to return when this URI is
     * matched. URI nodes may be exact match string, the token "*"
     * that matches any text, or the token "#" that matches only
     * numbers.
     * <p>
     * Starting from API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2},
     * this method will accept a leading slash in the path.
     *
     * @param authority the authority to match
     * @param path the path to match. * may be used as a wild card for
     * any text, and # may be used as a wild card for numbers.
     * @param code the code that is returned when a URI is matched
     * against the given components. Must be positive.
     */
    public void addURI(String authority, String path, int code)
    {
        if (code < 0) {
            throw new IllegalArgumentException("code " + code + " is invalid: it must be positive");
        }

        String[] tokens = null;
        if (path != null) {
            String newPath = path;
            // Strip leading slash if present.
            if (path.length() > 1 && path.charAt(0) == '/') {
                newPath = path.substring(1);
            }
            tokens = newPath.split("/");
        }

        int numTokens = tokens != null ? tokens.length : 0;
        UriMatcher node = this;
        for (int i = -1; i < numTokens; i++) {
            String token = i < 0 ? authority : tokens[i];
            ArrayList<UriMatcher> children = node.mChildren;
            int numChildren = children.size();
            UriMatcher child;
            int j;
            for (j = 0; j < numChildren; j++) {
                child = children.get(j);
                if (token.equals(child.mText)) {
                    node = child;
                    break;
                }
            }
            if (j == numChildren) {
                // Child not found, create it
                child = createChild(token);
                node.mChildren.add(child);
                node = child;
            }
        }
        node.mCode = code;
    }

    private static UriMatcher createChild(String token) {
        switch (token) {
            case "#":
                return new UriMatcher(NUMBER, "#");
            case "*":
                return new UriMatcher(TEXT, "*");
            default:
                return new UriMatcher(EXACT, token);
        }
    }

    /**
     * Try to match against the path in a url.
     *
     * @param uri       The url whose path we will match against.
     *
     * @return  The code for the matched node (added using addURI),
     * or -1 if there is no matched node.
     */
    public int match(Uri uri)
    {
        final List<String> pathSegments = uri.getPathSegments();
        final int li = pathSegments.size();

        UriMatcher node = this;

        if (li == 0 && uri.getAuthority() == null) {
            return this.mCode;
        }

        for (int i=-1; i<li; i++) {
            String u = i < 0 ? uri.getAuthority() : pathSegments.get(i);
            ArrayList<UriMatcher> list = node.mChildren;
            if (list == null) {
                break;
            }
            node = null;
            int lj = list.size();
            for (int j=0; j<lj; j++) {
                UriMatcher n = list.get(j);
          which_switch:
                switch (n.mWhich) {
                    case EXACT:
                        if (n.mText.equals(u)) {
                            node = n;
                        }
                        break;
                    case NUMBER:
                        int lk = u.length();
                        for (int k=0; k<lk; k++) {
                            char c = u.charAt(k);
                            if (c < '0' || c > '9') {
                                break which_switch;
                            }
                        }
                        node = n;
                        break;
                    case TEXT:
                        node = n;
                        break;
                }
                if (node != null) {
                    break;
                }
            }
            if (node == null) {
                return NO_MATCH;
            }
        }

        return node.mCode;
    }

    private static final int EXACT = 0;
    private static final int NUMBER = 1;
    private static final int TEXT = 2;

    private int mCode;
    private final int mWhich;
    @UnsupportedAppUsage
    private final String mText;
    @UnsupportedAppUsage
    private ArrayList<UriMatcher> mChildren;
}

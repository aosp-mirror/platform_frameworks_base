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

import android.content.UriMatcher;
import android.net.Uri;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

public class UriMatcherTest extends TestCase {

    static final int ROOT = 0;
    static final int PEOPLE = 1;
    static final int PEOPLE_ID = 2;
    static final int PEOPLE_PHONES = 3;
    static final int PEOPLE_PHONES_ID = 4;
    static final int PEOPLE_ADDRESSES = 5;
    static final int PEOPLE_ADDRESSES_ID = 6;
    static final int PEOPLE_CONTACTMETH = 7;
    static final int PEOPLE_CONTACTMETH_ID = 8;
    static final int CALLS = 9;
    static final int CALLS_ID = 10;
    static final int CALLERID = 11;
    static final int CALLERID_TEXT = 12;
    static final int FILTERRECENT = 13;
    static final int ANOTHER_PATH_SEGMENT = 13;

    @SmallTest
    public void testContentUris() {
        UriMatcher matcher = new UriMatcher(ROOT);
        matcher.addURI("people", null, PEOPLE);
        matcher.addURI("people", "#", PEOPLE_ID);
        matcher.addURI("people", "#/phones", PEOPLE_PHONES);
        matcher.addURI("people", "#/phones/blah", PEOPLE_PHONES_ID);
        matcher.addURI("people", "#/phones/#", PEOPLE_PHONES_ID);
        matcher.addURI("people", "#/addresses", PEOPLE_ADDRESSES);
        matcher.addURI("people", "#/addresses/#", PEOPLE_ADDRESSES_ID);
        matcher.addURI("people", "#/contact-methods", PEOPLE_CONTACTMETH);
        matcher.addURI("people", "#/contact-methods/#", PEOPLE_CONTACTMETH_ID);
        matcher.addURI("calls", null, CALLS);
        matcher.addURI("calls", "#", CALLS_ID);
        matcher.addURI("caller-id", null, CALLERID);
        matcher.addURI("caller-id", "*", CALLERID_TEXT);
        matcher.addURI("filter-recent", null, FILTERRECENT);
        matcher.addURI("auth", "another/path/segment", ANOTHER_PATH_SEGMENT);
        checkAll(matcher);
    }

    @SmallTest
    public void testContentUrisWithLeadingSlash() {
        UriMatcher matcher = new UriMatcher(ROOT);
        matcher.addURI("people", null, PEOPLE);
        matcher.addURI("people", "/#", PEOPLE_ID);
        matcher.addURI("people", "/#/phones", PEOPLE_PHONES);
        matcher.addURI("people", "/#/phones/blah", PEOPLE_PHONES_ID);
        matcher.addURI("people", "/#/phones/#", PEOPLE_PHONES_ID);
        matcher.addURI("people", "/#/addresses", PEOPLE_ADDRESSES);
        matcher.addURI("people", "/#/addresses/#", PEOPLE_ADDRESSES_ID);
        matcher.addURI("people", "/#/contact-methods", PEOPLE_CONTACTMETH);
        matcher.addURI("people", "/#/contact-methods/#", PEOPLE_CONTACTMETH_ID);
        matcher.addURI("calls", null, CALLS);
        matcher.addURI("calls", "/#", CALLS_ID);
        matcher.addURI("caller-id", null, CALLERID);
        matcher.addURI("caller-id", "/*", CALLERID_TEXT);
        matcher.addURI("filter-recent", null, FILTERRECENT);
        matcher.addURI("auth", "/another/path/segment", ANOTHER_PATH_SEGMENT);
        checkAll(matcher);
    }

    @SmallTest
    public void testContentUrisWithLeadingSlashAndOnlySlash() {
        UriMatcher matcher = new UriMatcher(ROOT);
        matcher.addURI("people", "/", PEOPLE);
        matcher.addURI("people", "/#", PEOPLE_ID);
        matcher.addURI("people", "/#/phones", PEOPLE_PHONES);
        matcher.addURI("people", "/#/phones/blah", PEOPLE_PHONES_ID);
        matcher.addURI("people", "/#/phones/#", PEOPLE_PHONES_ID);
        matcher.addURI("people", "/#/addresses", PEOPLE_ADDRESSES);
        matcher.addURI("people", "/#/addresses/#", PEOPLE_ADDRESSES_ID);
        matcher.addURI("people", "/#/contact-methods", PEOPLE_CONTACTMETH);
        matcher.addURI("people", "/#/contact-methods/#", PEOPLE_CONTACTMETH_ID);
        matcher.addURI("calls", "/", CALLS);
        matcher.addURI("calls", "/#", CALLS_ID);
        matcher.addURI("caller-id", "/", CALLERID);
        matcher.addURI("caller-id", "/*", CALLERID_TEXT);
        matcher.addURI("filter-recent", null, FILTERRECENT);
        matcher.addURI("auth", "/another/path/segment", ANOTHER_PATH_SEGMENT);
        checkAll(matcher);
    }

    private void checkAll(UriMatcher matcher) {
        check("content://asdf", UriMatcher.NO_MATCH, matcher);
        check("content://people", PEOPLE, matcher);
        check("content://people/", PEOPLE, matcher);
        check("content://people/1", PEOPLE_ID, matcher);
        check("content://people/asdf", UriMatcher.NO_MATCH, matcher);
        check("content://people/2/phones", PEOPLE_PHONES, matcher);
        check("content://people/2/phones/3", PEOPLE_PHONES_ID, matcher);
        check("content://people/2/phones/asdf", UriMatcher.NO_MATCH, matcher);
        check("content://people/2/addresses", PEOPLE_ADDRESSES, matcher);
        check("content://people/2/addresses/3", PEOPLE_ADDRESSES_ID, matcher);
        check("content://people/2/addresses/asdf", UriMatcher.NO_MATCH, matcher);
        check("content://people/2/contact-methods", PEOPLE_CONTACTMETH, matcher);
        check("content://people/2/contact-methods/3", PEOPLE_CONTACTMETH_ID, matcher);
        check("content://people/2/contact-methods/asdf", UriMatcher.NO_MATCH, matcher);
        check("content://calls", CALLS, matcher);
        check("content://calls/", CALLS, matcher);
        check("content://calls/1", CALLS_ID, matcher);
        check("content://calls/asdf", UriMatcher.NO_MATCH, matcher);
        check("content://caller-id", CALLERID, matcher);
        check("content://caller-id/", CALLERID, matcher);
        check("content://caller-id/asdf", CALLERID_TEXT, matcher);
        check("content://caller-id/1", CALLERID_TEXT, matcher);
        check("content://filter-recent", FILTERRECENT, matcher);
        check("content://auth/another/path/segment", ANOTHER_PATH_SEGMENT, matcher);
    }

    private void check(String uri, int expected, UriMatcher matcher) {
        int result = matcher.match(Uri.parse(uri));
        if (result != expected) {
            String msg = "failed on " + uri;
            msg += " expected " + expected + " got " + result;
            throw new RuntimeException(msg);
        }
    }
}


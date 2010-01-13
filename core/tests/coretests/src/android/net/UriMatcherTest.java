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

public class UriMatcherTest extends TestCase
{
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
    
    @SmallTest
    public void testContentUris() {
        check("content://asdf", UriMatcher.NO_MATCH);
        check("content://people", PEOPLE);
        check("content://people/1", PEOPLE_ID);
        check("content://people/asdf", UriMatcher.NO_MATCH);
        check("content://people/2/phones", PEOPLE_PHONES); 
        check("content://people/2/phones/3", PEOPLE_PHONES_ID); 
        check("content://people/2/phones/asdf", UriMatcher.NO_MATCH);
        check("content://people/2/addresses", PEOPLE_ADDRESSES); 
        check("content://people/2/addresses/3", PEOPLE_ADDRESSES_ID); 
        check("content://people/2/addresses/asdf", UriMatcher.NO_MATCH);
        check("content://people/2/contact-methods", PEOPLE_CONTACTMETH); 
        check("content://people/2/contact-methods/3", PEOPLE_CONTACTMETH_ID); 
        check("content://people/2/contact-methods/asdf", UriMatcher.NO_MATCH);
        check("content://calls", CALLS);
        check("content://calls/1", CALLS_ID);
        check("content://calls/asdf", UriMatcher.NO_MATCH);
        check("content://caller-id", CALLERID);
        check("content://caller-id/asdf", CALLERID_TEXT);
        check("content://caller-id/1", CALLERID_TEXT);
        check("content://filter-recent", FILTERRECENT);
    }

    private static final UriMatcher mURLMatcher = new UriMatcher(ROOT);

    static
    {
        mURLMatcher.addURI("people", null, PEOPLE);
        mURLMatcher.addURI("people", "#", PEOPLE_ID);
        mURLMatcher.addURI("people", "#/phones", PEOPLE_PHONES);
        mURLMatcher.addURI("people", "#/phones/blah", PEOPLE_PHONES_ID);
        mURLMatcher.addURI("people", "#/phones/#", PEOPLE_PHONES_ID);
        mURLMatcher.addURI("people", "#/addresses", PEOPLE_ADDRESSES);
        mURLMatcher.addURI("people", "#/addresses/#", PEOPLE_ADDRESSES_ID);
        mURLMatcher.addURI("people", "#/contact-methods", PEOPLE_CONTACTMETH);
        mURLMatcher.addURI("people", "#/contact-methods/#", PEOPLE_CONTACTMETH_ID);
        mURLMatcher.addURI("calls", null, CALLS);
        mURLMatcher.addURI("calls", "#", CALLS_ID);
        mURLMatcher.addURI("caller-id", null, CALLERID);
        mURLMatcher.addURI("caller-id", "*", CALLERID_TEXT);
        mURLMatcher.addURI("filter-recent", null, FILTERRECENT);
    }

    void check(String uri, int expected)
    {
        int result = mURLMatcher.match(Uri.parse(uri));
        if (result != expected) {
            String msg = "failed on " + uri;
            msg += " expected " + expected + " got " + result;
            throw new RuntimeException(msg);
        }
    }
}


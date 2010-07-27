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

package android.text.util;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

/**
 * LinkifyTest tests {@link Linkify}.
 */
public class LinkifyTest extends AndroidTestCase {

    @SmallTest
    public void testNothing() throws Exception {
        TextView tv;

        tv = new TextView(getContext());
        tv.setText("Hey, foo@google.com, call 415-555-1212.");

        assertFalse(tv.getMovementMethod() instanceof LinkMovementMethod);
        assertTrue(tv.getUrls().length == 0);
    }

    @SmallTest
    public void testNormal() throws Exception {
        TextView tv;

        tv = new TextView(getContext());
        tv.setAutoLinkMask(Linkify.ALL);
        tv.setText("Hey, foo@google.com, call 415-555-1212.");

        assertTrue(tv.getMovementMethod() instanceof LinkMovementMethod);
        assertTrue(tv.getUrls().length == 2);
    }

    @SmallTest
    public void testUnclickable() throws Exception {
        TextView tv;

        tv = new TextView(getContext());
        tv.setAutoLinkMask(Linkify.ALL);
        tv.setLinksClickable(false);
        tv.setText("Hey, foo@google.com, call 415-555-1212.");

        assertFalse(tv.getMovementMethod() instanceof LinkMovementMethod);
        assertTrue(tv.getUrls().length == 2);
    }
}

/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.net.LinkSocket;
import android.test.suitebuilder.annotation.SmallTest;
import junit.framework.TestCase;

/**
 * Test LinkSocket
 */
public class LinkSocketTest extends TestCase {

    @SmallTest
    public void testBasic() throws Exception {
        LinkSocket ls;

        ls = new LinkSocket();
        ls.close();
    }

    @SmallTest
    public void testLinkCapabilities() throws Exception {
        LinkCapabilities lc;

        lc = new LinkCapabilities();
        assertEquals(0, lc.size());
        assertEquals(true, lc.isEmpty());
    }
}

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

package android.core;

import junit.framework.TestCase;

import java.net.URI;
import java.net.URISyntaxException;
import android.test.suitebuilder.annotation.SmallTest;

public class URITest extends TestCase {

    @SmallTest
    public void testConstruct() throws Exception {
        construct("http://www.google.com/this/is-the/path?query#fragment",
                "www.google.com", "/this/is-the/path", true);
    }

    private static void construct(String str, String host, String path, boolean absolute)
            throws URISyntaxException {
        URI uri = new URI(str);
        assertEquals(host, uri.getHost());
        assertEquals(path, uri.getPath());
        assertEquals(absolute, uri.isAbsolute());
    }

    @SmallTest
    public void testResolve() throws Exception {
        resolve("http://www.google.com/your",
                "mom",
                "http://www.google.com/mom");
    }

    private static void resolve(String base, String uri, String expected) {
        URI b = URI.create(base);
        URI resolved = b.resolve(uri);
//        System.out.println("base=" + base + " uri=" + uri
//                + " resolved=" + resolved);
        assertEquals(expected, resolved.toString());
    }
}

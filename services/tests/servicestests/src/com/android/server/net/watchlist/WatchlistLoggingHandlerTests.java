/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.net.watchlist;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * runtest frameworks-services -c com.android.server.net.watchlist.WatchlistLoggingHandlerTests
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class WatchlistLoggingHandlerTests {

    @Test
    public void testWatchlistLoggingHandler_getAllSubDomains() throws Exception {
        String[] subDomains = WatchlistLoggingHandler.getAllSubDomains("abc.def.gh.i.jkl.mm");
        assertTrue(Arrays.equals(subDomains, new String[] {"abc.def.gh.i.jkl.mm",
                "def.gh.i.jkl.mm", "gh.i.jkl.mm", "i.jkl.mm", "jkl.mm", "mm"}));
        subDomains = WatchlistLoggingHandler.getAllSubDomains(null);
        assertNull(subDomains);
        subDomains = WatchlistLoggingHandler.getAllSubDomains("jkl.mm");
        assertTrue(Arrays.equals(subDomains, new String[] {"jkl.mm", "mm"}));
        subDomains = WatchlistLoggingHandler.getAllSubDomains("abc");
        assertTrue(Arrays.equals(subDomains, new String[] {"abc"}));
        subDomains = WatchlistLoggingHandler.getAllSubDomains("jkl.mm.");
        assertTrue(Arrays.equals(subDomains, new String[] {"jkl.mm.", "mm."}));
    }
}

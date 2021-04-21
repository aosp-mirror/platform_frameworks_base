/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import static org.junit.Assert.assertTrue;

import android.content.IntentFilter;

import androidx.test.filters.SmallTest;

import com.android.server.utils.WatchableTester;

import org.junit.Test;

import java.util.Iterator;

@SmallTest
public class WatchedIntentHandlingTest {

    @Test
    public void testWatchedIntentFilter() {
        IntentFilter i = new IntentFilter("TEST_ACTION");
        WatchedIntentFilter f = new WatchedIntentFilter(i);
        final WatchableTester watcher =
                new WatchableTester(f, "WatchedIntentFilter");
        watcher.register();
        int wantPriority = 3;
        f.setPriority(wantPriority);
        watcher.verifyChangeReported("setPriority");
        f.getPriority();
        watcher.verifyNoChangeReported("getPriority");
        assertTrue(f.getPriority() == wantPriority);
        f.setPriority(f.getPriority() + 1);
        watcher.verifyChangeReported("setPriority");
        assertTrue(f.getPriority() == wantPriority + 1);

        i.setPriority(wantPriority + 3);
        watcher.verifyNoChangeReported("indendent intent");
        assertTrue(f.getPriority() == wantPriority + 1);

        f.addAction("action-1");
        f.addAction("action-2");
        f.addAction("action-3");
        f.addAction("action-4");
        watcher.verifyChangeReported("addAction");
        int actionCount = f.countActions();

        Iterator<String> actions = f.actionsIterator();
        watcher.verifyNoChangeReported("actionsIterator 1");
        int count = 0;
        while (actions.hasNext()) {
            assertTrue(f.hasAction(actions.next()));
            count++;
        }
        watcher.verifyNoChangeReported("actionsIterator 2");
        assertTrue(count == actionCount);

        actions = f.actionsIterator();
        watcher.verifyNoChangeReported("actionsIterator 1");
        while (actions.hasNext()) {
            if (actions.next().equals("action-3")) {
                actions.remove();
                watcher.verifyChangeReported("remove action");
            }
        }
        assertTrue(f.countActions() == actionCount - 1);

        WatchedIntentFilter s1 = f.snapshot();
        watcher.verifyNoChangeReported("pulled snapshot");
    }

}

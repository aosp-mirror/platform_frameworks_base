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

import android.content.ComponentName;
import android.content.IntentFilter;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.utils.WatchableTester;

import org.junit.Test;

import java.util.Iterator;

@Presubmit
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

    @Test
    public void testPreferredActivity() {
        // Create a bunch of nondescript component names
        ComponentName component = new ComponentName("Package_A", "Class_A");
        ComponentName[] components = new ComponentName[10];
        for (int i = 0; i < components.length; i++) {
            components[i] = new ComponentName("Package_" + i, "Class_" + i);
        }
        IntentFilter i = new IntentFilter("TEST_ACTION");
        PreferredActivity a = new PreferredActivity(i, 1, components, component, true);
        final WatchableTester watcher = new WatchableTester(a, "PreferredIntentResolver");
        watcher.register();

        // Verify that the initial IntentFilter and the PreferredActivity are truly
        // independent.  This is in addition to verifying that the PreferredActivity
        // properly reports its changes.
        i.setPriority(i.getPriority() + 1);
        watcher.verifyNoChangeReported("indepenent intent");
        a.setPriority(a.getPriority() + 2);
        watcher.verifyChangeReported("dependent intent");
        // Verify independence of i and a
        assertTrue(i.getPriority() != a.getPriority());

        // Verify that snapshots created from the PreferredActivity are stable when the
        // source PreferredActivity changes.
        a.setPriority(3);
        watcher.verifyChangeReported("initialize intent priority");
        PreferredActivity s1 = a.snapshot();
        watcher.verifyNoChangeReported("pulled snapshot");
        // Verify snapshot cache.  In the absence of changes to the PreferredActivity, the
        // snapshot will not be rebuilt and will be the exact same object as before.
        assertTrue(s1 == a.snapshot());
        // Force a change by incrementing the priority.  The next snapshot must be
        // different from the first snapshot.
        a.setPriority(a.getPriority() + 1);
        watcher.verifyChangeReported("increment priority");
        PreferredActivity s2 = a.snapshot();
        watcher.verifyNoChangeReported("pulled second snapshot");
        assertTrue(s1 != s2);
        // Assert the two snapshots are different.  s1 should have priority 3 and s2
        // should have priority 4.  s2 should match the current value in a.
        assertTrue(a.getPriority() == s2.getPriority());
        assertTrue(s1.getPriority() != s2.getPriority());
    }

    @Test
    public void testPreferredIntentResolver() {
        PreferredIntentResolver r = new PreferredIntentResolver();
        final WatchableTester watcher = new WatchableTester(r, "PreferredIntentResolver");
        watcher.register();
        // Create a bunch of nondescript component names
        ComponentName component = new ComponentName("Package_A", "Class_A");
        ComponentName[] components = new ComponentName[10];
        for (int i = 0; i < components.length; i++) {
            components[i] = new ComponentName("Package_" + i, "Class_" + i);
        }
        IntentFilter i = new IntentFilter("TEST_ACTION");
        PreferredActivity a1 = new PreferredActivity(i, 1, components, component, true);

        r.addFilter(null, a1);
        watcher.verifyChangeReported("addFilter");
        i.setPriority(i.getPriority() + 1);
        watcher.verifyNoChangeReported("indepenent intent");
        a1.setPriority(a1.getPriority() + 1);
        watcher.verifyChangeReported("dependent intent");

        PreferredActivity s1 = a1.snapshot();
        watcher.verifyNoChangeReported("pulled snapshot");
        // Verify snapshot cache.
        assertTrue(s1 == a1.snapshot());
        a1.setPriority(a1.getPriority() + 1);
        watcher.verifyChangeReported("increment priority");
        PreferredActivity s2 = a1.snapshot();
        watcher.verifyNoChangeReported("pulled second snapshot");
        assertTrue(s1.getPriority() != s2.getPriority());
    }
}

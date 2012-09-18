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

package android.content;

import java.util.ArrayList;

import android.content.ContentService.ObserverCall;
import android.content.ContentService.ObserverNode;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.test.AndroidTestCase;

public class ObserverNodeTest extends AndroidTestCase {
    static class TestObserver  extends ContentObserver {
        public TestObserver() {
            super(new Handler());
        }
    }

    public void testUri() {
        final int myUserHandle = UserHandle.myUserId();

        ObserverNode root = new ObserverNode("");
        Uri[] uris = new Uri[] {
            Uri.parse("content://c/a/"),
            Uri.parse("content://c/"),
            Uri.parse("content://x/"),
            Uri.parse("content://c/b/"),
            Uri.parse("content://c/a/a1/1/"),
            Uri.parse("content://c/a/a1/2/"),
            Uri.parse("content://c/b/1/"),
            Uri.parse("content://c/b/2/"),
        };

        int[] nums = new int[] {4, 7, 1, 4, 2, 2, 3, 3};

        // special case
        root.addObserverLocked(uris[0], new TestObserver().getContentObserver(), false, root,
                0, 0, myUserHandle);
        for(int i = 1; i < uris.length; i++) {
            root.addObserverLocked(uris[i], new TestObserver().getContentObserver(), true, root,
                    0, 0, myUserHandle);
        }

        ArrayList<ObserverCall> calls = new ArrayList<ObserverCall>();

        for (int i = nums.length - 1; i >=0; --i) {
            root.collectObserversLocked(uris[i], 0, null, false, myUserHandle, calls);
            assertEquals(nums[i], calls.size());
            calls.clear();
        }
    }

    public void testUriNotNotify() {
        final int myUserHandle = UserHandle.myUserId();

        ObserverNode root = new ObserverNode("");
        Uri[] uris = new Uri[] {
            Uri.parse("content://c/"),
            Uri.parse("content://x/"),
            Uri.parse("content://c/a/"),
            Uri.parse("content://c/b/"),
            Uri.parse("content://c/a/1/"),
            Uri.parse("content://c/a/2/"),
            Uri.parse("content://c/b/1/"),
            Uri.parse("content://c/b/2/"),
        };
        int[] nums = new int[] {7, 1, 3, 3, 1, 1, 1, 1};

        for(int i = 0; i < uris.length; i++) {
            root.addObserverLocked(uris[i], new TestObserver().getContentObserver(), false, root,
                    0, 0, myUserHandle);
        }

        ArrayList<ObserverCall> calls = new ArrayList<ObserverCall>();

        for (int i = uris.length - 1; i >=0; --i) {
            root.collectObserversLocked(uris[i], 0, null, false, myUserHandle, calls);
            assertEquals(nums[i], calls.size());
            calls.clear();
        }
    }
}

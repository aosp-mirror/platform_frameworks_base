/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui.testing.dirlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.documentsui.dirlist.MultiSelectManager;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;

/**
 * Helper class for making assertions against the state of a MultiSelectManager instance.
 */
public final class SelectionProbe {

    private final MultiSelectManager mMgr;

    public SelectionProbe(MultiSelectManager mgr) {
        mMgr = mgr;
    }

    public void assertRangeSelected(int begin, int end) {
        for (int i = begin; i <= end; i++) {
            assertSelected(i);
        }
    }

    public void assertRangeNotSelected(int begin, int end) {
        for (int i = begin; i <= end; i++) {
            assertNotSelected(i);
        }
    }

    public void assertRangeSelection(int begin, int end) {
        assertSelectionSize(end - begin + 1);
        assertRangeSelected(begin, end);
    }

    public void assertSelectionSize(int expected) {
        Selection selection = mMgr.getSelection();
        assertEquals(selection.toString(), expected, selection.size());
    }

    public void assertNoSelection() {
        assertSelectionSize(0);
    }

    public void assertSelection(int... ids) {
        assertSelected(ids);
        assertEquals(ids.length, mMgr.getSelection().size());
    }

    public void assertSelected(int... ids) {
        Selection sel = mMgr.getSelection();
        for (int id : ids) {
            String sid = String.valueOf(id);
            assertTrue(sid + " is not in selection " + sel, sel.contains(sid));
        }
    }

    public void assertNotSelected(int... ids) {
        Selection sel = mMgr.getSelection();
        for (int id : ids) {
            String sid = String.valueOf(id);
            assertFalse(sid + " is in selection " + sel, sel.contains(sid));
        }
    }

    public void select(int...positions) {
        for (int position : positions) {
            mMgr.toggleSelection(String.valueOf(position));
        }
    }
}

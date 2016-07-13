/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui.dirlist;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.SparseBooleanArray;

import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.testing.dirlist.SelectionProbe;
import com.android.documentsui.testing.dirlist.TestSelectionListener;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SmallTest
public class MultiSelectManagerTest extends AndroidTestCase {

    private static final List<String> ITEMS = TestData.create(100);

    private MultiSelectManager mManager;
    private TestSelectionListener mCallback;
    private TestDocumentsAdapter mAdapter;
    private SelectionProbe mSelection;

    @Override
    public void setUp() throws Exception {
        mCallback = new TestSelectionListener();
        mAdapter = new TestDocumentsAdapter(ITEMS);
        mManager = new MultiSelectManager(mAdapter, MultiSelectManager.MODE_MULTIPLE);
        mManager.addCallback(mCallback);

        mSelection = new SelectionProbe(mManager);
    }

    public void testSelection() {
        // Check selection.
        mManager.toggleSelection(ITEMS.get(7));
        mSelection.assertSelection(7);
        // Check deselection.
        mManager.toggleSelection(ITEMS.get(7));
        mSelection.assertNoSelection();
    }

    public void testSelection_NotifiesSelectionChanged() {
        // Selection should notify.
        mManager.toggleSelection(ITEMS.get(7));
        mCallback.assertSelectionChanged();
        // Deselection should notify.
        mManager.toggleSelection(ITEMS.get(7));
        mCallback.assertSelectionChanged();
    }

    public void testRangeSelection() {
        mManager.startRangeSelection(15);
        mManager.snapRangeSelection(19);
        mSelection.assertRangeSelection(15, 19);
    }

    public void testRangeSelection_snapExpand() {
        mManager.startRangeSelection(15);
        mManager.snapRangeSelection(19);
        mManager.snapRangeSelection(27);
        mSelection.assertRangeSelection(15, 27);
    }

    public void testRangeSelection_snapContract() {
        mManager.startRangeSelection(15);
        mManager.snapRangeSelection(27);
        mManager.snapRangeSelection(19);
        mSelection.assertRangeSelection(15, 19);
    }

    public void testRangeSelection_snapInvert() {
        mManager.startRangeSelection(15);
        mManager.snapRangeSelection(27);
        mManager.snapRangeSelection(3);
        mSelection.assertRangeSelection(3, 15);
    }

    public void testRangeSelection_multiple() {
        mManager.startRangeSelection(15);
        mManager.snapRangeSelection(27);
        mManager.endRangeSelection();
        mManager.startRangeSelection(42);
        mManager.snapRangeSelection(57);
        mSelection.assertSelectionSize(29);
        mSelection.assertRangeSelected(15, 27);
        mSelection.assertRangeSelected(42, 57);

    }

    public void testProvisionalSelection() {
        Selection s = mManager.getSelection();
        mSelection.assertNoSelection();

        SparseBooleanArray provisional = new SparseBooleanArray();
        provisional.append(1, true);
        provisional.append(2, true);
        s.setProvisionalSelection(getItemIds(provisional));
        mSelection.assertSelection(1, 2);
    }

    public void testProvisionalSelection_Replace() {
        Selection s = mManager.getSelection();

        SparseBooleanArray provisional = new SparseBooleanArray();
        provisional.append(1, true);
        provisional.append(2, true);
        s.setProvisionalSelection(getItemIds(provisional));

        provisional.clear();
        provisional.append(3, true);
        provisional.append(4, true);
        s.setProvisionalSelection(getItemIds(provisional));
        mSelection.assertSelection(3, 4);
    }

    public void testProvisionalSelection_IntersectsExistingProvisionalSelection() {
        Selection s = mManager.getSelection();

        SparseBooleanArray provisional = new SparseBooleanArray();
        provisional.append(1, true);
        provisional.append(2, true);
        s.setProvisionalSelection(getItemIds(provisional));

        provisional.clear();
        provisional.append(1, true);
        s.setProvisionalSelection(getItemIds(provisional));
        mSelection.assertSelection(1);
    }

    public void testProvisionalSelection_Apply() {
        Selection s = mManager.getSelection();

        SparseBooleanArray provisional = new SparseBooleanArray();
        provisional.append(1, true);
        provisional.append(2, true);
        s.setProvisionalSelection(getItemIds(provisional));
        s.applyProvisionalSelection();
        mSelection.assertSelection(1, 2);
    }

    public void testProvisionalSelection_Cancel() {
        mManager.toggleSelection(ITEMS.get(1));
        mManager.toggleSelection(ITEMS.get(2));
        Selection s = mManager.getSelection();

        SparseBooleanArray provisional = new SparseBooleanArray();
        provisional.append(3, true);
        provisional.append(4, true);
        s.setProvisionalSelection(getItemIds(provisional));
        s.cancelProvisionalSelection();

        // Original selection should remain.
        mSelection.assertSelection(1, 2);
    }

    public void testProvisionalSelection_IntersectsAppliedSelection() {
        mManager.toggleSelection(ITEMS.get(1));
        mManager.toggleSelection(ITEMS.get(2));
        Selection s = mManager.getSelection();

        SparseBooleanArray provisional = new SparseBooleanArray();
        provisional.append(2, true);
        provisional.append(3, true);
        s.setProvisionalSelection(getItemIds(provisional));
        mSelection.assertSelection(1, 2, 3);
    }

    private static Set<String> getItemIds(SparseBooleanArray selection) {
        Set<String> ids = new HashSet<>();

        int count = selection.size();
        for (int i = 0; i < count; ++i) {
            ids.add(ITEMS.get(selection.keyAt(i)));
        }

        return ids;
    }
}

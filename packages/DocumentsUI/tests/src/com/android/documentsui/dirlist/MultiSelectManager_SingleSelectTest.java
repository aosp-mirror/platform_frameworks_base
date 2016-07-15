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

import com.android.documentsui.testing.dirlist.SelectionProbe;
import com.android.documentsui.testing.dirlist.TestSelectionListener;

import java.util.List;

@SmallTest
public class MultiSelectManager_SingleSelectTest extends AndroidTestCase {

    private static final List<String> ITEMS = TestData.create(100);

    private MultiSelectManager mManager;
    private TestSelectionListener mCallback;
    private TestDocumentsAdapter mAdapter;
    private SelectionProbe mSelection;

    @Override
    public void setUp() throws Exception {
        mCallback = new TestSelectionListener();
        mAdapter = new TestDocumentsAdapter(ITEMS);
        mManager = new MultiSelectManager(mAdapter, MultiSelectManager.MODE_SINGLE);
        mManager.addCallback(mCallback);

        mSelection = new SelectionProbe(mManager);
    }

    public void testSimpleSelect() {
        mManager.toggleSelection(ITEMS.get(3));
        mManager.toggleSelection(ITEMS.get(4));
        mCallback.assertSelectionChanged();
        mSelection.assertSelection(4);
    }

    public void testRangeSelectionNotEstablished() {
        mManager.toggleSelection(ITEMS.get(3));
        mCallback.reset();

        try {
            mManager.snapRangeSelection(10);
            fail("Should have thrown.");
        } catch (Exception expected) {}

        mCallback.assertSelectionUnchanged();
        mSelection.assertSelection(3);
    }
}

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

package com.android.documentsui.dirlist;

import static org.junit.Assert.*;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.android.documentsui.Events.InputEvent;
import com.android.documentsui.TestInputEvent;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.dirlist.UserInputHandler.DocumentDetails;
import com.android.documentsui.testing.TestPredicate;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class UserInputHandler_MouseTest {

    private static final List<String> ITEMS = TestData.create(100);

    private TestDocumentsAdapter mAdapter;
    private MultiSelectManager mSelectionMgr;
    private TestPredicate<DocumentDetails> mCanSelect;
    private TestPredicate<InputEvent> mRightClickHandler;
    private TestPredicate<DocumentDetails> mActivateHandler;
    private TestPredicate<DocumentDetails> mDeleteHandler;

    private TestInputEvent mTestEvent;
    private TestDocDetails mTestDoc;

    private UserInputHandler mInputHandler;

    @Before
    public void setUp() {

        mAdapter = new TestDocumentsAdapter(ITEMS);
        mSelectionMgr = new MultiSelectManager(mAdapter, MultiSelectManager.MODE_MULTIPLE);
        mCanSelect = new TestPredicate<>();
        mRightClickHandler = new TestPredicate<>();
        mActivateHandler = new TestPredicate<>();
        mDeleteHandler = new TestPredicate<>();

        mInputHandler = new UserInputHandler(
                mSelectionMgr,
                new TestFocusHandler(),
                (MotionEvent event) -> {
                    return mTestEvent;
                },
                (InputEvent event) -> {
                    return mTestDoc;
                },
                mCanSelect,
                mRightClickHandler::test,
                mActivateHandler::test,
                mDeleteHandler::test);

        mTestEvent = new TestInputEvent();
        mTestEvent.mouseEvent = true;
        mTestDoc = new TestDocDetails();
    }

    @Test
    public void testConfirmedClick_StartsSelection() {
        mTestDoc.modelId = "11";
        mInputHandler.onSingleTapConfirmed(null);
        assertSelected("11");
    }

    @Test
    public void testDoubleClick_Activates() {
        mTestDoc.modelId = "11";
        mInputHandler.onDoubleTap(null);
        mActivateHandler.assertLastArgument(mTestDoc);
    }

    void assertSelected(String id) {
        Selection sel = mSelectionMgr.getSelection();
        assertTrue(sel.contains(id));
    }

    private final class TestDocDetails implements DocumentDetails {

        private String modelId;
        private int position;
        private boolean inHotspot;

        @Override
        public String getModelId() {
            return modelId;
        }

        @Override
        public int getAdapterPosition() {
            return position;
        }

        @Override
        public boolean isInSelectionHotspot(InputEvent event) {
            return inHotspot;
        }

    }

    private final class TestFocusHandler implements FocusHandler {

        @Override
        public boolean handleKey(DocumentHolder doc, int keyCode, KeyEvent event) {
            return false;
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
        }

        @Override
        public void restoreLastFocus() {
        }

        @Override
        public int getFocusPosition() {
            return 0;
        }
    }
}

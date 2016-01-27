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

import android.content.Context;
import android.database.Cursor;
import android.graphics.Rect;
import android.os.SystemClock;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;

import com.android.documentsui.R;
import com.android.documentsui.State;

@SmallTest
public class DocumentHolderTest extends AndroidTestCase {

    DocumentHolder mHolder;
    TestListener mListener;

    public void setUp() throws Exception {
        Context context = getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        mHolder = new DocumentHolder(getContext(), inflater.inflate(R.layout.item_doc_list, null)) {
            @Override
            public void bind(Cursor cursor, String modelId, State state) {}
        };

        mListener = new TestListener();
        mHolder.addEventListener(mListener);

        mHolder.itemView.requestLayout();
        mHolder.itemView.invalidate();
    }

    public void testClickActivates() {
        click();
        mListener.assertSelected();
    }

    public void testTapActivates() {
        tap();
        mListener.assertActivated();
    }

    public void click() {
        mHolder.onSingleTapUp(createEvent(MotionEvent.TOOL_TYPE_MOUSE));
    }

    public void tap() {
        mHolder.onSingleTapUp(createEvent(MotionEvent.TOOL_TYPE_FINGER));
    }

    public MotionEvent createEvent(int tooltype) {
        long time = SystemClock.uptimeMillis();

        PointerProperties properties[] = new PointerProperties[] {
                new PointerProperties()
        };
        properties[0].toolType = tooltype;

        PointerCoords coords[] = new PointerCoords[] {
                new PointerCoords()
        };

        Rect rect = new Rect();
        mHolder.itemView.getHitRect(rect);
        coords[0].x = rect.left;
        coords[0].y = rect.top;

        return MotionEvent.obtain(
                time, // down time
                time, // event time
                MotionEvent.ACTION_UP, // action
                1, // pointer count
                properties, // pointer properties
                coords, // pointer coords
                0, // metastate
                0, // button state
                0, // xprecision
                0, // yprecision
                0, // deviceid
                0, // edgeflags
                0, // source
                0 // flags
                );
    }

    private class TestListener implements DocumentHolder.EventListener {
        private boolean mActivated = false;
        private boolean mSelected = false;

        public void assertActivated() {
            assertTrue(mActivated);
            assertFalse(mSelected);
        }

        public void assertSelected() {
            assertTrue(mSelected);
            assertFalse(mActivated);
        }

        @Override
        public boolean onActivate(DocumentHolder doc) {
            mActivated = true;
            return true;
        }

        @Override
        public boolean onSelect(DocumentHolder doc) {
            mSelected = true;
            return true;
        }

        @Override
        public boolean onKey(DocumentHolder doc, int keyCode, KeyEvent event) {
            return false;
        }

    }
}

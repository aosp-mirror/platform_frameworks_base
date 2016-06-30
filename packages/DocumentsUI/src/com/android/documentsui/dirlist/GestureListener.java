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

import android.support.v7.widget.RecyclerView;
import android.view.GestureDetector;
import android.view.MotionEvent;

import com.android.documentsui.Events;
import com.android.documentsui.Events.MotionInputEvent;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The gesture listener for items in the directly list, interprets gestures, and sends the
 * events to the target DocumentHolder, whence they are routed to the appropriate listener.
 */
final class GestureListener extends GestureDetector.SimpleOnGestureListener {
    // From the RecyclerView, we get two events sent to
    // ListeningGestureDetector#onInterceptTouchEvent on a mouse click; we first get an
    // ACTION_DOWN Event for clicking on the mouse, and then an ACTION_UP event from releasing
    // the mouse click. ACTION_UP event doesn't have information regarding the button (primary
    // vs. secondary), so we have to save that somewhere first from ACTION_DOWN, and then reuse
    // it later. The ACTION_DOWN event doesn't get forwarded to GestureListener, so we have open
    // up a public set method to set it.
    private int mLastButtonState = -1;
    private MultiSelectManager mSelectionMgr;
    private RecyclerView mRecView;
    private Function<MotionInputEvent, DocumentHolder> mDocFinder;
    private Predicate<MotionInputEvent> mDoubleTapHandler;
    private Predicate<MotionInputEvent> mRightClickHandler;

    public GestureListener(
            MultiSelectManager selectionMgr,
            RecyclerView recView,
            Function<MotionInputEvent, DocumentHolder> docFinder,
            Predicate<MotionInputEvent> doubleTapHandler,
            Predicate<MotionInputEvent> rightClickHandler) {
        mSelectionMgr = selectionMgr;
        mRecView = recView;
        mDocFinder = docFinder;
        mDoubleTapHandler = doubleTapHandler;
        mRightClickHandler = rightClickHandler;
    }

    public void setLastButtonState(int state) {
        mLastButtonState = state;
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        // Single tap logic:
        // We first see if it's a mouse event, and if it was right click by checking on
        // @{code ListeningGestureDetector#mLastButtonState}
        // If the selection manager is active, it gets first whack at handling tap
        // events. Otherwise, tap events are routed to the target DocumentHolder.
        if (Events.isMouseEvent(e) && mLastButtonState == MotionEvent.BUTTON_SECONDARY) {
            mLastButtonState = -1;
            return onRightClick(e);
        }

        try (MotionInputEvent event = MotionInputEvent.obtain(e, mRecView)) {
            boolean handled = mSelectionMgr.onSingleTapUp(event);

            if (handled) {
                return handled;
            }

            // Give the DocumentHolder a crack at the event.
            DocumentHolder holder = mDocFinder.apply(event);
            if (holder != null) {
                handled = holder.onSingleTapUp(e);
            }

            return handled;
        }
    }

    @Override
    public void onLongPress(MotionEvent e) {
        // Long-press events get routed directly to the selection manager. They can be
        // changed to route through the DocumentHolder if necessary.
        try (MotionInputEvent event = MotionInputEvent.obtain(e, mRecView)) {
            mSelectionMgr.onLongPress(event);
        }
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        // Double-tap events are handled directly by the DirectoryFragment. They can be changed
        // to route through the DocumentHolder if necessary.

        try (MotionInputEvent event = MotionInputEvent.obtain(e, mRecView)) {
            return mDoubleTapHandler.test(event);
        }
    }

    public boolean onRightClick(MotionEvent e) {
        try (MotionInputEvent event = MotionInputEvent.obtain(e, mRecView)) {
            return mRightClickHandler.test(event);
        }
    }
}
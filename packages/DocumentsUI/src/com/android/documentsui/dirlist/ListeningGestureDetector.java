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
import android.support.v13.view.DragStartHelper;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.OnItemTouchListener;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;

import com.android.documentsui.Events;

// Previously we listened to events with one class, only to bounce them forward
// to GestureDetector. We're still doing that here, but with a single class
// that reduces overall complexity in our glue code.
final class ListeningGestureDetector extends GestureDetector
        implements OnItemTouchListener, OnTouchListener {

    private DragStartHelper mDragHelper;
    private GestureListener mGestureListener;

    public ListeningGestureDetector(
            Context context, DragStartHelper dragHelper, GestureListener listener) {
        super(context, listener);
        mDragHelper = dragHelper;
        mGestureListener = listener;
        setOnDoubleTapListener(listener);
    }

    @Override
    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && Events.isMouseEvent(e)) {
            mGestureListener.setLastButtonState(e.getButtonState());
        }

        // Detect drag events. When a drag is detected, intercept the rest of the gesture.
        View itemView = rv.findChildViewUnder(e.getX(), e.getY());
        if (itemView != null && mDragHelper.onTouch(itemView,  e)) {
            return true;
        }
        // Forward unhandled events to the GestureDetector.
        onTouchEvent(e);

        return false;
    }

    @Override
    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
        View itemView = rv.findChildViewUnder(e.getX(), e.getY());
        mDragHelper.onTouch(itemView,  e);
        // Note: even though this event is being handled as part of a drag gesture, continue
        // forwarding to the GestureDetector. The detector needs to see the entire cluster of
        // events in order to properly interpret gestures.
        onTouchEvent(e);
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}

    // For mEmptyView right-click context menu
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
            return mGestureListener.onRightClick(event);
        }
        return false;
    }
}
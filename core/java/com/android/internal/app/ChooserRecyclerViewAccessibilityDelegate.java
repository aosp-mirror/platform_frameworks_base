/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.app;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.widget.RecyclerView;
import com.android.internal.widget.RecyclerViewAccessibilityDelegate;

class ChooserRecyclerViewAccessibilityDelegate extends RecyclerViewAccessibilityDelegate {
    private final Rect mTempRect = new Rect();
    private final int[] mConsumed = new int[2];

    ChooserRecyclerViewAccessibilityDelegate(RecyclerView recyclerView) {
        super(recyclerView);
    }

    @Override
    public boolean onRequestSendAccessibilityEvent(
            @NonNull ViewGroup host,
            @NonNull View view,
            @NonNull AccessibilityEvent event) {
        boolean result = super.onRequestSendAccessibilityEvent(host, view, event);
        if (result && event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
            ensureViewOnScreenVisibility((RecyclerView) host, view);
        }
        return result;
    }

    /**
     * Bring the view that received accessibility focus on the screen.
     * The method's logic is based on a model where RecyclerView is a child of another scrollable
     * component (ResolverDrawerLayout) and can be partially scrolled off the screen. In that case,
     * RecyclerView's children that are positioned fully within RecyclerView bounds but scrolled
     * out of the screen by the outer component, when selected by the accessibility navigation will
     * remain off the screen (as neither components detect such specific case).
     * If the view that receiving accessibility focus is scrolled of the screen, perform the nested
     * scrolling to make in visible.
     */
    private void ensureViewOnScreenVisibility(RecyclerView recyclerView, View view) {
        View child = recyclerView.findContainingItemView(view);
        if (child == null) {
            return;
        }
        recyclerView.getBoundsOnScreen(mTempRect, true);
        int recyclerOnScreenTop = mTempRect.top;
        int recyclerOnScreenBottom = mTempRect.bottom;
        child.getBoundsOnScreen(mTempRect);
        int dy = 0;
        // if needed, do the page-length scroll instead of just a row-length scroll as
        // ResolverDrawerLayout snaps to the compact view and the row-length scroll can be snapped
        // back right away.
        if (mTempRect.top < recyclerOnScreenTop) {
            // snap to the bottom
            dy = mTempRect.bottom - recyclerOnScreenBottom;
        } else if (mTempRect.bottom > recyclerOnScreenBottom) {
            // snap to the top
            dy = mTempRect.top - recyclerOnScreenTop;
        }
        nestedVerticalScrollBy(recyclerView, dy);
    }

    private void nestedVerticalScrollBy(RecyclerView recyclerView, int dy) {
        if (dy == 0) {
            return;
        }
        recyclerView.startNestedScroll(View.SCROLL_AXIS_VERTICAL);
        if (recyclerView.dispatchNestedPreScroll(0, dy, mConsumed, null)) {
            dy -= mConsumed[1];
        }
        recyclerView.scrollBy(0, dy);
        recyclerView.stopNestedScroll();
    }
}

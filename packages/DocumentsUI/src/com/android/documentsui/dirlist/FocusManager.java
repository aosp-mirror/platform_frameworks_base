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

import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import com.android.documentsui.Events;

/**
 * A class that handles navigation and focus within the DirectoryFragment.
 */
class FocusManager implements View.OnFocusChangeListener {
    private static final String TAG = "FocusManager";

    private RecyclerView mView;
    private RecyclerView.Adapter<?> mAdapter;
    private LinearLayoutManager mLayout;

    private int mLastFocusPosition = RecyclerView.NO_POSITION;

    public FocusManager(RecyclerView view) {
        mView = view;
        mAdapter = view.getAdapter();
        mLayout = (LinearLayoutManager) view.getLayoutManager();
    }

    /**
     * Handles navigation (setting focus, adjusting selection if needed) arising from incoming key
     * events.
     *
     * @param doc The DocumentHolder receiving the key event.
     * @param keyCode
     * @param event
     * @return Whether the event was handled.
     */
    public boolean handleKey(DocumentHolder doc, int keyCode, KeyEvent event) {
        if (Events.isNavigationKeyCode(keyCode)) {
            // Find the target item and focus it.
            int endPos = findTargetPosition(doc.itemView, keyCode, event);

            if (endPos != RecyclerView.NO_POSITION) {
                focusItem(endPos);
            }
            // Swallow all navigation keystrokes. Otherwise they go to the app's global
            // key-handler, which will route them back to the DF and cause focus to be reset.
            return true;
        }
        return false;
    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        // Remember focus events on items.
        if (hasFocus && v.getParent() == mView) {
            mLastFocusPosition = mView.getChildAdapterPosition(v);
        }
    }

    /**
     * Requests focus on the item that last had focus. Scrolls to that item if necessary.
     */
    public void restoreLastFocus() {
        if (mLastFocusPosition != RecyclerView.NO_POSITION) {
            // The system takes care of situations when a view is no longer on screen, etc,
            focusItem(mLastFocusPosition);
        } else {
            // Focus the first visible item
            focusItem(mLayout.findFirstVisibleItemPosition());
        }
    }

    /**
     * @return The adapter position of the last focused item.
     */
    public int getFocusPosition() {
        return mLastFocusPosition;
    }

    /**
     * Finds the destination position where the focus should land for a given navigation event.
     *
     * @param view The view that received the event.
     * @param keyCode The key code for the event.
     * @param event
     * @return The adapter position of the destination item. Could be RecyclerView.NO_POSITION.
     */
    private int findTargetPosition(View view, int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MOVE_HOME:
                return 0;
            case KeyEvent.KEYCODE_MOVE_END:
                return mAdapter.getItemCount() - 1;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return findPagedTargetPosition(view, keyCode, event);
        }

        // Find a navigation target based on the arrow key that the user pressed.
        int searchDir = -1;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                searchDir = View.FOCUS_UP;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                searchDir = View.FOCUS_DOWN;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                searchDir = View.FOCUS_LEFT;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                searchDir = View.FOCUS_RIGHT;
                break;
        }

        if (searchDir != -1) {
            View targetView = view.focusSearch(searchDir);
            // TargetView can be null, for example, if the user pressed <down> at the bottom
            // of the list.
            if (targetView != null) {
                // Ignore navigation targets that aren't items in the RecyclerView.
                if (targetView.getParent() == mView) {
                    return mView.getChildAdapterPosition(targetView);
                }
            }
        }

        return RecyclerView.NO_POSITION;
    }

    /**
     * Given a PgUp/PgDn event and the current view, find the position of the target view.
     * This returns:
     * <li>The position of the topmost (or bottom-most) visible item, if the current item is not
     *     the top- or bottom-most visible item.
     * <li>The position of an item that is one page's worth of items up (or down) if the current
     *      item is the top- or bottom-most visible item.
     * <li>The first (or last) item, if paging up (or down) would go past those limits.
     * @param view The view that received the key event.
     * @param keyCode Must be KEYCODE_PAGE_UP or KEYCODE_PAGE_DOWN.
     * @param event
     * @return The adapter position of the target item.
     */
    private int findPagedTargetPosition(View view, int keyCode, KeyEvent event) {
        int first = mLayout.findFirstVisibleItemPosition();
        int last = mLayout.findLastVisibleItemPosition();
        int current = mView.getChildAdapterPosition(view);
        int pageSize = last - first + 1;

        if (keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            if (current > first) {
                // If the current item isn't the first item, target the first item.
                return first;
            } else {
                // If the current item is the first item, target the item one page up.
                int target = current - pageSize;
                return target < 0 ? 0 : target;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            if (current < last) {
                // If the current item isn't the last item, target the last item.
                return last;
            } else {
                // If the current item is the last item, target the item one page down.
                int target = current + pageSize;
                int max = mAdapter.getItemCount() - 1;
                return target < max ? target : max;
            }
        }

        throw new IllegalArgumentException("Unsupported keyCode: " + keyCode);
    }

    /**
     * Requests focus for the item in the given adapter position, scrolling the RecyclerView if
     * necessary.
     *
     * @param pos
     */
    private void focusItem(final int pos) {
        // If the item is already in view, focus it; otherwise, scroll to it and focus it.
        RecyclerView.ViewHolder vh = mView.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.requestFocus();
        } else {
            mView.smoothScrollToPosition(pos);
            // Set a one-time listener to request focus when the scroll has completed.
            mView.addOnScrollListener(
                    new RecyclerView.OnScrollListener() {
                        @Override
                        public void onScrollStateChanged(RecyclerView view, int newState) {
                            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                // When scrolling stops, find the item and focus it.
                                RecyclerView.ViewHolder vh =
                                        view.findViewHolderForAdapterPosition(pos);
                                if (vh != null) {
                                    vh.itemView.requestFocus();
                                } else {
                                    // This might happen in weird corner cases, e.g. if the user is
                                    // scrolling while a delete operation is in progress. In that
                                    // case, just don't attempt to focus the missing item.
                                    Log.w(TAG, "Unable to focus position " + pos + " after scroll");
                                }
                                view.removeOnScrollListener(this);
                            }
                        }
                    });
        }
    }
}

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

import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.content.Context;
import android.provider.DocumentsContract.Document;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.Spannable;
import android.text.method.KeyListener;
import android.text.method.TextKeyListener;
import android.text.method.TextKeyListener.Capitalize;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import com.android.documentsui.Events;
import com.android.documentsui.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A class that handles navigation and focus within the DirectoryFragment.
 */
class FocusManager implements View.OnFocusChangeListener {
    private static final String TAG = "FocusManager";

    private RecyclerView mView;
    private DocumentsAdapter mAdapter;
    private GridLayoutManager mLayout;

    private TitleSearchHelper mSearchHelper;
    private Model mModel;

    private int mLastFocusPosition = RecyclerView.NO_POSITION;

    public FocusManager(Context context, RecyclerView view, Model model) {
        mView = view;
        mAdapter = (DocumentsAdapter) view.getAdapter();
        mLayout = (GridLayoutManager) view.getLayoutManager();
        mModel = model;

        mSearchHelper = new TitleSearchHelper(context);
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
        // Search helper gets first crack, for doing type-to-focus.
        if (mSearchHelper.handleKey(doc, keyCode, event)) {
            return true;
        }

        // Translate space/shift-space into PgDn/PgUp
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            if (event.isShiftPressed()) {
                keyCode = KeyEvent.KEYCODE_PAGE_UP;
            } else {
                keyCode = KeyEvent.KEYCODE_PAGE_DOWN;
            }
        }

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
        }

        if (inGridMode()) {
            int currentPosition = mView.getChildAdapterPosition(view);
            // Left and right arrow keys only work in grid mode.
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (currentPosition > 0) {
                        // Stop backward focus search at the first item, otherwise focus will wrap
                        // around to the last visible item.
                        searchDir = View.FOCUS_BACKWARD;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (currentPosition < mAdapter.getItemCount() - 1) {
                        // Stop forward focus search at the last item, otherwise focus will wrap
                        // around to the first visible item.
                        searchDir = View.FOCUS_FORWARD;
                    }
                    break;
            }
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
            mView.smoothScrollToPosition(pos);
        }
    }

    /**
     * @return Whether the layout manager is currently in a grid-configuration.
     */
    private boolean inGridMode() {
        return mLayout.getSpanCount() > 1;
    }

    /**
     * A helper class for handling type-to-focus. Instantiate this class, and pass it KeyEvents via
     * the {@link #handleKey(DocumentHolder, int, KeyEvent)} method. The class internally will build
     * up a string from individual key events, and perform searching based on that string. When an
     * item is found that matches the search term, that item will be focused. This class also
     * highlights instances of the search term found in the view.
     */
    private class TitleSearchHelper {
        final private KeyListener mTextListener = new TextKeyListener(Capitalize.NONE, false);
        final private Editable mSearchString = Editable.Factory.getInstance().newEditable("");
        final private Highlighter mHighlighter = new Highlighter();
        final private BackgroundColorSpan mSpan;
        private List<String> mIndex;
        private boolean mActive;

        public TitleSearchHelper(Context context) {
            mSpan = new BackgroundColorSpan(context.getColor(R.color.accent_dark));
        }

        /**
         * Handles alphanumeric keystrokes for type-to-focus. This method builds a search term out
         * of individual key events, and then performs a search for the given string.
         *
         * @param doc The document holder receiving the key event.
         * @param keyCode
         * @param event
         * @return Whether the event was handled.
         */
        public boolean handleKey(DocumentHolder doc, int keyCode, KeyEvent event) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_ESCAPE:
                case KeyEvent.KEYCODE_ENTER:
                    if (mActive) {
                        // These keys end any active searches.
                        deactivate();
                        return true;
                    } else {
                        // Don't handle these key events if there is no active search.
                        return false;
                    }
                case KeyEvent.KEYCODE_SPACE:
                    // This allows users to search for files with spaces in their names, but ignores
                    // spacebar events when a text search is not active.
                    if (!mActive) {
                        return false;
                    }
            }

            // Navigation keys also end active searches.
            if (Events.isNavigationKeyCode(keyCode)) {
                deactivate();
                // Don't handle the keycode, so navigation still occurs.
                return false;
            }

            // Build up the search string, and perform the search.
            boolean handled = mTextListener.onKeyDown(doc.itemView, mSearchString, keyCode, event);

            // Delete is processed by the text listener, but not "handled". Check separately for it.
            if (handled || keyCode == KeyEvent.KEYCODE_DEL) {
                String searchString = mSearchString.toString();
                if (searchString.length() == 0) {
                    // Don't perform empty searches.
                    return false;
                }
                activate();
                for (int pos = 0; pos < mIndex.size(); pos++) {
                    String title = mIndex.get(pos);
                    if (title != null && title.startsWith(searchString)) {
                        focusItem(pos);
                        break;
                    }
                }
            }

            return handled;
        }

        /**
         * Activates the search helper, which changes its key handling and updates the search index
         * and highlights if necessary. Call this each time the search term is updated.
         */
        private void activate() {
            if (!mActive) {
                // Install listeners.
                mModel.addUpdateListener(mModelListener);
            }

            // If the search index was invalidated, rebuild it
            if (mIndex == null) {
                buildIndex();
            }

            // TODO: Uncomment this to enable search term highlighting in the UI.
//            mHighlighter.activate();

            mActive = true;
        }

        /**
         * Deactivates the search helper (see {@link #activate()}). Call this when a search ends.
         */
        private void deactivate() {
            if (mActive) {
                // Remove listeners.
                mModel.removeUpdateListener(mModelListener);
            }

            // TODO: Uncomment this when search-term highlighting is enabled in the UI.
//            mHighlighter.deactivate();

            mIndex = null;
            mSearchString.clear();
            mActive = false;
        }

        /**
         * Applies title highlights to the given view. The view must have a title field that is a
         * spannable text field.  If this condition is not met, this function does nothing.
         *
         * @param view
         */
        private void applyHighlight(View view) {
            TextView titleView = (TextView) view.findViewById(android.R.id.title);
            if (titleView == null) {
                return;
            }

            String searchString = mSearchString.toString();
            CharSequence tmpText = titleView.getText();
            if (tmpText instanceof Spannable) {
                Spannable title = (Spannable) tmpText;
                String titleString = title.toString();
                if (titleString.startsWith(searchString)) {
                    title.setSpan(mSpan, 0, searchString.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    title.removeSpan(mSpan);
                }
            }
        }

        /**
         * Removes title highlights from the given view. The view must have a title field that is a
         * spannable text field.  If this condition is not met, this function does nothing.
         *
         * @param view
         */
        private void removeHighlight(View view) {
            TextView titleView = (TextView) view.findViewById(android.R.id.title);
            if (titleView == null) {
                return;
            }

            CharSequence tmpText = titleView.getText();
            if (tmpText instanceof Spannable) {
                ((Spannable) tmpText).removeSpan(mSpan);
            }
        }

        /**
         * Builds a search index for finding items by title. Queries the model and adapter, so both
         * must be set up before calling this method.
         */
        private void buildIndex() {
            int itemCount = mAdapter.getItemCount();
            List<String> index = new ArrayList<>(itemCount);
            for (int i = 0; i < itemCount; i++) {
                String modelId = mAdapter.getModelId(i);
                if (modelId != null) {
                    index.add(
                            getCursorString(mModel.getItem(modelId), Document.COLUMN_DISPLAY_NAME));
                } else {
                    index.add("");
                }
            }
            mIndex = index;
        }

        private Model.UpdateListener mModelListener = new Model.UpdateListener() {
            @Override
            public void onModelUpdate(Model model) {
                // Invalidate the search index when the model updates.
                mIndex = null;
            }

            @Override
            public void onModelUpdateFailed(Exception e) {
                // Invalidate the search index when the model updates.
                mIndex = null;
            }
        };

        private class Highlighter implements RecyclerView.OnChildAttachStateChangeListener {
            /**
             * Starts highlighting instances of the current search term in the UI.
             */
            public void activate() {
                // Update highlights on all views
                int itemCount = mView.getChildCount();
                for (int i = 0; i < itemCount; i++) {
                    applyHighlight(mView.getChildAt(i));
                }
                // Keep highlights up-to-date as items come in and out of view.
                mView.addOnChildAttachStateChangeListener(this);
            }

            /**
             * Stops highlighting instances of the current search term in the UI.
             */
            public void deactivate() {
                // Remove highlights on all views
                int itemCount = mView.getChildCount();
                for (int i = 0; i < itemCount; i++) {
                    removeHighlight(mView.getChildAt(i));
                }
                // Stop updating highlights.
                mView.removeOnChildAttachStateChangeListener(this);
            }

            @Override
            public void onChildViewAttachedToWindow(View view) {
                applyHighlight(view);
            }

            @Override
            public void onChildViewDetachedFromWindow(View view) {
                TextView titleView = (TextView) view.findViewById(android.R.id.title);
                if (titleView != null) {
                    removeHighlight(titleView);
                }
            }
        };
    }
}

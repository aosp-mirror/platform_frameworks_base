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

import static com.android.documentsui.Shared.DEBUG;

import android.support.annotation.VisibleForTesting;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.android.documentsui.Events;
import com.android.documentsui.Events.InputEvent;
import com.android.documentsui.dirlist.DocumentHolder.KeyboardEventListener;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Grand unified-ish gesture/event listener for items in the directory list.
 */
public final class UserInputHandler<T extends InputEvent>
        extends GestureDetector.SimpleOnGestureListener
        implements KeyboardEventListener {

    private static final String TAG = "UserInputHandler";

    private final MultiSelectManager mSelectionMgr;
    private final FocusHandler mFocusHandler;
    private final Function<MotionEvent, T> mEventConverter;
    private final Function<T, DocumentDetails> mDocFinder;
    private final Predicate<DocumentDetails> mSelectable;
    private final EventHandler mRightClickHandler;
    private final DocumentHandler mActivateHandler;
    private final DocumentHandler mDeleteHandler;
    private final TouchInputDelegate mTouchDelegate;
    private final MouseInputDelegate mMouseDelegate;
    private final KeyInputHandler mKeyListener;

    public UserInputHandler(
            MultiSelectManager selectionMgr,
            FocusHandler focusHandler,
            Function<MotionEvent, T> eventConverter,
            Function<T, DocumentDetails> docFinder,
            Predicate<DocumentDetails> selectable,
            EventHandler rightClickHandler,
            DocumentHandler activateHandler,
            DocumentHandler deleteHandler) {

        mSelectionMgr = selectionMgr;
        mFocusHandler = focusHandler;
        mEventConverter = eventConverter;
        mDocFinder = docFinder;
        mSelectable = selectable;
        mRightClickHandler = rightClickHandler;
        mActivateHandler = activateHandler;
        mDeleteHandler = deleteHandler;

        mTouchDelegate = new TouchInputDelegate();
        mMouseDelegate = new MouseInputDelegate();
        mKeyListener = new KeyInputHandler();
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        try (T event = mEventConverter.apply(e)) {
            return onSingleTapUp(event);
        }
    }

    @VisibleForTesting
    boolean onSingleTapUp(T event) {
        return event.isMouseEvent()
                ? mMouseDelegate.onSingleTapUp(event)
                : mTouchDelegate.onSingleTapUp(event);
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        try (T event = mEventConverter.apply(e)) {
            return onSingleTapConfirmed(event);
        }
    }

    @VisibleForTesting
    boolean onSingleTapConfirmed(T event) {
        return event.isMouseEvent()
                ? mMouseDelegate.onSingleTapConfirmed(event)
                : mTouchDelegate.onSingleTapConfirmed(event);
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        try (T event = mEventConverter.apply(e)) {
            return onDoubleTap(event);
        }
    }

    @VisibleForTesting
    boolean onDoubleTap(T event) {
        return event.isMouseEvent()
                ? mMouseDelegate.onDoubleTap(event)
                : mTouchDelegate.onDoubleTap(event);
    }

    @Override
    public void onLongPress(MotionEvent e) {
        try (T event = mEventConverter.apply(e)) {
            onLongPress(event);
        }
    }

    @VisibleForTesting
    void onLongPress(T event) {
        if (event.isMouseEvent()) {
            mMouseDelegate.onLongPress(event);
        }
        mTouchDelegate.onLongPress(event);
    }

    public boolean onSingleRightClickUp(MotionEvent e) {
        try (T event = mEventConverter.apply(e)) {
            return mMouseDelegate.onSingleRightClickUp(event);
        }
    }

    @Override
    public boolean onKey(DocumentHolder doc, int keyCode, KeyEvent event) {
        return mKeyListener.onKey(doc, keyCode, event);
    }

    // TODO: Isolate this hack...see if we can't get this solved at the platform level.
    public void setLastButtonState(int state) {
        mMouseDelegate.setLastButtonState(state);
    }

    private boolean activateDocument(DocumentDetails doc) {
        return mActivateHandler.accept(doc);
    }

    private boolean selectDocument(DocumentDetails doc) {
        assert(doc != null);
        mSelectionMgr.toggleSelection(doc.getModelId());
        mSelectionMgr.setSelectionRangeBegin(doc.getAdapterPosition());
        return true;
    }

    boolean isRangeExtension(T event) {
        return event.isShiftKeyDown() && mSelectionMgr.isRangeSelectionActive();
    }

    private void extendSelectionRange(T event) {
        mSelectionMgr.snapSelection(event.getItemPosition());
    }

    private final class TouchInputDelegate {

        boolean onSingleTapUp(T event) {
            if (!event.isOverItem()) {
                if (DEBUG) Log.d(TAG, "Tap on non-item. Clearing selection.");
                mSelectionMgr.clearSelection();
                return false;
            }

            if (mSelectionMgr.hasSelection()) {
                if (isRangeExtension(event)) {
                    mSelectionMgr.snapSelection(event.getItemPosition());
                } else {
                    selectDocument(mDocFinder.apply(event));
                }
                return true;
            }

            // Give the DocumentHolder a crack at the event.
            DocumentDetails doc = mDocFinder.apply(event);
            if (doc != null) {
                // Touch events select if they occur in the selection hotspot,
                // otherwise they activate.
                return doc.isInSelectionHotspot(event)
                        ? selectDocument(doc)
                        : activateDocument(doc);
            }

            return false;
        }

        boolean onSingleTapConfirmed(T event) {
            return false;
        }

        boolean onDoubleTap(T event) {
            return false;
        }

        final void onLongPress(T event) {
            if (!event.isOverItem()) {
                return;
            }

            if (isRangeExtension(event)) {
                extendSelectionRange(event);
            } else {
                selectDocument(mDocFinder.apply(event));
            }
        }
    }

    private final class MouseInputDelegate {

        // From the RecyclerView, we get two events sent to
        // ListeningGestureDetector#onInterceptTouchEvent on a mouse click; we first get an
        // ACTION_DOWN Event for clicking on the mouse, and then an ACTION_UP event from releasing
        // the mouse click. ACTION_UP event doesn't have information regarding the button (primary
        // vs. secondary), so we have to save that somewhere first from ACTION_DOWN, and then reuse
        // it later. The ACTION_DOWN event doesn't get forwarded to UserInputListener,
        // so we have open up a public set method to set it.
        private int mLastButtonState = -1;

        // true when the previous event has consumed a right click motion event
        private boolean mAteRightClick;

        // The event has been handled in onSingleTapUp
        private boolean mHandledTapUp;

        boolean onSingleTapUp(T event) {
            if (eatRightClick()) {
                return onSingleRightClickUp(event);
            }

            if (!event.isOverItem()) {
                mSelectionMgr.clearSelection();
                return false;
            }

            if (mSelectionMgr.hasSelection()) {
                if (isRangeExtension(event)) {
                    extendSelectionRange(event);
                } else {
                    selectDocument(mDocFinder.apply(event));
                }
                mHandledTapUp = true;
                return true;
            }

            // We'll toggle selection in onSingleTapConfirmed
            // This avoids flickering on/off action mode when an item is double clicked.
            if (!mSelectionMgr.hasSelection()) {
                return false;
            }

            DocumentDetails doc = mDocFinder.apply(event);
            if (doc == null) {
                return false;
            }

            mHandledTapUp = true;
            return selectDocument(doc);
        }

        boolean onSingleTapConfirmed(T event) {
            if (mAteRightClick) {
                mAteRightClick = false;
                return false;
            }
            if (mHandledTapUp) {
                mHandledTapUp = false;
                return false;
            }

            if (mSelectionMgr.hasSelection()) {
                return false;  // should have been handled by onSingleTapUp.
            }

            DocumentDetails doc = mDocFinder.apply(event);
            if (doc == null) {
                return false;
            }

            return selectDocument(doc);
        }

        boolean onDoubleTap(T event) {
            mHandledTapUp = false;
            DocumentDetails doc = mDocFinder.apply(event);
            if (doc != null) {
                return mSelectionMgr.hasSelection()
                        ? selectDocument(doc)
                        : activateDocument(doc);
            }
            return false;
        }

        final void onLongPress(T event) {
            if (!event.isOverItem()) {
                return;
            }

            if (isRangeExtension(event)) {
                extendSelectionRange(event);
            } else {
                selectDocument(mDocFinder.apply(event));
            }
        }

        private boolean onSingleRightClickUp(T event) {
            return mRightClickHandler.apply(event);
        }

        // hack alert from here through end of class.
        private void setLastButtonState(int state) {
            mLastButtonState = state;
        }

        private boolean eatRightClick() {
            if (mLastButtonState == MotionEvent.BUTTON_SECONDARY) {
                mLastButtonState = -1;
                mAteRightClick = true;
                return true;
            }
            return false;
        }
    }

    private final class KeyInputHandler {
        // TODO: Refactor FocusManager to depend only on DocumentDetails so we can eliminate
        // difficult to test dependency on DocumentHolder.

        boolean onKey(DocumentHolder doc, int keyCode, KeyEvent event) {
            // Only handle key-down events. This is simpler, consistent with most other UIs, and
            // enables the handling of repeated key events from holding down a key.
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            // Ignore tab key events.  Those should be handled by the top-level key handler.
            if (keyCode == KeyEvent.KEYCODE_TAB) {
                return false;
            }

            if (mFocusHandler.handleKey(doc, keyCode, event)) {
                // Handle range selection adjustments. Extending the selection will adjust the
                // bounds of the in-progress range selection. Each time an unshifted navigation
                // event is received, the range selection is restarted.
                if (shouldExtendSelection(doc, event)) {
                    if (!mSelectionMgr.isRangeSelectionActive()) {
                        // Start a range selection if one isn't active
                        mSelectionMgr.startRangeSelection(doc.getAdapterPosition());
                    }
                    mSelectionMgr.snapRangeSelection(mFocusHandler.getFocusPosition());
                } else {
                    mSelectionMgr.endRangeSelection();
                }
                return true;
            }

            // Handle enter key events
            switch (keyCode) {
                case KeyEvent.KEYCODE_ENTER:
                    if (event.isShiftPressed()) {
                        selectDocument(doc);
                    }
                    // For non-shifted enter keypresses, fall through.
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    return activateDocument(doc);
                case KeyEvent.KEYCODE_FORWARD_DEL:
                    // This has to be handled here instead of in a keyboard shortcut, because
                    // keyboard shortcuts all have to be modified with the 'Ctrl' key.
                    if (mSelectionMgr.hasSelection()) {
                        mDeleteHandler.accept(doc);
                    }
                    // Always handle the key, even if there was nothing to delete. This is a
                    // precaution to prevent other handlers from potentially picking up the event
                    // and triggering extra behaviors.
                    return true;
            }

            return false;
        }

        private boolean shouldExtendSelection(DocumentDetails doc, KeyEvent event) {
            if (!Events.isNavigationKeyCode(event.getKeyCode()) || !event.isShiftPressed()) {
                return false;
            }

            return mSelectable.test(doc);
        }
    }

    /**
     * Class providing limited access to document view info.
     */
    public interface DocumentDetails {
        String getModelId();
        int getAdapterPosition();
        boolean isInSelectionHotspot(InputEvent event);
    }

    @FunctionalInterface
    interface EventHandler {
        boolean apply(InputEvent event);
    }

    @FunctionalInterface
    interface DocumentHandler {
        boolean accept(DocumentDetails doc);
    }
}

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

import android.view.KeyEvent;

import com.android.documentsui.Events;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Handles click/tap/key events on individual DocumentHolders.
 */
class ItemEventListener implements DocumentHolder.EventListener {
    private MultiSelectManager mSelectionManager;
    private FocusManager mFocusManager;

    private Consumer<String> mViewItemCallback;
    private Consumer<Selection> mDeleteDocumentsCallback;
    private Predicate<String> mCanSelectPredicate;

    public ItemEventListener(
            MultiSelectManager selectionManager,
            FocusManager focusManager,
            Consumer<String> viewItemCallback,
            Consumer<Selection> deleteDocumentsCallback,
            Predicate<String> canSelectPredicate) {

        mSelectionManager = selectionManager;
        mFocusManager = focusManager;
        mViewItemCallback = viewItemCallback;
        mDeleteDocumentsCallback = deleteDocumentsCallback;
        mCanSelectPredicate = canSelectPredicate;
    }

    @Override
    public boolean onActivate(DocumentHolder doc) {
        // Toggle selection if we're in selection mode, othewise, view item.
        if (mSelectionManager.hasSelection()) {
            mSelectionManager.toggleSelection(doc.modelId);
        } else {
            mViewItemCallback.accept(doc.modelId);
        }
        return true;
    }

    @Override
    public boolean onSelect(DocumentHolder doc) {
        mSelectionManager.toggleSelection(doc.modelId);
        mSelectionManager.setSelectionRangeBegin(doc.getAdapterPosition());
        return true;
    }

    @Override
    public boolean onKey(DocumentHolder doc, int keyCode, KeyEvent event) {
        // Only handle key-down events. This is simpler, consistent with most other UIs, and
        // enables the handling of repeated key events from holding down a key.
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        // Ignore tab key events.  Those should be handled by the top-level key handler.
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            return false;
        }

        if (mFocusManager.handleKey(doc, keyCode, event)) {
            // Handle range selection adjustments. Extending the selection will adjust the
            // bounds of the in-progress range selection. Each time an unshifted navigation
            // event is received, the range selection is restarted.
            if (shouldExtendSelection(doc, event)) {
                if (!mSelectionManager.isRangeSelectionActive()) {
                    // Start a range selection if one isn't active
                    mSelectionManager.startRangeSelection(doc.getAdapterPosition());
                }
                mSelectionManager.snapRangeSelection(mFocusManager.getFocusPosition());
            } else {
                mSelectionManager.endRangeSelection();
            }
            return true;
        }

        // Handle enter key events
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                if (event.isShiftPressed()) {
                    return onSelect(doc);
                }
                // For non-shifted enter keypresses, fall through.
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return onActivate(doc);
            case KeyEvent.KEYCODE_FORWARD_DEL:
                // This has to be handled here instead of in a keyboard shortcut, because
                // keyboard shortcuts all have to be modified with the 'Ctrl' key.
                if (mSelectionManager.hasSelection()) {
                    Selection selection = mSelectionManager.getSelection(new Selection());
                    mDeleteDocumentsCallback.accept(selection);
                }
                // Always handle the key, even if there was nothing to delete. This is a
                // precaution to prevent other handlers from potentially picking up the event
                // and triggering extra behaviours.
                return true;
        }

        return false;
    }

    private boolean shouldExtendSelection(DocumentHolder doc, KeyEvent event) {
        if (!Events.isNavigationKeyCode(event.getKeyCode()) || !event.isShiftPressed()) {
            return false;
        }

        return mCanSelectPredicate.test(doc.modelId);
    }
}

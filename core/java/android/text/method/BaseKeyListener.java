/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.text.method;

import android.view.KeyEvent;
import android.view.View;
import android.text.*;
import android.text.method.TextKeyListener.Capitalize;
import android.widget.TextView;

/**
 * Abstract base class for key listeners.
 *
 * Provides a basic foundation for entering and editing text.
 * Subclasses should override {@link #onKeyDown} and {@link #onKeyUp} to insert
 * characters as keys are pressed.
 */
public abstract class BaseKeyListener extends MetaKeyKeyListener
        implements KeyListener {
    /* package */ static final Object OLD_SEL_START = new NoCopySpan.Concrete();

    /**
     * Performs the action that happens when you press the {@link KeyEvent#KEYCODE_DEL} key in
     * a {@link TextView}.  If there is a selection, deletes the selection; otherwise,
     * deletes the character before the cursor, if any; ALT+DEL deletes everything on
     * the line the cursor is on.
     *
     * @return true if anything was deleted; false otherwise.
     */
    public boolean backspace(View view, Editable content, int keyCode, KeyEvent event) {
        return backspaceOrForwardDelete(view, content, keyCode, event, false);
    }

    /**
     * Performs the action that happens when you press the {@link KeyEvent#KEYCODE_FORWARD_DEL}
     * key in a {@link TextView}.  If there is a selection, deletes the selection; otherwise,
     * deletes the character before the cursor, if any; ALT+FORWARD_DEL deletes everything on
     * the line the cursor is on.
     *
     * @return true if anything was deleted; false otherwise.
     */
    public boolean forwardDelete(View view, Editable content, int keyCode, KeyEvent event) {
        return backspaceOrForwardDelete(view, content, keyCode, event, true);
    }

    private boolean backspaceOrForwardDelete(View view, Editable content, int keyCode,
            KeyEvent event, boolean isForwardDelete) {
        // Ensure the key event does not have modifiers except ALT or SHIFT.
        if (!KeyEvent.metaStateHasNoModifiers(event.getMetaState()
                & ~(KeyEvent.META_SHIFT_MASK | KeyEvent.META_ALT_MASK))) {
            return false;
        }

        // If there is a current selection, delete it.
        if (deleteSelection(view, content)) {
            return true;
        }

        // Alt+Backspace or Alt+ForwardDelete deletes the current line, if possible.
        if (event.isAltPressed() || getMetaState(content, META_ALT_ON) == 1) {
            if (deleteLine(view, content)) {
                return true;
            }
        }

        // Delete a character.
        final int start = Selection.getSelectionEnd(content);
        final int end;
        if (isForwardDelete || event.isShiftPressed()
                || getMetaState(content, META_SHIFT_ON) == 1) {
            end = TextUtils.getOffsetAfter(content, start);
        } else {
            end = TextUtils.getOffsetBefore(content, start);
        }
        if (start != end) {
            content.delete(Math.min(start, end), Math.max(start, end));
            return true;
        }
        return false;
    }

    private boolean deleteSelection(View view, Editable content) {
        int selectionStart = Selection.getSelectionStart(content);
        int selectionEnd = Selection.getSelectionEnd(content);
        if (selectionEnd < selectionStart) {
            int temp = selectionEnd;
            selectionEnd = selectionStart;
            selectionStart = temp;
        }
        if (selectionStart != selectionEnd) {
            content.delete(selectionStart, selectionEnd);
            return true;
        }
        return false;
    }

    private boolean deleteLine(View view, Editable content) {
        if (view instanceof TextView) {
            final Layout layout = ((TextView) view).getLayout();
            if (layout != null) {
                final int line = layout.getLineForOffset(Selection.getSelectionStart(content));
                final int start = layout.getLineStart(line);
                final int end = layout.getLineEnd(line);
                if (end != start) {
                    content.delete(start, end);
                    return true;
                }
            }
        }
        return false;
    }

    static int makeTextContentType(Capitalize caps, boolean autoText) {
        int contentType = InputType.TYPE_CLASS_TEXT;
        switch (caps) {
            case CHARACTERS:
                contentType |= InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
                break;
            case WORDS:
                contentType |= InputType.TYPE_TEXT_FLAG_CAP_WORDS;
                break;
            case SENTENCES:
                contentType |= InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
                break;
        }
        if (autoText) {
            contentType |= InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
        }
        return contentType;
    }

    public boolean onKeyDown(View view, Editable content,
                             int keyCode, KeyEvent event) {
        boolean handled;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                handled = backspace(view, content, keyCode, event);
                break;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                handled = forwardDelete(view, content, keyCode, event);
                break;
            default:
                handled = false;
                break;
        }

        if (handled) {
            adjustMetaAfterKeypress(content);
        }

        return super.onKeyDown(view, content, keyCode, event);
    }

    /**
     * Base implementation handles ACTION_MULTIPLE KEYCODE_UNKNOWN by inserting
     * the event's text into the content.
     */
    public boolean onKeyOther(View view, Editable content, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_MULTIPLE
                || event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN) {
            // Not something we are interested in.
            return false;
        }

        int selectionStart = Selection.getSelectionStart(content);
        int selectionEnd = Selection.getSelectionEnd(content);
        if (selectionEnd < selectionStart) {
            int temp = selectionEnd;
            selectionEnd = selectionStart;
            selectionStart = temp;
        }

        CharSequence text = event.getCharacters();
        if (text == null) {
            return false;
        }

        content.replace(selectionStart, selectionEnd, text);
        return true;
    }
}


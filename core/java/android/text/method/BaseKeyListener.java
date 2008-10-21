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
import android.os.Message;
import android.util.Log;
import android.text.*;
import android.widget.TextView;

public abstract class BaseKeyListener
extends MetaKeyKeyListener
implements KeyListener {
    /* package */ static final Object OLD_SEL_START = new Object();

    /**
     * Performs the action that happens when you press the DEL key in
     * a TextView.  If there is a selection, deletes the selection;
     * otherwise, DEL alone deletes the character before the cursor,
     * if any;
     * ALT+DEL deletes everything on the line the cursor is on.
     *
     * @return true if anything was deleted; false otherwise.   
     */
    public boolean backspace(View view, Editable content, int keyCode,
                             KeyEvent event) {
        int selStart, selEnd;
        boolean result = true;

        {
            int a = Selection.getSelectionStart(content);
            int b = Selection.getSelectionEnd(content);

            selStart = Math.min(a, b);
            selEnd = Math.max(a, b);
        }

        if (selStart != selEnd) {
            content.delete(selStart, selEnd);
        } else if (altBackspace(view, content, keyCode, event)) {
            result = true;
        } else {
            int to = TextUtils.getOffsetBefore(content, selEnd);

            if (to != selEnd) {
                content.delete(Math.min(to, selEnd), Math.max(to, selEnd));
            }
            else {
                result = false;
            }
        }

        if (result)
            adjustMetaAfterKeypress(content);

        return result;
    }

    private boolean altBackspace(View view, Editable content, int keyCode,
                                 KeyEvent event) {
        if (getMetaState(content, META_ALT_ON) != 1) {
            return false;
        }

        if (!(view instanceof TextView)) {
            return false;
        }

        Layout layout = ((TextView) view).getLayout();

        if (layout == null) {
            return false;
        }

        int l = layout.getLineForOffset(Selection.getSelectionStart(content));
        int start = layout.getLineStart(l);
        int end = layout.getLineEnd(l);

        if (end == start) {
            return false;
        }

        content.delete(start, end);
        return true;
    }

    public boolean onKeyDown(View view, Editable content,
                             int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            backspace(view, content, keyCode, event);
            return true;
        }
        
        return super.onKeyDown(view, content, keyCode, event);
    }
}


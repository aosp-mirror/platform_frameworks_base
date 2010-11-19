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
import android.text.Editable;
import android.text.InputFilter;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

/**
 * For numeric text entry
 */
public abstract class NumberKeyListener extends BaseKeyListener
    implements InputFilter
{
    /**
     * You can say which characters you can accept.
     */
    protected abstract char[] getAcceptedChars();

    protected int lookup(KeyEvent event, Spannable content) {
        return event.getMatch(getAcceptedChars(), event.getMetaState() | getMetaState(content));
    }

    public CharSequence filter(CharSequence source, int start, int end,
                               Spanned dest, int dstart, int dend) {
        char[] accept = getAcceptedChars();
        boolean filter = false;

        int i;
        for (i = start; i < end; i++) {
            if (!ok(accept, source.charAt(i))) {
                break;
            }
        }

        if (i == end) {
            // It was all OK.
            return null;
        }

        if (end - start == 1) {
            // It was not OK, and there is only one char, so nothing remains.
            return "";
        }

        SpannableStringBuilder filtered =
            new SpannableStringBuilder(source, start, end);
        i -= start;
        end -= start;

        int len = end - start;
        // Only count down to i because the chars before that were all OK.
        for (int j = end - 1; j >= i; j--) {
            if (!ok(accept, source.charAt(j))) {
                filtered.delete(j, j + 1);
            }
        }

        return filtered;
    }

    protected static boolean ok(char[] accept, char c) {
        for (int i = accept.length - 1; i >= 0; i--) {
            if (accept[i] == c) {
                return true;
            }
        }

        return false;
    }
    
    @Override
    public boolean onKeyDown(View view, Editable content,
                             int keyCode, KeyEvent event) {
        int selStart, selEnd;

        {
            int a = Selection.getSelectionStart(content);
            int b = Selection.getSelectionEnd(content);

            selStart = Math.min(a, b);
            selEnd = Math.max(a, b);
        }

        if (selStart < 0 || selEnd < 0) {
            selStart = selEnd = 0;
            Selection.setSelection(content, 0);
        }

        int i = event != null ? lookup(event, content) : 0;
        int repeatCount = event != null ? event.getRepeatCount() : 0;
        if (repeatCount == 0) {
            if (i != 0) {
                if (selStart != selEnd) {
                    Selection.setSelection(content, selEnd);
                }

                content.replace(selStart, selEnd, String.valueOf((char) i));

                adjustMetaAfterKeypress(content);
                return true;
            }
        } else if (i == '0' && repeatCount == 1) {
            // Pretty hackish, it replaces the 0 with the +

            if (selStart == selEnd && selEnd > 0 &&
                    content.charAt(selStart - 1) == '0') {
                content.replace(selStart - 1, selEnd, String.valueOf('+'));
                adjustMetaAfterKeypress(content);
                return true;
            }
        }

        adjustMetaAfterKeypress(content);
        return super.onKeyDown(view, content, keyCode, event);
    }
}

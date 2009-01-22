/*
 * Copyright (C) 2007-2008 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.internal.widget;

import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.KeyListener;
import android.util.Log;
import android.util.LogPrinter;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.widget.TextView;

class ComposingText {
}

public class EditableInputConnection extends BaseInputConnection {
    private static final boolean DEBUG = false;
    private static final String TAG = "EditableInputConnection";

    public static final Object COMPOSING = new ComposingText();
    
    private final TextView mTextView;

    private Object[] mDefaultComposingSpans;
    
    public EditableInputConnection(TextView textview) {
        super(textview);
        mTextView = textview;
    }

    public static final void removeComposingSpans(Spannable text) {
        text.removeSpan(COMPOSING);
        Object[] sps = text.getSpans(0, text.length(), Object.class);
        if (sps != null) {
            for (int i=sps.length-1; i>=0; i--) {
                Object o = sps[i];
                if ((text.getSpanFlags(o)&Spanned.SPAN_COMPOSING) != 0) {
                    text.removeSpan(o);
                }
            }
        }
    }
    
    public static void setComposingSpans(Spannable text) {
        final Object[] sps = text.getSpans(0, text.length(), Object.class);
        if (sps != null) {
            for (int i=sps.length-1; i>=0; i--) {
                final Object o = sps[i];
                if (o == COMPOSING) {
                    text.removeSpan(o);
                    continue;
                }
                final int fl = text.getSpanFlags(o);
                if ((fl&(Spanned.SPAN_COMPOSING|Spanned.SPAN_POINT_MARK_MASK)) 
                        != (Spanned.SPAN_COMPOSING|Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)) {
                    text.setSpan(o, text.getSpanStart(o), text.getSpanEnd(o),
                            (fl&Spanned.SPAN_POINT_MARK_MASK)
                                    | Spanned.SPAN_COMPOSING
                                    | Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        
        text.setSpan(COMPOSING, 0, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_COMPOSING);
    }
    
    public static int getComposingSpanStart(Spannable text) {
        return text.getSpanStart(COMPOSING);
    }
    
    public static int getComposingSpanEnd(Spannable text) {
        return text.getSpanEnd(COMPOSING);
    }
    
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        if (DEBUG) Log.v(TAG, "setComposingText " + text);
        replaceText(text, newCursorPosition, true);
        return true;
    }

    public boolean finishComposingText() {
        if (DEBUG) Log.v(TAG, "finishComposingText");
        final Editable content = getEditable();
        if (content != null) {
            removeComposingSpans(content);
        }
        return true;
    }
    
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (DEBUG) Log.v(TAG, "commitText " + text);
        replaceText(text, newCursorPosition, false);
        return true;
    }

    public boolean commitCompletion(CompletionInfo text) {
        if (DEBUG) Log.v(TAG, "commitCompletion " + text);
        mTextView.onCommitCompletion(text);
        return true;
    }

    public CharSequence getTextBeforeCursor(int length) {
        final Editable content = getEditable();
        if (content == null) return null;

        int a = Selection.getSelectionStart(content);
        int b = Selection.getSelectionEnd(content);

        if (a > b) {
            int tmp = a;
            a = b;
            b = tmp;
        }

        if (length > a) {
            length = a;
        }

        return content.subSequence(a - length, a);
    }

    public CharSequence getTextAfterCursor(int length) {
        final Editable content = getEditable();
        if (content == null) return null;

        int a = Selection.getSelectionStart(content);
        int b = Selection.getSelectionEnd(content);

        if (a > b) {
            int tmp = a;
            a = b;
            b = tmp;
        }

        if (b + length > content.length()) {
            length = content.length() - b;
        }

        return content.subSequence(b, b + length);
    }

    public int getCursorCapsMode(int reqModes) {
        final Editable content = getEditable();
        if (content == null) return 0;
        
        int a = Selection.getSelectionStart(content);
        int b = Selection.getSelectionEnd(content);

        if (a > b) {
            int tmp = a;
            a = b;
            b = tmp;
        }

        return TextUtils.getCapsMode(content, a, reqModes);
    }
    
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        if (mTextView != null) {
            ExtractedText et = new ExtractedText();
            if (mTextView.extractText(request, et)) {
                if ((flags&EXTRACTED_TEXT_MONITOR) != 0) {
                    mTextView.setExtracting(request);
                }
                return et;
            }
        }
        return null;
    }
    
    public boolean deleteSurroundingText(int leftLength, int rightLength) {
        if (DEBUG) Log.v(TAG, "deleteSurroundingText " + leftLength
                + " / " + rightLength);
        final Editable content = getEditable();
        if (content == null) return false;

        int a = Selection.getSelectionStart(content);
        int b = Selection.getSelectionEnd(content);

        if (a > b) {
            int tmp = a;
            a = b;
            b = tmp;
        }

        // ignore the composing text.
        int ca = content.getSpanStart(COMPOSING);
        int cb = content.getSpanEnd(COMPOSING);
        if (cb < ca) {
            int tmp = ca;
            ca = cb;
            cb = tmp;
        }
        if (ca != -1 && cb != -1) {
            if (ca < a) a = ca;
            if (cb > b) b = cb;
        }

        int deleted = 0;

        if (leftLength > 0) {
            int start = a - leftLength;
            if (start < 0) start = 0;
            content.delete(start, a);
            deleted = a - start;
        }

        if (rightLength > 0) {
            b = b - deleted;

            int end = b + rightLength;
            if (end > content.length()) end = content.length();

            content.delete(b, end);
        }
        
        return true;
    }

    public boolean beginBatchEdit() {
        if (mTextView == null) return false;
        mTextView.onBeginBatchEdit();
        return true;
    }
    
    public boolean endBatchEdit() {
        if (mTextView == null) return false;
        mTextView.onEndBatchEdit();
        return true;
    }
    
    public boolean clearMetaKeyStates(int states) {
        final Editable content = getEditable();
        if (content == null) return false;
        KeyListener kl = mTextView.getKeyListener();
        if (kl != null) kl.clearMetaKeyState(mTextView, content, states);
        return true;
    }
    
    public boolean performPrivateCommand(String action, Bundle data) {
        if (mTextView == null) return false;
        mTextView.onPrivateIMECommand(action, data);
        return true;
    }
    
    private Editable getEditable() {
        TextView tv = mTextView;
        if (tv != null) {
            return tv.getEditableText();
        }
        return null;
    }
    
    private void replaceText(CharSequence text, int newCursorPosition,
            boolean composing) {
        final Editable content = getEditable();
        
        // delete composing text set previously.
        int a = content.getSpanStart(COMPOSING);
        int b = content.getSpanEnd(COMPOSING);

        if (DEBUG) Log.v(TAG, "Composing span: " + a + " to " + b);
        
        if (b < a) {
            int tmp = a;
            a = b;
            b = tmp;
        }

        if (a != -1 && b != -1) {
            removeComposingSpans(content);
        } else {
            a = Selection.getSelectionStart(content);
            b = Selection.getSelectionEnd(content);
            if (a >=0 && b>= 0 && a != b) {
                if (b < a) {
                    int tmp = a;
                    a = b;
                    b = tmp;
                }
            }
        }

        if (composing) {
            Spannable sp = null;
            if (!(text instanceof Spannable)) {
                sp = new SpannableStringBuilder(text);
                text = sp;
                if (mDefaultComposingSpans == null) {
                    TypedArray ta = mTextView.getContext().getTheme()
                            .obtainStyledAttributes(new int[] {
                                    com.android.internal.R.attr.candidatesTextStyleSpans
                            });
                    CharSequence style = ta.getText(0);
                    ta.recycle();
                    if (style != null && style instanceof Spanned) {
                        mDefaultComposingSpans = ((Spanned)style).getSpans(
                                0, style.length(), Object.class);
                    }
                }
                if (mDefaultComposingSpans != null) {
                    for (int i = 0; i < mDefaultComposingSpans.length; ++i) {
                        sp.setSpan(mDefaultComposingSpans[i], 0, sp.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            } else {
                sp = (Spannable)text;
            }
            setComposingSpans(sp);
        }
        
        // Adjust newCursorPosition to be relative the start of the text.
        newCursorPosition += a;

        if (DEBUG) Log.v(TAG, "Replacing from " + a + " to " + b + " with \""
                + text + "\", composing=" + composing
                + ", type=" + text.getClass().getCanonicalName());
        
        if (DEBUG) {
            LogPrinter lp = new LogPrinter(Log.VERBOSE, TAG);
            lp.println("Current text:");
            TextUtils.dumpSpans(content, lp, "  ");
            lp.println("Composing text:");
            TextUtils.dumpSpans(text, lp, "  ");
        }
        
        content.replace(a, b, text);
        if (newCursorPosition < 0) newCursorPosition = 0;
        if (newCursorPosition > content.length())
            newCursorPosition = content.length();
        Selection.setSelection(content, newCursorPosition);
        
        if (DEBUG) {
            LogPrinter lp = new LogPrinter(Log.VERBOSE, TAG);
            lp.println("Final text:");
            TextUtils.dumpSpans(content, lp, "  ");
        }
    }
}

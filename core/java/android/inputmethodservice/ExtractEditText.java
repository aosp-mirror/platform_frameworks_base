/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.inputmethodservice;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/***
 * Specialization of {@link EditText} for showing and interacting with the
 * extracted text in a full-screen input method.
 */
public class ExtractEditText extends EditText {
    private InputMethodService mIME;
    private int mSettingExtractedText;
    
    public ExtractEditText(Context context) {
        super(context, null);
    }

    public ExtractEditText(Context context, AttributeSet attrs) {
        super(context, attrs, com.android.internal.R.attr.editTextStyle);
    }

    public ExtractEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ExtractEditText(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    
    void setIME(InputMethodService ime) {
        mIME = ime;
    }
    
    /**
     * Start making changes that will not be reported to the client.  That
     * is, {@link #onSelectionChanged(int, int)} will not result in sending
     * the new selection to the client
     */
    public void startInternalChanges() {
        mSettingExtractedText += 1;
    }
    
    /**
     * Finish making changes that will not be reported to the client.  That
     * is, {@link #onSelectionChanged(int, int)} will not result in sending
     * the new selection to the client
     */
    public void finishInternalChanges() {
        mSettingExtractedText -= 1;
    }
    
    /**
     * Implement just to keep track of when we are setting text from the
     * client (vs. seeing changes in ourself from the user).
     */
    @Override public void setExtractedText(ExtractedText text) {
        try {
            mSettingExtractedText++;
            super.setExtractedText(text);
        } finally {
            mSettingExtractedText--;
        }
    }
    
    /**
     * Report to the underlying text editor about selection changes.
     */
    @Override protected void onSelectionChanged(int selStart, int selEnd) {
        if (mSettingExtractedText == 0 && mIME != null && selStart >= 0 && selEnd >= 0) {
            mIME.onExtractedSelectionChanged(selStart, selEnd);
        }
    }
    
    /**
     * Redirect clicks to the IME for handling there.  First allows any
     * on click handler to run, though.
     */
    @Override public boolean performClick() {
        if (!super.performClick() && mIME != null) {
            mIME.onExtractedTextClicked();
            return true;
        }
        return false;
    }
    
    @Override public boolean onTextContextMenuItem(int id) {
        if (mIME != null && mIME.onExtractTextContextMenuItem(id)) {
            // Mode was started on Extracted, needs to be stopped here.
            // Cut and paste will change the text, which stops selection mode.
            if (id == android.R.id.copy) stopSelectionActionMode();
            return true;
        }
        return super.onTextContextMenuItem(id);
    }
    
    /**
     * We are always considered to be an input method target.
     */
    @Override
    public boolean isInputMethodTarget() {
        return true;
    }
    
    /**
     * Return true if the edit text is currently showing a scroll bar.
     */
    public boolean hasVerticalScrollBar() {
        return computeVerticalScrollRange() > computeVerticalScrollExtent();
    }
    
    /**
     * Pretend like the window this view is in always has focus, so its
     * highlight and cursor will be displayed.
     */
    @Override public boolean hasWindowFocus() {
        return this.isEnabled();
    }

    /**
     * Pretend like this view always has focus, so its
     * highlight and cursor will be displayed.
     */
    @Override public boolean isFocused() {
        return this.isEnabled();
    }

    /**
     * Pretend like this view always has focus, so its
     * highlight and cursor will be displayed.
     */
    @Override public boolean hasFocus() {
        return this.isEnabled();
    }

    /**
     * @hide
     */
    @Override protected void viewClicked(InputMethodManager imm) {
        // As an instance of this class is supposed to be owned by IMS,
        // and it has a reference to the IMS (the current IME),
        // we just need to call back its onViewClicked() here.
        // It should be good to avoid unnecessary IPCs by doing this as well.
        if (mIME != null) {
            mIME.onViewClicked(false);
        }
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @Override
    protected void deleteText_internal(int start, int end) {
        // Do not call the super method.
        // This will change the source TextView instead, which will update the ExtractTextView.
        mIME.onExtractedDeleteText(start, end);
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @Override
    protected void replaceText_internal(int start, int end, CharSequence text) {
        // Do not call the super method.
        // This will change the source TextView instead, which will update the ExtractTextView.
        mIME.onExtractedReplaceText(start, end, text);
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @Override
    protected void setSpan_internal(Object span, int start, int end, int flags) {
        // Do not call the super method.
        // This will change the source TextView instead, which will update the ExtractTextView.
        mIME.onExtractedSetSpan(span, start, end, flags);
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @Override
    protected void setCursorPosition_internal(int start, int end) {
        // Do not call the super method.
        // This will change the source TextView instead, which will update the ExtractTextView.
        mIME.onExtractedSelectionChanged(start, end);
    }
}

package android.inputmethodservice;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.ExtractedText;
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

    public ExtractEditText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
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
        if (mIME != null) {
            if (mIME.onExtractTextContextMenuItem(id)) {
                return true;
            }
        }
        return super.onTextContextMenuItem(id);
    }
    
    /**
     * We are always considered to be an input method target.
     */
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
        return this.isEnabled() ? true : false;
    }

    /**
     * Pretend like this view always has focus, so its
     * highlight and cursor will be displayed.
     */
    @Override public boolean isFocused() {
        return this.isEnabled() ? true : false;
    }

    /**
     * Pretend like this view always has focus, so its
     * highlight and cursor will be displayed.
     */
    @Override public boolean hasFocus() {
        return this.isEnabled() ? true : false;
    }
}

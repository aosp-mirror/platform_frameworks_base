package android.inputmethodservice;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Button;

/***
 * Specialization of {@link Button} that ignores the window not being focused.
 */
class ExtractButton extends Button {
    public ExtractButton(Context context) {
        super(context, null);
    }

    public ExtractButton(Context context, AttributeSet attrs) {
        super(context, attrs, com.android.internal.R.attr.buttonStyle);
    }

    public ExtractButton(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }
    
    /**
     * Pretend like the window this view is in always has focus, so it will
     * highlight when selected.
     */
    @Override public boolean hasWindowFocus() {
        return this.isEnabled() ? true : false;
    }
}

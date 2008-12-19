package android.inputmethodservice;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;

/***
 * Specialization of {@link EditText} for showing and interacting with the
 * extracted text in a full-screen input method.
 */
public class ExtractEditText extends EditText {
    public ExtractEditText(Context context) {
        super(context, null);
    }

    public ExtractEditText(Context context, AttributeSet attrs) {
        super(context, attrs, com.android.internal.R.attr.editTextStyle);
    }

    public ExtractEditText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }
}

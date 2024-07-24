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

package android.widget;

import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Build;
import android.text.Editable;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.method.ArrowKeyMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.SpanUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;

import com.android.internal.R;

/*
 * This is supposed to be a *very* thin veneer over TextView.
 * Do not make any changes here that do anything that a TextView
 * with a key listener and a movement method wouldn't do!
 */

/**
 * A user interface element for entering and modifying text.
 * When you define an edit text widget, you must specify the
 * {@link android.R.styleable#TextView_inputType}
 * attribute. For example, for plain text input set inputType to "text":
 * <p>
 * <pre>
 * &lt;EditText
 *     android:id="@+id/plain_text_input"
 *     android:layout_height="wrap_content"
 *     android:layout_width="match_parent"
 *     android:inputType="text"/&gt;</pre>
 *
 * Choosing the input type configures the keyboard type that is shown, acceptable characters,
 * and appearance of the edit text.
 * For example, if you want to accept a secret number, like a unique pin or serial number,
 * you can set inputType to "numericPassword".
 * An inputType of "numericPassword" results in an edit text that accepts numbers only,
 * shows a numeric keyboard when focused, and masks the text that is entered for privacy.
 * <p>
 * See the <a href="{@docRoot}guide/topics/ui/controls/text.html">Text Fields</a>
 * guide for examples of other
 * {@link android.R.styleable#TextView_inputType} settings.
 * </p>
 * <p>You also can receive callbacks as a user changes text by
 * adding a {@link android.text.TextWatcher} to the edit text.
 * This is useful when you want to add auto-save functionality as changes are made,
 * or validate the format of user input, for example.
 * You add a text watcher using the {@link TextView#addTextChangedListener} method.
 * </p>
 * <p>
 * This widget does not support auto-sizing text.
 * <p>
 * <b>XML attributes</b>
 * <p>
 * See {@link android.R.styleable#EditText EditText Attributes},
 * {@link android.R.styleable#TextView TextView Attributes},
 * {@link android.R.styleable#View View Attributes}
 *
 * @attr ref android.R.styleable#EditText_enableTextStylingShortcuts
 */
public class EditText extends TextView {

    // True if the style shortcut is enabled.
    private boolean mStyleShortcutsEnabled = false;

    private static final int ID_BOLD = android.R.id.bold;
    private static final int ID_ITALIC = android.R.id.italic;
    private static final int ID_UNDERLINE = android.R.id.underline;

    /** @hide */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public static final long LINE_HEIGHT_FOR_LOCALE = 303326708L;

    public EditText(Context context) {
        this(context, null);
    }

    public EditText(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.editTextStyle);
    }

    public EditText(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public EditText(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final Resources.Theme theme = context.getTheme();
        final TypedArray a = theme.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.EditText, defStyleAttr, defStyleRes);

        try {
            final int n = a.getIndexCount();
            for (int i = 0; i < n; ++i) {
                int attr = a.getIndex(i);
                switch (attr) {
                    case com.android.internal.R.styleable.EditText_enableTextStylingShortcuts:
                        mStyleShortcutsEnabled = a.getBoolean(attr, false);
                        break;
                }
            }
        } finally {
            a.recycle();
        }

        boolean hasUseLocalePreferredLineHeightForMinimumInt = false;
        boolean useLocalePreferredLineHeightForMinimumInt = false;
        TypedArray tvArray = theme.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.TextView, defStyleAttr, defStyleRes);
        try {
            hasUseLocalePreferredLineHeightForMinimumInt =
                    tvArray.hasValue(R.styleable.TextView_useLocalePreferredLineHeightForMinimum);
            if (hasUseLocalePreferredLineHeightForMinimumInt) {
                useLocalePreferredLineHeightForMinimumInt = tvArray.getBoolean(
                        R.styleable.TextView_useLocalePreferredLineHeightForMinimum, false);
            }
        } finally {
            tvArray.recycle();
        }
        if (!hasUseLocalePreferredLineHeightForMinimumInt) {
            useLocalePreferredLineHeightForMinimumInt =
                    CompatChanges.isChangeEnabled(LINE_HEIGHT_FOR_LOCALE);
        }
        setLocalePreferredLineHeightForMinimumUsed(useLocalePreferredLineHeightForMinimumInt);
    }

    @Override
    public boolean getFreezesText() {
        return true;
    }

    @Override
    protected boolean getDefaultEditable() {
        return true;
    }

    @Override
    protected MovementMethod getDefaultMovementMethod() {
        return ArrowKeyMovementMethod.getInstance();
    }

    @Override
    public Editable getText() {
        CharSequence text = super.getText();
        // This can only happen during construction.
        if (text == null) {
            return null;
        }
        if (text instanceof Editable) {
            return (Editable) text;
        }
        super.setText(text, BufferType.EDITABLE);
        return (Editable) super.getText();
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, BufferType.EDITABLE);
    }

    /**
     * Convenience for {@link Selection#setSelection(Spannable, int, int)}.
     */
    public void setSelection(int start, int stop) {
        Selection.setSelection(getText(), start, stop);
    }

    /**
     * Convenience for {@link Selection#setSelection(Spannable, int)}.
     */
    public void setSelection(int index) {
        Selection.setSelection(getText(), index);
    }

    /**
     * Convenience for {@link Selection#selectAll}.
     */
    public void selectAll() {
        Selection.selectAll(getText());
    }

    /**
     * Convenience for {@link Selection#extendSelection}.
     */
    public void extendSelection(int index) {
        Selection.extendSelection(getText(), index);
    }

    /**
     * Causes words in the text that are longer than the view's width to be ellipsized instead of
     * broken in the middle. {@link TextUtils.TruncateAt#MARQUEE
     * TextUtils.TruncateAt#MARQUEE} is not supported.
     *
     * @param ellipsis Type of ellipsis to be applied.
     * @throws IllegalArgumentException When the value of <code>ellipsis</code> parameter is
     *      {@link TextUtils.TruncateAt#MARQUEE}.
     * @see TextView#setEllipsize(TextUtils.TruncateAt)
     */
    @Override
    public void setEllipsize(TextUtils.TruncateAt ellipsis) {
        if (ellipsis == TextUtils.TruncateAt.MARQUEE) {
            throw new IllegalArgumentException("EditText cannot use the ellipsize mode "
                    + "TextUtils.TruncateAt.MARQUEE");
        }
        super.setEllipsize(ellipsis);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return EditText.class.getName();
    }

    /** @hide */
    @Override
    protected boolean supportsAutoSizeText() {
        return false;
    }

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        if (event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            // Handle Ctrl-only shortcuts.
            switch (keyCode) {
                case KeyEvent.KEYCODE_B:
                    if (mStyleShortcutsEnabled && hasSelection()) {
                        return onTextContextMenuItem(ID_BOLD);
                    }
                    break;
                case KeyEvent.KEYCODE_I:
                    if (mStyleShortcutsEnabled && hasSelection()) {
                        return onTextContextMenuItem(ID_ITALIC);
                    }
                    break;
                case KeyEvent.KEYCODE_U:
                    if (mStyleShortcutsEnabled && hasSelection()) {
                        return onTextContextMenuItem(ID_UNDERLINE);
                    }
                    break;
            }
        }
        return super.onKeyShortcut(keyCode, event);
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        // TODO: Move to switch-case once the resource ID is finalized.
        if (id == ID_BOLD || id == ID_ITALIC || id == ID_UNDERLINE) {
            return performStylingAction(id);
        }
        return super.onTextContextMenuItem(id);
    }

    private boolean performStylingAction(int actionId) {
        final int selectionStart = getSelectionStart();
        final int selectionEnd = getSelectionEnd();
        if (selectionStart < 0 || selectionEnd < 0) {
            return false;  // There is no selection.
        }
        int min = Math.min(selectionStart, selectionEnd);
        int max = Math.max(selectionStart, selectionEnd);


        Spannable spannable = getText();
        if (actionId == ID_BOLD) {
            return SpanUtils.toggleBold(spannable, min, max);
        } else if (actionId == ID_ITALIC) {
            return SpanUtils.toggleItalic(spannable, min, max);
        } else if (actionId == ID_UNDERLINE) {
            return SpanUtils.toggleUnderline(spannable, min, max);
        }

        return false;
    }

    /**
     * Enables styls shortcuts, e.g. Ctrl+B for making text bold.
     *
     * @param enabled true for enabled, false for disabled.
     */
    public void setStyleShortcutsEnabled(boolean enabled) {
        mStyleShortcutsEnabled = enabled;
    }

    /**
     * Return true if style shortcut is enabled, otherwise returns false.
     * @return true if style shortcut is enabled, otherwise returns false.
     */
    public boolean isStyleShortcutEnabled() {
        return mStyleShortcutsEnabled;
    }
}

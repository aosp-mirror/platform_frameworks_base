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
 * limitations under the License
 */
package androidx.wear.ble.view;

import android.annotation.TargetApi;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import androidx.annotation.StyleRes;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Space;
import android.widget.TextView;

import com.android.packageinstaller.R;

/**
 * A dialog to display a title, a message, and/or an icon with a positive and a negative button.
 *
 * <p>The buttons are hidden away unless there is a listener attached to the button. Since there's
 * no click listener attached by default, the buttons are hidden be default.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class AcceptDenyDialog extends Dialog {
    /** Icon at the top of the dialog. */
    protected ImageView mIcon;
    /** Title at the top of the dialog. */
    protected TextView mTitle;
    /** Message content of the dialog. */
    protected TextView mMessage;
    /** Panel containing the buttons. */
    protected View mButtonPanel;
    /** Positive button in the button panel. */
    protected ImageButton mPositiveButton;
    /** Negative button in the button panel. */
    protected ImageButton mNegativeButton;
    /**
     * Click listener for the positive button. Positive button should hide if this is <code>null
     * </code>.
     */
    protected DialogInterface.OnClickListener mPositiveButtonListener;
    /**
     * Click listener for the negative button. Negative button should hide if this is <code>null
     * </code>.
     */
    protected DialogInterface.OnClickListener mNegativeButtonListener;
    /** Spacer between the positive and negative button. Hidden if one button is hidden. */
    protected View mSpacer;

    private final View.OnClickListener mButtonHandler = (v) -> {
        if (v == mPositiveButton && mPositiveButtonListener != null) {
            mPositiveButtonListener.onClick(this, DialogInterface.BUTTON_POSITIVE);
            dismiss();
        } else if (v == mNegativeButton && mNegativeButtonListener != null) {
            mNegativeButtonListener.onClick(this, DialogInterface.BUTTON_NEGATIVE);
            dismiss();
        }
    };

    public AcceptDenyDialog(Context context) {
        this(context, 0 /* use default context theme */);
    }

    public AcceptDenyDialog(Context context, @StyleRes int themeResId) {
        super(context, themeResId);

        setContentView(R.layout.accept_deny_dialog);

        mTitle = (TextView) findViewById(android.R.id.title);
        mMessage = (TextView) findViewById(android.R.id.message);
        mIcon = (ImageView) findViewById(android.R.id.icon);
        mPositiveButton = (ImageButton) findViewById(android.R.id.button1);
        mPositiveButton.setOnClickListener(mButtonHandler);
        mNegativeButton = (ImageButton) findViewById(android.R.id.button2);
        mNegativeButton.setOnClickListener(mButtonHandler);
        mSpacer = (Space) findViewById(R.id.spacer);
        mButtonPanel = findViewById(R.id.buttonPanel);
    }

    public ImageButton getButton(int whichButton) {
        switch (whichButton) {
            case DialogInterface.BUTTON_POSITIVE:
                return mPositiveButton;
            case DialogInterface.BUTTON_NEGATIVE:
                return mNegativeButton;
            default:
                return null;
        }
    }

    public void setIcon(Drawable icon) {
        mIcon.setVisibility(icon == null ? View.GONE : View.VISIBLE);
        mIcon.setImageDrawable(icon);
    }

    /**
     * @param resId the resourceId of the drawable to use as the icon or 0 if you don't want an icon.
     */
    public void setIcon(int resId) {
        mIcon.setVisibility(resId == 0 ? View.GONE : View.VISIBLE);
        mIcon.setImageResource(resId);
    }

    /** @param message the content message text of the dialog. */
    public void setMessage(CharSequence message) {
        mMessage.setText(message);
        mMessage.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    /** @param title the title text of the dialog. */
    @Override
    public void setTitle(CharSequence title) {
        mTitle.setText(title);
    }

    /**
     * Sets a click listener for a button.
     *
     * <p>Will hide button bar if all buttons are hidden (i.e. their click listeners are <code>null
     * </code>).
     *
     * @param whichButton {@link DialogInterface.BUTTON_POSITIVE} or {@link
     *     DialogInterface.BUTTON_NEGATIVE}
     * @param listener the listener to set for the button. Hide button if <code>null</code>.
     */
    public void setButton(int whichButton, DialogInterface.OnClickListener listener) {
        switch (whichButton) {
            case DialogInterface.BUTTON_POSITIVE:
                mPositiveButtonListener = listener;
                break;
            case DialogInterface.BUTTON_NEGATIVE:
                mNegativeButtonListener = listener;
                break;
            default:
                return;
        }

        mSpacer.setVisibility(mPositiveButtonListener == null || mNegativeButtonListener == null
                ? View.GONE : View.INVISIBLE);
        mPositiveButton.setVisibility(
                mPositiveButtonListener == null ? View.GONE : View.VISIBLE);
        mNegativeButton.setVisibility(
                mNegativeButtonListener == null ? View.GONE : View.VISIBLE);
        mButtonPanel.setVisibility(
                mPositiveButtonListener == null && mNegativeButtonListener == null
                ? View.GONE : View.VISIBLE);
    }

    /**
     * Convenience method for <code>setButton(DialogInterface.BUTTON_POSITIVE, listener)</code>.
     *
     * @param listener the listener for the positive button.
     */
    public void setPositiveButton(DialogInterface.OnClickListener listener) {
        setButton(DialogInterface.BUTTON_POSITIVE, listener);
    }

    /**
     * Convenience method for <code>setButton(DialogInterface.BUTTON_NEGATIVE, listener)</code>.
     *
     * @param listener the listener for the positive button.
     */
    public void setNegativeButton(DialogInterface.OnClickListener listener) {
        setButton(DialogInterface.BUTTON_NEGATIVE, listener);
    }
}

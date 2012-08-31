/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import com.android.internal.app.AlertController;

import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Message;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;

/**
 * A subclass of Dialog that can display one, two or three buttons. If you only want to
 * display a String in this dialog box, use the setMessage() method.  If you
 * want to display a more complex view, look up the FrameLayout called "custom"
 * and add your view to it:
 *
 * <pre>
 * FrameLayout fl = (FrameLayout) findViewById(android.R.id.custom);
 * fl.addView(myView, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
 * </pre>
 * 
 * <p>The AlertDialog class takes care of automatically setting
 * {@link WindowManager.LayoutParams#FLAG_ALT_FOCUSABLE_IM
 * WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM} for you based on whether
 * any views in the dialog return true from {@link View#onCheckIsTextEditor()
 * View.onCheckIsTextEditor()}.  Generally you want this set for a Dialog
 * without text editors, so that it will be placed on top of the current
 * input method UI.  You can modify this behavior by forcing the flag to your
 * desired mode after calling {@link #onCreate}.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating dialogs, read the
 * <a href="{@docRoot}guide/topics/ui/dialogs.html">Dialogs</a> developer guide.</p>
 * </div>
 */
public class AlertDialog extends Dialog implements DialogInterface {
    private AlertController mAlert;

    /**
     * Special theme constant for {@link #AlertDialog(Context, int)}: use
     * the traditional (pre-Holo) alert dialog theme.
     */
    public static final int THEME_TRADITIONAL = 1;
    
    /**
     * Special theme constant for {@link #AlertDialog(Context, int)}: use
     * the holographic alert theme with a dark background.
     */
    public static final int THEME_HOLO_DARK = 2;
    
    /**
     * Special theme constant for {@link #AlertDialog(Context, int)}: use
     * the holographic alert theme with a light background.
     */
    public static final int THEME_HOLO_LIGHT = 3;

    /**
     * Special theme constant for {@link #AlertDialog(Context, int)}: use
     * the device's default alert theme with a dark background.
     */
    public static final int THEME_DEVICE_DEFAULT_DARK = 4;

    /**
     * Special theme constant for {@link #AlertDialog(Context, int)}: use
     * the device's default alert theme with a dark background.
     */
    public static final int THEME_DEVICE_DEFAULT_LIGHT = 5;
    
    protected AlertDialog(Context context) {
        this(context, resolveDialogTheme(context, 0), true);
    }

    /**
     * Construct an AlertDialog that uses an explicit theme.  The actual style
     * that an AlertDialog uses is a private implementation, however you can
     * here supply either the name of an attribute in the theme from which
     * to get the dialog's style (such as {@link android.R.attr#alertDialogTheme}
     * or one of the constants {@link #THEME_TRADITIONAL},
     * {@link #THEME_HOLO_DARK}, or {@link #THEME_HOLO_LIGHT}.
     */
    protected AlertDialog(Context context, int theme) {
        this(context, theme, true);
    }

    AlertDialog(Context context, int theme, boolean createThemeContextWrapper) {
        super(context, resolveDialogTheme(context, theme), createThemeContextWrapper);

        mWindow.alwaysReadCloseOnTouchAttr();
        mAlert = new AlertController(getContext(), this, getWindow());
    }

    protected AlertDialog(Context context, boolean cancelable, OnCancelListener cancelListener) {
        super(context, resolveDialogTheme(context, 0));
        mWindow.alwaysReadCloseOnTouchAttr();
        setCancelable(cancelable);
        setOnCancelListener(cancelListener);
        mAlert = new AlertController(context, this, getWindow());
    }

    static int resolveDialogTheme(Context context, int resid) {
        if (resid == THEME_TRADITIONAL) {
            return com.android.internal.R.style.Theme_Dialog_Alert;
        } else if (resid == THEME_HOLO_DARK) {
            return com.android.internal.R.style.Theme_Holo_Dialog_Alert;
        } else if (resid == THEME_HOLO_LIGHT) {
            return com.android.internal.R.style.Theme_Holo_Light_Dialog_Alert;
        } else if (resid == THEME_DEVICE_DEFAULT_DARK) {
            return com.android.internal.R.style.Theme_DeviceDefault_Dialog_Alert;
        } else if (resid == THEME_DEVICE_DEFAULT_LIGHT) {
            return com.android.internal.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        } else if (resid >= 0x01000000) {   // start of real resource IDs.
            return resid;
        } else {
            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(com.android.internal.R.attr.alertDialogTheme,
                    outValue, true);
            return outValue.resourceId;
        }
    }

    /**
     * Gets one of the buttons used in the dialog.
     * <p>
     * If a button does not exist in the dialog, null will be returned.
     * 
     * @param whichButton The identifier of the button that should be returned.
     *            For example, this can be
     *            {@link DialogInterface#BUTTON_POSITIVE}.
     * @return The button from the dialog, or null if a button does not exist.
     */
    public Button getButton(int whichButton) {
        return mAlert.getButton(whichButton);
    }
    
    /**
     * Gets the list view used in the dialog.
     *  
     * @return The {@link ListView} from the dialog.
     */
    public ListView getListView() {
        return mAlert.getListView();
    }
    
    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        mAlert.setTitle(title);
    }

    /**
     * @see Builder#setCustomTitle(View)
     */
    public void setCustomTitle(View customTitleView) {
        mAlert.setCustomTitle(customTitleView);
    }
    
    public void setMessage(CharSequence message) {
        mAlert.setMessage(message);
    }

    /**
     * Set the view to display in that dialog.
     */
    public void setView(View view) {
        mAlert.setView(view);
    }
    
    /**
     * Set the view to display in that dialog, specifying the spacing to appear around that 
     * view.
     *
     * @param view The view to show in the content area of the dialog
     * @param viewSpacingLeft Extra space to appear to the left of {@code view}
     * @param viewSpacingTop Extra space to appear above {@code view}
     * @param viewSpacingRight Extra space to appear to the right of {@code view}
     * @param viewSpacingBottom Extra space to appear below {@code view}
     */
    public void setView(View view, int viewSpacingLeft, int viewSpacingTop, int viewSpacingRight,
            int viewSpacingBottom) {
        mAlert.setView(view, viewSpacingLeft, viewSpacingTop, viewSpacingRight, viewSpacingBottom);
    }

    /**
     * Set a message to be sent when a button is pressed.
     * 
     * @param whichButton Which button to set the message for, can be one of
     *            {@link DialogInterface#BUTTON_POSITIVE},
     *            {@link DialogInterface#BUTTON_NEGATIVE}, or
     *            {@link DialogInterface#BUTTON_NEUTRAL}
     * @param text The text to display in positive button.
     * @param msg The {@link Message} to be sent when clicked.
     */
    public void setButton(int whichButton, CharSequence text, Message msg) {
        mAlert.setButton(whichButton, text, null, msg);
    }
    
    /**
     * Set a listener to be invoked when the positive button of the dialog is pressed.
     * 
     * @param whichButton Which button to set the listener on, can be one of
     *            {@link DialogInterface#BUTTON_POSITIVE},
     *            {@link DialogInterface#BUTTON_NEGATIVE}, or
     *            {@link DialogInterface#BUTTON_NEUTRAL}
     * @param text The text to display in positive button.
     * @param listener The {@link DialogInterface.OnClickListener} to use.
     */
    public void setButton(int whichButton, CharSequence text, OnClickListener listener) {
        mAlert.setButton(whichButton, text, listener, null);
    }

    /**
     * @deprecated Use {@link #setButton(int, CharSequence, Message)} with
     *             {@link DialogInterface#BUTTON_POSITIVE}.
     */
    @Deprecated
    public void setButton(CharSequence text, Message msg) {
        setButton(BUTTON_POSITIVE, text, msg);
    }
        
    /**
     * @deprecated Use {@link #setButton(int, CharSequence, Message)} with
     *             {@link DialogInterface#BUTTON_NEGATIVE}.
     */
    @Deprecated
    public void setButton2(CharSequence text, Message msg) {
        setButton(BUTTON_NEGATIVE, text, msg);
    }

    /**
     * @deprecated Use {@link #setButton(int, CharSequence, Message)} with
     *             {@link DialogInterface#BUTTON_NEUTRAL}.
     */
    @Deprecated
    public void setButton3(CharSequence text, Message msg) {
        setButton(BUTTON_NEUTRAL, text, msg);
    }

    /**
     * Set a listener to be invoked when button 1 of the dialog is pressed.
     * 
     * @param text The text to display in button 1.
     * @param listener The {@link DialogInterface.OnClickListener} to use.
     * @deprecated Use
     *             {@link #setButton(int, CharSequence, android.content.DialogInterface.OnClickListener)}
     *             with {@link DialogInterface#BUTTON_POSITIVE}
     */
    @Deprecated
    public void setButton(CharSequence text, final OnClickListener listener) {
        setButton(BUTTON_POSITIVE, text, listener);
    }

    /**
     * Set a listener to be invoked when button 2 of the dialog is pressed.
     * @param text The text to display in button 2.
     * @param listener The {@link DialogInterface.OnClickListener} to use.
     * @deprecated Use
     *             {@link #setButton(int, CharSequence, android.content.DialogInterface.OnClickListener)}
     *             with {@link DialogInterface#BUTTON_NEGATIVE}
     */
    @Deprecated
    public void setButton2(CharSequence text, final OnClickListener listener) {
        setButton(BUTTON_NEGATIVE, text, listener);
    }

    /**
     * Set a listener to be invoked when button 3 of the dialog is pressed.
     * @param text The text to display in button 3.
     * @param listener The {@link DialogInterface.OnClickListener} to use.
     * @deprecated Use
     *             {@link #setButton(int, CharSequence, android.content.DialogInterface.OnClickListener)}
     *             with {@link DialogInterface#BUTTON_POSITIVE}
     */
    @Deprecated
    public void setButton3(CharSequence text, final OnClickListener listener) {
        setButton(BUTTON_NEUTRAL, text, listener);
    }

    /**
     * Set resId to 0 if you don't want an icon.
     * @param resId the resourceId of the drawable to use as the icon or 0
     * if you don't want an icon.
     */
    public void setIcon(int resId) {
        mAlert.setIcon(resId);
    }
    
    public void setIcon(Drawable icon) {
        mAlert.setIcon(icon);
    }

    /**
     * Set an icon as supplied by a theme attribute. e.g. android.R.attr.alertDialogIcon
     *
     * @param attrId ID of a theme attribute that points to a drawable resource.
     */
    public void setIconAttribute(int attrId) {
        TypedValue out = new TypedValue();
        mContext.getTheme().resolveAttribute(attrId, out, true);
        mAlert.setIcon(out.resourceId);
    }

    public void setInverseBackgroundForced(boolean forceInverseBackground) {
        mAlert.setInverseBackgroundForced(forceInverseBackground);
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAlert.installContent();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mAlert.onKeyDown(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mAlert.onKeyUp(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }
    
    public static class Builder {
        private final AlertController.AlertParams P;
        private int mTheme;
        
        /**
         * Constructor using a context for this builder and the {@link AlertDialog} it creates.
         */
        public Builder(Context context) {
            this(context, resolveDialogTheme(context, 0));
        }

        /**
         * Constructor using a context and theme for this builder and
         * the {@link AlertDialog} it creates.  The actual theme
         * that an AlertDialog uses is a private implementation, however you can
         * here supply either the name of an attribute in the theme from which
         * to get the dialog's style (such as {@link android.R.attr#alertDialogTheme}
         * or one of the constants
         * {@link AlertDialog#THEME_TRADITIONAL AlertDialog.THEME_TRADITIONAL},
         * {@link AlertDialog#THEME_HOLO_DARK AlertDialog.THEME_HOLO_DARK}, or
         * {@link AlertDialog#THEME_HOLO_LIGHT AlertDialog.THEME_HOLO_LIGHT}.
         */
        public Builder(Context context, int theme) {
            P = new AlertController.AlertParams(new ContextThemeWrapper(
                    context, resolveDialogTheme(context, theme)));
            mTheme = theme;
        }
        
        /**
         * Returns a {@link Context} with the appropriate theme for dialogs created by this Builder.
         * Applications should use this Context for obtaining LayoutInflaters for inflating views
         * that will be used in the resulting dialogs, as it will cause views to be inflated with
         * the correct theme.
         *
         * @return A Context for built Dialogs.
         */
        public Context getContext() {
            return P.mContext;
        }

        /**
         * Set the title using the given resource id.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setTitle(int titleId) {
            P.mTitle = P.mContext.getText(titleId);
            return this;
        }
        
        /**
         * Set the title displayed in the {@link Dialog}.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setTitle(CharSequence title) {
            P.mTitle = title;
            return this;
        }
        
        /**
         * Set the title using the custom view {@code customTitleView}. The
         * methods {@link #setTitle(int)} and {@link #setIcon(int)} should be
         * sufficient for most titles, but this is provided if the title needs
         * more customization. Using this will replace the title and icon set
         * via the other methods.
         * 
         * @param customTitleView The custom view to use as the title.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setCustomTitle(View customTitleView) {
            P.mCustomTitleView = customTitleView;
            return this;
        }
        
        /**
         * Set the message to display using the given resource id.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setMessage(int messageId) {
            P.mMessage = P.mContext.getText(messageId);
            return this;
        }
        
        /**
         * Set the message to display.
          *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setMessage(CharSequence message) {
            P.mMessage = message;
            return this;
        }
        
        /**
         * Set the resource id of the {@link Drawable} to be used in the title.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setIcon(int iconId) {
            P.mIconId = iconId;
            return this;
        }
        
        /**
         * Set the {@link Drawable} to be used in the title.
          *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setIcon(Drawable icon) {
            P.mIcon = icon;
            return this;
        }

        /**
         * Set an icon as supplied by a theme attribute. e.g. android.R.attr.alertDialogIcon
         *
         * @param attrId ID of a theme attribute that points to a drawable resource.
         */
        public Builder setIconAttribute(int attrId) {
            TypedValue out = new TypedValue();
            P.mContext.getTheme().resolveAttribute(attrId, out, true);
            P.mIconId = out.resourceId;
            return this;
        }

        /**
         * Set a listener to be invoked when the positive button of the dialog is pressed.
         * @param textId The resource id of the text to display in the positive button
         * @param listener The {@link DialogInterface.OnClickListener} to use.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setPositiveButton(int textId, final OnClickListener listener) {
            P.mPositiveButtonText = P.mContext.getText(textId);
            P.mPositiveButtonListener = listener;
            return this;
        }
        
        /**
         * Set a listener to be invoked when the positive button of the dialog is pressed.
         * @param text The text to display in the positive button
         * @param listener The {@link DialogInterface.OnClickListener} to use.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setPositiveButton(CharSequence text, final OnClickListener listener) {
            P.mPositiveButtonText = text;
            P.mPositiveButtonListener = listener;
            return this;
        }
        
        /**
         * Set a listener to be invoked when the negative button of the dialog is pressed.
         * @param textId The resource id of the text to display in the negative button
         * @param listener The {@link DialogInterface.OnClickListener} to use.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setNegativeButton(int textId, final OnClickListener listener) {
            P.mNegativeButtonText = P.mContext.getText(textId);
            P.mNegativeButtonListener = listener;
            return this;
        }
        
        /**
         * Set a listener to be invoked when the negative button of the dialog is pressed.
         * @param text The text to display in the negative button
         * @param listener The {@link DialogInterface.OnClickListener} to use.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setNegativeButton(CharSequence text, final OnClickListener listener) {
            P.mNegativeButtonText = text;
            P.mNegativeButtonListener = listener;
            return this;
        }
        
        /**
         * Set a listener to be invoked when the neutral button of the dialog is pressed.
         * @param textId The resource id of the text to display in the neutral button
         * @param listener The {@link DialogInterface.OnClickListener} to use.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setNeutralButton(int textId, final OnClickListener listener) {
            P.mNeutralButtonText = P.mContext.getText(textId);
            P.mNeutralButtonListener = listener;
            return this;
        }
        
        /**
         * Set a listener to be invoked when the neutral button of the dialog is pressed.
         * @param text The text to display in the neutral button
         * @param listener The {@link DialogInterface.OnClickListener} to use.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setNeutralButton(CharSequence text, final OnClickListener listener) {
            P.mNeutralButtonText = text;
            P.mNeutralButtonListener = listener;
            return this;
        }
        
        /**
         * Sets whether the dialog is cancelable or not.  Default is true.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setCancelable(boolean cancelable) {
            P.mCancelable = cancelable;
            return this;
        }
        
        /**
         * Sets the callback that will be called if the dialog is canceled.
         *
         * <p>Even in a cancelable dialog, the dialog may be dismissed for reasons other than
         * being canceled or one of the supplied choices being selected.
         * If you are interested in listening for all cases where the dialog is dismissed
         * and not just when it is canceled, see
         * {@link #setOnDismissListener(android.content.DialogInterface.OnDismissListener) setOnDismissListener}.</p>
         * @see #setCancelable(boolean)
         * @see #setOnDismissListener(android.content.DialogInterface.OnDismissListener)
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setOnCancelListener(OnCancelListener onCancelListener) {
            P.mOnCancelListener = onCancelListener;
            return this;
        }
        
        /**
         * Sets the callback that will be called when the dialog is dismissed for any reason.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setOnDismissListener(OnDismissListener onDismissListener) {
            P.mOnDismissListener = onDismissListener;
            return this;
        }

        /**
         * Sets the callback that will be called if a key is dispatched to the dialog.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setOnKeyListener(OnKeyListener onKeyListener) {
            P.mOnKeyListener = onKeyListener;
            return this;
        }
        
        /**
         * Set a list of items to be displayed in the dialog as the content, you will be notified of the
         * selected item via the supplied listener. This should be an array type i.e. R.array.foo
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setItems(int itemsId, final OnClickListener listener) {
            P.mItems = P.mContext.getResources().getTextArray(itemsId);
            P.mOnClickListener = listener;
            return this;
        }
        
        /**
         * Set a list of items to be displayed in the dialog as the content, you will be notified of the
         * selected item via the supplied listener.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setItems(CharSequence[] items, final OnClickListener listener) {
            P.mItems = items;
            P.mOnClickListener = listener;
            return this;
        }
        
        /**
         * Set a list of items, which are supplied by the given {@link ListAdapter}, to be
         * displayed in the dialog as the content, you will be notified of the
         * selected item via the supplied listener.
         * 
         * @param adapter The {@link ListAdapter} to supply the list of items
         * @param listener The listener that will be called when an item is clicked.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setAdapter(final ListAdapter adapter, final OnClickListener listener) {
            P.mAdapter = adapter;
            P.mOnClickListener = listener;
            return this;
        }
        
        /**
         * Set a list of items, which are supplied by the given {@link Cursor}, to be
         * displayed in the dialog as the content, you will be notified of the
         * selected item via the supplied listener.
         * 
         * @param cursor The {@link Cursor} to supply the list of items
         * @param listener The listener that will be called when an item is clicked.
         * @param labelColumn The column name on the cursor containing the string to display
         *          in the label.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setCursor(final Cursor cursor, final OnClickListener listener,
                String labelColumn) {
            P.mCursor = cursor;
            P.mLabelColumn = labelColumn;
            P.mOnClickListener = listener;
            return this;
        }
        
        /**
         * Set a list of items to be displayed in the dialog as the content,
         * you will be notified of the selected item via the supplied listener.
         * This should be an array type, e.g. R.array.foo. The list will have
         * a check mark displayed to the right of the text for each checked
         * item. Clicking on an item in the list will not dismiss the dialog.
         * Clicking on a button will dismiss the dialog.
         * 
         * @param itemsId the resource id of an array i.e. R.array.foo
         * @param checkedItems specifies which items are checked. It should be null in which case no
         *        items are checked. If non null it must be exactly the same length as the array of
         *        items.
         * @param listener notified when an item on the list is clicked. The dialog will not be
         *        dismissed when an item is clicked. It will only be dismissed if clicked on a
         *        button, if no buttons are supplied it's up to the user to dismiss the dialog.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setMultiChoiceItems(int itemsId, boolean[] checkedItems, 
                final OnMultiChoiceClickListener listener) {
            P.mItems = P.mContext.getResources().getTextArray(itemsId);
            P.mOnCheckboxClickListener = listener;
            P.mCheckedItems = checkedItems;
            P.mIsMultiChoice = true;
            return this;
        }
        
        /**
         * Set a list of items to be displayed in the dialog as the content,
         * you will be notified of the selected item via the supplied listener.
         * The list will have a check mark displayed to the right of the text
         * for each checked item. Clicking on an item in the list will not
         * dismiss the dialog. Clicking on a button will dismiss the dialog.
         * 
         * @param items the text of the items to be displayed in the list.
         * @param checkedItems specifies which items are checked. It should be null in which case no
         *        items are checked. If non null it must be exactly the same length as the array of
         *        items.
         * @param listener notified when an item on the list is clicked. The dialog will not be
         *        dismissed when an item is clicked. It will only be dismissed if clicked on a
         *        button, if no buttons are supplied it's up to the user to dismiss the dialog.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setMultiChoiceItems(CharSequence[] items, boolean[] checkedItems, 
                final OnMultiChoiceClickListener listener) {
            P.mItems = items;
            P.mOnCheckboxClickListener = listener;
            P.mCheckedItems = checkedItems;
            P.mIsMultiChoice = true;
            return this;
        }
        
        /**
         * Set a list of items to be displayed in the dialog as the content,
         * you will be notified of the selected item via the supplied listener.
         * The list will have a check mark displayed to the right of the text
         * for each checked item. Clicking on an item in the list will not
         * dismiss the dialog. Clicking on a button will dismiss the dialog.
         * 
         * @param cursor the cursor used to provide the items.
         * @param isCheckedColumn specifies the column name on the cursor to use to determine
         *        whether a checkbox is checked or not. It must return an integer value where 1
         *        means checked and 0 means unchecked.
         * @param labelColumn The column name on the cursor containing the string to display in the
         *        label.
         * @param listener notified when an item on the list is clicked. The dialog will not be
         *        dismissed when an item is clicked. It will only be dismissed if clicked on a
         *        button, if no buttons are supplied it's up to the user to dismiss the dialog.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setMultiChoiceItems(Cursor cursor, String isCheckedColumn, String labelColumn, 
                final OnMultiChoiceClickListener listener) {
            P.mCursor = cursor;
            P.mOnCheckboxClickListener = listener;
            P.mIsCheckedColumn = isCheckedColumn;
            P.mLabelColumn = labelColumn;
            P.mIsMultiChoice = true;
            return this;
        }
        
        /**
         * Set a list of items to be displayed in the dialog as the content, you will be notified of
         * the selected item via the supplied listener. This should be an array type i.e.
         * R.array.foo The list will have a check mark displayed to the right of the text for the
         * checked item. Clicking on an item in the list will not dismiss the dialog. Clicking on a
         * button will dismiss the dialog.
         * 
         * @param itemsId the resource id of an array i.e. R.array.foo
         * @param checkedItem specifies which item is checked. If -1 no items are checked.
         * @param listener notified when an item on the list is clicked. The dialog will not be
         *        dismissed when an item is clicked. It will only be dismissed if clicked on a
         *        button, if no buttons are supplied it's up to the user to dismiss the dialog.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setSingleChoiceItems(int itemsId, int checkedItem, 
                final OnClickListener listener) {
            P.mItems = P.mContext.getResources().getTextArray(itemsId);
            P.mOnClickListener = listener;
            P.mCheckedItem = checkedItem;
            P.mIsSingleChoice = true;
            return this;
        }
        
        /**
         * Set a list of items to be displayed in the dialog as the content, you will be notified of
         * the selected item via the supplied listener. The list will have a check mark displayed to
         * the right of the text for the checked item. Clicking on an item in the list will not
         * dismiss the dialog. Clicking on a button will dismiss the dialog.
         * 
         * @param cursor the cursor to retrieve the items from.
         * @param checkedItem specifies which item is checked. If -1 no items are checked.
         * @param labelColumn The column name on the cursor containing the string to display in the
         *        label.
         * @param listener notified when an item on the list is clicked. The dialog will not be
         *        dismissed when an item is clicked. It will only be dismissed if clicked on a
         *        button, if no buttons are supplied it's up to the user to dismiss the dialog.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setSingleChoiceItems(Cursor cursor, int checkedItem, String labelColumn, 
                final OnClickListener listener) {
            P.mCursor = cursor;
            P.mOnClickListener = listener;
            P.mCheckedItem = checkedItem;
            P.mLabelColumn = labelColumn;
            P.mIsSingleChoice = true;
            return this;
        }
        
        /**
         * Set a list of items to be displayed in the dialog as the content, you will be notified of
         * the selected item via the supplied listener. The list will have a check mark displayed to
         * the right of the text for the checked item. Clicking on an item in the list will not
         * dismiss the dialog. Clicking on a button will dismiss the dialog.
         * 
         * @param items the items to be displayed.
         * @param checkedItem specifies which item is checked. If -1 no items are checked.
         * @param listener notified when an item on the list is clicked. The dialog will not be
         *        dismissed when an item is clicked. It will only be dismissed if clicked on a
         *        button, if no buttons are supplied it's up to the user to dismiss the dialog.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setSingleChoiceItems(CharSequence[] items, int checkedItem, final OnClickListener listener) {
            P.mItems = items;
            P.mOnClickListener = listener;
            P.mCheckedItem = checkedItem;
            P.mIsSingleChoice = true;
            return this;
        } 
        
        /**
         * Set a list of items to be displayed in the dialog as the content, you will be notified of
         * the selected item via the supplied listener. The list will have a check mark displayed to
         * the right of the text for the checked item. Clicking on an item in the list will not
         * dismiss the dialog. Clicking on a button will dismiss the dialog.
         * 
         * @param adapter The {@link ListAdapter} to supply the list of items
         * @param checkedItem specifies which item is checked. If -1 no items are checked.
         * @param listener notified when an item on the list is clicked. The dialog will not be
         *        dismissed when an item is clicked. It will only be dismissed if clicked on a
         *        button, if no buttons are supplied it's up to the user to dismiss the dialog.
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setSingleChoiceItems(ListAdapter adapter, int checkedItem, final OnClickListener listener) {
            P.mAdapter = adapter;
            P.mOnClickListener = listener;
            P.mCheckedItem = checkedItem;
            P.mIsSingleChoice = true;
            return this;
        }
        
        /**
         * Sets a listener to be invoked when an item in the list is selected.
         * 
         * @param listener The listener to be invoked.
         * @see AdapterView#setOnItemSelectedListener(android.widget.AdapterView.OnItemSelectedListener)
         *
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setOnItemSelectedListener(final AdapterView.OnItemSelectedListener listener) {
            P.mOnItemSelectedListener = listener;
            return this;
        }
        
        /**
         * Set a custom view to be the contents of the Dialog. If the supplied view is an instance
         * of a {@link ListView} the light background will be used.
         *
         * @param view The view to use as the contents of the Dialog.
         * 
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setView(View view) {
            P.mView = view;
            P.mViewSpacingSpecified = false;
            return this;
        }
        
        /**
         * Set a custom view to be the contents of the Dialog, specifying the
         * spacing to appear around that view. If the supplied view is an
         * instance of a {@link ListView} the light background will be used.
         * 
         * @param view The view to use as the contents of the Dialog.
         * @param viewSpacingLeft Spacing between the left edge of the view and
         *        the dialog frame
         * @param viewSpacingTop Spacing between the top edge of the view and
         *        the dialog frame
         * @param viewSpacingRight Spacing between the right edge of the view
         *        and the dialog frame
         * @param viewSpacingBottom Spacing between the bottom edge of the view
         *        and the dialog frame
         * @return This Builder object to allow for chaining of calls to set
         *         methods
         *         
         * 
         * This is currently hidden because it seems like people should just
         * be able to put padding around the view.
         * @hide
         */
        public Builder setView(View view, int viewSpacingLeft, int viewSpacingTop,
                int viewSpacingRight, int viewSpacingBottom) {
            P.mView = view;
            P.mViewSpacingSpecified = true;
            P.mViewSpacingLeft = viewSpacingLeft;
            P.mViewSpacingTop = viewSpacingTop;
            P.mViewSpacingRight = viewSpacingRight;
            P.mViewSpacingBottom = viewSpacingBottom;
            return this;
        }
        
        /**
         * Sets the Dialog to use the inverse background, regardless of what the
         * contents is.
         * 
         * @param useInverseBackground Whether to use the inverse background
         * 
         * @return This Builder object to allow for chaining of calls to set methods
         */
        public Builder setInverseBackgroundForced(boolean useInverseBackground) {
            P.mForceInverseBackground = useInverseBackground;
            return this;
        }

        /**
         * @hide
         */
        public Builder setRecycleOnMeasureEnabled(boolean enabled) {
            P.mRecycleOnMeasure = enabled;
            return this;
        }


        /**
         * Creates a {@link AlertDialog} with the arguments supplied to this builder. It does not
         * {@link Dialog#show()} the dialog. This allows the user to do any extra processing
         * before displaying the dialog. Use {@link #show()} if you don't have any other processing
         * to do and want this to be created and displayed.
         */
        public AlertDialog create() {
            final AlertDialog dialog = new AlertDialog(P.mContext, mTheme, false);
            P.apply(dialog.mAlert);
            dialog.setCancelable(P.mCancelable);
            if (P.mCancelable) {
                dialog.setCanceledOnTouchOutside(true);
            }
            dialog.setOnCancelListener(P.mOnCancelListener);
            dialog.setOnDismissListener(P.mOnDismissListener);
            if (P.mOnKeyListener != null) {
                dialog.setOnKeyListener(P.mOnKeyListener);
            }
            return dialog;
        }

        /**
         * Creates a {@link AlertDialog} with the arguments supplied to this builder and
         * {@link Dialog#show()}'s the dialog.
         */
        public AlertDialog show() {
            AlertDialog dialog = create();
            dialog.show();
            return dialog;
        }
    }
    
}

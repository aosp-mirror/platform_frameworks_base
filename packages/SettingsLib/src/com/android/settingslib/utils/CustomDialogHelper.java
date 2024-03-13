/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.settingslib.utils;
import android.annotation.IntDef;
import android.annotation.StringRes;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.settingslib.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class is used to create custom dialog with icon, title, message and custom view that are
 * horizontally centered.
 */
public class CustomDialogHelper {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ICON, TITLE, MESSAGE, LAYOUT, BACK_BUTTON, NEGATIVE_BUTTON, POSITIVE_BUTTON})
    public @interface LayoutComponent {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({BACK_BUTTON, NEGATIVE_BUTTON, POSITIVE_BUTTON})
    public @interface LayoutButton {}

    public static final int ICON = 0;
    public static final int TITLE = 1;
    public static final int MESSAGE = 2;
    public static final int LAYOUT = 3;
    public static final int BACK_BUTTON = 4;
    public static final int NEGATIVE_BUTTON = 5;
    public static final int POSITIVE_BUTTON = 6;
    private View mDialogContent;
    private Dialog mDialog;
    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private ImageView mDialogIcon;
    private TextView mDialogTitle;
    private TextView mDialogMessage;
    private LinearLayout mCustomLayout;
    private Button mPositiveButton;
    private Button mNegativeButton;
    private Button mBackButton;

    public CustomDialogHelper(Context context) {
        mContext = context;
        mLayoutInflater = LayoutInflater.from(context);
        mDialogContent = mLayoutInflater.inflate(R.layout.dialog_with_icon, null);
        mDialogIcon = mDialogContent.findViewById(R.id.dialog_with_icon_icon);
        mDialogTitle = mDialogContent.findViewById(R.id.dialog_with_icon_title);
        mDialogMessage = mDialogContent.findViewById(R.id.dialog_with_icon_message);
        mCustomLayout = mDialogContent.findViewById(R.id.custom_layout);
        mPositiveButton = mDialogContent.findViewById(R.id.button_ok);
        mNegativeButton = mDialogContent.findViewById(R.id.button_cancel);
        mBackButton = mDialogContent.findViewById(R.id.button_back);
        createDialog();
    }

    /**
     * Creates dialog with content defined in constructor.
     */
    private void createDialog() {
        mDialog = new AlertDialog.Builder(mContext)
                .setView(mDialogContent)
                .setCancelable(true)
                .create();
        mDialog.getWindow()
                .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    /**
     * Sets title and listener for positive button.
     */
    public CustomDialogHelper setPositiveButton(@StringRes int resid,
            View.OnClickListener onClickListener) {
        setButton(POSITIVE_BUTTON, resid, onClickListener);
        return this;
    }

    /**
     * Sets positive button text.
     */
    public CustomDialogHelper setPositiveButtonText(@StringRes int resid) {
        mPositiveButton.setText(resid);
        return this;
    }

    /**
     * Sets title and listener for negative button.
     */
    public CustomDialogHelper setNegativeButton(@StringRes int resid,
            View.OnClickListener onClickListener) {
        setButton(NEGATIVE_BUTTON, resid, onClickListener);
        return this;
    }

    /**
     * Sets negative button text.
     */
    public CustomDialogHelper setNegativeButtonText(@StringRes int resid) {
        mNegativeButton.setText(resid);
        return this;
    }

    /**
     * Sets title and listener for back button.
     */
    public CustomDialogHelper setBackButton(@StringRes int resid,
            View.OnClickListener onClickListener) {
        setButton(BACK_BUTTON, resid, onClickListener);
        return this;
    }

    /**
     * Sets title for back button.
     */
    public CustomDialogHelper setBackButtonText(@StringRes int resid) {
        mBackButton.setText(resid);
        return this;
    }

    private void setButton(@LayoutButton int whichButton, @StringRes int resid,
            View.OnClickListener listener) {
        switch (whichButton) {
            case POSITIVE_BUTTON :
                mPositiveButton.setText(resid);
                mPositiveButton.setVisibility(View.VISIBLE);
                mPositiveButton.setOnClickListener(listener);
                break;
            case NEGATIVE_BUTTON:
                mNegativeButton.setText(resid);
                mNegativeButton.setVisibility(View.VISIBLE);
                mNegativeButton.setOnClickListener(listener);
                break;
            case BACK_BUTTON:
                mBackButton.setText(resid);
                mBackButton.setVisibility(View.VISIBLE);
                mBackButton.setOnClickListener(listener);
                break;
            default:
                break;
        }
    }


    /**
     * Modifies state of button.
     * //TODO: modify method to allow setting state for any button.
     */
    public CustomDialogHelper setButtonEnabled(boolean enabled) {
        mPositiveButton.setEnabled(enabled);
        return this;
    }

    /**
     * Sets title of the dialog.
     */
    public CustomDialogHelper setTitle(@StringRes int resid) {
        mDialogTitle.setText(resid);
        return this;
    }

    /**
     * Sets message of the dialog.
     */
    public CustomDialogHelper setMessage(@StringRes int resid) {
        mDialogMessage.setText(resid);
        return this;
    }

    /**
     * Sets message padding of the dialog.
     */
    public CustomDialogHelper setMessagePadding(int dp) {
        mDialogMessage.setPadding(dp, dp, dp, dp);
        return this;
    }

    /**
     * Sets icon of the dialog.
     */
    public CustomDialogHelper setIcon(Drawable icon) {
        mDialogIcon.setImageDrawable(icon);
        return this;
    }

    /**
     * Removes all views that were previously added to the custom layout part.
     */
    public CustomDialogHelper clearCustomLayout() {
        mCustomLayout.removeAllViews();
        return this;
    }

    /**
     * Hides custom layout.
     */
    public void hideCustomLayout() {
        mCustomLayout.setVisibility(View.GONE);
    }

    /**
     * Shows custom layout.
     */
    public void showCustomLayout() {
        mCustomLayout.setVisibility(View.VISIBLE);
    }

    /**
     * Adds view to custom layout.
     */
    public CustomDialogHelper addCustomView(View view) {
        mCustomLayout.addView(view);
        return this;
    }

    /**
     * Returns dialog.
     */
    public Dialog getDialog() {
        return mDialog;
    }

    /**
     * Sets visibility of layout component.
     * @param element part of the layout visibility of which is being changed.
     * @param isVisible true if visibility is set to View.VISIBLE
     * @return this
     */
    public CustomDialogHelper setVisibility(@LayoutComponent int element, boolean isVisible) {
        int visibility;
        if (isVisible) {
            visibility = View.VISIBLE;
        } else {
            visibility = View.GONE;
        }
        switch (element) {
            case ICON:
                mDialogIcon.setVisibility(visibility);
                break;
            case TITLE:
                mDialogTitle.setVisibility(visibility);
                break;
            case MESSAGE:
                mDialogMessage.setVisibility(visibility);
                break;
            case BACK_BUTTON:
                mBackButton.setVisibility(visibility);
                break;
            case NEGATIVE_BUTTON:
                mNegativeButton.setVisibility(visibility);
                break;
            case POSITIVE_BUTTON:
                mPositiveButton.setVisibility(visibility);
                break;
            default:
                break;
        }
        return this;
    }

    /**
     * Requests focus on dialog title when used. Used to let talkback know that the dialog content
     * is updated and needs to be read from the beginning.
     */
    public void requestFocusOnTitle() {
        mDialogTitle.requestFocus();
        mDialogTitle.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }
}

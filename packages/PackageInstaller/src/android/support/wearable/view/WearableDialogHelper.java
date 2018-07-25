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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import android.util.Log;
import android.widget.Button;

/**
 * Helper to add icons to AlertDialog buttons.AlertDialog buttons.
 */
public class WearableDialogHelper {
    private static final String TAG = "WearableDialogHelper";

    private int mPositiveIconId;
    private Drawable mPositiveIcon;

    private int mNeutralIconId;
    private Drawable mNeutralIcon;

    private int mNegativeIconId;
    private Drawable mNegativeIcon;

    @VisibleForTesting /* package */ Resources mResources;
    @VisibleForTesting /* package */ Resources.Theme mTheme;

    /**
     * Convenience constructor, equivalent to {@code new WearableDialogHelper(context.getResources(),
     * context.getTheme())}.
     */
    public WearableDialogHelper(@NonNull Context context) {
        this(context.getResources(), context.getTheme());
    }

    /**
     * @param resources the Resources used to obtain Drawables from resource IDs.
     * @param theme the Theme used to properly obtain Drawables from resource IDs.
     */
    public WearableDialogHelper(@NonNull Resources resources, @NonNull Resources.Theme theme) {
        mResources = resources;
        mTheme = theme;
    }

    @Nullable
    public Drawable getPositiveIcon() {
        return resolveDrawable(mPositiveIcon, mPositiveIconId);
    }

    @Nullable
    public Drawable getNegativeIcon() {
        return resolveDrawable(mNegativeIcon, mNegativeIconId);
    }

    @Nullable
    public Drawable getNeutralIcon() {
        return resolveDrawable(mNeutralIcon, mNeutralIconId);
    }

    @NonNull
    public WearableDialogHelper setPositiveIcon(@DrawableRes int resId) {
        mPositiveIconId = resId;
        mPositiveIcon = null;
        return this;
    }

    @NonNull
    public WearableDialogHelper setPositiveIcon(@Nullable Drawable icon) {
        mPositiveIcon = icon;
        mPositiveIconId = 0;
        return this;
    }

    @NonNull
    public WearableDialogHelper setNegativeIcon(@DrawableRes int resId) {
        mNegativeIconId = resId;
        mNegativeIcon = null;
        return this;
    }

    @NonNull
    public WearableDialogHelper setNegativeIcon(@Nullable Drawable icon) {
        mNegativeIcon = icon;
        mNegativeIconId = 0;
        return this;
    }

    @NonNull
    public WearableDialogHelper setNeutralIcon(@DrawableRes int resId) {
        mNeutralIconId = resId;
        mNeutralIcon = null;
        return this;
    }

    @NonNull
    public WearableDialogHelper setNeutralIcon(@Nullable Drawable icon) {
        mNeutralIcon = icon;
        mNeutralIconId = 0;
        return this;
    }

    /**
     * Applies the button icons setup in the helper to the buttons in the dialog.
     *
     * <p>Note that this should be called after {@code AlertDialog.create()}, NOT {@code
     * AlertDialog.Builder.create()}. Calling {@code AlertDialog.Builder.show()} would also accomplish
     * the same thing.
     *
     * @param dialog the AlertDialog to style with the helper.
     */
    public void apply(@NonNull AlertDialog dialog) {
        applyButton(dialog.getButton(DialogInterface.BUTTON_POSITIVE), getPositiveIcon());
        applyButton(dialog.getButton(DialogInterface.BUTTON_NEGATIVE), getNegativeIcon());
        applyButton(dialog.getButton(DialogInterface.BUTTON_NEUTRAL), getNeutralIcon());
    }

    /** Applies the specified drawable to the button. */
    @VisibleForTesting
    /* package */ void applyButton(@Nullable Button button, @Nullable Drawable drawable) {
        if (button != null) {
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null);
            button.setAllCaps(false);
        } else if (drawable != null) {
            Log.w(TAG, "non-null drawable used with missing button, did you call AlertDialog.create()?");
        }
    }

    /** Obtain a drawable between a drawable and a resource ID. */
    @VisibleForTesting
    /* package */ Drawable resolveDrawable(@Nullable Drawable drawable, @DrawableRes int resId) {
        return drawable == null && resId != 0 ? mResources.getDrawable(resId, mTheme) : drawable;
    }

    /** Convenience builder to generate an AlertDialog with icons in buttons. */
    public static class DialogBuilder extends AlertDialog.Builder {
        private final WearableDialogHelper mHelper;

        public DialogBuilder(Context context) {
            super(context);
            mHelper = new WearableDialogHelper(context.getResources(), context.getTheme());
        }

        public DialogBuilder(Context context, int themeResId) {
            super(context, themeResId);
            mHelper = new WearableDialogHelper(context.getResources(), context.getTheme());
        }

        public WearableDialogHelper getHelper() {
            return mHelper;
        }

        public DialogBuilder setPositiveIcon(@DrawableRes int iconId) {
            mHelper.setPositiveIcon(iconId);
            return this;
        }

        public DialogBuilder setPositiveIcon(@Nullable Drawable icon) {
            mHelper.setPositiveIcon(icon);
            return this;
        }

        public DialogBuilder setNegativeIcon(@DrawableRes int iconId) {
            mHelper.setNegativeIcon(iconId);
            return this;
        }

        public DialogBuilder setNegativeIcon(@Nullable Drawable icon) {
            mHelper.setNegativeIcon(icon);
            return this;
        }

        public DialogBuilder setNeutralIcon(@DrawableRes int iconId) {
            mHelper.setNeutralIcon(iconId);
            return this;
        }

        public DialogBuilder setNeutralIcon(@Nullable Drawable icon) {
            mHelper.setNeutralIcon(icon);
            return this;
        }

        @Override
        public AlertDialog create() {
            final AlertDialog dialog = super.create();
            dialog.create();
            mHelper.apply(dialog);
            return dialog;
        }

        @Override
        public AlertDialog show() {
            final AlertDialog dialog = this.create();
            dialog.show();
            return dialog;
        }
    }
}

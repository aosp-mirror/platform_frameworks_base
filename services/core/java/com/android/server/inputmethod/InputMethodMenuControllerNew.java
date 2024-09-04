/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;


import static android.Manifest.permission.HIDE_OVERLAY_WINDOWS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;

import static com.android.server.inputmethod.InputMethodManagerService.DEBUG;
import static com.android.server.inputmethod.InputMethodUtils.NOT_A_SUBTYPE_INDEX;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Printer;
import android.util.Slog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.widget.RecyclerView;

import java.util.List;

/**
 * Controller for showing and hiding the Input Method Switcher Menu.
 */
final class InputMethodMenuControllerNew {

    private static final String TAG = InputMethodMenuControllerNew.class.getSimpleName();

    /**
     * The horizontal offset from the menu to the edge of the screen corresponding
     * to {@link Gravity#END}.
     */
    private static final int HORIZONTAL_OFFSET = 16;

    /** The title of the window, used for debugging. */
    private static final String WINDOW_TITLE = "IME Switcher Menu";

    private final InputMethodDialogWindowContext mDialogWindowContext =
            new InputMethodDialogWindowContext();

    @Nullable
    private AlertDialog mDialog;

    @Nullable
    private List<MenuItem> mMenuItems;

    /**
     * Shows the Input Method Switcher Menu, with a list of IMEs and their subtypes.
     *
     * @param items         the list of menu items.
     * @param selectedIndex the index of the menu item that is selected.
     *                      If no other IMEs are enabled, this index will be out of reach.
     * @param displayId     the ID of the display where the menu was requested.
     * @param userId        the ID of the user that requested the menu.
     */
    @RequiresPermission(allOf = {INTERACT_ACROSS_USERS, HIDE_OVERLAY_WINDOWS})
    void show(@NonNull List<MenuItem> items, int selectedIndex, int displayId,
            @UserIdInt int userId) {
        // Hide the menu in case it was already showing.
        hide(displayId, userId);

        final Context dialogWindowContext = mDialogWindowContext.get(displayId);
        final var builder = new AlertDialog.Builder(dialogWindowContext,
                com.android.internal.R.style.Theme_DeviceDefault_InputMethodSwitcherDialog);
        final var inflater = LayoutInflater.from(builder.getContext());

        // Create the content view.
        final View contentView = inflater
                .inflate(com.android.internal.R.layout.input_method_switch_dialog_new, null);
        contentView.setAccessibilityPaneTitle(
                dialogWindowContext.getText(com.android.internal.R.string.select_input_method));
        builder.setView(contentView);

        final DialogInterface.OnClickListener onClickListener = (dialog, which) -> {
            if (which != selectedIndex) {
                final var item = items.get(which);
                InputMethodManagerInternal.get()
                        .switchToInputMethod(item.mImi.getId(), item.mSubtypeIndex, userId);
            }
            hide(displayId, userId);
        };

        final var selectedImi = selectedIndex >= 0 ? items.get(selectedIndex).mImi : null;
        final var languageSettingsIntent = selectedImi != null
                ? selectedImi.createImeLanguageSettingsActivityIntent() : null;
        final boolean isDeviceProvisioned = Settings.Global.getInt(
                dialogWindowContext.getContentResolver(), Settings.Global.DEVICE_PROVISIONED,
                0) != 0;
        final boolean hasLanguageSettingsButton = languageSettingsIntent != null
                && isDeviceProvisioned;
        if (hasLanguageSettingsButton) {
            final View buttonBar = contentView
                    .requireViewById(com.android.internal.R.id.button_bar);
            buttonBar.setVisibility(View.VISIBLE);

            languageSettingsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final Button languageSettingsButton = contentView
                    .requireViewById(com.android.internal.R.id.button1);
            languageSettingsButton.setVisibility(View.VISIBLE);
            languageSettingsButton.setOnClickListener(v -> {
                v.getContext().startActivityAsUser(languageSettingsIntent, UserHandle.of(userId));
                hide(displayId, userId);
            });
        }

        // Create the current IME subtypes list.
        final RecyclerView recyclerView = contentView
                .requireViewById(com.android.internal.R.id.list);
        recyclerView.setAdapter(new Adapter(items, selectedIndex, inflater, onClickListener));
        // Scroll to the currently selected IME. This must run after the recycler view is laid out.
        recyclerView.post(() -> recyclerView.scrollToPosition(selectedIndex));
        // Indicate that the list can be scrolled.
        recyclerView.setScrollIndicators(
                hasLanguageSettingsButton ? View.SCROLL_INDICATOR_BOTTOM : 0);
        // Request focus to enable rotary scrolling on watches.
        recyclerView.requestFocus();

        builder.setOnCancelListener(dialog -> hide(displayId, userId));
        mMenuItems = items;
        mDialog = builder.create();
        mDialog.setCanceledOnTouchOutside(true);
        final Window w = mDialog.getWindow();
        w.setHideOverlayWindows(true);
        final WindowManager.LayoutParams attrs = w.getAttributes();
        // Use an alternate token for the dialog for that window manager can group the token
        // with other IME windows based on type vs. grouping based on whichever token happens
        // to get selected by the system later on.
        attrs.token = dialogWindowContext.getWindowContextToken();
        attrs.gravity = Gravity.getAbsoluteGravity(Gravity.BOTTOM | Gravity.END,
                dialogWindowContext.getResources().getConfiguration().getLayoutDirection());
        attrs.x = HORIZONTAL_OFFSET;
        attrs.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        attrs.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
        // Used for debugging only, not user visible.
        attrs.setTitle(WINDOW_TITLE);
        w.setAttributes(attrs);

        mDialog.show();
        InputMethodManagerInternal.get().updateShouldShowImeSwitcher(displayId, userId);
    }

    /**
     * Hides the Input Method Switcher Menu.
     *
     * @param displayId the ID of the display from where the menu should be hidden.
     * @param userId    the ID of the user for which the menu should be hidden.
     */
    void hide(int displayId, @UserIdInt int userId) {
        if (DEBUG) Slog.v(TAG, "Hide IME switcher menu.");

        mMenuItems = null;
        // Cannot use dialog.isShowing() here, as the cancel listener flow already resets mShowing.
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;

            InputMethodManagerInternal.get().updateShouldShowImeSwitcher(displayId, userId);
        }
    }

    /**
     * Returns whether the Input Method Switcher Menu is showing.
     */
    boolean isShowing() {
        return mDialog != null && mDialog.isShowing();
    }

    void dump(@NonNull Printer pw, @NonNull String prefix) {
        final boolean showing = isShowing();
        pw.println(prefix + "  isShowing: " + showing);

        if (showing) {
            pw.println(prefix + "  menuItems: " + mMenuItems);
        }
    }

    /**
     * Item to be shown in the Input Method Switcher Menu, containing an input method and
     * optionally an input method subtype.
     */
    static class MenuItem {

        /** The name of the input method. */
        @NonNull
        private final CharSequence mImeName;

        /**
         * The name of the input method subtype, or {@code null} if this item doesn't have a
         * subtype.
         */
        @Nullable
        private final CharSequence mSubtypeName;

        /** The info of the input method. */
        @NonNull
        private final InputMethodInfo mImi;

        /**
         * The index of the subtype in the input method's array of subtypes,
         * or {@link InputMethodUtils#NOT_A_SUBTYPE_INDEX} if this item doesn't have a subtype.
         */
        @IntRange(from = NOT_A_SUBTYPE_INDEX)
        private final int mSubtypeIndex;

        /** Whether this item has a group header (only the first item of each input method). */
        private final boolean mHasHeader;

        /**
         * Whether this item should has a group divider (same as {@link #mHasHeader},
         * excluding the first IME).
         */
        private final boolean mHasDivider;

        MenuItem(@NonNull CharSequence imeName, @Nullable CharSequence subtypeName,
                @NonNull InputMethodInfo imi,
                @IntRange(from = NOT_A_SUBTYPE_INDEX) int subtypeIndex, boolean hasHeader,
                boolean hasDivider) {
            mImeName = imeName;
            mSubtypeName = subtypeName;
            mImi = imi;
            mSubtypeIndex = subtypeIndex;
            mHasHeader = hasHeader;
            mHasDivider = hasDivider;
        }

        @Override
        public String toString() {
            return "MenuItem{"
                    + "mImeName=" + mImeName
                    + " mSubtypeName=" + mSubtypeName
                    + " mSubtypeIndex=" + mSubtypeIndex
                    + " mHasHeader=" + mHasHeader
                    + " mHasDivider=" + mHasDivider
                    + "}";
        }
    }

    private static class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {

        /** The list of items to show. */
        @NonNull
        private final List<MenuItem> mItems;
        /** The index of the selected item. */
        private final int mSelectedIndex;
        @NonNull
        private final LayoutInflater mInflater;
        @NonNull
        private final DialogInterface.OnClickListener mOnClickListener;

        Adapter(@NonNull List<MenuItem> items, int selectedIndex,
                @NonNull LayoutInflater inflater,
                @NonNull DialogInterface.OnClickListener onClickListener) {
            mItems = items;
            mSelectedIndex = selectedIndex;
            mInflater = inflater;
            mOnClickListener = onClickListener;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view = mInflater.inflate(
                    com.android.internal.R.layout.input_method_switch_item_new, parent, false);

            return new ViewHolder(view, mOnClickListener);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            holder.bind(mItems.get(position), position == mSelectedIndex /* isSelected */);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        private static class ViewHolder extends RecyclerView.ViewHolder {

            /** The container of the item. */
            @NonNull
            private final View mContainer;
            /** The name of the item. */
            @NonNull
            private final TextView mName;
            /** Indicator for the selected status of the item. */
            @NonNull
            private final ImageView mCheckmark;
            /** The group header optionally drawn above the item. */
            @NonNull
            private final TextView mHeader;
            /** The group divider optionally drawn above the item. */
            @NonNull
            private final View mDivider;

            private ViewHolder(@NonNull View itemView,
                    @NonNull DialogInterface.OnClickListener onClickListener) {
                super(itemView);

                mContainer = itemView.requireViewById(com.android.internal.R.id.list_item);
                mName = itemView.requireViewById(com.android.internal.R.id.text);
                mCheckmark = itemView.requireViewById(com.android.internal.R.id.image);
                mHeader = itemView.requireViewById(com.android.internal.R.id.header_text);
                mDivider = itemView.requireViewById(com.android.internal.R.id.divider);

                mContainer.setOnClickListener((v) ->
                        onClickListener.onClick(null /* dialog */, getAdapterPosition()));
            }

            /**
             * Binds the given item to the current view.
             *
             * @param item       the item to bind.
             * @param isSelected whether this is selected.
             */
            private void bind(@NonNull MenuItem item, boolean isSelected) {
                // Use the IME name for subtypes with an empty subtype name.
                final var name = TextUtils.isEmpty(item.mSubtypeName)
                        ? item.mImeName : item.mSubtypeName;
                mContainer.setActivated(isSelected);
                // Activated is the correct state, but we also set selected for accessibility info.
                mContainer.setSelected(isSelected);
                mName.setSelected(isSelected);
                mName.setText(name);
                mCheckmark.setVisibility(isSelected ? View.VISIBLE : View.GONE);
                mHeader.setText(item.mImeName);
                mHeader.setVisibility(item.mHasHeader ? View.VISIBLE : View.GONE);
                mDivider.setVisibility(item.mHasDivider ? View.VISIBLE : View.GONE);
            }
        }
    }
}

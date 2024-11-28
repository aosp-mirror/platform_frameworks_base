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

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.RecyclerView;
import com.android.server.inputmethod.InputMethodSubtypeSwitchingController.ImeSubtypeListItem;

import java.util.ArrayList;
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
     * @param items                the list of input method and subtype items.
     * @param selectedImeId        the ID of the selected input method.
     * @param selectedSubtypeIndex the index of the selected subtype in the input method's array of
     *                             subtypes, or {@link InputMethodUtils#NOT_A_SUBTYPE_INDEX} if no
     *                             subtype is selected.
     * @param displayId            the ID of the display where the menu was requested.
     * @param userId               the ID of the user that requested the menu.
     */
    @RequiresPermission(allOf = {INTERACT_ACROSS_USERS, HIDE_OVERLAY_WINDOWS})
    void show(@NonNull List<ImeSubtypeListItem> items, @Nullable String selectedImeId,
            int selectedSubtypeIndex, int displayId, @UserIdInt int userId) {
        // Hide the menu in case it was already showing.
        hide(displayId, userId);

        final var menuItems = getMenuItems(items);
        final int selectedIndex = getSelectedIndex(menuItems, selectedImeId, selectedSubtypeIndex);
        if (selectedIndex == -1) {
            Slog.w(TAG, "Switching menu shown with no item selected, IME id: " + selectedImeId
                    + ", subtype index: " + selectedSubtypeIndex);
        }

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

        final OnClickListener onClickListener = (item, isSelected) -> {
            if (!isSelected) {
                InputMethodManagerInternal.get()
                        .switchToInputMethod(item.mImi.getId(), item.mSubtypeIndex, userId);
            }
            hide(displayId, userId);
        };

        // Create the current IME subtypes list.
        final RecyclerView recyclerView = contentView
                .requireViewById(com.android.internal.R.id.list);
        recyclerView.setAdapter(new Adapter(menuItems, selectedIndex, inflater, onClickListener));
        // Scroll to the currently selected IME. This must run after the recycler view is laid out.
        recyclerView.post(() -> recyclerView.scrollToPosition(selectedIndex));
        // Request focus to enable rotary scrolling on watches.
        recyclerView.requestFocus();

        final var selectedItem = selectedIndex > -1 ? menuItems.get(selectedIndex) : null;
        updateLanguageSettingsButton(selectedItem, contentView, displayId, userId);

        builder.setOnCancelListener(dialog -> hide(displayId, userId));
        mMenuItems = menuItems;
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
        pw.println(prefix + "isShowing: " + showing);

        if (showing) {
            pw.println(prefix + "menuItems: " + mMenuItems);
        }
    }

    /**
     * Creates the list of menu items from the given list of input methods and subtypes. This
     * handles adding headers and dividers between groups of items from different input methods
     * as follows:
     *
     * <li>If there is only one group, no divider or header will be added.</li>
     * <li>A divider is added before each group, except the first one.</li>
     * <li>A header is added before each group (after the divider, if it exists) if the group has
     * at least two items, or a single item with a subtype name.</li>
     *
     * @param items the list of input method and subtype items.
     */
    @VisibleForTesting
    @NonNull
    static List<MenuItem> getMenuItems(@NonNull List<ImeSubtypeListItem> items) {
        final var menuItems = new ArrayList<MenuItem>();
        if (items.isEmpty()) {
            return menuItems;
        }

        final var itemsArray = (ArrayList<ImeSubtypeListItem>) items;
        final int numItems = itemsArray.size();
        // Initialize to the last IME id to avoid headers if there is only a single IME.
        String prevImeId = itemsArray.getLast().mImi.getId();
        boolean firstGroup = true;
        for (int i = 0; i < numItems; i++) {
            final var item = itemsArray.get(i);

            final var imeId = item.mImi.getId();
            final boolean groupChange = !imeId.equals(prevImeId);
            if (groupChange) {
                if (!firstGroup) {
                    menuItems.add(DividerItem.getInstance());
                }
                // Add a header if we have at least two items, or a single item with a subtype name.
                final var nextItemId = i + 1 < numItems ? itemsArray.get(i + 1).mImi.getId() : null;
                final boolean addHeader = item.mSubtypeName != null || imeId.equals(nextItemId);
                if (addHeader) {
                    menuItems.add(new HeaderItem(item.mImeName));
                }
                firstGroup = false;
                prevImeId = imeId;
            }

            menuItems.add(new SubtypeItem(item.mImeName, item.mSubtypeName, item.mLayoutName,
                    item.mImi, item.mSubtypeIndex));
        }

        return menuItems;
    }

    /**
     * Gets the index of the selected item.
     *
     * @param items                the list of menu items.
     * @param selectedImeId        the ID of the selected input method.
     * @param selectedSubtypeIndex the index of the selected subtype in the input method's array of
     *                             subtypes, or {@link InputMethodUtils#NOT_A_SUBTYPE_INDEX} if no
     *                             subtype is selected.
     * @return the index of the selected item, or {@code -1} if no item is selected.
     */
    @VisibleForTesting
    @IntRange(from = -1)
    static int getSelectedIndex(@NonNull List<MenuItem> items, @Nullable String selectedImeId,
            int selectedSubtypeIndex) {
        for (int i = 0; i < items.size(); i++) {
            final var item = items.get(i);
            if (item instanceof SubtypeItem subtypeItem) {
                final var imeId = subtypeItem.mImi.getId();
                final int subtypeIndex = subtypeItem.mSubtypeIndex;
                if (imeId.equals(selectedImeId)
                        && ((subtypeIndex == 0 && selectedSubtypeIndex == NOT_A_SUBTYPE_INDEX)
                            || subtypeIndex == NOT_A_SUBTYPE_INDEX
                            || subtypeIndex == selectedSubtypeIndex)) {
                    return i;
                }
            }
        }
        // Either there is no selected IME, or the selected subtype is enabled but not in the list.
        // This can happen if an implicit subtype is selected, but we got a list of explicit
        // subtypes. In this case, the implicit subtype will no longer be included in the list.
        return -1;
    }

    /**
     * Updates the visibility of the Language Settings button to visible if the currently selected
     * item specifies a (language) settings activity and the device is provisioned. Otherwise,
     * the button won't be shown.
     *
     * @param selectedItem the currently selected item, or {@code null} if no item is selected.
     * @param view         the menu dialog view.
     * @param displayId    the ID of the display where the menu was requested.
     * @param userId       the ID of the user that requested the menu.
     */
    @RequiresPermission(allOf = {INTERACT_ACROSS_USERS})
    private void updateLanguageSettingsButton(@Nullable MenuItem selectedItem, @NonNull View view,
            int displayId, @UserIdInt int userId) {
        final var settingsIntent = (selectedItem instanceof SubtypeItem selectedSubtypeItem)
                ? selectedSubtypeItem.mImi.createImeLanguageSettingsActivityIntent() : null;
        final boolean isDeviceProvisioned = Settings.Global.getInt(
                view.getContext().getContentResolver(), Settings.Global.DEVICE_PROVISIONED,
                0) != 0;
        final boolean hasButton = settingsIntent != null && isDeviceProvisioned;
        final View buttonBar = view.requireViewById(com.android.internal.R.id.button_bar);
        final Button button = view.requireViewById(com.android.internal.R.id.button1);
        final RecyclerView recyclerView = view.requireViewById(com.android.internal.R.id.list);
        if (hasButton) {
            settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            buttonBar.setVisibility(View.VISIBLE);
            button.setOnClickListener(v -> {
                v.getContext().startActivityAsUser(settingsIntent, UserHandle.of(userId));
                hide(displayId, userId);
            });
            // Indicate that the list can be scrolled.
            recyclerView.setScrollIndicators(View.SCROLL_INDICATOR_BOTTOM);
        } else {
            buttonBar.setVisibility(View.GONE);
            button.setOnClickListener(null);
            // Remove scroll indicator as there is nothing drawn below the list.
            recyclerView.setScrollIndicators(0 /* indicators */);
        }
    }

    /**
     * Interface definition for callbacks to be invoked when a {@link SubtypeItem} is clicked.
     */
    private interface OnClickListener {

        /**
         * Called when an item is clicked.
         *
         * @param item       The item that was clicked.
         * @param isSelected Whether the item is the currently selected one.
         */
        void onClick(@NonNull SubtypeItem item, boolean isSelected);
    }

    /** Item to be displayed in the menu. */
    sealed interface MenuItem {}

    /** Subtype item containing an input method and optionally an input method subtype. */
    static final class SubtypeItem implements MenuItem {

        /** The name of the input method. */
        @NonNull
        final CharSequence mImeName;

        /**
         * The name of the input method subtype, or {@code null} if this item doesn't have a
         * subtype.
         */
        @Nullable
        final CharSequence mSubtypeName;

        /**
         * The name of the subtype's layout, or {@code null} if this item doesn't have a subtype,
         * or doesn't specify a layout.
         */
        @Nullable
        private final CharSequence mLayoutName;

        /** The info of the input method. */
        @NonNull
        final InputMethodInfo mImi;

        /**
         * The index of the subtype in the input method's array of subtypes,
         * or {@link InputMethodUtils#NOT_A_SUBTYPE_INDEX} if this item doesn't have a subtype.
         */
        @IntRange(from = NOT_A_SUBTYPE_INDEX)
        final int mSubtypeIndex;

        SubtypeItem(@NonNull CharSequence imeName, @Nullable CharSequence subtypeName,
                @Nullable CharSequence layoutName, @NonNull InputMethodInfo imi,
                @IntRange(from = NOT_A_SUBTYPE_INDEX) int subtypeIndex) {
            mImeName = imeName;
            mSubtypeName = subtypeName;
            mLayoutName = layoutName;
            mImi = imi;
            mSubtypeIndex = subtypeIndex;
        }

        @Override
        public String toString() {
            return "SubtypeItem{"
                    + "mImeName=" + mImeName
                    + " mSubtypeName=" + mSubtypeName
                    + " mSubtypeIndex=" + mSubtypeIndex
                    + "}";
        }
    }

    /** Header item displayed before a group of {@link SubtypeItem} of the same input method. */
    static final class HeaderItem implements MenuItem {

        /** The header title. */
        @NonNull
        final CharSequence mTitle;

        HeaderItem(@NonNull CharSequence title) {
            mTitle = title;
        }

        @Override
        public String toString() {
            return "HeaderItem{"
                    + "mTitle=" + mTitle
                    + "}";
        }
    }

    /** Divider item displayed before a {@link HeaderItem}. */
    static final class DividerItem implements MenuItem {

        private static DividerItem sInstance;

        /** Gets a singleton instance of DividerItem. */
        @NonNull
        static DividerItem getInstance() {
            if (sInstance == null) {
                sInstance = new DividerItem();
            }
            return sInstance;
        }

        @Override
        public String toString() {
            return "DividerItem{}";
        }
    }

    private static final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        /** View type for unknown item. */
        private static final int TYPE_UNKNOWN = -1;

        /** View type for {@link SubtypeItem}. */
        private static final int TYPE_SUBTYPE = 0;

        /** View type for {@link HeaderItem}. */
        private static final int TYPE_HEADER = 1;

        /** View type for {@link DividerItem}. */
        private static final int TYPE_DIVIDER = 2;

        /** The list of items to show. */
        @NonNull
        private final List<MenuItem> mItems;
        /** The index of the selected item. */
        @IntRange(from = -1)
        private final int mSelectedIndex;
        @NonNull
        private final LayoutInflater mInflater;
        /** The listener used to handle clicks on {@link SubtypeViewHolder} items. */
        @NonNull
        private final OnClickListener mListener;

        Adapter(@NonNull List<MenuItem> items, @IntRange(from = -1) int selectedIndex,
                @NonNull LayoutInflater inflater,
                @NonNull OnClickListener listener) {
            mItems = items;
            mSelectedIndex = selectedIndex;
            mInflater = inflater;
            mListener = listener;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            switch (viewType) {
                case TYPE_SUBTYPE -> {
                    final View view = mInflater.inflate(
                            com.android.internal.R.layout.input_method_switch_item_new, parent,
                            false);
                    return new SubtypeViewHolder(view, mListener);
                }
                case TYPE_HEADER -> {
                    final View view = mInflater.inflate(
                            com.android.internal.R.layout.input_method_switch_item_header, parent,
                            false);
                    return new HeaderViewHolder(view);
                }
                case TYPE_DIVIDER -> {
                    final View view = mInflater.inflate(
                            com.android.internal.R.layout.input_method_switch_item_divider, parent,
                            false);
                    return new DividerViewHolder(view);
                }
                default -> throw new IllegalArgumentException("Unknown viewType: " + viewType);
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final var item = mItems.get(position);
            if (holder instanceof SubtypeViewHolder subtypeHolder
                    && item instanceof SubtypeItem subtypeItem) {
                subtypeHolder.bind(subtypeItem, position == mSelectedIndex /* isSelected */);
            } else if (holder instanceof HeaderViewHolder headerHolder
                    && item instanceof HeaderItem headerItem) {
                headerHolder.bind(headerItem);
            } else if (holder instanceof DividerViewHolder && item instanceof DividerItem) {
                // Nothing to bind for dividers.
                return;
            } else {
                Slog.w(TAG, "Holder type: " + holder + " doesn't match item type: " + item);
            }
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @Override
        public int getItemViewType(int position) {
            final var item = mItems.get(position);
            if (item instanceof SubtypeItem) {
                return TYPE_SUBTYPE;
            } else if (item instanceof HeaderItem) {
                return TYPE_HEADER;
            } else if (item instanceof DividerItem) {
                return TYPE_DIVIDER;
            } else {
                return TYPE_UNKNOWN;
            }
        }

        private static final class SubtypeViewHolder extends RecyclerView.ViewHolder {

            /** The container of the item. */
            @NonNull
            private final View mContainer;
            /** The name of the item. */
            @NonNull
            private final TextView mName;
            /** The layout name. */
            @NonNull
            private final TextView mLayout;
            /** Indicator for the selected status of the item. */
            @NonNull
            private final ImageView mCheckmark;

            /** The bound item data, or {@code null} if no item was bound yet. */
            @Nullable
            private SubtypeItem mItem;
            /** Whether this item is the currently selected one. */
            private boolean mIsSelected;

            SubtypeViewHolder(@NonNull View itemView, @NonNull OnClickListener listener) {
                super(itemView);

                mContainer = itemView;
                mName = itemView.requireViewById(com.android.internal.R.id.text);
                mLayout = itemView.requireViewById(com.android.internal.R.id.text2);
                mCheckmark = itemView.requireViewById(com.android.internal.R.id.image);

                mContainer.setOnClickListener((v) -> {
                    if (mItem != null) {
                        listener.onClick(mItem, mIsSelected);
                    }
                });
            }

            /**
             * Binds the given item to the current view.
             *
             * @param item       the item to bind.
             * @param isSelected whether the item is selected.
             */
            void bind(@NonNull SubtypeItem item, boolean isSelected) {
                mItem = item;
                mIsSelected = isSelected;
                // Use the IME name for subtypes with an empty subtype name.
                final var name = TextUtils.isEmpty(item.mSubtypeName)
                        ? item.mImeName : item.mSubtypeName;
                mContainer.setActivated(isSelected);
                // Activated is the correct state, but we also set selected for accessibility info.
                mContainer.setSelected(isSelected);
                // Trigger the ellipsize marquee behaviour by selecting the name.
                mName.setSelected(isSelected);
                mName.setText(name);
                mLayout.setText(item.mLayoutName);
                mLayout.setVisibility(
                        !TextUtils.isEmpty(item.mLayoutName) ? View.VISIBLE : View.GONE);
                mCheckmark.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            }
        }

        private static final class HeaderViewHolder extends RecyclerView.ViewHolder {

            /** The title view, only visible if the bound item has a title. */
            private final TextView mTitle;

            HeaderViewHolder(@NonNull View itemView) {
                super(itemView);

                mTitle = itemView.requireViewById(com.android.internal.R.id.header_text);
            }

            /**
             * Binds the given item to the current view.
             *
             * @param item the item to bind.
             */
            void bind(@NonNull HeaderItem item) {
                mTitle.setText(item.mTitle);
            }
        }

        private static final class DividerViewHolder extends RecyclerView.ViewHolder {

            DividerViewHolder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }
}

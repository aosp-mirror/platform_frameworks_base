/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.server.inputmethod.InputMethodManagerService.DEBUG;
import static com.android.server.inputmethod.InputMethodUtils.NOT_A_SUBTYPE_INDEX;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Printer;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.inputmethod.InputMethodSubtypeSwitchingController.ImeSubtypeListItem;
import com.android.server.wm.WindowManagerInternal;

import java.util.List;

/** A controller to show/hide the input method menu */
final class InputMethodMenuController {
    private static final String TAG = InputMethodMenuController.class.getSimpleName();

    private final InputMethodManagerService mService;
    private final WindowManagerInternal mWindowManagerInternal;

    private AlertDialog.Builder mDialogBuilder;
    private AlertDialog mSwitchingDialog;
    private View mSwitchingDialogTitleView;
    private List<ImeSubtypeListItem> mImList;
    private InputMethodInfo[] mIms;
    private int[] mSubtypeIndices;

    private boolean mShowImeWithHardKeyboard;

    @GuardedBy("ImfLock.class")
    @Nullable
    private InputMethodDialogWindowContext mDialogWindowContext;

    InputMethodMenuController(InputMethodManagerService service) {
        mService = service;
        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
    }

    @GuardedBy("ImfLock.class")
    void showInputMethodMenuLocked(boolean showAuxSubtypes, int displayId,
            String preferredInputMethodId, int preferredInputMethodSubtypeIndex,
            @NonNull List<ImeSubtypeListItem> imList, @UserIdInt int userId) {
        if (DEBUG) Slog.v(TAG, "Show switching menu. showAuxSubtypes=" + showAuxSubtypes);

        final var bindingController = mService.getInputMethodBindingController(userId);

        hideInputMethodMenuLocked(userId);

        if (preferredInputMethodSubtypeIndex == NOT_A_SUBTYPE_INDEX) {
            final InputMethodSubtype currentSubtype =
                    bindingController.getCurrentInputMethodSubtype();
            if (currentSubtype != null) {
                final String curMethodId = bindingController.getSelectedMethodId();
                final InputMethodInfo currentImi =
                        InputMethodSettingsRepository.get(userId).getMethodMap().get(curMethodId);
                preferredInputMethodSubtypeIndex = SubtypeUtils.getSubtypeIndexFromHashCode(
                        currentImi, currentSubtype.hashCode());
            }
        }

        // Find out which item should be checked by default.
        final int size = imList.size();
        mImList = imList;
        mIms = new InputMethodInfo[size];
        mSubtypeIndices = new int[size];
        // No items are checked by default. When we have a list of explicitly enabled subtypes,
        // the implicit subtype is no longer listed, but if it is still the selected one,
        // no items will be shown as checked.
        int checkedItem = -1;
        for (int i = 0; i < size; ++i) {
            final ImeSubtypeListItem item = imList.get(i);
            mIms[i] = item.mImi;
            mSubtypeIndices[i] = item.mSubtypeIndex;
            if (mIms[i].getId().equals(preferredInputMethodId)) {
                int subtypeIndex = mSubtypeIndices[i];
                if ((subtypeIndex == NOT_A_SUBTYPE_INDEX)
                        || (preferredInputMethodSubtypeIndex == NOT_A_SUBTYPE_INDEX
                        && subtypeIndex == 0)
                        || (subtypeIndex == preferredInputMethodSubtypeIndex)) {
                    checkedItem = i;
                }
            }
        }

        if (checkedItem == -1) {
            Slog.w(TAG, "Switching menu shown with no item selected"
                    + ", IME id: " + preferredInputMethodId
                    + ", subtype index: " + preferredInputMethodSubtypeIndex);
        }

        if (mDialogWindowContext == null) {
            mDialogWindowContext = new InputMethodDialogWindowContext();
        }
        final Context dialogWindowContext = mDialogWindowContext.get(displayId);
        mDialogBuilder = new AlertDialog.Builder(dialogWindowContext);
        mDialogBuilder.setOnCancelListener(dialog -> hideInputMethodMenu(userId));

        final Context dialogContext = mDialogBuilder.getContext();
        final TypedArray a = dialogContext.obtainStyledAttributes(null,
                com.android.internal.R.styleable.DialogPreference,
                com.android.internal.R.attr.alertDialogStyle, 0);
        final Drawable dialogIcon = a.getDrawable(
                com.android.internal.R.styleable.DialogPreference_dialogIcon);
        a.recycle();

        mDialogBuilder.setIcon(dialogIcon);

        final LayoutInflater inflater = dialogContext.getSystemService(LayoutInflater.class);
        final View tv = inflater.inflate(
                com.android.internal.R.layout.input_method_switch_dialog_title, null);
        mDialogBuilder.setCustomTitle(tv);

        // Setup layout for a toggle switch of the hardware keyboard
        mSwitchingDialogTitleView = tv;
        mSwitchingDialogTitleView
                .findViewById(com.android.internal.R.id.hard_keyboard_section)
                .setVisibility(mWindowManagerInternal.isHardKeyboardAvailable()
                        ? View.VISIBLE : View.GONE);
        final Switch hardKeySwitch = mSwitchingDialogTitleView.findViewById(
                com.android.internal.R.id.hard_keyboard_switch);
        hardKeySwitch.setChecked(mShowImeWithHardKeyboard);
        hardKeySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SecureSettingsWrapper.putBoolean(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD,
                    isChecked, userId);
            // Ensure that the input method dialog is dismissed when changing
            // the hardware keyboard state.
            hideInputMethodMenu(userId);
        });

        // Fill the list items with onClick listener, which takes care of IME (and subtype)
        // switching when clicked.
        final ImeSubtypeListAdapter adapter = new ImeSubtypeListAdapter(dialogContext,
                com.android.internal.R.layout.input_method_switch_item, imList, checkedItem);
        final DialogInterface.OnClickListener choiceListener = (dialog, which) -> {
            synchronized (ImfLock.class) {
                if (mIms == null || mIms.length <= which || mSubtypeIndices == null
                        || mSubtypeIndices.length <= which) {
                    return;
                }
                final InputMethodInfo im = mIms[which];
                int subtypeIndex = mSubtypeIndices[which];
                adapter.mCheckedItem = which;
                adapter.notifyDataSetChanged();
                if (im != null) {
                    if (subtypeIndex < 0 || subtypeIndex >= im.getSubtypeCount()) {
                        subtypeIndex = NOT_A_SUBTYPE_INDEX;
                    }
                    mService.setInputMethodLocked(im.getId(), subtypeIndex, userId);
                }
                hideInputMethodMenuLocked(userId);
            }
        };
        mDialogBuilder.setSingleChoiceItems(adapter, checkedItem, choiceListener);

        // Final steps to instantiate a dialog to show it up.
        mSwitchingDialog = mDialogBuilder.create();
        mSwitchingDialog.setCanceledOnTouchOutside(true);
        final Window w = mSwitchingDialog.getWindow();
        final WindowManager.LayoutParams attrs = w.getAttributes();
        w.setType(WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG);
        w.setHideOverlayWindows(true);
        // Use an alternate token for the dialog for that window manager can group the token
        // with other IME windows based on type vs. grouping based on whichever token happens
        // to get selected by the system later on.
        attrs.token = dialogWindowContext.getWindowContextToken();
        attrs.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        attrs.setTitle("Select input method");
        w.setAttributes(attrs);
        mService.updateSystemUiLocked(userId);
        mService.sendOnNavButtonFlagsChangedLocked(mService.getUserData(userId));
        mSwitchingDialog.show();
    }

    void updateKeyboardFromSettingsLocked(@UserIdInt int userId) {
        mShowImeWithHardKeyboard =
                SecureSettingsWrapper.getBoolean(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD,
                        false, userId);
        if (mSwitchingDialog != null && mSwitchingDialogTitleView != null
                && mSwitchingDialog.isShowing()) {
            final Switch hardKeySwitch = mSwitchingDialogTitleView.findViewById(
                    com.android.internal.R.id.hard_keyboard_switch);
            hardKeySwitch.setChecked(mShowImeWithHardKeyboard);
        }
    }

    /**
     * Hides the input method switcher menu.
     *
     * @param userId user ID for this operation
     */
    void hideInputMethodMenu(@UserIdInt int userId) {
        synchronized (ImfLock.class) {
            hideInputMethodMenuLocked(userId);
        }
    }

    /**
     * Hides the input method switcher menu, synchronised version of {@link #hideInputMethodMenu}.
     *
     * @param userId user ID for this operation
     */
    @GuardedBy("ImfLock.class")
    void hideInputMethodMenuLocked(@UserIdInt int userId) {
        if (DEBUG) Slog.v(TAG, "Hide switching menu");

        if (mSwitchingDialog != null) {
            mSwitchingDialog.dismiss();
            mSwitchingDialog = null;
            mSwitchingDialogTitleView = null;

            mService.updateSystemUiLocked(userId);
            mService.sendOnNavButtonFlagsChangedToAllImesLocked();
            mDialogBuilder = null;
            mImList = null;
            mIms = null;
            mSubtypeIndices = null;
        }
    }

    AlertDialog getSwitchingDialogLocked() {
        return mSwitchingDialog;
    }

    boolean getShowImeWithHardKeyboard() {
        return mShowImeWithHardKeyboard;
    }

    boolean isisInputMethodPickerShownForTestLocked() {
        if (mSwitchingDialog == null) {
            return false;
        }
        return mSwitchingDialog.isShowing();
    }

    void handleHardKeyboardStatusChange(boolean available) {
        if (DEBUG) {
            Slog.w(TAG, "HardKeyboardStatusChanged: available=" + available);
        }
        synchronized (ImfLock.class) {
            if (mSwitchingDialog != null && mSwitchingDialogTitleView != null
                    && mSwitchingDialog.isShowing()) {
                mSwitchingDialogTitleView.findViewById(
                        com.android.internal.R.id.hard_keyboard_section).setVisibility(
                        available ? View.VISIBLE : View.GONE);
            }
        }
    }

    void dump(@NonNull Printer pw, @NonNull String prefix) {
        final boolean showing = isisInputMethodPickerShownForTestLocked();
        pw.println(prefix + "isShowing: " + showing);

        if (showing) {
            pw.println(prefix + "imList: " + mImList);
        }
    }

    private static class ImeSubtypeListAdapter extends ArrayAdapter<ImeSubtypeListItem> {
        private final LayoutInflater mInflater;
        private final int mTextViewResourceId;
        private final List<ImeSubtypeListItem> mItemsList;
        public int mCheckedItem;
        private ImeSubtypeListAdapter(Context context, int textViewResourceId,
                List<ImeSubtypeListItem> itemsList, int checkedItem) {
            super(context, textViewResourceId, itemsList);

            mTextViewResourceId = textViewResourceId;
            mItemsList = itemsList;
            mCheckedItem = checkedItem;
            mInflater = LayoutInflater.from(context);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View view = convertView != null ? convertView
                    : mInflater.inflate(mTextViewResourceId, null);
            if (position < 0 || position >= mItemsList.size()) return view;
            final ImeSubtypeListItem item = mItemsList.get(position);
            final CharSequence imeName = item.mImeName;
            final CharSequence subtypeName = item.mSubtypeName;
            final TextView firstTextView = view.findViewById(android.R.id.text1);
            final TextView secondTextView = view.findViewById(android.R.id.text2);
            if (TextUtils.isEmpty(subtypeName)) {
                firstTextView.setText(imeName);
                secondTextView.setVisibility(View.GONE);
            } else {
                firstTextView.setText(subtypeName);
                secondTextView.setText(imeName);
                secondTextView.setVisibility(View.VISIBLE);
            }
            final RadioButton radioButton = view.findViewById(com.android.internal.R.id.radio);
            radioButton.setChecked(position == mCheckedItem);
            return view;
        }
    }
}

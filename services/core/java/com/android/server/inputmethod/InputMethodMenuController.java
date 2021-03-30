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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;
import static com.android.server.inputmethod.InputMethodManagerService.DEBUG;
import static com.android.server.inputmethod.InputMethodManagerService.MSG_HARD_KEYBOARD_SWITCH_CHANGED;
import static com.android.server.inputmethod.InputMethodUtils.NOT_A_SUBTYPE_ID;

import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.ContextThemeWrapper;
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

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.inputmethod.InputMethodSubtypeSwitchingController.ImeSubtypeListItem;
import com.android.server.wm.WindowManagerInternal;

import java.util.List;

/** A controller to show/hide the input method menu */
@VisibleForTesting(visibility = PACKAGE)
public class InputMethodMenuController {
    private static final String TAG = InputMethodMenuController.class.getSimpleName();

    private final InputMethodManagerService mService;
    private final InputMethodUtils.InputMethodSettings mSettings;
    private final InputMethodSubtypeSwitchingController mSwitchingController;
    private final Handler mHandler;
    private final ArrayMap<String, InputMethodInfo> mMethodMap;
    private final KeyguardManager mKeyguardManager;
    private final HardKeyboardListener mHardKeyboardListener;
    private final WindowManagerInternal mWindowManagerInternal;

    private Context mSettingsContext;
    private AlertDialog.Builder mDialogBuilder;
    private AlertDialog mSwitchingDialog;
    private IBinder mSwitchingDialogToken;
    private View mSwitchingDialogTitleView;
    private InputMethodInfo[] mIms;
    private int[] mSubtypeIds;

    private boolean mShowImeWithHardKeyboard;

    @VisibleForTesting(visibility = PACKAGE)
    public InputMethodMenuController(InputMethodManagerService service) {
        mService = service;
        mSettings = mService.mSettings;
        mSwitchingController = mService.mSwitchingController;
        mHandler = mService.mHandler;
        mMethodMap = mService.mMethodMap;
        mKeyguardManager = mService.mKeyguardManager;
        mHardKeyboardListener = new HardKeyboardListener();
        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
    }

    void showInputMethodMenu(boolean showAuxSubtypes, int displayId) {
        if (DEBUG) Slog.v(TAG, "Show switching menu. showAuxSubtypes=" + showAuxSubtypes);

        final boolean isScreenLocked = isScreenLocked();

        final String lastInputMethodId = mSettings.getSelectedInputMethod();
        int lastInputMethodSubtypeId = mSettings.getSelectedInputMethodSubtypeId(lastInputMethodId);
        if (DEBUG) Slog.v(TAG, "Current IME: " + lastInputMethodId);

        synchronized (mMethodMap) {
            final List<ImeSubtypeListItem> imList = mSwitchingController
                    .getSortedInputMethodAndSubtypeListForImeMenuLocked(
                            showAuxSubtypes, isScreenLocked);
            if (imList.isEmpty()) {
                return;
            }

            hideInputMethodMenuLocked();

            if (lastInputMethodSubtypeId == NOT_A_SUBTYPE_ID) {
                final InputMethodSubtype currentSubtype =
                        mService.getCurrentInputMethodSubtypeLocked();
                if (currentSubtype != null) {
                    final String curMethodId = mService.getCurrentMethodId();
                    final InputMethodInfo currentImi = mMethodMap.get(curMethodId);
                    lastInputMethodSubtypeId = InputMethodUtils.getSubtypeIdFromHashCode(
                            currentImi, currentSubtype.hashCode());
                }
            }

            final int size = imList.size();
            mIms = new InputMethodInfo[size];
            mSubtypeIds = new int[size];
            int checkedItem = 0;
            for (int i = 0; i < size; ++i) {
                final ImeSubtypeListItem item = imList.get(i);
                mIms[i] = item.mImi;
                mSubtypeIds[i] = item.mSubtypeId;
                if (mIms[i].getId().equals(lastInputMethodId)) {
                    int subtypeId = mSubtypeIds[i];
                    if ((subtypeId == NOT_A_SUBTYPE_ID)
                            || (lastInputMethodSubtypeId == NOT_A_SUBTYPE_ID && subtypeId == 0)
                            || (subtypeId == lastInputMethodSubtypeId)) {
                        checkedItem = i;
                    }
                }
            }

            final Context settingsContext = getSettingsContext(displayId);
            mDialogBuilder = new AlertDialog.Builder(settingsContext);
            mDialogBuilder.setOnCancelListener(dialog -> hideInputMethodMenu());

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
                mSettings.setShowImeWithHardKeyboard(isChecked);
                // Ensure that the input method dialog is dismissed when changing
                // the hardware keyboard state.
                hideInputMethodMenu();
            });

            final ImeSubtypeListAdapter adapter = new ImeSubtypeListAdapter(dialogContext,
                    com.android.internal.R.layout.input_method_switch_item, imList, checkedItem);
            final DialogInterface.OnClickListener choiceListener = (dialog, which) -> {
                synchronized (mMethodMap) {
                    if (mIms == null || mIms.length <= which || mSubtypeIds == null
                            || mSubtypeIds.length <= which) {
                        return;
                    }
                    final InputMethodInfo im = mIms[which];
                    int subtypeId = mSubtypeIds[which];
                    adapter.mCheckedItem = which;
                    adapter.notifyDataSetChanged();
                    hideInputMethodMenu();
                    if (im != null) {
                        if (subtypeId < 0 || subtypeId >= im.getSubtypeCount()) {
                            subtypeId = NOT_A_SUBTYPE_ID;
                        }
                        mService.setInputMethodLocked(im.getId(), subtypeId);
                    }
                }
            };
            mDialogBuilder.setSingleChoiceItems(adapter, checkedItem, choiceListener);

            mSwitchingDialog = mDialogBuilder.create();
            mSwitchingDialog.setCanceledOnTouchOutside(true);
            final Window w = mSwitchingDialog.getWindow();
            final WindowManager.LayoutParams attrs = w.getAttributes();
            w.setType(WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG);
            // Use an alternate token for the dialog for that window manager can group the token
            // with other IME windows based on type vs. grouping based on whichever token happens
            // to get selected by the system later on.
            attrs.token = mSwitchingDialogToken;
            attrs.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
            attrs.setTitle("Select input method");
            w.setAttributes(attrs);
            mService.updateSystemUiLocked();
            mSwitchingDialog.show();
        }
    }

    /**
     * Returns the window context for IME switch dialogs to receive configuration changes.
     *
     * This method initializes the window context if it was not initialized. This method also moves
     * the context to the targeted display if the current display of context is different than
     * the display specified by {@code displayId}.
     */
    @VisibleForTesting
    public Context getSettingsContext(int displayId) {
        // TODO(b/178462039): Cover the case when IME is moved to another ImeContainer.
        if (mSettingsContext == null || mSettingsContext.getDisplayId() != displayId) {
            final Context systemUiContext = ActivityThread.currentActivityThread()
                    .createSystemUiContext(displayId);
            final Context windowContext = systemUiContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG, null /* options */);
            mSettingsContext = new ContextThemeWrapper(
                    windowContext, com.android.internal.R.style.Theme_DeviceDefault_Settings);
            mSwitchingDialogToken = mSettingsContext.getWindowContextToken();
        }
        return mSettingsContext;
    }

    private boolean isScreenLocked() {
        return mKeyguardManager != null && mKeyguardManager.isKeyguardLocked()
                && mKeyguardManager.isKeyguardSecure();
    }

    void updateKeyboardFromSettingsLocked() {
        mShowImeWithHardKeyboard = mSettings.isShowImeWithHardKeyboardEnabled();
        if (mSwitchingDialog != null && mSwitchingDialogTitleView != null
                && mSwitchingDialog.isShowing()) {
            final Switch hardKeySwitch = mSwitchingDialogTitleView.findViewById(
                    com.android.internal.R.id.hard_keyboard_switch);
            hardKeySwitch.setChecked(mShowImeWithHardKeyboard);
        }
    }

    void hideInputMethodMenu() {
        synchronized (mMethodMap) {
            hideInputMethodMenuLocked();
        }
    }

    void hideInputMethodMenuLocked() {
        if (DEBUG) Slog.v(TAG, "Hide switching menu");

        if (mSwitchingDialog != null) {
            mSwitchingDialog.dismiss();
            mSwitchingDialog = null;
            mSwitchingDialogTitleView = null;
        }

        mService.updateSystemUiLocked();
        mDialogBuilder = null;
        mIms = null;
    }

    HardKeyboardListener getHardKeyboardListener() {
        return mHardKeyboardListener;
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

    class HardKeyboardListener implements WindowManagerInternal.OnHardKeyboardStatusChangeListener {
        @Override
        public void onHardKeyboardStatusChange(boolean available) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_HARD_KEYBOARD_SWITCH_CHANGED,
                    available ? 1 : 0));
        }

        public void handleHardKeyboardStatusChange(boolean available) {
            if (DEBUG) {
                Slog.w(TAG, "HardKeyboardStatusChanged: available=" + available);
            }
            synchronized (mMethodMap) {
                if (mSwitchingDialog != null && mSwitchingDialogTitleView != null
                        && mSwitchingDialog.isShowing()) {
                    mSwitchingDialogTitleView.findViewById(
                            com.android.internal.R.id.hard_keyboard_section).setVisibility(
                            available ? View.VISIBLE : View.GONE);
                }
            }
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

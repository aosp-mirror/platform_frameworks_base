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

package com.android.systemui.statusbar;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.KeyboardShortcutInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager.KeyboardShortcutsReceiver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.internal.app.AssistUtils;
import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static android.content.Context.LAYOUT_INFLATER_SERVICE;
import static android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;

/**
 * Contains functionality for handling keyboard shortcuts.
 */
public final class KeyboardShortcuts {
    private static final String TAG = KeyboardShortcuts.class.getSimpleName();
    private static final Object sLock = new Object();
    private static KeyboardShortcuts sInstance;

    private final SparseArray<String> mSpecialCharacterNames = new SparseArray<>();
    private final SparseArray<String> mModifierNames = new SparseArray<>();
    private final SparseArray<Drawable> mSpecialCharacterDrawables = new SparseArray<>();
    private final SparseArray<Drawable> mModifierDrawables = new SparseArray<>();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Context mContext;
    private final IPackageManager mPackageManager;
    private final OnClickListener mDialogCloseListener = new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
            dismissKeyboardShortcuts();
        }
    };
    private final Comparator<KeyboardShortcutInfo> mApplicationItemsComparator =
            new Comparator<KeyboardShortcutInfo>() {
                @Override
                public int compare(KeyboardShortcutInfo ksh1, KeyboardShortcutInfo ksh2) {
                    boolean ksh1ShouldBeLast = ksh1.getLabel() == null
                            || ksh1.getLabel().toString().isEmpty();
                    boolean ksh2ShouldBeLast = ksh2.getLabel() == null
                            || ksh2.getLabel().toString().isEmpty();
                    if (ksh1ShouldBeLast && ksh2ShouldBeLast) {
                        return 0;
                    }
                    if (ksh1ShouldBeLast) {
                        return 1;
                    }
                    if (ksh2ShouldBeLast) {
                        return -1;
                    }
                    return (ksh1.getLabel().toString()).compareToIgnoreCase(
                            ksh2.getLabel().toString());
                }
            };

    private Dialog mKeyboardShortcutsDialog;
    private KeyCharacterMap mKeyCharacterMap;

    private KeyboardShortcuts(Context context) {
        this.mContext = new ContextThemeWrapper(context, R.style.KeyboardShortcutsDialog);
        this.mPackageManager = AppGlobals.getPackageManager();
        loadResources(context);
    }

    private static KeyboardShortcuts getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new KeyboardShortcuts(context);
        }
        return sInstance;
    }

    public static void show(Context context, int deviceId) {
        synchronized (sLock) {
            if (sInstance != null && !sInstance.mContext.equals(context)) {
                dismiss();
            }
            getInstance(context).showKeyboardShortcuts(deviceId);
        }
    }

    public static void toggle(Context context, int deviceId) {
        synchronized (sLock) {
            if (isShowing()) {
                dismiss();
            } else {
                show(context, deviceId);
            }
        }
    }

    public static void dismiss() {
        synchronized (sLock) {
            if (sInstance != null) {
                sInstance.dismissKeyboardShortcuts();
                sInstance = null;
            }
        }
    }

    private static boolean isShowing() {
        return sInstance != null && sInstance.mKeyboardShortcutsDialog != null
                && sInstance.mKeyboardShortcutsDialog.isShowing();
    }

    private void loadResources(Context context) {
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_HOME, context.getString(R.string.keyboard_key_home));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_BACK, context.getString(R.string.keyboard_key_back));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_DPAD_UP, context.getString(R.string.keyboard_key_dpad_up));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_DPAD_DOWN, context.getString(R.string.keyboard_key_dpad_down));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_DPAD_LEFT, context.getString(R.string.keyboard_key_dpad_left));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_DPAD_RIGHT, context.getString(R.string.keyboard_key_dpad_right));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_DPAD_CENTER, context.getString(R.string.keyboard_key_dpad_center));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_PERIOD, ".");
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_TAB, context.getString(R.string.keyboard_key_tab));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_SPACE, context.getString(R.string.keyboard_key_space));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_ENTER, context.getString(R.string.keyboard_key_enter));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_DEL, context.getString(R.string.keyboard_key_backspace));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                context.getString(R.string.keyboard_key_media_play_pause));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_MEDIA_STOP, context.getString(R.string.keyboard_key_media_stop));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_MEDIA_NEXT, context.getString(R.string.keyboard_key_media_next));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                context.getString(R.string.keyboard_key_media_previous));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_MEDIA_REWIND,
                context.getString(R.string.keyboard_key_media_rewind));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                context.getString(R.string.keyboard_key_media_fast_forward));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_PAGE_UP, context.getString(R.string.keyboard_key_page_up));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_PAGE_DOWN, context.getString(R.string.keyboard_key_page_down));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_A,
                context.getString(R.string.keyboard_key_button_template, "A"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_B,
                context.getString(R.string.keyboard_key_button_template, "B"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_C,
                context.getString(R.string.keyboard_key_button_template, "C"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_X,
                context.getString(R.string.keyboard_key_button_template, "X"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_Y,
                context.getString(R.string.keyboard_key_button_template, "Y"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_Z,
                context.getString(R.string.keyboard_key_button_template, "Z"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_L1,
                context.getString(R.string.keyboard_key_button_template, "L1"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_R1,
                context.getString(R.string.keyboard_key_button_template, "R1"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_L2,
                context.getString(R.string.keyboard_key_button_template, "L2"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_R2,
                context.getString(R.string.keyboard_key_button_template, "R2"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_START,
                context.getString(R.string.keyboard_key_button_template, "Start"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_SELECT,
                context.getString(R.string.keyboard_key_button_template, "Select"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BUTTON_MODE,
                context.getString(R.string.keyboard_key_button_template, "Mode"));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_FORWARD_DEL, context.getString(R.string.keyboard_key_forward_del));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_ESCAPE, "Esc");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_SYSRQ, "SysRq");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_BREAK, "Break");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_SCROLL_LOCK, "Scroll Lock");
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_MOVE_HOME, context.getString(R.string.keyboard_key_move_home));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_MOVE_END, context.getString(R.string.keyboard_key_move_end));
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_INSERT, context.getString(R.string.keyboard_key_insert));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_F1, "F1");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_F2, "F2");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_F3, "F3");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_F4, "F4");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_F5, "F5");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_F6, "F6");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_F7, "F7");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_F8, "F8");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_F9, "F9");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_F10, "F10");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_F11, "F11");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_F12, "F12");
        mSpecialCharacterNames.put(
                KeyEvent.KEYCODE_NUM_LOCK, context.getString(R.string.keyboard_key_num_lock));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_0,
                context.getString(R.string.keyboard_key_numpad_template, "0"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_1,
                context.getString(R.string.keyboard_key_numpad_template, "1"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_2,
                context.getString(R.string.keyboard_key_numpad_template, "2"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_3,
                context.getString(R.string.keyboard_key_numpad_template, "3"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_4,
                context.getString(R.string.keyboard_key_numpad_template, "4"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_5,
                context.getString(R.string.keyboard_key_numpad_template, "5"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_6,
                context.getString(R.string.keyboard_key_numpad_template, "6"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_7,
                context.getString(R.string.keyboard_key_numpad_template, "7"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_8,
                context.getString(R.string.keyboard_key_numpad_template, "8"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_9,
                context.getString(R.string.keyboard_key_numpad_template, "9"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_DIVIDE,
                context.getString(R.string.keyboard_key_numpad_template, "/"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY,
                context.getString(R.string.keyboard_key_numpad_template, "*"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
                context.getString(R.string.keyboard_key_numpad_template, "-"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_ADD,
                context.getString(R.string.keyboard_key_numpad_template, "+"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_DOT,
                context.getString(R.string.keyboard_key_numpad_template, "."));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_COMMA,
                context.getString(R.string.keyboard_key_numpad_template, ","));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_ENTER,
                context.getString(R.string.keyboard_key_numpad_template,
                        context.getString(R.string.keyboard_key_enter)));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_EQUALS,
                context.getString(R.string.keyboard_key_numpad_template, "="));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN,
                context.getString(R.string.keyboard_key_numpad_template, "("));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN,
                context.getString(R.string.keyboard_key_numpad_template, ")"));
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_ZENKAKU_HANKAKU, "半角/全角");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_EISU, "英数");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_MUHENKAN, "無変換");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_HENKAN, "変換");
        mSpecialCharacterNames.put(KeyEvent.KEYCODE_KATAKANA_HIRAGANA, "かな");

        mModifierNames.put(KeyEvent.META_META_ON, "Meta");
        mModifierNames.put(KeyEvent.META_CTRL_ON, "Ctrl");
        mModifierNames.put(KeyEvent.META_ALT_ON, "Alt");
        mModifierNames.put(KeyEvent.META_SHIFT_ON, "Shift");
        mModifierNames.put(KeyEvent.META_SYM_ON, "Sym");
        mModifierNames.put(KeyEvent.META_FUNCTION_ON, "Fn");

        mSpecialCharacterDrawables.put(
                KeyEvent.KEYCODE_DEL, context.getDrawable(R.drawable.ic_ksh_key_backspace));
        mSpecialCharacterDrawables.put(
                KeyEvent.KEYCODE_ENTER, context.getDrawable(R.drawable.ic_ksh_key_enter));
        mSpecialCharacterDrawables.put(
                KeyEvent.KEYCODE_DPAD_UP, context.getDrawable(R.drawable.ic_ksh_key_up));
        mSpecialCharacterDrawables.put(
                KeyEvent.KEYCODE_DPAD_RIGHT, context.getDrawable(R.drawable.ic_ksh_key_right));
        mSpecialCharacterDrawables.put(
                KeyEvent.KEYCODE_DPAD_DOWN, context.getDrawable(R.drawable.ic_ksh_key_down));
        mSpecialCharacterDrawables.put(
                KeyEvent.KEYCODE_DPAD_LEFT, context.getDrawable(R.drawable.ic_ksh_key_left));

        mModifierDrawables.put(
                KeyEvent.META_META_ON, context.getDrawable(R.drawable.ic_ksh_key_meta));
    }

    /**
     * Retrieves a {@link KeyCharacterMap} and assigns it to mKeyCharacterMap. If the given id is an
     * existing device, that device's map is used. Otherwise, it checks first all available devices
     * and if there is a full keyboard it uses that map, otherwise falls back to the Virtual
     * Keyboard with its default map.
     */
    private void retrieveKeyCharacterMap(int deviceId) {
        final InputManager inputManager = InputManager.getInstance();
        if (deviceId != -1) {
            final InputDevice inputDevice = inputManager.getInputDevice(deviceId);
            if (inputDevice != null) {
                mKeyCharacterMap = inputDevice.getKeyCharacterMap();
                return;
            }
        }
        final int[] deviceIds = inputManager.getInputDeviceIds();
        for (int i = 0; i < deviceIds.length; ++i) {
            final InputDevice inputDevice = inputManager.getInputDevice(deviceIds[i]);
            // -1 is the Virtual Keyboard, with the default key map. Use that one only as last
            // resort.
            if (inputDevice.getId() != -1 && inputDevice.isFullKeyboard()) {
                mKeyCharacterMap = inputDevice.getKeyCharacterMap();
                return;
            }
        }
        final InputDevice inputDevice = inputManager.getInputDevice(-1);
        mKeyCharacterMap = inputDevice.getKeyCharacterMap();
    }

    private void showKeyboardShortcuts(int deviceId) {
        retrieveKeyCharacterMap(deviceId);
        Recents.getSystemServices().requestKeyboardShortcuts(mContext,
                new KeyboardShortcutsReceiver() {
                    @Override
                    public void onKeyboardShortcutsReceived(
                            final List<KeyboardShortcutGroup> result) {
                        result.add(getSystemShortcuts());
                        final KeyboardShortcutGroup appShortcuts = getDefaultApplicationShortcuts();
                        if (appShortcuts != null) {
                            result.add(appShortcuts);
                        }
                        showKeyboardShortcutsDialog(result);
                    }
                }, deviceId);
    }

    private void dismissKeyboardShortcuts() {
        if (mKeyboardShortcutsDialog != null) {
            mKeyboardShortcutsDialog.dismiss();
            mKeyboardShortcutsDialog = null;
        }
    }

    private KeyboardShortcutGroup getSystemShortcuts() {
        final KeyboardShortcutGroup systemGroup = new KeyboardShortcutGroup(
                mContext.getString(R.string.keyboard_shortcut_group_system), true);
        systemGroup.addItem(new KeyboardShortcutInfo(
                mContext.getString(R.string.keyboard_shortcut_group_system_home),
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.META_META_ON));
        systemGroup.addItem(new KeyboardShortcutInfo(
                mContext.getString(R.string.keyboard_shortcut_group_system_back),
                KeyEvent.KEYCODE_DEL,
                KeyEvent.META_META_ON));
        systemGroup.addItem(new KeyboardShortcutInfo(
                mContext.getString(R.string.keyboard_shortcut_group_system_recents),
                KeyEvent.KEYCODE_TAB,
                KeyEvent.META_ALT_ON));
        systemGroup.addItem(new KeyboardShortcutInfo(
                mContext.getString(
                        R.string.keyboard_shortcut_group_system_notifications),
                KeyEvent.KEYCODE_N,
                KeyEvent.META_META_ON));
        systemGroup.addItem(new KeyboardShortcutInfo(
                mContext.getString(
                        R.string.keyboard_shortcut_group_system_shortcuts_helper),
                KeyEvent.KEYCODE_SLASH,
                KeyEvent.META_META_ON));
        systemGroup.addItem(new KeyboardShortcutInfo(
                mContext.getString(
                        R.string.keyboard_shortcut_group_system_switch_input),
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.META_META_ON));
        return systemGroup;
    }

    private KeyboardShortcutGroup getDefaultApplicationShortcuts() {
        final int userId = mContext.getUserId();
        List<KeyboardShortcutInfo> keyboardShortcutInfoAppItems = new ArrayList<>();

        // Assist.
        final AssistUtils assistUtils = new AssistUtils(mContext);
        final ComponentName assistComponent = assistUtils.getAssistComponentForUser(userId);
        PackageInfo assistPackageInfo = null;
        try {
            assistPackageInfo = mPackageManager.getPackageInfo(
                    assistComponent.getPackageName(), 0, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "PackageManagerService is dead");
        }

        if (assistPackageInfo != null) {
            final Icon assistIcon = Icon.createWithResource(
                    assistPackageInfo.applicationInfo.packageName,
                    assistPackageInfo.applicationInfo.icon);

            keyboardShortcutInfoAppItems.add(new KeyboardShortcutInfo(
                    mContext.getString(R.string.keyboard_shortcut_group_applications_assist),
                    assistIcon,
                    KeyEvent.KEYCODE_UNKNOWN,
                    KeyEvent.META_META_ON));
        }

        // Browser.
        final Icon browserIcon = getIconForIntentCategory(Intent.CATEGORY_APP_BROWSER, userId);
        if (browserIcon != null) {
            keyboardShortcutInfoAppItems.add(new KeyboardShortcutInfo(
                    mContext.getString(R.string.keyboard_shortcut_group_applications_browser),
                    browserIcon,
                    KeyEvent.KEYCODE_B,
                    KeyEvent.META_META_ON));
        }


        // Contacts.
        final Icon contactsIcon = getIconForIntentCategory(Intent.CATEGORY_APP_CONTACTS, userId);
        if (contactsIcon != null) {
            keyboardShortcutInfoAppItems.add(new KeyboardShortcutInfo(
                    mContext.getString(R.string.keyboard_shortcut_group_applications_contacts),
                    contactsIcon,
                    KeyEvent.KEYCODE_C,
                    KeyEvent.META_META_ON));
        }

        // Email.
        final Icon emailIcon = getIconForIntentCategory(Intent.CATEGORY_APP_EMAIL, userId);
        if (emailIcon != null) {
            keyboardShortcutInfoAppItems.add(new KeyboardShortcutInfo(
                    mContext.getString(R.string.keyboard_shortcut_group_applications_email),
                    emailIcon,
                    KeyEvent.KEYCODE_E,
                    KeyEvent.META_META_ON));
        }

        // Messaging.
        final Icon messagingIcon = getIconForIntentCategory(Intent.CATEGORY_APP_MESSAGING, userId);
        if (messagingIcon != null) {
            keyboardShortcutInfoAppItems.add(new KeyboardShortcutInfo(
                    mContext.getString(R.string.keyboard_shortcut_group_applications_im),
                    messagingIcon,
                    KeyEvent.KEYCODE_T,
                    KeyEvent.META_META_ON));
        }

        // Music.
        final Icon musicIcon = getIconForIntentCategory(Intent.CATEGORY_APP_MUSIC, userId);
        if (musicIcon != null) {
            keyboardShortcutInfoAppItems.add(new KeyboardShortcutInfo(
                    mContext.getString(R.string.keyboard_shortcut_group_applications_music),
                    musicIcon,
                    KeyEvent.KEYCODE_P,
                    KeyEvent.META_META_ON));
        }

        // Calendar.
        final Icon calendarIcon = getIconForIntentCategory(Intent.CATEGORY_APP_CALENDAR, userId);
        if (calendarIcon != null) {
            keyboardShortcutInfoAppItems.add(new KeyboardShortcutInfo(
                    mContext.getString(R.string.keyboard_shortcut_group_applications_calendar),
                    calendarIcon,
                    KeyEvent.KEYCODE_L,
                    KeyEvent.META_META_ON));
        }

        final int itemsSize = keyboardShortcutInfoAppItems.size();
        if (itemsSize == 0) {
            return null;
        }

        // Sorts by label, case insensitive with nulls and/or empty labels last.
        Collections.sort(keyboardShortcutInfoAppItems, mApplicationItemsComparator);
        return new KeyboardShortcutGroup(
                mContext.getString(R.string.keyboard_shortcut_group_applications),
                keyboardShortcutInfoAppItems,
                true);
    }

    private Icon getIconForIntentCategory(String intentCategory, int userId) {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(intentCategory);

        final PackageInfo packageInfo = getPackageInfoForIntent(intent, userId);
        if (packageInfo != null && packageInfo.applicationInfo.icon != 0) {
            return Icon.createWithResource(
                    packageInfo.applicationInfo.packageName,
                    packageInfo.applicationInfo.icon);
        }
        return null;
    }

    private PackageInfo getPackageInfoForIntent(Intent intent, int userId) {
        try {
            ResolveInfo handler;
            handler = mPackageManager.resolveIntent(
                    intent, intent.resolveTypeIfNeeded(mContext.getContentResolver()), 0, userId);
            if (handler == null || handler.activityInfo == null) {
                return null;
            }
            return mPackageManager.getPackageInfo(handler.activityInfo.packageName, 0, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "PackageManagerService is dead", e);
            return null;
        }
    }

    private void showKeyboardShortcutsDialog(
            final List<KeyboardShortcutGroup> keyboardShortcutGroups) {
        // Need to post on the main thread.
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                handleShowKeyboardShortcuts(keyboardShortcutGroups);
            }
        });
    }

    private void handleShowKeyboardShortcuts(List<KeyboardShortcutGroup> keyboardShortcutGroups) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(mContext);
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                LAYOUT_INFLATER_SERVICE);
        final View keyboardShortcutsView = inflater.inflate(
                R.layout.keyboard_shortcuts_view, null);
        populateKeyboardShortcuts((LinearLayout) keyboardShortcutsView.findViewById(
                R.id.keyboard_shortcuts_container), keyboardShortcutGroups);
        dialogBuilder.setView(keyboardShortcutsView);
        dialogBuilder.setPositiveButton(R.string.quick_settings_done, mDialogCloseListener);
        mKeyboardShortcutsDialog = dialogBuilder.create();
        mKeyboardShortcutsDialog.setCanceledOnTouchOutside(true);
        Window keyboardShortcutsWindow = mKeyboardShortcutsDialog.getWindow();
        keyboardShortcutsWindow.setType(TYPE_SYSTEM_DIALOG);
        mKeyboardShortcutsDialog.show();
    }

    private void populateKeyboardShortcuts(LinearLayout keyboardShortcutsLayout,
            List<KeyboardShortcutGroup> keyboardShortcutGroups) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        final int keyboardShortcutGroupsSize = keyboardShortcutGroups.size();
        TextView shortcutsKeyView = (TextView) inflater.inflate(
                R.layout.keyboard_shortcuts_key_view, null, false);
        shortcutsKeyView.measure(
                View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        final int shortcutKeyTextItemMinWidth = shortcutsKeyView.getMeasuredHeight();
        // Needed to be able to scale the image items to the same height as the text items.
        final int shortcutKeyIconItemHeightWidth = shortcutsKeyView.getMeasuredHeight()
                - shortcutsKeyView.getPaddingTop()
                - shortcutsKeyView.getPaddingBottom();
        for (int i = 0; i < keyboardShortcutGroupsSize; i++) {
            KeyboardShortcutGroup group = keyboardShortcutGroups.get(i);
            TextView categoryTitle = (TextView) inflater.inflate(
                    R.layout.keyboard_shortcuts_category_title, keyboardShortcutsLayout, false);
            categoryTitle.setText(group.getLabel());
            categoryTitle.setTextColor(group.isSystemGroup()
                    ? Utils.getColorAccent(mContext)
                    : mContext.getColor(R.color.ksh_application_group_color));
            keyboardShortcutsLayout.addView(categoryTitle);

            LinearLayout shortcutContainer = (LinearLayout) inflater.inflate(
                    R.layout.keyboard_shortcuts_container, keyboardShortcutsLayout, false);
            final int itemsSize = group.getItems().size();
            for (int j = 0; j < itemsSize; j++) {
                KeyboardShortcutInfo info = group.getItems().get(j);
                List<StringDrawableContainer> shortcutKeys = getHumanReadableShortcutKeys(info);
                if (shortcutKeys == null) {
                    // Ignore shortcuts we can't display keys for.
                    Log.w(TAG, "Keyboard Shortcut contains unsupported keys, skipping.");
                    continue;
                }
                View shortcutView = inflater.inflate(R.layout.keyboard_shortcut_app_item,
                        shortcutContainer, false);

                if (info.getIcon() != null) {
                    ImageView shortcutIcon = (ImageView) shortcutView
                            .findViewById(R.id.keyboard_shortcuts_icon);
                    shortcutIcon.setImageIcon(info.getIcon());
                    shortcutIcon.setVisibility(View.VISIBLE);
                }

                TextView shortcutKeyword = (TextView) shortcutView
                        .findViewById(R.id.keyboard_shortcuts_keyword);
                shortcutKeyword.setText(info.getLabel());
                if (info.getIcon() != null) {
                    RelativeLayout.LayoutParams lp =
                            (RelativeLayout.LayoutParams) shortcutKeyword.getLayoutParams();
                    lp.removeRule(RelativeLayout.ALIGN_PARENT_START);
                    shortcutKeyword.setLayoutParams(lp);
                }

                ViewGroup shortcutItemsContainer = (ViewGroup) shortcutView
                        .findViewById(R.id.keyboard_shortcuts_item_container);
                final int shortcutKeysSize = shortcutKeys.size();
                for (int k = 0; k < shortcutKeysSize; k++) {
                    StringDrawableContainer shortcutRepresentation = shortcutKeys.get(k);
                    if (shortcutRepresentation.mDrawable != null) {
                        ImageView shortcutKeyIconView = (ImageView) inflater.inflate(
                                R.layout.keyboard_shortcuts_key_icon_view, shortcutItemsContainer,
                                false);
                        Bitmap bitmap = Bitmap.createBitmap(shortcutKeyIconItemHeightWidth,
                                shortcutKeyIconItemHeightWidth, Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(bitmap);
                        shortcutRepresentation.mDrawable.setBounds(0, 0, canvas.getWidth(),
                                canvas.getHeight());
                        shortcutRepresentation.mDrawable.draw(canvas);
                        shortcutKeyIconView.setImageBitmap(bitmap);
                        shortcutKeyIconView.setImportantForAccessibility(
                                IMPORTANT_FOR_ACCESSIBILITY_YES);
                        shortcutKeyIconView.setAccessibilityDelegate(
                                new ShortcutKeyAccessibilityDelegate(
                                        shortcutRepresentation.mString));
                        shortcutItemsContainer.addView(shortcutKeyIconView);
                    } else if (shortcutRepresentation.mString != null) {
                        TextView shortcutKeyTextView = (TextView) inflater.inflate(
                                R.layout.keyboard_shortcuts_key_view, shortcutItemsContainer,
                                false);
                        shortcutKeyTextView.setMinimumWidth(shortcutKeyTextItemMinWidth);
                        shortcutKeyTextView.setText(shortcutRepresentation.mString);
                        shortcutKeyTextView.setAccessibilityDelegate(
                                new ShortcutKeyAccessibilityDelegate(
                                        shortcutRepresentation.mString));
                        shortcutItemsContainer.addView(shortcutKeyTextView);
                    }
                }
                shortcutContainer.addView(shortcutView);
            }
            keyboardShortcutsLayout.addView(shortcutContainer);
            if (i < keyboardShortcutGroupsSize - 1) {
                View separator = inflater.inflate(
                        R.layout.keyboard_shortcuts_category_separator, keyboardShortcutsLayout,
                        false);
                keyboardShortcutsLayout.addView(separator);
            }
        }
    }

    private List<StringDrawableContainer> getHumanReadableShortcutKeys(KeyboardShortcutInfo info) {
        List<StringDrawableContainer> shortcutKeys = getHumanReadableModifiers(info);
        if (shortcutKeys == null) {
            return null;
        }
        String shortcutKeyString = null;
        Drawable shortcutKeyDrawable = null;
        if (info.getBaseCharacter() > Character.MIN_VALUE) {
            shortcutKeyString = String.valueOf(info.getBaseCharacter());
        } else if (mSpecialCharacterDrawables.get(info.getKeycode()) != null) {
            shortcutKeyDrawable = mSpecialCharacterDrawables.get(info.getKeycode());
            shortcutKeyString = mSpecialCharacterNames.get(info.getKeycode());
        } else if (mSpecialCharacterNames.get(info.getKeycode()) != null) {
            shortcutKeyString = mSpecialCharacterNames.get(info.getKeycode());
        } else {
            // Special case for shortcuts with no base key or keycode.
            if (info.getKeycode() == KeyEvent.KEYCODE_UNKNOWN) {
                return shortcutKeys;
            }
            char displayLabel = mKeyCharacterMap.getDisplayLabel(info.getKeycode());
            if (displayLabel != 0) {
                shortcutKeyString = String.valueOf(displayLabel);
            } else {
                return null;
            }
        }

        if (shortcutKeyString != null) {
            shortcutKeys.add(new StringDrawableContainer(shortcutKeyString, shortcutKeyDrawable));
        } else {
            Log.w(TAG, "Keyboard Shortcut does not have a text representation, skipping.");
        }

        return shortcutKeys;
    }

    private List<StringDrawableContainer> getHumanReadableModifiers(KeyboardShortcutInfo info) {
        final List<StringDrawableContainer> shortcutKeys = new ArrayList<>();
        int modifiers = info.getModifiers();
        if (modifiers == 0) {
            return shortcutKeys;
        }
        for(int i = 0; i < mModifierNames.size(); ++i) {
            final int supportedModifier = mModifierNames.keyAt(i);
            if ((modifiers & supportedModifier) != 0) {
                shortcutKeys.add(new StringDrawableContainer(
                        mModifierNames.get(supportedModifier),
                        mModifierDrawables.get(supportedModifier)));
                modifiers &= ~supportedModifier;
            }
        }
        if (modifiers != 0) {
            // Remaining unsupported modifiers, don't show anything.
            return null;
        }
        return shortcutKeys;
    }

    private final class ShortcutKeyAccessibilityDelegate extends AccessibilityDelegate {
        private String mContentDescription;

        ShortcutKeyAccessibilityDelegate(String contentDescription) {
            mContentDescription = contentDescription;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            if (mContentDescription != null) {
                info.setContentDescription(mContentDescription.toLowerCase());
            }
        }
    }

    private static final class StringDrawableContainer {
        @NonNull
        public String mString;
        @Nullable
        public Drawable mDrawable;

        StringDrawableContainer(String string, Drawable drawable) {
            mString = string;
            mDrawable = drawable;
        }
    }
}

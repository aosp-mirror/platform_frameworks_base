/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.policy;

import android.annotation.SuppressLint;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.android.internal.policy.IShortcutService;
import com.android.internal.util.XmlUtils;
import com.android.server.input.KeyboardMetricsCollector;
import com.android.server.input.KeyboardMetricsCollector.KeyboardLogEvent;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages quick launch shortcuts by:
 * <li> Keeping the local copy in sync with the database (this is an observer)
 * <li> Returning a shortcut-matching intent to clients
 * <li> Returning particular kind of application intent by special key.
 */
public class ModifierShortcutManager {
    private static final String TAG = "ModifierShortcutManager";

    private static final String TAG_BOOKMARKS = "bookmarks";
    private static final String TAG_BOOKMARK = "bookmark";

    private static final String ATTRIBUTE_PACKAGE = "package";
    private static final String ATTRIBUTE_CLASS = "class";
    private static final String ATTRIBUTE_SHORTCUT = "shortcut";
    private static final String ATTRIBUTE_CATEGORY = "category";
    private static final String ATTRIBUTE_SHIFT = "shift";
    private static final String ATTRIBUTE_ROLE = "role";

    private final SparseArray<Intent> mIntentShortcuts = new SparseArray<>();
    private final SparseArray<Intent> mShiftShortcuts = new SparseArray<>();
    private final SparseArray<String> mRoleShortcuts = new SparseArray<String>();
    private final SparseArray<String> mShiftRoleShortcuts = new SparseArray<String>();
    private final Map<String, Intent> mRoleIntents = new HashMap<String, Intent>();

    private LongSparseArray<IShortcutService> mShortcutKeyServices = new LongSparseArray<>();

    /* Table of Application Launch keys.  Maps from key codes to intent categories.
     *
     * These are special keys that are used to launch particular kinds of applications,
     * such as a web browser.  HID defines nearly a hundred of them in the Consumer (0x0C)
     * usage page.  We don't support quite that many yet...
     */
    static SparseArray<String> sApplicationLaunchKeyCategories;
    static SparseArray<String> sApplicationLaunchKeyRoles;
    static {
        sApplicationLaunchKeyRoles = new SparseArray<String>();
        sApplicationLaunchKeyCategories = new SparseArray<String>();
        sApplicationLaunchKeyRoles.append(
                KeyEvent.KEYCODE_EXPLORER, RoleManager.ROLE_BROWSER);
        sApplicationLaunchKeyCategories.append(
                KeyEvent.KEYCODE_ENVELOPE, Intent.CATEGORY_APP_EMAIL);
        sApplicationLaunchKeyCategories.append(
                KeyEvent.KEYCODE_CONTACTS, Intent.CATEGORY_APP_CONTACTS);
        sApplicationLaunchKeyCategories.append(
                KeyEvent.KEYCODE_CALENDAR, Intent.CATEGORY_APP_CALENDAR);
        sApplicationLaunchKeyCategories.append(
                KeyEvent.KEYCODE_MUSIC, Intent.CATEGORY_APP_MUSIC);
        sApplicationLaunchKeyCategories.append(
                KeyEvent.KEYCODE_CALCULATOR, Intent.CATEGORY_APP_CALCULATOR);
    }

    public static final String EXTRA_ROLE =
            "com.android.server.policy.ModifierShortcutManager.EXTRA_ROLE";

    private final Context mContext;
    private final Handler mHandler;
    private final RoleManager mRoleManager;
    private final PackageManager mPackageManager;
    private boolean mSearchKeyShortcutPending = false;
    private boolean mConsumeSearchKeyUp = true;

    ModifierShortcutManager(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mPackageManager = mContext.getPackageManager();
        mRoleManager = mContext.getSystemService(RoleManager.class);
        mRoleManager.addOnRoleHoldersChangedListenerAsUser(mContext.getMainExecutor(),
                (String roleName, UserHandle user) -> {
                    mRoleIntents.remove(roleName);
                }, UserHandle.ALL);
        loadShortcuts();
    }

    /**
     * Gets the shortcut intent for a given keycode+modifier. Make sure you
     * strip whatever modifier is used for invoking shortcuts (for example,
     * if 'Sym+A' should invoke a shortcut on 'A', you should strip the
     * 'Sym' bit from the modifiers before calling this method.
     * <p>
     * This will first try an exact match (with modifiers), and then try a
     * match without modifiers (primary character on a key).
     *
     * @param kcm The key character map of the device on which the key was pressed.
     * @param keyCode The key code.
     * @param metaState The meta state, omitting any modifiers that were used
     * to invoke the shortcut.
     * @return The intent that matches the shortcut, or null if not found.
     */
    private Intent getIntent(KeyCharacterMap kcm, int keyCode, int metaState) {
        // If a modifier key other than shift is also pressed, skip it.
        final boolean isShiftOn = KeyEvent.metaStateHasModifiers(metaState, KeyEvent.META_SHIFT_ON);
        if (!isShiftOn && !KeyEvent.metaStateHasNoModifiers(metaState)) {
            return null;
        }

        Intent shortcutIntent = null;

        // If the Shift key is pressed, then search for the shift shortcuts.
        SparseArray<Intent> shortcutMap = isShiftOn ? mShiftShortcuts : mIntentShortcuts;

        // First try the exact keycode (with modifiers).
        int shortcutChar = kcm.get(keyCode, metaState);
        if (shortcutChar != 0) {
            shortcutIntent = shortcutMap.get(shortcutChar);
        }

        // Next try the primary character on that key.
        if (shortcutIntent == null) {
            shortcutChar = Character.toLowerCase(kcm.getDisplayLabel(keyCode));
            if (shortcutChar != 0) {
                shortcutIntent = shortcutMap.get(shortcutChar);

                if (shortcutIntent == null) {
                    // Check for role based shortcut
                    String role = isShiftOn ? mShiftRoleShortcuts.get(shortcutChar)
                            : mRoleShortcuts.get(shortcutChar);
                    if (role != null) {
                        shortcutIntent = getRoleLaunchIntent(role);
                    }
                }
            }
        }

        return shortcutIntent;
    }

    private Intent getRoleLaunchIntent(String role) {
        Intent intent = mRoleIntents.get(role);
        if (intent == null) {
            if (mRoleManager.isRoleAvailable(role)) {
                String rolePackage = mRoleManager.getDefaultApplication(role);
                if (rolePackage != null) {
                    intent = mPackageManager.getLaunchIntentForPackage(rolePackage);
                    intent.putExtra(EXTRA_ROLE, role);
                    mRoleIntents.put(role, intent);
                } else {
                    Log.w(TAG, "No default application for role " + role);
                }
            } else {
                Log.w(TAG, "Role " + role + " is not available.");
            }
        }
        return intent;
    }

    private void loadShortcuts() {

        try {
            XmlResourceParser parser = mContext.getResources().getXml(
                    com.android.internal.R.xml.bookmarks);
            XmlUtils.beginDocument(parser, TAG_BOOKMARKS);

            while (true) {
                XmlUtils.nextElement(parser);

                if (parser.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                if (!TAG_BOOKMARK.equals(parser.getName())) {
                    break;
                }

                String packageName = parser.getAttributeValue(null, ATTRIBUTE_PACKAGE);
                String className = parser.getAttributeValue(null, ATTRIBUTE_CLASS);
                String shortcutName = parser.getAttributeValue(null, ATTRIBUTE_SHORTCUT);
                String categoryName = parser.getAttributeValue(null, ATTRIBUTE_CATEGORY);
                String shiftName = parser.getAttributeValue(null, ATTRIBUTE_SHIFT);
                String roleName = parser.getAttributeValue(null, ATTRIBUTE_ROLE);

                if (TextUtils.isEmpty(shortcutName)) {
                    Log.w(TAG, "Shortcut required for bookmark with category=" + categoryName
                            + " packageName=" + packageName + " className=" + className
                            + " role=" + roleName + "shiftName=" + shiftName);
                    continue;
                }

                final int shortcutChar = shortcutName.charAt(0);
                final boolean isShiftShortcut = (shiftName != null && shiftName.equals("true"));
                final Intent intent;
                if (packageName != null && className != null) {
                    if (roleName != null || categoryName != null) {
                        Log.w(TAG, "Cannot specify role or category when package and class"
                                + " are present for bookmark packageName=" + packageName
                                + " className=" + className + " shortcutChar=" + shortcutChar);
                        continue;
                    }
                    ComponentName componentName = new ComponentName(packageName, className);
                    try {
                        mPackageManager.getActivityInfo(componentName,
                                PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                                        | PackageManager.MATCH_UNINSTALLED_PACKAGES);
                    } catch (PackageManager.NameNotFoundException e) {
                        String[] packages = mPackageManager.canonicalToCurrentPackageNames(
                                new String[] { packageName });
                        componentName = new ComponentName(packages[0], className);
                        try {
                            mPackageManager.getActivityInfo(componentName,
                                    PackageManager.MATCH_DIRECT_BOOT_AWARE
                                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                                            | PackageManager.MATCH_UNINSTALLED_PACKAGES);
                        } catch (PackageManager.NameNotFoundException e1) {
                            Log.w(TAG, "Unable to add bookmark: " + packageName
                                    + "/" + className + " not found.");
                            continue;
                        }
                    }

                    intent = new Intent(Intent.ACTION_MAIN);
                    intent.addCategory(Intent.CATEGORY_LAUNCHER);
                    intent.setComponent(componentName);
                } else if (categoryName != null) {
                    if (roleName != null) {
                        Log.w(TAG, "Cannot specify role bookmark when category is present for"
                                + " bookmark shortcutChar=" + shortcutChar
                                + " category= " + categoryName);
                        continue;
                    }
                    intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, categoryName);
                } else if (roleName != null) {
                    // We can't resolve the role at the time of this file being parsed as the
                    // device hasn't finished booting, so we will look it up lazily.
                    if (isShiftShortcut) {
                        mShiftRoleShortcuts.put(shortcutChar, roleName);
                    } else {
                        mRoleShortcuts.put(shortcutChar, roleName);
                    }
                    continue;
                } else {
                    Log.w(TAG, "Unable to add bookmark for shortcut " + shortcutName
                            + ": missing package/class, category or role attributes");
                    continue;
                }

                if (isShiftShortcut) {
                    mShiftShortcuts.put(shortcutChar, intent);
                } else {
                    mIntentShortcuts.put(shortcutChar, intent);
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Got exception parsing bookmarks.", e);
        }
    }

    void registerShortcutKey(long shortcutCode, IShortcutService shortcutService)
            throws RemoteException {
        IShortcutService service = mShortcutKeyServices.get(shortcutCode);
        if (service != null && service.asBinder().pingBinder()) {
            throw new RemoteException("Key already exists.");
        }

        mShortcutKeyServices.put(shortcutCode, shortcutService);
    }

    /**
     * Handle the shortcut to {@link IShortcutService}
     * @param keyCode The key code of the event.
     * @param metaState The meta key modifier state.
     * @return True if invoked the shortcut, otherwise false.
     */
    private boolean handleShortcutService(int keyCode, int metaState) {
        long shortcutCode = keyCode;
        if ((metaState & KeyEvent.META_CTRL_ON) != 0) {
            shortcutCode |= ((long) KeyEvent.META_CTRL_ON) << Integer.SIZE;
        }

        if ((metaState & KeyEvent.META_ALT_ON) != 0) {
            shortcutCode |= ((long) KeyEvent.META_ALT_ON) << Integer.SIZE;
        }

        if ((metaState & KeyEvent.META_SHIFT_ON) != 0) {
            shortcutCode |= ((long) KeyEvent.META_SHIFT_ON) << Integer.SIZE;
        }

        if ((metaState & KeyEvent.META_META_ON) != 0) {
            shortcutCode |= ((long) KeyEvent.META_META_ON) << Integer.SIZE;
        }

        IShortcutService shortcutService = mShortcutKeyServices.get(shortcutCode);
        if (shortcutService != null) {
            try {
                shortcutService.notifyShortcutKeyPressed(shortcutCode);
            } catch (RemoteException e) {
                mShortcutKeyServices.delete(shortcutCode);
            }
            return true;
        }
        return false;
    }

    /**
     * Handle the shortcut to {@link Intent}
     *
     * @param kcm the {@link KeyCharacterMap} associated with the keyboard device.
     * @param keyEvent The key event.
     * @param metaState The meta key modifier state.
     * @return True if invoked the shortcut, otherwise false.
     */
    @SuppressLint("MissingPermission")
    private boolean handleIntentShortcut(KeyCharacterMap kcm, KeyEvent keyEvent, int metaState) {
        final int keyCode = keyEvent.getKeyCode();
        // Shortcuts are invoked through Search+key, so intercept those here
        // Any printing key that is chorded with Search should be consumed
        // even if no shortcut was invoked.  This prevents text from being
        // inadvertently inserted when using a keyboard that has built-in macro
        // shortcut keys (that emit Search+x) and some of them are not registered.
        if (mSearchKeyShortcutPending) {
            if (kcm.isPrintingKey(keyCode)) {
                mConsumeSearchKeyUp = true;
                mSearchKeyShortcutPending = false;
            } else {
                return false;
            }
        } else if ((metaState & KeyEvent.META_META_MASK) != 0) {
            // Invoke shortcuts using Meta.
            metaState &= ~KeyEvent.META_META_MASK;
        } else {
            Intent intent = null;
            // Handle application launch keys.
            String role = sApplicationLaunchKeyRoles.get(keyCode);
            String category = sApplicationLaunchKeyCategories.get(keyCode);
            if (role != null) {
                intent = getRoleLaunchIntent(role);
            } else if (category != null) {
                intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category);
            }

            if (intent != null) {
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                } catch (ActivityNotFoundException ex) {
                    Slog.w(TAG, "Dropping application launch key because "
                            + "the activity to which it is registered was not found: "
                            + "keyCode=" + KeyEvent.keyCodeToString(keyCode) + ","
                            + " category=" + category + " role=" + role);
                }
                logKeyboardShortcut(keyEvent, KeyboardLogEvent.getLogEventFromIntent(intent));
                return true;
            } else {
                return false;
            }
        }

        final Intent shortcutIntent = getIntent(kcm, keyCode, metaState);
        if (shortcutIntent != null) {
            shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                mContext.startActivityAsUser(shortcutIntent, UserHandle.CURRENT);
            } catch (ActivityNotFoundException ex) {
                Slog.w(TAG, "Dropping shortcut key combination because "
                        + "the activity to which it is registered was not found: "
                        + "META+ or SEARCH" + KeyEvent.keyCodeToString(keyCode));
            }
            logKeyboardShortcut(keyEvent, KeyboardLogEvent.getLogEventFromIntent(shortcutIntent));
            return true;
        }
        return false;
    }

    private void logKeyboardShortcut(KeyEvent event, KeyboardLogEvent logEvent) {
        mHandler.post(() -> handleKeyboardLogging(event, logEvent));
    }

    private void handleKeyboardLogging(KeyEvent event, KeyboardLogEvent logEvent) {
        final InputManager inputManager = mContext.getSystemService(InputManager.class);
        final InputDevice inputDevice = inputManager != null
                ? inputManager.getInputDevice(event.getDeviceId()) : null;
        KeyboardMetricsCollector.logKeyboardSystemsEventReportedAtom(inputDevice,
                logEvent, event.getMetaState(), event.getKeyCode());
    }

    /**
     * Handle the shortcut from {@link KeyEvent}
     *
     * @param event Description of the key event.
     * @return True if invoked the shortcut, otherwise false.
     */
    boolean interceptKey(KeyEvent event) {
        if (event.getRepeatCount() != 0) {
            return false;
        }

        final int metaState = event.getModifiers();
        final int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                mSearchKeyShortcutPending = true;
                mConsumeSearchKeyUp = false;
            } else {
                mSearchKeyShortcutPending = false;
                if (mConsumeSearchKeyUp) {
                    mConsumeSearchKeyUp = false;
                    return true;
                }
            }
            return false;
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        final KeyCharacterMap kcm = event.getKeyCharacterMap();
        if (handleIntentShortcut(kcm, event, metaState)) {
            return true;
        }

        if (handleShortcutService(keyCode, metaState)) {
            return true;
        }

        return false;
    }
}

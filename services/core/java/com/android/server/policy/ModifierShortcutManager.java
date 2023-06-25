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

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.android.internal.policy.IShortcutService;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Manages quick launch shortcuts by:
 * <li> Keeping the local copy in sync with the database (this is an observer)
 * <li> Returning a shortcut-matching intent to clients
 * <li> Returning particular kind of application intent by special key.
 */
class ModifierShortcutManager {
    private static final String TAG = "WindowManager";

    private static final String TAG_BOOKMARKS = "bookmarks";
    private static final String TAG_BOOKMARK = "bookmark";

    private static final String ATTRIBUTE_PACKAGE = "package";
    private static final String ATTRIBUTE_CLASS = "class";
    private static final String ATTRIBUTE_SHORTCUT = "shortcut";
    private static final String ATTRIBUTE_CATEGORY = "category";
    private static final String ATTRIBUTE_SHIFT = "shift";

    private final SparseArray<Intent> mIntentShortcuts = new SparseArray<>();
    private final SparseArray<Intent> mShiftShortcuts = new SparseArray<>();

    private LongSparseArray<IShortcutService> mShortcutKeyServices = new LongSparseArray<>();

    /* Table of Application Launch keys.  Maps from key codes to intent categories.
     *
     * These are special keys that are used to launch particular kinds of applications,
     * such as a web browser.  HID defines nearly a hundred of them in the Consumer (0x0C)
     * usage page.  We don't support quite that many yet...
     */
    static SparseArray<String> sApplicationLaunchKeyCategories;
    static {
        sApplicationLaunchKeyCategories = new SparseArray<String>();
        sApplicationLaunchKeyCategories.append(
                KeyEvent.KEYCODE_EXPLORER, Intent.CATEGORY_APP_BROWSER);
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

    private final Context mContext;
    private boolean mSearchKeyShortcutPending = false;
    private boolean mConsumeSearchKeyUp = true;

    ModifierShortcutManager(Context context) {
        mContext = context;
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
            }
        }

        return shortcutIntent;
    }

    private void loadShortcuts() {
        PackageManager packageManager = mContext.getPackageManager();
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

                if (TextUtils.isEmpty(shortcutName)) {
                    Log.w(TAG, "Unable to get shortcut for: " + packageName + "/" + className);
                    continue;
                }

                final int shortcutChar = shortcutName.charAt(0);
                final boolean isShiftShortcut = (shiftName != null && shiftName.equals("true"));

                final Intent intent;
                if (packageName != null && className != null) {
                    ComponentName componentName = new ComponentName(packageName, className);
                    try {
                        packageManager.getActivityInfo(componentName,
                                PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                                        | PackageManager.MATCH_UNINSTALLED_PACKAGES);
                    } catch (PackageManager.NameNotFoundException e) {
                        String[] packages = packageManager.canonicalToCurrentPackageNames(
                                new String[] { packageName });
                        componentName = new ComponentName(packages[0], className);
                        try {
                            packageManager.getActivityInfo(componentName,
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
                    intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, categoryName);
                } else {
                    Log.w(TAG, "Unable to add bookmark for shortcut " + shortcutName
                            + ": missing package/class or category attributes");
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
     * @param keyCode The key code of the event.
     * @param metaState The meta key modifier state.
     * @return True if invoked the shortcut, otherwise false.
     */
    private boolean handleIntentShortcut(KeyCharacterMap kcm, int keyCode, int metaState) {
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
            // Handle application launch keys.
            String category = sApplicationLaunchKeyCategories.get(keyCode);
            if (category != null) {
                Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                } catch (ActivityNotFoundException ex) {
                    Slog.w(TAG, "Dropping application launch key because "
                            + "the activity to which it is registered was not found: "
                            + "keyCode=" + KeyEvent.keyCodeToString(keyCode) + ","
                            + " category=" + category);
                }
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
            return true;
        }
        return false;
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
        if (handleIntentShortcut(kcm, keyCode, metaState)) {
            return true;
        }

        if (handleShortcutService(keyCode, metaState)) {
            return true;
        }

        return false;
    }
}

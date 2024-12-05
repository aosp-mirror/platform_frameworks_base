/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.input;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.hardware.input.AppLaunchData;
import android.hardware.input.InputGestureData;
import android.hardware.input.KeyGestureEvent;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.android.internal.R;
import com.android.internal.policy.IShortcutService;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages quick launch app shortcuts by parsing {@code bookmarks.xml} and intercepting the
 * correct key combinations for the app shortcuts defined.
 *
 * Currently there are 2 ways of defining shortcuts:
 * - Adding shortcuts to {@code bookmarks.xml}
 * - Calling into {@code registerShortcutKey()}.
 */
final class AppLaunchShortcutManager {
    private static final String TAG = "AppShortcutManager";

    private static final String TAG_BOOKMARKS = "bookmarks";
    private static final String TAG_BOOKMARK = "bookmark";

    private static final String ATTRIBUTE_PACKAGE = "package";
    private static final String ATTRIBUTE_CLASS = "class";
    private static final String ATTRIBUTE_SHORTCUT = "shortcut";
    private static final String ATTRIBUTE_CATEGORY = "category";
    private static final String ATTRIBUTE_SHIFT = "shift";
    private static final String ATTRIBUTE_ROLE = "role";

    private static final int SHORTCUT_CODE_META_MASK =
            KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON
                    | KeyEvent.META_META_ON;

    private LongSparseArray<IShortcutService> mShortcutKeyServices = new LongSparseArray<>();

    /* Table of Application Launch keys.  Maps from key codes to intent categories.
     *
     * These are special keys that are used to launch particular kinds of applications,
     * such as a web browser.  HID defines nearly a hundred of them in the Consumer (0x0C)
     * usage page.  We don't support quite that many yet...
     */
    private static final SparseArray<String> sApplicationLaunchKeyCategories;
    private static final SparseArray<String> sApplicationLaunchKeyRoles;
    static {
        sApplicationLaunchKeyRoles = new SparseArray<>();
        sApplicationLaunchKeyCategories = new SparseArray<>();
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

    private final Context mContext;
    private boolean mSearchKeyShortcutPending = false;
    private boolean mConsumeSearchKeyUp = true;
    private final Map<InputGestureData.Trigger, InputGestureData> mBookmarks = new HashMap<>();

    @SuppressLint("MissingPermission")
    AppLaunchShortcutManager(Context context) {
        mContext = context;
    }

    public void systemRunning() {
        loadShortcuts();
    }

    private void loadShortcuts() {
        try {
            XmlResourceParser parser = mContext.getResources().getXml(R.xml.bookmarks);
            XmlUtils.beginDocument(parser, TAG_BOOKMARKS);
            KeyCharacterMap virtualKcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

            while (true) {
                XmlUtils.nextElement(parser);

                if (parser.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                if (!TAG_BOOKMARK.equals(parser.getName())) {
                    Log.w(TAG, "TAG_BOOKMARK not found");
                    break;
                }

                String packageName = parser.getAttributeValue(null, ATTRIBUTE_PACKAGE);
                String className = parser.getAttributeValue(null, ATTRIBUTE_CLASS);
                String categoryName = parser.getAttributeValue(null, ATTRIBUTE_CATEGORY);
                String shiftName = parser.getAttributeValue(null, ATTRIBUTE_SHIFT);
                String roleName = parser.getAttributeValue(null, ATTRIBUTE_ROLE);
                String shortcut = parser.getAttributeValue(null, ATTRIBUTE_SHORTCUT);
                int keycode;
                int modifierState;
                TypedArray a = mContext.getResources().obtainAttributes(parser,
                        R.styleable.Bookmark);
                try {
                    keycode = a.getInt(R.styleable.Bookmark_keycode, KeyEvent.KEYCODE_UNKNOWN);
                    modifierState = a.getInt(R.styleable.Bookmark_modifierState, 0);
                } finally {
                    a.recycle();
                }
                if (keycode == KeyEvent.KEYCODE_UNKNOWN && !TextUtils.isEmpty(shortcut)) {
                    // Fetch keycode using shortcut char
                    KeyEvent[] events = virtualKcm.getEvents(new char[]{shortcut.toLowerCase(
                            Locale.ROOT).charAt(0)});
                    // Single key press can generate the character
                    if (events != null && events.length == 2) {
                        keycode = events[0].getKeyCode();
                    }
                }
                if (keycode == KeyEvent.KEYCODE_UNKNOWN) {
                    Log.w(TAG, "Keycode required for bookmark with category=" + categoryName
                            + " packageName=" + packageName + " className=" + className
                            + " role=" + roleName + " shiftName=" + shiftName
                            + " shortcut=" + shortcut + " modifierState=" + modifierState);
                    continue;
                }

                if (modifierState == 0) {
                    // Fetch modifierState using shiftName
                    boolean isShiftShortcut = shiftName != null && shiftName.toLowerCase(
                            Locale.ROOT).equals("true");
                    modifierState =
                            KeyEvent.META_META_ON | (isShiftShortcut ? KeyEvent.META_SHIFT_ON : 0);
                }
                AppLaunchData launchData = null;
                if (!TextUtils.isEmpty(packageName) && !TextUtils.isEmpty(className)) {
                    launchData = AppLaunchData.createLaunchDataForComponent(packageName, className);
                } else if (!TextUtils.isEmpty(categoryName)) {
                    launchData = AppLaunchData.createLaunchDataForCategory(categoryName);
                } else if (!TextUtils.isEmpty(roleName)) {
                    launchData = AppLaunchData.createLaunchDataForRole(roleName);
                }
                if (launchData != null) {
                    Log.d(TAG, "adding shortcut " + launchData + " modifierState="
                            + modifierState + " keycode=" + keycode);
                    // All bookmarks are based on Action key
                    InputGestureData bookmark = new InputGestureData.Builder()
                            .setTrigger(InputGestureData.createKeyTrigger(keycode, modifierState))
                            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION)
                            .setAppLaunchData(launchData)
                            .build();
                    mBookmarks.put(bookmark.getTrigger(), bookmark);
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Got exception parsing bookmarks.", e);
        }
    }

    public void registerShortcutKey(long shortcutCode, IShortcutService shortcutService)
            throws RemoteException {
        IShortcutService service = mShortcutKeyServices.get(shortcutCode);
        if (service != null && service.asBinder().pingBinder()) {
            throw new RemoteException("Key: " + shortcutCode + ", already exists.");
        }

        mShortcutKeyServices.put(shortcutCode, shortcutService);
    }

    /**
     * Handle the shortcut to {@link IShortcutService}
     * @return true if invoked the shortcut, otherwise false.
     */
    public boolean handleShortcutService(KeyEvent event) {
        // TODO(b/358569822): Ideally shortcut service custom shortcuts should be either
        //  migrated to bookmarks or customizable shortcut APIs.
        final long shortcutCodeMeta = event.getMetaState() & SHORTCUT_CODE_META_MASK;
        if (shortcutCodeMeta == 0) {
            return false;
        }
        long shortcutCode = event.getKeyCode() | (shortcutCodeMeta << Integer.SIZE);
        IShortcutService shortcutService = mShortcutKeyServices.get(shortcutCode);
        if (shortcutService != null) {
            try {
                shortcutService.notifyShortcutKeyPressed(shortcutCode);
            } catch (RemoteException e) {
                Log.w(TAG,
                        "Shortcut key service not found, deleting shortcut code: " + shortcutCode);
                mShortcutKeyServices.delete(shortcutCode);
            }
            return true;
        }
        return false;
    }

    /**
     * Handle the shortcut to Launch application.
     *
     * @param keyEvent The key event.
     */
    @SuppressLint("MissingPermission")
    @Nullable
    private AppLaunchData interceptShortcut(KeyEvent keyEvent) {
        final int keyCode = keyEvent.getKeyCode();
        final int modifierState = keyEvent.getMetaState() & SHORTCUT_CODE_META_MASK;
        // Shortcuts are invoked through Search+key, so intercept those here
        // Any printing key that is chorded with Search should be consumed
        // even if no shortcut was invoked.  This prevents text from being
        // inadvertently inserted when using a keyboard that has built-in macro
        // shortcut keys (that emit Search+x) and some of them are not registered.
        if (mSearchKeyShortcutPending) {
            KeyCharacterMap kcm = keyEvent.getKeyCharacterMap();
            if (kcm != null && kcm.isPrintingKey(keyCode)) {
                mConsumeSearchKeyUp = true;
                mSearchKeyShortcutPending = false;
            } else {
                return null;
            }
        } else if (modifierState == 0) {
            AppLaunchData appLaunchData = null;
            // Handle application launch keys.
            String role = sApplicationLaunchKeyRoles.get(keyCode);
            String category = sApplicationLaunchKeyCategories.get(keyCode);
            if (!TextUtils.isEmpty(role)) {
                appLaunchData = AppLaunchData.createLaunchDataForRole(role);
            } else if (!TextUtils.isEmpty(category)) {
                appLaunchData = AppLaunchData.createLaunchDataForCategory(category);
            }

            return appLaunchData;
        }

        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return null;
        }
        InputGestureData gesture = mBookmarks.get(
                InputGestureData.createKeyTrigger(keyCode, modifierState));
        if (gesture == null) {
            return null;
        }
        return gesture.getAction().appLaunchData();
    }

    /**
     * Handle the shortcut from {@link KeyEvent}
     *
     * @param event Description of the key event.
     */
    public InterceptKeyResult interceptKey(KeyEvent event) {
        if (event.getRepeatCount() != 0) {
            return InterceptKeyResult.DO_NOTHING;
        }

        final int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                mSearchKeyShortcutPending = true;
                mConsumeSearchKeyUp = false;
            } else {
                mSearchKeyShortcutPending = false;
                if (mConsumeSearchKeyUp) {
                    mConsumeSearchKeyUp = false;
                    return InterceptKeyResult.CONSUME_KEY;
                }
            }
            return InterceptKeyResult.DO_NOTHING;
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return InterceptKeyResult.DO_NOTHING;
        }

        // Intercept shortcuts defined in bookmarks or through application launch keycodes
        return new InterceptKeyResult(/* consumed =*/ false, interceptShortcut(event));
    }

    /**
     * @return a list of {@link InputGestureData} containing the application launch shortcuts parsed
     * at boot time from {@code bookmarks.xml}.
     */
    public List<InputGestureData> getBookmarks() {
        return new ArrayList<>(mBookmarks.values());
    }

    public void dump(IndentingPrintWriter ipw) {
        ipw.println("AppLaunchShortcutManager:");
        ipw.increaseIndent();
        for (InputGestureData data : mBookmarks.values()) {
            ipw.println(data);
        }
        ipw.decreaseIndent();
    }

    public record InterceptKeyResult(boolean consumed, @Nullable AppLaunchData appLaunchData) {
        private static final InterceptKeyResult DO_NOTHING = new InterceptKeyResult(false, null);
        private static final InterceptKeyResult CONSUME_KEY = new InterceptKeyResult(true, null);
    }
}

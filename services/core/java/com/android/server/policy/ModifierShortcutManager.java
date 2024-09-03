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

import static com.android.server.flags.Flags.modifierShortcutManagerMultiuser;
import static com.android.hardware.input.Flags.modifierShortcutManagerRefactor;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Icon;
import android.hardware.input.KeyGestureEvent;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.KeyboardShortcutInfo;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.IShortcutService;
import com.android.internal.util.XmlUtils;
import com.android.server.LocalServices;
import com.android.server.input.InputManagerInternal;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final SparseArray<Intent> mCategoryShortcuts = new SparseArray<>();
    private final SparseArray<Intent> mShiftCategoryShortcuts = new SparseArray<>();
    private final SparseArray<String> mRoleShortcuts = new SparseArray<String>();
    private final SparseArray<String> mShiftRoleShortcuts = new SparseArray<String>();
    private final Map<String, Intent> mRoleIntents = new HashMap<String, Intent>();
    private final SparseArray<ComponentName> mComponentShortcuts = new SparseArray<>();
    private final SparseArray<ComponentName> mShiftComponentShortcuts = new SparseArray<>();
    private final Map<ComponentName, Intent> mComponentIntents =
            new HashMap<ComponentName, Intent>();

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
    private final InputManagerInternal mInputManagerInternal;
    private boolean mSearchKeyShortcutPending = false;
    private boolean mConsumeSearchKeyUp = true;
    private UserHandle mCurrentUser;
    private final Map<Pair<Character, Boolean>, Bookmark> mBookmarks = new HashMap<>();

    ModifierShortcutManager(Context context, Handler handler, UserHandle currentUser) {
        mContext = context;
        mHandler = handler;
        RoleManager rm = mContext.getSystemService(RoleManager.class);
        rm.addOnRoleHoldersChangedListenerAsUser(mContext.getMainExecutor(),
                (String roleName, UserHandle user) -> {
                    if (modifierShortcutManagerRefactor()) {
                        mBookmarks.values().stream().filter(b ->
                                b instanceof RoleBookmark
                                && ((RoleBookmark) b).getRole().equals(roleName))
                                .forEach(Bookmark::clearIntent);
                    } else {
                        mRoleIntents.remove(roleName);
                    }
                }, UserHandle.ALL);
        mCurrentUser = currentUser;
        mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
        loadShortcuts();
    }

    void setCurrentUser(UserHandle newUser) {
        mCurrentUser = newUser;

        // Role based shortcuts may resolve to different apps for different users
        // so clear the cache.
        clearRoleIntents();
        clearComponentIntents();
    }

    void clearRoleIntents() {
        if (modifierShortcutManagerRefactor()) {
            mBookmarks.values().stream().filter(b ->
                    b instanceof RoleBookmark).forEach(Bookmark::clearIntent);
        } else {
            mRoleIntents.clear();
        }
    }

    void clearComponentIntents() {
        if (modifierShortcutManagerRefactor()) {
            mBookmarks.values().stream().filter(b ->
                    b instanceof ComponentBookmark).forEach(Bookmark::clearIntent);
        } else {
            mComponentIntents.clear();
        }
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
    @Nullable
    private Intent getIntent(KeyCharacterMap kcm, int keyCode, int metaState) {
        // If a modifier key other than shift is also pressed, skip it.
        final boolean isShiftOn = KeyEvent.metaStateHasModifiers(
                metaState, KeyEvent.META_SHIFT_ON);
        if (!isShiftOn && !KeyEvent.metaStateHasNoModifiers(metaState)) {
            return null;
        }

        Intent shortcutIntent = null;

        // First try the exact keycode (with modifiers).
        int shortcutChar = kcm.get(keyCode, metaState);
        if (shortcutChar == 0) {
            return null;
        }

        if (modifierShortcutManagerRefactor()) {
            Bookmark bookmark = mBookmarks.get(new Pair<>((char) shortcutChar, isShiftOn));
            if (bookmark == null) {
                // Next try the primary character on that key.
                shortcutChar = Character.toLowerCase(kcm.getDisplayLabel(keyCode));
                if (shortcutChar == 0) {
                    return null;
                }
                bookmark = mBookmarks.get(new Pair<>((char) shortcutChar, isShiftOn));
            }

            if (bookmark != null) {
                Context context = modifierShortcutManagerMultiuser()
                        ? mContext.createContextAsUser(mCurrentUser, 0) : mContext;
                shortcutIntent = bookmark.getIntent(context);
            } else {
                Log.d(TAG, "No bookmark found for "
                        + (isShiftOn ? "SHIFT+" : "") + (char) shortcutChar);
            }
        } else {
            // If the Shift key is pressed, then search for the shift shortcuts.
            SparseArray<Intent> shortcutMap = isShiftOn
                    ? mShiftCategoryShortcuts : mCategoryShortcuts;
            shortcutIntent = shortcutMap.get(shortcutChar);

            if (shortcutIntent == null) {
                // Next try the primary character on that key.
                shortcutChar = Character.toLowerCase(kcm.getDisplayLabel(keyCode));
                if (shortcutChar == 0) {
                    return null;
                }
                shortcutIntent = shortcutMap.get(shortcutChar);
            }

            if (shortcutIntent == null) {
                // Next check for role based shortcut with primary character.
                String role = isShiftOn ? mShiftRoleShortcuts.get(shortcutChar)
                        : mRoleShortcuts.get(shortcutChar);
                if (role != null) {
                    shortcutIntent = getRoleLaunchIntent(role);
                }
            }

            if (modifierShortcutManagerMultiuser()) {
                if (shortcutIntent == null) {
                    // Next check component based shortcuts with primary character.
                    ComponentName component = isShiftOn
                            ? mShiftComponentShortcuts.get(shortcutChar)
                            : mComponentShortcuts.get(shortcutChar);
                    if (component != null) {
                        shortcutIntent = resolveComponentNameIntent(component);
                    }
                }
            }
        }
        return shortcutIntent;
    }

    @Nullable
    private static Intent getRoleLaunchIntent(Context context, String role) {
        Intent intent = null;
        RoleManager rm = context.getSystemService(RoleManager.class);
        PackageManager pm = context.getPackageManager();
        if (rm.isRoleAvailable(role)) {
            String rolePackage = rm.getDefaultApplication(role);
            if (rolePackage != null) {
                intent = pm.getLaunchIntentForPackage(rolePackage);
                if (intent != null) {
                    intent.putExtra(EXTRA_ROLE, role);

                } else {
                    Log.w(TAG, "No launch intent for role " + role);
                }
            } else {
                Log.w(TAG, "No default application for role "
                        + role + " user=" + context.getUser());
            }
        } else {
            Log.w(TAG, "Role " + role + " is not available.");
        }
        return intent;
    }

    @Nullable
    private Intent getRoleLaunchIntent(String role) {
        Intent intent = mRoleIntents.get(role);
        if (intent == null) {
            Context context = modifierShortcutManagerMultiuser()
                    ? mContext.createContextAsUser(mCurrentUser, 0) : mContext;
            intent = getRoleLaunchIntent(context, role);
            if (intent != null) {
                mRoleIntents.put(role, intent);
            }
        }

        return intent;
    }

    private void loadShortcuts() {
        try {
            XmlResourceParser parser = mContext.getResources().getXml(R.xml.bookmarks);
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

                final boolean isShiftShortcut = (shiftName != null && shiftName.equals("true"));

                if (modifierShortcutManagerRefactor()) {
                    final char shortcutChar = shortcutName.charAt(0);
                    Bookmark bookmark = null;
                    if (packageName != null && className != null) {
                        bookmark = new ComponentBookmark(
                                shortcutChar, isShiftShortcut, packageName, className);
                    } else if (categoryName != null) {
                        bookmark = new CategoryBookmark(
                                shortcutChar, isShiftShortcut, categoryName);
                    } else if (roleName != null) {
                        bookmark = new RoleBookmark(shortcutChar, isShiftShortcut, roleName);
                    }
                    if (bookmark != null) {
                        Log.d(TAG, "adding shortcut " + bookmark + "shift="
                                + isShiftShortcut + " char=" + shortcutChar);
                        mBookmarks.put(new Pair<>(shortcutChar, isShiftShortcut), bookmark);
                    }
                } else {
                    final int shortcutChar = shortcutName.charAt(0);
                    if (packageName != null && className != null) {
                        if (roleName != null || categoryName != null) {
                            Log.w(TAG, "Cannot specify role or category when package and class"
                                    + " are present for bookmark packageName=" + packageName
                                    + " className=" + className + " shortcutChar=" + shortcutChar);
                            continue;
                        }
                        if (modifierShortcutManagerMultiuser()) {
                            ComponentName componentName =
                                    new ComponentName(packageName, className);
                            if (isShiftShortcut) {
                                mShiftComponentShortcuts.put(shortcutChar, componentName);
                            } else {
                                mComponentShortcuts.put(shortcutChar, componentName);
                            }
                        } else {
                            Intent intent = resolveComponentNameIntent(packageName, className);
                            if (isShiftShortcut) {
                                mShiftCategoryShortcuts.put(shortcutChar, intent);
                            } else {
                                mCategoryShortcuts.put(shortcutChar, intent);
                            }
                        }
                        continue;
                    } else if (categoryName != null) {
                        if (roleName != null) {
                            Log.w(TAG, "Cannot specify role bookmark when category is present for"
                                    + " bookmark shortcutChar=" + shortcutChar
                                    + " category= " + categoryName);
                            continue;
                        }
                        Intent intent = Intent.makeMainSelectorActivity(
                                Intent.ACTION_MAIN, categoryName);
                        if (intent == null) {
                            Log.w(TAG, "Null selector intent for " + categoryName);
                        } else {
                            if (isShiftShortcut) {
                                mShiftCategoryShortcuts.put(shortcutChar, intent);
                            } else {
                                mCategoryShortcuts.put(shortcutChar, intent);
                            }
                        }
                        continue;
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
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Got exception parsing bookmarks.", e);
        }
    }

    @Nullable
    private Intent resolveComponentNameIntent(ComponentName componentName) {
        if (modifierShortcutManagerRefactor()) {
            return null;
        } else {
            Intent intent = mComponentIntents.get(componentName);
            if (intent == null) {
                intent = resolveComponentNameIntent(
                        componentName.getPackageName(), componentName.getClassName());
                if (intent != null) {
                    mComponentIntents.put(componentName, intent);
                }
            }
            return intent;
        }
    }

    @Nullable
    private Intent resolveComponentNameIntent(String packageName, String className) {
        if (modifierShortcutManagerRefactor()) {
            return null;
        } else {
            Context context = modifierShortcutManagerMultiuser()
                        ? mContext.createContextAsUser(mCurrentUser, 0) : mContext;
            return resolveComponentNameIntent(context, packageName, className);
        }
    }

    @Nullable
    private static Intent resolveComponentNameIntent(
            Context context, String packageName, String className) {
        PackageManager pm = context.getPackageManager();
        int flags = PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        if (!modifierShortcutManagerMultiuser()) {
            flags |= PackageManager.MATCH_DIRECT_BOOT_AWARE
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES;
        }
        ComponentName componentName = new ComponentName(packageName, className);
        try {
            pm.getActivityInfo(componentName, flags);
        } catch (PackageManager.NameNotFoundException e) {
            String[] packages = pm.canonicalToCurrentPackageNames(
                    new String[] { packageName });
            componentName = new ComponentName(packages[0], className);
            try {
                pm.getActivityInfo(componentName, flags);
            } catch (PackageManager.NameNotFoundException e1) {
                Log.w(TAG, "Unable to add bookmark: " + packageName
                        + "/" + className + " not found.");
                return null;
            }
        }
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(componentName);
        return intent;
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
                    if (modifierShortcutManagerMultiuser()) {
                        mContext.startActivityAsUser(intent, mCurrentUser);
                    } else {
                        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                    }
                } catch (ActivityNotFoundException ex) {
                    Slog.w(TAG, "Dropping application launch key because "
                            + "the activity to which it is registered was not found: "
                            + "keyCode=" + KeyEvent.keyCodeToString(keyCode) + ","
                            + " category=" + category + " role=" + role);
                }
                notifyKeyGestureCompleted(keyEvent, getKeyGestureTypeFromIntent(intent));
                return true;
            } else {
                return false;
            }
        }

        final Intent shortcutIntent = getIntent(kcm, keyCode, metaState);
        if (shortcutIntent != null) {
            shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                if (modifierShortcutManagerMultiuser()) {
                    mContext.startActivityAsUser(shortcutIntent, mCurrentUser);
                } else {
                    mContext.startActivityAsUser(shortcutIntent, UserHandle.CURRENT);
                }
            } catch (ActivityNotFoundException ex) {
                Slog.w(TAG, "Dropping shortcut key combination because "
                        + "the activity to which it is registered was not found: "
                        + "META+ or SEARCH" + KeyEvent.keyCodeToString(keyCode));
            }
            notifyKeyGestureCompleted(keyEvent, getKeyGestureTypeFromIntent(shortcutIntent));
            return true;
        }
        return false;
    }

    private void notifyKeyGestureCompleted(KeyEvent event,
            @KeyGestureEvent.KeyGestureType int gestureType) {
        if (gestureType == KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED) {
            return;
        }
        mInputManagerInternal.notifyKeyGestureCompleted(event.getDeviceId(),
                new int[]{event.getKeyCode()}, event.getMetaState(), gestureType);
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

    /**
     * @param deviceId The input device id of the input device that will handle the shortcuts.
     *
     * @return a {@link KeyboardShortcutGroup} containing the application launch keyboard
     *         shortcuts parsed at boot time from {@code bookmarks.xml}.
     */
    public KeyboardShortcutGroup getApplicationLaunchKeyboardShortcuts(int deviceId) {
        List<KeyboardShortcutInfo> shortcuts = new ArrayList();
        if (modifierShortcutManagerRefactor()) {
            for (Bookmark b : mBookmarks.values()) {
                KeyboardShortcutInfo info = shortcutInfoFromIntent(
                        b.getShortcutChar(), b.getIntent(mContext), b.isShift());
                if (info != null) {
                    shortcuts.add(info);
                }
            }
        } else {
            for (int i = 0; i <  mCategoryShortcuts.size(); i++) {
                KeyboardShortcutInfo info = shortcutInfoFromIntent(
                        (char) (mCategoryShortcuts.keyAt(i)),
                        mCategoryShortcuts.valueAt(i),
                        false);
                if (info != null) {
                    shortcuts.add(info);
                }
            }

            for (int i = 0; i <  mShiftCategoryShortcuts.size(); i++) {
                KeyboardShortcutInfo info = shortcutInfoFromIntent(
                        (char) (mShiftCategoryShortcuts.keyAt(i)),
                        mShiftCategoryShortcuts.valueAt(i),
                        true);
                if (info != null) {
                    shortcuts.add(info);
                }
            }

            for (int i = 0; i <  mRoleShortcuts.size(); i++) {
                String role = mRoleShortcuts.valueAt(i);
                KeyboardShortcutInfo info = shortcutInfoFromIntent(
                        (char) (mRoleShortcuts.keyAt(i)),
                        getRoleLaunchIntent(role),
                        false);
                if (info != null) {
                    shortcuts.add(info);
                }
            }

            for (int i = 0; i <  mShiftRoleShortcuts.size(); i++) {
                String role = mShiftRoleShortcuts.valueAt(i);
                KeyboardShortcutInfo info = shortcutInfoFromIntent(
                        (char) (mShiftRoleShortcuts.keyAt(i)),
                        getRoleLaunchIntent(role),
                        true);
                if (info != null) {
                    shortcuts.add(info);
                }
            }

            if (modifierShortcutManagerMultiuser()) {
                for (int i = 0; i < mComponentShortcuts.size(); i++) {
                    ComponentName component = mComponentShortcuts.valueAt(i);
                    KeyboardShortcutInfo info = shortcutInfoFromIntent(
                            (char) (mComponentShortcuts.keyAt(i)),
                            resolveComponentNameIntent(component),
                            false);
                    if (info != null) {
                        shortcuts.add(info);
                    }
                }

                for (int i = 0; i < mShiftComponentShortcuts.size(); i++) {
                    ComponentName component = mShiftComponentShortcuts.valueAt(i);
                    KeyboardShortcutInfo info = shortcutInfoFromIntent(
                            (char) (mShiftComponentShortcuts.keyAt(i)),
                            resolveComponentNameIntent(component),
                            true);
                    if (info != null) {
                        shortcuts.add(info);
                    }
                }
            }
        }
        return new KeyboardShortcutGroup(
                mContext.getString(R.string.keyboard_shortcut_group_applications),
                shortcuts);
    }

    /**
     * Given an intent to launch an application and the character and shift state that should
     * trigger it, return a suitable {@link KeyboardShortcutInfo} that contains the label and
     * icon for the target application.
     *
     * @param baseChar the character that triggers the shortcut
     * @param intent the application launch intent
     * @param shift whether the shift key is required to be presed.
     */
    @VisibleForTesting
    KeyboardShortcutInfo shortcutInfoFromIntent(char baseChar, Intent intent, boolean shift) {
        if (intent == null) {
            return null;
        }

        CharSequence label;
        Icon icon;
        Context context = modifierShortcutManagerMultiuser()
                ? mContext.createContextAsUser(mCurrentUser, 0) : mContext;
        PackageManager pm = context.getPackageManager();
        ActivityInfo resolvedActivity = intent.resolveActivityInfo(
                pm, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolvedActivity == null) {
            return null;
        }
        boolean isResolver = com.android.internal.app.ResolverActivity.class.getName().equals(
                resolvedActivity.name);
        if (isResolver) {
            label = getIntentCategoryLabel(context,
                    intent.getSelector().getCategories().iterator().next());
            if (label == null) {
                return null;
            }
            icon = Icon.createWithResource(context, R.drawable.sym_def_app_icon);

        } else {
            label = resolvedActivity.loadLabel(pm);
            icon = Icon.createWithResource(
                    resolvedActivity.packageName, resolvedActivity.getIconResource());
        }
        int modifiers = KeyEvent.META_META_ON;
        if (shift) {
            modifiers |= KeyEvent.META_SHIFT_ON;
        }
        return new KeyboardShortcutInfo(label, icon, baseChar, modifiers);
    }

    @VisibleForTesting
    static String getIntentCategoryLabel(Context context, CharSequence category) {
        int resid;
        switch (category.toString()) {
            case Intent.CATEGORY_APP_BROWSER:
                resid = R.string.keyboard_shortcut_group_applications_browser;
                break;
            case Intent.CATEGORY_APP_CONTACTS:
                resid = R.string.keyboard_shortcut_group_applications_contacts;
                break;
            case Intent.CATEGORY_APP_EMAIL:
                resid = R.string.keyboard_shortcut_group_applications_email;
                break;
            case Intent.CATEGORY_APP_CALENDAR:
                resid = R.string.keyboard_shortcut_group_applications_calendar;
                break;
            case Intent.CATEGORY_APP_MAPS:
                resid = R.string.keyboard_shortcut_group_applications_maps;
                break;
            case Intent.CATEGORY_APP_MUSIC:
                resid = R.string.keyboard_shortcut_group_applications_music;
                break;
            case Intent.CATEGORY_APP_MESSAGING:
                resid = R.string.keyboard_shortcut_group_applications_sms;
                break;
            case Intent.CATEGORY_APP_CALCULATOR:
                resid = R.string.keyboard_shortcut_group_applications_calculator;
                break;
            default:
                Log.e(TAG, ("No label for app category " + category));
                return null;
        }
        return context.getString(resid);
    };


    /**
     * Find Key gesture type corresponding to intent filter category. Returns
     * {@code KEY_GESTURE_TYPE_UNSPECIFIED if no matching event found}
     */
    @KeyGestureEvent.KeyGestureType
    private static int getKeyGestureTypeFromIntent(Intent intent) {
        Intent selectorIntent = intent.getSelector();
        if (selectorIntent != null) {
            Set<String> selectorCategories = selectorIntent.getCategories();
            if (selectorCategories != null && !selectorCategories.isEmpty()) {
                for (String intentCategory : selectorCategories) {
                    int keyGestureType = getKeyGestureTypeFromSelectorCategory(intentCategory);
                    if (keyGestureType == KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED) {
                        continue;
                    }
                    return keyGestureType;
                }
            }
        }

        // The shortcut may be targeting a system role rather than using an intent selector,
        // so check for that.
        String role = intent.getStringExtra(ModifierShortcutManager.EXTRA_ROLE);
        if (!TextUtils.isEmpty(role)) {
            return getKeyGestureTypeFromRole(role);
        }

        Set<String> intentCategories = intent.getCategories();
        if (intentCategories == null || intentCategories.isEmpty()
                || !intentCategories.contains(Intent.CATEGORY_LAUNCHER)) {
            return KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED;
        }
        if (intent.getComponent() == null) {
            return KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED;
        }

        // TODO(b/280423320): Add new field package name associated in the
        //  KeyboardShortcutEvent atom and log it accordingly.
        return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION_BY_PACKAGE_NAME;
    }

    @KeyGestureEvent.KeyGestureType
    private static int getKeyGestureTypeFromSelectorCategory(String category) {
        switch (category) {
            case Intent.CATEGORY_APP_BROWSER:
                return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER;
            case Intent.CATEGORY_APP_EMAIL:
                return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL;
            case Intent.CATEGORY_APP_CONTACTS:
                return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS;
            case Intent.CATEGORY_APP_CALENDAR:
                return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR;
            case Intent.CATEGORY_APP_CALCULATOR:
                return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR;
            case Intent.CATEGORY_APP_MUSIC:
                return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MUSIC;
            case Intent.CATEGORY_APP_MAPS:
                return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS;
            case Intent.CATEGORY_APP_MESSAGING:
                return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING;
            case Intent.CATEGORY_APP_GALLERY:
                return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_GALLERY;
            case Intent.CATEGORY_APP_FILES:
                return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FILES;
            case Intent.CATEGORY_APP_WEATHER:
                return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_WEATHER;
            case Intent.CATEGORY_APP_FITNESS:
                return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_FITNESS;
            default:
                return KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED;
        }
    }

    /**
     * Find KeyGestureType corresponding to the provide system role name.
     * Returns {@code KEY_GESTURE_TYPE_UNSPECIFIED} if no matching event found.
     */
    @KeyGestureEvent.KeyGestureType
    private static int getKeyGestureTypeFromRole(String role) {
        if (RoleManager.ROLE_BROWSER.equals(role)) {
            return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER;
        } else if (RoleManager.ROLE_SMS.equals(role)) {
            return KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING;
        } else {
            Log.w(TAG, "Keyboard gesture event to launch " + role + " not supported for logging");
            return KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED;
        }
    }

    void dump(String prefix, PrintWriter pw) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw,  "  ", prefix);
        ipw.println("ModifierShortcutManager shortcuts:");
        if (modifierShortcutManagerRefactor()) {
            ipw.increaseIndent();
            for (Bookmark b : mBookmarks.values()) {
                boolean isShift = b.isShift();
                char shortcutChar = b.getShortcutChar();
                Context context = modifierShortcutManagerMultiuser()
                        ? mContext.createContextAsUser(mCurrentUser, 0) : mContext;

                Intent intent = b.getIntent(context);
                ipw.print(isShift ? "SHIFT+" : "");
                ipw.println(shortcutChar + " " + intent);
                ipw.increaseIndent();
                ipw.increaseIndent();
                KeyboardShortcutInfo info = shortcutInfoFromIntent(shortcutChar, intent, isShift);
                if (info != null) {
                    ipw.println("Resolves to: " + info.getLabel());
                } else {
                    ipw.println("<No KeyboardShortcutInfo available for this shortcut>");
                }
                ipw.decreaseIndent();
                ipw.decreaseIndent();
            }
        } else {
            ipw.increaseIndent();
            ipw.println("Roles");
            ipw.increaseIndent();
            for (int i = 0; i < mRoleShortcuts.size(); i++) {
                String role = mRoleShortcuts.valueAt(i);
                char shortcutChar = (char) mRoleShortcuts.keyAt(i);
                Intent intent = getRoleLaunchIntent(role);
                ipw.println(shortcutChar + " " + role + " " + intent);
            }

            for (int i = 0; i < mShiftRoleShortcuts.size(); i++) {
                String role = mShiftRoleShortcuts.valueAt(i);
                char shortcutChar = (char) mShiftRoleShortcuts.keyAt(i);
                Intent intent = getRoleLaunchIntent(role);
                ipw.println("SHIFT+" + shortcutChar + " " + role + " " + intent);
            }

            ipw.decreaseIndent();
            ipw.println("Selectors");
            ipw.increaseIndent();
            for (int i = 0; i < mCategoryShortcuts.size(); i++) {
                char shortcutChar = (char) mCategoryShortcuts.keyAt(i);
                Intent intent = mCategoryShortcuts.valueAt(i);
                ipw.println(shortcutChar + " " + intent);
            }

            for (int i = 0; i < mShiftCategoryShortcuts.size(); i++) {
                char shortcutChar = (char) mShiftCategoryShortcuts.keyAt(i);
                Intent intent = mShiftCategoryShortcuts.valueAt(i);
                ipw.println("SHIFT+" + shortcutChar + " " + intent);

            }

            if (modifierShortcutManagerMultiuser()) {
                ipw.decreaseIndent();
                ipw.println("ComponentNames");
                ipw.increaseIndent();
                for (int i = 0; i < mComponentShortcuts.size(); i++) {
                    char shortcutChar = (char) mComponentShortcuts.keyAt(i);
                    ComponentName component = mComponentShortcuts.valueAt(i);
                    Intent intent = resolveComponentNameIntent(component);
                    ipw.println(shortcutChar + " " + component + " " + intent);
                }

                for (int i = 0; i < mShiftComponentShortcuts.size(); i++) {
                    char shortcutChar = (char) mShiftComponentShortcuts.keyAt(i);
                    ComponentName component = mShiftComponentShortcuts.valueAt(i);
                    Intent intent = resolveComponentNameIntent(component);
                    ipw.println("SHIFT+" + shortcutChar + " " + component + " " + intent);
                }
            }
        }
    }

    private abstract static  class Bookmark {
        private final char mShortcutChar;
        private final boolean mShift;
        protected Intent mIntent;

        Bookmark(char shortcutChar, boolean shift) {
            mShortcutChar = shortcutChar;
            mShift = shift;
        }

        public char getShortcutChar() {
            return mShortcutChar;
        }

        public boolean isShift() {
            return mShift;
        }

        public abstract Intent getIntent(Context context);

        public void clearIntent() {
            mIntent = null;
        }

    }

    private static final class RoleBookmark extends Bookmark {
        private final String mRole;

        RoleBookmark(char shortcutChar, boolean shift, String role) {
            super(shortcutChar, shift);
            mRole = role;
        }

        public String getRole() {
            return mRole;
        }

        @Nullable
        @Override
        public Intent getIntent(Context context) {
            if (mIntent != null) {
                return mIntent;
            }
            mIntent = getRoleLaunchIntent(context, mRole);
            return mIntent;
        }
    }

    private static final class CategoryBookmark extends Bookmark {
        private final String mCategory;

        CategoryBookmark(char shortcutChar, boolean shift, String category) {
            super(shortcutChar, shift);
            mCategory = category;
        }

        @NonNull
        @Override
        public Intent getIntent(Context context) {
            if (mIntent != null) {
                return mIntent;
            }

            mIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, mCategory);
            return mIntent;
        }
    }

    private static final class ComponentBookmark extends Bookmark {
        private final String mPackageName;
        private final String mClassName;

        ComponentBookmark(
                char shortcutChar, boolean shift, String packageName, String className) {
            super(shortcutChar, shift);
            mPackageName = packageName;
            mClassName = className;
        }

        @Nullable
        @Override
        public Intent getIntent(Context context) {
            if (mIntent != null) {
                return mIntent;
            }
            mIntent = resolveComponentNameIntent(context, mPackageName, mClassName);
            return mIntent;
        }
    }
}

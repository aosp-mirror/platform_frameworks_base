/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.hardware.input.InputDeviceIdentifier;
import android.hardware.input.InputManager;
import android.hardware.input.KeyboardLayout;
import android.icu.lang.UScript;
import android.icu.util.ULocale;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.util.Slog;
import android.view.InputDevice;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.InputMethodSubtypeHandle;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.XmlUtils;

import libcore.io.Streams;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A component of {@link InputManagerService} responsible for managing Physical Keyboard layouts.
 *
 * @hide
 */
final class KeyboardLayoutManager implements InputManager.InputDeviceListener {

    private static final String TAG = "KeyboardLayoutManager";

    // To enable these logs, run: 'adb shell setprop log.tag.KeyboardLayoutManager DEBUG'
    // (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int MSG_UPDATE_EXISTING_DEVICES = 1;
    private static final int MSG_SWITCH_KEYBOARD_LAYOUT = 2;
    private static final int MSG_RELOAD_KEYBOARD_LAYOUTS = 3;
    private static final int MSG_UPDATE_KEYBOARD_LAYOUTS = 4;

    private final Context mContext;
    private final NativeInputManagerService mNative;
    // The PersistentDataStore should be locked before use.
    @GuardedBy("mDataStore")
    private final PersistentDataStore mDataStore;
    private final Handler mHandler;

    private final List<InputDevice> mKeyboardsWithMissingLayouts = new ArrayList<>();
    private boolean mKeyboardLayoutNotificationShown = false;
    private Toast mSwitchedKeyboardLayoutToast;

    // This cache stores "best-matched" layouts so that we don't need to run the matching
    // algorithm repeatedly.
    @GuardedBy("mKeyboardLayoutCache")
    private final Map<String, String> mKeyboardLayoutCache = new ArrayMap<>();
    @Nullable
    private ImeInfo mCurrentImeInfo;

    KeyboardLayoutManager(Context context, NativeInputManagerService nativeService,
            PersistentDataStore dataStore, Looper looper) {
        mContext = context;
        mNative = nativeService;
        mDataStore = dataStore;
        mHandler = new Handler(looper, this::handleMessage, true /* async */);
    }

    public void systemRunning() {
        // Listen to new Package installations to fetch new Keyboard layouts
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateKeyboardLayouts();
            }
        }, filter, null, mHandler);

        mHandler.sendEmptyMessage(MSG_UPDATE_KEYBOARD_LAYOUTS);

        // Listen to new InputDevice changes
        InputManager inputManager = Objects.requireNonNull(
                mContext.getSystemService(InputManager.class));
        inputManager.registerInputDeviceListener(this, mHandler);

        Message msg = Message.obtain(mHandler, MSG_UPDATE_EXISTING_DEVICES,
                inputManager.getInputDeviceIds());
        mHandler.sendMessage(msg);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        onInputDeviceChanged(deviceId);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        if (!useNewSettingsUi()) {
            mKeyboardsWithMissingLayouts.removeIf(device -> device.getId() == deviceId);
            maybeUpdateNotification();
        }
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        final InputDevice inputDevice = getInputDevice(deviceId);
        if (inputDevice == null || inputDevice.isVirtual() || !inputDevice.isFullKeyboard()) {
            return;
        }
        if (!useNewSettingsUi()) {
            synchronized (mDataStore) {
                String layout = getCurrentKeyboardLayoutForInputDevice(inputDevice.getIdentifier());
                if (layout == null) {
                    layout = getDefaultKeyboardLayout(inputDevice);
                    if (layout != null) {
                        setCurrentKeyboardLayoutForInputDevice(inputDevice.getIdentifier(), layout);
                    } else {
                        mKeyboardsWithMissingLayouts.add(inputDevice);
                    }
                }
                maybeUpdateNotification();
            }
        }
        // TODO(b/259530132): Show notification for new Settings UI
    }

    private String getDefaultKeyboardLayout(final InputDevice inputDevice) {
        final Locale systemLocale = mContext.getResources().getConfiguration().locale;
        // If our locale doesn't have a language for some reason, then we don't really have a
        // reasonable default.
        if (TextUtils.isEmpty(systemLocale.getLanguage())) {
            return null;
        }
        final List<KeyboardLayout> layouts = new ArrayList<>();
        visitAllKeyboardLayouts((resources, keyboardLayoutResId, layout) -> {
            // Only select a default when we know the layout is appropriate. For now, this
            // means it's a custom layout for a specific keyboard.
            if (layout.getVendorId() != inputDevice.getVendorId()
                    || layout.getProductId() != inputDevice.getProductId()) {
                return;
            }
            final LocaleList locales = layout.getLocales();
            for (int localeIndex = 0; localeIndex < locales.size(); ++localeIndex) {
                final Locale locale = locales.get(localeIndex);
                if (locale != null && isCompatibleLocale(systemLocale, locale)) {
                    layouts.add(layout);
                    break;
                }
            }
        });

        if (layouts.isEmpty()) {
            return null;
        }

        // First sort so that ones with higher priority are listed at the top
        Collections.sort(layouts);
        // Next we want to try to find an exact match of language, country and variant.
        for (KeyboardLayout layout : layouts) {
            final LocaleList locales = layout.getLocales();
            for (int localeIndex = 0; localeIndex < locales.size(); ++localeIndex) {
                final Locale locale = locales.get(localeIndex);
                if (locale != null && locale.getCountry().equals(systemLocale.getCountry())
                        && locale.getVariant().equals(systemLocale.getVariant())) {
                    return layout.getDescriptor();
                }
            }
        }
        // Then try an exact match of language and country
        for (KeyboardLayout layout : layouts) {
            final LocaleList locales = layout.getLocales();
            for (int localeIndex = 0; localeIndex < locales.size(); ++localeIndex) {
                final Locale locale = locales.get(localeIndex);
                if (locale != null && locale.getCountry().equals(systemLocale.getCountry())) {
                    return layout.getDescriptor();
                }
            }
        }

        // Give up and just use the highest priority layout with matching language
        return layouts.get(0).getDescriptor();
    }

    private static boolean isCompatibleLocale(Locale systemLocale, Locale keyboardLocale) {
        // Different languages are never compatible
        if (!systemLocale.getLanguage().equals(keyboardLocale.getLanguage())) {
            return false;
        }
        // If both the system and the keyboard layout have a country specifier, they must be equal.
        return TextUtils.isEmpty(systemLocale.getCountry())
                || TextUtils.isEmpty(keyboardLocale.getCountry())
                || systemLocale.getCountry().equals(keyboardLocale.getCountry());
    }

    private void updateKeyboardLayouts() {
        // Scan all input devices state for keyboard layouts that have been uninstalled.
        final HashSet<String> availableKeyboardLayouts = new HashSet<String>();
        visitAllKeyboardLayouts((resources, keyboardLayoutResId, layout) ->
                availableKeyboardLayouts.add(layout.getDescriptor()));
        synchronized (mDataStore) {
            try {
                mDataStore.removeUninstalledKeyboardLayouts(availableKeyboardLayouts);
            } finally {
                mDataStore.saveIfNeeded();
            }
        }

        synchronized (mKeyboardLayoutCache) {
            // Invalidate the cache: With packages being installed/removed, existing cache of
            // auto-selected layout might not be the best layouts anymore.
            mKeyboardLayoutCache.clear();
        }

        // Reload keyboard layouts.
        reloadKeyboardLayouts();
    }

    public KeyboardLayout[] getKeyboardLayouts() {
        final ArrayList<KeyboardLayout> list = new ArrayList<>();
        visitAllKeyboardLayouts((resources, keyboardLayoutResId, layout) -> list.add(layout));
        return list.toArray(new KeyboardLayout[0]);
    }

    public KeyboardLayout[] getKeyboardLayoutsForInputDevice(
            final InputDeviceIdentifier identifier) {
        if (useNewSettingsUi()) {
            return new KeyboardLayout[0];
        }
        final String[] enabledLayoutDescriptors =
                getEnabledKeyboardLayoutsForInputDevice(identifier);
        final ArrayList<KeyboardLayout> enabledLayouts =
                new ArrayList<>(enabledLayoutDescriptors.length);
        final ArrayList<KeyboardLayout> potentialLayouts = new ArrayList<>();
        visitAllKeyboardLayouts(new KeyboardLayoutVisitor() {
            boolean mHasSeenDeviceSpecificLayout;

            @Override
            public void visitKeyboardLayout(Resources resources,
                    int keyboardLayoutResId, KeyboardLayout layout) {
                // First check if it's enabled. If the keyboard layout is enabled then we always
                // want to return it as a possible layout for the device.
                for (String s : enabledLayoutDescriptors) {
                    if (s != null && s.equals(layout.getDescriptor())) {
                        enabledLayouts.add(layout);
                        return;
                    }
                }
                // Next find any potential layouts that aren't yet enabled for the device. For
                // devices that have special layouts we assume there's a reason that the generic
                // layouts don't work for them so we don't want to return them since it's likely
                // to result in a poor user experience.
                if (layout.getVendorId() == identifier.getVendorId()
                        && layout.getProductId() == identifier.getProductId()) {
                    if (!mHasSeenDeviceSpecificLayout) {
                        mHasSeenDeviceSpecificLayout = true;
                        potentialLayouts.clear();
                    }
                    potentialLayouts.add(layout);
                } else if (layout.getVendorId() == -1 && layout.getProductId() == -1
                        && !mHasSeenDeviceSpecificLayout) {
                    potentialLayouts.add(layout);
                }
            }
        });
        return Stream.concat(enabledLayouts.stream(), potentialLayouts.stream()).toArray(
                KeyboardLayout[]::new);
    }

    @Nullable
    public KeyboardLayout getKeyboardLayout(String keyboardLayoutDescriptor) {
        Objects.requireNonNull(keyboardLayoutDescriptor,
                "keyboardLayoutDescriptor must not be null");

        final KeyboardLayout[] result = new KeyboardLayout[1];
        visitKeyboardLayout(keyboardLayoutDescriptor,
                (resources, keyboardLayoutResId, layout) -> result[0] = layout);
        if (result[0] == null) {
            Slog.w(TAG, "Could not get keyboard layout with descriptor '"
                    + keyboardLayoutDescriptor + "'.");
        }
        return result[0];
    }

    private void visitAllKeyboardLayouts(KeyboardLayoutVisitor visitor) {
        final PackageManager pm = mContext.getPackageManager();
        Intent intent = new Intent(InputManager.ACTION_QUERY_KEYBOARD_LAYOUTS);
        for (ResolveInfo resolveInfo : pm.queryBroadcastReceivers(intent,
                PackageManager.GET_META_DATA | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE)) {
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            final int priority = resolveInfo.priority;
            visitKeyboardLayoutsInPackage(pm, activityInfo, null, priority, visitor);
        }
    }

    private void visitKeyboardLayout(String keyboardLayoutDescriptor,
            KeyboardLayoutVisitor visitor) {
        KeyboardLayoutDescriptor d = KeyboardLayoutDescriptor.parse(keyboardLayoutDescriptor);
        if (d != null) {
            final PackageManager pm = mContext.getPackageManager();
            try {
                ActivityInfo receiver = pm.getReceiverInfo(
                        new ComponentName(d.packageName, d.receiverName),
                        PackageManager.GET_META_DATA
                                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
                visitKeyboardLayoutsInPackage(pm, receiver, d.keyboardLayoutName, 0, visitor);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
    }

    private void visitKeyboardLayoutsInPackage(PackageManager pm, ActivityInfo receiver,
            String keyboardName, int requestedPriority, KeyboardLayoutVisitor visitor) {
        Bundle metaData = receiver.metaData;
        if (metaData == null) {
            return;
        }

        int configResId = metaData.getInt(InputManager.META_DATA_KEYBOARD_LAYOUTS);
        if (configResId == 0) {
            Slog.w(TAG, "Missing meta-data '" + InputManager.META_DATA_KEYBOARD_LAYOUTS
                    + "' on receiver " + receiver.packageName + "/" + receiver.name);
            return;
        }

        CharSequence receiverLabel = receiver.loadLabel(pm);
        String collection = receiverLabel != null ? receiverLabel.toString() : "";
        int priority;
        if ((receiver.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            priority = requestedPriority;
        } else {
            priority = 0;
        }

        try {
            Resources resources = pm.getResourcesForApplication(receiver.applicationInfo);
            try (XmlResourceParser parser = resources.getXml(configResId)) {
                XmlUtils.beginDocument(parser, "keyboard-layouts");

                while (true) {
                    XmlUtils.nextElement(parser);
                    String element = parser.getName();
                    if (element == null) {
                        break;
                    }
                    if (element.equals("keyboard-layout")) {
                        TypedArray a = resources.obtainAttributes(
                                parser, R.styleable.KeyboardLayout);
                        try {
                            String name = a.getString(
                                    R.styleable.KeyboardLayout_name);
                            String label = a.getString(
                                    R.styleable.KeyboardLayout_label);
                            int keyboardLayoutResId = a.getResourceId(
                                    R.styleable.KeyboardLayout_keyboardLayout,
                                    0);
                            String languageTags = a.getString(
                                    R.styleable.KeyboardLayout_keyboardLocale);
                            LocaleList locales = getLocalesFromLanguageTags(languageTags);
                            int layoutType = a.getInt(R.styleable.KeyboardLayout_keyboardLayoutType,
                                    0);
                            int vid = a.getInt(
                                    R.styleable.KeyboardLayout_vendorId, -1);
                            int pid = a.getInt(
                                    R.styleable.KeyboardLayout_productId, -1);

                            if (name == null || label == null || keyboardLayoutResId == 0) {
                                Slog.w(TAG, "Missing required 'name', 'label' or 'keyboardLayout' "
                                        + "attributes in keyboard layout "
                                        + "resource from receiver "
                                        + receiver.packageName + "/" + receiver.name);
                            } else {
                                String descriptor = KeyboardLayoutDescriptor.format(
                                        receiver.packageName, receiver.name, name);
                                if (keyboardName == null || name.equals(keyboardName)) {
                                    KeyboardLayout layout = new KeyboardLayout(
                                            descriptor, label, collection, priority,
                                            locales, layoutType, vid, pid);
                                    visitor.visitKeyboardLayout(
                                            resources, keyboardLayoutResId, layout);
                                }
                            }
                        } finally {
                            a.recycle();
                        }
                    } else {
                        Slog.w(TAG, "Skipping unrecognized element '" + element
                                + "' in keyboard layout resource from receiver "
                                + receiver.packageName + "/" + receiver.name);
                    }
                }
            }
        } catch (Exception ex) {
            Slog.w(TAG, "Could not parse keyboard layout resource from receiver "
                    + receiver.packageName + "/" + receiver.name, ex);
        }
    }

    @NonNull
    private static LocaleList getLocalesFromLanguageTags(String languageTags) {
        if (TextUtils.isEmpty(languageTags)) {
            return LocaleList.getEmptyLocaleList();
        }
        return LocaleList.forLanguageTags(languageTags.replace('|', ','));
    }

    private static String getLayoutDescriptor(@NonNull InputDeviceIdentifier identifier) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        Objects.requireNonNull(identifier.getDescriptor(), "descriptor must not be null");

        if (identifier.getVendorId() == 0 && identifier.getProductId() == 0) {
            return identifier.getDescriptor();
        }
        // If vendor id and product id is available, use it as keys. This allows us to have the
        // same setup for all keyboards with same product and vendor id. i.e. User can swap 2
        // identical keyboards and still get the same setup.
        return "vendor:" + identifier.getVendorId() + ",product:" + identifier.getProductId();
    }

    @Nullable
    public String getCurrentKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier) {
        if (useNewSettingsUi()) {
            Slog.e(TAG, "getCurrentKeyboardLayoutForInputDevice API not supported");
            return null;
        }
        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            String layout;
            // try loading it using the layout descriptor if we have it
            layout = mDataStore.getCurrentKeyboardLayout(key);
            if (layout == null && !key.equals(identifier.getDescriptor())) {
                // if it doesn't exist fall back to the device descriptor
                layout = mDataStore.getCurrentKeyboardLayout(identifier.getDescriptor());
            }
            if (DEBUG) {
                Slog.d(TAG, "getCurrentKeyboardLayoutForInputDevice() "
                        + identifier.toString() + ": " + layout);
            }
            return layout;
        }
    }

    public void setCurrentKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        if (useNewSettingsUi()) {
            Slog.e(TAG, "setCurrentKeyboardLayoutForInputDevice API not supported");
            return;
        }

        Objects.requireNonNull(keyboardLayoutDescriptor,
                "keyboardLayoutDescriptor must not be null");
        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            try {
                if (mDataStore.setCurrentKeyboardLayout(key, keyboardLayoutDescriptor)) {
                    if (DEBUG) {
                        Slog.d(TAG, "setCurrentKeyboardLayoutForInputDevice() " + identifier
                                + " key: " + key
                                + " keyboardLayoutDescriptor: " + keyboardLayoutDescriptor);
                    }
                    mHandler.sendEmptyMessage(MSG_RELOAD_KEYBOARD_LAYOUTS);
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    public String[] getEnabledKeyboardLayoutsForInputDevice(InputDeviceIdentifier identifier) {
        if (useNewSettingsUi()) {
            Slog.e(TAG, "getEnabledKeyboardLayoutsForInputDevice API not supported");
            return new String[0];
        }
        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            String[] layouts = mDataStore.getKeyboardLayouts(key);
            if ((layouts == null || layouts.length == 0)
                    && !key.equals(identifier.getDescriptor())) {
                layouts = mDataStore.getKeyboardLayouts(identifier.getDescriptor());
            }
            return layouts;
        }
    }

    public void addKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        if (useNewSettingsUi()) {
            Slog.e(TAG, "addKeyboardLayoutForInputDevice API not supported");
            return;
        }
        Objects.requireNonNull(keyboardLayoutDescriptor,
                "keyboardLayoutDescriptor must not be null");

        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            try {
                String oldLayout = mDataStore.getCurrentKeyboardLayout(key);
                if (oldLayout == null && !key.equals(identifier.getDescriptor())) {
                    oldLayout = mDataStore.getCurrentKeyboardLayout(identifier.getDescriptor());
                }
                if (mDataStore.addKeyboardLayout(key, keyboardLayoutDescriptor)
                        && !Objects.equals(oldLayout,
                        mDataStore.getCurrentKeyboardLayout(key))) {
                    mHandler.sendEmptyMessage(MSG_RELOAD_KEYBOARD_LAYOUTS);
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    public void removeKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        if (useNewSettingsUi()) {
            Slog.e(TAG, "removeKeyboardLayoutForInputDevice API not supported");
            return;
        }
        Objects.requireNonNull(keyboardLayoutDescriptor,
                "keyboardLayoutDescriptor must not be null");

        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            try {
                String oldLayout = mDataStore.getCurrentKeyboardLayout(key);
                if (oldLayout == null && !key.equals(identifier.getDescriptor())) {
                    oldLayout = mDataStore.getCurrentKeyboardLayout(identifier.getDescriptor());
                }
                boolean removed = mDataStore.removeKeyboardLayout(key, keyboardLayoutDescriptor);
                if (!key.equals(identifier.getDescriptor())) {
                    // We need to remove from both places to ensure it is gone
                    removed |= mDataStore.removeKeyboardLayout(identifier.getDescriptor(),
                            keyboardLayoutDescriptor);
                }
                if (removed && !Objects.equals(oldLayout,
                        mDataStore.getCurrentKeyboardLayout(key))) {
                    mHandler.sendEmptyMessage(MSG_RELOAD_KEYBOARD_LAYOUTS);
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    public void switchKeyboardLayout(int deviceId, int direction) {
        if (useNewSettingsUi()) {
            Slog.e(TAG, "switchKeyboardLayout API not supported");
            return;
        }
        mHandler.obtainMessage(MSG_SWITCH_KEYBOARD_LAYOUT, deviceId, direction).sendToTarget();
    }

    // Must be called on handler.
    private void handleSwitchKeyboardLayout(int deviceId, int direction) {
        final InputDevice device = getInputDevice(deviceId);
        if (device != null) {
            final boolean changed;
            final String keyboardLayoutDescriptor;

            String key = getLayoutDescriptor(device.getIdentifier());
            synchronized (mDataStore) {
                try {
                    changed = mDataStore.switchKeyboardLayout(key, direction);
                    keyboardLayoutDescriptor = mDataStore.getCurrentKeyboardLayout(
                            key);
                } finally {
                    mDataStore.saveIfNeeded();
                }
            }

            if (changed) {
                if (mSwitchedKeyboardLayoutToast != null) {
                    mSwitchedKeyboardLayoutToast.cancel();
                    mSwitchedKeyboardLayoutToast = null;
                }
                if (keyboardLayoutDescriptor != null) {
                    KeyboardLayout keyboardLayout = getKeyboardLayout(keyboardLayoutDescriptor);
                    if (keyboardLayout != null) {
                        mSwitchedKeyboardLayoutToast = Toast.makeText(
                                mContext, keyboardLayout.getLabel(), Toast.LENGTH_SHORT);
                        mSwitchedKeyboardLayoutToast.show();
                    }
                }

                reloadKeyboardLayouts();
            }
        }
    }

    @Nullable
    public String[] getKeyboardLayoutOverlay(InputDeviceIdentifier identifier) {
        String keyboardLayoutDescriptor;
        if (useNewSettingsUi()) {
            if (mCurrentImeInfo == null) {
                // Haven't received onInputMethodSubtypeChanged() callback from IMMS. Will reload
                // keyboard layouts once we receive the callback.
                return null;
            }

            keyboardLayoutDescriptor = getKeyboardLayoutForInputDeviceInternal(identifier,
                    mCurrentImeInfo);
        } else {
            keyboardLayoutDescriptor = getCurrentKeyboardLayoutForInputDevice(identifier);
        }
        if (keyboardLayoutDescriptor == null) {
            return null;
        }

        final String[] result = new String[2];
        visitKeyboardLayout(keyboardLayoutDescriptor,
                (resources, keyboardLayoutResId, layout) -> {
                    try (InputStreamReader stream = new InputStreamReader(
                            resources.openRawResource(keyboardLayoutResId))) {
                        result[0] = layout.getDescriptor();
                        result[1] = Streams.readFully(stream);
                    } catch (IOException | Resources.NotFoundException ignored) {
                    }
                });
        if (result[0] == null) {
            Slog.w(TAG, "Could not get keyboard layout with descriptor '"
                    + keyboardLayoutDescriptor + "'.");
            return null;
        }
        return result;
    }

    @Nullable
    public String getKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            @UserIdInt int userId, @NonNull InputMethodInfo imeInfo,
            @Nullable InputMethodSubtype imeSubtype) {
        if (!useNewSettingsUi()) {
            Slog.e(TAG, "getKeyboardLayoutForInputDevice() API not supported");
            return null;
        }
        InputMethodSubtypeHandle subtypeHandle = InputMethodSubtypeHandle.of(imeInfo, imeSubtype);
        String layout = getKeyboardLayoutForInputDeviceInternal(identifier,
                new ImeInfo(userId, subtypeHandle, imeSubtype));
        if (DEBUG) {
            Slog.d(TAG, "getKeyboardLayoutForInputDevice() " + identifier.toString() + ", userId : "
                    + userId + ", subtypeHandle = " + subtypeHandle + " -> " + layout);
        }
        return layout;
    }

    public void setKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            @UserIdInt int userId, @NonNull InputMethodInfo imeInfo,
            @Nullable InputMethodSubtype imeSubtype,
            String keyboardLayoutDescriptor) {
        if (!useNewSettingsUi()) {
            Slog.e(TAG, "setKeyboardLayoutForInputDevice() API not supported");
            return;
        }
        Objects.requireNonNull(keyboardLayoutDescriptor,
                "keyboardLayoutDescriptor must not be null");
        String key = createLayoutKey(identifier, userId,
                InputMethodSubtypeHandle.of(imeInfo, imeSubtype));
        synchronized (mDataStore) {
            try {
                // Key for storing into data store = <device descriptor>,<userId>,<subtypeHandle>
                if (mDataStore.setKeyboardLayout(getLayoutDescriptor(identifier), key,
                        keyboardLayoutDescriptor)) {
                    if (DEBUG) {
                        Slog.d(TAG, "setKeyboardLayoutForInputDevice() " + identifier
                                + " key: " + key
                                + " keyboardLayoutDescriptor: " + keyboardLayoutDescriptor);
                    }
                    mHandler.sendEmptyMessage(MSG_RELOAD_KEYBOARD_LAYOUTS);
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    public KeyboardLayout[] getKeyboardLayoutListForInputDevice(InputDeviceIdentifier identifier,
            @UserIdInt int userId, @NonNull InputMethodInfo imeInfo,
            @Nullable InputMethodSubtype imeSubtype) {
        if (!useNewSettingsUi()) {
            Slog.e(TAG, "getKeyboardLayoutListForInputDevice() API not supported");
            return new KeyboardLayout[0];
        }
        return getKeyboardLayoutListForInputDeviceInternal(identifier, new ImeInfo(userId,
                InputMethodSubtypeHandle.of(imeInfo, imeSubtype), imeSubtype));
    }

    private KeyboardLayout[] getKeyboardLayoutListForInputDeviceInternal(
            InputDeviceIdentifier identifier, ImeInfo imeInfo) {
        String key = createLayoutKey(identifier, imeInfo.mUserId, imeInfo.mImeSubtypeHandle);

        // Fetch user selected layout and always include it in layout list.
        String userSelectedLayout;
        synchronized (mDataStore) {
            userSelectedLayout = mDataStore.getKeyboardLayout(getLayoutDescriptor(identifier), key);
        }

        final ArrayList<KeyboardLayout> potentialLayouts = new ArrayList<>();
        String imeLanguageTag;
        if (imeInfo.mImeSubtype == null) {
            imeLanguageTag = "";
        } else {
            ULocale imeLocale = imeInfo.mImeSubtype.getPhysicalKeyboardHintLanguageTag();
            imeLanguageTag = imeLocale != null ? imeLocale.toLanguageTag()
                    : imeInfo.mImeSubtype.getCanonicalizedLanguageTag();
        }

        visitAllKeyboardLayouts(new KeyboardLayoutVisitor() {
            boolean mDeviceSpecificLayoutAvailable;

            @Override
            public void visitKeyboardLayout(Resources resources,
                    int keyboardLayoutResId, KeyboardLayout layout) {
                // Next find any potential layouts that aren't yet enabled for the device. For
                // devices that have special layouts we assume there's a reason that the generic
                // layouts don't work for them, so we don't want to return them since it's likely
                // to result in a poor user experience.
                if (layout.getVendorId() == identifier.getVendorId()
                        && layout.getProductId() == identifier.getProductId()) {
                    if (!mDeviceSpecificLayoutAvailable) {
                        mDeviceSpecificLayoutAvailable = true;
                        potentialLayouts.clear();
                    }
                    potentialLayouts.add(layout);
                } else if (layout.getVendorId() == -1 && layout.getProductId() == -1
                        && !mDeviceSpecificLayoutAvailable && isLayoutCompatibleWithLanguageTag(
                        layout, imeLanguageTag)) {
                    potentialLayouts.add(layout);
                } else if (layout.getDescriptor().equals(userSelectedLayout)) {
                    potentialLayouts.add(layout);
                }
            }
        });
        // Sort the Keyboard layouts. This is done first by priority then by label. So, system
        // layouts will come above 3rd party layouts.
        Collections.sort(potentialLayouts);
        return potentialLayouts.toArray(new KeyboardLayout[0]);
    }

    public void onInputMethodSubtypeChanged(@UserIdInt int userId,
            @Nullable InputMethodSubtypeHandle subtypeHandle,
            @Nullable InputMethodSubtype subtype) {
        if (!useNewSettingsUi()) {
            Slog.e(TAG, "onInputMethodSubtypeChanged() API not supported");
            return;
        }
        if (subtypeHandle == null) {
            if (DEBUG) {
                Slog.d(TAG, "No InputMethod is running, ignoring change");
            }
            return;
        }
        if (mCurrentImeInfo == null || !subtypeHandle.equals(mCurrentImeInfo.mImeSubtypeHandle)
                || mCurrentImeInfo.mUserId != userId) {
            mCurrentImeInfo = new ImeInfo(userId, subtypeHandle, subtype);
            mHandler.sendEmptyMessage(MSG_RELOAD_KEYBOARD_LAYOUTS);
            if (DEBUG) {
                Slog.d(TAG, "InputMethodSubtype changed: userId=" + userId
                        + " subtypeHandle=" + subtypeHandle);
            }
        }
    }

    @Nullable
    private String getKeyboardLayoutForInputDeviceInternal(InputDeviceIdentifier identifier,
            ImeInfo imeInfo) {
        InputDevice inputDevice = getInputDevice(identifier);
        if (inputDevice == null || inputDevice.isVirtual() || !inputDevice.isFullKeyboard()) {
            return null;
        }
        String key = createLayoutKey(identifier, imeInfo.mUserId, imeInfo.mImeSubtypeHandle);
        String layout;
        synchronized (mDataStore) {
            layout = mDataStore.getKeyboardLayout(getLayoutDescriptor(identifier), key);
        }
        if (layout == null) {
            synchronized (mKeyboardLayoutCache) {
                // Check Auto-selected layout cache to see if layout had been previously selected
                if (mKeyboardLayoutCache.containsKey(key)) {
                    layout = mKeyboardLayoutCache.get(key);
                } else {
                    // NOTE: This list is already filtered based on IME Script code
                    KeyboardLayout[] layoutList = getKeyboardLayoutListForInputDeviceInternal(
                            identifier, imeInfo);
                    // Call auto-matching algorithm to find the best matching layout
                    layout = getDefaultKeyboardLayoutBasedOnImeInfo(inputDevice, imeInfo,
                            layoutList);
                    mKeyboardLayoutCache.put(key, layout);
                }
            }
        }
        return layout;
    }

    @Nullable
    private static String getDefaultKeyboardLayoutBasedOnImeInfo(InputDevice inputDevice,
            ImeInfo imeInfo, KeyboardLayout[] layoutList) {
        if (imeInfo.mImeSubtypeHandle == null) {
            return null;
        }

        Arrays.sort(layoutList);

        // Check <VendorID, ProductID> matching for explicitly declared custom KCM files.
        for (KeyboardLayout layout : layoutList) {
            if (layout.getVendorId() == inputDevice.getVendorId()
                    && layout.getProductId() == inputDevice.getProductId()) {
                if (DEBUG) {
                    Slog.d(TAG,
                            "getDefaultKeyboardLayoutBasedOnImeInfo() : Layout found based on "
                                    + "vendor and product Ids. " + inputDevice.getIdentifier()
                                    + " : " + layout.getDescriptor());
                }
                return layout.getDescriptor();
            }
        }

        // Check layout type, language tag information from InputDevice for matching
        String inputLanguageTag = inputDevice.getKeyboardLanguageTag();
        if (inputLanguageTag != null) {
            String layoutDesc = getMatchingLayoutForProvidedLanguageTagAndLayoutType(layoutList,
                    inputLanguageTag, inputDevice.getKeyboardLayoutType());

            if (layoutDesc != null) {
                if (DEBUG) {
                    Slog.d(TAG,
                            "getDefaultKeyboardLayoutBasedOnImeInfo() : Layout found based on "
                                    + "HW information (Language tag and Layout type). "
                                    + inputDevice.getIdentifier() + " : " + layoutDesc);
                }
                return layoutDesc;
            }
        }

        InputMethodSubtype subtype = imeInfo.mImeSubtype;
        // Can't auto select layout based on IME if subtype or language tag is null
        if (subtype == null) {
            return null;
        }

        // Check layout type, language tag information from IME for matching
        ULocale pkLocale = subtype.getPhysicalKeyboardHintLanguageTag();
        String pkLanguageTag =
                pkLocale != null ? pkLocale.toLanguageTag() : subtype.getCanonicalizedLanguageTag();
        String layoutDesc = getMatchingLayoutForProvidedLanguageTagAndLayoutType(layoutList,
                pkLanguageTag, subtype.getPhysicalKeyboardHintLayoutType());
        if (DEBUG) {
            Slog.d(TAG,
                    "getDefaultKeyboardLayoutBasedOnImeInfo() : Layout found based on "
                            + "IME locale matching. " + inputDevice.getIdentifier() + " : "
                            + layoutDesc);
        }
        return layoutDesc;
    }

    @Nullable
    private static String getMatchingLayoutForProvidedLanguageTagAndLayoutType(
            KeyboardLayout[] layoutList, @NonNull String languageTag, @Nullable String layoutType) {
        if (layoutType == null || !KeyboardLayout.isLayoutTypeValid(layoutType)) {
            layoutType = KeyboardLayout.LAYOUT_TYPE_UNDEFINED;
        }
        List<KeyboardLayout> layoutsFilteredByLayoutType = new ArrayList<>();
        for (KeyboardLayout layout : layoutList) {
            if (layout.getLayoutType().equals(layoutType)) {
                layoutsFilteredByLayoutType.add(layout);
            }
        }
        String layoutDesc = getMatchingLayoutForProvidedLanguageTag(layoutsFilteredByLayoutType,
                languageTag);
        if (layoutDesc != null) {
            return layoutDesc;
        }

        return getMatchingLayoutForProvidedLanguageTag(Arrays.asList(layoutList), languageTag);
    }

    @Nullable
    private static String getMatchingLayoutForProvidedLanguageTag(List<KeyboardLayout> layoutList,
            @NonNull String languageTag) {
        Locale locale = Locale.forLanguageTag(languageTag);
        String layoutMatchingLanguage = null;
        String layoutMatchingLanguageAndCountry = null;

        for (KeyboardLayout layout : layoutList) {
            final LocaleList locales = layout.getLocales();
            for (int i = 0; i < locales.size(); i++) {
                final Locale l = locales.get(i);
                if (l == null) {
                    continue;
                }
                if (l.getLanguage().equals(locale.getLanguage())) {
                    if (layoutMatchingLanguage == null) {
                        layoutMatchingLanguage = layout.getDescriptor();
                    }
                    if (l.getCountry().equals(locale.getCountry())) {
                        if (layoutMatchingLanguageAndCountry == null) {
                            layoutMatchingLanguageAndCountry = layout.getDescriptor();
                        }
                        if (l.getVariant().equals(locale.getVariant())) {
                            return layout.getDescriptor();
                        }
                    }
                }
            }
        }
        return layoutMatchingLanguageAndCountry != null
                    ? layoutMatchingLanguageAndCountry : layoutMatchingLanguage;
    }

    private void reloadKeyboardLayouts() {
        if (DEBUG) {
            Slog.d(TAG, "Reloading keyboard layouts.");
        }
        mNative.reloadKeyboardLayouts();
    }

    private void maybeUpdateNotification() {
        NotificationManager notificationManager = mContext.getSystemService(
                NotificationManager.class);
        if (notificationManager == null) {
            return;
        }
        if (!mKeyboardsWithMissingLayouts.isEmpty()) {
            if (mKeyboardsWithMissingLayouts.size() > 1) {
                // We have more than one keyboard missing a layout, so drop the
                // user at the generic input methods page, so they can pick which
                // one to set.
                showMissingKeyboardLayoutNotification(notificationManager, null);
            } else {
                showMissingKeyboardLayoutNotification(notificationManager,
                        mKeyboardsWithMissingLayouts.get(0));
            }
        } else if (mKeyboardLayoutNotificationShown) {
            hideMissingKeyboardLayoutNotification(notificationManager);
        }
    }

    // Must be called on handler.
    private void showMissingKeyboardLayoutNotification(NotificationManager notificationManager,
            InputDevice device) {
        if (!mKeyboardLayoutNotificationShown) {
            final Intent intent = new Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS);
            if (device != null) {
                intent.putExtra(Settings.EXTRA_INPUT_DEVICE_IDENTIFIER, device.getIdentifier());
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            final PendingIntent keyboardLayoutIntent = PendingIntent.getActivityAsUser(mContext, 0,
                    intent, PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);

            Resources r = mContext.getResources();
            Notification notification =
                    new Notification.Builder(mContext, SystemNotificationChannels.PHYSICAL_KEYBOARD)
                            .setContentTitle(r.getString(
                                    R.string.select_keyboard_layout_notification_title))
                            .setContentText(r.getString(
                                    R.string.select_keyboard_layout_notification_message))
                            .setContentIntent(keyboardLayoutIntent)
                            .setSmallIcon(R.drawable.ic_settings_language)
                            .setColor(mContext.getColor(
                                    com.android.internal.R.color.system_notification_accent_color))
                            .build();
            notificationManager.notifyAsUser(null,
                    SystemMessageProto.SystemMessage.NOTE_SELECT_KEYBOARD_LAYOUT,
                    notification, UserHandle.ALL);
            mKeyboardLayoutNotificationShown = true;
        }
    }

    // Must be called on handler.
    private void hideMissingKeyboardLayoutNotification(NotificationManager notificationManager) {
        if (mKeyboardLayoutNotificationShown) {
            mKeyboardLayoutNotificationShown = false;
            notificationManager.cancelAsUser(null,
                    SystemMessageProto.SystemMessage.NOTE_SELECT_KEYBOARD_LAYOUT,
                    UserHandle.ALL);
        }
    }

    private boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_UPDATE_EXISTING_DEVICES:
                // Circle through all the already added input devices
                // Need to do it on handler thread and not block IMS thread
                for (int deviceId : (int[]) msg.obj) {
                    onInputDeviceAdded(deviceId);
                }
                return true;
            case MSG_SWITCH_KEYBOARD_LAYOUT:
                handleSwitchKeyboardLayout(msg.arg1, msg.arg2);
                return true;
            case MSG_RELOAD_KEYBOARD_LAYOUTS:
                reloadKeyboardLayouts();
                return true;
            case MSG_UPDATE_KEYBOARD_LAYOUTS:
                updateKeyboardLayouts();
                return true;
            default:
                return false;
        }
    }

    private boolean useNewSettingsUi() {
        return FeatureFlagUtils.isEnabled(mContext, FeatureFlagUtils.SETTINGS_NEW_KEYBOARD_UI);
    }

    @Nullable
    private InputDevice getInputDevice(int deviceId) {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        return inputManager != null ? inputManager.getInputDevice(deviceId) : null;
    }

    @Nullable
    private InputDevice getInputDevice(InputDeviceIdentifier identifier) {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        return inputManager != null ? inputManager.getInputDeviceByDescriptor(
                identifier.getDescriptor()) : null;
    }

    private static String createLayoutKey(InputDeviceIdentifier identifier, int userId,
            @NonNull InputMethodSubtypeHandle subtypeHandle) {
        Objects.requireNonNull(subtypeHandle, "subtypeHandle must not be null");
        return "layoutDescriptor:" + getLayoutDescriptor(identifier) + ",userId:" + userId
                + ",subtypeHandle:" + subtypeHandle.toStringHandle();
    }

    private static boolean isLayoutCompatibleWithLanguageTag(KeyboardLayout layout,
            @NonNull String languageTag) {
        final int[] scriptsFromLanguageTag = UScript.getCode(Locale.forLanguageTag(languageTag));
        if (scriptsFromLanguageTag.length == 0) {
            // If no scripts inferred from languageTag then allowing the layout
            return true;
        }
        LocaleList locales = layout.getLocales();
        if (locales.isEmpty()) {
            // KCM file doesn't have an associated language tag. This can be from
            // a 3rd party app so need to include it as a potential layout.
            return true;
        }
        for (int i = 0; i < locales.size(); i++) {
            final Locale locale = locales.get(i);
            if (locale == null) {
                continue;
            }
            int[] scripts = UScript.getCode(locale);
            if (scripts != null && haveCommonValue(scripts, scriptsFromLanguageTag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean haveCommonValue(int[] arr1, int[] arr2) {
        for (int a1 : arr1) {
            for (int a2 : arr2) {
                if (a1 == a2) return true;
            }
        }
        return false;
    }

    private static final class KeyboardLayoutDescriptor {
        public String packageName;
        public String receiverName;
        public String keyboardLayoutName;

        public static String format(String packageName,
                String receiverName, String keyboardName) {
            return packageName + "/" + receiverName + "/" + keyboardName;
        }

        public static KeyboardLayoutDescriptor parse(String descriptor) {
            int pos = descriptor.indexOf('/');
            if (pos < 0 || pos + 1 == descriptor.length()) {
                return null;
            }
            int pos2 = descriptor.indexOf('/', pos + 1);
            if (pos2 < pos + 2 || pos2 + 1 == descriptor.length()) {
                return null;
            }

            KeyboardLayoutDescriptor result = new KeyboardLayoutDescriptor();
            result.packageName = descriptor.substring(0, pos);
            result.receiverName = descriptor.substring(pos + 1, pos2);
            result.keyboardLayoutName = descriptor.substring(pos2 + 1);
            return result;
        }
    }

    private static class ImeInfo {
        @UserIdInt int mUserId;
        @NonNull InputMethodSubtypeHandle mImeSubtypeHandle;
        @Nullable InputMethodSubtype mImeSubtype;

        ImeInfo(@UserIdInt int userId, @NonNull InputMethodSubtypeHandle imeSubtypeHandle,
                @Nullable InputMethodSubtype imeSubtype) {
            mUserId = userId;
            mImeSubtypeHandle = imeSubtypeHandle;
            mImeSubtype = imeSubtype;
        }
    }

    private interface KeyboardLayoutVisitor {
        void visitKeyboardLayout(Resources resources,
                int keyboardLayoutResId, KeyboardLayout layout);
    }
}

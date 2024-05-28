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

import static android.hardware.input.KeyboardLayoutSelectionResult.FAILED;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_USER;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEVICE;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEFAULT;

import android.annotation.AnyThread;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.settings.SettingsEnums;
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
import android.hardware.input.KeyboardLayoutSelectionResult;
import android.icu.lang.UScript;
import android.icu.util.ULocale;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.InputMethodSubtypeHandle;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.XmlUtils;
import com.android.server.LocalServices;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;
import com.android.server.input.KeyboardMetricsCollector.KeyboardConfigurationEvent;
import com.android.server.inputmethod.InputMethodManagerInternal;

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
import java.util.Set;
import java.util.stream.Stream;

/**
 * A component of {@link InputManagerService} responsible for managing Physical Keyboard layouts.
 *
 * @hide
 */
class KeyboardLayoutManager implements InputManager.InputDeviceListener {

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

    // Connected keyboards with associated keyboard layouts (either auto-detected or manually
    // selected layout).
    private final SparseArray<KeyboardConfiguration> mConfiguredKeyboards = new SparseArray<>();
    private Toast mSwitchedKeyboardLayoutToast;

    // This cache stores "best-matched" layouts so that we don't need to run the matching
    // algorithm repeatedly.
    @GuardedBy("mKeyboardLayoutCache")
    private final Map<String, KeyboardLayoutSelectionResult> mKeyboardLayoutCache =
            new ArrayMap<>();
    private final Object mImeInfoLock = new Object();
    @Nullable
    @GuardedBy("mImeInfoLock")
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
    @MainThread
    public void onInputDeviceAdded(int deviceId) {
        // Logging keyboard configuration data to statsd whenever input device is added. Currently
        // only logging for New Settings UI where we are using IME to decide the layout information.
        onInputDeviceChangedInternal(deviceId, true /* shouldLogConfiguration */);
    }

    @Override
    @MainThread
    public void onInputDeviceRemoved(int deviceId) {
        mConfiguredKeyboards.remove(deviceId);
        maybeUpdateNotification();
    }

    @Override
    @MainThread
    public void onInputDeviceChanged(int deviceId) {
        onInputDeviceChangedInternal(deviceId, false /* shouldLogConfiguration */);
    }

    private void onInputDeviceChangedInternal(int deviceId, boolean shouldLogConfiguration) {
        final InputDevice inputDevice = getInputDevice(deviceId);
        if (inputDevice == null || inputDevice.isVirtual() || !inputDevice.isFullKeyboard()) {
            return;
        }
        final KeyboardIdentifier keyboardIdentifier = new KeyboardIdentifier(inputDevice);
        KeyboardConfiguration config = mConfiguredKeyboards.get(deviceId);
        if (config == null) {
            config = new KeyboardConfiguration(deviceId);
            mConfiguredKeyboards.put(deviceId, config);
        }

        boolean needToShowNotification = false;
        if (!useNewSettingsUi()) {
            synchronized (mDataStore) {
                String layout = getCurrentKeyboardLayoutForInputDevice(inputDevice.getIdentifier());
                if (layout == null) {
                    layout = getDefaultKeyboardLayout(inputDevice);
                    if (layout != null) {
                        setCurrentKeyboardLayoutForInputDevice(inputDevice.getIdentifier(), layout);
                    }
                }
                if (layout == null) {
                    // In old settings show notification always until user manually selects a
                    // layout in the settings.
                    needToShowNotification = true;
                }
            }
        } else {
            Set<String> selectedLayouts = new HashSet<>();
            List<ImeInfo> imeInfoList = getImeInfoListForLayoutMapping();
            List<KeyboardLayoutSelectionResult> resultList = new ArrayList<>();
            boolean hasMissingLayout = false;
            for (ImeInfo imeInfo : imeInfoList) {
                // Check if the layout has been previously configured
                KeyboardLayoutSelectionResult result = getKeyboardLayoutForInputDeviceInternal(
                        keyboardIdentifier, imeInfo);
                boolean noLayoutFound = result.getLayoutDescriptor() == null;
                if (!noLayoutFound) {
                    selectedLayouts.add(result.getLayoutDescriptor());
                }
                resultList.add(result);
                hasMissingLayout |= noLayoutFound;
            }

            if (DEBUG) {
                Slog.d(TAG,
                        "Layouts selected for input device: " + keyboardIdentifier
                                + " -> selectedLayouts: " + selectedLayouts);
            }

            // If even one layout not configured properly, we need to ask user to configure
            // the keyboard properly from the Settings.
            if (hasMissingLayout) {
                selectedLayouts.clear();
            }

            config.setConfiguredLayouts(selectedLayouts);

            synchronized (mDataStore) {
                try {
                    final String key = keyboardIdentifier.toString();
                    if (mDataStore.setSelectedKeyboardLayouts(key, selectedLayouts)) {
                        // Need to show the notification only if layout selection changed
                        // from the previous configuration
                        needToShowNotification = true;
                    }

                    if (shouldLogConfiguration) {
                        logKeyboardConfigurationEvent(inputDevice, imeInfoList, resultList,
                                !mDataStore.hasInputDeviceEntry(key));
                    }
                } finally {
                    mDataStore.saveIfNeeded();
                }
            }
        }
        if (needToShowNotification) {
            maybeUpdateNotification();
        }
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

    @AnyThread
    public KeyboardLayout[] getKeyboardLayouts() {
        final ArrayList<KeyboardLayout> list = new ArrayList<>();
        visitAllKeyboardLayouts((resources, keyboardLayoutResId, layout) -> list.add(layout));
        return list.toArray(new KeyboardLayout[0]);
    }

    @AnyThread
    public KeyboardLayout[] getKeyboardLayoutsForInputDevice(
            final InputDeviceIdentifier identifier) {
        if (useNewSettingsUi()) {
            // Provide all supported keyboard layouts since Ime info is not provided
            return getKeyboardLayouts();
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

    @AnyThread
    @Nullable
    public KeyboardLayout getKeyboardLayout(@NonNull String keyboardLayoutDescriptor) {
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

    @AnyThread
    public KeyCharacterMap getKeyCharacterMap(@NonNull String layoutDescriptor) {
        final String[] overlay = new String[1];
        visitKeyboardLayout(layoutDescriptor,
                (resources, keyboardLayoutResId, layout) -> {
                    try (InputStreamReader stream = new InputStreamReader(
                            resources.openRawResource(keyboardLayoutResId))) {
                        overlay[0] = Streams.readFully(stream);
                    } catch (IOException | Resources.NotFoundException ignored) {
                    }
                });
        if (TextUtils.isEmpty(overlay[0])) {
            return KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        }
        return KeyCharacterMap.load(layoutDescriptor, overlay[0]);
    }

    private void visitAllKeyboardLayouts(KeyboardLayoutVisitor visitor) {
        final PackageManager pm = mContext.getPackageManager();
        Intent intent = new Intent(InputManager.ACTION_QUERY_KEYBOARD_LAYOUTS);
        for (ResolveInfo resolveInfo : pm.queryBroadcastReceiversAsUser(intent,
                PackageManager.GET_META_DATA | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, UserHandle.USER_SYSTEM)) {
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

    @AnyThread
    @Nullable
    public String getCurrentKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier) {
        if (useNewSettingsUi()) {
            Slog.e(TAG, "getCurrentKeyboardLayoutForInputDevice API not supported");
            return null;
        }
        String key = new KeyboardIdentifier(identifier).toString();
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

    @AnyThread
    public void setCurrentKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        if (useNewSettingsUi()) {
            Slog.e(TAG, "setCurrentKeyboardLayoutForInputDevice API not supported");
            return;
        }

        Objects.requireNonNull(keyboardLayoutDescriptor,
                "keyboardLayoutDescriptor must not be null");
        String key = new KeyboardIdentifier(identifier).toString();
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

    @AnyThread
    public String[] getEnabledKeyboardLayoutsForInputDevice(InputDeviceIdentifier identifier) {
        if (useNewSettingsUi()) {
            Slog.e(TAG, "getEnabledKeyboardLayoutsForInputDevice API not supported");
            return new String[0];
        }
        String key = new KeyboardIdentifier(identifier).toString();
        synchronized (mDataStore) {
            String[] layouts = mDataStore.getKeyboardLayouts(key);
            if ((layouts == null || layouts.length == 0)
                    && !key.equals(identifier.getDescriptor())) {
                layouts = mDataStore.getKeyboardLayouts(identifier.getDescriptor());
            }
            return layouts;
        }
    }

    @AnyThread
    public void addKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        if (useNewSettingsUi()) {
            Slog.e(TAG, "addKeyboardLayoutForInputDevice API not supported");
            return;
        }
        Objects.requireNonNull(keyboardLayoutDescriptor,
                "keyboardLayoutDescriptor must not be null");

        String key = new KeyboardIdentifier(identifier).toString();
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

    @AnyThread
    public void removeKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        if (useNewSettingsUi()) {
            Slog.e(TAG, "removeKeyboardLayoutForInputDevice API not supported");
            return;
        }
        Objects.requireNonNull(keyboardLayoutDescriptor,
                "keyboardLayoutDescriptor must not be null");

        String key = new KeyboardIdentifier(identifier).toString();
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

    @AnyThread
    public void switchKeyboardLayout(int deviceId, int direction) {
        if (useNewSettingsUi()) {
            Slog.e(TAG, "switchKeyboardLayout API not supported");
            return;
        }
        mHandler.obtainMessage(MSG_SWITCH_KEYBOARD_LAYOUT, deviceId, direction).sendToTarget();
    }

    @MainThread
    private void handleSwitchKeyboardLayout(int deviceId, int direction) {
        final InputDevice device = getInputDevice(deviceId);
        if (device != null) {
            final boolean changed;
            final String keyboardLayoutDescriptor;

            String key = new KeyboardIdentifier(device.getIdentifier()).toString();
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
    @AnyThread
    public String[] getKeyboardLayoutOverlay(InputDeviceIdentifier identifier, String languageTag,
            String layoutType) {
        String keyboardLayoutDescriptor;
        if (useNewSettingsUi()) {
            synchronized (mImeInfoLock) {
                KeyboardLayoutSelectionResult result = getKeyboardLayoutForInputDeviceInternal(
                        new KeyboardIdentifier(identifier, languageTag, layoutType),
                        mCurrentImeInfo);
                keyboardLayoutDescriptor = result.getLayoutDescriptor();
            }
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

    @AnyThread
    @NonNull
    public KeyboardLayoutSelectionResult getKeyboardLayoutForInputDevice(
            InputDeviceIdentifier identifier, @UserIdInt int userId,
            @NonNull InputMethodInfo imeInfo, @Nullable InputMethodSubtype imeSubtype) {
        if (!useNewSettingsUi()) {
            Slog.e(TAG, "getKeyboardLayoutForInputDevice() API not supported");
            return FAILED;
        }
        InputDevice inputDevice = getInputDevice(identifier);
        if (inputDevice == null || inputDevice.isVirtual() || !inputDevice.isFullKeyboard()) {
            return FAILED;
        }
        KeyboardIdentifier keyboardIdentifier = new KeyboardIdentifier(inputDevice);
        KeyboardLayoutSelectionResult result = getKeyboardLayoutForInputDeviceInternal(
                keyboardIdentifier, new ImeInfo(userId, imeInfo, imeSubtype));
        if (DEBUG) {
            Slog.d(TAG, "getKeyboardLayoutForInputDevice() " + identifier.toString() + ", userId : "
                    + userId + ", subtype = " + imeSubtype + " -> " + result);
        }
        return result;
    }

    @AnyThread
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
        InputDevice inputDevice = getInputDevice(identifier);
        if (inputDevice == null || inputDevice.isVirtual() || !inputDevice.isFullKeyboard()) {
            return;
        }
        KeyboardIdentifier keyboardIdentifier = new KeyboardIdentifier(inputDevice);
        String layoutKey = new LayoutKey(keyboardIdentifier,
                new ImeInfo(userId, imeInfo, imeSubtype)).toString();
        synchronized (mDataStore) {
            try {
                if (mDataStore.setKeyboardLayout(keyboardIdentifier.toString(), layoutKey,
                        keyboardLayoutDescriptor)) {
                    if (DEBUG) {
                        Slog.d(TAG, "setKeyboardLayoutForInputDevice() " + identifier
                                + " key: " + layoutKey
                                + " keyboardLayoutDescriptor: " + keyboardLayoutDescriptor);
                    }
                    mHandler.sendEmptyMessage(MSG_RELOAD_KEYBOARD_LAYOUTS);
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    @AnyThread
    public KeyboardLayout[] getKeyboardLayoutListForInputDevice(InputDeviceIdentifier identifier,
            @UserIdInt int userId, @NonNull InputMethodInfo imeInfo,
            @Nullable InputMethodSubtype imeSubtype) {
        if (!useNewSettingsUi()) {
            Slog.e(TAG, "getKeyboardLayoutListForInputDevice() API not supported");
            return new KeyboardLayout[0];
        }
        InputDevice inputDevice = getInputDevice(identifier);
        if (inputDevice == null || inputDevice.isVirtual() || !inputDevice.isFullKeyboard()) {
            return new KeyboardLayout[0];
        }
        return getKeyboardLayoutListForInputDeviceInternal(new KeyboardIdentifier(inputDevice),
                new ImeInfo(userId, imeInfo, imeSubtype));
    }

    private KeyboardLayout[] getKeyboardLayoutListForInputDeviceInternal(
            KeyboardIdentifier keyboardIdentifier, @Nullable ImeInfo imeInfo) {
        String layoutKey = new LayoutKey(keyboardIdentifier, imeInfo).toString();

        // Fetch user selected layout and always include it in layout list.
        String userSelectedLayout;
        synchronized (mDataStore) {
            userSelectedLayout = mDataStore.getKeyboardLayout(keyboardIdentifier.toString(),
                    layoutKey);
        }

        final ArrayList<KeyboardLayout> potentialLayouts = new ArrayList<>();
        String imeLanguageTag;
        if (imeInfo == null || imeInfo.mImeSubtype == null) {
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
                if (layout.getVendorId() == keyboardIdentifier.mIdentifier.getVendorId()
                        && layout.getProductId() == keyboardIdentifier.mIdentifier.getProductId()) {
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

    @AnyThread
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
        synchronized (mImeInfoLock) {
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
    }

    @Nullable
    private KeyboardLayoutSelectionResult getKeyboardLayoutForInputDeviceInternal(
            KeyboardIdentifier keyboardIdentifier, @Nullable ImeInfo imeInfo) {
        String layoutKey = new LayoutKey(keyboardIdentifier, imeInfo).toString();
        synchronized (mDataStore) {
            String layout = mDataStore.getKeyboardLayout(keyboardIdentifier.toString(), layoutKey);
            if (layout != null) {
                return new KeyboardLayoutSelectionResult(layout, LAYOUT_SELECTION_CRITERIA_USER);
            }
        }

        synchronized (mKeyboardLayoutCache) {
            // Check Auto-selected layout cache to see if layout had been previously selected
            if (mKeyboardLayoutCache.containsKey(layoutKey)) {
                return mKeyboardLayoutCache.get(layoutKey);
            } else {
                // NOTE: This list is already filtered based on IME Script code
                KeyboardLayout[] layoutList = getKeyboardLayoutListForInputDeviceInternal(
                        keyboardIdentifier, imeInfo);
                // Call auto-matching algorithm to find the best matching layout
                KeyboardLayoutSelectionResult result =
                        getDefaultKeyboardLayoutBasedOnImeInfo(keyboardIdentifier, imeInfo,
                                layoutList);
                mKeyboardLayoutCache.put(layoutKey, result);
                return result;
            }
        }
    }

    @NonNull
    private static KeyboardLayoutSelectionResult getDefaultKeyboardLayoutBasedOnImeInfo(
            KeyboardIdentifier keyboardIdentifier, @Nullable ImeInfo imeInfo,
            KeyboardLayout[] layoutList) {
        Arrays.sort(layoutList);

        // Check <VendorID, ProductID> matching for explicitly declared custom KCM files.
        for (KeyboardLayout layout : layoutList) {
            if (layout.getVendorId() == keyboardIdentifier.mIdentifier.getVendorId()
                    && layout.getProductId() == keyboardIdentifier.mIdentifier.getProductId()) {
                if (DEBUG) {
                    Slog.d(TAG,
                            "getDefaultKeyboardLayoutBasedOnImeInfo() : Layout found based on "
                                    + "vendor and product Ids. " + keyboardIdentifier
                                    + " : " + layout.getDescriptor());
                }
                return new KeyboardLayoutSelectionResult(layout.getDescriptor(),
                        LAYOUT_SELECTION_CRITERIA_DEVICE);
            }
        }

        // Check layout type, language tag information from InputDevice for matching
        String inputLanguageTag = keyboardIdentifier.mLanguageTag;
        if (inputLanguageTag != null) {
            String layoutDesc = getMatchingLayoutForProvidedLanguageTagAndLayoutType(layoutList,
                    inputLanguageTag, keyboardIdentifier.mLayoutType);

            if (layoutDesc != null) {
                if (DEBUG) {
                    Slog.d(TAG,
                            "getDefaultKeyboardLayoutBasedOnImeInfo() : Layout found based on "
                                    + "HW information (Language tag and Layout type). "
                                    + keyboardIdentifier + " : " + layoutDesc);
                }
                return new KeyboardLayoutSelectionResult(layoutDesc,
                        LAYOUT_SELECTION_CRITERIA_DEVICE);
            }
        }

        if (imeInfo == null || imeInfo.mImeSubtypeHandle == null || imeInfo.mImeSubtype == null) {
            // Can't auto select layout based on IME info is null
            return FAILED;
        }

        InputMethodSubtype subtype = imeInfo.mImeSubtype;
        // Check layout type, language tag information from IME for matching
        ULocale pkLocale = subtype.getPhysicalKeyboardHintLanguageTag();
        String pkLanguageTag =
                pkLocale != null ? pkLocale.toLanguageTag() : subtype.getCanonicalizedLanguageTag();
        String layoutDesc = getMatchingLayoutForProvidedLanguageTagAndLayoutType(layoutList,
                pkLanguageTag, subtype.getPhysicalKeyboardHintLayoutType());
        if (DEBUG) {
            Slog.d(TAG,
                    "getDefaultKeyboardLayoutBasedOnImeInfo() : Layout found based on "
                            + "IME locale matching. " + keyboardIdentifier + " : "
                            + layoutDesc);
        }
        if (layoutDesc != null) {
            return new KeyboardLayoutSelectionResult(layoutDesc,
                    LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD);
        }
        return FAILED;
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

    @MainThread
    private void maybeUpdateNotification() {
        List<KeyboardConfiguration> configurations = new ArrayList<>();
        for (int i = 0; i < mConfiguredKeyboards.size(); i++) {
            int deviceId = mConfiguredKeyboards.keyAt(i);
            KeyboardConfiguration config = mConfiguredKeyboards.valueAt(i);
            if (isVirtualDevice(deviceId)) {
                continue;
            }
            // If we have a keyboard with no selected layouts, we should always show missing
            // layout notification even if there are other keyboards that are configured properly.
            if (!config.hasConfiguredLayouts()) {
                showMissingKeyboardLayoutNotification();
                return;
            }
            configurations.add(config);
        }
        if (configurations.size() == 0) {
            hideKeyboardLayoutNotification();
            return;
        }
        showConfiguredKeyboardLayoutNotification(configurations);
    }

    @MainThread
    private void showMissingKeyboardLayoutNotification() {
        final Resources r = mContext.getResources();
        final String missingKeyboardLayoutNotificationContent = r.getString(
                R.string.select_keyboard_layout_notification_message);

        if (mConfiguredKeyboards.size() == 1) {
            final InputDevice device = getInputDevice(mConfiguredKeyboards.keyAt(0));
            if (device == null) {
                return;
            }
            showKeyboardLayoutNotification(
                    r.getString(
                            R.string.select_keyboard_layout_notification_title,
                            device.getName()),
                    missingKeyboardLayoutNotificationContent,
                    device);
        } else {
            showKeyboardLayoutNotification(
                    r.getString(R.string.select_multiple_keyboards_layout_notification_title),
                    missingKeyboardLayoutNotificationContent,
                    null);
        }
    }

    @MainThread
    private void showKeyboardLayoutNotification(@NonNull String intentTitle,
            @NonNull String intentContent, @Nullable InputDevice targetDevice) {
        final NotificationManager notificationManager = mContext.getSystemService(
                NotificationManager.class);
        if (notificationManager == null) {
            return;
        }

        final Intent intent = new Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS);

        if (targetDevice != null) {
            intent.putExtra(Settings.EXTRA_INPUT_DEVICE_IDENTIFIER, targetDevice.getIdentifier());
            intent.putExtra(
                    Settings.EXTRA_ENTRYPOINT, SettingsEnums.KEYBOARD_CONFIGURED_NOTIFICATION);
        }

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final PendingIntent keyboardLayoutIntent = PendingIntent.getActivityAsUser(mContext, 0,
                intent, PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);

        Notification notification =
                new Notification.Builder(mContext, SystemNotificationChannels.PHYSICAL_KEYBOARD)
                        .setContentTitle(intentTitle)
                        .setContentText(intentContent)
                        .setContentIntent(keyboardLayoutIntent)
                        .setSmallIcon(R.drawable.ic_settings_language)
                        .setColor(mContext.getColor(
                                com.android.internal.R.color.system_notification_accent_color))
                        .setAutoCancel(true)
                        .build();
        notificationManager.notifyAsUser(null,
                SystemMessageProto.SystemMessage.NOTE_SELECT_KEYBOARD_LAYOUT,
                notification, UserHandle.ALL);
    }

    @MainThread
    private void hideKeyboardLayoutNotification() {
        NotificationManager notificationManager = mContext.getSystemService(
                NotificationManager.class);
        if (notificationManager == null) {
            return;
        }

        notificationManager.cancelAsUser(null,
                SystemMessageProto.SystemMessage.NOTE_SELECT_KEYBOARD_LAYOUT,
                UserHandle.ALL);
    }

    @MainThread
    private void showConfiguredKeyboardLayoutNotification(
            List<KeyboardConfiguration> configurations) {
        final Resources r = mContext.getResources();

        if (configurations.size() != 1) {
            showKeyboardLayoutNotification(
                    r.getString(R.string.keyboard_layout_notification_multiple_selected_title),
                    r.getString(R.string.keyboard_layout_notification_multiple_selected_message),
                    null);
            return;
        }

        final KeyboardConfiguration config = configurations.get(0);
        final InputDevice inputDevice = getInputDevice(config.getDeviceId());
        if (inputDevice == null || !config.hasConfiguredLayouts()) {
            return;
        }

        showKeyboardLayoutNotification(
                r.getString(
                        R.string.keyboard_layout_notification_selected_title,
                        inputDevice.getName()),
                createConfiguredNotificationText(mContext, config.getConfiguredLayouts()),
                inputDevice);
    }

    @MainThread
    private String createConfiguredNotificationText(@NonNull Context context,
            @NonNull Set<String> selectedLayouts) {
        final Resources r = context.getResources();
        List<String> layoutNames = new ArrayList<>();
        selectedLayouts.forEach(
                (layoutDesc) -> layoutNames.add(getKeyboardLayout(layoutDesc).getLabel()));
        Collections.sort(layoutNames);
        switch (layoutNames.size()) {
            case 1:
                return r.getString(R.string.keyboard_layout_notification_one_selected_message,
                        layoutNames.get(0));
            case 2:
                return r.getString(R.string.keyboard_layout_notification_two_selected_message,
                        layoutNames.get(0), layoutNames.get(1));
            case 3:
                return r.getString(R.string.keyboard_layout_notification_three_selected_message,
                        layoutNames.get(0), layoutNames.get(1), layoutNames.get(2));
            default:
                return r.getString(
                        R.string.keyboard_layout_notification_more_than_three_selected_message,
                        layoutNames.get(0), layoutNames.get(1), layoutNames.get(2));
        }
    }

    private void logKeyboardConfigurationEvent(@NonNull InputDevice inputDevice,
            @NonNull List<ImeInfo> imeInfoList,
            @NonNull List<KeyboardLayoutSelectionResult> resultList,
            boolean isFirstConfiguration) {
        if (imeInfoList.isEmpty() || resultList.isEmpty()) {
            return;
        }
        KeyboardConfigurationEvent.Builder configurationEventBuilder =
                new KeyboardConfigurationEvent.Builder(inputDevice).setIsFirstTimeConfiguration(
                        isFirstConfiguration);
        for (int i = 0; i < imeInfoList.size(); i++) {
            KeyboardLayoutSelectionResult result = resultList.get(i);
            String layoutName = null;
            int layoutSelectionCriteria = LAYOUT_SELECTION_CRITERIA_DEFAULT;
            if (result != null && result.getLayoutDescriptor() != null) {
                layoutSelectionCriteria = result.getSelectionCriteria();
                KeyboardLayoutDescriptor d = KeyboardLayoutDescriptor.parse(
                        result.getLayoutDescriptor());
                if (d != null) {
                    layoutName = d.keyboardLayoutName;
                }
            }
            configurationEventBuilder.addLayoutSelection(imeInfoList.get(i).mImeSubtype, layoutName,
                    layoutSelectionCriteria);
        }
        KeyboardMetricsCollector.logKeyboardConfiguredAtom(configurationEventBuilder.build());
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

    @SuppressLint("MissingPermission")
    @VisibleForTesting
    public List<ImeInfo> getImeInfoListForLayoutMapping() {
        List<ImeInfo> imeInfoList = new ArrayList<>();
        UserManager userManager = Objects.requireNonNull(
                mContext.getSystemService(UserManager.class));
        InputMethodManager inputMethodManager = Objects.requireNonNull(
                mContext.getSystemService(InputMethodManager.class));
        // Need to use InputMethodManagerInternal to call getEnabledInputMethodListAsUser()
        // instead of using InputMethodManager which uses enforceCallingPermissions() that
        // breaks when we are calling the method for work profile user ID since it doesn't check
        // self permissions.
        InputMethodManagerInternal inputMethodManagerInternal = InputMethodManagerInternal.get();
        for (UserHandle userHandle : userManager.getUserHandles(true /* excludeDying */)) {
            int userId = userHandle.getIdentifier();
            for (InputMethodInfo imeInfo :
                    inputMethodManagerInternal.getEnabledInputMethodListAsUser(
                            userId)) {
                for (InputMethodSubtype imeSubtype :
                        inputMethodManager.getEnabledInputMethodSubtypeList(
                                imeInfo, true /* allowsImplicitlyEnabledSubtypes */)) {
                    if (!imeSubtype.isSuitableForPhysicalKeyboardLayoutMapping()) {
                        continue;
                    }
                    imeInfoList.add(new ImeInfo(userId, imeInfo, imeSubtype));
                }
            }
        }
        return imeInfoList;
    }

    private static boolean isLayoutCompatibleWithLanguageTag(KeyboardLayout layout,
            @NonNull String languageTag) {
        LocaleList layoutLocales = layout.getLocales();
        if (layoutLocales.isEmpty() || TextUtils.isEmpty(languageTag)) {
            // KCM file doesn't have an associated language tag. This can be from
            // a 3rd party app so need to include it as a potential layout.
            return true;
        }
        // Match derived Script codes
        final int[] scriptsFromLanguageTag = getScriptCodes(Locale.forLanguageTag(languageTag));
        if (scriptsFromLanguageTag.length == 0) {
            // If no scripts inferred from languageTag then allowing the layout
            return true;
        }
        for (int i = 0; i < layoutLocales.size(); i++) {
            final Locale locale = layoutLocales.get(i);
            int[] scripts = getScriptCodes(locale);
            if (haveCommonValue(scripts, scriptsFromLanguageTag)) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    public boolean isVirtualDevice(int deviceId) {
        VirtualDeviceManagerInternal vdm = LocalServices.getService(
                VirtualDeviceManagerInternal.class);
        return vdm != null && vdm.isInputDeviceOwnedByVirtualDevice(deviceId);
    }

    private static int[] getScriptCodes(@Nullable Locale locale) {
        if (locale == null) {
            return new int[0];
        }
        if (!TextUtils.isEmpty(locale.getScript())) {
            int scriptCode = UScript.getCodeFromName(locale.getScript());
            if (scriptCode != UScript.INVALID_CODE) {
                return new int[]{scriptCode};
            }
        }
        int[] scripts = UScript.getCode(locale);
        if (scripts != null) {
            return scripts;
        }
        return new int[0];
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

    @VisibleForTesting
    public static class ImeInfo {
        @UserIdInt int mUserId;
        @NonNull InputMethodSubtypeHandle mImeSubtypeHandle;
        @Nullable InputMethodSubtype mImeSubtype;

        ImeInfo(@UserIdInt int userId, @NonNull InputMethodSubtypeHandle imeSubtypeHandle,
                @Nullable InputMethodSubtype imeSubtype) {
            mUserId = userId;
            mImeSubtypeHandle = imeSubtypeHandle;
            mImeSubtype = imeSubtype;
        }

        ImeInfo(@UserIdInt int userId, @NonNull InputMethodInfo imeInfo,
                @Nullable InputMethodSubtype imeSubtype) {
            this(userId, InputMethodSubtypeHandle.of(imeInfo, imeSubtype), imeSubtype);
        }
    }

    private static class KeyboardConfiguration {

        // If null or empty, it means no layout is configured for the device. And user needs to
        // manually set up the device.
        @Nullable
        private Set<String> mConfiguredLayouts;

        private final int mDeviceId;

        private KeyboardConfiguration(int deviceId) {
            mDeviceId = deviceId;
        }

        private int getDeviceId() {
            return mDeviceId;
        }

        private boolean hasConfiguredLayouts() {
            return mConfiguredLayouts != null && !mConfiguredLayouts.isEmpty();
        }

        @Nullable
        private Set<String> getConfiguredLayouts() {
            return mConfiguredLayouts;
        }

        private void setConfiguredLayouts(Set<String> configuredLayouts) {
            mConfiguredLayouts = configuredLayouts;
        }
    }

    private interface KeyboardLayoutVisitor {
        void visitKeyboardLayout(Resources resources,
                int keyboardLayoutResId, KeyboardLayout layout);
    }

    private static class KeyboardIdentifier {
        @NonNull
        private final InputDeviceIdentifier mIdentifier;
        @Nullable
        private final String mLanguageTag;
        @Nullable
        private final String mLayoutType;

        // NOTE: Use this only for old settings UI where we don't use language tag and layout
        // type to determine the KCM file.
        private KeyboardIdentifier(@NonNull InputDeviceIdentifier inputDeviceIdentifier) {
            this(inputDeviceIdentifier, null, null);
        }

        private KeyboardIdentifier(@NonNull InputDevice inputDevice) {
            this(inputDevice.getIdentifier(), inputDevice.getKeyboardLanguageTag(),
                    inputDevice.getKeyboardLayoutType());
        }

        private KeyboardIdentifier(@NonNull InputDeviceIdentifier identifier,
                @Nullable String languageTag, @Nullable String layoutType) {
            Objects.requireNonNull(identifier, "identifier must not be null");
            Objects.requireNonNull(identifier.getDescriptor(), "descriptor must not be null");
            mIdentifier = identifier;
            mLanguageTag = languageTag;
            mLayoutType = layoutType;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(toString());
        }

        @Override
        public String toString() {
            if (mIdentifier.getVendorId() == 0 && mIdentifier.getProductId() == 0) {
                return mIdentifier.getDescriptor();
            }
            // If vendor id and product id is available, use it as keys. This allows us to have the
            // same setup for all keyboards with same product and vendor id. i.e. User can swap 2
            // identical keyboards and still get the same setup.
            StringBuilder key = new StringBuilder();
            key.append("vendor:").append(mIdentifier.getVendorId()).append(",product:").append(
                    mIdentifier.getProductId());

            // Some keyboards can have same product ID and vendor ID but different Keyboard info
            // like language tag and layout type.
            if (!TextUtils.isEmpty(mLanguageTag)) {
                key.append(",languageTag:").append(mLanguageTag);
            }
            if (!TextUtils.isEmpty(mLayoutType)) {
                key.append(",layoutType:").append(mLayoutType);
            }
            return key.toString();
        }
    }

    private static class LayoutKey {

        private final KeyboardIdentifier mKeyboardIdentifier;
        @Nullable
        private final ImeInfo mImeInfo;

        private LayoutKey(KeyboardIdentifier keyboardIdentifier, @Nullable ImeInfo imeInfo) {
            mKeyboardIdentifier = keyboardIdentifier;
            mImeInfo = imeInfo;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(toString());
        }

        @Override
        public String toString() {
            if (mImeInfo == null) {
                return mKeyboardIdentifier.toString();
            }
            Objects.requireNonNull(mImeInfo.mImeSubtypeHandle, "subtypeHandle must not be null");
            return "layoutDescriptor:" + mKeyboardIdentifier + ",userId:" + mImeInfo.mUserId
                    + ",subtypeHandle:" + mImeInfo.mImeSubtypeHandle.toStringHandle();
        }
    }
}

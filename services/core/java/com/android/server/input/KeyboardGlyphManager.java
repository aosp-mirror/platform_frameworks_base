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

import static com.android.hardware.input.Flags.keyboardGlyphMap;

import android.annotation.AnyRes;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.hardware.input.InputManager;
import android.hardware.input.KeyGlyphMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.InputDevice;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Provides information on custom glyphs configured for specific keyboard devices.
 *
 * @hide
 */
public final class KeyboardGlyphManager implements InputManager.InputDeviceListener {

    private static final String TAG = "KeyboardGlyphManager";
    // To enable these logs, run: 'adb shell setprop log.tag.KeyboardGlyphManager DEBUG'
    // (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String TAG_KEYBOARD_GLYPH_MAPS = "keyboard-glyph-maps";
    private static final String TAG_KEYBOARD_GLYPH_MAP = "keyboard-glyph-map";
    private static final String TAG_KEY_GLYPH = "key-glyph";
    private static final String TAG_MODIFIER_GLYPH = "modifier-glyph";
    private static final String TAG_FUNCTION_ROW_KEY = "function-row-key";
    private static final String TAG_HARDWARE_DEFINED_SHORTCUT = "hardware-defined-shortcut";

    private final Context mContext;
    private final Handler mHandler;
    private final Object mGlyphMapLock = new Object();
    @GuardedBy("mGlyphMapLock")
    private boolean mGlyphMapDataLoaded = false;
    @GuardedBy("mGlyphMapLock")
    private List<KeyGlyphMapData> mGlyphMapDataList = new ArrayList<>();
    // Cache for already loaded glyph maps
    @GuardedBy("mGlyphMapLock")
    private final SparseArray<KeyGlyphMap> mGlyphMapCache = new SparseArray<>();

    KeyboardGlyphManager(Context context, Looper looper) {
        mContext = context;
        mHandler = new Handler(looper);
    }

    void systemRunning() {
        if (!keyboardGlyphMap()) {
            return;
        }
        // Listen to new Package installations to fetch new Keyboard glyph maps
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                resetMaps();
            }
        }, filter, null, mHandler);
    }

    @Override
    @MainThread
    public void onInputDeviceAdded(int deviceId) {
    }

    @Override
    @MainThread
    public void onInputDeviceRemoved(int deviceId) {
        synchronized (mGlyphMapLock) {
            mGlyphMapCache.remove(deviceId);
        }
    }

    @Override
    @MainThread
    public void onInputDeviceChanged(int deviceId) {
    }

    @MainThread
    private void resetMaps() {
        synchronized (mGlyphMapLock) {
            mGlyphMapDataLoaded = false;
            mGlyphMapDataList.clear();
            mGlyphMapCache.clear();
        }
    }

    @NonNull
    private List<KeyGlyphMapData> loadGlyphMapDataList() {
        final PackageManager pm = mContext.getPackageManager();
        List<KeyGlyphMapData> glyphMaps = new ArrayList<>();
        Intent intent = new Intent(InputManager.ACTION_QUERY_KEYBOARD_GLYPH_MAPS);
        for (ResolveInfo resolveInfo : pm.queryBroadcastReceiversAsUser(intent,
                PackageManager.GET_META_DATA | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, UserHandle.USER_SYSTEM)) {
            if (resolveInfo == null || resolveInfo.activityInfo == null) {
                continue;
            }
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            KeyGlyphMapData data = getKeyboardGlyphMapsInPackage(pm, activityInfo);
            if (data == null) {
                continue;
            }
            glyphMaps.add(data);
        }
        return glyphMaps;
    }

    @Nullable
    private KeyGlyphMapData getKeyboardGlyphMapsInPackage(PackageManager pm,
            @NonNull ActivityInfo receiver) {
        Bundle metaData = receiver.metaData;
        if (metaData == null) {
            return null;
        }

        int configResId = metaData.getInt(InputManager.META_DATA_KEYBOARD_GLYPH_MAPS);
        if (configResId == 0) {
            Slog.w(TAG, "Missing meta-data '" + InputManager.META_DATA_KEYBOARD_GLYPH_MAPS
                    + "' on receiver " + receiver.packageName + "/" + receiver.name);
            return null;
        }

        try {
            Resources resources = pm.getResourcesForApplication(receiver.applicationInfo);
            try (XmlResourceParser parser = resources.getXml(configResId)) {
                XmlUtils.beginDocument(parser, TAG_KEYBOARD_GLYPH_MAPS);

                while (true) {
                    XmlUtils.nextElement(parser);
                    String element = parser.getName();
                    if (element == null) {
                        break;
                    }
                    if (!TAG_KEYBOARD_GLYPH_MAP.equals(element)) {
                        continue;
                    }
                    TypedArray a = resources.obtainAttributes(parser, R.styleable.KeyboardGlyphMap);
                    try {
                        int glyphMapRes = a.getResourceId(R.styleable.KeyboardGlyphMap_glyphMap, 0);
                        int vendor = a.getInt(R.styleable.KeyboardGlyphMap_vendorId, -1);
                        int product = a.getInt(R.styleable.KeyboardGlyphMap_productId, -1);
                        if (glyphMapRes != 0 && vendor != -1 && product != -1) {
                            return new KeyGlyphMapData(receiver.packageName, receiver.name,
                                    glyphMapRes, vendor, product);
                        }
                    } finally {
                        a.recycle();
                    }
                }
            }
        } catch (Exception ex) {
            Slog.w(TAG, "Could not parse keyboard glyph map resource from receiver "
                    + receiver.packageName + "/" + receiver.name, ex);
        }
        return null;
    }

    @Nullable
    private KeyGlyphMap loadGlyphMap(KeyGlyphMapData data) {
        final PackageManager pm = mContext.getPackageManager();
        try {
            ComponentName componentName = new ComponentName(data.packageName, data.receiverName);
            ActivityInfo receiver = pm.getReceiverInfo(componentName,
                    PackageManager.GET_META_DATA
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
            Resources resources = pm.getResourcesForApplication(receiver.applicationInfo);
            return loadGlyphMapFromResource(resources, componentName, data.resourceId);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Package not found: " + e);
        }
        return null;
    }

    @NonNull
    private KeyGlyphMap loadGlyphMapFromResource(Resources resources,
            @NonNull ComponentName componentName, @AnyRes int glyphMapId) {
        SparseIntArray keyGlyphs = new SparseIntArray();
        SparseIntArray modifierGlyphs = new SparseIntArray();
        List<Integer> functionRowKeys = new ArrayList<>();
        HashMap<KeyGlyphMap.KeyCombination, Integer> hardwareShortcuts = new HashMap<>();
        try {
            XmlResourceParser parser = resources.getXml(glyphMapId);
            XmlUtils.beginDocument(parser, TAG_KEYBOARD_GLYPH_MAP);

            while (true) {
                XmlUtils.nextElement(parser);
                String element = parser.getName();
                if (element == null) {
                    break;
                }
                switch (element) {
                    case TAG_KEY_GLYPH -> {
                        final TypedArray a = resources.obtainAttributes(parser,
                                R.styleable.KeyGlyph);
                        try {
                            int keycode = a.getInt(R.styleable.KeyGlyph_keycode, 0);
                            int keyGlyph = a.getResourceId(R.styleable.KeyGlyph_glyphDrawable, 0);
                            if (keycode != 0 && keyGlyph != 0) {
                                keyGlyphs.put(keycode, keyGlyph);
                            }
                        } finally {
                            a.recycle();
                        }
                    }
                    case TAG_MODIFIER_GLYPH -> {
                        final TypedArray a = resources.obtainAttributes(parser,
                                R.styleable.ModifierGlyph);
                        try {
                            int modifier = a.getInt(R.styleable.ModifierGlyph_modifier, 0);
                            int modifierGlyph = a.getResourceId(
                                    R.styleable.ModifierGlyph_glyphDrawable,
                                    0);
                            if (modifier != 0 && modifierGlyph != 0) {
                                modifierGlyphs.put(modifier, modifierGlyph);
                            }
                        } finally {
                            a.recycle();
                        }
                    }
                    case TAG_FUNCTION_ROW_KEY -> {
                        final TypedArray a = resources.obtainAttributes(parser,
                                R.styleable.FunctionRowKey);
                        try {
                            int keycode = a.getInt(R.styleable.FunctionRowKey_keycode, 0);
                            if (keycode != 0) {
                                functionRowKeys.add(keycode);
                            }
                        } finally {
                            a.recycle();
                        }
                    }
                    case TAG_HARDWARE_DEFINED_SHORTCUT -> {
                        final TypedArray a = resources.obtainAttributes(parser,
                                R.styleable.HardwareDefinedShortcut);
                        try {
                            int keycode = a.getInt(R.styleable.HardwareDefinedShortcut_keycode,
                                    0);
                            int modifierState = a.getInt(
                                    R.styleable.HardwareDefinedShortcut_modifierState, 0);
                            int outKeycode = a.getInt(
                                    R.styleable.HardwareDefinedShortcut_outKeycode,
                                    0);
                            if (keycode != 0 && modifierState != 0 && outKeycode != 0) {
                                hardwareShortcuts.put(
                                        new KeyGlyphMap.KeyCombination(modifierState, keycode),
                                        outKeycode);
                            }
                        } finally {
                            a.recycle();
                        }
                    }
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Unable to parse key glyph map : " + e);
        }
        return new KeyGlyphMap(componentName, keyGlyphs, modifierGlyphs,
                functionRowKeys.stream().mapToInt(Integer::intValue).toArray(), hardwareShortcuts);
    }

    /**
     * Returns keyboard glyph map corresponding to device ID
     */
    @Nullable
    public KeyGlyphMap getKeyGlyphMap(int deviceId) {
        if (!keyboardGlyphMap()) {
            return null;
        }
        synchronized (mGlyphMapLock) {
            if (mGlyphMapCache.indexOfKey(deviceId) >= 0) {
                return mGlyphMapCache.get(deviceId);
            }
            KeyGlyphMap keyGlyphMap = getKeyGlyphMapInternal(deviceId);
            mGlyphMapCache.put(deviceId, keyGlyphMap);
            return keyGlyphMap;
        }
    }

    @GuardedBy("mGlyphMapLock")
    private KeyGlyphMap getKeyGlyphMapInternal(int deviceId) {
        final InputDevice inputDevice = getInputDevice(deviceId);
        if (inputDevice == null || inputDevice.isVirtual() || !inputDevice.isFullKeyboard()) {
            return null;
        }
        if (!mGlyphMapDataLoaded) {
            mGlyphMapDataList = loadGlyphMapDataList();
            mGlyphMapDataLoaded = true;
        }
        for (KeyGlyphMapData data : mGlyphMapDataList) {
            if (data.vendorId == inputDevice.getVendorId()
                    && data.productId == inputDevice.getProductId()) {
                return loadGlyphMap(data);
            }
        }
        return null;
    }

    void dump(IndentingPrintWriter ipw) {
        if (!keyboardGlyphMap()) {
            return;
        }
        List<KeyGlyphMapData> glyphMapDataList = loadGlyphMapDataList();
        ipw.println(TAG + ": " + glyphMapDataList.size() + " glyph maps");
        ipw.increaseIndent();
        for (KeyGlyphMapData data : glyphMapDataList) {
            ipw.println(data);
            if (DEBUG) {
                KeyGlyphMap map = loadGlyphMap(data);
                if (map != null) {
                    ipw.increaseIndent();
                    ipw.println(map);
                    ipw.decreaseIndent();
                }
            }
        }
        ipw.decreaseIndent();
    }

    @Nullable
    private InputDevice getInputDevice(int deviceId) {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        return inputManager != null ? inputManager.getInputDevice(deviceId) : null;
    }

    private record KeyGlyphMapData(@NonNull String packageName, @NonNull String receiverName,
                                   @AnyRes int resourceId, int vendorId, int productId) {
    }
}

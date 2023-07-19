/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2021-2023 crDroid Android Project
 *           (C) 2023 ArrowOS
 *           (C) 2023 The LibreMobileOS Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.xtended;

import android.app.ActivityTaskManager;
import android.app.Application;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.ComponentName;
import android.os.Binder;
import android.os.Process;
import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class PixelPropsUtils {

    private static final String TAG = PixelPropsUtils.class.getSimpleName();
    private static final String DEVICE = "ro.product.device";
    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final ComponentName GMS_ADD_ACCOUNT_ACTIVITY = ComponentName.unflattenFromString(
            "com.google.android.gms/.auth.uiflows.minutemaid.MinuteMaidActivity");

    private static final boolean DEBUG = false;

    private static final Map<String, Object> propsToChangeGeneric;
    private static final Map<String, Object> propsToChangePixel5;
    private static final Map<String, Object> propsToChangePixel7Pro;
    private static final Map<String, Object> propsToChangePixelXL;
    private static final Map<String, Object> propsToChangeROG6;
    private static final Map<String, Object> propsToChangeXP5;
    private static final Map<String, Object> propsToChangeOP8P;
    private static final Map<String, Object> propsToChangeOP9P;
    private static final Map<String, Object> propsToChange11T;
    private static final Map<String, Object> propsToChangeMI13P;
    private static final Map<String, Object> propsToChangeF4;
    private static final Map<String, Object> propsToChangeK30U;
    private static final Map<String, ArrayList<String>> propsToKeep;

    // Packages to Spoof as Pixel 7 Pro
    private static final String[] packagesToChangePixel7Pro = {
            "com.google.android.apps.privacy.wildlife",
            "com.google.android.apps.wallpaper",
            "com.google.android.apps.wallpaper.pixel",
            "com.google.android.inputmethod.latin"
    };

    // Packages to Spoof as Pixel XL
    private static final String[] packagesToChangePixelXL = {
            "com.samsung.accessory",
            "com.samsung.accessory.fridaymgr",
            "com.samsung.accessory.berrymgr",
            "com.samsung.accessory.neobeanmgr",
            "com.samsung.android.app.watchmanager",
            "com.samsung.android.geargplugin",
            "com.samsung.android.gearnplugin",
            "com.samsung.android.modenplugin",
            "com.samsung.android.neatplugin",
            "com.samsung.android.waterplugin"
    };

    // Packages to Spoof as Pixel 7 Pro
    private static final String[] extraPackagesToChange = {
            "com.android.chrome",
            "com.android.vending",
            "com.breel.wallpapers20",
            "com.nhs.online.nhsonline",
            "com.netflix.mediaclient"
    };

    // Packages to Keep with original device
    private static final String[] packagesToKeep = {
            "com.google.android.MTCL83",
            "com.google.android.UltraCVM",
            "com.google.android.apps.cameralite",
            "com.google.android.dialer",
            "com.google.android.euicc",
            "com.google.ar.core",
            "com.google.android.youtube",
            "com.google.android.apps.youtube.kids",
            "com.google.android.apps.youtube.music",
            "com.google.android.apps.recorder",
            "com.google.android.apps.wearables.maestro.companion",
            "com.google.android.apps.subscriptions.red",
            "com.google.android.apps.tachyon",
            "com.google.android.apps.tycho"
    };

    // Packages to Spoof as ROG Phone 6
    private static final String[] packagesToChangeROG6 = {
            "com.activision.callofduty.shooter",
            "com.ea.gp.fifamobile",
            "com.gameloft.android.ANMP.GloftA9HM",
            "com.madfingergames.legends",
            "com.mobile.legends",
            "com.pearlabyss.blackdesertm",
            "com.pearlabyss.blackdesertm.gl"
    };

    // Packages to Spoof as Xperia 5
    private static final String[] packagesToChangeXP5 = {
            "com.garena.game.codm",
            "com.tencent.tmgp.kr.codm",
            "com.vng.codmvn"
    };

    // Packages to Spoof as OnePlus 8 Pro
    private static final String[] packagesToChangeOP8P = {
            "com.netease.lztgglobal",
            "com.pubg.krmobile",
            "com.rekoo.pubgm",
            "com.riotgames.league.wildrift",
            "com.riotgames.league.wildrifttw",
            "com.riotgames.league.wildriftvn",
            "com.tencent.ig",
            "com.tencent.tmgp.pubgmhd",
            "com.vng.pubgmobile"
    };

    // Packages to Spoof as OnePlus 9 Pro
    private static final String[] packagesToChangeOP9P = {
            "com.epicgames.fortnite",
            "com.epicgames.portal",
            "com.tencent.lolm"
    };

    // Packages to Spoof as Mi 11T
    private static final String[] packagesToChange11T = {
            "com.ea.gp.apexlegendsmobilefps",
            "com.levelinfinite.hotta.gp",
            "com.supercell.clashofclans",
            "com.vng.mlbbvn"
    };

    // Packages to Spoof as Xiaomi 13 Pro
    private static final String[] packagesToChangeMI13P = {
            "com.levelinfinite.sgameGlobal",
            "com.tencent.tmgp.sgame"
    };

    // Packages to Spoof as POCO F4
    private static final String[] packagesToChangeF4 = {
            "com.dts.freefiremax",
            "com.dts.freefireth"
    };

    // Packages to Spoof as Redmi K30 Ultra
    private static final String[] packagesToChangeK30U = {
            "com.pubg.imobile"
    };

    // Codenames for currently supported Pixels by Google
    private static final String[] pixelCodenames = {
            "lynx",
            "cheetah",
            "panther",
            "bluejay",
            "oriole",
            "raven",
            "barbet",
            "redfin",
            "bramble",
            "sunfish"
    };

    private static volatile String sProcessName;
    private static volatile boolean sIsGms, sIsFinsky, sIsPhotos;

    static {
        propsToKeep = new HashMap<>();
        propsToKeep.put("com.google.android.settings.intelligence", new ArrayList<>(Collections.singletonList("FINGERPRINT")));
        propsToChangeGeneric = new HashMap<>();
        propsToChangeGeneric.put("TYPE", "user");
        propsToChangeGeneric.put("TAGS", "release-keys");
        propsToChangePixel7Pro = new HashMap<>();
        propsToChangePixel7Pro.put("BRAND", "google");
        propsToChangePixel7Pro.put("MANUFACTURER", "Google");
        propsToChangePixel7Pro.put("DEVICE", "cheetah");
        propsToChangePixel7Pro.put("PRODUCT", "cheetah");
        propsToChangePixel7Pro.put("MODEL", "Pixel 7 Pro");
        propsToChangePixel7Pro.put("FINGERPRINT", "google/cheetah/cheetah:13/TQ2A.230505.002/9891397:user/release-keys");
        propsToChangePixel5 = new HashMap<>();
        propsToChangePixel5.put("BRAND", "google");
        propsToChangePixel5.put("MANUFACTURER", "Google");
        propsToChangePixel5.put("DEVICE", "redfin");
        propsToChangePixel5.put("PRODUCT", "redfin");
        propsToChangePixel5.put("MODEL", "Pixel 5");
        propsToChangePixel5.put("FINGERPRINT", "google/redfin/redfin:13/TQ2A.230505.002/9891397:user/release-keys");
        propsToChangePixelXL = new HashMap<>();
        propsToChangePixelXL.put("BRAND", "google");
        propsToChangePixelXL.put("MANUFACTURER", "Google");
        propsToChangePixelXL.put("DEVICE", "marlin");
        propsToChangePixelXL.put("PRODUCT", "marlin");
        propsToChangePixelXL.put("MODEL", "Pixel XL");
        propsToChangePixelXL.put("FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
        propsToChangeROG6 = new HashMap<>();
        propsToChangeROG6.put("BRAND", "asus");
        propsToChangeROG6.put("MANUFACTURER", "asus");
        propsToChangeROG6.put("DEVICE", "AI2201");
        propsToChangeROG6.put("MODEL", "ASUS_AI2201");
        propsToChangeXP5 = new HashMap<>();
        propsToChangeXP5.put("MODEL", "SO-52A");
        propsToChangeXP5.put("MANUFACTURER", "Sony");
        propsToChangeOP8P = new HashMap<>();
        propsToChangeOP8P.put("MODEL", "IN2020");
        propsToChangeOP8P.put("MANUFACTURER", "OnePlus");
        propsToChangeOP9P = new HashMap<>();
        propsToChangeOP9P.put("MODEL", "LE2123");
        propsToChangeOP9P.put("MANUFACTURER", "OnePlus");
        propsToChange11T = new HashMap<>();
        propsToChange11T.put("MODEL", "21081111RG");
        propsToChange11T.put("MANUFACTURER", "Xiaomi");
        propsToChangeMI13P = new HashMap<>();
        propsToChangeMI13P.put("BRAND", "Xiaomi");
        propsToChangeMI13P.put("MANUFACTURER", "Xiaomi");
        propsToChangeMI13P.put("MODEL", "2210132C");
        propsToChangeF4 = new HashMap<>();
        propsToChangeF4.put("MODEL", "22021211RG");
        propsToChangeF4.put("MANUFACTURER", "Xiaomi");
        propsToChangeK30U = new HashMap<>();
        propsToChangeK30U.put("MODEL", "M2006J10C");
        propsToChangeK30U.put("MANUFACTURER", "Xiaomi");
    }

    public static void setProps(String packageName) {
        propsToChangeGeneric.forEach((k, v) -> setPropValue(k, v));

        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        if (packageName.startsWith("com.google.")
                || Arrays.asList(extraPackagesToChange).contains(packageName)) {

            if (Arrays.asList(packagesToKeep).contains(packageName) ||
                    packageName.startsWith("com.google.android.GoogleCamera")) {
                return;
            }

            Map<String, Object> propsToChange = new HashMap<>();
            boolean isPixelDevice = Arrays.asList(pixelCodenames).contains(SystemProperties.get(DEVICE));

            if (packageName.equals("com.google.android.apps.photos")) {
                if (SystemProperties.getBoolean("persist.sys.pixelprops.gphotos", true)) {
                    propsToChange.putAll(propsToChangePixelXL);
                } else {
                    if (isPixelDevice) return;
                    propsToChange.putAll(propsToChangePixel5);
                }
            } else if (packageName.equals("com.netflix.mediaclient") && 
                        !SystemProperties.getBoolean("persist.sys.pixelprops.netflix", false)) {
                    if (DEBUG) Log.d(TAG, "Netflix spoofing disabled by system prop");
                    return;
            } else if (isPixelDevice) {
                return;
            } else if (packageName.equals("com.android.vending")) {
                sIsFinsky = true;
                return;
            } else {
                if (Arrays.asList(packagesToChangePixel7Pro).contains(packageName)) {
                    propsToChange.putAll(propsToChangePixel7Pro);
                } else if (Arrays.asList(packagesToChangePixelXL).contains(packageName)) {
                    propsToChange.putAll(propsToChangePixelXL);
                } else {
                    propsToChange.putAll(propsToChangePixel5);
                }
            }

            if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
            for (Map.Entry<String, Object> prop : propsToChange.entrySet()) {
                String key = prop.getKey();
                Object value = prop.getValue();
                if (propsToKeep.containsKey(packageName) && propsToKeep.get(packageName).contains(key)) {
                    if (DEBUG) Log.d(TAG, "Not defining " + key + " prop for: " + packageName);
                    continue;
                }
                if (DEBUG) Log.d(TAG, "Defining " + key + " prop for: " + packageName);
                setPropValue(key, value);
            }
            if (packageName.equals(PACKAGE_GMS)) {
                final String processName = Application.getProcessName();
                 sProcessName = processName;
                if (processName.equals("com.google.android.gms.unstable")) {
                    sIsGms = true;
                    spoofBuildGms();
                }
                return;
            }
            // Set proper indexing fingerprint
            if (packageName.equals("com.google.android.settings.intelligence")) {
                setPropValue("FINGERPRINT", Build.VERSION.INCREMENTAL);
            }
        } else {

            if (!SystemProperties.getBoolean("persist.sys.pixelprops.games", false))
                return;

            if (Arrays.asList(packagesToChangeROG6).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeROG6.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeXP5).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeXP5.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeOP8P).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeOP8P.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeOP9P).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeOP9P.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChange11T).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChange11T.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeMI13P).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeMI13P.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeF4).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeF4.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            } else if (Arrays.asList(packagesToChangeK30U).contains(packageName)) {
                if (DEBUG) Log.d(TAG, "Defining props for: " + packageName);
                for (Map.Entry<String, Object> prop : propsToChangeK30U.entrySet()) {
                    String key = prop.getKey();
                    Object value = prop.getValue();
                    setPropValue(key, value);
                }
            }
        }
    }

    private static void setPropValue(String key, Object value) {
        try {
            if (DEBUG) Log.d(TAG, "Defining prop " + key + " to " + value.toString());
            Field field = Build.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static void setBuildField(String key, String value) {
        try {
            // Unlock
            Field field = Build.class.getDeclaredField(key);
            field.setAccessible(true);

            // Edit
            field.set(null, value);

            // Lock
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to spoof Build." + key, e);
        }
    }

    private static void setVersionField(String key, Integer value) {
        try {
            // Unlock
            Field field = Build.VERSION.class.getDeclaredField(key);
            field.setAccessible(true);

            // Edit
            field.set(null, value);

            // Lock
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to spoof Build." + key, e);
        }
    }

    private static void spoofBuildGms() {
       final boolean was = isGmsAddAccountActivityOnTop();
        final TaskStackListener taskStackListener = new TaskStackListener() {
            @Override
            public void onTaskStackChanged() {
                final boolean is = isGmsAddAccountActivityOnTop();
                if (is ^ was) {
                    dlog("GmsAddAccountActivityOnTop is:" + is + " was:" + was +
                            ", killing myself!"); // process will restart automatically later
                    Process.killProcess(Process.myPid());
                }
            }
        };
        if (!was) {
            dlog("Spoofing build for GMS");
        // Alter model name and fingerprint to avoid hardware attestation enforcement
        setBuildField("FINGERPRINT", "google/marlin/marlin:7.1.2/NJH47F/4146041:user/release-keys");
        setBuildField("PRODUCT", "marlin");
        setBuildField("DEVICE", "marlin");
        setBuildField("MODEL", "Pixel XL");
        setVersionField("DEVICE_INITIAL_SDK_INT", Build.VERSION_CODES.N_MR1);
        } else {
            dlog("Skip spoofing build for GMS, because GmsAddAccountActivityOnTop");
        }
        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskStackListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register task stack listener!", e);
        }
    }

    private static boolean isGmsAddAccountActivityOnTop() {
        try {
            final ActivityTaskManager.RootTaskInfo focusedTask =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return focusedTask != null && focusedTask.topActivity != null
                    && focusedTask.topActivity.equals(GMS_ADD_ACCOUNT_ACTIVITY);
        } catch (Exception e) {
            Log.e(TAG, "Unable to get top activity!", e);
        }
        return false;
    }

    public static boolean shouldBypassTaskPermission(Context context) {
        // GMS doesn't have MANAGE_ACTIVITY_TASKS permission
        final int callingUid = Binder.getCallingUid();
        final int gmsUid;
        try {
            gmsUid = context.getPackageManager().getApplicationInfo(PACKAGE_GMS, 0).uid;
            dlog("shouldBypassTaskPermission: gmsUid:" + gmsUid + " callingUid:" + callingUid);
        } catch (Exception e) {
            Log.e(TAG, "shouldBypassTaskPermission: unable to get gms uid", e);
            return false;
        }
        return gmsUid == callingUid;
    }

    private static boolean isCallerSafetyNet() {
        return sIsGms && Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(elem -> elem.getClassName().contains("DroidGuard"));
    }

    public static void onEngineGetCertificateChain() {
        // Check stack for SafetyNet or Play Integrity
        if (isCallerSafetyNet() || sIsFinsky) {
            Log.i(TAG, "Blocked key attestation sIsGms=" + sIsGms + " sIsFinsky=" + sIsFinsky);
            throw new UnsupportedOperationException();
        }
    }

    public static void dlog(String msg) {
      if (DEBUG) Log.d(TAG, msg);
        if (DEBUG) Log.d(TAG, "[" + sProcessName + "] " + msg);
    }

}

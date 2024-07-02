/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.CompatScaleProvider.COMPAT_SCALE_MODE_SYSTEM_FIRST;
import static com.android.server.wm.CompatScaleProvider.COMPAT_SCALE_MODE_SYSTEM_LAST;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.Overridable;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.CompatibilityInfo.CompatScale;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.Xml;

import com.android.internal.protolog.ProtoLog;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class CompatModePackages {
    /**
     * <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> is the gatekeeper of all per-app buffer
     * inverse downscale changes. Enabling this change will allow the following scaling factors:
     * <a href="#DOWNSCALE_90">DOWNSCALE_90</a>
     * <a href="#DOWNSCALE_85">DOWNSCALE_85</a>
     * <a href="#DOWNSCALE_80">DOWNSCALE_80</a>
     * <a href="#DOWNSCALE_75">DOWNSCALE_75</a>
     * <a href="#DOWNSCALE_70">DOWNSCALE_70</a>
     * <a href="#DOWNSCALE_65">DOWNSCALE_65</a>
     * <a href="#DOWNSCALE_60">DOWNSCALE_60</a>
     * <a href="#DOWNSCALE_55">DOWNSCALE_55</a>
     * <a href="#DOWNSCALE_50">DOWNSCALE_50</a>
     * <a href="#DOWNSCALE_45">DOWNSCALE_45</a>
     * <a href="#DOWNSCALE_40">DOWNSCALE_40</a>
     * <a href="#DOWNSCALE_35">DOWNSCALE_35</a>
     * <a href="#DOWNSCALE_30">DOWNSCALE_30</a>
     *
     * If <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> is enabled for an app package, then
     * the app will be forcibly resized to the lowest enabled scaling factor e.g. 1/0.8 if both
     * 1/0.8 and 1/0.7 (* 100%) were enabled.
     *
     * When both <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a>
     * and <a href="#DOWNSCALED">DOWNSCALED</a> are enabled, then
     * <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> takes precedence.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALED_INVERSE = 273564678L; // This is a Bug ID.

    /**
     * <a href="#DOWNSCALED">DOWNSCALED</a> is the gatekeeper of all per-app buffer downscaling
     * changes. Enabling this change will allow the following scaling factors:
     * <a href="#DOWNSCALE_90">DOWNSCALE_90</a>
     * <a href="#DOWNSCALE_85">DOWNSCALE_85</a>
     * <a href="#DOWNSCALE_80">DOWNSCALE_80</a>
     * <a href="#DOWNSCALE_75">DOWNSCALE_75</a>
     * <a href="#DOWNSCALE_70">DOWNSCALE_70</a>
     * <a href="#DOWNSCALE_65">DOWNSCALE_65</a>
     * <a href="#DOWNSCALE_60">DOWNSCALE_60</a>
     * <a href="#DOWNSCALE_55">DOWNSCALE_55</a>
     * <a href="#DOWNSCALE_50">DOWNSCALE_50</a>
     * <a href="#DOWNSCALE_45">DOWNSCALE_45</a>
     * <a href="#DOWNSCALE_40">DOWNSCALE_40</a>
     * <a href="#DOWNSCALE_35">DOWNSCALE_35</a>
     * <a href="#DOWNSCALE_30">DOWNSCALE_30</a>
     *
     * If <a href="#DOWNSCALED">DOWNSCALED</a> is enabled for an app package, then the app will be
     * forcibly resized to the highest enabled scaling factor e.g. 80% if both 80% and 70% were
     * enabled.
     *
     * When both <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a>
     * and <a href="#DOWNSCALED">DOWNSCALED</a> are enabled, then
     * <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> takes precedence.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALED = 168419799L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_90">DOWNSCALE_90</a> for a package will force the app to assume it's
     * running on a display with 90% the vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 111.11% the vertical and horizontal resolution of
     * the real display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_90 = 182811243L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_85">DOWNSCALE_85</a> for a package will force the app to assume it's
     * running on a display with 85% the vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 117.65% the vertical and horizontal resolution of the
     * real display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_85 = 189969734L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_80">DOWNSCALE_80</a> for a package will force the app to assume it's
     * running on a display with 80% the vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 125% the vertical and horizontal resolution of the real
     * display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_80 = 176926753L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_75">DOWNSCALE_75</a> for a package will force the app to assume it's
     * running on a display with 75% the vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 133.33% the vertical and horizontal resolution of the
     * real display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_75 = 189969779L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_70">DOWNSCALE_70</a> for a package will force the app to assume it's
     * running on a display with 70% the vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 142.86% the vertical and horizontal resolution of the
     * real display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_70 = 176926829L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_65">DOWNSCALE_65</a> for a package will force the app to assume it's
     * running on a display with 65% the vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 153.85% the vertical and horizontal resolution of the
     * real display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_65 = 189969744L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_60">DOWNSCALE_60</a> for a package will force the app to assume it's
     * running on a display with 60% the vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 166.67% the vertical and horizontal resolution of the
     * real display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_60 = 176926771L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_55">DOWNSCALE_55</a> for a package will force the app to assume it's
     * running on a display with 55% the vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 181.82% the vertical and horizontal resolution of the
     * real display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_55 = 189970036L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_50">DOWNSCALE_50</a> for a package will force the app to assume it's
     * running on a display with 50% vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 200% the vertical and horizontal resolution of the real
     * display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_50 = 176926741L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_45">DOWNSCALE_45</a> for a package will force the app to assume it's
     * running on a display with 45% the vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 222.22% the vertical and horizontal resolution of the
     * real display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_45 = 189969782L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_40">DOWNSCALE_40</a> for a package will force the app to assume it's
     * running on a display with 40% the vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 250% the vertical and horizontal resolution of the real
     * display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_40 = 189970038L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_35">DOWNSCALE_35</a> for a package will force the app to assume it's
     * running on a display with 35% the vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 285.71% the vertical and horizontal resolution of the
     * real display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_35 = 189969749L;

    /**
     * With <a href="#DOWNSCALED">DOWNSCALED</a> enabled, subsequently enabling change-id
     * <a href="#DOWNSCALE_30">DOWNSCALE_30</a> for a package will force the app to assume it's
     * running on a display with 30% the vertical and horizontal resolution of the real display.
     *
     * With <a href="#DOWNSCALED_INVERSE">DOWNSCALED_INVERSE</a> enabled will force the app to
     * assume it's running on a display with 333.33% the vertical and horizontal resolution of the
     * real display
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_30 = 189970040L;

    /**
     * On Android TV applications that target pre-S are not expecting to receive a Window larger
     * than 1080p, so if needed we are downscaling their Windows to 1080p.
     * However, applications that target S and greater release version are expected to be able to
     * handle any Window size, so we should not downscale their Windows.
     */
    @ChangeId
    @Overridable
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long DO_NOT_DOWNSCALE_TO_1080P_ON_TV = 157629738L; // This is a Bug ID.

    private static final int MSG_WRITE = 300;

    private static final String TAG = TAG_WITH_CLASS_NAME ? "CompatModePackages" : TAG_ATM;

    // Compatibility state: no longer ask user to select the mode.
    private static final int COMPAT_FLAG_DONT_ASK = 1 << 0;

    // Compatibility state: compatibility mode is enabled.
    private static final int COMPAT_FLAG_ENABLED = 1 << 1;

    private final class CompatHandler extends Handler {
        public CompatHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WRITE:
                    saveCompatModes();
                    break;
            }
        }
    }

    private final ActivityTaskManagerService mService;
    private final AtomicFile mFile;
    private final HashMap<String, Integer> mPackages = new HashMap<>();
    private final SparseBooleanArray mLegacyScreenCompatPackages = new SparseBooleanArray();
    private final CompatHandler mHandler;

    private final SparseArray<CompatScaleProvider> mProviders = new SparseArray<>();

    public CompatModePackages(ActivityTaskManagerService service, File systemDir, Handler handler) {
        mService = service;
        mFile = new AtomicFile(new File(systemDir, "packages-compat.xml"), "compat-mode");
        mHandler = new CompatHandler(handler.getLooper());

        FileInputStream fis = null;
        try {
            fis = mFile.openRead();
            TypedXmlPullParser parser = Xml.resolvePullParser(fis);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }
            if (eventType == XmlPullParser.END_DOCUMENT) {
                return;
            }

            String tagName = parser.getName();
            if ("compat-packages".equals(tagName)) {
                eventType = parser.next();
                do {
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = parser.getName();
                        if (parser.getDepth() == 2) {
                            if ("pkg".equals(tagName)) {
                                String pkg = parser.getAttributeValue(null, "name");
                                if (pkg != null) {
                                    int modeInt = parser.getAttributeInt(null, "mode", 0);
                                    mPackages.put(pkg, modeInt);
                                }
                            }
                        }
                    }
                    eventType = parser.next();
                } while (eventType != XmlPullParser.END_DOCUMENT);
            }
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Error reading compat-packages", e);
        } catch (java.io.IOException e) {
            if (fis != null) Slog.w(TAG, "Error reading compat-packages", e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (java.io.IOException e1) {
                }
            }
        }
    }

    public HashMap<String, Integer> getPackages() {
        return mPackages;
    }

    private int getPackageFlags(String packageName) {
        Integer flags = mPackages.get(packageName);
        return flags != null ? flags : 0;
    }

    public void handlePackageDataClearedLocked(String packageName) {
        // User has explicitly asked to clear all associated data.
        removePackage(packageName);
    }

    public void handlePackageUninstalledLocked(String packageName) {
        // Clear settings when app is uninstalled since this is an explicit
        // signal from the user to remove the app and all associated data.
        removePackage(packageName);
    }

    private void removePackage(String packageName) {
        if (mPackages.containsKey(packageName)) {
            mPackages.remove(packageName);
            scheduleWrite();
        }
        mLegacyScreenCompatPackages.delete(packageName.hashCode());
    }

    public void handlePackageAddedLocked(String packageName, boolean updated) {
        ApplicationInfo ai = null;
        try {
            ai = AppGlobals.getPackageManager().getApplicationInfo(packageName, 0, 0);
        } catch (RemoteException e) {
        }
        if (ai == null) {
            return;
        }
        CompatibilityInfo ci = compatibilityInfoForPackageLocked(ai);
        final boolean mayCompat = !ci.alwaysSupportsScreen()
                && !ci.neverSupportsScreen();

        if (updated) {
            // Update -- if the app no longer can run in compat mode, clear
            // any current settings for it.
            if (!mayCompat && mPackages.containsKey(packageName)) {
                mPackages.remove(packageName);
                scheduleWrite();
            }
        }
    }

    private void scheduleWrite() {
        mHandler.removeMessages(MSG_WRITE);
        Message msg = mHandler.obtainMessage(MSG_WRITE);
        mHandler.sendMessageDelayed(msg, 10000);
    }

    /**
     * Returns {@code true} if the windows belonging to the package should be scaled with
     * {@link DisplayContent#mCompatibleScreenScale}.
     */
    boolean useLegacyScreenCompatMode(String packageName) {
        if (mLegacyScreenCompatPackages.size() == 0) {
            return false;
        }
        return mLegacyScreenCompatPackages.get(packageName.hashCode());
    }

    public CompatibilityInfo compatibilityInfoForPackageLocked(ApplicationInfo ai) {
        final boolean forceCompat = getPackageCompatModeEnabledLocked(ai);
        final CompatScale compatScale = getCompatScaleFromProvider(ai.packageName, ai.uid);
        final float appScale = compatScale != null
                ? compatScale.mScaleFactor
                : getCompatScale(ai.packageName, ai.uid, /* checkProvider= */ false);
        final float densityScale = compatScale != null ? compatScale.mDensityScaleFactor : appScale;
        final Configuration config = mService.getGlobalConfiguration();
        final CompatibilityInfo info = new CompatibilityInfo(ai, config.screenLayout,
                config.smallestScreenWidthDp, forceCompat, appScale, densityScale);
        // Ignore invalid info which may be a placeholder of isolated process.
        if (ai.flags != 0 && ai.sourceDir != null) {
            if (!info.supportsScreen() && !"android".equals(ai.packageName)) {
                Slog.i(TAG, "Use legacy screen compat mode: " + ai.packageName);
                mLegacyScreenCompatPackages.put(ai.packageName.hashCode(), true);
            } else if (mLegacyScreenCompatPackages.size() > 0) {
                mLegacyScreenCompatPackages.delete(ai.packageName.hashCode());
            }
        }
        return info;
    }

    float getCompatScale(String packageName, int uid) {
        return getCompatScale(packageName, uid, /* checkProvider= */ true);
    }

    private CompatScale getCompatScaleFromProvider(String packageName, int uid) {
        for (int i = 0; i < mProviders.size(); i++) {
            final CompatScaleProvider provider = mProviders.valueAt(i);
            final CompatScale compatScale = provider.getCompatScale(packageName, uid);
            if (compatScale != null) {
                return compatScale;
            }
        }
        return null;
    }

    private float getCompatScale(String packageName, int uid, boolean checkProviders) {
        if (checkProviders) {
            final CompatScale compatScale = getCompatScaleFromProvider(packageName, uid);
            if (compatScale != null) {
                return compatScale.mScaleFactor;
            }
        }
        final UserHandle userHandle = UserHandle.getUserHandleForUid(uid);

        final boolean isDownscaledEnabled = CompatChanges.isChangeEnabled(
                DOWNSCALED, packageName, userHandle);
        final boolean isDownscaledInverseEnabled = CompatChanges.isChangeEnabled(
                DOWNSCALED_INVERSE, packageName, userHandle);
        if (isDownscaledEnabled || isDownscaledInverseEnabled) {
            final float scalingFactor = getScalingFactor(packageName, userHandle);
            if (scalingFactor != 1f) {
                // For Upscaling the returned factor must be scalingFactor
                // For Downscaling the returned factor must be 1f / scalingFactor
                return isDownscaledInverseEnabled ? scalingFactor : 1f / scalingFactor;
            }
        }

        if (mService.mHasLeanbackFeature) {
            final Configuration config = mService.getGlobalConfiguration();
            final float density = config.densityDpi / (float) DisplayMetrics.DENSITY_DEFAULT;
            final int smallestScreenWidthPx = (int) (config.smallestScreenWidthDp * density + .5f);
            if (smallestScreenWidthPx > 1080 && !CompatChanges.isChangeEnabled(
                    DO_NOT_DOWNSCALE_TO_1080P_ON_TV, packageName, userHandle)) {
                return smallestScreenWidthPx / 1080f;
            }
        }

        return 1f;
    }

    void registerCompatScaleProvider(@CompatScaleProvider.CompatScaleModeOrderId int id,
            @NonNull CompatScaleProvider provider) {
        synchronized (mService.mGlobalLock) {
            if (mProviders.contains(id)) {
                throw new IllegalArgumentException("Duplicate id provided: " + id);
            }
            if (provider == null) {
                throw new IllegalArgumentException("The passed CompatScaleProvider "
                        + "can not be null");
            }
            if (!CompatScaleProvider.isValidOrderId(id)) {
                throw new IllegalArgumentException(
                        "Provided id " + id + " is not in range of valid ids for system "
                                + "services [" + COMPAT_SCALE_MODE_SYSTEM_FIRST + ","
                                + COMPAT_SCALE_MODE_SYSTEM_LAST + "]");
            }
            mProviders.put(id, provider);
        }
    }

    void unregisterCompatScaleProvider(@CompatScaleProvider.CompatScaleModeOrderId int id) {
        synchronized (mService.mGlobalLock) {
            if (!mProviders.contains(id)) {
                throw new IllegalArgumentException(
                        "CompatScaleProvider with id (" + id + ") is not registered");
            }
            mProviders.remove(id);
        }
    }

    private static float getScalingFactor(String packageName, UserHandle userHandle) {
        if (CompatChanges.isChangeEnabled(DOWNSCALE_90, packageName, userHandle)) {
            return 0.9f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_85, packageName, userHandle)) {
            return 0.85f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_80, packageName, userHandle)) {
            return 0.8f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_75, packageName, userHandle)) {
            return 0.75f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_70, packageName, userHandle)) {
            return 0.7f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_65, packageName, userHandle)) {
            return 0.65f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_60, packageName, userHandle)) {
            return 0.6f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_55, packageName, userHandle)) {
            return 0.55f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_50, packageName, userHandle)) {
            return 0.5f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_45, packageName, userHandle)) {
            return 0.45f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_40, packageName, userHandle)) {
            return 0.4f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_35, packageName, userHandle)) {
            return 0.35f;
        }
        if (CompatChanges.isChangeEnabled(DOWNSCALE_30, packageName, userHandle)) {
            return 0.3f;
        }
        return 1f;
    }

    public int computeCompatModeLocked(ApplicationInfo ai) {
        final CompatibilityInfo info = compatibilityInfoForPackageLocked(ai);
        if (info.alwaysSupportsScreen()) {
            return ActivityManager.COMPAT_MODE_NEVER;
        }
        if (info.neverSupportsScreen()) {
            return ActivityManager.COMPAT_MODE_ALWAYS;
        }
        return getPackageCompatModeEnabledLocked(ai) ? ActivityManager.COMPAT_MODE_ENABLED
                : ActivityManager.COMPAT_MODE_DISABLED;
    }

    public boolean getPackageAskCompatModeLocked(String packageName) {
        return (getPackageFlags(packageName)&COMPAT_FLAG_DONT_ASK) == 0;
    }

    public void setPackageAskCompatModeLocked(String packageName, boolean ask) {
        setPackageFlagLocked(packageName, COMPAT_FLAG_DONT_ASK, ask);
    }

    private boolean getPackageCompatModeEnabledLocked(ApplicationInfo ai) {
        return (getPackageFlags(ai.packageName) & COMPAT_FLAG_ENABLED) != 0;
    }

    private void setPackageFlagLocked(String packageName, int flag, boolean set) {
        final int curFlags = getPackageFlags(packageName);
        final int newFlags = set ? (curFlags & ~flag) : (curFlags | flag);
        if (curFlags != newFlags) {
            if (newFlags != 0) {
                mPackages.put(packageName, newFlags);
            } else {
                mPackages.remove(packageName);
            }
            scheduleWrite();
        }
    }

    public int getPackageScreenCompatModeLocked(String packageName) {
        ApplicationInfo ai = null;
        try {
            ai = AppGlobals.getPackageManager().getApplicationInfo(packageName, 0, 0);
        } catch (RemoteException e) {
        }
        if (ai == null) {
            return ActivityManager.COMPAT_MODE_UNKNOWN;
        }
        return computeCompatModeLocked(ai);
    }

    public void setPackageScreenCompatModeLocked(String packageName, int mode) {
        ApplicationInfo ai = null;
        try {
            ai = AppGlobals.getPackageManager().getApplicationInfo(packageName, 0, 0);
        } catch (RemoteException e) {
        }
        if (ai == null) {
            Slog.w(TAG, "setPackageScreenCompatMode failed: unknown package " + packageName);
            return;
        }
        setPackageScreenCompatModeLocked(ai, mode);
    }

    void setPackageScreenCompatModeLocked(ApplicationInfo ai, int mode) {
        final String packageName = ai.packageName;

        int curFlags = getPackageFlags(packageName);

        boolean enable;
        switch (mode) {
            case ActivityManager.COMPAT_MODE_DISABLED:
                enable = false;
                break;
            case ActivityManager.COMPAT_MODE_ENABLED:
                enable = true;
                break;
            case ActivityManager.COMPAT_MODE_TOGGLE:
                enable = (curFlags&COMPAT_FLAG_ENABLED) == 0;
                break;
            default:
                Slog.w(TAG, "Unknown screen compat mode req #" + mode + "; ignoring");
                return;
        }

        int newFlags = curFlags;
        if (enable) {
            newFlags |= COMPAT_FLAG_ENABLED;
        } else {
            newFlags &= ~COMPAT_FLAG_ENABLED;
        }

        CompatibilityInfo ci = compatibilityInfoForPackageLocked(ai);
        if (ci.alwaysSupportsScreen()) {
            Slog.w(TAG, "Ignoring compat mode change of " + packageName
                    + "; compatibility never needed");
            newFlags = 0;
        }
        if (ci.neverSupportsScreen()) {
            Slog.w(TAG, "Ignoring compat mode change of " + packageName
                    + "; compatibility always needed");
            newFlags = 0;
        }

        if (newFlags != curFlags) {
            if (newFlags != 0) {
                mPackages.put(packageName, newFlags);
            } else {
                mPackages.remove(packageName);
            }

            // Need to get compatibility info in new state.
            ci = compatibilityInfoForPackageLocked(ai);

            scheduleWrite();

            final ArrayList<WindowProcessController> restartedApps = new ArrayList<>();
            mService.mRootWindowContainer.forAllWindows(w -> {
                final ActivityRecord ar = w.mActivityRecord;
                if (ar != null) {
                    if (ar.packageName.equals(packageName) && !restartedApps.contains(ar.app)) {
                        ar.restartProcessIfVisible();
                        restartedApps.add(ar.app);
                    }
                } else if (w.getProcess().mInfo.packageName.equals(packageName)) {
                    w.updateGlobalScale();
                }
            }, true /* traverseTopToBottom */);
            // Tell all processes that loaded this package about the change.
            SparseArray<WindowProcessController> pidMap = mService.mProcessMap.getPidMap();
            for (int i = pidMap.size() - 1; i >= 0; i--) {
                final WindowProcessController app = pidMap.valueAt(i);
                if (!app.containsPackage(packageName) || restartedApps.contains(app)) {
                    continue;
                }
                try {
                    if (app.hasThread()) {
                        ProtoLog.v(WM_DEBUG_CONFIGURATION, "Sending to proc %s "
                                + "new compat %s", app.mName, ci);
                        app.getThread().updatePackageCompatibilityInfo(packageName, ci);
                    }
                } catch (Exception e) {
                }
            }
        }
    }

    private void saveCompatModes() {
        HashMap<String, Integer> pkgs;
        synchronized (mService.mGlobalLock) {
            pkgs = new HashMap<>(mPackages);
        }

        FileOutputStream fos = null;

        try {
            fos = mFile.startWrite();
            TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            out.startTag(null, "compat-packages");

            final IPackageManager pm = AppGlobals.getPackageManager();
            final Iterator<Map.Entry<String, Integer>> it = pkgs.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Integer> entry = it.next();
                String pkg = entry.getKey();
                int mode = entry.getValue();
                if (mode == 0) {
                    continue;
                }
                ApplicationInfo ai = null;
                try {
                    ai = pm.getApplicationInfo(pkg, 0, 0);
                } catch (RemoteException e) {
                }
                if (ai == null) {
                    continue;
                }
                final CompatibilityInfo info = compatibilityInfoForPackageLocked(ai);
                if (info.alwaysSupportsScreen()) {
                    continue;
                }
                if (info.neverSupportsScreen()) {
                    continue;
                }
                out.startTag(null, "pkg");
                out.attribute(null, "name", pkg);
                out.attributeInt(null, "mode", mode);
                out.endTag(null, "pkg");
            }

            out.endTag(null, "compat-packages");
            out.endDocument();

            mFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Slog.w(TAG, "Error writing compat packages", e1);
            if (fos != null) {
                mFile.failWrite(fos);
            }
        }
    }
}

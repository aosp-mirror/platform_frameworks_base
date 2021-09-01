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
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskSupervisor.PRESERVE_WINDOWS;

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
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.protolog.common.ProtoLog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class CompatModePackages {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "CompatModePackages" : TAG_ATM;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;

    private final ActivityTaskManagerService mService;
    private final AtomicFile mFile;

    // Compatibility state: no longer ask user to select the mode.
    private static final int COMPAT_FLAG_DONT_ASK = 1<<0;
    // Compatibility state: compatibility mode is enabled.
    private static final int COMPAT_FLAG_ENABLED = 1<<1;

    /**
     * CompatModePackages#DOWNSCALED is the gatekeeper of all per-app buffer downscaling
     * changes.  Disabling this change will prevent the following scaling factors from working:
     * CompatModePackages#DOWNSCALE_90
     * CompatModePackages#DOWNSCALE_85
     * CompatModePackages#DOWNSCALE_80
     * CompatModePackages#DOWNSCALE_75
     * CompatModePackages#DOWNSCALE_70
     * CompatModePackages#DOWNSCALE_65
     * CompatModePackages#DOWNSCALE_60
     * CompatModePackages#DOWNSCALE_55
     * CompatModePackages#DOWNSCALE_50
     * CompatModePackages#DOWNSCALE_45
     * CompatModePackages#DOWNSCALE_40
     * CompatModePackages#DOWNSCALE_35
     * CompatModePackages#DOWNSCALE_30
     *
     * If CompatModePackages#DOWNSCALED is enabled for an app package, then the app will be forcibly
     * resized to the highest enabled scaling factor e.g. 80% if both 80% and 70% were enabled.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALED = 168419799L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_90 for a package will force the app to assume it's
     * running on a display with 90% the vertical and horizontal resolution of the real display.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_90 = 182811243L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_85 for a package will force the app to assume it's
     * running on a display with 85% the vertical and horizontal resolution of the real display.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_85 = 189969734L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_80 for a package will force the app to assume it's
     * running on a display with 80% the vertical and horizontal resolution of the real display.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_80 = 176926753L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_75 for a package will force the app to assume it's
     * running on a display with 75% the vertical and horizontal resolution of the real display.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_75 = 189969779L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_70 for a package will force the app to assume it's
     * running on a display with 70% the vertical and horizontal resolution of the real display.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_70 = 176926829L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_65 for a package will force the app to assume it's
     * running on a display with 65% the vertical and horizontal resolution of the real display.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_65 = 189969744L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_60 for a package will force the app to assume it's
     * running on a display with 60% the vertical and horizontal resolution of the real display.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_60 = 176926771L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_55 for a package will force the app to assume it's
     * running on a display with 55% the vertical and horizontal resolution of the real display.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_55 = 189970036L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_50 for a package will force the app to assume it's
     * running on a display with 50% vertical and horizontal resolution of the real display.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_50 = 176926741L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_45 for a package will force the app to assume it's
     * running on a display with 45% the vertical and horizontal resolution of the real display.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_45 = 189969782L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_40 for a package will force the app to assume it's
     * running on a display with 40% the vertical and horizontal resolution of the real display.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_40 = 189970038L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_35 for a package will force the app to assume it's
     * running on a display with 35% the vertical and horizontal resolution of the real display.
     */
    @ChangeId
    @Disabled
    @Overridable
    public static final long DOWNSCALE_35 = 189969749L;

    /**
     * With CompatModePackages#DOWNSCALED enabled, subsequently enabling change-id
     * CompatModePackages#DOWNSCALE_30 for a package will force the app to assume it's
     * running on a display with 30% the vertical and horizontal resolution of the real display.
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

    private final HashMap<String, Integer> mPackages = new HashMap<String, Integer>();

    private static final int MSG_WRITE = 300;

    private final CompatHandler mHandler;

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

    public CompatibilityInfo compatibilityInfoForPackageLocked(ApplicationInfo ai) {
        final boolean forceCompat = getPackageCompatModeEnabledLocked(ai);
        final float compatScale = getCompatScale(ai.packageName, ai.uid);
        final Configuration config = mService.getGlobalConfiguration();
        return new CompatibilityInfo(ai, config.screenLayout, config.smallestScreenWidthDp,
                forceCompat, compatScale);
    }

    float getCompatScale(String packageName, int uid) {
        final UserHandle userHandle = UserHandle.getUserHandleForUid(uid);
        if (CompatChanges.isChangeEnabled(DOWNSCALED, packageName, userHandle)) {
            if (CompatChanges.isChangeEnabled(DOWNSCALE_90, packageName, userHandle)) {
                return 1f / 0.9f;
            }
            if (CompatChanges.isChangeEnabled(DOWNSCALE_85, packageName, userHandle)) {
                return 1f / 0.85f;
            }
            if (CompatChanges.isChangeEnabled(DOWNSCALE_80, packageName, userHandle)) {
                return 1f / 0.8f;
            }
            if (CompatChanges.isChangeEnabled(DOWNSCALE_75, packageName, userHandle)) {
                return 1f / 0.75f;
            }
            if (CompatChanges.isChangeEnabled(DOWNSCALE_70, packageName, userHandle)) {
                return 1f / 0.7f;
            }
            if (CompatChanges.isChangeEnabled(DOWNSCALE_65, packageName, userHandle)) {
                return 1f / 0.65f;
            }
            if (CompatChanges.isChangeEnabled(DOWNSCALE_60, packageName, userHandle)) {
                return 1f / 0.6f;
            }
            if (CompatChanges.isChangeEnabled(DOWNSCALE_55, packageName, userHandle)) {
                return 1f / 0.55f;
            }
            if (CompatChanges.isChangeEnabled(DOWNSCALE_50, packageName, userHandle)) {
                return 1f / 0.5f;
            }
            if (CompatChanges.isChangeEnabled(DOWNSCALE_45, packageName, userHandle)) {
                return 1f / 0.45f;
            }
            if (CompatChanges.isChangeEnabled(DOWNSCALE_40, packageName, userHandle)) {
                return 1f / 0.4f;
            }
            if (CompatChanges.isChangeEnabled(DOWNSCALE_35, packageName, userHandle)) {
                return 1f / 0.35f;
            }
            if (CompatChanges.isChangeEnabled(DOWNSCALE_30, packageName, userHandle)) {
                return 1f / 0.3f;
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

            final Task rootTask = mService.getTopDisplayFocusedRootTask();
            ActivityRecord starting = rootTask.restartPackage(packageName);

            // Tell all processes that loaded this package about the change.
            SparseArray<WindowProcessController> pidMap = mService.mProcessMap.getPidMap();
            for (int i = pidMap.size() - 1; i >= 0; i--) {
                final WindowProcessController app = pidMap.valueAt(i);
                if (!app.mPkgList.contains(packageName)) {
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

            if (starting != null) {
                starting.ensureActivityConfiguration(0 /* globalChanges */,
                        false /* preserveWindow */);
                // And we need to make sure at this point that all other activities
                // are made visible with the correct configuration.
                rootTask.ensureActivitiesVisible(starting, 0, !PRESERVE_WINDOWS);
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

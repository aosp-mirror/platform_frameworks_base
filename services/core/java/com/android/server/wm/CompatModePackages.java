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

import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import com.android.internal.util.FastXmlSerializer;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

public final class CompatModePackages {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "CompatModePackages" : TAG_ATM;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;

    private final ActivityTaskManagerService mService;
    private final AtomicFile mFile;

    // Compatibility state: no longer ask user to select the mode.
    private static final int COMPAT_FLAG_DONT_ASK = 1<<0;
    // Compatibility state: compatibility mode is enabled.
    private static final int COMPAT_FLAG_ENABLED = 1<<1;

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
    };

    public CompatModePackages(ActivityTaskManagerService service, File systemDir, Handler handler) {
        mService = service;
        mFile = new AtomicFile(new File(systemDir, "packages-compat.xml"), "compat-mode");
        mHandler = new CompatHandler(handler.getLooper());

        FileInputStream fis = null;
        try {
            fis = mFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());
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
                                    String mode = parser.getAttributeValue(null, "mode");
                                    int modeInt = 0;
                                    if (mode != null) {
                                        try {
                                            modeInt = Integer.parseInt(mode);
                                        } catch (NumberFormatException e) {
                                        }
                                    }
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
        final Configuration globalConfig = mService.getGlobalConfiguration();
        CompatibilityInfo ci = new CompatibilityInfo(ai, globalConfig.screenLayout,
                globalConfig.smallestScreenWidthDp,
                (getPackageFlags(ai.packageName)&COMPAT_FLAG_ENABLED) != 0);
        //Slog.i(TAG, "*********** COMPAT FOR PKG " + ai.packageName + ": " + ci);
        return ci;
    }

    public int computeCompatModeLocked(ApplicationInfo ai) {
        final boolean enabled = (getPackageFlags(ai.packageName)&COMPAT_FLAG_ENABLED) != 0;
        final Configuration globalConfig = mService.getGlobalConfiguration();
        final CompatibilityInfo info = new CompatibilityInfo(ai, globalConfig.screenLayout,
                globalConfig.smallestScreenWidthDp, enabled);
        if (info.alwaysSupportsScreen()) {
            return ActivityManager.COMPAT_MODE_NEVER;
        }
        if (info.neverSupportsScreen()) {
            return ActivityManager.COMPAT_MODE_ALWAYS;
        }
        return enabled ? ActivityManager.COMPAT_MODE_ENABLED
                : ActivityManager.COMPAT_MODE_DISABLED;
    }

    public boolean getPackageAskCompatModeLocked(String packageName) {
        return (getPackageFlags(packageName)&COMPAT_FLAG_DONT_ASK) == 0;
    }

    public void setPackageAskCompatModeLocked(String packageName, boolean ask) {
        setPackageFlagLocked(packageName, COMPAT_FLAG_DONT_ASK, ask);
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

            final ActivityStack stack = mService.getTopDisplayFocusedStack();
            ActivityRecord starting = stack.restartPackage(packageName);

            // Tell all processes that loaded this package about the change.
            SparseArray<WindowProcessController> pidMap = mService.mProcessMap.getPidMap();
            for (int i = pidMap.size() - 1; i >= 0; i--) {
                final WindowProcessController app = pidMap.valueAt(i);
                if (!app.mPkgList.contains(packageName)) {
                    continue;
                }
                try {
                    if (app.hasThread()) {
                        if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION, "Sending to proc "
                                + app.mName + " new compat " + ci);
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
                stack.ensureActivitiesVisible(starting, 0, !PRESERVE_WINDOWS);
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
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            out.startTag(null, "compat-packages");

            final IPackageManager pm = AppGlobals.getPackageManager();
            final Configuration globalConfig = mService.getGlobalConfiguration();
            final int screenLayout = globalConfig.screenLayout;
            final int smallestScreenWidthDp = globalConfig.smallestScreenWidthDp;
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
                CompatibilityInfo info = new CompatibilityInfo(ai, screenLayout,
                        smallestScreenWidthDp, false);
                if (info.alwaysSupportsScreen()) {
                    continue;
                }
                if (info.neverSupportsScreen()) {
                    continue;
                }
                out.startTag(null, "pkg");
                out.attribute(null, "name", pkg);
                out.attribute(null, "mode", Integer.toString(mode));
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

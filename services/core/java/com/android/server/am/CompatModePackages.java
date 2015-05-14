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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.*;

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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

public final class CompatModePackages {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "CompatModePackages" : TAG_AM;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;

    private final ActivityManagerService mService;
    private final AtomicFile mFile;

    // Compatibility state: no longer ask user to select the mode.
    public static final int COMPAT_FLAG_DONT_ASK = 1<<0;
    // Compatibility state: compatibility mode is enabled.
    public static final int COMPAT_FLAG_ENABLED = 1<<1;

    private final HashMap<String, Integer> mPackages = new HashMap<String, Integer>();

    private static final int MSG_WRITE = ActivityManagerService.FIRST_COMPAT_MODE_MSG;

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

    public CompatModePackages(ActivityManagerService service, File systemDir, Handler handler) {
        mService = service;
        mFile = new AtomicFile(new File(systemDir, "packages-compat.xml"));
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
                mHandler.removeMessages(MSG_WRITE);
                Message msg = mHandler.obtainMessage(MSG_WRITE);
                mHandler.sendMessageDelayed(msg, 10000);
            }
        }
    }

    public CompatibilityInfo compatibilityInfoForPackageLocked(ApplicationInfo ai) {
        CompatibilityInfo ci = new CompatibilityInfo(ai, mService.mConfiguration.screenLayout,
                mService.mConfiguration.smallestScreenWidthDp,
                (getPackageFlags(ai.packageName)&COMPAT_FLAG_ENABLED) != 0);
        //Slog.i(TAG, "*********** COMPAT FOR PKG " + ai.packageName + ": " + ci);
        return ci;
    }

    public int computeCompatModeLocked(ApplicationInfo ai) {
        boolean enabled = (getPackageFlags(ai.packageName)&COMPAT_FLAG_ENABLED) != 0;
        CompatibilityInfo info = new CompatibilityInfo(ai,
                mService.mConfiguration.screenLayout,
                mService.mConfiguration.smallestScreenWidthDp, enabled);
        if (info.alwaysSupportsScreen()) {
            return ActivityManager.COMPAT_MODE_NEVER;
        }
        if (info.neverSupportsScreen()) {
            return ActivityManager.COMPAT_MODE_ALWAYS;
        }
        return enabled ? ActivityManager.COMPAT_MODE_ENABLED
                : ActivityManager.COMPAT_MODE_DISABLED;
    }

    public boolean getFrontActivityAskCompatModeLocked() {
        ActivityRecord r = mService.getFocusedStack().topRunningActivityLocked(null);
        if (r == null) {
            return false;
        }
        return getPackageAskCompatModeLocked(r.packageName);
    }

    public boolean getPackageAskCompatModeLocked(String packageName) {
        return (getPackageFlags(packageName)&COMPAT_FLAG_DONT_ASK) == 0;
    }

    public void setFrontActivityAskCompatModeLocked(boolean ask) {
        ActivityRecord r = mService.getFocusedStack().topRunningActivityLocked(null);
        if (r != null) {
            setPackageAskCompatModeLocked(r.packageName, ask);
        }
    }

    public void setPackageAskCompatModeLocked(String packageName, boolean ask) {
        int curFlags = getPackageFlags(packageName);
        int newFlags = ask ? (curFlags&~COMPAT_FLAG_DONT_ASK) : (curFlags|COMPAT_FLAG_DONT_ASK);
        if (curFlags != newFlags) {
            if (newFlags != 0) {
                mPackages.put(packageName, newFlags);
            } else {
                mPackages.remove(packageName);
            }
            mHandler.removeMessages(MSG_WRITE);
            Message msg = mHandler.obtainMessage(MSG_WRITE);
            mHandler.sendMessageDelayed(msg, 10000);
        }
    }

    public int getFrontActivityScreenCompatModeLocked() {
        ActivityRecord r = mService.getFocusedStack().topRunningActivityLocked(null);
        if (r == null) {
            return ActivityManager.COMPAT_MODE_UNKNOWN;
        }
        return computeCompatModeLocked(r.info.applicationInfo);
    }

    public void setFrontActivityScreenCompatModeLocked(int mode) {
        ActivityRecord r = mService.getFocusedStack().topRunningActivityLocked(null);
        if (r == null) {
            Slog.w(TAG, "setFrontActivityScreenCompatMode failed: no top activity");
            return;
        }
        setPackageScreenCompatModeLocked(r.info.applicationInfo, mode);
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

    private void setPackageScreenCompatModeLocked(ApplicationInfo ai, int mode) {
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

            mHandler.removeMessages(MSG_WRITE);
            Message msg = mHandler.obtainMessage(MSG_WRITE);
            mHandler.sendMessageDelayed(msg, 10000);

            final ActivityStack stack = mService.getFocusedStack();
            ActivityRecord starting = stack.restartPackage(packageName);

            // Tell all processes that loaded this package about the change.
            for (int i=mService.mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord app = mService.mLruProcesses.get(i);
                if (!app.pkgList.containsKey(packageName)) {
                    continue;
                }
                try {
                    if (app.thread != null) {
                        if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION, "Sending to proc "
                                + app.processName + " new compat " + ci);
                        app.thread.updatePackageCompatibilityInfo(packageName, ci);
                    }
                } catch (Exception e) {
                }
            }

            if (starting != null) {
                stack.ensureActivityConfigurationLocked(starting, 0);
                // And we need to make sure at this point that all other activities
                // are made visible with the correct configuration.
                stack.ensureActivitiesVisibleLocked(starting, 0);
            }
        }
    }

    void saveCompatModes() {
        HashMap<String, Integer> pkgs;
        synchronized (mService) {
            pkgs = new HashMap<String, Integer>(mPackages);
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
            final int screenLayout = mService.mConfiguration.screenLayout;
            final int smallestScreenWidthDp = mService.mConfiguration.smallestScreenWidthDp;
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

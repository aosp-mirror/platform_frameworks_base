package com.android.server.am;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Iterator;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import com.android.internal.os.AtomicFile;
import com.android.internal.util.FastXmlSerializer;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.res.CompatibilityInfo;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;
import android.util.Xml;

public class CompatModePackages {
    private final String TAG = ActivityManagerService.TAG;
    private final boolean DEBUG_CONFIGURATION = ActivityManagerService.DEBUG_CONFIGURATION;

    private final ActivityManagerService mService;
    private final AtomicFile mFile;

    private final HashSet<String> mPackages = new HashSet<String>();

    private static final int MSG_WRITE = 1;

    private final Handler mHandler = new Handler() {
        @Override public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WRITE:
                    saveCompatModes();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    };

    public CompatModePackages(ActivityManagerService service, File systemDir) {
        mService = service;
        mFile = new AtomicFile(new File(systemDir, "packages-compat.xml"));

        FileInputStream fis = null;
        try {
            fis = mFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG) {
                eventType = parser.next();
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
                                    mPackages.add(pkg);
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

    public HashSet<String> getPackages() {
        return mPackages;
    }

    public CompatibilityInfo compatibilityInfoForPackageLocked(ApplicationInfo ai) {
        return new CompatibilityInfo(ai, mService.mConfiguration.screenLayout,
                mPackages.contains(ai.packageName));
    }

    private int computeCompatModeLocked(ApplicationInfo ai) {
        boolean enabled = mPackages.contains(ai.packageName);
        CompatibilityInfo info = new CompatibilityInfo(ai,
                mService.mConfiguration.screenLayout, enabled);
        if (info.alwaysSupportsScreen()) {
            return ActivityManager.COMPAT_MODE_NEVER;
        }
        if (info.neverSupportsScreen()) {
            return ActivityManager.COMPAT_MODE_ALWAYS;
        }
        return enabled ? ActivityManager.COMPAT_MODE_ENABLED
                : ActivityManager.COMPAT_MODE_DISABLED;
    }

    public int getFrontActivityScreenCompatModeLocked() {
        ActivityRecord r = mService.mMainStack.topRunningActivityLocked(null);
        if (r == null) {
            return ActivityManager.COMPAT_MODE_UNKNOWN;
        }
        return computeCompatModeLocked(r.info.applicationInfo);
    }

    public void setFrontActivityScreenCompatModeLocked(int mode) {
        ActivityRecord r = mService.mMainStack.topRunningActivityLocked(null);
        if (r == null) {
            Slog.w(TAG, "setFrontActivityScreenCompatMode failed: no top activity");
            return;
        }
        setPackageScreenCompatModeLocked(r.info.applicationInfo, mode);
    }

    public int getPackageScreenCompatModeLocked(String packageName) {
        ApplicationInfo ai = null;
        try {
            ai = AppGlobals.getPackageManager().getApplicationInfo(packageName, 0);
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
            ai = AppGlobals.getPackageManager().getApplicationInfo(packageName, 0);
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

        boolean changed = false;
        boolean enable;
        switch (mode) {
            case ActivityManager.COMPAT_MODE_DISABLED:
                enable = false;
                break;
            case ActivityManager.COMPAT_MODE_ENABLED:
                enable = true;
                break;
            case ActivityManager.COMPAT_MODE_TOGGLE:
                enable = !mPackages.contains(packageName);
                break;
            default:
                Slog.w(TAG, "Unknown screen compat mode req #" + mode + "; ignoring");
                return;
        }
        if (enable) {
            if (!mPackages.contains(packageName)) {
                changed = true;
                mPackages.add(packageName);
            }
        } else {
            if (mPackages.contains(packageName)) {
                changed = true;
                mPackages.remove(packageName);
            }
        }
        if (changed) {
            CompatibilityInfo ci = compatibilityInfoForPackageLocked(ai);
            if (ci.alwaysSupportsScreen()) {
                Slog.w(TAG, "Ignoring compat mode change of " + packageName
                        + "; compatibility never needed");
                return;
            }
            if (ci.neverSupportsScreen()) {
                Slog.w(TAG, "Ignoring compat mode change of " + packageName
                        + "; compatibility always needed");
                return;
            }

            mHandler.removeMessages(MSG_WRITE);
            Message msg = mHandler.obtainMessage(MSG_WRITE);
            mHandler.sendMessageDelayed(msg, 10000);

            // Tell all processes that loaded this package about the change.
            for (int i=mService.mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord app = mService.mLruProcesses.get(i);
                if (!app.pkgList.contains(packageName)) {
                    continue;
                }
                try {
                    if (app.thread != null) {
                        if (DEBUG_CONFIGURATION) Slog.v(TAG, "Sending to proc "
                                + app.processName + " new compat " + ci);
                        app.thread.updatePackageCompatibilityInfo(packageName, ci);
                    }
                } catch (Exception e) {
                }
            }

            // All activities that came from the packge must be
            // restarted as if there was a config change.
            for (int i=mService.mMainStack.mHistory.size()-1; i>=0; i--) {
                ActivityRecord a = (ActivityRecord)mService.mMainStack.mHistory.get(i);
                if (a.info.packageName.equals(packageName)) {
                    a.forceNewConfig = true;
                }
            }

            ActivityRecord starting = mService.mMainStack.topRunningActivityLocked(null);
            if (starting != null) {
                mService.mMainStack.ensureActivityConfigurationLocked(starting, 0);
                // And we need to make sure at this point that all other activities
                // are made visible with the correct configuration.
                mService.mMainStack.ensureActivitiesVisibleLocked(starting, 0);
            }
        }
    }

    void saveCompatModes() {
        HashSet<String> pkgs;
        synchronized (mService) {
            pkgs = new HashSet<String>(mPackages);
        }

        FileOutputStream fos = null;

        try {
            fos = mFile.startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            out.startTag(null, "compat-packages");

            final IPackageManager pm = AppGlobals.getPackageManager();
            final int screenLayout = mService.mConfiguration.screenLayout;
            final Iterator<String> it = pkgs.iterator();
            while (it.hasNext()) {
                String pkg = it.next();
                ApplicationInfo ai = null;
                try {
                    ai = pm.getApplicationInfo(pkg, 0);
                } catch (RemoteException e) {
                }
                if (ai == null) {
                    continue;
                }
                CompatibilityInfo info = new CompatibilityInfo(ai, screenLayout, false);
                if (info.alwaysSupportsScreen()) {
                    continue;
                }
                if (info.neverSupportsScreen()) {
                    continue;
                }
                out.startTag(null, "pkg");
                out.attribute(null, "name", pkg);
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

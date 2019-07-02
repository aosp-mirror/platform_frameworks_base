/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.UiThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AtomicFile;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Manages warning dialogs shown during application lifecycle.
 */
class AppWarnings {
    private static final String TAG = "AppWarnings";
    private static final String CONFIG_FILE_NAME = "packages-warnings.xml";

    public static final int FLAG_HIDE_DISPLAY_SIZE = 0x01;
    public static final int FLAG_HIDE_COMPILE_SDK = 0x02;
    public static final int FLAG_HIDE_DEPRECATED_SDK = 0x04;

    private final HashMap<String, Integer> mPackageFlags = new HashMap<>();

    private final ActivityTaskManagerService mAtm;
    private final Context mUiContext;
    private final ConfigHandler mHandler;
    private final UiHandler mUiHandler;
    private final AtomicFile mConfigFile;

    private UnsupportedDisplaySizeDialog mUnsupportedDisplaySizeDialog;
    private UnsupportedCompileSdkDialog mUnsupportedCompileSdkDialog;
    private DeprecatedTargetSdkVersionDialog mDeprecatedTargetSdkVersionDialog;

    /** @see android.app.ActivityManager#alwaysShowUnsupportedCompileSdkWarning */
    private HashSet<ComponentName> mAlwaysShowUnsupportedCompileSdkWarningActivities =
            new HashSet<>();

    /** @see android.app.ActivityManager#alwaysShowUnsupportedCompileSdkWarning */
    void alwaysShowUnsupportedCompileSdkWarning(ComponentName activity) {
        mAlwaysShowUnsupportedCompileSdkWarningActivities.add(activity);
    }

    /**
     * Creates a new warning dialog manager.
     * <p>
     * <strong>Note:</strong> Must be called from the ActivityManagerService thread.
     *
     * @param atm
     * @param uiContext
     * @param handler
     * @param uiHandler
     * @param systemDir
     */
    public AppWarnings(ActivityTaskManagerService atm, Context uiContext, Handler handler,
            Handler uiHandler, File systemDir) {
        mAtm = atm;
        mUiContext = uiContext;
        mHandler = new ConfigHandler(handler.getLooper());
        mUiHandler = new UiHandler(uiHandler.getLooper());
        mConfigFile = new AtomicFile(new File(systemDir, CONFIG_FILE_NAME), "warnings-config");

        readConfigFromFileAmsThread();
    }

    /**
     * Shows the "unsupported display size" warning, if necessary.
     *
     * @param r activity record for which the warning may be displayed
     */
    public void showUnsupportedDisplaySizeDialogIfNeeded(ActivityRecord r) {
        final Configuration globalConfig = mAtm.getGlobalConfiguration();
        if (globalConfig.densityDpi != DisplayMetrics.DENSITY_DEVICE_STABLE
                && r.appInfo.requiresSmallestWidthDp > globalConfig.smallestScreenWidthDp) {
            mUiHandler.showUnsupportedDisplaySizeDialog(r);
        }
    }

    /**
     * Shows the "unsupported compile SDK" warning, if necessary.
     *
     * @param r activity record for which the warning may be displayed
     */
    public void showUnsupportedCompileSdkDialogIfNeeded(ActivityRecord r) {
        if (r.appInfo.compileSdkVersion == 0 || r.appInfo.compileSdkVersionCodename == null) {
            // We don't know enough about this package. Abort!
            return;
        }

        // TODO(b/75318890): Need to move this to when the app actually crashes.
        if (/*ActivityManager.isRunningInTestHarness()
                &&*/ !mAlwaysShowUnsupportedCompileSdkWarningActivities.contains(
                        r.mActivityComponent)) {
            // Don't show warning if we are running in a test harness and we don't have to always
            // show for this activity.
            return;
        }

        // If the application was built against an pre-release SDK that's older than the current
        // platform OR if the current platform is pre-release and older than the SDK against which
        // the application was built OR both are pre-release with the same SDK_INT but different
        // codenames (e.g. simultaneous pre-release development), then we're likely to run into
        // compatibility issues. Warn the user and offer to check for an update.
        final int compileSdk = r.appInfo.compileSdkVersion;
        final int platformSdk = Build.VERSION.SDK_INT;
        final boolean isCompileSdkPreview = !"REL".equals(r.appInfo.compileSdkVersionCodename);
        final boolean isPlatformSdkPreview = !"REL".equals(Build.VERSION.CODENAME);
        if ((isCompileSdkPreview && compileSdk < platformSdk)
                || (isPlatformSdkPreview && platformSdk < compileSdk)
                || (isCompileSdkPreview && isPlatformSdkPreview && platformSdk == compileSdk
                    && !Build.VERSION.CODENAME.equals(r.appInfo.compileSdkVersionCodename))) {
            mUiHandler.showUnsupportedCompileSdkDialog(r);
        }
    }

    /**
     * Shows the "deprecated target sdk" warning, if necessary.
     *
     * @param r activity record for which the warning may be displayed
     */
    public void showDeprecatedTargetDialogIfNeeded(ActivityRecord r) {
        if (r.appInfo.targetSdkVersion < Build.VERSION.MIN_SUPPORTED_TARGET_SDK_INT) {
            mUiHandler.showDeprecatedTargetDialog(r);
        }
    }

    /**
     * Called when an activity is being started.
     *
     * @param r record for the activity being started
     */
    public void onStartActivity(ActivityRecord r) {
        showUnsupportedCompileSdkDialogIfNeeded(r);
        showUnsupportedDisplaySizeDialogIfNeeded(r);
        showDeprecatedTargetDialogIfNeeded(r);
    }

    /**
     * Called when an activity was previously started and is being resumed.
     *
     * @param r record for the activity being resumed
     */
    public void onResumeActivity(ActivityRecord r) {
        showUnsupportedDisplaySizeDialogIfNeeded(r);
    }

    /**
     * Called by ActivityManagerService when package data has been cleared.
     *
     * @param name the package whose data has been cleared
     */
    public void onPackageDataCleared(String name) {
        removePackageAndHideDialogs(name);
    }

    /**
     * Called by ActivityManagerService when a package has been uninstalled.
     *
     * @param name the package that has been uninstalled
     */
    public void onPackageUninstalled(String name) {
        removePackageAndHideDialogs(name);
    }

    /**
     * Called by ActivityManagerService when the default display density has changed.
     */
    public void onDensityChanged() {
        mUiHandler.hideUnsupportedDisplaySizeDialog();
    }

    /**
     * Does what it says on the tin.
     */
    private void removePackageAndHideDialogs(String name) {
        mUiHandler.hideDialogsForPackage(name);

        synchronized (mPackageFlags) {
            mPackageFlags.remove(name);
            mHandler.scheduleWrite();
        }
    }

    /**
     * Hides the "unsupported display size" warning.
     * <p>
     * <strong>Note:</strong> Must be called on the UI thread.
     */
    @UiThread
    private void hideUnsupportedDisplaySizeDialogUiThread() {
        if (mUnsupportedDisplaySizeDialog != null) {
            mUnsupportedDisplaySizeDialog.dismiss();
            mUnsupportedDisplaySizeDialog = null;
        }
    }

    /**
     * Shows the "unsupported display size" warning for the given application.
     * <p>
     * <strong>Note:</strong> Must be called on the UI thread.
     *
     * @param ar record for the activity that triggered the warning
     */
    @UiThread
    private void showUnsupportedDisplaySizeDialogUiThread(ActivityRecord ar) {
        if (mUnsupportedDisplaySizeDialog != null) {
            mUnsupportedDisplaySizeDialog.dismiss();
            mUnsupportedDisplaySizeDialog = null;
        }
        if (ar != null && !hasPackageFlag(
                ar.packageName, FLAG_HIDE_DISPLAY_SIZE)) {
            mUnsupportedDisplaySizeDialog = new UnsupportedDisplaySizeDialog(
                    AppWarnings.this, mUiContext, ar.info.applicationInfo);
            mUnsupportedDisplaySizeDialog.show();
        }
    }

    /**
     * Shows the "unsupported compile SDK" warning for the given application.
     * <p>
     * <strong>Note:</strong> Must be called on the UI thread.
     *
     * @param ar record for the activity that triggered the warning
     */
    @UiThread
    private void showUnsupportedCompileSdkDialogUiThread(ActivityRecord ar) {
        if (mUnsupportedCompileSdkDialog != null) {
            mUnsupportedCompileSdkDialog.dismiss();
            mUnsupportedCompileSdkDialog = null;
        }
        if (ar != null && !hasPackageFlag(
                ar.packageName, FLAG_HIDE_COMPILE_SDK)) {
            mUnsupportedCompileSdkDialog = new UnsupportedCompileSdkDialog(
                    AppWarnings.this, mUiContext, ar.info.applicationInfo);
            mUnsupportedCompileSdkDialog.show();
        }
    }

    /**
     * Shows the "deprecated target sdk version" warning for the given application.
     * <p>
     * <strong>Note:</strong> Must be called on the UI thread.
     *
     * @param ar record for the activity that triggered the warning
     */
    @UiThread
    private void showDeprecatedTargetSdkDialogUiThread(ActivityRecord ar) {
        if (mDeprecatedTargetSdkVersionDialog != null) {
            mDeprecatedTargetSdkVersionDialog.dismiss();
            mDeprecatedTargetSdkVersionDialog = null;
        }
        if (ar != null && !hasPackageFlag(
                ar.packageName, FLAG_HIDE_DEPRECATED_SDK)) {
            mDeprecatedTargetSdkVersionDialog = new DeprecatedTargetSdkVersionDialog(
                    AppWarnings.this, mUiContext, ar.info.applicationInfo);
            mDeprecatedTargetSdkVersionDialog.show();
        }
    }

    /**
     * Dismisses all warnings for the given package.
     * <p>
     * <strong>Note:</strong> Must be called on the UI thread.
     *
     * @param name the package for which warnings should be dismissed, or {@code null} to dismiss
     *             all warnings
     */
    @UiThread
    private void hideDialogsForPackageUiThread(String name) {
        // Hides the "unsupported display" dialog if necessary.
        if (mUnsupportedDisplaySizeDialog != null && (name == null || name.equals(
                mUnsupportedDisplaySizeDialog.getPackageName()))) {
            mUnsupportedDisplaySizeDialog.dismiss();
            mUnsupportedDisplaySizeDialog = null;
        }

        // Hides the "unsupported compile SDK" dialog if necessary.
        if (mUnsupportedCompileSdkDialog != null && (name == null || name.equals(
                mUnsupportedCompileSdkDialog.getPackageName()))) {
            mUnsupportedCompileSdkDialog.dismiss();
            mUnsupportedCompileSdkDialog = null;
        }

        // Hides the "deprecated target sdk version" dialog if necessary.
        if (mDeprecatedTargetSdkVersionDialog != null && (name == null || name.equals(
                mDeprecatedTargetSdkVersionDialog.getPackageName()))) {
            mDeprecatedTargetSdkVersionDialog.dismiss();
            mDeprecatedTargetSdkVersionDialog = null;
        }
    }

    /**
     * Returns the value of the flag for the given package.
     *
     * @param name the package from which to retrieve the flag
     * @param flag the bitmask for the flag to retrieve
     * @return {@code true} if the flag is enabled, {@code false} otherwise
     */
    boolean hasPackageFlag(String name, int flag) {
        return (getPackageFlags(name) & flag) == flag;
    }

    /**
     * Sets the flag for the given package to the specified value.
     *
     * @param name the package on which to set the flag
     * @param flag the bitmask for flag to set
     * @param enabled the value to set for the flag
     */
    void setPackageFlag(String name, int flag, boolean enabled) {
        synchronized (mPackageFlags) {
            final int curFlags = getPackageFlags(name);
            final int newFlags = enabled ? (curFlags | flag) : (curFlags & ~flag);
            if (curFlags != newFlags) {
                if (newFlags != 0) {
                    mPackageFlags.put(name, newFlags);
                } else {
                    mPackageFlags.remove(name);
                }
                mHandler.scheduleWrite();
            }
        }
    }

    /**
     * Returns the bitmask of flags set for the specified package.
     */
    private int getPackageFlags(String name) {
        synchronized (mPackageFlags) {
            return mPackageFlags.getOrDefault(name, 0);
        }
    }

    /**
     * Handles messages on the system process UI thread.
     */
    private final class UiHandler extends Handler {
        private static final int MSG_SHOW_UNSUPPORTED_DISPLAY_SIZE_DIALOG = 1;
        private static final int MSG_HIDE_UNSUPPORTED_DISPLAY_SIZE_DIALOG = 2;
        private static final int MSG_SHOW_UNSUPPORTED_COMPILE_SDK_DIALOG = 3;
        private static final int MSG_HIDE_DIALOGS_FOR_PACKAGE = 4;
        private static final int MSG_SHOW_DEPRECATED_TARGET_SDK_DIALOG = 5;

        public UiHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SHOW_UNSUPPORTED_DISPLAY_SIZE_DIALOG: {
                    final ActivityRecord ar = (ActivityRecord) msg.obj;
                    showUnsupportedDisplaySizeDialogUiThread(ar);
                } break;
                case MSG_HIDE_UNSUPPORTED_DISPLAY_SIZE_DIALOG: {
                    hideUnsupportedDisplaySizeDialogUiThread();
                } break;
                case MSG_SHOW_UNSUPPORTED_COMPILE_SDK_DIALOG: {
                    final ActivityRecord ar = (ActivityRecord) msg.obj;
                    showUnsupportedCompileSdkDialogUiThread(ar);
                } break;
                case MSG_HIDE_DIALOGS_FOR_PACKAGE: {
                    final String name = (String) msg.obj;
                    hideDialogsForPackageUiThread(name);
                } break;
                case MSG_SHOW_DEPRECATED_TARGET_SDK_DIALOG: {
                    final ActivityRecord ar = (ActivityRecord) msg.obj;
                    showDeprecatedTargetSdkDialogUiThread(ar);
                } break;
            }
        }

        public void showUnsupportedDisplaySizeDialog(ActivityRecord r) {
            removeMessages(MSG_SHOW_UNSUPPORTED_DISPLAY_SIZE_DIALOG);
            obtainMessage(MSG_SHOW_UNSUPPORTED_DISPLAY_SIZE_DIALOG, r).sendToTarget();
        }

        public void hideUnsupportedDisplaySizeDialog() {
            removeMessages(MSG_HIDE_UNSUPPORTED_DISPLAY_SIZE_DIALOG);
            sendEmptyMessage(MSG_HIDE_UNSUPPORTED_DISPLAY_SIZE_DIALOG);
        }

        public void showUnsupportedCompileSdkDialog(ActivityRecord r) {
            removeMessages(MSG_SHOW_UNSUPPORTED_COMPILE_SDK_DIALOG);
            obtainMessage(MSG_SHOW_UNSUPPORTED_COMPILE_SDK_DIALOG, r).sendToTarget();
        }

        public void showDeprecatedTargetDialog(ActivityRecord r) {
            removeMessages(MSG_SHOW_DEPRECATED_TARGET_SDK_DIALOG);
            obtainMessage(MSG_SHOW_DEPRECATED_TARGET_SDK_DIALOG, r).sendToTarget();
        }

        public void hideDialogsForPackage(String name) {
            obtainMessage(MSG_HIDE_DIALOGS_FOR_PACKAGE, name).sendToTarget();
        }
    }

    /**
     * Handles messages on the ActivityTaskManagerService thread.
     */
    private final class ConfigHandler extends Handler {
        private static final int MSG_WRITE = 1;

        private static final int DELAY_MSG_WRITE = 10000;

        public ConfigHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WRITE:
                    writeConfigToFileAmsThread();
                    break;
            }
        }

        public void scheduleWrite() {
            removeMessages(MSG_WRITE);
            sendEmptyMessageDelayed(MSG_WRITE, DELAY_MSG_WRITE);
        }
    }

    /**
     * Writes the configuration file.
     * <p>
     * <strong>Note:</strong> Should be called from the ActivityManagerService thread unless you
     * don't care where you're doing I/O operations. But you <i>do</i> care, don't you?
     */
    private void writeConfigToFileAmsThread() {
        // Create a shallow copy so that we don't have to synchronize on config.
        final HashMap<String, Integer> packageFlags;
        synchronized (mPackageFlags) {
            packageFlags = new HashMap<>(mPackageFlags);
        }

        FileOutputStream fos = null;
        try {
            fos = mConfigFile.startWrite();

            final XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            out.startTag(null, "packages");

            for (Map.Entry<String, Integer> entry : packageFlags.entrySet()) {
                String pkg = entry.getKey();
                int mode = entry.getValue();
                if (mode == 0) {
                    continue;
                }
                out.startTag(null, "package");
                out.attribute(null, "name", pkg);
                out.attribute(null, "flags", Integer.toString(mode));
                out.endTag(null, "package");
            }

            out.endTag(null, "packages");
            out.endDocument();

            mConfigFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Slog.w(TAG, "Error writing package metadata", e1);
            if (fos != null) {
                mConfigFile.failWrite(fos);
            }
        }
    }

    /**
     * Reads the configuration file and populates the package flags.
     * <p>
     * <strong>Note:</strong> Must be called from the constructor (and thus on the
     * ActivityManagerService thread) since we don't synchronize on config.
     */
    private void readConfigFromFileAmsThread() {
        FileInputStream fis = null;

        try {
            fis = mConfigFile.openRead();

            final XmlPullParser parser = Xml.newPullParser();
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
            if ("packages".equals(tagName)) {
                eventType = parser.next();
                do {
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = parser.getName();
                        if (parser.getDepth() == 2) {
                            if ("package".equals(tagName)) {
                                final String name = parser.getAttributeValue(null, "name");
                                if (name != null) {
                                    final String flags = parser.getAttributeValue(
                                            null, "flags");
                                    int flagsInt = 0;
                                    if (flags != null) {
                                        try {
                                            flagsInt = Integer.parseInt(flags);
                                        } catch (NumberFormatException e) {
                                        }
                                    }
                                    mPackageFlags.put(name, flagsInt);
                                }
                            }
                        }
                    }
                    eventType = parser.next();
                } while (eventType != XmlPullParser.END_DOCUMENT);
            }
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Error reading package metadata", e);
        } catch (java.io.IOException e) {
            if (fis != null) Slog.w(TAG, "Error reading package metadata", e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (java.io.IOException e1) {
                }
            }
        }
    }
}

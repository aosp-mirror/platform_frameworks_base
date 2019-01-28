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

package android.content.pm;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager.DeleteFlags;
import android.content.pm.PackageManager.InstallReason;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.FileBridge;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ExceptionUtils;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Offers the ability to install, upgrade, and remove applications on the
 * device. This includes support for apps packaged either as a single
 * "monolithic" APK, or apps packaged as multiple "split" APKs.
 * <p>
 * An app is delivered for installation through a
 * {@link PackageInstaller.Session}, which any app can create. Once the session
 * is created, the installer can stream one or more APKs into place until it
 * decides to either commit or destroy the session. Committing may require user
 * intervention to complete the installation, unless the caller falls into one of the
 * following categories, in which case the installation will complete automatically.
 * <ul>
 * <li>the device owner
 * <li>the affiliated profile owner
 * <li>the device owner delegated app with
 *     {@link android.app.admin.DevicePolicyManager#DELEGATION_PACKAGE_INSTALLATION}
 * </ul>
 * <p>
 * Sessions can install brand new apps, upgrade existing apps, or add new splits
 * into an existing app.
 * <p>
 * Apps packaged as multiple split APKs always consist of a single "base" APK
 * (with a {@code null} split name) and zero or more "split" APKs (with unique
 * split names). Any subset of these APKs can be installed together, as long as
 * the following constraints are met:
 * <ul>
 * <li>All APKs must have the exact same package name, version code, and signing
 * certificates.
 * <li>All APKs must have unique split names.
 * <li>All installations must contain a single base APK.
 * </ul>
 * <p>
 * The ApiDemos project contains examples of using this API:
 * <code>ApiDemos/src/com/example/android/apis/content/InstallApk*.java</code>.
 * <p>
 * On Android Q or above, an app installed notification will be posted
 * by system after a new app is installed.
 * To customize installer's notification icon, you should declare the following in the manifest
 * &lt;application> as follows: </p>
 * <pre>
 * &lt;meta-data android:name="com.android.packageinstaller.notification.smallIcon"
 * android:resource="@drawable/installer_notification_icon"/>
 * </pre>
 * <pre>
 * &lt;meta-data android:name="com.android.packageinstaller.notification.color"
 * android:resource="@color/installer_notification_color"/>
 * </pre>
 */
public class PackageInstaller {
    private static final String TAG = "PackageInstaller";

    /** {@hide} */
    public static final boolean ENABLE_REVOCABLE_FD =
            SystemProperties.getBoolean("fw.revocable_fd", false);

    /**
     * Activity Action: Show details about a particular install session. This
     * may surface actions such as pause, resume, or cancel.
     * <p>
     * This should always be scoped to the installer package that owns the
     * session. Clients should use {@link SessionInfo#createDetailsIntent()} to
     * build this intent correctly.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     * <p>
     * The session to show details for is defined in {@link #EXTRA_SESSION_ID}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SESSION_DETAILS = "android.content.pm.action.SESSION_DETAILS";

    /**
     * Broadcast Action: Explicit broadcast sent to the last known default launcher when a session
     * for a new install is committed. For managed profile, this is sent to the default launcher
     * of the primary profile.
     * <p>
     * The associated session is defined in {@link #EXTRA_SESSION} and the user for which this
     * session was created in {@link Intent#EXTRA_USER}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SESSION_COMMITTED =
            "android.content.pm.action.SESSION_COMMITTED";

    /**
     * Broadcast Action: Send information about a staged install session when its state is updated.
     * <p>
     * The associated session information is defined in {@link #EXTRA_SESSION}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SESSION_UPDATED =
            "android.content.pm.action.SESSION_UPDATED";

    /** {@hide} */
    public static final String ACTION_CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL";

    /**
     * An integer session ID that an operation is working with.
     *
     * @see Intent#getIntExtra(String, int)
     */
    public static final String EXTRA_SESSION_ID = "android.content.pm.extra.SESSION_ID";

    /**
     * {@link SessionInfo} that an operation is working with.
     *
     * @see Intent#getParcelableExtra(String)
     */
    public static final String EXTRA_SESSION = "android.content.pm.extra.SESSION";

    /**
     * Package name that an operation is working with.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String EXTRA_PACKAGE_NAME = "android.content.pm.extra.PACKAGE_NAME";

    /**
     * Current status of an operation. Will be one of
     * {@link #STATUS_PENDING_USER_ACTION}, {@link #STATUS_SUCCESS},
     * {@link #STATUS_FAILURE}, {@link #STATUS_FAILURE_ABORTED},
     * {@link #STATUS_FAILURE_BLOCKED}, {@link #STATUS_FAILURE_CONFLICT},
     * {@link #STATUS_FAILURE_INCOMPATIBLE}, {@link #STATUS_FAILURE_INVALID}, or
     * {@link #STATUS_FAILURE_STORAGE}.
     * <p>
     * More information about a status may be available through additional
     * extras; see the individual status documentation for details.
     *
     * @see Intent#getIntExtra(String, int)
     */
    public static final String EXTRA_STATUS = "android.content.pm.extra.STATUS";

    /**
     * Detailed string representation of the status, including raw details that
     * are useful for debugging.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String EXTRA_STATUS_MESSAGE = "android.content.pm.extra.STATUS_MESSAGE";

    /**
     * Another package name relevant to a status. This is typically the package
     * responsible for causing an operation failure.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String
            EXTRA_OTHER_PACKAGE_NAME = "android.content.pm.extra.OTHER_PACKAGE_NAME";

    /**
     * Storage path relevant to a status.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String EXTRA_STORAGE_PATH = "android.content.pm.extra.STORAGE_PATH";

    /** {@hide} */
    @Deprecated
    public static final String EXTRA_PACKAGE_NAMES = "android.content.pm.extra.PACKAGE_NAMES";

    /** {@hide} */
    public static final String EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS";
    /** {@hide} */
    public static final String EXTRA_LEGACY_BUNDLE = "android.content.pm.extra.LEGACY_BUNDLE";
    /** {@hide} */
    public static final String EXTRA_CALLBACK = "android.content.pm.extra.CALLBACK";

    /**
     * User action is currently required to proceed. You can launch the intent
     * activity described by {@link Intent#EXTRA_INTENT} to involve the user and
     * continue.
     * <p>
     * You may choose to immediately launch the intent if the user is actively
     * using your app. Otherwise, you should use a notification to guide the
     * user back into your app before launching.
     *
     * @see Intent#getParcelableExtra(String)
     */
    public static final int STATUS_PENDING_USER_ACTION = -1;

    /**
     * The operation succeeded.
     */
    public static final int STATUS_SUCCESS = 0;

    /**
     * The operation failed in a generic way. The system will always try to
     * provide a more specific failure reason, but in some rare cases this may
     * be delivered.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE = 1;

    /**
     * The operation failed because it was blocked. For example, a device policy
     * may be blocking the operation, a package verifier may have blocked the
     * operation, or the app may be required for core system operation.
     * <p>
     * The result may also contain {@link #EXTRA_OTHER_PACKAGE_NAME} with the
     * specific package blocking the install.
     *
     * @see #EXTRA_STATUS_MESSAGE
     * @see #EXTRA_OTHER_PACKAGE_NAME
     */
    public static final int STATUS_FAILURE_BLOCKED = 2;

    /**
     * The operation failed because it was actively aborted. For example, the
     * user actively declined requested permissions, or the session was
     * abandoned.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_ABORTED = 3;

    /**
     * The operation failed because one or more of the APKs was invalid. For
     * example, they might be malformed, corrupt, incorrectly signed,
     * mismatched, etc.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_INVALID = 4;

    /**
     * The operation failed because it conflicts (or is inconsistent with) with
     * another package already installed on the device. For example, an existing
     * permission, incompatible certificates, etc. The user may be able to
     * uninstall another app to fix the issue.
     * <p>
     * The result may also contain {@link #EXTRA_OTHER_PACKAGE_NAME} with the
     * specific package identified as the cause of the conflict.
     *
     * @see #EXTRA_STATUS_MESSAGE
     * @see #EXTRA_OTHER_PACKAGE_NAME
     */
    public static final int STATUS_FAILURE_CONFLICT = 5;

    /**
     * The operation failed because of storage issues. For example, the device
     * may be running low on space, or external media may be unavailable. The
     * user may be able to help free space or insert different external media.
     * <p>
     * The result may also contain {@link #EXTRA_STORAGE_PATH} with the path to
     * the storage device that caused the failure.
     *
     * @see #EXTRA_STATUS_MESSAGE
     * @see #EXTRA_STORAGE_PATH
     */
    public static final int STATUS_FAILURE_STORAGE = 6;

    /**
     * The operation failed because it is fundamentally incompatible with this
     * device. For example, the app may require a hardware feature that doesn't
     * exist, it may be missing native code for the ABIs supported by the
     * device, or it requires a newer SDK version, etc.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_INCOMPATIBLE = 7;

    private final IPackageInstaller mInstaller;
    private final int mUserId;
    private final String mInstallerPackageName;

    private final ArrayList<SessionCallbackDelegate> mDelegates = new ArrayList<>();

    /** {@hide} */
    public PackageInstaller(IPackageInstaller installer,
            String installerPackageName, int userId) {
        mInstaller = installer;
        mInstallerPackageName = installerPackageName;
        mUserId = userId;
    }

    /**
     * Create a new session using the given parameters, returning a unique ID
     * that represents the session. Once created, the session can be opened
     * multiple times across multiple device boots.
     * <p>
     * The system may automatically destroy sessions that have not been
     * finalized (either committed or abandoned) within a reasonable period of
     * time, typically on the order of a day.
     *
     * @throws IOException if parameters were unsatisfiable, such as lack of
     *             disk space or unavailable media.
     * @throws SecurityException when installation services are unavailable,
     *             such as when called from a restricted user.
     * @throws IllegalArgumentException when {@link SessionParams} is invalid.
     * @return positive, non-zero unique ID that represents the created session.
     *         This ID remains consistent across device reboots until the
     *         session is finalized. IDs are not reused during a given boot.
     */
    public int createSession(@NonNull SessionParams params) throws IOException {
        try {
            final String installerPackage;
            if (params.installerPackageName == null) {
                installerPackage = mInstallerPackageName;
            } else {
                installerPackage = params.installerPackageName;
            }

            return mInstaller.createSession(params, installerPackage, mUserId);
        } catch (RuntimeException e) {
            ExceptionUtils.maybeUnwrapIOException(e);
            throw e;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Open an existing session to actively perform work. To succeed, the caller
     * must be the owner of the install session.
     *
     * @throws IOException if parameters were unsatisfiable, such as lack of
     *             disk space or unavailable media.
     * @throws SecurityException when the caller does not own the session, or
     *             the session is invalid.
     */
    public @NonNull Session openSession(int sessionId) throws IOException {
        try {
            try {
                return new Session(mInstaller.openSession(sessionId));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } catch (RuntimeException e) {
            ExceptionUtils.maybeUnwrapIOException(e);
            throw e;
        }
    }

    /**
     * Update the icon representing the app being installed in a specific
     * session. This should be roughly
     * {@link ActivityManager#getLauncherLargeIconSize()} in both dimensions.
     *
     * @throws SecurityException when the caller does not own the session, or
     *             the session is invalid.
     */
    public void updateSessionAppIcon(int sessionId, @Nullable Bitmap appIcon) {
        try {
            mInstaller.updateSessionAppIcon(sessionId, appIcon);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Update the label representing the app being installed in a specific
     * session.
     *
     * @throws SecurityException when the caller does not own the session, or
     *             the session is invalid.
     */
    public void updateSessionAppLabel(int sessionId, @Nullable CharSequence appLabel) {
        try {
            final String val = (appLabel != null) ? appLabel.toString() : null;
            mInstaller.updateSessionAppLabel(sessionId, val);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Completely abandon the given session, destroying all staged data and
     * rendering it invalid. Abandoned sessions will be reported to
     * {@link SessionCallback} listeners as failures. This is equivalent to
     * opening the session and calling {@link Session#abandon()}.
     *
     * @throws SecurityException when the caller does not own the session, or
     *             the session is invalid.
     */
    public void abandonSession(int sessionId) {
        try {
            mInstaller.abandonSession(sessionId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return details for a specific session. No special permissions are
     * required to retrieve these details.
     *
     * @return details for the requested session, or {@code null} if the session
     *         does not exist.
     */
    public @Nullable SessionInfo getSessionInfo(int sessionId) {
        try {
            return mInstaller.getSessionInfo(sessionId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return list of all known install sessions, regardless of the installer.
     */
    public @NonNull List<SessionInfo> getAllSessions() {
        try {
            return mInstaller.getAllSessions(mUserId).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return list of all known install sessions owned by the calling app.
     */
    public @NonNull List<SessionInfo> getMySessions() {
        try {
            return mInstaller.getMySessions(mInstallerPackageName, mUserId).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return list of all staged install sessions.
     */
    public @NonNull List<SessionInfo> getStagedSessions() {
        try {
            // TODO: limit this to the mUserId?
            return mInstaller.getStagedSessions().getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Uninstall the given package, removing it completely from the device. This
     * method is available to:
     * <ul>
     * <li>the current "installer of record" for the package
     * <li>the device owner
     * <li>the affiliated profile owner
     * <li>the device owner delegated app with
     *     {@link android.app.admin.DevicePolicyManager#DELEGATION_PACKAGE_INSTALLATION}
     * </ul>
     *
     * @param packageName The package to uninstall.
     * @param statusReceiver Where to deliver the result.
     *
     * @see android.app.admin.DevicePolicyManager
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES})
    public void uninstall(@NonNull String packageName, @NonNull IntentSender statusReceiver) {
        uninstall(packageName, 0 /*flags*/, statusReceiver);
    }

    /**
     * Uninstall the given package, removing it completely from the device. This
     * method is only available to the current "installer of record" for the
     * package.
     *
     * @param packageName The package to uninstall.
     * @param flags Flags for uninstall.
     * @param statusReceiver Where to deliver the result.
     *
     * @hide
     */
    public void uninstall(@NonNull String packageName, @DeleteFlags int flags,
            @NonNull IntentSender statusReceiver) {
        uninstall(new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                flags, statusReceiver);
    }

    /**
     * Uninstall the given package with a specific version code, removing it
     * completely from the device. If the version code of the package
     * does not match the one passed in the versioned package argument this
     * method is a no-op. Use {@link PackageManager#VERSION_CODE_HIGHEST} to
     * uninstall the latest version of the package.
     * <p>
     * This method is available to:
     * <ul>
     * <li>the current "installer of record" for the package
     * <li>the device owner
     * <li>the affiliated profile owner
     * </ul>
     *
     * @param versionedPackage The versioned package to uninstall.
     * @param statusReceiver Where to deliver the result.
     *
     * @see android.app.admin.DevicePolicyManager
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES})
    public void uninstall(@NonNull VersionedPackage versionedPackage,
            @NonNull IntentSender statusReceiver) {
        uninstall(versionedPackage, 0 /*flags*/, statusReceiver);
    }

    /**
     * Uninstall the given package with a specific version code, removing it
     * completely from the device. This method is only available to the current
     * "installer of record" for the package. If the version code of the package
     * does not match the one passed in the versioned package argument this
     * method is a no-op. Use {@link PackageManager#VERSION_CODE_HIGHEST} to
     * uninstall the latest version of the package.
     *
     * @param versionedPackage The versioned package to uninstall.
     * @param flags Flags for uninstall.
     * @param statusReceiver Where to deliver the result.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES})
    public void uninstall(@NonNull VersionedPackage versionedPackage, @DeleteFlags int flags,
            @NonNull IntentSender statusReceiver) {
        Preconditions.checkNotNull(versionedPackage, "versionedPackage cannot be null");
        try {
            mInstaller.uninstall(versionedPackage, mInstallerPackageName,
                    flags, statusReceiver, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Install the given package, which already exists on the device, for the user for which this
     * installer was created.
     *
     * @param packageName The package to install.
     * @param installReason Reason for install.
     * @param statusReceiver Where to deliver the result.
     */
    @RequiresPermission(allOf = {
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.INSTALL_EXISTING_PACKAGES})
    public void installExistingPackage(@NonNull String packageName,
            @InstallReason int installReason,
            @Nullable IntentSender statusReceiver) {
        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        try {
            mInstaller.installExistingPackage(packageName, 0, installReason, statusReceiver,
                    mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /** {@hide} */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.INSTALL_PACKAGES)
    public void setPermissionsResult(int sessionId, boolean accepted) {
        try {
            mInstaller.setPermissionsResult(sessionId, accepted);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Events for observing session lifecycle.
     * <p>
     * A typical session lifecycle looks like this:
     * <ul>
     * <li>An installer creates a session to indicate pending app delivery. All
     * install details are available at this point.
     * <li>The installer opens the session to deliver APK data. Note that a
     * session may be opened and closed multiple times as network connectivity
     * changes. The installer may deliver periodic progress updates.
     * <li>The installer commits or abandons the session, resulting in the
     * session being finished.
     * </ul>
     */
    public static abstract class SessionCallback {
        /**
         * New session has been created. Details about the session can be
         * obtained from {@link PackageInstaller#getSessionInfo(int)}.
         */
        public abstract void onCreated(int sessionId);

        /**
         * Badging details for an existing session has changed. For example, the
         * app icon or label has been updated.
         */
        public abstract void onBadgingChanged(int sessionId);

        /**
         * Active state for session has been changed.
         * <p>
         * A session is considered active whenever there is ongoing forward
         * progress being made, such as the installer holding an open
         * {@link Session} instance while streaming data into place, or the
         * system optimizing code as the result of
         * {@link Session#commit(IntentSender)}.
         * <p>
         * If the installer closes the {@link Session} without committing, the
         * session is considered inactive until the installer opens the session
         * again.
         */
        public abstract void onActiveChanged(int sessionId, boolean active);

        /**
         * Progress for given session has been updated.
         * <p>
         * Note that this progress may not directly correspond to the value
         * reported by
         * {@link PackageInstaller.Session#setStagingProgress(float)}, as the
         * system may carve out a portion of the overall progress to represent
         * its own internal installation work.
         */
        public abstract void onProgressChanged(int sessionId, float progress);

        /**
         * Session has completely finished, either with success or failure.
         */
        public abstract void onFinished(int sessionId, boolean success);
    }

    /** {@hide} */
    static class SessionCallbackDelegate extends IPackageInstallerCallback.Stub {
        private static final int MSG_SESSION_CREATED = 1;
        private static final int MSG_SESSION_BADGING_CHANGED = 2;
        private static final int MSG_SESSION_ACTIVE_CHANGED = 3;
        private static final int MSG_SESSION_PROGRESS_CHANGED = 4;
        private static final int MSG_SESSION_FINISHED = 5;

        final SessionCallback mCallback;
        final Executor mExecutor;

        SessionCallbackDelegate(SessionCallback callback, Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onSessionCreated(int sessionId) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onCreated, mCallback,
                    sessionId).recycleOnUse());
        }

        @Override
        public void onSessionBadgingChanged(int sessionId) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onBadgingChanged,
                    mCallback, sessionId).recycleOnUse());
        }

        @Override
        public void onSessionActiveChanged(int sessionId, boolean active) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onActiveChanged,
                    mCallback, sessionId, active).recycleOnUse());
        }

        @Override
        public void onSessionProgressChanged(int sessionId, float progress) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onProgressChanged,
                    mCallback, sessionId, progress).recycleOnUse());
        }

        @Override
        public void onSessionFinished(int sessionId, boolean success) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onFinished,
                    mCallback, sessionId, success).recycleOnUse());
        }
    }

    /** {@hide} */
    @Deprecated
    public void addSessionCallback(@NonNull SessionCallback callback) {
        registerSessionCallback(callback);
    }

    /**
     * Register to watch for session lifecycle events. No special permissions
     * are required to watch for these events.
     */
    public void registerSessionCallback(@NonNull SessionCallback callback) {
        registerSessionCallback(callback, new Handler());
    }

    /** {@hide} */
    @Deprecated
    public void addSessionCallback(@NonNull SessionCallback callback, @NonNull Handler handler) {
        registerSessionCallback(callback, handler);
    }

    /**
     * Register to watch for session lifecycle events. No special permissions
     * are required to watch for these events.
     *
     * @param handler to dispatch callback events through, otherwise uses
     *            calling thread.
     */
    public void registerSessionCallback(@NonNull SessionCallback callback, @NonNull Handler handler) {
        synchronized (mDelegates) {
            final SessionCallbackDelegate delegate = new SessionCallbackDelegate(callback,
                    new HandlerExecutor(handler));
            try {
                mInstaller.registerCallback(delegate, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mDelegates.add(delegate);
        }
    }

    /** {@hide} */
    @Deprecated
    public void removeSessionCallback(@NonNull SessionCallback callback) {
        unregisterSessionCallback(callback);
    }

    /**
     * Unregister a previously registered callback.
     */
    public void unregisterSessionCallback(@NonNull SessionCallback callback) {
        synchronized (mDelegates) {
            for (Iterator<SessionCallbackDelegate> i = mDelegates.iterator(); i.hasNext();) {
                final SessionCallbackDelegate delegate = i.next();
                if (delegate.mCallback == callback) {
                    try {
                        mInstaller.unregisterCallback(delegate);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    i.remove();
                }
            }
        }
    }

    /**
     * An installation that is being actively staged. For an install to succeed,
     * all existing and new packages must have identical package names, version
     * codes, and signing certificates.
     * <p>
     * A session may contain any number of split packages. If the application
     * does not yet exist, this session must include a base package.
     * <p>
     * If an APK included in this session is already defined by the existing
     * installation (for example, the same split name), the APK in this session
     * will replace the existing APK.
     * <p>
     * In such a case that multiple packages need to be commited simultaneously,
     * multiple sessions can be referenced by a single multi-package session.
     * This session is created with no package name and calling
     * {@link SessionParams#setMultiPackage()} with {@code true}. The
     * individual session IDs can be added with {@link #addChildSessionId(int)}
     * and commit of the multi-package session will result in all child sessions
     * being committed atomically.
     */
    public static class Session implements Closeable {
        /** {@hide} */
        protected final IPackageInstallerSession mSession;

        /** {@hide} */
        public Session(IPackageInstallerSession session) {
            mSession = session;
        }

        /** {@hide} */
        @Deprecated
        public void setProgress(float progress) {
            setStagingProgress(progress);
        }

        /**
         * Set current progress of staging this session. Valid values are
         * anywhere between 0 and 1.
         * <p>
         * Note that this progress may not directly correspond to the value
         * reported by {@link SessionCallback#onProgressChanged(int, float)}, as
         * the system may carve out a portion of the overall progress to
         * represent its own internal installation work.
         */
        public void setStagingProgress(float progress) {
            try {
                mSession.setClientProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /** {@hide} */
        @UnsupportedAppUsage
        public void addProgress(float progress) {
            try {
                mSession.addClientProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Open a stream to write an APK file into the session.
         * <p>
         * The returned stream will start writing data at the requested offset
         * in the underlying file, which can be used to resume a partially
         * written file. If a valid file length is specified, the system will
         * preallocate the underlying disk space to optimize placement on disk.
         * It's strongly recommended to provide a valid file length when known.
         * <p>
         * You can write data into the returned stream, optionally call
         * {@link #fsync(OutputStream)} as needed to ensure bytes have been
         * persisted to disk, and then close when finished. All streams must be
         * closed before calling {@link #commit(IntentSender)}.
         *
         * @param name arbitrary, unique name of your choosing to identify the
         *            APK being written. You can open a file again for
         *            additional writes (such as after a reboot) by using the
         *            same name. This name is only meaningful within the context
         *            of a single install session.
         * @param offsetBytes offset into the file to begin writing at, or 0 to
         *            start at the beginning of the file.
         * @param lengthBytes total size of the file being written, used to
         *            preallocate the underlying disk space, or -1 if unknown.
         *            The system may clear various caches as needed to allocate
         *            this space.
         * @throws IOException if trouble opening the file for writing, such as
         *             lack of disk space or unavailable media.
         * @throws SecurityException if called after the session has been
         *             sealed or abandoned
         */
        public @NonNull OutputStream openWrite(@NonNull String name, long offsetBytes,
                long lengthBytes) throws IOException {
            try {
                if (ENABLE_REVOCABLE_FD) {
                    return new ParcelFileDescriptor.AutoCloseOutputStream(
                            mSession.openWrite(name, offsetBytes, lengthBytes));
                } else {
                    final ParcelFileDescriptor clientSocket = mSession.openWrite(name,
                            offsetBytes, lengthBytes);
                    return new FileBridge.FileBridgeOutputStream(clientSocket);
                }
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /** {@hide} */
        public void write(@NonNull String name, long offsetBytes, long lengthBytes,
                @NonNull ParcelFileDescriptor fd) throws IOException {
            try {
                mSession.write(name, offsetBytes, lengthBytes, fd);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Ensure that any outstanding data for given stream has been committed
         * to disk. This is only valid for streams returned from
         * {@link #openWrite(String, long, long)}.
         */
        public void fsync(@NonNull OutputStream out) throws IOException {
            if (ENABLE_REVOCABLE_FD) {
                if (out instanceof ParcelFileDescriptor.AutoCloseOutputStream) {
                    try {
                        Os.fsync(((ParcelFileDescriptor.AutoCloseOutputStream) out).getFD());
                    } catch (ErrnoException e) {
                        throw e.rethrowAsIOException();
                    }
                } else {
                    throw new IllegalArgumentException("Unrecognized stream");
                }
            } else {
                if (out instanceof FileBridge.FileBridgeOutputStream) {
                    ((FileBridge.FileBridgeOutputStream) out).fsync();
                } else {
                    throw new IllegalArgumentException("Unrecognized stream");
                }
            }
        }

        /**
         * Return all APK names contained in this session.
         * <p>
         * This returns all names which have been previously written through
         * {@link #openWrite(String, long, long)} as part of this session.
         *
         * @throws SecurityException if called after the session has been
         *             committed or abandoned.
         */
        public @NonNull String[] getNames() throws IOException {
            try {
                return mSession.getNames();
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Open a stream to read an APK file from the session.
         * <p>
         * This is only valid for names which have been previously written
         * through {@link #openWrite(String, long, long)} as part of this
         * session. For example, this stream may be used to calculate a
         * {@link MessageDigest} of a written APK before committing.
         *
         * @throws SecurityException if called after the session has been
         *             committed or abandoned.
         */
        public @NonNull InputStream openRead(@NonNull String name) throws IOException {
            try {
                final ParcelFileDescriptor pfd = mSession.openRead(name);
                return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Removes a split.
         * <p>
         * Split removals occur prior to adding new APKs. If upgrading a feature
         * split, it is not expected nor desirable to remove the split prior to
         * upgrading.
         * <p>
         * When split removal is bundled with new APKs, the packageName must be
         * identical.
         */
        public void removeSplit(@NonNull String splitName) throws IOException {
            try {
                mSession.removeSplit(splitName);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Attempt to commit everything staged in this session. This may require
         * user intervention, and so it may not happen immediately. The final
         * result of the commit will be reported through the given callback.
         * <p>
         * Once this method is called, the session is sealed and no additional
         * mutations may be performed on the session. If the device reboots
         * before the session has been finalized, you may commit the session again.
         * <p>
         * If the installer is the device owner or the affiliated profile owner, there will be no
         * user intervention.
         *
         * @param statusReceiver Called when the state of the session changes. Intents
         *                       sent to this receiver contain {@link #EXTRA_STATUS}. Refer to the
         *                       individual status codes on how to handle them.
         *
         * @throws SecurityException if streams opened through
         *             {@link #openWrite(String, long, long)} are still open.
         *
         * @see android.app.admin.DevicePolicyManager
         */
        public void commit(@NonNull IntentSender statusReceiver) {
            try {
                mSession.commit(statusReceiver, false);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Attempt to commit a session that has been {@link #transfer(String) transferred}.
         *
         * <p>If the device reboots before the session has been finalized, you may commit the
         * session again.
         *
         * <p>The caller of this method is responsible to ensure the safety of the session. As the
         * session was created by another - usually less trusted - app, it is paramount that before
         * committing <u>all</u> public and system {@link SessionInfo properties of the session}
         * and <u>all</u> {@link #openRead(String) APKs} are verified by the caller. It might happen
         * that new properties are added to the session with a new API revision. In this case the
         * callers need to be updated.
         *
         * @param statusReceiver Called when the state of the session changes. Intents
         *                       sent to this receiver contain {@link #EXTRA_STATUS}. Refer to the
         *                       individual status codes on how to handle them.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.INSTALL_PACKAGES)
        public void commitTransferred(@NonNull IntentSender statusReceiver) {
            try {
                mSession.commit(statusReceiver, true);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Transfer the session to a new owner.
         * <p>
         * Only sessions that update the installing app can be transferred.
         * <p>
         * After the transfer to a package with a different uid all method calls on the session
         * will cause {@link SecurityException}s.
         * <p>
         * Once this method is called, the session is sealed and no additional mutations beside
         * committing it may be performed on the session.
         *
         * @param packageName The package of the new owner. Needs to hold the INSTALL_PACKAGES
         *                    permission.
         *
         * @throws PackageManager.NameNotFoundException if the new owner could not be found.
         * @throws SecurityException if called after the session has been committed or abandoned.
         * @throws SecurityException if the session does not update the original installer
         * @throws SecurityException if streams opened through
         *                           {@link #openWrite(String, long, long) are still open.
         */
        public void transfer(@NonNull String packageName)
                throws PackageManager.NameNotFoundException {
            Preconditions.checkNotNull(packageName);

            try {
                mSession.transfer(packageName);
            } catch (ParcelableException e) {
                e.maybeRethrow(PackageManager.NameNotFoundException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Release this session object. You can open the session again if it
         * hasn't been finalized.
         */
        @Override
        public void close() {
            try {
                mSession.close();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Completely abandon this session, destroying all staged data and
         * rendering it invalid. Abandoned sessions will be reported to
         * {@link SessionCallback} listeners as failures. This is equivalent to
         * opening the session and calling {@link Session#abandon()}.
         */
        public void abandon() {
            try {
                mSession.abandon();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return {@code true} if this session will commit more than one package when it is
         * committed.
         */
        public boolean isMultiPackage() {
            try {
                return mSession.isMultiPackage();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return {@code true} if this session will be staged and applied at next reboot.
         */
        public boolean isStaged() {
            try {
                return mSession.isStaged();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return the session ID of the multi-package session that this belongs to or
         * {@link SessionInfo#INVALID_ID} if it does not belong to a multi-package session.
         */
        public int getParentSessionId() {
            try {
                return mSession.getParentSessionId();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return the set of session IDs that will be committed atomically when this session is
         * committed if this is a multi-package session or null if none exist.
         */
        @NonNull
        public int[] getChildSessionIds() {
            try {
                return mSession.getChildSessionIds();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Adds a session ID to the set of sessions that will be committed atomically
         * when this session is committed.
         *
         * @param sessionId the session ID to add to this multi-package session.
         */
        public void addChildSessionId(int sessionId) {
            try {
                mSession.addChildSessionId(sessionId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Removes a session ID from the set of sessions that will be committed
         * atomically when this session is committed.
         *
         * @param sessionId the session ID to remove from this multi-package session.
         */
        public void removeChildSessionId(int sessionId) {
            try {
                mSession.removeChildSessionId(sessionId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Parameters for creating a new {@link PackageInstaller.Session}.
     */
    public static class SessionParams implements Parcelable {

        /** {@hide} */
        public static final int MODE_INVALID = -1;

        /**
         * Mode for an install session whose staged APKs should fully replace any
         * existing APKs for the target app.
         */
        public static final int MODE_FULL_INSTALL = 1;

        /**
         * Mode for an install session that should inherit any existing APKs for the
         * target app, unless they have been explicitly overridden (based on split
         * name) by the session. For example, this can be used to add one or more
         * split APKs to an existing installation.
         * <p>
         * If there are no existing APKs for the target app, this behaves like
         * {@link #MODE_FULL_INSTALL}.
         */
        public static final int MODE_INHERIT_EXISTING = 2;

        /** {@hide} */
        public static final int UID_UNKNOWN = -1;

        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public int mode = MODE_INVALID;
        /** {@hide} */
        @UnsupportedAppUsage
        public int installFlags;
        /** {@hide} */
        public int installLocation = PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
        /** {@hide} */
        public @InstallReason int installReason = PackageManager.INSTALL_REASON_UNKNOWN;
        /** {@hide} */
        @UnsupportedAppUsage
        public long sizeBytes = -1;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String appPackageName;
        /** {@hide} */
        @UnsupportedAppUsage
        public Bitmap appIcon;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String appLabel;
        /** {@hide} */
        public long appIconLastModified = -1;
        /** {@hide} */
        public Uri originatingUri;
        /** {@hide} */
        @UnsupportedAppUsage
        public int originatingUid = UID_UNKNOWN;
        /** {@hide} */
        public Uri referrerUri;
        /** {@hide} */
        public String abiOverride;
        /** {@hide} */
        public String volumeUuid;
        /** {@hide} */
        public String[] grantedRuntimePermissions;
        /** {@hide} */
        public String installerPackageName;
        /** {@hide} */
        public boolean isMultiPackage;
        /** {@hide} */
        public boolean isStaged;

        /**
         * Construct parameters for a new package install session.
         *
         * @param mode one of {@link #MODE_FULL_INSTALL} or
         *            {@link #MODE_INHERIT_EXISTING} describing how the session
         *            should interact with an existing app.
         */
        public SessionParams(int mode) {
            this.mode = mode;
        }

        /** {@hide} */
        public SessionParams(Parcel source) {
            mode = source.readInt();
            installFlags = source.readInt();
            installLocation = source.readInt();
            installReason = source.readInt();
            sizeBytes = source.readLong();
            appPackageName = source.readString();
            appIcon = source.readParcelable(null);
            appLabel = source.readString();
            originatingUri = source.readParcelable(null);
            originatingUid = source.readInt();
            referrerUri = source.readParcelable(null);
            abiOverride = source.readString();
            volumeUuid = source.readString();
            grantedRuntimePermissions = source.readStringArray();
            installerPackageName = source.readString();
            isMultiPackage = source.readBoolean();
            isStaged = source.readBoolean();
        }

        /** {@hide} */
        public SessionParams copy() {
            SessionParams ret = new SessionParams(mode);
            ret.installFlags = installFlags;
            ret.installLocation = installLocation;
            ret.installReason = installReason;
            ret.sizeBytes = sizeBytes;
            ret.appPackageName = appPackageName;
            ret.appIcon = appIcon;  // not a copy.
            ret.appLabel = appLabel;
            ret.originatingUri = originatingUri;  // not a copy, but immutable.
            ret.originatingUid = originatingUid;
            ret.referrerUri = referrerUri;  // not a copy, but immutable.
            ret.abiOverride = abiOverride;
            ret.volumeUuid = volumeUuid;
            ret.grantedRuntimePermissions = grantedRuntimePermissions;
            ret.installerPackageName = installerPackageName;
            ret.isMultiPackage = isMultiPackage;
            ret.isStaged = isStaged;
            return ret;
        }

        /**
         * Check if there are hidden options set.
         *
         * <p>Hidden options are those options that cannot be verified via public or system-api
         * methods on {@link SessionInfo}.
         *
         * @return {@code true} if any hidden option is set.
         *
         * @hide
         */
        public boolean areHiddenOptionsSet() {
            return (installFlags & (PackageManager.INSTALL_ALLOW_DOWNGRADE
                    | PackageManager.INSTALL_DONT_KILL_APP
                    | PackageManager.INSTALL_INSTANT_APP
                    | PackageManager.INSTALL_FULL_APP
                    | PackageManager.INSTALL_VIRTUAL_PRELOAD
                    | PackageManager.INSTALL_ALLOCATE_AGGRESSIVE)) != installFlags
                    || abiOverride != null || volumeUuid != null;
        }

        /**
         * Provide value of {@link PackageInfo#installLocation}, which may be used
         * to determine where the app will be staged. Defaults to
         * {@link PackageInfo#INSTALL_LOCATION_INTERNAL_ONLY}.
         */
        public void setInstallLocation(int installLocation) {
            this.installLocation = installLocation;
        }

        /**
         * Optionally indicate the total size (in bytes) of all APKs that will be
         * delivered in this session. The system may use this to ensure enough disk
         * space exists before proceeding, or to estimate container size for
         * installations living on external storage.
         *
         * @see PackageInfo#INSTALL_LOCATION_AUTO
         * @see PackageInfo#INSTALL_LOCATION_PREFER_EXTERNAL
         */
        public void setSize(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        /**
         * Optionally set the package name of the app being installed. It's strongly
         * recommended that you provide this value when known, so that observers can
         * communicate installing apps to users.
         * <p>
         * If the APKs staged in the session aren't consistent with this package
         * name, the install will fail. Regardless of this value, all APKs in the
         * app must have the same package name.
         */
        public void setAppPackageName(@Nullable String appPackageName) {
            this.appPackageName = appPackageName;
        }

        /**
         * Optionally set an icon representing the app being installed. This should
         * be roughly {@link ActivityManager#getLauncherLargeIconSize()} in both
         * dimensions.
         */
        public void setAppIcon(@Nullable Bitmap appIcon) {
            this.appIcon = appIcon;
        }

        /**
         * Optionally set a label representing the app being installed.
         */
        public void setAppLabel(@Nullable CharSequence appLabel) {
            this.appLabel = (appLabel != null) ? appLabel.toString() : null;
        }

        /**
         * Optionally set the URI where this package was downloaded from. This is
         * informational and may be used as a signal for anti-malware purposes.
         *
         * @see Intent#EXTRA_ORIGINATING_URI
         */
        public void setOriginatingUri(@Nullable Uri originatingUri) {
            this.originatingUri = originatingUri;
        }

        /**
         * Sets the UID that initiated the package installation. This is informational
         * and may be used as a signal for anti-malware purposes.
         */
        public void setOriginatingUid(int originatingUid) {
            this.originatingUid = originatingUid;
        }

        /**
         * Optionally set the URI that referred you to install this package. This is
         * informational and may be used as a signal for anti-malware purposes.
         *
         * @see Intent#EXTRA_REFERRER
         */
        public void setReferrerUri(@Nullable Uri referrerUri) {
            this.referrerUri = referrerUri;
        }

        /**
         * Sets which runtime permissions to be granted to the package at installation.
         *
         * @param permissions The permissions to grant or null to grant all runtime
         *     permissions.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS)
        public void setGrantedRuntimePermissions(String[] permissions) {
            installFlags |= PackageManager.INSTALL_GRANT_RUNTIME_PERMISSIONS;
            this.grantedRuntimePermissions = permissions;
        }

        /**
         * Request that rollbacks be enabled for the given upgrade.
         * @hide
         */
        @SystemApi
        public void setEnableRollback() {
            installFlags |= PackageManager.INSTALL_ENABLE_ROLLBACK;
        }

        /** {@hide} */
        @SystemApi
        public void setAllowDowngrade(boolean allowDowngrade) {
            if (allowDowngrade) {
                installFlags |= PackageManager.INSTALL_ALLOW_DOWNGRADE;
            } else {
                installFlags &= ~PackageManager.INSTALL_ALLOW_DOWNGRADE;
            }
        }

        /** {@hide} */
        public void setInstallFlagsForcePermissionPrompt() {
            installFlags |= PackageManager.INSTALL_FORCE_PERMISSION_PROMPT;
        }

        /** {@hide} */
        @SystemApi
        public void setDontKillApp(boolean dontKillApp) {
            if (dontKillApp) {
                installFlags |= PackageManager.INSTALL_DONT_KILL_APP;
            } else {
                installFlags &= ~PackageManager.INSTALL_DONT_KILL_APP;
            }
        }

        /** {@hide} */
        @SystemApi
        public void setInstallAsInstantApp(boolean isInstantApp) {
            if (isInstantApp) {
                installFlags |= PackageManager.INSTALL_INSTANT_APP;
                installFlags &= ~PackageManager.INSTALL_FULL_APP;
            } else {
                installFlags &= ~PackageManager.INSTALL_INSTANT_APP;
                installFlags |= PackageManager.INSTALL_FULL_APP;
            }
        }

        /**
         * Sets the install as a virtual preload. Will only have effect when called
         * by the verifier.
         * {@hide}
         */
        @SystemApi
        public void setInstallAsVirtualPreload() {
            installFlags |= PackageManager.INSTALL_VIRTUAL_PRELOAD;
        }

        /**
         * Set the reason for installing this package.
         * <p>
         * The install reason should be a pre-defined integer. The behavior is
         * undefined if other values are used.
         *
         * @see PackageManager#INSTALL_REASON_UNKNOWN
         * @see PackageManager#INSTALL_REASON_POLICY
         * @see PackageManager#INSTALL_REASON_DEVICE_RESTORE
         * @see PackageManager#INSTALL_REASON_DEVICE_SETUP
         * @see PackageManager#INSTALL_REASON_USER
         */
        public void setInstallReason(@InstallReason int installReason) {
            this.installReason = installReason;
        }

        /** {@hide} */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.ALLOCATE_AGGRESSIVE)
        public void setAllocateAggressive(boolean allocateAggressive) {
            if (allocateAggressive) {
                installFlags |= PackageManager.INSTALL_ALLOCATE_AGGRESSIVE;
            } else {
                installFlags &= ~PackageManager.INSTALL_ALLOCATE_AGGRESSIVE;
            }
        }

        /**
         * Set the installer package for the app.
         *
         * By default this is the app that created the {@link PackageInstaller} object.
         *
         * @param installerPackageName name of the installer package
         * {@hide}
         */
        public void setInstallerPackageName(String installerPackageName) {
            this.installerPackageName = installerPackageName;
        }

        /**
         * Set this session to be the parent of a multi-package install.
         *
         * A multi-package install session contains no APKs and only references other install
         * sessions via ID. When a multi-package session is committed, all of its children
         * are committed to the system in an atomic manner. If any children fail to install,
         * all of them do, including the multi-package session.
         */
        public void setMultiPackage() {
            this.isMultiPackage = true;
        }

        /**
         * Set this session to be staged to be installed at reboot.
         *
         * Staged sessions are scheduled to be installed at next reboot. Staged sessions can also be
         * multi-package. In that case, if any of the children sessions fail to install at reboot,
         * all the other children sessions are aborted as well.
         *
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(Manifest.permission.INSTALL_PACKAGES)
        public void setStaged() {
            this.isStaged = true;
        }

        /**
         * Set this session to be installing an APEX package.
         */
        public void setInstallAsApex() {
            installFlags |= PackageManager.INSTALL_APEX;
        }

        /** {@hide} */
        public void dump(IndentingPrintWriter pw) {
            pw.printPair("mode", mode);
            pw.printHexPair("installFlags", installFlags);
            pw.printPair("installLocation", installLocation);
            pw.printPair("sizeBytes", sizeBytes);
            pw.printPair("appPackageName", appPackageName);
            pw.printPair("appIcon", (appIcon != null));
            pw.printPair("appLabel", appLabel);
            pw.printPair("originatingUri", originatingUri);
            pw.printPair("originatingUid", originatingUid);
            pw.printPair("referrerUri", referrerUri);
            pw.printPair("abiOverride", abiOverride);
            pw.printPair("volumeUuid", volumeUuid);
            pw.printPair("grantedRuntimePermissions", grantedRuntimePermissions);
            pw.printPair("installerPackageName", installerPackageName);
            pw.printPair("isMultiPackage", isMultiPackage);
            pw.printPair("isStaged", isStaged);
            pw.println();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mode);
            dest.writeInt(installFlags);
            dest.writeInt(installLocation);
            dest.writeInt(installReason);
            dest.writeLong(sizeBytes);
            dest.writeString(appPackageName);
            dest.writeParcelable(appIcon, flags);
            dest.writeString(appLabel);
            dest.writeParcelable(originatingUri, flags);
            dest.writeInt(originatingUid);
            dest.writeParcelable(referrerUri, flags);
            dest.writeString(abiOverride);
            dest.writeString(volumeUuid);
            dest.writeStringArray(grantedRuntimePermissions);
            dest.writeString(installerPackageName);
            dest.writeBoolean(isMultiPackage);
            dest.writeBoolean(isStaged);
        }

        public static final Parcelable.Creator<SessionParams>
                CREATOR = new Parcelable.Creator<SessionParams>() {
                    @Override
                    public SessionParams createFromParcel(Parcel p) {
                        return new SessionParams(p);
                    }

                    @Override
                    public SessionParams[] newArray(int size) {
                        return new SessionParams[size];
                    }
                };
    }

    /**
     * Details for an active install session.
     */
    public static class SessionInfo implements Parcelable {

        /**
         * A session ID that does not exist or is invalid.
         */
        public static final int INVALID_ID = -1;
        /** {@hide} */
        private static final int[] NO_SESSIONS = {};

        /** @hide */
        @IntDef(prefix = { "STAGED_SESSION_" }, value = {
                STAGED_SESSION_NO_ERROR,
                STAGED_SESSION_VERIFICATION_FAILED,
                STAGED_SESSION_ACTIVATION_FAILED,
                STAGED_SESSION_UNKNOWN})
        @Retention(RetentionPolicy.SOURCE)
        public @interface StagedSessionErrorCode{}
        /**
         * Constant indicating that no error occurred during the preparation or the activation of
         * this staged session.
         */
        public static final int STAGED_SESSION_NO_ERROR = 0;

        /**
         * Constant indicating that an error occurred during the verification phase (pre-reboot) of
         * this staged session.
         */
        public static final int STAGED_SESSION_VERIFICATION_FAILED = 1;

        /**
         * Constant indicating that an error occurred during the activation phase (post-reboot) of
         * this staged session.
         */
        public static final int STAGED_SESSION_ACTIVATION_FAILED = 2;

        /**
         * Constant indicating that an unknown error occurred while processing this staged session.
         */
        public static final int STAGED_SESSION_UNKNOWN = 3;

        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public int sessionId;
        /** {@hide} */
        public int userId;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String installerPackageName;
        /** {@hide} */
        @UnsupportedAppUsage
        public String resolvedBaseCodePath;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public float progress;
        /** {@hide} */
        @UnsupportedAppUsage
        public boolean sealed;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public boolean active;

        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public int mode;
        /** {@hide} */
        public @InstallReason int installReason;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public long sizeBytes;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String appPackageName;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public Bitmap appIcon;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public CharSequence appLabel;

        /** {@hide} */
        public int installLocation;
        /** {@hide} */
        public Uri originatingUri;
        /** {@hide} */
        public int originatingUid;
        /** {@hide} */
        public Uri referrerUri;
        /** {@hide} */
        public String[] grantedRuntimePermissions;
        /** {@hide} */
        public int installFlags;
        /** {@hide} */
        public boolean isMultiPackage;
        /** {@hide} */
        public boolean isStaged;
        /** {@hide} */
        public int parentSessionId = INVALID_ID;
        /** {@hide} */
        public int[] childSessionIds = NO_SESSIONS;

        /** {@hide} */
        public boolean isSessionApplied;
        /** {@hide} */
        public boolean isSessionReady;
        /** {@hide} */
        public boolean isSessionFailed;
        private int mStagedSessionErrorCode;
        private String mStagedSessionErrorMessage;

        /** {@hide} */
        @UnsupportedAppUsage
        public SessionInfo() {
        }

        /** {@hide} */
        public SessionInfo(Parcel source) {
            sessionId = source.readInt();
            userId = source.readInt();
            installerPackageName = source.readString();
            resolvedBaseCodePath = source.readString();
            progress = source.readFloat();
            sealed = source.readInt() != 0;
            active = source.readInt() != 0;

            mode = source.readInt();
            installReason = source.readInt();
            sizeBytes = source.readLong();
            appPackageName = source.readString();
            appIcon = source.readParcelable(null);
            appLabel = source.readString();

            installLocation = source.readInt();
            originatingUri = source.readParcelable(null);
            originatingUid = source.readInt();
            referrerUri = source.readParcelable(null);
            grantedRuntimePermissions = source.readStringArray();
            installFlags = source.readInt();
            isMultiPackage = source.readBoolean();
            isStaged = source.readBoolean();
            parentSessionId = source.readInt();
            childSessionIds = source.createIntArray();
            if (childSessionIds == null) {
                childSessionIds = NO_SESSIONS;
            }
            isSessionApplied = source.readBoolean();
            isSessionReady = source.readBoolean();
            isSessionFailed = source.readBoolean();
            mStagedSessionErrorCode = source.readInt();
            mStagedSessionErrorMessage = source.readString();
        }

        /**
         * Return the ID for this session.
         */
        public int getSessionId() {
            return sessionId;
        }

        /**
         * Return the user associated with this session.
         */
        public UserHandle getUser() {
            return new UserHandle(userId);
        }

        /**
         * Return the package name of the app that owns this session.
         */
        public @Nullable String getInstallerPackageName() {
            return installerPackageName;
        }

        /**
         * Return current overall progress of this session, between 0 and 1.
         * <p>
         * Note that this progress may not directly correspond to the value
         * reported by
         * {@link PackageInstaller.Session#setStagingProgress(float)}, as the
         * system may carve out a portion of the overall progress to represent
         * its own internal installation work.
         */
        public float getProgress() {
            return progress;
        }

        /**
         * Return if this session is currently active.
         * <p>
         * A session is considered active whenever there is ongoing forward
         * progress being made, such as the installer holding an open
         * {@link Session} instance while streaming data into place, or the
         * system optimizing code as the result of
         * {@link Session#commit(IntentSender)}.
         * <p>
         * If the installer closes the {@link Session} without committing, the
         * session is considered inactive until the installer opens the session
         * again.
         */
        public boolean isActive() {
            return active;
        }

        /**
         * Return if this session is sealed.
         * <p>
         * Once sealed, no further changes may be made to the session. A session
         * is sealed the moment {@link Session#commit(IntentSender)} is called.
         */
        public boolean isSealed() {
            return sealed;
        }

        /**
         * Return the reason for installing this package.
         *
         * @return The install reason.
         */
        public @InstallReason int getInstallReason() {
            return installReason;
        }

        /** {@hide} */
        @Deprecated
        public boolean isOpen() {
            return isActive();
        }

        /**
         * Return the package name this session is working with. May be {@code null}
         * if unknown.
         */
        public @Nullable String getAppPackageName() {
            return appPackageName;
        }

        /**
         * Return an icon representing the app being installed. May be {@code null}
         * if unavailable.
         */
        public @Nullable Bitmap getAppIcon() {
            if (appIcon == null) {
                // Icon may have been omitted for calls that return bulk session
                // lists, so try fetching the specific icon.
                try {
                    final SessionInfo info = AppGlobals.getPackageManager().getPackageInstaller()
                            .getSessionInfo(sessionId);
                    appIcon = (info != null) ? info.appIcon : null;
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            return appIcon;
        }

        /**
         * Return a label representing the app being installed. May be {@code null}
         * if unavailable.
         */
        public @Nullable CharSequence getAppLabel() {
            return appLabel;
        }

        /**
         * Return an Intent that can be started to view details about this install
         * session. This may surface actions such as pause, resume, or cancel.
         * <p>
         * In some cases, a matching Activity may not exist, so ensure you safeguard
         * against this.
         *
         * @see PackageInstaller#ACTION_SESSION_DETAILS
         */
        public @Nullable Intent createDetailsIntent() {
            final Intent intent = new Intent(PackageInstaller.ACTION_SESSION_DETAILS);
            intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
            intent.setPackage(installerPackageName);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }

        /**
         * Get the mode of the session as set in the constructor of the {@link SessionParams}.
         *
         * @return One of {@link SessionParams#MODE_FULL_INSTALL}
         *         or {@link SessionParams#MODE_INHERIT_EXISTING}
         */
        public int getMode() {
            return mode;
        }

        /**
         * Get the value set in {@link SessionParams#setInstallLocation(int)}.
         */
        public int getInstallLocation() {
            return installLocation;
        }

        /**
         * Get the value as set in {@link SessionParams#setSize(long)}.
         *
         * <p>The value is a hint and does not have to match the actual size.
         */
        public long getSize() {
            return sizeBytes;
        }

        /**
         * Get the value set in {@link SessionParams#setOriginatingUri(Uri)}.
         */
        public @Nullable Uri getOriginatingUri() {
            return originatingUri;
        }

        /**
         * Get the value set in {@link SessionParams#setOriginatingUid(int)}.
         */
        public int getOriginatingUid() {
            return originatingUid;
        }

        /**
         * Get the value set in {@link SessionParams#setReferrerUri(Uri)}
         */
        public @Nullable Uri getReferrerUri() {
            return referrerUri;
        }

        /**
         * Get the value set in {@link SessionParams#setGrantedRuntimePermissions(String[])}.
         *
         * @hide
         */
        @SystemApi
        public @Nullable String[] getGrantedRuntimePermissions() {
            return grantedRuntimePermissions;
        }

        /**
         * Get the value set in {@link SessionParams#setAllowDowngrade(boolean)}.
         *
         * @hide
         */
        @SystemApi
        public boolean getAllowDowngrade() {
            return (installFlags & PackageManager.INSTALL_ALLOW_DOWNGRADE) != 0;
        }

        /**
         * Get the value set in {@link SessionParams#setDontKillApp(boolean)}.
         *
         * @hide
         */
        @SystemApi
        public boolean getDontKillApp() {
            return (installFlags & PackageManager.INSTALL_DONT_KILL_APP) != 0;
        }

        /**
         * If {@link SessionParams#setInstallAsInstantApp(boolean)} was called with {@code true},
         * return true. If it was called with {@code false} or if it was not called return false.
         *
         * @hide
         *
         * @see #getInstallAsFullApp
         */
        @SystemApi
        public boolean getInstallAsInstantApp(boolean isInstantApp) {
            return (installFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
        }

        /**
         * If {@link SessionParams#setInstallAsInstantApp(boolean)} was called with {@code false},
         * return true. If it was called with {@code true} or if it was not called return false.
         *
         * @hide
         *
         * @see #getInstallAsInstantApp
         */
        @SystemApi
        public boolean getInstallAsFullApp(boolean isInstantApp) {
            return (installFlags & PackageManager.INSTALL_FULL_APP) != 0;
        }

        /**
         * Get if {@link SessionParams#setInstallAsVirtualPreload()} was called.
         *
         * @hide
         */
        @SystemApi
        public boolean getInstallAsVirtualPreload() {
            return (installFlags & PackageManager.INSTALL_VIRTUAL_PRELOAD) != 0;
        }

        /**
         * Get the value set in {@link SessionParams#setAllocateAggressive(boolean)}.
         *
         * @hide
         */
        @SystemApi
        public boolean getAllocateAggressive() {
            return (installFlags & PackageManager.INSTALL_ALLOCATE_AGGRESSIVE) != 0;
        }


        /** {@hide} */
        @Deprecated
        public @Nullable Intent getDetailsIntent() {
            return createDetailsIntent();
        }

        /**
         * Returns true if this session is a multi-package session containing references to other
         * sessions.
         */
        public boolean isMultiPackage() {
            return isMultiPackage;
        }

        /**
         * Returns true if this session is a staged session which will be applied at next reboot.
         */
        public boolean isStaged() {
            return isStaged;
        }

        /**
         * Returns the parent multi-package session ID if this session belongs to one,
         * {@link #INVALID_ID} otherwise.
         */
        public int getParentSessionId() {
            return parentSessionId;
        }

        /**
         * Returns the set of session IDs that will be committed when this session is commited if
         * this session is a multi-package session.
         */
        public int[] getChildSessionIds() {
            return childSessionIds;
        }

        /**
         * Whether the staged session has been applied successfully, meaning that all of its
         * packages have been activated and no further action is required.
         * Only meaningful if {@code isStaged} is true.
         */
        public boolean isSessionApplied() {
            return isSessionApplied;
        }

        /**
         * Whether the staged session is ready to be applied at next reboot. Only meaningful if
         * {@code isStaged} is true.
         */
        public boolean isSessionReady() {
            return isSessionReady;
        }

        /**
         * Whether something went wrong and the staged session is declared as failed, meaning that
         * it will be ignored at next reboot. Only meaningful if {@code isStaged} is true.
         */
        public boolean isSessionFailed() {
            return isSessionFailed;
        }

        /**
         * If something went wrong with a staged session, clients can check this error code to
         * understand which kind of failure happened. Only meaningful if {@code isStaged} is true.
         */
        public int getStagedSessionErrorCode() {
            return mStagedSessionErrorCode;
        }

        /**
         * Text description of the error code returned by {@code getStagedSessionErrorCode}, or
         * empty string if no error was encountered.
         */
        public String getStagedSessionErrorMessage() {
            return mStagedSessionErrorMessage;
        }

        /** {@hide} */
        public void setStagedSessionErrorCode(@StagedSessionErrorCode int errorCode,
                                              String errorMessage) {
            mStagedSessionErrorCode = errorCode;
            mStagedSessionErrorMessage = errorMessage;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(sessionId);
            dest.writeInt(userId);
            dest.writeString(installerPackageName);
            dest.writeString(resolvedBaseCodePath);
            dest.writeFloat(progress);
            dest.writeInt(sealed ? 1 : 0);
            dest.writeInt(active ? 1 : 0);

            dest.writeInt(mode);
            dest.writeInt(installReason);
            dest.writeLong(sizeBytes);
            dest.writeString(appPackageName);
            dest.writeParcelable(appIcon, flags);
            dest.writeString(appLabel != null ? appLabel.toString() : null);

            dest.writeInt(installLocation);
            dest.writeParcelable(originatingUri, flags);
            dest.writeInt(originatingUid);
            dest.writeParcelable(referrerUri, flags);
            dest.writeStringArray(grantedRuntimePermissions);
            dest.writeInt(installFlags);
            dest.writeBoolean(isMultiPackage);
            dest.writeBoolean(isStaged);
            dest.writeInt(parentSessionId);
            dest.writeIntArray(childSessionIds);
            dest.writeBoolean(isSessionApplied);
            dest.writeBoolean(isSessionReady);
            dest.writeBoolean(isSessionFailed);
            dest.writeInt(mStagedSessionErrorCode);
            dest.writeString(mStagedSessionErrorMessage);
        }

        public static final Parcelable.Creator<SessionInfo>
                CREATOR = new Parcelable.Creator<SessionInfo>() {
                    @Override
                    public SessionInfo createFromParcel(Parcel p) {
                        return new SessionInfo(p);
                    }

                    @Override
                    public SessionInfo[] newArray(int size) {
                        return new SessionInfo[size];
                    }
                };
    }
}

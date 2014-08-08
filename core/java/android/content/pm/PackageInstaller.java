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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.PackageDeleteObserver;
import android.app.PackageInstallObserver;
import android.content.Intent;
import android.os.Bundle;
import android.os.FileBridge;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.ExceptionUtils;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Offers the ability to install, upgrade, and remove applications on the
 * device. This includes support for apps packaged either as a single
 * "monolithic" APK, or apps packaged as multiple "split" APKs.
 * <p>
 * An app is delivered for installation through a
 * {@link PackageInstaller.Session}, which any app can create. Once the session
 * is created, the installer can stream one or more APKs into place until it
 * decides to either commit or destroy the session. Committing may require user
 * intervention to complete the installation.
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
 */
public class PackageInstaller {
    /**
     * Activity Action: Show details about a particular install session. This
     * may surface actions such as pause, resume, or cancel.
     * <p>
     * This should always be scoped to the installer package that owns the
     * session. Clients should use {@link InstallSessionInfo#getDetailsIntent()}
     * to build this intent correctly.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SESSION_DETAILS = "android.content.pm.action.SESSION_DETAILS";

    /** {@hide} */
    public static final String
            ACTION_CONFIRM_PERMISSIONS = "android.content.pm.action.CONFIRM_PERMISSIONS";

    /**
     * An integer session ID.
     *
     * @see #ACTION_SESSION_DETAILS
     */
    public static final String EXTRA_SESSION_ID = "android.content.pm.extra.SESSION_ID";

    /** {@hide} */
    public static final String EXTRA_CALLBACK = "android.content.pm.extra.CALLBACK";

    private final PackageManager mPm;
    private final IPackageInstaller mInstaller;
    private final int mUserId;
    private final String mInstallerPackageName;

    private final ArrayList<SessionCallbackDelegate> mDelegates = new ArrayList<>();

    /** {@hide} */
    public PackageInstaller(PackageManager pm, IPackageInstaller installer,
            String installerPackageName, int userId) {
        mPm = pm;
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
     * @return positive, non-zero unique ID that represents the created session.
     *         This ID remains consistent across device reboots until the
     *         session is finalized. IDs are not reused during a given boot.
     */
    public int createSession(@NonNull InstallSessionParams params) throws IOException {
        try {
            return mInstaller.createSession(params, mInstallerPackageName, mUserId);
        } catch (RuntimeException e) {
            ExceptionUtils.maybeUnwrapIOException(e);
            throw e;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Open an existing session to actively perform work. To succeed, the caller
     * must be the owner of the install session.
     */
    public @NonNull Session openSession(int sessionId) {
        try {
            return new Session(mInstaller.openSession(sessionId));
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Return details for a specific session. To succeed, the caller must either
     * own this session, or be the current home app.
     */
    public @Nullable InstallSessionInfo getSessionInfo(int sessionId) {
        try {
            return mInstaller.getSessionInfo(sessionId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Return list of all active install sessions, regardless of the installer.
     * To succeed, the caller must be the current home app.
     */
    public @NonNull List<InstallSessionInfo> getAllSessions() {
        try {
            return mInstaller.getAllSessions(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Return list of all install sessions owned by the calling app.
     */
    public @NonNull List<InstallSessionInfo> getMySessions() {
        try {
            return mInstaller.getMySessions(mInstallerPackageName, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Uninstall the given package, removing it completely from the device. This
     * method is only available to the current "installer of record" for the
     * package.
     */
    public void uninstall(@NonNull String packageName, @NonNull UninstallCallback callback) {
        try {
            mInstaller.uninstall(packageName, 0,
                    new UninstallCallbackDelegate(callback).getBinder(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Uninstall only a specific split from the given package.
     *
     * @hide
     */
    public void uninstall(@NonNull String packageName, @NonNull String splitName,
            @NonNull UninstallCallback callback) {
        try {
            mInstaller.uninstallSplit(packageName, splitName, 0,
                    new UninstallCallbackDelegate(callback).getBinder(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /** {@hide} */
    public void setPermissionsResult(int sessionId, boolean accepted) {
        try {
            mInstaller.setPermissionsResult(sessionId, accepted);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
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
         * Session has been opened. A session is usually opened when the
         * installer is actively writing data.
         */
        public abstract void onOpened(int sessionId);

        /**
         * Progress for given session has been updated.
         * <p>
         * Note that this progress may not directly correspond to the value
         * reported by {@link PackageInstaller.Session#setProgress(float)}, as
         * the system may carve out a portion of the overall progress to
         * represent its own internal installation work.
         */
        public abstract void onProgressChanged(int sessionId, float progress);

        /**
         * Session has been closed.
         */
        public abstract void onClosed(int sessionId);

        /**
         * Session has completely finished, either with success or failure.
         */
        public abstract void onFinished(int sessionId, boolean success);
    }

    /** {@hide} */
    private static class SessionCallbackDelegate extends IPackageInstallerCallback.Stub implements
            Handler.Callback {
        private static final int MSG_SESSION_CREATED = 1;
        private static final int MSG_SESSION_OPENED = 2;
        private static final int MSG_SESSION_PROGRESS_CHANGED = 3;
        private static final int MSG_SESSION_CLOSED = 4;
        private static final int MSG_SESSION_FINISHED = 5;

        final SessionCallback mCallback;
        final Handler mHandler;

        public SessionCallbackDelegate(SessionCallback callback, Looper looper) {
            mCallback = callback;
            mHandler = new Handler(looper, this);
        }

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SESSION_CREATED:
                    mCallback.onCreated(msg.arg1);
                    return true;
                case MSG_SESSION_OPENED:
                    mCallback.onOpened(msg.arg1);
                    return true;
                case MSG_SESSION_PROGRESS_CHANGED:
                    mCallback.onProgressChanged(msg.arg1, (float) msg.obj);
                    return true;
                case MSG_SESSION_CLOSED:
                    mCallback.onClosed(msg.arg1);
                    return true;
                case MSG_SESSION_FINISHED:
                    mCallback.onFinished(msg.arg1, msg.arg2 != 0);
                    return true;
            }
            return false;
        }

        @Override
        public void onSessionCreated(int sessionId) {
            mHandler.obtainMessage(MSG_SESSION_CREATED, sessionId, 0).sendToTarget();
        }

        @Override
        public void onSessionOpened(int sessionId) {
            mHandler.obtainMessage(MSG_SESSION_OPENED, sessionId, 0).sendToTarget();
        }

        @Override
        public void onSessionProgressChanged(int sessionId, float progress) {
            mHandler.obtainMessage(MSG_SESSION_PROGRESS_CHANGED, sessionId, 0, progress)
                    .sendToTarget();
        }

        @Override
        public void onSessionClosed(int sessionId) {
            mHandler.obtainMessage(MSG_SESSION_CLOSED, sessionId, 0).sendToTarget();
        }

        @Override
        public void onSessionFinished(int sessionId, boolean success) {
            mHandler.obtainMessage(MSG_SESSION_FINISHED, sessionId, success ? 1 : 0)
                    .sendToTarget();
        }
    }

    /**
     * Register to watch for session lifecycle events. To succeed, the caller
     * must be the current home app.
     */
    public void addSessionCallback(@NonNull SessionCallback callback) {
        addSessionCallback(callback, new Handler());
    }

    /**
     * Register to watch for session lifecycle events. To succeed, the caller
     * must be the current home app.
     *
     * @param handler to dispatch callback events through, otherwise uses
     *            calling thread.
     */
    public void addSessionCallback(@NonNull SessionCallback callback, @NonNull Handler handler) {
        synchronized (mDelegates) {
            final SessionCallbackDelegate delegate = new SessionCallbackDelegate(callback,
                    handler.getLooper());
            try {
                mInstaller.registerCallback(delegate, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
            mDelegates.add(delegate);
        }
    }

    /**
     * Unregister an existing callback.
     */
    public void removeSessionCallback(@NonNull SessionCallback callback) {
        synchronized (mDelegates) {
            for (Iterator<SessionCallbackDelegate> i = mDelegates.iterator(); i.hasNext();) {
                final SessionCallbackDelegate delegate = i.next();
                if (delegate.mCallback == callback) {
                    try {
                        mInstaller.unregisterCallback(delegate);
                    } catch (RemoteException e) {
                        throw e.rethrowAsRuntimeException();
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
     */
    public static class Session implements Closeable {
        private IPackageInstallerSession mSession;

        /** {@hide} */
        public Session(IPackageInstallerSession session) {
            mSession = session;
        }

        /**
         * Set current progress. Valid values are anywhere between 0 and 1.
         */
        public void setProgress(float progress) {
            try {
                mSession.setClientProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /** {@hide} */
        public void addProgress(float progress) {
            try {
                mSession.addClientProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
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
         * closed before calling {@link #commit(CommitCallback)}.
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
         */
        public @NonNull OutputStream openWrite(@NonNull String name, long offsetBytes,
                long lengthBytes) throws IOException {
            try {
                final ParcelFileDescriptor clientSocket = mSession.openWrite(name,
                        offsetBytes, lengthBytes);
                return new FileBridge.FileBridgeOutputStream(clientSocket.getFileDescriptor());
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Ensure that any outstanding data for given stream has been committed
         * to disk. This is only valid for streams returned from
         * {@link #openWrite(String, long, long)}.
         */
        public void fsync(@NonNull OutputStream out) throws IOException {
            if (out instanceof FileBridge.FileBridgeOutputStream) {
                ((FileBridge.FileBridgeOutputStream) out).fsync();
            } else {
                throw new IllegalArgumentException("Unrecognized stream");
            }
        }

        /**
         * List all APK names contained in this session.
         * <p>
         * This returns all names which have been previously written through
         * {@link #openWrite(String, long, long)} as part of this session.
         */
        public @NonNull String[] list() {
            try {
                return mSession.list();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Open a stream to read an APK file from the session.
         * <p>
         * This is only valid for names which have been previously written
         * through {@link #openWrite(String, long, long)} as part of this
         * session. For example, this stream may be used to calculate a
         * {@link MessageDigest} of a written APK before committing.
         */
        public @NonNull InputStream openRead(@NonNull String name) throws IOException {
            try {
                final ParcelFileDescriptor pfd = mSession.openRead(name);
                return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Attempt to commit everything staged in this session. This may require
         * user intervention, and so it may not happen immediately. The final
         * result of the commit will be reported through the given callback.
         * <p>
         * Once this method is called, no additional mutations may be performed
         * on the session. If the device reboots before the session has been
         * finalized, you may commit the session again.
         */
        public void commit(@NonNull CommitCallback callback) {
            try {
                mSession.commit(new CommitCallbackDelegate(callback).getBinder());
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
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
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Completely abandon this session, destroying all staged data and
         * rendering it invalid.
         */
        public void abandon() {
            try {
                mSession.abandon();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Events for a specific uninstall request.
     */
    public static abstract class UninstallCallback {
        /**
         * Generic unknown failure. The system will always try to provide a more
         * specific failure reason, but in some rare cases this may be
         * delivered.
         */
        public static final int FAILURE_UNKNOWN = 0;

        /**
         * This uninstall was blocked. The package may be required for core
         * system operation, or the user may be restricted. Attempting to
         * uninstall again will have the same result.
         */
        public static final int FAILURE_BLOCKED = 1;

        /**
         * This uninstall was actively aborted. For example, the user declined
         * to uninstall. You may try to uninstall again.
         */
        public static final int FAILURE_ABORTED = 2;

        /**
         * User action is required to proceed. You can start the given intent
         * activity to involve the user and continue.
         * <p>
         * You may choose to immediately launch the intent if the user is
         * actively using your app. However, you should use a notification to
         * guide the user back into your app if not currently active.
         */
        public abstract void onUserActionRequired(Intent intent);

        public abstract void onSuccess();
        public abstract void onFailure(int failureReason, String msg, Bundle extras);
    }

    /** {@hide} */
    private static class UninstallCallbackDelegate extends PackageDeleteObserver {
        private final UninstallCallback target;

        public UninstallCallbackDelegate(UninstallCallback target) {
            this.target = target;
        }

        @Override
        public void onUserActionRequired(Intent intent) {
            target.onUserActionRequired(intent);
        }

        @Override
        public void onPackageDeleted(String basePackageName, int returnCode, String msg) {
            if (returnCode == PackageManager.DELETE_SUCCEEDED) {
                target.onSuccess();
            } else {
                final int failureReason = PackageManager.deleteStatusToFailureReason(returnCode);
                msg = PackageManager.deleteStatusToString(returnCode) + ": " + msg;
                target.onFailure(failureReason, msg, null);
            }
        }
    }

    /**
     * Final result of a session commit request.
     */
    public static abstract class CommitCallback {
        /**
         * Generic unknown failure. The system will always try to provide a more
         * specific failure reason, but in some rare cases this may be
         * delivered.
         */
        public static final int FAILURE_UNKNOWN = 0;

        /**
         * One or more of the APKs included in the session was invalid. For
         * example, they might be malformed, corrupt, incorrectly signed,
         * mismatched, etc. The installer may want to try downloading and
         * installing again.
         */
        public static final int FAILURE_INVALID = 1;

        /**
         * This install session conflicts (or is inconsistent with) with another
         * package already installed on the device. For example, an existing
         * permission, incompatible certificates, etc. The user may be able to
         * uninstall another app to fix the issue.
         * <p>
         * The extras bundle may contain {@link #EXTRA_PACKAGE_NAME} with the
         * specific packages identified as the cause of the conflict.
         */
        public static final int FAILURE_CONFLICT = 2;

        /**
         * This install session failed due to storage issues. For example,
         * the device may be running low on space, or the required external
         * media may be unavailable. The user may be able to help free space
         * or insert the correct media.
         */
        public static final int FAILURE_STORAGE = 3;

        /**
         * This install session is fundamentally incompatible with this
         * device. For example, the package may require a hardware feature
         * that doesn't exist, it may be missing native code for the device
         * ABI, or it requires a newer SDK version, etc. This install would
         * never succeed.
         */
        public static final int FAILURE_INCOMPATIBLE = 4;

        /**
         * This install session failed because it was actively aborted. For
         * example, the user declined requested permissions, or a verifier
         * rejected the session.
         *
         * @see PackageManager#VERIFICATION_REJECT
         */
        public static final int FAILURE_ABORTED = 5;

        public static final String EXTRA_PACKAGE_NAME = "android.content.pm.extra.PACKAGE_NAME";

        /**
         * User action is required to proceed. You can start the given intent
         * activity to involve the user and continue.
         * <p>
         * You may choose to immediately launch the intent if the user is
         * actively using your app. However, you should use a notification to
         * guide the user back into your app if not currently active.
         */
        public abstract void onUserActionRequired(Intent intent);

        public abstract void onSuccess();
        public abstract void onFailure(int failureReason, String msg, Bundle extras);
    }

    /** {@hide} */
    private static class CommitCallbackDelegate extends PackageInstallObserver {
        private final CommitCallback target;

        public CommitCallbackDelegate(CommitCallback target) {
            this.target = target;
        }

        @Override
        public void onUserActionRequired(Intent intent) {
            target.onUserActionRequired(intent);
        }

        @Override
        public void onPackageInstalled(String basePackageName, int returnCode, String msg,
                Bundle extras) {
            if (returnCode == PackageManager.INSTALL_SUCCEEDED) {
                target.onSuccess();
            } else {
                final int failureReason = PackageManager.installStatusToFailureReason(returnCode);
                msg = PackageManager.installStatusToString(returnCode) + ": " + msg;

                if (extras != null) {
                    extras.putString(CommitCallback.EXTRA_PACKAGE_NAME,
                            extras.getString(PackageManager.EXTRA_FAILURE_EXISTING_PACKAGE));
                }

                target.onFailure(failureReason, msg, extras);
            }
        }
    }
}

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

import android.app.PackageInstallObserver;
import android.app.PackageUninstallObserver;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.FileBridge;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.ExceptionUtils;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
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
 * onto an existing app.
 * <p>
 * Apps packaged into multiple split APKs always consist of a single "base" APK
 * (with a {@code null} split name) and zero or more "split" APKs (with unique
 * split names). Any subset of these APKs can be installed together, as long as
 * the following constraints are met:
 * <ul>
 * <li>All APKs must have the exact same package name, version code, and signing
 * certificates.
 * <li>All installations must contain a single base APK.
 * <li>All APKs must have unique split names.
 * </ul>
 */
public class PackageInstaller {
    private final PackageManager mPm;
    private final IPackageInstaller mInstaller;
    private final int mUserId;
    private final String mInstallerPackageName;

    /** {@hide} */
    public PackageInstaller(PackageManager pm, IPackageInstaller installer,
            String installerPackageName, int userId) {
        mPm = pm;
        mInstaller = installer;
        mInstallerPackageName = installerPackageName;
        mUserId = userId;
    }

    /**
     * Quickly test if the given package is already available on the device.
     * This is typically used in multi-user scenarios where another user on the
     * device has already installed the package.
     *
     * @hide
     */
    public boolean isPackageAvailable(String packageName) {
        return mPm.isPackageAvailable(packageName);
    }

    /** {@hide} */
    public void installAvailablePackage(String packageName, PackageInstallObserver observer) {
        int returnCode;
        try {
            returnCode = mPm.installExistingPackage(packageName);
        } catch (NameNotFoundException e) {
            returnCode = PackageManager.INSTALL_FAILED_PACKAGE_CHANGED;
        }
        observer.packageInstalled(packageName, null, returnCode);
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
     */
    public int createSession(InstallSessionParams params) throws IOException {
        try {
            return mInstaller.createSession(mInstallerPackageName, params, mUserId);
        } catch (RuntimeException e) {
            ExceptionUtils.maybeUnwrapIOException(e);
            throw e;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Open an existing session to actively perform work.
     */
    public Session openSession(int sessionId) {
        try {
            return new Session(mInstaller.openSession(sessionId));
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Return list of all active install sessions on the device.
     */
    public List<InstallSessionInfo> getActiveSessions() {
        // TODO: filter based on caller
        // TODO: let launcher app see all active sessions
        try {
            return mInstaller.getSessions(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Uninstall the given package, removing it completely from the device. This
     * method is only available to the current "installer of record" for the
     * package.
     */
    public void uninstall(String packageName, UninstallResultCallback callback) {
        try {
            mInstaller.uninstall(packageName, 0,
                    new UninstallResultCallbackDelegate(callback).getBinder(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Uninstall only a specific split from the given package.
     *
     * @hide
     */
    public void uninstall(String packageName, String splitName, UninstallResultCallback callback) {
        try {
            mInstaller.uninstallSplit(packageName, splitName, 0,
                    new UninstallResultCallbackDelegate(callback).getBinder(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Events for observing session lifecycle.
     */
    public static abstract class SessionObserver {
        private final IPackageInstallerObserver.Stub mBinder = new IPackageInstallerObserver.Stub() {
            @Override
            public void onSessionCreated(InstallSessionInfo info) {
                SessionObserver.this.onCreated(info);
            }

            @Override
            public void onSessionProgress(int sessionId, int progress) {
                SessionObserver.this.onProgress(sessionId, progress);
            }

            @Override
            public void onSessionFinished(int sessionId, boolean success) {
                SessionObserver.this.onFinalized(sessionId, success);
            }
        };

        /** {@hide} */
        public IPackageInstallerObserver getBinder() {
            return mBinder;
        }

        /**
         * New session has been created.
         */
        public abstract void onCreated(InstallSessionInfo info);

        /**
         * Progress for given session has been updated.
         * <p>
         * Note that this progress may not directly correspond to the value
         * reported by {@link PackageInstaller.Session#setProgress(int)}, as the
         * system may carve out a portion of the overall progress to represent
         * its own internal installation work.
         */
        public abstract void onProgress(int sessionId, int progress);

        /**
         * Session has been finalized, either with success or failure.
         */
        public abstract void onFinalized(int sessionId, boolean success);
    }

    /**
     * Register to watch for session lifecycle events.
     */
    public void registerSessionObserver(SessionObserver observer) {
        try {
            mInstaller.registerObserver(observer.getBinder(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Unregister an existing observer.
     */
    public void unregisterSessionObserver(SessionObserver observer) {
        try {
            mInstaller.unregisterObserver(observer.getBinder(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
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
     * If a package included in this session is already defined by the existing
     * installation (for example, the same split name), the package in this
     * session will replace the existing package.
     */
    public static class Session implements Closeable {
        private IPackageInstallerSession mSession;

        /** {@hide} */
        public Session(IPackageInstallerSession session) {
            mSession = session;
        }

        /**
         * Set current progress. Valid values are anywhere between 0 and
         * {@link InstallSessionParams#setProgressMax(int)}.
         */
        public void setProgress(int progress) {
            try {
                mSession.setClientProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /** {@hide} */
        public void addProgress(int progress) {
            try {
                mSession.addClientProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Open an APK file for writing, starting at the given offset. You can
         * then stream data into the file, periodically calling
         * {@link #fsync(OutputStream)} to ensure bytes have been written to
         * disk.
         */
        public OutputStream openWrite(String splitName, long offsetBytes, long lengthBytes)
                throws IOException {
            try {
                final ParcelFileDescriptor clientSocket = mSession.openWrite(splitName,
                        offsetBytes, lengthBytes);
                return new FileBridge.FileBridgeOutputStream(clientSocket.getFileDescriptor());
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw new IOException(e);
            }
        }

        /**
         * Ensure that any outstanding data for given stream has been committed
         * to disk. This is only valid for streams returned from
         * {@link #openWrite(String, long, long)}.
         */
        public void fsync(OutputStream out) throws IOException {
            if (out instanceof FileBridge.FileBridgeOutputStream) {
                ((FileBridge.FileBridgeOutputStream) out).fsync();
            } else {
                throw new IllegalArgumentException("Unrecognized stream");
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
        public void commit(CommitResultCallback callback) {
            try {
                mSession.install(new CommitResultCallbackDelegate(callback).getBinder());
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
            // No resources to release at the moment
        }

        /**
         * Completely destroy this session, rendering it invalid.
         */
        public void destroy() {
            try {
                mSession.destroy();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Final result of an uninstall request.
     */
    public static abstract class UninstallResultCallback {
        public abstract void onSuccess();
        public abstract void onFailure(String msg);
    }

    /** {@hide} */
    private static class UninstallResultCallbackDelegate extends PackageUninstallObserver {
        private final UninstallResultCallback target;

        public UninstallResultCallbackDelegate(UninstallResultCallback target) {
            this.target = target;
        }

        @Override
        public void onUninstallFinished(String basePackageName, int returnCode) {
            final String msg = null;

            switch (returnCode) {
                case PackageManager.DELETE_SUCCEEDED: target.onSuccess(); break;
                case PackageManager.DELETE_FAILED_INTERNAL_ERROR: target.onFailure("DELETE_FAILED_INTERNAL_ERROR: " + msg); break;
                case PackageManager.DELETE_FAILED_DEVICE_POLICY_MANAGER: target.onFailure("DELETE_FAILED_DEVICE_POLICY_MANAGER: " + msg); break;
                case PackageManager.DELETE_FAILED_USER_RESTRICTED: target.onFailure("DELETE_FAILED_USER_RESTRICTED: " + msg); break;
                case PackageManager.DELETE_FAILED_OWNER_BLOCKED: target.onFailure("DELETE_FAILED_OWNER_BLOCKED: " + msg); break;
                default: target.onFailure(msg); break;
            }
        }
    }

    /**
     * Final result of a session commit request.
     */
    public static abstract class CommitResultCallback {
        public abstract void onSuccess();

        /**
         * Generic failure occurred. You can override methods (such as
         * {@link #onFailureInvalid(String)}) to handle more specific categories
         * of failure. By default, those specific categories all flow into this
         * generic failure.
         */
        public abstract void onFailure(String msg);

        /**
         * One or more of the APKs included in the session was invalid. For
         * example, they might be malformed, corrupt, incorrectly signed,
         * mismatched, etc. The installer may want to try downloading and
         * installing again.
         */
        public void onFailureInvalid(String msg) {
            onFailure(msg);
        }

        /**
         * This install session conflicts (or is inconsistent with) with another
         * package already installed on the device. For example, an existing
         * permission, incompatible certificates, etc. The user may be able to
         * uninstall another app to fix the issue.
         *
         * @param otherPackageName if one specific package was identified as the
         *            cause of the conflict, it's named here. If unknown, or
         *            multiple packages, this may be {@code null}.
         */
        public void onFailureConflict(String msg, String otherPackageName) {
            onFailure(msg);
        }

        /**
         * This install session failed due to storage issues. For example,
         * the device may be running low on space, or the required external
         * media may be unavailable. The user may be able to help free space
         * or insert the correct media.
         */
        public void onFailureStorage(String msg) {
            onFailure(msg);
        }

        /**
         * This install session is fundamentally incompatible with this
         * device. For example, the package may require a hardware feature
         * that doesn't exist, it may be missing native code for the device
         * ABI, or it requires a newer SDK version, etc. This install would
         * never succeed.
         */
        public void onFailureIncompatible(String msg) {
            onFailure(msg);
        }
    }

    /** {@hide} */
    private static class CommitResultCallbackDelegate extends PackageInstallObserver {
        private final CommitResultCallback target;

        public CommitResultCallbackDelegate(CommitResultCallback target) {
            this.target = target;
        }

        @Override
        public void packageInstalled(String basePackageName, Bundle extras, int returnCode,
                String msg) {
            final String otherPackage = null;

            switch (returnCode) {
                case PackageManager.INSTALL_SUCCEEDED: target.onSuccess(); break;
                case PackageManager.INSTALL_FAILED_ALREADY_EXISTS: target.onFailureConflict("INSTALL_FAILED_ALREADY_EXISTS: " + msg, otherPackage); break;
                case PackageManager.INSTALL_FAILED_INVALID_APK: target.onFailureInvalid("INSTALL_FAILED_INVALID_APK: " + msg); break;
                case PackageManager.INSTALL_FAILED_INVALID_URI: target.onFailureInvalid("INSTALL_FAILED_INVALID_URI: " + msg); break;
                case PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE: target.onFailureStorage("INSTALL_FAILED_INSUFFICIENT_STORAGE: " + msg); break;
                case PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE: target.onFailureConflict("INSTALL_FAILED_DUPLICATE_PACKAGE: " + msg, otherPackage); break;
                case PackageManager.INSTALL_FAILED_NO_SHARED_USER: target.onFailureConflict("INSTALL_FAILED_NO_SHARED_USER: " + msg, otherPackage); break;
                case PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE: target.onFailureConflict("INSTALL_FAILED_UPDATE_INCOMPATIBLE: " + msg, otherPackage); break;
                case PackageManager.INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: target.onFailureConflict("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: " + msg, otherPackage); break;
                case PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY: target.onFailureIncompatible("INSTALL_FAILED_MISSING_SHARED_LIBRARY: " + msg); break;
                case PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE: target.onFailureConflict("INSTALL_FAILED_REPLACE_COULDNT_DELETE: " + msg, otherPackage); break;
                case PackageManager.INSTALL_FAILED_DEXOPT: target.onFailureInvalid("INSTALL_FAILED_DEXOPT: " + msg); break;
                case PackageManager.INSTALL_FAILED_OLDER_SDK: target.onFailureIncompatible("INSTALL_FAILED_OLDER_SDK: " + msg); break;
                case PackageManager.INSTALL_FAILED_CONFLICTING_PROVIDER: target.onFailureConflict("INSTALL_FAILED_CONFLICTING_PROVIDER: " + msg, otherPackage); break;
                case PackageManager.INSTALL_FAILED_NEWER_SDK: target.onFailureIncompatible("INSTALL_FAILED_NEWER_SDK: " + msg); break;
                case PackageManager.INSTALL_FAILED_TEST_ONLY: target.onFailureInvalid("INSTALL_FAILED_TEST_ONLY: " + msg); break;
                case PackageManager.INSTALL_FAILED_CPU_ABI_INCOMPATIBLE: target.onFailureIncompatible("INSTALL_FAILED_CPU_ABI_INCOMPATIBLE: " + msg); break;
                case PackageManager.INSTALL_FAILED_MISSING_FEATURE: target.onFailureIncompatible("INSTALL_FAILED_MISSING_FEATURE: " + msg); break;
                case PackageManager.INSTALL_FAILED_CONTAINER_ERROR: target.onFailureStorage("INSTALL_FAILED_CONTAINER_ERROR: " + msg); break;
                case PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION: target.onFailureStorage("INSTALL_FAILED_INVALID_INSTALL_LOCATION: " + msg); break;
                case PackageManager.INSTALL_FAILED_MEDIA_UNAVAILABLE: target.onFailureStorage("INSTALL_FAILED_MEDIA_UNAVAILABLE: " + msg); break;
                case PackageManager.INSTALL_FAILED_VERIFICATION_TIMEOUT: target.onFailure("INSTALL_FAILED_VERIFICATION_TIMEOUT: " + msg); break;
                case PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE: target.onFailure("INSTALL_FAILED_VERIFICATION_FAILURE: " + msg); break;
                case PackageManager.INSTALL_FAILED_PACKAGE_CHANGED: target.onFailureInvalid("INSTALL_FAILED_PACKAGE_CHANGED: " + msg); break;
                case PackageManager.INSTALL_FAILED_UID_CHANGED: target.onFailureInvalid("INSTALL_FAILED_UID_CHANGED: " + msg); break;
                case PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE: target.onFailureInvalid("INSTALL_FAILED_VERSION_DOWNGRADE: " + msg); break;
                case PackageManager.INSTALL_PARSE_FAILED_NOT_APK: target.onFailureInvalid("INSTALL_PARSE_FAILED_NOT_APK: " + msg); break;
                case PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST: target.onFailureInvalid("INSTALL_PARSE_FAILED_BAD_MANIFEST: " + msg); break;
                case PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: target.onFailureInvalid("INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: " + msg); break;
                case PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES: target.onFailureInvalid("INSTALL_PARSE_FAILED_NO_CERTIFICATES: " + msg); break;
                case PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: target.onFailureInvalid("INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: " + msg); break;
                case PackageManager.INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING: target.onFailureInvalid("INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING: " + msg); break;
                case PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME: target.onFailureInvalid("INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME: " + msg); break;
                case PackageManager.INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID: target.onFailureInvalid("INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID: " + msg); break;
                case PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED: target.onFailureInvalid("INSTALL_PARSE_FAILED_MANIFEST_MALFORMED: " + msg); break;
                case PackageManager.INSTALL_PARSE_FAILED_MANIFEST_EMPTY: target.onFailureInvalid("INSTALL_PARSE_FAILED_MANIFEST_EMPTY: " + msg); break;
                case PackageManager.INSTALL_FAILED_INTERNAL_ERROR: target.onFailure("INSTALL_FAILED_INTERNAL_ERROR: " + msg); break;
                case PackageManager.INSTALL_FAILED_USER_RESTRICTED: target.onFailureIncompatible("INSTALL_FAILED_USER_RESTRICTED: " + msg); break;
                case PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION: target.onFailureConflict("INSTALL_FAILED_DUPLICATE_PERMISSION: " + msg, otherPackage); break;
                case PackageManager.INSTALL_FAILED_NO_MATCHING_ABIS: target.onFailureInvalid("INSTALL_FAILED_NO_MATCHING_ABIS: " + msg); break;
                default: target.onFailure(msg); break;
            }
        }
    }
}

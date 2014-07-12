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

import java.io.Closeable;
import java.io.OutputStream;
import java.util.List;

/** {@hide} */
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

    public boolean isPackageAvailable(String packageName) {
        try {
            final ApplicationInfo info = mPm.getApplicationInfo(packageName,
                    PackageManager.GET_UNINSTALLED_PACKAGES);
            return ((info.flags & ApplicationInfo.FLAG_INSTALLED) != 0);
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    public void installAvailablePackage(String packageName, PackageInstallObserver observer) {
        int returnCode;
        try {
            returnCode = mPm.installExistingPackage(packageName);
        } catch (NameNotFoundException e) {
            returnCode = PackageManager.INSTALL_FAILED_PACKAGE_CHANGED;
        }
        observer.packageInstalled(packageName, null, returnCode);
    }

    public int createSession(InstallSessionParams params) {
        try {
            return mInstaller.createSession(mInstallerPackageName, params, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public Session openSession(int sessionId) {
        try {
            return new Session(mInstaller.openSession(sessionId));
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public List<InstallSessionInfo> getSessions() {
        // TODO: filter based on caller
        // TODO: let launcher app see all active sessions
        try {
            return mInstaller.getSessions(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public void uninstall(String packageName, UninstallResultCallback callback) {
        try {
            mInstaller.uninstall(packageName, 0,
                    new UninstallResultCallbackDelegate(callback).getBinder(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public void uninstall(String packageName, String splitName, UninstallResultCallback callback) {
        try {
            mInstaller.uninstallSplit(packageName, splitName, 0,
                    new UninstallResultCallbackDelegate(callback).getBinder(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

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
                SessionObserver.this.onFinished(sessionId, success);
            }
        };

        /** {@hide} */
        public IPackageInstallerObserver getBinder() {
            return mBinder;
        }

        public abstract void onCreated(InstallSessionInfo info);
        public abstract void onProgress(int sessionId, int progress);
        public abstract void onFinished(int sessionId, boolean success);
    }

    public void registerObserver(SessionObserver observer) {
        // TODO: consider restricting to current launcher app
        try {
            mInstaller.registerObserver(observer.getBinder(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public void unregisterObserver(SessionObserver observer) {
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

        public void updateProgress(int progress) {
            try {
                mSession.updateProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Open an APK file for writing, starting at the given offset. You can
         * then stream data into the file, periodically calling
         * {@link OutputStream#flush()} to ensure bytes have been written to
         * disk.
         */
        public OutputStream openWrite(String splitName, long offsetBytes, long lengthBytes) {
            try {
                final ParcelFileDescriptor clientSocket = mSession.openWrite(splitName,
                        offsetBytes, lengthBytes);
                return new FileBridge.FileBridgeOutputStream(clientSocket.getFileDescriptor());
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        public void install(InstallResultCallback callback) {
            try {
                mSession.install(new InstallResultCallbackDelegate(callback).getBinder());
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        @Override
        public void close() {
            // No resources to release at the moment
        }

        public void destroy() {
            try {
                mSession.destroy();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }
    }

    public static abstract class UninstallResultCallback {
        public abstract void onSuccess();
        public abstract void onFailure(String msg);
    }

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

    public static abstract class InstallResultCallback {
        /**
         * The session installed successfully.
         */
        public abstract void onSuccess();

        /**
         * General unclassified failure. You may be interested in overriding
         * more granular classifications.
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
         * This install session conflicts (or is inconsistent with) with
         * another package already installed on the device. For example, an
         * existing permission, incompatible certificates, etc. The user may
         * be able to uninstall another app to fix the issue.
         */
        public void onFailureConflict(String msg, String packageName) {
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

    private static class InstallResultCallbackDelegate extends PackageInstallObserver {
        private final InstallResultCallback target;

        public InstallResultCallbackDelegate(InstallResultCallback target) {
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

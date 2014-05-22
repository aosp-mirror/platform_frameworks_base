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
import android.os.FileBridge;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.OutputStream;

/** {@hide} */
public class PackageInstaller {
    private final PackageManager mPm;
    private final IPackageInstaller mInstaller;
    private final int mUserId;
    private final String mInstallerPackageName;

    /** {@hide} */
    public PackageInstaller(PackageManager pm, IPackageInstaller installer, int userId,
            String installerPackageName) {
        mPm = pm;
        mInstaller = installer;
        mUserId = userId;
        mInstallerPackageName = installerPackageName;
    }

    public boolean isPackageAvailable(String basePackageName) {
        try {
            final ApplicationInfo info = mPm.getApplicationInfo(basePackageName,
                    PackageManager.GET_UNINSTALLED_PACKAGES);
            return ((info.flags & ApplicationInfo.FLAG_INSTALLED) != 0);
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    public void installAvailablePackage(String basePackageName, PackageInstallObserver observer) {
        int returnCode;
        try {
            returnCode = mPm.installExistingPackage(basePackageName);
        } catch (NameNotFoundException e) {
            returnCode = PackageManager.INSTALL_FAILED_PACKAGE_CHANGED;
        }
        observer.packageInstalled(basePackageName, null, returnCode);
    }

    public int createSession(PackageInstallerParams params) {
        try {
            return mInstaller.createSession(mUserId, mInstallerPackageName, params);
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

    public int[] getSessions() {
        try {
            return mInstaller.getSessions(mUserId, mInstallerPackageName);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public void uninstall(String basePackageName, PackageUninstallObserver observer) {
        try {
            mInstaller.uninstall(mUserId, basePackageName, observer.getBinder());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public void uninstall(String basePackageName, String splitName,
            PackageUninstallObserver observer) {
        try {
            mInstaller.uninstallSplit(mUserId, basePackageName, splitName, observer.getBinder());
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
    public class Session {
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

        public void install(PackageInstallObserver observer) {
            try {
                mSession.install(observer.getBinder());
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

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
}

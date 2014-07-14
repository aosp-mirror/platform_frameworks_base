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

import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallerObserver;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.InstallSessionInfo;
import android.content.pm.InstallSessionParams;
import android.os.ParcelFileDescriptor;

/** {@hide} */
interface IPackageInstaller {
    int createSession(String installerPackageName, in InstallSessionParams params, int userId);
    IPackageInstallerSession openSession(int sessionId);

    List<InstallSessionInfo> getSessions(int userId);

    void registerObserver(IPackageInstallerObserver observer, int userId);
    void unregisterObserver(IPackageInstallerObserver observer, int userId);

    void uninstall(String packageName, int flags, in IPackageDeleteObserver observer, int userId);
    void uninstallSplit(String packageName, String splitName, int flags, in IPackageDeleteObserver observer, int userId);
}

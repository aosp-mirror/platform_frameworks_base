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

import android.content.pm.Checksum;
import android.content.pm.DataLoaderParamsParcel;
import android.content.pm.IOnChecksumsReadyListener;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.PackageInstaller;
import android.content.IntentSender;
import android.os.ParcelFileDescriptor;

/** {@hide} */
interface IPackageInstallerSession {
    void setClientProgress(float progress);
    void addClientProgress(float progress);

    String[] getNames();

    ParcelFileDescriptor openWrite(String name, long offsetBytes, long lengthBytes);
    ParcelFileDescriptor openRead(String name);

    void write(String name, long offsetBytes, long lengthBytes, in ParcelFileDescriptor fd);
    void stageViaHardLink(String target);

    void setChecksums(String name, in Checksum[] checksums, in byte[] signature);
    void requestChecksums(in String name, int optional, int required, in List trustedInstallers, in IOnChecksumsReadyListener onChecksumsReadyListener);

    void removeSplit(String splitName);

    void close();
    void commit(in IntentSender statusReceiver, boolean forTransferred);
    void transfer(in String packageName);
    void abandon();
    void seal();
    List<String> fetchPackageNames();

    DataLoaderParamsParcel getDataLoaderParams();
    void addFile(int location, String name, long lengthBytes, in byte[] metadata, in byte[] signature);
    void removeFile(int location, String name);

    boolean isMultiPackage();
    int[] getChildSessionIds();
    void addChildSessionId(in int sessionId);
    void removeChildSessionId(in int sessionId);
    int getParentSessionId();

    boolean isStaged();
    int getInstallFlags();

    void requestUserPreapproval(in PackageInstaller.PreapprovalDetails details, in IntentSender statusReceiver);

    boolean isApplicationEnabledSettingPersistent();
    boolean isRequestUpdateOwnership();

    ParcelFileDescriptor getAppMetadataFd();
    ParcelFileDescriptor openWriteAppMetadata();
}

/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.app;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.content.pm.ContainerEncryptionParams;
import android.content.pm.PackageInfoLite;
import android.content.res.ObbInfo;

interface IMediaContainerService {
    String copyResourceToContainer(in Uri packageURI, String containerId, String key,
            String resFileName, String publicResFileName, boolean isExternal,
            boolean isForwardLocked);
    int copyResource(in Uri packageURI, in ContainerEncryptionParams encryptionParams,
            in ParcelFileDescriptor outStream);
    PackageInfoLite getMinimalPackageInfo(in String packagePath, in int flags, in long threshold);
    boolean checkInternalFreeStorage(in Uri fileUri, boolean isForwardLocked, in long threshold);
    boolean checkExternalFreeStorage(in Uri fileUri, boolean isForwardLocked);
    ObbInfo getObbInfo(in String filename);
    long calculateDirectorySize(in String directory);
    /** Return file system stats: [0] is total bytes, [1] is available bytes */
    long[] getFileSystemStats(in String path);
    void clearDirectory(in String directory);
}

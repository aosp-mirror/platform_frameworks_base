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
import android.content.pm.PackageInfoLite;
import android.content.res.ObbInfo;

interface IMediaContainerService {
    String copyResourceToContainer(in Uri packageURI,
                String containerId,
                String key, String resFileName);
    int copyResource(in Uri packageURI,
                in ParcelFileDescriptor outStream);
    PackageInfoLite getMinimalPackageInfo(in Uri fileUri, in int flags, in long threshold);
    boolean checkInternalFreeStorage(in Uri fileUri, in long threshold);
    boolean checkExternalFreeStorage(in Uri fileUri);
    ObbInfo getObbInfo(in String filename);
    long calculateDirectorySize(in String directory);
}

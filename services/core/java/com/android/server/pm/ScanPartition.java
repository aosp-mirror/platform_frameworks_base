/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import static com.android.server.pm.PackageManagerService.SCAN_AS_APK_IN_APEX;
import static com.android.server.pm.PackageManagerService.SCAN_AS_FACTORY;
import static com.android.server.pm.PackageManagerService.SCAN_AS_ODM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_OEM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_PRODUCT;
import static com.android.server.pm.PackageManagerService.SCAN_AS_SYSTEM_EXT;
import static com.android.server.pm.PackageManagerService.SCAN_AS_VENDOR;
import static com.android.server.pm.PackageManagerService.SCAN_DROP_CACHE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackagePartitions;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;

/**
 * List of partitions to be scanned during system boot
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class ScanPartition extends PackagePartitions.SystemPartition {
    @PackageManagerService.ScanFlags
    public final int scanFlag;

    @Nullable
    public final ApexManager.ActiveApexInfo apexInfo;

    public ScanPartition(@NonNull PackagePartitions.SystemPartition partition) {
        super(partition);
        scanFlag = scanFlagForPartition(partition);
        apexInfo = null;
    }

    /**
     * Creates a partition containing the same folders as the original partition but with a
     * different root folder. The new partition will include the scan flags of the original
     * partition along with any specified additional scan flags.
     */
    public ScanPartition(@NonNull File folder, @NonNull ScanPartition original,
            @Nullable ApexManager.ActiveApexInfo apexInfo) {
        super(folder, original);
        var scanFlags = original.scanFlag;
        this.apexInfo = apexInfo;
        if (apexInfo != null) {
            scanFlags |= SCAN_AS_APK_IN_APEX;
            if (apexInfo.isFactory) {
                scanFlags |= SCAN_AS_FACTORY;
            }
            if (apexInfo.activeApexChanged) {
                scanFlags |= SCAN_DROP_CACHE;
            }
        }
        //noinspection WrongConstant
        this.scanFlag = scanFlags;
    }

    private static int scanFlagForPartition(PackagePartitions.SystemPartition partition) {
        switch (partition.type) {
            case PackagePartitions.PARTITION_SYSTEM:
                return 0;
            case PackagePartitions.PARTITION_VENDOR:
                return SCAN_AS_VENDOR;
            case PackagePartitions.PARTITION_ODM:
                return SCAN_AS_ODM;
            case PackagePartitions.PARTITION_OEM:
                return SCAN_AS_OEM;
            case PackagePartitions.PARTITION_PRODUCT:
                return SCAN_AS_PRODUCT;
            case PackagePartitions.PARTITION_SYSTEM_EXT:
                return SCAN_AS_SYSTEM_EXT;
            default:
                throw new IllegalStateException("Unable to determine scan flag for "
                        + partition.getFolder());
        }
    }

    @Override
    public String toString() {
        return getFolder().getAbsolutePath() + ":" + scanFlag;
    }
}

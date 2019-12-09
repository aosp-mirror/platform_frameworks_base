/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.pm.parsing;

import android.annotation.Nullable;
import android.content.pm.PackageParser;
import android.content.pm.SharedLibraryInfo;

import java.util.List;

/**
 * Contains remaining mutable fields after package parsing has completed.
 *
 * Most are state that can probably be tracked outside of the AndroidPackage object. New methods
 * should never be added to this interface.
 *
 * TODO(b/135203078): Remove entirely
 *
 * @deprecated the eventual goal is that the object returned from parsing represents exactly what
 * was parsed from the APK, and so further mutation should be disallowed,
 * with any state being stored in another class
 *
 * @hide
 */
@Deprecated
public interface AndroidPackageWrite extends AndroidPackage {

    AndroidPackageWrite setUsesLibraryFiles(@Nullable String[] usesLibraryFiles);

    // TODO(b/135203078): Remove or use a non-system wide representation of the shared libraries;
    //  this doesn't represent what was parsed from the APK
    AndroidPackageWrite setUsesLibraryInfos(@Nullable List<SharedLibraryInfo> usesLibraryInfos);

    AndroidPackageWrite setHiddenUntilInstalled(boolean hidden);

    AndroidPackageWrite setUpdatedSystemApp(boolean updatedSystemApp);

    AndroidPackageWrite setLastPackageUsageTimeInMills(int reason, long time);

    AndroidPackageWrite setPrimaryCpuAbi(String primaryCpuAbi);

    AndroidPackageWrite setSeInfo(String seInfo);

    AndroidPackageWrite setSigningDetails(PackageParser.SigningDetails signingDetails);
}

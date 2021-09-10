/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

/**
 * Methods that normal consumers should not have access to. This usually means the field is stateful
 * or deprecated and should be access through a utility class or a system manager class.
 * <p>
 * This is a separate interface, not implemented by the base {@link ParsingPackageRead} because Java
 * doesn't support non-public interface methods. The class must be cast to this interface.
 *
 * @hide
 */
interface ParsingPackageHidden {

    /**
     * @see PackageInfo#versionCode
     * @see ApplicationInfo#versionCode
     */
    int getVersionCode();

    /**
     * @see PackageInfo#versionCodeMajor
     */
    int getVersionCodeMajor();

    // TODO(b/135203078): Hide and enforce going through PackageInfoUtils
    ApplicationInfo toAppInfoWithoutState();
}

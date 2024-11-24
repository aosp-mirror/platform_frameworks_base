/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.vcn;

/**
 * Class to represent that VCN is in a mainline module
 *
 * <p>This class is used to check whether VCN is in the non-updatable platform or in a mainline
 * module.
 */
// When VCN is in a mainline module, this class (module/com/android/server/vcn/VcnLocation.java)
// will be built in to the vcn-location-sources filegroup. When VCN is in the non-updatable
// platform, platform/com/android/server/vcn/VcnLocation.java will be built in to the filegroup
public class VcnLocation {
    /** Indicate that VCN is the platform */
    public static final boolean IS_VCN_IN_MAINLINE = true;
}

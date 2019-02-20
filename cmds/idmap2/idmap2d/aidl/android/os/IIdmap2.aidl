/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.os;

/**
 * @hide
 */
interface IIdmap2 {
  const int POLICY_PUBLIC = 0x00000001;
  const int POLICY_SYSTEM_PARTITION = 0x00000002;
  const int POLICY_VENDOR_PARTITION = 0x00000004;
  const int POLICY_PRODUCT_PARTITION = 0x00000008;
  const int POLICY_SIGNATURE = 0x00000010;

  @utf8InCpp String getIdmapPath(@utf8InCpp String overlayApkPath, int userId);
  boolean removeIdmap(@utf8InCpp String overlayApkPath, int userId);
  boolean verifyIdmap(@utf8InCpp String overlayApkPath, int fulfilledPolicies,
                      boolean enforceOverlayable, int userId);
  @nullable @utf8InCpp String createIdmap(@utf8InCpp String targetApkPath,
                                          @utf8InCpp String overlayApkPath,
                                          int fulfilledPolicies,
                                          boolean enforceOverlayable,
                                          int userId);
}

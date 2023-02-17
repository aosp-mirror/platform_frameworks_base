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

import android.os.FabricatedOverlayInfo;
import android.os.FabricatedOverlayInternal;

/**
 * @hide
 */
interface IIdmap2 {
  @utf8InCpp String getIdmapPath(@utf8InCpp String overlayApkPath, int userId);
  boolean removeIdmap(@utf8InCpp String overlayApkPath, int userId);
  boolean verifyIdmap(@utf8InCpp String targetApkPath,
                      @utf8InCpp String overlayApkPath,
                      @utf8InCpp String overlayName,
                      int fulfilledPolicies,
                      boolean enforceOverlayable,
                      int userId);
  @nullable @utf8InCpp String createIdmap(@utf8InCpp String targetApkPath,
                                          @utf8InCpp String overlayApkPath,
                                          @utf8InCpp String overlayName,
                                          int fulfilledPolicies,
                                          boolean enforceOverlayable,
                                          int userId);

  @nullable FabricatedOverlayInfo createFabricatedOverlay(in FabricatedOverlayInternal overlay);
  boolean deleteFabricatedOverlay(@utf8InCpp String path);

  int acquireFabricatedOverlayIterator();
  void releaseFabricatedOverlayIterator(int iteratorId);
  List<FabricatedOverlayInfo> nextFabricatedOverlayInfos(int iteratorId);

  @utf8InCpp String dumpIdmap(@utf8InCpp String overlayApkPath);
}

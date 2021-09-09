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

package com.android.server.pm.pkg;

import android.annotation.SystemApi;

import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PkgAppInfo;
import com.android.server.pm.parsing.pkg.PkgPackageInfo;

/**
 * Explicit interface used for consumers like mainline who need a {@link SystemApi @SystemApi} form
 * of {@link AndroidPackage}.
 * <p>
 * There should be no methods in this class. All of them must come from other interfaces that group
 * the actual methods. This is done to ensure proper separation of the (legacy?) Info object APIs.
 */
// TODO(b/173807334): Expose API
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface AndroidPackageApi extends PkgPackageInfo, PkgAppInfo {

}

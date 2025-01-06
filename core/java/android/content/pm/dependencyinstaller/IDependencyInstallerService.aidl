/**
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

package android.content.pm.dependencyinstaller;

import android.content.pm.dependencyinstaller.DependencyInstallerCallback;
import android.content.pm.SharedLibraryInfo;
import java.util.List;

/**
* Interface used to communicate with the application code that holds the Dependency Installer role.
* {@hide}
*/
oneway interface IDependencyInstallerService {
    /**
     * Notify dependency installer of the required dependencies to complete the current install
     * session.
     *
     * @param neededLibraries the list of shared library dependencies needed to be obtained and
     *                        installed.
     */
    void onDependenciesRequired(in List<SharedLibraryInfo> neededLibraries,
                in DependencyInstallerCallback callback);
 }
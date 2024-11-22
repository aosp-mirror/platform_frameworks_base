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

import java.util.List;

/**
* Callbacks for Dependency Installer. The app side invokes on this interface to indicate
* completion of the async dependency install request given by the system server.
*
* {@hide}
*/
interface IDependencyInstallerCallback {
    /**
     * Callback to indicate that all the requested dependencies have been resolved and have been
     * committed for installation. See {@link  DependencyInstallerService#onDependenciesRequired}.
     *
     * @param sessionIds the install session IDs for all requested dependencies
     */
    void onAllDependenciesResolved(in int[] sessionIds);

    /**
     * Callback to indicate that at least one of the required dependencies could not be resolved
     * and any associated sessions have been abandoned.
     */
    void onFailureToResolveAllDependencies();
}

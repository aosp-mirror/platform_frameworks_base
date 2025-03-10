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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.content.pm.Flags;
import android.content.pm.SharedLibraryInfo;
import android.os.IBinder;

import java.util.List;

/**
 * Service that needs to be implemented by the holder of the DependencyInstaller role. This service
 * will be invoked by the system during application installations if it depends on
 * {@link android.content.pm.SharedLibraryInfo#TYPE_STATIC} or
 * {@link android.content.pm.SharedLibraryInfo#TYPE_SDK_PACKAGE} and those dependencies aren't
 * already installed.
 * <p>
 * Below is an example manifest registration for a {@code DependencyInstallerService}.
 * <pre>
 * {@code
 * <service android:name=".ExampleDependencyInstallerService"
 *     android:permission="android.permission.BIND_DEPENDENCY_INSTALLER" >
 *     ...
 *     <intent-filter>
 *         <action android:name="android.content.pm.action.INSTALL_DEPENDENCY" />
 *     </intent-filter>
 * </service>
 * }
 * </pre>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SDK_DEPENDENCY_INSTALLER)
public abstract class DependencyInstallerService extends Service {

    private IDependencyInstallerService mBinder;

    @Override
    public final @NonNull IBinder onBind(@Nullable Intent intent) {
        if (mBinder == null) {
            mBinder = new IDependencyInstallerService.Stub() {
                @Override
                public void onDependenciesRequired(List<SharedLibraryInfo> neededLibraries,
                        DependencyInstallerCallback callback) {
                    DependencyInstallerService.this.onDependenciesRequired(neededLibraries,
                            callback);
                }
            };
        }
        return mBinder.asBinder();
    }

    /**
     * Notify the holder of the DependencyInstaller role of the missing dependencies required for
     * the completion of an active install session.
     *
     * @param neededLibraries the list of shared library dependencies needed to be obtained and
     *                        installed.
     */
    public abstract void onDependenciesRequired(@NonNull List<SharedLibraryInfo> neededLibraries,
            @NonNull DependencyInstallerCallback callback);
}

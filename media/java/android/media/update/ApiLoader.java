/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.media.update;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.internal.annotations.GuardedBy;

import dalvik.system.PathClassLoader;

import java.io.File;

/**
 * @hide
 */
public final class ApiLoader {
    @GuardedBy("this")
    private static StaticProvider sMediaUpdatable;

    private static final String UPDATE_PACKAGE = "com.android.media.update";
    private static final String UPDATE_CLASS = "com.android.media.update.ApiFactory";
    private static final String UPDATE_METHOD = "initialize";
    private static final boolean REGISTER_UPDATE_DEPENDENCY = true;

    private ApiLoader() { }

    public static StaticProvider getProvider() {
        try {
            return getMediaUpdatable();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (NameNotFoundException | ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // TODO This method may do I/O; Ensure it does not violate (emit warnings in) strict mode.
    private static synchronized StaticProvider getMediaUpdatable()
            throws NameNotFoundException, ReflectiveOperationException, RemoteException {
        if (sMediaUpdatable != null) return sMediaUpdatable;

        // TODO Figure out when to use which package (query media update service)
        int flags = Build.IS_DEBUGGABLE ? 0 : PackageManager.MATCH_SYSTEM_ONLY;
        ApplicationInfo ai = AppGlobals.getPackageManager().getApplicationInfo(
                UPDATE_PACKAGE, flags, UserHandle.myUserId());

        if (REGISTER_UPDATE_DEPENDENCY) {
            // Register a dependency to the updatable in order to be killed during updates
            ActivityManager.getService().addPackageDependency(ai.packageName);
        }

        ClassLoader classLoader = new PathClassLoader(ai.sourceDir,
                ai.nativeLibraryDir + File.pathSeparator + System.getProperty("java.library.path"),
                ClassLoader.getSystemClassLoader().getParent());
        return sMediaUpdatable = (StaticProvider) classLoader.loadClass(UPDATE_CLASS)
                .getMethod(UPDATE_METHOD, ApplicationInfo.class).invoke(null, ai);
    }
}

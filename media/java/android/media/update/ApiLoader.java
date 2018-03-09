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

import android.annotation.NonNull;
import android.content.res.Resources;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * @hide
 */
public final class ApiLoader {
    private static Object sMediaLibrary;

    private static final String UPDATE_PACKAGE = "com.android.media.update";
    private static final String UPDATE_CLASS = "com.android.media.update.ApiFactory";
    private static final String UPDATE_METHOD = "initialize";

    private ApiLoader() { }

    public static StaticProvider getProvider(@NonNull Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context shouldn't be null");
        }
        try {
            return (StaticProvider) getMediaLibraryImpl(context);
        } catch (PackageManager.NameNotFoundException | ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // TODO This method may do I/O; Ensure it does not violate (emit warnings in) strict mode.
    private static synchronized Object getMediaLibraryImpl(Context context)
            throws PackageManager.NameNotFoundException, ReflectiveOperationException {
        if (sMediaLibrary != null) return sMediaLibrary;

        // TODO Figure out when to use which package (query media update service)
        int flags = Build.IS_DEBUGGABLE ? 0 : PackageManager.MATCH_FACTORY_ONLY;
        Context libContext = context.createApplicationContext(
                context.getPackageManager().getPackageInfo(UPDATE_PACKAGE, flags).applicationInfo,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        sMediaLibrary = libContext.getClassLoader()
                .loadClass(UPDATE_CLASS)
                .getMethod(UPDATE_METHOD, Resources.class, Resources.Theme.class)
                .invoke(null, libContext.getResources(), libContext.getTheme());
        return sMediaLibrary;
    }
}

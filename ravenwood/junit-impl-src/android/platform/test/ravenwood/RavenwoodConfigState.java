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
package android.platform.test.ravenwood;

import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_EMPTY_RESOURCES_APK;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.annotation.Nullable;
import android.app.ResourcesManager;
import android.content.res.Resources;
import android.view.DisplayAdjustments;

import java.io.File;
import java.util.HashMap;

/**
 * Used to store various states associated with {@link RavenwoodConfig} that's inly needed
 * in junit-impl.
 *
 * We don't want to put it in junit-src to avoid having to recompile all the downstream
 * dependencies after changing this class.
 *
 * All members must be called from the runner's main thread.
 */
public class RavenwoodConfigState {
    private static final String TAG = "RavenwoodConfigState";

    private final RavenwoodConfig mConfig;

    public RavenwoodConfigState(RavenwoodConfig config) {
        mConfig = config;
    }

    /** Map from path -> resources. */
    private final HashMap<File, Resources> mCachedResources = new HashMap<>();

    /**
     * Load {@link Resources} from an APK, with cache.
     */
    public Resources loadResources(@Nullable File apkPath) {
        var cached = mCachedResources.get(apkPath);
        if (cached != null) {
            return cached;
        }

        var fileToLoad = apkPath != null ? apkPath : new File(RAVENWOOD_EMPTY_RESOURCES_APK);

        assertTrue("File " + fileToLoad + " doesn't exist.", fileToLoad.isFile());

        final String path = fileToLoad.getAbsolutePath();
        final var emptyPaths = new String[0];

        ResourcesManager.getInstance().initializeApplicationPaths(path, emptyPaths);

        final var ret = ResourcesManager.getInstance().getResources(null, path,
                emptyPaths, emptyPaths, emptyPaths,
                emptyPaths, null, null,
                new DisplayAdjustments().getCompatibilityInfo(),
                RavenwoodRuntimeEnvironmentController.class.getClassLoader(), null);

        assertNotNull(ret);

        mCachedResources.put(apkPath, ret);
        return ret;
    }
}

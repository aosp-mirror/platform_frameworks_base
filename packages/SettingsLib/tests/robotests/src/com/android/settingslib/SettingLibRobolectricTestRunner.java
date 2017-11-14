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
package com.android.settingslib;

import org.junit.runners.model.InitializationError;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.Fs;
import org.robolectric.res.ResourcePath;

import java.util.List;

public class SettingLibRobolectricTestRunner extends RobolectricTestRunner {

    public SettingLibRobolectricTestRunner(Class<?> testClass) throws InitializationError {
        super(testClass);
    }

    @Override
    protected AndroidManifest getAppManifest(Config config) {
        // Using the manifest file's relative path, we can figure out the application directory.
        final String appRoot = "frameworks/base/packages/SettingsLib";
        final String manifestPath = appRoot + "/AndroidManifest.xml";
        final String resDir = appRoot + "/tests/robotests/res";
        final String assetsDir = appRoot + config.assetDir();

        final AndroidManifest manifest = new AndroidManifest(Fs.fileFromPath(manifestPath),
                Fs.fileFromPath(resDir), Fs.fileFromPath(assetsDir)) {
            @Override
            public List<ResourcePath> getIncludedResourcePaths() {
                List<ResourcePath> paths = super.getIncludedResourcePaths();
                SettingLibRobolectricTestRunner.getIncludedResourcePaths(getPackageName(), paths);
                return paths;
            }
        };
        manifest.setPackageName("com.android.settingslib");
        return manifest;
    }

    static void getIncludedResourcePaths(String packageName, List<ResourcePath> paths) {
        paths.add(new ResourcePath(
                packageName,
                Fs.fileFromPath("./frameworks/base/packages/SettingsLib/res"),
                null));
        paths.add(new ResourcePath(
                packageName,
                Fs.fileFromPath("./frameworks/base/core/res/res"),
                null));
        paths.add(new ResourcePath(
                packageName,
                Fs.fileFromPath("./frameworks/support/v7/appcompat/res"),
                null));
    }

}

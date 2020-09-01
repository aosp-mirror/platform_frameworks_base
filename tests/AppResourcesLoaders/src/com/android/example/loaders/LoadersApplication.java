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

package com.android.example.loaders;

import android.app.Application;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class LoadersApplication extends Application {
    private static final String TAG = "LoadersApplication";
    private static final String LOADER_RESOURCES_APK = "AppResourcesLoaders_Overlay.apk";

    @Override
    public void onCreate() {
        try {
            final Resources resources = getResources();
            final ResourcesLoader loader = new ResourcesLoader();
            loader.addProvider(ResourcesProvider.loadFromApk(copyResource(LOADER_RESOURCES_APK)));
            resources.addLoaders(loader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load loader resources ", e);
        }
    }

    private ParcelFileDescriptor copyResource(String fileName) throws IOException {
        final File apkFile = new File(getFilesDir(), fileName);
        final InputStream is = getClassLoader().getResourceAsStream(LOADER_RESOURCES_APK);
        final FileOutputStream os = new FileOutputStream(apkFile);
        byte[] buffer = new byte[8192];
        int count;
        while ((count = is.read(buffer)) != -1) {
            os.write(buffer, 0, count);
        }
        return ParcelFileDescriptor.open(apkFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}

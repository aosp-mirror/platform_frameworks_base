/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.content.pm.split;

import android.content.pm.PackageParser;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;

/**
 * Simple interface for loading base Assets and Splits. Used by PackageParser when parsing
 * split APKs.
 *
 * @hide
 */
public interface SplitAssetLoader extends AutoCloseable {
    AssetManager getBaseAssetManager() throws PackageParser.PackageParserException;
    AssetManager getSplitAssetManager(int splitIdx) throws PackageParser.PackageParserException;

    ApkAssets getBaseApkAssets();
}

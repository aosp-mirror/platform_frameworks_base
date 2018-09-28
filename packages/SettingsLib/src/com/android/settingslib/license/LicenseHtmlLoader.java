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

package com.android.settingslib.license;

import static com.android.settingslib.license.LicenseHtmlLoaderCompat.generateHtmlFile;
import static com.android.settingslib.license.LicenseHtmlLoaderCompat.getCachedHtmlFile;
import static com.android.settingslib.license.LicenseHtmlLoaderCompat.getVaildXmlFiles;
import static com.android.settingslib.license.LicenseHtmlLoaderCompat.isCachedHtmlFileOutdated;

import android.content.Context;
import android.util.Log;

import com.android.settingslib.utils.AsyncLoader;

import java.io.File;
import java.util.List;

/**
 * LicenseHtmlLoader is a loader which loads a license html file from default license xml files.
 */
public class LicenseHtmlLoader extends AsyncLoader<File> {
    private static final String TAG = "LicenseHtmlLoader";

    private Context mContext;

    public LicenseHtmlLoader(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public File loadInBackground() {
        return generateHtmlFromDefaultXmlFiles();
    }

    @Override
    protected void onDiscardResult(File f) {
    }

    private File generateHtmlFromDefaultXmlFiles() {
        final List<File> xmlFiles = getVaildXmlFiles();
        if (xmlFiles.isEmpty()) {
            Log.e(TAG, "No notice file exists.");
            return null;
        }

        File cachedHtmlFile = getCachedHtmlFile(mContext);
        if (!isCachedHtmlFileOutdated(xmlFiles, cachedHtmlFile)
                || generateHtmlFile(mContext, xmlFiles, cachedHtmlFile)) {
            return cachedHtmlFile;
        }

        return null;
    }

}

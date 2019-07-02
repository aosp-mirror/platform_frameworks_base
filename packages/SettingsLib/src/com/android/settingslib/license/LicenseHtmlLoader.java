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

import android.content.Context;

import com.android.settingslib.utils.AsyncLoader;

import java.io.File;

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
        return new LicenseHtmlLoaderCompat(mContext).loadInBackground();
    }

    @Override
    protected void onDiscardResult(File f) {
    }
}

/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.updates;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;

public class ApnDbInstallReceiver extends ConfigUpdateInstallReceiver {

    private static final Uri UPDATE_APN_DB = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI,
            "update_db");

    public ApnDbInstallReceiver() {
        super("/data/misc/", "apns-conf.xml", "metadata/", "version");
    }

    @Override
    protected void postInstall(Context context, Intent intent) {
        ContentResolver resolver = context.getContentResolver();
        resolver.delete(UPDATE_APN_DB, null, null);
    }
}

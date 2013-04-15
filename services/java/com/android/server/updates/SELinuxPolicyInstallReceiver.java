/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Context;
import android.content.Intent;
import android.os.SELinux;
import android.provider.Settings;
import android.util.Base64;
import android.util.Slog;

import java.io.IOException;

public class SELinuxPolicyInstallReceiver extends ConfigUpdateInstallReceiver {

    public SELinuxPolicyInstallReceiver() {
        super("/data/security/", "sepolicy", "metadata/", "version");
    }

    @Override
    protected void install(byte[] encodedContent, int version) throws IOException {
        super.install(Base64.decode(encodedContent, Base64.DEFAULT), version);
    }

    @Override
    protected void postInstall(Context context, Intent intent) {
           boolean mode = Settings.Global.getInt(context.getContentResolver(),
                                                Settings.Global.SELINUX_STATUS, 0) == 1;
           SELinux.setSELinuxEnforce(mode);
    }
}

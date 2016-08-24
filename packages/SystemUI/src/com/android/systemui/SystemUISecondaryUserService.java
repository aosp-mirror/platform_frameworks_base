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

package com.android.systemui;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class SystemUISecondaryUserService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        ((SystemUIApplication) getApplication()).startSecondaryUserServicesIfNeeded();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        SystemUI[] services = ((SystemUIApplication) getApplication()).getServices();
        if (args == null || args.length == 0) {
            for (SystemUI ui: services) {
                if (ui != null) {
                    pw.println("dumping service: " + ui.getClass().getName());
                    ui.dump(fd, pw, args);
                }
            }
        } else {
            String svc = args[0];
            for (SystemUI ui: services) {
                if (ui != null) {
                    String name = ui.getClass().getName();
                    if (name.endsWith(svc)) {
                        ui.dump(fd, pw, args);
                    }
                }
            }
        }
    }
}


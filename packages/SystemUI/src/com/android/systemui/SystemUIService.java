/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.annotation.NonNull;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.internal.os.BinderInternal;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.shared.plugins.PluginManagerImpl;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class SystemUIService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        ((SystemUIApplication) getApplication()).startServicesIfNeeded();

        // For debugging RescueParty
        if (Build.IS_DEBUGGABLE && SystemProperties.getBoolean("debug.crash_sysui", false)) {
            throw new RuntimeException();
        }

        if (Build.IS_ENG) {
            // b/71353150 - looking for leaked binder proxies
            BinderInternal.nSetBinderProxyCountEnabled(true);
            BinderInternal.nSetBinderProxyCountWatermarks(1000,900);
            BinderInternal.setBinderProxyCountCallback(
                    new BinderInternal.BinderProxyLimitListener() {
                        @Override
                        public void onLimitReached(int uid) {
                            Slog.w(SystemUIApplication.TAG,
                                    "uid " + uid + " sent too many Binder proxies to uid "
                                    + Process.myUid());
                        }
                    }, Dependency.get(Dependency.MAIN_HANDLER));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (args != null && args.length > 0 && args[0].equals("--config")) {
            dumpConfig(pw);
            return;
        }

        dumpServices(((SystemUIApplication) getApplication()).getServices(), fd, pw, args);
        dumpConfig(pw);
    }

    static void dumpServices(
            SystemUI[] services, FileDescriptor fd, PrintWriter pw, String[] args) {
        if (args == null || args.length == 0) {
            pw.println("dumping service: " + Dependency.class.getName());
            Dependency.staticDump(fd, pw, args);
            for (SystemUI ui: services) {
                pw.println("dumping service: " + ui.getClass().getName());
                ui.dump(fd, pw, args);
            }
            if (Build.IS_DEBUGGABLE) {
                pw.println("dumping plugins:");
                ((PluginManagerImpl) Dependency.get(PluginManager.class)).dump(fd, pw, args);
            }
        } else {
            String svc = args[0].toLowerCase();
            if (Dependency.class.getName().endsWith(svc)) {
                Dependency.staticDump(fd, pw, args);
            }
            for (SystemUI ui: services) {
                String name = ui.getClass().getName().toLowerCase();
                if (name.endsWith(svc)) {
                    ui.dump(fd, pw, args);
                }
            }
        }
    }

    private void dumpConfig(@NonNull PrintWriter pw) {
        pw.println("SystemUiServiceComponents configuration:");

        pw.print("vendor component: ");
        pw.println(getResources().getString(R.string.config_systemUIVendorServiceComponent));

        dumpConfig(pw, "global", R.array.config_systemUIServiceComponents);
        dumpConfig(pw, "per-user", R.array.config_systemUIServiceComponentsPerUser);
    }

    private void dumpConfig(@NonNull PrintWriter pw, @NonNull String type, int resId) {
        final String[] services = getResources().getStringArray(resId);
        pw.print(type); pw.print(": ");
        if (services == null) {
            pw.println("N/A");
            return;
        }
        pw.print(services.length);
        pw.println(" services");
        for (int i = 0; i < services.length; i++) {
            pw.print("  "); pw.print(i); pw.print(": "); pw.println(services[i]);
        }
    }
}


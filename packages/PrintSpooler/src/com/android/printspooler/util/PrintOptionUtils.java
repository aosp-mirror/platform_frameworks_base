/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.printspooler.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.print.PrintManager;
import android.printservice.PrintServiceInfo;

import java.util.List;

public class PrintOptionUtils {

    private PrintOptionUtils() {
        /* ignore - hide constructor */
    }

    /**
     * Gets the advanced options activity name for a print service.
     *
     * @param context Context for accessing system resources.
     * @param serviceName The print service name.
     * @return The advanced options activity name or null.
     */
    public static String getAdvancedOptionsActivityName(Context context,
            ComponentName serviceName) {
        PrintManager printManager = (PrintManager) context.getSystemService(
                Context.PRINT_SERVICE);
        List<PrintServiceInfo> printServices = printManager.getEnabledPrintServices();
        final int printServiceCount = printServices.size();
        for (int i = 0; i < printServiceCount; i ++) {
            PrintServiceInfo printServiceInfo = printServices.get(i);
            ServiceInfo serviceInfo = printServiceInfo.getResolveInfo().serviceInfo;
            if (serviceInfo.name.equals(serviceName.getClassName())
                    && serviceInfo.packageName.equals(serviceName.getPackageName())) {
                return printServiceInfo.getAdvancedOptionsActivityName();
            }
        }
        return null;
    }
}

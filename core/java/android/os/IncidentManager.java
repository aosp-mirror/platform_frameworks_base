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

package android.os;

import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.os.IIncidentManager;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Slog;

/**
 * Class to take an incident report.
 *
 * @hide
 */
@SystemApi
@TestApi
@SystemService(Context.INCIDENT_SERVICE)
public class IncidentManager {
    private static final String TAG = "incident";

    private Context mContext;

    /**
     * @hide
     */
    public IncidentManager(Context context) {
        mContext = context;
    }

    /**
     * Take an incident report and put it in dropbox.
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.DUMP,
            android.Manifest.permission.PACKAGE_USAGE_STATS
    })
    public void reportIncident(IncidentReportArgs args) {
        final IIncidentManager service = IIncidentManager.Stub.asInterface(
                ServiceManager.getService("incident"));
        if (service == null) {
            Slog.e(TAG, "reportIncident can't find incident binder service");
            return;
        }

        try {
            service.reportIncident(args);
        } catch (RemoteException ex) {
            Slog.e(TAG, "reportIncident failed", ex);
        }
    }

    /**
     * Convenience method to trigger an incident report and put it in dropbox.
     * <p>
     * The fields that are reported will be looked up in the system setting named by
     * the settingName parameter.  The setting must match one of these patterns:
     *      The string "disabled": The report will not be taken.
     *      The string "all": The report will taken with all sections.
     *      The string "none": The report will taken with no sections, but with the header.
     *      A comma separated list of field numbers: The report will have these fields.
     * <p>
     * The header parameter will be added as a header for the incident report.  Fill in a
     * {@link android.util.proto.ProtoOutputStream ProtoOutputStream}, and then call the
     * {@link android.util.proto.ProtoOutputStream#bytes bytes()} method to retrieve
     * the encoded data for the header.
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.DUMP,
            android.Manifest.permission.PACKAGE_USAGE_STATS
    })
    public void reportIncident(String settingName, byte[] headerProto) {
        // Sections
        String setting = Settings.System.getString(mContext.getContentResolver(), settingName);
        IncidentReportArgs args;
        try {
            args = IncidentReportArgs.parseSetting(setting);
        } catch (IllegalArgumentException ex) {
            Slog.w(TAG, "Bad value for incident report setting '" + settingName + "'", ex);
            return;
        }
        if (args == null) {
            Slog.i(TAG, "Incident report requested but disabled: " + settingName);
            return;
        }

        // Header
        args.addHeader(headerProto);

        // Look up the service
        final IIncidentManager service = IIncidentManager.Stub.asInterface(
                ServiceManager.getService("incident"));
        if (service == null) {
            Slog.e(TAG, "reportIncident can't find incident binder service");
            return;
        }

        // Call the service
        Slog.i(TAG, "Taking incident report: " + settingName);
        try {
            service.reportIncident(args);
        } catch (RemoteException ex) {
            Slog.e(TAG, "reportIncident failed", ex);
        }
    }
}


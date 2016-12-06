/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Printer;
import android.util.Slog;
import com.android.internal.util.FastPrintWriter;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Describes an application error.
 *
 * A report has a type, which is one of
 * <ul>
 * <li> {@link #TYPE_NONE} uninitialized instance of {@link ApplicationErrorReport}.
 * <li> {@link #TYPE_CRASH} application crash. Information about the crash
 * is stored in {@link #crashInfo}.
 * <li> {@link #TYPE_ANR} application not responding. Information about the
 * ANR is stored in {@link #anrInfo}.
 * <li> {@link #TYPE_BATTERY} user reported application is using too much
 * battery. Information about the battery use is stored in {@link #batteryInfo}.
 * <li> {@link #TYPE_RUNNING_SERVICE} user reported application is leaving an
 * unneeded serive running. Information about the battery use is stored in
 * {@link #runningServiceInfo}.
 * </ul>
 */

public class ApplicationErrorReport implements Parcelable {
    // System property defining error report receiver for system apps
    static final String SYSTEM_APPS_ERROR_RECEIVER_PROPERTY = "ro.error.receiver.system.apps";

    // System property defining default error report receiver
    static final String DEFAULT_ERROR_RECEIVER_PROPERTY = "ro.error.receiver.default";

    /**
     * Uninitialized error report.
     */
    public static final int TYPE_NONE = 0;

    /**
     * An error report about an application crash.
     */
    public static final int TYPE_CRASH = 1;

    /**
     * An error report about an application that's not responding.
     */
    public static final int TYPE_ANR = 2;

    /**
     * An error report about an application that's consuming too much battery.
     */
    public static final int TYPE_BATTERY = 3;

    /**
     * A report from a user to a developer about a running service that the
     * user doesn't think should be running.
     */
    public static final int TYPE_RUNNING_SERVICE = 5;

    /**
     * Type of this report. Can be one of {@link #TYPE_NONE},
     * {@link #TYPE_CRASH}, {@link #TYPE_ANR}, {@link #TYPE_BATTERY},
     * or {@link #TYPE_RUNNING_SERVICE}.
     */
    public int type;

    /**
     * Package name of the application.
     */
    public String packageName;

    /**
     * Package name of the application which installed the application this
     * report pertains to.
     * This identifies which market the application came from.
     */
    public String installerPackageName;

    /**
     * Process name of the application.
     */
    public String processName;

    /**
     * Time at which the error occurred.
     */
    public long time;

    /**
     * Set if the app is on the system image.
     */
    public boolean systemApp;

    /**
     * If this report is of type {@link #TYPE_CRASH}, contains an instance
     * of CrashInfo describing the crash; otherwise null.
     */
    public CrashInfo crashInfo;

    /**
     * If this report is of type {@link #TYPE_ANR}, contains an instance
     * of AnrInfo describing the ANR; otherwise null.
     */
    public AnrInfo anrInfo;

    /**
     * If this report is of type {@link #TYPE_BATTERY}, contains an instance
     * of BatteryInfo; otherwise null.
     */
    public BatteryInfo batteryInfo;

    /**
     * If this report is of type {@link #TYPE_RUNNING_SERVICE}, contains an instance
     * of RunningServiceInfo; otherwise null.
     */
    public RunningServiceInfo runningServiceInfo;

    /**
     * Create an uninitialized instance of {@link ApplicationErrorReport}.
     */
    public ApplicationErrorReport() {
    }

    /**
     * Create an instance of {@link ApplicationErrorReport} initialized from
     * a parcel.
     */
    ApplicationErrorReport(Parcel in) {
        readFromParcel(in);
    }

    public static ComponentName getErrorReportReceiver(Context context,
            String packageName, int appFlags) {
        // check if error reporting is enabled in secure settings
        int enabled = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.SEND_ACTION_APP_ERROR, 0);
        if (enabled == 0) {
            return null;
        }

        PackageManager pm = context.getPackageManager();

        // look for receiver in the installer package
        String candidate = null;
        ComponentName result = null;

        try {
            candidate = pm.getInstallerPackageName(packageName);
        } catch (IllegalArgumentException e) {
            // the package could already removed
        }

        if (candidate != null) {
            result = getErrorReportReceiver(pm, packageName, candidate);
            if (result != null) {
                return result;
            }
        }

        // if the error app is on the system image, look for system apps
        // error receiver
        if ((appFlags&ApplicationInfo.FLAG_SYSTEM) != 0) {
            candidate = SystemProperties.get(SYSTEM_APPS_ERROR_RECEIVER_PROPERTY);
            result = getErrorReportReceiver(pm, packageName, candidate);
            if (result != null) {
                return result;
            }
        }

        // if there is a default receiver, try that
        candidate = SystemProperties.get(DEFAULT_ERROR_RECEIVER_PROPERTY);
        return getErrorReportReceiver(pm, packageName, candidate);
    }

    /**
     * Return activity in receiverPackage that handles ACTION_APP_ERROR.
     *
     * @param pm PackageManager instance
     * @param errorPackage package which caused the error
     * @param receiverPackage candidate package to receive the error
     * @return activity component within receiverPackage which handles
     * ACTION_APP_ERROR, or null if not found
     */
    static ComponentName getErrorReportReceiver(PackageManager pm, String errorPackage,
            String receiverPackage) {
        if (receiverPackage == null || receiverPackage.length() == 0) {
            return null;
        }

        // break the loop if it's the error report receiver package that crashed
        if (receiverPackage.equals(errorPackage)) {
            return null;
        }

        Intent intent = new Intent(Intent.ACTION_APP_ERROR);
        intent.setPackage(receiverPackage);
        ResolveInfo info = pm.resolveActivity(intent, 0);
        if (info == null || info.activityInfo == null) {
            return null;
        }
        return new ComponentName(receiverPackage, info.activityInfo.name);
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(type);
        dest.writeString(packageName);
        dest.writeString(installerPackageName);
        dest.writeString(processName);
        dest.writeLong(time);
        dest.writeInt(systemApp ? 1 : 0);
        dest.writeInt(crashInfo != null ? 1 : 0);

        switch (type) {
            case TYPE_CRASH:
                if (crashInfo != null) {
                    crashInfo.writeToParcel(dest, flags);
                }
                break;
            case TYPE_ANR:
                anrInfo.writeToParcel(dest, flags);
                break;
            case TYPE_BATTERY:
                batteryInfo.writeToParcel(dest, flags);
                break;
            case TYPE_RUNNING_SERVICE:
                runningServiceInfo.writeToParcel(dest, flags);
                break;
        }
    }

    public void readFromParcel(Parcel in) {
        type = in.readInt();
        packageName = in.readString();
        installerPackageName = in.readString();
        processName = in.readString();
        time = in.readLong();
        systemApp = in.readInt() == 1;
        boolean hasCrashInfo = in.readInt() == 1;

        switch (type) {
            case TYPE_CRASH:
                crashInfo = hasCrashInfo ? new CrashInfo(in) : null;
                anrInfo = null;
                batteryInfo = null;
                runningServiceInfo = null;
                break;
            case TYPE_ANR:
                anrInfo = new AnrInfo(in);
                crashInfo = null;
                batteryInfo = null;
                runningServiceInfo = null;
                break;
            case TYPE_BATTERY:
                batteryInfo = new BatteryInfo(in);
                anrInfo = null;
                crashInfo = null;
                runningServiceInfo = null;
                break;
            case TYPE_RUNNING_SERVICE:
                batteryInfo = null;
                anrInfo = null;
                crashInfo = null;
                runningServiceInfo = new RunningServiceInfo(in);
                break;
        }
    }

    /**
     * Describes an application crash.
     */
    public static class CrashInfo {
        /**
         * Class name of the exception that caused the crash.
         */
        public String exceptionClassName;

        /**
         * Message stored in the exception.
         */
        public String exceptionMessage;

        /**
         * File which the exception was thrown from.
         */
        public String throwFileName;

        /**
         * Class which the exception was thrown from.
         */
        public String throwClassName;

        /**
         * Method which the exception was thrown from.
         */
        public String throwMethodName;

        /**
         * Line number the exception was thrown from.
         */
        public int throwLineNumber;

        /**
         * Stack trace.
         */
        public String stackTrace;

        /**
         * Create an uninitialized instance of CrashInfo.
         */
        public CrashInfo() {
        }

        /**
         * Create an instance of CrashInfo initialized from an exception.
         */
        public CrashInfo(Throwable tr) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new FastPrintWriter(sw, false, 256);
            tr.printStackTrace(pw);
            pw.flush();
            stackTrace = sanitizeString(sw.toString());
            exceptionMessage = tr.getMessage();

            // Populate fields with the "root cause" exception
            Throwable rootTr = tr;
            while (tr.getCause() != null) {
                tr = tr.getCause();
                if (tr.getStackTrace() != null && tr.getStackTrace().length > 0) {
                    rootTr = tr;
                }
                String msg = tr.getMessage();
                if (msg != null && msg.length() > 0) {
                    exceptionMessage = msg;
                }
            }

            exceptionClassName = rootTr.getClass().getName();
            if (rootTr.getStackTrace().length > 0) {
                StackTraceElement trace = rootTr.getStackTrace()[0];
                throwFileName = trace.getFileName();
                throwClassName = trace.getClassName();
                throwMethodName = trace.getMethodName();
                throwLineNumber = trace.getLineNumber();
            } else {
                throwFileName = "unknown";
                throwClassName = "unknown";
                throwMethodName = "unknown";
                throwLineNumber = 0;
            }

            exceptionMessage = sanitizeString(exceptionMessage);
        }

        /**
         * Ensure that the string is of reasonable size, truncating from the middle if needed.
         */
        private String sanitizeString(String s) {
            int prefixLength = 10 * 1024;
            int suffixLength = 10 * 1024;
            int acceptableLength = prefixLength + suffixLength;

            if (s != null && s.length() > acceptableLength) {
                String replacement =
                        "\n[TRUNCATED " + (s.length() - acceptableLength) + " CHARS]\n";

                StringBuilder sb = new StringBuilder(acceptableLength + replacement.length());
                sb.append(s.substring(0, prefixLength));
                sb.append(replacement);
                sb.append(s.substring(s.length() - suffixLength));
                return sb.toString();
            }
            return s;
        }

        /**
         * Create an instance of CrashInfo initialized from a Parcel.
         */
        public CrashInfo(Parcel in) {
            exceptionClassName = in.readString();
            exceptionMessage = in.readString();
            throwFileName = in.readString();
            throwClassName = in.readString();
            throwMethodName = in.readString();
            throwLineNumber = in.readInt();
            stackTrace = in.readString();
        }

        /**
         * Save a CrashInfo instance to a parcel.
         */
        public void writeToParcel(Parcel dest, int flags) {
            int start = dest.dataPosition();
            dest.writeString(exceptionClassName);
            dest.writeString(exceptionMessage);
            dest.writeString(throwFileName);
            dest.writeString(throwClassName);
            dest.writeString(throwMethodName);
            dest.writeInt(throwLineNumber);
            dest.writeString(stackTrace);
            int total = dest.dataPosition()-start;
            if (total > 20*1024) {
                Slog.d("Error", "ERR: exClass=" + exceptionClassName);
                Slog.d("Error", "ERR: exMsg=" + exceptionMessage);
                Slog.d("Error", "ERR: file=" + throwFileName);
                Slog.d("Error", "ERR: class=" + throwClassName);
                Slog.d("Error", "ERR: method=" + throwMethodName + " line=" + throwLineNumber);
                Slog.d("Error", "ERR: stack=" + stackTrace);
                Slog.d("Error", "ERR: TOTAL BYTES WRITTEN: " + (dest.dataPosition()-start));
            }
        }

        /**
         * Dump a CrashInfo instance to a Printer.
         */
        public void dump(Printer pw, String prefix) {
            pw.println(prefix + "exceptionClassName: " + exceptionClassName);
            pw.println(prefix + "exceptionMessage: " + exceptionMessage);
            pw.println(prefix + "throwFileName: " + throwFileName);
            pw.println(prefix + "throwClassName: " + throwClassName);
            pw.println(prefix + "throwMethodName: " + throwMethodName);
            pw.println(prefix + "throwLineNumber: " + throwLineNumber);
            pw.println(prefix + "stackTrace: " + stackTrace);
        }
    }

    /**
     * Describes an application not responding error.
     */
    public static class AnrInfo {
        /**
         * Activity name.
         */
        public String activity;

        /**
         * Description of the operation that timed out.
         */
        public String cause;

        /**
         * Additional info, including CPU stats.
         */
        public String info;

        /**
         * Create an uninitialized instance of AnrInfo.
         */
        public AnrInfo() {
        }

        /**
         * Create an instance of AnrInfo initialized from a Parcel.
         */
        public AnrInfo(Parcel in) {
            activity = in.readString();
            cause = in.readString();
            info = in.readString();
        }

        /**
         * Save an AnrInfo instance to a parcel.
         */
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(activity);
            dest.writeString(cause);
            dest.writeString(info);
        }

        /**
         * Dump an AnrInfo instance to a Printer.
         */
        public void dump(Printer pw, String prefix) {
            pw.println(prefix + "activity: " + activity);
            pw.println(prefix + "cause: " + cause);
            pw.println(prefix + "info: " + info);
        }
    }

    /**
     * Describes a battery usage report.
     */
    public static class BatteryInfo {
        /**
         * Percentage of the battery that was used up by the process.
         */
        public int usagePercent;

        /**
         * Duration in microseconds over which the process used the above
         * percentage of battery.
         */
        public long durationMicros;

        /**
         * Dump of various info impacting battery use.
         */
        public String usageDetails;

        /**
         * Checkin details.
         */
        public String checkinDetails;

        /**
         * Create an uninitialized instance of BatteryInfo.
         */
        public BatteryInfo() {
        }

        /**
         * Create an instance of BatteryInfo initialized from a Parcel.
         */
        public BatteryInfo(Parcel in) {
            usagePercent = in.readInt();
            durationMicros = in.readLong();
            usageDetails = in.readString();
            checkinDetails = in.readString();
        }

        /**
         * Save a BatteryInfo instance to a parcel.
         */
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(usagePercent);
            dest.writeLong(durationMicros);
            dest.writeString(usageDetails);
            dest.writeString(checkinDetails);
        }

        /**
         * Dump a BatteryInfo instance to a Printer.
         */
        public void dump(Printer pw, String prefix) {
            pw.println(prefix + "usagePercent: " + usagePercent);
            pw.println(prefix + "durationMicros: " + durationMicros);
            pw.println(prefix + "usageDetails: " + usageDetails);
            pw.println(prefix + "checkinDetails: " + checkinDetails);
        }
    }

    /**
     * Describes a running service report.
     */
    public static class RunningServiceInfo {
        /**
         * Duration in milliseconds that the service has been running.
         */
        public long durationMillis;

        /**
         * Dump of debug information about the service.
         */
        public String serviceDetails;

        /**
         * Create an uninitialized instance of RunningServiceInfo.
         */
        public RunningServiceInfo() {
        }

        /**
         * Create an instance of RunningServiceInfo initialized from a Parcel.
         */
        public RunningServiceInfo(Parcel in) {
            durationMillis = in.readLong();
            serviceDetails = in.readString();
        }

        /**
         * Save a RunningServiceInfo instance to a parcel.
         */
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(durationMillis);
            dest.writeString(serviceDetails);
        }

        /**
         * Dump a BatteryInfo instance to a Printer.
         */
        public void dump(Printer pw, String prefix) {
            pw.println(prefix + "durationMillis: " + durationMillis);
            pw.println(prefix + "serviceDetails: " + serviceDetails);
        }
    }

    public static final Parcelable.Creator<ApplicationErrorReport> CREATOR
            = new Parcelable.Creator<ApplicationErrorReport>() {
        public ApplicationErrorReport createFromParcel(Parcel source) {
            return new ApplicationErrorReport(source);
        }

        public ApplicationErrorReport[] newArray(int size) {
            return new ApplicationErrorReport[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    /**
     * Dump the report to a Printer.
     */
    public void dump(Printer pw, String prefix) {
        pw.println(prefix + "type: " + type);
        pw.println(prefix + "packageName: " + packageName);
        pw.println(prefix + "installerPackageName: " + installerPackageName);
        pw.println(prefix + "processName: " + processName);
        pw.println(prefix + "time: " + time);
        pw.println(prefix + "systemApp: " + systemApp);

        switch (type) {
            case TYPE_CRASH:
                crashInfo.dump(pw, prefix);
                break;
            case TYPE_ANR:
                anrInfo.dump(pw, prefix);
                break;
            case TYPE_BATTERY:
                batteryInfo.dump(pw, prefix);
                break;
            case TYPE_RUNNING_SERVICE:
                runningServiceInfo.dump(pw, prefix);
                break;
        }
    }
}

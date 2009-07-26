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

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Printer;

/**
 * Describes an application error.
 *
 * A report has a type, which is one of
 * <ul>
 * <li> {@link #TYPE_CRASH} application crash. Information about the crash
 * is stored in {@link #crashInfo}.
 * <li> {@link #TYPE_ANR} application not responding. Information about the
 * ANR is stored in {@link #anrInfo}.
 * <li> {@link #TYPE_NONE} uninitialized instance of {@link ApplicationErrorReport}.
 * </ul>
 *
 * @hide
 */

public class ApplicationErrorReport implements Parcelable {
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
     * Type of this report. Can be one of {@link #TYPE_NONE},
     * {@link #TYPE_CRASH} or {@link #TYPE_ANR}.
     */
    public int type;

    /**
     * Package name of the application.
     */
    public String packageName;

    /**
     * Package name of the application which installed the application this
     * report pertains to.
     * This identifies which Market the application came from.
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

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(type);
        dest.writeString(packageName);
        dest.writeString(installerPackageName);
        dest.writeString(processName);
        dest.writeLong(time);

        switch (type) {
            case TYPE_CRASH:
                crashInfo.writeToParcel(dest, flags);
                break;
            case TYPE_ANR:
                anrInfo.writeToParcel(dest, flags);
                break;
        }
    }

    public void readFromParcel(Parcel in) {
        type = in.readInt();
        packageName = in.readString();
        installerPackageName = in.readString();
        processName = in.readString();
        time = in.readLong();

        switch (type) {
            case TYPE_CRASH:
                crashInfo = new CrashInfo(in);
                anrInfo = null;
                break;
            case TYPE_ANR:
                anrInfo = new AnrInfo(in);
                crashInfo = null;
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
         * Stack trace.
         */
        public String stackTrace;

        /**
         * Create an uninitialized instance of CrashInfo.
         */
        public CrashInfo() {
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
            stackTrace = in.readString();
        }

        /**
         * Save a CrashInfo instance to a parcel.
         */
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(exceptionClassName);
            dest.writeString(exceptionMessage);
            dest.writeString(throwFileName);
            dest.writeString(throwClassName);
            dest.writeString(throwMethodName);
            dest.writeString(stackTrace);
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

        switch (type) {
            case TYPE_CRASH:
                crashInfo.dump(pw, prefix);
                break;
            case TYPE_ANR:
                anrInfo.dump(pw, prefix);
                break;
        }
    }
}

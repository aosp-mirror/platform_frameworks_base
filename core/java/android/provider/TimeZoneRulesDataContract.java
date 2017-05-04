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

package android.provider;

import android.net.Uri;

/**
 * A set of constants for implementing a time zone data content provider, which is used by the time
 * zone updater application.
 *
 * @hide
 */
// TODO(nfuller): Expose necessary APIs for OEMs with @SystemApi. http://b/31008728
public final class TimeZoneRulesDataContract {

    private TimeZoneRulesDataContract() {}

    /**
     * The authority that <em>must</em> be used for the time zone data content provider.
     * To be accepted by the time zone updater application it <em>must</em> be exposed by the
     * package specified in the config_timeZoneRulesDataPackage config value.
     */
    public static final String AUTHORITY = "com.android.timezone";

    /** A content:// style uri to the authority for the time zone data content provider */
    private static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    /**
     * The content:// style URI for determining what type of update is available.
     *
     * <p>The URI can be queried using
     * {@link android.content.ContentProvider#query(Uri, String[], String, String[], String)};
     * the result will be a cursor with a single row. If the {@link #COLUMN_OPERATION}
     * column is {@link #OPERATION_INSTALL} then see {@link #DATA_URI} for how to obtain the
     * binary data.
     */
    public static final Uri OPERATION_URI = Uri.withAppendedPath(AUTHORITY_URI, "operation");

    /**
     * The {@code String} column of the {@link #OPERATION_URI} that provides an int specifying the
     * type of operation to perform. See {@link #OPERATION_NO_OP}, {@link #OPERATION_UNINSTALL} and
     * {@link #OPERATION_INSTALL}.
     */
    public static final String COLUMN_OPERATION = "operation";

    /**
     * An operation type used when the time zone rules on device should be left as they are.
     * This is not expected to be used in normal operation but a safe result in the event of an
     * error that cannot be recovered from.
     */
    public static final String OPERATION_NO_OP = "NOOP";

    /**
     * An operation type used when the current time zone rules on device should be uninstalled,
     * returning to the values held in the system partition.
     */
    public static final String OPERATION_UNINSTALL = "UNINSTALL";

    /**
     * An operation type used when the current time zone rules on device should be replaced by
     * a new set obtained via the {@link android.content.ContentProvider#openFile(Uri, String)}
     * method.
     */
    public static final String OPERATION_INSTALL = "INSTALL";

    /**
     * The {@code nullable int} column of the {@link #OPERATION_URI} that describes the major
     * version of the distro to be installed.
     * Only non-null if {@link #COLUMN_OPERATION} contains {@link #OPERATION_INSTALL}.
     */
    public static final String COLUMN_DISTRO_MAJOR_VERSION = "distro_major_version";

    /**
     * The {@code nullable int} column of the {@link #OPERATION_URI} that describes the minor
     * version of the distro to be installed.
     * Only non-null if {@link #COLUMN_OPERATION} contains {@link #OPERATION_INSTALL}.
     */
    public static final String COLUMN_DISTRO_MINOR_VERSION = "distro_minor_version";

    /**
     * The {@code nullable String} column of the {@link #OPERATION_URI} that describes the IANA
     * rules version of the distro to be installed.
     * Only non-null if {@link #COLUMN_OPERATION} contains {@link #OPERATION_INSTALL}.
     */
    public static final String COLUMN_RULES_VERSION = "rules_version";

    /**
     * The {@code nullable int} column of the {@link #OPERATION_URI} that describes the revision
     * number of the distro to be installed.
     * Only non-null if {@link #COLUMN_OPERATION} contains {@link #OPERATION_INSTALL}.
     */
    public static final String COLUMN_REVISION = "revision";

    /**
     * The content:// style URI for obtaining time zone bundle data.
     *
     * <p>Use {@link android.content.ContentProvider#openFile(Uri, String)} with "r" mode.
     */
    public static final Uri DATA_URI = Uri.withAppendedPath(AUTHORITY_URI, "data");
}

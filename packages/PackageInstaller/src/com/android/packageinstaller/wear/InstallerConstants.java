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
 * limitations under the License
 */

package com.android.packageinstaller.wear;

/**
 * Constants for Installation / Uninstallation requests.
 * Using the same values as Finsky/Wearsky code for consistency in user analytics of failures
 */
public class InstallerConstants {
    /** Request succeeded */
    public static final int STATUS_SUCCESS = 0;

    /**
     * The new PackageInstaller also returns a small set of less granular error codes, which
     * we'll remap to the range -500 and below to keep away from existing installer codes
     * (which run from -1 to -110).
     */
    public final static int ERROR_PACKAGEINSTALLER_BASE = -500;

    public static final int ERROR_COULD_NOT_GET_FD = -603;
    /** This node is not targeted by this request. */

    /** The install did not complete because could not create PackageInstaller session */
    public final static int ERROR_INSTALL_CREATE_SESSION = -612;
    /** The install did not complete because could not open PackageInstaller session  */
    public final static int ERROR_INSTALL_OPEN_SESSION = -613;
    /** The install did not complete because could not open PackageInstaller output stream */
    public final static int ERROR_INSTALL_OPEN_STREAM = -614;
    /** The install did not complete because of an exception while streaming bytes */
    public final static int ERROR_INSTALL_COPY_STREAM_EXCEPTION = -615;
    /** The install did not complete because of an unexpected exception from PackageInstaller */
    public final static int ERROR_INSTALL_SESSION_EXCEPTION = -616;
    /** The install did not complete because of an unexpected userActionRequired callback */
    public final static int ERROR_INSTALL_USER_ACTION_REQUIRED = -617;
    /** The install did not complete because of an unexpected broadcast (missing fields) */
    public final static int ERROR_INSTALL_MALFORMED_BROADCAST = -618;
    /** The install did not complete because of an error while copying from downloaded file */
    public final static int ERROR_INSTALL_APK_COPY_FAILURE = -619;
    /** The install did not complete because of an error while copying to the PackageInstaller
     * output stream */
    public final static int ERROR_INSTALL_COPY_STREAM = -620;
    /** The install did not complete because of an error while closing the PackageInstaller
     * output stream */
    public final static int ERROR_INSTALL_CLOSE_STREAM = -621;
}
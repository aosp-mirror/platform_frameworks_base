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

package com.android.server.pm;

import android.content.pm.PackageManager;
import android.content.pm.PackageParser.PackageParserException;

import com.android.internal.os.InstallerConnection.InstallerException;

/** {@hide} */
public class PackageManagerException extends Exception {
    public final int error;

    public PackageManagerException(String detailMessage) {
        super(detailMessage);
        this.error = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
    }

    public PackageManagerException(int error, String detailMessage) {
        super(detailMessage);
        this.error = error;
    }

    public PackageManagerException(int error, String detailMessage, Throwable throwable) {
        super(detailMessage, throwable);
        this.error = error;
    }

    public static PackageManagerException from(PackageParserException e)
            throws PackageManagerException {
        throw new PackageManagerException(e.error, e.getMessage(), e.getCause());
    }

    public static PackageManagerException from(InstallerException e)
            throws PackageManagerException {
        throw new PackageManagerException(PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                e.getMessage(), e.getCause());
    }
}

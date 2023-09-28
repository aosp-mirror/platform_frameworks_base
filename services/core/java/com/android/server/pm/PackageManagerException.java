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

import android.annotation.IntDef;
import android.content.pm.PackageManager;

import com.android.server.pm.Installer.InstallerException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** {@hide} */
public class PackageManagerException extends Exception {
    public static final int INTERNAL_ERROR_NATIVE_LIBRARY_COPY = -1;
    public static final int INTERNAL_ERROR_MOVE = -2;
    public static final int INTERNAL_ERROR_MISSING_SETTING_FOR_MOVE = -3;
    public static final int INTERNAL_ERROR_DERIVING_ABI = -4;
    public static final int INTERNAL_ERROR_VERITY_SETUP = -5;
    public static final int INTERNAL_ERROR_SHARED_LIB_INSTALLED_TWICE = -6;
    public static final int INTERNAL_ERROR_STORAGE_INVALID_PACKAGE_UNKNOWN = -7;
    public static final int INTERNAL_ERROR_STORAGE_INVALID_VOLUME_UNKNOWN = -8;
    public static final int INTERNAL_ERROR_STORAGE_INVALID_NOT_INSTALLED_FOR_USER = -9;
    public static final int INTERNAL_ERROR_STORAGE_INVALID_SHOULD_NOT_HAVE_STORAGE = -10;
    public static final int INTERNAL_ERROR_DECOMPRESS_STUB = -11;
    public static final int INTERNAL_ERROR_UPDATED_VERSION_BETTER_THAN_SYSTEM = -12;
    public static final int INTERNAL_ERROR_DUP_STATIC_SHARED_LIB_PROVIDER = -13;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_VERSION_CODES_ORDER = -14;
    public static final int INTERNAL_ERROR_SYSTEM_OVERLAY_STATIC = -15;
    public static final int INTERNAL_ERROR_OVERLAY_LOW_TARGET_SDK = -16;
    public static final int INTERNAL_ERROR_OVERLAY_SIGNATURE1 = -17;
    public static final int INTERNAL_ERROR_OVERLAY_SIGNATURE2 = -18;
    public static final int INTERNAL_ERROR_NOT_PRIV_SHARED_USER = -19;
    public static final int INTERNAL_ERROR_INSTALL_MISSING_CHILD_SESSIONS = -20;
    public static final int INTERNAL_ERROR_VERIFY_MISSING_CHILD_SESSIONS = -21;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_LOW_SDK = -22;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_INSTANT = -23;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_RENAMED = -24;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_DYNAMIC = -25;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_SHARED_USER = -26;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_ACTIVITY = -27;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_SERVICE = -28;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_CONTENT_PROVIDER = -29;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_BROADCAST_RECEIVER = -30;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_PERMISSION_GROUP = -31;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_FEATURE = -32;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_PERMISSION = -33;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_PROTECTED_BROADCAST = -34;
    public static final int INTERNAL_ERROR_STATIC_SHARED_LIB_OVERLAY_TARGETS = -35;
    public static final int INTERNAL_ERROR_APEX_NOT_DIRECTORY = -36;
    public static final int INTERNAL_ERROR_APEX_MORE_THAN_ONE_FILE = -37;
    public static final int INTERNAL_ERROR_MISSING_USER = -38;

    @IntDef(prefix = { "INTERNAL_ERROR_" }, value = {
            INTERNAL_ERROR_NATIVE_LIBRARY_COPY,
            INTERNAL_ERROR_MOVE,
            INTERNAL_ERROR_MISSING_SETTING_FOR_MOVE,
            INTERNAL_ERROR_DERIVING_ABI,
            INTERNAL_ERROR_VERITY_SETUP,
            INTERNAL_ERROR_SHARED_LIB_INSTALLED_TWICE,
            INTERNAL_ERROR_STORAGE_INVALID_PACKAGE_UNKNOWN,
            INTERNAL_ERROR_STORAGE_INVALID_VOLUME_UNKNOWN,
            INTERNAL_ERROR_STORAGE_INVALID_NOT_INSTALLED_FOR_USER,
            INTERNAL_ERROR_STORAGE_INVALID_SHOULD_NOT_HAVE_STORAGE,
            INTERNAL_ERROR_DECOMPRESS_STUB,
            INTERNAL_ERROR_UPDATED_VERSION_BETTER_THAN_SYSTEM,
            INTERNAL_ERROR_DUP_STATIC_SHARED_LIB_PROVIDER,
            INTERNAL_ERROR_STATIC_SHARED_LIB_VERSION_CODES_ORDER,
            INTERNAL_ERROR_SYSTEM_OVERLAY_STATIC,
            INTERNAL_ERROR_OVERLAY_LOW_TARGET_SDK,
            INTERNAL_ERROR_OVERLAY_SIGNATURE1,
            INTERNAL_ERROR_OVERLAY_SIGNATURE2,
            INTERNAL_ERROR_NOT_PRIV_SHARED_USER,
            INTERNAL_ERROR_INSTALL_MISSING_CHILD_SESSIONS,
            INTERNAL_ERROR_VERIFY_MISSING_CHILD_SESSIONS,
            INTERNAL_ERROR_STATIC_SHARED_LIB_LOW_SDK,
            INTERNAL_ERROR_STATIC_SHARED_LIB_INSTANT,
            INTERNAL_ERROR_STATIC_SHARED_LIB_RENAMED,
            INTERNAL_ERROR_STATIC_SHARED_LIB_DYNAMIC,
            INTERNAL_ERROR_STATIC_SHARED_LIB_SHARED_USER,
            INTERNAL_ERROR_STATIC_SHARED_LIB_ACTIVITY,
            INTERNAL_ERROR_STATIC_SHARED_LIB_SERVICE,
            INTERNAL_ERROR_STATIC_SHARED_LIB_CONTENT_PROVIDER,
            INTERNAL_ERROR_STATIC_SHARED_LIB_BROADCAST_RECEIVER,
            INTERNAL_ERROR_STATIC_SHARED_LIB_PERMISSION_GROUP,
            INTERNAL_ERROR_STATIC_SHARED_LIB_FEATURE,
            INTERNAL_ERROR_STATIC_SHARED_LIB_PERMISSION,
            INTERNAL_ERROR_STATIC_SHARED_LIB_PROTECTED_BROADCAST,
            INTERNAL_ERROR_STATIC_SHARED_LIB_OVERLAY_TARGETS,
            INTERNAL_ERROR_APEX_NOT_DIRECTORY,
            INTERNAL_ERROR_APEX_MORE_THAN_ONE_FILE,
            INTERNAL_ERROR_MISSING_USER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InternalErrorCode {}

    public final int error;
    public final int internalErrorCode;

    /**
     * Default constructor without specifying the public return code of this exception.
     * The public error code will be {@link PackageManager.INSTALL_FAILED_INTERNAL_ERROR}.
     *
     * Note for developers: if you use this constructor, assuming you have a different case where
     * the exception should be thrown with {@link PackageManager.INSTALL_FAILED_INTERNAL_ERROR},
     * please create a new {@link InternalErrorCode} constant.
     *
     * @param detailMessage Details about the cause of the exception.
     * @param internalErrorCode Used for logging and analysis.
     */
    public static PackageManagerException ofInternalError(String detailMessage,
            @InternalErrorCode int internalErrorCode) {
        return new PackageManagerException(
                PackageManager.INSTALL_FAILED_INTERNAL_ERROR, detailMessage, internalErrorCode);
    }

    protected PackageManagerException(int error, String detailMessage, int internalErrorCode) {
        super(detailMessage);
        this.error = error;
        this.internalErrorCode = internalErrorCode;
    }

    public PackageManagerException(int error, String detailMessage) {
        super(detailMessage);
        this.error = error;
        this.internalErrorCode = 0;
    }

    public PackageManagerException(int error, String detailMessage, Throwable throwable) {
        super(detailMessage, throwable);
        this.error = error;
        this.internalErrorCode = 0;
    }

    public PackageManagerException(Throwable e) {
        super(e);
        this.error = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        this.internalErrorCode = 0;
    }

    public static PackageManagerException from(InstallerException e)
            throws PackageManagerException {
        throw new PackageManagerException(PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                e.getMessage(), e.getCause());
    }
}

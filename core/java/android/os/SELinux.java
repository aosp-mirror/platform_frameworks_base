/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;
import android.util.Slog;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

/**
 * This class provides access to the centralized jni bindings for
 * SELinux interaction.
 * {@hide}
 */
public class SELinux {
    private static final String TAG = "SELinux";

    /** Keep in sync with ./external/selinux/libselinux/include/selinux/android.h */
    private static final int SELINUX_ANDROID_RESTORECON_NOCHANGE = 1;
    private static final int SELINUX_ANDROID_RESTORECON_VERBOSE = 2;
    private static final int SELINUX_ANDROID_RESTORECON_RECURSE = 4;
    private static final int SELINUX_ANDROID_RESTORECON_FORCE = 8;
    private static final int SELINUX_ANDROID_RESTORECON_DATADATA = 16;
    private static final int SELINUX_ANDROID_RESTORECON_SKIPCE = 32;
    private static final int SELINUX_ANDROID_RESTORECON_CROSS_FILESYSTEMS = 64;
    private static final int SELINUX_ANDROID_RESTORECON_SKIP_SEHASH = 128;

    /**
     * Get context associated with path by file_contexts.
     * @param path path to the regular file to get the security context for.
     * @return a String representing the security context or null on failure.
     */
    public static final native String fileSelabelLookup(String path);

    /**
     * Determine whether SELinux is disabled or enabled.
     * @return a boolean indicating whether SELinux is enabled.
     */
    @UnsupportedAppUsage
    public static final native boolean isSELinuxEnabled();

    /**
     * Determine whether SELinux is permissive or enforcing.
     * @return a boolean indicating whether SELinux is enforcing.
     */
    @UnsupportedAppUsage
    public static final native boolean isSELinuxEnforced();

    /**
     * Sets the security context for newly created file objects.
     * @param context a security context given as a String.
     * @return a boolean indicating whether the operation succeeded.
     */
    public static final native boolean setFSCreateContext(String context);

    /**
     * Change the security context of an existing file object.
     * @param path representing the path of file object to relabel.
     * @param context new security context given as a String.
     * @return a boolean indicating whether the operation succeeded.
     */
    public static final native boolean setFileContext(String path, String context);

    /**
     * Get the security context of a file object.
     * @param path the pathname of the file object.
     * @return a security context given as a String.
     */
    @UnsupportedAppUsage
    public static final native String getFileContext(String path);

    /**
     * Get the security context of a peer socket.
     * @param fd FileDescriptor class of the peer socket.
     * @return a String representing the peer socket security context.
     */
    public static final native String getPeerContext(FileDescriptor fd);

    /**
     * Get the security context of a file descriptor of a file.
     * @param fd FileDescriptor of a file.
     * @return a String representing the file descriptor security context.
     */
    public static final native String getFileContext(FileDescriptor fd);

    /**
     * Gets the security context of the current process.
     * @return a String representing the security context of the current process.
     */
    @UnsupportedAppUsage
    public static final native String getContext();

    /**
     * Gets the security context of a given process id.
     * @param pid an int representing the process id to check.
     * @return a String representing the security context of the given pid.
     */
    @UnsupportedAppUsage
    public static final native String getPidContext(int pid);

    /**
     * Check permissions between two security contexts.
     * @param scon The source or subject security context.
     * @param tcon The target or object security context.
     * @param tclass The object security class name.
     * @param perm The permission name.
     * @return a boolean indicating whether permission was granted.
     */
    @UnsupportedAppUsage
    public static final native boolean checkSELinuxAccess(String scon, String tcon, String tclass, String perm);

    /**
     * Restores a file to its default SELinux security context.
     * If the system is not compiled with SELinux, then {@code true}
     * is automatically returned.
     * If SELinux is compiled in, but disabled, then {@code true} is
     * returned.
     *
     * @param pathname The pathname of the file to be relabeled.
     * @return a boolean indicating whether the relabeling succeeded.
     * @exception NullPointerException if the pathname is a null object.
     */
    public static boolean restorecon(String pathname) throws NullPointerException {
        if (pathname == null) { throw new NullPointerException(); }
        return native_restorecon(pathname, 0);
    }

    /**
     * Restores a file to its default SELinux security context.
     * If the system is not compiled with SELinux, then {@code true}
     * is automatically returned.
     * If SELinux is compiled in, but disabled, then {@code true} is
     * returned.
     *
     * @param pathname The pathname of the file to be relabeled.
     * @return a boolean indicating whether the relabeling succeeded.
     */
    private static native boolean native_restorecon(String pathname, int flags);

    /**
     * Restores a file to its default SELinux security context.
     * If the system is not compiled with SELinux, then {@code true}
     * is automatically returned.
     * If SELinux is compiled in, but disabled, then {@code true} is
     * returned.
     *
     * @param file The File object representing the path to be relabeled.
     * @return a boolean indicating whether the relabeling succeeded.
     * @exception NullPointerException if the file is a null object.
     */
    public static boolean restorecon(File file) throws NullPointerException {
        try {
            return native_restorecon(file.getCanonicalPath(), 0);
        } catch (IOException e) {
            Slog.e(TAG, "Error getting canonical path. Restorecon failed for " +
                    file.getPath(), e);
            return false;
        }
    }

    /**
     * Recursively restores all files under the given path to their default
     * SELinux security context. If the system is not compiled with SELinux,
     * then {@code true} is automatically returned. If SELinux is compiled in,
     * but disabled, then {@code true} is returned.
     *
     * @return a boolean indicating whether the relabeling succeeded.
     */
    @UnsupportedAppUsage
    public static boolean restoreconRecursive(File file) {
        try {
            return native_restorecon(file.getCanonicalPath(),
                SELINUX_ANDROID_RESTORECON_RECURSE | SELINUX_ANDROID_RESTORECON_SKIP_SEHASH);
        } catch (IOException e) {
            Slog.e(TAG, "Error getting canonical path. Restorecon failed for " +
                    file.getPath(), e);
            return false;
        }
    }
}

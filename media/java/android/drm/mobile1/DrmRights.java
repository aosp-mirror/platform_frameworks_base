/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.drm.mobile1;

/**
 * This class provides interfaces to access the DRM rights.
 */
public class DrmRights {
    /**
     * The DRM permission of play.
     */
    public static final int DRM_PERMISSION_PLAY = 1;

    /**
     * The DRM permission of display.
     */
    public static final int DRM_PERMISSION_DISPLAY = 2;

    /**
     * The DRM permission of execute.
     */
    public static final int DRM_PERMISSION_EXECUTE = 3;

    /**
     * The DRM permission of print.
     */
    public static final int DRM_PERMISSION_PRINT = 4;

    /**
     * Successful operation.
     */
    private static final int JNI_DRM_SUCCESS = 0;

    /**
     * General failure.
     */
    private static final int JNI_DRM_FAILURE = -1;

    /**
     * The uid of this rights object.
     */
    private String roId = "";


    /**
     * Construct the DrmRights.
     */
    public DrmRights() {
    }

    /**
     * Get the constraint of the given permission on this rights object.
     *
     * @param permission    the given permission.
     *
     * @return a DrmConstraint instance.
     */
    public DrmConstraintInfo getConstraint(int permission) {
        DrmConstraintInfo c = new DrmConstraintInfo();

        /* call native method to get latest constraint information */
        int res = nativeGetConstraintInfo(permission, c);

        if (JNI_DRM_FAILURE == res)
            return null;

        return c;
    }

    /**
     * Consume the rights of the given permission.
     *
     * @param permission    the given permission.
     *
     * @return true if consume success.
     *         false if consume failure.
     */
    public boolean consumeRights(int permission) {
        /* call native method to consume and update rights */
        int res = nativeConsumeRights(permission);

        if (JNI_DRM_FAILURE == res)
            return false;

        return true;
    }


    /**
     * native method: get the constraint information of the given permission.
     *
     * @param permission    the given permission.
     * @param constraint    the instance of constraint.
     *
     * @return #JNI_DRM_SUCCESS if succeed.
     *         #JNI_DRM_FAILURE if fail.
     */
    private native int nativeGetConstraintInfo(int permission, DrmConstraintInfo constraint);

    /**
     * native method: consume the rights of the given permission.
     *
     * @param permission    the given permission.
     *
     * @return #JNI_DRM_SUCCESS if succeed.
     *         #JNI_DRM_FAILURE if fail.
     */
    private native int nativeConsumeRights(int permission);


    /**
     * Load the shared library to link the native methods.
     */
    static {
        try {
            System.loadLibrary("drm1_jni");
        }
        catch (UnsatisfiedLinkError ule) {
            System.err.println("WARNING: Could not load libdrm1_jni.so");
        }
    }
}

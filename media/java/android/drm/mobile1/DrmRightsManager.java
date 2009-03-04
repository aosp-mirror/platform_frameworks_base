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

import java.io.*;
import java.util.*;

/**
 * This class provides interfaces to access the DRM right manager.
 */
public class DrmRightsManager {
    /**
     * The "application/vnd.oma.drm.rights+xml" mime type.
     */
    public static final String DRM_MIMETYPE_RIGHTS_XML_STRING = "application/vnd.oma.drm.rights+xml";

    /**
     * The "application/vnd.oma.drm.rights+wbxml" mime type.
     */
    public static final String DRM_MIMETYPE_RIGHTS_WBXML_STRING = "application/vnd.oma.drm.rights+wbxml";

    /**
     * The id of "application/vnd.oma.drm.rights+xml" mime type.
     */
    private static final int DRM_MIMETYPE_RIGHTS_XML = 3;

    /**
     * The id of "application/vnd.oma.drm.rights+wbxml" mime type.
     */
    private static final int DRM_MIMETYPE_RIGHTS_WBXML = 4;

    /**
     * The id of "application/vnd.oma.drm.message" mime type.
     */
    private static final int DRM_MIMETYPE_MESSAGE = 1;

    /**
     * Successful operation.
     */
    private static final int JNI_DRM_SUCCESS = 0;

    /**
     * General failure.
     */
    private static final int JNI_DRM_FAILURE = -1;

    /**
     * The instance of the rights manager.
     */
    private static DrmRightsManager singleton = null;


    /**
     * Construct a DrmRightsManager
     */
    protected DrmRightsManager() {
    }

    /**
     * Get the DrmRightsManager instance.
     *
     * @return the instance of DrmRightsManager.
     */
    public static synchronized DrmRightsManager getInstance() {
        if (singleton == null) {
            singleton = new DrmRightsManager();
        }

        return singleton;
    }

    /**
     * Install one DRM rights and return one instance of DrmRights.
     *
     * @param rightsData    raw rights data.
     * @param mimeTypeStr   the mime type of the rights object.
     *
     * @return the instance of the installed DrmRights.
     */
    public synchronized DrmRights installRights(InputStream rightsData, int len, String mimeTypeStr) throws DrmException, IOException {
        int mimeType = 0;

        if (DRM_MIMETYPE_RIGHTS_XML_STRING.equals(mimeTypeStr))
            mimeType = DRM_MIMETYPE_RIGHTS_XML;
        else if (DRM_MIMETYPE_RIGHTS_WBXML_STRING.equals(mimeTypeStr))
            mimeType = DRM_MIMETYPE_RIGHTS_WBXML;
        else if (DrmRawContent.DRM_MIMETYPE_MESSAGE_STRING.equals(mimeTypeStr))
            mimeType = DRM_MIMETYPE_MESSAGE;
        else
            throw new IllegalArgumentException("mimeType must be DRM_MIMETYPE_RIGHTS_XML or DRM_MIMETYPE_RIGHTS_WBXML or DRM_MIMETYPE_MESSAGE");

        if (len <= 0)
            return null;

        DrmRights rights = new DrmRights();

        /* call native method to install this rights object. */
        int res = nativeInstallDrmRights(rightsData, len, mimeType, rights);

        if (JNI_DRM_FAILURE == res)
            throw new DrmException("nativeInstallDrmRights() returned JNI_DRM_FAILURE");

        return rights;
    }

    /**
     * Query DRM rights of specified DRM raw content.
     *
     * @param content       raw content object.
     *
     * @return the instance of DrmRights, or null if there is no rights.
     */
    public synchronized DrmRights queryRights(DrmRawContent content) {
        DrmRights rights = new DrmRights();

        /* call native method to query the rights */
        int res = nativeQueryRights(content, rights);

        if (JNI_DRM_FAILURE == res)
            return null;

        return rights;
    }

    /**
     * Get the list of all DRM rights saved in local client.
     *
     * @return the list of all the rights object.
     */
    public synchronized List getRightsList() {
        List rightsList = new ArrayList();

        /* call native method to get how many rights object in current agent */
        int num = nativeGetNumOfRights();

        if (JNI_DRM_FAILURE == num)
            return null;

        if (num > 0) {
            DrmRights[] rightsArray = new DrmRights[num];
            int i;

            for (i = 0; i < num; i++)
                rightsArray[i] = new DrmRights();

            /* call native method to get all the rights information */
            num = nativeGetRightsList(rightsArray, num);

            if (JNI_DRM_FAILURE == num)
                return null;

            /* add all rights informations to ArrayList */
            for (i = 0; i < num; i++)
                rightsList.add(rightsArray[i]);
        }

        return rightsList;
    }

    /**
     * Delete the specified DRM rights object.
     *
     * @param rights    the specified rights object to be deleted.
     */
    public synchronized void deleteRights(DrmRights rights) {
        /* call native method to delete the specified rights object */
        int res = nativeDeleteRights(rights);

        if (JNI_DRM_FAILURE == res)
            return;
    }


    /**
     * native method: install rights object to local client.
     *
     * @param data      input DRM rights object data to be installed.
     * @param len       the length of the data.
     * @param mimeType  the mime type of this DRM rights object. the value of this field includes:
     *                      #DRM_MIMETYPE_RIGHTS_XML
     *                      #DRM_MIMETYPE_RIGHTS_WBXML
     * @parma rights    the instance of DRMRights to be filled.
     *
     * @return #JNI_DRM_SUCCESS if succeed.
     *         #JNI_DRM_FAILURE if fail.
     */
    private native int nativeInstallDrmRights(InputStream data, int len, int mimeType, DrmRights rights);

    /**
     * native method: query the given DRM content's rights object.
     *
     * @param content   the given DRM content.
     * @param rights    the instance of rights to set if have.
     *
     * @return #JNI_DRM_SUCCESS if succeed.
     *         #JNI_DRM_FAILURE if fail.
     */
    private native int nativeQueryRights(DrmRawContent content, DrmRights rights);

    /**
     * native method: get how many rights object in current DRM agent.
     *
     * @return the number of the rights object.
     *         #JNI_DRM_FAILURE if fail.
     */
    private native int nativeGetNumOfRights();

    /**
     * native method: get all the rights object in current local agent.
     *
     * @param rights    the array instance of rights object.
     * @param numRights how many rights can be saved.
     *
     * @return the number of the rights object has been gotten.
     *         #JNI_DRM_FAILURE if fail.
     */
    private native int nativeGetRightsList(DrmRights[] rights, int numRights);

    /**
     * native method: delete a specified rights object.
     *
     * @param rights    the specified rights object to be deleted.
     *
     * @return #JNI_DRM_SUCCESS if succeed.
     *         #JNI_DRM_FAILURE if fail.
     */
    private native int nativeDeleteRights(DrmRights rights);


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

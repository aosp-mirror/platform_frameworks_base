/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.drm;

import java.util.HashMap;

/**
 * This is an entity class which would be passed to caller in
 * {@link DrmManagerClient.OnErrorListener#onError(DrmManagerClient, DrmErrorEvent)}
 *
 */
public class DrmErrorEvent extends DrmEvent {
    /**
     * TYPE_RIGHTS_NOT_INSTALLED, when something went wrong installing the rights.
     */
    public static final int TYPE_RIGHTS_NOT_INSTALLED = 2001;
    /**
     * TYPE_RIGHTS_RENEWAL_NOT_ALLOWED, when the server rejects renewal of rights.
     */
    public static final int TYPE_RIGHTS_RENEWAL_NOT_ALLOWED = 2002;
    /**
     * TYPE_NOT_SUPPORTED, when answer from server can not be handled by the native agent.
     */
    public static final int TYPE_NOT_SUPPORTED = 2003;
    /**
     * TYPE_OUT_OF_MEMORY, when memory allocation fail during renewal.
     * Can in the future perhaps be used to trigger garbage collector.
     */
    public static final int TYPE_OUT_OF_MEMORY = 2004;
    /**
     * TYPE_NO_INTERNET_CONNECTION, when the Internet connection is missing and no attempt
     * can be made to renew rights.
     */
    public static final int TYPE_NO_INTERNET_CONNECTION = 2005;
    /**
     * TYPE_PROCESS_DRM_INFO_FAILED, when failed to process DrmInfo.
     */
    public static final int TYPE_PROCESS_DRM_INFO_FAILED = 2006;
    /**
     * TYPE_REMOVE_ALL_RIGHTS_FAILED, when failed to remove all the rights objects
     * associated with all DRM schemes.
     */
    public static final int TYPE_REMOVE_ALL_RIGHTS_FAILED = 2007;
    /**
     * TYPE_ACQUIRE_DRM_INFO_FAILED, when failed to acquire DrmInfo.
     */
    public static final int TYPE_ACQUIRE_DRM_INFO_FAILED = 2008;

    /**
     * constructor to create DrmErrorEvent object with given parameters
     *
     * @param uniqueId Unique session identifier
     * @param type Type of the event. It could be one of the types defined above
     * @param message Message description
     */
    public DrmErrorEvent(int uniqueId, int type, String message) {
        super(uniqueId, type, message);
    }

    /**
     * constructor to create DrmErrorEvent object with given parameters
     *
     * @param uniqueId Unique session identifier
     * @param type Type of the event. It could be one of the types defined above
     * @param message Message description
     * @param attributes Attributes for extensible information. Could be any
     * information provided by the plugin
     */
    public DrmErrorEvent(int uniqueId, int type, String message,
                            HashMap<String, Object> attributes) {
        super(uniqueId, type, message, attributes);
    }
}


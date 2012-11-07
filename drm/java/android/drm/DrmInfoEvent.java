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
 * An entity class that is passed to the 
 * {@link DrmManagerClient.OnInfoListener#onInfo onInfo()} callback.
 *
 */
public class DrmInfoEvent extends DrmEvent {

    // Please add newly defined type constants to the end of the list,
    // and modify checkTypeValidity() accordingly.

    /**
     * The registration has already been done by another account ID.
     */
    public static final int TYPE_ALREADY_REGISTERED_BY_ANOTHER_ACCOUNT = 1;
    /**
     * The rights need to be removed completely.
     */
    public static final int TYPE_REMOVE_RIGHTS = 2;
    /**
     * The rights have been successfully downloaded and installed.
     */
    public static final int TYPE_RIGHTS_INSTALLED = 3;
    /**
     * The rights object is being delivered to the device. You must wait before
     * calling {@link DrmManagerClient#acquireRights acquireRights()} again.
     */
    public static final int TYPE_WAIT_FOR_RIGHTS = 4;
    /**
     * The registration has already been done for the given account.
     */
    public static final int TYPE_ACCOUNT_ALREADY_REGISTERED = 5;
    /**
     * The rights have been removed.
     */
    public static final int TYPE_RIGHTS_REMOVED = 6;

    // Add more type constants here...

    // FIXME:
    // We may want to add a user-defined type constant, such as
    // TYPE_VENDOR_SPECIFIC, to take care vendor specific use
    // cases.

    /**
     * Creates a <code>DrmInfoEvent</code> object with the specified parameters.
     *
     * @param uniqueId Unique session identifier.
     * @param type Type of the event. Must be any of the event types defined above,
     * or the constants defined in {@link DrmEvent}.
     * @param message Message description. It can be null.
     */
    public DrmInfoEvent(int uniqueId, int type, String message) {
        super(uniqueId, type, message);
        checkTypeValidity(type);
    }

    /**
     * Creates a <code>DrmInfoEvent</code> object with the specified parameters.
     *
     * @param uniqueId Unique session identifier.
     * @param type Type of the event. Must be any of the event types defined above,
     * or the constants defined in {@link DrmEvent}
     * @param message Message description. It can be null.
     * @param attributes Attributes for extensible information. Could be any
     * information provided by the plug-in.
     */
    public DrmInfoEvent(int uniqueId, int type, String message,
                            HashMap<String, Object> attributes) {
        super(uniqueId, type, message, attributes);
        checkTypeValidity(type);
    }

    /*
     * Check the validity of the given type.
     * To overcome a design flaw, we need also accept the type constants
     * defined in super class, DrmEvent.
     */
    private void checkTypeValidity(int type) {
        if (type < TYPE_ALREADY_REGISTERED_BY_ANOTHER_ACCOUNT ||
            type > TYPE_RIGHTS_REMOVED) {

            if (type != TYPE_ALL_RIGHTS_REMOVED &&
                type != TYPE_DRM_INFO_PROCESSED) {
                final String msg = "Unsupported type: " + type;
                throw new IllegalArgumentException(msg);
            }
        }
    }
}


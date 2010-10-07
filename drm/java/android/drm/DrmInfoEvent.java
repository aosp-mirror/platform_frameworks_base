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

/**
 * This is an entity class which would be passed to caller in
 * {@link DrmManagerClient.OnInfoListener#onInfo(DrmManagerClient, DrmInfoEvent)}
 *
 */
public class DrmInfoEvent extends DrmEvent {
    /**
     * TYPE_ALREADY_REGISTERED_BY_ANOTHER_ACCOUNT, when registration has been already done
     * by another account ID.
     */
    public static final int TYPE_ALREADY_REGISTERED_BY_ANOTHER_ACCOUNT = 1;
    /**
     * TYPE_REMOVE_RIGHTS, when the rights needs to be removed completely.
     */
    public static final int TYPE_REMOVE_RIGHTS = 2;
    /**
     * TYPE_RIGHTS_INSTALLED, when the rights are downloaded and installed ok.
     */
    public static final int TYPE_RIGHTS_INSTALLED = 3;
    /**
     * TYPE_WAIT_FOR_RIGHTS, rights object is on it's way to phone,
     * wait before calling checkRights again.
     */
    public static final int TYPE_WAIT_FOR_RIGHTS = 4;
    /**
     * TYPE_ACCOUNT_ALREADY_REGISTERED, when registration has been
     * already done for the given account.
     */
    public static final int TYPE_ACCOUNT_ALREADY_REGISTERED = 5;

    /**
     * constructor to create DrmInfoEvent object with given parameters
     *
     * @param uniqueId Unique session identifier
     * @param type Type of information
     * @param message Message description
     */
    public DrmInfoEvent(int uniqueId, int type, String message) {
        super(uniqueId, type, message);
    }
}


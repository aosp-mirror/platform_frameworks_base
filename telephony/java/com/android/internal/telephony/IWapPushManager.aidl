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

package com.android.internal.telephony;

import android.content.Intent;

interface IWapPushManager {
    /**
     * Processes WAP push message and triggers the receiver application registered
     * in the application ID table.
     */
    int processMessage(String app_id, String content_type, in Intent intent);

    /**
     * Add receiver application into the application ID table.
     * Returns true if inserting the information is successfull. Inserting the duplicated
     * record in the application ID table is not allowed. Use update/delete method.
     */
    boolean addPackage(String x_app_id, String content_type,
            String package_name, String class_name,
            int app_type, boolean need_signature, boolean further_processing);

    /**
     * Updates receiver application that is last added.
     * Returns true if updating the information is successfull.
     */
    boolean updatePackage(String x_app_id, String content_type,
            String package_name, String class_name,
            int app_type, boolean need_signature, boolean further_processing);

    /**
     * Delites receiver application information.
     * Returns true if deleting is successfull.
     */
    boolean deletePackage(String x_app_id, String content_type,
                            String package_name, String class_name);
}


/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * See the License for the specific language governing per  missions and
 * limitations under the License.
 */

package android.companion;

import android.app.PendingIntent;
import android.companion.AssociationInfo;

/** @hide */
interface IAssociationRequestCallback {
    oneway void onAssociationPending(in PendingIntent pendingIntent);

    oneway void onAssociationCreated(in AssociationInfo associationInfo);

    oneway void onFailure(in int errorCode, in CharSequence error);
}
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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm;

import android.net.Uri;

final class VerificationInfo {
    /** URI referencing where the package was downloaded from. */
    final Uri mOriginatingUri;

    /** HTTP referrer URI associated with the originatingURI. */
    final Uri mReferrer;

    /** UID of the application that the install request originated from. */
    final int mOriginatingUid;

    /** UID of application requesting the install */
    final int mInstallerUid;

    VerificationInfo(Uri originatingUri, Uri referrer, int originatingUid, int installerUid) {
        mOriginatingUri = originatingUri;
        mReferrer = referrer;
        mOriginatingUid = originatingUid;
        mInstallerUid = installerUid;
    }
}

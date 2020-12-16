/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.domain.verify;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.pm.domain.verify.DomainVerificationManager;
import android.content.pm.domain.verify.DomainVerificationSet;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.server.pm.domain.verify.models.DomainVerificationPkgState;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.UUID;

public interface DomainVerificationManagerInternal extends DomainVerificationManager {

    UUID DISABLED_ID = new UUID(0, 0);

    /**
     * Generate a new domain set ID to be used for attaching new packages.
     */
    @NonNull
    UUID generateNewId();

    /**
     * Serializes the entire internal state. This is equivalent to a full backup of the existing
     * verification state.
     */
    void writeSettings(@NonNull TypedXmlSerializer serializer) throws IOException;

    /**
     * Read back a list of {@link DomainVerificationPkgState}s previously written by {@link
     * #writeSettings(TypedXmlSerializer)}. Assumes that the
     * {@link DomainVerificationPersistence#TAG_DOMAIN_VERIFICATIONS}
     * tag has already been entered.
     * <p>
     * This is expected to only be used to re-attach states for packages already known to be on the
     * device. If restoring from a backup, use {@link #restoreSettings(TypedXmlPullParser)}.
     */
    void readSettings(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException;

    /**
     * Remove all state for the given package.
     */
    void clearPackage(@NonNull String packageName);

    /**
     * Delete all the state for a user. This can be because the user has been removed from the
     * device, or simply that the state for a user should be deleted.
     */
    void clearUser(@UserIdInt int userId);

    /**
     * Restore a list of {@link DomainVerificationPkgState}s previously written by {@link
     * #writeSettings(TypedXmlSerializer)}. Assumes that the
     * {@link DomainVerificationPersistence#TAG_DOMAIN_VERIFICATIONS}
     * tag has already been entered.
     * <p>
     * This is <b>only</b> for restore, and will override package states, ignoring if their {@link
     * DomainVerificationSet#getIdentifier()}s match. It's expected that any restored domains marked
     * as success verify against the server correctly, although the verification agent may decide to
     * re-verify them when it gets the chance.
     */
    /*
     * TODO(b/170746586): Figure out how to verify that package signatures match at snapshot time
     *  and restore time.
     */
    void restoreSettings(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException;

}

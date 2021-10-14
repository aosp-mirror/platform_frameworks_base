/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.om;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.om.OverlayIdentifier;
import android.os.UserHandle;

/**
 * State for dumps performed by the OverlayManagerService.
 */
public final class DumpState {
    @UserIdInt private int mUserId = UserHandle.USER_ALL;
    @Nullable private String mPackageName;
    @Nullable private String mOverlayName;
    @Nullable private String mField;
    private boolean mVerbose;

    /** Sets the user to dump the state for */
    public void setUserId(@UserIdInt int userId) {
        mUserId = userId;
    }
    @UserIdInt public int getUserId() {
        return mUserId;
    }

    /** Sets the name of the package to dump the state for */
    public void setOverlyIdentifier(String overlayIdentifier) {
        final OverlayIdentifier overlay = OverlayIdentifier.fromString(overlayIdentifier);
        mPackageName = overlay.getPackageName();
        mOverlayName = overlay.getOverlayName();
    }
    @Nullable public String getPackageName() {
        return mPackageName;
    }
    @Nullable public String getOverlayName() {
        return mOverlayName;
    }

    /** Sets the name of the field to dump the state for */
    public void setField(String field) {
        mField = field;
    }
    @Nullable public String getField() {
        return mField;
    }

    /** Enables verbose dump state */
    public void setVerbose(boolean verbose) {
        mVerbose = verbose;
    }
    public boolean isVerbose() {
        return mVerbose;
    }
}

/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.credentials.selection;

import static android.credentials.flags.Flags.FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.IBinder;

/**
 * Unique identifier for a getCredential / createCredential API session.
 *
 * To compare if two requests pertain to the same session, compare their RequestTokens using
 * the {@link RequestToken#equals(Object)} method.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
public final class RequestToken {

    @NonNull
    private final IBinder mToken;

    /** @hide */
    @TestApi
    @FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
    public RequestToken(@NonNull IBinder token) {
        mToken = token;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || !(obj instanceof RequestToken)) {
            return false;
        }
        final RequestToken other = (RequestToken) obj;
        return mToken.equals(other.mToken);
    }

    @Override
    public int hashCode() {
        return mToken.hashCode();
    }
}

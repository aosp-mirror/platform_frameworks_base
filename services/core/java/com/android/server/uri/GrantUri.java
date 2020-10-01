/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.uri;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.util.proto.ProtoOutputStream;

import com.android.server.am.GrantUriProto;

/** A {@link Uri} that can be granted to app an to access with the right permission. */
public class GrantUri {
    public final int sourceUserId;
    public final Uri uri;
    public final boolean prefix;

    public GrantUri(int sourceUserId, Uri uri, int modeFlags) {
        this.sourceUserId = sourceUserId;
        this.uri = uri;
        this.prefix = (modeFlags & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0;
    }

    @Override
    public int hashCode() {
        int hashCode = 1;
        hashCode = 31 * hashCode + sourceUserId;
        hashCode = 31 * hashCode + uri.hashCode();
        hashCode = 31 * hashCode + (prefix ? 1231 : 1237);
        return hashCode;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof GrantUri) {
            GrantUri other = (GrantUri) o;
            return uri.equals(other.uri) && (sourceUserId == other.sourceUserId)
                    && prefix == other.prefix;
        }
        return false;
    }

    @Override
    public String toString() {
        String result = uri.toString() + " [user " + sourceUserId + "]";
        if (prefix) result += " [prefix]";
        return result;
    }

    public String toSafeString() {
        String result = uri.toSafeString() + " [user " + sourceUserId + "]";
        if (prefix) result += " [prefix]";
        return result;
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(GrantUriProto.URI, uri.toString());
        proto.write(GrantUriProto.SOURCE_USER_ID, sourceUserId);
        proto.end(token);
    }

    public static GrantUri resolve(int defaultSourceUserHandle, Uri uri, int modeFlags) {
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            return new GrantUri(ContentProvider.getUserIdFromUri(uri, defaultSourceUserHandle),
                    ContentProvider.getUriWithoutUserId(uri), modeFlags);
        } else {
            return new GrantUri(defaultSourceUserHandle, uri, modeFlags);
        }
    }
}

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

package android.app.compat;

import android.annotation.IntDef;
import android.annotation.NonNull;

import com.android.internal.annotations.Immutable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;


/**
 * A key type for caching calls to {@link com.android.internal.compat.IPlatformCompat}
 *
 * <p>For {@link com.android.internal.compat.IPlatformCompat#isChangeEnabledByPackageName}
 * and {@link com.android.internal.compat.IPlatformCompat#isChangeEnabledByUid}
 *
 * @hide
 */
@Immutable
final class ChangeIdStateQuery {

    static final int QUERY_BY_PACKAGE_NAME = 0;
    static final int QUERY_BY_UID = 1;
    @IntDef({QUERY_BY_PACKAGE_NAME, QUERY_BY_UID})
    @Retention(RetentionPolicy.SOURCE)
    @interface QueryType {}

    public @QueryType int type;
    public long changeId;
    public String packageName;
    public int uid;
    public int userId;

    private ChangeIdStateQuery(@QueryType int type, long changeId, String packageName,
                               int uid, int userId) {
        this.type = type;
        this.changeId = changeId;
        this.packageName = packageName;
        this.uid = uid;
        this.userId = userId;
    }

    static ChangeIdStateQuery byPackageName(long changeId, @NonNull String packageName,
                                            int userId) {
        return new ChangeIdStateQuery(QUERY_BY_PACKAGE_NAME, changeId, packageName, 0, userId);
    }

    static ChangeIdStateQuery byUid(long changeId, int uid) {
        return new ChangeIdStateQuery(QUERY_BY_UID, changeId, null, uid, 0);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if ((other == null) || !(other instanceof ChangeIdStateQuery)) {
            return false;
        }
        final ChangeIdStateQuery that = (ChangeIdStateQuery) other;
        return this.type == that.type
            && this.changeId == that.changeId
            && Objects.equals(this.packageName, that.packageName)
            && this.uid == that.uid
            && this.userId == that.userId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, changeId, packageName, uid, userId);
    }
}

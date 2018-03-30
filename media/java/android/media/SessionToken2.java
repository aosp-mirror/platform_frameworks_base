/*
 * Copyright 2018 The Android Open Source Project
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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.Context;
import android.media.session.MediaSessionManager;
import android.media.update.ApiLoader;
import android.media.update.SessionToken2Provider;
import android.os.Bundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 * Represents an ongoing {@link MediaSession2} or a {@link MediaSessionService2}.
 * If it's representing a session service, it may not be ongoing.
 * <p>
 * This may be passed to apps by the session owner to allow them to create a
 * {@link MediaController2} to communicate with the session.
 * <p>
 * It can be also obtained by {@link MediaSessionManager}.
 */
// New version of MediaSession.Token for following reasons
//   - Stop implementing Parcelable for updatable support
//   - Represent session and library service (formerly browser service) in one class.
//     Previously MediaSession.Token was for session and ComponentName was for service.
public final class SessionToken2 {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {TYPE_SESSION, TYPE_SESSION_SERVICE, TYPE_LIBRARY_SERVICE})
    public @interface TokenType {
    }

    public static final int TYPE_SESSION = 0;
    public static final int TYPE_SESSION_SERVICE = 1;
    public static final int TYPE_LIBRARY_SERVICE = 2;

    private final SessionToken2Provider mProvider;

    // From the return value of android.os.Process.getUidForName(String) when error
    private static final int UID_UNKNOWN = -1;

    /**
     * Constructor for the token. You can only create token for session service or library service
     * to use by {@link MediaController2} or {@link MediaBrowser2}.
     *
     * @param context context
     * @param packageName package name
     * @param serviceName name of service. Can be {@code null} if it's not an service.
     */
    public SessionToken2(@NonNull Context context, @NonNull String packageName,
            @NonNull String serviceName) {
        this(context, packageName, serviceName, UID_UNKNOWN);
    }

    /**
     * Constructor for the token. You can only create token for session service or library service
     * to use by {@link MediaController2} or {@link MediaBrowser2}.
     *
     * @param context context
     * @param packageName package name
     * @param serviceName name of service. Can be {@code null} if it's not an service.
     * @param uid uid of the app.
     * @hide
     */
    public SessionToken2(@NonNull Context context, @NonNull String packageName,
            @NonNull String serviceName, int uid) {
        mProvider = ApiLoader.getProvider().createSessionToken2(
                context, this, packageName, serviceName, uid);
    }

    /**
     * Constructor for the token.
     * @hide
     */
    public SessionToken2(@NonNull SessionToken2Provider provider) {
        mProvider = provider;
    }

    @Override
    public int hashCode() {
        return mProvider.hashCode_impl();
    }

    @Override
    public boolean equals(Object obj) {
        return mProvider.equals_impl(obj);
    }

    @Override
    public String toString() {
        return mProvider.toString_impl();
    }

    /**
     * @hide
     */
    public SessionToken2Provider getProvider() {
        return mProvider;
    }

    /**
     * @return uid of the session
     */
    public int getUid() {
        return mProvider.getUid_impl();
    }

    /**
     * @return package name
     */
    public String getPackageName() {
        return mProvider.getPackageName_impl();
    }

    /**
     * @return id
     */
    public String getId() {
        return mProvider.getId_imp();
    }

    /**
     * @return type of the token
     * @see #TYPE_SESSION
     * @see #TYPE_SESSION_SERVICE
     */
    public @TokenType int getType() {
        return mProvider.getType_impl();
    }

    /**
     * Create a token from the bundle, exported by {@link #toBundle()}.
     * @param bundle
     * @return
     */
    public static SessionToken2 fromBundle(@NonNull Bundle bundle) {
        return ApiLoader.getProvider().fromBundle_SessionToken2(bundle);
    }

    /**
     * Create a {@link Bundle} from this token to share it across processes.
     * @return Bundle
     */
    public Bundle toBundle() {
        return mProvider.toBundle_impl();
    }
}

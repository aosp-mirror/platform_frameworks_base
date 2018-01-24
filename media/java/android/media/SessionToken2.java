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
import android.annotation.Nullable;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents an ongoing {@link MediaSession2} or a {@link MediaSessionService2}.
 * If it's representing a session service, it may not be ongoing.
 * <p>
 * This may be passed to apps by the session owner to allow them to create a
 * {@link MediaController2} to communicate with the session.
 * <p>
 * It can be also obtained by {@link MediaSessionManager}.
 * @hide
 */
// TODO(jaewan): Move Token to updatable!
public final class SessionToken2 {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {TYPE_SESSION, TYPE_SESSION_SERVICE, TYPE_LIBRARY_SERVICE})
    public @interface TokenType {
    }

    public static final int TYPE_SESSION = 0;
    public static final int TYPE_SESSION_SERVICE = 1;
    public static final int TYPE_LIBRARY_SERVICE = 2;

    private static final String KEY_TYPE = "android.media.token.type";
    private static final String KEY_PACKAGE_NAME = "android.media.token.package_name";
    private static final String KEY_SERVICE_NAME = "android.media.token.service_name";
    private static final String KEY_ID = "android.media.token.id";
    private static final String KEY_SESSION_BINDER = "android.media.token.session_binder";

    private final @TokenType int mType;
    private final String mPackageName;
    private final String mServiceName;
    private final String mId;
    private final IMediaSession2 mSessionBinder;

    /**
     * Constructor for the token.
     *
     * @hide
     * @param type type
     * @param packageName package name
     * @param id id
     * @param serviceName name of service. Can be {@code null} if it's not an service.
     * @param sessionBinder binder for this session. Can be {@code null} if it's service.
     * @hide
     */
    // TODO(jaewan): UID is also needed.
    // TODO(jaewan): Unhide
    public SessionToken2(@TokenType int type, @NonNull String packageName, @NonNull String id,
            @Nullable String serviceName, @Nullable IMediaSession2 sessionBinder) {
        // TODO(jaewan): Add sanity check.
        mType = type;
        mPackageName = packageName;
        mId = id;
        mServiceName = serviceName;
        mSessionBinder = sessionBinder;
    }

    public int hashCode() {
        final int prime = 31;
        return mType
                + prime * (mPackageName.hashCode()
                + prime * (mId.hashCode()
                + prime * ((mServiceName != null ? mServiceName.hashCode() : 0)
                + prime * (mSessionBinder != null ? mSessionBinder.asBinder().hashCode() : 0))));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        SessionToken2 other = (SessionToken2) obj;
        if (!mPackageName.equals(other.getPackageName())
                || !mServiceName.equals(other.getServiceName())
                || !mId.equals(other.getId())
                || mType != other.getType()) {
            return false;
        }
        if (mSessionBinder == other.getSessionBinder()) {
            return true;
        } else if (mSessionBinder == null || other.getSessionBinder() == null) {
            return false;
        }
        return mSessionBinder.asBinder().equals(other.getSessionBinder().asBinder());
    }

    @Override
    public String toString() {
        return "SessionToken {pkg=" + mPackageName + " id=" + mId + " type=" + mType
                + " service=" + mServiceName + " binder=" + mSessionBinder + "}";
    }

    /**
     * @return package name
     */
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * @return id
     */
    public String getId() {
        return mId;
    }

    /**
     * @return type of the token
     * @see #TYPE_SESSION
     * @see #TYPE_SESSION_SERVICE
     */
    public @TokenType int getType() {
        return mType;
    }

    /**
     * @return session binder.
     * @hide
     */
    public @Nullable IMediaSession2 getSessionBinder() {
        return mSessionBinder;
    }

    /**
     * @return service name if it's session service.
     * @hide
     */
    public @Nullable String getServiceName() {
        return mServiceName;
    }

    /**
     * Create a token from the bundle, exported by {@link #toBundle()}.
     *
     * @param bundle
     * @return
     */
    public static SessionToken2 fromBundle(@NonNull Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        final @TokenType int type = bundle.getInt(KEY_TYPE, -1);
        final String packageName = bundle.getString(KEY_PACKAGE_NAME);
        final String serviceName = bundle.getString(KEY_SERVICE_NAME);
        final String id = bundle.getString(KEY_ID);
        final IBinder sessionBinder = bundle.getBinder(KEY_SESSION_BINDER);

        // Sanity check.
        switch (type) {
            case TYPE_SESSION:
                if (!(sessionBinder instanceof IMediaSession2)) {
                    throw new IllegalArgumentException("Session needs sessionBinder");
                }
                break;
            case TYPE_SESSION_SERVICE:
                if (TextUtils.isEmpty(serviceName)) {
                    throw new IllegalArgumentException("Session service needs service name");
                }
                if (sessionBinder != null && !(sessionBinder instanceof IMediaSession2)) {
                    throw new IllegalArgumentException("Invalid session binder");
                }
                break;
            default:
                throw new IllegalArgumentException("Invalid type");
        }
        if (TextUtils.isEmpty(packageName) || id == null) {
            throw new IllegalArgumentException("Package name nor ID cannot be null.");
        }
        // TODO(jaewan): Revisit here when we add connection callback to the session for individual
        //               controller's permission check. With it, sessionBinder should be available
        //               if and only if for session, not session service.
        return new SessionToken2(type, packageName, id, serviceName,
                sessionBinder != null ? IMediaSession2.Stub.asInterface(sessionBinder) : null);
    }

    /**
     * Create a {@link Bundle} from this token to share it across processes.
     *
     * @return Bundle
     */
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString(KEY_PACKAGE_NAME, mPackageName);
        bundle.putString(KEY_SERVICE_NAME, mServiceName);
        bundle.putString(KEY_ID, mId);
        bundle.putInt(KEY_TYPE, mType);
        bundle.putBinder(KEY_SESSION_BINDER,
                mSessionBinder != null ? mSessionBinder.asBinder() : null);
        return bundle;
    }
}

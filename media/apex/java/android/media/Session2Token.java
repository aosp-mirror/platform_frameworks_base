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
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;

/**
 * Represents an ongoing MediaSession2 or a MediaSession2Service.
 * If it's representing a session service, it may not be ongoing.
 * <p>
 * This API is not generally intended for third party application developers.
 * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/media2/package-summary.html">Media2 Library</a>
 * for consistent behavior across all devices.
 * <p>
 * This may be passed to apps by the session owner to allow them to create a
 * MediaController2 to communicate with the session.
 * <p>
 * It can be also obtained by {@link android.media.session.MediaSessionManager}.
 */
public final class Session2Token implements Parcelable {
    private static final String TAG = "Session2Token";

    public static final Creator<Session2Token> CREATOR = new Creator<Session2Token>() {
        @Override
        public Session2Token createFromParcel(Parcel p) {
            return new Session2Token(p);
        }

        @Override
        public Session2Token[] newArray(int size) {
            return new Session2Token[size];
        }
    };

    /**
     * The {@link Intent} that must be declared for the session service.
     * @hide
     */
    @SystemApi
    public static final String SESSION_SERVICE_INTERFACE = "android.media.MediaSession2Service";

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "TYPE_", value = {TYPE_SESSION, TYPE_SESSION_SERVICE})
    public @interface TokenType {
    }

    /**
     * Type for MediaSession2.
     */
    public static final int TYPE_SESSION = 0;

    /**
     * Type for MediaSession2Service.
     */
    public static final int TYPE_SESSION_SERVICE = 1;

    private final String mSessionId;
    private final int mPid;
    private final int mUid;
    @TokenType
    private final int mType;
    private final String mPackageName;
    private final String mServiceName;
    private final ComponentName mComponentName;
    private final Bundle mExtras;

    private boolean mDestroyed = false;

    /**
     * Constructor for the token with type {@link #TYPE_SESSION_SERVICE}.
     *
     * @param context The context.
     * @param serviceComponent The component name of the service.
     */
    public Session2Token(@NonNull Context context, @NonNull ComponentName serviceComponent) {
        if (context == null) {
            throw new IllegalArgumentException("context shouldn't be null");
        }
        if (serviceComponent == null) {
            throw new IllegalArgumentException("serviceComponent shouldn't be null");
        }

        final PackageManager manager = context.getPackageManager();
        final int uid = getUid(manager, serviceComponent.getPackageName());

        if (!isInterfaceDeclared(manager, SESSION_SERVICE_INTERFACE, serviceComponent)) {
            Log.w(TAG, serviceComponent + " doesn't implement MediaSession2Service.");
        }
        mSessionId = null;
        mComponentName = serviceComponent;
        mPackageName = serviceComponent.getPackageName();
        mServiceName = serviceComponent.getClassName();
        mPid = -1;
        mUid = uid;
        mType = TYPE_SESSION_SERVICE;
        mExtras = null;
    }

    /**
     * Constructor for the token with type {@link #TYPE_SESSION}.
     *
     * @param context The context.
     * @param sessionId The ID of the session. Should be unique.
     * @param extras The extras.
     * @hide
     */
    @SystemApi
    public Session2Token(@NonNull Context context, @NonNull String sessionId,
            @Nullable Bundle extras) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId shouldn't be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context shouldn't be null");
        }
        mSessionId = sessionId;
        mPid = Process.myPid();
        mUid = Process.myUid();
        mType = TYPE_SESSION;
        mPackageName = context.getPackageName();
        mExtras = extras;
        mServiceName = null;
        mComponentName = null;
    }

    Session2Token(Parcel in) {
        mSessionId = in.readString();
        mPid = in.readInt();
        mUid = in.readInt();
        mType = in.readInt();
        mPackageName = in.readString();
        mServiceName = in.readString();
        mComponentName = ComponentName.unflattenFromString(in.readString());
        mExtras = in.readParcelable(null);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mSessionId);
        dest.writeInt(mPid);
        dest.writeInt(mUid);
        dest.writeInt(mType);
        dest.writeString(mPackageName);
        dest.writeString(mServiceName);
        dest.writeString(mComponentName == null ? "" : mComponentName.flattenToString());
        dest.writeParcelable(mExtras, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSessionId, mPid, mUid, mType, mPackageName, mServiceName);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Session2Token)) {
            return false;
        }
        Session2Token other = (Session2Token) obj;
        return TextUtils.equals(mSessionId, other.mSessionId)
                && mPid == other.mPid
                && mUid == other.mUid
                && mType == other.mType
                && TextUtils.equals(mPackageName, other.mPackageName)
                && TextUtils.equals(mServiceName, other.mServiceName);
    }

    @Override
    public String toString() {
        return "Session2Token {pkg=" + mPackageName + " type=" + mType
                + " service=" + mServiceName + "}";
    }

    /**
     * @return pid of the session
     * @hide
     */
    @SystemApi
    public int getPid() {
        return mPid;
    }

    /**
     * @return uid of the session
     */
    public int getUid() {
        return mUid;
    }

    /**
     * @return package name of the session
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * @return service name of the session. Can be {@code null} for {@link #TYPE_SESSION}.
     */
    @Nullable
    public String getServiceName() {
        return mServiceName;
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
     * @return extras
     * @hide
     */
    @SystemApi
    @NonNull
    public Bundle getExtras() {
        return mExtras == null ? new Bundle() : new Bundle(mExtras);
    }

    /**
     * Destroys this session token. After this method is called,
     * {@link MediaSessionManager#notifySession2Created(Session2Token)} should not be called
     * with this token.
     *
     * @see MediaSessionManager#notifySession2Created(Session2Token)
     * @hide
     */
    @SystemApi
    public void destroy() {
        mDestroyed = true;
    }

    /**
     * @return whether this token is destroyed
     * @hide
     */
    @SystemApi
    public boolean isDestroyed() {
        return mDestroyed;
    }

    private static boolean isInterfaceDeclared(PackageManager manager, String serviceInterface,
            ComponentName serviceComponent) {
        Intent serviceIntent = new Intent(serviceInterface);
        // Use queryIntentServices to find services with MediaSession2Service.SERVICE_INTERFACE.
        // We cannot use resolveService with intent specified class name, because resolveService
        // ignores actions if Intent.setClassName() is specified.
        serviceIntent.setPackage(serviceComponent.getPackageName());

        List<ResolveInfo> list = manager.queryIntentServices(
                serviceIntent, PackageManager.GET_META_DATA);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                ResolveInfo resolveInfo = list.get(i);
                if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                    continue;
                }
                if (TextUtils.equals(
                        resolveInfo.serviceInfo.name, serviceComponent.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int getUid(PackageManager manager, String packageName) {
        try {
            return manager.getApplicationInfo(packageName, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Cannot find package " + packageName);
        }
    }
}

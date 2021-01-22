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

package android.net;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Provides identifying information of a QoS session.  Sent to an application through
 * {@link QosCallback}.
 *
 * @hide
 */
@SystemApi
public final class QosSession implements Parcelable {

    /**
     * The {@link QosSession} is a LTE EPS Session.
     */
    public static final int TYPE_EPS_BEARER = 1;

    private final int mSessionId;

    private final int mSessionType;

    /**
     * Gets the unique id of the session that is used to differentiate sessions across different
     * types.
     * <p/>
     * Note: Different qos sessions can be provided by different actors.
     *
     * @return the unique id
     */
    public long getUniqueId() {
        return (long) mSessionType << 32 | mSessionId;
    }

    /**
     * Gets the session id that is unique within that type.
     * <p/>
     * Note: The session id is set by the actor providing the qos.  It can be either manufactured by
     * the actor, but also may have a particular meaning within that type.  For example, using the
     * bearer id as the session id for {@link android.telephony.data.EpsBearerQosSessionAttributes}
     * is a straight forward way to keep the sessions unique from one another within that type.
     *
     * @return the id of the session
     */
    public int getSessionId() {
        return mSessionId;
    }

    /**
     * Gets the type of session.
     */
    @QosSessionType
    public int getSessionType() {
        return mSessionType;
    }

    /**
     * Creates a {@link QosSession}.
     *
     * @param sessionId uniquely identifies the session across all sessions of the same type
     * @param sessionType the type of session
     */
    public QosSession(final int sessionId, @QosSessionType final int sessionType) {
        //Ensures the session id is unique across types of sessions
        mSessionId = sessionId;
        mSessionType = sessionType;
    }


    @Override
    public String toString() {
        return "QosSession{"
                + "mSessionId=" + mSessionId
                + ", mSessionType=" + mSessionType
                + '}';
    }

    /**
     * Annotations for types of qos sessions.
     */
    @IntDef(value = {
            TYPE_EPS_BEARER,
    })
    @interface QosSessionType {}

    private QosSession(final Parcel in) {
        mSessionId = in.readInt();
        mSessionType = in.readInt();
    }

    @NonNull
    public static final Creator<QosSession> CREATOR = new Creator<QosSession>() {
        @NonNull
        @Override
        public QosSession createFromParcel(@NonNull final Parcel in) {
            return new QosSession(in);
        }

        @NonNull
        @Override
        public QosSession[] newArray(final int size) {
            return new QosSession[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        dest.writeInt(mSessionId);
        dest.writeInt(mSessionType);
    }
}

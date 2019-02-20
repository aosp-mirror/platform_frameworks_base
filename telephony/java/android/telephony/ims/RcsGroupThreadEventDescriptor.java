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
package android.telephony.ims;

import android.os.Parcel;

/**
 * @hide - used only for internal communication with the ircs service
 */
public abstract class RcsGroupThreadEventDescriptor extends RcsEventDescriptor {
    protected final int mRcsGroupThreadId;
    protected final int mOriginatingParticipantId;

    RcsGroupThreadEventDescriptor(long timestamp, int rcsGroupThreadId,
            int originatingParticipantId) {
        super(timestamp);
        mRcsGroupThreadId = rcsGroupThreadId;
        mOriginatingParticipantId = originatingParticipantId;
    }

    RcsGroupThreadEventDescriptor(Parcel in) {
        super(in);
        mRcsGroupThreadId = in.readInt();
        mOriginatingParticipantId = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mRcsGroupThreadId);
        dest.writeInt(mOriginatingParticipantId);
    }
}

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
 * limitations under the License.
 */
package android.telephony.ims;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;

/**
 * An event that indicates an {@link RcsGroupThread}'s icon was changed. Please see R6-2-5 - GSMA
 * RCC.71 (RCS Universal Profile Service Definition Document)
 */
public final class RcsGroupThreadIconChangedEvent extends RcsGroupThreadEvent {
    private final Uri mNewIcon;

    /**
     * Creates a new {@link RcsGroupThreadIconChangedEvent}. This event is not persisted into
     * storage until {@link RcsMessageStore#persistRcsEvent(RcsEvent)} is called.
     *
     * @param timestamp The timestamp of when this event happened, in milliseconds passed after
     *                  midnight, January 1st, 1970 UTC
     * @param rcsGroupThread The {@link RcsGroupThread} that this event happened on
     * @param originatingParticipant The {@link RcsParticipant} that changed the
     *                               {@link RcsGroupThread}'s icon.
     * @param newIcon {@link Uri} to the new icon of this {@link RcsGroupThread}
     * @see RcsMessageStore#persistRcsEvent(RcsEvent)
     */
    public RcsGroupThreadIconChangedEvent(long timestamp, @NonNull RcsGroupThread rcsGroupThread,
            @NonNull RcsParticipant originatingParticipant, @Nullable Uri newIcon) {
        super(timestamp, rcsGroupThread.getThreadId(), originatingParticipant.getId());
        mNewIcon = newIcon;
    }

    /**
     * @return Returns the {@link Uri} to the icon of the {@link RcsGroupThread} after this
     * {@link RcsGroupThreadIconChangedEvent} occured.
     */
    @Nullable
    public Uri getNewIcon() {
        return mNewIcon;
    }

    /**
     * Persists the event to the data store.
     *
     * @hide - not meant for public use.
     */
    @Override
    public void persist() throws RcsMessageStoreException {
        // TODO ensure failure throws
        RcsControllerCall.call(iRcs -> iRcs.createGroupThreadIconChangedEvent(
                getTimestamp(), getRcsGroupThread().getThreadId(),
                getOriginatingParticipant().getId(), mNewIcon));
    }
}

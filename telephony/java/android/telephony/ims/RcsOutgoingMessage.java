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

import android.annotation.NonNull;
import android.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.List;

/**
 * This is a single instance of a message sent over RCS.
 *
 * @hide
 */
public class RcsOutgoingMessage extends RcsMessage {
    RcsOutgoingMessage(int id) {
        super(id);
    }

    /**
     * @return Returns the {@link RcsOutgoingMessageDelivery}s associated with this message. Please
     * note that the deliveries returned for the {@link RcsOutgoingMessage} may not always match the
     * {@link RcsParticipant}s on the {@link RcsGroupThread} as the group recipients may have
     * changed.
     * @throws RcsMessageStoreException if the outgoing deliveries could not be read from storage.
     */
    @NonNull
    @WorkerThread
    public List<RcsOutgoingMessageDelivery> getOutgoingDeliveries()
            throws RcsMessageStoreException {
        int[] deliveryParticipants;
        List<RcsOutgoingMessageDelivery> messageDeliveries = new ArrayList<>();

        deliveryParticipants = RcsControllerCall.call(
                iRcs -> iRcs.getMessageRecipients(mId));

        if (deliveryParticipants != null) {
            for (Integer deliveryParticipant : deliveryParticipants) {
                messageDeliveries.add(new RcsOutgoingMessageDelivery(deliveryParticipant, mId));
            }
        }

        return messageDeliveries;
    }

    /**
     * @return Returns {@code false} as this is not an incoming message.
     */
    @Override
    public boolean isIncoming() {
        return false;
    }
}

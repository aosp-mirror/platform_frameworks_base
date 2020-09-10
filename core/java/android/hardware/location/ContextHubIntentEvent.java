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
package android.hardware.location;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.content.Intent;

import java.util.Objects;

/**
 * A helper class to retrieve information about a Intent event received for a PendingIntent
 * registered with {@link ContextHubManager.createClient(ContextHubInfo, PendingIntent, long)}.
 * This object can only be created through the factory method
 * {@link ContextHubIntentEvent.fromIntent(Intent)}.
 *
 * @hide
 */
@SystemApi
public class ContextHubIntentEvent {
    @ContextHubManager.Event private final int mEventType;

    @NonNull private final ContextHubInfo mContextHubInfo;

    private final long mNanoAppId;

    private final NanoAppMessage mNanoAppMessage;

    private final int mNanoAppAbortCode;

    private ContextHubIntentEvent(
            @NonNull ContextHubInfo contextHubInfo, @ContextHubManager.Event int eventType,
            long nanoAppId, NanoAppMessage nanoAppMessage, int nanoAppAbortCode) {
        mContextHubInfo = contextHubInfo;
        mEventType = eventType;
        mNanoAppId = nanoAppId;
        mNanoAppMessage = nanoAppMessage;
        mNanoAppAbortCode = nanoAppAbortCode;
    }

    private ContextHubIntentEvent(
            @NonNull ContextHubInfo contextHubInfo, @ContextHubManager.Event int eventType) {
        this(contextHubInfo, eventType, -1 /* nanoAppId */, null /* nanoAppMessage */,
                -1 /* nanoAppAbortCode */);
    }

    private ContextHubIntentEvent(
            @NonNull ContextHubInfo contextHubInfo, @ContextHubManager.Event int eventType,
            long nanoAppId) {
        this(contextHubInfo, eventType, nanoAppId, null /* nanoAppMessage */,
                -1 /* nanoAppAbortCode */);
    }

    private ContextHubIntentEvent(
            @NonNull ContextHubInfo contextHubInfo, @ContextHubManager.Event int eventType,
            long nanoAppId, @NonNull NanoAppMessage nanoAppMessage) {
        this(contextHubInfo, eventType, nanoAppId, nanoAppMessage, -1 /* nanoAppAbortCode */);
    }

    private ContextHubIntentEvent(
            @NonNull ContextHubInfo contextHubInfo, @ContextHubManager.Event int eventType,
            long nanoAppId, int nanoAppAbortCode) {
        this(contextHubInfo, eventType, nanoAppId, null /* nanoAppMessage */, nanoAppAbortCode);
    }

    /**
     * Creates a ContextHubIntentEvent object from an Intent received through a PendingIntent
     * registered with {@link ContextHubManager.createClient(ContextHubInfo, PendingIntent, long)}.
     *
     * @param intent the Intent object from an Intent event
     * @return the ContextHubIntentEvent object describing the event
     *
     * @throws IllegalArgumentException if the Intent was not a valid intent
     */
    @NonNull
    public static ContextHubIntentEvent fromIntent(@NonNull Intent intent) {
        Objects.requireNonNull(intent, "Intent cannot be null");

        hasExtraOrThrow(intent, ContextHubManager.EXTRA_CONTEXT_HUB_INFO);
        ContextHubInfo info = intent.getParcelableExtra(ContextHubManager.EXTRA_CONTEXT_HUB_INFO);
        if (info == null) {
            throw new IllegalArgumentException("ContextHubInfo extra was null");
        }

        int eventType = getIntExtraOrThrow(intent, ContextHubManager.EXTRA_EVENT_TYPE);
        ContextHubIntentEvent event;
        switch (eventType) {
            case ContextHubManager.EVENT_NANOAPP_LOADED:
            case ContextHubManager.EVENT_NANOAPP_UNLOADED:
            case ContextHubManager.EVENT_NANOAPP_ENABLED:
            case ContextHubManager.EVENT_NANOAPP_DISABLED:
            case ContextHubManager.EVENT_NANOAPP_ABORTED:
            case ContextHubManager.EVENT_NANOAPP_MESSAGE: // fall through
                long nanoAppId = getLongExtraOrThrow(intent, ContextHubManager.EXTRA_NANOAPP_ID);
                if (eventType == ContextHubManager.EVENT_NANOAPP_MESSAGE) {
                    hasExtraOrThrow(intent, ContextHubManager.EXTRA_MESSAGE);
                    NanoAppMessage message =
                            intent.getParcelableExtra(ContextHubManager.EXTRA_MESSAGE);
                    if (message == null) {
                        throw new IllegalArgumentException("NanoAppMessage extra was null");
                    }

                    event = new ContextHubIntentEvent(info, eventType, nanoAppId, message);
                } else if (eventType == ContextHubManager.EVENT_NANOAPP_ABORTED) {
                    int nanoAppAbortCode = getIntExtraOrThrow(
                            intent, ContextHubManager.EXTRA_NANOAPP_ABORT_CODE);
                    event = new ContextHubIntentEvent(info, eventType, nanoAppId, nanoAppAbortCode);
                } else {
                    event = new ContextHubIntentEvent(info, eventType, nanoAppId);
                }
                break;

            case ContextHubManager.EVENT_HUB_RESET:
                event = new ContextHubIntentEvent(info, eventType);
                break;

            default:
                throw new IllegalArgumentException("Unknown intent event type " + eventType);
        }

        return event;
    }

    /**
     * @return the event type of this Intent event
     */
    @ContextHubManager.Event
    public int getEventType() {
        return mEventType;
    }

    /**
     * @return the ContextHubInfo object describing the Context Hub this event was for
     */
    @NonNull
    public ContextHubInfo getContextHubInfo() {
        return mContextHubInfo;
    }

    /**
     * @return the ID of the nanoapp this event was for
     *
     * @throws UnsupportedOperationException if the event did not have a nanoapp associated
     */
    public long getNanoAppId() {
        if (mEventType == ContextHubManager.EVENT_HUB_RESET) {
            throw new UnsupportedOperationException(
                    "Cannot invoke getNanoAppId() on Context Hub reset event");
        }
        return mNanoAppId;
    }

    /**
     * @return the nanoapp's abort code
     *
     * @throws UnsupportedOperationException if this was not a nanoapp abort event
     */
    public int getNanoAppAbortCode() {
        if (mEventType != ContextHubManager.EVENT_NANOAPP_ABORTED) {
            throw new UnsupportedOperationException(
                    "Cannot invoke getNanoAppAbortCode() on non-abort event: " + mEventType);
        }
        return mNanoAppAbortCode;
    }

    /**
     * @return the message from a nanoapp
     *
     * @throws UnsupportedOperationException if this was not a nanoapp message event
     */
    @NonNull
    public NanoAppMessage getNanoAppMessage() {
        if (mEventType != ContextHubManager.EVENT_NANOAPP_MESSAGE) {
            throw new UnsupportedOperationException(
                    "Cannot invoke getNanoAppMessage() on non-message event: " + mEventType);
        }
        return mNanoAppMessage;
    }

    @NonNull
    @Override
    public String toString() {
        String out = "ContextHubIntentEvent[eventType = " + mEventType
                + ", contextHubId = " + mContextHubInfo.getId();

        if (mEventType != ContextHubManager.EVENT_HUB_RESET) {
            out += ", nanoAppId = 0x" + Long.toHexString(mNanoAppId);
        }
        if (mEventType == ContextHubManager.EVENT_NANOAPP_ABORTED) {
            out += ", nanoAppAbortCode = " + mNanoAppAbortCode;
        }
        if (mEventType == ContextHubManager.EVENT_NANOAPP_MESSAGE) {
            out += ", nanoAppMessage = " + mNanoAppMessage;
        }

        return out + "]";
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (object == this) {
            return true;
        }

        boolean isEqual = false;
        if (object instanceof ContextHubIntentEvent) {
            ContextHubIntentEvent other = (ContextHubIntentEvent) object;
            if (other.getEventType() == mEventType
                    && other.getContextHubInfo().equals(mContextHubInfo)) {
                isEqual = true;
                try {
                    if (mEventType != ContextHubManager.EVENT_HUB_RESET) {
                        isEqual &= (other.getNanoAppId() == mNanoAppId);
                    }
                    if (mEventType == ContextHubManager.EVENT_NANOAPP_ABORTED) {
                        isEqual &= (other.getNanoAppAbortCode() == mNanoAppAbortCode);
                    }
                    if (mEventType == ContextHubManager.EVENT_NANOAPP_MESSAGE) {
                        isEqual &= other.getNanoAppMessage().equals(mNanoAppMessage);
                    }
                } catch (UnsupportedOperationException e) {
                    isEqual = false;
                }
            }
        }

        return isEqual;
    }

    private static void hasExtraOrThrow(Intent intent, String extra) {
        if (!intent.hasExtra(extra)) {
            throw new IllegalArgumentException("Intent did not have extra: " + extra);
        }
    }

    private static int getIntExtraOrThrow(Intent intent, String extra) {
        hasExtraOrThrow(intent, extra);
        return intent.getIntExtra(extra, -1 /* defaultValue */);
    }

    private static long getLongExtraOrThrow(Intent intent, String extra) {
        hasExtraOrThrow(intent, extra);
        return intent.getLongExtra(extra, -1 /* defaultValue */);
    }
}

/*
 ** Copyright 2015, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Pools;
import android.util.Pools.Pool;
import android.util.Slog;
import android.view.InputEventConsistencyVerifier;
import android.view.KeyEvent;
import android.view.WindowManagerPolicy;

import com.android.server.accessibility.AccessibilityManagerService.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dispatcher to send KeyEvents to all accessibility services that are able to process them.
 * Events that are handled by one or more services are consumed. Events that are not processed
 * by any service (or time out before a service reports them as handled) are passed along to
 * the rest of the system.
 *
 * The class assumes that services report their return values in order, which is valid because
 * they process each call to {@code AccessibilityService.onKeyEvent} on a single thread, and so
 * don't see the N+1th event until they have processed the Nth event.
 */
public class KeyEventDispatcher {
    // Debugging
    private static final String LOG_TAG = "KeyEventDispatcher";
    private static final boolean DEBUG = false;
    /* KeyEvents must be processed in this time interval */
    private static final long ON_KEY_EVENT_TIMEOUT_MILLIS = 500;
    private static final int MSG_ON_KEY_EVENT_TIMEOUT = 1;
    private static final int MAX_POOL_SIZE = 10;

    private final Pool<PendingKeyEvent> mPendingEventPool = new Pools.SimplePool<>(MAX_POOL_SIZE);
    private final Object mLock;

    /*
     * Track events sent to each service. If a KeyEvent is to be sent to at least one service,
     * a corresponding PendingKeyEvent is created for it. This PendingKeyEvent is placed in
     * the list for each service its KeyEvent is sent to. It is removed from the list when
     * the service calls setOnKeyEventResult, or when we time out waiting for the service to
     * respond.
     */
    private final Map<Service, ArrayList<PendingKeyEvent>> mPendingEventsMap = new ArrayMap<>();

    private final InputEventConsistencyVerifier mSentEventsVerifier;
    private final Handler mHandlerToSendKeyEventsToInputFilter;
    private final int mMessageTypeForSendKeyEvent;
    private final Handler mKeyEventTimeoutHandler;
    private final PowerManager mPowerManager;

    /**
     * @param handlerToSendKeyEventsToInputFilter The handler to which to post {@code KeyEvent}s
     * that have not been handled by any accessibility service.
     * @param messageTypeForSendKeyEvent The field to populate {@code message.what} for the
     * message that carries a {@code KeyEvent} to be sent to the input filter
     * @param lock The lock used for all synchronization in this package. This lock must be held
     * when calling {@code notifyKeyEventLocked}
     * @param powerManager The power manager to alert to user activity if a KeyEvent is processed
     * by a service
     */
    public KeyEventDispatcher(Handler handlerToSendKeyEventsToInputFilter,
            int messageTypeForSendKeyEvent, Object lock,
            PowerManager powerManager) {
        if (InputEventConsistencyVerifier.isInstrumentationEnabled()) {
            mSentEventsVerifier = new InputEventConsistencyVerifier(
                    this, 0, KeyEventDispatcher.class.getSimpleName());
        } else {
            mSentEventsVerifier = null;
        }
        mHandlerToSendKeyEventsToInputFilter = handlerToSendKeyEventsToInputFilter;
        mMessageTypeForSendKeyEvent = messageTypeForSendKeyEvent;
        mKeyEventTimeoutHandler =
                new Handler(mHandlerToSendKeyEventsToInputFilter.getLooper(), new Callback());
        mLock = lock;
        mPowerManager = powerManager;
    }

    /**
     * Notify that a new KeyEvent is available to accessibility services. Must be called with the
     * lock used to construct this object held. The boundServices list must also be protected
     * by a lock.
     *
     * @param event The new key event
     * @param policyFlags Flags for the event
     * @param boundServices A list of currently bound AccessibilityServices
     *
     * @return {@code true} if the event was passed to at least one AccessibilityService,
     * {@code false} otherwise.
     */
    // TODO: The locking policy for boundServices needs some thought.
    public boolean notifyKeyEventLocked(
            KeyEvent event, int policyFlags, List<Service> boundServices) {
        PendingKeyEvent pendingKeyEvent = null;
        KeyEvent localClone = KeyEvent.obtain(event);
        for (int i = 0; i < boundServices.size(); i++) {
            Service service = boundServices.get(i);
            // Key events are handled only by services that declared
            // this capability and requested to filter key events.
            if (!service.mRequestFilterKeyEvents || (service.mServiceInterface == null)) {
                continue;
            }
            int filterKeyEventBit = service.mAccessibilityServiceInfo.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS;
            if (filterKeyEventBit == 0) {
                continue;
            }

            try {
                // The event will be cloned in the IPC call, so it doesn't need to be here.
                service.mServiceInterface.onKeyEvent(localClone, localClone.getSequenceNumber());
            } catch (RemoteException re) {
                continue;
            }

            if (pendingKeyEvent == null) {
                pendingKeyEvent = obtainPendingEventLocked(localClone, policyFlags);
            }
            ArrayList<PendingKeyEvent> pendingEventList = mPendingEventsMap.get(service);
            if (pendingEventList == null) {
                pendingEventList = new ArrayList<>();
                mPendingEventsMap.put(service, pendingEventList);
            }
            pendingEventList.add(pendingKeyEvent);
            pendingKeyEvent.referenceCount++;
        }

        if (pendingKeyEvent == null) {
            localClone.recycle();
            return false;
        }

        Message message = mKeyEventTimeoutHandler.obtainMessage(
                MSG_ON_KEY_EVENT_TIMEOUT, pendingKeyEvent);
        mKeyEventTimeoutHandler.sendMessageDelayed(message, ON_KEY_EVENT_TIMEOUT_MILLIS);
        return true;
    }

    /**
     * Set the result from onKeyEvent from one service.
     *
     * @param service The service setting the result
     * @param handled {@code true} if the service handled the {@code KeyEvent}
     * @param sequence The sequence number of the {@code KeyEvent}
     */
    public void setOnKeyEventResult(Service service, boolean handled, int sequence) {
        synchronized (mLock) {
            PendingKeyEvent pendingEvent =
                    removeEventFromListLocked(mPendingEventsMap.get(service), sequence);
            if (pendingEvent != null) {
                if (handled && !pendingEvent.handled) {
                    pendingEvent.handled = handled;
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        mPowerManager.userActivity(pendingEvent.event.getEventTime(),
                                PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY, 0);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
                removeReferenceToPendingEventLocked(pendingEvent);
            }
        }
    }

    /**
     * Flush all pending key events for a service, treating all of them as unhandled
     *
     * @param service The service for which to flush events
     */
    public void flush(Service service) {
        synchronized (mLock) {
            List<PendingKeyEvent> pendingEvents = mPendingEventsMap.get(service);
            if (pendingEvents != null) {
                for (int i = 0; i < pendingEvents.size(); i++) {
                    PendingKeyEvent pendingEvent = pendingEvents.get(i);
                    removeReferenceToPendingEventLocked(pendingEvent);
                }
                mPendingEventsMap.remove(service);
            }
        }
    }

    private PendingKeyEvent obtainPendingEventLocked(KeyEvent event, int policyFlags) {
        PendingKeyEvent pendingEvent = mPendingEventPool.acquire();
        if (pendingEvent == null) {
            pendingEvent = new PendingKeyEvent();
        }
        pendingEvent.event = event;
        pendingEvent.policyFlags = policyFlags;
        pendingEvent.referenceCount = 0;
        pendingEvent.handled = false;
        return pendingEvent;
    }

    private static PendingKeyEvent removeEventFromListLocked(
            List<PendingKeyEvent> listOfEvents, int sequence) {
        /* In normal operation, the event should be first */
        for (int i = 0; i < listOfEvents.size(); i++) {
            PendingKeyEvent pendingKeyEvent = listOfEvents.get(i);
            if (pendingKeyEvent.event.getSequenceNumber() == sequence) {
                    /*
                     * Removing the first element of the ArrayList can be slow if there are a lot
                     * of events backed up, but for a handful of events it's better than incurring
                     * the fixed overhead of LinkedList. An ArrayList optimized for removing the
                     * first element (by treating the underlying array as a circular buffer) would
                     * be ideal.
                     */
                listOfEvents.remove(pendingKeyEvent);
                return pendingKeyEvent;
            }
        }
        return null;
    }

    /**
     * @param pendingEvent The event whose reference count should be decreased
     * @return {@code true} if the event was release, {@code false} if not.
     */
    private boolean removeReferenceToPendingEventLocked(PendingKeyEvent pendingEvent) {
        if (--pendingEvent.referenceCount > 0) {
            return false;
        }
        mKeyEventTimeoutHandler.removeMessages(MSG_ON_KEY_EVENT_TIMEOUT, pendingEvent);
        if (!pendingEvent.handled) {
                /* Pass event to input filter */
            if (DEBUG) {
                Slog.i(LOG_TAG, "Injecting event: " + pendingEvent.event);
            }
            if (mSentEventsVerifier != null) {
                mSentEventsVerifier.onKeyEvent(pendingEvent.event, 0);
            }
            int policyFlags = pendingEvent.policyFlags | WindowManagerPolicy.FLAG_PASS_TO_USER;
            mHandlerToSendKeyEventsToInputFilter
                    .obtainMessage(mMessageTypeForSendKeyEvent, policyFlags, 0, pendingEvent.event)
                    .sendToTarget();
        } else {
            pendingEvent.event.recycle();
        }
        mPendingEventPool.release(pendingEvent);
        return true;
    }

    private static final class PendingKeyEvent {
        /* Event and policyFlag provided in notifyKeyEventLocked */
        KeyEvent event;
        int policyFlags;
        /*
         * The referenceCount optimizes the process of determining the number of services
         * still holding a KeyEvent. It must be equal to the number of times the PendingEvent
         * appears in mPendingEventsMap, or PendingEvents will leak.
         */
        int referenceCount;
        /* Whether or not at least one service had handled this event */
        boolean handled;
    }

    private class Callback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message message) {
            if (message.what != MSG_ON_KEY_EVENT_TIMEOUT) {
                throw new IllegalArgumentException("Unknown message: " + message.what);
            }
            PendingKeyEvent pendingKeyEvent = (PendingKeyEvent) message.obj;
            synchronized (mLock) {
                for (ArrayList<PendingKeyEvent> listForService : mPendingEventsMap.values()) {
                    if (listForService.remove(pendingKeyEvent)) {
                        if(removeReferenceToPendingEventLocked(pendingKeyEvent)) {
                            break;
                        }
                    }
                }
            }
            return true;
        }
    }
}

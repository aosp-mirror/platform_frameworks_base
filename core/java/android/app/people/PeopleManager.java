/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.people;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * This class allows interaction with conversation and people data.
 */
@SystemService(Context.PEOPLE_SERVICE)
public final class PeopleManager {

    private static final String LOG_TAG = PeopleManager.class.getSimpleName();

    /**
     * @hide
     */
    @VisibleForTesting
    public Map<ConversationListener, Pair<Executor, IConversationListener>>
            mConversationListeners = new HashMap<>();

    @NonNull
    private Context mContext;

    @NonNull
    private IPeopleManager mService;

    /**
     * @hide
     */
    public PeopleManager(@NonNull Context context) throws ServiceManager.ServiceNotFoundException {
        mContext = context;
        mService = IPeopleManager.Stub.asInterface(ServiceManager.getServiceOrThrow(
                Context.PEOPLE_SERVICE));
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public PeopleManager(@NonNull Context context, IPeopleManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns whether a shortcut has a conversation associated.
     *
     * <p>Requires android.permission.READ_PEOPLE_DATA permission.
     *
     * <p>This method may return different results for the same shortcut over time, as an app adopts
     * conversation features or if a user hasn't communicated with the conversation associated to
     * the shortcut in a while, so the result should not be stored and relied on indefinitely by
     * clients.
     *
     * @param packageName name of the package the conversation is part of
     * @param shortcutId  the shortcut id backing the conversation
     * @return whether the {@shortcutId} is backed by a Conversation.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PEOPLE_DATA)
    public boolean isConversation(@NonNull String packageName, @NonNull String shortcutId) {
        Preconditions.checkStringNotEmpty(packageName);
        Preconditions.checkStringNotEmpty(shortcutId);
        try {
            return mService.isConversation(packageName, mContext.getUserId(), shortcutId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets or updates a {@link ConversationStatus} for a conversation.
     *
     * <p>Statuses are meant to represent current information about the conversation. Like
     * notifications, they are transient and are not persisted beyond a reboot, nor are they
     * backed up and restored.</p>
     * <p>If the provided conversation shortcut is not already pinned, or cached by the system,
     * it will remain cached as long as the status is active.</p>
     *
     * @param conversationId the {@link ShortcutInfo#getId() id} of the shortcut backing the
     *                       conversation that has an active status
     * @param status         the current status for the given conversation
     * @return whether the role is available in the system
     */
    public void addOrUpdateStatus(@NonNull String conversationId,
            @NonNull ConversationStatus status) {
        Preconditions.checkStringNotEmpty(conversationId);
        Objects.requireNonNull(status);
        try {
            mService.addOrUpdateStatus(
                    mContext.getPackageName(), mContext.getUserId(), conversationId, status);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unpublishes a given status from the given conversation.
     *
     * @param conversationId the {@link ShortcutInfo#getId() id} of the shortcut backing the
     *                       conversation that has an active status
     * @param statusId       the {@link ConversationStatus#getId() id} of a published status for the
     *                       given conversation
     */
    public void clearStatus(@NonNull String conversationId, @NonNull String statusId) {
        Preconditions.checkStringNotEmpty(conversationId);
        Preconditions.checkStringNotEmpty(statusId);
        try {
            mService.clearStatus(
                    mContext.getPackageName(), mContext.getUserId(), conversationId, statusId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes all published statuses for the given conversation.
     *
     * @param conversationId the {@link ShortcutInfo#getId() id} of the shortcut backing the
     *                       conversation that has one or more active statuses
     */
    public void clearStatuses(@NonNull String conversationId) {
        Preconditions.checkStringNotEmpty(conversationId);
        try {
            mService.clearStatuses(
                    mContext.getPackageName(), mContext.getUserId(), conversationId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all of the currently published statuses for a given conversation.
     *
     * @param conversationId the {@link ShortcutInfo#getId() id} of the shortcut backing the
     *                       conversation that has one or more active statuses
     */
    public @NonNull List<ConversationStatus> getStatuses(@NonNull String conversationId) {
        try {
            final ParceledListSlice<ConversationStatus> parceledList
                    = mService.getStatuses(
                    mContext.getPackageName(), mContext.getUserId(), conversationId);
            if (parceledList != null) {
                return parceledList.getList();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return new ArrayList<>();
    }

    /**
     * Listeners for conversation changes.
     *
     * @hide
     */
    public interface ConversationListener {
        /**
         * Triggers when the conversation registered for a listener has been updated.
         *
         * @param conversation The conversation with modified data
         * @see IPeopleManager#registerConversationListener(String, int, String,
         * android.app.people.ConversationListener)
         *
         * <p>Only system root and SysUI have access to register the listener.
         */
        default void onConversationUpdate(@NonNull ConversationChannel conversation) {
        }
    }

    /**
     * Register a listener to watch for changes to the conversation identified by {@code
     * packageName}, {@code userId}, and {@code shortcutId}.
     *
     * @param packageName The package name to match and filter the conversation to send updates for.
     * @param userId      The user ID to match and filter the conversation to send updates for.
     * @param shortcutId  The shortcut ID to match and filter the conversation to send updates for.
     * @param listener    The listener to register to receive conversation updates.
     * @param executor    {@link Executor} to handle the listeners. To dispatch listeners to the
     *                    main thread of your application, you can use
     *                    {@link android.content.Context#getMainExecutor()}.
     * @hide
     */
    public void registerConversationListener(String packageName, int userId, String shortcutId,
            ConversationListener listener, Executor executor) {
        requireNonNull(listener, "Listener cannot be null");
        requireNonNull(packageName, "Package name cannot be null");
        requireNonNull(shortcutId, "Shortcut ID cannot be null");
        synchronized (mConversationListeners) {
            IConversationListener proxy = (IConversationListener) new ConversationListenerProxy(
                    executor, listener);
            try {
                mService.registerConversationListener(
                        packageName, userId, shortcutId, proxy);
                mConversationListeners.put(listener,
                        new Pair<>(executor, proxy));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters the listener previously registered to watch conversation changes.
     *
     * @param listener The listener to register to receive conversation updates.
     * @hide
     */
    public void unregisterConversationListener(
            ConversationListener listener) {
        requireNonNull(listener, "Listener cannot be null");

        synchronized (mConversationListeners) {
            if (mConversationListeners.containsKey(listener)) {
                IConversationListener proxy = mConversationListeners.remove(listener).second;
                try {
                    mService.unregisterConversationListener(proxy);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Listener proxy class for {@link ConversationListener}
     *
     * @hide
     */
    private static class ConversationListenerProxy extends
            IConversationListener.Stub {
        private final Executor mExecutor;
        private final ConversationListener mListener;

        ConversationListenerProxy(Executor executor, ConversationListener listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onConversationUpdate(@NonNull ConversationChannel conversation) {
            if (mListener == null || mExecutor == null) {
                // Binder is dead.
                Slog.e(LOG_TAG, "Binder is dead");
                return;
            }
            mExecutor.execute(() -> mListener.onConversationUpdate(conversation));
        }
    }
}

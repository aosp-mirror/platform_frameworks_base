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

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * This class allows interaction with conversation and people data.
 */
@SystemService(Context.PEOPLE_SERVICE)
public final class PeopleManager {

    private static final String LOG_TAG = PeopleManager.class.getSimpleName();

    @NonNull
    private final Context mContext;

    @NonNull
    private final IPeopleManager mService;

    /**
     * @hide
     */
    public PeopleManager(@NonNull Context context) throws ServiceManager.ServiceNotFoundException {
        mContext = context;
        mService = IPeopleManager.Stub.asInterface(ServiceManager.getServiceOrThrow(
                Context.PEOPLE_SERVICE));
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
     * @param shortcutId the shortcut id backing the conversation
     * @return whether the {@shortcutId} is backed by a Conversation.
     *
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
     * @param status the current status for the given conversation
     *
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
     * @param statusId the {@link ConversationStatus#getId() id} of a published status for the given
     *                 conversation
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
}

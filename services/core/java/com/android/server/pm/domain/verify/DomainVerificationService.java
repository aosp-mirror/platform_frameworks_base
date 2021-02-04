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

package com.android.server.pm.domain.verify;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.domain.verify.DomainVerificationSet;
import android.content.pm.domain.verify.DomainVerificationUserSelection;
import android.content.pm.domain.verify.IDomainVerificationManager;
import android.util.Singleton;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;
import com.android.server.pm.domain.verify.models.DomainVerificationPkgState;
import com.android.server.pm.domain.verify.models.DomainVerificationStateMap;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class DomainVerificationService extends SystemService
        implements DomainVerificationManagerInternal {

    private static final String TAG = "DomainVerificationService";

    /**
     * States that are currently alive and attached to a package. Entries are exclusive with the
     * state stored in {@link DomainVerificationSettings}, as any pending/restored state should be
     * immediately attached once its available.
     **/
    @GuardedBy("mLock")
    @NonNull
    private final DomainVerificationStateMap<DomainVerificationPkgState> mAttachedPkgStates =
            new DomainVerificationStateMap<>();

    /**
     * Lock for all state reads/writes.
     */
    private final Object mLock = new Object();

    @NonNull
    private final Singleton<Connection> mConnection;

    @NonNull
    private final DomainVerificationSettings mSettings;

    @NonNull
    private final IDomainVerificationManager.Stub mStub = new DomainVerificationManagerStub(this);

    public DomainVerificationService(@NonNull Context context,
            @NonNull Singleton<Connection> connection) {
        super(context);
        mConnection = connection;
        mSettings = new DomainVerificationSettings();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.DOMAIN_VERIFICATION_SERVICE, mStub);
    }

    @NonNull
    @Override
    public List<String> getValidVerificationPackageNames() {
        return null;
    }

    @Nullable
    @Override
    public DomainVerificationSet getDomainVerificationSet(@NonNull String packageName)
            throws NameNotFoundException {
        return null;
    }

    @Override
    public void setDomainVerificationStatus(@NonNull UUID domainSetId, @NonNull Set<String> domains,
            int state) throws InvalidDomainSetException, NameNotFoundException {
        //TODO(b/163565712): Implement method
        mConnection.get().scheduleWriteSettings();
    }

    @Override
    public void setDomainVerificationLinkHandlingAllowed(@NonNull String packageName,
            boolean allowed) throws NameNotFoundException {
        //TODO(b/163565712): Implement method
        mConnection.get().scheduleWriteSettings();
    }

    public void setDomainVerificationLinkHandlingAllowed(@NonNull String packageName,
            boolean allowed, @UserIdInt int userId) throws NameNotFoundException {
        //TODO(b/163565712): Implement method
        mConnection.get().scheduleWriteSettings();
    }

    @Override
    public void setDomainVerificationUserSelection(@NonNull UUID domainSetId,
            @NonNull Set<String> domains, boolean enabled)
            throws InvalidDomainSetException, NameNotFoundException {
        //TODO(b/163565712): Implement method
        mConnection.get().scheduleWriteSettings();
    }

    public void setDomainVerificationUserSelection(@NonNull UUID domainSetId,
            @NonNull Set<String> domains, boolean enabled, @UserIdInt int userId)
            throws InvalidDomainSetException, NameNotFoundException {
        //TODO(b/163565712): Implement method
        mConnection.get().scheduleWriteSettings();
    }

    @Nullable
    @Override
    public DomainVerificationUserSelection getDomainVerificationUserSelection(
            @NonNull String packageName) throws NameNotFoundException {
        return null;
    }

    @Nullable
    public DomainVerificationUserSelection getDomainVerificationUserSelection(
            @NonNull String packageName, @UserIdInt int userId) throws NameNotFoundException {
        return null;
    }

    @NonNull
    @Override
    public UUID generateNewId() {
        // TODO(b/159952358): Domain set ID collisions
        return UUID.randomUUID();
    }

    @Override
    public void writeSettings(@NonNull TypedXmlSerializer serializer) throws IOException {
        synchronized (mLock) {
            mSettings.writeSettings(serializer, mAttachedPkgStates);
        }
    }

    @Override
    public void readSettings(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        synchronized (mLock) {
            mSettings.readSettings(parser, mAttachedPkgStates);
        }
    }

    @Override
    public void restoreSettings(@NonNull TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        synchronized (mLock) {
            mSettings.restoreSettings(parser, mAttachedPkgStates);
        }
    }

    @Override
    public void clearPackage(@NonNull String packageName) {
        synchronized (mLock) {
            mAttachedPkgStates.remove(packageName);
        }

        mConnection.get().scheduleWriteSettings();
    }

    @Override
    public void clearUser(@UserIdInt int userId) {
        synchronized (mLock) {
            int attachedSize = mAttachedPkgStates.size();
            for (int index = 0; index < attachedSize; index++) {
                mAttachedPkgStates.valueAt(index).removeUser(userId);
            }

            mSettings.removeUser(userId);
        }

        mConnection.get().scheduleWriteSettings();
    }

    public interface Connection {

        /**
         * Notify that a settings change has been made and that eventually
         * {@link #writeSettings(TypedXmlSerializer)} should be invoked by the parent.
         */
        void scheduleWriteSettings();
    }
}

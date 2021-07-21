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

package com.android.server.searchui;

import static android.Manifest.permission.MANAGE_SEARCH_UI;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.content.Context.SEARCH_UI_SERVICE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.search.ISearchCallback;
import android.app.search.ISearchUiManager;
import android.app.search.Query;
import android.app.search.SearchContext;
import android.app.search.SearchSessionId;
import android.app.search.SearchTargetEvent;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.util.function.Consumer;

/**
 * A service used to return search targets given a query.
 */
public class SearchUiManagerService extends
        AbstractMasterSystemService<SearchUiManagerService, SearchUiPerUserService> {

    private static final String TAG = SearchUiManagerService.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int MAX_TEMP_SERVICE_DURATION_MS = 1_000 * 60 * 2; // 2 minutes

    private ActivityTaskManagerInternal mActivityTaskManagerInternal;

    public SearchUiManagerService(Context context) {
        super(context, new FrameworkResourcesServiceNameResolver(context,
                com.android.internal.R.string.config_defaultSearchUiService), null,
                PACKAGE_UPDATE_POLICY_NO_REFRESH | PACKAGE_RESTART_POLICY_NO_REFRESH);
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
    }

    @Override
    protected SearchUiPerUserService newServiceLocked(int resolvedUserId, boolean disabled) {
        return new SearchUiPerUserService(this, mLock, resolvedUserId);
    }

    @Override
    public void onStart() {
        publishBinderService(SEARCH_UI_SERVICE, new SearchUiManagerStub());
    }

    @Override
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(MANAGE_SEARCH_UI, TAG);
    }

    @Override // from AbstractMasterSystemService
    protected void onServicePackageUpdatedLocked(@UserIdInt int userId) {
        final SearchUiPerUserService service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageUpdatedLocked();
        }
    }

    @Override // from AbstractMasterSystemService
    protected void onServicePackageRestartedLocked(@UserIdInt int userId) {
        final SearchUiPerUserService service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageRestartedLocked();
        }
    }

    @Override
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMP_SERVICE_DURATION_MS;
    }

    private class SearchUiManagerStub extends ISearchUiManager.Stub {

        @Override
        public void createSearchSession(@NonNull SearchContext context,
                @NonNull SearchSessionId sessionId, @NonNull IBinder token) {
            runForUserLocked("createSearchSession", sessionId, (service) ->
                    service.onCreateSearchSessionLocked(context, sessionId, token));
        }

        @Override
        public void notifyEvent(@NonNull SearchSessionId sessionId, @NonNull Query query,
                @NonNull SearchTargetEvent event) {
            runForUserLocked("notifyEvent", sessionId,
                    (service) -> service.notifyLocked(sessionId, query, event));
        }

        @Override
        public void query(@NonNull SearchSessionId sessionId,
                @NonNull Query query,
                ISearchCallback callback) {
            runForUserLocked("query", sessionId,
                    (service) -> service.queryLocked(sessionId, query, callback));
        }

        @Override
        public void destroySearchSession(@NonNull SearchSessionId sessionId) {
            runForUserLocked("destroySearchSession", sessionId,
                    (service) -> service.onDestroyLocked(sessionId));
        }

        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args, @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) {
            new SearchUiManagerServiceShellCommand(SearchUiManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        private void runForUserLocked(@NonNull final String func,
                @NonNull final SearchSessionId sessionId,
                @NonNull final Consumer<SearchUiPerUserService> c) {
            ActivityManagerInternal am = LocalServices.getService(ActivityManagerInternal.class);
            final int userId = am.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                    sessionId.getUserId(), false, ALLOW_NON_FULL, null, null);

            if (DEBUG) {
                Slog.d(TAG, "runForUserLocked:" + func + " from pid=" + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
            }
            if (!(mServiceNameResolver.isTemporary(userId)
                    || mActivityTaskManagerInternal.isCallerRecents(Binder.getCallingUid()))) {

                String msg = "Permission Denial: " + func + " from pid=" + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid();
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }

            final long origId = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    final SearchUiPerUserService service = getServiceForUserLocked(userId);
                    c.accept(service);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }
}

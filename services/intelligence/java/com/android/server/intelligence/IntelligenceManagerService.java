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

package com.android.server.intelligence;

import static android.content.Context.INTELLIGENCE_MANAGER_SERVICE;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.os.UserManager;
import android.service.intelligence.InteractionSessionId;
import android.view.intelligence.ContentCaptureEvent;
import android.view.intelligence.IIntelligenceManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.AbstractMasterSystemService;
import com.android.server.LocalServices;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * A service used to observe the contents of the screen.
 *
 * <p>The data collected by this service can be analyzed and combined with other sources to provide
 * contextual data in other areas of the system such as Autofill.
 */
public final class IntelligenceManagerService
        extends AbstractMasterSystemService<IntelligencePerUserService> {

    private static final String TAG = "IntelligenceManagerService";

    @GuardedBy("mLock")
    private ActivityManagerInternal mAm;

    private final LocalService mLocalService = new LocalService();

    public IntelligenceManagerService(Context context) {
        super(context, UserManager.DISALLOW_INTELLIGENCE_CAPTURE);
    }

    @Override // from AbstractMasterSystemService
    protected String getServiceSettingsProperty() {
        // TODO(b/111276913): STOPSHIP temporary settings, until it's set by resourcs + cmd
        return "intel_service";
    }

    @Override // from AbstractMasterSystemService
    protected IntelligencePerUserService newServiceLocked(@UserIdInt int resolvedUserId,
            boolean disabled) {
        return new IntelligencePerUserService(this, mLock, resolvedUserId);
    }

    @Override // from SystemService
    public void onStart() {
        publishBinderService(INTELLIGENCE_MANAGER_SERVICE,
                new IntelligenceManagerServiceStub());
        publishLocalService(IntelligenceManagerInternal.class, mLocalService);
    }

    @Override // from AbstractMasterSystemService
    protected void onServiceRemoved(@NonNull IntelligencePerUserService service,
            @UserIdInt int userId) {
        service.destroyLocked();
    }

    private ActivityManagerInternal getAmInternal() {
        synchronized (mLock) {
            if (mAm == null) {
                mAm = LocalServices.getService(ActivityManagerInternal.class);
            }
        }
        return mAm;
    }

    final class IntelligenceManagerServiceStub extends IIntelligenceManager.Stub {

        @Override
        public void startSession(int userId, @NonNull IBinder activityToken,
                @NonNull ComponentName componentName, @NonNull InteractionSessionId sessionId,
                int flags, @NonNull IResultReceiver result) {
            Preconditions.checkNotNull(activityToken);
            Preconditions.checkNotNull(componentName);
            Preconditions.checkNotNull(sessionId);

            // TODO(b/111276913): refactor getTaskIdForActivity() to also return ComponentName,
            // so we don't pass it on startSession (same for Autofill)
            final int taskId = getAmInternal().getTaskIdForActivity(activityToken, false);

            // TODO(b/111276913): get from AM as well
            final int displayId = 0;

            synchronized (mLock) {
                final IntelligencePerUserService service = getServiceForUserLocked(userId);
                service.startSessionLocked(activityToken, componentName, taskId, displayId,
                        sessionId, flags, result);
            }
        }

        @Override
        public void sendEvents(int userId, @NonNull InteractionSessionId sessionId,
                @NonNull List<ContentCaptureEvent> events) {
            Preconditions.checkNotNull(sessionId);
            Preconditions.checkNotNull(events);

            synchronized (mLock) {
                final IntelligencePerUserService service = getServiceForUserLocked(userId);
                service.sendEventsLocked(sessionId, events);
            }
        }

        @Override
        public void finishSession(int userId, @NonNull InteractionSessionId sessionId) {
            Preconditions.checkNotNull(sessionId);

            synchronized (mLock) {
                final IntelligencePerUserService service = getServiceForUserLocked(userId);
                service.finishSessionLocked(sessionId);
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;

            synchronized (mLock) {
                dumpLocked("", pw);
            }
        }
    }

    private final class LocalService extends IntelligenceManagerInternal {

        @Override
        public boolean isIntelligenceServiceForUser(int uid, int userId) {
            synchronized (mLock) {
                final IntelligencePerUserService service = peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.isIntelligenceServiceForUserLocked(uid);
                }
            }

            return false;
        }
    }
}

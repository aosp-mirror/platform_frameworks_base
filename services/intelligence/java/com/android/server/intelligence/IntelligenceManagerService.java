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

import static android.content.Context.CONTENT_CAPTURE_MANAGER_SERVICE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserManager;
import android.service.intelligence.InteractionSessionId;
import android.view.autofill.AutofillId;
import android.view.autofill.IAutoFillManagerClient;
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
//TODO(b/111276913): rename once the final name is defined
public final class IntelligenceManagerService extends
        AbstractMasterSystemService<IntelligenceManagerService, IntelligencePerUserService> {

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
        return "smart_suggestions_service";
    }

    @Override // from AbstractMasterSystemService
    protected IntelligencePerUserService newServiceLocked(@UserIdInt int resolvedUserId,
            boolean disabled) {
        return new IntelligencePerUserService(this, mLock, resolvedUserId);
    }

    @Override // from SystemService
    public void onStart() {
        publishBinderService(CONTENT_CAPTURE_MANAGER_SERVICE,
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
        public void startSession(@UserIdInt int userId, @NonNull IBinder activityToken,
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
        public void sendEvents(@UserIdInt int userId, @NonNull InteractionSessionId sessionId,
                @NonNull List<ContentCaptureEvent> events) {
            Preconditions.checkNotNull(sessionId);
            Preconditions.checkNotNull(events);

            synchronized (mLock) {
                final IntelligencePerUserService service = getServiceForUserLocked(userId);
                service.sendEventsLocked(sessionId, events);
            }
        }

        @Override
        public void finishSession(@UserIdInt int userId, @NonNull InteractionSessionId sessionId,
                @Nullable List<ContentCaptureEvent> events) {
            Preconditions.checkNotNull(sessionId);

            synchronized (mLock) {
                final IntelligencePerUserService service = getServiceForUserLocked(userId);
                service.finishSessionLocked(sessionId, events);
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
        public boolean isIntelligenceServiceForUser(int uid, @UserIdInt int userId) {
            synchronized (mLock) {
                final IntelligencePerUserService service = peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.isIntelligenceServiceForUserLocked(uid);
                }
            }
            return false;
        }

        @Override
        public boolean sendActivityAssistData(@UserIdInt int userId, @NonNull IBinder activityToken,
                @NonNull Bundle data) {
            synchronized (mLock) {
                final IntelligencePerUserService service = peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.sendActivityAssistDataLocked(activityToken, data);
                }
            }
            return false;
        }

        @Override
        public AugmentedAutofillCallback requestAutofill(@UserIdInt int userId,
                @NonNull IAutoFillManagerClient client, @NonNull IBinder activityToken,
                int autofillSessionId, @NonNull AutofillId focusedId) {
            synchronized (mLock) {
                final IntelligencePerUserService service = peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.requestAutofill(client, activityToken, autofillSessionId,
                            focusedId);
                }
            }
            return null;
        }
    }
}

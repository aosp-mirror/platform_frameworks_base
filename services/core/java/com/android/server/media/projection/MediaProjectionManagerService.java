/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.media.projection;

import static android.Manifest.permission.MANAGE_MEDIA_PROJECTION;
import static android.app.ActivityManagerInternal.MEDIA_PROJECTION_TOKEN_EVENT_CREATED;
import static android.app.ActivityManagerInternal.MEDIA_PROJECTION_TOKEN_EVENT_DESTROYED;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.media.projection.IMediaProjectionManager.EXTRA_PACKAGE_REUSING_GRANTED_CONSENT;
import static android.media.projection.IMediaProjectionManager.EXTRA_USER_REVIEW_GRANTED_CONSENT;
import static android.media.projection.ReviewGrantedConsentResult.RECORD_CANCEL;
import static android.media.projection.ReviewGrantedConsentResult.RECORD_CONTENT_DISPLAY;
import static android.media.projection.ReviewGrantedConsentResult.RECORD_CONTENT_TASK;
import static android.media.projection.ReviewGrantedConsentResult.UNKNOWN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import android.Manifest;
import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.IProcessObserver;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.media.MediaRouter;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionCallback;
import android.media.projection.IMediaProjectionManager;
import android.media.projection.IMediaProjectionWatcherCallback;
import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.media.projection.ReviewGrantedConsentResult;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.ContentRecordingSession;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Manages MediaProjection sessions.
 *
 * The {@link MediaProjectionManagerService} manages the creation and lifetime of MediaProjections,
 * as well as the capabilities they grant. Any service using MediaProjection tokens as permission
 * grants <b>must</b> validate the token before use by calling {@link
 * IMediaProjectionManager#isCurrentProjection}.
 */
public final class MediaProjectionManagerService extends SystemService
        implements Watchdog.Monitor {
    private static final boolean REQUIRE_FG_SERVICE_FOR_PROJECTION = true;
    private static final String TAG = "MediaProjectionManagerService";

    /**
     * Determines how to respond to an app re-using a consent token; either failing or allowing the
     * user to re-grant consent.
     *
     * <p>Enabled after version 33 (Android T), so applies to target SDK of 34+ (Android U+).
     * @hide
     */
    @VisibleForTesting
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    static final long MEDIA_PROJECTION_PREVENTS_REUSING_CONSENT = 266201607L; // buganizer id

    // Protects access to state at service level & IMediaProjection level.
    // Invocation order while holding locks must follow below to avoid deadlock:
    // WindowManagerService -> MediaProjectionManagerService -> DisplayManagerService
    // See mediaprojection.md
    private final Object mLock = new Object();
    private final Map<IBinder, IBinder.DeathRecipient> mDeathEaters;
    private final CallbackDelegate mCallbackDelegate;

    private final Context mContext;
    private final Injector mInjector;
    private final Clock mClock;
    private final AppOpsManager mAppOps;
    private final ActivityManagerInternal mActivityManagerInternal;
    private final PackageManager mPackageManager;
    private final WindowManagerInternal mWmInternal;

    private final MediaRouter mMediaRouter;
    private final MediaRouterCallback mMediaRouterCallback;
    private MediaRouter.RouteInfo mMediaRouteInfo;

    @GuardedBy("mLock")
    private IBinder mProjectionToken;
    @GuardedBy("mLock")
    private MediaProjection mProjectionGrant;

    public MediaProjectionManagerService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting MediaProjectionManagerService(Context context, Injector injector) {
        super(context);
        mContext = context;
        mInjector = injector;
        mClock = injector.createClock();
        mDeathEaters = new ArrayMap<IBinder, IBinder.DeathRecipient>();
        mCallbackDelegate = new CallbackDelegate();
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mPackageManager = mContext.getPackageManager();
        mWmInternal = LocalServices.getService(WindowManagerInternal.class);
        mMediaRouter = (MediaRouter) mContext.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        mMediaRouterCallback = new MediaRouterCallback();
        Watchdog.getInstance().addMonitor(this);
    }

    /** Functional interface for providing time. */
    @VisibleForTesting
    interface Clock {
        /**
         * Returns current time in milliseconds since boot, not counting time spent in deep sleep.
         */
        long uptimeMillis();
    }

    @VisibleForTesting
    static class Injector {

        /**
         * Returns whether we should prevent the calling app from re-using the user's consent, or
         * allow the user to re-grant access to the same consent token.
         */
        boolean shouldMediaProjectionPreventReusingConsent(MediaProjection projection) {
            // TODO(b/269273190): query feature flag directly instead of injecting.
            return CompatChanges.isChangeEnabled(MEDIA_PROJECTION_PREVENTS_REUSING_CONSENT,
                    projection.packageName, UserHandle.getUserHandleForUid(projection.uid));
        }

        Clock createClock() {
            return SystemClock::uptimeMillis;
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_PROJECTION_SERVICE, new BinderService(mContext),
                false /*allowIsolated*/);
        mMediaRouter.addCallback(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY, mMediaRouterCallback,
                MediaRouter.CALLBACK_FLAG_PASSIVE_DISCOVERY);
        if (REQUIRE_FG_SERVICE_FOR_PROJECTION) {
            mActivityManagerInternal.registerProcessObserver(new IProcessObserver.Stub() {
                @Override
                public void onForegroundActivitiesChanged(int pid, int uid, boolean fg) {
                }

                @Override
                public void onForegroundServicesChanged(int pid, int uid, int serviceTypes) {
                    MediaProjectionManagerService.this.handleForegroundServicesChanged(pid, uid,
                            serviceTypes);
                }

                @Override
                public void onProcessDied(int pid, int uid) {
                }
            });
        }
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        mMediaRouter.rebindAsUser(to.getUserIdentifier());
        synchronized (mLock) {
            if (mProjectionGrant != null) {
                mProjectionGrant.stop();
            }
        }
    }

    @Override
    public void monitor() {
        synchronized (mLock) { /* check for deadlock */ }
    }

    /**
     * Called when the set of active foreground service types for a given {@code uid / pid} changes.
     * We will stop the active projection grant if its owner targets {@code Q} or higher and has no
     * started foreground services of type {@code FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION}.
     */
    private void handleForegroundServicesChanged(int pid, int uid, int serviceTypes) {
        synchronized (mLock) {
            if (mProjectionGrant == null || mProjectionGrant.uid != uid) {
                return;
            }

            if (!mProjectionGrant.requiresForegroundService()) {
                return;
            }

            if (mActivityManagerInternal.hasRunningForegroundService(
                    uid, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)) {
                // If there is any process within this UID running a FGS
                // with the mediaProjection type, that's Okay.
                return;
            }

            mProjectionGrant.stop();
        }
    }

    private void startProjectionLocked(final MediaProjection projection) {
        if (mProjectionGrant != null) {
            mProjectionGrant.stop();
        }
        if (mMediaRouteInfo != null) {
            mMediaRouter.getFallbackRoute().select();
        }
        mProjectionToken = projection.asBinder();
        mProjectionGrant = projection;
        dispatchStart(projection);
    }

    private void stopProjectionLocked(final MediaProjection projection) {
        mProjectionToken = null;
        mProjectionGrant = null;
        dispatchStop(projection);
    }

    private void addCallback(final IMediaProjectionWatcherCallback callback) {
        IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                removeCallback(callback);
            }
        };
        synchronized (mLock) {
            mCallbackDelegate.add(callback);
            linkDeathRecipientLocked(callback, deathRecipient);
        }
    }

    private void removeCallback(IMediaProjectionWatcherCallback callback) {
        synchronized (mLock) {
            unlinkDeathRecipientLocked(callback);
            mCallbackDelegate.remove(callback);
        }
    }

    private void linkDeathRecipientLocked(IMediaProjectionWatcherCallback callback,
            IBinder.DeathRecipient deathRecipient) {
        try {
            final IBinder token = callback.asBinder();
            token.linkToDeath(deathRecipient, 0);
            mDeathEaters.put(token, deathRecipient);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to link to death for media projection monitoring callback", e);
        }
    }

    private void unlinkDeathRecipientLocked(IMediaProjectionWatcherCallback callback) {
        final IBinder token = callback.asBinder();
        IBinder.DeathRecipient deathRecipient = mDeathEaters.remove(token);
        if (deathRecipient != null) {
            token.unlinkToDeath(deathRecipient, 0);
        }
    }

    private void dispatchStart(MediaProjection projection) {
        mCallbackDelegate.dispatchStart(projection);
    }

    private void dispatchStop(MediaProjection projection) {
        mCallbackDelegate.dispatchStop(projection);
    }

    /**
     * Returns {@code true} when updating the current mirroring session on WM succeeded, and
     * {@code false} otherwise.
     */
    @VisibleForTesting
    boolean setContentRecordingSession(@Nullable ContentRecordingSession incomingSession) {
        // NEVER lock while calling into WindowManagerService, since WindowManagerService is
        // ALWAYS locked when it invokes MediaProjectionManagerService.
        final boolean setSessionSucceeded = mWmInternal.setContentRecordingSession(incomingSession);
        synchronized (mLock) {
            if (!setSessionSucceeded) {
                // Unable to start mirroring, so tear down this projection.
                if (mProjectionGrant != null) {
                    mProjectionGrant.stop();
                }
                return false;
            }
            if (mProjectionGrant != null) {
                // Cache the session details.
                mProjectionGrant.mSession = incomingSession;
            }
            return true;
        }
    }

    /**
     * Returns {@code true} when the given token matches the token of the current projection
     * instance. Returns {@code false} otherwise.
     */
    @VisibleForTesting
    boolean isCurrentProjection(IBinder token) {
        synchronized (mLock) {
            if (mProjectionToken != null) {
                return mProjectionToken.equals(token);
            }
            return false;
        }
    }

    /**
     * Re-shows the permission dialog for the user to review consent they've already granted in
     * the given projection instance.
     *
     * <p>Preconditions:
     * <ul>
     *   <li>{@link IMediaProjection#isValid} returned false, rather than throwing an exception</li>
     *   <li>Given projection instance is the current projection instance.</li>
     * <ul>
     *
     * <p>Returns immediately but waits to start recording until user has reviewed their consent.
     */
    @VisibleForTesting
    void requestConsentForInvalidProjection() {
        Intent reviewConsentIntent;
        int uid;
        synchronized (mLock) {
            reviewConsentIntent = buildReviewGrantedConsentIntentLocked();
            uid = mProjectionGrant.uid;
        }
        // NEVER lock while calling into a method that eventually acquires the WindowManagerService
        // lock, since WindowManagerService is ALWAYS locked when it invokes
        // MediaProjectionManagerService.
        Slog.v(TAG, "Reusing token: Reshow dialog for due to invalid projection.");
        // Trigger the permission dialog again in SysUI
        // Do not handle the result; SysUI will update us when the user has consented.
        mContext.startActivityAsUser(reviewConsentIntent,
                UserHandle.getUserHandleForUid(uid));
    }

    /**
     * Returns an intent to re-show the consent dialog in SysUI. Should only be used for the
     * scenario where the host app has re-used the consent token.
     *
     * <p>Consent dialog result handled in
     * {@link BinderService#setUserReviewGrantedConsentResult(int)}.
     */
    private Intent buildReviewGrantedConsentIntentLocked() {
        final String permissionDialogString = mContext.getResources().getString(
                R.string.config_mediaProjectionPermissionDialogComponent);
        final ComponentName mediaProjectionPermissionDialogComponent =
                ComponentName.unflattenFromString(permissionDialogString);
        // We can use mProjectionGrant since we already checked that it matches the given token.
        return new Intent().setComponent(mediaProjectionPermissionDialogComponent)
                .putExtra(EXTRA_USER_REVIEW_GRANTED_CONSENT, true)
                .putExtra(EXTRA_PACKAGE_REUSING_GRANTED_CONSENT, mProjectionGrant.packageName)
                .setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }

    /**
     * Handles result of dialog shown from
     * {@link BinderService#buildReviewGrantedConsentIntentLocked()}.
     *
     * <p>Tears down session if user did not consent, or starts mirroring if user did consent.
     */
    @VisibleForTesting
    void setUserReviewGrantedConsentResult(@ReviewGrantedConsentResult int consentResult,
            @Nullable IMediaProjection projection) {
        synchronized (mLock) {
            final boolean consentGranted =
                    consentResult == RECORD_CONTENT_DISPLAY || consentResult == RECORD_CONTENT_TASK;
            if (consentGranted && !isCurrentProjection(
                    projection == null ? null : projection.asBinder())) {
                Slog.v(TAG, "Reusing token: Ignore consent result of " + consentResult + " for a "
                        + "token that isn't current");
                return;
            }
            if (mProjectionGrant == null) {
                Slog.w(TAG, "Reusing token: Can't review consent with no ongoing projection.");
                return;
            }
            if (mProjectionGrant.mSession == null
                    || !mProjectionGrant.mSession.isWaitingForConsent()) {
                Slog.w(TAG, "Reusing token: Ignore consent result " + consentResult
                        + " if not waiting for the result.");
                return;
            }
            Slog.v(TAG, "Reusing token: Handling user consent result " + consentResult);
            switch (consentResult) {
                case UNKNOWN:
                case RECORD_CANCEL:
                    // Pass in null to stop mirroring.
                    setReviewedConsentSessionLocked(/* session= */ null);
                    // The grant may now be null if setting the session failed.
                    if (mProjectionGrant != null) {
                        // Always stop the projection.
                        mProjectionGrant.stop();
                    }
                    break;
                case RECORD_CONTENT_DISPLAY:
                    // TODO(270118861) The app may have specified a particular id in the virtual
                    //  display config. However - below will always return INVALID since it checks
                    //  that window manager mirroring is not enabled (it is always enabled for MP).
                    setReviewedConsentSessionLocked(ContentRecordingSession.createDisplaySession(
                            DEFAULT_DISPLAY));
                    break;
                case RECORD_CONTENT_TASK:
                    setReviewedConsentSessionLocked(ContentRecordingSession.createTaskSession(
                            mProjectionGrant.getLaunchCookie()));
                    break;
            }
        }
    }

    /**
     * Updates the session after the user has reviewed consent. There must be a current session.
     *
     * @param session The new session details, or {@code null} to stop recording.
     */
    private void setReviewedConsentSessionLocked(@Nullable ContentRecordingSession session) {
        if (session != null) {
            session.setWaitingForConsent(false);
            session.setVirtualDisplayId(mProjectionGrant.mVirtualDisplayId);
        }

        Slog.v(TAG, "Reusing token: Processed consent so set the session " + session);
        if (!setContentRecordingSession(session)) {
            Slog.e(TAG, "Reusing token: Failed to set session for reused consent, so stop");
            // Do not need to invoke stop; updating the session does it for us.
        }
    }

    // TODO(b/261563516): Remove internal method and test aidl directly, here and elsewhere.
    @VisibleForTesting
    MediaProjection createProjectionInternal(int uid, String packageName, int type,
            boolean isPermanentGrant, UserHandle callingUser) {
        MediaProjection projection;
        ApplicationInfo ai;
        try {
            ai = mPackageManager.getApplicationInfoAsUser(packageName, ApplicationInfoFlags.of(0),
                    callingUser);
        } catch (NameNotFoundException e) {
            throw new IllegalArgumentException("No package matching :" + packageName);
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            projection = new MediaProjection(type, uid, packageName, ai.targetSdkVersion,
                    ai.isPrivilegedApp());
            if (isPermanentGrant) {
                mAppOps.setMode(AppOpsManager.OP_PROJECT_MEDIA,
                        projection.uid, projection.packageName, AppOpsManager.MODE_ALLOWED);
            }
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return projection;
    }

    // TODO(b/261563516): Remove internal method and test aidl directly, here and elsewhere.
    @VisibleForTesting
    MediaProjection getProjectionInternal(int uid, String packageName) {
        final long callingToken = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                // Supposedly the package has re-used the user's consent; confirm the provided
                // details against the current projection token before re-using the current
                // projection.
                if (mProjectionGrant == null || mProjectionGrant.mSession == null
                        || !mProjectionGrant.mSession.isWaitingForConsent()) {
                    Slog.e(TAG, "Reusing token: Not possible to reuse the current projection "
                            + "instance");
                    return null;
                }
                // The package matches, go ahead and re-use the token for this request.
                if (mProjectionGrant.uid == uid
                        && Objects.equals(mProjectionGrant.packageName, packageName)) {
                    Slog.v(TAG, "Reusing token: getProjection can reuse the current projection");
                    return mProjectionGrant;
                } else {
                    Slog.e(TAG, "Reusing token: Not possible to reuse the current projection "
                            + "instance due to package details mismatching");
                    return null;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @VisibleForTesting
    MediaProjectionInfo getActiveProjectionInfo() {
        synchronized (mLock) {
            if (mProjectionGrant == null) {
                return null;
            }
            return mProjectionGrant.getProjectionInfo();
        }
    }

    private void dump(final PrintWriter pw) {
        pw.println("MEDIA PROJECTION MANAGER (dumpsys media_projection)");
        synchronized (mLock) {
            pw.println("Media Projection: ");
            if (mProjectionGrant != null ) {
                mProjectionGrant.dump(pw);
            } else {
                pw.println("null");
            }
        }
    }

    private final class BinderService extends IMediaProjectionManager.Stub {

        BinderService(Context context) {
            super(PermissionEnforcer.fromContext(context));
        }

        @Override // Binder call
        public boolean hasProjectionPermission(int uid, String packageName) {
            final long token = Binder.clearCallingIdentity();
            boolean hasPermission = false;
            try {
                hasPermission |= checkPermission(packageName,
                        android.Manifest.permission.CAPTURE_VIDEO_OUTPUT)
                        || mAppOps.noteOpNoThrow(
                                AppOpsManager.OP_PROJECT_MEDIA, uid, packageName)
                        == AppOpsManager.MODE_ALLOWED;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return hasPermission;
        }

        @Override // Binder call
        public IMediaProjection createProjection(int uid, String packageName, int type,
                boolean isPermanentGrant) {
            if (mContext.checkCallingPermission(MANAGE_MEDIA_PROJECTION)
                        != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires MANAGE_MEDIA_PROJECTION in order to grant "
                        + "projection permission");
            }
            if (packageName == null || packageName.isEmpty()) {
                throw new IllegalArgumentException("package name must not be empty");
            }
            final UserHandle callingUser = Binder.getCallingUserHandle();
            return createProjectionInternal(uid, packageName, type, isPermanentGrant,
                    callingUser);
        }

        @Override // Binder call
        @EnforcePermission(MANAGE_MEDIA_PROJECTION)
        public IMediaProjection getProjection(int uid, String packageName) {
            getProjection_enforcePermission();
            if (packageName == null || packageName.isEmpty()) {
                throw new IllegalArgumentException("package name must not be empty");
            }

            MediaProjection projection;
            final long callingToken = Binder.clearCallingIdentity();
            try {
                projection = getProjectionInternal(uid, packageName);
            } finally {
                Binder.restoreCallingIdentity(callingToken);
            }
            return projection;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)
        @Override // Binder call
        public boolean isCurrentProjection(IMediaProjection projection) {
            isCurrentProjection_enforcePermission();
            return MediaProjectionManagerService.this.isCurrentProjection(
                    projection == null ? null : projection.asBinder());
        }

        @Override // Binder call
        public MediaProjectionInfo getActiveProjectionInfo() {
            if (mContext.checkCallingPermission(MANAGE_MEDIA_PROJECTION)
                        != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires MANAGE_MEDIA_PROJECTION in order to get "
                        + "active projection info");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return MediaProjectionManagerService.this.getActiveProjectionInfo();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)
        @Override // Binder call
        public void stopActiveProjection() {
            stopActiveProjection_enforcePermission();
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (mProjectionGrant != null) {
                        mProjectionGrant.stop();
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)
        @Override // Binder call
        public void notifyActiveProjectionCapturedContentResized(int width, int height) {
            notifyActiveProjectionCapturedContentResized_enforcePermission();
            synchronized (mLock) {
                if (!isCurrentProjection(mProjectionGrant)) {
                    return;
                }
            }
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (mProjectionGrant != null && mCallbackDelegate != null) {
                        mCallbackDelegate.dispatchResize(mProjectionGrant, width, height);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)
        @Override
        public void notifyActiveProjectionCapturedContentVisibilityChanged(boolean isVisible) {
            notifyActiveProjectionCapturedContentVisibilityChanged_enforcePermission();
            synchronized (mLock) {
                if (!isCurrentProjection(mProjectionGrant)) {
                    return;
                }
            }
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (mProjectionGrant != null && mCallbackDelegate != null) {
                        mCallbackDelegate.dispatchVisibilityChanged(mProjectionGrant, isVisible);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override //Binder call
        public void addCallback(final IMediaProjectionWatcherCallback callback) {
            if (mContext.checkCallingPermission(MANAGE_MEDIA_PROJECTION)
                        != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires MANAGE_MEDIA_PROJECTION in order to add "
                        + "projection callbacks");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                MediaProjectionManagerService.this.addCallback(callback);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void removeCallback(IMediaProjectionWatcherCallback callback) {
            if (mContext.checkCallingPermission(MANAGE_MEDIA_PROJECTION)
                        != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires MANAGE_MEDIA_PROJECTION in order to remove "
                        + "projection callbacks");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                MediaProjectionManagerService.this.removeCallback(callback);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)
        @Override
        public boolean setContentRecordingSession(@Nullable ContentRecordingSession incomingSession,
                @NonNull IMediaProjection projection) {
            setContentRecordingSession_enforcePermission();
            synchronized (mLock) {
                if (!isCurrentProjection(projection)) {
                    throw new SecurityException("Unable to set ContentRecordingSession on "
                            + "non-current MediaProjection");
                }
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                return MediaProjectionManagerService.this.setContentRecordingSession(
                        incomingSession);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)
        @Override
        public void requestConsentForInvalidProjection(@NonNull IMediaProjection projection) {
            requestConsentForInvalidProjection_enforcePermission();
            synchronized (mLock) {
                if (!isCurrentProjection(projection)) {
                    Slog.v(TAG, "Reusing token: Won't request consent again for a token that "
                            + "isn't current");
                    return;
                }
            }

            // Remove calling app identity before performing any privileged operations.
            final long token = Binder.clearCallingIdentity();
            try {
                MediaProjectionManagerService.this.requestConsentForInvalidProjection();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        @EnforcePermission(MANAGE_MEDIA_PROJECTION)
        public void setUserReviewGrantedConsentResult(@ReviewGrantedConsentResult int consentResult,
                @Nullable IMediaProjection projection) {
            setUserReviewGrantedConsentResult_enforcePermission();
            // Remove calling app identity before performing any privileged operations.
            final long token = Binder.clearCallingIdentity();
            try {
                MediaProjectionManagerService.this.setUserReviewGrantedConsentResult(consentResult,
                        projection);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override // Binder call
        public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
            final long token = Binder.clearCallingIdentity();
            try {
                MediaProjectionManagerService.this.dump(pw);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private boolean checkPermission(String packageName, String permission) {
            return mContext.getPackageManager().checkPermission(permission, packageName)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    @VisibleForTesting
    final class MediaProjection extends IMediaProjection.Stub {
        // Host app has 5 minutes to begin using the token before it is invalid.
        // Some apps show a dialog for the user to interact with (selecting recording resolution)
        // before starting capture, but after requesting consent.
        final long mDefaultTimeoutMs = Duration.ofMinutes(5).toMillis();
        // The creation timestamp in milliseconds, measured by {@link SystemClock#uptimeMillis}.
        private final long mCreateTimeMs;
        public final int uid;
        public final String packageName;
        public final UserHandle userHandle;
        private final int mTargetSdkVersion;
        private final boolean mIsPrivileged;
        private final int mType;

        private IMediaProjectionCallback mCallback;
        private IBinder mToken;
        private IBinder.DeathRecipient mDeathEater;
        private boolean mRestoreSystemAlertWindow;
        private IBinder mLaunchCookie = null;

        // Values for tracking token validity.
        // Timeout value to compare creation time against.
        private long mTimeoutMs = mDefaultTimeoutMs;
        // Count of number of times IMediaProjection#start is invoked.
        private int mCountStarts = 0;
        // Set if MediaProjection#createVirtualDisplay has been invoked previously (it
        // should only be called once).
        private int mVirtualDisplayId = INVALID_DISPLAY;
        // The associated session details already sent to WindowManager.
        private ContentRecordingSession mSession;

        MediaProjection(int type, int uid, String packageName, int targetSdkVersion,
                boolean isPrivileged) {
            mType = type;
            this.uid = uid;
            this.packageName = packageName;
            userHandle = new UserHandle(UserHandle.getUserId(uid));
            mTargetSdkVersion = targetSdkVersion;
            mIsPrivileged = isPrivileged;
            mCreateTimeMs = mClock.uptimeMillis();
            // TODO(b/267740338): Add unit test.
            mActivityManagerInternal.notifyMediaProjectionEvent(uid, asBinder(),
                    MEDIA_PROJECTION_TOKEN_EVENT_CREATED);
        }

        @Override // Binder call
        public boolean canProjectVideo() {
            return mType == MediaProjectionManager.TYPE_MIRRORING ||
                    mType == MediaProjectionManager.TYPE_SCREEN_CAPTURE;
        }

        @Override // Binder call
        public boolean canProjectSecureVideo() {
            return false;
        }

        @Override // Binder call
        public boolean canProjectAudio() {
            return mType == MediaProjectionManager.TYPE_MIRRORING
                || mType == MediaProjectionManager.TYPE_PRESENTATION
                || mType == MediaProjectionManager.TYPE_SCREEN_CAPTURE;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)
        @Override // Binder call
        public int applyVirtualDisplayFlags(int flags) {
            applyVirtualDisplayFlags_enforcePermission();
            if (mType == MediaProjectionManager.TYPE_SCREEN_CAPTURE) {
                flags &= ~DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
                flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
                return flags;
            } else if (mType == MediaProjectionManager.TYPE_MIRRORING) {
                flags &= ~(DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
                flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
                return flags;
            } else if (mType == MediaProjectionManager.TYPE_PRESENTATION) {
                flags &= ~DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
                flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION |
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
                return flags;
            } else  {
                throw new RuntimeException("Unknown MediaProjection type");
            }
        }

        @Override // Binder call
        public void start(final IMediaProjectionCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            synchronized (mLock) {
                if (isCurrentProjection(asBinder())) {
                    Slog.w(TAG, "UID " + Binder.getCallingUid()
                            + " attempted to start already started MediaProjection");
                    // It is possible the app didn't explicitly invoke stop before trying to start
                    // again; ensure this start is counted in case they are re-using this token.
                    mCountStarts++;
                    return;
                }

                if (REQUIRE_FG_SERVICE_FOR_PROJECTION
                        && requiresForegroundService()
                        && !mActivityManagerInternal.hasRunningForegroundService(
                                uid, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)) {
                    throw new SecurityException("Media projections require a foreground service"
                            + " of type ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION");
                }

                mCallback = callback;
                registerCallback(mCallback);
                try {
                    mToken = callback.asBinder();
                    mDeathEater = new IBinder.DeathRecipient() {
                        @Override
                        public void binderDied() {
                            mCallbackDelegate.remove(callback);
                            stop();
                        }
                    };
                    mToken.linkToDeath(mDeathEater, 0);
                } catch (RemoteException e) {
                    Slog.w(TAG,
                            "MediaProjectionCallbacks must be valid, aborting MediaProjection", e);
                    return;
                }
                if (mType == MediaProjectionManager.TYPE_SCREEN_CAPTURE) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        // We allow an app running a current screen capture session to use
                        // SYSTEM_ALERT_WINDOW for the duration of the session, to enable
                        // them to overlay their UX on top of what is being captured.
                        // We only do this if the app requests the permission, and the appop
                        // is in its default state (the user has neither explicitly allowed nor
                        // disallowed it).
                        final PackageInfo packageInfo = mPackageManager.getPackageInfoAsUser(
                                packageName, PackageManager.GET_PERMISSIONS,
                                UserHandle.getUserId(uid));
                        if (ArrayUtils.contains(packageInfo.requestedPermissions,
                                Manifest.permission.SYSTEM_ALERT_WINDOW)) {
                            final int currentMode = mAppOps.unsafeCheckOpRawNoThrow(
                                    AppOpsManager.OP_SYSTEM_ALERT_WINDOW, uid, packageName);
                            if (currentMode == AppOpsManager.MODE_DEFAULT) {
                                mAppOps.setMode(AppOpsManager.OP_SYSTEM_ALERT_WINDOW, uid,
                                        packageName, AppOpsManager.MODE_ALLOWED);
                                mRestoreSystemAlertWindow = true;
                            }
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        Slog.w(TAG, "Package not found, aborting MediaProjection", e);
                        return;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
                startProjectionLocked(this);
                // Mark this token as used when the app gets the MediaProjection instance.
                mCountStarts++;
            }
        }

        @Override // Binder call
        public void stop() {
            synchronized (mLock) {
                if (!isCurrentProjection(asBinder())) {
                    Slog.w(TAG, "Attempted to stop inactive MediaProjection "
                            + "(uid=" + Binder.getCallingUid() + ", "
                            + "pid=" + Binder.getCallingPid() + ")");
                    return;
                }
                if (mRestoreSystemAlertWindow) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        // Put the appop back how it was, unless it has been changed from what
                        // we set it to.
                        // Note that WindowManager takes care of removing any existing overlay
                        // windows when we do this.
                        final int currentMode = mAppOps.unsafeCheckOpRawNoThrow(
                                AppOpsManager.OP_SYSTEM_ALERT_WINDOW, uid, packageName);
                        if (currentMode == AppOpsManager.MODE_ALLOWED) {
                            mAppOps.setMode(AppOpsManager.OP_SYSTEM_ALERT_WINDOW, uid, packageName,
                                    AppOpsManager.MODE_DEFAULT);
                        }
                        mRestoreSystemAlertWindow = false;
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
                stopProjectionLocked(this);
                mToken.unlinkToDeath(mDeathEater, 0);
                mToken = null;
                unregisterCallback(mCallback);
                mCallback = null;
                // TODO(b/267740338): Add unit test.
                mActivityManagerInternal.notifyMediaProjectionEvent(uid, asBinder(),
                        MEDIA_PROJECTION_TOKEN_EVENT_DESTROYED);
            }
        }

        @Override // Binder call
        public void registerCallback(IMediaProjectionCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            mCallbackDelegate.add(callback);
        }

        @Override // Binder call
        public void unregisterCallback(IMediaProjectionCallback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            mCallbackDelegate.remove(callback);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)
        @Override // Binder call
        public void setLaunchCookie(IBinder launchCookie) {
            setLaunchCookie_enforcePermission();
            mLaunchCookie = launchCookie;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)
        @Override // Binder call
        public IBinder getLaunchCookie() {
            getLaunchCookie_enforcePermission();
            return mLaunchCookie;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)
        @Override
        public boolean isValid() {
            isValid_enforcePermission();
            synchronized (mLock) {
                final long curMs = mClock.uptimeMillis();
                final boolean hasTimedOut = curMs - mCreateTimeMs > mTimeoutMs;
                final boolean virtualDisplayCreated = mVirtualDisplayId != INVALID_DISPLAY;
                final boolean isValid =
                        !hasTimedOut && (mCountStarts <= 1) && !virtualDisplayCreated;
                if (isValid) {
                    return true;
                }

                // Can safely use mProjectionGrant since we know this is the current projection.
                if (mInjector.shouldMediaProjectionPreventReusingConsent(mProjectionGrant)) {
                    Slog.v(TAG, "Reusing token: Throw exception due to invalid projection.");
                    // Tear down projection here; necessary to ensure (among other reasons) that
                    // stop is dispatched to client and cast icon disappears from status bar.
                    mProjectionGrant.stop();
                    throw new IllegalStateException("Don't re-use the resultData to retrieve "
                            + "the same projection instance, and don't use a token that has "
                            + "timed out. Don't take multiple captures by invoking "
                            + "MediaProjection#createVirtualDisplay multiple times on the "
                            + "same instance.");
                }
                return false;
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)
        @Override
        public void notifyVirtualDisplayCreated(int displayId) {
            notifyVirtualDisplayCreated_enforcePermission();
            synchronized (mLock) {
                mVirtualDisplayId = displayId;

                // If prior session was does not have a valid display id, then update the display
                // so recording can start.
                if (mSession != null && mSession.getVirtualDisplayId() == INVALID_DISPLAY) {
                    Slog.v(TAG, "Virtual display now created, so update session with the virtual "
                            + "display id");
                    mSession.setVirtualDisplayId(mVirtualDisplayId);
                    if (!setContentRecordingSession(mSession)) {
                        Slog.e(TAG, "Failed to set session for virtual display id");
                        // Do not need to invoke stop; updating the session does it for us.
                    }
                }
            }
        }

        public MediaProjectionInfo getProjectionInfo() {
            return new MediaProjectionInfo(packageName, userHandle);
        }

        boolean requiresForegroundService() {
            return mTargetSdkVersion >= Build.VERSION_CODES.Q && !mIsPrivileged;
        }

        public void dump(PrintWriter pw) {
            pw.println("(" + packageName + ", uid=" + uid + "): " + typeToString(mType));
        }
    }

    private class MediaRouterCallback extends MediaRouter.SimpleCallback {
        @Override
        public void onRouteSelected(MediaRouter router, int type, MediaRouter.RouteInfo info) {
            synchronized (mLock) {
                if ((type & MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY) != 0) {
                    mMediaRouteInfo = info;
                    if (mProjectionGrant != null) {
                        mProjectionGrant.stop();
                    }
                }
            }
        }

        @Override
        public void onRouteUnselected(MediaRouter route, int type, MediaRouter.RouteInfo info) {
            if (mMediaRouteInfo == info) {
                mMediaRouteInfo = null;
            }
        }
    }


    private static class CallbackDelegate {
        private Map<IBinder, IMediaProjectionCallback> mClientCallbacks;
        // Map from the IBinder token representing the callback, to the callback instance.
        // Represents the callbacks registered on the client's MediaProjectionManager.
        private Map<IBinder, IMediaProjectionWatcherCallback> mWatcherCallbacks;
        private Handler mHandler;
        private final Object mLock = new Object();

        public CallbackDelegate() {
            mHandler = new Handler(Looper.getMainLooper(), null, true /*async*/);
            mClientCallbacks = new ArrayMap<IBinder, IMediaProjectionCallback>();
            mWatcherCallbacks = new ArrayMap<IBinder, IMediaProjectionWatcherCallback>();
        }

        public void add(IMediaProjectionCallback callback) {
            synchronized (mLock) {
                mClientCallbacks.put(callback.asBinder(), callback);
            }
        }

        public void add(IMediaProjectionWatcherCallback callback) {
            synchronized (mLock) {
                mWatcherCallbacks.put(callback.asBinder(), callback);
            }
        }

        public void remove(IMediaProjectionCallback callback) {
            synchronized (mLock) {
                mClientCallbacks.remove(callback.asBinder());
            }
        }

        public void remove(IMediaProjectionWatcherCallback callback) {
            synchronized (mLock) {
                mWatcherCallbacks.remove(callback.asBinder());
            }
        }

        public void dispatchStart(MediaProjection projection) {
            if (projection == null) {
                Slog.e(TAG, "Tried to dispatch start notification for a null media projection."
                        + " Ignoring!");
                return;
            }
            synchronized (mLock) {
                for (IMediaProjectionWatcherCallback callback : mWatcherCallbacks.values()) {
                    MediaProjectionInfo info = projection.getProjectionInfo();
                    mHandler.post(new WatcherStartCallback(info, callback));
                }
            }
        }

        public void dispatchStop(MediaProjection projection) {
            if (projection == null) {
                Slog.e(TAG, "Tried to dispatch stop notification for a null media projection."
                        + " Ignoring!");
                return;
            }
            synchronized (mLock) {
                for (IMediaProjectionCallback callback : mClientCallbacks.values()) {
                    // Notify every callback the client has registered for a particular
                    // MediaProjection instance.
                    mHandler.post(new ClientStopCallback(callback));
                }

                for (IMediaProjectionWatcherCallback callback : mWatcherCallbacks.values()) {
                    MediaProjectionInfo info = projection.getProjectionInfo();
                    mHandler.post(new WatcherStopCallback(info, callback));
                }
            }
        }

        public void dispatchResize(MediaProjection projection, int width, int height) {
            if (projection == null) {
                Slog.e(TAG,
                        "Tried to dispatch resize notification for a null media projection. "
                                + "Ignoring!");
                return;
            }
            synchronized (mLock) {
                // TODO(b/249827847): Currently the service assumes there is only one projection
                //  at once - need to find the callback for the given projection, when there are
                //  multiple sessions.
                for (IMediaProjectionCallback callback : mClientCallbacks.values()) {
                    mHandler.post(() -> {
                        try {
                            // Notify every callback the client has registered for a particular
                            // MediaProjection instance.
                            callback.onCapturedContentResize(width, height);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to notify media projection has resized to " + width
                                    + " x " + height, e);
                        }
                    });
                }
                // Do not need to notify watcher callback about resize, since watcher callback
                // is for passing along if recording is still ongoing or not.
            }
        }

        public void dispatchVisibilityChanged(MediaProjection projection, boolean isVisible) {
            if (projection == null) {
                Slog.e(TAG,
                        "Tried to dispatch visibility changed notification for a null media "
                                + "projection. Ignoring!");
                return;
            }
            synchronized (mLock) {
                // TODO(b/249827847): Currently the service assumes there is only one projection
                //  at once - need to find the callback for the given projection, when there are
                //  multiple sessions.
                for (IMediaProjectionCallback callback : mClientCallbacks.values()) {
                    mHandler.post(() -> {
                        try {
                            // Notify every callback the client has registered for a particular
                            // MediaProjection instance.
                            callback.onCapturedContentVisibilityChanged(isVisible);
                        } catch (RemoteException e) {
                            Slog.w(TAG,
                                    "Failed to notify media projection has captured content "
                                            + "visibility change to "
                                            + isVisible, e);
                        }
                    });
                }
                // Do not need to notify watcher callback about visibility changes, since watcher
                // callback is for passing along if recording is still ongoing or not.
            }
        }
    }

    private static final class WatcherStartCallback implements Runnable {
        private IMediaProjectionWatcherCallback mCallback;
        private MediaProjectionInfo mInfo;

        public WatcherStartCallback(MediaProjectionInfo info,
                IMediaProjectionWatcherCallback callback) {
            mInfo = info;
            mCallback = callback;
        }

        @Override
        public void run() {
            try {
                mCallback.onStart(mInfo);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify media projection has stopped", e);
            }
        }
    }

    private static final class WatcherStopCallback implements Runnable {
        private IMediaProjectionWatcherCallback mCallback;
        private MediaProjectionInfo mInfo;

        public WatcherStopCallback(MediaProjectionInfo info,
                IMediaProjectionWatcherCallback callback) {
            mInfo = info;
            mCallback = callback;
        }

        @Override
        public void run() {
            try {
                mCallback.onStop(mInfo);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify media projection has stopped", e);
            }
        }
    }

    private static final class ClientStopCallback implements Runnable {
        private IMediaProjectionCallback mCallback;

        public ClientStopCallback(IMediaProjectionCallback callback) {
            mCallback = callback;
        }

        @Override
        public void run() {
            try {
                mCallback.onStop();
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify media projection has stopped", e);
            }
        }
    }


    private static String typeToString(int type) {
        switch (type) {
            case MediaProjectionManager.TYPE_SCREEN_CAPTURE:
                return "TYPE_SCREEN_CAPTURE";
            case MediaProjectionManager.TYPE_MIRRORING:
                return "TYPE_MIRRORING";
            case MediaProjectionManager.TYPE_PRESENTATION:
                return "TYPE_PRESENTATION";
        }
        return Integer.toString(type);
    }
}

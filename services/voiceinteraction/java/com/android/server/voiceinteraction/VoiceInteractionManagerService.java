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

package com.android.server.voiceinteraction;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;
import static android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.KeyphraseMetadata;
import android.hardware.soundtrigger.ModelParams;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModelParamRange;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.media.permission.PermissionUtil;
import android.media.permission.SafeCloseable;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SharedMemory;
import android.os.ShellCallback;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.IMicrophoneHotwordDetectionVoiceInteractionCallback;
import android.service.voice.IVisualQueryDetectionVoiceInteractionCallback;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.VoiceInteractionManagerInternal;
import android.service.voice.VoiceInteractionManagerInternal.WearableHotwordDetectionCallback;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionServiceInfo;
import android.service.voice.VoiceInteractionSession;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.window.ScreenCapture;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVisualQueryDetectionAttentionListener;
import com.android.internal.app.IVisualQueryRecognitionStatusListener;
import com.android.internal.app.IVoiceActionCheckCallback;
import com.android.internal.app.IVoiceInteractionAccessibilitySettingsListener;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.app.IVoiceInteractionSessionListener;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.app.IVoiceInteractionSoundTriggerSession;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SoundTriggerInternal;
import com.android.server.SystemService;
import com.android.server.UiThread;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.permission.LegacyPermissionManagerInternal;
import com.android.server.policy.AppOpsPolicy;
import com.android.server.utils.Slogf;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.wm.ActivityAssistInfo;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * SystemService that publishes an IVoiceInteractionManagerService.
 */
public class VoiceInteractionManagerService extends SystemService {
    static final String TAG = "VoiceInteractionManager";
    static final boolean DEBUG = false;

    /** Static constants used by Contextual Search helper. */
    private static final String CS_KEY_FLAG_SECURE_FOUND =
            "com.android.contextualsearch.flag_secure_found";
    private static final String CS_KEY_FLAG_SCREENSHOT =
            "com.android.contextualsearch.screenshot";
    private static final String CS_KEY_FLAG_IS_MANAGED_PROFILE_VISIBLE =
            "com.android.contextualsearch.is_managed_profile_visible";
    private static final String CS_KEY_FLAG_VISIBLE_PACKAGE_NAMES =
            "com.android.contextualsearch.visible_package_names";
    private static final String CS_INTENT_FILTER =
            "com.android.contextualsearch.LAUNCH";


    final Context mContext;
    final ContentResolver mResolver;
    // Can be overridden for testing purposes
    private IEnrolledModelDb mDbHelper;
    private final IEnrolledModelDb mRealDbHelper;
    final ActivityManagerInternal mAmInternal;
    final ActivityTaskManagerInternal mAtmInternal;
    final UserManagerInternal mUserManagerInternal;
    final WindowManagerInternal mWmInternal;
    final DevicePolicyManagerInternal mDpmInternal;
    final ArrayMap<Integer, VoiceInteractionManagerServiceStub.SoundTriggerSession>
            mLoadedKeyphraseIds = new ArrayMap<>();
    ShortcutServiceInternal mShortcutServiceInternal;
    SoundTriggerInternal mSoundTriggerInternal;

    private final RemoteCallbackList<IVoiceInteractionSessionListener>
            mVoiceInteractionSessionListeners = new RemoteCallbackList<>();
    private IVisualQueryRecognitionStatusListener mVisualQueryRecognitionStatusListener;

    public VoiceInteractionManagerService(Context context) {
        super(context);
        mContext = context;
        mResolver = context.getContentResolver();
        mUserManagerInternal = Objects.requireNonNull(
                LocalServices.getService(UserManagerInternal.class));
        mDbHelper = mRealDbHelper = new DatabaseHelper(context);
        mServiceStub = new VoiceInteractionManagerServiceStub();
        mAmInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityManagerInternal.class));
        mAtmInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityTaskManagerInternal.class));
        mWmInternal = Objects.requireNonNull(
                LocalServices.getService(WindowManagerInternal.class));
        mDpmInternal = LocalServices.getService(DevicePolicyManagerInternal.class);
        LegacyPermissionManagerInternal permissionManagerInternal = LocalServices.getService(
                LegacyPermissionManagerInternal.class);
        permissionManagerInternal.setVoiceInteractionPackagesProvider(
                new LegacyPermissionManagerInternal.PackagesProvider() {
            @Override
            public String[] getPackages(int userId) {
                mServiceStub.initForUser(userId);
                ComponentName interactor = mServiceStub.getCurInteractor(userId);
                if (interactor != null) {
                    return new String[] {interactor.getPackageName()};
                }
                return null;
            }
        });
    }

    @Override
    public void onStart() {
        publishBinderService(Context.VOICE_INTERACTION_MANAGER_SERVICE, mServiceStub);
        publishLocalService(VoiceInteractionManagerInternal.class, new LocalService());
        mAmInternal.setVoiceInteractionManagerProvider(
                new ActivityManagerInternal.VoiceInteractionManagerProvider() {
                    @Override
                    public void notifyActivityDestroyed(IBinder activityToken) {
                        if (DEBUG) {
                            Slog.d(TAG, "notifyActivityDestroyed activityToken=" + activityToken);
                        }
                        mServiceStub.notifyActivityDestroyed(activityToken);
                    }
                });
    }

    @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            mShortcutServiceInternal = Objects.requireNonNull(
                    LocalServices.getService(ShortcutServiceInternal.class));
            mSoundTriggerInternal = LocalServices.getService(SoundTriggerInternal.class);
        } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            mServiceStub.systemRunning(isSafeMode());
        } else if (phase == PHASE_BOOT_COMPLETED) {
            mServiceStub.registerVoiceInteractionSessionListener(mLatencyLoggingListener);
        }
    }

    @Override
    public boolean isUserSupported(@NonNull TargetUser user) {
        return user.isFull();
    }

    private boolean isUserSupported(@NonNull UserInfo user) {
        return user.isFull();
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        if (DEBUG_USER) Slog.d(TAG, "onUserStarting(" + user + ")");

        mServiceStub.initForUser(user.getUserIdentifier());
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        if (DEBUG_USER) Slog.d(TAG, "onUserUnlocking(" + user + ")");

        mServiceStub.initForUser(user.getUserIdentifier());
        mServiceStub.switchImplementationIfNeeded(false);
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        if (DEBUG_USER) Slog.d(TAG, "onSwitchUser(" + from + " > " + to + ")");

        mServiceStub.switchUser(to.getUserIdentifier());
    }

    class LocalService extends VoiceInteractionManagerInternal {
        @Override
        public void startLocalVoiceInteraction(@NonNull IBinder callingActivity,
                @Nullable String attributionTag, @Nullable Bundle options) {
            if (DEBUG) {
                Slog.i(TAG, "startLocalVoiceInteraction " + callingActivity);
            }
            VoiceInteractionManagerService.this.mServiceStub.startLocalVoiceInteraction(
                    callingActivity, attributionTag, options);
        }

        @Override
        public boolean supportsLocalVoiceInteraction() {
            return VoiceInteractionManagerService.this.mServiceStub.supportsLocalVoiceInteraction();
        }

        @Override
        public void stopLocalVoiceInteraction(IBinder callingActivity) {
            if (DEBUG) {
                Slog.i(TAG, "stopLocalVoiceInteraction " + callingActivity);
            }
            VoiceInteractionManagerService.this.mServiceStub.stopLocalVoiceInteraction(
                    callingActivity);
        }

        @Override
        public boolean hasActiveSession(String packageName) {
            VoiceInteractionManagerServiceImpl impl =
                    VoiceInteractionManagerService.this.mServiceStub.mImpl;
            if (impl == null) {
                return false;
            }

            VoiceInteractionSessionConnection session =
                    impl.mActiveSession;
            if (session == null) {
                return false;
            }

            return TextUtils.equals(packageName, session.mSessionComponentName.getPackageName());
        }

        @Override
        public String getVoiceInteractorPackageName(IBinder callingVoiceInteractor) {
            VoiceInteractionManagerServiceImpl impl =
                    VoiceInteractionManagerService.this.mServiceStub.mImpl;
            if (impl == null) {
                return null;
            }
            VoiceInteractionSessionConnection session =
                    impl.mActiveSession;
            if (session == null) {
                return null;
            }
            IVoiceInteractor voiceInteractor = session.mInteractor;
            if (voiceInteractor == null || voiceInteractor.asBinder() != callingVoiceInteractor) {
                return null;
            }
            return session.mSessionComponentName.getPackageName();
        }

        @Override
        public HotwordDetectionServiceIdentity getHotwordDetectionServiceIdentity() {
            // IMPORTANT: This is called when performing permission checks; do not lock!

            // TODO: Have AppOpsPolicy register a listener instead of calling in here everytime.
            // Then also remove the `volatile`s that were added with this method.

            VoiceInteractionManagerServiceImpl impl =
                    VoiceInteractionManagerService.this.mServiceStub.mImpl;
            if (impl == null) {
                return null;
            }
            HotwordDetectionConnection hotwordDetectionConnection =
                    impl.mHotwordDetectionConnection;
            if (hotwordDetectionConnection == null) {
                return null;
            }
            return hotwordDetectionConnection.mIdentity;
        }

        // TODO(b/226201975): remove this method once RoleService supports pre-created users
        @Override
        public void onPreCreatedUserConversion(int userId) {
            Slogf.d(TAG, "onPreCreatedUserConversion(%d): calling onRoleHoldersChanged() again",
                    userId);
            mServiceStub.mRoleObserver.onRoleHoldersChanged(RoleManager.ROLE_ASSISTANT,
                                                UserHandle.of(userId));
        }

        @Override
        public void startListeningFromWearable(
                ParcelFileDescriptor audioStreamFromWearable,
                AudioFormat audioFormatFromWearable,
                PersistableBundle options,
                ComponentName targetVisComponentName,
                int userId,
                WearableHotwordDetectionCallback callback) {
            Slog.d(TAG, "#startListeningFromWearable");
            VoiceInteractionManagerServiceImpl impl = mServiceStub.mImpl;
            if (impl == null) {
                callback.onError(
                        "Unable to start listening from wearable because the service impl is"
                                + " null.");
                return;
            }
            if (targetVisComponentName != null && !targetVisComponentName.equals(impl.mComponent)) {
                callback.onError(
                        TextUtils.formatSimple(
                                "Unable to start listening from wearable because the target"
                                    + " VoiceInteractionService %s is different from the current"
                                    + " VoiceInteractionService %s",
                                targetVisComponentName, impl.mComponent));
                return;
            }
            if (userId != impl.mUser) {
                callback.onError(
                        TextUtils.formatSimple(
                                "Unable to start listening from wearable because the target userId"
                                    + " %s is different from the current"
                                    + " VoiceInteractionManagerServiceImpl's userId %s",
                                userId, impl.mUser));
                return;
            }
            synchronized (mServiceStub) {
                impl.startListeningFromWearableLocked(
                        audioStreamFromWearable, audioFormatFromWearable, options, callback);
            }
        }
    }

    // implementation entry point and binder service
    private final VoiceInteractionManagerServiceStub mServiceStub;

    class VoiceInteractionManagerServiceStub extends IVoiceInteractionManagerService.Stub {

        volatile VoiceInteractionManagerServiceImpl mImpl;

        private boolean mSafeMode;
        private int mCurUser;
        private boolean mCurUserSupported;

        @GuardedBy("this")
        private boolean mTemporarilyDisabled;

        /** The start value of showSessionId */
        private static final int SHOW_SESSION_START_ID = 0;

        private final boolean IS_HDS_REQUIRED = AppOpsPolicy.isHotwordDetectionServiceRequired(
                mContext.getPackageManager());

        @GuardedBy("this")
        private int mShowSessionId = SHOW_SESSION_START_ID;

        private final boolean mEnableService;
        // TODO(b/226201975): remove reference once RoleService supports pre-created users
        private final RoleObserver mRoleObserver;

        VoiceInteractionManagerServiceStub() {
            mEnableService = shouldEnableService(mContext);
            mRoleObserver = new RoleObserver(mContext.getMainExecutor());
        }

        void handleUserStop(String packageName, int userHandle) {
            synchronized (VoiceInteractionManagerServiceStub.this) {
                ComponentName curInteractor = getCurInteractor(userHandle);
                if (curInteractor != null && packageName.equals(curInteractor.getPackageName())) {
                    Slog.d(TAG, "switchImplementation for user stop.");
                    switchImplementationIfNeededLocked(true);
                }
            }
        }

        int getNextShowSessionId() {
            synchronized (this) {
                // Reset the showSessionId to SHOW_SESSION_START_ID to avoid the value exceeds
                // Integer.MAX_VALUE
                if (mShowSessionId == Integer.MAX_VALUE - 1) {
                    mShowSessionId = SHOW_SESSION_START_ID;
                }
                mShowSessionId++;
                return mShowSessionId;
            }
        }

        int getShowSessionId() {
            synchronized (this) {
                return mShowSessionId;
            }
        }

        @Override
        public @NonNull IVoiceInteractionSoundTriggerSession createSoundTriggerSessionAsOriginator(
                @NonNull Identity originatorIdentity, IBinder client,
                ModuleProperties moduleProperties) {
            Objects.requireNonNull(originatorIdentity);
            boolean forHotwordDetectionService = false;
            synchronized (VoiceInteractionManagerServiceStub.this) {
                enforceIsCurrentVoiceInteractionService();
                forHotwordDetectionService =
                        mImpl != null && mImpl.mHotwordDetectionConnection != null;
            }
            if (HotwordDetectionConnection.DEBUG) {
                Slog.d(TAG, "Creating a SoundTriggerSession, for HDS: "
                        + forHotwordDetectionService);
            }
            try (SafeCloseable ignored = PermissionUtil.establishIdentityDirect(
                    originatorIdentity)) {
                if (!IS_HDS_REQUIRED) {
                    // For devices which still have hotword exemption, any client (not just HDS
                    // clients) are trusted.
                    // TODO (b/292012931) remove once trusted uniformly required.
                    forHotwordDetectionService = true;
                }
                return new SoundTriggerSession(mSoundTriggerInternal.attach(client,
                            moduleProperties, forHotwordDetectionService), originatorIdentity);
            }
        }

        @Override
        public List<ModuleProperties> listModuleProperties(Identity originatorIdentity) {
            synchronized (VoiceInteractionManagerServiceStub.this) {
                enforceIsCurrentVoiceInteractionService();
            }
            return mSoundTriggerInternal.listModuleProperties(originatorIdentity);
        }

        // TODO: VI Make sure the caller is the current user or profile
        void startLocalVoiceInteraction(@NonNull final IBinder token,
                @Nullable String attributionTag, @Nullable Bundle options) {
            if (mImpl == null) return;

            final int callingUid = Binder.getCallingUid();
            final long caller = Binder.clearCallingIdentity();
            try {
                // HotwordDetector trigger uses VoiceInteractionService#showSession
                // We need to cancel here because UI is not being shown due to a SoundTrigger
                // HAL event.
                HotwordMetricsLogger.cancelHotwordTriggerToUiLatencySession(mContext);
                mImpl.showSessionLocked(options,
                        VoiceInteractionSession.SHOW_SOURCE_ACTIVITY, attributionTag,
                        new IVoiceInteractionSessionShowCallback.Stub() {
                            @Override
                            public void onFailed() {
                            }

                            @Override
                            public void onShown() {
                                synchronized (VoiceInteractionManagerServiceStub.this) {
                                    if (mImpl != null) {
                                        mImpl.grantImplicitAccessLocked(callingUid,
                                                /* intent= */ null);
                                    }
                                }
                                mAtmInternal.onLocalVoiceInteractionStarted(token,
                                        mImpl.mActiveSession.mSession,
                                        mImpl.mActiveSession.mInteractor);
                            }
                        },
                        token);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        public void stopLocalVoiceInteraction(IBinder callingActivity) {
            if (mImpl == null) return;

            final long caller = Binder.clearCallingIdentity();
            try {
                mImpl.finishLocked(callingActivity, true);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        public boolean supportsLocalVoiceInteraction() {
            if (mImpl == null) return false;

            return mImpl.supportsLocalVoiceInteraction();
        }

        void notifyActivityDestroyed(@NonNull IBinder activityToken) {
            synchronized (this) {
                if (mImpl == null || activityToken == null) return;

                Binder.withCleanCallingIdentity(
                        () -> mImpl.notifyActivityDestroyedLocked(activityToken));
            }
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (RuntimeException e) {
                // The activity manager only throws security exceptions, so let's
                // log all others.
                if (!(e instanceof SecurityException)) {
                    Slog.wtf(TAG, "VoiceInteractionManagerService Crash", e);
                }
                throw e;
            }
        }

        public void initForUser(int userHandle) {
            final TimingsTraceAndSlog t;
            if (DEBUG_USER) {
                t = new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
                t.traceBegin("initForUser(" + userHandle + ")");
            } else {
                t = null;
            }
            initForUserNoTracing(userHandle);
            if (t != null) {
                t.traceEnd();
            }
        }

        private void initForUserNoTracing(@UserIdInt int userHandle) {
            if (DEBUG) Slog.d(TAG, "**************** initForUser user=" + userHandle);
            String curInteractorStr = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.VOICE_INTERACTION_SERVICE, userHandle);
            ComponentName curRecognizer = getCurRecognizer(userHandle);
            VoiceInteractionServiceInfo curInteractorInfo = null;
            if (DEBUG) {
                Slog.d(TAG, "curInteractorStr=" + curInteractorStr
                        + " curRecognizer=" + curRecognizer
                        + " mEnableService=" + mEnableService
                        + " mTemporarilyDisabled=" + mTemporarilyDisabled);
            }
            if (curInteractorStr == null && curRecognizer != null && mEnableService) {
                // If there is no interactor setting, that means we are upgrading
                // from an older platform version.  If the current recognizer is not
                // set or matches the preferred recognizer, then we want to upgrade
                // the user to have the default voice interaction service enabled.
                // Note that we don't do this for low-RAM devices, since we aren't
                // supporting voice interaction services there.
                curInteractorInfo = findAvailInteractor(userHandle, curRecognizer.getPackageName());
                if (curInteractorInfo != null) {
                    // Looks good!  We'll apply this one.  To make it happen, we clear the
                    // recognizer so that we don't think we have anything set and will
                    // re-apply the settings.
                    if (DEBUG) Slog.d(TAG, "No set interactor, found avail: "
                            + curInteractorInfo.getServiceInfo().name);
                    curRecognizer = null;
                }
            }

            // If forceInteractorPackage exists, try to apply the interactor from this package if
            // possible and ignore the regular interactor setting.
            String forceInteractorPackage =
                    getForceVoiceInteractionServicePackage(mContext.getResources());
            if (forceInteractorPackage != null) {
                curInteractorInfo = findAvailInteractor(userHandle, forceInteractorPackage);
                if (curInteractorInfo != null) {
                    // We'll apply this one. Clear the recognizer and re-apply the settings.
                    curRecognizer = null;
                }
            }

            // If we are on a svelte device, make sure an interactor is not currently
            // enabled; if it is, turn it off.
            if (!mEnableService && curInteractorStr != null) {
                if (!TextUtils.isEmpty(curInteractorStr)) {
                    if (DEBUG) Slog.d(TAG, "Svelte device; disabling interactor");
                    setCurInteractor(null, userHandle);
                    curInteractorStr = "";
                }
            }

            if (curRecognizer != null) {
                // If we already have at least a recognizer, then we probably want to
                // leave things as they are...  unless something has disappeared.
                IPackageManager pm = AppGlobals.getPackageManager();
                ServiceInfo interactorInfo = null;
                ServiceInfo recognizerInfo = null;
                ComponentName curInteractor = !TextUtils.isEmpty(curInteractorStr)
                        ? ComponentName.unflattenFromString(curInteractorStr) : null;
                try {
                    recognizerInfo = pm.getServiceInfo(
                            curRecognizer,
                            PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                                    | PackageManager.GET_META_DATA,
                            userHandle);
                    if (recognizerInfo != null) {
                        RecognitionServiceInfo rsi =
                                RecognitionServiceInfo.parseInfo(
                                        mContext.getPackageManager(), recognizerInfo);
                        if (!TextUtils.isEmpty(rsi.getParseError())) {
                            Log.w(TAG, "Parse error in getAvailableServices: "
                                    + rsi.getParseError());
                            // We still use the recognizer to preserve pre-existing behavior.
                        }
                        if (!rsi.isSelectableAsDefault()) {
                            if (DEBUG) {
                                Slog.d(TAG, "Found non selectableAsDefault recognizer as"
                                        + " default. Unsetting the default and looking for another"
                                        + " one.");
                            }
                            recognizerInfo = null;
                        }
                    }
                    if (curInteractor != null) {
                        interactorInfo = pm.getServiceInfo(curInteractor,
                                PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userHandle);
                    }
                } catch (RemoteException e) {
                }
                // If the apps for the currently set components still exist, then all is okay.
                if (recognizerInfo != null && (curInteractor == null || interactorInfo != null)) {
                    if (DEBUG) Slog.d(TAG, "Current interactor/recognizer okay, done!");
                    return;
                }
                if (DEBUG) Slog.d(TAG, "Bad recognizer (" + recognizerInfo + ") or interactor ("
                        + interactorInfo + ")");
            }

            // Initializing settings. Look for an interactor first, but only on non-svelte and only
            // if the user hasn't explicitly unset it.
            if (curInteractorInfo == null && mEnableService && !"".equals(curInteractorStr)) {
                curInteractorInfo = findAvailInteractor(userHandle, null);
            }

            if (curInteractorInfo != null) {
                // Eventually it will be an error to not specify this.
                setCurInteractor(new ComponentName(curInteractorInfo.getServiceInfo().packageName,
                        curInteractorInfo.getServiceInfo().name), userHandle);
            } else {
                // No voice interactor, so clear the setting.
                setCurInteractor(null, userHandle);
            }

            initRecognizer(userHandle);
        }

        public void initRecognizer(int userHandle) {
            ComponentName curRecognizer = findAvailRecognizer(null, userHandle);
            if (curRecognizer != null) {
                setCurRecognizer(curRecognizer, userHandle);
            }
        }

        private boolean shouldEnableService(Context context) {
            // VoiceInteractionService should not be enabled on devices that have not declared the
            // recognition feature (including low-ram devices where notLowRam="true" takes effect),
            // unless the device's configuration has explicitly set the config flag for a fixed
            // voice interaction service.
            if (getForceVoiceInteractionServicePackage(context.getResources()) != null) {
                return true;
            }
            return context.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_VOICE_RECOGNIZERS);
        }

        private String getForceVoiceInteractionServicePackage(Resources res) {
            String interactorPackage = res.getString(
                    com.android.internal.R.string.config_forceVoiceInteractionServicePackage);
            return TextUtils.isEmpty(interactorPackage) ? null : interactorPackage;
        }

        public void systemRunning(boolean safeMode) {
            mSafeMode = safeMode;

            mPackageMonitor.register(mContext, BackgroundThread.getHandler().getLooper(),
                    UserHandle.ALL, true);
            new SettingsObserver(UiThread.getHandler());

            synchronized (this) {
                setCurrentUserLocked(ActivityManager.getCurrentUser());
                switchImplementationIfNeededLocked(false);
            }
        }

        private void setCurrentUserLocked(@UserIdInt int userHandle) {
            mCurUser = userHandle;
            final UserInfo userInfo = mUserManagerInternal.getUserInfo(mCurUser);
            mCurUserSupported = isUserSupported(userInfo);
        }

        public void switchUser(@UserIdInt int userHandle) {
            FgThread.getHandler().post(() -> {
                synchronized (this) {
                    setCurrentUserLocked(userHandle);
                    switchImplementationIfNeededLocked(false);
                }
            });
        }

        void switchImplementationIfNeeded(boolean force) {
            synchronized (this) {
                switchImplementationIfNeededLocked(force);
            }
        }

        void switchImplementationIfNeededLocked(boolean force) {
            if (!mCurUserSupported) {
                if (DEBUG_USER) {
                    Slog.d(TAG, "switchImplementationIfNeeded(): skipping: force= " + force
                            + "mCurUserSupported=" + mCurUserSupported);
                }
                if (mImpl != null) {
                    mImpl.shutdownLocked();
                    setImplLocked(null);
                }
                return;
            }

            final TimingsTraceAndSlog t;
            if (DEBUG_USER) {
                t = new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
                t.traceBegin("switchImplementation(" + mCurUser + ")");
            } else {
                t = null;
            }
            switchImplementationIfNeededNoTracingLocked(force);
            if (t != null) {
                t.traceEnd();
            }
        }

        void switchImplementationIfNeededNoTracingLocked(boolean force) {
            if (!mSafeMode) {
                String curService = Settings.Secure.getStringForUser(
                        mResolver, Settings.Secure.VOICE_INTERACTION_SERVICE, mCurUser);
                ComponentName serviceComponent = null;
                ServiceInfo serviceInfo = null;
                if (curService != null && !curService.isEmpty()) {
                    try {
                        serviceComponent = ComponentName.unflattenFromString(curService);
                        serviceInfo = getValidVoiceInteractionServiceInfo(serviceComponent);
                    } catch (RuntimeException e) {
                        Slog.wtf(TAG, "Bad voice interaction service name " + curService, e);
                        serviceComponent = null;
                        serviceInfo = null;
                    }
                }

                final boolean hasComponent = serviceComponent != null && serviceInfo != null;

                if (mUserManagerInternal.isUserUnlockingOrUnlocked(mCurUser)) {
                    if (hasComponent) {
                        mShortcutServiceInternal.setShortcutHostPackage(TAG,
                                serviceComponent.getPackageName(), mCurUser);
                        mAtmInternal.setAllowAppSwitches(TAG,
                                serviceInfo.applicationInfo.uid, mCurUser);
                    } else {
                        mShortcutServiceInternal.setShortcutHostPackage(TAG, null, mCurUser);
                        mAtmInternal.setAllowAppSwitches(TAG, -1, mCurUser);
                    }
                }

                if (force || mImpl == null || mImpl.mUser != mCurUser
                        || !mImpl.mComponent.equals(serviceComponent)) {
                    unloadAllKeyphraseModels();
                    if (mImpl != null) {
                        mImpl.shutdownLocked();
                    }
                    if (hasComponent) {
                        setImplLocked(new VoiceInteractionManagerServiceImpl(mContext,
                                UiThread.getHandler(), this, mCurUser, serviceComponent));
                        mImpl.startLocked();
                    } else {
                        setImplLocked(null);
                    }
                }
            }
        }

        @Nullable
        private ServiceInfo getValidVoiceInteractionServiceInfo(
                @Nullable ComponentName serviceComponent) {
            if (serviceComponent == null) {
                return null;
            }
            List<ResolveInfo> services = queryInteractorServices(
                    mCurUser, serviceComponent.getPackageName());
            for (int i = 0; i < services.size(); i++) {
                ResolveInfo service = services.get(i);
                VoiceInteractionServiceInfo info = new VoiceInteractionServiceInfo(
                        mContext.getPackageManager(), service.serviceInfo);
                ServiceInfo candidateInfo = info.getServiceInfo();
                if (candidateInfo != null
                        && candidateInfo.getComponentName().equals(serviceComponent)) {
                    return candidateInfo;
                }
            }
            return null;
        }

        private List<ResolveInfo> queryInteractorServices(
                @UserIdInt int user,
                @Nullable String packageName) {
            return mContext.getPackageManager().queryIntentServicesAsUser(
                    new Intent(VoiceInteractionService.SERVICE_INTERFACE).setPackage(packageName),
                    PackageManager.GET_META_DATA
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                    user);
        }

        VoiceInteractionServiceInfo findAvailInteractor(
                @UserIdInt int user,
                @Nullable String packageName) {
            List<ResolveInfo> available = queryInteractorServices(user, packageName);
            int numAvailable = available.size();
            if (numAvailable == 0) {
                Slog.w(TAG, "no available voice interaction services found for user " + user);
                return null;
            }
            // Find first system package.  We never want to allow third party services to
            // be automatically selected, because those require approval of the user.
            VoiceInteractionServiceInfo foundInfo = null;
            for (int i = 0; i < numAvailable; i++) {
                ServiceInfo cur = available.get(i).serviceInfo;
                if ((cur.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    continue;
                }
                VoiceInteractionServiceInfo info =
                        new VoiceInteractionServiceInfo(mContext.getPackageManager(), cur);
                if (info.getParseError() != null) {
                    Slog.w(TAG,
                            "Bad interaction service " + cur.packageName + "/"
                                    + cur.name + ": " + info.getParseError());
                } else if (foundInfo == null) {
                    foundInfo = info;
                } else {
                    Slog.w(TAG, "More than one voice interaction service, "
                            + "picking first "
                            + new ComponentName(
                            foundInfo.getServiceInfo().packageName,
                            foundInfo.getServiceInfo().name)
                            + " over "
                            + new ComponentName(cur.packageName, cur.name));
                }
            }
            return foundInfo;
        }

        ComponentName getCurInteractor(int userHandle) {
            String curInteractor = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.VOICE_INTERACTION_SERVICE, userHandle);
            if (TextUtils.isEmpty(curInteractor)) {
                return null;
            }
            if (DEBUG) {
                Slog.d(TAG, "getCurInteractor curInteractor=" + curInteractor
                    + " user=" + userHandle);
            }
            return ComponentName.unflattenFromString(curInteractor);
        }

        void setCurInteractor(ComponentName comp, int userHandle) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.VOICE_INTERACTION_SERVICE,
                    comp != null ? comp.flattenToShortString() : "", userHandle);
            if (DEBUG) {
                Slog.d(TAG, "setCurInteractor comp=" + comp + " user=" + userHandle);
            }
        }

        ComponentName findAvailRecognizer(String prefPackage, int userHandle) {
            if (prefPackage == null) {
                prefPackage = getDefaultRecognizer();
            }

            List<RecognitionServiceInfo> available =
                    RecognitionServiceInfo.getAvailableServices(mContext, userHandle);
            if (available.size() == 0) {
                Slog.w(TAG, "no available voice recognition services found for user " + userHandle);
                return null;
            } else {
                List<RecognitionServiceInfo> nonSelectableAsDefault =
                        removeNonSelectableAsDefault(available);
                if (available.size() == 0) {
                    Slog.w(TAG, "No selectableAsDefault recognition services found for user "
                            + userHandle + ". Falling back to non selectableAsDefault ones.");
                    available = nonSelectableAsDefault;
                }
                int numAvailable = available.size();
                if (prefPackage != null) {
                    for (int i = 0; i < numAvailable; i++) {
                        ServiceInfo serviceInfo = available.get(i).getServiceInfo();
                        if (prefPackage.equals(serviceInfo.packageName)) {
                            return new ComponentName(serviceInfo.packageName, serviceInfo.name);
                        }
                    }
                }
                if (numAvailable > 1) {
                    Slog.w(TAG, "more than one voice recognition service found, picking first");
                }

                ServiceInfo serviceInfo = available.get(0).getServiceInfo();
                return new ComponentName(serviceInfo.packageName, serviceInfo.name);
            }
        }

        private List<RecognitionServiceInfo> removeNonSelectableAsDefault(
                List<RecognitionServiceInfo> services) {
            List<RecognitionServiceInfo> nonSelectableAsDefault = new ArrayList<>();
            for (int i = services.size() - 1; i >= 0; i--) {
                if (!services.get(i).isSelectableAsDefault()) {
                    nonSelectableAsDefault.add(services.remove(i));
                }
            }
            return nonSelectableAsDefault;
        }

        @Nullable
        public String getDefaultRecognizer() {
            String recognizer = mContext.getString(R.string.config_systemSpeechRecognizer);
            return TextUtils.isEmpty(recognizer) ? null : recognizer;
        }

        ComponentName getCurRecognizer(int userHandle) {
            String curRecognizer = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.VOICE_RECOGNITION_SERVICE, userHandle);
            if (TextUtils.isEmpty(curRecognizer)) {
                return null;
            }
            if (DEBUG) Slog.d(TAG, "getCurRecognizer curRecognizer=" + curRecognizer
                    + " user=" + userHandle);
            return ComponentName.unflattenFromString(curRecognizer);
        }

        void setCurRecognizer(ComponentName comp, int userHandle) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.VOICE_RECOGNITION_SERVICE,
                    comp != null ? comp.flattenToShortString() : "", userHandle);
            if (DEBUG) Slog.d(TAG, "setCurRecognizer comp=" + comp
                    + " user=" + userHandle);
        }

        ComponentName getCurAssistant(int userHandle) {
            String curAssistant = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.ASSISTANT, userHandle);
            if (TextUtils.isEmpty(curAssistant)) {
                return null;
            }
            if (DEBUG) Slog.d(TAG, "getCurAssistant curAssistant=" + curAssistant
                    + " user=" + userHandle);
            return ComponentName.unflattenFromString(curAssistant);
        }

        void resetCurAssistant(int userHandle) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.ASSISTANT, null, userHandle);
        }

        void forceRestartHotwordDetector() {
            mImpl.forceRestartHotwordDetector();
        }

        // Called by Shell command
        void setDebugHotwordLogging(boolean logging) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "setTemporaryLogging without running voice interaction service");
                    return;
                }
                mImpl.setDebugHotwordLoggingLocked(logging);
            }
        }

        @Override
        public void showSession(@Nullable Bundle args, int flags, @Nullable String attributionTag) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.showSessionLocked(args, flags, attributionTag, null, null);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public boolean deliverNewSession(IBinder token, IVoiceInteractionSession session,
                IVoiceInteractor interactor) {
            synchronized (this) {
                if (mImpl == null) {
                    throw new SecurityException(
                            "deliverNewSession without running voice interaction service");
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.deliverNewSessionLocked(token, session, interactor);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public boolean showSessionFromSession(@NonNull IBinder token, @Nullable Bundle sessionArgs,
                int flags, @Nullable String attributionTag) {
            synchronized (this) {
                final String csKey = mContext.getResources()
                        .getString(R.string.config_defaultContextualSearchKey);
                final String csEnabledKey = mContext.getResources()
                        .getString(R.string.config_defaultContextualSearchEnabled);

                // If the request is for Contextual Search, process it differently
                if (sessionArgs != null && sessionArgs.containsKey(csKey)) {
                    if (sessionArgs.getBoolean(csEnabledKey, true)) {
                        // If Contextual Search is enabled, try to follow that path.
                        Intent launchIntent = getContextualSearchIntent(sessionArgs);
                        if (launchIntent != null) {
                            // Hand over to contextual search helper.
                            Slog.d(TAG, "Handed over to contextual search helper.");
                            final long caller = Binder.clearCallingIdentity();
                            try {
                                return startContextualSearch(launchIntent);
                            } finally {
                                Binder.restoreCallingIdentity(caller);
                            }
                        }
                    }

                    // Since we are here, Contextual Search helper couldn't handle the request.
                    final String visEnabledKey = mContext.getResources()
                            .getString(R.string.config_defaultContextualSearchLegacyEnabled);
                    if (sessionArgs.getBoolean(visEnabledKey, true)) {
                        // If visEnabledKey is set to true (or absent), we try following VIS path.
                        String csPkgName = mContext.getResources()
                                .getString(R.string.config_defaultContextualSearchPackageName);
                        ComponentName currInteractor =
                                getCurInteractor(Binder.getCallingUserHandle().getIdentifier());
                        if (currInteractor == null
                                || !csPkgName.equals(currInteractor.getPackageName())) {
                            // Check if the interactor can handle Contextual Search.
                            // If not, return failure.
                            Slog.w(TAG, "Contextual Search not supported yet. Returning failure.");
                            return false;
                        }
                    } else {
                        // If visEnabledKey is set to false AND the request was for Contextual
                        // Search, return false.
                        return false;
                    }
                    // Given that we haven't returned yet, we can say that
                    // - Contextual Search Helper couldn't handle the request
                    // - VIS path for Contextual Search is enabled
                    // - The current interactor supports Contextual Search.
                    // Hence, we will proceed with the VIS path.
                    Slog.d(TAG, "Contextual search not supported yet. Proceeding with VIS.");

                }

                if (mImpl == null) {
                    Slog.w(TAG, "showSessionFromSession without running voice interaction service");
                    return false;
                }
                // If the token is null, then the request to show the session is not coming from
                // the active VoiceInteractionService session.
                // We need to cancel here because UI is not being shown due to a SoundTrigger
                // HAL event.
                if (token == null) {
                    HotwordMetricsLogger.cancelHotwordTriggerToUiLatencySession(mContext);
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.showSessionLocked(sessionArgs, flags, attributionTag, null, null);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public boolean hideSessionFromSession(IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "hideSessionFromSession without running voice interaction service");
                    return false;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.hideSessionLocked();
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int startVoiceActivity(@NonNull IBinder token, @NonNull Intent intent,
                @Nullable String resolvedType, @Nullable String attributionTag) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "startVoiceActivity without running voice interaction service");
                    return ActivityManager.START_CANCELED;
                }
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    final ActivityInfo activityInfo = intent.resolveActivityInfo(
                            mContext.getPackageManager(), PackageManager.MATCH_ALL);
                    if (activityInfo != null) {
                        final int activityUid = activityInfo.applicationInfo.uid;
                        mImpl.grantImplicitAccessLocked(activityUid, intent);
                    } else {
                        Slog.w(TAG, "Cannot find ActivityInfo in startVoiceActivity.");
                    }
                    return mImpl.startVoiceActivityLocked(attributionTag, callingPid, callingUid,
                            token, intent, resolvedType);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int startAssistantActivity(@NonNull IBinder token, @NonNull Intent intent,
                @Nullable String resolvedType, @NonNull String attributionTag,
                @NonNull Bundle bundle) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "startAssistantActivity without running voice interaction service");
                    return ActivityManager.START_CANCELED;
                }
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.startAssistantActivityLocked(attributionTag, callingPid,
                            callingUid, token, intent, resolvedType, bundle);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void requestDirectActions(@NonNull IBinder token, int taskId,
                @NonNull IBinder assistToken, @Nullable RemoteCallback cancellationCallback,
                @NonNull RemoteCallback resultCallback) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "requestDirectActions without running voice interaction service");
                    resultCallback.sendResult(null);
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.requestDirectActionsLocked(token, taskId, assistToken,
                            cancellationCallback, resultCallback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void performDirectAction(@NonNull IBinder token, @NonNull String actionId,
                @NonNull Bundle arguments, int taskId, IBinder assistToken,
                @Nullable RemoteCallback cancellationCallback,
                @NonNull RemoteCallback resultCallback) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "performDirectAction without running voice interaction service");
                    resultCallback.sendResult(null);
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.performDirectActionLocked(token, actionId, arguments, taskId,
                            assistToken, cancellationCallback, resultCallback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void setKeepAwake(IBinder token, boolean keepAwake) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "setKeepAwake without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.setKeepAwakeLocked(token, keepAwake);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void closeSystemDialogs(IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "closeSystemDialogs without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.closeSystemDialogsLocked(token);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void finish(IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "finish without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.finishLocked(token, false);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void setDisabledShowContext(int flags) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "setDisabledShowContext without running voice interaction service");
                    return;
                }
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.setDisabledShowContextLocked(callingUid, flags);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int getDisabledShowContext() {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "getDisabledShowContext without running voice interaction service");
                    return 0;
                }
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.getDisabledShowContextLocked(callingUid);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int getUserDisabledShowContext() {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG,
                            "getUserDisabledShowContext without running voice interaction service");
                    return 0;
                }
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.getUserDisabledShowContextLocked(callingUid);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public void setDisabled(boolean disabled) {
            super.setDisabled_enforcePermission();

            synchronized (this) {
                if (mTemporarilyDisabled == disabled) {
                    if (DEBUG) Slog.d(TAG, "setDisabled(): already " + disabled);
                    return;
                }
                mTemporarilyDisabled = disabled;
                if (mTemporarilyDisabled) {
                    Slog.i(TAG, "setDisabled(): temporarily disabling and hiding current session");
                    try {
                        hideCurrentSession();
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to call hideCurrentSession", e);
                    }
                } else {
                    Slog.i(TAG, "setDisabled(): re-enabling");
                }
            }
        }

        @Override
        public void startListeningVisibleActivityChanged(@NonNull IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "startListeningVisibleActivityChanged without running"
                            + " voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.startListeningVisibleActivityChangedLocked(token);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void stopListeningVisibleActivityChanged(@NonNull IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "stopListeningVisibleActivityChanged without running"
                            + " voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.stopListeningVisibleActivityChangedLocked(token);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void notifyActivityEventChanged(@NonNull IBinder activityToken, int type) {
            synchronized (this) {
                if (mImpl == null || activityToken == null) {
                    return;
                }
                Binder.withCleanCallingIdentity(
                        () -> mImpl.notifyActivityEventChangedLocked(activityToken, type));
            }
        }
        //----------------- Hotword Detection/Validation APIs --------------------------------//

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_HOTWORD_DETECTION)
        @Override
        public void updateState(
                @Nullable PersistableBundle options,
                @Nullable SharedMemory sharedMemory,
                @NonNull IBinder token) {
            super.updateState_enforcePermission();

            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                Binder.withCleanCallingIdentity(
                        () -> mImpl.updateStateLocked(options, sharedMemory, token));
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_HOTWORD_DETECTION)
        @Override
        public void initAndVerifyDetector(
                @NonNull Identity voiceInteractorIdentity,
                @Nullable PersistableBundle options,
                @Nullable SharedMemory sharedMemory,
                @NonNull IBinder token,
                IHotwordRecognitionStatusCallback callback,
                int detectorType) {
            super.initAndVerifyDetector_enforcePermission();

            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                voiceInteractorIdentity.uid = Binder.getCallingUid();
                voiceInteractorIdentity.pid = Binder.getCallingPid();

                Binder.withCleanCallingIdentity(
                        () -> mImpl.initAndVerifyDetectorLocked(voiceInteractorIdentity, options,
                                sharedMemory, token, callback, detectorType));
            }
        }

        @Override
        public void destroyDetector(@NonNull IBinder token) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "destroyDetector without running voice interaction service");
                    return;
                }

                Binder.withCleanCallingIdentity(
                        () -> mImpl.destroyDetectorLocked(token));
            }
        }

        @Override
        public void shutdownHotwordDetectionService() {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG,
                            "shutdownHotwordDetectionService without running voice"
                                    + " interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.shutdownHotwordDetectionServiceLocked();
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @android.annotation.EnforcePermission(
                android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public void subscribeVisualQueryRecognitionStatus(IVisualQueryRecognitionStatusListener
                listener) {
            super.subscribeVisualQueryRecognitionStatus_enforcePermission();
            synchronized (this) {
                mVisualQueryRecognitionStatusListener = listener;
            }
        }

        @android.annotation.EnforcePermission(
                android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public void enableVisualQueryDetection(
                IVisualQueryDetectionAttentionListener listener) {
            super.enableVisualQueryDetection_enforcePermission();
            synchronized (this) {

                if (mImpl == null) {
                    Slog.w(TAG,
                            "enableVisualQueryDetection without running voice interaction service");
                    return;
                }
                this.mImpl.setVisualQueryDetectionAttentionListenerLocked(listener);
            }
        }

        @android.annotation.EnforcePermission(
                android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public void disableVisualQueryDetection() {
            super.disableVisualQueryDetection_enforcePermission();
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG,
                            "disableVisualQueryDetection without running voice interaction"
                                    + " service");
                    return;
                }
                this.mImpl.setVisualQueryDetectionAttentionListenerLocked(null);
            }
        }

        @Override
        public void startPerceiving(
                IVisualQueryDetectionVoiceInteractionCallback callback)
                throws RemoteException {
            enforceCallingPermission(Manifest.permission.RECORD_AUDIO);
            enforceCallingPermission(Manifest.permission.CAMERA);
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG, "startPerceiving without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    boolean success = mImpl.startPerceivingLocked(callback);
                    if (success && mVisualQueryRecognitionStatusListener != null) {
                        mVisualQueryRecognitionStatusListener.onStartPerceiving();
                    }
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void stopPerceiving() throws RemoteException {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG, "stopPerceiving without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    boolean success = mImpl.stopPerceivingLocked();
                    if (success && mVisualQueryRecognitionStatusListener != null) {
                        mVisualQueryRecognitionStatusListener.onStopPerceiving();
                    }
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void startListeningFromMic(
                AudioFormat audioFormat,
                IMicrophoneHotwordDetectionVoiceInteractionCallback callback)
                throws RemoteException {
            enforceCallingPermission(Manifest.permission.RECORD_AUDIO);
            enforceCallingPermission(Manifest.permission.CAPTURE_AUDIO_HOTWORD);
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG, "startListeningFromMic without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.startListeningFromMicLocked(audioFormat, callback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void startListeningFromExternalSource(
                ParcelFileDescriptor audioStream,
                AudioFormat audioFormat,
                PersistableBundle options,
                @NonNull IBinder token,
                IMicrophoneHotwordDetectionVoiceInteractionCallback callback)
                throws RemoteException {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG, "startListeningFromExternalSource without running voice"
                            + " interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.startListeningFromExternalSourceLocked(audioStream, audioFormat, options,
                            token, callback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void stopListeningFromMic() throws RemoteException {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG, "stopListeningFromMic without running voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.stopListeningFromMicLocked();
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public void triggerHardwareRecognitionEventForTest(
                SoundTrigger.KeyphraseRecognitionEvent event,
                IHotwordRecognitionStatusCallback callback)
                throws RemoteException {
            enforceCallingPermission(Manifest.permission.RECORD_AUDIO);
            enforceCallingPermission(Manifest.permission.CAPTURE_AUDIO_HOTWORD);
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                if (mImpl == null) {
                    Slog.w(TAG, "triggerHardwareRecognitionEventForTest without running"
                            + " voice interaction service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.triggerHardwareRecognitionEventForTestLocked(event, callback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }


      //----------------- Model management APIs --------------------------------//

        @Override
        public KeyphraseSoundModel getKeyphraseSoundModel(int keyphraseId, String bcp47Locale) {
            enforceCallerAllowedToEnrollVoiceModel();

            if (bcp47Locale == null) {
                throw new IllegalArgumentException("Illegal argument(s) in getKeyphraseSoundModel");
            }

            final int callingUserId = UserHandle.getCallingUserId();
            final long caller = Binder.clearCallingIdentity();
            try {
                return mDbHelper.getKeyphraseSoundModel(keyphraseId, callingUserId, bcp47Locale);
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public int updateKeyphraseSoundModel(KeyphraseSoundModel model) {
            enforceCallerAllowedToEnrollVoiceModel();
            if (model == null) {
                throw new IllegalArgumentException("Model must not be null");
            }

            final long caller = Binder.clearCallingIdentity();
            try {
                if (mDbHelper.updateKeyphraseSoundModel(model)) {
                    synchronized (this) {
                        // Notify the voice interaction service of a change in sound models.
                        if (mImpl != null && mImpl.mService != null) {
                            mImpl.notifySoundModelsChangedLocked();
                        }
                    }
                    return SoundTriggerInternal.STATUS_OK;
                } else {
                    return SoundTriggerInternal.STATUS_ERROR;
                }
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        public int deleteKeyphraseSoundModel(int keyphraseId, String bcp47Locale) {
            enforceCallerAllowedToEnrollVoiceModel();

            if (bcp47Locale == null) {
                throw new IllegalArgumentException(
                        "Illegal argument(s) in deleteKeyphraseSoundModel");
            }

            final int callingUserId = UserHandle.getCallingUserId();
            boolean deleted = false;
            final long caller = Binder.clearCallingIdentity();
            try {
                SoundTriggerSession session = mLoadedKeyphraseIds.get(keyphraseId);
                if (session != null) {
                    int unloadStatus = session.unloadKeyphraseModel(keyphraseId);
                    if (unloadStatus != SoundTriggerInternal.STATUS_OK) {
                        Slog.w(TAG, "Unable to unload keyphrase sound model:" + unloadStatus);
                    }
                }
                deleted = mDbHelper.deleteKeyphraseSoundModel(keyphraseId, callingUserId,
                        bcp47Locale);
                return deleted ? SoundTriggerInternal.STATUS_OK : SoundTriggerInternal.STATUS_ERROR;
            } finally {
                if (deleted) {
                    synchronized (this) {
                        // Notify the voice interaction service of a change in sound models.
                        if (mImpl != null && mImpl.mService != null) {
                            mImpl.notifySoundModelsChangedLocked();
                        }
                        mLoadedKeyphraseIds.remove(keyphraseId);
                    }
                }
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Override
        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_VOICE_KEYPHRASES)
        public void setModelDatabaseForTestEnabled(boolean enabled, IBinder token) {
            super.setModelDatabaseForTestEnabled_enforcePermission();
            enforceCallerAllowedToEnrollVoiceModel();
            synchronized (this) {
                if (enabled) {
                    // Replace the dbhelper with a new test db
                    final var db = new TestModelEnrollmentDatabase();
                    try {
                        // Listen to our caller death, and make sure we revert to the real
                        // db if they left the model in a test state.
                        token.linkToDeath(() -> {
                            synchronized (this) {
                                if (mDbHelper == db) {
                                    mDbHelper = mRealDbHelper;
                                    mImpl.notifySoundModelsChangedLocked();
                                }
                            }
                        }, 0);
                    } catch (RemoteException e) {
                        // If the caller is already dead, nothing to do.
                        return;
                    }
                    mDbHelper = db;
                    mImpl.notifySoundModelsChangedLocked();
                } else {
                    // Nothing to do if the db is already set to the real impl.
                    if (mDbHelper != mRealDbHelper) {
                        mDbHelper = mRealDbHelper;
                        mImpl.notifySoundModelsChangedLocked();
                    }
                }
            }
        }

        //----------------- SoundTrigger APIs --------------------------------//
        @Override
        public boolean isEnrolledForKeyphrase(int keyphraseId, String bcp47Locale) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();
            }

            if (bcp47Locale == null) {
                throw new IllegalArgumentException("Illegal argument(s) in isEnrolledForKeyphrase");
            }

            final int callingUserId = UserHandle.getCallingUserId();
            final long caller = Binder.clearCallingIdentity();
            try {
                KeyphraseSoundModel model =
                        mDbHelper.getKeyphraseSoundModel(keyphraseId, callingUserId, bcp47Locale);
                return model != null;
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @Nullable
        public KeyphraseMetadata getEnrolledKeyphraseMetadata(String keyphrase,
                String bcp47Locale) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();
            }

            if (bcp47Locale == null) {
                throw new IllegalArgumentException("Illegal argument(s) in isEnrolledForKeyphrase");
            }

            final int callingUserId = UserHandle.getCallingUserId();
            final long caller = Binder.clearCallingIdentity();
            try {
                KeyphraseSoundModel model =
                        mDbHelper.getKeyphraseSoundModel(keyphrase, callingUserId, bcp47Locale);
                if (model == null) {
                    return null;
                }

                for (SoundTrigger.Keyphrase phrase : model.getKeyphrases()) {
                    if (keyphrase.equals(phrase.getText())) {
                        ArraySet<Locale> locales = new ArraySet<>();
                        locales.add(phrase.getLocale());
                        return new KeyphraseMetadata(
                                phrase.getId(),
                                phrase.getText(),
                                locales,
                                phrase.getRecognitionModes());
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(caller);
            }

            return null;
        }

        private class SoundTriggerSession extends IVoiceInteractionSoundTriggerSession.Stub {
            final SoundTriggerInternal.Session mSession;
            private IHotwordRecognitionStatusCallback mSessionExternalCallback;
            private IRecognitionStatusCallback mSessionInternalCallback;
            private final Identity mVoiceInteractorIdentity;

            SoundTriggerSession(
                    SoundTriggerInternal.Session session,
                    Identity voiceInteractorIdentity) {
                mSession = session;
                mVoiceInteractorIdentity = voiceInteractorIdentity;
            }

            @Override
            public ModuleProperties getDspModuleProperties() {
                // Allow the call if this is the current voice interaction service.
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    enforceIsCurrentVoiceInteractionService();

                    final long caller = Binder.clearCallingIdentity();
                    try {
                        return mSession.getModuleProperties();
                    } finally {
                        Binder.restoreCallingIdentity(caller);
                    }
                }
            }

            @Override
            public int startRecognition(int keyphraseId, String bcp47Locale,
                    IHotwordRecognitionStatusCallback callback, RecognitionConfig recognitionConfig,
                    boolean runInBatterySaverMode) {
                // Allow the call if this is the current voice interaction service.
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    enforceIsCurrentVoiceInteractionService();

                    if (callback == null || recognitionConfig == null || bcp47Locale == null) {
                        throw new IllegalArgumentException(
                                "Illegal argument(s) in startRecognition");
                    }
                    if (runInBatterySaverMode) {
                        enforceCallingPermission(
                                Manifest.permission.SOUND_TRIGGER_RUN_IN_BATTERY_SAVER);
                    }
                }

                final int callingUserId = UserHandle.getCallingUserId();
                final long caller = Binder.clearCallingIdentity();
                try {
                    KeyphraseSoundModel soundModel =
                            mDbHelper.getKeyphraseSoundModel(keyphraseId,
                                    callingUserId, bcp47Locale);
                    if (soundModel == null
                            || soundModel.getUuid() == null
                            || soundModel.getKeyphrases() == null) {
                        Slog.w(TAG, "No matching sound model found in startRecognition");
                        return SoundTriggerInternal.STATUS_ERROR;
                    }
                    // Regardless of the status of the start recognition, we need to make sure
                    // that we unload this model if needed later.
                    synchronized (VoiceInteractionManagerServiceStub.this) {
                        mLoadedKeyphraseIds.put(keyphraseId, this);
                        if (mSessionExternalCallback == null
                                || mSessionInternalCallback == null
                                || callback.asBinder() != mSessionExternalCallback.asBinder()) {
                            mSessionInternalCallback = createSoundTriggerCallbackLocked(callback,
                                    mVoiceInteractorIdentity);
                            mSessionExternalCallback = callback;
                        }
                    }
                    return mSession.startRecognition(keyphraseId, soundModel,
                            mSessionInternalCallback, recognitionConfig, runInBatterySaverMode);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }

            @Override
            public int stopRecognition(int keyphraseId,
                    IHotwordRecognitionStatusCallback callback) {
                final IRecognitionStatusCallback soundTriggerCallback;
                // Allow the call if this is the current voice interaction service.
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    enforceIsCurrentVoiceInteractionService();
                    if (mSessionExternalCallback == null
                            || mSessionInternalCallback == null
                            || callback.asBinder() != mSessionExternalCallback.asBinder()) {
                        soundTriggerCallback = createSoundTriggerCallbackLocked(callback,
                                mVoiceInteractorIdentity);
                        Slog.w(TAG, "stopRecognition() called with a different callback than"
                                + "startRecognition()");
                    } else {
                        soundTriggerCallback = mSessionInternalCallback;
                    }
                    mSessionExternalCallback = null;
                    mSessionInternalCallback = null;
                }

                final long caller = Binder.clearCallingIdentity();
                try {
                    return mSession.stopRecognition(keyphraseId, soundTriggerCallback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }

            @Override
            public int setParameter(int keyphraseId, @ModelParams int modelParam, int value) {
                // Allow the call if this is the current voice interaction service.
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    enforceIsCurrentVoiceInteractionService();
                }

                final long caller = Binder.clearCallingIdentity();
                try {
                    return mSession.setParameter(keyphraseId, modelParam, value);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }

            @Override
            public int getParameter(int keyphraseId, @ModelParams int modelParam) {
                // Allow the call if this is the current voice interaction service.
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    enforceIsCurrentVoiceInteractionService();
                }

                final long caller = Binder.clearCallingIdentity();
                try {
                    return mSession.getParameter(keyphraseId, modelParam);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }

            @Override
            @Nullable
            public ModelParamRange queryParameter(int keyphraseId, @ModelParams int modelParam) {
                // Allow the call if this is the current voice interaction service.
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    enforceIsCurrentVoiceInteractionService();
                }

                final long caller = Binder.clearCallingIdentity();
                try {
                    return mSession.queryParameter(keyphraseId, modelParam);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }

            @Override
            public void detach() {
                mSession.detach();
            }

            private int unloadKeyphraseModel(int keyphraseId) {
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mSession.unloadKeyphraseModel(keyphraseId);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        private synchronized void unloadAllKeyphraseModels() {
            for (int i = 0; i < mLoadedKeyphraseIds.size(); i++) {
                int id = mLoadedKeyphraseIds.keyAt(i);
                SoundTriggerSession session = mLoadedKeyphraseIds.valueAt(i);
                int status = session.unloadKeyphraseModel(id);
                if (status != SoundTriggerInternal.STATUS_OK) {
                    Slog.w(TAG, "Failed to unload keyphrase " + id + ":" + status);
                }
            }
            mLoadedKeyphraseIds.clear();
        }

        @Override
        public ComponentName getActiveServiceComponentName() {
            synchronized (this) {
                return mImpl != null ? mImpl.mComponent : null;
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public boolean showSessionForActiveService(@Nullable Bundle args, int sourceFlags,
                @Nullable String attributionTag,
                @Nullable IVoiceInteractionSessionShowCallback showCallback,
                @Nullable IBinder activityToken) {
            super.showSessionForActiveService_enforcePermission();

            if (DEBUG_USER) Slog.d(TAG, "showSessionForActiveService()");

            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "showSessionForActiveService without running voice interaction"
                            + "service");
                    return false;
                }
                if (mTemporarilyDisabled) {
                    Slog.i(TAG, "showSessionForActiveService(): ignored while temporarily "
                            + "disabled");
                    return false;
                }

                final long caller = Binder.clearCallingIdentity();
                try {
                    // HotwordDetector trigger uses VoiceInteractionService#showSession
                    // We need to cancel here because UI is not being shown due to a SoundTrigger
                    // HAL event.
                    HotwordMetricsLogger.cancelHotwordTriggerToUiLatencySession(mContext);

                    return mImpl.showSessionLocked(args,
                            sourceFlags
                                    | VoiceInteractionSession.SHOW_WITH_ASSIST
                                    | VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
                            attributionTag, showCallback, activityToken);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public void hideCurrentSession() throws RemoteException {

            super.hideCurrentSession_enforcePermission();

            if (mImpl == null) {
                return;
            }
            final long caller = Binder.clearCallingIdentity();
            try {
                if (mImpl.mActiveSession != null && mImpl.mActiveSession.mSession != null) {
                    try {
                        mImpl.mActiveSession.mSession.closeSystemDialogs();
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to call closeSystemDialogs", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(caller);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public void launchVoiceAssistFromKeyguard() {
            super.launchVoiceAssistFromKeyguard_enforcePermission();

            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "launchVoiceAssistFromKeyguard without running voice interaction"
                            + "service");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.launchVoiceAssistFromKeyguard();
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public boolean isSessionRunning() {
            super.isSessionRunning_enforcePermission();

            synchronized (this) {
                return mImpl != null && mImpl.mActiveSession != null;
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public boolean activeServiceSupportsAssist() {
            super.activeServiceSupportsAssist_enforcePermission();

            synchronized (this) {
                return mImpl != null && mImpl.mInfo != null && mImpl.mInfo.getSupportsAssist();
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public boolean activeServiceSupportsLaunchFromKeyguard() throws RemoteException {
            super.activeServiceSupportsLaunchFromKeyguard_enforcePermission();

            synchronized (this) {
                return mImpl != null && mImpl.mInfo != null
                        && mImpl.mInfo.getSupportsLaunchFromKeyguard();
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public void onLockscreenShown() {
            super.onLockscreenShown_enforcePermission();

            synchronized (this) {
                if (mImpl == null) {
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    if (mImpl.mActiveSession != null && mImpl.mActiveSession.mSession != null) {
                        try {
                            mImpl.mActiveSession.mSession.onLockscreenShown();
                        } catch (RemoteException e) {
                            Log.w(TAG, "Failed to call onLockscreenShown", e);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public void registerVoiceInteractionSessionListener(
                IVoiceInteractionSessionListener listener) {
            super.registerVoiceInteractionSessionListener_enforcePermission();

            synchronized (this) {
                mVoiceInteractionSessionListeners.register(listener);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
        @Override
        public void getActiveServiceSupportedActions(List<String> voiceActions,
                IVoiceActionCheckCallback callback) {
            super.getActiveServiceSupportedActions_enforcePermission();

            synchronized (this) {
                if (mImpl == null) {
                    try {
                        callback.onComplete(null);
                    } catch (RemoteException e) {
                    }
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.getActiveServiceSupportedActions(voiceActions, callback);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        public void onSessionShown() {
            synchronized (this) {
                final int size = mVoiceInteractionSessionListeners.beginBroadcast();
                for (int i = 0; i < size; ++i) {
                    final IVoiceInteractionSessionListener listener =
                            mVoiceInteractionSessionListeners.getBroadcastItem(i);
                    try {
                        listener.onVoiceSessionShown();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error delivering voice interaction open event.", e);
                    }
                }
                mVoiceInteractionSessionListeners.finishBroadcast();
            }
        }

        public void onSessionHidden() {
            synchronized (this) {
                final int size = mVoiceInteractionSessionListeners.beginBroadcast();
                for (int i = 0; i < size; ++i) {
                    final IVoiceInteractionSessionListener listener =
                            mVoiceInteractionSessionListeners.getBroadcastItem(i);
                    try {
                        listener.onVoiceSessionHidden();

                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error delivering voice interaction closed event.", e);
                    }
                }
                mVoiceInteractionSessionListeners.finishBroadcast();
            }
        }

        public void setSessionWindowVisible(IBinder token, boolean visible) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "setSessionWindowVisible called without running voice interaction "
                            + "service");
                    return;
                }
                if (mImpl.mActiveSession == null || token != mImpl.mActiveSession.mToken) {
                    Slog.w(TAG, "setSessionWindowVisible does not match active session");
                    return;
                }
                final long caller = Binder.clearCallingIdentity();
                try {
                    mVoiceInteractionSessionListeners.broadcast(listener -> {
                        try {
                            listener.onVoiceSessionWindowVisibilityChanged(visible);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Error delivering window visibility event to listener.", e);
                        }
                    });
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        public boolean getAccessibilityDetectionEnabled() {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "registerAccessibilityDetectionSettingsListener called without"
                            + " running voice interaction service");
                    return false;
                }
                return mImpl.getAccessibilityDetectionEnabled();
            }
        }

        @Override
        public void registerAccessibilityDetectionSettingsListener(
                IVoiceInteractionAccessibilitySettingsListener listener) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "registerAccessibilityDetectionSettingsListener called without"
                            + " running voice interaction service");
                    return;
                }
                mImpl.registerAccessibilityDetectionSettingsListenerLocked(listener);
            }
        }

        @Override
        public void unregisterAccessibilityDetectionSettingsListener(
                IVoiceInteractionAccessibilitySettingsListener listener) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "unregisterAccessibilityDetectionSettingsListener called "
                            + "without running voice interaction service");
                    return;
                }
                mImpl.unregisterAccessibilityDetectionSettingsListenerLocked(listener);
            }
        }


        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
            synchronized (this) {
                pw.println("VOICE INTERACTION MANAGER (dumpsys voiceinteraction)");
                pw.println("  mEnableService: " + mEnableService);
                pw.println("  mTemporarilyDisabled: " + mTemporarilyDisabled);
                pw.println("  mCurUser: " + mCurUser);
                pw.println("  mCurUserSupported: " + mCurUserSupported);
                pw.println("  mIsHdsRequired: " + IS_HDS_REQUIRED);
                dumpSupportedUsers(pw, "  ");
                mDbHelper.dump(pw);
                if (mImpl == null) {
                    pw.println("  (No active implementation)");
                } else {
                    mImpl.dumpLocked(fd, pw, args);
                }
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new VoiceInteractionManagerServiceShellCommand(mServiceStub)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        @Override
        public void setUiHints(Bundle hints) {
            synchronized (this) {
                enforceIsCurrentVoiceInteractionService();

                final int size = mVoiceInteractionSessionListeners.beginBroadcast();
                for (int i = 0; i < size; ++i) {
                    final IVoiceInteractionSessionListener listener =
                            mVoiceInteractionSessionListeners.getBroadcastItem(i);
                    try {
                        listener.onSetUiHints(hints);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error delivering UI hints.", e);
                    }
                }
                mVoiceInteractionSessionListeners.finishBroadcast();
            }
        }

        private boolean isCallerHoldingPermission(String permission) {
            return mContext.checkCallingOrSelfPermission(permission)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private void enforceCallingPermission(String permission) {
            if (!isCallerHoldingPermission(permission)) {
                throw new SecurityException("Caller does not hold the permission " + permission);
            }
        }

        private void enforceIsCurrentVoiceInteractionService() {
            if (!isCallerCurrentVoiceInteractionService()) {
                throw new
                    SecurityException("Caller is not the current voice interaction service");
            }
        }

        private void enforceIsCallerPreinstalledAssistant() {
            if (!isCallerPreinstalledAssistant()) {
                throw new
                        SecurityException("Caller is not the pre-installed assistant.");
            }
        }

        private void enforceCallerAllowedToEnrollVoiceModel() {
            if (isCallerHoldingPermission(Manifest.permission.KEYPHRASE_ENROLLMENT_APPLICATION)) {
                return;
            }

            enforceCallingPermission(Manifest.permission.MANAGE_VOICE_KEYPHRASES);
            enforceIsCurrentVoiceInteractionService();
        }

        private boolean isCallerCurrentVoiceInteractionService() {
            return mImpl != null
                    && mImpl.mInfo.getServiceInfo().applicationInfo.uid == Binder.getCallingUid();
        }

        private boolean isCallerPreinstalledAssistant() {
            return mImpl != null
                    && mImpl.getApplicationInfo().uid == Binder.getCallingUid()
                    && (mImpl.getApplicationInfo().isSystemApp()
                    || mImpl.getApplicationInfo().isUpdatedSystemApp());
        }

        private void setImplLocked(VoiceInteractionManagerServiceImpl impl) {
            mImpl = impl;
            mAtmInternal.notifyActiveVoiceInteractionServiceChanged(
                    getActiveServiceComponentName());
        }

        private IRecognitionStatusCallback createSoundTriggerCallbackLocked(
                IHotwordRecognitionStatusCallback callback,
                Identity voiceInteractorIdentity) {
            if (mImpl == null) {
                return null;
            }
            return mImpl.createSoundTriggerCallbackLocked(mContext, callback,
                    voiceInteractorIdentity);
        }

        class RoleObserver implements OnRoleHoldersChangedListener {
            private PackageManager mPm = mContext.getPackageManager();
            private RoleManager mRm = mContext.getSystemService(RoleManager.class);

            RoleObserver(@NonNull @CallbackExecutor Executor executor) {
                mRm.addOnRoleHoldersChangedListenerAsUser(executor, this, UserHandle.ALL);
                // Sync only if assistant role has been initialized.
                if (mRm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    UserHandle currentUser = UserHandle.of(LocalServices.getService(
                            ActivityManagerInternal.class).getCurrentUserId());
                    onRoleHoldersChanged(RoleManager.ROLE_ASSISTANT, currentUser);
                }
            }

            /**
             * Convert the assistant-role holder into settings. The rest of the system uses the
             * settings.
             *
             * @param roleName the name of the role whose holders are changed
             * @param user the user for this role holder change
             */
            @Override
            public void onRoleHoldersChanged(@NonNull String roleName, @NonNull UserHandle user) {
                if (!roleName.equals(RoleManager.ROLE_ASSISTANT)) {
                    return;
                }

                List<String> roleHolders = mRm.getRoleHoldersAsUser(roleName, user);

                if (DEBUG) {
                    Slogf.d(TAG, "onRoleHoldersChanged(%s, %s): roleHolders=%s", roleName, user,
                            roleHolders);
                }

                // TODO(b/226201975): this method is beling called when a pre-created user is added,
                // at which point it doesn't have any role holders. But it's not called again when
                // the actual user is added (i.e., when the  pre-created user is converted), so we
                // need to save the user id and call this method again when it's converted
                // (at onPreCreatedUserConversion()).
                // Once RoleService properly handles pre-created users, this workaround should be
                // removed.
                if (roleHolders.isEmpty()) {
                    UserInfo userInfo = mUserManagerInternal.getUserInfo(user.getIdentifier());
                    if (userInfo != null && userInfo.preCreated) {
                        Slogf.d(TAG, "onRoleHoldersChanged(): ignoring pre-created user %s for now,"
                                + " this method will be called again when it's converted to a real"
                                + " user", userInfo.toFullString());
                        return;
                    }
                }

                int userId = user.getIdentifier();
                if (roleHolders.isEmpty()) {
                    Settings.Secure.putStringForUser(getContext().getContentResolver(),
                            Settings.Secure.ASSISTANT, "", userId);
                    Settings.Secure.putStringForUser(getContext().getContentResolver(),
                            Settings.Secure.VOICE_INTERACTION_SERVICE, "", userId);
                } else {
                    // Assistant is singleton role
                    String pkg = roleHolders.get(0);

                    // Try to set role holder as VoiceInteractionService
                    for (ResolveInfo resolveInfo : queryInteractorServices(userId, pkg)) {
                        ServiceInfo serviceInfo = resolveInfo.serviceInfo;

                        VoiceInteractionServiceInfo voiceInteractionServiceInfo =
                                new VoiceInteractionServiceInfo(mPm, serviceInfo);
                        if (!voiceInteractionServiceInfo.getSupportsAssist()) {
                            continue;
                        }

                        String serviceComponentName = serviceInfo.getComponentName()
                                .flattenToShortString();
                        if (voiceInteractionServiceInfo.getRecognitionService() == null) {
                            Slog.e(TAG, "The RecognitionService must be set to avoid boot "
                                    + "loop on earlier platform version. Also make sure that this "
                                    + "is a valid RecognitionService when running on Android 11 "
                                    + "or earlier.");
                            serviceComponentName = "";
                        }

                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.ASSISTANT, serviceComponentName, userId);
                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.VOICE_INTERACTION_SERVICE, serviceComponentName,
                                userId);
                        return;
                    }

                    // If no service could be found try to set assist activity
                    final List<ResolveInfo> activities = mPm.queryIntentActivitiesAsUser(
                            new Intent(Intent.ACTION_ASSIST).setPackage(pkg),
                            PackageManager.MATCH_DEFAULT_ONLY
                                    | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);

                    for (ResolveInfo resolveInfo : activities) {
                        ActivityInfo activityInfo = resolveInfo.activityInfo;

                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.ASSISTANT,
                                activityInfo.getComponentName().flattenToShortString(), userId);
                        Settings.Secure.putStringForUser(getContext().getContentResolver(),
                                Settings.Secure.VOICE_INTERACTION_SERVICE, "", userId);
                        return;
                    }
                }
            }
        }

        class SettingsObserver extends ContentObserver {
            SettingsObserver(Handler handler) {
                super(handler);
                ContentResolver resolver = mContext.getContentResolver();
                resolver.registerContentObserver(Settings.Secure.getUriFor(
                        Settings.Secure.VOICE_INTERACTION_SERVICE), false, this,
                        UserHandle.USER_ALL);
            }

            @Override
            public void onChange(boolean selfChange) {
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    switchImplementationIfNeededLocked(false);
                }
            }
        }

        private void resetServicesIfNoRecognitionService(ComponentName serviceComponent,
                int userHandle) {
            for (ResolveInfo resolveInfo : queryInteractorServices(userHandle,
                    serviceComponent.getPackageName())) {
                VoiceInteractionServiceInfo serviceInfo =
                        new VoiceInteractionServiceInfo(
                                mContext.getPackageManager(),
                                resolveInfo.serviceInfo);
                if (!serviceInfo.getSupportsAssist()) {
                    continue;
                }
                if (serviceInfo.getRecognitionService() == null) {
                    Slog.e(TAG, "The RecognitionService must be set to "
                            + "avoid boot loop on earlier platform version. "
                            + "Also make sure that this is a valid "
                            + "RecognitionService when running on Android 11 "
                            + "or earlier.");
                    setCurInteractor(null, userHandle);
                    resetCurAssistant(userHandle);
                }
            }
        }

        PackageMonitor mPackageMonitor = new PackageMonitor(
                /* supportsPackageRestartQuery= */ true) {

            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages, int uid,
                    boolean doit) {
                if (DEBUG) Slog.d(TAG, "onHandleForceStop uid=" + uid + " doit=" + doit);

                int userHandle = UserHandle.getUserId(uid);
                ComponentName curInteractor = getCurInteractor(userHandle);
                ComponentName curRecognizer = getCurRecognizer(userHandle);
                boolean hitInt = false;
                boolean hitRec = false;
                for (String pkg : packages) {
                    if (curInteractor != null && pkg.equals(curInteractor.getPackageName())) {
                        hitInt = true;
                        break;
                    } else if (curRecognizer != null
                            && pkg.equals(curRecognizer.getPackageName())) {
                        hitRec = true;
                        break;
                    }
                }
                if (hitInt && doit) {
                    // The user is force stopping our current interactor, restart the service.
                    synchronized (VoiceInteractionManagerServiceStub.this) {
                        Slog.i(TAG, "Force stopping current voice interactor: "
                                + getCurInteractor(userHandle));
                        unloadAllKeyphraseModels();
                        if (mImpl != null) {
                            mImpl.shutdownLocked();
                            setImplLocked(null);
                        }
                        switchImplementationIfNeededLocked(true);
                    }
                } else if (hitRec && doit) {
                    // We are just force-stopping the current recognizer, which is not
                    // also the current interactor.
                    synchronized (VoiceInteractionManagerServiceStub.this) {
                        Slog.i(TAG, "Force stopping current voice recognizer: "
                                + getCurRecognizer(userHandle));
                        initRecognizer(userHandle);
                    }
                }
                return hitInt || hitRec;
            }

            @Override
            public void onPackageModified(@NonNull String pkgName) {
                // If the package modified is not in the current user, then don't bother making
                // any changes as we are going to do any initialization needed when we switch users.
                if (mCurUser != getChangingUserId()) {
                    return;
                }
                // Package getting updated will be handled by {@link #onSomePackagesChanged}.
                if (isPackageAppearing(pkgName) != PACKAGE_UNCHANGED) {
                    return;
                }
                if (getCurRecognizer(mCurUser) == null) {
                    initRecognizer(mCurUser);
                }
                final String curInteractorStr = Settings.Secure.getStringForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.VOICE_INTERACTION_SERVICE, mCurUser);
                final ComponentName curInteractor = getCurInteractor(mCurUser);
                // If there's no interactor and the user hasn't explicitly unset it, check if the
                // modified package offers one.
                if (curInteractor == null && !"".equals(curInteractorStr)) {
                    final VoiceInteractionServiceInfo availInteractorInfo
                            = findAvailInteractor(mCurUser, pkgName);
                    if (availInteractorInfo != null) {
                        final ComponentName availInteractor = new ComponentName(
                                availInteractorInfo.getServiceInfo().packageName,
                                availInteractorInfo.getServiceInfo().name);
                        setCurInteractor(availInteractor, mCurUser);
                    }
                } else {
                    if (didSomePackagesChange()) {
                        // Package is changed
                        if (curInteractor != null && pkgName.equals(
                                curInteractor.getPackageName())) {
                            switchImplementationIfNeeded(true);
                        }
                    } else {
                        // Only some components are changed
                        if (curInteractor != null
                                && isComponentModified(curInteractor.getClassName())) {
                            switchImplementationIfNeeded(true);
                        }
                    }
                }
            }

            @Override
            public void onSomePackagesChanged() {
                int userHandle = getChangingUserId();
                if (DEBUG) Slog.d(TAG, "onSomePackagesChanged user=" + userHandle);

                synchronized (VoiceInteractionManagerServiceStub.this) {
                    ComponentName curInteractor = getCurInteractor(userHandle);
                    ComponentName curRecognizer = getCurRecognizer(userHandle);
                    ComponentName curAssistant = getCurAssistant(userHandle);
                    if (curRecognizer == null) {
                        // Could a new recognizer appear when we don't have one pre-installed?
                        if (anyPackagesAppearing()) {
                            initRecognizer(userHandle);
                        }
                    }

                    if (curInteractor != null) {
                        int change = isPackageDisappearing(curInteractor.getPackageName());
                        if (change == PACKAGE_PERMANENT_CHANGE) {
                            // The currently set interactor is permanently gone; fall back to
                            // the default config.
                            setCurInteractor(null, userHandle);
                            setCurRecognizer(null, userHandle);
                            resetCurAssistant(userHandle);
                            initForUser(userHandle);
                            return;
                        }

                        change = isPackageAppearing(curInteractor.getPackageName());
                        if (change != PACKAGE_UNCHANGED) {
                            resetServicesIfNoRecognitionService(curInteractor, userHandle);
                            // If current interactor is now appearing, for any reason, then
                            // restart our connection with it.
                            if (mImpl != null && curInteractor.getPackageName().equals(
                                    mImpl.mComponent.getPackageName())) {
                                switchImplementationIfNeededLocked(true);
                            }
                        }
                        return;
                    }

                    if (curAssistant != null) {
                        int change = isPackageDisappearing(curAssistant.getPackageName());
                        if (change == PACKAGE_PERMANENT_CHANGE) {
                            // If the currently set assistant is being removed, then we should
                            // reset back to the default state (which is probably that we prefer
                            // to have the default full voice interactor enabled).
                            setCurInteractor(null, userHandle);
                            setCurRecognizer(null, userHandle);
                            resetCurAssistant(userHandle);
                            initForUser(userHandle);
                            return;
                        }
                        change = isPackageAppearing(curAssistant.getPackageName());
                        if (change != PACKAGE_UNCHANGED) {
                            // It is possible to update Assistant without a voice interactor to one
                            // with a voice-interactor. We should make sure the recognition service
                            // is set to avoid boot loop.
                            resetServicesIfNoRecognitionService(curAssistant, userHandle);
                        }
                    }

                    if (curRecognizer != null) {
                        int change = isPackageDisappearing(curRecognizer.getPackageName());
                        if (change == PACKAGE_PERMANENT_CHANGE
                                || change == PACKAGE_TEMPORARY_CHANGE) {
                            setCurRecognizer(findAvailRecognizer(null, userHandle), userHandle);

                        } else if (isPackageModified(curRecognizer.getPackageName())) {
                            setCurRecognizer(findAvailRecognizer(curRecognizer.getPackageName(),
                                    userHandle), userHandle);
                        }
                    }
                }
            }
        };

        private Intent getContextualSearchIntent(Bundle args) {
            String csPkgName = mContext.getResources()
                    .getString(R.string.config_defaultContextualSearchPackageName);
            if (csPkgName.isEmpty()) {
                // Return null if csPackageName is not specified.
                return null;
            }
            Intent launchIntent = new Intent(CS_INTENT_FILTER);
            launchIntent.setPackage(csPkgName);
            ResolveInfo resolveInfo = mContext.getPackageManager().resolveActivity(
                    launchIntent, PackageManager.MATCH_FACTORY_ONLY);
            if (resolveInfo == null) {
                return null;
            }
            launchIntent.setComponent(resolveInfo.getComponentInfo().getComponentName());
            launchIntent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION
                    | FLAG_ACTIVITY_NO_USER_ACTION | FLAG_ACTIVITY_CLEAR_TASK);
            launchIntent.putExtras(args);
            boolean isAssistDataAllowed = mAtmInternal.isAssistDataAllowed();
            final List<ActivityAssistInfo> records = mAtmInternal.getTopVisibleActivities();
            ArrayList<String> visiblePackageNames = new ArrayList<>();
            boolean isManagedProfileVisible = false;
            for (ActivityAssistInfo record: records) {
                // Add the package name to the list only if assist data is allowed.
                if (isAssistDataAllowed) {
                    visiblePackageNames.add(record.getComponentName().getPackageName());
                }
                if (mDpmInternal != null
                        && mDpmInternal.isUserOrganizationManaged(record.getUserId())) {
                    isManagedProfileVisible = true;
                }
            }
            final ScreenCapture.ScreenshotHardwareBuffer shb =
                    mWmInternal.takeAssistScreenshot(/* windowTypesToExclude= */ Set.of());
            final Bitmap bm = shb != null ? shb.asBitmap() : null;
            // Now that everything is fetched, putting it in the launchIntent.
            if (bm != null) {
                launchIntent.putExtra(CS_KEY_FLAG_SECURE_FOUND, shb.containsSecureLayers());
                // Only put the screenshot if assist data is allowed
                if (isAssistDataAllowed) {
                    launchIntent.putExtra(CS_KEY_FLAG_SCREENSHOT, bm.asShared());
                }
            }
            launchIntent.putExtra(CS_KEY_FLAG_IS_MANAGED_PROFILE_VISIBLE, isManagedProfileVisible);
            // Only put the list of visible package names if assist data is allowed
            if (isAssistDataAllowed) {
                launchIntent.putExtra(CS_KEY_FLAG_VISIBLE_PACKAGE_NAMES, visiblePackageNames);
            }

            return launchIntent;
        }

        @RequiresPermission(android.Manifest.permission.START_TASKS_FROM_RECENTS)
        private boolean startContextualSearch(Intent launchIntent) {
            // Contextual search starts with a frozen screen - so we launch without
            // any system animations or starting window.
            final ActivityOptions opts = ActivityOptions.makeCustomTaskAnimation(mContext,
                    /* enterResId= */ 0, /* exitResId= */ 0, null, null, null);
            opts.setDisableStartingWindow(true);
            int resultCode = mAtmInternal.startActivityWithScreenshot(launchIntent,
                    mContext.getPackageName(), Binder.getCallingUid(), Binder.getCallingPid(), null,
                    opts.toBundle(), Binder.getCallingUserHandle().getIdentifier());
            return resultCode == ActivityManager.START_SUCCESS;
        }

    }

    /**
     * End the latency tracking log for keyphrase hotword invocation.
     * The measurement covers from when the SoundTrigger HAL emits an event, captured in
     * {@link com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging}
     * to when the {@link android.service.voice.VoiceInteractionSession} system UI view is shown.
     */
    private final IVoiceInteractionSessionListener mLatencyLoggingListener =
            new IVoiceInteractionSessionListener.Stub() {
                @Override
                public void onVoiceSessionShown() throws RemoteException {}

                @Override
                public void onVoiceSessionHidden() throws RemoteException {}

                @Override
                public void onVoiceSessionWindowVisibilityChanged(boolean visible)
                        throws RemoteException {
                    if (visible) {
                        // The AlwaysOnHotwordDetector trigger latency is always completed here even
                        // if the reason the window was shown was not due to a SoundTrigger HAL
                        // event. It is expected that the latency will be canceled if shown for
                        // other invocation reasons, and this call becomes a noop.
                        HotwordMetricsLogger.stopHotwordTriggerToUiLatencySession(mContext);
                    }
                }

                @Override
                public void onSetUiHints(Bundle args) throws RemoteException {}

                @Override
                public IBinder asBinder() {
                    return mServiceStub;
                }
            };
}

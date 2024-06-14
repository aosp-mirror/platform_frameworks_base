/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.contextualsearch;

import static android.Manifest.permission.ACCESS_CONTEXTUAL_SEARCH;
import static android.app.AppOpsManager.OP_ASSIST_SCREENSHOT;
import static android.app.AppOpsManager.OP_ASSIST_STRUCTURE;
import static android.content.Context.CONTEXTUAL_SEARCH_SERVICE;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;
import static android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION;
import static android.content.pm.PackageManager.MATCH_FACTORY_ONLY;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_POINTER;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;

import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_CONTENT;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_STRUCTURE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.app.contextualsearch.CallbackToken;
import android.app.contextualsearch.ContextualSearchManager;
import android.app.contextualsearch.ContextualSearchState;
import android.app.contextualsearch.IContextualSearchCallback;
import android.app.contextualsearch.IContextualSearchManager;
import android.app.contextualsearch.flags.Flags;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelableException;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;
import android.view.IWindowManager;
import android.window.ScreenCapture;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.am.AssistDataRequester;
import com.android.server.am.AssistDataRequester.AssistDataRequesterCallbacks;
import com.android.server.wm.ActivityAssistInfo;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class ContextualSearchManagerService extends SystemService {
    private static final String TAG = ContextualSearchManagerService.class.getSimpleName();
    private static final int MSG_RESET_TEMPORARY_PACKAGE = 0;
    private static final int MAX_TEMP_PACKAGE_DURATION_MS = 1_000 * 60 * 2; // 2 minutes
    private static final int MSG_INVALIDATE_TOKEN = 1;
    private static final int MAX_TOKEN_VALID_DURATION_MS = 1_000 * 60 * 10; // 10 minutes

    private final Context mContext;
    private final ActivityTaskManagerInternal mAtmInternal;
    private final PackageManagerInternal mPackageManager;
    private final WindowManagerInternal mWmInternal;
    private final DevicePolicyManagerInternal mDpmInternal;
    private final Object mLock = new Object();
    private final AssistDataRequester mAssistDataRequester;

    private final AssistDataRequesterCallbacks mAssistDataCallbacks =
            new AssistDataRequesterCallbacks() {
                @Override
                public boolean canHandleReceivedAssistDataLocked() {
                    synchronized (mLock) {
                        return mStateCallback != null;
                    }
                }

                @Override
                public void onAssistDataReceivedLocked(
                        final Bundle data,
                        final int activityIndex,
                        final int activityCount) {
                    final IContextualSearchCallback callback;
                    synchronized (mLock) {
                        callback = mStateCallback;
                    }

                    if (callback != null) {
                        try {
                            callback.onResult(new ContextualSearchState(
                                    data.getParcelable(ASSIST_KEY_STRUCTURE, AssistStructure.class),
                                    data.getParcelable(ASSIST_KEY_CONTENT, AssistContent.class),
                                    data));
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error invoking ContextualSearchCallback", e);
                        }
                    } else {
                        Log.w(TAG, "Callback went away!");
                    }
                }

                @Override
                public void onAssistRequestCompleted() {
                    synchronized (mLock) {
                        mStateCallback = null;
                    }
                }
            };

    @GuardedBy("this")
    private Handler mTemporaryHandler;
    @GuardedBy("this")
    private String mTemporaryPackage = null;
    @GuardedBy("this")
    private long mTokenValidDurationMs = MAX_TOKEN_VALID_DURATION_MS;

    @GuardedBy("mLock")
    private IContextualSearchCallback mStateCallback;

    public ContextualSearchManagerService(@NonNull Context context) {
        super(context);
        if (DEBUG_USER) Log.d(TAG, "ContextualSearchManagerService created");
        mContext = context;
        mAtmInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityTaskManagerInternal.class));
        mPackageManager = LocalServices.getService(PackageManagerInternal.class);
        mWmInternal = Objects.requireNonNull(LocalServices.getService(WindowManagerInternal.class));
        mDpmInternal = LocalServices.getService(DevicePolicyManagerInternal.class);
        mAssistDataRequester = new AssistDataRequester(
                mContext,
                IWindowManager.Stub.asInterface(ServiceManager.getService(Context.WINDOW_SERVICE)),
                mContext.getSystemService(AppOpsManager.class),
                mAssistDataCallbacks, mLock, OP_ASSIST_STRUCTURE, OP_ASSIST_SCREENSHOT);

        updateSecureSetting();
    }

    @Override
    public void onStart() {
        publishBinderService(CONTEXTUAL_SEARCH_SERVICE, new ContextualSearchManagerStub());
    }

    private void updateSecureSetting() {
        // Write default package to secure setting every time there is a change. If OEM didn't
        // supply a new value in their config, then we would write empty string.
        Settings.Secure.putString(
            mContext.getContentResolver(),
            Settings.Secure.CONTEXTUAL_SEARCH_PACKAGE,
            getContextualSearchPackageName());
    }

    private String getContextualSearchPackageName() {
      synchronized (this) {
         return mTemporaryPackage != null ? mTemporaryPackage : mContext
                .getResources().getString(R.string.config_defaultContextualSearchPackageName);
      }
    }

    void resetTemporaryPackage() {
        synchronized (this) {
            enforceOverridingPermission("resetTemporaryPackage");
            if (mTemporaryHandler != null) {
                mTemporaryHandler.removeMessages(MSG_RESET_TEMPORARY_PACKAGE);
                mTemporaryHandler = null;
            }
            if (DEBUG_USER) Log.d(TAG, "mTemporaryPackage reset.");
            mTemporaryPackage = null;
            updateSecureSetting();
        }
    }

    void setTemporaryPackage(@NonNull String temporaryPackage, int durationMs) {
        synchronized (this) {
            enforceOverridingPermission("setTemporaryPackage");
            final int maxDurationMs = MAX_TEMP_PACKAGE_DURATION_MS;
            if (durationMs > maxDurationMs) {
                throw new IllegalArgumentException(
                        "Max duration is " + maxDurationMs + " (called with " + durationMs + ")");
            }
            if (mTemporaryHandler == null) {
                mTemporaryHandler = new Handler(Looper.getMainLooper(), null, true) {
                    @Override
                    public void handleMessage(Message msg) {
                        if (msg.what == MSG_RESET_TEMPORARY_PACKAGE) {
                            synchronized (this) {
                                resetTemporaryPackage();
                            }
                        } else {
                            Slog.wtf(TAG, "invalid handler msg: " + msg);
                        }
                    }
                };
            } else {
                mTemporaryHandler.removeMessages(MSG_RESET_TEMPORARY_PACKAGE);
            }
            mTemporaryPackage = temporaryPackage;
            updateSecureSetting();
            mTemporaryHandler.sendEmptyMessageDelayed(MSG_RESET_TEMPORARY_PACKAGE, durationMs);
            if (DEBUG_USER) Log.d(TAG, "mTemporaryPackage set to " + mTemporaryPackage);
        }
    }

    void resetTokenValidDurationMs() {
        setTokenValidDurationMs(MAX_TOKEN_VALID_DURATION_MS);
    }

    void setTokenValidDurationMs(int durationMs) {
        synchronized (this) {
            enforceOverridingPermission("setTokenValidDurationMs");
            if (durationMs > MAX_TOKEN_VALID_DURATION_MS) {
                throw new IllegalArgumentException(
                        "Token max duration is " + MAX_TOKEN_VALID_DURATION_MS + " (called with "
                                + durationMs + ")");
            }
            mTokenValidDurationMs = durationMs;
            if (DEBUG_USER) Log.d(TAG, "mTokenValidDurationMs set to " + durationMs);
        }
    }

    private long getTokenValidDurationMs() {
        synchronized (this) {
            return mTokenValidDurationMs;
        }
    }

    private Intent getResolvedLaunchIntent() {
        synchronized (this) {
            // If mTemporaryPackage is not null, use it to get the ContextualSearch intent.
            String csPkgName = getContextualSearchPackageName();
            if (csPkgName.isEmpty()) {
                // Return null if csPackageName is not specified.
                return null;
            }
            Intent launchIntent = new Intent(
                    ContextualSearchManager.ACTION_LAUNCH_CONTEXTUAL_SEARCH);
            launchIntent.setPackage(csPkgName);
            ResolveInfo resolveInfo = mContext.getPackageManager().resolveActivity(
                    launchIntent, MATCH_FACTORY_ONLY);
            if (resolveInfo == null) {
                return null;
            }
            ComponentName componentName = resolveInfo.getComponentInfo().getComponentName();
            if (componentName == null) {
                return null;
            }
            launchIntent.setComponent(componentName);
            return launchIntent;
        }
    }

    private Intent getContextualSearchIntent(int entrypoint, CallbackToken mToken) {
        final Intent launchIntent = getResolvedLaunchIntent();
        if (launchIntent == null) {
            return null;
        }

        if (DEBUG_USER) Log.d(TAG, "Launch component: " + launchIntent.getComponent());
        launchIntent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION
                | FLAG_ACTIVITY_NO_USER_ACTION | FLAG_ACTIVITY_CLEAR_TASK);
        launchIntent.putExtra(
                ContextualSearchManager.EXTRA_INVOCATION_TIME_MS,
                SystemClock.uptimeMillis());
        launchIntent.putExtra(ContextualSearchManager.EXTRA_ENTRYPOINT, entrypoint);
        launchIntent.putExtra(ContextualSearchManager.EXTRA_TOKEN, mToken);
        boolean isAssistDataAllowed = mAtmInternal.isAssistDataAllowed();
        final List<ActivityAssistInfo> records = mAtmInternal.getTopVisibleActivities();
        final List<IBinder> activityTokens = new ArrayList<>(records.size());
        ArrayList<String> visiblePackageNames = new ArrayList<>();
        boolean isManagedProfileVisible = false;
        for (ActivityAssistInfo record : records) {
            // Add the package name to the list only if assist data is allowed.
            if (isAssistDataAllowed) {
                visiblePackageNames.add(record.getComponentName().getPackageName());
                activityTokens.add(record.getActivityToken());
            }
            if (mDpmInternal != null
                    && mDpmInternal.isUserOrganizationManaged(record.getUserId())) {
                isManagedProfileVisible = true;
            }
        }
        if (isAssistDataAllowed) {
            try {
                final String csPackage = Objects.requireNonNull(launchIntent.getPackage());
                final int csUid = mPackageManager.getPackageUid(csPackage, 0, 0);
                mAssistDataRequester.requestAssistData(
                        activityTokens,
                        /* fetchData */ true,
                        /* fetchScreenshot */ false,
                        /* allowFetchData */ true,
                        /* allowFetchScreenshot */ false,
                        csUid,
                        csPackage,
                        null);
            } catch (Exception e) {
                Log.e(TAG, "Could not request assist data", e);
            }
        }
        final ScreenCapture.ScreenshotHardwareBuffer shb;
        if (mWmInternal != null) {
            shb = mWmInternal.takeAssistScreenshot(Set.of(
                    TYPE_STATUS_BAR,
                    TYPE_NAVIGATION_BAR,
                    TYPE_NAVIGATION_BAR_PANEL,
                    TYPE_POINTER));
        } else {
            shb = null;
        }
        final Bitmap bm = shb != null ? shb.asBitmap() : null;
        // Now that everything is fetched, putting it in the launchIntent.
        if (bm != null) {
            launchIntent.putExtra(ContextualSearchManager.EXTRA_FLAG_SECURE_FOUND,
                    shb.containsSecureLayers());
            // Only put the screenshot if assist data is allowed
            if (isAssistDataAllowed) {
                launchIntent.putExtra(ContextualSearchManager.EXTRA_SCREENSHOT, bm.asShared());
            }
        }
        launchIntent.putExtra(ContextualSearchManager.EXTRA_IS_MANAGED_PROFILE_VISIBLE,
                isManagedProfileVisible);
        // Only put the list of visible package names if assist data is allowed
        if (isAssistDataAllowed) {
            launchIntent.putExtra(ContextualSearchManager.EXTRA_VISIBLE_PACKAGE_NAMES,
                    visiblePackageNames);
        }
        return launchIntent;
    }

    @RequiresPermission(android.Manifest.permission.START_TASKS_FROM_RECENTS)
    private int invokeContextualSearchIntent(Intent launchIntent) {
        // Contextual search starts with a frozen screen - so we launch without
        // any system animations or starting window.
        final ActivityOptions opts = ActivityOptions.makeCustomTaskAnimation(mContext,
                /* enterResId= */ 0, /* exitResId= */ 0, null, null, null);
        opts.setDisableStartingWindow(true);
        return mAtmInternal.startActivityWithScreenshot(launchIntent,
                mContext.getPackageName(), Binder.getCallingUid(), Binder.getCallingPid(), null,
                opts.toBundle(), Binder.getCallingUserHandle().getIdentifier());
    }

    private void enforcePermission(@NonNull final String func) {
        Context ctx = getContext();
        if (!(ctx.checkCallingPermission(ACCESS_CONTEXTUAL_SEARCH) == PERMISSION_GRANTED
                || isCallerTemporary())) {
            String msg = "Permission Denial: Cannot call " + func + " from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid();
            throw new SecurityException(msg);
        }
    }

    private void enforceOverridingPermission(@NonNull final String func) {
        if (!(Binder.getCallingUid() == Process.SHELL_UID
                || Binder.getCallingUid() == Process.ROOT_UID
                || Binder.getCallingUid() == Process.SYSTEM_UID)) {
            String msg = "Permission Denial: Cannot override Contextual Search. Called " + func
                    + " from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid();
            throw new SecurityException(msg);
        }
    }

    private boolean isCallerTemporary() {
        synchronized (this) {
            return mTemporaryPackage != null
                    && mTemporaryPackage.equals(
                    getContext().getPackageManager().getNameForUid(Binder.getCallingUid()));
        }
    }

    private class ContextualSearchManagerStub extends IContextualSearchManager.Stub {
        @GuardedBy("this")
        private Handler mTokenHandler;
        private @Nullable CallbackToken mToken;

        private void invalidateToken() {
            synchronized (this) {
                if (mTokenHandler != null) {
                    mTokenHandler.removeMessages(MSG_INVALIDATE_TOKEN);
                    mTokenHandler = null;
                }
                if (DEBUG_USER) Log.d(TAG, "mToken invalidated.");
                mToken = null;
            }
        }

        private void issueToken() {
            synchronized (this) {
                mToken = new CallbackToken();
                if (mTokenHandler == null) {
                    mTokenHandler = new Handler(Looper.getMainLooper(), null, true) {
                        @Override
                        public void handleMessage(Message msg) {
                            if (msg.what == MSG_INVALIDATE_TOKEN) {
                                invalidateToken();
                            } else {
                                Slog.wtf(TAG, "invalid token handler msg: " + msg);
                            }
                        }
                    };
                } else {
                    mTokenHandler.removeMessages(MSG_INVALIDATE_TOKEN);
                }
                mTokenHandler.sendEmptyMessageDelayed(
                        MSG_INVALIDATE_TOKEN, getTokenValidDurationMs());
            }
        }

        @Override
        public void startContextualSearch(int entrypoint) {
            synchronized (this) {
                if (DEBUG_USER) Log.d(TAG, "startContextualSearch");
                enforcePermission("startContextualSearch");
                mAssistDataRequester.cancel();
                // Creates a new CallbackToken at mToken and an expiration handler.
                issueToken();
                // We get the launch intent with the system server's identity because the system
                // server has READ_FRAME_BUFFER permission to get the screenshot and because only
                // the system server can invoke non-exported activities.
                Binder.withCleanCallingIdentity(() -> {
                    Intent launchIntent = getContextualSearchIntent(entrypoint, mToken);
                    if (launchIntent != null) {
                        int result = invokeContextualSearchIntent(launchIntent);
                        if (DEBUG_USER) Log.d(TAG, "Launch result: " + result);
                    }
                });
            }
        }

        @Override
        public void getContextualSearchState(
                @NonNull IBinder token,
                @NonNull IContextualSearchCallback callback) {
            if (DEBUG_USER) {
                Log.i(TAG, "getContextualSearchState token: " + token + ", callback: " + callback);
            }
            if (mToken == null || !mToken.getToken().equals(token)) {
                if (DEBUG_USER) {
                    Log.e(TAG, "getContextualSearchState: invalid token, returning error");
                }
                try {
                    callback.onError(
                            new ParcelableException(new IllegalArgumentException("Invalid token")));
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not invoke onError callback", e);
                }
                return;
            }
            invalidateToken();
            if (Flags.enableTokenRefresh()) {
                issueToken();
                Bundle bundle = new Bundle();
                bundle.putParcelable(ContextualSearchManager.EXTRA_TOKEN, mToken);
                // We get take the screenshot with the system server's identity because the system
                // server has READ_FRAME_BUFFER permission to get the screenshot.
                Binder.withCleanCallingIdentity(() -> {
                    if (mWmInternal != null) {
                        bundle.putParcelable(ContextualSearchManager.EXTRA_SCREENSHOT,
                                mWmInternal.takeAssistScreenshot(Set.of(
                                        TYPE_STATUS_BAR,
                                        TYPE_NAVIGATION_BAR,
                                        TYPE_NAVIGATION_BAR_PANEL,
                                        TYPE_POINTER))
                                .asBitmap().asShared());
                    }
                    try {
                        callback.onResult(
                            new ContextualSearchState(null, null, bundle));
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error invoking ContextualSearchCallback", e);
                    }
                });
            }
            synchronized (mLock) {
                mStateCallback = callback;
            }
            mAssistDataRequester.processPendingAssistData();
        }

        public void onShellCommand(
                @Nullable FileDescriptor in,
                @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args,
                @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) {
            new ContextualSearchManagerShellCommand(ContextualSearchManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }
    }
}

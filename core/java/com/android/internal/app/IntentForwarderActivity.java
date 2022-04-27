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

package com.android.internal.app;

import static android.app.admin.DevicePolicyResources.Strings.Core.FORWARD_INTENT_TO_PERSONAL;
import static android.app.admin.DevicePolicyResources.Strings.Core.FORWARD_INTENT_TO_WORK;
import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;

import static com.android.internal.app.ResolverActivity.EXTRA_CALLING_USER;
import static com.android.internal.app.ResolverActivity.EXTRA_SELECTED_PROFILE;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.admin.DevicePolicyManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.metrics.LogMaker;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This is used in conjunction with
 * {@link DevicePolicyManager#addCrossProfileIntentFilter} to enable intents to
 * be passed in and out of a managed profile.
 */
public class IntentForwarderActivity extends Activity  {
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static String TAG = "IntentForwarderActivity";

    public static String FORWARD_INTENT_TO_PARENT
            = "com.android.internal.app.ForwardIntentToParent";

    public static String FORWARD_INTENT_TO_MANAGED_PROFILE
            = "com.android.internal.app.ForwardIntentToManagedProfile";

    private static final Set<String> ALLOWED_TEXT_MESSAGE_SCHEMES
            = new HashSet<>(Arrays.asList("sms", "smsto", "mms", "mmsto"));

    private static final String TEL_SCHEME = "tel";

    private static final ComponentName RESOLVER_COMPONENT_NAME =
            new ComponentName("android", ResolverActivity.class.getName());

    private Injector mInjector;

    private MetricsLogger mMetricsLogger;
    protected ExecutorService mExecutorService;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutorService.shutdown();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInjector = createInjector();
        mExecutorService = Executors.newSingleThreadExecutor();

        Intent intentReceived = getIntent();
        String className = intentReceived.getComponent().getClassName();
        final int targetUserId;
        final String userMessage;
        if (className.equals(FORWARD_INTENT_TO_PARENT)) {
            userMessage = getForwardToPersonalMessage();
            targetUserId = getProfileParent();

            getMetricsLogger().write(
                    new LogMaker(MetricsEvent.ACTION_SWITCH_SHARE_PROFILE)
                    .setSubtype(MetricsEvent.PARENT_PROFILE));
        } else if (className.equals(FORWARD_INTENT_TO_MANAGED_PROFILE)) {
            userMessage = getForwardToWorkMessage();
            targetUserId = getManagedProfile();

            getMetricsLogger().write(
                    new LogMaker(MetricsEvent.ACTION_SWITCH_SHARE_PROFILE)
                    .setSubtype(MetricsEvent.MANAGED_PROFILE));
        } else {
            Slog.wtf(TAG, IntentForwarderActivity.class.getName() + " cannot be called directly");
            userMessage = null;
            targetUserId = UserHandle.USER_NULL;
        }
        if (targetUserId == UserHandle.USER_NULL) {
            // This covers the case where there is no parent / managed profile.
            finish();
            return;
        }
        if (Intent.ACTION_CHOOSER.equals(intentReceived.getAction())) {
            launchChooserActivityWithCorrectTab(intentReceived, className);
            return;
        }

        final int callingUserId = getUserId();
        final Intent newIntent = canForward(intentReceived, getUserId(), targetUserId,
                mInjector.getIPackageManager(), getContentResolver());

        if (newIntent == null) {
            Slog.wtf(TAG, "the intent: " + intentReceived + " cannot be forwarded from user "
                    + callingUserId + " to user " + targetUserId);
            finish();
            return;
        }

        newIntent.prepareToLeaveUser(callingUserId);
        final CompletableFuture<ResolveInfo> targetResolveInfoFuture =
                mInjector.resolveActivityAsUser(newIntent, MATCH_DEFAULT_ONLY, targetUserId);
        targetResolveInfoFuture
                .thenApplyAsync(targetResolveInfo -> {
                    if (isResolverActivityResolveInfo(targetResolveInfo)) {
                        launchResolverActivityWithCorrectTab(intentReceived, className, newIntent,
                                callingUserId, targetUserId);
                        return targetResolveInfo;
                    }
                    startActivityAsCaller(newIntent, targetUserId);
                    return targetResolveInfo;
                }, mExecutorService)
                .thenAcceptAsync(result -> {
                    maybeShowDisclosure(intentReceived, result, userMessage);
                    finish();
                }, getApplicationContext().getMainExecutor());
    }

    private String getForwardToPersonalMessage() {
        return getSystemService(DevicePolicyManager.class).getResources().getString(
                FORWARD_INTENT_TO_PERSONAL,
                () -> getString(com.android.internal.R.string.forward_intent_to_owner));
    }

    private String getForwardToWorkMessage() {
        return getSystemService(DevicePolicyManager.class).getResources().getString(
                FORWARD_INTENT_TO_WORK,
                () -> getString(com.android.internal.R.string.forward_intent_to_work));
    }

    private boolean isIntentForwarderResolveInfo(ResolveInfo resolveInfo) {
        if (resolveInfo == null) {
            return false;
        }
        ActivityInfo activityInfo = resolveInfo.activityInfo;
        if (activityInfo == null) {
            return false;
        }
        if (!"android".equals(activityInfo.packageName)) {
            return false;
        }
        return activityInfo.name.equals(FORWARD_INTENT_TO_PARENT)
                || activityInfo.name.equals(FORWARD_INTENT_TO_MANAGED_PROFILE);
    }

    private boolean isResolverActivityResolveInfo(@Nullable ResolveInfo resolveInfo) {
        return resolveInfo != null
                && resolveInfo.activityInfo != null
                && RESOLVER_COMPONENT_NAME.equals(resolveInfo.activityInfo.getComponentName());
    }

    private void maybeShowDisclosure(
            Intent intentReceived, ResolveInfo resolveInfo, @Nullable String message) {
        if (shouldShowDisclosure(resolveInfo, intentReceived) && message != null) {
            mInjector.showToast(message, Toast.LENGTH_LONG);
        }
    }

    private void startActivityAsCaller(Intent newIntent, int userId) {
        try {
            startActivityAsCaller(
                    newIntent,
                    /* options= */ null,
                    /* ignoreTargetSecurity= */ false,
                    userId);
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Unable to launch as UID " + getLaunchedFromUid() + " package "
                    + getLaunchedFromPackage() + ", while running in "
                    + ActivityThread.currentProcessName(), e);
        }
    }

    private void launchChooserActivityWithCorrectTab(Intent intentReceived, String className) {
        // When showing the sharesheet, instead of forwarding to the other profile,
        // we launch the sharesheet in the current user and select the other tab.
        // This fixes b/152866292 where the user can not go back to the original profile
        // when cross-profile intents are disabled.
        int selectedProfile = findSelectedProfile(className);
        sanitizeIntent(intentReceived);
        intentReceived.putExtra(EXTRA_SELECTED_PROFILE, selectedProfile);
        Intent innerIntent = intentReceived.getParcelableExtra(Intent.EXTRA_INTENT);
        if (innerIntent == null) {
            Slog.wtf(TAG, "Cannot start a chooser intent with no extra " + Intent.EXTRA_INTENT);
            return;
        }
        sanitizeIntent(innerIntent);
        startActivityAsCaller(intentReceived, null, false, getUserId());
        finish();
    }

    private void launchResolverActivityWithCorrectTab(Intent intentReceived, String className,
            Intent newIntent, int callingUserId, int targetUserId) {
        // When showing the intent resolver, instead of forwarding to the other profile,
        // we launch it in the current user and select the other tab. This fixes b/155874820.
        //
        // In the case when there are 0 targets in the current profile and >1 apps in the other
        // profile, the package manager launches the intent resolver in the other profile.
        // If that's the case, we launch the resolver in the target user instead (other profile).
        ResolveInfo callingResolveInfo = mInjector.resolveActivityAsUser(
                newIntent, MATCH_DEFAULT_ONLY, callingUserId).join();
        int userId = isIntentForwarderResolveInfo(callingResolveInfo)
                ? targetUserId : callingUserId;
        int selectedProfile = findSelectedProfile(className);
        sanitizeIntent(intentReceived);
        intentReceived.putExtra(EXTRA_SELECTED_PROFILE, selectedProfile);
        intentReceived.putExtra(EXTRA_CALLING_USER, UserHandle.of(callingUserId));
        startActivityAsCaller(intentReceived, null, false, userId);
        finish();
    }

    private int findSelectedProfile(String className) {
        if (className.equals(FORWARD_INTENT_TO_PARENT)) {
            return ChooserActivity.PROFILE_PERSONAL;
        } else if (className.equals(FORWARD_INTENT_TO_MANAGED_PROFILE)) {
            return ChooserActivity.PROFILE_WORK;
        }
        return -1;
    }

    private boolean shouldShowDisclosure(@Nullable ResolveInfo ri, Intent intent) {
        if (!isDeviceProvisioned()) {
            return false;
        }
        if (ri == null || ri.activityInfo == null) {
            return true;
        }
        if (ri.activityInfo.applicationInfo.isSystemApp()
                && (isDialerIntent(intent) || isTextMessageIntent(intent))) {
            return false;
        }
        return !isTargetResolverOrChooserActivity(ri.activityInfo);
    }

    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, /* def= */ 0) != 0;
    }

    private boolean isTextMessageIntent(Intent intent) {
        return (Intent.ACTION_SENDTO.equals(intent.getAction()) || isViewActionIntent(intent))
                && ALLOWED_TEXT_MESSAGE_SCHEMES.contains(intent.getScheme());
    }

    private boolean isDialerIntent(Intent intent) {
        return Intent.ACTION_DIAL.equals(intent.getAction())
                || Intent.ACTION_CALL.equals(intent.getAction())
                || Intent.ACTION_CALL_PRIVILEGED.equals(intent.getAction())
                || Intent.ACTION_CALL_EMERGENCY.equals(intent.getAction())
                || (isViewActionIntent(intent) && TEL_SCHEME.equals(intent.getScheme()));
    }

    private boolean isViewActionIntent(Intent intent) {
        return Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_BROWSABLE);
    }

    private boolean isTargetResolverOrChooserActivity(ActivityInfo activityInfo) {
        if (!"android".equals(activityInfo.packageName)) {
            return false;
        }
        return ResolverActivity.class.getName().equals(activityInfo.name)
            || ChooserActivity.class.getName().equals(activityInfo.name);
    }

    /**
     * Check whether the intent can be forwarded to target user. Return the intent used for
     * forwarding if it can be forwarded, {@code null} otherwise.
     */
    static Intent canForward(Intent incomingIntent, int sourceUserId, int targetUserId,
            IPackageManager packageManager, ContentResolver contentResolver)  {
        Intent forwardIntent = new Intent(incomingIntent);
        forwardIntent.addFlags(
                Intent.FLAG_ACTIVITY_FORWARD_RESULT | Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
        sanitizeIntent(forwardIntent);

        Intent intentToCheck = forwardIntent;
        if (Intent.ACTION_CHOOSER.equals(forwardIntent.getAction())) {
            return null;
        }
        if (forwardIntent.getSelector() != null) {
            intentToCheck = forwardIntent.getSelector();
        }
        String resolvedType = intentToCheck.resolveTypeIfNeeded(contentResolver);
        sanitizeIntent(intentToCheck);
        try {
            if (packageManager.canForwardTo(
                    intentToCheck, resolvedType, sourceUserId, targetUserId)) {
                return forwardIntent;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "PackageManagerService is dead?");
        }
        return null;
    }

    /**
     * Returns the userId of the managed profile for this device or UserHandle.USER_NULL if there is
     * no managed profile.
     *
     * TODO: Remove the assumption that there is only one managed profile
     * on the device.
     */
    private int getManagedProfile() {
        List<UserInfo> relatedUsers = mInjector.getUserManager().getProfiles(UserHandle.myUserId());
        for (UserInfo userInfo : relatedUsers) {
            if (userInfo.isManagedProfile()) return userInfo.id;
        }
        Slog.wtf(TAG, FORWARD_INTENT_TO_MANAGED_PROFILE
                + " has been called, but there is no managed profile");
        return UserHandle.USER_NULL;
    }

    /**
     * Returns the userId of the profile parent or UserHandle.USER_NULL if there is
     * no parent.
     */
    private int getProfileParent() {
        UserInfo parent = mInjector.getUserManager().getProfileParent(UserHandle.myUserId());
        if (parent == null) {
            Slog.wtf(TAG, FORWARD_INTENT_TO_PARENT
                    + " has been called, but there is no parent");
            return UserHandle.USER_NULL;
        }
        return parent.id;
    }

    /**
     * Sanitize the intent in place.
     */
    private static void sanitizeIntent(Intent intent) {
        // Apps should not be allowed to target a specific package/ component in the target user.
        intent.setPackage(null);
        intent.setComponent(null);
    }

    protected MetricsLogger getMetricsLogger() {
        if (mMetricsLogger == null) {
            mMetricsLogger = new MetricsLogger();
        }
        return mMetricsLogger;
    }

    @VisibleForTesting
    protected Injector createInjector() {
        return new InjectorImpl();
    }

    private class InjectorImpl implements Injector {

        @Override
        public IPackageManager getIPackageManager() {
            return AppGlobals.getPackageManager();
        }

        @Override
        public UserManager getUserManager() {
            return getSystemService(UserManager.class);
        }

        @Override
        public PackageManager getPackageManager() {
            return IntentForwarderActivity.this.getPackageManager();
        }

        @Override
        @Nullable
        public CompletableFuture<ResolveInfo> resolveActivityAsUser(
                Intent intent, int flags, int userId) {
            return CompletableFuture.supplyAsync(
                    () -> getPackageManager().resolveActivityAsUser(intent, flags, userId));
        }

        @Override
        public void showToast(String message, int duration) {
            Toast.makeText(IntentForwarderActivity.this, message, duration).show();
        }
    }

    public interface Injector {
        IPackageManager getIPackageManager();

        UserManager getUserManager();

        PackageManager getPackageManager();

        CompletableFuture<ResolveInfo> resolveActivityAsUser(Intent intent, int flags, int userId);

        void showToast(String message, int duration);
    }
}

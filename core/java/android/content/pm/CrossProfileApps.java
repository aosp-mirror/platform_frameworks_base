/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.content.pm;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.app.admin.DevicePolicyResources.Strings.Core.SWITCH_TO_PERSONAL_LABEL;
import static android.app.admin.DevicePolicyResources.Strings.Core.SWITCH_TO_WORK_LABEL;
import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;
import static android.app.admin.flags.Flags.FLAG_ALLOW_QUERYING_PROFILE_TYPE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AppOpsManager.Mode;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.internal.R;
import com.android.internal.util.UserIcons;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Class for handling cross profile operations. Apps can use this class to interact with its
 * instance in any profile that is in {@link #getTargetUserProfiles()}. For example, app can
 * use this class to start its main activity in managed profile.
 */
public class CrossProfileApps {

    /**
     * Broadcast signalling that the receiving app's permission to interact across profiles has
     * changed. This includes the user, admin, or OEM changing their consent such that the
     * permission for the app to interact across profiles has changed.
     *
     * <p>This broadcast is not sent when other circumstances result in a change to being able to
     * interact across profiles in practice, such as the profile being turned off or removed, apps
     * being uninstalled, etc. The methods {@link #canInteractAcrossProfiles()} and {@link
     * #canRequestInteractAcrossProfiles()} can be used by apps prior to attempting to interact
     * across profiles or attempting to request user consent to interact across profiles.
     *
     * <p>Apps that have set the {@code android:crossProfile} manifest attribute to {@code true}
     * can receive this broadcast in manifest broadcast receivers. Otherwise, it can only be
     * received by dynamically-registered broadcast receivers.
     */
    public static final String ACTION_CAN_INTERACT_ACROSS_PROFILES_CHANGED =
            "android.content.pm.action.CAN_INTERACT_ACROSS_PROFILES_CHANGED";

    private final Context mContext;
    private final ICrossProfileApps mService;
    private final UserManager mUserManager;
    private final Resources mResources;

    /** @hide */
    public CrossProfileApps(Context context, ICrossProfileApps service) {
        mContext = context;
        mService = service;
        mUserManager = context.getSystemService(UserManager.class);
        mResources = context.getResources();
    }

    /**
     * Starts the specified main activity of the caller package in the specified profile.
     *
     * @param component The ComponentName of the activity to launch, it must be exported and has
     *        action {@link android.content.Intent#ACTION_MAIN}, category
     *        {@link android.content.Intent#CATEGORY_LAUNCHER}. Otherwise, SecurityException will
     *        be thrown.
     * @param targetUser The UserHandle of the profile, must be one of the users returned by
     *        {@link #getTargetUserProfiles()}, otherwise a {@link SecurityException} will
     *        be thrown.
     */
    public void startMainActivity(@NonNull ComponentName component,
            @NonNull UserHandle targetUser) {
        try {
            mService.startActivityAsUser(
                    mContext.getIApplicationThread(),
                    mContext.getPackageName(),
                    mContext.getAttributionTag(),
                    component,
                    targetUser.getIdentifier(),
                    true,
                    mContext.getActivityToken(),
                    ActivityOptions.makeBasic().toBundle());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Starts the specified main activity of the caller package in the specified profile, launching
     * in the specified activity.
     *
     * @param component The ComponentName of the activity to launch, it must be exported and has
     *        action {@link android.content.Intent#ACTION_MAIN}, category
     *        {@link android.content.Intent#CATEGORY_LAUNCHER}. Otherwise, SecurityException will
     *        be thrown.
     * @param targetUser The UserHandle of the profile, must be one of the users returned by
     *        {@link #getTargetUserProfiles()}, otherwise a {@link SecurityException} will
     *        be thrown.
     * @param callingActivity The activity to start the new activity from for the purposes of
     *        deciding which task the new activity should belong to. If {@code null}, the activity
     *        will always be started in a new task.
     * @param options The activity options or {@code null}. See {@link android.app.ActivityOptions}.
     */
    public void startMainActivity(@NonNull ComponentName component,
            @NonNull UserHandle targetUser,
            @Nullable Activity callingActivity,
            @Nullable Bundle options) {
        try {
            mService.startActivityAsUser(
                    mContext.getIApplicationThread(),
                    mContext.getPackageName(),
                    mContext.getAttributionTag(),
                    component,
                    targetUser.getIdentifier(),
                    true,
                    callingActivity != null ? callingActivity.getActivityToken() : null,
                    options);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Starts the specified activity of the caller package in the specified profile.
     *
     * <p>The caller must have the {@link android.Manifest.permission#INTERACT_ACROSS_PROFILES},
     * {@code android.Manifest.permission#INTERACT_ACROSS_USERS}, or {@code
     * android.Manifest.permission#INTERACT_ACROSS_USERS_FULL} permission. Both the caller and
     * target user profiles must be in the same profile group. The target user must be a valid user
     * returned from {@link #getTargetUserProfiles()}.
     *
     * @param intent The intent to launch. A component in the caller package must be specified.
     * @param targetUser The {@link UserHandle} of the profile; must be one of the users returned by
     *        {@link #getTargetUserProfiles()} if different to the calling user, otherwise a
     *        {@link SecurityException} will be thrown.
     * @param callingActivity The activity to start the new activity from for the purposes of
     *        passing back any result and deciding which task the new activity should belong to. If
     *        {@code null}, the activity will always be started in a new task and no result will be
     *        returned.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.INTERACT_ACROSS_PROFILES,
            INTERACT_ACROSS_USERS})
    public void startActivity(
            @NonNull Intent intent,
            @NonNull UserHandle targetUser,
            @Nullable Activity callingActivity) {
        startActivity(intent, targetUser, callingActivity, /* options= */ null);
    }

    /**
     * Starts the specified activity of the caller package in the specified profile.
     *
     * <p>The caller must have the {@link android.Manifest.permission#INTERACT_ACROSS_PROFILES},
     * {@code android.Manifest.permission#INTERACT_ACROSS_USERS}, or {@code
     * android.Manifest.permission#INTERACT_ACROSS_USERS_FULL} permission. Both the caller and
     * target user profiles must be in the same profile group. The target user must be a valid user
     * returned from {@link #getTargetUserProfiles()}.
     *
     * @param intent The intent to launch. A component in the caller package must be specified.
     * @param targetUser The {@link UserHandle} of the profile; must be one of the users returned by
     *        {@link #getTargetUserProfiles()} if different to the calling user, otherwise a
     *        {@link SecurityException} will be thrown.
     * @param callingActivity The activity to start the new activity from for the purposes of
     *        passing back any result and deciding which task the new activity should belong to. If
     *        {@code null}, the activity will always be started in a new task and no result will be
     *        returned.
     * @param options The activity options or {@code null}. See {@link android.app.ActivityOptions}.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.INTERACT_ACROSS_PROFILES,
            INTERACT_ACROSS_USERS})
    public void startActivity(
            @NonNull Intent intent,
            @NonNull UserHandle targetUser,
            @Nullable Activity callingActivity,
            @Nullable Bundle options) {
        try {
            mService.startActivityAsUserByIntent(
                    mContext.getIApplicationThread(),
                    mContext.getPackageName(),
                    mContext.getAttributionTag(),
                    intent,
                    targetUser.getIdentifier(),
                    callingActivity != null ? callingActivity.getActivityToken() : null,
                    options);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Starts the specified activity of the caller package in the specified profile. Unlike
     * {@link #startMainActivity}, this can start any activity of the caller package, not just
     * the main activity.
     * The caller must have the {@link android.Manifest.permission#INTERACT_ACROSS_PROFILES}
     * or {@link android.Manifest.permission#START_CROSS_PROFILE_ACTIVITIES}
     * permission and both the caller and target user profiles must be in the same profile group.
     *
     * @param component The ComponentName of the activity to launch. It must be exported.
     * @param targetUser The UserHandle of the profile, must be one of the users returned by
     *        {@link #getTargetUserProfiles()}, otherwise a {@link SecurityException} will
     *        be thrown.
     * @param callingActivity The activity to start the new activity from for the purposes of
     *        deciding which task the new activity should belong to. If {@code null}, the activity
     *        will always be started in a new task.
     * @param options The activity options or {@code null}. See {@link android.app.ActivityOptions}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.INTERACT_ACROSS_PROFILES,
            android.Manifest.permission.START_CROSS_PROFILE_ACTIVITIES})
    public void startActivity(
            @NonNull ComponentName component,
            @NonNull UserHandle targetUser,
            @Nullable Activity callingActivity,
            @Nullable Bundle options) {
        try {
            mService.startActivityAsUser(
                    mContext.getIApplicationThread(),
                    mContext.getPackageName(),
                    mContext.getAttributionTag(),
                    component,
                    targetUser.getIdentifier(),
                    false,
                    callingActivity != null ? callingActivity.getActivityToken() : null,
                    options);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Starts the specified activity of the caller package in the specified profile. Unlike
     * {@link #startMainActivity}, this can start any activity of the caller package, not just
     * the main activity.
     * The caller must have the {@link android.Manifest.permission#INTERACT_ACROSS_PROFILES}
     * or {@link android.Manifest.permission#START_CROSS_PROFILE_ACTIVITIES}
     * permission and both the caller and target user profiles must be in the same profile group.
     *
     * @param component The ComponentName of the activity to launch. It must be exported.
     * @param targetUser The UserHandle of the profile, must be one of the users returned by
     *        {@link #getTargetUserProfiles()}, otherwise a {@link SecurityException} will
     *        be thrown.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.INTERACT_ACROSS_PROFILES,
            android.Manifest.permission.START_CROSS_PROFILE_ACTIVITIES})
    public void startActivity(@NonNull ComponentName component, @NonNull UserHandle targetUser) {
        try {
            mService.startActivityAsUser(mContext.getIApplicationThread(),
                    mContext.getPackageName(), mContext.getAttributionTag(), component,
                    targetUser.getIdentifier(), false, null, null);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Return a list of user profiles that that the caller can use when calling other APIs in this
     * class.
     * <p>
     * A user profile would be considered as a valid target user profile, provided that:
     * <ul>
     * <li>It gets caller app installed</li>
     * <li>It is not equal to the calling user</li>
     * <li>It is in the same profile group of calling user profile</li>
     * <li>It is enabled</li>
     * </ul>
     *
     * @see UserManager#getUserProfiles()
     */
    public @NonNull List<UserHandle> getTargetUserProfiles() {
        try {
            return mService.getTargetUserProfiles(mContext.getPackageName());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }


    /**
     * Checks if the specified user is a profile, i.e. not the parent user.
     *
     * @param userHandle The UserHandle of the target profile, must be one of the users returned by
     *        {@link #getTargetUserProfiles()}, otherwise a {@link SecurityException} will
     *        be thrown.
     * @return whether the specified user is a profile.
     */
    @FlaggedApi(FLAG_ALLOW_QUERYING_PROFILE_TYPE)
    @SuppressWarnings("UserHandleName")
    public boolean isProfile(@NonNull UserHandle userHandle) {
        // Note that this is not a security check, but rather a check for correct use.
        // The actual security check is performed by UserManager.
        verifyCanAccessUser(userHandle);

        return mUserManager.isProfile(userHandle.getIdentifier());
    }

    /**
     * Checks if the specified user is a managed profile.
     *
     * @param userHandle The UserHandle of the target profile, must be one of the users returned by
     *        {@link #getTargetUserProfiles()}, otherwise a {@link SecurityException} will
     *        be thrown.
     * @return whether the specified user is a managed profile.
     */
    @FlaggedApi(FLAG_ALLOW_QUERYING_PROFILE_TYPE)
    @SuppressWarnings("UserHandleName")
    public boolean isManagedProfile(@NonNull UserHandle userHandle) {
        // Note that this is not a security check, but rather a check for correct use.
        // The actual security check is performed by UserManager.
        verifyCanAccessUser(userHandle);

        return mUserManager.isManagedProfile(userHandle.getIdentifier());
    }

    /**
     * Return a label that calling app can show to user for the semantic of profile switching --
     * launching its own activity in specified user profile. For example, it may return
     * "Switch to work" if the given user handle is the managed profile one.
     *
     * @param userHandle The UserHandle of the target profile, must be one of the users returned by
     *        {@link #getTargetUserProfiles()}, otherwise a {@link SecurityException} will
     *        be thrown.
     * @return a label that calling app can show user for the semantic of launching its own
     *         activity in the specified user profile.
     *
     * @see #startMainActivity(ComponentName, UserHandle)
     */
    public @NonNull CharSequence getProfileSwitchingLabel(@NonNull UserHandle userHandle) {
        verifyCanAccessUser(userHandle);

        final boolean isManagedProfile = mUserManager.isManagedProfile(userHandle.getIdentifier());
        final DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        final String callingAppLabel = getCallingApplicationLabel().toString();
        return dpm.getResources().getString(
                getUpdatableProfileSwitchingLabelId(isManagedProfile),
                () -> getDefaultProfileSwitchingLabel(isManagedProfile, callingAppLabel),
                callingAppLabel);
    }

    private CharSequence getCallingApplicationLabel() {
        PackageManager pm = mContext.getPackageManager();
        // If there is a label for the launcher intent, then use that as it is typically shorter.
        // Otherwise, just use the top-level application name.
        Intent launchIntent = pm.getLaunchIntentForPackage(mContext.getPackageName());
        if (launchIntent == null) {
            return getDefaultCallingApplicationLabel();
        }
        List<ResolveInfo> infos =
                pm.queryIntentActivities(
                        launchIntent, PackageManager.ResolveInfoFlags.of(MATCH_DEFAULT_ONLY));
        if (infos.size() > 0) {
            return infos.get(0).loadLabel(pm);
        }
        return getDefaultCallingApplicationLabel();
    }

    private CharSequence getDefaultCallingApplicationLabel() {
        return mContext.getApplicationInfo()
                .loadSafeLabel(
                        mContext.getPackageManager(),
                        /* ellipsizeDip= */ 0,
                        TextUtils.SAFE_STRING_FLAG_SINGLE_LINE
                                | TextUtils.SAFE_STRING_FLAG_TRIM);
    }

    private String getUpdatableProfileSwitchingLabelId(boolean isManagedProfile) {
        return isManagedProfile ? SWITCH_TO_WORK_LABEL : SWITCH_TO_PERSONAL_LABEL;
    }

    private String getDefaultProfileSwitchingLabel(boolean isManagedProfile, String label) {
        final int stringRes = isManagedProfile
                ? R.string.managed_profile_app_label : R.string.user_owner_app_label;
        return mResources.getString(stringRes, label);
    }


    /**
     * Return a drawable that calling app can show to user for the semantic of profile switching --
     * launching its own activity in specified user profile. For example, it may return a briefcase
     * icon if the given user handle is the managed profile one.
     *
     * @param userHandle The UserHandle of the target profile, must be one of the users returned by
     *        {@link #getTargetUserProfiles()}, otherwise a {@link SecurityException} will
     *        be thrown.
     * @return an icon that calling app can show user for the semantic of launching its own
     *         activity in specified user profile.
     *
     * @see #startMainActivity(ComponentName, UserHandle)
     */
    public @NonNull Drawable getProfileSwitchingIconDrawable(@NonNull UserHandle userHandle) {
        verifyCanAccessUser(userHandle);

        final boolean isManagedProfile =
                mUserManager.isManagedProfile(userHandle.getIdentifier());
        if (isManagedProfile) {
            return mContext.getPackageManager().getUserBadgeForDensityNoBackground(
                    userHandle, /* density= */ 0);
        }
        Drawable personalProfileIcon = UserIcons.getDefaultUserIcon(
                mResources, UserHandle.USER_SYSTEM,  /* light= */ true);
        // Using the same colors as the managed profile icon.
        int colorId = mContext.getResources().getConfiguration().isNightModeActive()
                ? R.color.profile_badge_1_dark
                : R.color.profile_badge_1;
        // First set the color filter to null so that it does not override
        // the tint.
        personalProfileIcon.setColorFilter(null);
        personalProfileIcon.setTint(mResources.getColor(colorId, /* theme= */ null));
        return personalProfileIcon;
    }

    /**
     * Returns whether the calling package can request to navigate the user to
     * the relevant settings page to request user consent to interact across profiles.
     *
     * <p>If {@code true}, the navigation intent can be obtained via {@link
     * #createRequestInteractAcrossProfilesIntent()}. The package can then listen to {@link
     * #ACTION_CAN_INTERACT_ACROSS_PROFILES_CHANGED} broadcasts.
     *
     * <p>Specifically, returns whether the following are all true:
     * <ul>
     * <li>{@code UserManager#getEnabledProfileIds(int)} returns at least one other profile for the
     * calling user.</li>
     * <li>The calling app has requested
     * {@code android.Manifest.permission.INTERACT_ACROSS_PROFILES} in its manifest.</li>
     * <li>The calling app is not a profile owner within the profile group of the calling user.</li>
     * </ul>
     *
     * <p>Note that in order for the user to be able to grant the consent, the requesting package
     * must be allowlisted by the admin or the OEM and installed in the other profile. If this is
     * not the case the user will be shown a message explaining why they can't grant the consent.
     *
     * <p>Note that user consent could already be granted if given a return value of {@code true}.
     * The package's current ability to interact across profiles can be checked with {@link
     * #canInteractAcrossProfiles()}.
     *
     * @return true if the calling package can request to interact across profiles.
     */
    public boolean canRequestInteractAcrossProfiles() {
        try {
            return mService.canRequestInteractAcrossProfiles(mContext.getPackageName());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the calling package can interact across profiles.

     * <p>Specifically, returns whether the following are all true:
     * <ul>
     * <li>{@link #getTargetUserProfiles()} returns a non-empty list for the calling user.</li>
     * <li>The user has previously consented to cross-profile communication for the calling
     * package.</li>
     * <li>The calling package has either been allowlisted by default by the OEM or has been
     * explicitly allowlisted by the admin via
     * {@link android.app.admin.DevicePolicyManager#setCrossProfilePackages(ComponentName, Set)}.
     * </li>
     * </ul>
     *
     * <p>If {@code false}, the package's current ability to request user consent to interact across
     * profiles can be checked with {@link #canRequestInteractAcrossProfiles()}. If {@code true},
     * user consent can be obtained via {@link #createRequestInteractAcrossProfilesIntent()}. The
     * package can then listen to {@link #ACTION_CAN_INTERACT_ACROSS_PROFILES_CHANGED} broadcasts.
     *
     * @return true if the calling package can interact across profiles.
     * @throws SecurityException if {@code mContext.getPackageName()} does not belong to the
     * calling UID.
     */
    public boolean canInteractAcrossProfiles() {
        try {
            return mService.canInteractAcrossProfiles(mContext.getPackageName());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns an {@link Intent} to open the settings page that allows the user to decide whether
     * the calling app can interact across profiles.
     *
     * <p>The method {@link #canRequestInteractAcrossProfiles()} must be returning {@code true}.
     *
     * <p>Note that the user may already have given consent and the app may already be able to
     * interact across profiles, even if {@link #canRequestInteractAcrossProfiles()} is {@code
     * true}. The current ability to interact across profiles is given by {@link
     * #canInteractAcrossProfiles()}.
     *
     * @return an {@link Intent} to open the settings page that allows the user to decide whether
     * the app can interact across profiles
     *
     * @throws SecurityException if {@code mContext.getPackageName()} does not belong to the
     * calling UID, or {@link #canRequestInteractAcrossProfiles()} is {@code false}.
     */
    public @NonNull Intent createRequestInteractAcrossProfilesIntent() {
        if (!canRequestInteractAcrossProfiles()) {
            throw new SecurityException(
                    "The calling package can not request to interact across profiles.");
        }
        final Intent settingsIntent = new Intent();
        settingsIntent.setAction(Settings.ACTION_MANAGE_CROSS_PROFILE_ACCESS);
        final Uri packageUri = Uri.parse("package:" + mContext.getPackageName());
        settingsIntent.setData(packageUri);
        return settingsIntent;
    }

    /**
     * Sets the app-op for {@link android.Manifest.permission#INTERACT_ACROSS_PROFILES} that is
     * configurable by users in Settings. This configures it for the profile group of the calling
     * package.
     *
     * <p>Before calling, check {@link #canConfigureInteractAcrossProfiles(String)} and do not call
     * if it is {@code false}. If presenting a user interface, do not allow the user to configure
     * the app-op in that case.
     *
     * <p>The underlying app-op {@link android.app.AppOpsManager#OP_INTERACT_ACROSS_PROFILES} should
     * never be set directly. This method ensures that the app-op is kept in sync for the app across
     * each user in the profile group and that those apps are sent a broadcast when their ability to
     * interact across profiles changes.
     *
     * <p>This method should be used directly whenever a user's action results in a change in an
     * app's ability to interact across profiles, as defined by the return value of {@link
     * #canInteractAcrossProfiles()}. This includes user consent changes in Settings or during
     * provisioning.
     *
     * <p>If other changes could have affected the app's ability to interact across profiles, as
     * defined by the return value of {@link #canInteractAcrossProfiles()}, such as changes to the
     * admin or OEM consent whitelists, then {@link #resetInteractAcrossProfilesAppOps(Collection,
     * Set)} should be used.
     *
     * <p>If the caller does not have the {@link android.Manifest.permission
     * #CONFIGURE_INTERACT_ACROSS_PROFILES} permission, then they must have the permissions that
     * would have been required to call {@link android.app.AppOpsManager#setMode(int, int, String,
     * int)}, which includes {@link android.Manifest.permission#MANAGE_APP_OPS_MODES}.
     *
     * <p>Also requires either {@link android.Manifest.permission#INTERACT_ACROSS_USERS} or {@link
     * android.Manifest.permission#INTERACT_ACROSS_USERS_FULL}.
     *
     * @hide
     */
    @RequiresPermission(
            allOf={android.Manifest.permission.CONFIGURE_INTERACT_ACROSS_PROFILES,
                    INTERACT_ACROSS_USERS})
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public void setInteractAcrossProfilesAppOp(@NonNull String packageName, @Mode int newMode) {
        try {
            mService.setInteractAcrossProfilesAppOp(mContext.getUserId(), packageName, newMode);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the given package can have its ability to interact across profiles configured
     * by the user. This means that every other condition to interact across profiles has been set.
     *
     * <p>This differs from {@link #canRequestInteractAcrossProfiles()} since it will not return
     * {@code false} simply when the target profile is disabled.
     *
     * @hide
     */
    @TestApi
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public boolean canConfigureInteractAcrossProfiles(@NonNull String packageName) {
        try {
            return mService.canConfigureInteractAcrossProfiles(mContext.getUserId(), packageName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if the given package has requested
     * {@link android.Manifest.permission#INTERACT_ACROSS_PROFILES} and the user has at least one
     * other profile in the same profile group.
     *
     * <p>This differs from {@link #canConfigureInteractAcrossProfiles(String)} since it will
     * not return {@code false} if the app is not allowlisted or not installed in the other profile.
     *
     * <p>Note that platform-signed apps that are automatically granted the permission and are not
     * allowlisted by the OEM will not be included in this list.
     *
     * @hide
     */
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public boolean canUserAttemptToConfigureInteractAcrossProfiles(String packageName) {
        try {
            return mService.canUserAttemptToConfigureInteractAcrossProfiles(
                    mContext.getUserId(), packageName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }
    /**
     * For each of the packages defined in {@code previousCrossProfilePackages} but not included in
     * {@code newCrossProfilePackages}, resets the app-op for {@link android.Manifest.permission
     * #INTERACT_ACROSS_PROFILES} back to its default value if it can no longer be configured by
     * users in Settings, as defined by {@link #canConfigureInteractAcrossProfiles(String)}.
     *
     * <p>This method should be used whenever an app's ability to interact across profiles could
     * have changed as a result of non-user actions, such as changes to admin or OEM consent
     * whitelists.
     *
     * <p>If the caller does not have the {@link android.Manifest.permission
     * #CONFIGURE_INTERACT_ACROSS_PROFILES} permission, then they must have the permissions that
     * would have been required to call {@link android.app.AppOpsManager#setMode(int, int, String,
     * int)}, which includes {@link android.Manifest.permission#MANAGE_APP_OPS_MODES}.
     *
     * <p>Also requires either {@link android.Manifest.permission#INTERACT_ACROSS_USERS} or {@link
     * android.Manifest.permission#INTERACT_ACROSS_USERS_FULL}.
     *
     * @hide
     */
    @RequiresPermission(
            allOf={android.Manifest.permission.CONFIGURE_INTERACT_ACROSS_PROFILES,
                    INTERACT_ACROSS_USERS})
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public void resetInteractAcrossProfilesAppOps(
            @NonNull Collection<String> previousCrossProfilePackages,
            @NonNull Set<String> newCrossProfilePackages) {
        if (previousCrossProfilePackages.isEmpty()) {
            return;
        }
        final List<String> unsetCrossProfilePackages =
                previousCrossProfilePackages.stream()
                        .filter(packageName -> !newCrossProfilePackages.contains(packageName))
                        .collect(Collectors.toList());
        if (unsetCrossProfilePackages.isEmpty()) {
            return;
        }
        try {
            mService.resetInteractAcrossProfilesAppOps(
                    mContext.getUserId(), unsetCrossProfilePackages);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Clears the app-op for {@link android.Manifest.permission#INTERACT_ACROSS_PROFILES} back to
     * its default value for every package on the device.
     *
     * <p>This method can be used to ensure that app-op state is not left around on existing users
     * for previously-configured profiles.
     *
     * <p>If the caller does not have the {@link android.Manifest.permission
     * #CONFIGURE_INTERACT_ACROSS_PROFILES} permission, then they must have the permissions that
     * would have been required to call {@link android.app.AppOpsManager#setMode(int, int, String,
     * int)}, which includes {@link android.Manifest.permission#MANAGE_APP_OPS_MODES}.
     *
     * <p>Also requires either {@link android.Manifest.permission#INTERACT_ACROSS_USERS} or {@link
     * android.Manifest.permission#INTERACT_ACROSS_USERS_FULL}.
     *
     * @hide
     */
    @RequiresPermission(
            allOf={android.Manifest.permission.CONFIGURE_INTERACT_ACROSS_PROFILES,
                    INTERACT_ACROSS_USERS})
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public void clearInteractAcrossProfilesAppOps() {
        try {
            mService.clearInteractAcrossProfilesAppOps(mContext.getUserId());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * A validation method to check that the methods in this class are only being applied to user
     * handles returned by {@link #getTargetUserProfiles()}. As this is run client-side for
     * input validation purposes, this should never replace a real security check service-side.
     */
    private void verifyCanAccessUser(UserHandle userHandle) {
        if (!getTargetUserProfiles().contains(userHandle)) {
            throw new SecurityException("Not allowed to access " + userHandle);
        }
    }
}

/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.accessibility;

import static android.content.pm.PackageManagerInternal.PACKAGE_INSTALLER;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.appwidget.AppWidgetManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;

import libcore.util.EmptyArray;

import java.util.ArrayList;
import java.util.Set;

/**
 * This class provides APIs of accessibility security policies for accessibility manager
 * to grant accessibility capabilities or events access right to accessibility services. And also
 * monitors the current bound accessibility services to prompt permission warnings for
 * not accessibility-categorized ones.
 */
public class AccessibilitySecurityPolicy {
    private static final int OWN_PROCESS_ID = android.os.Process.myPid();
    private static final String LOG_TAG = "AccessibilitySecurityPolicy";

    private static final int KEEP_SOURCE_EVENT_TYPES = AccessibilityEvent.TYPE_VIEW_CLICKED
            | AccessibilityEvent.TYPE_VIEW_FOCUSED
            | AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
            | AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
            | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_WINDOWS_CHANGED
            | AccessibilityEvent.TYPE_VIEW_SELECTED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            | AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            | AccessibilityEvent.TYPE_VIEW_SCROLLED
            | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
            | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
            | AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY;

    public static final boolean POLICY_WARNING_ENABLED = true;

    /**
     * Methods that should find their way into separate modules, but are still in AMS
     * TODO (b/111889696): Refactoring UserState to AccessibilityUserManager.
     */
    public interface AccessibilityUserManager {
        /**
         * Returns current userId maintained in accessibility manager service
         */
        int getCurrentUserIdLocked();
        // TODO: Should include resolveProfileParentLocked, but that was already in SecurityPolicy
    }

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final AppOpsManager mAppOpsManager;
    private final AccessibilityUserManager mAccessibilityUserManager;
    private final PolicyWarningUIController mPolicyWarningUIController;
    /** All bound accessibility services which don't belong to accessibility category. */
    private final ArraySet<ComponentName> mNonA11yCategoryServices = new ArraySet<>();

    private AppWidgetManagerInternal mAppWidgetService;
    private AccessibilityWindowManager mAccessibilityWindowManager;
    private int mCurrentUserId = UserHandle.USER_NULL;

    /**
     * Constructor for AccessibilityManagerService.
     */
    public AccessibilitySecurityPolicy(PolicyWarningUIController policyWarningUIController,
            @NonNull Context context,
            @NonNull AccessibilityUserManager a11yUserManager) {
        mContext = context;
        mAccessibilityUserManager = a11yUserManager;
        mPackageManager = mContext.getPackageManager();
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mAppOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mPolicyWarningUIController = policyWarningUIController;
        mPolicyWarningUIController.setAccessibilityPolicyManager(this);
    }

    /**
     * Setup AccessibilityWindowManager. This isn't part of the constructor because the
     * window manager and security policy both call each other.
     */
    public void setAccessibilityWindowManager(@NonNull AccessibilityWindowManager awm) {
        mAccessibilityWindowManager = awm;
    }

    /**
     * Setup AppWidgetManger during boot phase.
     */
    public void setAppWidgetManager(@NonNull AppWidgetManagerInternal appWidgetManager) {
        mAppWidgetService = appWidgetManager;
    }

    /**
     * Check if an accessibility event can be dispatched. Events should be dispatched only if they
     * are dispatched from items that services can see.
     *
     * @param userId The userId to check
     * @param event The event to check
     * @return {@code true} if the event can be dispatched
     */
    public boolean canDispatchAccessibilityEventLocked(int userId,
            @NonNull AccessibilityEvent event) {
        final int eventType = event.getEventType();
        switch (eventType) {
            // All events that are for changes in a global window
            // state should *always* be dispatched.
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
            case AccessibilityEvent.TYPE_ANNOUNCEMENT:
                // All events generated by the user touching the
                // screen should *always* be dispatched.
            case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START:
            case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END:
            case AccessibilityEvent.TYPE_GESTURE_DETECTION_START:
            case AccessibilityEvent.TYPE_GESTURE_DETECTION_END:
            case AccessibilityEvent.TYPE_TOUCH_INTERACTION_START:
            case AccessibilityEvent.TYPE_TOUCH_INTERACTION_END:
            case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER:
            case AccessibilityEvent.TYPE_VIEW_HOVER_EXIT:
                // Also always dispatch the event that assist is reading context.
            case AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT:
                // Also windows changing should always be dispatched.
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED: {
                return true;
            }
            // All events for changes in window content should be
            // dispatched *only* if this window is one of the windows
            // the accessibility layer reports which are windows
            // that a sighted user can touch.
            default: {
                return isRetrievalAllowingWindowLocked(userId, event.getWindowId());
            }
        }
    }

    /**
     * Find a valid package name for an app to expose to accessibility
     *
     * @param packageName The package name the app wants to expose
     * @param appId The app's id
     * @param userId The app's user id
     * @param pid The app's process pid that requested this
     * @return A package name that is valid to report
     */
    @Nullable
    public String resolveValidReportedPackageLocked(
            @Nullable CharSequence packageName, int appId, int userId, int pid) {
        // Okay to pass no package
        if (packageName == null) {
            return null;
        }
        // The system gets to pass any package
        if (appId == Process.SYSTEM_UID) {
            return packageName.toString();
        }
        // Passing a package in your UID is fine
        final String packageNameStr = packageName.toString();
        final int resolvedUid = UserHandle.getUid(userId, appId);
        if (isValidPackageForUid(packageNameStr, resolvedUid)) {
            return packageName.toString();
        }
        // Appwidget hosts get to pass packages for widgets they host
        if (mAppWidgetService != null && ArrayUtils.contains(mAppWidgetService
                .getHostedWidgetPackages(resolvedUid), packageNameStr)) {
            return packageName.toString();
        }
        // If app has the targeted permission to act as another package
        if (mContext.checkPermission(Manifest.permission.ACT_AS_PACKAGE_FOR_ACCESSIBILITY,
                pid, resolvedUid) == PackageManager.PERMISSION_GRANTED) {
            return packageName.toString();
        }
        // Otherwise, set the package to the first one in the UID
        final String[] packageNames = mPackageManager.getPackagesForUid(resolvedUid);
        if (ArrayUtils.isEmpty(packageNames)) {
            return null;
        }
        // Okay, the caller reported a package it does not have access to.
        // Instead of crashing the caller for better backwards compatibility
        // we report the first package in the UID. Since most of the time apps
        // don't use shared user id, this will yield correct results and for
        // the edge case of using a shared user id we may report the wrong
        // package but this is fine since first, this is a cheating app and
        // second there is no way to get the correct package anyway.
        return packageNames[0];
    }

    /**
     * Get the packages that are valid for a uid. In some situations, like app widgets, there
     * could be several valid packages
     *
     * @param targetPackage A package that is known to be valid for this id
     * @param targetUid The whose packages should be checked
     * @return An array of all valid package names. An empty array means any package is OK
     */
    @NonNull
    public String[] computeValidReportedPackages(
            @NonNull String targetPackage, int targetUid) {
        if (UserHandle.getAppId(targetUid) == Process.SYSTEM_UID) {
            // Empty array means any package is Okay
            return EmptyArray.STRING;
        }
        // IMPORTANT: The target package is already vetted to be in the target UID
        String[] uidPackages = new String[]{targetPackage};
        // Appwidget hosts get to pass packages for widgets they host
        if (mAppWidgetService != null) {
            final ArraySet<String> widgetPackages = mAppWidgetService
                    .getHostedWidgetPackages(targetUid);
            if (widgetPackages != null && !widgetPackages.isEmpty()) {
                final String[] validPackages = new String[uidPackages.length
                        + widgetPackages.size()];
                System.arraycopy(uidPackages, 0, validPackages, 0, uidPackages.length);
                final int widgetPackageCount = widgetPackages.size();
                for (int i = 0; i < widgetPackageCount; i++) {
                    validPackages[uidPackages.length + i] = widgetPackages.valueAt(i);
                }
                return validPackages;
            }
        }
        return uidPackages;
    }

    /**
     * Reset the event source for events that should not carry one
     *
     * @param event The event potentially to modify
     */
    public void updateEventSourceLocked(@NonNull AccessibilityEvent event) {
        if ((event.getEventType() & KEEP_SOURCE_EVENT_TYPES) == 0) {
            event.setSource(null);
        }
    }

    /**
     * Check if a service can have access to a window
     *
     * @param userId The id of the user running the service
     * @param service The service requesting access
     * @param windowId The window it wants access to
     *
     * @return Whether ot not the service may retrieve info from the window
     */
    public boolean canGetAccessibilityNodeInfoLocked(int userId,
            @NonNull AbstractAccessibilityServiceConnection service, int windowId) {
        return canRetrieveWindowContentLocked(service)
                && isRetrievalAllowingWindowLocked(userId, windowId);
    }

    /**
     * Check if a service can have access the list of windows
     *
     * @param service The service requesting access
     *
     * @return Whether ot not the service may retrieve the window list
     */
    public boolean canRetrieveWindowsLocked(
            @NonNull AbstractAccessibilityServiceConnection service) {
        return canRetrieveWindowContentLocked(service) && service.mRetrieveInteractiveWindows;
    }

    /**
     * Check if a service can have access the content of windows on the screen
     *
     * @param service The service requesting access
     *
     * @return Whether ot not the service may retrieve the content
     */
    public boolean canRetrieveWindowContentLocked(
            @NonNull AbstractAccessibilityServiceConnection service) {
        return (service.getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0;
    }

    /**
     * Check if a service can control magnification
     *
     * @param service The service requesting access
     *
     * @return Whether ot not the service may control magnification
     */
    public boolean canControlMagnification(
            @NonNull AbstractAccessibilityServiceConnection service) {
        return (service.getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_CONTROL_MAGNIFICATION) != 0;
    }

    /**
     * Check if a service can perform gestures
     *
     * @param service The service requesting access
     *
     * @return Whether ot not the service may perform gestures
     */
    public boolean canPerformGestures(@NonNull AccessibilityServiceConnection service) {
        return (service.getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0;
    }

    /**
     * Check if a service can capture gestures from the fingerprint sensor
     *
     * @param service The service requesting access
     *
     * @return Whether ot not the service may capture gestures from the fingerprint sensor
     */
    public boolean canCaptureFingerprintGestures(@NonNull AccessibilityServiceConnection service) {
        return (service.getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES) != 0;
    }

    /**
     * Checks if a service can take screenshot.
     *
     * @param service The service requesting access
     *
     * @return Whether ot not the service may take screenshot
     */
    public boolean canTakeScreenshotLocked(
            @NonNull AbstractAccessibilityServiceConnection service) {
        return (service.getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0;
    }

    /**
     * Returns the parent userId of the profile according to the specified userId.
     *
     * @param userId The userId to check
     * @return the parent userId of the profile, or self if no parent exist
     */
    public int resolveProfileParentLocked(int userId) {
        if (userId != mAccessibilityUserManager.getCurrentUserIdLocked()) {
            final long identity = Binder.clearCallingIdentity();
            try {
                UserInfo parent = mUserManager.getProfileParent(userId);
                if (parent != null) {
                    return parent.getUserHandle().getIdentifier();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        return userId;
    }

    /**
     * Returns the parent userId of the profile according to the specified userId. Enforcing
     * permissions check if specified userId is not caller's userId.
     *
     * @param userId The userId to check
     * @return the parent userId of the profile, or self if no parent exist
     * @throws SecurityException if caller cannot interact across users
     * @throws IllegalArgumentException if specified invalid userId
     */
    public int resolveCallingUserIdEnforcingPermissionsLocked(int userId) {
        final int callingUid = Binder.getCallingUid();
        final int currentUserId = mAccessibilityUserManager.getCurrentUserIdLocked();
        if (callingUid == 0
                || callingUid == Process.SYSTEM_UID
                || callingUid == Process.SHELL_UID) {
            if (userId == UserHandle.USER_CURRENT
                    || userId == UserHandle.USER_CURRENT_OR_SELF) {
                return currentUserId;
            }
            return resolveProfileParentLocked(userId);
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (callingUserId == userId) {
            return resolveProfileParentLocked(userId);
        }
        final int callingUserParentId = resolveProfileParentLocked(callingUserId);
        if (callingUserParentId == currentUserId && (userId == UserHandle.USER_CURRENT
                || userId == UserHandle.USER_CURRENT_OR_SELF)) {
            return currentUserId;
        }
        if (!hasPermission(Manifest.permission.INTERACT_ACROSS_USERS)
                && !hasPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL)) {
            throw new SecurityException("Call from user " + callingUserId + " as user "
                    + userId + " without permission INTERACT_ACROSS_USERS or "
                    + "INTERACT_ACROSS_USERS_FULL not allowed.");
        }
        if (userId == UserHandle.USER_CURRENT
                || userId == UserHandle.USER_CURRENT_OR_SELF) {
            return currentUserId;
        }
        return resolveProfileParentLocked(userId);
    }

    /**
     * Returns false if caller is not SYSTEM and SHELL, and tried to interact across users.
     *
     * @param userId The userId to interact.
     * @return false if caller cannot interact across users.
     */
    public boolean isCallerInteractingAcrossUsers(int userId) {
        final int callingUid = Binder.getCallingUid();
        return (Binder.getCallingPid() == android.os.Process.myPid()
                || callingUid == Process.SHELL_UID
                || userId == UserHandle.USER_CURRENT
                || userId == UserHandle.USER_CURRENT_OR_SELF);
    }

    private boolean isValidPackageForUid(String packageName, int uid) {
        final long token = Binder.clearCallingIdentity();
        try {
            // Since we treat calls from a profile as if made by its parent, using
            // MATCH_ANY_USER to query the uid of the given package name.
            return uid == mPackageManager.getPackageUidAsUser(
                    packageName, PackageManager.MATCH_ANY_USER, UserHandle.getUserId(uid));
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean isRetrievalAllowingWindowLocked(int userId, int windowId) {
        // The system gets to interact with any window it wants.
        if (Binder.getCallingUid() == Process.SYSTEM_UID) {
            return true;
        }
        if (Binder.getCallingUid() == Process.SHELL_UID) {
            if (!isShellAllowedToRetrieveWindowLocked(userId, windowId)) {
                return false;
            }
        }
        if (mAccessibilityWindowManager.resolveParentWindowIdLocked(windowId)
                == mAccessibilityWindowManager.getActiveWindowId(userId)) {
            return true;
        }
        return mAccessibilityWindowManager.findA11yWindowInfoByIdLocked(windowId) != null;
    }

    private boolean isShellAllowedToRetrieveWindowLocked(int userId, int windowId) {
        final long token = Binder.clearCallingIdentity();
        try {
            IBinder windowToken = mAccessibilityWindowManager
                    .getWindowTokenForUserAndWindowIdLocked(userId, windowId);
            if (windowToken == null) {
                return false;
            }
            int windowOwnerUserId = mAccessibilityWindowManager.getWindowOwnerUserId(windowToken);
            if (windowOwnerUserId == UserHandle.USER_NULL) {
                return false;
            }
            return !mUserManager.hasUserRestriction(
                    UserManager.DISALLOW_DEBUGGING_FEATURES, UserHandle.of(windowOwnerUserId));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Enforcing permission check to caller.
     *
     * @param permission The permission to check
     * @param function The function name to check
     */
    public void enforceCallingPermission(@NonNull String permission, @Nullable String function) {
        if (OWN_PROCESS_ID == Binder.getCallingPid()) {
            return;
        }
        if (!hasPermission(permission)) {
            throw new SecurityException("You do not have " + permission
                    + " required to call " + function + " from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
        }
    }

    /**
     * Permission check to caller.
     *
     * @param permission The permission to check
     * @return true if caller has permission
     */
    public boolean hasPermission(@NonNull String permission) {
        return mContext.checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Checks if accessibility service could register into the system.
     *
     * @param serviceInfo The ServiceInfo
     * @return True if it could register into the system
     */
    public boolean canRegisterService(@NonNull ServiceInfo serviceInfo) {
        if (!android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE.equals(
                serviceInfo.permission)) {
            Slog.w(LOG_TAG, "Skipping accessibility service " + new ComponentName(
                    serviceInfo.packageName, serviceInfo.name).flattenToShortString()
                    + ": it does not require the permission "
                    + android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE);
            return false;
        }

        int servicePackageUid = serviceInfo.applicationInfo.uid;
        if (mAppOpsManager.noteOpNoThrow(AppOpsManager.OPSTR_BIND_ACCESSIBILITY_SERVICE,
                servicePackageUid, serviceInfo.packageName, null, null)
                != AppOpsManager.MODE_ALLOWED) {
            Slog.w(LOG_TAG, "Skipping accessibility service " + new ComponentName(
                    serviceInfo.packageName, serviceInfo.name).flattenToShortString()
                    + ": disallowed by AppOps");
            return false;
        }

        return true;
    }

    /**
     * Checks if accessibility service could execute accessibility operations.
     *
     * @param service The accessibility service connection
     * @return True if it could execute accessibility operations
     */
    public boolean checkAccessibilityAccess(AbstractAccessibilityServiceConnection service) {
        final String packageName = service.getComponentName().getPackageName();
        final ResolveInfo resolveInfo = service.getServiceInfo().getResolveInfo();

        if (resolveInfo == null) {
            // For InteractionBridge and UiAutomation
            return true;
        }

        final int servicePackageUid = resolveInfo.serviceInfo.applicationInfo.uid;
        final int callingPid = Binder.getCallingPid();
        final long identityToken = Binder.clearCallingIdentity();
        final String attributionTag = service.getAttributionTag();
        try {
            // For the caller is system, just block the data to a11y services.
            if (OWN_PROCESS_ID == callingPid) {
                return mAppOpsManager.noteOpNoThrow(AppOpsManager.OPSTR_ACCESS_ACCESSIBILITY,
                        servicePackageUid, packageName, attributionTag, null)
                        == AppOpsManager.MODE_ALLOWED;
            }

            return mAppOpsManager.noteOp(AppOpsManager.OPSTR_ACCESS_ACCESSIBILITY,
                    servicePackageUid, packageName, attributionTag, null)
                    == AppOpsManager.MODE_ALLOWED;
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Enforcing permission check to IPC caller or grant it if it's not through IPC.
     *
     * @param permission The permission to check
     */
    public void enforceCallingOrSelfPermission(@NonNull String permission) {
        if (mContext.checkCallingOrSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller does not hold permission "
                    + permission);
        }
    }

    /**
     * Called after a service was bound or unbound. Checks the current bound accessibility
     * services and updates alarms.
     *
     * @param userId        The user id
     * @param boundServices The bound services
     */
    public void onBoundServicesChangedLocked(int userId,
            ArrayList<AccessibilityServiceConnection> boundServices) {
        if (!POLICY_WARNING_ENABLED) {
            return;
        }
        if (mAccessibilityUserManager.getCurrentUserIdLocked() != userId) {
            return;
        }

        ArraySet<ComponentName> tempNonA11yCategoryServices = new ArraySet<>();
        for (int i = 0; i < boundServices.size(); i++) {
            final AccessibilityServiceInfo a11yServiceInfo = boundServices.get(
                    i).getServiceInfo();
            final ComponentName service = a11yServiceInfo.getComponentName().clone();
            if (!isA11yCategoryService(a11yServiceInfo)) {
                tempNonA11yCategoryServices.add(service);
                if (mNonA11yCategoryServices.contains(service)) {
                    mNonA11yCategoryServices.remove(service);
                } else {
                    mPolicyWarningUIController.onNonA11yCategoryServiceBound(userId, service);
                }
            }
        }

        for (int i = 0; i < mNonA11yCategoryServices.size(); i++) {
            final ComponentName service = mNonA11yCategoryServices.valueAt(i);
            mPolicyWarningUIController.onNonA11yCategoryServiceUnbound(userId, service);
        }
        mNonA11yCategoryServices.clear();
        mNonA11yCategoryServices.addAll(tempNonA11yCategoryServices);
    }

    /**
     * Called after switching to another user. Resets data and cancels old alarms after
     * switching to another user.
     *
     * @param userId          The user id
     * @param enabledServices The enabled services
     */
    public void onSwitchUserLocked(int userId, Set<ComponentName> enabledServices) {
        if (!POLICY_WARNING_ENABLED) {
            return;
        }
        if (mCurrentUserId == userId) {
            return;
        }

        mPolicyWarningUIController.onSwitchUserLocked(userId, enabledServices);

        for (int i = 0; i < mNonA11yCategoryServices.size(); i++) {
            mPolicyWarningUIController.onNonA11yCategoryServiceUnbound(mCurrentUserId,
                    mNonA11yCategoryServices.valueAt(i));
        }
        mNonA11yCategoryServices.clear();
        mCurrentUserId = userId;
    }

    /**
     * Called after the enabled accessibility services changed.
     *
     * @param userId          The user id
     * @param enabledServices The enabled services
     */
    public void onEnabledServicesChangedLocked(int userId,
            Set<ComponentName> enabledServices) {
        if (!POLICY_WARNING_ENABLED) {
            return;
        }
        if (mAccessibilityUserManager.getCurrentUserIdLocked() != userId) {
            return;
        }

        mPolicyWarningUIController.onEnabledServicesChangedLocked(userId, enabledServices);
    }

    /**
     * Identifies whether the accessibility service is true and designed for accessibility. An
     * accessibility service is considered as accessibility category if meets all conditions below:
     * <ul>
     *     <li> {@link AccessibilityServiceInfo#isAccessibilityTool} is true</li>
     *     <li> is installed from the trusted install source</li>
     * </ul>
     *
     * @param serviceInfo The accessibility service's serviceInfo.
     * @return Returns true if it is a true accessibility service.
     */
    public boolean isA11yCategoryService(AccessibilityServiceInfo serviceInfo) {
        if (!serviceInfo.isAccessibilityTool()) {
            return false;
        }
        if (!serviceInfo.getResolveInfo().serviceInfo.applicationInfo.isSystemApp()) {
            return hasTrustedSystemInstallSource(
                    serviceInfo.getResolveInfo().serviceInfo.packageName);
        }
        return true;
    }

    /** Returns true if the {@code installedPackage} is installed from the trusted install source.
     */
    private boolean hasTrustedSystemInstallSource(String installedPackage) {
        try {
            InstallSourceInfo installSourceInfo = mPackageManager.getInstallSourceInfo(
                    installedPackage);
            if (installSourceInfo == null) {
                return false;
            }
            final String installSourcePackageName = installSourceInfo.getInitiatingPackageName();
            if (installSourcePackageName == null || !mPackageManager.getPackageInfo(
                    installSourcePackageName,
                    0).applicationInfo.isSystemApp()) {
                return false;
            }
            return isTrustedInstallSource(installSourcePackageName);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(LOG_TAG, "can't find the package's install source:" + installedPackage);
        }
        return false;
    }

    /** Returns true if the {@code installerPackage} is a trusted install source. */
    private boolean isTrustedInstallSource(String installerPackage) {
        final String[] allowedInstallingSources = mContext.getResources().getStringArray(
                com.android.internal.R.array
                        .config_accessibility_allowed_install_source);

        if (allowedInstallingSources.length == 0) {
            //Filters unwanted default installers if no allowed install sources.
            String defaultInstaller = ArrayUtils.firstOrNull(LocalServices.getService(
                    PackageManagerInternal.class).getKnownPackageNames(PACKAGE_INSTALLER,
                    mCurrentUserId));
            return !TextUtils.equals(defaultInstaller, installerPackage);
        }
        for (int i = 0; i < allowedInstallingSources.length; i++) {
            if (TextUtils.equals(allowedInstallingSources[i], installerPackage)) {
                return true;
            }
        }
        return false;
    }
}

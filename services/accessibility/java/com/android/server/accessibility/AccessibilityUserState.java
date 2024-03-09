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

import static android.accessibilityservice.AccessibilityService.SHOW_MODE_AUTO;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HIDDEN;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_MASK;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_NONE;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_BUTTON;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_SHORTCUT_KEY;
import static android.view.accessibility.AccessibilityManager.ShortcutType;

import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;

import android.accessibilityservice.AccessibilityService.SoftKeyboardShowMode;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityShortcutInfo;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.RemoteCallbackList;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

import com.android.internal.R;
import com.android.internal.accessibility.AccessibilityShortcutController;
import com.android.internal.accessibility.common.ShortcutConstants;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class that hold states and settings per user and share between
 * {@link AccessibilityManagerService} and {@link AccessibilityServiceConnection}.
 */
class AccessibilityUserState {
    private static final String LOG_TAG = AccessibilityUserState.class.getSimpleName();

    final int mUserId;

    // Non-transient state.

    final RemoteCallbackList<IAccessibilityManagerClient> mUserClients = new RemoteCallbackList<>();

    // Transient state.

    final ArrayList<AccessibilityServiceConnection> mBoundServices = new ArrayList<>();

    final Map<ComponentName, AccessibilityServiceConnection> mComponentNameToServiceMap =
            new HashMap<>();

    final List<AccessibilityServiceInfo> mInstalledServices = new ArrayList<>();

    final List<AccessibilityShortcutInfo> mInstalledShortcuts = new ArrayList<>();

    final Set<ComponentName> mBindingServices = new HashSet<>();

    final Set<ComponentName> mCrashedServices = new HashSet<>();

    final Set<ComponentName> mEnabledServices = new HashSet<>();

    final Set<ComponentName> mTouchExplorationGrantedServices = new HashSet<>();

    final ArraySet<String> mAccessibilityShortcutKeyTargets = new ArraySet<>();

    final ArraySet<String> mAccessibilityButtonTargets = new ArraySet<>();
    private final ArraySet<String> mAccessibilityQsTargets = new ArraySet<>();

    private final ServiceInfoChangeListener mServiceInfoChangeListener;

    private ComponentName mServiceChangingSoftKeyboardMode;

    private String mTargetAssignedToAccessibilityButton;

    private boolean mBindInstantServiceAllowed;
    private boolean mIsAudioDescriptionByDefaultRequested;
    private boolean mIsAutoclickEnabled;
    private boolean mIsMagnificationSingleFingerTripleTapEnabled;
    private boolean mMagnificationTwoFingerTripleTapEnabled;
    private boolean mIsFilterKeyEventsEnabled;
    private boolean mIsPerformGesturesEnabled;
    private boolean mAccessibilityFocusOnlyInActiveWindow;
    private boolean mIsTextHighContrastEnabled;
    private boolean mIsTouchExplorationEnabled;
    private boolean mServiceHandlesDoubleTap;
    private boolean mRequestMultiFingerGestures;
    private boolean mRequestTwoFingerPassthrough;
    private boolean mSendMotionEventsEnabled;
    private SparseArray<Boolean> mServiceDetectsGestures = new SparseArray<>(0);
    private int mUserInteractiveUiTimeout;
    private int mUserNonInteractiveUiTimeout;
    private int mNonInteractiveUiTimeout = 0;
    private int mInteractiveUiTimeout = 0;
    private int mLastSentClientState = -1;

    /** {@code true} if the device config supports window magnification. */
    private final boolean mSupportWindowMagnification;
    // The magnification modes on displays.
    private final SparseIntArray mMagnificationModes = new SparseIntArray();
    // The magnification capabilities used to know magnification mode could be switched.
    private int mMagnificationCapabilities = ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
    // Whether the following typing focus feature for magnification is enabled.
    private boolean mMagnificationFollowTypingEnabled = true;
    // Whether the always on magnification feature is enabled.
    private boolean mAlwaysOnMagnificationEnabled = false;

    /** The stroke width of the focus rectangle in pixels */
    private int mFocusStrokeWidth;
    /** The color of the focus rectangle */
    private int mFocusColor;
    // The default value of the focus stroke width.
    private final int mFocusStrokeWidthDefaultValue;
    // The default value of the focus color.
    private final int mFocusColorDefaultValue;
    private final Map<ComponentName, ComponentName> mA11yServiceToTileService = new ArrayMap<>();
    private final Map<ComponentName, ComponentName> mA11yActivityToTileService = new ArrayMap<>();

    private Context mContext;

    @SoftKeyboardShowMode
    private int mSoftKeyboardShowMode = SHOW_MODE_AUTO;

    boolean isValidMagnificationModeLocked(int displayId) {
        final int mode = getMagnificationModeLocked(displayId);
        if (!mSupportWindowMagnification
                && mode == Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW) {
            return false;
        }
        return (mMagnificationCapabilities & mode) != 0;
    }

    interface ServiceInfoChangeListener {
        void onServiceInfoChangedLocked(AccessibilityUserState userState);
    }

    AccessibilityUserState(int userId, @NonNull Context context,
            @NonNull ServiceInfoChangeListener serviceInfoChangeListener) {
        mUserId = userId;
        mContext = context;
        mServiceInfoChangeListener = serviceInfoChangeListener;
        mFocusStrokeWidthDefaultValue = mContext.getResources().getDimensionPixelSize(
                R.dimen.accessibility_focus_highlight_stroke_width);
        mFocusColorDefaultValue = mContext.getResources().getColor(
                R.color.accessibility_focus_highlight_color);
        mFocusStrokeWidth = mFocusStrokeWidthDefaultValue;
        mFocusColor = mFocusColorDefaultValue;
        mSupportWindowMagnification = mContext.getResources().getBoolean(
                R.bool.config_magnification_area) && mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WINDOW_MAGNIFICATION);
    }

    boolean isHandlingAccessibilityEventsLocked() {
        return !mBoundServices.isEmpty() || !mBindingServices.isEmpty();
    }

    void onSwitchToAnotherUserLocked() {
        // Unbind all services.
        unbindAllServicesLocked();

        // Clear service management state.
        mBoundServices.clear();
        mBindingServices.clear();
        mCrashedServices.clear();

        // Clear event management state.
        mLastSentClientState = -1;

        // clear UI timeout
        mNonInteractiveUiTimeout = 0;
        mInteractiveUiTimeout = 0;

        // Clear state persisted in settings.
        mEnabledServices.clear();
        mTouchExplorationGrantedServices.clear();
        mAccessibilityShortcutKeyTargets.clear();
        mAccessibilityButtonTargets.clear();
        mTargetAssignedToAccessibilityButton = null;
        mIsTouchExplorationEnabled = false;
        mServiceHandlesDoubleTap = false;
        mRequestMultiFingerGestures = false;
        mRequestTwoFingerPassthrough = false;
        mSendMotionEventsEnabled = false;
        mIsMagnificationSingleFingerTripleTapEnabled = false;
        mMagnificationTwoFingerTripleTapEnabled = false;
        mIsAutoclickEnabled = false;
        mUserNonInteractiveUiTimeout = 0;
        mUserInteractiveUiTimeout = 0;
        mMagnificationModes.clear();
        mFocusStrokeWidth = mFocusStrokeWidthDefaultValue;
        mFocusColor = mFocusColorDefaultValue;
        mMagnificationFollowTypingEnabled = true;
        mAlwaysOnMagnificationEnabled = false;
    }

    void addServiceLocked(AccessibilityServiceConnection serviceConnection) {
        if (!mBoundServices.contains(serviceConnection)) {
            if (!Flags.addWindowTokenWithoutLock()) {
                serviceConnection.addWindowTokensForAllDisplays();
            }
            mBoundServices.add(serviceConnection);
            mComponentNameToServiceMap.put(serviceConnection.getComponentName(), serviceConnection);
            mServiceInfoChangeListener.onServiceInfoChangedLocked(this);
        }
    }

    /**
     * Removes a service.
     * There are three states to a service here: off, bound, and binding.
     * This stops tracking the service as bound.
     *
     * @param serviceConnection The service.
     */
    void removeServiceLocked(AccessibilityServiceConnection serviceConnection) {
        mBoundServices.remove(serviceConnection);
        serviceConnection.onRemoved();
        if ((mServiceChangingSoftKeyboardMode != null)
                && (mServiceChangingSoftKeyboardMode.equals(
                serviceConnection.getServiceInfo().getComponentName()))) {
            setSoftKeyboardModeLocked(SHOW_MODE_AUTO, null);
        }
        // It may be possible to bind a service twice, which confuses the map. Rebuild the map
        // to make sure we can still reach a service
        mComponentNameToServiceMap.clear();
        for (int i = 0; i < mBoundServices.size(); i++) {
            AccessibilityServiceConnection boundClient = mBoundServices.get(i);
            mComponentNameToServiceMap.put(boundClient.getComponentName(), boundClient);
        }
        mServiceInfoChangeListener.onServiceInfoChangedLocked(this);
    }

    /**
     * Make sure a services disconnected but still 'on' state is reflected in AccessibilityUserState
     * There are four states to a service here: off, bound, and binding, and crashed.
     * This drops a service from a bound state, to the crashed state.
     * The crashed state describes the situation where a service used to be bound, but no longer is
     * despite still being enabled.
     *
     * @param serviceConnection The service.
     */
    void serviceDisconnectedLocked(AccessibilityServiceConnection serviceConnection) {
        removeServiceLocked(serviceConnection);
        mCrashedServices.add(serviceConnection.getComponentName());
    }

    /**
     * Set the soft keyboard mode. This mode is a bit odd, as it spans multiple settings.
     * The ACCESSIBILITY_SOFT_KEYBOARD_MODE setting can be checked by the rest of the system
     * to see if it should suppress showing the IME. The SHOW_IME_WITH_HARD_KEYBOARD setting
     * setting can be changed by the user, and prevents the system from suppressing the soft
     * keyboard when the hard keyboard is connected. The hard keyboard setting needs to defer
     * to the user's preference, if they have supplied one.
     *
     * @param newMode The new mode
     * @param requester The service requesting the change, so we can undo it when the
     *                  service stops. Set to null if something other than a service is forcing
     *                  the change.
     *
     * @return Whether or not the soft keyboard mode equals the new mode after the call
     */
    boolean setSoftKeyboardModeLocked(@SoftKeyboardShowMode int newMode,
            @Nullable ComponentName requester) {
        if ((newMode != SHOW_MODE_AUTO)
                && (newMode != SHOW_MODE_HIDDEN)
                && (newMode != SHOW_MODE_IGNORE_HARD_KEYBOARD)) {
            Slog.w(LOG_TAG, "Invalid soft keyboard mode");
            return false;
        }
        if (mSoftKeyboardShowMode == newMode) {
            return true;
        }

        if (newMode == SHOW_MODE_IGNORE_HARD_KEYBOARD) {
            if (hasUserOverriddenHardKeyboardSetting()) {
                // The user has specified a default for this setting
                return false;
            }
            // Save the original value. But don't do this if the value in settings is already
            // the new mode. That happens when we start up after a reboot, and we don't want
            // to overwrite the value we had from when we first started controlling the setting.
            if (getSoftKeyboardValueFromSettings() != SHOW_MODE_IGNORE_HARD_KEYBOARD) {
                setOriginalHardKeyboardValue(getSecureIntForUser(
                        Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 0, mUserId) != 0);
            }
            putSecureIntForUser(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 1, mUserId);
        } else if (mSoftKeyboardShowMode == SHOW_MODE_IGNORE_HARD_KEYBOARD) {
            putSecureIntForUser(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD,
                    getOriginalHardKeyboardValue() ? 1 : 0, mUserId);
        }

        saveSoftKeyboardValueToSettings(newMode);
        mSoftKeyboardShowMode = newMode;
        mServiceChangingSoftKeyboardMode = requester;
        for (int i = mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = mBoundServices.get(i);
            service.notifySoftKeyboardShowModeChangedLocked(mSoftKeyboardShowMode);
        }
        return true;
    }

    @SoftKeyboardShowMode
    int getSoftKeyboardShowModeLocked() {
        return mSoftKeyboardShowMode;
    }

    /**
     * If the settings are inconsistent with the internal state, make the internal state
     * match the settings.
     */
    void reconcileSoftKeyboardModeWithSettingsLocked() {
        final boolean showWithHardKeyboardSettings =
                getSecureIntForUser(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 0, mUserId) != 0;
        if (mSoftKeyboardShowMode == SHOW_MODE_IGNORE_HARD_KEYBOARD) {
            if (!showWithHardKeyboardSettings) {
                // The user has overridden the setting. Respect that and prevent further changes
                // to this behavior.
                setSoftKeyboardModeLocked(SHOW_MODE_AUTO, null);
                setUserOverridesHardKeyboardSetting();
            }
        }

        // If the setting and the internal state are out of sync, set both to default
        if (getSoftKeyboardValueFromSettings() != mSoftKeyboardShowMode) {
            Slog.e(LOG_TAG, "Show IME setting inconsistent with internal state. Overwriting");
            setSoftKeyboardModeLocked(SHOW_MODE_AUTO, null);
            putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                    SHOW_MODE_AUTO, mUserId);
        }
    }

    boolean getBindInstantServiceAllowedLocked() {
        return mBindInstantServiceAllowed;
    }

    /* Need to have a permission check on callee */
    void setBindInstantServiceAllowedLocked(boolean allowed) {
        mBindInstantServiceAllowed = allowed;
    }

    /**
     * Returns binding service list.
     */
    Set<ComponentName> getBindingServicesLocked() {
        return mBindingServices;
    }

    /**
     * Returns crashed service list.
     */
    Set<ComponentName> getCrashedServicesLocked() {
        return mCrashedServices;
    }

    /**
     * Returns enabled service list.
     */
    Set<ComponentName> getEnabledServicesLocked() {
        return mEnabledServices;
    }

    /**
     * Remove the service from the crashed and binding service lists if the user disabled it.
     */
    void removeDisabledServicesFromTemporaryStatesLocked() {
        for (int i = 0, count = mInstalledServices.size(); i < count; i++) {
            final AccessibilityServiceInfo installedService = mInstalledServices.get(i);
            final ComponentName componentName = ComponentName.unflattenFromString(
                    installedService.getId());

            if (!mEnabledServices.contains(componentName)) {
                // Remove from mCrashedServices, since users may toggle the on/off switch to retry.
                mCrashedServices.remove(componentName);
                // Remove from mBindingServices, since services can get stuck in the binding state
                // if binding starts but never finishes. If the service later attempts to finish
                // binding but it is not in the enabled list then it will exit before initializing;
                // see AccessibilityServiceConnection#initializeService().
                mBindingServices.remove(componentName);
            }
        }
    }

    List<AccessibilityServiceConnection> getBoundServicesLocked() {
        return mBoundServices;
    }

    int getClientStateLocked(boolean uiAutomationCanIntrospect,
            int traceClientState) {
        int clientState = 0;
        final boolean a11yEnabled = uiAutomationCanIntrospect
                || isHandlingAccessibilityEventsLocked();
        if (a11yEnabled) {
            clientState |= AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;
        }
        // Touch exploration relies on enabled accessibility.
        if (a11yEnabled && mIsTouchExplorationEnabled) {
            clientState |= AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED;
            clientState |= AccessibilityManager.STATE_FLAG_DISPATCH_DOUBLE_TAP;
            clientState |= AccessibilityManager.STATE_FLAG_REQUEST_MULTI_FINGER_GESTURES;
        }
        if (mIsTextHighContrastEnabled) {
            clientState |= AccessibilityManager.STATE_FLAG_HIGH_TEXT_CONTRAST_ENABLED;
        }
        if (mIsAudioDescriptionByDefaultRequested) {
            clientState |=
                    AccessibilityManager.STATE_FLAG_AUDIO_DESCRIPTION_BY_DEFAULT_ENABLED;
        }

        clientState |= traceClientState;

        return clientState;
    }

    private void setUserOverridesHardKeyboardSetting() {
        final int softKeyboardSetting = getSecureIntForUser(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, SHOW_MODE_AUTO, mUserId);
        putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                softKeyboardSetting | SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN,
                mUserId);
    }

    private boolean hasUserOverriddenHardKeyboardSetting() {
        final int softKeyboardSetting = getSecureIntForUser(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, SHOW_MODE_AUTO, mUserId);
        return (softKeyboardSetting & SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN)
                != 0;
    }

    private void setOriginalHardKeyboardValue(boolean originalHardKeyboardValue) {
        final int oldSoftKeyboardSetting = getSecureIntForUser(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, SHOW_MODE_AUTO, mUserId);
        final int newSoftKeyboardSetting = oldSoftKeyboardSetting
                & (~SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE)
                | ((originalHardKeyboardValue) ? SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE : 0);
        putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                newSoftKeyboardSetting, mUserId);
    }

    private void saveSoftKeyboardValueToSettings(int softKeyboardShowMode) {
        final int oldSoftKeyboardSetting = getSecureIntForUser(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, SHOW_MODE_AUTO, mUserId);
        final int newSoftKeyboardSetting = oldSoftKeyboardSetting & (~SHOW_MODE_MASK)
                | softKeyboardShowMode;
        putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                newSoftKeyboardSetting, mUserId);
    }

    private int getSoftKeyboardValueFromSettings() {
        return getSecureIntForUser(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, SHOW_MODE_AUTO, mUserId)
                & SHOW_MODE_MASK;
    }

    private boolean getOriginalHardKeyboardValue() {
        return (getSecureIntForUser(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, SHOW_MODE_AUTO, mUserId)
                & SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE) != 0;
    }

    private void unbindAllServicesLocked() {
        final List<AccessibilityServiceConnection> services = mBoundServices;
        for (int count = services.size(); count > 0; count--) {
            // When the service is unbound, it disappears from the list, so there's no need to
            // keep track of the index
            services.get(0).unbindLocked();
        }
    }

    private int getSecureIntForUser(String key, int def, int userId) {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(), key, def, userId);
    }

    private void putSecureIntForUser(String key, int value, int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(), key, value, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.append("User state[");
        pw.println();
        pw.append("     attributes:{id=").append(String.valueOf(mUserId));
        pw.append(", touchExplorationEnabled=").append(String.valueOf(mIsTouchExplorationEnabled));
        pw.append(", serviceHandlesDoubleTap=")
                .append(String.valueOf(mServiceHandlesDoubleTap));
        pw.append(", requestMultiFingerGestures=")
                .append(String.valueOf(mRequestMultiFingerGestures));
        pw.append(", requestTwoFingerPassthrough=")
                .append(String.valueOf(mRequestTwoFingerPassthrough));
        pw.append(", sendMotionEventsEnabled").append(String.valueOf(mSendMotionEventsEnabled));
        pw.append(", displayMagnificationEnabled=").append(String.valueOf(
                mIsMagnificationSingleFingerTripleTapEnabled));
        pw.append(", autoclickEnabled=").append(String.valueOf(mIsAutoclickEnabled));
        pw.append(", nonInteractiveUiTimeout=").append(String.valueOf(mNonInteractiveUiTimeout));
        pw.append(", interactiveUiTimeout=").append(String.valueOf(mInteractiveUiTimeout));
        pw.append(", installedServiceCount=").append(String.valueOf(mInstalledServices.size()));
        pw.append(", magnificationModes=").append(String.valueOf(mMagnificationModes));
        pw.append(", magnificationCapabilities=")
                .append(String.valueOf(mMagnificationCapabilities));
        pw.append(", audioDescriptionByDefaultEnabled=")
                .append(String.valueOf(mIsAudioDescriptionByDefaultRequested));
        pw.append(", magnificationFollowTypingEnabled=")
                .append(String.valueOf(mMagnificationFollowTypingEnabled));
        pw.append(", alwaysOnMagnificationEnabled=")
                .append(String.valueOf(mAlwaysOnMagnificationEnabled));
        pw.append("}");
        pw.println();
        pw.append("     shortcut key:{");
        int size = mAccessibilityShortcutKeyTargets.size();
        for (int i = 0; i < size; i++) {
            final String componentId = mAccessibilityShortcutKeyTargets.valueAt(i);
            pw.append(componentId);
            if (i + 1 < size) {
                pw.append(", ");
            }
        }
        pw.println("}");
        pw.append("     button:{");
        size = mAccessibilityButtonTargets.size();
        for (int i = 0; i < size; i++) {
            final String componentId = mAccessibilityButtonTargets.valueAt(i);
            pw.append(componentId);
            if (i + 1 < size) {
                pw.append(", ");
            }
        }
        pw.println("}");
        pw.append("     button target:{").append(mTargetAssignedToAccessibilityButton);
        pw.println("}");
        pw.append("     qs shortcut targets:" + mAccessibilityQsTargets);
        pw.println();
        pw.append("     Bound services:{");
        final int serviceCount = mBoundServices.size();
        for (int j = 0; j < serviceCount; j++) {
            if (j > 0) {
                pw.append(", ");
                pw.println();
                pw.append("                     ");
            }
            AccessibilityServiceConnection service = mBoundServices.get(j);
            service.dump(fd, pw, args);
        }
        pw.println("}");
        pw.append("     Enabled services:{");
        Iterator<ComponentName> it = mEnabledServices.iterator();
        if (it.hasNext()) {
            ComponentName componentName = it.next();
            pw.append(componentName.toShortString());
            while (it.hasNext()) {
                componentName = it.next();
                pw.append(", ");
                pw.append(componentName.toShortString());
            }
        }
        pw.println("}");
        pw.append("     Binding services:{");
        it = mBindingServices.iterator();
        if (it.hasNext()) {
            ComponentName componentName = it.next();
            pw.append(componentName.toShortString());
            while (it.hasNext()) {
                componentName = it.next();
                pw.append(", ");
                pw.append(componentName.toShortString());
            }
        }
        pw.println("}");
        pw.append("     Crashed services:{");
        it = mCrashedServices.iterator();
        if (it.hasNext()) {
            ComponentName componentName = it.next();
            pw.append(componentName.toShortString());
            while (it.hasNext()) {
                componentName = it.next();
                pw.append(", ");
                pw.append(componentName.toShortString());
            }
        }
        pw.println("}");
        pw.println("     Client list info:{");
        mUserClients.dump(pw, "          Client list ");
        pw.println("          Registered clients:{");
        for (int i = 0; i < mUserClients.getRegisteredCallbackCount(); i++) {
            AccessibilityManagerService.Client client = (AccessibilityManagerService.Client)
                    mUserClients.getRegisteredCallbackCookie(i);
            pw.append(Arrays.toString(client.mPackageNames));
        }
        pw.println("}]");
    }

    public boolean isAutoclickEnabledLocked() {
        return mIsAutoclickEnabled;
    }

    public void setAutoclickEnabledLocked(boolean enabled) {
        mIsAutoclickEnabled = enabled;
    }

    public boolean isMagnificationSingleFingerTripleTapEnabledLocked() {
        return mIsMagnificationSingleFingerTripleTapEnabled;
    }

    public void setMagnificationSingleFingerTripleTapEnabledLocked(boolean enabled) {
        mIsMagnificationSingleFingerTripleTapEnabled = enabled;
    }

    public boolean isMagnificationTwoFingerTripleTapEnabledLocked() {
        return mMagnificationTwoFingerTripleTapEnabled;
    }

    public void setMagnificationTwoFingerTripleTapEnabledLocked(boolean enabled) {
        mMagnificationTwoFingerTripleTapEnabled = enabled;
    }

    public boolean isFilterKeyEventsEnabledLocked() {
        return mIsFilterKeyEventsEnabled;
    }

    public void setFilterKeyEventsEnabledLocked(boolean enabled) {
        mIsFilterKeyEventsEnabled = enabled;
    }

    public int getInteractiveUiTimeoutLocked() {
        return mInteractiveUiTimeout;
    }

    public void setInteractiveUiTimeoutLocked(int timeout) {
        mInteractiveUiTimeout = timeout;
    }

    public int getLastSentClientStateLocked() {
        return mLastSentClientState;
    }

    public void setLastSentClientStateLocked(int state) {
        mLastSentClientState = state;
    }

    /**
     * Returns true if navibar magnification or shortcut key magnification is enabled.
     */
    public boolean isShortcutMagnificationEnabledLocked() {
        return mAccessibilityShortcutKeyTargets.contains(MAGNIFICATION_CONTROLLER_NAME)
                || mAccessibilityButtonTargets.contains(MAGNIFICATION_CONTROLLER_NAME);
    }

    /**
     * Gets the magnification mode for the given display.
     * @return magnification mode
     *
     * @see Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
     * @see Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
     */
    public int getMagnificationModeLocked(int displayId) {
        int mode = mMagnificationModes.get(displayId, ACCESSIBILITY_MAGNIFICATION_MODE_NONE);
        if (mode == ACCESSIBILITY_MAGNIFICATION_MODE_NONE) {
            mode = ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
            setMagnificationModeLocked(displayId, mode);
        }
        return mode;
    }


    /**
     * Gets the magnification capabilities setting of current user.
     *
     * @return magnification capabilities
     *
     * @see Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
     * @see Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
     * @see Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_ALL
     */
    int getMagnificationCapabilitiesLocked() {
        return mMagnificationCapabilities;
    }

    /**
     * Sets the magnification capabilities from Settings value.
     *
     * @see Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
     * @see Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
     * @see Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_ALL
     */
    public void setMagnificationCapabilitiesLocked(int capabilities) {
        mMagnificationCapabilities = capabilities;
    }

    public void setMagnificationFollowTypingEnabled(boolean enabled) {
        mMagnificationFollowTypingEnabled = enabled;
    }

    public boolean isMagnificationFollowTypingEnabled() {
        return mMagnificationFollowTypingEnabled;
    }

    public void setAlwaysOnMagnificationEnabled(boolean enabled) {
        mAlwaysOnMagnificationEnabled = enabled;
    }

    public boolean isAlwaysOnMagnificationEnabled() {
        return mAlwaysOnMagnificationEnabled;
    }

    /**
     * Sets the magnification mode to the given display.
     *
     * @param displayId The display id.
     * @param mode The magnification mode.
     */
    public void setMagnificationModeLocked(int displayId, int mode) {
        mMagnificationModes.put(displayId, mode);
    }

    /**
     * Disable both shortcuts' magnification function.
     */
    public void disableShortcutMagnificationLocked() {
        mAccessibilityShortcutKeyTargets.remove(MAGNIFICATION_CONTROLLER_NAME);
        mAccessibilityButtonTargets.remove(MAGNIFICATION_CONTROLLER_NAME);
    }

    /**
     * Returns a set which contains the flattened component names and the system class names
     * assigned to the given shortcut.
     *
     * @param shortcutType The shortcut type.
     * @return The array set of the strings
     */
    public ArraySet<String> getShortcutTargetsLocked(@ShortcutType int shortcutType) {
        if (shortcutType == ACCESSIBILITY_SHORTCUT_KEY) {
            return mAccessibilityShortcutKeyTargets;
        } else if (shortcutType == ACCESSIBILITY_BUTTON) {
            return mAccessibilityButtonTargets;
        } else if (shortcutType == ShortcutConstants.UserShortcutType.QUICK_SETTINGS) {
            return getA11yQsTargets();
        }
        return null;
    }

    /**
     * Whether or not the given shortcut target is installed in device.
     *
     * @param name The shortcut target name
     * @return true if the shortcut target is installed.
     */
    public boolean isShortcutTargetInstalledLocked(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        if (MAGNIFICATION_CONTROLLER_NAME.equals(name)) {
            return true;
        }

        final ComponentName componentName = ComponentName.unflattenFromString(name);
        if (componentName == null) {
            return false;
        }
        if (AccessibilityShortcutController.getFrameworkShortcutFeaturesMap()
                .containsKey(componentName)) {
            return true;
        }
        if (getInstalledServiceInfoLocked(componentName) != null) {
            return true;
        }
        for (int i = 0; i < mInstalledShortcuts.size(); i++) {
            if (mInstalledShortcuts.get(i).getComponentName().equals(componentName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes given shortcut target in the list.
     *
     * @param shortcutType The shortcut type.
     * @param target The component name of the shortcut target.
     * @return true if the shortcut target is removed.
     */
    public boolean removeShortcutTargetLocked(@ShortcutType int shortcutType,
            ComponentName target) {
        Set<String> targets = getShortcutTargetsLocked(shortcutType);
        boolean result = targets.removeIf(name -> {
            ComponentName componentName;
            if (name == null
                    || (componentName = ComponentName.unflattenFromString(name)) == null) {
                return false;
            }
            return componentName.equals(target);
        });
        if (shortcutType == ShortcutConstants.UserShortcutType.QUICK_SETTINGS) {
            updateA11yQsTargetLocked(targets);
        }

        return result;
    }

    /**
     * Returns installed accessibility service info by the given service component name.
     */
    public AccessibilityServiceInfo getInstalledServiceInfoLocked(ComponentName componentName) {
        for (int i = 0; i < mInstalledServices.size(); i++) {
            final AccessibilityServiceInfo serviceInfo = mInstalledServices.get(i);
            if (serviceInfo.getComponentName().equals(componentName)) {
                return serviceInfo;
            }
        }
        return null;
    }

    /**
     * Returns accessibility service connection by the given service component name.
     */
    public AccessibilityServiceConnection getServiceConnectionLocked(ComponentName componentName) {
        return mComponentNameToServiceMap.get(componentName);
    }

    public int getNonInteractiveUiTimeoutLocked() {
        return mNonInteractiveUiTimeout;
    }

    public void setNonInteractiveUiTimeoutLocked(int timeout) {
        mNonInteractiveUiTimeout = timeout;
    }

    public boolean isPerformGesturesEnabledLocked() {
        return mIsPerformGesturesEnabled;
    }

    public void setPerformGesturesEnabledLocked(boolean enabled) {
        mIsPerformGesturesEnabled = enabled;
    }

    public boolean isAccessibilityFocusOnlyInActiveWindow() {
        return mAccessibilityFocusOnlyInActiveWindow;
    }

    public void setAccessibilityFocusOnlyInActiveWindow(boolean enabled) {
        mAccessibilityFocusOnlyInActiveWindow = enabled;
    }
    public ComponentName getServiceChangingSoftKeyboardModeLocked() {
        return mServiceChangingSoftKeyboardMode;
    }

    public void setServiceChangingSoftKeyboardModeLocked(
            ComponentName serviceChangingSoftKeyboardMode) {
        mServiceChangingSoftKeyboardMode = serviceChangingSoftKeyboardMode;
    }

    public boolean isTextHighContrastEnabledLocked() {
        return mIsTextHighContrastEnabled;
    }

    public void setTextHighContrastEnabledLocked(boolean enabled) {
        mIsTextHighContrastEnabled = enabled;
    }

    public boolean isAudioDescriptionByDefaultEnabledLocked() {
        return mIsAudioDescriptionByDefaultRequested;
    }

    public void setAudioDescriptionByDefaultEnabledLocked(boolean enabled) {
        mIsAudioDescriptionByDefaultRequested = enabled;
    }

    public boolean isTouchExplorationEnabledLocked() {
        return mIsTouchExplorationEnabled;
    }

    public void setTouchExplorationEnabledLocked(boolean enabled) {
        mIsTouchExplorationEnabled = enabled;
    }

    public boolean isServiceHandlesDoubleTapEnabledLocked() {
        return mServiceHandlesDoubleTap;
    }

    public void setServiceHandlesDoubleTapLocked(boolean enabled) {
        mServiceHandlesDoubleTap = enabled;
    }

    public boolean isMultiFingerGesturesEnabledLocked() {
        return mRequestMultiFingerGestures;
    }

    public void setMultiFingerGesturesLocked(boolean enabled) {
        mRequestMultiFingerGestures = enabled;
    }
    public boolean isTwoFingerPassthroughEnabledLocked() {
        return mRequestTwoFingerPassthrough;
    }

    public void setTwoFingerPassthroughLocked(boolean enabled) {
        mRequestTwoFingerPassthrough = enabled;
    }

    public boolean isSendMotionEventsEnabled() {
        return mSendMotionEventsEnabled;
    }

    public void setSendMotionEventsEnabled(boolean mode) {
        mSendMotionEventsEnabled = mode;
    }

    public int getUserInteractiveUiTimeoutLocked() {
        return mUserInteractiveUiTimeout;
    }

    public void setUserInteractiveUiTimeoutLocked(int timeout) {
        mUserInteractiveUiTimeout = timeout;
    }

    public int getUserNonInteractiveUiTimeoutLocked() {
        return mUserNonInteractiveUiTimeout;
    }

    public void setUserNonInteractiveUiTimeoutLocked(int timeout) {
        mUserNonInteractiveUiTimeout = timeout;
    }

    /**
     * Gets a shortcut target which is assigned to the accessibility button by the chooser
     * activity.
     *
     * @return The flattened component name or the system class name of the shortcut target.
     */
    public String getTargetAssignedToAccessibilityButton() {
        return mTargetAssignedToAccessibilityButton;
    }

    /**
     * Sets a shortcut target which is assigned to the accessibility button by the chooser
     * activity.
     *
     * @param target The flattened component name or the system class name of the shortcut target.
     */
    public void setTargetAssignedToAccessibilityButton(String target) {
        mTargetAssignedToAccessibilityButton = target;
    }

    /**
     * Whether or not the given target name is contained in the shortcut collection. Since the
     * component name string format could be short or long, this function un-flatten the component
     * name from the string in {@code shortcutTargets} and compared with the given target name.
     *
     * @param shortcutTargets The shortcut type.
     * @param targetName The target name.
     * @return {@code true} if the target is in the shortcut collection.
     */
    public static boolean doesShortcutTargetsStringContain(Collection<String> shortcutTargets,
            String targetName) {
        if (shortcutTargets == null || targetName == null) {
            return false;
        }
        // Some system features, such as magnification, don't have component name. Using string
        // compare first.
        if (shortcutTargets.contains(targetName)) {
            return true;
        }
        final ComponentName targetComponentName = ComponentName.unflattenFromString(targetName);
        if (targetComponentName == null) {
            return false;
        }
        for (String stringName : shortcutTargets) {
            if (!TextUtils.isEmpty(stringName)
                    && targetComponentName.equals(ComponentName.unflattenFromString(stringName))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the stroke width of the focus rectangle.
     * @return The stroke width.
     */
    public int getFocusStrokeWidthLocked() {
        return mFocusStrokeWidth;
    }

    /**
     * Gets the color of the focus rectangle.
     * @return The color.
     */
    public int getFocusColorLocked() {
        return mFocusColor;
    }

    /**
     * Sets the stroke width and color of the focus rectangle.
     *
     * @param strokeWidth The strokeWidth of the focus rectangle.
     * @param color The color of the focus rectangle.
     */
    public void setFocusAppearanceLocked(int strokeWidth, int color) {
        mFocusStrokeWidth = strokeWidth;
        mFocusColor = color;
    }

    public void setServiceDetectsGesturesEnabled(int displayId, boolean mode) {
        mServiceDetectsGestures.put(displayId, mode);
    }

    public void resetServiceDetectsGestures() {
        mServiceDetectsGestures.clear();
    }

    public boolean isServiceDetectsGesturesEnabled(int displayId) {
        if (mServiceDetectsGestures.contains(displayId)) {
            return mServiceDetectsGestures.get(displayId);
        }
        return false;
    }

    public void updateTileServiceMapForAccessibilityServiceLocked() {
        mA11yServiceToTileService.clear();
        mInstalledServices.forEach(
                a11yServiceInfo -> {
                    String tileServiceName = a11yServiceInfo.getTileServiceName();
                    if (!TextUtils.isEmpty(tileServiceName)) {
                        ResolveInfo resolveInfo = a11yServiceInfo.getResolveInfo();
                        ComponentName a11yFeature = new ComponentName(
                                resolveInfo.serviceInfo.packageName,
                                resolveInfo.serviceInfo.name
                        );
                        ComponentName tileService = new ComponentName(
                                a11yFeature.getPackageName(),
                                tileServiceName
                        );
                        mA11yServiceToTileService.put(a11yFeature, tileService);
                    }
                }
        );
    }

    public void updateTileServiceMapForAccessibilityActivityLocked() {
        mA11yActivityToTileService.clear();
        mInstalledShortcuts.forEach(
                a11yShortcutInfo -> {
                    String tileServiceName = a11yShortcutInfo.getTileServiceName();
                    if (!TextUtils.isEmpty(tileServiceName)) {
                        ComponentName a11yFeature = a11yShortcutInfo.getComponentName();
                        ComponentName tileService = new ComponentName(
                                a11yFeature.getPackageName(),
                                tileServiceName);
                        mA11yActivityToTileService.put(a11yFeature, tileService);
                    }
                }
        );
    }

    public void updateA11yQsTargetLocked(Set<String> targets) {
        mAccessibilityQsTargets.clear();
        mAccessibilityQsTargets.addAll(targets);
    }

    /**
     * Returns a copy of the targets which has qs shortcut turned on
     */
    public ArraySet<String> getA11yQsTargets() {
        return new ArraySet<>(mAccessibilityQsTargets);
    }

    public Map<ComponentName, ComponentName> getA11yFeatureToTileService() {
        Map<ComponentName, ComponentName> featureToTileServiceMap = new ArrayMap<>();
        featureToTileServiceMap.putAll(mA11yServiceToTileService);
        featureToTileServiceMap.putAll(mA11yActivityToTileService);
        return featureToTileServiceMap;
    }
}

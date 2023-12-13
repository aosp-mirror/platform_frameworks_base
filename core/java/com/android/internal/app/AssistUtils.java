/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Set;

/**
 * Utility method for dealing with the assistant aspects of
 * {@link com.android.internal.app.IVoiceInteractionManagerService IVoiceInteractionManagerService}.
 */
public class AssistUtils {

    private static final String TAG = "AssistUtils";

    /** bundle key: how was the assistant invoked? */
    public static final String INVOCATION_TYPE_KEY = "invocation_type";
    /** value for INVOCATION_TYPE_KEY: no data */
    public static final int INVOCATION_TYPE_UNKNOWN = 0;
    /** value for INVOCATION_TYPE_KEY: on-screen swipe gesture */
    public static final int INVOCATION_TYPE_GESTURE = 1;
    /** value for INVOCATION_TYPE_KEY: device-specific physical gesture */
    public static final int INVOCATION_TYPE_PHYSICAL_GESTURE = 2;
    /** value for INVOCATION_TYPE_KEY: voice hotword */
    public static final int INVOCATION_TYPE_VOICE = 3;
    /** value for INVOCATION_TYPE_KEY: search bar affordance */
    public static final int INVOCATION_TYPE_QUICK_SEARCH_BAR = 4;
    /** value for INVOCATION_TYPE_KEY: long press on home navigation button */
    public static final int INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS = 5;
    /** value for INVOCATION_TYPE_KEY: long press on physical power button */
    public static final int INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6;
    /** value for INVOCATION_TYPE_KEY: press on physcial assistant button */
    public static final int INVOCATION_TYPE_ASSIST_BUTTON = 7;
    /** value for INVOCATION_TYPE_KEY: long press on nav handle */
    public static final int INVOCATION_TYPE_NAV_HANDLE_LONG_PRESS = 8;

    private final Context mContext;
    private final IVoiceInteractionManagerService mVoiceInteractionManagerService;

    @UnsupportedAppUsage
    public AssistUtils(Context context) {
        mContext = context;
        mVoiceInteractionManagerService = IVoiceInteractionManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
    }

    /**
     * Shows the session for the currently active service. Used to start a new session from system
     * affordances.
     *
     * @param args the bundle to pass as arguments to the voice interaction session
     * @param sourceFlags flags indicating the source of this show
     * @param showCallback optional callback to be notified when the session was shown
     * @param activityToken optional token of activity that needs to be on top
     *
     * @deprecated Use {@link #showSessionForActiveService(Bundle, int, String,
     *             IVoiceInteractionSessionShowCallback, IBinder)} instead
     */
    @Deprecated
    public boolean showSessionForActiveService(@Nullable Bundle args, int sourceFlags,
            @Nullable IVoiceInteractionSessionShowCallback showCallback,
            @Nullable IBinder activityToken) {
        return showSessionForActiveServiceInternal(args, sourceFlags, /* attributionTag */ null,
                showCallback, activityToken);
    }

    /**
     * Shows the session for the currently active service. Used to start a new session from system
     * affordances.
     *
     * @param args the bundle to pass as arguments to the voice interaction session
     * @param sourceFlags flags indicating the source of this show
     * @param attributionTag the attribution tag of the calling context or {@code null} for default
     *                       attribution
     * @param showCallback optional callback to be notified when the session was shown
     * @param activityToken optional token of activity that needs to be on top
     */
    public boolean showSessionForActiveService(@Nullable Bundle args, int sourceFlags,
            @Nullable String attributionTag,
            @Nullable IVoiceInteractionSessionShowCallback showCallback,
            @Nullable IBinder activityToken) {
        return showSessionForActiveServiceInternal(args, sourceFlags, attributionTag, showCallback,
                activityToken);
    }

    private boolean showSessionForActiveServiceInternal(@Nullable Bundle args, int sourceFlags,
            @Nullable String attributionTag,
            @Nullable IVoiceInteractionSessionShowCallback showCallback,
            @Nullable IBinder activityToken) {
        try {
            if (mVoiceInteractionManagerService != null) {
                return mVoiceInteractionManagerService.showSessionForActiveService(args,
                        sourceFlags, attributionTag, showCallback, activityToken);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call showSessionForActiveService", e);
        }
        return false;
    }

    /**
     * Checks the availability of a set of voice actions for the current active voice service.
     *
     * @param voiceActions A set of supported voice actions to be checked.
     * @param callback     The callback which will deliver a set of supported voice actions. If
     *                     no voice actions are supported for the given voice action set, then null
     *                     or empty set is provided.
     */
    public void getActiveServiceSupportedActions(@NonNull Set<String> voiceActions,
            @NonNull IVoiceActionCheckCallback callback) {
        try {
            if (mVoiceInteractionManagerService != null) {
                mVoiceInteractionManagerService
                        .getActiveServiceSupportedActions(new ArrayList<>(voiceActions), callback);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call activeServiceSupportedActions", e);
            try {
                callback.onComplete(null);
            } catch (RemoteException re) {
            }
        }
    }

    public void launchVoiceAssistFromKeyguard() {
        try {
            if (mVoiceInteractionManagerService != null) {
                mVoiceInteractionManagerService.launchVoiceAssistFromKeyguard();
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call launchVoiceAssistFromKeyguard", e);
        }
    }

    public boolean activeServiceSupportsAssistGesture() {
        try {
            return mVoiceInteractionManagerService != null
                    && mVoiceInteractionManagerService.activeServiceSupportsAssist();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call activeServiceSupportsAssistGesture", e);
            return false;
        }
    }

    public boolean activeServiceSupportsLaunchFromKeyguard() {
        try {
            return mVoiceInteractionManagerService != null
                    && mVoiceInteractionManagerService.activeServiceSupportsLaunchFromKeyguard();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call activeServiceSupportsLaunchFromKeyguard", e);
            return false;
        }
    }

    public ComponentName getActiveServiceComponentName() {
        try {
            if (mVoiceInteractionManagerService != null) {
                return mVoiceInteractionManagerService.getActiveServiceComponentName();
            } else {
                return null;
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call getActiveServiceComponentName", e);
            return null;
        }
    }

    public boolean isSessionRunning() {
        try {
            return mVoiceInteractionManagerService != null
                    && mVoiceInteractionManagerService.isSessionRunning();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call isSessionRunning", e);
            return false;
        }
    }

    public void hideCurrentSession() {
        try {
            if (mVoiceInteractionManagerService != null) {
                mVoiceInteractionManagerService.hideCurrentSession();
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call hideCurrentSession", e);
        }
    }

    public void onLockscreenShown() {
        try {
            if (mVoiceInteractionManagerService != null) {
                mVoiceInteractionManagerService.onLockscreenShown();
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call onLockscreenShown", e);
        }
    }

    public void registerVoiceInteractionSessionListener(IVoiceInteractionSessionListener listener) {
        try {
            if (mVoiceInteractionManagerService != null) {
                mVoiceInteractionManagerService.registerVoiceInteractionSessionListener(listener);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to register voice interaction listener", e);
        }
    }

    /**
     * Allows subscription to {@link android.service.voice.VisualQueryDetectionService} service
     * status.
     *
     * @param listener to receive visual service start/stop events.
     */
    public void subscribeVisualQueryRecognitionStatus(IVisualQueryRecognitionStatusListener
            listener) {
        try {
            if (mVoiceInteractionManagerService != null) {
                mVoiceInteractionManagerService.subscribeVisualQueryRecognitionStatus(listener);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to register visual query detection start listener", e);
        }
    }

    /**
     * Enables visual detection service.
     *
     * @param listener to receive visual attention gained/lost events.
     */
    public void enableVisualQueryDetection(
            IVisualQueryDetectionAttentionListener listener) {
        try {
            if (mVoiceInteractionManagerService != null) {
                mVoiceInteractionManagerService.enableVisualQueryDetection(listener);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to register visual query detection attention listener", e);
        }
    }

    /**
     * Disables visual query detection.
     */
    public void disableVisualQueryDetection() {
        try {
            if (mVoiceInteractionManagerService != null) {
                mVoiceInteractionManagerService.disableVisualQueryDetection();
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to register visual query detection attention listener", e);
        }
    }

    @UnsupportedAppUsage
    public ComponentName getAssistComponentForUser(int userId) {
        final String setting = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.ASSISTANT, userId);
        if (setting != null) {
            return ComponentName.unflattenFromString(setting);
        } else {
            return null;
        }
    }

    public static boolean isPreinstalledAssistant(Context context, ComponentName assistant) {
        if (assistant == null) {
            return false;
        }
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = context.getPackageManager().getApplicationInfo(
                    assistant.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return applicationInfo.isSystemApp() || applicationInfo.isUpdatedSystemApp();
    }

    public static boolean isDisclosureEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ASSIST_DISCLOSURE_ENABLED, 0) != 0;
    }

    /**
     * @return if the disclosure animation should trigger for the given assistant.
     *
     * Third-party assistants will always need to disclose, while the user can configure this for
     * pre-installed assistants.
     */
    public static boolean shouldDisclose(Context context, ComponentName assistant) {
        if (!allowDisablingAssistDisclosure(context)) {
            return true;
        }

        return isDisclosureEnabled(context) || !isPreinstalledAssistant(context, assistant);
    }

    public static boolean allowDisablingAssistDisclosure(Context context) {
        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_allowDisablingAssistDisclosure);
    }
}

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
import android.annotation.UnsupportedAppUsage;
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

    private final Context mContext;
    private final IVoiceInteractionManagerService mVoiceInteractionManagerService;

    @UnsupportedAppUsage
    public AssistUtils(Context context) {
        mContext = context;
        mVoiceInteractionManagerService = IVoiceInteractionManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
    }

    public boolean showSessionForActiveService(Bundle args, int sourceFlags,
            IVoiceInteractionSessionShowCallback showCallback, IBinder activityToken) {
        try {
            if (mVoiceInteractionManagerService != null) {
                return mVoiceInteractionManagerService.showSessionForActiveService(args,
                        sourceFlags, showCallback, activityToken);
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

    private static boolean isDisclosureEnabled(Context context) {
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

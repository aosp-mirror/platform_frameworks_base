/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.PowerWhitelistManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Holds the media button receiver, and also provides helper methods around it.
 */
final class MediaButtonReceiverHolder {
    public static final int COMPONENT_TYPE_INVALID = 0;
    public static final int COMPONENT_TYPE_BROADCAST = 1;
    public static final int COMPONENT_TYPE_ACTIVITY = 2;
    public static final int COMPONENT_TYPE_SERVICE = 3;

    @IntDef(value = {
            COMPONENT_TYPE_INVALID,
            COMPONENT_TYPE_BROADCAST,
            COMPONENT_TYPE_ACTIVITY,
            COMPONENT_TYPE_SERVICE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ComponentType {}

    private static final String TAG = "PendingIntentHolder";
    private static final boolean DEBUG_KEY_EVENT = MediaSessionService.DEBUG_KEY_EVENT;
    private static final String COMPONENT_NAME_USER_ID_DELIM = ",";
    // Filter apps regardless of the phone's locked/unlocked state.
    private static final int PACKAGE_MANAGER_COMMON_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

    /**
     * Denotes the duration during which a media button receiver will be exempted from
     * FGS-from-BG restriction and so will be allowed to start an FGS even if it is in the
     * background state while it receives a media key event.
     */
    private static final long FGS_STARTS_TEMP_ALLOWLIST_DURATION_MS = 10_000;

    private final int mUserId;
    private final PendingIntent mPendingIntent;
    private final ComponentName mComponentName;
    private final String mPackageName;
    @ComponentType
    private final int mComponentType;

    /**
     * Unflatten from string which is previously flattened string via flattenToString().
     * <p>
     * It's used to store and restore media button receiver across the boot, by keeping the intent's
     * component name to the persistent storage.
     *
     * @param mediaButtonReceiverInfo previously flattened string via flattenToString()
     * @return new instance if the string was valid. {@code null} otherwise.
     */
    public static MediaButtonReceiverHolder unflattenFromString(
            Context context, String mediaButtonReceiverInfo) {
        if (TextUtils.isEmpty(mediaButtonReceiverInfo)) {
            return null;
        }
        String[] tokens = mediaButtonReceiverInfo.split(COMPONENT_NAME_USER_ID_DELIM);
        if (tokens == null || (tokens.length != 2 && tokens.length != 3)) {
            return null;
        }
        ComponentName componentName = ComponentName.unflattenFromString(tokens[0]);
        if (componentName == null) {
            return null;
        }
        int userId = Integer.parseInt(tokens[1]);
        // Guess component type if the OS version is updated from the older version.
        int componentType = (tokens.length == 3)
                ?  Integer.parseInt(tokens[2])
                : getComponentType(context, componentName);
        return new MediaButtonReceiverHolder(userId, null, componentName, componentType);
    }

    /**
     * Creates a new instance.
     *
     * @param context context
     * @param userId userId
     * @param pendingIntent pending intent
     * @return Can be {@code null} if pending intent was null.
     */
    public static MediaButtonReceiverHolder create(Context context, int userId,
            PendingIntent pendingIntent, String sessionPackageName) {
        if (pendingIntent == null) {
            return null;
        }
        int componentType = getComponentType(pendingIntent);
        ComponentName componentName = getComponentName(pendingIntent, componentType);
        if (componentName != null) {
            return new MediaButtonReceiverHolder(userId, pendingIntent, componentName,
                    componentType);
        }

        // Failed to resolve target component for the pending intent. It's unlikely to be usable.
        // However, the pending intent would be still used, so setting the package name to the
        // package name of the session that set this pending intent.
        Log.w(TAG, "Unresolvable implicit intent is set, pi=" + pendingIntent);
        return new MediaButtonReceiverHolder(userId, pendingIntent, sessionPackageName);
    }

    public static MediaButtonReceiverHolder create(int userId, ComponentName broadcastReceiver) {
        return new MediaButtonReceiverHolder(userId, null, broadcastReceiver,
                COMPONENT_TYPE_BROADCAST);
    }

    private MediaButtonReceiverHolder(int userId, PendingIntent pendingIntent,
            ComponentName componentName, @ComponentType int componentType) {
        mUserId = userId;
        mPendingIntent = pendingIntent;
        mComponentName = componentName;
        mPackageName = componentName.getPackageName();
        mComponentType = componentType;
    }

    private MediaButtonReceiverHolder(int userId, PendingIntent pendingIntent, String packageName) {
        mUserId = userId;
        mPendingIntent = pendingIntent;
        mComponentName = null;
        mPackageName = packageName;
        mComponentType = COMPONENT_TYPE_INVALID;
    }

    /**
     * @return the user id
     */
    public int getUserId() {
        return mUserId;
    }

    /**
     * @return package name that the media button receiver would be sent to.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Sends the media key event to the media button receiver.
     * <p>
     * This prioritizes using use pending intent for sending media key event.
     *
     * @param context context to be used to call PendingIntent#send
     * @param keyEvent keyEvent to send
     * @param resultCode result code to be used to call PendingIntent#send
     *                   Ignored if there's no valid pending intent.
     * @param onFinishedListener callback to be used to get result of PendingIntent#send.
     *                           Ignored if there's no valid pending intent.
     * @param handler handler to be used to call onFinishedListener
     *                Ignored if there's no valid pending intent.
     * @see PendingIntent#send(Context, int, Intent, PendingIntent.OnFinished, Handler)
     */
    public boolean send(Context context, KeyEvent keyEvent, String callingPackageName,
            int resultCode, PendingIntent.OnFinished onFinishedListener, Handler handler) {
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        mediaButtonIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        // TODO: Find a way to also send PID/UID in secure way.
        mediaButtonIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, callingPackageName);

        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setTemporaryAppAllowlist(FGS_STARTS_TEMP_ALLOWLIST_DURATION_MS,
                PowerWhitelistManager.TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                PowerWhitelistManager.REASON_MEDIA_BUTTON, "");
        if (mPendingIntent != null) {
            if (DEBUG_KEY_EVENT) {
                Log.d(TAG, "Sending " + keyEvent + " to the last known PendingIntent "
                        + mPendingIntent);
            }
            try {
                mPendingIntent.send(
                        context, resultCode, mediaButtonIntent, onFinishedListener, handler,
                        /* requiredPermission= */ null, options.toBundle());
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "Error sending key event to media button receiver " + mPendingIntent, e);
                return false;
            }
        } else if (mComponentName != null) {
            if (DEBUG_KEY_EVENT) {
                Log.d(TAG, "Sending " + keyEvent + " to the restored intent "
                        + mComponentName + ", type=" + mComponentType);
            }
            mediaButtonIntent.setComponent(mComponentName);
            UserHandle userHandle = UserHandle.of(mUserId);
            try {
                switch (mComponentType) {
                    case COMPONENT_TYPE_ACTIVITY:
                        context.startActivityAsUser(mediaButtonIntent, userHandle);
                        break;
                    case COMPONENT_TYPE_SERVICE:
                        context.createContextAsUser(userHandle, 0).startForegroundService(
                                mediaButtonIntent);
                        break;
                    default:
                        // Legacy behavior for other cases.
                        context.sendBroadcastAsUser(mediaButtonIntent, userHandle,
                                /* receiverPermission= */ null, options.toBundle());
                }
            } catch (Exception e) {
                Log.w(TAG, "Error sending media button to the restored intent "
                        + mComponentName + ", type=" + mComponentType, e);
                return false;
            }
        } else {
            // Leave log, just in case.
            Log.e(TAG, "Shouldn't be happen -- pending intent or component name must be set");
            return false;
        }
        return true;
    }


    @Override
    public String toString() {
        if (mPendingIntent != null) {
            return "MBR {pi=" + mPendingIntent + ", type=" + mComponentType + "}";
        }
        return "Restored MBR {component=" + mComponentName + ", type=" + mComponentType + "}";
    }

    /**
     * @return flattened string. Can be empty string if the MBR is created with implicit intent.
     */
    public String flattenToString() {
        if (mComponentName == null) {
            // We don't know which component would receive the key event.
            return "";
        }
        return String.join(COMPONENT_NAME_USER_ID_DELIM,
                mComponentName.flattenToString(),
                String.valueOf(mUserId),
                String.valueOf(mComponentType));
    }

    @ComponentType
    private static int getComponentType(PendingIntent pendingIntent) {
        if (pendingIntent.isBroadcast()) {
            return COMPONENT_TYPE_BROADCAST;
        } else if (pendingIntent.isActivity()) {
            return COMPONENT_TYPE_ACTIVITY;
        } else if (pendingIntent.isForegroundService() || pendingIntent.isService()) {
            return COMPONENT_TYPE_SERVICE;
        }
        return COMPONENT_TYPE_INVALID;
    }

    /**
     * Gets the type of the component
     *
     * @param context context
     * @param componentName component name
     * @return A component type
     */
    @ComponentType
    private static int getComponentType(Context context, ComponentName componentName) {
        if (componentName == null) {
            return COMPONENT_TYPE_INVALID;
        }
        PackageManager pm = context.getPackageManager();
        try {
            ActivityInfo activityInfo = pm.getActivityInfo(componentName,
                    PACKAGE_MANAGER_COMMON_FLAGS | PackageManager.GET_ACTIVITIES);
            if (activityInfo != null) {
                return COMPONENT_TYPE_ACTIVITY;
            }
        } catch (PackageManager.NameNotFoundException e) {
        }
        try {
            ServiceInfo serviceInfo = pm.getServiceInfo(componentName,
                    PACKAGE_MANAGER_COMMON_FLAGS | PackageManager.GET_SERVICES);
            if (serviceInfo != null) {
                return COMPONENT_TYPE_SERVICE;
            }
        } catch (PackageManager.NameNotFoundException e) {
        }
        // Pick legacy behavior for BroadcastReceiver or unknown.
        return COMPONENT_TYPE_BROADCAST;
    }

    private static ComponentName getComponentName(PendingIntent pendingIntent, int componentType) {
        List<ResolveInfo> resolveInfos = null;
        switch (componentType) {
            case COMPONENT_TYPE_ACTIVITY:
                resolveInfos = pendingIntent.queryIntentComponents(
                        PACKAGE_MANAGER_COMMON_FLAGS
                                | PackageManager.MATCH_DEFAULT_ONLY /* Implicit intent receiver
                                should be set as default. Only needed for activity. */
                                | PackageManager.GET_ACTIVITIES);
                break;
            case COMPONENT_TYPE_SERVICE:
                resolveInfos = pendingIntent.queryIntentComponents(
                        PACKAGE_MANAGER_COMMON_FLAGS | PackageManager.GET_SERVICES);
                break;
            case COMPONENT_TYPE_BROADCAST:
                resolveInfos = pendingIntent.queryIntentComponents(
                        PACKAGE_MANAGER_COMMON_FLAGS | PackageManager.GET_RECEIVERS);
                break;
        }
        if (resolveInfos != null && !resolveInfos.isEmpty()) {
            return createComponentName(resolveInfos.get(0));
        }
        return null;
    }

    private static ComponentName createComponentName(ResolveInfo resolveInfo) {
        if (resolveInfo == null) {
            return null;
        }
        ComponentInfo componentInfo;
        // Code borrowed from ResolveInfo#getComponentInfo().
        if (resolveInfo.activityInfo != null) {
            componentInfo = resolveInfo.activityInfo;
        } else if (resolveInfo.serviceInfo != null) {
            componentInfo = resolveInfo.serviceInfo;
        } else {
            // We're not interested in content provider.
            return null;
        }
        // Code borrowed from ComponentInfo#getComponentName().
        try {
            return new ComponentName(componentInfo.packageName, componentInfo.name);
        } catch (IllegalArgumentException | NullPointerException e) {
            // This may be happen if resolveActivity() end up with matching multiple activities.
            // see PackageManager#resolveActivity().
            return null;
        }
    }
}

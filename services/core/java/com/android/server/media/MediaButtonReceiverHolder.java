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
            PendingIntent pendingIntent) {
        if (pendingIntent == null) {
            return null;
        }
        ComponentName componentName = (pendingIntent != null && pendingIntent.getIntent() != null)
                ? pendingIntent.getIntent().getComponent() : null;
        if (componentName != null) {
            // Explicit intent, where component name is in the PendingIntent.
            return new MediaButtonReceiverHolder(userId, pendingIntent, componentName,
                    getComponentType(context, componentName));
        }

        // Implicit intent, where component name isn't in the PendingIntent. Try resolve.
        PackageManager pm = context.getPackageManager();
        Intent intent = pendingIntent.getIntent();
        if ((componentName = resolveImplicitServiceIntent(pm, intent)) != null) {
            return new MediaButtonReceiverHolder(
                    userId, pendingIntent, componentName, COMPONENT_TYPE_SERVICE);
        } else if ((componentName = resolveManifestDeclaredBroadcastReceiverIntent(pm, intent))
                != null) {
            return new MediaButtonReceiverHolder(
                    userId, pendingIntent, componentName, COMPONENT_TYPE_BROADCAST);
        } else if ((componentName = resolveImplicitActivityIntent(pm, intent)) != null) {
            return new MediaButtonReceiverHolder(
                    userId, pendingIntent, componentName, COMPONENT_TYPE_ACTIVITY);
        }

        // Failed to resolve target component for the pending intent. It's unlikely to be usable.
        // However, the pending intent would be still used, just to follow the legacy behavior.
        Log.w(TAG, "Unresolvable implicit intent is set, pi=" + pendingIntent);
        String packageName = (pendingIntent != null && pendingIntent.getIntent() != null)
                ? pendingIntent.getIntent().getPackage() : null;
        return new MediaButtonReceiverHolder(userId, pendingIntent,
                packageName != null ? packageName : "");
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
        options.setBackgroundActivityStartsAllowed(true);
        if (mPendingIntent != null) {
            if (DEBUG_KEY_EVENT) {
                Log.d(TAG, "Sending " + keyEvent + " to the last known PendingIntent "
                        + mPendingIntent);
            }
            try {
                mPendingIntent.send(
                        context, resultCode, mediaButtonIntent, onFinishedListener, handler,
                        /* requiredPermission= */null, options.toBundle());
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
                        context.startForegroundServiceAsUser(mediaButtonIntent,
                                userHandle);
                        break;
                    default:
                        // Legacy behavior for other cases.
                        context.sendBroadcastAsUser(mediaButtonIntent, userHandle,
                                /* requiredPermission= */null, options.toBundle());
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
                    PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.GET_ACTIVITIES);
            if (activityInfo != null) {
                return COMPONENT_TYPE_ACTIVITY;
            }
        } catch (PackageManager.NameNotFoundException e) {
        }
        try {
            ServiceInfo serviceInfo = pm.getServiceInfo(componentName,
                    PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.GET_SERVICES);
            if (serviceInfo != null) {
                return COMPONENT_TYPE_SERVICE;
            }
        } catch (PackageManager.NameNotFoundException e) {
        }
        // Pick legacy behavior for BroadcastReceiver or unknown.
        return COMPONENT_TYPE_BROADCAST;
    }

    private static ComponentName resolveImplicitServiceIntent(PackageManager pm, Intent intent) {
        // Flag explanations.
        // - MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE:
        //     filter apps regardless of the phone's locked/unlocked state.
        // - GET_SERVICES: Return service
        return createComponentName(pm.resolveService(intent,
                PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                        | PackageManager.GET_SERVICES));
    }

    private static ComponentName resolveManifestDeclaredBroadcastReceiverIntent(
            PackageManager pm, Intent intent) {
        // Flag explanations.
        // - MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE:
        //     filter apps regardless of the phone's locked/unlocked state.
        List<ResolveInfo> resolveInfos = pm.queryBroadcastReceivers(intent,
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        return (resolveInfos != null && !resolveInfos.isEmpty())
                ? createComponentName(resolveInfos.get(0)) : null;
    }

    private static ComponentName resolveImplicitActivityIntent(PackageManager pm, Intent intent) {
        // Flag explanations.
        // - MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE:
        //     Filter apps regardless of the phone's locked/unlocked state.
        // - MATCH_DEFAULT_ONLY:
        //     Implicit intent receiver should be set as default. Only needed for activity.
        // - GET_ACTIVITIES: Return activity
        return createComponentName(pm.resolveActivity(intent,
                PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                        | PackageManager.MATCH_DEFAULT_ONLY
                        | PackageManager.GET_ACTIVITIES));
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

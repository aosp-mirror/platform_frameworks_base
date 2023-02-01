/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.pip.tv;

import static android.app.Notification.Action.SEMANTIC_ACTION_DELETE;
import static android.app.Notification.Action.SEMANTIC_ACTION_NONE;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ImageUtils;
import com.android.wm.shell.R;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipParamsChangedForwarder;
import com.android.wm.shell.pip.PipUtils;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * A notification that informs users that PiP is running and also provides PiP controls.
 * <p>Once it's created, it will manage the PiP notification UI by itself except for handling
 * configuration changes and user initiated expanded PiP toggling.
 */
public class TvPipNotificationController {
    private static final String TAG = "TvPipNotification";

    // Referenced in com.android.systemui.util.NotificationChannels.
    public static final String NOTIFICATION_CHANNEL = "TVPIP";
    private static final String NOTIFICATION_TAG = "TvPip";
    private static final String SYSTEMUI_PERMISSION = "com.android.systemui.permission.SELF";

    private static final String ACTION_SHOW_PIP_MENU =
            "com.android.wm.shell.pip.tv.notification.action.SHOW_PIP_MENU";
    private static final String ACTION_CLOSE_PIP =
            "com.android.wm.shell.pip.tv.notification.action.CLOSE_PIP";
    private static final String ACTION_MOVE_PIP =
            "com.android.wm.shell.pip.tv.notification.action.MOVE_PIP";
    private static final String ACTION_TOGGLE_EXPANDED_PIP =
            "com.android.wm.shell.pip.tv.notification.action.TOGGLE_EXPANDED_PIP";
    private static final String ACTION_FULLSCREEN =
            "com.android.wm.shell.pip.tv.notification.action.FULLSCREEN";

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final NotificationManager mNotificationManager;
    private final Notification.Builder mNotificationBuilder;
    private final ActionBroadcastReceiver mActionBroadcastReceiver;
    private final Handler mMainHandler;
    private Delegate mDelegate;
    private final TvPipBoundsState mTvPipBoundsState;

    private String mDefaultTitle;

    private final List<RemoteAction> mCustomActions = new ArrayList<>();
    private final List<RemoteAction> mMediaActions = new ArrayList<>();
    private RemoteAction mCustomCloseAction;

    private MediaSession.Token mMediaSessionToken;

    /** Package name for the application that owns PiP window. */
    private String mPackageName;

    private boolean mIsNotificationShown;
    private String mPipTitle;
    private String mPipSubtitle;

    private Bitmap mActivityIcon;

    public TvPipNotificationController(Context context, PipMediaController pipMediaController,
            PipParamsChangedForwarder pipParamsChangedForwarder, TvPipBoundsState tvPipBoundsState,
            Handler mainHandler) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mMainHandler = mainHandler;
        mTvPipBoundsState = tvPipBoundsState;

        mNotificationBuilder = new Notification.Builder(context, NOTIFICATION_CHANNEL)
                .setLocalOnly(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setShowWhen(true)
                .setSmallIcon(R.drawable.pip_icon)
                .setAllowSystemGeneratedContextualActions(false)
                .setContentIntent(createPendingIntent(context, ACTION_FULLSCREEN))
                .setDeleteIntent(getCloseAction().actionIntent)
                .extend(new Notification.TvExtender()
                        .setContentIntent(createPendingIntent(context, ACTION_SHOW_PIP_MENU))
                        .setDeleteIntent(createPendingIntent(context, ACTION_CLOSE_PIP)));

        mActionBroadcastReceiver = new ActionBroadcastReceiver();

        pipMediaController.addActionListener(this::onMediaActionsChanged);
        pipMediaController.addTokenListener(this::onMediaSessionTokenChanged);

        pipParamsChangedForwarder.addListener(
                new PipParamsChangedForwarder.PipParamsChangedCallback() {
                    @Override
                    public void onExpandedAspectRatioChanged(float ratio) {
                        updateExpansionState();
                    }

                    @Override
                    public void onActionsChanged(List<RemoteAction> actions,
                            RemoteAction closeAction) {
                        mCustomActions.clear();
                        mCustomActions.addAll(actions);
                        mCustomCloseAction = closeAction;
                        updateNotificationContent();
                    }

                    @Override
                    public void onTitleChanged(String title) {
                        mPipTitle = title;
                        updateNotificationContent();
                    }

                    @Override
                    public void onSubtitleChanged(String subtitle) {
                        mPipSubtitle = subtitle;
                        updateNotificationContent();
                    }
                });

        onConfigurationChanged(context);
    }

    void setDelegate(Delegate delegate) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: setDelegate(), delegate=%s",
                TAG, delegate);

        if (mDelegate != null) {
            throw new IllegalStateException(
                    "The delegate has already been set and should not change.");
        }
        if (delegate == null) {
            throw new IllegalArgumentException("The delegate must not be null.");
        }

        mDelegate = delegate;
    }

    void show(String packageName) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: show %s", TAG, packageName);
        if (mDelegate == null) {
            throw new IllegalStateException("Delegate is not set.");
        }

        mIsNotificationShown = true;
        mPackageName = packageName;
        mActivityIcon = getActivityIcon();
        mActionBroadcastReceiver.register();

        updateNotificationContent();
    }

    void dismiss() {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: dismiss()", TAG);

        mIsNotificationShown = false;
        mPackageName = null;
        mActionBroadcastReceiver.unregister();

        mNotificationManager.cancel(NOTIFICATION_TAG, SystemMessage.NOTE_TV_PIP);
    }

    private Notification.Action getToggleAction(boolean expanded) {
        if (expanded) {
            return createSystemAction(R.drawable.pip_ic_collapse,
                    R.string.pip_collapse, ACTION_TOGGLE_EXPANDED_PIP);
        } else {
            return createSystemAction(R.drawable.pip_ic_expand, R.string.pip_expand,
                    ACTION_TOGGLE_EXPANDED_PIP);
        }
    }

    private Notification.Action createSystemAction(int iconRes, int titleRes, String action) {
        Notification.Action.Builder builder = new Notification.Action.Builder(
                Icon.createWithResource(mContext, iconRes),
                mContext.getString(titleRes),
                createPendingIntent(mContext, action));
        builder.setContextual(true);
        return builder.build();
    }

    private void onMediaActionsChanged(List<RemoteAction> actions) {
        mMediaActions.clear();
        mMediaActions.addAll(actions);
        if (mCustomActions.isEmpty()) {
            updateNotificationContent();
        }
    }

    private void onMediaSessionTokenChanged(MediaSession.Token token) {
        mMediaSessionToken = token;
        updateNotificationContent();
    }

    private Notification.Action remoteToNotificationAction(RemoteAction action) {
        return remoteToNotificationAction(action, SEMANTIC_ACTION_NONE);
    }

    private Notification.Action remoteToNotificationAction(RemoteAction action,
            int semanticAction) {
        Notification.Action.Builder builder = new Notification.Action.Builder(action.getIcon(),
                action.getTitle(),
                action.getActionIntent());
        if (action.getContentDescription() != null) {
            Bundle extras = new Bundle();
            extras.putCharSequence(Notification.EXTRA_PICTURE_CONTENT_DESCRIPTION,
                    action.getContentDescription());
            builder.addExtras(extras);
        }
        builder.setSemanticAction(semanticAction);
        builder.setContextual(true);
        return builder.build();
    }

    private Notification.Action[] getNotificationActions() {
        final List<Notification.Action> actions = new ArrayList<>();

        // 1. Fullscreen
        actions.add(getFullscreenAction());
        // 2. Close
        actions.add(getCloseAction());
        // 3. App actions
        final List<RemoteAction> appActions =
                mCustomActions.isEmpty() ? mMediaActions : mCustomActions;
        for (RemoteAction appAction : appActions) {
            if (PipUtils.remoteActionsMatch(mCustomCloseAction, appAction)
                    || !appAction.isEnabled()) {
                continue;
            }
            actions.add(remoteToNotificationAction(appAction));
        }
        // 4. Move
        actions.add(getMoveAction());
        // 5. Toggle expansion (if expanded PiP enabled)
        if (mTvPipBoundsState.getDesiredTvExpandedAspectRatio() > 0
                && mTvPipBoundsState.isTvExpandedPipSupported()) {
            actions.add(getToggleAction(mTvPipBoundsState.isTvPipExpanded()));
        }
        return actions.toArray(new Notification.Action[0]);
    }

    private Notification.Action getCloseAction() {
        if (mCustomCloseAction == null) {
            return createSystemAction(R.drawable.pip_ic_close_white, R.string.pip_close,
                    ACTION_CLOSE_PIP);
        } else {
            return remoteToNotificationAction(mCustomCloseAction, SEMANTIC_ACTION_DELETE);
        }
    }

    private Notification.Action getFullscreenAction() {
        return createSystemAction(R.drawable.pip_ic_fullscreen_white,
                R.string.pip_fullscreen, ACTION_FULLSCREEN);
    }

    private Notification.Action getMoveAction() {
        return createSystemAction(R.drawable.pip_ic_move_white, R.string.pip_move,
                ACTION_MOVE_PIP);
    }

    /**
     * Called by {@link TvPipController} when the configuration is changed.
     */
    void onConfigurationChanged(Context context) {
        mDefaultTitle = context.getResources().getString(R.string.pip_notification_unknown_title);
        updateNotificationContent();
    }

    void updateExpansionState() {
        updateNotificationContent();
    }

    private void updateNotificationContent() {
        if (mPackageManager == null || !mIsNotificationShown) {
            return;
        }

        Notification.Action[] actions = getNotificationActions();
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: update(), title: %s, subtitle: %s, mediaSessionToken: %s, #actions: %s", TAG,
                getNotificationTitle(), mPipSubtitle, mMediaSessionToken, actions.length);
        for (Notification.Action action : actions) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: action: %s", TAG,
                    action.toString());
        }

        mNotificationBuilder
                .setWhen(System.currentTimeMillis())
                .setContentTitle(getNotificationTitle())
                .setContentText(mPipSubtitle)
                .setSubText(getApplicationLabel(mPackageName))
                .setActions(actions);
        setPipIcon();

        Bundle extras = new Bundle();
        extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, mMediaSessionToken);
        mNotificationBuilder.setExtras(extras);

        // TvExtender not recognized if not set last.
        mNotificationBuilder.extend(new Notification.TvExtender()
                .setContentIntent(createPendingIntent(mContext, ACTION_SHOW_PIP_MENU))
                .setDeleteIntent(createPendingIntent(mContext, ACTION_CLOSE_PIP)));
        mNotificationManager.notify(NOTIFICATION_TAG, SystemMessage.NOTE_TV_PIP,
                mNotificationBuilder.build());
    }

    private String getNotificationTitle() {
        if (!TextUtils.isEmpty(mPipTitle)) {
            return mPipTitle;
        }
        final String applicationTitle = getApplicationLabel(mPackageName);
        if (!TextUtils.isEmpty(applicationTitle)) {
            return applicationTitle;
        }
        return mDefaultTitle;
    }

    private String getApplicationLabel(String packageName) {
        try {
            final ApplicationInfo appInfo = mPackageManager.getApplicationInfo(packageName, 0);
            return mPackageManager.getApplicationLabel(appInfo).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void setPipIcon() {
        if (mActivityIcon != null) {
            mNotificationBuilder.setLargeIcon(mActivityIcon);
            return;
        }
        // Fallback: Picture-in-Picture icon
        mNotificationBuilder.setLargeIcon(Icon.createWithResource(mContext, R.drawable.pip_icon));
    }

    private Bitmap getActivityIcon() {
        if (mContext == null) return null;
        ComponentName componentName = PipUtils.getTopPipActivity(mContext).first;
        if (componentName == null) return null;

        Drawable drawable;
        try {
            drawable = mPackageManager.getActivityIcon(componentName);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        int width = mContext.getResources().getDimensionPixelSize(
                android.R.dimen.notification_large_icon_width);
        int height = mContext.getResources().getDimensionPixelSize(
                android.R.dimen.notification_large_icon_height);
        return ImageUtils.buildScaledBitmap(drawable, width, height, /* allowUpscaling */ true);
    }

    private static PendingIntent createPendingIntent(Context context, String action) {
        return PendingIntent.getBroadcast(context, 0,
                new Intent(action).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private class ActionBroadcastReceiver extends BroadcastReceiver {
        final IntentFilter mIntentFilter;
        {
            mIntentFilter = new IntentFilter();
            mIntentFilter.addAction(ACTION_CLOSE_PIP);
            mIntentFilter.addAction(ACTION_SHOW_PIP_MENU);
            mIntentFilter.addAction(ACTION_MOVE_PIP);
            mIntentFilter.addAction(ACTION_TOGGLE_EXPANDED_PIP);
            mIntentFilter.addAction(ACTION_FULLSCREEN);
        }
        boolean mRegistered = false;

        void register() {
            if (mRegistered) return;

            mContext.registerReceiverForAllUsers(this, mIntentFilter, SYSTEMUI_PERMISSION,
                    mMainHandler);
            mRegistered = true;
        }

        void unregister() {
            if (!mRegistered) return;

            mContext.unregisterReceiver(this);
            mRegistered = false;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: on(Broadcast)Receive(), action=%s", TAG, action);

            if (ACTION_SHOW_PIP_MENU.equals(action)) {
                mDelegate.showPictureInPictureMenu();
            } else if (ACTION_CLOSE_PIP.equals(action)) {
                mDelegate.closePip();
            } else if (ACTION_MOVE_PIP.equals(action)) {
                mDelegate.enterPipMovementMenu();
            } else if (ACTION_TOGGLE_EXPANDED_PIP.equals(action)) {
                mDelegate.togglePipExpansion();
            } else if (ACTION_FULLSCREEN.equals(action)) {
                mDelegate.movePipToFullscreen();
            }
        }
    }

    interface Delegate {
        void showPictureInPictureMenu();

        void closePip();

        void enterPipMovementMenu();

        void togglePipExpansion();

        void movePipToFullscreen();
    }
}

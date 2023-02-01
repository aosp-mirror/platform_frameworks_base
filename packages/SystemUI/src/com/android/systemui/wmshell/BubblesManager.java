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

package com.android.systemui.wmshell;

import static android.app.NotificationManager.BUBBLE_PREFERENCE_NONE;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_SELECTED;
import static android.provider.Settings.Secure.NOTIFICATION_BUBBLES;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED;
import static android.service.notification.NotificationStats.DISMISSAL_BUBBLE;
import static android.service.notification.NotificationStats.DISMISS_SENTIMENT_NEUTRAL;

import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.ZenModeConfig;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.model.SysUiState;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.notification.NotificationChannelHelper;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.phone.StatusBarWindowCallback;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.wm.shell.bubbles.Bubble;
import com.android.wm.shell.bubbles.BubbleEntry;
import com.android.wm.shell.bubbles.Bubbles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The SysUi side bubbles manager which communicate with other SysUi components.
 */
@SysUISingleton
public class BubblesManager {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubblesManager" : TAG_BUBBLES;

    private final Context mContext;
    private final Bubbles mBubbles;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final ShadeController mShadeController;
    private final IStatusBarService mBarService;
    private final INotificationManager mNotificationManager;
    private final IDreamManager mDreamManager;
    private final NotificationVisibilityProvider mVisibilityProvider;
    private final NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    private final NotificationLockscreenUserManager mNotifUserManager;
    private final CommonNotifCollection mCommonNotifCollection;
    private final NotifPipeline mNotifPipeline;
    private final Executor mSysuiMainExecutor;

    private final Bubbles.SysuiProxy mSysuiProxy;
    // TODO (b/145659174): allow for multiple callbacks to support the "shadow" new notif pipeline
    private final List<NotifCallback> mCallbacks = new ArrayList<>();
    private final StatusBarWindowCallback mStatusBarWindowCallback;

    /**
     * Creates {@link BubblesManager}, returns {@code null} if Optional {@link Bubbles} not present
     * which means bubbles feature not support.
     */
    @Nullable
    public static BubblesManager create(Context context,
            Optional<Bubbles> bubblesOptional,
            NotificationShadeWindowController notificationShadeWindowController,
            KeyguardStateController keyguardStateController,
            ShadeController shadeController,
            @Nullable IStatusBarService statusBarService,
            INotificationManager notificationManager,
            IDreamManager dreamManager,
            NotificationVisibilityProvider visibilityProvider,
            NotificationInterruptStateProvider interruptionStateProvider,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager notifUserManager,
            CommonNotifCollection notifCollection,
            NotifPipeline notifPipeline,
            SysUiState sysUiState,
            Executor sysuiMainExecutor) {
        if (bubblesOptional.isPresent()) {
            return new BubblesManager(context,
                    bubblesOptional.get(),
                    notificationShadeWindowController,
                    keyguardStateController,
                    shadeController,
                    statusBarService,
                    notificationManager,
                    dreamManager,
                    visibilityProvider,
                    interruptionStateProvider,
                    zenModeController,
                    notifUserManager,
                    notifCollection,
                    notifPipeline,
                    sysUiState,
                    sysuiMainExecutor);
        } else {
            return null;
        }
    }

    @VisibleForTesting
    BubblesManager(Context context,
            Bubbles bubbles,
            NotificationShadeWindowController notificationShadeWindowController,
            KeyguardStateController keyguardStateController,
            ShadeController shadeController,
            @Nullable IStatusBarService statusBarService,
            INotificationManager notificationManager,
            IDreamManager dreamManager,
            NotificationVisibilityProvider visibilityProvider,
            NotificationInterruptStateProvider interruptionStateProvider,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager notifUserManager,
            CommonNotifCollection notifCollection,
            NotifPipeline notifPipeline,
            SysUiState sysUiState,
            Executor sysuiMainExecutor) {
        mContext = context;
        mBubbles = bubbles;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mShadeController = shadeController;
        mNotificationManager = notificationManager;
        mDreamManager = dreamManager;
        mVisibilityProvider = visibilityProvider;
        mNotificationInterruptStateProvider = interruptionStateProvider;
        mNotifUserManager = notifUserManager;
        mCommonNotifCollection = notifCollection;
        mNotifPipeline = notifPipeline;
        mSysuiMainExecutor = sysuiMainExecutor;

        mBarService = statusBarService == null
                ? IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE))
                : statusBarService;

        setupNotifPipeline();

        keyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onKeyguardShowingChanged() {
                boolean isUnlockedShade = !keyguardStateController.isShowing()
                        && !isDreamingOrInPreview();
                bubbles.onStatusBarStateChanged(isUnlockedShade);
            }
        });

        zenModeController.addCallback(new ZenModeController.Callback() {
            @Override
            public void onZenChanged(int zen) {
                mBubbles.onZenStateChanged();
            }

            @Override
            public void onConfigChanged(ZenModeConfig config) {
                mBubbles.onZenStateChanged();
            }
        });

        notifUserManager.addUserChangedListener(
                new NotificationLockscreenUserManager.UserChangedListener() {
                    @Override
                    public void onUserChanged(int userId) {
                        mBubbles.onUserChanged(userId);
                    }

                    @Override
                    public void onCurrentProfilesChanged(SparseArray<UserInfo> currentProfiles) {
                        mBubbles.onCurrentProfilesChanged(currentProfiles);
                    }

                    @Override
                    public void onUserRemoved(int userId) {
                        mBubbles.onUserRemoved(userId);
                    }

                });

        // Store callback in a field so it won't get GC'd
        mStatusBarWindowCallback =
                (keyguardShowing, keyguardOccluded, bouncerShowing, isDozing, panelExpanded) ->
                        mBubbles.onNotificationPanelExpandedChanged(panelExpanded);
        notificationShadeWindowController.registerCallback(mStatusBarWindowCallback);

        mSysuiProxy = new Bubbles.SysuiProxy() {
            @Override
            public void isNotificationPanelExpand(Consumer<Boolean> callback) {
                sysuiMainExecutor.execute(() -> {
                    callback.accept(mNotificationShadeWindowController.getPanelExpanded());
                });
            }

            @Override
            public void getPendingOrActiveEntry(String key, Consumer<BubbleEntry> callback) {
                sysuiMainExecutor.execute(() -> {
                    final NotificationEntry entry = mCommonNotifCollection.getEntry(key);
                    callback.accept(entry == null ? null : notifToBubbleEntry(entry));
                });
            }

            @Override
            public void getShouldRestoredEntries(Set<String> savedBubbleKeys,
                    Consumer<List<BubbleEntry>> callback) {
                sysuiMainExecutor.execute(() -> {
                    List<BubbleEntry> result = new ArrayList<>();
                    final Collection<NotificationEntry> activeEntries =
                            mCommonNotifCollection.getAllNotifs();
                    for (NotificationEntry entry : activeEntries) {
                        if (mNotifUserManager.isCurrentProfile(entry.getSbn().getUserId())
                                && savedBubbleKeys.contains(entry.getKey())
                                && mNotificationInterruptStateProvider.shouldBubbleUp(entry)
                                && entry.isBubble()) {
                            result.add(notifToBubbleEntry(entry));
                        }
                    }
                    callback.accept(result);
                });
            }

            @Override
            public void setNotificationInterruption(String key) {
                sysuiMainExecutor.execute(() -> {
                    final NotificationEntry entry = mCommonNotifCollection.getEntry(key);
                    if (entry != null
                            && entry.getImportance() >= NotificationManager.IMPORTANCE_HIGH) {
                        entry.setInterruption();
                    }
                });
            }

            @Override
            public void requestNotificationShadeTopUi(boolean requestTopUi, String componentTag) {
                sysuiMainExecutor.execute(() -> {
                    mNotificationShadeWindowController.setRequestTopUi(requestTopUi, componentTag);
                });
            }

            @Override
            public void notifyRemoveNotification(String key, int reason) {
                sysuiMainExecutor.execute(() -> {
                    final NotificationEntry entry = mCommonNotifCollection.getEntry(key);
                    if (entry != null) {
                        for (NotifCallback cb : mCallbacks) {
                            cb.removeNotification(entry, getDismissedByUserStats(entry, true),
                                    reason);
                        }
                    }
                });
            }

            @Override
            public void notifyInvalidateNotifications(String reason) {
                sysuiMainExecutor.execute(() -> {
                    for (NotifCallback cb : mCallbacks) {
                        cb.invalidateNotifications(reason);
                    }
                });
            }

            @Override
            public void updateNotificationBubbleButton(String key) {
                sysuiMainExecutor.execute(() -> {
                    final NotificationEntry entry = mCommonNotifCollection.getEntry(key);
                    if (entry != null && entry.getRow() != null) {
                        entry.getRow().updateBubbleButton();
                    }
                });
            }

            @Override
            public void onStackExpandChanged(boolean shouldExpand) {
                sysuiMainExecutor.execute(() -> {
                    sysUiState.setFlag(QuickStepContract.SYSUI_STATE_BUBBLES_EXPANDED, shouldExpand)
                            .commitUpdate(mContext.getDisplayId());
                    if (!shouldExpand) {
                        sysUiState.setFlag(
                                QuickStepContract.SYSUI_STATE_BUBBLES_MANAGE_MENU_EXPANDED,
                                false).commitUpdate(mContext.getDisplayId());
                    }
                });
            }

            @Override
            public void onManageMenuExpandChanged(boolean menuExpanded) {
                sysuiMainExecutor.execute(() -> {
                    sysUiState.setFlag(QuickStepContract.SYSUI_STATE_BUBBLES_MANAGE_MENU_EXPANDED,
                            menuExpanded).commitUpdate(mContext.getDisplayId());
                });
            }


            @Override
            public void onUnbubbleConversation(String key) {
                sysuiMainExecutor.execute(() -> {
                    final NotificationEntry entry = mCommonNotifCollection.getEntry(key);
                    if (entry != null) {
                        onUserChangedBubble(entry, false /* shouldBubble */);
                    }
                });
            }
        };
        mBubbles.setSysuiProxy(mSysuiProxy);
    }

    private boolean isDreamingOrInPreview() {
        try {
            return mDreamManager.isDreamingOrInPreview();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query dream manager.", e);
            return false;
        }
    }

    private void setupNotifPipeline() {
        mNotifPipeline.addCollectionListener(new NotifCollectionListener() {
            @Override
            public void onEntryAdded(NotificationEntry entry) {
                BubblesManager.this.onEntryAdded(entry);
            }

            @Override
            public void onEntryUpdated(NotificationEntry entry, boolean fromSystem) {
                BubblesManager.this.onEntryUpdated(entry, fromSystem);
            }

            @Override
            public void onEntryRemoved(NotificationEntry entry,
                    @NotifCollection.CancellationReason int reason) {
                if (reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL) {
                    BubblesManager.this.onEntryRemoved(entry);
                }
            }

            @Override
            public void onRankingUpdate(RankingMap rankingMap) {
                BubblesManager.this.onRankingUpdate(rankingMap);
            }

            @Override
            public void onNotificationChannelModified(
                    String pkgName,
                    UserHandle user,
                    NotificationChannel channel,
                    int modificationType) {
                BubblesManager.this.onNotificationChannelModified(
                        pkgName,
                        user,
                        channel,
                        modificationType);
            }
        });
    }

    void onEntryAdded(NotificationEntry entry) {
        if (mNotificationInterruptStateProvider.shouldBubbleUp(entry)
                && entry.isBubble()) {
            mBubbles.onEntryAdded(notifToBubbleEntry(entry));
        }
    }

    void onEntryUpdated(NotificationEntry entry, boolean fromSystem) {
        boolean shouldBubble = mNotificationInterruptStateProvider.shouldBubbleUp(entry);
        mBubbles.onEntryUpdated(notifToBubbleEntry(entry),
                shouldBubble, fromSystem);
    }

    void onEntryRemoved(NotificationEntry entry) {
        mBubbles.onEntryRemoved(notifToBubbleEntry(entry));
    }

    void onRankingUpdate(RankingMap rankingMap) {
        String[] orderedKeys = rankingMap.getOrderedKeys();
        HashMap<String, Pair<BubbleEntry, Boolean>> pendingOrActiveNotif = new HashMap<>();
        for (int i = 0; i < orderedKeys.length; i++) {
            String key = orderedKeys[i];
            final NotificationEntry entry = mCommonNotifCollection.getEntry(key);
            BubbleEntry bubbleEntry = entry != null
                    ? notifToBubbleEntry(entry)
                    : null;
            boolean shouldBubbleUp = entry != null
                    ? mNotificationInterruptStateProvider.shouldBubbleUp(entry)
                    : false;
            pendingOrActiveNotif.put(key, new Pair<>(bubbleEntry, shouldBubbleUp));
        }
        mBubbles.onRankingUpdated(rankingMap, pendingOrActiveNotif);
    }

    void onNotificationChannelModified(
            String pkg,
            UserHandle user,
            NotificationChannel channel,
            int modificationType) {
        mBubbles.onNotificationChannelModified(pkg, user, channel, modificationType);
    }

    private DismissedByUserStats getDismissedByUserStats(
            NotificationEntry entry,
            boolean isVisible) {
        return new DismissedByUserStats(
                DISMISSAL_BUBBLE,
                DISMISS_SENTIMENT_NEUTRAL,
                mVisibilityProvider.obtain(entry, isVisible));
    }

    /**
     * We intercept notification entries (including group summaries) dismissed by the user when
     * there is an active bubble associated with it. We do this so that developers can still
     * cancel it (and hence the bubbles associated with it).
     *
     * @return true if we want to intercept the dismissal of the entry, else false.
     * @see Bubbles#handleDismissalInterception(BubbleEntry, List, IntConsumer, Executor)
     */
    public boolean handleDismissalInterception(NotificationEntry entry) {
        if (entry == null) {
            return false;
        }

        List<NotificationEntry> children = entry.getAttachedNotifChildren();
        List<BubbleEntry> bubbleChildren = null;
        if (children != null) {
            bubbleChildren = new ArrayList<>();
            for (int i = 0; i < children.size(); i++) {
                bubbleChildren.add(notifToBubbleEntry(children.get(i)));
            }
        }

        return mBubbles.handleDismissalInterception(notifToBubbleEntry(entry), bubbleChildren,
                // TODO : b/171847985 should re-work on notification side to make this more clear.
                (int i) -> {
                    if (i >= 0) {
                        for (NotifCallback cb : mCallbacks) {
                            cb.removeNotification(children.get(i),
                                    getDismissedByUserStats(children.get(i), true),
                                    REASON_GROUP_SUMMARY_CANCELED);
                        }
                    } else {
                        for (NotifCallback cb : mCallbacks) {
                            cb.removeNotification(entry, getDismissedByUserStats(entry, true),
                                    REASON_GROUP_SUMMARY_CANCELED);
                        }
                    }
                }, mSysuiMainExecutor);
    }

    /**
     * Request the stack expand if needed, then select the specified Bubble as current.
     * If no bubble exists for this entry, one is created.
     *
     * @param entry the notification for the bubble to be selected
     */
    public void expandStackAndSelectBubble(NotificationEntry entry) {
        mBubbles.expandStackAndSelectBubble(notifToBubbleEntry(entry));
    }

    /**
     * Request the stack expand if needed, then select the specified Bubble as current.
     *
     * @param bubble the bubble to be selected
     */
    public void expandStackAndSelectBubble(Bubble bubble) {
        mBubbles.expandStackAndSelectBubble(bubble);
    }

    /**
     * @return a bubble that matches the provided shortcutId, if one exists.
     */
    public Bubble getBubbleWithShortcutId(String shortcutId) {
        return mBubbles.getBubbleWithShortcutId(shortcutId);
    }

    /** See {@link NotifCallback}. */
    public void addNotifCallback(NotifCallback callback) {
        mCallbacks.add(callback);
    }

    /**
     * When a notification is set as important, make it a bubble and expand the stack if
     * it can bubble.
     *
     * @param entry the important notification.
     */
    public void onUserSetImportantConversation(NotificationEntry entry) {
        if (entry.getBubbleMetadata() == null) {
            // No bubble metadata, nothing to do.
            return;
        }
        try {
            int flags = Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
            mBarService.onNotificationBubbleChanged(entry.getKey(), true, flags);
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage());
        }
        mShadeController.collapsePanel(true);
        if (entry.getRow() != null) {
            entry.getRow().updateBubbleButton();
        }
    }

    /**
     * Called when a user has indicated that an active notification should be shown as a bubble.
     * <p>
     * This method will collapse the shade, create the bubble without a flyout or dot, and suppress
     * the notification from appearing in the shade.
     *
     * @param entry        the notification to change bubble state for.
     * @param shouldBubble whether the notification should show as a bubble or not.
     */
    public void onUserChangedBubble(@NonNull final NotificationEntry entry, boolean shouldBubble) {
        NotificationChannel channel = entry.getChannel();
        final String appPkg = entry.getSbn().getPackageName();
        final int appUid = entry.getSbn().getUid();
        if (channel == null || appPkg == null) {
            return;
        }

        entry.setFlagBubble(shouldBubble);

        // Update the state in NotificationManagerService
        try {
            int flags = Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
            flags |= Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE;
            mBarService.onNotificationBubbleChanged(entry.getKey(), shouldBubble, flags);
        } catch (RemoteException e) {
        }

        // Change the settings
        channel = NotificationChannelHelper.createConversationChannelIfNeeded(mContext,
                mNotificationManager, entry, channel);
        channel.setAllowBubbles(shouldBubble);
        try {
            int currentPref = mNotificationManager.getBubblePreferenceForPackage(appPkg, appUid);
            if (shouldBubble && currentPref == BUBBLE_PREFERENCE_NONE) {
                mNotificationManager.setBubblesAllowed(appPkg, appUid, BUBBLE_PREFERENCE_SELECTED);
            }
            mNotificationManager.updateNotificationChannelForPackage(appPkg, appUid, channel);
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage());
        }

        if (shouldBubble) {
            mShadeController.collapsePanel(true);
            if (entry.getRow() != null) {
                entry.getRow().updateBubbleButton();
            }
        }
    }

    /** Checks whether bubbles are enabled for this user, handles negative userIds. */
    public static boolean areBubblesEnabled(@NonNull Context context, @NonNull UserHandle user) {
        if (user.getIdentifier() < 0) {
            return Settings.Secure.getInt(context.getContentResolver(),
                    NOTIFICATION_BUBBLES, 0) == 1;
        } else {
            return Settings.Secure.getIntForUser(context.getContentResolver(),
                    NOTIFICATION_BUBBLES, 0, user.getIdentifier()) == 1;
        }
    }

    static BubbleEntry notifToBubbleEntry(NotificationEntry e) {
        return new BubbleEntry(e.getSbn(), e.getRanking(), e.isDismissable(),
                e.shouldSuppressNotificationDot(), e.shouldSuppressNotificationList(),
                e.shouldSuppressPeek());
    }

    /**
     * Callback for when the BubbleController wants to interact with the notification pipeline to:
     * - Remove a previously bubbled notification
     * - Update the notification shade since bubbled notification should/shouldn't be showing
     */
    public interface NotifCallback {
        /**
         * Called when a bubbled notification that was hidden from the shade is now being removed
         * This can happen when an app cancels a bubbled notification or when the user dismisses a
         * bubble.
         */
        void removeNotification(@NonNull NotificationEntry entry,
                @NonNull DismissedByUserStats stats, int reason);

        /**
         * Called when a bubbled notification has changed whether it should be
         * filtered from the shade.
         */
        void invalidateNotifications(@NonNull String reason);
    }
}

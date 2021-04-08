/* //device/java/android/android/app/INotificationManager.aidl
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.app;

import android.app.ITransientNotification;
import android.app.ITransientNotificationCallback;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationHistory;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.Adjustment;
import android.service.notification.Condition;
import android.service.notification.IConditionListener;
import android.service.notification.IConditionProvider;
import android.service.notification.INotificationListener;
import android.service.notification.NotificationListenerFilter;
import android.service.notification.StatusBarNotification;
import android.app.AutomaticZenRule;
import android.service.notification.ZenModeConfig;

/** {@hide} */
interface INotificationManager
{
    @UnsupportedAppUsage
    void cancelAllNotifications(String pkg, int userId);

    void clearData(String pkg, int uid, boolean fromApp);
    void enqueueTextToast(String pkg, IBinder token, CharSequence text, int duration, int displayId, @nullable ITransientNotificationCallback callback);
    void enqueueToast(String pkg, IBinder token, ITransientNotification callback, int duration, int displayId);
    void cancelToast(String pkg, IBinder token);
    void finishToken(String pkg, IBinder token);

    void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id,
            in Notification notification, int userId);
    @UnsupportedAppUsage
    void cancelNotificationWithTag(String pkg, String opPkg, String tag, int id, int userId);

    void setShowBadge(String pkg, int uid, boolean showBadge);
    boolean canShowBadge(String pkg, int uid);
    boolean hasSentValidMsg(String pkg, int uid);
    boolean isInInvalidMsgState(String pkg, int uid);
    boolean hasUserDemotedInvalidMsgApp(String pkg, int uid);
    void setInvalidMsgAppDemoted(String pkg, int uid, boolean isDemoted);
    void setNotificationsEnabledForPackage(String pkg, int uid, boolean enabled);
    /**
     * Updates the notification's enabled state. Additionally locks importance for all of the
     * notifications belonging to the app, such that future notifications aren't reconsidered for
     * blocking helper.
     */
    void setNotificationsEnabledWithImportanceLockForPackage(String pkg, int uid, boolean enabled);

    @UnsupportedAppUsage
    boolean areNotificationsEnabledForPackage(String pkg, int uid);
    boolean areNotificationsEnabled(String pkg);
    int getPackageImportance(String pkg);

    List<String> getAllowedAssistantAdjustments(String pkg);
    void allowAssistantAdjustment(String adjustmentType);
    void disallowAssistantAdjustment(String adjustmentType);

    boolean shouldHideSilentStatusIcons(String callingPkg);
    void setHideSilentStatusIcons(boolean hide);

    void setBubblesAllowed(String pkg, int uid, int bubblePreference);
    boolean areBubblesAllowed(String pkg);
    boolean areBubblesEnabled(in UserHandle user);
    int getBubblePreferenceForPackage(String pkg, int uid);

    void createNotificationChannelGroups(String pkg, in ParceledListSlice channelGroupList);
    void createNotificationChannels(String pkg, in ParceledListSlice channelsList);
    void createNotificationChannelsForPackage(String pkg, int uid, in ParceledListSlice channelsList);
    ParceledListSlice getConversations(boolean onlyImportant);
    ParceledListSlice getConversationsForPackage(String pkg, int uid);
    ParceledListSlice getNotificationChannelGroupsForPackage(String pkg, int uid, boolean includeDeleted);
    NotificationChannelGroup getNotificationChannelGroupForPackage(String groupId, String pkg, int uid);
    NotificationChannelGroup getPopulatedNotificationChannelGroupForPackage(String pkg, int uid, String groupId, boolean includeDeleted);
    void updateNotificationChannelGroupForPackage(String pkg, int uid, in NotificationChannelGroup group);
    void updateNotificationChannelForPackage(String pkg, int uid, in NotificationChannel channel);
    void unlockNotificationChannel(String pkg, int uid, String channelId);
    void unlockAllNotificationChannels();
    NotificationChannel getNotificationChannel(String callingPkg, int userId, String pkg, String channelId);
    NotificationChannel getConversationNotificationChannel(String callingPkg, int userId, String pkg, String channelId, boolean returnParentIfNoConversationChannel, String conversationId);
    void createConversationNotificationChannelForPackage(String pkg, int uid, in NotificationChannel parentChannel, String conversationId);
    NotificationChannel getNotificationChannelForPackage(String pkg, int uid, String channelId, String conversationId, boolean includeDeleted);
    void deleteNotificationChannel(String pkg, String channelId);
    ParceledListSlice getNotificationChannels(String callingPkg, String targetPkg, int userId);
    ParceledListSlice getNotificationChannelsForPackage(String pkg, int uid, boolean includeDeleted);
    int getNumNotificationChannelsForPackage(String pkg, int uid, boolean includeDeleted);
    int getDeletedChannelCount(String pkg, int uid);
    int getBlockedChannelCount(String pkg, int uid);
    void deleteNotificationChannelGroup(String pkg, String channelGroupId);
    NotificationChannelGroup getNotificationChannelGroup(String pkg, String channelGroupId);
    ParceledListSlice getNotificationChannelGroups(String pkg);
    boolean onlyHasDefaultChannel(String pkg, int uid);
    int getBlockedAppCount(int userId);
    boolean areChannelsBypassingDnd();
    int getAppsBypassingDndCount(int uid);
    ParceledListSlice getNotificationChannelsBypassingDnd(String pkg, int userId);
    boolean isPackagePaused(String pkg);
    void deleteNotificationHistoryItem(String pkg, int uid, long postedTime);

    void silenceNotificationSound();

    // TODO: Remove this when callers have been migrated to the equivalent
    // INotificationListener method.
    @UnsupportedAppUsage
    StatusBarNotification[] getActiveNotifications(String callingPkg);
    StatusBarNotification[] getActiveNotificationsWithAttribution(String callingPkg,
            String callingAttributionTag);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    StatusBarNotification[] getHistoricalNotifications(String callingPkg, int count, boolean includeSnoozed);
    StatusBarNotification[] getHistoricalNotificationsWithAttribution(String callingPkg,
            String callingAttributionTag, int count, boolean includeSnoozed);

    NotificationHistory getNotificationHistory(String callingPkg, String callingAttributionTag);

    void registerListener(in INotificationListener listener, in ComponentName component, int userid);
    void unregisterListener(in INotificationListener listener, int userid);

    void cancelNotificationFromListener(in INotificationListener token, String pkg, String tag, int id);
    void cancelNotificationsFromListener(in INotificationListener token, in String[] keys);

    void snoozeNotificationUntilContextFromListener(in INotificationListener token, String key, String snoozeCriterionId);
    void snoozeNotificationUntilFromListener(in INotificationListener token, String key, long until);

    void requestBindListener(in ComponentName component);
    void requestUnbindListener(in INotificationListener token);
    void requestBindProvider(in ComponentName component);
    void requestUnbindProvider(in IConditionProvider token);

    void setNotificationsShownFromListener(in INotificationListener token, in String[] keys);

    ParceledListSlice getActiveNotificationsFromListener(in INotificationListener token, in String[] keys, int trim);
    ParceledListSlice getSnoozedNotificationsFromListener(in INotificationListener token, int trim);
    void clearRequestedListenerHints(in INotificationListener token);
    void requestHintsFromListener(in INotificationListener token, int hints);
    int getHintsFromListener(in INotificationListener token);
    void requestInterruptionFilterFromListener(in INotificationListener token, int interruptionFilter);
    int getInterruptionFilterFromListener(in INotificationListener token);
    void setOnNotificationPostedTrimFromListener(in INotificationListener token, int trim);
    void setInterruptionFilter(String pkg, int interruptionFilter);

    void updateNotificationChannelGroupFromPrivilegedListener(in INotificationListener token, String pkg, in UserHandle user, in NotificationChannelGroup group);
    void updateNotificationChannelFromPrivilegedListener(in INotificationListener token, String pkg, in UserHandle user, in NotificationChannel channel);
    ParceledListSlice getNotificationChannelsFromPrivilegedListener(in INotificationListener token, String pkg, in UserHandle user);
    ParceledListSlice getNotificationChannelGroupsFromPrivilegedListener(in INotificationListener token, String pkg, in UserHandle user);

    void applyEnqueuedAdjustmentFromAssistant(in INotificationListener token, in Adjustment adjustment);
    void applyAdjustmentFromAssistant(in INotificationListener token, in Adjustment adjustment);
    void applyAdjustmentsFromAssistant(in INotificationListener token, in List<Adjustment> adjustments);
    void unsnoozeNotificationFromAssistant(in INotificationListener token, String key);
    void unsnoozeNotificationFromSystemListener(in INotificationListener token, String key);

    ComponentName getEffectsSuppressor();
    boolean matchesCallFilter(in Bundle extras);
    boolean isSystemConditionProviderEnabled(String path);

    boolean isNotificationListenerAccessGranted(in ComponentName listener);
    boolean isNotificationListenerAccessGrantedForUser(in ComponentName listener, int userId);
    boolean isNotificationAssistantAccessGranted(in ComponentName assistant);
    void setNotificationListenerAccessGranted(in ComponentName listener, boolean enabled, boolean userSet);
    void setNotificationAssistantAccessGranted(in ComponentName assistant, boolean enabled);
    void setNotificationListenerAccessGrantedForUser(in ComponentName listener, int userId, boolean enabled, boolean userSet);
    void setNotificationAssistantAccessGrantedForUser(in ComponentName assistant, int userId, boolean enabled);
    List<String> getEnabledNotificationListenerPackages();
    List<ComponentName> getEnabledNotificationListeners(int userId);
    ComponentName getAllowedNotificationAssistantForUser(int userId);
    ComponentName getAllowedNotificationAssistant();
    boolean hasEnabledNotificationListener(String packageName, int userId);

    @UnsupportedAppUsage
    int getZenMode();
    @UnsupportedAppUsage
    ZenModeConfig getZenModeConfig();
    NotificationManager.Policy getConsolidatedNotificationPolicy();
    oneway void setZenMode(int mode, in Uri conditionId, String reason);
    oneway void notifyConditions(String pkg, in IConditionProvider provider, in Condition[] conditions);
    boolean isNotificationPolicyAccessGranted(String pkg);
    NotificationManager.Policy getNotificationPolicy(String pkg);
    void setNotificationPolicy(String pkg, in NotificationManager.Policy policy);
    boolean isNotificationPolicyAccessGrantedForPackage(String pkg);
    void setNotificationPolicyAccessGranted(String pkg, boolean granted);
    void setNotificationPolicyAccessGrantedForUser(String pkg, int userId, boolean granted);
    AutomaticZenRule getAutomaticZenRule(String id);
    List<ZenModeConfig.ZenRule> getZenRules();
    String addAutomaticZenRule(in AutomaticZenRule automaticZenRule);
    boolean updateAutomaticZenRule(String id, in AutomaticZenRule automaticZenRule);
    boolean removeAutomaticZenRule(String id);
    boolean removeAutomaticZenRules(String packageName);
    int getRuleInstanceCount(in ComponentName owner);
    void setAutomaticZenRuleState(String id, in Condition condition);

    byte[] getBackupPayload(int user);
    void applyRestore(in byte[] payload, int user);

    ParceledListSlice getAppActiveNotifications(String callingPkg, int userId);

    void setNotificationDelegate(String callingPkg, String delegate);
    String getNotificationDelegate(String callingPkg);
    boolean canNotifyAsPackage(String callingPkg, String targetPkg, int userId);

    void setPrivateNotificationsAllowed(boolean allow);
    boolean getPrivateNotificationsAllowed();

    long pullStats(long startNs, int report, boolean doAgg, out List<ParcelFileDescriptor> stats);

    NotificationListenerFilter getListenerFilter(in ComponentName cn, int userId);
    void setListenerFilter(in ComponentName cn, int userId, in NotificationListenerFilter nlf);
    void migrateNotificationFilter(in INotificationListener token, int defaultTypes, in List<String> disallowedPkgs);

    void setToastRateLimitingEnabled(boolean enable);
}

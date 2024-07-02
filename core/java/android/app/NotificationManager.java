/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.annotation.WorkerThread;
import android.app.Notification.Builder;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.Adjustment;
import android.service.notification.Condition;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenPolicy;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Class to notify the user of events that happen.  This is how you tell
 * the user that something has happened in the background.
 *
 * <p>Notifications can take different forms:
 * <ul>
 *      <li>A persistent icon that goes in the status bar and is accessible
 *          through the launcher, (when the user selects it, a designated Intent
 *          can be launched),</li>
 *      <li>Turning on or flashing LEDs on the device, or</li>
 *      <li>Alerting the user by flashing the backlight, playing a sound,
 *          or vibrating.</li>
 * </ul>
 *
 * <p>
 * Each of the notify methods takes an int id parameter and optionally a
 * {@link String} tag parameter, which may be {@code null}.  These parameters
 * are used to form a pair (tag, id), or ({@code null}, id) if tag is
 * unspecified.  This pair identifies this notification from your app to the
 * system, so that pair should be unique within your app.  If you call one
 * of the notify methods with a (tag, id) pair that is currently active and
 * a new set of notification parameters, it will be updated.  For example,
 * if you pass a new status bar icon, the old icon in the status bar will
 * be replaced with the new one.  This is also the same tag and id you pass
 * to the {@link #cancel(int)} or {@link #cancel(String, int)} method to clear
 * this notification.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For a guide to creating notifications, read the
 * <a href="{@docRoot}guide/topics/ui/notifiers/notifications.html">Status Bar Notifications</a>
 * developer guide.</p>
 * </div>
 *
 * @see android.app.Notification
 */
@SystemService(Context.NOTIFICATION_SERVICE)
public class NotificationManager {
    private static String TAG = "NotificationManager";
    private static boolean localLOGV = false;

    /**
     * Intent that is broadcast when an application is blocked or unblocked.
     *
     * This broadcast is only sent to the app whose block state has changed.
     *
     * Input: nothing
     * Output: {@link #EXTRA_BLOCKED_STATE}
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_APP_BLOCK_STATE_CHANGED =
            "android.app.action.APP_BLOCK_STATE_CHANGED";

    /**
     * Intent that is broadcast when a {@link NotificationChannel} is blocked
     * (when {@link NotificationChannel#getImportance()} is {@link #IMPORTANCE_NONE}) or unblocked
     * (when {@link NotificationChannel#getImportance()} is anything other than
     * {@link #IMPORTANCE_NONE}).
     *
     * This broadcast is only sent to the app that owns the channel that has changed.
     *
     * Input: nothing
     * Output: {@link #EXTRA_NOTIFICATION_CHANNEL_ID}
     * Output: {@link #EXTRA_BLOCKED_STATE}
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED =
            "android.app.action.NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED";

    /**
     * Activity action: Toggle notification panel of the specified handler.
     *
     * <p><strong>Important:</strong>You must protect the activity that handles this action with
     * the {@link android.Manifest.permission#STATUS_BAR_SERVICE} permission to ensure that only
     * the SystemUI can launch this activity. Activities that are not properly protected will not
     * be launched.
     *
     * <p class="note">This is currently only used on TV to allow a system app to handle the
     * notification panel. The package handling the notification panel has to be specified by
     * config_notificationHandlerPackage in values/config.xml.
     *
     * Input: nothing
     * Output: nothing
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_TOGGLE_NOTIFICATION_HANDLER_PANEL =
            "android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL";

    /**
     * Activity action: Open notification panel of the specified handler.
     *
     * <p><strong>Important:</strong>You must protect the activity that handles this action with
     * the {@link android.Manifest.permission#STATUS_BAR_SERVICE} permission to ensure that only
     * the SystemUI can launch this activity. Activities that are not properly protected will
     * not be launched.
     *
     * <p class="note"> This is currently only used on TV to allow a system app to handle the
     * notification panel. The package handling the notification panel has to be specified by
     * config_notificationHandlerPackage in values/config.xml.
     *
     * Input: nothing
     * Output: nothing
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_OPEN_NOTIFICATION_HANDLER_PANEL =
            "android.app.action.OPEN_NOTIFICATION_HANDLER_PANEL";

    /**
     * Intent that is broadcast when the notification panel of the specified handler is to be
     * closed.
     *
     * <p><strong>Important:</strong>You should protect the receiver that handles this action with
     * the {@link android.Manifest.permission#STATUS_BAR_SERVICE} permission to ensure that only
     * the SystemUI can send this broadcast to the notification handler.
     *
     * <p class="note"> This is currently only used on TV to allow a system app to handle the
     * notification panel. The package handling the notification panel has to be specified by
     * config_notificationHandlerPackage in values/config.xml. This is a protected intent that can
     * only be sent by the system.
     *
     * Input: nothing.
     * Output: nothing.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CLOSE_NOTIFICATION_HANDLER_PANEL =
            "android.app.action.CLOSE_NOTIFICATION_HANDLER_PANEL";

    /**
     * Extra for {@link #ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED} containing the id of the
     * {@link NotificationChannel} which has a new blocked state.
     *
     * The value will be the {@link NotificationChannel#getId()} of the channel.
     */
    public static final String EXTRA_NOTIFICATION_CHANNEL_ID =
            "android.app.extra.NOTIFICATION_CHANNEL_ID";

    /**
     * Extra for {@link #ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED} containing the id
     * of the {@link NotificationChannelGroup} which has a new blocked state.
     *
     * The value will be the {@link NotificationChannelGroup#getId()} of the group.
     */
    public static final String EXTRA_NOTIFICATION_CHANNEL_GROUP_ID =
            "android.app.extra.NOTIFICATION_CHANNEL_GROUP_ID";


    /**
     * Extra for {@link #ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED} or
     * {@link #ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED} containing the new blocked
     * state as a boolean.
     *
     * The value will be {@code true} if this channel or group is now blocked and {@code false} if
     * this channel or group is now unblocked.
     */
    public static final String EXTRA_BLOCKED_STATE = "android.app.extra.BLOCKED_STATE";

    /**
     * Intent that is broadcast when a {@link NotificationChannelGroup} is
     * {@link NotificationChannelGroup#isBlocked() blocked} or unblocked.
     *
     * This broadcast is only sent to the app that owns the channel group that has changed.
     *
     * Input: nothing
     * Output: {@link #EXTRA_NOTIFICATION_CHANNEL_GROUP_ID}
     * Output: {@link #EXTRA_BLOCKED_STATE}
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED =
            "android.app.action.NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED";

    /**
     * Intent that is broadcast when the status of an {@link AutomaticZenRule} has changed.
     *
     * <p>Use this to know whether you need to continue monitor to device state in order to
     * provide up-to-date states (with {@link #setAutomaticZenRuleState(String, Condition)}) for
     * this rule.</p>
     *
     * Input: nothing
     * Output: {@link #EXTRA_AUTOMATIC_ZEN_RULE_ID}
     * Output: {@link #EXTRA_AUTOMATIC_ZEN_RULE_STATUS}
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED =
            "android.app.action.AUTOMATIC_ZEN_RULE_STATUS_CHANGED";

    /**
     * Integer extra for {@link #ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED} containing the state of
     * the {@link AutomaticZenRule}.
     *
     * <p>The value will be one of {@link #AUTOMATIC_RULE_STATUS_ENABLED},
     * {@link #AUTOMATIC_RULE_STATUS_DISABLED}, {@link #AUTOMATIC_RULE_STATUS_REMOVED},
     * {@link #AUTOMATIC_RULE_STATUS_ACTIVATED}, {@link #AUTOMATIC_RULE_STATUS_DEACTIVATED}, or
     * {@link #AUTOMATIC_RULE_STATUS_UNKNOWN}.
     *
     * <p>Note that the {@link #AUTOMATIC_RULE_STATUS_ACTIVATED} and
     * {@link #AUTOMATIC_RULE_STATUS_DEACTIVATED} statuses are only sent to packages targeting
     * {@link Build.VERSION_CODES#VANILLA_ICE_CREAM} and above; apps targeting a lower SDK version
     * will be sent {@link #AUTOMATIC_RULE_STATUS_UNKNOWN} in their place instead.
     */
    public static final String EXTRA_AUTOMATIC_ZEN_RULE_STATUS =
            "android.app.extra.AUTOMATIC_ZEN_RULE_STATUS";

    /**
     * String extra for {@link #ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED} containing the id of the
     * {@link AutomaticZenRule} (see {@link #addAutomaticZenRule(AutomaticZenRule)}) that has
     * changed.
     */
    public static final String EXTRA_AUTOMATIC_ZEN_RULE_ID =
            "android.app.extra.AUTOMATIC_ZEN_RULE_ID";

    /** @hide */
    @IntDef(prefix = { "AUTOMATIC_RULE_STATUS" }, value = {
            AUTOMATIC_RULE_STATUS_ENABLED, AUTOMATIC_RULE_STATUS_DISABLED,
            AUTOMATIC_RULE_STATUS_REMOVED, AUTOMATIC_RULE_STATUS_UNKNOWN,
            AUTOMATIC_RULE_STATUS_ACTIVATED, AUTOMATIC_RULE_STATUS_DEACTIVATED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AutomaticZenRuleStatus {}

    /**
     * Constant value for {@link #EXTRA_AUTOMATIC_ZEN_RULE_STATUS} - the current status of the
     * rule is unknown at your target sdk version, and you should continue to provide state changes
     * via {@link #setAutomaticZenRuleState(String, Condition)}.
     */
    public static final int AUTOMATIC_RULE_STATUS_UNKNOWN = -1;

    /**
     * Constant value for {@link #EXTRA_AUTOMATIC_ZEN_RULE_STATUS} - the given rule currently
     * exists and is enabled. You should continue to provide state changes via
     * {@link #setAutomaticZenRuleState(String, Condition)}.
     */
    public static final int AUTOMATIC_RULE_STATUS_ENABLED = 1;

    /**
     * Constant value for {@link #EXTRA_AUTOMATIC_ZEN_RULE_STATUS} - the given rule currently
     * exists but is disabled. You do not need to continue to provide state changes via
     * {@link #setAutomaticZenRuleState(String, Condition)} until the rule is reenabled.
     */
    public static final int AUTOMATIC_RULE_STATUS_DISABLED = 2;

    /**
     * Constant value for {@link #EXTRA_AUTOMATIC_ZEN_RULE_STATUS} - the given rule has been
     * deleted. Further calls to {@link #setAutomaticZenRuleState(String, Condition)} will be
     * ignored.
     */
    public static final int AUTOMATIC_RULE_STATUS_REMOVED = 3;

    /**
     * Constant value for {@link #EXTRA_AUTOMATIC_ZEN_RULE_STATUS} - the given rule has been
     * activated by the user or cross device sync. Sent from
     * {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}. If the rule owner has a mode that includes
     * a DND component, the rule owner should activate any extra behavior that's part of that mode
     * in response to this broadcast.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int AUTOMATIC_RULE_STATUS_ACTIVATED = 4;

    /**
     * Constant value for {@link #EXTRA_AUTOMATIC_ZEN_RULE_STATUS} - the given rule has been
     * deactivated ("snoozed") by the user. The rule will not return to an activated state until
     * the app calls {@link #setAutomaticZenRuleState(String, Condition)} with
     * {@link Condition#STATE_FALSE} (either immediately or when the trigger criteria is no
     * longer met) and then {@link Condition#STATE_TRUE} when the trigger criteria is freshly met,
     * or when the user re-activates it.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int AUTOMATIC_RULE_STATUS_DEACTIVATED = 5;

    /**
     * Intent that is broadcast when the state of {@link #getEffectsSuppressor()} changes.
     *
     * <p>This broadcast is only sent to registered receivers and (starting from
     * {@link Build.VERSION_CODES#Q}) receivers in packages that have been granted Do Not
     * Disturb access (see {@link #isNotificationPolicyAccessGranted()}).
     *
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_EFFECTS_SUPPRESSOR_CHANGED
            = "android.os.action.ACTION_EFFECTS_SUPPRESSOR_CHANGED";

    /**
     * Intent that is broadcast when the state of {@link #isNotificationPolicyAccessGranted()}
     * changes.
     *
     * This broadcast is only sent to registered receivers, and only to the apps that have changed.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED
            = "android.app.action.NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED";

    /**
     * Intent that is broadcast when the state of {@link #getNotificationPolicy()} changes.
     *
     * <p>This broadcast is only sent to registered receivers and (starting from
     * {@link Build.VERSION_CODES#Q}) receivers in packages that have been granted Do Not
     * Disturb access (see {@link #isNotificationPolicyAccessGranted()}).
     *
     * <p>Starting with {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}, most calls to
     * {@link #setNotificationPolicy(Policy)} will update the app's implicit rule policy instead of
     * the global policy, so this broadcast will be sent much less frequently.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_POLICY_CHANGED
            = "android.app.action.NOTIFICATION_POLICY_CHANGED";

    /**
     * Intent that is broadcast when the state of {@link #getConsolidatedNotificationPolicy()}
     * changes.
     *
     * <p>This broadcast is only sent to registered receivers and receivers in packages that have
     * been granted Do Not Disturb access (see {@link #isNotificationPolicyAccessGranted()}).
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED =
            "android.app.action.CONSOLIDATED_NOTIFICATION_POLICY_CHANGED";

    /**
     * Extra for {@link #ACTION_NOTIFICATION_POLICY_CHANGED} and
     * {@link #ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED} containing the new
     * {@link Policy} value.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final String EXTRA_NOTIFICATION_POLICY =
            "android.app.extra.NOTIFICATION_POLICY";

    /**
     * Intent that is broadcast when the state of getCurrentInterruptionFilter() changes.
     *
     * <p>This broadcast is only sent to registered receivers and (starting from
     * {@link Build.VERSION_CODES#Q}) receivers in packages that have been granted Do Not
     * Disturb access (see {@link #isNotificationPolicyAccessGranted()}).
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_INTERRUPTION_FILTER_CHANGED
            = "android.app.action.INTERRUPTION_FILTER_CHANGED";

    /**
     * Intent that is broadcast when the state of
     * {@link #hasEnabledNotificationListener(String, UserHandle)} changes.
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final String ACTION_NOTIFICATION_LISTENER_ENABLED_CHANGED =
            "android.app.action.NOTIFICATION_LISTENER_ENABLED_CHANGED";

    /**
     * Intent that is broadcast when the state of getCurrentInterruptionFilter() changes.
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_INTERRUPTION_FILTER_CHANGED_INTERNAL
            = "android.app.action.INTERRUPTION_FILTER_CHANGED_INTERNAL";

    /** @hide */
    @IntDef(prefix = { "INTERRUPTION_FILTER_" }, value = {
            INTERRUPTION_FILTER_NONE, INTERRUPTION_FILTER_PRIORITY, INTERRUPTION_FILTER_ALARMS,
            INTERRUPTION_FILTER_ALL, INTERRUPTION_FILTER_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InterruptionFilter {}

    /**
     * {@link #getCurrentInterruptionFilter() Interruption filter} constant -
     *     Normal interruption filter - no notifications are suppressed.
     */
    public static final int INTERRUPTION_FILTER_ALL = 1;

    /**
     * {@link #getCurrentInterruptionFilter() Interruption filter} constant -
     *     Priority interruption filter - all notifications are suppressed except those that match
     *     the priority criteria. Some audio streams are muted. See
     *     {@link Policy#priorityCallSenders}, {@link Policy#priorityCategories},
     *     {@link Policy#priorityMessageSenders} to define or query this criteria. Users can
     *     additionally specify packages that can bypass this interruption filter.
     */
    public static final int INTERRUPTION_FILTER_PRIORITY = 2;

    /**
     * {@link #getCurrentInterruptionFilter() Interruption filter} constant -
     *     No interruptions filter - all notifications are suppressed and all audio streams (except
     *     those used for phone calls) and vibrations are muted.
     */
    public static final int INTERRUPTION_FILTER_NONE = 3;

    /**
     * {@link #getCurrentInterruptionFilter() Interruption filter} constant -
     *     Alarms only interruption filter - all notifications except those of category
     *     {@link Notification#CATEGORY_ALARM} are suppressed. Some audio streams are muted.
     */
    public static final int INTERRUPTION_FILTER_ALARMS = 4;

    /** {@link #getCurrentInterruptionFilter() Interruption filter} constant - returned when
     * the value is unavailable for any reason.
     */
    public static final int INTERRUPTION_FILTER_UNKNOWN = 0;

    /** @hide */
    @IntDef(prefix = { "IMPORTANCE_" }, value = {
            IMPORTANCE_UNSPECIFIED, IMPORTANCE_NONE,
            IMPORTANCE_MIN, IMPORTANCE_LOW, IMPORTANCE_DEFAULT, IMPORTANCE_HIGH
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Importance {}

    /** @hide */
    @IntDef(prefix = { "BUBBLE_PREFERENCE_" }, value = {
            BUBBLE_PREFERENCE_NONE, BUBBLE_PREFERENCE_SELECTED,
            BUBBLE_PREFERENCE_ALL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BubblePreference {}

    /**
     * Activity Action: Launch an Automatic Zen Rule configuration screen
     * <p>
     * Input: Optionally, {@link #EXTRA_AUTOMATIC_RULE_ID}, if the configuration screen for an
     * existing rule should be displayed. If the rule id is missing or null, apps should display
     * a configuration screen where users can create a new instance of the rule.
     * <p>
     * Output: Nothing
     * <p>
     *     You can have multiple activities handling this intent, if you support multiple
     *     {@link AutomaticZenRule rules}. In order for the system to properly display all of your
     *     rule types so that users can create new instances or configure existing ones, you need
     *     to add some extra metadata ({@link #META_DATA_AUTOMATIC_RULE_TYPE})
     *     to your activity tag in your manifest. If you'd like to limit the number of rules a user
     *     can create from this flow, you can additionally optionally include
     *     {@link #META_DATA_RULE_INSTANCE_LIMIT}.
     *
     *     For example,
     *     &lt;meta-data
     *         android:name="android.app.zen.automatic.ruleType"
     *         android:value="@string/my_condition_rule">
     *     &lt;/meta-data>
     *     &lt;meta-data
     *         android:name="android.app.zen.automatic.ruleInstanceLimit"
     *         android:value="1">
     *     &lt;/meta-data>
     * </p>
     * </p>
     *
     * @see #addAutomaticZenRule(AutomaticZenRule)
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_AUTOMATIC_ZEN_RULE =
            "android.app.action.AUTOMATIC_ZEN_RULE";

    /**
     * Used as an optional string extra on {@link #ACTION_AUTOMATIC_ZEN_RULE} intents. If
     * provided, contains the id of the {@link AutomaticZenRule} (as returned from
     * {@link NotificationManager#addAutomaticZenRule(AutomaticZenRule)}) for which configuration
     * settings should be displayed.
     */
    public static final String EXTRA_AUTOMATIC_RULE_ID = "android.app.extra.AUTOMATIC_RULE_ID";

    /**
     * A required {@code meta-data} tag for activities that handle
     * {@link #ACTION_AUTOMATIC_ZEN_RULE}.
     *
     * This tag should contain a localized name of the type of the zen rule provided by the
     * activity.
     */
    public static final String META_DATA_AUTOMATIC_RULE_TYPE =
            "android.service.zen.automatic.ruleType";

    /**
     * An optional {@code meta-data} tag for activities that handle
     * {@link #ACTION_AUTOMATIC_ZEN_RULE}.
     *
     * This tag should contain the maximum number of rule instances that
     * can be created for this rule type. Omit or enter a value <= 0 to allow unlimited instances.
     */
    public static final String META_DATA_RULE_INSTANCE_LIMIT =
            "android.service.zen.automatic.ruleInstanceLimit";

    /** Value signifying that the user has not expressed a per-app visibility override value.
     * @hide */
    public static final int VISIBILITY_NO_OVERRIDE = -1000;

    /**
     * Value signifying that the user has not expressed an importance.
     *
     * This value is for persisting preferences, and should never be associated with
     * an actual notification.
     */
    public static final int IMPORTANCE_UNSPECIFIED = -1000;

    /**
     * A notification with no importance: does not show in the shade.
     */
    public static final int IMPORTANCE_NONE = 0;

    /**
     * Min notification importance: only shows in the shade, below the fold.  This should
     * not be used with {@link Service#startForeground(int, Notification) Service.startForeground}
     * since a foreground service is supposed to be something the user cares about so it does
     * not make semantic sense to mark its notification as minimum importance.  If you do this
     * as of Android version {@link android.os.Build.VERSION_CODES#O}, the system will show
     * a higher-priority notification about your app running in the background.
     */
    public static final int IMPORTANCE_MIN = 1;

    /**
     * Low notification importance: Shows in the shade, and potentially in the status bar
     * (see {@link #shouldHideSilentStatusBarIcons()}), but is not audibly intrusive.
     */
    public static final int IMPORTANCE_LOW = 2;

    /**
     * Default notification importance: shows everywhere, makes noise, but does not visually
     * intrude.
     */
    public static final int IMPORTANCE_DEFAULT = 3;

    /**
     * Higher notification importance: shows everywhere, makes noise and peeks. May use full screen
     * intents.
     */
    public static final int IMPORTANCE_HIGH = 4;

    /**
     * Unused.
     */
    public static final int IMPORTANCE_MAX = 5;

    /**
     * Indicates that the no bubbles are allowed from the app. If the app sends bubbles, only the
     * notification will appear. The notification will have an affordance allowing the user to
     * bubble it. If the user selects this affordance, that notification is approved to bubble
     * and the apps' bubble preference will be upgraded to {@link #BUBBLE_PREFERENCE_SELECTED}.
     */
    public static final int BUBBLE_PREFERENCE_NONE = 0;

    /**
     * Indicates that all bubbles are allowed from the app. If the app sends bubbles, the bubble
     * will appear along with the notification.
     */
    public static final int BUBBLE_PREFERENCE_ALL = 1;

    /**
     * Indicates that only notifications selected by the user will appear as bubbles. If
     * the app sends bubbles that haven't been selected, only the notification appear. If the
     * bubble has been approved by the user, it will appear along with the notification.
     */
    public static final int BUBBLE_PREFERENCE_SELECTED = 2;

    /**
     * Maximum length of the component name of a registered NotificationListenerService.
     * @hide
     */
    public static int MAX_SERVICE_COMPONENT_NAME_LENGTH = 500;

    private final Map<CallNotificationEventListener, CallNotificationEventCallbackStub>
            mCallNotificationEventCallbacks = new HashMap<>();

    @UnsupportedAppUsage
    private static INotificationManager sService;

    /** @hide */
    @UnsupportedAppUsage
    static public INotificationManager getService()
    {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService("notification");
        sService = INotificationManager.Stub.asInterface(b);
        return sService;
    }

    @UnsupportedAppUsage
    /*package*/ NotificationManager(Context context, Handler handler)
    {
        mContext = context;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public static NotificationManager from(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Post a notification to be shown in the status bar. If a notification with
     * the same id has already been posted by your application and has not yet been canceled, it
     * will be replaced by the updated information.
     *
     * @param id An identifier for this notification unique within your
     *        application.
     * @param notification A {@link Notification} object describing what to show the user. Must not
     *        be null.
     */
    public void notify(int id, Notification notification)
    {
        notify(null, id, notification);
    }

    /**
     * Posts a notification to be shown in the status bar. If a notification with
     * the same tag and id has already been posted by your application and has not yet been
     * canceled, it will be replaced by the updated information.
     *
     * All {@link android.service.notification.NotificationListenerService listener services} will
     * be granted {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} access to any {@link Uri uris}
     * provided on this notification or the
     * {@link NotificationChannel} this notification is posted to using
     * {@link Context#grantUriPermission(String, Uri, int)}. Permission will be revoked when the
     * notification is canceled, or you can revoke permissions with
     * {@link Context#revokeUriPermission(Uri, int)}.
     *
     * @param tag A string identifier for this notification.  May be {@code null}.
     * @param id An identifier for this notification.  The pair (tag, id) must be unique
     *        within your application.
     * @param notification A {@link Notification} object describing what to
     *        show the user. Must not be null.
     */
    public void notify(String tag, int id, Notification notification)
    {
        notifyAsUser(tag, id, notification, mContext.getUser());
    }

    /**
     * Posts a notification as a specified package to be shown in the status bar. If a notification
     * with the same tag and id has already been posted for that package and has not yet been
     * canceled, it will be replaced by the updated information.
     *
     * All {@link android.service.notification.NotificationListenerService listener services} will
     * be granted {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} access to any {@link Uri uris}
     * provided on this notification or the
     * {@link NotificationChannel} this notification is posted to using
     * {@link Context#grantUriPermission(String, Uri, int)}. Permission will be revoked when the
     * notification is canceled, or you can revoke permissions with
     * {@link Context#revokeUriPermission(Uri, int)}.
     *
     * @param targetPackage The package to post the notification as. The package must have granted
     *                      you access to post notifications on their behalf with
     *                      {@link #setNotificationDelegate(String)}.
     * @param tag A string identifier for this notification.  May be {@code null}.
     * @param id An identifier for this notification.  The pair (tag, id) must be unique
     *        within your application.
     * @param notification A {@link Notification} object describing what to
     *        show the user. Must not be null.
     */
    public void notifyAsPackage(@NonNull String targetPackage, @Nullable String tag, int id,
            @NonNull Notification notification) {
        INotificationManager service = getService();
        String sender = mContext.getPackageName();

        try {
            if (localLOGV) Log.v(TAG, sender + ": notify(" + id + ", " + notification + ")");
            service.enqueueNotificationWithTag(targetPackage, sender, tag, id,
                    fixNotification(notification), mContext.getUser().getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void notifyAsUser(String tag, int id, Notification notification, UserHandle user)
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();

        try {
            if (localLOGV) Log.v(TAG, pkg + ": notify(" + id + ", " + notification + ")");
            service.enqueueNotificationWithTag(pkg, mContext.getOpPackageName(), tag, id,
                    fixNotification(notification), user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private Notification fixNotification(Notification notification) {
        String pkg = mContext.getPackageName();
        // Fix the notification as best we can.
        Notification.addFieldsFromContext(mContext, notification);

        if (notification.sound != null) {
            notification.sound = notification.sound.getCanonicalUri();
            if (StrictMode.vmFileUriExposureEnabled()) {
                notification.sound.checkFileUriExposed("Notification.sound");
            }

        }
        fixLegacySmallIcon(notification, pkg);
        if (mContext.getApplicationInfo().targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (notification.getSmallIcon() == null) {
                throw new IllegalArgumentException("Invalid notification (no valid small icon): "
                        + notification);
            }
        }

        notification.reduceImageSizes(mContext);
        return Builder.maybeCloneStrippedForDelivery(notification);
    }

    private void fixLegacySmallIcon(Notification n, String pkg) {
        if (n.getSmallIcon() == null && n.icon != 0) {
            n.setSmallIcon(Icon.createWithResource(pkg, n.icon));
        }
    }

    /**
     * Cancels a previously posted notification.
     *
     *  <p>If the notification does not currently represent a
     *  {@link Service#startForeground(int, Notification) foreground service} or a
     *  {@link android.app.job.JobInfo.Builder#setUserInitiated(boolean) user-initiated job},
     *  it will be removed from the UI and live
     *  {@link android.service.notification.NotificationListenerService notification listeners}
     *  will be informed so they can remove the notification from their UIs.</p>
     */
    public void cancel(int id)
    {
        cancel(null, id);
    }

    /**
     * Cancels a previously posted notification.
     *
     *  <p>If the notification does not currently represent a
     *  {@link Service#startForeground(int, Notification) foreground service} or a
     *  {@link android.app.job.JobInfo.Builder#setUserInitiated(boolean) user-initiated job},
     *  it will be removed from the UI and live
     *  {@link android.service.notification.NotificationListenerService notification listeners}
     *  will be informed so they can remove the notification from their UIs.</p>
     */
    public void cancel(@Nullable String tag, int id)
    {
        cancelAsUser(tag, id, mContext.getUser());
    }

    /**
     * Cancels a previously posted notification.
     *
     * <p>If the notification does not currently represent a
     *  {@link Service#startForeground(int, Notification) foreground service} or a
     *  {@link android.app.job.JobInfo.Builder#setUserInitiated(boolean) user-initiated job},
     *  it will be removed from the UI and live
     * {@link android.service.notification.NotificationListenerService notification listeners}
     * will be informed so they can remove the notification from their UIs.</p>
     *
     * <p>This method may be used by {@link #getNotificationDelegate() a notification delegate} to
     * cancel notifications that they have posted via {@link #notifyAsPackage(String, String, int,
     * Notification)}.</p>
     *
     * @param targetPackage The package to cancel the notification as. If this package is not your
     *                      package, you can only cancel notifications you posted with
     *                      {@link #notifyAsPackage(String, String, int, Notification).
     * @param tag A string identifier for this notification.  May be {@code null}.
     * @param id An identifier for this notification.
     */
    public void cancelAsPackage(@NonNull String targetPackage, @Nullable String tag, int id) {
        INotificationManager service = getService();
        try {
            service.cancelNotificationWithTag(targetPackage, mContext.getOpPackageName(),
                    tag, id, mContext.getUser().getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void cancelAsUser(String tag, int id, UserHandle user)
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (localLOGV) Log.v(TAG, pkg + ": cancel(" + id + ")");
        try {
            service.cancelNotificationWithTag(
                    pkg, mContext.getOpPackageName(), tag, id, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Cancel all previously shown notifications. See {@link #cancel} for the
     * detailed behavior.
     */
    public void cancelAll()
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (localLOGV) Log.v(TAG, pkg + ": cancelAll()");
        try {
            service.cancelAllNotifications(pkg, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allows a package to post notifications on your behalf using
     * {@link #notifyAsPackage(String, String, int, Notification)}.
     *
     * This can be used to allow persistent processes to post notifications based on messages
     * received on your behalf from the cloud, without your process having to wake up.
     *
     * You can check if you have an allowed delegate with {@link #getNotificationDelegate()} and
     * revoke your delegate by passing null to this method.
     *
     * @param delegate Package name of the app which can send notifications on your behalf.
     */
    public void setNotificationDelegate(@Nullable String delegate) {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (localLOGV) Log.v(TAG, pkg + ": cancelAll()");
        try {
            service.setNotificationDelegate(pkg, delegate);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the {@link #setNotificationDelegate(String) delegate} that can post notifications on
     * your behalf, if there currently is one.
     */
    public @Nullable String getNotificationDelegate() {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        try {
            return service.getNotificationDelegate(pkg);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether you are allowed to post notifications on behalf of a given package, with
     * {@link #notifyAsPackage(String, String, int, Notification)}.
     *
     * See {@link #setNotificationDelegate(String)}.
     */
    public boolean canNotifyAsPackage(@NonNull String pkg) {
        INotificationManager service = getService();
        try {
            return service.canNotifyAsPackage(mContext.getPackageName(), pkg, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the calling app can send fullscreen intents.
     * <p>From Android {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, apps may not have
     * permission to use {@link android.Manifest.permission#USE_FULL_SCREEN_INTENT}. If permission
     * is denied, notification will show up as an expanded heads up notification on lockscreen.
     * <p> To request access, add the {@link android.Manifest.permission#USE_FULL_SCREEN_INTENT}
     * permission to your manifest, and use
     * {@link android.provider.Settings#ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT}.
     */
    public boolean canUseFullScreenIntent() {
        INotificationManager service = getService();
        try {
            return service.canUseFullScreenIntent(mContext.getAttributionSource());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a group container for {@link NotificationChannel} objects.
     *
     * This can be used to rename an existing group.
     * <p>
     *     Group information is only used for presentation, not for behavior. Groups are optional
     *     for channels, and you can have a mix of channels that belong to groups and channels
     *     that do not.
     * </p>
     * <p>
     *     For example, if your application supports multiple accounts, and those accounts will
     *     have similar channels, you can create a group for each account with account specific
     *     labels instead of appending account information to each channel's label.
     * </p>
     *
     * @param group The group to create
     */
    public void createNotificationChannelGroup(@NonNull NotificationChannelGroup group) {
        createNotificationChannelGroups(Arrays.asList(group));
    }

    /**
     * Creates multiple notification channel groups.
     *
     * @param groups The list of groups to create
     */
    public void createNotificationChannelGroups(@NonNull List<NotificationChannelGroup> groups) {
        INotificationManager service = getService();
        try {
            service.createNotificationChannelGroups(mContext.getPackageName(),
                    new ParceledListSlice(groups));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a notification channel that notifications can be posted to.
     *
     * This can also be used to restore a deleted channel and to update an existing channel's
     * name, description, group, and/or importance.
     *
     * <p>The name and description should only be changed if the locale changes
     * or in response to the user renaming this channel. For example, if a user has a channel
     * named 'Messages' and the user changes their locale, this channel's name should be updated
     * with the translation of 'Messages' in the new locale.
     *
     * <p>The importance of an existing channel will only be changed if the new importance is lower
     * than the current value and the user has not altered any settings on this channel.
     *
     * <p>The group an existing channel will only be changed if the channel does not already
     * belong to a group.
     *
     * All other fields are ignored for channels that already exist.
     *
     * @param channel  the channel to create.  Note that the created channel may differ from this
     *                 value. If the provided channel is malformed, a RemoteException will be
     *                 thrown.
     */
    public void createNotificationChannel(@NonNull NotificationChannel channel) {
        createNotificationChannels(Arrays.asList(channel));
    }

    /**
     * Creates multiple notification channels that different notifications can be posted to. See
     * {@link #createNotificationChannel(NotificationChannel)}.
     *
     * @param channels the list of channels to attempt to create.
     */
    public void createNotificationChannels(@NonNull List<NotificationChannel> channels) {
        INotificationManager service = getService();
        try {
            service.createNotificationChannels(mContext.getPackageName(),
                    new ParceledListSlice(channels));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the notification channel settings for a given channel id.
     *
     * <p>The channel must belong to your package, or to a package you are an approved notification
     * delegate for (see {@link #canNotifyAsPackage(String)}), or it will not be returned. To query
     * a channel as a notification delegate, call this method from a context created for that
     * package (see {@link Context#createPackageContext(String, int)}).</p>
     */
    public NotificationChannel getNotificationChannel(String channelId) {
        INotificationManager service = getService();
        try {
            return service.getNotificationChannel(mContext.getOpPackageName(),
                    mContext.getUserId(), mContext.getPackageName(), channelId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the notification channel settings for a given channel and
     * {@link ShortcutInfo#getId() conversation id}.
     *
     * <p>The channel must belong to your package, or to a package you are an approved notification
     * delegate for (see {@link #canNotifyAsPackage(String)}), or it will not be returned. To query
     * a channel as a notification delegate, call this method from a context created for that
     * package (see {@link Context#createPackageContext(String, int)}).</p>
     */
    public @Nullable NotificationChannel getNotificationChannel(@NonNull String channelId,
            @NonNull String conversationId) {
        INotificationManager service = getService();
        try {
            return service.getConversationNotificationChannel(mContext.getOpPackageName(),
                    mContext.getUserId(), mContext.getPackageName(), channelId, true,
                    conversationId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all notification channels belonging to the calling package.
     *
     * <p>Approved notification delegates (see {@link #canNotifyAsPackage(String)}) can query
     * notification channels belonging to packages they are the delegate for. To do so, call this
     * method from a context created for that package (see
     * {@link Context#createPackageContext(String, int)}).</p>
     */
    public List<NotificationChannel> getNotificationChannels() {
        INotificationManager service = getService();
        try {
            return service.getNotificationChannels(mContext.getOpPackageName(),
                    mContext.getPackageName(), mContext.getUserId()).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes the given notification channel.
     *
     * <p>If you {@link #createNotificationChannel(NotificationChannel) create} a new channel with
     * this same id, the deleted channel will be un-deleted with all of the same settings it
     * had before it was deleted.
     */
    public void deleteNotificationChannel(String channelId) {
        INotificationManager service = getService();
        try {
            service.deleteNotificationChannel(mContext.getPackageName(), channelId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the notification channel group settings for a given channel group id.
     *
     * The channel group must belong to your package, or null will be returned.
     */
    public NotificationChannelGroup getNotificationChannelGroup(String channelGroupId) {
        INotificationManager service = getService();
        try {
            return service.getNotificationChannelGroup(mContext.getPackageName(), channelGroupId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all notification channel groups belonging to the calling app.
     */
    public List<NotificationChannelGroup> getNotificationChannelGroups() {
        INotificationManager service = getService();
        try {
            final ParceledListSlice<NotificationChannelGroup> parceledList =
                    service.getNotificationChannelGroups(mContext.getPackageName());
            if (parceledList != null) {
                return parceledList.getList();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return new ArrayList<>();
    }

    /**
     * Deletes the given notification channel group, and all notification channels that
     * belong to it.
     */
    public void deleteNotificationChannelGroup(String groupId) {
        INotificationManager service = getService();
        try {
            service.deleteNotificationChannelGroup(mContext.getPackageName(), groupId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @TestApi
    public void updateNotificationChannel(@NonNull String pkg, int uid,
            @NonNull NotificationChannel channel) {
        INotificationManager service = getService();
        try {
            service.updateNotificationChannelForPackage(pkg, uid, channel);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @TestApi
    public ComponentName getEffectsSuppressor() {
        INotificationManager service = getService();
        try {
            return service.getEffectsSuppressor();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean matchesCallFilter(Bundle extras) {
        INotificationManager service = getService();
        try {
            return service.matchesCallFilter(extras);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @TestApi
    public void cleanUpCallersAfter(long timeThreshold) {
        INotificationManager service = getService();
        try {
            service.cleanUpCallersAfter(timeThreshold);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean isSystemConditionProviderEnabled(String path) {
        INotificationManager service = getService();
        try {
            return service.isSystemConditionProviderEnabled(path);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void setZenMode(int mode, Uri conditionId, String reason) {
        setZenMode(mode, conditionId, reason, /* fromUser= */ false);
    }

    /** @hide */
    public void setZenMode(int mode, Uri conditionId, String reason, boolean fromUser) {
        INotificationManager service = getService();
        try {
            service.setZenMode(mode, conditionId, reason, fromUser);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * @hide
     */
    public int getZenMode() {
        INotificationManager service = getService();
        try {
            return service.getZenMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public ZenModeConfig getZenModeConfig() {
        INotificationManager service = getService();
        try {
            return service.getZenModeConfig();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the currently applied notification policy.
     *
     * <p>
     * If {@link #getCurrentInterruptionFilter} is equal to {@link #INTERRUPTION_FILTER_ALL},
     * then the consolidated notification policy will match the default notification policy
     * returned by {@link #getNotificationPolicy}.
     * </p>
     */
    public @NonNull NotificationManager.Policy getConsolidatedNotificationPolicy() {
        INotificationManager service = getService();
        try {
            return service.getConsolidatedNotificationPolicy();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public int getRuleInstanceCount(ComponentName owner) {
        INotificationManager service = getService();
        try {
            return service.getRuleInstanceCount(owner);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if users can independently and fully manage {@link AutomaticZenRule} rules. This
     * includes the ability to independently activate/deactivate rules and overwrite/freeze the
     * behavior (policy) of the rule when activated.
     * <p>
     * If this method returns true, calls to
     * {@link #updateAutomaticZenRule(String, AutomaticZenRule)} may fail and apps should defer
     * rule management to system settings/uis via
     * {@link Settings#ACTION_AUTOMATIC_ZEN_RULE_SETTINGS}.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public boolean areAutomaticZenRulesUserManaged() {
        // modes ui is dependent on modes api
        return Flags.modesApi() && Flags.modesUi();
    }


    /**
     * Returns AutomaticZenRules owned by the caller.
     *
     * <p>
     * Throws a SecurityException if policy access is not granted to this package.
     * See {@link #isNotificationPolicyAccessGranted}.
     */
    public Map<String, AutomaticZenRule> getAutomaticZenRules() {
        INotificationManager service = getService();
        try {
            if (Flags.modesApi()) {
                return service.getAutomaticZenRules();
            } else {
                List<ZenModeConfig.ZenRule> rules = service.getZenRules();
                Map<String, AutomaticZenRule> ruleMap = new HashMap<>();
                for (ZenModeConfig.ZenRule rule : rules) {
                    AutomaticZenRule azr = new AutomaticZenRule(rule.name, rule.component,
                            rule.configurationActivity, rule.conditionId, rule.zenPolicy,
                            zenModeToInterruptionFilter(rule.zenMode), rule.enabled,
                            rule.creationTime);
                    azr.setPackageName(rule.pkg);
                    ruleMap.put(rule.id, azr);
                }
                return ruleMap;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the AutomaticZenRule with the given id, if it exists and the caller has access.
     *
     * <p>
     * Throws a SecurityException if policy access is not granted to this package.
     * See {@link #isNotificationPolicyAccessGranted}.
     *
     * <p>
     * Returns null if there are no zen rules that match the given id, or if the calling package
     * doesn't own the matching rule. See {@link AutomaticZenRule#getOwner}.
     */
    public AutomaticZenRule getAutomaticZenRule(String id) {
        INotificationManager service = getService();
        try {
            return service.getAutomaticZenRule(id);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates the given zen rule.
     *
     * <p>
     * Throws a SecurityException if policy access is not granted to this package.
     * See {@link #isNotificationPolicyAccessGranted}.
     *
     * @param automaticZenRule the rule to create.
     * @return The id of the newly created rule; null if the rule could not be created.
     */
    public String addAutomaticZenRule(AutomaticZenRule automaticZenRule) {
        return addAutomaticZenRule(automaticZenRule, /* fromUser= */ false);
    }

    /** @hide */
    @TestApi
    @FlaggedApi(Flags.FLAG_MODES_API)
    @NonNull
    public String addAutomaticZenRule(@NonNull AutomaticZenRule automaticZenRule,
            boolean fromUser) {
        INotificationManager service = getService();
        try {
            return service.addAutomaticZenRule(automaticZenRule,
                    mContext.getPackageName(), fromUser);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the given zen rule.
     *
     * <p>Before {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}, updating a rule that is not backed
     * up by a {@link android.service.notification.ConditionProviderService} will deactivate it if
     * it was previously active. Starting with {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}, this
     * will only happen if the rule's definition is actually changing.
     *
     * <p>Throws a SecurityException if policy access is not granted to this package.
     * See {@link #isNotificationPolicyAccessGranted}.
     *
     * <p>Callers can only update rules that they own. See {@link AutomaticZenRule#getOwner}.
     *
     * @param id The id of the rule to update
     * @param automaticZenRule the rule to update.
     * @return Whether the rule was successfully updated.
     */
    public boolean updateAutomaticZenRule(String id, AutomaticZenRule automaticZenRule) {
        return updateAutomaticZenRule(id, automaticZenRule, /* fromUser= */ false);
    }

    /** @hide */
    @TestApi
    @FlaggedApi(Flags.FLAG_MODES_API)
    public boolean updateAutomaticZenRule(@NonNull String id,
            @NonNull AutomaticZenRule automaticZenRule, boolean fromUser) {
        INotificationManager service = getService();
        try {
            return service.updateAutomaticZenRule(id, automaticZenRule, fromUser);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current activation state of an {@link AutomaticZenRule}.
     *
     * <p>Returns {@link Condition#STATE_UNKNOWN} if the rule does not exist or the calling
     * package doesn't have access to it.
     *
     * @param id The id of the rule
     * @return the state of the rule.
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    @Condition.State
    public int getAutomaticZenRuleState(@NonNull String id) {
        INotificationManager service = getService();
        try {
            return service.getAutomaticZenRuleState(id);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Informs the notification manager that the state of an {@link AutomaticZenRule} has changed.
     * Use this method to put the system into Do Not Disturb mode or request that it exits Do Not
     * Disturb mode. The calling app must own the provided {@link android.app.AutomaticZenRule}.
     * <p>
     *     This method can be used in conjunction with or as a replacement to
     *     {@link android.service.notification.ConditionProviderService#notifyCondition(Condition)}.
     * </p>
     * @param id The id of the rule whose state should change
     * @param condition The new state of this rule
     */
    public void setAutomaticZenRuleState(@NonNull String id, @NonNull Condition condition) {
        INotificationManager service = getService();
        try {
            service.setAutomaticZenRuleState(id, condition);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes the automatic zen rule with the given id.
     *
     * <p>
     * Throws a SecurityException if policy access is not granted to this package.
     * See {@link #isNotificationPolicyAccessGranted}.
     *
     * <p>
     * Callers can only delete rules that they own. See {@link AutomaticZenRule#getOwner}.
     * @param id the id of the rule to delete.
     * @return Whether the rule was successfully deleted.
     */
    public boolean removeAutomaticZenRule(String id) {
        return removeAutomaticZenRule(id, /* fromUser= */ false);
    }

    /** @hide */
    @TestApi
    @FlaggedApi(Flags.FLAG_MODES_API)
    public boolean removeAutomaticZenRule(@NonNull String id, boolean fromUser) {
        INotificationManager service = getService();
        try {
            return service.removeAutomaticZenRule(id, fromUser);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes all automatic zen rules owned by the given package.
     *
     * @hide
     */
    public boolean removeAutomaticZenRules(String packageName) {
        return removeAutomaticZenRules(packageName, /* fromUser= */ false);
    }

    /** @hide */
    public boolean removeAutomaticZenRules(String packageName, boolean fromUser) {
        INotificationManager service = getService();
        try {
            return service.removeAutomaticZenRules(packageName, fromUser);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user specified importance for notifications from the calling
     * package.
     */
    public @Importance int getImportance() {
        INotificationManager service = getService();
        try {
            return service.getPackageImportance(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether notifications from the calling package are enabled.
     */
    public boolean areNotificationsEnabled() {
        INotificationManager service = getService();
        try {
            return service.areNotificationsEnabled(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets whether all notifications posted by this app can appear outside of the
     * notification shade, floating over other apps' content.
     *
     * <p>This value will be ignored for notifications that are posted to channels that do not
     * allow bubbles ({@link NotificationChannel#canBubble()}).
     *
     * @see Notification#getBubbleMetadata()
     * @deprecated use {@link #getBubblePreference()} instead.
     */
    @Deprecated
    public boolean areBubblesAllowed() {
        INotificationManager service = getService();
        try {
            return service.areBubblesAllowed(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether bubbles are enabled at the feature level for the current user. When enabled,
     * notifications able to bubble will display an affordance allowing the user to bubble them.
     *
     * @see Notification.Builder#setBubbleMetadata(Notification.BubbleMetadata)
     */
    public boolean areBubblesEnabled() {
        INotificationManager service = getService();
        try {
            return service.areBubblesEnabled(mContext.getUser());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the bubble preference for the app. This preference only applies to notifications that
     * have been properly configured to bubble.
     *
     * <p>
     * If {@link #BUBBLE_PREFERENCE_ALL}, then any bubble notification will appear as a bubble, as
     * long as the user hasn't excluded it ({@link NotificationChannel#canBubble()}).
     *
     * <p>
     * If {@link #BUBBLE_PREFERENCE_SELECTED}, then any bubble notification will appear as a bubble,
     * as long as the user has selected it.
     *
     * <p>
     * If {@link #BUBBLE_PREFERENCE_NONE}, then no notification may appear as a bubble from the app.
     *
     * @see Notification#getBubbleMetadata()
     * @return the users' bubble preference for the app.
     */
    public @BubblePreference int getBubblePreference() {
        INotificationManager service = getService();
        try {
            return service.getBubblePreferenceForPackage(mContext.getPackageName(),
                    Binder.getCallingUid());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Silences the current notification sound, if ones currently playing.
     * <p>
     * It is intended to handle use-cases such as silencing a ringing call
     * when the user presses the volume button during ringing.
     * <p>
     * If this method is called prior to when the notification begins playing, the sound will not be
     * silenced.  As such it is not intended as a means to avoid playing of a sound.
     * @hide
     */
    public void silenceNotificationSound() {
        INotificationManager service = getService();
        try {
            service.silenceNotificationSound();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether notifications from this package are temporarily hidden. This
     * could be done because the package was marked as distracting to the user via
     * {@code PackageManager#setDistractingPackageRestrictions(String[], int)} or because the
     * package is {@code PackageManager#setPackagesSuspended(String[], boolean, PersistableBundle,
     * PersistableBundle, SuspendDialogInfo) suspended}.
     */
    public boolean areNotificationsPaused() {
        INotificationManager service = getService();
        try {
            return service.isPackagePaused(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks the ability to modify notification do not disturb policy for the calling package.
     *
     * <p>
     * Returns true if the calling package can modify notification policy.
     *
     * <p>
     * Apps can request policy access by sending the user to the activity that matches the system
     * intent action {@link android.provider.Settings#ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS}.
     *
     * <p>
     * Use {@link #ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED} to listen for
     * user grant or denial of this access.
     */
    public boolean isNotificationPolicyAccessGranted() {
        INotificationManager service = getService();
        try {
            return service.isNotificationPolicyAccessGranted(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the user has approved a given
     * {@link android.service.notification.NotificationListenerService}.
     *
     * <p>
     * The listener service must belong to the calling app.
     *
     * <p>
     * Apps can request notification listener access by sending the user to the activity that
     * matches the system intent action
     * {@link android.provider.Settings#ACTION_NOTIFICATION_LISTENER_SETTINGS}.
     */
    public boolean isNotificationListenerAccessGranted(ComponentName listener) {
        INotificationManager service = getService();
        try {
            return service.isNotificationListenerAccessGranted(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the user has approved a given
     * {@link android.service.notification.NotificationAssistantService}.
     *
     * <p>
     * The assistant service must belong to the calling app.
     *
     * <p>
     * Apps can request notification assistant access by sending the user to the activity that
     * matches the system intent action
     * TODO: STOPSHIP: Add correct intent
     * {@link android.provider.Settings#ACTION_MANAGE_DEFAULT_APPS_SETTINGS}.
     * @hide
     */
    @SystemApi
    public boolean isNotificationAssistantAccessGranted(@NonNull ComponentName assistant) {
        INotificationManager service = getService();
        try {
            return service.isNotificationAssistantAccessGranted(assistant);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the user wants silent notifications (see {@link #IMPORTANCE_LOW} to appear
     * in the status bar.
     *
     * <p>Only available for {@link #isNotificationListenerAccessGranted(ComponentName) notification
     * listeners}.
     */
    public boolean shouldHideSilentStatusBarIcons() {
        INotificationManager service = getService();
        try {
            return service.shouldHideSilentStatusIcons(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of {@link android.service.notification.Adjustment adjustment keys} that can
     * be modified by the current {@link android.service.notification.NotificationAssistantService}.
     *
     * <p>Only callable by the current
     * {@link android.service.notification.NotificationAssistantService}.
     * See {@link #isNotificationAssistantAccessGranted(ComponentName)}</p>
     * @hide
     */
    @SystemApi
    public @NonNull @Adjustment.Keys List<String> getAllowedAssistantAdjustments() {
        INotificationManager service = getService();
        try {
            return service.getAllowedAssistantAdjustments(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @TestApi
    public boolean isNotificationPolicyAccessGrantedForPackage(@NonNull String pkg) {
        INotificationManager service = getService();
        try {
            return service.isNotificationPolicyAccessGrantedForPackage(pkg);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public List<String> getEnabledNotificationListenerPackages() {
        INotificationManager service = getService();
        try {
            return service.getEnabledNotificationListenerPackages();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the current user-specified default notification policy.
     *
     * <p>For apps targeting {@link Build.VERSION_CODES#VANILLA_ICE_CREAM} and above (with some
     * exceptions, such as companion device managers) this method will return the policy associated
     * to their implicit {@link AutomaticZenRule} instead, if it exists. See
     * {@link #setNotificationPolicy(Policy)}.
     */
    public Policy getNotificationPolicy() {
        INotificationManager service = getService();
        try {
            return service.getNotificationPolicy(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the current notification policy (which applies when {@link #setInterruptionFilter} is
     * called with the {@link #INTERRUPTION_FILTER_PRIORITY} value).
     *
     * <p>Apps targeting {@link Build.VERSION_CODES#VANILLA_ICE_CREAM} and above (with some
     * exceptions, such as companion device managers) cannot modify the global notification policy.
     * Calling this method will instead create or update an {@link AutomaticZenRule} associated to
     * the app, using a {@link ZenPolicy} corresponding to the {@link Policy} supplied here, and
     * which will be activated/deactivated by calls to {@link #setInterruptionFilter(int)}.
     *
     * <p>Only available if policy access is granted to this package. See
     * {@link #isNotificationPolicyAccessGranted}.
     *
     * @param policy The new desired policy.
     */
    public void setNotificationPolicy(@NonNull Policy policy) {
        setNotificationPolicy(policy, /* fromUser= */ false);
    }

    /** @hide */
    public void setNotificationPolicy(@NonNull Policy policy, boolean fromUser) {
        checkRequired("policy", policy);
        INotificationManager service = getService();
        try {
            service.setNotificationPolicy(mContext.getOpPackageName(), policy, fromUser);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setNotificationPolicyAccessGranted(String pkg, boolean granted) {
        INotificationManager service = getService();
        try {
            service.setNotificationPolicyAccessGranted(pkg, granted);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setNotificationListenerAccessGranted(
            @NonNull ComponentName listener, boolean granted) {
        setNotificationListenerAccessGranted(listener, granted, true);
    }
    /**
     * Gets the device-default notification policy as a ZenPolicy.
     * @hide
     */
    @TestApi
    @FlaggedApi(Flags.FLAG_MODES_API)
    public @NonNull ZenPolicy getDefaultZenPolicy() {
        INotificationManager service = getService();
        try {
            return service.getDefaultZenPolicy();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
    /**
     * @hide
     */
    @FlaggedApi(Flags.FLAG_MODES_UI)
    public void setManualZenRuleDeviceEffects(@NonNull ZenDeviceEffects effects) {
        INotificationManager service = getService();
        try {
            service.setManualZenRuleDeviceEffects(effects);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * For apps targeting {@link Build.VERSION_CODES#VANILLA_ICE_CREAM} and above, the
     * {@code setNotificationListenerAccessGranted} method will use the user contained within the
     * context.
     * For apps targeting an SDK version <em>below</em> this, the user of the calling process will
     * be used (Process.myUserHandle()).
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public static final long SET_LISTENER_ACCESS_GRANTED_IS_USER_AWARE = 302563478L;

    /**
     * Grants/revokes Notification Listener access to the given component for current user.
     * To grant access for a particular user, obtain this service by using the {@link Context}
     * provided by {@link Context#createPackageContextAsUser}
     *
     * @param listener Name of component to grant/revoke access
     * @param granted  Grant/revoke access
     * @param userSet  Whether the action was triggered explicitly by user
     * @hide
     */
    @SystemApi
    @TestApi
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @RequiresPermission(android.Manifest.permission.MANAGE_NOTIFICATION_LISTENERS)
    public void setNotificationListenerAccessGranted(
            @NonNull ComponentName listener, boolean granted, boolean userSet) {
        INotificationManager service = getService();
        try {
            if (CompatChanges.isChangeEnabled(SET_LISTENER_ACCESS_GRANTED_IS_USER_AWARE)) {
                service.setNotificationListenerAccessGrantedForUser(listener, mContext.getUserId(),
                        granted, userSet);
            } else {
                service.setNotificationListenerAccessGranted(listener, granted, userSet);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setNotificationListenerAccessGrantedForUser(ComponentName listener, int userId,
            boolean granted) {
        INotificationManager service = getService();
        try {
            service.setNotificationListenerAccessGrantedForUser(listener, userId, granted, true);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Grants/revokes Notification Assistant access to {@code assistant} for current user.
     * To grant access for a particular user, obtain this service by using the {@link Context}
     * provided by {@link Context#createPackageContextAsUser}
     *
     * @param assistant Name of component to grant/revoke access or {@code null} to revoke access to
     *                  current assistant
     * @param granted Grant/revoke access
     * @hide
     */
    @SystemApi
    public void setNotificationAssistantAccessGranted(@Nullable ComponentName assistant,
            boolean granted) {
        INotificationManager service = getService();
        try {
            service.setNotificationAssistantAccessGranted(assistant, granted);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the list of enabled notification listener components for current user.
     * To query for a particular user, obtain this service by using the {@link Context}
     * provided by {@link Context#createPackageContextAsUser}
     *
     * @return the list of {@link ComponentName}s of the notification listeners
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_NOTIFICATION_LISTENERS)
    public @NonNull List<ComponentName> getEnabledNotificationListeners() {
        return getEnabledNotificationListeners(mContext.getUserId());
    }

    /** @hide */
    public List<ComponentName> getEnabledNotificationListeners(int userId) {
        INotificationManager service = getService();
        try {
            return service.getEnabledNotificationListeners(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @SystemApi
    public @Nullable ComponentName getAllowedNotificationAssistant() {
        INotificationManager service = getService();
        try {
            return service.getAllowedNotificationAssistant();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Whether the given user has an enabled
     * {@link android.service.notification.NotificationListenerService} with the given package name.
     *
     * @param packageName the package name of the NotificationListenerService class
     * @param userHandle the handle of the user that set the listener
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @SuppressLint("UserHandle")
    public boolean hasEnabledNotificationListener(@NonNull String packageName,
            @NonNull UserHandle userHandle) {
        INotificationManager service = getService();
        try {
            return service.hasEnabledNotificationListener(packageName, userHandle.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private Context mContext;

    private static void checkRequired(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    /**
     * Controls whether toast rate limiting is enabled for the calling uid.
     *
     * @param enable true to enable toast rate limiting, false to disable it
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_TOAST_RATE_LIMITING)
    public void setToastRateLimitingEnabled(boolean enable) {
        INotificationManager service = getService();
        try {
            service.setToastRateLimitingEnabled(enable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notification policy configuration.  Represents user-preferences for notification
     * filtering.
     */
    public static class Policy implements android.os.Parcelable {
        /** Reminder notifications are prioritized. */
        public static final int PRIORITY_CATEGORY_REMINDERS = 1 << 0;
        /** Event notifications are prioritized. */
        public static final int PRIORITY_CATEGORY_EVENTS = 1 << 1;
        /** Message notifications are prioritized. */
        public static final int PRIORITY_CATEGORY_MESSAGES = 1 << 2;
        /** Calls are prioritized. */
        public static final int PRIORITY_CATEGORY_CALLS = 1 << 3;
        /** Calls from repeat callers are prioritized. */
        public static final int PRIORITY_CATEGORY_REPEAT_CALLERS = 1 << 4;
        /** Alarms are prioritized */
        public static final int PRIORITY_CATEGORY_ALARMS = 1 << 5;
        /** Media, game, voice navigation are prioritized */
        public static final int PRIORITY_CATEGORY_MEDIA = 1 << 6;
        /**System (catch-all for non-never suppressible sounds) are prioritized */
        public static final int PRIORITY_CATEGORY_SYSTEM = 1 << 7;
        /**
         * Conversations are allowed through DND.
         */
        public static final int PRIORITY_CATEGORY_CONVERSATIONS = 1 << 8;

        /**
         * @hide
         */
        public static final int[] ALL_PRIORITY_CATEGORIES = {
                PRIORITY_CATEGORY_ALARMS,
                PRIORITY_CATEGORY_MEDIA,
                PRIORITY_CATEGORY_SYSTEM,
                PRIORITY_CATEGORY_REMINDERS,
                PRIORITY_CATEGORY_EVENTS,
                PRIORITY_CATEGORY_MESSAGES,
                PRIORITY_CATEGORY_CALLS,
                PRIORITY_CATEGORY_REPEAT_CALLERS,
                PRIORITY_CATEGORY_CONVERSATIONS,
        };

        /** @hide */
        @IntDef(prefix = { "PRIORITY_SENDERS_" }, value = {
                PRIORITY_SENDERS_ANY,
                PRIORITY_SENDERS_CONTACTS,
                PRIORITY_SENDERS_STARRED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface PrioritySenders {}

        /** Any sender is prioritized. */
        public static final int PRIORITY_SENDERS_ANY = 0;
        /** Saved contacts are prioritized. */
        public static final int PRIORITY_SENDERS_CONTACTS = 1;
        /** Only starred contacts are prioritized. */
        public static final int PRIORITY_SENDERS_STARRED = 2;


        /** @hide */
        @IntDef(prefix = { "CONVERSATION_SENDERS_" }, value = {
                CONVERSATION_SENDERS_ANYONE,
                CONVERSATION_SENDERS_IMPORTANT,
                CONVERSATION_SENDERS_NONE,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ConversationSenders {}
        /**
         * Used to indicate all conversations can bypass dnd.
         */
        public static final int CONVERSATION_SENDERS_ANYONE = ZenPolicy.CONVERSATION_SENDERS_ANYONE;

        /**
         * Used to indicate important conversations can bypass dnd.
         */
        public static final int CONVERSATION_SENDERS_IMPORTANT =
                ZenPolicy.CONVERSATION_SENDERS_IMPORTANT;

        /**
         * Used to indicate no conversations can bypass dnd.
         */
        public static final int CONVERSATION_SENDERS_NONE = ZenPolicy.CONVERSATION_SENDERS_NONE;

        /** Notification categories to prioritize. Bitmask of PRIORITY_CATEGORY_* constants. */
        public final int priorityCategories;

        /** Notification senders to prioritize for calls. One of:
         * PRIORITY_SENDERS_ANY, PRIORITY_SENDERS_CONTACTS, PRIORITY_SENDERS_STARRED */
        @PrioritySenders
        public final int priorityCallSenders;

        /** Notification senders to prioritize for messages. One of:
         * PRIORITY_SENDERS_ANY, PRIORITY_SENDERS_CONTACTS, PRIORITY_SENDERS_STARRED */
        @PrioritySenders
        public final int priorityMessageSenders;

        /**
         * Notification senders to prioritize for conversations. One of:
         * {@link #CONVERSATION_SENDERS_NONE}, {@link #CONVERSATION_SENDERS_IMPORTANT},
         * {@link #CONVERSATION_SENDERS_ANYONE}.
         */
        @ConversationSenders
        public final int priorityConversationSenders;

        /**
         * @hide
         */
        public static final int CONVERSATION_SENDERS_UNSET = -1;

        /**
         * @hide
         */
        public static final int SUPPRESSED_EFFECTS_UNSET = -1;

        /**
         * Whether notifications suppressed by DND should not interrupt visually (e.g. with
         * notification lights or by turning the screen on) when the screen is off.
         *
         * @deprecated use {@link #SUPPRESSED_EFFECT_FULL_SCREEN_INTENT} and
         * {@link #SUPPRESSED_EFFECT_AMBIENT} and {@link #SUPPRESSED_EFFECT_LIGHTS} individually.
         */
        @Deprecated
        public static final int SUPPRESSED_EFFECT_SCREEN_OFF = 1 << 0;
        /**
         * Whether notifications suppressed by DND should not interrupt visually when the screen
         * is on (e.g. by peeking onto the screen).
         *
         * @deprecated use {@link #SUPPRESSED_EFFECT_PEEK}.
         */
        @Deprecated
        public static final int SUPPRESSED_EFFECT_SCREEN_ON = 1 << 1;

        /**
         * Whether {@link Notification#fullScreenIntent full screen intents} from
         * notifications intercepted by DND are blocked.
         */
        public static final int SUPPRESSED_EFFECT_FULL_SCREEN_INTENT = 1 << 2;

        /**
         * Whether {@link NotificationChannel#shouldShowLights() notification lights} from
         * notifications intercepted by DND are blocked.
         */
        public static final int SUPPRESSED_EFFECT_LIGHTS = 1 << 3;

        /**
         * Whether notifications intercepted by DND are prevented from peeking.
         */
        public static final int SUPPRESSED_EFFECT_PEEK = 1 << 4;

        /**
         * Whether notifications intercepted by DND are prevented from appearing in the status bar,
         * on devices that support status bars.
         */
        public static final int SUPPRESSED_EFFECT_STATUS_BAR = 1 << 5;

        /**
         * Whether {@link NotificationChannel#canShowBadge() badges} from
         * notifications intercepted by DND are blocked on devices that support badging.
         */
        public static final int SUPPRESSED_EFFECT_BADGE = 1 << 6;

        /**
         * Whether notification intercepted by DND are prevented from appearing on ambient displays
         * on devices that support ambient display.
         */
        public static final int SUPPRESSED_EFFECT_AMBIENT = 1 << 7;

        /**
         * Whether notification intercepted by DND are prevented from appearing in notification
         * list views like the notification shade or lockscreen on devices that support those
         * views.
         */
        public static final int SUPPRESSED_EFFECT_NOTIFICATION_LIST = 1 << 8;

        private static final int[] ALL_SUPPRESSED_EFFECTS = {
                SUPPRESSED_EFFECT_SCREEN_OFF,
                SUPPRESSED_EFFECT_SCREEN_ON,
                SUPPRESSED_EFFECT_FULL_SCREEN_INTENT,
                SUPPRESSED_EFFECT_LIGHTS,
                SUPPRESSED_EFFECT_PEEK,
                SUPPRESSED_EFFECT_STATUS_BAR,
                SUPPRESSED_EFFECT_BADGE,
                SUPPRESSED_EFFECT_AMBIENT,
                SUPPRESSED_EFFECT_NOTIFICATION_LIST
        };

        /**
         * Visual effects to suppress for a notification that is filtered by Do Not Disturb mode.
         * Bitmask of SUPPRESSED_EFFECT_* constants.
         */
        public final int suppressedVisualEffects;

        /**
         * @hide
         */
        public static final int STATE_CHANNELS_BYPASSING_DND = 1 << 0;

        /**
         * Whether the policy indicates that even priority channels are NOT permitted to bypass DND.
         * Note that this state explicitly marks the "disallow" state because the default behavior
         * is to allow priority channels to break through.
         * @hide
         */
        public static final int STATE_PRIORITY_CHANNELS_BLOCKED = 1 << 1;

        /**
         * @hide
         */
        public static final int STATE_UNSET = -1;

        /**
         * Notification state information that is necessary to determine Do Not Disturb behavior.
         * Bitmask of STATE_* constants.
         * @hide
         */
        public final int state;

        /**
         * Constructs a policy for Do Not Disturb priority mode behavior.
         *
         * <p>
         *     Apps that target API levels below {@link Build.VERSION_CODES#P} cannot
         *     change user-designated values to allow or disallow
         *     {@link Policy#PRIORITY_CATEGORY_ALARMS}, {@link Policy#PRIORITY_CATEGORY_SYSTEM}, and
         *     {@link Policy#PRIORITY_CATEGORY_MEDIA} from bypassing dnd.
         *
         * @param priorityCategories bitmask of categories of notifications that can bypass DND.
         * @param priorityCallSenders which callers can bypass DND.
         * @param priorityMessageSenders which message senders can bypass DND.
         */
        public Policy(int priorityCategories, int priorityCallSenders, int priorityMessageSenders) {
            this(priorityCategories, priorityCallSenders, priorityMessageSenders,
                    SUPPRESSED_EFFECTS_UNSET, STATE_UNSET, CONVERSATION_SENDERS_UNSET);
        }

        /**
         * Constructs a policy for Do Not Disturb priority mode behavior.
         *
         * <p>
         *     Apps that target API levels below {@link Build.VERSION_CODES#R} cannot
         *     change user-designated values to allow or disallow
         *     {@link Policy#PRIORITY_CATEGORY_CONVERSATIONS}, from bypassing dnd.
         * <p>
         *     Additionally, apps that target API levels below {@link Build.VERSION_CODES#P} can
         *     only modify the {@link #SUPPRESSED_EFFECT_SCREEN_ON} and
         *     {@link #SUPPRESSED_EFFECT_SCREEN_OFF} bits of the suppressed visual effects field.
         *     All other suppressed effects will be ignored and reconstituted from the screen on
         *     and screen off values.
         * <p>
         *     Apps that target {@link Build.VERSION_CODES#P} or above can set any
         *     suppressed visual effects. However, if any suppressed effects >
         *     {@link #SUPPRESSED_EFFECT_SCREEN_ON} are set, {@link #SUPPRESSED_EFFECT_SCREEN_ON}
         *     and {@link #SUPPRESSED_EFFECT_SCREEN_OFF} will be ignored and reconstituted from
         *     the more specific suppressed visual effect bits. Apps should migrate to targeting
         *     specific effects instead of the deprecated {@link #SUPPRESSED_EFFECT_SCREEN_ON} and
         *     {@link #SUPPRESSED_EFFECT_SCREEN_OFF} effects.
         *
         * @param priorityCategories bitmask of categories of notifications that can bypass DND.
         * @param priorityCallSenders which callers can bypass DND.
         * @param priorityMessageSenders which message senders can bypass DND.
         * @param suppressedVisualEffects which visual interruptions should be suppressed from
         *                                notifications that are filtered by DND.
         */
        public Policy(int priorityCategories, int priorityCallSenders, int priorityMessageSenders,
                int suppressedVisualEffects) {
            this(priorityCategories, priorityCallSenders, priorityMessageSenders,
                    suppressedVisualEffects, STATE_UNSET, CONVERSATION_SENDERS_UNSET);
        }

        /**
         * Constructs a policy for Do Not Disturb priority mode behavior.
         *
         * <p>
         *     Apps that target API levels below {@link Build.VERSION_CODES#P} cannot
         *     change user-designated values to allow or disallow
         *     {@link Policy#PRIORITY_CATEGORY_CONVERSATIONS} from bypassing dnd. If you do need
         *     to change them, use a {@link ZenPolicy} associated with an {@link AutomaticZenRule}
         *     instead of changing the global setting.
         * <p>
         *     Apps that target API levels below {@link Build.VERSION_CODES#P} cannot
         *     change user-designated values to allow or disallow
         *     {@link Policy#PRIORITY_CATEGORY_ALARMS},
         *     {@link Policy#PRIORITY_CATEGORY_SYSTEM}, and
         *     {@link Policy#PRIORITY_CATEGORY_MEDIA} from bypassing dnd.
         * <p>
         *     Additionally, apps that target API levels below {@link Build.VERSION_CODES#P} can
         *     only modify the {@link #SUPPRESSED_EFFECT_SCREEN_ON} and
         *     {@link #SUPPRESSED_EFFECT_SCREEN_OFF} bits of the suppressed visual effects field.
         *     All other suppressed effects will be ignored and reconstituted from the screen on
         *     and screen off values.
         * <p>
         *     Apps that target {@link Build.VERSION_CODES#P} or above can set any
         *     suppressed visual effects. However, if any suppressed effects >
         *     {@link #SUPPRESSED_EFFECT_SCREEN_ON} are set, {@link #SUPPRESSED_EFFECT_SCREEN_ON}
         *     and {@link #SUPPRESSED_EFFECT_SCREEN_OFF} will be ignored and reconstituted from
         *     the more specific suppressed visual effect bits. Apps should migrate to targeting
         *     specific effects instead of the deprecated {@link #SUPPRESSED_EFFECT_SCREEN_ON} and
         *     {@link #SUPPRESSED_EFFECT_SCREEN_OFF} effects.
         *
         * @param priorityCategories bitmask of categories of notifications that can bypass DND.
         * @param priorityCallSenders which callers can bypass DND.
         * @param priorityMessageSenders which message senders can bypass DND.
         * @param suppressedVisualEffects which visual interruptions should be suppressed from
         *                                notifications that are filtered by DND.
         */
        public Policy(int priorityCategories, @PrioritySenders int priorityCallSenders,
                @PrioritySenders int priorityMessageSenders,
                int suppressedVisualEffects, @ConversationSenders int priorityConversationSenders) {
            this(priorityCategories, priorityCallSenders, priorityMessageSenders,
                    suppressedVisualEffects, STATE_UNSET, priorityConversationSenders);
        }

        /** @hide */
        public Policy(int priorityCategories, int priorityCallSenders, int priorityMessageSenders,
                int suppressedVisualEffects, int state, int priorityConversationSenders) {
            this.priorityCategories = priorityCategories;
            this.priorityCallSenders = priorityCallSenders;
            this.priorityMessageSenders = priorityMessageSenders;
            this.suppressedVisualEffects = suppressedVisualEffects;
            this.state = state;
            this.priorityConversationSenders = priorityConversationSenders;
        }


        /** @hide */
        public Policy(Parcel source) {
            this(source.readInt(), source.readInt(), source.readInt(), source.readInt(),
                    source.readInt(), source.readInt());
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(priorityCategories);
            dest.writeInt(priorityCallSenders);
            dest.writeInt(priorityMessageSenders);
            dest.writeInt(suppressedVisualEffects);
            dest.writeInt(state);
            dest.writeInt(priorityConversationSenders);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(priorityCategories, priorityCallSenders, priorityMessageSenders,
                    suppressedVisualEffects, state, priorityConversationSenders);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof Policy)) return false;
            if (o == this) return true;
            final Policy other = (Policy) o;
            return other.priorityCategories == priorityCategories
                    && other.priorityCallSenders == priorityCallSenders
                    && other.priorityMessageSenders == priorityMessageSenders
                    && suppressedVisualEffectsEqual(suppressedVisualEffects,
                    other.suppressedVisualEffects)
                    && other.state == this.state
                    && other.priorityConversationSenders == this.priorityConversationSenders;
        }

        private boolean suppressedVisualEffectsEqual(int suppressedEffects,
                int otherSuppressedVisualEffects) {
            if (suppressedEffects == otherSuppressedVisualEffects) {
                return true;
            }

            if ((suppressedEffects & SUPPRESSED_EFFECT_SCREEN_ON) != 0) {
                suppressedEffects |= SUPPRESSED_EFFECT_PEEK;
            }
            if ((suppressedEffects & SUPPRESSED_EFFECT_SCREEN_OFF) != 0) {
                suppressedEffects |= SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
                suppressedEffects |= SUPPRESSED_EFFECT_LIGHTS;
                suppressedEffects |= SUPPRESSED_EFFECT_AMBIENT;
            }

            if ((otherSuppressedVisualEffects & SUPPRESSED_EFFECT_SCREEN_ON) != 0) {
                otherSuppressedVisualEffects |= SUPPRESSED_EFFECT_PEEK;
            }
            if ((otherSuppressedVisualEffects & SUPPRESSED_EFFECT_SCREEN_OFF) != 0) {
                otherSuppressedVisualEffects |= SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
                otherSuppressedVisualEffects |= SUPPRESSED_EFFECT_LIGHTS;
                otherSuppressedVisualEffects |= SUPPRESSED_EFFECT_AMBIENT;
            }

            if ((suppressedEffects & SUPPRESSED_EFFECT_SCREEN_ON)
                    != (otherSuppressedVisualEffects & SUPPRESSED_EFFECT_SCREEN_ON)) {
                int currSuppressedEffects = (suppressedEffects & SUPPRESSED_EFFECT_SCREEN_ON) != 0
                        ? otherSuppressedVisualEffects : suppressedEffects;
                if ((currSuppressedEffects & SUPPRESSED_EFFECT_PEEK) == 0) {
                    return false;
                }
            }

            if ((suppressedEffects & SUPPRESSED_EFFECT_SCREEN_OFF)
                    != (otherSuppressedVisualEffects & SUPPRESSED_EFFECT_SCREEN_OFF)) {
                int currSuppressedEffects = (suppressedEffects & SUPPRESSED_EFFECT_SCREEN_OFF) != 0
                        ? otherSuppressedVisualEffects : suppressedEffects;
                if ((currSuppressedEffects & SUPPRESSED_EFFECT_FULL_SCREEN_INTENT) == 0
                        || (currSuppressedEffects & SUPPRESSED_EFFECT_LIGHTS) == 0
                        || (currSuppressedEffects & SUPPRESSED_EFFECT_AMBIENT) == 0) {
                    return false;
                }
            }

            int thisWithoutOldEffects = suppressedEffects
                    & ~SUPPRESSED_EFFECT_SCREEN_ON
                    & ~SUPPRESSED_EFFECT_SCREEN_OFF;
            int otherWithoutOldEffects = otherSuppressedVisualEffects
                    & ~SUPPRESSED_EFFECT_SCREEN_ON
                    & ~SUPPRESSED_EFFECT_SCREEN_OFF;
            return thisWithoutOldEffects == otherWithoutOldEffects;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder().append("NotificationManager.Policy[")
                    .append("priorityCategories=")
                    .append(priorityCategoriesToString(priorityCategories))
                    .append(",priorityCallSenders=")
                    .append(prioritySendersToString(priorityCallSenders))
                    .append(",priorityMessageSenders=")
                    .append(prioritySendersToString(priorityMessageSenders))
                    .append(",priorityConvSenders=")
                    .append(conversationSendersToString(priorityConversationSenders))
                    .append(",suppressedVisualEffects=")
                    .append(suppressedEffectsToString(suppressedVisualEffects));
            if (Flags.modesApi()) {
                sb.append(",hasPriorityChannels=");
            } else {
                sb.append(",areChannelsBypassingDnd=");
            }
            sb.append((state == STATE_UNSET
                    ? "unset"
                    : ((state & STATE_CHANNELS_BYPASSING_DND) != 0)
                            ? "true"
                            : "false"));
            if (Flags.modesApi()) {
                sb.append(",allowPriorityChannels=")
                        .append((state == STATE_UNSET
                                ? "unset"
                                : (allowPriorityChannels() ? "true" : "false")));
            }
            return sb.append("]").toString();
        }

        /** @hide */
        public void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long pToken = proto.start(fieldId);

            bitwiseToProtoEnum(proto, PolicyProto.PRIORITY_CATEGORIES, priorityCategories);
            proto.write(PolicyProto.PRIORITY_CALL_SENDER, priorityCallSenders);
            proto.write(PolicyProto.PRIORITY_MESSAGE_SENDER, priorityMessageSenders);
            bitwiseToProtoEnum(
                    proto, PolicyProto.SUPPRESSED_VISUAL_EFFECTS, suppressedVisualEffects);

            proto.end(pToken);
        }

        private static void bitwiseToProtoEnum(ProtoOutputStream proto, long fieldId, int data) {
            for (int i = 1; data > 0; ++i, data >>>= 1) {
                if ((data & 1) == 1) {
                    proto.write(fieldId, i);
                }
            }
        }

        /**
         * @hide
         */
        public static int getAllSuppressedVisualEffects() {
            int effects = 0;
            for (int i = 0; i < ALL_SUPPRESSED_EFFECTS.length; i++) {
                effects |= ALL_SUPPRESSED_EFFECTS[i];
            }
            return effects;
        }

        /**
         * @hide
         */
        public static boolean areAllVisualEffectsSuppressed(int effects) {
            for (int i = 0; i < ALL_SUPPRESSED_EFFECTS.length; i++) {
                final int effect = ALL_SUPPRESSED_EFFECTS[i];
                if ((effects & effect) == 0) {
                    return false;
                }
            }
            return true;
        }

        private static int toggleEffects(int currentEffects, int[] effects, boolean suppress) {
            for (int i = 0; i < effects.length; i++) {
                final int effect = effects[i];
                if (suppress) {
                    currentEffects |= effect;
                } else {
                    currentEffects &= ~effect;
                }
            }
            return currentEffects;
        }

        public static String suppressedEffectsToString(int effects) {
            if (effects <= 0) return "";
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ALL_SUPPRESSED_EFFECTS.length; i++) {
                final int effect = ALL_SUPPRESSED_EFFECTS[i];
                if ((effects & effect) != 0) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(effectToString(effect));
                }
                effects &= ~effect;
            }
            if (effects != 0) {
                if (sb.length() > 0) sb.append(',');
                sb.append("UNKNOWN_").append(effects);
            }
            return sb.toString();
        }

        public static String priorityCategoriesToString(int priorityCategories) {
            if (priorityCategories == 0) return "";
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ALL_PRIORITY_CATEGORIES.length; i++) {
                final int priorityCategory = ALL_PRIORITY_CATEGORIES[i];
                if ((priorityCategories & priorityCategory) != 0) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(priorityCategoryToString(priorityCategory));
                }
                priorityCategories &= ~priorityCategory;
            }
            if (priorityCategories != 0) {
                if (sb.length() > 0) sb.append(',');
                sb.append("PRIORITY_CATEGORY_UNKNOWN_").append(priorityCategories);
            }
            return sb.toString();
        }

        private static String effectToString(int effect) {
            switch (effect) {
                case SUPPRESSED_EFFECT_FULL_SCREEN_INTENT:
                    return "SUPPRESSED_EFFECT_FULL_SCREEN_INTENT";
                case SUPPRESSED_EFFECT_LIGHTS:
                    return "SUPPRESSED_EFFECT_LIGHTS";
                case SUPPRESSED_EFFECT_PEEK:
                    return "SUPPRESSED_EFFECT_PEEK";
                case SUPPRESSED_EFFECT_STATUS_BAR:
                    return "SUPPRESSED_EFFECT_STATUS_BAR";
                case SUPPRESSED_EFFECT_BADGE:
                    return "SUPPRESSED_EFFECT_BADGE";
                case SUPPRESSED_EFFECT_AMBIENT:
                    return "SUPPRESSED_EFFECT_AMBIENT";
                case SUPPRESSED_EFFECT_NOTIFICATION_LIST:
                    return "SUPPRESSED_EFFECT_NOTIFICATION_LIST";
                case SUPPRESSED_EFFECT_SCREEN_OFF:
                    return "SUPPRESSED_EFFECT_SCREEN_OFF";
                case SUPPRESSED_EFFECT_SCREEN_ON:
                    return "SUPPRESSED_EFFECT_SCREEN_ON";
                case SUPPRESSED_EFFECTS_UNSET:
                    return "SUPPRESSED_EFFECTS_UNSET";
                default: return "UNKNOWN_" + effect;
            }
        }

        private static String priorityCategoryToString(int priorityCategory) {
            switch (priorityCategory) {
                case PRIORITY_CATEGORY_REMINDERS: return "PRIORITY_CATEGORY_REMINDERS";
                case PRIORITY_CATEGORY_EVENTS: return "PRIORITY_CATEGORY_EVENTS";
                case PRIORITY_CATEGORY_MESSAGES: return "PRIORITY_CATEGORY_MESSAGES";
                case PRIORITY_CATEGORY_CALLS: return "PRIORITY_CATEGORY_CALLS";
                case PRIORITY_CATEGORY_REPEAT_CALLERS: return "PRIORITY_CATEGORY_REPEAT_CALLERS";
                case PRIORITY_CATEGORY_ALARMS: return "PRIORITY_CATEGORY_ALARMS";
                case PRIORITY_CATEGORY_MEDIA: return "PRIORITY_CATEGORY_MEDIA";
                case PRIORITY_CATEGORY_SYSTEM: return "PRIORITY_CATEGORY_SYSTEM";
                case PRIORITY_CATEGORY_CONVERSATIONS: return "PRIORITY_CATEGORY_CONVERSATIONS";
                default: return "PRIORITY_CATEGORY_UNKNOWN_" + priorityCategory;
            }
        }

        public static String prioritySendersToString(int prioritySenders) {
            switch (prioritySenders) {
                case PRIORITY_SENDERS_ANY: return "PRIORITY_SENDERS_ANY";
                case PRIORITY_SENDERS_CONTACTS: return "PRIORITY_SENDERS_CONTACTS";
                case PRIORITY_SENDERS_STARRED: return "PRIORITY_SENDERS_STARRED";
                default: return "PRIORITY_SENDERS_UNKNOWN_" + prioritySenders;
            }
        }

        /**
         * @hide
         */
        public static @NonNull String conversationSendersToString(int priorityConversationSenders) {
            switch (priorityConversationSenders) {
                case CONVERSATION_SENDERS_ANYONE:
                    return "anyone";
                case CONVERSATION_SENDERS_IMPORTANT:
                    return "important";
                case CONVERSATION_SENDERS_NONE:
                    return "none";
                case CONVERSATION_SENDERS_UNSET:
                    return "unset";
            }
            return "invalidConversationType{" + priorityConversationSenders + "}";
        }

        public static final @android.annotation.NonNull Parcelable.Creator<Policy> CREATOR
                = new Parcelable.Creator<Policy>() {
            @Override
            public Policy createFromParcel(Parcel in) {
                return new Policy(in);
            }

            @Override
            public Policy[] newArray(int size) {
                return new Policy[size];
            }
        };

        /** @hide **/
        public boolean allowAlarms() {
            return (priorityCategories & PRIORITY_CATEGORY_ALARMS) != 0;
        }

        /** @hide **/
        public boolean allowMedia() {
            return (priorityCategories & PRIORITY_CATEGORY_MEDIA) != 0;
        }

        /** @hide **/
        public boolean allowSystem() {
            return (priorityCategories & PRIORITY_CATEGORY_SYSTEM) != 0;
        }

        /** @hide **/
        public boolean allowRepeatCallers() {
            return (priorityCategories & PRIORITY_CATEGORY_REPEAT_CALLERS) != 0;
        }

        /** @hide **/
        public boolean allowCalls() {
            return (priorityCategories & PRIORITY_CATEGORY_CALLS) != 0;
        }

        /** @hide **/
        public boolean allowConversations() {
            return (priorityCategories & PRIORITY_CATEGORY_CONVERSATIONS) != 0;
        }

        /** @hide **/
        public boolean allowMessages() {
            return (priorityCategories & PRIORITY_CATEGORY_MESSAGES) != 0;
        }

        /** @hide **/
        public boolean allowEvents() {
            return (priorityCategories & PRIORITY_CATEGORY_EVENTS) != 0;
        }

        /** @hide **/
        public boolean allowReminders() {
            return (priorityCategories & PRIORITY_CATEGORY_REMINDERS) != 0;
        }

        /** @hide **/
        @PrioritySenders
        public int allowCallsFrom() {
            return priorityCallSenders;
        }

        /** @hide **/
        @PrioritySenders
        public int allowMessagesFrom() {
            return priorityMessageSenders;
        }

        /** @hide **/
        @ConversationSenders
        public int allowConversationsFrom() {
            return priorityConversationSenders;
        }

        /** @hide **/
        public boolean showFullScreenIntents() {
            return (suppressedVisualEffects & SUPPRESSED_EFFECT_FULL_SCREEN_INTENT) == 0;
        }

        /** @hide **/
        public boolean showLights() {
            return (suppressedVisualEffects & SUPPRESSED_EFFECT_LIGHTS) == 0;
        }

        /** @hide **/
        public boolean showPeeking() {
            return (suppressedVisualEffects & SUPPRESSED_EFFECT_PEEK) == 0;
        }

        /** @hide **/
        public boolean showStatusBarIcons() {
            return (suppressedVisualEffects & SUPPRESSED_EFFECT_STATUS_BAR) == 0;
        }

        /** @hide **/
        public boolean showAmbient() {
            return (suppressedVisualEffects & SUPPRESSED_EFFECT_AMBIENT) == 0;
        }

        /** @hide **/
        public boolean showBadges() {
            return (suppressedVisualEffects & SUPPRESSED_EFFECT_BADGE) == 0;
        }

        /** @hide **/
        public boolean showInNotificationList() {
            return (suppressedVisualEffects & SUPPRESSED_EFFECT_NOTIFICATION_LIST) == 0;
        }

        /** @hide **/
        @FlaggedApi(Flags.FLAG_MODES_API)
        @TestApi // so CTS tests can read this state without having to use implementation detail
        public boolean allowPriorityChannels() {
            if (state == STATE_UNSET) {
                return true; // default
            }
            return (state & STATE_PRIORITY_CHANNELS_BLOCKED) == 0;
        }

        /** @hide */
        @FlaggedApi(Flags.FLAG_MODES_API)
        public boolean hasPriorityChannels() {
            return (state & STATE_CHANNELS_BYPASSING_DND) != 0;
        }

        /** @hide **/
        @FlaggedApi(Flags.FLAG_MODES_API)
        public static int policyState(boolean hasPriorityChannels, boolean allowPriorityChannels) {
            int state = 0;
            if (hasPriorityChannels) {
                state |= STATE_CHANNELS_BYPASSING_DND;
            }
            if (!allowPriorityChannels) {
                state |= STATE_PRIORITY_CHANNELS_BLOCKED;
            }
            return state;
        }

        /**
         * returns a deep copy of this policy
         * @hide
         */
        public Policy copy() {
            final Parcel parcel = Parcel.obtain();
            try {
                writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                return new Policy(parcel);
            } finally {
                parcel.recycle();
            }
        }
    }

    /**
     * Recover a list of active notifications: ones that have been posted by the calling app that
     * have not yet been dismissed by the user or {@link #cancel(String, int)}ed by the app.
     *
     * <p><Each notification is embedded in a {@link StatusBarNotification} object, including the
     * original <code>tag</code> and <code>id</code> supplied to
     * {@link #notify(String, int, Notification) notify()}
     * (via {@link StatusBarNotification#getTag() getTag()} and
     * {@link StatusBarNotification#getId() getId()}) as well as a copy of the original
     * {@link Notification} object (via {@link StatusBarNotification#getNotification()}).
     * </p>
     * <p>From {@link Build.VERSION_CODES#Q}, will also return notifications you've posted as an
     * app's notification delegate via
     * {@link NotificationManager#notifyAsPackage(String, String, int, Notification)}.
     * </p>
     *
     * @return An array of {@link StatusBarNotification}.
     */
    public StatusBarNotification[] getActiveNotifications() {
        final INotificationManager service = getService();
        final String pkg = mContext.getPackageName();
        try {
            final ParceledListSlice<StatusBarNotification> parceledList
                    = service.getAppActiveNotifications(pkg, mContext.getUserId());
            if (parceledList != null) {
                final List<StatusBarNotification> list = parceledList.getList();
                return list.toArray(new StatusBarNotification[list.size()]);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return new StatusBarNotification[0];
    }

    /**
     * Gets the current notification interruption filter.
     * <p>
     * The interruption filter defines which notifications are allowed to
     * interrupt the user (e.g. via sound &amp; vibration) and is applied
     * globally.
     */
    public final @InterruptionFilter int getCurrentInterruptionFilter() {
        final INotificationManager service = getService();
        try {
            return zenModeToInterruptionFilter(service.getZenMode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the current notification interruption filter.
     * <p>
     * The interruption filter defines which notifications are allowed to
     * interrupt the user (e.g. via sound &amp; vibration) and is applied
     * globally.
     *
     * <p>Apps targeting {@link Build.VERSION_CODES#VANILLA_ICE_CREAM} and above (with some
     * exceptions, such as companion device managers) cannot modify the global interruption filter.
     * Calling this method will instead activate or deactivate an {@link AutomaticZenRule}
     * associated to the app, using a {@link ZenPolicy} that corresponds to the {@link Policy}
     * supplied to {@link #setNotificationPolicy(Policy)} (or the global policy when one wasn't
     * provided).
     *
     * <p> Only available if policy access is granted to this package. See
     * {@link #isNotificationPolicyAccessGranted}.
     */
    public final void setInterruptionFilter(@InterruptionFilter int interruptionFilter) {
        setInterruptionFilter(interruptionFilter, /* fromUser= */ false);
    }

    /** @hide */
    public final void setInterruptionFilter(@InterruptionFilter int interruptionFilter,
            boolean fromUser) {
        final INotificationManager service = getService();
        try {
            service.setInterruptionFilter(mContext.getOpPackageName(), interruptionFilter,
                    fromUser);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether a call from the provided URI is permitted to notify the user.
     * <p>
     * A true return value indicates one of the following: Do Not Disturb is not currently active;
     * or the caller is a repeat caller and the current policy allows interruptions from repeat
     * callers; or the caller is in the user's set of contacts whose calls are allowed to interrupt
     * Do Not Disturb.
     * </p>
     * <p>
     * If Do Not Disturb is enabled and either no interruptions or only alarms are allowed, this
     * method will return false regardless of input.
     * </p>
     * <p>
     * The provided URI should be a <code>tel:</code> or <code>mailto:</code> schema URI indicating
     * the source of the call. For an accurate answer regarding whether the caller matches the
     * user's permitted contacts, the path part of the URI must match an entry the Contacts database
     * in the appropriate column.
     * </p>
     * <p>
     * Passing in a {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI} is also
     * permissible, but should only be used for priority contact interruptions and may not provide
     * accurate results in the case of repeat callers.
     * </p>
     * <p>
     * See also {@link Person.Builder#setUri} and
     * {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI}
     * for more information.
     * </p>
     * <p>
     * Callers of this method must have notification listener access, permission to read contacts,
     * or have system permissions.
     * </p>
     * <p>
     * NOTE: This method calls into Contacts, which may take some time, and should not be called
     * on the main thread.
     * </p>
     *
     * @param uri A URI representing a caller. Must not be null.
     * @return A boolean indicating whether a call from the URI provided would be allowed to
     *         interrupt the user given the current filter.
     */
    @WorkerThread
    public boolean matchesCallFilter(@NonNull Uri uri) {
        Bundle extras = new Bundle();
        ArrayList<Person> pList = new ArrayList<>();
        pList.add(new Person.Builder().setUri(uri.toString()).build());
        extras.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, pList);

        return matchesCallFilter(extras);
    }

    /** @hide */
    public static int zenModeToInterruptionFilter(int zen) {
        switch (zen) {
            case Global.ZEN_MODE_OFF: return INTERRUPTION_FILTER_ALL;
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS: return INTERRUPTION_FILTER_PRIORITY;
            case Global.ZEN_MODE_ALARMS: return INTERRUPTION_FILTER_ALARMS;
            case Global.ZEN_MODE_NO_INTERRUPTIONS: return INTERRUPTION_FILTER_NONE;
            default: return INTERRUPTION_FILTER_UNKNOWN;
        }
    }

    /** @hide */
    public static int zenModeFromInterruptionFilter(int interruptionFilter, int defValue) {
        switch (interruptionFilter) {
            case INTERRUPTION_FILTER_ALL: return Global.ZEN_MODE_OFF;
            case INTERRUPTION_FILTER_PRIORITY: return Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
            case INTERRUPTION_FILTER_ALARMS: return Global.ZEN_MODE_ALARMS;
            case INTERRUPTION_FILTER_NONE:  return Global.ZEN_MODE_NO_INTERRUPTIONS;
            default: return defValue;
        }
    }

    /**
     * Callback to receive updates when a call notification has been posted or removed
     * @hide
     */
    @SystemApi
    @FlaggedApi(android.service.notification.Flags.FLAG_CALLSTYLE_CALLBACK_API)
    public interface CallNotificationEventListener {
        /**
         *  Called when a call notification was posted by a package this listener
         *  has registered for.
         * @param packageName package name of the app that posted the removed notification
         */
        @FlaggedApi(android.service.notification.Flags.FLAG_CALLSTYLE_CALLBACK_API)
        void onCallNotificationPosted(@NonNull String packageName, @NonNull UserHandle userHandle);

        /**
         *  Called when a call notification was removed by a package this listener
         *  has registered for.
         * @param packageName package name of the app that removed notification
         */
        @FlaggedApi(android.service.notification.Flags.FLAG_CALLSTYLE_CALLBACK_API)
        void onCallNotificationRemoved(@NonNull String packageName, @NonNull UserHandle userHandle);
    }

    private static class CallNotificationEventCallbackStub extends
            ICallNotificationEventCallback.Stub {
        final String mPackageName;
        final UserHandle mUserHandle;
        final Executor mExecutor;
        final CallNotificationEventListener mListener;

        CallNotificationEventCallbackStub(@NonNull String packageName,
                @NonNull UserHandle userHandle, @NonNull @CallbackExecutor Executor executor,
                @NonNull CallNotificationEventListener listener) {
            mPackageName = packageName;
            mUserHandle = userHandle;
            mExecutor = executor;
            mListener = listener;
        }

        @FlaggedApi(android.service.notification.Flags.FLAG_CALLSTYLE_CALLBACK_API)
        @Override
        public void onCallNotificationPosted(String packageName, UserHandle userHandle) {
            mExecutor.execute(() -> mListener.onCallNotificationPosted(packageName, userHandle));
        }

        @FlaggedApi(android.service.notification.Flags.FLAG_CALLSTYLE_CALLBACK_API)
        @Override
        public void onCallNotificationRemoved(String packageName, UserHandle userHandle) {
            mExecutor.execute(() -> mListener.onCallNotificationRemoved(packageName, userHandle));
        }
    }

    /**
     * Register a listener to be notified when a call notification is posted or removed
     * for a specific package and user.
     *
     * @param packageName Which package to monitor
     * @param userHandle Which user to monitor
     * @param executor Callback will run on this executor
     * @param listener Listener to register
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
        android.Manifest.permission.INTERACT_ACROSS_USERS,
        android.Manifest.permission.ACCESS_NOTIFICATIONS})
    @FlaggedApi(android.service.notification.Flags.FLAG_CALLSTYLE_CALLBACK_API)
    @SuppressLint("UserHandle")
    public void registerCallNotificationEventListener(@NonNull String packageName,
            @NonNull UserHandle userHandle, @NonNull @CallbackExecutor Executor executor,
            @NonNull CallNotificationEventListener listener) {
        checkRequired("packageName", packageName);
        checkRequired("userHandle", userHandle);
        checkRequired("executor", executor);
        checkRequired("listener", listener);
        INotificationManager service = getService();
        try {
            synchronized (mCallNotificationEventCallbacks) {
                CallNotificationEventCallbackStub callbackStub =
                        new CallNotificationEventCallbackStub(packageName, userHandle,
                                executor, listener);
                mCallNotificationEventCallbacks.put(listener, callbackStub);

                service.registerCallNotificationEventListener(packageName, userHandle,
                        callbackStub);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister a listener that was previously
     * registered with {@link #registerCallNotificationEventListener}
     *
     * @param listener Listener to unregister
     * @hide
     */
    @SystemApi
    @FlaggedApi(android.service.notification.Flags.FLAG_CALLSTYLE_CALLBACK_API)
    @RequiresPermission(allOf = {
        android.Manifest.permission.INTERACT_ACROSS_USERS,
        android.Manifest.permission.ACCESS_NOTIFICATIONS})
    public void unregisterCallNotificationEventListener(
            @NonNull CallNotificationEventListener listener) {
        checkRequired("listener", listener);
        INotificationManager service = getService();
        try {
            synchronized (mCallNotificationEventCallbacks) {
                CallNotificationEventCallbackStub callbackStub =
                        mCallNotificationEventCallbacks.remove(listener);
                if (callbackStub != null) {
                    service.unregisterCallNotificationEventListener(callbackStub.mPackageName,
                            callbackStub.mUserHandle, callbackStub);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

}

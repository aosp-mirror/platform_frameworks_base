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

package com.android.server.notification;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManagerInternal.ServiceNotificationPolicy.NOT_FOREGROUND_SERVICE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
import static android.app.Notification.FLAG_AUTOGROUP_SUMMARY;
import static android.app.Notification.FLAG_AUTO_CANCEL;
import static android.app.Notification.FLAG_BUBBLE;
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.Notification.FLAG_FSI_REQUESTED_BUT_DENIED;
import static android.app.Notification.FLAG_INSISTENT;
import static android.app.Notification.FLAG_NO_CLEAR;
import static android.app.Notification.FLAG_NO_DISMISS;
import static android.app.Notification.FLAG_ONGOING_EVENT;
import static android.app.Notification.FLAG_ONLY_ALERT_ONCE;
import static android.app.Notification.FLAG_USER_INITIATED_JOB;
import static android.app.NotificationChannel.CONVERSATION_CHANNEL_ID_FORMAT;
import static android.app.NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED;
import static android.app.NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED;
import static android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED;
import static android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED_INTERNAL;
import static android.app.NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED;
import static android.app.NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED;
import static android.app.NotificationManager.ACTION_NOTIFICATION_LISTENER_ENABLED_CHANGED;
import static android.app.NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_ALL;
import static android.app.NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID;
import static android.app.NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.IMPORTANCE_NONE;
import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECTS_UNSET;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;
import static android.content.Context.BIND_ALLOW_WHITELIST_MANAGEMENT;
import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Context.BIND_FOREGROUND_SERVICE;
import static android.content.Context.BIND_NOT_PERCEPTIBLE;
import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_TELECOM;
import static android.content.pm.PackageManager.FEATURE_TELEVISION;
import static android.content.pm.PackageManager.MATCH_ALL;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
import static android.os.Flags.allowPrivateProfile;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;
import static android.os.PowerWhitelistManager.REASON_NOTIFICATION_SERVICE;
import static android.os.PowerWhitelistManager.TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.os.UserHandle.USER_NULL;
import static android.os.UserHandle.USER_SYSTEM;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_ALERTING;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_CONVERSATIONS;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_ONGOING;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_SILENT;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_EFFECTS;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS;
import static android.service.notification.NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES;
import static android.service.notification.NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_DELETED;
import static android.service.notification.NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_ASSISTANT_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_REMOVED;
import static android.service.notification.NotificationListenerService.REASON_CLEAR_DATA;
import static android.service.notification.NotificationListenerService.REASON_CLICK;
import static android.service.notification.NotificationListenerService.REASON_ERROR;
import static android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED;
import static android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_LOCKDOWN;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_BANNED;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_CHANGED;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_SUSPENDED;
import static android.service.notification.NotificationListenerService.REASON_PROFILE_TURNED_OFF;
import static android.service.notification.NotificationListenerService.REASON_SNOOZED;
import static android.service.notification.NotificationListenerService.REASON_TIMEOUT;
import static android.service.notification.NotificationListenerService.REASON_UNAUTOBUNDLED;
import static android.service.notification.NotificationListenerService.REASON_USER_STOPPED;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_DEMOTED;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_PROMOTED;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_UNCHANGED;
import static android.service.notification.NotificationListenerService.TRIM_FULL;
import static android.service.notification.NotificationListenerService.TRIM_LIGHT;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static com.android.internal.util.FrameworkStatsLog.DND_MODE_RULE;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_CHANNEL_GROUP_PREFERENCES;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_PREFERENCES;
import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.server.am.PendingIntentRecord.FLAG_ACTIVITY_SENDER;
import static com.android.server.am.PendingIntentRecord.FLAG_BROADCAST_SENDER;
import static com.android.server.am.PendingIntentRecord.FLAG_SERVICE_SENDER;
import static com.android.server.notification.Flags.expireBitmaps;
import static com.android.server.policy.PhoneWindowManager.TOAST_WINDOW_ANIM_BUFFER;
import static com.android.server.policy.PhoneWindowManager.TOAST_WINDOW_TIMEOUT;
import static com.android.server.utils.PriorityDump.PRIORITY_ARG;
import static com.android.server.utils.PriorityDump.PRIORITY_ARG_CRITICAL;
import static com.android.server.utils.PriorityDump.PRIORITY_ARG_NORMAL;

import android.Manifest;
import android.Manifest.permission;
import android.annotation.DurationMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerInternal.ServiceNotificationPolicy;
import android.app.ActivityTaskManager;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AutomaticZenRule;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.ITransientNotification;
import android.app.ITransientNotificationCallback;
import android.app.IUriGrantsManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationHistory;
import android.app.NotificationHistory.HistoricalNotification;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
import android.app.PendingIntent;
import android.app.RemoteServiceException.BadForegroundServiceNotificationException;
import android.app.RemoteServiceException.BadUserInitiatedJobNotificationException;
import android.app.StatsManager;
import android.app.StatusBarManager;
import android.app.UriGrantsManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.backup.BackupManager;
import android.app.compat.CompatChanges;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.ICompanionDeviceManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.LoggingOnly;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ServiceInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.IRingtonePlayer;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DeviceIdleManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationEffect;
import android.os.WorkSource;
import android.permission.PermissionManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.service.notification.Adjustment;
import android.service.notification.Condition;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.IConditionProvider;
import android.service.notification.INotificationListener;
import android.service.notification.IStatusBarNotificationHolder;
import android.service.notification.ListenersDisablingEffectsProto;
import android.service.notification.NotificationAssistantService;
import android.service.notification.NotificationListenerFilter;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationRankingUpdate;
import android.service.notification.NotificationRecordProto;
import android.service.notification.NotificationServiceDumpProto;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeProto;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.StatsEvent;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags;
import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags.NotificationFlags;
import com.android.internal.logging.InstanceId;
import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SomeArgs;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.TriPredicate;
import com.android.internal.widget.LockPatternUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.DeviceIdleInternal;
import com.android.server.EventLogTags;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LogicalLight;
import com.android.server.notification.ManagedServices.ManagedServiceInfo;
import com.android.server.notification.ManagedServices.UserProfiles;
import com.android.server.notification.toast.CustomToastRecord;
import com.android.server.notification.toast.TextToastRecord;
import com.android.server.notification.toast.ToastRecord;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.utils.Slogf;
import com.android.server.utils.quota.MultiRateLimiter;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.BackgroundActivityStartCallback;
import com.android.server.wm.WindowManagerInternal;

import libcore.io.IoUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/** {@hide} */
public class NotificationManagerService extends SystemService {
    public static final String TAG = "NotificationService";
    public static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    public static final boolean ENABLE_CHILD_NOTIFICATIONS
            = SystemProperties.getBoolean("debug.child_notifs", true);

    // pullStats report request: undecorated remote view stats
    public static final int REPORT_REMOTE_VIEWS = 0x01;

    static final boolean DEBUG_INTERRUPTIVENESS = SystemProperties.getBoolean(
            "debug.notification.interruptiveness", false);

    static final int MAX_PACKAGE_NOTIFICATIONS = 50;
    static final float DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE = 5f;

    // To limit bad UX of seeing a toast many seconds after if was triggered.
    static final int MAX_PACKAGE_TOASTS = 5;

    // message codes
    static final int MESSAGE_DURATION_REACHED = 2;
    // 3: removed to a different handler
    static final int MESSAGE_SEND_RANKING_UPDATE = 4;
    static final int MESSAGE_LISTENER_HINTS_CHANGED = 5;
    static final int MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED = 6;
    static final int MESSAGE_FINISH_TOKEN_TIMEOUT = 7;
    static final int MESSAGE_ON_PACKAGE_CHANGED = 8;

    static final Duration BITMAP_DURATION = Duration.ofHours(24);

    // ranking thread messages
    private static final int MESSAGE_RECONSIDER_RANKING = 1000;
    private static final int MESSAGE_RANKING_SORT = 1001;

    static final int LONG_DELAY = TOAST_WINDOW_TIMEOUT - TOAST_WINDOW_ANIM_BUFFER; // 3.5 seconds
    static final int SHORT_DELAY = 2000; // 2 seconds

    // 1 second past the ANR timeout.
    static final int FINISH_TOKEN_TIMEOUT = 11 * 1000;

    static final long SNOOZE_UNTIL_UNSPECIFIED = -1;

    static final int INVALID_UID = -1;
    static final String ROOT_PKG = "root";

    static final String[] ALLOWED_ADJUSTMENTS = new String[] {
            Adjustment.KEY_PEOPLE,
            Adjustment.KEY_SNOOZE_CRITERIA,
            Adjustment.KEY_USER_SENTIMENT,
            Adjustment.KEY_CONTEXTUAL_ACTIONS,
            Adjustment.KEY_TEXT_REPLIES,
            Adjustment.KEY_IMPORTANCE,
            Adjustment.KEY_IMPORTANCE_PROPOSAL,
            Adjustment.KEY_SENSITIVE_CONTENT,
            Adjustment.KEY_RANKING_SCORE,
            Adjustment.KEY_NOT_CONVERSATION
    };

    static final String[] NON_BLOCKABLE_DEFAULT_ROLES = new String[] {
            RoleManager.ROLE_DIALER,
            RoleManager.ROLE_EMERGENCY
    };

    // Used for rate limiting toasts by package.
    static final String TOAST_QUOTA_TAG = "toast_quota_tag";

    // This constant defines rate limits applied to showing toasts. The numbers are set in a way
    // such that an aggressive toast showing strategy would result in a roughly 1.5x longer wait
    // time (before the package is allowed to show toasts again) each time the toast rate limit is
    // reached. It's meant to protect the user against apps spamming them with toasts (either
    // accidentally or on purpose).
    private static final MultiRateLimiter.RateLimit[] TOAST_RATE_LIMITS = {
            MultiRateLimiter.RateLimit.create(3, Duration.ofSeconds(20)),
            MultiRateLimiter.RateLimit.create(5, Duration.ofSeconds(42)),
            MultiRateLimiter.RateLimit.create(6, Duration.ofSeconds(68)),
    };

    // When #matchesCallFilter is called from the ringer, wait at most
    // 3s to resolve the contacts. This timeout is required since
    // ContactsProvider might take a long time to start up.
    //
    // Return STARRED_CONTACT when the timeout is hit in order to avoid
    // missed calls in ZEN mode "Important".
    static final int MATCHES_CALL_FILTER_CONTACTS_TIMEOUT_MS = 3000;
    static final float MATCHES_CALL_FILTER_TIMEOUT_AFFINITY =
            ValidateNotificationPeople.STARRED_CONTACT;

    /** notification_enqueue status value for a newly enqueued notification. */
    private static final int EVENTLOG_ENQUEUE_STATUS_NEW = 0;

    /** notification_enqueue status value for an existing notification. */
    private static final int EVENTLOG_ENQUEUE_STATUS_UPDATE = 1;

    /** notification_enqueue status value for an ignored notification. */
    private static final int EVENTLOG_ENQUEUE_STATUS_IGNORED = 2;
    private static final long MIN_PACKAGE_OVERRATE_LOG_INTERVAL = 5000; // milliseconds

    private static final long DELAY_FOR_ASSISTANT_TIME = 200;

    private static final String ACTION_NOTIFICATION_TIMEOUT =
            NotificationManagerService.class.getSimpleName() + ".TIMEOUT";
    private static final int REQUEST_CODE_TIMEOUT = 1;
    private static final String SCHEME_TIMEOUT = "timeout";
    private static final String EXTRA_KEY = "key";

    private static final int NOTIFICATION_INSTANCE_ID_MAX = (1 << 13);

    // States for the review permissions notification
    static final int REVIEW_NOTIF_STATE_UNKNOWN = -1;
    static final int REVIEW_NOTIF_STATE_SHOULD_SHOW = 0;
    static final int REVIEW_NOTIF_STATE_USER_INTERACTED = 1;
    static final int REVIEW_NOTIF_STATE_DISMISSED = 2;
    static final int REVIEW_NOTIF_STATE_RESHOWN = 3;

    // Action strings for review permissions notification
    static final String REVIEW_NOTIF_ACTION_REMIND = "REVIEW_NOTIF_ACTION_REMIND";
    static final String REVIEW_NOTIF_ACTION_DISMISS = "REVIEW_NOTIF_ACTION_DISMISS";
    static final String REVIEW_NOTIF_ACTION_CANCELED = "REVIEW_NOTIF_ACTION_CANCELED";

    /**
     * Apps that post custom toasts in the background will have those blocked. Apps can
     * still post toasts created with
     * {@link android.widget.Toast#makeText(Context, CharSequence, int)} and its variants while
     * in the background.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long CHANGE_BACKGROUND_CUSTOM_TOAST_BLOCK = 128611929L;

    /**
     * Activity starts coming from broadcast receivers or services in response to notification and
     * notification action clicks will be blocked for UX and performance reasons. Instead start the
     * activity directly from the PendingIntent.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    private static final long NOTIFICATION_TRAMPOLINE_BLOCK = 167676448L;

    /**
     * Activity starts coming from broadcast receivers or services in response to notification and
     * notification action clicks will be blocked for UX and performance reasons for previously
     * exempt role holders (browser).
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.S_V2)
    private static final long NOTIFICATION_TRAMPOLINE_BLOCK_FOR_EXEMPT_ROLES = 227752274L;

    /**
     * Whether a notification listeners can understand new, more specific, cancellation reasons.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    private static final long NOTIFICATION_CANCELLATION_REASONS = 175319604L;

    /**
     * Rate limit showing toasts, on a per package basis.
     *
     * It limits the number of {@link android.widget.Toast#show()} calls to prevent overburdening
     * the user with too many toasts in a limited time. Any attempt to show more toasts than allowed
     * in a certain time frame will result in the toast being discarded.
     */
    @ChangeId
    @LoggingOnly
    private static final long RATE_LIMIT_TOASTS = 174840628L;

    /**
     * Whether listeners understand the more specific reason provided for notification
     * cancellations from an assistant, rather than using the more general REASON_LISTENER_CANCEL.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.S_V2)
    private static final long NOTIFICATION_LOG_ASSISTANT_CANCEL = 195579280L;

    /**
     * NO_CLEAR flag will be set for any media notification.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    static final long ENFORCE_NO_CLEAR_FLAG_ON_MEDIA_NOTIFICATION = 264179692L;

    /**
     * App calls to {@link android.app.NotificationManager#setInterruptionFilter} and
     * {@link android.app.NotificationManager#setNotificationPolicy} manage DND through the
     * creation and activation of an implicit {@link android.app.AutomaticZenRule}.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    static final long MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES = 308670109L;

    private static final Duration POST_WAKE_LOCK_TIMEOUT = Duration.ofSeconds(30);

    private IActivityManager mAm;
    private ActivityTaskManagerInternal mAtm;
    private ActivityManager mActivityManager;
    private ActivityManagerInternal mAmi;
    private IPackageManager mPackageManager;
    private PackageManager mPackageManagerClient;
    PackageManagerInternal mPackageManagerInternal;
    private PermissionManager mPermissionManager;
    private PermissionPolicyInternal mPermissionPolicyInternal;
    AudioManager mAudioManager;
    AudioManagerInternal mAudioManagerInternal;
    // Can be null for wear
    @Nullable StatusBarManagerInternal mStatusBar;
    private WindowManagerInternal mWindowManagerInternal;
    private AlarmManager mAlarmManager;
    private ICompanionDeviceManager mCompanionManager;
    private AccessibilityManager mAccessibilityManager;
    private DeviceIdleManager mDeviceIdleManager;
    private IUriGrantsManager mUgm;
    private UriGrantsManagerInternal mUgmInternal;
    private volatile RoleObserver mRoleObserver;
    private UserManager mUm;
    private UserManagerInternal mUmInternal;
    private IPlatformCompat mPlatformCompat;
    private ShortcutHelper mShortcutHelper;
    private PermissionHelper mPermissionHelper;
    private UsageStatsManagerInternal mUsageStatsManagerInternal;
    private TelecomManager mTelecomManager;
    private PowerManager mPowerManager;
    private PostNotificationTrackerFactory mPostNotificationTrackerFactory;

    final IBinder mForegroundToken = new Binder();
    private WorkerHandler mHandler;
    private final HandlerThread mRankingThread = new HandlerThread("ranker",
            Process.THREAD_PRIORITY_BACKGROUND);

    private LogicalLight mNotificationLight;
    LogicalLight mAttentionLight;

    private boolean mUseAttentionLight;
    boolean mHasLight = true;
    boolean mSystemReady;

    private boolean mDisableNotificationEffects;
    private int mCallState;
    private String mSoundNotificationKey;
    private String mVibrateNotificationKey;

    private final SparseArray<ArraySet<ComponentName>> mListenersDisablingEffects =
            new SparseArray<>();
    private List<ComponentName> mEffectsSuppressors = new ArrayList<>();
    private int mListenerHints;  // right now, all hints are global
    private int mInterruptionFilter = NotificationListenerService.INTERRUPTION_FILTER_UNKNOWN;

    // for enabling and disabling notification pulse behavior
    boolean mScreenOn = true;
    protected boolean mInCallStateOffHook = false;
    boolean mNotificationPulseEnabled;

    private Uri mInCallNotificationUri;
    private AudioAttributes mInCallNotificationAudioAttributes;
    private float mInCallNotificationVolume;
    private Binder mCallNotificationToken = null;

    private SystemUiSystemPropertiesFlags.FlagResolver mFlagResolver;

    // used as a mutex for access to all active notifications & listeners
    final Object mNotificationLock = new Object();
    @GuardedBy("mNotificationLock")
    final ArrayList<NotificationRecord> mNotificationList = new ArrayList<>();
    @GuardedBy("mNotificationLock")
    final ArrayMap<String, NotificationRecord> mNotificationsByKey = new ArrayMap<>();
    @GuardedBy("mNotificationLock")
    final ArrayMap<String, InlineReplyUriRecord> mInlineReplyRecordsByKey = new ArrayMap<>();
    @GuardedBy("mNotificationLock")
    final ArrayList<NotificationRecord> mEnqueuedNotifications = new ArrayList<>();
    @GuardedBy("mNotificationLock")
    final ArrayMap<Integer, ArrayMap<String, String>> mAutobundledSummaries = new ArrayMap<>();
    final ArrayList<ToastRecord> mToastQueue = new ArrayList<>();
    // set of uids for which toast rate limiting is disabled
    @GuardedBy("mToastQueue")
    private final Set<Integer> mToastRateLimitingDisabledUids = new ArraySet<>();
    final ArrayMap<String, NotificationRecord> mSummaryByGroupKey = new ArrayMap<>();

    // True if the toast that's on top of the queue is being shown at the moment.
    @GuardedBy("mToastQueue")
    private boolean mIsCurrentToastShown = false;

    // Used for rate limiting toasts by package.
    private MultiRateLimiter mToastRateLimiter;

    private KeyguardManager mKeyguardManager;

    // The last key in this list owns the hardware.
    ArrayList<String> mLights = new ArrayList<>();

    private AppOpsManager mAppOps;
    private UsageStatsManagerInternal mAppUsageStats;
    private DevicePolicyManagerInternal mDpm;
    private StatsManager mStatsManager;
    private StatsPullAtomCallbackImpl mPullAtomCallback;

    private Archive mArchive;

    // Persistent storage for notification policy
    private AtomicFile mPolicyFile;

    private static final int DB_VERSION = 1;

    private static final String TAG_NOTIFICATION_POLICY = "notification-policy";
    private static final String ATTR_VERSION = "version";

    private static final String LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_TAG =
            "allow-secure-notifications-on-lockscreen";
    private static final String LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_VALUE = "value";

    @VisibleForTesting
    RankingHelper mRankingHelper;
    @VisibleForTesting
    PreferencesHelper mPreferencesHelper;
    private VibratorHelper mVibratorHelper;

    private final UserProfiles mUserProfiles = new UserProfiles();
    private NotificationListeners mListeners;
    private NotificationAssistants mAssistants;
    private ConditionProviders mConditionProviders;
    private NotificationUsageStats mUsageStats;
    private boolean mLockScreenAllowSecureNotifications = true;
    boolean mSystemExemptFromDismissal = false;

    private static final int MY_UID = Process.myUid();
    private static final int MY_PID = Process.myPid();
    private static final IBinder ALLOWLIST_TOKEN = new Binder();
    protected RankingHandler mRankingHandler;
    private long mLastOverRateLogTime;
    private float mMaxPackageEnqueueRate = DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE;

    private NotificationHistoryManager mHistoryManager;
    protected SnoozeHelper mSnoozeHelper;
    private GroupHelper mGroupHelper;
    private int mAutoGroupAtCount;
    private boolean mIsTelevision;
    private boolean mIsAutomotive;
    private boolean mNotificationEffectsEnabledForAutomotive;
    private DeviceConfig.OnPropertiesChangedListener mDeviceConfigChangedListener;
    protected NotificationAttentionHelper mAttentionHelper;

    private int mWarnRemoteViewsSizeBytes;
    private int mStripRemoteViewsSizeBytes;

    @VisibleForTesting
    protected boolean mShowReviewPermissionsNotification;

    private MetricsLogger mMetricsLogger;
    private NotificationChannelLogger mNotificationChannelLogger;
    private TriPredicate<String, Integer, String> mAllowedManagedServicePackages;

    private final SavePolicyFileRunnable mSavePolicyFile = new SavePolicyFileRunnable();
    private NotificationRecordLogger mNotificationRecordLogger;
    private InstanceIdSequence mNotificationInstanceIdSequence;
    private Set<String> mMsgPkgsAllowedAsConvos = new HashSet();
    private String mDefaultSearchSelectorPkg;

    // Broadcast intent receiver for notification permissions review-related intents
    private ReviewNotificationPermissionsReceiver mReviewNotificationPermissionsReceiver;

    static class Archive {
        final SparseArray<Boolean> mEnabled;
        final int mBufferSize;
        final Object mBufferLock = new Object();
        @GuardedBy("mBufferLock")
        final LinkedList<Pair<StatusBarNotification, Integer>> mBuffer;

        public Archive(int size) {
            mBufferSize = size;
            mBuffer = new LinkedList<>();
            mEnabled = new SparseArray<>();
        }

        public String toString() {
            final StringBuilder sb = new StringBuilder();
            final int N = mBuffer.size();
            sb.append("Archive (");
            sb.append(N);
            sb.append(" notification");
            sb.append((N == 1) ? ")" : "s)");
            return sb.toString();
        }

        public void record(StatusBarNotification sbn, int reason) {
            if (!mEnabled.get(sbn.getNormalizedUserId(), false)) {
                return;
            }
            synchronized (mBufferLock) {
                if (mBuffer.size() == mBufferSize) {
                    mBuffer.removeFirst();
                }

                // We don't want to store the heavy bits of the notification in the archive,
                // but other clients in the system process might be using the object, so we
                // store a (lightened) copy.
                mBuffer.addLast(new Pair<>(sbn.cloneLight(), reason));
            }
        }

        public Iterator<Pair<StatusBarNotification, Integer>> descendingIterator() {
            return mBuffer.descendingIterator();
        }

        public StatusBarNotification[] getArray(UserManager um, int count, boolean includeSnoozed) {
            ArrayList<Integer> currentUsers = new ArrayList<>();
            currentUsers.add(UserHandle.USER_ALL);
            Binder.withCleanCallingIdentity(() -> {
                for (int user : um.getProfileIds(ActivityManager.getCurrentUser(), false)) {
                    currentUsers.add(user);
                }
            });
            synchronized (mBufferLock) {
                if (count == 0) count = mBufferSize;
                List<StatusBarNotification> a = new ArrayList();
                Iterator<Pair<StatusBarNotification, Integer>> iter = descendingIterator();
                int i = 0;
                while (iter.hasNext() && i < count) {
                    Pair<StatusBarNotification, Integer> pair = iter.next();
                    if (pair.second != REASON_SNOOZED || includeSnoozed) {
                        if (currentUsers.contains(pair.first.getUserId())) {
                            i++;
                            a.add(pair.first);
                        }
                    }
                }
                return a.toArray(new StatusBarNotification[a.size()]);
            }
        }

        public void updateHistoryEnabled(@UserIdInt int userId, boolean enabled) {
            mEnabled.put(userId, enabled);

            if (!enabled) {
                synchronized (mBufferLock) {
                    for (int i = mBuffer.size() - 1; i >= 0; i--) {
                        if (userId == mBuffer.get(i).first.getNormalizedUserId()) {
                            mBuffer.remove(i);
                        }
                    }
                }
            }
        }

        // Remove notifications with the specified user & channel ID.
        public void removeChannelNotifications(String pkg, @UserIdInt int userId,
                String channelId) {
            synchronized (mBufferLock) {
                Iterator<Pair<StatusBarNotification, Integer>> bufferIter = descendingIterator();
                while (bufferIter.hasNext()) {
                    final Pair<StatusBarNotification, Integer> pair = bufferIter.next();
                    if (pair.first != null
                            && userId == pair.first.getNormalizedUserId()
                            && pkg != null && pkg.equals(pair.first.getPackageName())
                            && pair.first.getNotification() != null
                            && Objects.equals(channelId,
                            pair.first.getNotification().getChannelId())) {
                        bufferIter.remove();
                    }
                }
            }
        }

        void dumpImpl(PrintWriter pw, @NonNull DumpFilter filter) {
            synchronized (mBufferLock) {
                Iterator<Pair<StatusBarNotification, Integer>> iter = descendingIterator();
                int i = 0;
                while (iter.hasNext()) {
                    final StatusBarNotification sbn = iter.next().first;
                    if (filter != null && !filter.matches(sbn)) continue;
                    pw.println("    " + sbn);
                    if (++i >= 5) {
                        if (iter.hasNext()) pw.println("    ...");
                        break;
                    }
                }
            }
        }
    }

    void loadDefaultApprovedServices(int userId) {
        mListeners.loadDefaultsFromConfig();

        mConditionProviders.loadDefaultsFromConfig();

        mAssistants.loadDefaultsFromConfig();
    }

    protected void allowDefaultApprovedServices(int userId) {
        ArraySet<ComponentName> defaultListeners = mListeners.getDefaultComponents();
        for (int i = 0; i < defaultListeners.size(); i++) {
            ComponentName cn = defaultListeners.valueAt(i);
            allowNotificationListener(userId, cn);
        }

        allowDndPackages(userId);

        setDefaultAssistantForUser(userId);
    }

    @VisibleForTesting
    void allowDndPackages(int userId) {
        ArraySet<String> defaultDnds = mConditionProviders.getDefaultPackages();
        for (int i = 0; i < defaultDnds.size(); i++) {
            allowDndPackage(userId, defaultDnds.valueAt(i));
        }
        if (!isDNDMigrationDone(userId)) {
            setDNDMigrationDone(userId);
        }
    }

    @VisibleForTesting
    boolean isDNDMigrationDone(int userId) {
        return Settings.Secure.getIntForUser(getContext().getContentResolver(),
                Settings.Secure.DND_CONFIGS_MIGRATED, 0, userId) == 1;
    }

    @VisibleForTesting
    void setDNDMigrationDone(int userId) {
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.DND_CONFIGS_MIGRATED, 1, userId);
    }

    protected void migrateDefaultNAS() {
        final List<UserInfo> activeUsers = mUm.getUsers();
        for (UserInfo userInfo : activeUsers) {
            int userId = userInfo.getUserHandle().getIdentifier();
            if (isNASMigrationDone(userId)
                    || userInfo.isManagedProfile() || userInfo.isCloneProfile()) {
                continue;
            }
            List<ComponentName> allowedComponents = mAssistants.getAllowedComponents(userId);
            if (allowedComponents.size() == 0) { // user set to none
                Slog.d(TAG, "NAS Migration: user set to none, disable new NAS setting");
                setNASMigrationDone(userId);
                mAssistants.clearDefaults();
            } else {
                Slog.d(TAG, "Reset NAS setting and migrate to new default");
                resetAssistantUserSet(userId);
                // migrate to new default and set migration done
                mAssistants.resetDefaultAssistantsIfNecessary();
            }
        }
    }

    @VisibleForTesting
    void setNASMigrationDone(int baseUserId) {
        for (int profileId : mUm.getProfileIds(baseUserId, false)) {
            Settings.Secure.putIntForUser(getContext().getContentResolver(),
                    Settings.Secure.NAS_SETTINGS_UPDATED, 1, profileId);
        }
    }

    @VisibleForTesting
    boolean isNASMigrationDone(int userId) {
        return (Settings.Secure.getIntForUser(getContext().getContentResolver(),
                Settings.Secure.NAS_SETTINGS_UPDATED, 0, userId) == 1);
    }

    protected void setDefaultAssistantForUser(int userId) {
        String overrideDefaultAssistantString = DeviceConfig.getProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_DEFAULT_SERVICE);
        if (overrideDefaultAssistantString != null) {
            ArraySet<ComponentName> approved = mAssistants.queryPackageForServices(
                    overrideDefaultAssistantString,
                    MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                    userId);
            for (int i = 0; i < approved.size(); i++) {
                if (allowAssistant(userId, approved.valueAt(i))) return;
            }
        }
        ArraySet<ComponentName> defaults = mAssistants.getDefaultComponents();
        // We should have only one default assistant by default
        // allowAssistant should execute once in practice
        for (int i = 0; i < defaults.size(); i++) {
            ComponentName cn = defaults.valueAt(i);
            if (allowAssistant(userId, cn)) return;
        }
    }

    /**
     * This method will update the flags of the summary.
     * It will set it to FLAG_ONGOING_EVENT if any of its group members
     * has the same flag. It will delete the flag otherwise
     * @param userId user id of the autogroup summary
     * @param pkg package of the autogroup summary
     * @param flags the new flags for this summary
     * @param isAppForeground true if the app is currently in the foreground.
     */
    @GuardedBy("mNotificationLock")
    protected void updateAutobundledSummaryFlags(int userId, String pkg, int flags,
            boolean isAppForeground) {
        ArrayMap<String, String> summaries = mAutobundledSummaries.get(userId);
        if (summaries == null) {
            return;
        }
        String summaryKey = summaries.get(pkg);
        if (summaryKey == null) {
            return;
        }
        NotificationRecord summary = mNotificationsByKey.get(summaryKey);
        if (summary == null) {
            return;
        }
        int oldFlags = summary.getSbn().getNotification().flags;
        if (oldFlags != flags) {
            summary.getSbn().getNotification().flags = flags;
            mHandler.post(new EnqueueNotificationRunnable(userId, summary, isAppForeground,
                    mPostNotificationTrackerFactory.newTracker(null)));
        }
    }

    private void allowDndPackage(int userId, String packageName) {
        try {
            getBinderService().setNotificationPolicyAccessGrantedForUser(packageName, userId, true);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void allowNotificationListener(int userId, ComponentName cn) {

        try {
            getBinderService().setNotificationListenerAccessGrantedForUser(cn,
                        userId, true, true);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private boolean allowAssistant(int userId, ComponentName candidate) {
        Set<ComponentName> validAssistants =
                mAssistants.queryPackageForServices(
                        null,
                        MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, userId);
        if (candidate != null && validAssistants.contains(candidate)) {
            setNotificationAssistantAccessGrantedForUserInternal(candidate, userId, true, false);
            return true;
        }
        return false;
    }

    void readPolicyXml(InputStream stream, boolean forRestore, int userId)
            throws XmlPullParserException, NumberFormatException, IOException {
        final TypedXmlPullParser parser;
        if (forRestore) {
            parser = Xml.newFastPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());
        } else {
            parser = Xml.resolvePullParser(stream);
        }
        XmlUtils.beginDocument(parser, TAG_NOTIFICATION_POLICY);
        boolean migratedManagedServices = false;
        UserInfo userInfo = mUmInternal.getUserInfo(userId);
        boolean ineligibleForManagedServices = forRestore &&
                (userInfo.isManagedProfile() || userInfo.isCloneProfile());
        int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (ZenModeConfig.ZEN_TAG.equals(parser.getName())) {
                mZenModeHelper.readXml(parser, forRestore, userId);
            } else if (PreferencesHelper.TAG_RANKING.equals(parser.getName())){
                mPreferencesHelper.readXml(parser, forRestore, userId);
            }
            if (mListeners.getConfig().xmlTag.equals(parser.getName())) {
                if (ineligibleForManagedServices) {
                    continue;
                }
                mListeners.readXml(parser, mAllowedManagedServicePackages, forRestore, userId);
                migratedManagedServices = true;
            } else if (mAssistants.getConfig().xmlTag.equals(parser.getName())) {
                if (ineligibleForManagedServices) {
                    continue;
                }
                mAssistants.readXml(parser, mAllowedManagedServicePackages, forRestore, userId);
                migratedManagedServices = true;
            } else if (mConditionProviders.getConfig().xmlTag.equals(parser.getName())) {
                if (ineligibleForManagedServices) {
                    continue;
                }
                mConditionProviders.readXml(
                        parser, mAllowedManagedServicePackages, forRestore, userId);
                migratedManagedServices = true;
            } else if (mSnoozeHelper.XML_TAG_NAME.equals(parser.getName())) {
                mSnoozeHelper.readXml(parser, System.currentTimeMillis());
            }
            if (LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_TAG.equals(parser.getName())) {
                if (forRestore && userId != UserHandle.USER_SYSTEM) {
                    continue;
                }
                mLockScreenAllowSecureNotifications = parser.getAttributeBoolean(null,
                        LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_VALUE, true);
            }
        }

        if (!migratedManagedServices) {
            mListeners.migrateToXml();
            mAssistants.migrateToXml();
            mConditionProviders.migrateToXml();
            handleSavePolicyFile();
        }

        mAssistants.resetDefaultAssistantsIfNecessary();
        mPreferencesHelper.syncChannelsBypassingDnd();
    }

    @VisibleForTesting
    void resetDefaultDndIfNecessary() {
        boolean removed = false;
        final List<UserInfo> activeUsers = mUm.getAliveUsers();
        for (UserInfo userInfo : activeUsers) {
            int userId = userInfo.getUserHandle().getIdentifier();
            if (isDNDMigrationDone(userId)) {
                continue;
            }
            removed |= mConditionProviders.removeDefaultFromConfig(userId);
            mConditionProviders.resetDefaultFromConfig();
            allowDndPackages(userId);
        }
        if (removed) {
            handleSavePolicyFile();
        }
    }

    @VisibleForTesting
    protected void loadPolicyFile() {
        if (DBG) Slog.d(TAG, "loadPolicyFile");
        synchronized (mPolicyFile) {
            InputStream infile = null;
            try {
                infile = mPolicyFile.openRead();
                readPolicyXml(infile, false /*forRestore*/, UserHandle.USER_ALL);

                // We re-load the default dnd packages to allow the newly added and denined.
                final boolean isWatch = mPackageManagerClient.hasSystemFeature(
                        PackageManager.FEATURE_WATCH);
                if (isWatch) {
                    resetDefaultDndIfNecessary();
                }
            } catch (FileNotFoundException e) {
                // No data yet
                // Load default managed services approvals
                loadDefaultApprovedServices(USER_SYSTEM);
                allowDefaultApprovedServices(USER_SYSTEM);
            } catch (IOException e) {
                Log.wtf(TAG, "Unable to read notification policy", e);
            } catch (NumberFormatException e) {
                Log.wtf(TAG, "Unable to parse notification policy", e);
            } catch (XmlPullParserException e) {
                Log.wtf(TAG, "Unable to parse notification policy", e);
            } finally {
                IoUtils.closeQuietly(infile);
            }
        }
    }

    @VisibleForTesting
    protected void handleSavePolicyFile() {
        if (!IoThread.getHandler().hasCallbacks(mSavePolicyFile)) {
            IoThread.getHandler().postDelayed(mSavePolicyFile, 250);
        }
    }

    private final class SavePolicyFileRunnable implements Runnable {
        @Override
        public void run() {
            if (DBG) Slog.d(TAG, "handleSavePolicyFile");
            synchronized (mPolicyFile) {
                final FileOutputStream stream;
                try {
                    stream = mPolicyFile.startWrite();
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to save policy file", e);
                    return;
                }

                try {
                    writePolicyXml(stream, false /*forBackup*/, UserHandle.USER_ALL);
                    mPolicyFile.finishWrite(stream);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to save policy file, restoring backup", e);
                    mPolicyFile.failWrite(stream);
                }
            }
            BackupManager.dataChanged(getContext().getPackageName());
        }
    }

    private void writePolicyXml(OutputStream stream, boolean forBackup, int userId)
            throws IOException {
        final TypedXmlSerializer out;
        if (forBackup) {
            out = Xml.newFastSerializer();
            out.setOutput(stream, StandardCharsets.UTF_8.name());
        } else {
            out = Xml.resolveSerializer(stream);
        }
        out.startDocument(null, true);
        out.startTag(null, TAG_NOTIFICATION_POLICY);
        out.attributeInt(null, ATTR_VERSION, DB_VERSION);
        mZenModeHelper.writeXml(out, forBackup, null, userId);
        mPreferencesHelper.writeXml(out, forBackup, userId);
        mListeners.writeXml(out, forBackup, userId);
        mAssistants.writeXml(out, forBackup, userId);
        mSnoozeHelper.writeXml(out);
        mConditionProviders.writeXml(out, forBackup, userId);
        if (!forBackup || userId == UserHandle.USER_SYSTEM) {
            writeSecureNotificationsPolicy(out);
        }
        out.endTag(null, TAG_NOTIFICATION_POLICY);
        out.endDocument();
    }

    @VisibleForTesting
    final NotificationDelegate mNotificationDelegate = new NotificationDelegate() {

        @Override
        public void prepareForPossibleShutdown() {
            mHistoryManager.triggerWriteToDisk();
        }

        @Override
        public void onSetDisabled(int status) {
            synchronized (mNotificationLock) {
                if (Flags.refactorAttentionHelper()) {
                    mAttentionHelper.updateDisableNotificationEffectsLocked(status);
                } else {
                    mDisableNotificationEffects =
                            (status & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0;
                    if (disableNotificationEffects(null) != null) {
                        // cancel whatever's going on
                        clearSoundLocked();
                        clearVibrateLocked();
                    }
                }
            }
        }

        @Override
        public void onClearAll(int callingUid, int callingPid, int userId) {
            synchronized (mNotificationLock) {
                cancelAllLocked(callingUid, callingPid, userId, REASON_CANCEL_ALL, null,
                        /*includeCurrentProfiles*/ true);
            }
        }

        @Override
        public void onNotificationClick(int callingUid, int callingPid, String key,
                NotificationVisibility nv) {
            exitIdle();
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r == null) {
                    Slog.w(TAG, "No notification with key: " + key);
                    return;
                }
                final long now = System.currentTimeMillis();
                MetricsLogger.action(r.getItemLogMaker()
                        .setType(MetricsEvent.TYPE_ACTION)
                        .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX, nv.rank)
                        .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_COUNT, nv.count));
                mNotificationRecordLogger.log(
                        NotificationRecordLogger.NotificationEvent.NOTIFICATION_CLICKED, r);
                EventLogTags.writeNotificationClicked(key,
                        r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now),
                        nv.rank, nv.count);

                StatusBarNotification sbn = r.getSbn();
                cancelNotification(callingUid, callingPid, sbn.getPackageName(), sbn.getTag(),
                        sbn.getId(), Notification.FLAG_AUTO_CANCEL,
                        FLAG_FOREGROUND_SERVICE | FLAG_USER_INITIATED_JOB | FLAG_BUBBLE,
                        false, r.getUserId(), REASON_CLICK, nv.rank, nv.count, null);
                nv.recycle();
                reportUserInteraction(r);
                mAssistants.notifyAssistantNotificationClicked(r);
            }
        }

        @Override
        public void onNotificationActionClick(int callingUid, int callingPid, String key,
                int actionIndex, Notification.Action action, NotificationVisibility nv,
                boolean generatedByAssistant) {
            exitIdle();
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r == null) {
                    Slog.w(TAG, "No notification with key: " + key);
                    return;
                }
                final long now = System.currentTimeMillis();
                MetricsLogger.action(r.getLogMaker(now)
                        .setCategory(MetricsEvent.NOTIFICATION_ITEM_ACTION)
                        .setType(MetricsEvent.TYPE_ACTION)
                        .setSubtype(actionIndex)
                        .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX, nv.rank)
                        .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_COUNT, nv.count)
                        .addTaggedData(MetricsEvent.NOTIFICATION_ACTION_IS_SMART,
                                action.isContextual() ? 1 : 0)
                        .addTaggedData(
                                MetricsEvent.NOTIFICATION_SMART_SUGGESTION_ASSISTANT_GENERATED,
                                generatedByAssistant ? 1 : 0)
                        .addTaggedData(MetricsEvent.NOTIFICATION_LOCATION,
                                nv.location.toMetricsEventEnum()));
                mNotificationRecordLogger.log(
                        NotificationRecordLogger.NotificationEvent.fromAction(actionIndex,
                                generatedByAssistant, action.isContextual()), r);
                EventLogTags.writeNotificationActionClicked(key,
                        action.actionIntent.getTarget().toString(),
                        action.actionIntent.getIntent().toString(), actionIndex,
                        r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now),
                        nv.rank, nv.count);
                nv.recycle();
                reportUserInteraction(r);
                mAssistants.notifyAssistantActionClicked(r, action, generatedByAssistant);
            }
        }

        @Override
        public void onNotificationClear(int callingUid, int callingPid,
                String pkg, int userId, String key,
                @NotificationStats.DismissalSurface int dismissalSurface,
                @NotificationStats.DismissalSentiment int dismissalSentiment,
                NotificationVisibility nv) {
            String tag = null;
            int id = 0;
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    r.recordDismissalSurface(dismissalSurface);
                    r.recordDismissalSentiment(dismissalSentiment);
                    tag = r.getSbn().getTag();
                    id = r.getSbn().getId();
                }
            }

            int mustNotHaveFlags = FLAG_NO_DISMISS;
            cancelNotification(callingUid, callingPid, pkg, tag, id,
                    /* mustHaveFlags= */ 0,
                    /* mustNotHaveFlags= */ mustNotHaveFlags,
                    /* sendDelete= */ true,
                    userId, REASON_CANCEL, nv.rank, nv.count, /* listener= */ null);
            nv.recycle();
        }

        @Override
        public void onPanelRevealed(boolean clearEffects, int items) {
            MetricsLogger.visible(getContext(), MetricsEvent.NOTIFICATION_PANEL);
            MetricsLogger.histogram(getContext(), "note_load", items);
            mNotificationRecordLogger.log(
                    NotificationRecordLogger.NotificationPanelEvent.NOTIFICATION_PANEL_OPEN);
            EventLogTags.writeNotificationPanelRevealed(items);
            if (clearEffects) {
                clearEffects();
            }
            mAssistants.onPanelRevealed(items);
        }

        @Override
        public void onPanelHidden() {
            MetricsLogger.hidden(getContext(), MetricsEvent.NOTIFICATION_PANEL);
            mNotificationRecordLogger.log(
                    NotificationRecordLogger.NotificationPanelEvent.NOTIFICATION_PANEL_CLOSE);
            EventLogTags.writeNotificationPanelHidden();
            mAssistants.onPanelHidden();
        }

        @Override
        public void clearEffects() {
            synchronized (mNotificationLock) {
                if (DBG) Slog.d(TAG, "clearEffects");
                if (Flags.refactorAttentionHelper()) {
                    mAttentionHelper.clearAttentionEffects();
                } else {
                    clearSoundLocked();
                    clearVibrateLocked();
                    clearLightsLocked();
                }
            }
        }

        @Override
        public void onNotificationError(int callingUid, int callingPid, String pkg, String tag,
                int id, int uid, int initialPid, String message, int userId) {
            final boolean fgService;
            final boolean uiJob;
            synchronized (mNotificationLock) {
                NotificationRecord r = findNotificationLocked(pkg, tag, id, userId);
                fgService = r != null && (r.getNotification().flags & FLAG_FOREGROUND_SERVICE) != 0;
                uiJob = r != null && (r.getNotification().flags & FLAG_USER_INITIATED_JOB) != 0;
            }
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0, 0, false, userId,
                    REASON_ERROR, null);
            if (fgService || uiJob) {
                // Still crash for foreground services or user-initiated jobs, preventing the
                // not-crash behaviour abused by apps to give us a garbage notification and
                // silently start a fg service or user-initiated job.
                final int exceptionTypeId = fgService
                        ? BadForegroundServiceNotificationException.TYPE_ID
                        : BadUserInitiatedJobNotificationException.TYPE_ID;
                Binder.withCleanCallingIdentity(
                        () -> mAm.crashApplicationWithType(uid, initialPid, pkg, -1,
                            "Bad notification(tag=" + tag + ", id=" + id + ") posted from package "
                                + pkg + ", crashing app(uid=" + uid + ", pid=" + initialPid + "): "
                                + message, true /* force */, exceptionTypeId));
            }
        }

        @Override
        public void onNotificationVisibilityChanged(NotificationVisibility[] newlyVisibleKeys,
                NotificationVisibility[] noLongerVisibleKeys) {
            synchronized (mNotificationLock) {
                for (NotificationVisibility nv : newlyVisibleKeys) {
                    NotificationRecord r = mNotificationsByKey.get(nv.key);
                    if (r == null) continue;
                    if (!r.isSeen()) {
                        // Report to usage stats that notification was made visible
                        if (DBG) Slog.d(TAG, "Marking notification as visible " + nv.key);
                        reportSeen(r);
                    }
                    r.setVisibility(true, nv.rank, nv.count, mNotificationRecordLogger);
                    mAssistants.notifyAssistantVisibilityChangedLocked(r, true);
                    boolean isHun = (nv.location
                            == NotificationVisibility.NotificationLocation.LOCATION_FIRST_HEADS_UP);
                    // hasBeenVisiblyExpanded must be called after updating the expansion state of
                    // the NotificationRecord to ensure the expansion state is up-to-date.
                    if (isHun || r.hasBeenVisiblyExpanded()) {
                        logSmartSuggestionsVisible(r, nv.location.toMetricsEventEnum());
                    }
                    maybeRecordInterruptionLocked(r);
                    nv.recycle();
                }
                // Note that we might receive this event after notifications
                // have already left the system, e.g. after dismissing from the
                // shade. Hence not finding notifications in
                // mNotificationsByKey is not an exceptional condition.
                for (NotificationVisibility nv : noLongerVisibleKeys) {
                    NotificationRecord r = mNotificationsByKey.get(nv.key);
                    if (r == null) continue;
                    r.setVisibility(false, nv.rank, nv.count, mNotificationRecordLogger);
                    mAssistants.notifyAssistantVisibilityChangedLocked(r, false);
                    nv.recycle();
                }
            }
        }

        @Override
        public void onNotificationExpansionChanged(String key,
                boolean userAction, boolean expanded, int notificationLocation) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    r.stats.onExpansionChanged(userAction, expanded);
                    // hasBeenVisiblyExpanded must be called after updating the expansion state of
                    // the NotificationRecord to ensure the expansion state is up-to-date.
                    if (r.hasBeenVisiblyExpanded()) {
                        logSmartSuggestionsVisible(r, notificationLocation);
                    }
                    if (userAction) {
                        MetricsLogger.action(r.getItemLogMaker()
                                .setType(expanded ? MetricsEvent.TYPE_DETAIL
                                        : MetricsEvent.TYPE_COLLAPSE));
                        mNotificationRecordLogger.log(
                                NotificationRecordLogger.NotificationEvent.fromExpanded(expanded,
                                        userAction),
                                r);
                    }
                    if (expanded && userAction) {
                        r.recordExpanded();
                        reportUserInteraction(r);
                    }
                    mAssistants.notifyAssistantExpansionChangedLocked(
                            r.getSbn(), r.getNotificationType(), userAction, expanded);
                }
            }
        }

        @Override
        public void onNotificationDirectReplied(String key) {
            exitIdle();
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    r.recordDirectReplied();
                    mMetricsLogger.write(r.getLogMaker()
                            .setCategory(MetricsEvent.NOTIFICATION_DIRECT_REPLY_ACTION)
                            .setType(MetricsEvent.TYPE_ACTION));
                    mNotificationRecordLogger.log(
                            NotificationRecordLogger.NotificationEvent.NOTIFICATION_DIRECT_REPLIED,
                            r);
                    reportUserInteraction(r);
                    mAssistants.notifyAssistantNotificationDirectReplyLocked(r);
                }
            }
        }

        @Override
        public void onNotificationSmartSuggestionsAdded(String key, int smartReplyCount,
                int smartActionCount, boolean generatedByAssistant, boolean editBeforeSending) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    r.setNumSmartRepliesAdded(smartReplyCount);
                    r.setNumSmartActionsAdded(smartActionCount);
                    r.setSuggestionsGeneratedByAssistant(generatedByAssistant);
                    r.setEditChoicesBeforeSending(editBeforeSending);
                }
            }
        }

        @Override
        public void onNotificationSmartReplySent(String key, int replyIndex, CharSequence reply,
                int notificationLocation, boolean modifiedBeforeSending) {

            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    LogMaker logMaker = r.getLogMaker()
                            .setCategory(MetricsEvent.SMART_REPLY_ACTION)
                            .setSubtype(replyIndex)
                            .addTaggedData(
                                    MetricsEvent.NOTIFICATION_SMART_SUGGESTION_ASSISTANT_GENERATED,
                                    r.getSuggestionsGeneratedByAssistant() ? 1 : 0)
                            .addTaggedData(MetricsEvent.NOTIFICATION_LOCATION,
                                    notificationLocation)
                            .addTaggedData(
                                    MetricsEvent.NOTIFICATION_SMART_REPLY_EDIT_BEFORE_SENDING,
                                    r.getEditChoicesBeforeSending() ? 1 : 0)
                            .addTaggedData(
                                    MetricsEvent.NOTIFICATION_SMART_REPLY_MODIFIED_BEFORE_SENDING,
                                    modifiedBeforeSending ? 1 : 0);
                    mMetricsLogger.write(logMaker);
                    mNotificationRecordLogger.log(
                            NotificationRecordLogger.NotificationEvent.NOTIFICATION_SMART_REPLIED,
                            r);
                    // Treat clicking on a smart reply as a user interaction.
                    reportUserInteraction(r);
                    mAssistants.notifyAssistantSuggestedReplySent(
                            r.getSbn(), r.getNotificationType(), reply,
                            r.getSuggestionsGeneratedByAssistant());
                }
            }
        }

        @Override
        public void onNotificationSettingsViewed(String key) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    r.recordViewedSettings();
                }
            }
        }

        @Override
        public void onNotificationBubbleChanged(String key, boolean isBubble, int bubbleFlags) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    if (!isBubble) {
                        // This happens if the user has dismissed the bubble but the notification
                        // is still active in the shade, enqueuing would create a bubble since
                        // the notification is technically allowed. Flip the flag so that
                        // apps querying noMan will know that their notification is not showing
                        // as a bubble.
                        r.getNotification().flags &= ~FLAG_BUBBLE;
                        r.setFlagBubbleRemoved(true);
                    } else {
                        // Enqueue will trigger resort & if the flag is allowed to be true it'll
                        // be applied there.
                        r.getNotification().flags |= FLAG_ONLY_ALERT_ONCE;
                        r.setFlagBubbleRemoved(false);
                        if (r.getNotification().getBubbleMetadata() != null) {
                            r.getNotification().getBubbleMetadata().setFlags(bubbleFlags);
                        }
                        // Force isAppForeground true here, because for sysui's purposes we
                        // want to adjust the flag behaviour.
                        mHandler.post(new EnqueueNotificationRunnable(r.getUser().getIdentifier(),
                                r, true /* isAppForeground*/,
                                mPostNotificationTrackerFactory.newTracker(null)));
                    }
                }
            }
        }

        @Override
        public void onBubbleMetadataFlagChanged(String key, int flags) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    Notification.BubbleMetadata data = r.getNotification().getBubbleMetadata();
                    if (data == null) {
                        // No data, do nothing
                        return;
                    }

                    if (flags != data.getFlags()) {
                        int changedFlags = data.getFlags() ^ flags;
                        if ((changedFlags & FLAG_SUPPRESS_NOTIFICATION) != 0) {
                            // Suppress notification flag changed, clear any effects
                            if (Flags.refactorAttentionHelper()) {
                                mAttentionHelper.clearEffectsLocked(key);
                            } else {
                                clearEffectsLocked(key);
                            }
                        }
                        data.setFlags(flags);
                        // Shouldn't alert again just because of a flag change.
                        r.getNotification().flags |= FLAG_ONLY_ALERT_ONCE;
                        // Force isAppForeground true here, because for sysui's purposes we
                        // want to be able to adjust the flag behaviour.
                        mHandler.post(
                                new EnqueueNotificationRunnable(r.getUser().getIdentifier(), r,
                                        /* foreground= */ true,
                                        mPostNotificationTrackerFactory.newTracker(null)));
                    }
                }
            }
        }

        /**
         * Grant permission to read the specified URI to the package specified in the
         * NotificationRecord associated with the given key. The callingUid represents the UID of
         * SystemUI from which this method is being called.
         *
         * For this to work, SystemUI must have permission to read the URI when running under the
         * user associated with the NotificationRecord, and this grant will fail when trying
         * to grant URI permissions across users.
         */
        @Override
        public void grantInlineReplyUriPermission(String key, Uri uri, UserHandle user,
                String packageName, int callingUid) {
            synchronized (mNotificationLock) {
                InlineReplyUriRecord r = mInlineReplyRecordsByKey.get(key);
                if (r == null) {
                    InlineReplyUriRecord newRecord = new InlineReplyUriRecord(
                            mUgmInternal.newUriPermissionOwner("INLINE_REPLY:" + key),
                            user,
                            packageName,
                            key);
                    r = newRecord;
                    mInlineReplyRecordsByKey.put(key, r);
                }
                IBinder owner = r.getPermissionOwner();
                int uid = callingUid;
                int userId = r.getUserId();
                if (UserHandle.getUserId(uid) != userId) {
                    try {
                        final String[] pkgs = mPackageManager.getPackagesForUid(callingUid);
                        if (pkgs == null) {
                            Log.e(TAG, "Cannot grant uri permission to unknown UID: "
                                    + callingUid);
                        }
                        final String pkg = pkgs[0]; // Get the SystemUI package
                        // Find the UID for SystemUI for the correct user
                        uid =  mPackageManager.getPackageUid(pkg, 0, userId);
                    } catch (RemoteException re) {
                        Log.e(TAG, "Cannot talk to package manager", re);
                    }
                }
                r.addUri(uri);
                grantUriPermission(owner, uri, uid, r.getPackageName(), userId);
            }
        }

        @Override
        /**
         * Clears inline URI permission grants by destroying the permission owner for the specified
         * notification.
         */
        public void clearInlineReplyUriPermissions(String key, int callingUid) {
            synchronized (mNotificationLock) {
                InlineReplyUriRecord uriRecord = mInlineReplyRecordsByKey.get(key);
                if (uriRecord != null) {
                    destroyPermissionOwner(uriRecord.getPermissionOwner(), uriRecord.getUserId(),
                            "INLINE_REPLY: " + uriRecord.getKey());
                    mInlineReplyRecordsByKey.remove(key);
                }
            }
        }

        @Override
        public void onNotificationFeedbackReceived(String key, Bundle feedback) {
            exitIdle();
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r == null) {
                    if (DBG) Slog.w(TAG, "No notification with key: " + key);
                    return;
                }
                mAssistants.notifyAssistantFeedbackReceived(r, feedback);
            }
        }

    };

    NotificationManagerPrivate mNotificationManagerPrivate = key -> {
        synchronized (mNotificationLock) {
            return mNotificationsByKey.get(key);
        }
    };

    @VisibleForTesting
    void logSmartSuggestionsVisible(NotificationRecord r, int notificationLocation) {
        // If the newly visible notification has smart suggestions
        // then log that the user has seen them.
        if ((r.getNumSmartRepliesAdded() > 0 || r.getNumSmartActionsAdded() > 0)
                && !r.hasSeenSmartReplies()) {
            r.setSeenSmartReplies(true);
            LogMaker logMaker = r.getLogMaker()
                    .setCategory(MetricsEvent.SMART_REPLY_VISIBLE)
                    .addTaggedData(MetricsEvent.NOTIFICATION_SMART_REPLY_COUNT,
                            r.getNumSmartRepliesAdded())
                    .addTaggedData(MetricsEvent.NOTIFICATION_SMART_ACTION_COUNT,
                            r.getNumSmartActionsAdded())
                    .addTaggedData(
                            MetricsEvent.NOTIFICATION_SMART_SUGGESTION_ASSISTANT_GENERATED,
                            r.getSuggestionsGeneratedByAssistant() ? 1 : 0)
                    // The fields in the NotificationVisibility.NotificationLocation enum map
                    // directly to the fields in the MetricsEvent.NotificationLocation enum.
                    .addTaggedData(MetricsEvent.NOTIFICATION_LOCATION, notificationLocation)
                    .addTaggedData(
                            MetricsEvent.NOTIFICATION_SMART_REPLY_EDIT_BEFORE_SENDING,
                            r.getEditChoicesBeforeSending() ? 1 : 0);
            mMetricsLogger.write(logMaker);
            mNotificationRecordLogger.log(
                    NotificationRecordLogger.NotificationEvent.NOTIFICATION_SMART_REPLY_VISIBLE,
                    r);
        }
    }

    @GuardedBy("mNotificationLock")
    void clearSoundLocked() {
        mSoundNotificationKey = null;
        final long identity = Binder.clearCallingIdentity();
        try {
            final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
            if (player != null) {
                player.stopAsync();
            }
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @GuardedBy("mNotificationLock")
    void clearVibrateLocked() {
        mVibrateNotificationKey = null;
        final long identity = Binder.clearCallingIdentity();
        try {
            mVibratorHelper.cancelVibration();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @GuardedBy("mNotificationLock")
    private void clearLightsLocked() {
        // light
        mLights.clear();
        updateLightsLocked();
    }

    @GuardedBy("mNotificationLock")
    private void clearEffectsLocked(String key) {
        if (key.equals(mSoundNotificationKey)) {
            clearSoundLocked();
        }
        if (key.equals(mVibrateNotificationKey)) {
            clearVibrateLocked();
        }
        boolean removed = mLights.remove(key);
        if (removed) {
            updateLightsLocked();
        }
    }

    protected final BroadcastReceiver mLocaleChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
                // update system notification channels
                SystemNotificationChannels.createAll(context);
                mZenModeHelper.updateDefaultZenRules(Binder.getCallingUid(),
                        isCallerIsSystemOrSystemUi());
                mPreferencesHelper.onLocaleChanged(context, ActivityManager.getCurrentUser());
            }
        }
    };

    private final BroadcastReceiver mRestoreReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SETTING_RESTORED.equals(intent.getAction())) {
                try {
                    String element = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                    String newValue = intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE);
                    int restoredFromSdkInt = intent.getIntExtra(
                            Intent.EXTRA_SETTING_RESTORED_FROM_SDK_INT, 0);
                    mListeners.onSettingRestored(
                            element, newValue, restoredFromSdkInt, getSendingUserId());
                    mConditionProviders.onSettingRestored(
                            element, newValue, restoredFromSdkInt, getSendingUserId());
                } catch (Exception e) {
                    Slog.wtf(TAG, "Cannot restore managed services from settings", e);
                }
            }
        }
    };

    private final BroadcastReceiver mNotificationTimeoutReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            if (ACTION_NOTIFICATION_TIMEOUT.equals(action)) {
                final NotificationRecord record;
                synchronized (mNotificationLock) {
                    record = findNotificationByKeyLocked(intent.getStringExtra(EXTRA_KEY));
                }
                if (record != null) {
                    cancelNotification(record.getSbn().getUid(), record.getSbn().getInitialPid(),
                            record.getSbn().getPackageName(), record.getSbn().getTag(),
                            record.getSbn().getId(), 0,
                            FLAG_FOREGROUND_SERVICE | FLAG_USER_INITIATED_JOB,
                            true, record.getUserId(), REASON_TIMEOUT, null);
                }
            }
        }
    };

    private final BroadcastReceiver mPackageIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }

            boolean queryRemove = false;
            boolean packageChanged = false;
            boolean cancelNotifications = true;
            boolean hideNotifications = false;
            boolean unhideNotifications = false;
            int reason = REASON_PACKAGE_CHANGED;

            if (action.equals(Intent.ACTION_PACKAGE_ADDED)
                    || (queryRemove=action.equals(Intent.ACTION_PACKAGE_REMOVED))
                    || action.equals(Intent.ACTION_PACKAGE_RESTARTED)
                    || (packageChanged=action.equals(Intent.ACTION_PACKAGE_CHANGED))
                    || action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
                    || action.equals(Intent.ACTION_PACKAGES_SUSPENDED)
                    || action.equals(Intent.ACTION_PACKAGES_UNSUSPENDED)
                    || action.equals(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED)) {
                int changeUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_ALL);
                String pkgList[] = null;
                int uidList[] = null;
                boolean removingPackage = queryRemove &&
                        !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                if (DBG) Slog.i(TAG, "action=" + action + " removing=" + removingPackage);
                if (action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                    uidList = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                } else if (action.equals(Intent.ACTION_PACKAGES_SUSPENDED)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                    uidList = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                    cancelNotifications = false;
                    hideNotifications = true;
                } else if (action.equals(Intent.ACTION_PACKAGES_UNSUSPENDED)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                    uidList = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                    cancelNotifications = false;
                    unhideNotifications = true;
                } else if (action.equals(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED)) {
                    final int distractionRestrictions =
                            intent.getIntExtra(Intent.EXTRA_DISTRACTION_RESTRICTIONS,
                                    PackageManager.RESTRICTION_NONE);
                    if ((distractionRestrictions
                            & PackageManager.RESTRICTION_HIDE_NOTIFICATIONS) != 0) {
                        pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                        uidList = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                        cancelNotifications = false;
                        hideNotifications = true;
                    } else {
                        pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                        uidList = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                        cancelNotifications = false;
                        unhideNotifications = true;
                    }
                } else {
                    Uri uri = intent.getData();
                    if (uri == null) {
                        return;
                    }
                    String pkgName = uri.getSchemeSpecificPart();
                    if (pkgName == null) {
                        return;
                    }
                    if (packageChanged) {
                        // We cancel notifications for packages which have just been disabled
                        try {
                            final int enabled = mPackageManager.getApplicationEnabledSetting(
                                    pkgName,
                                    changeUserId != UserHandle.USER_ALL ? changeUserId :
                                            USER_SYSTEM);
                            if (enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                    || enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                                cancelNotifications = false;
                            }
                        } catch (IllegalArgumentException e) {
                            // Package doesn't exist; probably racing with uninstall.
                            // cancelNotifications is already true, so nothing to do here.
                            if (DBG) {
                                Slog.i(TAG, "Exception trying to look up app enabled setting", e);
                            }
                        } catch (RemoteException e) {
                            // Failed to talk to PackageManagerService Should never happen!
                        }
                    }
                    pkgList = new String[]{pkgName};
                    uidList = new int[] {intent.getIntExtra(Intent.EXTRA_UID, -1)};
                }
                if (pkgList != null && (pkgList.length > 0)) {
                    if (cancelNotifications) {
                        for (String pkgName : pkgList) {
                            cancelAllNotificationsInt(MY_UID, MY_PID, pkgName, null, 0, 0,
                                    changeUserId, reason);
                        }
                    } else if (hideNotifications && uidList != null && (uidList.length > 0)) {
                        hideNotificationsForPackages(pkgList, uidList);
                    } else if (unhideNotifications && uidList != null && (uidList.length > 0)) {
                        unhideNotificationsForPackages(pkgList, uidList);
                    }
                }

                mHandler.scheduleOnPackageChanged(removingPackage, changeUserId, pkgList, uidList);
            }
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (!Flags.refactorAttentionHelper()) {
                if (action.equals(Intent.ACTION_SCREEN_ON)) {
                    // Keep track of screen on/off state, but do not turn off the notification light
                    // until user passes through the lock screen or views the notification.
                    mScreenOn = true;
                    updateNotificationPulse();
                } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                    mScreenOn = false;
                    updateNotificationPulse();
                } else if (action.equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                    mInCallStateOffHook = TelephonyManager.EXTRA_STATE_OFFHOOK
                        .equals(intent.getStringExtra(TelephonyManager.EXTRA_STATE));
                    updateNotificationPulse();
                } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                    // turn off LED when user passes through lock screen
                    if (mNotificationLight != null) {
                        mNotificationLight.turnOff();
                    }
                }
            }

            if (action.equals(Intent.ACTION_USER_STOPPED)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userHandle >= 0) {
                    cancelAllNotificationsInt(MY_UID, MY_PID, null, null, 0, 0, userHandle,
                            REASON_USER_STOPPED);
                }
            } else if (
                    isProfileUnavailable(action)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userHandle >= 0) {
                    cancelAllNotificationsInt(MY_UID, MY_PID, null, null, 0, 0, userHandle,
                            REASON_PROFILE_TURNED_OFF);
                    mSnoozeHelper.clearData(userHandle);
                }
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                mUserProfiles.updateCache(context);
                if (!mUserProfiles.isProfileUser(userId)) {
                    // reload per-user settings
                    mSettingsObserver.update(null);
                    // Refresh managed services
                    mConditionProviders.onUserSwitched(userId);
                    mListeners.onUserSwitched(userId);
                    mZenModeHelper.onUserSwitched(userId);
                    mPreferencesHelper.syncChannelsBypassingDnd();
                }
                // assistant is the only thing that cares about managed profiles specifically
                mAssistants.onUserSwitched(userId);
            } else if (action.equals(Intent.ACTION_USER_ADDED)) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                if (userId != USER_NULL) {
                    mUserProfiles.updateCache(context);
                    if (!mUserProfiles.isProfileUser(userId)) {
                        allowDefaultApprovedServices(userId);
                    }
                }
            } else if (action.equals(Intent.ACTION_USER_REMOVED)) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                mUserProfiles.updateCache(context);
                mZenModeHelper.onUserRemoved(userId);
                mPreferencesHelper.onUserRemoved(userId);
                mListeners.onUserRemoved(userId);
                mConditionProviders.onUserRemoved(userId);
                mAssistants.onUserRemoved(userId);
                mHistoryManager.onUserRemoved(userId);
                mPreferencesHelper.syncChannelsBypassingDnd();
                handleSavePolicyFile();
            } else if (action.equals(Intent.ACTION_USER_UNLOCKED)) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                mUserProfiles.updateCache(context);
                mAssistants.onUserUnlocked(userId);
                if (!mUserProfiles.isProfileUser(userId)) {
                    mConditionProviders.onUserUnlocked(userId);
                    mListeners.onUserUnlocked(userId);
                    mZenModeHelper.onUserUnlocked(userId);
                }
            }
        }

        private boolean isProfileUnavailable(String action) {
            return allowPrivateProfile() ?
                    action.equals(Intent.ACTION_PROFILE_UNAVAILABLE) :
                    action.equals(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        }
    };

    private final class SettingsObserver extends ContentObserver {
        private final Uri NOTIFICATION_BADGING_URI
                = Settings.Secure.getUriFor(Settings.Secure.NOTIFICATION_BADGING);
        private final Uri NOTIFICATION_BUBBLES_URI
                = Settings.Secure.getUriFor(Settings.Secure.NOTIFICATION_BUBBLES);
        private final Uri NOTIFICATION_LIGHT_PULSE_URI
                = Settings.System.getUriFor(Settings.System.NOTIFICATION_LIGHT_PULSE);
        private final Uri NOTIFICATION_RATE_LIMIT_URI
                = Settings.Global.getUriFor(Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE);
        private final Uri NOTIFICATION_HISTORY_ENABLED
                = Settings.Secure.getUriFor(Settings.Secure.NOTIFICATION_HISTORY_ENABLED);
        private final Uri NOTIFICATION_SHOW_MEDIA_ON_QUICK_SETTINGS_URI
                = Settings.Global.getUriFor(Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS);
        private final Uri LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS
                = Settings.Secure.getUriFor(
                        Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
        private final Uri LOCK_SCREEN_SHOW_NOTIFICATIONS
                = Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS);
        private final Uri SHOW_NOTIFICATION_SNOOZE
                = Settings.Secure.getUriFor(Settings.Secure.SHOW_NOTIFICATION_SNOOZE);

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(NOTIFICATION_BADGING_URI,
                    false, this, UserHandle.USER_ALL);
            if (!Flags.refactorAttentionHelper()) {
                resolver.registerContentObserver(NOTIFICATION_LIGHT_PULSE_URI,
                    false, this, UserHandle.USER_ALL);
            }
            resolver.registerContentObserver(NOTIFICATION_RATE_LIMIT_URI,
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(NOTIFICATION_BUBBLES_URI,
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(NOTIFICATION_HISTORY_ENABLED,
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(NOTIFICATION_SHOW_MEDIA_ON_QUICK_SETTINGS_URI,
                    false, this, UserHandle.USER_ALL);

            resolver.registerContentObserver(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LOCK_SCREEN_SHOW_NOTIFICATIONS,
                    false, this, UserHandle.USER_ALL);

            resolver.registerContentObserver(SHOW_NOTIFICATION_SNOOZE,
                    false, this, UserHandle.USER_ALL);

            update(null);
        }

        @Override public void onChange(boolean selfChange, Uri uri, int userId) {
            update(uri);
        }

        public void update(Uri uri) {
            ContentResolver resolver = getContext().getContentResolver();
            if (!Flags.refactorAttentionHelper()) {
                if (uri == null || NOTIFICATION_LIGHT_PULSE_URI.equals(uri)) {
                    boolean pulseEnabled = Settings.System.getIntForUser(resolver,
                        Settings.System.NOTIFICATION_LIGHT_PULSE, 0, UserHandle.USER_CURRENT)
                        != 0;
                    if (mNotificationPulseEnabled != pulseEnabled) {
                        mNotificationPulseEnabled = pulseEnabled;
                        updateNotificationPulse();
                    }
                }
            }
            if (uri == null || NOTIFICATION_RATE_LIMIT_URI.equals(uri)) {
                mMaxPackageEnqueueRate = Settings.Global.getFloat(resolver,
                            Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE, mMaxPackageEnqueueRate);
            }
            if (uri == null || NOTIFICATION_BADGING_URI.equals(uri)) {
                mPreferencesHelper.updateBadgingEnabled();
            }
            if (uri == null || NOTIFICATION_BUBBLES_URI.equals(uri)) {
                mPreferencesHelper.updateBubblesEnabled();
            }
            if (uri == null || NOTIFICATION_HISTORY_ENABLED.equals(uri)) {
                final IntArray userIds = mUserProfiles.getCurrentProfileIds();

                for (int i = 0; i < userIds.size(); i++) {
                    mArchive.updateHistoryEnabled(userIds.get(i),
                            Settings.Secure.getIntForUser(resolver,
                                    Settings.Secure.NOTIFICATION_HISTORY_ENABLED, 0,
                                    userIds.get(i)) == 1);
                }
            }
            if (uri == null || NOTIFICATION_SHOW_MEDIA_ON_QUICK_SETTINGS_URI.equals(uri)) {
                mPreferencesHelper.updateMediaNotificationFilteringEnabled();
            }
            if (uri == null || LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS.equals(uri)) {
                mPreferencesHelper.updateLockScreenPrivateNotifications();
            }
            if (uri == null || LOCK_SCREEN_SHOW_NOTIFICATIONS.equals(uri)) {
                mPreferencesHelper.updateLockScreenShowNotifications();
            }
            if (SHOW_NOTIFICATION_SNOOZE.equals(uri)) {
                final boolean snoozeEnabled = Settings.Secure.getIntForUser(resolver,
                        Secure.SHOW_NOTIFICATION_SNOOZE, 0, UserHandle.USER_CURRENT)
                        != 0;
                if (!snoozeEnabled) {
                    unsnoozeAll();
                }
            }
        }
    }

    private SettingsObserver mSettingsObserver;
    protected ZenModeHelper mZenModeHelper;

    protected class StrongAuthTracker extends LockPatternUtils.StrongAuthTracker {

        SparseBooleanArray mUserInLockDownMode = new SparseBooleanArray();

        StrongAuthTracker(Context context) {
            super(context);
        }

        private boolean containsFlag(int haystack, int needle) {
            return (haystack & needle) != 0;
        }

        // Return whether the user is in lockdown mode.
        // If the flag is not set, we assume the user is not in lockdown.
        public boolean isInLockDownMode(int userId) {
            return mUserInLockDownMode.get(userId, false);
        }

        @Override
        public synchronized void onStrongAuthRequiredChanged(int userId) {
            boolean userInLockDownModeNext = containsFlag(getStrongAuthForUser(userId),
                    STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);

            // Nothing happens if the lockdown mode of userId keeps the same.
            if (userInLockDownModeNext == isInLockDownMode(userId)) {
                return;
            }

            // When the lockdown mode is changed, we perform the following steps.
            // If the userInLockDownModeNext is true, all the function calls to
            // notifyPostedLocked and notifyRemovedLocked will not be executed.
            // The cancelNotificationsWhenEnterLockDownMode calls notifyRemovedLocked
            // and postNotificationsWhenExitLockDownMode calls notifyPostedLocked.
            // So we shall call cancelNotificationsWhenEnterLockDownMode before
            // we set mUserInLockDownMode as true.
            // On the other hand, if the userInLockDownModeNext is false, we shall call
            // postNotificationsWhenExitLockDownMode after we put false into mUserInLockDownMode
            if (userInLockDownModeNext) {
                cancelNotificationsWhenEnterLockDownMode(userId);
            }

            mUserInLockDownMode.put(userId, userInLockDownModeNext);

            if (!userInLockDownModeNext) {
                postNotificationsWhenExitLockDownMode(userId);
            }
        }
    }

    private StrongAuthTracker mStrongAuthTracker;

    public NotificationManagerService(Context context) {
        this(context,
                new NotificationRecordLoggerImpl(),
                new InstanceIdSequence(NOTIFICATION_INSTANCE_ID_MAX));
    }

    @VisibleForTesting
    public NotificationManagerService(Context context,
            NotificationRecordLogger notificationRecordLogger,
            InstanceIdSequence notificationInstanceIdSequence) {
        super(context);
        mNotificationRecordLogger = notificationRecordLogger;
        mNotificationInstanceIdSequence = notificationInstanceIdSequence;
        Notification.processAllowlistToken = ALLOWLIST_TOKEN;
    }

    // TODO - replace these methods with new fields in the VisibleForTesting constructor
    @VisibleForTesting
    void setAudioManager(AudioManager audioManager) {
        mAudioManager = audioManager;
    }

    @VisibleForTesting
    void setStrongAuthTracker(StrongAuthTracker strongAuthTracker) {
        mStrongAuthTracker = strongAuthTracker;
    }

    @VisibleForTesting
    void setKeyguardManager(KeyguardManager keyguardManager) {
        mKeyguardManager = keyguardManager;
    }

    @VisibleForTesting
    ShortcutHelper getShortcutHelper() {
        return mShortcutHelper;
    }

    @VisibleForTesting
    void setShortcutHelper(ShortcutHelper helper) {
        mShortcutHelper = helper;
    }

    @VisibleForTesting
    VibratorHelper getVibratorHelper() {
        return mVibratorHelper;
    }

    @VisibleForTesting
    void setVibratorHelper(VibratorHelper helper) {
        mVibratorHelper = helper;
    }

    @VisibleForTesting
    void setHints(int hints) {
        mListenerHints = hints;
    }

    @VisibleForTesting
    void setLights(LogicalLight light) {
        mNotificationLight = light;
        mAttentionLight = light;
        mNotificationPulseEnabled = true;
    }

    @VisibleForTesting
    void setScreenOn(boolean on) {
        mScreenOn = on;
    }

    @VisibleForTesting
    int getNotificationRecordCount() {
        synchronized (mNotificationLock) {
            int count = mNotificationList.size() + mNotificationsByKey.size()
                    + mSummaryByGroupKey.size() + mEnqueuedNotifications.size();
            // subtract duplicates
            for (NotificationRecord posted : mNotificationList) {
                if (mNotificationsByKey.containsKey(posted.getKey())) {
                    count--;
                }
                if (posted.getSbn().isGroup() && posted.getNotification().isGroupSummary()) {
                    count--;
                }
            }

            return count;
        }
    }

    @VisibleForTesting
    void clearNotifications() {
        synchronized (mNotificationList) {
            mEnqueuedNotifications.clear();
            mNotificationList.clear();
            mNotificationsByKey.clear();
            mSummaryByGroupKey.clear();
        }
    }

    @VisibleForTesting
    void addNotification(NotificationRecord r) {
        mNotificationList.add(r);
        mNotificationsByKey.put(r.getSbn().getKey(), r);
        if (r.getSbn().isGroup()) {
            mSummaryByGroupKey.put(r.getGroupKey(), r);
        }
    }

    @VisibleForTesting
    void addEnqueuedNotification(NotificationRecord r) {
        mEnqueuedNotifications.add(r);
    }

    @VisibleForTesting
    NotificationRecord getNotificationRecord(String key) {
        return mNotificationsByKey.get(key);
    }


    @VisibleForTesting
    void setSystemReady(boolean systemReady) {
        mSystemReady = systemReady;
    }

    @VisibleForTesting
    void setHandler(WorkerHandler handler) {
        mHandler = handler;
    }

    @VisibleForTesting
    void setRankingHelper(RankingHelper rankingHelper) {
        mRankingHelper = rankingHelper;
    }

    @VisibleForTesting
    void setPreferencesHelper(PreferencesHelper prefHelper) { mPreferencesHelper = prefHelper; }

    @VisibleForTesting
    void setZenHelper(ZenModeHelper zenHelper) {
        mZenModeHelper = zenHelper;
    }

    @VisibleForTesting
    void setIsAutomotive(boolean isAutomotive) {
        mIsAutomotive = isAutomotive;
    }

    @VisibleForTesting
    void setNotificationEffectsEnabledForAutomotive(boolean isEnabled) {
        mNotificationEffectsEnabledForAutomotive = isEnabled;
    }

    @VisibleForTesting
    void setIsTelevision(boolean isTelevision) {
        mIsTelevision = isTelevision;
    }

    @VisibleForTesting
    void setUsageStats(NotificationUsageStats us) {
        mUsageStats = us;
    }

    @VisibleForTesting
    void setAccessibilityManager(AccessibilityManager am) {
        mAccessibilityManager = am;
    }

    @VisibleForTesting
    void setTelecomManager(TelecomManager tm) {
        mTelecomManager = tm;
    }

    // TODO: All tests should use this init instead of the one-off setters above.
    @VisibleForTesting
    void init(WorkerHandler handler, RankingHandler rankingHandler,
            IPackageManager packageManager, PackageManager packageManagerClient,
            LightsManager lightsManager, NotificationListeners notificationListeners,
            NotificationAssistants notificationAssistants, ConditionProviders conditionProviders,
            ICompanionDeviceManager companionManager, SnoozeHelper snoozeHelper,
            NotificationUsageStats usageStats, AtomicFile policyFile,
            ActivityManager activityManager, GroupHelper groupHelper, IActivityManager am,
            ActivityTaskManagerInternal atm, UsageStatsManagerInternal appUsageStats,
            DevicePolicyManagerInternal dpm, IUriGrantsManager ugm,
            UriGrantsManagerInternal ugmInternal, AppOpsManager appOps, UserManager userManager,
            NotificationHistoryManager historyManager, StatsManager statsManager,
            TelephonyManager telephonyManager, ActivityManagerInternal ami,
            MultiRateLimiter toastRateLimiter, PermissionHelper permissionHelper,
            UsageStatsManagerInternal usageStatsManagerInternal,
            TelecomManager telecomManager, NotificationChannelLogger channelLogger,
            SystemUiSystemPropertiesFlags.FlagResolver flagResolver,
            PermissionManager permissionManager, PowerManager powerManager,
            PostNotificationTrackerFactory postNotificationTrackerFactory) {
        mHandler = handler;
        Resources resources = getContext().getResources();
        mMaxPackageEnqueueRate = Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE,
                DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE);

        mAccessibilityManager =
                (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        mAm = am;
        mAtm = atm;
        mAtm.setBackgroundActivityStartCallback(new NotificationTrampolineCallback());
        mUgm = ugm;
        mUgmInternal = ugmInternal;
        mPackageManager = packageManager;
        mPackageManagerClient = packageManagerClient;
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mPermissionManager = permissionManager;
        mPermissionPolicyInternal = LocalServices.getService(PermissionPolicyInternal.class);
        mUmInternal = LocalServices.getService(UserManagerInternal.class);
        mUsageStatsManagerInternal = usageStatsManagerInternal;
        mAppOps = appOps;
        mAppUsageStats = appUsageStats;
        mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        mCompanionManager = companionManager;
        mActivityManager = activityManager;
        mAmi = ami;
        mDeviceIdleManager = getContext().getSystemService(DeviceIdleManager.class);
        mDpm = dpm;
        mUm = userManager;
        mTelecomManager = telecomManager;
        mPowerManager = powerManager;
        mPostNotificationTrackerFactory = postNotificationTrackerFactory;
        mPlatformCompat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));

        mStrongAuthTracker = new StrongAuthTracker(getContext());
        String[] extractorNames;
        try {
            extractorNames = resources.getStringArray(R.array.config_notificationSignalExtractors);
        } catch (Resources.NotFoundException e) {
            extractorNames = new String[0];
        }
        mUsageStats = usageStats;
        mMetricsLogger = new MetricsLogger();
        mRankingHandler = rankingHandler;
        mConditionProviders = conditionProviders;
        mZenModeHelper = new ZenModeHelper(getContext(), mHandler.getLooper(), mConditionProviders,
                flagResolver, new ZenModeEventLogger(mPackageManagerClient));
        mZenModeHelper.addCallback(new ZenModeHelper.Callback() {
            @Override
            public void onConfigChanged() {
                handleSavePolicyFile();
            }

            @Override
            void onZenModeChanged() {
                Binder.withCleanCallingIdentity(() -> {
                    sendRegisteredOnlyBroadcast(ACTION_INTERRUPTION_FILTER_CHANGED);
                    getContext().sendBroadcastAsUser(
                            new Intent(ACTION_INTERRUPTION_FILTER_CHANGED_INTERNAL)
                                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                            UserHandle.ALL, permission.MANAGE_NOTIFICATIONS);
                    synchronized (mNotificationLock) {
                        updateInterruptionFilterLocked();
                    }
                    mRankingHandler.requestSort();
                });
            }

            @Override
            void onPolicyChanged() {
                Binder.withCleanCallingIdentity(() -> {
                    sendRegisteredOnlyBroadcast(
                            NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED);
                    mRankingHandler.requestSort();
                });
            }

            @Override
            void onConsolidatedPolicyChanged() {
                Binder.withCleanCallingIdentity(() -> {
                    mRankingHandler.requestSort();
                });
            }

            @Override
            void onAutomaticRuleStatusChanged(int userId, String pkg, String id, int status) {
                Binder.withCleanCallingIdentity(() -> {
                    Intent intent = new Intent(ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED);
                    intent.setPackage(pkg);
                    intent.putExtra(EXTRA_AUTOMATIC_ZEN_RULE_ID, id);
                    intent.putExtra(EXTRA_AUTOMATIC_ZEN_RULE_STATUS, status);
                    getContext().sendBroadcastAsUser(intent, UserHandle.of(userId));
                });
            }
        });
        mPermissionHelper = permissionHelper;
        mNotificationChannelLogger = channelLogger;
        mUserProfiles.updateCache(getContext());
        mPreferencesHelper = new PreferencesHelper(getContext(),
                mPackageManagerClient,
                mRankingHandler,
                mZenModeHelper,
                mPermissionHelper,
                mPermissionManager,
                mNotificationChannelLogger,
                mAppOps,
                mUserProfiles,
                mShowReviewPermissionsNotification);
        mRankingHelper = new RankingHelper(getContext(),
                mRankingHandler,
                mPreferencesHelper,
                mZenModeHelper,
                mUsageStats,
                extractorNames);
        mSnoozeHelper = snoozeHelper;
        mGroupHelper = groupHelper;
        mVibratorHelper = new VibratorHelper(getContext());
        mHistoryManager = historyManager;

        // This is a ManagedServices object that keeps track of the listeners.
        mListeners = notificationListeners;

        // This is a MangedServices object that keeps track of the assistant.
        mAssistants = notificationAssistants;

        // Needs to be set before loadPolicyFile
        mAllowedManagedServicePackages = this::canUseManagedServices;

        mPolicyFile = policyFile;
        loadPolicyFile();
        mStatusBar = getLocalService(StatusBarManagerInternal.class);
        if (mStatusBar != null) {
            mStatusBar.setNotificationDelegate(mNotificationDelegate);
        }

        mNotificationLight = lightsManager.getLight(LightsManager.LIGHT_ID_NOTIFICATIONS);
        mAttentionLight = lightsManager.getLight(LightsManager.LIGHT_ID_ATTENTION);

        mInCallNotificationUri = Uri.parse("file://" +
                resources.getString(R.string.config_inCallNotificationSound));
        mInCallNotificationAudioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build();
        mInCallNotificationVolume = resources.getFloat(R.dimen.config_inCallNotificationVolume);

        mUseAttentionLight = resources.getBoolean(R.bool.config_useAttentionLight);
        mHasLight =
                resources.getBoolean(com.android.internal.R.bool.config_intrusiveNotificationLed);

        // Don't start allowing notifications until the setup wizard has run once.
        // After that, including subsequent boots, init with notifications turned on.
        // This works on the first boot because the setup wizard will toggle this
        // flag at least once and we'll go back to 0 after that.
        if (0 == Settings.Global.getInt(getContext().getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0)) {
            mDisableNotificationEffects = true;
        }
        mZenModeHelper.initZenMode();
        mInterruptionFilter = mZenModeHelper.getZenModeListenerInterruptionFilter();

        if (mPackageManagerClient.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            telephonyManager.listen(new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String incomingNumber) {
                    if (mCallState == state) return;
                    if (DBG) Slog.d(TAG, "Call state changed: " + callStateToString(state));
                    mCallState = state;
                }
            }, PhoneStateListener.LISTEN_CALL_STATE);
        }

        mSettingsObserver = new SettingsObserver(mHandler);

        mArchive = new Archive(resources.getInteger(
                R.integer.config_notificationServiceArchiveSize));

        mIsTelevision = mPackageManagerClient.hasSystemFeature(FEATURE_LEANBACK)
                || mPackageManagerClient.hasSystemFeature(FEATURE_TELEVISION);

        mIsAutomotive =
                mPackageManagerClient.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0);
        mNotificationEffectsEnabledForAutomotive =
                resources.getBoolean(R.bool.config_enableServerNotificationEffectsForAutomotive);

        mZenModeHelper.setPriorityOnlyDndExemptPackages(getContext().getResources().getStringArray(
                com.android.internal.R.array.config_priorityOnlyDndExemptPackages));

        mWarnRemoteViewsSizeBytes = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_notificationWarnRemoteViewSizeBytes);
        mStripRemoteViewsSizeBytes = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_notificationStripRemoteViewSizeBytes);

        mMsgPkgsAllowedAsConvos = Set.of(getStringArrayResource(
                com.android.internal.R.array.config_notificationMsgPkgsAllowedAsConvos));
        mDefaultSearchSelectorPkg = getContext().getString(getContext().getResources()
                .getIdentifier("config_defaultSearchSelectorPackageName", "string", "android"));

        mFlagResolver = flagResolver;

        mStatsManager = statsManager;

        mToastRateLimiter = toastRateLimiter;

        if (Flags.refactorAttentionHelper()) {
            mAttentionHelper = new NotificationAttentionHelper(getContext(), lightsManager,
                mAccessibilityManager, mPackageManagerClient, userManager, usageStats,
                mNotificationManagerPrivate, mZenModeHelper, flagResolver);
        }

        // register for various Intents.
        // If this is called within a test, make sure to unregister the intent receivers by
        // calling onDestroy()
        IntentFilter filter = new IntentFilter();
        if (!Flags.refactorAttentionHelper()) {
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            filter.addAction(Intent.ACTION_USER_PRESENT);
        }
        filter.addAction(Intent.ACTION_USER_STOPPED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        if (allowPrivateProfile()){
            filter.addAction(Intent.ACTION_PROFILE_UNAVAILABLE);
        }
        getContext().registerReceiverAsUser(mIntentReceiver, UserHandle.ALL, filter, null, null);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        pkgFilter.addDataScheme("package");
        getContext().registerReceiverAsUser(mPackageIntentReceiver, UserHandle.ALL, pkgFilter, null,
                null);

        IntentFilter suspendedPkgFilter = new IntentFilter();
        suspendedPkgFilter.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
        suspendedPkgFilter.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
        suspendedPkgFilter.addAction(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED);
        getContext().registerReceiverAsUser(mPackageIntentReceiver, UserHandle.ALL,
                suspendedPkgFilter, null, null);

        IntentFilter sdFilter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        getContext().registerReceiverAsUser(mPackageIntentReceiver, UserHandle.ALL, sdFilter, null,
                null);

        IntentFilter timeoutFilter = new IntentFilter(ACTION_NOTIFICATION_TIMEOUT);
        timeoutFilter.addDataScheme(SCHEME_TIMEOUT);
        getContext().registerReceiver(mNotificationTimeoutReceiver, timeoutFilter,
                Context.RECEIVER_EXPORTED_UNAUDITED);

        IntentFilter settingsRestoredFilter = new IntentFilter(Intent.ACTION_SETTING_RESTORED);
        getContext().registerReceiver(mRestoreReceiver, settingsRestoredFilter);

        IntentFilter localeChangedFilter = new IntentFilter(Intent.ACTION_LOCALE_CHANGED);
        getContext().registerReceiver(mLocaleChangeReceiver, localeChangedFilter);

        mReviewNotificationPermissionsReceiver = new ReviewNotificationPermissionsReceiver();
        getContext().registerReceiver(mReviewNotificationPermissionsReceiver,
                ReviewNotificationPermissionsReceiver.getFilter(),
                Context.RECEIVER_NOT_EXPORTED);

        mAppOps.startWatchingMode(AppOpsManager.OP_POST_NOTIFICATION, null,
                new AppOpsManager.OnOpChangedInternalListener() {
                    @Override
                    public void onOpChanged(@NonNull String op, @NonNull String packageName,
                            int userId) {
                        mHandler.post(
                                () -> handleNotificationPermissionChange(packageName, userId));
                    }
                });
    }

    /**
     * Cleanup broadcast receivers change listeners.
     */
    public void onDestroy() {
        getContext().unregisterReceiver(mIntentReceiver);
        getContext().unregisterReceiver(mPackageIntentReceiver);
        getContext().unregisterReceiver(mNotificationTimeoutReceiver);
        getContext().unregisterReceiver(mRestoreReceiver);
        getContext().unregisterReceiver(mLocaleChangeReceiver);

        if (mDeviceConfigChangedListener != null) {
            DeviceConfig.removeOnPropertiesChangedListener(mDeviceConfigChangedListener);
        }
    }

    protected String[] getStringArrayResource(int key) {
        return getContext().getResources().getStringArray(key);
    }

    @Override
    public void onStart() {
        SnoozeHelper snoozeHelper = new SnoozeHelper(getContext(), (userId, r, muteOnReturn) -> {
            try {
                if (DBG) {
                    Slog.d(TAG, "Reposting " + r.getKey() + " " + muteOnReturn);
                }
                enqueueNotificationInternal(r.getSbn().getPackageName(), r.getSbn().getOpPkg(),
                        r.getSbn().getUid(), r.getSbn().getInitialPid(), r.getSbn().getTag(),
                        r.getSbn().getId(),  r.getSbn().getNotification(), userId, muteOnReturn,
                        false /* byForegroundService */);
            } catch (Exception e) {
                Slog.e(TAG, "Cannot un-snooze notification", e);
            }
        }, mUserProfiles);

        final File systemDir = new File(Environment.getDataDirectory(), "system");
        mRankingThread.start();

        WorkerHandler handler = new WorkerHandler(Looper.myLooper());

        mShowReviewPermissionsNotification = getContext().getResources().getBoolean(
                R.bool.config_notificationReviewPermissions);

        init(handler, new RankingHandlerWorker(mRankingThread.getLooper()),
                AppGlobals.getPackageManager(), getContext().getPackageManager(),
                getLocalService(LightsManager.class),
                new NotificationListeners(getContext(), mNotificationLock, mUserProfiles,
                        AppGlobals.getPackageManager()),
                new NotificationAssistants(getContext(), mNotificationLock, mUserProfiles,
                        AppGlobals.getPackageManager()),
                new ConditionProviders(getContext(), mUserProfiles, AppGlobals.getPackageManager()),
                null, snoozeHelper, new NotificationUsageStats(getContext()),
                new AtomicFile(new File(
                        systemDir, "notification_policy.xml"), "notification-policy"),
                (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE),
                getGroupHelper(), ActivityManager.getService(),
                LocalServices.getService(ActivityTaskManagerInternal.class),
                LocalServices.getService(UsageStatsManagerInternal.class),
                LocalServices.getService(DevicePolicyManagerInternal.class),
                UriGrantsManager.getService(),
                LocalServices.getService(UriGrantsManagerInternal.class),
                getContext().getSystemService(AppOpsManager.class),
                getContext().getSystemService(UserManager.class),
                new NotificationHistoryManager(getContext(), handler),
                mStatsManager = (StatsManager) getContext().getSystemService(
                        Context.STATS_MANAGER),
                getContext().getSystemService(TelephonyManager.class),
                LocalServices.getService(ActivityManagerInternal.class),
                createToastRateLimiter(), new PermissionHelper(getContext(),
                        AppGlobals.getPackageManager(),
                        AppGlobals.getPermissionManager()),
                LocalServices.getService(UsageStatsManagerInternal.class),
                getContext().getSystemService(TelecomManager.class),
                new NotificationChannelLoggerImpl(), SystemUiSystemPropertiesFlags.getResolver(),
                getContext().getSystemService(PermissionManager.class),
                getContext().getSystemService(PowerManager.class),
                new PostNotificationTrackerFactory() {});

        publishBinderService(Context.NOTIFICATION_SERVICE, mService, /* allowIsolated= */ false,
                DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PRIORITY_NORMAL);
        publishLocalService(NotificationManagerInternal.class, mInternalService);
    }

    void registerDeviceConfigChange() {
        mDeviceConfigChangedListener = properties -> {
            if (!DeviceConfig.NAMESPACE_SYSTEMUI.equals(properties.getNamespace())) {
                return;
            }
            for (String name : properties.getKeyset()) {
                if (SystemUiDeviceConfigFlags.NAS_DEFAULT_SERVICE.equals(name)) {
                    mAssistants.resetDefaultAssistantsIfNecessary();
                }
            }
        };
        mSystemExemptFromDismissal = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_DEVICE_POLICY_MANAGER,
                /* name= */ "application_exemptions",
                /* defaultValue= */ true);
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                new HandlerExecutor(mHandler),
                mDeviceConfigChangedListener);
    }

    private void registerNotificationPreferencesPullers() {
        mPullAtomCallback = new StatsPullAtomCallbackImpl();
        mStatsManager.setPullAtomCallback(
                PACKAGE_NOTIFICATION_PREFERENCES,
                null, // use default PullAtomMetadata values
                ConcurrentUtils.DIRECT_EXECUTOR,
                mPullAtomCallback
        );
        mStatsManager.setPullAtomCallback(
                PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES,
                null, // use default PullAtomMetadata values
                ConcurrentUtils.DIRECT_EXECUTOR,
                mPullAtomCallback
        );
        mStatsManager.setPullAtomCallback(
                PACKAGE_NOTIFICATION_CHANNEL_GROUP_PREFERENCES,
                null, // use default PullAtomMetadata values
                ConcurrentUtils.DIRECT_EXECUTOR,
                mPullAtomCallback
        );
        mStatsManager.setPullAtomCallback(
                DND_MODE_RULE,
                null, // use default PullAtomMetadata values
                ConcurrentUtils.DIRECT_EXECUTOR,
                mPullAtomCallback
        );
    }

    private class StatsPullAtomCallbackImpl implements StatsManager.StatsPullAtomCallback {
        @Override
        public int onPullAtom(int atomTag, List<StatsEvent> data) {
            switch (atomTag) {
                case PACKAGE_NOTIFICATION_PREFERENCES:
                case PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES:
                case PACKAGE_NOTIFICATION_CHANNEL_GROUP_PREFERENCES:
                case DND_MODE_RULE:
                    return pullNotificationStates(atomTag, data);
                default:
                    throw new UnsupportedOperationException("Unknown tagId=" + atomTag);
            }
        }
    }

    private int pullNotificationStates(int atomTag, List<StatsEvent> data) {
        switch(atomTag) {
            case PACKAGE_NOTIFICATION_PREFERENCES:
                mPreferencesHelper.pullPackagePreferencesStats(data,
                        getAllUsersNotificationPermissions());
                break;
            case PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES:
                mPreferencesHelper.pullPackageChannelPreferencesStats(data);
                break;
            case PACKAGE_NOTIFICATION_CHANNEL_GROUP_PREFERENCES:
                mPreferencesHelper.pullPackageChannelGroupPreferencesStats(data);
                break;
            case DND_MODE_RULE:
                mZenModeHelper.pullRules(data);
                break;
        }
        return StatsManager.PULL_SUCCESS;
    }

    private GroupHelper getGroupHelper() {
        mAutoGroupAtCount =
                getContext().getResources().getInteger(R.integer.config_autoGroupAtCount);
        return new GroupHelper(mAutoGroupAtCount, new GroupHelper.Callback() {
            @Override
            public void addAutoGroup(String key) {
                synchronized (mNotificationLock) {
                    addAutogroupKeyLocked(key);
                }
            }

            @Override
            public void removeAutoGroup(String key) {
                synchronized (mNotificationLock) {
                    removeAutogroupKeyLocked(key);
                }
            }

            @Override
            public void addAutoGroupSummary(int userId, String pkg, String triggeringKey,
                    int flags) {
                NotificationRecord r = createAutoGroupSummary(userId, pkg, triggeringKey, flags);
                if (r != null) {
                    final boolean isAppForeground =
                            mActivityManager.getPackageImportance(pkg) == IMPORTANCE_FOREGROUND;
                    mHandler.post(new EnqueueNotificationRunnable(userId, r, isAppForeground,
                            mPostNotificationTrackerFactory.newTracker(null)));
                }
            }

            @Override
            public void removeAutoGroupSummary(int userId, String pkg) {
                synchronized (mNotificationLock) {
                    clearAutogroupSummaryLocked(userId, pkg);
                }
            }

            @Override
            public void updateAutogroupSummary(int userId, String pkg, int flags) {
                boolean isAppForeground = pkg != null
                        && mActivityManager.getPackageImportance(pkg) == IMPORTANCE_FOREGROUND;
                synchronized (mNotificationLock) {
                    updateAutobundledSummaryFlags(userId, pkg, flags, isAppForeground);
                }
            }
        });
    }

    private void sendRegisteredOnlyBroadcast(String action) {
        int[] userIds = mUmInternal.getProfileIds(mAmi.getCurrentUserId(), true);
        Intent intent = new Intent(action).addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        for (int userId : userIds) {
            getContext().sendBroadcastAsUser(intent, UserHandle.of(userId), null);
        }
        // explicitly send the broadcast to all DND packages, even if they aren't currently running
        for (int userId : userIds) {
            for (String pkg : mConditionProviders.getAllowedPackages(userId)) {
                Intent pkgIntent = new Intent(action).setPackage(pkg).setFlags(
                        Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                getContext().sendBroadcastAsUser(pkgIntent, UserHandle.of(userId));
            }
        }
    }

    @Override
    public void onBootPhase(int phase) {
        onBootPhase(phase, Looper.getMainLooper());
    }

    @VisibleForTesting
    void onBootPhase(int phase, Looper mainLooper) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            // no beeping until we're basically done booting
            mSystemReady = true;

            // Grab our optional AudioService
            mAudioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            mAudioManagerInternal = getLocalService(AudioManagerInternal.class);
            mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
            mKeyguardManager = getContext().getSystemService(KeyguardManager.class);
            mZenModeHelper.onSystemReady();
            RoleObserver roleObserver = new RoleObserver(getContext(),
                    getContext().getSystemService(RoleManager.class),
                    mPackageManager, mainLooper);
            roleObserver.init();
            mRoleObserver = roleObserver;
            LauncherApps launcherApps =
                    (LauncherApps) getContext().getSystemService(Context.LAUNCHER_APPS_SERVICE);
            UserManager userManager = (UserManager) getContext().getSystemService(
                    Context.USER_SERVICE);
            mShortcutHelper = new ShortcutHelper(launcherApps, mShortcutListener, getLocalService(
                    ShortcutServiceInternal.class), userManager);
            BubbleExtractor bubbsExtractor = mRankingHelper.findExtractor(BubbleExtractor.class);
            if (bubbsExtractor != null) {
                bubbsExtractor.setShortcutHelper(mShortcutHelper);
            }
            registerNotificationPreferencesPullers();
            new LockPatternUtils(getContext()).registerStrongAuthTracker(mStrongAuthTracker);
            if (Flags.refactorAttentionHelper()) {
                mAttentionHelper.onSystemReady();
            }
        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            // This observer will force an update when observe is called, causing us to
            // bind to listener services.
            mSettingsObserver.observe();
            mListeners.onBootPhaseAppsCanStart();
            mAssistants.onBootPhaseAppsCanStart();
            mConditionProviders.onBootPhaseAppsCanStart();
            mHistoryManager.onBootPhaseAppsCanStart();
            registerDeviceConfigChange();
            migrateDefaultNAS();
            maybeShowInitialReviewPermissionsNotification();
        } else if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
            mSnoozeHelper.scheduleRepostsForPersistedNotifications(System.currentTimeMillis());
        } else if (phase == SystemService.PHASE_DEVICE_SPECIFIC_SERVICES_READY) {
            mPreferencesHelper.updateFixedImportance(mUm.getUsers());
            mPreferencesHelper.migrateNotificationPermissions(mUm.getUsers());
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            if (mFlagResolver.isEnabled(NotificationFlags.DEBUG_SHORT_BITMAP_DURATION)) {
                new Thread(() -> {
                    while (true) {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) { }
                        mInternalService.removeBitmaps();
                    }
                }).start();
            } else if (expireBitmaps()) {
                NotificationBitmapJobService.scheduleJob(getContext());
            }
        }
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        mHandler.post(() -> {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "notifHistoryUnlockUser");
            try {
                mHistoryManager.onUserUnlocked(user.getUserIdentifier());
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
        });
    }

    private void sendAppBlockStateChangedBroadcast(String pkg, int uid, boolean blocked) {
        // From Android T, revoking the notification permission will cause the app to be killed.
        // delay this broadcast so it doesn't race with that process death
        mHandler.postDelayed(() -> {
            try {
                getContext().sendBroadcastAsUser(
                        new Intent(ACTION_APP_BLOCK_STATE_CHANGED)
                                .putExtra(NotificationManager.EXTRA_BLOCKED_STATE, blocked)
                                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                .setPackage(pkg),
                        UserHandle.of(UserHandle.getUserId(uid)), null);
            } catch (SecurityException e) {
                Slog.w(TAG, "Can't notify app about app block change", e);
            }
        }, 500);
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        mHandler.post(() -> {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "notifHistoryStopUser");
            try {
                mHistoryManager.onUserStopped(user.getUserIdentifier());
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
        });
    }

    @GuardedBy("mNotificationLock")
    private void updateListenerHintsLocked() {
        final int hints = calculateHints();
        if (hints == mListenerHints) return;
        ZenLog.traceListenerHintsChanged(mListenerHints, hints, mEffectsSuppressors.size());
        mListenerHints = hints;
        scheduleListenerHintsChanged(hints);
    }

    @GuardedBy("mNotificationLock")
    private void updateEffectsSuppressorLocked() {
        final long updatedSuppressedEffects = calculateSuppressedEffects();
        if (updatedSuppressedEffects == mZenModeHelper.getSuppressedEffects()) return;
        final List<ComponentName> suppressors = getSuppressors();
        ZenLog.traceEffectsSuppressorChanged(
                mEffectsSuppressors, suppressors, updatedSuppressedEffects);
        mEffectsSuppressors = suppressors;
        mZenModeHelper.setSuppressedEffects(updatedSuppressedEffects);
        sendRegisteredOnlyBroadcast(NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED);
    }

    private void exitIdle() {
        if (mDeviceIdleManager != null) {
            mDeviceIdleManager.endIdle("notification interaction");
        }
    }

    void updateNotificationChannelInt(String pkg, int uid, NotificationChannel channel,
            boolean fromListener) {
        if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            // cancel
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channel.getId(), 0, 0,
                    UserHandle.getUserId(uid), REASON_CHANNEL_BANNED
            );
            if (isUidSystemOrPhone(uid)) {
                IntArray profileIds = mUserProfiles.getCurrentProfileIds();
                int N = profileIds.size();
                for (int i = 0; i < N; i++) {
                    int profileId = profileIds.get(i);
                    cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channel.getId(), 0, 0,
                            profileId, REASON_CHANNEL_BANNED
                    );
                }
            }
        }
        final NotificationChannel preUpdate =
                mPreferencesHelper.getNotificationChannel(pkg, uid, channel.getId(), true);

        mPreferencesHelper.updateNotificationChannel(pkg, uid, channel, true,
                Binder.getCallingUid(), isCallerIsSystemOrSystemUi());
        if (mPreferencesHelper.onlyHasDefaultChannel(pkg, uid)) {
            mPermissionHelper.setNotificationPermission(pkg, UserHandle.getUserId(uid),
                    channel.getImportance() != IMPORTANCE_NONE, true);
        }
        maybeNotifyChannelOwner(pkg, uid, preUpdate, channel);

        if (!fromListener) {
            final NotificationChannel modifiedChannel = mPreferencesHelper.getNotificationChannel(
                    pkg, uid, channel.getId(), false);
            mListeners.notifyNotificationChannelChanged(
                    pkg, UserHandle.getUserHandleForUid(uid),
                    modifiedChannel, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);
        }

        handleSavePolicyFile();
    }

    private void maybeNotifyChannelOwner(String pkg, int uid, NotificationChannel preUpdate,
            NotificationChannel update) {
        try {
            if ((preUpdate.getImportance() == IMPORTANCE_NONE
                    && update.getImportance() != IMPORTANCE_NONE)
                    || (preUpdate.getImportance() != IMPORTANCE_NONE
                    && update.getImportance() == IMPORTANCE_NONE)) {
                getContext().sendBroadcastAsUser(
                        new Intent(ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
                                .putExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID,
                                        update.getId())
                                .putExtra(NotificationManager.EXTRA_BLOCKED_STATE,
                                        update.getImportance() == IMPORTANCE_NONE)
                                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                .setPackage(pkg),
                        UserHandle.of(UserHandle.getUserId(uid)), null);
            }
        } catch (SecurityException e) {
            Slog.w(TAG, "Can't notify app about channel change", e);
        }
    }

    void createNotificationChannelGroup(String pkg, int uid, NotificationChannelGroup group,
            boolean fromApp, boolean fromListener) {
        Objects.requireNonNull(group);
        Objects.requireNonNull(pkg);

        final NotificationChannelGroup preUpdate =
                mPreferencesHelper.getNotificationChannelGroup(group.getId(), pkg, uid);
        mPreferencesHelper.createNotificationChannelGroup(pkg, uid, group,
                fromApp, Binder.getCallingUid(), isCallerIsSystemOrSystemUi());
        if (!fromApp) {
            maybeNotifyChannelGroupOwner(pkg, uid, preUpdate, group);
        }
        if (!fromListener) {
            mListeners.notifyNotificationChannelGroupChanged(pkg,
                    UserHandle.of(UserHandle.getCallingUserId()), group,
                    NOTIFICATION_CHANNEL_OR_GROUP_ADDED);
        }
    }

    private void maybeNotifyChannelGroupOwner(String pkg, int uid,
            NotificationChannelGroup preUpdate, NotificationChannelGroup update) {
        try {
            if (preUpdate.isBlocked() != update.isBlocked()) {
                getContext().sendBroadcastAsUser(
                        new Intent(ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED)
                                .putExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_GROUP_ID,
                                        update.getId())
                                .putExtra(NotificationManager.EXTRA_BLOCKED_STATE,
                                        update.isBlocked())
                                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                .setPackage(pkg),
                        UserHandle.of(UserHandle.getUserId(uid)), null);
            }
        } catch (SecurityException e) {
            Slog.w(TAG, "Can't notify app about group change", e);
        }
    }

    private ArrayList<ComponentName> getSuppressors() {
        ArrayList<ComponentName> names = new ArrayList<ComponentName>();
        for (int i = mListenersDisablingEffects.size() - 1; i >= 0; --i) {
            ArraySet<ComponentName> serviceInfoList = mListenersDisablingEffects.valueAt(i);

            for (ComponentName info : serviceInfoList) {
                names.add(info);
            }
        }

        return names;
    }

    private boolean removeDisabledHints(ManagedServiceInfo info) {
        return removeDisabledHints(info, 0);
    }

    private boolean removeDisabledHints(ManagedServiceInfo info, int hints) {
        boolean removed = false;

        for (int i = mListenersDisablingEffects.size() - 1; i >= 0; --i) {
            final int hint = mListenersDisablingEffects.keyAt(i);
            final ArraySet<ComponentName> listeners = mListenersDisablingEffects.valueAt(i);

            if (hints == 0 || (hint & hints) == hint) {
                removed |= listeners.remove(info.component);
            }
        }

        return removed;
    }

    private void addDisabledHints(ManagedServiceInfo info, int hints) {
        if ((hints & HINT_HOST_DISABLE_EFFECTS) != 0) {
            addDisabledHint(info, HINT_HOST_DISABLE_EFFECTS);
        }

        if ((hints & HINT_HOST_DISABLE_NOTIFICATION_EFFECTS) != 0) {
            addDisabledHint(info, HINT_HOST_DISABLE_NOTIFICATION_EFFECTS);
        }

        if ((hints & HINT_HOST_DISABLE_CALL_EFFECTS) != 0) {
            addDisabledHint(info, HINT_HOST_DISABLE_CALL_EFFECTS);
        }
    }

    private void addDisabledHint(ManagedServiceInfo info, int hint) {
        if (mListenersDisablingEffects.indexOfKey(hint) < 0) {
            mListenersDisablingEffects.put(hint, new ArraySet<>());
        }

        ArraySet<ComponentName> hintListeners = mListenersDisablingEffects.get(hint);
        hintListeners.add(info.component);
    }

    private int calculateHints() {
        int hints = 0;
        for (int i = mListenersDisablingEffects.size() - 1; i >= 0; --i) {
            int hint = mListenersDisablingEffects.keyAt(i);
            ArraySet<ComponentName> serviceInfoList = mListenersDisablingEffects.valueAt(i);

            if (!serviceInfoList.isEmpty()) {
                hints |= hint;
            }
        }

        return hints;
    }

    private long calculateSuppressedEffects() {
        int hints = calculateHints();
        long suppressedEffects = 0;

        if ((hints & HINT_HOST_DISABLE_EFFECTS) != 0) {
            suppressedEffects |= ZenModeHelper.SUPPRESSED_EFFECT_ALL;
        }

        if ((hints & HINT_HOST_DISABLE_NOTIFICATION_EFFECTS) != 0) {
            suppressedEffects |= ZenModeHelper.SUPPRESSED_EFFECT_NOTIFICATIONS;
        }

        if ((hints & HINT_HOST_DISABLE_CALL_EFFECTS) != 0) {
            suppressedEffects |= ZenModeHelper.SUPPRESSED_EFFECT_CALLS;
        }

        return suppressedEffects;
    }

    @GuardedBy("mNotificationLock")
    private void updateInterruptionFilterLocked() {
        int interruptionFilter = mZenModeHelper.getZenModeListenerInterruptionFilter();
        if (interruptionFilter == mInterruptionFilter) return;
        mInterruptionFilter = interruptionFilter;
        scheduleInterruptionFilterChanged(interruptionFilter);
    }

    int correctCategory(int requestedCategoryList, int categoryType,
            int currentCategoryList) {
        if ((requestedCategoryList & categoryType) != 0
                && (currentCategoryList & categoryType) == 0) {
            requestedCategoryList &= ~categoryType;
        } else if ((requestedCategoryList & categoryType) == 0
                && (currentCategoryList & categoryType) != 0){
            requestedCategoryList |= categoryType;
        }
        return requestedCategoryList;
    }

    @VisibleForTesting
    INotificationManager getBinderService() {
        return INotificationManager.Stub.asInterface(mService);
    }

    /**
     * Report to usage stats that the notification was seen.
     * @param r notification record
     */
    @GuardedBy("mNotificationLock")
    protected void reportSeen(NotificationRecord r) {
        if (!r.isProxied()) {
            mAppUsageStats.reportEvent(r.getSbn().getPackageName(),
                    getRealUserId(r.getSbn().getUserId()),
                    UsageEvents.Event.NOTIFICATION_SEEN);
        }
    }

    protected int calculateSuppressedVisualEffects(Policy incomingPolicy, Policy currPolicy,
            int targetSdkVersion) {
        if (incomingPolicy.suppressedVisualEffects == SUPPRESSED_EFFECTS_UNSET) {
            return incomingPolicy.suppressedVisualEffects;
        }
        final int[] effectsIntroducedInP = {
                SUPPRESSED_EFFECT_FULL_SCREEN_INTENT,
                SUPPRESSED_EFFECT_LIGHTS,
                SUPPRESSED_EFFECT_PEEK,
                SUPPRESSED_EFFECT_STATUS_BAR,
                SUPPRESSED_EFFECT_BADGE,
                SUPPRESSED_EFFECT_AMBIENT,
                SUPPRESSED_EFFECT_NOTIFICATION_LIST
        };

        int newSuppressedVisualEffects = incomingPolicy.suppressedVisualEffects;
        if (targetSdkVersion < Build.VERSION_CODES.P) {
            // unset higher order bits introduced in P, maintain the user's higher order bits
            for (int i = 0; i < effectsIntroducedInP.length ; i++) {
                newSuppressedVisualEffects &= ~effectsIntroducedInP[i];
                newSuppressedVisualEffects |=
                        (currPolicy.suppressedVisualEffects & effectsIntroducedInP[i]);
            }
            // set higher order bits according to lower order bits
            if ((newSuppressedVisualEffects & SUPPRESSED_EFFECT_SCREEN_OFF) != 0) {
                newSuppressedVisualEffects |= SUPPRESSED_EFFECT_LIGHTS;
                newSuppressedVisualEffects |= SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
            }
            if ((newSuppressedVisualEffects & SUPPRESSED_EFFECT_SCREEN_ON) != 0) {
                newSuppressedVisualEffects |= SUPPRESSED_EFFECT_PEEK;
            }
        } else {
            boolean hasNewEffects = (newSuppressedVisualEffects
                    - SUPPRESSED_EFFECT_SCREEN_ON - SUPPRESSED_EFFECT_SCREEN_OFF) > 0;
            // if any of the new effects introduced in P are set
            if (hasNewEffects) {
                // clear out the deprecated effects
                newSuppressedVisualEffects &= ~ (SUPPRESSED_EFFECT_SCREEN_ON
                        | SUPPRESSED_EFFECT_SCREEN_OFF);

                // set the deprecated effects according to the new more specific effects
                if ((newSuppressedVisualEffects & Policy.SUPPRESSED_EFFECT_PEEK) != 0) {
                    newSuppressedVisualEffects |= SUPPRESSED_EFFECT_SCREEN_ON;
                }
                if ((newSuppressedVisualEffects & Policy.SUPPRESSED_EFFECT_LIGHTS) != 0
                        && (newSuppressedVisualEffects
                        & Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT) != 0
                        && (newSuppressedVisualEffects
                        & Policy.SUPPRESSED_EFFECT_AMBIENT) != 0) {
                    newSuppressedVisualEffects |= SUPPRESSED_EFFECT_SCREEN_OFF;
                }
            } else {
                // set higher order bits according to lower order bits
                if ((newSuppressedVisualEffects & SUPPRESSED_EFFECT_SCREEN_OFF) != 0) {
                    newSuppressedVisualEffects |= SUPPRESSED_EFFECT_LIGHTS;
                    newSuppressedVisualEffects |= SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
                    newSuppressedVisualEffects |= SUPPRESSED_EFFECT_AMBIENT;
                }
                if ((newSuppressedVisualEffects & SUPPRESSED_EFFECT_SCREEN_ON) != 0) {
                    newSuppressedVisualEffects |= SUPPRESSED_EFFECT_PEEK;
                }
            }
        }

        return newSuppressedVisualEffects;
    }

    @GuardedBy("mNotificationLock")
    protected void maybeRecordInterruptionLocked(NotificationRecord r) {
        if (r.isInterruptive() && !r.hasRecordedInterruption()) {
            mAppUsageStats.reportInterruptiveNotification(r.getSbn().getPackageName(),
                    r.getChannel().getId(),
                    getRealUserId(r.getSbn().getUserId()));
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "notifHistoryAddItem");
            try {
                if (r.getNotification().getSmallIcon() != null) {
                    mHistoryManager.addNotification(new HistoricalNotification.Builder()
                            .setPackage(r.getSbn().getPackageName())
                            .setUid(r.getSbn().getUid())
                            .setUserId(r.getSbn().getNormalizedUserId())
                            .setChannelId(r.getChannel().getId())
                            .setChannelName(r.getChannel().getName().toString())
                            .setPostedTimeMs(System.currentTimeMillis())
                            .setTitle(getHistoryTitle(r.getNotification()))
                            .setText(getHistoryText(
                                    r.getSbn().getPackageContext(getContext()),
                                    r.getNotification()))
                            .setIcon(r.getNotification().getSmallIcon())
                            .build());
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
            r.setRecordedInterruption(true);
        }
    }

    protected void reportForegroundServiceUpdate(boolean shown,
            final Notification notification, final int id, final String pkg, final int userId) {
        mHandler.post(() -> {
            mAmi.onForegroundServiceNotificationUpdate(shown, notification, id, pkg, userId);
        });
    }

    protected void maybeReportForegroundServiceUpdate(final NotificationRecord r, boolean shown) {
        if (r.isForegroundService()) {
            // snapshot live state for the asynchronous operation
            final StatusBarNotification sbn = r.getSbn();
            reportForegroundServiceUpdate(shown, sbn.getNotification(), sbn.getId(),
                    sbn.getPackageName(), sbn.getUser().getIdentifier());
        }
    }

    private String getHistoryTitle(Notification n) {
        CharSequence title = null;
        if (n.extras != null) {
            title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            if (title == null) {
                title = n.extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
            }
        }
        return title == null ? getContext().getResources().getString(
            com.android.internal.R.string.notification_history_title_placeholder)
            : String.valueOf(title);
    }

    /**
     * Returns the appropriate substring for this notification based on the style of notification.
     */
    private String getHistoryText(Context appContext, Notification n) {
        CharSequence text = null;
        if (n.extras != null) {
            text = n.extras.getCharSequence(Notification.EXTRA_TEXT);

            Notification.Builder nb = Notification.Builder.recoverBuilder(appContext, n);

            if (nb.getStyle() instanceof Notification.BigTextStyle) {
                text = ((Notification.BigTextStyle) nb.getStyle()).getBigText();
            } else if (nb.getStyle() instanceof Notification.MessagingStyle) {
                Notification.MessagingStyle ms = (Notification.MessagingStyle) nb.getStyle();
                final List<Notification.MessagingStyle.Message> messages = ms.getMessages();
                if (messages != null && messages.size() > 0) {
                    text = messages.get(messages.size() - 1).getText();
                }
            }

            if (TextUtils.isEmpty(text)) {
                text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            }
        }
        return text == null ? null : String.valueOf(text);
    }

    protected void maybeRegisterMessageSent(NotificationRecord r) {
        if (r.isConversation()) {
            if (r.getShortcutInfo() != null) {
                if (mPreferencesHelper.setValidMessageSent(
                        r.getSbn().getPackageName(), r.getUid())) {
                    handleSavePolicyFile();
                } else if (r.getNotification().getBubbleMetadata() != null) {
                    // If bubble metadata is present it is valid (if invalid it's removed
                    // via BubbleExtractor).
                    if (mPreferencesHelper.setValidBubbleSent(
                            r.getSbn().getPackageName(), r.getUid())) {
                        handleSavePolicyFile();
                    }
                }
            } else {
                if (mPreferencesHelper.setInvalidMessageSent(
                        r.getSbn().getPackageName(), r.getUid())) {
                    handleSavePolicyFile();
                }
            }
        }
    }

    /**
     * Report to usage stats that the user interacted with the notification.
     * @param r notification record
     */
    protected void reportUserInteraction(NotificationRecord r) {
        mAppUsageStats.reportEvent(r.getSbn().getPackageName(),
                getRealUserId(r.getSbn().getUserId()),
                UsageEvents.Event.USER_INTERACTION);

        if (Flags.politeNotifications()) {
            mAttentionHelper.onUserInteraction(r);
        }
    }

    private int getRealUserId(int userId) {
        return userId == UserHandle.USER_ALL ? UserHandle.USER_SYSTEM : userId;
    }

    private ToastRecord getToastRecord(int uid, int pid, String packageName, boolean isSystemToast,
            IBinder token, @Nullable CharSequence text, @Nullable ITransientNotification callback,
            int duration, Binder windowToken, int displayId,
            @Nullable ITransientNotificationCallback textCallback) {
        if (callback == null) {
            return new TextToastRecord(this, mStatusBar, uid, pid, packageName,
                    isSystemToast, token, text, duration, windowToken, displayId, textCallback);
        } else {
            return new CustomToastRecord(this, uid, pid, packageName,
                    isSystemToast, token, callback, duration, windowToken, displayId);
        }
    }

    @VisibleForTesting
    NotificationManagerInternal getInternalService() {
        return mInternalService;
    }

    private MultiRateLimiter createToastRateLimiter() {
        return new MultiRateLimiter.Builder(getContext()).addRateLimits(TOAST_RATE_LIMITS).build();
    }

    protected int checkComponentPermission(String permission, int uid, int owningUid,
            boolean exported) {
        return ActivityManager.checkComponentPermission(permission, uid, owningUid, exported);
    }

    @VisibleForTesting
    final IBinder mService = new INotificationManager.Stub() {
        // Toasts
        // ============================================================================

        @Override
        public void enqueueTextToast(String pkg, IBinder token, CharSequence text, int duration,
                boolean isUiContext, int displayId,
                @Nullable ITransientNotificationCallback textCallback) {
            enqueueToast(pkg, token, text, /* callback= */ null, duration, isUiContext, displayId,
                    textCallback);
        }

        @Override
        public void enqueueToast(String pkg, IBinder token, ITransientNotification callback,
                int duration, boolean isUiContext, int displayId) {
            enqueueToast(pkg, token, /* text= */ null, callback, duration, isUiContext, displayId,
                    /* textCallback= */ null);
        }

        private void enqueueToast(String pkg, IBinder token, @Nullable CharSequence text,
                @Nullable ITransientNotification callback, int duration, boolean isUiContext,
                int displayId, @Nullable ITransientNotificationCallback textCallback) {
            if (DBG) {
                Slog.i(TAG, "enqueueToast pkg=" + pkg + " token=" + token + " duration=" + duration
                        + " isUiContext=" + isUiContext + " displayId=" + displayId);
            }

            if (pkg == null || (text == null && callback == null)
                    || (text != null && callback != null) || token == null) {
                Slog.e(TAG, "Not enqueuing toast. pkg=" + pkg + " text=" + text + " callback="
                        + " token=" + token);
                return;
            }

            final int callingUid = Binder.getCallingUid();
            if (!isUiContext && displayId == Display.DEFAULT_DISPLAY
                    && mUm.isVisibleBackgroundUsersSupported()) {
                // When the caller is a visible background user using a non-UI context (like the
                // application context), the Toast must be displayed in the display the user was
                // started visible on.
                int userId = UserHandle.getUserId(callingUid);
                int userDisplayId = mUmInternal.getMainDisplayAssignedToUser(userId);
                if (displayId != userDisplayId) {
                    if (DBG) {
                        Slogf.d(TAG, "Changing display id from %d to %d on user %d", displayId,
                                userDisplayId, userId);
                    }
                    displayId = userDisplayId;
                }
            }

            checkCallerIsSameApp(pkg);
            final boolean isSystemToast = isCallerIsSystemOrSystemUi()
                    || PackageManagerService.PLATFORM_PACKAGE_NAME.equals(pkg);
            boolean isAppRenderedToast = (callback != null);
            if (!checkCanEnqueueToast(pkg, callingUid, displayId, isAppRenderedToast,
                    isSystemToast)) {
                return;
            }

            synchronized (mToastQueue) {
                int callingPid = Binder.getCallingPid();
                final long callingId = Binder.clearCallingIdentity();
                try {
                    ToastRecord record;
                    int index = indexOfToastLocked(pkg, token);
                    // If it's already in the queue, we update it in place, we don't
                    // move it to the end of the queue.
                    if (index >= 0) {
                        record = mToastQueue.get(index);
                        record.update(duration);
                    } else {
                        // Limit the number of toasts that any given package can enqueue.
                        // Prevents DOS attacks and deals with leaks.
                        int count = 0;
                        final int N = mToastQueue.size();
                        for (int i = 0; i < N; i++) {
                            final ToastRecord r = mToastQueue.get(i);
                            if (r.pkg.equals(pkg)) {
                                count++;
                                if (count >= MAX_PACKAGE_TOASTS) {
                                    Slog.e(TAG, "Package has already queued " + count
                                            + " toasts. Not showing more. Package=" + pkg);
                                    return;
                                }
                            }
                        }

                        Binder windowToken = new Binder();
                        mWindowManagerInternal.addWindowToken(windowToken, TYPE_TOAST, displayId,
                                null /* options */);
                        record = getToastRecord(callingUid, callingPid, pkg, isSystemToast, token,
                                text, callback, duration, windowToken, displayId, textCallback);

                        // Insert system toasts at the front of the queue
                        int systemToastInsertIdx = mToastQueue.size();
                        if (isSystemToast) {
                            systemToastInsertIdx = getInsertIndexForSystemToastLocked();
                        }
                        if (systemToastInsertIdx < mToastQueue.size()) {
                            index = systemToastInsertIdx;
                            mToastQueue.add(index, record);
                        } else {
                            mToastQueue.add(record);
                            index = mToastQueue.size() - 1;
                        }
                        keepProcessAliveForToastIfNeededLocked(callingPid);
                    }
                    // If it's at index 0, it's the current toast.  It doesn't matter if it's
                    // new or just been updated, show it.
                    // If the callback fails, this will remove it from the list, so don't
                    // assume that it's valid after this.
                    if (index == 0) {
                        showNextToastLocked(false);
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
        }

        @GuardedBy("mToastQueue")
        private int getInsertIndexForSystemToastLocked() {
            // If there are other system toasts: insert after the last one
            int idx = 0;
            for (ToastRecord r : mToastQueue) {
                if (idx == 0 && mIsCurrentToastShown) {
                    idx++;
                    continue;
                }
                if (!r.isSystemToast) {
                    return idx;
                }
                idx++;
            }
            return idx;
        }

        private boolean checkCanEnqueueToast(String pkg, int callingUid, int displayId,
                boolean isAppRenderedToast, boolean isSystemToast) {
            final boolean isPackageSuspended = isPackagePaused(pkg);
            final boolean notificationsDisabledForPackage = !areNotificationsEnabledForPackage(pkg,
                    callingUid);

            final boolean appIsForeground;
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                appIsForeground = mActivityManager.getUidImportance(callingUid)
                        == IMPORTANCE_FOREGROUND;
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }

            if (!isSystemToast && ((notificationsDisabledForPackage && !appIsForeground)
                    || isPackageSuspended)) {
                Slog.e(TAG, "Suppressing toast from package " + pkg
                        + (isPackageSuspended ? " due to package suspended."
                        : " by user request."));
                return false;
            }

            if (blockToast(callingUid, isSystemToast, isAppRenderedToast,
                    isPackageInForegroundForToast(callingUid))) {
                Slog.w(TAG, "Blocking custom toast from package " + pkg
                        + " due to package not in the foreground at time the toast was posted");
                return false;
            }

            int userId = UserHandle.getUserId(callingUid);
            if (!isSystemToast && !mUmInternal.isUserVisible(userId, displayId)) {
                Slog.e(TAG, "Suppressing toast from package " + pkg + "/" + callingUid + " as user "
                        + userId + " is not visible on display " + displayId);
                return false;
            }

            return true;
        }

        @Override
        public void cancelToast(String pkg, IBinder token) {
            Slog.i(TAG, "cancelToast pkg=" + pkg + " token=" + token);

            if (pkg == null || token == null) {
                Slog.e(TAG, "Not cancelling notification. pkg=" + pkg + " token=" + token);
                return;
            }

            synchronized (mToastQueue) {
                final long callingId = Binder.clearCallingIdentity();
                try {
                    int index = indexOfToastLocked(pkg, token);
                    if (index >= 0) {
                        cancelToastLocked(index);
                    } else {
                        Slog.w(TAG, "Toast already cancelled. pkg=" + pkg
                                + " token=" + token);
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_TOAST_RATE_LIMITING)
        @Override
        public void setToastRateLimitingEnabled(boolean enable) {

            super.setToastRateLimitingEnabled_enforcePermission();

            synchronized (mToastQueue) {
                int uid = Binder.getCallingUid();
                int userId = UserHandle.getUserId(uid);
                if (enable) {
                    mToastRateLimitingDisabledUids.remove(uid);
                    try {
                        String[] packages = mPackageManager.getPackagesForUid(uid);
                        if (packages == null) {
                            Slog.e(TAG, "setToastRateLimitingEnabled method haven't found any "
                                    + "packages for the  given uid: " + uid + ", toast rate "
                                    + "limiter not reset for that uid.");
                            return;
                        }
                        for (String pkg : packages) {
                            mToastRateLimiter.clear(userId, pkg);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to reset toast rate limiter for given uid", e);
                    }
                } else {
                    mToastRateLimitingDisabledUids.add(uid);
                }
            }
        }

        @Override
        public void finishToken(String pkg, IBinder token) {
            synchronized (mToastQueue) {
                final long callingId = Binder.clearCallingIdentity();
                try {
                    int index = indexOfToastLocked(pkg, token);
                    if (index >= 0) {
                        ToastRecord record = mToastQueue.get(index);
                        finishWindowTokenLocked(record.windowToken, record.displayId);
                    } else {
                        Slog.w(TAG, "Toast already killed. pkg=" + pkg
                                + " token=" + token);
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
        }

        @Override
        public void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id,
                Notification notification, int userId) throws RemoteException {
            enqueueNotificationInternal(pkg, opPkg, Binder.getCallingUid(),
                    Binder.getCallingPid(), tag, id, notification, userId,
                    false /* byForegroundService */);
        }

        @Override
        public void cancelNotificationWithTag(String pkg, String opPkg, String tag, int id,
                int userId) {
            cancelNotificationInternal(pkg, opPkg, Binder.getCallingUid(), Binder.getCallingPid(),
                    tag, id, userId);
        }

        @Override
        public void cancelAllNotifications(String pkg, int userId) {
            checkCallerIsSystemOrSameApp(pkg);

            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, true, false, "cancelAllNotifications", pkg);

            // Don't allow the app to cancel active FGS or UIJ notifications
            cancelAllNotificationsInt(Binder.getCallingUid(), Binder.getCallingPid(),
                    pkg, null, 0, FLAG_FOREGROUND_SERVICE | FLAG_USER_INITIATED_JOB,
                    userId, REASON_APP_CANCEL_ALL);
        }

        @Override
        public void silenceNotificationSound() {
            checkCallerIsSystem();

            mNotificationDelegate.clearEffects();
        }

        @Override
        public void setNotificationsEnabledForPackage(String pkg, int uid, boolean enabled) {
            enforceSystemOrSystemUI("setNotificationsEnabledForPackage");
            boolean wasEnabled = mPermissionHelper.hasPermission(uid);
            if (wasEnabled == enabled) {
                return;
            }
            mPermissionHelper.setNotificationPermission(
                    pkg, UserHandle.getUserId(uid), enabled, true);
            sendAppBlockStateChangedBroadcast(pkg, uid, !enabled);

            mMetricsLogger.write(new LogMaker(MetricsEvent.ACTION_BAN_APP_NOTES)
                    .setType(MetricsEvent.TYPE_ACTION)
                    .setPackageName(pkg)
                    .setSubtype(enabled ? 1 : 0));
            mNotificationChannelLogger.logAppNotificationsAllowed(uid, pkg, enabled);

            // Outstanding notifications from this package will be cancelled as soon as we get the
            // callback from AppOpsManager.
        }

        /**
         * Updates the enabled state for notifications for the given package (and uid).
         * Additionally, this method marks the app importance as locked by the user, which
         * means
         * that notifications from the app will <b>not</b> be considered for showing a
         * blocking helper.
         *
         * @param pkg     package that owns the notifications to update
         * @param uid     uid of the app providing notifications
         * @param enabled whether notifications should be enabled for the app
         * @see #setNotificationsEnabledForPackage(String, int, boolean)
         */
        @Override
        public void setNotificationsEnabledWithImportanceLockForPackage(
                String pkg, int uid, boolean enabled) {
            setNotificationsEnabledForPackage(pkg, uid, enabled);
        }

        /**
         * Use this when you just want to know if notifications are OK for this package.
         */
        @Override
        public boolean areNotificationsEnabled(String pkg) {
            return areNotificationsEnabledForPackage(pkg, Binder.getCallingUid());
        }

        /**
         * Use this when you just want to know if notifications are OK for this package.
         */
        @Override
        public boolean areNotificationsEnabledForPackage(String pkg, int uid) {
            enforceSystemOrSystemUIOrSamePackage(pkg,
                    "Caller not system or systemui or same package");
            if (UserHandle.getCallingUserId() != UserHandle.getUserId(uid)) {
                getContext().enforceCallingPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        "canNotifyAsPackage for uid " + uid);
            }

            return areNotificationsEnabledForPackageInt(pkg, uid);
        }

        /**
         * @return true if and only if "all" bubbles are allowed from the provided package.
         */
        @Override
        public boolean areBubblesAllowed(String pkg) {
            return getBubblePreferenceForPackage(pkg, Binder.getCallingUid())
                    == BUBBLE_PREFERENCE_ALL;
        }

        /**
         * @return true if this user has bubbles enabled at the feature-level.
         */
        @Override
        public boolean areBubblesEnabled(UserHandle user) {
            if (UserHandle.getCallingUserId() != user.getIdentifier()) {
                getContext().enforceCallingPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        "areBubblesEnabled for user " + user.getIdentifier());
            }
            return mPreferencesHelper.bubblesEnabled(user);
        }

        @Override
        public int getBubblePreferenceForPackage(String pkg, int uid) {
            enforceSystemOrSystemUIOrSamePackage(pkg,
                    "Caller not system or systemui or same package");

            if (UserHandle.getCallingUserId() != UserHandle.getUserId(uid)) {
                getContext().enforceCallingPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        "getBubblePreferenceForPackage for uid " + uid);
            }

            return mPreferencesHelper.getBubblePreference(pkg, uid);
        }

        @Override
        public void setBubblesAllowed(String pkg, int uid, int bubblePreference) {
            checkCallerIsSystemOrSystemUiOrShell("Caller not system or sysui or shell");
            mPreferencesHelper.setBubblesAllowed(pkg, uid, bubblePreference);
            handleSavePolicyFile();
        }

        @Override
        public boolean shouldHideSilentStatusIcons(String callingPkg) {
            checkCallerIsSameApp(callingPkg);

            if (isCallerSystemOrPhone()
                    || mListeners.isListenerPackage(callingPkg)) {
                return mPreferencesHelper.shouldHideSilentStatusIcons();
            } else {
                throw new SecurityException("Only available for notification listeners");
            }
        }

        @Override
        public void setHideSilentStatusIcons(boolean hide) {
            checkCallerIsSystem();

            mPreferencesHelper.setHideSilentStatusIcons(hide);
            handleSavePolicyFile();

            mListeners.onStatusBarIconsBehaviorChanged(hide);
        }

        @Override
        public void deleteNotificationHistoryItem(String pkg, int uid, long postedTime) {
            checkCallerIsSystem();
            mHistoryManager.deleteNotificationHistoryItem(pkg, uid, postedTime);
        }

        @Override
        public NotificationListenerFilter getListenerFilter(ComponentName cn, int userId) {
            checkCallerIsSystem();
            return mListeners.getNotificationListenerFilter(Pair.create(cn, userId));
        }

        @Override
        public void setListenerFilter(ComponentName cn, int userId,
                NotificationListenerFilter nlf) {
            checkCallerIsSystem();
            mListeners.setNotificationListenerFilter(Pair.create(cn, userId), nlf);
            // TODO (b/173052211): cancel notifications for listeners that can no longer see them
            handleSavePolicyFile();
        }

        @Override
        public int getPackageImportance(String pkg) {
            checkCallerIsSystemOrSameApp(pkg);
            if (mPermissionHelper.hasPermission(Binder.getCallingUid())) {
                return IMPORTANCE_DEFAULT;
            } else {
                return IMPORTANCE_NONE;
            }
        }

        @Override
        public boolean isImportanceLocked(String pkg, int uid) {
            checkCallerIsSystem();
            return mPreferencesHelper.isImportanceLocked(pkg, uid);
        }

        @Override
        public boolean canShowBadge(String pkg, int uid) {
            checkCallerIsSystem();
            return mPreferencesHelper.canShowBadge(pkg, uid);
        }

        @Override
        public void setShowBadge(String pkg, int uid, boolean showBadge) {
            checkCallerIsSystem();
            mPreferencesHelper.setShowBadge(pkg, uid, showBadge);
            handleSavePolicyFile();
        }

        @Override
        public boolean hasSentValidMsg(String pkg, int uid) {
            checkCallerIsSystem();
            return mPreferencesHelper.hasSentValidMsg(pkg, uid);
        }

        @Override
        public boolean isInInvalidMsgState(String pkg, int uid) {
            checkCallerIsSystem();
            return mPreferencesHelper.isInInvalidMsgState(pkg, uid);
        }

        @Override
        public boolean hasUserDemotedInvalidMsgApp(String pkg, int uid) {
            checkCallerIsSystem();
            return mPreferencesHelper.hasUserDemotedInvalidMsgApp(pkg, uid);
        }

        @Override
        public void setInvalidMsgAppDemoted(String pkg, int uid, boolean isDemoted) {
            checkCallerIsSystem();
            mPreferencesHelper.setInvalidMsgAppDemoted(pkg, uid, isDemoted);
            handleSavePolicyFile();
        }

        @Override
        public boolean hasSentValidBubble(String pkg, int uid) {
            checkCallerIsSystem();
            return mPreferencesHelper.hasSentValidBubble(pkg, uid);
        }

        @Override
        public void setNotificationDelegate(String callingPkg, String delegate) {
            checkCallerIsSameApp(callingPkg);
            final int callingUid = Binder.getCallingUid();
            UserHandle user = UserHandle.getUserHandleForUid(callingUid);
            if (delegate == null) {
                mPreferencesHelper.revokeNotificationDelegate(callingPkg, Binder.getCallingUid());
                handleSavePolicyFile();
            } else {
                try {
                    ApplicationInfo info =
                            mPackageManager.getApplicationInfo(delegate,
                                    MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                                    user.getIdentifier());
                    if (info != null) {
                        mPreferencesHelper.setNotificationDelegate(
                                callingPkg, callingUid, delegate, info.uid);
                        handleSavePolicyFile();
                    }
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }

        @Override
        public String getNotificationDelegate(String callingPkg) {
            // callable by Settings also
            checkCallerIsSystemOrSameApp(callingPkg);
            return mPreferencesHelper.getNotificationDelegate(callingPkg, Binder.getCallingUid());
        }

        @Override
        public boolean canNotifyAsPackage(String callingPkg, String targetPkg, int userId) {
            checkCallerIsSameApp(callingPkg);
            final int callingUid = Binder.getCallingUid();
            UserHandle user = UserHandle.getUserHandleForUid(callingUid);
            if (user.getIdentifier() != userId) {
                getContext().enforceCallingPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        "canNotifyAsPackage for user " + userId);
            }
            if (callingPkg.equals(targetPkg)) {
                return true;
            }
            try {
                ApplicationInfo info =
                        mPackageManager.getApplicationInfo(targetPkg,
                                MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                                userId);
                if (info != null) {
                    return mPreferencesHelper.isDelegateAllowed(
                            targetPkg, info.uid, callingPkg, callingUid);
                }
            } catch (RemoteException e) {
                // :(
            }
            return false;
        }

        @Override
        public boolean canUseFullScreenIntent(@NonNull AttributionSource attributionSource) {
            final String packageName = attributionSource.getPackageName();
            final int uid = attributionSource.getUid();
            final int userId = UserHandle.getUserId(uid);
            checkCallerIsSameApp(packageName, uid, userId);

            final ApplicationInfo applicationInfo;
            try {
                applicationInfo = mPackageManagerClient.getApplicationInfoAsUser(
                        packageName, PackageManager.MATCH_DIRECT_BOOT_AUTO, userId);
            } catch (NameNotFoundException e) {
                Slog.e(TAG, "Failed to getApplicationInfo() in canUseFullScreenIntent()", e);
                return false;
            }
            final boolean showStickyHunIfDenied = mFlagResolver.isEnabled(
                    SystemUiSystemPropertiesFlags.NotificationFlags
                            .SHOW_STICKY_HUN_FOR_DENIED_FSI);
            return checkUseFullScreenIntentPermission(attributionSource, applicationInfo,
                    showStickyHunIfDenied /* isAppOpPermission */, false /* forDataDelivery */);
        }

        @Override
        public void updateNotificationChannelGroupForPackage(String pkg, int uid,
                NotificationChannelGroup group) throws RemoteException {
            enforceSystemOrSystemUI("Caller not system or systemui");
            createNotificationChannelGroup(pkg, uid, group, false, false);
            handleSavePolicyFile();
        }

        @Override
        public void createNotificationChannelGroups(String pkg,
                ParceledListSlice channelGroupList) throws RemoteException {
            checkCallerIsSystemOrSameApp(pkg);
            List<NotificationChannelGroup> groups = channelGroupList.getList();
            final int groupSize = groups.size();
            for (int i = 0; i < groupSize; i++) {
                final NotificationChannelGroup group = groups.get(i);
                createNotificationChannelGroup(pkg, Binder.getCallingUid(), group, true, false);
            }
            handleSavePolicyFile();
        }

        private void createNotificationChannelsImpl(String pkg, int uid,
                ParceledListSlice channelsList) {
            createNotificationChannelsImpl(pkg, uid, channelsList,
                    ActivityTaskManager.INVALID_TASK_ID);
        }

        private void createNotificationChannelsImpl(String pkg, int uid,
                ParceledListSlice channelsList, int startingTaskId) {
            List<NotificationChannel> channels = channelsList.getList();
            final int channelsSize = channels.size();
            ParceledListSlice<NotificationChannel> oldChannels =
                    mPreferencesHelper.getNotificationChannels(pkg, uid, true);
            final boolean hadChannel = oldChannels != null && !oldChannels.getList().isEmpty();
            boolean needsPolicyFileChange = false;
            boolean hasRequestedNotificationPermission = false;
            for (int i = 0; i < channelsSize; i++) {
                final NotificationChannel channel = channels.get(i);
                Objects.requireNonNull(channel, "channel in list is null");
                needsPolicyFileChange = mPreferencesHelper.createNotificationChannel(pkg, uid,
                        channel, true /* fromTargetApp */,
                        mConditionProviders.isPackageOrComponentAllowed(
                                pkg, UserHandle.getUserId(uid)), Binder.getCallingUid(),
                        isCallerIsSystemOrSystemUi());
                if (needsPolicyFileChange) {
                    mListeners.notifyNotificationChannelChanged(pkg,
                            UserHandle.getUserHandleForUid(uid),
                            mPreferencesHelper.getNotificationChannel(pkg, uid, channel.getId(),
                                    false),
                            NOTIFICATION_CHANNEL_OR_GROUP_ADDED);
                    boolean hasChannel = hadChannel || hasRequestedNotificationPermission;
                    if (!hasChannel) {
                        ParceledListSlice<NotificationChannel> currChannels =
                                mPreferencesHelper.getNotificationChannels(pkg, uid, true);
                        hasChannel = currChannels != null && !currChannels.getList().isEmpty();
                    }
                    if (!hadChannel && hasChannel && !hasRequestedNotificationPermission
                            && startingTaskId != ActivityTaskManager.INVALID_TASK_ID) {
                        hasRequestedNotificationPermission = true;
                        if (mPermissionPolicyInternal == null) {
                            mPermissionPolicyInternal =
                                    LocalServices.getService(PermissionPolicyInternal.class);
                        }
                        mHandler.post(new ShowNotificationPermissionPromptRunnable(pkg,
                                UserHandle.getUserId(uid), startingTaskId,
                                mPermissionPolicyInternal));
                    }
                }
            }
            if (needsPolicyFileChange) {
                handleSavePolicyFile();
            }
        }

        @Override
        public void createNotificationChannels(String pkg, ParceledListSlice channelsList) {
            checkCallerIsSystemOrSameApp(pkg);
            int taskId = ActivityTaskManager.INVALID_TASK_ID;
            try {
                int uid = mPackageManager.getPackageUid(pkg, 0,
                        UserHandle.getUserId(Binder.getCallingUid()));
                taskId = mAtm.getTaskToShowPermissionDialogOn(pkg, uid);
            } catch (RemoteException e) {
                // Do nothing
            }
            createNotificationChannelsImpl(pkg, Binder.getCallingUid(), channelsList, taskId);
        }

        @Override
        public void createNotificationChannelsForPackage(String pkg, int uid,
                ParceledListSlice channelsList) {
            enforceSystemOrSystemUI("only system can call this");
            createNotificationChannelsImpl(pkg, uid, channelsList);
        }

        @Override
        public void createConversationNotificationChannelForPackage(String pkg, int uid,
                NotificationChannel parentChannel, String conversationId) {
            enforceSystemOrSystemUI("only system can call this");
            Preconditions.checkNotNull(parentChannel);
            Preconditions.checkNotNull(conversationId);
            String parentId = parentChannel.getId();
            NotificationChannel conversationChannel = parentChannel;
            conversationChannel.setId(String.format(
                    CONVERSATION_CHANNEL_ID_FORMAT, parentId, conversationId));
            conversationChannel.setConversationId(parentId, conversationId);
            createNotificationChannelsImpl(
                    pkg, uid, new ParceledListSlice(Arrays.asList(conversationChannel)));
            mRankingHandler.requestSort();
            handleSavePolicyFile();
        }

        @Override
        public NotificationChannel getNotificationChannel(String callingPkg, int userId,
                String targetPkg, String channelId) {
            return getConversationNotificationChannel(
                    callingPkg, userId, targetPkg, channelId, true, null);
        }

        @Override
        public NotificationChannel getConversationNotificationChannel(String callingPkg, int userId,
                String targetPkg, String channelId, boolean returnParentIfNoConversationChannel,
                String conversationId) {
            if (canNotifyAsPackage(callingPkg, targetPkg, userId)
                    || isCallerIsSystemOrSysemUiOrShell()) {
                int targetUid = -1;
                try {
                    targetUid = mPackageManagerClient.getPackageUidAsUser(targetPkg, userId);
                } catch (NameNotFoundException e) {
                    /* ignore */
                }
                return mPreferencesHelper.getConversationNotificationChannel(
                        targetPkg, targetUid, channelId, conversationId,
                        returnParentIfNoConversationChannel, false /* includeDeleted */);
            }
            throw new SecurityException("Pkg " + callingPkg
                    + " cannot read channels for " + targetPkg + " in " + userId);
        }

        @Override
        public NotificationChannel getNotificationChannelForPackage(String pkg, int uid,
                String channelId, String conversationId, boolean includeDeleted) {
            checkCallerIsSystem();
            return mPreferencesHelper.getConversationNotificationChannel(
                    pkg, uid, channelId, conversationId, true, includeDeleted);
        }

        // Returns 'true' if the given channel has a notification associated
        // with an active foreground service.
        private void enforceDeletingChannelHasNoFgService(String pkg, int userId,
                String channelId) {
            if (mAmi.hasForegroundServiceNotification(pkg, userId, channelId)) {
                Slog.w(TAG, "Package u" + userId + "/" + pkg
                        + " may not delete notification channel '"
                        + channelId + "' with fg service");
                throw new SecurityException("Not allowed to delete channel " + channelId
                        + " with a foreground service");
            }
        }

        // Throws a security exception if the given channel has a notification associated
        // with an active user-initiated job.
        private void enforceDeletingChannelHasNoUserInitiatedJob(String pkg, int userId,
                String channelId) {
            final JobSchedulerInternal js = LocalServices.getService(JobSchedulerInternal.class);
            if (js != null && js.isNotificationChannelAssociatedWithAnyUserInitiatedJobs(
                    channelId, userId, pkg)) {
                Slog.w(TAG, "Package u" + userId + "/" + pkg
                        + " may not delete notification channel '"
                        + channelId + "' with user-initiated job");
                throw new SecurityException("Not allowed to delete channel " + channelId
                        + " with a user-initiated job");
            }
        }

        @Override
        public void deleteNotificationChannel(String pkg, String channelId) {
            checkCallerIsSystemOrSameApp(pkg);
            final int callingUid = Binder.getCallingUid();
            final boolean isSystemOrSystemUi = isCallerIsSystemOrSystemUi();
            final int callingUser = UserHandle.getUserId(callingUid);
            if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(channelId)) {
                throw new IllegalArgumentException("Cannot delete default channel");
            }
            enforceDeletingChannelHasNoFgService(pkg, callingUser, channelId);
            enforceDeletingChannelHasNoUserInitiatedJob(pkg, callingUser, channelId);
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channelId, 0, 0,
                    callingUser, REASON_CHANNEL_REMOVED);
            boolean previouslyExisted = mPreferencesHelper.deleteNotificationChannel(
                    pkg, callingUid, channelId, callingUid, isSystemOrSystemUi);
            if (previouslyExisted) {
                // Remove from both recent notification archive and notification history
                mArchive.removeChannelNotifications(pkg, callingUser, channelId);
                mHistoryManager.deleteNotificationChannel(pkg, callingUid, channelId);
                mListeners.notifyNotificationChannelChanged(pkg,
                        UserHandle.getUserHandleForUid(callingUid),
                        mPreferencesHelper.getNotificationChannel(pkg, callingUid, channelId, true),
                        NOTIFICATION_CHANNEL_OR_GROUP_DELETED);
                handleSavePolicyFile();
            }
        }

        @Override
        public NotificationChannelGroup getNotificationChannelGroup(String pkg, String groupId) {
            checkCallerIsSystemOrSameApp(pkg);
            return mPreferencesHelper.getNotificationChannelGroupWithChannels(
                    pkg, Binder.getCallingUid(), groupId, false);
        }

        @Override
        public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroups(
                String pkg) {
            checkCallerIsSystemOrSameApp(pkg);
            return mPreferencesHelper.getNotificationChannelGroups(
                    pkg, Binder.getCallingUid(), false, false, true, true, null);
        }

        @Override
        public void deleteNotificationChannelGroup(String pkg, String groupId) {
            checkCallerIsSystemOrSameApp(pkg);

            final int callingUid = Binder.getCallingUid();
            final boolean isSystemOrSystemUi = isCallerIsSystemOrSystemUi();
            NotificationChannelGroup groupToDelete =
                    mPreferencesHelper.getNotificationChannelGroupWithChannels(
                            pkg, callingUid, groupId, false);
            if (groupToDelete != null) {
                // Preflight for allowability
                final int userId = UserHandle.getUserId(callingUid);
                List<NotificationChannel> groupChannels = groupToDelete.getChannels();
                for (int i = 0; i < groupChannels.size(); i++) {
                    final String channelId = groupChannels.get(i).getId();
                    enforceDeletingChannelHasNoFgService(pkg, userId, channelId);
                    enforceDeletingChannelHasNoUserInitiatedJob(pkg, userId, channelId);
                }
                List<NotificationChannel> deletedChannels =
                        mPreferencesHelper.deleteNotificationChannelGroup(pkg, callingUid, groupId,
                                callingUid, isSystemOrSystemUi);
                for (int i = 0; i < deletedChannels.size(); i++) {
                    final NotificationChannel deletedChannel = deletedChannels.get(i);
                    cancelAllNotificationsInt(MY_UID, MY_PID, pkg, deletedChannel.getId(), 0, 0,
                            userId, REASON_CHANNEL_REMOVED
                    );
                    mListeners.notifyNotificationChannelChanged(pkg,
                            UserHandle.getUserHandleForUid(callingUid),
                            deletedChannel,
                            NOTIFICATION_CHANNEL_OR_GROUP_DELETED);
                }
                mListeners.notifyNotificationChannelGroupChanged(
                        pkg, UserHandle.getUserHandleForUid(callingUid), groupToDelete,
                        NOTIFICATION_CHANNEL_OR_GROUP_DELETED);
                handleSavePolicyFile();
            }
        }

        @Override
        public void updateNotificationChannelForPackage(String pkg, int uid,
                NotificationChannel channel) {
            checkCallerIsSystemOrSystemUiOrShell("Caller not system or sysui or shell");
            Objects.requireNonNull(channel);
            updateNotificationChannelInt(pkg, uid, channel, false);
        }

        @Override
        public void unlockNotificationChannel(String pkg, int uid, String channelId) {
            checkCallerIsSystemOrSystemUiOrShell("Caller not system or sysui or shell");
            mPreferencesHelper.unlockNotificationChannelImportance(pkg, uid, channelId);
            handleSavePolicyFile();
        }

        @Override
        public void unlockAllNotificationChannels() {
            checkCallerIsSystem();
            mPreferencesHelper.unlockAllNotificationChannels();
            handleSavePolicyFile();
        }

        @Override
        public ParceledListSlice<NotificationChannel> getNotificationChannelsForPackage(String pkg,
                int uid, boolean includeDeleted) {
            enforceSystemOrSystemUI("getNotificationChannelsForPackage");
            return mPreferencesHelper.getNotificationChannels(pkg, uid, includeDeleted);
        }

        @Override
        public int getNumNotificationChannelsForPackage(String pkg, int uid,
                boolean includeDeleted) {
            enforceSystemOrSystemUI("getNumNotificationChannelsForPackage");
            return NotificationManagerService.this
                    .getNumNotificationChannelsForPackage(pkg, uid, includeDeleted);
        }

        @Override
        public boolean onlyHasDefaultChannel(String pkg, int uid) {
            enforceSystemOrSystemUI("onlyHasDefaultChannel");
            return mPreferencesHelper.onlyHasDefaultChannel(pkg, uid);
        }

        @Override
        public int getDeletedChannelCount(String pkg, int uid) {
            enforceSystemOrSystemUI("getDeletedChannelCount");
            return mPreferencesHelper.getDeletedChannelCount(pkg, uid);
        }

        @Override
        public int getBlockedChannelCount(String pkg, int uid) {
            enforceSystemOrSystemUI("getBlockedChannelCount");
            return mPreferencesHelper.getBlockedChannelCount(pkg, uid);
        }

        @Override
        public ParceledListSlice<ConversationChannelWrapper> getConversations(
                boolean onlyImportant) {
            enforceSystemOrSystemUI("getConversations");
            IntArray userIds = mUserProfiles.getCurrentProfileIds();
            ArrayList<ConversationChannelWrapper> conversations =
                    mPreferencesHelper.getConversations(userIds, onlyImportant);
            for (ConversationChannelWrapper conversation : conversations) {
                if (mShortcutHelper == null) {
                    conversation.setShortcutInfo(null);
                } else {
                    conversation.setShortcutInfo(mShortcutHelper.getValidShortcutInfo(
                            conversation.getNotificationChannel().getConversationId(),
                            conversation.getPkg(),
                            UserHandle.of(UserHandle.getUserId(conversation.getUid()))));
                }
            }
            return new ParceledListSlice<>(conversations);
        }

        @Override
        public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroupsForPackage(
                String pkg, int uid, boolean includeDeleted) {
            enforceSystemOrSystemUI("getNotificationChannelGroupsForPackage");
            return mPreferencesHelper.getNotificationChannelGroups(
                    pkg, uid, includeDeleted, true, false, true, null);
        }

        @Override
        public ParceledListSlice<NotificationChannelGroup>
                getRecentBlockedNotificationChannelGroupsForPackage(String pkg, int uid) {
            enforceSystemOrSystemUI("getRecentBlockedNotificationChannelGroupsForPackage");
            Set<String> recentlySentChannels = new HashSet<>();
            long now = System.currentTimeMillis();
            long startTime = now - (DateUtils.DAY_IN_MILLIS * 14);
            UsageEvents events = mUsageStatsManagerInternal.queryEventsForUser(
                UserHandle.getUserId(uid),  startTime, now, UsageEvents.SHOW_ALL_EVENT_DATA);
            // get all channelids that sent notifs in the past 2 weeks
            if (events != null) {
                UsageEvents.Event event = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    if (event.getEventType() == UsageEvents.Event.NOTIFICATION_INTERRUPTION) {
                        if (pkg.equals(event.mPackage)) {
                            String channelId = event.mNotificationChannelId;
                            if (channelId != null) {
                                recentlySentChannels.add(channelId);
                            }
                        }
                    }
                }
            }

            return mPreferencesHelper.getNotificationChannelGroups(
                    pkg, uid, false, true, false, true, recentlySentChannels);
        }

        @Override
        public ParceledListSlice<ConversationChannelWrapper> getConversationsForPackage(String pkg,
                int uid) {
            enforceSystemOrSystemUI("getConversationsForPackage");
            ArrayList<ConversationChannelWrapper> conversations =
                    mPreferencesHelper.getConversations(pkg, uid);
            for (ConversationChannelWrapper conversation : conversations) {
                if (mShortcutHelper == null) {
                    conversation.setShortcutInfo(null);
                } else {
                    conversation.setShortcutInfo(mShortcutHelper.getValidShortcutInfo(
                            conversation.getNotificationChannel().getConversationId(),
                            pkg,
                            UserHandle.of(UserHandle.getUserId(uid))));
                }
            }
            return new ParceledListSlice<>(conversations);
        }

        @Override
        public NotificationChannelGroup getPopulatedNotificationChannelGroupForPackage(
                String pkg, int uid, String groupId, boolean includeDeleted) {
            enforceSystemOrSystemUI("getPopulatedNotificationChannelGroupForPackage");
            return mPreferencesHelper.getNotificationChannelGroupWithChannels(
                    pkg, uid, groupId, includeDeleted);
        }

        @Override
        public NotificationChannelGroup getNotificationChannelGroupForPackage(
                String groupId, String pkg, int uid) {
            enforceSystemOrSystemUI("getNotificationChannelGroupForPackage");
            return mPreferencesHelper.getNotificationChannelGroup(groupId, pkg, uid);
        }

        @Override
        public ParceledListSlice<NotificationChannel> getNotificationChannels(
                String callingPkg, String targetPkg, int userId) {
            if (canNotifyAsPackage(callingPkg, targetPkg, userId)
                || isCallingUidSystem()) {
                int targetUid = -1;
                try {
                    targetUid = mPackageManagerClient.getPackageUidAsUser(targetPkg, userId);
                } catch (NameNotFoundException e) {
                    /* ignore */
                }
                return mPreferencesHelper.getNotificationChannels(
                        targetPkg, targetUid, false /* includeDeleted */);
            }
            throw new SecurityException("Pkg " + callingPkg
                    + " cannot read channels for " + targetPkg + " in " + userId);
        }

        @Override
        public ParceledListSlice<NotificationChannel> getNotificationChannelsBypassingDnd(
                String pkg, int uid) {
            checkCallerIsSystem();
            if (!areNotificationsEnabledForPackage(pkg, uid)) {
                return ParceledListSlice.emptyList();
            }
            return mPreferencesHelper.getNotificationChannelsBypassingDnd(pkg, uid);
        }

        @Override
        public boolean areChannelsBypassingDnd() {
            return mPreferencesHelper.areChannelsBypassingDnd();
        }

        @Override
        public void clearData(String packageName, int uid, boolean fromApp) throws RemoteException {
            boolean packagesChanged = false;
            checkCallerIsSystem();
            // Cancel posted notifications
            final int userId = UserHandle.getUserId(uid);
            cancelAllNotificationsInt(MY_UID, MY_PID, packageName, null, 0, 0,
                    UserHandle.getUserId(Binder.getCallingUid()), REASON_CLEAR_DATA);

            // Zen
            packagesChanged |=
                    mConditionProviders.resetPackage(packageName, userId);

            // Listener
            ArrayMap<Boolean, ArrayList<ComponentName>> changedListeners =
                    mListeners.resetComponents(packageName, userId);
            packagesChanged |= changedListeners.get(true).size() > 0
                    || changedListeners.get(false).size() > 0;

            // When a listener is enabled, we enable the dnd package as a secondary
            for (int i = 0; i < changedListeners.get(true).size(); i++) {
                mConditionProviders.setPackageOrComponentEnabled(
                        changedListeners.get(true).get(i).getPackageName(),
                        userId, false, true);
            }

            // Assistant
            ArrayMap<Boolean, ArrayList<ComponentName>> changedAssistants =
                    mAssistants.resetComponents(packageName, userId);
            packagesChanged |= changedAssistants.get(true).size() > 0
                    || changedAssistants.get(false).size() > 0;

            // we want only one assistant enabled
            for (int i = 1; i < changedAssistants.get(true).size(); i++) {
                mAssistants.setPackageOrComponentEnabled(
                        changedAssistants.get(true).get(i).flattenToString(),
                        userId, true, false);
            }

            // When the default assistant is enabled, we enable the dnd package as a secondary
            if (changedAssistants.get(true).size() > 0) {
                //we want only one assistant active
                mConditionProviders
                        .setPackageOrComponentEnabled(
                                changedAssistants.get(true).get(0).getPackageName(),
                                userId, false, true);

            }

            // Snoozing
            mSnoozeHelper.clearData(UserHandle.getUserId(uid), packageName);

            // Reset notification preferences
            if (!fromApp) {
                mPreferencesHelper.clearData(packageName, uid);
            }

            if (packagesChanged) {
                getContext().sendBroadcastAsUser(new Intent(
                                ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
                                .setPackage(packageName)
                                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                        UserHandle.of(userId), null);
            }

            handleSavePolicyFile();
        }

        @Override
        public List<String> getAllowedAssistantAdjustments(String pkg) {
            checkCallerIsSystemOrSameApp(pkg);

            if (!isCallerSystemOrPhone()
                    && !mAssistants.isPackageAllowed(pkg, UserHandle.getCallingUserId())) {
                    throw new SecurityException("Not currently an assistant");
            }

            return mAssistants.getAllowedAssistantAdjustments();
        }

        /**
         * @deprecated Use {@link #getActiveNotificationsWithAttribution(String, String)} instead.
         */
        @Deprecated
        @Override
        public StatusBarNotification[] getActiveNotifications(String callingPkg) {
            return getActiveNotificationsWithAttribution(callingPkg, null);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_NOTIFICATIONS)
        /**
         * System-only API for getting a list of current (i.e. not cleared) notifications.
         *
         * Requires ACCESS_NOTIFICATIONS which is signature|system.
         * @returns A list of all the notifications, in natural order.
         */
        @Override
        public StatusBarNotification[] getActiveNotificationsWithAttribution(String callingPkg,
                String callingAttributionTag) {
            // enforce() will ensure the calling uid has the correct permission
            getActiveNotificationsWithAttribution_enforcePermission();

            ArrayList<StatusBarNotification> tmp = new ArrayList<>();
            int uid = Binder.getCallingUid();

            ArrayList<Integer> currentUsers = new ArrayList<>();
            currentUsers.add(UserHandle.USER_ALL);
            Binder.withCleanCallingIdentity(() -> {
                for (int user : mUm.getProfileIds(ActivityManager.getCurrentUser(), false)) {
                    currentUsers.add(user);
                }
            });

            // noteOp will check to make sure the callingPkg matches the uid
            if (mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, uid, callingPkg,
                    callingAttributionTag, null)
                    == MODE_ALLOWED) {
                synchronized (mNotificationLock) {
                    final int N = mNotificationList.size();
                    for (int i = 0; i < N; i++) {
                        final StatusBarNotification sbn = mNotificationList.get(i).getSbn();
                        if (currentUsers.contains(sbn.getUserId())) {
                            tmp.add(sbn);
                        }
                    }
                }
            }
            return tmp.toArray(new StatusBarNotification[tmp.size()]);
        }

        /**
         * Public API for getting a list of current notifications for the calling package/uid.
         *
         * Note that since notification posting is done asynchronously, this will not return
         * notifications that are in the process of being posted.
         *
         * From {@link Build.VERSION_CODES#Q}, will also return notifications you've posted as
         * an app's notification delegate via
         * {@link NotificationManager#notifyAsPackage(String, String, int, Notification)}.
         *
         * @returns A list of all the package's notifications, in natural order.
         */
        @Override
        public ParceledListSlice<StatusBarNotification> getAppActiveNotifications(String pkg,
                int incomingUserId) {
            checkCallerIsSystemOrSameApp(pkg);
            int userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), incomingUserId, true, false,
                    "getAppActiveNotifications", pkg);
            synchronized (mNotificationLock) {
                final ArrayMap<String, StatusBarNotification> map
                        = new ArrayMap<>(mNotificationList.size() + mEnqueuedNotifications.size());
                final int N = mNotificationList.size();
                for (int i = 0; i < N; i++) {
                    StatusBarNotification sbn = sanitizeSbn(pkg, userId,
                            mNotificationList.get(i).getSbn());
                    if (sbn != null) {
                        map.put(sbn.getKey(), sbn);
                    }
                }
                for(NotificationRecord snoozed: mSnoozeHelper.getSnoozed(userId, pkg)) {
                    StatusBarNotification sbn = sanitizeSbn(pkg, userId, snoozed.getSbn());
                    if (sbn != null) {
                        map.put(sbn.getKey(), sbn);
                    }
                }
                final int M = mEnqueuedNotifications.size();
                for (int i = 0; i < M; i++) {
                    StatusBarNotification sbn = sanitizeSbn(pkg, userId,
                            mEnqueuedNotifications.get(i).getSbn());
                    if (sbn != null) {
                        map.put(sbn.getKey(), sbn); // pending update overwrites existing post here
                    }
                }
                final ArrayList<StatusBarNotification> list = new ArrayList<>(map.size());
                list.addAll(map.values());
                return new ParceledListSlice<StatusBarNotification>(list);
            }
        }

        /** Notifications returned here will have allowlistToken stripped from them. */
        private StatusBarNotification sanitizeSbn(String pkg, int userId,
                StatusBarNotification sbn) {
            if (sbn.getUserId() == userId) {
                if (sbn.getPackageName().equals(pkg) || sbn.getOpPkg().equals(pkg)) {
                    // We could pass back a cloneLight() but clients might get confused and
                    // try to send this thing back to notify() again, which would not work
                    // very well.
                    Notification notification = sbn.getNotification().clone();
                    // Remove background token before returning notification to untrusted app, this
                    // ensures the app isn't able to perform background operations that are
                    // associated with notification interactions.
                    notification.clearAllowlistToken();
                    return new StatusBarNotification(
                            sbn.getPackageName(),
                            sbn.getOpPkg(),
                            sbn.getId(), sbn.getTag(), sbn.getUid(), sbn.getInitialPid(),
                            notification,
                            sbn.getUser(), sbn.getOverrideGroupKey(), sbn.getPostTime());
                }
            }
            return null;
        }

        /**
         * @deprecated Use {@link #getHistoricalNotificationsWithAttribution} instead.
         */
        @Deprecated
        @Override
        @RequiresPermission(android.Manifest.permission.ACCESS_NOTIFICATIONS)
        public StatusBarNotification[] getHistoricalNotifications(String callingPkg, int count,
                boolean includeSnoozed) {
            return getHistoricalNotificationsWithAttribution(callingPkg, null, count,
                    includeSnoozed);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_NOTIFICATIONS)
        /**
         * System-only API for getting a list of recent (cleared, no longer shown) notifications.
         */
        @Override
        @RequiresPermission(android.Manifest.permission.ACCESS_NOTIFICATIONS)
        public StatusBarNotification[] getHistoricalNotificationsWithAttribution(String callingPkg,
                String callingAttributionTag, int count, boolean includeSnoozed) {
            // enforce() will ensure the calling uid has the correct permission
            getHistoricalNotificationsWithAttribution_enforcePermission();

            StatusBarNotification[] tmp = null;
            int uid = Binder.getCallingUid();

            // noteOp will check to make sure the callingPkg matches the uid
            if (mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, uid, callingPkg,
                    callingAttributionTag, null)
                    == MODE_ALLOWED) {
                synchronized (mArchive) {
                    tmp = mArchive.getArray(mUm, count, includeSnoozed);
                }
            }
            return tmp;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_NOTIFICATIONS)
        /**
         * System-only API for getting a list of historical notifications. May contain multiple days
         * of notifications.
         */
        @Override
        @WorkerThread
        @RequiresPermission(android.Manifest.permission.ACCESS_NOTIFICATIONS)
        public NotificationHistory getNotificationHistory(String callingPkg,
                String callingAttributionTag) {
            // enforce() will ensure the calling uid has the correct permission
            getNotificationHistory_enforcePermission();
            int uid = Binder.getCallingUid();

            // noteOp will check to make sure the callingPkg matches the uid
            if (mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, uid, callingPkg,
                    callingAttributionTag, null)
                    == MODE_ALLOWED) {
                IntArray currentUserIds = mUserProfiles.getCurrentProfileIds();
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "notifHistoryReadHistory");
                try {
                    return mHistoryManager.readNotificationHistory(currentUserIds.toArray());
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                }
            }
            return new NotificationHistory();
        }

        /**
         * Register a listener binder directly with the notification manager.
         *
         * Only works with system callers. Apps should extend
         * {@link android.service.notification.NotificationListenerService}.
         */
        @Override
        public void registerListener(final INotificationListener listener,
                final ComponentName component, final int userid) {
            enforceSystemOrSystemUI("INotificationManager.registerListener");
            mListeners.registerSystemService(listener, component, userid, Binder.getCallingUid());
        }

        /**
         * Remove a listener binder directly
         */
        @Override
        public void unregisterListener(INotificationListener token, int userid) {
            mListeners.unregisterService(token, userid);
        }

        /**
         * Allow an INotificationListener to simulate a "clear all" operation.
         *
         * {@see com.android.server.StatusBarManagerService.NotificationCallbacks#onClearAllNotifications}
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void cancelNotificationsFromListener(INotificationListener token, String[] keys) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);

                    // Cancellation reason. If the token comes from assistant, label the
                    // cancellation as coming from the assistant; default to LISTENER_CANCEL.
                    int reason = REASON_LISTENER_CANCEL;
                    if (mAssistants.isServiceTokenValidLocked(token)) {
                        reason = REASON_ASSISTANT_CANCEL;
                    }

                    if (keys != null) {
                        final int N = keys.length;
                        for (int i = 0; i < N; i++) {
                            NotificationRecord r = mNotificationsByKey.get(keys[i]);
                            if (r == null) continue;
                            final int userId = r.getSbn().getUserId();
                            if (userId != info.userid && userId != UserHandle.USER_ALL &&
                                    !mUserProfiles.isCurrentProfile(userId)) {
                                continue;
                            }
                            cancelNotificationFromListenerLocked(info, callingUid, callingPid,
                                    r.getSbn().getPackageName(), r.getSbn().getTag(),
                                    r.getSbn().getId(), userId, reason);
                        }
                    } else {
                        cancelAllLocked(callingUid, callingPid, info.userid,
                                REASON_LISTENER_CANCEL_ALL, info, info.supportsProfiles());
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Handle request from an approved listener to re-enable itself.
         *
         * @param component The componenet to be re-enabled, caller must match package.
         */
        @Override
        public void requestBindListener(ComponentName component) {
            checkCallerIsSystemOrSameApp(component.getPackageName());
            int uid = Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                ManagedServices manager =
                        mAssistants.isComponentEnabledForCurrentProfiles(component)
                        ? mAssistants
                        : mListeners;
                manager.setComponentState(component, UserHandle.getUserId(uid), true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestUnbindListener(INotificationListener token) {
            int uid = Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                // allow bound services to disable themselves
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    info.getOwner().setComponentState(
                            info.component, UserHandle.getUserId(uid), false);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestUnbindListenerComponent(ComponentName component) {
            checkCallerIsSameApp(component.getPackageName());
            int uid = Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    ManagedServices manager =
                            mAssistants.isComponentEnabledForCurrentProfiles(component)
                                    ? mAssistants
                                    : mListeners;
                    if (manager.isPackageOrComponentAllowed(component.flattenToString(),
                            UserHandle.getUserId(uid))) {
                        manager.setComponentState(component, UserHandle.getUserId(uid), false);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setNotificationsShownFromListener(INotificationListener token, String[] keys) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    if (keys == null) {
                        return;
                    }
                    ArrayList<NotificationRecord> seen = new ArrayList<>();
                    final int n = keys.length;
                    for (int i = 0; i < n; i++) {
                        NotificationRecord r = mNotificationsByKey.get(keys[i]);
                        if (r == null) continue;
                        final int userId = r.getSbn().getUserId();
                        if (userId != info.userid && userId != UserHandle.USER_ALL
                                && !mUserProfiles.isCurrentProfile(userId)) {
                            continue;
                        }
                        seen.add(r);
                        if (!r.isSeen()) {
                            if (DBG) Slog.d(TAG, "Marking notification as seen " + keys[i]);
                            reportSeen(r);
                            r.setSeen();
                            maybeRecordInterruptionLocked(r);
                        }
                    }
                    if (!seen.isEmpty()) {
                        mAssistants.onNotificationsSeenLocked(seen);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allow an INotificationListener to simulate clearing (dismissing) a single notification.
         *
         * {@see com.android.server.StatusBarManagerService.NotificationCallbacks#onNotificationClear}
         *
         * @param info The binder for the listener, to check that the caller is allowed
         */
        @GuardedBy("mNotificationLock")
        private void cancelNotificationFromListenerLocked(ManagedServiceInfo info,
                int callingUid, int callingPid, String pkg, String tag, int id, int userId,
                int reason) {
            int mustNotHaveFlags = FLAG_ONGOING_EVENT;
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0 /* mustHaveFlags */,
                    mustNotHaveFlags,
                    true,
                    userId, reason, info);
        }

        /**
         * Allow an INotificationListener to snooze a single notification until a context.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void snoozeNotificationUntilContextFromListener(INotificationListener token,
                String key, String snoozeCriterionId) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    snoozeNotificationInt(key, SNOOZE_UNTIL_UNSPECIFIED, snoozeCriterionId, info);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allow an INotificationListener to snooze a single notification until a time.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void snoozeNotificationUntilFromListener(INotificationListener token, String key,
                long duration) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    snoozeNotificationInt(key, duration, null, info);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allows the notification assistant to un-snooze a single notification.
         *
         * @param token The binder for the assistant, to check that the caller is allowed
         */
        @Override
        public void unsnoozeNotificationFromAssistant(INotificationListener token, String key) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info =
                            mAssistants.checkServiceTokenLocked(token);
                    unsnoozeNotificationInt(key, info, false);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allows the notification assistant to un-snooze a single notification.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void unsnoozeNotificationFromSystemListener(INotificationListener token,
                String key) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info =
                            mListeners.checkServiceTokenLocked(token);
                    if (!info.isSystem) {
                        throw new SecurityException("Not allowed to unsnooze before deadline");
                    }
                    unsnoozeNotificationInt(key, info, true);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allows an app to set an initial notification listener filter
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void migrateNotificationFilter(INotificationListener token, int defaultTypes,
                List<String> disallowedApps) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);

                    Pair key = Pair.create(info.component, info.userid);

                    NotificationListenerFilter nlf = mListeners.getNotificationListenerFilter(key);
                    if (nlf == null) {
                        nlf = new NotificationListenerFilter();
                    }
                    if (nlf.getDisallowedPackages().isEmpty() && disallowedApps != null) {
                        for (String pkg : disallowedApps) {
                            // block the current user's version and any work profile versions
                            for (int userId : mUm.getProfileIds(info.userid, false)) {
                                try {
                                    int uid = getUidForPackageAndUser(pkg, UserHandle.of(userId));
                                    VersionedPackage vp = new VersionedPackage(pkg, uid);
                                    nlf.addPackage(vp);
                                } catch (Exception e) {
                                    // pkg doesn't exist on that user; skip
                                }
                            }
                        }
                    }
                    if (nlf.areAllTypesAllowed()) {
                        nlf.setTypes(defaultTypes);
                    }
                    mListeners.setNotificationListenerFilter(key, nlf);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allow an INotificationListener to simulate clearing (dismissing) a single notification.
         *
         * {@see com.android.server.StatusBarManagerService.NotificationCallbacks#onNotificationClear}
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void cancelNotificationFromListener(INotificationListener token, String pkg,
                String tag, int id) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    int cancelReason = REASON_LISTENER_CANCEL;
                    if (mAssistants.isServiceTokenValidLocked(token)) {
                        cancelReason = REASON_ASSISTANT_CANCEL;
                    }
                    if (info.supportsProfiles()) {
                        Slog.e(TAG, "Ignoring deprecated cancelNotification(pkg, tag, id) "
                                + "from " + info.component
                                + " use cancelNotification(key) instead.");
                    } else {
                        cancelNotificationFromListenerLocked(info, callingUid, callingPid,
                                pkg, tag, id, info.userid, cancelReason);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allow an INotificationListener to request the list of outstanding notifications seen by
         * the current user. Useful when starting up, after which point the listener callbacks
         * should be used.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         * @param keys An array of notification keys to fetch, or null to fetch everything
         * @returns The return value will contain the notifications specified in keys, in that
         *      order, or if keys is null, all the notifications, in natural order.
         */
        @Override
        public ParceledListSlice<StatusBarNotification> getActiveNotificationsFromListener(
                INotificationListener token, String[] keys, int trim) {
            synchronized (mNotificationLock) {
                final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                final boolean getKeys = keys != null;
                final int N = getKeys ? keys.length : mNotificationList.size();
                final ArrayList<StatusBarNotification> list
                        = new ArrayList<StatusBarNotification>(N);
                for (int i=0; i<N; i++) {
                    final NotificationRecord r = getKeys
                            ? mNotificationsByKey.get(keys[i])
                            : mNotificationList.get(i);
                    if (r == null) continue;
                    StatusBarNotification sbn = r.getSbn();
                    if (!isVisibleToListener(sbn, r.getNotificationType(), info)) continue;
                    StatusBarNotification sbnToSend =
                            (trim == TRIM_FULL) ? sbn : sbn.cloneLight();
                    list.add(sbnToSend);
                }
                return new ParceledListSlice<StatusBarNotification>(list);
            }
        }

        /**
         * Allow an INotificationListener to request the list of outstanding snoozed notifications
         * seen by the current user. Useful when starting up, after which point the listener
         * callbacks should be used.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         * @returns The return value will contain the notifications specified in keys, in that
         *      order, or if keys is null, all the notifications, in natural order.
         */
        @Override
        public ParceledListSlice<StatusBarNotification> getSnoozedNotificationsFromListener(
                INotificationListener token, int trim) {
            synchronized (mNotificationLock) {
                final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                List<NotificationRecord> snoozedRecords = mSnoozeHelper.getSnoozed();
                final int N = snoozedRecords.size();
                final ArrayList<StatusBarNotification> list = new ArrayList<>(N);
                for (int i=0; i < N; i++) {
                    final NotificationRecord r = snoozedRecords.get(i);
                    if (r == null) continue;
                    StatusBarNotification sbn = r.getSbn();
                    if (!isVisibleToListener(sbn, r.getNotificationType(), info)) continue;
                    StatusBarNotification sbnToSend =
                            (trim == TRIM_FULL) ? sbn : sbn.cloneLight();
                    list.add(sbnToSend);
                }
                return new ParceledListSlice<>(list);
            }
        }

        @Override
        public void clearRequestedListenerHints(INotificationListener token) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    removeDisabledHints(info);
                    updateListenerHintsLocked();
                    updateEffectsSuppressorLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestHintsFromListener(INotificationListener token, int hints) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    final int disableEffectsMask = HINT_HOST_DISABLE_EFFECTS
                            | HINT_HOST_DISABLE_NOTIFICATION_EFFECTS
                            | HINT_HOST_DISABLE_CALL_EFFECTS;
                    final boolean disableEffects = (hints & disableEffectsMask) != 0;
                    if (disableEffects) {
                        addDisabledHints(info, hints);
                    } else {
                        removeDisabledHints(info, hints);
                    }
                    updateListenerHintsLocked();
                    updateEffectsSuppressorLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public int getHintsFromListener(INotificationListener token) {
            synchronized (mNotificationLock) {
                return mListenerHints;
            }
        }

        @Override
        public int getHintsFromListenerNoToken() {
            synchronized (mNotificationLock) {
                return mListenerHints;
            }
        }

        @Override
        public void requestInterruptionFilterFromListener(INotificationListener token,
                int interruptionFilter) throws RemoteException {
            final int callingUid = Binder.getCallingUid();
            final boolean isSystemOrSystemUi = isCallerIsSystemOrSystemUi();
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    mZenModeHelper.requestFromListener(info.component, interruptionFilter,
                            callingUid, isSystemOrSystemUi);
                    updateInterruptionFilterLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public int getInterruptionFilterFromListener(INotificationListener token)
                throws RemoteException {
            synchronized (mNotificationLock) {
                return mInterruptionFilter;
            }
        }

        @Override
        public void setOnNotificationPostedTrimFromListener(INotificationListener token, int trim)
                throws RemoteException {
            synchronized (mNotificationLock) {
                final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                if (info == null) return;
                mListeners.setOnNotificationPostedTrimLocked(info, trim);
            }
        }

        @Override
        public int getZenMode() {
            return mZenModeHelper.getZenMode();
        }

        @Override
        public ZenModeConfig getZenModeConfig() {
            enforceSystemOrSystemUI("INotificationManager.getZenModeConfig");
            return mZenModeHelper.getConfig();
        }

        @Override
        public void setZenMode(int mode, Uri conditionId, String reason) throws RemoteException {
            enforceSystemOrSystemUI("INotificationManager.setZenMode");
            final int callingUid = Binder.getCallingUid();
            final boolean isSystemOrSystemUi = isCallerIsSystemOrSystemUi();
            final long identity = Binder.clearCallingIdentity();
            try {
                mZenModeHelper.setManualZenMode(mode, conditionId, null, reason, callingUid,
                        isSystemOrSystemUi);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<ZenModeConfig.ZenRule> getZenRules() throws RemoteException {
            enforcePolicyAccess(Binder.getCallingUid(), "getAutomaticZenRules");
            return mZenModeHelper.getZenRules();
        }

        @Override
        public AutomaticZenRule getAutomaticZenRule(String id) throws RemoteException {
            Objects.requireNonNull(id, "Id is null");
            enforcePolicyAccess(Binder.getCallingUid(), "getAutomaticZenRule");
            return mZenModeHelper.getAutomaticZenRule(id);
        }

        @Override
        public String addAutomaticZenRule(AutomaticZenRule automaticZenRule, String pkg) {
            Objects.requireNonNull(automaticZenRule, "automaticZenRule is null");
            Objects.requireNonNull(automaticZenRule.getName(), "Name is null");
            if (automaticZenRule.getOwner() == null
                    && automaticZenRule.getConfigurationActivity() == null) {
                throw new NullPointerException(
                        "Rule must have a conditionproviderservice and/or configuration activity");
            }
            Objects.requireNonNull(automaticZenRule.getConditionId(), "ConditionId is null");
            checkCallerIsSameApp(pkg);
            if (automaticZenRule.getZenPolicy() != null
                    && automaticZenRule.getInterruptionFilter() != INTERRUPTION_FILTER_PRIORITY) {
                throw new IllegalArgumentException("ZenPolicy is only applicable to "
                        + "INTERRUPTION_FILTER_PRIORITY filters");
            }
            enforcePolicyAccess(Binder.getCallingUid(), "addAutomaticZenRule");

            // If the calling app is the system (from any user), take the package name from the
            // rule's owner rather than from the caller's package.
            String rulePkg = pkg;
            if (isCallingAppIdSystem()) {
                if (automaticZenRule.getOwner() != null) {
                    rulePkg = automaticZenRule.getOwner().getPackageName();
                }
            }

            return mZenModeHelper.addAutomaticZenRule(rulePkg, automaticZenRule,
                    "addAutomaticZenRule", Binder.getCallingUid(),
                    isCallerIsSystemOrSystemUi());
        }

        @Override
        public boolean updateAutomaticZenRule(String id, AutomaticZenRule automaticZenRule)
                throws RemoteException {
            Objects.requireNonNull(automaticZenRule, "automaticZenRule is null");
            Objects.requireNonNull(automaticZenRule.getName(), "Name is null");
            if (automaticZenRule.getOwner() == null
                    && automaticZenRule.getConfigurationActivity() == null) {
                throw new NullPointerException(
                        "Rule must have a conditionproviderservice and/or configuration activity");
            }
            Objects.requireNonNull(automaticZenRule.getConditionId(), "ConditionId is null");
            enforcePolicyAccess(Binder.getCallingUid(), "updateAutomaticZenRule");

            return mZenModeHelper.updateAutomaticZenRule(id, automaticZenRule,
                    "updateAutomaticZenRule", Binder.getCallingUid(),
                    isCallerIsSystemOrSystemUi());
        }

        @Override
        public boolean removeAutomaticZenRule(String id) throws RemoteException {
            Objects.requireNonNull(id, "Id is null");
            // Verify that they can modify zen rules.
            enforcePolicyAccess(Binder.getCallingUid(), "removeAutomaticZenRule");

            return mZenModeHelper.removeAutomaticZenRule(id, "removeAutomaticZenRule",
                    Binder.getCallingUid(), isCallerIsSystemOrSystemUi());
        }

        @Override
        public boolean removeAutomaticZenRules(String packageName) throws RemoteException {
            Objects.requireNonNull(packageName, "Package name is null");
            enforceSystemOrSystemUI("removeAutomaticZenRules");

            return mZenModeHelper.removeAutomaticZenRules(packageName,
                    packageName + "|removeAutomaticZenRules", Binder.getCallingUid(),
                    isCallerIsSystemOrSystemUi());
        }

        @Override
        public int getRuleInstanceCount(ComponentName owner) throws RemoteException {
            Objects.requireNonNull(owner, "Owner is null");
            enforceSystemOrSystemUI("getRuleInstanceCount");

            return mZenModeHelper.getCurrentInstanceCount(owner);
        }

        @Override
        public void setAutomaticZenRuleState(String id, Condition condition) {
            Objects.requireNonNull(id, "id is null");
            Objects.requireNonNull(condition, "Condition is null");

            enforcePolicyAccess(Binder.getCallingUid(), "setAutomaticZenRuleState");

            mZenModeHelper.setAutomaticZenRuleState(id, condition, Binder.getCallingUid(),
                    isCallerIsSystemOrSystemUi());
        }

        @Override
        public void setInterruptionFilter(String pkg, int filter) throws RemoteException {
            enforcePolicyAccess(pkg, "setInterruptionFilter");
            final int zen = NotificationManager.zenModeFromInterruptionFilter(filter, -1);
            if (zen == -1) throw new IllegalArgumentException("Invalid filter: " + filter);
            final int callingUid = Binder.getCallingUid();
            final boolean isSystemOrSystemUi = isCallerIsSystemOrSystemUi();

            if (android.app.Flags.modesApi() && !canManageGlobalZenPolicy(pkg, callingUid)) {
                mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(pkg, callingUid, zen);
                return;
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                mZenModeHelper.setManualZenMode(zen, null, pkg, "setInterruptionFilter",
                        callingUid, isSystemOrSystemUi);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyConditions(final String pkg, IConditionProvider provider,
                final Condition[] conditions) {
            final ManagedServiceInfo info = mConditionProviders.checkServiceToken(provider);
            checkCallerIsSystemOrSameApp(pkg);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mConditionProviders.notifyConditions(pkg, info, conditions);
                }
            });
        }

        @Override
        public void requestUnbindProvider(IConditionProvider provider) {
            int uid = Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                // allow bound services to disable themselves
                final ManagedServiceInfo info = mConditionProviders.checkServiceToken(provider);
                info.getOwner().setComponentState(info.component, UserHandle.getUserId(uid), false);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestBindProvider(ComponentName component) {
            checkCallerIsSystemOrSameApp(component.getPackageName());
            int uid = Binder.getCallingUid();
            final long identity = Binder.clearCallingIdentity();
            try {
                mConditionProviders.setComponentState(component, UserHandle.getUserId(uid), true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void enforceSystemOrSystemUI(String message) {
            if (isCallerSystemOrPhone()) return;
            getContext().enforceCallingPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                    message);
        }

        private void enforceSystemOrSystemUIOrSamePackage(String pkg, String message) {
            try {
                checkCallerIsSystemOrSameApp(pkg);
            } catch (SecurityException e) {
                getContext().enforceCallingPermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE,
                        message);
            }
        }

        private void enforcePolicyAccess(int uid, String method) {
            if (PackageManager.PERMISSION_GRANTED == getContext().checkCallingPermission(
                    android.Manifest.permission.MANAGE_NOTIFICATIONS)) {
                return;
            }
            boolean accessAllowed = false;
            String[] packages = mPackageManagerClient.getPackagesForUid(uid);
            final int packageCount = packages.length;
            for (int i = 0; i < packageCount; i++) {
                if (mConditionProviders.isPackageOrComponentAllowed(
                        packages[i], UserHandle.getUserId(uid))) {
                    accessAllowed = true;
                }
            }
            if (!accessAllowed) {
                Slog.w(TAG, "Notification policy access denied calling " + method);
                throw new SecurityException("Notification policy access denied");
            }
        }

        private boolean canManageGlobalZenPolicy(String callingPkg, int callingUid) {
            boolean isCompatChangeEnabled = Binder.withCleanCallingIdentity(
                    () -> CompatChanges.isChangeEnabled(MANAGE_GLOBAL_ZEN_VIA_IMPLICIT_RULES,
                            callingUid));
            return !isCompatChangeEnabled
                    || isCallerIsSystemOrSystemUi()
                    || hasCompanionDevice(callingPkg, UserHandle.getUserId(callingUid),
                            AssociationRequest.DEVICE_PROFILE_WATCH);
        }

        private void enforcePolicyAccess(String pkg, String method) {
            if (PackageManager.PERMISSION_GRANTED == getContext().checkCallingPermission(
                    android.Manifest.permission.MANAGE_NOTIFICATIONS)) {
                return;
            }
            checkCallerIsSameApp(pkg);
            if (!checkPolicyAccess(pkg)) {
                Slog.w(TAG, "Notification policy access denied calling " + method);
                throw new SecurityException("Notification policy access denied");
            }
        }

        private boolean checkPackagePolicyAccess(String pkg) {
            return mConditionProviders.isPackageOrComponentAllowed(
                    pkg, getCallingUserHandle().getIdentifier());
        }

        private boolean checkPolicyAccess(String pkg) {
            final int uid;
            try {
                uid = getContext().getPackageManager().getPackageUidAsUser(pkg,
                        UserHandle.getCallingUserId());
                if (PackageManager.PERMISSION_GRANTED == checkComponentPermission(
                        android.Manifest.permission.MANAGE_NOTIFICATIONS, uid,
                        -1, true)) {
                    return true;
                }
            } catch (NameNotFoundException e) {
                return false;
            }
            //TODO(b/169395065) Figure out if this flow makes sense in Device Owner mode.
            return checkPackagePolicyAccess(pkg)
                    || mListeners.isComponentEnabledForPackage(pkg)
                    || (mDpm != null && (mDpm.isActiveProfileOwner(uid)
                                || mDpm.isActiveDeviceOwner(uid)));
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, pw)) return;
            final DumpFilter filter = DumpFilter.parseFromArguments(args);
            final long token = Binder.clearCallingIdentity();
            try {
                final ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions =
                        getAllUsersNotificationPermissions();
                if (filter.stats) {
                    dumpJson(pw, filter, pkgPermissions);
                } else if (filter.rvStats) {
                    dumpRemoteViewStats(pw, filter);
                } else if (filter.proto) {
                    dumpProto(fd, filter, pkgPermissions);
                } else if (filter.criticalPriority) {
                    dumpNotificationRecords(pw, filter);
                } else {
                    dumpImpl(pw, filter, pkgPermissions);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public ComponentName getEffectsSuppressor() {
            return !mEffectsSuppressors.isEmpty() ? mEffectsSuppressors.get(0) : null;
        }

        @Override
        public boolean matchesCallFilter(Bundle extras) {
            // Because matchesCallFilter may use contact data to filter calls, the callers of this
            // method need to either have notification listener access or permission to read
            // contacts.
            boolean systemAccess = false;
            try {
                enforceSystemOrSystemUI("INotificationManager.matchesCallFilter");
                systemAccess = true;
            } catch (SecurityException e) {
            }

            boolean listenerAccess = false;
            try {
                String[] pkgNames = mPackageManager.getPackagesForUid(Binder.getCallingUid());
                for (int i = 0; i < pkgNames.length; i++) {
                    // in most cases there should only be one package here
                    listenerAccess |= mListeners.hasAllowedListener(pkgNames[i],
                            Binder.getCallingUserHandle().getIdentifier());
                }
            } catch (RemoteException e) {
            } finally {
                if (!systemAccess && !listenerAccess) {
                    getContext().enforceCallingPermission(Manifest.permission.READ_CONTACTS,
                            "matchesCallFilter requires listener permission, contacts read access,"
                            + " or system level access");
                }
            }

            return mZenModeHelper.matchesCallFilter(
                    Binder.getCallingUserHandle(),
                    extras,
                    mRankingHelper.findExtractor(ValidateNotificationPeople.class),
                    MATCHES_CALL_FILTER_CONTACTS_TIMEOUT_MS,
                    MATCHES_CALL_FILTER_TIMEOUT_AFFINITY,
                    Binder.getCallingUid());
        }

        @Override
        public void cleanUpCallersAfter(long timeThreshold) {
            enforceSystemOrSystemUI("INotificationManager.cleanUpCallersAfter");
            mZenModeHelper.cleanUpCallersAfter(timeThreshold);
        }

        @Override
        public boolean isSystemConditionProviderEnabled(String path) {
            enforceSystemOrSystemUI("INotificationManager.isSystemConditionProviderEnabled");
            return mConditionProviders.isSystemProviderEnabled(path);
        }

        // Backup/restore interface
        @Override
        public byte[] getBackupPayload(int user) {
            checkCallerIsSystem();
            if (DBG) Slog.d(TAG, "getBackupPayload u=" + user);
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                writePolicyXml(baos, true /*forBackup*/, user);
                return baos.toByteArray();
            } catch (IOException e) {
                Slog.w(TAG, "getBackupPayload: error writing payload for user " + user, e);
            }
            return null;
        }

        @Override
        public void applyRestore(byte[] payload, int user) {
            checkCallerIsSystem();
            if (DBG) Slog.d(TAG, "applyRestore u=" + user + " payload="
                    + (payload != null ? new String(payload, StandardCharsets.UTF_8) : null));
            if (payload == null) {
                Slog.w(TAG, "applyRestore: no payload to restore for user " + user);
                return;
            }
            final ByteArrayInputStream bais = new ByteArrayInputStream(payload);
            try {
                readPolicyXml(bais, true /*forRestore*/, user);
                handleSavePolicyFile();
            } catch (NumberFormatException | XmlPullParserException | IOException e) {
                Slog.w(TAG, "applyRestore: error reading payload", e);
            }
        }

        @Override
        public boolean isNotificationPolicyAccessGranted(String pkg) {
            return checkPolicyAccess(pkg);
        }

        @Override
        public boolean isNotificationPolicyAccessGrantedForPackage(String pkg) {
            enforceSystemOrSystemUIOrSamePackage(pkg,
                    "request policy access status for another package");
            return checkPolicyAccess(pkg);
        }

        @Override
        public void setNotificationPolicyAccessGranted(String pkg, boolean granted)
                throws RemoteException {
            setNotificationPolicyAccessGrantedForUser(
                    pkg, getCallingUserHandle().getIdentifier(), granted);
        }

        @Override
        public void setNotificationPolicyAccessGrantedForUser(
                String pkg, int userId, boolean granted) {
            checkCallerIsSystemOrShell();
            final long identity = Binder.clearCallingIdentity();
            try {
                if (mAllowedManagedServicePackages.test(
                        pkg, userId, mConditionProviders.getRequiredPermission())) {
                    mConditionProviders.setPackageOrComponentEnabled(
                            pkg, userId, true, granted);

                    getContext().sendBroadcastAsUser(new Intent(
                            ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
                                    .setPackage(pkg)
                                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                            UserHandle.of(userId), null);
                    handleSavePolicyFile();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public Policy getNotificationPolicy(String pkg) {
            final int callingUid = Binder.getCallingUid();
            if (android.app.Flags.modesApi() && !canManageGlobalZenPolicy(pkg, callingUid)) {
                return mZenModeHelper.getNotificationPolicyFromImplicitZenRule(pkg);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return mZenModeHelper.getNotificationPolicy();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public Policy getConsolidatedNotificationPolicy() {
            final long identity = Binder.clearCallingIdentity();
            try {
                return mZenModeHelper.getConsolidatedNotificationPolicy();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Sets the notification policy.  Apps that target API levels below
         * {@link android.os.Build.VERSION_CODES#P} cannot change user-designated values to
         * allow or disallow {@link Policy#PRIORITY_CATEGORY_ALARMS},
         * {@link Policy#PRIORITY_CATEGORY_SYSTEM} and
         * {@link Policy#PRIORITY_CATEGORY_MEDIA} from bypassing dnd
         */
        @Override
        public void setNotificationPolicy(String pkg, Policy policy) {
            enforcePolicyAccess(pkg, "setNotificationPolicy");
            int callingUid = Binder.getCallingUid();
            boolean isSystemOrSystemUi = isCallerIsSystemOrSystemUi();

            boolean shouldApplyAsImplicitRule = android.app.Flags.modesApi()
                    && !canManageGlobalZenPolicy(pkg, callingUid);

            final long identity = Binder.clearCallingIdentity();
            try {
                final ApplicationInfo applicationInfo = mPackageManager.getApplicationInfo(pkg,
                        0, UserHandle.getUserId(callingUid));
                Policy currPolicy = mZenModeHelper.getNotificationPolicy();

                if (applicationInfo.targetSdkVersion < Build.VERSION_CODES.P) {
                    int priorityCategories = policy.priorityCategories;
                    // ignore alarm and media values from new policy
                    priorityCategories &= ~Policy.PRIORITY_CATEGORY_ALARMS;
                    priorityCategories &= ~Policy.PRIORITY_CATEGORY_MEDIA;
                    priorityCategories &= ~Policy.PRIORITY_CATEGORY_SYSTEM;
                    // use user-designated values
                    priorityCategories |= currPolicy.priorityCategories
                            & Policy.PRIORITY_CATEGORY_ALARMS;
                    priorityCategories |= currPolicy.priorityCategories
                            & Policy.PRIORITY_CATEGORY_MEDIA;
                    priorityCategories |= currPolicy.priorityCategories
                            & Policy.PRIORITY_CATEGORY_SYSTEM;

                    policy = new Policy(priorityCategories,
                            policy.priorityCallSenders, policy.priorityMessageSenders,
                            policy.suppressedVisualEffects);
                }
                if (applicationInfo.targetSdkVersion < Build.VERSION_CODES.R) {
                    int priorityCategories = correctCategory(policy.priorityCategories,
                            Policy.PRIORITY_CATEGORY_CONVERSATIONS,
                            currPolicy.priorityCategories);

                    policy = new Policy(priorityCategories,
                            policy.priorityCallSenders, policy.priorityMessageSenders,
                            policy.suppressedVisualEffects, currPolicy.priorityConversationSenders);
                }
                int newVisualEffects = calculateSuppressedVisualEffects(
                            policy, currPolicy, applicationInfo.targetSdkVersion);
                policy = new Policy(policy.priorityCategories,
                        policy.priorityCallSenders, policy.priorityMessageSenders,
                        newVisualEffects, policy.priorityConversationSenders);

                if (shouldApplyAsImplicitRule) {
                    mZenModeHelper.applyGlobalPolicyAsImplicitZenRule(pkg, callingUid, policy);
                } else {
                    ZenLog.traceSetNotificationPolicy(pkg, applicationInfo.targetSdkVersion,
                            policy);
                    mZenModeHelper.setNotificationPolicy(policy, callingUid, isSystemOrSystemUi);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to set notification policy", e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<String> getEnabledNotificationListenerPackages() {
            checkCallerIsSystem();
            return mListeners.getAllowedPackages(getCallingUserHandle().getIdentifier());
        }

        @Override
        public List<ComponentName> getEnabledNotificationListeners(int userId) {
            checkNotificationListenerAccess();
            return mListeners.getAllowedComponents(userId);
        }

        @Override
        public ComponentName getAllowedNotificationAssistantForUser(int userId) {
            checkCallerIsSystemOrSystemUiOrShell();
            List<ComponentName> allowedComponents = mAssistants.getAllowedComponents(userId);
            if (allowedComponents.size() > 1) {
                throw new IllegalStateException(
                        "At most one NotificationAssistant: " + allowedComponents.size());
            }
            return CollectionUtils.firstOrNull(allowedComponents);
        }

        @Override
        public ComponentName getAllowedNotificationAssistant() {
            return getAllowedNotificationAssistantForUser(getCallingUserHandle().getIdentifier());
        }

        @Override
        public ComponentName getDefaultNotificationAssistant() {
            checkCallerIsSystem();
            return mAssistants.getDefaultFromConfig();
        }

        @Override
        public void setNASMigrationDoneAndResetDefault(int userId, boolean loadFromConfig) {
            checkCallerIsSystem();
            setNASMigrationDone(userId);
            if (loadFromConfig) {
                mAssistants.resetDefaultFromConfig();
            } else {
                mAssistants.clearDefaults();
            }
        }


        @Override
        public boolean hasEnabledNotificationListener(String packageName, int userId) {
            checkCallerIsSystem();
            return mListeners.isPackageAllowed(packageName, userId);
        }

        @Override
        public boolean isNotificationListenerAccessGranted(ComponentName listener) {
            Objects.requireNonNull(listener);
            checkCallerIsSystemOrSameApp(listener.getPackageName());
            return mListeners.isPackageOrComponentAllowed(listener.flattenToString(),
                    getCallingUserHandle().getIdentifier());
        }

        @Override
        public boolean isNotificationListenerAccessGrantedForUser(ComponentName listener,
                int userId) {
            Objects.requireNonNull(listener);
            checkCallerIsSystem();
            return mListeners.isPackageOrComponentAllowed(listener.flattenToString(),
                    userId);
        }

        @Override
        public boolean isNotificationAssistantAccessGranted(ComponentName assistant) {
            Objects.requireNonNull(assistant);
            checkCallerIsSystemOrSameApp(assistant.getPackageName());
            return mAssistants.isPackageOrComponentAllowed(assistant.flattenToString(),
                    getCallingUserHandle().getIdentifier());
        }

        @Override
        public void setNotificationListenerAccessGranted(ComponentName listener,
                boolean granted, boolean userSet) throws RemoteException {
            setNotificationListenerAccessGrantedForUser(
                    listener, getCallingUserHandle().getIdentifier(), granted, userSet);
        }

        @Override
        public void setNotificationAssistantAccessGranted(ComponentName assistant,
                boolean granted) {
            setNotificationAssistantAccessGrantedForUser(
                    assistant, getCallingUserHandle().getIdentifier(), granted);
        }

        @Override
        public void setNotificationListenerAccessGrantedForUser(ComponentName listener, int userId,
                boolean granted, boolean userSet) {
            Objects.requireNonNull(listener);
            if (UserHandle.getCallingUserId() != userId) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS,
                        "setNotificationListenerAccessGrantedForUser for user " + userId);
            }
            checkNotificationListenerAccess();
            if (granted && listener.flattenToString().length()
                    > NotificationManager.MAX_SERVICE_COMPONENT_NAME_LENGTH) {
                throw new IllegalArgumentException(
                        "Component name too long: " + listener.flattenToString());
            }
            if (!userSet && isNotificationListenerAccessUserSet(listener, userId)) {
                // Don't override user's choice
                return;
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                if (mAllowedManagedServicePackages.test(
                        listener.getPackageName(), userId, mListeners.getRequiredPermission())) {
                    mConditionProviders.setPackageOrComponentEnabled(listener.flattenToString(),
                            userId, false, granted, userSet);
                    mListeners.setPackageOrComponentEnabled(listener.flattenToString(),
                            userId, true, granted, userSet);

                    getContext().sendBroadcastAsUser(new Intent(
                            ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
                                    .setPackage(listener.getPackageName())
                                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                            UserHandle.of(userId), null);

                    handleSavePolicyFile();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private boolean isNotificationListenerAccessUserSet(ComponentName listener, int userId) {
            return mListeners.isPackageOrComponentUserSet(listener.flattenToString(), userId);
        }

        @Override
        public void setNotificationAssistantAccessGrantedForUser(ComponentName assistant,
                int userId, boolean granted) {
            checkCallerIsSystemOrSystemUiOrShell();
            for (UserInfo ui : mUm.getEnabledProfiles(userId)) {
                mAssistants.setUserSet(ui.id, true);
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                setNotificationAssistantAccessGrantedForUserInternal(assistant, userId, granted,
                        true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void applyEnqueuedAdjustmentFromAssistant(INotificationListener token,
                Adjustment adjustment) {
            boolean foundEnqueued = false;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    mAssistants.checkServiceTokenLocked(token);
                    int N = mEnqueuedNotifications.size();
                    for (int i = 0; i < N; i++) {
                        final NotificationRecord r = mEnqueuedNotifications.get(i);
                        if (Objects.equals(adjustment.getKey(), r.getKey())
                                && Objects.equals(adjustment.getUser(), r.getUserId())
                                && mAssistants.isSameUser(token, r.getUserId())) {
                            applyAdjustment(r, adjustment);
                            r.applyAdjustments();
                            // importance is checked at the beginning of the
                            // PostNotificationRunnable, before the signal extractors are run, so
                            // calculate the final importance here
                            r.calculateImportance();
                            foundEnqueued = true;
                        }
                    }
                    if (!foundEnqueued) {
                        applyAdjustmentFromAssistant(token, adjustment);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void applyAdjustmentFromAssistant(INotificationListener token,
                Adjustment adjustment) {
            List<Adjustment> adjustments = new ArrayList<>();
            adjustments.add(adjustment);
            applyAdjustmentsFromAssistant(token, adjustments);
        }

        @Override
        public void applyAdjustmentsFromAssistant(INotificationListener token,
                List<Adjustment> adjustments) {

            boolean needsSort = false;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    mAssistants.checkServiceTokenLocked(token);
                    for (Adjustment adjustment : adjustments) {
                        NotificationRecord r = mNotificationsByKey.get(adjustment.getKey());
                        if (r != null && mAssistants.isSameUser(token, r.getUserId())) {
                            applyAdjustment(r, adjustment);
                            // If the assistant has blocked the notification, cancel it
                            // This will trigger a sort, so we don't have to explicitly ask for
                            // one here.
                            if (adjustment.getSignals().containsKey(Adjustment.KEY_IMPORTANCE)
                                    && adjustment.getSignals().getInt(Adjustment.KEY_IMPORTANCE)
                                    == IMPORTANCE_NONE) {
                                cancelNotificationsFromListener(token, new String[]{r.getKey()});
                            } else {
                                r.setPendingLogUpdate(true);
                                needsSort = true;
                            }
                        }
                    }
                }
                if (needsSort) {
                    mRankingHandler.requestSort();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void updateNotificationChannelGroupFromPrivilegedListener(
                INotificationListener token, String pkg, UserHandle user,
                NotificationChannelGroup group) throws RemoteException {
            Objects.requireNonNull(user);
            verifyPrivilegedListener(token, user, false);
            createNotificationChannelGroup(
                    pkg, getUidForPackageAndUser(pkg, user), group, false, true);
            handleSavePolicyFile();
        }

        @Override
        public void updateNotificationChannelFromPrivilegedListener(INotificationListener token,
                String pkg, UserHandle user, NotificationChannel channel) throws RemoteException {
            Objects.requireNonNull(channel);
            Objects.requireNonNull(pkg);
            Objects.requireNonNull(user);

            verifyPrivilegedListener(token, user, false);
            updateNotificationChannelInt(pkg, getUidForPackageAndUser(pkg, user), channel, true);
        }

        @Override
        public ParceledListSlice<NotificationChannel> getNotificationChannelsFromPrivilegedListener(
                INotificationListener token, String pkg, UserHandle user) throws RemoteException {
            Objects.requireNonNull(pkg);
            Objects.requireNonNull(user);
            verifyPrivilegedListener(token, user, true);

            return mPreferencesHelper.getNotificationChannels(pkg,
                    getUidForPackageAndUser(pkg, user), false /* includeDeleted */);
        }

        @Override
        public ParceledListSlice<NotificationChannelGroup>
                getNotificationChannelGroupsFromPrivilegedListener(
                INotificationListener token, String pkg, UserHandle user) throws RemoteException {
            Objects.requireNonNull(pkg);
            Objects.requireNonNull(user);
            verifyPrivilegedListener(token, user, true);

            List<NotificationChannelGroup> groups = new ArrayList<>();
            groups.addAll(mPreferencesHelper.getNotificationChannelGroups(
                    pkg, getUidForPackageAndUser(pkg, user)));
            return new ParceledListSlice<>(groups);
        }

        @Override
        public boolean isInCall(String pkg, int uid) {
            checkCallerIsSystemOrSystemUiOrShell();
            return isCallNotification(pkg, uid);
        }

        @Override
        public void setPrivateNotificationsAllowed(boolean allow) {
            if (PackageManager.PERMISSION_GRANTED
                    != getContext().checkCallingPermission(
                            permission.CONTROL_KEYGUARD_SECURE_NOTIFICATIONS)) {
                throw new SecurityException(
                        "Requires CONTROL_KEYGUARD_SECURE_NOTIFICATIONS permission");
            }
            if (allow != mLockScreenAllowSecureNotifications) {
                mLockScreenAllowSecureNotifications = allow;
                handleSavePolicyFile();
            }
        }

        @Override
        public boolean getPrivateNotificationsAllowed() {
            if (PackageManager.PERMISSION_GRANTED
                    != getContext().checkCallingPermission(
                            permission.CONTROL_KEYGUARD_SECURE_NOTIFICATIONS)) {
                throw new SecurityException(
                        "Requires CONTROL_KEYGUARD_SECURE_NOTIFICATIONS permission");
            }
            return mLockScreenAllowSecureNotifications;
        }

        @Override
        public boolean isPackagePaused(String pkg) {
            Objects.requireNonNull(pkg);
            checkCallerIsSameApp(pkg);

            return isPackagePausedOrSuspended(pkg, Binder.getCallingUid());
        }

        @Override
        public boolean isPermissionFixed(String pkg, @UserIdInt int userId) {
            enforceSystemOrSystemUI("isPermissionFixed");
            return mPermissionHelper.isPermissionFixed(pkg, userId);
        }

        private void verifyPrivilegedListener(INotificationListener token, UserHandle user,
                boolean assistantAllowed) {
            ManagedServiceInfo info;
            synchronized (mNotificationLock) {
                info = mListeners.checkServiceTokenLocked(token);
            }
            if (!hasCompanionDevice(info)) {
                synchronized (mNotificationLock) {
                    if (!assistantAllowed || !mAssistants.isServiceTokenValidLocked(info.service)) {
                        throw new SecurityException(info + " does not have access");
                    }
                }
            }
            if (!info.enabledAndUserMatches(user.getIdentifier())) {
                throw new SecurityException(info + " does not have access");
            }
        }

        private int getUidForPackageAndUser(String pkg, UserHandle user) throws RemoteException {
            int uid = INVALID_UID;
            final long identity = Binder.clearCallingIdentity();
            try {
                uid = mPackageManager.getPackageUid(pkg, 0, user.getIdentifier());
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return uid;
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver)
                throws RemoteException {
            new NotificationShellCmd(NotificationManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        /**
         * Get stats committed after startNs
         *
         * @param startNs Report stats committed after this time in nanoseconds.
         * @param report  Indicatess which section to include in the stats.
         * @param doAgg   Whether to aggregate the stats or keep them separated.
         * @param out   List of protos of individual commits or one representing the
         *                aggregate.
         * @return the report time in nanoseconds, or 0 on error.
         */
        @Override
        public long pullStats(long startNs, int report, boolean doAgg,
                List<ParcelFileDescriptor> out) {
            checkCallerIsSystemOrShell();
            long startMs = TimeUnit.MILLISECONDS.convert(startNs, TimeUnit.NANOSECONDS);

            final long identity = Binder.clearCallingIdentity();
            try {
                switch (report) {
                    case REPORT_REMOTE_VIEWS:
                        Slog.e(TAG, "pullStats REPORT_REMOTE_VIEWS from: "
                                + startMs + "  with " + doAgg);
                        PulledStats stats = mUsageStats.remoteViewStats(startMs, doAgg);
                        if (stats != null) {
                            out.add(stats.toParcelFileDescriptor(report));
                            Slog.e(TAG, "exiting pullStats with: " + out.size());
                            long endNs = TimeUnit.NANOSECONDS
                                    .convert(stats.endTimeMs(), TimeUnit.MILLISECONDS);
                            return endNs;
                        }
                        Slog.e(TAG, "null stats for: " + report);
                }
            } catch (IOException e) {

                Slog.e(TAG, "exiting pullStats: on error", e);
                return 0;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            Slog.e(TAG, "exiting pullStats: bad request");
            return 0;
        }
    };

    private void handleNotificationPermissionChange(String pkg, @UserIdInt int userId) {
        if (!mUmInternal.isUserInitialized(userId)) {
            return; // App-op "updates" are sent when starting a new user the first time.
        }
        int uid = mPackageManagerInternal.getPackageUid(pkg, 0, userId);
        if (uid == INVALID_UID) {
            Log.e(TAG, String.format("No uid found for %s, %s!", pkg, userId));
            return;
        }
        boolean hasPermission = mPermissionHelper.hasPermission(uid);
        if (!hasPermission) {
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, /* channelId= */ null,
                    /* mustHaveFlags= */ 0, /* mustNotHaveFlags= */ 0, userId,
                    REASON_PACKAGE_BANNED);
        }
    }

    protected void checkNotificationListenerAccess() {
        if (!isCallerSystemOrPhone()) {
            getContext().enforceCallingPermission(
                    permission.MANAGE_NOTIFICATION_LISTENERS,
                    "Caller must hold " + permission.MANAGE_NOTIFICATION_LISTENERS);
        }
    }

    @VisibleForTesting
    protected void setNotificationAssistantAccessGrantedForUserInternal(
            ComponentName assistant, int baseUserId, boolean granted, boolean userSet) {
        List<UserInfo> users = mUm.getEnabledProfiles(baseUserId);
        if (users != null) {
            for (UserInfo user : users) {
                int userId = user.id;
                if (assistant == null) {
                    ComponentName allowedAssistant = CollectionUtils.firstOrNull(
                            mAssistants.getAllowedComponents(userId));
                    if (allowedAssistant != null) {
                        setNotificationAssistantAccessGrantedForUserInternal(
                                allowedAssistant, userId, false, userSet);
                    }
                    continue;
                }
                if (!granted || mAllowedManagedServicePackages.test(assistant.getPackageName(),
                        userId, mAssistants.getRequiredPermission())) {
                    mConditionProviders.setPackageOrComponentEnabled(assistant.flattenToString(),
                            userId, false, granted);
                    mAssistants.setPackageOrComponentEnabled(assistant.flattenToString(),
                            userId, true, granted, userSet);

                    getContext().sendBroadcastAsUser(
                            new Intent(ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
                                    .setPackage(assistant.getPackageName())
                                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                            UserHandle.of(userId), null);

                    handleSavePolicyFile();
                }
            }
        }
    }

    private void applyAdjustment(NotificationRecord r, Adjustment adjustment) {
        if (r == null) {
            return;
        }
        if (adjustment.getSignals() != null) {
            final Bundle adjustments = adjustment.getSignals();
            Bundle.setDefusable(adjustments, true);
            List<String> toRemove = new ArrayList<>();
            for (String potentialKey : adjustments.keySet()) {
                if (!mAssistants.isAdjustmentAllowed(potentialKey)) {
                    toRemove.add(potentialKey);
                }
            }
            for (String removeKey : toRemove) {
                adjustments.remove(removeKey);
            }
            r.addAdjustment(adjustment);
        }
    }

    @GuardedBy("mNotificationLock")
    void addAutogroupKeyLocked(String key) {
        NotificationRecord r = mNotificationsByKey.get(key);
        if (r == null) {
            return;
        }
        if (r.getSbn().getOverrideGroupKey() == null) {
            addAutoGroupAdjustment(r, GroupHelper.AUTOGROUP_KEY);
            EventLogTags.writeNotificationAutogrouped(key);
            mRankingHandler.requestSort();
        }
    }

    @GuardedBy("mNotificationLock")
    void removeAutogroupKeyLocked(String key) {
        NotificationRecord r = mNotificationsByKey.get(key);
        if (r == null) {
            Slog.w(TAG, "Failed to remove autogroup " + key);
            return;
        }
        if (r.getSbn().getOverrideGroupKey() != null) {
            addAutoGroupAdjustment(r, null);
            EventLogTags.writeNotificationUnautogrouped(key);
            mRankingHandler.requestSort();
        }
    }

    private void addAutoGroupAdjustment(NotificationRecord r, String overrideGroupKey) {
        Bundle signals = new Bundle();
        signals.putString(Adjustment.KEY_GROUP_KEY, overrideGroupKey);
        Adjustment adjustment = new Adjustment(r.getSbn().getPackageName(), r.getKey(), signals, "",
                r.getSbn().getUserId());
        r.addAdjustment(adjustment);
    }

    // Clears the 'fake' auto-group summary.
    @VisibleForTesting
    @GuardedBy("mNotificationLock")
    void clearAutogroupSummaryLocked(int userId, String pkg) {
        ArrayMap<String, String> summaries = mAutobundledSummaries.get(userId);
        if (summaries != null && summaries.containsKey(pkg)) {
            final NotificationRecord removed = findNotificationByKeyLocked(summaries.remove(pkg));
            if (removed != null) {
                final StatusBarNotification sbn = removed.getSbn();
                cancelNotification(MY_UID, MY_PID, pkg, sbn.getTag(), sbn.getId(), 0, 0, false,
                        userId, REASON_UNAUTOBUNDLED, null);
            }
        }
    }

    @GuardedBy("mNotificationLock")
    private boolean hasAutoGroupSummaryLocked(StatusBarNotification sbn) {
        ArrayMap<String, String> summaries = mAutobundledSummaries.get(sbn.getUserId());
        return summaries != null && summaries.containsKey(sbn.getPackageName());
    }

    // Creates a 'fake' summary for a package that has exceeded the solo-notification limit.
    NotificationRecord createAutoGroupSummary(int userId, String pkg, String triggeringKey,
            int flagsToSet) {
        NotificationRecord summaryRecord = null;
        boolean isPermissionFixed = mPermissionHelper.isPermissionFixed(pkg, userId);
        synchronized (mNotificationLock) {
            NotificationRecord notificationRecord = mNotificationsByKey.get(triggeringKey);
            if (notificationRecord == null) {
                // The notification could have been cancelled again already. A successive
                // adjustment will post a summary if needed.
                return null;
            }
            final StatusBarNotification adjustedSbn = notificationRecord.getSbn();
            userId = adjustedSbn.getUser().getIdentifier();
            int uid =  adjustedSbn.getUid();
            ArrayMap<String, String> summaries = mAutobundledSummaries.get(userId);
            if (summaries == null) {
                summaries = new ArrayMap<>();
            }
            mAutobundledSummaries.put(userId, summaries);
            if (!summaries.containsKey(pkg)) {
                // Add summary
                final ApplicationInfo appInfo =
                        adjustedSbn.getNotification().extras.getParcelable(
                                Notification.EXTRA_BUILDER_APPLICATION_INFO, ApplicationInfo.class);
                final Bundle extras = new Bundle();
                extras.putParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO, appInfo);
                final String channelId = notificationRecord.getChannel().getId();
                final Notification summaryNotification =
                        new Notification.Builder(getContext(), channelId)
                                .setSmallIcon(adjustedSbn.getNotification().getSmallIcon())
                                .setGroupSummary(true)
                                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                                .setGroup(GroupHelper.AUTOGROUP_KEY)
                                .setFlag(flagsToSet, true)
                                .setColor(adjustedSbn.getNotification().color)
                                .build();
                summaryNotification.extras.putAll(extras);
                Intent appIntent = getContext().getPackageManager().getLaunchIntentForPackage(pkg);
                if (appIntent != null) {
                    summaryNotification.contentIntent = mAmi.getPendingIntentActivityAsApp(
                            0, appIntent, PendingIntent.FLAG_IMMUTABLE, null,
                            pkg, appInfo.uid);
                }
                final StatusBarNotification summarySbn =
                        new StatusBarNotification(adjustedSbn.getPackageName(),
                                adjustedSbn.getOpPkg(),
                                Integer.MAX_VALUE,
                                GroupHelper.AUTOGROUP_KEY, adjustedSbn.getUid(),
                                adjustedSbn.getInitialPid(), summaryNotification,
                                adjustedSbn.getUser(), GroupHelper.AUTOGROUP_KEY,
                                System.currentTimeMillis());
                summaryRecord = new NotificationRecord(getContext(), summarySbn,
                        notificationRecord.getChannel());
                summaryRecord.setImportanceFixed(isPermissionFixed);
                summaryRecord.setIsAppImportanceLocked(
                        notificationRecord.getIsAppImportanceLocked());
                summaries.put(pkg, summarySbn.getKey());
            }
            if (summaryRecord != null && checkDisqualifyingFeatures(userId, uid,
                    summaryRecord.getSbn().getId(), summaryRecord.getSbn().getTag(), summaryRecord,
                    true, false)) {
                return summaryRecord;
            }
        }
        return null;
    }

    private String disableNotificationEffects(NotificationRecord record) {
        if (mDisableNotificationEffects) {
            return "booleanState";
        }
        if ((mListenerHints & HINT_HOST_DISABLE_EFFECTS) != 0) {
            return "listenerHints";
        }
        if (record != null && record.getAudioAttributes() != null) {
            if ((mListenerHints & HINT_HOST_DISABLE_NOTIFICATION_EFFECTS) != 0) {
                if (record.getAudioAttributes().getUsage()
                        != AudioAttributes.USAGE_NOTIFICATION_RINGTONE) {
                    return "listenerNoti";
                }
            }
            if ((mListenerHints & HINT_HOST_DISABLE_CALL_EFFECTS) != 0) {
                if (record.getAudioAttributes().getUsage()
                        == AudioAttributes.USAGE_NOTIFICATION_RINGTONE) {
                    return "listenerCall";
                }
            }
        }
        if (mCallState != TelephonyManager.CALL_STATE_IDLE && !mZenModeHelper.isCall(record)) {
            return "callState";
        }
        return null;
    }

    // Gets packages that have requested notification permission, and whether that has been
    // allowed/denied, for all users on the device.
    // Returns a single map containing that info keyed by (uid, package name) for all users.
    // Because this calls into mPermissionHelper, this method must never be called with a lock held.
    @VisibleForTesting
    protected ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>>
            getAllUsersNotificationPermissions() {
        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> allPermissions = new ArrayMap<>();
        final List<UserInfo> allUsers = mUm.getUsers();
        // for each of these, get the package notification permissions that are associated
        // with this user and add it to the map
        for (UserInfo ui : allUsers) {
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> userPermissions =
                    mPermissionHelper.getNotificationPermissionValues(
                            ui.getUserHandle().getIdentifier());
            for (Pair<Integer, String> pair : userPermissions.keySet()) {
                allPermissions.put(pair, userPermissions.get(pair));
            }
        }
        return allPermissions;
    }

    private void dumpJson(PrintWriter pw, @NonNull DumpFilter filter,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions) {
        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Notification Manager");
            dump.put("bans", mPreferencesHelper.dumpBansJson(filter, pkgPermissions));
            dump.put("ranking", mPreferencesHelper.dumpJson(filter, pkgPermissions));
            dump.put("stats", mUsageStats.dumpJson(filter));
            dump.put("channels", mPreferencesHelper.dumpChannelsJson(filter));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        pw.println(dump);
    }

    private void dumpRemoteViewStats(PrintWriter pw, @NonNull DumpFilter filter) {
        PulledStats stats = mUsageStats.remoteViewStats(filter.since, true);
        if (stats == null) {
            pw.println("no remote view stats reported.");
            return;
        }
        stats.dump(REPORT_REMOTE_VIEWS, pw, filter);
    }

    private void dumpProto(FileDescriptor fd, @NonNull DumpFilter filter,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        synchronized (mNotificationLock) {
            int N = mNotificationList.size();
            for (int i = 0; i < N; i++) {
                final NotificationRecord nr = mNotificationList.get(i);
                if (filter.filtered && !filter.matches(nr.getSbn())) continue;
                nr.dump(proto, NotificationServiceDumpProto.RECORDS, filter.redact,
                        NotificationRecordProto.POSTED);
            }
            N = mEnqueuedNotifications.size();
            for (int i = 0; i < N; i++) {
                final NotificationRecord nr = mEnqueuedNotifications.get(i);
                if (filter.filtered && !filter.matches(nr.getSbn())) continue;
                nr.dump(proto, NotificationServiceDumpProto.RECORDS, filter.redact,
                        NotificationRecordProto.ENQUEUED);
            }
            List<NotificationRecord> snoozed = mSnoozeHelper.getSnoozed();
            N = snoozed.size();
            for (int i = 0; i < N; i++) {
                final NotificationRecord nr = snoozed.get(i);
                if (filter.filtered && !filter.matches(nr.getSbn())) continue;
                nr.dump(proto, NotificationServiceDumpProto.RECORDS, filter.redact,
                        NotificationRecordProto.SNOOZED);
            }

            long zenLog = proto.start(NotificationServiceDumpProto.ZEN);
            mZenModeHelper.dump(proto);
            for (ComponentName suppressor : mEffectsSuppressors) {
                suppressor.dumpDebug(proto, ZenModeProto.SUPPRESSORS);
            }
            proto.end(zenLog);

            long listenersToken = proto.start(NotificationServiceDumpProto.NOTIFICATION_LISTENERS);
            mListeners.dump(proto, filter);
            proto.end(listenersToken);

            proto.write(NotificationServiceDumpProto.LISTENER_HINTS, mListenerHints);

            for (int i = 0; i < mListenersDisablingEffects.size(); ++i) {
                long effectsToken = proto.start(
                    NotificationServiceDumpProto.LISTENERS_DISABLING_EFFECTS);

                proto.write(
                    ListenersDisablingEffectsProto.HINT, mListenersDisablingEffects.keyAt(i));
                final ArraySet<ComponentName> listeners =
                    mListenersDisablingEffects.valueAt(i);
                for (int j = 0; j < listeners.size(); j++) {
                    final ComponentName componentName = listeners.valueAt(j);
                    componentName.dumpDebug(proto,
                            ListenersDisablingEffectsProto.LISTENER_COMPONENTS);
                }

                proto.end(effectsToken);
            }

            long assistantsToken = proto.start(
                NotificationServiceDumpProto.NOTIFICATION_ASSISTANTS);
            mAssistants.dump(proto, filter);
            proto.end(assistantsToken);

            long conditionsToken = proto.start(NotificationServiceDumpProto.CONDITION_PROVIDERS);
            mConditionProviders.dump(proto, filter);
            proto.end(conditionsToken);

            long rankingToken = proto.start(NotificationServiceDumpProto.RANKING_CONFIG);
            mRankingHelper.dump(proto, filter);
            mPreferencesHelper.dump(proto, filter, pkgPermissions);
            proto.end(rankingToken);
        }

        proto.flush();
    }

    private void dumpNotificationRecords(PrintWriter pw, @NonNull DumpFilter filter) {
        synchronized (mNotificationLock) {
            int N;
            N = mNotificationList.size();
            if (N > 0) {
                pw.println("  Notification List:");
                for (int i = 0; i < N; i++) {
                    final NotificationRecord nr = mNotificationList.get(i);
                    if (filter.filtered && !filter.matches(nr.getSbn())) continue;
                    nr.dump(pw, "    ", getContext(), filter.redact);
                }
                pw.println("  ");
            }
        }
    }

    void dumpImpl(PrintWriter pw, @NonNull DumpFilter filter,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions) {
        pw.print("Current Notification Manager state");
        if (filter.filtered) {
            pw.print(" (filtered to "); pw.print(filter); pw.print(")");
        }
        pw.println(':');
        int N;
        final boolean zenOnly = filter.filtered && filter.zen;

        if (!zenOnly) {
            synchronized (mToastQueue) {
                N = mToastQueue.size();
                if (N > 0) {
                    pw.println("  Toast Queue:");
                    for (int i=0; i<N; i++) {
                        mToastQueue.get(i).dump(pw, "    ", filter);
                    }
                    pw.println("  ");
                }
            }
        }

        synchronized (mNotificationLock) {
            if (!zenOnly) {
                // Priority filters are only set when called via bugreport. If set
                // skip sections that are part of the critical section.
                if (!filter.normalPriority) {
                    dumpNotificationRecords(pw, filter);
                }
                if (!filter.filtered) {
                    N = mLights.size();
                    if (N > 0) {
                        pw.println("  Lights List:");
                        for (int i=0; i<N; i++) {
                            if (i == N - 1) {
                                pw.print("  > ");
                            } else {
                                pw.print("    ");
                            }
                            pw.println(mLights.get(i));
                        }
                        pw.println("  ");
                    }
                    pw.println("  mUseAttentionLight=" + mUseAttentionLight);
                    pw.println("  mHasLight=" + mHasLight);
                    pw.println("  mNotificationPulseEnabled=" + mNotificationPulseEnabled);
                    pw.println("  mSoundNotificationKey=" + mSoundNotificationKey);
                    pw.println("  mVibrateNotificationKey=" + mVibrateNotificationKey);
                    pw.println("  mDisableNotificationEffects=" + mDisableNotificationEffects);
                    pw.println("  mCallState=" + callStateToString(mCallState));
                    pw.println("  mSystemReady=" + mSystemReady);
                    pw.println("  mMaxPackageEnqueueRate=" + mMaxPackageEnqueueRate);
                    pw.println("  hideSilentStatusBar="
                            + mPreferencesHelper.shouldHideSilentStatusIcons());
                    if (Flags.refactorAttentionHelper()) {
                        mAttentionHelper.dump(pw, "    ", filter);
                    }
                }
                pw.println("  mArchive=" + mArchive.toString());
                mArchive.dumpImpl(pw, filter);

                if (!zenOnly) {
                    N = mEnqueuedNotifications.size();
                    if (N > 0) {
                        pw.println("  Enqueued Notification List:");
                        for (int i = 0; i < N; i++) {
                            final NotificationRecord nr = mEnqueuedNotifications.get(i);
                            if (filter.filtered && !filter.matches(nr.getSbn())) continue;
                            nr.dump(pw, "    ", getContext(), filter.redact);
                        }
                        pw.println("  ");
                    }

                    mSnoozeHelper.dump(pw, filter);
                }
            }

            if (!zenOnly) {
                pw.println("\n  Ranking Config:");
                mRankingHelper.dump(pw, "    ", filter);

                pw.println("\n Notification Preferences:");
                mPreferencesHelper.dump(pw, "    ", filter, pkgPermissions);

                pw.println("\n  Notification listeners:");
                mListeners.dump(pw, filter);
                pw.print("    mListenerHints: "); pw.println(mListenerHints);
                pw.print("    mListenersDisablingEffects: (");
                N = mListenersDisablingEffects.size();
                for (int i = 0; i < N; i++) {
                    final int hint = mListenersDisablingEffects.keyAt(i);
                    if (i > 0) pw.print(';');
                    pw.print("hint[" + hint + "]:");

                    final ArraySet<ComponentName> listeners = mListenersDisablingEffects.valueAt(i);
                    final int listenerSize = listeners.size();

                    for (int j = 0; j < listenerSize; j++) {
                        if (j > 0) pw.print(',');
                        final ComponentName listener = listeners.valueAt(j);
                        if (listener != null) {
                            pw.print(listener);
                        }
                    }
                }
                pw.println(')');
                pw.println("\n  Notification assistant services:");
                mAssistants.dump(pw, filter);
            }

            if (!filter.filtered || zenOnly) {
                pw.println("\n  Zen Mode:");
                pw.print("    mInterruptionFilter="); pw.println(mInterruptionFilter);
                mZenModeHelper.dump(pw, "    ");

                pw.println("\n  Zen Log:");
                ZenLog.dump(pw, "    ");
            }

            pw.println("\n  Condition providers:");
            mConditionProviders.dump(pw, filter);

            pw.println("\n  Group summaries:");
            for (Entry<String, NotificationRecord> entry : mSummaryByGroupKey.entrySet()) {
                NotificationRecord r = entry.getValue();
                pw.println("    " + entry.getKey() + " -> " + r.getKey());
                if (mNotificationsByKey.get(r.getKey()) != r) {
                    pw.println("!!!!!!LEAK: Record not found in mNotificationsByKey.");
                    r.dump(pw, "      ", getContext(), filter.redact);
                }
            }

            if (!zenOnly) {
                pw.println("\n  Usage Stats:");
                mUsageStats.dump(pw, "    ", filter);
            }
        }
    }

    /**
     * The private API only accessible to the system process.
     */
    private final NotificationManagerInternal mInternalService = new NotificationManagerInternal() {

        @Override
        public NotificationChannel getNotificationChannel(String pkg, int uid, String
                channelId) {
            return mPreferencesHelper.getNotificationChannel(pkg, uid, channelId, false);
        }

        @Override
        public NotificationChannelGroup getNotificationChannelGroup(String pkg, int uid, String
                channelId) {
            return mPreferencesHelper.getGroupForChannel(pkg, uid, channelId);
        }

        @Override
        public void enqueueNotification(String pkg, String opPkg, int callingUid, int callingPid,
                String tag, int id, Notification notification, int userId) {
            enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, notification,
                    userId, false /* byForegroundService */);
        }

        @Override
        public void enqueueNotification(String pkg, String opPkg, int callingUid, int callingPid,
                String tag, int id, Notification notification, int userId,
                boolean byForegroundService) {
            enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, notification,
                    userId, byForegroundService);
        }

        @Override
        public void cancelNotification(String pkg, String opPkg, int callingUid, int callingPid,
                String tag, int id, int userId) {
            cancelNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, userId);
        }

        @Override
        public boolean isNotificationShown(String pkg, String tag, int notificationId, int userId) {
            return isNotificationShownInternal(pkg, tag, notificationId, userId);
        }

        @Override
        public void removeForegroundServiceFlagFromNotification(String pkg, int notificationId,
                int userId) {
            checkCallerIsSystem();
            mHandler.post(() -> {
                synchronized (mNotificationLock) {
                    removeFlagFromNotificationLocked(pkg, notificationId, userId,
                            FLAG_FOREGROUND_SERVICE);
                }
            });
        }

        @Override
        public void removeUserInitiatedJobFlagFromNotification(String pkg, int notificationId,
                int userId) {
            checkCallerIsSystem();
            mHandler.post(() -> {
                synchronized (mNotificationLock) {
                    removeFlagFromNotificationLocked(pkg, notificationId, userId,
                            FLAG_USER_INITIATED_JOB);
                }
            });
        }

        @GuardedBy("mNotificationLock")
        private void removeFlagFromNotificationLocked(String pkg, int notificationId, int userId,
                int flag) {
            int count = getNotificationCount(pkg, userId);
            boolean removeFlagFromNotification = false;
            if (count > MAX_PACKAGE_NOTIFICATIONS) {
                mUsageStats.registerOverCountQuota(pkg);
                removeFlagFromNotification = true;
            }
            if (removeFlagFromNotification) {
                NotificationRecord r = findNotificationLocked(pkg, null, notificationId, userId);
                if (r != null) {
                    if (DBG) {
                        final String type = (flag ==  FLAG_FOREGROUND_SERVICE) ? "FGS" : "UIJ";
                        Slog.d(TAG, "Remove " + type + " flag not allow. "
                                + "Cancel " + type + " notification");
                    }
                    removeFromNotificationListsLocked(r);
                    cancelNotificationLocked(r, false, REASON_APP_CANCEL, true,
                            null, SystemClock.elapsedRealtime());
                }
            } else {
                List<NotificationRecord> enqueued = findNotificationsByListLocked(
                        mEnqueuedNotifications, pkg, null, notificationId, userId);
                for (int i = 0; i < enqueued.size(); i++) {
                    final NotificationRecord r = enqueued.get(i);
                    if (r != null) {
                        // strip flag from all enqueued notifications. listeners will be informed
                        // in post runnable.
                        StatusBarNotification sbn = r.getSbn();
                        sbn.getNotification().flags = (r.mOriginalFlags & ~flag);
                    }
                }

                NotificationRecord r = findNotificationByListLocked(
                        mNotificationList, pkg, null, notificationId, userId);
                if (r != null) {
                    // if posted notification exists, strip its flag and tell listeners
                    StatusBarNotification sbn = r.getSbn();
                    sbn.getNotification().flags = (r.mOriginalFlags & ~flag);
                    mRankingHelper.sort(mNotificationList);
                    mListeners.notifyPostedLocked(r, r);
                }
            }
        }

        @Override
        public void onConversationRemoved(String pkg, int uid, Set<String> shortcuts) {
            onConversationRemovedInternal(pkg, uid, shortcuts);
        }

        @Override
        public int getNumNotificationChannelsForPackage(String pkg, int uid,
                boolean includeDeleted) {
            return NotificationManagerService.this
                    .getNumNotificationChannelsForPackage(pkg, uid, includeDeleted);
        }

        @Override
        public boolean areNotificationsEnabledForPackage(String pkg, int uid) {
            return areNotificationsEnabledForPackageInt(pkg, uid);
        }

        @Override
        public void sendReviewPermissionsNotification() {
            if (!mShowReviewPermissionsNotification) {
                // don't show if this notification is turned off
                return;
            }

            // This method is meant to be called from the JobService upon running the job for this
            // notification having been rescheduled; so without checking any other state, it will
            // send the notification.
            checkCallerIsSystem();
            NotificationManager nm = getContext().getSystemService(NotificationManager.class);
            nm.notify(TAG,
                    SystemMessageProto.SystemMessage.NOTE_REVIEW_NOTIFICATION_PERMISSIONS,
                    createReviewPermissionsNotification());
            Settings.Global.putInt(getContext().getContentResolver(),
                    Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                    NotificationManagerService.REVIEW_NOTIF_STATE_RESHOWN);
        }

        @Override
        public void cleanupHistoryFiles() {
            checkCallerIsSystem();
            mHistoryManager.cleanupHistoryFiles();
        }

        @Override
        public void removeBitmaps() {
            // Check all NotificationRecords, remove expired bitmaps and icon URIs, repost silently.
            synchronized (mNotificationLock) {
                for (NotificationRecord r: mNotificationList) {

                    // System#currentTimeMillis when posted
                    final long timePostedMs = r.getSbn().getPostTime();
                    final long timeNowMs = System.currentTimeMillis();

                    final long bitmapDuration;
                    if (mFlagResolver.isEnabled(NotificationFlags.DEBUG_SHORT_BITMAP_DURATION)) {
                        bitmapDuration = Duration.ofSeconds(5).toMillis();
                    } else {
                        bitmapDuration = BITMAP_DURATION.toMillis();
                    }

                    if (isBitmapExpired(timePostedMs, timeNowMs, bitmapDuration)) {
                        removeBitmapAndRepost(r);
                    }
                }
            }
        }
    };

    private static boolean isBigPictureWithBitmapOrIcon(Notification n) {
        final boolean isBigPicture = n.isStyle(Notification.BigPictureStyle.class);
        if (!isBigPicture) {
            return false;
        }

        final boolean hasBitmap = n.extras.containsKey(Notification.EXTRA_PICTURE);
        if (hasBitmap) {
            return true;
        }

        final boolean hasIcon = n.extras.containsKey(Notification.EXTRA_PICTURE_ICON);
        if (hasIcon) {
            return true;
        }
        return false;
    }

    private static boolean isBitmapExpired(long timePostedMs, long timeNowMs, long timeToLiveMs) {
        final long timeDiff = timeNowMs - timePostedMs;
        return timeDiff > timeToLiveMs;
    }

    private void removeBitmapAndRepost(NotificationRecord r) {
        if (!isBigPictureWithBitmapOrIcon(r.getNotification())) {
            return;
        }
        // Remove Notification object's reference to picture bitmap or URI
        r.getNotification().extras.remove(Notification.EXTRA_PICTURE);
        r.getNotification().extras.remove(Notification.EXTRA_PICTURE_ICON);

        // Make Notification silent
        r.getNotification().flags |= FLAG_ONLY_ALERT_ONCE;

        // Repost
        enqueueNotificationInternal(r.getSbn().getPackageName(),
                r.getSbn().getOpPkg(), r.getSbn().getUid(),
                r.getSbn().getInitialPid(), r.getSbn().getTag(),
                r.getSbn().getId(), r.getNotification(),
                r.getSbn().getUserId(), /* postSilently= */ true,
                /* byForegroundService= */ false);
    }

    int getNumNotificationChannelsForPackage(String pkg, int uid, boolean includeDeleted) {
        return mPreferencesHelper.getNotificationChannels(pkg, uid, includeDeleted).getList()
                .size();
    }

    void cancelNotificationInternal(String pkg, String opPkg, int callingUid, int callingPid,
            String tag, int id, int userId) {
        userId = ActivityManager.handleIncomingUser(callingPid,
                callingUid, userId, true, false, "cancelNotificationWithTag", pkg);

        // ensure opPkg is delegate if does not match pkg
        int uid = resolveNotificationUid(opPkg, pkg, callingUid, userId);

        if (uid == INVALID_UID) {
            Slog.w(TAG, opPkg + ":" + callingUid + " trying to cancel notification "
                    + "for nonexistent pkg " + pkg + " in user " + userId);
            return;
        }

        // if opPkg is not the same as pkg, make sure the notification given was posted
        // by opPkg
        if (!Objects.equals(pkg, opPkg)) {
            synchronized (mNotificationLock) {
                // Look for the notification, searching both the posted and enqueued lists.
                NotificationRecord r = findNotificationLocked(pkg, tag, id, userId);
                if (r != null) {
                    if (!Objects.equals(opPkg, r.getSbn().getOpPkg())) {
                        throw new SecurityException(opPkg + " does not have permission to "
                                + "cancel a notification they did not post " + tag + " " + id);
                    }
                }
            }
        }

        // Don't allow client applications to cancel foreground service notifs, user-initiated job
        // notifs or autobundled summaries.
        final int mustNotHaveFlags = isCallingUidSystem() ? 0 :
                (FLAG_FOREGROUND_SERVICE | FLAG_USER_INITIATED_JOB | FLAG_AUTOGROUP_SUMMARY);
        cancelNotification(uid, callingPid, pkg, tag, id, 0,
                mustNotHaveFlags, false, userId, REASON_APP_CANCEL, null);
    }

    boolean isNotificationShownInternal(String pkg, String tag, int notificationId, int userId) {
        synchronized (mNotificationLock) {
            return findNotificationLocked(pkg, tag, notificationId, userId) != null;
        }
    }

    void enqueueNotificationInternal(final String pkg, final String opPkg, final int callingUid,
            final int callingPid, final String tag, final int id, final Notification notification,
            int incomingUserId, boolean byForegroundService) {
        enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, notification,
                incomingUserId, false /* postSilently */, byForegroundService);
    }

    void enqueueNotificationInternal(final String pkg, final String opPkg, final int callingUid,
            final int callingPid, final String tag, final int id, final Notification notification,
            int incomingUserId, boolean postSilently, boolean byForegroundService) {
        PostNotificationTracker tracker = acquireWakeLockForPost(pkg, callingUid);
        boolean enqueued = false;
        try {
            enqueued = enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id,
                    notification, incomingUserId, postSilently, tracker, byForegroundService);
        } finally {
            if (!enqueued) {
                tracker.cancel();
            }
        }
    }

    private PostNotificationTracker acquireWakeLockForPost(String pkg, int uid) {
        // The package probably doesn't have WAKE_LOCK permission and should not require it.
        return Binder.withCleanCallingIdentity(() -> {
            WakeLock wakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "NotificationManagerService:post:" + pkg);
            wakeLock.setWorkSource(new WorkSource(uid, pkg));
            wakeLock.acquire(POST_WAKE_LOCK_TIMEOUT.toMillis());
            return mPostNotificationTrackerFactory.newTracker(wakeLock);
        });
    }

    /**
     * @return True if we successfully processed the notification and handed off the task of
     * enqueueing it to a background thread; false otherwise.
     */
    private boolean enqueueNotificationInternal(final String pkg, final String opPkg,  //HUI
            final int callingUid, final int callingPid, final String tag, final int id,
            final Notification notification, int incomingUserId, boolean postSilently,
            PostNotificationTracker tracker, boolean byForegroundService) {
        if (DBG) {
            Slog.v(TAG, "enqueueNotificationInternal: pkg=" + pkg + " id=" + id
                    + " notification=" + notification);
        }

        if (pkg == null || notification == null) {
            throw new IllegalArgumentException("null not allowed: pkg=" + pkg
                    + " id=" + id + " notification=" + notification);
        }

        final int userId = ActivityManager.handleIncomingUser(callingPid,
                callingUid, incomingUserId, true, false, "enqueueNotification", pkg);
        final UserHandle user = UserHandle.of(userId);

        // Can throw a SecurityException if the calling uid doesn't have permission to post
        // as "pkg"
        final int notificationUid = resolveNotificationUid(opPkg, pkg, callingUid, userId);

        if (notificationUid == INVALID_UID) {
            throw new SecurityException("Caller " + opPkg + ":" + callingUid
                    + " trying to post for invalid pkg " + pkg + " in user " + incomingUserId);
        }

        checkRestrictedCategories(notification);

        // Notifications passed to setForegroundService() have FLAG_FOREGROUND_SERVICE,
        // but it's also possible that the app has called notify() with an update to an
        // FGS notification that hasn't yet been displayed.  Make sure we check for any
        // FGS-related situation up front, outside of any locks so it's safe to call into
        // the Activity Manager.
        final ServiceNotificationPolicy policy = mAmi.applyForegroundServiceNotification(
                notification, tag, id, pkg, userId);

        boolean stripUijFlag = true;
        final JobSchedulerInternal js = LocalServices.getService(JobSchedulerInternal.class);
        if (js != null) {
            stripUijFlag = !js.isNotificationAssociatedWithAnyUserInitiatedJobs(id, userId, pkg);
        }

        // Fix the notification as best we can.
        try {
            fixNotification(notification, pkg, tag, id, userId, notificationUid,
                    policy, stripUijFlag);
        } catch (Exception e) {
            if (notification.isForegroundService()) {
                throw new SecurityException("Invalid FGS notification", e);
            }
            Slog.e(TAG, "Cannot fix notification", e);
            return false;
        }

        if (policy == ServiceNotificationPolicy.UPDATE_ONLY) {
            // Proceed if the notification is already showing/known, otherwise ignore
            // because the service lifecycle logic has retained responsibility for its
            // handling.
            if (!isNotificationShownInternal(pkg, tag, id, userId)) {
                reportForegroundServiceUpdate(false, notification, id, pkg, userId);
                return false;
            }
        }

        mUsageStats.registerEnqueuedByApp(pkg);

        final StatusBarNotification n = new StatusBarNotification(
                pkg, opPkg, id, tag, notificationUid, callingPid, notification,
                user, null, System.currentTimeMillis());

        // setup local book-keeping
        String channelId = notification.getChannelId();
        if (mIsTelevision && (new Notification.TvExtender(notification)).getChannelId() != null) {
            channelId = (new Notification.TvExtender(notification)).getChannelId();
        }
        String shortcutId = n.getShortcutId();
        final NotificationChannel channel = mPreferencesHelper.getConversationNotificationChannel(
                pkg, notificationUid, channelId, shortcutId,
                true /* parent ok */, false /* includeDeleted */);
        if (channel == null) {
            final String noChannelStr = "No Channel found for "
                    + "pkg=" + pkg
                    + ", channelId=" + channelId
                    + ", id=" + id
                    + ", tag=" + tag
                    + ", opPkg=" + opPkg
                    + ", callingUid=" + callingUid
                    + ", userId=" + userId
                    + ", incomingUserId=" + incomingUserId
                    + ", notificationUid=" + notificationUid
                    + ", notification=" + notification;
            Slog.e(TAG, noChannelStr);
            boolean appNotificationsOff = !mPermissionHelper.hasPermission(notificationUid);


            if (!appNotificationsOff) {
                doChannelWarningToast(notificationUid,
                        "Developer warning for package \"" + pkg + "\"\n" +
                        "Failed to post notification on channel \"" + channelId + "\"\n" +
                        "See log for more details");
            }
            return false;
        }

        final NotificationRecord r = new NotificationRecord(getContext(), n, channel);
        r.setIsAppImportanceLocked(mPermissionHelper.isPermissionUserSet(pkg, userId));
        r.setPostSilently(postSilently);
        r.setFlagBubbleRemoved(false);
        r.setPkgAllowedAsConvo(mMsgPkgsAllowedAsConvos.contains(pkg));
        boolean isImportanceFixed = mPermissionHelper.isPermissionFixed(pkg, userId);
        r.setImportanceFixed(isImportanceFixed);

        if (notification.isFgsOrUij()) {
            if (((channel.getUserLockedFields() & NotificationChannel.USER_LOCKED_IMPORTANCE) == 0
                        || !channel.isUserVisibleTaskShown())
                    && (r.getImportance() == IMPORTANCE_MIN
                            || r.getImportance() == IMPORTANCE_NONE)) {
                // Increase the importance of fgs/uij notifications unless the user had
                // an opinion otherwise (and the channel hasn't yet shown a fgs/uij).
                channel.setImportance(IMPORTANCE_LOW);
                r.setSystemImportance(IMPORTANCE_LOW);
                if (!channel.isUserVisibleTaskShown()) {
                    channel.unlockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
                    channel.setUserVisibleTaskShown(true);
                }
                mPreferencesHelper.updateNotificationChannel(
                        pkg, notificationUid, channel, false, callingUid,
                        isCallerIsSystemOrSystemUi());
                r.updateNotificationChannel(channel);
            } else if (!channel.isUserVisibleTaskShown() && !TextUtils.isEmpty(channelId)
                    && !NotificationChannel.DEFAULT_CHANNEL_ID.equals(channelId)) {
                channel.setUserVisibleTaskShown(true);
                r.updateNotificationChannel(channel);
            }
        }

        ShortcutInfo info = mShortcutHelper != null
                ? mShortcutHelper.getValidShortcutInfo(notification.getShortcutId(), pkg, user)
                : null;
        if (notification.getShortcutId() != null && info == null) {
            Slog.w(TAG, "notification " + r.getKey() + " added an invalid shortcut");
        }
        r.setShortcutInfo(info);
        r.setHasSentValidMsg(mPreferencesHelper.hasSentValidMsg(pkg, notificationUid));
        r.userDemotedAppFromConvoSpace(
                mPreferencesHelper.hasUserDemotedInvalidMsgApp(pkg, notificationUid));

        if (!checkDisqualifyingFeatures(userId, notificationUid, id, tag, r,
                r.getSbn().getOverrideGroupKey() != null, byForegroundService)) {
            return false;
        }

        mUsageStats.registerEnqueuedByAppAndAccepted(pkg);

        if (info != null) {
            // Cache the shortcut synchronously after the associated notification is posted in case
            // the app unpublishes this shortcut immediately after posting the notification. If the
            // user does not modify the notification settings on this conversation, the shortcut
            // will be uncached by People Service when all the associated notifications are removed.
            mShortcutHelper.cacheShortcut(info, user);
        }

        // temporarily allow apps to perform extra work when their pending intents are launched
        if (notification.allPendingIntents != null) {
            final int intentCount = notification.allPendingIntents.size();
            if (intentCount > 0) {
                final long duration = LocalServices.getService(
                        DeviceIdleInternal.class).getNotificationAllowlistDuration();
                for (int i = 0; i < intentCount; i++) {
                    PendingIntent pendingIntent = notification.allPendingIntents.valueAt(i);
                    if (pendingIntent != null) {
                        mAmi.setPendingIntentAllowlistDuration(pendingIntent.getTarget(),
                                ALLOWLIST_TOKEN, duration,
                                TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                                REASON_NOTIFICATION_SERVICE,
                                "NotificationManagerService");
                        mAmi.setPendingIntentAllowBgActivityStarts(pendingIntent.getTarget(),
                                ALLOWLIST_TOKEN, (FLAG_ACTIVITY_SENDER | FLAG_BROADCAST_SENDER
                                        | FLAG_SERVICE_SENDER));
                    }
                }
            }
        }

        // Need escalated privileges to get package importance
        final long token = Binder.clearCallingIdentity();
        boolean isAppForeground;
        try {
            isAppForeground = mActivityManager.getPackageImportance(pkg) == IMPORTANCE_FOREGROUND;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        mHandler.post(new EnqueueNotificationRunnable(userId, r, isAppForeground, tracker));
        return true;
    }

    private void onConversationRemovedInternal(String pkg, int uid, Set<String> shortcuts) {
        checkCallerIsSystem();
        Preconditions.checkStringNotEmpty(pkg);

        mHistoryManager.deleteConversations(pkg, uid, shortcuts);
        List<String> deletedChannelIds =
                mPreferencesHelper.deleteConversations(pkg, uid, shortcuts,
                        /* callingUid */ Process.SYSTEM_UID, /* is system */ true);
        for (String channelId : deletedChannelIds) {
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channelId, 0, 0,
                    UserHandle.getUserId(uid), REASON_CHANNEL_REMOVED
            );
        }
        handleSavePolicyFile();
    }

    private void makeStickyHun(Notification notification, String pkg, @UserIdInt int userId) {
        if (mPermissionHelper.hasRequestedPermission(
                Manifest.permission.USE_FULL_SCREEN_INTENT, pkg, userId)) {
            notification.flags |= FLAG_FSI_REQUESTED_BUT_DENIED;
        }
        if (notification.contentIntent == null) {
            // On notification click, if contentIntent is null, SystemUI launches the
            // fullScreenIntent instead.
            notification.contentIntent = notification.fullScreenIntent;
        }
        notification.fullScreenIntent = null;
    }

    @VisibleForTesting
    protected void fixNotification(Notification notification, String pkg, String tag, int id,
            @UserIdInt int userId, int notificationUid,
            ServiceNotificationPolicy fgsPolicy, boolean stripUijFlag)
            throws NameNotFoundException, RemoteException {
        final ApplicationInfo ai = mPackageManagerClient.getApplicationInfoAsUser(
                pkg, PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                (userId == UserHandle.USER_ALL) ? USER_SYSTEM : userId);
        Notification.addFieldsFromContext(ai, notification);

        if (notification.isForegroundService() && fgsPolicy == NOT_FOREGROUND_SERVICE) {
            notification.flags &= ~FLAG_FOREGROUND_SERVICE;
        }
        if (notification.isUserInitiatedJob() && stripUijFlag) {
            notification.flags &= ~FLAG_USER_INITIATED_JOB;
        }

        // Remove FLAG_AUTO_CANCEL from notifications that are associated with a FGS or UIJ.
        if (notification.isFgsOrUij()) {
            notification.flags &= ~FLAG_AUTO_CANCEL;
        }

        // Only notifications that can be non-dismissible can have the flag FLAG_NO_DISMISS
        if (((notification.flags & FLAG_ONGOING_EVENT) > 0)
                && canBeNonDismissible(ai, notification)) {
            notification.flags |= FLAG_NO_DISMISS;
        } else {
            notification.flags &= ~FLAG_NO_DISMISS;
        }

        int canColorize = getContext().checkPermission(
                android.Manifest.permission.USE_COLORIZED_NOTIFICATIONS, -1, notificationUid);

        if (canColorize == PERMISSION_GRANTED) {
            notification.flags |= Notification.FLAG_CAN_COLORIZE;
        } else {
            notification.flags &= ~Notification.FLAG_CAN_COLORIZE;
        }

        if (notification.extras.getBoolean(Notification.EXTRA_ALLOW_DURING_SETUP, false)) {
            int hasShowDuringSetupPerm = getContext().checkPermission(
                    android.Manifest.permission.NOTIFICATION_DURING_SETUP, -1, notificationUid);
            if (hasShowDuringSetupPerm != PERMISSION_GRANTED) {
                notification.extras.remove(Notification.EXTRA_ALLOW_DURING_SETUP);
                if (DBG) {
                    Slog.w(TAG, "warning: pkg " + pkg + " attempting to show during setup"
                            + " without holding perm "
                            + Manifest.permission.NOTIFICATION_DURING_SETUP);
                }
            }
        }

        notification.flags &= ~FLAG_FSI_REQUESTED_BUT_DENIED;

        if (notification.fullScreenIntent != null) {
            final boolean forceDemoteFsiToStickyHun = mFlagResolver.isEnabled(
                    SystemUiSystemPropertiesFlags.NotificationFlags.FSI_FORCE_DEMOTE);
            if (forceDemoteFsiToStickyHun) {
                makeStickyHun(notification, pkg, userId);
            } else {
                final AttributionSource attributionSource =
                        new AttributionSource.Builder(notificationUid).setPackageName(pkg).build();
                final boolean showStickyHunIfDenied = mFlagResolver.isEnabled(
                        SystemUiSystemPropertiesFlags.NotificationFlags
                                .SHOW_STICKY_HUN_FOR_DENIED_FSI);
                final boolean canUseFullScreenIntent = checkUseFullScreenIntentPermission(
                        attributionSource, ai, showStickyHunIfDenied /* isAppOpPermission */,
                        true /* forDataDelivery */);
                if (!canUseFullScreenIntent) {
                    if (showStickyHunIfDenied) {
                        makeStickyHun(notification, pkg, userId);
                    } else {
                        notification.fullScreenIntent = null;
                        Slog.w(TAG, "Package " + pkg + ": Use of fullScreenIntent requires the"
                                + "USE_FULL_SCREEN_INTENT permission");
                    }
                }
            }
        }

        // Ensure all actions are present
        if (notification.actions != null) {
            boolean hasNullActions = false;
            int nActions = notification.actions.length;
            for (int i = 0; i < nActions; i++) {
                if (notification.actions[i] == null) {
                    hasNullActions = true;
                    break;
                }
            }
            if (hasNullActions) {
                ArrayList<Notification.Action> nonNullActions = new ArrayList<>();
                for (int i = 0; i < nActions; i++) {
                    if (notification.actions[i] != null) {
                        nonNullActions.add(notification.actions[i]);
                    }
                }
                if (nonNullActions.size() != 0) {
                    notification.actions = nonNullActions.toArray(new Notification.Action[0]);
                } else {
                    notification.actions = null;
                }
            }
        }

        // Ensure CallStyle has all the correct actions
        if (notification.isStyle(Notification.CallStyle.class)) {
            Notification.Builder builder =
                    Notification.Builder.recoverBuilder(getContext(), notification);
            Notification.CallStyle style = (Notification.CallStyle) builder.getStyle();
            List<Notification.Action> actions = style.getActionsListWithSystemActions();
            notification.actions = new Notification.Action[actions.size()];
            actions.toArray(notification.actions);
        }

        // Ensure MediaStyle has correct permissions for remote device extras
        if (notification.isStyle(Notification.MediaStyle.class)
                || notification.isStyle(Notification.DecoratedMediaCustomViewStyle.class)) {
            int hasMediaContentControlPermission = getContext().checkPermission(
                    android.Manifest.permission.MEDIA_CONTENT_CONTROL, -1, notificationUid);
            if (hasMediaContentControlPermission != PERMISSION_GRANTED) {
                notification.extras.remove(Notification.EXTRA_MEDIA_REMOTE_DEVICE);
                notification.extras.remove(Notification.EXTRA_MEDIA_REMOTE_ICON);
                notification.extras.remove(Notification.EXTRA_MEDIA_REMOTE_INTENT);
                if (DBG) {
                    Slog.w(TAG, "Package " + pkg + ": Use of setRemotePlayback requires the "
                            + "MEDIA_CONTENT_CONTROL permission");
                }
            }

            // Enforce NO_CLEAR flag on MediaStyle notification for apps with targetSdk >= V.
            if (CompatChanges.isChangeEnabled(ENFORCE_NO_CLEAR_FLAG_ON_MEDIA_NOTIFICATION,
                    notificationUid)) {
                notification.flags |= Notification.FLAG_NO_CLEAR;
            }
        }

        // Ensure only allowed packages have a substitute app name
        if (notification.extras.containsKey(Notification.EXTRA_SUBSTITUTE_APP_NAME)) {
            int hasSubstituteAppNamePermission = getContext().checkPermission(
                    permission.SUBSTITUTE_NOTIFICATION_APP_NAME, -1, notificationUid);
            if (hasSubstituteAppNamePermission != PERMISSION_GRANTED) {
                notification.extras.remove(Notification.EXTRA_SUBSTITUTE_APP_NAME);
                if (DBG) {
                    Slog.w(TAG, "warning: pkg " + pkg + " attempting to substitute app name"
                            + " without holding perm "
                            + Manifest.permission.SUBSTITUTE_NOTIFICATION_APP_NAME);
                }
            }
        }

        // Remote views? Are they too big?
        checkRemoteViews(pkg, tag, id, notification);
    }

    /**
     * Whether a notification can be non-dismissible.
     * A notification should be dismissible, unless it's exempted for some reason.
     */
    private boolean canBeNonDismissible(ApplicationInfo ai, Notification notification) {
        return notification.isMediaNotification() || isEnterpriseExempted(ai)
                || notification.isStyle(Notification.CallStyle.class)
                || isDefaultSearchSelectorPackage(ai.packageName);
    }

    private boolean isDefaultSearchSelectorPackage(String pkg) {
        return Objects.equals(mDefaultSearchSelectorPkg, pkg);
    }

    private boolean isEnterpriseExempted(ApplicationInfo ai) {
        // Check if the app is an organization admin app
        // TODO(b/234609037): Replace with new DPM APIs to check if organization admin
        if (mDpm != null && (mDpm.isActiveProfileOwner(ai.uid)
                || mDpm.isActiveDeviceOwner(ai.uid))) {
            return true;
        }
        // Check if an app has been given system exemption
        return mSystemExemptFromDismissal && mAppOps.checkOpNoThrow(
                AppOpsManager.OP_SYSTEM_EXEMPT_FROM_DISMISSIBLE_NOTIFICATIONS, ai.uid,
                ai.packageName) == AppOpsManager.MODE_ALLOWED;
    }

    private boolean checkUseFullScreenIntentPermission(@NonNull AttributionSource attributionSource,
            @NonNull ApplicationInfo applicationInfo, boolean isAppOpPermission,
            boolean forDataDelivery) {
        if (applicationInfo.targetSdkVersion < Build.VERSION_CODES.Q) {
            return true;
        }
        if (isAppOpPermission) {
            final int permissionResult;
            if (forDataDelivery) {
                permissionResult = mPermissionManager.checkPermissionForDataDelivery(
                        permission.USE_FULL_SCREEN_INTENT, attributionSource, /* message= */ null);
            } else {
                permissionResult = mPermissionManager.checkPermissionForPreflight(
                        permission.USE_FULL_SCREEN_INTENT, attributionSource);
            }
            return permissionResult == PermissionManager.PERMISSION_GRANTED;
        } else {
            final int permissionResult = getContext().checkPermission(
                    permission.USE_FULL_SCREEN_INTENT, attributionSource.getPid(),
                    attributionSource.getUid());
            return permissionResult == PERMISSION_GRANTED;
        }
    }

    private void checkRemoteViews(String pkg, String tag, int id, Notification notification) {
        if (removeRemoteView(pkg, tag, id, notification.contentView)) {
            notification.contentView = null;
        }
        if (removeRemoteView(pkg, tag, id, notification.bigContentView)) {
            notification.bigContentView = null;
        }
        if (removeRemoteView(pkg, tag, id, notification.headsUpContentView)) {
            notification.headsUpContentView = null;
        }
        if (notification.publicVersion != null) {
            if (removeRemoteView(pkg, tag, id, notification.publicVersion.contentView)) {
                notification.publicVersion.contentView = null;
            }
            if (removeRemoteView(pkg, tag, id, notification.publicVersion.bigContentView)) {
                notification.publicVersion.bigContentView = null;
            }
            if (removeRemoteView(pkg, tag, id, notification.publicVersion.headsUpContentView)) {
                notification.publicVersion.headsUpContentView = null;
            }
        }
    }

    private boolean removeRemoteView(String pkg, String tag, int id, RemoteViews contentView) {
        if (contentView == null) {
            return false;
        }
        final int contentViewSize = contentView.estimateMemoryUsage();
        if (contentViewSize > mWarnRemoteViewsSizeBytes
                && contentViewSize < mStripRemoteViewsSizeBytes) {
            Slog.w(TAG, "RemoteViews too large on pkg: " + pkg + " tag: " + tag + " id: " + id
                    + " this might be stripped in a future release");
        }
        if (contentViewSize >= mStripRemoteViewsSizeBytes) {
            mUsageStats.registerImageRemoved(pkg);
            Slog.w(TAG, "Removed too large RemoteViews (" + contentViewSize + " bytes) on pkg: "
                    + pkg + " tag: " + tag + " id: " + id);
            return true;
        }
        return false;
    }

    /**
     * Strips any flags from BubbleMetadata that wouldn't apply (e.g. app not foreground).
     */
    private void updateNotificationBubbleFlags(NotificationRecord r, boolean isAppForeground) {
        Notification notification = r.getNotification();
        Notification.BubbleMetadata metadata = notification.getBubbleMetadata();
        if (metadata == null) {
            // Nothing to update
            return;
        }
        if (!isAppForeground) {
            // Auto expand only works if foreground
            int flags = metadata.getFlags();
            flags &= ~Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE;
            metadata.setFlags(flags);
        }
        if (!metadata.isBubbleSuppressable()) {
            // If it's not suppressable remove the suppress flag
            int flags = metadata.getFlags();
            flags &= ~Notification.BubbleMetadata.FLAG_SUPPRESS_BUBBLE;
            metadata.setFlags(flags);
        }
    }

    private ShortcutHelper.ShortcutListener mShortcutListener =
            new ShortcutHelper.ShortcutListener() {
                @Override
                public void onShortcutRemoved(String key) {
                    String packageName;
                    synchronized (mNotificationLock) {
                        NotificationRecord r = mNotificationsByKey.get(key);
                        packageName = r != null ? r.getSbn().getPackageName() : null;
                    }
                    boolean isAppForeground = packageName != null
                            && mActivityManager.getPackageImportance(packageName)
                            == IMPORTANCE_FOREGROUND;
                    synchronized (mNotificationLock) {
                        NotificationRecord r = mNotificationsByKey.get(key);
                        if (r != null) {
                            r.setShortcutInfo(null);
                            // Enqueue will trigger resort & flag is updated that way.
                            r.getNotification().flags |= FLAG_ONLY_ALERT_ONCE;
                            mHandler.post(
                                    new NotificationManagerService.EnqueueNotificationRunnable(
                                            r.getUser().getIdentifier(), r, isAppForeground,
                                            mPostNotificationTrackerFactory.newTracker(null)));
                        }
                    }
                }
            };

    protected void doChannelWarningToast(int forUid, CharSequence toastText) {
        Binder.withCleanCallingIdentity(() -> {
            final boolean warningEnabled = Settings.Global.getInt(getContext().getContentResolver(),
                    Settings.Global.SHOW_NOTIFICATION_CHANNEL_WARNINGS, 0) != 0;
            if (warningEnabled) {
                Toast toast = Toast.makeText(getContext(), mHandler.getLooper(), toastText,
                        Toast.LENGTH_SHORT);
                toast.show();
            }
        });
    }

    @VisibleForTesting
    int resolveNotificationUid(String callingPkg, String targetPkg, int callingUid, int userId) {
        if (userId == UserHandle.USER_ALL) {
            userId = USER_SYSTEM;
        }
        // posted from app A on behalf of app A
        if (isCallerSameApp(targetPkg, callingUid, userId)
                && (TextUtils.equals(callingPkg, targetPkg)
                || isCallerSameApp(callingPkg, callingUid, userId))) {
            return callingUid;
        }

        int targetUid = INVALID_UID;
        try {
            targetUid = mPackageManagerClient.getPackageUidAsUser(targetPkg, userId);
        } catch (NameNotFoundException e) {
            /* ignore, handled by caller */
        }
        // posted from app A on behalf of app B
        if (isCallerAndroid(callingPkg, callingUid)
                || mPreferencesHelper.isDelegateAllowed(
                        targetPkg, targetUid, callingPkg, callingUid)) {
            return targetUid;
        }

        throw new SecurityException("Caller " + callingPkg + ":" + callingUid
                + " cannot post for pkg " + targetPkg + " in user " + userId);
    }

    public boolean hasFlag(final int flags, final int flag) {
        return (flags & flag) != 0;
    }
    /**
     * Checks if a notification can be posted. checks rate limiter, snooze helper, and blocking.
     *
     * Has side effects.
     */
    boolean checkDisqualifyingFeatures(int userId, int uid, int id, String tag,
            NotificationRecord r, boolean isAutogroup, boolean byForegroundService) {
        Notification n = r.getNotification();
        final String pkg = r.getSbn().getPackageName();
        final boolean isSystemNotification =
                isUidSystemOrPhone(uid) || ("android".equals(pkg));
        final boolean isNotificationFromListener = mListeners.isListenerPackage(pkg);

        // Limit the number of notifications that any given package except the android
        // package or a registered listener can enqueue.  Prevents DOS attacks and deals with leaks.
        if (!isSystemNotification && !isNotificationFromListener) {
            final int callingUid = Binder.getCallingUid();
            synchronized (mNotificationLock) {
                if (mNotificationsByKey.get(r.getSbn().getKey()) == null
                        && isCallerInstantApp(callingUid, userId)) {
                    // Ephemeral apps have some special constraints for notifications.
                    // They are not allowed to create new notifications however they are allowed to
                    // update notifications created by the system (e.g. a foreground service
                    // notification).
                    throw new SecurityException("Instant app " + pkg
                            + " cannot create notifications");
                }

                // Rate limit updates that aren't completed progress notifications
                // Search for the original one in the posted and not-yet-posted (enqueued) lists.
                boolean isUpdate = mNotificationsByKey.get(r.getSbn().getKey()) != null
                        || findNotificationByListLocked(mEnqueuedNotifications, r.getSbn().getKey())
                        != null;
                if (isUpdate && !r.getNotification().hasCompletedProgress() && !isAutogroup) {
                    final float appEnqueueRate = mUsageStats.getAppEnqueueRate(pkg);
                    if (appEnqueueRate > mMaxPackageEnqueueRate) {
                        mUsageStats.registerOverRateQuota(pkg);
                        final long now = SystemClock.elapsedRealtime();
                        if ((now - mLastOverRateLogTime) > MIN_PACKAGE_OVERRATE_LOG_INTERVAL) {
                            Slog.e(TAG, "Package enqueue rate is " + appEnqueueRate
                                    + ". Shedding " + r.getSbn().getKey() + ". package=" + pkg);
                            mLastOverRateLogTime = now;
                        }
                        return false;
                    }
                }
            }

            // limit the number of non-fgs/uij outstanding notificationrecords an app can have
            if (!n.isFgsOrUij()) {
                int count = getNotificationCount(pkg, userId, id, tag);
                if (count >= MAX_PACKAGE_NOTIFICATIONS) {
                    mUsageStats.registerOverCountQuota(pkg);
                    Slog.e(TAG, "Package has already posted or enqueued " + count
                            + " notifications.  Not showing more.  package=" + pkg);
                    return false;
                }
            }
        }

        // bubble or inline reply that's immutable?
        if (n.getBubbleMetadata() != null
                && n.getBubbleMetadata().getIntent() != null
                && hasFlag(mAmi.getPendingIntentFlags(
                        n.getBubbleMetadata().getIntent().getTarget()),
                        PendingIntent.FLAG_IMMUTABLE)) {
            throw new IllegalArgumentException(r.getKey() + " Not posted."
                    + " PendingIntents attached to bubbles must be mutable");
        }

        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if ((action.getRemoteInputs() != null || action.getDataOnlyRemoteInputs() != null)
                        && hasFlag(mAmi.getPendingIntentFlags(action.actionIntent.getTarget()),
                        PendingIntent.FLAG_IMMUTABLE)) {
                    throw new IllegalArgumentException(r.getKey() + " Not posted."
                            + " PendingIntents attached to actions with remote"
                            + " inputs must be mutable");
                }
            }
        }

        if (r.getSystemGeneratedSmartActions() != null) {
            for (Notification.Action action : r.getSystemGeneratedSmartActions()) {
                if ((action.getRemoteInputs() != null || action.getDataOnlyRemoteInputs() != null)
                        && hasFlag(mAmi.getPendingIntentFlags(action.actionIntent.getTarget()),
                        PendingIntent.FLAG_IMMUTABLE)) {
                    throw new IllegalArgumentException(r.getKey() + " Not posted."
                            + " PendingIntents attached to contextual actions with remote inputs"
                            + " must be mutable");
                }
            }
        }

        if (n.isStyle(Notification.CallStyle.class)) {
            boolean hasFullScreenIntent = n.fullScreenIntent != null;
            boolean requestedFullScreenIntent = (n.flags & FLAG_FSI_REQUESTED_BUT_DENIED) != 0;
            if (!n.isFgsOrUij() && !hasFullScreenIntent && !requestedFullScreenIntent
                    && !byForegroundService) {
                throw new IllegalArgumentException(r.getKey() + " Not posted."
                        + " CallStyle notifications must be for a foreground service or"
                        + " user initated job or use a fullScreenIntent.");
            }
        }

        // snoozed apps
        if (mSnoozeHelper.isSnoozed(userId, pkg, r.getKey())) {
            MetricsLogger.action(r.getLogMaker()
                    .setType(MetricsProto.MetricsEvent.TYPE_UPDATE)
                    .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED));
            mNotificationRecordLogger.log(
                    NotificationRecordLogger.NotificationEvent.NOTIFICATION_NOT_POSTED_SNOOZED,
                    r);
            if (DBG) {
                Slog.d(TAG, "Ignored enqueue for snoozed notification " + r.getKey());
            }
            mSnoozeHelper.update(userId, r);
            handleSavePolicyFile();
            return false;
        }

        // blocked apps
        boolean isBlocked = !areNotificationsEnabledForPackageInt(pkg, uid);
        synchronized (mNotificationLock) {
            isBlocked |= isRecordBlockedLocked(r);
        }
        if (isBlocked && !(n.isMediaNotification() || isCallNotification(pkg, uid, n))) {
            if (DBG) {
                Slog.e(TAG, "Suppressing notification from package " + r.getSbn().getPackageName()
                        + " by user request.");
            }
            mUsageStats.registerBlocked(r);
            return false;
        }

        return true;
    }

    private boolean isCallNotification(String pkg, int uid, Notification n) {
        if (n.isStyle(Notification.CallStyle.class)) {
            return isCallNotification(pkg, uid);
        }
        return false;
    }

    private boolean isCallNotification(String pkg, int uid) {
        final long identity = Binder.clearCallingIdentity();
        try {
            if (mPackageManagerClient.hasSystemFeature(FEATURE_TELECOM)
                    && mTelecomManager != null) {
                try {
                    return mTelecomManager.isInManagedCall()
                            || mTelecomManager.isInSelfManagedCall(
                            pkg, UserHandle.getUserHandleForUid(uid));
                } catch (IllegalStateException ise) {
                    // Telecom is not ready (this is likely early boot), so there are no calls.
                    return false;
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean areNotificationsEnabledForPackageInt(String pkg, int uid) {
        return mPermissionHelper.hasPermission(uid);
    }

    private int getNotificationCount(String pkg, int userId) {
        int count = 0;
        synchronized (mNotificationLock) {
            final int numListSize = mNotificationList.size();
            for (int i = 0; i < numListSize; i++) {
                final NotificationRecord existing = mNotificationList.get(i);
                if (existing.getSbn().getPackageName().equals(pkg)
                        && existing.getSbn().getUserId() == userId) {
                    count++;
                }
            }
            final int numEnqSize = mEnqueuedNotifications.size();
            for (int i = 0; i < numEnqSize; i++) {
                final NotificationRecord existing = mEnqueuedNotifications.get(i);
                if (existing.getSbn().getPackageName().equals(pkg)
                        && existing.getSbn().getUserId() == userId) {
                    count++;
                }
            }
        }
        return count;
    }

    protected int getNotificationCount(String pkg, int userId, int excludedId,
            String excludedTag) {
        int count = 0;
        synchronized (mNotificationLock) {
            final int N = mNotificationList.size();
            for (int i = 0; i < N; i++) {
                final NotificationRecord existing = mNotificationList.get(i);
                if (existing.getSbn().getPackageName().equals(pkg)
                        && existing.getSbn().getUserId() == userId) {
                    if (existing.getSbn().getId() == excludedId
                            && TextUtils.equals(existing.getSbn().getTag(), excludedTag)) {
                        continue;
                    }
                    count++;
                }
            }
            final int M = mEnqueuedNotifications.size();
            for (int i = 0; i < M; i++) {
                final NotificationRecord existing = mEnqueuedNotifications.get(i);
                if (existing.getSbn().getPackageName().equals(pkg)
                        && existing.getSbn().getUserId() == userId) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Checks whether a notification is banned at a group or channel level or if the NAS or system
     * has blocked the notification.
     */
    @GuardedBy("mNotificationLock")
    boolean isRecordBlockedLocked(NotificationRecord r) {
        final String pkg = r.getSbn().getPackageName();
        final int callingUid = r.getSbn().getUid();
        return mPreferencesHelper.isGroupBlocked(pkg, callingUid, r.getChannel().getGroup())
                || r.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    protected class SnoozeNotificationRunnable implements Runnable {
        private final String mKey;
        private final long mDuration;
        private final String mSnoozeCriterionId;

        SnoozeNotificationRunnable(String key, long duration, String snoozeCriterionId) {
            mKey = key;
            mDuration = duration;
            mSnoozeCriterionId = snoozeCriterionId;
        }

        @Override
        public void run() {
            synchronized (mNotificationLock) {
                final NotificationRecord r = findInCurrentAndSnoozedNotificationByKeyLocked(mKey);
                if (r != null) {
                    snoozeLocked(r);
                }
            }
        }

        @GuardedBy("mNotificationLock")
        void snoozeLocked(NotificationRecord r) {
            final List<NotificationRecord> recordsToSnooze = new ArrayList<>();
            if (r.getSbn().isGroup()) {
                final List<NotificationRecord> groupNotifications =
                        findCurrentAndSnoozedGroupNotificationsLocked(
                        r.getSbn().getPackageName(),
                                r.getSbn().getGroupKey(), r.getSbn().getUserId());
                if (r.getNotification().isGroupSummary()) {
                    // snooze all children
                    for (int i = 0; i < groupNotifications.size(); i++) {
                        if (!mKey.equals(groupNotifications.get(i).getKey())) {
                            recordsToSnooze.add(groupNotifications.get(i));
                        }
                    }
                } else {
                    // if there is a valid summary for this group, and we are snoozing the only
                    // child, also snooze the summary
                    if (mSummaryByGroupKey.containsKey(r.getSbn().getGroupKey())) {
                        if (groupNotifications.size() == 2) {
                            // snooze summary and the one child
                            for (int i = 0; i < groupNotifications.size(); i++) {
                                if (!mKey.equals(groupNotifications.get(i).getKey())) {
                                    recordsToSnooze.add(groupNotifications.get(i));
                                }
                            }
                        }
                    }
                }
            }
            // snooze the notification
            recordsToSnooze.add(r);

            if (mSnoozeHelper.canSnooze(recordsToSnooze.size())) {
                for (int i = 0; i < recordsToSnooze.size(); i++) {
                    snoozeNotificationLocked(recordsToSnooze.get(i));
                }
            } else {
                Log.w(TAG, "Cannot snooze " + r.getKey() + ": too many snoozed notifications");
            }
        }

        @GuardedBy("mNotificationLock")
        void snoozeNotificationLocked(NotificationRecord r) {
            MetricsLogger.action(r.getLogMaker()
                    .setCategory(MetricsEvent.NOTIFICATION_SNOOZED)
                    .setType(MetricsEvent.TYPE_CLOSE)
                    .addTaggedData(MetricsEvent.FIELD_NOTIFICATION_SNOOZE_DURATION_MS,
                            mDuration)
                    .addTaggedData(MetricsEvent.NOTIFICATION_SNOOZED_CRITERIA,
                            mSnoozeCriterionId == null ? 0 : 1));
            mNotificationRecordLogger.log(
                    NotificationRecordLogger.NotificationEvent.NOTIFICATION_SNOOZED, r);
            reportUserInteraction(r);
            boolean wasPosted = removeFromNotificationListsLocked(r);
            cancelNotificationLocked(r, false, REASON_SNOOZED, wasPosted, null,
                    SystemClock.elapsedRealtime());
            if (Flags.refactorAttentionHelper()) {
                mAttentionHelper.updateLightsLocked();
            } else {
                updateLightsLocked();
            }
            if (isSnoozable(r)) {
                if (mSnoozeCriterionId != null) {
                    mAssistants.notifyAssistantSnoozedLocked(r, mSnoozeCriterionId);
                    mSnoozeHelper.snooze(r, mSnoozeCriterionId);
                } else {
                    mSnoozeHelper.snooze(r, mDuration);
                }
                r.recordSnoozed();
                handleSavePolicyFile();
            }
        }

        /**
         * Autogroup summaries are not snoozable
         * They will be recreated as needed when the group children are unsnoozed
         */
        private boolean isSnoozable(NotificationRecord record) {
            return !(record.getNotification().isGroupSummary() && GroupHelper.AUTOGROUP_KEY.equals(
                    record.getNotification().getGroup()));
        }
    }

    private void unsnoozeAll() {
        synchronized (mNotificationLock) {
            mSnoozeHelper.repostAll(mUserProfiles.getCurrentProfileIds());
            handleSavePolicyFile();
        }
    }

    protected class CancelNotificationRunnable implements Runnable {
        private final int mCallingUid;
        private final int mCallingPid;
        private final String mPkg;
        private final String mTag;
        private final int mId;
        private final int mMustHaveFlags;
        private final int mMustNotHaveFlags;
        private final boolean mSendDelete;
        private final int mUserId;
        private final int mReason;
        private final int mRank;
        private final int mCount;
        private final ManagedServiceInfo mListener;
        private final long mCancellationElapsedTimeMs;

        CancelNotificationRunnable(final int callingUid, final int callingPid,
                final String pkg, final String tag, final int id,
                final int mustHaveFlags, final int mustNotHaveFlags, final boolean sendDelete,
                final int userId, final int reason, int rank, int count,
                final ManagedServiceInfo listener,
                @ElapsedRealtimeLong long cancellationElapsedTimeMs) {
            this.mCallingUid = callingUid;
            this.mCallingPid = callingPid;
            this.mPkg = pkg;
            this.mTag = tag;
            this.mId = id;
            this.mMustHaveFlags = mustHaveFlags;
            this.mMustNotHaveFlags = mustNotHaveFlags;
            this.mSendDelete = sendDelete;
            this.mUserId = userId;
            this.mReason = reason;
            this.mRank = rank;
            this.mCount = count;
            this.mListener = listener;
            this.mCancellationElapsedTimeMs = cancellationElapsedTimeMs;
        }

        @Override
        public void run() {
            String listenerName = mListener == null ? null : mListener.component.toShortString();
            if (DBG) {
                EventLogTags.writeNotificationCancel(mCallingUid, mCallingPid, mPkg, mId, mTag,
                        mUserId, mMustHaveFlags, mMustNotHaveFlags, mReason, listenerName);
            }

            synchronized (mNotificationLock) {
                // Look for the notification, searching both the posted and enqueued lists.
                NotificationRecord r = findNotificationLocked(mPkg, mTag, mId, mUserId);
                if (r != null) {
                    // The notification was found, check if it should be removed.

                    // Ideally we'd do this in the caller of this method. However, that would
                    // require the caller to also find the notification.
                    if (mReason == REASON_CLICK) {
                        mUsageStats.registerClickedByUser(r);
                    }

                    if ((mReason == REASON_LISTENER_CANCEL
                            && r.getNotification().isBubbleNotification())
                            || (mReason == REASON_CLICK && r.canBubble()
                            && r.isFlagBubbleRemoved())) {
                        int flags = 0;
                        if (r.getNotification().getBubbleMetadata() != null) {
                            flags = r.getNotification().getBubbleMetadata().getFlags();
                        }
                        flags |= FLAG_SUPPRESS_NOTIFICATION;
                        mNotificationDelegate.onBubbleMetadataFlagChanged(r.getKey(), flags);
                        return;
                    }
                    if ((r.getNotification().flags & mMustHaveFlags) != mMustHaveFlags) {
                        return;
                    }
                    if ((r.getNotification().flags & mMustNotHaveFlags) != 0) {
                        return;
                    }

                    FlagChecker childrenFlagChecker = (flags) -> {
                            if (mReason == REASON_CANCEL
                                    || mReason == REASON_CLICK
                                    || mReason == REASON_CANCEL_ALL) {
                                // Bubbled children get to stick around if the summary was manually
                                // cancelled (user removed) from systemui.
                                if ((flags & FLAG_BUBBLE) != 0) {
                                    return false;
                                }
                            } else if (mReason == REASON_APP_CANCEL) {
                                if ((flags & FLAG_FOREGROUND_SERVICE) != 0
                                        || (flags & FLAG_USER_INITIATED_JOB) != 0) {
                                    return false;
                                }
                            }
                            if ((flags & mMustNotHaveFlags) != 0) {
                                return false;
                            }
                            return true;
                        };

                    // Cancel the notification.
                    boolean wasPosted = removeFromNotificationListsLocked(r);
                    cancelNotificationLocked(
                            r, mSendDelete, mReason, mRank, mCount, wasPosted, listenerName,
                            mCancellationElapsedTimeMs);
                    cancelGroupChildrenLocked(r, mCallingUid, mCallingPid, listenerName,
                            mSendDelete, childrenFlagChecker, mReason,
                            mCancellationElapsedTimeMs);
                    if (Flags.refactorAttentionHelper()) {
                        mAttentionHelper.updateLightsLocked();
                    } else {
                        updateLightsLocked();
                    }
                    if (mShortcutHelper != null) {
                        mShortcutHelper.maybeListenForShortcutChangesForBubbles(r,
                                true /* isRemoved */,
                                mHandler);
                    }
                } else {
                    // No notification was found, assume that it is snoozed and cancel it.
                    if (mReason != REASON_SNOOZED) {
                        final boolean wasSnoozed = mSnoozeHelper.cancel(mUserId, mPkg, mTag, mId);
                        if (wasSnoozed) {
                            handleSavePolicyFile();
                        }
                    }
                }
            }
        }
    }

    protected static class ShowNotificationPermissionPromptRunnable implements Runnable {
        private final String mPkgName;
        private final int mUserId;
        private final int mTaskId;
        private final PermissionPolicyInternal mPpi;

        ShowNotificationPermissionPromptRunnable(String pkg, int user, int task,
                PermissionPolicyInternal pPi) {
            mPkgName = pkg;
            mUserId = user;
            mTaskId = task;
            mPpi = pPi;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ShowNotificationPermissionPromptRunnable)) {
                return false;
            }

            ShowNotificationPermissionPromptRunnable other =
                    (ShowNotificationPermissionPromptRunnable) o;

            return Objects.equals(mPkgName, other.mPkgName) && mUserId == other.mUserId
                    && mTaskId == other.mTaskId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPkgName, mUserId, mTaskId);
        }

        @Override
        public void run() {
            mPpi.showNotificationPromptIfNeeded(mPkgName, mUserId, mTaskId);
        }
    }

    protected class EnqueueNotificationRunnable implements Runnable {
        private final NotificationRecord r;
        private final int userId;
        private final boolean isAppForeground;
        private final PostNotificationTracker mTracker;

        EnqueueNotificationRunnable(int userId, NotificationRecord r, boolean foreground,
                PostNotificationTracker tracker) {
            this.userId = userId;
            this.r = r;
            this.isAppForeground = foreground;
            this.mTracker = checkNotNull(tracker);
        }

        @Override
        public void run() {
            boolean enqueued = false;
            try {
                enqueued = enqueueNotification();
            } finally {
                if (!enqueued) {
                    mTracker.cancel();
                }
            }
        }

        /**
         * @return True if we successfully enqueued the notification and handed off the task of
         * posting it to a background thread; false otherwise.
         */
        private boolean enqueueNotification() {
            synchronized (mNotificationLock) {
                final long snoozeAt =
                        mSnoozeHelper.getSnoozeTimeForUnpostedNotification(
                                r.getUser().getIdentifier(),
                                r.getSbn().getPackageName(), r.getSbn().getKey());
                final long currentTime = System.currentTimeMillis();
                if (snoozeAt > currentTime) {
                    (new SnoozeNotificationRunnable(r.getSbn().getKey(),
                            snoozeAt - currentTime, null)).snoozeLocked(r);
                    return false;
                }

                final String contextId =
                        mSnoozeHelper.getSnoozeContextForUnpostedNotification(
                                r.getUser().getIdentifier(),
                                r.getSbn().getPackageName(), r.getSbn().getKey());
                if (contextId != null) {
                    (new SnoozeNotificationRunnable(r.getSbn().getKey(),
                            0, contextId)).snoozeLocked(r);
                    return false;
                }

                mEnqueuedNotifications.add(r);
                scheduleTimeoutLocked(r);

                final StatusBarNotification n = r.getSbn();
                if (DBG) Slog.d(TAG, "EnqueueNotificationRunnable.run for: " + n.getKey());
                NotificationRecord old = mNotificationsByKey.get(n.getKey());
                if (old != null) {
                    // Retain ranking information from previous record
                    r.copyRankingInformation(old);
                }

                final int callingUid = n.getUid();
                final int callingPid = n.getInitialPid();
                final Notification notification = n.getNotification();
                final String pkg = n.getPackageName();
                final int id = n.getId();
                final String tag = n.getTag();

                // We need to fix the notification up a little for bubbles
                updateNotificationBubbleFlags(r, isAppForeground);

                // Handle grouped notifications and bail out early if we
                // can to avoid extracting signals.
                handleGroupedNotificationLocked(r, old, callingUid, callingPid);

                // if this is a group child, unsnooze parent summary
                if (n.isGroup() && notification.isGroupChild()) {
                    mSnoozeHelper.repostGroupSummary(pkg, r.getUserId(), n.getGroupKey());
                }

                // This conditional is a dirty hack to limit the logging done on
                //     behalf of the download manager without affecting other apps.
                if (!pkg.equals("com.android.providers.downloads")
                        || Log.isLoggable("DownloadManager", Log.VERBOSE)) {
                    int enqueueStatus = EVENTLOG_ENQUEUE_STATUS_NEW;
                    if (old != null) {
                        enqueueStatus = EVENTLOG_ENQUEUE_STATUS_UPDATE;
                    }
                    EventLogTags.writeNotificationEnqueue(callingUid, callingPid,
                            pkg, id, tag, userId, notification.toString(),
                            enqueueStatus);
                }

                // tell the assistant service about the notification
                if (mAssistants.isEnabled()) {
                    mAssistants.onNotificationEnqueuedLocked(r);
                    mHandler.postDelayed(
                            new PostNotificationRunnable(r.getKey(), r.getSbn().getPackageName(),
                                    r.getUid(), mTracker),
                            DELAY_FOR_ASSISTANT_TIME);
                } else {
                    mHandler.post(
                            new PostNotificationRunnable(r.getKey(), r.getSbn().getPackageName(),
                                    r.getUid(), mTracker));
                }
                return true;
            }
        }
    }

    @GuardedBy("mNotificationLock")
    boolean isPackagePausedOrSuspended(String pkg, int uid) {
        boolean isPaused;

        final PackageManagerInternal pmi = LocalServices.getService(
                PackageManagerInternal.class);
        int flags = pmi.getDistractingPackageRestrictions(
                pkg, Binder.getCallingUserHandle().getIdentifier());
        isPaused = ((flags & PackageManager.RESTRICTION_HIDE_NOTIFICATIONS) != 0);

        isPaused |= isPackageSuspendedForUser(pkg, uid);

        return isPaused;
    }

    protected class PostNotificationRunnable implements Runnable {
        private final String key;
        private final String pkg;
        private final int uid;
        private final PostNotificationTracker mTracker;

        PostNotificationRunnable(String key, String pkg, int uid, PostNotificationTracker tracker) {
            this.key = key;
            this.pkg = pkg;
            this.uid = uid;
            this.mTracker = checkNotNull(tracker);
        }

        @Override
        public void run() {
            boolean posted = false;
            try {
                posted = postNotification();
            } finally {
                if (!posted) {
                    mTracker.cancel();
                }
            }
        }

        /**
         * @return True if we successfully processed the notification and handed off the task of
         * notifying all listeners to a background thread; false otherwise.
         */
        private boolean postNotification() {
            boolean appBanned = !areNotificationsEnabledForPackageInt(pkg, uid);
            boolean isCallNotification = isCallNotification(pkg, uid);
            boolean posted = false;
            synchronized (mNotificationLock) {
                try {
                    NotificationRecord r = findNotificationByListLocked(mEnqueuedNotifications,
                            key);
                    if (r == null) {
                        Slog.i(TAG, "Cannot find enqueued record for key: " + key);
                        return false;
                    }

                    final StatusBarNotification n = r.getSbn();
                    final Notification notification = n.getNotification();
                    boolean isCallNotificationAndCorrectStyle = isCallNotification
                            && notification.isStyle(Notification.CallStyle.class);

                    if (!(notification.isMediaNotification() || isCallNotificationAndCorrectStyle)
                            && (appBanned || isRecordBlockedLocked(r))) {
                        mUsageStats.registerBlocked(r);
                        if (DBG) {
                            Slog.e(TAG, "Suppressing notification from package " + pkg);
                        }
                        return false;
                    }

                    final boolean isPackageSuspended =
                            isPackagePausedOrSuspended(r.getSbn().getPackageName(), r.getUid());
                    r.setHidden(isPackageSuspended);
                    if (isPackageSuspended) {
                        mUsageStats.registerSuspendedByAdmin(r);
                    }
                    NotificationRecord old = mNotificationsByKey.get(key);

                    // Make sure the SBN has an instance ID for statsd logging.
                    if (old == null || old.getSbn().getInstanceId() == null) {
                        n.setInstanceId(mNotificationInstanceIdSequence.newInstanceId());
                    } else {
                        n.setInstanceId(old.getSbn().getInstanceId());
                    }

                    int index = indexOfNotificationLocked(n.getKey());
                    if (index < 0) {
                        mNotificationList.add(r);
                        mUsageStats.registerPostedByApp(r);
                        mUsageStatsManagerInternal.reportNotificationPosted(r.getSbn().getOpPkg(),
                                r.getSbn().getUser(), mTracker.getStartTime());
                        final boolean isInterruptive = isVisuallyInterruptive(null, r);
                        r.setInterruptive(isInterruptive);
                        r.setTextChanged(isInterruptive);
                    } else {
                        old = mNotificationList.get(index);  // Potentially *changes* old
                        mNotificationList.set(index, r);
                        mUsageStats.registerUpdatedByApp(r, old);
                        mUsageStatsManagerInternal.reportNotificationUpdated(r.getSbn().getOpPkg(),
                                r.getSbn().getUser(), mTracker.getStartTime());
                        // Make sure we don't lose the foreground service state.
                        notification.flags |=
                                old.getNotification().flags & FLAG_FOREGROUND_SERVICE;
                        r.isUpdate = true;
                        final boolean isInterruptive = isVisuallyInterruptive(old, r);
                        r.setTextChanged(isInterruptive);
                    }

                    mNotificationsByKey.put(n.getKey(), r);

                    // Ensure if this is a foreground service that the proper additional
                    // flags are set.
                    if ((notification.flags & FLAG_FOREGROUND_SERVICE) != 0) {
                        notification.flags |= FLAG_NO_CLEAR;
                    }

                    mRankingHelper.extractSignals(r);
                    mRankingHelper.sort(mNotificationList);
                    final int position = mRankingHelper.indexOf(mNotificationList, r);

                    int buzzBeepBlinkLoggingCode = 0;
                    if (!r.isHidden()) {
                        if (Flags.refactorAttentionHelper()) {
                            buzzBeepBlinkLoggingCode = mAttentionHelper.buzzBeepBlinkLocked(r,
                                new NotificationAttentionHelper.Signals(
                                    mUserProfiles.isCurrentProfile(r.getUserId()),
                                    mListenerHints));
                        } else {
                            buzzBeepBlinkLoggingCode = buzzBeepBlinkLocked(r);
                        }
                    }

                    if (notification.getSmallIcon() != null) {
                        NotificationRecordLogger.NotificationReported maybeReport =
                                mNotificationRecordLogger.prepareToLogNotificationPosted(r, old,
                                        position, buzzBeepBlinkLoggingCode,
                                        getGroupInstanceId(r.getSbn().getGroupKey()));
                        notifyListenersPostedAndLogLocked(r, old, mTracker, maybeReport);
                        posted = true;

                        StatusBarNotification oldSbn = (old != null) ? old.getSbn() : null;
                        if (oldSbn == null
                                || !Objects.equals(oldSbn.getGroup(), n.getGroup())
                                || oldSbn.getNotification().flags != n.getNotification().flags) {
                            if (!isCritical(r)) {
                                mHandler.post(() -> {
                                    synchronized (mNotificationLock) {
                                        mGroupHelper.onNotificationPosted(
                                                n, hasAutoGroupSummaryLocked(n));
                                    }
                                });
                            }
                        }
                    } else {
                        Slog.e(TAG, "Not posting notification without small icon: " + notification);
                        if (old != null && !old.isCanceled) {
                            mListeners.notifyRemovedLocked(r,
                                    NotificationListenerService.REASON_ERROR, r.getStats());
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mGroupHelper.onNotificationRemoved(n);
                                }
                            });
                        }
                        // ATTENTION: in a future release we will bail out here
                        // so that we do not play sounds, show lights, etc. for invalid
                        // notifications
                        Slog.e(TAG, "WARNING: In a future release this will crash the app: "
                                + n.getPackageName());
                    }

                    if (mShortcutHelper != null) {
                        mShortcutHelper.maybeListenForShortcutChangesForBubbles(r,
                                false /* isRemoved */,
                                mHandler);
                    }

                    maybeRecordInterruptionLocked(r);
                    maybeRegisterMessageSent(r);
                    maybeReportForegroundServiceUpdate(r, true);
                } finally {
                    int N = mEnqueuedNotifications.size();
                    for (int i = 0; i < N; i++) {
                        final NotificationRecord enqueued = mEnqueuedNotifications.get(i);
                        if (Objects.equals(key, enqueued.getKey())) {
                            mEnqueuedNotifications.remove(i);
                            break;
                        }
                    }
                }
            }
            return posted;
        }
    }

    /**
     *
     */
    @GuardedBy("mNotificationLock")
    InstanceId getGroupInstanceId(String groupKey) {
        if (groupKey == null) {
            return null;
        }
        NotificationRecord group = mSummaryByGroupKey.get(groupKey);
        if (group == null) {
            return null;
        }
        return group.getSbn().getInstanceId();
    }

    /**
     * If the notification differs enough visually, consider it a new interruptive notification.
     */
    @GuardedBy("mNotificationLock")
    @VisibleForTesting
    protected boolean isVisuallyInterruptive(@Nullable NotificationRecord old,
            @NonNull NotificationRecord r) {
        // Ignore summary updates because we don't display most of the information.
        if (r.getSbn().isGroup() && r.getSbn().getNotification().isGroupSummary()) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is not interruptive: summary");
            }
            return false;
        }

        if (old == null) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is interruptive: new notification");
            }
            return true;
        }

        Notification oldN = old.getSbn().getNotification();
        Notification newN = r.getSbn().getNotification();
        if (oldN.extras == null || newN.extras == null) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is not interruptive: no extras");
            }
            return false;
        }

        // Ignore visual interruptions from foreground services because users
        // consider them one 'session'. Count them for everything else.
        if ((r.getSbn().getNotification().flags & FLAG_FOREGROUND_SERVICE) != 0) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is not interruptive: foreground service");
            }
            return false;
        }

        final String oldTitle = String.valueOf(oldN.extras.get(Notification.EXTRA_TITLE));
        final String newTitle = String.valueOf(newN.extras.get(Notification.EXTRA_TITLE));
        if (!Objects.equals(oldTitle, newTitle)) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is interruptive: changed title");
                Slog.v(TAG, "INTERRUPTIVENESS: " + String.format("   old title: %s (%s@0x%08x)",
                        oldTitle, oldTitle.getClass(), oldTitle.hashCode()));
                Slog.v(TAG, "INTERRUPTIVENESS: " + String.format("   new title: %s (%s@0x%08x)",
                        newTitle, newTitle.getClass(), newTitle.hashCode()));
            }
            return true;
        }

        // Do not compare Spannables (will always return false); compare unstyled Strings
        final String oldText = String.valueOf(oldN.extras.get(Notification.EXTRA_TEXT));
        final String newText = String.valueOf(newN.extras.get(Notification.EXTRA_TEXT));
        if (!Objects.equals(oldText, newText)) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        + r.getKey() + " is interruptive: changed text");
                Slog.v(TAG, "INTERRUPTIVENESS: " + String.format("   old text: %s (%s@0x%08x)",
                        oldText, oldText.getClass(), oldText.hashCode()));
                Slog.v(TAG, "INTERRUPTIVENESS: " + String.format("   new text: %s (%s@0x%08x)",
                        newText, newText.getClass(), newText.hashCode()));
            }
            return true;
        }

        if (oldN.hasCompletedProgress() != newN.hasCompletedProgress()) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                    +  r.getKey() + " is interruptive: completed progress");
            }
            return true;
        }

        if (Notification.areIconsDifferent(oldN, newN)) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is interruptive: icons differ");
            }
            return true;
        }

        // Fields below are invisible to bubbles.
        if (r.canBubble()) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is not interruptive: bubble");
            }
            return false;
        }

        // Actions
        if (Notification.areActionsVisiblyDifferent(oldN, newN)) {
            if (DEBUG_INTERRUPTIVENESS) {
                Slog.v(TAG, "INTERRUPTIVENESS: "
                        +  r.getKey() + " is interruptive: changed actions");
            }
            return true;
        }

        try {
            Notification.Builder oldB = Notification.Builder.recoverBuilder(getContext(), oldN);
            Notification.Builder newB = Notification.Builder.recoverBuilder(getContext(), newN);

            // Style based comparisons
            if (Notification.areStyledNotificationsVisiblyDifferent(oldB, newB)) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            +  r.getKey() + " is interruptive: styles differ");
                }
                return true;
            }

            // Remote views
            if (Notification.areRemoteViewsChanged(oldB, newB)) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            +  r.getKey() + " is interruptive: remoteviews differ");
                }
                return true;
            }
        } catch (Exception e) {
            Slog.w(TAG, "error recovering builder", e);
        }
        return false;
    }

    /**
     * Check if the notification is classified as critical.
     *
     * @param record the record to test for criticality
     * @return {@code true} if notification is considered critical
     *
     * @see CriticalNotificationExtractor for criteria
     */
    private boolean isCritical(NotificationRecord record) {
        // 0 is the most critical
        return record.getCriticality() < CriticalNotificationExtractor.NORMAL;
    }

    /**
     * Ensures that grouped notification receive their special treatment.
     *
     * <p>Cancels group children if the new notification causes a group to lose
     * its summary.</p>
     *
     * <p>Updates mSummaryByGroupKey.</p>
     */
    @GuardedBy("mNotificationLock")
    private void handleGroupedNotificationLocked(NotificationRecord r, NotificationRecord old,
            int callingUid, int callingPid) {
        StatusBarNotification sbn = r.getSbn();
        Notification n = sbn.getNotification();
        if (n.isGroupSummary() && !sbn.isAppGroup())  {
            // notifications without a group shouldn't be a summary, otherwise autobundling can
            // lead to bugs
            n.flags &= ~Notification.FLAG_GROUP_SUMMARY;
        }

        String group = sbn.getGroupKey();
        boolean isSummary = n.isGroupSummary();

        Notification oldN = old != null ? old.getSbn().getNotification() : null;
        String oldGroup = old != null ? old.getSbn().getGroupKey() : null;
        boolean oldIsSummary = old != null && oldN.isGroupSummary();

        if (oldIsSummary) {
            NotificationRecord removedSummary = mSummaryByGroupKey.remove(oldGroup);
            if (removedSummary != old) {
                String removedKey =
                        removedSummary != null ? removedSummary.getKey() : "<null>";
                Slog.w(TAG, "Removed summary didn't match old notification: old=" + old.getKey() +
                        ", removed=" + removedKey);
            }
        }
        if (isSummary) {
            mSummaryByGroupKey.put(group, r);
        }

        FlagChecker childrenFlagChecker = (flags) -> {
            if ((flags & FLAG_FOREGROUND_SERVICE) != 0 || (flags & FLAG_USER_INITIATED_JOB) != 0) {
                return false;
            }
            return true;
        };

        // Clear out group children of the old notification if the update
        // causes the group summary to go away. This happens when the old
        // notification was a summary and the new one isn't, or when the old
        // notification was a summary and its group key changed.
        if (oldIsSummary && (!isSummary || !oldGroup.equals(group))) {
            cancelGroupChildrenLocked(old, callingUid, callingPid, null, false /* sendDelete */,
                    childrenFlagChecker, REASON_APP_CANCEL, SystemClock.elapsedRealtime());
        }
    }

    @VisibleForTesting
    @GuardedBy("mNotificationLock")
    void scheduleTimeoutLocked(NotificationRecord record) {
        if (record.getNotification().getTimeoutAfter() > 0) {
            final PendingIntent pi = PendingIntent.getBroadcast(getContext(),
                    REQUEST_CODE_TIMEOUT,
                    new Intent(ACTION_NOTIFICATION_TIMEOUT)
                            .setPackage(PackageManagerService.PLATFORM_PACKAGE_NAME)
                            .setData(new Uri.Builder().scheme(SCHEME_TIMEOUT)
                                    .appendPath(record.getKey()).build())
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                            .putExtra(EXTRA_KEY, record.getKey()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + record.getNotification().getTimeoutAfter(), pi);
        }
    }

    @VisibleForTesting
    @GuardedBy("mNotificationLock")
    /**
     * Determine whether this notification should attempt to make noise, vibrate, or flash the LED
     * @return buzzBeepBlink - bitfield (buzz ? 1 : 0) | (beep ? 2 : 0) | (blink ? 4 : 0)
     */
    int buzzBeepBlinkLocked(NotificationRecord record) {
        if (mIsAutomotive && !mNotificationEffectsEnabledForAutomotive) {
            return 0;
        }
        boolean buzz = false;
        boolean beep = false;
        boolean blink = false;

        final String key = record.getKey();

        // Should this notification make noise, vibe, or use the LED?
        final boolean aboveThreshold =
                mIsAutomotive
                        ? record.getImportance() > NotificationManager.IMPORTANCE_DEFAULT
                        : record.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT;
        // Remember if this notification already owns the notification channels.
        boolean wasBeep = key != null && key.equals(mSoundNotificationKey);
        boolean wasBuzz = key != null && key.equals(mVibrateNotificationKey);
        // These are set inside the conditional if the notification is allowed to make noise.
        boolean hasValidVibrate = false;
        boolean hasValidSound = false;
        boolean sentAccessibilityEvent = false;

        // If the notification will appear in the status bar, it should send an accessibility event
        final boolean suppressedByDnd = record.isIntercepted()
                && (record.getSuppressedVisualEffects() & SUPPRESSED_EFFECT_STATUS_BAR) != 0;
        if (!record.isUpdate
                && record.getImportance() > IMPORTANCE_MIN
                && !suppressedByDnd
                && isNotificationForCurrentUser(record)) {
            sendAccessibilityEvent(record);
            sentAccessibilityEvent = true;
        }

        if (aboveThreshold && isNotificationForCurrentUser(record)) {
            if (mSystemReady && mAudioManager != null) {
                Uri soundUri = record.getSound();
                hasValidSound = soundUri != null && !Uri.EMPTY.equals(soundUri);
                VibrationEffect vibration = record.getVibration();
                // Demote sound to vibration if vibration missing & phone in vibration mode.
                if (vibration == null
                        && hasValidSound
                        && (mAudioManager.getRingerModeInternal()
                        == AudioManager.RINGER_MODE_VIBRATE)
                        && mAudioManager.getStreamVolume(
                        AudioAttributes.toLegacyStreamType(record.getAudioAttributes())) == 0) {
                    boolean insistent = (record.getFlags() & Notification.FLAG_INSISTENT) != 0;
                    vibration = mVibratorHelper.createFallbackVibration(insistent);
                }
                hasValidVibrate = vibration != null;
                boolean hasAudibleAlert = hasValidSound || hasValidVibrate;
                if (hasAudibleAlert && !shouldMuteNotificationLocked(record)) {
                    if (!sentAccessibilityEvent) {
                        sendAccessibilityEvent(record);
                        sentAccessibilityEvent = true;
                    }
                    if (DBG) Slog.v(TAG, "Interrupting!");
                    boolean isInsistentUpdate = isInsistentUpdate(record);
                    if (hasValidSound) {
                        if (isInsistentUpdate) {
                            // don't reset insistent sound, it's jarring
                            beep = true;
                        } else {
                            if (isInCall()) {
                                playInCallNotification();
                                beep = true;
                            } else {
                                beep = playSound(record, soundUri);
                            }
                            if (beep) {
                                mSoundNotificationKey = key;
                            }
                        }
                    }

                    final boolean ringerModeSilent =
                            mAudioManager.getRingerModeInternal()
                                    == AudioManager.RINGER_MODE_SILENT;
                    if (!isInCall() && hasValidVibrate && !ringerModeSilent) {
                        if (isInsistentUpdate) {
                            buzz = true;
                        } else {
                            buzz = playVibration(record, vibration, hasValidSound);
                            if (buzz) {
                                mVibrateNotificationKey = key;
                            }
                        }
                    }

                    // Try to start flash notification event whenever an audible and non-suppressed
                    // notification is received
                    mAccessibilityManager.startFlashNotificationEvent(getContext(),
                            AccessibilityManager.FLASH_REASON_NOTIFICATION,
                            record.getSbn().getPackageName());

                } else if ((record.getFlags() & Notification.FLAG_INSISTENT) != 0) {
                    hasValidSound = false;
                }
            }
        }
        // If a notification is updated to remove the actively playing sound or vibrate,
        // cancel that feedback now
        if (wasBeep && !hasValidSound) {
            clearSoundLocked();
        }
        if (wasBuzz && !hasValidVibrate) {
            clearVibrateLocked();
        }

        // light
        // release the light
        boolean wasShowLights = mLights.remove(key);
        if (canShowLightsLocked(record, aboveThreshold)) {
            mLights.add(key);
            updateLightsLocked();
            if (mUseAttentionLight && mAttentionLight != null) {
                mAttentionLight.pulse();
            }
            blink = true;
        } else if (wasShowLights) {
            updateLightsLocked();
        }
        final int buzzBeepBlink = (buzz ? 1 : 0) | (beep ? 2 : 0) | (blink ? 4 : 0);
        if (buzzBeepBlink > 0) {
            // Ignore summary updates because we don't display most of the information.
            if (record.getSbn().isGroup() && record.getSbn().getNotification().isGroupSummary()) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            + record.getKey() + " is not interruptive: summary");
                }
            } else if (record.canBubble()) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            + record.getKey() + " is not interruptive: bubble");
                }
            } else {
                record.setInterruptive(true);
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: "
                            + record.getKey() + " is interruptive: alerted");
                }
            }
            MetricsLogger.action(record.getLogMaker()
                    .setCategory(MetricsEvent.NOTIFICATION_ALERT)
                    .setType(MetricsEvent.TYPE_OPEN)
                    .setSubtype(buzzBeepBlink));
            EventLogTags.writeNotificationAlert(key, buzz ? 1 : 0, beep ? 1 : 0, blink ? 1 : 0, 0);
        }
        record.setAudiblyAlerted(buzz || beep);
        return buzzBeepBlink;
    }

    @GuardedBy("mNotificationLock")
    boolean canShowLightsLocked(final NotificationRecord record, boolean aboveThreshold) {
        // device lacks light
        if (!mHasLight) {
            return false;
        }
        // user turned lights off globally
        if (!mNotificationPulseEnabled) {
            return false;
        }
        // the notification/channel has no light
        if (record.getLight() == null) {
            return false;
        }
        // unimportant notification
        if (!aboveThreshold) {
            return false;
        }
        // suppressed due to DND
        if ((record.getSuppressedVisualEffects() & SUPPRESSED_EFFECT_LIGHTS) != 0) {
            return false;
        }
        // Suppressed because it's a silent update
        final Notification notification = record.getNotification();
        if (record.isUpdate && (notification.flags & FLAG_ONLY_ALERT_ONCE) != 0) {
            return false;
        }
        // Suppressed because another notification in its group handles alerting
        if (record.getSbn().isGroup() && record.getNotification().suppressAlertingDueToGrouping()) {
            return false;
        }
        // not if in call
        if (isInCall()) {
            return false;
        }
        // check current user
        if (!isNotificationForCurrentUser(record)) {
            return false;
        }
        // Light, but only when the screen is off
        return true;
    }

    @GuardedBy("mNotificationLock")
    boolean isInsistentUpdate(final NotificationRecord record) {
        return (Objects.equals(record.getKey(), mSoundNotificationKey)
                || Objects.equals(record.getKey(), mVibrateNotificationKey))
                && isCurrentlyInsistent();
    }

    @GuardedBy("mNotificationLock")
    boolean isCurrentlyInsistent() {
        return isLoopingRingtoneNotification(mNotificationsByKey.get(mSoundNotificationKey))
                || isLoopingRingtoneNotification(mNotificationsByKey.get(mVibrateNotificationKey));
    }

    @GuardedBy("mNotificationLock")
    boolean shouldMuteNotificationLocked(final NotificationRecord record) {
        // Suppressed because it's a silent update
        final Notification notification = record.getNotification();
        if (record.isUpdate && (notification.flags & FLAG_ONLY_ALERT_ONCE) != 0) {
            return true;
        }

        // Suppressed because a user manually unsnoozed something (or similar)
        if (record.shouldPostSilently()) {
            return true;
        }

        // muted by listener
        final String disableEffects = disableNotificationEffects(record);
        if (disableEffects != null) {
            ZenLog.traceDisableEffects(record, disableEffects);
            return true;
        }

        // suppressed due to DND
        if (record.isIntercepted()) {
            return true;
        }

        // Suppressed because another notification in its group handles alerting
        if (record.getSbn().isGroup()) {
            if (notification.suppressAlertingDueToGrouping()) {
                return true;
            }
        }

        // Suppressed for being too recently noisy
        final String pkg = record.getSbn().getPackageName();
        if (mUsageStats.isAlertRateLimited(pkg)) {
            Slog.e(TAG, "Muting recently noisy " + record.getKey());
            return true;
        }

        // A different looping ringtone, such as an incoming call is playing
        if (isCurrentlyInsistent() && !isInsistentUpdate(record)) {
            return true;
        }

        // Suppressed since it's a non-interruptive update to a bubble-suppressed notification
        final boolean isBubbleOrOverflowed = record.canBubble() && (record.isFlagBubbleRemoved()
                || record.getNotification().isBubbleNotification());
        if (record.isUpdate && !record.isInterruptive() && isBubbleOrOverflowed
                && record.getNotification().getBubbleMetadata() != null) {
            if (record.getNotification().getBubbleMetadata().isNotificationSuppressed()) {
                return true;
            }
        }

        return false;
    }

    @GuardedBy("mNotificationLock")
    private boolean isLoopingRingtoneNotification(final NotificationRecord playingRecord) {
        if (playingRecord != null) {
            if (playingRecord.getAudioAttributes().getUsage() == USAGE_NOTIFICATION_RINGTONE
                    && (playingRecord.getNotification().flags & FLAG_INSISTENT) != 0) {
                return true;
            }
        }
        return false;
    }

    private boolean playSound(final NotificationRecord record, Uri soundUri) {
        boolean looping = (record.getNotification().flags & FLAG_INSISTENT) != 0;
        // play notifications if there is no user of exclusive audio focus
        // and the stream volume is not 0 (non-zero volume implies not silenced by SILENT or
        //   VIBRATE ringer mode)
        if (!mAudioManager.isAudioFocusExclusive()
                && (mAudioManager.getStreamVolume(
                        AudioAttributes.toLegacyStreamType(record.getAudioAttributes())) != 0)) {
            final long identity = Binder.clearCallingIdentity();
            try {
                final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                if (player != null) {
                    if (DBG) Slog.v(TAG, "Playing sound " + soundUri
                            + " with attributes " + record.getAudioAttributes());
                    player.playAsync(soundUri, record.getSbn().getUser(), looping,
                            record.getAudioAttributes(), 1.0f);
                    return true;
                }
            } catch (RemoteException e) {
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        return false;
    }

    private boolean playVibration(final NotificationRecord record, final VibrationEffect effect,
            boolean delayVibForSound) {
        // Escalate privileges so we can use the vibrator even if the
        // notifying app does not have the VIBRATE permission.
        final long identity = Binder.clearCallingIdentity();
        try {
            if (delayVibForSound) {
                new Thread(() -> {
                    // delay the vibration by the same amount as the notification sound
                    final int waitMs = mAudioManager.getFocusRampTimeMs(
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                            record.getAudioAttributes());
                    if (DBG) {
                        Slog.v(TAG, "Delaying vibration for notification "
                                + record.getKey() + " by " + waitMs + "ms");
                    }
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException e) { }
                    // Notifications might be canceled before it actually vibrates due to waitMs,
                    // so need to check that the notification is still valid for vibrate.
                    synchronized (mNotificationLock) {
                        if (mNotificationsByKey.get(record.getKey()) != null) {
                            if (record.getKey().equals(mVibrateNotificationKey)) {
                                vibrate(record, effect, true);
                            } else {
                                if (DBG) {
                                    Slog.v(TAG, "No vibration for notification "
                                            + record.getKey() + ": a new notification is "
                                            + "vibrating, or effects were cleared while waiting");
                                }
                            }
                        } else {
                            Slog.w(TAG, "No vibration for canceled notification "
                                    + record.getKey());
                        }
                    }
                }).start();
            } else {
                vibrate(record, effect, false);
            }
            return true;
        } finally{
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void vibrate(NotificationRecord record, VibrationEffect effect, boolean delayed) {
        // We need to vibrate as "android" so we can breakthrough DND. VibratorManagerService
        // doesn't have a concept of vibrating on an app's behalf, so add the app information
        // to the reason so we can still debug from bugreports
        String reason = "Notification (" + record.getSbn().getOpPkg() + " "
                + record.getSbn().getUid() + ") " + (delayed ? "(Delayed)" : "");
        mVibratorHelper.vibrate(effect, record.getAudioAttributes(), reason);
    }

    private boolean isNotificationForCurrentUser(NotificationRecord record) {
        final int currentUser;
        final long token = Binder.clearCallingIdentity();
        try {
            currentUser = ActivityManager.getCurrentUser();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return (record.getUserId() == UserHandle.USER_ALL ||
                record.getUserId() == currentUser ||
                mUserProfiles.isCurrentProfile(record.getUserId()));
    }

    protected void playInCallNotification() {
        final ContentResolver cr = getContext().getContentResolver();
        if (mAudioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_NORMAL
                && Settings.Secure.getIntForUser(cr,
                Settings.Secure.IN_CALL_NOTIFICATION_ENABLED, 1, cr.getUserId()) != 0) {
            new Thread() {
                @Override
                public void run() {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                        if (player != null) {
                            if (mCallNotificationToken != null) {
                                player.stop(mCallNotificationToken);
                            }
                            mCallNotificationToken = new Binder();
                            player.play(mCallNotificationToken, mInCallNotificationUri,
                                    mInCallNotificationAudioAttributes,
                                    mInCallNotificationVolume, false);
                        }
                    } catch (RemoteException e) {
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }.start();
        }
    }

    @GuardedBy("mToastQueue")
    void showNextToastLocked(boolean lastToastWasTextRecord) {
        if (mIsCurrentToastShown) {
            return; // Don't show the same toast twice.
        }

        ToastRecord record = mToastQueue.get(0);
        while (record != null) {
            int userId = UserHandle.getUserId(record.uid);
            boolean rateLimitingEnabled =
                    !mToastRateLimitingDisabledUids.contains(record.uid);
            boolean isWithinQuota =
                    mToastRateLimiter.isWithinQuota(userId, record.pkg, TOAST_QUOTA_TAG)
                            || isExemptFromRateLimiting(record.pkg, userId);
            boolean isPackageInForeground = isPackageInForegroundForToast(record.uid);

            if (tryShowToast(
                    record, rateLimitingEnabled, isWithinQuota, isPackageInForeground)) {
                scheduleDurationReachedLocked(record, lastToastWasTextRecord);
                mIsCurrentToastShown = true;
                if (rateLimitingEnabled && !isPackageInForeground) {
                    mToastRateLimiter.noteEvent(userId, record.pkg, TOAST_QUOTA_TAG);
                }
                return;
            }

            int index = mToastQueue.indexOf(record);
            if (index >= 0) {
                ToastRecord toast = mToastQueue.remove(index);
                mWindowManagerInternal.removeWindowToken(
                        toast.windowToken, true /* removeWindows */, toast.displayId);
            }
            record = (mToastQueue.size() > 0) ? mToastQueue.get(0) : null;
        }
    }

    /** Returns true if it successfully showed the toast. */
    private boolean tryShowToast(ToastRecord record, boolean rateLimitingEnabled,
            boolean isWithinQuota, boolean isPackageInForeground) {
        if (rateLimitingEnabled && !isWithinQuota && !isPackageInForeground) {
            reportCompatRateLimitingToastsChange(record.uid);
            Slog.w(TAG, "Package " + record.pkg + " is above allowed toast quota, the "
                    + "following toast was blocked and discarded: " + record);
            return false;
        }
        if (blockToast(record.uid, record.isSystemToast, record.isAppRendered(),
                isPackageInForeground)) {
            Slog.w(TAG, "Blocking custom toast from package " + record.pkg
                    + " due to package not in the foreground at the time of showing the toast");
            return false;
        }
        return record.show();
    }

    private boolean isExemptFromRateLimiting(String pkg, int userId) {
        boolean isExemptFromRateLimiting = false;
        try {
            isExemptFromRateLimiting = mPackageManager.checkPermission(
                    android.Manifest.permission.UNLIMITED_TOASTS, pkg, userId)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to connect with package manager");
        }
        return isExemptFromRateLimiting;
    }

    /** Reports rate limiting toasts compat change (used when the toast was blocked). */
    private void reportCompatRateLimitingToastsChange(int uid) {
        final long id = Binder.clearCallingIdentity();
        try {
            mPlatformCompat.reportChangeByUid(RATE_LIMIT_TOASTS, uid);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unexpected exception while reporting toast was blocked due to rate"
                    + " limiting", e);
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    @GuardedBy("mToastQueue")
    void cancelToastLocked(int index) {
        ToastRecord record = mToastQueue.get(index);
        record.hide();

        if (index == 0) {
            mIsCurrentToastShown = false;
        }

        ToastRecord lastToast = mToastQueue.remove(index);

        // We need to schedule a timeout to make sure the token is eventually killed
        scheduleKillTokenTimeout(lastToast);

        keepProcessAliveForToastIfNeededLocked(record.pid);
        if (mToastQueue.size() > 0) {
            // Show the next one. If the callback fails, this will remove
            // it from the list, so don't assume that the list hasn't changed
            // after this point.
            showNextToastLocked(lastToast instanceof TextToastRecord);
        }
    }

    void finishWindowTokenLocked(IBinder t, int displayId) {
        mHandler.removeCallbacksAndMessages(t);
        // We pass 'true' for 'removeWindows' to let the WindowManager destroy any
        // remaining surfaces as either the client has called finishToken indicating
        // it has successfully removed the views, or the client has timed out
        // at which point anything goes.
        mWindowManagerInternal.removeWindowToken(t, true /* removeWindows */, displayId);
    }

    @GuardedBy("mToastQueue")
    private void scheduleDurationReachedLocked(ToastRecord r, boolean lastToastWasTextRecord)
    {
        mHandler.removeCallbacksAndMessages(r);
        Message m = Message.obtain(mHandler, MESSAGE_DURATION_REACHED, r);
        int delay = r.getDuration() == Toast.LENGTH_LONG ? LONG_DELAY : SHORT_DELAY;
        // Accessibility users may need longer timeout duration. This api compares original delay
        // with user's preference and return longer one. It returns original delay if there's no
        // preference.
        delay = mAccessibilityManager.getRecommendedTimeoutMillis(delay,
                AccessibilityManager.FLAG_CONTENT_TEXT);

        if (lastToastWasTextRecord) {
            delay += 250; // delay to account for previous toast's "out" animation
        }
        if (r instanceof TextToastRecord) {
            delay += 333; // delay to account for this toast's "in" animation
        }

        mHandler.sendMessageDelayed(m, delay);
    }

    private void handleDurationReached(ToastRecord record)
    {
        if (DBG) Slog.d(TAG, "Timeout pkg=" + record.pkg + " token=" + record.token);
        synchronized (mToastQueue) {
            int index = indexOfToastLocked(record.pkg, record.token);
            if (index >= 0) {
                cancelToastLocked(index);
            }
        }
    }

    @GuardedBy("mToastQueue")
    private void scheduleKillTokenTimeout(ToastRecord r)
    {
        mHandler.removeCallbacksAndMessages(r);
        Message m = Message.obtain(mHandler, MESSAGE_FINISH_TOKEN_TIMEOUT, r);
        mHandler.sendMessageDelayed(m, FINISH_TOKEN_TIMEOUT);
    }

    private void handleKillTokenTimeout(ToastRecord record)
    {
        if (DBG) Slog.d(TAG, "Kill Token Timeout token=" + record.windowToken);
        synchronized (mToastQueue) {
            finishWindowTokenLocked(record.windowToken, record.displayId);
        }
    }

    @GuardedBy("mToastQueue")
    int indexOfToastLocked(String pkg, IBinder token) {
        ArrayList<ToastRecord> list = mToastQueue;
        int len = list.size();
        for (int i=0; i<len; i++) {
            ToastRecord r = list.get(i);
            if (r.pkg.equals(pkg) && r.token == token) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Adjust process {@code pid} importance according to whether it has toasts in the queue or not.
     */
    public void keepProcessAliveForToastIfNeeded(int pid) {
        synchronized (mToastQueue) {
            keepProcessAliveForToastIfNeededLocked(pid);
        }
    }

    @GuardedBy("mToastQueue")
    private void keepProcessAliveForToastIfNeededLocked(int pid) {
        int toastCount = 0; // toasts from this pid, rendered by the app
        ArrayList<ToastRecord> list = mToastQueue;
        int n = list.size();
        for (int i = 0; i < n; i++) {
            ToastRecord r = list.get(i);
            if (r.pid == pid && r.keepProcessAlive()) {
                toastCount++;
            }
        }
        try {
            mAm.setProcessImportant(mForegroundToken, pid, toastCount > 0, "toast");
        } catch (RemoteException e) {
            // Shouldn't happen.
        }
    }

    /**
     * Implementation note: Our definition of foreground for toasts is an implementation matter
     * and should strike a balance between functionality and anti-abuse effectiveness. We
     * currently worry about the following cases:
     * <ol>
     *     <li>App with fullscreen activity: Allow toasts
     *     <li>App behind translucent activity from other app: Block toasts
     *     <li>App in multi-window: Allow toasts
     *     <li>App with expanded bubble: Allow toasts
     *     <li>App posting toasts on onCreate(), onStart(), onResume(): Allow toasts
     *     <li>App posting toasts on onPause(), onStop(), onDestroy(): Block toasts
     * </ol>
     * Checking if the UID has any resumed activities satisfy use-cases above.
     *
     * <p>Checking if {@code mActivityManager.getUidImportance(callingUid) ==
     * IMPORTANCE_FOREGROUND} does not work because it considers the app in foreground if it has
     * any visible activities, failing case 2 in list above.
     */
    private boolean isPackageInForegroundForToast(int callingUid) {
        return mAtm.hasResumedActivity(callingUid);
    }

    /**
     * True if the toast should be blocked. It will return true if all of the following conditions
     * apply: it's a custom toast, it's not a system toast, the package that sent the toast is in
     * the background and CHANGE_BACKGROUND_CUSTOM_TOAST_BLOCK is enabled.
     *
     * CHANGE_BACKGROUND_CUSTOM_TOAST_BLOCK is gated on targetSdk, so it will return false for apps
     * with targetSdk < R. For apps with targetSdk R+, text toasts are not app-rendered, so
     * isAppRenderedToast == true means it's a custom toast.
     */
    private boolean blockToast(int uid, boolean isSystemToast, boolean isAppRenderedToast,
            boolean isPackageInForeground) {
        return isAppRenderedToast
                && !isSystemToast
                && !isPackageInForeground
                && CompatChanges.isChangeEnabled(CHANGE_BACKGROUND_CUSTOM_TOAST_BLOCK, uid);
    }

    private void handleRankingReconsideration(Message message) {
        if (!(message.obj instanceof RankingReconsideration)) return;
        RankingReconsideration recon = (RankingReconsideration) message.obj;
        recon.run();
        boolean changed;
        synchronized (mNotificationLock) {
            final NotificationRecord record = mNotificationsByKey.get(recon.getKey());
            if (record == null) {
                return;
            }
            int indexBefore = findNotificationRecordIndexLocked(record);
            boolean interceptBefore = record.isIntercepted();
            int visibilityBefore = record.getPackageVisibilityOverride();
            boolean interruptiveBefore = record.isInterruptive();

            recon.applyChangesLocked(record);
            applyZenModeLocked(record);
            mRankingHelper.sort(mNotificationList);
            boolean indexChanged = indexBefore != findNotificationRecordIndexLocked(record);
            boolean interceptChanged = interceptBefore != record.isIntercepted();
            boolean visibilityChanged = visibilityBefore != record.getPackageVisibilityOverride();

            // Broadcast isInterruptive changes for bubbles.
            boolean interruptiveChanged =
                    record.canBubble() && (interruptiveBefore != record.isInterruptive());

            changed = indexChanged
                    || interceptChanged
                    || visibilityChanged
                    || interruptiveChanged;
            if (interceptBefore && !record.isIntercepted()
                    && record.isNewEnoughForAlerting(System.currentTimeMillis())) {
                if (Flags.refactorAttentionHelper()) {
                    mAttentionHelper.buzzBeepBlinkLocked(record,
                        new NotificationAttentionHelper.Signals(
                            mUserProfiles.isCurrentProfile(record.getUserId()), mListenerHints));
                } else {
                    buzzBeepBlinkLocked(record);
                }

                // Log alert after change in intercepted state to Zen Log as well
                ZenLog.traceAlertOnUpdatedIntercept(record);
            }
        }
        if (changed) {
            mHandler.scheduleSendRankingUpdate();
        }
    }

    void handleRankingSort() {
        if (mRankingHelper == null) return;
        synchronized (mNotificationLock) {
            final int N = mNotificationList.size();
            // Any field that can change via one of the extractors needs to be added here.
            ArrayMap<String, NotificationRecordExtractorData> extractorDataBefore =
                    new ArrayMap<>(N);
            for (int i = 0; i < N; i++) {
                final NotificationRecord r = mNotificationList.get(i);
                NotificationRecordExtractorData extractorData = new NotificationRecordExtractorData(
                        i,
                        r.getPackageVisibilityOverride(),
                        r.canShowBadge(),
                        r.canBubble(),
                        r.getNotification().isBubbleNotification(),
                        r.getChannel(),
                        r.getGroupKey(),
                        r.getPeopleOverride(),
                        r.getSnoozeCriteria(),
                        r.getUserSentiment(),
                        r.getSuppressedVisualEffects(),
                        r.getSystemGeneratedSmartActions(),
                        r.getSmartReplies(),
                        r.getImportance(),
                        r.getRankingScore(),
                        r.isConversation(),
                        r.getProposedImportance(),
                        r.hasSensitiveContent());
                extractorDataBefore.put(r.getKey(), extractorData);
                mRankingHelper.extractSignals(r);
            }
            mRankingHelper.sort(mNotificationList);
            for (int i = 0; i < N; i++) {
                final NotificationRecord r = mNotificationList.get(i);
                if (!extractorDataBefore.containsKey(r.getKey())) {
                    // This shouldn't happen given that we just built this with all the
                    // notifications, but check just to be safe.
                    continue;
                }
                if (extractorDataBefore.get(r.getKey()).hasDiffForRankingLocked(r, i)) {
                    mHandler.scheduleSendRankingUpdate();
                }

                // If this notification is one for which we wanted to log an update, and
                // sufficient relevant bits are different, log update.
                if (r.hasPendingLogUpdate()) {
                    // We need to acquire the previous data associated with this specific
                    // notification, as the one at the current index may be unrelated if
                    // notification order has changed.
                    NotificationRecordExtractorData prevData = extractorDataBefore.get(r.getKey());
                    if (prevData.hasDiffForLoggingLocked(r, i)) {
                        mNotificationRecordLogger.logNotificationAdjusted(r, i, 0,
                                getGroupInstanceId(r.getSbn().getGroupKey()));
                    }

                    // Remove whether there was a diff or not; we've sorted the key, so if it
                    // turns out there was nothing to log, that's fine too.
                    r.setPendingLogUpdate(false);
                }
            }
        }
    }

    @GuardedBy("mNotificationLock")
    private void recordCallerLocked(NotificationRecord record) {
        if (mZenModeHelper.isCall(record)) {
            mZenModeHelper.recordCaller(record);
        }
    }

    // let zen mode evaluate this record
    @GuardedBy("mNotificationLock")
    private void applyZenModeLocked(NotificationRecord record) {
        record.setIntercepted(mZenModeHelper.shouldIntercept(record));
        if (record.isIntercepted()) {
            record.setSuppressedVisualEffects(
                    mZenModeHelper.getConsolidatedNotificationPolicy().suppressedVisualEffects);
        } else {
            record.setSuppressedVisualEffects(0);
        }
    }

    @GuardedBy("mNotificationLock")
    private int findNotificationRecordIndexLocked(NotificationRecord target) {
        return mRankingHelper.indexOf(mNotificationList, target);
    }

    private void handleSendRankingUpdate() {
        synchronized (mNotificationLock) {
            mListeners.notifyRankingUpdateLocked(null);
        }
    }

    private void scheduleListenerHintsChanged(int state) {
        mHandler.removeMessages(MESSAGE_LISTENER_HINTS_CHANGED);
        mHandler.obtainMessage(MESSAGE_LISTENER_HINTS_CHANGED, state, 0).sendToTarget();
    }

    private void scheduleInterruptionFilterChanged(int listenerInterruptionFilter) {
        mHandler.removeMessages(MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED);
        mHandler.obtainMessage(
                MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED,
                listenerInterruptionFilter,
                0).sendToTarget();
    }

    private void handleListenerHintsChanged(int hints) {
        synchronized (mNotificationLock) {
            mListeners.notifyListenerHintsChangedLocked(hints);
        }
    }

    private void handleListenerInterruptionFilterChanged(int interruptionFilter) {
        synchronized (mNotificationLock) {
            mListeners.notifyInterruptionFilterChanged(interruptionFilter);
        }
    }

    void handleOnPackageChanged(boolean removingPackage, int changeUserId,
            String[] pkgList, int[] uidList) {
        boolean preferencesChanged = removingPackage;
        mListeners.onPackagesChanged(removingPackage, pkgList, uidList);
        mAssistants.onPackagesChanged(removingPackage, pkgList, uidList);
        mConditionProviders.onPackagesChanged(removingPackage, pkgList, uidList);
        preferencesChanged |= mPreferencesHelper.onPackagesChanged(
                removingPackage, changeUserId, pkgList, uidList);
        if (removingPackage) {
            int size = Math.min(pkgList.length, uidList.length);
            for (int i = 0; i < size; i++) {
                final String pkg = pkgList[i];
                final int uid = uidList[i];
                mHistoryManager.onPackageRemoved(UserHandle.getUserId(uid), pkg);
            }
        }
        if (preferencesChanged) {
            handleSavePolicyFile();
        }
    }

    protected class WorkerHandler extends Handler
    {
        public WorkerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg)
        {
            switch (msg.what)
            {
                case MESSAGE_DURATION_REACHED:
                    handleDurationReached((ToastRecord) msg.obj);
                    break;
                case MESSAGE_FINISH_TOKEN_TIMEOUT:
                    handleKillTokenTimeout((ToastRecord) msg.obj);
                    break;
                case MESSAGE_SEND_RANKING_UPDATE:
                    handleSendRankingUpdate();
                    break;
                case MESSAGE_LISTENER_HINTS_CHANGED:
                    handleListenerHintsChanged(msg.arg1);
                    break;
                case MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED:
                    handleListenerInterruptionFilterChanged(msg.arg1);
                    break;
                case MESSAGE_ON_PACKAGE_CHANGED:
                    SomeArgs args = (SomeArgs) msg.obj;
                    handleOnPackageChanged((boolean) args.arg1, args.argi1, (String[]) args.arg2,
                            (int[]) args.arg3);
                    args.recycle();
                    break;
            }
        }

        protected void scheduleSendRankingUpdate() {
            if (!hasMessages(MESSAGE_SEND_RANKING_UPDATE)) {
                Message m = Message.obtain(this, MESSAGE_SEND_RANKING_UPDATE);
                sendMessage(m);
            }
        }

        protected void scheduleCancelNotification(CancelNotificationRunnable cancelRunnable) {
            if (!hasCallbacks(cancelRunnable)) {
                sendMessage(Message.obtain(this, cancelRunnable));
            }
        }

        protected void scheduleOnPackageChanged(boolean removingPackage, int changeUserId,
                String[] pkgList, int[] uidList) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = removingPackage;
            args.argi1 = changeUserId;
            args.arg2 = pkgList;
            args.arg3 = uidList;
            sendMessage(Message.obtain(this, MESSAGE_ON_PACKAGE_CHANGED, args));
        }
    }

    private final class RankingHandlerWorker extends Handler implements RankingHandler
    {
        public RankingHandlerWorker(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_RECONSIDER_RANKING:
                    handleRankingReconsideration(msg);
                    break;
                case MESSAGE_RANKING_SORT:
                    handleRankingSort();
                    break;
            }
        }

        public void requestSort() {
            removeMessages(MESSAGE_RANKING_SORT);
            Message msg = Message.obtain();
            msg.what = MESSAGE_RANKING_SORT;
            sendMessage(msg);
        }

        public void requestReconsideration(RankingReconsideration recon) {
            Message m = Message.obtain(this,
                    NotificationManagerService.MESSAGE_RECONSIDER_RANKING, recon);
            long delay = recon.getDelay(TimeUnit.MILLISECONDS);
            sendMessageDelayed(m, delay);
        }
    }

    // Notifications
    // ============================================================================
    static int clamp(int x, int low, int high) {
        return (x < low) ? low : ((x > high) ? high : x);
    }

    void sendAccessibilityEvent(NotificationRecord record) {
        if (!mAccessibilityManager.isEnabled()) {
            return;
        }

        final Notification notification = record.getNotification();
        final CharSequence packageName = record.getSbn().getPackageName();
        final AccessibilityEvent event =
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setPackageName(packageName);
        event.setClassName(Notification.class.getName());
        final int visibilityOverride = record.getPackageVisibilityOverride();
        final int notifVisibility = visibilityOverride == NotificationManager.VISIBILITY_NO_OVERRIDE
                ? notification.visibility : visibilityOverride;
        final int userId = record.getUser().getIdentifier();
        final boolean needPublic = userId >= 0 && mKeyguardManager.isDeviceLocked(userId);
        if (needPublic && notifVisibility != Notification.VISIBILITY_PUBLIC) {
            // Emit the public version if we're on the lockscreen and this notification isn't
            // publicly visible.
            event.setParcelableData(notification.publicVersion);
        } else {
            event.setParcelableData(notification);
        }
        final CharSequence tickerText = notification.tickerText;
        if (!TextUtils.isEmpty(tickerText)) {
            event.getText().add(tickerText);
        }

        mAccessibilityManager.sendAccessibilityEvent(event);
    }

    /**
     * Removes all NotificationsRecords with the same key as the given notification record
     * from both lists. Do not call this method while iterating over either list.
     */
    @GuardedBy("mNotificationLock")
    private boolean removeFromNotificationListsLocked(NotificationRecord r) {
        // Remove from both lists, either list could have a separate Record for what is
        // effectively the same notification.
        boolean wasPosted = false;
        NotificationRecord recordInList = null;
        if ((recordInList = findNotificationByListLocked(mNotificationList, r.getKey()))
                != null) {
            mNotificationList.remove(recordInList);
            mNotificationsByKey.remove(recordInList.getSbn().getKey());
            wasPosted = true;
        }
        while ((recordInList = findNotificationByListLocked(mEnqueuedNotifications, r.getKey()))
                != null) {
            mEnqueuedNotifications.remove(recordInList);
        }
        return wasPosted;
    }

    @GuardedBy("mNotificationLock")
    private void cancelNotificationLocked(NotificationRecord r, boolean sendDelete,
            @NotificationListenerService.NotificationCancelReason int reason,
            boolean wasPosted, String listenerName,
            @ElapsedRealtimeLong long cancellationElapsedTimeMs) {
        cancelNotificationLocked(r, sendDelete, reason, -1, -1, wasPosted, listenerName,
                cancellationElapsedTimeMs);
    }

    @GuardedBy("mNotificationLock")
    private void cancelNotificationLocked(NotificationRecord r, boolean sendDelete,
            @NotificationListenerService.NotificationCancelReason int reason,
            int rank, int count, boolean wasPosted, String listenerName,
            @ElapsedRealtimeLong long cancellationElapsedTimeMs) {
        final String canceledKey = r.getKey();

        // Get pending intent used to create alarm, use FLAG_NO_CREATE if PendingIntent
        // does not already exist, then null will be returned.
        final PendingIntent pi = PendingIntent.getBroadcast(getContext(),
                REQUEST_CODE_TIMEOUT,
                new Intent(ACTION_NOTIFICATION_TIMEOUT)
                        .setData(new Uri.Builder().scheme(SCHEME_TIMEOUT)
                                .appendPath(r.getKey()).build())
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);

        // Cancel alarm corresponding to pi.
        if (pi != null) {
            mAlarmManager.cancel(pi);
        }

        // Record caller.
        recordCallerLocked(r);

        if (r.getStats().getDismissalSurface() == NotificationStats.DISMISSAL_NOT_DISMISSED) {
            r.recordDismissalSurface(NotificationStats.DISMISSAL_OTHER);
        }

        // tell the app
        if (sendDelete) {
            final PendingIntent deleteIntent = r.getNotification().deleteIntent;
            if (deleteIntent != null) {
                try {
                    // make sure deleteIntent cannot be used to start activities from background
                    LocalServices.getService(ActivityManagerInternal.class)
                            .clearPendingIntentAllowBgActivityStarts(deleteIntent.getTarget(),
                                    ALLOWLIST_TOKEN);
                    deleteIntent.send();
                } catch (PendingIntent.CanceledException ex) {
                    // do nothing - there's no relevant way to recover, and
                    //     no reason to let this propagate
                    Slog.w(TAG, "canceled PendingIntent for " + r.getSbn().getPackageName(), ex);
                }
            }
        }

        // Only cancel these if this notification actually got to be posted.
        if (wasPosted) {
            // status bar
            if (r.getNotification().getSmallIcon() != null) {
                if (reason != REASON_SNOOZED) {
                    r.isCanceled = true;
                }
                mListeners.notifyRemovedLocked(r, reason, r.getStats());
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mGroupHelper.onNotificationRemoved(r.getSbn());
                    }
                });
            }

            if (Flags.refactorAttentionHelper()) {
                mAttentionHelper.clearEffectsLocked(canceledKey);
            } else {
                // sound
                if (canceledKey.equals(mSoundNotificationKey)) {
                    clearSoundLocked();
                }

                // vibrate
                if (canceledKey.equals(mVibrateNotificationKey)) {
                    clearVibrateLocked();
                }

                // light
                mLights.remove(canceledKey);
            }
        }

        // Record usage stats
        // TODO: add unbundling stats?
        switch (reason) {
            case REASON_CANCEL:
            case REASON_CANCEL_ALL:
            case REASON_LISTENER_CANCEL:
            case REASON_LISTENER_CANCEL_ALL:
                mUsageStats.registerDismissedByUser(r);
                break;
            case REASON_APP_CANCEL:
            case REASON_APP_CANCEL_ALL:
                mUsageStats.registerRemovedByApp(r);
                mUsageStatsManagerInternal.reportNotificationRemoved(r.getSbn().getOpPkg(),
                        r.getUser(), cancellationElapsedTimeMs);
                break;
        }

        String groupKey = r.getGroupKey();
        NotificationRecord groupSummary = mSummaryByGroupKey.get(groupKey);
        if (groupSummary != null && groupSummary.getKey().equals(canceledKey)) {
            mSummaryByGroupKey.remove(groupKey);
        }
        final ArrayMap<String, String> summaries =
                mAutobundledSummaries.get(r.getSbn().getUserId());
        if (summaries != null && r.getSbn().getKey().equals(
                summaries.get(r.getSbn().getPackageName()))) {
            summaries.remove(r.getSbn().getPackageName());
        }

        // Save it for users of getHistoricalNotifications(), unless the whole channel was deleted
        if (reason != REASON_CHANNEL_REMOVED) {
            mArchive.record(r.getSbn(), reason);
        }

        final long now = System.currentTimeMillis();
        final LogMaker logMaker = r.getItemLogMaker()
                .setType(MetricsEvent.TYPE_DISMISS)
                .setSubtype(reason);
        if (rank != -1 && count != -1) {
            logMaker.addTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX, rank)
                    .addTaggedData(MetricsEvent.NOTIFICATION_SHADE_COUNT, count);
        }
        MetricsLogger.action(logMaker);
        EventLogTags.writeNotificationCanceled(canceledKey, reason,
                r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now),
                rank, count, listenerName);
        if (wasPosted) {
            mNotificationRecordLogger.logNotificationCancelled(r, reason,
                    r.getStats().getDismissalSurface());
        }
    }

    @VisibleForTesting
    void updateUriPermissions(@Nullable NotificationRecord newRecord,
            @Nullable NotificationRecord oldRecord, String targetPkg, int targetUserId) {
        updateUriPermissions(newRecord, oldRecord, targetPkg, targetUserId, false);
    }

    @VisibleForTesting
    void updateUriPermissions(@Nullable NotificationRecord newRecord,
            @Nullable NotificationRecord oldRecord, String targetPkg, int targetUserId,
            boolean onlyRevokeCurrentTarget) {
        final String key = (newRecord != null) ? newRecord.getKey() : oldRecord.getKey();
        if (DBG) Slog.d(TAG, key + ": updating permissions");

        final ArraySet<Uri> newUris = (newRecord != null) ? newRecord.getGrantableUris() : null;
        final ArraySet<Uri> oldUris = (oldRecord != null) ? oldRecord.getGrantableUris() : null;

        // Shortcut when no Uris involved
        if (newUris == null && oldUris == null) {
            return;
        }

        // Inherit any existing owner
        IBinder permissionOwner = null;
        if (newRecord != null && permissionOwner == null) {
            permissionOwner = newRecord.permissionOwner;
        }
        if (oldRecord != null && permissionOwner == null) {
            permissionOwner = oldRecord.permissionOwner;
        }

        // If we have Uris to grant, but no owner yet, go create one
        if (newUris != null && permissionOwner == null) {
            if (DBG) Slog.d(TAG, key + ": creating owner");
            permissionOwner = mUgmInternal.newUriPermissionOwner("NOTIF:" + key);
        }

        // If we have no Uris to grant, but an existing owner, go destroy it
        // When revoking permissions of a single listener, destroying the owner will revoke
        // permissions of other listeners who need to keep access.
        if (newUris == null && permissionOwner != null && !onlyRevokeCurrentTarget) {
            destroyPermissionOwner(permissionOwner, UserHandle.getUserId(oldRecord.getUid()), key);
            permissionOwner = null;
        }

        // Grant access to new Uris
        if (newUris != null && permissionOwner != null) {
            for (int i = 0; i < newUris.size(); i++) {
                final Uri uri = newUris.valueAt(i);
                if (oldUris == null || !oldUris.contains(uri)) {
                    if (DBG) {
                        Slog.d(TAG, key + ": granting " + uri);
                    }
                    grantUriPermission(permissionOwner, uri, newRecord.getUid(), targetPkg,
                            targetUserId);
                }
            }
        }

        // Revoke access to old Uris
        if (oldUris != null && permissionOwner != null) {
            for (int i = 0; i < oldUris.size(); i++) {
                final Uri uri = oldUris.valueAt(i);
                if (newUris == null || !newUris.contains(uri)) {
                    if (DBG) Slog.d(TAG, key + ": revoking " + uri);
                    if (onlyRevokeCurrentTarget) {
                        // We're revoking permission from one listener only; other listeners may
                        // still need access because the notification may still exist
                        revokeUriPermission(permissionOwner, uri,
                                UserHandle.getUserId(oldRecord.getUid()), targetPkg, targetUserId);
                    } else {
                        // This is broad to unilaterally revoke permissions to this Uri as granted
                        // by this notification.  But this code-path can only be used when the
                        // reason for revoking is that the notification posted again without this
                        // Uri, not when removing an individual listener.
                        revokeUriPermission(permissionOwner, uri,
                                UserHandle.getUserId(oldRecord.getUid()),
                                null, UserHandle.USER_ALL);
                    }
                }
            }
        }

        if (newRecord != null) {
            newRecord.permissionOwner = permissionOwner;
        }
    }

    private void grantUriPermission(IBinder owner, Uri uri, int sourceUid, String targetPkg,
            int targetUserId) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;
        final long ident = Binder.clearCallingIdentity();
        try {
            mUgm.grantUriPermissionFromOwner(owner, sourceUid, targetPkg,
                    ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)),
                    targetUserId);
        } catch (RemoteException ignored) {
            // Ignored because we're in same process
        } catch (SecurityException e) {
            Slog.e(TAG, "Cannot grant uri access; " + sourceUid + " does not own " + uri);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void revokeUriPermission(IBinder owner, Uri uri, int sourceUserId, String targetPkg,
            int targetUserId) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;
        int userId = ContentProvider.getUserIdFromUri(uri, sourceUserId);

        final long ident = Binder.clearCallingIdentity();
        try {
            mUgmInternal.revokeUriPermissionFromOwner(
                    owner,
                    ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    userId, targetPkg, targetUserId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void destroyPermissionOwner(IBinder owner, int userId, String logKey) {
        final long ident = Binder.clearCallingIdentity();
        try {
            if (DBG) Slog.d(TAG, logKey + ": destroying owner");
            mUgmInternal.revokeUriPermissionFromOwner(owner, null, ~0, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Cancels a notification ONLY if it has all of the {@code mustHaveFlags}
     * and none of the {@code mustNotHaveFlags}.
     */
    void cancelNotification(final int callingUid, final int callingPid,
            final String pkg, final String tag, int id,
            final int mustHaveFlags, final int mustNotHaveFlags, final boolean sendDelete,
            final int userId, final int reason, final ManagedServiceInfo listener) {
        cancelNotification(callingUid, callingPid, pkg, tag, id, mustHaveFlags, mustNotHaveFlags,
                sendDelete, userId, reason, -1 /* rank */, -1 /* count */, listener);
    }

    /**
     * Cancels a notification ONLY if it has all of the {@code mustHaveFlags}
     * and none of the {@code mustNotHaveFlags}.
     */
    void cancelNotification(final int callingUid, final int callingPid,
            final String pkg, final String tag, final int id,
            final int mustHaveFlags, final int mustNotHaveFlags, final boolean sendDelete,
            final int userId, final int reason, int rank, int count,
            final ManagedServiceInfo listener) {
        // In enqueueNotificationInternal notifications are added by scheduling the
        // work on the worker handler. Hence, we also schedule the cancel on this
        // handler to avoid a scenario where an add notification call followed by a
        // remove notification call ends up in not removing the notification.
        mHandler.scheduleCancelNotification(new CancelNotificationRunnable(callingUid, callingPid,
                pkg, tag, id, mustHaveFlags, mustNotHaveFlags, sendDelete, userId, reason, rank,
                count, listener, SystemClock.elapsedRealtime()));
    }

    /**
     * Determine whether the userId applies to the notification in question, either because
     * they match exactly, or one of them is USER_ALL (which is treated as a wildcard).
     */
    private static boolean notificationMatchesUserId(NotificationRecord r, int userId,
            boolean isAutogroupSummary) {
        if (isAutogroupSummary) {
            return r.getUserId() == userId;
        } else {
            return
                // looking for USER_ALL notifications? match everything
                userId == UserHandle.USER_ALL
                        // a notification sent to USER_ALL matches any query
                        || r.getUserId() == UserHandle.USER_ALL
                        // an exact user match
                        || r.getUserId() == userId;
        }
    }

    /**
     * Determine whether the userId applies to the notification in question, either because
     * they match exactly, or one of them is USER_ALL (which is treated as a wildcard) or
     * because it matches one of the users profiles.
     */
    private boolean notificationMatchesCurrentProfiles(NotificationRecord r, int userId) {
        return notificationMatchesUserId(r, userId, false)
                || mUserProfiles.isCurrentProfile(r.getUserId());
    }

    /**
     * Cancels all notifications from a given package that have all of the
     * {@code mustHaveFlags} and none of the {@code mustNotHaveFlags}.
     */
    void cancelAllNotificationsInt(int callingUid, int callingPid, String pkg,
            @Nullable String channelId, int mustHaveFlags, int mustNotHaveFlags, int userId,
            int reason) {
        final long cancellationElapsedTimeMs = SystemClock.elapsedRealtime();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                EventLogTags.writeNotificationCancelAll(callingUid, callingPid,
                        pkg, userId, mustHaveFlags, mustNotHaveFlags, reason,
                        /* listener= */ null);

                synchronized (mNotificationLock) {
                    FlagChecker flagChecker = (int flags) -> {
                        if ((flags & mustHaveFlags) != mustHaveFlags) {
                            return false;
                        }
                        if ((flags & mustNotHaveFlags) != 0) {
                            return false;
                        }
                        return true;
                    };
                    cancelAllNotificationsByListLocked(mNotificationList, pkg,
                            true /*nullPkgIndicatesUserSwitch*/, channelId, flagChecker,
                            false /*includeCurrentProfiles*/, userId, false /*sendDelete*/, reason,
                            null /* listenerName */, true /* wasPosted */,
                            cancellationElapsedTimeMs);
                    cancelAllNotificationsByListLocked(mEnqueuedNotifications, pkg,
                            true /*nullPkgIndicatesUserSwitch*/, channelId, flagChecker,
                            false /*includeCurrentProfiles*/, userId, false /*sendDelete*/, reason,
                            null /* listenerName */, false /* wasPosted */,
                            cancellationElapsedTimeMs);
                    mSnoozeHelper.cancel(userId, pkg);
                }
            }
        });
    }

    private interface FlagChecker {
        // Returns false if these flags do not pass the defined flag test.
        public boolean apply(int flags);
    }

    @GuardedBy("mNotificationLock")
    private void cancelAllNotificationsByListLocked(ArrayList<NotificationRecord> notificationList,
            @Nullable String pkg, boolean nullPkgIndicatesUserSwitch, @Nullable String channelId,
            FlagChecker flagChecker, boolean includeCurrentProfiles, int userId, boolean sendDelete,
            int reason, String listenerName, boolean wasPosted,
            @ElapsedRealtimeLong long cancellationElapsedTimeMs) {
        Set<String> childNotifications = null;
        for (int i = notificationList.size() - 1; i >= 0; --i) {
            NotificationRecord r = notificationList.get(i);
            if (includeCurrentProfiles) {
                if (!notificationMatchesCurrentProfiles(r, userId)) {
                    continue;
                }
            } else if (!notificationMatchesUserId(r, userId, false)) {
                continue;
            }
            // Don't remove notifications to all, if there's no package name specified
            if (nullPkgIndicatesUserSwitch && pkg == null && r.getUserId() == UserHandle.USER_ALL) {
                continue;
            }
            if (!flagChecker.apply(r.getFlags())) {
                continue;
            }
            if (pkg != null && !r.getSbn().getPackageName().equals(pkg)) {
                continue;
            }
            if (channelId != null && !channelId.equals(r.getChannel().getId())) {
                continue;
            }
            if (r.getSbn().isGroup() && r.getNotification().isGroupChild()) {
                if (childNotifications == null) {
                    childNotifications = new HashSet<>();
                }
                childNotifications.add(r.getKey());
                continue;
            }
            notificationList.remove(i);
            mNotificationsByKey.remove(r.getKey());
            r.recordDismissalSentiment(NotificationStats.DISMISS_SENTIMENT_NEUTRAL);
            cancelNotificationLocked(r, sendDelete, reason, wasPosted, listenerName,
                    cancellationElapsedTimeMs);
        }
        if (childNotifications != null) {
            final int M = notificationList.size();
            for (int i = M - 1; i >= 0; i--) {
                NotificationRecord r = notificationList.get(i);
                if (childNotifications.contains(r.getKey())) {
                    // dismiss conditions were checked in the first loop and so don't need to be
                    // checked again
                    notificationList.remove(i);
                    mNotificationsByKey.remove(r.getKey());
                    r.recordDismissalSentiment(NotificationStats.DISMISS_SENTIMENT_NEUTRAL);
                    cancelNotificationLocked(r, sendDelete, reason, wasPosted, listenerName,
                            cancellationElapsedTimeMs);
                }
            }
            if (Flags.refactorAttentionHelper()) {
                mAttentionHelper.updateLightsLocked();
            } else {
                updateLightsLocked();
            }
        }
    }

    void snoozeNotificationInt(String key, long duration, String snoozeCriterionId,
            ManagedServiceInfo listener) {
        if (listener == null) {
            return;
        }
        String listenerName = listener.component.toShortString();
        if ((duration <= 0 && snoozeCriterionId == null) || key == null) {
            return;
        }
        synchronized (mNotificationLock) {
            final NotificationRecord r = findInCurrentAndSnoozedNotificationByKeyLocked(key);
            if (r == null) {
                return;
            }
            if (!listener.enabledAndUserMatches(r.getSbn().getNormalizedUserId())){
                return;
            }
        }

        if (DBG) {
            Slog.d(TAG, String.format("snooze event(%s, %d, %s, %s)", key, duration,
                    snoozeCriterionId, listenerName));
        }
        // Needs to post so that it can cancel notifications not yet enqueued.
        mHandler.post(new SnoozeNotificationRunnable(key, duration, snoozeCriterionId));
    }

    void unsnoozeNotificationInt(String key, ManagedServiceInfo listener, boolean muteOnReturn) {
        String listenerName = listener == null ? null : listener.component.toShortString();
        if (DBG) {
            Slog.d(TAG, String.format("unsnooze event(%s, %s)", key, listenerName));
        }
        mSnoozeHelper.repost(key, muteOnReturn);
        handleSavePolicyFile();
    }

    @GuardedBy("mNotificationLock")
    void cancelAllLocked(int callingUid, int callingPid, int userId, int reason,
            ManagedServiceInfo listener, boolean includeCurrentProfiles) {
        final long cancellationElapsedTimeMs = SystemClock.elapsedRealtime();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (mNotificationLock) {
                    String listenerName =
                            listener == null ? null : listener.component.toShortString();
                    EventLogTags.writeNotificationCancelAll(callingUid, callingPid,
                            null, userId, 0, 0, reason, listenerName);

                    FlagChecker flagChecker = (int flags) -> {
                        int flagsToCheck = FLAG_ONGOING_EVENT | FLAG_NO_CLEAR;
                        if (REASON_LISTENER_CANCEL_ALL == reason
                                || REASON_CANCEL_ALL == reason) {
                            flagsToCheck |= FLAG_BUBBLE;
                        }
                        if ((flags & flagsToCheck) != 0) {
                            return false;
                        }
                        return true;
                    };

                    cancelAllNotificationsByListLocked(mNotificationList,
                            null, false /*nullPkgIndicatesUserSwitch*/, null, flagChecker,
                            includeCurrentProfiles, userId, true /*sendDelete*/, reason,
                            listenerName, true, cancellationElapsedTimeMs);
                    cancelAllNotificationsByListLocked(mEnqueuedNotifications,
                            null, false /*nullPkgIndicatesUserSwitch*/, null,
                            flagChecker, includeCurrentProfiles, userId, true /*sendDelete*/,
                            reason, listenerName, false, cancellationElapsedTimeMs);
                    mSnoozeHelper.cancel(userId, includeCurrentProfiles);
                }
            }
        });
    }

    // Warning: The caller is responsible for invoking updateLightsLocked().
    @GuardedBy("mNotificationLock")
    private void cancelGroupChildrenLocked(NotificationRecord r, int callingUid, int callingPid,
            String listenerName, boolean sendDelete, FlagChecker flagChecker, int reason,
            @ElapsedRealtimeLong long cancellationElapsedTimeMs) {
        Notification n = r.getNotification();
        if (!n.isGroupSummary()) {
            return;
        }

        String pkg = r.getSbn().getPackageName();

        if (pkg == null) {
            if (DBG) Slog.e(TAG, "No package for group summary: " + r.getKey());
            return;
        }

        cancelGroupChildrenByListLocked(mNotificationList, r, callingUid, callingPid, listenerName,
                sendDelete, true, flagChecker, reason, cancellationElapsedTimeMs);
        cancelGroupChildrenByListLocked(mEnqueuedNotifications, r, callingUid, callingPid,
                listenerName, sendDelete, false, flagChecker, reason, cancellationElapsedTimeMs);
    }

    @GuardedBy("mNotificationLock")
    private void cancelGroupChildrenByListLocked(ArrayList<NotificationRecord> notificationList,
            NotificationRecord parentNotification, int callingUid, int callingPid,
            String listenerName, boolean sendDelete, boolean wasPosted, FlagChecker flagChecker,
            int reason, @ElapsedRealtimeLong long cancellationElapsedTimeMs) {
        final String pkg = parentNotification.getSbn().getPackageName();
        final int userId = parentNotification.getUserId();
        final int childReason = REASON_GROUP_SUMMARY_CANCELED;
        for (int i = notificationList.size() - 1; i >= 0; i--) {
            final NotificationRecord childR = notificationList.get(i);
            final StatusBarNotification childSbn = childR.getSbn();
            if ((childSbn.isGroup() && !childSbn.getNotification().isGroupSummary()) &&
                    childR.getGroupKey().equals(parentNotification.getGroupKey())
                    && (flagChecker == null || flagChecker.apply(childR.getFlags()))
                    && (!childR.getChannel().isImportantConversation()
                            || reason != REASON_CANCEL)) {
                EventLogTags.writeNotificationCancel(callingUid, callingPid, pkg, childSbn.getId(),
                        childSbn.getTag(), userId, 0, 0, childReason, listenerName);
                notificationList.remove(i);
                mNotificationsByKey.remove(childR.getKey());
                cancelNotificationLocked(childR, sendDelete, childReason, wasPosted, listenerName,
                        cancellationElapsedTimeMs);
            }
        }
    }

    @GuardedBy("mNotificationLock")
    void updateLightsLocked()
    {
        if (mNotificationLight == null) {
            return;
        }

        // handle notification lights
        NotificationRecord ledNotification = null;
        while (ledNotification == null && !mLights.isEmpty()) {
            final String owner = mLights.get(mLights.size() - 1);
            ledNotification = mNotificationsByKey.get(owner);
            if (ledNotification == null) {
                Slog.wtfStack(TAG, "LED Notification does not exist: " + owner);
                mLights.remove(owner);
            }
        }

        // Don't flash while we are in a call or screen is on
        if (ledNotification == null || isInCall() || mScreenOn) {
            mNotificationLight.turnOff();
        } else {
            NotificationRecord.Light light = ledNotification.getLight();
            if (light != null && mNotificationPulseEnabled) {
                // pulse repeatedly
                mNotificationLight.setFlashing(light.color, LogicalLight.LIGHT_FLASH_TIMED,
                        light.onMs, light.offMs);
            }
        }
    }

    @GuardedBy("mNotificationLock")
    @NonNull
    List<NotificationRecord> findCurrentAndSnoozedGroupNotificationsLocked(String pkg,
            String groupKey, int userId) {
        List<NotificationRecord> records = mSnoozeHelper.getNotifications(pkg, groupKey, userId);
        records.addAll(findGroupNotificationsLocked(pkg, groupKey, userId));
        return records;
    }

    @GuardedBy("mNotificationLock")
    @NonNull List<NotificationRecord> findGroupNotificationsLocked(String pkg,
            String groupKey, int userId) {
        List<NotificationRecord> records = new ArrayList<>();
        records.addAll(findGroupNotificationByListLocked(mNotificationList, pkg, groupKey, userId));
        records.addAll(
                findGroupNotificationByListLocked(mEnqueuedNotifications, pkg, groupKey, userId));
        return records;
    }

    @GuardedBy("mNotificationLock")
    private NotificationRecord findInCurrentAndSnoozedNotificationByKeyLocked(String key) {
        NotificationRecord r = findNotificationByKeyLocked(key);
        if (r == null) {
            r = mSnoozeHelper.getNotification(key);
        }
        return r;

    }

    @GuardedBy("mNotificationLock")
    private @NonNull List<NotificationRecord> findGroupNotificationByListLocked(
            ArrayList<NotificationRecord> list, String pkg, String groupKey, int userId) {
        List<NotificationRecord> records = new ArrayList<>();
        final int len = list.size();
        for (int i = 0; i < len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId, false) && r.getGroupKey().equals(groupKey)
                    && r.getSbn().getPackageName().equals(pkg)) {
                records.add(r);
            }
        }
        return records;
    }

    // Searches both enqueued and posted notifications by key.
    // TODO: need to combine a bunch of these getters with slightly different behavior.
    // TODO: Should enqueuing just add to mNotificationsByKey instead?
    @GuardedBy("mNotificationLock")
    private NotificationRecord findNotificationByKeyLocked(String key) {
        NotificationRecord r;
        if ((r = findNotificationByListLocked(mNotificationList, key)) != null) {
            return r;
        }
        if ((r = findNotificationByListLocked(mEnqueuedNotifications, key)) != null) {
            return r;
        }
        return null;
    }

    @GuardedBy("mNotificationLock")
    NotificationRecord findNotificationLocked(String pkg, String tag, int id, int userId) {
        NotificationRecord r;
        if ((r = findNotificationByListLocked(mNotificationList, pkg, tag, id, userId)) != null) {
            return r;
        }
        if ((r = findNotificationByListLocked(mEnqueuedNotifications, pkg, tag, id, userId))
                != null) {
            return r;
        }
        return null;
    }

    @Nullable
    private static NotificationRecord findNotificationByListLocked(
            ArrayList<NotificationRecord> list, String pkg, String tag, int id, int userId) {
        final int len = list.size();
        for (int i = 0; i < len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId, (r.getFlags() & GroupHelper.BASE_FLAGS) != 0)
                    && r.getSbn().getId() == id && TextUtils.equals(r.getSbn().getTag(), tag)
                    && r.getSbn().getPackageName().equals(pkg)) {
                return r;
            }
        }
        return null;
    }

    private static List<NotificationRecord> findNotificationsByListLocked(
            ArrayList<NotificationRecord> list, String pkg, String tag, int id, int userId) {
        List<NotificationRecord> matching = new ArrayList<>();
        final int len = list.size();
        for (int i = 0; i < len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId, false) && r.getSbn().getId() == id
                    && TextUtils.equals(r.getSbn().getTag(), tag)
                    && r.getSbn().getPackageName().equals(pkg)) {
                matching.add(r);
            }
        }
        return matching;
    }

    @Nullable
    private static NotificationRecord findNotificationByListLocked(
            ArrayList<NotificationRecord> list, String key) {
        final int N = list.size();
        for (int i = 0; i < N; i++) {
            if (key.equals(list.get(i).getKey())) {
                return list.get(i);
            }
        }
        return null;
    }

    @GuardedBy("mNotificationLock")
    int indexOfNotificationLocked(String key) {
        final int N = mNotificationList.size();
        for (int i = 0; i < N; i++) {
            if (key.equals(mNotificationList.get(i).getKey())) {
                return i;
            }
        }
        return -1;
    }

    private void hideNotificationsForPackages(@NonNull String[] pkgs, @NonNull int[] uidList) {
        synchronized (mNotificationLock) {
            Set<Integer> uidSet = Arrays.stream(uidList).boxed().collect(Collectors.toSet());
            List<String> pkgList = Arrays.asList(pkgs);
            List<NotificationRecord> changedNotifications = new ArrayList<>();
            int numNotifications = mNotificationList.size();
            for (int i = 0; i < numNotifications; i++) {
                NotificationRecord rec = mNotificationList.get(i);
                if (pkgList.contains(rec.getSbn().getPackageName())
                        && uidSet.contains(rec.getUid())) {
                    rec.setHidden(true);
                    changedNotifications.add(rec);
                }
            }

            mListeners.notifyHiddenLocked(changedNotifications);
        }
    }

    private void unhideNotificationsForPackages(@NonNull String[] pkgs,
            @NonNull int[] uidList) {
        synchronized (mNotificationLock) {
            Set<Integer> uidSet = Arrays.stream(uidList).boxed().collect(Collectors.toSet());
            List<String> pkgList = Arrays.asList(pkgs);
            List<NotificationRecord> changedNotifications = new ArrayList<>();
            int numNotifications = mNotificationList.size();
            for (int i = 0; i < numNotifications; i++) {
                NotificationRecord rec = mNotificationList.get(i);
                if (pkgList.contains(rec.getSbn().getPackageName())
                        && uidSet.contains(rec.getUid())) {
                    rec.setHidden(false);
                    changedNotifications.add(rec);
                }
            }

            mListeners.notifyUnhiddenLocked(changedNotifications);
        }
    }

    private void cancelNotificationsWhenEnterLockDownMode(int userId) {
        synchronized (mNotificationLock) {
            int numNotifications = mNotificationList.size();
            for (int i = 0; i < numNotifications; i++) {
                NotificationRecord rec = mNotificationList.get(i);
                if (rec.getUser().getIdentifier() != userId) {
                    continue;
                }
                mListeners.notifyRemovedLocked(rec, REASON_LOCKDOWN,
                        rec.getStats());
            }

        }
    }

    private void postNotificationsWhenExitLockDownMode(int userId) {
        synchronized (mNotificationLock) {
            int numNotifications = mNotificationList.size();
            // Set the delay to spread out the burst of notifications.
            long delay = 0;
            for (int i = 0; i < numNotifications; i++) {
                NotificationRecord rec = mNotificationList.get(i);
                if (rec.getUser().getIdentifier() != userId) {
                    continue;
                }
                mHandler.postDelayed(() -> {
                    synchronized (mNotificationLock) {
                        mListeners.notifyPostedLocked(rec, rec);
                    }
                }, delay);
                delay += 20;
            }
        }
    }

    private void updateNotificationPulse() {
        synchronized (mNotificationLock) {
            updateLightsLocked();
        }
    }

    protected boolean isCallingUidSystem() {
        final int uid = Binder.getCallingUid();
        return uid == Process.SYSTEM_UID;
    }

    protected boolean isCallingAppIdSystem() {
        final int uid = Binder.getCallingUid();
        final int appid = UserHandle.getAppId(uid);
        return appid == Process.SYSTEM_UID;
    }

    protected boolean isUidSystemOrPhone(int uid) {
        final int appid = UserHandle.getAppId(uid);
        return (appid == Process.SYSTEM_UID || appid == Process.PHONE_UID
                || uid == Process.ROOT_UID);
    }

    // TODO: Most calls should probably move to isCallerSystem.
    protected boolean isCallerSystemOrPhone() {
        return isUidSystemOrPhone(Binder.getCallingUid());
    }

    @VisibleForTesting
    protected boolean isCallerIsSystemOrSystemUi() {
        if (isCallerSystemOrPhone()) {
            return true;
        }
        return getContext().checkCallingPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
                == PERMISSION_GRANTED;
    }

    private boolean isCallerIsSystemOrSysemUiOrShell() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
            return true;
        }
        return isCallerIsSystemOrSystemUi();
    }

    private void checkCallerIsSystemOrShell() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
            return;
        }
        checkCallerIsSystem();
    }

    private void checkCallerIsSystem() {
        if (isCallerSystemOrPhone()) {
            return;
        }
        throw new SecurityException("Disallowed call for uid " + Binder.getCallingUid());
    }

    private void checkCallerIsSystemOrSystemUiOrShell() {
        checkCallerIsSystemOrSystemUiOrShell(null);
    }

    private void checkCallerIsSystemOrSystemUiOrShell(String message) {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
            return;
        }
        if (isCallerSystemOrPhone()) {
            return;
        }
        getContext().enforceCallingPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                message);
    }

    private void checkCallerIsSystemOrSameApp(String pkg) {
        if (isCallerSystemOrPhone()) {
            return;
        }
        checkCallerIsSameApp(pkg);
    }

    private boolean isCallerAndroid(String callingPkg, int uid) {
        return isUidSystemOrPhone(uid) && callingPkg != null
                && PackageManagerService.PLATFORM_PACKAGE_NAME.equals(callingPkg);
    }

    /**
     * Check if the notification is of a category type that is restricted to system use only,
     * if so throw SecurityException
     */
    private void checkRestrictedCategories(final Notification notification) {
        try {
            if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0)) {
                return;
            }
        } catch (RemoteException re) {
            if (DBG) Slog.e(TAG, "Unable to confirm if it's safe to skip category "
                    + "restrictions check thus the check will be done anyway");
        }
        if (Notification.CATEGORY_CAR_EMERGENCY.equals(notification.category)
                || Notification.CATEGORY_CAR_WARNING.equals(notification.category)
                || Notification.CATEGORY_CAR_INFORMATION.equals(notification.category)) {
            getContext().enforceCallingPermission(
                    android.Manifest.permission.SEND_CATEGORY_CAR_NOTIFICATIONS,
                    String.format("Notification category %s restricted",
                            notification.category));
        }
    }

    @VisibleForTesting
    boolean isCallerInstantApp(int callingUid, int userId) {
        // System is always allowed to act for ephemeral apps.
        if (isUidSystemOrPhone(callingUid)) {
            return false;
        }

        if (userId == UserHandle.USER_ALL) {
            userId = USER_SYSTEM;
        }

        try {
            final String[] pkgs = mPackageManager.getPackagesForUid(callingUid);
            if (pkgs == null) {
                throw new SecurityException("Unknown uid " + callingUid);
            }
            final String pkg = pkgs[0];
            mAppOps.checkPackage(callingUid, pkg);

            ApplicationInfo ai = mPackageManager.getApplicationInfo(pkg, 0, userId);
            if (ai == null) {
                throw new SecurityException("Unknown package " + pkg);
            }
            return ai.isInstantApp();
        } catch (RemoteException re) {
            throw new SecurityException("Unknown uid " + callingUid, re);
        }
    }

    private void checkCallerIsSameApp(String pkg) {
        checkCallerIsSameApp(pkg, Binder.getCallingUid(), UserHandle.getCallingUserId());
    }

    private void checkCallerIsSameApp(String pkg, int uid, int userId) {
        if (uid == Process.ROOT_UID && ROOT_PKG.equals(pkg)) {
            return;
        }
        if (!mPackageManagerInternal.isSameApp(pkg, uid, userId)) {
            throw new SecurityException("Package " + pkg + " is not owned by uid " + uid);
        }
    }

    private boolean isCallerSameApp(String pkg, int uid, int userId) {
        try {
            checkCallerIsSameApp(pkg, uid, userId);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static String callStateToString(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE: return "CALL_STATE_IDLE";
            case TelephonyManager.CALL_STATE_RINGING: return "CALL_STATE_RINGING";
            case TelephonyManager.CALL_STATE_OFFHOOK: return "CALL_STATE_OFFHOOK";
            default: return "CALL_STATE_UNKNOWN_" + state;
        }
    }

    /**
     * Generates a NotificationRankingUpdate from 'sbns', considering only
     * notifications visible to the given listener.
     */
    @GuardedBy("mNotificationLock")
    NotificationRankingUpdate makeRankingUpdateLocked(ManagedServiceInfo info) {
        final int N = mNotificationList.size();
        final ArrayList<NotificationListenerService.Ranking> rankings = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            NotificationRecord record = mNotificationList.get(i);
            if (isInLockDownMode(record.getUser().getIdentifier())) {
                continue;
            }
            if (!isVisibleToListener(record.getSbn(), record.getNotificationType(), info)) {
                continue;
            }
            final String key = record.getSbn().getKey();
            final NotificationListenerService.Ranking ranking =
                    new NotificationListenerService.Ranking();
            ranking.populate(
                    key,
                    rankings.size(),
                    !record.isIntercepted(),
                    record.getPackageVisibilityOverride(),
                    record.getSuppressedVisualEffects(),
                    record.getImportance(),
                    record.getImportanceExplanation(),
                    record.getSbn().getOverrideGroupKey(),
                    record.getChannel(),
                    record.getPeopleOverride(),
                    record.getSnoozeCriteria(),
                    record.canShowBadge(),
                    record.getUserSentiment(),
                    record.isHidden(),
                    record.getLastAudiblyAlertedMs(),
                    record.getSound() != null || record.getVibration() != null,
                    record.getSystemGeneratedSmartActions(),
                    record.getSmartReplies(),
                    record.canBubble(),
                    record.isTextChanged(),
                    record.isConversation(),
                    record.getShortcutInfo(),
                    record.getRankingScore() == 0
                            ? RANKING_UNCHANGED
                            : (record.getRankingScore() > 0 ?  RANKING_PROMOTED : RANKING_DEMOTED),
                    record.getNotification().isBubbleNotification(),
                    record.getProposedImportance(),
                    record.hasSensitiveContent()
            );
            rankings.add(ranking);
        }

        return new NotificationRankingUpdate(
                rankings.toArray(new NotificationListenerService.Ranking[0]));
    }

    boolean isInLockDownMode(int userId) {
        return mStrongAuthTracker.isInLockDownMode(userId);
    }

    boolean hasCompanionDevice(ManagedServiceInfo info) {
        return hasCompanionDevice(info.component.getPackageName(),
                info.userid, /* withDeviceProfile= */ null);
    }

    private boolean hasCompanionDevice(String pkg, @UserIdInt int userId,
            @Nullable @AssociationRequest.DeviceProfile String withDeviceProfile) {
        if (mCompanionManager == null) {
            mCompanionManager = getCompanionManager();
        }
        // Companion mgr doesn't exist on all device types
        if (mCompanionManager == null) {
            return false;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            List<AssociationInfo> associations = mCompanionManager.getAssociations(pkg, userId);
            for (AssociationInfo association : associations) {
                if (withDeviceProfile == null || withDeviceProfile.equals(
                        association.getDeviceProfile())) {
                    return true;
                }
            }
        } catch (SecurityException se) {
            // Not a privileged listener
        } catch (RemoteException re) {
            Slog.e(TAG, "Cannot reach companion device service", re);
        } catch (Exception e) {
            Slog.e(TAG, "Cannot verify caller pkg=" + pkg + ", userId=" + userId, e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    protected ICompanionDeviceManager getCompanionManager() {
        return ICompanionDeviceManager.Stub.asInterface(
                ServiceManager.getService(Context.COMPANION_DEVICE_SERVICE));
    }

    @VisibleForTesting
    boolean isVisibleToListener(StatusBarNotification sbn, int notificationType,
            ManagedServiceInfo listener) {
        if (!listener.enabledAndUserMatches(sbn.getUserId())) {
            return false;
        }
        if (!isInteractionVisibleToListener(listener, sbn.getUserId())) {
            return false;
        }
        NotificationListenerFilter nls = mListeners.getNotificationListenerFilter(listener.mKey);
        if (nls != null
                && (!nls.isTypeAllowed(notificationType)
                || !nls.isPackageAllowed(
                        new VersionedPackage(sbn.getPackageName(), sbn.getUid())))) {
            return false;
        }
        return true;
    }

    /**
     * Returns whether the given assistant should be informed about interactions on the given user.
     *
     * Normally an assistant would be able to see all interactions on the current user and any
     * associated profiles because they are notification listeners, but since NASes have one
     * instance per user, we want to filter out interactions that are not for the user that the
     * given NAS is bound in.
     */
    @VisibleForTesting
    boolean isInteractionVisibleToListener(ManagedServiceInfo info, int userId) {
        boolean isAssistantService = isServiceTokenValid(info.getService());
        return !isAssistantService || info.isSameUser(userId);
    }

    private boolean isServiceTokenValid(IInterface service) {
        synchronized (mNotificationLock) {
            return mAssistants.isServiceTokenValidLocked(service);
        }
    }

    private boolean isPackageSuspendedForUser(String pkg, int uid) {
        final long identity = Binder.clearCallingIdentity();
        int userId = UserHandle.getUserId(uid);
        try {
            return mPackageManager.isPackageSuspendedForUser(pkg, userId);
        } catch (RemoteException re) {
            throw new SecurityException("Could not talk to package manager service");
        } catch (IllegalArgumentException ex) {
            // Package not found.
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @VisibleForTesting
    boolean canUseManagedServices(String pkg, Integer userId, String requiredPermission) {
        boolean canUseManagedServices = true;
        if (requiredPermission != null) {
            try {
                if (mPackageManager.checkPermission(requiredPermission, pkg, userId)
                        != PackageManager.PERMISSION_GRANTED) {
                    canUseManagedServices = false;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "can't talk to pm", e);
            }
        }

        return canUseManagedServices;
    }

    private class TrimCache {
        StatusBarNotification heavy;
        StatusBarNotification sbnClone;
        StatusBarNotification sbnCloneLight;

        TrimCache(StatusBarNotification sbn) {
            heavy = sbn;
        }

        StatusBarNotification ForListener(ManagedServiceInfo info) {
            if (mListeners.getOnNotificationPostedTrim(info) == TRIM_LIGHT) {
                if (sbnCloneLight == null) {
                    sbnCloneLight = heavy.cloneLight();
                }
                return sbnCloneLight;
            } else {
                if (sbnClone == null) {
                    sbnClone = heavy.clone();
                }
                return sbnClone;
            }
        }
    }

    private boolean isInCall() {
        if (mInCallStateOffHook) {
            return true;
        }
        int audioMode = mAudioManager.getMode();
        if (audioMode == AudioManager.MODE_IN_CALL
                || audioMode == AudioManager.MODE_IN_COMMUNICATION) {
            return true;
        }
        return false;
    }

    public class NotificationAssistants extends ManagedServices {
        static final String TAG_ENABLED_NOTIFICATION_ASSISTANTS = "enabled_assistants";

        private static final String ATT_TYPES = "types";

        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private Set<String> mAllowedAdjustments = new ArraySet<>();

        protected ComponentName mDefaultFromConfig = null;

        @Override
        protected void loadDefaultsFromConfig() {
            loadDefaultsFromConfig(true);
        }

        protected void loadDefaultsFromConfig(boolean addToDefault) {
            ArraySet<String> assistants = new ArraySet<>();
            assistants.addAll(Arrays.asList(mContext.getResources().getString(
                    com.android.internal.R.string.config_defaultAssistantAccessComponent)
                    .split(ManagedServices.ENABLED_SERVICES_SEPARATOR)));
            for (int i = 0; i < assistants.size(); i++) {
                ComponentName assistantCn = ComponentName
                        .unflattenFromString(assistants.valueAt(i));
                String packageName = assistants.valueAt(i);
                if (assistantCn != null) {
                    packageName = assistantCn.getPackageName();
                }
                if (TextUtils.isEmpty(packageName)) {
                    continue;
                }
                ArraySet<ComponentName> approved = queryPackageForServices(packageName,
                        MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, USER_SYSTEM);
                if (approved.contains(assistantCn)) {
                    if (addToDefault) {
                        // add the default loaded from config file to mDefaultComponents and
                        // mDefaultPackages
                        addDefaultComponentOrPackage(assistantCn.flattenToString());
                    } else {
                        // otherwise, store in the mDefaultFromConfig for NAS settings migration
                        mDefaultFromConfig = assistantCn;
                    }
                }
            }
        }

        ComponentName getDefaultFromConfig() {
            if (mDefaultFromConfig == null) {
                loadDefaultsFromConfig(false);
            }
            return mDefaultFromConfig;
        }

        @Override
        protected void upgradeUserSet() {
            for (int userId: mApproved.keySet()) {
                ArraySet<String> userSetServices = mUserSetServices.get(userId);
                mIsUserChanged.put(userId, (userSetServices != null && userSetServices.size() > 0));
            }
        }

        @Override
        protected void addApprovedList(String approved, int userId, boolean isPrimary,
                String userSet) {
            if (!TextUtils.isEmpty(approved)) {
                String[] approvedArray = approved.split(ENABLED_SERVICES_SEPARATOR);
                if (approvedArray.length > 1) {
                    Slog.d(TAG, "More than one approved assistants");
                    approved = approvedArray[0];
                }
            }
            super.addApprovedList(approved, userId, isPrimary, userSet);
        }

        public NotificationAssistants(Context context, Object lock, UserProfiles up,
                IPackageManager pm) {
            super(context, lock, up, pm);

            // Add all default allowed adjustment types.
            for (int i = 0; i < ALLOWED_ADJUSTMENTS.length; i++) {
                mAllowedAdjustments.add(ALLOWED_ADJUSTMENTS[i]);
            }
        }

        @Override
        protected Config getConfig() {
            Config c = new Config();
            c.caption = "notification assistant";
            c.serviceInterface = NotificationAssistantService.SERVICE_INTERFACE;
            c.xmlTag = TAG_ENABLED_NOTIFICATION_ASSISTANTS;
            c.secureSettingName = Settings.Secure.ENABLED_NOTIFICATION_ASSISTANT;
            c.bindPermission = Manifest.permission.BIND_NOTIFICATION_ASSISTANT_SERVICE;
            c.settingsAction = Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS;
            c.clientLabel = R.string.notification_ranker_binding_label;
            return c;
        }

        @Override
        protected IInterface asInterface(IBinder binder) {
            return INotificationListener.Stub.asInterface(binder);
        }

        @Override
        protected boolean checkType(IInterface service) {
            return service instanceof INotificationListener;
        }

        @Override
        protected void onServiceAdded(ManagedServiceInfo info) {
            mListeners.registerGuestService(info);
        }

        @Override
        protected void ensureFilters(ServiceInfo si, int userId) {
            // nothing to filter; no user visible settings for types/packages like other
            // listeners
        }

        @Override
        @GuardedBy("mNotificationLock")
        protected void onServiceRemovedLocked(ManagedServiceInfo removed) {
            mListeners.unregisterService(removed.service, removed.userid);
        }

        @Override
        public void onUserUnlocked(int user) {
            if (DEBUG) Slog.d(TAG, "onUserUnlocked u=" + user);
            // force rebind the assistant, as it might be keeping its own state in user locked
            // storage
            rebindServices(true, user);
        }

        @Override
        protected boolean allowRebindForParentUser() {
            return false;
        }

        @Override
        protected String getRequiredPermission() {
            // only signature/privileged apps can be bound.
            return android.Manifest.permission.REQUEST_NOTIFICATION_ASSISTANT_SERVICE;
        }

        protected List<String> getAllowedAssistantAdjustments() {
            synchronized (mLock) {
                List<String> types = new ArrayList<>();
                types.addAll(mAllowedAdjustments);
                return types;
            }
        }

        protected boolean isAdjustmentAllowed(String type) {
            synchronized (mLock) {
                return mAllowedAdjustments.contains(type);
            }
        }

        protected void onNotificationsSeenLocked(ArrayList<NotificationRecord> records) {
            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                ArrayList<String> keys = new ArrayList<>(records.size());
                for (NotificationRecord r : records) {
                    boolean sbnVisible = isVisibleToListener(
                            r.getSbn(), r.getNotificationType(), info)
                            && info.isSameUser(r.getUserId());
                    if (sbnVisible) {
                        keys.add(r.getKey());
                    }
                }

                if (!keys.isEmpty()) {
                    mHandler.post(() -> notifySeen(info, keys));
                }
            }
        }

        protected void onPanelRevealed(int items) {
            // send to all currently bounds NASes since notifications from both users will appear in
            // the panel
            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                mHandler.post(() -> {
                    final INotificationListener assistant = (INotificationListener) info.service;
                    try {
                        assistant.onPanelRevealed(items);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify assistant (panel revealed): " + info, ex);
                    }
                });
            }
        }

        protected void onPanelHidden() {
            // send to all currently bounds NASes since notifications from both users will appear in
            // the panel
            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                mHandler.post(() -> {
                    final INotificationListener assistant = (INotificationListener) info.service;
                    try {
                        assistant.onPanelHidden();
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify assistant (panel hidden): " + info, ex);
                    }
                });
            }
        }

        boolean hasUserSet(int userId) {
            Boolean userSet = mIsUserChanged.get(userId);
            return (userSet != null && userSet);
        }

        void setUserSet(int userId, boolean set) {
            mIsUserChanged.put(userId, set);
        }

        private void notifySeen(final ManagedServiceInfo info,
                final ArrayList<String> keys) {
            final INotificationListener assistant = (INotificationListener) info.service;
            try {
                assistant.onNotificationsSeen(keys);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify assistant (seen): " + info, ex);
            }
        }

        @GuardedBy("mNotificationLock")
        private void onNotificationEnqueuedLocked(final NotificationRecord r) {
            final boolean debug = isVerboseLogEnabled();
            if (debug) {
                Slog.v(TAG, "onNotificationEnqueuedLocked() called with: r = [" + r + "]");
            }
            final StatusBarNotification sbn = r.getSbn();

            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                boolean sbnVisible = isVisibleToListener(
                        sbn, r.getNotificationType(), info)
                        && info.isSameUser(r.getUserId());
                if (sbnVisible) {
                    TrimCache trimCache = new TrimCache(sbn);
                    final INotificationListener assistant = (INotificationListener) info.service;
                    final StatusBarNotification sbnToPost = trimCache.ForListener(info);
                    final StatusBarNotificationHolder sbnHolder =
                            new StatusBarNotificationHolder(sbnToPost);
                    try {
                        if (debug) {
                            Slog.v(TAG,
                                    "calling onNotificationEnqueuedWithChannel " + sbnHolder);
                        }
                        final NotificationRankingUpdate update = makeRankingUpdateLocked(info);
                        assistant.onNotificationEnqueuedWithChannel(sbnHolder, r.getChannel(),
                                update);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify assistant (enqueued): " + assistant, ex);
                    }
                }
            }
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantVisibilityChangedLocked(
                final NotificationRecord r,
                final boolean isVisible) {
            final String key = r.getSbn().getKey();
            if (DBG) {
                Slog.d(TAG, "notifyAssistantVisibilityChangedLocked: " + key);
            }
            notifyAssistantLocked(
                    r.getSbn(),
                    r.getNotificationType(),
                    true /* sameUserOnly */,
                    (assistant, sbnHolder) -> {
                        try {
                            assistant.onNotificationVisibilityChanged(key, isVisible);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (visible): " + assistant, ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantExpansionChangedLocked(
                final StatusBarNotification sbn,
                final int notificationType,
                final boolean isUserAction,
                final boolean isExpanded) {
            final String key = sbn.getKey();
            notifyAssistantLocked(
                    sbn,
                    notificationType,
                    true /* sameUserOnly */,
                    (assistant, sbnHolder) -> {
                        try {
                            assistant.onNotificationExpansionChanged(key, isUserAction, isExpanded);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (expanded): " + assistant, ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantNotificationDirectReplyLocked(
                final NotificationRecord r) {
            final String key = r.getKey();
            notifyAssistantLocked(
                    r.getSbn(),
                    r.getNotificationType(),
                    true /* sameUserOnly */,
                    (assistant, sbnHolder) -> {
                        try {
                            assistant.onNotificationDirectReply(key);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (expanded): " + assistant, ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantSuggestedReplySent(
                final StatusBarNotification sbn, int notificationType,
                CharSequence reply, boolean generatedByAssistant) {
            final String key = sbn.getKey();
            notifyAssistantLocked(
                    sbn,
                    notificationType,
                    true /* sameUserOnly */,
                    (assistant, sbnHolder) -> {
                        try {
                            assistant.onSuggestedReplySent(
                                    key,
                                    reply,
                                    generatedByAssistant
                                            ? NotificationAssistantService.SOURCE_FROM_ASSISTANT
                                            : NotificationAssistantService.SOURCE_FROM_APP);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (snoozed): " + assistant, ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantActionClicked(
                final NotificationRecord r, Notification.Action action,
                boolean generatedByAssistant) {
            final String key = r.getSbn().getKey();
            notifyAssistantLocked(
                    r.getSbn(),
                    r.getNotificationType(),
                    true /* sameUserOnly */,
                    (assistant, sbnHolder) -> {
                        try {
                            assistant.onActionClicked(
                                    key,
                                    action,
                                    generatedByAssistant
                                            ? NotificationAssistantService.SOURCE_FROM_ASSISTANT
                                            : NotificationAssistantService.SOURCE_FROM_APP);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (snoozed): " + assistant, ex);
                        }
                    });
        }

        /**
         * asynchronously notify the assistant that a notification has been snoozed until a
         * context
         */
        @GuardedBy("mNotificationLock")
        private void notifyAssistantSnoozedLocked(
                final NotificationRecord r, final String snoozeCriterionId) {
            notifyAssistantLocked(
                    r.getSbn(),
                    r.getNotificationType(),
                    true /* sameUserOnly */,
                    (assistant, sbnHolder) -> {
                        try {
                            assistant.onNotificationSnoozedUntilContext(
                                    sbnHolder, snoozeCriterionId);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (snoozed): " + assistant, ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantNotificationClicked(final NotificationRecord r) {
            final String key = r.getSbn().getKey();
            notifyAssistantLocked(
                    r.getSbn(),
                    r.getNotificationType(),
                    true /* sameUserOnly */,
                    (assistant, sbnHolder) -> {
                        try {
                            assistant.onNotificationClicked(key);
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "unable to notify assistant (clicked): " + assistant, ex);
                        }
                    });
        }

        @GuardedBy("mNotificationLock")
        void notifyAssistantFeedbackReceived(final NotificationRecord r, Bundle feedback) {
            final StatusBarNotification sbn = r.getSbn();

            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                boolean sbnVisible = isVisibleToListener(
                        sbn, r.getNotificationType(), info)
                        && info.isSameUser(r.getUserId());
                if (sbnVisible) {
                    final INotificationListener assistant = (INotificationListener) info.service;
                    try {
                        final NotificationRankingUpdate update = makeRankingUpdateLocked(info);
                        assistant.onNotificationFeedbackReceived(sbn.getKey(), update, feedback);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify assistant (feedback): " + assistant, ex);
                    }
                }
            }
        }

        /**
         * Notifies the assistant something about the specified notification, only assistant
         * that is visible to the notification will be notified.
         *
         * @param sbn          the notification object that the update is about.
         * @param sameUserOnly should the update  be sent to the assistant in the same user only.
         * @param callback     the callback that provides the assistant to be notified, executed
         *                     in WorkerHandler.
         */
        @GuardedBy("mNotificationLock")
        private void notifyAssistantLocked(
                final StatusBarNotification sbn,
                int notificationType,
                boolean sameUserOnly,
                BiConsumer<INotificationListener, StatusBarNotificationHolder> callback) {
            TrimCache trimCache = new TrimCache(sbn);
            // There should be only one, but it's a list, so while we enforce
            // singularity elsewhere, we keep it general here, to avoid surprises.

            final boolean debug = isVerboseLogEnabled();
            if (debug) {
                Slog.v(TAG,
                        "notifyAssistantLocked() called with: sbn = [" + sbn + "], sameUserOnly = ["
                                + sameUserOnly + "], callback = [" + callback + "]");
            }
            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                boolean sbnVisible = isVisibleToListener(sbn, notificationType, info)
                        && (!sameUserOnly || info.isSameUser(sbn.getUserId()));
                if (debug) {
                    Slog.v(TAG, "notifyAssistantLocked info=" + info + " snbVisible=" + sbnVisible);
                }
                if (!sbnVisible) {
                    continue;
                }
                final INotificationListener assistant = (INotificationListener) info.service;
                final StatusBarNotification sbnToPost = trimCache.ForListener(info);
                final StatusBarNotificationHolder sbnHolder =
                        new StatusBarNotificationHolder(sbnToPost);
                mHandler.post(() -> callback.accept(assistant, sbnHolder));
            }
        }

        public boolean isEnabled() {
            return !getServices().isEmpty();
        }

        protected void resetDefaultAssistantsIfNecessary() {
            final List<UserInfo> activeUsers = mUm.getAliveUsers();
            for (UserInfo userInfo : activeUsers) {
                int userId = userInfo.getUserHandle().getIdentifier();
                if (!hasUserSet(userId)) {
                    if (!isNASMigrationDone(userId)) {
                        resetDefaultFromConfig();
                        setNASMigrationDone(userId);
                    }
                    Slog.d(TAG, "Approving default notification assistant for user " + userId);
                    setDefaultAssistantForUser(userId);
                }
            }
        }

        protected void resetDefaultFromConfig() {
            clearDefaults();
            loadDefaultsFromConfig();
        }

        protected void clearDefaults() {
            mDefaultComponents.clear();
            mDefaultPackages.clear();
        }

        @Override
        protected void setPackageOrComponentEnabled(String pkgOrComponent, int userId,
                boolean isPrimary, boolean enabled, boolean userSet) {
            // Ensures that only one component is enabled at a time
            if (enabled) {
                List<ComponentName> allowedComponents = getAllowedComponents(userId);
                if (!allowedComponents.isEmpty()) {
                    ComponentName currentComponent = CollectionUtils.firstOrNull(allowedComponents);
                    if (currentComponent.flattenToString().equals(pkgOrComponent)) return;
                    setNotificationAssistantAccessGrantedForUserInternal(
                            currentComponent, userId, false, userSet);
                }
            }
            super.setPackageOrComponentEnabled(pkgOrComponent, userId, isPrimary, enabled, userSet);
        }

        private boolean isVerboseLogEnabled() {
            return Log.isLoggable("notification_assistant", Log.VERBOSE);
        }
    }

    /**
     * Asynchronously notify all listeners about a posted (new or updated) notification. This
     * should be called from {@link PostNotificationRunnable} to "complete" the post (since SysUI is
     * one of the NLSes, and will display it to the user).
     *
     * <p>This method will call {@link PostNotificationTracker#finish} on the supplied tracker
     * when every {@link NotificationListenerService} has received the news.
     *
     * <p>Also takes care of removing a notification that has been visible to a listener before,
     * but isn't anymore.
     */
    @GuardedBy("mNotificationLock")
    private void notifyListenersPostedAndLogLocked(NotificationRecord r, NotificationRecord old,
            @NonNull PostNotificationTracker tracker,
            @Nullable NotificationRecordLogger.NotificationReported report) {
        List<Runnable> listenerCalls = mListeners.prepareNotifyPostedLocked(r, old, true);
        mHandler.post(() -> {
            for (Runnable listenerCall : listenerCalls) {
                listenerCall.run();
            }

            long postDurationMillis = tracker.finish();
            if (report != null) {
                report.post_duration_millis = postDurationMillis;
                mNotificationRecordLogger.logNotificationPosted(report);
            }
        });
    }

    public class NotificationListeners extends ManagedServices {
        static final String TAG_ENABLED_NOTIFICATION_LISTENERS = "enabled_listeners";
        static final String TAG_REQUESTED_LISTENERS = "request_listeners";
        static final String TAG_REQUESTED_LISTENER = "listener";
        static final String ATT_COMPONENT = "component";
        static final String ATT_TYPES = "types";
        static final String ATT_PKG = "pkg";
        static final String ATT_UID = "uid";
        static final String TAG_APPROVED = "allowed";
        static final String TAG_DISALLOWED= "disallowed";
        static final String XML_SEPARATOR = ",";
        static final String FLAG_SEPARATOR = "\\|";

        private final ArraySet<ManagedServiceInfo> mLightTrimListeners = new ArraySet<>();
        @GuardedBy("mRequestedNotificationListeners")
        private final ArrayMap<Pair<ComponentName, Integer>, NotificationListenerFilter>
                mRequestedNotificationListeners = new ArrayMap<>();
        private final boolean mIsHeadlessSystemUserMode;

        public NotificationListeners(Context context, Object lock, UserProfiles userProfiles,
                IPackageManager pm) {
            this(context, lock, userProfiles, pm, UserManager.isHeadlessSystemUserMode());
        }

        @VisibleForTesting
        public NotificationListeners(Context context, Object lock, UserProfiles userProfiles,
                IPackageManager pm, boolean isHeadlessSystemUserMode) {
            super(context, lock, userProfiles, pm);
            this.mIsHeadlessSystemUserMode = isHeadlessSystemUserMode;
        }

        @Override
        protected void setPackageOrComponentEnabled(String pkgOrComponent, int userId,
                boolean isPrimary, boolean enabled, boolean userSet) {
            super.setPackageOrComponentEnabled(pkgOrComponent, userId, isPrimary, enabled, userSet);

            mContext.sendBroadcastAsUser(
                    new Intent(ACTION_NOTIFICATION_LISTENER_ENABLED_CHANGED)
                            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                    UserHandle.of(userId), null);
        }

        @Override
        protected void loadDefaultsFromConfig() {
            String defaultListenerAccess = mContext.getResources().getString(
                    R.string.config_defaultListenerAccessPackages);
            if (defaultListenerAccess != null) {
                String[] listeners =
                        defaultListenerAccess.split(ManagedServices.ENABLED_SERVICES_SEPARATOR);
                for (int i = 0; i < listeners.length; i++) {
                    if (TextUtils.isEmpty(listeners[i])) {
                        continue;
                    }
                    int packageQueryFlags = MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
                    // In the headless system user mode, packages might not be installed for the
                    // system user. Match packages for any user since apps can be installed only for
                    // non-system users and would be considering uninstalled for the system user.
                    if (mIsHeadlessSystemUserMode) {
                        packageQueryFlags += MATCH_ANY_USER;
                    }
                    ArraySet<ComponentName> approvedListeners =
                            this.queryPackageForServices(listeners[i], packageQueryFlags,
                                    USER_SYSTEM);
                    for (int k = 0; k < approvedListeners.size(); k++) {
                        ComponentName cn = approvedListeners.valueAt(k);
                        addDefaultComponentOrPackage(cn.flattenToString());
                    }
                }
            }
        }

        @Override
        protected int getBindFlags() {
            // Most of the same flags as the base, but also add BIND_NOT_PERCEPTIBLE
            // because too many 3P apps could be kept in memory as notification listeners and
            // cause extreme memory pressure.
            // TODO: Change the binding lifecycle of NotificationListeners to avoid this situation.
            return BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE
                    | BIND_NOT_PERCEPTIBLE | BIND_ALLOW_WHITELIST_MANAGEMENT;
        }

        @Override
        protected Config getConfig() {
            Config c = new Config();
            c.caption = "notification listener";
            c.serviceInterface = NotificationListenerService.SERVICE_INTERFACE;
            c.xmlTag = TAG_ENABLED_NOTIFICATION_LISTENERS;
            c.secureSettingName = Settings.Secure.ENABLED_NOTIFICATION_LISTENERS;
            c.bindPermission = android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE;
            c.settingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS;
            c.clientLabel = R.string.notification_listener_binding_label;
            return c;
        }

        @Override
        protected IInterface asInterface(IBinder binder) {
            return INotificationListener.Stub.asInterface(binder);
        }

        @Override
        protected boolean checkType(IInterface service) {
            return service instanceof INotificationListener;
        }

        @Override
        public void onServiceAdded(ManagedServiceInfo info) {
            final INotificationListener listener = (INotificationListener) info.service;
            final NotificationRankingUpdate update;
            synchronized (mNotificationLock) {
                update = makeRankingUpdateLocked(info);
                updateUriPermissionsForActiveNotificationsLocked(info, true);
            }
            try {
                listener.onListenerConnected(update);
            } catch (RemoteException e) {
                // we tried
            }
        }

        @Override
        @GuardedBy("mNotificationLock")
        protected void onServiceRemovedLocked(ManagedServiceInfo removed) {
            updateUriPermissionsForActiveNotificationsLocked(removed, false);
            if (removeDisabledHints(removed)) {
                updateListenerHintsLocked();
                updateEffectsSuppressorLocked();
            }
            mLightTrimListeners.remove(removed);
        }

        @Override
        public void onUserRemoved(int user) {
            super.onUserRemoved(user);
            synchronized (mRequestedNotificationListeners) {
                for (int i = mRequestedNotificationListeners.size() - 1; i >= 0; i--) {
                    if (mRequestedNotificationListeners.keyAt(i).second == user) {
                        mRequestedNotificationListeners.removeAt(i);
                    }
                }
            }
        }

        @Override
        protected boolean allowRebindForParentUser() {
            return true;
        }

        @Override
        public void onPackagesChanged(boolean removingPackage, String[] pkgList, int[] uidList) {
            super.onPackagesChanged(removingPackage, pkgList, uidList);

            synchronized (mRequestedNotificationListeners) {
                // Since the default behavior is to allow everything, we don't need to explicitly
                // handle package add or update. they will be added to the xml file on next boot or
                // when the user tries to change the settings.
                if (removingPackage) {
                    for (int i = 0; i < pkgList.length; i++) {
                        String pkg = pkgList[i];
                        int userId = UserHandle.getUserId(uidList[i]);
                        for (int j = mRequestedNotificationListeners.size() - 1; j >= 0; j--) {
                            Pair<ComponentName, Integer> key =
                                    mRequestedNotificationListeners.keyAt(j);
                            if (key.second == userId && key.first.getPackageName().equals(pkg)) {
                                mRequestedNotificationListeners.removeAt(j);
                            }
                        }
                    }
                }

                // clean up anything in the disallowed pkgs list
                for (int i = 0; i < pkgList.length; i++) {
                    String pkg = pkgList[i];
                    for (int j = mRequestedNotificationListeners.size() - 1; j >= 0; j--) {
                        NotificationListenerFilter nlf =
                                mRequestedNotificationListeners.valueAt(j);

                        VersionedPackage ai = new VersionedPackage(pkg, uidList[i]);
                        nlf.removePackage(ai);
                    }
                }
            }
        }

        @Override
        protected String getRequiredPermission() {
            return null;
        }

        @Override
        protected boolean shouldReflectToSettings() {
            // androidx has a public method that reads the approved set of listeners from
            // Settings so we have to continue writing this list for this type of service
            return true;
        }

        @Override
        protected void readExtraTag(String tag, TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            if (TAG_REQUESTED_LISTENERS.equals(tag)) {
                final int listenersOuterDepth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, listenersOuterDepth)) {
                    if (!TAG_REQUESTED_LISTENER.equals(parser.getName())) {
                        continue;
                    }
                    final int userId = XmlUtils.readIntAttribute(parser, ATT_USER_ID);
                    final ComponentName cn = ComponentName.unflattenFromString(
                            XmlUtils.readStringAttribute(parser, ATT_COMPONENT));
                    int approved = FLAG_FILTER_TYPE_CONVERSATIONS | FLAG_FILTER_TYPE_ALERTING
                            | FLAG_FILTER_TYPE_SILENT | FLAG_FILTER_TYPE_ONGOING;

                    ArraySet<VersionedPackage> disallowedPkgs = new ArraySet<>();
                    final int listenerOuterDepth = parser.getDepth();
                    while (XmlUtils.nextElementWithin(parser, listenerOuterDepth)) {
                        if (TAG_APPROVED.equals(parser.getName())) {
                            approved = XmlUtils.readIntAttribute(parser, ATT_TYPES);
                        } else if (TAG_DISALLOWED.equals(parser.getName())) {
                            String pkg = XmlUtils.readStringAttribute(parser, ATT_PKG);
                            int uid = XmlUtils.readIntAttribute(parser, ATT_UID);
                            if (!TextUtils.isEmpty(pkg)) {
                                VersionedPackage ai = new VersionedPackage(pkg, uid);
                                disallowedPkgs.add(ai);
                            }
                        }
                    }
                    NotificationListenerFilter nlf =
                            new NotificationListenerFilter(approved, disallowedPkgs);
                    synchronized (mRequestedNotificationListeners) {
                        mRequestedNotificationListeners.put(Pair.create(cn, userId), nlf);
                    }
                }
            }
        }

        @Override
        protected void writeExtraXmlTags(TypedXmlSerializer out) throws IOException {
            out.startTag(null, TAG_REQUESTED_LISTENERS);
            synchronized (mRequestedNotificationListeners) {
                for (Pair<ComponentName, Integer> listener :
                        mRequestedNotificationListeners.keySet()) {
                    NotificationListenerFilter nlf = mRequestedNotificationListeners.get(listener);
                    out.startTag(null, TAG_REQUESTED_LISTENER);
                    XmlUtils.writeStringAttribute(
                            out, ATT_COMPONENT, listener.first.flattenToString());
                    XmlUtils.writeIntAttribute(out, ATT_USER_ID, listener.second);

                    out.startTag(null, TAG_APPROVED);
                    XmlUtils.writeIntAttribute(out, ATT_TYPES, nlf.getTypes());
                    out.endTag(null, TAG_APPROVED);

                    for (VersionedPackage ai : nlf.getDisallowedPackages()) {
                        if (!TextUtils.isEmpty(ai.getPackageName())) {
                            out.startTag(null, TAG_DISALLOWED);
                            XmlUtils.writeStringAttribute(out, ATT_PKG, ai.getPackageName());
                            XmlUtils.writeIntAttribute(out, ATT_UID, ai.getVersionCode());
                            out.endTag(null, TAG_DISALLOWED);
                        }
                    }

                    out.endTag(null, TAG_REQUESTED_LISTENER);
                }
            }

            out.endTag(null, TAG_REQUESTED_LISTENERS);
        }

        @Nullable protected NotificationListenerFilter getNotificationListenerFilter(
                Pair<ComponentName, Integer> pair) {
            synchronized (mRequestedNotificationListeners) {
                return mRequestedNotificationListeners.get(pair);
            }
        }

        protected void setNotificationListenerFilter(Pair<ComponentName, Integer> pair,
                NotificationListenerFilter nlf) {
            synchronized (mRequestedNotificationListeners) {
                mRequestedNotificationListeners.put(pair, nlf);
            }
        }

        @Override
        protected void ensureFilters(ServiceInfo si, int userId) {
            Pair<ComponentName, Integer> listener = Pair.create(si.getComponentName(), userId);
            synchronized (mRequestedNotificationListeners) {
                NotificationListenerFilter existingNlf =
                        mRequestedNotificationListeners.get(listener);
                if (si.metaData != null) {
                    if (existingNlf == null) {
                        // no stored filters for this listener; see if they provided a default
                        if (si.metaData.containsKey(META_DATA_DEFAULT_FILTER_TYPES)) {
                            String typeList =
                                    si.metaData.get(META_DATA_DEFAULT_FILTER_TYPES).toString();
                            if (typeList != null) {
                                int types = getTypesFromStringList(typeList);
                                NotificationListenerFilter nlf =
                                        new NotificationListenerFilter(types, new ArraySet<>());
                                mRequestedNotificationListeners.put(listener, nlf);
                            }
                        }
                    }

                    // also check the types they never want bridged
                    if (si.metaData.containsKey(META_DATA_DISABLED_FILTER_TYPES)) {
                        int neverBridge = getTypesFromStringList(si.metaData.get(
                                META_DATA_DISABLED_FILTER_TYPES).toString());
                        if (neverBridge != 0) {
                            NotificationListenerFilter nlf =
                                    mRequestedNotificationListeners.getOrDefault(
                                            listener, new NotificationListenerFilter());
                            nlf.setTypes(nlf.getTypes() & ~neverBridge);
                            mRequestedNotificationListeners.put(listener, nlf);
                        }
                    }
                }
            }
        }

        private int getTypesFromStringList(String typeList) {
            int types = 0;
            if (typeList != null) {
                String[] typeStrings = typeList.split(FLAG_SEPARATOR);
                for (int i = 0; i < typeStrings.length; i++) {
                    final String typeString = typeStrings[i];
                    if (TextUtils.isEmpty(typeString)) {
                        continue;
                    }
                    if (typeString.equalsIgnoreCase("ONGOING")) {
                        types |= FLAG_FILTER_TYPE_ONGOING;
                    } else if (typeString.equalsIgnoreCase("CONVERSATIONS")) {
                        types |= FLAG_FILTER_TYPE_CONVERSATIONS;
                    } else if (typeString.equalsIgnoreCase("SILENT")) {
                        types |= FLAG_FILTER_TYPE_SILENT;
                    } else if (typeString.equalsIgnoreCase("ALERTING")) {
                        types |= FLAG_FILTER_TYPE_ALERTING;
                    } else {
                        try {
                            types |= Integer.parseInt(typeString);
                        } catch (NumberFormatException e) {
                            // skip
                        }
                    }
                }
            }
            return types;
        }

        @GuardedBy("mNotificationLock")
        public void setOnNotificationPostedTrimLocked(ManagedServiceInfo info, int trim) {
            if (trim == TRIM_LIGHT) {
                mLightTrimListeners.add(info);
            } else {
                mLightTrimListeners.remove(info);
            }
        }

        public int getOnNotificationPostedTrim(ManagedServiceInfo info) {
            return mLightTrimListeners.contains(info) ? TRIM_LIGHT : TRIM_FULL;
        }

        public void onStatusBarIconsBehaviorChanged(boolean hideSilentStatusIcons) {
            // send to all currently bounds NASes since notifications from both users will appear in
            // the status bar
            for (final ManagedServiceInfo info : getServices()) {
                mHandler.post(() -> {
                    final INotificationListener listener = (INotificationListener) info.service;
                    try {
                        listener.onStatusBarIconsBehaviorChanged(hideSilentStatusIcons);
                    } catch (RemoteException ex) {
                        Slog.e(TAG, "unable to notify listener "
                                + "(hideSilentStatusIcons): " + info, ex);
                    }
                });
            }
        }

        /**
         * Asynchronously notify all listeners about a new or updated notification. Note that the
         * notification is new or updated from the point of view of the NLS, but might not be
         * "strictly new" <em>from the point of view of NMS itself</em> -- for example, this method
         * is also invoked after exiting lockdown mode.
         *
         * <p>
         * Also takes care of removing a notification that has been visible to a listener before,
         * but isn't anymore.
         */
        @VisibleForTesting
        @GuardedBy("mNotificationLock")
        void notifyPostedLocked(NotificationRecord r, NotificationRecord old) {
            notifyPostedLocked(r, old, true);
        }

        /**
         * Asynchronously notify all listeners about a new or updated notification. Note that the
         * notification is new or updated from the point of view of the NLS, but might not be
         * "strictly new" <em>from the point of view of NMS itself</em> -- for example, this method
         * is invoked after exiting lockdown mode.
         *
         * @param notifyAllListeners notifies all listeners if true, else only notifies listeners
         *                           targeting <= O_MR1
         */
        @VisibleForTesting
        @GuardedBy("mNotificationLock")
        void notifyPostedLocked(NotificationRecord r, NotificationRecord old,
                boolean notifyAllListeners) {
            for (Runnable listenerCall : prepareNotifyPostedLocked(r, old, notifyAllListeners)) {
                mHandler.post(listenerCall);
            }
        }

        /**
         * "Prepares" to notify all listeners about the posted notification.
         *
         * <p>This method <em>does not invoke</em> the listeners; the caller should post each
         * returned {@link Runnable} on a suitable thread to do so.
         *
         * @param notifyAllListeners notifies all listeners if true, else only notifies listeners
         *                           targeting <= O_MR1
         * @return A list of {@link Runnable} operations to notify all listeners about the posted
         * notification.
         */
        @VisibleForTesting
        @GuardedBy("mNotificationLock")
        List<Runnable> prepareNotifyPostedLocked(NotificationRecord r,
                NotificationRecord old, boolean notifyAllListeners) {
            if (isInLockDownMode(r.getUser().getIdentifier())) {
                return new ArrayList<>();
            }

            ArrayList<Runnable> listenerCalls = new ArrayList<>();
            try {
                // Lazily initialized snapshots of the notification.
                StatusBarNotification sbn = r.getSbn();
                StatusBarNotification oldSbn = (old != null) ? old.getSbn() : null;
                TrimCache trimCache = new TrimCache(sbn);

                for (final ManagedServiceInfo info : getServices()) {
                    boolean sbnVisible = isVisibleToListener(sbn, r.getNotificationType(), info);
                    boolean oldSbnVisible = (oldSbn != null)
                            && isVisibleToListener(oldSbn, old.getNotificationType(), info);
                    // This notification hasn't been and still isn't visible -> ignore.
                    if (!oldSbnVisible && !sbnVisible) {
                        continue;
                    }
                    // If the notification is hidden, don't notifyPosted listeners targeting < P.
                    // Instead, those listeners will receive notifyPosted when the notification is
                    // unhidden.
                    if (r.isHidden() && info.targetSdkVersion < Build.VERSION_CODES.P) {
                        continue;
                    }

                    // If we shouldn't notify all listeners, this means the hidden state of
                    // a notification was changed.  Don't notifyPosted listeners targeting >= P.
                    // Instead, those listeners will receive notifyRankingUpdate.
                    if (!notifyAllListeners && info.targetSdkVersion >= Build.VERSION_CODES.P) {
                        continue;
                    }

                    final NotificationRankingUpdate update = makeRankingUpdateLocked(info);

                    // This notification became invisible -> remove the old one.
                    if (oldSbnVisible && !sbnVisible) {
                        final StatusBarNotification oldSbnLightClone = oldSbn.cloneLight();
                        listenerCalls.add(() -> notifyRemoved(
                                info, oldSbnLightClone, update, null, REASON_USER_STOPPED));
                        continue;
                    }
                    // Grant access before listener is notified
                    final int targetUserId = (info.userid == UserHandle.USER_ALL)
                            ? UserHandle.USER_SYSTEM : info.userid;
                    updateUriPermissions(r, old, info.component.getPackageName(), targetUserId);

                    mPackageManagerInternal.grantImplicitAccess(
                            targetUserId, null /* intent */,
                            UserHandle.getAppId(info.uid),
                            sbn.getUid(),
                            false /* direct */, false /* retainOnUpdate */);

                    final StatusBarNotification sbnToPost = trimCache.ForListener(info);
                    listenerCalls.add(() -> notifyPosted(info, sbnToPost, update));
                }
            } catch (Exception e) {
                Slog.e(TAG, "Could not notify listeners for " + r.getKey(), e);
            }
            return listenerCalls;
        }

        /**
         * Synchronously grant or revoke permissions to Uris for all active and visible
         * notifications to just the NotificationListenerService provided.
         */
        @GuardedBy("mNotificationLock")
        private void updateUriPermissionsForActiveNotificationsLocked(
                ManagedServiceInfo info, boolean grant) {
            try {
                for (final NotificationRecord r : mNotificationList) {
                    // When granting permissions, ignore notifications which are invisible.
                    // When revoking permissions, all notifications are invisible, so process all.
                    if (grant && !isVisibleToListener(r.getSbn(), r.getNotificationType(), info)) {
                        continue;
                    }
                    // If the notification is hidden, permissions are not required by the listener.
                    if (r.isHidden() && info.targetSdkVersion < Build.VERSION_CODES.P) {
                        continue;
                    }
                    // Grant or revoke access synchronously
                    final int targetUserId = (info.userid == UserHandle.USER_ALL)
                            ? UserHandle.USER_SYSTEM : info.userid;
                    if (grant) {
                        // Grant permissions by passing arguments as if the notification is new.
                        updateUriPermissions(/* newRecord */ r, /* oldRecord */ null,
                                info.component.getPackageName(), targetUserId);
                    } else {
                        // Revoke permissions by passing arguments as if the notification was
                        // removed, but set `onlyRevokeCurrentTarget` to avoid revoking permissions
                        // granted to *other* targets by this notification's URIs.
                        updateUriPermissions(/* newRecord */ null, /* oldRecord */ r,
                                info.component.getPackageName(), targetUserId,
                                /* onlyRevokeCurrentTarget */ true);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Could not " + (grant ? "grant" : "revoke") + " Uri permissions to "
                        + info.component, e);
            }
        }

        /**
         * asynchronously notify all listeners about a removed notification
         */
        @GuardedBy("mNotificationLock")
        public void notifyRemovedLocked(NotificationRecord r, int reason,
                NotificationStats notificationStats) {
            if (isInLockDownMode(r.getUser().getIdentifier())) {
                return;
            }

            final StatusBarNotification sbn = r.getSbn();

            // make a copy in case changes are made to the underlying Notification object
            // NOTE: this copy is lightweight: it doesn't include heavyweight parts of the
            // notification
            final StatusBarNotification sbnLight = sbn.cloneLight();
            for (final ManagedServiceInfo info : getServices()) {
                if (!isVisibleToListener(sbn, r.getNotificationType(), info)) {
                    continue;
                }

                // don't notifyRemoved for listeners targeting < P
                // if not for reason package suspended
                if (r.isHidden() && reason != REASON_PACKAGE_SUSPENDED
                        && info.targetSdkVersion < Build.VERSION_CODES.P) {
                    continue;
                }

                // don't notifyRemoved for listeners targeting >= P
                // if the reason is package suspended
                if (reason == REASON_PACKAGE_SUSPENDED
                        && info.targetSdkVersion >= Build.VERSION_CODES.P) {
                    continue;
                }

                // Only assistants can get stats
                final NotificationStats stats = mAssistants.isServiceTokenValidLocked(info.service)
                        ? notificationStats : null;
                final NotificationRankingUpdate update = makeRankingUpdateLocked(info);
                mHandler.post(() -> notifyRemoved(info, sbnLight, update, stats, reason));
            }

            // Revoke access after all listeners have been updated
            mHandler.post(() -> updateUriPermissions(null, r, null, UserHandle.USER_SYSTEM));
        }

        /**
         * Asynchronously notify all listeners about a reordering of notifications
         * unless changedHiddenNotifications is populated.
         * If changedHiddenNotifications is populated, there was a change in the hidden state
         * of the notifications.  In this case, we only send updates to listeners that
         * target >= P.
         */
        @GuardedBy("mNotificationLock")
        public void notifyRankingUpdateLocked(List<NotificationRecord> changedHiddenNotifications) {
            boolean isHiddenRankingUpdate = changedHiddenNotifications != null
                    && changedHiddenNotifications.size() > 0;
            // TODO (b/73052211): if the ranking update changed the notification type,
            // cancel notifications for NLSes that can't see them anymore
            for (final ManagedServiceInfo serviceInfo : getServices()) {
                if (!serviceInfo.isEnabledForCurrentProfiles() || !isInteractionVisibleToListener(
                        serviceInfo, ActivityManager.getCurrentUser())) {
                    continue;
                }

                boolean notifyThisListener = false;
                if (isHiddenRankingUpdate && serviceInfo.targetSdkVersion >=
                        Build.VERSION_CODES.P) {
                    for (NotificationRecord rec : changedHiddenNotifications) {
                        if (isVisibleToListener(
                                rec.getSbn(), rec.getNotificationType(), serviceInfo)) {
                            notifyThisListener = true;
                            break;
                        }
                    }
                }

                if (notifyThisListener || !isHiddenRankingUpdate) {
                    final NotificationRankingUpdate update = makeRankingUpdateLocked(
                            serviceInfo);

                    mHandler.post(() -> notifyRankingUpdate(serviceInfo, update));
                }
            }
        }

        @GuardedBy("mNotificationLock")
        public void notifyListenerHintsChangedLocked(final int hints) {
            for (final ManagedServiceInfo serviceInfo : getServices()) {
                if (!serviceInfo.isEnabledForCurrentProfiles() || !isInteractionVisibleToListener(
                        serviceInfo, ActivityManager.getCurrentUser())) {
                    continue;
                }
                mHandler.post(() -> notifyListenerHintsChanged(serviceInfo, hints));
            }
        }

        /**
         * asynchronously notify relevant listeners their notification is hidden
         * NotificationListenerServices that target P+:
         *      NotificationListenerService#notifyRankingUpdateLocked()
         * NotificationListenerServices that target <= P:
         *      NotificationListenerService#notifyRemovedLocked() with REASON_PACKAGE_SUSPENDED.
         */
        @GuardedBy("mNotificationLock")
        public void notifyHiddenLocked(List<NotificationRecord> changedNotifications) {
            if (changedNotifications == null || changedNotifications.size() == 0) {
                return;
            }

            notifyRankingUpdateLocked(changedNotifications);

            // for listeners that target < P, notifyRemoveLocked
            int numChangedNotifications = changedNotifications.size();
            for (int i = 0; i < numChangedNotifications; i++) {
                NotificationRecord rec = changedNotifications.get(i);
                mListeners.notifyRemovedLocked(rec, REASON_PACKAGE_SUSPENDED, rec.getStats());
            }
        }

        /**
         * asynchronously notify relevant listeners their notification is unhidden
         * NotificationListenerServices that target P+:
         *      NotificationListenerService#notifyRankingUpdateLocked()
         * NotificationListenerServices that target <= P:
         *      NotificationListeners#notifyPostedLocked()
         */
        @GuardedBy("mNotificationLock")
        public void notifyUnhiddenLocked(List<NotificationRecord> changedNotifications) {
            if (changedNotifications == null || changedNotifications.size() == 0) {
                return;
            }

            notifyRankingUpdateLocked(changedNotifications);

            // for listeners that target < P, notifyPostedLocked
            int numChangedNotifications = changedNotifications.size();
            for (int i = 0; i < numChangedNotifications; i++) {
                NotificationRecord rec = changedNotifications.get(i);
                notifyPostedLocked(rec, rec, false);
            }
        }

        public void notifyInterruptionFilterChanged(final int interruptionFilter) {
            for (final ManagedServiceInfo serviceInfo : getServices()) {
                if (!serviceInfo.isEnabledForCurrentProfiles() || !isInteractionVisibleToListener(
                        serviceInfo, ActivityManager.getCurrentUser())) {
                    continue;
                }
                mHandler.post(
                        () -> notifyInterruptionFilterChanged(serviceInfo, interruptionFilter));
            }
        }

        protected void notifyNotificationChannelChanged(final String pkg, final UserHandle user,
                final NotificationChannel channel, final int modificationType) {
            if (channel == null) {
                return;
            }
            for (final ManagedServiceInfo info : getServices()) {
                if (!info.enabledAndUserMatches(UserHandle.getCallingUserId())
                        || !isInteractionVisibleToListener(info, UserHandle.getCallingUserId())) {
                    continue;
                }

                BackgroundThread.getHandler().post(() -> {
                    if (info.isSystem
                            || hasCompanionDevice(info)
                            || isServiceTokenValid(info.service)) {
                        notifyNotificationChannelChanged(
                                info, pkg, user, channel, modificationType);
                    }
                });
            }
        }

        protected void notifyNotificationChannelGroupChanged(
                final String pkg, final UserHandle user, final NotificationChannelGroup group,
                final int modificationType) {
            if (group == null) {
                return;
            }
            for (final ManagedServiceInfo info : getServices()) {
                if (!info.enabledAndUserMatches(UserHandle.getCallingUserId())
                        || !isInteractionVisibleToListener(info, UserHandle.getCallingUserId())) {
                    continue;
                }

                BackgroundThread.getHandler().post(() -> {
                    if (info.isSystem() || hasCompanionDevice(info)) {
                        notifyNotificationChannelGroupChanged(
                                info, pkg, user, group, modificationType);
                    }
                });
            }
        }

        private void notifyPosted(final ManagedServiceInfo info,
                final StatusBarNotification sbn, NotificationRankingUpdate rankingUpdate) {
            final INotificationListener listener = (INotificationListener) info.service;
            StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbn);
            try {
                listener.onNotificationPosted(sbnHolder, rankingUpdate);
            } catch (android.os.DeadObjectException ex) {
                Slog.wtf(TAG, "unable to notify listener (posted): " + info, ex);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (posted): " + info, ex);
            }
        }

        private void notifyRemoved(ManagedServiceInfo info, StatusBarNotification sbn,
                NotificationRankingUpdate rankingUpdate, NotificationStats stats, int reason) {
            final INotificationListener listener = (INotificationListener) info.service;
            StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbn);
            try {
                if (!CompatChanges.isChangeEnabled(NOTIFICATION_CANCELLATION_REASONS, info.uid)
                        && (reason == REASON_CHANNEL_REMOVED || reason == REASON_CLEAR_DATA)) {
                    reason = REASON_CHANNEL_BANNED;
                }
                // apps before T don't know about REASON_ASSISTANT, so replace it with the
                // previously-used case, REASON_LISTENER_CANCEL
                if (!CompatChanges.isChangeEnabled(NOTIFICATION_LOG_ASSISTANT_CANCEL, info.uid)
                        && reason == REASON_ASSISTANT_CANCEL) {
                    reason = REASON_LISTENER_CANCEL;
                }
                listener.onNotificationRemoved(sbnHolder, rankingUpdate, stats, reason);
            } catch (android.os.DeadObjectException ex) {
                Slog.wtf(TAG, "unable to notify listener (removed): " + info, ex);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (removed): " + info, ex);
            }
        }

        private void notifyRankingUpdate(ManagedServiceInfo info,
                                         NotificationRankingUpdate rankingUpdate) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onNotificationRankingUpdate(rankingUpdate);
            } catch (android.os.DeadObjectException ex) {
                Slog.wtf(TAG, "unable to notify listener (ranking update): " + info, ex);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (ranking update): " + info, ex);
            }
        }

        private void notifyListenerHintsChanged(ManagedServiceInfo info, int hints) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onListenerHintsChanged(hints);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (listener hints): " + info, ex);
            }
        }

        private void notifyInterruptionFilterChanged(ManagedServiceInfo info,
                int interruptionFilter) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onInterruptionFilterChanged(interruptionFilter);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (interruption filter): " + info, ex);
            }
        }

        void notifyNotificationChannelChanged(ManagedServiceInfo info,
                final String pkg, final UserHandle user, final NotificationChannel channel,
                final int modificationType) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onNotificationChannelModification(pkg, user, channel, modificationType);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (channel changed): " + info, ex);
            }
        }

        private void notifyNotificationChannelGroupChanged(ManagedServiceInfo info,
                final String pkg, final UserHandle user, final NotificationChannelGroup group,
                final int modificationType) {
            final INotificationListener listener = (INotificationListener) info.getService();
            try {
                listener.onNotificationChannelGroupModification(pkg, user, group, modificationType);
            } catch (RemoteException ex) {
                Slog.e(TAG, "unable to notify listener (channel group changed): " + info, ex);
            }
        }

        public boolean isListenerPackage(String packageName) {
            if (packageName == null) {
                return false;
            }
            // TODO: clean up locking object later
            synchronized (mNotificationLock) {
                for (final ManagedServiceInfo serviceInfo : getServices()) {
                    if (packageName.equals(serviceInfo.component.getPackageName())) {
                        return true;
                    }
                }
            }
            return false;
        }

        // Returns whether there is a component with listener access granted that is associated
        // with the given package name / user ID.
        boolean hasAllowedListener(String packageName, int userId) {
            if (packageName == null) {
                return false;
            }

            // Loop through allowed components to compare package names
            List<ComponentName> allowedComponents = getAllowedComponents(userId);
            for (int i = 0; i < allowedComponents.size(); i++) {
                if (allowedComponents.get(i).getPackageName().equals(packageName)) {
                    return true;
                }
            }
            return false;
        }
    }

    // TODO (b/194833441): remove when we've fully migrated to a permission
    class RoleObserver implements OnRoleHoldersChangedListener {
        // Role name : user id : list of approved packages
        private ArrayMap<String, ArrayMap<Integer, ArraySet<String>>> mNonBlockableDefaultApps;

        /**
         * Writes should be pretty rare (only when default browser changes) and reads are done
         * during activity start code-path, so we're optimizing for reads. This means this set is
         * immutable once written and we'll recreate the set every time there is a role change and
         * then assign that new set to the volatile below, so reads can be done without needing to
         * hold a lock. Every write is done on the main-thread, so write atomicity is guaranteed.
         *
         * Didn't use unmodifiable set to enforce immutability to avoid iterating via iterators.
         */
        private volatile ArraySet<Integer> mTrampolineExemptUids = new ArraySet<>();

        private final RoleManager mRm;
        private final IPackageManager mPm;
        private final Executor mExecutor;
        private final Looper mMainLooper;

        RoleObserver(Context context, @NonNull RoleManager roleManager,
                @NonNull IPackageManager pkgMgr, @NonNull Looper mainLooper) {
            mRm = roleManager;
            mPm = pkgMgr;
            mExecutor = context.getMainExecutor();
            mMainLooper = mainLooper;
        }

        /** Should be called from the main-thread. */
        @MainThread
        public void init() {
            List<UserHandle> users = mUm.getUserHandles(/* excludeDying */ true);
            mNonBlockableDefaultApps = new ArrayMap<>();
            for (int i = 0; i < NON_BLOCKABLE_DEFAULT_ROLES.length; i++) {
                final ArrayMap<Integer, ArraySet<String>> userToApprovedList = new ArrayMap<>();
                mNonBlockableDefaultApps.put(NON_BLOCKABLE_DEFAULT_ROLES[i], userToApprovedList);
                for (int j = 0; j < users.size(); j++) {
                    Integer userId = users.get(j).getIdentifier();
                    ArraySet<String> approvedForUserId = new ArraySet<>(mRm.getRoleHoldersAsUser(
                            NON_BLOCKABLE_DEFAULT_ROLES[i], UserHandle.of(userId)));
                    ArraySet<Pair<String, Integer>> approvedAppUids = new ArraySet<>();
                    for (String pkg : approvedForUserId) {
                        approvedAppUids.add(new Pair(pkg, getUidForPackage(pkg, userId)));
                    }
                    userToApprovedList.put(userId, approvedForUserId);
                    mPreferencesHelper.updateDefaultApps(userId, null, approvedAppUids);
                }
            }
            updateTrampolineExemptUidsForUsers(users.toArray(new UserHandle[0]));
            mRm.addOnRoleHoldersChangedListenerAsUser(mExecutor, this, UserHandle.ALL);
        }

        @VisibleForTesting
        public boolean isApprovedPackageForRoleForUser(String role, String pkg, int userId) {
            return mNonBlockableDefaultApps.get(role).get(userId).contains(pkg);
        }

        @VisibleForTesting
        public boolean isUidExemptFromTrampolineRestrictions(int uid) {
            return mTrampolineExemptUids.contains(uid);
        }

        /**
         * Convert the assistant-role holder into settings. The rest of the system uses the
         * settings.
         *
         * @param roleName the name of the role whose holders are changed
         * @param user the user for this role holder change
         */
        @Override
        public void onRoleHoldersChanged(@NonNull String roleName, @NonNull UserHandle user) {
            onRoleHoldersChangedForNonBlockableDefaultApps(roleName, user);
            onRoleHoldersChangedForTrampolines(roleName, user);
        }

        private void onRoleHoldersChangedForNonBlockableDefaultApps(@NonNull String roleName,
                @NonNull UserHandle user) {
            // we only care about a couple of the roles they'll tell us about
            boolean relevantChange = false;
            for (int i = 0; i < NON_BLOCKABLE_DEFAULT_ROLES.length; i++) {
                if (NON_BLOCKABLE_DEFAULT_ROLES[i].equals(roleName)) {
                    relevantChange = true;
                    break;
                }
            }

            if (!relevantChange) {
                return;
            }

            ArraySet<String> roleHolders = new ArraySet<>(mRm.getRoleHoldersAsUser(roleName, user));

            // find the diff
            ArrayMap<Integer, ArraySet<String>> prevApprovedForRole =
                    mNonBlockableDefaultApps.getOrDefault(roleName, new ArrayMap<>());
            ArraySet<String> previouslyApproved =
                    prevApprovedForRole.getOrDefault(user.getIdentifier(), new ArraySet<>());

            ArraySet<String> toRemove = new ArraySet<>();
            ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();

            for (String previous : previouslyApproved) {
                if (!roleHolders.contains(previous)) {
                    toRemove.add(previous);
                }
            }
            for (String nowApproved : roleHolders) {
                if (!previouslyApproved.contains(nowApproved)) {
                    toAdd.add(new Pair(nowApproved,
                            getUidForPackage(nowApproved, user.getIdentifier())));
                }
            }

            // store newly approved apps
            prevApprovedForRole.put(user.getIdentifier(), roleHolders);
            mNonBlockableDefaultApps.put(roleName, prevApprovedForRole);

            // update what apps can be blocked
            mPreferencesHelper.updateDefaultApps(user.getIdentifier(), toRemove, toAdd);

            // RoleManager is the source of truth for this data so we don't need to trigger a
            // write of the notification policy xml for this change
        }

        private void onRoleHoldersChangedForTrampolines(@NonNull String roleName,
                @NonNull UserHandle user) {
            if (!RoleManager.ROLE_BROWSER.equals(roleName)) {
                return;
            }
            updateTrampolineExemptUidsForUsers(user);
        }

        private void updateTrampolineExemptUidsForUsers(UserHandle... users) {
            Preconditions.checkState(mMainLooper.isCurrentThread());
            ArraySet<Integer> oldUids = mTrampolineExemptUids;
            ArraySet<Integer> newUids = new ArraySet<>();
            // Add the uids from previous set for the users that we won't update.
            for (int i = 0, n = oldUids.size(); i < n; i++) {
                int uid = oldUids.valueAt(i);
                UserHandle user = UserHandle.of(UserHandle.getUserId(uid));
                if (!ArrayUtils.contains(users, user)) {
                    newUids.add(uid);
                }
            }
            // Now lookup the new uids for the users that we want to update.
            for (int i = 0, n = users.length; i < n; i++) {
                UserHandle user = users[i];
                for (String pkg : mRm.getRoleHoldersAsUser(RoleManager.ROLE_BROWSER, user)) {
                    int uid = getUidForPackage(pkg, user.getIdentifier());
                    if (uid != -1) {
                        newUids.add(uid);
                    } else {
                        Slog.e(TAG, "Bad uid (-1) for browser package " + pkg);
                    }
                }
            }
            mTrampolineExemptUids = newUids;
        }

        private int getUidForPackage(String pkg, int userId) {
            try {
                return mPm.getPackageUid(pkg, MATCH_ALL, userId);
            } catch (RemoteException e) {
                Slog.e(TAG, "role manager has bad default " + pkg + " " + userId);
            }
            return -1;
        }
    }

    public static final class DumpFilter {
        public boolean filtered = false;
        public String pkgFilter;
        public boolean zen;
        public long since;
        public boolean stats;
        public boolean rvStats;
        public boolean redact = true;
        public boolean proto = false;
        public boolean criticalPriority = false;
        public boolean normalPriority = false;

        @NonNull
        public static DumpFilter parseFromArguments(String[] args) {
            final DumpFilter filter = new DumpFilter();
            for (int ai = 0; ai < args.length; ai++) {
                final String a = args[ai];
                if ("--proto".equals(a)) {
                    filter.proto = true;
                } else if ("--noredact".equals(a) || "--reveal".equals(a)) {
                    filter.redact = false;
                } else if ("p".equals(a) || "pkg".equals(a) || "--package".equals(a)) {
                    if (ai < args.length-1) {
                        ai++;
                        filter.pkgFilter = args[ai].trim().toLowerCase();
                        if (filter.pkgFilter.isEmpty()) {
                            filter.pkgFilter = null;
                        } else {
                            filter.filtered = true;
                        }
                    }
                } else if ("--zen".equals(a) || "zen".equals(a)) {
                    filter.filtered = true;
                    filter.zen = true;
                } else if ("--stats".equals(a)) {
                    filter.stats = true;
                    if (ai < args.length-1) {
                        ai++;
                        filter.since = Long.parseLong(args[ai]);
                    } else {
                        filter.since = 0;
                    }
                } else if ("--remote-view-stats".equals(a)) {
                    filter.rvStats = true;
                    if (ai < args.length-1) {
                        ai++;
                        filter.since = Long.parseLong(args[ai]);
                    } else {
                        filter.since = 0;
                    }
                } else if (PRIORITY_ARG.equals(a)) {
                    // Bugreport will call the service twice with priority arguments, first to dump
                    // critical sections and then non critical ones. Set appropriate filters
                    // to generate the desired data.
                    if (ai < args.length - 1) {
                        ai++;
                        switch (args[ai]) {
                            case PRIORITY_ARG_CRITICAL:
                                filter.criticalPriority = true;
                                break;
                            case PRIORITY_ARG_NORMAL:
                                filter.normalPriority = true;
                                break;
                        }
                    }
                }
            }
            return filter;
        }

        public boolean matches(StatusBarNotification sbn) {
            if (!filtered) return true;
            return zen ? true : sbn != null
                    && (matches(sbn.getPackageName()) || matches(sbn.getOpPkg()));
        }

        public boolean matches(ComponentName component) {
            if (!filtered) return true;
            return zen ? true : component != null && matches(component.getPackageName());
        }

        public boolean matches(String pkg) {
            if (!filtered) return true;
            return zen ? true : pkg != null && pkg.toLowerCase().contains(pkgFilter);
        }

        @Override
        public String toString() {
            return stats ? "stats" : zen ? "zen" : ('\'' + pkgFilter + '\'');
        }
    }

    @VisibleForTesting
    void resetAssistantUserSet(int userId) {
        checkCallerIsSystemOrShell();
        mAssistants.setUserSet(userId, false);
        handleSavePolicyFile();
    }

    @VisibleForTesting
    @Nullable
    ComponentName getApprovedAssistant(int userId) {
        checkCallerIsSystemOrShell();
        List<ComponentName> allowedComponents = mAssistants.getAllowedComponents(userId);
        return CollectionUtils.firstOrNull(allowedComponents);
    }

    /**
     * Wrapper for a StatusBarNotification object that allows transfer across a oneway
     * binder without sending large amounts of data over a oneway transaction.
     */
    private static final class StatusBarNotificationHolder
            extends IStatusBarNotificationHolder.Stub {
        private StatusBarNotification mValue;

        public StatusBarNotificationHolder(StatusBarNotification value) {
            mValue = value;
        }

        /** Get the held value and clear it. This function should only be called once per holder */
        @Override
        public StatusBarNotification get() {
            StatusBarNotification value = mValue;
            mValue = null;
            return value;
        }
    }

    private void writeSecureNotificationsPolicy(TypedXmlSerializer out) throws IOException {
        out.startTag(null, LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_TAG);
        out.attributeBoolean(null, LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_VALUE,
                mLockScreenAllowSecureNotifications);
        out.endTag(null, LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_TAG);
    }

    // Creates a notification that informs the user about changes due to the migration to
    // use permissions for notifications.
    protected Notification createReviewPermissionsNotification() {
        int title = R.string.review_notification_settings_title;
        int content = R.string.review_notification_settings_text;

        // Tapping on the notification leads to the settings screen for managing app notifications,
        // using the intent reserved for system services to indicate it comes from this notification
        Intent tapIntent = new Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS_FOR_REVIEW);
        Intent remindIntent = new Intent(REVIEW_NOTIF_ACTION_REMIND);
        Intent dismissIntent = new Intent(REVIEW_NOTIF_ACTION_DISMISS);
        Intent swipeIntent = new Intent(REVIEW_NOTIF_ACTION_CANCELED);

        // Both "remind me" and "dismiss" actions will be actions received by the BroadcastReceiver
        final Notification.Action remindMe = new Notification.Action.Builder(null,
                getContext().getResources().getString(
                        R.string.review_notification_settings_remind_me_action),
                PendingIntent.getBroadcast(
                        getContext(), 0, remindIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();
        final Notification.Action dismiss = new Notification.Action.Builder(null,
                getContext().getResources().getString(
                        R.string.review_notification_settings_dismiss),
                PendingIntent.getBroadcast(
                        getContext(), 0, dismissIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();

        return new Notification.Builder(getContext(), SystemNotificationChannels.SYSTEM_CHANGES)
                .setSmallIcon(R.drawable.stat_sys_adb)
                .setContentTitle(getContext().getResources().getString(title))
                .setContentText(getContext().getResources().getString(content))
                .setContentIntent(PendingIntent.getActivity(getContext(), 0, tapIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setStyle(new Notification.BigTextStyle())
                .setFlag(Notification.FLAG_NO_CLEAR, true)
                .setAutoCancel(true)
                .addAction(remindMe)
                .addAction(dismiss)
                .setDeleteIntent(PendingIntent.getBroadcast(getContext(), 0, swipeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();
    }

    protected void maybeShowInitialReviewPermissionsNotification() {
        if (!mShowReviewPermissionsNotification) {
            // if this notification is disabled by settings do not ever show it
            return;
        }

        int currentState = Settings.Global.getInt(getContext().getContentResolver(),
                Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                REVIEW_NOTIF_STATE_UNKNOWN);

        // now check the last known state of the notification -- this determination of whether the
        // user is in the correct target audience occurs elsewhere, and will have written the
        // REVIEW_NOTIF_STATE_SHOULD_SHOW to indicate it should be shown in the future.
        //
        // alternatively, if the user has rescheduled the notification (so it has been shown
        // again) but not yet interacted with the new notification, then show it again on boot,
        // as this state indicates that the user had the notification open before rebooting.
        //
        // sending the notification here does not record a new state for the notification;
        // that will be written by parts of the system further down the line if at any point
        // the user interacts with the notification.
        if (currentState == REVIEW_NOTIF_STATE_SHOULD_SHOW
                || currentState == REVIEW_NOTIF_STATE_RESHOWN) {
            NotificationManager nm = getContext().getSystemService(NotificationManager.class);
            nm.notify(TAG,
                    SystemMessageProto.SystemMessage.NOTE_REVIEW_NOTIFICATION_PERMISSIONS,
                    createReviewPermissionsNotification());
        }
    }

    /**
     * Shows a warning on logcat. Shows the toast only once per package. This is to avoid being too
     * aggressive and annoying the user.
     *
     * TODO(b/161957908): Remove dogfooder toast.
     */
    private class NotificationTrampolineCallback implements BackgroundActivityStartCallback {
        @Override
        public boolean isActivityStartAllowed(Collection<IBinder> tokens, int uid,
                String packageName) {
            checkArgument(!tokens.isEmpty());
            for (IBinder token : tokens) {
                if (token != ALLOWLIST_TOKEN) {
                    // We only block or warn if the start is exclusively due to notification
                    return true;
                }
            }
            String logcatMessage =
                    "Indirect notification activity start (trampoline) from " + packageName;
            if (blockTrampoline(uid)) {
                Slog.e(TAG, logcatMessage + " blocked");
                return false;
            } else {
                Slog.w(TAG, logcatMessage + ", this should be avoided for performance reasons");
                return true;
            }
        }

        private boolean blockTrampoline(int uid) {
            if (mRoleObserver != null && mRoleObserver.isUidExemptFromTrampolineRestrictions(uid)) {
                return CompatChanges.isChangeEnabled(NOTIFICATION_TRAMPOLINE_BLOCK_FOR_EXEMPT_ROLES,
                        uid);
            }
            return CompatChanges.isChangeEnabled(NOTIFICATION_TRAMPOLINE_BLOCK, uid);
        }

        @Override
        public boolean canCloseSystemDialogs(Collection<IBinder> tokens, int uid) {
            // If the start is allowed via notification, we allow the app to close system dialogs
            // only if their targetSdk < S, otherwise they have no valid reason to do this since
            // trampolines are blocked.
            return tokens.contains(ALLOWLIST_TOKEN)
                    && !CompatChanges.isChangeEnabled(NOTIFICATION_TRAMPOLINE_BLOCK, uid);
        }
    }

    interface PostNotificationTrackerFactory {
        default PostNotificationTracker newTracker(@Nullable WakeLock optionalWakelock) {
            return new PostNotificationTracker(optionalWakelock);
        }
    }

    static class PostNotificationTracker {
        @ElapsedRealtimeLong private final long mStartTime;
        @Nullable private final WakeLock mWakeLock;
        private boolean mOngoing;

        @VisibleForTesting
        PostNotificationTracker(@Nullable WakeLock wakeLock) {
            mStartTime = SystemClock.elapsedRealtime();
            mWakeLock = wakeLock;
            mOngoing = true;
            if (DBG) {
                Slog.d(TAG, "PostNotification: Started");
            }
        }

        @ElapsedRealtimeLong
        long getStartTime() {
            return mStartTime;
        }

        @VisibleForTesting
        boolean isOngoing() {
            return mOngoing;
        }

        /**
         * Cancels the tracker (releasing the acquired WakeLock). Either {@link #finish} or
         * {@link #cancel} (exclusively) should be called on this object before it's discarded.
         */
        void cancel() {
            if (!isOngoing()) {
                Log.wtfStack(TAG, "cancel() called on already-finished tracker");
                return;
            }
            mOngoing = false;
            if (mWakeLock != null) {
                Binder.withCleanCallingIdentity(() -> mWakeLock.release());
            }
            if (DBG) {
                long elapsedTime = SystemClock.elapsedRealtime() - mStartTime;
                Slog.d(TAG, TextUtils.formatSimple("PostNotification: Abandoned after %d ms",
                        elapsedTime));
            }
        }

        /**
         * Finishes the tracker (releasing the acquired WakeLock) and returns the time elapsed since
         * the operation started, in milliseconds. Either {@link #finish} or {@link #cancel}
         * (exclusively) should be called on this object before it's discarded.
         */
        @DurationMillisLong
        long finish() {
            long elapsedTime = SystemClock.elapsedRealtime() - mStartTime;
            if (!isOngoing()) {
                Log.wtfStack(TAG, "finish() called on already-finished tracker");
                return elapsedTime;
            }
            mOngoing = false;
            if (mWakeLock != null) {
                Binder.withCleanCallingIdentity(() -> mWakeLock.release());
            }
            if (DBG) {
                Slog.d(TAG,
                        TextUtils.formatSimple("PostNotification: Finished in %d ms", elapsedTime));
            }
            return elapsedTime;
        }
    }
}

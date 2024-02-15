/**
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;
import static android.app.NotificationChannel.PLACEHOLDER_CONVERSATION_ID;
import static android.app.NotificationChannel.USER_LOCKED_IMPORTANCE;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_ALL;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_NONE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_MAX;
import static android.app.NotificationManager.IMPORTANCE_NONE;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;

import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_CHANNEL_GROUP_PREFERENCES;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_PREFERENCES;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_PREFERENCES__FSI_STATE__DENIED;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_PREFERENCES__FSI_STATE__GRANTED;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_PREFERENCES__FSI_STATE__NOT_REQUESTED;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.provider.Settings;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.NotificationListenerService;
import android.service.notification.RankingHelperProto;
import android.service.notification.ZenModeConfig;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.StatsEvent;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags;
import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags.NotificationFlags;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.notification.PermissionHelper.PackagePermission;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PreferencesHelper implements RankingConfig {
    private static final String TAG = "NotificationPrefHelper";
    private final int XML_VERSION;
    /** What version to check to do the upgrade for bubbles. */
    private static final int XML_VERSION_BUBBLES_UPGRADE = 1;
    /** The first xml version with notification permissions enabled. */
    private static final int XML_VERSION_NOTIF_PERMISSION = 3;
    /** The first xml version that notifies users to review their notification permissions */
    private static final int XML_VERSION_REVIEW_PERMISSIONS_NOTIFICATION = 4;
    @VisibleForTesting
    static final int UNKNOWN_UID = UserHandle.USER_NULL;

    @VisibleForTesting
    static final int NOTIFICATION_CHANNEL_COUNT_LIMIT = 5000;
    @VisibleForTesting
    static final int NOTIFICATION_CHANNEL_GROUP_COUNT_LIMIT = 6000;

    private static final int NOTIFICATION_PREFERENCES_PULL_LIMIT = 1000;
    private static final int NOTIFICATION_CHANNEL_PULL_LIMIT = 2000;
    private static final int NOTIFICATION_CHANNEL_GROUP_PULL_LIMIT = 1000;
    private static final int NOTIFICATION_CHANNEL_DELETION_RETENTION_DAYS = 30;

    @VisibleForTesting
    static final String TAG_RANKING = "ranking";
    private static final String TAG_PACKAGE = "package";
    private static final String TAG_CHANNEL = "channel";
    private static final String TAG_GROUP = "channelGroup";
    private static final String TAG_DELEGATE = "delegate";
    private static final String TAG_STATUS_ICONS = "silent_status_icons";

    private static final String ATT_VERSION = "version";
    private static final String ATT_NAME = "name";
    private static final String ATT_UID = "uid";
    private static final String ATT_ID = "id";
    private static final String ATT_ALLOW_BUBBLE = "allow_bubble";
    private static final String ATT_PRIORITY = "priority";
    private static final String ATT_VISIBILITY = "visibility";
    private static final String ATT_IMPORTANCE = "importance";
    private static final String ATT_SHOW_BADGE = "show_badge";
    private static final String ATT_APP_USER_LOCKED_FIELDS = "app_user_locked_fields";
    private static final String ATT_ENABLED = "enabled";
    private static final String ATT_HIDE_SILENT = "hide_gentle";
    private static final String ATT_SENT_INVALID_MESSAGE = "sent_invalid_msg";
    private static final String ATT_SENT_VALID_MESSAGE = "sent_valid_msg";
    private static final String ATT_USER_DEMOTED_INVALID_MSG_APP = "user_demote_msg_app";
    private static final String ATT_SENT_VALID_BUBBLE = "sent_valid_bubble";

    private static final int DEFAULT_PRIORITY = Notification.PRIORITY_DEFAULT;
    private static final int DEFAULT_VISIBILITY = NotificationManager.VISIBILITY_NO_OVERRIDE;
    private static final int DEFAULT_IMPORTANCE = NotificationManager.IMPORTANCE_UNSPECIFIED;
    @VisibleForTesting
    static final boolean DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS = false;
    private static final boolean DEFAULT_SHOW_BADGE = true;

    private static final boolean DEFAULT_APP_LOCKED_IMPORTANCE  = false;

    static final boolean DEFAULT_BUBBLES_ENABLED = true;
    @VisibleForTesting
    static final int DEFAULT_BUBBLE_PREFERENCE = BUBBLE_PREFERENCE_NONE;

    private static final int NOTIFICATION_UPDATE_LOG_SUBTYPE_FROM_APP = 0;
    private static final int NOTIFICATION_UPDATE_LOG_SUBTYPE_FROM_USER = 1;

    /**
     * Default value for what fields are user locked. See {@link LockableAppFields} for all lockable
     * fields.
     */
    private static final int DEFAULT_LOCKED_APP_FIELDS = 0;

    /**
     * All user-lockable fields for a given application.
     */
    @IntDef({LockableAppFields.USER_LOCKED_IMPORTANCE})
    public @interface LockableAppFields {
        int USER_LOCKED_IMPORTANCE = 0x00000001;
        int USER_LOCKED_BUBBLE = 0x00000002;
    }

    // pkg|uid => PackagePreferences
    private final ArrayMap<String, PackagePreferences> mPackagePreferences = new ArrayMap<>();
    // pkg|userId => PackagePreferences
    private final ArrayMap<String, PackagePreferences> mRestoredWithoutUids = new ArrayMap<>();

    private final Context mContext;
    private final PackageManager mPm;
    private final RankingHandler mRankingHandler;
    private final ZenModeHelper mZenModeHelper;
    private final PermissionHelper mPermissionHelper;
    private final PermissionManager mPermissionManager;
    private final NotificationChannelLogger mNotificationChannelLogger;
    private final AppOpsManager mAppOps;
    private final ManagedServices.UserProfiles mUserProfiles;

    private SparseBooleanArray mBadgingEnabled;
    private SparseBooleanArray mBubblesEnabled;
    private SparseBooleanArray mLockScreenShowNotifications;
    private SparseBooleanArray mLockScreenPrivateNotifications;
    private boolean mIsMediaNotificationFilteringEnabled;
    // When modes_api flag is enabled, this value only tracks whether the current user has any
    // channels marked as "priority channels", but not necessarily whether they are permitted
    // to bypass DND by current zen policy.
    // TODO: b/310620812 - Rename to be more accurate when modes_api flag is inlined.
    private boolean mCurrentUserHasChannelsBypassingDnd;
    private boolean mHideSilentStatusBarIcons = DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS;
    private final boolean mShowReviewPermissionsNotification;

    public PreferencesHelper(Context context, PackageManager pm, RankingHandler rankingHandler,
            ZenModeHelper zenHelper, PermissionHelper permHelper, PermissionManager permManager,
            NotificationChannelLogger notificationChannelLogger,
            AppOpsManager appOpsManager, ManagedServices.UserProfiles userProfiles,
            boolean showReviewPermissionsNotification) {
        mContext = context;
        mZenModeHelper = zenHelper;
        mRankingHandler = rankingHandler;
        mPermissionHelper = permHelper;
        mPermissionManager = permManager;
        mPm = pm;
        mNotificationChannelLogger = notificationChannelLogger;
        mAppOps = appOpsManager;
        mUserProfiles = userProfiles;
        mShowReviewPermissionsNotification = showReviewPermissionsNotification;
        mIsMediaNotificationFilteringEnabled = context.getResources()
                .getBoolean(R.bool.config_quickSettingsShowMediaPlayer);

        XML_VERSION = 4;

        updateBadgingEnabled();
        updateBubblesEnabled();
        updateMediaNotificationFilteringEnabled();
    }

    public void readXml(TypedXmlPullParser parser, boolean forRestore, int userId)
            throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type != XmlPullParser.START_TAG) return;
        String tag = parser.getName();
        if (!TAG_RANKING.equals(tag)) return;

        final int xmlVersion = parser.getAttributeInt(null, ATT_VERSION, -1);
        boolean upgradeForBubbles = xmlVersion == XML_VERSION_BUBBLES_UPGRADE;
        boolean migrateToPermission = (xmlVersion < XML_VERSION_NOTIF_PERMISSION);
        if (mShowReviewPermissionsNotification
                && (xmlVersion < XML_VERSION_REVIEW_PERMISSIONS_NOTIFICATION)) {
            // make a note that we should show the notification at some point.
            // it shouldn't be possible for the user to already have seen it, as the XML version
            // would be newer then.
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                    NotificationManagerService.REVIEW_NOTIF_STATE_SHOULD_SHOW);
        }
        synchronized (mPackagePreferences) {
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                tag = parser.getName();
                if (type == XmlPullParser.END_TAG && TAG_RANKING.equals(tag)) {
                    break;
                }
                if (type == XmlPullParser.START_TAG) {
                    if (TAG_STATUS_ICONS.equals(tag)) {
                        if (forRestore && userId != UserHandle.USER_SYSTEM) {
                            continue;
                        }
                        mHideSilentStatusBarIcons = parser.getAttributeBoolean(null,
                                ATT_HIDE_SILENT, DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS);
                    } else if (TAG_PACKAGE.equals(tag)) {
                        String name = parser.getAttributeValue(null, ATT_NAME);
                        if (!TextUtils.isEmpty(name)) {
                            restorePackage(parser, forRestore, userId, name, upgradeForBubbles,
                                    migrateToPermission);
                        }
                    }
                }
            }
        }
    }

    @GuardedBy("mPackagePreferences")
    private void restorePackage(TypedXmlPullParser parser, boolean forRestore,
            @UserIdInt int userId, String name, boolean upgradeForBubbles,
            boolean migrateToPermission) {
        try {
            int uid = parser.getAttributeInt(null, ATT_UID, UNKNOWN_UID);
            if (forRestore) {
                try {
                    uid = mPm.getPackageUidAsUser(name, userId);
                } catch (PackageManager.NameNotFoundException e) {
                    // noop
                }
            }
            boolean skipWarningLogged = false;
            boolean skipGroupWarningLogged = false;
            boolean hasSAWPermission = false;
            if (upgradeForBubbles && uid != UNKNOWN_UID) {
                hasSAWPermission = mAppOps.noteOpNoThrow(
                        OP_SYSTEM_ALERT_WINDOW, uid, name, null,
                        "check-notif-bubble") == AppOpsManager.MODE_ALLOWED;
            }
            int bubblePref = hasSAWPermission
                    ? BUBBLE_PREFERENCE_ALL
                    : parser.getAttributeInt(null, ATT_ALLOW_BUBBLE, DEFAULT_BUBBLE_PREFERENCE);
            int appImportance = parser.getAttributeInt(null, ATT_IMPORTANCE, DEFAULT_IMPORTANCE);

            PackagePreferences r = getOrCreatePackagePreferencesLocked(
                    name, userId, uid,
                    appImportance,
                    parser.getAttributeInt(null, ATT_PRIORITY, DEFAULT_PRIORITY),
                    parser.getAttributeInt(null, ATT_VISIBILITY, DEFAULT_VISIBILITY),
                    parser.getAttributeBoolean(null, ATT_SHOW_BADGE, DEFAULT_SHOW_BADGE),
                    bubblePref);
            r.priority = parser.getAttributeInt(null, ATT_PRIORITY, DEFAULT_PRIORITY);
            r.visibility = parser.getAttributeInt(null, ATT_VISIBILITY, DEFAULT_VISIBILITY);
            r.showBadge = parser.getAttributeBoolean(null, ATT_SHOW_BADGE, DEFAULT_SHOW_BADGE);
            r.lockedAppFields = parser.getAttributeInt(null,
                    ATT_APP_USER_LOCKED_FIELDS, DEFAULT_LOCKED_APP_FIELDS);
            r.hasSentInvalidMessage = parser.getAttributeBoolean(null, ATT_SENT_INVALID_MESSAGE,
                    false);
            r.hasSentValidMessage = parser.getAttributeBoolean(null, ATT_SENT_VALID_MESSAGE, false);
            r.userDemotedMsgApp = parser.getAttributeBoolean(
                    null, ATT_USER_DEMOTED_INVALID_MSG_APP, false);
            r.hasSentValidBubble = parser.getAttributeBoolean(null, ATT_SENT_VALID_BUBBLE, false);

            final int innerDepth = parser.getDepth();
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG
                    || parser.getDepth() > innerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                // Channel groups
                if (TAG_GROUP.equals(tagName)) {
                    if (r.groups.size() >= NOTIFICATION_CHANNEL_GROUP_COUNT_LIMIT) {
                        if (!skipGroupWarningLogged) {
                            Slog.w(TAG, "Skipping further groups for " + r.pkg);
                            skipGroupWarningLogged = true;
                        }
                        continue;
                    }
                    String id = parser.getAttributeValue(null, ATT_ID);
                    CharSequence groupName = parser.getAttributeValue(null, ATT_NAME);
                    if (!TextUtils.isEmpty(id)) {
                        NotificationChannelGroup group =
                                new NotificationChannelGroup(id, groupName);
                        group.populateFromXml(parser);
                        r.groups.put(id, group);
                    }
                }
                // Channels
                if (TAG_CHANNEL.equals(tagName)) {
                    if (r.channels.size() >= NOTIFICATION_CHANNEL_COUNT_LIMIT) {
                        if (!skipWarningLogged) {
                            Slog.w(TAG, "Skipping further channels for " + r.pkg);
                            skipWarningLogged = true;
                        }
                        continue;
                    }
                    restoreChannel(parser, forRestore, r);
                }

                // Delegate
                if (TAG_DELEGATE.equals(tagName)) {
                    int delegateId = parser.getAttributeInt(null, ATT_UID, UNKNOWN_UID);
                    String delegateName = XmlUtils.readStringAttribute(parser, ATT_NAME);
                    boolean delegateEnabled = parser.getAttributeBoolean(
                            null, ATT_ENABLED, Delegate.DEFAULT_ENABLED);
                    Delegate d = null;
                    if (delegateId != UNKNOWN_UID && !TextUtils.isEmpty(delegateName)) {
                        d = new Delegate(delegateName, delegateId, delegateEnabled);
                    }
                    r.delegate = d;
                }
            }

            try {
                deleteDefaultChannelIfNeededLocked(r);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "deleteDefaultChannelIfNeededLocked - Exception: " + e);
            }

            if (migrateToPermission) {
                r.importance = appImportance;
                r.migrateToPm = true;
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to restore pkg", e);
        }
    }

    @GuardedBy("mPackagePreferences")
    private void restoreChannel(TypedXmlPullParser parser, boolean forRestore,
            PackagePreferences r) {
        try {
            String id = parser.getAttributeValue(null, ATT_ID);
            String channelName = parser.getAttributeValue(null, ATT_NAME);
            int channelImportance = parser.getAttributeInt(
                    null, ATT_IMPORTANCE, DEFAULT_IMPORTANCE);
            if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(channelName)) {
                NotificationChannel channel = new NotificationChannel(
                        id, channelName, channelImportance);
                if (forRestore) {
                    final boolean pkgInstalled = r.uid != UNKNOWN_UID;
                    channel.populateFromXmlForRestore(parser, pkgInstalled, mContext);
                } else {
                    channel.populateFromXml(parser);
                }
                channel.setImportanceLockedByCriticalDeviceFunction(
                        r.defaultAppLockedImportance || r.fixedImportance);

                if (isShortcutOk(channel) && isDeletionOk(channel)) {
                    r.channels.put(id, channel);
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "could not restore channel for " + r.pkg, e);
        }
    }

    @GuardedBy("mPackagePreferences")
    private boolean hasUserConfiguredSettings(PackagePreferences p){
        boolean hasChangedChannel = false;
        for (NotificationChannel channel : p.channels.values()) {
            if (channel.getUserLockedFields() != 0) {
                hasChangedChannel = true;
                break;
            }
        }
        return hasChangedChannel || p.importance == IMPORTANCE_NONE;
    }

    private boolean isShortcutOk(NotificationChannel channel) {
        boolean isInvalidShortcutChannel =
                channel.getConversationId() != null &&
                        channel.getConversationId().contains(
                                PLACEHOLDER_CONVERSATION_ID);
        return !isInvalidShortcutChannel;
    }

    private boolean isDeletionOk(NotificationChannel nc) {
        if (!nc.isDeleted()) {
            return true;
        }
        long boundary = System.currentTimeMillis() - (
                DateUtils.DAY_IN_MILLIS * NOTIFICATION_CHANNEL_DELETION_RETENTION_DAYS);
        if (nc.getDeletedTimeMs() <= boundary) {
            return false;
        }
        return true;
    }

    private PackagePreferences getPackagePreferencesLocked(String pkg, int uid) {
        final String key = packagePreferencesKey(pkg, uid);
        return mPackagePreferences.get(key);
    }

    private PackagePreferences getOrCreatePackagePreferencesLocked(String pkg,
            int uid) {
        // TODO (b/194833441): use permissionhelper instead of DEFAULT_IMPORTANCE
        return getOrCreatePackagePreferencesLocked(pkg, UserHandle.getUserId(uid), uid,
                DEFAULT_IMPORTANCE, DEFAULT_PRIORITY, DEFAULT_VISIBILITY, DEFAULT_SHOW_BADGE,
                DEFAULT_BUBBLE_PREFERENCE);
    }

    private PackagePreferences getOrCreatePackagePreferencesLocked(String pkg,
            @UserIdInt int userId, int uid, int importance, int priority, int visibility,
            boolean showBadge, int bubblePreference) {
        final String key = packagePreferencesKey(pkg, uid);
        PackagePreferences
                r = (uid == UNKNOWN_UID)
                ? mRestoredWithoutUids.get(unrestoredPackageKey(pkg, userId))
                : mPackagePreferences.get(key);
        if (r == null) {
            r = new PackagePreferences();
            r.pkg = pkg;
            r.uid = uid;
            r.importance = importance;
            r.priority = priority;
            r.visibility = visibility;
            r.showBadge = showBadge;
            r.bubblePreference = bubblePreference;

            try {
                createDefaultChannelIfNeededLocked(r);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "createDefaultChannelIfNeededLocked - Exception: " + e);
            }

            if (r.uid == UNKNOWN_UID) {
                mRestoredWithoutUids.put(unrestoredPackageKey(pkg, userId), r);
            } else {
                mPackagePreferences.put(key, r);
            }
        }
        return r;
    }

    private boolean shouldHaveDefaultChannel(PackagePreferences r) throws
            PackageManager.NameNotFoundException {
        final int userId = UserHandle.getUserId(r.uid);
        final ApplicationInfo applicationInfo =
                mPm.getApplicationInfoAsUser(r.pkg, 0, userId);
        if (applicationInfo.targetSdkVersion >= Build.VERSION_CODES.O) {
            // O apps should not have the default channel.
            return false;
        }

        // Otherwise, this app should have the default channel.
        return true;
    }

    private boolean deleteDefaultChannelIfNeededLocked(PackagePreferences r) throws
            PackageManager.NameNotFoundException {
        if (!r.channels.containsKey(NotificationChannel.DEFAULT_CHANNEL_ID)) {
            // Not present
            return false;
        }

        if (shouldHaveDefaultChannel(r)) {
            // Keep the default channel until upgraded.
            return false;
        }

        // Remove Default Channel.
        r.channels.remove(NotificationChannel.DEFAULT_CHANNEL_ID);

        return true;
    }

    private boolean createDefaultChannelIfNeededLocked(PackagePreferences r) throws
            PackageManager.NameNotFoundException {
        if (r.uid == UNKNOWN_UID) {
            return false;
        }

        if (r.channels.containsKey(NotificationChannel.DEFAULT_CHANNEL_ID)) {
            r.channels.get(NotificationChannel.DEFAULT_CHANNEL_ID).setName(mContext.getString(
                    com.android.internal.R.string.default_notification_channel_label));
            return false;
        }

        if (!shouldHaveDefaultChannel(r)) {
            // Keep the default channel until upgraded.
            return false;
        }

        // Create Default Channel
        NotificationChannel channel;
        channel = new NotificationChannel(
                NotificationChannel.DEFAULT_CHANNEL_ID,
                mContext.getString(R.string.default_notification_channel_label),
                r.importance);
        channel.setBypassDnd(r.priority == Notification.PRIORITY_MAX);
        channel.setLockscreenVisibility(r.visibility);
        if (r.importance != NotificationManager.IMPORTANCE_UNSPECIFIED) {
            channel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
        }
        if (r.priority != DEFAULT_PRIORITY) {
            channel.lockFields(NotificationChannel.USER_LOCKED_PRIORITY);
        }
        if (r.visibility != DEFAULT_VISIBILITY) {
            channel.lockFields(NotificationChannel.USER_LOCKED_VISIBILITY);
        }
        r.channels.put(channel.getId(), channel);

        return true;
    }

    public void writeXml(TypedXmlSerializer out, boolean forBackup, int userId) throws IOException {
        out.startTag(null, TAG_RANKING);
        out.attributeInt(null, ATT_VERSION, XML_VERSION);
        if (mHideSilentStatusBarIcons != DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS) {
            out.startTag(null, TAG_STATUS_ICONS);
            out.attributeBoolean(null, ATT_HIDE_SILENT, mHideSilentStatusBarIcons);
            out.endTag(null, TAG_STATUS_ICONS);
        }
        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> notifPermissions = new ArrayMap<>();
        if (forBackup) {
            notifPermissions = mPermissionHelper.getNotificationPermissionValues(userId);
        }

        synchronized (mPackagePreferences) {
            final int N = mPackagePreferences.size();
            for (int i = 0; i < N; i++) {
                final PackagePreferences r = mPackagePreferences.valueAt(i);
                if (forBackup && UserHandle.getUserId(r.uid) != userId) {
                    continue;
                }
                out.startTag(null, TAG_PACKAGE);
                out.attribute(null, ATT_NAME, r.pkg);
                if (!notifPermissions.isEmpty()) {
                    Pair<Integer, String> app = new Pair(r.uid, r.pkg);
                    final Pair<Boolean, Boolean> permission = notifPermissions.get(app);
                    out.attributeInt(null, ATT_IMPORTANCE,
                            permission != null && permission.first ? IMPORTANCE_DEFAULT
                                    : IMPORTANCE_NONE);
                    notifPermissions.remove(app);
                } else {
                    if (r.importance != DEFAULT_IMPORTANCE) {
                        out.attributeInt(null, ATT_IMPORTANCE, r.importance);
                    }
                }
                if (r.priority != DEFAULT_PRIORITY) {
                    out.attributeInt(null, ATT_PRIORITY, r.priority);
                }
                if (r.visibility != DEFAULT_VISIBILITY) {
                    out.attributeInt(null, ATT_VISIBILITY, r.visibility);
                }
                if (r.bubblePreference != DEFAULT_BUBBLE_PREFERENCE) {
                    out.attributeInt(null, ATT_ALLOW_BUBBLE, r.bubblePreference);
                }
                out.attributeBoolean(null, ATT_SHOW_BADGE, r.showBadge);
                out.attributeInt(null, ATT_APP_USER_LOCKED_FIELDS,
                        r.lockedAppFields);
                out.attributeBoolean(null, ATT_SENT_INVALID_MESSAGE,
                        r.hasSentInvalidMessage);
                out.attributeBoolean(null, ATT_SENT_VALID_MESSAGE,
                        r.hasSentValidMessage);
                out.attributeBoolean(null, ATT_USER_DEMOTED_INVALID_MSG_APP,
                        r.userDemotedMsgApp);
                out.attributeBoolean(null, ATT_SENT_VALID_BUBBLE, r.hasSentValidBubble);

                if (!forBackup) {
                    out.attributeInt(null, ATT_UID, r.uid);
                }

                if (r.delegate != null) {
                    out.startTag(null, TAG_DELEGATE);

                    out.attribute(null, ATT_NAME, r.delegate.mPkg);
                    out.attributeInt(null, ATT_UID, r.delegate.mUid);
                    if (r.delegate.mEnabled != Delegate.DEFAULT_ENABLED) {
                        out.attributeBoolean(null, ATT_ENABLED, r.delegate.mEnabled);
                    }
                    out.endTag(null, TAG_DELEGATE);
                }

                for (NotificationChannelGroup group : r.groups.values()) {
                    group.writeXml(out);
                }

                for (NotificationChannel channel : r.channels.values()) {
                    if (forBackup) {
                        if (!channel.isDeleted()) {
                            channel.writeXmlForBackup(out, mContext);
                        }
                    } else {
                        channel.writeXml(out);
                    }
                }

                out.endTag(null, TAG_PACKAGE);
            }
        }
        // Some apps have permissions set but don't have expanded notification settings
        if (!notifPermissions.isEmpty()) {
            for (Pair<Integer, String> app : notifPermissions.keySet()) {
                out.startTag(null, TAG_PACKAGE);
                out.attribute(null, ATT_NAME, app.second);
                out.attributeInt(null, ATT_IMPORTANCE,
                        notifPermissions.get(app).first ? IMPORTANCE_DEFAULT : IMPORTANCE_NONE);
                out.endTag(null, TAG_PACKAGE);
            }
        }
        out.endTag(null, TAG_RANKING);
    }

    /**
     * Sets whether bubbles are allowed.
     *
     * @param pkg the package to allow or not allow bubbles for.
     * @param uid the uid to allow or not allow bubbles for.
     * @param bubblePreference whether bubbles are allowed.
     */
    public void setBubblesAllowed(String pkg, int uid, int bubblePreference) {
        boolean changed = false;
        synchronized (mPackagePreferences) {
            PackagePreferences p = getOrCreatePackagePreferencesLocked(pkg, uid);
            changed = p.bubblePreference != bubblePreference;
            p.bubblePreference = bubblePreference;
            p.lockedAppFields = p.lockedAppFields | LockableAppFields.USER_LOCKED_BUBBLE;
        }
        if (changed) {
            updateConfig();
        }
    }

    /**
     * Whether bubbles are allowed.
     *
     * @param pkg the package to check if bubbles are allowed for
     * @param uid the uid to check if bubbles are allowed for.
     * @return whether bubbles are allowed.
     */
    @Override
    public int getBubblePreference(String pkg, int uid) {
        synchronized (mPackagePreferences) {
            return getOrCreatePackagePreferencesLocked(pkg, uid).bubblePreference;
        }
    }

    public int getAppLockedFields(String pkg, int uid) {
        synchronized (mPackagePreferences) {
            return getOrCreatePackagePreferencesLocked(pkg, uid).lockedAppFields;
        }
    }

    @Override
    public boolean canShowBadge(String packageName, int uid) {
        synchronized (mPackagePreferences) {
            return getOrCreatePackagePreferencesLocked(packageName, uid).showBadge;
        }
    }

    @Override
    public void setShowBadge(String packageName, int uid, boolean showBadge) {
        boolean changed = false;
        synchronized (mPackagePreferences) {
            PackagePreferences pkgPrefs = getOrCreatePackagePreferencesLocked(packageName, uid);
            if (pkgPrefs.showBadge != showBadge) {
                pkgPrefs.showBadge = showBadge;
                changed = true;
            }
        }
        if (changed) {
            updateConfig();
        }
    }

    public boolean isInInvalidMsgState(String packageName, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(packageName, uid);
            return r.hasSentInvalidMessage && !r.hasSentValidMessage;
        }
    }

    public boolean hasUserDemotedInvalidMsgApp(String packageName, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(packageName, uid);
            return isInInvalidMsgState(packageName, uid) ? r.userDemotedMsgApp : false;
        }
    }

    public void setInvalidMsgAppDemoted(String packageName, int uid, boolean isDemoted) {
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(packageName, uid);
            r.userDemotedMsgApp = isDemoted;
        }
    }

    public boolean setInvalidMessageSent(String packageName, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(packageName, uid);
            boolean valueChanged = r.hasSentInvalidMessage == false;
            r.hasSentInvalidMessage = true;

            return valueChanged;
        }
    }

    public boolean setValidMessageSent(String packageName, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(packageName, uid);
            boolean valueChanged = r.hasSentValidMessage == false;
            r.hasSentValidMessage = true;

            return valueChanged;
        }
    }

    @VisibleForTesting
    boolean hasSentInvalidMsg(String packageName, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(packageName, uid);
            return r.hasSentInvalidMessage;
        }
    }

    @VisibleForTesting
    boolean hasSentValidMsg(String packageName, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(packageName, uid);
            return r.hasSentValidMessage;
        }
    }

    @VisibleForTesting
    boolean didUserEverDemoteInvalidMsgApp(String packageName, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(packageName, uid);
            return r.userDemotedMsgApp;
        }
    }

    /** Sets whether this package has sent a notification with valid bubble metadata. */
    public boolean setValidBubbleSent(String packageName, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(packageName, uid);
            boolean valueChanged = !r.hasSentValidBubble;
            r.hasSentValidBubble = true;
            return valueChanged;
        }
    }

    boolean hasSentValidBubble(String packageName, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(packageName, uid);
            return r.hasSentValidBubble;
        }
    }

    boolean isImportanceLocked(String pkg, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
            return r.fixedImportance || r.defaultAppLockedImportance;
        }
    }

    @Override
    public boolean isGroupBlocked(String packageName, int uid, String groupId) {
        if (groupId == null) {
            return false;
        }
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(packageName, uid);
            NotificationChannelGroup group = r.groups.get(groupId);
            if (group == null) {
                return false;
            }
            return group.isBlocked();
        }
    }

    int getPackagePriority(String pkg, int uid) {
        synchronized (mPackagePreferences) {
            return getOrCreatePackagePreferencesLocked(pkg, uid).priority;
        }
    }

    int getPackageVisibility(String pkg, int uid) {
        synchronized (mPackagePreferences) {
            return getOrCreatePackagePreferencesLocked(pkg, uid).visibility;
        }
    }

    @Override
    public void createNotificationChannelGroup(String pkg, int uid, NotificationChannelGroup group,
            boolean fromTargetApp, int callingUid, boolean fromSystemOrSystemUi) {
        Objects.requireNonNull(pkg);
        Objects.requireNonNull(group);
        Objects.requireNonNull(group.getId());
        if (TextUtils.isEmpty(group.getName())) {
            throw new IllegalArgumentException("group.getName() can't be empty");
        }
        boolean needsDndChange = false;
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
            if (r == null) {
                throw new IllegalArgumentException("Invalid package");
            }
            if (r.groups.size() >= NOTIFICATION_CHANNEL_GROUP_COUNT_LIMIT) {
                throw new IllegalStateException("Limit exceed; cannot create more groups");
            }
            if (fromTargetApp) {
                group.setBlocked(false);
            }
            final NotificationChannelGroup oldGroup = r.groups.get(group.getId());
            if (oldGroup != null) {
                group.setChannels(oldGroup.getChannels());

                // apps can't update the blocked status or app overlay permission
                if (fromTargetApp) {
                    group.setBlocked(oldGroup.isBlocked());
                    group.unlockFields(group.getUserLockedFields());
                    group.lockFields(oldGroup.getUserLockedFields());
                } else {
                    // but the system can
                    if (group.isBlocked() != oldGroup.isBlocked()) {
                        group.lockFields(NotificationChannelGroup.USER_LOCKED_BLOCKED_STATE);
                        needsDndChange = true;
                    }
                }
            }
            if (!group.equals(oldGroup)) {
                // will log for new entries as well as name/description changes
                MetricsLogger.action(getChannelGroupLog(group.getId(), pkg));
                mNotificationChannelLogger.logNotificationChannelGroup(group, uid, pkg,
                        oldGroup == null,
                        (oldGroup != null) && oldGroup.isBlocked());
            }
            r.groups.put(group.getId(), group);
        }
        if (needsDndChange) {
            updateCurrentUserHasChannelsBypassingDnd(callingUid, fromSystemOrSystemUi);
        }
    }

    @Override
    public boolean createNotificationChannel(String pkg, int uid, NotificationChannel channel,
            boolean fromTargetApp, boolean hasDndAccess, int callingUid,
            boolean fromSystemOrSystemUi) {
        Objects.requireNonNull(pkg);
        Objects.requireNonNull(channel);
        Objects.requireNonNull(channel.getId());
        Preconditions.checkArgument(!TextUtils.isEmpty(channel.getName()));
        Preconditions.checkArgument(channel.getImportance() >= IMPORTANCE_NONE
                && channel.getImportance() <= IMPORTANCE_MAX, "Invalid importance level");

        boolean needsPolicyFileChange = false, wasUndeleted = false, needsDndChange = false;
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
            if (r == null) {
                throw new IllegalArgumentException("Invalid package");
            }
            if (channel.getGroup() != null && !r.groups.containsKey(channel.getGroup())) {
                throw new IllegalArgumentException("NotificationChannelGroup doesn't exist");
            }
            if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(channel.getId())) {
                throw new IllegalArgumentException("Reserved id");
            }
            NotificationChannel existing = r.channels.get(channel.getId());
            if (existing != null && fromTargetApp) {
                // Actually modifying an existing channel - keep most of the existing settings
                if (existing.isDeleted()) {
                    // The existing channel was deleted - undelete it.
                    existing.setDeleted(false);
                    existing.setDeletedTimeMs(-1);
                    needsPolicyFileChange = true;
                    wasUndeleted = true;

                    // log a resurrected channel as if it's new again
                    MetricsLogger.action(getChannelLog(channel, pkg).setType(
                            com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_OPEN));
                    mNotificationChannelLogger.logNotificationChannelCreated(channel, uid, pkg);
                }

                if (!Objects.equals(channel.getName().toString(), existing.getName().toString())) {
                    existing.setName(channel.getName().toString());
                    needsPolicyFileChange = true;
                }
                if (!Objects.equals(channel.getDescription(), existing.getDescription())) {
                    existing.setDescription(channel.getDescription());
                    needsPolicyFileChange = true;
                }
                if (channel.isBlockable() != existing.isBlockable()) {
                    existing.setBlockable(channel.isBlockable());
                    needsPolicyFileChange = true;
                }
                if (channel.getGroup() != null && existing.getGroup() == null) {
                    existing.setGroup(channel.getGroup());
                    needsPolicyFileChange = true;
                }

                // Apps are allowed to downgrade channel importance if the user has not changed any
                // fields on this channel yet.
                final int previousExistingImportance = existing.getImportance();
                final int previousLoggingImportance =
                        NotificationChannelLogger.getLoggingImportance(existing);
                if (existing.getUserLockedFields() == 0 &&
                        channel.getImportance() < existing.getImportance()) {
                    existing.setImportance(channel.getImportance());
                    needsPolicyFileChange = true;
                }

                // system apps and dnd access apps can bypass dnd if the user hasn't changed any
                // fields on the channel yet
                if (existing.getUserLockedFields() == 0 && hasDndAccess) {
                    boolean bypassDnd = channel.canBypassDnd();
                    if (bypassDnd != existing.canBypassDnd() || wasUndeleted) {
                        existing.setBypassDnd(bypassDnd);
                        needsPolicyFileChange = true;

                        if (bypassDnd != mCurrentUserHasChannelsBypassingDnd
                                || previousExistingImportance != existing.getImportance()) {
                            needsDndChange = true;
                        }
                    }
                }

                if (existing.getOriginalImportance() == IMPORTANCE_UNSPECIFIED) {
                    existing.setOriginalImportance(channel.getImportance());
                    needsPolicyFileChange = true;
                }

                if (needsPolicyFileChange) {
                    updateConfig();
                }
                if (needsPolicyFileChange && !wasUndeleted) {
                    mNotificationChannelLogger.logNotificationChannelModified(existing, uid, pkg,
                            previousLoggingImportance, false);
                }
            } else {
                if (r.channels.size() >= NOTIFICATION_CHANNEL_COUNT_LIMIT) {
                    throw new IllegalStateException("Limit exceed; cannot create more channels");
                }

                needsPolicyFileChange = true;

                // Reset fields that apps aren't allowed to set.
                if (fromTargetApp && !hasDndAccess) {
                    channel.setBypassDnd(r.priority == Notification.PRIORITY_MAX);
                }
                if (fromTargetApp) {
                    channel.setLockscreenVisibility(r.visibility);
                    channel.setAllowBubbles(existing != null
                            ? existing.getAllowBubbles()
                            : NotificationChannel.DEFAULT_ALLOW_BUBBLE);
                    channel.setImportantConversation(false);
                }
                clearLockedFieldsLocked(channel);

                channel.setImportanceLockedByCriticalDeviceFunction(
                        r.defaultAppLockedImportance || r.fixedImportance);

                if (channel.getLockscreenVisibility() == Notification.VISIBILITY_PUBLIC) {
                    channel.setLockscreenVisibility(
                            NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE);
                }
                if (!r.showBadge) {
                    channel.setShowBadge(false);
                }
                channel.setOriginalImportance(channel.getImportance());

                // validate parent
                if (channel.getParentChannelId() != null) {
                    Preconditions.checkArgument(
                            r.channels.containsKey(channel.getParentChannelId()),
                            "Tried to create a conversation channel without a preexisting parent");
                }

                r.channels.put(channel.getId(), channel);
                if (channel.canBypassDnd() != mCurrentUserHasChannelsBypassingDnd) {
                    needsDndChange = true;
                }
                MetricsLogger.action(getChannelLog(channel, pkg).setType(
                        com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_OPEN));
                mNotificationChannelLogger.logNotificationChannelCreated(channel, uid, pkg);
            }
        }

        if (needsDndChange) {
            updateCurrentUserHasChannelsBypassingDnd(callingUid, fromSystemOrSystemUi);
        }

        return needsPolicyFileChange;
    }

    void clearLockedFieldsLocked(NotificationChannel channel) {
        channel.unlockFields(channel.getUserLockedFields());
    }

    void unlockNotificationChannelImportance(String pkg, int uid, String updatedChannelId) {
        Objects.requireNonNull(updatedChannelId);
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
            if (r == null) {
                throw new IllegalArgumentException("Invalid package");
            }

            NotificationChannel channel = r.channels.get(updatedChannelId);
            if (channel == null || channel.isDeleted()) {
                throw new IllegalArgumentException("Channel does not exist");
            }
            channel.unlockFields(USER_LOCKED_IMPORTANCE);
        }
    }


    @Override
    public void updateNotificationChannel(String pkg, int uid, NotificationChannel updatedChannel,
            boolean fromUser, int callingUid, boolean fromSystemOrSystemUi) {
        Objects.requireNonNull(updatedChannel);
        Objects.requireNonNull(updatedChannel.getId());
        boolean changed = false;
        boolean needsDndChange = false;
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
            if (r == null) {
                throw new IllegalArgumentException("Invalid package");
            }
            NotificationChannel channel = r.channels.get(updatedChannel.getId());
            if (channel == null || channel.isDeleted()) {
                throw new IllegalArgumentException("Channel does not exist");
            }
            if (updatedChannel.getLockscreenVisibility() == Notification.VISIBILITY_PUBLIC) {
                updatedChannel.setLockscreenVisibility(
                        NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE);
            }
            if (fromUser) {
                updatedChannel.lockFields(channel.getUserLockedFields());
                lockFieldsForUpdateLocked(channel, updatedChannel);
            } else {
                updatedChannel.unlockFields(updatedChannel.getUserLockedFields());
            }

            if (channel.isImportanceLockedByCriticalDeviceFunction()
                    && !(channel.isBlockable() || channel.getImportance() == IMPORTANCE_NONE)) {
                updatedChannel.setImportance(channel.getImportance());
            }

            r.channels.put(updatedChannel.getId(), updatedChannel);

            if (onlyHasDefaultChannel(pkg, uid)) {
                r.priority = updatedChannel.canBypassDnd()
                        ? Notification.PRIORITY_MAX : Notification.PRIORITY_DEFAULT;
                r.visibility = updatedChannel.getLockscreenVisibility();
                r.showBadge = updatedChannel.canShowBadge();
                changed = true;
            }

            if (!channel.equals(updatedChannel)) {
                // only log if there are real changes
                MetricsLogger.action(getChannelLog(updatedChannel, pkg)
                        .setSubtype(fromUser ? NOTIFICATION_UPDATE_LOG_SUBTYPE_FROM_USER
                                : NOTIFICATION_UPDATE_LOG_SUBTYPE_FROM_APP));
                mNotificationChannelLogger.logNotificationChannelModified(updatedChannel, uid, pkg,
                        NotificationChannelLogger.getLoggingImportance(channel), fromUser);
                changed = true;
            }

            if (fromUser && SystemUiSystemPropertiesFlags.getResolver().isEnabled(
                    NotificationFlags.PROPAGATE_CHANNEL_UPDATES_TO_CONVERSATIONS)) {
                updateChildrenConversationChannels(r, channel, updatedChannel);
                // No need to update changed or needsDndChanged as the child channel(s) cannot be
                // relevantly affected without the parent channel already having been.
            }

            if (updatedChannel.canBypassDnd() != mCurrentUserHasChannelsBypassingDnd
                    || channel.getImportance() != updatedChannel.getImportance()) {
                needsDndChange = true;
                changed = true;
            }
        }
        if (needsDndChange) {
            updateCurrentUserHasChannelsBypassingDnd(callingUid, fromSystemOrSystemUi);
        }
        if (changed) {
            updateConfig();
        }
    }

    /**
     * Updates conversation channels after user changes to their parent channel. See
     * {@link #maybeUpdateChildConversationChannel}.
     */
    @GuardedBy("mPackagePreferences")
    private void updateChildrenConversationChannels(@NonNull PackagePreferences packagePreferences,
            @NonNull NotificationChannel oldParent, @NonNull NotificationChannel updatedParent) {
        if (oldParent.equals(updatedParent)) {
            return;
        }
        if (oldParent.isConversation()) {
            return; // Can't have children.
        }
        for (NotificationChannel channel : packagePreferences.channels.values()) {
            // Include deleted -- otherwise they will have old settings if later resurrected.
            // Include demoted -- still attached to their parents.
            if (channel.isConversation()
                    && oldParent.getId().equals(channel.getParentChannelId())) {
                maybeUpdateChildConversationChannel(packagePreferences.pkg, packagePreferences.uid,
                        channel, oldParent, updatedParent);
            }
        }
    }

    /**
     * Apply the diff between {@code oldParent} and {@code updatedParent} to the child
     * {@code conversation} channel. Only fields that are not locked on the conversation channel
     * (see {@link NotificationChannel#LOCKABLE_FIELDS }) will be updated (so that we don't override
     * previous explicit user choices).
     *
     * <p>This will also log the change as if it was {@code fromUser=true}.
     */
    @GuardedBy("mPackagePreferences")
    private void maybeUpdateChildConversationChannel(String pkg, int uid,
            @NonNull NotificationChannel conversation, @NonNull NotificationChannel oldParent,
            @NonNull NotificationChannel updatedParent) {
        boolean changed = false;
        int oldLoggingImportance = NotificationChannelLogger.getLoggingImportance(conversation);

        if ((conversation.getUserLockedFields() & NotificationChannel.USER_LOCKED_PRIORITY) == 0
                && oldParent.canBypassDnd() != updatedParent.canBypassDnd()) {
            conversation.setBypassDnd(updatedParent.canBypassDnd());
            changed = true;
        }
        if ((conversation.getUserLockedFields() & NotificationChannel.USER_LOCKED_VISIBILITY) == 0
                && oldParent.getLockscreenVisibility()
                != updatedParent.getLockscreenVisibility()) {
            conversation.setLockscreenVisibility(updatedParent.getLockscreenVisibility());
            changed = true;
        }
        if ((conversation.getUserLockedFields() & NotificationChannel.USER_LOCKED_IMPORTANCE) == 0
                && oldParent.getImportance() != updatedParent.getImportance()) {
            conversation.setImportance(updatedParent.getImportance());
            changed = true;
        }
        if ((conversation.getUserLockedFields() & NotificationChannel.USER_LOCKED_LIGHTS) == 0
                && (oldParent.shouldShowLights() != updatedParent.shouldShowLights()
                || oldParent.getLightColor() != updatedParent.getLightColor())) {
            conversation.enableLights(updatedParent.shouldShowLights());
            conversation.setLightColor(updatedParent.getLightColor());
            changed = true;
        }
        if ((conversation.getUserLockedFields() & NotificationChannel.USER_LOCKED_SOUND) == 0
                && !Objects.equals(oldParent.getSound(), updatedParent.getSound())) {
            conversation.setSound(updatedParent.getSound(), updatedParent.getAudioAttributes());
            changed = true;
        }
        if ((conversation.getUserLockedFields() & NotificationChannel.USER_LOCKED_VIBRATION) == 0
                && (!Arrays.equals(oldParent.getVibrationPattern(),
                updatedParent.getVibrationPattern())
                || !Objects.equals(
                        oldParent.getVibrationEffect(), updatedParent.getVibrationEffect())
                || oldParent.shouldVibrate() != updatedParent.shouldVibrate())) {
            // enableVibration must be 2nd because setVibrationPattern may toggle it.
            conversation.setVibrationPattern(updatedParent.getVibrationPattern());
            conversation.enableVibration(updatedParent.shouldVibrate());
            changed = true;
        }
        if ((conversation.getUserLockedFields() & NotificationChannel.USER_LOCKED_SHOW_BADGE) == 0
                && oldParent.canShowBadge() != updatedParent.canShowBadge()) {
            conversation.setShowBadge(updatedParent.canShowBadge());
            changed = true;
        }
        if ((conversation.getUserLockedFields() & NotificationChannel.USER_LOCKED_ALLOW_BUBBLE) == 0
                && oldParent.getAllowBubbles() != updatedParent.getAllowBubbles()) {
            conversation.setAllowBubbles(updatedParent.getAllowBubbles());
            changed = true;
        }

        if (changed) {
            MetricsLogger.action(getChannelLog(conversation, pkg).setSubtype(
                    NOTIFICATION_UPDATE_LOG_SUBTYPE_FROM_USER));
            mNotificationChannelLogger.logNotificationChannelModified(conversation, uid, pkg,
                    oldLoggingImportance, /* fromUser= */ true);
        }
    }

    @Override
    public NotificationChannel getNotificationChannel(String pkg, int uid, String channelId,
            boolean includeDeleted) {
        Objects.requireNonNull(pkg);
        return getConversationNotificationChannel(pkg, uid, channelId, null, true, includeDeleted);
    }

    @Override
    public NotificationChannel getConversationNotificationChannel(String pkg, int uid,
            String channelId, String conversationId, boolean returnParentIfNoConversationChannel,
            boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return null;
            }
            if (channelId == null) {
                channelId = NotificationChannel.DEFAULT_CHANNEL_ID;
            }
            NotificationChannel channel = null;
            if (conversationId != null) {
                // look for an automatically created conversation specific channel
                channel = findConversationChannel(r, channelId, conversationId, includeDeleted);
            }
            if (channel == null && returnParentIfNoConversationChannel) {
                // look for it just based on its id
                final NotificationChannel nc = r.channels.get(channelId);
                if (nc != null && (includeDeleted || !nc.isDeleted())) {
                    return nc;
                }
            }
            return channel;
        }
    }

    private NotificationChannel findConversationChannel(PackagePreferences p, String parentId,
            String conversationId, boolean includeDeleted) {
        for (NotificationChannel nc : p.channels.values()) {
            if (conversationId.equals(nc.getConversationId())
                    && parentId.equals(nc.getParentChannelId())
                    && (includeDeleted || !nc.isDeleted())) {
                return nc;
            }
        }
        return null;
    }

    public List<NotificationChannel> getNotificationChannelsByConversationId(String pkg, int uid,
            String conversationId) {
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(conversationId);
        List<NotificationChannel> channels = new ArrayList<>();
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return channels;
            }
            for (NotificationChannel nc : r.channels.values()) {
                if (conversationId.equals(nc.getConversationId())
                        && !nc.isDeleted()) {
                    channels.add(nc);
                }
            }
            return channels;
        }
    }

    @Override
    public boolean deleteNotificationChannel(String pkg, int uid, String channelId,
            int callingUid, boolean fromSystemOrSystemUi) {
        boolean deletedChannel = false;
        boolean channelBypassedDnd = false;
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return false;
            }
            NotificationChannel channel = r.channels.get(channelId);
            if (channel != null) {
                channelBypassedDnd = channel.canBypassDnd();
                deletedChannel = deleteNotificationChannelLocked(channel, pkg, uid);
            }
        }
        if (channelBypassedDnd) {
            updateCurrentUserHasChannelsBypassingDnd(callingUid, fromSystemOrSystemUi);
        }
        return deletedChannel;
    }

    private boolean deleteNotificationChannelLocked(NotificationChannel channel, String pkg,
            int uid) {
        if (!channel.isDeleted()) {
            channel.setDeleted(true);
            channel.setDeletedTimeMs(System.currentTimeMillis());
            LogMaker lm = getChannelLog(channel, pkg);
            lm.setType(com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_CLOSE);
            MetricsLogger.action(lm);
            mNotificationChannelLogger.logNotificationChannelDeleted(channel, uid, pkg);
            return true;
        }
        return false;
    }

    @Override
    @VisibleForTesting
    public void permanentlyDeleteNotificationChannel(String pkg, int uid, String channelId) {
        Objects.requireNonNull(pkg);
        Objects.requireNonNull(channelId);
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return;
            }
            r.channels.remove(channelId);
        }
    }

    @Override
    public void permanentlyDeleteNotificationChannels(String pkg, int uid) {
        Objects.requireNonNull(pkg);
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return;
            }
            int N = r.channels.size() - 1;
            for (int i = N; i >= 0; i--) {
                String key = r.channels.keyAt(i);
                if (!NotificationChannel.DEFAULT_CHANNEL_ID.equals(key)) {
                    r.channels.remove(key);
                }
            }
        }
    }

    public boolean shouldHideSilentStatusIcons() {
        return mHideSilentStatusBarIcons;
    }

    public void setHideSilentStatusIcons(boolean hide) {
        mHideSilentStatusBarIcons = hide;
    }

    public void updateFixedImportance(List<UserInfo> users) {
        for (UserInfo user : users) {
            List<PackageInfo> packages = mPm.getInstalledPackagesAsUser(
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY),
                    user.getUserHandle().getIdentifier());
            for (PackageInfo pi : packages) {
                boolean fixed = mPermissionHelper.isPermissionFixed(
                        pi.packageName, user.getUserHandle().getIdentifier());
                if (fixed) {
                    synchronized (mPackagePreferences) {
                        PackagePreferences p = getOrCreatePackagePreferencesLocked(
                                pi.packageName, pi.applicationInfo.uid);
                        p.fixedImportance = true;
                        for (NotificationChannel channel : p.channels.values()) {
                            channel.setImportanceLockedByCriticalDeviceFunction(true);
                        }
                    }
                }
            }
        }
    }

    public void updateDefaultApps(int userId, ArraySet<String> toRemove,
            ArraySet<Pair<String, Integer>> toAdd) {
        synchronized (mPackagePreferences) {
            for (PackagePreferences p : mPackagePreferences.values()) {
                if (userId == UserHandle.getUserId(p.uid)) {
                    if (toRemove != null && toRemove.contains(p.pkg)) {
                        p.defaultAppLockedImportance = false;
                        if (!p.fixedImportance) {
                            for (NotificationChannel channel : p.channels.values()) {
                                channel.setImportanceLockedByCriticalDeviceFunction(false);
                            }
                        }
                    }
                }
            }
            if (toAdd != null) {
                for (Pair<String, Integer> approvedApp : toAdd) {
                    PackagePreferences p = getOrCreatePackagePreferencesLocked(
                            approvedApp.first,
                            approvedApp.second);
                    p.defaultAppLockedImportance = true;
                    for (NotificationChannel channel : p.channels.values()) {
                        channel.setImportanceLockedByCriticalDeviceFunction(true);
                    }
                }
            }
        }
    }

    public NotificationChannelGroup getNotificationChannelGroupWithChannels(String pkg,
            int uid, String groupId, boolean includeDeleted) {
        Objects.requireNonNull(pkg);
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null || groupId == null || !r.groups.containsKey(groupId)) {
                return null;
            }
            NotificationChannelGroup group = r.groups.get(groupId).clone();
            group.setChannels(new ArrayList<>());
            int N = r.channels.size();
            for (int i = 0; i < N; i++) {
                final NotificationChannel nc = r.channels.valueAt(i);
                if (includeDeleted || !nc.isDeleted()) {
                    if (groupId.equals(nc.getGroup())) {
                        group.addChannel(nc);
                    }
                }
            }
            return group;
        }
    }

    public NotificationChannelGroup getNotificationChannelGroup(String groupId, String pkg,
            int uid) {
        Objects.requireNonNull(pkg);
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return null;
            }
            return r.groups.get(groupId);
        }
    }

    public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroups(String pkg,
            int uid, boolean includeDeleted, boolean includeNonGrouped, boolean includeEmpty,
            boolean includeBlocked, Set<String> activeChannelFilter) {
        Objects.requireNonNull(pkg);
        Map<String, NotificationChannelGroup> groups = new ArrayMap<>();
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return ParceledListSlice.emptyList();
            }
            NotificationChannelGroup nonGrouped = new NotificationChannelGroup(null, null);
            int N = r.channels.size();
            for (int i = 0; i < N; i++) {
                final NotificationChannel nc = r.channels.valueAt(i);
                boolean includeChannel = (includeDeleted || !nc.isDeleted())
                        && (activeChannelFilter == null
                                || (includeBlocked && nc.getImportance() == IMPORTANCE_NONE)
                                || activeChannelFilter.contains(nc.getId()));
                if (includeChannel) {
                    if (nc.getGroup() != null) {
                        if (r.groups.get(nc.getGroup()) != null) {
                            NotificationChannelGroup ncg = groups.get(nc.getGroup());
                            if (ncg == null) {
                                ncg = r.groups.get(nc.getGroup()).clone();
                                ncg.setChannels(new ArrayList<>());
                                groups.put(nc.getGroup(), ncg);
                            }
                            ncg.addChannel(nc);
                        }
                    } else {
                        nonGrouped.addChannel(nc);
                    }
                }
            }
            if (includeNonGrouped && nonGrouped.getChannels().size() > 0) {
                groups.put(null, nonGrouped);
            }
            if (includeEmpty) {
                for (NotificationChannelGroup group : r.groups.values()) {
                    if (!groups.containsKey(group.getId())) {
                        groups.put(group.getId(), group);
                    }
                }
            }
            return new ParceledListSlice<>(new ArrayList<>(groups.values()));
        }
    }

    public List<NotificationChannel> deleteNotificationChannelGroup(String pkg, int uid,
            String groupId, int callingUid, boolean fromSystemOrSystemUi) {
        List<NotificationChannel> deletedChannels = new ArrayList<>();
        boolean groupBypassedDnd = false;
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null || TextUtils.isEmpty(groupId)) {
                return deletedChannels;
            }

            NotificationChannelGroup channelGroup = r.groups.remove(groupId);
            if (channelGroup != null) {
                mNotificationChannelLogger.logNotificationChannelGroupDeleted(channelGroup, uid,
                        pkg);
            }

            int N = r.channels.size();
            for (int i = 0; i < N; i++) {
                final NotificationChannel nc = r.channels.valueAt(i);
                if (groupId.equals(nc.getGroup())) {
                    groupBypassedDnd |= nc.canBypassDnd();
                    deleteNotificationChannelLocked(nc, pkg, uid);
                    deletedChannels.add(nc);
                }
            }
        }
        if (groupBypassedDnd) {
            updateCurrentUserHasChannelsBypassingDnd(callingUid, fromSystemOrSystemUi);
        }
        return deletedChannels;
    }

    @Override
    public Collection<NotificationChannelGroup> getNotificationChannelGroups(String pkg,
            int uid) {
        List<NotificationChannelGroup> groups = new ArrayList<>();
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return groups;
            }
            groups.addAll(r.groups.values());
        }
        return groups;
    }

    public NotificationChannelGroup getGroupForChannel(String pkg, int uid, String channelId) {
        synchronized (mPackagePreferences) {
            PackagePreferences p = getPackagePreferencesLocked(pkg, uid);
            if (p != null) {
                NotificationChannel nc = p.channels.get(channelId);
                if (nc != null && !nc.isDeleted()) {
                    if (nc.getGroup() != null) {
                        NotificationChannelGroup group = p.groups.get(nc.getGroup());
                        if (group != null) {
                            return group;
                        }
                    }
                }
            }
        }
        return null;
    }

    public ArrayList<ConversationChannelWrapper> getConversations(IntArray userIds,
            boolean onlyImportant) {
        synchronized (mPackagePreferences) {
            ArrayList<ConversationChannelWrapper> conversations = new ArrayList<>();
            for (PackagePreferences p : mPackagePreferences.values()) {
                if (userIds.binarySearch(UserHandle.getUserId(p.uid)) >= 0) {
                    int N = p.channels.size();
                    for (int i = 0; i < N; i++) {
                        final NotificationChannel nc = p.channels.valueAt(i);
                        if (!TextUtils.isEmpty(nc.getConversationId()) && !nc.isDeleted()
                                && !nc.isDemoted()
                                && (nc.isImportantConversation() || !onlyImportant)) {
                            ConversationChannelWrapper conversation =
                                    new ConversationChannelWrapper();
                            conversation.setPkg(p.pkg);
                            conversation.setUid(p.uid);
                            conversation.setNotificationChannel(nc);
                            NotificationChannel parent = p.channels.get(nc.getParentChannelId());
                            conversation.setParentChannelLabel(parent == null
                                    ? null
                                    : parent.getName());
                            boolean blockedByGroup = false;
                            if (nc.getGroup() != null) {
                                NotificationChannelGroup group = p.groups.get(nc.getGroup());
                                if (group != null) {
                                    if (group.isBlocked()) {
                                        blockedByGroup = true;
                                    } else {
                                        conversation.setGroupLabel(group.getName());
                                    }
                                }
                            }
                            if (!blockedByGroup) {
                                conversations.add(conversation);
                            }
                        }
                    }
                }
            }

            return conversations;
        }
    }

    public ArrayList<ConversationChannelWrapper> getConversations(String pkg, int uid) {
        Objects.requireNonNull(pkg);
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return new ArrayList<>();
            }
            ArrayList<ConversationChannelWrapper> conversations = new ArrayList<>();
            int N = r.channels.size();
            for (int i = 0; i < N; i++) {
                final NotificationChannel nc = r.channels.valueAt(i);
                if (!TextUtils.isEmpty(nc.getConversationId())
                        && !nc.isDeleted()
                        && !nc.isDemoted()) {
                    ConversationChannelWrapper conversation = new ConversationChannelWrapper();
                    conversation.setPkg(r.pkg);
                    conversation.setUid(r.uid);
                    conversation.setNotificationChannel(nc);
                    conversation.setParentChannelLabel(
                            r.channels.get(nc.getParentChannelId()).getName());
                    boolean blockedByGroup = false;
                    if (nc.getGroup() != null) {
                        NotificationChannelGroup group = r.groups.get(nc.getGroup());
                        if (group != null) {
                            if (group.isBlocked()) {
                                blockedByGroup = true;
                            } else {
                                conversation.setGroupLabel(group.getName());
                            }
                        }
                    }
                    if (!blockedByGroup) {
                        conversations.add(conversation);
                    }
                }
            }

            return conversations;
        }
    }

    public @NonNull List<String> deleteConversations(String pkg, int uid,
            Set<String> conversationIds, int callingUid, boolean fromSystemOrSystemUi) {
        List<String> deletedChannelIds = new ArrayList<>();
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return deletedChannelIds;
            }
            int N = r.channels.size();
            for (int i = 0; i < N; i++) {
                final NotificationChannel nc = r.channels.valueAt(i);
                if (nc.getConversationId() != null
                        && conversationIds.contains(nc.getConversationId())) {
                    nc.setDeleted(true);
                    nc.setDeletedTimeMs(System.currentTimeMillis());
                    LogMaker lm = getChannelLog(nc, pkg);
                    lm.setType(
                            com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_CLOSE);
                    MetricsLogger.action(lm);
                    mNotificationChannelLogger.logNotificationChannelDeleted(nc, uid, pkg);

                    deletedChannelIds.add(nc.getId());
                }
            }
        }
        if (!deletedChannelIds.isEmpty() && mCurrentUserHasChannelsBypassingDnd) {
            updateCurrentUserHasChannelsBypassingDnd(callingUid, fromSystemOrSystemUi);
        }
        return deletedChannelIds;
    }

    @Override
    public ParceledListSlice<NotificationChannel> getNotificationChannels(String pkg, int uid,
            boolean includeDeleted) {
        Objects.requireNonNull(pkg);
        List<NotificationChannel> channels = new ArrayList<>();
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return ParceledListSlice.emptyList();
            }
            int N = r.channels.size();
            for (int i = 0; i < N; i++) {
                final NotificationChannel nc = r.channels.valueAt(i);
                if (includeDeleted || !nc.isDeleted()) {
                    channels.add(nc);
                }
            }
            return new ParceledListSlice<>(channels);
        }
    }

    /**
     * Gets all notification channels associated with the given pkg and uid that can bypass dnd
     */
    public ParceledListSlice<NotificationChannel> getNotificationChannelsBypassingDnd(String pkg,
            int uid) {
        List<NotificationChannel> channels = new ArrayList<>();
        synchronized (mPackagePreferences) {
            final PackagePreferences r = mPackagePreferences.get(
                    packagePreferencesKey(pkg, uid));
            if (r != null) {
                for (NotificationChannel channel : r.channels.values()) {
                    if (channelIsLiveLocked(r, channel) && channel.canBypassDnd()) {
                        channels.add(channel);
                    }
                }
            }
        }
        return new ParceledListSlice<>(channels);
    }

    /**
     * True for pre-O apps that only have the default channel, or pre O apps that have no
     * channels yet. This method will create the default channel for pre-O apps that don't have it.
     * Should never be true for O+ targeting apps, but that's enforced on boot/when an app
     * upgrades.
     */
    public boolean onlyHasDefaultChannel(String pkg, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
            if (r.channels.size() == 1
                    && r.channels.containsKey(NotificationChannel.DEFAULT_CHANNEL_ID)) {
                return true;
            }
            return false;
        }
    }

    public int getDeletedChannelCount(String pkg, int uid) {
        Objects.requireNonNull(pkg);
        int deletedCount = 0;
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return deletedCount;
            }
            int N = r.channels.size();
            for (int i = 0; i < N; i++) {
                final NotificationChannel nc = r.channels.valueAt(i);
                if (nc.isDeleted()) {
                    deletedCount++;
                }
            }
            return deletedCount;
        }
    }

    public int getBlockedChannelCount(String pkg, int uid) {
        Objects.requireNonNull(pkg);
        int blockedCount = 0;
        synchronized (mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return blockedCount;
            }
            int N = r.channels.size();
            for (int i = 0; i < N; i++) {
                final NotificationChannel nc = r.channels.valueAt(i);
                if (!nc.isDeleted() && IMPORTANCE_NONE == nc.getImportance()) {
                    blockedCount++;
                }
            }
            return blockedCount;
        }
    }

    /**
     * Syncs {@link #mCurrentUserHasChannelsBypassingDnd} with the current user's notification
     * policy before updating. Must be called:
     * <ul>
     *     <li>On system init, after channels and DND configurations are loaded.
     *     <li>When the current user is switched, after the corresponding DND config is loaded.
     *     <li>If users are removed (the removed user could've been a profile of the current one).
     * </ul>
     */
    void syncChannelsBypassingDnd() {
        mCurrentUserHasChannelsBypassingDnd = (mZenModeHelper.getNotificationPolicy().state
                & NotificationManager.Policy.STATE_CHANNELS_BYPASSING_DND) != 0;

        updateCurrentUserHasChannelsBypassingDnd(/* callingUid= */ Process.SYSTEM_UID,
                /* fromSystemOrSystemUi= */ true);
    }

    /**
     * Updates the user's NotificationPolicy based on whether the current userId has channels
     * bypassing DND. It should be called whenever a channel is created, updated, or deleted, or
     * when the current user (or its profiles) change.
     */
    private void updateCurrentUserHasChannelsBypassingDnd(int callingUid,
            boolean fromSystemOrSystemUi) {
        ArraySet<Pair<String, Integer>> candidatePkgs = new ArraySet<>();

        final IntArray currentUserIds = mUserProfiles.getCurrentProfileIds();
        synchronized (mPackagePreferences) {
            final int numPackagePreferences = mPackagePreferences.size();
            for (int i = 0; i < numPackagePreferences; i++) {
                final PackagePreferences r = mPackagePreferences.valueAt(i);
                if (!currentUserIds.contains(UserHandle.getUserId(r.uid))) {
                    continue; // Package isn't associated with any profile of the current userId.
                }

                for (NotificationChannel channel : r.channels.values()) {
                    if (channelIsLiveLocked(r, channel) && channel.canBypassDnd()) {
                        candidatePkgs.add(new Pair<>(r.pkg, r.uid));
                        break;
                    }
                }
            }
        }
        for (int i = candidatePkgs.size() - 1; i >= 0; i--) {
            Pair<String, Integer> app = candidatePkgs.valueAt(i);
            if (!mPermissionHelper.hasPermission(app.second)) {
                candidatePkgs.removeAt(i);
            }
        }
        boolean haveBypassingApps = candidatePkgs.size() > 0;
        if (mCurrentUserHasChannelsBypassingDnd != haveBypassingApps) {
            mCurrentUserHasChannelsBypassingDnd = haveBypassingApps;
            updateZenPolicy(mCurrentUserHasChannelsBypassingDnd, callingUid, fromSystemOrSystemUi);
        }
    }

    private boolean channelIsLiveLocked(PackagePreferences pkgPref, NotificationChannel channel) {
        // Channel is in a group that's blocked
        if (isGroupBlocked(pkgPref.pkg, pkgPref.uid, channel.getGroup())) {
            return false;
        }

        // Channel is deleted or is blocked
        if (channel.isDeleted() || channel.getImportance() == IMPORTANCE_NONE) {
            return false;
        }

        return true;
    }

    public void updateZenPolicy(boolean areChannelsBypassingDnd, int callingUid,
            boolean fromSystemOrSystemUi) {
        NotificationManager.Policy policy = mZenModeHelper.getNotificationPolicy();
        mZenModeHelper.setNotificationPolicy(
                new NotificationManager.Policy(
                        policy.priorityCategories, policy.priorityCallSenders,
                        policy.priorityMessageSenders, policy.suppressedVisualEffects,
                        (areChannelsBypassingDnd
                                ? NotificationManager.Policy.STATE_CHANNELS_BYPASSING_DND : 0),
                        policy.priorityConversationSenders),
                fromSystemOrSystemUi ? ZenModeConfig.UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI
                        : ZenModeConfig.UPDATE_ORIGIN_APP,
                callingUid);
    }

    // TODO: b/310620812 - rename to hasPriorityChannels() when modes_api is inlined.
    public boolean areChannelsBypassingDnd() {
        return mCurrentUserHasChannelsBypassingDnd;
    }

    /**
     * Sets whether any notifications from the app, represented by the given {@code pkgName} and
     * {@code uid}, have their importance locked by the user. Locked notifications don't get
     * considered for sentiment adjustments (and thus never show a blocking helper).
     */
    public void setAppImportanceLocked(String packageName, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences prefs = getOrCreatePackagePreferencesLocked(packageName, uid);
            if ((prefs.lockedAppFields & LockableAppFields.USER_LOCKED_IMPORTANCE) != 0) {
                return;
            }

            prefs.lockedAppFields =
                    prefs.lockedAppFields | LockableAppFields.USER_LOCKED_IMPORTANCE;
        }
        updateConfig();
    }

    /**
     * Returns the delegate for a given package, if it's allowed by the package and the user.
     */
    public @Nullable String getNotificationDelegate(String sourcePkg, int sourceUid) {
        synchronized (mPackagePreferences) {
            PackagePreferences prefs = getPackagePreferencesLocked(sourcePkg, sourceUid);

            if (prefs == null || prefs.delegate == null) {
                return null;
            }
            if (!prefs.delegate.mEnabled) {
                return null;
            }
            return prefs.delegate.mPkg;
        }
    }

    /**
     * Used by an app to delegate notification posting privileges to another apps.
     */
    public void setNotificationDelegate(String sourcePkg, int sourceUid,
            String delegatePkg, int delegateUid) {
        synchronized (mPackagePreferences) {
            PackagePreferences prefs = getOrCreatePackagePreferencesLocked(sourcePkg, sourceUid);
            prefs.delegate = new Delegate(delegatePkg, delegateUid, true);
        }
    }

    /**
     * Used by an app to turn off its notification delegate.
     */
    public void revokeNotificationDelegate(String sourcePkg, int sourceUid) {
        synchronized (mPackagePreferences) {
            PackagePreferences prefs = getPackagePreferencesLocked(sourcePkg, sourceUid);
            if (prefs != null && prefs.delegate != null) {
                prefs.delegate.mEnabled = false;
            }
        }
    }

    /**
     * Returns whether the given app is allowed on post notifications on behalf of the other given
     * app.
     */
    public boolean isDelegateAllowed(String sourcePkg, int sourceUid,
            String potentialDelegatePkg, int potentialDelegateUid) {
        synchronized (mPackagePreferences) {
            PackagePreferences prefs = getPackagePreferencesLocked(sourcePkg, sourceUid);

            return prefs != null && prefs.isValidDelegate(potentialDelegatePkg,
                    potentialDelegateUid);
        }
    }

    private void lockFieldsForUpdateLocked(NotificationChannel original,
            NotificationChannel update) {
        if (original.canBypassDnd() != update.canBypassDnd()) {
            update.lockFields(NotificationChannel.USER_LOCKED_PRIORITY);
        }
        if (original.getLockscreenVisibility() != update.getLockscreenVisibility()) {
            update.lockFields(NotificationChannel.USER_LOCKED_VISIBILITY);
        }
        if (original.getImportance() != update.getImportance()) {
            update.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
        }
        if (original.shouldShowLights() != update.shouldShowLights()
                || original.getLightColor() != update.getLightColor()) {
            update.lockFields(NotificationChannel.USER_LOCKED_LIGHTS);
        }
        if (!Objects.equals(original.getSound(), update.getSound())) {
            update.lockFields(NotificationChannel.USER_LOCKED_SOUND);
        }
        if (!Arrays.equals(original.getVibrationPattern(), update.getVibrationPattern())
                || !Objects.equals(original.getVibrationEffect(), update.getVibrationEffect())
                || original.shouldVibrate() != update.shouldVibrate()) {
            update.lockFields(NotificationChannel.USER_LOCKED_VIBRATION);
        }
        if (original.canShowBadge() != update.canShowBadge()) {
            update.lockFields(NotificationChannel.USER_LOCKED_SHOW_BADGE);
        }
        if (original.getAllowBubbles() != update.getAllowBubbles()) {
            update.lockFields(NotificationChannel.USER_LOCKED_ALLOW_BUBBLE);
        }
    }

    public void dump(PrintWriter pw, String prefix,
            @NonNull NotificationManagerService.DumpFilter filter,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions) {
        pw.print(prefix);
        pw.println("per-package config version: " + XML_VERSION);

        pw.println("PackagePreferences:");
        synchronized (mPackagePreferences) {
            dumpPackagePreferencesLocked(pw, prefix, filter, mPackagePreferences, pkgPermissions);
        }
        pw.println("Restored without uid:");
        dumpPackagePreferencesLocked(pw, prefix, filter, mRestoredWithoutUids, null);
    }

    public void dump(ProtoOutputStream proto,
            @NonNull NotificationManagerService.DumpFilter filter,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions) {
        synchronized (mPackagePreferences) {
            dumpPackagePreferencesLocked(proto, RankingHelperProto.RECORDS, filter,
                    mPackagePreferences, pkgPermissions);
        }
        dumpPackagePreferencesLocked(proto, RankingHelperProto.RECORDS_RESTORED_WITHOUT_UID, filter,
                mRestoredWithoutUids, null);
    }

    private void dumpPackagePreferencesLocked(PrintWriter pw, String prefix,
            @NonNull NotificationManagerService.DumpFilter filter,
            ArrayMap<String, PackagePreferences> packagePreferences,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> packagePermissions) {
        // Used for tracking which package preferences we've seen already for notification
        // permission reasons; after handling packages with local preferences, we'll want to dump
        // the ones with notification permissions set but not local prefs.
        Set<Pair<Integer, String>> pkgsWithPermissionsToHandle = null;
        if (packagePermissions != null) {
            pkgsWithPermissionsToHandle = packagePermissions.keySet();
        }
        final int N = packagePreferences.size();
        for (int i = 0; i < N; i++) {
            final PackagePreferences r = packagePreferences.valueAt(i);
            if (filter.matches(r.pkg)) {
                pw.print(prefix);
                pw.print("  AppSettings: ");
                pw.print(r.pkg);
                pw.print(" (");
                pw.print(r.uid == UNKNOWN_UID ? "UNKNOWN_UID" : Integer.toString(r.uid));
                pw.print(')');
                Pair<Integer, String> key = new Pair<>(r.uid, r.pkg);
                if (packagePermissions != null && pkgsWithPermissionsToHandle.contains(key)) {
                    pw.print(" importance=");
                    pw.print(NotificationListenerService.Ranking.importanceToString(
                            packagePermissions.get(key).first
                                    ? IMPORTANCE_DEFAULT : IMPORTANCE_NONE));
                    pw.print(" userSet=");
                    pw.print(packagePermissions.get(key).second);
                    pkgsWithPermissionsToHandle.remove(key);
                }
                if (r.priority != DEFAULT_PRIORITY) {
                    pw.print(" priority=");
                    pw.print(Notification.priorityToString(r.priority));
                }
                if (r.visibility != DEFAULT_VISIBILITY) {
                    pw.print(" visibility=");
                    pw.print(Notification.visibilityToString(r.visibility));
                }
                if (r.showBadge != DEFAULT_SHOW_BADGE) {
                    pw.print(" showBadge=");
                    pw.print(r.showBadge);
                }
                if (r.defaultAppLockedImportance != DEFAULT_APP_LOCKED_IMPORTANCE) {
                    pw.print(" defaultAppLocked=");
                    pw.print(r.defaultAppLockedImportance);
                }
                if (r.fixedImportance != DEFAULT_APP_LOCKED_IMPORTANCE) {
                    pw.print(" fixedImportance=");
                    pw.print(r.fixedImportance);
                }
                pw.println();
                for (NotificationChannel channel : r.channels.values()) {
                    pw.print(prefix);
                    channel.dump(pw, "    ", filter.redact);
                }
                for (NotificationChannelGroup group : r.groups.values()) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.print("  ");
                    pw.println(group);
                }
            }
        }
        // Handle any remaining packages with permissions
        if (pkgsWithPermissionsToHandle != null) {
            for (Pair<Integer, String> p : pkgsWithPermissionsToHandle) {
                // p.first is the uid of this package; p.second is the package name
                if (filter.matches(p.second)) {
                    pw.print(prefix);
                    pw.print("  AppSettings: ");
                    pw.print(p.second);
                    pw.print(" (");
                    pw.print(p.first == UNKNOWN_UID ? "UNKNOWN_UID" : Integer.toString(p.first));
                    pw.print(')');
                    pw.print(" importance=");
                    pw.print(NotificationListenerService.Ranking.importanceToString(
                            packagePermissions.get(p).first
                                    ? IMPORTANCE_DEFAULT : IMPORTANCE_NONE));
                    pw.print(" userSet=");
                    pw.print(packagePermissions.get(p).second);
                    pw.println();
                }
            }
        }
    }

    private void dumpPackagePreferencesLocked(ProtoOutputStream proto, long fieldId,
            @NonNull NotificationManagerService.DumpFilter filter,
            ArrayMap<String, PackagePreferences> packagePreferences,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> packagePermissions) {
        Set<Pair<Integer, String>> pkgsWithPermissionsToHandle = null;
        if (packagePermissions != null) {
            pkgsWithPermissionsToHandle = packagePermissions.keySet();
        }

        final int N = packagePreferences.size();
        long fToken;
        for (int i = 0; i < N; i++) {
            final PackagePreferences r = packagePreferences.valueAt(i);
            if (filter.matches(r.pkg)) {
                fToken = proto.start(fieldId);

                proto.write(RankingHelperProto.RecordProto.PACKAGE, r.pkg);
                proto.write(RankingHelperProto.RecordProto.UID, r.uid);
                Pair<Integer, String> key = new Pair<>(r.uid, r.pkg);
                if (packagePermissions != null && pkgsWithPermissionsToHandle.contains(key)) {
                    proto.write(RankingHelperProto.RecordProto.IMPORTANCE,
                            packagePermissions.get(key).first
                                    ? IMPORTANCE_DEFAULT : IMPORTANCE_NONE);
                    pkgsWithPermissionsToHandle.remove(key);
                }
                proto.write(RankingHelperProto.RecordProto.PRIORITY, r.priority);
                proto.write(RankingHelperProto.RecordProto.VISIBILITY, r.visibility);
                proto.write(RankingHelperProto.RecordProto.SHOW_BADGE, r.showBadge);

                for (NotificationChannel channel : r.channels.values()) {
                    channel.dumpDebug(proto, RankingHelperProto.RecordProto.CHANNELS);
                }
                for (NotificationChannelGroup group : r.groups.values()) {
                    group.dumpDebug(proto, RankingHelperProto.RecordProto.CHANNEL_GROUPS);
                }

                proto.end(fToken);
            }
        }

        if (pkgsWithPermissionsToHandle != null) {
            for (Pair<Integer, String> p : pkgsWithPermissionsToHandle) {
                if (filter.matches(p.second)) {
                    fToken = proto.start(fieldId);
                    proto.write(RankingHelperProto.RecordProto.PACKAGE, p.second);
                    proto.write(RankingHelperProto.RecordProto.UID, p.first);
                    proto.write(RankingHelperProto.RecordProto.IMPORTANCE,
                            packagePermissions.get(p).first
                                    ? IMPORTANCE_DEFAULT : IMPORTANCE_NONE);
                    proto.end(fToken);
                }
            }
        }
    }

    /**
     * @return State of the full screen intent permission for this package.
     */
    @VisibleForTesting
    int getFsiState(String pkg, int uid, boolean requestedFSIPermission) {
        if (!requestedFSIPermission) {
            return PACKAGE_NOTIFICATION_PREFERENCES__FSI_STATE__NOT_REQUESTED;
        }
        final AttributionSource attributionSource =
                new AttributionSource.Builder(uid).setPackageName(pkg).build();

        final int result = mPermissionManager.checkPermissionForPreflight(
                android.Manifest.permission.USE_FULL_SCREEN_INTENT, attributionSource);

        if (result == PermissionManager.PERMISSION_GRANTED) {
            return PACKAGE_NOTIFICATION_PREFERENCES__FSI_STATE__GRANTED;
        }
        return PACKAGE_NOTIFICATION_PREFERENCES__FSI_STATE__DENIED;
    }

    /**
     * @return True if the current full screen intent permission state for this package was set by
     * the user.
     */
    @VisibleForTesting
    boolean isFsiPermissionUserSet(String pkg, int uid, int fsiState, int currentPermissionFlags) {
        if (fsiState == PACKAGE_NOTIFICATION_PREFERENCES__FSI_STATE__NOT_REQUESTED) {
            return false;
        }
        return (currentPermissionFlags & PackageManager.FLAG_PERMISSION_USER_SET) != 0;
    }

    /**
     * Fills out {@link PackageNotificationPreferences} proto and wraps it in a {@link StatsEvent}.
     */
    public void pullPackagePreferencesStats(List<StatsEvent> events,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions) {
        Set<Pair<Integer, String>> pkgsWithPermissionsToHandle = null;
        if (pkgPermissions != null) {
            pkgsWithPermissionsToHandle = pkgPermissions.keySet();
        }
        int pulledEvents = 0;
        synchronized (mPackagePreferences) {
            for (int i = 0; i < mPackagePreferences.size(); i++) {
                if (pulledEvents > NOTIFICATION_PREFERENCES_PULL_LIMIT) {
                    break;
                }
                pulledEvents++;
                final PackagePreferences r = mPackagePreferences.valueAt(i);

                // collect whether this package's importance info was user-set for later, if needed
                // before the migration is enabled, this will simply default to false in all cases.
                boolean importanceIsUserSet = false;
                // Even if this package's data is not present, we need to write something;
                // default to IMPORTANCE_UNSPECIFIED. If PM doesn't know about the package
                // for some reason, notifications are not allowed, but in logged output we want
                // to distinguish this case from the actually-banned packages.
                int importance = IMPORTANCE_UNSPECIFIED;
                Pair<Integer, String> key = new Pair<>(r.uid, r.pkg);
                if (pkgPermissions != null && pkgsWithPermissionsToHandle.contains(key)) {
                    Pair<Boolean, Boolean> permissionPair = pkgPermissions.get(key);
                    importance = permissionPair.first
                            ? IMPORTANCE_DEFAULT : IMPORTANCE_NONE;
                    // cache the second value for writing later
                    importanceIsUserSet = permissionPair.second;

                    pkgsWithPermissionsToHandle.remove(key);
                }

                final boolean requestedFSIPermission = mPermissionHelper.hasRequestedPermission(
                        android.Manifest.permission.USE_FULL_SCREEN_INTENT, r.pkg, r.uid);

                final int fsiState = getFsiState(r.pkg, r.uid, requestedFSIPermission);

                final int currentPermissionFlags = mPm.getPermissionFlags(
                        android.Manifest.permission.USE_FULL_SCREEN_INTENT, r.pkg,
                        UserHandle.getUserHandleForUid(r.uid));

                final boolean fsiIsUserSet =
                        isFsiPermissionUserSet(r.pkg, r.uid, fsiState,
                                currentPermissionFlags);

                events.add(FrameworkStatsLog.buildStatsEvent(
                        PACKAGE_NOTIFICATION_PREFERENCES,
                        /* optional int32 uid = 1 [(is_uid) = true] */ r.uid,
                        /* optional int32 importance = 2 */ importance,
                        /* optional int32 visibility = 3 */ r.visibility,
                        /* optional int32 user_locked_fields = 4 */ r.lockedAppFields,
                        /* optional bool user_set_importance = 5 */ importanceIsUserSet,
                        /* optional FsiState fsi_state = 6 */ fsiState,
                        /* optional bool is_fsi_permission_user_set = 7 */ fsiIsUserSet));
            }
        }

        // handle remaining packages with PackageManager permissions but not local settings
        if (pkgPermissions != null) {
            for (Pair<Integer, String> p : pkgsWithPermissionsToHandle) {
                if (pulledEvents > NOTIFICATION_PREFERENCES_PULL_LIMIT) {
                    break;
                }
                pulledEvents++;
                // Because all fields are required in FrameworkStatsLog.buildStatsEvent, we have
                // to fill in default values for all the unspecified fields.
                events.add(FrameworkStatsLog.buildStatsEvent(
                        PACKAGE_NOTIFICATION_PREFERENCES,
                        /* optional int32 uid = 1 [(is_uid) = true] */ p.first,
                        /* optional int32 importance = 2 */ pkgPermissions.get(p).first
                                ? IMPORTANCE_DEFAULT : IMPORTANCE_NONE,
                        /* optional int32 visibility = 3 */ DEFAULT_VISIBILITY,
                        /* optional int32 user_locked_fields = 4 */ DEFAULT_LOCKED_APP_FIELDS,
                        /* optional bool user_set_importance = 5 */ pkgPermissions.get(p).second,
                        /* optional FsiState fsi_state = 6 */ 0,
                        /* optional bool is_fsi_permission_user_set = 7 */ false));
            }
        }
    }

    /**
     * Fills out {@link PackageNotificationChannelPreferences} proto and wraps it in a
     * {@link StatsEvent}.
     */
    public void pullPackageChannelPreferencesStats(List<StatsEvent> events) {
        synchronized (mPackagePreferences) {
            int totalChannelsPulled = 0;
            for (int i = 0; i < mPackagePreferences.size(); i++) {
                if (totalChannelsPulled > NOTIFICATION_CHANNEL_PULL_LIMIT) {
                    break;
                }
                final PackagePreferences r = mPackagePreferences.valueAt(i);
                for (NotificationChannel channel : r.channels.values()) {
                    if (++totalChannelsPulled > NOTIFICATION_CHANNEL_PULL_LIMIT) {
                        break;
                    }
                    events.add(FrameworkStatsLog.buildStatsEvent(
                            PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES,
                            /* optional int32 uid = 1 [(is_uid) = true] */ r.uid,
                            /* optional string channel_id = 2 */ channel.getId(),
                            /* optional string channel_name = 3 */ channel.getName().toString(),
                            /* optional string description = 4 */ channel.getDescription(),
                            /* optional int32 importance = 5 */ channel.getImportance(),
                            /* optional int32 user_locked_fields = 6 */
                            channel.getUserLockedFields(),
                            /* optional bool is_deleted = 7 */ channel.isDeleted(),
                            /* optional bool is_conversation = 8 */
                            channel.getConversationId() != null,
                            /* optional bool is_demoted_conversation = 9 */ channel.isDemoted(),
                            /* optional bool is_important_conversation = 10 */
                            channel.isImportantConversation()));
                }
            }
        }
    }

    /**
     * Fills out {@link PackageNotificationChannelGroupPreferences} proto and wraps it in a
     * {@link StatsEvent}.
     */
    public void pullPackageChannelGroupPreferencesStats(List<StatsEvent> events) {
        synchronized (mPackagePreferences) {
            int totalGroupsPulled = 0;
            for (int i = 0; i < mPackagePreferences.size(); i++) {
                if (totalGroupsPulled > NOTIFICATION_CHANNEL_GROUP_PULL_LIMIT) {
                    break;
                }
                final PackagePreferences r = mPackagePreferences.valueAt(i);
                for (NotificationChannelGroup groupChannel : r.groups.values()) {
                    if (++totalGroupsPulled > NOTIFICATION_CHANNEL_GROUP_PULL_LIMIT) {
                        break;
                    }
                    events.add(FrameworkStatsLog.buildStatsEvent(
                            PACKAGE_NOTIFICATION_CHANNEL_GROUP_PREFERENCES,
                            /* optional int32 uid = 1 [(is_uid) = true] */ r.uid,
                            /* optional string group_id = 2 */ groupChannel.getId(),
                            /* optional string group_name = 3 */ groupChannel.getName().toString(),
                            /* optional string description = 4 */ groupChannel.getDescription(),
                            /* optional bool is_blocked = 5 */ groupChannel.isBlocked(),
                            /* optional int32 user_locked_fields = 6 */
                            groupChannel.getUserLockedFields()));
                }
            }
        }
    }

    public JSONObject dumpJson(NotificationManagerService.DumpFilter filter,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions) {
        JSONObject ranking = new JSONObject();
        JSONArray PackagePreferencess = new JSONArray();
        try {
            ranking.put("noUid", mRestoredWithoutUids.size());
        } catch (JSONException e) {
            // pass
        }

        // Track data that we've handled from the permissions-based list
        Set<Pair<Integer, String>> pkgsWithPermissionsToHandle = null;
        if (pkgPermissions != null) {
            pkgsWithPermissionsToHandle = pkgPermissions.keySet();
        }

        synchronized (mPackagePreferences) {
            final int N = mPackagePreferences.size();
            for (int i = 0; i < N; i++) {
                final PackagePreferences r = mPackagePreferences.valueAt(i);
                if (filter == null || filter.matches(r.pkg)) {
                    JSONObject PackagePreferences = new JSONObject();
                    try {
                        PackagePreferences.put("userId", UserHandle.getUserId(r.uid));
                        PackagePreferences.put("packageName", r.pkg);
                        Pair<Integer, String> key = new Pair<>(r.uid, r.pkg);
                        if (pkgPermissions != null
                                && pkgsWithPermissionsToHandle.contains(key)) {
                            PackagePreferences.put("importance",
                                    NotificationListenerService.Ranking.importanceToString(
                                            pkgPermissions.get(key).first
                                                    ? IMPORTANCE_DEFAULT : IMPORTANCE_NONE));
                            pkgsWithPermissionsToHandle.remove(key);
                        }
                        if (r.priority != DEFAULT_PRIORITY) {
                            PackagePreferences.put("priority",
                                    Notification.priorityToString(r.priority));
                        }
                        if (r.visibility != DEFAULT_VISIBILITY) {
                            PackagePreferences.put("visibility",
                                    Notification.visibilityToString(r.visibility));
                        }
                        if (r.showBadge != DEFAULT_SHOW_BADGE) {
                            PackagePreferences.put("showBadge", Boolean.valueOf(r.showBadge));
                        }
                        JSONArray channels = new JSONArray();
                        for (NotificationChannel channel : r.channels.values()) {
                            channels.put(channel.toJson());
                        }
                        PackagePreferences.put("channels", channels);
                        JSONArray groups = new JSONArray();
                        for (NotificationChannelGroup group : r.groups.values()) {
                            groups.put(group.toJson());
                        }
                        PackagePreferences.put("groups", groups);
                    } catch (JSONException e) {
                        // pass
                    }
                    PackagePreferencess.put(PackagePreferences);
                }
            }
        }

        // handle packages for which there are permissions but no local settings
        if (pkgsWithPermissionsToHandle != null) {
            for (Pair<Integer, String> p : pkgsWithPermissionsToHandle) {
                if (filter == null || filter.matches(p.second)) {
                    JSONObject PackagePreferences = new JSONObject();
                    try {
                        PackagePreferences.put("userId", UserHandle.getUserId(p.first));
                        PackagePreferences.put("packageName", p.second);
                        PackagePreferences.put("importance",
                                NotificationListenerService.Ranking.importanceToString(
                                        pkgPermissions.get(p).first
                                                ? IMPORTANCE_DEFAULT : IMPORTANCE_NONE));
                    } catch (JSONException e) {
                        // pass
                    }
                    PackagePreferencess.put(PackagePreferences);
                }
            }
        }

        try {
            ranking.put("PackagePreferencess", PackagePreferencess);
        } catch (JSONException e) {
            // pass
        }
        return ranking;
    }

    /**
     * Dump only the ban information as structured JSON for the stats collector.
     *
     * This is intentionally redundant with {#link dumpJson} because the old
     * scraper will expect this format.
     *
     * @param filter
     * @return
     */
    public JSONArray dumpBansJson(NotificationManagerService.DumpFilter filter,
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions) {
        JSONArray bans = new JSONArray();
        Map<Integer, String> packageBans = getPermissionBasedPackageBans(pkgPermissions);
        for (Map.Entry<Integer, String> ban : packageBans.entrySet()) {
            final int userId = UserHandle.getUserId(ban.getKey());
            final String packageName = ban.getValue();
            if (filter == null || filter.matches(packageName)) {
                JSONObject banJson = new JSONObject();
                try {
                    banJson.put("userId", userId);
                    banJson.put("packageName", packageName);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                bans.put(banJson);
            }
        }
        return bans;
    }

    public Map<Integer, String> getPackageBans() {
        synchronized (mPackagePreferences) {
            final int N = mPackagePreferences.size();
            ArrayMap<Integer, String> packageBans = new ArrayMap<>(N);
            for (int i = 0; i < N; i++) {
                final PackagePreferences r = mPackagePreferences.valueAt(i);
                if (r.importance == IMPORTANCE_NONE) {
                    packageBans.put(r.uid, r.pkg);
                }
            }

            return packageBans;
        }
    }

    // Same functionality as getPackageBans by extracting the set of packages from the provided
    // map that are disallowed from sending notifications.
    protected Map<Integer, String> getPermissionBasedPackageBans(
            ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> pkgPermissions) {
        ArrayMap<Integer, String> packageBans = new ArrayMap<>();
        if (pkgPermissions != null) {
            for (Pair<Integer, String> p : pkgPermissions.keySet()) {
                if (!pkgPermissions.get(p).first) {
                    packageBans.put(p.first, p.second);
                }
            }
        }
        return packageBans;
    }

    /**
     * Dump only the channel information as structured JSON for the stats collector.
     *
     * This is intentionally redundant with {#link dumpJson} because the old
     * scraper will expect this format.
     *
     * @param filter
     * @return
     */
    public JSONArray dumpChannelsJson(NotificationManagerService.DumpFilter filter) {
        JSONArray channels = new JSONArray();
        Map<String, Integer> packageChannels = getPackageChannels();
        for (Map.Entry<String, Integer> channelCount : packageChannels.entrySet()) {
            final String packageName = channelCount.getKey();
            if (filter == null || filter.matches(packageName)) {
                JSONObject channelCountJson = new JSONObject();
                try {
                    channelCountJson.put("packageName", packageName);
                    channelCountJson.put("channelCount", channelCount.getValue());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                channels.put(channelCountJson);
            }
        }
        return channels;
    }

    private Map<String, Integer> getPackageChannels() {
        ArrayMap<String, Integer> packageChannels = new ArrayMap<>();
        synchronized (mPackagePreferences) {
            for (int i = 0; i < mPackagePreferences.size(); i++) {
                final PackagePreferences r = mPackagePreferences.valueAt(i);
                int channelCount = 0;
                for (int j = 0; j < r.channels.size(); j++) {
                    if (!r.channels.valueAt(j).isDeleted()) {
                        channelCount++;
                    }
                }
                packageChannels.put(r.pkg, channelCount);
            }
        }
        return packageChannels;
    }

    public void onUserRemoved(int userId) {
        synchronized (mPackagePreferences) {
            int N = mPackagePreferences.size();
            for (int i = N - 1; i >= 0; i--) {
                PackagePreferences PackagePreferences = mPackagePreferences.valueAt(i);
                if (UserHandle.getUserId(PackagePreferences.uid) == userId) {
                    mPackagePreferences.removeAt(i);
                }
            }
        }
    }

    protected void onLocaleChanged(Context context, int userId) {
        synchronized (mPackagePreferences) {
            int N = mPackagePreferences.size();
            for (int i = 0; i < N; i++) {
                PackagePreferences PackagePreferences = mPackagePreferences.valueAt(i);
                if (UserHandle.getUserId(PackagePreferences.uid) == userId) {
                    if (PackagePreferences.channels.containsKey(
                            NotificationChannel.DEFAULT_CHANNEL_ID)) {
                        PackagePreferences.channels.get(
                                NotificationChannel.DEFAULT_CHANNEL_ID).setName(
                                context.getResources().getString(
                                        R.string.default_notification_channel_label));
                    }
                }
            }
        }
    }

    public boolean onPackagesChanged(boolean removingPackage, int changeUserId, String[] pkgList,
            int[] uidList) {
        if (pkgList == null || pkgList.length == 0) {
            return false; // nothing to do
        }
        boolean updated = false;
        if (removingPackage) {
            // Remove notification settings for uninstalled package
            int size = Math.min(pkgList.length, uidList.length);
            for (int i = 0; i < size; i++) {
                final String pkg = pkgList[i];
                final int uid = uidList[i];
                synchronized (mPackagePreferences) {
                    mPackagePreferences.remove(packagePreferencesKey(pkg, uid));
                }
                mRestoredWithoutUids.remove(unrestoredPackageKey(pkg, changeUserId));
                updated = true;
            }
        } else {
            for (String pkg : pkgList) {
                // Package install
                final PackagePreferences r =
                        mRestoredWithoutUids.get(unrestoredPackageKey(pkg, changeUserId));
                if (r != null) {
                    try {
                        r.uid = mPm.getPackageUidAsUser(r.pkg, changeUserId);
                        mRestoredWithoutUids.remove(unrestoredPackageKey(pkg, changeUserId));
                        synchronized (mPackagePreferences) {
                            mPackagePreferences.put(packagePreferencesKey(r.pkg, r.uid), r);

                            // Try to restore any unrestored sound resources
                            for (NotificationChannel channel : r.channels.values()) {
                                if (!channel.isSoundRestored()) {
                                    Uri uri = channel.getSound();
                                    Uri restoredUri =
                                            channel.restoreSoundUri(
                                                    mContext,
                                                    uri,
                                                    true,
                                                    channel.getAudioAttributes().getUsage());
                                    if (Settings.System.DEFAULT_NOTIFICATION_URI.equals(
                                            restoredUri)) {
                                        Log.w(TAG,
                                                "Could not restore sound: " + uri + " for channel: "
                                                        + channel);
                                    }
                                    channel.setSound(restoredUri, channel.getAudioAttributes());
                                }
                            }
                        }
                        if (r.migrateToPm) {
                            try {
                                PackagePermission p = new PackagePermission(
                                        r.pkg, UserHandle.getUserId(r.uid),
                                        r.importance != IMPORTANCE_NONE,
                                        hasUserConfiguredSettings(r));
                                mPermissionHelper.setNotificationPermission(p);
                            } catch (Exception e) {
                                Slog.e(TAG, "could not migrate setting for " + r.pkg, e);
                            }
                        }
                        updated = true;
                    } catch (Exception e) {
                        Slog.e(TAG, "could not restore " + r.pkg, e);
                    }
                }
                // Package upgrade
                try {
                    synchronized (mPackagePreferences) {
                        PackagePreferences fullPackagePreferences = getPackagePreferencesLocked(pkg,
                                mPm.getPackageUidAsUser(pkg, changeUserId));
                        if (fullPackagePreferences != null) {
                            updated |= createDefaultChannelIfNeededLocked(fullPackagePreferences);
                            updated |= deleteDefaultChannelIfNeededLocked(fullPackagePreferences);
                        }
                    }
                } catch (PackageManager.NameNotFoundException e) {
                }
            }
        }

        if (updated) {
            updateConfig();
        }
        return updated;
    }

    public void clearData(String pkg, int uid) {
        synchronized (mPackagePreferences) {
            PackagePreferences p = getPackagePreferencesLocked(pkg, uid);
            if (p != null) {
                p.channels = new ArrayMap<>();
                p.groups = new ArrayMap<>();
                p.delegate = null;
                p.lockedAppFields = DEFAULT_LOCKED_APP_FIELDS;
                p.bubblePreference = DEFAULT_BUBBLE_PREFERENCE;
                p.importance = DEFAULT_IMPORTANCE;
                p.priority = DEFAULT_PRIORITY;
                p.visibility = DEFAULT_VISIBILITY;
                p.showBadge = DEFAULT_SHOW_BADGE;
            }
        }
    }

    private LogMaker getChannelLog(NotificationChannel channel, String pkg) {
        return new LogMaker(
                com.android.internal.logging.nano.MetricsProto.MetricsEvent
                        .ACTION_NOTIFICATION_CHANNEL)
                .setType(com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_UPDATE)
                .setPackageName(pkg)
                .addTaggedData(
                        com.android.internal.logging.nano.MetricsProto.MetricsEvent
                                .FIELD_NOTIFICATION_CHANNEL_ID,
                        channel.getId())
                .addTaggedData(
                        com.android.internal.logging.nano.MetricsProto.MetricsEvent
                                .FIELD_NOTIFICATION_CHANNEL_IMPORTANCE,
                        channel.getImportance());
    }

    private LogMaker getChannelGroupLog(String groupId, String pkg) {
        return new LogMaker(
                com.android.internal.logging.nano.MetricsProto.MetricsEvent
                        .ACTION_NOTIFICATION_CHANNEL_GROUP)
                .setType(com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_UPDATE)
                .addTaggedData(
                        com.android.internal.logging.nano.MetricsProto.MetricsEvent
                                .FIELD_NOTIFICATION_CHANNEL_GROUP_ID,
                        groupId)
                .setPackageName(pkg);
    }

    /** Requests check of the feature setting for showing media notifications in quick settings. */
    public void updateMediaNotificationFilteringEnabled() {
        // TODO(b/192412820): Consolidate SHOW_MEDIA_ON_QUICK_SETTINGS into compile-time value.
        final boolean newValue = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS, 1) > 0
                        && mContext.getResources().getBoolean(
                                R.bool.config_quickSettingsShowMediaPlayer);
        if (newValue != mIsMediaNotificationFilteringEnabled) {
            mIsMediaNotificationFilteringEnabled = newValue;
            updateConfig();
        }
    }

    /** Returns true if the setting is enabled for showing media notifications in quick settings. */
    public boolean isMediaNotificationFilteringEnabled() {
        return mIsMediaNotificationFilteringEnabled;
    }

    public void updateBadgingEnabled() {
        if (mBadgingEnabled == null) {
            mBadgingEnabled = new SparseBooleanArray();
        }
        boolean changed = false;
        // update the cached values
        for (int index = 0; index < mBadgingEnabled.size(); index++) {
            int userId = mBadgingEnabled.keyAt(index);
            final boolean oldValue = mBadgingEnabled.get(userId);
            final boolean newValue = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.NOTIFICATION_BADGING,
                    DEFAULT_SHOW_BADGE ? 1 : 0, userId) != 0;
            mBadgingEnabled.put(userId, newValue);
            changed |= oldValue != newValue;
        }
        if (changed) {
            updateConfig();
        }
    }

    public boolean badgingEnabled(UserHandle userHandle) {
        int userId = userHandle.getIdentifier();
        if (userId == UserHandle.USER_ALL) {
            return false;
        }
        if (mBadgingEnabled.indexOfKey(userId) < 0) {
            mBadgingEnabled.put(userId,
                    Settings.Secure.getIntForUser(mContext.getContentResolver(),
                            Settings.Secure.NOTIFICATION_BADGING,
                            DEFAULT_SHOW_BADGE ? 1 : 0, userId) != 0);
        }
        return mBadgingEnabled.get(userId, DEFAULT_SHOW_BADGE);
    }

    /** Updates whether bubbles are enabled for this user. */
    public void updateBubblesEnabled() {
        if (mBubblesEnabled == null) {
            mBubblesEnabled = new SparseBooleanArray();
        }
        boolean changed = false;
        // update the cached values
        for (int index = 0; index < mBubblesEnabled.size(); index++) {
            int userId = mBubblesEnabled.keyAt(index);
            final boolean oldValue = mBubblesEnabled.get(userId);
            final boolean newValue = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.NOTIFICATION_BUBBLES,
                    DEFAULT_BUBBLES_ENABLED ? 1 : 0, userId) != 0;
            mBubblesEnabled.put(userId, newValue);
            changed |= oldValue != newValue;
        }
        if (changed) {
            updateConfig();
        }
    }

    /** Returns true if bubbles are enabled for this user. */
    public boolean bubblesEnabled(UserHandle userHandle) {
        int userId = userHandle.getIdentifier();
        if (userId == UserHandle.USER_ALL) {
            return false;
        }
        if (mBubblesEnabled.indexOfKey(userId) < 0) {
            mBubblesEnabled.put(userId,
                    Settings.Secure.getIntForUser(mContext.getContentResolver(),
                            Settings.Secure.NOTIFICATION_BUBBLES,
                            DEFAULT_BUBBLES_ENABLED ? 1 : 0, userId) != 0);
        }
        return mBubblesEnabled.get(userId, DEFAULT_BUBBLES_ENABLED);
    }

    public void updateLockScreenPrivateNotifications() {
        if (mLockScreenPrivateNotifications == null) {
            mLockScreenPrivateNotifications = new SparseBooleanArray();
        }
        boolean changed = false;
        // update the cached values
        for (int index = 0; index < mLockScreenPrivateNotifications.size(); index++) {
            int userId = mLockScreenPrivateNotifications.keyAt(index);
            final boolean oldValue = mLockScreenPrivateNotifications.get(userId);
            final boolean newValue = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 1, userId) != 0;
            mLockScreenPrivateNotifications.put(userId, newValue);
            changed |= oldValue != newValue;
        }
        if (changed) {
            updateConfig();
        }
    }

    public void updateLockScreenShowNotifications() {
        if (mLockScreenShowNotifications == null) {
            mLockScreenShowNotifications = new SparseBooleanArray();
        }
        boolean changed = false;
        // update the cached values
        for (int index = 0; index < mLockScreenShowNotifications.size(); index++) {
            int userId = mLockScreenShowNotifications.keyAt(index);
            final boolean oldValue = mLockScreenShowNotifications.get(userId);
            final boolean newValue = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, userId) != 0;
            mLockScreenShowNotifications.put(userId, newValue);
            changed |= oldValue != newValue;
        }
        if (changed) {
            updateConfig();
        }
    }

    @Override
    public boolean canShowNotificationsOnLockscreen(int userId) {
        if (mLockScreenShowNotifications == null) {
            mLockScreenShowNotifications = new SparseBooleanArray();
        }
        return mLockScreenShowNotifications.get(userId, true);
    }

    @Override
    public boolean canShowPrivateNotificationsOnLockScreen(int userId) {
        if (mLockScreenPrivateNotifications == null) {
            mLockScreenPrivateNotifications = new SparseBooleanArray();
        }
        return mLockScreenPrivateNotifications.get(userId, true);
    }

    public void unlockAllNotificationChannels() {
        synchronized (mPackagePreferences) {
            final int numPackagePreferences = mPackagePreferences.size();
            for (int i = 0; i < numPackagePreferences; i++) {
                final PackagePreferences r = mPackagePreferences.valueAt(i);
                for (NotificationChannel channel : r.channels.values()) {
                    channel.unlockFields(USER_LOCKED_IMPORTANCE);
                }
            }
        }
    }

    public void migrateNotificationPermissions(List<UserInfo> users) {
        for (UserInfo user : users) {
            List<PackageInfo> packages = mPm.getInstalledPackagesAsUser(
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ALL),
                    user.getUserHandle().getIdentifier());
            for (PackageInfo pi : packages) {
                synchronized (mPackagePreferences) {
                    PackagePreferences p = getOrCreatePackagePreferencesLocked(
                            pi.packageName, pi.applicationInfo.uid);
                    if (p.migrateToPm && p.uid != UNKNOWN_UID) {
                        try {
                            PackagePermission pkgPerm = new PackagePermission(
                                    p.pkg, UserHandle.getUserId(p.uid),
                                    p.importance != IMPORTANCE_NONE,
                                    hasUserConfiguredSettings(p));
                            mPermissionHelper.setNotificationPermission(pkgPerm);
                        } catch (Exception e) {
                            Slog.e(TAG, "could not migrate setting for " + p.pkg, e);
                        }
                    }
                }
            }
        }
    }

    private void updateConfig() {
        mRankingHandler.requestSort();
    }

    private static String packagePreferencesKey(String pkg, int uid) {
        return pkg + "|" + uid;
    }

    private static String unrestoredPackageKey(String pkg, @UserIdInt int userId) {
        return pkg + "|" + userId;
    }

    private static class PackagePreferences {
        String pkg;
        int uid = UNKNOWN_UID;
        int importance = DEFAULT_IMPORTANCE;
        int priority = DEFAULT_PRIORITY;
        int visibility = DEFAULT_VISIBILITY;
        boolean showBadge = DEFAULT_SHOW_BADGE;
        int bubblePreference = DEFAULT_BUBBLE_PREFERENCE;
        int lockedAppFields = DEFAULT_LOCKED_APP_FIELDS;
        // these fields are loaded on boot from a different source of truth and so are not
        // written to notification policy xml
        boolean defaultAppLockedImportance = DEFAULT_APP_LOCKED_IMPORTANCE;
        boolean fixedImportance = DEFAULT_APP_LOCKED_IMPORTANCE;

        boolean hasSentInvalidMessage = false;
        boolean hasSentValidMessage = false;
        // note: only valid while hasSentMessage is false and hasSentInvalidMessage is true
        boolean userDemotedMsgApp = false;
        boolean hasSentValidBubble = false;

        boolean migrateToPm = false;

        Delegate delegate = null;
        ArrayMap<String, NotificationChannel> channels = new ArrayMap<>();
        Map<String, NotificationChannelGroup> groups = new ConcurrentHashMap<>();

        public boolean isValidDelegate(String pkg, int uid) {
            return delegate != null && delegate.isAllowed(pkg, uid);
        }
    }

    private static class Delegate {
        static final boolean DEFAULT_ENABLED = true;

        final String mPkg;
        final int mUid;
        boolean mEnabled;

        Delegate(String pkg, int uid, boolean enabled) {
            mPkg = pkg;
            mUid = uid;
            mEnabled = enabled;
        }

        public boolean isAllowed(String pkg, int uid) {
            if (pkg == null || uid == UNKNOWN_UID) {
                return false;
            }
            return pkg.equals(mPkg)
                    && uid == mUid
                    && mEnabled;
        }
    }
}

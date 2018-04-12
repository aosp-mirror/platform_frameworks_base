/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.notification;

import static android.app.NotificationManager.IMPORTANCE_NONE;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ParceledListSlice;
import android.content.pm.Signature;
import android.metrics.LogMaker;
import android.os.Build;
import android.os.UserHandle;
import android.print.PrintManager;
import android.provider.Settings.Secure;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.RankingHelperProto;
import android.service.notification.RankingHelperProto.RecordProto;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

public class RankingHelper implements RankingConfig {
    private static final String TAG = "RankingHelper";

    private static final int XML_VERSION = 1;

    static final String TAG_RANKING = "ranking";
    private static final String TAG_PACKAGE = "package";
    private static final String TAG_CHANNEL = "channel";
    private static final String TAG_GROUP = "channelGroup";

    private static final String ATT_VERSION = "version";
    private static final String ATT_NAME = "name";
    private static final String ATT_UID = "uid";
    private static final String ATT_ID = "id";
    private static final String ATT_PRIORITY = "priority";
    private static final String ATT_VISIBILITY = "visibility";
    private static final String ATT_IMPORTANCE = "importance";
    private static final String ATT_SHOW_BADGE = "show_badge";
    private static final String ATT_APP_USER_LOCKED_FIELDS = "app_user_locked_fields";

    private static final int DEFAULT_PRIORITY = Notification.PRIORITY_DEFAULT;
    private static final int DEFAULT_VISIBILITY = NotificationManager.VISIBILITY_NO_OVERRIDE;
    private static final int DEFAULT_IMPORTANCE = NotificationManager.IMPORTANCE_UNSPECIFIED;
    private static final boolean DEFAULT_SHOW_BADGE = true;
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
    }

    private final NotificationSignalExtractor[] mSignalExtractors;
    private final NotificationComparator mPreliminaryComparator;
    private final GlobalSortKeyComparator mFinalComparator = new GlobalSortKeyComparator();

    private final ArrayMap<String, Record> mRecords = new ArrayMap<>(); // pkg|uid => Record
    private final ArrayMap<String, NotificationRecord> mProxyByGroupTmp = new ArrayMap<>();
    private final ArrayMap<String, Record> mRestoredWithoutUids = new ArrayMap<>(); // pkg => Record
    private final ArrayMap<Pair<String, Integer>, Boolean> mSystemAppCache = new ArrayMap<>();

    private final Context mContext;
    private final RankingHandler mRankingHandler;
    private final PackageManager mPm;
    private SparseBooleanArray mBadgingEnabled;

    private Signature[] mSystemSignature;
    private String mPermissionControllerPackageName;
    private String mServicesSystemSharedLibPackageName;
    private String mSharedSystemSharedLibPackageName;

    public RankingHelper(Context context, PackageManager pm, RankingHandler rankingHandler,
            ZenModeHelper zenHelper, NotificationUsageStats usageStats, String[] extractorNames) {
        mContext = context;
        mRankingHandler = rankingHandler;
        mPm = pm;

        mPreliminaryComparator = new NotificationComparator(mContext);

        updateBadgingEnabled();

        final int N = extractorNames.length;
        mSignalExtractors = new NotificationSignalExtractor[N];
        for (int i = 0; i < N; i++) {
            try {
                Class<?> extractorClass = mContext.getClassLoader().loadClass(extractorNames[i]);
                NotificationSignalExtractor extractor =
                        (NotificationSignalExtractor) extractorClass.newInstance();
                extractor.initialize(mContext, usageStats);
                extractor.setConfig(this);
                extractor.setZenHelper(zenHelper);
                mSignalExtractors[i] = extractor;
            } catch (ClassNotFoundException e) {
                Slog.w(TAG, "Couldn't find extractor " + extractorNames[i] + ".", e);
            } catch (InstantiationException e) {
                Slog.w(TAG, "Couldn't instantiate extractor " + extractorNames[i] + ".", e);
            } catch (IllegalAccessException e) {
                Slog.w(TAG, "Problem accessing extractor " + extractorNames[i] + ".", e);
            }
        }

        getSignatures();
    }

    @SuppressWarnings("unchecked")
    public <T extends NotificationSignalExtractor> T findExtractor(Class<T> extractorClass) {
        final int N = mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            final NotificationSignalExtractor extractor = mSignalExtractors[i];
            if (extractorClass.equals(extractor.getClass())) {
                return (T) extractor;
            }
        }
        return null;
    }

    public void extractSignals(NotificationRecord r) {
        final int N = mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            NotificationSignalExtractor extractor = mSignalExtractors[i];
            try {
                RankingReconsideration recon = extractor.process(r);
                if (recon != null) {
                    mRankingHandler.requestReconsideration(recon);
                }
            } catch (Throwable t) {
                Slog.w(TAG, "NotificationSignalExtractor failed.", t);
            }
        }
    }

    public void readXml(XmlPullParser parser, boolean forRestore)
            throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type != XmlPullParser.START_TAG) return;
        String tag = parser.getName();
        if (!TAG_RANKING.equals(tag)) return;
        // Clobber groups and channels with the xml, but don't delete other data that wasn't present
        // at the time of serialization.
        mRestoredWithoutUids.clear();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            tag = parser.getName();
            if (type == XmlPullParser.END_TAG && TAG_RANKING.equals(tag)) {
                return;
            }
            if (type == XmlPullParser.START_TAG) {
                if (TAG_PACKAGE.equals(tag)) {
                    int uid = XmlUtils.readIntAttribute(parser, ATT_UID, Record.UNKNOWN_UID);
                    String name = parser.getAttributeValue(null, ATT_NAME);
                    if (!TextUtils.isEmpty(name)) {
                        if (forRestore) {
                            try {
                                //TODO: http://b/22388012
                                uid = mPm.getPackageUidAsUser(name, UserHandle.USER_SYSTEM);
                            } catch (NameNotFoundException e) {
                                // noop
                            }
                        }

                        Record r = getOrCreateRecord(name, uid,
                                XmlUtils.readIntAttribute(
                                        parser, ATT_IMPORTANCE, DEFAULT_IMPORTANCE),
                                XmlUtils.readIntAttribute(parser, ATT_PRIORITY, DEFAULT_PRIORITY),
                                XmlUtils.readIntAttribute(
                                        parser, ATT_VISIBILITY, DEFAULT_VISIBILITY),
                                XmlUtils.readBooleanAttribute(
                                        parser, ATT_SHOW_BADGE, DEFAULT_SHOW_BADGE));
                        r.importance = XmlUtils.readIntAttribute(
                                parser, ATT_IMPORTANCE, DEFAULT_IMPORTANCE);
                        r.priority = XmlUtils.readIntAttribute(
                                parser, ATT_PRIORITY, DEFAULT_PRIORITY);
                        r.visibility = XmlUtils.readIntAttribute(
                                parser, ATT_VISIBILITY, DEFAULT_VISIBILITY);
                        r.showBadge = XmlUtils.readBooleanAttribute(
                                parser, ATT_SHOW_BADGE, DEFAULT_SHOW_BADGE);
                        r.lockedAppFields = XmlUtils.readIntAttribute(parser,
                                ATT_APP_USER_LOCKED_FIELDS, DEFAULT_LOCKED_APP_FIELDS);

                        final int innerDepth = parser.getDepth();
                        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                                && (type != XmlPullParser.END_TAG
                                || parser.getDepth() > innerDepth)) {
                            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                                continue;
                            }

                            String tagName = parser.getName();
                            // Channel groups
                            if (TAG_GROUP.equals(tagName)) {
                                String id = parser.getAttributeValue(null, ATT_ID);
                                CharSequence groupName = parser.getAttributeValue(null, ATT_NAME);
                                if (!TextUtils.isEmpty(id)) {
                                    NotificationChannelGroup group
                                            = new NotificationChannelGroup(id, groupName);
                                    group.populateFromXml(parser);
                                    r.groups.put(id, group);
                                }
                            }
                            // Channels
                            if (TAG_CHANNEL.equals(tagName)) {
                                String id = parser.getAttributeValue(null, ATT_ID);
                                String channelName = parser.getAttributeValue(null, ATT_NAME);
                                int channelImportance = XmlUtils.readIntAttribute(
                                        parser, ATT_IMPORTANCE, DEFAULT_IMPORTANCE);
                                if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(channelName)) {
                                    NotificationChannel channel = new NotificationChannel(id,
                                            channelName, channelImportance);
                                    if (forRestore) {
                                        channel.populateFromXmlForRestore(parser, mContext);
                                    } else {
                                        channel.populateFromXml(parser);
                                    }
                                    r.channels.put(id, channel);
                                }
                            }
                        }

                        try {
                            deleteDefaultChannelIfNeeded(r);
                        } catch (NameNotFoundException e) {
                            Slog.e(TAG, "deleteDefaultChannelIfNeeded - Exception: " + e);
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("Failed to reach END_DOCUMENT");
    }

    private static String recordKey(String pkg, int uid) {
        return pkg + "|" + uid;
    }

    private Record getRecord(String pkg, int uid) {
        final String key = recordKey(pkg, uid);
        synchronized (mRecords) {
            return mRecords.get(key);
        }
    }

    private Record getOrCreateRecord(String pkg, int uid) {
        return getOrCreateRecord(pkg, uid,
                DEFAULT_IMPORTANCE, DEFAULT_PRIORITY, DEFAULT_VISIBILITY, DEFAULT_SHOW_BADGE);
    }

    private Record getOrCreateRecord(String pkg, int uid, int importance, int priority,
            int visibility, boolean showBadge) {
        final String key = recordKey(pkg, uid);
        synchronized (mRecords) {
            Record r = (uid == Record.UNKNOWN_UID) ? mRestoredWithoutUids.get(pkg) : mRecords.get(
                    key);
            if (r == null) {
                r = new Record();
                r.pkg = pkg;
                r.uid = uid;
                r.importance = importance;
                r.priority = priority;
                r.visibility = visibility;
                r.showBadge = showBadge;

                try {
                    createDefaultChannelIfNeeded(r);
                } catch (NameNotFoundException e) {
                    Slog.e(TAG, "createDefaultChannelIfNeeded - Exception: " + e);
                }

                if (r.uid == Record.UNKNOWN_UID) {
                    mRestoredWithoutUids.put(pkg, r);
                } else {
                    mRecords.put(key, r);
                }
            }
            return r;
        }
    }

    private boolean shouldHaveDefaultChannel(Record r) throws NameNotFoundException {
        final int userId = UserHandle.getUserId(r.uid);
        final ApplicationInfo applicationInfo = mPm.getApplicationInfoAsUser(r.pkg, 0, userId);
        if (applicationInfo.targetSdkVersion >= Build.VERSION_CODES.O) {
            // O apps should not have the default channel.
            return false;
        }

        // Otherwise, this app should have the default channel.
        return true;
    }

    private void deleteDefaultChannelIfNeeded(Record r) throws NameNotFoundException {
        if (!r.channels.containsKey(NotificationChannel.DEFAULT_CHANNEL_ID)) {
            // Not present
            return;
        }

        if (shouldHaveDefaultChannel(r)) {
            // Keep the default channel until upgraded.
            return;
        }

        // Remove Default Channel.
        r.channels.remove(NotificationChannel.DEFAULT_CHANNEL_ID);
    }

    private void createDefaultChannelIfNeeded(Record r) throws NameNotFoundException {
        if (r.channels.containsKey(NotificationChannel.DEFAULT_CHANNEL_ID)) {
            r.channels.get(NotificationChannel.DEFAULT_CHANNEL_ID).setName(
                    mContext.getString(R.string.default_notification_channel_label));
            return;
        }

        if (!shouldHaveDefaultChannel(r)) {
            // Keep the default channel until upgraded.
            return;
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
    }

    public void writeXml(XmlSerializer out, boolean forBackup) throws IOException {
        out.startTag(null, TAG_RANKING);
        out.attribute(null, ATT_VERSION, Integer.toString(XML_VERSION));

        synchronized (mRecords) {
            final int N = mRecords.size();
            for (int i = 0; i < N; i++) {
                final Record r = mRecords.valueAt(i);
                //TODO: http://b/22388012
                if (forBackup && UserHandle.getUserId(r.uid) != UserHandle.USER_SYSTEM) {
                    continue;
                }
                final boolean hasNonDefaultSettings =
                        r.importance != DEFAULT_IMPORTANCE
                            || r.priority != DEFAULT_PRIORITY
                            || r.visibility != DEFAULT_VISIBILITY
                            || r.showBadge != DEFAULT_SHOW_BADGE
                            || r.lockedAppFields != DEFAULT_LOCKED_APP_FIELDS
                            || r.channels.size() > 0
                            || r.groups.size() > 0;
                if (hasNonDefaultSettings) {
                    out.startTag(null, TAG_PACKAGE);
                    out.attribute(null, ATT_NAME, r.pkg);
                    if (r.importance != DEFAULT_IMPORTANCE) {
                        out.attribute(null, ATT_IMPORTANCE, Integer.toString(r.importance));
                    }
                    if (r.priority != DEFAULT_PRIORITY) {
                        out.attribute(null, ATT_PRIORITY, Integer.toString(r.priority));
                    }
                    if (r.visibility != DEFAULT_VISIBILITY) {
                        out.attribute(null, ATT_VISIBILITY, Integer.toString(r.visibility));
                    }
                    out.attribute(null, ATT_SHOW_BADGE, Boolean.toString(r.showBadge));
                    out.attribute(null, ATT_APP_USER_LOCKED_FIELDS,
                            Integer.toString(r.lockedAppFields));

                    if (!forBackup) {
                        out.attribute(null, ATT_UID, Integer.toString(r.uid));
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
        }
        out.endTag(null, TAG_RANKING);
    }

    private void updateConfig() {
        final int N = mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            mSignalExtractors[i].setConfig(this);
        }
        mRankingHandler.requestSort();
    }

    public void sort(ArrayList<NotificationRecord> notificationList) {
        final int N = notificationList.size();
        // clear global sort keys
        for (int i = N - 1; i >= 0; i--) {
            notificationList.get(i).setGlobalSortKey(null);
        }

        // rank each record individually
        Collections.sort(notificationList, mPreliminaryComparator);

        synchronized (mProxyByGroupTmp) {
            // record individual ranking result and nominate proxies for each group
            for (int i = N - 1; i >= 0; i--) {
                final NotificationRecord record = notificationList.get(i);
                record.setAuthoritativeRank(i);
                final String groupKey = record.getGroupKey();
                NotificationRecord existingProxy = mProxyByGroupTmp.get(groupKey);
                if (existingProxy == null) {
                    mProxyByGroupTmp.put(groupKey, record);
                }
            }
            // assign global sort key:
            //   is_recently_intrusive:group_rank:is_group_summary:group_sort_key:rank
            for (int i = 0; i < N; i++) {
                final NotificationRecord record = notificationList.get(i);
                NotificationRecord groupProxy = mProxyByGroupTmp.get(record.getGroupKey());
                String groupSortKey = record.getNotification().getSortKey();

                // We need to make sure the developer provided group sort key (gsk) is handled
                // correctly:
                //   gsk="" < gsk=non-null-string < gsk=null
                //
                // We enforce this by using different prefixes for these three cases.
                String groupSortKeyPortion;
                if (groupSortKey == null) {
                    groupSortKeyPortion = "nsk";
                } else if (groupSortKey.equals("")) {
                    groupSortKeyPortion = "esk";
                } else {
                    groupSortKeyPortion = "gsk=" + groupSortKey;
                }

                boolean isGroupSummary = record.getNotification().isGroupSummary();
                record.setGlobalSortKey(
                        String.format("intrsv=%c:grnk=0x%04x:gsmry=%c:%s:rnk=0x%04x",
                        record.isRecentlyIntrusive()
                                && record.getImportance() > NotificationManager.IMPORTANCE_MIN
                                ? '0' : '1',
                        groupProxy.getAuthoritativeRank(),
                        isGroupSummary ? '0' : '1',
                        groupSortKeyPortion,
                        record.getAuthoritativeRank()));
            }
            mProxyByGroupTmp.clear();
        }

        // Do a second ranking pass, using group proxies
        Collections.sort(notificationList, mFinalComparator);
    }

    public int indexOf(ArrayList<NotificationRecord> notificationList, NotificationRecord target) {
        return Collections.binarySearch(notificationList, target, mFinalComparator);
    }

    /**
     * Gets importance.
     */
    @Override
    public int getImportance(String packageName, int uid) {
        return getOrCreateRecord(packageName, uid).importance;
    }


    /**
     * Returns whether the importance of the corresponding notification is user-locked and shouldn't
     * be adjusted by an assistant (via means of a blocking helper, for example). For the channel
     * locking field, see {@link NotificationChannel#USER_LOCKED_IMPORTANCE}.
     */
    public boolean getIsAppImportanceLocked(String packageName, int uid) {
        int userLockedFields = getOrCreateRecord(packageName, uid).lockedAppFields;
        return (userLockedFields & LockableAppFields.USER_LOCKED_IMPORTANCE) != 0;
    }

    @Override
    public boolean canShowBadge(String packageName, int uid) {
        return getOrCreateRecord(packageName, uid).showBadge;
    }

    @Override
    public void setShowBadge(String packageName, int uid, boolean showBadge) {
        getOrCreateRecord(packageName, uid).showBadge = showBadge;
        updateConfig();
    }

    @Override
    public boolean isGroupBlocked(String packageName, int uid, String groupId) {
        if (groupId == null) {
            return false;
        }
        Record r = getOrCreateRecord(packageName, uid);
        NotificationChannelGroup group = r.groups.get(groupId);
        if (group == null) {
            return false;
        }
        return group.isBlocked();
    }

    int getPackagePriority(String pkg, int uid) {
        return getOrCreateRecord(pkg, uid).priority;
    }

    int getPackageVisibility(String pkg, int uid) {
        return getOrCreateRecord(pkg, uid).visibility;
    }

    @Override
    public void createNotificationChannelGroup(String pkg, int uid, NotificationChannelGroup group,
            boolean fromTargetApp) {
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(group);
        Preconditions.checkNotNull(group.getId());
        Preconditions.checkNotNull(!TextUtils.isEmpty(group.getName()));
        Record r = getOrCreateRecord(pkg, uid);
        if (r == null) {
            throw new IllegalArgumentException("Invalid package");
        }
        final NotificationChannelGroup oldGroup = r.groups.get(group.getId());
        if (!group.equals(oldGroup)) {
            // will log for new entries as well as name/description changes
            MetricsLogger.action(getChannelGroupLog(group.getId(), pkg));
        }
        if (oldGroup != null) {
            group.setChannels(oldGroup.getChannels());

            if (fromTargetApp) {
                group.setBlocked(oldGroup.isBlocked());
            }
        }
        r.groups.put(group.getId(), group);
    }

    @Override
    public void createNotificationChannel(String pkg, int uid, NotificationChannel channel,
            boolean fromTargetApp, boolean hasDndAccess) {
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(channel);
        Preconditions.checkNotNull(channel.getId());
        Preconditions.checkArgument(!TextUtils.isEmpty(channel.getName()));
        Record r = getOrCreateRecord(pkg, uid);
        if (r == null) {
            throw new IllegalArgumentException("Invalid package");
        }
        if (channel.getGroup() != null && !r.groups.containsKey(channel.getGroup())) {
            throw new IllegalArgumentException("NotificationChannelGroup doesn't exist");
        }
        if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(channel.getId())) {
            throw new IllegalArgumentException("Reserved id");
        }
        final boolean isSystemApp = isSystemPackage(pkg, uid);
        NotificationChannel existing = r.channels.get(channel.getId());
        // Keep most of the existing settings
        if (existing != null && fromTargetApp) {
            if (existing.isDeleted()) {
                existing.setDeleted(false);

                // log a resurrected channel as if it's new again
                MetricsLogger.action(getChannelLog(channel, pkg).setType(
                        MetricsProto.MetricsEvent.TYPE_OPEN));
            }

            existing.setName(channel.getName().toString());
            existing.setDescription(channel.getDescription());
            existing.setBlockableSystem(channel.isBlockableSystem());
            if (existing.getGroup() == null) {
                existing.setGroup(channel.getGroup());
            }

            // Apps are allowed to downgrade channel importance if the user has not changed any
            // fields on this channel yet.
            if (existing.getUserLockedFields() == 0 &&
                    channel.getImportance() < existing.getImportance()) {
                existing.setImportance(channel.getImportance());
            }

            // system apps and dnd access apps can bypass dnd if the user hasn't changed any
            // fields on the channel yet
            if (existing.getUserLockedFields() == 0 && (isSystemApp || hasDndAccess)) {
                existing.setBypassDnd(channel.canBypassDnd());
            }

            updateConfig();
            return;
        }
        if (channel.getImportance() < IMPORTANCE_NONE
                || channel.getImportance() > NotificationManager.IMPORTANCE_MAX) {
            throw new IllegalArgumentException("Invalid importance level");
        }

        // Reset fields that apps aren't allowed to set.
        if (fromTargetApp && !(isSystemApp || hasDndAccess)) {
            channel.setBypassDnd(r.priority == Notification.PRIORITY_MAX);
        }
        if (fromTargetApp) {
            channel.setLockscreenVisibility(r.visibility);
        }
        clearLockedFields(channel);
        if (channel.getLockscreenVisibility() == Notification.VISIBILITY_PUBLIC) {
            channel.setLockscreenVisibility(Ranking.VISIBILITY_NO_OVERRIDE);
        }
        if (!r.showBadge) {
            channel.setShowBadge(false);
        }

        r.channels.put(channel.getId(), channel);
        MetricsLogger.action(getChannelLog(channel, pkg).setType(
                MetricsProto.MetricsEvent.TYPE_OPEN));
    }

    void clearLockedFields(NotificationChannel channel) {
        channel.unlockFields(channel.getUserLockedFields());
    }

    /**
     * Determine whether a package is a "system package", in which case certain things (like
     * bypassing DND) should be allowed.
     */
    private boolean isSystemPackage(String pkg, int uid) {
        Pair<String, Integer> app = new Pair(pkg, uid);
        if (mSystemAppCache.containsKey(app)) {
            return mSystemAppCache.get(app);
        }

        PackageInfo pi;
        try {
            pi = mPm.getPackageInfoAsUser(
                    pkg, PackageManager.GET_SIGNATURES, UserHandle.getUserId(uid));
        } catch (NameNotFoundException e) {
            Slog.w(TAG, "Can't find pkg", e);
            return false;
        }
        boolean isSystem = (mSystemSignature[0] != null
                && mSystemSignature[0].equals(getFirstSignature(pi)))
                || pkg.equals(mPermissionControllerPackageName)
                || pkg.equals(mServicesSystemSharedLibPackageName)
                || pkg.equals(mSharedSystemSharedLibPackageName)
                || pkg.equals(PrintManager.PRINT_SPOOLER_PACKAGE_NAME)
                || isDeviceProvisioningPackage(pkg);
        mSystemAppCache.put(app, isSystem);
        return isSystem;
    }

    private Signature getFirstSignature(PackageInfo pkg) {
        if (pkg != null && pkg.signatures != null && pkg.signatures.length > 0) {
            return pkg.signatures[0];
        }
        return null;
    }

    private Signature getSystemSignature() {
        try {
            final PackageInfo sys = mPm.getPackageInfoAsUser(
                    "android", PackageManager.GET_SIGNATURES, UserHandle.USER_SYSTEM);
            return getFirstSignature(sys);
        } catch (NameNotFoundException e) {
        }
        return null;
    }

    private boolean isDeviceProvisioningPackage(String packageName) {
        String deviceProvisioningPackage = mContext.getResources().getString(
                com.android.internal.R.string.config_deviceProvisioningPackage);
        return deviceProvisioningPackage != null && deviceProvisioningPackage.equals(packageName);
    }

    private void getSignatures() {
        mSystemSignature = new Signature[]{getSystemSignature()};
        mPermissionControllerPackageName = mPm.getPermissionControllerPackageName();
        mServicesSystemSharedLibPackageName = mPm.getServicesSystemSharedLibraryPackageName();
        mSharedSystemSharedLibPackageName = mPm.getSharedSystemSharedLibraryPackageName();
    }

    @Override
    public void updateNotificationChannel(String pkg, int uid, NotificationChannel updatedChannel,
            boolean fromUser) {
        Preconditions.checkNotNull(updatedChannel);
        Preconditions.checkNotNull(updatedChannel.getId());
        Record r = getOrCreateRecord(pkg, uid);
        if (r == null) {
            throw new IllegalArgumentException("Invalid package");
        }
        NotificationChannel channel = r.channels.get(updatedChannel.getId());
        if (channel == null || channel.isDeleted()) {
            throw new IllegalArgumentException("Channel does not exist");
        }
        if (updatedChannel.getLockscreenVisibility() == Notification.VISIBILITY_PUBLIC) {
            updatedChannel.setLockscreenVisibility(Ranking.VISIBILITY_NO_OVERRIDE);
        }
        if (!fromUser) {
            updatedChannel.unlockFields(updatedChannel.getUserLockedFields());
        }
        if (fromUser) {
            updatedChannel.lockFields(channel.getUserLockedFields());
            lockFieldsForUpdate(channel, updatedChannel);
        }
        r.channels.put(updatedChannel.getId(), updatedChannel);

        if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(updatedChannel.getId())) {
            // copy settings to app level so they are inherited by new channels
            // when the app migrates
            r.importance = updatedChannel.getImportance();
            r.priority = updatedChannel.canBypassDnd()
                    ? Notification.PRIORITY_MAX : Notification.PRIORITY_DEFAULT;
            r.visibility = updatedChannel.getLockscreenVisibility();
            r.showBadge = updatedChannel.canShowBadge();
        }

        if (!channel.equals(updatedChannel)) {
            // only log if there are real changes
            MetricsLogger.action(getChannelLog(updatedChannel, pkg));
        }
        updateConfig();
    }

    @Override
    public NotificationChannel getNotificationChannel(String pkg, int uid, String channelId,
            boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        Record r = getOrCreateRecord(pkg, uid);
        if (r == null) {
            return null;
        }
        if (channelId == null) {
            channelId = NotificationChannel.DEFAULT_CHANNEL_ID;
        }
        final NotificationChannel nc = r.channels.get(channelId);
        if (nc != null && (includeDeleted || !nc.isDeleted())) {
            return nc;
        }
        return null;
    }

    @Override
    public void deleteNotificationChannel(String pkg, int uid, String channelId) {
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return;
        }
        NotificationChannel channel = r.channels.get(channelId);
        if (channel != null) {
            channel.setDeleted(true);
            LogMaker lm = getChannelLog(channel, pkg);
            lm.setType(MetricsProto.MetricsEvent.TYPE_CLOSE);
            MetricsLogger.action(lm);
        }
    }

    @Override
    @VisibleForTesting
    public void permanentlyDeleteNotificationChannel(String pkg, int uid, String channelId) {
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(channelId);
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return;
        }
        r.channels.remove(channelId);
    }

    @Override
    public void permanentlyDeleteNotificationChannels(String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        Record r = getRecord(pkg, uid);
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

    public NotificationChannelGroup getNotificationChannelGroupWithChannels(String pkg,
            int uid, String groupId, boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        Record r = getRecord(pkg, uid);
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

    public NotificationChannelGroup getNotificationChannelGroup(String groupId, String pkg,
            int uid) {
        Preconditions.checkNotNull(pkg);
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return null;
        }
        return r.groups.get(groupId);
    }

    @Override
    public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroups(String pkg,
            int uid, boolean includeDeleted, boolean includeNonGrouped) {
        Preconditions.checkNotNull(pkg);
        Map<String, NotificationChannelGroup> groups = new ArrayMap<>();
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return ParceledListSlice.emptyList();
        }
        NotificationChannelGroup nonGrouped = new NotificationChannelGroup(null, null);
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            final NotificationChannel nc = r.channels.valueAt(i);
            if (includeDeleted || !nc.isDeleted()) {
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
        return new ParceledListSlice<>(new ArrayList<>(groups.values()));
    }

    public List<NotificationChannel> deleteNotificationChannelGroup(String pkg, int uid,
            String groupId) {
        List<NotificationChannel> deletedChannels = new ArrayList<>();
        Record r = getRecord(pkg, uid);
        if (r == null || TextUtils.isEmpty(groupId)) {
            return deletedChannels;
        }

        r.groups.remove(groupId);

        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            final NotificationChannel nc = r.channels.valueAt(i);
            if (groupId.equals(nc.getGroup())) {
                nc.setDeleted(true);
                deletedChannels.add(nc);
            }
        }
        return deletedChannels;
    }

    @Override
    public Collection<NotificationChannelGroup> getNotificationChannelGroups(String pkg,
            int uid) {
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return new ArrayList<>();
        }
        return r.groups.values();
    }

    @Override
    public ParceledListSlice<NotificationChannel> getNotificationChannels(String pkg, int uid,
            boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        List<NotificationChannel> channels = new ArrayList<>();
        Record r = getRecord(pkg, uid);
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

    /**
     * True for pre-O apps that only have the default channel, or pre O apps that have no
     * channels yet. This method will create the default channel for pre-O apps that don't have it.
     * Should never be true for O+ targeting apps, but that's enforced on boot/when an app
     * upgrades.
     */
    public boolean onlyHasDefaultChannel(String pkg, int uid) {
        Record r = getOrCreateRecord(pkg, uid);
        if (r.channels.size() == 1
                && r.channels.containsKey(NotificationChannel.DEFAULT_CHANNEL_ID)) {
            return true;
        }
        return false;
    }

    public int getDeletedChannelCount(String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        int deletedCount = 0;
        Record r = getRecord(pkg, uid);
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

    public int getBlockedChannelCount(String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        int blockedCount = 0;
        Record r = getRecord(pkg, uid);
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

    /**
     * Sets importance.
     */
    @Override
    public void setImportance(String pkgName, int uid, int importance) {
        getOrCreateRecord(pkgName, uid).importance = importance;
        updateConfig();
    }

    public void setEnabled(String packageName, int uid, boolean enabled) {
        boolean wasEnabled = getImportance(packageName, uid) != IMPORTANCE_NONE;
        if (wasEnabled == enabled) {
            return;
        }
        setImportance(packageName, uid,
                enabled ? DEFAULT_IMPORTANCE : IMPORTANCE_NONE);
    }

    /**
     * Sets whether any notifications from the app, represented by the given {@code pkgName} and
     * {@code uid}, have their importance locked by the user. Locked notifications don't get
     * considered for sentiment adjustments (and thus never show a blocking helper).
     */
    public void setAppImportanceLocked(String packageName, int uid) {
        Record record = getOrCreateRecord(packageName, uid);
        if ((record.lockedAppFields & LockableAppFields.USER_LOCKED_IMPORTANCE) != 0) {
            return;
        }

        record.lockedAppFields = record.lockedAppFields | LockableAppFields.USER_LOCKED_IMPORTANCE;
        updateConfig();
    }

    @VisibleForTesting
    void lockFieldsForUpdate(NotificationChannel original, NotificationChannel update) {
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
                || original.shouldVibrate() != update.shouldVibrate()) {
            update.lockFields(NotificationChannel.USER_LOCKED_VIBRATION);
        }
        if (original.canShowBadge() != update.canShowBadge()) {
            update.lockFields(NotificationChannel.USER_LOCKED_SHOW_BADGE);
        }
    }

    public void dump(PrintWriter pw, String prefix,
            @NonNull NotificationManagerService.DumpFilter filter) {
        final int N = mSignalExtractors.length;
        pw.print(prefix);
        pw.print("mSignalExtractors.length = ");
        pw.println(N);
        for (int i = 0; i < N; i++) {
            pw.print(prefix);
            pw.print("  ");
            pw.println(mSignalExtractors[i].getClass().getSimpleName());
        }

        pw.print(prefix);
        pw.println("per-package config:");

        pw.println("Records:");
        synchronized (mRecords) {
            dumpRecords(pw, prefix, filter, mRecords);
        }
        pw.println("Restored without uid:");
        dumpRecords(pw, prefix, filter, mRestoredWithoutUids);
    }

    public void dump(ProtoOutputStream proto,
            @NonNull NotificationManagerService.DumpFilter filter) {
        final int N = mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            proto.write(RankingHelperProto.NOTIFICATION_SIGNAL_EXTRACTORS,
                mSignalExtractors[i].getClass().getSimpleName());
        }
        synchronized (mRecords) {
            dumpRecords(proto, RankingHelperProto.RECORDS, filter, mRecords);
        }
        dumpRecords(proto, RankingHelperProto.RECORDS_RESTORED_WITHOUT_UID, filter,
            mRestoredWithoutUids);
    }

    private static void dumpRecords(ProtoOutputStream proto, long fieldId,
            @NonNull NotificationManagerService.DumpFilter filter,
            ArrayMap<String, Record> records) {
        final int N = records.size();
        long fToken;
        for (int i = 0; i < N; i++) {
            final Record r = records.valueAt(i);
            if (filter.matches(r.pkg)) {
                fToken = proto.start(fieldId);

                proto.write(RecordProto.PACKAGE, r.pkg);
                proto.write(RecordProto.UID, r.uid);
                proto.write(RecordProto.IMPORTANCE, r.importance);
                proto.write(RecordProto.PRIORITY, r.priority);
                proto.write(RecordProto.VISIBILITY, r.visibility);
                proto.write(RecordProto.SHOW_BADGE, r.showBadge);

                for (NotificationChannel channel : r.channels.values()) {
                    channel.writeToProto(proto, RecordProto.CHANNELS);
                }
                for (NotificationChannelGroup group : r.groups.values()) {
                    group.writeToProto(proto, RecordProto.CHANNEL_GROUPS);
                }

                proto.end(fToken);
            }
        }
    }

    private static void dumpRecords(PrintWriter pw, String prefix,
            @NonNull NotificationManagerService.DumpFilter filter,
            ArrayMap<String, Record> records) {
        final int N = records.size();
        for (int i = 0; i < N; i++) {
            final Record r = records.valueAt(i);
            if (filter.matches(r.pkg)) {
                pw.print(prefix);
                pw.print("  AppSettings: ");
                pw.print(r.pkg);
                pw.print(" (");
                pw.print(r.uid == Record.UNKNOWN_UID ? "UNKNOWN_UID" : Integer.toString(r.uid));
                pw.print(')');
                if (r.importance != DEFAULT_IMPORTANCE) {
                    pw.print(" importance=");
                    pw.print(Ranking.importanceToString(r.importance));
                }
                if (r.priority != DEFAULT_PRIORITY) {
                    pw.print(" priority=");
                    pw.print(Notification.priorityToString(r.priority));
                }
                if (r.visibility != DEFAULT_VISIBILITY) {
                    pw.print(" visibility=");
                    pw.print(Notification.visibilityToString(r.visibility));
                }
                pw.print(" showBadge=");
                pw.print(Boolean.toString(r.showBadge));
                pw.println();
                for (NotificationChannel channel : r.channels.values()) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.print("  ");
                    pw.println(channel);
                }
                for (NotificationChannelGroup group : r.groups.values()) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.print("  ");
                    pw.println(group);
                }
            }
        }
    }

    public JSONObject dumpJson(NotificationManagerService.DumpFilter filter) {
        JSONObject ranking = new JSONObject();
        JSONArray records = new JSONArray();
        try {
            ranking.put("noUid", mRestoredWithoutUids.size());
        } catch (JSONException e) {
           // pass
        }
        synchronized (mRecords) {
            final int N = mRecords.size();
            for (int i = 0; i < N; i++) {
                final Record r = mRecords.valueAt(i);
                if (filter == null || filter.matches(r.pkg)) {
                    JSONObject record = new JSONObject();
                    try {
                        record.put("userId", UserHandle.getUserId(r.uid));
                        record.put("packageName", r.pkg);
                        if (r.importance != DEFAULT_IMPORTANCE) {
                            record.put("importance", Ranking.importanceToString(r.importance));
                        }
                        if (r.priority != DEFAULT_PRIORITY) {
                            record.put("priority", Notification.priorityToString(r.priority));
                        }
                        if (r.visibility != DEFAULT_VISIBILITY) {
                            record.put("visibility", Notification.visibilityToString(r.visibility));
                        }
                        if (r.showBadge != DEFAULT_SHOW_BADGE) {
                            record.put("showBadge", Boolean.valueOf(r.showBadge));
                        }
                        for (NotificationChannel channel : r.channels.values()) {
                            record.put("channel", channel.toJson());
                        }
                        for (NotificationChannelGroup group : r.groups.values()) {
                            record.put("group", group.toJson());
                        }
                    } catch (JSONException e) {
                        // pass
                    }
                    records.put(record);
                }
            }
        }
        try {
            ranking.put("records", records);
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
    public JSONArray dumpBansJson(NotificationManagerService.DumpFilter filter) {
        JSONArray bans = new JSONArray();
        Map<Integer, String> packageBans = getPackageBans();
        for(Entry<Integer, String> ban : packageBans.entrySet()) {
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
        synchronized (mRecords) {
            final int N = mRecords.size();
            ArrayMap<Integer, String> packageBans = new ArrayMap<>(N);
            for (int i = 0; i < N; i++) {
                final Record r = mRecords.valueAt(i);
                if (r.importance == IMPORTANCE_NONE) {
                    packageBans.put(r.uid, r.pkg);
                }
            }

            return packageBans;
        }
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
        for(Entry<String, Integer> channelCount : packageChannels.entrySet()) {
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
        synchronized (mRecords) {
            for (int i = 0; i < mRecords.size(); i++) {
                final Record r = mRecords.valueAt(i);
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
        synchronized (mRecords) {
            int N = mRecords.size();
            for (int i = N - 1; i >= 0 ; i--) {
                Record record = mRecords.valueAt(i);
                if (UserHandle.getUserId(record.uid) == userId) {
                    mRecords.removeAt(i);
                }
            }
        }
    }

    protected void onLocaleChanged(Context context, int userId) {
        synchronized (mRecords) {
            int N = mRecords.size();
            for (int i = 0; i < N; i++) {
                Record record = mRecords.valueAt(i);
                if (UserHandle.getUserId(record.uid) == userId) {
                    if (record.channels.containsKey(NotificationChannel.DEFAULT_CHANNEL_ID)) {
                        record.channels.get(NotificationChannel.DEFAULT_CHANNEL_ID).setName(
                                context.getResources().getString(
                                        R.string.default_notification_channel_label));
                    }
                }
            }
        }
    }

    public void onPackagesChanged(boolean removingPackage, int changeUserId, String[] pkgList,
            int[] uidList) {
        if (pkgList == null || pkgList.length == 0) {
            return; // nothing to do
        }
        boolean updated = false;
        if (removingPackage) {
            // Remove notification settings for uninstalled package
            int size = Math.min(pkgList.length, uidList.length);
            for (int i = 0; i < size; i++) {
                final String pkg = pkgList[i];
                final int uid = uidList[i];
                synchronized (mRecords) {
                    mRecords.remove(recordKey(pkg, uid));
                }
                mRestoredWithoutUids.remove(pkg);
                updated = true;
            }
        } else {
            for (String pkg : pkgList) {
                // Package install
                final Record r = mRestoredWithoutUids.get(pkg);
                if (r != null) {
                    try {
                        r.uid = mPm.getPackageUidAsUser(r.pkg, changeUserId);
                        mRestoredWithoutUids.remove(pkg);
                        synchronized (mRecords) {
                            mRecords.put(recordKey(r.pkg, r.uid), r);
                        }
                        updated = true;
                    } catch (NameNotFoundException e) {
                        // noop
                    }
                }
                // Package upgrade
                try {
                    Record fullRecord = getRecord(pkg,
                            mPm.getPackageUidAsUser(pkg, changeUserId));
                    if (fullRecord != null) {
                        createDefaultChannelIfNeeded(fullRecord);
                        deleteDefaultChannelIfNeeded(fullRecord);
                    }
                } catch (NameNotFoundException e) {}
            }
        }

        if (updated) {
            updateConfig();
        }
    }

    private LogMaker getChannelLog(NotificationChannel channel, String pkg) {
        return new LogMaker(MetricsProto.MetricsEvent.ACTION_NOTIFICATION_CHANNEL)
                .setType(MetricsProto.MetricsEvent.TYPE_UPDATE)
                .setPackageName(pkg)
                .addTaggedData(MetricsProto.MetricsEvent.FIELD_NOTIFICATION_CHANNEL_ID,
                        channel.getId())
                .addTaggedData(MetricsProto.MetricsEvent.FIELD_NOTIFICATION_CHANNEL_IMPORTANCE,
                        channel.getImportance());
    }

    private LogMaker getChannelGroupLog(String groupId, String pkg) {
        return new LogMaker(MetricsProto.MetricsEvent.ACTION_NOTIFICATION_CHANNEL_GROUP)
                .setType(MetricsProto.MetricsEvent.TYPE_UPDATE)
                .addTaggedData(MetricsProto.MetricsEvent.FIELD_NOTIFICATION_CHANNEL_GROUP_ID,
                        groupId)
                .setPackageName(pkg);
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
            final boolean newValue = Secure.getIntForUser(mContext.getContentResolver(),
                    Secure.NOTIFICATION_BADGING,
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
                    Secure.getIntForUser(mContext.getContentResolver(),
                            Secure.NOTIFICATION_BADGING,
                            DEFAULT_SHOW_BADGE ? 1 : 0, userId) != 0);
        }
        return mBadgingEnabled.get(userId, DEFAULT_SHOW_BADGE);
    }


    private static class Record {
        static int UNKNOWN_UID = UserHandle.USER_NULL;

        String pkg;
        int uid = UNKNOWN_UID;
        int importance = DEFAULT_IMPORTANCE;
        int priority = DEFAULT_PRIORITY;
        int visibility = DEFAULT_VISIBILITY;
        boolean showBadge = DEFAULT_SHOW_BADGE;
        int lockedAppFields = DEFAULT_LOCKED_APP_FIELDS;

        ArrayMap<String, NotificationChannel> channels = new ArrayMap<>();
        Map<String, NotificationChannelGroup> groups = new ConcurrentHashMap<>();
   }
}

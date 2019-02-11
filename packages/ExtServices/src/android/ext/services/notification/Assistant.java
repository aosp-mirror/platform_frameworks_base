/**
 * Copyright (C) 2017 The Android Open Source Project
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

package android.ext.services.notification;

import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.service.notification.Adjustment.KEY_IMPORTANCE;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.ext.services.notification.AgingHelper.Callback;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.service.notification.Adjustment;
import android.service.notification.NotificationAssistantService;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Notification assistant that provides guidance on notification channel blocking
 */
public class Assistant extends NotificationAssistantService {
    private static final String TAG = "ExtAssistant";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    public static final boolean AGE_NOTIFICATIONS = SystemProperties.getBoolean(
            "debug.age_notifs", false);

    private static final String TAG_ASSISTANT = "assistant";
    private static final String TAG_IMPRESSION = "impression-set";
    private static final String ATT_KEY = "key";
    private static final int DB_VERSION = 1;
    private static final String ATTR_VERSION = "version";

    private static final ArrayList<Integer> PREJUDICAL_DISMISSALS = new ArrayList<>();
    static {
        PREJUDICAL_DISMISSALS.add(REASON_CANCEL);
        PREJUDICAL_DISMISSALS.add(REASON_LISTENER_CANCEL);
    }

    private SmartActionsHelper mSmartActionsHelper;
    private NotificationCategorizer mNotificationCategorizer;
    private AgingHelper mAgingHelper;

    // key : impressions tracker
    // TODO: prune deleted channels and apps
    private final ArrayMap<String, ChannelImpressions> mkeyToImpressions = new ArrayMap<>();
    // SBN key : entry
    protected ArrayMap<String, NotificationEntry> mLiveNotifications = new ArrayMap<>();

    private Ranking mFakeRanking = null;
    private AtomicFile mFile = null;
    private IPackageManager mPackageManager;

    @VisibleForTesting
    protected AssistantSettings.Factory mSettingsFactory = AssistantSettings.FACTORY;
    @VisibleForTesting
    protected AssistantSettings mSettings;
    private SmsHelper mSmsHelper;

    public Assistant() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Contexts are correctly hooked up by the creation step, which is required for the observer
        // to be hooked up/initialized.
        mPackageManager = ActivityThread.getPackageManager();
        mSettings = mSettingsFactory.createAndRegister(mHandler,
                getApplicationContext().getContentResolver(), getUserId(), this::updateThresholds);
        mSmartActionsHelper = new SmartActionsHelper(getContext(), mSettings);
        mNotificationCategorizer = new NotificationCategorizer();
        mAgingHelper = new AgingHelper(getContext(),
                mNotificationCategorizer,
                new AgingCallback());
        mSmsHelper = new SmsHelper(this);
        mSmsHelper.initialize();
    }

    @Override
    public void onDestroy() {
        // This null check is only for the unit tests as ServiceTestCase.tearDown calls onDestroy
        // without having first called onCreate.
        if (mSmsHelper != null) {
            mSmsHelper.destroy();
        }
        super.onDestroy();
    }

    private void loadFile() {
        if (DEBUG) Slog.d(TAG, "loadFile");
        AsyncTask.execute(() -> {
            InputStream infile = null;
            try {
                infile = mFile.openRead();
                readXml(infile);
            } catch (FileNotFoundException e) {
                Log.d(TAG, "File doesn't exist or isn't readable yet");
            } catch (IOException e) {
                Log.e(TAG, "Unable to read channel impressions", e);
            } catch (NumberFormatException | XmlPullParserException e) {
                Log.e(TAG, "Unable to parse channel impressions", e);
            } finally {
                IoUtils.closeQuietly(infile);
            }
        });
    }

    protected void readXml(InputStream stream)
            throws XmlPullParserException, NumberFormatException, IOException {
        final XmlPullParser parser = Xml.newPullParser();
        parser.setInput(stream, StandardCharsets.UTF_8.name());
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (!TAG_ASSISTANT.equals(parser.getName())) {
                continue;
            }
            final int impressionOuterDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, impressionOuterDepth)) {
                if (!TAG_IMPRESSION.equals(parser.getName())) {
                    continue;
                }
                String key = parser.getAttributeValue(null, ATT_KEY);
                ChannelImpressions ci = createChannelImpressionsWithThresholds();
                ci.populateFromXml(parser);
                synchronized (mkeyToImpressions) {
                    ci.append(mkeyToImpressions.get(key));
                    mkeyToImpressions.put(key, ci);
                }
            }
        }
    }

    private void saveFile() {
        AsyncTask.execute(() -> {
            final FileOutputStream stream;
            try {
                stream = mFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to save policy file", e);
                return;
            }
            try {
                final XmlSerializer out = new FastXmlSerializer();
                out.setOutput(stream, StandardCharsets.UTF_8.name());
                writeXml(out);
                mFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to save impressions file, restoring backup", e);
                mFile.failWrite(stream);
            }
        });
    }

    protected void writeXml(XmlSerializer out) throws IOException {
        out.startDocument(null, true);
        out.startTag(null, TAG_ASSISTANT);
        out.attribute(null, ATTR_VERSION, Integer.toString(DB_VERSION));
        synchronized (mkeyToImpressions) {
            for (Map.Entry<String, ChannelImpressions> entry
                    : mkeyToImpressions.entrySet()) {
                // TODO: ensure channel still exists
                out.startTag(null, TAG_IMPRESSION);
                out.attribute(null, ATT_KEY, entry.getKey());
                entry.getValue().writeXml(out);
                out.endTag(null, TAG_IMPRESSION);
            }
        }
        out.endTag(null, TAG_ASSISTANT);
        out.endDocument();
    }

    @Override
    public Adjustment onNotificationEnqueued(StatusBarNotification sbn,
            NotificationChannel channel) {
        if (DEBUG) Log.i(TAG, "ENQUEUED " + sbn.getKey() + " on " + channel.getId());
        if (!isForCurrentUser(sbn)) {
            return null;
        }
        NotificationEntry entry =
                new NotificationEntry(mPackageManager, sbn, channel, mSmsHelper);
        SmartActionsHelper.SmartSuggestions suggestions = mSmartActionsHelper.suggest(entry);
        if (DEBUG) {
            Log.d(TAG, String.format("Creating Adjustment for %s, with %d actions, and %d replies.",
                    sbn.getKey(), suggestions.actions.size(), suggestions.replies.size()));
        }
        return createEnqueuedNotificationAdjustment(
                entry, suggestions.actions, suggestions.replies);
    }

    /** A convenience helper for creating an adjustment for an SBN. */
    @VisibleForTesting
    @Nullable
    Adjustment createEnqueuedNotificationAdjustment(
            @NonNull NotificationEntry entry,
            @NonNull ArrayList<Notification.Action> smartActions,
            @NonNull ArrayList<CharSequence> smartReplies) {
        Bundle signals = new Bundle();

        if (!smartActions.isEmpty()) {
            signals.putParcelableArrayList(Adjustment.KEY_CONTEXTUAL_ACTIONS, smartActions);
        }
        if (!smartReplies.isEmpty()) {
            signals.putCharSequenceArrayList(Adjustment.KEY_TEXT_REPLIES, smartReplies);
        }
        if (mSettings.mNewInterruptionModel) {
            if (mNotificationCategorizer.shouldSilence(entry)) {
                final int importance = entry.getImportance() < IMPORTANCE_LOW
                        ? entry.getImportance() : IMPORTANCE_LOW;
                signals.putInt(KEY_IMPORTANCE, importance);
            }
        }

        return new Adjustment(
                entry.getSbn().getPackageName(),
                entry.getSbn().getKey(),
                signals,
                "",
                entry.getSbn().getUserId());
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (DEBUG) Log.i(TAG, "POSTED " + sbn.getKey());
        try {
            if (!isForCurrentUser(sbn)) {
                return;
            }
            Ranking ranking = getRanking(sbn.getKey(), rankingMap);
            if (ranking != null && ranking.getChannel() != null) {
                NotificationEntry entry = new NotificationEntry(mPackageManager,
                        sbn, ranking.getChannel(), mSmsHelper);
                String key = getKey(
                        sbn.getPackageName(), sbn.getUserId(), ranking.getChannel().getId());
                ChannelImpressions ci = mkeyToImpressions.getOrDefault(key,
                        createChannelImpressionsWithThresholds());
                if (ranking.getImportance() > IMPORTANCE_MIN && ci.shouldTriggerBlock()) {
                    adjustNotification(createNegativeAdjustment(
                            sbn.getPackageName(), sbn.getKey(), sbn.getUserId()));
                }
                mkeyToImpressions.put(key, ci);
                mLiveNotifications.put(sbn.getKey(), entry);
                mAgingHelper.onNotificationPosted(entry);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error occurred processing post", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
            NotificationStats stats, int reason) {
        try {
            if (!isForCurrentUser(sbn)) {
                return;
            }

            mAgingHelper.onNotificationRemoved(sbn.getKey());

            boolean updatedImpressions = false;
            String channelId = mLiveNotifications.remove(sbn.getKey()).getChannel().getId();
            String key = getKey(sbn.getPackageName(), sbn.getUserId(), channelId);
            synchronized (mkeyToImpressions) {
                ChannelImpressions ci = mkeyToImpressions.getOrDefault(key,
                        createChannelImpressionsWithThresholds());
                if (stats != null && stats.hasSeen()) {
                    ci.incrementViews();
                    updatedImpressions = true;
                }
                if (PREJUDICAL_DISMISSALS.contains(reason)) {
                    if ((!sbn.isAppGroup() || sbn.getNotification().isGroupChild())
                            && !stats.hasInteracted()
                            && stats.getDismissalSurface() != NotificationStats.DISMISSAL_AOD
                            && stats.getDismissalSurface() != NotificationStats.DISMISSAL_PEEK
                            && stats.getDismissalSurface() != NotificationStats.DISMISSAL_OTHER) {
                        if (DEBUG) Log.i(TAG, "increment dismissals " + key);
                        ci.incrementDismissals();
                        updatedImpressions = true;
                    } else {
                        if (DEBUG) Slog.i(TAG, "reset streak " + key);
                        if (ci.getStreak() > 0) {
                            updatedImpressions = true;
                        }
                        ci.resetStreak();
                    }
                }
                mkeyToImpressions.put(key, ci);
            }
            if (updatedImpressions) {
                saveFile();
            }
        } catch (Throwable e) {
            Slog.e(TAG, "Error occurred processing removal of " + sbn, e);
        }
    }

    @Override
    public void onNotificationSnoozedUntilContext(StatusBarNotification sbn,
            String snoozeCriterionId) {
    }

    @Override
    public void onNotificationsSeen(List<String> keys) {
        try {
            if (keys == null) {
                return;
            }

            for (String key : keys) {
                NotificationEntry entry = mLiveNotifications.get(key);

                if (entry != null) {
                    entry.setSeen();
                    mAgingHelper.onNotificationSeen(entry);
                }
            }
        } catch (Throwable e) {
            Slog.e(TAG, "Error occurred processing seen", e);
        }
    }

    @Override
    public void onNotificationExpansionChanged(@NonNull String key, boolean isUserAction,
            boolean isExpanded) {
        if (DEBUG) {
            Log.d(TAG, "onNotificationExpansionChanged() called with: key = [" + key
                    + "], isUserAction = [" + isUserAction + "], isExpanded = [" + isExpanded
                    + "]");
        }
        NotificationEntry entry = mLiveNotifications.get(key);

        if (entry != null) {
            entry.setExpanded(isExpanded);
            mSmartActionsHelper.onNotificationExpansionChanged(entry, isUserAction, isExpanded);
        }
    }

    @Override
    public void onNotificationDirectReplied(@NonNull String key) {
        if (DEBUG) Log.i(TAG, "onNotificationDirectReplied " + key);
        mSmartActionsHelper.onNotificationDirectReplied(key);
    }

    @Override
    public void onSuggestedReplySent(@NonNull String key, @NonNull CharSequence reply,
            @Source int source) {
        if (DEBUG) {
            Log.d(TAG, "onSuggestedReplySent() called with: key = [" + key + "], reply = [" + reply
                    + "], source = [" + source + "]");
        }
        mSmartActionsHelper.onSuggestedReplySent(key, reply, source);
    }

    @Override
    public void onActionInvoked(@NonNull String key, @NonNull Notification.Action action,
            @Source int source) {
        if (DEBUG) {
            Log.d(TAG,
                    "onActionInvoked() called with: key = [" + key + "], action = [" + action.title
                            + "], source = [" + source + "]");
        }
        mSmartActionsHelper.onActionClicked(key, action, source);
    }

    @Override
    public void onListenerConnected() {
        if (DEBUG) Log.i(TAG, "CONNECTED");
        try {
            mFile = new AtomicFile(new File(new File(
                    Environment.getDataUserCePackageDirectory(
                            StorageManager.UUID_PRIVATE_INTERNAL, getUserId(), getPackageName()),
                    "assistant"), "blocking_helper_stats.xml"));
            loadFile();
            for (StatusBarNotification sbn : getActiveNotifications()) {
                onNotificationPosted(sbn);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error occurred on connection", e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (mAgingHelper != null) {
            mAgingHelper.onDestroy();
        }
    }

    private boolean isForCurrentUser(StatusBarNotification sbn) {
        return sbn != null && sbn.getUserId() == UserHandle.myUserId();
    }

    protected String getKey(String pkg, int userId, String channelId) {
        return pkg + "|" + userId + "|" + channelId;
    }

    private Ranking getRanking(String key, RankingMap rankingMap) {
        if (mFakeRanking != null) {
            return mFakeRanking;
        }
        Ranking ranking = new Ranking();
        rankingMap.getRanking(key, ranking);
        return ranking;
    }

    private Adjustment createNegativeAdjustment(String packageName, String key, int user) {
        if (DEBUG) Log.d(TAG, "User probably doesn't want " + key);
        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT, USER_SENTIMENT_NEGATIVE);
        return new Adjustment(packageName, key,  signals, "", user);
    }

    // for testing

    @VisibleForTesting
    public void setFile(AtomicFile file) {
        mFile = file;
    }

    @VisibleForTesting
    public void setFakeRanking(Ranking ranking) {
        mFakeRanking = ranking;
    }

    @VisibleForTesting
    public void setNoMan(INotificationManager noMan) {
        mNoMan = noMan;
    }

    @VisibleForTesting
    public void setContext(Context context) {
        mSystemContext = context;
    }

    @VisibleForTesting
    public void setPackageManager(IPackageManager pm) {
        mPackageManager = pm;
    }

    @VisibleForTesting
    public void setSmartActionsHelper(SmartActionsHelper smartActionsHelper) {
        mSmartActionsHelper = smartActionsHelper;
    }

    @VisibleForTesting
    public ChannelImpressions getImpressions(String key) {
        synchronized (mkeyToImpressions) {
            return mkeyToImpressions.get(key);
        }
    }

    @VisibleForTesting
    public void insertImpressions(String key, ChannelImpressions ci) {
        synchronized (mkeyToImpressions) {
            mkeyToImpressions.put(key, ci);
        }
    }

    private ChannelImpressions createChannelImpressionsWithThresholds() {
        ChannelImpressions impressions = new ChannelImpressions();
        impressions.updateThresholds(mSettings.mDismissToViewRatioLimit, mSettings.mStreakLimit);
        return impressions;
    }

    private void updateThresholds() {
        // Update all existing channel impression objects with any new limits/thresholds.
        synchronized (mkeyToImpressions) {
            for (ChannelImpressions channelImpressions: mkeyToImpressions.values()) {
                channelImpressions.updateThresholds(
                        mSettings.mDismissToViewRatioLimit, mSettings.mStreakLimit);
            }
        }
    }

    protected final class AgingCallback implements Callback {
        @Override
        public void sendAdjustment(String key, int newImportance) {
            if (AGE_NOTIFICATIONS) {
                NotificationEntry entry = mLiveNotifications.get(key);
                if (entry != null) {
                    Bundle bundle = new Bundle();
                    bundle.putInt(KEY_IMPORTANCE, newImportance);
                    Adjustment adjustment = new Adjustment(entry.getSbn().getPackageName(), key,
                            bundle, "aging", entry.getSbn().getUserId());
                    adjustNotification(adjustment);
                }
            }
        }
    }

}

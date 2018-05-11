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

import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;

import android.app.INotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.ext.services.R;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.NotificationAssistantService;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;

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
import java.util.Map;

/**
 * Notification assistant that provides guidance on notification channel blocking
 */
public class Assistant extends NotificationAssistantService {
    private static final String TAG = "ExtAssistant";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

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

    private float mDismissToViewRatioLimit;
    private int mStreakLimit;

    // key : impressions tracker
    // TODO: prune deleted channels and apps
    final ArrayMap<String, ChannelImpressions> mkeyToImpressions = new ArrayMap<>();
    // SBN key : channel id
    ArrayMap<String, String> mLiveNotifications = new ArrayMap<>();

    private Ranking mFakeRanking = null;
    private AtomicFile mFile = null;

    public Assistant() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Contexts are correctly hooked up by the creation step, which is required for the observer
        // to be hooked up/initialized.
        new SettingsObserver(mHandler);
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

    private void saveFile() throws IOException {
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
    public Adjustment onNotificationEnqueued(StatusBarNotification sbn) {
        if (DEBUG) Log.i(TAG, "ENQUEUED " + sbn.getKey());
        return null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (DEBUG) Log.i(TAG, "POSTED " + sbn.getKey());
        try {
            Ranking ranking = getRanking(sbn.getKey(), rankingMap);
            if (ranking != null && ranking.getChannel() != null) {
                String key = getKey(
                        sbn.getPackageName(), sbn.getUserId(), ranking.getChannel().getId());
                ChannelImpressions ci = mkeyToImpressions.getOrDefault(key,
                        createChannelImpressionsWithThresholds());
                if (ranking.getImportance() > IMPORTANCE_MIN && ci.shouldTriggerBlock()) {
                    adjustNotification(createNegativeAdjustment(
                            sbn.getPackageName(), sbn.getKey(), sbn.getUserId()));
                }
                mkeyToImpressions.put(key, ci);
                mLiveNotifications.put(sbn.getKey(), ranking.getChannel().getId());
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error occurred processing post", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
            NotificationStats stats, int reason) {
        try {
            boolean updatedImpressions = false;
            String channelId = mLiveNotifications.remove(sbn.getKey());
            String key = getKey(sbn.getPackageName(), sbn.getUserId(), channelId);
            synchronized (mkeyToImpressions) {
                ChannelImpressions ci = mkeyToImpressions.getOrDefault(key,
                        createChannelImpressionsWithThresholds());
                if (stats.hasSeen()) {
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
            Slog.e(TAG, "Error occurred processing removal", e);
        }
    }

    @Override
    public void onNotificationSnoozedUntilContext(StatusBarNotification sbn,
            String snoozeCriterionId) {
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
        return new Adjustment(packageName, key,  signals,
                getContext().getString(R.string.prompt_block_reason), user);
    }

    // for testing

    protected void setFile(AtomicFile file) {
        mFile = file;
    }

    protected void setFakeRanking(Ranking ranking) {
        mFakeRanking = ranking;
    }

    protected void setNoMan(INotificationManager noMan) {
        mNoMan = noMan;
    }

    protected void setContext(Context context) {
        mSystemContext = context;
    }

    protected ChannelImpressions getImpressions(String key) {
        synchronized (mkeyToImpressions) {
            return mkeyToImpressions.get(key);
        }
    }

    protected void insertImpressions(String key, ChannelImpressions ci) {
        synchronized (mkeyToImpressions) {
            mkeyToImpressions.put(key, ci);
        }
    }

    private ChannelImpressions createChannelImpressionsWithThresholds() {
        ChannelImpressions impressions = new ChannelImpressions();
        impressions.updateThresholds(mDismissToViewRatioLimit, mStreakLimit);
        return impressions;
    }

    /**
     * Observer for updates on blocking helper threshold values.
     */
    private final class SettingsObserver extends ContentObserver {
        private final Uri STREAK_LIMIT_URI =
                Settings.Global.getUriFor(Settings.Global.BLOCKING_HELPER_STREAK_LIMIT);
        private final Uri DISMISS_TO_VIEW_RATIO_LIMIT_URI =
                Settings.Global.getUriFor(
                        Settings.Global.BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT);

        public SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver resolver = getApplicationContext().getContentResolver();
            resolver.registerContentObserver(
                    DISMISS_TO_VIEW_RATIO_LIMIT_URI, false, this, getUserId());
            resolver.registerContentObserver(STREAK_LIMIT_URI, false, this, getUserId());

            // Update all uris on creation.
            update(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        private void update(Uri uri) {
            ContentResolver resolver = getApplicationContext().getContentResolver();
            if (uri == null || DISMISS_TO_VIEW_RATIO_LIMIT_URI.equals(uri)) {
                mDismissToViewRatioLimit = Settings.Global.getFloat(
                        resolver, Settings.Global.BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT,
                        ChannelImpressions.DEFAULT_DISMISS_TO_VIEW_RATIO_LIMIT);
            }
            if (uri == null || STREAK_LIMIT_URI.equals(uri)) {
                mStreakLimit = Settings.Global.getInt(
                        resolver, Settings.Global.BLOCKING_HELPER_STREAK_LIMIT,
                        ChannelImpressions.DEFAULT_STREAK_LIMIT);
            }

            // Update all existing channel impression objects with any new limits/thresholds.
            synchronized (mkeyToImpressions) {
                for (ChannelImpressions channelImpressions: mkeyToImpressions.values()) {
                    channelImpressions.updateThresholds(mDismissToViewRatioLimit, mStreakLimit);
                }
            }
        }
    }
}
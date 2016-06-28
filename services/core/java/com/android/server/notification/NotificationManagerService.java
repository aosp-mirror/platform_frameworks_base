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

import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_EFFECTS;
import static android.service.notification.NotificationListenerService.TRIM_FULL;
import static android.service.notification.NotificationListenerService.TRIM_LIGHT;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.ITransientNotification;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.backup.BackupManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioSystem;
import android.media.IRingtonePlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.IConditionListener;
import android.service.notification.IConditionProvider;
import android.service.notification.INotificationListener;
import android.service.notification.IStatusBarNotificationHolder;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationRankingUpdate;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenModeConfig;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;
import com.android.server.notification.ManagedServices.ManagedServiceInfo;
import com.android.server.notification.ManagedServices.UserProfiles;
import com.android.server.statusbar.StatusBarManagerInternal;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;

/** {@hide} */
public class NotificationManagerService extends SystemService {
    static final String TAG = "NotificationService";
    static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    public static final boolean ENABLE_CHILD_NOTIFICATIONS = Build.IS_DEBUGGABLE
            && SystemProperties.getBoolean("debug.child_notifs", false);

    static final int MAX_PACKAGE_NOTIFICATIONS = 50;

    // message codes
    static final int MESSAGE_TIMEOUT = 2;
    static final int MESSAGE_SAVE_POLICY_FILE = 3;
    static final int MESSAGE_RECONSIDER_RANKING = 4;
    static final int MESSAGE_RANKING_CONFIG_CHANGE = 5;
    static final int MESSAGE_SEND_RANKING_UPDATE = 6;
    static final int MESSAGE_LISTENER_HINTS_CHANGED = 7;
    static final int MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED = 8;

    static final int LONG_DELAY = 3500; // 3.5 seconds
    static final int SHORT_DELAY = 2000; // 2 seconds

    static final long[] DEFAULT_VIBRATE_PATTERN = {0, 250, 250, 250};

    static final int VIBRATE_PATTERN_MAXLEN = 8 * 2 + 1; // up to eight bumps

    static final int DEFAULT_STREAM_TYPE = AudioManager.STREAM_NOTIFICATION;
    static final boolean SCORE_ONGOING_HIGHER = false;

    static final int JUNK_SCORE = -1000;
    static final int NOTIFICATION_PRIORITY_MULTIPLIER = 10;
    static final int SCORE_DISPLAY_THRESHOLD = Notification.PRIORITY_MIN * NOTIFICATION_PRIORITY_MULTIPLIER;

    // Notifications with scores below this will not interrupt the user, either via LED or
    // sound or vibration
    static final int SCORE_INTERRUPTION_THRESHOLD =
            Notification.PRIORITY_LOW * NOTIFICATION_PRIORITY_MULTIPLIER;

    static final boolean ENABLE_BLOCKED_NOTIFICATIONS = true;
    static final boolean ENABLE_BLOCKED_TOASTS = true;

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

    private IActivityManager mAm;
    AudioManager mAudioManager;
    AudioManagerInternal mAudioManagerInternal;
    StatusBarManagerInternal mStatusBar;
    Vibrator mVibrator;

    final IBinder mForegroundToken = new Binder();
    private WorkerHandler mHandler;
    private final HandlerThread mRankingThread = new HandlerThread("ranker",
            Process.THREAD_PRIORITY_BACKGROUND);

    private Light mNotificationLight;
    Light mAttentionLight;
    private int mDefaultNotificationColor;
    private int mDefaultNotificationLedOn;

    private int mDefaultNotificationLedOff;
    private long[] mDefaultVibrationPattern;

    private long[] mFallbackVibrationPattern;
    private boolean mUseAttentionLight;
    boolean mSystemReady;

    private boolean mDisableNotificationEffects;
    private int mCallState;
    private String mSoundNotificationKey;
    private String mVibrateNotificationKey;

    private final ArraySet<ManagedServiceInfo> mListenersDisablingEffects = new ArraySet<>();
    private ComponentName mEffectsSuppressor;
    private int mListenerHints;  // right now, all hints are global
    private int mInterruptionFilter = NotificationListenerService.INTERRUPTION_FILTER_UNKNOWN;

    // for enabling and disabling notification pulse behavior
    private boolean mScreenOn = true;
    private boolean mInCall = false;
    private boolean mNotificationPulseEnabled;

    // used as a mutex for access to all active notifications & listeners
    final ArrayList<NotificationRecord> mNotificationList =
            new ArrayList<NotificationRecord>();
    final ArrayMap<String, NotificationRecord> mNotificationsByKey =
            new ArrayMap<String, NotificationRecord>();
    final ArrayList<ToastRecord> mToastQueue = new ArrayList<ToastRecord>();
    final ArrayMap<String, NotificationRecord> mSummaryByGroupKey = new ArrayMap<>();
    final PolicyAccess mPolicyAccess = new PolicyAccess();

    // The last key in this list owns the hardware.
    ArrayList<String> mLights = new ArrayList<>();

    private AppOpsManager mAppOps;
    private UsageStatsManagerInternal mAppUsageStats;

    private Archive mArchive;

    // Persistent storage for notification policy
    private AtomicFile mPolicyFile;

    // Temporary holder for <blocked-packages> config coming from old policy files.
    private HashSet<String> mBlockedPackages = new HashSet<String>();

    private static final int DB_VERSION = 1;

    private static final String TAG_NOTIFICATION_POLICY = "notification-policy";
    private static final String ATTR_VERSION = "version";

    // Obsolete:  converted if present, but not resaved to disk.
    private static final String TAG_BLOCKED_PKGS = "blocked-packages";
    private static final String TAG_PACKAGE = "package";
    private static final String ATTR_NAME = "name";

    private RankingHelper mRankingHelper;

    private final UserProfiles mUserProfiles = new UserProfiles();
    private NotificationListeners mListeners;
    private ConditionProviders mConditionProviders;
    private NotificationUsageStats mUsageStats;

    private static final int MY_UID = Process.myUid();
    private static final int MY_PID = Process.myPid();
    private static final int REASON_DELEGATE_CLICK = 1;
    private static final int REASON_DELEGATE_CANCEL = 2;
    private static final int REASON_DELEGATE_CANCEL_ALL = 3;
    private static final int REASON_DELEGATE_ERROR = 4;
    private static final int REASON_PACKAGE_CHANGED = 5;
    private static final int REASON_USER_STOPPED = 6;
    private static final int REASON_PACKAGE_BANNED = 7;
    private static final int REASON_NOMAN_CANCEL = 8;
    private static final int REASON_NOMAN_CANCEL_ALL = 9;
    private static final int REASON_LISTENER_CANCEL = 10;
    private static final int REASON_LISTENER_CANCEL_ALL = 11;
    private static final int REASON_GROUP_SUMMARY_CANCELED = 12;
    private static final int REASON_GROUP_OPTIMIZATION = 13;

    private static class Archive {
        final int mBufferSize;
        final ArrayDeque<StatusBarNotification> mBuffer;

        public Archive(int size) {
            mBufferSize = size;
            mBuffer = new ArrayDeque<StatusBarNotification>(mBufferSize);
        }

        public String toString() {
            final StringBuilder sb = new StringBuilder();
            final int N = mBuffer.size();
            sb.append("Archive (");
            sb.append(N);
            sb.append(" notification");
            sb.append((N==1)?")":"s)");
            return sb.toString();
        }

        public void record(StatusBarNotification nr) {
            if (mBuffer.size() == mBufferSize) {
                mBuffer.removeFirst();
            }

            // We don't want to store the heavy bits of the notification in the archive,
            // but other clients in the system process might be using the object, so we
            // store a (lightened) copy.
            mBuffer.addLast(nr.cloneLight());
        }

        public Iterator<StatusBarNotification> descendingIterator() {
            return mBuffer.descendingIterator();
        }

        public StatusBarNotification[] getArray(int count) {
            if (count == 0) count = mBufferSize;
            final StatusBarNotification[] a
                    = new StatusBarNotification[Math.min(count, mBuffer.size())];
            Iterator<StatusBarNotification> iter = descendingIterator();
            int i=0;
            while (iter.hasNext() && i < count) {
                a[i++] = iter.next();
            }
            return a;
        }

    }

    private void readPolicyXml(InputStream stream, boolean forRestore)
            throws XmlPullParserException, NumberFormatException, IOException {
        final XmlPullParser parser = Xml.newPullParser();
        parser.setInput(stream, StandardCharsets.UTF_8.name());

        int type;
        String tag;
        int version = DB_VERSION;
        while ((type = parser.next()) != END_DOCUMENT) {
            tag = parser.getName();
            if (type == START_TAG) {
                if (TAG_NOTIFICATION_POLICY.equals(tag)) {
                    version = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VERSION));
                } else if (TAG_BLOCKED_PKGS.equals(tag)) {
                    while ((type = parser.next()) != END_DOCUMENT) {
                        tag = parser.getName();
                        if (TAG_PACKAGE.equals(tag)) {
                            mBlockedPackages.add(
                                    parser.getAttributeValue(null, ATTR_NAME));
                        } else if (TAG_BLOCKED_PKGS.equals(tag) && type == END_TAG) {
                            break;
                        }
                    }
                }
            }
            mZenModeHelper.readXml(parser, forRestore);
            mRankingHelper.readXml(parser, forRestore);
        }
    }

    private void loadPolicyFile() {
        if (DBG) Slog.d(TAG, "loadPolicyFile");
        synchronized(mPolicyFile) {
            mBlockedPackages.clear();

            FileInputStream infile = null;
            try {
                infile = mPolicyFile.openRead();
                readPolicyXml(infile, false /*forRestore*/);
            } catch (FileNotFoundException e) {
                // No data yet
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

    public void savePolicyFile() {
        mHandler.removeMessages(MESSAGE_SAVE_POLICY_FILE);
        mHandler.sendEmptyMessage(MESSAGE_SAVE_POLICY_FILE);
    }

    private void handleSavePolicyFile() {
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
                writePolicyXml(stream, false /*forBackup*/);
                mPolicyFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to save policy file, restoring backup", e);
                mPolicyFile.failWrite(stream);
            }
        }
        BackupManager.dataChanged(getContext().getPackageName());
    }

    private void writePolicyXml(OutputStream stream, boolean forBackup) throws IOException {
        final XmlSerializer out = new FastXmlSerializer();
        out.setOutput(stream, StandardCharsets.UTF_8.name());
        out.startDocument(null, true);
        out.startTag(null, TAG_NOTIFICATION_POLICY);
        out.attribute(null, ATTR_VERSION, Integer.toString(DB_VERSION));
        mZenModeHelper.writeXml(out, forBackup);
        mRankingHelper.writeXml(out, forBackup);
        out.endTag(null, TAG_NOTIFICATION_POLICY);
        out.endDocument();
    }

    /** Use this when you actually want to post a notification or toast.
     *
     * Unchecked. Not exposed via Binder, but can be called in the course of enqueue*().
     */
    private boolean noteNotificationOp(String pkg, int uid) {
        if (mAppOps.noteOpNoThrow(AppOpsManager.OP_POST_NOTIFICATION, uid, pkg)
                != AppOpsManager.MODE_ALLOWED) {
            Slog.v(TAG, "notifications are disabled by AppOps for " + pkg);
            return false;
        }
        return true;
    }

    /** Use this to check if a package can post a notification or toast. */
    private boolean checkNotificationOp(String pkg, int uid) {
        return mAppOps.checkOp(AppOpsManager.OP_POST_NOTIFICATION, uid, pkg)
                == AppOpsManager.MODE_ALLOWED;
    }

    private static final class ToastRecord
    {
        final int pid;
        final String pkg;
        final ITransientNotification callback;
        int duration;

        ToastRecord(int pid, String pkg, ITransientNotification callback, int duration)
        {
            this.pid = pid;
            this.pkg = pkg;
            this.callback = callback;
            this.duration = duration;
        }

        void update(int duration) {
            this.duration = duration;
        }

        void dump(PrintWriter pw, String prefix, DumpFilter filter) {
            if (filter != null && !filter.matches(pkg)) return;
            pw.println(prefix + this);
        }

        @Override
        public final String toString()
        {
            return "ToastRecord{"
                + Integer.toHexString(System.identityHashCode(this))
                + " pkg=" + pkg
                + " callback=" + callback
                + " duration=" + duration;
        }
    }

    private final NotificationDelegate mNotificationDelegate = new NotificationDelegate() {

        @Override
        public void onSetDisabled(int status) {
            synchronized (mNotificationList) {
                mDisableNotificationEffects =
                        (status & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0;
                if (disableNotificationEffects(null) != null) {
                    // cancel whatever's going on
                    long identity = Binder.clearCallingIdentity();
                    try {
                        final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                        if (player != null) {
                            player.stopAsync();
                        }
                    } catch (RemoteException e) {
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }

                    identity = Binder.clearCallingIdentity();
                    try {
                        mVibrator.cancel();
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }
        }

        @Override
        public void onClearAll(int callingUid, int callingPid, int userId) {
            synchronized (mNotificationList) {
                cancelAllLocked(callingUid, callingPid, userId, REASON_DELEGATE_CANCEL_ALL, null,
                        /*includeCurrentProfiles*/ true);
            }
        }

        @Override
        public void onNotificationClick(int callingUid, int callingPid, String key) {
            synchronized (mNotificationList) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r == null) {
                    Log.w(TAG, "No notification with key: " + key);
                    return;
                }
                final long now = System.currentTimeMillis();
                EventLogTags.writeNotificationClicked(key,
                        r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now));

                StatusBarNotification sbn = r.sbn;
                cancelNotification(callingUid, callingPid, sbn.getPackageName(), sbn.getTag(),
                        sbn.getId(), Notification.FLAG_AUTO_CANCEL,
                        Notification.FLAG_FOREGROUND_SERVICE, false, r.getUserId(),
                        REASON_DELEGATE_CLICK, null);
            }
        }

        @Override
        public void onNotificationActionClick(int callingUid, int callingPid, String key,
                int actionIndex) {
            synchronized (mNotificationList) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r == null) {
                    Log.w(TAG, "No notification with key: " + key);
                    return;
                }
                final long now = System.currentTimeMillis();
                EventLogTags.writeNotificationActionClicked(key, actionIndex,
                        r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now));
                // TODO: Log action click via UsageStats.
            }
        }

        @Override
        public void onNotificationClear(int callingUid, int callingPid,
                String pkg, String tag, int id, int userId) {
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0,
                    Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE,
                    true, userId, REASON_DELEGATE_CANCEL, null);
        }

        @Override
        public void onPanelRevealed(boolean clearEffects, int items) {
            EventLogTags.writeNotificationPanelRevealed(items);
            if (clearEffects) {
                clearEffects();
            }
        }

        @Override
        public void onPanelHidden() {
            EventLogTags.writeNotificationPanelHidden();
        }

        @Override
        public void clearEffects() {
            synchronized (mNotificationList) {
                if (DBG) Slog.d(TAG, "clearEffects");

                // sound
                mSoundNotificationKey = null;

                long identity = Binder.clearCallingIdentity();
                try {
                    final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                    if (player != null) {
                        player.stopAsync();
                    }
                } catch (RemoteException e) {
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

                // vibrate
                mVibrateNotificationKey = null;
                identity = Binder.clearCallingIdentity();
                try {
                    mVibrator.cancel();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

                // light
                mLights.clear();
                updateLightsLocked();
            }
        }

        @Override
        public void onNotificationError(int callingUid, int callingPid, String pkg, String tag, int id,
                int uid, int initialPid, String message, int userId) {
            Slog.d(TAG, "onNotification error pkg=" + pkg + " tag=" + tag + " id=" + id
                    + "; will crashApplication(uid=" + uid + ", pid=" + initialPid + ")");
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0, 0, false, userId,
                    REASON_DELEGATE_ERROR, null);
            long ident = Binder.clearCallingIdentity();
            try {
                ActivityManagerNative.getDefault().crashApplication(uid, initialPid, pkg,
                        "Bad notification posted from package " + pkg
                        + ": " + message);
            } catch (RemoteException e) {
            }
            Binder.restoreCallingIdentity(ident);
        }

        @Override
        public void onNotificationVisibilityChanged(NotificationVisibility[] newlyVisibleKeys,
                NotificationVisibility[] noLongerVisibleKeys) {
            synchronized (mNotificationList) {
                for (NotificationVisibility nv : newlyVisibleKeys) {
                    NotificationRecord r = mNotificationsByKey.get(nv.key);
                    if (r == null) continue;
                    r.setVisibility(true, nv.rank);
                    nv.recycle();
                }
                // Note that we might receive this event after notifications
                // have already left the system, e.g. after dismissing from the
                // shade. Hence not finding notifications in
                // mNotificationsByKey is not an exceptional condition.
                for (NotificationVisibility nv : noLongerVisibleKeys) {
                    NotificationRecord r = mNotificationsByKey.get(nv.key);
                    if (r == null) continue;
                    r.setVisibility(false, nv.rank);
                    nv.recycle();
                }
            }
        }

        @Override
        public void onNotificationExpansionChanged(String key,
                boolean userAction, boolean expanded) {
            synchronized (mNotificationList) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    r.stats.onExpansionChanged(userAction, expanded);
                    final long now = System.currentTimeMillis();
                    EventLogTags.writeNotificationExpansion(key,
                            userAction ? 1 : 0, expanded ? 1 : 0,
                            r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now));
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

            boolean queryRestart = false;
            boolean queryRemove = false;
            boolean packageChanged = false;
            boolean cancelNotifications = true;

            if (action.equals(Intent.ACTION_PACKAGE_ADDED)
                    || (queryRemove=action.equals(Intent.ACTION_PACKAGE_REMOVED))
                    || action.equals(Intent.ACTION_PACKAGE_RESTARTED)
                    || (packageChanged=action.equals(Intent.ACTION_PACKAGE_CHANGED))
                    || (queryRestart=action.equals(Intent.ACTION_QUERY_PACKAGE_RESTART))
                    || action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)) {
                int changeUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_ALL);
                String pkgList[] = null;
                boolean queryReplace = queryRemove &&
                        intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                if (DBG) Slog.i(TAG, "action=" + action + " queryReplace=" + queryReplace);
                if (action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                } else if (queryRestart) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
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
                            final IPackageManager pm = AppGlobals.getPackageManager();
                            final int enabled = pm.getApplicationEnabledSetting(pkgName,
                                    changeUserId != UserHandle.USER_ALL ? changeUserId :
                                    UserHandle.USER_OWNER);
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
                }

                if (pkgList != null && (pkgList.length > 0)) {
                    for (String pkgName : pkgList) {
                        if (cancelNotifications) {
                            cancelAllNotificationsInt(MY_UID, MY_PID, pkgName, 0, 0, !queryRestart,
                                    changeUserId, REASON_PACKAGE_CHANGED, null);
                        }
                    }
                }
                mListeners.onPackagesChanged(queryReplace, pkgList);
                mConditionProviders.onPackagesChanged(queryReplace, pkgList);
                mRankingHelper.onPackagesChanged(queryReplace, pkgList);
            }
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                // Keep track of screen on/off state, but do not turn off the notification light
                // until user passes through the lock screen or views the notification.
                mScreenOn = true;
                updateNotificationPulse();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mScreenOn = false;
                updateNotificationPulse();
            } else if (action.equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                mInCall = TelephonyManager.EXTRA_STATE_OFFHOOK
                        .equals(intent.getStringExtra(TelephonyManager.EXTRA_STATE));
                updateNotificationPulse();
            } else if (action.equals(Intent.ACTION_USER_STOPPED)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userHandle >= 0) {
                    cancelAllNotificationsInt(MY_UID, MY_PID, null, 0, 0, true, userHandle,
                            REASON_USER_STOPPED, null);
                }
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                // turn off LED when user passes through lock screen
                mNotificationLight.turnOff();
                mStatusBar.notificationLightOff();
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                final int user = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                // reload per-user settings
                mSettingsObserver.update(null);
                mUserProfiles.updateCache(context);
                // Refresh managed services
                mConditionProviders.onUserSwitched(user);
                mListeners.onUserSwitched(user);
                mZenModeHelper.onUserSwitched(user);
            } else if (action.equals(Intent.ACTION_USER_ADDED)) {
                mUserProfiles.updateCache(context);
            } else if (action.equals(Intent.ACTION_USER_REMOVED)) {
                final int user = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                mZenModeHelper.onUserRemoved(user);
            }
        }
    };

    private final class SettingsObserver extends ContentObserver {
        private final Uri NOTIFICATION_LIGHT_PULSE_URI
                = Settings.System.getUriFor(Settings.System.NOTIFICATION_LIGHT_PULSE);

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(NOTIFICATION_LIGHT_PULSE_URI,
                    false, this, UserHandle.USER_ALL);
            update(null);
        }

        @Override public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        public void update(Uri uri) {
            ContentResolver resolver = getContext().getContentResolver();
            if (uri == null || NOTIFICATION_LIGHT_PULSE_URI.equals(uri)) {
                boolean pulseEnabled = Settings.System.getInt(resolver,
                            Settings.System.NOTIFICATION_LIGHT_PULSE, 0) != 0;
                if (mNotificationPulseEnabled != pulseEnabled) {
                    mNotificationPulseEnabled = pulseEnabled;
                    updateNotificationPulse();
                }
            }
        }
    }

    private SettingsObserver mSettingsObserver;
    private ZenModeHelper mZenModeHelper;

    private final Runnable mBuzzBeepBlinked = new Runnable() {
        @Override
        public void run() {
            mStatusBar.buzzBeepBlinked();
        }
    };

    static long[] getLongArray(Resources r, int resid, int maxlen, long[] def) {
        int[] ar = r.getIntArray(resid);
        if (ar == null) {
            return def;
        }
        final int len = ar.length > maxlen ? maxlen : ar.length;
        long[] out = new long[len];
        for (int i=0; i<len; i++) {
            out[i] = ar[i];
        }
        return out;
    }

    public NotificationManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        Resources resources = getContext().getResources();

        mAm = ActivityManagerNative.getDefault();
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mVibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        mAppUsageStats = LocalServices.getService(UsageStatsManagerInternal.class);

        mHandler = new WorkerHandler();
        mRankingThread.start();
        String[] extractorNames;
        try {
            extractorNames = resources.getStringArray(R.array.config_notificationSignalExtractors);
        } catch (Resources.NotFoundException e) {
            extractorNames = new String[0];
        }
        mUsageStats = new NotificationUsageStats(getContext());
        mRankingHelper = new RankingHelper(getContext(),
                new RankingWorkerHandler(mRankingThread.getLooper()),
                mUsageStats,
                extractorNames);
        mConditionProviders = new ConditionProviders(getContext(), mHandler, mUserProfiles);
        mZenModeHelper = new ZenModeHelper(getContext(), mHandler.getLooper(), mConditionProviders);
        mZenModeHelper.addCallback(new ZenModeHelper.Callback() {
            @Override
            public void onConfigChanged() {
                savePolicyFile();
            }

            @Override
            void onZenModeChanged() {
                sendRegisteredOnlyBroadcast(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
                synchronized(mNotificationList) {
                    updateInterruptionFilterLocked();
                }
            }

            @Override
            void onPolicyChanged() {
                sendRegisteredOnlyBroadcast(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED);
            }
        });
        final File systemDir = new File(Environment.getDataDirectory(), "system");
        mPolicyFile = new AtomicFile(new File(systemDir, "notification_policy.xml"));

        importOldBlockDb();

        mListeners = new NotificationListeners();
        mStatusBar = getLocalService(StatusBarManagerInternal.class);
        mStatusBar.setNotificationDelegate(mNotificationDelegate);

        final LightsManager lights = getLocalService(LightsManager.class);
        mNotificationLight = lights.getLight(LightsManager.LIGHT_ID_NOTIFICATIONS);
        mAttentionLight = lights.getLight(LightsManager.LIGHT_ID_ATTENTION);

        mDefaultNotificationColor = resources.getColor(
                R.color.config_defaultNotificationColor);
        mDefaultNotificationLedOn = resources.getInteger(
                R.integer.config_defaultNotificationLedOn);
        mDefaultNotificationLedOff = resources.getInteger(
                R.integer.config_defaultNotificationLedOff);

        mDefaultVibrationPattern = getLongArray(resources,
                R.array.config_defaultNotificationVibePattern,
                VIBRATE_PATTERN_MAXLEN,
                DEFAULT_VIBRATE_PATTERN);

        mFallbackVibrationPattern = getLongArray(resources,
                R.array.config_notificationFallbackVibePattern,
                VIBRATE_PATTERN_MAXLEN,
                DEFAULT_VIBRATE_PATTERN);

        mUseAttentionLight = resources.getBoolean(R.bool.config_useAttentionLight);

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

        mUserProfiles.updateCache(getContext());
        listenForCallState();

        // register for various Intents
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_STOPPED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        getContext().registerReceiver(mIntentReceiver, filter);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        pkgFilter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
        pkgFilter.addDataScheme("package");
        getContext().registerReceiverAsUser(mPackageIntentReceiver, UserHandle.ALL, pkgFilter, null,
                null);

        IntentFilter sdFilter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        getContext().registerReceiverAsUser(mPackageIntentReceiver, UserHandle.ALL, sdFilter, null,
                null);

        mSettingsObserver = new SettingsObserver(mHandler);

        mArchive = new Archive(resources.getInteger(
                R.integer.config_notificationServiceArchiveSize));

        publishBinderService(Context.NOTIFICATION_SERVICE, mService);
        publishLocalService(NotificationManagerInternal.class, mInternalService);
    }

    private void sendRegisteredOnlyBroadcast(String action) {
        getContext().sendBroadcastAsUser(new Intent(action)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY), UserHandle.ALL, null);
    }

    /**
     * Read the old XML-based app block database and import those blockages into the AppOps system.
     */
    private void importOldBlockDb() {
        loadPolicyFile();

        PackageManager pm = getContext().getPackageManager();
        for (String pkg : mBlockedPackages) {
            PackageInfo info = null;
            try {
                info = pm.getPackageInfo(pkg, 0);
                setNotificationsEnabledForPackageImpl(pkg, info.applicationInfo.uid, false);
            } catch (NameNotFoundException e) {
                // forget you
            }
        }
        mBlockedPackages.clear();
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            // no beeping until we're basically done booting
            mSystemReady = true;

            // Grab our optional AudioService
            mAudioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            mAudioManagerInternal = getLocalService(AudioManagerInternal.class);
            mZenModeHelper.onSystemReady();
        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            // This observer will force an update when observe is called, causing us to
            // bind to listener services.
            mSettingsObserver.observe();
            mListeners.onBootPhaseAppsCanStart();
            mConditionProviders.onBootPhaseAppsCanStart();
        }
    }

    void setNotificationsEnabledForPackageImpl(String pkg, int uid, boolean enabled) {
        Slog.v(TAG, (enabled?"en":"dis") + "abling notifications for " + pkg);

        mAppOps.setMode(AppOpsManager.OP_POST_NOTIFICATION, uid, pkg,
                enabled ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED);

        // Now, cancel any outstanding notifications that are part of a just-disabled app
        if (ENABLE_BLOCKED_NOTIFICATIONS && !enabled) {
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, 0, 0, true, UserHandle.getUserId(uid),
                    REASON_PACKAGE_BANNED, null);
        }
    }

    private void updateListenerHintsLocked() {
        final int hints = mListenersDisablingEffects.isEmpty() ? 0 : HINT_HOST_DISABLE_EFFECTS;
        if (hints == mListenerHints) return;
        ZenLog.traceListenerHintsChanged(mListenerHints, hints, mListenersDisablingEffects.size());
        mListenerHints = hints;
        scheduleListenerHintsChanged(hints);
    }

    private void updateEffectsSuppressorLocked() {
        final ComponentName suppressor = !mListenersDisablingEffects.isEmpty()
                ? mListenersDisablingEffects.valueAt(0).component : null;
        if (Objects.equals(suppressor, mEffectsSuppressor)) return;
        ZenLog.traceEffectsSuppressorChanged(mEffectsSuppressor, suppressor);
        mEffectsSuppressor = suppressor;
        mZenModeHelper.setEffectsSuppressed(suppressor != null);
        sendRegisteredOnlyBroadcast(NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED);
    }

    private void updateInterruptionFilterLocked() {
        int interruptionFilter = mZenModeHelper.getZenModeListenerInterruptionFilter();
        if (interruptionFilter == mInterruptionFilter) return;
        mInterruptionFilter = interruptionFilter;
        scheduleInterruptionFilterChanged(interruptionFilter);
    }

    private final IBinder mService = new INotificationManager.Stub() {
        // Toasts
        // ============================================================================

        @Override
        public void enqueueToast(String pkg, ITransientNotification callback, int duration)
        {
            if (DBG) {
                Slog.i(TAG, "enqueueToast pkg=" + pkg + " callback=" + callback
                        + " duration=" + duration);
            }

            if (pkg == null || callback == null) {
                Slog.e(TAG, "Not doing toast. pkg=" + pkg + " callback=" + callback);
                return ;
            }

            final boolean isSystemToast = isCallerSystem() || ("android".equals(pkg));

            if (ENABLE_BLOCKED_TOASTS && !noteNotificationOp(pkg, Binder.getCallingUid())) {
                if (!isSystemToast) {
                    Slog.e(TAG, "Suppressing toast from package " + pkg + " by user request.");
                    return;
                }
            }

            synchronized (mToastQueue) {
                int callingPid = Binder.getCallingPid();
                long callingId = Binder.clearCallingIdentity();
                try {
                    ToastRecord record;
                    int index = indexOfToastLocked(pkg, callback);
                    // If it's already in the queue, we update it in place, we don't
                    // move it to the end of the queue.
                    if (index >= 0) {
                        record = mToastQueue.get(index);
                        record.update(duration);
                    } else {
                        // Limit the number of toasts that any given package except the android
                        // package can enqueue.  Prevents DOS attacks and deals with leaks.
                        if (!isSystemToast) {
                            int count = 0;
                            final int N = mToastQueue.size();
                            for (int i=0; i<N; i++) {
                                 final ToastRecord r = mToastQueue.get(i);
                                 if (r.pkg.equals(pkg)) {
                                     count++;
                                     if (count >= MAX_PACKAGE_NOTIFICATIONS) {
                                         Slog.e(TAG, "Package has already posted " + count
                                                + " toasts. Not showing more. Package=" + pkg);
                                         return;
                                     }
                                 }
                            }
                        }

                        record = new ToastRecord(callingPid, pkg, callback, duration);
                        mToastQueue.add(record);
                        index = mToastQueue.size() - 1;
                        keepProcessAliveLocked(callingPid);
                    }
                    // If it's at index 0, it's the current toast.  It doesn't matter if it's
                    // new or just been updated.  Call back and tell it to show itself.
                    // If the callback fails, this will remove it from the list, so don't
                    // assume that it's valid after this.
                    if (index == 0) {
                        showNextToastLocked();
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
        }

        @Override
        public void cancelToast(String pkg, ITransientNotification callback) {
            Slog.i(TAG, "cancelToast pkg=" + pkg + " callback=" + callback);

            if (pkg == null || callback == null) {
                Slog.e(TAG, "Not cancelling notification. pkg=" + pkg + " callback=" + callback);
                return ;
            }

            synchronized (mToastQueue) {
                long callingId = Binder.clearCallingIdentity();
                try {
                    int index = indexOfToastLocked(pkg, callback);
                    if (index >= 0) {
                        cancelToastLocked(index);
                    } else {
                        Slog.w(TAG, "Toast already cancelled. pkg=" + pkg
                                + " callback=" + callback);
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
        }

        @Override
        public void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id,
                Notification notification, int[] idOut, int userId) throws RemoteException {
            enqueueNotificationInternal(pkg, opPkg, Binder.getCallingUid(),
                    Binder.getCallingPid(), tag, id, notification, idOut, userId);
        }

        @Override
        public void cancelNotificationWithTag(String pkg, String tag, int id, int userId) {
            checkCallerIsSystemOrSameApp(pkg);
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, true, false, "cancelNotificationWithTag", pkg);
            // Don't allow client applications to cancel foreground service notis.
            cancelNotification(Binder.getCallingUid(), Binder.getCallingPid(), pkg, tag, id, 0,
                    Binder.getCallingUid() == Process.SYSTEM_UID
                    ? 0 : Notification.FLAG_FOREGROUND_SERVICE, false, userId, REASON_NOMAN_CANCEL,
                    null);
        }

        @Override
        public void cancelAllNotifications(String pkg, int userId) {
            checkCallerIsSystemOrSameApp(pkg);

            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, true, false, "cancelAllNotifications", pkg);

            // Calling from user space, don't allow the canceling of actively
            // running foreground services.
            cancelAllNotificationsInt(Binder.getCallingUid(), Binder.getCallingPid(),
                    pkg, 0, Notification.FLAG_FOREGROUND_SERVICE, true, userId,
                    REASON_NOMAN_CANCEL_ALL, null);
        }

        @Override
        public void setNotificationsEnabledForPackage(String pkg, int uid, boolean enabled) {
            checkCallerIsSystem();

            setNotificationsEnabledForPackageImpl(pkg, uid, enabled);
        }

        /**
         * Use this when you just want to know if notifications are OK for this package.
         */
        @Override
        public boolean areNotificationsEnabledForPackage(String pkg, int uid) {
            checkCallerIsSystem();
            return (mAppOps.checkOpNoThrow(AppOpsManager.OP_POST_NOTIFICATION, uid, pkg)
                    == AppOpsManager.MODE_ALLOWED);
        }

        @Override
        public void setPackagePriority(String pkg, int uid, int priority) {
            checkCallerIsSystem();
            mRankingHelper.setPackagePriority(pkg, uid, priority);
            savePolicyFile();
        }

        @Override
        public int getPackagePriority(String pkg, int uid) {
            checkCallerIsSystem();
            return mRankingHelper.getPackagePriority(pkg, uid);
        }

        @Override
        public void setPackagePeekable(String pkg, int uid, boolean peekable) {
            checkCallerIsSystem();

            mRankingHelper.setPackagePeekable(pkg, uid, peekable);
        }

        @Override
        public boolean getPackagePeekable(String pkg, int uid) {
            checkCallerIsSystem();
            return mRankingHelper.getPackagePeekable(pkg, uid);
        }

        @Override
        public void setPackageVisibilityOverride(String pkg, int uid, int visibility) {
            checkCallerIsSystem();
            mRankingHelper.setPackageVisibilityOverride(pkg, uid, visibility);
            savePolicyFile();
        }

        @Override
        public int getPackageVisibilityOverride(String pkg, int uid) {
            checkCallerIsSystem();
            return mRankingHelper.getPackageVisibilityOverride(pkg, uid);
        }

        /**
         * System-only API for getting a list of current (i.e. not cleared) notifications.
         *
         * Requires ACCESS_NOTIFICATIONS which is signature|system.
         * @returns A list of all the notifications, in natural order.
         */
        @Override
        public StatusBarNotification[] getActiveNotifications(String callingPkg) {
            // enforce() will ensure the calling uid has the correct permission
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_NOTIFICATIONS,
                    "NotificationManagerService.getActiveNotifications");

            StatusBarNotification[] tmp = null;
            int uid = Binder.getCallingUid();

            // noteOp will check to make sure the callingPkg matches the uid
            if (mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, uid, callingPkg)
                    == AppOpsManager.MODE_ALLOWED) {
                synchronized (mNotificationList) {
                    tmp = new StatusBarNotification[mNotificationList.size()];
                    final int N = mNotificationList.size();
                    for (int i=0; i<N; i++) {
                        tmp[i] = mNotificationList.get(i).sbn;
                    }
                }
            }
            return tmp;
        }

        /**
         * Public API for getting a list of current notifications for the calling package/uid.
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

            final int N = mNotificationList.size();
            final ArrayList<StatusBarNotification> list = new ArrayList<StatusBarNotification>(N);

            synchronized (mNotificationList) {
                for (int i = 0; i < N; i++) {
                    final StatusBarNotification sbn = mNotificationList.get(i).sbn;
                    if (sbn.getPackageName().equals(pkg) && sbn.getUserId() == userId) {
                        // We could pass back a cloneLight() but clients might get confused and
                        // try to send this thing back to notify() again, which would not work
                        // very well.
                        final StatusBarNotification sbnOut = new StatusBarNotification(
                                sbn.getPackageName(),
                                sbn.getOpPkg(),
                                sbn.getId(), sbn.getTag(), sbn.getUid(), sbn.getInitialPid(),
                                0, // hide score from apps
                                sbn.getNotification().clone(),
                                sbn.getUser(), sbn.getPostTime());
                        list.add(sbnOut);
                    }
                }
            }

            return new ParceledListSlice<StatusBarNotification>(list);
        }

        /**
         * System-only API for getting a list of recent (cleared, no longer shown) notifications.
         *
         * Requires ACCESS_NOTIFICATIONS which is signature|system.
         */
        @Override
        public StatusBarNotification[] getHistoricalNotifications(String callingPkg, int count) {
            // enforce() will ensure the calling uid has the correct permission
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_NOTIFICATIONS,
                    "NotificationManagerService.getHistoricalNotifications");

            StatusBarNotification[] tmp = null;
            int uid = Binder.getCallingUid();

            // noteOp will check to make sure the callingPkg matches the uid
            if (mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, uid, callingPkg)
                    == AppOpsManager.MODE_ALLOWED) {
                synchronized (mArchive) {
                    tmp = mArchive.getArray(count);
                }
            }
            return tmp;
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
            mListeners.registerService(listener, component, userid);
        }

        /**
         * Remove a listener binder directly
         */
        @Override
        public void unregisterListener(INotificationListener listener, int userid) {
            mListeners.unregisterService(listener, userid);
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
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationList) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    if (keys != null) {
                        final int N = keys.length;
                        for (int i = 0; i < N; i++) {
                            NotificationRecord r = mNotificationsByKey.get(keys[i]);
                            if (r == null) continue;
                            final int userId = r.sbn.getUserId();
                            if (userId != info.userid && userId != UserHandle.USER_ALL &&
                                    !mUserProfiles.isCurrentProfile(userId)) {
                                throw new SecurityException("Disallowed call from listener: "
                                        + info.service);
                            }
                            cancelNotificationFromListenerLocked(info, callingUid, callingPid,
                                    r.sbn.getPackageName(), r.sbn.getTag(), r.sbn.getId(),
                                    userId);
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

        @Override
        public void setNotificationsShownFromListener(INotificationListener token, String[] keys) {
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationList) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    if (keys != null) {
                        final int N = keys.length;
                        for (int i = 0; i < N; i++) {
                            NotificationRecord r = mNotificationsByKey.get(keys[i]);
                            if (r == null) continue;
                            final int userId = r.sbn.getUserId();
                            if (userId != info.userid && userId != UserHandle.USER_ALL &&
                                    !mUserProfiles.isCurrentProfile(userId)) {
                                throw new SecurityException("Disallowed call from listener: "
                                        + info.service);
                            }
                            if (!r.isSeen()) {
                                if (DBG) Slog.d(TAG, "Marking notification as seen " + keys[i]);
                                mAppUsageStats.reportEvent(r.sbn.getPackageName(),
                                        userId == UserHandle.USER_ALL ? UserHandle.USER_OWNER
                                                : userId,
                                        UsageEvents.Event.USER_INTERACTION);
                                r.setSeen();
                            }
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void cancelNotificationFromListenerLocked(ManagedServiceInfo info,
                int callingUid, int callingPid, String pkg, String tag, int id, int userId) {
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0,
                    Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE,
                    true,
                    userId, REASON_LISTENER_CANCEL, info);
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
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationList) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    if (info.supportsProfiles()) {
                        Log.e(TAG, "Ignoring deprecated cancelNotification(pkg, tag, id) "
                                + "from " + info.component
                                + " use cancelNotification(key) instead.");
                    } else {
                        cancelNotificationFromListenerLocked(info, callingUid, callingPid,
                                pkg, tag, id, info.userid);
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
            synchronized (mNotificationList) {
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
                    StatusBarNotification sbn = r.sbn;
                    if (!isVisibleToListener(sbn, info)) continue;
                    StatusBarNotification sbnToSend =
                            (trim == TRIM_FULL) ? sbn : sbn.cloneLight();
                    list.add(sbnToSend);
                }
                return new ParceledListSlice<StatusBarNotification>(list);
            }
        }

        @Override
        public void requestHintsFromListener(INotificationListener token, int hints) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationList) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    final boolean disableEffects = (hints & HINT_HOST_DISABLE_EFFECTS) != 0;
                    if (disableEffects) {
                        mListenersDisablingEffects.add(info);
                    } else {
                        mListenersDisablingEffects.remove(info);
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
            synchronized (mNotificationList) {
                return mListenerHints;
            }
        }

        @Override
        public void requestInterruptionFilterFromListener(INotificationListener token,
                int interruptionFilter) throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationList) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    mZenModeHelper.requestFromListener(info.component, interruptionFilter);
                    updateInterruptionFilterLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public int getInterruptionFilterFromListener(INotificationListener token)
                throws RemoteException {
            synchronized (mNotificationLight) {
                return mInterruptionFilter;
            }
        }

        @Override
        public void setOnNotificationPostedTrimFromListener(INotificationListener token, int trim)
                throws RemoteException {
            synchronized (mNotificationList) {
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
            enforceSystemOrSystemUIOrVolume("INotificationManager.getZenModeConfig");
            return mZenModeHelper.getConfig();
        }

        @Override
        public boolean setZenModeConfig(ZenModeConfig config, String reason) {
            checkCallerIsSystem();
            return mZenModeHelper.setConfig(config, reason);
        }

        @Override
        public void setZenMode(int mode, Uri conditionId, String reason) throws RemoteException {
            enforceSystemOrSystemUIOrVolume("INotificationManager.setZenMode");
            final long identity = Binder.clearCallingIdentity();
            try {
                mZenModeHelper.setManualZenMode(mode, conditionId, reason);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setInterruptionFilter(String pkg, int filter) throws RemoteException {
            enforcePolicyAccess(pkg, "setInterruptionFilter");
            final int zen = NotificationManager.zenModeFromInterruptionFilter(filter, -1);
            if (zen == -1) throw new IllegalArgumentException("Invalid filter: " + filter);
            final long identity = Binder.clearCallingIdentity();
            try {
                mZenModeHelper.setManualZenMode(zen, null, "setInterruptionFilter");
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
        public void requestZenModeConditions(IConditionListener callback, int relevance) {
            enforceSystemOrSystemUIOrVolume("INotificationManager.requestZenModeConditions");
            mZenModeHelper.requestZenModeConditions(callback, relevance);
        }

        private void enforceSystemOrSystemUIOrVolume(String message) {
            if (mAudioManagerInternal != null) {
                final int vcuid = mAudioManagerInternal.getVolumeControllerUid();
                if (vcuid > 0 && Binder.getCallingUid() == vcuid) {
                    return;
                }
            }
            enforceSystemOrSystemUI(message);
        }

        private void enforceSystemOrSystemUI(String message) {
            if (isCallerSystem()) return;
            getContext().enforceCallingPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                    message);
        }

        private void enforcePolicyAccess(String pkg, String method) {
            checkCallerIsSameApp(pkg);
            if (!checkPolicyAccess(pkg)) {
                Slog.w(TAG, "Notification policy access denied calling " + method);
                throw new SecurityException("Notification policy access denied");
            }
        }

        private boolean checkPackagePolicyAccess(String pkg) {
            return mPolicyAccess.isPackageGranted(pkg);
        }

        private boolean checkPolicyAccess(String pkg) {
            return checkPackagePolicyAccess(pkg) || mListeners.isComponentEnabledForPackage(pkg);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump NotificationManager from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            final DumpFilter filter = DumpFilter.parseFromArguments(args);
            if (filter != null && filter.stats) {
                dumpJson(pw, filter);
            } else {
                dumpImpl(pw, filter);
            }
        }

        @Override
        public ComponentName getEffectsSuppressor() {
            enforceSystemOrSystemUIOrVolume("INotificationManager.getEffectsSuppressor");
            return mEffectsSuppressor;
        }

        @Override
        public boolean matchesCallFilter(Bundle extras) {
            enforceSystemOrSystemUI("INotificationManager.matchesCallFilter");
            return mZenModeHelper.matchesCallFilter(
                    UserHandle.getCallingUserHandle(),
                    extras,
                    mRankingHelper.findExtractor(ValidateNotificationPeople.class),
                    MATCHES_CALL_FILTER_CONTACTS_TIMEOUT_MS,
                    MATCHES_CALL_FILTER_TIMEOUT_AFFINITY);
        }

        @Override
        public boolean isSystemConditionProviderEnabled(String path) {
            enforceSystemOrSystemUIOrVolume("INotificationManager.isSystemConditionProviderEnabled");
            return mConditionProviders.isSystemProviderEnabled(path);
        }

        // Backup/restore interface
        @Override
        public byte[] getBackupPayload(int user) {
            if (DBG) Slog.d(TAG, "getBackupPayload u=" + user);
            if (user != UserHandle.USER_OWNER) {
                Slog.w(TAG, "getBackupPayload: cannot backup policy for user " + user);
                return null;
            }
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                writePolicyXml(baos, true /*forBackup*/);
                return baos.toByteArray();
            } catch (IOException e) {
                Slog.w(TAG, "getBackupPayload: error writing payload for user " + user, e);
            }
            return null;
        }

        @Override
        public void applyRestore(byte[] payload, int user) {
            if (DBG) Slog.d(TAG, "applyRestore u=" + user + " payload="
                    + (payload != null ? new String(payload, StandardCharsets.UTF_8) : null));
            if (payload == null) {
                Slog.w(TAG, "applyRestore: no payload to restore for user " + user);
                return;
            }
            if (user != UserHandle.USER_OWNER) {
                Slog.w(TAG, "applyRestore: cannot restore policy for user " + user);
                return;
            }
            final ByteArrayInputStream bais = new ByteArrayInputStream(payload);
            try {
                readPolicyXml(bais, true /*forRestore*/);
                savePolicyFile();
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
            enforceSystemOrSystemUI("request policy access status for another package");
            return checkPackagePolicyAccess(pkg);
        }

        @Override
        public String[] getPackagesRequestingNotificationPolicyAccess()
                throws RemoteException {
            enforceSystemOrSystemUI("request policy access packages");
            final long identity = Binder.clearCallingIdentity();
            try {
                return mPolicyAccess.getRequestingPackages();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setNotificationPolicyAccessGranted(String pkg, boolean granted)
                throws RemoteException {
            enforceSystemOrSystemUI("grant notification policy access");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationList) {
                    mPolicyAccess.put(pkg, granted);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public Policy getNotificationPolicy(String pkg) {
            enforcePolicyAccess(pkg, "getNotificationPolicy");
            final long identity = Binder.clearCallingIdentity();
            try {
                return mZenModeHelper.getNotificationPolicy();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setNotificationPolicy(String pkg, Policy policy) {
            enforcePolicyAccess(pkg, "setNotificationPolicy");
            final long identity = Binder.clearCallingIdentity();
            try {
                mZenModeHelper.setNotificationPolicy(policy);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    };

    private String disableNotificationEffects(NotificationRecord record) {
        if (mDisableNotificationEffects) {
            return "booleanState";
        }
        if ((mListenerHints & HINT_HOST_DISABLE_EFFECTS) != 0) {
            return "listenerHints";
        }
        if (mCallState != TelephonyManager.CALL_STATE_IDLE && !mZenModeHelper.isCall(record)) {
            return "callState";
        }
        return null;
    };

    private void dumpJson(PrintWriter pw, DumpFilter filter) {
        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Notification Manager");
            JSONArray bans = new JSONArray();
            try {
                ArrayMap<Integer, ArrayList<String>> packageBans = getPackageBans(filter);
                for (Integer userId : packageBans.keySet()) {
                    for (String packageName : packageBans.get(userId)) {
                        JSONObject ban = new JSONObject();
                        ban.put("userId", userId);
                        ban.put("packageName", packageName);
                        bans.put(ban);
                    }
                }
            } catch (NameNotFoundException e) {
                // pass
            }
            dump.put("bans", bans);
            dump.put("stats", mUsageStats.dumpJson(filter));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        pw.println(dump);
    }

    void dumpImpl(PrintWriter pw, DumpFilter filter) {
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

        synchronized (mNotificationList) {
            if (!zenOnly) {
                N = mNotificationList.size();
                if (N > 0) {
                    pw.println("  Notification List:");
                    for (int i=0; i<N; i++) {
                        final NotificationRecord nr = mNotificationList.get(i);
                        if (filter.filtered && !filter.matches(nr.sbn)) continue;
                        nr.dump(pw, "    ", getContext(), filter.redact);
                    }
                    pw.println("  ");
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
                    pw.println("  mNotificationPulseEnabled=" + mNotificationPulseEnabled);
                    pw.println("  mSoundNotificationKey=" + mSoundNotificationKey);
                    pw.println("  mVibrateNotificationKey=" + mVibrateNotificationKey);
                    pw.println("  mDisableNotificationEffects=" + mDisableNotificationEffects);
                    pw.println("  mCallState=" + callStateToString(mCallState));
                    pw.println("  mSystemReady=" + mSystemReady);
                }
                pw.println("  mArchive=" + mArchive.toString());
                Iterator<StatusBarNotification> iter = mArchive.descendingIterator();
                int i=0;
                while (iter.hasNext()) {
                    final StatusBarNotification sbn = iter.next();
                    if (filter != null && !filter.matches(sbn)) continue;
                    pw.println("    " + sbn);
                    if (++i >= 5) {
                        if (iter.hasNext()) pw.println("    ...");
                        break;
                    }
                }
            }

            if (!zenOnly) {
                pw.println("\n  Usage Stats:");
                mUsageStats.dump(pw, "    ", filter);
            }

            if (!filter.filtered || zenOnly) {
                pw.println("\n  Zen Mode:");
                pw.print("    mInterruptionFilter="); pw.println(mInterruptionFilter);
                mZenModeHelper.dump(pw, "    ");

                pw.println("\n  Zen Log:");
                ZenLog.dump(pw, "    ");
            }

            if (!zenOnly) {
                pw.println("\n  Ranking Config:");
                mRankingHelper.dump(pw, "    ", filter);

                pw.println("\n  Notification listeners:");
                mListeners.dump(pw, filter);
                pw.print("    mListenerHints: "); pw.println(mListenerHints);
                pw.print("    mListenersDisablingEffects: (");
                N = mListenersDisablingEffects.size();
                for (int i = 0; i < N; i++) {
                    final ManagedServiceInfo listener = mListenersDisablingEffects.valueAt(i);
                    if (i > 0) pw.print(',');
                    pw.print(listener.component);
                }
                pw.println(')');
            }
            pw.println("\n  Policy access:");
            pw.print("    mPolicyAccess: "); pw.println(mPolicyAccess);

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

            try {
                pw.println("\n  Banned Packages:");
                ArrayMap<Integer, ArrayList<String>> packageBans = getPackageBans(filter);
                for (Integer userId : packageBans.keySet()) {
                    for (String packageName : packageBans.get(userId)) {
                        pw.println("    " + userId + ": " + packageName);
                    }
                }
            } catch (NameNotFoundException e) {
                // pass
            }
        }
    }

    private ArrayMap<Integer, ArrayList<String>> getPackageBans(DumpFilter filter)
            throws NameNotFoundException {
        ArrayMap<Integer, ArrayList<String>> packageBans = new ArrayMap<>();
        ArrayList<String> packageNames = new ArrayList<>();
        for (UserInfo user : UserManager.get(getContext()).getUsers()) {
            final int userId = user.getUserHandle().getIdentifier();
            final PackageManager packageManager = getContext().getPackageManager();
            List<PackageInfo> packages = packageManager.getInstalledPackages(0, userId);
            final int packageCount = packages.size();
            for (int p = 0; p < packageCount; p++) {
                final String packageName = packages.get(p).packageName;
                if (filter == null || filter.matches(packageName)) {
                    final int uid = packageManager.getPackageUid(packageName, userId);
                    if (!checkNotificationOp(packageName, uid)) {
                        packageNames.add(packageName);
                    }
                }
            }
            if (!packageNames.isEmpty()) {
                packageBans.put(userId, packageNames);
                packageNames = new ArrayList<>();
            }
        }
        return packageBans;
    }

    /**
     * The private API only accessible to the system process.
     */
    private final NotificationManagerInternal mInternalService = new NotificationManagerInternal() {
        @Override
        public void enqueueNotification(String pkg, String opPkg, int callingUid, int callingPid,
                String tag, int id, Notification notification, int[] idReceived, int userId) {
            enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, notification,
                    idReceived, userId);
        }

        @Override
        public void removeForegroundServiceFlagFromNotification(String pkg, int notificationId,
                int userId) {
            checkCallerIsSystem();
            synchronized (mNotificationList) {
                int i = indexOfNotificationLocked(pkg, null, notificationId, userId);
                if (i < 0) {
                    Log.d(TAG, "stripForegroundServiceFlag: Could not find notification with "
                            + "pkg=" + pkg + " / id=" + notificationId + " / userId=" + userId);
                    return;
                }
                NotificationRecord r = mNotificationList.get(i);
                StatusBarNotification sbn = r.sbn;
                // NoMan adds flags FLAG_NO_CLEAR and FLAG_ONGOING_EVENT when it sees
                // FLAG_FOREGROUND_SERVICE. Hence it's not enough to remove FLAG_FOREGROUND_SERVICE,
                // we have to revert to the flags we received initially *and* force remove
                // FLAG_FOREGROUND_SERVICE.
                sbn.getNotification().flags =
                        (r.mOriginalFlags & ~Notification.FLAG_FOREGROUND_SERVICE);
                mRankingHelper.sort(mNotificationList);
                mListeners.notifyPostedLocked(sbn, sbn /* oldSbn */);
            }
        }
    };

    void enqueueNotificationInternal(final String pkg, final String opPkg, final int callingUid,
            final int callingPid, final String tag, final int id, final Notification notification,
            int[] idOut, int incomingUserId) {
        if (DBG) {
            Slog.v(TAG, "enqueueNotificationInternal: pkg=" + pkg + " id=" + id
                    + " notification=" + notification);
        }
        checkCallerIsSystemOrSameApp(pkg);
        final boolean isSystemNotification = isUidSystem(callingUid) || ("android".equals(pkg));
        final boolean isNotificationFromListener = mListeners.isListenerPackage(pkg);

        final int userId = ActivityManager.handleIncomingUser(callingPid,
                callingUid, incomingUserId, true, false, "enqueueNotification", pkg);
        final UserHandle user = new UserHandle(userId);

        // Limit the number of notifications that any given package except the android
        // package or a registered listener can enqueue.  Prevents DOS attacks and deals with leaks.
        if (!isSystemNotification && !isNotificationFromListener) {
            synchronized (mNotificationList) {
                int count = 0;
                final int N = mNotificationList.size();
                for (int i=0; i<N; i++) {
                    final NotificationRecord r = mNotificationList.get(i);
                    if (r.sbn.getPackageName().equals(pkg) && r.sbn.getUserId() == userId) {
                        if (r.sbn.getId() == id && TextUtils.equals(r.sbn.getTag(), tag)) {
                            break;  // Allow updating existing notification
                        }
                        count++;
                        if (count >= MAX_PACKAGE_NOTIFICATIONS) {
                            Slog.e(TAG, "Package has already posted " + count
                                    + " notifications.  Not showing more.  package=" + pkg);
                            return;
                        }
                    }
                }
            }
        }

        if (pkg == null || notification == null) {
            throw new IllegalArgumentException("null not allowed: pkg=" + pkg
                    + " id=" + id + " notification=" + notification);
        }

        if (notification.getSmallIcon() != null) {
            if (!notification.isValid()) {
                throw new IllegalArgumentException("Invalid notification (): pkg=" + pkg
                        + " id=" + id + " notification=" + notification);
            }
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {

                synchronized (mNotificationList) {

                    // === Scoring ===

                    // 0. Sanitize inputs
                    notification.priority = clamp(notification.priority, Notification.PRIORITY_MIN,
                            Notification.PRIORITY_MAX);
                    // Migrate notification flags to scores
                    if (0 != (notification.flags & Notification.FLAG_HIGH_PRIORITY)) {
                        if (notification.priority < Notification.PRIORITY_MAX) {
                            notification.priority = Notification.PRIORITY_MAX;
                        }
                    } else if (SCORE_ONGOING_HIGHER &&
                            0 != (notification.flags & Notification.FLAG_ONGOING_EVENT)) {
                        if (notification.priority < Notification.PRIORITY_HIGH) {
                            notification.priority = Notification.PRIORITY_HIGH;
                        }
                    }
                    // force no heads up per package config
                    if (!mRankingHelper.getPackagePeekable(pkg, callingUid)) {
                        if (notification.extras == null) {
                            notification.extras = new Bundle();
                        }
                        notification.extras.putInt(Notification.EXTRA_AS_HEADS_UP,
                                Notification.HEADS_UP_NEVER);
                    }

                    // 1. initial score: buckets of 10, around the app [-20..20]
                    final int score = notification.priority * NOTIFICATION_PRIORITY_MULTIPLIER;

                    // 2. extract ranking signals from the notification data
                    final StatusBarNotification n = new StatusBarNotification(
                            pkg, opPkg, id, tag, callingUid, callingPid, score, notification,
                            user);
                    NotificationRecord r = new NotificationRecord(n, score);
                    NotificationRecord old = mNotificationsByKey.get(n.getKey());
                    if (old != null) {
                        // Retain ranking information from previous record
                        r.copyRankingInformation(old);
                    }

                    // Handle grouped notifications and bail out early if we
                    // can to avoid extracting signals.
                    handleGroupedNotificationLocked(r, old, callingUid, callingPid);
                    boolean ignoreNotification =
                            removeUnusedGroupedNotificationLocked(r, old, callingUid, callingPid);

                    // This conditional is a dirty hack to limit the logging done on
                    //     behalf of the download manager without affecting other apps.
                    if (!pkg.equals("com.android.providers.downloads")
                            || Log.isLoggable("DownloadManager", Log.VERBOSE)) {
                        int enqueueStatus = EVENTLOG_ENQUEUE_STATUS_NEW;
                        if (ignoreNotification) {
                            enqueueStatus = EVENTLOG_ENQUEUE_STATUS_IGNORED;
                        } else if (old != null) {
                            enqueueStatus = EVENTLOG_ENQUEUE_STATUS_UPDATE;
                        }
                        EventLogTags.writeNotificationEnqueue(callingUid, callingPid,
                                pkg, id, tag, userId, notification.toString(),
                                enqueueStatus);
                    }

                    if (ignoreNotification) {
                        return;
                    }

                    mRankingHelper.extractSignals(r);

                    // 3. Apply local rules

                    // blocked apps
                    if (ENABLE_BLOCKED_NOTIFICATIONS && !noteNotificationOp(pkg, callingUid)) {
                        if (!isSystemNotification) {
                            r.score = JUNK_SCORE;
                            Slog.e(TAG, "Suppressing notification from package " + pkg
                                    + " by user request.");
                            mUsageStats.registerBlocked(r);
                        }
                    }

                    if (r.score < SCORE_DISPLAY_THRESHOLD) {
                        // Notification will be blocked because the score is too low.
                        return;
                    }

                    int index = indexOfNotificationLocked(n.getKey());
                    if (index < 0) {
                        mNotificationList.add(r);
                        mUsageStats.registerPostedByApp(r);
                    } else {
                        old = mNotificationList.get(index);
                        mNotificationList.set(index, r);
                        mUsageStats.registerUpdatedByApp(r, old);
                        // Make sure we don't lose the foreground service state.
                        notification.flags |=
                                old.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE;
                        r.isUpdate = true;
                    }

                    mNotificationsByKey.put(n.getKey(), r);

                    // Ensure if this is a foreground service that the proper additional
                    // flags are set.
                    if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                        notification.flags |= Notification.FLAG_ONGOING_EVENT
                                | Notification.FLAG_NO_CLEAR;
                    }

                    applyZenModeLocked(r);
                    mRankingHelper.sort(mNotificationList);

                    if (notification.getSmallIcon() != null) {
                        StatusBarNotification oldSbn = (old != null) ? old.sbn : null;
                        mListeners.notifyPostedLocked(n, oldSbn);
                    } else {
                        Slog.e(TAG, "Not posting notification without small icon: " + notification);
                        if (old != null && !old.isCanceled) {
                            mListeners.notifyRemovedLocked(n);
                        }
                        // ATTENTION: in a future release we will bail out here
                        // so that we do not play sounds, show lights, etc. for invalid
                        // notifications
                        Slog.e(TAG, "WARNING: In a future release this will crash the app: "
                                + n.getPackageName());
                    }

                    buzzBeepBlinkLocked(r);
                }
            }
        });

        idOut[0] = id;
    }

    /**
     * Ensures that grouped notification receive their special treatment.
     *
     * <p>Cancels group children if the new notification causes a group to lose
     * its summary.</p>
     *
     * <p>Updates mSummaryByGroupKey.</p>
     */
    private void handleGroupedNotificationLocked(NotificationRecord r, NotificationRecord old,
            int callingUid, int callingPid) {
        StatusBarNotification sbn = r.sbn;
        Notification n = sbn.getNotification();
        String group = sbn.getGroupKey();
        boolean isSummary = n.isGroupSummary();

        Notification oldN = old != null ? old.sbn.getNotification() : null;
        String oldGroup = old != null ? old.sbn.getGroupKey() : null;
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

        // Clear out group children of the old notification if the update
        // causes the group summary to go away. This happens when the old
        // notification was a summary and the new one isn't, or when the old
        // notification was a summary and its group key changed.
        if (oldIsSummary && (!isSummary || !oldGroup.equals(group))) {
            cancelGroupChildrenLocked(old, callingUid, callingPid, null,
                    REASON_GROUP_SUMMARY_CANCELED);
        }
    }

    /**
     * Performs group notification optimizations if SysUI is the only active
     * notification listener and returns whether the given notification should
     * be ignored.
     *
     * <p>Returns true if the given notification is a child of a group with a
     * summary, which means that SysUI will never show it, and hence the new
     * notification can be safely ignored. Also cancels any previous instance
     * of the ignored notification.</p>
     *
     * <p>For summaries, cancels all children of that group, as SysUI will
     * never show them anymore.</p>
     *
     * @return true if the given notification can be ignored as an optimization
     */
    private boolean removeUnusedGroupedNotificationLocked(NotificationRecord r,
            NotificationRecord old, int callingUid, int callingPid) {
        if (!ENABLE_CHILD_NOTIFICATIONS) {
            // No optimizations are possible if listeners want groups.
            if (mListeners.notificationGroupsDesired()) {
                return false;
            }

            StatusBarNotification sbn = r.sbn;
            String group = sbn.getGroupKey();
            boolean isSummary = sbn.getNotification().isGroupSummary();
            boolean isChild = sbn.getNotification().isGroupChild();

            NotificationRecord summary = mSummaryByGroupKey.get(group);
            if (isChild && summary != null) {
                // Child with an active summary -> ignore
                if (DBG) {
                    Slog.d(TAG, "Ignoring group child " + sbn.getKey() + " due to existing summary "
                            + summary.getKey());
                }
                // Make sure we don't leave an old version of the notification around.
                if (old != null) {
                    if (DBG) {
                        Slog.d(TAG, "Canceling old version of ignored group child " + sbn.getKey());
                    }
                    cancelNotificationLocked(old, false, REASON_GROUP_OPTIMIZATION);
                }
                return true;
            } else if (isSummary) {
                // Summary -> cancel children
                cancelGroupChildrenLocked(r, callingUid, callingPid, null,
                        REASON_GROUP_OPTIMIZATION);
            }
        }
        return false;
    }

    private void buzzBeepBlinkLocked(NotificationRecord record) {
        boolean buzz = false;
        boolean beep = false;
        boolean blink = false;

        final Notification notification = record.sbn.getNotification();

        // Should this notification make noise, vibe, or use the LED?
        final boolean aboveThreshold = record.score >= SCORE_INTERRUPTION_THRESHOLD;
        final boolean canInterrupt = aboveThreshold && !record.isIntercepted();
        if (DBG || record.isIntercepted())
            Slog.v(TAG,
                    "pkg=" + record.sbn.getPackageName() + " canInterrupt=" + canInterrupt +
                            " intercept=" + record.isIntercepted()
            );

        final int currentUser;
        final long token = Binder.clearCallingIdentity();
        try {
            currentUser = ActivityManager.getCurrentUser();
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // If we're not supposed to beep, vibrate, etc. then don't.
        final String disableEffects = disableNotificationEffects(record);
        if (disableEffects != null) {
            ZenLog.traceDisableEffects(record, disableEffects);
        }
        if (disableEffects == null
                && (!(record.isUpdate
                    && (notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0 ))
                && (record.getUserId() == UserHandle.USER_ALL ||
                    record.getUserId() == currentUser ||
                    mUserProfiles.isCurrentProfile(record.getUserId()))
                && canInterrupt
                && mSystemReady
                && mAudioManager != null) {
            if (DBG) Slog.v(TAG, "Interrupting!");

            sendAccessibilityEvent(notification, record.sbn.getPackageName());

            // sound

            // should we use the default notification sound? (indicated either by
            // DEFAULT_SOUND or because notification.sound is pointing at
            // Settings.System.NOTIFICATION_SOUND)
            final boolean useDefaultSound =
                   (notification.defaults & Notification.DEFAULT_SOUND) != 0 ||
                           Settings.System.DEFAULT_NOTIFICATION_URI
                                   .equals(notification.sound);

            Uri soundUri = null;
            boolean hasValidSound = false;

            if (useDefaultSound) {
                soundUri = Settings.System.DEFAULT_NOTIFICATION_URI;

                // check to see if the default notification sound is silent
                ContentResolver resolver = getContext().getContentResolver();
                hasValidSound = Settings.System.getString(resolver,
                       Settings.System.NOTIFICATION_SOUND) != null;
            } else if (notification.sound != null) {
                soundUri = notification.sound;
                hasValidSound = (soundUri != null);
            }

            if (hasValidSound) {
                boolean looping =
                        (notification.flags & Notification.FLAG_INSISTENT) != 0;
                AudioAttributes audioAttributes = audioAttributesForNotification(notification);
                mSoundNotificationKey = record.getKey();
                // do not play notifications if stream volume is 0 (typically because
                // ringer mode is silent) or if there is a user of exclusive audio focus
                if ((mAudioManager.getStreamVolume(
                        AudioAttributes.toLegacyStreamType(audioAttributes)) != 0)
                            && !mAudioManager.isAudioFocusExclusive()) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        final IRingtonePlayer player =
                                mAudioManager.getRingtonePlayer();
                        if (player != null) {
                            if (DBG) Slog.v(TAG, "Playing sound " + soundUri
                                    + " with attributes " + audioAttributes);
                            player.playAsync(soundUri, record.sbn.getUser(), looping,
                                    audioAttributes);
                            beep = true;
                        }
                    } catch (RemoteException e) {
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }

            // vibrate
            // Does the notification want to specify its own vibration?
            final boolean hasCustomVibrate = notification.vibrate != null;

            // new in 4.2: if there was supposed to be a sound and we're in vibrate
            // mode, and no other vibration is specified, we fall back to vibration
            final boolean convertSoundToVibration =
                       !hasCustomVibrate
                    && hasValidSound
                    && (mAudioManager.getRingerModeInternal()
                               == AudioManager.RINGER_MODE_VIBRATE);

            // The DEFAULT_VIBRATE flag trumps any custom vibration AND the fallback.
            final boolean useDefaultVibrate =
                    (notification.defaults & Notification.DEFAULT_VIBRATE) != 0;

            if ((useDefaultVibrate || convertSoundToVibration || hasCustomVibrate)
                    && !(mAudioManager.getRingerModeInternal()
                            == AudioManager.RINGER_MODE_SILENT)) {
                mVibrateNotificationKey = record.getKey();

                if (useDefaultVibrate || convertSoundToVibration) {
                    // Escalate privileges so we can use the vibrator even if the
                    // notifying app does not have the VIBRATE permission.
                    long identity = Binder.clearCallingIdentity();
                    try {
                        mVibrator.vibrate(record.sbn.getUid(), record.sbn.getOpPkg(),
                            useDefaultVibrate ? mDefaultVibrationPattern
                                : mFallbackVibrationPattern,
                            ((notification.flags & Notification.FLAG_INSISTENT) != 0)
                                ? 0: -1, audioAttributesForNotification(notification));
                        buzz = true;
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                } else if (notification.vibrate.length > 1) {
                    // If you want your own vibration pattern, you need the VIBRATE
                    // permission
                    mVibrator.vibrate(record.sbn.getUid(), record.sbn.getOpPkg(),
                            notification.vibrate,
                        ((notification.flags & Notification.FLAG_INSISTENT) != 0)
                                ? 0: -1, audioAttributesForNotification(notification));
                    buzz = true;
                }
            }
        }

        // light
        // release the light
        boolean wasShowLights = mLights.remove(record.getKey());
        if ((notification.flags & Notification.FLAG_SHOW_LIGHTS) != 0 && aboveThreshold) {
            mLights.add(record.getKey());
            updateLightsLocked();
            if (mUseAttentionLight) {
                mAttentionLight.pulse();
            }
            blink = true;
        } else if (wasShowLights) {
            updateLightsLocked();
        }
        if (buzz || beep || blink) {
            EventLogTags.writeNotificationAlert(record.getKey(),
                    buzz ? 1 : 0, beep ? 1 : 0, blink ? 1 : 0);
            mHandler.post(mBuzzBeepBlinked);
        }
    }

    private static AudioAttributes audioAttributesForNotification(Notification n) {
        if (n.audioAttributes != null
                && !Notification.AUDIO_ATTRIBUTES_DEFAULT.equals(n.audioAttributes)) {
            // the audio attributes are set and different from the default, use them
            return n.audioAttributes;
        } else if (n.audioStreamType >= 0 && n.audioStreamType < AudioSystem.getNumStreamTypes()) {
            // the stream type is valid, use it
            return new AudioAttributes.Builder()
                    .setInternalLegacyStreamType(n.audioStreamType)
                    .build();
        } else if (n.audioStreamType == AudioSystem.STREAM_DEFAULT) {
            return Notification.AUDIO_ATTRIBUTES_DEFAULT;
        } else {
            Log.w(TAG, String.format("Invalid stream type: %d", n.audioStreamType));
            return Notification.AUDIO_ATTRIBUTES_DEFAULT;
        }
    }

    void showNextToastLocked() {
        ToastRecord record = mToastQueue.get(0);
        while (record != null) {
            if (DBG) Slog.d(TAG, "Show pkg=" + record.pkg + " callback=" + record.callback);
            try {
                record.callback.show();
                scheduleTimeoutLocked(record);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Object died trying to show notification " + record.callback
                        + " in package " + record.pkg);
                // remove it from the list and let the process die
                int index = mToastQueue.indexOf(record);
                if (index >= 0) {
                    mToastQueue.remove(index);
                }
                keepProcessAliveLocked(record.pid);
                if (mToastQueue.size() > 0) {
                    record = mToastQueue.get(0);
                } else {
                    record = null;
                }
            }
        }
    }

    void cancelToastLocked(int index) {
        ToastRecord record = mToastQueue.get(index);
        try {
            record.callback.hide();
        } catch (RemoteException e) {
            Slog.w(TAG, "Object died trying to hide notification " + record.callback
                    + " in package " + record.pkg);
            // don't worry about this, we're about to remove it from
            // the list anyway
        }
        mToastQueue.remove(index);
        keepProcessAliveLocked(record.pid);
        if (mToastQueue.size() > 0) {
            // Show the next one. If the callback fails, this will remove
            // it from the list, so don't assume that the list hasn't changed
            // after this point.
            showNextToastLocked();
        }
    }

    private void scheduleTimeoutLocked(ToastRecord r)
    {
        mHandler.removeCallbacksAndMessages(r);
        Message m = Message.obtain(mHandler, MESSAGE_TIMEOUT, r);
        long delay = r.duration == Toast.LENGTH_LONG ? LONG_DELAY : SHORT_DELAY;
        mHandler.sendMessageDelayed(m, delay);
    }

    private void handleTimeout(ToastRecord record)
    {
        if (DBG) Slog.d(TAG, "Timeout pkg=" + record.pkg + " callback=" + record.callback);
        synchronized (mToastQueue) {
            int index = indexOfToastLocked(record.pkg, record.callback);
            if (index >= 0) {
                cancelToastLocked(index);
            }
        }
    }

    // lock on mToastQueue
    int indexOfToastLocked(String pkg, ITransientNotification callback)
    {
        IBinder cbak = callback.asBinder();
        ArrayList<ToastRecord> list = mToastQueue;
        int len = list.size();
        for (int i=0; i<len; i++) {
            ToastRecord r = list.get(i);
            if (r.pkg.equals(pkg) && r.callback.asBinder() == cbak) {
                return i;
            }
        }
        return -1;
    }

    // lock on mToastQueue
    void keepProcessAliveLocked(int pid)
    {
        int toastCount = 0; // toasts from this pid
        ArrayList<ToastRecord> list = mToastQueue;
        int N = list.size();
        for (int i=0; i<N; i++) {
            ToastRecord r = list.get(i);
            if (r.pid == pid) {
                toastCount++;
            }
        }
        try {
            mAm.setProcessForeground(mForegroundToken, pid, toastCount > 0);
        } catch (RemoteException e) {
            // Shouldn't happen.
        }
    }

    private void handleRankingReconsideration(Message message) {
        if (!(message.obj instanceof RankingReconsideration)) return;
        RankingReconsideration recon = (RankingReconsideration) message.obj;
        recon.run();
        boolean changed;
        synchronized (mNotificationList) {
            final NotificationRecord record = mNotificationsByKey.get(recon.getKey());
            if (record == null) {
                return;
            }
            int indexBefore = findNotificationRecordIndexLocked(record);
            boolean interceptBefore = record.isIntercepted();
            int visibilityBefore = record.getPackageVisibilityOverride();
            recon.applyChangesLocked(record);
            applyZenModeLocked(record);
            mRankingHelper.sort(mNotificationList);
            int indexAfter = findNotificationRecordIndexLocked(record);
            boolean interceptAfter = record.isIntercepted();
            int visibilityAfter = record.getPackageVisibilityOverride();
            changed = indexBefore != indexAfter || interceptBefore != interceptAfter
                    || visibilityBefore != visibilityAfter;
            if (interceptBefore && !interceptAfter) {
                buzzBeepBlinkLocked(record);
            }
        }
        if (changed) {
            scheduleSendRankingUpdate();
        }
    }

    private void handleRankingConfigChange() {
        synchronized (mNotificationList) {
            final int N = mNotificationList.size();
            ArrayList<String> orderBefore = new ArrayList<String>(N);
            int[] visibilities = new int[N];
            for (int i = 0; i < N; i++) {
                final NotificationRecord r = mNotificationList.get(i);
                orderBefore.add(r.getKey());
                visibilities[i] = r.getPackageVisibilityOverride();
                mRankingHelper.extractSignals(r);
            }
            for (int i = 0; i < N; i++) {
                mRankingHelper.sort(mNotificationList);
                final NotificationRecord r = mNotificationList.get(i);
                if (!orderBefore.get(i).equals(r.getKey())
                        || visibilities[i] != r.getPackageVisibilityOverride()) {
                    scheduleSendRankingUpdate();
                    return;
                }
            }
        }
    }

    // let zen mode evaluate this record
    private void applyZenModeLocked(NotificationRecord record) {
        record.setIntercepted(mZenModeHelper.shouldIntercept(record));
    }

    // lock on mNotificationList
    private int findNotificationRecordIndexLocked(NotificationRecord target) {
        return mRankingHelper.indexOf(mNotificationList, target);
    }

    private void scheduleSendRankingUpdate() {
        mHandler.removeMessages(MESSAGE_SEND_RANKING_UPDATE);
        Message m = Message.obtain(mHandler, MESSAGE_SEND_RANKING_UPDATE);
        mHandler.sendMessage(m);
    }

    private void handleSendRankingUpdate() {
        synchronized (mNotificationList) {
            mListeners.notifyRankingUpdateLocked();
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
        synchronized (mNotificationList) {
            mListeners.notifyListenerHintsChangedLocked(hints);
        }
    }

    private void handleListenerInterruptionFilterChanged(int interruptionFilter) {
        synchronized (mNotificationList) {
            mListeners.notifyInterruptionFilterChanged(interruptionFilter);
        }
    }

    private final class WorkerHandler extends Handler
    {
        @Override
        public void handleMessage(Message msg)
        {
            switch (msg.what)
            {
                case MESSAGE_TIMEOUT:
                    handleTimeout((ToastRecord)msg.obj);
                    break;
                case MESSAGE_SAVE_POLICY_FILE:
                    handleSavePolicyFile();
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
            }
        }

    }

    private final class RankingWorkerHandler extends Handler
    {
        public RankingWorkerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_RECONSIDER_RANKING:
                    handleRankingReconsideration(msg);
                    break;
                case MESSAGE_RANKING_CONFIG_CHANGE:
                    handleRankingConfigChange();
                    break;
            }
        }
    }

    // Notifications
    // ============================================================================
    static int clamp(int x, int low, int high) {
        return (x < low) ? low : ((x > high) ? high : x);
    }

    void sendAccessibilityEvent(Notification notification, CharSequence packageName) {
        AccessibilityManager manager = AccessibilityManager.getInstance(getContext());
        if (!manager.isEnabled()) {
            return;
        }

        AccessibilityEvent event =
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setPackageName(packageName);
        event.setClassName(Notification.class.getName());
        event.setParcelableData(notification);
        CharSequence tickerText = notification.tickerText;
        if (!TextUtils.isEmpty(tickerText)) {
            event.getText().add(tickerText);
        }

        manager.sendAccessibilityEvent(event);
    }

    private void cancelNotificationLocked(NotificationRecord r, boolean sendDelete, int reason) {
        // tell the app
        if (sendDelete) {
            if (r.getNotification().deleteIntent != null) {
                try {
                    r.getNotification().deleteIntent.send();
                } catch (PendingIntent.CanceledException ex) {
                    // do nothing - there's no relevant way to recover, and
                    //     no reason to let this propagate
                    Slog.w(TAG, "canceled PendingIntent for " + r.sbn.getPackageName(), ex);
                }
            }
        }

        // status bar
        if (r.getNotification().getSmallIcon() != null) {
            r.isCanceled = true;
            mListeners.notifyRemovedLocked(r.sbn);
        }

        final String canceledKey = r.getKey();

        // sound
        if (canceledKey.equals(mSoundNotificationKey)) {
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

        // vibrate
        if (canceledKey.equals(mVibrateNotificationKey)) {
            mVibrateNotificationKey = null;
            long identity = Binder.clearCallingIdentity();
            try {
                mVibrator.cancel();
            }
            finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        // light
        mLights.remove(canceledKey);

        // Record usage stats
        switch (reason) {
            case REASON_DELEGATE_CANCEL:
            case REASON_DELEGATE_CANCEL_ALL:
            case REASON_LISTENER_CANCEL:
            case REASON_LISTENER_CANCEL_ALL:
                mUsageStats.registerDismissedByUser(r);
                break;
            case REASON_NOMAN_CANCEL:
            case REASON_NOMAN_CANCEL_ALL:
                mUsageStats.registerRemovedByApp(r);
                break;
        }

        mNotificationsByKey.remove(r.sbn.getKey());
        String groupKey = r.getGroupKey();
        NotificationRecord groupSummary = mSummaryByGroupKey.get(groupKey);
        if (groupSummary != null && groupSummary.getKey().equals(r.getKey())) {
            mSummaryByGroupKey.remove(groupKey);
        }

        // Save it for users of getHistoricalNotifications()
        mArchive.record(r.sbn);

        final long now = System.currentTimeMillis();
        EventLogTags.writeNotificationCanceled(canceledKey, reason,
                r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now));
    }

    /**
     * Cancels a notification ONLY if it has all of the {@code mustHaveFlags}
     * and none of the {@code mustNotHaveFlags}.
     */
    void cancelNotification(final int callingUid, final int callingPid,
            final String pkg, final String tag, final int id,
            final int mustHaveFlags, final int mustNotHaveFlags, final boolean sendDelete,
            final int userId, final int reason, final ManagedServiceInfo listener) {
        // In enqueueNotificationInternal notifications are added by scheduling the
        // work on the worker handler. Hence, we also schedule the cancel on this
        // handler to avoid a scenario where an add notification call followed by a
        // remove notification call ends up in not removing the notification.
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                String listenerName = listener == null ? null : listener.component.toShortString();
                if (DBG) EventLogTags.writeNotificationCancel(callingUid, callingPid, pkg, id, tag,
                        userId, mustHaveFlags, mustNotHaveFlags, reason, listenerName);

                synchronized (mNotificationList) {
                    int index = indexOfNotificationLocked(pkg, tag, id, userId);
                    if (index >= 0) {
                        NotificationRecord r = mNotificationList.get(index);

                        // Ideally we'd do this in the caller of this method. However, that would
                        // require the caller to also find the notification.
                        if (reason == REASON_DELEGATE_CLICK) {
                            mUsageStats.registerClickedByUser(r);
                        }

                        if ((r.getNotification().flags & mustHaveFlags) != mustHaveFlags) {
                            return;
                        }
                        if ((r.getNotification().flags & mustNotHaveFlags) != 0) {
                            return;
                        }

                        mNotificationList.remove(index);

                        cancelNotificationLocked(r, sendDelete, reason);
                        cancelGroupChildrenLocked(r, callingUid, callingPid, listenerName,
                                REASON_GROUP_SUMMARY_CANCELED);
                        updateLightsLocked();
                    }
                }
            }
        });
    }

    /**
     * Determine whether the userId applies to the notification in question, either because
     * they match exactly, or one of them is USER_ALL (which is treated as a wildcard).
     */
    private boolean notificationMatchesUserId(NotificationRecord r, int userId) {
        return
                // looking for USER_ALL notifications? match everything
                   userId == UserHandle.USER_ALL
                // a notification sent to USER_ALL matches any query
                || r.getUserId() == UserHandle.USER_ALL
                // an exact user match
                || r.getUserId() == userId;
    }

    /**
     * Determine whether the userId applies to the notification in question, either because
     * they match exactly, or one of them is USER_ALL (which is treated as a wildcard) or
     * because it matches one of the users profiles.
     */
    private boolean notificationMatchesCurrentProfiles(NotificationRecord r, int userId) {
        return notificationMatchesUserId(r, userId)
                || mUserProfiles.isCurrentProfile(r.getUserId());
    }

    /**
     * Cancels all notifications from a given package that have all of the
     * {@code mustHaveFlags}.
     */
    boolean cancelAllNotificationsInt(int callingUid, int callingPid, String pkg, int mustHaveFlags,
            int mustNotHaveFlags, boolean doit, int userId, int reason,
            ManagedServiceInfo listener) {
        String listenerName = listener == null ? null : listener.component.toShortString();
        EventLogTags.writeNotificationCancelAll(callingUid, callingPid,
                pkg, userId, mustHaveFlags, mustNotHaveFlags, reason,
                listenerName);

        synchronized (mNotificationList) {
            final int N = mNotificationList.size();
            ArrayList<NotificationRecord> canceledNotifications = null;
            for (int i = N-1; i >= 0; --i) {
                NotificationRecord r = mNotificationList.get(i);
                if (!notificationMatchesUserId(r, userId)) {
                    continue;
                }
                // Don't remove notifications to all, if there's no package name specified
                if (r.getUserId() == UserHandle.USER_ALL && pkg == null) {
                    continue;
                }
                if ((r.getFlags() & mustHaveFlags) != mustHaveFlags) {
                    continue;
                }
                if ((r.getFlags() & mustNotHaveFlags) != 0) {
                    continue;
                }
                if (pkg != null && !r.sbn.getPackageName().equals(pkg)) {
                    continue;
                }
                if (canceledNotifications == null) {
                    canceledNotifications = new ArrayList<>();
                }
                canceledNotifications.add(r);
                if (!doit) {
                    return true;
                }
                mNotificationList.remove(i);
                cancelNotificationLocked(r, false, reason);
            }
            if (doit && canceledNotifications != null) {
                final int M = canceledNotifications.size();
                for (int i = 0; i < M; i++) {
                    cancelGroupChildrenLocked(canceledNotifications.get(i), callingUid, callingPid,
                            listenerName, REASON_GROUP_SUMMARY_CANCELED);
                }
            }
            if (canceledNotifications != null) {
                updateLightsLocked();
            }
            return canceledNotifications != null;
        }
    }

    void cancelAllLocked(int callingUid, int callingPid, int userId, int reason,
            ManagedServiceInfo listener, boolean includeCurrentProfiles) {
        String listenerName = listener == null ? null : listener.component.toShortString();
        EventLogTags.writeNotificationCancelAll(callingUid, callingPid,
                null, userId, 0, 0, reason, listenerName);

        ArrayList<NotificationRecord> canceledNotifications = null;
        final int N = mNotificationList.size();
        for (int i=N-1; i>=0; i--) {
            NotificationRecord r = mNotificationList.get(i);
            if (includeCurrentProfiles) {
                if (!notificationMatchesCurrentProfiles(r, userId)) {
                    continue;
                }
            } else {
                if (!notificationMatchesUserId(r, userId)) {
                    continue;
                }
            }

            if ((r.getFlags() & (Notification.FLAG_ONGOING_EVENT
                            | Notification.FLAG_NO_CLEAR)) == 0) {
                mNotificationList.remove(i);
                cancelNotificationLocked(r, true, reason);
                // Make a note so we can cancel children later.
                if (canceledNotifications == null) {
                    canceledNotifications = new ArrayList<>();
                }
                canceledNotifications.add(r);
            }
        }
        int M = canceledNotifications != null ? canceledNotifications.size() : 0;
        for (int i = 0; i < M; i++) {
            cancelGroupChildrenLocked(canceledNotifications.get(i), callingUid, callingPid,
                    listenerName, REASON_GROUP_SUMMARY_CANCELED);
        }
        updateLightsLocked();
    }

    // Warning: The caller is responsible for invoking updateLightsLocked().
    private void cancelGroupChildrenLocked(NotificationRecord r, int callingUid, int callingPid,
            String listenerName, int reason) {
        Notification n = r.getNotification();
        if (!n.isGroupSummary()) {
            return;
        }

        String pkg = r.sbn.getPackageName();
        int userId = r.getUserId();

        if (pkg == null) {
            if (DBG) Log.e(TAG, "No package for group summary: " + r.getKey());
            return;
        }

        final int N = mNotificationList.size();
        for (int i = N - 1; i >= 0; i--) {
            NotificationRecord childR = mNotificationList.get(i);
            StatusBarNotification childSbn = childR.sbn;
            if (childR.getNotification().isGroupChild() &&
                    childR.getGroupKey().equals(r.getGroupKey())) {
                EventLogTags.writeNotificationCancel(callingUid, callingPid, pkg, childSbn.getId(),
                        childSbn.getTag(), userId, 0, 0, reason, listenerName);
                mNotificationList.remove(i);
                cancelNotificationLocked(childR, false, reason);
            }
        }
    }

    // lock on mNotificationList
    void updateLightsLocked()
    {
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
        if (ledNotification == null || mInCall || mScreenOn) {
            mNotificationLight.turnOff();
            mStatusBar.notificationLightOff();
        } else {
            final Notification ledno = ledNotification.sbn.getNotification();
            int ledARGB = ledno.ledARGB;
            int ledOnMS = ledno.ledOnMS;
            int ledOffMS = ledno.ledOffMS;
            if ((ledno.defaults & Notification.DEFAULT_LIGHTS) != 0) {
                ledARGB = mDefaultNotificationColor;
                ledOnMS = mDefaultNotificationLedOn;
                ledOffMS = mDefaultNotificationLedOff;
            }
            if (mNotificationPulseEnabled) {
                // pulse repeatedly
                mNotificationLight.setFlashing(ledARGB, Light.LIGHT_FLASH_TIMED,
                        ledOnMS, ledOffMS);
            }
            // let SystemUI make an independent decision
            mStatusBar.notificationLightPulse(ledARGB, ledOnMS, ledOffMS);
        }
    }

    // lock on mNotificationList
    int indexOfNotificationLocked(String pkg, String tag, int id, int userId)
    {
        ArrayList<NotificationRecord> list = mNotificationList;
        final int len = list.size();
        for (int i=0; i<len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId) && r.sbn.getId() == id &&
                    TextUtils.equals(r.sbn.getTag(), tag) && r.sbn.getPackageName().equals(pkg)) {
                return i;
            }
        }
        return -1;
    }

    // lock on mNotificationList
    int indexOfNotificationLocked(String key) {
        final int N = mNotificationList.size();
        for (int i = 0; i < N; i++) {
            if (key.equals(mNotificationList.get(i).getKey())) {
                return i;
            }
        }
        return -1;
    }

    private void updateNotificationPulse() {
        synchronized (mNotificationList) {
            updateLightsLocked();
        }
    }

    private static boolean isUidSystem(int uid) {
        final int appid = UserHandle.getAppId(uid);
        return (appid == Process.SYSTEM_UID || appid == Process.PHONE_UID || uid == 0);
    }

    private static boolean isCallerSystem() {
        return isUidSystem(Binder.getCallingUid());
    }

    private static void checkCallerIsSystem() {
        if (isCallerSystem()) {
            return;
        }
        throw new SecurityException("Disallowed call for uid " + Binder.getCallingUid());
    }

    private static void checkCallerIsSystemOrSameApp(String pkg) {
        if (isCallerSystem()) {
            return;
        }
        checkCallerIsSameApp(pkg);
    }

    private static void checkCallerIsSameApp(String pkg) {
        final int uid = Binder.getCallingUid();
        try {
            ApplicationInfo ai = AppGlobals.getPackageManager().getApplicationInfo(
                    pkg, 0, UserHandle.getCallingUserId());
            if (ai == null) {
                throw new SecurityException("Unknown package " + pkg);
            }
            if (!UserHandle.isSameApp(ai.uid, uid)) {
                throw new SecurityException("Calling uid " + uid + " gave package"
                        + pkg + " which is owned by uid " + ai.uid);
            }
        } catch (RemoteException re) {
            throw new SecurityException("Unknown package " + pkg + "\n" + re);
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

    private void listenForCallState() {
        TelephonyManager.from(getContext()).listen(new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                if (mCallState == state) return;
                if (DBG) Slog.d(TAG, "Call state changed: " + callStateToString(state));
                mCallState = state;
            }
        }, PhoneStateListener.LISTEN_CALL_STATE);
    }

    /**
     * Generates a NotificationRankingUpdate from 'sbns', considering only
     * notifications visible to the given listener.
     *
     * <p>Caller must hold a lock on mNotificationList.</p>
     */
    private NotificationRankingUpdate makeRankingUpdateLocked(ManagedServiceInfo info) {
        int speedBumpIndex = -1;
        final int N = mNotificationList.size();
        ArrayList<String> keys = new ArrayList<String>(N);
        ArrayList<String> interceptedKeys = new ArrayList<String>(N);
        Bundle visibilityOverrides = new Bundle();
        for (int i = 0; i < N; i++) {
            NotificationRecord record = mNotificationList.get(i);
            if (!isVisibleToListener(record.sbn, info)) {
                continue;
            }
            keys.add(record.sbn.getKey());
            if (record.isIntercepted()) {
                interceptedKeys.add(record.sbn.getKey());
            }
            if (record.getPackageVisibilityOverride()
                    != NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE) {
                visibilityOverrides.putInt(record.sbn.getKey(),
                        record.getPackageVisibilityOverride());
            }
            // Find first min-prio notification for speedbump placement.
            if (speedBumpIndex == -1 &&
                    // Intrusiveness trumps priority, hence ignore intrusives.
                    !record.isRecentlyIntrusive() &&
                    // Currently, package priority is either PRIORITY_DEFAULT or PRIORITY_MAX, so
                    // scanning for PRIORITY_MIN within the package bucket PRIORITY_DEFAULT
                    // (or lower as a safeguard) is sufficient to find the speedbump index.
                    // We'll have to revisit this when more package priority buckets are introduced.
                    record.getPackagePriority() <= Notification.PRIORITY_DEFAULT &&
                    record.sbn.getNotification().priority == Notification.PRIORITY_MIN) {
                speedBumpIndex = keys.size() - 1;
            }
        }
        String[] keysAr = keys.toArray(new String[keys.size()]);
        String[] interceptedKeysAr = interceptedKeys.toArray(new String[interceptedKeys.size()]);
        return new NotificationRankingUpdate(keysAr, interceptedKeysAr, visibilityOverrides,
                speedBumpIndex);
    }

    private boolean isVisibleToListener(StatusBarNotification sbn, ManagedServiceInfo listener) {
        if (!listener.enabledAndUserMatches(sbn.getUserId())) {
            return false;
        }
        // TODO: remove this for older listeners.
        return true;
    }

    public class NotificationListeners extends ManagedServices {

        private final ArraySet<ManagedServiceInfo> mLightTrimListeners = new ArraySet<>();
        private boolean mNotificationGroupsDesired;

        public NotificationListeners() {
            super(getContext(), mHandler, mNotificationList, mUserProfiles);
        }

        @Override
        protected Config getConfig() {
            Config c = new Config();
            c.caption = "notification listener";
            c.serviceInterface = NotificationListenerService.SERVICE_INTERFACE;
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
        public void onServiceAdded(ManagedServiceInfo info) {
            final INotificationListener listener = (INotificationListener) info.service;
            final NotificationRankingUpdate update;
            synchronized (mNotificationList) {
                updateNotificationGroupsDesiredLocked();
                update = makeRankingUpdateLocked(info);
            }
            try {
                listener.onListenerConnected(update);
            } catch (RemoteException e) {
                // we tried
            }
        }

        @Override
        protected void onServiceRemovedLocked(ManagedServiceInfo removed) {
            if (mListenersDisablingEffects.remove(removed)) {
                updateListenerHintsLocked();
                updateEffectsSuppressorLocked();
            }
            mLightTrimListeners.remove(removed);
            updateNotificationGroupsDesiredLocked();
        }

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

        /**
         * asynchronously notify all listeners about a new notification
         *
         * <p>
         * Also takes care of removing a notification that has been visible to a listener before,
         * but isn't anymore.
         */
        public void notifyPostedLocked(StatusBarNotification sbn, StatusBarNotification oldSbn) {
            // Lazily initialized snapshots of the notification.
            StatusBarNotification sbnClone = null;
            StatusBarNotification sbnCloneLight = null;

            for (final ManagedServiceInfo info : mServices) {
                boolean sbnVisible = isVisibleToListener(sbn, info);
                boolean oldSbnVisible = oldSbn != null ? isVisibleToListener(oldSbn, info) : false;
                // This notification hasn't been and still isn't visible -> ignore.
                if (!oldSbnVisible && !sbnVisible) {
                    continue;
                }
                final NotificationRankingUpdate update = makeRankingUpdateLocked(info);

                // This notification became invisible -> remove the old one.
                if (oldSbnVisible && !sbnVisible) {
                    final StatusBarNotification oldSbnLightClone = oldSbn.cloneLight();
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            notifyRemoved(info, oldSbnLightClone, update);
                        }
                    });
                    continue;
                }

                final int trim = mListeners.getOnNotificationPostedTrim(info);

                if (trim == TRIM_LIGHT && sbnCloneLight == null) {
                    sbnCloneLight = sbn.cloneLight();
                } else if (trim == TRIM_FULL && sbnClone == null) {
                    sbnClone = sbn.clone();
                }
                final StatusBarNotification sbnToPost =
                        (trim == TRIM_FULL) ? sbnClone : sbnCloneLight;

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyPosted(info, sbnToPost, update);
                    }
                });
            }
        }

        /**
         * asynchronously notify all listeners about a removed notification
         */
        public void notifyRemovedLocked(StatusBarNotification sbn) {
            // make a copy in case changes are made to the underlying Notification object
            // NOTE: this copy is lightweight: it doesn't include heavyweight parts of the
            // notification
            final StatusBarNotification sbnLight = sbn.cloneLight();
            for (final ManagedServiceInfo info : mServices) {
                if (!isVisibleToListener(sbn, info)) {
                    continue;
                }
                final NotificationRankingUpdate update = makeRankingUpdateLocked(info);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyRemoved(info, sbnLight, update);
                    }
                });
            }
        }

        /**
         * asynchronously notify all listeners about a reordering of notifications
         */
        public void notifyRankingUpdateLocked() {
            for (final ManagedServiceInfo serviceInfo : mServices) {
                if (!serviceInfo.isEnabledForCurrentProfiles()) {
                    continue;
                }
                final NotificationRankingUpdate update = makeRankingUpdateLocked(serviceInfo);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyRankingUpdate(serviceInfo, update);
                    }
                });
            }
        }

        public void notifyListenerHintsChangedLocked(final int hints) {
            for (final ManagedServiceInfo serviceInfo : mServices) {
                if (!serviceInfo.isEnabledForCurrentProfiles()) {
                    continue;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyListenerHintsChanged(serviceInfo, hints);
                    }
                });
            }
        }

        public void notifyInterruptionFilterChanged(final int interruptionFilter) {
            for (final ManagedServiceInfo serviceInfo : mServices) {
                if (!serviceInfo.isEnabledForCurrentProfiles()) {
                    continue;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyInterruptionFilterChanged(serviceInfo, interruptionFilter);
                    }
                });
            }
        }

        private void notifyPosted(final ManagedServiceInfo info,
                final StatusBarNotification sbn, NotificationRankingUpdate rankingUpdate) {
            final INotificationListener listener = (INotificationListener)info.service;
            StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbn);
            try {
                listener.onNotificationPosted(sbnHolder, rankingUpdate);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (posted): " + listener, ex);
            }
        }

        private void notifyRemoved(ManagedServiceInfo info, StatusBarNotification sbn,
                NotificationRankingUpdate rankingUpdate) {
            if (!info.enabledAndUserMatches(sbn.getUserId())) {
                return;
            }
            final INotificationListener listener = (INotificationListener) info.service;
            StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbn);
            try {
                listener.onNotificationRemoved(sbnHolder, rankingUpdate);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (removed): " + listener, ex);
            }
        }

        private void notifyRankingUpdate(ManagedServiceInfo info,
                                         NotificationRankingUpdate rankingUpdate) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onNotificationRankingUpdate(rankingUpdate);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (ranking update): " + listener, ex);
            }
        }

        private void notifyListenerHintsChanged(ManagedServiceInfo info, int hints) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onListenerHintsChanged(hints);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (listener hints): " + listener, ex);
            }
        }

        private void notifyInterruptionFilterChanged(ManagedServiceInfo info,
                int interruptionFilter) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onInterruptionFilterChanged(interruptionFilter);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (interruption filter): " + listener, ex);
            }
        }

        private boolean isListenerPackage(String packageName) {
            if (packageName == null) {
                return false;
            }
            // TODO: clean up locking object later
            synchronized (mNotificationList) {
                for (final ManagedServiceInfo serviceInfo : mServices) {
                    if (packageName.equals(serviceInfo.component.getPackageName())) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Returns whether any of the currently registered listeners wants to receive notification
         * groups.
         *
         * <p>Currently we assume groups are desired by non-SystemUI listeners.</p>
         */
        public boolean notificationGroupsDesired() {
            return mNotificationGroupsDesired;
        }

        private void updateNotificationGroupsDesiredLocked() {
            mNotificationGroupsDesired = true;
            // No listeners, no groups.
            if (mServices.isEmpty()) {
                mNotificationGroupsDesired = false;
                return;
            }
            // One listener: Check whether it's SysUI.
            if (mServices.size() == 1 &&
                    mServices.get(0).component.getPackageName().equals("com.android.systemui")) {
                mNotificationGroupsDesired = false;
                return;
            }
        }
    }

    public static final class DumpFilter {
        public boolean filtered = false;
        public String pkgFilter;
        public boolean zen;
        public long since;
        public boolean stats;
        public boolean redact = true;

        public static DumpFilter parseFromArguments(String[] args) {
            final DumpFilter filter = new DumpFilter();
            for (int ai = 0; ai < args.length; ai++) {
                final String a = args[ai];
                if ("--noredact".equals(a) || "--reveal".equals(a)) {
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
                        filter.since = Long.valueOf(args[ai]);
                    } else {
                        filter.since = 0;
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

    private final class PolicyAccess {
        private static final String SEPARATOR = ":";
        private final String[] PERM = {
            android.Manifest.permission.ACCESS_NOTIFICATION_POLICY
        };

        public boolean isPackageGranted(String pkg) {
            return pkg != null && getGrantedPackages().contains(pkg);
        }

        public void put(String pkg, boolean granted) {
            if (pkg == null) return;
            final ArraySet<String> pkgs = getGrantedPackages();
            boolean changed;
            if (granted) {
                changed = pkgs.add(pkg);
            } else {
                changed = pkgs.remove(pkg);
            }
            if (!changed) return;
            final String setting = TextUtils.join(SEPARATOR, pkgs);
            final int currentUser = ActivityManager.getCurrentUser();
            Settings.Secure.putStringForUser(getContext().getContentResolver(),
                    Settings.Secure.ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES,
                    setting,
                    currentUser);
            getContext().sendBroadcastAsUser(new Intent(NotificationManager
                    .ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY), new UserHandle(currentUser), null);
        }

        public ArraySet<String> getGrantedPackages() {
            final ArraySet<String> pkgs = new ArraySet<>();

            long identity = Binder.clearCallingIdentity();
            try {
                final String setting = Settings.Secure.getStringForUser(
                        getContext().getContentResolver(),
                        Settings.Secure.ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES,
                        ActivityManager.getCurrentUser());
                if (setting != null) {
                    final String[] tokens = setting.split(SEPARATOR);
                    for (int i = 0; i < tokens.length; i++) {
                        String token = tokens[i];
                        if (token != null) {
                            token.trim();
                        }
                        if (TextUtils.isEmpty(token)) {
                            continue;
                        }
                        pkgs.add(token);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return pkgs;
        }

        public String[] getRequestingPackages() throws RemoteException {
            final ParceledListSlice list = AppGlobals.getPackageManager()
                    .getPackagesHoldingPermissions(PERM, 0 /*flags*/,
                            ActivityManager.getCurrentUser());
            final List<PackageInfo> pkgs = list.getList();
            if (pkgs == null || pkgs.isEmpty()) return new String[0];
            final int N = pkgs.size();
            final String[] rt = new String[N];
            for (int i = 0; i < N; i++) {
                rt[i] = pkgs.get(i).packageName;
            }
            return rt;
        }
    }
}

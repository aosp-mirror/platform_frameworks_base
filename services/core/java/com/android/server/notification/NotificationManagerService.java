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
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.IRingtonePlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.INotificationListener;
import android.service.notification.IConditionListener;
import android.service.notification.IConditionProvider;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationRankingUpdate;
import android.service.notification.StatusBarNotification;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.EventLogTags;
import com.android.server.SystemService;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;
import com.android.server.notification.ManagedServices.ManagedServiceInfo;
import com.android.server.notification.ManagedServices.UserProfiles;
import com.android.server.notification.NotificationUsageStats.SingleNotificationStats;
import com.android.server.statusbar.StatusBarManagerInternal;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** {@hide} */
public class NotificationManagerService extends SystemService {
    static final String TAG = "NotificationService";
    static final boolean DBG = false;

    static final int MAX_PACKAGE_NOTIFICATIONS = 50;

    // message codes
    static final int MESSAGE_TIMEOUT = 2;
    static final int MESSAGE_SAVE_POLICY_FILE = 3;
    static final int MESSAGE_RECONSIDER_RANKING = 4;
    static final int MESSAGE_SEND_RANKING_UPDATE = 5;

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

    private IActivityManager mAm;
    AudioManager mAudioManager;
    StatusBarManagerInternal mStatusBar;
    Vibrator mVibrator;

    final IBinder mForegroundToken = new Binder();
    private WorkerHandler mHandler;
    private final HandlerThread mRankingThread = new HandlerThread("ranker",
            Process.THREAD_PRIORITY_BACKGROUND);
    private Handler mRankingHandler = null;

    private Light mNotificationLight;
    Light mAttentionLight;
    private int mDefaultNotificationColor;
    private int mDefaultNotificationLedOn;

    private int mDefaultNotificationLedOff;
    private long[] mDefaultVibrationPattern;

    private long[] mFallbackVibrationPattern;
    boolean mSystemReady;

    private boolean mDisableNotificationAlerts;
    NotificationRecord mSoundNotification;
    NotificationRecord mVibrateNotification;

    // for enabling and disabling notification pulse behavior
    private boolean mScreenOn = true;
    private boolean mInCall = false;
    private boolean mNotificationPulseEnabled;

    // used as a mutex for access to all active notifications & listeners
    final ArrayList<NotificationRecord> mNotificationList =
            new ArrayList<NotificationRecord>();
    final NotificationComparator mRankingComparator = new NotificationComparator();
    final ArrayMap<String, NotificationRecord> mNotificationsByKey =
            new ArrayMap<String, NotificationRecord>();
    final ArrayList<ToastRecord> mToastQueue = new ArrayList<ToastRecord>();

    ArrayList<NotificationRecord> mLights = new ArrayList<NotificationRecord>();
    NotificationRecord mLedNotification;

    private AppOpsManager mAppOps;

    // Notification control database. For now just contains disabled packages.
    private AtomicFile mPolicyFile;
    private HashSet<String> mBlockedPackages = new HashSet<String>();

    private static final int DB_VERSION = 1;

    private static final String TAG_BODY = "notification-policy";
    private static final String ATTR_VERSION = "version";

    private static final String TAG_BLOCKED_PKGS = "blocked-packages";
    private static final String TAG_PACKAGE = "package";
    private static final String ATTR_NAME = "name";

    final ArrayList<NotificationSignalExtractor> mSignalExtractors = new ArrayList<NotificationSignalExtractor>();

    private final UserProfiles mUserProfiles = new UserProfiles();
    private NotificationListeners mListeners;
    private ConditionProviders mConditionProviders;
    private NotificationUsageStats mUsageStats;

    private static final String EXTRA_INTERCEPT = "android.intercept";

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

    private static class Archive {
        static final int BUFFER_SIZE = 250;
        ArrayDeque<StatusBarNotification> mBuffer = new ArrayDeque<StatusBarNotification>(BUFFER_SIZE);

        public Archive() {
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
            if (mBuffer.size() == BUFFER_SIZE) {
                mBuffer.removeFirst();
            }

            // We don't want to store the heavy bits of the notification in the archive,
            // but other clients in the system process might be using the object, so we
            // store a (lightened) copy.
            mBuffer.addLast(nr.cloneLight());
        }


        public void clear() {
            mBuffer.clear();
        }

        public Iterator<StatusBarNotification> descendingIterator() {
            return mBuffer.descendingIterator();
        }
        public Iterator<StatusBarNotification> ascendingIterator() {
            return mBuffer.iterator();
        }
        public Iterator<StatusBarNotification> filter(
                final Iterator<StatusBarNotification> iter, final String pkg, final int userId) {
            return new Iterator<StatusBarNotification>() {
                StatusBarNotification mNext = findNext();

                private StatusBarNotification findNext() {
                    while (iter.hasNext()) {
                        StatusBarNotification nr = iter.next();
                        if ((pkg == null || nr.getPackageName() == pkg)
                                && (userId == UserHandle.USER_ALL || nr.getUserId() == userId)) {
                            return nr;
                        }
                    }
                    return null;
                }

                @Override
                public boolean hasNext() {
                    return mNext == null;
                }

                @Override
                public StatusBarNotification next() {
                    StatusBarNotification next = mNext;
                    if (next == null) {
                        throw new NoSuchElementException();
                    }
                    mNext = findNext();
                    return next;
                }

                @Override
                public void remove() {
                    iter.remove();
                }
            };
        }

        public StatusBarNotification[] getArray(int count) {
            if (count == 0) count = Archive.BUFFER_SIZE;
            final StatusBarNotification[] a
                    = new StatusBarNotification[Math.min(count, mBuffer.size())];
            Iterator<StatusBarNotification> iter = descendingIterator();
            int i=0;
            while (iter.hasNext() && i < count) {
                a[i++] = iter.next();
            }
            return a;
        }

        public StatusBarNotification[] getArray(int count, String pkg, int userId) {
            if (count == 0) count = Archive.BUFFER_SIZE;
            final StatusBarNotification[] a
                    = new StatusBarNotification[Math.min(count, mBuffer.size())];
            Iterator<StatusBarNotification> iter = filter(descendingIterator(), pkg, userId);
            int i=0;
            while (iter.hasNext() && i < count) {
                a[i++] = iter.next();
            }
            return a;
        }

    }

    Archive mArchive = new Archive();

    private void loadPolicyFile() {
        synchronized(mPolicyFile) {
            mBlockedPackages.clear();

            FileInputStream infile = null;
            try {
                infile = mPolicyFile.openRead();
                final XmlPullParser parser = Xml.newPullParser();
                parser.setInput(infile, null);

                int type;
                String tag;
                int version = DB_VERSION;
                while ((type = parser.next()) != END_DOCUMENT) {
                    tag = parser.getName();
                    if (type == START_TAG) {
                        if (TAG_BODY.equals(tag)) {
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
                    mZenModeHelper.readXml(parser);
                }
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
        Slog.d(TAG, "handleSavePolicyFile");
        synchronized (mPolicyFile) {
            final FileOutputStream stream;
            try {
                stream = mPolicyFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to save policy file", e);
                return;
            }

            try {
                final XmlSerializer out = new FastXmlSerializer();
                out.setOutput(stream, "utf-8");
                out.startDocument(null, true);
                out.startTag(null, TAG_BODY);
                out.attribute(null, ATTR_VERSION, Integer.toString(DB_VERSION));
                mZenModeHelper.writeXml(out);
                out.endTag(null, TAG_BODY);
                out.endDocument();
                mPolicyFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to save policy file, restoring backup", e);
                mPolicyFile.failWrite(stream);
            }
        }
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

    private static String idDebugString(Context baseContext, String packageName, int id) {
        Context c = null;

        if (packageName != null) {
            try {
                c = baseContext.createPackageContext(packageName, 0);
            } catch (NameNotFoundException e) {
                c = baseContext;
            }
        } else {
            c = baseContext;
        }

        String pkg;
        String type;
        String name;

        Resources r = c.getResources();
        try {
            return r.getResourceName(id);
        } catch (Resources.NotFoundException e) {
            return "<name unknown>";
        }
    }



    public static final class NotificationRecord
    {
        final StatusBarNotification sbn;
        SingleNotificationStats stats;
        IBinder statusBarKey;

        // These members are used by NotificationSignalExtractors
        // to communicate with the ranking module.
        private float mContactAffinity;
        private boolean mRecentlyIntrusive;

        NotificationRecord(StatusBarNotification sbn)
        {
            this.sbn = sbn;
        }

        public Notification getNotification() { return sbn.getNotification(); }
        public int getFlags() { return sbn.getNotification().flags; }
        public int getUserId() { return sbn.getUserId(); }

        void dump(PrintWriter pw, String prefix, Context baseContext) {
            final Notification notification = sbn.getNotification();
            pw.println(prefix + this);
            pw.println(prefix + "  uid=" + sbn.getUid() + " userId=" + sbn.getUserId());
            pw.println(prefix + "  icon=0x" + Integer.toHexString(notification.icon)
                    + " / " + idDebugString(baseContext, sbn.getPackageName(), notification.icon));
            pw.println(prefix + "  pri=" + notification.priority + " score=" + sbn.getScore());
            pw.println(prefix + "  key=" + sbn.getKey());
            pw.println(prefix + "  contentIntent=" + notification.contentIntent);
            pw.println(prefix + "  deleteIntent=" + notification.deleteIntent);
            pw.println(prefix + "  tickerText=" + notification.tickerText);
            pw.println(prefix + "  contentView=" + notification.contentView);
            pw.println(prefix + String.format("  defaults=0x%08x flags=0x%08x",
                    notification.defaults, notification.flags));
            pw.println(prefix + "  sound=" + notification.sound);
            pw.println(prefix + String.format("  color=0x%08x", notification.color));
            pw.println(prefix + "  vibrate=" + Arrays.toString(notification.vibrate));
            pw.println(prefix + String.format("  led=0x%08x onMs=%d offMs=%d",
                    notification.ledARGB, notification.ledOnMS, notification.ledOffMS));
            if (notification.actions != null && notification.actions.length > 0) {
                pw.println(prefix + "  actions={");
                final int N = notification.actions.length;
                for (int i=0; i<N; i++) {
                    final Notification.Action action = notification.actions[i];
                    pw.println(String.format("%s    [%d] \"%s\" -> %s",
                            prefix,
                            i,
                            action.title,
                            action.actionIntent.toString()
                            ));
                }
                pw.println(prefix + "  }");
            }
            if (notification.extras != null && notification.extras.size() > 0) {
                pw.println(prefix + "  extras={");
                for (String key : notification.extras.keySet()) {
                    pw.print(prefix + "    " + key + "=");
                    Object val = notification.extras.get(key);
                    if (val == null) {
                        pw.println("null");
                    } else {
                        pw.print(val.toString());
                        if (val instanceof Bitmap) {
                            pw.print(String.format(" (%dx%d)",
                                    ((Bitmap) val).getWidth(),
                                    ((Bitmap) val).getHeight()));
                        } else if (val.getClass().isArray()) {
                            pw.println(" {");
                            final int N = Array.getLength(val);
                            for (int i=0; i<N; i++) {
                                if (i > 0) pw.println(",");
                                pw.print(prefix + "      " + Array.get(val, i));
                            }
                            pw.print("\n" + prefix + "    }");
                        }
                        pw.println();
                    }
                }
                pw.println(prefix + "  }");
            }
            pw.println(prefix + "  stats=" + stats.toString());
            pw.println(prefix + "  mContactAffinity=" + mContactAffinity);
            pw.println(prefix + "  mRecentlyIntrusive=" + mRecentlyIntrusive);
        }

        @Override
        public final String toString() {
            return String.format(
                    "NotificationRecord(0x%08x: pkg=%s user=%s id=%d tag=%s score=%d key=%s: %s)",
                    System.identityHashCode(this),
                    this.sbn.getPackageName(), this.sbn.getUser(), this.sbn.getId(),
                    this.sbn.getTag(), this.sbn.getScore(), this.sbn.getKey(),
                    this.sbn.getNotification());
        }

        public void setContactAffinity(float contactAffinity) {
            mContactAffinity = contactAffinity;
        }

        public float getContactAffinity() {
            return mContactAffinity;
        }

        public boolean isRecentlyIntrusive() {
            return mRecentlyIntrusive;
        }

        public void setRecentlyIntusive(boolean recentlyIntrusive) {
            mRecentlyIntrusive = recentlyIntrusive;
        }
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

        void dump(PrintWriter pw, String prefix) {
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
                mDisableNotificationAlerts = (status & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0;
                if (mDisableNotificationAlerts) {
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
                EventLogTags.writeNotificationClicked(key);
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r == null) {
                    Log.w(TAG, "No notification with key: " + key);
                    return;
                }
                StatusBarNotification sbn = r.sbn;
                cancelNotification(callingUid, callingPid, sbn.getPackageName(), sbn.getTag(),
                        sbn.getId(), Notification.FLAG_AUTO_CANCEL,
                        Notification.FLAG_FOREGROUND_SERVICE, false, r.getUserId(),
                        REASON_DELEGATE_CLICK, null);
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
        public void onPanelRevealed() {
            EventLogTags.writeNotificationPanelRevealed();
            synchronized (mNotificationList) {
                // sound
                mSoundNotification = null;

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
                mVibrateNotification = null;
                identity = Binder.clearCallingIdentity();
                try {
                    mVibrator.cancel();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

                // light
                mLights.clear();
                mLedNotification = null;
                updateLightsLocked();
            }
        }

        @Override
        public void onPanelHidden() {
            EventLogTags.writeNotificationPanelHidden();
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
        public boolean allowDisable(int what, IBinder token, String pkg) {
            return mZenModeHelper.allowDisable(what, token, pkg);
        }

        @Override
        public void onNotificationVisibilityChanged(
                String[] newlyVisibleKeys, String[] noLongerVisibleKeys) {
            // Using ';' as separator since eventlogs uses ',' to separate
            // args.
            EventLogTags.writeNotificationVisibilityChanged(
                    TextUtils.join(";", newlyVisibleKeys),
                    TextUtils.join(";", noLongerVisibleKeys));
            synchronized (mNotificationList) {
                for (String key : newlyVisibleKeys) {
                    NotificationRecord r = mNotificationsByKey.get(key);
                    if (r == null) continue;
                    r.stats.onVisibilityChanged(true);
                }
                // Note that we might receive this event after notifications
                // have already left the system, e.g. after dismissing from the
                // shade. Hence not finding notifications in
                // mNotificationsByKey is not an exceptional condition.
                for (String key : noLongerVisibleKeys) {
                    NotificationRecord r = mNotificationsByKey.get(key);
                    if (r == null) continue;
                    r.stats.onVisibilityChanged(false);
                }
            }
        }
    };

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

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
                            final int enabled = getContext().getPackageManager()
                                    .getApplicationEnabledSetting(pkgName);
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
                        }
                    }
                    pkgList = new String[]{pkgName};
                }

                if (pkgList != null && (pkgList.length > 0)) {
                    for (String pkgName : pkgList) {
                        if (cancelNotifications) {
                            cancelAllNotificationsInt(MY_UID, MY_PID, pkgName, 0, 0, !queryRestart,
                                    UserHandle.USER_ALL, REASON_PACKAGE_CHANGED, null);
                        }
                    }
                }
                mListeners.onPackagesChanged(queryReplace, pkgList);
                mConditionProviders.onPackagesChanged(queryReplace, pkgList);
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                // Keep track of screen on/off state, but do not turn off the notification light
                // until user passes through the lock screen or views the notification.
                mScreenOn = true;
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mScreenOn = false;
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
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                // reload per-user settings
                mSettingsObserver.update(null);
                mUserProfiles.updateCache(context);
            } else if (action.equals(Intent.ACTION_USER_ADDED)) {
                mUserProfiles.updateCache(context);
            }
        }
    };

    class SettingsObserver extends ContentObserver {
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
        mAm = ActivityManagerNative.getDefault();
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mVibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);

        mHandler = new WorkerHandler();
        mRankingThread.start();
        mRankingHandler = new RankingWorkerHandler(mRankingThread.getLooper());
        mZenModeHelper = new ZenModeHelper(getContext(), mHandler);
        mZenModeHelper.addCallback(new ZenModeHelper.Callback() {
            @Override
            public void onConfigChanged() {
                savePolicyFile();
            }
        });
        final File systemDir = new File(Environment.getDataDirectory(), "system");
        mPolicyFile = new AtomicFile(new File(systemDir, "notification_policy.xml"));
        mUsageStats = new NotificationUsageStats(getContext());

        importOldBlockDb();

        mListeners = new NotificationListeners();
        mConditionProviders = new ConditionProviders(getContext(),
                mHandler, mUserProfiles, mZenModeHelper);
        mStatusBar = getLocalService(StatusBarManagerInternal.class);
        mStatusBar.setNotificationDelegate(mNotificationDelegate);

        final LightsManager lights = getLocalService(LightsManager.class);
        mNotificationLight = lights.getLight(LightsManager.LIGHT_ID_NOTIFICATIONS);
        mAttentionLight = lights.getLight(LightsManager.LIGHT_ID_ATTENTION);

        Resources resources = getContext().getResources();
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

        // Don't start allowing notifications until the setup wizard has run once.
        // After that, including subsequent boots, init with notifications turned on.
        // This works on the first boot because the setup wizard will toggle this
        // flag at least once and we'll go back to 0 after that.
        if (0 == Settings.Global.getInt(getContext().getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0)) {
            mDisableNotificationAlerts = true;
        }
        mZenModeHelper.updateZenMode();

        mUserProfiles.updateCache(getContext());

        // register for various Intents
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_STOPPED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        getContext().registerReceiver(mIntentReceiver, filter);
        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        pkgFilter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
        pkgFilter.addDataScheme("package");
        getContext().registerReceiver(mIntentReceiver, pkgFilter);
        IntentFilter sdFilter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        getContext().registerReceiver(mIntentReceiver, sdFilter);

        mSettingsObserver = new SettingsObserver(mHandler);

        // spin up NotificationSignalExtractors
        String[] extractorNames = resources.getStringArray(
                R.array.config_notificationSignalExtractors);
        for (String extractorName : extractorNames) {
            try {
                Class<?> extractorClass = getContext().getClassLoader().loadClass(extractorName);
                NotificationSignalExtractor extractor =
                        (NotificationSignalExtractor) extractorClass.newInstance();
                extractor.initialize(getContext());
                mSignalExtractors.add(extractor);
            } catch (ClassNotFoundException e) {
                Slog.w(TAG, "Couldn't find extractor " + extractorName + ".", e);
            } catch (InstantiationException e) {
                Slog.w(TAG, "Couldn't instantiate extractor " + extractorName + ".", e);
            } catch (IllegalAccessException e) {
                Slog.w(TAG, "Problem accessing extractor " + extractorName + ".", e);
            }
        }

        publishBinderService(Context.NOTIFICATION_SERVICE, mService);
        publishLocalService(NotificationManagerInternal.class, mInternalService);
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
            checkCallerIsSystem();
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
                            final int userId = r.sbn.getUserId();
                            if (userId != info.userid && userId != UserHandle.USER_ALL &&
                                    !mUserProfiles.isCurrentProfile(userId)) {
                                throw new SecurityException("Disallowed call from listener: "
                                        + info.service);
                            }
                            if (r != null) {
                                cancelNotificationFromListenerLocked(info, callingUid, callingPid,
                                        r.sbn.getPackageName(), r.sbn.getTag(), r.sbn.getId(),
                                        userId);
                            }
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
         * @param keys the notification keys to fetch, or null for all active notifications.
         * @returns The return value will contain the notifications specified in keys, in that
         *      order, or if keys is null, all the notifications, in natural order.
         */
        @Override
        public StatusBarNotification[] getActiveNotificationsFromListener(
                INotificationListener token, String[] keys) {
            synchronized (mNotificationList) {
                final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                final ArrayList<StatusBarNotification> list
                        = new ArrayList<StatusBarNotification>();
                if (keys == null) {
                    final int N = mNotificationList.size();
                    for (int i=0; i<N; i++) {
                        StatusBarNotification sbn = mNotificationList.get(i).sbn;
                        if (info.enabledAndUserMatches(sbn.getUserId())) {
                            list.add(sbn);
                        }
                    }
                } else {
                    final int N = keys.length;
                    for (int i=0; i<N; i++) {
                        NotificationRecord r = mNotificationsByKey.get(keys[i]);
                        if (r != null && info.enabledAndUserMatches(r.sbn.getUserId())) {
                            list.add(r.sbn);
                        }
                    }
                }
                return list.toArray(new StatusBarNotification[list.size()]);
            }
        }

        @Override
        public String[] getActiveNotificationKeysFromListener(INotificationListener token) {
            return NotificationManagerService.this.getActiveNotificationKeys(token);
        }

        @Override
        public ZenModeConfig getZenModeConfig() {
            checkCallerIsSystem();
            return mZenModeHelper.getConfig();
        }

        @Override
        public boolean setZenModeConfig(ZenModeConfig config) {
            checkCallerIsSystem();
            return mZenModeHelper.setConfig(config);
        }

        @Override
        public void notifyConditions(String pkg, IConditionProvider provider,
                Condition[] conditions) {
            final ManagedServiceInfo info = mConditionProviders.checkServiceToken(provider);
            checkCallerIsSystemOrSameApp(pkg);
            final long identity = Binder.clearCallingIdentity();
            try {
                mConditionProviders.notifyConditions(pkg, info, conditions);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestZenModeConditions(IConditionListener callback, int relevance) {
            enforceSystemOrSystemUI("INotificationManager.requestZenModeConditions");
            mConditionProviders.requestZenModeConditions(callback, relevance);
        }

        @Override
        public void setZenModeCondition(Uri conditionId) {
            enforceSystemOrSystemUI("INotificationManager.setZenModeCondition");
            final long identity = Binder.clearCallingIdentity();
            try {
                mConditionProviders.setZenModeCondition(conditionId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setAutomaticZenModeConditions(Uri[] conditionIds) {
            enforceSystemOrSystemUI("INotificationManager.setAutomaticZenModeConditions");
            mConditionProviders.setAutomaticZenModeConditions(conditionIds);
        }

        @Override
        public Condition[] getAutomaticZenModeConditions() {
            enforceSystemOrSystemUI("INotificationManager.getAutomaticZenModeConditions");
            return mConditionProviders.getAutomaticZenModeConditions();
        }

        private void enforceSystemOrSystemUI(String message) {
            if (isCallerSystem()) return;
            getContext().enforceCallingPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                    message);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump NotificationManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            dumpImpl(pw);
        }
    };

    private String[] getActiveNotificationKeys(INotificationListener token) {
        final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
        final ArrayList<String> keys = new ArrayList<String>();
        if (info.isEnabledForCurrentProfiles()) {
            synchronized (mNotificationList) {
                final int N = mNotificationList.size();
                for (int i = 0; i < N; i++) {
                    final StatusBarNotification sbn = mNotificationList.get(i).sbn;
                    if (info.enabledAndUserMatches(sbn.getUserId())) {
                        keys.add(sbn.getKey());
                    }
                }
            }
        }
        return keys.toArray(new String[keys.size()]);
    }

    void dumpImpl(PrintWriter pw) {
        pw.println("Current Notification Manager state:");

        int N;

        synchronized (mToastQueue) {
            N = mToastQueue.size();
            if (N > 0) {
                pw.println("  Toast Queue:");
                for (int i=0; i<N; i++) {
                    mToastQueue.get(i).dump(pw, "    ");
                }
                pw.println("  ");
            }

        }

        synchronized (mNotificationList) {
            N = mNotificationList.size();
            if (N > 0) {
                pw.println("  Notification List:");
                for (int i=0; i<N; i++) {
                    mNotificationList.get(i).dump(pw, "    ", getContext());
                }
                pw.println("  ");
            }

            N = mLights.size();
            if (N > 0) {
                pw.println("  Lights List:");
                for (int i=0; i<N; i++) {
                    pw.println("    " + mLights.get(i));
                }
                pw.println("  ");
            }

            pw.println("  mSoundNotification=" + mSoundNotification);
            pw.println("  mVibrateNotification=" + mVibrateNotification);
            pw.println("  mDisableNotificationAlerts=" + mDisableNotificationAlerts);
            pw.println("  mSystemReady=" + mSystemReady);
            pw.println("  mArchive=" + mArchive.toString());
            Iterator<StatusBarNotification> iter = mArchive.descendingIterator();
            int i=0;
            while (iter.hasNext()) {
                pw.println("    " + iter.next());
                if (++i >= 5) {
                    if (iter.hasNext()) pw.println("    ...");
                    break;
                }
            }

            pw.println("\n  Usage Stats:");
            mUsageStats.dump(pw, "    ");

            pw.println("\n  Zen Mode:");
            mZenModeHelper.dump(pw, "    ");

            pw.println("\n  Notification listeners:");
            mListeners.dump(pw);

            pw.println("\n  Condition providers:");
            mConditionProviders.dump(pw);
        }
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

        final int userId = ActivityManager.handleIncomingUser(callingPid,
                callingUid, incomingUserId, true, false, "enqueueNotification", pkg);
        final UserHandle user = new UserHandle(userId);

        // Limit the number of notifications that any given package except the android
        // package can enqueue.  Prevents DOS attacks and deals with leaks.
        if (!isSystemNotification) {
            synchronized (mNotificationList) {
                int count = 0;
                final int N = mNotificationList.size();
                for (int i=0; i<N; i++) {
                    final NotificationRecord r = mNotificationList.get(i);
                    if (r.sbn.getPackageName().equals(pkg) && r.sbn.getUserId() == userId) {
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

        // This conditional is a dirty hack to limit the logging done on
        //     behalf of the download manager without affecting other apps.
        if (!pkg.equals("com.android.providers.downloads")
                || Log.isLoggable("DownloadManager", Log.VERBOSE)) {
            EventLogTags.writeNotificationEnqueue(callingUid, callingPid,
                    pkg, id, tag, userId, notification.toString());
        }

        if (pkg == null || notification == null) {
            throw new IllegalArgumentException("null not allowed: pkg=" + pkg
                    + " id=" + id + " notification=" + notification);
        }
        if (notification.icon != 0) {
            if (notification.contentView == null) {
                throw new IllegalArgumentException("contentView required: pkg=" + pkg
                        + " id=" + id + " notification=" + notification);
            }
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {

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

                // 1. initial score: buckets of 10, around the app
                int score = notification.priority * NOTIFICATION_PRIORITY_MULTIPLIER; //[-20..20]

                // 2. extract ranking signals from the notification data
                final StatusBarNotification n = new StatusBarNotification(
                        pkg, opPkg, id, tag, callingUid, callingPid, score, notification,
                        user);
                NotificationRecord r = new NotificationRecord(n);
                if (!mSignalExtractors.isEmpty()) {
                    for (NotificationSignalExtractor extractor : mSignalExtractors) {
                        try {
                            RankingFuture future = extractor.process(r);
                            scheduleRankingReconsideration(future);
                        } catch (Throwable t) {
                            Slog.w(TAG, "NotificationSignalExtractor failed.", t);
                        }
                    }
                }

                // 3. Apply local rules

                // blocked apps
                if (ENABLE_BLOCKED_NOTIFICATIONS && !noteNotificationOp(pkg, callingUid)) {
                    if (!isSystemNotification) {
                        score = JUNK_SCORE;
                        Slog.e(TAG, "Suppressing notification from package " + pkg
                                + " by user request.");
                    }
                }

                if (score < SCORE_DISPLAY_THRESHOLD) {
                    // Notification will be blocked because the score is too low.
                    return;
                }

                // Is this notification intercepted by zen mode?
                final boolean intercept = mZenModeHelper.shouldIntercept(pkg, notification);
                notification.extras.putBoolean(EXTRA_INTERCEPT, intercept);

                // Should this notification make noise, vibe, or use the LED?
                final boolean canInterrupt = (score >= SCORE_INTERRUPTION_THRESHOLD) && !intercept;
                if (DBG || intercept) Slog.v(TAG,
                        "pkg=" + pkg + " canInterrupt=" + canInterrupt + " intercept=" + intercept);
                synchronized (mNotificationList) {
                    NotificationRecord old = null;
                    int index = indexOfNotificationLocked(pkg, tag, id, userId);
                    if (index < 0) {
                        mNotificationList.add(r);
                        mUsageStats.registerPostedByApp(r);
                    } else {
                        old = mNotificationList.get(index);
                        mNotificationList.set(index, r);
                        mUsageStats.registerUpdatedByApp(r, old);
                        // Make sure we don't lose the foreground service state.
                        if (old != null) {
                            notification.flags |=
                                old.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE;
                        }
                    }
                    if (old != null) {
                        mNotificationsByKey.remove(old.sbn.getKey());
                    }
                    mNotificationsByKey.put(n.getKey(), r);

                    Collections.sort(mNotificationList, mRankingComparator);

                    // Ensure if this is a foreground service that the proper additional
                    // flags are set.
                    if ((notification.flags&Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                        notification.flags |= Notification.FLAG_ONGOING_EVENT
                                | Notification.FLAG_NO_CLEAR;
                    }

                    final int currentUser;
                    final long token = Binder.clearCallingIdentity();
                    try {
                        currentUser = ActivityManager.getCurrentUser();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }

                    if (notification.icon != 0) {
                        if (old != null && old.statusBarKey != null) {
                            r.statusBarKey = old.statusBarKey;
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                mStatusBar.updateNotification(r.statusBarKey, n);
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }
                        } else {
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                r.statusBarKey = mStatusBar.addNotification(n);
                                if ((n.getNotification().flags & Notification.FLAG_SHOW_LIGHTS) != 0
                                        && canInterrupt) {
                                    mAttentionLight.pulse();
                                }
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }
                        }
                        // Send accessibility events only for the current user.
                        if (currentUser == userId) {
                            sendAccessibilityEvent(notification, pkg);
                        }

                        mListeners.notifyPostedLocked(r.sbn, cloneNotificationListLocked());
                    } else {
                        Slog.e(TAG, "Not posting notification with icon==0: " + notification);
                        if (old != null && old.statusBarKey != null) {
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                mStatusBar.removeNotification(old.statusBarKey);
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }

                            mListeners.notifyRemovedLocked(r.sbn, cloneNotificationListLocked());
                        }
                        // ATTENTION: in a future release we will bail out here
                        // so that we do not play sounds, show lights, etc. for invalid
                        // notifications
                        Slog.e(TAG, "WARNING: In a future release this will crash the app: "
                                + n.getPackageName());
                    }

                    // If we're not supposed to beep, vibrate, etc. then don't.
                    if (!mDisableNotificationAlerts
                            && (!(old != null
                                && (notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0 ))
                            && (r.getUserId() == UserHandle.USER_ALL ||
                                (r.getUserId() == userId && r.getUserId() == currentUser) ||
                                mUserProfiles.isCurrentProfile(r.getUserId()))
                            && canInterrupt
                            && mSystemReady
                            && mAudioManager != null) {
                        if (DBG) Slog.v(TAG, "Interrupting!");
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
                            int audioStreamType;
                            if (notification.audioStreamType >= 0) {
                                audioStreamType = notification.audioStreamType;
                            } else {
                                audioStreamType = DEFAULT_STREAM_TYPE;
                            }
                            mSoundNotification = r;
                            // do not play notifications if stream volume is 0 (typically because
                            // ringer mode is silent) or if there is a user of exclusive audio focus
                            if ((mAudioManager.getStreamVolume(audioStreamType) != 0)
                                    && !mAudioManager.isAudioFocusExclusive()) {
                                final long identity = Binder.clearCallingIdentity();
                                try {
                                    final IRingtonePlayer player =
                                            mAudioManager.getRingtonePlayer();
                                    if (player != null) {
                                        if (DBG) Slog.v(TAG, "Playing sound " + soundUri
                                                + " on stream " + audioStreamType);
                                        player.playAsync(soundUri, user, looping, audioStreamType);
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
                                && (mAudioManager.getRingerMode()
                                           == AudioManager.RINGER_MODE_VIBRATE);

                        // The DEFAULT_VIBRATE flag trumps any custom vibration AND the fallback.
                        final boolean useDefaultVibrate =
                                (notification.defaults & Notification.DEFAULT_VIBRATE) != 0;

                        if ((useDefaultVibrate || convertSoundToVibration || hasCustomVibrate)
                                && !(mAudioManager.getRingerMode()
                                        == AudioManager.RINGER_MODE_SILENT)) {
                            mVibrateNotification = r;

                            if (useDefaultVibrate || convertSoundToVibration) {
                                // Escalate privileges so we can use the vibrator even if the
                                // notifying app does not have the VIBRATE permission.
                                long identity = Binder.clearCallingIdentity();
                                try {
                                    mVibrator.vibrate(r.sbn.getUid(), r.sbn.getOpPkg(),
                                        useDefaultVibrate ? mDefaultVibrationPattern
                                            : mFallbackVibrationPattern,
                                        ((notification.flags & Notification.FLAG_INSISTENT) != 0)
                                                ? 0: -1, notification.audioStreamType);
                                } finally {
                                    Binder.restoreCallingIdentity(identity);
                                }
                            } else if (notification.vibrate.length > 1) {
                                // If you want your own vibration pattern, you need the VIBRATE
                                // permission
                                mVibrator.vibrate(r.sbn.getUid(), r.sbn.getOpPkg(),
                                        notification.vibrate,
                                    ((notification.flags & Notification.FLAG_INSISTENT) != 0)
                                            ? 0: -1, notification.audioStreamType);
                            }
                        }
                    }

                    // light
                    // the most recent thing gets the light
                    mLights.remove(old);
                    if (mLedNotification == old) {
                        mLedNotification = null;
                    }
                    //Slog.i(TAG, "notification.lights="
                    //        + ((old.notification.lights.flags & Notification.FLAG_SHOW_LIGHTS)
                    //                  != 0));
                    if ((notification.flags & Notification.FLAG_SHOW_LIGHTS) != 0
                            && canInterrupt) {
                        mLights.add(r);
                        updateLightsLocked();
                    } else {
                        if (old != null
                                && ((old.getFlags() & Notification.FLAG_SHOW_LIGHTS) != 0)) {
                            updateLightsLocked();
                        }
                    }
                }
            }
        });

        idOut[0] = id;
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

    private void scheduleRankingReconsideration(RankingFuture future) {
        if (future != null) {
            Message m = Message.obtain(mRankingHandler, MESSAGE_RECONSIDER_RANKING, future);
            long delay = future.getDelay(TimeUnit.MILLISECONDS);
            mRankingHandler.sendMessageDelayed(m, delay);
        }
    }

    private void handleRankingReconsideration(Message message) {
        if (!(message.obj instanceof RankingFuture)) return;

        RankingFuture future = (RankingFuture) message.obj;
        future.run();
        try {
            NotificationRecord record = future.get();
            synchronized (mNotificationList) {
                int before = mNotificationList.indexOf(record);
                if (before != -1) {
                    Collections.sort(mNotificationList, mRankingComparator);
                    int after = mNotificationList.indexOf(record);

                    if (before != after) {
                        scheduleSendRankingUpdate();
                    }
                }
            }
        } catch (InterruptedException e) {
            // we're running the future explicitly, so this should never happen
        } catch (ExecutionException e) {
            // we're running the future explicitly, so this should never happen
        }
    }

    private void scheduleSendRankingUpdate() {
        mHandler.removeMessages(MESSAGE_SEND_RANKING_UPDATE);
        Message m = Message.obtain(mHandler, MESSAGE_SEND_RANKING_UPDATE);
        mHandler.sendMessage(m);
    }

    private void handleSendRankingUpdate() {
        synchronized (mNotificationList) {
            mListeners.notifyRankingUpdateLocked(cloneNotificationListLocked());
        }
    }

    private ArrayList<StatusBarNotification> cloneNotificationListLocked() {
        final int N = mNotificationList.size();
        ArrayList<StatusBarNotification> sbns = new ArrayList<StatusBarNotification>(N);
        for (int i = 0; i < N; i++) {
            sbns.add(mNotificationList.get(i).sbn);
        }
        return sbns;
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
        if (r.getNotification().icon != 0) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mStatusBar.removeNotification(r.statusBarKey);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            r.statusBarKey = null;
            mListeners.notifyRemovedLocked(r.sbn, cloneNotificationListLocked());
        }

        // sound
        if (mSoundNotification == r) {
            mSoundNotification = null;
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
        if (mVibrateNotification == r) {
            mVibrateNotification = null;
            long identity = Binder.clearCallingIdentity();
            try {
                mVibrator.cancel();
            }
            finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        // light
        mLights.remove(r);
        if (mLedNotification == r) {
            mLedNotification = null;
        }

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
            case REASON_DELEGATE_CLICK:
                mUsageStats.registerCancelDueToClick(r);
                break;
            default:
                mUsageStats.registerCancelUnknown(r);
                break;
        }

        // Save it for users of getHistoricalNotifications()
        mArchive.record(r.sbn);
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
                EventLogTags.writeNotificationCancel(callingUid, callingPid, pkg, id, tag, userId,
                        mustHaveFlags, mustNotHaveFlags, reason,
                        listener == null ? null : listener.component.toShortString());

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
                        mNotificationsByKey.remove(r.sbn.getKey());

                        cancelNotificationLocked(r, sendDelete, reason);
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
        EventLogTags.writeNotificationCancelAll(callingUid, callingPid,
                pkg, userId, mustHaveFlags, mustNotHaveFlags, reason,
                listener == null ? null : listener.component.toShortString());

        synchronized (mNotificationList) {
            final int N = mNotificationList.size();
            boolean canceledSomething = false;
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
                canceledSomething = true;
                if (!doit) {
                    return true;
                }
                mNotificationList.remove(i);
                mNotificationsByKey.remove(r.sbn.getKey());
                cancelNotificationLocked(r, false, reason);
            }
            if (canceledSomething) {
                updateLightsLocked();
            }
            return canceledSomething;
        }
    }

    void cancelAllLocked(int callingUid, int callingPid, int userId, int reason,
            ManagedServiceInfo listener, boolean includeCurrentProfiles) {
        EventLogTags.writeNotificationCancelAll(callingUid, callingPid,
                null, userId, 0, 0, reason,
                listener == null ? null : listener.component.toShortString());

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
                mNotificationsByKey.remove(r.sbn.getKey());
                cancelNotificationLocked(r, true, reason);
            }
        }
        updateLightsLocked();
    }

    // lock on mNotificationList
    void updateLightsLocked()
    {
        // handle notification lights
        if (mLedNotification == null) {
            // get next notification, if any
            int n = mLights.size();
            if (n > 0) {
                mLedNotification = mLights.get(n-1);
            }
        }

        // Don't flash while we are in a call or screen is on
        if (mLedNotification == null || mInCall || mScreenOn) {
            mNotificationLight.turnOff();
        } else {
            final Notification ledno = mLedNotification.sbn.getNotification();
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
        }
    }

    // lock on mNotificationList
    int indexOfNotificationLocked(String pkg, String tag, int id, int userId)
    {
        ArrayList<NotificationRecord> list = mNotificationList;
        final int len = list.size();
        for (int i=0; i<len; i++) {
            NotificationRecord r = list.get(i);
            if (!notificationMatchesUserId(r, userId) || r.sbn.getId() != id) {
                continue;
            }
            if (tag == null) {
                if (r.sbn.getTag() != null) {
                    continue;
                }
            } else {
                if (!tag.equals(r.sbn.getTag())) {
                    continue;
                }
            }
            if (r.sbn.getPackageName().equals(pkg)) {
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
        final int uid = Binder.getCallingUid();
        try {
            ApplicationInfo ai = AppGlobals.getPackageManager().getApplicationInfo(
                    pkg, 0, UserHandle.getCallingUserId());
            if (!UserHandle.isSameApp(ai.uid, uid)) {
                throw new SecurityException("Calling uid " + uid + " gave package"
                        + pkg + " which is owned by uid " + ai.uid);
            }
        } catch (RemoteException re) {
            throw new SecurityException("Unknown package " + pkg + "\n" + re);
        }
    }

    /**
     * Generates a NotificationRankingUpdate from 'sbns', considering only
     * notifications visible to the given listener.
     */
    private static NotificationRankingUpdate makeRankingUpdateForListener(ManagedServiceInfo info,
            ArrayList<StatusBarNotification> sbns) {
        int speedBumpIndex = -1;
        ArrayList<String> keys = new ArrayList<String>(sbns.size());
        ArrayList<String> dndKeys = new ArrayList<String>(sbns.size());
        for (StatusBarNotification sbn: sbns) {
            if (!info.enabledAndUserMatches(sbn.getUserId())) {
                continue;
            }
            keys.add(sbn.getKey());
            if (sbn.getNotification().extras.getBoolean(EXTRA_INTERCEPT)) {
                dndKeys.add(sbn.getKey());
            }
            if (speedBumpIndex == -1 &&
                    sbn.getNotification().priority == Notification.PRIORITY_MIN) {
                speedBumpIndex = keys.size() - 1;
            }
        }
        String[] keysAr = keys.toArray(new String[keys.size()]);
        String[] dndKeysAr = dndKeys.toArray(new String[dndKeys.size()]);
        return new NotificationRankingUpdate(keysAr, dndKeysAr, speedBumpIndex);
    }

    public class NotificationListeners extends ManagedServices {

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
            final ArrayList<StatusBarNotification> sbns;
            synchronized (mNotificationList) {
                sbns = cloneNotificationListLocked();
            }
            try {
                listener.onListenerConnected(makeRankingUpdateForListener(info, sbns));
            } catch (RemoteException e) {
                // we tried
            }
        }

        /**
         * asynchronously notify all listeners about a new notification
         */
        public void notifyPostedLocked(StatusBarNotification sbn,
                final ArrayList<StatusBarNotification> sbns) {
            // make a copy in case changes are made to the underlying Notification object
            final StatusBarNotification sbnClone = sbn.clone();
            for (final ManagedServiceInfo info : mServices) {
                if (!info.isEnabledForCurrentProfiles()) {
                    continue;
                }
                final NotificationRankingUpdate update = makeRankingUpdateForListener(info, sbns);
                if (update.getOrderedKeys().length == 0) {
                    continue;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyPostedIfUserMatch(info, sbnClone, update);
                    }
                });
            }
        }

        /**
         * asynchronously notify all listeners about a removed notification
         */
        public void notifyRemovedLocked(StatusBarNotification sbn,
                final ArrayList<StatusBarNotification> sbns) {
            // make a copy in case changes are made to the underlying Notification object
            // NOTE: this copy is lightweight: it doesn't include heavyweight parts of the
            // notification
            final StatusBarNotification sbnLight = sbn.cloneLight();
            for (final ManagedServiceInfo info : mServices) {
                if (!info.isEnabledForCurrentProfiles()) {
                    continue;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyRemovedIfUserMatch(info, sbnLight,
                                makeRankingUpdateForListener(info, sbns));
                    }
                });
            }
        }

        /**
         * asynchronously notify all listeners about a reordering of notifications
         * @param sbns an array of {@link StatusBarNotification}s to consider.  This code
         *             must not rely on mutable members of these objects, such as the
         *             {@link Notification}.
         */
        public void notifyRankingUpdateLocked(final ArrayList<StatusBarNotification> sbns) {
            for (final ManagedServiceInfo serviceInfo : mServices) {
                if (!serviceInfo.isEnabledForCurrentProfiles()) {
                    continue;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyRankingUpdate(serviceInfo,
                                makeRankingUpdateForListener(serviceInfo, sbns));
                    }
                });
            }
        }

        private void notifyPostedIfUserMatch(final ManagedServiceInfo info,
                final StatusBarNotification sbn, NotificationRankingUpdate rankingUpdate) {
            if (!info.enabledAndUserMatches(sbn.getUserId())) {
                return;
            }
            final INotificationListener listener = (INotificationListener)info.service;
            try {
                listener.onNotificationPosted(sbn, rankingUpdate);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (posted): " + listener, ex);
            }
        }

        private void notifyRemovedIfUserMatch(ManagedServiceInfo info, StatusBarNotification sbn,
                NotificationRankingUpdate rankingUpdate) {
            if (!info.enabledAndUserMatches(sbn.getUserId())) {
                return;
            }
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onNotificationRemoved(sbn, rankingUpdate);
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
    }
}

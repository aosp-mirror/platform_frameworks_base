/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.alarm;


import android.annotation.Nullable;
import android.os.Environment;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseLongArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * User wakeup store keeps the list of user ids with the times that user needs to be started in
 * sorted list in order for alarms to execute even if user gets stopped.
 * The list of user ids with at least one alarms scheduled is also persisted to the XML file to
 * start them after the device reboot.
 */
public class UserWakeupStore {
    private static final boolean DEBUG = false;

    static final String USER_WAKEUP_TAG = UserWakeupStore.class.getSimpleName();
    private static final String TAG_USERS = "users";
    private static final String TAG_USER = "user";
    private static final String ATTR_USER_ID = "user_id";
    private static final String ATTR_VERSION = "version";

    public static final int XML_VERSION_CURRENT = 1;
    @VisibleForTesting
    static final String ROOT_DIR_NAME = "alarms";
    @VisibleForTesting
    static final String USERS_FILE_NAME = "usersWithAlarmClocks.xml";

    /**
     * Time offset of user start before the original alarm time in milliseconds.
     * Also used to schedule user start after reboot to avoid starting them simultaneously.
     */
    @VisibleForTesting
    static final long BUFFER_TIME_MS = TimeUnit.SECONDS.toMillis(30);
    /**
     * Maximum time deviation limit to introduce a 5-second time window for user starts.
     */
    @VisibleForTesting
    static final long USER_START_TIME_DEVIATION_LIMIT_MS = TimeUnit.SECONDS.toMillis(5);
    /**
     * Delay between two consecutive user starts scheduled during user wakeup store initialization.
     */
    @VisibleForTesting
    static final long INITIAL_USER_START_SCHEDULING_DELAY_MS = TimeUnit.SECONDS.toMillis(5);

    private final Object mUserWakeupLock = new Object();

    /**
     * A list of wakeups for users with scheduled alarms.
     */
    @GuardedBy("mUserWakeupLock")
    private final SparseLongArray mUserStarts = new SparseLongArray();

    private Executor mBackgroundExecutor;
    private static final File USER_WAKEUP_DIR = new File(Environment.getDataSystemDirectory(),
            ROOT_DIR_NAME);
    private static final Random sRandom = new Random(500);

    /**
     * Initialize mUserWakeups with persisted values.
     */
    public void init() {
        mBackgroundExecutor = BackgroundThread.getExecutor();
        mBackgroundExecutor.execute(this::readUserIdList);
    }

    /**
     * Add user wakeup for the alarm if needed.
     * @param userId Id of the user that scheduled alarm.
     * @param alarmTime time when alarm is expected to trigger.
     */
    public void addUserWakeup(int userId, long alarmTime) {
        synchronized (mUserWakeupLock) {
            mUserStarts.put(userId, alarmTime - BUFFER_TIME_MS + getUserWakeupOffset());
        }
        updateUserListFile();
    }

    /**
     * Remove wakeup scheduled for the user with given userId if present.
     */
    public void removeUserWakeup(int userId) {
        if (deleteWakeupFromUserStarts(userId)) {
            updateUserListFile();
        }
    }

    /**
     * Get ids of users that need to be started now.
     * @param nowElapsed current time.
     * @return user ids to be started, or empty if no user needs to be started.
     */
    public int[] getUserIdsToWakeup(long nowElapsed) {
        synchronized (mUserWakeupLock) {
            final int[] userIds = new int[mUserStarts.size()];
            int index = 0;
            for (int i = mUserStarts.size() - 1; i >= 0; i--) {
                if (mUserStarts.valueAt(i) <= nowElapsed) {
                    userIds[index++] = mUserStarts.keyAt(i);
                }
            }
            return Arrays.copyOfRange(userIds, 0, index);
        }
    }

    /**
     * Persist user ids that have alarms scheduled so that they can be started after device reboot.
     */
    private void updateUserListFile() {
        mBackgroundExecutor.execute(() -> {
            try {
                writeUserIdList();
                if (DEBUG) {
                    synchronized (mUserWakeupLock) {
                        Slog.i(USER_WAKEUP_TAG, "Printing out user wakeups " + mUserStarts.size());
                        for (int i = 0; i < mUserStarts.size(); i++) {
                            Slog.i(USER_WAKEUP_TAG, "User id: " + mUserStarts.keyAt(i) + "  time: "
                                    + mUserStarts.valueAt(i));
                        }
                    }
                }
            } catch (Exception e) {
                Slog.e(USER_WAKEUP_TAG, "Failed to write " + e.getLocalizedMessage());
            }
        });
    }

    /**
     * Return scheduled start time for user or -1 if user does not have alarm set.
     */
    @VisibleForTesting
    long getWakeupTimeForUser(int userId) {
        synchronized (mUserWakeupLock) {
            return mUserStarts.get(userId, -1);
        }
    }

    /**
     * Remove scheduled user wakeup from the list when it is started.
     */
    public void onUserStarting(int userId) {
        if (deleteWakeupFromUserStarts(userId)) {
            updateUserListFile();
        }
    }

    /**
     * Remove userId from the store when the user is removed.
     */
    public void onUserRemoved(int userId) {
        if (deleteWakeupFromUserStarts(userId)) {
            updateUserListFile();
        }
    }

    /**
     * Remove wakeup for a given userId from mUserStarts.
     * @return true if an entry is found and the list of wakeups changes.
     */
    private boolean deleteWakeupFromUserStarts(int userId) {
        synchronized (mUserWakeupLock) {
            final int index = mUserStarts.indexOfKey(userId);
            if (index >= 0) {
                mUserStarts.removeAt(index);
                return true;
            }
            return false;
        }
    }

    /**
     * Get the soonest wakeup time in the store.
     */
    public long getNextWakeupTime() {
        long nextWakeupTime = -1;
        synchronized (mUserWakeupLock) {
            for (int i = 0; i < mUserStarts.size(); i++) {
                if (mUserStarts.valueAt(i) < nextWakeupTime || nextWakeupTime == -1) {
                    nextWakeupTime = mUserStarts.valueAt(i);
                }
            }
        }
        return nextWakeupTime;
    }

    private static long getUserWakeupOffset() {
        return sRandom.nextLong(USER_START_TIME_DEVIATION_LIMIT_MS * 2)
                - USER_START_TIME_DEVIATION_LIMIT_MS;
    }

    /**
     * Write a list of ids for users who have alarm scheduled.
     * Sample XML file:
     *
     * <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
     * <users version="1">
     * <user user_id="12" />
     * <user user_id="10" />
     * </users>
     * ~
     */
    private void writeUserIdList() {
        final AtomicFile file = getUserWakeupFile();
        if (file == null) {
            return;
        }
        try (FileOutputStream fos = file.startWrite(SystemClock.uptimeMillis())) {
            final XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, TAG_USERS);
            XmlUtils.writeIntAttribute(out, ATTR_VERSION, XML_VERSION_CURRENT);
            final List<Pair<Integer, Long>> listOfUsers = new ArrayList<>();
            synchronized (mUserWakeupLock) {
                for (int i = 0; i < mUserStarts.size(); i++) {
                    listOfUsers.add(new Pair<>(mUserStarts.keyAt(i), mUserStarts.valueAt(i)));
                }
            }
            Collections.sort(listOfUsers, Comparator.comparingLong(pair -> pair.second));
            for (int i = 0; i < listOfUsers.size(); i++) {
                out.startTag(null, TAG_USER);
                XmlUtils.writeIntAttribute(out, ATTR_USER_ID, listOfUsers.get(i).first);
                out.endTag(null, TAG_USER);
            }
            out.endTag(null, TAG_USERS);
            out.endDocument();
            file.finishWrite(fos);
        } catch (IOException e) {
            Slog.wtf(USER_WAKEUP_TAG, "Error writing user wakeup data", e);
            file.delete();
        }
    }

    private void readUserIdList() {
        final AtomicFile userWakeupFile = getUserWakeupFile();
        if (userWakeupFile == null) {
            return;
        } else if (!userWakeupFile.exists()) {
            Slog.w(USER_WAKEUP_TAG, "User wakeup file not available: "
                    + userWakeupFile.getBaseFile());
            return;
        }
        synchronized (mUserWakeupLock) {
            mUserStarts.clear();
        }
        try (FileInputStream fis = userWakeupFile.openRead()) {
            final TypedXmlPullParser parser = Xml.resolvePullParser(fis);
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                // Skip
            }
            if (type != XmlPullParser.START_TAG) {
                Slog.e(USER_WAKEUP_TAG, "Unable to read user list. No start tag found in "
                        + userWakeupFile.getBaseFile());
                return;
            }
            int version = -1;
            if (parser.getName().equals(TAG_USERS)) {
                version = parser.getAttributeInt(null, ATTR_VERSION, version);
            }

            long counter = 0;
            final long currentTime = SystemClock.elapsedRealtime();
            // Time delay between now and first user wakeup is scheduled.
            final long scheduleOffset = currentTime + BUFFER_TIME_MS + getUserWakeupOffset();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    if (parser.getName().equals(TAG_USER)) {
                        final int id = parser.getAttributeInt(null, ATTR_USER_ID);
                        synchronized (mUserWakeupLock) {
                            mUserStarts.put(id, scheduleOffset + (counter++
                                    * INITIAL_USER_START_SCHEDULING_DELAY_MS));
                        }
                    }
                }
            }
        } catch (IOException | XmlPullParserException e) {
            Slog.wtf(USER_WAKEUP_TAG, "Error reading user wakeup data", e);
        }
    }

    @Nullable
    private AtomicFile getUserWakeupFile() {
        if (!USER_WAKEUP_DIR.exists() && !USER_WAKEUP_DIR.mkdir()) {
            Slog.wtf(USER_WAKEUP_TAG, "Failed to mkdir() user list file: " + USER_WAKEUP_DIR);
            return null;
        }
        final File userFile = new File(USER_WAKEUP_DIR, USERS_FILE_NAME);
        return new AtomicFile(userFile);
    }

    void dump(IndentingPrintWriter pw, long nowELAPSED) {
        synchronized (mUserWakeupLock) {
            pw.increaseIndent();
            pw.print("User wakeup store file path: ");
            final AtomicFile file = getUserWakeupFile();
            if (file == null) {
                pw.println("null");
            } else {
                pw.println(file.getBaseFile().getAbsolutePath());
            }
            pw.println(mUserStarts.size() + " user wakeups scheduled: ");
            for (int i = 0; i < mUserStarts.size(); i++) {
                pw.print("UserId: ");
                pw.print(mUserStarts.keyAt(i));
                pw.print(", userStartTime: ");
                TimeUtils.formatDuration(mUserStarts.valueAt(i), nowELAPSED, pw);
                pw.println();
            }
            pw.decreaseIndent();
        }
    }
}

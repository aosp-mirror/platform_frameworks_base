/*
* Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.notification;

import android.app.Notification;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.SpannableString;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * This NotificationScorer bumps up the priority of notifications that contain references to the
 * display names of starred contacts. The references it picks up are spannable strings which, in
 * their entirety, match the display name of some starred contact. The magnitude of the bump ranges
 * from 0 to 15 (assuming NOTIFICATION_PRIORITY_MULTIPLIER = 10) depending on the initial score, and
 * the mapping is defined by priorityBumpMap. In a production version of this scorer, a notification
 * extra will be used to specify contact identifiers.
 */

public class DemoContactNotificationScorer implements NotificationScorer {
    private static final String TAG = "DemoContactNotificationScorer";
    private static final boolean DBG = false;

    protected static final boolean ENABLE_CONTACT_SCORER = true;
    private static final String SETTING_ENABLE_SCORER = "contact_scorer_enabled";
    protected boolean mEnabled;

    // see NotificationManagerService
    private static final int NOTIFICATION_PRIORITY_MULTIPLIER = 10;

    private Context mContext;

    private static final List<String> RELEVANT_KEYS_LIST = Arrays.asList(
            Notification.EXTRA_INFO_TEXT, Notification.EXTRA_TEXT, Notification.EXTRA_TEXT_LINES,
            Notification.EXTRA_SUB_TEXT, Notification.EXTRA_TITLE
    );

    private static final String[] PROJECTION = new String[] {
            ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME
    };

    private static final Uri CONTACTS_URI = ContactsContract.Contacts.CONTENT_URI;

    private static List<String> extractSpannedStrings(CharSequence charSequence) {
        if (charSequence == null) return Collections.emptyList();
        if (!(charSequence instanceof SpannableString)) {
            return Arrays.asList(charSequence.toString());
        }
        SpannableString spannableString = (SpannableString)charSequence;
        // get all spans
        Object[] ssArr = spannableString.getSpans(0, spannableString.length(), Object.class);
        // spanned string sequences
        ArrayList<String> sss = new ArrayList<String>();
        for (Object spanObj : ssArr) {
            try {
                sss.add(spannableString.subSequence(spannableString.getSpanStart(spanObj),
                        spannableString.getSpanEnd(spanObj)).toString());
            } catch(StringIndexOutOfBoundsException e) {
                Slog.e(TAG, "Bad indices when extracting spanned subsequence", e);
            }
        }
        return sss;
    };

    private static String getQuestionMarksInParens(int n) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < n; i++) {
            if (sb.length() > 1) sb.append(',');
            sb.append('?');
        }
        sb.append(")");
        return sb.toString();
    }

    private boolean hasStarredContact(Bundle extras) {
        if (extras == null) return false;
        ArrayList<String> qStrings = new ArrayList<String>();
        // build list to query against the database for display names.
        for (String rk: RELEVANT_KEYS_LIST) {
            if (extras.get(rk) == null) {
                continue;
            } else if (extras.get(rk) instanceof CharSequence) {
                qStrings.addAll(extractSpannedStrings((CharSequence) extras.get(rk)));
            } else if (extras.get(rk) instanceof CharSequence[]) {
                // this is intended for Notification.EXTRA_TEXT_LINES
                for (CharSequence line: (CharSequence[]) extras.get(rk)){
                    qStrings.addAll(extractSpannedStrings(line));
                }
            } else {
                Slog.w(TAG, "Strange, the extra " + rk + " is of unexpected type.");
            }
        }
        if (qStrings.isEmpty()) return false;
        String[] qStringsArr = qStrings.toArray(new String[qStrings.size()]);

        String selection = ContactsContract.Contacts.DISPLAY_NAME + " IN "
                + getQuestionMarksInParens(qStringsArr.length) + " AND "
                + ContactsContract.Contacts.STARRED+" ='1'";

        Cursor c = null;
        try {
            c = mContext.getContentResolver().query(
                    CONTACTS_URI, PROJECTION, selection, qStringsArr, null);
            if (c != null) return c.getCount() > 0;
        } catch(Throwable t) {
            Slog.w(TAG, "Problem getting content resolver or performing contacts query.", t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return false;
    }

    private final static int clamp(int x, int low, int high) {
        return (x < low) ? low : ((x > high) ? high : x);
    }

    private static int priorityBumpMap(int incomingScore) {
        //assumption is that scale runs from [-2*pm, 2*pm]
        int pm = NOTIFICATION_PRIORITY_MULTIPLIER;
        int theScore = incomingScore;
        // enforce input in range
        theScore = clamp(theScore, -2 * pm, 2 * pm);
        if (theScore != incomingScore) return incomingScore;
        // map -20 -> -20 and -10 -> 5 (when pm = 10)
        if (theScore <= -pm) {
            theScore += 1.5 * (theScore + 2 * pm);
        } else {
            // map 0 -> 10, 10 -> 15, 20 -> 20;
            theScore += 0.5 * (2 * pm - theScore);
        }
        if (DBG) Slog.v(TAG, "priorityBumpMap: score before: " + incomingScore
                + ", score after " + theScore + ".");
        return theScore;
    }

    @Override
    public void initialize(Context context) {
        if (DBG) Slog.v(TAG, "Initializing  " + getClass().getSimpleName() + ".");
        mContext = context;
        mEnabled = ENABLE_CONTACT_SCORER && 1 == Settings.Global.getInt(
                mContext.getContentResolver(), SETTING_ENABLE_SCORER, 0);
    }

    @Override
    public int getScore(Notification notification, int score) {
        if (notification == null || !mEnabled) {
            if (DBG) Slog.w(TAG, "empty notification? scorer disabled?");
            return score;
        }
        boolean hasStarredPriority = hasStarredContact(notification.extras);

        if (DBG) {
            if (hasStarredPriority) {
                Slog.v(TAG, "Notification references starred contact. Promoted!");
            } else {
                Slog.v(TAG, "Notification lacks any starred contact reference. Not promoted!");
            }
        }
        if (hasStarredPriority) score = priorityBumpMap(score);
        return score;
    }
}


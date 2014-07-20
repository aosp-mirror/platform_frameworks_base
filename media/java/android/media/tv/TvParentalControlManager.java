/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.tv;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Contains methods for accessing and monitoring the user's parental control settings.
 * <p>
 * To obtain a handle to the TV parental control manager, do the following:
 * <p>
 * <code>
 * <pre>TvParentalControlManager tvParentalControlManager =
 *        (TvParentalControlManager) context.getSystemService(Context.TV_PARENTAL_CONTROL_SERVICE);
 * </pre>
 * </code>
 */
public final class TvParentalControlManager {
    /** Default parental control enabled value. */
    private static final int DEFAULT_ENABLED = 0;

    private final Handler mHandler = new Handler();

    private final ContentResolver mContentResolver;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final List<ParentalControlListenerRecord> mParentalControlListenerRecordList =
            new LinkedList<ParentalControlListenerRecord>();

    @GuardedBy("mLock")
    private final List<TvContentRating> mBlockedRatings = new ArrayList<TvContentRating>();

    @GuardedBy("mLock")
    private String mBlockedRatingsString;

    /**
     * Creates a new parental control manager for the specified context.
     *
     * @hide
     */
    public TvParentalControlManager(Context context) {
        mContentResolver = context.getContentResolver();
    }

    /**
     * Returns the user's parental control enabled state.
     *
     * @return {@code true} if the user enabled the parental control, {@code false} otherwise.
     */
    public final boolean isEnabled() {
        return Settings.Secure.getInt(mContentResolver, Settings.Secure.TV_PARENTAL_CONTROL_ENABLED,
                DEFAULT_ENABLED) == 1;
    }

    /**
     * Checks whether a given TV content rating is blocked by the user.
     *
     * @param rating The TV content rating to check.
     * @return {@code true} if blocked, {@code false} if not blocked or parental control is
     *         disabled.
     */
    public final boolean isRatingBlocked(TvContentRating rating) {
        if (!isEnabled()) {
            // Parental control is disabled. Enjoy watching good stuff.
            return false;
        }

        // Update the blocked ratings only when they change.
        final String blockedRatingsString = Settings.Secure.getString(mContentResolver,
                Settings.Secure.TV_PARENTAL_CONTROL_BLOCKED_RATINGS);
        synchronized (mLock) {
            if (!TextUtils.equals(blockedRatingsString, mBlockedRatingsString)) {
                mBlockedRatingsString = blockedRatingsString;
                updateBlockedRatingsLocked();
            }
            for (TvContentRating blockedRating : mBlockedRatings) {
                if (rating.contains(blockedRating)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateBlockedRatingsLocked() {
        mBlockedRatings.clear();
        if (TextUtils.isEmpty(mBlockedRatingsString)) {
            return;
        }
        for (String blockedRatingString : mBlockedRatingsString.split("\\s*,\\s*")) {
            mBlockedRatings.add(TvContentRating.unflattenFromString(blockedRatingString));
        }
    }

    /**
     * Adds a listener for monitoring the changes in the user's parental control settings.
     *
     * @param listener The listener to add.
     * @param handler a {@link Handler} that the settings change will be delivered to.
     */
    public void addParentalControlListener(ParentalControlListener listener,
            Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler cannot be null");
        }
        synchronized (mLock) {
            if (mParentalControlListenerRecordList.isEmpty()) {
                registerObserver(Settings.Secure.TV_PARENTAL_CONTROL_ENABLED);
                registerObserver(Settings.Secure.TV_PARENTAL_CONTROL_BLOCKED_RATINGS);
            }
            mParentalControlListenerRecordList.add(
                    new ParentalControlListenerRecord(listener, handler));
        }
    }

    private void registerObserver(String key) {
        mContentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false,
                mContentObserver);
    }

    /**
     * Removes a listener previously added using {@link #addParentalControlListener}.
     *
     * @param listener The listener to remove.
     */
    public void removeParentalControlListener(ParentalControlListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        synchronized (mLock) {
            for (Iterator<ParentalControlListenerRecord> it =
                    mParentalControlListenerRecordList.iterator(); it.hasNext();) {
                ParentalControlListenerRecord record = it.next();
                if (record.getListener() == listener) {
                    it.remove();
                    break;
                }
            }
        }
    }

    private void notifyEnabledChanged() {
        final boolean enabled = isEnabled();
        synchronized (mLock) {
            for (ParentalControlListenerRecord record : mParentalControlListenerRecordList) {
                record.postEnabledChanged(enabled);
            }
        }
    }

    private final ContentObserver mContentObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            final String uriPath = uri.getPath();
            final String name = uriPath.substring(uriPath.lastIndexOf('/') + 1);
            if (Settings.Secure.TV_PARENTAL_CONTROL_ENABLED.equals(name)) {
                notifyEnabledChanged();
            } else if (Settings.Secure.TV_PARENTAL_CONTROL_BLOCKED_RATINGS.equals(name)) {
                // We only need a single listener when multiple ratings change in rapid
                // succession.
                mHandler.removeCallbacks(mBlockedRatingsChangedRunnable);
                mHandler.post(mBlockedRatingsChangedRunnable);
            }
        }
    };

    /**
     * Runnable posted when user blocked ratings change. This is used to prevent unnecessary change
     * notifications when multiple ratings change in rapid succession.
     */
    private final Runnable mBlockedRatingsChangedRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                for (ParentalControlListenerRecord record : mParentalControlListenerRecordList) {
                    record.postBlockedRatingsChanged();
                }
            }
        }
    };

    /**
     * Listener for changes in parental control settings, including enabled state.
     */
    public static abstract class ParentalControlListener {
        /**
         * Called when the parental control enabled state changes.
         *
         * @param enabled the user's parental control enabled state
         */
        public void onEnabledChanged(boolean enabled) {}

        /**
         * Called when the user blocked ratings change.
         * <p>
         * When this is invoked, one should immediately call
         * {@link TvParentalControlManager#isRatingBlocked} to reevaluate the current content since
         * the user might have changed her mind and blocked the rating for the content.
         *
         * @see TvParentalControlManager#isRatingBlocked
         */
        public void onBlockedRatingsChanged() {}
    }

    private static final class ParentalControlListenerRecord {
        private final ParentalControlListener mListener;
        private final Handler mHandler;

        public ParentalControlListenerRecord(ParentalControlListener listener, Handler handler) {
            mListener = listener;
            mHandler = handler;
        }

        public ParentalControlListener getListener() {
            return mListener;
        }

        public void postEnabledChanged(final boolean enabled) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onEnabledChanged(enabled);
                }
            });
        }

        public void postBlockedRatingsChanged() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onBlockedRatingsChanged();
                }
            });
        }
    }
}

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

package android.content;

import android.accounts.Account;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Convenience class to construct sync requests. See {@link android.content.SyncRequest.Builder}
 * for an explanation of the various functions. The resulting object is passed through to the
 * framework via {@link android.content.ContentResolver#requestSync(SyncRequest)}.
 */
public class SyncRequest implements Parcelable {
    private static final String TAG = "SyncRequest";
    /** Account to pass to the sync adapter. Can be null. */
    private final Account mAccountToSync;
    /** Authority string that corresponds to a ContentProvider. */
    private final String mAuthority;
    /** Bundle containing user info as well as sync settings. */
    private final Bundle mExtras;
    /** Don't allow this sync request on metered networks. */
    private final boolean mDisallowMetered;
    /**
     * Amount of time before {@link #mSyncRunTimeSecs} from which the sync may optionally be
     * started.
     */
    private final long mSyncFlexTimeSecs;
    /**
     * Specifies a point in the future at which the sync must have been scheduled to run.
     */
    private final long mSyncRunTimeSecs;
    /** Periodic versus one-off. */
    private final boolean mIsPeriodic;
    /** Service versus provider. */
    private final boolean mIsAuthority;
    /** Sync should be run in lieu of other syncs. */
    private final boolean mIsExpedited;

    /**
     * {@hide}
     * @return whether this sync is periodic or one-time. A Sync Request must be
     *         either one of these or an InvalidStateException will be thrown in
     *         Builder.build().
     */
    public boolean isPeriodic() {
        return mIsPeriodic;
    }

    /**
     * {@hide}
     * @return whether this sync is expedited.
     */
    public boolean isExpedited() {
        return mIsExpedited;
    }

    /**
     * {@hide}
     *
     * @return account object for this sync.
     * @throws IllegalArgumentException if this function is called for a request that targets a
     * sync service.
     */
    public Account getAccount() {
        return mAccountToSync;
    }

    /**
     * {@hide}
     *
     * @return provider for this sync.
     * @throws IllegalArgumentException if this function is called for a request that targets a
     * sync service.
     */
    public String getProvider() {
        return mAuthority;
    }

    /**
     * {@hide}
     * Retrieve bundle for this SyncRequest. Will not be null.
     */
    public Bundle getBundle() {
        return mExtras;
    }

    /**
     * {@hide}
     * @return the earliest point in time that this sync can be scheduled.
     */
    public long getSyncFlexTime() {
        return mSyncFlexTimeSecs;
    }
    /**
     * {@hide}
     * @return the last point in time at which this sync must scheduled.
     */
    public long getSyncRunTime() {
        return mSyncRunTimeSecs;
    }

    public static final Creator<SyncRequest> CREATOR = new Creator<SyncRequest>() {

        @Override
        public SyncRequest createFromParcel(Parcel in) {
            return new SyncRequest(in);
        }

        @Override
        public SyncRequest[] newArray(int size) {
            return new SyncRequest[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeBundle(mExtras);
        parcel.writeLong(mSyncFlexTimeSecs);
        parcel.writeLong(mSyncRunTimeSecs);
        parcel.writeInt((mIsPeriodic ? 1 : 0));
        parcel.writeInt((mDisallowMetered ? 1 : 0));
        parcel.writeInt((mIsAuthority ? 1 : 0));
        parcel.writeInt((mIsExpedited? 1 : 0));
        parcel.writeParcelable(mAccountToSync, flags);
        parcel.writeString(mAuthority);
    }

    private SyncRequest(Parcel in) {
        mExtras = Bundle.setDefusable(in.readBundle(), true);
        mSyncFlexTimeSecs = in.readLong();
        mSyncRunTimeSecs = in.readLong();
        mIsPeriodic = (in.readInt() != 0);
        mDisallowMetered = (in.readInt() != 0);
        mIsAuthority = (in.readInt() != 0);
        mIsExpedited = (in.readInt() != 0);
        mAccountToSync = in.readParcelable(null);
        mAuthority = in.readString();
    }

    /** {@hide} Protected ctor to instantiate anonymous SyncRequest. */
    protected SyncRequest(SyncRequest.Builder b) {
        mSyncFlexTimeSecs = b.mSyncFlexTimeSecs;
        mSyncRunTimeSecs = b.mSyncRunTimeSecs;
        mAccountToSync = b.mAccount;
        mAuthority = b.mAuthority;
        mIsPeriodic = (b.mSyncType == Builder.SYNC_TYPE_PERIODIC);
        mIsAuthority = (b.mSyncTarget == Builder.SYNC_TARGET_ADAPTER);
        mIsExpedited = b.mExpedited;
        mExtras = new Bundle(b.mCustomExtras);
        // For now we merge the sync config extras & the custom extras into one bundle.
        // TODO: pass the configuration extras through separately.
        mExtras.putAll(b.mSyncConfigExtras);
        mDisallowMetered = b.mDisallowMetered;
    }

    /**
     * Builder class for a {@link SyncRequest}. As you build your SyncRequest this class will also
     * perform validation.
     */
    public static class Builder {
        /** Unknown sync type. */
        private static final int SYNC_TYPE_UNKNOWN = 0;
        /** Specify that this is a periodic sync. */
        private static final int SYNC_TYPE_PERIODIC = 1;
        /** Specify that this is a one-time sync. */
        private static final int SYNC_TYPE_ONCE = 2;
        /** Unknown sync target. */
        private static final int SYNC_TARGET_UNKNOWN = 0;
        /** Specify that this is a sync with a provider. */
        private static final int SYNC_TARGET_ADAPTER = 2;
        /**
         * Earliest point of displacement into the future at which this sync can
         * occur.
         */
        private long mSyncFlexTimeSecs;
        /** Displacement into the future at which this sync must occur. */
        private long mSyncRunTimeSecs;
        /**
         * Sync configuration information - custom user data explicitly provided by the developer.
         * This data is handed over to the sync operation.
         */
        private Bundle mCustomExtras;
        /**
         * Sync system configuration -  used to store system sync configuration. Corresponds to
         * ContentResolver.SYNC_EXTRAS_* flags.
         * TODO: Use this instead of dumping into one bundle. Need to decide if these flags should
         * discriminate between equivalent syncs.
         */
        private Bundle mSyncConfigExtras;
        /** Whether or not this sync can occur on metered networks. Default false. */
        private boolean mDisallowMetered;
        /**
         * Whether this builder is building a periodic sync, or a one-time sync.
         */
        private int mSyncType = SYNC_TYPE_UNKNOWN;
        /** Whether this will go to a sync adapter. */
        private int mSyncTarget = SYNC_TARGET_UNKNOWN;
        /** Whether this is a user-activated sync. */
        private boolean mIsManual;
        /**
         * Whether to retry this one-time sync if the sync fails. Not valid for
         * periodic syncs. See {@link ContentResolver#SYNC_EXTRAS_DO_NOT_RETRY}.
         */
        private boolean mNoRetry;
        /**
         * Whether to respect back-off for this one-time sync. Not valid for
         * periodic syncs. See
         * {@link ContentResolver#SYNC_EXTRAS_IGNORE_BACKOFF};
         */
        private boolean mIgnoreBackoff;

        /** Ignore sync system settings and perform sync anyway. */
        private boolean mIgnoreSettings;

        /** This sync will run in preference to other non-expedited syncs. */
        private boolean mExpedited;

        /**
         * The Account object that together with an Authority name define the SyncAdapter (if
         * this sync is bound to a provider), otherwise null.
         */
        private Account mAccount;
        /**
         * The Authority name that together with an Account define the SyncAdapter (if
         * this sync is bound to a provider), otherwise null.
         */
        private String mAuthority;
        /**
         * Whether the sync requires the phone to be plugged in.
         */
        private boolean mRequiresCharging;

        public Builder() {
        }

        /**
         * Request that a sync occur immediately.
         *
         * Example
         * <pre>
         *     SyncRequest.Builder builder = (new SyncRequest.Builder()).syncOnce();
         * </pre>
         */
        public Builder syncOnce() {
            if (mSyncType != SYNC_TYPE_UNKNOWN) {
                throw new IllegalArgumentException("Sync type has already been defined.");
            }
            mSyncType = SYNC_TYPE_ONCE;
            setupInterval(0, 0);
            return this;
        }

        /**
         * Build a periodic sync. Either this or syncOnce() <b>must</b> be called for this builder.
         * Syncs are identified by target {@link android.provider} and by the
         * contents of the extras bundle.
         * You cannot reuse the same builder for one-time syncs after having specified a periodic
         * sync (by calling this function). If you do, an <code>IllegalArgumentException</code>
         * will be thrown.
         * <p>The bundle for a periodic sync can be queried by applications with the correct
         * permissions using
         * {@link ContentResolver#getPeriodicSyncs(Account account, String provider)}, so no
         * sensitive data should be transferred here.
         *
         * Example usage.
         *
         * <pre>
         *     Request a periodic sync every 5 hours with 20 minutes of flex.
         *     SyncRequest.Builder builder =
         *         (new SyncRequest.Builder()).syncPeriodic(5 * HOUR_IN_SECS, 20 * MIN_IN_SECS);
         *
         *     Schedule a periodic sync every hour at any point in time during that hour.
         *     SyncRequest.Builder builder =
         *         (new SyncRequest.Builder()).syncPeriodic(1 * HOUR_IN_SECS, 1 * HOUR_IN_SECS);
         * </pre>
         *
         * N.B.: Periodic syncs are not allowed to have any of
         * {@link ContentResolver#SYNC_EXTRAS_DO_NOT_RETRY},
         * {@link ContentResolver#SYNC_EXTRAS_IGNORE_BACKOFF},
         * {@link ContentResolver#SYNC_EXTRAS_IGNORE_SETTINGS},
         * {@link ContentResolver#SYNC_EXTRAS_INITIALIZE},
         * {@link ContentResolver#SYNC_EXTRAS_FORCE},
         * {@link ContentResolver#SYNC_EXTRAS_EXPEDITED},
         * {@link ContentResolver#SYNC_EXTRAS_MANUAL}
         * set to true. If any are supplied then an <code>IllegalArgumentException</code> will
         * be thrown.
         *
         * @param pollFrequency the amount of time in seconds that you wish
         *            to elapse between periodic syncs. A minimum period of 1 hour is enforced.
         * @param beforeSeconds the amount of flex time in seconds before
         *            {@code pollFrequency} that you permit for the sync to take
         *            place. Must be less than {@code pollFrequency} and greater than
         *            MAX(5% of {@code pollFrequency}, 5 minutes)
         */
        public Builder syncPeriodic(long pollFrequency, long beforeSeconds) {
            if (mSyncType != SYNC_TYPE_UNKNOWN) {
                throw new IllegalArgumentException("Sync type has already been defined.");
            }
            mSyncType = SYNC_TYPE_PERIODIC;
            setupInterval(pollFrequency, beforeSeconds);
            return this;
        }

        private void setupInterval(long at, long before) {
            if (before > at) {
                throw new IllegalArgumentException("Specified run time for the sync must be" +
                    " after the specified flex time.");
            }
            mSyncRunTimeSecs = at;
            mSyncFlexTimeSecs = before;
        }

        /**
         * Will throw an <code>IllegalArgumentException</code> if called and
         * {@link #setIgnoreSettings(boolean ignoreSettings)} has already been called.
         * @param disallow true to allow this transfer on metered networks. Default false.
         *
         */
        public Builder setDisallowMetered(boolean disallow) {
            if (mIgnoreSettings && disallow) {
                throw new IllegalArgumentException("setDisallowMetered(true) after having"
                        + " specified that settings are ignored.");
            }
            mDisallowMetered = disallow;
            return this;
        }

        /**
         * Specify whether the sync requires the phone to be plugged in.
         * @param requiresCharging true if sync requires the phone to be plugged in. Default false.
         */
        public Builder setRequiresCharging(boolean requiresCharging) {
            mRequiresCharging = requiresCharging;
            return this;
        }

        /**
         * Specify an authority and account for this transfer.
         *
         * @param authority A String identifying the content provider to be synced.
         * @param account Account to sync. Can be null unless this is a periodic
         *            sync, for which verification by the ContentResolver will
         *            fail. If a sync is performed without an account, the
         */
        public Builder setSyncAdapter(Account account, String authority) {
            if (mSyncTarget != SYNC_TARGET_UNKNOWN) {
                throw new IllegalArgumentException("Sync target has already been defined.");
            }
            if (authority != null && authority.length() == 0) {
                throw new IllegalArgumentException("Authority must be non-empty");
            }
            mSyncTarget = SYNC_TARGET_ADAPTER;
            mAccount = account;
            mAuthority = authority;
            return this;
        }

        /**
         * Developer-provided extras handed back when sync actually occurs. This bundle is copied
         * into the SyncRequest returned by {@link #build()}.
         *
         * Example:
         * <pre>
         *   String[] syncItems = {"dog", "cat", "frog", "child"};
         *   SyncRequest.Builder builder =
         *     new SyncRequest.Builder()
         *       .setSyncAdapter(dummyAccount, dummyProvider)
         *       .syncOnce();
         *
         *   for (String syncData : syncItems) {
         *     Bundle extras = new Bundle();
         *     extras.setString("data", syncData);
         *     builder.setExtras(extras);
         *     ContentResolver.sync(builder.build()); // Each sync() request creates a unique sync.
         *   }
         * </pre>
         * Only values of the following types may be used in the extras bundle:
         * <ul>
         * <li>Integer</li>
         * <li>Long</li>
         * <li>Boolean</li>
         * <li>Float</li>
         * <li>Double</li>
         * <li>String</li>
         * <li>Account</li>
         * <li>null</li>
         * </ul>
         * If any data is present in the bundle not of this type, build() will
         * throw a runtime exception.
         *
         * @param bundle extras bundle to set.
         */
        public Builder setExtras(Bundle bundle) {
            mCustomExtras = bundle;
            return this;
        }

        /**
         * Convenience function for setting {@link ContentResolver#SYNC_EXTRAS_DO_NOT_RETRY}.
         *
         * A one-off sync operation that fails will be retried with exponential back-off unless
         * this is set to false. Not valid for periodic sync and will throw an
         * <code>IllegalArgumentException</code> in build().
         *
         * @param noRetry true to not retry a failed sync. Default false.
         */
        public Builder setNoRetry(boolean noRetry) {
            mNoRetry = noRetry;
            return this;
        }

        /**
         * Convenience function for setting {@link ContentResolver#SYNC_EXTRAS_IGNORE_SETTINGS}.
         *
         * Not valid for periodic sync and will throw an <code>IllegalArgumentException</code> in
         * {@link #build()}.
         * <p>Throws <code>IllegalArgumentException</code> if called and
         * {@link #setDisallowMetered(boolean)} has been set.
         * 
         *
         * @param ignoreSettings true to ignore the sync automatically settings. Default false.
         */
        public Builder setIgnoreSettings(boolean ignoreSettings) {
            if (mDisallowMetered && ignoreSettings) {
                throw new IllegalArgumentException("setIgnoreSettings(true) after having specified"
                        + " sync settings with this builder.");
            }
            mIgnoreSettings = ignoreSettings;
            return this;
        }

        /**
         * Convenience function for setting {@link ContentResolver#SYNC_EXTRAS_IGNORE_BACKOFF}.
         *
         * Ignoring back-off will force the sync scheduling process to ignore any back-off that was
         * the result of a failed sync, as well as to invalidate any {@link SyncResult#delayUntil}
         * value that may have been set by the adapter. Successive failures will not honor this
         * flag. Not valid for periodic sync and will throw an <code>IllegalArgumentException</code>
         * in {@link #build()}.
         *
         * @param ignoreBackoff ignore back off settings. Default false.
         */
        public Builder setIgnoreBackoff(boolean ignoreBackoff) {
            mIgnoreBackoff = ignoreBackoff;
            return this;
        }

        /**
         * Convenience function for setting {@link ContentResolver#SYNC_EXTRAS_MANUAL}.
         *
         * Not valid for periodic sync and will throw an <code>IllegalArgumentException</code> in
         * {@link #build()}.
         *
         * @param isManual User-initiated sync or not. Default false.
         */
        public Builder setManual(boolean isManual) {
            mIsManual = isManual;
            return this;
        }

        /**
         * An expedited sync runs immediately and can preempt other non-expedited running syncs.
         *
         * Not valid for periodic sync and will throw an <code>IllegalArgumentException</code> in
         * {@link #build()}.
         *
         * @param expedited whether to run expedited. Default false.
         */
        public Builder setExpedited(boolean expedited) {
            mExpedited = expedited;
            return this;
        }

        /**
         * Performs validation over the request and throws the runtime exception
         * <code>IllegalArgumentException</code> if this validation fails.
         *
         * @return a SyncRequest with the information contained within this
         *         builder.
         */
        public SyncRequest build() {
            // Validate the extras bundle
            ContentResolver.validateSyncExtrasBundle(mCustomExtras);
            if (mCustomExtras == null) {
                mCustomExtras = new Bundle();
            }
            // Combine builder extra flags into the config bundle.
            mSyncConfigExtras = new Bundle();
            if (mIgnoreBackoff) {
                mSyncConfigExtras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true);
            }
            if (mDisallowMetered) {
                mSyncConfigExtras.putBoolean(ContentResolver.SYNC_EXTRAS_DISALLOW_METERED, true);
            }
            if (mRequiresCharging) {
                mSyncConfigExtras.putBoolean(ContentResolver.SYNC_EXTRAS_REQUIRE_CHARGING, true);
            }
            if (mIgnoreSettings) {
                mSyncConfigExtras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true);
            }
            if (mNoRetry) {
                mSyncConfigExtras.putBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, true);
            }
            if (mExpedited) {
                mSyncConfigExtras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            }
            if (mIsManual) {
                mSyncConfigExtras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true);
                mSyncConfigExtras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true);
            }
            if (mSyncType == SYNC_TYPE_PERIODIC) {
                // If this is a periodic sync ensure than invalid extras were not set.
                if (ContentResolver.invalidPeriodicExtras(mCustomExtras) ||
                        ContentResolver.invalidPeriodicExtras(mSyncConfigExtras)) {
                    throw new IllegalArgumentException("Illegal extras were set");
                }
            }
            // Ensure that a target for the sync has been set.
            if (mSyncTarget == SYNC_TARGET_UNKNOWN) {
                throw new IllegalArgumentException("Must specify an adapter with" +
                        " setSyncAdapter(Account, String");
            }
            return new SyncRequest(this);
        }
    }
}

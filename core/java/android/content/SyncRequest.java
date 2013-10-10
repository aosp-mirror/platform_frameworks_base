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

public class SyncRequest implements Parcelable {
    private static final String TAG = "SyncRequest";
    /** Account to pass to the sync adapter. May be null. */
    private final Account mAccountToSync;
    /** Authority string that corresponds to a ContentProvider. */
    private final String mAuthority;
    /** Sync service identifier. May be null.*/
    private final ComponentName mComponentInfo;
    /** Bundle containing user info as well as sync settings. */
    private final Bundle mExtras;
    /** Disallow this sync request on metered networks. */
    private final boolean mDisallowMetered;
    /**
     * Anticipated upload size in bytes.
     * TODO: Not yet used - we put this information into the bundle for simplicity.
     */
    private final long mTxBytes;
    /**
     * Anticipated download size in bytes.
     * TODO: Not yet used - we put this information into the bundle.
     */
    private final long mRxBytes;
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
     * @return whether this is an expedited sync.
     */
    public boolean isExpedited() {
        return mIsExpedited;
    }

    /**
     * {@hide}
     * @return true if this sync uses an account/authority pair, or false if this sync is bound to
     * a Sync Service.
     */
    public boolean hasAuthority() {
        return mIsAuthority;
    }

    /**
     * {@hide}
     * @return account object for this sync.
     * @throws IllegalArgumentException if this function is called for a request that does not
     * specify an account/provider authority.
     */
    public Account getAccount() {
        if (!hasAuthority()) {
            throw new IllegalArgumentException("Cannot getAccount() for a sync that does not"
                    + "specify an authority.");
        }
        return mAccountToSync;
    }

    /**
     * {@hide}
     * @return provider for this sync.
     * @throws IllegalArgumentException if this function is called for a request that does not
     * specify an account/provider authority.
     */
    public String getProvider() {
        if (!hasAuthority()) {
            throw new IllegalArgumentException("Cannot getProvider() for a sync that does not"
                    + "specify a provider.");
        }
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
        parcel.writeLong(mTxBytes);
        parcel.writeLong(mRxBytes);
        parcel.writeInt((mIsAuthority ? 1 : 0));
        parcel.writeInt((mIsExpedited? 1 : 0));
        if (mIsAuthority) {
            parcel.writeParcelable(mAccountToSync, flags);
            parcel.writeString(mAuthority);
        } else {
            parcel.writeParcelable(mComponentInfo, flags);
        }
    }

    private SyncRequest(Parcel in) {
        mExtras = in.readBundle();
        mSyncFlexTimeSecs = in.readLong();
        mSyncRunTimeSecs = in.readLong();
        mIsPeriodic = (in.readInt() != 0);
        mDisallowMetered = (in.readInt() != 0);
        mTxBytes = in.readLong();
        mRxBytes = in.readLong();
        mIsAuthority = (in.readInt() != 0);
        mIsExpedited = (in.readInt() != 0);
        if (mIsAuthority) {
            mComponentInfo = null;
            mAccountToSync = in.readParcelable(null);
            mAuthority = in.readString();
        } else {
            mComponentInfo = in.readParcelable(null);
            mAccountToSync = null;
            mAuthority = null;
        }
    }

    /** {@hide} Protected ctor to instantiate anonymous SyncRequest. */
    protected SyncRequest(SyncRequest.Builder b) {
        mSyncFlexTimeSecs = b.mSyncFlexTimeSecs;
        mSyncRunTimeSecs = b.mSyncRunTimeSecs;
        mAccountToSync = b.mAccount;
        mAuthority = b.mAuthority;
        mComponentInfo = b.mComponentName;
        mIsPeriodic = (b.mSyncType == Builder.SYNC_TYPE_PERIODIC);
        mIsAuthority = (b.mSyncTarget == Builder.SYNC_TARGET_ADAPTER);
        mIsExpedited = b.mExpedited;
        mExtras = new Bundle(b.mCustomExtras);
        // For now we merge the sync config extras & the custom extras into one bundle.
        // TODO: pass the configuration extras through separately.
        mExtras.putAll(b.mSyncConfigExtras);
        mDisallowMetered = b.mDisallowMetered;
        mTxBytes = b.mTxBytes;
        mRxBytes = b.mRxBytes;
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
        /** Specify that this is an anonymous sync. */
        private static final int SYNC_TARGET_SERVICE = 1;
        /** Specify that this is a sync with a provider. */
        private static final int SYNC_TARGET_ADAPTER = 2;
        /** Earliest point of displacement into the future at which this sync can occur. */
        private long mSyncFlexTimeSecs;
        /** Latest point of displacement into the future at which this sync must occur. */
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
        /** Expected upload transfer in bytes. */
        private long mTxBytes = -1L;
        /** Expected download transfer in bytes. */
        private long mRxBytes = -1L;
        /** Whether or not this sync can occur on metered networks. Default false. */
        private boolean mDisallowMetered;
        /** Priority of this sync relative to others from calling app [-2, 2]. Default 0. */
        private int mPriority = 0;
        /**
         * Whether this builder is building a periodic sync, or a one-time sync.
         */
        private int mSyncType = SYNC_TYPE_UNKNOWN;
        /** Whether this will go to a sync adapter or to a sync service. */
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
         * The sync component that contains the sync logic if this is a provider-less sync,
         * otherwise null.
         */
        private ComponentName mComponentName;
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
         * Syncs are identified by target {@link android.provider}/{@link android.accounts.Account}
         * and by the contents of the extras bundle.
         * You cannot reuse the same builder for one-time syncs (by calling this function) after
         * having specified a periodic sync. If you do, an <code>IllegalArgumentException</code>
         * will be thrown.
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
         *            to elapse between periodic syncs.
         * @param beforeSeconds the amount of flex time in seconds before
         *            {@code pollFrequency} that you permit for the sync to take
         *            place. Must be less than {@code pollFrequency}.
         */
        public Builder syncPeriodic(long pollFrequency, long beforeSeconds) {
            if (mSyncType != SYNC_TYPE_UNKNOWN) {
                throw new IllegalArgumentException("Sync type has already been defined.");
            }
            mSyncType = SYNC_TYPE_PERIODIC;
            setupInterval(pollFrequency, beforeSeconds);
            return this;
        }

        /** {@hide} */
        private void setupInterval(long at, long before) {
            if (before > at) {
                throw new IllegalArgumentException("Specified run time for the sync must be" +
                    " after the specified flex time.");
            }
            mSyncRunTimeSecs = at;
            mSyncFlexTimeSecs = before;
        }

        /**
         * {@hide}
         * Developer can provide insight into their payload size; optional. -1 specifies unknown,
         * so that you are not restricted to defining both fields.
         *
         * @param rxBytes Bytes expected to be downloaded.
         * @param txBytes Bytes expected to be uploaded.
         */
        public Builder setTransferSize(long rxBytes, long txBytes) {
            mRxBytes = rxBytes;
            mTxBytes = txBytes;
            return this;
        }

        /**
         * @see android.net.ConnectivityManager#isActiveNetworkMetered()
         * @param disallow true to enforce that this transfer not occur on metered networks.
         *                 Default false.
         */
        public Builder setDisallowMetered(boolean disallow) {
            mDisallowMetered = disallow;
            return this;
        }

        /**
         * Specify an authority and account for this transfer.
         *
         * @param authority String identifying which content provider to sync.
         * @param account Account to sync. Can be null unless this is a periodic sync.
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
            mComponentName = null;
            return this;
        }

        /**
         * Optional developer-provided extras handed back in
         * {@link AbstractThreadedSyncAdapter#onPerformSync(Account, Bundle, String,
         * ContentProviderClient, SyncResult)} occurs. This bundle is copied into the SyncRequest
         * returned by {@link #build()}.
         *
         * Example:
         * <pre>
         *   String[] syncItems = {"dog", "cat", "frog", "child"};
         *   SyncRequest.Builder builder =
         *     new SyncRequest.Builder()
         *       .setSyncAdapter(dummyAccount, dummyProvider)
         *       .syncOnce(5 * MINUTES_IN_SECS);
         *
         *   for (String syncData : syncItems) {
         *     Bundle extras = new Bundle();
         *     extras.setString("data", syncData);
         *     builder.setExtras(extras);
         *     ContentResolver.sync(builder.build()); // Each sync() request is for a unique sync.
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
         * A sync can specify that system sync settings be ignored (user has turned sync off). Not
         * valid for periodic sync and will throw an <code>IllegalArgumentException</code> in
         * {@link #build()}.
         *
         * @param ignoreSettings true to ignore the sync automatically settings. Default false.
         */
        public Builder setIgnoreSettings(boolean ignoreSettings) {
            mIgnoreSettings = ignoreSettings;
            return this;
        }

        /**
         * Convenience function for setting {@link ContentResolver#SYNC_EXTRAS_IGNORE_BACKOFF}.
         *
         * Force the sync scheduling process to ignore any back-off that was the result of a failed
         * sync, as well as to invalidate any {@link SyncResult#delayUntil} value that may have
         * been set by the adapter. Successive failures will not honor this flag. Not valid for
         * periodic sync and will throw an <code>IllegalArgumentException</code> in
         * {@link #build()}.
         *
         * @param ignoreBackoff ignore back-off settings. Default false.
         */
        public Builder setIgnoreBackoff(boolean ignoreBackoff) {
            mIgnoreBackoff = ignoreBackoff;
            return this;
        }

        /**
         * Convenience function for setting {@link ContentResolver#SYNC_EXTRAS_MANUAL}.
         *
         * A manual sync is functionally equivalent to calling {@link #setIgnoreBackoff(boolean)}
         * and {@link #setIgnoreSettings(boolean)}. Not valid for periodic sync and will throw an
         * <code>IllegalArgumentException</code> in {@link #build()}.
         *
         * @param isManual User-initiated sync or not. Default false.
         */
        public Builder setManual(boolean isManual) {
            mIsManual = isManual;
            return this;
        }

        /**
         * An expedited sync runs immediately and will preempt another non-expedited running sync.
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
         * {@hide}
         * @param priority the priority of this request among all requests from the calling app.
         * Range of [-2,2] similar to how this is done with notifications.
         */
        public Builder setPriority(int priority) {
            if (priority < -2 || priority > 2) {
                throw new IllegalArgumentException("Priority must be within range [-2, 2]");
            }
            mPriority = priority;
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
            if (mCustomExtras == null) {
                mCustomExtras = new Bundle();
            }
            // Validate the extras bundle
            ContentResolver.validateSyncExtrasBundle(mCustomExtras);
            // Combine builder extra flags into the config bundle.
            mSyncConfigExtras = new Bundle();
            if (mIgnoreBackoff) {
                mSyncConfigExtras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true);
            }
            if (mDisallowMetered) {
                mSyncConfigExtras.putBoolean(ContentResolver.SYNC_EXTRAS_DISALLOW_METERED, true);
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
                mSyncConfigExtras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            }
            mSyncConfigExtras.putLong(ContentResolver.SYNC_EXTRAS_EXPECTED_UPLOAD, mTxBytes);
            mSyncConfigExtras.putLong(ContentResolver.SYNC_EXTRAS_EXPECTED_DOWNLOAD, mRxBytes);
            mSyncConfigExtras.putInt(ContentResolver.SYNC_EXTRAS_PRIORITY, mPriority);
            if (mSyncType == SYNC_TYPE_PERIODIC) {
                // If this is a periodic sync ensure than invalid extras were not set.
                validatePeriodicExtras(mCustomExtras);
                validatePeriodicExtras(mSyncConfigExtras);
                // Verify that account and provider are not null.
                if (mAccount == null) {
                    throw new IllegalArgumentException("Account must not be null for periodic"
                            + " sync.");
                }
                if (mAuthority == null) {
                    throw new IllegalArgumentException("Authority must not be null for periodic"
                            + " sync.");
                }
            } else if (mSyncType == SYNC_TYPE_UNKNOWN) {
                throw new IllegalArgumentException("Must call either syncOnce() or syncPeriodic()");
            }
            // Ensure that a target for the sync has been set.
            if (mSyncTarget == SYNC_TARGET_UNKNOWN) {
                throw new IllegalArgumentException("Must specify an adapter with "
                        + "setSyncAdapter(Account, String");
            }
            return new SyncRequest(this);
        }

        /**
         * Helper function to throw an <code>IllegalArgumentException</code> if any illegal
         * extras were set for a periodic sync.
         *
         * @param extras bundle to validate.
         */
        private void validatePeriodicExtras(Bundle extras) {
            if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)
                    || extras.getBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, false)
                    || extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, false)
                    || extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, false)
                    || extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, false)
                    || extras.getBoolean(ContentResolver.SYNC_EXTRAS_FORCE, false)
                    || extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false)) {
                throw new IllegalArgumentException("Illegal extras were set");
            }
        }
    }
}

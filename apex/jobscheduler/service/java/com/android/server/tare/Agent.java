/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import android.annotation.NonNull;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import java.util.List;

/**
 * Other half of the IRS. The agent handles the nitty gritty details, interacting directly with
 * ledgers, carrying out specific events such as tax collection and granting initial balances or
 * replenishing balances, and tracking ongoing events.
 */
class Agent {
    private static final String TAG = "TARE-" + Agent.class.getSimpleName();
    private static final boolean DEBUG = InternalResourceService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private final CompleteEconomicPolicy mCompleteEconomicPolicy;
    private final InternalResourceService mIrs;

    @GuardedBy("mLock")
    private final SparseArrayMap<String, Ledger> mLedgers = new SparseArrayMap<>();

    @GuardedBy("mLock")
    private long mCurrentNarcsInCirculation;

    Agent(@NonNull InternalResourceService irs,
            @NonNull CompleteEconomicPolicy completeEconomicPolicy) {
        mIrs = irs;
        mCompleteEconomicPolicy = completeEconomicPolicy;
    }

    @GuardedBy("mLock")
    @NonNull
    private Ledger getLedgerLocked(final int userId, @NonNull final String pkgName) {
        Ledger ledger = mLedgers.get(userId, pkgName);
        if (ledger == null) {
            // TODO: load from disk
            ledger = new Ledger();
            mLedgers.add(userId, pkgName, ledger);
        }
        return ledger;
    }

    /** Get an app's current balance, factoring in any currently ongoing events. */
    @GuardedBy("mLock")
    private long getBalanceLocked(final int userId, @NonNull final String pkgName) {
        final Ledger ledger = getLedgerLocked(userId, pkgName);
        long balance = ledger.getCurrentBalance();
        // TODO: add ongoing events
        return balance;
    }

    @GuardedBy("mLock")
    private void recordTransactionLocked(final int userId, @NonNull final String pkgName,
            @NonNull Ledger ledger, @NonNull Ledger.Transaction transaction) {
        final long maxCirculationAllowed = mIrs.getMaxCirculationLocked();
        final long newArcsInCirculation = mCurrentNarcsInCirculation + transaction.delta;
        if (transaction.delta > 0 && newArcsInCirculation > maxCirculationAllowed) {
            final long newDelta = maxCirculationAllowed - mCurrentNarcsInCirculation;
            Slog.i(TAG, "Would result in too many credits in circulation. Decreasing transaction "
                    + transaction.reason + (transaction.tag == null ? "" : ":" + transaction.tag)
                    + " for <" + userId + ">" + pkgName + " by " + (transaction.delta - newDelta));
            transaction = new Ledger.Transaction(
                    transaction.startTimeMs, transaction.endTimeMs,
                    transaction.reason, transaction.tag, newDelta);
        }
        final long originalBalance = ledger.getCurrentBalance();
        if (transaction.delta > 0
                && originalBalance + transaction.delta
                > mCompleteEconomicPolicy.getMaxSatiatedBalance()) {
            final long newDelta = mCompleteEconomicPolicy.getMaxSatiatedBalance() - originalBalance;
            Slog.i(TAG, "Would result in becoming too rich. Decreasing transaction  "
                    + transaction.reason + (transaction.tag == null ? "" : ":" + transaction.tag)
                    + " for <" + userId + ">" + pkgName + " by " + (transaction.delta - newDelta));
            transaction = new Ledger.Transaction(
                    transaction.startTimeMs, transaction.endTimeMs,
                    transaction.reason, transaction.tag, newDelta);
        }
        ledger.recordTransaction(transaction);
        mCurrentNarcsInCirculation += transaction.delta;
        // TODO: save changes to disk in a background thread
        final long newBalance = ledger.getCurrentBalance();
        if (originalBalance <= 0 && newBalance > 0) {
            mIrs.postSolvencyChanged(userId, pkgName, true);
        } else if (originalBalance > 0 && newBalance <= 0) {
            mIrs.postSolvencyChanged(userId, pkgName, false);
        }
    }

    @GuardedBy("mLock")
    void distributeBasicIncomeLocked(int batteryLevel) {
        List<PackageInfo> pkgs = mIrs.getInstalledPackages();
        final long now = System.currentTimeMillis();
        for (int i = 0; i < pkgs.size(); ++i) {
            final PackageInfo pkgInfo = pkgs.get(i);
            final int userId = UserHandle.getUserId(pkgInfo.applicationInfo.uid);
            final String pkgName = pkgInfo.packageName;
            Ledger ledger = getLedgerLocked(userId, pkgName);
            final long minBalance = mIrs.getMinBalanceLocked(userId, pkgName);
            final double perc = batteryLevel / 100d;
            // TODO: maybe don't give credits to bankrupt apps until battery level >= 50%
            if (ledger.getCurrentBalance() < minBalance) {
                final long shortfall = minBalance - getBalanceLocked(userId, pkgName);
                recordTransactionLocked(userId, pkgName, ledger,
                        new Ledger.Transaction(now, now, "UNIVERSAL_BASIC_INCOME",
                                null, (long) (perc * shortfall)));
            }
        }
    }

    /** Give each app an initial balance. */
    @GuardedBy("mLock")
    void grantBirthrightsLocked() {
        UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        final int[] userIds = userManagerInternal.getUserIds();
        for (int userId : userIds) {
            grantBirthrightsLocked(userId);
        }
    }

    @GuardedBy("mLock")
    void grantBirthrightsLocked(final int userId) {
        PackageManager packageManager = mIrs.getContext().getPackageManager();
        List<PackageInfo> pkgs = packageManager.getInstalledPackagesAsUser(0, userId);
        final long maxBirthright =
                mIrs.getMaxCirculationLocked() / mIrs.getInstalledPackages().size();
        final long now = System.currentTimeMillis();

        for (int i = 0; i < pkgs.size(); ++i) {
            final PackageInfo packageInfo = pkgs.get(i);
            final String pkgName = packageInfo.packageName;
            final Ledger ledger = getLedgerLocked(userId, pkgName);
            if (ledger.getCurrentBalance() > 0) {
                // App already got credits somehow. Move along.
                Slog.wtf(TAG, "App " + pkgName + " had credits before economy was set up");
                continue;
            }

            recordTransactionLocked(userId, pkgName, ledger,
                    new Ledger.Transaction(now, now, "BIRTHRIGHT", null,
                            Math.min(maxBirthright, mIrs.getMinBalanceLocked(userId, pkgName))));
        }
    }

    @GuardedBy("mLock")
    void grantBirthrightLocked(final int userId, @NonNull final String pkgName) {
        final Ledger ledger = getLedgerLocked(userId, pkgName);
        if (ledger.getCurrentBalance() > 0) {
            Slog.wtf(TAG, "App " + pkgName + " had credits as soon as it was installed");
            // App already got credits somehow. Move along.
            return;
        }

        List<PackageInfo> pkgs = mIrs.getInstalledPackages();
        final int numPackages = pkgs.size();
        final long maxBirthright = mIrs.getMaxCirculationLocked() / numPackages;
        final long now = System.currentTimeMillis();

        recordTransactionLocked(userId, pkgName, ledger,
                new Ledger.Transaction(now, now, "BIRTHRIGHT", null,
                        Math.min(maxBirthright, mIrs.getMinBalanceLocked(userId, pkgName))));
    }

    @GuardedBy("mLock")
    void onPackageRemovedLocked(final int userId, @NonNull final String pkgName) {
        reclaimAssetsLocked(userId, pkgName);
    }

    /**
     * Reclaims any ARCs granted to the app, making them available to other apps. Also deletes the
     * app's ledger and stops any ongoing event tracking.
     */
    @GuardedBy("mLock")
    private void reclaimAssetsLocked(final int userId, @NonNull final String pkgName) {
        Ledger ledger = getLedgerLocked(userId, pkgName);
        if (ledger.getCurrentBalance() != 0) {
            mCurrentNarcsInCirculation -= ledger.getCurrentBalance();
        }
        // TODO: delete ledger entry from disk
        mLedgers.delete(userId, pkgName);
    }

    @GuardedBy("mLock")
    void onUserRemovedLocked(final int userId, @NonNull final List<String> pkgNames) {
        reclaimAssetsLocked(userId, pkgNames);
    }

    @GuardedBy("mLock")
    private void reclaimAssetsLocked(final int userId, @NonNull final List<String> pkgNames) {
        for (int i = 0; i < pkgNames.size(); ++i) {
            reclaimAssetsLocked(userId, pkgNames.get(i));
        }
    }

    @GuardedBy("mLock")
    void dumpLocked(IndentingPrintWriter pw) {
        pw.print("Current GDP: ");
        pw.println(mCurrentNarcsInCirculation);
    }
}

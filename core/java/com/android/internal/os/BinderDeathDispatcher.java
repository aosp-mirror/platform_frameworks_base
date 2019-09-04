/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * Multiplexes multiple binder death recipients on the same binder objects, so that at the native
 * level, we only need to keep track of one death recipient reference. This will help reduce the
 * number of JNI strong references.
 *
 * test with: atest FrameworksCoreTests:BinderDeathDispatcherTest
 */
public class BinderDeathDispatcher<T extends IInterface> {
    private static final String TAG = "BinderDeathDispatcher";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final ArrayMap<IBinder, RecipientsInfo> mTargets = new ArrayMap<>();

    @VisibleForTesting
    class RecipientsInfo implements DeathRecipient {
        final IBinder mTarget;

        /**
         * Recipient list. If it's null, {@link #mTarget} has already died, but in that case
         * this RecipientsInfo instance is removed from {@link #mTargets}.
         */
        @GuardedBy("mLock")
        @Nullable
        ArraySet<DeathRecipient> mRecipients = new ArraySet<>();

        private RecipientsInfo(IBinder target) {
            mTarget = target;
        }

        @Override
        public void binderDied() {
            final ArraySet<DeathRecipient> copy;
            synchronized (mLock) {
                copy = mRecipients;
                mRecipients = null;

                // Also remove from the targets.
                mTargets.remove(mTarget);
            }
            if (copy == null) {
                return;
            }
            // Let's call it without holding the lock.
            final int size = copy.size();
            for (int i = 0; i < size; i++) {
                copy.valueAt(i).binderDied();
            }
        }
    }

    /**
     * Add a {@code recipient} to the death recipient list on {@code target}.
     *
     * @return # of recipients in the recipient list, including {@code recipient}. Or, -1
     * if {@code target} is already dead, in which case recipient's
     * {@link DeathRecipient#binderDied} won't be called.
     */
    public int linkToDeath(@NonNull T target, @NonNull DeathRecipient recipient) {
        final IBinder ib = target.asBinder();
        synchronized (mLock) {
            RecipientsInfo info = mTargets.get(ib);
            if (info == null) {
                info = new RecipientsInfo(ib);

                // First recipient; need to link to death.
                try {
                    ib.linkToDeath(info, 0);
                } catch (RemoteException e) {
                    return -1; // Already dead.
                }
                mTargets.put(ib, info);
            }
            info.mRecipients.add(recipient);
            return info.mRecipients.size();
        }
    }

    public void unlinkToDeath(@NonNull T target, @NonNull DeathRecipient recipient) {
        final IBinder ib = target.asBinder();

        synchronized (mLock) {
            final RecipientsInfo info = mTargets.get(ib);
            if (info == null) {
                return;
            }
            if (info.mRecipients.remove(recipient) && info.mRecipients.size() == 0) {
                info.mTarget.unlinkToDeath(info, 0);
                mTargets.remove(info.mTarget);
            }
        }
    }

    public void dump(PrintWriter pw, String indent) {
        synchronized (mLock) {
            pw.print(indent);
            pw.print("# of watched binders: ");
            pw.println(mTargets.size());

            pw.print(indent);
            pw.print("# of death recipients: ");
            int n = 0;
            for (RecipientsInfo info : mTargets.values()) {
                n += info.mRecipients.size();
            }
            pw.println(n);
        }
    }

    @VisibleForTesting
    public ArrayMap<IBinder, RecipientsInfo> getTargetsForTest() {
        return mTargets;
    }
}

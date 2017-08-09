/*
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
 * limitations under the License
 */

package com.android.server.fingerprint;

import android.content.Context;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.IBinder;
import android.util.Slog;
import java.util.ArrayList;
import java.util.List;

/**
 * An internal class to help clean up unknown fingerprints in the hardware and software
 */
public abstract class InternalEnumerateClient extends EnumerateClient {

    private List<Fingerprint> mEnrolledList;
    private List<Fingerprint> mEnumeratedList = new ArrayList<>(); // list of fp to delete

    public InternalEnumerateClient(Context context, long halDeviceId, IBinder token,
            IFingerprintServiceReceiver receiver, int groupId, int userId,
            boolean restricted, String owner, List<Fingerprint> enrolledList) {

        super(context, halDeviceId, token, receiver, userId, groupId, restricted, owner);
        mEnrolledList = enrolledList;
    }

    private void handleEnumeratedFingerprint(int fingerId, int groupId, int remaining) {

        boolean matched = false;
        for (int i=0; i<mEnrolledList.size(); i++) {
            if (mEnrolledList.get(i).getFingerId() == fingerId) {
                mEnrolledList.remove(i);
                matched = true;
                Slog.e(TAG, "Matched fingerprint fid=" + fingerId);
                break;
            }
        }

        // fingerId 0 means no fingerprints are in hardware
        if (!matched && fingerId != 0) {
            Fingerprint fingerprint = new Fingerprint("", groupId, fingerId, getHalDeviceId());
            mEnumeratedList.add(fingerprint);
        }
    }

    private void doFingerprintCleanup() {

        if (mEnrolledList == null) {
            return;
        }

        for (Fingerprint f : mEnrolledList) {
            Slog.e(TAG, "Internal Enumerate: Removing dangling enrolled fingerprint: "
                    + f.getName() + " " + f.getFingerId() + " " + f.getGroupId()
                    + " " + f.getDeviceId());

            FingerprintUtils.getInstance().removeFingerprintIdForUser(getContext(),
                    f.getFingerId(), getTargetUserId());
        }
        mEnrolledList.clear();
    }

    public List<Fingerprint> getEnumeratedList() {
        return mEnumeratedList;
    }

    @Override
    public boolean onEnumerationResult(int fingerId, int groupId, int remaining) {

        handleEnumeratedFingerprint(fingerId, groupId, remaining);
        if (remaining == 0) {
            doFingerprintCleanup();
        }

        return remaining == 0;
    }

}

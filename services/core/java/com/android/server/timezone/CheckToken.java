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
 * limitations under the License.
 */

package com.android.server.timezone;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * A deserialized version of the byte[] sent to the time zone update application to identify a
 * triggered time zone update check. It encodes the optimistic lock ID used to detect
 * concurrent checks and the minimal package versions that will have been checked.
 */
final class CheckToken {

    final int mOptimisticLockId;
    final PackageVersions mPackageVersions;

    CheckToken(int optimisticLockId, PackageVersions packageVersions) {
        this.mOptimisticLockId = optimisticLockId;

        if (packageVersions == null) {
            throw new NullPointerException("packageVersions == null");
        }
        this.mPackageVersions = packageVersions;
    }

    byte[] toByteArray() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(12 /* (3 * sizeof(int)) */);
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(mOptimisticLockId);
            dos.writeLong(mPackageVersions.mUpdateAppVersion);
            dos.writeLong(mPackageVersions.mDataAppVersion);
        } catch (IOException e) {
            throw new RuntimeException("Unable to write into a ByteArrayOutputStream", e);
        }
        return baos.toByteArray();
    }

    static CheckToken fromByteArray(byte[] tokenBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(tokenBytes);
        try (DataInputStream dis = new DataInputStream(bais)) {
            int versionId = dis.readInt();
            long updateAppVersion = dis.readLong();
            long dataAppVersion = dis.readLong();
            return new CheckToken(versionId, new PackageVersions(updateAppVersion, dataAppVersion));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        CheckToken checkToken = (CheckToken) o;

        if (mOptimisticLockId != checkToken.mOptimisticLockId) {
            return false;
        }
        return mPackageVersions.equals(checkToken.mPackageVersions);
    }

    @Override
    public int hashCode() {
        int result = mOptimisticLockId;
        result = 31 * result + mPackageVersions.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Token{" +
                "mOptimisticLockId=" + mOptimisticLockId +
                ", mPackageVersions=" + mPackageVersions +
                '}';
    }
}

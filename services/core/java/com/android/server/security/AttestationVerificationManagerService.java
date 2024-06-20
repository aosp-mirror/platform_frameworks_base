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

package com.android.server.security;

import static android.Manifest.permission.USE_ATTESTATION_VERIFICATION_SERVICE;
import static android.security.attestationverification.AttestationVerificationManager.PROFILE_PEER_DEVICE;
import static android.security.attestationverification.AttestationVerificationManager.PROFILE_SELF_TRUSTED;
import static android.security.attestationverification.AttestationVerificationManager.RESULT_FAILURE;
import static android.security.attestationverification.AttestationVerificationManager.RESULT_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelDuration;
import android.os.RemoteException;
import android.security.attestationverification.AttestationProfile;
import android.security.attestationverification.IAttestationVerificationManagerService;
import android.security.attestationverification.IVerificationResult;
import android.security.attestationverification.VerificationToken;
import android.text.TextUtils;
import android.util.ExceptionUtils;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;

/**
 * A {@link SystemService} which provides functionality related to verifying attestations of
 * (usually) remote computing environments.
 *
 * @hide
 */
public class AttestationVerificationManagerService extends SystemService {

    private static final String TAG = "AVF";
    private static final int DUMP_EVENT_LOG_SIZE = 10;
    private final AttestationVerificationPeerDeviceVerifier mPeerDeviceVerifier;
    private final DumpLogger mDumpLogger = new DumpLogger();

    public AttestationVerificationManagerService(final Context context) throws Exception {
        super(context);
        mPeerDeviceVerifier = new AttestationVerificationPeerDeviceVerifier(context, mDumpLogger);
    }

    private final IBinder mService = new IAttestationVerificationManagerService.Stub() {
        @Override
        public void verifyAttestation(
                AttestationProfile profile,
                int localBindingType,
                Bundle requirements,
                byte[] attestation,
                AndroidFuture resultCallback) throws RemoteException {
            enforceUsePermission();
            try {
                Slog.d(TAG, "verifyAttestation");
                verifyAttestationForAllVerifiers(profile, localBindingType, requirements,
                        attestation, resultCallback);
            } catch (Throwable t) {
                Slog.e(TAG, "failed to verify attestation", t);
                throw ExceptionUtils.propagate(t, RemoteException.class);
            }
        }

        @Override
        public void verifyToken(VerificationToken token, ParcelDuration parcelDuration,
                AndroidFuture resultCallback) throws RemoteException {
            enforceUsePermission();
            // TODO(b/201696614): Implement
            resultCallback.complete(RESULT_UNKNOWN);
        }

        private void enforceUsePermission() {
            getContext().enforceCallingOrSelfPermission(USE_ATTESTATION_VERIFICATION_SERVICE, null);
        }

        @Override
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
                @Nullable String[] args) {
            if (!android.security.Flags.dumpAttestationVerifications()) {
                super.dump(fd, writer, args);
                return;
            }

            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, writer)) return;

            final IndentingPrintWriter fout = new IndentingPrintWriter(writer, "    ");

            fout.print("AttestationVerificationManagerService");
            fout.println();
            fout.increaseIndent();

            fout.println("Event Log:");
            fout.increaseIndent();
            mDumpLogger.dumpTo(fout);
            fout.decreaseIndent();
        }
    };

    private void verifyAttestationForAllVerifiers(
            AttestationProfile profile, int localBindingType, Bundle requirements,
            byte[] attestation, AndroidFuture<IVerificationResult> resultCallback) {
        IVerificationResult result = new IVerificationResult();
        // TODO(b/201696614): Implement
        result.token = null;
        switch (profile.getAttestationProfileId()) {
            case PROFILE_SELF_TRUSTED:
                Slog.d(TAG, "Verifying Self Trusted profile.");
                try {
                    result.resultCode =
                            AttestationVerificationSelfTrustedVerifierForTesting.getInstance()
                                    .verifyAttestation(localBindingType, requirements, attestation);
                } catch (Throwable t) {
                    result.resultCode = RESULT_FAILURE;
                }
                break;
            case PROFILE_PEER_DEVICE:
                Slog.d(TAG, "Verifying Peer Device profile.");
                result.resultCode = mPeerDeviceVerifier.verifyAttestation(
                        localBindingType, requirements, attestation);
                break;
            default:
                Slog.d(TAG, "No profile found, defaulting.");
                result.resultCode = RESULT_UNKNOWN;
        }
        resultCallback.complete(result);
    }

    @Override
    public void onStart() {
        Slog.d(TAG, "Started");
        publishBinderService(Context.ATTESTATION_VERIFICATION_SERVICE, mService);
    }


    static class DumpLogger {
        private final ArrayDeque<DumpData> mData = new ArrayDeque<>(DUMP_EVENT_LOG_SIZE);
        private int mEventsLogged = 0;

        void logAttempt(DumpData data) {
            synchronized (mData) {
                if (mData.size() == DUMP_EVENT_LOG_SIZE) {
                    mData.removeFirst();
                }

                mEventsLogged++;
                data.mEventNumber = mEventsLogged;

                data.mEventTimeMs = System.currentTimeMillis();

                mData.add(data);
            }
        }

        void dumpTo(IndentingPrintWriter writer) {
            synchronized (mData) {
                for (DumpData data : mData.reversed()) {
                    writer.println(
                            TextUtils.formatSimple("Verification #%d [%s]", data.mEventNumber,
                                    TimeUtils.formatForLogging(data.mEventTimeMs)));
                    writer.increaseIndent();
                    data.dumpTo(writer);
                    writer.decreaseIndent();
                }
            }
        }
    }

    abstract static class DumpData {
        protected int mEventNumber = -1;
        protected long mEventTimeMs = -1;

        abstract void dumpTo(IndentingPrintWriter writer);
    }
}

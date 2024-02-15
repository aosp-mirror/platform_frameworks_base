/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.security.rkp;

import android.content.Context;
import android.hardware.security.keymint.DeviceInfo;
import android.hardware.security.keymint.IRemotelyProvisionedComponent;
import android.hardware.security.keymint.MacedPublicKey;
import android.hardware.security.keymint.ProtectedData;
import android.hardware.security.keymint.RpcHardwareInfo;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ShellCommand;
import android.security.rkp.service.RegistrationProxy;
import android.security.rkp.service.RemotelyProvisionedKey;
import android.util.IndentingPrintWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;

class RemoteProvisioningShellCommand extends ShellCommand {
    private static final String USAGE = "usage: cmd remote_provisioning SUBCOMMAND [ARGS]\n"
            + "help\n"
            + "  Show this message.\n"
            + "dump\n"
            + "  Dump service diagnostics.\n"
            + "list\n"
            + "  List the names of the IRemotelyProvisionedComponent instances.\n"
            + "csr [--challenge CHALLENGE] NAME\n"
            + "  Generate and print a base64-encoded CSR from the named\n"
            + "  IRemotelyProvisionedComponent. A base64-encoded challenge can be provided,\n"
            + "  or else it defaults to an empty challenge.\n"
            + "certify NAME\n"
            + "  Output the PEM-encoded certificate chain provisioned for the named\n"
            + "  IRemotelyProvisionedComponent.\n";

    static final String EEK_ED25519_BASE64 = "goRDoQEnoFgqpAEBAycgBiFYIJm57t1e5FL2hcZMYtw+YatXSH11N"
            + "ymtdoAy0rPLY1jZWEAeIghLpLekyNdOAw7+uK8UTKc7b6XN3Np5xitk/pk5r3bngPpmAIUNB5gqrJFcpyUUS"
            + "QY0dcqKJ3rZ41pJ6wIDhEOhASegWE6lAQECWCDQrsEVyirPc65rzMvRlh1l6LHd10oaN7lDOpfVmd+YCAM4G"
            + "CAEIVggvoXnRsSjQlpA2TY6phXQLFh+PdwzAjLS/F4ehyVfcmBYQJvPkOIuS6vRGLEOjl0gJ0uEWP78MpB+c"
            + "gWDvNeCvvpkeC1UEEvAMb9r6B414vAtzmwvT/L1T6XUg62WovGHWAQ=";

    static final String EEK_P256_BASE64 = "goRDoQEmoFhNpQECAyYgASFYIPcUituX9MxT79JkEcTjdR9mH6RxDGzP"
            + "+glGgHSHVPKtIlggXn9b9uzk9hnM/xM3/Q+hyJPbGAZ2xF3m12p3hsMtr49YQC+XjkL7vgctlUeFR5NAsB/U"
            + "m0ekxESp8qEHhxDHn8sR9L+f6Dvg5zRMFfx7w34zBfTRNDztAgRgehXgedOK/ySEQ6EBJqBYcaYBAgJYIDVz"
            + "tz+gioCJsSZn6ct8daGvAmH8bmUDkTvTS30UlD5GAzgYIAEhWCDgQc8vDzQPHDMsQbDP1wwwVTXSHmpHE0su"
            + "0UiWfiScaCJYIB/ORcX7YbqBIfnlBZubOQ52hoZHuB4vRfHOr9o/gGjbWECMs7p+ID4ysGjfYNEdffCsOI5R"
            + "vP9s4Wc7Snm8Vnizmdh8igfY2rW1f3H02GvfMyc0e2XRKuuGmZirOrSAqr1Q";

    private static final int ERROR = -1;
    private static final int SUCCESS = 0;

    private static final Duration BIND_TIMEOUT = Duration.ofSeconds(10);
    private static final int KEY_ID = 452436;

    private final Context mContext;
    private final int mCallerUid;
    private final Injector mInjector;

    RemoteProvisioningShellCommand(Context context, int callerUid) {
        this(context, callerUid, new Injector());
    }

    RemoteProvisioningShellCommand(Context context, int callerUid, Injector injector) {
        mContext = context;
        mCallerUid = callerUid;
        mInjector = injector;
    }

    @Override
    public void onHelp() {
        getOutPrintWriter().print(USAGE);
    }

    @Override
    @SuppressWarnings("CatchAndPrintStackTrace")
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        try {
            switch (cmd) {
                case "list":
                    return list();
                case "csr":
                    return csr();
                case "certify":
                    return certify();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            e.printStackTrace(getErrPrintWriter());
            return ERROR;
        }
    }

    @SuppressWarnings("CatchAndPrintStackTrace")
    void dump(PrintWriter pw) {
        try {
            IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
            for (String name : mInjector.getIrpcNames()) {
                ipw.println(name + ":");
                ipw.increaseIndent();
                dumpRpcInstance(ipw, name);
                ipw.decreaseIndent();
            }
        } catch (Exception e) {
            e.printStackTrace(pw);
        }
    }

    private void dumpRpcInstance(PrintWriter pw, String name) throws RemoteException {
        RpcHardwareInfo info = mInjector.getIrpcBinder(name).getHardwareInfo();
        pw.println("hwVersion=" + info.versionNumber);
        pw.println("rpcAuthorName=" + info.rpcAuthorName);
        if (info.versionNumber < 3) {
            pw.println("supportedEekCurve=" + info.supportedEekCurve);
        }
        pw.println("uniqueId=" + info.uniqueId);
        if (info.versionNumber >= 3) {
            pw.println("supportedNumKeysInCsr=" + info.supportedNumKeysInCsr);
        }
    }

    private int list() throws RemoteException {
        for (String name : mInjector.getIrpcNames()) {
            getOutPrintWriter().println(name);
        }
        return SUCCESS;
    }

    private int csr() throws RemoteException, CborException {
        byte[] challenge = {};
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--challenge":
                    challenge = Base64.getDecoder().decode(getNextArgRequired());
                    break;
                default:
                    getErrPrintWriter().println("error: unknown option " + opt);
                    return ERROR;
            }
        }
        String name = getNextArgRequired();

        IRemotelyProvisionedComponent binder = mInjector.getIrpcBinder(name);
        RpcHardwareInfo info = binder.getHardwareInfo();
        MacedPublicKey[] emptyKeys = new MacedPublicKey[] {};
        byte[] csrBytes;
        switch (info.versionNumber) {
            case 1:
            case 2:
                DeviceInfo deviceInfo = new DeviceInfo();
                ProtectedData protectedData = new ProtectedData();
                byte[] eek = getEekChain(info.supportedEekCurve);
                byte[] keysToSignMac = binder.generateCertificateRequest(
                        /*testMode=*/false, emptyKeys, eek, challenge, deviceInfo, protectedData);
                csrBytes = composeCertificateRequestV1(
                        deviceInfo, challenge, protectedData, keysToSignMac);
                break;
            case 3:
                csrBytes = binder.generateCertificateRequestV2(emptyKeys, challenge);
                break;
            default:
                getErrPrintWriter().println("error: unsupported hwVersion: " + info.versionNumber);
                return ERROR;
        }
        getOutPrintWriter().println(Base64.getEncoder().encodeToString(csrBytes));
        return SUCCESS;
    }

    private byte[] getEekChain(int supportedEekCurve) {
        switch (supportedEekCurve) {
            case RpcHardwareInfo.CURVE_25519:
                return Base64.getDecoder().decode(EEK_ED25519_BASE64);
            case RpcHardwareInfo.CURVE_P256:
                return Base64.getDecoder().decode(EEK_P256_BASE64);
            default:
                throw new IllegalArgumentException("unsupported EEK curve: " + supportedEekCurve);
        }
    }

    private byte[] composeCertificateRequestV1(DeviceInfo deviceInfo, byte[] challenge,
            ProtectedData protectedData, byte[] keysToSignMac) throws CborException {
        Array info = new Array()
                .add(decode(deviceInfo.deviceInfo))
                .add(new Map());

        // COSE_Signature with the hmac-sha256 algorithm and without a payload.
        Array mac = new Array()
                .add(new ByteString(encode(
                            new Map().put(new UnsignedInteger(1), new UnsignedInteger(5)))))
                .add(new Map())
                .add(SimpleValue.NULL)
                .add(new ByteString(keysToSignMac));

        Array csr = new Array()
                .add(info)
                .add(new ByteString(challenge))
                .add(decode(protectedData.protectedData))
                .add(mac);

        return encode(csr);
    }

    private byte[] encode(DataItem item) throws CborException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new CborEncoder(baos).encode(item);
        return baos.toByteArray();
    }

    private DataItem decode(byte[] data) throws CborException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        return new CborDecoder(bais).decodeNext();
    }

    private int certify() throws Exception {
        String name = getNextArgRequired();

        Executor executor = mContext.getMainExecutor();
        CancellationSignal cancellationSignal = new CancellationSignal();
        OutcomeFuture<RemotelyProvisionedKey> key = new OutcomeFuture<>();
        mInjector.getRegistrationProxy(mContext, mCallerUid, name, executor)
                .getKeyAsync(KEY_ID, cancellationSignal, executor, key);
        byte[] encodedCertChain = key.join().getEncodedCertChain();
        ByteArrayInputStream is = new ByteArrayInputStream(encodedCertChain);
        PrintWriter pw = getOutPrintWriter();
        for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(is)) {
            String encoded = Base64.getEncoder().encodeToString(cert.getEncoded());
            pw.println("-----BEGIN CERTIFICATE-----");
            pw.println(encoded.replaceAll("(.{64})", "$1\n").stripTrailing());
            pw.println("-----END CERTIFICATE-----");
        }
        return SUCCESS;
    }

    /** Treat an OutcomeReceiver as a future for use in synchronous code. */
    private static class OutcomeFuture<T> implements OutcomeReceiver<T, Exception> {
        private CompletableFuture<T> mFuture = new CompletableFuture<>();

        @Override
          public void onResult(T result) {
            mFuture.complete(result);
        }

        @Override
        public void onError(Exception e) {
            mFuture.completeExceptionally(e);
        }

        public T join() {
            return mFuture.join();
        }
    }

    static class Injector {
        String[] getIrpcNames() {
            return ServiceManager.getDeclaredInstances(IRemotelyProvisionedComponent.DESCRIPTOR);
        }

        IRemotelyProvisionedComponent getIrpcBinder(String name) {
            String irpc = IRemotelyProvisionedComponent.DESCRIPTOR + "/" + name;
            IRemotelyProvisionedComponent binder =
                    IRemotelyProvisionedComponent.Stub.asInterface(
                            ServiceManager.waitForDeclaredService(irpc));
            if (binder == null) {
                throw new IllegalArgumentException("failed to find " + irpc);
            }
            return binder;
        }

        RegistrationProxy getRegistrationProxy(
                Context context, int callerUid, String name, Executor executor) {
            String irpc = IRemotelyProvisionedComponent.DESCRIPTOR + "/" + name;
            OutcomeFuture<RegistrationProxy> registration = new OutcomeFuture<>();
            RegistrationProxy.createAsync(
                    context, callerUid, irpc, BIND_TIMEOUT, executor, registration);
            return registration.join();
        }
    }
}

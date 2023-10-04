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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.security.keymint.DeviceInfo;
import android.hardware.security.keymint.IRemotelyProvisionedComponent;
import android.hardware.security.keymint.MacedPublicKey;
import android.hardware.security.keymint.ProtectedData;
import android.hardware.security.keymint.RpcHardwareInfo;
import android.os.Binder;
import android.os.FileUtils;
import android.os.OutcomeReceiver;
import android.os.Process;
import android.security.rkp.service.RegistrationProxy;
import android.security.rkp.service.RemotelyProvisionedKey;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
public class RemoteProvisioningShellCommandTest {

    private Context mContext;

    private static class Injector extends RemoteProvisioningShellCommand.Injector {

        Map<String, IRemotelyProvisionedComponent> mIrpcs;
        Map<String, RegistrationProxy> mRegistrationProxies;

        @Override
        String[] getIrpcNames() {
            return mIrpcs.keySet().toArray(new String[0]);
        }

        @Override
        IRemotelyProvisionedComponent getIrpcBinder(String name) {
            IRemotelyProvisionedComponent irpc = mIrpcs.get(name);
            if (irpc == null) {
                throw new IllegalArgumentException("failed to find " + irpc);
            }
            return irpc;
        }

        @Override
        RegistrationProxy getRegistrationProxy(
                Context context, int callerUid, String name, Executor executor) {
            return mRegistrationProxies.get(name);
        }
    }

    private static class CommandResult {
        private int mCode;
        private String mOut;
        private String mErr;

        CommandResult(int code, String out, String err) {
            mCode = code;
            mOut = out;
            mErr = err;
        }

        int getCode() {
            return mCode;
        }

        String getOut() {
            return mOut;
        }

        String getErr() {
            return mErr;
        }
    }

    private static CommandResult exec(
            RemoteProvisioningShellCommand cmd, String[] args) throws Exception {
        File in = File.createTempFile("rpsct_in_", null);
        File out = File.createTempFile("rpsct_out_", null);
        File err = File.createTempFile("rpsct_err_", null);
        int code = cmd.exec(
                new Binder(),
                new FileInputStream(in).getFD(),
                new FileOutputStream(out).getFD(),
                new FileOutputStream(err).getFD(),
                args);
        return new CommandResult(
                code, FileUtils.readTextFile(out, 0, null), FileUtils.readTextFile(err, 0, null));
    }

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void list_zeroInstances() throws Exception {
        Injector injector = new Injector();
        injector.mIrpcs = Map.of();
        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                mContext, Process.SHELL_UID, injector);
        CommandResult res = exec(cmd, new String[] {"list"});
        assertThat(res.getErr()).isEmpty();
        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getOut()).isEmpty();
        assertThat(res.getOut().lines()).isEmpty();
    }

    @Test
    public void list_oneInstances() throws Exception {
        Injector injector = new Injector();
        injector.mIrpcs = Map.of("default", mock(IRemotelyProvisionedComponent.class));
        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                mContext, Process.SHELL_UID, injector);
        CommandResult res = exec(cmd, new String[] {"list"});
        assertThat(res.getErr()).isEmpty();
        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getOut().lines()).containsExactly("default");
    }

    @Test
    public void list_twoInstances() throws Exception {
        Injector injector = new Injector();
        injector.mIrpcs = Map.of(
                "default", mock(IRemotelyProvisionedComponent.class),
                "strongbox", mock(IRemotelyProvisionedComponent.class));
        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                mContext, Process.SHELL_UID, injector);
        CommandResult res = exec(cmd, new String[] {"list"});
        assertThat(res.getErr()).isEmpty();
        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getOut().lines()).containsExactly("default", "strongbox");
    }

    @Test
    public void csr_hwVersion1_withChallenge() throws Exception {
        IRemotelyProvisionedComponent defaultMock = mock(IRemotelyProvisionedComponent.class);
        RpcHardwareInfo defaultInfo = new RpcHardwareInfo();
        defaultInfo.versionNumber = 1;
        defaultInfo.supportedEekCurve = RpcHardwareInfo.CURVE_25519;
        when(defaultMock.getHardwareInfo()).thenReturn(defaultInfo);
        doAnswer(invocation -> {
            ((DeviceInfo) invocation.getArgument(4)).deviceInfo = new byte[] {0x00};
            ((ProtectedData) invocation.getArgument(5)).protectedData = new byte[] {0x00};
            return new byte[] {0x77, 0x77, 0x77, 0x77};
        }).when(defaultMock).generateCertificateRequest(
                anyBoolean(), any(), any(), any(), any(), any());

        Injector injector = new Injector();
        injector.mIrpcs = Map.of("default", defaultMock);
        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                mContext, Process.SHELL_UID, injector);
        CommandResult res = exec(cmd, new String[] {
                "csr", "--challenge", "dGVzdHRlc3R0ZXN0dGVzdA==", "default"});
        verify(defaultMock).generateCertificateRequest(
                /*test_mode=*/eq(false),
                eq(new MacedPublicKey[0]),
                eq(Base64.getDecoder().decode(RemoteProvisioningShellCommand.EEK_ED25519_BASE64)),
                eq(new byte[] {
                        0x74, 0x65, 0x73, 0x74, 0x74, 0x65, 0x73, 0x74,
                        0x74, 0x65, 0x73, 0x74, 0x74, 0x65, 0x73, 0x74}),
                any(DeviceInfo.class),
                any(ProtectedData.class));
        assertThat(res.getErr()).isEmpty();
        assertThat(res.getCode()).isEqualTo(0);
    }

    @Test
    public void csr_hwVersion2_withChallenge() throws Exception {
        IRemotelyProvisionedComponent defaultMock = mock(IRemotelyProvisionedComponent.class);
        RpcHardwareInfo defaultInfo = new RpcHardwareInfo();
        defaultInfo.versionNumber = 2;
        defaultInfo.supportedEekCurve = RpcHardwareInfo.CURVE_P256;
        when(defaultMock.getHardwareInfo()).thenReturn(defaultInfo);
        doAnswer(invocation -> {
            ((DeviceInfo) invocation.getArgument(4)).deviceInfo = new byte[] {0x00};
            ((ProtectedData) invocation.getArgument(5)).protectedData = new byte[] {0x00};
            return new byte[] {0x77, 0x77, 0x77, 0x77};
        }).when(defaultMock).generateCertificateRequest(
                anyBoolean(), any(), any(), any(), any(), any());

        Injector injector = new Injector();
        injector.mIrpcs = Map.of("default", defaultMock);
        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                mContext, Process.SHELL_UID, injector);
        CommandResult res = exec(cmd, new String[] {
                "csr", "--challenge", "dGVzdHRlc3R0ZXN0dGVzdA==", "default"});
        verify(defaultMock).generateCertificateRequest(
                /*test_mode=*/eq(false),
                eq(new MacedPublicKey[0]),
                eq(Base64.getDecoder().decode(RemoteProvisioningShellCommand.EEK_P256_BASE64)),
                eq(new byte[] {
                        0x74, 0x65, 0x73, 0x74, 0x74, 0x65, 0x73, 0x74,
                        0x74, 0x65, 0x73, 0x74, 0x74, 0x65, 0x73, 0x74}),
                any(DeviceInfo.class),
                any(ProtectedData.class));
        assertThat(res.getErr()).isEmpty();
        assertThat(res.getCode()).isEqualTo(0);
    }

    @Test
    public void csr_hwVersion3_withoutChallenge() throws Exception {
        IRemotelyProvisionedComponent defaultMock = mock(IRemotelyProvisionedComponent.class);
        RpcHardwareInfo defaultInfo = new RpcHardwareInfo();
        defaultInfo.versionNumber = 3;
        when(defaultMock.getHardwareInfo()).thenReturn(defaultInfo);
        when(defaultMock.generateCertificateRequestV2(any(), any()))
            .thenReturn(new byte[] {0x68, 0x65, 0x6c, 0x6c, 0x6f});

        Injector injector = new Injector();
        injector.mIrpcs = Map.of("default", defaultMock);
        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                mContext, Process.SHELL_UID, injector);
        CommandResult res = exec(cmd, new String[] {"csr", "default"});
        verify(defaultMock).generateCertificateRequestV2(new MacedPublicKey[0], new byte[0]);
        assertThat(res.getErr()).isEmpty();
        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getOut()).isEqualTo("aGVsbG8=\n");
    }

    @Test
    public void csr_hwVersion3_withChallenge() throws Exception {
        IRemotelyProvisionedComponent defaultMock = mock(IRemotelyProvisionedComponent.class);
        RpcHardwareInfo defaultInfo = new RpcHardwareInfo();
        defaultInfo.versionNumber = 3;
        when(defaultMock.getHardwareInfo()).thenReturn(defaultInfo);
        when(defaultMock.generateCertificateRequestV2(any(), any()))
            .thenReturn(new byte[] {0x68, 0x69});

        Injector injector = new Injector();
        injector.mIrpcs = Map.of("default", defaultMock);
        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                mContext, Process.SHELL_UID, injector);
        CommandResult res = exec(cmd, new String[] {"csr", "--challenge", "dHJpYWw=", "default"});
        verify(defaultMock).generateCertificateRequestV2(
                new MacedPublicKey[0], new byte[] {0x74, 0x72, 0x69, 0x61, 0x6c});
        assertThat(res.getErr()).isEmpty();
        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getOut()).isEqualTo("aGk=\n");
    }

    @Test
    public void certify_sameOrderAsReceived() throws Exception {
        String cert1 = "MIIBqDCCAU2gAwIBAgIUI3FFU7xZno/2Xf/wZzKKquP0ov0wCgYIKoZIzj0EAwIw\n"
                + "KTELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAkNBMQ0wCwYDVQQKDARUZXN0MB4XDTIz\n"
                + "MDgyMjE5MzgxMFoXDTMzMDgxOTE5MzgxMFowKTELMAkGA1UEBhMCVVMxCzAJBgNV\n"
                + "BAgMAkNBMQ0wCwYDVQQKDARUZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE\n"
                + "czOpG6NKOdDjV/yrKjuy0q0jEJvsVLGgTeY+vyKRBJS59OhyRWG6n3aza21bNg5d\n"
                + "WE9ruz+bcT0IP4kDbiS0y6NTMFEwHQYDVR0OBBYEFHYfJxCUipNI7qRqvczcWsOb\n"
                + "FIDPMB8GA1UdIwQYMBaAFHYfJxCUipNI7qRqvczcWsObFIDPMA8GA1UdEwEB/wQF\n"
                + "MAMBAf8wCgYIKoZIzj0EAwIDSQAwRgIhAKm/kpJwlnWkjoLCAddBiSnxbT4EfJIK\n"
                + "H0j58tg5VazHAiEAnS/kRzU9AbstOZyD7el/ws3gLXkbUNey3pLFutBWsSU=\n";
        String cert2 = "MIIBpjCCAU2gAwIBAgIUdSzfZzeGr+h70JPO7Sxwdkw99iMwCgYIKoZIzj0EAwIw\n"
                + "KTELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAkNBMQ0wCwYDVQQKDARUZXN0MB4XDTIz\n"
                + "MDgyMjIwMTcyMFoXDTMzMDgxOTIwMTcyMFowKTELMAkGA1UEBhMCVVMxCzAJBgNV\n"
                + "BAgMAkNBMQ0wCwYDVQQKDARUZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE\n"
                + "voGJi4DxuqH8rzPV6Eq0OVULc0xFzaM0500VBqiQEB7Qt0Ktk2d+3bUrFAb3SZV4\n"
                + "6TIdb7SkynvaDtr0x45Ng6NTMFEwHQYDVR0OBBYEFMeGjvGV0ADPBJk5/FPoW9HQ\n"
                + "uTc6MB8GA1UdIwQYMBaAFMeGjvGV0ADPBJk5/FPoW9HQuTc6MA8GA1UdEwEB/wQF\n"
                + "MAMBAf8wCgYIKoZIzj0EAwIDRwAwRAIgd1gu7iiNOQXaQUn5BT3WwWR0Yk78ndWt\n"
                + "ew7tRiTOhFcCIFURi6WcNH0oWa6IbwBSMC9aZlo98Fbg+dTwhLAAw+PW\n";
        byte[] cert1Bytes = Base64.getDecoder().decode(cert1.replaceAll("\\s+", ""));
        byte[] cert2Bytes = Base64.getDecoder().decode(cert2.replaceAll("\\s+", ""));
        byte[] certChain = Arrays.copyOf(cert1Bytes, cert1Bytes.length + cert2Bytes.length);
        System.arraycopy(cert2Bytes, 0, certChain, cert1Bytes.length, cert2Bytes.length);
        RemotelyProvisionedKey keyMock = mock(RemotelyProvisionedKey.class);
        when(keyMock.getEncodedCertChain()).thenReturn(certChain);
        RegistrationProxy defaultMock = mock(RegistrationProxy.class);
        doAnswer(invocation -> {
            ((OutcomeReceiver<RemotelyProvisionedKey, Exception>) invocation.getArgument(3))
                    .onResult(keyMock);
            return null;
        }).when(defaultMock).getKeyAsync(anyInt(), any(), any(), any());

        Injector injector = new Injector();
        injector.mRegistrationProxies = Map.of("default", defaultMock);
        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                mContext, Process.SHELL_UID, injector);
        CommandResult res = exec(cmd, new String[] {"certify", "default"});
        assertThat(res.getErr()).isEmpty();
        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getOut()).isEqualTo(
                "-----BEGIN CERTIFICATE-----\n" + cert1 + "-----END CERTIFICATE-----\n"
                + "-----BEGIN CERTIFICATE-----\n" + cert2 + "-----END CERTIFICATE-----\n");
    }

    @Test
    public void certify_noBlankLineBeforeTrailer() throws Exception {
        String cert = "MIIB2zCCAYGgAwIBAgIRAOpN7Em1k7gaqLAB2dzXUTYwCgYIKoZIzj0EAwIwKTET\n"
                + "MBEGA1UEChMKR29vZ2xlIExMQzESMBAGA1UEAxMJRHJvaWQgQ0EzMB4XDTIzMDgx\n"
                + "ODIzMzI1MloXDTIzMDkyMTIzMzI1MlowOTEMMAoGA1UEChMDVEVFMSkwJwYDVQQD\n"
                + "EyBlYTRkZWM0OWI1OTNiODFhYThiMDAxZDlkY2Q3NTEzNjBZMBMGByqGSM49AgEG\n"
                + "CCqGSM49AwEHA0IABHM/cKZblmlw8bdGbDXnX+ZiLiGjSjaLHXYOoHDrVArAMXUi\n"
                + "L6brhcUPaqSGcVLcfFZbaFMOxXW6TsGdQiwJ0iyjejB4MB0GA1UdDgQWBBTYzft+\n"
                + "X32TH/Hh+ngwQF6aPhnfXDAfBgNVHSMEGDAWgBQT4JObI9mzNNW2FRsHRcw4zVn2\n"
                + "8jAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwICBDAVBgorBgEEAdZ5AgEe\n"
                + "BAehARoABAAAMAoGCCqGSM49BAMCA0gAMEUCIDc0OR7CzIYw0myTr0y/Brl1nZyk\n"
                + "eGSQp615WpTwYhwxAiEApM10gSIKBIo7Z4/FNzkuiz1zZwW9+Dcqisqxkfe6icQ=\n";
        byte[] certBytes = Base64.getDecoder().decode(cert.replaceAll("\\s+", ""));
        RemotelyProvisionedKey keyMock = mock(RemotelyProvisionedKey.class);
        when(keyMock.getEncodedCertChain()).thenReturn(certBytes);
        RegistrationProxy defaultMock = mock(RegistrationProxy.class);
        doAnswer(invocation -> {
            ((OutcomeReceiver<RemotelyProvisionedKey, Exception>) invocation.getArgument(3))
                    .onResult(keyMock);
            return null;
        }).when(defaultMock).getKeyAsync(anyInt(), any(), any(), any());

        Injector injector = new Injector();
        injector.mRegistrationProxies = Map.of("strongbox", defaultMock);
        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                mContext, Process.SHELL_UID, injector);
        CommandResult res = exec(cmd, new String[] {"certify", "strongbox"});
        assertThat(res.getErr()).isEmpty();
        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getOut()).isEqualTo(
                "-----BEGIN CERTIFICATE-----\n" + cert + "-----END CERTIFICATE-----\n");
    }
}

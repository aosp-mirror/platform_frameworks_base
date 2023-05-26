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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.security.keymint.DeviceInfo;
import android.hardware.security.keymint.IRemotelyProvisionedComponent;
import android.hardware.security.keymint.MacedPublicKey;
import android.hardware.security.keymint.ProtectedData;
import android.hardware.security.keymint.RpcHardwareInfo;
import android.os.Binder;
import android.os.FileUtils;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Base64;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class RemoteProvisioningShellCommandTest {

    private static class Injector extends RemoteProvisioningShellCommand.Injector {

        private final Map<String, IRemotelyProvisionedComponent> mIrpcs;

        Injector(Map irpcs) {
            mIrpcs = irpcs;
        }

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

    @Test
    public void list_zeroInstances() throws Exception {
        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                new Injector(Map.of()));
        CommandResult res = exec(cmd, new String[] {"list"});
        assertThat(res.getErr()).isEmpty();
        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getOut()).isEmpty();
        assertThat(res.getOut().lines()).isEmpty();
    }

    @Test
    public void list_oneInstances() throws Exception {
        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                new Injector(Map.of("default", mock(IRemotelyProvisionedComponent.class))));
        CommandResult res = exec(cmd, new String[] {"list"});
        assertThat(res.getErr()).isEmpty();
        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getOut().lines()).containsExactly("default");
    }

    @Test
    public void list_twoInstances() throws Exception {
        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                new Injector(Map.of(
                       "default", mock(IRemotelyProvisionedComponent.class),
                       "strongbox", mock(IRemotelyProvisionedComponent.class))));
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

        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                new Injector(Map.of("default", defaultMock)));
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

        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                new Injector(Map.of("default", defaultMock)));
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

        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                new Injector(Map.of("default", defaultMock)));
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

        RemoteProvisioningShellCommand cmd = new RemoteProvisioningShellCommand(
                new Injector(Map.of("default", defaultMock)));
        CommandResult res = exec(cmd, new String[] {"csr", "--challenge", "dHJpYWw=", "default"});
        verify(defaultMock).generateCertificateRequestV2(
                new MacedPublicKey[0], new byte[] {0x74, 0x72, 0x69, 0x61, 0x6c});
        assertThat(res.getErr()).isEmpty();
        assertThat(res.getCode()).isEqualTo(0);
        assertThat(res.getOut()).isEqualTo("aGk=\n");
    }
}

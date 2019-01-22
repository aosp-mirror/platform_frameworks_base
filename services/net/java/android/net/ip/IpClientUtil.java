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

package android.net.ip;

import android.content.Context;
import android.net.LinkProperties;
import android.os.ConditionVariable;

import java.io.FileDescriptor;
import java.io.PrintWriter;


/**
 * Utilities and wrappers to simplify communication with IpClient, which lives in the NetworkStack
 * process.
 *
 * @hide
 */
public class IpClientUtil {
    // TODO: remove once IpClient dumps are moved to NetworkStack and callers don't need this arg
    public static final String DUMP_ARG = IpClient.DUMP_ARG;

    /**
     * Subclass of {@link IpClientCallbacks} allowing clients to block until provisioning is
     * complete with {@link WaitForProvisioningCallbacks#waitForProvisioning()}.
     */
    public static class WaitForProvisioningCallbacks extends IpClientCallbacks {
        private final ConditionVariable mCV = new ConditionVariable();
        private LinkProperties mCallbackLinkProperties;

        /**
         * Block until either {@link #onProvisioningSuccess(LinkProperties)} or
         * {@link #onProvisioningFailure(LinkProperties)} is called.
         */
        public LinkProperties waitForProvisioning() {
            mCV.block();
            return mCallbackLinkProperties;
        }

        @Override
        public void onProvisioningSuccess(LinkProperties newLp) {
            mCallbackLinkProperties = newLp;
            mCV.open();
        }

        @Override
        public void onProvisioningFailure(LinkProperties newLp) {
            mCallbackLinkProperties = null;
            mCV.open();
        }
    }

    /**
     * Create a new IpClient.
     *
     * <p>This is a convenience method to allow clients to use {@link IpClientCallbacks} instead of
     * {@link IIpClientCallbacks}.
     */
    public static void makeIpClient(Context context, String ifName, IpClientCallbacks callback) {
        // TODO: request IpClient asynchronously from NetworkStack.
        final IpClient ipClient = new IpClient(context, ifName, callback);
        callback.onIpClientCreated(ipClient.makeConnector());
    }

    /**
     * Dump logs for the specified IpClient.
     * TODO: remove logging from this method once IpClient logs are dumped in NetworkStack dumpsys,
     * then remove callers and delete.
     */
    public static void dumpIpClient(
            IIpClient connector, FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!(connector instanceof IpClient.IpClientConnector)) {
            pw.println("Invalid connector");
            return;
        }
        ((IpClient.IpClientConnector) connector).dumpIpClientLogs(fd, pw, args);
    }
}

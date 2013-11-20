/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net.dhcp;

import java.net.InetAddress;
import java.util.List;

/**
 * This class defines the "next steps" which occur after a given DHCP
 * packet has been received.
 */
interface DhcpStateMachine {
    /**
     * Signals that an offer packet has been received with the specified
     * parameters.
     */
    public void onOfferReceived(boolean broadcast, int transactionId,
        byte[] myMac, InetAddress offeredIpAddress,
        InetAddress serverIpAddress);

    /**
     * Signals that a NAK packet has been received.
     */
    public void onNakReceived();

    /**
     * Signals that the final ACK has been received from the server.
     */
    public void onAckReceived(InetAddress myIpAddress, InetAddress myNetMask,
        InetAddress myGateway, List<InetAddress> myDnsServers,
        InetAddress myDhcpServer, int leaseTime);

    /**
     * Signals that a client's DISCOVER packet has been received with the
     * specified parameters.
     */
    public void onDiscoverReceived(boolean broadcast, int transactionId,
        byte[] clientMac, byte[] requestedParameterList);

    /**
     * Signals that a client's REQUEST packet has been received with the
     * specified parameters.
     */
    public void onRequestReceived(boolean broadcast, int transactionId,
        byte[] clientMac, InetAddress requestedIp, byte[] requestedParams,
        String clientHostName);

    /**
     * Signals that a client's INFORM packet has been received with the
     * specified parameters.
     */
    public void onInformReceived(int transactionId, byte[] clientMac,
        InetAddress preassignedIp, byte[] requestedParams);

    /**
     * Signals that a client's DECLINE packet has been received with the
     * specified parameters.
     */
    public void onDeclineReceived(byte[] clientMac, InetAddress declinedIp);
}

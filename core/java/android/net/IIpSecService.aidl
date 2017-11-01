/*
** Copyright 2017, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.net;

import android.net.Network;
import android.net.IpSecConfig;
import android.net.IpSecUdpEncapResponse;
import android.net.IpSecSpiResponse;
import android.net.IpSecTransformResponse;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

/**
 * @hide
 */
interface IIpSecService
{
    IpSecSpiResponse reserveSecurityParameterIndex(
            int direction, in String remoteAddress, int requestedSpi, in IBinder binder);

    void releaseSecurityParameterIndex(int resourceId);

    IpSecUdpEncapResponse openUdpEncapsulationSocket(int port, in IBinder binder);

    void closeUdpEncapsulationSocket(int resourceId);

    IpSecTransformResponse createTransportModeTransform(in IpSecConfig c, in IBinder binder);

    void deleteTransportModeTransform(int transformId);

    void applyTransportModeTransform(in ParcelFileDescriptor socket, int transformId);

    void removeTransportModeTransform(in ParcelFileDescriptor socket, int transformId);
}

/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.net;

import android.net.NetworkIdentity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;

/**
 * Identity of a {@code iface}, defined by the set of {@link NetworkIdentity}
 * active on that interface.
 *
 * @hide
 */
public class NetworkIdentitySet extends HashSet<NetworkIdentity> implements
        Comparable<NetworkIdentitySet> {
    private static final int VERSION_INIT = 1;
    private static final int VERSION_ADD_ROAMING = 2;
    private static final int VERSION_ADD_NETWORK_ID = 3;

    public NetworkIdentitySet() {
    }

    public NetworkIdentitySet(DataInputStream in) throws IOException {
        final int version = in.readInt();
        final int size = in.readInt();
        for (int i = 0; i < size; i++) {
            if (version <= VERSION_INIT) {
                final int ignored = in.readInt();
            }
            final int type = in.readInt();
            final int subType = in.readInt();
            final String subscriberId = readOptionalString(in);
            final String networkId;
            if (version >= VERSION_ADD_NETWORK_ID) {
                networkId = readOptionalString(in);
            } else {
                networkId = null;
            }
            final boolean roaming;
            if (version >= VERSION_ADD_ROAMING) {
                roaming = in.readBoolean();
            } else {
                roaming = false;
            }

            add(new NetworkIdentity(type, subType, subscriberId, networkId, false));
        }
    }

    public void writeToStream(DataOutputStream out) throws IOException {
        out.writeInt(VERSION_ADD_NETWORK_ID);
        out.writeInt(size());
        for (NetworkIdentity ident : this) {
            out.writeInt(ident.getType());
            out.writeInt(ident.getSubType());
            writeOptionalString(out, ident.getSubscriberId());
            writeOptionalString(out, ident.getNetworkId());
            out.writeBoolean(ident.getRoaming());
        }
    }

    private static void writeOptionalString(DataOutputStream out, String value) throws IOException {
        if (value != null) {
            out.writeByte(1);
            out.writeUTF(value);
        } else {
            out.writeByte(0);
        }
    }

    private static String readOptionalString(DataInputStream in) throws IOException {
        if (in.readByte() != 0) {
            return in.readUTF();
        } else {
            return null;
        }
    }

    @Override
    public int compareTo(NetworkIdentitySet another) {
        if (isEmpty()) return -1;
        if (another.isEmpty()) return 1;

        final NetworkIdentity ident = iterator().next();
        final NetworkIdentity anotherIdent = another.iterator().next();
        return ident.compareTo(anotherIdent);
    }
}

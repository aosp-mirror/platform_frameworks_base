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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.HashSet;

/**
 * Identity of a {@code iface}, defined by the set of {@link NetworkIdentity}
 * active on that interface.
 *
 * @hide
 */
public class InterfaceIdentity extends HashSet<NetworkIdentity> {
    private static final int VERSION_CURRENT = 1;

    public InterfaceIdentity() {
    }

    public InterfaceIdentity(DataInputStream in) throws IOException {
        final int version = in.readInt();
        switch (version) {
            case VERSION_CURRENT: {
                final int size = in.readInt();
                for (int i = 0; i < size; i++) {
                    add(new NetworkIdentity(in));
                }
                break;
            }
            default: {
                throw new ProtocolException("unexpected version: " + version);
            }
        }
    }

    public void writeToStream(DataOutputStream out) throws IOException {
        out.writeInt(VERSION_CURRENT);
        out.writeInt(size());
        for (NetworkIdentity ident : this) {
            ident.writeToStream(out);
        }
    }

    /**
     * Test if any {@link NetworkIdentity} on this interface matches the given
     * template and IMEI.
     */
    public boolean matchesTemplate(int networkTemplate, String subscriberId) {
        for (NetworkIdentity ident : this) {
            if (ident.matchesTemplate(networkTemplate, subscriberId)) {
                return true;
            }
        }
        return false;
    }
}

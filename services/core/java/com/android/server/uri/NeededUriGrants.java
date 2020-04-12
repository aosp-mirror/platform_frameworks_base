/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.uri;

import android.util.ArraySet;
import android.util.proto.ProtoOutputStream;

import com.android.server.am.NeededUriGrantsProto;

/** List of {@link GrantUri} a process needs. */
public class NeededUriGrants {
    final String targetPkg;
    final int targetUid;
    final int flags;
    final ArraySet<GrantUri> uris;

    public NeededUriGrants(String targetPkg, int targetUid, int flags) {
        this.targetPkg = targetPkg;
        this.targetUid = targetUid;
        this.flags = flags;
        this.uris = new ArraySet<>();
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(NeededUriGrantsProto.TARGET_PACKAGE, targetPkg);
        proto.write(NeededUriGrantsProto.TARGET_UID, targetUid);
        proto.write(NeededUriGrantsProto.FLAGS, flags);

        final int N = uris.size();
        for (int i = 0; i < N; i++) {
            uris.valueAt(i).dumpDebug(proto, NeededUriGrantsProto.GRANTS);
        }
        proto.end(token);
    }
}

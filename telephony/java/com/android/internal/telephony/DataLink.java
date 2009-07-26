/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.os.Handler;
import android.os.Registrant;

/**
 * Base class representing the data link layer (eg, PPP).
 *
 * {@hide}
 */
public abstract class DataLink extends Handler implements DataLinkInterface {

    /** Registrant for link status change notifications. */
    protected Registrant mLinkChangeRegistrant;
    protected DataConnectionTracker dataConnection;

    protected DataLink(DataConnectionTracker dc) {
        dataConnection = dc;
    }

    public void setOnLinkChange(Handler h, int what, Object obj) {
        mLinkChangeRegistrant = new Registrant(h, what, obj);
    }
}

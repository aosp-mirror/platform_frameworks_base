/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.connectivity;

import android.net.ConnectivityMetricsEvent;
import android.net.ConnectivityMetricsLogger;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

abstract public class MetricsTestUtil {
    private MetricsTestUtil() {
    }

    static ConnectivityMetricsEvent ipEv(Parcelable p) {
        return ev(ConnectivityMetricsLogger.COMPONENT_TAG_CONNECTIVITY, p);
    }

    static ConnectivityMetricsEvent telephonyEv() {
        return ev(ConnectivityMetricsLogger.COMPONENT_TAG_TELEPHONY, new Bundle());
    }

    static ConnectivityMetricsEvent ev(int tag, Parcelable p) {
        return new ConnectivityMetricsEvent(1L, tag, 0, p);
    }

    // Utiliy interface for describing the content of a Parcel. This relies on
    // the implementation defails of Parcelable and on the fact that the fully
    // qualified Parcelable class names are written as string in the Parcels.
    interface ParcelField {
        void write(Parcel p);
    }

    static ConnectivityMetricsEvent describeIpEvent(ParcelField... fs) {
        Parcel p = Parcel.obtain();
        for (ParcelField f : fs) {
            f.write(p);
        }
        p.setDataPosition(0);
        return ipEv(p.readParcelable(ClassLoader.getSystemClassLoader()));
    }

    static ParcelField aType(Class<?> c) {
        return new ParcelField() {
            public void write(Parcel p) {
                p.writeString(c.getName());
            }
        };
    }

    static ParcelField aBool(boolean b) {
        return aByte((byte) (b ? 1 : 0));
    }

    static ParcelField aByte(byte b) {
        return new ParcelField() {
            public void write(Parcel p) {
                p.writeByte(b);
            }
        };
    }

    static ParcelField anInt(int i) {
        return new ParcelField() {
            public void write(Parcel p) {
                p.writeInt(i);
            }
        };
    }

    static ParcelField aLong(long l) {
        return new ParcelField() {
            public void write(Parcel p) {
                p.writeLong(l);
            }
        };
    }

    static ParcelField aString(String s) {
        return new ParcelField() {
            public void write(Parcel p) {
                p.writeString(s);
            }
        };
    }

    static ParcelField aByteArray(byte... ary) {
        return new ParcelField() {
            public void write(Parcel p) {
                p.writeByteArray(ary);
            }
        };
    }

    static ParcelField anIntArray(int... ary) {
        return new ParcelField() {
            public void write(Parcel p) {
                p.writeIntArray(ary);
            }
        };
    }

    static byte b(int i) {
        return (byte) i;
    }
}

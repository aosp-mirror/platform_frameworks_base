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
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.function.Consumer;

abstract public class MetricsTestUtil {
    private MetricsTestUtil() {
    }

    static ConnectivityMetricsEvent ev(Parcelable p) {
        ConnectivityMetricsEvent ev = new ConnectivityMetricsEvent();
        ev.timestamp = 1L;
        ev.data = p;
        return ev;
    }

    static ConnectivityMetricsEvent describeIpEvent(Consumer<Parcel>... fs) {
        Parcel p = Parcel.obtain();
        for (Consumer<Parcel> f : fs) {
            f.accept(p);
        }
        p.setDataPosition(0);
        return ev(p.readParcelable(ClassLoader.getSystemClassLoader()));
    }

    static Consumer<Parcel> aType(Class<?> c) {
        return aString(c.getName());
    }

    static Consumer<Parcel> aBool(boolean b) {
        return aByte((byte) (b ? 1 : 0));
    }

    static Consumer<Parcel> aByte(byte b) {
        return (p) -> p.writeByte(b);
    }

    static Consumer<Parcel> anInt(int i) {
        return (p) -> p.writeInt(i);
    }

    static Consumer<Parcel> aLong(long l) {
        return (p) -> p.writeLong(l);
    }

    static Consumer<Parcel> aString(String s) {
        return (p) -> p.writeString(s);
    }

    static Consumer<Parcel> aByteArray(byte... ary) {
        return (p) -> p.writeByteArray(ary);
    }

    static Consumer<Parcel> anIntArray(int... ary) {
        return (p) -> p.writeIntArray(ary);
    }

    static byte b(int i) {
        return (byte) i;
    }
}

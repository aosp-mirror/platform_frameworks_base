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
package com.android.codegentest;

import android.os.Parcel;

import com.android.internal.util.Parcelling;

import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sample {@link Parcelling} implementation for {@link Date}.
 *
 * See {@link SampleDataClass#mDate} for usage.
 * See {@link SampleDataClass#writeToParcel} + {@link SampleDataClass#sParcellingForDate}
 * for resulting generated code.
 *
 * Ignore {@link #sInstanceCount} - used for testing.
 */
public class MyDateParcelling implements Parcelling<Date> {

    static AtomicInteger sInstanceCount = new AtomicInteger(0);

    public MyDateParcelling() {
        sInstanceCount.getAndIncrement();
    }

    @Override
    public void parcel(Date item, Parcel dest, int parcelFlags) {
        dest.writeLong(item.getTime());
    }

    @Override
    public Date unparcel(Parcel source) {
        return new Date(source.readLong());
    }
}

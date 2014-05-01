/*
 * Copyright 2014, The Android Open Source Project
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


package com.android.server.pm;


import java.io.PrintWriter;
import com.android.server.IntentResolver;
import java.util.List;

/**
 * Used to find a list of {@link ForwardingIntentFilter}s that match an intent.
 */
class ForwardingIntentResolver
        extends IntentResolver<ForwardingIntentFilter, ForwardingIntentFilter> {
    @Override
    protected ForwardingIntentFilter[] newArray(int size) {
        return new ForwardingIntentFilter[size];
    }

    @Override
    protected boolean isPackageForFilter(String packageName, ForwardingIntentFilter filter) {
        return false;
    }

    @Override
    protected void sortResults(List<ForwardingIntentFilter> results) {
        //We don't sort the results
    }
}

/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.test;

import android.os.Bundle;

import com.android.internal.location.ProviderRequest;
import com.android.server.location.AbstractLocationProvider;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;

public class FakeProvider extends AbstractLocationProvider {

    public FakeProvider() {
        super(Runnable::run, Collections.emptySet());
    }

    @Override
    protected void onSetRequest(ProviderRequest request) {}

    @Override
    protected void onExtraCommand(int uid, int pid, String command, Bundle extras) {}

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {}
}

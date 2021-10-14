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

import android.location.provider.ProviderRequest;
import android.os.Bundle;

import com.android.server.location.provider.AbstractLocationProvider;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;

public class FakeProvider extends AbstractLocationProvider {

    public interface FakeProviderInterface {

        void onSetRequest(ProviderRequest request);

        void onFlush(Runnable callback);

        void onExtraCommand(int uid, int pid, String command, Bundle extras);

        void dump(FileDescriptor fd, PrintWriter pw, String[] args);
    }

    private final FakeProviderInterface mFakeInterface;

    public FakeProvider(FakeProviderInterface fakeInterface) {
        super(Runnable::run, null, null, Collections.emptySet());
        mFakeInterface = fakeInterface;
    }

    @Override
    protected void onSetRequest(ProviderRequest request) {
        mFakeInterface.onSetRequest(request);
    }

    @Override
    protected void onFlush(Runnable callback) {
        mFakeInterface.onFlush(callback);
    }

    @Override
    protected void onExtraCommand(int uid, int pid, String command, Bundle extras) {
        mFakeInterface.onExtraCommand(uid, pid, command, extras);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mFakeInterface.dump(fd, pw, args);
    }
}

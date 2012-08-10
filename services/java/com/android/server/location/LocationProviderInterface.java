/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.location;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;


import android.os.Bundle;
import android.os.WorkSource;

/**
 * Location Manager's interface for location providers.
 * @hide
 */
public interface LocationProviderInterface {
    public String getName();

    public void enable();
    public void disable();
    public boolean isEnabled();
    public void setRequest(ProviderRequest request, WorkSource source);

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args);

    // --- deprecated (but still supported) ---
    public ProviderProperties getProperties();
    public int getStatus(Bundle extras);
    public long getStatusUpdateTime();
    public boolean sendExtraCommand(String command, Bundle extras);
}

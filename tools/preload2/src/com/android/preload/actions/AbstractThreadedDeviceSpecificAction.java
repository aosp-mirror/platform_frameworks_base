/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.preload.actions;

import com.android.ddmlib.IDevice;

import java.awt.event.ActionEvent;

public abstract class AbstractThreadedDeviceSpecificAction extends AbstractThreadedAction
        implements DeviceSpecific {

    protected IDevice device;

    protected AbstractThreadedDeviceSpecificAction(String title, IDevice device) {
        super(title);
        this.device = device;
    }

    @Override
    public void setDevice(IDevice device) {
        this.device = device;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (device == null) {
            return;
        }
        super.actionPerformed(e);
    }
}

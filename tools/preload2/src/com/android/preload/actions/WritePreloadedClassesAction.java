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

import com.android.ddmlib.Client;
import com.android.ddmlib.IDevice;
import com.android.preload.ClientUtils;
import com.android.preload.DeviceUtils;
import com.android.preload.DumpData;
import com.android.preload.DumpTableModel;
import com.android.preload.Main;

import java.awt.event.ActionEvent;
import java.io.File;
import java.util.Date;
import java.util.Map;

public class WritePreloadedClassesAction extends AbstractThreadedDeviceSpecificAction {
    private File preloadedClassFile;

    public WritePreloadedClassesAction(ClientUtils utils, IDevice device, DumpTableModel dataTableModel) {
        super("Write preloaded classes action", device);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        File[] files = Main.getUI().showOpenDialog(true);
        if (files != null && files.length > 0) {
            preloadedClassFile = files[0];
            super.actionPerformed(e);
        }
    }

    @Override
    public void run() {
        Main.getUI().showWaitDialog();
        try {
            // Write the new file with a 5-minute timeout
            DeviceUtils.overwritePreloaded(device, preloadedClassFile, 5 * 60);
        } catch (Exception e) {
            System.err.println(e);
        } finally {
            Main.getUI().hideWaitDialog();
        }
    }
}

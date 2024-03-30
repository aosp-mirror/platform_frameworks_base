/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import com.android.systemui.statusbar.phone.SystemUIDialog;

import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

/**
 * Dialog for showing hearing devices controls.
 */
public class HearingDevicesDialogDelegate implements SystemUIDialog.Delegate{

    private final SystemUIDialog.Factory mSystemUIDialogFactory;

    private SystemUIDialog mDialog;

    /** Factory to create a {@link HearingDevicesDialogDelegate} dialog instance. */
    @AssistedFactory
    public interface Factory {
        /** Create a {@link HearingDevicesDialogDelegate} instance */
        HearingDevicesDialogDelegate create();
    }

    @AssistedInject
    public HearingDevicesDialogDelegate(
            SystemUIDialog.Factory systemUIDialogFactory) {
        mSystemUIDialogFactory = systemUIDialogFactory;
    }

    @Override
    public SystemUIDialog createDialog() {
        SystemUIDialog dialog = mSystemUIDialogFactory.create(this);

        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = dialog;

        return dialog;
    }
}

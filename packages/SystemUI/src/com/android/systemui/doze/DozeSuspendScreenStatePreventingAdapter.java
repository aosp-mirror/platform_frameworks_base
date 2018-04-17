/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.doze;

import androidx.annotation.VisibleForTesting;
import android.view.Display;

import com.android.systemui.statusbar.phone.DozeParameters;

/**
 * Prevents usage of doze screen states on devices that don't support them.
 */
public class DozeSuspendScreenStatePreventingAdapter extends DozeMachine.Service.Delegate {

    @VisibleForTesting
    DozeSuspendScreenStatePreventingAdapter(DozeMachine.Service inner) {
        super(inner);
    }

    @Override
    public void setDozeScreenState(int state) {
        if (state == Display.STATE_DOZE_SUSPEND) {
            state = Display.STATE_DOZE;
        }
        super.setDozeScreenState(state);
    }

    /**
     * If the device supports the doze display state, return {@code inner}. Otherwise
     * return a new instance of {@link DozeSuspendScreenStatePreventingAdapter} wrapping {@code inner}.
     */
    public static DozeMachine.Service wrapIfNeeded(DozeMachine.Service inner,
            DozeParameters params) {
        return isNeeded(params) ? new DozeSuspendScreenStatePreventingAdapter(inner) : inner;
    }

    private static boolean isNeeded(DozeParameters params) {
        return !params.getDozeSuspendDisplayStateSupported();
    }
}

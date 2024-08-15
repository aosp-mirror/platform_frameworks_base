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
package com.android.systemui.dreams.homecontrols

import android.app.Activity
import android.service.dreams.DreamService
import javax.inject.Inject

class DreamServiceDelegateImpl @Inject constructor() : DreamServiceDelegate {
    override fun getActivity(dreamService: DreamService): Activity {
        return dreamService.activity
    }

    override fun finish(dreamService: DreamService) {
        dreamService.finish()
    }

    override fun wakeUp(dreamService: DreamService) {
        dreamService.wakeUp()
    }

    override fun redirectWake(dreamService: DreamService): Boolean {
        return dreamService.redirectWake
    }
}

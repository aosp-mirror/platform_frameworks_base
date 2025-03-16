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

package com.android.systemui.statusbar.data.repository

import android.view.WindowManager
import com.android.app.viewcapture.ViewCaptureAwareWindowManager
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.display.data.repository.displayWindowPropertiesRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import org.mockito.kotlin.mock

val Kosmos.fakePrivacyDotWindowControllerStore by
    Kosmos.Fixture { FakePrivacyDotWindowControllerStore() }

val Kosmos.privacyDotWindowControllerStoreImpl by
    Kosmos.Fixture {
        PrivacyDotWindowControllerStoreImpl(
            backgroundApplicationScope = applicationCoroutineScope,
            displayRepository = displayRepository,
            windowControllerFactory = { _, _, _, _ -> mock() },
            displayWindowPropertiesRepository = displayWindowPropertiesRepository,
            privacyDotViewControllerStore = privacyDotViewControllerStore,
            viewCaptureAwareWindowManagerFactory =
                object : ViewCaptureAwareWindowManager.Factory {
                    override fun create(
                        windowManager: WindowManager
                    ): ViewCaptureAwareWindowManager {
                        return mock()
                    }
                },
        )
    }

var Kosmos.privacyDotWindowControllerStore: PrivacyDotWindowControllerStore by
    Kosmos.Fixture { fakePrivacyDotWindowControllerStore }

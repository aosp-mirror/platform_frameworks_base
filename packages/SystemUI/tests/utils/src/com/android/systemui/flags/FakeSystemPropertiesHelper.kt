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

package com.android.systemui.flags

import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject

@SysUISingleton
class FakeSystemPropertiesHelper @Inject constructor() : SystemPropertiesHelper() {
    private val fakeProperties = mutableMapOf<String, Any>()

    override fun get(name: String): String {
        return fakeProperties[name] as String
    }

    override fun get(name: String, def: String?): String {
        return checkNotNull(fakeProperties[name] as String? ?: def)
    }

    override fun getBoolean(name: String, default: Boolean): Boolean {
        return fakeProperties[name] as Boolean? ?: default
    }

    override fun setBoolean(name: String, value: Boolean) {
        fakeProperties[name] = value
    }

    override fun set(name: String, value: String) {
        fakeProperties[name] = value
    }

    override fun set(name: String, value: Int) {
        fakeProperties[name] = value
    }

    override fun erase(name: String) {
        fakeProperties.remove(name)
    }
}

@Module
interface FakeSystemPropertiesHelperModule {
    @Binds fun bindFake(fake: FakeSystemPropertiesHelper): SystemPropertiesHelper
}

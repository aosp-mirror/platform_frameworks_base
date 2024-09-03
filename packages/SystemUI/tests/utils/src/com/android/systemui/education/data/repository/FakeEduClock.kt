/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.data.repository

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration

class FakeEduClock(private var base: Instant) : Clock() {
    private val zone: ZoneId = ZoneId.of("UTC")

    override fun instant(): Instant {
        return base
    }

    override fun withZone(zoneId: ZoneId?): Clock {
        return FakeEduClock(base)
    }

    override fun getZone(): ZoneId {
        return zone
    }

    fun offset(duration: Duration) {
        base = base.plusSeconds(duration.inWholeSeconds)
    }
}

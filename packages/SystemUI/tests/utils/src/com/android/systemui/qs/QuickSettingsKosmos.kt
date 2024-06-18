/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs

import com.android.internal.logging.uiEventLoggerFake
import com.android.systemui.InstanceIdSequenceFake
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.plugins.qs.QSFactory
import com.android.systemui.qs.tiles.di.NewQSTileFactory

val Kosmos.instanceIdSequenceFake: InstanceIdSequenceFake by
    Kosmos.Fixture { InstanceIdSequenceFake(0) }
val Kosmos.qsEventLogger: QsEventLoggerFake by
    Kosmos.Fixture { QsEventLoggerFake(uiEventLoggerFake, instanceIdSequenceFake) }

var Kosmos.newQSTileFactory by Kosmos.Fixture<NewQSTileFactory>()
var Kosmos.qsTileFactory by Kosmos.Fixture<QSFactory>()

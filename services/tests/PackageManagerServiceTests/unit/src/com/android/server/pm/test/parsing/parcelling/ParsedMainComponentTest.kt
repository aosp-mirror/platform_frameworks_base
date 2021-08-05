/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm.test.parsing.parcelling

import android.content.pm.parsing.component.ParsedMainComponent
import android.content.pm.parsing.component.ParsedService
import android.os.Parcelable
import java.util.Arrays
import kotlin.contracts.ExperimentalContracts
import kotlin.reflect.KClass
import kotlin.reflect.KFunction1

@ExperimentalContracts
abstract class ParsedMainComponentTest(kClass: KClass<out Parcelable>) :
    ParsedComponentTest(kClass) {

    final override val subclassBaseParams
        get() = mainComponentSubclassBaseParams + listOf(
            ParsedMainComponent::getOrder,
            ParsedMainComponent::getProcessName,
            ParsedMainComponent::getSplitName,
            ParsedMainComponent::isDirectBootAware,
            ParsedMainComponent::isEnabled,
            ParsedMainComponent::isExported,
        )

    abstract val mainComponentSubclassBaseParams: Collection<KFunction1<*, Any?>>

    final override fun subclassExtraParams() = mainComponentSubclassExtraParams() + listOf(
        getSetByValue(
            ParsedService::getAttributionTags,
            ParsedService::setAttributionTags,
            arrayOf("testAttributionTag"),
            compare = Arrays::equals
        ),
    )

    open fun mainComponentSubclassExtraParams(): Collection<Param?> = emptyList()
}

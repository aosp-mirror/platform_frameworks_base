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

import android.content.pm.PackageManager
import com.android.internal.pm.pkg.component.ParsedComponent
import com.android.internal.pm.pkg.component.ParsedComponentImpl
import com.android.internal.pm.pkg.component.ParsedIntentInfoImpl
import android.os.Bundle
import android.os.Parcelable
import kotlin.contracts.ExperimentalContracts
import kotlin.reflect.KClass
import kotlin.reflect.KFunction1

@ExperimentalContracts
abstract class ParsedComponentTest(getterType: KClass<*>, setterType: KClass<out Parcelable>) :
    ParcelableComponentTest(getterType, setterType) {

    constructor(getterAndSetterType: KClass<out Parcelable>) :
            this(getterAndSetterType, getterAndSetterType)

    final override val excludedMethods
        get() = subclassExcludedMethods + listOf(
            // Method aliases/utilities
            "getClassName",
            "getComponentName",
            "setProperties" // Tested though addProperty
        )

    open val subclassExcludedMethods: Collection<String> = emptyList()

    final override val baseParams
        get() = subclassBaseParams + listOf(
            ParsedComponent::getBanner,
            ParsedComponent::getDescriptionRes,
            ParsedComponent::getFlags,
            ParsedComponent::getIcon,
            ParsedComponent::getLabelRes,
            ParsedComponent::getLogo,
            ParsedComponent::getName,
            ParsedComponent::getNonLocalizedLabel,
            ParsedComponent::getPackageName,
        )

    abstract val subclassBaseParams: Collection<KFunction1<*, Any?>>

    final override fun extraParams() = subclassExtraParams() + listOf(
        getSetByValue(
            ParsedComponent::getIntents,
            ParsedComponentImpl::addIntent,
            "TestLabel",
            transformGet = { it.singleOrNull()?.nonLocalizedLabel },
            transformSet = { ParsedIntentInfoImpl()
                .setNonLocalizedLabel(it!!) },
        ),
        getSetByValue(
            ParsedComponent::getProperties,
            ParsedComponentImpl::addProperty,
            PackageManager.Property(
                "testPropertyName",
                "testPropertyValue",
                "testPropertyClassName",
                "testPropertyPackageName"
            ),
            transformGet = { it["testPropertyName"] },
            compare = { first, second ->
                equalBy(
                    first, second,
                    PackageManager.Property::getName,
                    PackageManager.Property::getClassName,
                    PackageManager.Property::getPackageName,
                    PackageManager.Property::getString,
                )
            }
        ),
        getSetByValue(
            ParsedComponent::getMetaData,
            ParsedComponentImpl::setMetaData,
            "testBundleKey" to "testBundleValue",
            transformGet = { "testBundleKey" to it?.getString("testBundleKey") },
            transformSet = { Bundle().apply { putString(it.first, it.second) } }
        ),
    )

    open fun subclassExtraParams(): Collection<Param?> = emptyList()
}

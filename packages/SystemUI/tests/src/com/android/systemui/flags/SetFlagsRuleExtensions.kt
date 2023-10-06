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

package com.android.systemui.flags

import android.platform.test.flag.junit.SetFlagsRule

fun SetFlagsRule.setFlagDefault(flagName: String) {
    if (getFlagDefault(flagName)) {
        enableFlags(flagName)
    } else {
        disableFlags(flagName)
    }
}

// NOTE: This code uses reflection to gain access to private members of aconfig generated
//  classes (in the same way SetFlagsRule does internally) because this is the only way to get
//  at the underlying information and read the current value of the flag.
// If aconfig had flag constants with accessible default values, this would be unnecessary.
private fun getFlagDefault(name: String): Boolean {
    val flagPackage = name.substringBeforeLast(".")
    val featureFlagsImplClass = Class.forName("$flagPackage.FeatureFlagsImpl")
    val featureFlagsImpl = featureFlagsImplClass.getConstructor().newInstance()
    val flagMethodName = name.substringAfterLast(".").snakeToCamelCase()
    val flagGetter = featureFlagsImplClass.getDeclaredMethod(flagMethodName)
    return flagGetter.invoke(featureFlagsImpl) as Boolean
}

private fun String.snakeToCamelCase(): String {
    val pattern = "_[a-z]".toRegex()
    return replace(pattern) { it.value.last().uppercase() }
}

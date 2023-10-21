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

/**
 * Set the given flag's value to the real value for the current build configuration.
 * This prevents test code from crashing because it is reading an unspecified flag value.
 *
 * REMINDER: You should always test your code with your flag in both configurations, so
 * generally you should be explicitly enabling or disabling your flag. This method is for
 * situations where the flag needs to be read (e.g. in the class constructor), but its value
 * shouldn't affect the actual test cases. In those cases, it's mildly safer to use this method
 * than to hard-code `false` or `true` because then at least if you're wrong, and the flag value
 * *does* matter, you'll notice when the flag is flipped and tests start failing.
 */
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

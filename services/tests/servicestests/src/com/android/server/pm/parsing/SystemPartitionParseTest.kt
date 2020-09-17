/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.parsing

import android.content.pm.PackageManager
import android.content.pm.PackageParser
import android.platform.test.annotations.Postsubmit
import com.android.server.pm.PackageManagerService
import org.junit.Test

/**
 * This test parses all the system APKs on the device image to ensure that they succeed.
 *
 * Any invalid APKs should be removed from the device or marked as skipped through any mechanism
 * for ignoring packages.
 *
 * This test must run on deferred postsubmit. Targeted presubmit will not catch errors fast enough,
 * and the low failure rate does not warrant global presubmit.
 */
@Postsubmit
class SystemPartitionParseTest {

    private val APKS = PackageManagerService.SYSTEM_PARTITIONS
            .flatMap { listOfNotNull(it.appFolder, it.privAppFolder, it.overlayFolder) }
            .flatMap {
                it.walkTopDown()
                        .filter { it.name.endsWith(".apk") }
                        .toList()
            }
            .distinct()

    private val parser = PackageParser2.forParsingFileWithDefaults()

    @Test
    fun verify() {
        val exceptions = APKS
                .map {
                    runCatching {
                        parser.parsePackage(it, PackageParser.PARSE_IS_SYSTEM_DIR, false)
                    }
                }
                .mapNotNull { it.exceptionOrNull() }
                .filterNot { (it as? PackageParser.PackageParserException)?.error ==
                        PackageManager.INSTALL_PARSE_FAILED_SKIPPED }

        if (exceptions.isEmpty()) return

        throw AssertionError("verify failed with ${exceptions.size} errors:\n" +
                exceptions.joinToString(separator = "\n") { it.message.orEmpty() })
    }
}

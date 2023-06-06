// Copyright (C) 2023 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package api

import (
	"testing"

	"android/soong/android"
	"android/soong/bp2build"
	"android/soong/java"
)

func runCombinedApisTestCaseWithRegistrationCtxFunc(t *testing.T, tc bp2build.Bp2buildTestCase, registrationCtxFunc func(ctx android.RegistrationContext)) {
	t.Helper()
	(&tc).ModuleTypeUnderTest = "combined_apis"
	(&tc).ModuleTypeUnderTestFactory = combinedApisModuleFactory
	bp2build.RunBp2BuildTestCase(t, registrationCtxFunc, tc)
}

func runCombinedApisTestCase(t *testing.T, tc bp2build.Bp2buildTestCase) {
	t.Helper()
	runCombinedApisTestCaseWithRegistrationCtxFunc(t, tc, func(ctx android.RegistrationContext) {
		ctx.RegisterModuleType("java_defaults", java.DefaultsFactory)
	})
}

func TestCombinedApisGeneral(t *testing.T) {
	runCombinedApisTestCase(t, bp2build.Bp2buildTestCase{
		Description: "combined_apis, general case",
		Blueprint: `combined_apis {
    name: "foo",
    bootclasspath: ["bcp"],
    system_server_classpath: ["ssc"],
}
`,
		Filesystem: map[string]string{
			"a/Android.bp": `
			java_defaults {
				name: "android.jar_defaults",
			}
			`,
		},
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("merged_txts", "foo-current.txt", bp2build.AttrNameToString{
				"scope": `"public"`,
				"base":  `":non-updatable-current.txt__BP2BUILD__MISSING__DEP"`,
				"deps":  `[":bcp__BP2BUILD__MISSING__DEP"]`,
			}),
			bp2build.MakeBazelTargetNoRestrictions("merged_txts", "foo-system-current.txt", bp2build.AttrNameToString{
				"scope": `"system"`,
				"base":  `":non-updatable-system-current.txt__BP2BUILD__MISSING__DEP"`,
				"deps":  `[":bcp__BP2BUILD__MISSING__DEP"]`,
			}),
			bp2build.MakeBazelTargetNoRestrictions("merged_txts", "foo-module-lib-current.txt", bp2build.AttrNameToString{
				"scope": `"module-lib"`,
				"base":  `":non-updatable-module-lib-current.txt__BP2BUILD__MISSING__DEP"`,
				"deps":  `[":bcp__BP2BUILD__MISSING__DEP"]`,
			}),
			bp2build.MakeBazelTargetNoRestrictions("merged_txts", "foo-system-server-current.txt", bp2build.AttrNameToString{
				"scope": `"system-server"`,
				"base":  `":non-updatable-system-server-current.txt__BP2BUILD__MISSING__DEP"`,
				"deps":  `[":ssc__BP2BUILD__MISSING__DEP"]`,
			}),
		},
	})
}

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
		ctx.RegisterModuleType("java_sdk_library", java.SdkLibraryFactory)
		ctx.RegisterModuleType("filegroup", android.FileGroupFactory)
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

java_sdk_library {
		name: "bcp",
		srcs: ["a.java", "b.java"],
		shared_library: false,
}
java_sdk_library {
		name: "ssc",
		srcs: ["a.java", "b.java"],
		shared_library: false,
}
filegroup {
    name: "non-updatable-current.txt",
    srcs: ["current.txt"],
}
filegroup {
    name: "non-updatable-system-current.txt",
    srcs: ["system-current.txt"],
}
filegroup {
    name: "non-updatable-module-lib-current.txt",
    srcs: ["system-removed.txt"],
}
filegroup {
    name: "non-updatable-system-server-current.txt",
    srcs: ["system-lint-baseline.txt"],
}
`,
		Filesystem: map[string]string{
			"a/Android.bp": `
			java_defaults {
				name: "android.jar_defaults",
			}
			`,
			"api/current.txt":        "",
			"api/removed.txt":        "",
			"api/system-current.txt": "",
			"api/system-removed.txt": "",
			"api/test-current.txt":   "",
			"api/test-removed.txt":   "",
		},
		StubbedBuildDefinitions:    []string{"bcp", "ssc", "non-updatable-current.txt", "non-updatable-system-current.txt", "non-updatable-module-lib-current.txt", "non-updatable-system-server-current.txt"},
		ExpectedHandcraftedModules: []string{"foo-current.txt", "foo-system-current.txt", "foo-module-lib-current.txt", "foo-system-server-current.txt"},
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("merged_txts", "foo-current.txt", bp2build.AttrNameToString{
				"scope": `"public"`,
				"base":  `":non-updatable-current.txt"`,
				"deps":  `[":bcp"]`,
			}),
			bp2build.MakeBazelTargetNoRestrictions("merged_txts", "foo-system-current.txt", bp2build.AttrNameToString{
				"scope": `"system"`,
				"base":  `":non-updatable-system-current.txt"`,
				"deps":  `[":bcp"]`,
			}),
			bp2build.MakeBazelTargetNoRestrictions("merged_txts", "foo-module-lib-current.txt", bp2build.AttrNameToString{
				"scope": `"module-lib"`,
				"base":  `":non-updatable-module-lib-current.txt"`,
				"deps":  `[":bcp"]`,
			}),
			bp2build.MakeBazelTargetNoRestrictions("merged_txts", "foo-system-server-current.txt", bp2build.AttrNameToString{
				"scope": `"system-server"`,
				"base":  `":non-updatable-system-server-current.txt"`,
				"deps":  `[":ssc"]`,
			}),
		},
	})
}

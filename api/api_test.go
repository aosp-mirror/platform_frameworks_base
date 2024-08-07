// Copyright (C) 2024 The Android Open Source Project
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
	"android/soong/android"
	"android/soong/java"
	"fmt"
	"testing"

	"github.com/google/blueprint/proptools"
)

var prepareForTestWithCombinedApis = android.GroupFixturePreparers(
	android.FixtureRegisterWithContext(registerBuildComponents),
	java.PrepareForTestWithJavaBuildComponents,
	android.FixtureAddTextFile("a/Android.bp", gatherRequiredDepsForTest()),
	java.PrepareForTestWithJavaSdkLibraryFiles,
	android.FixtureMergeMockFs(android.MockFS{
		"a/api/current.txt":            nil,
		"a/api/removed.txt":            nil,
		"a/api/system-current.txt":     nil,
		"a/api/system-removed.txt":     nil,
		"a/api/test-current.txt":       nil,
		"a/api/test-removed.txt":       nil,
		"a/api/module-lib-current.txt": nil,
		"a/api/module-lib-removed.txt": nil,
	}),
	android.FixtureModifyProductVariables(func(variables android.FixtureProductVariables) {
		variables.Allow_missing_dependencies = proptools.BoolPtr(true)
	}),
)

func gatherRequiredDepsForTest() string {
	var bp string

	extraLibraryModules := []string{
		"stable.core.platform.api.stubs",
		"core-lambda-stubs",
		"core.current.stubs",
		"ext",
		"framework",
		"android_stubs_current",
		"android_system_stubs_current",
		"android_test_stubs_current",
		"android_test_frameworks_core_stubs_current",
		"android_module_lib_stubs_current",
		"android_system_server_stubs_current",
		"android_stubs_current.from-text",
		"android_system_stubs_current.from-text",
		"android_test_stubs_current.from-text",
		"android_test_frameworks_core_stubs_current.from-text",
		"android_module_lib_stubs_current.from-text",
		"android_system_server_stubs_current.from-text",
		"android_stubs_current.from-source",
		"android_system_stubs_current.from-source",
		"android_test_stubs_current.from-source",
		"android_test_frameworks_core_stubs_current.from-source",
		"android_module_lib_stubs_current.from-source",
		"android_system_server_stubs_current.from-source",
		"android_stubs_current_exportable.from-source",
		"android_system_stubs_current_exportable.from-source",
		"android_test_stubs_current_exportable.from-source",
		"android_module_lib_stubs_current_exportable.from-source",
		"android_system_server_stubs_current_exportable.from-source",
		"stub-annotations",
	}

	extraSdkLibraryModules := []string{
		"framework-virtualization",
		"framework-location",
	}

	extraSystemModules := []string{
		"core-public-stubs-system-modules",
		"core-module-lib-stubs-system-modules",
		"stable-core-platform-api-stubs-system-modules",
	}

	extraFilegroupModules := []string{
		"non-updatable-current.txt",
		"non-updatable-removed.txt",
		"non-updatable-system-current.txt",
		"non-updatable-system-removed.txt",
		"non-updatable-test-current.txt",
		"non-updatable-test-removed.txt",
		"non-updatable-module-lib-current.txt",
		"non-updatable-module-lib-removed.txt",
		"non-updatable-system-server-current.txt",
		"non-updatable-system-server-removed.txt",
		"non-updatable-exportable-current.txt",
		"non-updatable-exportable-removed.txt",
		"non-updatable-exportable-system-current.txt",
		"non-updatable-exportable-system-removed.txt",
		"non-updatable-exportable-test-current.txt",
		"non-updatable-exportable-test-removed.txt",
		"non-updatable-exportable-module-lib-current.txt",
		"non-updatable-exportable-module-lib-removed.txt",
		"non-updatable-exportable-system-server-current.txt",
		"non-updatable-exportable-system-server-removed.txt",
	}

	for _, extra := range extraLibraryModules {
		bp += fmt.Sprintf(`
			java_library {
				name: "%s",
				srcs: ["a.java"],
				sdk_version: "none",
				system_modules: "stable-core-platform-api-stubs-system-modules",
				compile_dex: true,
			}
		`, extra)
	}

	for _, extra := range extraSdkLibraryModules {
		bp += fmt.Sprintf(`
			java_sdk_library {
				name: "%s",
				srcs: ["a.java"],
				public: {
					enabled: true,
				},
				system: {
					enabled: true,
				},
				test: {
					enabled: true,
				},
				module_lib: {
					enabled: true,
				},
				api_packages: [
					"foo",
				],
				sdk_version: "core_current",
				compile_dex: true,
				annotations_enabled: true,
			}
		`, extra)
	}

	for _, extra := range extraFilegroupModules {
		bp += fmt.Sprintf(`
			filegroup {
				name: "%[1]s",
			}
		`, extra)
	}

	for _, extra := range extraSystemModules {
		bp += fmt.Sprintf(`
			java_system_modules {
				name: "%[1]s",
				libs: ["%[1]s-lib"],
			}
			java_library {
				name: "%[1]s-lib",
				sdk_version: "none",
				system_modules: "none",
			}
		`, extra)
	}

	bp += fmt.Sprintf(`
		java_defaults {
			name: "android.jar_defaults",
		}
	`)

	return bp
}

func TestCombinedApisDefaults(t *testing.T) {

	result := android.GroupFixturePreparers(
		prepareForTestWithCombinedApis,
		java.FixtureWithLastReleaseApis(
			"framework-location", "framework-virtualization", "framework-foo", "framework-bar"),
		android.FixtureModifyProductVariables(func(variables android.FixtureProductVariables) {
			variables.VendorVars = map[string]map[string]string{
				"boolean_var": {
					"for_testing": "true",
				},
			}
		}),
	).RunTestWithBp(t, `
	java_sdk_library {
		name: "framework-foo",
		srcs: ["a.java"],
		public: {
			enabled: true,
		},
		system: {
			enabled: true,
		},
		test: {
			enabled: true,
		},
		module_lib: {
			enabled: true,
		},
		api_packages: [
			"foo",
		],
		sdk_version: "core_current",
		annotations_enabled: true,
	}
	java_sdk_library {
		name: "framework-bar",
		srcs: ["a.java"],
		public: {
			enabled: true,
		},
		system: {
			enabled: true,
		},
		test: {
			enabled: true,
		},
		module_lib: {
			enabled: true,
		},
		api_packages: [
			"foo",
		],
		sdk_version: "core_current",
		annotations_enabled: true,
	}

	combined_apis {
		name: "foo",
		bootclasspath: [
			"framework-bar",
		] + select(boolean_var_for_testing(), {
			true: [
				"framework-foo",
			],
			default: [],
		}),
	}
	`)

	subModuleDependsOnSelectAppendedModule := java.CheckModuleHasDependency(t,
		result.TestContext, "foo-current.txt", "", "framework-foo")
	android.AssertBoolEquals(t, "Submodule expected to depend on the select-appended module",
		true, subModuleDependsOnSelectAppendedModule)
}

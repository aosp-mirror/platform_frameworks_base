// Copyright (C) 2021 The Android Open Source Project
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
	"fmt"
	"sort"
	"strings"

	"github.com/google/blueprint/proptools"

	"android/soong/android"
	"android/soong/genrule"
	"android/soong/java"
)

const art = "art.module.public.api"
const conscrypt = "conscrypt.module.public.api"
const i18n = "i18n.module.public.api"
const virtualization = "framework-virtualization"
const location = "framework-location"

var core_libraries_modules = []string{art, conscrypt, i18n}

// List of modules that are not yet updatable, and hence they can still compile
// against hidden APIs. These modules are filtered out when building the
// updatable-framework-module-impl (because updatable-framework-module-impl is
// built against module_current SDK). Instead they are directly statically
// linked into the all-framework-module-lib, which is building against hidden
// APIs.
// In addition, the modules in this list are allowed to contribute to test APIs
// stubs.
var non_updatable_modules = []string{virtualization, location}

// The intention behind this soong plugin is to generate a number of "merged"
// API-related modules that would otherwise require a large amount of very
// similar Android.bp boilerplate to define. For example, the merged current.txt
// API definitions (created by merging the non-updatable current.txt with all
// the module current.txts). This simplifies the addition of new android
// modules, by reducing the number of genrules etc a new module must be added to.

// The properties of the combined_apis module type.
type CombinedApisProperties struct {
	// Module libraries in the bootclasspath
	Bootclasspath []string
	// Module libraries on the bootclasspath if include_nonpublic_framework_api is true.
	Conditional_bootclasspath []string
	// Module libraries in system server
	System_server_classpath []string
}

type CombinedApis struct {
	android.ModuleBase
	android.DefaultableModuleBase

	properties CombinedApisProperties
}

func init() {
	registerBuildComponents(android.InitRegistrationContext)
}

func registerBuildComponents(ctx android.RegistrationContext) {
	ctx.RegisterModuleType("combined_apis", combinedApisModuleFactory)
	ctx.RegisterModuleType("combined_apis_defaults", CombinedApisModuleDefaultsFactory)
}

var PrepareForCombinedApisTest = android.FixtureRegisterWithContext(registerBuildComponents)

func (a *CombinedApis) GenerateAndroidBuildActions(ctx android.ModuleContext) {
}

type genruleProps struct {
	Name       *string
	Cmd        *string
	Dists      []android.Dist
	Out        []string
	Srcs       []string
	Tools      []string
	Visibility []string
}

type libraryProps struct {
	Name        *string
	Sdk_version *string
	Static_libs []string
	Visibility  []string
	Defaults    []string
}

type fgProps struct {
	Name       *string
	Srcs       []string
	Visibility []string
}

type defaultsProps struct {
	Name                *string
	Api_surface         *string
	Api_contributions   []string
	Defaults_visibility []string
	Previous_api        *string
}

// Struct to pass parameters for the various merged [current|removed].txt file modules we create.
type MergedTxtDefinition struct {
	// "current.txt" or "removed.txt"
	TxtFilename string
	// Filename in the new dist dir. "android.txt" or "android-removed.txt"
	DistFilename string
	// The module for the non-updatable / non-module part of the api.
	BaseTxt string
	// The list of modules that are relevant for this merged txt.
	Modules []string
	// The output tag for each module to use.e.g. {.public.api.txt} for current.txt
	ModuleTag string
	// public, system, module-lib or system-server
	Scope string
}

func createMergedTxt(ctx android.LoadHookContext, txt MergedTxtDefinition) {
	metalavaCmd := "$(location metalava)"
	// Silence reflection warnings. See b/168689341
	metalavaCmd += " -J--add-opens=java.base/java.util=ALL-UNNAMED "
	metalavaCmd += " --quiet merge-signatures --format=v2 "

	filename := txt.TxtFilename
	if txt.Scope != "public" {
		filename = txt.Scope + "-" + filename
	}
	moduleName := ctx.ModuleName() + "-" + filename

	props := genruleProps{}
	props.Name = proptools.StringPtr(moduleName)
	props.Tools = []string{"metalava"}
	props.Out = []string{filename}
	props.Cmd = proptools.StringPtr(metalavaCmd + "$(in) --out $(out)")
	props.Srcs = append([]string{txt.BaseTxt}, createSrcs(txt.Modules, txt.ModuleTag)...)
	props.Dists = []android.Dist{
		{
			Targets: []string{"droidcore"},
			Dir:     proptools.StringPtr("api"),
			Dest:    proptools.StringPtr(filename),
		},
		{
			Targets: []string{"api_txt", "sdk"},
			Dir:     proptools.StringPtr("apistubs/android/" + txt.Scope + "/api"),
			Dest:    proptools.StringPtr(txt.DistFilename),
		},
	}
	props.Visibility = []string{"//visibility:public"}
	ctx.CreateModule(genrule.GenRuleFactory, &props)
}

func createMergedAnnotationsFilegroups(ctx android.LoadHookContext, modules, system_server_modules []string) {
	for _, i := range []struct {
		name    string
		tag     string
		modules []string
	}{
		{
			name:    "all-modules-public-annotations",
			tag:     "{.public.annotations.zip}",
			modules: modules,
		}, {
			name:    "all-modules-system-annotations",
			tag:     "{.system.annotations.zip}",
			modules: modules,
		}, {
			name:    "all-modules-module-lib-annotations",
			tag:     "{.module-lib.annotations.zip}",
			modules: modules,
		}, {
			name:    "all-modules-system-server-annotations",
			tag:     "{.system-server.annotations.zip}",
			modules: system_server_modules,
		},
	} {
		props := fgProps{}
		props.Name = proptools.StringPtr(i.name)
		props.Srcs = createSrcs(i.modules, i.tag)
		ctx.CreateModule(android.FileGroupFactory, &props)
	}
}

func createMergedPublicStubs(ctx android.LoadHookContext, modules []string) {
	props := libraryProps{}
	props.Name = proptools.StringPtr("all-modules-public-stubs")
	props.Static_libs = transformArray(modules, "", ".stubs")
	props.Sdk_version = proptools.StringPtr("module_current")
	props.Visibility = []string{"//frameworks/base"}
	ctx.CreateModule(java.LibraryFactory, &props)
}

func createMergedSystemStubs(ctx android.LoadHookContext, modules []string) {
	// First create the all-updatable-modules-system-stubs
	{
		updatable_modules := removeAll(modules, non_updatable_modules)
		props := libraryProps{}
		props.Name = proptools.StringPtr("all-updatable-modules-system-stubs")
		props.Static_libs = transformArray(updatable_modules, "", ".stubs.system")
		props.Sdk_version = proptools.StringPtr("module_current")
		props.Visibility = []string{"//frameworks/base"}
		ctx.CreateModule(java.LibraryFactory, &props)
	}
	// Now merge all-updatable-modules-system-stubs and stubs from non-updatable modules
	// into all-modules-system-stubs.
	{
		props := libraryProps{}
		props.Name = proptools.StringPtr("all-modules-system-stubs")
		props.Static_libs = transformArray(non_updatable_modules, "", ".stubs.system")
		props.Static_libs = append(props.Static_libs, "all-updatable-modules-system-stubs")
		props.Sdk_version = proptools.StringPtr("module_current")
		props.Visibility = []string{"//frameworks/base"}
		ctx.CreateModule(java.LibraryFactory, &props)
	}
}

func createMergedTestStubsForNonUpdatableModules(ctx android.LoadHookContext) {
	props := libraryProps{}
	props.Name = proptools.StringPtr("all-non-updatable-modules-test-stubs")
	props.Static_libs = transformArray(non_updatable_modules, "", ".stubs.test")
	props.Sdk_version = proptools.StringPtr("module_current")
	props.Visibility = []string{"//frameworks/base"}
	ctx.CreateModule(java.LibraryFactory, &props)
}

func createMergedFrameworkImpl(ctx android.LoadHookContext, modules []string) {
	// This module is for the "framework-all" module, which should not include the core libraries.
	modules = removeAll(modules, core_libraries_modules)
	// Remove the modules that belong to non-updatable APEXes since those are allowed to compile
	// against unstable APIs.
	modules = removeAll(modules, non_updatable_modules)
	// First create updatable-framework-module-impl, which contains all updatable modules.
	// This module compiles against module_lib SDK.
	{
		props := libraryProps{}
		props.Name = proptools.StringPtr("updatable-framework-module-impl")
		props.Static_libs = transformArray(modules, "", ".impl")
		props.Sdk_version = proptools.StringPtr("module_current")
		props.Visibility = []string{"//frameworks/base"}
		ctx.CreateModule(java.LibraryFactory, &props)
	}

	// Now create all-framework-module-impl, which contains updatable-framework-module-impl
	// and all non-updatable modules. This module compiles against hidden APIs.
	{
		props := libraryProps{}
		props.Name = proptools.StringPtr("all-framework-module-impl")
		props.Static_libs = transformArray(non_updatable_modules, "", ".impl")
		props.Static_libs = append(props.Static_libs, "updatable-framework-module-impl")
		props.Sdk_version = proptools.StringPtr("core_platform")
		props.Visibility = []string{"//frameworks/base"}
		ctx.CreateModule(java.LibraryFactory, &props)
	}
}

func createMergedFrameworkModuleLibStubs(ctx android.LoadHookContext, modules []string) {
	// The user of this module compiles against the "core" SDK and against non-updatable modules,
	// so remove to avoid dupes.
	modules = removeAll(modules, core_libraries_modules)
	modules = removeAll(modules, non_updatable_modules)
	props := libraryProps{}
	props.Name = proptools.StringPtr("framework-updatable-stubs-module_libs_api")
	props.Static_libs = transformArray(modules, "", ".stubs.module_lib")
	props.Sdk_version = proptools.StringPtr("module_current")
	props.Visibility = []string{"//frameworks/base"}
	ctx.CreateModule(java.LibraryFactory, &props)
}

func createPublicStubsSourceFilegroup(ctx android.LoadHookContext, modules []string) {
	props := fgProps{}
	props.Name = proptools.StringPtr("all-modules-public-stubs-source")
	props.Srcs = createSrcs(modules, "{.public.stubs.source}")
	props.Visibility = []string{"//frameworks/base"}
	ctx.CreateModule(android.FileGroupFactory, &props)
}

func createMergedTxts(ctx android.LoadHookContext, bootclasspath, system_server_classpath []string) {
	var textFiles []MergedTxtDefinition

	tagSuffix := []string{".api.txt}", ".removed-api.txt}"}
	distFilename := []string{"android.txt", "android-removed.txt"}
	for i, f := range []string{"current.txt", "removed.txt"} {
		textFiles = append(textFiles, MergedTxtDefinition{
			TxtFilename:  f,
			DistFilename: distFilename[i],
			BaseTxt:      ":non-updatable-" + f,
			Modules:      bootclasspath,
			ModuleTag:    "{.public" + tagSuffix[i],
			Scope:        "public",
		})
		textFiles = append(textFiles, MergedTxtDefinition{
			TxtFilename:  f,
			DistFilename: distFilename[i],
			BaseTxt:      ":non-updatable-system-" + f,
			Modules:      bootclasspath,
			ModuleTag:    "{.system" + tagSuffix[i],
			Scope:        "system",
		})
		textFiles = append(textFiles, MergedTxtDefinition{
			TxtFilename:  f,
			DistFilename: distFilename[i],
			BaseTxt:      ":non-updatable-module-lib-" + f,
			Modules:      bootclasspath,
			ModuleTag:    "{.module-lib" + tagSuffix[i],
			Scope:        "module-lib",
		})
		textFiles = append(textFiles, MergedTxtDefinition{
			TxtFilename:  f,
			DistFilename: distFilename[i],
			BaseTxt:      ":non-updatable-system-server-" + f,
			Modules:      system_server_classpath,
			ModuleTag:    "{.system-server" + tagSuffix[i],
			Scope:        "system-server",
		})
	}
	for _, txt := range textFiles {
		createMergedTxt(ctx, txt)
	}
}

func createApiContributionDefaults(ctx android.LoadHookContext, modules []string) {
	defaultsSdkKinds := []android.SdkKind{
		android.SdkPublic, android.SdkSystem, android.SdkModule,
	}
	for _, sdkKind := range defaultsSdkKinds {
		props := defaultsProps{}
		props.Name = proptools.StringPtr(
			sdkKind.DefaultJavaLibraryName() + "_contributions")
		if sdkKind == android.SdkModule {
			props.Name = proptools.StringPtr(
				sdkKind.DefaultJavaLibraryName() + "_contributions_full")
		}
		props.Api_surface = proptools.StringPtr(sdkKind.String())
		apiSuffix := ""
		if sdkKind != android.SdkPublic {
			apiSuffix = "." + strings.ReplaceAll(sdkKind.String(), "-", "_")
		}
		props.Api_contributions = transformArray(
			modules, "", fmt.Sprintf(".stubs.source%s.api.contribution", apiSuffix))
		props.Defaults_visibility = []string{"//visibility:public"}
		props.Previous_api = proptools.StringPtr(":android.api.public.latest")
		ctx.CreateModule(java.DefaultsFactory, &props)
	}
}

func createFullApiLibraries(ctx android.LoadHookContext) {
	javaLibraryNames := []string{
		"android_stubs_current",
		"android_system_stubs_current",
		"android_test_stubs_current",
		"android_test_frameworks_core_stubs_current",
		"android_module_lib_stubs_current",
		"android_system_server_stubs_current",
	}

	for _, libraryName := range javaLibraryNames {
		props := libraryProps{}
		props.Name = proptools.StringPtr(libraryName)
		staticLib := libraryName + ".from-source"
		if ctx.Config().BuildFromTextStub() {
			staticLib = libraryName + ".from-text"
		}
		props.Static_libs = []string{staticLib}
		props.Defaults = []string{"android.jar_defaults"}
		props.Visibility = []string{"//visibility:public"}

		ctx.CreateModule(java.LibraryFactory, &props)
	}
}

func (a *CombinedApis) createInternalModules(ctx android.LoadHookContext) {
	bootclasspath := a.properties.Bootclasspath
	system_server_classpath := a.properties.System_server_classpath
	if ctx.Config().VendorConfig("ANDROID").Bool("include_nonpublic_framework_api") {
		bootclasspath = append(bootclasspath, a.properties.Conditional_bootclasspath...)
		sort.Strings(bootclasspath)
	}
	createMergedTxts(ctx, bootclasspath, system_server_classpath)

	createMergedPublicStubs(ctx, bootclasspath)
	createMergedSystemStubs(ctx, bootclasspath)
	createMergedTestStubsForNonUpdatableModules(ctx)
	createMergedFrameworkModuleLibStubs(ctx, bootclasspath)
	createMergedFrameworkImpl(ctx, bootclasspath)

	createMergedAnnotationsFilegroups(ctx, bootclasspath, system_server_classpath)

	createPublicStubsSourceFilegroup(ctx, bootclasspath)

	createApiContributionDefaults(ctx, bootclasspath)

	createFullApiLibraries(ctx)
}

func combinedApisModuleFactory() android.Module {
	module := &CombinedApis{}
	module.AddProperties(&module.properties)
	android.InitAndroidModule(module)
	android.InitDefaultableModule(module)
	android.AddLoadHook(module, func(ctx android.LoadHookContext) { module.createInternalModules(ctx) })
	return module
}

// Various utility methods below.

// Creates an array of ":<m><tag>" for each m in <modules>.
func createSrcs(modules []string, tag string) []string {
	return transformArray(modules, ":", tag)
}

// Creates an array of "<prefix><m><suffix>", for each m in <modules>.
func transformArray(modules []string, prefix, suffix string) []string {
	a := make([]string, 0, len(modules))
	for _, module := range modules {
		a = append(a, prefix+module+suffix)
	}
	return a
}

func removeAll(s []string, vs []string) []string {
	for _, v := range vs {
		s = remove(s, v)
	}
	return s
}

func remove(s []string, v string) []string {
	s2 := make([]string, 0, len(s))
	for _, sv := range s {
		if sv != v {
			s2 = append(s2, sv)
		}
	}
	return s2
}

// Defaults
type CombinedApisModuleDefaults struct {
	android.ModuleBase
	android.DefaultsModuleBase
}

func CombinedApisModuleDefaultsFactory() android.Module {
	module := &CombinedApisModuleDefaults{}
	module.AddProperties(&CombinedApisProperties{})
	android.InitDefaultsModule(module)
	return module
}

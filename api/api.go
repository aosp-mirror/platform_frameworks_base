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
	"slices"

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
	Bootclasspath proptools.Configurable[[]string]
	// Module libraries on the bootclasspath if include_nonpublic_framework_api is true.
	Conditional_bootclasspath []string
	// Module libraries in system server
	System_server_classpath proptools.Configurable[[]string]
}

type CombinedApis struct {
	android.ModuleBase

	properties CombinedApisProperties
}

func init() {
	registerBuildComponents(android.InitRegistrationContext)
}

func registerBuildComponents(ctx android.RegistrationContext) {
	ctx.RegisterModuleType("combined_apis", combinedApisModuleFactory)
}

var PrepareForCombinedApisTest = android.FixtureRegisterWithContext(registerBuildComponents)

func (a *CombinedApis) apiFingerprintStubDeps(ctx android.BottomUpMutatorContext) []string {
	bootClasspath := a.properties.Bootclasspath.GetOrDefault(ctx, nil)
	systemServerClasspath := a.properties.System_server_classpath.GetOrDefault(ctx, nil)
	var ret []string
	ret = append(
		ret,
		transformArray(bootClasspath, "", ".stubs")...,
	)
	ret = append(
		ret,
		transformArray(bootClasspath, "", ".stubs.system")...,
	)
	ret = append(
		ret,
		transformArray(bootClasspath, "", ".stubs.module_lib")...,
	)
	ret = append(
		ret,
		transformArray(systemServerClasspath, "", ".stubs.system_server")...,
	)
	return ret
}

func (a *CombinedApis) DepsMutator(ctx android.BottomUpMutatorContext) {
	ctx.AddDependency(ctx.Module(), nil, a.apiFingerprintStubDeps(ctx)...)
}

func (a *CombinedApis) GenerateAndroidBuildActions(ctx android.ModuleContext) {
	ctx.WalkDeps(func(child, parent android.Module) bool {
		if _, ok := child.(java.AndroidLibraryDependency); ok && child.Name() != "framework-res" {
			// Stubs of BCP and SSCP libraries should not have any dependencies on apps
			// This check ensures that we do not run into circular dependencies when UNBUNDLED_BUILD_TARGET_SDK_WITH_API_FINGERPRINT=true
			ctx.ModuleErrorf(
				"Module %s is not a valid dependency of the stub library %s\n."+
					"If this dependency has been added via `libs` of java_sdk_library, please move it to `impl_only_libs`\n",
				child.Name(), parent.Name())
			return false // error detected
		}
		return true
	})

}

type genruleProps struct {
	Name       *string
	Cmd        *string
	Dists      []android.Dist
	Out        []string
	Srcs       proptools.Configurable[[]string]
	Tools      []string
	Visibility []string
}

type libraryProps struct {
	Name            *string
	Sdk_version     *string
	Static_libs     proptools.Configurable[[]string]
	Visibility      []string
	Defaults        []string
	Is_stubs_module *bool
}

type fgProps struct {
	Name       *string
	Srcs       proptools.Configurable[[]string]
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
	Modules proptools.Configurable[[]string]
	// The output tag for each module to use.e.g. {.public.api.txt} for current.txt
	ModuleTag string
	// public, system, module-lib or system-server
	Scope string
}

func createMergedTxt(ctx android.LoadHookContext, txt MergedTxtDefinition, stubsTypeSuffix string, doDist bool) {
	metalavaCmd := "$(location metalava)"
	// Silence reflection warnings. See b/168689341
	metalavaCmd += " -J--add-opens=java.base/java.util=ALL-UNNAMED "
	metalavaCmd += " --quiet merge-signatures --format=v2 "

	filename := txt.TxtFilename
	if txt.Scope != "public" {
		filename = txt.Scope + "-" + filename
	}
	moduleName := ctx.ModuleName() + stubsTypeSuffix + filename

	props := genruleProps{}
	props.Name = proptools.StringPtr(moduleName)
	props.Tools = []string{"metalava"}
	props.Out = []string{filename}
	props.Cmd = proptools.StringPtr(metalavaCmd + "$(in) --out $(out)")
	props.Srcs = proptools.NewSimpleConfigurable([]string{txt.BaseTxt})
	props.Srcs.Append(createSrcs(txt.Modules, txt.ModuleTag))
	if doDist {
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
	}
	props.Visibility = []string{"//visibility:public"}
	ctx.CreateModule(genrule.GenRuleFactory, &props)
}

func createMergedAnnotationsFilegroups(ctx android.LoadHookContext, modules, system_server_modules proptools.Configurable[[]string]) {
	for _, i := range []struct {
		name    string
		tag     string
		modules proptools.Configurable[[]string]
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

func createMergedPublicStubs(ctx android.LoadHookContext, modules proptools.Configurable[[]string]) {
	modules = modules.Clone()
	transformConfigurableArray(modules, "", ".stubs")
	props := libraryProps{}
	props.Name = proptools.StringPtr("all-modules-public-stubs")
	props.Static_libs = modules
	props.Sdk_version = proptools.StringPtr("module_current")
	props.Visibility = []string{"//frameworks/base"}
	props.Is_stubs_module = proptools.BoolPtr(true)
	ctx.CreateModule(java.LibraryFactory, &props)
}

func createMergedPublicExportableStubs(ctx android.LoadHookContext, modules proptools.Configurable[[]string]) {
	modules = modules.Clone()
	transformConfigurableArray(modules, "", ".stubs.exportable")
	props := libraryProps{}
	props.Name = proptools.StringPtr("all-modules-public-stubs-exportable")
	props.Static_libs = modules
	props.Sdk_version = proptools.StringPtr("module_current")
	props.Visibility = []string{"//frameworks/base"}
	props.Is_stubs_module = proptools.BoolPtr(true)
	ctx.CreateModule(java.LibraryFactory, &props)
}

func createMergedSystemStubs(ctx android.LoadHookContext, modules proptools.Configurable[[]string]) {
	// First create the all-updatable-modules-system-stubs
	{
		updatable_modules := modules.Clone()
		removeAll(updatable_modules, non_updatable_modules)
		transformConfigurableArray(updatable_modules, "", ".stubs.system")
		props := libraryProps{}
		props.Name = proptools.StringPtr("all-updatable-modules-system-stubs")
		props.Static_libs = updatable_modules
		props.Sdk_version = proptools.StringPtr("module_current")
		props.Visibility = []string{"//frameworks/base"}
		props.Is_stubs_module = proptools.BoolPtr(true)
		ctx.CreateModule(java.LibraryFactory, &props)
	}
	// Now merge all-updatable-modules-system-stubs and stubs from non-updatable modules
	// into all-modules-system-stubs.
	{
		static_libs := transformArray(non_updatable_modules, "", ".stubs.system")
		static_libs = append(static_libs, "all-updatable-modules-system-stubs")
		props := libraryProps{}
		props.Name = proptools.StringPtr("all-modules-system-stubs")
		props.Static_libs = proptools.NewSimpleConfigurable(static_libs)
		props.Sdk_version = proptools.StringPtr("module_current")
		props.Visibility = []string{"//frameworks/base"}
		props.Is_stubs_module = proptools.BoolPtr(true)
		ctx.CreateModule(java.LibraryFactory, &props)
	}
}

func createMergedSystemExportableStubs(ctx android.LoadHookContext, modules proptools.Configurable[[]string]) {
	// First create the all-updatable-modules-system-stubs
	{
		updatable_modules := modules.Clone()
		removeAll(updatable_modules, non_updatable_modules)
		transformConfigurableArray(updatable_modules, "", ".stubs.exportable.system")
		props := libraryProps{}
		props.Name = proptools.StringPtr("all-updatable-modules-system-stubs-exportable")
		props.Static_libs = updatable_modules
		props.Sdk_version = proptools.StringPtr("module_current")
		props.Visibility = []string{"//frameworks/base"}
		props.Is_stubs_module = proptools.BoolPtr(true)
		ctx.CreateModule(java.LibraryFactory, &props)
	}
	// Now merge all-updatable-modules-system-stubs and stubs from non-updatable modules
	// into all-modules-system-stubs.
	{
		static_libs := transformArray(non_updatable_modules, "", ".stubs.exportable.system")
		static_libs = append(static_libs, "all-updatable-modules-system-stubs-exportable")
		props := libraryProps{}
		props.Name = proptools.StringPtr("all-modules-system-stubs-exportable")
		props.Static_libs = proptools.NewSimpleConfigurable(static_libs)
		props.Sdk_version = proptools.StringPtr("module_current")
		props.Visibility = []string{"//frameworks/base"}
		props.Is_stubs_module = proptools.BoolPtr(true)
		ctx.CreateModule(java.LibraryFactory, &props)
	}
}

func createMergedTestStubsForNonUpdatableModules(ctx android.LoadHookContext) {
	props := libraryProps{}
	props.Name = proptools.StringPtr("all-non-updatable-modules-test-stubs")
	props.Static_libs = proptools.NewSimpleConfigurable(transformArray(non_updatable_modules, "", ".stubs.test"))
	props.Sdk_version = proptools.StringPtr("module_current")
	props.Visibility = []string{"//frameworks/base"}
	props.Is_stubs_module = proptools.BoolPtr(true)
	ctx.CreateModule(java.LibraryFactory, &props)
}

func createMergedTestExportableStubsForNonUpdatableModules(ctx android.LoadHookContext) {
	props := libraryProps{}
	props.Name = proptools.StringPtr("all-non-updatable-modules-test-stubs-exportable")
	props.Static_libs = proptools.NewSimpleConfigurable(transformArray(non_updatable_modules, "", ".stubs.exportable.test"))
	props.Sdk_version = proptools.StringPtr("module_current")
	props.Visibility = []string{"//frameworks/base"}
	props.Is_stubs_module = proptools.BoolPtr(true)
	ctx.CreateModule(java.LibraryFactory, &props)
}

func createMergedFrameworkImpl(ctx android.LoadHookContext, modules proptools.Configurable[[]string]) {
	modules = modules.Clone()
	// This module is for the "framework-all" module, which should not include the core libraries.
	removeAll(modules, core_libraries_modules)
	// Remove the modules that belong to non-updatable APEXes since those are allowed to compile
	// against unstable APIs.
	removeAll(modules, non_updatable_modules)
	// First create updatable-framework-module-impl, which contains all updatable modules.
	// This module compiles against module_lib SDK.
	{
		transformConfigurableArray(modules, "", ".impl")
		props := libraryProps{}
		props.Name = proptools.StringPtr("updatable-framework-module-impl")
		props.Static_libs = modules
		props.Sdk_version = proptools.StringPtr("module_current")
		props.Visibility = []string{"//frameworks/base"}
		ctx.CreateModule(java.LibraryFactory, &props)
	}

	// Now create all-framework-module-impl, which contains updatable-framework-module-impl
	// and all non-updatable modules. This module compiles against hidden APIs.
	{
		static_libs := transformArray(non_updatable_modules, "", ".impl")
		static_libs = append(static_libs, "updatable-framework-module-impl")
		props := libraryProps{}
		props.Name = proptools.StringPtr("all-framework-module-impl")
		props.Static_libs = proptools.NewSimpleConfigurable(static_libs)
		props.Sdk_version = proptools.StringPtr("core_platform")
		props.Visibility = []string{"//frameworks/base"}
		ctx.CreateModule(java.LibraryFactory, &props)
	}
}

func createMergedFrameworkModuleLibExportableStubs(ctx android.LoadHookContext, modules proptools.Configurable[[]string]) {
	modules = modules.Clone()
	// The user of this module compiles against the "core" SDK and against non-updatable modules,
	// so remove to avoid dupes.
	removeAll(modules, core_libraries_modules)
	removeAll(modules, non_updatable_modules)
	transformConfigurableArray(modules, "", ".stubs.exportable.module_lib")
	props := libraryProps{}
	props.Name = proptools.StringPtr("framework-updatable-stubs-module_libs_api-exportable")
	props.Static_libs = modules
	props.Sdk_version = proptools.StringPtr("module_current")
	props.Visibility = []string{"//frameworks/base"}
	props.Is_stubs_module = proptools.BoolPtr(true)
	ctx.CreateModule(java.LibraryFactory, &props)
}

func createMergedFrameworkModuleLibStubs(ctx android.LoadHookContext, modules proptools.Configurable[[]string]) {
	modules = modules.Clone()
	// The user of this module compiles against the "core" SDK and against non-updatable modules,
	// so remove to avoid dupes.
	removeAll(modules, core_libraries_modules)
	removeAll(modules, non_updatable_modules)
	transformConfigurableArray(modules, "", ".stubs.module_lib")
	props := libraryProps{}
	props.Name = proptools.StringPtr("framework-updatable-stubs-module_libs_api")
	props.Static_libs = modules
	props.Sdk_version = proptools.StringPtr("module_current")
	props.Visibility = []string{"//frameworks/base"}
	props.Is_stubs_module = proptools.BoolPtr(true)
	ctx.CreateModule(java.LibraryFactory, &props)
}

func createMergedFrameworkSystemServerExportableStubs(ctx android.LoadHookContext, bootclasspath, system_server_classpath proptools.Configurable[[]string]) {
	// The user of this module compiles against the "core" SDK and against non-updatable bootclasspathModules,
	// so remove to avoid dupes.
	bootclasspathModules := bootclasspath.Clone()
	removeAll(bootclasspathModules, core_libraries_modules)
	removeAll(bootclasspathModules, non_updatable_modules)
	transformConfigurableArray(bootclasspathModules, "", ".stubs.exportable.module_lib")

	system_server_classpath = system_server_classpath.Clone()
	transformConfigurableArray(system_server_classpath, "", ".stubs.exportable.system_server")

	// Include all the module-lib APIs from the bootclasspath libraries.
	// Then add all the system-server APIs from the service-* libraries.
	bootclasspathModules.Append(system_server_classpath)

	props := libraryProps{}
	props.Name = proptools.StringPtr("framework-updatable-stubs-system_server_api-exportable")
	props.Static_libs = bootclasspathModules
	props.Sdk_version = proptools.StringPtr("system_server_current")
	props.Visibility = []string{"//frameworks/base"}
	props.Is_stubs_module = proptools.BoolPtr(true)
	ctx.CreateModule(java.LibraryFactory, &props)
}

func createPublicStubsSourceFilegroup(ctx android.LoadHookContext, modules proptools.Configurable[[]string]) {
	props := fgProps{}
	props.Name = proptools.StringPtr("all-modules-public-stubs-source")
	props.Srcs = createSrcs(modules, "{.public.stubs.source}")
	props.Visibility = []string{"//frameworks/base"}
	ctx.CreateModule(android.FileGroupFactory, &props)
}

func createMergedTxts(
	ctx android.LoadHookContext,
	bootclasspath proptools.Configurable[[]string],
	system_server_classpath proptools.Configurable[[]string],
	baseTxtModulePrefix string,
	stubsTypeSuffix string,
	doDist bool,
) {
	var textFiles []MergedTxtDefinition

	tagSuffix := []string{".api.txt}", ".removed-api.txt}"}
	distFilename := []string{"android.txt", "android-removed.txt"}
	for i, f := range []string{"current.txt", "removed.txt"} {
		textFiles = append(textFiles, MergedTxtDefinition{
			TxtFilename:  f,
			DistFilename: distFilename[i],
			BaseTxt:      ":" + baseTxtModulePrefix + f,
			Modules:      bootclasspath,
			ModuleTag:    "{.public" + tagSuffix[i],
			Scope:        "public",
		})
		textFiles = append(textFiles, MergedTxtDefinition{
			TxtFilename:  f,
			DistFilename: distFilename[i],
			BaseTxt:      ":" + baseTxtModulePrefix + "system-" + f,
			Modules:      bootclasspath,
			ModuleTag:    "{.system" + tagSuffix[i],
			Scope:        "system",
		})
		textFiles = append(textFiles, MergedTxtDefinition{
			TxtFilename:  f,
			DistFilename: distFilename[i],
			BaseTxt:      ":" + baseTxtModulePrefix + "module-lib-" + f,
			Modules:      bootclasspath,
			ModuleTag:    "{.module-lib" + tagSuffix[i],
			Scope:        "module-lib",
		})
		textFiles = append(textFiles, MergedTxtDefinition{
			TxtFilename:  f,
			DistFilename: distFilename[i],
			BaseTxt:      ":" + baseTxtModulePrefix + "system-server-" + f,
			Modules:      system_server_classpath,
			ModuleTag:    "{.system-server" + tagSuffix[i],
			Scope:        "system-server",
		})
	}
	for _, txt := range textFiles {
		createMergedTxt(ctx, txt, stubsTypeSuffix, doDist)
	}
}

func (a *CombinedApis) createInternalModules(ctx android.LoadHookContext) {
	bootclasspath := a.properties.Bootclasspath.Clone()
	system_server_classpath := a.properties.System_server_classpath.Clone()
	if ctx.Config().VendorConfig("ANDROID").Bool("include_nonpublic_framework_api") {
		bootclasspath.AppendSimpleValue(a.properties.Conditional_bootclasspath)
	}
	createMergedTxts(ctx, bootclasspath, system_server_classpath, "non-updatable-", "-", false)
	createMergedTxts(ctx, bootclasspath, system_server_classpath, "non-updatable-exportable-", "-exportable-", true)

	createMergedPublicStubs(ctx, bootclasspath)
	createMergedSystemStubs(ctx, bootclasspath)
	createMergedTestStubsForNonUpdatableModules(ctx)
	createMergedFrameworkModuleLibStubs(ctx, bootclasspath)
	createMergedFrameworkImpl(ctx, bootclasspath)

	createMergedPublicExportableStubs(ctx, bootclasspath)
	createMergedSystemExportableStubs(ctx, bootclasspath)
	createMergedTestExportableStubsForNonUpdatableModules(ctx)
	createMergedFrameworkModuleLibExportableStubs(ctx, bootclasspath)
	createMergedFrameworkSystemServerExportableStubs(ctx, bootclasspath, system_server_classpath)

	createMergedAnnotationsFilegroups(ctx, bootclasspath, system_server_classpath)

	createPublicStubsSourceFilegroup(ctx, bootclasspath)
}

func combinedApisModuleFactory() android.Module {
	module := &CombinedApis{}
	module.AddProperties(&module.properties)
	android.InitAndroidModule(module)
	android.AddLoadHook(module, func(ctx android.LoadHookContext) { module.createInternalModules(ctx) })
	return module
}

// Various utility methods below.

// Creates an array of ":<m><tag>" for each m in <modules>.
func createSrcs(modules proptools.Configurable[[]string], tag string) proptools.Configurable[[]string] {
	result := modules.Clone()
	transformConfigurableArray(result, ":", tag)
	return result
}

// Creates an array of "<prefix><m><suffix>", for each m in <modules>.
func transformArray(modules []string, prefix, suffix string) []string {
	a := make([]string, 0, len(modules))
	for _, module := range modules {
		a = append(a, prefix+module+suffix)
	}
	return a
}

// Creates an array of "<prefix><m><suffix>", for each m in <modules>.
func transformConfigurableArray(modules proptools.Configurable[[]string], prefix, suffix string) {
	modules.AddPostProcessor(func(s []string) []string {
		return transformArray(s, prefix, suffix)
	})
}

func removeAll(s proptools.Configurable[[]string], vs []string) {
	s.AddPostProcessor(func(s []string) []string {
		a := make([]string, 0, len(s))
		for _, module := range s {
			if !slices.Contains(vs, module) {
				a = append(a, module)
			}
		}
		return a
	})
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

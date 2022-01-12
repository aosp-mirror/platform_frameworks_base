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
	"github.com/google/blueprint/proptools"

	"android/soong/android"
	"android/soong/genrule"
)

// The intention behind this soong plugin is to generate a number of "merged"
// API-related modules that would otherwise require a large amount of very
// similar Android.bp boilerplate to define. For example, the merged current.txt
// API definitions (created by merging the non-updatable current.txt with all
// the module current.txts). This simplifies the addition of new android
// modules, by reducing the number of genrules etc a new module must be added to.

// The properties of the combined_apis module type.
type CombinedApisProperties struct {
	// Module libraries that have public APIs
	Public []string
	// Module libraries that have system APIs
	System []string
	// Module libraries that have module_library APIs
	Module_lib []string
	// Module libraries that have system_server APIs
	System_server []string
	// ART module library. The only API library not removed from the filtered api database, because
	// 1) ART apis are available by default to all modules, while other module-to-module deps are
	//    explicit and probably receive more scrutiny anyway
	// 2) The number of ART/libcore APIs is large, so not linting them would create a large gap
	// 3) It's a compromise. Ideally we wouldn't be filtering out any module APIs, and have
	//    per-module lint databases that excludes just that module's APIs. Alas, that's more
	//    difficult to achieve.
	Art_module string
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

// Struct to pass parameters for the various merged [current|removed].txt file modules we create.
type MergedTxtDefinition struct {
	// "current.txt" or "removed.txt"
	TxtFilename string
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
	metalavaCmd += " --quiet --no-banner --format=v2 "

	filename := txt.TxtFilename
	if txt.Scope != "public" {
		filename = txt.Scope + "-" + filename
	}

	props := genruleProps{}
	props.Name = proptools.StringPtr(ctx.ModuleName() + "-" + filename)
	props.Tools = []string{"metalava"}
	props.Out = []string{txt.TxtFilename}
	props.Cmd = proptools.StringPtr(metalavaCmd + "$(in) --api $(out)")
	props.Srcs = createSrcs(txt.BaseTxt, txt.Modules, txt.ModuleTag)
	props.Dists = []android.Dist{
		{
			Targets: []string{"droidcore"},
			Dir:     proptools.StringPtr("api"),
			Dest:    proptools.StringPtr(filename),
		},
		{
			Targets: []string{"sdk"},
			Dir:     proptools.StringPtr("apistubs/android/" + txt.Scope + "/api"),
			Dest:    proptools.StringPtr(txt.TxtFilename),
		},
	}
	props.Visibility = []string{"//visibility:public"}
	ctx.CreateModule(genrule.GenRuleFactory, &props)
}

func createMergedStubsSrcjar(ctx android.LoadHookContext, modules []string) {
	props := genruleProps{}
	props.Name = proptools.StringPtr(ctx.ModuleName() + "-current.srcjar")
	props.Tools = []string{"merge_zips"}
	props.Out = []string{"current.srcjar"}
	props.Cmd = proptools.StringPtr("$(location merge_zips) $(out) $(in)")
	props.Srcs = createSrcs(":api-stubs-docs-non-updatable", modules, "{.public.stubs.source}")
	props.Visibility = []string{"//visibility:private"} // Used by make module in //development, mind
	ctx.CreateModule(genrule.GenRuleFactory, &props)
}

func createFilteredApiVersions(ctx android.LoadHookContext, modules []string) {
	props := genruleProps{}
	props.Name = proptools.StringPtr("api-versions-xml-public-filtered")
	props.Tools = []string{"api_versions_trimmer"}
	props.Out = []string{"api-versions-public-filtered.xml"}
	props.Cmd = proptools.StringPtr("$(location api_versions_trimmer) $(out) $(in)")
	// Note: order matters: first parameter is the full api-versions.xml
	// after that the stubs files in any order
	// stubs files are all modules that export API surfaces EXCEPT ART
	props.Srcs = createSrcs(":framework-doc-stubs{.api_versions.xml}", modules, ".stubs{.jar}")
	props.Dists = []android.Dist{{Targets: []string{"sdk"}}}
	ctx.CreateModule(genrule.GenRuleFactory, &props)
}

func createSrcs(base string, modules []string, tag string) []string {
	a := make([]string, 0, len(modules)+1)
	a = append(a, base)
	for _, module := range modules {
		a = append(a, ":"+module+tag)
	}
	return a
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

func createMergedTxts(ctx android.LoadHookContext, props CombinedApisProperties) {
	var textFiles []MergedTxtDefinition
	tagSuffix := []string{".api.txt}", ".removed-api.txt}"}
	for i, f := range []string{"current.txt", "removed.txt"} {
		textFiles = append(textFiles, MergedTxtDefinition{
			TxtFilename: f,
			BaseTxt:     ":non-updatable-" + f,
			Modules:     props.Public,
			ModuleTag:   "{.public" + tagSuffix[i],
			Scope:       "public",
		})
		textFiles = append(textFiles, MergedTxtDefinition{
			TxtFilename: f,
			BaseTxt:     ":non-updatable-system-" + f,
			Modules:     props.System,
			ModuleTag:   "{.system" + tagSuffix[i],
			Scope:       "system",
		})
		textFiles = append(textFiles, MergedTxtDefinition{
			TxtFilename: f,
			BaseTxt:     ":non-updatable-module-lib-" + f,
			Modules:     props.Module_lib,
			ModuleTag:   "{.module-lib" + tagSuffix[i],
			Scope:       "module-lib",
		})
		textFiles = append(textFiles, MergedTxtDefinition{
			TxtFilename: f,
			BaseTxt:     ":non-updatable-system-server-" + f,
			Modules:     props.System_server,
			ModuleTag:   "{.system-server" + tagSuffix[i],
			Scope:       "system-server",
		})
	}
	for _, txt := range textFiles {
		createMergedTxt(ctx, txt)
	}
}

func (a *CombinedApis) createInternalModules(ctx android.LoadHookContext) {
	createMergedTxts(ctx, a.properties)

	createMergedStubsSrcjar(ctx, a.properties.Public)

	// For the filtered api versions, we prune all APIs except art module's APIs.
	createFilteredApiVersions(ctx, remove(a.properties.Public, a.properties.Art_module))
}

func combinedApisModuleFactory() android.Module {
	module := &CombinedApis{}
	module.AddProperties(&module.properties)
	android.InitAndroidModule(module)
	android.AddLoadHook(module, func(ctx android.LoadHookContext) { module.createInternalModules(ctx) })
	return module
}

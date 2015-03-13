/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.databinding

import org.gradle.api.Plugin
import org.gradle.api.Project
import groovy.lang.Closure
import org.codehaus.groovy.runtime.MethodClosure
import org.gradle.api.Task
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.api.ApplicationVariantImpl
import com.android.build.gradle.internal.variant.ApplicationVariantData
import kotlin.properties.Delegates
import java.net.URLClassLoader
import groovy.lang.MetaClass
import java.io.File
import java.util.ArrayList
import org.gradle.api.internal.tasks.compile.DefaultJavaCompileSpec
import org.gradle.api.tasks.WorkResult
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.DefaultSourceDirectorySet
import org.gradle.api.tasks.compile.CompileOptions
import org.gradle.internal.Factory
import org.gradle.api.AntBuilder
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.tasks.compile.daemon.CompilerDaemonManager
import org.gradle.api.internal.tasks.compile.DefaultJavaCompilerFactory
import javax.tools.JavaCompiler
import javax.tools.ToolProvider
import java.util.Arrays
import org.apache.commons.io.FileUtils
import com.android.databinding.reflection.ModelAnalyzer
import com.android.databinding.writer.JavaFileWriter
import java.io.FileInputStream
import java.io.FileOutputStream
import org.apache.commons.io.IOUtils
import java.io.FileWriter
import java.io.ByteArrayOutputStream
import org.apache.commons.codec.binary.Base64
import com.android.builder.model.ApiVersion
import com.android.databinding.util.Log
import org.gradle.api.Action
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.LibraryVariant
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.variant.BaseVariantData
import com.android.build.gradle.internal.variant.ApkVariantData
import com.android.build.gradle.internal.variant.LibraryVariantData
import com.android.build.gradle.internal.api.LibraryVariantImpl
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.internal.variant.TestVariantData
import com.android.build.gradle.internal.api.TestVariantImpl

class DataBinderPlugin : Plugin<Project> {

    inner class GradleFileWriter(var outputBase: String) : JavaFileWriter() {
        override fun writeToFile(canonicalName: String, contents: String) {
            val f = File("$outputBase/${canonicalName.replaceAll("\\.", "/")}.java")
            log("Asked to write to ${canonicalName}. outputting to:${f.getAbsolutePath()}")
            f.getParentFile().mkdirs()
            f.writeText(contents, "utf-8")
        }
    }

    override fun apply(project: Project?) {
        if (project == null) return
        project.afterEvaluate {
            createXmlProcessor(project)
        }
    }

    fun log(s: String) {
        System.out.println("[qwqw data binding]: $s")
    }

    fun createXmlProcessor(p: Project) {
        val androidExt = p.getExtensions().getByName("android")
        if (androidExt !is BaseExtension) {
            return
        }
        log("project build dir:${p.getBuildDir()}")
        // TODO this will differ per flavor

        if (androidExt is AppExtension) {
            createXmlProcessorForApp(p, androidExt)
        } else if (androidExt is LibraryExtension) {
            createXmlProcessorForLibrary(p, androidExt)
        } else {
            throw RuntimeException("cannot understand android extension. What is it? ${androidExt}")
        }
    }

    fun createXmlProcessorForLibrary(project : Project, lib : LibraryExtension) {
        val sdkDir = lib.getSdkDirectory()
        lib.getTestVariants().forEach { variant ->
            log("test variant $variant. dir name ${variant.getDirName()}")
            val variantData = getVariantData(variant)
            attachXmlProcessor(project, variantData, sdkDir, false)//tests extend apk variant
        }
        lib.getLibraryVariants().forEach { variant ->
            log("lib variant $variant . dir name ${variant.getDirName()}")
            val variantData = getVariantData(variant)
            attachXmlProcessor(project, variantData, sdkDir, true)
        }
    }

    fun getVariantData(appVariant : LibraryVariant) : LibraryVariantData {
        val clazz = javaClass<LibraryVariantImpl>()
        val field = clazz.getDeclaredField("variantData")
        field.setAccessible(true)
        return field.get(appVariant) as LibraryVariantData
    }

    fun getVariantData(testVariant : TestVariant) : TestVariantData {
        val clazz = javaClass<TestVariantImpl>()
        val field = clazz.getDeclaredField("variantData")
        field.setAccessible(true)
        return field.get(testVariant) as TestVariantData
    }

    fun getVariantData(appVariant : ApplicationVariant) : ApplicationVariantData {
        val clazz = javaClass<ApplicationVariantImpl>()
        val field = clazz.getDeclaredField("variantData")
        field.setAccessible(true)
        return field.get(appVariant) as ApplicationVariantData
    }

    fun createXmlProcessorForApp(project : Project, appExt: AppExtension) {
        val sdkDir = appExt.getSdkDirectory()
        appExt.getTestVariants().forEach { testVariant ->
            val variantData = getVariantData(testVariant)
            attachXmlProcessor(project, variantData, sdkDir, false)
        }
        appExt.getApplicationVariants().forEach { appVariant ->
            val variantData = getVariantData(appVariant)
            attachXmlProcessor(project, variantData, sdkDir, false)
        }
    }

    fun attachXmlProcessor(project : Project, variantData : BaseVariantData<*>, sdkDir : File,
            isLibrary : Boolean) {
        val configuration = variantData.getVariantConfiguration()
        val minSdkVersion = configuration.getMinSdkVersion()
        val generateRTask = variantData.generateRClassTask
        val packageName = generateRTask.getPackageForR()
        log("r task name $generateRTask . text symbols output dir: ${generateRTask.getTextSymbolOutputDir()}")
        val fullName = configuration.getFullName()
        val sources = variantData.getJavaSources()
        sources.forEach({
            if (it is FileCollection) {
                it.forEach {
                    log("sources for ${variantData} ${it}}")
                }
            } else {
                log("sources for ${variantData}: ${it}");
            }
        })
        val resourceFolders = arrayListOf(variantData.mergeResourcesTask.getOutputDir())
        log("MERGE RES OUTPUT ${variantData.mergeResourcesTask.getOutputDir()}")
        val codeGenTargetFolder = generateRTask.getSourceOutputDir()
        // TODO unnecessary?

        // TODO attach to test module as well!

        variantData.addJavaSourceFoldersToModel(codeGenTargetFolder)
        val writerOutBase = codeGenTargetFolder.getAbsolutePath();
        val fileWriter = GradleFileWriter(writerOutBase)
        val xmlProcessor = LayoutXmlProcessor(packageName, resourceFolders, fileWriter,
                minSdkVersion.getApiLevel(), isLibrary)
        val processResTask = generateRTask

        val xmlOutDir = "${project.getBuildDir()}/layout-info/${configuration.getDirName()}";
        log("xml output for ${variantData} is ${xmlOutDir}")
        val dataBindingTaskName = "dataBinding${processResTask.getName().capitalize()}"
        log("created task $dataBindingTaskName")
        project.getTasks().create(dataBindingTaskName,
                javaClass<DataBindingProcessLayoutsTask>(),
                object : Action<DataBindingProcessLayoutsTask> {
                    override fun execute(task: DataBindingProcessLayoutsTask) {
                        task.xmlProcessor = xmlProcessor
                        task.sdkDir = sdkDir
                        Log.d { "TASK adding dependency on ${task} for ${processResTask}" }
                        processResTask.dependsOn(task)
                        processResTask.getDependsOn().filterNot { it == task }.forEach {
                            Log.d { "adding dependency on ${it} for ${task}" }
                            task.dependsOn(it)
                        }
                        processResTask.doLast {
                            task.writeFiles(File(xmlOutDir))
                        }
                    }
                });

        if (isLibrary) {
            val packageJarTaskName = "package${fullName.capitalize()}Jar"
            val packageTask = project.getTasks().findByName(packageJarTaskName)
            if (packageTask !is org.gradle.api.tasks.bundling.Jar) {
                throw RuntimeException("cannot find package task in $project $variantData project $packageJarTaskName")
            }
            val excludePattern = "com/android/databinding/layouts/*.*"
            val appPkgAsClass = packageName.replace('.', '/')
            packageTask.exclude(excludePattern)
            packageTask.exclude("$appPkgAsClass/generated/*")
            packageTask.exclude("$appPkgAsClass/BR.*")
            packageTask.exclude(xmlProcessor.getInfoClassFullName().replace('.', '/') + ".class")
            log("excludes ${packageTask.getExcludes()}")
        }
    }
}

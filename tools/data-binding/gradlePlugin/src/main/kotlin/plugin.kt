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

class DataBinderPlugin : Plugin<Project> {

    inner class GradleFileWriter(var outputBase : String) : JavaFileWriter {
        override fun writeToFile(canonicalName: String, contents: String) {
            val f = File("$outputBase/${canonicalName.replaceAll("\\.", "/")}.java")
            log("Asked to write to ${canonicalName}. outputting to:${f.getAbsolutePath()}")
            f.getParentFile().mkdirs()
            f.writeText(contents, "utf-8")
        }
    }

    var xmlProcessor: LayoutXmlProcessor by Delegates.notNull()
    var project : Project by Delegates.notNull()

    var generatedBinderSrc : File by Delegates.notNull()

    var generatedBinderOut : File by Delegates.notNull()

    var androidJar : File by Delegates.notNull()

    var variantData : ApplicationVariantData by Delegates.notNull()

    var codeGenTargetFolder : File by Delegates.notNull()

    var viewBinderSource : File by Delegates.notNull()

    val serializedBinderBundlePath by Delegates.lazy {
        "${codeGenTargetFolder.getAbsolutePath()}/${CompilerChef.RESOURCE_BUNDLE_FILE_NAME}"
    }
    val viewBinderSourceRoot by Delegates.lazy {
        File(project.getBuildDir(), "databinder")
    }


    var fileWriter : GradleFileWriter by Delegates.notNull()

    val viewBinderCompileOutput by Delegates.lazy { File(viewBinderSourceRoot, "out") }

    override fun apply(project: Project?) {
        if (project == null) return
        val generateIntermediateFile = MethodClosure(this, "generateIntermediateFile")
        val preprocessLayoutFiles = MethodClosure(this, "preprocessLayoutFiles")
        this.project = project
        project.afterEvaluate {
            // TODO read from app
            val variants = arrayListOf("Debug")
            xmlProcessor = createXmlProcessor(project)
            log("after eval")
            //processDebugResources
            variants.forEach { variant ->
//                val preTasks = it.getTasksByName("pre${variant}Build", true)
//                preTasks.forEach {
//                    it.doLast (generateAttr)
//                }
                val processResTasks = it.getTasksByName("process${variant}Resources", true)
                processResTasks.forEach {
                    it.doFirst(preprocessLayoutFiles)
                    it.doLast(generateIntermediateFile)
                }
            }
        }
    }

    fun log(s: String) {
        System.out.println("PLOG: $s")
    }

    fun createXmlProcessor(p: Project): LayoutXmlProcessor {
        val ss = p.getExtensions().getByName("android") as AppExtension
        androidJar = File(ss.getSdkDirectory().getAbsolutePath() + "/platforms/${ss.getCompileSdkVersion()}/android.jar")
        log("creating parser!")
        log("project build dir:${p.getBuildDir()}")
        val clazz = javaClass<ApplicationVariantImpl>()
        val field = clazz.getDeclaredField("variantData")
        field.setAccessible(true)
        var appVariant = ss.getApplicationVariants().first { it is ApplicationVariantImpl }
        variantData = field.get(appVariant) as ApplicationVariantData


        // TODO
        val packageName = variantData.generateRClassTask.getPackageForR()
                //"com.com.android.databinding.android.databinding.libraryGen"//variantData.getPackageName()
        //
        val sources = variantData.getJavaSources()
        sources.forEach({
            log("source: ${it}");
        })
        val resourceFolders = arrayListOf(variantData.mergeResourcesTask.getOutputDir())
        log("MERGE RES OUTPUT ${variantData.mergeResourcesTask.getOutputDir()}")
        //TODO
        codeGenTargetFolder = variantData.generateRClassTask.getSourceOutputDir()
        val resGenTargetFolder = variantData.generateRClassTask.getResDir()
        variantData.addJavaSourceFoldersToModel(codeGenTargetFolder)
        variantData.addJavaSourceFoldersToModel(viewBinderSourceRoot)

        val jCompileTask = variantData.javaCompileTask
        val dexTask = variantData.dexTask
        val options = jCompileTask.getOptions()
        log("compile options: ${options.optionMap()}")
        viewBinderSource = File(viewBinderSourceRoot.getAbsolutePath() + "/src")
        viewBinderSource.mkdirs()
        variantData.registerJavaGeneratingTask(project.task("dataBinderDummySourceGenTask", MethodClosure(this,"dummySourceGenTask" )), File(viewBinderSourceRoot.getAbsolutePath() + "/src/"))
        viewBinderCompileOutput.mkdirs()
        log("view binder source will be ${viewBinderSource}")
        log("adding out dir to input files ${viewBinderCompileOutput}")
        var inputFiles = dexTask.getInputFiles()
        var inputDir = dexTask.getInputDir()
        log("current input files for dex are ${inputFiles} or dir ${inputDir}")
        if (inputDir != null && inputFiles == null) {
            inputFiles = arrayListOf(inputDir)
            dexTask.setInputDir(null)
        }
        inputFiles.add(viewBinderCompileOutput)
        dexTask.setInputFiles(inputFiles)
        log("updated dexTask input files ${dexTask.getInputFiles()} vs ${inputFiles} vs dir ${dexTask.getInputDir()}")

        dexTask.doFirst(MethodClosure(this, "preDexAnalysis"))
        val writerOutBase = codeGenTargetFolder.getAbsolutePath();
        fileWriter = GradleFileWriter(writerOutBase)
        return LayoutXmlProcessor(packageName, resourceFolders, fileWriter)
    }



    fun dummySourceGenTask(o : Any?) {
        System.out.println("running dummySourceGenTask")
    }

    fun preDexAnalysis(o : Any?) {
        val jCompileTask = variantData.javaCompileTask
        val dexTask = variantData.dexTask
        log("dex task files: ${dexTask.getInputFiles()} ${dexTask.getInputFiles().javaClass}")
        log("compile CP: ${jCompileTask.getClasspath().getAsPath()}")
        val jarUrl = androidJar.toURI().toURL()
        val androidClassLoader = URLClassLoader(array(jarUrl))
        val cpFiles = arrayListOf<File>()
        cpFiles.addAll(dexTask.getInputFiles())
        cpFiles.addAll(jCompileTask.getClasspath().getFiles())
        //project.task("compileGenerated", MethodClosure(this, "compileGenerated"))
    }

    fun preprocessLayoutFiles(o: Any?) {
        xmlProcessor.processResources()
    }

    fun generateIntermediateFile(o: Any?) {
        xmlProcessor.writeIntermediateFile()
    }
}

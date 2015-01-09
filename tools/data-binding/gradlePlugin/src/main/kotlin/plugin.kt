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
import com.android.databinding.KLayoutParser
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

class DataBinderPlugin : Plugin<Project> {
    var parser: KLayoutParser by Delegates.notNull()
    var project : Project by Delegates.notNull()

    var generatedBinderSrc : File by Delegates.notNull()

    var generatedBinderOut : File by Delegates.notNull()

    var androidJar : File by Delegates.notNull()

    var variantData : ApplicationVariantData by Delegates.notNull()

    val testOut by Delegates.lazy {
        File("app/build/databinder")
    }
    var viewBinderSource : File by Delegates.notNull()
    val viewBinderCompileOutput by Delegates.lazy { File(testOut, "out") }

    override fun apply(project: Project?) {
        if (project == null) return
        val generateAttr = MethodClosure(this, "generateAttr")
        val generateBrFile = MethodClosure(this, "generateBrFile")
        val generateBinders = MethodClosure(this, "generateBinders")
        this.project = project
        project.afterEvaluate {
            // TODO read from app
            val variants = arrayListOf("Debug")
            parser = createKParser(project)
            log("after eval")
            //processDebugResources
            variants.forEach { variant ->
//                val preTasks = it.getTasksByName("pre${variant}Build", true)
//                preTasks.forEach {
//                    it.doLast (generateAttr)
//                }
                val processResTasks = it.getTasksByName("process${variant}Resources", true)
                processResTasks.forEach {
                    it.doFirst (generateAttr)
                }
                val generateSourcesTasks = it.getTasksByName("generate${variant}Sources", true)
                log("generate sources tasks ${generateSourcesTasks}")
                generateSourcesTasks.forEach {
                    it.doFirst(generateBrFile)
                }

                val compileTasks = it.getTasksByName("compile${variant}Java", true)
                log("compile tasks ${compileTasks}")
                compileTasks.forEach {
                    it.doFirst(MethodClosure(this, "cleanBinderOutFolder"))
                    it.doFirst(generateBinders)
                }
            }
        }
    }

    fun log(s: String) {
        System.out.println("PLOG: $s")
    }

    fun createKParser(p: Project): KLayoutParser {
        val ss = p.getExtensions().getByName("android") as AppExtension
        androidJar = File(ss.getSdkDirectory().getAbsolutePath() + "/platforms/${ss.getCompileSdkVersion()}/android.jar")
        log("creating parser!")
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
        val codeGenTargetFolder = variantData.generateRClassTask.getSourceOutputDir()
        val resGenTargetFolder = variantData.generateRClassTask.getResDir()
        variantData.addJavaSourceFoldersToModel(codeGenTargetFolder)
        val jCompileTask = variantData.javaCompileTask
        val dexTask = variantData.dexTask
        val options = jCompileTask.getOptions()
        log("compile options: ${options.optionMap()}")
        viewBinderSource = File(testOut.getAbsolutePath() + "/src/" + packageName.split("\\.").join("/"))
        viewBinderSource.mkdirs()
        variantData.registerJavaGeneratingTask(project.task("dataBinderDummySourceGenTask", MethodClosure(this,"dummySourceGenTask" )), File(testOut.getAbsolutePath() + "/src/"))
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
        return KLayoutParser(packageName, resourceFolders, codeGenTargetFolder, resGenTargetFolder)
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
        val urls = cpFiles.map { it.toURI().toURL() }.copyToArray()
        log("generated urls: ${urls} len: ${urls.size}")
        val classLoader = URLClassLoader(urls, androidClassLoader)
        log("created class loader")
        parser.classAnalyzer = com.android.databinding.util.ClassAnalyzer(classLoader)
        com.android.databinding2.ClassAnalyzer.setClassLoader(classLoader)
        project.task("compileGenerated", MethodClosure(this, "compileGenerated"))
    }
    fun compileGenerated(o : Any?) {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val fileManager = compiler.getStandardFileManager(null, null, null)
        val javaCompileTask = variantData.javaCompileTask
        val dexTask = variantData.dexTask
        parser.analyzeClasses()
        parser.writeViewBinders(viewBinderSource)
        parser.writeDbrFile(viewBinderSource)


        viewBinderCompileOutput.mkdirs()
        val cpFiles = arrayListOf<File>()
//        val jarUrl = File("/Users/yboyar/android/sdk/platforms/android-21/android.jar")
//        log ("jarURL ${jarUrl.getAbsolutePath()} vs androidJar: ${androidJar.getAbsolutePath()}")
        cpFiles.addAll(dexTask.getInputFiles())
        cpFiles.addAll(javaCompileTask.getClasspath().getFiles())
        cpFiles.add(javaCompileTask.getDestinationDir())
        cpFiles.add(androidJar)
        val filesToCompile = viewBinderSource.listFiles().map { it.getAbsolutePath() }
        log("files to compile ${filesToCompile}")
        val fileObjects = fileManager.getJavaFileObjectsFromStrings(filesToCompile)
        val optionList = arrayListOf<String>()
        // set compiler's classpath to be same as the runtime's
        optionList.addAll(Arrays.asList("-classpath",cpFiles.map{it.getAbsolutePath()}.join(":")))
        optionList.add("-verbose")
        optionList.add("-d")
        optionList.add(viewBinderCompileOutput.getAbsolutePath())
        log("compile options: ${optionList}")
        val javac = compiler.getTask(null, fileManager, null, optionList, null, fileObjects) as JavaCompiler.CompilationTask
        val compileResult = javac.call()

        if (!compileResult) {
            throw RuntimeException("cannot compile generated files. see error for details")
        }
    }

    fun generateAttr(o: Any?) {
        parser.processIfNecessary()
        log("generate attr ${o}")
        parser.writeAttrFile()
    }

    fun cleanBinderOutFolder(o : Any?) {
        log("cleaning out folder pre-compile of $o")
        viewBinderSource.mkdirs()
        FileUtils.cleanDirectory(viewBinderSource)
        viewBinderCompileOutput.mkdirs()
        FileUtils.cleanDirectory(viewBinderCompileOutput)
    }

    fun generateBrFile(o: Any?) {
        parser.processIfNecessary()
        log("generating BR ${o}")
        parser.writeBrFile()
        parser.writeViewBinderInterfaces()
    }

    fun generateBinders(o: Any?) {
        log("generating binders ${o}")
//        parser.writeViewBinders()
//        parser.writeDbrFile()
    }
}

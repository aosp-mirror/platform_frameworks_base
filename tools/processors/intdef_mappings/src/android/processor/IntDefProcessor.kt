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

package android.processor

import android.annotation.IntDef
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.NewArrayTree
import com.sun.source.util.SimpleTreeVisitor
import com.sun.source.util.Trees
import java.io.IOException
import java.io.Writer
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.AnnotationValue
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic.Kind
import javax.tools.StandardLocation.SOURCE_OUTPUT
import kotlin.collections.set

/**
 * The IntDefProcessor is intended to generate a mapping from ints to their respective string
 * identifier for each IntDef for use by Winscope or any other tool which requires such a mapping.
 *
 * The processor will run when building :framework-minus-apex-intdefs and dump all the IntDef
 * mappings found in the files that make up the build target as json to outputPath.
 */
class IntDefProcessor : AbstractProcessor() {
    private val outputName = "intDefMapping.json"

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latest()

    // Define what the annotation we care about are for compiler optimization
    override fun getSupportedAnnotationTypes() = LinkedHashSet<String>().apply {
        add(IntDef::class.java.name)
    }

    override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
        // There should only be one matching annotation definition for intDef
        val annotationType = annotations.firstOrNull() ?: return false
        val annotatedElements = roundEnv.getElementsAnnotatedWith(annotationType)

        val annotationTypeToIntDefMapping = annotatedElements.associate { annotatedElement ->
            val type = (annotatedElement as TypeElement).qualifiedName.toString()
            val mapping = generateIntDefMapping(annotatedElement, annotationType)
            val intDef = annotatedElement.getAnnotation(IntDef::class.java)
            type to IntDefMapping(mapping, intDef.flag)
        }

        try {
            outputToFile(annotationTypeToIntDefMapping)
        } catch (e: IOException) {
            error("Failed to write IntDef mappings :: $e")
        }
        return false
    }

    private fun generateIntDefMapping(
        annotatedElement: TypeElement,
        annotationType: TypeElement
    ): Map<Int, String> {
        // LinkedHashMap makes sure ordering is the same as in the code
        val mapping = LinkedHashMap<Int, String>()

        val annotationMirror = annotatedElement.annotationMirrors
                // Should only ever be one matching this condition
                .first { it.annotationType.asElement() == annotationType }

        val value = annotationMirror.elementValues.entries
                .first { entry -> entry.key.simpleName.contentEquals("value") }
                .value

        val trees = Trees.instance(processingEnv)
        val tree = trees.getTree(annotatedElement, annotationMirror, value)

        val identifiers = ArrayList<String>()
        tree.accept(IdentifierVisitor(), identifiers)

        val values = value.value as List<AnnotationValue>

        for (i in identifiers.indices) {
            mapping[values[i].value as Int] = identifiers[i]
        }

        return mapping
    }

    private class IdentifierVisitor : SimpleTreeVisitor<Void, ArrayList<String>>() {
        override fun visitNewArray(node: NewArrayTree, indentifiers: ArrayList<String>): Void? {
            for (initializer in node.initializers) {
                initializer.accept(this, indentifiers)
            }

            return null
        }

        override fun visitMemberSelect(node: MemberSelectTree, indentifiers: ArrayList<String>):
                Void? {
            indentifiers.add(node.identifier.toString())

            return null
        }

        override fun visitIdentifier(node: IdentifierTree, indentifiers: ArrayList<String>): Void? {
            indentifiers.add(node.name.toString())

            return null
        }
    }

    @Throws(IOException::class)
    private fun outputToFile(annotationTypeToIntDefMapping: Map<String, IntDefMapping>) {
        val resource = processingEnv.filer.createResource(
                SOURCE_OUTPUT, "com.android.winscope", outputName)
        val writer = resource.openWriter()
        serializeTo(annotationTypeToIntDefMapping, writer)
        writer.close()
    }

    private fun error(message: String) {
        processingEnv.messager.printMessage(Kind.ERROR, message)
    }

    private fun note(message: String) {
        processingEnv.messager.printMessage(Kind.NOTE, message)
    }

    class IntDefMapping(val mapping: Map<Int, String>, val flag: Boolean) {
        val size
            get() = this.mapping.size

        val entries
            get() = this.mapping.entries
    }

    companion object {
        fun serializeTo(
            annotationTypeToIntDefMapping: Map<String, IntDefMapping>,
            writer: Writer
        ) {
            val indent = "  "

            writer.appendln("{")

            val intDefTypesCount = annotationTypeToIntDefMapping.size
            var currentIntDefTypesCount = 0
            for ((field, intDefMapping) in annotationTypeToIntDefMapping) {
                writer.appendln("""$indent"$field": {""")

                // Start IntDef

                writer.appendln("""$indent$indent"flag": ${intDefMapping.flag},""")

                writer.appendln("""$indent$indent"values": {""")
                intDefMapping.entries.joinTo(writer, separator = ",\n") { (value, identifier) ->
                    """$indent$indent$indent"$value": "$identifier""""
                }
                writer.appendln()
                writer.appendln("$indent$indent}")

                // End IntDef

                writer.append("$indent}")
                if (++currentIntDefTypesCount < intDefTypesCount) {
                    writer.appendln(",")
                } else {
                    writer.appendln("")
                }
            }

            writer.appendln("}")
        }
    }
}

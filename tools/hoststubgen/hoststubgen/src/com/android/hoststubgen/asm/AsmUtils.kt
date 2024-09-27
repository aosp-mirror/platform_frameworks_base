/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.asm

import com.android.hoststubgen.ClassParseException
import com.android.hoststubgen.HostStubGenInternalException
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode


/** Name of the class initializer method. */
const val CLASS_INITIALIZER_NAME = "<clinit>"

/** Descriptor of the class initializer method. */
const val CLASS_INITIALIZER_DESC = "()V"

/** Name of constructors. */
const val CTOR_NAME = "<init>"

/**
 * Find any of [set] from the list of visible / invisible annotations.
 */
fun findAnyAnnotation(
    set: Set<String>,
    visibleAnnotations: List<AnnotationNode>?,
    invisibleAnnotations: List<AnnotationNode>?,
): AnnotationNode? {
    return visibleAnnotations?.find { it.desc in set }
        ?: invisibleAnnotations?.find { it.desc in set }
}

fun ClassNode.findAnyAnnotation(set: Set<String>): AnnotationNode? {
    return findAnyAnnotation(set, this.visibleAnnotations, this.invisibleAnnotations)
}

fun MethodNode.findAnyAnnotation(set: Set<String>): AnnotationNode? {
    return findAnyAnnotation(set, this.visibleAnnotations, this.invisibleAnnotations)
}

fun FieldNode.findAnyAnnotation(set: Set<String>): AnnotationNode? {
    return findAnyAnnotation(set, this.visibleAnnotations, this.invisibleAnnotations)
}

fun findAllAnnotations(
    set: Set<String>,
    visibleAnnotations: List<AnnotationNode>?,
    invisibleAnnotations: List<AnnotationNode>?
): List<AnnotationNode> {
    return (visibleAnnotations ?: emptyList()).filter { it.desc in set } +
            (invisibleAnnotations ?: emptyList()).filter { it.desc in set }
}

fun ClassNode.findAllAnnotations(set: Set<String>): List<AnnotationNode> {
    return findAllAnnotations(set, this.visibleAnnotations, this.invisibleAnnotations)
}

fun MethodNode.findAllAnnotations(set: Set<String>): List<AnnotationNode> {
    return findAllAnnotations(set, this.visibleAnnotations, this.invisibleAnnotations)
}

fun FieldNode.findAllAnnotations(set: Set<String>): List<AnnotationNode> {
    return findAllAnnotations(set, this.visibleAnnotations, this.invisibleAnnotations)
}

fun <T> findAnnotationValueAsObject(
    an: AnnotationNode,
    propertyName: String,
    expectedTypeHumanReadableName: String,
    converter: (Any?) -> T?,
): T? {
    for (i in 0..(an.values?.size ?: 0) - 2 step 2) {
        val name = an.values[i]

        if (name != propertyName) {
            continue
        }
        val value = an.values[i + 1]
        if (value == null) {
            return null
        }

        try {
            return converter(value)
        } catch (e: ClassCastException) {
            throw ClassParseException(
                "The type of '$propertyName' in annotation @${an.desc} must be " +
                        "$expectedTypeHumanReadableName, but is ${value?.javaClass?.canonicalName}")
        }
    }
    return null
}

fun findAnnotationValueAsString(an: AnnotationNode, propertyName: String): String? {
    return findAnnotationValueAsObject(an, propertyName, "String", {it as String})
}

fun findAnnotationValueAsType(an: AnnotationNode, propertyName: String): Type? {
    return findAnnotationValueAsObject(an, propertyName, "Class", {it as Type})
}


val periodOrSlash = charArrayOf('.', '/')

fun getPackageNameFromFullClassName(fullClassName: String): String {
    val pos = fullClassName.lastIndexOfAny(periodOrSlash)
    if (pos == -1) {
        return ""
    } else {
        return fullClassName.substring(0, pos)
    }
}

fun getClassNameFromFullClassName(fullClassName: String): String {
    val pos = fullClassName.lastIndexOfAny(periodOrSlash)
    if (pos == -1) {
        return fullClassName
    } else {
        return fullClassName.substring(pos + 1)
    }
}

fun getOuterClassNameFromFullClassName(fullClassName: String): String {
    val start = fullClassName.lastIndexOfAny(periodOrSlash)
    val end = fullClassName.indexOf('$')
    if (end == -1) {
        return fullClassName.substring(start + 1)
    } else {
        return fullClassName.substring(start + 1, end)
    }
}

/**
 * If [className] is a fully qualified name, just return it.
 * Otherwise, prepend [defaultPackageName].
 */
fun resolveClassNameWithDefaultPackage(className: String, defaultPackageName: String): String {
    if (className.contains('.') || className.contains('/')) {
        return className
    }
    return "$defaultPackageName.$className"
}

fun splitWithLastPeriod(name: String): Pair<String, String>? {
    val pos = name.lastIndexOf('.')
    if (pos < 0) {
        return null
    }
    return Pair(name.substring(0, pos), name.substring(pos + 1))
}

fun String.startsWithAny(vararg prefixes: String): Boolean {
    prefixes.forEach {
        if (this.startsWith(it)) {
            return true
        }
    }
    return false
}

fun String.endsWithAny(vararg suffixes: String): Boolean {
    suffixes.forEach {
        if (this.endsWith(it)) {
            return true
        }
    }
    return false
}

fun String.toJvmClassName(): String {
    return this.replace('.', '/')
}

fun String.toHumanReadableClassName(): String {
    return this.replace('/', '.')
}

fun String.toHumanReadableMethodName(): String {
    return this.replace('/', '.')
}

fun zipEntryNameToClassName(entryFilename: String): String? {
    val suffix = ".class"
    if (!entryFilename.endsWith(suffix)) {
        return null
    }
    return entryFilename.substring(0, entryFilename.length - suffix.length)
}

private val numericalInnerClassName = """.*\$\d+$""".toRegex()

fun isAnonymousInnerClass(cn: ClassNode): Boolean {
    // TODO: Is there a better way?
    return cn.name.matches(numericalInnerClassName)
}

/**
 * Write bytecode to push all the method arguments to the stack.
 * The number of arguments and their type are taken from [methodDescriptor].
 */
fun writeByteCodeToPushArguments(
        methodDescriptor: String,
        writer: MethodVisitor,
        argOffset: Int = 0,
        ) {
    var i = argOffset - 1
    Type.getArgumentTypes(methodDescriptor).forEach { type ->
        i++

        // See https://en.wikipedia.org/wiki/List_of_Java_bytecode_instructions

        // Note, long and double will consume two local variable spaces, so the extra `i++`.
        when (type) {
            Type.VOID_TYPE -> throw HostStubGenInternalException("VOID_TYPE not expected")
            Type.BOOLEAN_TYPE, Type.CHAR_TYPE, Type.BYTE_TYPE, Type.SHORT_TYPE, Type.INT_TYPE
                -> writer.visitVarInsn(Opcodes.ILOAD, i)
            Type.FLOAT_TYPE -> writer.visitVarInsn(Opcodes.FLOAD, i)
            Type.LONG_TYPE -> writer.visitVarInsn(Opcodes.LLOAD, i++)
            Type.DOUBLE_TYPE -> writer.visitVarInsn(Opcodes.DLOAD, i++)
            else -> writer.visitVarInsn(Opcodes.ALOAD, i)
        }
    }
}

/**
 * Write bytecode to "RETURN" that matches the method's return type, according to
 * [methodDescriptor].
 */
fun writeByteCodeToReturn(methodDescriptor: String, writer: MethodVisitor) {
    Type.getReturnType(methodDescriptor).let { type ->
        // See https://en.wikipedia.org/wiki/List_of_Java_bytecode_instructions
        when (type) {
            Type.VOID_TYPE -> writer.visitInsn(Opcodes.RETURN)
            Type.BOOLEAN_TYPE, Type.CHAR_TYPE, Type.BYTE_TYPE, Type.SHORT_TYPE, Type.INT_TYPE
                -> writer.visitInsn(Opcodes.IRETURN)
            Type.FLOAT_TYPE -> writer.visitInsn(Opcodes.FRETURN)
            Type.LONG_TYPE -> writer.visitInsn(Opcodes.LRETURN)
            Type.DOUBLE_TYPE -> writer.visitInsn(Opcodes.DRETURN)
            else -> writer.visitInsn(Opcodes.ARETURN)
        }
    }
}

/**
 * Given a method descriptor, insert an [argType] as the first argument to it.
 */
fun prependArgTypeToMethodDescriptor(methodDescriptor: String, classInternalName: String): String {
    val returnType = Type.getReturnType(methodDescriptor)
    val argTypes = Type.getArgumentTypes(methodDescriptor).toMutableList()

    argTypes.add(0, Type.getType("L" + classInternalName + ";"))

    return Type.getMethodDescriptor(returnType, *argTypes.toTypedArray())
}

/**
 * Return the "visibility" modifier from an `access` integer.
 *
 * (see https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.1-200-E.1)
 */
fun getVisibilityModifier(access: Int): Int {
    return access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED)
}

/**
 * Return true if an `access` integer is "private" or "package private".
 */
fun isVisibilityPrivateOrPackagePrivate(access: Int): Boolean {
    return when (getVisibilityModifier(access)) {
        0 -> true // Package private.
        Opcodes.ACC_PRIVATE -> true
        else -> false
    }
}

enum class Visibility {
    PRIVATE,
    PACKAGE_PRIVATE,
    PROTECTED,
    PUBLIC;

    companion object {
        fun fromAccess(access: Int): Visibility {
            if ((access and Opcodes.ACC_PUBLIC) != 0) {
                return PUBLIC
            }
            if ((access and Opcodes.ACC_PROTECTED) != 0) {
                return PROTECTED
            }
            if ((access and Opcodes.ACC_PRIVATE) != 0) {
                return PRIVATE
            }

            return PACKAGE_PRIVATE
        }
    }
}

fun ClassNode.isEnum(): Boolean {
    return (this.access and Opcodes.ACC_ENUM) != 0
}

fun ClassNode.isAnnotation(): Boolean {
    return (this.access and Opcodes.ACC_ANNOTATION) != 0
}

fun ClassNode.isSynthetic(): Boolean {
    return (this.access and Opcodes.ACC_SYNTHETIC) != 0
}

fun MethodNode.isSynthetic(): Boolean {
    return (this.access and Opcodes.ACC_SYNTHETIC) != 0
}

fun MethodNode.isStatic(): Boolean {
    return (this.access and Opcodes.ACC_STATIC) != 0
}

fun MethodNode.isPublic(): Boolean {
    return (this.access and Opcodes.ACC_PUBLIC) != 0
}

fun MethodNode.isNative(): Boolean {
    return (this.access and Opcodes.ACC_NATIVE) != 0
}

fun MethodNode.isSpecial(): Boolean {
    return CTOR_NAME == this.name || CLASS_INITIALIZER_NAME == this.name
}

fun FieldNode.isEnum(): Boolean {
    return (this.access and Opcodes.ACC_ENUM) != 0
}

fun FieldNode.isSynthetic(): Boolean {
    return (this.access and Opcodes.ACC_SYNTHETIC) != 0
}

fun ClassNode.getVisibility(): Visibility {
    return Visibility.fromAccess(this.access)
}

fun MethodNode.getVisibility(): Visibility {
    return Visibility.fromAccess(this.access)
}

fun FieldNode.getVisibility(): Visibility {
    return Visibility.fromAccess(this.access)
}

/** Return the [access] flags without the visibility */
fun clearVisibility(access: Int): Int {
    return access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE).inv()
}

/** Return the visibility part of the [access] flags */
fun getVisibility(access: Int): Int {
    return access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE)
}


/*

Dump of the members of TinyFrameworkEnumSimple:

class com/android/hoststubgen/test/tinyframework/TinyFrameworkEnumSimple	keep
  field Cat	keep (ACC_PUBLIC, ACC_STATIC, ACC_FINAL, ACC_ENUM)
  field Dog	keep
  field $VALUES	keep (ACC_PRIVATE, ACC_STATIC, ACC_FINAL, ACC_SYNTHETIC)

  method values	()[Lcom/android/hoststubgen/test/tinyframework/TinyFrameworkEnumSimple;	keep
    ^- NOT synthetic (ACC_PUBLIC, ACC_STATIC)
  method valueOf	(Ljava/lang/String;)Lcom/android/hoststubgen/test/tinyframework/TinyFrameworkEnumSimple;	keep
    ^- NOT synthetic (ACC_PUBLIC, ACC_STATIC)
  method <init>	(Ljava/lang/String;I)V	keep
    ^- NOT synthetic (ACC_PRIVATE)

  method $values	()[Lcom/android/hoststubgen/test/tinyframework/TinyFrameworkEnumSimple;	keep
     (ACC_PRIVATE, ACC_STATIC, ACC_SYNTHETIC)
  method <clinit>	()V	keep

Dump of the members of TinyFrameworkEnumSimple:

class com/android/hoststubgen/test/tinyframework/TinyFrameworkEnumComplex	keep
  field RED	keep
  field BLUE	keep
  field GREEN	keep
  field mLongName	keep
  field mShortName	keep
  field $VALUES	keep
  method values	()[Lcom/android/hoststubgen/test/tinyframework/TinyFrameworkEnumComplex;	keep
  method valueOf	(Ljava/lang/String;)Lcom/android/hoststubgen/test/tinyframework/TinyFrameworkEnumComplex;	keep
  method <init>	(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V	keep
  method getLongName	()Ljava/lang/String;	keep
  method getShortName	()Ljava/lang/String;	keep
  method $values	()[Lcom/android/hoststubgen/test/tinyframework/TinyFrameworkEnumComplex;	keep
  method <clinit>	()V	keep

 */

fun isAutoGeneratedEnumMember(mn: MethodNode): Boolean {
    if (mn.isSynthetic()) {
        return true
    }
    if (mn.name == "<init>" && mn.desc == "(Ljava/lang/String;I)V") {
        return true
    }
    if (mn.name == "<clinit>" && mn.desc == "()V") {
        return true
    }
    if (mn.name == "values" && mn.desc.startsWith("()")) {
        return true
    }
    if (mn.name == "valueOf" && mn.desc.startsWith("(Ljava/lang/String;)")) {
        return true
    }

    return false
}

fun isAutoGeneratedEnumMember(fn: FieldNode): Boolean {
    if (fn.isSynthetic() || fn.isEnum()) {
        return true
    }
    return false
}

/**
 * Class to help handle [ClassVisitor], [MethodVisitor] and [FieldVisitor] in a unified way.
 */
abstract class UnifiedVisitor {
    abstract fun visitAnnotation(descriptor: String, visible: Boolean)

    companion object {
        fun on(target: ClassVisitor): UnifiedVisitor {
            return object : UnifiedVisitor() {
                override fun visitAnnotation(descriptor: String, visible: Boolean) {
                    target.visitAnnotation(descriptor, visible)
                }
            }
        }

        fun on(target: MethodVisitor): UnifiedVisitor {
            return object : UnifiedVisitor() {
                override fun visitAnnotation(descriptor: String, visible: Boolean) {
                    target.visitAnnotation(descriptor, visible)
                }
            }
        }

        fun on(target: FieldVisitor): UnifiedVisitor {
            return object : UnifiedVisitor() {
                override fun visitAnnotation(descriptor: String, visible: Boolean) {
                    target.visitAnnotation(descriptor, visible)
                }
            }
        }
    }
}

/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.platform.test.ravenwood.ravenizer.adapter

import android.platform.test.ravenwood.RavenwoodAwareTestRunner
import com.android.hoststubgen.ClassParseException
import com.android.hoststubgen.asm.CLASS_INITIALIZER_DESC
import com.android.hoststubgen.asm.CLASS_INITIALIZER_NAME
import com.android.hoststubgen.asm.CTOR_NAME
import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.asm.findAnnotationValueAsType
import com.android.hoststubgen.asm.findAnyAnnotation
import com.android.hoststubgen.asm.toHumanReadableClassName
import com.android.hoststubgen.log
import com.android.hoststubgen.visitors.OPCODE_VERSION
import com.android.platform.test.ravenwood.ravenizer.RavenizerInternalException
import com.android.platform.test.ravenwood.ravenizer.classRuleAnotType
import com.android.platform.test.ravenwood.ravenizer.isTestLookingClass
import com.android.platform.test.ravenwood.ravenizer.innerRunnerAnotType
import com.android.platform.test.ravenwood.ravenizer.noRavenizerAnotType
import com.android.platform.test.ravenwood.ravenizer.ravenwoodTestRunnerType
import com.android.platform.test.ravenwood.ravenizer.ruleAnotType
import com.android.platform.test.ravenwood.ravenizer.runWithAnotType
import com.android.platform.test.ravenwood.ravenizer.testRuleType
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.tree.ClassNode

/**
 * Class visitor to update the RunWith and inject some necessary rules.
 *
 * - Change the @RunWith(RavenwoodAwareTestRunner.class).
 * - If the original class has a @RunWith(...), then change it to an @OrigRunWith(...).
 * - Add RavenwoodAwareTestRunner's member rules as junit rules.
 * - Update the order of the existing JUnit rules to make sure they don't use the MIN or MAX.
 */
class RunnerRewritingAdapter private constructor(
    protected val classes: ClassNodes,
    nextVisitor: ClassVisitor,
) : ClassVisitor(OPCODE_VERSION, nextVisitor) {
    /** Arbitrary cut-off point when deciding whether to change the order or an existing rule.*/
    val RULE_ORDER_TWEAK_CUTOFF = 1973020500

    /** Current class's internal name */
    lateinit var classInternalName: String

    /** [ClassNode] for the current class */
    lateinit var classNode: ClassNode

    /** True if this visitor is generating code. */
    var isGeneratingCode = false

    /** Run a [block] with [isGeneratingCode] set to true. */
    private inline fun <T> generateCode(block: () -> T): T {
        isGeneratingCode = true
        try {
            return block()
        } finally {
            isGeneratingCode = false
        }
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
        ) {
        classInternalName = name!!
        classNode = classes.getClass(name)
        if (!isTestLookingClass(classes, name)) {
            throw RavenizerInternalException("This adapter shouldn't be used for non-test class")
        }
        super.visit(version, access, name, signature, superName, interfaces)

        generateCode {
            injectRunWithAnnotation()
            if (!classes.hasClassInitializer(classInternalName)) {
                injectStaticInitializer()
            }
            injectRules()
        }
    }

    /**
     * Remove the original @RunWith annotation.
     */
    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
        if (!isGeneratingCode && runWithAnotType.desc == descriptor) {
            return null
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        val fallback = super.visitField(access, name, descriptor, signature, value)
        if (isGeneratingCode) {
            return fallback
        }
        return FieldRuleOrderRewriter(name, fallback)
    }

    /** Inject an empty <clinit>. The body will be injected by [visitMethod]. */
    private fun injectStaticInitializer() {
        visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
            CLASS_INITIALIZER_NAME,
            CLASS_INITIALIZER_DESC,
            null,
            null
        )!!.let { mv ->
            mv.visitCode()
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
    }

    /**
     * Inject `@RunWith(RavenwoodAwareTestRunner.class)`. If the class already has
     * a `@RunWith`, then change it to add a `@OrigRunWith`.
     */
    private fun injectRunWithAnnotation() {
        // Extract the original RunWith annotation and its value.
        val runWith = classNode.findAnyAnnotation(runWithAnotType.descAsSet)
        val runWithClass = runWith?.let { an ->
            findAnnotationValueAsType(an, "value")
        }

        if (runWith != null) {
            if (runWithClass == ravenwoodTestRunnerType.type) {
                // It already uses RavenwoodTestRunner. We'll just keep it, but we need to
                // inject it again because the original one is removed by visitAnnotation().
                log.d("Class ${classInternalName.toHumanReadableClassName()}" +
                        " already uses RavenwoodTestRunner.")
                visitAnnotation(runWithAnotType.desc, true)!!.let { av ->
                    av.visit("value", ravenwoodTestRunnerType)
                    av.visitEnd()
                }
                return
            }
            if (runWithClass == null) {
                throw ClassParseException("@RunWith annotation doesn't have a property \"value\""
                        + " in class ${classInternalName.toHumanReadableClassName()}")
            }

            // Inject an @OrigRunWith.
            visitAnnotation(innerRunnerAnotType.desc, true)!!.let { av ->
                av.visit("value", runWithClass)
                av.visitEnd()
            }
        }

        // Inject a @RunWith(RavenwoodAwareTestRunner.class).
        visitAnnotation(runWithAnotType.desc, true)!!.let { av ->
            av.visit("value", ravenwoodTestRunnerType.type)
            av.visitEnd()
        }
        log.v("Update the @RunWith: ${classInternalName.toHumanReadableClassName()}")
    }

    /*
     Generate the fields and the ctor, which should looks like  this:

  public static final org.junit.rules.TestRule sRavenwoodImplicitClassMinRule;
    descriptor: Lorg/junit/rules/TestRule;
    flags: (0x0019) ACC_PUBLIC, ACC_STATIC, ACC_FINAL
    RuntimeVisibleAnnotations:
      0: #49(#50=I#51)
        org.junit.ClassRule(
          order=-2147483648
        )

  public static final org.junit.rules.TestRule sRavenwoodImplicitClassMaxRule;
    descriptor: Lorg/junit/rules/TestRule;
    flags: (0x0019) ACC_PUBLIC, ACC_STATIC, ACC_FINAL
    RuntimeVisibleAnnotations:
      0: #49(#50=I#52)
        org.junit.ClassRule(
          order=2147483647
        )

  public final org.junit.rules.TestRule sRavenwoodImplicitInstanceMinRule;
    descriptor: Lorg/junit/rules/TestRule;
    flags: (0x0011) ACC_PUBLIC, ACC_FINAL
    RuntimeVisibleAnnotations:
      0: #53(#50=I#51)
        org.junit.Rule(
          order=-2147483648
        )

  public final org.junit.rules.TestRule sRavenwoodImplicitInstanceMaxRule;
    descriptor: Lorg/junit/rules/TestRule;
    flags: (0x0011) ACC_PUBLIC, ACC_FINAL
    RuntimeVisibleAnnotations:
      0: #53(#50=I#52)
        org.junit.Rule(
          order=2147483647
        )
     */

    val sRavenwood_ClassRuleMin = "sRavenwood_ClassRuleMin"
    val sRavenwood_ClassRuleMax = "sRavenwood_ClassRuleMax"
    val mRavenwood_InstRuleMin = "mRavenwood_InstRuleMin"
    val mRavenwood_InstRuleMax = "mRavenwood_InstRuleMax"

    private fun injectRules() {
        injectRule(sRavenwood_ClassRuleMin, true, Integer.MIN_VALUE)
        injectRule(sRavenwood_ClassRuleMax, true, Integer.MAX_VALUE)
        injectRule(mRavenwood_InstRuleMin, false, Integer.MIN_VALUE)
        injectRule(mRavenwood_InstRuleMax, false, Integer.MAX_VALUE)
    }

    private fun injectRule(fieldName: String, isStatic: Boolean, order: Int) {
        visitField(
            ACC_PUBLIC or ACC_FINAL or (if (isStatic) ACC_STATIC else 0),
            fieldName,
            testRuleType.desc,
            null,
            null,
        ).let { fv ->
            val anot = if (isStatic) { classRuleAnotType } else { ruleAnotType }
            fv.visitAnnotation(anot.desc, true).let {
                it.visit("order", order)
                it.visitEnd()
            }
            fv.visitEnd()
        }
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?,
    ): MethodVisitor {
        val next = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (name == CLASS_INITIALIZER_NAME && descriptor == CLASS_INITIALIZER_DESC) {
            return ClassInitializerVisitor(
                access, name, descriptor, signature, exceptions, next)
        }
        if (name == CTOR_NAME) {
            return ConstructorVisitor(
                access, name, descriptor, signature, exceptions, next)
        }
        return next
    }

    /*

  static {};
    descriptor: ()V
    flags: (0x0008) ACC_STATIC
    Code:
      stack=1, locals=0, args_size=0
         0: getstatic     #36                 // Field android/platform/test/ravenwood/RavenwoodAwareTestRunner.RavenwoodImplicitClassMinRule:Lorg/junit/rules/TestRule;
         3: putstatic     #39                 // Field sRavenwoodImplicitClassMinRule:Lorg/junit/rules/TestRule;
         6: getstatic     #42                 // Field android/platform/test/ravenwood/RavenwoodAwareTestRunner.RavenwoodImplicitClassMaxRule:Lorg/junit/rules/TestRule;
         9: putstatic     #45                 // Field sRavenwoodImplicitClassMaxRule:Lorg/junit/rules/TestRule;
        12: return
      LineNumberTable:
        line 33: 0
        line 36: 6
     */
    private inner class ClassInitializerVisitor(
        access: Int,
        val name: String,
        val descriptor: String,
        signature: String?,
        exceptions: Array<String>?,
        next: MethodVisitor?,
    ) : MethodVisitor(OPCODE_VERSION, next) {
        override fun visitCode() {
            visitFieldInsn(Opcodes.GETSTATIC,
                ravenwoodTestRunnerType.internlName,
                RavenwoodAwareTestRunner.IMPLICIT_CLASS_OUTER_RULE_NAME,
                testRuleType.desc
            )
            visitFieldInsn(Opcodes.PUTSTATIC,
                classInternalName,
                sRavenwood_ClassRuleMin,
                testRuleType.desc
            )

            visitFieldInsn(Opcodes.GETSTATIC,
                ravenwoodTestRunnerType.internlName,
                RavenwoodAwareTestRunner.IMPLICIT_CLASS_INNER_RULE_NAME,
                testRuleType.desc
            )
            visitFieldInsn(Opcodes.PUTSTATIC,
                classInternalName,
                sRavenwood_ClassRuleMax,
                testRuleType.desc
            )

            super.visitCode()
        }
    }

    /*
  public com.android.ravenwoodtest.bivalenttest.runnertest.RavenwoodRunnerTest();
    descriptor: ()V
    flags: (0x0001) ACC_PUBLIC
    Code:
      stack=2, locals=1, args_size=1
         0: aload_0
         1: invokespecial #1                  // Method java/lang/Object."<init>":()V
         4: aload_0
         5: getstatic     #7                  // Field android/platform/test/ravenwood/RavenwoodAwareTestRunner.RavenwoodImplicitInstanceMinRule:Lorg/junit/rules/TestRule;
         8: putfield      #13                 // Field sRavenwoodImplicitInstanceMinRule:Lorg/junit/rules/TestRule;
        11: aload_0
        12: getstatic     #18                 // Field android/platform/test/ravenwood/RavenwoodAwareTestRunner.RavenwoodImplicitInstanceMaxRule:Lorg/junit/rules/TestRule;
        15: putfield      #21                 // Field sRavenwoodImplicitInstanceMaxRule:Lorg/junit/rules/TestRule;
        18: return
      LineNumberTable:
        line 31: 0
        line 38: 4
        line 41: 11
      LocalVariableTable:
        Start  Length  Slot  Name   Signature
            0      19     0  this   Lcom/android/ravenwoodtest/bivalenttest/runnertest/RavenwoodRunnerTest;
     */
    private inner class ConstructorVisitor(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?,
        next: MethodVisitor?,
    ) : AdviceAdapter(OPCODE_VERSION, next, ACC_ENUM, name, descriptor) {
        override fun onMethodEnter() {
            visitVarInsn(ALOAD, 0)
            visitFieldInsn(Opcodes.GETSTATIC,
                ravenwoodTestRunnerType.internlName,
                RavenwoodAwareTestRunner.IMPLICIT_INST_OUTER_RULE_NAME,
                testRuleType.desc
            )
            visitFieldInsn(Opcodes.PUTFIELD,
                classInternalName,
                mRavenwood_InstRuleMin,
                testRuleType.desc
            )

            visitVarInsn(ALOAD, 0)
            visitFieldInsn(Opcodes.GETSTATIC,
                ravenwoodTestRunnerType.internlName,
                RavenwoodAwareTestRunner.IMPLICIT_INST_INNER_RULE_NAME,
                testRuleType.desc
            )
            visitFieldInsn(Opcodes.PUTFIELD,
                classInternalName,
                mRavenwood_InstRuleMax,
                testRuleType.desc
            )
        }
    }

    /**
     * Rewrite "order" of the existing junit rules to make sure no rules use a MAX or MIN order.
     *
     * Currently, we do it a hacky way -- use an arbitrary cut-off point, and if the order
     * is larger than that, decrement by 1, and if it's smaller than the negative cut-off point,
     * increment it by 1.
     *
     * (or the arbitrary number is already used.... then we're unlucky, let's change the cut-off
     * point.)
     */
    private inner class FieldRuleOrderRewriter(
        val fieldName: String,
        next: FieldVisitor,
    ) : FieldVisitor(OPCODE_VERSION, next) {
        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
            val fallback = super.visitAnnotation(descriptor, visible)
            if (descriptor != ruleAnotType.desc && descriptor != classRuleAnotType.desc) {
                return fallback
            }
            return RuleOrderRewriter(fallback)
        }

        private inner class RuleOrderRewriter(
            next: AnnotationVisitor,
        ) : AnnotationVisitor(OPCODE_VERSION, next) {
            override fun visit(name: String?, origValue: Any?) {
                if (name != "order") {
                    return super.visit(name, origValue)
                }
                var order = origValue as Int
                if (order == RULE_ORDER_TWEAK_CUTOFF || order == -RULE_ORDER_TWEAK_CUTOFF) {
                    // Oops. If this happens, we'll need to change RULE_ORDER_TWEAK_CUTOFF.
                    // Or, we could scan all the rules in the target jar and find an unused number.
                    // Because rules propagate to subclasses, we'll at least check all the
                    // super classes of the current class.
                    throw RavenizerInternalException(
                        "OOPS: Field $classInternalName.$fieldName uses $order."
                                + " We can't update it.")
                }
                if (order > RULE_ORDER_TWEAK_CUTOFF) {
                    order -= 1
                }
                if (order < -RULE_ORDER_TWEAK_CUTOFF) {
                    order += 1
                }
                super.visit(name, order)
            }
        }
    }

    companion object {
        fun shouldProcess(classes: ClassNodes, className: String): Boolean {
            if (!isTestLookingClass(classes, className)) {
                return false
            }
            // Don't process a class if it has a @NoRavenizer annotation.
            classes.findClass(className)?.let { cn ->
                if (cn.findAnyAnnotation(noRavenizerAnotType.descAsSet) != null) {
                    log.i("Class ${className.toHumanReadableClassName()} has" +
                        " @${noRavenizerAnotType.humanReadableName}. Skipping."
                    )
                    return false
                }
            }
            return true
        }

        fun maybeApply(
            className: String,
            classes: ClassNodes,
            nextVisitor: ClassVisitor,
        ): ClassVisitor {
            if (!shouldProcess(classes, className)) {
                return nextVisitor
            } else {
                return RunnerRewritingAdapter(classes, nextVisitor)
            }
        }
    }
}